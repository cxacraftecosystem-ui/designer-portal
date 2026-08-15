"""What one master admin may do to another, against a real database.

The rule under test is the one place the two permission mirrors disagreed about the HIGHEST
privilege class in the system. ``canManageUser`` in ``frontend/lib/permissions.ts`` returns
``target.role !== "MASTER_ADMIN" || target.id === user?.id``, so both browsers render a second
master-admin row with no controls on it, and ``docs/PERMISSIONS.md`` §2 states that rule as the
system's. ``assert_can_manage_target`` returned unconditionally for a master admin, so
``PATCH /users/{id}`` and ``DELETE /users/{id}`` accepted exactly that target. The only peer
protection that existed — ``assert_not_demoting_master`` — keys on ``MASTER_ADMIN_EMAIL`` from the
environment rather than on the role, so it protects one ADDRESS and not the privilege.

That combination is not an escalation (the UI is the stricter side) but it is an operator whose
model of who can remove whom is wrong in whichever direction they formed it: a deputy promoted for
a handover reads "protected" off the screen and is demotable and deletable with one curl.

Driven over HTTP against Postgres rather than by calling the guard directly, because the guard is
not the only thing standing between the request and the row — ``update_user`` branches on self
first, and ``delete_user`` checks the configured master email and self BEFORE reaching it. A unit
test of ``assert_can_manage_target`` would pass while either route routed around it.

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
    """A master admin who does the managing, and a TestClient sharing the app's Prisma connection.

    The actor is created directly in the database and NOT through ``POST /users``: minting the
    first MASTER_ADMIN over the API needs a master admin to already be signed in, and its address
    is deliberately NOT ``MASTER_ADMIN_EMAIL`` — the whole point of the rule being tested is that
    protection follows the ROLE and not the one address in the environment. If the fixture used the
    configured address, ``assert_not_demoting_master`` would answer every assertion below and the
    guard under test would never run.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        root = await db.user.create(data={
            "email": f"peer-root-{stamp}@example.org",
            "name": "Acting Master Admin",
            "role": "MASTER_ADMIN",
            "passwordHash": hash_password("unused"),
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        client.headers.update({"Authorization": f"Bearer {create_access_token(subject=root.id)}"})
        yield {"client": client, "root": root, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


async def _account(world: dict[str, Any], slug: str, role: str) -> dict[str, Any]:
    """An account created through the API, so the row is made the way the app makes it."""
    made = world["client"].post("/api/users", json={
        "email": f"peer-{slug}-{world['stamp']}@example.org",
        "name": f"Peer {slug}",
        "password": "LocalDev123!",
        "role": role,
    })
    assert made.status_code == 201, made.text
    return made.json()


async def test_minting_a_peer_and_managing_a_lower_tier_both_still_work(client, world):
    """The control, and it is not decoration. A guard written slightly too wide turns every
    admin-management flow into a 403, and "the peer is refused" would still pass against a route
    that refuses everybody. Minting stays open deliberately: the new rule is that a promotion to
    MASTER_ADMIN is a ONE-WAY door through the API, not that it cannot be made.
    """
    deputy = await _account(world, "mintable", "MASTER_ADMIN")
    assert deputy["role"] == "MASTER_ADMIN"

    ordinary = await _account(world, "ordinary", "ADMIN")
    renamed = client.patch(f"/api/users/{ordinary['id']}", json={"name": "Renamed Admin"})
    assert renamed.status_code == 200, renamed.text
    assert renamed.json()["name"] == "Renamed Admin"

    # And the self-exception the browsers carry (``target.id === user?.id``) is still open: a master
    # admin edits their own identity fields. Nothing in the peer guard may close this.
    own = client.patch(f"/api/users/{world['root'].id}", json={"name": "Acting Master Admin II"})
    assert own.status_code == 200, own.text


async def test_a_master_admin_cannot_demote_a_master_admin_peer(client, world):
    """THE REGRESSION, PATCH half.

    ``assert_can_manage_target`` was ``if is_master_admin(current_user): return`` with no peer test,
    so this request succeeded and the deputy came back as an ADMIN — while both browsers had
    rendered that row with no controls at all.
    """
    deputy = await _account(world, "demote", "MASTER_ADMIN")

    refused = client.patch(f"/api/users/{deputy['id']}", json={"role": "ADMIN"})
    assert refused.status_code == 403, refused.text
    detail = refused.json()["detail"]
    assert "peers" in detail, detail
    # The message has to say what the reader can actually do instead, not merely that they cannot.
    assert "database access" in detail, detail

    # THE ROW IS UNCHANGED. A 403 raised after the write would be a worse defect than the one being
    # fixed, and the status code alone cannot tell the two apart.
    still = client.get("/api/users", params={"search": deputy["email"]})
    assert still.status_code == 200, still.text
    rows = [row for row in still.json()["items"] if row["id"] == deputy["id"]]
    assert rows and rows[0]["role"] == "MASTER_ADMIN"


async def test_a_master_admin_cannot_delete_a_master_admin_peer(client, world):
    """THE REGRESSION, DELETE half.

    ``delete_user`` refuses the CONFIGURED master-admin address by email before it reaches the
    guard, which is exactly why this test's peer holds an ordinary address: any other MASTER_ADMIN
    account fell straight through to ``assert_can_manage_target``, which let it past. The account
    that had just been promoted for a handover was the one account nobody believed was deletable.
    """
    deputy = await _account(world, "delete", "MASTER_ADMIN")

    refused = client.delete(f"/api/users/{deputy['id']}")
    assert refused.status_code == 403, refused.text
    assert "peers" in refused.json()["detail"]

    listed = client.get("/api/users", params={"search": deputy["email"]})
    assert [row["id"] for row in listed.json()["items"]] == [deputy["id"]]
