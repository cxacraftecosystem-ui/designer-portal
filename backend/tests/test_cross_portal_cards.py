"""Field Repository cards on the designer portal, through the cross-portal channel.

The documentation portal is replaced by a fake ``peer_call`` (the signing layer it stands for is
pinned in tests/test_cross_portal_identity.py); the database is real. Pinned here:

* an F card resolves through an existing link with no network call;
* an F card for an artisan not linked here is REMOTE, asking for consent, and with the consent tick
  carries the same prefill the Aadhaar lookup does;
* a peer answer for an artisan linked here is FOUND via LINK, and the code is kept for next time;
* the peer saying nothing is the one 404; the peer unreachable is a 503;
* linking from a card records matchedBy=CARD, sends this portal's card code and keeps theirs;
* refresh keeps their latest code and re-sends ours, so a reissue push that failed lands later.
"""

from __future__ import annotations

import asyncio
import os
import uuid
from typing import Any

import pytest

from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import artisan_card as ac
from app.services.cross_portal_client import UNAVAILABLE, PeerUnavailable

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
    ),
]

KEY = "test-only-artisan-card-key-not-a-secret-0123456789"
SETTINGS = {
    "artisan_cards_enabled": True,
    "artisan_card_key": KEY,
    "artisan_card_key_version": 1,
    "artisan_card_resolve_miss_limit": 50,
    "artisan_card_issuer": "D",
    "cross_portal_identity_enabled": True,
    "cross_portal_token": "t" * 40,
    "field_repository_api_url": "https://api.docs.example/api",
}


def _remote(artisan_id: str, card_code: str, name: str = "Meena Devi") -> dict[str, Any]:
    return {
        "artisanId": artisan_id,
        "matchedBy": "CARD",
        "cardCode": card_code,
        "fields": {
            "name": name,
            "localName": None,
            "gender": "Female",
            "dateOfBirth": None,
            "pehchanCardAvailable": False,
            "pehchanCardNumber": None,
            "craftStartDate": None,
            "experienceYears": 12,
            "experienceMonths": None,
            "phone": None,
            "place": "Bhuj",
        },
        "craft": None,
        "statedAddress": {"state": "Gujarat", "district": "Kachchh", "village": "Nirona"},
    }


class FakePeer:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, Any]] = []
        self.answers: dict[tuple[str, str], Any] = {}

    async def __call__(self, settings, method, path, *, body=None, params=None, requested_by=None):
        self.calls.append((method, path, body))
        answer = self.answers.get((method, path))
        if answer is None:
            raise AssertionError(f"unexpected peer call {method} {path}")
        if isinstance(answer, Exception):
            raise answer
        return answer

    def paths(self) -> list[str]:
        return [f"{m} {p}" for m, p, _ in self.calls]


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.api.routes import cross_portal as cross_portal_routes
    from app.main import app

    settings = get_settings()
    saved = {name: getattr(settings, name) for name in SETTINGS}
    for name, value in SETTINGS.items():
        setattr(settings, name, value)
    peer = FakePeer()
    real_peer_call = cross_portal_routes.peer_call
    cross_portal_routes.peer_call = peer
    tag = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            admin = await db.user.create(
                data={
                    "email": f"xcard-admin-{tag}@example.org",
                    "name": "Cross Card Admin",
                    "role": "ADMIN",
                    "passwordHash": hash_password("unused"),
                }
            )
            out: dict[str, Any] = {"admin": admin.id, "tag": tag}
            for key in ("linked", "linked_no_code", "to_link", "reissued"):
                row = await db.artisan.create(
                    data={"name": f"Meena {key} {tag}", "place": "Bhuj", "createdById": admin.id}
                )
                out[key] = row.id
            await db.crossportalartisanlink.create(
                data={
                    "artisanId": out["linked"],
                    "remoteArtisanId": f"fr-linked-{tag}",
                    "matchedBy": "AADHAAR",
                    "remoteCardCode": f"X{tag[:8]}A".upper()[:10],
                }
            )
            for key in ("linked_no_code", "reissued"):
                await db.crossportalartisanlink.create(
                    data={
                        "artisanId": out[key],
                        "remoteArtisanId": f"fr-{key}-{tag}",
                        "matchedBy": "AADHAAR",
                    }
                )
            # A link the other side has recorded, so refresh goes straight to the read.
            from datetime import UTC, datetime

            await db.crossportalartisanlink.update(
                where={"artisanId": out["reissued"]}, data={"remoteAcknowledgedAt": datetime.now(UTC)}
            )
            return out
        finally:
            await db.disconnect()

    try:
        seeded = asyncio.run(seed())
        seeded["peer"] = peer
        with TestClient(app) as client:
            seeded["client"] = client
            yield seeded
    finally:
        cross_portal_routes.peer_call = real_peer_call
        for name, value in saved.items():
            setattr(settings, name, value)


def _code(seed: str) -> str:
    """A valid Crockford code made from a test tag."""
    text = "".join(ch for ch in seed.upper() if ch in ac.ALPHABET)
    return (text + "0000000000")[:10]


@pytest.fixture
def client(world):
    world["peer"].calls.clear()
    world["peer"].answers.clear()
    return world["client"]


@pytest.fixture
def admin(world):
    return {"Authorization": f"Bearer {create_access_token(world['admin'])}"}


def _resolve(client, admin, given: str, consent: bool = False):
    return client.post(
        "/api/artisan-cards/resolve", json={"input": given, "consent": consent}, headers=admin
    )


def test_an_f_card_resolves_through_an_existing_link_with_no_network_call(client, admin, world):
    stored = f"X{world['tag'][:8]}A".upper()[:10]
    response = _resolve(client, admin, ac.short_code("F", stored))
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["status"] == "FOUND" and body["via"] == "LINK"
    assert body["artisan"]["id"] == world["linked"]
    assert world["peer"].calls == []


def test_an_unlinked_artisan_is_remote_and_asks_for_consent_first(client, admin, world):
    code = _code("NEWONE" + world["tag"])
    world["peer"].answers[("POST", "/cards/resolve")] = (200, {"match": _remote(f"fr-new-{world['tag']}", code)})
    first = _resolve(client, admin, ac.short_code("F", code))
    assert first.status_code == 200, first.text
    assert first.json() == {"status": "REMOTE", "issuer": "F", "needsConsent": True}
    sent = world["peer"].calls[0][2]
    assert sent == {"cardCode": code, "tag": None, "keyVersion": None}
    second = _resolve(client, admin, ac.short_code("F", code), consent=True)
    body = second.json()
    assert body["status"] == "REMOTE" and body["needsConsent"] is False
    match = body["match"]
    assert match["remoteArtisanId"] == f"fr-new-{world['tag']}"
    assert match["matchedBy"] == "CARD"
    assert match["fields"]["name"] == "Meena Devi"
    assert match["statedAddress"]["village"] == "Nirona"


def test_a_peer_answer_for_an_artisan_linked_here_is_found_and_the_code_is_kept(client, admin, world):
    code = _code("KEEP" + world["tag"])
    world["peer"].answers[("POST", "/cards/resolve")] = (
        200,
        {"match": _remote(f"fr-linked_no_code-{world['tag']}", code)},
    )
    response = _resolve(client, admin, ac.short_code("F", code))
    assert response.json()["status"] == "FOUND"
    assert response.json()["via"] == "LINK"
    assert response.json()["artisan"]["id"] == world["linked_no_code"]
    # Kept: the next scan needs no network call.
    world["peer"].answers.clear()
    world["peer"].calls.clear()
    again = _resolve(client, admin, ac.short_code("F", code))
    assert again.json()["artisan"]["id"] == world["linked_no_code"]
    assert world["peer"].calls == []


def test_the_peer_saying_nothing_is_the_one_404(client, admin, world):
    world["peer"].answers[("POST", "/cards/resolve")] = (200, {"match": None})
    response = _resolve(client, admin, ac.short_code("F", _code("NOBODY" + world["tag"])))
    assert response.status_code == 404
    assert response.json()["detail"] == "No artisan matches this card."


def test_the_peer_unreachable_is_a_503_and_nothing_is_linked(client, admin, world):
    world["peer"].answers[("POST", "/cards/resolve")] = PeerUnavailable(UNAVAILABLE)
    response = _resolve(client, admin, ac.short_code("F", _code("DOWN" + world["tag"])))
    assert response.status_code == 503
    assert response.json()["detail"] == UNAVAILABLE


def test_linking_from_a_card_records_card_and_swaps_codes(client, admin, world):
    code = _code("LINKME" + world["tag"])
    remote_id = f"fr-to-link-{world['tag']}"
    peer = world["peer"]
    peer.answers[("POST", "/cards/resolve")] = (200, {"match": _remote(remote_id, code)})
    peer.answers[("POST", f"/artisans/{remote_id}/links")] = (200, {"linked": True, "cardCode": code})
    response = client.post(
        f"/api/cross-portal/artisans/{world['to_link']}/link",
        json={"consent": True, "cardInput": ac.short_code("F", code), "expectedRemoteArtisanId": remote_id},
        headers=admin,
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["matchedBy"] == "CARD"
    assert body["remoteCardCode"] == code
    link_body = next(b for m, p, b in peer.calls if p == f"/artisans/{remote_id}/links")
    assert link_body["matchedBy"] == "CARD"
    own = client.get(f"/api/artisans/{world['to_link']}/card", headers=admin).json()
    assert link_body["remoteCardCode"] == own["code"]
    # The client's word is not taken: a different expected record is refused.
    peer.calls.clear()
    wrong = client.post(
        f"/api/cross-portal/artisans/{world['to_link']}/link",
        json={"consent": True, "cardInput": ac.short_code("F", code), "expectedRemoteArtisanId": "someone-else"},
        headers=admin,
    )
    assert wrong.status_code == 409


def test_a_d_card_cannot_link_and_a_mistyped_card_is_422(client, admin, world):
    own = client.get(f"/api/artisans/{world['to_link']}/card", headers=admin).json()
    response = client.post(
        f"/api/cross-portal/artisans/{world['to_link']}/link",
        json={"consent": True, "cardInput": own["shortCode"]},
        headers=admin,
    )
    assert response.status_code == 422
    bad = client.post(
        f"/api/cross-portal/artisans/{world['to_link']}/link",
        json={"consent": True, "cardInput": "F-00000-00000-ZZ"},
        headers=admin,
    )
    assert bad.status_code == 422


def test_reissue_pushes_the_new_code_and_a_failed_push_is_resent_on_refresh(client, admin, world):
    peer = world["peer"]
    artisan_id = world["reissued"]
    remote_id = f"fr-reissued-{world['tag']}"
    put_path = f"/artisans/{remote_id}/links/{artisan_id}/card"
    peer.answers[("PUT", put_path)] = (200, {"updated": True})
    first = client.post(f"/api/artisans/{artisan_id}/card/reissue", json={"reason": "DAMAGED"}, headers=admin)
    assert first.status_code == 200, first.text
    assert ("PUT", put_path, {"remoteCardCode": first.json()["code"]}) in peer.calls

    # The Field Repository is down for the next reissue: the card is still issued here.
    peer.calls.clear()
    peer.answers[("PUT", put_path)] = PeerUnavailable(UNAVAILABLE)
    second = client.post(f"/api/artisans/{artisan_id}/card/reissue", json={"reason": "LOST"}, headers=admin)
    assert second.status_code == 200
    new_code = second.json()["code"]

    # The next refresh sends it again, and keeps the code their answer carries.
    peer.calls.clear()
    remote_code = _code("THEIRS" + world["tag"])
    peer.answers[("PUT", put_path)] = (200, {"updated": True})
    peer.answers[("GET", f"/artisans/{remote_id}")] = (
        200,
        {"match": {**_remote(remote_id, remote_code, name=f"Meena reissued {world['tag']}"), "fields": {"name": f"Meena reissued {world['tag']}"}}},
    )
    refreshed = client.post(f"/api/cross-portal/artisans/{artisan_id}/link/refresh", headers=admin)
    assert refreshed.status_code == 200, refreshed.text
    assert ("PUT", put_path, {"remoteCardCode": new_code}) in peer.calls
    assert refreshed.json()["link"]["remoteCardCode"] == remote_code
