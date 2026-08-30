"""Who rated which sketch or prototype, when, how — and who is allowed to know.

The owner's rule for the Sketches & Prototypes review surface, in their words: *"designers rate
peers' work qualitatively and quantitatively, leave suggestions, and RANK sketches and prototypes
by drag-and-drop AND by up/down arrows — sorted by score by default, with the designer having the
final say"*, over *"two review levels: workshop peers first, then the whole pool of designers once
prototypes are finalised"*, and *"admins and master admins see who rated what, when and how;
designers see the same for their own records only"*.

This module is the ledger behind that sentence and the whole of its access control. The routes in
``app/api/routes/design_ratings.py`` are only the wire.

================================================================================================
WHAT THIS IS NOT
================================================================================================

**IT IS NOT A SECOND WAY TO ADD A PROTOTYPE.** The registry already owns sketches (stage 11,
``sketch``), sketch reviews (12), prototypes (13, ``prototype``), iterations (14) and validation
(15). Nothing here creates, edits or shadows any of them: a rating POINTS AT a ``DwStageEntry`` and
carries no design content of its own. If a fact belongs on the sketch, it belongs in the registry.

**IT IS NOT A NEW RANKING MECHANISM.** The placed order is ``DwStageEntry.ordinal``, which both
clients already derive from array order and already move with up/down arrows. :func:`rank` reads
that column; it never writes one. A drag-to-reorder is still an ordinary stage save.

**AND IT IS NOT A STAGE FIELD**, which is the design most likely to be proposed again, so the
refusal is made true by construction rather than only stated: :class:`RatingWritePlan` refuses to
name any table outside :data:`WRITABLE_TABLES`, and ``DwStageEntry`` is not in it — the same door
and the same guard as ``dictation_consent.ConsentWritePlan`` and ``ai_layers.LayerWritePlan``. Four
reasons it cannot live in a stage row, any one of them sufficient:

1. **One rating per reviewer cannot be enforced inside a JSON blob.** A stage entry's ``data`` is
   opaque to Postgres, so there is no unique index expressible over "this reviewer, this subject,
   this round". Without one, two taps on a flaky connection are two ratings.
2. **A stage row cannot say who and when.** ``save_stage``'s UPDATE branch writes exactly
   ``{data, ordinal, deletedAt}``; ``createdById`` is set on CREATE alone and ``updatedAt`` is
   ``@updatedAt`` and moves whenever anything else in that stage changes. A rating stored there
   would be credited to whoever first saved the stage and dated to an unrelated typo correction —
   and "who rated what, when and how" is the entire content of this feature.
3. **The REF field type points at repository records, never at a ``User``.** A reviewer is an
   account, and the registry has no way to name one.
4. **Level 2 is read by the very people the stage loader turns away.** Every stage read *and every
   stage WRITE* goes through ``load_workshop_or_404``, which admits three parties: the creator, an
   admin, and the holder of a ``DesignWorkshopViewer`` grant. The pool round is by definition the
   designers it refuses, so serving a rating out of the stage row would mean teaching that helper
   about POOL — and what that helper grants is read *plus* the 22 stage save routes. A rating in
   its own table is reachable through :func:`load_ratable_workshop_or_404`, a separate narrow door
   that grants nothing but these reads.

   **NOT because the pool read is cross-workshop — IT IS NOT, and an earlier draft of this
   docstring said it was.** ``workshopId`` is required in both rounds, because the placed order is
   ``DwStageEntry.ordinal`` and that column orders one collection inside one workshop; the pool
   round is the same list read by a wider audience, not a wider list. See ``round_ranking`` in
   ``app/api/routes/design_ratings.py``, which states the constraint at length.

Its precedent is ``DwWorkshopConsentDecision``: a per-decision row carrying an actor, a SERVER clock
and a DEVICE clock. That is the shape a judgement captured in a courtyard and synced a fortnight
later needs, and a rating is captured in exactly the same place. It departs from that precedent in
one respect, stated in the model's own docstring: the consent log is APPEND-ONLY and a rating is an
UPSERT, because a rating is a current opinion and a designer who moves a score from 3 to 4 has not
made two judgements. That is why this module has an amend path at all, and why everything below
about telling an amendment from a replay exists.

================================================================================================
THE MODEL THIS CODE WRITES TO — ``DwReviewRating``, AND THE ONE COLUMN IT DOES NOT HAVE
================================================================================================

The table is ``DwReviewRating`` in ``prisma/schema.prisma``, added in the same wave by the agent who
owns that file. Nothing here may edit it, so what this module depends on is written down: the
delegate name (:data:`RATING_DELEGATE`), the table name (:data:`RATING_TABLE`), and the columns
``designWorkshopId``, ``stageEntryId``, ``entityKey``, ``reviewerId``, ``round``, ``score``,
``comment``, ``suggestion``, ``ratedAt``, ``createdAt``, ``updatedAt``, under
``@@unique([stageEntryId, reviewerId, round])``. No route and no permission rule touches a column
directly — they all go through :func:`rating_plan` and :func:`rating_payload` — so a rename is those
two functions and these two constants.

**IT HAS NO ``clientKey``, AND THAT SHAPES THE IDEMPOTENCY RULE BELOW.** The obvious way to tell a
replayed outbox delivery from a genuine amendment is a token the device mints per capture; the
landed model does not carry one, and this module does not get to add a column. So the ordering is
taken from ``ratedAt``, the device clock the model DOES carry — see "OFFLINE" below, which states
exactly what that buys and what it does not. If a ``clientKey`` is ever added, :func:`rating_plan`
is the single place that changes.

================================================================================================
THE PERMISSION RULE, WHICH IS THE POINT OF THIS MODULE
================================================================================================

Two questions, deliberately kept apart, because conflating them is how this surface leaks:

* **WHO IS IN THE ROUND** — may this account rate, and see the aggregate scores that drive the
  ranking. PEER is the workshop's own party; POOL is the wider designer pool, once THIS PIECE has
  been declared finished. The pool gate is per sketch or prototype and not per workshop — see
  :data:`POOL_OPENS_WHEN_FIELD`.
* **WHO MAY READ THE LEDGER** — may this account see the individual rows: who rated, when, how.
  Admins and master admins, always. The record's own author, for their own record. Nobody else,
  ever — a peer sees the average and their own row, and no more.

Both are enforced HERE, server side. A column hidden in a client is not a control, and this module
is written so a client CANNOT be handed a row it may not have: :func:`rating_payload` redacts on
the way out rather than the routes trimming afterwards.

**404, NEVER 403.** A subject the caller may not reach answers "Record not found" with the same
detail string a missing id gets, exactly as ``services/records.require_record`` does. This
repository is keyed by cuid and a 403 would confirm which cuids exist, which is a free enumeration
of the ministry's data set for anybody with a designer login.

**THE POOL IS NOT A WIDER WORKSHOP LOAD, and that is the load-bearing structural choice.**
``load_workshop_or_404`` admits the creator, an admin, and the holder of a ``DesignWorkshopViewer``
grant — and what that admits is READ *plus stage writes*, because the stage save routes go through
that same helper. Adding "…or any designer, once the workshop is COMPLETE" to it would therefore
hand every designer in the country write access to every finished workshop's 22 stages. So the pool
round does not touch that helper at all: :func:`load_ratable_workshop_or_404` is a SEPARATE, narrow
door that yields the workshop header and the rateable rows and nothing else, and anybody who
genuinely needs the workshop itself is given a viewer grant through the mechanism that already
exists. Nothing in this module may grow a caller that widens the other one.

================================================================================================
OFFLINE, AND THE DOUBLE-FILED ROW THIS REPOSITORY HAS ALREADY SHIPPED
================================================================================================

A rating is captured in a courtyard and synced later, so the outbox can and does send the same
capture twice. Two clocks and one ordering rule handle it.

* ``ratedAt`` is the DEVICE's clock — when the designer actually judged the piece. ``createdAt`` is
  the server's and always says when the server heard it. Collapsing them fabricates one of the two
  answers; this is ``DwAiLayer.producedAt`` versus ``createdAt`` and
  ``DwWorkshopConsentDecision.recordedAt`` versus ``createdAt``, for the same reason. A device clock
  in the future is refused rather than stored — see :data:`MAX_DEVICE_CLOCK_SKEW`.

* **NO DUPLICATE ROW IS POSSIBLE**, which is the failure the wave was told to prevent and the one
  this repository has already shipped from an outbox that sent twice. ``@@unique([stageEntryId,
  reviewerId, round])`` makes a second row unrepresentable, and :func:`rating_plan` resolves a
  second delivery to an UPDATE of the row that exists rather than an insert that races it.

* **AND A STALE DELIVERY DOES NOT UNDO AN AMENDMENT**, which is the subtler half. Rate a prototype
  5, amend it to 3, then drive through a tunnel: the queued ORIGINAL arrives last and a plain
  last-write-wins upsert silently restores the 5. So arrival order is not what decides —
  :func:`rating_plan` compares the DEVICE clocks and refuses a delivery whose ``ratedAt`` is older
  than the stored one, and treats an identical ``ratedAt`` as the same capture re-delivered.

  **AT THE RESOLUTION THE COLUMN KEEPS, WHICH IS NOT A DETAIL.** ``ratedAt`` is ``TIMESTAMP(3)``
  and the query engine truncates to milliseconds on the way in, so a stored clock is never equal
  to the microsecond-precision value that wrote it. Compared exactly, "identical ``ratedAt``"
  never happens and every replay is applied as an amendment — see
  :data:`LEDGER_CLOCK_RESOLUTION`, which is where that is measured and where the comparison
  tolerates it.

  **WHAT THAT BUYS AND WHAT IT DOES NOT, stated plainly rather than left to be discovered.** It is
  strictly weaker than a per-capture ``clientKey``, which the landed model does not carry. It
  orders two captures from the SAME device correctly, because one device's clock is monotonic
  across a tunnel. It cannot order two captures from two devices whose clocks disagree, and a
  rating filed with no ``ratedAt`` at all — typed straight against the server, where there is no
  courtyard moment to record — is always applied, because there is nothing to compare it against
  and the person is on the server as it happens. Applied, but never ERASING a clock the row
  already carries: :func:`rating_plan` omits ``ratedAt`` from an amendment that has none, because
  writing None over a stored moment would both fabricate away the courtyard and disarm this whole
  rule for every delivery afterwards. Both of those are the right answer for the case
  they describe; neither is as good as a token. If a ``clientKey`` column is ever added,
  :func:`rating_plan` is the one function that changes.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any

from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import can_run_design_workshops, is_admin
from app.services.design_workshop_viewers import has_viewer_grant

logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------------------
# The vocabulary
# --------------------------------------------------------------------------------------


class RatingRound(str, Enum):
    """Which of the owner's two review levels a rating belongs to.

    Mirrors the ``DwReviewRound`` Postgres enum and the registry's ``REVIEW_ROUND`` controlled
    list, which carry the same two tokens.

    STORED ON THE ROW AND NEVER DERIVED AT READ TIME, for the reason the whole report pipeline is
    built on: a document already handed to a ministry officer must not change because a record moved
    on. A prototype whose peers rated it while ``peerRoundClosedAt`` was blank has that date filled
    in a month later, and a derived round would retroactively reclassify every one of those peer
    ratings as a pool rating — turning a room full of colleagues into a jury of strangers in the
    audit trail.
    """

    PEER = "PEER"
    POOL = "POOL"


#: The two registry entities that can be rated, and the whole list.
#:
#: NAMED RATHER THAN "anything in stages 11 and 13", because a stage holds more than its headline
#: entity — stage 13 also carries ``prototypeStageLog`` and ``prototypeMaterial``, which are child
#: rows of a prototype and not things a designer ranks. Rating one would produce a ranking of
#: material line-items sitting beside a ranking of prototypes with no way to tell them apart.
RATEABLE_ENTITIES: frozenset[str] = frozenset({"sketch", "prototype"})

#: The scale, inclusive at both ends.
#:
#: 1–5 because that is the scale this repository already uses for its only other quantitative
#: judgement — ``Feedback.rating`` and its per-aspect sub-ratings — and a product with two
#: different star scales teaches its users that neither means anything.
MIN_SCORE = 1
MAX_SCORE = 5

#: How far ahead of the server a device's clock may be before its ``ratedAt`` is refused.
#:
#: Copied deliberately from ``dictation_consent.MAX_DEVICE_CLOCK_SKEW``, and for its argument: a
#: device clock that is WRONG is the price of trusting it at all, and a judgement dated to next
#: week is not a judgement anybody made. Refused rather than corrected, because silently rewriting
#: the moment to "now" is the fabrication the two-clock split exists to prevent.
MAX_DEVICE_CLOCK_SKEW = timedelta(minutes=15)

#: The resolution a device clock actually survives a round trip to the ledger at, and the reason
#: :func:`_is_stale_delivery` compares at it rather than exactly.
#:
#: **THIS IS NOT A TOLERANCE ANYBODY CHOSE — IT IS THE STORAGE, AND UNCOMPARED IT DEFEATED THE WHOLE
#: REPLAY RULE.** ``DwReviewRating.ratedAt`` is ``TIMESTAMP(3)`` and the Prisma query engine
#: truncates a datetime to milliseconds before Postgres ever sees it, so a capture sent as
#: ``…:53.451879`` is stored and read back as ``…:53.451000``. Compared exactly, the SAME capture
#: redelivered by an outbox therefore looks strictly NEWER than the row it created: ``incoming <=
#: stored`` is false, the delivery is planned as an amendment, and the endpoint answers
#: ``replayed: false`` for a rating the server already held. The unique index still refuses a second
#: ROW — no rating is double-filed — but the row is rewritten and ``updatedAt`` moves, so "who rated
#: what, when and how" gains an amendment nobody made.
#:
#: WIDENING THE COLUMN IS NOT THE FIX, and the migration's own note on ``ratedAt`` records that
#: being measured on 2026-08-22: at ``TIMESTAMP(6)`` every row still read back with three zeroes,
#: because the truncation happens in the query engine and not in Postgres. The column matches what
#: a client can deliver; the COMPARISON is what has to tolerate it.
#:
#: What this costs, said rather than left to be found: two genuine captures less than a millisecond
#: apart from one device are read as one. That is not a loss, because after storage they ARE one —
#: the second is indistinguishable from a replay of the first in the column both are kept in.
LEDGER_CLOCK_RESOLUTION = timedelta(milliseconds=1)

#: The table this module may write to, and the whole list.
#:
#: The "a rating is not a stage field" argument in the module docstring is made TRUE here rather
#: than merely asserted: ``DwStageEntry`` is absent and :class:`RatingWritePlan` refuses to name a
#: table that is not in this set, so there is no expressible write from this module into a stage
#: row. A later change that wants ratings inside ``data`` has to delete this check, which is a
#: visible act in a diff and a failing test rather than a quiet new call site.
RATING_TABLE = "DwReviewRating"

WRITABLE_TABLES: frozenset[str] = frozenset({RATING_TABLE})

#: Named so the refusal can name it, and so a reader grepping for the stage table finds this note.
STAGE_TABLE = "DwStageEntry"

#: The Prisma delegate for the ledger. See the model block in the module docstring: another agent
#: owns ``schema.prisma``, and this constant plus that block is the whole of what a rename touches.
RATING_DELEGATE = "dwreviewrating"


# --------------------------------------------------------------------------------------
# TWO OWNER CALLS AND ONE FACT THE REGISTRY ALREADY SETTLES.
#
# The two calls are reversible defaults this code was not entitled to make on its own, and each
# lives in exactly one place so that changing the owner's mind is a one-line diff with a test
# already stating both outcomes. The third constant is not a call at all — it names a field the
# registry declares, and it sits here because a reader looking for "when does the pool open" will
# look here first and must not conclude it is ours to choose.
# --------------------------------------------------------------------------------------

#: **OWNER DECISION — does a designer see the IDENTITY of someone who rated their own record?**
#:
#: The score half is settled: a designer sees the ratings on their own record, admins see
#: everything. The NAME half was left open, so this is the one switch that answers it, and the
#: default here is the recon's recommendation rather than a decision this code is entitled to make:
#:
#: * **PEER — identity shown.** Peers share a room for a fortnight, already see each other's edits
#:   through ``fieldProvenance``, and a suggestion signed by nobody cannot be discussed over the
#:   table it was written at.
#: * **POOL — identity withheld**, which is what this constant switches. Strangers rating strangers
#:   across the country is a different social situation: an unsigned three is feedback, a signed
#:   three from a designer you will meet at the next empanelment round is a grievance.
#:
#: FLIP THIS ONE LINE to show pool raters' names to the designer whose record it is. Nothing else
#: in the codebase needs to change: :func:`access_for` reads it, :func:`rating_payload` redacts on
#: it, and ``test_design_ratings`` parametrises the matrix over both values so the suite states the
#: consequence either way. Admins are NOT affected — they see identities in both rounds always,
#: which is the owner's sentence and not a switch.
POOL_RATINGS_NAME_THEIR_RATER = False

#: The registry field that OPENS the pool round, read off the rated row itself.
#:
#: The owner said *"once prototypes are finalised"*, and the registry answers it per piece:
#: ``prototype.peerRoundClosedAt`` — *"The day this prototype was declared finished and opened to
#: designers outside the workshop. Blank means peer review is still running."* Its own declaration
#: gives the reason it is not a workshop-level flag, and it is the right one: *"prototypes finish
#: one at a time. A workshop-level flag would open the pool round on nine unfinished prototypes the
#: day the tenth was done."*
#:
#: SO THE GATE IS PER SUBJECT AND NOT PER WORKSHOP, and this module does not get a second opinion
#: about when a piece is finished. Reading the workshop's status instead — the obvious-looking
#: shortcut, and what an earlier draft of this file did — would publish nine unfinished prototypes
#: to the whole country the day the tenth was signed off, which is exactly what the registry's own
#: note refuses.
#:
#: **A SKETCH CARRIES THE SAME KEY, AND THIS NOTE USED TO SAY THE OPPOSITE.** Until the registry
#: was corrected, ``peerRoundClosedAt`` was declared on ``prototype`` alone, so :func:`pool_is_open`
#: was always False for a ``sketch`` and level 2 could never open on one — while ``RATEABLE_ENTITIES``
#: named ``sketch``, ``sketchReview.reviewRound`` offered POOL, and the ledger's ``round`` column
#: carried both tokens for either kind of subject. Three declarations assumed a sketch could reach
#: a second round and the one field that decides it was missing, which is not a decision anybody
#: took. ``sketch`` now declares it too — see the long note at its declaration in stage 11 — so this
#: constant is read off whichever row it was handed and nothing here special-cases the entity.
#: BLANK STILL MEANS CLOSED, so the correction widened nothing on its own: every sketch already in
#: the database carries no value here and reaches the pool only when somebody in the workshop dates
#: it deliberately. Two tests compare the two sets directly and in both directions, and neither
#: needs a database: ``test_design_ratings_api``, for what this API assumes about the registry, and
#: ``test_review_rating_ledger``, for the declarations agreeing with each other.
POOL_OPENS_WHEN_FIELD = "peerRoundClosedAt"

#: **OWNER DECISION — may a designer rate their own sketch or prototype?**
#:
#: No, by default. The score feeds a ranking that decides which pieces go forward, and a
#: self-awarded five is not peer review. Refused rather than merely discouraged in the UI, because
#: "we never built the button" is not enforcement.
#:
#: It is a constant rather than an inlined ``if`` because it is genuinely arguable: a designer
#: recording their own confidence in a piece is a real thing a workshop might want, and if the
#: owner wants it, flipping this is the whole change. The author still always READS their own
#: record's ledger — that is a different rule and is not affected.
#:
#: **IT SUBTRACTS THE ROW'S AUTHOR AND NOBODY ELSE.** :func:`access_for` gates it on
#: :func:`is_row_author`, never on :func:`is_own_record`: the wider predicate also counts whoever
#: created the WORKSHOP, and since only an admin may create one, reading it here meant the admin
#: running a workshop was the single account that could not rate anything in it.
SELF_RATING_IS_REFUSED = True


class RatingRuleViolation(ValueError):
    """A rating that cannot be written, refused with a sentence naming the next move.

    A ``ValueError`` rather than an ``HTTPException`` so this module stays importable — and
    testable — with no framework underneath it, exactly as ``dictation_consent.ConsentRuleViolation``
    and ``ai_layers.LayerRuleViolation`` are. The route turns it into a status code; the sentence it
    carries is written for the designer who will read it on a phone in a courtyard.
    """


class RatingSubjectGone(RatingRuleViolation):
    """The row this write was planned against is no longer there.

    A NARROW SUBCLASS RATHER THAN A NEW EXCEPTION TREE, so nothing that already catches
    :class:`RatingRuleViolation` stops catching it — but the route can answer it with the 404 the
    rest of this surface uses instead of the 422 a malformed request gets, because the caller's
    request was not malformed: a sketch, its workshop or the rating itself was deleted in the
    milliseconds between the read and the write, and the cascade took the ledger row with it.
    """


class RatingLedgerUnavailable(RuntimeError):
    """The ledger table is not in the generated Prisma client on this build.

    NAMED RATHER THAN LEFT AS AN ``AttributeError``, because the two states it distinguishes look
    identical from a stack trace and have completely different next moves: "the migration has not
    been applied to this database yet" (run it) versus "the model was renamed" (see
    :data:`RATING_DELEGATE` and the model block in the module docstring). It is also what lets the
    database-backed tests skip on a stated condition instead of erroring on a missing attribute,
    which is the difference between "not yet" and "broken".
    """


def _ledger() -> Any:
    """The Prisma delegate for :data:`RATING_DELEGATE`, or a refusal that says which of two things
    is wrong."""
    delegate = getattr(db, RATING_DELEGATE, None)
    if delegate is None:
        raise RatingLedgerUnavailable(
            f"The rating ledger delegate db.{RATING_DELEGATE} does not exist in this build's "
            f"Prisma client. Either the {RATING_TABLE} model has not been added to "
            f"prisma/schema.prisma and generated yet, or it was renamed — in which case update "
            f"design_ratings.RATING_DELEGATE and the model block in this module's docstring."
        )
    return delegate


# --------------------------------------------------------------------------------------
# The subject: one sketch or one prototype, with everything a permission rule needs
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class RatingSubject:
    """One rateable stage row, flattened into the facts the access rules actually consult.

    A FLAT DATACLASS AND NOT THE PRISMA ROW, so :func:`access_for` and :func:`rank` are pure
    functions a test can call with no database, no event loop and no generated client. Every
    database read this module does is in the ``load_*`` helpers below; every RULE is in a function
    that takes one of these.

    ``author_id`` is ``DwStageEntry.createdById`` — the designer who entered THIS sketch — and
    ``workshop_author_id`` is the workshop's creator. Both count as "their own record" for READING
    a ledger (see :func:`is_own_record`), and the sharper of the two is the first: a workshop is run
    by two designers over one set of rows, so "the designer whose sketch it is" is a per-row fact
    and not a per-workshop one.

    ONLY ``author_id`` MAKES SOMEBODY THE AUTHOR, which is what the self-rating refusal reads —
    see :func:`is_row_author`. Running a workshop is not drawing what is in it, and treating it as
    though it were refused every workshop's own admin a vote on every piece inside.
    """

    entry_id: str
    entity_key: str
    workshop_id: str
    #: Has THIS piece been declared finished and opened to designers outside the workshop? Read
    #: from :data:`POOL_OPENS_WHEN_FIELD` on the row's own ``data`` — see that constant for why the
    #: gate is per subject, and for why a sketch is gated by its own date exactly as a prototype is.
    pool_open: bool
    label: str
    ordinal: int
    author_id: str | None
    workshop_author_id: str | None


def _entry_label(entity_key: str, data: Any) -> str:
    """A short human name for one rateable row, for a ranking list to print.

    Both ``sketch`` and ``prototype`` declare ``label_field="name"`` in the registry, so that is
    tried first; the identifier column is the fallback, because a sketch saved in a hurry very
    often has a number and no name yet and "" is not something a ranking list can show. Read out of
    ``data`` rather than through the registry's label machinery deliberately: this is a display
    string on a list, and importing ``stage_definitions`` here would pull two minutes of registry
    construction into a module that otherwise needs none of it.
    """
    if not isinstance(data, Mapping):
        return ""
    for key in ("name", "sketchNo", "prototypeCode"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def pool_is_open(data: Any) -> bool:
    """Has this row been opened to designers outside the workshop?

    True only for a NON-EMPTY value in :data:`POOL_OPENS_WHEN_FIELD`. It is a DATE field in the
    registry, so what arrives here is whatever the client stored — a date string, or a blank the
    designer cleared. Anything blank, absent or unreadable means the peer round is still running,
    which is the direction that fails CLOSED: an unreadable value costs a designer a round they can
    open by filling the field in, where the opposite costs an unfinished prototype shown to the
    whole country and cannot be undone.
    """
    if not isinstance(data, Mapping):
        return False
    value = data.get(POOL_OPENS_WHEN_FIELD)
    if isinstance(value, str):
        return bool(value.strip())
    return value is not None


# ======================================================================================
# THE TWO READS OF ``DwStageEntry``, AND WHY THEY DO NOT RESOLVE FIELD PROVENANCE
# ======================================================================================
#
# ``DwStageEntry`` rows carry per-field authorship in ``fieldProvenance``, and
# ``tests/test_entry_provenance_readers.py`` asserts once per reader that every surface serving a
# stage-entry field serves the stamp beside it — with a tripwire that fails when a module starts
# reading that table without having been classified. This module is on that list as EXEMPT, and the
# argument is here rather than only in the test, because this is where somebody will be standing
# when they widen one of the two ``load_*`` helpers below.
#
# WHAT THESE TWO READS TAKE OFF A STAGE ROW IS THREE THINGS, AND NONE OF THEM IS AN ANSWER:
#
#   * ``label`` — one display string, via :func:`_entry_label`. It is the same object as the
#     intra-workshop REF picker's (``design_workshops._in_record_options``), which is exempt on the
#     stated grounds that it "returns dropdown LABELS and attributes nothing to anybody" and which
#     sends ``"data": {}`` beside every option for exactly that reason. A ranking list has to print
#     something a designer recognises; it prints the row's ``label_field`` and stops.
#   * ``pool_open`` — :data:`POOL_OPENS_WHEN_FIELD` read as a GATE. The value decides whether a
#     round is open and then never leaves the server. There is no reader to mislead about who set
#     it, because nothing is served.
#   * ``author_id`` / ``workshop_author_id`` — ``createdById``, a ROW-level fact and not a
#     field-level one, consulted by :func:`is_row_author` and :func:`is_own_record` and absent from
#     every payload this feature emits. The only authorship on the wire here is a RATING's, which
#     comes from ``DwReviewRating.reviewerId`` and has its own provenance in its own table.
#
# AND THE REASON THE EXEMPTION IS THE SAFE DIRECTION RATHER THAN MERELY THE CHEAP ONE — this is the
# half that is NOT true of the REF picker, so it is worth stating plainly. That picker is
# intra-workshop by construction; this surface is not. The POOL round is read by designers the
# workshop loader turns away (see ``load_ratable_workshop_or_404``), and this module deliberately
# withholds identities from them: :data:`POOL_RATINGS_NAME_THEIR_RATER` is ``False``, so a pool
# reader is shown scores without the names of the people who gave them. A provenance stamp carries
# ``by`` and ``byName``. Resolving provenance onto this payload would therefore export the name of
# the researcher who recorded a value, and of the designer who typed over it, to accounts holding
# no grant on the workshop — widening identity disclosure on the one surface built to narrow it.
#
# SO THE FENCE, NOT THE RESOLUTION, IS WHAT THIS READER OWES: it may carry a label and a gate, and
# a THIRD field off ``data`` is the change that makes the exemption false. That is asserted, against
# these two functions, in ``test_entry_provenance_readers.py`` beside the readers that do resolve.


async def load_subject(entry_id: str) -> RatingSubject | None:
    """The sketch or prototype behind an id, or None — which every caller must turn into a 404.

    None rather than an exception, and the routes turn it into the SAME "Record not found" a
    caller who is simply not allowed to see it gets. Three states collapse into that one answer on
    purpose: no such row, a soft-deleted row, and a row of an entity nobody rates. Telling them
    apart would let a designer walk the cuid space and learn which ids are prototypes.
    """
    if not entry_id:
        return None
    entry = await db.dwstageentry.find_unique(where={"id": entry_id})
    if entry is None or getattr(entry, "deletedAt", None) is not None:
        return None
    if entry.entityKey not in RATEABLE_ENTITIES:
        return None
    workshop = await db.designworkshop.find_unique(where={"id": entry.designWorkshopId})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        # A soft-deleted workshop's rows are not rateable by anybody, admins included. An admin
        # who needs to see them restores the workshop first, which is the same shape
        # ``load_workshop_or_404`` uses and keeps the restore the single way back.
        return None
    return RatingSubject(
        entry_id=entry.id,
        entity_key=entry.entityKey,
        workshop_id=entry.designWorkshopId,
        pool_open=pool_is_open(getattr(entry, "data", None)),
        label=_entry_label(entry.entityKey, getattr(entry, "data", None)),
        ordinal=int(getattr(entry, "ordinal", 0) or 0),
        author_id=getattr(entry, "createdById", None),
        workshop_author_id=getattr(workshop, "createdById", None),
    )


async def load_subjects(workshop_id: str, entity_key: str, workshop: Any) -> list[RatingSubject]:
    """Every rateable row of one entity in one workshop, in PLACED order.

    ``order={"ordinal": "asc"}`` is the designer's own arrangement — the thing the drag handles and
    the up/down arrows write — and :func:`rank` turns it into ``placedPosition``. The workshop row
    is passed in rather than re-read because every caller has already loaded it to decide whether
    the caller may be here at all.
    """
    rows = await db.dwstageentry.find_many(
        where={
            "designWorkshopId": workshop_id,
            "entityKey": entity_key,
            "deletedAt": None,
        },
        order={"ordinal": "asc"},
    )
    return [
        RatingSubject(
            entry_id=row.id,
            entity_key=row.entityKey,
            workshop_id=workshop_id,
            pool_open=pool_is_open(getattr(row, "data", None)),
            label=_entry_label(row.entityKey, getattr(row, "data", None)),
            ordinal=int(getattr(row, "ordinal", 0) or 0),
            author_id=getattr(row, "createdById", None),
            workshop_author_id=getattr(workshop, "createdById", None),
        )
        for row in rows
    ]


def pool_visible(
    subjects: Sequence[RatingSubject], *, is_member: bool, admin: bool
) -> list[RatingSubject]:
    """The subjects a POOL caller may be shown, which for a stranger is the OPENED ones only.

    A member of the workshop and an admin see the whole collection — they already see it on every
    other screen, and hiding rows from the people running the workshop would make their own
    ranking disagree with their own stage list. For everybody else this is the gate:
    ``peerRoundClosedAt`` is set, or the piece is not theirs to see yet.

    Applied to the LIST rather than at the door, because with a per-subject gate there is no single
    answer for the workshop — see :func:`load_ratable_workshop_or_404`, which lets a design-workshop
    role through and leaves the narrowing here. A caller left with nothing must be given the same
    404 a missing workshop gets, or this route becomes an oracle for which workshop ids exist.
    """
    if is_member or admin:
        return list(subjects)
    return [item for item in subjects if item.pool_open]


# --------------------------------------------------------------------------------------
# The permission rule
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class RatingAccess:
    """What one account may do with one subject in one round. The whole of this module's policy.

    Four separate answers rather than one "role", because they genuinely come apart and every
    conflation of them is a leak:

    * :attr:`in_round` — may rate, and may see the AGGREGATE (the average and the count) that the
      default ranking is sorted by. A peer needs the scores to rank; they do not need the names.
    * :attr:`may_read_ledger` — may see the individual rows: who rated, when, how. The owner's
      sentence, exactly: admins and master admins always, designers for their own records only.
    * :attr:`sees_rater_identity` — whether those rows arrive with a name on them. See
      :data:`POOL_RATINGS_NAME_THEIR_RATER`, which is the owner call this separates out.
    * :attr:`may_rate` — :attr:`in_round` minus the self-rating refusal.

    :attr:`visible` is the union that decides 404 versus an answer, and it is deliberately WIDER
    than :attr:`in_round`: the author of a sketch whose pool round has not opened yet is not in any
    round, but their own record is not "not found" to them.

    **:attr:`is_author` AND :attr:`is_own_record` ARE TWO DIFFERENT QUESTIONS, and collapsing them
    cost an admin their whole workshop.** ``is_own_record`` is the READING rule and admits the
    workshop's creator as well as the row's author (see :func:`is_own_record`). ``is_author`` is
    the row alone, and it is the one the self-rating refusal is gated on — because only an
    ADMIN may create a workshop (``deps.can_create_design_workshops``), so subtracting every
    workshop creator from :attr:`may_rate` meant the admin who started a workshop could not rate a
    single piece inside it, and was told "this is your own record" about a prototype somebody else
    drew.

    :attr:`is_member` is carried so a payload can ask whether the caller holds the workshop
    ITSELF — see :func:`ranked_payload`, where it decides whether the raw ``ordinal`` is disclosed.
    """

    round: RatingRound
    in_round: bool
    may_rate: bool
    may_read_ledger: bool
    sees_rater_identity: bool
    is_own_record: bool
    #: Did this caller enter THIS row? ``DwStageEntry.createdById`` alone — never the workshop's.
    is_author: bool
    is_admin: bool
    is_member: bool

    @property
    def visible(self) -> bool:
        return self.in_round or self.may_read_ledger


def is_row_author(subject: RatingSubject, user: Any) -> bool:
    """Did this caller ENTER this very sketch or prototype? ``DwStageEntry.createdById``, alone.

    THE NARROW HALF OF :func:`is_own_record`, split out because the two are used for opposite
    purposes and only this one may subtract from :attr:`RatingAccess.may_rate`. "A designer does
    not rate their own work" is a statement about the person who made the piece; the workshop's
    creator did not make it, and refusing them is not the same rule.

    A NULL AUTHOR IS NOT A MATCH — rows written before ``createdById`` was populated carry None,
    and ``None == None`` would make every such row "everybody's own" for any caller whose id was
    somehow also missing.
    """
    user_id = getattr(user, "id", None)
    return bool(user_id) and bool(subject.author_id) and user_id == subject.author_id


def is_own_record(subject: RatingSubject, user: Any) -> bool:
    """Is this the caller's OWN sketch or prototype, for "designers see the same for their own
    records only"?

    TWO CLAUSES, AND THE FIRST IS THE SHARP ONE. ``author_id`` is the ``DwStageEntry.createdById``
    of this very row — the designer who drew and entered this sketch — and that is what the owner's
    sentence means by "their own records". The workshop's creator is admitted as well because a
    workshop is the container the ministry funds and indexes under their name, and a creator who
    could not see the reviews of work filed inside their own workshop would have to ask an admin
    for a report on their own project.

    **THIS IS THE READING RULE AND NOTHING ELSE.** The second clause is justified above for READING
    a ledger and it is not a claim of authorship, so it must never reach the self-rating refusal:
    feeding it into ``may_rate`` meant that the admin who created a workshop — and only an admin
    can (``deps.can_create_design_workshops``) — was refused every rating inside it, with a 403
    saying "this is your own record" about a prototype another designer drew. :func:`is_row_author`
    is what that refusal reads instead.

    A NULL AUTHOR IS NOT A MATCH. Rows written before ``createdById`` was populated carry None, and
    ``None == None`` would make every such row "everybody's own record" for any caller whose id was
    somehow also missing. Guarded explicitly rather than left to truthiness.
    """
    user_id = getattr(user, "id", None)
    if not user_id:
        return False
    return user_id in {
        identifier for identifier in (subject.author_id, subject.workshop_author_id) if identifier
    }


def access_for(
    subject: RatingSubject,
    user: Any,
    round_: RatingRound,
    *,
    is_member: bool,
) -> RatingAccess:
    """The whole policy, as a pure function.

    ``is_member`` is "does this account hold the workshop through ``createdById`` or a
    ``DesignWorkshopViewer`` grant" — the one fact here that needs a database, resolved by
    :func:`resolve_access` and passed in. Everything else is decided from the subject, the role and
    the round, which is what lets the permission matrix be tested exhaustively without one.

    THE RULES, IN THE ORDER THEY ARE APPLIED.

    **A role outside the design-workshop set is refused everything.** ``can_run_design_workshops``
    is a SET and not a rank threshold — a PROFESSOR outranks a DESIGNER and still cannot run a
    workshop — so a "this tier and above" spelling here would quietly admit them. See
    ``deps.DESIGN_WORKSHOP_ROLES``.

    **PEER admits the workshop's own party**: its creator, anybody an admin has given a viewer
    grant, and admins. That is the room the owner meant by "workshop peers", and it is exactly the
    set ``load_workshop_or_404`` already admits — reused rather than restated, so the two cannot
    drift.

    **POOL admits any design-workshop role once THIS PIECE has been opened to them** — that is,
    once ``peerRoundClosedAt`` is set on the row (:data:`POOL_OPENS_WHEN_FIELD`) — plus admins
    unconditionally, plus the workshop's own party unconditionally. Three things follow, and all
    three are deliberate:

    * the gate is PER SUBJECT, so nine unfinished prototypes are not published the day the tenth is
      signed off. The registry's own note on that field says exactly this, and it is why this
      module does not read the workshop's status instead;
    * peers do not lose their own workshop when one of its pieces reaches the pool;
    * a ``sketch`` is gated by ITS OWN date and not by its prototypes', because the registry
      declares the same key on both entities. This clause used to read "a sketch has no such field,
      so level 2 never opens on one"; it does now. See :data:`POOL_OPENS_WHEN_FIELD`.

    **The ledger is admins, plus the author of the record.** Never a peer, in either round. A
    designer who is only a peer sees the average, the count, and their own row — which is enough to
    rank and to amend, and is the whole of what "designers see the same for their own records only"
    leaves them.

    **THE SELF-RATING REFUSAL READS AUTHORSHIP OF THE ROW, NOT "OWN RECORD".** :func:`is_own_record`
    also admits the workshop's creator, which is right for reading a ledger and wrong for rating:
    only an admin may create a workshop, so gating ``may_rate`` on it made the admin who started a
    workshop the one account that could never rate anything in it. See :func:`is_row_author`.
    """
    admin = is_admin(user)
    own = is_own_record(subject, user)
    author = is_row_author(subject, user)

    if not can_run_design_workshops(user):
        # Everything false, including for a subject this account happens to have created — which
        # cannot arise today (nothing but a design-workshop role can create a stage row) and is
        # written this way so that a future role change cannot turn a stale ``createdById`` into a
        # standing grant.
        return RatingAccess(
            round=round_,
            in_round=False,
            may_rate=False,
            may_read_ledger=False,
            sees_rater_identity=False,
            is_own_record=False,
            is_author=False,
            is_admin=admin,
            is_member=is_member,
        )

    if round_ is RatingRound.PEER:
        in_round = is_member or admin
    else:
        in_round = is_member or admin or subject.pool_open

    may_rate = in_round and not (author and SELF_RATING_IS_REFUSED)
    may_read_ledger = admin or own

    if admin:
        sees_identity = True
    elif not may_read_ledger:
        sees_identity = False
    elif round_ is RatingRound.PEER:
        sees_identity = True
    else:
        sees_identity = POOL_RATINGS_NAME_THEIR_RATER

    return RatingAccess(
        round=round_,
        in_round=in_round,
        may_rate=may_rate,
        may_read_ledger=may_read_ledger,
        sees_rater_identity=sees_identity,
        is_own_record=own,
        is_author=author,
        is_admin=admin,
        is_member=is_member,
    )


async def is_workshop_member(workshop_id: str, creator_id: str | None, user: Any) -> bool:
    """Does this account hold the workshop itself — as its creator, or through a viewer grant?

    THE ONE DATABASE FACT THE POLICY NEEDS, resolved in a single place so the ledger routes and the
    ranking route cannot disagree about who a peer is. It is deliberately the SAME pair of clauses
    ``load_workshop_or_404`` uses, minus the admin arm, which every caller here handles separately
    because an admin's access does not depend on membership at all.

    The grant read is skipped for the creator and for admins, exactly as that helper skips it, so
    the ordinary case — a designer opening their own workshop's review tab — costs no extra round
    trip.
    """
    user_id = getattr(user, "id", "")
    if not user_id:
        return False
    if creator_id == user_id:
        return True
    if is_admin(user):
        # Not a member; an admin reaches everything by role and asking the grant table would be a
        # query whose answer changes nothing.
        return False
    return await has_viewer_grant(workshop_id, user_id)


async def resolve_access(subject: RatingSubject, user: Any, round_: RatingRound) -> RatingAccess:
    """:func:`access_for` with the one database fact it needs looked up."""
    member = await is_workshop_member(subject.workshop_id, subject.workshop_author_id, user)
    return access_for(subject, user, round_, is_member=member)


async def load_ratable_workshop_or_404(
    workshop_id: str, user: Any, round_: RatingRound
) -> tuple[Any, bool]:
    """The workshop behind a round listing and whether the caller is a MEMBER of it — or a 404.

    The membership flag comes back with the workshop because the caller needs it for every row's
    per-subject access decision and re-deriving it there would either cost a grant query per row or
    tempt the caller into the wrong shortcut — comparing ``createdById`` alone, which silently
    demotes every viewer-granted co-designer to a stranger.

    **DELIBERATELY NOT ``load_workshop_or_404``, and this is the most important line in this
    module.** That helper answers "may this caller open the workshop", and what it grants is read
    PLUS STAGE WRITES, because every one of the 22 stage save routes is gated by it. The pool round
    is by definition the designers it turns away, so teaching it about POOL would hand every
    designer in the country write access to every finished workshop's fieldwork. Instead this is a
    second, narrow door that leads only to the reads in ``api/routes/design_ratings.py``: the
    rateable rows and their scores, and nothing else about the workshop. Anybody who genuinely
    needs the workshop itself is given a ``DesignWorkshopViewer`` grant through the mechanism that
    already exists for exactly that.

    404 and never 403, with the same detail string as a missing id — see the module docstring.
    """
    from fastapi import HTTPException, status  # local: keeps this module framework-free to import

    not_found = HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not workshop_id:
        raise not_found
    workshop = await db.designworkshop.find_unique(where={"id": workshop_id})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        raise not_found
    if not can_run_design_workshops(user):
        raise not_found

    member = await is_workshop_member(workshop_id, getattr(workshop, "createdById", None), user)
    if is_admin(user) or member:
        return workshop, member
    if round_ is RatingRound.POOL:
        # THE DOOR OPENS, AND THE LIST IS WHAT NARROWS. Whether a pool reader may see this
        # workshop at all is now a property of its individual rows, so it cannot be answered
        # here without reading them — and reading them here would duplicate the query the caller
        # is about to make anyway. So a design-workshop role is let through and
        # :func:`pool_visible` removes every piece that has not been opened; the caller turns an
        # empty result into the same 404, which is what keeps a stranger from learning that a
        # workshop id exists by watching this route succeed on it.
        return workshop, member
    raise not_found


# --------------------------------------------------------------------------------------
# Reading: one subject's ledger, redacted on the way out
# --------------------------------------------------------------------------------------


def rating_payload(
    row: Any,
    *,
    viewer_id: str,
    access: RatingAccess,
) -> dict[str, Any]:
    """One ledger row as the clients read it, with the identity decision already applied.

    **REDACTED HERE AND NOT IN THE ROUTE**, which is the difference between a control and a
    convention: a route that trimmed keys afterwards would leak the first time somebody added an
    endpoint that forgot to. Everything a caller must not have is absent from the dict rather than
    present and empty, so no client can render a name that was never sent.

    **THE REVIEWER ALWAYS SEES THEIR OWN ROW IN FULL.** They wrote it; withholding their own name
    from them would be theatre, and the amend flow needs to show a designer what they already said.
    That clause is why ``viewer_id`` is a parameter here at all.

    A HAND-WRITTEN DICT AND NOT A DUMP OF THE ROW, the same rule ``workshop_summary`` follows: a
    column added to the model reaches a client only by being named here. That is the point — the
    default for a new column on this table is invisible, not exposed.
    """
    mine = bool(viewer_id) and getattr(row, "reviewerId", None) == viewer_id
    payload: dict[str, Any] = {
        "id": row.id,
        "subjectId": getattr(row, "stageEntryId", None),
        "round": str(getattr(getattr(row, "round", None), "value", getattr(row, "round", None))),
        "score": getattr(row, "score", None),
        "comment": getattr(row, "comment", None),
        "suggestion": getattr(row, "suggestion", None),
        # BOTH CLOCKS, ALWAYS. "when" in the owner's sentence is ambiguous between them and only
        # sending one would decide it silently: ``ratedAt`` is when the designer judged the piece
        # and ``createdAt`` is when this server heard about it, and on this fleet those can be a
        # fortnight apart.
        "ratedAt": _iso(getattr(row, "ratedAt", None)),
        "createdAt": _iso(getattr(row, "createdAt", None)),
        "updatedAt": _iso(getattr(row, "updatedAt", None)),
        "mine": mine,
    }
    if mine or access.sees_rater_identity:
        payload["reviewerId"] = getattr(row, "reviewerId", None)
    return payload


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


def visible_rows(rows: Sequence[Any], *, viewer_id: str, access: RatingAccess) -> list[Any]:
    """The subset of one subject's ledger this caller may see AT ALL, before redaction.

    Two layers rather than one, and they answer different questions. This one is *which rows*;
    :func:`rating_payload` is *how much of a row*. A peer who may not read the ledger still gets
    their own row back — otherwise the amend flow has no way to show a designer what they already
    said, and the client would have to keep the only copy.
    """
    if access.may_read_ledger:
        return list(rows)
    if not viewer_id:
        return []
    return [row for row in rows if getattr(row, "reviewerId", None) == viewer_id]


async def subject_ratings(subject: RatingSubject, round_: RatingRound) -> list[Any]:
    """Every ledger row for one subject in one round, oldest first.

    Unredacted and unfiltered: this is the raw read, and the caller pairs it with
    :func:`visible_rows` and :func:`rating_payload`. Kept separate so the filtering is visible at
    the call site instead of buried in a query the next reader has to reconstruct.
    """
    return await _ledger().find_many(
        where={"stageEntryId": subject.entry_id, "round": round_.value},
        order={"createdAt": "asc"},
    )


async def workshop_ratings(workshop_id: str, entity_key: str, round_: RatingRound) -> list[Any]:
    """Every ledger row behind one round listing: one workshop, one entity, one round.

    One query for the whole page rather than one per subject. A workshop holds a few dozen sketches
    at most and each carries a handful of ratings, so this is a small read — and the alternative is
    the sequential-round-trip cost that ``services/records.py`` measures at 4.8 seconds for twenty
    rows on this deployment.
    """
    return await _ledger().find_many(
        where={
            "designWorkshopId": workshop_id,
            "entityKey": entity_key,
            "round": round_.value,
        },
        order={"createdAt": "asc"},
    )


# --------------------------------------------------------------------------------------
# The ranking: the aggregate the review tab is sorted by
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class RankedSubject:
    """One row of the ranked list: what it scored, where the scores put it, where the designer did.

    **TWO POSITIONS, AND THE WHOLE FEATURE IS THE GAP BETWEEN THEM.** The owner asked for "sorted
    by score by default, with the designer having the final say", which is two orders that have to
    be visible at once:

    * :attr:`default_position` is what the ratings say — the order this list would be in if nobody
      had touched it. Derived, never stored.
    * :attr:`placed_position` is what the designer says — ``DwStageEntry.ordinal``, moved by the
      drag handles and the up/down arrows both clients already have.

    A client that only ever showed the sorted order could not show a designer that they had
    overruled the scores, which is precisely the judgement the owner wants recorded.
    """

    subject: RatingSubject
    score: float | None
    count: int
    default_position: int
    placed_position: int


def rank(subjects: Sequence[RatingSubject], rows: Sequence[Any]) -> list[RankedSubject]:
    """The ranked list for one round, as a pure function over rows already read.

    Returned in PLACED order — the designer's arrangement is the one the page renders, and a client
    that wants the score order sorts by ``default_position``, which is already in the payload. That
    way the two orders travel together and no client has to recompute an average to sort.

    THE AVERAGE IS THE MEAN OF THE SCORES ON THE ROWS GIVEN, and nothing here re-weights it by
    seniority or recency. Two reasons, and the second is the one that matters: an unweighted mean is
    the only aggregate a designer can check by hand against the five numbers on their screen, and
    a ranking somebody cannot verify is a ranking they will not trust.

    **UNRATED ROWS SORT LAST, NOT FIRST**, and they are ranked rather than omitted. ``None`` is not
    zero — a sketch nobody has got to yet has not been judged badly, it has not been judged — but a
    list that dropped it would hide the pieces most in need of a review, which is the opposite of
    what a review tab is for.

    THE TIEBREAK IS TOTAL AND DETERMINISTIC — score, then how many people rated it, then the placed
    order, then the id. Without the last two, two sketches on the same average come back in
    whatever order the scan produced, and with a list that gets CUT for display that is what
    decides which one a designer never sees. That is the trap ``tests/test_design_workshop_viewers``
    documents for the eligible-viewer picker, where an unpinned tie order decided which colleague
    fell off the end of a truncated list.
    """
    totals: dict[str, list[int]] = {}
    for row in rows:
        entry_id = getattr(row, "stageEntryId", None)
        score = getattr(row, "score", None)
        if not entry_id or not isinstance(score, int):
            continue
        totals.setdefault(entry_id, []).append(score)

    placed = sorted(subjects, key=lambda s: (s.ordinal, s.entry_id))
    placed_at = {subject.entry_id: index + 1 for index, subject in enumerate(placed)}

    scored: dict[str, tuple[float | None, int]] = {}
    for subject in subjects:
        got = totals.get(subject.entry_id, [])
        scored[subject.entry_id] = (
            (sum(got) / len(got)) if got else None,
            len(got),
        )

    def default_key(subject: RatingSubject) -> tuple[int, float, int, int, str]:
        score, count = scored[subject.entry_id]
        # ``score is None`` first in the tuple pushes the unrated to the end whatever their
        # neighbours scored; the negatives make a higher score and a larger sample sort earlier
        # under an ascending sort, so there is one comparison direction in this function rather
        # than a reverse= flag that a later edit can forget on one of the two keys.
        return (
            1 if score is None else 0,
            -(score or 0.0),
            -count,
            placed_at[subject.entry_id],
            subject.entry_id,
        )

    by_score = sorted(subjects, key=default_key)
    default_at = {subject.entry_id: index + 1 for index, subject in enumerate(by_score)}

    return [
        RankedSubject(
            subject=subject,
            score=scored[subject.entry_id][0],
            count=scored[subject.entry_id][1],
            default_position=default_at[subject.entry_id],
            placed_position=placed_at[subject.entry_id],
        )
        for subject in placed
    ]


#: Decimal places the average is rounded to on the wire, for every route that sends one.
#:
#: Three rather than one: the client decides how to DISPLAY an average, and a server that
#: pre-rounded to 4.2 would make two prototypes on 4.24 and 4.16 look tied on a list whose whole
#: job is to order them.
SCORE_DECIMALS = 3


def round_score(value: float | None) -> float | None:
    """The average, rounded once for the wire, in the one place both routes read it from.

    IT LIVES HERE AND NOT IN THE ROUTES because two of them send an average — the ranked list sends
    one per row through :func:`ranked_payload`, and the subject ledger sends one in its ``summary``
    — and the same piece of work must not carry two precisions depending on which request asked for
    it. An earlier spelling had a helper in the route module whose docstring claimed both routes
    called it while :func:`ranked_payload` rounded independently one file over, which is exactly
    the drift it said it prevented.
    """
    return round(value, SCORE_DECIMALS) if value is not None else None


def ranked_payload(
    ranked: RankedSubject, *, mine: Any | None, show_ordinal: bool
) -> dict[str, Any]:
    """One ranked row on the wire.

    ``mine`` is this caller's own rating of this subject if they have one, so the review tab can
    render the control already filled in without a second request per row. It carries no identity
    decision: it is the caller's own row, which they may always see in full.

    The AVERAGE is here and the individual scores are NOT, in either direction of the permission
    rule — a peer gets the aggregate because ranking needs it, and the ledger is a separate request
    that applies :func:`visible_rows`. Folding the rows into this payload is how a listing endpoint
    ends up leaking what the detail endpoint carefully redacts.

    **``show_ordinal`` IS A DISCLOSURE DECISION AND HAS TO BE PASSED IN.** ``placedPosition`` is a
    position within what this caller may SEE — the pool narrowing happens before the ranking
    precisely so a stranger is not handed "placed 3 of 3" for the one piece they may know about.
    The raw ``DwStageEntry.ordinal`` discloses the same count directly and by the back door: a pool
    reader shown one opened prototype sitting at ordinal 7 has learned that the workshop holds at
    least eight pieces they may not open. So it travels only to the people who already see the
    whole collection on every other screen — the workshop's own party and admins — and every other
    caller gets ``placedPosition``, which is all a client needs to render the list.
    """
    payload: dict[str, Any] = {
        "subjectId": ranked.subject.entry_id,
        "entityKey": ranked.subject.entity_key,
        "label": ranked.subject.label,
        "workshopId": ranked.subject.workshop_id,
        "score": round_score(ranked.score),
        "ratingCount": ranked.count,
        "defaultPosition": ranked.default_position,
        "placedPosition": ranked.placed_position,
        "myRating": mine,
    }
    if show_ordinal:
        payload["ordinal"] = ranked.subject.ordinal
    return payload


# --------------------------------------------------------------------------------------
# Writing: a plan, so the rules are testable without a database
# --------------------------------------------------------------------------------------


class Operation(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"


@dataclass(frozen=True, slots=True)
class RatingWritePlan:
    """One intended database write, described rather than performed.

    WHY A PLAN AND NOT A COROUTINE THAT WRITES, in the words ``ai_layers.LayerWritePlan`` and
    ``dictation_consent.ConsentWritePlan`` already argue at length: a plan can be asserted about by
    ``pytest`` with no database, no event loop and no generated Prisma client — which is what makes
    the idempotency rule below coverable on a laptop rather than by a round-trip script somebody
    runs once — and a plan names its TABLE, which is what makes "a rating is never a stage field"
    true by construction rather than by convention.
    """

    table: str
    operation: Operation
    data: Mapping[str, Any]
    #: Present for an UPDATE only, and always exactly ``{"id": ...}`` — one row, named.
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in WRITABLE_TABLES:
            raise RatingRuleViolation(
                f"A design rating may not be written into {self.table}. It is a row in "
                f"{RATING_TABLE} and nowhere else: a stage entry's data is a JSON "
                f"blob, so one-rating-per-reviewer cannot be indexed inside it, and save_stage "
                f"writes only data, ordinal and deletedAt — it cannot record who rated or when."
            )
        if self.operation is Operation.UPDATE and not self.where:
            raise RatingRuleViolation(
                "An update must name the single row it changes. Pass where={'id': rating_id}."
            )
        if self.operation is Operation.CREATE and self.where:
            raise RatingRuleViolation("A create names no existing row. Drop the where clause.")


@dataclass(frozen=True, slots=True)
class RatingOutcome:
    """What one submitted rating turns into: a write, or the discovery that it already happened.

    ``plan is None`` together with ``replayed=True`` is the outbox delivering the same capture a
    second time — the case that has already shipped a double-filed record in this repository from a
    different write path. It is a SUCCESS and not an error: the device did the right thing, the
    server has the row, and the correct answer is the stored one. Answering 409 would make a phone
    with a flaky connection show a red line for a rating that is safely recorded.
    """

    plan: RatingWritePlan | None
    replayed: bool
    existing_id: str | None = None


def rating_plan(
    *,
    subject: RatingSubject,
    round_: RatingRound,
    reviewer_id: str,
    score: int,
    comment: str | None,
    suggestion: str | None,
    at: datetime,
    rated_at: datetime | None,
    existing: Any | None,
) -> RatingOutcome:
    """The write one submitted rating makes, or the finding that it is a stale or repeated delivery.

    ``existing`` is the caller's current rating of this subject in this round — the row the unique
    index ``(stageEntryId, reviewerId, round)`` allows exactly one of — read by the route and passed
    in so this function stays pure.

    **THE THREE OUTCOMES, AND THE MIDDLE ONE IS THE POINT.**

    * no existing row -> CREATE.
    * an existing row whose device clock is the SAME as, or NEWER than, this delivery's -> REPLAY.
      Nothing is written and the stored row is the answer. "The same" means the same MILLISECOND,
      because that is all ``ratedAt`` keeps — see :data:`LEDGER_CLOCK_RESOLUTION`, without which a
      redelivered capture reads as strictly newer than the row it created and this branch is
      unreachable.
    * anything else -> UPDATE. The reviewer changed their mind, and an amendment is meant to
      overwrite.

    **WHY THE DEVICE CLOCK AND NOT ARRIVAL ORDER.** Rate a prototype 5, amend it to 3, then drive
    through a tunnel: the queued ORIGINAL is delivered last, and a plain upsert restores the 5 —
    silently, with nothing on any screen saying the amendment was undone. Ordering by ``ratedAt``
    puts the two deliveries back in the order the PERSON made them, which is the order that matters.

    **WHAT THIS IS WEAKER THAN, said plainly.** A per-capture ``clientKey`` would be exact; the
    landed ``DwReviewRating`` has no such column and this module does not own the schema. So:

    * two captures from ONE device order correctly, because that device's clock is monotonic across
      the tunnel — which is the whole of the case this rule exists for;
    * two captures from TWO devices whose clocks disagree do not, and last-plausible-clock wins;
    * a delivery with NO ``ratedAt`` is always applied. It was typed straight against the server,
      where there is no courtyard moment to record and the person is present as it happens.
      **It does not, however, ERASE one.** An amendment carrying no device clock leaves ``ratedAt``
      out of the UPDATE entirely rather than writing None over what is stored — otherwise a
      designer correcting a score from a desk would delete the moment they made the judgement in
      the courtyard, and would leave the row with a NULL clock that makes every later delivery
      look fresh, which puts the tunnel regression straight back.

    ``createdAt`` is never in the data: it is the server's clock, defaulted by the column, and a
    client that could set it could date a judgement to whenever it liked. Nor is ``updatedAt``,
    which is ``@updatedAt`` and belongs to Postgres.
    """
    if not reviewer_id:
        raise RatingRuleViolation("A rating must name the account that made it.")
    if not isinstance(score, int) or isinstance(score, bool):
        raise RatingRuleViolation(f"A score is a whole number from {MIN_SCORE} to {MAX_SCORE}.")
    if not MIN_SCORE <= score <= MAX_SCORE:
        # Also a CHECK constraint on the column, deliberately in both places: the constraint is what
        # protects the average from a row written by anything that is not this function, and this is
        # what turns the refusal into a sentence a designer can read instead of a 500.
        raise RatingRuleViolation(
            f"A score of {score} is outside the {MIN_SCORE}–{MAX_SCORE} scale this product "
            f"uses everywhere else."
        )
    moment = _aware(rated_at)
    if moment is not None and moment - at > MAX_DEVICE_CLOCK_SKEW:
        raise RatingRuleViolation(
            "This rating says it was made in the future, so the device's clock is wrong. "
            "Correct the date and time on the device and sync again — the moment is not "
            "rewritten here, because a judgement dated to the sync is not the moment anybody "
            "made it."
        )

    if existing is not None and _is_stale_delivery(existing, moment):
        return RatingOutcome(plan=None, replayed=True, existing_id=getattr(existing, "id", None))

    body: dict[str, Any] = {
        "score": score,
        "comment": comment or None,
        "suggestion": suggestion or None,
    }
    # **AN AMENDMENT NEVER ERASES A COURTYARD MOMENT.** On a CREATE, ``ratedAt=None`` is the honest
    # answer — the rating was typed straight against the server and there was no earlier moment. On
    # an UPDATE it is not: writing None over a stored clock destroys the only record of when the
    # designer actually judged the piece, and it also disarms the rule this module exists for —
    # ``_is_stale_delivery`` returns False for every delivery once the stored clock is NULL, so the
    # queued original arriving out of a tunnel would go straight back over the amendment. So the
    # key is OMITTED rather than sent as None, leaving the column as Postgres already has it.
    if rated_at is not None or existing is None:
        body["ratedAt"] = rated_at
    if existing is None:
        body.update(
            {
                "designWorkshopId": subject.workshop_id,
                "stageEntryId": subject.entry_id,
                "entityKey": subject.entity_key,
                "round": round_.value,
                "reviewerId": reviewer_id,
            }
        )
        return RatingOutcome(
            plan=RatingWritePlan(
                table=RATING_TABLE,
                operation=Operation.CREATE,
                data=body,
            ),
            replayed=False,
        )
    return RatingOutcome(
        plan=RatingWritePlan(
            table=RATING_TABLE,
            operation=Operation.UPDATE,
            data=body,
            where={"id": existing.id},
        ),
        replayed=False,
        existing_id=existing.id,
    )


def _aware(value: datetime | None) -> datetime | None:
    """A device clock as an aware datetime, reading a naive one as UTC.

    Both clients send an offset; a hand-rolled request or an older build may not, and comparing a
    naive datetime with an aware one raises ``TypeError`` rather than answering wrongly — so this is
    the difference between a refusal a designer can act on and a 500.
    """
    if value is None:
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _as_stored(value: datetime | None) -> datetime | None:
    """A device clock at the resolution the ledger keeps it at.

    The stored side has already been through this truncation on its way into Postgres; the incoming
    side has not, and comparing the two as sent is what made a redelivered capture look newer than
    the row it wrote. See :data:`LEDGER_CLOCK_RESOLUTION` for the measurement and for why widening
    the column does not help. Applied to BOTH sides rather than only to the incoming one, so the
    rule reads as "compare at the resolution the column keeps" and holds for a row that never went
    to a database at all — which is how ``test_design_ratings`` exercises it.

    TRUNCATES RATHER THAN ROUNDS, matching what the query engine does, and the direction is the safe
    one either way: a stored clock that had been rounded UP would still be at or after a truncated
    incoming one, so a replay is still recognised as a replay. Reads
    :data:`LEDGER_CLOCK_RESOLUTION` rather than restating it, so the constant is the one place a
    column of a different precision would be described. It is a SUB-SECOND resolution by
    construction — ``microsecond`` is all this arithmetic touches.
    """
    if value is None:
        return None
    step = LEDGER_CLOCK_RESOLUTION // timedelta(microseconds=1)
    return value - timedelta(microseconds=value.microsecond % step)


def _is_stale_delivery(existing: Any, incoming: datetime | None) -> bool:
    """Is this delivery an older or repeated capture of a rating the server already holds?

    Only when BOTH sides carry a device clock and the incoming one is not newer, COMPARED AT THE
    RESOLUTION THE LEDGER STORES — see :func:`_as_stored`. Every other combination applies the
    write, and each for a stated reason:

    * incoming has no clock — typed against the server, and there is nothing for it to be behind;
    * the stored row has no clock — it was typed against the server, and a courtyard capture
      arriving afterwards is a new statement rather than a late one;
    * incoming is strictly newer — an amendment, which is the whole point of allowing a second
      write at all.
    """
    stored = _as_stored(_aware(getattr(existing, "ratedAt", None)))
    incoming = _as_stored(incoming)
    if incoming is None or stored is None:
        return False
    return incoming <= stored


async def existing_rating(
    subject: RatingSubject, round_: RatingRound, reviewer_id: str
) -> Any | None:
    """This caller's current rating of this subject in this round, or None.

    A ``find_first`` on the columns the unique index covers rather than ``find_unique`` on the
    compound key, so this module does not depend on the generated name Prisma gives that index. It
    is the same indexed probe either way — ``@@unique([stageEntryId, reviewerId, round])`` leads
    with the column this ``where`` leads with.
    """
    if not reviewer_id:
        return None
    return await _ledger().find_first(
        where={
            "stageEntryId": subject.entry_id,
            "round": round_.value,
            "reviewerId": reviewer_id,
        }
    )


async def record_rating(
    *,
    subject: RatingSubject,
    round_: RatingRound,
    reviewer_id: str,
    score: int,
    comment: str | None,
    suggestion: str | None,
    at: datetime,
    rated_at: datetime | None,
) -> tuple[Any, RatingOutcome]:
    """Read, plan, write — and survive the one race the read-then-write leaves open.

    **WHY THERE IS A RETRY AT ALL.** :func:`existing_rating` then :func:`apply_rating` is a
    read-then-write with a network round trip in the middle, and the case this whole module is built
    around is a phone whose outbox sends the same capture twice. Two deliveries arriving CLOSE
    ENOUGH TOGETHER both read "no existing row" and both plan a CREATE; the unique index refuses the
    second, and without this the designer gets a 500 for a rating that is safely recorded. The
    index is doing exactly its job — this is the recovery, not a workaround for it, and
    ``save_stage`` carries the same duplicate-key recovery one table over for the same reason.

    ONE RETRY AND NOT A LOOP. The second attempt reads the row the first delivery just wrote, so it
    plans an UPDATE or a replay, neither of which can hit the unique index again. A loop would be
    hiding a different bug rather than recovering from this one, and would do it silently.
    """
    existing = await existing_rating(subject, round_, reviewer_id)
    outcome = rating_plan(
        subject=subject,
        round_=round_,
        reviewer_id=reviewer_id,
        score=score,
        comment=comment,
        suggestion=suggestion,
        at=at,
        rated_at=rated_at,
        existing=existing,
    )
    try:
        return await apply_rating(outcome), outcome
    except UniqueViolationError:
        logger.info(
            "design_ratings: two deliveries of %s's rating of %s raced; re-reading and amending",
            reviewer_id,
            subject.entry_id,
        )
        existing = await existing_rating(subject, round_, reviewer_id)
        if existing is None:
            # The index refused a create and the row is STILL not there. That is not this race, so
            # it must not be swallowed as though it were: something else owns the constraint that
            # fired, and re-raising is how it stays findable.
            raise
    outcome = rating_plan(
        subject=subject,
        round_=round_,
        reviewer_id=reviewer_id,
        score=score,
        comment=comment,
        suggestion=suggestion,
        at=at,
        rated_at=rated_at,
        existing=existing,
    )
    return await apply_rating(outcome), outcome


async def apply_rating(outcome: RatingOutcome) -> Any:
    """Perform a planned write and return the stored row.

    A REPLAY IS NOT A WRITE and does not reach here with a plan: it is answered from the row
    already in the table, which is what makes the endpoint safe to call twice with the same body.

    **AND A ROW THAT VANISHED BETWEEN THE READ AND THE WRITE IS A REFUSAL, NOT A CRASH.** Both the
    replay's ``find_unique`` and the amendment's ``update`` answer ``None`` when their row is gone,
    and it CAN be gone: :func:`existing_rating` ran a network round trip ago, and deleting the
    sketch or its workshop cascades onto the ledger. Returning that None would reach
    :func:`rating_payload`, whose first line reads an attribute off it, and the designer would get
    a 500 traceback where every other unreachable record on this surface answers "Record not
    found". So it is raised as :class:`RatingSubjectGone` and the route turns it into that 404.
    """
    if outcome.plan is None:
        if not outcome.existing_id:
            raise RatingRuleViolation("Nothing to write and no stored rating to answer with.")
        stored = await _ledger().find_unique(where={"id": outcome.existing_id})
        if stored is None:
            raise RatingSubjectGone(
                "The rating this delivery repeats has been removed. Nothing was written."
            )
        return stored
    plan = outcome.plan
    if plan.operation is Operation.CREATE:
        return await _ledger().create(data=dict(plan.data))
    amended = await _ledger().update(where=dict(plan.where or {}), data=dict(plan.data))
    if amended is None:
        raise RatingSubjectGone(
            "The rating this amendment changes has been removed. Nothing was written."
        )
    return amended
