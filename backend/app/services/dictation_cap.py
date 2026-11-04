from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone
from typing import Any

from app.core.db import db

logger = logging.getLogger(__name__)


_IST = timezone(timedelta(hours=5, minutes=30))

DAY_BOUNDARY_PHRASE = "midnight India time"


def ist_day(now: datetime | None = None) -> str:
    moment = now or datetime.now(UTC)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment.astimezone(_IST).date().isoformat()



@dataclass(frozen=True, slots=True)
class Allowance:
    day: str
    limit: int | None
    used: int

    @property
    def uncapped(self) -> bool:
        return self.limit is None

    @property
    def remaining(self) -> int | None:
        if self.limit is None:
            return None
        return max(0, self.limit - self.used)

    @property
    def spent(self) -> bool:
        return self.limit is not None and self.used >= self.limit


def cap_refusal(allowance: Allowance) -> str | None:
    if not allowance.spent:
        return None
    if allowance.limit == 0:
        return (
            "Dictation isn't available right now — the daily allowance is set to none. Type the "
            "words in instead. An administrator can raise the allowance in Settings."
        )
    return (
        f"You have used all {allowance.limit} of today's dictations that go to the transcription "
        f"service, so this one cannot be written down there. Type the words in instead — your "
        f"allowance starts again after {DAY_BOUNDARY_PHRASE}."
    )


def allowance_payload(allowance: Allowance) -> dict[str, Any]:
    return {
        "dictationsLimit": allowance.limit,
        "dictationsUsed": allowance.used,
        "dictationsRemaining": allowance.remaining,
        "dictationDay": allowance.day,
    }


def configured_cap(row: Any) -> int | None:
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



async def load_allowance(user_id: str, *, now: datetime | None = None) -> Allowance:
    day = ist_day(now)
    settings_row = await _settings_row()
    return Allowance(
        day=day, limit=configured_cap(settings_row), used=await _used_today(user_id, day)
    )


async def _settings_row() -> Any | None:
    from app.services.app_settings import load_app_settings

    return await load_app_settings()


async def _used_today(user_id: str, day: str) -> int:
    row = await db.dwdictationdailyusage.find_unique(
        where={"userId_istDay": {"userId": user_id, "istDay": day}}
    )
    return int(getattr(row, "count", 0) or 0)


async def spend(user_id: str, day: str) -> int | None:
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
