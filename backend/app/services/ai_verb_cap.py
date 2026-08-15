"""The per-designer daily ceiling on AI-verb runs — a second money control, not a second rate limit.

**WHY THIS IS NOT ``dictation_cap``, WHICH IS THE OBVIOUS-LOOKING HOME AND IS THE WRONG ONE.** Plan
``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §6 answer 1 settled that ceiling's scope on 2026-08-12
in the user's own words: it *"should only apply to the global /dictate when it is utilizing the
ElevenLabs / Deepgram / Whisper API, and not the browser / internet one, or the one through
sherpa-onnx or from the local SLM."* That decision has three consequences here and each one on its
own rules out reusing the counter:

1. **THE SENTENCE WOULD BECOME FALSE.** ``dictation_cap.cap_refusal`` says "you have used all N of
   today's dictations **that go to the transcription service**", and the plan's own note explains
   that the wording is part of the decision because the shorthand "a daily dictation cap" describes
   a limit on dictation, which is not what it is. Counting a caption there would make that sentence
   wrong for a designer who has not dictated all day.
2. **DESK WORK WOULD TAKE AWAY A COURTYARD CONTROL.** An afternoon spent captioning photographs at a
   desk would exhaust the allowance that the dictation control falls back from, and a designer
   standing in front of an artisan would be told to type — because of work done by the same person
   somewhere else, hours earlier, on something unrelated.
3. **THE UNITS ARE DIFFERENT BILLS.** A dictation is priced per second of audio and a proofread per
   token of text; one number cannot bound both, and the number an operator picks for one is
   meaningless for the other.

So: a second counter, a second setting (``AppSetting.dwAiVerbDailyCap``), and one shared day
boundary. **The day is ``dictation_cap.ist_day`` and is not re-derived here**, which is the single
most important line in this module: two definitions of "daily" is how a designer ends up with two
allowances or with none, and that function is already the only place in the backend that decides
which day a spend belongs to.

Everything else — why a Postgres row and not a counter in memory or in Redis, why not
``app/scale/rate_limit.py``, why the boundary is midnight IST and not UTC, why the handset's clock is
never consulted — is argued in full in ``services/dictation_cap.py`` and is deliberately not repeated
here. This module is that module's decisions applied to a different meter.

**WHAT IS COUNTED: one increment per run that actually reached a provider.** Not per successful run.
A body refused before the call never reached one; a 503 for a missing key is the server's own
misconfiguration and the one refusal a designer can do nothing whatever about, so spending their
allowance on it would let a deployment with no key silently exhaust every designer's day. Everything
that did reach a provider counts, INCLUDING a failure — the credit is spent by the call, and counting
only successes leaves the ceiling uncapped for exactly the failure mode that produces the most
retries.

**ONE CEILING ACROSS ALL FIVE VERBS, BROKEN DOWN BY VERB IN THE TABLE.** The ceiling is shared
because the bill is shared. The breakdown is stored because the first question anybody asks when a
cap is reached is which verb spent it, and a single counter can never answer that — and because these
rows are the only measurement of what these verbs actually cost that this system will ever have,
which is the same argument ``dictation_cap.spend`` makes for counting while uncapped.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from app.core.db import db
from app.services.dictation_cap import DAY_BOUNDARY_PHRASE, ist_day

logger = logging.getLogger(__name__)

#: The setting column this ceiling reads. Named once so the refusal, the loader and any future
#: settings screen cannot spell it differently.
SETTING_COLUMN = "dwAiVerbDailyCap"


@dataclass(frozen=True, slots=True)
class VerbAllowance:
    """One designer's AI-verb allowance for one India-time day, and where it went.

    A VALUE AND NOT A DECISION SITE, exactly as ``dictation_cap.Allowance`` is: everything below is
    arithmetic over three facts read from the database, so the ceiling's behaviour is asserted by
    ``pytest`` with no Postgres and no event loop.
    """

    day: str
    #: None for uncapped, 0 for "refuse every run". Both are real settings; see :func:`configured_cap`.
    limit: int | None
    #: Runs that reached a provider on ``day``, across every verb.
    used: int
    #: ``{verb: count}`` — the breakdown, for the sentence and for whoever sizes the next cap.
    by_verb: tuple[tuple[str, int], ...] = ()

    @property
    def remaining(self) -> int | None:
        """How many are left, or None when there is no ceiling to count down from.

        None rather than a large number, because "no limit" and "a big limit" are different facts and
        only one can honestly be printed as a countdown. Never negative: a stored count above the
        limit — a cap lowered mid-day, or the bounded overshoot :func:`spend` records — reads as zero
        left rather than as a negative allowance nobody can interpret.
        """
        if self.limit is None:
            return None
        return max(0, self.limit - self.used)

    @property
    def spent(self) -> bool:
        """Whether the next run must be refused.

        ``>=`` and not ``==``, for ``dictation_cap.Allowance.spent``'s reason: a master admin who
        lowers the cap at noon must not hand every designer who is already over it an unbounded
        afternoon because no equality will ever hold again.
        """
        return self.limit is not None and self.used >= self.limit


def cap_refusal(allowance: VerbAllowance) -> str | None:
    """The sentence a designer reads when the ceiling is reached, or None when it is not.

    **FIELD COPY, NOT A LOG LINE**, and it names four things for ``dictation_cap.cap_refusal``'s
    stated reasons: the limit as a number, what still works, the next move that works NOW, and when
    the allowance returns with the boundary named.

    **IT SAYS WHICH CONTROL IS AFFECTED AND WHICH IS NOT**, which is the clause the dictation cap's
    own decision record insists on. A designer told "your daily AI allowance is used up" reasonably
    concludes that dictation has stopped working too — it has not, it is a different ceiling — and a
    designer who stops using a control that still works has lost something to a message rather than
    to a limit.

    THE ZERO CASE HAS ITS OWN SENTENCE. "You have used all 0" reads as a bug, and somebody who has
    run nothing all morning being told they have used up an allowance would reasonably conclude the
    app is broken and distrust the next message. A cap of 0 is a deliberate setting — these verbs
    turned off — and the honest sentence says that rather than blaming their usage.
    """
    if not allowance.spent:
        return None
    if allowance.limit == 0:
        return (
            "This server is not sending anything to the writing and captioning models at the moment "
            "— that daily allowance is set to none. Everything else still works, including "
            "dictation, which has its own separate allowance. Whoever administers the server can "
            "raise it in the settings; nothing on this device can."
        )
    breakdown = ", ".join(f"{count} × {verb.lower()}" for verb, count in allowance.by_verb)
    spent_on = f" Today's runs: {breakdown}." if breakdown else ""
    return (
        f"You have used all {allowance.limit} of today's runs of the writing and captioning models, "
        f"so this one cannot be done.{spent_on} Dictation has its own separate allowance and is "
        f"unaffected. Write the words yourself for now — your allowance starts again after "
        f"{DAY_BOUNDARY_PHRASE}."
    )


def allowance_payload(allowance: VerbAllowance) -> dict[str, Any]:
    """The allowance as every client reads it. camelCase on the wire.

    **CARRIED ON THE 201 AS WELL AS IN THE REFUSAL**, which is the whole reason this is not merely a
    429: a client that can learn the ceiling only by being refused has to spend a run to learn it, and
    then another one tomorrow. ``aiVerbDay`` is what makes a cached copy safe — it is the SERVER's
    India-time date, so a cached "spent" whose day no longer matches is stale rather than
    authoritative, exactly as ``dictationDay`` is one module over.

    Both numbers are null when there is no cap, because 0 remaining and "no ceiling" must not look
    alike.
    """
    return {
        "aiVerbsLimit": allowance.limit,
        "aiVerbsUsed": allowance.used,
        "aiVerbsRemaining": allowance.remaining,
        "aiVerbDay": allowance.day,
        "aiVerbsByVerb": dict(allowance.by_verb),
    }


def configured_cap(row: Any) -> int | None:
    """The ceiling from the settings singleton: None for uncapped, 0 for "refuse every run".

    **BOTH ARE REAL SETTINGS AND NEITHER IS A "NOT SET" SENTINEL**, and a reader who guesses will
    guess wrong in the expensive direction — which is why it is stated here, in the SQL and in
    schema.prisma rather than in one of the three.

    A MISSING SETTINGS ROW IS UNCAPPED, matching ``app_settings``' convention that a deployment whose
    singleton has never been written behaves as the defaults.

    A NEGATIVE STORED VALUE IS TREATED AS UNCAPPED, exactly as ``dictation_cap.configured_cap`` does
    and for its argued reason: the route refuses a negative, so one can only arrive by a hand edit;
    read as a ceiling it would clamp to 0 and silently withdraw a capability from the whole fleet with
    nothing anywhere naming a mistyped number as the cause, which is the failure nobody thinks to look
    for. Logged at WARNING either way so the bad row is findable rather than merely tolerated.
    """
    if row is None:
        return None
    value = getattr(row, SETTING_COLUMN, None)
    if value is None:
        return None
    try:
        cap = int(value)
    except (TypeError, ValueError):
        logger.warning(
            "ai_verb_cap: the configured daily cap %r is not a number; treating the server as "
            "uncapped",
            value,
        )
        return None
    if cap < 0:
        logger.warning(
            "ai_verb_cap: the configured daily cap is %d, which is not a ceiling anybody meant; "
            "treating the server as uncapped. Set it to 0 to switch these verbs off, or to a "
            "positive number to cap them.",
            cap,
        )
        return None
    return cap


def allowance_of(day: str, cap: int | None, rows: Any) -> VerbAllowance:
    """Assemble an allowance from the settings cap and the day's usage rows. Pure.

    Split out from :func:`load_allowance` so the arithmetic — including the breakdown's ordering and
    the total — is asserted without a database, which is the same split ``dictation_cap`` draws with
    its own pure ``Allowance``.

    The breakdown is ordered by count and then by name, so the sentence names the expensive verb
    first and two runs with identical counts do not shuffle between requests.
    """
    counts: list[tuple[str, int]] = []
    total = 0
    for row in rows or ():
        verb = str(getattr(row, "verb", "") or "").strip() or "UNRECORDED"
        try:
            count = int(getattr(row, "count", 0) or 0)
        except (TypeError, ValueError):
            # A meter reading nobody can parse is skipped rather than crashing a request a designer
            # is waiting on, and logged so it is findable. It is not counted as zero silently.
            logger.warning("ai_verb_cap: usage row for %s has an unreadable count", verb)
            continue
        counts.append((verb, count))
        total += count
    counts.sort(key=lambda pair: (-pair[1], pair[0]))
    return VerbAllowance(day=day, limit=cap, used=total, by_verb=tuple(counts))


# --------------------------------------------------------------------------------------
# The database half: one read and one increment. No rule is decided below this line.
# --------------------------------------------------------------------------------------


async def load_allowance(user_id: str, *, now: datetime | None = None) -> VerbAllowance:
    """This designer's verb allowance for the server's current India-time day.

    **THE COUNTER IS READ EVEN WHEN THE SERVER IS UNCAPPED**, and skipping it would be the first
    draft's mistake: with no ceiling there is nothing to compare against, but ``used`` would then
    have to be reported as 0, and ``aiVerbsUsed: 0`` on an uncapped deployment is an invented fact
    about a designer who has run thirty-seven. This repository's rule is that an unmeasured thing is
    called unmeasured, and there is no way to say that in an integer.

    Two reads, both by index: the settings singleton and this designer's rows for today (at most five
    of them, one per verb).
    """
    day = ist_day(now)
    settings_row = await _settings_row()
    return allowance_of(day, configured_cap(settings_row), await _usage_today(user_id, day))


async def _settings_row() -> Any | None:
    """The settings singleton, or None. Never raises into a verb's request path.

    ``app_settings.load_app_settings`` already swallows its own failures for this reason and is used
    rather than a fresh query, so this cap reads the same row through the same door as the dictation
    cap beside it.
    """
    from app.services.app_settings import load_app_settings

    return await load_app_settings()


async def _usage_today(user_id: str, day: str) -> list[Any]:
    return await db.dwaiverbdailyusage.find_many(where={"userId": user_id, "istDay": day})


async def spend(user_id: str, day: str, verb: str) -> None:
    """Record one run that reached a provider.

    **CALLED AFTER THE PROVIDER CALL AND NEVER BEFORE**, so the two things that never reached a
    provider never consume an allowance: a body refused before the call, and the 503 that means no
    key is configured. This function is not the gate and must never be moved above the call it counts.

    THE ARITHMETIC IS POSTGRES', not this process's: ``{"increment": 1}`` adds one inside the UPDATE,
    so two overlapping runs from one designer cannot both read 7 and both write 8. The two gaps that
    leaves are the same two ``dictation_cap.spend`` documents and are left open for its stated
    reasons — the CREATE branch of an upsert is a read-then-insert, and the route's read-then-check
    permits a bounded overshoot that ``spent``'s ``>=`` still refuses afterwards. A guarded increment
    would have to reserve the credit BEFORE the provider call and refund it on every refusal, and a
    process that dies between the reservation and the refund charges a designer for a run they never
    got.

    A FAILURE HERE IS LOGGED AND SWALLOWED, deliberately, and it is the one place in this module that
    fails open. The words are already produced and the layer is already written; handing a designer
    "something went wrong on the server" for a layer they can see would cost them the result AND the
    retry, and the retry would spend the credit again.
    """
    try:
        await db.dwaiverbdailyusage.upsert(
            where={
                "userId_istDay_verb": {"userId": user_id, "istDay": day, "verb": verb}
            },
            data={
                "create": {"userId": user_id, "istDay": day, "verb": verb, "count": 1},
                "update": {"count": {"increment": 1}},
            },
        )
    except Exception as exc:  # noqa: BLE001 - see the docstring: the layer is already written
        logger.warning(
            "ai_verb_cap: could not record a %s run against %s for %s (%s); this one is not counted "
            "against their allowance",
            verb,
            user_id,
            day,
            exc,
        )
