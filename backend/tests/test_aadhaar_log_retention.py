"""Regulation 18(3): Aadhaar log entries archived after two years, deleted after the archive period.

Against a real database (the entries are ``RecordRevision`` rows, selected by a JSON operator), with
the bucket replaced by an in-memory store. Pinned: only entries older than two years leave the live
table, only after their archive read back, and a failed upload deletes nothing; a record under legal
hold keeps its entries live and its archives undeleted; an expired archive is deleted once and its
ledger row stripped of record ids; the archive carries no Aadhaar number.
"""

from __future__ import annotations

import asyncio
import gzip
import json
import re
import uuid
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db

from app.services.aadhaar import log_retention as retention

NOW = datetime.now(UTC)


class MemoryStore:
    def __init__(self, *, fail: bool = False) -> None:
        self.objects: dict[str, bytes] = {}
        self.deleted: list[str] = []
        self.fail = fail

    def put(self, key: str, body: bytes, sha256: str) -> None:
        if self.fail:
            raise ConnectionError("bucket unreachable")
        self.objects[key] = body

    def delete(self, key: str) -> None:
        self.deleted.append(key)
        self.objects.pop(key, None)


def test_two_years_is_two_calendar_years_and_29_february_is_safe() -> None:
    assert retention.years_before(datetime(2028, 2, 29, tzinfo=UTC), 2) == datetime(2026, 2, 28, tzinfo=UTC)
    assert retention.years_after(datetime(2026, 10, 17, tzinfo=UTC), 7) == datetime(2033, 10, 17, tzinfo=UTC)


def test_the_worker_hook_runs_at_most_once_a_day(monkeypatch) -> None:
    calls: list[datetime] = []

    async def fake_run(settings: Any, *, now: datetime) -> str:
        calls.append(now)
        return "ran"

    monkeypatch.setattr(retention, "run_retention", fake_run)
    monkeypatch.setattr(retention, "_next_run", None)
    on = SimpleNamespace(aadhaar_log_retention_enabled=True)
    assert asyncio.run(retention.maybe_run(on, now=NOW)) == "ran"
    assert asyncio.run(retention.maybe_run(on, now=NOW + timedelta(hours=23))) is None
    assert asyncio.run(retention.maybe_run(on, now=NOW + timedelta(hours=25))) == "ran"
    off = SimpleNamespace(aadhaar_log_retention_enabled=False)
    assert asyncio.run(retention.maybe_run(off, now=NOW + timedelta(days=9))) is None
    assert len(calls) == 2


async def _seed(tag: str) -> dict[str, Any]:
    from app.core.db import db
    from app.services.aadhaar.records import audit_attempt

    people = {}
    for slug in ("old", "held", "recent"):
        people[slug] = await db.user.create(
            data={"email": f"retention-{slug}-{tag}@example.org", "name": f"R {slug}", "role": "DESIGNER"}
        )
    actor = SimpleNamespace(id=people["old"].id)
    for person in people.values():
        await audit_attempt("user", person.id, actor, method="SECURE_QR", outcome="VERIFIED",
                            previous=None, last4="2345")
    backdate = (NOW - timedelta(days=3 * 365)).replace(tzinfo=None)
    for slug in ("old", "held"):
        await db.execute_raw(
            """UPDATE "RecordRevision" SET "createdAt" = $1::timestamp
               WHERE "recordType" = 'user' AND "recordId" = $2""",
            backdate.isoformat(),
            people[slug].id,
        )
    await db.aadhaarlegalhold.create(
        data={"recordType": "user", "recordId": people["held"].id, "reason": "Test court order 1/2026"}
    )
    return {slug: user.id for slug, user in people.items()}


async def _entries(user_id: str) -> int:
    from app.core.db import db

    return await db.recordrevision.count(where={"recordType": "user", "recordId": user_id})


@needs_db
def test_old_entries_are_archived_held_ones_stay_and_expired_archives_are_deleted() -> None:
    from app.core.db import db

    tag = uuid.uuid4().hex[:8]

    async def scenario() -> None:
        await db.connect()
        try:
            ids = await _seed(tag)

            # A bucket that is down: nothing leaves the live table.
            down = MemoryStore(fail=True)
            report = await retention.archive_due(NOW, down)
            assert report.failures and report.archived_entries == 0
            assert await _entries(ids["old"]) == 1

            store = MemoryStore()
            report = await retention.archive_due(NOW, store)
            assert report.archived_entries >= 1 and report.held_entries_kept >= 1
            assert await _entries(ids["old"]) == 0, "the two-year-old entry is still live"
            assert await _entries(ids["held"]) == 1, "an entry under legal hold was archived"
            assert await _entries(ids["recent"]) == 1, "a recent entry was archived"

            archive = await db.aadhaarlogarchive.find_first(
                where={"recordKeys": {"has": f"user:{ids['old']}"}}
            )
            assert archive is not None and archive.objectKey in store.objects
            assert archive.deleteAfter.year == archive.newestAt.year + 7
            lines = gzip.decompress(store.objects[archive.objectKey]).decode().splitlines()
            entries = [json.loads(line) for line in lines]
            mine = [e for e in entries if e["recordId"] == ids["old"]]
            assert mine and mine[0][retention.ENTRY_KEY]["outcome"] == "VERIFIED"
            assert re.search(r"\d{12}", " ".join(lines)) is None, "an archive holds a full number"

            # Running again changes nothing that was already archived.
            again = await retention.archive_due(NOW, MemoryStore())
            assert await _entries(ids["held"]) == 1 and again.archives_written == 0

            # Past the archive period: deleted once, unless the archive holds a held record's entry.
            await db.aadhaarlegalhold.create(
                data={"recordType": "user", "recordId": ids["old"], "reason": "Test dispute"}
            )
            later = NOW + timedelta(days=8 * 365)
            kept = await retention.delete_expired(later, store)
            assert kept.held_archives_kept >= 1 and archive.objectKey in store.objects
            await db.aadhaarlegalhold.update_many(
                where={"recordId": ids["old"]}, data={"releasedAt": NOW}
            )
            gone = await retention.delete_expired(later, store)
            assert archive.objectKey in store.deleted and gone.archives_deleted >= 1
            row = await db.aadhaarlogarchive.find_unique(where={"id": archive.id})
            assert row.deletedAt is not None and row.recordKeys == []
            assert (await retention.delete_expired(later, store)).archives_deleted == 0
        finally:
            await db.disconnect()

    asyncio.run(scenario())


@pytest.mark.parametrize("years", [4, 0])
def test_the_archive_period_cannot_be_set_below_five_years(years: int) -> None:
    from pydantic import ValidationError

    from app.core.config import Settings, get_settings

    base = get_settings().model_dump(by_alias=True)
    base["AADHAAR_LOG_ARCHIVE_YEARS"] = years
    with pytest.raises(ValidationError):
        Settings(**base)
