"""THE APPROVING AUTHORITY'S READS: the queue, the waiting counts, one report, and its decisions.

The owner's requirement ends: *"This iterative review loop continues until the sanctioning authority
is satisfied and clicks 'approve'."* Until 2026-10-10 nothing named that authority (the deferral in
docs/OPEN_FINDINGS.md, closed that day) and APPROVED and SUBMITTED were unreachable. This module is
what the approvals surface READS; what each decision WRITES is planned in
``schemas/design_workshop_approvals``, the wire is ``api/routes/design_workshop_approvals.py``, and
WHO may decide is ``deps.APPROVAL_AUTHORITY_ROLES`` plus rule 7 of ``services/design_workshop_posts``.

=======================================================================================
WHO THE APPROVING AUTHORITY IS, AND THE PRODUCT'S OWN EVIDENCE FOR IT
=======================================================================================

**THE MINISTRY ADMIN AND THE MASTER ADMIN, ESTATE-WIDE, BY ROLE.** Read off the product rather than
invented here (the full table is docs/PERMISSIONS.md §2):

1. **NOT THE ASSISTANT OR REGIONAL DIRECTOR POSTS.** The owner's ruling of 2026-10-09 made both posts
   view-and-monitor, and docs/PERMISSIONS.md §4.8 says no approval route is to be built for either.
2. **THE MINISTRY'S ADMINISTERING TIER.** MINISTRY_ADMIN reads the whole estate, staffs every
   workshop and appoints its inspectors; an RD is refused even that, because "the supervised must not
   choose the supervisor". The ministry desk lays a workshop out as planned, sanctioned, staffed,
   filled in and read back — the signature at the end of that sequence is the ministry's.
3. **THE QUEUE WAS DESIGNED ESTATE-WIDE.** Its predicate — every report handed in at least once and
   not deleted — names no person, and ``schema.prisma`` built an index for exactly that predicate on
   2026-09-13 (``@@index([deletedAt, submissionRound])``).
4. **THE OFFICE IS THE DEVELOPMENT COMMISSIONER (HANDICRAFTS)'S** — the DCH template's own addressee —
   and production holds one Ministry Admin, which is why one person may approve and then hand on.

ADMIN is not in the set: platform administration is not the ministry, the reason the ministry
dashboard leaves it out too.

=======================================================================================
WHAT THIS MODULE DELIBERATELY DOES NOT NAME
=======================================================================================

``tests/test_dw_inspector_scope_gate.py`` sweeps every file under ``app/`` for the inspection scope's
predicates and table AS TEXT. Who inspects a workshop is read here only through
``design_workshop_inspectors.inspector_rows`` (a headcount for the screen) and, in rule 7, through
``design_workshop_posts`` — never through the scope's own names.
"""

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

#: The four tabs of the queue, by the word the client sends.
STATES: tuple[str, ...] = ("awaiting", "approved", "handedOn", "returned")

#: How many decisions one read carries, newest first. A report goes through a handful of rounds; the
#: cap is there so a pathological history cannot make the read unbounded, and it is said out loud.
DECISION_READ_LIMIT = 200


def _status(record: Any) -> str:
    value = getattr(record, "status", "")
    return str(getattr(value, "value", value) or "")


async def load_approvable_workshop_or_404(workshop_id: str) -> Any:
    """The workshop for the approving authority, or 404. **NO ``for_edit`` AND THERE MUST NEVER BE ONE.**

    The caller already stands behind ``require_approving_authority``, and the authority reads the
    whole estate (the ministry register lists every workshop), so there is no per-row scope to test
    and nothing a 404 would hide that the caller cannot already see. A soft-deleted workshop is a 404
    here: nobody decides on a report every list leaves out, and restoring one is an admin's act on
    its own screen.
    """
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None or record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


def queue_where(state: str, search: str | None = None) -> dict[str, Any]:
    """The queue's predicate for one tab — always inside "handed in at least once, not deleted".

    ``{deletedAt: null, submissionRound > 0}`` is the predicate the 2026-09-13 index was built for,
    and every tab narrows it by status rather than replacing it, so a report nobody handed in can
    never appear here. ``handedOn`` asks for a set ``handedOnAt`` — a legacy SUBMITTED row was never
    handed on through this product and is not shown as if it had been. The search is AND-composed
    with the scope through ``OR`` on its own key, as on every sibling list.
    """
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
    """The order each tab is read in — the one a person working through it needs.

    Waiting reports: OLDEST WAIT FIRST, by ``lastHandedInAt`` (Postgres puts the NULLs of reports
    handed in before that column existed LAST in ascending order, which leaves them after every dated
    wait — ``createdAt`` then orders them among themselves). Approved: the oldest approval first, the
    one longest waiting to be handed on. Handed on and returned: the most recent first.
    """
    tie = [{"createdAt": "asc"}, {"id": "asc"}]
    if state == "awaiting":
        return [{"lastHandedInAt": "asc"}, *tie]
    if state == "approved":
        return [{"approvedAt": "asc"}, *tie]
    if state == "handedOn":
        return [{"handedOnAt": "desc"}, *tie]
    return [{"reviewedAt": "desc"}, *tie]


async def awaiting_counts() -> dict[str, int]:
    """``{awaiting, toHandOn}`` — the badge's number and the second figure beside it.

    THE NOTIFICATION, in this product's only shape for one: there is no mailer and no push, and
    ``GET /sanction-orders/awaiting-count`` set the pattern of a count a badge reads. Estate-wide on
    purpose, like the queue.
    """
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
    """Every decision taken on this report, newest first, decoded into what a screen names.

    **THE FIRST READER ``ReviewLog`` HAS EVER HAD.** It was a write-only ledger — the six record types
    write it and nothing reads it back — and a fourth write-only table is what
    ``test_review_rating_ledger.py`` calls indefensible. The register of corrections
    (``DwInspectionFeedback``) says what was ASKED for; this says what was DECIDED and by whom.
    """
    rows = await db.reviewlog.find_many(
        where={"recordType": "DESIGN_WORKSHOP", "recordId": workshop_id},
        include={"reviewer": True},
        order={"createdAt": "desc"},
        take=DECISION_READ_LIMIT,
    )
    return plans.decision_history_payload(rows)


async def approval_names(record: Any) -> dict[str, str | None]:
    """``approvedByName``, ``approvedByRole`` and ``handedOnByName`` for a single read. One query, or
    none when nobody has approved or handed it on.

    NONE IS A REAL ANSWER: nobody has acted yet, or the account has gone (both pointers are SetNull).
    A screen prints "somebody no longer on record" for the second and never guesses at a name.
    """
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
    """``{user id: name}`` for every approver and hand-on account on a page of rows. One query."""
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
    """One exported report as the hand-on dialog offers it to be pinned."""
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
    """The newest exported report of this workshop, or ``None`` — the one a hand-on pins by default."""
    row = await db.dwreportexport.find_first(
        where={"designWorkshopId": workshop_id},
        include={"generatedBy": True},
        order={"generatedAt": "desc"},
    )
    return export_payload(row) if row is not None else None


async def export_for_hand_on(workshop_id: str, export_id: str, approved_at: Any) -> Any:
    """The export a hand-on pins, or a 422 saying why it cannot be pinned.

    It must be a file of THIS workshop, generated at or after the approval — a file made before the
    approval is not the approved report, and pinning it would put the wrong document on the record as
    the one the office received.
    """
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
    """Where the report goes when nobody says otherwise: stage 20's "Submitted to", else the DCH office.

    Read off the entries the caller has already loaded — stage 20 (``REPORT_GENERATION``) is where the
    designer names the report's addressee, and the cover prints it.
    """
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
    """What THIS reader may decide on this report now, and why not when they may not. PURE.

    ``refusal`` is rule 7's sentence for this reader on this workshop (``None`` when it does not bar
    them). THE CLIENT DRAWS ITS BUTTONS FROM THIS AND FROM NOTHING ELSE: who may decide turns on rows
    the client cannot see. ``refusals`` carries, per verb, the sentence the server would answer the
    press with because of where the report stands; ``reviseKind`` names what ``/revise`` would do.
    """
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
