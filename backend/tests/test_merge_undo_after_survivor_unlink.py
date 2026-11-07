from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.core.db import db
from app.services import artisan_merge, cross_portal_client
from tests import duplicates_support as ds

pytestmark = pytest.mark.skipif(not ds.LOCAL, reason="needs a LOCAL database")

SETTINGS = SimpleNamespace(
    cross_portal_identity_enabled=True,
    cross_portal_token="t" * 40,
    aadhaar_fingerprint_key="k" * 40,
    field_repository_api_url="https://api.docs.example/api",
    artisan_duplicate_hold=True,
)


def run(coro_fn):
    async def wrapper():
        await db.connect()
        try:
            return await coro_fn()
        finally:
            await db.disconnect()

    return asyncio.run(wrapper())


class Peer:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, Any]] = []

    async def __call__(self, settings, method, path, *, body=None, params=None, requested_by=None):
        self.calls.append((method, path, body))
        return 200, {}


async def _merged(monkeypatch, t: str, *, both_linked: bool) -> tuple[Any, Any, Any, Peer]:
    user = await ds.admin(t)
    keep = await ds.artisan(user, f"Unlinked {t}")
    gone = await ds.artisan(user, f"Unlinked {t}")
    remote = f"R-{t}"
    await db.crossportalartisanlink.create(data={"artisanId": gone.id, "remoteArtisanId": remote, "matchedBy": "AADHAAR"})
    if both_linked:
        await db.crossportalartisanlink.create(data={"artisanId": keep.id, "remoteArtisanId": remote, "matchedBy": "AADHAAR"})
    peer = Peer()
    monkeypatch.setattr(cross_portal_client, "peer_call", peer)
    pair = await ds.make_pair(keep.id, gone.id)
    result = await artisan_merge.merge(
        pair, survivor=ds.sides(pair, keep.id), choices={}, expected_updated_at=None, reason=None, user=user, settings=SETTINGS
    )
    assert (await db.crossportalartisanlink.find_unique(where={"artisanId": keep.id})).remoteArtisanId == remote
    await db.crossportalartisanlink.delete(where={"artisanId": keep.id})
    peer.calls.clear()
    return user, gone, result, peer


def test_undo_does_not_relink_the_restored_record_after_the_survivor_was_unlinked(monkeypatch):
    async def scenario():
        t = ds.tag()
        user, gone, result, _peer = await _merged(monkeypatch, t, both_linked=True)
        await artisan_merge.undo(result["merge"]["id"], user=user, settings=SETTINGS)
        assert await db.artisan.find_unique(where={"id": gone.id}) is not None
        assert await db.crossportalartisanlink.find_unique(where={"artisanId": gone.id}) is None

    run(scenario)


def test_undo_does_not_ask_the_peer_to_relink_after_the_survivor_was_unlinked(monkeypatch):
    async def scenario():
        t = ds.tag()
        user, gone, result, peer = await _merged(monkeypatch, t, both_linked=False)
        await artisan_merge.undo(result["merge"]["id"], user=user, settings=SETTINGS)
        assert await db.artisan.find_unique(where={"id": gone.id}) is not None
        assert not [call for call in peer.calls if call[0] == "POST"]
        assert await db.crossportalartisanlink.find_unique(where={"artisanId": gone.id}) is None

    run(scenario)
