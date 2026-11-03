import os
import uuid
from contextlib import suppress
from datetime import UTC, datetime
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.stage_schema import ENUMS

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "workshop-types-test-password"

DESIGN_KEY = "DESIGN_PROTOTYPE_DEVELOPMENT"

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Workshop Types Admin"),
    ("designer", "DESIGNER", "Workshop Types Designer"),
    ("professor", "PROFESSOR", "Workshop Types Professor"),
)


def _stamped_key(stamp: str, suffix: str) -> str:
    return f"TEST_{suffix}_{stamp}".upper()


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    made: list[str] = []
    workshops: dict[str, str] = {}

    def address(slug: str) -> str:
        return f"wtypes-{slug}-{stamp}@example.org".lower()

    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": name,
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": address(slug),
                    "status": "ACTIVE",
                    "notes": "Seeded by tests/test_workshop_types.py; this module is about the "
                    "workshop-type vocabulary, not the platform allow-list.",
                }
            )

        for slug, kind_suffix, deleted in (("inUse", "INUSE", False), ("trashed", "TRASHED", True)):
            workshop = await db.designworkshop.create(
                data={
                    "title": f"Workshop pinning the delete refusal ({slug}) {stamp}",
                    "workshopKind": _stamped_key(stamp, kind_suffix),
                    "createdById": people["admin"].id,
                    "deletedAt": datetime.now(UTC) if deleted else None,
                }
            )
            workshops[slug] = workshop.id
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "stamp": stamp,
            "made": made,
            "workshops": workshops,
        }

    with suppress(Exception):
        if not db.is_connected():
            await db.connect()
        try:
            for key in made:
                await db.workshoptypeoption.delete_many(where={"key": key})
            for workshop_id in workshops.values():
                await db.designworkshop.delete_many(where={"id": workshop_id})
            for slug, _role, _name in ACCOUNTS:
                await db.accessroster.delete_many(where={"email": address(slug)})
                await db.user.delete_many(where={"email": address(slug)})
        finally:
            await db.disconnect()


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _key(world: dict[str, Any], suffix: str) -> str:
    return _stamped_key(world["stamp"], suffix)


def _make(client: Any, world: dict[str, Any], *, key: str, **overrides: Any) -> dict[str, Any]:
    body = {"key": key, "label": "A test type", **overrides}
    response = client.post("/api/workshop-types", json=body, headers=_headers(world, "admin"))
    assert response.status_code == 201, response.text
    world["made"].append(key)
    return response.json()


def _by_key(client: Any, world: dict[str, Any]) -> dict[str, dict[str, Any]]:
    response = client.get(
        "/api/workshop-types",
        params={"includeInactive": "true"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["key"]: row for row in response.json()}



async def test_the_six_registry_tokens_all_have_a_type_row(world, client):
    rows = _by_key(client, world)
    missing = [token for token in ENUMS["WORKSHOP_KIND"] if token not in rows]
    assert not missing, (
        f"{missing} are WORKSHOP_KIND tokens with no WorkshopTypeOption row, so a workshop already "
        "filed under one of them renders with no type at all. Seed them; see "
        "prisma/migrations/20260916160000_workshop_type_options."
    )


async def test_exactly_the_design_prototype_type_routes_at_the_design_workshop_table(world, client):
    rows = _by_key(client, world)
    seeded = {token: rows[token] for token in ENUMS["WORKSHOP_KIND"] if token in rows}
    routing = {key for key, row in seeded.items() if row["routesToDesignWorkshop"]}
    assert routing == {DESIGN_KEY}, (
        "Exactly one seeded type must route at the DesignWorkshop table, and it must be "
        f"{DESIGN_KEY}. Found: {sorted(routing)}."
    )


async def test_the_seeded_labels_are_the_registry_s_own_words(world, client):
    rows = _by_key(client, world)
    for token, label in ENUMS["WORKSHOP_KIND"].items():
        assert rows[token]["label"] == label, (
            f"{token} reads {rows[token]['label']!r} on the record form and {label!r} inside stage 1."
        )



async def test_every_signed_in_account_may_READ_the_list(world, client):
    for slug in ("designer", "professor", "admin"):
        response = client.get("/api/workshop-types", headers=_headers(world, slug))
        assert response.status_code == 200, f"{slug}: {response.text}"
        assert len(response.json()) >= len(ENUMS["WORKSHOP_KIND"])


async def test_a_designer_may_not_create_a_type_and_is_refused_by_the_SERVER(world, client):
    response = client.post(
        "/api/workshop-types",
        json={"key": _key(world, "FORBIDDEN"), "label": "Should never exist"},
        headers=_headers(world, "designer"),
    )
    assert response.status_code == 403, response.text


async def test_a_professor_outranks_a_designer_and_is_still_refused(world, client):
    response = client.post(
        "/api/workshop-types",
        json={"key": _key(world, "PROFESSOR"), "label": "Should never exist"},
        headers=_headers(world, "professor"),
    )
    assert response.status_code == 403, response.text


async def test_a_designer_may_not_edit_or_delete_a_type(world, client):
    created = _make(client, world, key=_key(world, "GATED"))
    patch = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"label": "Renamed by somebody who may not"},
        headers=_headers(world, "designer"),
    )
    assert patch.status_code == 403, patch.text
    delete = client.delete(
        f"/api/workshop-types/{created['id']}", headers=_headers(world, "designer")
    )
    assert delete.status_code == 403, delete.text



async def test_editing_a_label_is_safe_and_never_touches_the_key(world, client):
    created = _make(client, world, key=_key(world, "LABEL"), label="Mispelt Label")
    response = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"label": "Correctly Spelt Label"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    updated = response.json()
    assert updated["label"] == "Correctly Spelt Label"
    assert updated["key"] == created["key"], "A label edit moved the key."
    assert updated["routesToDesignWorkshop"] == created["routesToDesignWorkshop"], (
        "A label edit changed where records under this type save. `exclude_unset` is what stops an "
        "absent field being written as a null; see routes/workshop_types.update_workshop_type."
    )
    assert updated["isActive"] == created["isActive"]


async def test_an_attempt_to_rename_a_key_is_REFUSED_rather_than_ignored(world, client):
    created = _make(client, world, key=_key(world, "IMMUTABLE"))
    response = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"key": _key(world, "SOMETHINGELSE"), "label": "Still here"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    assert "key" in response.text

    rows = _by_key(client, world)
    assert created["key"] in rows
    assert rows[created["key"]]["label"] == created["label"]


async def test_a_key_that_is_already_taken_answers_409_and_not_500(world, client):
    created = _make(client, world, key=_key(world, "TAKEN"))
    response = client.post(
        "/api/workshop-types",
        json={"key": created["key"], "label": "A second type under one key"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 409, response.text
    assert created["key"] in response.json()["detail"]


async def test_a_malformed_key_is_refused_with_a_sentence_a_person_can_act_on(world, client):
    for bad in ("design prototype", "design-prototype", "designPrototype", "1_LEADING_DIGIT"):
        response = client.post(
            "/api/workshop-types",
            json={"key": bad, "label": "Should never exist"},
            headers=_headers(world, "admin"),
        )
        assert response.status_code == 422, f"{bad!r} was accepted: {response.text}"
        assert "DESIGN_PROTOTYPE_DEVELOPMENT" in response.text



async def test_a_retired_type_leaves_the_picker_and_stays_on_the_admin_screen(world, client):
    created = _make(client, world, key=_key(world, "RETIRE"))
    response = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"isActive": False},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["isActive"] is False

    offered = client.get("/api/workshop-types", headers=_headers(world, "designer")).json()
    assert created["key"] not in {row["key"] for row in offered}, (
        "A retired type is still being offered in the picker."
    )
    assert created["key"] in _by_key(client, world), (
        "A retired type has vanished from the admin screen, so nobody can put it back."
    )



async def test_a_type_nothing_uses_is_deleted_outright(world, client):
    created = _make(client, world, key=_key(world, "UNUSED"))
    response = client.delete(
        f"/api/workshop-types/{created['id']}", headers=_headers(world, "admin")
    )
    assert response.status_code == 204, response.text
    assert created["key"] not in _by_key(client, world)


async def test_a_type_with_a_workshop_under_it_is_REFUSED_and_the_refusal_COUNTS_them(
    world, client
):
    created = _make(client, world, key=_key(world, "INUSE"), label="A type in use")

    response = client.delete(
        f"/api/workshop-types/{created['id']}", headers=_headers(world, "admin")
    )
    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert "1 design workshop" in detail, f"The refusal does not count the workshops: {detail!r}"
    assert "Deactivate" in detail, f"The refusal does not name the remedy: {detail!r}"
    assert created["label"] in detail, f"The refusal does not name the type: {detail!r}"

    assert created["key"] in _by_key(client, world)

    retire = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"isActive": False},
        headers=_headers(world, "admin"),
    )
    assert retire.status_code == 200, retire.text
    assert retire.json()["isActive"] is False


async def test_a_SOFT_DELETED_workshop_does_not_hold_a_type_hostage(world, client):
    created = _make(client, world, key=_key(world, "TRASHED"))
    response = client.delete(
        f"/api/workshop-types/{created['id']}", headers=_headers(world, "admin")
    )
    assert response.status_code == 204, response.text



async def test_reorder_moves_the_whole_list_in_one_request(world, client):
    first = _make(client, world, key=_key(world, "ORDA"), label="Order probe A")
    second = _make(client, world, key=_key(world, "ORDB"), label="Order probe B")

    response = client.patch(
        "/api/workshop-types/reorder",
        json={"ids": [second["id"], first["id"]]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    positions = {row["key"]: row["sortOrder"] for row in response.json()}
    assert positions[second["key"]] < positions[first["key"]]


async def test_reorder_refuses_an_id_this_server_does_not_have(world, client):
    created = _make(client, world, key=_key(world, "ORDMISSING"))
    response = client.patch(
        "/api/workshop-types/reorder",
        json={"ids": [created["id"], "not-an-id-anything-here-holds"]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 404, response.text


async def test_the_reorder_path_is_not_swallowed_by_the_id_route(world, client):
    response = client.patch(
        "/api/workshop-types/reorder",
        json={"ids": []},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
