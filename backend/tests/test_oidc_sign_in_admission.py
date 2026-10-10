"""Microsoft and Yahoo sign-in through ``POST /auth/login``: admission, linking and refusals.

What a PROVED Microsoft or Yahoo identity may do is exactly what a proved Google identity may do —
the same allow-list gate, before any write, and the same account found under the same address —
with one deliberate difference: no Gmail-spelling fold to FIND an account, because neither provider
runs Gmail or promises anything about another spelling of the address it verified. Every case below
goes through the real route with real signed tokens (``oidc_fake_idp``); only the provider's two
HTTP answers are replaced.

Postgres is required, and the module follows the sync pattern ``tests/conftest.py`` describes: one
``asyncio.run`` seeds every row before the ``TestClient`` starts, the tests are sync and drive the
client, and nothing here awaits ``db``.

    docker compose up -d postgres minio          # from the REPOSITORY ROOT, not from backend/
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

from __future__ import annotations

import asyncio
import os
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from oidc_fake_idp import WORK_TENANT, FakeIdP, login_body

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
]

PASSWORD = "a-long-enough-password"


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    tag = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"oidc-{slug}-{tag}@example.org"

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            now = datetime.now(UTC)
            admin = await db.user.create(data={
                "email": address("admin"),
                "name": "OIDC Admin",
                "role": "ADMIN",
                "passwordHash": hash_password(PASSWORD),
                "passwordSetAt": now,
                "authProvider": "LOCAL",
            })
            local = await db.user.create(data={
                "email": address("local"),
                "name": "Name An Administrator Typed",
                "role": "RESEARCHER",
                "passwordHash": hash_password(PASSWORD),
                "passwordSetAt": now,
                "authProvider": "LOCAL",
            })
            google_only = await db.user.create(data={
                "email": address("google"),
                "name": "Google Name",
                "role": "RESEARCHER",
                "avatarUrl": "https://example.org/avatar.png",
                "authProvider": "GOOGLE",
            })
            gmail_dotted = f"oidc.fold.{tag}@gmail.com"
            folded_local = await db.user.create(data={
                "email": gmail_dotted,
                "name": "Gmail Dotted Local",
                "role": "RESEARCHER",
                "passwordHash": hash_password(PASSWORD),
                "passwordSetAt": now,
                "authProvider": "LOCAL",
            })
            admitted = {
                address("new-ms"): "RESEARCHER",
                address("new-yahoo"): "FIELD_CONTRIBUTOR",
                address("local"): "RESEARCHER",
                address("google"): "RESEARCHER",
                address("unverified"): "RESEARCHER",
                # The allow-list holds the mailbox's canonical spelling; the password account sits
                # under the dotted one an administrator typed.
                f"oidcfold{tag}@gmail.com": "RESEARCHER",
            }
            for email, role in admitted.items():
                await db.accessroster.create(data={
                    "email": email, "status": "ACTIVE", "admitRole": role, "joinedAt": now,
                })
            await db.accessroster.create(data={
                "email": address("suspended"), "status": "SUSPENDED", "decidedAt": now,
            })
            return {
                "admin": admin.id,
                "local": local.id,
                "google_only": google_only.id,
                "folded_local": folded_local.id,
                "gmail_undotted": f"oidcfold{tag}@gmail.com",
                "address": address,
                "tag": tag,
            }
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


@pytest.fixture
def client(world):
    return world["client"]


def _admin(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['admin'])}"}


def _accounts(client: Any, world: dict[str, Any], term: str) -> dict[str, Any]:
    response = client.get("/api/users/directory", params={"search": term}, headers=_admin(world))
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()}


def _microsoft(client: Any, monkeypatch: Any, email: str, **claims: Any) -> Any:
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.microsoft_claims(email, **claims)))
    return client.post("/api/auth/login", json=login_body("MICROSOFT"))


def _yahoo(client: Any, monkeypatch: Any, email: str, **claims: Any) -> Any:
    idp = FakeIdP().install(monkeypatch)
    idp.issue(idp.sign(idp.yahoo_claims(email, **claims), alg="ES256"))
    return client.post("/api/auth/login", json=login_body("YAHOO"))


def test_an_admitted_microsoft_address_gets_an_account_at_its_admitted_tier(world, client, monkeypatch):
    response = _microsoft(client, monkeypatch, world["address"]("new-ms").upper())
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["accessToken"]
    assert body["user"]["email"] == world["address"]("new-ms")
    assert body["user"]["authProvider"] == "MICROSOFT"
    assert body["user"]["role"] == "RESEARCHER"
    assert body["user"]["name"] == "Meera Iyer"
    assert "passwordHash" not in body["user"]


def test_an_admitted_yahoo_address_gets_an_account_at_its_admitted_tier(world, client, monkeypatch):
    response = _yahoo(client, monkeypatch, world["address"]("new-yahoo"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["authProvider"] == "YAHOO"
    assert response.json()["user"]["role"] == "FIELD_CONTRIBUTOR"


def test_an_unknown_address_becomes_a_pending_request_and_gets_no_account(world, client, monkeypatch):
    stranger = world["address"]("stranger")
    response = _microsoft(client, monkeypatch, stranger)
    assert response.status_code == 403, response.text
    assert response.headers.get("X-Access-Status") == "PENDING"
    assert set(response.json()) == {"detail"}
    assert stranger not in _accounts(client, world, stranger)


def test_a_suspended_address_is_refused_as_suspended(world, client, monkeypatch):
    response = _yahoo(client, monkeypatch, world["address"]("suspended"))
    assert response.status_code == 403
    assert response.headers.get("X-Access-Status") == "SUSPENDED"


def test_an_unverified_address_is_refused_before_admission_and_creates_nothing(world, client, monkeypatch):
    unverified = world["address"]("unverified")
    response = _microsoft(client, monkeypatch, unverified, tenant=WORK_TENANT)
    assert response.status_code == 401, response.text
    assert "has not confirmed the email address" in response.json()["detail"]
    assert unverified not in _accounts(client, world, unverified)
    response = _yahoo(client, monkeypatch, unverified, email_verified=False)
    assert response.status_code == 401


def test_a_password_account_is_signed_in_to_and_keeps_what_its_provisioner_gave_it(world, client, monkeypatch):
    response = _yahoo(client, monkeypatch, world["address"]("local"))
    assert response.status_code == 200, response.text
    user = response.json()["user"]
    assert user["id"] == world["local"]
    assert user["authProvider"] == "LOCAL"
    assert user["name"] == "Name An Administrator Typed"
    # The password still works: a second way in, not a conversion.
    password = client.post("/api/auth/login", json={"email": world["address"]("local"), "password": PASSWORD})
    assert password.status_code == 200, password.text


def test_a_google_account_keeps_its_provider_and_its_avatar(world, client, monkeypatch):
    response = _microsoft(client, monkeypatch, world["address"]("google"))
    assert response.status_code == 200, response.text
    user = response.json()["user"]
    assert user["id"] == world["google_only"]
    assert user["authProvider"] == "GOOGLE"
    assert user["avatarUrl"] == "https://example.org/avatar.png"


def test_no_gmail_spelling_is_folded_to_find_an_account(world, client, monkeypatch):
    """The allow-list admits the mailbox (its rule for every sign-in path), but the account a
    Microsoft identity signs in to is the one at the literal address it verified — never the
    password account an administrator filed under another spelling."""
    response = _microsoft(client, monkeypatch, world["gmail_undotted"])
    assert response.status_code == 200, response.text
    user = response.json()["user"]
    assert user["id"] != world["folded_local"]
    assert user["email"] == world["gmail_undotted"]


def test_a_forged_token_is_refused_with_one_sentence_and_no_detail(world, client, monkeypatch):
    idp = FakeIdP().install(monkeypatch)
    claims = idp.microsoft_claims(world["address"]("new-ms"))
    idp.issue(idp.sign(claims, key=idp.stranger_key))
    response = client.post("/api/auth/login", json=login_body("MICROSOFT"))
    assert response.status_code == 401
    assert response.json() == {
        "detail": "Signing in with Microsoft did not complete. Try again, or use another way to sign in."
    }


def test_an_unconfigured_provider_is_not_available(world, client, monkeypatch):
    FakeIdP().install(monkeypatch, microsoft_client_id="")
    response = client.post("/api/auth/login", json=login_body("MICROSOFT"))
    assert response.status_code == 400
    assert response.json()["detail"] == "Signing in with Microsoft is not available here. Use another way to sign in."
