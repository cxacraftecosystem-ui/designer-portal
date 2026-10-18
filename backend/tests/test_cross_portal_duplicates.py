"""The duplicate finder's Field Repository half, with the peer mocked.

The daily scan sends exact keys only and stores only the remote id; Link goes through the existing
link service; a refresh follows a Field Repository merge (RELINK); merging a linked record moves
the link at the peer first and puts it back when the local transaction fails.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.core.db import db
from app.services import artisan_duplicates, artisan_merge, cross_portal_client
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
    def __init__(self, answers: dict[tuple[str, str], tuple[int, Any]] | None = None, default=(200, {})):
        self.calls: list[tuple[str, str, Any, Any]] = []
        self.answers = answers or {}
        self.default = default

    async def __call__(self, settings, method, path, *, body=None, params=None, requested_by=None):
        self.calls.append((method, path, body, requested_by))
        answer = self.answers.get((method, path))
        if callable(answer):
            return answer(body)
        return answer or self.default


def test_the_scan_stores_only_the_remote_id_and_pairs_two_locals_on_one_remote(monkeypatch):
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        a = await ds.artisan(user, f"Scan A {t}", aadhaarFingerprint=ds.fp("sa" + t))
        b = await ds.artisan(user, f"Scan B {t}", pehchanCardNumber=f"SB{t}")
        c = await ds.artisan(user, f"Scan C {t}", aadhaarFingerprint=ds.fp("sc" + t))

        def match(body):
            refs = {item["ref"] for item in body["items"]}
            hits = [{"ref": a.id, "artisanId": f"fr-{t}", "matchedBy": "AADHAAR"},
                    {"ref": b.id, "artisanId": f"fr-{t}", "matchedBy": "PEHCHAN"}]
            return 200, {"matches": [h for h in hits if h["ref"] in refs]}

        peer = Peer({("POST", "/artisans/match"): match})
        monkeypatch.setattr(cross_portal_client, "peer_call", peer)
        await artisan_duplicates.run_peer_scan(SETTINGS)
        for method, path, body, requested_by in peer.calls:
            assert (method, path, requested_by) == ("POST", "/artisans/match", "system:duplicate-scan")
            assert len(body["items"]) <= artisan_duplicates.PEER_MATCH_BATCH
            for item in body["items"]:
                assert set(item) == {"ref", "pehchanCardNumber", "aadhaarCode"}
        rows = await db.artisanduplicatecandidate.find_many(where={"kind": "CROSS_PORTAL", "artisanAId": {"in": [a.id, b.id, c.id]}})
        assert {(r.artisanAId, r.remoteArtisanId, r.remoteMatchedBy) for r in rows} == {
            (a.id, f"fr-{t}", "AADHAAR"), (b.id, f"fr-{t}", "PEHCHAN")}
        assert all(r.score == 1.0 and r.band == "VERY_LIKELY" and r.artisanBId is None for r in rows)
        within = await db.artisanduplicatecandidate.find_first(where={"kind": "WITHIN", "artisanAId": min(a.id, b.id), "artisanBId": max(a.id, b.id)})
        assert within is not None and within.reasons[0]["code"] == "SAME_FIELD_REPOSITORY_LINK"
        # A decided pair keeps its decision when the scan runs again.
        await db.artisanduplicatecandidate.update(where={"id": rows[0].id}, data={"status": "NOT_DUPLICATE"})
        await artisan_duplicates.run_peer_scan(SETTINGS)
        assert (await db.artisanduplicatecandidate.find_unique(where={"id": rows[0].id})).status == "NOT_DUPLICATE"

    run(scenario)


def test_the_scan_skips_a_decided_artisan_and_a_restart_does_not_ask_again(monkeypatch):
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        a = await ds.artisan(user, f"Skip A {t}", aadhaarFingerprint=ds.fp("ka" + t))

        def match(body):
            refs = {item["ref"] for item in body["items"]}
            return 200, {"matches": [{"ref": a.id, "artisanId": f"fk-{t}", "matchedBy": "AADHAAR"}] if a.id in refs else []}

        peer = Peer({("POST", "/artisans/match"): match})
        monkeypatch.setattr(cross_portal_client, "peer_call", peer)
        await artisan_duplicates.run_peer_scan(SETTINGS)
        assert len(peer.calls) <= artisan_duplicates.PEER_SCAN_MAX_CALLS
        asked = await db.query_raw('SELECT "askedAt" FROM "ArtisanPeerScan" WHERE "artisanId" = $1', a.id)
        assert len(asked) == 1
        pair = await db.artisanduplicatecandidate.find_first(where={"kind": "CROSS_PORTAL", "artisanAId": a.id})
        await db.artisanduplicatecandidate.update(where={"id": pair.id}, data={"status": "NOT_DUPLICATE"})
        peer.calls.clear()
        await artisan_duplicates.run_peer_scan(SETTINGS)
        sent = {item["ref"] for _, _, body, _ in peer.calls for item in body["items"]}
        assert a.id not in sent

        # A restarted worker sees today's ask and waits a day.
        ran: list[Any] = []

        async def fake_scan(settings):
            ran.append(settings)
            return {"within": 0, "crossPortal": 0}

        monkeypatch.setattr(artisan_duplicates, "_next_run", None)
        monkeypatch.setattr(artisan_duplicates, "run_scan", fake_scan)
        assert await artisan_duplicates.maybe_run(SETTINGS) is None
        assert ran == []

    run(scenario)


def test_link_marks_the_pair_linked_through_the_link_service(monkeypatch):
    async def scenario():
        from app.api.routes import artisan_duplicates as routes
        from app.api.routes import cross_portal as cp
        from app.schemas.duplicates import PairLinkIn

        t = ds.tag()
        user = await ds.admin(t)
        a = await ds.artisan(user, f"Link A {t}", aadhaarFingerprint=ds.fp("la" + t))
        await artisan_duplicates.upsert_cross(a.id, f"frl-{t}", "AADHAAR")
        pair = await db.artisanduplicatecandidate.find_first(where={"artisanAId": a.id, "kind": "CROSS_PORTAL"})
        peer = Peer({("POST", "/artisans/lookup"): (200, {"match": {"artisanId": f"frl-{t}", "matchedBy": "AADHAAR"}})})
        monkeypatch.setattr(cp, "peer_call", peer)
        monkeypatch.setattr(cp, "get_settings", lambda: SETTINGS)
        with pytest.raises(HTTPException):
            await routes.link_pair(pair.id, PairLinkIn(consent=False), current_user=user)
        result = await routes.link_pair(pair.id, PairLinkIn(consent=True), current_user=user)
        assert result["pair"]["status"] == "LINKED" and result["link"]["linked"] is True
        link = await db.crossportalartisanlink.find_unique(where={"artisanId": a.id})
        assert link.remoteArtisanId == f"frl-{t}"

    run(scenario)


def test_refresh_follows_a_field_repository_merge(monkeypatch):
    async def scenario():
        from app.api.routes import cross_portal as cp

        t = ds.tag()
        user = await ds.admin(t)
        a = await ds.artisan(user, f"Relink {t}")
        await db.crossportalartisanlink.create(data={
            "artisanId": a.id, "remoteArtisanId": f"old-{t}", "matchedBy": "PEHCHAN",
            "remoteAcknowledgedAt": "2026-10-01T00:00:00Z",
        })
        peer = Peer({("GET", f"/artisans/old-{t}"): (200, {"match": {"artisanId": f"new-{t}", "fields": {}}})})
        monkeypatch.setattr(cp, "peer_call", peer)
        monkeypatch.setattr(cp, "get_settings", lambda: SETTINGS)
        result = await cp.refresh_artisan(a.id, current_user=user)
        assert result["link"]["remoteArtisanId"] == f"new-{t}"
        log = await db.crossportalaccesslog.find_first(where={"artisanId": a.id, "action": "RELINK"})
        assert log is not None and log.remoteArtisanId == f"new-{t}"

    run(scenario)


def test_merging_a_linked_record_moves_the_link_at_the_peer_and_compensates(monkeypatch):
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        keep = await ds.artisan(user, f"Moved {t}")
        gone = await ds.artisan(user, f"Moved {t}")
        await db.crossportalartisanlink.create(data={"artisanId": gone.id, "remoteArtisanId": f"R-{t}", "matchedBy": "AADHAAR"})
        pair = await ds.make_pair(keep.id, gone.id)
        survivor = ds.sides(pair, keep.id)

        # The local transaction fails: both peer calls are reversed.
        peer = Peer(default=(200, {}))
        monkeypatch.setattr(cross_portal_client, "peer_call", peer)
        real_tx = artisan_merge._merge_tx

        async def boom(*args, **kwargs):
            raise RuntimeError("database went away")

        monkeypatch.setattr(artisan_merge, "_merge_tx", boom)
        with pytest.raises(RuntimeError):
            await artisan_merge.merge(pair, survivor=survivor, choices={}, expected_updated_at=None, reason=None, user=user, settings=SETTINGS)
        assert [(m, p, b) for m, p, b, _ in peer.calls] == [
            ("POST", f"/artisans/R-{t}/links", {"remoteArtisanId": keep.id, "matchedBy": "AADHAAR"}),
            ("DELETE", f"/artisans/R-{t}/links/{gone.id}", None),
            ("POST", f"/artisans/R-{t}/links", {"remoteArtisanId": gone.id, "matchedBy": "AADHAAR"}),
            ("DELETE", f"/artisans/R-{t}/links/{keep.id}", None),
        ]
        assert (await db.crossportalartisanlink.find_unique(where={"artisanId": gone.id})) is not None

        # Then it succeeds: the link is on the survivor, and undo moves it back at the peer first.
        monkeypatch.setattr(artisan_merge, "_merge_tx", real_tx)
        peer.calls.clear()
        result = await artisan_merge.merge(pair, survivor=survivor, choices={}, expected_updated_at=None, reason=None, user=user, settings=SETTINGS)
        assert (await db.crossportalartisanlink.find_unique(where={"artisanId": keep.id})).remoteArtisanId == f"R-{t}"
        assert [(m, p) for m, p, _, _ in peer.calls] == [("POST", f"/artisans/R-{t}/links"), ("DELETE", f"/artisans/R-{t}/links/{gone.id}")]
        peer.calls.clear()
        await artisan_merge.undo(result["merge"]["id"], user=user, settings=SETTINGS)
        assert [(m, p) for m, p, _, _ in peer.calls] == [("POST", f"/artisans/R-{t}/links"), ("DELETE", f"/artisans/R-{t}/links/{keep.id}")]
        assert (await db.crossportalartisanlink.find_unique(where={"artisanId": gone.id})).remoteArtisanId == f"R-{t}"

        # An unreachable peer is a 503 and nothing moves.
        from app.services.cross_portal_client import PeerUnavailable

        async def down(*args, **kwargs):
            raise PeerUnavailable("down")

        monkeypatch.setattr(cross_portal_client, "peer_call", down)
        pair = await ds.make_pair(keep.id, gone.id)
        with pytest.raises(HTTPException) as caught:
            await artisan_merge.merge(pair, survivor=ds.sides(pair, keep.id), choices={}, expected_updated_at=None, reason=None, user=user, settings=SETTINGS)
        assert caught.value.status_code == 503
        assert await db.artisan.find_unique(where={"id": gone.id}) is not None

    run(scenario)


def test_two_records_linked_to_different_remotes_are_never_merged(monkeypatch):
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        a = await ds.artisan(user, f"Conflict {t}")
        b = await ds.artisan(user, f"Conflict {t}")
        await db.crossportalartisanlink.create(data={"artisanId": a.id, "remoteArtisanId": f"X-{t}", "matchedBy": "AADHAAR"})
        await db.crossportalartisanlink.create(data={"artisanId": b.id, "remoteArtisanId": f"Y-{t}", "matchedBy": "AADHAAR"})
        pair = await ds.make_pair(a.id, b.id)
        monkeypatch.setattr(cross_portal_client, "peer_call", Peer())
        with pytest.raises(HTTPException) as caught:
            await artisan_merge.merge(pair, survivor="A", choices={}, expected_updated_at=None, reason=None, user=user, settings=SETTINGS)
        assert caught.value.status_code == 409
        assert caught.value.detail["blockers"][0]["code"] == "cross_portal_conflict"

    run(scenario)
