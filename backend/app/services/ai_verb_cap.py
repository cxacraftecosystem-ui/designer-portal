from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from app.core.db import db
from app.services.dictation_cap import DAY_BOUNDARY_PHRASE, ist_day

logger = logging.getLogger(__name__)

SETTING_COLUMN = "dwAiVerbDailyCap"


@dataclass(frozen=True, slots=True)
class VerbAllowance:
    day: str
    limit: int | None
    used: int
    by_verb: tuple[tuple[str, int], ...] = ()

    @property
    def remaining(self) -> int | None:
        if self.limit is None:
            return None
        return max(0, self.limit - self.used)

    @property
    def spent(self) -> bool:
        return self.limit is not None and self.used >= self.limit


def cap_refusal(allowance: VerbAllowance) -> str | None:
    if not allowance.spent:
        return None
    if allowance.limit == 0:
        return (
            "Writing and captioning help is switched off — its daily allowance is set to none. "
            "Everything else still works, including dictation, which has its own separate "
            "allowance. An administrator can raise it in Settings."
        )
    breakdown = ", ".join(f"{count} × {verb.lower()}" for verb, count in allowance.by_verb)
    spent_on = f" Today's runs: {breakdown}." if breakdown else ""
    return (
        f"You have used all {allowance.limit} of today's runs of the writing and captioning models, "
        f"so this one cannot be done.{spent_on} Dictation has its own separate allowance and is "
        f"unaffected. Write the words yourself — your allowance starts again after "
        f"{DAY_BOUNDARY_PHRASE}."
    )


def allowance_payload(allowance: VerbAllowance) -> dict[str, Any]:
    return {
        "aiVerbsLimit": allowance.limit,
        "aiVerbsUsed": allowance.used,
        "aiVerbsRemaining": allowance.remaining,
        "aiVerbDay": allowance.day,
        "aiVerbsByVerb": dict(allowance.by_verb),
    }


def configured_cap(row: Any) -> int | None:
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
    counts: list[tuple[str, int]] = []
    total = 0
    for row in rows or ():
        verb = str(getattr(row, "verb", "") or "").strip() or "UNRECORDED"
        try:
            count = int(getattr(row, "count", 0) or 0)
        except (TypeError, ValueError):
            logger.warning("ai_verb_cap: usage row for %s has an unreadable count", verb)
            continue
        counts.append((verb, count))
        total += count
    counts.sort(key=lambda pair: (-pair[1], pair[0]))
    return VerbAllowance(day=day, limit=cap, used=total, by_verb=tuple(counts))



async def load_allowance(user_id: str, *, now: datetime | None = None) -> VerbAllowance:
    day = ist_day(now)
    settings_row = await _settings_row()
    return allowance_of(day, configured_cap(settings_row), await _usage_today(user_id, day))


async def _settings_row() -> Any | None:
    from app.services.app_settings import load_app_settings

    return await load_app_settings()


async def _usage_today(user_id: str, day: str) -> list[Any]:
    return await db.dwaiverbdailyusage.find_many(where={"userId": user_id, "istDay": day})


async def spend(user_id: str, day: str, verb: str) -> None:
    try:
        await db.dwaiverbdailyusage.upsert(
            where={"userId_istDay_verb": {"userId": user_id, "istDay": day, "verb": verb}},
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
