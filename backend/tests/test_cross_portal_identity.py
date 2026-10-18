"""C27 on the designer portal: lookup, link, refresh, unlink — and the switch that keeps it all off.

No database and no network: the route module's ``db``, ``get_settings`` and ``peer_call`` are
replaced by fakes; the client's signature check is driven against a fake ``requests``.
"""

from __future__ import annotations

import json
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import cross_portal as routes
from app.services import cross_portal, cross_portal_client
from app.services.artisan_identity import verhoeff_ok

pytestmark = pytest.mark.anyio

TOKEN = "t" * 40
KEY = "k" * 40
URL = "https://api.docs.example/api"


def _valid_aadhaar() -> str:
    for last in range(10):
        candidate = f"23412341234{last}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no check digit")


AADHAAR = _valid_aadhaar()


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def _match(row: dict[str, Any], where: dict[str, Any]) -> bool:
    for key, want in where.items():
        have = row.get(key)
        if isinstance(want, dict) and "gte" in want:
            if have is None or have < want["gte"]:
                return False
        elif have != want:
            return False
    return True


class Table:
    def __init__(self, rows=None):
        self.rows = [dict(r) for r in rows or []]

    async def find_unique(self, where, include=None):
        hit = next((r for r in self.rows if _match(r, where)), None)
        return SimpleNamespace(**hit) if hit else None

    async def find_first(self, where, order=None):
        return await self.find_unique(where)

    async def upsert(self, where, data):
        for row in self.rows:
            if _match(row, where):
                row.update(data["update"])
                return SimpleNamespace(**row)
        row = dict(data["create"])
        row.setdefault("remoteAcknowledgedAt", None)
        row.setdefault("lastRefreshedAt", None)
        self.rows.append(row)
        return SimpleNamespace(**row)

    async def update(self, where, data, include=None):
        for row in self.rows:
            if _match(row, where):
                row.update(data)
                return SimpleNamespace(**row)
        raise AssertionError(where)

    async def delete(self, where):
        self.rows = [r for r in self.rows if not _match(r, where)]

    async def delete_many(self, where):
        self.rows = [r for r in self.rows if not _match(r, where)]

    async def create(self, data):
        row = {"id": f"log{len(self.rows) + 1}", "createdAt": datetime.now(UTC), **data}
        self.rows.append(row)
        return SimpleNamespace(**row)

    async def count(self, where=None):
        return len([r for r in self.rows if _match(r, where or {})])


def _settings(**over):
    base = {
        "cross_portal_identity_enabled": True,
        "cross_portal_token": TOKEN,
        "cross_portal_aadhaar_hmac_key": KEY,
        "field_repository_api_url": URL,
    }
    base.update(over)
    return SimpleNamespace(**base)


REMOTE = {
    "artisanId": "fr1",
    "matchedBy": "AADHAAR",
    "fields": {
        "name": "Meena Devi",
        "localName": "मीना देवी",
        "gender": "Female",
        "dateOfBirth": "1980-05-01T00:00:00",
        "pehchanCardAvailable": True,
        "pehchanCardNumber": "PEH12345",
        "craftStartDate": None,
        "experienceYears": 20,
        "experienceMonths": None,
        "phone": "9999999999",
        "place": "Bhuj",
    },
    "craft": {"code": "bandhani", "name": "Bandhani"},
    "statedAddress": {"state": "Gujarat", "district": "Kachchh", "village": "Nirona"},
}


@pytest.fixture
def world(monkeypatch):
    calls: list[tuple[str, str, Any]] = []
    answers: dict[tuple[str, str], tuple[int, Any]] = {}

    async def fake_peer(settings, method, path, *, body=None, params=None, requested_by=None):
        calls.append((method, path, body))
        asked_by.append(requested_by)
        answer = answers.get((method, path), (200, {"match": REMOTE}))
        if isinstance(answer, Exception):
            raise answer
        return answer

    asked_by: list[Any] = []

    artisan = {
        "id": "dp1",
        "name": "Meena",
        "localName": None,
        "gender": None,
        "dateOfBirth": None,
        "pehchanCardAvailable": False,
        "pehchanCardNumber": None,
        "aadhaarNumber": AADHAAR,
        "createdById": "u1",
        "designWorkshopId": None,
        "status": "APPROVED",
    }
    fake = SimpleNamespace(
        artisan=Table([artisan]),
        craft=Table([{"id": "c-local", "name": "Bandhej", "craftCode": "bandhani"}]),
        crossportalartisanlink=Table(),
        crossportalaccesslog=Table(),
    )

    @asynccontextmanager
    async def tx():
        yield fake

    fake.tx = tx

    async def allowed(*_a, **_k):
        return None

    async def guard(*_a, **_k):
        return True

    monkeypatch.setattr(routes, "db", fake)
    monkeypatch.setattr(routes, "get_settings", lambda: _settings())
    monkeypatch.setattr(routes, "peer_call", fake_peer)
    monkeypatch.setattr(routes, "assert_may_write_a_record_filed_under", allowed)
    monkeypatch.setattr(routes, "guard_record_edit", guard)
    clash_checks: list[Any] = []

    async def no_clash(data, exclude_id=None):
        clash_checks.append((dict(data), exclude_id))

    monkeypatch.setattr(routes, "_guard_identity_conflicts", no_clash)

    async def required(model, record_id):
        row = await model.find_unique(where={"id": record_id})
        if row is None:
            raise HTTPException(status_code=404, detail="Record not found")
        return row

    monkeypatch.setattr(routes, "require_record", required)
    return SimpleNamespace(
        db=fake, calls=calls, answers=answers, asked_by=asked_by, clash_checks=clash_checks
    )


OWNER = SimpleNamespace(id="u1", role="DESIGNER")


async def test_everything_is_404_until_the_url_and_secrets_are_set(world, monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings(field_repository_api_url=None))
    with pytest.raises(HTTPException) as exc:
        await routes.lookup_artisan(routes.LookupIn(aadhaarNumber=AADHAAR, consent=True), OWNER)
    assert exc.value.status_code == 404
    assert await routes.cross_portal_status(OWNER) == {
        "enabled": False,
        "sharedFields": cross_portal.consent_fields_phrase(),
        "coverageEnabled": False,
    }


async def test_lookup_needs_the_artisans_agreement_and_a_number(world):
    with pytest.raises(HTTPException) as exc:
        await routes.lookup_artisan(routes.LookupIn(aadhaarNumber=AADHAAR), OWNER)
    assert exc.value.status_code == 422
    with pytest.raises(HTTPException) as exc:
        await routes.lookup_artisan(routes.LookupIn(consent=True), OWNER)
    assert exc.value.status_code == 422
    assert world.calls == []


async def test_lookup_sends_the_code_never_the_number_and_maps_the_craft(world):
    out = await routes.lookup_artisan(routes.LookupIn(aadhaarNumber=AADHAAR, consent=True), OWNER)
    method, path, body = world.calls[0]
    assert (method, path) == ("POST", "/artisans/lookup")
    assert body == {"aadhaarCode": cross_portal.aadhaar_code(AADHAAR, KEY)}
    assert AADHAAR not in json.dumps(body)
    match = out["match"]
    assert match["remoteArtisanId"] == "fr1"
    assert match["fields"]["name"] == "Meena Devi"
    # Matched on the shared code (C26), so the local spelling is the one offered.
    assert match["craft"]["craftId"] == "c-local" and match["craft"]["craftName"] == "Bandhej"
    assert match["identityFields"] == list(cross_portal.IDENTITY_FIELDS)


async def test_no_match_is_said_as_none(world):
    world.answers[("POST", "/artisans/lookup")] = (200, {"match": None})
    out = await routes.lookup_artisan(
        routes.LookupIn(pehchanCardNumber="peh-1", consent=True), OWNER
    )
    assert out == {"match": None}


async def test_link_re_derives_the_match_from_the_stored_numbers(world):
    out = await routes.link_artisan("dp1", routes.LinkIn(consent=True, expectedRemoteArtisanId="fr1"), OWNER)
    assert out["linked"] is True and out["remoteArtisanId"] == "fr1"
    assert out["remoteAcknowledged"] is True
    lookup = world.calls[0]
    assert lookup[2] == {"aadhaarCode": cross_portal.aadhaar_code(AADHAAR, KEY)}
    assert world.calls[1][:2] == ("POST", "/artisans/fr1/links")
    assert world.db.crossportalartisanlink.rows[0]["consentRecordedById"] == "u1"
    assert world.asked_by == ["u1", "u1"], "the other side's log is told who asked"


async def test_a_link_the_other_side_refuses_is_never_written(world):
    """Consent withdrawn between the lookup and the link: FR answers 409, and DP keeps no row."""
    world.answers[("POST", "/artisans/fr1/links")] = (409, {"linked": False})
    with pytest.raises(HTTPException) as exc:
        await routes.link_artisan("dp1", routes.LinkIn(consent=True), OWNER)
    assert exc.value.status_code == 409
    assert exc.value.detail == routes.CONSENT_WITHDRAWN
    assert world.db.crossportalartisanlink.rows == []
    assert world.db.crossportalaccesslog.rows[-1]["outcome"] == "REFUSED"


async def test_an_unacknowledged_link_is_re_sent_by_the_next_refresh(world):
    world.answers[("POST", "/artisans/fr1/links")] = cross_portal_client.PeerUnavailable("down")
    out = await routes.link_artisan("dp1", routes.LinkIn(consent=True), OWNER)
    assert out["linked"] is True and out["remoteAcknowledged"] is False
    del world.answers[("POST", "/artisans/fr1/links")]
    refreshed = await routes.refresh_artisan("dp1", OWNER)
    assert refreshed["link"]["remoteAcknowledged"] is True
    assert [c[:2] for c in world.calls[-2:]] == [("POST", "/artisans/fr1/links"), ("GET", "/artisans/fr1")]


async def test_a_refresh_whose_resent_link_is_refused_drops_ours(world):
    world.answers[("POST", "/artisans/fr1/links")] = cross_portal_client.PeerUnavailable("down")
    await routes.link_artisan("dp1", routes.LinkIn(consent=True), OWNER)
    world.answers[("POST", "/artisans/fr1/links")] = (409, {"linked": False})
    with pytest.raises(HTTPException) as exc:
        await routes.refresh_artisan("dp1", OWNER)
    assert exc.value.status_code == 409
    assert world.db.crossportalartisanlink.rows == []


async def test_link_refuses_a_mismatch_and_a_missing_agreement(world):
    with pytest.raises(HTTPException) as exc:
        await routes.link_artisan("dp1", routes.LinkIn(consent=True, expectedRemoteArtisanId="other"), OWNER)
    assert exc.value.status_code == 409
    with pytest.raises(HTTPException) as exc:
        await routes.link_artisan("dp1", routes.LinkIn(), OWNER)
    assert exc.value.status_code == 422
    assert world.db.crossportalartisanlink.rows == []


async def test_a_stranger_may_not_link(world, monkeypatch):
    async def no(*_a, **_k):
        return False

    async def tier(*_a, **_k):
        return None

    monkeypatch.setattr(routes, "may_edit_lower_ranked_record", no)
    monkeypatch.setattr(routes, "effective_tier_for_record", tier)
    with pytest.raises(HTTPException) as exc:
        await routes.link_artisan("dp1", routes.LinkIn(consent=True), SimpleNamespace(id="u9", role="RESEARCHER"))
    assert exc.value.status_code == 403


async def test_refresh_copies_only_the_identity_fields(world):
    await routes.link_artisan("dp1", routes.LinkIn(consent=True), OWNER)
    out = await routes.refresh_artisan("dp1", OWNER)
    row = world.db.artisan.rows[0]
    assert row["name"] == "Meena Devi" and row["pehchanCardNumber"] == "PEH12345"
    assert row["dateOfBirth"] == datetime(1980, 5, 1)
    assert "phone" not in row and "place" not in row  # suggested once, never refreshed
    assert set(out["changed"]) <= set(cross_portal.IDENTITY_FIELDS)
    assert out["link"]["lastRefreshedAt"] is not None


async def test_refresh_runs_the_ordinary_edits_helpers(world):
    world.db.artisan.rows[0]["status"] = "NEEDS_REVISION"
    await routes.link_artisan("dp1", routes.LinkIn(consent=True), OWNER)
    await routes.refresh_artisan("dp1", OWNER)
    row = world.db.artisan.rows[0]
    assert row["status"] == "PENDING", "the creator's refresh resubmits a record sent back"
    assert world.clash_checks and world.clash_checks[-1][1] == "dp1"
    assert world.clash_checks[-1][0]["pehchanCardNumber"] == "PEH12345"


async def test_refresh_holds_the_pehchan_pair(world):
    await routes.link_artisan("dp1", routes.LinkIn(consent=True), OWNER)
    broken = {**REMOTE, "fields": {**REMOTE["fields"], "pehchanCardNumber": None}}
    world.answers[("GET", "/artisans/fr1")] = (200, {"match": broken})
    with pytest.raises(HTTPException) as exc:
        await routes.refresh_artisan("dp1", OWNER)
    assert exc.value.status_code == 422
    assert exc.value.detail == routes.REFRESH_PEHCHAN
    assert world.db.artisan.rows[0]["name"] == "Meena", "nothing was written"


async def test_every_lookup_is_logged_and_limited_per_hour(world):
    out = await routes.lookup_artisan(routes.LookupIn(aadhaarNumber=AADHAAR, consent=True), OWNER)
    assert out["match"]
    log = world.db.crossportalaccesslog.rows
    assert [(r["userId"], r["action"], r["outcome"], r["remoteArtisanId"]) for r in log] == [
        ("u1", "LOOKUP", "MATCH", "fr1")
    ]
    assert AADHAAR not in json.dumps(log, default=str)
    assert cross_portal.aadhaar_code(AADHAAR, KEY) not in json.dumps(log, default=str)
    now = datetime.now(UTC)
    world.db.crossportalaccesslog.rows = [
        {"userId": "u1", "action": "LOOKUP", "outcome": "NO_MATCH", "createdAt": now}
        for _ in range(routes.LOOKUPS_PER_HOUR)
    ] + [{"userId": "u1", "action": "LOOKUP", "outcome": "NO_MATCH", "createdAt": now - timedelta(hours=2)}]
    with pytest.raises(HTTPException) as exc:
        await routes.lookup_artisan(routes.LookupIn(aadhaarNumber=AADHAAR, consent=True), OWNER)
    assert exc.value.status_code == 429
    # Another account is not held to this one's count.
    other = SimpleNamespace(id="u2", role="DESIGNER")
    assert (await routes.lookup_artisan(routes.LookupIn(aadhaarNumber=AADHAAR, consent=True), other))["match"]


async def test_refresh_when_sharing_was_withdrawn_is_a_409(world):
    await routes.link_artisan("dp1", routes.LinkIn(consent=True), OWNER)
    world.answers[("GET", "/artisans/fr1")] = (404, {"match": None})
    with pytest.raises(HTTPException) as exc:
        await routes.refresh_artisan("dp1", OWNER)
    assert exc.value.status_code == 409


async def test_unlink_tells_the_other_side_and_removes_the_row(world):
    await routes.link_artisan("dp1", routes.LinkIn(consent=True), OWNER)
    assert await routes.unlink_artisan("dp1", OWNER) == {"linked": False}
    assert world.db.crossportalartisanlink.rows == []
    assert world.calls[-1][:2] == ("DELETE", "/artisans/fr1/links/dp1")


# ── the client refuses an unsigned answer ─────────────────────────────────────────────────────


class _Response:
    def __init__(self, status, body: bytes, signature: str | None):
        self.status_code = status
        self.content = body
        self.headers = {cross_portal.SIGNATURE_HEADER: signature} if signature else {}


def test_the_client_accepts_only_an_answer_signed_for_its_own_request(monkeypatch):
    body = b'{"match":null}'
    sent: dict[str, Any] = {}

    def good(method, url, headers, data, params, timeout):
        nonce = headers[cross_portal.NONCE_HEADER]
        sent.update(url=url, auth=headers["Authorization"], nonce=nonce, by=headers.get(cross_portal.REQUESTED_BY_HEADER))
        signature = cross_portal.sign(
            TOKEN, body, method=method, path="/api/cross-portal/peer/artisans/lookup", nonce=nonce
        )
        return _Response(200, body, signature)

    monkeypatch.setattr(cross_portal_client.requests, "request", good)
    assert cross_portal_client._call_sync(_settings(), "POST", "/artisans/lookup", {}, None, "u1") == (
        200,
        {"match": None},
    )
    assert sent["url"] == f"{URL}/cross-portal/peer/artisans/lookup"
    assert sent["auth"] == f"Bearer {TOKEN}"
    assert len(sent["nonce"]) >= cross_portal.MIN_NONCE_LENGTH and sent["by"] == "u1"

    def forged(method, url, headers, data, params, timeout):
        nonce = headers[cross_portal.NONCE_HEADER]
        return _Response(200, body, cross_portal.sign("x" * 40, body, method=method, path=url, nonce=nonce))

    monkeypatch.setattr(cross_portal_client.requests, "request", forged)
    with pytest.raises(cross_portal_client.PeerUnavailable):
        cross_portal_client._call_sync(_settings(), "POST", "/artisans/lookup", {}, None, None)

    captured = cross_portal.sign(TOKEN, body, method="POST", path="/cross-portal/peer/artisans/lookup", nonce="a" * 32)

    def replayed(method, url, headers, data, params, timeout):
        return _Response(200, body, captured)

    monkeypatch.setattr(cross_portal_client.requests, "request", replayed)
    with pytest.raises(cross_portal_client.PeerUnavailable):
        cross_portal_client._call_sync(_settings(), "POST", "/artisans/lookup", {}, None, None)


def test_the_clients_fall_back_to_the_servers_words():
    import pathlib

    root = pathlib.Path(__file__).resolve().parents[2]
    phrase = cross_portal.consent_fields_phrase()
    web = (root / "frontend/lib/crossPortal.ts").read_text(encoding="utf-8")
    kotlin = (
        root / "android/app/src/main/java/com/designprototype/workshop/data/CrossPortalApi.kt"
    ).read_text(encoding="utf-8")
    assert f'"{phrase}"' in web
    assert f'"{phrase}"' in kotlin


def test_the_config_defaults_to_off():
    from app.core.config import Settings

    fields = Settings.model_fields
    assert fields["cross_portal_identity_enabled"].default is False
    assert fields["cross_portal_token"].default is None
    assert fields["field_repository_api_url"].default is None


def test_the_shared_module_is_the_same_bytes_in_both_portals():
    import pathlib

    root = pathlib.Path(__file__).resolve().parents[2]
    for candidate in (root.parent / root.name.replace("dp-", "doc-", 1), pathlib.Path("C:/dev/IIT/documentation-portal")):
        other = candidate / "backend/app/services/cross_portal.py"
        if other.is_file():
            mine = (root / "backend/app/services/cross_portal.py").read_text(encoding="utf-8")
            assert mine.replace("\r\n", "\n") == other.read_text(encoding="utf-8").replace("\r\n", "\n")
            return
    pytest.skip("no sibling checkout")
