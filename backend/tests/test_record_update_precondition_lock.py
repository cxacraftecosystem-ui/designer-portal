from __future__ import annotations

import asyncio
import os
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from fastapi import HTTPException

from app.services import records as records_service
from app.services.records import lock_and_assert_expected_updated_at

pytestmark = pytest.mark.anyio


class _Table:
    def __init__(self, stored: datetime) -> None:
        self.stored = stored
        self.calls: list[dict] = []

    async def update_many(self, where, data):
        self.calls.append(where)
        if where["updatedAt"] != self.stored:
            return 0
        self.stored = data["updatedAt"]
        return 1


class _Tx:
    def __init__(self, stored: datetime) -> None:
        self.craft = _Table(stored)


class _Row:
    def __init__(self, record_id: str, updated_at: datetime) -> None:
        self.id = record_id
        self.updatedAt = updated_at


NOW = datetime(2026, 10, 16, 8, 0, 0, tzinfo=UTC)


async def test_no_precondition_touches_nothing():
    tx = _Tx(NOW)
    await lock_and_assert_expected_updated_at(tx, "craft", _Row("c1", NOW), None)
    assert tx.craft.calls == []


async def test_a_match_claims_the_row_with_a_conditional_write():
    tx = _Tx(NOW)
    await lock_and_assert_expected_updated_at(tx, "craft", _Row("c1", NOW), NOW)
    assert tx.craft.calls == [{"id": "c1", "updatedAt": NOW}]


async def test_a_row_written_since_it_was_read_matches_nothing_and_is_refused():
    tx = _Tx(NOW + timedelta(hours=1))
    with pytest.raises(HTTPException) as refused:
        await lock_and_assert_expected_updated_at(tx, "craft", _Row("c1", NOW), NOW)
    assert refused.value.status_code == 409
    assert refused.value.detail["code"] == "record_changed"


async def test_a_stale_precondition_is_refused_before_any_write():
    tx = _Tx(NOW)
    with pytest.raises(HTTPException):
        await lock_and_assert_expected_updated_at(tx, "craft", _Row("c1", NOW), NOW - timedelta(hours=2))
    assert tx.craft.calls == []


async def test_only_known_models_are_claimed():
    with pytest.raises(ValueError):
        await lock_and_assert_expected_updated_at(_Tx(NOW), "user", _Row("c1", NOW), NOW)
    assert {"productdocumentation", "tooldocumentation"} <= records_service.EXPECTED_UPDATED_AT_MODELS


_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL")
async def test_two_corrections_against_one_version_cannot_both_land():
    from app.core.db import db

    connected_here = not db.is_connected()
    if connected_here:
        await db.connect()
    craft = await db.craft.create(data={"name": f"lock-race-{uuid.uuid4().hex[:10]}"})
    expected = craft.updatedAt
    first_holds_lock = asyncio.Event()
    outcomes: dict[str, str] = {}
    try:

        async def first() -> None:
            async with db.tx() as tx:
                await lock_and_assert_expected_updated_at(tx, "craft", craft, expected)
                first_holds_lock.set()
                await asyncio.sleep(1.5)
                await tx.craft.update(where={"id": craft.id}, data={"description": "first"})
            outcomes["first"] = "landed"

        async def second() -> None:
            await first_holds_lock.wait()
            try:
                async with db.tx() as tx:
                    await lock_and_assert_expected_updated_at(tx, "craft", craft, expected)
                    await tx.craft.update(where={"id": craft.id}, data={"description": "second"})
                outcomes["second"] = "landed"
            except HTTPException as refused:
                outcomes["second"] = f"refused {refused.status_code}"

        await asyncio.gather(first(), second())
        stored = await db.craft.find_unique(where={"id": craft.id})
        assert outcomes == {"first": "landed", "second": "refused 409"}
        assert stored is not None and stored.description == "first"
    finally:
        await db.craft.delete_many(where={"id": craft.id})
        if connected_here:
            await db.disconnect()
