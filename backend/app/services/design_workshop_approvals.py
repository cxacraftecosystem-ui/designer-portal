from __future__ import annotations

from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.schemas import design_workshop_approvals as plans
from app.schemas.design_workshop_review_loop import (
    APPROVED,
    NEEDS_REVISION,
    PRE_SUBMISSION,
    SUBMITTED,
)
from app.services.concurrency import gather_reads
from app.services.records import contains

STATES: tuple[str, ...] = ("awaiting", "approved", "handedOn", "returned")

DECISION_READ_LIMIT = 200


def _status(record: Any) -> str:
    value = getattr(record, "status", "")
    return str(getattr(value, "value", value) or "")


async def load_approvable_workshop_or_404(workshop_id: str) -> Any:
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None or record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


def queue_where(state: str, search: str | None = None) -> dict[str, Any]:
    where: dict[str, Any] = {"deletedAt": None, "submissionRound": {"gt": 0}}
    if state == "awaiting":
        where["status"] = PRE_SUBMISSION
    elif state == "approved":
        where["status"] = APPROVED
    elif state == "handedOn":
        where["status"] = SUBMITTED
        where["handedOnAt"] = {"not": None}
    elif state == "returned":
        where["status"] = NEEDS_REVISION
    else:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"{state!r} is not one of the lists on this screen. Ask for awaiting, approved, "
                f"handedOn or returned."
            ),
        )
    term = (search or "").strip()
    if term:
        where["OR"] = [
            {"title": contains(term)},
            {"craftName": contains(term)},
            {"clusterName": contains(term)},
            {"workshopCode": contains(term)},
            {"designerName": contains(term)},
        ]
    return where


def queue_order(state: str) -> list[dict[str, str]]:
    tie = [{"createdAt": "asc"}, {"id": "asc"}]
    if state == "awaiting":
        return [{"lastHandedInAt": "asc"}, *tie]
    if state == "approved":
        return [{"approvedAt": "asc"}, *tie]
    if state == "handedOn":
        return [{"handedOnAt": "desc"}, *tie]
    return [{"reviewedAt": "desc"}, *tie]


async def awaiting_counts() -> dict[str, int]:
    awaiting, to_hand_on = await gather_reads(
        db.designworkshop.count(
            where={"deletedAt": None, "submissionRound": {"gt": 0}, "status": PRE_SUBMISSION}
        ),
        db.designworkshop.count(
            where={"deletedAt": None, "submissionRound": {"gt": 0}, "status": APPROVED}
        ),
    )
    return {"awaiting": awaiting, "toHandOn": to_hand_on}


async def decision_history(workshop_id: str) -> list[dict[str, Any]]:
    rows = await db.reviewlog.find_many(
        where={"recordType": "DESIGN_WORKSHOP", "recordId": workshop_id},
        include={"reviewer": True},
        order={"createdAt": "desc"},
        take=DECISION_READ_LIMIT,
    )
    return plans.decision_history_payload(rows)


async def approval_names(record: Any) -> dict[str, str | None]:
    approved_by = getattr(record, "approvedById", None)
    handed_on_by = getattr(record, "handedOnById", None)
    ids = sorted({uid for uid in (approved_by, handed_on_by) if uid})
    people: dict[str, Any] = {}
    if ids:
        for row in await db.user.find_many(where={"id": {"in": ids}}):
            people[row.id] = row
    approver = people.get(approved_by) if approved_by else None
    role = getattr(approver, "role", None) if approver is not None else None
    return {
        "approvedByName": getattr(approver, "name", None) if approver is not None else None,
        "approvedByRole": str(getattr(role, "value", role)) if role is not None else None,
        "handedOnByName": (
            getattr(people.get(handed_on_by), "name", None) if handed_on_by else None
        ),
    }


async def names_for(records: list[Any]) -> dict[str, str | None]:
    ids = sorted(
        {
            uid
            for record in records
            for uid in (getattr(record, "approvedById", None), getattr(record, "handedOnById", None))
            if uid
        }
    )
    if not ids:
        return {}
    return {row.id: getattr(row, "name", None) for row in await db.user.find_many(where={"id": {"in": ids}})}


def export_payload(row: Any) -> dict[str, Any]:
    generated_by = getattr(row, "generatedBy", None)
    moment = getattr(row, "generatedAt", None)
    return {
        "id": row.id,
        "format": getattr(row, "format", None),
        "templateId": getattr(row, "templateId", None),
        "fileName": getattr(row, "fileName", None),
        "generatedAt": moment.isoformat() if moment else None,
        "generatedByName": getattr(generated_by, "name", None) if generated_by is not None else None,
    }


async def latest_export(workshop_id: str) -> dict[str, Any] | None:
    row = await db.dwreportexport.find_first(
        where={"designWorkshopId": workshop_id},
        include={"generatedBy": True},
        order={"generatedAt": "desc"},
    )
    return export_payload(row) if row is not None else None


async def export_for_hand_on(workshop_id: str, export_id: str, approved_at: Any) -> Any:
    row = await db.dwreportexport.find_unique(where={"id": export_id})
    if row is None or row.designWorkshopId != workshop_id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="That exported report is not one of this workshop's. Pick one of its own reports.",
        )
    generated = getattr(row, "generatedAt", None)
    if approved_at is not None and generated is not None and generated < approved_at:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "That report was generated before this one was approved, so it is not the approved "
                "report. Generate the report again and hand that one on."
            ),
        )
    return row


def hand_on_default_to(entries: list[Any]) -> str:
    for entry in entries:
        if getattr(entry, "stageKey", None) != "REPORT_GENERATION":
            continue
        data = getattr(entry, "data", None)
        value = data.get("submittedTo") if isinstance(data, dict) else None
        text = str(value or "").strip()
        if text:
            return text[: plans.MAX_HANDED_ON_TO_CHARS]
    return plans.DEFAULT_OFFICE


def decision_view(record: Any, *, refusal: str | None) -> dict[str, Any]:
    current = _status(record)
    handed_on = getattr(record, "handedOnAt", None) is not None
    approve_reason = (
        "This report is already approved." if current == APPROVED else plans.approve_refusal(current, handed_on=handed_on)
    )
    hand_on = plans.hand_on_refusal(current, handed_on=handed_on)
    reasons = {
        "approve": approve_reason,
        "revise": plans.revise_refusal(current, handed_on=handed_on),
        "handOn": hand_on[1] if hand_on else None,
    }
    return {
        "mayDecide": {verb: refusal is None and reason is None for verb, reason in reasons.items()},
        "decisionRefusal": refusal,
        "refusals": reasons,
        "reviseKind": plans.revise_kind(current, handed_on=handed_on),
    }
