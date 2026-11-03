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
    made = world["client"].post("/api/users", json={
        "email": f"user-del-{slug}-{world['stamp']}@example.org",
        "name": f"Departing {slug}",
        "password": "LocalDev123!",
        "role": "DESIGNER",
        "mustChangePassword": False,
    })
    assert made.status_code == 201, made.text
    return made.json()


async def test_deleting_an_account_that_created_nothing_still_works(client, world):
    account = await _designer(world, "unused")
    removed = client.delete(f"/api/users/{account['id']}")
    assert removed.status_code == 204, removed.text


async def test_removing_a_colleague_who_did_work_says_what_is_in_the_way(client, world):
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
    assert "1 questionnaire" in detail
    assert "deactivate" in detail.lower()

    from prisma import Prisma

    own = Prisma()
    await own.connect()
    try:
        await own.questionnaire.delete(where={"id": made.json()["id"]})
    finally:
        await own.disconnect()

    again = client.delete(f"/api/users/{account['id']}")
    assert again.status_code == 204, again.text
