"""Request usage: buffering one row per served request, and the consent rules that gate it.

**THIS IS NOT AI-CREDIT USAGE, AND THE WORD IS ALREADY TAKEN TWICE IN THIS DIRECTORY.**
``ai_verb_cap.py`` and ``dictation_cap.py`` write ``DwAiVerbDailyUsage`` and
``DwDictationDailyUsage``, which are SPEND METERS — how many proofreads and how many transcriptions
one designer has bought today, counted so an allowance can refuse the next one. This module records
NAVIGATION: which screen was reached, what it answered, and how long the server took. The two share
a noun and nothing else, and a reader who greps ``usage`` will find three unrelated things unless
this paragraph is here. The Prisma model is ``UsageEvent``; the HTTP prefix is ``/usage``; the word
``analytics`` is deliberately not used anywhere in it, because that one is already the cross-workshop
CONTENT comparison at ``/api/analytics/design-workshops`` and at ``/admin/analytics``.

================================================================================================
WHY A BUFFER, AND WHY A WRITE PER REQUEST WAS NEVER ON THE TABLE
================================================================================================

``DATABASE_CONNECTION_LIMIT`` is 10, and it is 10 because 40 exhausted a pooler's shared
client-connection budget and crash-looped this deployment — the incident is recorded on that setting
in ``core/config.py`` and the dashboard route carries a written warning not to raise it to fit
something new in. ``concurrency.gather_reads`` is already bounded by that same 10.

The database is also in a different AWS region from the web box: ``concurrency.py`` measured ONE
Prisma round trip at 756 ms against tables whose server-side execution is 0.04–0.24 ms. Virtually
the whole cost is latency, paid once per statement and almost independent of how many rows the
statement carries. Those two facts settle the design between them:

* an INSERT inside the request path would take one of ten connections and add most of a second to
  the response, on every request, to record that the request was slow;
* one ``create_many`` of 200 rows costs very nearly what one ``create_many`` of 1 row costs, so
  batching is close to free and divides the connection cost by the batch size.

``UsageEvent`` is shaped to allow exactly that and its own docstring says so: no unique constraint
so no upsert, no counter so no read-modify-write, no ``@updatedAt`` so no row is revisited, and a
client-generated cuid so a whole batch can be inserted without the database being consulted first.
That is why this module does NOT copy ``ai_verb_cap.spend``'s ``upsert`` + ``{"increment": 1}``:
that shape is a read-modify-write per event onto a row every concurrent request contends on, which
is correct for a daily meter with one row per person per day and wrong for the highest-write table
in the schema. What IS copied from ``spend`` is its failure posture — catch, log at warning, carry
on — for the reason given under :func:`flush`.

**THE CLOCK IS STAMPED WHEN THE REQUEST FINISHES, NOT WHEN THE ROW IS WRITTEN.** ``createdAt`` has a
``@default(now())`` and this module deliberately does not use it: a row can sit in the buffer for
five seconds normally and for minutes during a database outage, so the default would date every row
in a flush to the flush. The whole justification for keeping per-request rows rather than a daily
rollup is that they preserve **the order somebody moved through the app**; dating them to the write
would destroy exactly the thing they exist for, and it would do it silently. Same discipline, same
reason, as ``dictation_consent``'s two clocks — ``recordedAt`` is when the person answered,
``createdAt`` is when the server heard it, and collapsing them fabricates one of the two.

================================================================================================
CONSENT: FLAGGED, NOT SETTLED — AND THE DEFAULT IS STATED RATHER THAN ASSUMED
================================================================================================

Watching a designer navigate is a NEW CATEGORY OF PERSONAL DATA in this repository. Everything else
it holds about a person is something that person typed in; this is something the system noticed
about them without being asked. The codebase already models consent explicitly wherever it collects
a recording, and this module follows that model in shape without pretending it has been applied:

* three states and never a boolean (:class:`UsageConsent`), so "nobody has asked" can never be read
  as "they said no";
* NULL in ``UsageEvent.consentState`` means NOBODY WAS ASKED, and it is the only way the rows
  gathered before a flow exists can be found and deleted on the day somebody decides they should be;
* the token ``"GRANTED"`` is written ONLY when a grant was actually recorded — never as a default,
  never as an optimistic guess. A row that claims a consent nobody gave is worse than no row.

**THE DEFAULT IS :data:`DEFAULT_UNASKED_COLLECTION` = ANONYMOUS, AND IT IS A DECISION SOMEBODY ELSE
IS ENTITLED TO OVERRULE IN ONE LINE.** Until a consent flow exists every account is
``NOT_RECORDED``, and this module then records the request WITHOUT the identity: route, status,
duration and client, with ``userId`` NULL and ``consentState`` NULL. That is deliberately the
middle option of three, all three of which are spelled out as real selectable values in
:class:`UnaskedCollection` so that the alternatives are visible rather than hypothetical:

* ``NOTHING`` collects nothing at all until somebody has been asked. Safest, and it means requirement
  22-25 measures nothing until a screen ships on two clients.
* ``ANONYMOUS`` — the default — answers "which screens are reached and where is it slow" while
  attributing nothing to a named colleague who was never asked.
* ``ATTRIBUTED`` also answers "what did this designer do last week", and it does so by recording a
  named person's movements without their knowledge. It is offered so the choice is made on purpose
  rather than arrived at by accident; if it is chosen, ``consentState`` is STILL NULL, because
  nobody was asked and the column must not be made to say otherwise.

The open questions the schema flags are not answered here and must not be answered by a later reader
guessing: what is asked, when it is asked, whether a refusal stops collection or only stops
attribution, and what happens to rows gathered before it was ever asked. This module ships one
defensible default for each, all in :func:`collection_plan`, and
``docs/DECISION-usage-consent-default.md`` carries the argument so the owner can overrule it without
reading the code.

**WHAT A REFUSAL COSTS THE NUMBERS, SAID OUT LOUD.** A ``REFUSED`` account is not recorded at all,
not even anonymously, so every aggregate this module produces describes *everyone who did not
refuse* and not *everyone*. Anybody reporting these figures has to say that. The alternative —
keeping a refuser's rows as "anonymous" — is worse and is what the option list above rejects: on a
route only two people use, anonymity is a label rather than a property, and a refusal that changes
nothing a person would recognise is not a refusal.

================================================================================================
THE ROUTE TEMPLATE, AND WHY THIS MODULE VALIDATES IT INSTEAD OF TRUSTING ITS CALLER
================================================================================================

``"/design-workshops/{workshop_id}/stages/{stage_key}"``, never
``"/design-workshops/3f9c…/stages/sketches"``. Every record id in this API travels in the path, so a
table of raw paths is a per-designer reading list of other people's artisans, sketches and interviews
— assembled with no access check, kept for ever, and readable by anybody who can query the table.

The middleware is supposed to pass ``scope["route"].path_format``. It is one keystroke from
``scope["path"]``, the mistake is invisible in review, and the damage is permanent because the rows
are append-only. So this module refuses the value rather than trusting the caller, in two layers of
different strength — and the difference between them is stated honestly because overclaiming here
would be the same defect in prose:

1. :func:`register_known_templates` installs the app's own route table as an ALLOW-LIST. While it is
   populated nothing outside it can be recorded, which is a real guarantee rather than a heuristic.
   This is the wiring to prefer.
2. With no allow-list registered, :func:`ensure_route_template` falls back to SHAPE: a segment must
   be a ``{placeholder}`` or a word that does not look like an identifier, and cuids, UUIDs, long hex
   runs and anything that does not begin with a letter are refused. It catches every id shape this
   API actually mints. **It cannot catch a slug** — a record whose id happened to read
   ``banarasi-brocade-weaving`` is indistinguishable from a route word, and no amount of regex fixes
   that. Layer 1 is the guarantee; layer 2 is a net under it.

Neither layer may raise into a request: see :func:`record_event`.
"""

from __future__ import annotations

import asyncio
import logging
import re
from collections import deque
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any

from app.core.db import db
from app.services.concurrency import gather_reads

logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------------------
# The two halves' shared vocabulary
#
# Declared HERE and imported by both the pure-ASGI middleware and the dependency that knows who the
# caller is, because those two halves cannot see each other: a middleware never decodes a token, and
# a dependency never sees the status code or the duration. They are stitched through
# ``scope["state"]``, and a stitch is a pair of string literals that must agree. The last time this
# repository let one contract live in two places it was the feedback field list, which is why
# ``feedback.FEEDBACK_FIELDS`` exists — same discipline, one definition, imported twice.
#
# Both names are valid Python identifiers on purpose. The dependency half writes them as
# ``request.state.usage_user_id = ...``, and Starlette's ``State.__setattr__`` puts that straight
# into ``scope["state"]`` by reference, which is how the value survives back out to the middleware.
# --------------------------------------------------------------------------------------

#: Where the dependency layer leaves the signed-in account's id for the middleware to pick up.
USAGE_USER_ID_KEY = "usage_user_id"

#: Where the dependency layer leaves the caller's resolved :class:`UsageConsent`. Absent today for
#: every request, because nothing yet asks anybody — see :func:`resolve_consent`.
USAGE_CONSENT_KEY = "usage_consent_state"

#: The header a client sets to say what it is. Nothing sends it yet: as of 2026-08-29 neither
#: ``frontend/lib/api.ts`` nor the Android network layer builds it, so every row records the
#: ``"api"`` fallback until one of them does. That is honest — an unlabelled client IS an unknown
#: client — and it is why :data:`DEFAULT_CLIENT_APP` is a value rather than a NULL.
CLIENT_APP_HEADER = "x-client-app"

#: The three the column is documented to carry. Anything else normalises to the fallback rather than
#: being stored: this value is CLIENT-SUPPLIED, and one handset shipping a typo must not be able to
#: put an unbounded string into a batch of two hundred unrelated rows.
CLIENT_APPS: frozenset[str] = frozenset({"web", "android", "api"})

#: What a request that did not say gets recorded as. Mandated by the schema — the column is NOT NULL
#: with no database default, so this module supplies it or the INSERT fails.
DEFAULT_CLIENT_APP = "api"

#: What a request that matched no route records. A FIXED placeholder and never the path that was
#: wanted: a 404 on ``/artisans/3f9c…`` would otherwise smuggle a record id back in through the one
#: door nobody thinks to guard, and unmatched paths are also where a scanner's junk lands.
UNMATCHED_ROUTE = "<unmatched>"

#: What a template this module REFUSED records as — distinct from :data:`UNMATCHED_ROUTE` because
#: they are different failures and one column must not mean two things. ``<unmatched>`` is the
#: ordinary, expected fate of a 404; ``<unsafe>`` means a caller handed this module something that
#: was not a template, which is a bug in the caller and appears in the log as one.
UNSAFE_ROUTE = "<unsafe>"


# --------------------------------------------------------------------------------------
# The batching numbers. Each of these is a decision with an arithmetic behind it, not a taste.
# --------------------------------------------------------------------------------------

#: Rows per ``create_many``, and the number that divides the connection cost.
#:
#: One Prisma round trip costs ~756 ms of latency against 0.04–0.24 ms of server-side work
#: (``concurrency.py``), so the statement's cost barely moves with its row count. At 200 the
#: amortised cost of the pool connection is 1/200th of a request; at 1,000 it would be 1/1000th and
#: the marginal gain is nothing, while the row a designer generated would wait five times longer to
#: become durable and a lost process would take five times as much with it. 200 is where those two
#: curves stop being interesting.
FLUSH_ROWS = 200

#: Seconds between flushes when the size threshold has not been reached.
#:
#: The same 5.0 as ``MEDIA_QUEUE_INTERVAL_SECONDS`` (``core/config.py``), deliberately, so an
#: operator reads ONE background cadence off this box instead of two. It also bounds what an unclean
#: shutdown loses to five seconds of traffic — and :func:`flush_all` is what makes a CLEAN shutdown
#: lose nothing at all.
FLUSH_INTERVAL_SECONDS = 5.0

#: How often the worker wakes to see whether the buffer has already reached :data:`FLUSH_ROWS`.
#:
#: The requirement is "size threshold OR time interval, whichever comes first", and the obvious
#: primitive for that is an ``asyncio.Event`` the recorder sets. It is not used, for a concrete
#: failure: an Event held at module scope belongs to the loop it was first awaited on, and a test
#: suite that builds a fresh event loop per test then gets ``got Future attached to a different
#: loop`` from a module that has nothing to do with the test. A poll has no loop affinity at all.
#: Four wakeups a second, each a length check on a deque, is not measurable next to a 756 ms query.
FLUSH_POLL_SECONDS = 0.25

#: The hard ceiling on buffered rows. **THE BUFFER DROPS RATHER THAN GROWS, ALWAYS.**
#:
#: An unbounded queue behind a database that has gone away is a memory leak with a timer on it, and
#: the outage it would cause is the API falling over — which would mean instrumentation collected for
#: a research paper took the field's app down. That trade is never worth making: a usage row is an
#: observation, not a record of anybody taking responsibility for anything, and losing some is
#: strictly better than losing the service.
#:
#: The arithmetic, so the number can be argued with rather than guessed at again. A buffered row is a
#: small dict of eight keys, on the order of a kilobyte in CPython once its strings are counted, so
#: 5,000 of them is about 5 MB — a rounding error on a 1 GiB box, and small enough that the ceiling
#: is not itself a memory risk.
#:
#: **IT IS A MEMORY BUDGET AND NOT AN OUTAGE BUDGET, AND THE DIFFERENCE IS THE WHOLE OF THIS
#: PARAGRAPH.** An earlier version of this comment read "at a sustained 20 requests a second, 5,000
#: rows is roughly four minutes of total database unavailability before anything is lost at all",
#: which is wrong, and wrong in the direction an operator would plan around: it invites somebody to
#: read this constant as how long Postgres may be away. Simulated against this module's own writer at
#: exactly that rate — a database that refuses every write, 20 requests a second, the real
#: :func:`run_flush_worker` cadence — the first row is lost after **5.25 seconds**, and four minutes
#: in, **4,720 of 4,800 rows are gone** with ``droppedAtCeiling`` still reading zero.
#:
#: The reason is :data:`FLUSH_MAX_ATTEMPTS`, one constant down. A drained batch is offered twice and
#: then ABANDONED, so the buffer is emptied by the writer whether or not the write lands, and it
#: never accumulates towards this ceiling at all: the ceiling is reached only when rows arrive faster
#: than the flusher can drain-and-abandon them, which is somewhere north of 200 rows per flush cycle.
#: **During an outage the loss path is abandonment, not eviction.** Both are counted, and
#: ``GET /usage/collection`` reports them under separate names for this reason —
#: ``abandonedAfterFailedWrites`` is the one that moves when the database is away, and
#: ``droppedAtCeiling`` is the one that moves when this process is simply producing faster than it
#: can write. Reading the second where the first belongs would understate an outage as zero.
BUFFER_CEILING = 5_000

#: How many times one drained batch is offered to the database before it is abandoned.
#:
#: Two — one attempt and one retry. Zero retries would throw 200 rows away on a blip that a second
#: attempt would have survived. Unlimited retries are worse than either: a batch that fails for a
#: reason retrying cannot fix would be re-offered every five seconds for ever, holding its slot while
#: fresh rows are evicted by the ceiling behind it, which converts a bounded buffer into total loss
#: plus an endless error log. So the loss stays proportional to the outage, and the abandonment is
#: counted and logged rather than silent. ``MEDIA_QUEUE_JOB_MAX_ATTEMPTS`` is the house precedent for
#: bounding attempts at all.
#:
#: **THIS CONSTANT, NOT :data:`BUFFER_CEILING`, IS WHAT DECIDES WHAT AN OUTAGE COSTS** — see the
#: measured figures on that constant. Two attempts spread over one flush interval means roughly five
#: seconds of database unavailability is survivable and anything longer is not: from then on, every
#: batch is written off at the rate the writer drains them. That is a deliberate trade — a poison
#: batch, one row Postgres will never accept, must not be able to block every legitimate row behind
#: it for the life of the process — and it is the right one while these rows are observations rather
#: than a ledger. It is also the number to change, and the only one, if somebody decides a longer
#: outage should be survivable: raising :data:`BUFFER_CEILING` alone would achieve nothing whatever.
FLUSH_MAX_ATTEMPTS = 2

#: Longest ``routeTemplate`` accepted. The column is TEXT and imposes nothing; this does, because a
#: runaway string riding into a batch of 200 is 200 rows lost, not one.
MAX_TEMPLATE_LENGTH = 255

#: Longest ``durationMs`` recorded. ``statusCode`` and ``durationMs`` are INTEGER, i.e. 32-bit
#: signed, and this is that ceiling (24.8 days). Nothing should ever approach it — but a clock that
#: steps, or a connection held open by a stalled upload, would produce a value Postgres rejects, and
#: a rejected value on a batched writer discards the whole flush rather than the one bad row. Clamped
#: rather than dropped, so the request is still counted.
MAX_DURATION_MS = 2_147_483_647


# --------------------------------------------------------------------------------------
# The read-side numbers.
# --------------------------------------------------------------------------------------

#: Below this many IDENTIFIED accounts on a route, its aggregate is withheld — reported as ``None``,
#: which is a REFUSAL and never a zero.
#:
#: Five, matching ``workshop_analytics.MIN_WORKSHOPS_FOR_RATE`` and chosen for the same reason: three
#: is the number in the brief's own example of a lie told with arithmetic. The exposure here is
#: sharper than a craft outcome, though. "Who opened the artisan screen at 2 a.m." is answerable from
#: a page labelled *aggregates* the moment a route has one user in the window, and the window is
#: chosen by whoever is asking.
#:
#: **ROWS WITH A NULL ``userId`` DO NOT COUNT TOWARDS IT, AND THAT IS NOT AN OVERSIGHT.** The floor
#: protects identified people; a row that identifies nobody cannot de-anonymise anybody. Counting
#: NULLs as a user would also withhold the sign-in routes, which are almost entirely unauthenticated
#: — and "the sign-in page is slow for the people who cannot get in" is named in the schema as
#: precisely the thing this table should be able to show. So a route with NO identified users reports
#: freely, and a route with between one and four is withheld entirely. A reader "restoring
#: consistency" by counting the NULLs would silently delete that capability.
MIN_IDENTIFIED_USERS_FOR_ROUTE = 5

#: Widest window either aggregate will answer over.
#:
#: Both queries ride an index whose leading column is an equality, so a wide range is a long index
#: scan rather than the whole-table scan the schema refuses to build an index for — but this is by
#: far the highest-write table in the database and "the last five years" is not a question anybody
#: has asked. A year and a day covers every year-on-year comparison; anything wider is a report that
#: should be built from a rollup somebody designs on purpose.
MAX_RANGE_DAYS = 366

#: Most templates one :func:`usage_for_routes` call may name.
#:
#: The IN list becomes a bitmap of index probes, which is cheap while it is short and stops being an
#: index strategy when it is long. Fifty is more than the number of routes any one screen reports on.
MAX_TEMPLATES_PER_QUERY = 50


# --------------------------------------------------------------------------------------
# Refusals
# --------------------------------------------------------------------------------------


class UsageRuleViolation(ValueError):
    """A usage value that cannot be recorded, refused with a sentence naming what was wrong.

    A ``ValueError`` and not an ``HTTPException``, so this module stays importable — and testable —
    with no framework underneath it, exactly as ``dictation_consent.ConsentRuleViolation`` and
    ``ai_layers.LayerRuleViolation`` are. A route turns it into a status code.

    **NOTHING ON THE REQUEST PATH RAISES THIS.** :func:`record_event` catches it, substitutes
    :data:`UNSAFE_ROUTE` and logs, because a measurement that can 500 a designer's sketch upload is
    worse than no measurement. It is raised by :func:`ensure_route_template` and by the read helpers,
    which are called from places where a refusal is the right answer.
    """


# --------------------------------------------------------------------------------------
# Consent
# --------------------------------------------------------------------------------------


class UsageConsent(str, Enum):
    """Whether this account has agreed to have its navigation recorded.

    Three states and not a boolean, for ``DwDictationConsent``'s reason applied to a different fact:
    "nobody has asked this designer yet" and "this designer said no" are different situations with
    different next moves, and a boolean would have had to default to false for every account already
    in the database — making every never-asked colleague indistinguishable from a refusal nobody ever
    made.

    A SEPARATE enum from ``DwDictationConsent`` although the three tokens read the same, and the
    schema is explicit about why: that one is scoped to an ARTISAN'S AUDIO leaving a device in a named
    workshop, and reusing it would file a designer's browsing consent and an artisan's recording
    consent as one fact — after which the day either meaning moves, the other moves with it.
    """

    #: Nobody has been asked. Every account today, and the state this module fails to.
    NOT_RECORDED = "NOT_RECORDED"
    GRANTED = "GRANTED"
    REFUSED = "REFUSED"


class UnaskedCollection(str, Enum):
    """What is collected from an account nobody has asked yet. **THE OWNER'S DECISION, NOT THIS
    MODULE'S** — the three options are named so the choice is visible and can be changed in one line.

    See :data:`DEFAULT_UNASKED_COLLECTION` for which one is in force and the argument for it.
    """

    #: Record nothing at all until somebody has been asked. Nothing is observed, and requirement
    #: 22-25 measures nothing until a consent screen ships on both clients.
    NOTHING = "NOTHING"

    #: Record the request and drop the identity: route, status, duration and client, with ``userId``
    #: NULL. Answers "which screens are reached, and where is it slow" and cannot answer "what did
    #: this named person do".
    ANONYMOUS = "ANONYMOUS"

    #: Record the request AND attribute it. This observes a named colleague who was never asked, and
    #: naming it as an option is the point — somebody choosing it should be choosing it. Note that
    #: ``consentState`` stays NULL under this setting: the rows are attributed, and they still say
    #: truthfully that nobody was asked.
    ATTRIBUTED = "ATTRIBUTED"


#: **THE HONEST DEFAULT, AND IT IS DELIBERATELY THE MIDDLE ONE OF THE THREE.**
#:
#: Until a consent flow exists, this module records WHAT WAS REACHED and not WHO REACHED IT. The
#: reasoning, stated so it can be overruled rather than merely obeyed:
#:
#: * ``NOTHING`` is the safest and it is not free. The requirement is to understand how designers
#:   move through the platform and where it is slow, and today nothing in this system can answer
#:   either half; choosing ``NOTHING`` means it still cannot, for as long as it takes to design,
#:   build and ship a consent screen on web and on Android. Route, status and duration with no
#:   identity attached answer the "where is it slow" half in full and the "how do they move" half in
#:   aggregate, and they do it while nobody's name is in the table.
#: * ``ATTRIBUTED`` is what most products do by default and it is the one thing this module will not
#:   do by default. Building a per-designer trail of colleagues who were never asked, in a repository
#:   that already refuses to send an artisan's voice anywhere without a recorded answer, would be
#:   this codebase applying one standard to the people it studies and another to the people it is
#:   built for.
#:
#: **WHAT THIS DEFAULT IS NOT.** It is not consent, and no row it writes claims to be: every row it
#: produces carries ``consentState`` NULL, which the schema defines as NOBODY WAS ASKED, and which is
#: the only thing that makes those rows findable and deletable on the day somebody decides they
#: should be. To overrule: change this one value. ``docs/DECISION-usage-consent-default.md`` carries
#: the argument in prose, and the table of what each option costs, for whoever makes that call.
DEFAULT_UNASKED_COLLECTION = UnaskedCollection.ANONYMOUS

#: The name this module will read off a ``User`` row once somebody builds the flow. It does not exist
#: yet — adding it is a Prisma migration this module may not write — and :func:`resolve_consent`
#: therefore answers ``NOT_RECORDED`` for everybody. Named as a constant rather than inlined so the
#: day the column lands there is exactly one line to change and one grep to find it.
CONSENT_ATTRIBUTE = "usageConsent"


@dataclass(frozen=True, slots=True)
class CollectionPlan:
    """What may be recorded about one request, given the consent on file.

    A value rather than a branch inside :func:`record_event`, for ``dictation_consent.Send``'s
    reason: this is the whole of the consent policy, it is the thing a reviewer needs to read, and it
    should be greppable, testable and printable without a database or a web framework anywhere near
    it.
    """

    #: Whether a row is written at all.
    record: bool
    #: Whether that row carries the account id. False means ``userId`` is NULL.
    attribute: bool
    #: What goes into ``consentState``. **Only ever ``"GRANTED"`` or None.** None means NOBODY WAS
    #: ASKED, exactly as the column is documented, and it is never written to mean anything else.
    #: There is no ``"REFUSED"`` token because a refusal produces no row to put it on.
    consent_state: str | None
    #: One sentence, for a log line or a methodology note. Written for a person, not a machine.
    reason: str


def resolve_consent(user: Any) -> UsageConsent:
    """This account's answer about being observed. Today: ``NOT_RECORDED``, for everybody.

    **NOTHING IN THIS SYSTEM ASKS ANYBODY YET, AND THIS FUNCTION DOES NOT PRETEND OTHERWISE.** There
    is no column, no route and no screen; the schema flags the question as open and this module may
    not answer it by inventing a store. So the honest answer for every account is "nobody has asked",
    and :data:`DEFAULT_UNASKED_COLLECTION` decides what that means for collection.

    It is a real function with a real signature all the same, and it is SYNCHRONOUS and does no
    database work on purpose. When the flow exists it reads :data:`CONSENT_ATTRIBUTE` off the ``User``
    row the dependency layer has ALREADY loaded to authenticate the request — so wiring it up costs
    no extra round trip on the request path, which is the constraint :func:`record_event` is built
    around. One line changes here; no caller changes at all.

    UNREADABLE RESOLVES TO ``NOT_RECORDED``, never to ``GRANTED``: a null, an unknown token, a
    wrong-cased string, a row restored from before the column existed. That is the same posture as
    ``dictation_consent.consent_of`` and for a related reason, though it is worth being precise about
    what "safe" means in each. There, an unreadable value gates a send, and the cost of guessing
    wrong is a named artisan's recorded voice reaching a third party. Here it means the request is
    recorded without a name on it — the cost of guessing wrong is a weaker dataset, and the cost of
    guessing the other way is attributing a colleague's movements on the strength of a value nobody
    could read.
    """
    raw = getattr(user, CONSENT_ATTRIBUTE, None)
    token = str(getattr(raw, "value", raw) or "")
    if not token:
        return UsageConsent.NOT_RECORDED
    try:
        return UsageConsent(token)
    except ValueError:
        # Logged rather than swallowed: a token outside the enum means either a newer build wrote it
        # or somebody edited the row by hand, and both are worth finding. It still resolves to
        # "nobody was asked", which is the answer that claims the least.
        logger.warning(
            "usage: account %s carries the unreadable consent %r; treating it as NOT_RECORDED",
            getattr(user, "id", "?"),
            token,
        )
        return UsageConsent.NOT_RECORDED


def collection_plan(
    consent: UsageConsent | None,
    *,
    unasked: UnaskedCollection | None = None,
) -> CollectionPlan:
    """The three-way rule, in one place: what is recorded for GRANTED, for REFUSED, and for neither.

    ``consent=None`` is read as :attr:`UsageConsent.NOT_RECORDED`. That is not a convenience — it is
    the case that actually happens, because the middleware finds nothing under
    :data:`USAGE_CONSENT_KEY` for every request until a flow exists, and "the stitch found nothing"
    and "nobody has asked this person" are the same fact today.

    **GRANTED** — recorded and attributed, with ``consentState = "GRANTED"``. The only circumstance
    in which that token is ever written, so a row carrying it is a row somebody actually agreed to.

    **REFUSED** — nothing is recorded. Not an anonymous row either, and this is the sharpest of the
    three decisions, so here is the argument. Keeping a refuser's rows as "anonymous" is what a
    system does when it wants the number more than it wants the answer: on a route only two people
    use, anonymity is a label and not a property, and a refusal that changes nothing the person would
    recognise is a preference, not a permission. The cost is stated in the module docstring and must
    be stated by anybody reporting these figures — the aggregates describe everyone who did not
    refuse.

    **NOT_RECORDED** — whatever :data:`DEFAULT_UNASKED_COLLECTION` says, and by default the request
    without the identity. ``consentState`` stays NULL under every one of the three options, including
    ``ATTRIBUTED``: nobody was asked, and the column exists to keep saying so.

    ``unasked`` is an argument rather than only a module constant so a test can exercise all three
    branches without mutating global state, and so a future settings-backed flag has somewhere to
    plug in without this function changing shape.
    """
    policy = unasked if unasked is not None else DEFAULT_UNASKED_COLLECTION
    state = consent if consent is not None else UsageConsent.NOT_RECORDED

    if state is UsageConsent.GRANTED:
        return CollectionPlan(
            record=True,
            attribute=True,
            consent_state=UsageConsent.GRANTED.value,
            reason="This account agreed to have its use of the platform recorded.",
        )
    if state is UsageConsent.REFUSED:
        return CollectionPlan(
            record=False,
            attribute=False,
            consent_state=None,
            reason=(
                "This account declined to have its use of the platform recorded, so nothing about "
                "this request is kept — not anonymously either."
            ),
        )
    if policy is UnaskedCollection.NOTHING:
        return CollectionPlan(
            record=False,
            attribute=False,
            consent_state=None,
            reason=(
                "Nobody has been asked yet whether their use of the platform may be recorded, and "
                "this deployment collects nothing until they have been."
            ),
        )
    if policy is UnaskedCollection.ATTRIBUTED:
        return CollectionPlan(
            record=True,
            attribute=True,
            # NULL, and not "GRANTED", although the row is attributed. Nobody was asked; a row that
            # claimed otherwise would be this module forging the one distinction the column exists
            # for.
            consent_state=None,
            reason=(
                "Nobody has been asked yet whether their use of the platform may be recorded. This "
                "deployment records and attributes it anyway; the row says truthfully that no "
                "answer was ever given."
            ),
        )
    return CollectionPlan(
        record=True,
        attribute=False,
        consent_state=None,
        reason=(
            "Nobody has been asked yet whether their use of the platform may be recorded, so the "
            "request is kept without the identity: which screen, what it answered, how long it "
            "took, and no name."
        ),
    )


@dataclass(frozen=True, slots=True)
class Withdrawal:
    """What a withdrawal actually reached. Returned by :func:`withdraw`; safe to ignore."""

    #: Rows this account had waiting in the buffer, thrown away before they could be written.
    buffered_dropped: int
    #: Rows already in the database, deleted.
    stored_deleted: int
    #: False when the delete could not be run at all. The buffer purge always happens.
    stored_delete_ran: bool


async def withdraw(user_id: str) -> Withdrawal:
    """Stop recording this account, drop what is buffered for it, and delete what is stored.

    **THIS IS WHAT MAKES A WITHDRAWAL A WITHDRAWAL**, and it is ``dictation_consent
    .cancel_pending_transcriptions``'s argument applied to a different queue. There, recording
    REFUSED closes the gate against future sends, but a recording queued on the 3rd is a row sitting
    in ``MediaProcessingJob`` that would still go out on the night of the 9th unless something reaches
    in and stops it — *"a consent that cannot recall what it already authorised is a preference, not
    a permission."* Here the queue is this module's own buffer: rows already observed and not yet
    written. Marking an account refused without emptying it would write that account's last five
    seconds of navigation AFTER they asked to stop.

    IT DELETES THE STORED ROWS RATHER THAN ANONYMISING THEM, and the schema settles that for us.
    ``UsageEvent.userId`` is CASCADE on account deletion specifically so that what the system noticed
    about a person goes when the person goes, and the comment there refuses SetNull by name: it would
    make NULL mean two things in one column — "nobody was signed in" and "this person is gone" — so
    every count of unauthenticated traffic would quietly include ex-colleagues' requests. Blanking
    ``userId`` on withdrawal commits exactly that error and adds a worse one, because a withdrawn
    person's rows would then be indistinguishable from anonymous traffic while still being in there.
    Deleting reuses the answer the schema already gave for the same data.

    NOTE WHAT IT DOES *NOT* DO, because the audio path does not either: it does not pretend the
    observation never happened. There is no log of the withdrawal, for the honest reason that this
    module has nowhere to write one — ``DwWorkshopConsentDecision``'s equivalent for usage is part of
    the flow nobody has designed yet. Whoever designs it should read that model first.

    **THE DURABLE HALF IS MISSING AND THAT IS NOT HIDDEN.** The refusal is remembered in a
    process-local set, so it holds until this process restarts and does not reach a second worker.
    There is no column to persist it in and this module may not add one. A real withdrawal needs the
    answer stored and fed back through :func:`resolve_consent`; until then this function is the part
    that can be built without a migration, and it is worth having on its own — it is what empties the
    queue.

    NEVER RAISES, and the caller need not check the result — the same posture, for the same reason,
    as ``cancel_pending_transcriptions``: the person's answer is the thing that matters, and losing
    it because a cleanup query failed would be the worst possible trade. Idempotent, so a caller that
    wants to close the one narrow window below can simply call it twice.

    THE WINDOW, stated rather than papered over: a batch already handed to Prisma when this is called
    is mid-INSERT and neither the purge nor the delete can see it. Every other path is closed —
    :func:`record_event` refuses a withdrawn account immediately, and :func:`flush` filters the batch
    against the withdrawn set in the moment before it writes. This is the same candour as the audio
    path's note that a PROCESSING job "cannot be un-sent".
    """
    _WITHDRAWN.add(user_id)

    before = len(_BUFFER)
    kept = [row for row in _BUFFER if row.get("userId") != user_id]
    _BUFFER.clear()
    _BUFFER.extend(kept)
    dropped = before - len(kept)

    global _RETRY_BATCH
    if _RETRY_BATCH:
        held = len(_RETRY_BATCH)
        _RETRY_BATCH = [row for row in _RETRY_BATCH if row.get("userId") != user_id]
        dropped += held - len(_RETRY_BATCH)

    deleted = 0
    ran = False
    try:
        deleted = await db.usageevent.delete_many(where={"userId": user_id})
        ran = True
    except Exception as exc:  # noqa: BLE001 - see the docstring: the answer outranks the cleanup
        logger.warning(
            "usage: withdrew %s from recording and dropped %s buffered row(s), but could not "
            "delete the rows already stored (%s); collection has stopped, the deletion has not "
            "happened and re-running withdraw() is safe",
            user_id,
            dropped,
            exc,
        )

    if ran:
        logger.info(
            "usage: withdrew %s from recording; dropped %s buffered row(s) and deleted %s stored "
            "row(s)",
            user_id,
            dropped,
            deleted,
        )
    return Withdrawal(buffered_dropped=dropped, stored_deleted=deleted, stored_delete_ran=ran)


def is_withdrawn(user_id: str | None) -> bool:
    """Whether this process has been told to stop recording this account. See :func:`withdraw`."""
    return bool(user_id) and user_id in _WITHDRAWN


# --------------------------------------------------------------------------------------
# The route template: validation, and the allow-list that makes it a guarantee
# --------------------------------------------------------------------------------------

# A path parameter as Starlette leaves it in ``path_format``. The converter suffix (``{p:path}``, as
# on ``/points/{point_key:path}/records``) is stripped by ``compile_path`` before it reaches us, but
# it is accepted here anyway: a template that arrives from a hand-written list or a future Starlette
# should be recognised rather than filed under ``<unsafe>``.
_PLACEHOLDER = r"\{[A-Za-z_][A-Za-z0-9_]*(?::[A-Za-z_]+)?\}"

# The FIRST token of a segment, when it is a literal, MUST BEGIN WITH A LETTER. That single
# requirement is what refuses the id shapes this API actually mints: a bare integer, a Mongo-style
# ``507f1f77…``, and every UUID that happens to start with a digit. Verified 2026-08-29 across every
# route declared in ``app/api/routes/`` — 271 decorator path literals, 176 of them distinct — and
# every literal segment in this backend begins with a letter.
#
# THE COUNT IS A RECIPE, NOT A CONSTANT, and it is written as one because an earlier draft of this
# comment quoted a number nobody could reproduce and a second file quoted a different one for the
# same claim:
#     grep -rEoh '@router\.(get|post|put|patch|delete)\(\s*"[^"]*"' app/api/routes/*.py | wc -l
# It moves whenever a route is added. What has to hold is the PROPERTY rather than the number, and
# ``test_building_the_app_installs_the_real_route_table_as_an_allow_list`` asserts it against the live
# route table — every mounted template survives ``is_route_template`` — which is the check that keeps
# working on the day somebody adds the two hundred and seventy-second route.
_HEAD_LITERAL = r"[A-Za-z][A-Za-z0-9._-]*"

# A literal run AFTER a placeholder in the same segment may begin with anything in the alphabet,
# because real routes here do exactly that: ``{dataset_name}.csv``, ``subtitles.{fmt}``,
# ``{artisan_id}/consolidated.csv``. Relaxing the first character is safe precisely because a segment
# that reached this rule already contains a ``{`` — and a raw interpolated path never does.
_TAIL_LITERAL = r"[A-Za-z0-9._-]+"

_HEAD_TOKEN_RE = re.compile(f"{_PLACEHOLDER}|{_HEAD_LITERAL}")
_TAIL_TOKEN_RE = re.compile(f"{_PLACEHOLDER}|{_TAIL_LITERAL}")

# The id shapes that DO begin with a letter and would otherwise walk straight through.
# ``cuid`` is what ``@default(cuid())`` mints on every model in this schema; the UUID form is what
# arrives from clients and from S3 keys; the long-hex form catches digests and tokens.
_CUID_RE = re.compile(r"^c[a-z0-9]{20,}$")
_UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
_LONG_HEX_RE = re.compile(r"^[0-9a-fA-F]{16,}$")

#: Longest single literal run in a segment. The longest real one in this backend is
#: ``design-workshop-inspections`` at 27 characters; 48 leaves room and still refuses an opaque blob.
_MAX_LITERAL_RUN = 48

#: The placeholders that are always acceptable whatever the allow-list says, because they are this
#: module's own vocabulary rather than anything a router produced.
_RESERVED_TEMPLATES: frozenset[str] = frozenset({UNMATCHED_ROUTE, UNSAFE_ROUTE})

#: The app's own route table, once somebody installs it. Empty means "fall back to shape".
_KNOWN_TEMPLATES: frozenset[str] = frozenset()

#: Templates already complained about, so one mis-wired route does not write a log line per request.
#: Bounded, because an unbounded set fed by a caller is the same memory leak the buffer refuses.
_WARNED_TEMPLATES: set[str] = set()
_MAX_WARNED_TEMPLATES = 200


def register_known_templates(templates: Iterable[str]) -> int:
    """Install the app's route table as an allow-list. Returns how many templates were accepted.

    **THIS IS THE LAYER THAT MAKES "A RAW PATH CANNOT BE RECORDED" A GUARANTEE RATHER THAN A
    HEURISTIC.** While this set is populated, :func:`ensure_route_template` accepts nothing outside
    it, so no interpolated path can be stored no matter what a caller passes — including the one
    shape the shape-checker provably cannot catch, a record id that happens to read like a word.

    Call it once at startup with every mounted route's ``path_format``. It is deliberately not
    mandatory: with nothing registered the shape rules still apply, so a deployment that never wires
    this up is defended, just less absolutely. Each entry is itself shape-checked on the way in, so a
    caller cannot widen the net by registering rubbish, and anything refused is logged and skipped
    rather than raising — a bad entry must not stop the app from booting.

    Registering a PARTIAL list is the one way to make this worse rather than better: real routes
    outside it would be recorded as :data:`UNSAFE_ROUTE`. That failure is loud — one warning per
    distinct refused template — which is why the warning exists.
    """
    global _KNOWN_TEMPLATES
    accepted: set[str] = set()
    for raw in templates:
        try:
            accepted.add(_shape_checked(raw))
        except UsageRuleViolation as exc:
            logger.warning("usage: refused %r from the route allow-list (%s)", raw, exc)
    _KNOWN_TEMPLATES = frozenset(accepted)
    logger.info("usage: route allow-list installed with %s template(s)", len(accepted))
    return len(accepted)


def known_templates() -> frozenset[str]:
    """The installed allow-list, or an empty set when none was installed."""
    return _KNOWN_TEMPLATES


def _shape_checked(value: str) -> str:
    """The shape rules alone, with no allow-list. Raises :class:`UsageRuleViolation`."""
    if not isinstance(value, str):
        raise UsageRuleViolation("A route template must be a string.")
    template = value.strip()
    if not template:
        raise UsageRuleViolation("A route template cannot be empty.")
    if len(template) > MAX_TEMPLATE_LENGTH:
        raise UsageRuleViolation(
            f"A route template may be at most {MAX_TEMPLATE_LENGTH} characters; this one is "
            f"{len(template)}."
        )
    if not template.startswith("/"):
        raise UsageRuleViolation(f"A route template must start with '/'; {template!r} does not.")
    # Checked by name rather than left to the alphabet, so the refusal can say which door was tried.
    # A query string is the second way a record id reaches this table — the schema refuses a
    # query-string column for the same reason it refuses raw paths, because '?q=' carries whatever
    # somebody typed into a search box.
    for banned, what in (("?", "a query string"), ("#", "a fragment"), ("%", "percent-encoding")):
        if banned in template:
            raise UsageRuleViolation(
                f"A route template may not contain {what}; {template!r} does. Pass the matched "
                f"route's path_format, never the request path."
            )
    if template == "/":
        return template

    for segment in template.split("/")[1:]:
        if not segment:
            raise UsageRuleViolation(
                f"{template!r} has an empty path segment; a template has no '//' and no trailing "
                f"slash."
            )
        _check_segment(segment, template)
    return template


def _check_segment(segment: str, template: str) -> None:
    """One path segment, tokenised left to right. Raises :class:`UsageRuleViolation`.

    A hand-rolled scanner rather than one ``fullmatch`` against ``(?:placeholder|literal)+``, and the
    reason is a real hazard rather than taste: that pattern's repeated group can split a run of
    letters in exponentially many ways, so a segment that FAILS to match backtracks through every
    one of them. A twenty-character segment ending in a bad character would hang the middleware. This
    loop attempts exactly one match per position and advances, which is linear and cannot backtrack
    across tokens.
    """
    pos = 0
    while pos < len(segment):
        pattern = _HEAD_TOKEN_RE if pos == 0 else _TAIL_TOKEN_RE
        match = pattern.match(segment, pos)
        if match is None:
            raise UsageRuleViolation(
                f"{template!r} is not a route template: the segment {segment!r} is or contains "
                f"something that is not a path word or a {{placeholder}}. Pass the matched route's "
                f"path_format, never the request path."
            )
        token = match.group(0)
        if not token.startswith("{"):
            _check_literal(token, segment, template)
        pos = match.end()


def _check_literal(token: str, segment: str, template: str) -> None:
    """One literal run inside a segment: is it a path word, or is it somebody's record id?"""
    if len(token) > _MAX_LITERAL_RUN:
        raise UsageRuleViolation(
            f"{template!r} is not a route template: {token!r} is {len(token)} characters, which is "
            f"an identifier and not a path word."
        )
    if _CUID_RE.match(token) or _UUID_RE.match(token) or _LONG_HEX_RE.match(token):
        raise UsageRuleViolation(
            f"{template!r} is not a route template: the segment {segment!r} contains {token!r}, "
            f"which is a record id. Record ids must never be stored here — a table of raw paths is "
            f"a per-designer reading list of other people's fieldwork."
        )


def ensure_route_template(value: str) -> str:
    """The one gate every stored ``routeTemplate`` passes through. Raises on anything else.

    Order matters: the allow-list is consulted FIRST when one is installed, because it is the
    stronger rule and the shape rules cannot improve on it. With no allow-list, shape decides.
    :data:`UNMATCHED_ROUTE` and :data:`UNSAFE_ROUTE` are accepted under either regime — they are this
    module's own vocabulary and carry no path in them at all.

    Raises :class:`UsageRuleViolation` with a sentence naming what was wrong. On the request path
    :func:`record_event` catches it; anywhere else a refusal is the right answer.
    """
    if isinstance(value, str) and value.strip() in _RESERVED_TEMPLATES:
        return value.strip()
    template = _shape_checked(value)
    if _KNOWN_TEMPLATES and template not in _KNOWN_TEMPLATES:
        raise UsageRuleViolation(
            f"{template!r} is not one of this application's {len(_KNOWN_TEMPLATES)} routes. Only "
            f"templates from the mounted route table may be recorded."
        )
    return template


def is_route_template(value: str) -> bool:
    """:func:`ensure_route_template` as a predicate, for a caller that wants to branch rather than
    catch."""
    try:
        ensure_route_template(value)
    except UsageRuleViolation:
        return False
    return True


def normalise_client_app(raw: str | None) -> str:
    """``"web"``, ``"android"`` or ``"api"``, from whatever a client put in the header.

    THE ONE PLACE THIS IS DECIDED, as the column's own comment requires. The value is
    CLIENT-SUPPLIED, so it is normalised down to the three tokens rather than stored as sent: an
    unbounded string from a handset would ride into a batch of two hundred unrelated rows, and an
    enum would let a typo in one handset's header fail the INSERT of all of them.

    Anything unrecognised — absent, empty, misspelled, a whole user-agent — becomes
    :data:`DEFAULT_CLIENT_APP`. That is honest: an unlabelled client is an unknown client, and today
    that is every client, because neither ``frontend/lib/api.ts`` nor the Android network layer sends
    this header yet.
    """
    if not raw:
        return DEFAULT_CLIENT_APP
    token = raw.strip().lower()
    return token if token in CLIENT_APPS else DEFAULT_CLIENT_APP


def _normalise_method(raw: str | None) -> str:
    """The HTTP method, upper-cased and bounded. Unknown verbs are kept, not refused.

    A String and not an enum, on the column's own reasoning: this is a meter label, so a value nobody
    enumerated costs a wrong breakdown line and nothing more — while an enum would turn it into a
    failed INSERT that discards a whole flush rather than one row. Bounded to sixteen characters
    because the same logic that keeps an unknown verb also has to stop a long one.
    """
    if not raw:
        return "?"
    return raw.strip().upper()[:16] or "?"


# --------------------------------------------------------------------------------------
# The buffer
#
# Module-level and PER PROCESS, deliberately, and it must stay that way. ``main._media_queue_worker``
# takes an advisory lock so exactly one process drains the media job queue, because a job queue must
# have exactly one claimant. THIS IS THE OPPOSITE CASE: an in-process buffer belongs to the process
# that filled it, and a second worker that "lost the election" would sit on rows nobody ever writes.
# Every process flushes its own. Deployment runs --workers 1 today, so this is future-proofing rather
# than a live concern — but it is the kind of future-proofing that costs one paragraph now and a
# silent data gap later.
# --------------------------------------------------------------------------------------

#: ``maxlen`` does the eviction; the length check in :func:`record_event` is what makes it COUNTABLE.
_BUFFER: deque[dict[str, Any]] = deque(maxlen=BUFFER_CEILING)

#: A drained batch that failed to write and is owed another attempt. Up to :data:`FLUSH_ROWS` rows
#: living outside the ceiling — noted rather than hidden: the true peak is BUFFER_CEILING + 200.
_RETRY_BATCH: list[dict[str, Any]] = []
_RETRY_ATTEMPTS = 0

#: Accounts this process has been told to stop recording. See :func:`withdraw`.
_WITHDRAWN: set[str] = set()

#: A plain flag rather than an ``asyncio.Lock``, for :data:`FLUSH_POLL_SECONDS`' loop-affinity
#: reason. asyncio is cooperative and single-threaded, so a check-and-set with no ``await`` between
#: the two halves cannot interleave. What it buys is worth stating plainly: **at most one flush is in
#: flight at any moment, so this module never holds more than ONE of the ten pool connections**,
#: which is the whole concern that ruled out a write per request.
_FLUSH_IN_FLIGHT = False

_DROPPED_TOTAL = 0
_DROPPED_SINCE_REPORT = 0
_WRITTEN_TOTAL = 0
_FAILED_FLUSHES = 0
_ABANDONED_TOTAL = 0


def record_event(
    *,
    route_template: str,
    method: str,
    status_code: int,
    duration_ms: float,
    client_app: str | None = None,
    user_id: str | None = None,
    consent: UsageConsent | None = None,
    at: datetime | None = None,
) -> bool:
    """Buffer one served request. Returns whether a row was buffered.

    **THE CALL THE MIDDLEWARE MAKES, AND IT IS NOT A COROUTINE.** That is the design, not an
    omission: a function that is not ``async`` cannot await a database, cannot await anything, and so
    cannot be turned into a round trip on the request path by a later edit that "just adds an await
    here". The guarantee requirement 22-25 needs — measurement that cannot slow down or break the
    thing it measures — is made true by the signature rather than by a comment asking for it.

    It also never raises. Every failure inside it is caught and logged, because the caller is a
    middleware standing between a designer and their sketch upload: an exception thrown from here
    after the response has been written is a 500 caused by the instrumentation, which is strictly
    worse than no instrumentation.

    ``route_template`` MUST be a template. It is validated, not trusted — see the module docstring
    for the two layers and their different strengths. A value that fails is recorded under
    :data:`UNSAFE_ROUTE` and logged once: the request genuinely happened and dropping it would put a
    hole in the traffic count, but the path itself never reaches the database.

    ``at`` defaults to now, and this stamp is the row's ``createdAt`` — see the module docstring on
    why the database's own default is refused.

    Returns False when consent says nothing may be recorded (:func:`collection_plan`), when the
    account has been withdrawn, or when the buffer was full enough that this row displaced an older
    one — in that last case a row WAS buffered and an older one was dropped, and the count is in
    :func:`buffer_stats`.
    """
    global _DROPPED_TOTAL, _DROPPED_SINCE_REPORT

    try:
        if is_withdrawn(user_id):
            return False

        plan = collection_plan(consent)
        if not plan.record:
            return False

        try:
            template = ensure_route_template(route_template)
        except UsageRuleViolation as exc:
            _warn_once(route_template, exc)
            template = UNSAFE_ROUTE

        row: dict[str, Any] = {
            "routeTemplate": template,
            "method": _normalise_method(method),
            "statusCode": int(status_code),
            # Clamped at both ends. Below: a monotonic clock that steps backwards produces a negative
            # duration, which is not a measurement of anything. Above: see MAX_DURATION_MS — an
            # out-of-range INTEGER fails the INSERT, and on a batched writer that costs the flush
            # rather than the row.
            "durationMs": max(0, min(int(duration_ms), MAX_DURATION_MS)),
            "clientApp": normalise_client_app(client_app),
            "userId": user_id if plan.attribute else None,
            "consentState": plan.consent_state,
            "createdAt": at if at is not None else datetime.now(UTC),
        }

        # deque(maxlen=...) evicts the oldest on its own and says nothing about having done it. This
        # is the check that makes the loss countable — and a dataset that loses rows without saying
        # how many is a dataset that lies.
        if len(_BUFFER) == BUFFER_CEILING:
            _DROPPED_TOTAL += 1
            _DROPPED_SINCE_REPORT += 1
            if _DROPPED_SINCE_REPORT == 1:
                # One line the moment an episode starts, so an operator sees it now rather than up to
                # FLUSH_INTERVAL_SECONDS later; the per-flush summary below carries the totals.
                logger.warning(
                    "usage: the event buffer is full at %s rows and is now dropping the oldest — "
                    "the database is not accepting writes, or is far behind. Rows lost from here "
                    "on are counted and reported at each flush",
                    BUFFER_CEILING,
                )
        # THE OLDEST GOES, NOT THE NEWEST. During an outage the newest rows are the ones describing
        # the outage — the 500s and the slow requests — and they are the reason anybody will look.
        # Either choice biases the surviving sample; this one keeps the most recent window whole, and
        # the drop counter is what stops the bias from being invisible.
        _BUFFER.append(row)
        return True
    except Exception as exc:  # noqa: BLE001 - the response is already on the wire; see the docstring
        logger.warning("usage: could not record a %s on %r (%s)", method, route_template, exc)
        return False


def _warn_once(template: Any, exc: Exception) -> None:
    """One log line per distinct refused template, and at most :data:`_MAX_WARNED_TEMPLATES` of them.

    A refused template means the middleware is passing something that is not a route template — a bug
    that would otherwise write one warning per request at full traffic and bury everything else in
    the log. Bounded for the reason the buffer is bounded: an unbounded set fed from a caller is a
    memory leak wearing a different hat.
    """
    key = str(template)[:MAX_TEMPLATE_LENGTH]
    if key in _WARNED_TEMPLATES:
        return
    if len(_WARNED_TEMPLATES) < _MAX_WARNED_TEMPLATES:
        _WARNED_TEMPLATES.add(key)
    logger.warning(
        "usage: refused %r as a route template and recorded it as %s instead (%s). The caller "
        "should be passing the matched route's path_format",
        key,
        UNSAFE_ROUTE,
        exc,
    )


def buffer_stats() -> dict[str, Any]:
    """A snapshot of what the buffer is doing. For tests, for a health line, and for an operator.

    ``dropped`` is the number that matters and the reason this function exists at all: rows lost
    without a count make every figure computed from this table unfalsifiable.
    """
    return {
        "buffered": len(_BUFFER),
        "ceiling": BUFFER_CEILING,
        "retryPending": len(_RETRY_BATCH),
        "written": _WRITTEN_TOTAL,
        "dropped": _DROPPED_TOTAL,
        "abandoned": _ABANDONED_TOTAL,
        "failedFlushes": _FAILED_FLUSHES,
        "withdrawnAccounts": len(_WITHDRAWN),
    }


def reset_buffer() -> None:
    """Empty the buffer and the counters. **For tests.** Not part of the request path."""
    global _RETRY_BATCH, _RETRY_ATTEMPTS, _DROPPED_TOTAL, _DROPPED_SINCE_REPORT
    global _WRITTEN_TOTAL, _FAILED_FLUSHES, _ABANDONED_TOTAL, _FLUSH_IN_FLIGHT
    _BUFFER.clear()
    _RETRY_BATCH = []
    _RETRY_ATTEMPTS = 0
    _WITHDRAWN.clear()
    _WARNED_TEMPLATES.clear()
    _DROPPED_TOTAL = 0
    _DROPPED_SINCE_REPORT = 0
    _WRITTEN_TOTAL = 0
    _FAILED_FLUSHES = 0
    _ABANDONED_TOTAL = 0
    _FLUSH_IN_FLIGHT = False


# --------------------------------------------------------------------------------------
# The writer
# --------------------------------------------------------------------------------------


async def flush() -> int:
    """Write at most :data:`FLUSH_ROWS` buffered rows in ONE ``create_many``. Returns rows written.

    **NEVER RAISES.** This is ``ai_verb_cap.spend``'s posture and it is the same argument one layer
    over: there, the words are already produced and the layer already written, so handing a designer
    an error would cost them the result AND the retry. Here the response has already left the
    building. A flush failure is a warning in the log and nothing else, ever — an exception escaping
    this function reaches an ``asyncio`` task, and a measurement that can break a request is worse
    than no measurement.

    ONE STATEMENT, ONE CONNECTION. ``create_many`` sends the whole batch in a single round trip, and
    :data:`_FLUSH_IN_FLIGHT` means only one such call is outstanding at a time, so this module's
    steady-state demand on a pool of ten is exactly one — against ``gather_reads``, which is allowed
    all ten. That is the arithmetic that made a batched writer necessary in the first place.

    THE WITHDRAWN FILTER RUNS HERE, in the moment before the write, and not only at
    :func:`record_event`. A row buffered before somebody withdrew is a row observed before they said
    stop and written after, which is exactly the thing ``cancel_pending_transcriptions`` exists to
    prevent one queue over.

    A concurrent call returns 0 rather than queueing, which is what makes the flag safe. The only
    caller that can collide with the worker is a shutdown drain, and ``lifespan`` cancels the worker
    before draining.
    """
    global _FLUSH_IN_FLIGHT, _RETRY_BATCH, _RETRY_ATTEMPTS
    global _WRITTEN_TOTAL, _FAILED_FLUSHES, _ABANDONED_TOTAL, _DROPPED_SINCE_REPORT

    if _FLUSH_IN_FLIGHT:
        return 0

    if _DROPPED_SINCE_REPORT:
        # Reported here rather than at the drop, so a sustained outage produces one line every five
        # seconds instead of one per request. The running total is carried so a reader who missed the
        # first line still learns the size of the hole.
        logger.warning(
            "usage: dropped %s event(s) since the last flush because the buffer was at its %s-row "
            "ceiling (%s dropped in this process so far)",
            _DROPPED_SINCE_REPORT,
            BUFFER_CEILING,
            _DROPPED_TOTAL,
        )
        _DROPPED_SINCE_REPORT = 0

    if _RETRY_BATCH:
        batch = _RETRY_BATCH
        _RETRY_BATCH = []
    else:
        _RETRY_ATTEMPTS = 0
        batch = [_BUFFER.popleft() for _ in range(min(FLUSH_ROWS, len(_BUFFER)))]

    if not batch:
        return 0

    if _WITHDRAWN:
        batch = [row for row in batch if row.get("userId") not in _WITHDRAWN]
        if not batch:
            return 0

    _FLUSH_IN_FLIGHT = True
    try:
        written = await db.usageevent.create_many(data=batch)
        _WRITTEN_TOTAL += written
        _RETRY_ATTEMPTS = 0
        return written
    except Exception as exc:  # noqa: BLE001 - see the docstring: the response is long gone
        _FAILED_FLUSHES += 1
        _RETRY_ATTEMPTS += 1
        if _RETRY_ATTEMPTS >= FLUSH_MAX_ATTEMPTS:
            _ABANDONED_TOTAL += len(batch)
            _RETRY_ATTEMPTS = 0
            logger.warning(
                "usage: could not write %s event(s) after %s attempt(s) (%s); abandoning them so "
                "the buffer keeps draining. %s event(s) abandoned in this process so far",
                len(batch),
                FLUSH_MAX_ATTEMPTS,
                exc,
                _ABANDONED_TOTAL,
            )
        else:
            _RETRY_BATCH = batch
            logger.warning(
                "usage: could not write %s event(s) (%s); holding them for one more attempt",
                len(batch),
                exc,
            )
        return 0
    finally:
        _FLUSH_IN_FLIGHT = False


async def flush_all() -> int:
    """Drain everything, in :data:`FLUSH_ROWS`-sized statements. Returns rows written.

    **THE SHUTDOWN DRAIN.** Call it from ``lifespan``'s ``finally``, after the worker task is
    cancelled and BEFORE ``disconnect_db()`` — the second half matters, because a drain against a
    disconnected client writes nothing and the rows are gone with the process.

    Bounded by the buffer's own ceiling rather than by a loop condition that a still-live recorder
    could keep true for ever: it drains at most the number of rows that were present when it started,
    plus one statement's worth of slack, so a shutdown cannot be held open by traffic still arriving.

    THE STALL TEST IS "WROTE NOTHING **AND** SHRANK NOTHING", AND BOTH HALVES ARE LOAD-BEARING. A
    batch belonging entirely to a withdrawn account writes zero rows on purpose — testing only the
    write count would read that as a dead database and abandon every legitimate row queued behind it.
    A failed write, by contrast, leaves the totals exactly where they were, and that is where the
    drain stops: a shutdown is the worst possible moment to sit retrying a database that is not
    answering, and :func:`flush` has already logged what it is holding.
    """
    remaining = len(_BUFFER) + len(_RETRY_BATCH)
    statements = remaining // FLUSH_ROWS + 2
    written = 0
    for _ in range(statements):
        if not _BUFFER and not _RETRY_BATCH:
            break
        before = len(_BUFFER) + len(_RETRY_BATCH)
        wrote = await flush()
        written += wrote
        if wrote == 0 and len(_BUFFER) + len(_RETRY_BATCH) >= before:
            break
    if written:
        logger.info("usage: flushed %s event(s) on shutdown", written)
    return written


async def run_flush_worker() -> None:
    """The background loop. ``asyncio.create_task(usage.run_flush_worker())`` in ``lifespan``.

    Size threshold OR time interval, whichever comes first: it wakes every
    :data:`FLUSH_POLL_SECONDS` and writes when the buffer has reached :data:`FLUSH_ROWS` or when
    :data:`FLUSH_INTERVAL_SECONDS` has passed since the last write. Shaped after
    ``main._media_queue_worker`` — an unconditional ``try/except`` around the work and a sleep at the
    bottom — with the one difference the module docstring gives: **no advisory lock.** That lock
    exists so exactly one process drains a shared job queue; this buffer is not shared, and a worker
    that lost such an election would sit on rows nobody would ever write.

    :func:`flush` never raises, so the ``except`` here is belt and braces rather than the design —
    but a background task that dies takes the flusher with it silently and leaves the buffer to fill
    up to its ceiling, so the belt stays on.
    """
    logger.info(
        "usage: flusher started — up to %s rows per statement, at least every %.1fs, ceiling %s",
        FLUSH_ROWS,
        FLUSH_INTERVAL_SECONDS,
        BUFFER_CEILING,
    )
    elapsed = 0.0
    while True:
        await asyncio.sleep(FLUSH_POLL_SECONDS)
        elapsed += FLUSH_POLL_SECONDS
        due = elapsed >= FLUSH_INTERVAL_SECONDS
        if not due and len(_BUFFER) < FLUSH_ROWS and not _RETRY_BATCH:
            continue
        elapsed = 0.0
        try:
            await flush()
        except Exception:
            logger.exception("Usage event flusher failed")


# --------------------------------------------------------------------------------------
# Reading it back
#
# TWO HELPERS, AND THE SHAPE OF EACH IS ITS INDEX. ``@@index([userId, createdAt])`` and
# ``@@index([routeTemplate, createdAt])`` are both "one thing held constant, over a range of dates",
# which is the only order a btree serves both halves of. So both functions REQUIRE the equality
# column and neither offers a spelling for "all of them": there is no ``usage_for_routes()`` with no
# templates and no ``usage_for_user(None)``, because those are whole-window scans across every user
# and every route, and the schema deliberately does not build the index that would serve one —
# "a report nobody has asked for, paid for on every insert into what will be by far the
# highest-write table in this schema".
#
# NEITHER FUNCTION AUTHORISES ANYTHING. The route module owns the gate, on the house rule that this
# repository has twice shipped a UI guard over an open endpoint. What these functions do own is what
# they will not emit: :func:`usage_for_routes` folds identities into a COUNT inside this module and
# never returns a user id, so no caller of it can leak one by accident.
# --------------------------------------------------------------------------------------


def _ensure_window(since: datetime, until: datetime) -> tuple[datetime, datetime]:
    """A half-open ``[since, until)`` window, refused unless it is aware, ordered and bounded.

    AWARE ONLY. This backend stores aware stamps everywhere (``DTZ`` is on in ruff for exactly that
    reason) and a naive datetime compared against a ``timestamptz`` is silently interpreted in the
    server's timezone — which produces a window that is quietly wrong by hours rather than an error
    anybody notices.
    """
    for label, value in (("since", since), ("until", until)):
        if not isinstance(value, datetime):
            raise UsageRuleViolation(f"{label} must be a datetime.")
        if value.tzinfo is None or value.utcoffset() is None:
            raise UsageRuleViolation(
                f"{label} must carry a timezone; a naive datetime is read against the server's own "
                f"clock and silently shifts the window."
            )
    if since >= until:
        raise UsageRuleViolation("since must be earlier than until.")
    if until - since > timedelta(days=MAX_RANGE_DAYS):
        raise UsageRuleViolation(
            f"A usage window may be at most {MAX_RANGE_DAYS} days; this one is "
            f"{(until - since).days}. A longer report should be built from a rollup."
        )
    return since, until


def _window_where(since: datetime, until: datetime) -> dict[str, Any]:
    """The range half of both indexes: ``gte``/``lt``, half-open so adjacent windows neither overlap
    nor drop the boundary row."""
    return {"createdAt": {"gte": since, "lt": until}}


def _fold_status(groups: Sequence[Any], key: str) -> dict[str, dict[str, Any]]:
    """Fold a ``group_by([key, "statusCode"])`` result into one entry per ``key``.

    The per-route average is the count-weighted mean of the per-group means, which is EXACT rather
    than an approximation: ``avg_i * n_i`` is the group's true total, so summing those and dividing
    by the total count is the true mean. Rounded to a whole millisecond, because ``durationMs`` is an
    Int and the column's own comment refuses a Float precisely to stop an average being printed to
    three decimal places that reads as far more exact than the thing it measured.
    """
    folded: dict[str, dict[str, Any]] = {}
    for group in groups:
        name = str(group[key])
        entry = folded.setdefault(
            name,
            {
                "requests": 0,
                "ok": 0,
                "clientErrors": 0,
                "serverErrors": 0,
                "_durationTotal": 0.0,
                "maxDurationMs": 0,
            },
        )
        count = int(group["_count"]["_all"])
        status = int(group["statusCode"])
        entry["requests"] += count
        if status >= 500:
            entry["serverErrors"] += count
        elif status >= 400:
            entry["clientErrors"] += count
        else:
            entry["ok"] += count
        avg = (group.get("_avg") or {}).get("durationMs") or 0
        entry["_durationTotal"] += float(avg) * count
        top = (group.get("_max") or {}).get("durationMs") or 0
        entry["maxDurationMs"] = max(entry["maxDurationMs"], int(top))
    for entry in folded.values():
        total = entry.pop("_durationTotal")
        entry["avgDurationMs"] = round(total / entry["requests"]) if entry["requests"] else None
    return folded


async def usage_for_user(user_id: str, since: datetime, until: datetime) -> dict[str, Any]:
    """One account's own trail over a window, broken down by screen. Rides
    ``@@index([userId, createdAt])``.

    **THE CALLER MUST ALREADY HAVE ESTABLISHED THAT IT MAY READ THIS ACCOUNT.** This function
    authorises nothing; it is the query, not the gate. The intended use is a person reading their own
    trail, which is why there is no withholding floor here — a floor exists to stop one person being
    picked out of an aggregate, and there is no aggregate to hide in when the subject is the reader.
    Pointing this at somebody else's id is a decision a route makes with its own dependency and its
    own written argument.

    ONE ROUND TRIP. Grouping by ``(routeTemplate, statusCode)`` in a single statement yields the
    per-screen counts, the status bands and the timings together; splitting them would double a
    756 ms latency to compute what one statement already returns.
    """
    since, until = _ensure_window(since, until)
    if not user_id:
        raise UsageRuleViolation(
            "usage_for_user needs an account id; there is no 'everybody' form."
        )

    groups = await db.usageevent.group_by(
        by=["routeTemplate", "statusCode"],
        count=True,
        avg={"durationMs": True},
        max={"durationMs": True},
        where={"userId": user_id, **_window_where(since, until)},
    )
    folded = _fold_status(groups, "routeTemplate")
    routes = [
        {"routeTemplate": name, **entry}
        for name, entry in sorted(folded.items(), key=lambda kv: (-kv[1]["requests"], kv[0]))
    ]
    return {
        "userId": user_id,
        "from": since,
        "to": until,
        "requests": sum(entry["requests"] for entry in folded.values()),
        "routes": routes,
    }


async def usage_for_routes(
    templates: Sequence[str], since: datetime, until: datetime
) -> dict[str, Any]:
    """Named routes over a window: how often, how fast, how often broken. Rides
    ``@@index([routeTemplate, createdAt])``.

    ``templates`` IS REQUIRED AND HAS NO "ALL" SPELLING. Every template is put through
    :func:`ensure_route_template` first — one rule, one place, and it means a caller cannot use this
    function as an oracle by probing it with interpolated paths.

    **NO USER ID LEAVES THIS FUNCTION.** The distinct-account count is computed here, from a grouping
    that does see identities, and only the integer comes back. A route module physically cannot emit
    a user id from this result, which is the difference between a guarantee and a convention.

    THE WITHHOLDING FLOOR IS A REFUSAL AND NOT A ZERO. A route with between one and
    :data:`MIN_IDENTIFIED_USERS_FOR_ROUTE` identified accounts in the window comes back with every
    metric ``None`` and ``withheld`` true — because on a route two people use, an "aggregate" is a
    description of those two, and the window is chosen by whoever is asking. ``None`` is what the web
    client already renders as a bare em dash for ``workshop_analytics``' rates; note there that
    ``null`` coerces to 0 through arithmetic and through ``??``, so a consumer must branch on
    ``withheld`` rather than fall back.

    Routes with NO identified accounts are reported in full — see
    :data:`MIN_IDENTIFIED_USERS_FOR_ROUTE` for why that is the rule and not a hole in it.

    TWO ROUND TRIPS, GATHERED, whatever the number of templates: one grouping for the counts and
    timings, one for the identities. Both are ``routeTemplate IN (...)`` plus a date range — a
    bounded set of index probes, never a scan — and ``gather_reads`` runs them together under the
    pool bound rather than one after the other at 756 ms each.
    """
    since, until = _ensure_window(since, until)
    wanted = [ensure_route_template(value) for value in templates]
    if not wanted:
        raise UsageRuleViolation(
            "usage_for_routes needs at least one route template; there is no 'every route' form, "
            "because that is a scan of the whole window and no index serves it."
        )
    if len(wanted) > MAX_TEMPLATES_PER_QUERY:
        raise UsageRuleViolation(
            f"usage_for_routes takes at most {MAX_TEMPLATES_PER_QUERY} templates at a time; this "
            f"call named {len(wanted)}."
        )

    where = {"routeTemplate": {"in": wanted}, **_window_where(since, until)}
    status_groups, identity_groups = await gather_reads(
        db.usageevent.group_by(
            by=["routeTemplate", "statusCode"],
            count=True,
            avg={"durationMs": True},
            max={"durationMs": True},
            where=where,
        ),
        db.usageevent.group_by(by=["routeTemplate", "userId"], count=True, where=where),
    )

    folded = _fold_status(status_groups, "routeTemplate")
    identified: dict[str, int] = dict.fromkeys(wanted, 0)
    for group in identity_groups:
        # NULL userIds are skipped: they identify nobody, so they protect nobody and must not be
        # counted towards a floor that exists to protect identified people.
        if group.get("userId"):
            identified[str(group["routeTemplate"])] = (
                identified.get(str(group["routeTemplate"]), 0) + 1
            )

    routes: list[dict[str, Any]] = []
    for name in wanted:
        entry = folded.get(name)
        people = identified.get(name, 0)
        if entry is None:
            routes.append(
                {
                    "routeTemplate": name,
                    "requests": 0,
                    "identifiedUsers": 0,
                    "withheld": False,
                    "ok": 0,
                    "clientErrors": 0,
                    "serverErrors": 0,
                    "avgDurationMs": None,
                    "maxDurationMs": None,
                }
            )
            continue
        if 0 < people < MIN_IDENTIFIED_USERS_FOR_ROUTE:
            routes.append(
                {
                    "routeTemplate": name,
                    "requests": None,
                    "identifiedUsers": None,
                    "withheld": True,
                    "withheldBecause": (
                        f"Fewer than {MIN_IDENTIFIED_USERS_FOR_ROUTE} people used this screen in "
                        f"this period, so a figure here would describe them individually rather "
                        f"than describe a group."
                    ),
                    "ok": None,
                    "clientErrors": None,
                    "serverErrors": None,
                    "avgDurationMs": None,
                    "maxDurationMs": None,
                }
            )
            continue
        routes.append(
            {"routeTemplate": name, "identifiedUsers": people, "withheld": False, **entry}
        )

    return {
        "from": since,
        "to": until,
        "minimumIdentifiedUsers": MIN_IDENTIFIED_USERS_FOR_ROUTE,
        "routes": routes,
    }
