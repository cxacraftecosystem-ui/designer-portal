import logging
import os
import uuid
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any
from zoneinfo import ZoneInfo

import pytest

from app.services import app_settings
from app.services.app_settings import (
    DEFAULT_TIMEZONE,
    is_valid_timezone,
    within_processing_window,
)


def _host_has_tz_database() -> bool:
    try:
        ZoneInfo(DEFAULT_TIMEZONE)
    except Exception:  # noqa: BLE001
        return False
    return True


HAS_TZDATA = _host_has_tz_database()



def test_the_zone_the_queue_falls_back_to_is_always_settable() -> None:
    assert is_valid_timezone(DEFAULT_TIMEZONE) is True


@pytest.mark.parametrize(
    "typed",
    [
        "IST",
        "India Standard Time",
        "Asia/Kolkatta",
        "",
        "   ",
        "../../etc/passwd",
    ],
)
def test_a_string_the_queue_cannot_use_is_refused(typed: str) -> None:
    if HAS_TZDATA and _resolvable(typed):
        pytest.skip(f"{typed!r} is a real zone on this host, so it is legitimately accepted")
    assert is_valid_timezone(typed) is False


def test_a_name_that_only_resolves_after_stripping_is_refused() -> None:
    padded = f"{DEFAULT_TIMEZONE} "

    assert is_valid_timezone(DEFAULT_TIMEZONE) is True, "the bare name is the control"
    assert is_valid_timezone(padded) is False


def _resolvable(name: str) -> bool:
    try:
        ZoneInfo(name)
    except Exception:  # noqa: BLE001
        return False
    return True


@pytest.mark.skipif(not HAS_TZDATA, reason="host has no tz database; nothing but the default resolves")
def test_a_real_iana_zone_is_accepted_where_the_host_can_resolve_it() -> None:
    assert is_valid_timezone("UTC") is True
    assert is_valid_timezone("Europe/Paris") is True
    assert is_valid_timezone("America/New_York") is True


@pytest.mark.skipif(HAS_TZDATA, reason="this asserts the no-tz-database branch specifically")
def test_with_no_tz_database_only_the_fallback_zone_is_accepted() -> None:
    assert is_valid_timezone(DEFAULT_TIMEZONE) is True
    assert is_valid_timezone("UTC") is False



def _row(**overrides: Any) -> SimpleNamespace:
    row = SimpleNamespace(
        batchWindowEnabled=True,
        batchWindowStart="22:00",
        batchWindowEnd="05:00",
        batchTimezone=DEFAULT_TIMEZONE,
    )
    for field, value in overrides.items():
        setattr(row, field, value)
    return row


def test_falling_back_to_the_default_zone_is_reported_once_per_bad_name(caplog) -> None:
    app_settings._warned_timezones.clear()
    at = datetime(2026, 8, 15, 23, 0, tzinfo=UTC)

    with caplog.at_level(logging.WARNING, logger="app.services.app_settings"):
        first = within_processing_window(_row(batchTimezone="IST"), at)
        second = within_processing_window(_row(batchTimezone="IST"), at)

    assert first is True and second is True, "the fallback must still answer the queue's question"
    warnings = [r for r in caplog.records if "batchTimezone" in r.getMessage()]
    assert len(warnings) == 1, "one line per distinct bad name, not one per job"
    assert "'IST'" in warnings[0].getMessage()
    assert DEFAULT_TIMEZONE in warnings[0].getMessage()


def test_the_default_zone_never_warns_even_where_zoneinfo_cannot_build_it(caplog) -> None:
    app_settings._warned_timezones.clear()
    at = datetime(2026, 8, 15, 23, 0, tzinfo=UTC)

    with caplog.at_level(logging.WARNING, logger="app.services.app_settings"):
        inside = within_processing_window(_row(), at)

    assert inside is True, "the default zone must still evaluate the window correctly"
    assert [r for r in caplog.records if "batchTimezone" in r.getMessage()] == []

    with caplog.at_level(logging.WARNING, logger="app.services.app_settings"):
        within_processing_window(_row(batchTimezone="IST"), at)

    assert [r for r in caplog.records if "batchTimezone" in r.getMessage()], (
        "silencing the default must not have silenced the names the line exists for"
    )


_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

route = pytest.mark.skipif(
    not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def hub():
    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import hash_password
    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        admin = await db.user.create(data={
            "email": f"settings-tz-{stamp}@example.org",
            "name": "Settings Master Admin",
            "role": "MASTER_ADMIN",
            "passwordHash": hash_password("batch-timezone-test-password"),
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "admin": admin}


def _auth(hub: dict[str, Any]) -> dict[str, str]:
    from app.core.security import create_access_token

    return {"Authorization": f"Bearer {create_access_token(subject=hub['admin'].id)}"}


@route
@pytest.mark.anyio
async def test_an_unusable_timezone_is_refused_and_nothing_is_written(hub) -> None:
    client = hub["client"]
    before = client.get("/api/settings", headers=_auth(hub))
    assert before.status_code == 200, before.text
    stored = before.json()["batchTimezone"]

    refused = client.put("/api/settings", json={"batchTimezone": "IST"}, headers=_auth(hub))

    assert refused.status_code == 422, refused.text
    detail = refused.json()["detail"]
    assert "'IST'" in detail and "time zone" in detail, "the answer must name the bad value"
    after = client.get("/api/settings", headers=_auth(hub))
    assert after.json()["batchTimezone"] == stored


@route
@pytest.mark.anyio
async def test_the_zone_already_stored_can_still_be_saved(hub) -> None:
    client = hub["client"]
    stored = client.get("/api/settings", headers=_auth(hub)).json()["batchTimezone"]

    saved = client.put("/api/settings", json={"batchTimezone": stored}, headers=_auth(hub))

    assert saved.status_code == 200, saved.text
    assert saved.json()["batchTimezone"] == stored
