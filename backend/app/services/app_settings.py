import logging
from collections.abc import Collection
from datetime import datetime, time, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

from app.core.db import db

logger = logging.getLogger(__name__)

SINGLETON_ID = "singleton"
DEFAULT_TRANSCRIPTION_MODE = "REFINED_TRANSLATED"
VALID_TRANSCRIPTION_MODES = {"RAW", "REFINED", "REFINED_TRANSLATED"}
DEFAULT_TIMEZONE = "Asia/Kolkata"

DEFAULT_STT_PROVIDER_ORDER = ("elevenlabs", "deepgram", "whisper", "muse")
LATE_STT_PROVIDERS = ("muse",)


async def load_app_settings() -> Any | None:
    try:
        return await db.appsetting.find_unique(where={"id": SINGLETON_ID})
    except Exception:  # noqa: BLE001 - settings must never break the request/worker path
        return None


async def get_or_create_app_settings(updated_by_id: str | None = None) -> Any:
    existing = await load_app_settings()
    if existing is not None:
        return existing
    return await db.appsetting.create(data={"id": SINGLETON_ID, "updatedById": updated_by_id})


def transcription_mode(row: Any | None) -> str:
    if row is None:
        return DEFAULT_TRANSCRIPTION_MODE
    mode = getattr(row, "transcriptionMode", None)
    return mode if mode in VALID_TRANSCRIPTION_MODES else DEFAULT_TRANSCRIPTION_MODE


def stt_provider_order(row: Any | None) -> list[str]:
    stored = list(getattr(row, "sttProviderOrder", None) or []) if row is not None else []
    ordered: list[str] = []
    for provider in stored:
        if provider in DEFAULT_STT_PROVIDER_ORDER and provider not in ordered:
            ordered.append(provider)
    ordered.extend(p for p in DEFAULT_STT_PROVIDER_ORDER if p not in ordered)
    return ordered


async def load_stt_provider_order() -> list[str]:
    return stt_provider_order(await load_app_settings())


async def save_stt_provider_order(order: list[str], updated_by_id: str | None = None) -> list[str]:
    await get_or_create_app_settings(updated_by_id)
    row = await db.appsetting.update(
        where={"id": SINGLETON_ID},
        data={"sttProviderOrder": {"set": order}, "updatedById": updated_by_id},
    )
    return stt_provider_order(row)


def invalid_stt_provider_order(value: list[str]) -> str | None:
    known = list(DEFAULT_STT_PROVIDER_ORDER)
    unknown = [p for p in value if p not in DEFAULT_STT_PROVIDER_ORDER]
    if unknown:
        return f"lists unknown providers {unknown}; expected any of {known}"
    repeated = sorted({p for p in value if value.count(p) > 1})
    if repeated:
        return f"repeats {repeated}; each provider may appear once"
    missing = [p for p in known if p not in value and p not in LATE_STT_PROVIDERS]
    if missing:
        return f"is missing {missing}; it must rank every provider, not just the preferred ones"
    return None


STT_KEY_ABSENT = "NO_KEY"
STT_KEY_UNTESTED = "UNTESTED"
STT_KEY_FAILING = "FAILING"
STT_KEY_PASSING = "PASSING"


def freeze_unrankable(order: list[str], rankable: Collection[str]) -> list[str]:
    proven = [provider for provider in order if provider in rankable]
    frozen = [provider for provider in order if provider not in rankable]
    return proven + frozen


def order_violations(order: list[str], rankable: Collection[str]) -> int:
    seen_frozen = 0
    total = 0
    for provider in order:
        if provider in rankable:
            total += seen_frozen
        else:
            seen_frozen += 1
    return total


_recalled_verdicts: dict[str, dict[str, Any]] = {}


def remember_key_verdict(
    key_name: str, *, passing: bool, checked_at: datetime, error: str | None
) -> None:
    _recalled_verdicts[key_name] = {
        "status": STT_KEY_PASSING if passing else STT_KEY_FAILING,
        "checkedAt": checked_at,
        "error": None if passing else error,
    }


def recalled_key_verdict(key_name: str) -> dict[str, Any] | None:
    return _recalled_verdicts.get(key_name)


def forget_key_verdicts() -> None:
    _recalled_verdicts.clear()


def parse_hhmm(value: str | None, fallback: time) -> time:
    if not value:
        return fallback
    try:
        hours, minutes = str(value).split(":")
        return time(int(hours) % 24, int(minutes) % 60)
    except (ValueError, AttributeError):
        return fallback


def is_valid_hhmm(value: str | None) -> bool:
    if not value:
        return False
    try:
        hours, minutes = str(value).split(":")
        return 0 <= int(hours) <= 23 and 0 <= int(minutes) <= 59
    except (ValueError, AttributeError):
        return False


def is_valid_timezone(value: str | None) -> bool:
    if not value:
        return False
    name = str(value)
    try:
        ZoneInfo(name)
        return True
    except Exception:  # noqa: BLE001 - unknown zone, malformed name, or no tz database at all
        try:
            ZoneInfo(DEFAULT_TIMEZONE)
        except Exception:  # noqa: BLE001 - no tz database: nothing but the fallback is knowable
            return name == DEFAULT_TIMEZONE
        return False


_warned_timezones: set[str] = set()


def _tz_or_fallback(tz_name: str) -> Any:
    try:
        return ZoneInfo(tz_name)
    except Exception:  # noqa: BLE001 - bad tz string must not stall the queue
        if tz_name != DEFAULT_TIMEZONE and tz_name not in _warned_timezones:
            _warned_timezones.add(tz_name)
            logger.warning(
                "batchTimezone %r is not a usable timezone on this host; the off-peak window is "
                "being evaluated in %s instead. Re-save the setting to fix it.",
                tz_name,
                DEFAULT_TIMEZONE,
            )
        try:
            return ZoneInfo(DEFAULT_TIMEZONE)
        except Exception:  # noqa: BLE001 - no tz database at all (Windows without tzdata)
            return timezone(timedelta(hours=5, minutes=30), name="IST")


def within_processing_window(row: Any | None, now: datetime | None = None) -> bool:
    if row is None or not getattr(row, "batchWindowEnabled", False):
        return True
    tz_name = getattr(row, "batchTimezone", None) or DEFAULT_TIMEZONE
    tz = _tz_or_fallback(tz_name)
    current = (now or datetime.now(tz)).astimezone(tz).time()
    start = parse_hhmm(getattr(row, "batchWindowStart", None), time(2, 0))
    end = parse_hhmm(getattr(row, "batchWindowEnd", None), time(5, 0))
    if start == end:
        return True
    if start < end:
        return start <= current < end
    return current >= start or current < end



def transcription_paused(row: Any | None) -> bool:
    from app.transcription import schedule

    return schedule.is_paused(row)


def stt_providers_disabled(row: Any | None) -> set[str]:
    from app.transcription.contract import DISABLED_BY_DEFAULT

    if row is None:
        return set(DISABLED_BY_DEFAULT)
    stored = getattr(row, "sttProvidersDisabled", None)
    return set(DISABLED_BY_DEFAULT if stored is None else stored)


async def load_transcription_paused() -> bool:
    return transcription_paused(await load_app_settings())


async def save_transcription_switches(
    *,
    paused: bool | None = None,
    disabled: list[str] | None = None,
    updated_by_id: str | None = None,
) -> Any:
    await get_or_create_app_settings(updated_by_id)
    data: dict[str, Any] = {"updatedById": updated_by_id}
    if paused is not None:
        data["transcriptionPaused"] = bool(paused)
    if disabled is not None:
        known = [p for p in DEFAULT_STT_PROVIDER_ORDER if p in set(disabled)]
        data["sttProvidersDisabled"] = {"set": known}
    return await db.appsetting.update(where={"id": SINGLETON_ID}, data=data)
