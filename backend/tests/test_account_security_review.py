"""SECURITY REVIEW OF THE ACCOUNT-PARITY WORK (2026-10-17) — one test per finding.

* D2 — a password change (and a first password) stamps ``sessionsValidFrom`` cut to the whole second,
  so a token minted before the change — including one too old to carry a ``cred`` claim — dies, while
  the ``X-Session-Token`` handed back survives.
* B1 — the anonymous per-client limit keys on the RIGHT end of ``X-Forwarded-For``
  (``FORWARDED_CLIENT_HOPS`` from the right), never the caller-typed left end; a whole-process cap
  bounds "Forgot your password?"; an administrator's links and self-service links have separate
  budgets.
* B2 — "A password was added" / "Your password was changed" notices to the account's own address,
  and a first password on an account with two-step on needs the second step.
* B3 — nobody changes their OWN address through ``PATCH /users/{self}``.
* B4 — a master admin cannot reset, sign out or turn off two-step for ANOTHER master admin.

The pure tests (B1's key and caps, the notice wording) need nothing; the rest need Postgres and skip
without it. Rows written by the app are read back through ``client.portal`` (``tests/test_stage_sync.py``
carries the argument).
"""

import asyncio
import functools
import time
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import password_credential
from app.core.security import create_access_token, hash_password
from app.scale import rate_limit
from app.services import credential_links, mailer, two_factor

PASSWORD = "security-review-original-1"
SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"

# ==================================================================================================
# B1 — which address the anonymous limit keys on, and the whole-process cap (no database)
# ==================================================================================================


def _scope(forwarded: str | None, peer: str = "10.0.0.9") -> dict[str, Any]:
    headers = [(b"x-forwarded-for", forwarded.encode("latin-1"))] if forwarded is not None else []
    return {"type": "http", "headers": headers, "client": (peer, 1234)}


@pytest.fixture
def hops(monkeypatch):
    def set_hops(value: int) -> None:
        monkeypatch.setattr(get_settings(), "forwarded_client_hops", value)

    return set_hops


def test_the_client_address_is_never_the_caller_typed_left_entry(hops):
    hops(1)
    # nginx alone: the caller typed "1.1.1.1", nginx appended the real peer.
    assert rate_limit.client_address(_scope("1.1.1.1, 203.0.113.7")) == "ip:203.0.113.7"
    assert rate_limit.client_address(_scope("203.0.113.7")) == "ip:203.0.113.7"


def test_behind_cloudfront_the_client_is_second_from_the_right(hops):
    hops(2)
    # caller-typed, CloudFront's viewer, nginx's CloudFront edge
    assert rate_limit.client_address(_scope("1.1.1.1, 198.51.100.4, 130.176.0.1")) == "ip:198.51.100.4"
    # Two headers are one list, in order.
    scope = _scope("1.1.1.1")
    scope["headers"].append((b"x-forwarded-for", b"198.51.100.4, 130.176.0.1"))
    assert rate_limit.client_address(scope) == "ip:198.51.100.4"


def test_a_chain_shorter_than_the_proxies_falls_back_to_the_socket_peer(hops):
    hops(2)
    assert rate_limit.client_address(_scope("1.1.1.1", peer="10.0.0.9")) == "ip:10.0.0.9"
    assert rate_limit.client_address(_scope(None, peer="10.0.0.9")) == "ip:10.0.0.9"


def test_a_forged_left_entry_does_not_buy_a_fresh_bucket(hops):
    hops(1)
    rate_limit.reset_account_credential_budget()
    try:
        answers = [
            rate_limit.forgot_password_attempt(_scope(f"9.9.9.{i}, 203.0.113.50"))[0]
            for i in range(rate_limit.FORGOT_PASSWORD_REQUESTS + 1)
        ]
        assert answers == [True] * rate_limit.FORGOT_PASSWORD_REQUESTS + [False]
    finally:
        rate_limit.reset_account_credential_budget()


def test_the_whole_process_has_a_forgot_password_cap(hops):
    hops(1)
    rate_limit.reset_account_credential_budget()
    try:
        cap = rate_limit.FORGOT_PASSWORD_GLOBAL_PER_MINUTE
        answers = [
            rate_limit.forgot_password_attempt(_scope(f"203.0.113.{i}"))[0] for i in range(cap + 1)
        ]
        assert answers == [True] * cap + [False]
    finally:
        rate_limit.reset_account_credential_budget()


# ==================================================================================================
# B2 — the notice wording carries no secret (no database)
# ==================================================================================================


@pytest.mark.parametrize(
    ("kind", "by", "words"),
    [
        (mailer.PASSWORD_ADDED, "self", "A password was added"),
        (mailer.PASSWORD_CHANGED, "self", "was changed"),
        (mailer.PASSWORD_CHANGED, "administrator", "temporary password"),
    ],
)
def test_password_notices_say_what_happened_and_what_to_do(kind, by, words):
    rendered = mailer.render(kind, {"recipientName": "Meera", "at": "2026-10-17T10:00:00+00:00", "by": by})
    assert words in rendered.text
    assert "sign you out everywhere" in rendered.text
    assert "set-password" not in rendered.text and "token" not in rendered.text
    assert kind in mailer.KINDS


# ==================================================================================================
# The database half
# ==================================================================================================

#: slug -> (role, has a password, two-step on)
ACCOUNTS: dict[str, tuple[str, bool, bool]] = {
    "admin": ("ADMIN", True, False),
    "changer": ("RESEARCHER", True, False),
    "googler": ("RESEARCHER", False, False),
    "googlerTwoStep": ("RESEARCHER", False, True),
    "temp": ("RESEARCHER", True, False),
    "linked": ("RESEARCHER", True, False),
    "selfBudget": ("RESEARCHER", True, False),
    "adminBudget": ("RESEARCHER", True, False),
    "masterA": ("MASTER_ADMIN", True, False),
    "masterB": ("MASTER_ADMIN", True, True),
}


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"secrev-{slug.lower()}-{stamp}@example.org"

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            now = datetime.now(UTC)
            hashed = hash_password(PASSWORD)
            encrypted = two_factor.encrypt_secret(SECRET)
            people: dict[str, Any] = {}
            for slug, (role, has_password, two_step) in ACCOUNTS.items():
                data: dict[str, Any] = {
                    "email": address(slug),
                    "name": f"SecRev {slug}",
                    "role": role,
                    "passwordHash": hashed if has_password else None,
                    "passwordSetAt": now if has_password else None,
                    "authProvider": "LOCAL" if has_password else "GOOGLE",
                }
                if two_step:
                    data |= {
                        "twoFactorEnabled": True,
                        "twoFactorSecret": encrypted,
                        "twoFactorEnabledAt": now,
                    }
                people[slug] = await db.user.create(data=data)
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": now,
                        "notes": "Seeded by tests/test_account_security_review.py.",
                    }
                )
            return people
        finally:
            await db.disconnect()

    people = asyncio.run(seed())
    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def mail_on(monkeypatch):
    """Mail configured, without SES: messages are queued in the outbox and nothing is sent."""
    monkeypatch.setattr(mailer, "mail_configured", lambda settings=None: True)


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _as(world: dict[str, Any], slug: str) -> dict[str, str]:
    person = world["people"][slug]
    return _bearer(create_access_token(subject=person.id, credential=password_credential(person)))


def _read(client: Any, call: Any, **kwargs: Any) -> Any:
    return client.portal.call(functools.partial(call, **kwargs))


def _notices(client: Any, email: str) -> list[str]:
    rows = _read(client, db.emailmessage.find_many, where={"toAddress": email})
    return [row.kind for row in rows]


# ── D2 ─────────────────────────────────────────────────────────────────────────────────────────────


@needs_db
def test_a_password_change_ends_tokens_that_carry_no_fingerprint(world, mail_on):
    client = world["client"]
    person = world["people"]["changer"]
    # A token from before 2026-10-09: no ``cred`` claim, so the fingerprint alone cannot retire it.
    legacy = create_access_token(subject=person.id)
    current = create_access_token(subject=person.id, credential=password_credential(person))
    assert client.get("/api/auth/me", headers=_bearer(legacy)).status_code == 200
    time.sleep(1.1)  # the watermark is compared with a token's whole-second ``iat``

    answer = client.post(
        "/api/auth/change-password",
        json={"currentPassword": PASSWORD, "newPassword": "security-review-new-1"},
        headers=_bearer(current),
    )
    assert answer.status_code == 200, answer.text
    fresh = answer.headers["x-session-token"]

    assert client.get("/api/auth/me", headers=_bearer(legacy)).status_code == 401
    assert client.get("/api/auth/me", headers=_bearer(current)).status_code == 401
    assert client.get("/api/auth/me", headers=_bearer(fresh)).status_code == 200
    row = _read(client, db.user.find_unique, where={"id": person.id})
    assert row.sessionsValidFrom is not None
    assert row.sessionsValidFrom.microsecond == 0
    # B2: the owner hears about it.
    assert mailer.PASSWORD_CHANGED in _notices(client, world["address"]("changer"))


@needs_db
def test_a_first_password_ends_tokens_that_carry_no_fingerprint(world, mail_on):
    client = world["client"]
    person = world["people"]["googler"]
    legacy = create_access_token(subject=person.id)
    time.sleep(1.1)
    current = create_access_token(subject=person.id, credential=password_credential(person))

    answer = client.post(
        "/api/auth/change-password", json={"newPassword": "a-first-password-9"}, headers=_bearer(current)
    )
    assert answer.status_code == 200, answer.text
    fresh = answer.headers["x-session-token"]
    assert client.get("/api/auth/me", headers=_bearer(legacy)).status_code == 401
    assert client.get("/api/auth/me", headers=_bearer(fresh)).status_code == 200
    assert mailer.PASSWORD_ADDED in _notices(client, world["address"]("googler"))


# ── B2 ─────────────────────────────────────────────────────────────────────────────────────────────


@needs_db
def test_a_first_password_on_a_two_step_account_needs_the_code(world, mail_on):
    client = world["client"]
    person = world["people"]["googlerTwoStep"]
    headers = _bearer(create_access_token(subject=person.id, credential=password_credential(person)))
    body = {"newPassword": "a-first-password-10"}

    missing = client.post("/api/auth/change-password", json=body, headers=headers)
    assert missing.status_code == 400
    assert missing.headers.get("x-two-factor-required") == "1"
    current_code = two_factor.current_totp(SECRET)
    wrong = "000000" if current_code != "000000" else "111111"
    refused = client.post(
        "/api/auth/change-password", json={**body, "twoFactorCode": wrong}, headers=headers
    )
    assert refused.status_code == 400
    assert refused.headers.get("x-two-factor-required") == "1"
    assert refused.json()["detail"] == two_factor.WRONG_CODE_DETAIL
    row = _read(client, db.user.find_unique, where={"id": person.id})
    assert row.passwordHash is None, "nothing may be written before the second step is proved"

    accepted = client.post(
        "/api/auth/change-password",
        json={**body, "twoFactorCode": current_code},
        headers=headers,
    )
    assert accepted.status_code == 200, accepted.text
    assert mailer.PASSWORD_ADDED in _notices(client, world["address"]("googlerTwoStep"))


@needs_db
def test_an_administrators_temporary_password_and_a_link_redemption_notify_the_owner(world, mail_on):
    client = world["client"]
    temp = world["people"]["temp"]
    answer = client.patch(
        f"/api/users/{temp.id}", json={"password": "a-temporary-one-1"}, headers=_as(world, "admin")
    )
    assert answer.status_code == 200, answer.text
    assert mailer.PASSWORD_CHANGED in _notices(client, world["address"]("temp"))
    [row] = [
        r
        for r in _read(client, db.emailmessage.find_many, where={"toAddress": world["address"]("temp")})
        if r.kind == mailer.PASSWORD_CHANGED
    ]
    assert row.params["by"] == "administrator"
    assert row.sealed is None, "a notice carries no secret"

    linked = world["people"]["linked"]
    issued = client.post(
        "/api/auth/password-links", json={"userId": linked.id}, headers=_as(world, "admin")
    )
    assert issued.status_code == 201, issued.text
    token = issued.json()["link"].split("token=", 1)[1]
    from urllib.parse import unquote

    redeemed = client.post(
        "/api/auth/set-password", json={"token": unquote(token), "password": "chosen-by-link-1"}
    )
    assert redeemed.status_code == 200, redeemed.text
    assert mailer.PASSWORD_CHANGED in _notices(client, world["address"]("linked"))


@needs_db
def test_without_mail_a_change_still_succeeds(world):
    client = world["client"]
    # The changer's password changed above; sign in with it to get a token bound to it.
    signed = client.post(
        "/api/auth/login",
        json={"email": world["address"]("changer"), "password": "security-review-new-1"},
    )
    assert signed.status_code == 200, signed.text
    answer = client.post(
        "/api/auth/change-password",
        json={"currentPassword": "security-review-new-1", "newPassword": "security-review-new-2"},
        headers=_bearer(signed.json()["accessToken"]),
    )
    assert answer.status_code == 200, answer.text


# ── B1: separate budgets ───────────────────────────────────────────────────────────────────────────


@needs_db
def test_self_service_links_do_not_spend_the_administrators_budget(world):
    client = world["client"]
    person = world["people"]["selfBudget"]
    for _ in range(credential_links.ISSUE_BUDGET):
        _read(client, credential_links.issue_link, user=person, purpose=credential_links.RESET)
    issued = client.post(
        "/api/auth/password-links", json={"userId": person.id}, headers=_as(world, "admin")
    )
    assert issued.status_code == 201, issued.text


@needs_db
def test_administrator_links_do_not_spend_the_self_service_budget(world):
    client = world["client"]
    person = world["people"]["adminBudget"]
    admin = _as(world, "admin")
    for _ in range(credential_links.ISSUE_BUDGET):
        made = client.post("/api/auth/password-links", json={"userId": person.id}, headers=admin)
        assert made.status_code == 201, made.text
    spent = client.post("/api/auth/password-links", json={"userId": person.id}, headers=admin)
    assert spent.status_code == 429
    # The person's own "Forgot your password?" link is still mintable.
    own = _read(client, credential_links.issue_link, user=person, purpose=credential_links.RESET)
    assert own.link


# ── B3 ─────────────────────────────────────────────────────────────────────────────────────────────


@needs_db
def test_nobody_changes_their_own_address(world):
    client = world["client"]
    admin = world["people"]["admin"]
    refused = client.patch(
        f"/api/users/{admin.id}",
        json={"email": f"moved-{world['stamp']}@example.org"},
        headers=_as(world, "admin"),
    )
    assert refused.status_code == 403
    assert refused.json()["detail"] == "Ask another administrator to change your email address."
    renamed = client.patch(
        f"/api/users/{admin.id}", json={"name": "SecRev admin renamed"}, headers=_as(world, "admin")
    )
    assert renamed.status_code == 200, renamed.text
    assert renamed.json()["email"] == world["address"]("admin")


# ── B4 ─────────────────────────────────────────────────────────────────────────────────────────────


@needs_db
def test_a_master_admin_cannot_help_another_master_admin_back_in(world):
    client = world["client"]
    peer = world["people"]["masterB"]
    headers = _as(world, "masterA")
    attempts = [
        client.post(f"/api/users/{peer.id}/sign-out-everywhere", headers=headers),
        client.post("/api/auth/password-links", json={"userId": peer.id}, headers=headers),
        client.patch(f"/api/users/{peer.id}", json={"password": "peer-temporary-1"}, headers=headers),
        client.patch(f"/api/users/{peer.id}", json={"mustChangePassword": True}, headers=headers),
        client.delete(f"/api/users/{peer.id}/two-factor", headers=headers),
    ]
    assert [a.status_code for a in attempts] == [403] * len(attempts), [a.text for a in attempts]
    row = _read(client, db.user.find_unique, where={"id": peer.id})
    assert row.twoFactorEnabled is True and row.sessionsValidFrom is None
