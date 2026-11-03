import asyncio
import os
import uuid
from types import SimpleNamespace
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import build_runtime_database_url
from app.core.security import create_access_token, hash_password
from app.services import design_workshop_data as dwd

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = pytest.mark.skipif(
    not _LOCAL,
    reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
)


def _own_client():
    from prisma import Prisma

    return Prisma(datasource={"url": build_runtime_database_url(_URL, pooled=False)})


def _run(coro_factory):
    async def go():
        client = _own_client()
        await client.connect()
        try:
            return await coro_factory(client)
        finally:
            await client.disconnect()

    return asyncio.run(go())


def _stage_rows(workshop_id: str, entity_key: str | None = None) -> list[dict[str, Any]]:
    where: dict[str, Any] = {"designWorkshopId": workshop_id}
    if entity_key is not None:
        where["entityKey"] = entity_key

    async def read(client):
        rows = await client.dwstageentry.find_many(where=where)
        return [
            {
                "id": row.id,
                "entityKey": row.entityKey,
                "stageKey": row.stageKey,
                "data": row.data,
                "searchText": row.searchText,
                "deletedAt": row.deletedAt,
            }
            for row in rows
        ]

    return _run(read)


def _live_search_text(workshop_id: str, entity_key: str) -> str:
    return " ".join(
        row["searchText"] or ""
        for row in _stage_rows(workshop_id, entity_key)
        if row["deletedAt"] is None
    )


@pytest.fixture(scope="module")
def client():
    from fastapi.testclient import TestClient

    from app.main import app

    email = f"search-text-{uuid.uuid4().hex[:8]}@example.org"

    async def make_user(prisma):
        return await prisma.user.create(data={
            "email": email, "name": "Search Text Test", "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })

    user = _run(make_user)

    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield c


@pytest.fixture
def workshop(client):
    response = client.post("/api/design-workshops", json={"title": "Search text workshop"})
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _needle() -> str:
    return f"zqx{uuid.uuid4().hex[:10]}"


def _put_sketch(client, workshop_id, rows, *, emptied=None):
    body: dict[str, Any] = {
        "entries": [
            {"entityKey": "sketch", "ordinal": i, "data": row} for i, row in enumerate(rows)
        ],
        "replaceCollections": True,
    }
    if emptied is not None:
        body["emptiedEntities"] = emptied
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/SKETCH_DEVELOPMENT", json=body
    )



def test_a_saved_stage_row_carries_the_words_the_designer_typed(client, workshop):
    needle = _needle()
    assert _put_sketch(
        client, workshop, [{"_clientKey": "sk-a", "name": f"{needle} runner", "sketchNo": "SK-01"}]
    ).status_code == 200

    rows = _stage_rows(workshop, "sketch")
    assert len(rows) == 1
    assert needle in (rows[0]["searchText"] or "")


def test_re_saving_a_row_REPLACES_the_column_rather_than_leaving_the_old_words(client, workshop):
    old, new = _needle(), _needle()
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{old} runner"}])
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{new} runner"}])

    stored = _live_search_text(workshop, "sketch")
    assert new in stored
    assert old not in stored


def test_a_participants_phone_number_never_reaches_the_column(client, workshop):
    name, phone = _needle(), "9876500000"
    response = client.put(
        f"/api/design-workshops/{workshop}/stages/WORKSHOP_PLAN_PARTICIPANTS_OPENING",
        json={
            "entries": [
                {
                    "entityKey": "participant",
                    "ordinal": 0,
                    "data": {"_clientKey": "p-1", "name": name, "phone": phone},
                }
            ],
            "replaceCollections": True,
        },
    )
    assert response.status_code == 200, response.text

    stored = _live_search_text(workshop, "participant")
    assert name in stored, "the artisan's NAME is what a researcher searches for"
    assert phone not in stored



def test_a_stage_answer_is_findable_from_the_search_box(client, workshop):
    needle = _needle()
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{needle} runner"}])

    body = client.get("/api/search", params={"q": needle, "types": "designWorkshops"}).json()
    assert [item["id"] for item in body["designWorkshops"]] == [workshop]


def test_a_hit_on_a_stage_answer_NAMES_the_stage(client, workshop):
    needle = _needle()
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{needle} runner"}])

    body = client.get("/api/search", params={"q": needle, "types": "designWorkshops"}).json()
    named = body["designWorkshopStageMatches"][workshop]
    assert named == [dwd.stage_label("SKETCH_DEVELOPMENT")]
    assert "Stage 11" in named[0], "the stage NUMBER leads, as every other surface orders them"


def test_a_workshop_matched_on_its_own_columns_names_no_stage(client, workshop):
    body = client.get(
        "/api/search", params={"q": "Search text workshop", "types": "designWorkshops"}
    ).json()
    assert workshop in [item["id"] for item in body["designWorkshops"]]
    assert workshop not in body.get("designWorkshopStageMatches", {})


def test_a_deleted_stage_row_stops_being_findable(client, workshop):
    needle = _needle()
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{needle} runner"}])
    removed = _put_sketch(client, workshop, [], emptied=["sketch"]).json()
    assert removed["removed"] == 1, removed

    body = client.get("/api/search", params={"q": needle, "types": "designWorkshops"}).json()
    assert body["designWorkshops"] == []


def test_the_scope_sentence_no_longer_claims_the_stages_are_unsearched(client, workshop):
    body = client.get("/api/search", params={"q": "x", "types": "designWorkshops"}).json()
    scope = body["designWorkshopSearchScope"]
    assert "not searched" not in scope.split(".")[0], scope
    assert "stages" in scope, "what IS searched must still be said"
    assert "Numbers, dates and contact details are not searched" in scope



def test_the_backfill_computes_exactly_what_the_writer_wrote(client, workshop):
    from scripts.backfill_stage_search_text import _computed

    _put_sketch(
        client,
        workshop,
        [
            {"_clientKey": "sk-a", "name": f"{_needle()} runner", "category": "APPAREL"},
            {"_clientKey": "sk-b", "name": f"{_needle()} stole", "materials": ["cotton", "silk"]},
        ],
    )

    rows = [row for row in _stage_rows(workshop) if row["entityKey"] != "_custom"]
    assert rows, "the save wrote nothing, so this test would pass vacuously"
    for row in rows:
        assert _computed(SimpleNamespace(**row), None) == row["searchText"], row["entityKey"]
