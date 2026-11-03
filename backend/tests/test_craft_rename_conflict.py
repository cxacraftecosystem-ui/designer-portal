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

CONFLICT_DETAIL = {"code": "CRAFT_EXISTS", "message": "That craft is already in this sub-category."}

SUB_CATEGORY_ID = "cms" + __import__("hashlib").md5(b"s:Embroidery").hexdigest()[:22]


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
    return client.post(
        "/api/crafts",
        json={"name": name, "subCategoryId": SUB_CATEGORY_ID, "giStatus": "NON_GI"},
        headers=_headers(world),
    )


async def test_renaming_a_craft_onto_an_existing_name_is_the_same_409_as_creating_one(world, client):
    canonical = f"Bandhani {world['stamp']}"
    duplicate = f"Bandhej {world['stamp']}"

    first = _create(client, world, canonical)
    assert first.status_code == 201, first.text
    second = _create(client, world, duplicate)
    assert second.status_code == 201, second.text

    clash_on_create = _create(client, world, canonical)
    assert clash_on_create.status_code == 409, clash_on_create.text
    assert clash_on_create.json()["detail"] == CONFLICT_DETAIL

    clash_on_rename = client.patch(
        f"/api/crafts/{second.json()['id']}",
        json={"name": canonical},
        headers=_headers(world),
    )
    assert clash_on_rename.status_code == 409, clash_on_rename.text
    assert clash_on_rename.json()["detail"] == CONFLICT_DETAIL

    unchanged = client.get(f"/api/crafts/{second.json()['id']}", headers=_headers(world))
    assert unchanged.json()["name"] == duplicate


async def test_a_rename_that_does_not_collide_still_goes_through(world, client):
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
