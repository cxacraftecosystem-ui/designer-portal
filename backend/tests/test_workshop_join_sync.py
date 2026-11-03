import asyncio
import os
import uuid
from datetime import UTC, datetime
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
]

CASES: tuple[str, ...] = ("control", "unlink", "aim", "refile", "untouched")


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    tag = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            admin = await db.user.create(data={
                "email": f"join-sync-{tag}@example.org",
                "name": "Join Sync Admin",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            })
            workshop_a = await db.workshop.create(data={
                "title": f"Bagru block printing {tag}",
                "date": datetime(2026, 3, 1, tzinfo=UTC),
                "place": "Bagru",
                "createdById": admin.id,
            })
            workshop_b = await db.workshop.create(data={
                "title": f"Sanganer block printing {tag}",
                "date": datetime(2026, 4, 1, tzinfo=UTC),
                "place": "Sanganer",
                "createdById": admin.id,
            })
            artisans: dict[str, str] = {}
            for case in CASES:
                row = await db.artisan.create(data={
                    "name": f"Sita Devi {case} {tag}",
                    "place": "Bagru",
                    "createdById": admin.id,
                    "workshopId": workshop_a.id,
                })
                await db.workshopartisan.create(
                    data={"workshopId": workshop_a.id, "artisanId": row.id}
                )
                artisans[case] = row.id
            bystander = await db.artisan.create(data={
                "name": f"Roster Only Bystander {tag}",
                "place": "Bagru",
                "createdById": admin.id,
            })
            await db.workshopartisan.create(
                data={"workshopId": workshop_a.id, "artisanId": bystander.id}
            )
            crafts: dict[str, str] = {}
            for case in CASES:
                row = await db.craft.create(data={
                    "name": f"Bandhani {case} {tag}",
                    "place": "Bagru",
                    "createdById": admin.id,
                    "workshopId": workshop_a.id,
                })
                await db.workshopcraft.create(
                    data={"workshopId": workshop_a.id, "craftId": row.id}
                )
                crafts[case] = row.id
            craft_bystander = await db.craft.create(data={
                "name": f"Roster Only Craft {tag}",
                "place": "Bagru",
                "createdById": admin.id,
            })
            await db.workshopcraft.create(
                data={"workshopId": workshop_a.id, "craftId": craft_bystander.id}
            )
            return {
                "admin": admin,
                "workshop_a": workshop_a.id,
                "workshop_b": workshop_b.id,
                "artisans": artisans,
                "bystander": bystander.id,
                "crafts": crafts,
                "craft_bystander": craft_bystander.id,
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


def _headers(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['admin'].id)}"}


def _craft_ids_in(world: dict[str, Any], workshop_id: str) -> set[str]:
    response = world["client"].get(
        f"/api/crafts?workshopId={workshop_id}&pageSize=100", headers=_headers(world)
    )
    assert response.status_code == 200, response.text
    return {row["id"] for row in response.json()["items"]}


def _artisan_ids_in(world: dict[str, Any], workshop_id: str) -> set[str]:
    response = world["client"].get(
        f"/api/artisans?workshopId={workshop_id}&pageSize=100", headers=_headers(world)
    )
    assert response.status_code == 200, response.text
    return {row["id"] for row in response.json()["items"]}


def test_a_filed_artisan_is_in_the_workshop_by_both_readings(world, client):
    assert world["artisans"]["control"] in _artisan_ids_in(world, world["workshop_a"])


def test_unlinking_removes_the_artisan_from_the_workshop_and_not_only_from_the_column(world, client):
    artisan_id = world["artisans"]["unlink"]
    assert artisan_id in _artisan_ids_in(world, world["workshop_a"])

    unlink = client.patch(
        f"/api/artisans/{artisan_id}", json={"workshopId": None}, headers=_headers(world)
    )
    assert unlink.status_code == 200, unlink.text
    assert unlink.json()["workshopId"] is None

    assert artisan_id not in _artisan_ids_in(world, world["workshop_a"])


def test_an_unlink_leaves_another_artisans_roster_row_alone(world, client):
    client.patch(
        f"/api/artisans/{world['artisans']['aim']}",
        json={"workshopId": None},
        headers=_headers(world),
    )
    assert world["bystander"] in _artisan_ids_in(world, world["workshop_a"])


def test_refiling_an_artisan_moves_them_rather_than_counting_them_twice(world, client):
    artisan_id = world["artisans"]["refile"]
    moved = client.patch(
        f"/api/artisans/{artisan_id}",
        json={"workshopId": world["workshop_b"]},
        headers=_headers(world),
    )
    assert moved.status_code == 200, moved.text

    assert artisan_id in _artisan_ids_in(world, world["workshop_b"])
    assert artisan_id not in _artisan_ids_in(world, world["workshop_a"])


def test_an_edit_that_never_mentions_the_workshop_keeps_the_link(world, client):
    artisan_id = world["artisans"]["untouched"]
    renamed = client.patch(
        f"/api/artisans/{artisan_id}", json={"place": "Chhipa Mohalla"}, headers=_headers(world)
    )
    assert renamed.status_code == 200, renamed.text
    assert artisan_id in _artisan_ids_in(world, world["workshop_a"])



def test_a_filed_craft_is_in_the_workshop_by_both_readings(world, client):
    assert world["crafts"]["control"] in _craft_ids_in(world, world["workshop_a"])


def test_unlinking_a_craft_removes_it_from_the_workshop_and_not_only_from_the_column(world, client):
    craft_id = world["crafts"]["unlink"]
    assert craft_id in _craft_ids_in(world, world["workshop_a"])

    unlink = client.patch(
        f"/api/crafts/{craft_id}", json={"workshopId": None}, headers=_headers(world)
    )
    assert unlink.status_code == 200, unlink.text
    assert unlink.json()["workshopId"] is None

    assert craft_id not in _craft_ids_in(world, world["workshop_a"])


def test_an_unlink_leaves_another_crafts_join_row_alone(world, client):
    client.patch(
        f"/api/crafts/{world['crafts']['aim']}",
        json={"workshopId": None},
        headers=_headers(world),
    )
    assert world["craft_bystander"] in _craft_ids_in(world, world["workshop_a"])


def test_refiling_a_craft_moves_it_rather_than_counting_it_twice(world, client):
    craft_id = world["crafts"]["refile"]
    moved = client.patch(
        f"/api/crafts/{craft_id}",
        json={"workshopId": world["workshop_b"]},
        headers=_headers(world),
    )
    assert moved.status_code == 200, moved.text

    assert craft_id in _craft_ids_in(world, world["workshop_b"])
    assert craft_id not in _craft_ids_in(world, world["workshop_a"])


def test_an_edit_that_never_mentions_the_crafts_workshop_keeps_the_link(world, client):
    craft_id = world["crafts"]["untouched"]
    renamed = client.patch(
        f"/api/crafts/{craft_id}", json={"place": "Chhipa Mohalla"}, headers=_headers(world)
    )
    assert renamed.status_code == 200, renamed.text
    assert craft_id in _craft_ids_in(world, world["workshop_a"])
