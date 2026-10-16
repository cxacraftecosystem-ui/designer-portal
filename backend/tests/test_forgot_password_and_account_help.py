"""ACCOUNT PARITY (2026-10-17): "Forgot your password?", a first password for a provider-only
account, "Sign out of every device", the access list's "with a password", and ``hasPassword``.

The forgot-password door is ported from cxa-cms (``lib/auth/password-reset.ts``) and every assertion
below about it is one of that file's rules: ONE answer whatever the address, the address-dependent
work done after the answer, a per-connection 429 and a SILENT per-address cap, no mail for an account
with no password or one the allow-list does not admit, an audit row for every request, and a link
whose origin is the configured one and never the request's ``Host``.

Postgres is required — the routes read accounts, the allow-list and the link table — so the module
skips itself when ``DATABASE_URL`` does not point at a local database. Rows written by the app are
read back through ``client.portal`` (``tests/test_stage_sync.py`` carries the argument): the Prisma
connection belongs to the TestClient's loop once the ``with`` block is open.
"""

import asyncio
import functools
import time
import uuid
from datetime import UTC, datetime
from typing import Any

import jwt
import pytest
from conftest import needs_db

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import password_credential
from app.core.security import create_access_token, hash_password
from app.services import mailer, managed_secrets, password_reset

pytestmark = [needs_db]

PASSWORD = "forgot-password-original-1"

#: The one answer, written out rather than imported so a rewording is a decision a test notices.
ONE_ANSWER = (
    "If that address belongs to an account that signs in with a password, a link to choose a new "
    "password is on its way. It works once and lasts 2 hours. If nothing arrives within a few "
    "minutes, check your spam folder, or ask an administrator to make you a link."
)

#: slug -> (role, has a password, allow-list status or None, provider)
ACCOUNTS: dict[str, tuple[str, bool, str | None, str]] = {
    "admin": ("ADMIN", True, "ACTIVE", "LOCAL"),
    "researcher": ("RESEARCHER", True, "ACTIVE", "LOCAL"),
    "capped": ("RESEARCHER", True, "ACTIVE", "LOCAL"),
    "barred": ("RESEARCHER", True, "SUSPENDED", "LOCAL"),
    "googler": ("RESEARCHER", False, "ACTIVE", "GOOGLE"),
    "staleGoogler": ("RESEARCHER", False, "ACTIVE", "GOOGLE"),
    "target": ("RESEARCHER", True, "ACTIVE", "LOCAL"),
}


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"forgot-{slug}-{stamp}@example.org".lower()

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            now = datetime.now(UTC)
            people: dict[str, Any] = {}
            for slug, (role, has_password, state, provider) in ACCOUNTS.items():
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"Forgot {slug} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD) if has_password else None,
                        "passwordSetAt": now if has_password else None,
                        "authProvider": provider,
                    }
                )
                if state:
                    await db.accessroster.create(
                        data={
                            "email": address(slug),
                            "status": state,
                            "admitRole": role,
                            "joinedAt": now,
                            "notes": "Seeded by tests/test_forgot_password_and_account_help.py.",
                        }
                    )
            return people
        finally:
            await db.disconnect()

    people = asyncio.run(seed())
    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


@pytest.fixture
def mail_on(monkeypatch):
    """Mail configured, without SES: messages are queued in the outbox and nothing is sent."""
    monkeypatch.setattr(mailer, "mail_configured", lambda settings=None: True)


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _read(client: Any, call: Any, **kwargs: Any) -> Any:
    return client.portal.call(functools.partial(call, **kwargs))


def _requests_for(client: Any, email: str) -> list[Any]:
    return _read(
        client, db.passwordresetrequest.find_many, where={"email": email}, order={"createdAt": "asc"}
    )


def _messages_to(client: Any, email: str) -> list[Any]:
    return _read(client, db.emailmessage.find_many, where={"toAddress": email})


def _forgot(client: Any, email: str, **headers: str) -> Any:
    return client.post("/api/auth/forgot-password", json={"email": email}, headers=headers)


# ==================================================================================================
# "Forgot your password?"
# ==================================================================================================


def test_known_and_unknown_addresses_get_the_same_answer(world, client, mail_on):
    known = world["address"]("researcher")
    unknown = f"nobody-{world['stamp']}@example.org"

    first = _forgot(client, known, Host="evil.example", **{"X-Forwarded-Host": "evil.example"})
    second = _forgot(client, unknown)

    for answer in (first, second):
        assert answer.status_code == 200, answer.text
        assert answer.json() == {"message": ONE_ANSWER}
        assert answer.headers.get("cache-control") == "no-store"
    assert first.content == second.content

    assert [row.outcome for row in _requests_for(client, known)] == [password_reset.EMAILED]
    assert [row.outcome for row in _requests_for(client, unknown)] == [password_reset.UNKNOWN_ADDRESS]
    assert _requests_for(client, unknown)[0].accountId is None
    assert _messages_to(client, unknown) == []

    # The link went to the account's own address, as a RESET nobody issued.
    tokens = _read(
        client,
        db.passwordresettoken.find_many,
        where={"userId": world["people"]["researcher"].id},
    )
    assert len(tokens) == 1
    assert tokens[0].issuedById is None
    assert str(tokens[0].purpose.value if hasattr(tokens[0].purpose, "value") else tokens[0].purpose) == "RESET"


def test_the_link_is_built_from_the_configured_origin_not_the_host_header(world, client, mail_on):
    """The previous test sent a forged ``Host`` and ``X-Forwarded-Host``; the sealed link must not
    carry either."""
    known = world["address"]("researcher")
    messages = _messages_to(client, known)
    assert messages, "no message was queued for the known address"
    link = managed_secrets.decrypt(messages[-1].sealed)
    assert link is not None
    origin = str(get_settings().next_public_app_url).rstrip("/")
    assert link.startswith(f"{origin}/set-password?token="), link.split("?")[0]
    assert "evil.example" not in link


def test_no_mail_for_an_account_with_no_password_or_one_the_allow_list_bars(world, client, mail_on):
    for slug, outcome in (("googler", password_reset.NO_PASSWORD), ("barred", password_reset.NOT_ADMITTED)):
        email = world["address"](slug)
        answer = _forgot(client, email)
        assert answer.status_code == 200 and answer.json() == {"message": ONE_ANSWER}
        rows = _requests_for(client, email)
        assert [row.outcome for row in rows] == [outcome]
        assert rows[0].accountId == world["people"][slug].id
        assert _messages_to(client, email) == []


def test_the_per_address_cap_is_silent(world, client, mail_on):
    email = world["address"]("capped")
    answers = [_forgot(client, email) for _ in range(password_reset.ADDRESS_BUDGET + 1)]
    assert [a.status_code for a in answers] == [200] * (password_reset.ADDRESS_BUDGET + 1)
    assert len({a.content for a in answers}) == 1
    outcomes = [row.outcome for row in _requests_for(client, email)]
    assert outcomes == [password_reset.EMAILED] * password_reset.ADDRESS_BUDGET + [
        password_reset.ADDRESS_RATE_LIMITED
    ]
    assert len(_messages_to(client, email)) == password_reset.ADDRESS_BUDGET


def test_without_mail_nothing_is_sent_and_the_answer_is_the_same(world, client):
    email = world["address"]("target")
    answer = _forgot(client, email)
    assert answer.status_code == 200 and answer.json() == {"message": ONE_ANSWER}
    assert [row.outcome for row in _requests_for(client, email)] == [password_reset.EMAIL_NOT_CONFIGURED]


def test_the_per_connection_limit_is_a_429_and_a_malformed_address_a_422(world, client):
    malformed = client.post("/api/auth/forgot-password", json={"email": "not an address"})
    assert malformed.status_code == 422
    budget = 5
    statuses = [
        _forgot(client, f"flood-{i}-{world['stamp']}@example.org").status_code for i in range(budget + 1)
    ]
    assert statuses == [200] * budget + [429], statuses
    refused = _forgot(client, f"flood-last-{world['stamp']}@example.org")
    assert refused.status_code == 429
    assert refused.json()["detail"] == password_reset.TOO_MANY_REQUESTS_DETAIL
    assert int(refused.headers["retry-after"]) >= 1


def test_the_audit_row_records_the_client(world, client):
    email = f"audited-{world['stamp']}@example.org"
    _forgot(client, email, **{"User-Agent": "parity-test-agent"})
    [row] = _requests_for(client, email)
    assert row.userAgent == "parity-test-agent"
    assert row.ipAddress


# ==================================================================================================
# A first password for an account that signs in with a provider
# ==================================================================================================


def _fresh_google(monkeypatch, email: str, *, age_seconds: int = 0) -> dict[str, str]:
    """A Google ID token, as ``verify_google_token`` would answer for one issued ``age_seconds`` ago."""
    from app.api.routes import auth as auth_routes

    claims = {"email": email, "email_verified": True, "iat": int(time.time()) - age_seconds}
    monkeypatch.setattr(auth_routes, "verify_google_token", lambda _token: claims)
    return {"googleIdToken": "a-google-id-token"}


def test_a_provider_only_account_sets_a_first_password_with_a_fresh_provider_sign_in(
    world, client, monkeypatch
):
    person = world["people"]["googler"]
    old = create_access_token(subject=person.id, credential=password_credential(person))
    me = client.get("/api/auth/me", headers={"Authorization": f"Bearer {old}"})
    assert me.status_code == 200 and me.json()["hasPassword"] is False

    answer = client.post(
        "/api/auth/change-password",
        json={"newPassword": "a-first-password-1", **_fresh_google(monkeypatch, world["address"]("googler"))},
        headers={"Authorization": f"Bearer {old}"},
    )
    assert answer.status_code == 200, answer.text
    assert answer.json() == {"ok": True}
    fresh = answer.headers["x-session-token"]

    after = client.get("/api/auth/me", headers={"Authorization": f"Bearer {fresh}"})
    assert after.status_code == 200 and after.json()["hasPassword"] is True
    # The session the password was set from carries on; any other one opened before it ends.
    assert client.get("/api/auth/me", headers={"Authorization": f"Bearer {old}"}).status_code == 401
    # And the account now signs in either way: with the password, too.
    signed_in = client.post(
        "/api/auth/login",
        json={"email": world["address"]("googler"), "password": "a-first-password-1"},
    )
    assert signed_in.status_code == 200, signed_in.text


def test_without_a_fresh_provider_sign_in_it_is_asked_to_confirm(world, client, monkeypatch):
    """A session is not proof any more (2026-10-17): an hour-old one and a brand-new one alike are
    asked for the provider's own proof, and a stale proof is refused the same way."""
    person = world["people"]["staleGoogler"]
    settings = get_settings()
    now = int(time.time())
    stale = jwt.encode(
        {"sub": person.id, "iat": now - 3600, "exp": now + 3600},
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    refused = client.post(
        "/api/auth/change-password",
        json={"newPassword": "a-first-password-2"},
        headers={"Authorization": f"Bearer {stale}"},
    )
    assert refused.status_code == 400
    assert refused.headers.get("x-confirm-provider") == "GOOGLE"
    stale_proof = client.post(
        "/api/auth/change-password",
        json={
            "newPassword": "a-first-password-2",
            **_fresh_google(monkeypatch, world["address"]("staleGoogler"), age_seconds=600),
        },
        headers={"Authorization": f"Bearer {stale}"},
    )
    assert stale_proof.status_code == 400
    assert stale_proof.headers.get("x-confirm-provider") == "GOOGLE"
    row = _read(client, db.user.find_unique, where={"id": person.id})
    assert row.passwordHash is None


# ==================================================================================================
# Sign out of every device
# ==================================================================================================


def test_an_administrator_signs_somebody_out_of_every_device(world, client):
    target = world["people"]["target"]
    held = _headers(world, "target")
    assert client.get("/api/auth/me", headers=held).status_code == 200
    time.sleep(1.1)  # the watermark is compared with a token's whole-second ``iat``

    answer = client.post(f"/api/users/{target.id}/sign-out-everywhere", headers=_headers(world, "admin"))
    assert answer.status_code == 200, answer.text
    assert answer.json()["id"] == target.id
    assert "hasPassword" in answer.json() and "passwordHash" not in answer.json()
    assert client.get("/api/auth/me", headers=held).status_code == 401

    # The password still works: nothing but the sessions changed.
    again = client.post("/api/auth/login", json={"email": world["address"]("target"), "password": PASSWORD})
    assert again.status_code == 200, again.text


def test_sign_out_everywhere_is_refused_for_yourself_and_for_non_provisioners(world, client):
    admin = world["people"]["admin"]
    own = client.post(f"/api/users/{admin.id}/sign-out-everywhere", headers=_headers(world, "admin"))
    assert own.status_code == 403
    nobody = client.post(
        f"/api/users/{world['people']['capped'].id}/sign-out-everywhere",
        headers=_headers(world, "researcher"),
    )
    assert nobody.status_code == 403
    missing = client.post("/api/users/no-such-account/sign-out-everywhere", headers=_headers(world, "admin"))
    assert missing.status_code == 404


# ==================================================================================================
# The access list's "with a password"
# ==================================================================================================


def test_the_access_list_can_admit_an_address_with_a_password(world, client):
    email = f"forgot-roster-pw-{world['stamp']}@example.org"
    made = client.post(
        "/api/access/roster",
        json={
            "email": email,
            "fullName": "Roster Password Person",
            "role": "RESEARCHER",
            "password": "a-temporary-password-1",
        },
        headers=_headers(world, "admin"),
    )
    assert made.status_code == 201, made.text
    assert made.json()["accountCreated"] is True
    assert made.json()["status"] == "ACTIVE"

    signed_in = client.post("/api/auth/login", json={"email": email, "password": "a-temporary-password-1"})
    assert signed_in.status_code == 200, signed_in.text
    user = signed_in.json()["user"]
    assert user["mustChangePassword"] is True
    assert user["hasPassword"] is True
    assert user["name"] == "Roster Password Person"

    again = client.post(
        "/api/access/roster",
        json={"email": email, "password": "a-temporary-password-1"},
        headers=_headers(world, "admin"),
    )
    assert again.status_code == 409


def test_the_access_list_without_a_password_still_creates_no_account(world, client):
    email = f"forgot-roster-google-{world['stamp']}@example.org"
    made = client.post(
        "/api/access/roster",
        json={"email": email, "fullName": "Roster Google Person"},
        headers=_headers(world, "admin"),
    )
    assert made.status_code == 201, made.text
    assert "accountCreated" not in made.json()
    assert _read(client, db.user.find_unique, where={"email": email}) is None
