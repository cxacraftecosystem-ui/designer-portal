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
    made = world["client"].post("/api/users", json={
        "email": f"peer-{slug}-{world['stamp']}@example.org",
        "name": f"Peer {slug}",
        "password": "LocalDev123!",
        "role": role,
    })
    assert made.status_code == 201, made.text
    return made.json()


async def test_minting_a_peer_and_managing_a_lower_tier_both_still_work(client, world):
    deputy = await _account(world, "mintable", "MASTER_ADMIN")
    assert deputy["role"] == "MASTER_ADMIN"

    ordinary = await _account(world, "ordinary", "ADMIN")
    renamed = client.patch(f"/api/users/{ordinary['id']}", json={"name": "Renamed Admin"})
    assert renamed.status_code == 200, renamed.text
    assert renamed.json()["name"] == "Renamed Admin"

    own = client.patch(f"/api/users/{world['root'].id}", json={"name": "Acting Master Admin II"})
    assert own.status_code == 200, own.text


async def test_a_master_admin_cannot_demote_a_master_admin_peer(client, world):
    deputy = await _account(world, "demote", "MASTER_ADMIN")

    refused = client.patch(f"/api/users/{deputy['id']}", json={"role": "ADMIN"})
    assert refused.status_code == 403, refused.text
    detail = refused.json()["detail"]
    assert "can't change or remove each other" in detail, detail
    assert "ask the team that runs the platform" in detail, detail

    still = client.get("/api/users", params={"search": deputy["email"]})
    assert still.status_code == 200, still.text
    rows = [row for row in still.json()["items"] if row["id"] == deputy["id"]]
    assert rows and rows[0]["role"] == "MASTER_ADMIN"


async def test_a_master_admin_cannot_delete_a_master_admin_peer(client, world):
    deputy = await _account(world, "delete", "MASTER_ADMIN")

    refused = client.delete(f"/api/users/{deputy['id']}")
    assert refused.status_code == 403, refused.text
    assert "can't change or remove each other" in refused.json()["detail"]

    listed = client.get("/api/users", params={"search": deputy["email"]})
    assert [row["id"] for row in listed.json()["items"]] == [deputy["id"]]
