"""Renaming a craft onto a name another craft already holds.

``Craft.name`` is ``@unique`` — deliberately, because the craft is the shared taxonomy every other
record points at and two rows spelling one craft two ways is the thing the index exists to stop. The
create path has always answered that collision with 409 and a sentence. The rename path issued the
same write against the same index with no handler, so the driver's ``UniqueViolationError`` reached
the catch-all middleware in ``app.main``, which logged a stack trace and answered 500 with
"Something went wrong on the server. The error has been logged."

WHY THAT IS WORTH A TEST RATHER THAN A SHRUG. The rename is impossible either way — the index sees
to that — so nothing is lost and no data is wrong. What differs is what the professor is told. A 409
says the name is taken, which is actionable (open the other craft, merge into it, pick another
spelling). A 500 says the server is broken, which is not, so they retry — and every retry writes
another stack trace to the journal. The common way to arrive here is the most legitimate edit in the
taxonomy: a professor tidying a duplicate ("Bandhej") onto the canonical spelling ("Bandhani").

Postgres is required: the behaviour under test IS the unique index answering, and a mocked client
would be asserting that the mock raises what the test told it to. The module skips itself when
``DATABASE_URL`` does not point at a local database, exactly as ``test_designer_roster`` does.

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

PASSWORD = "craft-rename-test-password"

# The detail string is asserted verbatim rather than imported, and it is asserted to be the SAME
# string on both routes. That equality is the actual fix: a client (and the professor reading it)
# must not have to learn two different sentences for one collision on one index.
CONFLICT_DETAIL = "Craft name already exists"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """One craft-managing admin, and a per-run stamp so the unique index is not the flake.

    Fixed craft names would pass on a clean database and fail on the second run of the suite, which
    is the sort of failure that gets "fixed" by dropping the database — throwing away whatever it
    was protecting. Rows are made before the app starts and removed after it stops, because the
    Prisma client is bound to the TestClient's event loop; touching it from a test's own loop is
    cross-loop use that fails intermittently rather than honestly.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        admin = await db.user.create(data={
            "email": f"craft-rename-{stamp}@example.org",
            "name": "Craft Taxonomy Admin",
            "role": "ADMIN",
            "passwordHash": hash_password(PASSWORD),
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "admin": admin, "stamp": stamp}

    await db.connect()
    try:
        await db.craft.delete_many(where={"name": {"contains": stamp}})
        await db.user.delete(where={"id": admin.id})
    finally:
        await db.disconnect()


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _create(client: Any, world: dict[str, Any], name: str) -> Any:
    return client.post("/api/crafts", json={"name": name}, headers=_headers(world))


async def test_renaming_a_craft_onto_an_existing_name_is_the_same_409_as_creating_one(world, client):
    """The whole finding in one assertion pair: two routes, one index, one answer.

    Both halves are asserted in the same test on purpose. The bug was never "the update lacks a
    handler" in the abstract — it was that the two routes disagreed, so a test that pinned only the
    update's status could be satisfied by any status at all as long as somebody wrote it down.
    """
    canonical = f"Bandhani {world['stamp']}"
    duplicate = f"Bandhej {world['stamp']}"

    first = _create(client, world, canonical)
    assert first.status_code == 201, first.text
    second = _create(client, world, duplicate)
    assert second.status_code == 201, second.text

    # The create's answer, which was never in doubt.
    clash_on_create = _create(client, world, canonical)
    assert clash_on_create.status_code == 409, clash_on_create.text
    assert clash_on_create.json()["detail"] == CONFLICT_DETAIL

    # The rename onto that same occupied name: a 500 with an opaque sentence before the fix.
    clash_on_rename = client.patch(
        f"/api/crafts/{second.json()['id']}",
        json={"name": canonical},
        headers=_headers(world),
    )
    assert clash_on_rename.status_code == 409, clash_on_rename.text
    assert clash_on_rename.json()["detail"] == CONFLICT_DETAIL

    # The refusal must not have half-applied the edit: the duplicate keeps its own name, and the
    # canonical row is still the one holding it.
    unchanged = client.get(f"/api/crafts/{second.json()['id']}", headers=_headers(world))
    assert unchanged.json()["name"] == duplicate


async def test_a_rename_that_does_not_collide_still_goes_through(world, client):
    """The guard rail against the cheapest wrong fix — refusing every rename — and against catching
    an exception class so broad that a legitimate PATCH ends up reported as a name clash."""
    craft = _create(client, world, f"Sambalpuri Ikat {world['stamp']}")
    assert craft.status_code == 201, craft.text

    renamed = client.patch(
        f"/api/crafts/{craft.json()['id']}",
        json={"name": f"Sambalpuri Bandha {world['stamp']}", "category": "Weaving"},
        headers=_headers(world),
    )

    assert renamed.status_code == 200, renamed.text
    assert renamed.json()["name"] == f"Sambalpuri Bandha {world['stamp']}"


async def test_renaming_a_craft_to_the_name_it_already_has_is_not_a_conflict(world, client):
    """A PATCH that re-sends the row's own name touches the index but does not violate it. If this
    ever 409s, the handler has started answering on the strength of the write being attempted rather
    than on the database's verdict."""
    name = f"Dhokra Casting {world['stamp']}"
    craft = _create(client, world, name)
    assert craft.status_code == 201, craft.text

    again = client.patch(
        f"/api/crafts/{craft.json()['id']}",
        json={"name": name, "place": "Bastar"},
        headers=_headers(world),
    )

    assert again.status_code == 200, again.text
    assert again.json()["place"] == "Bastar"
