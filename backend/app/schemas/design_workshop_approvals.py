from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

from app.schemas import design_workshop_review_loop as loop
from app.schemas.design_workshop_review_loop import (
    APPROVAL_CACHE_KEYS,
    APPROVED,
    HAND_ON_CACHE_KEYS,
    MAX_BATCH_NOTES,
    MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS,
    NEEDS_REVISION,
    PRE_SUBMISSION,
    SUBMITTED,
    UNDER_REVIEW,
    InspectionRuleViolation,
    InspectionWritePlan,
    Operation,
    SendBackPlans,
)

HANDED_ON = "HANDED_ON"
APPROVAL_WITHDRAWN = "APPROVAL_WITHDRAWN"
RETURNED_BY_APPROVER = "RETURNED_BY_APPROVER"

RETURN = "RETURN"
WITHDRAW = "WITHDRAW"
RETURN_FROM_OFFICE = "RETURN_FROM_OFFICE"

KIND_APPROVED = "APPROVED"
KIND_APPROVAL_WITHDRAWN = "APPROVAL_WITHDRAWN"
KIND_RETURNED = "RETURNED"
KIND_SENT_BACK = "SENT_BACK"
KIND_HANDED_ON = "HANDED_ON"

DEFAULT_OFFICE = "Office of the Development Commissioner (Handicrafts)"

MAX_DECISION_NOTE_CHARS = MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS

MAX_HANDED_ON_TO_CHARS = 220

APPROVER_ROLE_LABELS: Mapping[str, str] = {
    "MINISTRY_ADMIN": "Ministry Admin",
    "MASTER_ADMIN": "Master Admin",
}


def role_label(role: str | None) -> str:
    token = str(role or "").strip()
    if token in APPROVER_ROLE_LABELS:
        return APPROVER_ROLE_LABELS[token]
    return token.replace("_", " ").title() if token else ""



def is_frozen(status_value: Any, handed_on_at: Any) -> bool:
    current = str(getattr(status_value, "value", status_value) or "")
    return current == APPROVED or (current == SUBMITTED and handed_on_at is not None)


FROZEN_APPROVED = (
    "This report has been approved, so it can no longer be changed. If something in it needs "
    "correcting, ask the Ministry Admin to withdraw the approval; it then comes back to its designers "
    "to correct and hand in again."
)
FROZEN_HANDED_ON = (
    "This report has been approved and handed on to the office, so it can no longer be changed. If "
    "something in it needs correcting, ask the Ministry Admin to return it to its designers."
)


FROZEN_WHERE: Mapping[str, Any] = {
    "OR": [
        {"status": APPROVED},
        {"status": SUBMITTED, "handedOnAt": {"not": None}},
    ]
}


def frozen_refusal(status_value: Any, handed_on_at: Any) -> str | None:
    if not is_frozen(status_value, handed_on_at):
        return None
    current = str(getattr(status_value, "value", status_value) or "")
    return FROZEN_APPROVED if current == APPROVED else FROZEN_HANDED_ON


CHANGED_AFTER_OPENED = (
    "This report changed after you opened it, so nothing was written. Reload it and read it again "
    "before deciding."
)

ALREADY_HANDED_ON = "This report has already been handed on to the office. Nothing was written."

REVISE_NOTE_REQUIRED = (
    "Say what needs correcting. The sentence goes to the designers as written, and without one they "
    "are told only that the report came back."
)


def _spoken(status_value: str) -> str:
    return loop._LABELS.get(status_value, status_value)


def approve_refusal(status_value: str, *, handed_on: bool) -> str | None:
    if status_value in (PRE_SUBMISSION, APPROVED):
        return None
    if status_value == NEEDS_REVISION:
        return (
            "This report was sent back for corrections and has not been handed back in, so there is "
            "nothing to approve yet. It can be approved once its designers hand it back in."
        )
    if status_value == SUBMITTED and handed_on:
        return "This report has already been approved and handed on to the office."
    return (
        f"This report is {_spoken(status_value)}, so there is nothing to approve: a report is approved "
        f"while it is in Pre-submission, after its designers hand it in."
    )


def revise_kind(status_value: str, *, handed_on: bool) -> str | None:
    if status_value in UNDER_REVIEW:
        return RETURN
    if status_value == APPROVED:
        return WITHDRAW
    if status_value == SUBMITTED and handed_on:
        return RETURN_FROM_OFFICE
    return None


def revise_refusal(status_value: str, *, handed_on: bool) -> str | None:
    if revise_kind(status_value, handed_on=handed_on) is not None:
        return None
    if status_value == SUBMITTED:
        return (
            "This report has no approval to withdraw: it was marked submitted before reports were "
            "approved in this app, and its designers can hand it in again for inspection."
        )
    return (
        f"This report is {_spoken(status_value)}, so there is nothing to send back: it has not been "
        f"handed in."
    )


def hand_on_refusal(status_value: str, *, handed_on: bool) -> tuple[int, str] | None:
    if status_value == APPROVED:
        return None
    if status_value == SUBMITTED and handed_on:
        return 409, ALREADY_HANDED_ON
    return 422, (
        f"Only an approved report can be handed on to the office, and this one is "
        f"{_spoken(status_value)}."
    )



class DwApproveIn(BaseModel):
    note: str | None = Field(default=None, max_length=MAX_DECISION_NOTE_CHARS)
    round: int = Field(ge=1)
    readAt: str = Field(min_length=1, max_length=64)


class DwDecisionNoteIn(BaseModel):
    note: str = Field(min_length=1, max_length=MAX_DECISION_NOTE_CHARS)
    stageKey: str | None = Field(default=None, max_length=120)
    fieldKey: str | None = Field(default=None, max_length=120)


class DwReviseIn(BaseModel):
    note: str | None = Field(default=None, max_length=MAX_DECISION_NOTE_CHARS)
    stageKey: str | None = Field(default=None, max_length=120)
    fieldKey: str | None = Field(default=None, max_length=120)
    notes: list[DwDecisionNoteIn] = Field(default_factory=list, max_length=MAX_BATCH_NOTES)


class DwHandOnIn(BaseModel):
    note: str | None = Field(default=None, max_length=MAX_DECISION_NOTE_CHARS)
    handedOnTo: str | None = Field(default=None, max_length=MAX_HANDED_ON_TO_CHARS)
    exportId: str | None = Field(default=None, max_length=64)



class DwApproveAndHandOnIn(BaseModel):
    note: str | None = Field(default=None, max_length=MAX_DECISION_NOTE_CHARS)
    round: int | None = Field(default=None, ge=1)
    readAt: str | None = Field(default=None, max_length=64)
    format: Literal["PDF", "DOCX"] = "PDF"
    templateId: str | None = Field(default=None, max_length=48)


@dataclass(frozen=True, slots=True)
class DecisionPlans:
    workshop: InspectionWritePlan
    log: InspectionWritePlan
    feedback: InspectionWritePlan | None = None
    extra: tuple[InspectionWritePlan, ...] = ()
    digest: str | None = None

    def __iter__(self):
        writes = [self.workshop]
        if self.feedback is not None:
            writes.append(self.feedback)
        writes.extend(self.extra)
        writes.append(self.log)
        return iter(writes)


def _round(round: int) -> int:
    if int(round) < 1:
        raise InspectionRuleViolation(
            "A decision belongs to a submission cycle, and this workshop's counter reads zero — which "
            "can only mean the copy from DesignWorkshop.submissionRound was skipped."
        )
    return int(round)


def _optional(text: str | None, *, cap: int, what: str) -> str | None:
    cleaned = (text or "").strip()
    if not cleaned:
        return None
    if len(cleaned) > cap:
        raise InspectionRuleViolation(f"{what} is at most {cap} characters. This one is {len(cleaned)}.")
    return cleaned


def _log(*, workshop_id: str, status_value: str, notes: str | None, actor_id: str) -> InspectionWritePlan:
    return InspectionWritePlan(
        table="ReviewLog",
        operation=Operation.CREATE,
        data={
            "recordType": "DESIGN_WORKSHOP",
            "recordId": workshop_id,
            "status": status_value,
            "notes": notes,
            "reviewerId": actor_id,
        },
    )


def _prefixed(prefix: str, text: str | None) -> str:
    return f"{prefix}: {text}" if text else f"{prefix}:"


def approve_plans(
    *,
    workshop_id: str,
    round: int,
    actor_id: str,
    at: datetime,
    note: str | None = None,
    read_at: datetime | None = None,
) -> DecisionPlans:
    from app.services.records import review_update

    cycle = _round(round)
    note_text = _optional(note, cap=MAX_DECISION_NOTE_CHARS, what="A note")
    cache = review_update(APPROVED, note_text, actor_id)
    where: dict[str, Any] = {
        "id": workshop_id,
        "status": PRE_SUBMISSION,
        "submissionRound": cycle,
        "deletedAt": None,
    }
    if read_at is not None:
        where["updatedAt"] = read_at
    workshop = InspectionWritePlan(
        table="DesignWorkshop",
        operation=Operation.UPDATE,
        where=where,
        data={
            **cache,
            "approvedById": actor_id,
            "approvedAt": cache["reviewedAt"],
            "approvedRound": cycle,
        },
    )
    log = _log(workshop_id=workshop_id, status_value=APPROVED, notes=note_text, actor_id=actor_id)
    return DecisionPlans(workshop=workshop, log=log)


def revise_plans(
    *,
    workshop_id: str,
    round: int,
    actor_id: str,
    at: datetime,
    note: str | None,
    current_status: str = APPROVED,
    handed_on: bool = False,
    stage_key: str | None = None,
    field_key: str | None = None,
    notes: list[tuple[str | None, str | None, str | None]] | None = None,
) -> DecisionPlans:
    from app.services.records import review_update

    cycle = _round(round)
    text = (note or "").strip()
    if not text and not notes:
        raise InspectionRuleViolation(REVISE_NOTE_REQUIRED)
    kind = revise_kind(current_status, handed_on=handed_on)
    if kind is None:
        raise InspectionRuleViolation(
            revise_refusal(current_status, handed_on=handed_on) or REVISE_NOTE_REQUIRED
        )
    if notes:
        return _revise_batch_plans(
            workshop_id=workshop_id,
            cycle=cycle,
            actor_id=actor_id,
            at=at,
            cover=text,
            notes=notes,
            kind=kind,
            current_status=current_status,
        )
    if kind == RETURN:
        plans: SendBackPlans = loop.send_back_plans(
            workshop_id=workshop_id,
            round=cycle,
            actor_id=actor_id,
            at=at,
            note=text,
            stage_key=stage_key,
            field_key=field_key,
            by_approving_authority=True,
            log_prefix=RETURNED_BY_APPROVER,
        )
        guarded = InspectionWritePlan(
            table="DesignWorkshop",
            operation=Operation.UPDATE,
            where={
                "id": workshop_id,
                "status": {"in": sorted(UNDER_REVIEW)},
                "deletedAt": None,
            },
            data=dict(plans.workshop.data),
        )
        return DecisionPlans(workshop=guarded, log=plans.log, feedback=plans.feedback)

    feedback = loop.feedback_plan(
        workshop_id=workshop_id,
        round=cycle,
        actor_id=actor_id,
        at=at,
        note=text,
        stage_key=stage_key,
        field_key=field_key,
        sent_back=True,
        by_approving_authority=True,
    )
    where: dict[str, Any] = {"id": workshop_id, "status": current_status, "deletedAt": None}
    if kind == RETURN_FROM_OFFICE:
        where["handedOnAt"] = {"not": None}
    workshop = InspectionWritePlan(
        table="DesignWorkshop",
        operation=Operation.UPDATE,
        where=where,
        data={
            **review_update(NEEDS_REVISION, text, actor_id),
            **dict.fromkeys(APPROVAL_CACHE_KEYS),
            **dict.fromkeys(HAND_ON_CACHE_KEYS),
        },
    )
    log = _log(
        workshop_id=workshop_id,
        status_value=NEEDS_REVISION,
        notes=_prefixed(APPROVAL_WITHDRAWN, text),
        actor_id=actor_id,
    )
    return DecisionPlans(workshop=workshop, log=log, feedback=feedback)


def _revise_batch_plans(
    *,
    workshop_id: str,
    cycle: int,
    actor_id: str,
    at: datetime,
    cover: str,
    notes: list[tuple[str | None, str | None, str | None]],
    kind: str,
    current_status: str,
) -> DecisionPlans:
    from app.services.records import review_update

    if kind == RETURN:
        batch, workshop, log = loop.send_back_batch_plans(
            workshop_id=workshop_id,
            round=cycle,
            actor_id=actor_id,
            at=at,
            notes=notes,
            cover=cover,
            by_approving_authority=True,
            log_prefix=RETURNED_BY_APPROVER,
        )
        guarded = InspectionWritePlan(
            table="DesignWorkshop",
            operation=Operation.UPDATE,
            where={"id": workshop_id, "status": {"in": sorted(UNDER_REVIEW)}, "deletedAt": None},
            data=dict(workshop.data),
        )
        return DecisionPlans(
            workshop=guarded,
            log=log,
            feedback=batch.rows[0],
            extra=batch.rows[1:],
            digest=batch.digest,
        )
    batch = loop.note_batch(
        workshop_id=workshop_id,
        round=cycle,
        actor_id=actor_id,
        at=at,
        notes=notes,
        cover=cover,
        sends_back=True,
        by_approving_authority=True,
    )
    where: dict[str, Any] = {"id": workshop_id, "status": current_status, "deletedAt": None}
    if kind == RETURN_FROM_OFFICE:
        where["handedOnAt"] = {"not": None}
    workshop = InspectionWritePlan(
        table="DesignWorkshop",
        operation=Operation.UPDATE,
        where=where,
        data={
            **review_update(NEEDS_REVISION, batch.cover, actor_id),
            **dict.fromkeys(APPROVAL_CACHE_KEYS),
            **dict.fromkeys(HAND_ON_CACHE_KEYS),
        },
    )
    log = _log(
        workshop_id=workshop_id,
        status_value=NEEDS_REVISION,
        notes=_prefixed(APPROVAL_WITHDRAWN, batch.cover),
        actor_id=actor_id,
    )
    return DecisionPlans(
        workshop=workshop,
        log=log,
        feedback=batch.rows[0],
        extra=batch.rows[1:],
        digest=batch.digest,
    )


def hand_on_plans(
    *,
    workshop_id: str,
    round: int,
    actor_id: str,
    at: datetime,
    note: str | None = None,
    handed_on_to: str | None = None,
    export_id: str | None = None,
) -> DecisionPlans:
    from app.services.records import review_update

    cycle = _round(round)
    note_text = _optional(note, cap=MAX_DECISION_NOTE_CHARS, what="A note")
    office = _optional(handed_on_to, cap=MAX_HANDED_ON_TO_CHARS, what="The office") or DEFAULT_OFFICE
    workshop = InspectionWritePlan(
        table="DesignWorkshop",
        operation=Operation.UPDATE,
        where={
            "id": workshop_id,
            "status": APPROVED,
            "submissionRound": cycle,
            "deletedAt": None,
        },
        data={
            **review_update(SUBMITTED, note_text, actor_id),
            "handedOnById": actor_id,
            "handedOnAt": at,
            "handedOnTo": office,
            "handedOnExportId": export_id,
        },
    )
    summary = f"To {office}." + (f" {note_text}" if note_text else "")
    log = _log(
        workshop_id=workshop_id,
        status_value=APPROVED,
        notes=_prefixed(HANDED_ON, summary),
        actor_id=actor_id,
    )
    return DecisionPlans(workshop=workshop, log=log)



def _iso(moment: Any) -> str | None:
    return moment.isoformat() if isinstance(moment, datetime) else None


def _split(notes: str | None, prefix: str) -> tuple[bool, str | None]:
    text = notes or ""
    marker = f"{prefix}:"
    if text.startswith(marker):
        rest = text[len(marker) :].strip()
        return True, rest or None
    return False, None


def decision_row_payload(row: Any) -> dict[str, Any] | None:
    status_value = str(getattr(getattr(row, "status", None), "value", getattr(row, "status", "")) or "")
    notes = getattr(row, "notes", None)
    if status_value == APPROVED:
        handed, rest = _split(notes, HANDED_ON)
        kind, note = (KIND_HANDED_ON, rest) if handed else (KIND_APPROVED, notes or None)
    elif status_value == NEEDS_REVISION:
        withdrawn, rest = _split(notes, APPROVAL_WITHDRAWN)
        returned, back = _split(notes, RETURNED_BY_APPROVER)
        if withdrawn:
            kind, note = KIND_APPROVAL_WITHDRAWN, rest
        elif returned:
            kind, note = KIND_RETURNED, back
        else:
            kind, note = KIND_SENT_BACK, notes or None
    else:
        return None
    reviewer = getattr(row, "reviewer", None)
    return {
        "id": getattr(row, "id", None),
        "kind": kind,
        "note": note,
        "actorId": getattr(row, "reviewerId", None),
        "actorName": getattr(reviewer, "name", None) if reviewer is not None else None,
        "at": _iso(getattr(row, "createdAt", None)),
    }


def decision_history_payload(rows: Iterable[Any]) -> list[dict[str, Any]]:
    decoded = (decision_row_payload(row) for row in rows)
    return [item for item in decoded if item is not None]
