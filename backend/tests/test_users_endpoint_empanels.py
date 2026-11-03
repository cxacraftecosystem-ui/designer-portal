import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.designers import DERIVED_EMPANELMENT_NOTE

pytestmark = [needs_db, pytest.mark.anyio]

PASSWORD = "users-endpoint-empanels-password"
API_PASSWORD = "LocalDev123!"

ADMIN_NOTE = "Empanelled and then withdrawn by hand. Do not restore."


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"fourthdoor-admin-{stamp}@example.org"

    await db.connect()
    try:
        admin = await db.user.create(
            data={
                "email": email,
                "name": f"Fourth door admin {stamp}",
                "role": "ADMIN",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        await db.accessroster.create(
            data={
                "email": email,
                "status": "ACTIVE",
                "admitRole": "ADMIN",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_users_endpoint_empanels.py.",
            }
        )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "admin": admin, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _create_account(world: dict[str, Any], email: str, role: str, label: str) -> Any:
    return world["client"].post(
        "/api/users",
        json={
            "email": email,
            "name": f"{label} {world['stamp']}",
            "password": API_PASSWORD,
            "role": role,
        },
        headers=_headers(world),
    )


def _roster_rows(world: dict[str, Any], *spellings: str, term: str | None = None) -> list[Any]:
    response = world["client"].get(
        "/api/designers/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    wanted = set(spellings)
    return [row for row in response.json()["items"] if row["email"] in wanted]


async def test_a_designer_created_here_is_empanelled_before_they_have_signed_in(world, client):
    email = f"fourthdoor-designer-{world['stamp']}@example.org"
    made = _create_account(world, email, "DESIGNER", "Typed In Designer")
    assert made.status_code == 201, made.text

    rows = _roster_rows(world, email, term=email)
    assert len(rows) == 1, (
        "creating an account AS A DESIGNER left /admin/designers empty, so the admin who just did "
        "it looks for the person, does not find them, and adds the row a second time by hand: "
        f"{rows}"
    )
    assert rows[0]["isActive"] is True
    assert rows[0]["firstSeenAt"] is None, "creating the account is not the designer arriving"
    assert rows[0]["addedById"] == world["admin"].id, (
        "the empanelment does not record which administrator granted it, so it reads as derived "
        "from a sign-in that has not happened"
    )
    assert rows[0]["notes"] == DERIVED_EMPANELMENT_NOTE, (
        "the note is the one ensure_empanelled writes; if this endpoint starts passing its own, "
        "say so here rather than letting the two roster screens describe the same act differently"
    )


async def test_creating_somebody_who_is_not_a_designer_empanels_nobody(world, client):
    email = f"fourthdoor-researcher-{world['stamp']}@example.org"
    made = _create_account(world, email, "RESEARCHER", "Typed In Researcher")
    assert made.status_code == 201, made.text
    assert _roster_rows(world, email, term=email) == [], (
        "creating a RESEARCHER wrote a designer-roster row"
    )


async def test_creating_an_account_never_revives_an_empanelment_an_admin_ended(world, client):
    email = f"fourthdoor-revoked-{world['stamp']}@example.org"
    empanelled = client.post(
        "/api/designers/roster",
        json={"email": email, "notes": ADMIN_NOTE, "isActive": False},
        headers=_headers(world),
    )
    assert empanelled.status_code == 201, empanelled.text
    assert empanelled.json()["isActive"] is False, "the state under test was not reached"

    made = _create_account(world, email, "DESIGNER", "Revoked Designer")
    assert made.status_code == 201, made.text

    rows = _roster_rows(world, email, term=email)
    assert len(rows) == 1, f"a second empanelment was written beside the revoked one: {rows}"
    assert rows[0]["id"] == empanelled.json()["id"]
    assert rows[0]["isActive"] is False, (
        "creating an account revived an empanelment an administrator had ended; every revocation "
        "any administrator ever made would come undone the same way, one new account at a time"
    )
    assert rows[0]["notes"] == ADMIN_NOTE, (
        "the row stayed suspended but lost what the administrator wrote on it, which is the same "
        "defect wearing a smaller hat"
    )


async def test_the_empanelment_is_written_under_the_mailbox_and_not_under_the_typed_address(
    world, client
):
    typed = f"fourthdoor.alias.{world['stamp']}@gmail.com"
    mailbox = f"fourthdooralias{world['stamp']}@gmail.com"
    assert typed != mailbox, "the fixture must hold two spellings, or this test proves nothing"

    made = _create_account(world, typed, "DESIGNER", "Aliased Designer")
    assert made.status_code == 201, made.text
    assert made.json()["email"] == typed, (
        "the account is expected to keep the spelling that was typed — that divergence from the "
        "canonicalised rosters is the whole state under test"
    )

    rows = _roster_rows(world, typed, mailbox)
    assert [row["email"] for row in rows] == [mailbox], (
        "the empanelment was written under the account's spelling rather than the mailbox both "
        f"rosters and the sign-in gate are keyed on: {[row['email'] for row in rows]}"
    )
