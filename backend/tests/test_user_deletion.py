"""Removing a colleague from Settings > Users, against a real database.

The rule this file pins is a property of the SCHEMA, not of the route: every creator relation on
``User`` is ``onDelete: Restrict`` — Artisan, Workshop, Product, Tool, Media, Questionnaire,
Process, ReviewLog, DesignWorkshop — because research data must outlive the person who recorded
it. So the delete Postgres refuses is the ordinary case rather than an edge one, and what the
admin is told about it can only be tested with Postgres underneath.

Run the local stack first:

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import os
import uuid
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """An admin who does the removing, and a TestClient sharing the app's Prisma connection."""
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        admin = await db.user.create(data={
            "email": f"user-del-admin-{stamp}@example.org",
            "name": "Deleting Admin",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        client.headers.update({"Authorization": f"Bearer {create_access_token(subject=admin.id)}"})
        yield {"client": client, "admin": admin, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


async def _designer(world: dict[str, Any], slug: str) -> Any:
    """A designer created through the API, so the row is made the way the app makes it."""
    made = world["client"].post("/api/users", json={
        "email": f"user-del-{slug}-{world['stamp']}@example.org",
        "name": f"Departing {slug}",
        "password": "LocalDev123!",
        "role": "DESIGNER",
    })
    assert made.status_code == 201, made.text
    return made.json()


async def test_deleting_an_account_that_created_nothing_still_works(client, world):
    """The control. Without it, a 409 for everything would pass the test below and break the
    endpoint entirely."""
    account = await _designer(world, "unused")
    removed = client.delete(f"/api/users/{account['id']}")
    assert removed.status_code == 204, removed.text


async def test_removing_a_colleague_who_did_work_says_what_is_in_the_way(client, world):
    """THE REGRESSION.

    ``await db.user.delete(...)`` had no except clause, so the ``ForeignKeyViolationError`` every
    creator relation guarantees became a bare 500 and the admin read "Something went wrong on the
    server. The error has been logged." — which says nothing about the situation (the account owns
    research data and must be deactivated or reassigned) and nothing they can act on.

    Any colleague who did any work at all is undeletable, so the only accounts the endpoint could
    actually delete were the ones that never did anything — precisely backwards from what an admin
    does when a designer leaves the project.

    THE WORK IS A QUESTIONNAIRE AND NOT A DESIGN WORKSHOP, AND THAT CHANGED WITH THE CREATE RULE.
    This test used to have the departing designer POST a design workshop; only admins and the master
    admin may start one now (``can_create_design_workshops``), so that line answers 403 and the
    account under test would own nothing at all — the 409 this exists to pin would never be reached
    and the test would fail on its setup rather than on its subject.

    A questionnaire is the right substitute rather than a convenient one. It is a research
    instrument a designer builds for their own workshop, ``Questionnaire.ownerId`` is in
    ``_CREATOR_RELATIONS`` exactly as ``DesignWorkshop.createdById`` is, and it is precisely the
    "records under existing workshops" that designers still create. The assertion moved from
    "1 design workshop" to "1 questionnaire" for that reason and for no other: what is being pinned
    — that the 409 names what is in the way, how much of it, and what to do instead — is unchanged.
    """
    account = await _designer(world, "worked")
    token = create_access_token(subject=account["id"])
    made = client.post(
        "/api/questionnaires",
        json={
            "title": f"Their fieldwork {world['stamp']}",
            "sections": [
                {"code": "A", "title": "About the loom", "questions": [{"prompt": "How many looms?"}]}
            ],
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert made.status_code == 201, made.text

    refused = client.delete(f"/api/users/{account['id']}")
    assert refused.status_code == 409, refused.text
    detail = refused.json()["detail"]
    # The three things the message has to carry: what is in the way, HOW MUCH of it (three
    # records is a reassignment, four hundred is a deactivation), and what to do instead.
    assert "1 questionnaire" in detail
    assert "deactivate" in detail.lower()

    # THE REMEDY THE MESSAGE NAMES MUST BE REAL. Once nothing references the account, the same
    # request succeeds — so "reassign its records, then remove the account" is advice that works
    # rather than a sentence written to fill a 409.
    #
    # Its OWN Prisma client, and NOT the API's delete: that one is a soft delete (the row and
    # every stage entry stay, deliberately), so it would leave the foreign key exactly where it
    # was. The ``db`` singleton is bound to the TestClient's event loop, and awaiting it from this
    # test's loop fails with "bound to a different event loop" rather than with anything to do
    # with the rule under test.
    from prisma import Prisma

    own = Prisma()
    await own.connect()
    try:
        # Cascades to the sections and questions the create wrote, which are what actually hold the
        # foreign keys back — deleting only the questionnaire row would leave them behind and the
        # second delete would 409 again for a different, unrelated reason.
        await own.questionnaire.delete(where={"id": made.json()["id"]})
    finally:
        await own.disconnect()

    again = client.delete(f"/api/users/{account['id']}")
    assert again.status_code == 204, again.text
