from __future__ import annotations

import json
from typing import Any

import pytest
import pytest_asyncio
from conftest import needs_db
from search_support import SECRETS, index_world, new_stamp, seed
from test_cross_portal_search_peer import NONCE, TOKEN, _admitted

from app.api.routes import cross_portal_explore as routes
from app.core.db import db
from app.services import cross_portal

pytestmark = [needs_db, pytest.mark.asyncio(loop_scope="module")]

EXPLORE_PATH = "/api/cross-portal/peer/explore"


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def world():
    await db.connect()
    try:
        w = await seed(new_stamp())
        await index_world(w)
        yield w
    finally:
        await db.disconnect()


async def _explore(w, monkeypatch, **body: Any) -> tuple[Any, dict[str, Any]]:
    request, settings = await _admitted(EXPLORE_PATH, monkeypatch)
    payload = routes.PeerExplore(
        **{
            "filters": {"craftCodes": [w.code.code]},
            "requester": {"userId": f"fr-{w.stamp}", "email": "nobody@example.invalid", "role": "RESEARCHER"},
            **body,
        }
    )
    response = await routes.peer_explore(payload, request, settings)
    return response, json.loads(response.body)


async def test_a_listing_without_text_is_signed_scoped_and_shareable_only(world, monkeypatch):
    response, body = await _explore(world, monkeypatch, type="artisans")
    assert cross_portal.signature_matches(
        TOKEN,
        response.body,
        response.headers[cross_portal.SIGNATURE_HEADER],
        method="POST",
        path=EXPLORE_PATH,
        nonce=NONCE,
    )
    ids = {item["id"] for item in body["items"]}
    assert world.linked.id in ids
    assert world.unlinked.id not in ids
    assert body["total"] >= len(body["items"]) >= 1
    for item in body["items"]:
        assert item["type"] == "artisans"
        assert set(item) == {"type", "id", "title", "subtitle", "place", "craftCodes", "recordDate", "openUrl"}
        assert item["openUrl"].startswith("https://designer.example/")
    raw = response.body.decode()
    for secret in SECRETS:
        assert secret not in raw


async def test_text_narrows_the_listing_and_an_unknown_type_is_empty(world, monkeypatch):
    _r, body = await _explore(world, monkeypatch, type="artisans", q="Sita")
    assert [item["id"] for item in body["items"]] == [world.linked.id]
    _r, empty = await _explore(world, monkeypatch, type="nonsense")
    assert empty == {"items": [], "total": 0}


async def test_a_craft_code_from_elsewhere_finds_nothing(world, monkeypatch):
    _r, body = await _explore(world, monkeypatch, type="artisans", filters={"craftCodes": ["no-such-code"]})
    assert body["items"] == [] and body["total"] == 0


async def test_pages_move_on_and_keep_the_total(world, monkeypatch):
    _r, first = await _explore(world, monkeypatch, limit=1, page=1)
    _r, second = await _explore(world, monkeypatch, limit=1, page=2)
    assert first["total"] == second["total"] >= 2
    assert first["items"][0]["id"] != second["items"][0]["id"]
