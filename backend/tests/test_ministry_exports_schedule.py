"""THE MINISTRY PACKS' MONTHLY SCHEDULE: which month is owed when, and that it is queued exactly once.

The period rules are pure (``period_for``); the rest runs over the database: two passes of
``maybe_run`` queue the seven packs of the month before and no more, a second scheduler run in the
same month changes nothing, and a unique violation from a racing writer is swallowed.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db
from ministry_exports_support import FakeBucket, private, reset, settings_on

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.services import ministry_exports as packs
from app.services.mailer import IST


def _ist(*parts: int) -> datetime:
    return datetime(*parts, tzinfo=IST)


# ── pure ────────────────────────────────────────────────────────────────────────────────────────


def test_nothing_is_owed_before_the_run_hour_on_the_first() -> None:
    assert packs.period_for(_ist(2026, 10, 1, 5, 59), 6) is None
    assert packs.period_for(_ist(2026, 10, 1, 6, 0), 6) == "2026-09"


def test_january_owes_the_december_before() -> None:
    assert packs.period_for(_ist(2026, 1, 1, 6, 0), 6) == "2025-12"
    assert packs.previous_period(_ist(2026, 1, 15, 12, 0)) == "2025-12"


def test_a_worker_down_on_the_first_catches_up_on_the_third() -> None:
    assert packs.period_for(_ist(2026, 10, 3, 2, 0), 6) == "2026-09"


def test_india_time_decides_the_day_not_utc() -> None:
    # 1 Oct 00:40 UTC is 06:10 IST: owed. 30 Sep 23:00 UTC is 1 Oct 04:30 IST: not yet.
    assert packs.period_for(datetime(2026, 10, 1, 0, 40, tzinfo=UTC), 6) == "2026-09"
    assert packs.period_for(datetime(2026, 9, 30, 23, 0, tzinfo=UTC), 6) is None
    assert packs.current_period(datetime(2026, 9, 30, 19, 0, tzinfo=UTC)) == "2026-10"


def test_the_digest_window_is_the_first_week_from_the_run_hour() -> None:
    assert not packs.digest_open(_ist(2026, 10, 1, 5, 59), 6)
    assert packs.digest_open(_ist(2026, 10, 1, 6, 0), 6)
    assert packs.digest_open(_ist(2026, 10, 8, 5, 59), 6)
    assert not packs.digest_open(_ist(2026, 10, 8, 6, 0), 6)
    assert not packs.digest_open(_ist(2026, 10, 20, 12, 0), 6)


def test_storage_keys_carry_the_revision_and_stay_out_of_media_and_backups() -> None:
    key = packs.storage_key(settings_on(), "2026-09", "SKILL_UPGRADATION", 3, "xlsx")
    assert (
        key
        == "exports/ministry/2026-09/SKILL_UPGRADATION/r3/ministry-pack-skill-upgradation-2026-09.xlsx"
    )
    assert not key.startswith(("media/", "backups/"))


@pytest.mark.asyncio
async def test_the_switch_off_does_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    called: list[Any] = []
    monkeypatch.setattr(packs, "ensure_scheduled", lambda *a, **k: called.append(a))
    packs.reset_schedule_gate()
    await packs.maybe_run(settings_on(ministry_exports_enabled=False), _ist(2026, 10, 1, 7, 0))
    assert called == []


# ── over the database ───────────────────────────────────────────────────────────────────────────


def _rows() -> list[Any]:
    async def work() -> list[Any]:
        return list(await db.ministryexportpack.find_many(order={"scheme": "asc"}))

    return private(work)


@needs_db
def test_two_passes_queue_exactly_seven_packs_for_the_month_before(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    FakeBucket().install(monkeypatch)
    built: list[str] = []

    async def fake_build(pack: Any, settings: Any = None) -> None:
        built.append(pack.scheme)
        await db.ministryexportpack.update_many(
            where={"id": pack.id, "status": packs.BUILDING},
            data={"status": packs.EMPTY, "lockedAt": None},
        )

    monkeypatch.setattr(packs, "build_pack", fake_build)
    settings = settings_on()

    async def work() -> None:
        await reset()
        packs.reset_schedule_gate()
        await packs.maybe_run(settings, _ist(2026, 10, 1, 6, 5))
        await packs.maybe_run(settings, _ist(2026, 10, 1, 6, 6))

    private(work)
    rows = _rows()
    assert len(rows) == 7
    assert {row.period for row in rows} == {"2026-09"}
    assert {row.trigger for row in rows} == {packs.SCHEDULED}
    assert len(built) == 2  # one pack per pass


@needs_db
def test_a_second_run_in_the_same_month_changes_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = settings_on()

    async def work() -> tuple[int, int]:
        await reset()
        first = await packs.ensure_scheduled("2026-09")
        second = await packs.ensure_scheduled("2026-09")
        return first, second

    first, second = private(work)
    before = {(row.scheme, row.revision, row.status) for row in _rows()}
    assert (first, second) == (7, 0)

    async def again() -> None:
        packs.reset_schedule_gate()
        monkeypatch.setattr(packs, "process_next_pack", _no_build)
        await packs.maybe_run(settings, _ist(2026, 10, 9, 12, 0))

    private(again)
    assert {(row.scheme, row.revision, row.status) for row in _rows()} == before


async def _no_build(*args: Any, **kwargs: Any) -> None:
    return None


@needs_db
def test_a_racing_insert_is_swallowed(monkeypatch: pytest.MonkeyPatch) -> None:
    from prisma.errors import UniqueViolationError

    async def work() -> int:
        await reset()
        await db.ministryexportpack.create(
            data={
                "period": "2026-08",
                "scheme": "OTHER",
                "trigger": packs.ON_DEMAND,
                "status": packs.READY,
            }
        )
        actions = type(db.ministryexportpack)  # slotted: patch the class, not the instance

        async def stale_find(self: Any, *args: Any, **kwargs: Any) -> list[Any]:
            return []  # as if another writer inserted between our read and our create

        with monkeypatch.context() as patch:
            patch.setattr(actions, "find_many", stale_find)
            return await packs.ensure_scheduled("2026-08")

    assert private(work) == 6
    rows = _rows()
    assert len(rows) == 7
    other = next(row for row in rows if row.scheme == "OTHER")
    assert other.trigger == packs.ON_DEMAND and other.revision == 1
    assert UniqueViolationError  # imported: the class the scheduler catches


@needs_db
def test_a_month_to_date_pack_is_rebuilt_once_the_month_is_over() -> None:
    async def work() -> Any:
        await reset()
        await db.ministryexportpack.create(
            data={
                "period": "2026-09",
                "scheme": "OTHER",
                "trigger": packs.ON_DEMAND,
                "status": packs.READY,
                "monthToDate": True,
            }
        )
        await db.ministryexportpack.create(
            data={
                "period": "2026-09",
                "scheme": "SKILL_UPGRADATION",
                "trigger": packs.ON_DEMAND,
                "status": packs.FAILED,
                "monthToDate": True,
            }
        )
        await packs.ensure_scheduled("2026-09")
        return {row.scheme: row for row in await db.ministryexportpack.find_many()}

    rows = private(work)
    assert rows["OTHER"].status == packs.QUEUED and rows["OTHER"].revision == 2
    assert rows["OTHER"].trigger == packs.SCHEDULED
    # A failed month-to-date pack is left alone, or it would be retried every ten minutes.
    assert (
        rows["SKILL_UPGRADATION"].status == packs.FAILED and rows["SKILL_UPGRADATION"].revision == 1
    )
