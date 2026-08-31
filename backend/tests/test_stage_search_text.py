"""``DwStageEntry.searchText`` against a real database: the writers, the search, the backfill.

The rules about what the column CONTAINS are pure and live in
``tests/test_design_workshop_search_text.py``. These are the three things that cannot be tested
without Postgres, and each of them is a way the feature can be silently wrong rather than broken:

* the writers actually write it (a stale copy answers searches with a previous designer's words);
* the ``designWorkshops`` bucket actually matches on it, and NAMES the stage it matched in;
* the backfill computes exactly what the writer would have, so a re-run over live rows is a no-op
  rather than a rewrite of the whole table.

EVERY TEST HERE IS SYNCHRONOUS, AND THE COLUMN IS READ THROUGH ITS OWN SHORT-LIVED PRISMA CLIENT.
That is not a style choice. ``TestClient`` runs the app — and the module-level ``db`` — on a portal
thread with an event loop of its own, so an ``async def`` test that awaits ``db.…`` is awaiting a
connection bound to a different loop: ``RuntimeError: … is bound to a different event loop``, which
is a harness fault that reads exactly like a broken feature. ``tests/test_stage_sync.py`` never hits
it because it asserts only through the API; this file has to look at a column that is deliberately
NOT on the wire, so it opens its own connection instead. See :func:`_stage_rows`.

Run the local stack first:

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

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
    """A Prisma client of this test's own, pointed at the same DSN the app uses.

    Built rather than imported so that connecting and disconnecting it cannot disturb the app's
    connection, which ``TestClient``'s lifespan owns on another thread.
    """
    from prisma import Prisma

    return Prisma(datasource={"url": build_runtime_database_url(_URL, pooled=False)})


def _run(coro_factory):
    """Run one short-lived connected session on a loop of this thread's own."""

    async def go():
        client = _own_client()
        await client.connect()
        try:
            return await coro_factory(client)
        finally:
            await client.disconnect()

    return asyncio.run(go())


def _stage_rows(workshop_id: str, entity_key: str | None = None) -> list[dict[str, Any]]:
    """The stage rows of one workshop, as plain dicts — including the column under test.

    Plain dicts and not Prisma models, because the connection they came from is closed by the time
    the assertions run and a lazily-resolved relation would raise at exactly the wrong moment.
    """
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
    """Every live row's rendered text for one entity, joined — what a ``contains`` would see."""
    return " ".join(
        row["searchText"] or ""
        for row in _stage_rows(workshop_id, entity_key)
        if row["deletedAt"] is None
    )


@pytest.fixture(scope="module")
def client():
    """A TestClient with a signed-in ADMIN, sharing one Prisma connection with the app.

    ADMIN and not a designer, deliberately: this file searches as well as saves, and the
    ``designWorkshops`` bucket is gated on ``can_view_design_workshop_data`` — Professor, Admin and
    Master Admin. A DESIGNER can write every one of these stages and would be refused the bucket.
    """
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
    """A word that cannot collide with anything another lane's fixtures put in this database.

    Two suites searching one shared Postgres for "indigo" would find each other's workshops and each
    other's stage rows, and the failure would read as this feature not working.
    """
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


# --------------------------------------------------------------------------------------
# The writer
# --------------------------------------------------------------------------------------


def test_a_saved_stage_row_carries_the_words_the_designer_typed(client, workshop):
    needle = _needle()
    assert _put_sketch(
        client, workshop, [{"_clientKey": "sk-a", "name": f"{needle} runner", "sketchNo": "SK-01"}]
    ).status_code == 200

    rows = _stage_rows(workshop, "sketch")
    assert len(rows) == 1
    assert needle in (rows[0]["searchText"] or "")


def test_re_saving_a_row_REPLACES_the_column_rather_than_leaving_the_old_words(client, workshop):
    """**THE FAILURE THIS COLUMN'S DESIGN IS MOST EXPOSED TO.**

    ``data`` is written wholesale on every update, so a rendered copy that was computed once and
    never again would go on answering searches with the FIRST designer's words — silently, because
    nothing about a stale string looks wrong. A designer who corrects a sketch's name must stop being
    findable by the name they corrected.
    """
    old, new = _needle(), _needle()
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{old} runner"}])
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{new} runner"}])

    stored = _live_search_text(workshop, "sketch")
    assert new in stored
    assert old not in stored


def test_a_participants_phone_number_never_reaches_the_column(client, workshop):
    """The database half of the exclusion argued at ``design_workshop_data.UNSEARCHABLE_FIELD_KEYS``.

    ``access.py`` accepts that clearing ``Artisan.phone`` leaves the number in every stage row that
    referenced her, on the stated ground that a stage entry "is not indexed by identity number …
    a RESIDUE, not a ledger". This assertion is what keeps that sentence true.
    """
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


# --------------------------------------------------------------------------------------
# The search
# --------------------------------------------------------------------------------------


def test_a_stage_answer_is_findable_from_the_search_box(client, workshop):
    """§6.1 of ``docs/DECISION-design-workshop-data-in-view-data.md``, closed end to end."""
    needle = _needle()
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{needle} runner"}])

    body = client.get("/api/search", params={"q": needle, "types": "designWorkshops"}).json()
    assert [item["id"] for item in body["designWorkshops"]] == [workshop]


def test_a_hit_on_a_stage_answer_NAMES_the_stage(client, workshop):
    """A hit the reader cannot account for is an answer that is right and unusable.

    The workshop has twenty-two stages and the word is in one of them; without this the researcher
    would have to open all of them to find out which.
    """
    needle = _needle()
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{needle} runner"}])

    body = client.get("/api/search", params={"q": needle, "types": "designWorkshops"}).json()
    named = body["designWorkshopStageMatches"][workshop]
    assert named == [dwd.stage_label("SKETCH_DEVELOPMENT")]
    assert "Stage 11" in named[0], "the stage NUMBER leads, as every other surface orders them"


def test_a_workshop_matched_on_its_own_columns_names_no_stage(client, workshop):
    """ABSENT, not an empty list. A workshop found by its title has no stage to name, and an empty
    list beside it would read as "we looked inside and found none".
    """
    body = client.get(
        "/api/search", params={"q": "Search text workshop", "types": "designWorkshops"}
    ).json()
    assert workshop in [item["id"] for item in body["designWorkshops"]]
    assert workshop not in body.get("designWorkshopStageMatches", {})


def test_a_deleted_stage_row_stops_being_findable(client, workshop):
    """A designer deletes their last sketch. The row survives with its rendered copy intact — the
    soft delete is what makes an undo possible — so the ``some`` clause has to test ``deletedAt``, or
    the search box becomes the one surface in the product that resurrects removed work.

    ``emptiedEntities`` and not an empty ``entries`` list, because those are different statements and
    only the first deletes anything: a payload that never NAMES a collection must not be able to
    sweep it (see ``save_stage``'s sweep, and the incident that rule was written for).
    """
    needle = _needle()
    _put_sketch(client, workshop, [{"_clientKey": "sk-a", "name": f"{needle} runner"}])
    removed = _put_sketch(client, workshop, [], emptied=["sketch"]).json()
    assert removed["removed"] == 1, removed

    body = client.get("/api/search", params={"q": needle, "types": "designWorkshops"}).json()
    assert body["designWorkshops"] == []


def test_the_scope_sentence_no_longer_claims_the_stages_are_unsearched(client, workshop):
    """**THE HONESTY SENTENCE, RETIRED.**

    ``designWorkshopSearchScope`` read "Answers inside the 22 stages are not searched." That was
    honesty about a limit; once the limit is gone the same sentence is a client-visible lie that
    tells a researcher not to trust an answer that is now correct. Both §6.1 and the review triggers
    at the foot of the decision record name this change as the trigger for retiring it.

    The second half is still a real limit and still has to be said, so the sentence is not simply
    deleted — it is asserted to have both properties.
    """
    body = client.get("/api/search", params={"q": "x", "types": "designWorkshops"}).json()
    scope = body["designWorkshopSearchScope"]
    assert "not searched" not in scope.split(".")[0], scope
    assert "stages" in scope, "what IS searched must still be said"
    assert "Numbers, dates and contact details are not searched" in scope


# --------------------------------------------------------------------------------------
# The backfill
# --------------------------------------------------------------------------------------


def test_the_backfill_computes_exactly_what_the_writer_wrote(client, workshop):
    """**THE ONE ASSERTION THAT MAKES THE BACKFILL SAFE TO RE-RUN.**

    ``scripts/backfill_stage_search_text.py`` skips a row whose stored value already equals the
    computed one, and reports how many it touched. If the script's renderer and ``save_stage``'s
    disagreed by so much as a separator, that skip would never fire: every run would rewrite every
    row in the table and report the whole corpus as "touched", which is indistinguishable from the
    column having been wrong.

    The registry rows are what this covers. The ``_custom`` branch of ``_computed`` needs a
    per-workshop definition and is exercised by ``custom_search_text``'s own pure tests; what cannot
    be checked without a database is that the two renderings of a REAL stored row agree.
    """
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
        # `_computed` reads ATTRIBUTES off a Prisma model; these came back as dicts because the
        # connection they were read on is already closed (see this module's header).
        assert _computed(SimpleNamespace(**row), None) == row["searchText"], row["entityKey"]
