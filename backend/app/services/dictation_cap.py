"""The per-designer daily ceiling on server dictations, counted where it cannot be lied about.

Plan ``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §6 answer 1, in the user's own words: *"Cost
ceiling for Tier 3 dictation. ANSWERED: a per-designer daily cap, named in words when it is hit. The
cap is configurable, counted per designer per local day, and when it is reached the control says so
and falls back to rung 3 and then rung 4 — it does not fail silently."* The rejected alternative was
"ship uncapped and measure", whose measurement is the first bill.

================================================================================================
AUTHORITATIVE ON THE SERVER. THE HANDSET'S COPY IS NOT A CONTROL.
================================================================================================

**A client-side counter is a client-side counter.** Process memory clears on a swipe-away, and a spend
ceiling defeated by a swipe-away is the one direction that costs money rather than a retry. So the
count that decides anything is the ``DwDictationDailyUsage`` row, and the handset's mirror exists for
exactly one purpose: so the dictation control can drop the server rung and fall back to the phone's
own recogniser **before** spending a six-megabyte upload to be told the same thing. Which is why
:func:`allowance_payload` is on the 200 path as well as in the refusal — a phone that can only learn
the limit by being refused has to spend an upload to learn it, which is the failure
``DwDictationUpload.kt`` already names for the 503: *"a six-megabyte upload per field, each one
spending mobile data to be told the same thing."*

**WHY A POSTGRES ROW AND NOT A COUNTER IN MEMORY.** One uvicorn worker per pod is deliberate, but prod
runs two replicas — so an in-process counter would give every designer TWICE the cap in production and
once in staging. A cap wrong by the replica count is worse than no cap, because the number on the
settings screen would be a lie.

**AND NOT REDIS**, which is the obvious implementation (``INCR`` on a day key with an ``EXPIRE``) and
is not available: ``scale_cache_backend`` defaults to ``"memory"`` and the compose Redis is
profile-gated, so on a default deployment that key would be incremented in one process's dictionary
and forgotten on the next deploy. Postgres is the only always-present store. Redis may later sit IN
FRONT of this table as an optimisation; it may never replace it.

**AND NOT ``app/scale/rate_limit.py``**, which looks like the home for this and is disqualified four
times over: it is flag-gated **off** by default; it keys on a SHA-256 digest of the bearer token or
the client IP and never on a user id, so one designer's phone and laptop are two buckets and its own
docstring's claim to the contrary is not what the code does; it is in-process unless Redis is
configured; and it describes itself as *"a courtesy backstop … NOT a security control"*. A spend
ceiling that is silently off in production is not a ceiling.

**THERE WAS NOTHING TO REUSE, AND SAYING SO IS PART OF THE DESIGN.** No per-user, per-day or otherwise
time-bucketed counter exists anywhere in the schema, and the dictation route stores nothing at all
("The text is returned and nothing is stored"). This is new storage, deliberately, rather than a
second mechanism bolted beside an existing one.

================================================================================================
THE DAY BOUNDARY, WHICH IS THE WHOLE MEANING OF "DAILY"
================================================================================================

"Daily" says nothing without a boundary, and the boundary is **midnight India Standard Time**, defined
by :func:`ist_day` and nowhere else. A UTC day would reset a designer's allowance at 05:30 IST —
mid-morning to this fleet — which ``api/routes/public.py`` calls *"visibly wrong to the people it is
for"* about the same choice for the census, and the corroboration it cites holds here too: every
record schema in this repository already defaults ``recordedTimezone`` to ``"Asia/Kolkata"``.

**THE HANDSET'S OWN TIMEZONE IS DELIBERATELY NOT CONSULTED.** Two phones with different clocks would
otherwise hold two different allowances against one server row, and a designer who crossed a boundary
in a phone's settings would get a second allowance. The day is the server's, and the refusal says so
in words so that a designer refused at 21:00 knows when it lifts.

================================================================================================
WHAT IS COUNTED, AND THE TWO THINGS THAT ARE DELIBERATELY NOT
================================================================================================

One increment per upload that **actually reached a provider**. Not per successful transcription:

* a **clip refused for size** never reached a provider and must not consume an allowance — the
  designer's next move is a shorter recording, and charging them for the refusal makes the ceiling
  arrive early for a reason they cannot see;
* a **503 because no provider is configured** is the server's own misconfiguration, and it is the one
  refusal a designer can do nothing whatever about. Spending their allowance on it would mean a
  deployment with no API key silently exhausting every designer's day.

Everything else counts, **including an empty or failed transcription**, and that is not an oversight:
the credit is spent by the provider call, and counting only successes would leave the ceiling uncapped
for exactly the failure mode that produces the most retries.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

from app.core.db import db

logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------------------
# The day
# --------------------------------------------------------------------------------------

#: India Standard Time as a fixed offset, copied from ``api/routes/public.py`` including its argument:
#: India has observed one offset with no daylight saving since 1945, and ``zoneinfo`` needs a system tz
#: database that a Windows development box does not have.
#:
#: The alternative already in this repository — ``ZoneInfo(batchTimezone)`` in
#: ``services/app_settings.py`` — carries a documented three-deep fallback ending in a hardcoded
#: ``+05:30`` for *"no tz database at all (Windows without tzdata)"*. That chain is acceptable in a
#: background scheduler and is not acceptable in the request path of a dictation a designer is standing
#: there waiting on: the failure it degrades through would decide which DAY somebody's allowance
#: belongs to.
_IST = timezone(timedelta(hours=5, minutes=30))

#: The name of the boundary, in the words the refusal uses, so the sentence and the arithmetic cannot
#: drift apart.
DAY_BOUNDARY_PHRASE = "midnight India time"


def ist_day(now: datetime | None = None) -> str:
    """The server's India-time calendar date as ``"YYYY-MM-DD"``. **The day boundary is defined here.**

    This function is the only place in the backend that decides which day a dictation belongs to, and
    the string it returns is the second half of ``DwDictationDailyUsage``'s primary key. A caller that
    computed its own date — even correctly — would be a second definition of the boundary, and the
    first day the two disagreed a designer would have two allowances or none.

    ``now`` is accepted so the boundary itself is testable without waiting for midnight; it is
    normalised to UTC when it arrives naive, because a naive datetime shifted by ``+05:30`` is only
    right if it happened to be UTC and the mistake would be invisible for most of the day.
    """
    moment = now or datetime.now(UTC)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment.astimezone(_IST).date().isoformat()


# --------------------------------------------------------------------------------------
# The allowance: a pure value the route and the tests both reason about
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Allowance:
    """One designer's server-dictation allowance for one India-time day.

    A VALUE AND NOT A DECISION SITE: everything below is derived arithmetic over three facts read from
    the database, so the ceiling's behaviour is asserted by ``pytest`` with no Postgres and no event
    loop. ``limit`` of None is *uncapped*, which is the configured default and today's behaviour.
    """

    #: The India-time date these numbers are about, as :func:`ist_day` spells it.
    day: str
    #: The configured ceiling: None for uncapped, 0 for "refuse every one". See :func:`configured_cap`.
    limit: int | None
    #: Uploads that reached a provider on ``day``, as the database has it.
    used: int

    @property
    def uncapped(self) -> bool:
        return self.limit is None

    @property
    def remaining(self) -> int | None:
        """How many are left, or None when there is no ceiling to count down from.

        None rather than a large number, because "no limit" and "a big limit" are different facts and
        only one of them can honestly be printed as a countdown. Never negative: a stored count above
        the limit — a cap lowered mid-day, or the bounded overshoot :func:`spend` records — reads as
        zero left rather than as a negative allowance nobody can interpret.
        """
        if self.limit is None:
            return None
        return max(0, self.limit - self.used)

    @property
    def spent(self) -> bool:
        """Whether the next upload must be refused.

        ``>=`` and not ``==``, which is the whole reason this is a property rather than a comparison
        written at the call site: a master admin who lowers the cap from 40 to 10 at noon must not
        hand every designer who has already spent 12 an unbounded afternoon because no equality will
        ever hold again.
        """
        return self.limit is not None and self.used >= self.limit


def cap_refusal(allowance: Allowance) -> str | None:
    """The sentence a designer reads when the ceiling is reached, or None when it is not.

    **THIS IS FIELD COPY, NOT A LOG LINE.** The Android control shows the server's own detail string
    verbatim for every HTTP answer that is not the route's own 503, so this text is read by somebody
    standing in a courtyard with an artisan waiting. It therefore names three things:

    * **the limit, as a number**, because plan §6.1 requires the cap be "named in words when it is
      hit" and a refusal that will not say how many is one a designer cannot plan around;
    * **the next move that works now** — type it in — rather than "try again", which is incapable of a
      different outcome today;
    * **when the allowance returns, with the boundary named**, because a designer refused at 21:00
      otherwise has no idea whether that means in three hours or in eleven. It is the SERVER's India
      day and not the phone's, and the sentence says India time so a phone whose clock disagrees does
      not make the sentence wrong.

    THE ZERO CASE HAS ITS OWN SENTENCE, and it is not politeness. "You have used all 0 of today's
    dictations" is prose that reads as a bug, and a designer who has recorded nothing all morning being
    told they have used up their allowance would reasonably conclude the app is broken and stop
    trusting the next message. A cap of 0 is a deliberate setting — server dictation turned off — and
    the honest sentence says that rather than blaming their usage.
    """
    if not allowance.spent:
        return None
    if allowance.limit == 0:
        return (
            "This server is not sending any dictation to the transcription service at the moment — "
            "the daily allowance is set to none. Type the words in instead. Whoever administers the "
            "server can raise the allowance in the settings; nothing on this phone can."
        )
    return (
        f"You have used all {allowance.limit} of today's dictations that go to the transcription "
        f"service, so this one cannot be written down there. Type the words in instead — your "
        f"allowance starts again after {DAY_BOUNDARY_PHRASE}."
    )


def allowance_payload(allowance: Allowance) -> dict[str, Any]:
    """The allowance as every client reads it. camelCase on the wire.

    **CARRIED ON THE 200 AS WELL AS IN THE REFUSAL, and that is the whole reason this is not just a
    429.** A phone that can only learn the ceiling by being refused has to spend a six-megabyte upload
    to learn it, and then another one tomorrow. With these four keys on every successful dictation the
    handset knows, from the last one it did, whether the next one is worth attempting — and can drop
    the server rung and use the phone's own recogniser with **zero bytes uploaded**.

    ``dictationDay`` is what makes the mirror safe to cache: the phone stores the day the server
    named, and a cached "spent" whose day no longer matches is stale rather than authoritative. Both
    numbers are null when there is no cap, because 0 remaining and "no ceiling" must not look alike.
    """
    return {
        "dictationsLimit": allowance.limit,
        "dictationsUsed": allowance.used,
        "dictationsRemaining": allowance.remaining,
        "dictationDay": allowance.day,
    }


def configured_cap(row: Any) -> int | None:
    """The ceiling from the settings singleton: None for uncapped, 0 for "refuse every one".

    **BOTH ARE REAL SETTINGS AND NEITHER IS A "NOT SET" SENTINEL.** NULL is uncapped, which is the
    column's default and today's behaviour, so the migration changes nothing on deploy. 0 turns server
    dictation off and :func:`cap_refusal` has its own sentence for it. Guessing between them is exactly
    what this function exists to stop each caller doing.

    A MISSING SETTINGS ROW IS UNCAPPED, matching ``app_settings``' own convention that a deployment
    whose singleton has never been written behaves as the defaults.

    **A NEGATIVE STORED VALUE IS TREATED AS UNCAPPED, and the choice is argued rather than obvious.**
    The route refuses one, so a negative can only arrive by somebody editing the row by hand. Read as a
    ceiling it would clamp to 0 and silently withdraw craft-aware dictation from every designer in the
    fleet — a capability loss with no message anywhere naming a mistyped number as the cause, which is
    the failure nobody would think to look for. Read as uncapped it restores the behaviour the column's
    own NULL means, which is what the deployment had before anybody typed anything. Logged at WARNING
    either way so the bad row is findable rather than merely tolerated.
    """
    if row is None:
        return None
    value = getattr(row, "dwDictationDailyCap", None)
    if value is None:
        return None
    try:
        cap = int(value)
    except (TypeError, ValueError):
        logger.warning(
            "dictation_cap: the configured daily cap %r is not a number; treating the server as "
            "uncapped",
            value,
        )
        return None
    if cap < 0:
        logger.warning(
            "dictation_cap: the configured daily cap is %d, which is not a ceiling anybody meant; "
            "treating the server as uncapped. Set it to 0 to switch server dictation off, or to a "
            "positive number to cap it.",
            cap,
        )
        return None
    return cap


# --------------------------------------------------------------------------------------
# The database half: two reads and one increment
#
# Everything above this line is pure and is what tests/test_dictation_consent_and_cap.py exercises.
# Everything below reads or writes rows. No rule is decided here.
# --------------------------------------------------------------------------------------


async def load_allowance(user_id: str, *, now: datetime | None = None) -> Allowance:
    """This designer's allowance for the server's current India-time day.

    Two reads, and both are by primary key — the settings singleton and the (userId, istDay) row — so
    this costs the dictation request two index lookups and no scan. They are awaited in sequence rather
    than gathered because at this size the round trips are not what the designer is waiting on, and a
    ``gather`` here would be the kind of concurrency that reads as optimisation and is noise.

    **THE COUNTER IS READ EVEN WHEN THE SERVER IS UNCAPPED, and skipping it was the first draft.** With
    no ceiling there is nothing to compare against, so the read looks like a query for nothing — but
    then ``used`` would have to be reported as 0, and ``dictationsUsed: 0`` on an uncapped deployment is
    an invented fact about a designer who has spent thirty-seven. This repository's rule is that an
    unmeasured thing is called unmeasured, and there is no way to say that in an integer. One primary-key
    lookup on a request that is about to spend several seconds inside a provider is not the cost worth
    saving.

    A day with no row yet is ``used=0`` rather than an error, and that 0 IS honest: the first dictation
    of the day is the ordinary case, and the row is created by :func:`spend` when there is something to
    count.
    """
    day = ist_day(now)
    settings_row = await _settings_row()
    return Allowance(
        day=day, limit=configured_cap(settings_row), used=await _used_today(user_id, day)
    )


async def _settings_row() -> Any | None:
    """The settings singleton, or None. Never raises into the dictation path.

    ``app_settings.load_app_settings`` already swallows its own failures for this reason and is used
    rather than a fresh query, so the cap reads the same row through the same door as the transcription
    mode beside it.
    """
    from app.services.app_settings import load_app_settings

    return await load_app_settings()


async def _used_today(user_id: str, day: str) -> int:
    row = await db.dwdictationdailyusage.find_unique(
        where={"userId_istDay": {"userId": user_id, "istDay": day}}
    )
    return int(getattr(row, "count", 0) or 0)


async def spend(user_id: str, day: str) -> int | None:
    """Record one upload that reached a provider, and return the day's new total, or None if it could
    not be recorded.

    **CALLED AFTER THE PROVIDER CALL AND NOT BEFORE**, so the two things that never reached a provider
    never consume an allowance: a clip refused for size, and the 503 that means no provider is
    configured. The module docstring says why each of those matters; what matters here is that this
    function is not the gate and must never be moved above the call it counts.

    **IT COUNTS WHILE THE SERVER IS UNCAPPED, TOO.** That looks like work for nothing and is not: the
    day a master admin first types a number, the designers who have been dictating all morning must
    already have that morning against their name. Starting every counter at zero at the moment a cap is
    configured would hand the whole fleet a fresh allowance on the afternoon somebody reacted to a
    bill. It is also what makes the first number choosable from evidence — the rows accumulated while
    uncapped are the only measurement of what a designer actually spends in a day that this system will
    ever have.

    THE ARITHMETIC IS POSTGRES', not this process's: ``{"increment": 1}`` adds one to the stored value
    inside the UPDATE statement, so two overlapping dictations from one designer cannot both read 7 and
    both write 8. Two things that closes and one it does not, all three stated rather than papered over:

    * **The CREATE branch is not atomic.** An ``upsert`` is a read followed by an insert, so the first
      two dictations of a day arriving together can both find no row and both try to create one; the
      loser hits the primary key, its exception is swallowed below, and that day's count is one short.
      One increment, once per designer per day, on a shape that needs two microphones in the same
      second to happen at all.
    * **The read-then-check in the route is not closed either.** The ceiling is compared before the
      provider call, so N requests overlapping inside one round trip can each pass a check that only
      N-1 of them should have. The overshoot is bounded by that concurrency and self-corrects on the
      next request — a phone has one microphone and dictation is synchronous, so what reaches this is a
      burst of parallel taps rather than a sustained bypass. ``Allowance.spent`` uses ``>=``, so an
      overshot count still refuses.
    * **WHY IT IS NOT CLOSED WITH A GUARDED INCREMENT, which this client CAN express** — an earlier
      draft of this docstring claimed it could not, and that was false: ``media_queue._lock_job`` does
      exactly this shape three files over, an ``update_many`` whose ``where`` carries the condition and
      whose returned row count decides the race. The reason is the ORDER, not the client. A guard of
      the form ``UPDATE … WHERE count < limit`` has to run BEFORE the provider call to be a ceiling,
      which means reserving the credit before anybody knows whether a provider was reached — and then
      refunding it on the over-size refusal, on the empty upload and on the 503. Plan §6.1 is explicit
      that a request which never reached a paid provider must not be counted, and a process that dies
      between the reservation and the refund charges a designer for a dictation they never got. A
      bounded overshoot costs the deployment a few provider calls; a lost refund costs a designer an
      allowance they can neither see nor recover.

    A FAILURE HERE IS LOGGED AND SWALLOWED, deliberately, and it is the one place in this module that
    fails open. The transcription has already happened and the words are already in the response; a
    designer must not be handed "something went wrong on the server" for text they can see, and losing
    one increment costs one upload of one designer's daily allowance. The alternative — a 500 after a
    successful provider call — would cost them the transcription AND the retry, and the retry would
    spend the credit again. None comes back rather than a number, so the response reports the count it
    can stand behind instead of an invented one.
    """
    try:
        row = await db.dwdictationdailyusage.upsert(
            where={"userId_istDay": {"userId": user_id, "istDay": day}},
            data={
                "create": {"userId": user_id, "istDay": day, "count": 1},
                "update": {"count": {"increment": 1}},
            },
        )
        return int(getattr(row, "count", 0) or 0)
    except Exception as exc:  # noqa: BLE001 - see the docstring: the transcript is already produced
        logger.warning(
            "dictation_cap: could not record a dictation against %s for %s (%s); this one is not "
            "counted against their allowance",
            user_id,
            day,
            exc,
        )
        return None
