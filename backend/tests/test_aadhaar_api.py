"""/api/aadhaar end to end against a LOCAL database: the flags, consent, permissions, what is stored,
the admin decision, the audit trail and the OTP throttle.

Pattern: ``tests/test_workshop_join_sync.py`` — seed in a private ``asyncio.run``, then drive a
``TestClient``. Stored values are read back through the API wherever one exposes the row (an admin's
``GET /users``, ``GET /artisans/{id}``, ``GET /data-access/revisions``), and through a PRIVATE
``Prisma()`` client for the two tables no route reads, so the shared client is never touched from a
second event loop.

Every document is signed with a TEST key that the ``offline_on`` fixture adds to the trusted list.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import random
import uuid
from typing import Any

import pytest
from aadhaar_support import (
    TEST_LAST4,
    TEST_NAME,
    FakeAsa,
    build_secure_qr,
    make_identity,
    offline_xml,
    reference_id,
    zipcrypto_zip,
)

from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.aadhaar import flags
from app.services.aadhaar.records import ARTISAN_COLUMNS, USER_COLUMNS

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
    ),
]

UIDAI_TEST = make_identity("TEST UIDAI offline signer (api)")


def _number(last4: str) -> str:
    return "9" + "".join(random.choice("0123456789") for _ in range(7)) + last4


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    tag = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            def user(role: str, name: str) -> Any:
                return db.user.create(
                    data={
                        "email": f"aadhaar-{role.lower()}-{uuid.uuid4().hex[:6]}-{tag}@example.org",
                        "name": name,
                        "role": role,
                        "passwordHash": hash_password("unused"),
                    }
                )

            admin = await user("ADMIN", "Aadhaar Admin")
            admin2 = await user("ADMIN", "Second Admin")
            researcher = await user("RESEARCHER", TEST_NAME)
            volunteer = await user("CROWDSOURCE_VOLUNTEER", "Sunita Sharma")
            other = await user("RESEARCHER", "Another Researcher")

            from app.services import aadhaar_fingerprint

            async def artisan(name: str, number: str | None) -> str:
                row = await db.artisan.create(
                    data={
                        "name": name,
                        "place": "Bagru",
                        "createdById": researcher.id,
                        # As the register holds an Aadhaar: the keyed fingerprint and the last four
                        # (the full-number column was dropped on 2026-10-19).
                        "aadhaarFingerprint": aadhaar_fingerprint.fingerprint_of(number) if number else None,
                        "aadhaarLast4": number[-4:] if number else None,
                    }
                )
                return row.id

            return {
                "admin": admin,
                "admin2": admin2,
                "researcher": researcher,
                "volunteer": volunteer,
                "other": other,
                "art_match": await artisan(TEST_NAME, _number(TEST_LAST4)),
                "art_wrong_number": await artisan(TEST_NAME, _number("9999")),
                "art_mismatch": await artisan(f"Meera Joshi {tag}", None),
                "tag": tag,
            }
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


def _h(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(user.id)}"}


@pytest.fixture
def offline_on(monkeypatch, tmp_path):
    settings = get_settings()
    monkeypatch.setattr(settings, "aadhaar_offline_verification_enabled", True)
    monkeypatch.setattr(
        settings, "aadhaar_extra_trusted_certs", UIDAI_TEST.write_pem(tmp_path, "uidai-test")
    )
    monkeypatch.setattr(settings, "aadhaar_offline_xml_max_age_days", 3.0)


def _qr_body(**kwargs: Any) -> dict[str, Any]:
    return {"consent": True, "qrText": build_secure_qr(UIDAI_TEST.key, **kwargs)}


def _zip_body(share: str = "4821", typed_share: str | None = None, **kwargs: Any) -> dict[str, Any]:
    archive = zipcrypto_zip("offline.xml", offline_xml(UIDAI_TEST, share_code=share, **kwargs), share)
    return {
        "consent": True,
        "zipBase64": base64.b64encode(archive).decode(),
        "shareCode": typed_share or share,
    }


def _admin_view_of(world: dict[str, Any], user: Any) -> dict[str, Any]:
    response = world["client"].get(
        "/api/users", params={"search": user.email}, headers=_h(world["admin"])
    )
    assert response.status_code == 200, response.text
    (row,) = response.json()["items"]
    return row


# ── off means absent ─────────────────────────────────────────────────────────────────────────

HIDDEN_ROUTES = [
    ("get", "/api/aadhaar/self"),
    ("post", "/api/aadhaar/self/secure-qr"),
    ("post", "/api/aadhaar/self/offline-xml"),
    ("delete", "/api/aadhaar/self"),
    ("post", "/api/aadhaar/self/online/otp"),
    ("post", "/api/aadhaar/self/online/verify"),
    ("get", "/api/aadhaar/artisans/x"),
    ("post", "/api/aadhaar/artisans/x/secure-qr"),
    ("post", "/api/aadhaar/artisans/x/offline-xml"),
    ("post", "/api/aadhaar/artisans/x/online/otp"),
    ("post", "/api/aadhaar/artisans/x/decision"),
    ("post", "/api/aadhaar/users/x/decision"),
]


def test_with_both_flags_off_every_route_is_a_404_even_unauthenticated(world):
    client = world["client"]
    for method, path in HIDDEN_ROUTES:
        anonymous = getattr(client, method)(path, **({"json": {}} if method == "post" else {}))
        signed_in = getattr(client, method)(
            path, headers=_h(world["admin"]), **({"json": {"junk": 1}} if method == "post" else {})
        )
        assert anonymous.status_code == 404, (method, path, anonymous.status_code)
        assert signed_in.status_code == 404, (method, path, signed_in.status_code)


def test_with_both_flags_off_no_payload_carries_a_verification_column(world):
    client = world["client"]
    me = client.get("/api/me", headers=_h(world["researcher"])).json()
    assert me["features"] == {
        "aadhaarOfflineVerification": False,
        "aadhaarOnlineAuth": False,
        "ministryExports": False,
    }
    assert not [key for key in me if key.startswith(flags.COLUMN_PREFIX)]
    assert client.get("/api/features", headers=_h(world["researcher"])).json() == me["features"]
    artisan = client.get(f"/api/artisans/{world['art_match']}", headers=_h(world["admin"])).json()
    assert not [key for key in artisan if key.startswith(flags.COLUMN_PREFIX)]
    listing = _admin_view_of(world, world["researcher"])
    assert not [key for key in listing if key.startswith(flags.COLUMN_PREFIX)]


def test_online_routes_stay_hidden_while_only_offline_is_on(world, offline_on):
    response = world["client"].post(
        "/api/aadhaar/self/online/otp", json={}, headers=_h(world["researcher"])
    )
    assert response.status_code == 404
    features = world["client"].get("/api/features", headers=_h(world["researcher"])).json()
    assert features == {"aadhaarOfflineVerification": True, "aadhaarOnlineAuth": False, "ministryExports": False}


# ── consent, verification, storage ───────────────────────────────────────────────────────────


def test_consent_is_required_before_anything_is_read(world, offline_on):
    body = _qr_body()
    body["consent"] = False
    response = world["client"].post(
        "/api/aadhaar/self/secure-qr", json=body, headers=_h(world["researcher"])
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "consent_required"
    me = world["client"].get("/api/aadhaar/self", headers=_h(world["researcher"])).json()
    assert me["status"] is None


def test_self_secure_qr_verifies_and_stores_only_the_permitted_columns(world, offline_on):
    client = world["client"]
    response = client.post(
        "/api/aadhaar/self/secure-qr", json=_qr_body(), headers=_h(world["researcher"])
    )
    assert response.status_code == 200, response.text
    summary = response.json()
    assert summary["status"] == "VERIFIED" and summary["verified"] is True
    assert summary["method"] == "SECURE_QR" and summary["last4"] == TEST_LAST4
    assert summary["birthYear"] == 1987 and summary["gender"] == "F"
    assert summary["dateOfBirth"] is None  # an account keeps the year only

    row = _admin_view_of(world, world["researcher"])
    stored = {key: value for key, value in row.items() if key.startswith(flags.COLUMN_PREFIX)}
    assert set(stored) == set(USER_COLUMNS)
    assert stored["aadhaarVerifyName"] == TEST_NAME
    assert stored["aadhaarVerifyLast4"] == TEST_LAST4
    assert stored["aadhaarVerifyNameMatch"] is True
    blob = json.dumps(row)
    for leak in ("Bagru", "303007", "Prajapati Ramesh", "W/O", "6789", "jP  "):
        assert leak not in blob, leak
    me = client.get("/api/me", headers=_h(world["researcher"])).json()
    assert me["aadhaarVerifyStatus"] == "VERIFIED"


def test_self_offline_zip_with_mobile_check(world, offline_on):
    client = world["client"]
    body = _zip_body()
    body["mobile"] = "9876543210"
    response = client.post("/api/aadhaar/self/offline-xml", json=body, headers=_h(world["researcher"]))
    assert response.status_code == 200, response.text
    assert response.json()["method"] == "OFFLINE_XML"


@pytest.mark.parametrize(
    ("body_kwargs", "code"),
    [
        ({"typed_share": "1111"}, "share_code_wrong"),
        ({"ref": None, "stale": True}, "expired"),
    ],
)
def test_offline_zip_refusals(world, offline_on, body_kwargs, code):
    from datetime import UTC, datetime, timedelta

    if body_kwargs.pop("stale", False):
        body_kwargs["ref"] = reference_id(generated=datetime.now(UTC) - timedelta(days=5))
    response = world["client"].post(
        "/api/aadhaar/self/offline-xml", json=_zip_body(**body_kwargs), headers=_h(world["researcher"])
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == code


def test_tampered_qr_is_refused_and_changes_nothing(world, offline_on):
    text = build_secure_qr(make_identity("not UIDAI").key)
    response = world["client"].post(
        "/api/aadhaar/self/secure-qr", json={"consent": True, "qrText": text},
        headers=_h(world["researcher"]),
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "signature_invalid"
    assert world["client"].get(
        "/api/aadhaar/self", headers=_h(world["researcher"])
    ).json()["status"] == "VERIFIED"


# ── name mismatch and the admin's decision ───────────────────────────────────────────────────


def test_name_mismatch_waits_for_an_admin(world, offline_on):
    client = world["client"]
    volunteer = world["volunteer"]
    response = client.post("/api/aadhaar/self/secure-qr", json=_qr_body(), headers=_h(volunteer))
    assert response.status_code == 200
    assert response.json()["status"] == "NAME_MISMATCH"
    assert response.json()["name"] == TEST_NAME and response.json()["recordName"] == "Sunita Sharma"

    path = f"/api/aadhaar/users/{volunteer.id}/decision"
    assert client.post(path, json={"accept": True}, headers=_h(volunteer)).status_code == 403
    assert client.post(path, json={"accept": True}, headers=_h(world["researcher"])).status_code == 403
    decided = client.post(path, json={"accept": True}, headers=_h(world["admin"]))
    assert decided.status_code == 200 and decided.json()["status"] == "VERIFIED"
    assert client.post(path, json={"accept": False}, headers=_h(world["admin"])).status_code == 409


def test_an_admin_cannot_decide_their_own_mismatch(world, offline_on):
    client = world["client"]
    admin = world["admin2"]
    assert client.post(
        "/api/aadhaar/self/secure-qr", json=_qr_body(), headers=_h(admin)
    ).json()["status"] == "NAME_MISMATCH"
    path = f"/api/aadhaar/users/{admin.id}/decision"
    assert client.post(path, json={"accept": True}, headers=_h(admin)).status_code == 403
    rejected = client.post(path, json={"accept": False}, headers=_h(world["admin"]))
    assert rejected.json()["status"] == "REJECTED"


def test_withdrawing_clears_every_column(world, offline_on):
    client = world["client"]
    other = world["other"]
    client.post("/api/aadhaar/self/secure-qr", json=_qr_body(), headers=_h(other))
    response = client.delete("/api/aadhaar/self", headers=_h(other))
    assert response.status_code == 200 and response.json()["status"] is None
    row = _admin_view_of(world, other)
    assert all(row[column] is None for column in USER_COLUMNS)


# ── artisans ─────────────────────────────────────────────────────────────────────────────────


def test_only_somebody_who_may_edit_the_artisan_may_verify_it(world, offline_on):
    client = world["client"]
    path = f"/api/aadhaar/artisans/{world['art_match']}/secure-qr"
    assert client.post(path, json=_qr_body(), headers=_h(world["volunteer"])).status_code == 403
    assert client.post(path, json=_qr_body(), headers=_h(world["other"])).status_code == 403
    response = client.post(path, json=_qr_body(), headers=_h(world["researcher"]))
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "VERIFIED"
    assert response.json()["dateOfBirth"] == "1987-05-07"  # an artisan row keeps the date

    # The record payloads never carry the result — not even to an admin; the verification has its
    # own read, which tells a reader outside the record's circle only THAT it is verified.
    artisan = client.get(f"/api/artisans/{world['art_match']}", headers=_h(world["admin"])).json()
    assert not [key for key in artisan if key.startswith(flags.COLUMN_PREFIX)]
    path = f"/api/aadhaar/artisans/{world['art_match']}"
    full = client.get(path, headers=_h(world["researcher"])).json()
    assert full["name"] == TEST_NAME and full["last4"] == TEST_LAST4
    limited = client.get(path, headers=_h(world["volunteer"])).json()
    assert limited == {key: full[key] for key in ("status", "verified", "method", "verifiedAt")}
    # The record's own dedup number is untouched and still masked by the existing rules.
    viewer = client.get(f"/api/artisans/{world['art_match']}", headers=_h(world["volunteer"])).json()
    assert viewer["aadhaarNumber"].startswith("XXXX XXXX ")


def test_a_card_for_a_different_aadhaar_is_refused(world, offline_on):
    response = world["client"].post(
        f"/api/aadhaar/artisans/{world['art_wrong_number']}/secure-qr",
        json=_qr_body(),
        headers=_h(world["researcher"]),
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "last4_mismatch"


def test_artisan_name_mismatch_is_decided_by_an_admin(world, offline_on):
    client = world["client"]
    artisan_id = world["art_mismatch"]
    response = client.post(
        f"/api/aadhaar/artisans/{artisan_id}/offline-xml", json=_zip_body(),
        headers=_h(world["researcher"]),
    )
    assert response.json()["status"] == "NAME_MISMATCH"
    path = f"/api/aadhaar/artisans/{artisan_id}/decision"
    assert client.post(path, json={"accept": True}, headers=_h(world["researcher"])).status_code == 403
    assert client.post(path, json={"accept": True}, headers=_h(world["admin"])).json()["verified"]


def test_every_attempt_is_audited_without_personal_data(world, offline_on):
    response = world["client"].get(
        "/api/data-access/revisions",
        params={"recordType": "artisan", "recordId": world["art_wrong_number"]},
        headers=_h(world["admin"]),
    )
    entries = [row["changes"]["aadhaarVerification"] for row in response.json()]
    assert entries and entries[-1]["outcome"] == "REFUSED"
    assert entries[-1]["code"] == "last4_mismatch"
    # The entries carry no name, date or number — and the editor embedded beside them (whose own
    # account is verified) carries no verification columns either.
    assert TEST_NAME not in json.dumps(entries) and "1987" not in json.dumps(entries)
    editor = response.json()[-1]["editedBy"]
    assert not [key for key in editor if key.startswith(flags.COLUMN_PREFIX)]


# ── online OTP through a fake ASA ────────────────────────────────────────────────────────────


@pytest.fixture
def online_on(monkeypatch, tmp_path):
    from test_aadhaar_online import TEST_UID, _config

    from app.services.aadhaar.online import service

    keys = {
        "aua": make_identity("TEST AUA (api)"),
        "enc": make_identity("TEST UIDAI enc (api)"),
        "sig": make_identity("TEST UIDAI sig (api)"),
    }
    fake = FakeAsa(aua=keys["aua"], uidai_encryption=keys["enc"], uidai_signing=keys["sig"])
    config = _config(keys, limit_per_user=2, limit_per_uid=10)
    monkeypatch.setattr(get_settings(), "aadhaar_online_auth_enabled", True)
    monkeypatch.setattr(service, "transport", fake.transport())
    monkeypatch.setattr(service, "load_online_config", lambda: config)
    return {"fake": fake, "uid": TEST_UID}


def test_online_otp_verifies_an_account_and_throttles(world, online_on):
    client = world["client"]
    other = world["other"]
    uid = online_on["uid"]
    issued = client.post(
        "/api/aadhaar/self/online/otp", json={"consent": True, "uid": uid}, headers=_h(other)
    )
    assert issued.status_code == 200, issued.text
    assert uid not in issued.text
    body = {"consent": True, "uid": uid, "otp": "123456", **{k: issued.json()[k] for k in ("txn", "token")}}
    verified = client.post("/api/aadhaar/self/online/verify", json=body, headers=_h(other))
    assert verified.status_code == 200, verified.text
    assert verified.json()["status"] == "VERIFIED" and verified.json()["method"] == "ONLINE_OTP"
    assert verified.json()["last4"] == uid[-4:]

    second = client.post(
        "/api/aadhaar/self/online/otp", json={"consent": True, "uid": uid}, headers=_h(other)
    )
    assert second.status_code == 200
    third = client.post(
        "/api/aadhaar/self/online/otp", json={"consent": True, "uid": uid}, headers=_h(other)
    )
    assert third.status_code == 429
    assert third.json()["detail"]["code"] == "rate_limited_user"


def test_nothing_identifying_reaches_the_throttle_or_the_ledger(world):
    """Read back with a PRIVATE client, after every request above has finished."""
    from prisma import Prisma

    async def read() -> tuple[list[Any], list[Any], Any]:
        private = Prisma()
        await private.connect()
        try:
            throttles = await private.aadhaarotpthrottle.find_many(
                where={"userId": world["other"].id}
            )
            ledger = await private.recordrevision.find_many(
                where={"recordType": "user", "recordId": world["researcher"].id}
            )
            artisan = await private.artisan.find_unique(where={"id": world["art_match"]})
            return throttles, ledger, artisan
        finally:
            await private.disconnect()

    throttles, ledger, artisan = asyncio.run(read())
    columns = {
        key: value
        for key, value in artisan.model_dump().items()
        if key.startswith(flags.COLUMN_PREFIX)
    }
    assert set(columns) == set(ARTISAN_COLUMNS)
    assert columns["aadhaarVerifyLast4"] == TEST_LAST4
    assert columns["aadhaarVerifyDob"].date().isoformat() == "1987-05-07"
    assert not any(
        isinstance(value, str) and len(value) >= 12 and value.isdigit() for value in columns.values()
    )
    from test_aadhaar_online import TEST_UID

    assert throttles and all(len(row.uidHash) == 64 and TEST_UID not in row.uidHash for row in throttles)
    outcomes = [row.changes["aadhaarVerification"]["outcome"] for row in ledger]
    assert "REFUSED" in outcomes and "VERIFIED" in outcomes
    blob = json.dumps([row.changes for row in ledger])
    assert TEST_NAME not in blob and "4821" not in blob and "9876543210" not in blob
