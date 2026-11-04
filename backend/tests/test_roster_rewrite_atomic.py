import inspect
import re
import uuid
from datetime import UTC, datetime

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password

pytestmark = [needs_db, pytest.mark.anyio]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        professor = await db.user.create(
            data={
                "email": f"roster-prof-{stamp}@example.org",
                "name": "Roster Professor",
                "role": "PROFESSOR",
                "passwordHash": hash_password("unused"),
            }
        )
        workshop = await db.workshop.create(
            data={
                "title": f"Roster workshop {stamp}",
                "date": datetime.now(UTC),
                "place": "Bagru",
                "createdById": professor.id,
            }
        )
        artisans = [
            (
                await db.artisan.create(
                    data={
                        "name": f"Roster Artisan {index} {stamp}",
                        "place": "Bagru",
                        "createdById": professor.id,
                    }
                )
            ).id
            for index in range(3)
        ]
        crafts = [
            (
                await db.craft.create(
                    data={
                        "name": f"Roster Craft {index} {stamp}",
                        "createdById": professor.id,
                    }
                )
            ).id
            for index in range(3)
        ]
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "prof": {"Authorization": f"Bearer {create_access_token(subject=professor.id)}"},
            "workshop_id": workshop.id,
            "artisans": artisans,
            "crafts": crafts,
        }


def _save(env, body: dict):
    return env["client"].patch(
        f"/api/workshops/{env['workshop_id']}", json=body, headers=env["prof"]
    )


def _workshop(env) -> dict:
    response = env["client"].get(f"/api/workshops/{env['workshop_id']}", headers=env["prof"])
    assert response.status_code == 200, response.text
    return response.json()


def _roster(env) -> set[str]:
    return {link["artisanId"] for link in _workshop(env).get("artisans") or []}


def _craft_roster(env) -> set[str]:
    return {link["craftId"] for link in _workshop(env).get("crafts") or []}


@pytest.fixture
def full_roster(env):
    response = _save(env, {"artisanIds": env["artisans"], "craftIds": env["crafts"]})
    assert response.status_code == 200, response.text
    assert _roster(env) == set(env["artisans"])
    assert _craft_roster(env) == set(env["crafts"])
    return env



def test_a_failed_insert_does_not_commit_the_wipe_that_preceded_it(full_roster, monkeypatch):
    env = full_roster

    from prisma.actions import WorkshopArtisanActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create_many`
        raise RuntimeError("the roster could not be re-inserted")

    monkeypatch.setattr(WorkshopArtisanActions, "create_many", _boom)

    response = _save(env, {"artisanIds": [env["artisans"][0]]})
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    assert _roster(env) == set(env["artisans"]), (
        "the delete committed without its insert: the roster was wiped by a failed save"
    )


def test_the_craft_roster_has_the_same_protection(full_roster, monkeypatch):
    env = full_roster

    from prisma.actions import WorkshopCraftActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create_many`
        raise RuntimeError("the craft roster could not be re-inserted")

    monkeypatch.setattr(WorkshopCraftActions, "create_many", _boom)

    response = _save(env, {"craftIds": [env["crafts"][0]]})
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    assert _craft_roster(env) == set(env["crafts"])


def test_a_failed_roster_write_also_rolls_back_the_row_and_its_audit_row(full_roster, monkeypatch):
    env = full_roster
    before = _workshop(env)

    from prisma.actions import WorkshopCraftActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create_many`
        raise RuntimeError("the craft roster could not be re-inserted")

    monkeypatch.setattr(WorkshopCraftActions, "create_many", _boom)

    response = _save(
        env,
        {
            "notes": "A note recorded alongside a roster rewrite that fails.",
            "artisanIds": [env["artisans"][0]],
            "craftIds": [env["crafts"][0]],
        },
    )
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    after = _workshop(env)
    assert after["notes"] == before["notes"], "the scalar half of a failed save was committed"
    assert _roster(env) == set(env["artisans"]), "the artisan roster was rewritten by a failed save"
    assert _craft_roster(env) == set(env["crafts"])

    revisions = env["client"].get(
        f"/api/data-access/revisions?recordType=workshop&recordId={env['workshop_id']}",
        headers=env["prof"],
    )
    assert revisions.status_code == 200, revisions.text
    assert not [
        row
        for row in revisions.json()
        if row["changes"].get("notes", {}).get("new")
        == "A note recorded alongside a roster rewrite that fails."
    ], "the ledger kept a row for a save that was rolled back"



def test_a_successful_rewrite_replaces_the_roster_wholesale(full_roster):
    env = full_roster

    response = _save(env, {"artisanIds": env["artisans"][:1], "craftIds": env["crafts"][:2]})
    assert response.status_code == 200, response.text
    assert _roster(env) == {env["artisans"][0]}
    assert _craft_roster(env) == set(env["crafts"][:2])


def test_saving_the_same_roster_twice_running_is_not_a_conflict(full_roster):
    env = full_roster

    for _attempt in range(2):
        response = _save(env, {"artisanIds": env["artisans"], "craftIds": env["crafts"]})
        assert response.status_code == 200, response.text
    assert _roster(env) == set(env["artisans"])
    assert _craft_roster(env) == set(env["crafts"])



def test_neither_roster_helper_can_write_through_the_module_client():
    from app.api.routes import workshops

    for helper in (workshops.replace_workshop_artisans, workshops.replace_workshop_crafts):
        source = inspect.getsource(helper)
        body = source.split('"""')[-1]
        assert not re.search(r"\bawait\s+db\s*\.", body), (
            f"{helper.__name__} writes through the module-level db client; it must use the "
            f"`writer` bound from its `client` argument, or the caller's transaction is a lie"
        )
        assert re.search(r"writer\s*=\s*db\s+if\s+client\s+is\s+None\s+else\s+client", body), (
            f"{helper.__name__} no longer resolves its writer from `client`"
        )
