"""The answering side of cross-portal search: ``POST /cross-portal/peer/search`` and ``.../record``
(routes/cross_portal_search, services/cross_portal_peer; docs/SEARCH.md).

Against a real database for the search itself. The admission dependency is exercised on its own and
through the app's router with a TestClient-free request object, so the module keeps one event loop.
"""

from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any

import pytest
import pytest_asyncio
from conftest import needs_db
from fastapi import HTTPException
from search_support import SECRETS, document, index_world, new_stamp, seed
from starlette.requests import Request

from app.api.routes import cross_portal_search as routes
from app.core.db import db
from app.services import cross_portal, cross_portal_peer

# One event loop for the module: `world` connects `db` once and every test awaits it.
pytestmark = [needs_db, pytest.mark.asyncio(loop_scope="module")]

TOKEN = "p" * 40
KEY = "k" * 40
NONCE = "n" * 32
SEARCH_PATH = "/api/cross-portal/peer/search"
RECORD_PATH = "/api/cross-portal/peer/search/record"


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def world():
    await db.connect()
    try:
        w = await seed(new_stamp())
        await index_world(w)
        yield w
    finally:
        await db.disconnect()


def _settings(**over: Any) -> SimpleNamespace:
    base = {
        "cross_portal_identity_enabled": False,
        "cross_portal_search_enabled": True,
        "cross_portal_token": TOKEN,
        "cross_portal_aadhaar_hmac_key": KEY,
        "field_repository_api_url": "https://api.docs.example/api",
        "next_public_app_url": "https://designer.example",
        "search_embeddings_enabled": False,
        "openai_api_key": None,
        "gemini_api_keys": [],
    }
    base.update(over)
    return SimpleNamespace(**base)


def _request(path: str, *, token: str = TOKEN, nonce: str = NONCE, by: str = "fr-user") -> Request:
    headers = [
        (b"authorization", f"Bearer {token}".encode()),
        (b"x-cross-portal-nonce", nonce.encode()),
        (cross_portal.REQUESTED_BY_HEADER.lower().encode(), by.encode()),
    ]
    scope = {
        "type": "http",
        "method": "POST",
        "path": path,
        "raw_path": path.encode(),
        "query_string": b"",
        "headers": headers,
        "server": ("testserver", 80),
        "scheme": "http",
    }
    return Request(scope)


async def _admitted(path: str, monkeypatch, **kw: Any) -> tuple[Request, Any]:
    settings = _settings()
    monkeypatch.setattr(cross_portal_peer, "get_settings", lambda: settings)
    request = _request(path, **kw)
    admitted = await cross_portal_peer.require_search_peer(
        request,
        authorization=request.headers.get("authorization"),
        x_cross_portal_nonce=request.headers.get("x-cross-portal-nonce"),
    )
    return request, admitted


def _body(response) -> dict[str, Any]:
    return json.loads(response.body)


def _requester(w, user=None, email: str | None = None) -> dict[str, str]:
    return {
        "userId": f"fr-{w.stamp}",
        "email": email if email is not None else (user.email if user else ""),
        "role": "RESEARCHER",
    }


async def _peer_search(w, monkeypatch, **body: Any):
    request, settings = await _admitted(SEARCH_PATH, monkeypatch)
    payload = routes.PeerSearch(**{"q": w.stamp, **body})
    return request, await routes.peer_search(payload, request, settings)


def _items(body: dict[str, Any]) -> list[dict[str, Any]]:
    return [i for g in body["groups"] for i in g["items"]]


async def test_off_is_404_and_a_bad_token_or_nonce_is_401(world, monkeypatch):
    monkeypatch.setattr(
        cross_portal_peer, "get_settings", lambda: _settings(cross_portal_search_enabled=False)
    )
    request = _request(SEARCH_PATH)
    with pytest.raises(HTTPException) as off:
        await cross_portal_peer.require_search_peer(request, f"Bearer {TOKEN}", NONCE)
    assert off.value.status_code == 404
    monkeypatch.setattr(cross_portal_peer, "get_settings", _settings)
    with pytest.raises(HTTPException) as bad:
        await cross_portal_peer.require_search_peer(request, "Bearer " + "x" * 40, NONCE)
    assert bad.value.status_code == 401
    with pytest.raises(HTTPException) as short:
        await cross_portal_peer.require_search_peer(request, f"Bearer {TOKEN}", "abc")
    assert short.value.status_code == 401


async def test_the_answer_is_signed_over_the_request(world, monkeypatch):
    _request, response = await _peer_search(
        world, monkeypatch, requester=_requester(world, email="nobody@example.invalid")
    )
    assert cross_portal.signature_matches(
        TOKEN,
        response.body,
        response.headers[cross_portal.SIGNATURE_HEADER],
        method="POST",
        path=SEARCH_PATH,
        nonce=NONCE,
    )


async def test_an_unknown_requester_gets_the_floor_of_shareable_records(world, monkeypatch):
    _r, response = await _peer_search(
        world, monkeypatch, requester=_requester(world, email="nobody@example.invalid")
    )
    body = _body(response)
    ids = {i["id"] for i in _items(body)}
    assert world.linked.id in ids and world.craft.id in ids and world.product_title.id in ids
    # Not shareable: an unlinked artisan, a free-text artisan, design-workshop data.
    for hidden in (
        world.unlinked.id,
        world.product_body.id,
        world.product_free.id,
        world.dw_designer.id,
        world.report.id,
        world.transcript_dw.id,
    ):
        assert hidden not in ids


async def test_a_matching_account_brings_its_own_rules_but_still_only_shareable(world, monkeypatch):
    _r, response = await _peer_search(
        world, monkeypatch, requester=_requester(world, world.professor)
    )
    ids = {i["id"] for i in _items(_body(response))}
    # A professor may read DW data here, but DW documents are never shareable.
    assert world.dw_designer.id not in ids and world.linked.id in ids


async def test_a_withdrawn_link_is_honoured_before_the_indexer_runs(world, monkeypatch):
    product = await document("products", world.product_title.id)
    assert product["shareable"] is True
    link = await db.crossportalartisanlink.find_unique(where={"artisanId": world.linked.id})
    await db.crossportalartisanlink.delete(where={"artisanId": world.linked.id})
    try:
        # The document still says shareable: nothing was drained.
        assert (await document("products", world.product_title.id))["shareable"] is True
        _r, response = await _peer_search(
            world, monkeypatch, requester=_requester(world, email="nobody@example.invalid")
        )
        ids = {i["id"] for i in _items(_body(response))}
        assert world.product_title.id not in ids and world.linked.id not in ids
        assert world.craft.id in ids
    finally:
        await db.crossportalartisanlink.create(
            data={
                "artisanId": link.artisanId,
                "remoteArtisanId": link.remoteArtisanId,
                "matchedBy": link.matchedBy,
            }
        )


async def test_items_carry_only_the_projection_and_linked_remote_ids(world, monkeypatch):
    _r, response = await _peer_search(
        world, monkeypatch, requester=_requester(world, email="nobody@example.invalid")
    )
    raw = response.body.decode()
    for secret in SECRETS:
        assert secret not in raw
    for forbidden in ("objectKey", "bucket", "aadhaar", "phone", "\"email\"", "dateOfBirth"):
        assert forbidden not in raw
    allowed = set(cross_portal.PEER_ITEM_KEYS) | {"linkedArtisanIds"}
    for item in _items(_body(response)):
        assert set(item) <= allowed
        assert item["openUrl"].startswith("https://designer.example/")
    product = next(i for i in _items(_body(response)) if i["id"] == world.product_title.id)
    assert product["linkedArtisanIds"] == [f"fr-{world.stamp}"]


async def test_each_search_is_logged_without_the_query(world, monkeypatch):
    asker = f"fr-{world.stamp}"
    await _peer_search(world, monkeypatch, requester=_requester(world, email="x@example.invalid"))
    rows = await db.crossportalaccesslog.find_many(
        where={"remoteUserId": asker, "action": "SEARCH"}, order={"createdAt": "desc"}
    )
    assert rows and rows[0].outcome == "ok" and rows[0].resultCount > 0
    for row in rows:
        assert world.stamp not in json.dumps(row.model_dump(), default=str).replace(asker, "")


async def test_the_rate_limit_answers_a_signed_429(world, monkeypatch):
    monkeypatch.setattr(routes, "RATE_LIMIT", 1)
    requester = {"userId": f"rl-{world.stamp}", "email": "", "role": "RESEARCHER"}
    _r, first = await _peer_search(world, monkeypatch, requester=requester)
    assert first.status_code == 200
    _r2, second = await _peer_search(world, monkeypatch, requester=requester)
    assert second.status_code == 429
    assert cross_portal.signature_matches(
        TOKEN,
        second.body,
        second.headers[cross_portal.SIGNATURE_HEADER],
        method="POST",
        path=SEARCH_PATH,
        nonce=NONCE,
    )


async def test_a_record_is_a_read_only_card_or_404(world, monkeypatch):
    request, settings = await _admitted(RECORD_PATH, monkeypatch)
    ok = await routes.peer_search_record(
        routes.PeerRecord(type="products", id=world.product_title.id, requester=_requester(world)),
        request,
        settings,
    )
    record = _body(ok)["record"]
    assert record["title"] == f"Indigo stole {world.stamp}"
    assert record["openUrl"] == f"https://designer.example/products/{world.product_title.id}"
    assert {f["label"] for f in record["fields"]} >= {"Type", "Place"}
    for secret in SECRETS:
        assert secret not in ok.body.decode()

    for kind, entity_id in (
        ("products", world.product_body.id),  # not shareable
        ("designWorkshops", world.dw_designer.id),  # DW data
        ("products", "missing"),
        ("nonsense", world.product_title.id),
    ):
        missing = await routes.peer_search_record(
            routes.PeerRecord(type=kind, id=entity_id, requester=_requester(world)),
            request,
            settings,
        )
        assert missing.status_code == 404 and _body(missing) == {"record": None}
    rows = await db.crossportalaccesslog.find_many(
        where={"remoteUserId": f"fr-{world.stamp}", "action": "RECORD_VIEW"}
    )
    assert {r.outcome for r in rows} == {"ok", "not_found"}
