from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any

from app.services.stage_schema import stages

DRAFT = "DRAFT"
IN_PROGRESS = "IN_PROGRESS"
COMPLETE = "COMPLETE"
PRE_SUBMISSION = "PRE_SUBMISSION"
NEEDS_REVISION = "NEEDS_REVISION"
SUBMITTED = "SUBMITTED"
APPROVED = "APPROVED"
ARCHIVED = "ARCHIVED"

UNDER_REVIEW = frozenset({PRE_SUBMISSION, NEEDS_REVISION})

MAX_DEVICE_CLOCK_SKEW = timedelta(minutes=15)

MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS = 4000

DESIGN_WORKSHOP_FEEDBACK_READ_LIMIT = 200

MAX_BATCH_NOTES = 30

NOTE_POSTS = frozenset({"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"})

NOT_UNDER_REVIEW_REFUSAL = (
    "This report has not been handed in for inspection yet, so there is nothing to comment on. "
    "Correction suggestions can only be filed while a workshop is in Pre-submission or waiting for "
    "revisions — its designers hand it in from the workshop's own screen."
)

REVIEW_CLOSED_APPROVED = (
    "This report has been approved, so its inspection is over and nothing more can be filed against "
    "it. If something in it is wrong, the Ministry Admin can withdraw the approval and send it back to "
    "its designers."
)
REVIEW_CLOSED_HANDED_ON = (
    "This report has been handed on to the office, so its inspection is over and nothing more can be "
    "filed against it."
)


def review_closed_refusal(status_value: str, *, handed_on: bool = False) -> str:
    current = str(status_value or "")
    if current == APPROVED:
        return REVIEW_CLOSED_APPROVED
    if current == SUBMITTED and handed_on:
        return REVIEW_CLOSED_HANDED_ON
    return NOT_UNDER_REVIEW_REFUSAL


LEGAL_TRANSITIONS: Mapping[str, frozenset[str]] = {
    DRAFT: frozenset({IN_PROGRESS, COMPLETE, PRE_SUBMISSION, ARCHIVED}),
    IN_PROGRESS: frozenset({COMPLETE, PRE_SUBMISSION, ARCHIVED}),
    COMPLETE: frozenset({IN_PROGRESS, PRE_SUBMISSION, ARCHIVED}),
    PRE_SUBMISSION: frozenset({IN_PROGRESS, NEEDS_REVISION, APPROVED}),
    NEEDS_REVISION: frozenset({IN_PROGRESS, PRE_SUBMISSION}),
    SUBMITTED: frozenset({IN_PROGRESS, PRE_SUBMISSION, ARCHIVED, NEEDS_REVISION}),
    APPROVED: frozenset({SUBMITTED, NEEDS_REVISION}),
    ARCHIVED: frozenset({IN_PROGRESS, PRE_SUBMISSION}),
}

DECISION_EDGES: frozenset[tuple[str, str]] = frozenset(
    {
        (PRE_SUBMISSION, NEEDS_REVISION),
        (PRE_SUBMISSION, APPROVED),
        (APPROVED, NEEDS_REVISION),
        (APPROVED, SUBMITTED),
        (SUBMITTED, NEEDS_REVISION),
    }
)

_LABELS: Mapping[str, str] = {
    DRAFT: "a draft",
    IN_PROGRESS: "in progress",
    COMPLETE: "complete",
    PRE_SUBMISSION: "in pre-submission",
    NEEDS_REVISION: "waiting for revisions",
    SUBMITTED: "handed on to the office",
    APPROVED: "approved",
    ARCHIVED: "archived",
}

_DECISION_ROUTES: Mapping[tuple[str, str], str] = {
    (PRE_SUBMISSION, NEEDS_REVISION): (
        "Sending a report back is a decision that is recorded with the officer's name, the "
        "correction suggestions and an audit entry, so it is POST "
        "/design-workshop-inspections/{id}/send-back — or, for the approving authority, POST "
        "/design-workshop-approvals/{id}/revise — and never a header edit."
    ),
    (PRE_SUBMISSION, APPROVED): (
        "Approving a report is the sanctioning authority's decision, recorded with their name and "
        "an audit entry in the same transaction, so it is POST "
        "/design-workshop-approvals/{id}/approve and never a header edit. A status that could be "
        "set from a header edit would be an approval that could be manufactured."
    ),
    (APPROVED, NEEDS_REVISION): (
        "Withdrawing an approval is the sanctioning authority's decision and needs a sentence "
        "saying why, so it is POST /design-workshop-approvals/{id}/revise and never a header edit."
    ),
    (APPROVED, SUBMITTED): (
        "Handing an approved report on to the office is the sanctioning authority's act, not the "
        "designer's, so it is POST /design-workshop-approvals/{id}/hand-on and never a header edit."
    ),
    (SUBMITTED, NEEDS_REVISION): (
        "Returning a report that has been handed on to the office is the sanctioning authority's "
        "decision and needs a sentence saying why, so it is POST "
        "/design-workshop-approvals/{id}/revise and never a header edit."
    ),
}


def patchable_from(current: str) -> frozenset[str]:
    allowed = LEGAL_TRANSITIONS.get(current, frozenset())
    return frozenset(nxt for nxt in allowed if (current, nxt) not in DECISION_EDGES)


HANDED_ON_REFUSAL = (
    "This report has been approved and handed on to the office, so it cannot be reopened, handed in "
    "again or archived from here. If something in it needs correcting, ask the Ministry Admin to "
    "return it to its designers."
)


def handed_on_refusal(current: str, nxt: str, *, handed_on: bool) -> str | None:
    if handed_on and current == SUBMITTED and nxt != current:
        return HANDED_ON_REFUSAL
    return None


def transition_refusal(current: str, nxt: str, *, by_decision_route: bool = False) -> str | None:
    if current == nxt:
        return None
    if current not in LEGAL_TRANSITIONS:
        return (
            "This workshop's status isn't recognised, so it can't be changed right now. Reload "
            "the page and try again."
        )
    if (current, nxt) in DECISION_EDGES:
        if by_decision_route:
            return None
        return _DECISION_ROUTES[(current, nxt)]
    if nxt not in LEGAL_TRANSITIONS[current]:
        return _dead_end_or_list(current, nxt)
    return None


def _dead_end_or_list(current: str, nxt: str) -> str:
    onward = sorted(patchable_from(current))
    if onward:
        return (
            f"A workshop that is {_LABELS.get(current, current)} cannot go straight to "
            f"{_LABELS.get(nxt, nxt)}. From here it can only become "
            f"{', '.join(_LABELS.get(token, token) for token in onward)}."
        )
    moves = sorted(LEGAL_TRANSITIONS.get(current, frozenset()))
    routes = " ".join(_DECISION_ROUTES[(current, token)] for token in moves if (current, token) in _DECISION_ROUTES)
    return (
        f"A workshop that is {_LABELS.get(current, current)} cannot be moved by a header edit at "
        f"all — every move left to it is a decision taken on its own route, by the office that "
        f"takes it. {routes}"
    )


APPROVAL_CACHE_KEYS: tuple[str, ...] = ("approvedById", "approvedAt", "approvedRound")
HAND_ON_CACHE_KEYS: tuple[str, ...] = (
    "handedOnById",
    "handedOnAt",
    "handedOnTo",
    "handedOnExportId",
)


def presubmission_header(current_status: str, *, at: datetime | None = None) -> dict[str, Any]:
    if current_status == PRE_SUBMISSION:
        return {}
    return {
        "status": PRE_SUBMISSION,
        "submissionRound": {"increment": 1},
        "reviewNotes": None,
        "reviewedById": None,
        "reviewedAt": None,
        **dict.fromkeys(APPROVAL_CACHE_KEYS),
        **dict.fromkeys(HAND_ON_CACHE_KEYS),
        "lastHandedInAt": at if at is not None else datetime.now(UTC),
    }



class InspectionRuleViolation(ValueError):
    pass


class Operation(Enum):
    CREATE = "create"
    UPDATE = "update"


WRITABLE_TABLES = frozenset({"DesignWorkshop", "DwInspectionFeedback", "ReviewLog"})

RECORD_STATUSES = frozenset({"DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"})


@dataclass(frozen=True, slots=True)
class InspectionWritePlan:
    table: str
    operation: Operation
    data: Mapping[str, Any]
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in WRITABLE_TABLES:
            raise InspectionRuleViolation(
                f"An inspection decision may not be written into {self.table}. It is a row in the "
                f"workshop's feedback log, three cache columns on the workshop and an audit entry, "
                f"and nowhere else: a stage field cannot say WHO wrote it or WHEN — a stage row's "
                f"updatedAt moves whenever anything in that stage changes, and its createdById is "
                f"whoever first saved it. Write it to one of "
                f"{', '.join(sorted(WRITABLE_TABLES))}."
            )
        if self.operation is Operation.UPDATE and not self.where:
            raise InspectionRuleViolation("An update must name the single row it changes.")
        if self.operation is Operation.CREATE and self.where:
            raise InspectionRuleViolation("A create names no existing row. Drop the where clause.")
        if (
            self.table == "ReviewLog"
            and self.operation is Operation.CREATE
            and self.data.get("status") not in RECORD_STATUSES
        ):
            raise InspectionRuleViolation(
                f"A ReviewLog row's status is the shared review vocabulary — one of "
                f"{', '.join(sorted(RECORD_STATUSES))} — and {self.data.get('status')!r} is not in "
                f"it. Say what the decision was with a notes prefix, as a reviewer's edit does."
            )


@dataclass(frozen=True, slots=True)
class SendBackPlans:
    feedback: InspectionWritePlan
    workshop: InspectionWritePlan
    log: InspectionWritePlan

    def __iter__(self):
        return iter((self.feedback, self.workshop, self.log))


def _clean_note(note: str | None) -> str:
    text = (note or "").strip()
    if not text:
        raise InspectionRuleViolation(
            "Comments are required when sending a record back for revision"
        )
    if len(text) > MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS:
        raise InspectionRuleViolation(
            f"A correction suggestion is at most {MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS} characters. "
            f"This one is {len(text)}. File the rest as a second suggestion — the register keeps "
            f"every one of them, in order."
        )
    return text


def _clean_stage_key(stage_key: str | None) -> str | None:
    if stage_key is None:
        return None
    key = stage_key.strip()
    if not key:
        return None
    if key not in {spec.key for spec in stages()}:
        raise InspectionRuleViolation(
            f"{key!r} isn't a stage of this report. Leave the stage out to file the suggestion "
            f"against the whole report."
        )
    return key


def feedback_plan(
    *,
    workshop_id: str,
    round: int,
    actor_id: str,
    at: datetime,
    note: str | None,
    stage_key: str | None = None,
    field_key: str | None = None,
    recorded_at: datetime | None = None,
    sent_back: bool = False,
    by_approving_authority: bool = False,
    actor_post: str | None = None,
) -> InspectionWritePlan:
    if round < 1:
        raise InspectionRuleViolation(
            "This suggestion couldn't be filed because the workshop's submission couldn't be "
            "read. Reload the workshop and try again."
        )
    if recorded_at is not None and recorded_at > at + MAX_DEVICE_CLOCK_SKEW:
        raise InspectionRuleViolation(
            "The device that filed this suggestion has its clock set ahead of the real time. "
            "Correct the device's date and time and file it again."
        )
    data: dict[str, Any] = {
        "designWorkshopId": workshop_id,
        "round": round,
        "stageKey": _clean_stage_key(stage_key),
        "fieldKey": (field_key or "").strip() or None,
        "note": _clean_note(note),
        "sentBack": sent_back,
        "byApprovingAuthority": by_approving_authority,
        "actorId": actor_id,
        "recordedAt": recorded_at,
    }
    if actor_post is not None:
        if actor_post not in NOTE_POSTS:
            raise InspectionRuleViolation(f"{actor_post!r} is not a post that files notes.")
        data["actorPost"] = actor_post
    return InspectionWritePlan(table="DwInspectionFeedback", operation=Operation.CREATE, data=data)


def send_back_plans(
    *,
    workshop_id: str,
    round: int,
    actor_id: str,
    at: datetime,
    note: str | None,
    stage_key: str | None = None,
    field_key: str | None = None,
    recorded_at: datetime | None = None,
    by_approving_authority: bool = False,
    log_prefix: str | None = None,
) -> SendBackPlans:
    from app.services.records import review_update

    note_text = _clean_note(note)
    feedback = feedback_plan(
        workshop_id=workshop_id,
        round=round,
        actor_id=actor_id,
        at=at,
        note=note_text,
        stage_key=stage_key,
        field_key=field_key,
        recorded_at=recorded_at,
        sent_back=True,
        by_approving_authority=by_approving_authority,
    )
    workshop = InspectionWritePlan(
        table="DesignWorkshop",
        operation=Operation.UPDATE,
        where={"id": workshop_id},
        data=review_update(NEEDS_REVISION, note_text, actor_id),
    )
    log = InspectionWritePlan(
        table="ReviewLog",
        operation=Operation.CREATE,
        data={
            "recordType": "DESIGN_WORKSHOP",
            "recordId": workshop_id,
            "status": NEEDS_REVISION,
            "notes": f"{log_prefix}: {note_text}" if log_prefix else note_text,
            "reviewerId": actor_id,
        },
    )
    return SendBackPlans(feedback=feedback, workshop=workshop, log=log)



def _iso(moment: Any) -> str | None:
    return moment.isoformat() if isinstance(moment, datetime) else None


def feedback_payload(row: Any) -> dict[str, Any]:
    actor = getattr(row, "actor", None)
    return {
        "id": getattr(row, "id", None),
        "designWorkshopId": getattr(row, "designWorkshopId", None),
        "round": getattr(row, "round", 0),
        "stageKey": getattr(row, "stageKey", None),
        "fieldKey": getattr(row, "fieldKey", None),
        "note": getattr(row, "note", ""),
        "sentBack": bool(getattr(row, "sentBack", False)),
        "byApprovingAuthority": bool(getattr(row, "byApprovingAuthority", False)),
        "actorPost": getattr(row, "actorPost", None),
        "actorId": getattr(row, "actorId", ""),
        "actorName": getattr(actor, "name", None) if actor is not None else None,
        "recordedAt": _iso(getattr(row, "recordedAt", None)),
        "createdAt": _iso(getattr(row, "createdAt", None)),
    }



@dataclass(frozen=True, slots=True)
class NoteBatch:
    rows: tuple[InspectionWritePlan, ...]
    cover: str
    digest: str


def note_batch(
    *,
    workshop_id: str,
    round: int,
    actor_id: str,
    at: datetime,
    notes: list[tuple[str | None, str | None, str | None]],
    cover: str | None = None,
    recorded_at: datetime | None = None,
    sends_back: bool = False,
    by_approving_authority: bool = False,
    actor_post: str | None = None,
) -> NoteBatch:
    if not notes:
        raise InspectionRuleViolation("Add at least one note.")
    if len(notes) > MAX_BATCH_NOTES:
        raise InspectionRuleViolation(f"Send at most {MAX_BATCH_NOTES} notes at once.")
    typed = (cover or "").strip()
    planned: list[tuple[str | None, str | None, str | None]] = list(notes)
    if typed:
        planned.insert(0, (typed, None, None))

    rows = tuple(
        feedback_plan(
            workshop_id=workshop_id,
            round=round,
            actor_id=actor_id,
            at=at,
            note=text,
            stage_key=stage_key,
            field_key=field_key,
            recorded_at=recorded_at,
            sent_back=sends_back and index == 0,
            by_approving_authority=by_approving_authority,
            actor_post=actor_post,
        )
        for index, (text, stage_key, field_key) in enumerate(planned)
    )
    lead = str(rows[0].data["note"])
    extra = len(rows) - 1
    summary = lead if extra == 0 else f"{lead} (and {extra} more note{'s' if extra != 1 else ''})"
    digest = "\n".join(f"{n}. {row.data['note']}" for n, row in enumerate(rows, start=1))
    return NoteBatch(
        rows=rows,
        cover=summary[:MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS],
        digest=digest if extra else lead,
    )


def send_back_batch_plans(
    *,
    workshop_id: str,
    round: int,
    actor_id: str,
    at: datetime,
    notes: list[tuple[str | None, str | None, str | None]],
    cover: str | None = None,
    recorded_at: datetime | None = None,
    by_approving_authority: bool = False,
    log_prefix: str | None = None,
) -> BatchSendBackPlans:
    from app.services.records import review_update

    batch = note_batch(
        workshop_id=workshop_id,
        round=round,
        actor_id=actor_id,
        at=at,
        notes=notes,
        cover=cover,
        recorded_at=recorded_at,
        sends_back=True,
        by_approving_authority=by_approving_authority,
    )
    workshop = InspectionWritePlan(
        table="DesignWorkshop",
        operation=Operation.UPDATE,
        where={"id": workshop_id},
        data=review_update(NEEDS_REVISION, batch.cover, actor_id),
    )
    log = InspectionWritePlan(
        table="ReviewLog",
        operation=Operation.CREATE,
        data={
            "recordType": "DESIGN_WORKSHOP",
            "recordId": workshop_id,
            "status": NEEDS_REVISION,
            "notes": f"{log_prefix}: {batch.cover}" if log_prefix else batch.cover,
            "reviewerId": actor_id,
        },
    )
    return BatchSendBackPlans(batch=batch, workshop=workshop, log=log)


@dataclass(frozen=True, slots=True)
class BatchSendBackPlans:
    batch: NoteBatch
    workshop: InspectionWritePlan
    log: InspectionWritePlan

    def __iter__(self):
        return iter((self.batch, self.workshop, self.log))
