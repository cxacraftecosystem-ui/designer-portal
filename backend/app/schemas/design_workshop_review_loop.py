"""THE PRE-SUBMISSION LOOP: the status graph, the write plans, and the resubmission rule.

The owner's requirement, in one sentence: *"Inspecting Officers can review them and submit
correction suggestions directly in a feedback box. The designer will edit the files based on these
suggestions and resubmit them. This iterative review loop continues until the sanctioning authority
is satisfied and clicks 'approve'."*

**BOTH HALVES OF THAT SENTENCE ARE BUILT (2026-10-09).** The inspector's half — a suggestion, and a
send-back — is ``api/routes/design_workshop_inspections.py``. The approving authority's half —
approve, send back or withdraw an approval, hand an approved report on to the office — is
``api/routes/design_workshop_approvals.py``, planned in ``schemas/design_workshop_approvals.py`` and
gated by ``core/deps.APPROVAL_AUTHORITY_ROLES`` and rule 7 of ``services/design_workshop_posts``.
Both build their writes from :class:`InspectionWritePlan`, which refuses a ``ReviewLog`` row the
shared enum would reject at construction, so no builder in any module can.

=======================================================================================
IT IS NOT A SECOND REVIEW MECHANISM, AND THE THREE REFUSALS THAT MADE IT ITS OWN MODULE
=======================================================================================

The repository already runs an iterative sent-back → edit → resubmit loop over six record types
(``services/records.resubmit_status``, ``review.py``'s mandatory-comment send-back,
``access.REVISION_SKIP_FIELDS``). This wave EXTENDS that machinery — it reuses
``records.review_update`` verbatim, writes its audit rows into the one ``ReviewLog`` the product
has, and spells its two new status tokens exactly as ``RecordStatus`` spells them — and it does NOT
add ``DESIGN_WORKSHOP`` to ``api/routes/review.py``'s review types. Three structural refusals, any
one sufficient:

1. ``set_review_status`` gates on ``can_review_record(reviewer, creator.role)``, a STRICTLY-GREATER
   rank comparison against the record's CREATOR. A design workshop's creator is always an ADMIN or
   MASTER_ADMIN, because the create gate admits nobody else — so an INSPECTOR at rank 37 would be
   refused every design workshop in the database and the queue would be permanently empty for the
   only tier the feature exists for.
2. That queue's scope is a ROLE CLAUSE on the creator. An inspector's scope is a per-workshop row in
   another table. ``/review/pending`` has no arm that can express it.
3. A second ``RecordStatus`` column beside ``DesignWorkshopStatus`` is two writers on one row with
   no arbitration — the defect the six promoted cover columns already pay for.

=======================================================================================
WHY THIS MODULE IS IN ``app/schemas`` AND NOT IN ``app/services``
=======================================================================================

**BECAUSE IT MUST IMPORT NOTHING THAT REACHES THE DATABASE, AND THIS IS THE PACKAGE WHERE THAT IS
ALREADY THE RULE.** ``schemas/design_workshops.py`` keeps ``DESIGN_WORKSHOP_STATUSES`` here for
exactly this reason, in its own words: "kept as a frozenset here rather than imported from the
generated Prisma client so validating a request body does not require the client to have been
generated". This module is the other half of that vocabulary — which value may follow which, what a
refusal says, and what a decision writes — and it carries the same constraint, for three payoffs:

* ``services/design_workshops.py`` can import it from inside ``save_stage`` with no import cycle;
* every rule below is assertable with no database, no event loop and no generated client, which is
  what lets ``tests/test_design_workshop_review_loop.py`` run in CI, where there is no Postgres;
* ``records.review_update`` is imported INSIDE the one function that needs it, because
  ``services/records.py`` imports ``app.core.db`` at module level and a module-level import here
  would make this file unimportable without a generated client.

``app/services/stage_schema`` IS imported at module level and that is not an exception: it touches
no database either (``schemas/design_workshops.py`` already imports ``ENUMS`` from it), and the
stage registry is the only thing that can say whether a ``stageKey`` names a real stage.

=======================================================================================
WHAT THIS MODULE DELIBERATELY DOES NOT NAME
=======================================================================================

``tests/test_dw_inspector_scope_gate.py`` sweeps every file under ``app/`` for the inspection
scope's four identifiers AS TEXT — a name in a docstring reads to it exactly like a call — and this
file is not one of the three the sweep exempts. The inspector's read-only loader and its scope
predicate are therefore referred to by description and never by name. That is not decoration: the
property being defended is that nothing outside that feature consults its scope, and this module
consults nothing at all.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any

from app.services.stage_schema import stages

# ── THE VOCABULARY ───────────────────────────────────────────────────────────────────────────────

#: Mirrored from ``schema.prisma::DesignWorkshopStatus`` for the reason the module docstring gives.
#: ``schemas/design_workshops.DESIGN_WORKSHOP_STATUSES`` is the same eight tokens as a set, and
#: ``test_design_workshop_review_loop.py`` checks the two against each other AND against the schema
#: file in both directions — a token added in one place and not the others is either unreachable or
#: a 500.
DRAFT = "DRAFT"
IN_PROGRESS = "IN_PROGRESS"
COMPLETE = "COMPLETE"
PRE_SUBMISSION = "PRE_SUBMISSION"
NEEDS_REVISION = "NEEDS_REVISION"
SUBMITTED = "SUBMITTED"
APPROVED = "APPROVED"
ARCHIVED = "ARCHIVED"

#: The states from which a decision may be taken at all, and therefore the only states in which a
#: correction suggestion may be filed. Both of them imply ``submissionRound >= 1``, which is what
#: the CHECK constraint on ``DwInspectionFeedback.round`` rests on.
UNDER_REVIEW = frozenset({PRE_SUBMISSION, NEEDS_REVISION})

#: How long a suggestion's device clock may run ahead of this server before it is refused rather
#: than corrected. BYTE-IDENTICAL to ``dictation_consent.MAX_DEVICE_CLOCK_SKEW`` and
#: ``design_ratings.MAX_DEVICE_CLOCK_SKEW``, and imported from neither, because both of those
#: modules touch the database and this one may not. A test pins the three together.
MAX_DEVICE_CLOCK_SKEW = timedelta(minutes=15)

#: How long one suggestion may be. These rows are serialised into BOTH detail reads, so an
#: unbounded column is paid for by every reader rather than by the writer. The wire cap is this
#: constant, imported by ``schemas/design_workshop_inspections`` rather than restated there — the
#: arrangement ``MAX_DESIGN_WORKSHOP_INSPECTORS`` already has with that file.
MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS = 4000

#: How many rows a detail read carries, newest first, with ``inspectionFeedbackTruncated`` beside
#: it. Bounded rather than paged: a report goes through a handful of rounds, and saying the history
#: was cut is cheaper than a second route with its own scope decision.
DESIGN_WORKSHOP_FEEDBACK_READ_LIMIT = 200

#: What a route answers when somebody tries to file a suggestion, or send a report back, on a
#: workshop that has not been handed in.
#:
#: **IT EXISTS SO THAT THE CHECK CONSTRAINT IS NEVER THE THING THAT SPEAKS.** The inspector's scope
#: is a row plus a soft-delete test and carries no status term, so an officer holding a row on a
#: DRAFT or IN_PROGRESS workshop reaches the write with ``submissionRound == 0``. Without this guard
#: Postgres refuses the INSERT on ``round >= 1``, the driver raises, and the officer is told the
#: server is broken — about a report that simply has not been handed in yet.
NOT_UNDER_REVIEW_REFUSAL = (
    "This report has not been handed in for inspection yet, so there is nothing to comment on. "
    "Correction suggestions can only be filed while a workshop is in Pre-submission or waiting for "
    "revisions — its designers hand it in from the workshop's own screen."
)

#: The same refusal for the two states the sentence above would be FALSE about (2026-10-09). An
#: approved report and one handed on to the office have both been handed in — what has happened is
#: that the inspection is over — so telling the officer "it has not been handed in yet" would be a
#: sentence about a different report. :func:`review_closed_refusal` picks.
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
    """What an officer is told on filing against a report that is not under review, by its state.

    :data:`NOT_UNDER_REVIEW_REFUSAL` for everything but the two states it would be false about: an
    APPROVED report, and a SUBMITTED one the approving authority handed on. A LEGACY SUBMITTED row —
    the word's meaning before 2026-09-13, never handed on through this product — keeps the old
    sentence, which is true of it: its designers can hand it in again.
    """
    current = str(status_value or "")
    if current == APPROVED:
        return REVIEW_CLOSED_APPROVED
    if current == SUBMITTED and handed_on:
        return REVIEW_CLOSED_HANDED_ON
    return NOT_UNDER_REVIEW_REFUSAL


# ── THE GRAPH ────────────────────────────────────────────────────────────────────────────────────

#: **THE FIRST TRANSITION GRAPH THIS PRODUCT HAS EVER HAD, AND WHAT IT COSTS.**
#:
#: Until 2026-09-13 ``PATCH /design-workshops/{id}`` copied ``status`` into the row through
#: ``_header_patch_data``'s plain field loop and the ONLY check was frozenset membership in the
#: pydantic validator. Any of the five values could follow any other; COMPLETE and SUBMITTED were
#: not gated differently from each other; and the web record page printed a named constant,
#: ``SUBMISSION_IS_REVERSIBLE``, saying so out loud in its confirmation dialog: *"the repository
#: lets any status follow any other, so nothing here is a one-way door."* That constant's own
#: docstring said *"If a future deploy makes SUBMITTED one-way on the server, this string is the
#: thing that has to change first"* — and it is changed in the same commit as this table, because an
#: irreversible act presented as reversible is the worse error.
#:
#: ── WHAT IS NOW REFUSED THAT WAS ALLOWED YESTERDAY ────────────────────────────────────────────
#:
#: 1. DRAFT is ENTRY-ONLY. Nothing may move INTO it. DRAFT means "nobody has typed into this yet"
#:    and a workshop with 22 filled stages cannot truthfully claim it; ``save_stage`` advances
#:    DRAFT -> IN_PROGRESS exactly once and would simply re-advance it, so a PATCH back to DRAFT was
#:    a word that survived until the next save.
#: 2. DRAFT / IN_PROGRESS / COMPLETE -> SUBMITTED is REFUSED. This is the visible behaviour change
#:    and it is the requirement, not a tightening: SUBMITTED now means THE APPROVED REPORT HAS BEEN
#:    HANDED ON, and the designer's forward act is PRE_SUBMISSION. Requirement 12 — "designers
#:    should be able to progressively fill and submit information as the workshop progresses" — is
#:    untouched, because entering PRE_SUBMISSION consults no scorer, exactly as entering SUBMITTED
#:    did not.
#: 3. The four edges in :data:`DECISION_EDGES` are refused to PATCH entirely. They are DECISIONS,
#:    and a decision is taken on a route that writes its ReviewLog row in the same transaction. A
#:    status change with no log entry is a decision that appears to have made itself.
#: 4. PRE_SUBMISSION -> ARCHIVED is refused. Archiving a report while officers are holding it hides
#:    it from the people who were asked to read it. Withdraw it first (-> IN_PROGRESS).
#: 5. **APPROVED -> ARCHIVED IS REFUSED, AND IT IS THE SUBTLE ONE.** It was legal in the first draft
#:    of this graph, and with ``ARCHIVED -> PRE_SUBMISSION`` also legal that is a TWO-HOP LAUNDERING
#:    PATH: any designer on the workshop could move an approved report back into the loop in two
#:    ordinary header edits, spending a submission round, with no ReviewLog row anywhere and the
#:    decision cache still reading "approved by X on the 11th". Deleting the edge closes it. An
#:    approved report is archived by handing it on first, or by the approval being withdrawn.
#:
#: ── WHAT IS DELIBERATELY STILL ALLOWED ────────────────────────────────────────────────────────
#:
#: SUBMITTED -> IN_PROGRESS and ARCHIVED -> IN_PROGRESS. Those are the "Reopen for editing" control
#: the web has shipped since the SubmissionCard landed, and taking them away would break a promise a
#: designer has been shown. SUBMITTED -> PRE_SUBMISSION is added so that the rows already carrying
#: the OLD meaning of SUBMITTED have a way into the loop without a backfill nobody can justify.
#:
#: ── AND WHAT THE ROW, NOT THE TABLE, NOW REFUSES (2026-10-09) ─────────────────────────────────
#:
#: **A REPORT HANDED ON TO THE OFFICE LOSES EVERY HEADER EDGE.** The three SUBMITTED header edges
#: above stay in the table for the legacy rows the paragraph above is about, and
#: :func:`handed_on_refusal` refuses every one of them for a row whose ``handedOnAt`` is set — the
#: hand-on is the only writer of that column, so a set value means the approving authority really
#: did hand THIS report on. Without the refusal "Reopen for editing" would be a header edit that
#: silently undid a hand-on, with no audit row and a stale "approved by" on the row, after which the
#: report could be approved and handed on a second time in a different shape: the APPROVED ->
#: ARCHIVED laundering path below, one status later. It is a refusal on the ROW and not an edge
#: removed from the table because the table cannot tell the two meanings of SUBMITTED apart and the
#: column can.
#:
#: **AND ONE DECISION EDGE LEADS OUT OF A HANDED-ON REPORT: SUBMITTED -> NEEDS_REVISION (R1).** The
#: approving authority may return a handed-on report to its designers, with a reason, through the
#: same ``/revise`` that withdraws an approval — the deferral recorded in docs/OPEN_FINDINGS.md left
#: "may an approval be withdrawn after hand-on" open, and the owner's default is yes, by the
#: authority and on the record. ``ReviewLog`` keeps both the hand-on and the return, and the export
#: ledger keeps the file that went. The approvals route refuses the edge for a LEGACY SUBMITTED row,
#: which was never approved here and has nothing to return.
LEGAL_TRANSITIONS: Mapping[str, frozenset[str]] = {
    DRAFT: frozenset({IN_PROGRESS, COMPLETE, PRE_SUBMISSION, ARCHIVED}),
    IN_PROGRESS: frozenset({COMPLETE, PRE_SUBMISSION, ARCHIVED}),
    COMPLETE: frozenset({IN_PROGRESS, PRE_SUBMISSION, ARCHIVED}),
    PRE_SUBMISSION: frozenset({IN_PROGRESS, NEEDS_REVISION, APPROVED}),
    NEEDS_REVISION: frozenset({IN_PROGRESS, PRE_SUBMISSION}),
    # NEEDS_REVISION is R1's decision edge — a handed-on report returned by the approving authority —
    # and is never a header edit; the other three are a LEGACY row's way back into the loop, refused
    # for a handed-on row by `handed_on_refusal`.
    SUBMITTED: frozenset({IN_PROGRESS, PRE_SUBMISSION, ARCHIVED, NEEDS_REVISION}),
    # TWO EDGES, AND BOTH OF THEM ARE DECISIONS. `APPROVED` is therefore the one token the graph
    # leaves with NOTHING a header edit may do, which is why the web card needs a sentence for it
    # rather than an empty button row — see `noActionsReason` in the record page.
    APPROVED: frozenset({SUBMITTED, NEEDS_REVISION}),
    ARCHIVED: frozenset({IN_PROGRESS, PRE_SUBMISSION}),
}

#: The four edges a DECISION ROUTE owns and ``PATCH /design-workshops/{id}`` may never make.
#:
#: Subtracted from the table above rather than written out as a second table, so the two cannot
#: disagree about an edge — the failure ``_CREATE_OPTIONAL_COLUMNS`` is derived to avoid.
#:
#: FIVE EDGES, AND BOTH ROUTERS THAT OWN THEM EXIST (2026-10-09). The send-back is the inspector's,
#: on ``api/routes/design_workshop_inspections.py`` — and since that date the approving authority's
#: too, sending a report back without approving it through ``/revise``. Approve, revise (withdraw an
#: approval, or return a handed-on report) and hand-on are on
#: ``api/routes/design_workshop_approvals.py``. Until that router landed APPROVED was unreachable —
#: the deferral recorded in docs/OPEN_FINDINGS.md, closed the same day. Every one of the five writes
#: its ``ReviewLog`` row in the same transaction as its status, which is the reason none of them may
#: ever be re-admitted to the header edit.
DECISION_EDGES: frozenset[tuple[str, str]] = frozenset(
    {
        # POST /design-workshop-inspections/{id}/send-back, and /design-workshop-approvals/{id}/revise
        (PRE_SUBMISSION, NEEDS_REVISION),
        (PRE_SUBMISSION, APPROVED),  # POST /design-workshop-approvals/{id}/approve
        (APPROVED, NEEDS_REVISION),  # POST /design-workshop-approvals/{id}/revise
        (APPROVED, SUBMITTED),  # POST /design-workshop-approvals/{id}/hand-on
        (SUBMITTED, NEEDS_REVISION),  # POST /design-workshop-approvals/{id}/revise (handed on only)
    }
)

#: How each token is spoken in a refusal. A client that is told "PRE_SUBMISSION" has been told the
#: column name; a designer reading "in pre-submission" has been told what is true of their report.
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

#: The sentence each decision edge is refused with, naming THE ROUTE THAT MAKES IT. Keyed by the
#: edge so that a fifth edge cannot be added to ``DECISION_EDGES`` without somebody writing down
#: which route owns it — a refusal that cannot name the next move is the one thing
#: ``_immutable_field_refusal`` and ``DESIGN_WORKSHOP_CREATE_REFUSAL`` both exist to prevent.
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
    """The statuses a HEADER EDIT may set from ``current`` — the graph less the decision edges.

    DERIVED AND NEVER LISTED. The web card's buttons are built from this same subtraction, so a
    button offering a status the server refuses, and a status the server allows with no button for
    it, are both impossible by construction rather than by somebody remembering.
    """
    allowed = LEGAL_TRANSITIONS.get(current, frozenset())
    return frozenset(nxt for nxt in allowed if (current, nxt) not in DECISION_EDGES)


#: What a header edit is told about a report the approving authority has handed on. See the
#: paragraph above ``LEGAL_TRANSITIONS`` on why the row decides this and the table cannot.
HANDED_ON_REFUSAL = (
    "This report has been approved and handed on to the office, so it cannot be reopened, handed in "
    "again or archived from here. If something in it needs correcting, ask the Ministry Admin to "
    "return it to its designers."
)


def handed_on_refusal(current: str, nxt: str, *, handed_on: bool) -> str | None:
    """The refusal for any HEADER EDIT out of a handed-on report, or ``None``.

    ``handed_on`` is whether the ROW records a hand-on (``handedOnAt`` is set). Asked by
    ``PATCH /design-workshops/{id}`` BEFORE :func:`transition_refusal`, and never by a decision
    route — the return of a handed-on report (R1) is a decision and is refused or allowed on its own
    route. A no-op is never a refusal; a legacy SUBMITTED row and every other status pass through.
    """
    if handed_on and current == SUBMITTED and nxt != current:
        return HANDED_ON_REFUSAL
    return None


def transition_refusal(current: str, nxt: str, *, by_decision_route: bool = False) -> str | None:
    """The sentence a caller is told, or ``None`` when the move is legal.

    A REFUSAL NAMES THE NEXT MOVE AND NOT ONLY THE REFUSAL — the rule ``_immutable_field_refusal``
    and ``DESIGN_WORKSHOP_CREATE_REFUSAL`` both follow. A client told "invalid transition" retries
    the same body.

    ``by_decision_route=True`` is passed by the decision routes and by nobody else; it is what makes
    the four edges in :data:`DECISION_EDGES` reachable at all. It does NOT widen anything else: a
    decision route asking for an edge the graph does not have is refused exactly as a PATCH is,
    which is what refuses a send-back on a workshop nobody has handed in.

    A NO-OP IS NEVER A REFUSAL. ``current == nxt`` is ``None``, matching ``_header_patch_data``'s
    rule that an empty save is a 200 and the unchanged summary rather than an error.

    AN UNKNOWN CURRENT STATUS FAILS CLOSED. A value this server does not recognise — a row written
    by a newer deployment — refuses every move rather than falling through to "allowed".
    """
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
    """The refusal for a move the graph does not have, in the two shapes it comes in.

    **A DEAD END MUST NOT PRINT AN EMPTY LIST.** The naive ``', '.join(sorted(...))`` over a token
    whose every outward edge is a decision produces "it can only become ." — a sentence with a hole
    in it, shown to a designer, about the one status where the answer matters most. ``APPROVED`` is
    that token today: both of its edges belong to the sanctioning authority, so what the designer
    needs to be told is WHOSE MOVE IT IS, not an empty set.
    """
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


#: THE APPROVING AUTHORITY'S CACHE ON THE WORKSHOP (2026-10-10): who approved it, in which round, and
#: when; and who handed it on, to which office, with which exported file. Kept HERE, in the
#: vocabulary module, because :func:`presubmission_header` clears them and
#: ``schemas/design_workshop_approvals`` — which writes them — imports this module, not the other
#: way round. Every key is a column on ``DesignWorkshop``; a test holds the two together.
APPROVAL_CACHE_KEYS: tuple[str, ...] = ("approvedById", "approvedAt", "approvedRound")
HAND_ON_CACHE_KEYS: tuple[str, ...] = (
    "handedOnById",
    "handedOnAt",
    "handedOnTo",
    "handedOnExportId",
)


def presubmission_header(current_status: str, *, at: datetime | None = None) -> dict[str, Any]:
    """The header write that puts a workshop into PRE_SUBMISSION, or ``{}`` when it is already there.

    **AND, SINCE 2026-10-10, IT CLEARS THE APPROVING AUTHORITY'S CACHE AND STAMPS THE HAND-IN.** A
    report entering PRE_SUBMISSION is waiting for a decision nobody has taken, so no screen may read
    "approved by X" or "handed on" off it: every key of :data:`APPROVAL_CACHE_KEYS` and
    :data:`HAND_ON_CACHE_KEYS` is nulled in the same statement as the review trio. They are already
    null on every path that can reach this — a withdrawal or a return clears them, and a handed-on
    report has no header edge back — so this is the guarantee rather than the mechanism.
    ``lastHandedInAt`` is ``at`` (the server's clock when the caller does not pass one) and is what
    the approvals queue orders its waiting reports by: ``reviewedAt`` is cleared here and cannot.

    **ONE RULE, TWO WRITE SITES, AND THIS IS WHY IT IS A FUNCTION.** ``PATCH /design-workshops/{id}``
    and ``save_stage``'s in-transaction header write both move a workshop into PRE_SUBMISSION, and
    both must increment ``submissionRound`` by exactly one in the SAME statement. Two hand-written
    ``{"increment": 1}`` dicts is how a round counter comes to say 4 on one path and 3 on the other,
    and every ``DwInspectionFeedback`` row written after that names the wrong cycle FOR EVER, because
    ``round`` is copied at write time and never recomputed. Nothing fails; the designer's "what was
    sent back in round 3" panel simply shows the wrong four suggestions.

    **IT ALSO CLEARS THE DECISION CACHE, AND THAT IS NOT TIDINESS.** ``reviewNotes``,
    ``reviewedById`` and ``reviewedAt`` are the cache of the LAST decision. A workshop that has just
    been handed back in is waiting for a decision nobody has taken yet, and leaving the trio set
    would have every screen name an officer who has not acted on this round — the same row reading
    "in pre-submission" and "sent back by X on the 3rd" at once. Nothing is lost: every sentence
    those columns ever held is also a ``DwInspectionFeedback`` row, which is the register, and the
    designer's panel is built from the rows and never from the cache.

    THE THREE CLEARED KEYS ARE EXACTLY ``records.review_update``'s non-status keys, and a test pins
    that equality rather than the literal three names — so a fourth cache column added to that
    function cannot be left uncleared here.

    Returns ``{}`` when the workshop is already in PRE_SUBMISSION, so a re-sent payload does not
    spend a round. That is the design-workshop form of the rule ``access.REVISION_SKIP_FIELDS``
    states for records: infrastructural churn must not read as an act.
    """
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


# ── THE WRITE PLANS ──────────────────────────────────────────────────────────────────────────────


class InspectionRuleViolation(ValueError):
    """A decision that cannot be written as asked. Rendered as a 422 with this sentence."""


class Operation(Enum):
    CREATE = "create"
    UPDATE = "update"


#: The tables the pre-submission loop may write to, and the whole list.
#:
#: ``DwStageEntry`` IS NOT HERE AND CANNOT BE ADDED. An inspector's suggestion stored as stage data
#: would be attributed to whoever first saved that stage and dated to the last edit of any of its
#: forty fields — ``save_stage``'s UPDATE branch writes exactly data, searchText, ordinal, deletedAt
#: and fieldProvenance, and ``updatedAt`` is ``@updatedAt``. It would also put an inspector on the
#: one write path the entire fifth access scope exists to keep them off. ``ConsentWritePlan`` makes
#: the same refusal for the same reason and this is deliberately the same shape, so a reader who
#: knows one knows the other.
WRITABLE_TABLES = frozenset({"DesignWorkshop", "DwInspectionFeedback", "ReviewLog"})

#: ``schema.prisma::RecordStatus``, mirrored for the module docstring's reason — this file may not
#: need a generated client. ``ReviewLog.status`` is typed by it, so a decision row carrying any other
#: word raises inside ``db.tx()`` and rolls the decision back with it: the officer presses the button,
#: is told the server failed, and the report appears not to have moved. ``InspectionWritePlan``
#: refuses such a row AT CONSTRUCTION, which is the repair
#: ``test_every_review_log_status_a_plan_builder_writes_is_a_recordstatus_token`` asked for — it
#: holds for a builder in any module, not only the ones that test can sweep. A test pins this set to
#: the schema in both directions.
RECORD_STATUSES = frozenset({"DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"})


@dataclass(frozen=True, slots=True)
class InspectionWritePlan:
    """One statement of a decision, checked at construction rather than at the driver.

    A PLAN AND NOT A CALL, for ``RatingWritePlan``'s reason: the rules below are then assertable
    with no database, no event loop and no generated client, and the route that applies them is a
    loop over three plans rather than three hand-written writes that can each drift.

    THE NAME SAYS "INSPECTION" AND THE TYPE NOW CARRIES EVERY DECISION — the approving authority's
    as well as the inspector's send-back — because the rules are the same rules: the same three
    tables, one named row per update, and an audit row the shared enum will accept.

    **A ``ReviewLog`` ROW IS REFUSED HERE UNLESS ITS ``status`` IS A ``RecordStatus`` TOKEN.** The
    mistake would otherwise surface as the driver refusing the INSERT inside the transaction — the
    decision rolled back, the officer told the server failed — and it is the mistake the natural
    spelling of a hand-on row (``SUBMITTED``) makes. The approvals module says what a decision did
    with the ``notes`` prefixes ``api/routes/review.py`` already uses for a reviewer's edit.
    """

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
    """The three writes one send-back makes, in the order a person would tell them.

    THEY GO IN ONE TRANSACTION. ``api/routes/review.py`` records why the decision and the log of it
    are one write: a failure in the gap leaves a report sent back with nothing anywhere saying who
    sent it back, when, or on what note — "a status change with no log entry is a decision that
    appears to have made itself".
    """

    feedback: InspectionWritePlan
    workshop: InspectionWritePlan
    log: InspectionWritePlan

    def __iter__(self):
        return iter((self.feedback, self.workshop, self.log))


def _clean_note(note: str | None) -> str:
    """The suggestion's text, or a refusal byte-compatible with ``review.py``'s send-back.

    A SEND-BACK WITH NO SENTENCE tells a designer only that a fortnight of work is wrong. That is
    the refusal ``api/routes/review.py`` already gives the six record types, and it is repeated
    verbatim rather than paraphrased so a client that renders one renders the other.
    """
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
    """The stage a suggestion is about, validated against the registry, or ``None``.

    NULL IS A REAL ANSWER AND NOT A MISSING ONE: most suggestions are about the report as a whole.

    THE STAGE KEY IS VALIDATED AND THE FIELD KEY DELIBERATELY IS NOT. A stage key the registry does
    not declare is a client bug — there are 22 of them and every client draws them from the same
    dump — while a field key an officer names can legitimately come from a client one release ahead,
    and refusing that row would lose the suggestion rather than the typo.
    """
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
) -> InspectionWritePlan:
    """One correction suggestion, as a row.

    ``by_approving_authority`` marks a sentence the approving authority wrote — a report sent back
    without approval, an approval withdrawn, a handed-on report returned — rather than an inspector.

    ``round`` IS COPIED FROM THE WORKSHOP AND NEVER SENT BY A CLIENT. Zero is refused here as well
    as by the CHECK constraint, and the refusal says which copy was skipped — because a zero can
    only mean the caller did not read ``submissionRound`` off the workshop row, and Postgres
    answering that question surfaces as a bare 500 to an officer who did nothing wrong.

    A CLOCK IN THE FUTURE IS REFUSED RATHER THAN CORRECTED. ``recordedAt`` is what the DEVICE said,
    and a device more than :data:`MAX_DEVICE_CLOCK_SKEW` ahead of this server is a device whose
    clock is wrong — storing a corrected time would file the suggestion at a moment nobody chose and
    silently disagree with the client that sent it. The same rule, the same skew and the same
    refusal as the consent log and the rating ledger.
    """
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
    return InspectionWritePlan(
        table="DwInspectionFeedback",
        operation=Operation.CREATE,
        data={
            "designWorkshopId": workshop_id,
            "round": round,
            "stageKey": _clean_stage_key(stage_key),
            "fieldKey": (field_key or "").strip() or None,
            "note": _clean_note(note),
            "sentBack": sent_back,
            "byApprovingAuthority": by_approving_authority,
            "actorId": actor_id,
            "recordedAt": recorded_at,
        },
    )


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
    """The whole of a send-back: the suggestion, the decision cache, the audit row.

    ``by_approving_authority`` and ``log_prefix`` are the approving authority's send-back without an
    approval (2026-10-09): the same three writes, the suggestion marked as the authority's and the
    audit row's note prefixed so the decision history can tell it from an inspector's. Both default
    to the inspector's send-back, which is unchanged.

    **THE WORKSHOP WRITE IS ``records.review_update`` VERBATIM AND IS NOT REBUILT HERE.** That is
    the reuse this wave promised: the same four-key dict six record types already write, so a
    workshop's decision cannot drift from theirs. It is imported INSIDE this function because
    ``services/records`` imports ``app.core.db`` at module level and this module may not — the same
    inside-the-function shape ``resubmit_status`` uses for its own import.

    ``ReviewLog.status`` IS ``RecordStatus`` AND NOT ``DesignWorkshopStatus``. ``NEEDS_REVISION`` and
    ``APPROVED`` exist in BOTH enums — that is exactly why those two spellings were chosen — and
    ``PRE_SUBMISSION`` exists in neither. **No ReviewLog row is ever written for a resubmission:**
    the resubmission is the designer's edit, it is already recorded by ``DwStageEntry.updatedAt``
    and ``fieldProvenance``, and inventing a ``RecordStatus`` value for it would be a schema change
    to the shared review enum in service of a design-workshop concept. A well-meaning "log the
    resubmission too" raises inside ``db.tx()`` and rolls the decision back with it, so the status
    would appear not to have moved.
    """
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


# ── THE READ ─────────────────────────────────────────────────────────────────────────────────────


def _iso(moment: Any) -> str | None:
    return moment.isoformat() if isinstance(moment, datetime) else None


def feedback_payload(row: Any) -> dict[str, Any]:
    """One correction suggestion as the clients read it. TWELVE KEYS, AND THEY ARE THE CONTRACT.

    The twelfth, ``byApprovingAuthority`` (2026-10-09), says the approving authority wrote it rather
    than an inspector; it is false on every row written before that date.

    SYNCHRONOUS, AND THE NAME ARRIVES ON THE ROW. ``actorName`` is read off ``row.actor``, which the
    two detail reads fetch with ``include={"actor": True}`` — so this stays a pure function over a
    row rather than a second query per suggestion, and this module stays free of the database. A
    caller that forgets the include gets ``None`` here rather than an exception, which is why the
    route tests assert a non-null name: that is the assertion that catches a missing include.

    NULL ``actorName`` IS A REAL STATE. The relation is Restrict so the account cannot have been
    deleted, but ``User.name`` can legitimately be blank, and a screen must print "an officer no
    longer named" rather than guessing at the workshop's own designer.

    BOTH MOMENTS ARE CARRIED and that is the point of the pair rather than a duplication —
    ``recordedAt`` is what the device said (null when the row was filed straight against the server)
    and ``createdAt`` is when the server heard it.
    """
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
        "actorId": getattr(row, "actorId", ""),
        "actorName": getattr(actor, "name", None) if actor is not None else None,
        "recordedAt": _iso(getattr(row, "recordedAt", None)),
        "createdAt": _iso(getattr(row, "createdAt", None)),
    }
