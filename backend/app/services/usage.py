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

``DATABASE_CONNECTION_LIMIT`` defaults to 10, and it is 10 because 40 exhausted a pooler's shared
client-connection budget and crash-looped this deployment — the incident is recorded on that setting
in ``core/config.py`` and the dashboard route carries a written warning not to raise it to fit
something new in. THE DEPLOYMENT SETS IT LOWER STILL, to 5, against a session pool of about fifteen
slots shared with the queue process. ``concurrency.gather_reads`` is bounded by whatever it is.

The database WAS also in a different AWS region from the web box: ``concurrency.py`` measured ONE
Prisma round trip at 756 ms against tables whose server-side execution is 0.04–0.24 ms. Production
moved on 2026-09-02 to a co-located database where a round trip is one or two milliseconds, so that
figure and every "most of a second" derived from it below are HISTORY — the measurement that built
this module rather than a claim about today, and nothing here has been re-timed since.

**THE DESIGN IS UNCHANGED BY THAT, AND THE CONNECTION IS WHY.** Latency was only ever half of the
argument; the other half is that an insert on the request path takes ONE OF FIVE connections, and
that number did not move — it got smaller. The two facts settle the design between them:

* an INSERT inside the request path would take one of those five connections on every request, to
  record that the request was slow — and it would do so under exactly the load that makes the pool
  scarce, which is the load worth measuring;
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
CONSENT: ASKED AT THE DOOR SINCE 2026-08-30, AND THE DEFAULT FOR THE UNASKED IS STILL STATED
================================================================================================

Watching a designer navigate is a NEW CATEGORY OF PERSONAL DATA in this repository. Everything else
it holds about a person is something that person typed in; this is something the system noticed
about them without being asked. The codebase already models consent explicitly wherever it collects
a recording, and this module now follows that model in fact as well as in shape:

* three states and never a boolean (:class:`UsageConsent`), so "nobody has asked" can never be read
  as "they said no";
* NULL in ``UsageEvent.consentState`` means NOBODY WAS ASKED, and it is the only way the rows
  gathered before a flow exists can be found and deleted on the day somebody decides they should be;
* the token ``"GRANTED"`` is written ONLY when a grant was actually recorded — never as a default,
  never as an optimistic guess. A row that claims a consent nobody gave is worse than no row.

**THE FLOW EXISTS NOW, AND WHAT IT ADDED IS LISTED HERE SO THE SHAPE IS READABLE IN ONE PLACE.**
:class:`UsageConsentBasis` records WHETHER AN ANSWER WAS FREELY GIVEN — the gate at sign-in is a
condition of access and a grant collected there is not free consent, so the circumstance is stored
beside the answer rather than left to be assumed; :data:`NOTICE_VERSION` records WHICH TEXT was on
screen, so a later reword cannot claim agreement to wording nobody saw; ``UsageConsentDecision`` is
the append-only log, because a withdrawal must not erase the answer earlier collection was made
under; and :func:`record_consent` is the one door, which on a REFUSAL also empties the buffer and
DELETES the stored rows. ``docs/DECISION-usage-consent-at-sign-in.md`` carries the whole argument.

**THE DEFAULT IS :data:`DEFAULT_UNASKED_COLLECTION` = ANONYMOUS, AND IT IS A DECISION SOMEBODY ELSE
IS ENTITLED TO OVERRULE IN ONE LINE.** It governs an account that has NOT YET ANSWERED, which since
the flow shipped is two populations and neither of them is "the whole fleet": the handful of
requests between a session being minted and the consent screen being answered, and — far larger —
every UNAUTHENTICATED request, which has no account to have asked. This module then records the
request WITHOUT the identity: route, status, duration and client, ``userId`` NULL, ``consentState``
NULL. That is deliberately the middle option of three, all three of which are spelled out as real
selectable values in :class:`UnaskedCollection` so that the alternatives are visible rather than
hypothetical:

* ``NOTHING`` collects nothing at all until somebody has been asked. Safest, and it means requirement
  22-25 measures nothing until a screen ships on two clients.
* ``ANONYMOUS`` — the default — answers "which screens are reached and where is it slow" while
  attributing nothing to a named colleague who was never asked.
* ``ATTRIBUTED`` also answers "what did this designer do last week", and it does so by recording a
  named person's movements without their knowledge. It is offered so the choice is made on purpose
  rather than arrived at by accident; if it is chosen, ``consentState`` is STILL NULL, because
  nobody was asked and the column must not be made to say otherwise.

**WHY THE ARRIVAL OF THE FLOW DID NOT CHANGE THAT CONSTANT, WHICH IS THE OBVIOUS THING TO EXPECT.**
The old argument for ANONYMOUS was "otherwise this system measures nothing at all, for as long as it
takes to ship a screen on two clients". That argument is spent. The one that replaced it, on
2026-08-30, is narrower and stronger: **the largest unasked population is people who are not signed
in**, and ``NOTHING`` would stop recording them — deleting, silently, the one capability the schema
names by name as worth having ("the sign-in page is slow for the people who cannot get in", which
:data:`MIN_IDENTIFIED_USERS_FOR_ROUTE` also has a paragraph protecting). A request with no account
attached identifies nobody, so there is nobody for a consent question to protect on it. The value is
unchanged and its justification is not; that is recorded in
``docs/DECISION-usage-consent-default.md`` with the date, rather than left as a constant whose reason
has quietly expired.

Three of the four questions the schema flagged are now answered — what is asked
(:func:`consent_notice`), when (at sign-in, and again whenever :data:`NOTICE_VERSION` moves), and
whether a refusal stops collection or only stops attribution (it stops collection entirely, and
deletes what was stored). **THE FOURTH IS STILL OPEN AND MUST NOT BE ANSWERED BY A LATER READER
GUESSING: what happens to the rows gathered before anybody was asked.** They carry ``consentState``
NULL, that NULL is what makes them findable as a set, and nothing here backfills it.

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
from collections.abc import Iterable, Mapping, Sequence
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

#: Where the dependency layer leaves the caller's resolved :class:`UsageConsent`. Written by
#: ``deps.get_current_user`` on every AUTHENTICATED request since 2026-08-30, and genuinely absent on
#: every unauthenticated one — there is no account to resolve a consent from. The middleware reads
#: anything that is not a :class:`UsageConsent` as absent rather than coercing it, so a wrong type
#: fails towards "nobody was asked" and never towards a claimed consent.
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
#: One Prisma round trip cost ~756 ms of latency against 0.04–0.24 ms of server-side work when this
#: was chosen (``concurrency.py``, and the co-located ~1-2ms it moved to on 2026-09-02), so the
#: statement's cost barely moves with its row count. THAT RATIO IS WHAT 200 RESTS ON AND IT SURVIVED
#: THE MOVE: a statement still costs a connection and a round trip whether it carries one row or two
#: hundred. At 200 the
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
#: For an account NOBODY HAS ASKED, this module records WHAT WAS REACHED and not WHO REACHED IT.
#:
#: **READ THE POPULATION BEFORE THE ARGUMENT, BECAUSE IT CHANGED ON 2026-08-30 AND THE SENTENCE HERE
#: DID NOT KEEP UP FOR A WHILE.** This paragraph used to open "until a consent flow exists" and to
#: say that "today nothing in this system can answer either half" — both of which were true when
#: there was no column, no route and no screen, and neither of which is true now. The flow shipped;
#: an account that has answered GRANTED is attributed and an account that has REFUSED is not
#: recorded at all. What this constant governs is the REMAINDER, and the module docstring's section
#: "WHY THE ARRIVAL OF THE FLOW DID NOT CHANGE THAT CONSTANT" is the argument that kept it where it
#: is. The reasoning, stated so it can be overruled rather than merely obeyed:
#:
#: * ``NOTHING`` is the safest and it is not free — and what it would now cost is not "measuring
#:   nothing until a screen ships", it is the UNAUTHENTICATED half of the traffic, which is by far
#:   the largest unasked population and has no account that could ever have been asked. Route,
#:   status and duration with no identity attached answer "where is it slow" in full and "how do
#:   they move" in aggregate, and they do it while nobody's name is in the table — including for
#:   "the sign-in page is slow for the people who cannot get in", which nothing else in this system
#:   can show and which ``NOTHING`` would delete.
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

#: The name this module reads off a ``User`` row. **THE COLUMN NOW EXISTS** (migration
#: ``20260830090000_usage_consent_and_decision_log``) and this constant is what the migration was
#: named after, not the other way round: it was written down long before the column so that the day
#: it landed there was exactly one line to change and one grep to find it.
#:
#: DO NOT RENAME EITHER HALF WITHOUT THE OTHER. :func:`resolve_consent` reads it with ``getattr``,
#: and a ``getattr`` miss does not raise — it returns None, which resolves to ``NOT_RECORDED``, which
#: silently reverts collection for the entire fleet to anonymous with no error, no log line and no
#: test going red. ``tests/test_usage_tracking.py`` pins the name against the generated Prisma model
#: for exactly that reason.
CONSENT_ATTRIBUTE = "usageConsent"

#: When the person answered. The other three columns of the answer, named here for the same reason as
#: the one above: they are read by ``getattr`` and a miss is silent.
CONSENT_AT_ATTRIBUTE = "usageConsentAt"
#: Whether the answer was a turnstile or a free choice — see :class:`UsageConsentBasis`.
CONSENT_BASIS_ATTRIBUTE = "usageConsentBasis"
#: Which version of the notice was on screen — see :data:`NOTICE_VERSION`.
CONSENT_VERSION_ATTRIBUTE = "usageConsentVersion"


class UsageConsentBasis(str, Enum):
    """UNDER WHAT CIRCUMSTANCES an answer was given. **The distinction that keeps the record honest.**

    The gate at sign-in is a CONDITION OF ACCESS: a person who will not agree cannot use the product.
    Under GDPR Art. 7(4) and the DPDP-style regimes this deployment sits under, that is not freely
    given consent — consent is not free where performance of the service is made conditional on it
    and the processing is not necessary for the service.

    **The requirement is not refused; it is recorded truthfully.** Storing ``GRANTED`` and nothing
    else would file nine thousand turnstiles as nine thousand free choices, and no later reader — a
    colleague, an ethics board, a methods section — could tell them apart. With this value stored
    beside the answer, and on every log row, they can.

    A FOURTH :class:`UsageConsent` MEMBER WAS THE ALTERNATIVE AND IS REFUSED. It would break
    :func:`collection_plan`'s three-way rule — which is the whole of the collection policy, in one
    function — and the documented meaning of ``UsageEvent.consentState``, in one edit. The
    circumstance is a second fact about one answer, so it is a second column.
    """

    #: The blocking agreement at the door. The account could not proceed without it.
    REQUIRED_AT_SIGN_IN = "REQUIRED_AT_SIGN_IN"

    #: Answered or changed on the account's own settings screen, where saying no costs nothing. Every
    #: WITHDRAWAL is one of these, and that asymmetry is what makes the turnstile defensible: the
    #: gate makes you agree to get in, and the settings card lets you take it back and keep working.
    #: A withdrawal that cost access would be theatre.
    OFFERED_IN_SETTINGS = "OFFERED_IN_SETTINGS"


#: **THE VERSION OF THE TEXT PEOPLE ARE AGREEING TO. BUMP IT WHENEVER THE NOTICE CHANGES MEANING.**
#:
#: A consent record that cannot say WHAT WAS AGREED TO invites exactly the claim it cannot support:
#: that everybody on file agreed to whatever the notice says today. So the version is stored on the
#: ``User`` row and on every decision row, and :func:`consent_gate` asks again when it has moved.
#:
#: DATED RATHER THAN NUMBERED, and the suffix is what makes two edits in one day distinguishable.
#: A bare integer would need a lookup table to date; a bare date cannot separate a morning's wording
#: from an afternoon's.
#:
#: WHAT COUNTS AS A CHANGE OF MEANING: a new column collected, a new reader admitted, a change to
#: what a withdrawal does, a change to retention. Fixing a typo does not. The test that pins this
#: constant to the notice's own content is what stops the notice moving while the version does not —
#: which would be the failure this constant exists to prevent, arriving by inattention rather than by
#: intent.
NOTICE_VERSION = "2026-08-30.1"

#: How far ahead of the server's clock a client-reported ``recordedAt`` may be before it is refused.
#:
#: Fifteen minutes, exactly as ``dictation_consent.MAX_DEVICE_CLOCK_SKEW`` is, and copied
#: deliberately so that a reader who knows one knows the other. A handset's clock drifts and a few
#: minutes out is ordinary; a consent dated to next March is a phone whose clock was set by hand, and
#: storing it would put an answer in the log that appears to have been given before the account
#: existed. The refusal names the next move rather than silently substituting ``now()``, because a
#: substituted timestamp is a fabricated fact about when somebody consented.
MAX_DEVICE_CLOCK_SKEW = timedelta(minutes=15)


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
    """This account's answer about being observed, read off the row that is already in hand.

    SYNCHRONOUS AND NO DATABASE WORK, WHICH IS THE WHOLE POINT OF ITS SHAPE. It reads
    :data:`CONSENT_ATTRIBUTE` off the ``User`` row the dependency layer has ALREADY loaded to
    authenticate the request, so the consent lookup on the hot path costs no extra round trip —
    the constraint :func:`record_event` is built around, and the reason this function was written
    with this signature a migration before the column existed.

    **A STALE NOTICE VERSION IS STILL ``GRANTED`` HERE, AND THAT IS DELIBERATE.** The version is
    compared in :func:`consent_gate`, which is what asks a person again; it is not compared here.
    Flipping a stale grant to ``NOT_RECORDED`` would mean a wording change silently reclassified the
    whole fleet mid-window — every aggregate's population would move on a deploy, and every
    designer's own ``/usage/me`` would go blank overnight — to enforce something the version column
    already answers by being stored. What the version exists to prevent is a record CLAIMING somebody
    agreed to text they never saw; that is prevented by keeping the version, not by deleting the
    agreement.

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
    the case that actually happens on **every unauthenticated request**, where there is no account
    for the stitch to resolve a consent from and so :data:`USAGE_CONSENT_KEY` is genuinely absent.
    "The stitch found nothing" and "nobody has asked this person" are the same fact, and reading the
    absence as anything else would attribute a request nobody signed in for.

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
    observation never happened. The dated decisions stay in ``UsageConsentDecision``, which is the
    only surviving evidence that the deleted rows were ever collected under an agreement — and which
    matters more here than one consent question over, precisely because here the rows themselves are
    gone.

    **THE DURABLE HALF EXISTS SINCE 2026-08-30 AND THIS IS NO LONGER THE WHOLE WITHDRAWAL.**
    :func:`record_consent` is the door: it writes ``REFUSED`` to ``User.usageConsent`` and a row to
    the decision log, and THEN calls this function. So the refusal now survives a restart and reaches
    a second worker, because :func:`resolve_consent` reads the column off the row every request
    already loads.

    WHAT THIS FUNCTION IS NOW: the fast, in-process half. ``_WITHDRAWN`` stops rows that are already
    in THIS process's buffer, which no column can reach, and the delete clears the archive. Calling
    it alone still stops collection in this worker and still deletes — that is why it is safe as a
    fallback — but it does not record an answer, and an account withdrawn only this way is GRANTED
    again the moment the process restarts. **Call :func:`record_consent`, not this.** The one place
    this is still the right call on its own is a test.

    THE COUNTERPART IS :func:`resume`, and forgetting it is the live trap: an account that agrees
    again after a withdrawal stays in ``_WITHDRAWN`` for the life of the process unless something
    takes it out, with every other part of the system reading GRANTED.

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


def resume(user_id: str) -> bool:
    """Undo the process-local half of a withdrawal, because an account may agree again.

    **WITHOUT THIS, A RE-CONSENT IS RECORDED, BELIEVED BY EVERY OTHER PART OF THE SYSTEM, AND
    QUIETLY INEFFECTIVE.** :data:`_WITHDRAWN` is checked first in :func:`record_event` and again in
    :func:`flush`, ahead of the consent rule and by design — it is the fast path that stops rows
    already in this process's buffer without a database read. It is also, therefore, a refusal that
    outlives the answer that produced it: a person who withdraws on Monday and agrees again on
    Tuesday would stay in the set until this worker restarted, with ``usageConsent`` reading GRANTED,
    ``/usage/me`` reporting nothing, and no log line anywhere saying why.

    Returns whether the account was actually in the set, so a caller can tell a re-consent from a
    first one. Called by :func:`record_consent` on every GRANT — never conditionally, because the
    condition would be "was it in the set", which is the thing this function answers.
    """
    was_withdrawn = user_id in _WITHDRAWN
    _WITHDRAWN.discard(user_id)
    if was_withdrawn:
        logger.info(
            "usage: %s has agreed again; this process will record its requests from now on. The "
            "rows deleted by the earlier withdrawal are gone and are not restored",
            user_id,
        )
    return was_withdrawn


# --------------------------------------------------------------------------------------
# THE NOTICE: what a person is agreeing to, computed from the policy actually in force
#
# **ONE SOURCE, AND IT IS THIS SERVER.** The sign-in screen on the web, the sign-in screen on
# Android and the settings card on both have to say the same thing, and the thing they say has to be
# what this deployment actually does. Writing the copy a second time in TSX and a third time in
# Kotlin is how two clients come to describe one decision differently — which is the failure
# ``feedback.FEEDBACK_FIELDS`` exists to prevent one contract over, and which matters more here than
# it does there: a consent notice that does not match the collection is not a smaller kind of
# correct, it is a consent to something else.
#
# COMPUTED, NOT ASSERTED. :func:`collects` derives its account-id line from
# :func:`collection_plan` — the same function the recorder itself calls, on the same constant — so a
# one-line policy flip changes what is PUBLISHED in the same edit that changes what is RECORDED,
# because it is the same line of code. The alternative was a constant sentence, and it was a lie
# waiting for that flip: under ``ATTRIBUTED`` it would have gone on telling a reader that attribution
# follows consent.
# --------------------------------------------------------------------------------------


def collects() -> list[str]:
    """What this deployment actually records, COMPUTED from the policy rather than asserted.

    **THE ACCOUNT-ID LINE USED TO BE A CONSTANT SAYING "ONLY WHERE CONSENT HAS BEEN RECORDED AS
    GRANTED", AND THAT IS TRUE OF EXACTLY ONE OF THE THREE POLICIES THIS MODULE SHIPS.**
    :data:`DEFAULT_UNASKED_COLLECTION` is documented as overrulable in one line and
    :class:`UnaskedCollection` names all three values on purpose, so both of the others are one edit
    away:

    * ``ATTRIBUTED`` records the id for people nobody ever asked. The old sentence would then have
      gone on telling a reader of the published method that attribution follows consent, on the same
      page whose ``consent.unaskedPolicy`` field said ATTRIBUTED — and the prose is the half a person
      reads.
    * ``NOTHING`` records no row at all, and this whole list would have gone on enumerating seven
      things that were not being collected.

    NOTE WHAT DOES *NOT* VARY: ``consentState`` stays NULL under all three policies, so no row ever
    claims a consent that was not given whatever this returns.

    LIVES IN THE SERVICE AND NOT IN THE ROUTE MODULE, since 2026-08-30, and the move is the point.
    The sign-in notice needs these sentences with NO SESSION AT ALL — a person deciding whether to
    agree has not agreed yet — while ``GET /usage/collection`` needs them behind
    ``require_usage_reader``. Two callers at two different gates, one list.
    """
    plan = collection_plan(UsageConsent.NOT_RECORDED)
    if not plan.record:
        return [
            "NOTHING. This deployment's policy for accounts nobody has asked is "
            f"{DEFAULT_UNASKED_COLLECTION.value}, and no request from an unasked account is "
            "recorded at all. The columns below describe what this instrumentation WOULD collect "
            "from an account that has agreed. See 'consent'.",
        ]

    if plan.attribute:
        account = (
            "The account id, on EVERY signed-in request — including from accounts nobody has asked. "
            "This deployment's policy for the unasked is "
            f"{DEFAULT_UNASKED_COLLECTION.value}. The rows still record consentState NULL, "
            "which means nobody was asked; they are attributed and unconsented, and anybody "
            "reporting figures drawn from them has to say so. See 'consent' below."
        )
    else:
        account = (
            "The account id, ONLY where consent has been recorded as granted. An account that has "
            "not answered, or that has refused, has no name on any row. See 'consent' below."
        )

    return [
        "The matched route TEMPLATE — /design-workshops/{workshop_id}, never the interpolated "
        "path. Record ids travel in paths here, so a table of raw paths would be a per-designer "
        "reading list of other people's fieldwork.",
        "The HTTP method.",
        "The status code the client received.",
        "Server duration in whole milliseconds.",
        "Which client said it was, from a header: web, android, or api for anything that did "
        "not say — which is every client today, because neither the web nor the Android layer "
        "sends the header yet.",
        account,
        "The moment the request finished.",
    ]


def does_not_collect() -> list[str]:
    """The other half of the notice, and the half a person actually wants. Constant, because every
    line of it is true under all three policies: none of these has a column to be stored in."""
    return [
        "The interpolated path, so no record id is ever stored.",
        "Query strings — '?q=' carries whatever somebody typed into a search box.",
        "Request or response bodies, headers other than the client label, IP addresses, "
        "user agents, or anything a person typed.",
        "Anything at all from the routes in 'notMeasured'.",
    ]


def readable_by() -> dict[str, str]:
    """Who can read what, keyed by route. **CHANGE THIS IN THE SAME COMMIT AS ANY NEW READ ROUTE.**

    It is not documentation of the gates — it is a promise made to a person at the moment they are
    deciding whether to agree, and it is shown to them verbatim. A route that reads one account's
    trail and is not named here makes the notice false for everybody who has already answered.
    ``tests/test_usage_tracking.py`` walks the router against this dict so the omission cannot ship.
    """
    return {
        "/usage/me": "the account itself, and nobody else at any rank",
        "/usage/me/trail": "the account itself, and nobody else at any rank",
        "/usage/routes": "Admin and above (deps.can_read_usage) — aggregates only, no user ids",
        "/usage/timeline": "Admin and above — aggregates only, no user ids",
        "/usage/latency": "Admin and above — aggregates only, no user ids",
        "/usage/clients": "Admin and above — aggregates only, no user ids",
        "/usage/screens": "Admin and above — aggregates only, no user ids",
        "/usage/collection": "Admin and above — this document, no figures about anybody",
        "/usage/accounts/{user_id}/trail": (
            "the MASTER ADMIN alone (deps.can_read_person_usage), and only where that account's own "
            "answer is GRANTED — a trail of somebody who refused, or who was never asked, is not "
            "readable by anyone. Each read is written to the server log naming the reader, the "
            "subject and the window; there is deliberately no durable audit TABLE for it yet, and "
            "the route says so rather than implying one"
        ),
    }


def retention_note() -> str:
    """How long these rows are kept. A sentence saying there is no policy, because the absence of one
    is a fact a person agreeing to be recorded is entitled to, and silence would be read as a policy
    somebody chose."""
    return (
        "There is no retention policy and nothing deletes these rows on a schedule; that is a "
        "decision nobody has made yet, and this sentence exists so it is not mistaken for one "
        "that was. Deleting an account deletes its rows (onDelete: Cascade), and withdrawing "
        "consent deletes that account's rows immediately."
    )


def consent_notice() -> dict[str, Any]:
    """The whole text a person is agreeing to, versioned, in the order it must be read in.

    **THE ORDER IS NOT COSMETIC.** What is collected, then what is not, then — before anything else —
    that agreeing is REQUIRED. A person who reads two paragraphs of reassurance and then discovers
    the choice was not a choice has been handled rather than asked. So the requirement is the third
    section and is stated in the plainest sentence in the payload, not implied by a disabled button.

    Then: what a duration is NOT (it measures the server, not what anybody waited for — the single
    most misread number in this feature); who can read it; that it can be withdrawn, where, and what
    a withdrawal actually does to what is already stored; and the retention answer, which is that
    there is not one.

    EVERY SECTION IS COMPUTED FROM THE RUNNING POLICY, not typed out here — see :func:`collects`.
    A deployment that flipped :data:`DEFAULT_UNASKED_COLLECTION` would publish a different notice on
    the same deploy, which is the only way a notice and a collection can be kept from disagreeing.

    THE VERSION TRAVELS WITH IT so a client can send back what it actually showed. A client that
    sends a version this server has never heard of is not refused — see
    :func:`consent_decision_plans` — because refusing would lock out a handset holding a cached
    notice, and the honest record of "they agreed to THAT text" is the version they saw.
    """
    return {
        "version": NOTICE_VERSION,
        "title": "Recording how you use this platform",
        "required": True,
        "requiredSentence": (
            "You cannot sign in without agreeing to this. It is a condition of using the platform, "
            "which means it is not a free choice — and this system records that it was not, rather "
            "than filing your answer as though you had been offered one."
        ),
        "collects": collects(),
        "doesNotCollect": does_not_collect(),
        "durationCaveat": (
            "The duration recorded is SERVER time only: from this API receiving your request to it "
            "finishing the answer. It is not what you waited for — it excludes the network, your "
            "device and anything drawn on your screen."
        ),
        "readableBy": readable_by(),
        "withdrawal": {
            "where": "Settings, on either client, at any time.",
            "costsNothing": (
                "Withdrawing does not sign you out and does not remove anything you can do. That "
                "is deliberate: an agreement you cannot take back without losing access is not an "
                "agreement."
            ),
            "does": [
                "Stops recording new requests from this account immediately.",
                "Throws away anything already observed and not yet written.",
                "DELETES the rows already stored for this account — it does not merely unname them.",
            ],
            "doesNot": [
                "It does not erase the fact that you had agreed. The dated decisions stay in your "
                "own consent log, because a withdrawal must not rewrite the answer the earlier "
                "collection was actually made under.",
            ],
        },
        "retention": retention_note(),
        "document": "docs/DECISION-usage-consent-at-sign-in.md",
    }


# --------------------------------------------------------------------------------------
# The recorded answer, and the gate a client renders from it
# --------------------------------------------------------------------------------------


def consent_record(user: Any) -> dict[str, Any]:
    """One account's stored answer, read off a row already in hand. No database work.

    Four columns rather than one, because "GRANTED" alone cannot answer any of the three questions a
    reader of a consent record actually has: when, under what circumstances, and to what text.
    """
    at = getattr(user, CONSENT_AT_ATTRIBUTE, None)
    basis = getattr(user, CONSENT_BASIS_ATTRIBUTE, None)
    version = getattr(user, CONSENT_VERSION_ATTRIBUTE, None)
    return {
        "state": resolve_consent(user).value,
        "at": at.isoformat() if isinstance(at, datetime) else None,
        "basis": str(getattr(basis, "value", basis)) if basis else None,
        "version": str(version) if version else None,
    }


def consent_gate(user: Any) -> dict[str, Any]:
    """Whether this account must be asked now, and the sentence saying why. **The client renders
    from this and computes nothing itself.**

    THE VERSION COMPARISON LIVES HERE AND IN NO CLIENT. "Have they agreed, and to the current text"
    is two facts and one answer, and the moment a web client and an Android client each derive that
    answer for themselves the two will disagree the first time somebody bumps
    :data:`NOTICE_VERSION` and only one of them is redeployed. One field, ``required``, decided by
    the server.

    THREE STATES REACH THREE DIFFERENT SENTENCES, on ``dictation_consent.gate_refusal``'s rule: the
    next moves differ, so the sentences must. Nobody-has-asked is answered by asking. A refusal has
    already been answered, and this account is *working normally* — telling it to go and agree would
    be false. A stale version is a third case again: they agreed, the text moved, and what is wanted
    is a fresh reading rather than a first one.

    IT NEVER REFUSES ANYTHING AND HAS NO STATUS CODE. It is a description of a state, attached to a
    sign-in that SUCCEEDED — see ``routes/auth.login``, which explains at length why a 403 at the
    door would be a gate nobody could get past.
    """
    state = resolve_consent(user)
    stored = consent_record(user)
    agreed_version = stored["version"]

    if state is UsageConsent.GRANTED and agreed_version == NOTICE_VERSION:
        required = False
        reason = "This account has agreed to the current version of the recording notice."
    elif state is UsageConsent.GRANTED:
        required = True
        reason = (
            "This account agreed to an earlier version of the recording notice"
            + (f" ({agreed_version})" if agreed_version else "")
            + ". The notice has changed, so the question is being asked again — agreeing to text "
            "somebody never saw is not something this system will record on their behalf. "
            "Recording continues in the meantime under the answer already given."
        )
    elif state is UsageConsent.REFUSED:
        required = False
        reason = (
            "This account has declined to have its use of the platform recorded, and nothing about "
            "its requests is kept — not anonymously either. That answer is on record and it costs "
            "this account nothing: everything else in the product works exactly as it does for "
            "anybody else. It can be changed in Settings at any time."
        )
    else:
        required = True
        reason = (
            "Nobody has asked this account yet whether its use of the platform may be recorded. "
            "Agreeing is a condition of using the platform, so this has to be answered before the "
            "product can be used — and until it is, requests are recorded WITHOUT any name on them."
        )

    return {
        "state": state.value,
        "required": required,
        "reason": reason,
        "noticeVersion": NOTICE_VERSION,
        "agreedVersion": agreed_version,
        "agreedAt": stored["at"],
        "basis": stored["basis"],
        # Named so a client never has to guess, and so the sentence above is actionable rather than
        # merely true. Both are under the /api prefix the router is mounted at.
        "answerAt": "POST /api/usage/consent",
        "noticeAt": "GET /api/usage/consent/notice",
    }


# --------------------------------------------------------------------------------------
# Writing an answer down: planned, not performed
#
# ``dictation_consent``'s shape, copied deliberately so that a reader who knows one knows the other:
# a plan can be asserted about by pytest with no database, no event loop and no generated Prisma
# client, and a plan NAMES ITS TABLE — which is what makes "a usage consent is never written onto
# the observations themselves" true by construction rather than by convention.
# --------------------------------------------------------------------------------------

#: The tables a usage consent may be written into, and the whole list.
#:
#: **``UsageEvent`` IS ABSENT AND THAT IS THE POINT.** The tempting wrong move here is exactly the
#: one the audio path had to refuse for stage entries: writing the answer onto the rows it is about.
#: An UPDATE across a consenting account's hundred thousand observations would (a) be a write to the
#: highest-write table in the schema on a request somebody is waiting on, and (b) destroy the one
#: distinction ``consentState`` exists for, because the rows collected BEFORE the answer would come
#: to claim it. A later change that wants it has to delete a check, which is a visible act in a diff
#: and a failing test rather than a quiet new call site.
CONSENT_WRITABLE_TABLES: frozenset[str] = frozenset({"User", "UsageConsentDecision"})

#: Named so the refusal can name it, and so a reader grepping for the observations table finds the
#: paragraph above.
OBSERVATION_TABLE = "UsageEvent"


class ConsentOperation(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"


@dataclass(frozen=True, slots=True)
class ConsentWritePlan:
    """One intended database write, described rather than performed."""

    table: str
    operation: ConsentOperation
    data: Mapping[str, Any]
    #: Present for an UPDATE only, and always exactly ``{"id": ...}`` — one row, named.
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in CONSENT_WRITABLE_TABLES:
            raise UsageRuleViolation(
                f"A usage consent may not be written into {self.table}. It is four columns on the "
                f"account and a row in its decision log, and nowhere else — writing it onto "
                f"{OBSERVATION_TABLE} would make the rows collected BEFORE the answer claim it, "
                f"which is the one distinction consentState exists for. Write it to one of "
                f"{', '.join(sorted(CONSENT_WRITABLE_TABLES))}."
            )
        if self.operation is ConsentOperation.UPDATE and not self.where:
            raise UsageRuleViolation(
                "An update must name the single row it changes. Pass where={'id': user_id}."
            )
        if self.operation is ConsentOperation.CREATE and self.where:
            raise UsageRuleViolation("A create names no existing row. Drop the where clause.")


@dataclass(frozen=True, slots=True)
class ConsentDecisionPlans:
    """The two writes one consent decision makes: the answer on the account, and the log row.

    Returned together and applied together. The log is the authoritative history — it is what keeps
    "who agreed on the 3rd" answerable after a withdrawal on the 9th, by which time the observations
    made under the grant have been DELETED — and the columns are the current state
    :func:`resolve_consent` reads on every request without walking a log.
    """

    account: ConsentWritePlan
    decision: ConsentWritePlan

    def __iter__(self):
        yield self.account
        yield self.decision


def consent_decision_plans(
    *,
    user_id: str,
    decision: UsageConsent,
    basis: UsageConsentBasis,
    notice_version: str,
    at: datetime,
    recorded_at: datetime | None = None,
    note: str | None = None,
) -> ConsentDecisionPlans:
    """One account records its own answer. Pure: no database, no framework, no clock of its own.

    ``at`` is the server's clock — when this request arrived. ``recorded_at`` is what the CLIENT
    said, and when it is present it is the moment the box was actually ticked; Android signs people
    in offline-capable contexts and syncs later.

    **WHAT LANDS IN ``usageConsentAt`` IS THE MOMENT THE PERSON ANSWERED**, so ``recorded_at`` when
    it was supplied and ``at`` when it was not. The other question — when the server heard it — is
    answered by the log row's own ``createdAt`` default, which is why nothing here sets it.

    ``NOT_RECORDED`` IS REFUSED AS A DECISION, exactly as it is one consent question over. It is the
    absence of an answer, and "somebody deliberately recorded that nobody has been asked" is not a
    state a person can be in. Taking an answer back is ``REFUSED``, which is a decision with a next
    move; un-recording one would leave a log saying an answer was un-given and a gate that cannot
    tell that from an account nobody has opened.

    **AN UNRECOGNISED ``notice_version`` IS ACCEPTED, NOT REFUSED**, and that is a deliberate
    asymmetry with everything else this function checks. A handset can hold a cached notice for a
    fortnight and a rollback can put an older one back in front of people; refusing the answer would
    lock those people out of a product whose door this consent is. What the record has to be true
    about is WHICH TEXT THEY SAW, and the honest answer to that is the version they say they saw. It
    is stored verbatim and bounded, never rewritten to today's.
    """
    account = str(user_id or "").strip()
    if not account:
        raise UsageRuleViolation(
            "A usage consent belongs to an account. Sign in and record it as yourself — there is "
            "deliberately no route by which one person records another's answer about being "
            "observed, because a consent somebody else can enter for you is not a consent."
        )
    if decision is UsageConsent.NOT_RECORDED:
        raise UsageRuleViolation(
            "NOT_RECORDED is what an account says before anybody has asked, not an answer somebody "
            "can record. Send GRANTED to agree, or REFUSED to decline — REFUSED is also how an "
            "agreement is withdrawn, and it deletes what has already been collected."
        )
    version = str(notice_version or "").strip()[:64]
    if not version:
        raise UsageRuleViolation(
            "A consent must say which version of the notice was on screen. Send the 'version' the "
            "notice endpoint returned — a record that cannot say what was agreed to is worse than "
            "none, because it invites the one claim it cannot support."
        )

    # BOTH MOMENTS ARE MADE AWARE BEFORE EITHER IS COMPARED, and the reason is a 500 rather than a
    # preference: an ISO-8601 moment with no offset parses NAIVE, and `naive > aware` raises
    # TypeError in Python — which is not a UsageRuleViolation, so no route's except clause catches
    # it, and a designer recording an answer they were entitled to file gets "something went wrong".
    at = _as_utc_moment(at)
    recorded_at = _as_utc_moment(recorded_at)
    if recorded_at is not None and at is not None and recorded_at > at + MAX_DEVICE_CLOCK_SKEW:
        raise UsageRuleViolation(
            f"This answer says it was recorded at {recorded_at.isoformat()}, which is in the "
            f"future — the device's clock is wrong. Fix the date and time on the device and try "
            f"again, or answer here so this server's own clock is used. It is not stored with a "
            f"corrected time, because when somebody consented is not something this server may "
            f"guess."
        )

    answered_at = recorded_at or at
    return ConsentDecisionPlans(
        account=ConsentWritePlan(
            table="User",
            operation=ConsentOperation.UPDATE,
            where={"id": account},
            data={
                CONSENT_ATTRIBUTE: decision.value,
                CONSENT_AT_ATTRIBUTE: answered_at,
                CONSENT_BASIS_ATTRIBUTE: basis.value,
                CONSENT_VERSION_ATTRIBUTE: version,
            },
        ),
        decision=ConsentWritePlan(
            table="UsageConsentDecision",
            operation=ConsentOperation.CREATE,
            data={
                "userId": account,
                "decision": decision.value,
                "basis": basis.value,
                "noticeVersion": version,
                "note": (note or "").strip()[:500] or None,
                # Only what the CLIENT said. NULL when the answer was given straight against this
                # server, where `createdAt` is the same moment and a copy would later read as "a
                # device reported this", which would be false.
                "recordedAt": recorded_at,
            },
        ),
    )


def _as_utc_moment(moment: datetime | None) -> datetime | None:
    """A moment that can be compared with another. Naive means UTC; aware keeps its own offset.

    NOT a conversion to UTC: ``+05:30`` is what the device said and rewriting it would lose the only
    clue about where the answer was taken down. All this does is stop a missing offset turning into a
    ``TypeError`` at the comparison two lines above the refusal it would otherwise have produced.
    """
    if moment is None or moment.tzinfo is not None:
        return moment
    return moment.replace(tzinfo=UTC)


def _consent_model(table: str) -> Any:
    """The Prisma model one writable table name maps to. THE SECOND HALF OF THE CONSTRUCTION GUARD.

    The name is checked before the client is touched, so the refusal is a :class:`UsageRuleViolation`
    with a sentence on any machine, with or without a generated Prisma client. There is no entry for
    ``UsageEvent``, so even a plan that somehow carried its name — a future edit that loosened
    :class:`ConsentWritePlan` — would still have nowhere to be applied. Resolved per call rather than
    in a module-level dict, because a dict built at import binds the client's attributes before
    anything has connected.
    """
    if table not in CONSENT_WRITABLE_TABLES:
        raise UsageRuleViolation(
            f"There is no usage-consent writer for {table}. A consent is four columns on the "
            f"account and a row in its decision log: {', '.join(sorted(CONSENT_WRITABLE_TABLES))}."
        )
    return {"User": db.user, "UsageConsentDecision": db.usageconsentdecision}[table]


async def apply_consent_plan(plan: ConsentWritePlan) -> Any:
    """Perform one planned write. The only place this module touches consent storage with intent."""
    model = _consent_model(plan.table)
    if plan.operation is ConsentOperation.CREATE:
        return await model.create(data=dict(plan.data))
    return await model.update(where=dict(plan.where or {}), data=dict(plan.data))


@dataclass(frozen=True, slots=True)
class ConsentOutcome:
    """What recording one answer actually reached. Returned by :func:`record_consent`."""

    #: The updated ``User`` row, as the client should read its consent columns back from.
    account: Any
    #: The decision that was recorded.
    decision: UsageConsent
    #: What the withdrawal reached, on a REFUSAL. ``None`` on a grant, where nothing is deleted.
    withdrawal: Withdrawal | None


async def record_consent(
    *,
    user_id: str,
    decision: UsageConsent,
    basis: UsageConsentBasis,
    notice_version: str,
    at: datetime | None = None,
    recorded_at: datetime | None = None,
    note: str | None = None,
) -> ConsentOutcome:
    """**THE ONE DOOR.** Record an answer, and on a refusal do to the collected data what the audio
    path does to a queued recording.

    ── WHAT IT FOLLOWS, NAMED ──────────────────────────────────────────────────────────────────

    ``routes/design_workshops.record_dictation_consent`` is the model and this function copies three
    things from it deliberately:

    1. **TWO WRITES, THE ANSWER FIRST AND THE LOG SECOND, AND NOT IN A TRANSACTION.** The order is
       chosen for the failure it leaves. If the log write fails after the account write, the account
       carries a correctly attributed answer with one history row missing: recoverable, and the gate
       is right. The other order would leave a log saying somebody withdrew while
       :func:`resolve_consent` still reads GRANTED and collection continues — which is the one
       failure that matters.
    2. **THE REFUSAL REACHES WHAT WAS ALREADY COLLECTED, and only the refusal does.** There, a
       REFUSED decision runs ``cancel_pending_transcriptions``, because nine clips queued under a
       grant given on the 3rd would otherwise go out on the night of the 9th — *"a consent that
       cannot recall what it already authorised is a preference, not a permission."* Here the queue
       is this module's own buffer and the archive is the ``UsageEvent`` table, and :func:`withdraw`
       empties the first and DELETES from the second. Guarded to REFUSED exactly as that call is: on
       a GRANT it would delete the record the grant was given in order to keep.
    3. **AFTER the decision is applied, never before.** The person's answer is the thing that
       matters and must land even if the cleanup fails; :func:`withdraw` never raises, for that
       reason.

    ── AND THE ONE THING IT ADDS, WHICH THE AUDIO PATH HAS NO NEED OF ──────────────────────────

    **A GRANT CALLS :func:`resume`.** ``_WITHDRAWN`` is a process-local set that makes
    :func:`record_event` and :func:`flush` refuse an account without a database read; a person who
    withdraws and later agrees again would otherwise stay in it for the life of the process, and
    their re-consent would be recorded, believed by every other part of the system, and quietly
    ineffective. There is no equivalent trap one consent question over because that gate reads a
    column and holds no set.

    THE DURABLE HALF IS NOW REAL, which is what closes the gap :func:`withdraw`'s docstring names.
    The answer is a column, ``resolve_consent`` reads it on every request through the row the
    dependency layer already loaded, and a second worker therefore honours a refusal it never saw
    recorded. The process-local set is now a FAST PATH — it stops rows already in this process's
    buffer — and no longer the only defence.

    THE CACHED IDENTITY IS INVALIDATED BY THE CALLER, not here. ``deps.invalidate_cached_user`` lives
    in a module that imports this one, so calling it from here would be an import cycle;
    ``routes/usage`` does it, and its docstring says why it must.
    """
    plans = consent_decision_plans(
        user_id=user_id,
        decision=decision,
        basis=basis,
        notice_version=notice_version,
        at=at if at is not None else datetime.now(UTC),
        recorded_at=recorded_at,
        note=note,
    )
    account = await apply_consent_plan(plans.account)
    await apply_consent_plan(plans.decision)

    withdrawal: Withdrawal | None = None
    if decision is UsageConsent.REFUSED:
        withdrawal = await withdraw(user_id)
    else:
        resume(user_id)
    return ConsentOutcome(account=account, decision=decision, withdrawal=withdrawal)


async def consent_history(user_id: str, *, limit: int = 50) -> list[Any]:
    """One account's answers, newest first. The only read this log has."""
    if not user_id:
        return []
    return await db.usageconsentdecision.find_many(
        where={"userId": user_id},
        order=[{"createdAt": "desc"}, {"id": "desc"}],
        take=max(1, min(int(limit), 200)),
    )


def consent_decision_payload(row: Any) -> dict[str, Any]:
    """One recorded answer as the clients read it.

    BOTH MOMENTS ARE CARRIED, and that is the point of the pair rather than a duplication:
    ``recordedAt`` is what the client said (null when the answer was given straight against this
    server) and ``createdAt`` is when the server heard it. A fortnight of no signal makes them differ
    by a fortnight, and a reader who can see only one of them cannot tell an answer given today from
    one given before the device was last synced.

    ``basis`` AND ``noticeVersion`` ARE NOT OPTIONAL ON THE WIRE. They are the two fields that make
    this row a consent record rather than a boolean with a date on it.
    """
    return {
        "id": getattr(row, "id", None),
        "decision": _enum_token(getattr(row, "decision", None)),
        "basis": _enum_token(getattr(row, "basis", None)),
        "noticeVersion": getattr(row, "noticeVersion", None),
        "note": getattr(row, "note", None),
        "recordedAt": _iso_or_none(getattr(row, "recordedAt", None)),
        "createdAt": _iso_or_none(getattr(row, "createdAt", None)),
    }


def _enum_token(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _iso_or_none(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


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


# --------------------------------------------------------------------------------------
# THE RICHER AGGREGATES: over time, at the tail, and split by client
#
# ── WHY THESE THREE ARE RAW SQL WHEN NOTHING ELSE IN THIS MODULE IS ─────────────────────────
#
# Not for speed, and not because Prisma is inadequate in general. Each of the three asks for
# something Prisma's ``group_by`` cannot express AT ALL:
#
#   * bucketing by hour or day needs ``date_trunc`` in the GROUP BY, and ``group_by`` groups only by
#     stored columns;
#   * a percentile needs ``percentile_cont`` — an ordered-set aggregate — and ``group_by`` offers
#     count, avg, min, max and sum. **NO PERCENTILE IS RECOVERABLE FROM WHAT IS ALREADY STORED**:
#     ``avgDurationMs`` is a count-weighted mean of per-group means (see :func:`_fold_status`), and a
#     mean carries no information about a tail. p95 is the number anybody actually wants — a route
#     whose mean is 120 ms and whose p95 is 4 seconds is a route that is broken for one request in
#     twenty, and the mean says it is fine;
#   * ``COUNT(DISTINCT "userId")`` in the same statement as the counts is what lets the withholding
#     floor be applied per emitted row without a second round trip. ``group_by`` can only produce it
#     as a separate grouping, which is why :func:`usage_for_routes` pays two.
#
# ── AND WHY EVERY ONE OF THEM IS STILL AN INDEX PROBE ───────────────────────────────────────
#
# All three carry ``"routeTemplate" = ANY($…)`` plus a ``createdAt`` range: equality on a bounded set
# of named templates, then a range — exactly the shape ``@@index([routeTemplate, createdAt])`` was
# built for, and never a scan. **THERE IS DELIBERATELY NO SPELLING FOR "EVERY ROUTE" ON ANY OF
# THEM**, for the same reason :func:`usage_for_routes` has none: that is a whole-window scan across
# every user and every route, the schema refuses to build the ``createdAt``-only index that would
# serve one, and no route added in this wave asks for it. If somebody wants the global ranking, the
# answer is a daily rollup table designed on purpose.
#
# ── THE MOMENTS ARE BOUND AS NAIVE-UTC TEXT, WHICH IS NOT AN OVERSIGHT ──────────────────────
#
# Prisma maps ``DateTime`` to ``TIMESTAMP(3)`` — no time zone — and stores UTC in it. So each bound
# is converted to UTC, stripped of its offset and bound as an ISO string cast to ``::timestamp``,
# which is byte-for-byte what the ORM path compares against. Binding an AWARE value against a
# ``timestamptz`` cast would make Postgres convert using the SERVER's timezone, producing a window
# quietly wrong by hours rather than an error anybody notices — the same failure
# :func:`_ensure_window` refuses naive datetimes to prevent, arriving from the other direction.
# Interpolating the dates into the SQL text is refused outright: this module is one keystroke from
# recording record ids, and a file that interpolates one value teaches the next reader to interpolate
# another.
# --------------------------------------------------------------------------------------

#: The buckets a timeline may be asked for. Two, and both are calendar units in UTC — there is no
#: "every 5 minutes", because a bucket finer than an hour over a route only a few people use is a
#: named person's afternoon reconstructed from a page labelled *aggregates*.
TIMELINE_BUCKETS: tuple[str, ...] = ("hour", "day")

#: Most buckets one timeline may return, and **the response states it**.
#:
#: 750 is chosen from the two windows anybody actually asks for: a year of days is 366 and a month of
#: hours is 744, and both fit. Hourly over the full 366-day window is 8,784 rows of JSON describing
#: an index range scan that touched every row in the year — a request that is almost certainly a
#: mistake, and one that would be served slowly and then rendered as an unreadable chart. It is
#: REFUSED with the arithmetic in the sentence rather than silently truncated, on
#: ``analytics.ROW_CAP``'s rule: a cap that is announced is a cap, and a cap that is silent is a lie.
MAX_TIMELINE_BUCKETS = 750

#: The percentiles reported, in the order they are reported. Three, and the median is included
#: BESIDE the tail rather than instead of it: p50 alone hides the tail and p99 alone cannot say
#: whether the tail is the whole distribution or one request in a hundred.
LATENCY_PERCENTILES: tuple[float, ...] = (0.5, 0.95, 0.99)

#: Rows one trail read may return. **A trail is a log and not an aggregate** — it is the one shape in
#: this module that replays the order somebody moved through the app — so the page is small and the
#: cap is stated on every response. 200 is one screenful of scrolling and roughly an hour of one
#: person's ordinary work; a caller who wants a day walks the pages, which is deliberate friction.
MAX_TRAIL_ROWS = 200


def _sql_window(since: datetime, until: datetime) -> tuple[str, str]:
    """The window as the two naive-UTC ISO strings the raw statements bind. See the banner above."""
    return (
        since.astimezone(UTC).replace(tzinfo=None).isoformat(sep=" ", timespec="milliseconds"),
        until.astimezone(UTC).replace(tzinfo=None).isoformat(sep=" ", timespec="milliseconds"),
    )


def _checked_templates(templates: Sequence[str]) -> list[str]:
    """The template list every new aggregate takes, put through the one gate and the one cap.

    Shared rather than repeated so that a caller cannot use any of these endpoints as an oracle by
    probing it with interpolated paths, and so the cap is one number in one place.
    """
    wanted = [ensure_route_template(value) for value in templates]
    if not wanted:
        raise UsageRuleViolation(
            "This report needs at least one route template; there is no 'every route' form, "
            "because that is a scan of the whole window and no index serves it."
        )
    if len(wanted) > MAX_TEMPLATES_PER_QUERY:
        raise UsageRuleViolation(
            f"At most {MAX_TEMPLATES_PER_QUERY} templates may be named at a time; this call named "
            f"{len(wanted)}."
        )
    return wanted


def _withheld_because(what: str) -> str:
    """The one sentence every withheld row in this module carries, with the subject swapped in.

    Written once so that four endpoints cannot come to explain the same refusal four different ways
    — and phrased as what the figure WOULD have described rather than as a rule number, because the
    reader is an administrator looking at a dash and not a person reading this file.
    """
    return (
        f"Fewer than {MIN_IDENTIFIED_USERS_FOR_ROUTE} identified people are behind {what}, so a "
        f"figure here would describe them individually rather than describe a group."
    )


def _withhold(entry: dict[str, Any], keys: Sequence[str], *, what: str) -> dict[str, Any]:
    """Blank every metric on one row and say why. **A REFUSAL AND NEVER A ZERO.**

    Every value goes to ``None`` and ``withheld`` goes to True. That pairing is load-bearing on the
    client side and the reason it is worth a helper: ``null`` coerces to 0 through arithmetic and
    through ``??``, so a consumer that falls back instead of branching on ``withheld`` publishes a
    number this server explicitly refused to state — and a chart is worse than a table here, because
    a plotted zero looks like a measurement while a blank cell looks like a blank cell.
    """
    blanked = dict(entry)
    for key in keys:
        blanked[key] = None
    blanked["withheld"] = True
    blanked["withheldBecause"] = _withheld_because(what)
    return blanked


def _error_rate(requests: Any, client_errors: Any, server_errors: Any) -> float | None:
    """Errors as a proportion of requests, or None when there is nothing to divide.

    ZERO REQUESTS IS None AND NOT 0.0, which is the whole reason this is a function. An hour with no
    traffic has no error rate — 0/0 is not "nothing went wrong", it is "nothing happened" — and a
    chart that plots the first as the second draws a reassuring flat line through every night and
    every outage in which the API answered nothing at all.
    """
    total = int(requests or 0)
    if total <= 0:
        return None
    return round((int(client_errors or 0) + int(server_errors or 0)) / total, 4)


async def usage_timeline(
    templates: Sequence[str],
    since: datetime,
    until: datetime,
    *,
    bucket: str = "day",
) -> dict[str, Any]:
    """Requests and errors over time for named screens, bucketed by hour or day.

    ONE STATEMENT. The counts, the three status bands and the distinct-identity count come back
    together, because splitting them would pay a 756 ms latency twice to compute what one grouping
    already returns — and because the floor has to be applied per bucket, which needs the identity
    count in the same row as the figures it would withhold.

    **THE FLOOR APPLIES PER BUCKET AND NOT PER SERIES, AND THAT IS THE STRICTER READING ON PURPOSE.**
    A series-level check would let an hour used by one person ride through inside a window used by
    fifty — which is exactly the shape of question this floor exists to refuse, since the window is
    chosen by whoever is asking and can be narrowed until only one person is left in it. So each
    bucket carries its own ``identifiedUsers`` and each is withheld on its own.

    AN EMPTY BUCKET IS RETURNED AS A ZERO AND NOT OMITTED. A gap in a series is read as "no data
    here"; a zero is read as "nothing happened here", and only the second is true of an hour this
    API was awake for. The buckets are filled in Python rather than by ``generate_series`` in the
    statement, so the SQL stays an index probe over rows that exist.
    """
    since, until = _ensure_window(since, until)
    wanted = _checked_templates(templates)
    unit = str(bucket or "").strip().lower()
    if unit not in TIMELINE_BUCKETS:
        raise UsageRuleViolation(
            f"A timeline is bucketed by one of {', '.join(TIMELINE_BUCKETS)}; {bucket!r} is "
            f"neither. There is deliberately no finer bucket: below an hour, a series over a screen "
            f"a few people use is one person's afternoon on a page labelled aggregates."
        )
    expected = _expected_buckets(since, until, unit)
    if expected > MAX_TIMELINE_BUCKETS:
        raise UsageRuleViolation(
            f"That window is {expected} {unit} buckets and at most {MAX_TIMELINE_BUCKETS} are "
            f"returned. Ask for a narrower window, or bucket by day instead of by hour — the "
            f"request is refused rather than truncated, because a truncated series looks exactly "
            f"like a period in which nothing happened."
        )

    start, end = _sql_window(since, until)
    rows = await db.query_raw(
        """
        SELECT
          to_char(date_trunc($1::text, "createdAt"), 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS bucket,
          COUNT(*)::int                                                        AS requests,
          COUNT(*) FILTER (WHERE "statusCode" < 400)::int                      AS ok,
          COUNT(*) FILTER (WHERE "statusCode" >= 400 AND "statusCode" < 500)::int AS client_errors,
          COUNT(*) FILTER (WHERE "statusCode" >= 500)::int                     AS server_errors,
          COUNT(DISTINCT "userId")::int                                        AS identified_users
        FROM "UsageEvent"
        WHERE "routeTemplate" = ANY($2::text[])
          AND "createdAt" >= $3::timestamp
          AND "createdAt" <  $4::timestamp
        GROUP BY 1
        ORDER BY 1
        """,
        unit,
        wanted,
        start,
        end,
    )

    # COUNT(DISTINCT "userId") already skips NULLs — SQL's own rule — which happens to be exactly the
    # rule the floor needs: a row identifying nobody protects nobody, so it must not be counted
    # towards a floor that exists to protect identified people. Stated because the alignment is
    # convenient rather than deliberate, and a later reader "fixing" it with COALESCE would suppress
    # every unauthenticated screen in the product.
    by_bucket = {str(row["bucket"]): row for row in rows}
    metrics = ("requests", "ok", "clientErrors", "serverErrors", "errorRate", "identifiedUsers")
    series: list[dict[str, Any]] = []
    for label in _bucket_labels(since, until, unit):
        row = by_bucket.get(label)
        if row is None:
            series.append(
                {
                    "bucket": label,
                    "requests": 0,
                    "ok": 0,
                    "clientErrors": 0,
                    "serverErrors": 0,
                    "errorRate": None,
                    "identifiedUsers": 0,
                    "withheld": False,
                }
            )
            continue
        entry = {
            "bucket": label,
            "requests": int(row["requests"] or 0),
            "ok": int(row["ok"] or 0),
            "clientErrors": int(row["client_errors"] or 0),
            "serverErrors": int(row["server_errors"] or 0),
            "errorRate": _error_rate(
                row["requests"], row["client_errors"], row["server_errors"]
            ),
            "identifiedUsers": int(row["identified_users"] or 0),
            "withheld": False,
        }
        people = entry["identifiedUsers"]
        if 0 < people < MIN_IDENTIFIED_USERS_FOR_ROUTE:
            entry = _withhold(entry, metrics, what=f"this {unit}")
        series.append(entry)

    return {
        "from": since,
        "to": until,
        "bucket": unit,
        "templates": wanted,
        "minimumIdentifiedUsers": MIN_IDENTIFIED_USERS_FOR_ROUTE,
        "maxBuckets": MAX_TIMELINE_BUCKETS,
        "series": series,
    }


def _expected_buckets(since: datetime, until: datetime, unit: str) -> int:
    """How many buckets a window spans, counted the way :func:`_bucket_labels` fills them.

    Computed BEFORE the query so an over-wide request is refused without touching the database, which
    is the same ordering ``routes/usage._window`` uses for the window cap and for the same reason: a
    refusal that costs a round trip teaches people to fear the endpoint. (That was a 756 ms hop when
    this was written and is a co-located millisecond or two since 2026-09-02; the ordering costs
    nothing either way, and a refusal that reaches the database is also a refusal that takes a
    connection.)
    """
    step = timedelta(hours=1) if unit == "hour" else timedelta(days=1)
    first = _floor_to(since, unit)
    span = until - first
    # Ceiling division, so a window ending mid-bucket still counts the bucket it ends in — the same
    # bucket :func:`_bucket_labels` will emit for it.
    return max(1, -(-int(span.total_seconds()) // int(step.total_seconds())))


def _floor_to(moment: datetime, unit: str) -> datetime:
    """``date_trunc`` in Python, in UTC, so the labels this module generates and the labels Postgres
    generates are the same strings. They are compared as strings, so a mismatch would show up as a
    series of empty buckets sitting next to a series of orphaned rows."""
    at_utc = moment.astimezone(UTC)
    if unit == "hour":
        return at_utc.replace(minute=0, second=0, microsecond=0)
    return at_utc.replace(hour=0, minute=0, second=0, microsecond=0)


def _bucket_labels(since: datetime, until: datetime, unit: str) -> list[str]:
    """Every bucket in ``[since, until)``, as the exact strings ``to_char`` produced above."""
    step = timedelta(hours=1) if unit == "hour" else timedelta(days=1)
    labels: list[str] = []
    cursor = _floor_to(since, unit)
    while cursor < until and len(labels) <= MAX_TIMELINE_BUCKETS:
        labels.append(cursor.strftime("%Y-%m-%dT%H:%M:%SZ"))
        cursor += step
    return labels


async def usage_latency(
    templates: Sequence[str], since: datetime, until: datetime
) -> dict[str, Any]:
    """Median and tail latency per screen. **The numbers the stored averages cannot produce.**

    ``avgDurationMs`` elsewhere in this module is a count-weighted mean of per-group means — exact as
    a mean, and carrying no information whatever about a tail. A screen averaging 120 ms with a p95
    of four seconds is broken for one request in twenty and reads as healthy in every other endpoint
    here. That is why this is a separate statement and not a serializer change over what is already
    fetched: ``percentile_cont`` is an ordered-set aggregate over the raw column, and there is
    nothing stored anywhere from which it could be reconstructed afterwards.

    SERVER TIME, LIKE EVERYTHING ELSE IN THIS MODULE. A p99 here is the ninety-ninth percentile of
    what this API took, not of what anybody waited for; it excludes the network, the device and the
    render. The response carries that sentence rather than leaving it to be assumed, because a
    percentile reads as more authoritative than a mean and is misread in the same direction.

    THE FLOOR APPLIES PER TEMPLATE, on the identity count from the same statement. A p99 over four
    people is one of those four people's worst request.
    """
    since, until = _ensure_window(since, until)
    wanted = _checked_templates(templates)
    start, end = _sql_window(since, until)

    rows = await db.query_raw(
        """
        SELECT
          "routeTemplate"                                                       AS template,
          COUNT(*)::int                                                         AS requests,
          COUNT(DISTINCT "userId")::int                                         AS identified_users,
          percentile_cont(0.5)  WITHIN GROUP (ORDER BY "durationMs")            AS p50,
          percentile_cont(0.95) WITHIN GROUP (ORDER BY "durationMs")            AS p95,
          percentile_cont(0.99) WITHIN GROUP (ORDER BY "durationMs")            AS p99,
          MAX("durationMs")::int                                                AS max_ms
        FROM "UsageEvent"
        WHERE "routeTemplate" = ANY($1::text[])
          AND "createdAt" >= $2::timestamp
          AND "createdAt" <  $3::timestamp
        GROUP BY 1
        """,
        wanted,
        start,
        end,
    )

    by_template = {str(row["template"]): row for row in rows}
    metrics = ("requests", "identifiedUsers", "p50Ms", "p95Ms", "p99Ms", "maxDurationMs")
    out: list[dict[str, Any]] = []
    for name in wanted:
        row = by_template.get(name)
        if row is None:
            # A screen with no traffic in the window, reported as such rather than omitted — the
            # same rule `usage_for_routes` follows, and a real answer a data-driven list could not
            # give. Every percentile is None because there is no distribution, which is not the same
            # fact as a withheld one and must not be rendered as one.
            out.append(
                {
                    "routeTemplate": name,
                    "requests": 0,
                    "identifiedUsers": 0,
                    "withheld": False,
                    "p50Ms": None,
                    "p95Ms": None,
                    "p99Ms": None,
                    "maxDurationMs": None,
                }
            )
            continue
        entry = {
            "routeTemplate": name,
            "requests": int(row["requests"] or 0),
            "identifiedUsers": int(row["identified_users"] or 0),
            "withheld": False,
            # Rounded to a whole millisecond, exactly as `_fold_status` rounds its mean and for the
            # column's own stated reason: `durationMs` is an Int, and a percentile printed to three
            # decimal places reads as far more exact than the thing it measured.
            "p50Ms": _round_ms(row["p50"]),
            "p95Ms": _round_ms(row["p95"]),
            "p99Ms": _round_ms(row["p99"]),
            "maxDurationMs": int(row["max_ms"] or 0),
        }
        people = entry["identifiedUsers"]
        if 0 < people < MIN_IDENTIFIED_USERS_FOR_ROUTE:
            entry = _withhold(entry, metrics, what="this screen in this period")
        out.append(entry)

    return {
        "from": since,
        "to": until,
        "percentiles": [f"p{int(value * 100)}" for value in LATENCY_PERCENTILES],
        "minimumIdentifiedUsers": MIN_IDENTIFIED_USERS_FOR_ROUTE,
        "routes": out,
    }


def _round_ms(value: Any) -> int | None:
    """A percentile as a whole millisecond, or None where Postgres had no rows to compute one."""
    if value is None:
        return None
    return round(float(value))


async def usage_clients(
    templates: Sequence[str], since: datetime, until: datetime
) -> dict[str, Any]:
    """The web / android / api split over named screens.

    **THE COLUMN HAS ALWAYS EXISTED AND IS ALWAYS ``api`` TODAY.** ``clientApp`` is written on every
    row from the ``x-client-app`` header, :data:`CLIENT_APPS` lists the three values it accepts and
    :data:`DEFAULT_CLIENT_APP` is what an unlabelled client records as — and as of 2026-08-30 neither
    ``frontend/lib/api.ts`` nor the Android network layer sends the header. So this endpoint answers
    honestly and the answer is currently "one client, and it did not say what it was".

    THAT IS WORTH SHIPPING RATHER THAN WAITING FOR, and the response says why in its own words: the
    schema's argument for the column is that "how do they navigate" has different answers on a laptop
    and on a handset that runs offline for a fortnight at a time, and until the header is sent the
    two are averaged into a designer who does not exist. An endpoint reporting 100% ``api`` is what
    makes that gap visible to whoever can close it; a missing endpoint makes it invisible.

    THE FLOOR APPLIES PER CLIENT ROW. If exactly three identified people used the Android app in the
    window, "Android: 412 requests, 3 people" is a description of those three.
    """
    since, until = _ensure_window(since, until)
    wanted = _checked_templates(templates)
    start, end = _sql_window(since, until)

    rows = await db.query_raw(
        """
        SELECT
          "clientApp"                                                           AS client,
          COUNT(*)::int                                                         AS requests,
          COUNT(*) FILTER (WHERE "statusCode" < 400)::int                       AS ok,
          COUNT(*) FILTER (WHERE "statusCode" >= 400 AND "statusCode" < 500)::int AS client_errors,
          COUNT(*) FILTER (WHERE "statusCode" >= 500)::int                      AS server_errors,
          COUNT(DISTINCT "userId")::int                                         AS identified_users,
          AVG("durationMs")                                                     AS avg_ms
        FROM "UsageEvent"
        WHERE "routeTemplate" = ANY($1::text[])
          AND "createdAt" >= $2::timestamp
          AND "createdAt" <  $3::timestamp
        GROUP BY 1
        """,
        wanted,
        start,
        end,
    )

    by_client = {str(row["client"]): row for row in rows}
    metrics = (
        "requests",
        "ok",
        "clientErrors",
        "serverErrors",
        "errorRate",
        "identifiedUsers",
        "avgDurationMs",
    )
    out: list[dict[str, Any]] = []
    # Every known client is emitted whether or not it appears, in a fixed order, so a client with no
    # traffic reads as a zero rather than as an absence — and so the shape of the answer does not
    # change on the day the web layer starts sending the header. Anything stored outside the three
    # (a hand-edited row, a value from a build that predates `normalise_client_app`) is appended
    # rather than dropped: silently omitting a client would understate the total.
    known = sorted(CLIENT_APPS)
    for name in known + sorted(set(by_client) - set(known)):
        row = by_client.get(name)
        if row is None:
            out.append(
                {
                    "clientApp": name,
                    "requests": 0,
                    "ok": 0,
                    "clientErrors": 0,
                    "serverErrors": 0,
                    "errorRate": None,
                    "identifiedUsers": 0,
                    "avgDurationMs": None,
                    "withheld": False,
                }
            )
            continue
        entry = {
            "clientApp": name,
            "requests": int(row["requests"] or 0),
            "ok": int(row["ok"] or 0),
            "clientErrors": int(row["client_errors"] or 0),
            "serverErrors": int(row["server_errors"] or 0),
            "errorRate": _error_rate(
                row["requests"], row["client_errors"], row["server_errors"]
            ),
            "identifiedUsers": int(row["identified_users"] or 0),
            "avgDurationMs": _round_ms(row["avg_ms"]),
            "withheld": False,
        }
        people = entry["identifiedUsers"]
        if 0 < people < MIN_IDENTIFIED_USERS_FOR_ROUTE:
            entry = _withhold(entry, metrics, what="this client in this period")
        out.append(entry)

    return {
        "from": since,
        "to": until,
        "templates": wanted,
        "minimumIdentifiedUsers": MIN_IDENTIFIED_USERS_FOR_ROUTE,
        "clients": out,
    }


# --------------------------------------------------------------------------------------
# THE TRAIL: the one shape in this module that is a log rather than an aggregate
#
# Everything above answers "how often, how fast, how often broken" and cannot replay an afternoon.
# This can. It is therefore the most sensitive read in the feature, and the gate is NOT here — see
# ``routes/usage.py``, which owns it, on the house rule that this repository has twice shipped a UI
# guard over an open endpoint. What THIS function owns is that it cannot be pointed at a set of
# people: there is no "everybody" form and no filter, only one named account.
# --------------------------------------------------------------------------------------


async def trail_for_user(
    user_id: str,
    since: datetime,
    until: datetime,
    *,
    limit: int = MAX_TRAIL_ROWS,
    offset: int = 0,
) -> dict[str, Any]:
    """One account's requests in the order they happened. Rides ``@@index([userId, createdAt])``.

    **THE CALLER MUST ALREADY HAVE ESTABLISHED THAT IT MAY READ THIS ACCOUNT.** This function
    authorises nothing; it is the query, not the gate — the same division
    :func:`usage_for_user` states, and it matters more here because this shape is a log.

    NO WITHHOLDING FLOOR, and its absence is not an oversight in either of the two ways this is
    read. A floor exists to stop one person being picked out of a group, and there is no group here
    at all: the subject is named in the URL. Applying one would mean refusing to show somebody their
    own afternoon on the grounds that too few people were in it.

    NEWEST FIRST, AND TIE-BROKEN ON ``id``. Two requests can share a millisecond — the timestamp is
    stamped when the response finished and this API serves parallel requests — so ordering on
    ``createdAt`` alone is not a total order, and a paged read over a non-total order silently skips
    and repeats rows at the page boundary. The cuid is the tiebreaker because it is the only other
    column guaranteed distinct.

    IT RETURNS THE ``consentState`` OF EVERY ROW rather than folding it away, and that is deliberate
    on the cross-account route: a trail whose rows say GRANTED is a trail collected under an
    agreement, and a reader is entitled to see that on the rows rather than infer it from the
    account's current answer — which may have been given after some of the rows were written.
    """
    since, until = _ensure_window(since, until)
    if not user_id:
        raise UsageRuleViolation(
            "trail_for_user needs an account id; there is no 'everybody' form, and there is no "
            "filter that would make one."
        )
    take = max(1, min(int(limit), MAX_TRAIL_ROWS))
    skip = max(0, int(offset))

    rows = await db.usageevent.find_many(
        where={"userId": user_id, **_window_where(since, until)},
        order=[{"createdAt": "desc"}, {"id": "desc"}],
        take=take,
        skip=skip,
    )
    return {
        "userId": user_id,
        "from": since,
        "to": until,
        "limit": take,
        "offset": skip,
        "maxRows": MAX_TRAIL_ROWS,
        "events": [
            {
                "id": getattr(row, "id", None),
                "routeTemplate": getattr(row, "routeTemplate", None),
                "method": getattr(row, "method", None),
                "statusCode": getattr(row, "statusCode", None),
                "durationMs": getattr(row, "durationMs", None),
                "clientApp": getattr(row, "clientApp", None),
                "consentState": getattr(row, "consentState", None),
                "at": _iso_or_none(getattr(row, "createdAt", None)),
            }
            for row in rows
        ],
    }
