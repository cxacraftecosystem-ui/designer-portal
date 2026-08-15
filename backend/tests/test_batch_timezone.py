"""The off-peak window's timezone: validated on the way in, and audible when it is not usable.

``batchTimezone`` was the one field on PUT /settings with no validation. Its siblings all had one —
``transcriptionMode`` against a set, both window times through ``is_valid_hhmm``, the provider order
through ``invalid_stt_provider_order`` — and the zone went straight to the column. The web offers it
as a free-text box with a placeholder rather than a picker, so "IST" or "India Standard Time" is a
perfectly natural thing for a master admin to type. The PUT answered 200, ``AppSettingDto`` echoed
the stored string back, the page redrew showing exactly what was typed, and
``within_processing_window`` quietly evaluated the window in Asia/Kolkata instead, with no log line
and no signal to any caller.

WHAT THAT COSTS is not a crash — the fallback chain is safe and terminates — it is a settings screen
that describes something other than what happens, on the one page whose whole job is to say when the
servers do their heavy work. On a deployment whose intended zone is not IST, the transcription
window runs at the wrong hours and competes with the daytime uploads it exists to protect.

TWO HALVES, TESTED SEPARATELY. The check refuses a value that cannot be used. The log line is for
the rows that predate the check (or arrive by hand): the fallback must stay — a bad string must
never stall the queue — but it must not be silent.

NOTE ON THIS HOST. ``ZoneInfo`` finds no tz database on this machine at all (no system zoneinfo, no
``tzdata`` package in backend/.venv): ``ZoneInfo("Asia/Kolkata")`` itself raises
``ZoneInfoNotFoundError``. That is precisely why ``is_valid_timezone`` has a second branch, and the
tests below detect the situation rather than assuming either way, so they are honest on a developer
laptop with a full tz database and on this one.
"""

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


# --- The check ---------------------------------------------------------------------------------


def test_the_zone_the_queue_falls_back_to_is_always_settable() -> None:
    """Whatever the host carries, the default must be storable — otherwise the fix would have made
    the field unsettable rather than validated, which is a worse defect than the one it closes."""
    assert is_valid_timezone(DEFAULT_TIMEZONE) is True


@pytest.mark.parametrize(
    "typed",
    [
        "IST",  # the abbreviation the placeholder practically invites
        "India Standard Time",  # the Windows display name
        "Asia/Kolkatta",  # a plausible misspelling of the default itself
        "",
        "   ",
        "../../etc/passwd",
    ],
)
def test_a_string_the_queue_cannot_use_is_refused(typed: str) -> None:
    """Each of these reached the column, was echoed back, and was then discarded by the queue.

    The rule is not "is this name famous" but "can THIS server build a ZoneInfo from it", because
    that is the question the queue asks at run time — and on a host with no tz database (this one)
    the answer for everything but the fallback is no. The skip keeps the test honest on a laptop
    with full tzdata, where one of these might legitimately resolve.
    """
    if HAS_TZDATA and _resolvable(typed):
        pytest.skip(f"{typed!r} is a real zone on this host, so it is legitimately accepted")
    assert is_valid_timezone(typed) is False


def test_a_name_that_only_resolves_after_stripping_is_refused() -> None:
    """The trailing space is the whole test, and it is not pedantry.

    An earlier draft of `is_valid_timezone` stripped before checking. `"Asia/Kolkata "` then passed
    validation on the stripped form, was written to the column WITH the space, and failed in
    `_tz_or_fallback`, which reads the column raw — an accepted, echoed-back setting the queue could
    not use, which is exactly the defect the validator exists to close. One string must be both
    judged and stored; if leniency is ever wanted, strip in the route before doing either.
    """
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
    """The branch that keeps the field usable on a host that can validate nothing. Accepting
    everything there would restore the original defect; accepting nothing would lock the field."""
    assert is_valid_timezone(DEFAULT_TIMEZONE) is True
    assert is_valid_timezone("UTC") is False


# --- The log line for a value that got in before the check existed --------------------------------


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
    """The queue must not stall on a bad string — that part is deliberate and stays — but a window
    being evaluated in a zone other than the one on the settings page has to be findable in the
    journal. Once per distinct name per process: this runs on every queued job, and a line per job
    would bury everything else in the log it was added to."""
    app_settings._warned_timezones.clear()
    # 23:00 UTC is 04:30 in India, inside a 22:00-05:00 window. India has no DST, so this holds
    # whether the fallback resolved to real Asia/Kolkata tzdata or to the fixed +05:30 zone.
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
    """A correctly-configured deployment must be silent — INCLUDING on a host with no tz database.

    THIS TEST CAUGHT A REAL MISFIRE. The first version of the warning logged unconditionally from
    the `except` arm, and on this machine (Windows, no system zoneinfo, no `tzdata` in
    backend/.venv) `ZoneInfo("Asia/Kolkata")` itself raises — so every single queued job announced
    that the deployment's own default "is not a usable timezone" and told the operator to re-save a
    setting that was already correct and whose re-saving is a no-op. The last-resort zone is a fixed
    +05:30 named IST, which for Asia/Kolkata is not an approximation but the same thing (one offset,
    no DST, since 1945), so there is genuinely nothing to report.

    The second half is the guard rail against the cheap way to make the first half pass: a name that
    really is wrong must still speak, on this same host, in this same process.
    """
    app_settings._warned_timezones.clear()
    at = datetime(2026, 8, 15, 23, 0, tzinfo=UTC)  # 04:30 IST, inside the 22:00-05:00 window

    with caplog.at_level(logging.WARNING, logger="app.services.app_settings"):
        inside = within_processing_window(_row(), at)

    assert inside is True, "the default zone must still evaluate the window correctly"
    assert [r for r in caplog.records if "batchTimezone" in r.getMessage()] == []

    with caplog.at_level(logging.WARNING, logger="app.services.app_settings"):
        within_processing_window(_row(batchTimezone="IST"), at)

    assert [r for r in caplog.records if "batchTimezone" in r.getMessage()], (
        "silencing the default must not have silenced the names the line exists for"
    )


# --- The route -------------------------------------------------------------------------------------
#
# Postgres is required for these two: the point is that the API refuses the write, and a refusal
# asserted against a mocked database is an assertion about the mock. The module skips itself when
# DATABASE_URL is not local, exactly as test_designer_roster does.

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
    """A master admin and the running app. The account is left behind on purpose: AppSetting's
    ``updatedById`` is a bare column with no foreign key, so a deleted user would leave the settings
    row pointing at nothing — and every other module here leaves its accounts too."""
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
    """Before the check this was a 200 whose response echoed "IST" back to the page while the queue
    kept using Asia/Kolkata. The stored value is asserted afterwards because a 422 that had already
    written the row would be the same defect wearing a different status code."""
    client = hub["client"]
    before = client.get("/api/settings", headers=_auth(hub))
    assert before.status_code == 200, before.text
    stored = before.json()["batchTimezone"]

    refused = client.put("/api/settings", json={"batchTimezone": "IST"}, headers=_auth(hub))

    assert refused.status_code == 422, refused.text
    detail = refused.json()["detail"]
    assert "batchTimezone" in detail and "'IST'" in detail, "the answer must name the bad value"
    after = client.get("/api/settings", headers=_auth(hub))
    assert after.json()["batchTimezone"] == stored


@route
@pytest.mark.anyio
async def test_the_zone_already_stored_can_still_be_saved(hub) -> None:
    """The guard rail against a check so strict it refuses the deployment's own configuration.

    It re-sends the value already on the row rather than choosing one, for two reasons: this is a
    single global singleton shared with every other test in the suite, so the test must not leave a
    different zone behind; and whatever is stored is by definition the zone this deployment means to
    use, which is exactly what must not be refused.
    """
    client = hub["client"]
    stored = client.get("/api/settings", headers=_auth(hub)).json()["batchTimezone"]

    saved = client.put("/api/settings", json={"batchTimezone": stored}, headers=_auth(hub))

    assert saved.status_code == 200, saved.text
    assert saved.json()["batchTimezone"] == stored
