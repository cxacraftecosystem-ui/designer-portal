"""THE APPROVING AUTHORITY'S DECISIONS, PLANNED: approve, send back or withdraw, hand on.

The owner's requirement ends: *"This iterative review loop continues until the sanctioning authority
is satisfied and clicks 'approve'."* ``schemas/design_workshop_review_loop`` owns the status graph and
the inspector's send-back; this module owns what each of the approving authority's decisions WRITES,
the sentences each refusal says, and the bodies the routes accept. ``core/deps.APPROVAL_AUTHORITY_ROLES``
and rule 7 of ``services/design_workshop_posts`` decide WHO; ``api/routes/design_workshop_approvals.py``
is the wire.

=======================================================================================
WHY THIS MODULE IS PURE, AND WHY IT IS HERE
=======================================================================================

For the review-loop module's reason, which this file is the other half of: every rule below is
assertable with no database, no event loop and no generated client, which is what lets
``tests/test_design_workshop_approvals.py`` run in CI's Backend tests job, where there is no
Postgres. ``records.review_update`` is imported INSIDE the builders that need it, because
``services/records`` imports ``app.core.db`` at module level. ``test_design_workshop_review_loop.py``
predicted this file by name, and its sweep of ``*_plans`` builders reaches this module too.

=======================================================================================
HOW A DECISION IS RECORDED
=======================================================================================

Every decision is three things in ONE transaction: a compare-and-set update of the workshop (its
WHERE carries the state the decision was taken against, so a decision that lost a race writes
nothing), a ``ReviewLog`` row, and — for the decisions that put the report back on its designers'
desk — a correction-suggestion row in the register they read. ``ReviewLog`` is the one ledger this
product has and is not changed: its ``status`` is ``RecordStatus``, so a decision says what it was the
way ``api/routes/review.py`` marks a reviewer's edit, with a prefix on ``notes`` —

    APPROVED                                   an approval (its note, if any)
    APPROVED        "HANDED_ON: …"             a hand-on: the report's review standing stays approved
    NEEDS_REVISION                             an inspector's send-back
    NEEDS_REVISION  "RETURNED_BY_APPROVER: …"  sent back by the approving authority without approval
    NEEDS_REVISION  "APPROVAL_WITHDRAWN: …"    an approval withdrawn, before or after the hand-on

— and :func:`decision_history_payload` decodes the pair back into what a screen says.

Nothing is e-mailed or transmitted by any of it. There is no mailer in this product; handing a
report on RECORDS that it went, to which office, by whom and — when the authority picked one — which
exported file, by its checksum.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from app.schemas import design_workshop_review_loop as loop
from app.schemas.design_workshop_review_loop import (
    APPROVAL_CACHE_KEYS,
    APPROVED,
    HAND_ON_CACHE_KEYS,
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

# ── THE VOCABULARY ───────────────────────────────────────────────────────────────────────────────

#: The ``ReviewLog.notes`` prefixes, spelled as ``api/routes/review.py`` spells ``EDITED``.
HANDED_ON = "HANDED_ON"
APPROVAL_WITHDRAWN = "APPROVAL_WITHDRAWN"
RETURNED_BY_APPROVER = "RETURNED_BY_APPROVER"

#: What ``/revise`` does from each state, as the detail read names it.
RETURN = "RETURN"
WITHDRAW = "WITHDRAW"
RETURN_FROM_OFFICE = "RETURN_FROM_OFFICE"

#: What one row of the decision history is, once decoded.
KIND_APPROVED = "APPROVED"
KIND_APPROVAL_WITHDRAWN = "APPROVAL_WITHDRAWN"
KIND_RETURNED = "RETURNED"
KIND_SENT_BACK = "SENT_BACK"
KIND_HANDED_ON = "HANDED_ON"

#: Where a report goes when nobody has said otherwise: the DCH template is "for submission to the
#: Development Commissioner (Handicrafts)", and this is that template's own organisation line
#: (``services/report_templates.py``). Stage 20's "Submitted to" answer wins when the designer gave
#: one.
DEFAULT_OFFICE = "Office of the Development Commissioner (Handicrafts)"

#: The longest note a decision may carry — a suggestion's cap, so both registers hold the same size.
MAX_DECISION_NOTE_CHARS = MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS

#: The longest office name a hand-on may record: stage 20's "Submitted to" box allows the same.
MAX_HANDED_ON_TO_CHARS = 220

#: How the two approving roles are named on a report's certification line.
APPROVER_ROLE_LABELS: Mapping[str, str] = {
    "MINISTRY_ADMIN": "Ministry Admin",
    "MASTER_ADMIN": "Master Admin",
}


def role_label(role: str | None) -> str:
    """The approver's role as a report prints it: the two labels above, else the token in words."""
    token = str(role or "").strip()
    if token in APPROVER_ROLE_LABELS:
        return APPROVER_ROLE_LABELS[token]
    return token.replace("_", " ").title() if token else ""


# ── THE FREEZE ───────────────────────────────────────────────────────────────────────────────────


def is_frozen(status_value: Any, handed_on_at: Any) -> bool:
    """May this report's CONTENT still change? Not once it is approved, nor once it is handed on.

    **AN APPROVAL THAT DOES NOT COVER THE CONTENT IT NAMES IS NOT AN APPROVAL**, so from the moment a
    report is APPROVED until the approval is withdrawn every content write is refused, and a report
    handed on to the office stays that way until the authority returns it. A LEGACY SUBMITTED row —
    the word's meaning before 2026-09-13, ``handedOnAt`` null — was never approved here and stays as
    writable as it always was.
    """
    current = str(getattr(status_value, "value", status_value) or "")
    return current == APPROVED or (current == SUBMITTED and handed_on_at is not None)


#: What every content write is told while the report is frozen (403). The designer's next move, and
#: whose it is, in the designer's terms.
FROZEN_APPROVED = (
    "This report has been approved, so it can no longer be changed. If something in it needs "
    "correcting, ask the Ministry Admin to withdraw the approval; it then comes back to its designers "
    "to correct and hand in again."
)
FROZEN_HANDED_ON = (
    "This report has been approved and handed on to the office, so it can no longer be changed. If "
    "something in it needs correcting, ask the Ministry Admin to return it to its designers."
)


#: :func:`is_frozen` as a Prisma filter, for the writes that must not land on a frozen report even
#: when the approval committed after their read: ``save_stage``'s header write and the header edit
#: carry ``{"NOT": FROZEN_WHERE}`` in their WHERE and refuse on a zero count. Two keys in one dict are
#: an AND, so the second arm is "SUBMITTED and handed on" — a legacy SUBMITTED row does not match.
FROZEN_WHERE: Mapping[str, Any] = {
    "OR": [
        {"status": APPROVED},
        {"status": SUBMITTED, "handedOnAt": {"not": None}},
    ]
}


def frozen_refusal(status_value: Any, handed_on_at: Any) -> str | None:
    """The 403 sentence for a content write on a frozen report, or ``None`` when it is not frozen."""
    if not is_frozen(status_value, handed_on_at):
        return None
    current = str(getattr(status_value, "value", status_value) or "")
    return FROZEN_APPROVED if current == APPROVED else FROZEN_HANDED_ON


# ── WHERE THE REPORT STANDS, PER VERB ────────────────────────────────────────────────────────────

#: 409 when the compare-and-set matched nothing, or the round/read stamp the authority decided
#: against is no longer the report's: somebody moved it, or its designers changed it, in between.
CHANGED_AFTER_OPENED = (
    "This report changed after you opened it, so nothing was written. Reload it and read it again "
    "before deciding."
)

#: 409 for a hand-on of a report that has already been handed on.
ALREADY_HANDED_ON = "This report has already been handed on to the office. Nothing was written."

#: 422 for a send-back or withdrawal with no sentence.
REVISE_NOTE_REQUIRED = (
    "Say what needs correcting. The sentence goes to the designers as written, and without one they "
    "are told only that the report came back."
)


def _spoken(status_value: str) -> str:
    return loop._LABELS.get(status_value, status_value)


def approve_refusal(status_value: str, *, handed_on: bool) -> str | None:
    """Why a report in this state cannot be approved (422), or ``None`` — PRE_SUBMISSION, or APPROVED,
    which the route answers as a no-op rather than a refusal (a no-op is never a refusal)."""
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
    """What ``/revise`` would do from this state — :data:`RETURN`, :data:`WITHDRAW`,
    :data:`RETURN_FROM_OFFICE` — or ``None`` when it does nothing from here."""
    if status_value in UNDER_REVIEW:
        return RETURN
    if status_value == APPROVED:
        return WITHDRAW
    if status_value == SUBMITTED and handed_on:
        return RETURN_FROM_OFFICE
    return None


def revise_refusal(status_value: str, *, handed_on: bool) -> str | None:
    """Why ``/revise`` cannot be taken from this state (422), or ``None``."""
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
    """Why this report cannot be handed on, with its status code, or ``None`` when it is APPROVED."""
    if status_value == APPROVED:
        return None
    if status_value == SUBMITTED and handed_on:
        return 409, ALREADY_HANDED_ON
    return 422, (
        f"Only an approved report can be handed on to the office, and this one is "
        f"{_spoken(status_value)}."
    )


# ── THE WIRE ─────────────────────────────────────────────────────────────────────────────────────


class DwApproveIn(BaseModel):
    """POST to approve a report in Pre-submission.

    ``round`` and ``readAt`` ARE THE REPORT AS THE AUTHORITY READ IT — its ``submissionRound`` and
    its ``updatedAt`` from the detail read — and both go into the compare-and-set. Every stage save
    moves ``updatedAt``, so an approval can never cover content its approver did not see: a save in
    between makes the write match nothing and the answer is a 409 asking for a reload. ``note`` is
    optional and is kept with the decision.
    """

    note: str | None = Field(default=None, max_length=MAX_DECISION_NOTE_CHARS)
    round: int = Field(ge=1)
    readAt: str = Field(min_length=1, max_length=64)


class DwReviseIn(BaseModel):
    """POST to send a report back without approving it, withdraw an approval, or return a report
    that has been handed on — whichever the report's state makes it (``reviseKind`` on the read).

    ``note`` IS MANDATORY and is refused blank in the plan, with :data:`REVISE_NOTE_REQUIRED`, so an
    absent note and an empty one are told the same sentence. It goes to the designers as written, in
    the same register as an inspector's corrections, optionally about one stage.
    """

    note: str | None = Field(default=None, max_length=MAX_DECISION_NOTE_CHARS)
    stageKey: str | None = Field(default=None, max_length=120)
    fieldKey: str | None = Field(default=None, max_length=120)


class DwHandOnIn(BaseModel):
    """POST to hand an approved report on to the office.

    ``handedOnTo`` defaults to stage 20's "Submitted to", else :data:`DEFAULT_OFFICE`. ``exportId``
    pins the exported file that went — a ``DwReportExport`` row of this workshop generated at or after
    the approval. ``note`` is optional — how, or under which reference, it went.
    """

    note: str | None = Field(default=None, max_length=MAX_DECISION_NOTE_CHARS)
    handedOnTo: str | None = Field(default=None, max_length=MAX_HANDED_ON_TO_CHARS)
    exportId: str | None = Field(default=None, max_length=64)


# ── THE PLANS ────────────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class DecisionPlans:
    """The writes one decision makes, iterable in the order the route applies them: the guarded
    workshop update FIRST, so a decision that lost a race is found out before anything is inserted;
    then the correction row (only for the decisions that send the report back); then the audit row.
    """

    workshop: InspectionWritePlan
    log: InspectionWritePlan
    feedback: InspectionWritePlan | None = None

    def __iter__(self):
        writes = [self.workshop]
        if self.feedback is not None:
            writes.append(self.feedback)
        writes.append(self.log)
        return iter(writes)


def _round(round: int) -> int:
    """The round copied off the workshop, refusing the zero that means the copy was skipped."""
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
    """PRE_SUBMISSION -> APPROVED. Two writes; no correction row.

    THE WORKSHOP WRITE IS ``records.review_update`` — the decision cache every screen already reads —
    plus the approval's own keys: who, when (the cache's own moment, so the two never disagree), and
    the round. GUARDED on ``(id, PRE_SUBMISSION, round, not deleted)`` and, when the route passes the
    read stamp, on ``updatedAt`` too: what is approved is exactly what was read.
    """
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
) -> DecisionPlans:
    """Back to NEEDS_REVISION, with a mandatory sentence: whichever of the three the state makes it.

    * **RETURN** (from PRE_SUBMISSION, or NEEDS_REVISION as a no-op that still files the sentence):
      the inspector's ``send_back_plans`` VERBATIM, its suggestion marked as the authority's and its
      audit note prefixed ``RETURNED_BY_APPROVER``, guarded on the report still being under review.
    * **WITHDRAW** (from APPROVED) and **RETURN_FROM_OFFICE** (from a handed-on SUBMITTED — R1): the
      sentence becomes a correction row with ``sentBack`` and ``byApprovingAuthority`` true, filed
      against the round the report was approved in — at least 1 by construction, because APPROVED is
      reachable only from PRE_SUBMISSION, so the ``round >= 1`` CHECK the send-back satisfies is
      satisfied here too. The decision cache is ``records.review_update`` and EVERY approval and
      hand-on key is cleared: the report is no longer approved or handed on, and the next approval
      sets them afresh. The audit note is prefixed ``APPROVAL_WITHDRAWN``. Guarded on the state it was
      taken from.
    """
    from app.services.records import review_update

    cycle = _round(round)
    text = (note or "").strip()
    if not text:
        raise InspectionRuleViolation(REVISE_NOTE_REQUIRED)
    kind = revise_kind(current_status, handed_on=handed_on)
    if kind is None:
        raise InspectionRuleViolation(
            revise_refusal(current_status, handed_on=handed_on) or REVISE_NOTE_REQUIRED
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
    """APPROVED -> SUBMITTED: the approved report is recorded as handed on to the office.

    The decision cache becomes the hand-on (``records.review_update``), the approval keys stay as the
    approval left them — "approved by X on the 11th" survives into SUBMITTED, which is why they are
    columns — and the hand-on's own keys say who, when, to which office and with which exported file.

    ``ReviewLog.status`` IS ``APPROVED``, not ``SUBMITTED``: the report's review standing is still
    approved, ``SUBMITTED`` is not a ``RecordStatus`` token, and the row says what happened with its
    ``HANDED_ON`` prefix — which carries the office, so the history keeps it after a return clears the
    column. GUARDED on ``(id, APPROVED, round, not deleted)``.
    """
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


# ── THE READ BACK ────────────────────────────────────────────────────────────────────────────────


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
    """One ``ReviewLog`` row of a design workshop as a decision a screen can name, or ``None`` for a
    row this decoder does not recognise (it is left out rather than mislabelled).

    ``actorName`` arrives on the row (``include={"reviewer": True}``) and can be ``None`` — a blank
    name — which a screen prints as "an account no longer named", never a guess.
    """
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
    """Every recognised decision, in the order the rows were given (the reader asks newest first)."""
    decoded = (decision_row_payload(row) for row in rows)
    return [item for item in decoded if item is not None]
