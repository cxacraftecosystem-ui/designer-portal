"""Two-step verification (TOTP): the arithmetic, the storage, and every door it guards.

The first half needs nothing: RFC 6238's own test vectors, the window, the encryption at rest, the
recovery-code shape, the QR geometry and the challenge token's signature and purpose. The second half
drives the real routes against Postgres — every sign-in method challenged (password, Google,
Microsoft, Yahoo), replay refused, recovery codes single-use, the per-challenge attempt cap, the
self-service setup/enable/disable/new-codes conversation, and who may reset somebody else's.

Postgres is required for the second half, and the module follows the sync pattern
``tests/conftest.py`` describes: one ``asyncio.run`` seeds every row before the ``TestClient`` starts,
the tests are sync and drive the client, and nothing here awaits ``db`` afterwards. Every test that
spends a code has an account of its own, because a spent step stays spent for that account.
"""

from __future__ import annotations

import asyncio
import functools
import os
import re
import time
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import jwt
import pytest
from oidc_fake_idp import FakeIdP, login_body

from app.core.db import db
from app.core.security import create_access_token, decode_access_token, hash_password
from app.services import two_factor

# ==================================================================================================
# The arithmetic — no database
# ==================================================================================================

#: RFC 6238 Appendix B's SHA-1 seed, "12345678901234567890", in base32.
RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

#: Appendix B's SHA-1 column, cut to six digits. HOTP truncates modulo 10^digits, so the six-digit
#: code is exactly the last six digits of the published eight-digit one.
RFC_VECTORS = [
    (59, "94287082"),
    (1111111109, "07081804"),
    (1111111111, "14050471"),
    (1234567890, "89005924"),
    (2000000000, "69279037"),
    (20000000000, "65353130"),
]


@pytest.mark.parametrize(("at", "eight_digits"), RFC_VECTORS)
def test_rfc_6238_appendix_b_sha1_vectors(at, eight_digits):
    assert two_factor.current_totp(RFC_SECRET, at=at) == eight_digits[-6:]
    assert two_factor.match_totp_step(RFC_SECRET, eight_digits[-6:], at=at) == at // 30


def test_the_window_is_one_step_either_side_and_no_wider():
    now = 1_700_000_000
    step = now // 30
    for offset in (-1, 0, 1):
        code = two_factor.current_totp(RFC_SECRET, at=now + offset * 30)
        assert two_factor.match_totp_step(RFC_SECRET, code, at=now) == step + offset
    for offset in (-2, 2):
        code = two_factor.current_totp(RFC_SECRET, at=now + offset * 30)
        assert two_factor.match_totp_step(RFC_SECRET, code, at=now) is None


@pytest.mark.parametrize("code", ["", "12345", "1234567", "abcdef", "12 34 5x", "１２３４５６"])
def test_anything_but_six_ascii_digits_is_not_an_authenticator_code(code):
    assert two_factor.match_totp_step(RFC_SECRET, code, at=59) is None


def test_a_secret_is_160_bits_of_unpadded_base32():
    secret = two_factor.generate_secret()
    assert re.fullmatch(r"[A-Z2-7]{32}", secret)
    assert secret != two_factor.generate_secret()


def test_the_otpauth_uri_names_the_issuer_and_the_account():
    uri = two_factor.totp_uri(RFC_SECRET, "meera@example.org")
    assert uri.startswith("otpauth://totp/Design%20Prototype%20Workshop%3Ameera%40example.org?")
    assert f"secret={RFC_SECRET}" in uri
    assert "issuer=Design%20Prototype%20Workshop" in uri
    assert "algorithm=SHA1" in uri and "digits=6" in uri and "period=30" in uri


def test_the_secret_is_encrypted_at_rest_and_tampering_is_refused():
    stored = two_factor.encrypt_secret(RFC_SECRET)
    assert RFC_SECRET not in stored
    assert stored.startswith("v1.") and stored.count(".") == 3
    assert two_factor.encrypt_secret(RFC_SECRET) != stored, "a fresh IV every time"
    assert two_factor.decrypt_secret(stored) == RFC_SECRET
    version, iv, tag, body = stored.split(".")
    flipped = body[:-1] + ("A" if body[-1] != "A" else "B")
    assert two_factor.decrypt_secret(".".join((version, iv, tag, flipped))) is None
    assert two_factor.decrypt_secret("v2." + stored[3:]) is None
    assert two_factor.decrypt_secret(None) is None


def test_recovery_codes_are_ten_unambiguous_xxxx_xxxx_codes():
    codes = two_factor.generate_recovery_codes()
    assert len(codes) == 10 and len(set(codes)) == 10
    for code in codes:
        assert re.fullmatch(r"[a-hjkmnp-z2-9]{4}-[a-hjkmnp-z2-9]{4}", code), code
        assert not set(code) & set("01ilo")


def test_a_recovery_code_is_read_however_it_was_typed():
    assert two_factor.canonical_recovery_code(" AB2C DEF3 ") == "ab2c-def3"
    assert two_factor.canonical_recovery_code("ab2c--def3") == "ab2c-def3"
    assert two_factor.canonical_recovery_code("ab2") == "ab2"


@pytest.mark.parametrize(
    ("text", "kind"),
    [
        ("123456", "totp"),
        ("123 456", "totp"),
        ("ab2c-def3", "recovery"),
        ("AB2CDEF3", "recovery"),
        ("12345", None),
        ("ab2c-def", None),
        ("", None),
    ],
)
def test_the_kind_of_code_is_decided_by_its_shape(text, kind):
    assert two_factor.code_kind(text) == kind


def test_the_qr_path_redraws_exactly_the_matrix_and_the_svg_has_the_shape_clients_parse():
    uri = two_factor.totp_uri(RFC_SECRET, "meera@example.org")
    matrix = two_factor.qr_matrix(uri)
    size = len(matrix)
    assert all(len(row) == size for row in matrix)
    border = two_factor.QR_QUIET_ZONE_MODULES
    assert not any(matrix[y][x] for y in range(border) for x in range(size)), "quiet zone"
    # Replay the path as rectangles; every dark module must be covered exactly once.
    drawn = [[False] * size for _ in range(size)]
    for x, y, run in re.findall(r"M(\d+) (\d+)h(\d+)v1h-\3z", two_factor.qr_path(matrix)):
        for dx in range(int(run)):
            assert not drawn[int(y)][int(x) + dx]
            drawn[int(y)][int(x) + dx] = True
    assert drawn == matrix
    svg = two_factor.qr_svg(uri)
    assert re.fullmatch(
        rf'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" [^>]*>'
        r'<rect [^>]*/><path fill="#000000" d="[Mhvz0-9 -]+"/></svg>',
        svg,
    )
    assert RFC_SECRET not in svg


def _sign(claims: dict[str, Any]) -> str:
    return jwt.encode(claims, two_factor._challenge_key(), algorithm="HS256")  # noqa: SLF001


def test_a_challenge_token_needs_its_purpose_its_key_and_its_time():
    now = datetime.now(UTC)
    good = {"sub": "u1", "jti": "c1", "purpose": "2fa", "exp": now + timedelta(minutes=5)}
    assert two_factor.read_challenge(_sign(good)) == two_factor.Challenge("u1", "c1")
    for bad in (
        {**good, "purpose": "session"},
        {**good, "exp": now - timedelta(seconds=1)},
        {key: value for key, value in good.items() if key != "jti"},
    ):
        with pytest.raises(two_factor.TwoFactorError) as refused:
            two_factor.read_challenge(_sign(bad))
        assert refused.value.detail == two_factor.CHALLENGE_DEAD_DETAIL
    # A session token is not a challenge, and a challenge is not a session: different keys.
    with pytest.raises(two_factor.TwoFactorError):
        two_factor.read_challenge(create_access_token("u1"))
    with pytest.raises(ValueError):
        decode_access_token(_sign(good))


# ==================================================================================================
# The routes — Postgres
# ==================================================================================================

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))
needs_db = pytest.mark.skipif(
    not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)

PASSWORD = "a-long-enough-password"
SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
#: Two live recovery codes on every seeded account — enough to prove single use without paying for
#: ten bcrypt hashes per account.
RECOVERY = ["ab2c-def3", "gh4j-km5n"]

#: Accounts with two-step verification ON, one per test that spends a code.
ON = (
    "password", "replay", "recovery", "cap", "google", "microsoft", "yahoo", "disable",
    "reset-target", "reset-peer", "regen", "no-leak", "expired", "lock", "dataset",
    "lock-recovery", "recovery-budget", "fully-locked", "mail", "lapsed", "window", "concurrent",
    "concurrent-http", "race", "escalate", "decay-25", "decay-23", "spend-disable", "spend-regen",
    "reset-lock", "cancel-on",
)
#: Seeded at ADMIN rather than RESEARCHER: a peer an admin may not manage, and dataset-token holders.
ADMIN_SLUGS = ("reset-peer", "dataset", "lock")
#: Accounts with it OFF.
OFF = ("plain", "enrol", "researcher", "pending", "stale")


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    tag = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"tfa-{slug}-{tag}@example.org"

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            now = datetime.now(UTC)
            hashed_password = hash_password(PASSWORD)
            hashed_codes = await two_factor.hash_recovery_codes(RECOVERY)
            encrypted = two_factor.encrypt_secret(SECRET)
            ids: dict[str, str] = {}
            admin = await db.user.create(data={
                "email": address("admin"), "name": "TFA Admin", "role": "ADMIN",
                "passwordHash": hashed_password, "passwordSetAt": now, "authProvider": "LOCAL",
            })
            ids["admin"] = admin.id
            for slug in (*ON, *OFF):
                is_on = slug in ON
                data: dict[str, Any] = {
                    "email": address(slug),
                    "name": f"TFA {slug}",
                    "role": "ADMIN" if slug in ADMIN_SLUGS else "RESEARCHER",
                    "passwordHash": hashed_password,
                    "passwordSetAt": now,
                    "authProvider": "LOCAL",
                }
                if is_on:
                    data |= {
                        "twoFactorEnabled": True,
                        "twoFactorSecret": encrypted,
                        "twoFactorRecoveryCodes": hashed_codes,
                        "twoFactorEnabledAt": now,
                    }
                ids[slug] = (await db.user.create(data=data)).id
            for slug in ("admin", *ON, *OFF):
                await db.accessroster.create(data={
                    "email": address(slug), "status": "ACTIVE", "admitRole": "RESEARCHER",
                    "joinedAt": now,
                })
            return {"ids": ids, "address": address}
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


def _bearer(user_id: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(user_id)}"}


def _password_login(world: dict[str, Any], slug: str) -> Any:
    return world["client"].post(
        "/api/auth/login", json={"email": world["address"](slug), "password": PASSWORD}
    )


def _challenge(response: Any) -> str:
    assert response.status_code == 200, response.text
    body = response.json()
    assert body == {"two_factor_required": True, "challenge_token": body["challenge_token"]}
    assert "accessToken" not in body
    return body["challenge_token"]


def _verify(world: dict[str, Any], token: str, code: str) -> Any:
    return world["client"].post(
        "/api/auth/two-factor/verify", json={"challenge_token": token, "code": code}
    )


def _now_code() -> str:
    return two_factor.current_totp(SECRET)


def _next_code() -> str:
    """The NEXT step's code — inside the ±1 window and above a step this test has just spent."""
    return two_factor.current_totp(SECRET, at=time.time() + 30)


@needs_db
def test_an_account_without_two_step_verification_signs_in_as_before(world):
    response = _password_login(world, "plain")
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["accessToken"] and "two_factor_required" not in body
    assert body["user"]["twoFactorEnabled"] is False


@needs_db
def test_a_password_sign_in_is_challenged_and_the_code_opens_the_session(world):
    token = _challenge(_password_login(world, "password"))
    response = _verify(world, token, _now_code())
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["tokenType"] == "bearer"
    assert body["user"]["id"] == world["ids"]["password"]
    me = world["client"].get("/api/me", headers={"Authorization": f"Bearer {body['accessToken']}"})
    assert me.status_code == 200, me.text
    # One challenge opens one session.
    again = _verify(world, token, _next_code())
    assert again.status_code == 401
    assert again.json()["detail"] == two_factor.CHALLENGE_DEAD_DETAIL


@needs_db
def test_a_challenge_token_is_not_a_session(world):
    token = _challenge(_password_login(world, "expired"))
    response = world["client"].get("/api/me", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401


@needs_db
def test_an_expired_or_forged_challenge_is_refused(world):
    past = datetime.now(UTC) - timedelta(seconds=1)
    stale = _sign({"sub": world["ids"]["expired"], "jti": "x", "purpose": "2fa", "exp": past})
    response = _verify(world, stale, _now_code())
    assert response.status_code == 401
    assert response.json()["detail"] == two_factor.CHALLENGE_DEAD_DETAIL
    forged = _sign({
        "sub": world["ids"]["expired"], "jti": "never-issued", "purpose": "2fa",
        "exp": datetime.now(UTC) + timedelta(minutes=5),
    })
    assert _verify(world, forged, _now_code()).status_code == 401


@needs_db
def test_a_code_once_accepted_is_refused_for_ever_after(world):
    code = _now_code()
    first = _verify(world, _challenge(_password_login(world, "replay")), code)
    assert first.status_code == 200, first.text
    replayed = _verify(world, _challenge(_password_login(world, "replay")), code)
    assert replayed.status_code == 401
    assert replayed.json()["detail"] == two_factor.WRONG_CODE_DETAIL


@needs_db
def test_a_recovery_code_works_once_however_it_is_typed(world):
    first = _verify(world, _challenge(_password_login(world, "recovery")), " AB2C DEF3 ")
    assert first.status_code == 200, first.text
    status = world["client"].get("/api/auth/two-factor", headers=_bearer(world["ids"]["recovery"]))
    assert status.json()["recovery_codes_remaining"] == 1
    second = _verify(world, _challenge(_password_login(world, "recovery")), RECOVERY[0])
    assert second.status_code == 401
    assert second.json()["detail"] == two_factor.WRONG_CODE_DETAIL


@needs_db
def test_five_wrong_codes_kill_the_challenge(world):
    token = _challenge(_password_login(world, "cap"))
    for _ in range(two_factor.MAX_CHALLENGE_ATTEMPTS):
        wrong = _verify(world, token, "000000" if _now_code() != "000000" else "111111")
        assert wrong.status_code == 401
        assert wrong.json()["detail"] == two_factor.WRONG_CODE_DETAIL
    dead = _verify(world, token, _now_code())
    assert dead.status_code == 401
    assert dead.json()["detail"] == two_factor.CHALLENGE_DEAD_DETAIL
    # A fresh sign-in gets a fresh challenge, and the right code still works.
    assert _verify(world, _challenge(_password_login(world, "cap")), _now_code()).status_code == 200


@needs_db
def test_a_google_sign_in_is_challenged(world, monkeypatch):
    from app.api.routes import auth as auth_routes

    email = world["address"]("google")
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": "G", "picture": None},
    )
    token = _challenge(world["client"].post("/api/auth/login", json={"googleIdToken": "t"}))
    response = _verify(world, token, _now_code())
    assert response.status_code == 200, response.text
    assert response.json()["user"]["id"] == world["ids"]["google"]


@needs_db
@pytest.mark.parametrize("provider", ["MICROSOFT", "YAHOO"])
def test_a_microsoft_or_yahoo_sign_in_is_challenged(world, monkeypatch, provider):
    slug = provider.lower()
    idp = FakeIdP().install(monkeypatch)
    if provider == "MICROSOFT":
        idp.issue(idp.sign(idp.microsoft_claims(world["address"](slug))))
    else:
        idp.issue(idp.sign(idp.yahoo_claims(world["address"](slug)), alg="ES256"))
    token = _challenge(world["client"].post("/api/auth/login", json=login_body(provider)))
    response = _verify(world, token, _now_code())
    assert response.status_code == 200, response.text
    assert response.json()["user"]["id"] == world["ids"][slug]


@needs_db
def test_setup_enable_status_new_codes_and_disable(world):
    client = world["client"]
    headers = _bearer(world["ids"]["enrol"])
    assert client.get("/api/auth/two-factor", headers=headers).json() == {
        "enabled": False, "pending": False, "recovery_codes_remaining": 0, "enabled_at": None,
    }
    setup = client.post("/api/auth/two-factor/setup", headers=headers)
    assert setup.status_code == 200, setup.text
    body = setup.json()
    assert set(body) == {"secret", "otpauth_uri", "qr_svg"}
    secret = body["secret"]
    assert world["address"]("enrol").replace("@", "%40") in body["otpauth_uri"]
    assert body["qr_svg"].startswith("<svg")
    # Not on yet, and a wrong code does not switch it on.
    wrong = "000000" if two_factor.current_totp(secret) != "000000" else "111111"
    refused = client.post("/api/auth/two-factor/enable", json={"code": wrong}, headers=headers)
    assert refused.status_code == 400
    assert client.get("/api/auth/two-factor", headers=headers).json()["enabled"] is False

    enabled = client.post(
        "/api/auth/two-factor/enable", json={"code": two_factor.current_totp(secret)}, headers=headers
    )
    assert enabled.status_code == 200, enabled.text
    codes = enabled.json()["recovery_codes"]
    assert len(codes) == 10
    state = client.get("/api/auth/two-factor", headers=headers).json()
    assert state["enabled"] is True and state["recovery_codes_remaining"] == 10 and state["enabled_at"]
    assert client.post("/api/auth/two-factor/setup", headers=headers).status_code == 409

    # New codes need a CURRENT authenticator code (the next step: enabling spent this one) and
    # retire every old code.
    fresh = client.post(
        "/api/auth/two-factor/recovery-codes",
        json={"code": two_factor.current_totp(secret, at=time.time() + 30)},
        headers=headers,
    )
    assert fresh.status_code == 200, fresh.text
    new_codes = fresh.json()["recovery_codes"]
    assert set(new_codes).isdisjoint(codes)
    assert client.post(
        "/api/auth/two-factor/disable", json={"code": codes[0]}, headers=headers
    ).status_code == 400

    off = client.post("/api/auth/two-factor/disable", json={"code": new_codes[0]}, headers=headers)
    assert off.status_code == 204, off.text
    assert client.get("/api/auth/two-factor", headers=headers).json()["enabled"] is False
    signed_in = _password_login(world, "enrol")
    assert "accessToken" in signed_in.json()


# ── A setup is not "on" until a code confirms it ─────────────────────────────────────────────────


class _Row:
    def __init__(self, **values: Any) -> None:
        self.__dict__.update(values)


def test_a_pending_setup_is_live_for_fifteen_minutes_and_never_reads_as_on():
    now = datetime.now(UTC)
    fresh = _Row(twoFactorEnabled=False, twoFactorPendingSecret="v1.a.b.c", twoFactorPendingSince=now - timedelta(minutes=14))
    stale = _Row(twoFactorEnabled=False, twoFactorPendingSecret="v1.a.b.c", twoFactorPendingSince=now - timedelta(minutes=16))
    undated = _Row(twoFactorEnabled=False, twoFactorPendingSecret="v1.a.b.c", twoFactorPendingSince=None)
    nothing = _Row(twoFactorEnabled=False, twoFactorPendingSecret=None, twoFactorPendingSince=None)
    on = _Row(twoFactorEnabled=True, twoFactorPendingSecret=None, twoFactorPendingSince=None,
              twoFactorRecoveryCodes=["h"] * 8, twoFactorEnabledAt=now)

    assert two_factor.PENDING_SETUP_MINUTES == 15
    assert two_factor.pending_is_live(fresh, now) is True
    assert two_factor.pending_is_live(stale, now) is False
    assert two_factor.pending_is_live(undated, now) is False, "a key shown before the timestamp existed is abandoned"
    assert two_factor.pending_is_live(nothing, now) is False
    assert two_factor.pending_is_live(on, now) is False
    assert [two_factor.has_stale_pending(row, now) for row in (fresh, stale, undated, nothing, on)] == [
        False, True, True, False, False,
    ]
    status = two_factor.status_of(fresh, now)
    assert status["enabled"] is False and status["pending"] is True, "pending is never on"
    assert two_factor.status_of(stale, now)["pending"] is False
    assert two_factor.status_of(on, now) == {
        "enabled": True, "pending": False, "recovery_codes_remaining": 8, "enabled_at": now,
    }


@needs_db
def test_an_unconfirmed_setup_is_off_signs_in_without_a_code_and_cancel_discards_it(world):
    client = world["client"]
    user_id = world["ids"]["pending"]
    headers = _bearer(user_id)
    setup = client.post("/api/auth/two-factor/setup", headers=headers)
    assert setup.status_code == 200, setup.text
    secret = setup.json()["secret"]

    state = client.get("/api/auth/two-factor", headers=headers).json()
    assert state["enabled"] is False and state["pending"] is True
    # A pending-only account is NOT challenged: only a confirmed second factor asks for a code.
    signed_in = _password_login(world, "pending")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["accessToken"] and "two_factor_required" not in signed_in.json()

    assert client.post("/api/auth/two-factor/setup/cancel", headers=headers).status_code == 204
    row = _run(world, _row, user_id)
    assert row.twoFactorPendingSecret is None and row.twoFactorPendingSince is None
    assert row.twoFactorEnabled is False
    assert client.get("/api/auth/two-factor", headers=headers).json()["pending"] is False
    # Idempotent, and a code from the discarded key switches nothing on.
    assert client.post("/api/auth/two-factor/setup/cancel", headers=headers).status_code == 204
    refused = client.post(
        "/api/auth/two-factor/enable", json={"code": two_factor.current_totp(secret)}, headers=headers
    )
    assert refused.status_code == 400, refused.text
    assert refused.json()["detail"] == two_factor.NO_SETUP_DETAIL
    assert client.get("/api/auth/two-factor", headers=headers).json()["enabled"] is False


@needs_db
def test_cancel_never_touches_a_second_factor_that_is_on(world):
    client = world["client"]
    user_id = world["ids"]["cancel-on"]
    headers = _bearer(user_id)
    before = _run(world, _row, user_id)
    assert client.post("/api/auth/two-factor/setup/cancel", headers=headers).status_code == 204
    after = _run(world, _row, user_id)
    assert after.twoFactorEnabled is True
    assert after.twoFactorSecret == before.twoFactorSecret
    assert after.twoFactorRecoveryCodes == before.twoFactorRecoveryCodes
    _challenge(_password_login(world, "cancel-on"))


@needs_db
def test_a_setup_older_than_fifteen_minutes_is_cleared_and_cannot_be_confirmed(world):
    client = world["client"]
    user_id = world["ids"]["stale"]
    headers = _bearer(user_id)
    secret = client.post("/api/auth/two-factor/setup", headers=headers).json()["secret"]
    _run(world, _set, user_id, twoFactorPendingSince=datetime.now(UTC) - timedelta(minutes=16))

    refused = client.post(
        "/api/auth/two-factor/enable", json={"code": two_factor.current_totp(secret)}, headers=headers
    )
    assert refused.status_code == 400 and refused.json()["detail"] == two_factor.NO_SETUP_DETAIL
    state = client.get("/api/auth/two-factor", headers=headers).json()
    assert state == {"enabled": False, "pending": False, "recovery_codes_remaining": 0, "enabled_at": None}
    assert _run(world, _row, user_id).twoFactorPendingSecret is None, "the status read clears it"
    assert "accessToken" in _password_login(world, "stale").json()


@needs_db
def test_disable_with_an_authenticator_code(world):
    headers = _bearer(world["ids"]["disable"])
    response = world["client"].post(
        "/api/auth/two-factor/disable", json={"code": _now_code()}, headers=headers
    )
    assert response.status_code == 204, response.text
    assert "accessToken" in _password_login(world, "disable").json()


@needs_db
def test_new_recovery_codes_refuse_a_recovery_code_as_proof(world):
    headers = _bearer(world["ids"]["regen"])
    response = world["client"].post(
        "/api/auth/two-factor/recovery-codes", json={"code": RECOVERY[1]}, headers=headers
    )
    assert response.status_code == 400


@needs_db
def test_no_response_carries_the_secret_or_the_hashes(world):
    for response in (
        world["client"].get("/api/me", headers=_bearer(world["ids"]["no-leak"])),
        world["client"].get("/api/users", headers=_bearer(world["ids"]["admin"]),
                            params={"search": world["address"]("no-leak")}),
    ):
        assert response.status_code == 200, response.text
        text = response.text
        for field in two_factor.SECRET_USER_FIELDS:
            assert field not in text
        assert "twoFactorEnabled" in text


@needs_db
def test_an_administrator_resets_it_and_the_persons_sessions_end(world):
    client = world["client"]
    target = world["ids"]["reset-target"]
    old_session = _bearer(target)
    assert client.get("/api/me", headers=old_session).status_code == 200
    response = client.delete(f"/api/users/{target}/two-factor", headers=_bearer(world["ids"]["admin"]))
    assert response.status_code == 200, response.text
    assert response.json()["twoFactorEnabled"] is False
    assert client.get("/api/me", headers=old_session).status_code == 401
    assert "accessToken" in _password_login(world, "reset-target").json()
    again = client.delete(f"/api/users/{target}/two-factor", headers=_bearer(world["ids"]["admin"]))
    assert again.status_code == 409


@needs_db
def test_who_may_reset_somebody_elses_second_factor(world):
    client = world["client"]
    target = world["ids"]["reset-peer"]
    # A researcher is no provisioner; an admin may not reset a peer admin, nor their own.
    assert client.delete(
        f"/api/users/{target}/two-factor", headers=_bearer(world["ids"]["researcher"])
    ).status_code == 403
    assert client.delete(
        f"/api/users/{target}/two-factor", headers=_bearer(world["ids"]["admin"])
    ).status_code == 403
    assert client.delete(
        f"/api/users/{target}/two-factor", headers=_bearer(target)
    ).status_code == 403
    assert client.delete(
        "/api/users/does-not-exist/two-factor", headers=_bearer(world["ids"]["admin"])
    ).status_code == 404


# ==================================================================================================
# The per-account failure budgets, every password-to-credential door, and the serializers
# ==================================================================================================
#
# Mirrors the documentation portal's tests of the same mechanism. Those run against an in-memory fake;
# these run against Postgres, so the service-level ones are driven on the TestClient's own event loop
# (``client.portal``), where the app's database connection lives.


def _run(world: dict[str, Any], fn: Any, *args: Any, **kwargs: Any) -> Any:
    return world["client"].portal.call(functools.partial(fn, *args, **kwargs))


async def _set(user_id: str, **data: Any) -> None:
    from app.core.deps import invalidate_cached_user

    await db.user.update(where={"id": user_id}, data=data)
    invalidate_cached_user(user_id)


async def _row(user_id: str) -> Any:
    return await db.user.find_unique(where={"id": user_id})


def _wrong() -> str:
    """Six digits that are no code in the ±1 window right now."""
    near = {two_factor.current_totp(SECRET, at=time.time() + 30 * k) for k in (-1, 0, 1, 2)}
    return next(code for code in ("000000", "111111", "222222", "333333", "444444") if code not in near)


@pytest.fixture
def unthrottled(monkeypatch):
    """The per-account credential BUCKET (ten requests in five minutes, shared with the password
    doors) is a separate, shorter limit; lifted here so these tests meet the second-factor budget."""
    from app.api.routes import two_factor as routes

    monkeypatch.setattr(routes, "account_credential_attempt", lambda _user_id: (True, 0.0))


def test_the_lockout_backs_off_and_is_capped_at_an_hour():
    assert two_factor.FAILURE_BUDGET == 10 and two_factor.RECOVERY_FAILURE_BUDGET == 5
    assert two_factor.lockout_duration(0) == timedelta(minutes=15)
    assert two_factor.lockout_duration(1) == timedelta(minutes=30)
    assert two_factor.lockout_duration(2) == timedelta(hours=1)
    assert two_factor.lockout_duration(3) == timedelta(hours=1)
    assert two_factor.lockout_duration(500) == timedelta(hours=1)


def test_no_serializer_carries_a_secret_user_field():
    """ONE tuple (``app/core/user_fields.py``), three serializers: both ``serialize_user`` and
    ``records.public_encode`` at any depth. A fully populated row goes in; none of them comes out."""
    from app.api.routes import auth as auth_routes, users as users_routes
    from app.core.user_fields import SECRET_USER_FIELDS
    from app.services.records import public_encode

    now = datetime.now(UTC)
    row = {
        "id": "u1",
        "email": "u1@example.org",
        "name": "Meera",
        "twoFactorEnabled": True,
        "twoFactorEnabledAt": now,
        "passwordHash": "$2b$12$" + "x" * 53,
        "twoFactorSecret": "v1.a.b.c",
        "twoFactorPendingSecret": "v1.d.e.f",
        "twoFactorPendingSince": now,
        "twoFactorRecoveryCodes": ["$2b$12$hash"],
        "twoFactorLastStep": 59_000_000,
        "twoFactorFailedAttempts": 3,
        "twoFactorFailureWindowStartedAt": now,
        "twoFactorLockedUntil": now,
        "twoFactorLockouts": 1,
        "twoFactorRecoveryFailedAttempts": 2,
        "twoFactorRecoveryWindowStartedAt": now,
        "twoFactorRecoveryLockedUntil": now,
        "twoFactorRecoveryLockouts": 1,
    }
    assert set(SECRET_USER_FIELDS) <= set(row), "the fixture must populate every secret field"
    assert two_factor.SECRET_USER_FIELDS is SECRET_USER_FIELDS
    record = {
        "id": "m1",
        "uploadedBy": dict(row),
        "workshop": {"createdBy": dict(row), "assignments": [{"user": dict(row)}]},
    }
    encoded = public_encode(record)

    def keys(value: Any) -> set[str]:
        if isinstance(value, dict):
            found = set(value)
            for nested in value.values():
                found |= keys(nested)
            return found
        if isinstance(value, list):
            return set().union(*(keys(item) for item in value)) if value else set()
        return set()

    leaked = keys(encoded) | set(auth_routes.serialize_user(dict(row))) | set(users_routes.serialize_user(dict(row)))
    assert not leaked & set(SECRET_USER_FIELDS), leaked & set(SECRET_USER_FIELDS)
    assert encoded["uploadedBy"]["twoFactorEnabled"] is True


@needs_db
def test_fresh_challenges_do_not_buy_more_guesses(world, unthrottled):
    # Minted before the lock, to show that a challenge already in hand is no way round it.
    stockpiled = _challenge(_password_login(world, "lock"))
    for _ in range(2):
        token = _challenge(_password_login(world, "lock"))
        for _ in range(two_factor.MAX_CHALLENGE_ATTEMPTS):
            assert _verify(world, token, _wrong()).status_code == 401
    row = _run(world, _row, world["ids"]["lock"])
    assert row.twoFactorLockedUntil is not None and row.twoFactorLockouts == 1
    locked = _verify(world, stockpiled, _now_code())
    assert locked.status_code == 429
    assert locked.json()["detail"] == two_factor.TOO_MANY_ATTEMPTS_DETAIL == "Too many attempts. Try again later."
    # ...and the dataset token shares the lock.
    minted = world["client"].post(
        "/api/datasets/token",
        json={"email": world["address"]("lock"), "password": PASSWORD, "twoFactorCode": _now_code()},
    )
    assert minted.status_code == 429, minted.text


@needs_db
def test_a_six_digit_lock_still_lets_the_owner_in_with_a_recovery_code(world):
    user_id = world["ids"]["lock-recovery"]
    _run(world, _set, user_id, twoFactorLockedUntil=datetime.now(UTC) + timedelta(minutes=10))
    # Sign-in still mints a challenge — the recovery codes are a way in — and one of them works.
    token = _challenge(_password_login(world, "lock-recovery"))
    assert _verify(world, token, _now_code()).status_code == 429
    assert _verify(world, token, RECOVERY[0]).status_code == 200


@needs_db
def test_recovery_codes_have_their_own_smaller_budget(world, unthrottled):
    user_id = world["ids"]["recovery-budget"]
    headers = _bearer(user_id)
    for _ in range(two_factor.RECOVERY_FAILURE_BUDGET):
        response = world["client"].post("/api/auth/two-factor/disable", json={"code": "zzzz-zzzz"}, headers=headers)
        assert response.status_code == 400, response.text
    row = _run(world, _row, user_id)
    assert two_factor.is_locked(row, two_factor.RECOVERY_BUDGET) and row.twoFactorRecoveryLockouts == 1
    # The six-digit budget is untouched and independent.
    assert not two_factor.is_locked(row) and row.twoFactorFailedAttempts == 0
    assert world["client"].post(
        "/api/auth/two-factor/disable", json={"code": RECOVERY[0]}, headers=headers
    ).status_code == 429
    assert world["client"].post(
        "/api/auth/two-factor/recovery-codes", json={"code": _now_code()}, headers=headers
    ).status_code == 200


@needs_db
def test_sign_in_says_too_many_attempts_only_when_every_way_in_is_locked(world):
    user_id = world["ids"]["fully-locked"]
    _run(world, _set, user_id, twoFactorLockedUntil=datetime.now(UTC) + timedelta(minutes=10))
    assert _password_login(world, "fully-locked").json().get("two_factor_required") is True
    _run(world, _set, user_id, twoFactorRecoveryLockedUntil=datetime.now(UTC) + timedelta(minutes=10))
    refused = _password_login(world, "fully-locked")
    assert refused.status_code == 429
    assert refused.json()["detail"] == two_factor.TOO_MANY_ATTEMPTS_DETAIL
    assert "challenge_token" not in refused.text
    # A wrong password is still the wrong-password answer, not a lock confirmation.
    wrong_password = world["client"].post(
        "/api/auth/login", json={"email": world["address"]("fully-locked"), "password": "not-the-password"}
    )
    assert wrong_password.status_code == 401


@needs_db
def test_the_owner_is_emailed_once_when_a_lock_is_written(world, monkeypatch):
    from app.services import email_outbox, mailer

    sent: list[tuple[str, dict[str, Any]]] = []

    async def capture(kind: str, *, to_address: str, recipient_id: str | None, params: dict[str, Any], **_: Any) -> None:
        sent.append((kind, {"to": to_address, "recipient": recipient_id, **params}))

    monkeypatch.setattr(email_outbox, "enqueue", capture)
    user_id = world["ids"]["mail"]

    async def spend() -> None:
        for _ in range(two_factor.FAILURE_BUDGET + 3):
            row = await _row(user_id)
            try:
                await two_factor.check_second_factor(row, _wrong())
            except two_factor.SecondFactorLocked:
                pass

    _run(world, spend)
    assert len(sent) == 1
    kind, params = sent[0]
    assert kind == mailer.SECOND_FACTOR_LOCKED
    assert params["to"] == world["address"]("mail") and params["recipient"] == user_id
    assert set(params) == {"to", "recipient", "recipientName", "lockedUntil", "codeKind"}
    rendered = mailer.render(kind, params)
    assert "entered your Design Prototype Workshop password and then several wrong verification codes" in rendered.text
    assert "change your password now" in rendered.text
    assert mailer.locked_until_words(params["lockedUntil"]) in rendered.text
    assert SECRET not in rendered.text
    recovery = mailer.render(kind, {**params, "codeKind": "recovery"})
    assert "recovery codes still work" not in recovery.text


@needs_db
def test_a_lapsed_lock_lets_the_right_code_in_and_resets_the_budget(world):
    user_id = world["ids"]["lapsed"]
    # The state a lock leaves behind: budget spent, window closed, the lock just ended.
    _run(
        world, _set, user_id,
        twoFactorFailedAttempts=two_factor.FAILURE_BUDGET,
        twoFactorFailureWindowStartedAt=None,
        twoFactorLockouts=2,
        twoFactorLockedUntil=datetime.now(UTC) - timedelta(seconds=1),
    )
    ok = _verify(world, _challenge(_password_login(world, "lapsed")), _now_code())
    assert ok.status_code == 200, ok.text
    row = _run(world, _row, user_id)
    assert (row.twoFactorFailedAttempts, row.twoFactorLockouts) == (0, 0)


@needs_db
def test_a_claim_outside_the_window_starts_a_new_one(world):
    user_id = world["ids"]["window"]
    _run(
        world, _set, user_id,
        twoFactorFailedAttempts=9,
        twoFactorFailureWindowStartedAt=datetime.now(UTC) - timedelta(minutes=16),
    )
    assert _run(world, two_factor.claim_attempt_slot, user_id) == 1
    assert _run(world, _row, user_id).twoFactorLockedUntil is None


@needs_db
def test_a_burst_of_concurrent_wrong_codes_gets_only_the_budget(world, monkeypatch, caplog):
    """Every request claims a slot atomically BEFORE its code is checked, so thirty simultaneous
    guesses get ten checks, one lock and one audit line — not thirty of each."""
    checked: list[str] = []

    async def counting(user: Any, code: str, *, allow_recovery: bool = True) -> None:
        checked.append(code)
        await asyncio.sleep(0)  # yield, so the burst genuinely interleaves
        return

    monkeypatch.setattr(two_factor, "verify_second_factor", counting)
    user_id = world["ids"]["concurrent"]

    async def burst() -> list[Any]:
        row = await _row(user_id)
        return await asyncio.gather(
            *(two_factor.check_second_factor(row, _wrong()) for _ in range(30)), return_exceptions=True
        )

    with caplog.at_level("WARNING"):
        outcomes = _run(world, burst)
    assert len(checked) == two_factor.FAILURE_BUDGET, outcomes
    assert sum(isinstance(o, two_factor.SecondFactorLocked) for o in outcomes) == 30 - two_factor.FAILURE_BUDGET
    row = _run(world, _row, user_id)
    assert row.twoFactorLockouts == 1 and two_factor.is_locked(row)
    assert sum("AUDIT two-factor locked" in r.getMessage() for r in caplog.records) == 1


@needs_db
def test_twenty_parallel_wrong_codes_over_http_check_at_most_the_budget(world, monkeypatch, unthrottled):
    """The same rule from the outside: twenty requests in flight at once on `/disable`."""
    from concurrent.futures import ThreadPoolExecutor

    checked = []
    real = two_factor.verify_second_factor

    async def counting(*args: Any, **kwargs: Any) -> Any:
        checked.append(1)
        return await real(*args, **kwargs)

    monkeypatch.setattr(two_factor, "verify_second_factor", counting)
    headers = _bearer(world["ids"]["concurrent-http"])
    wrong = _wrong()

    def guess(_index: int) -> int:
        return world["client"].post(
            "/api/auth/two-factor/disable", json={"code": wrong}, headers=headers
        ).status_code

    with ThreadPoolExecutor(max_workers=20) as pool:
        statuses = list(pool.map(guess, range(20)))
    assert len(checked) <= two_factor.FAILURE_BUDGET, (len(checked), statuses)
    assert statuses.count(400) == len(checked)
    assert statuses.count(429) == 20 - len(checked)
    response = world["client"].post("/api/auth/two-factor/disable", json={"code": _now_code()}, headers=headers)
    assert response.status_code == 429


@needs_db
def test_a_success_keeps_wrong_codes_that_raced_it(world):
    """A correct code clears the budget only if nobody claimed a slot after it; otherwise it hands
    back its own slot and the racing failures stay counted."""
    user_id = world["ids"]["race"]
    _run(world, _set, user_id, twoFactorFailedAttempts=6, twoFactorFailureWindowStartedAt=datetime.now(UTC))

    async def race() -> None:
        mine = await two_factor.claim_attempt_slot(user_id)  # 7
        await two_factor.claim_attempt_slot(user_id)  # an attacker's wrong code lands: 8
        await two_factor.claim_attempt_slot(user_id)  # and another: 9
        await two_factor.settle_success(user_id, mine)

    _run(world, race)
    assert _run(world, _row, user_id).twoFactorFailedAttempts == 8


@needs_db
def test_lockouts_escalate_once_per_lock(world):
    """Two locks in a row are lockout 1 (15 min) then lockout 2 (30 min), each written once."""
    user_id = world["ids"]["escalate"]

    async def spend() -> None:
        for _ in range(two_factor.FAILURE_BUDGET):
            assert await two_factor.check_second_factor(await _row(user_id), _wrong()) is False

    _run(world, spend)
    row = _run(world, _row, user_id)
    first = row.twoFactorLockedUntil - datetime.now(UTC)
    assert row.twoFactorLockouts == 1 and timedelta(minutes=14) < first <= timedelta(minutes=15)
    _run(world, _set, user_id, twoFactorLockedUntil=datetime.now(UTC) - timedelta(seconds=1))  # lapse it
    _run(world, spend)
    row = _run(world, _row, user_id)
    second = row.twoFactorLockedUntil - datetime.now(UTC)
    assert row.twoFactorLockouts == 2 and timedelta(minutes=29) < second <= timedelta(minutes=30)


@needs_db
@pytest.mark.parametrize(("ended_hours_ago", "expected"), [(25, 0), (23, 3)])
def test_the_lockout_count_decays_a_day_after_the_last_lock(world, ended_hours_ago, expected):
    """A password holder cannot ratchet the owner's lock up for ever."""
    user_id = world["ids"][f"decay-{ended_hours_ago}"]
    _run(
        world, _set, user_id,
        twoFactorLockouts=3,
        twoFactorLockedUntil=datetime.now(UTC) - timedelta(hours=ended_hours_ago),
        twoFactorFailureWindowStartedAt=None,
        twoFactorFailedAttempts=two_factor.FAILURE_BUDGET,
    )
    _run(world, two_factor.claim_attempt_slot, user_id)
    row = _run(world, _row, user_id)
    assert row.twoFactorLockouts == expected and row.twoFactorFailedAttempts == 1


@needs_db
@pytest.mark.parametrize("path", ["/api/auth/two-factor/disable", "/api/auth/two-factor/recovery-codes"])
def test_disable_and_recovery_codes_spend_the_same_budget(world, unthrottled, path):
    slug = "spend-disable" if path.endswith("disable") else "spend-regen"
    headers = _bearer(world["ids"][slug])
    for _ in range(two_factor.FAILURE_BUDGET):
        assert world["client"].post(path, json={"code": _wrong()}, headers=headers).status_code == 400
    refused = world["client"].post(path, json={"code": _now_code()}, headers=headers)
    assert refused.status_code == 429
    assert refused.json()["detail"] == two_factor.TOO_MANY_ATTEMPTS_DETAIL
    assert _run(world, _row, world["ids"][slug]).twoFactorEnabled is True


@needs_db
def test_an_administrators_reset_clears_the_lock(world):
    user_id = world["ids"]["reset-lock"]
    _run(
        world, _set, user_id,
        twoFactorLockedUntil=datetime.now(UTC) + timedelta(hours=1),
        twoFactorLockouts=3,
        twoFactorFailedAttempts=4,
        twoFactorRecoveryLockedUntil=datetime.now(UTC) + timedelta(hours=1),
    )
    response = world["client"].delete(f"/api/users/{user_id}/two-factor", headers=_bearer(world["ids"]["admin"]))
    assert response.status_code == 200, response.text
    row = _run(world, _row, user_id)
    assert (row.twoFactorLockedUntil, row.twoFactorLockouts, row.twoFactorFailedAttempts) == (None, 0, 0)
    assert row.twoFactorRecoveryLockedUntil is None


@needs_db
def test_the_dataset_token_asks_for_the_second_factor_too(world):
    from app.api.routes.datasets import DATASET_TOKEN_NEEDS_CODE_DETAIL

    client = world["client"]
    body = {"email": world["address"]("dataset"), "password": PASSWORD}
    no_code = client.post("/api/datasets/token", json=body)
    assert no_code.status_code == 401, no_code.text
    assert no_code.json()["detail"] == DATASET_TOKEN_NEEDS_CODE_DETAIL
    refused = client.post("/api/datasets/token", json={**body, "twoFactorCode": _wrong()})
    assert refused.status_code == 401
    assert refused.json()["detail"] == two_factor.WRONG_CODE_DETAIL
    minted = client.post("/api/datasets/token", json={**body, "twoFactorCode": _now_code()})
    assert minted.status_code == 200, minted.text
    assert minted.json()["accessToken"]
