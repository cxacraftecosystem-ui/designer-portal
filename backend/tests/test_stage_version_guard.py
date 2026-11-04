from __future__ import annotations

import uuid
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import design_workshops as service

pytestmark = pytest.mark.anyio


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"



def _row(row_id: str, data: dict[str, Any], version: int) -> Any:
    return SimpleNamespace(id=row_id, data=data, version=version)


def _planned(
    row_id: str,
    values: dict[str, Any],
    seen: int,
    scope: str,
    entity_key: str = "sketch",
    changed: bool = True,
) -> service._RowUpdate:
    return service._RowUpdate(
        row_id=row_id,
        seen_version=seen,
        columns={"data": values, "ordinal": 0, "deletedAt": None},
        values=values,
        scope=scope,
        entity_key=entity_key,
        changed=changed,
    )


class _EntryTable:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.finds: list[dict[str, Any]] = []

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.finds.append(kwargs)
        return list(self.rows)


def _install(monkeypatch: Any, rows: list[Any]) -> _EntryTable:
    table = _EntryTable(rows)
    monkeypatch.setattr(service, "db", SimpleNamespace(dwstageentry=table))
    return table


async def test_a_row_nobody_contested_is_carried_across_untouched(monkeypatch):
    _install(monkeypatch, [_row("r1", {"name": "Runner"}, 3)])
    quiet = _planned("r2", {"name": "Stole"}, 0, "sketch[1]")
    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", [quiet], set())
    assert kept == [quiet]
    assert refused == []


async def test_a_winner_that_stored_the_same_answers_is_not_a_conflict(monkeypatch):
    _install(monkeypatch, [_row("r1", {"name": "Runner"}, 7)])
    planned = _planned("r1", {"name": "Runner"}, 4, "sketch[0]")
    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", [planned], {"r1"})
    assert refused == []
    assert len(kept) == 1
    assert kept[0].seen_version == 7, (
        "the re-run must be planned against the version that actually exists, or it collides "
        "again on every attempt until the bound gives up"
    )
    assert kept[0].values == {"name": "Runner"}
    assert kept[0].scope == "sketch[0]"


async def test_a_winner_that_stored_something_else_is_refused_rather_than_overwritten(monkeypatch):
    _install(monkeypatch, [_row("r1", {"name": "Runner", "notes": "hers"}, 7)])
    planned = _planned("r1", {"name": "Runner"}, 4, "sketch[0]")
    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", [planned], {"r1"})
    assert kept == []
    assert [r.row_id for r in refused] == ["r1"]
    assert refused[0].scope == "sketch[0]"


async def test_a_row_that_has_vanished_is_refused_and_not_re_created(monkeypatch):
    _install(monkeypatch, [])
    planned = _planned("r1", {"name": "Runner"}, 4, "sketch[0]")
    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", [planned], {"r1"})
    assert kept == []
    assert [r.row_id for r in refused] == ["r1"]


async def test_one_contested_row_does_not_take_its_siblings_with_it(monkeypatch):
    _install(
        monkeypatch,
        [_row("r1", {"name": "Theirs"}, 7), _row("r2", {"name": "Stole"}, 1)],
    )
    contested = _planned("r1", {"name": "Ours"}, 4, "sketch[0]")
    quiet = _planned("r2", {"name": "Stole II"}, 1, "sketch[1]")
    kept, refused = await service._settle_version_conflicts(
        "dw-1", "SKETCH", [contested, quiet], {"r1"}
    )
    assert [r.row_id for r in kept] == ["r2"]
    assert [r.row_id for r in refused] == ["r1"]


async def test_the_last_pass_surrenders_so_the_rest_of_the_stage_still_saves(monkeypatch):
    _install(monkeypatch, [_row("r1", {"name": "Runner"}, 7)])
    planned = _planned("r1", {"name": "Runner"}, 4, "sketch[0]")

    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", [planned], {"r1"})
    assert [r.row_id for r in kept] == ["r1"] and refused == []

    _install(monkeypatch, [_row("r1", {"name": "Runner"}, 7)])
    kept, refused = await service._settle_version_conflicts(
        "dw-1", "SKETCH", [planned], {"r1"}, surrender=True
    )
    assert kept == []
    assert [r.row_id for r in refused] == ["r1"]


async def test_the_settler_re_reads_rather_than_trusting_the_plan(monkeypatch):
    table = _install(monkeypatch, [_row("r1", {"name": "Runner"}, 7)])
    await service._settle_version_conflicts(
        "dw-1", "SKETCH_DEVELOPMENT", [_planned("r1", {}, 0, "sketch[0]")], {"r1"}
    )
    assert table.finds == [
        {"where": {"designWorkshopId": "dw-1", "stageKey": "SKETCH_DEVELOPMENT"}}
    ]


def test_the_refusal_sentence_is_one_line_and_names_the_next_move():
    sentence = service.STAGE_ROW_CONFLICT_MESSAGE
    assert sentence.count(".") <= 1
    assert len(sentence) < 130
    assert "try again" not in sentence.lower()
    assert service.STAGE_ROW_CONFLICT_KEY.startswith("_"), (
        "a row-level refusal must not occupy a key that could collide with a real field, and the "
        "underscore is this protocol's own mark for 'not workshop data'"
    )



def test_a_refused_row_takes_its_promoted_columns_out_of_the_header_with_it():
    loser = _planned("r1", {"craftName": "Bandhani"}, 0, "workshopSetup", "workshopSetup")
    promoted, entities = service._promotions_from_plan([], [loser], {})
    assert promoted == {"craftName": "Bandhani"}
    assert entities == {"workshopSetup"}

    promoted, entities = service._promotions_from_plan([], [], {})
    assert promoted == {} and entities == set()
    assert service._coerce_promoted(promoted, entities) == {}, (
        "a refused row must contribute NOTHING to the header — neither its own values nor the "
        "instruction to blank the winner's"
    )


def test_a_surviving_sibling_still_promotes_when_another_row_is_refused():
    setup = _planned(
        "r1", {"craftName": "Ikat", "venue": "Bargarh"}, 3, "workshopSetup", "workshopSetup"
    )
    sibling = _planned("r2", {"name": "A sketch"}, 1, "sketch[0]", "sketch")

    whole, entities = service._promotions_from_plan([], [setup, sibling], {})
    settled, settled_entities = service._promotions_from_plan([], [setup], {})
    assert whole == settled == {"craftName": "Ikat", "venue": "Bargarh"}
    assert entities == {"workshopSetup", "sketch"} and settled_entities == {"workshopSetup"}

    header = service._coerce_promoted(settled, settled_entities)
    assert header["craftName"] == "Ikat" and header["venue"] == "Bargarh"
    assert header["clusterName"] is None, (
        "a column whose entity IS writing a row and whose value is blank is still NULLed — that "
        "rule is unchanged, and it is why the refused case may not name the entity at all"
    )
    assert "title" not in header, (
        "`title` is NOT NULL on DesignWorkshop, so `_coerce_promoted` skips it rather than "
        "failing the whole save with a MissingRequiredValueError"
    )


def test_a_create_promotes_through_its_registered_plan():
    key = ("workshopSetup", "__dw_singleton__:WORKSHOP_SETUP")
    creates = [{"entityKey": key[0], "clientKey": key[1], "data": object()}]
    plans = {key: service._CreatePlan(scope="workshopSetup", values={"craftName": "Ikat"})}
    promoted, entities = service._promotions_from_plan(creates, [], plans)
    assert promoted == {"craftName": "Ikat"}
    assert entities == {"workshopSetup"}


def test_a_create_with_no_plan_promotes_nothing_and_blanks_nothing():
    creates = [{"entityKey": "workshopSetup", "clientKey": None, "data": object()}]
    promoted, entities = service._promotions_from_plan(creates, [], {})
    assert promoted == {} and entities == set()



def test_every_row_update_declares_whether_it_changed():
    import dataclasses

    fields = {f.name: f for f in dataclasses.fields(service._RowUpdate)}
    assert "changed" in fields, "the per-row change flag has gone; the resubmission gate is blind"
    assert fields["changed"].default is dataclasses.MISSING
    assert fields["changed"].default_factory is dataclasses.MISSING


def test_a_save_that_planned_nothing_does_not_resubmit():
    assert service._content_changed([], [], []) is False


def test_a_replayed_identical_payload_does_not_resubmit():
    replay = [
        _planned("r1", {"name": "Runner"}, 3, "sketch[0]", changed=False),
        _planned("r2", {"name": "Stole"}, 4, "sketch[1]", changed=False),
    ]
    assert service._content_changed([], replay, []) is False


def test_one_changed_row_among_many_unchanged_resubmits():
    plan = [
        _planned("r1", {"name": "Runner"}, 3, "sketch[0]", changed=False),
        _planned("r2", {"name": "Stole, 180cm"}, 4, "sketch[1]", changed=True),
    ]
    assert service._content_changed([], plan, []) is True


def test_a_new_row_and_a_removed_row_each_resubmit():
    assert service._content_changed([{"entityKey": "sketch"}], [], []) is True
    assert service._content_changed([], [], ["r9"]) is True


async def test_a_save_whose_every_row_was_refused_does_not_resubmit(monkeypatch):
    _install(monkeypatch, [_row("r1", {"name": "Somebody else's answer"}, 9)])
    contested = [_planned("r1", {"name": "Runner"}, 1, "sketch[0]", changed=True)]
    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", contested, {"r1"})
    assert [r.row_id for r in refused] == ["r1"]
    assert service._content_changed([], kept, []) is False


def test_a_re_planned_row_keeps_its_own_verdict_across_the_settling():
    planned = _planned("r1", {"name": "Runner"}, 4, "sketch[0]", changed=True)
    from dataclasses import replace

    assert replace(planned, seen_version=7).changed is True


def test_the_retry_bound_is_small_and_finite():
    assert 2 <= service._STAGE_WRITE_ATTEMPTS <= 3



@pytest.fixture(scope="module")
async def client():
    from fastapi.testclient import TestClient

    from app.main import app

    email = f"version-guard-{uuid.uuid4().hex[:8]}@example.org"
    await db.connect()
    try:
        user = await db.user.create(
            data={
                "email": email,
                "name": "Version Guard",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            }
        )
    finally:
        await db.disconnect()

    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield c


@pytest.fixture
def workshop(client):
    response = client.post("/api/design-workshops", json={"title": "Version guard workshop"})
    assert response.status_code == 201, response.text
    return response.json()["id"]


A = {"_clientKey": "sk-a", "sketchNo": "SK-01", "name": "Runner"}
B = {"_clientKey": "sk-b", "sketchNo": "SK-02", "name": "Stole"}


def _save(client, workshop_id, rows):
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/SKETCH_DEVELOPMENT",
        json={
            "entries": [
                {"entityKey": "sketch", "ordinal": i, "data": row} for i, row in enumerate(rows)
            ],
            "replaceCollections": True,
        },
    )


def _read(client, workshop_id):
    payload = client.get(
        f"/api/design-workshops/{workshop_id}/stages/SKETCH_DEVELOPMENT"
    ).json()
    return {row["_clientKey"]: row for row in payload["collections"].get("sketch", [])}


def _at_the_window(
    monkeypatch,
    workshop_id: str,
    *,
    rewrite: dict[str, Any] | None,
    write: bool,
    stage_key: str = "SKETCH_DEVELOPMENT",
    client_key: str = "sk-a",
):
    from prisma import Json

    original = service.hydrate_entries
    seen: list[int] = []
    written: list[str] = []

    async def wrapper(pending, **kwargs):
        result = await original(pending, **kwargs)
        rows = await db.dwstageentry.find_many(
            where={
                "designWorkshopId": workshop_id,
                "stageKey": stage_key,
                "clientKey": client_key,
            }
        )
        for row in rows:
            seen.append(int(getattr(row, "version", 0) or 0))
            if not write or written:
                continue
            written.append(row.id)
            data: dict[str, Any] = {"version": {"increment": 1}}
            if rewrite is not None:
                data["data"] = Json({**dict(row.data or {}), **rewrite})
            await db.dwstageentry.update_many(where={"id": row.id}, data=data)
        return result

    monkeypatch.setattr(service, "hydrate_entries", wrapper)
    return seen, written


@needs_db
async def test_an_ordinary_save_moves_the_counter(client, workshop, monkeypatch):
    seen, _ = _at_the_window(monkeypatch, workshop, rewrite=None, write=False)
    assert _save(client, workshop, [A]).status_code == 200
    assert seen == [], "the row should not exist before the first save's transaction"

    assert _save(client, workshop, [A]).status_code == 200
    assert _save(client, workshop, [A]).status_code == 200
    assert seen == [0, 1], (
        "the UPDATE must increment in the same statement it writes in, or two requests can both "
        f"read one version and both pass their predicate; the saves read {seen}"
    )


@needs_db
async def test_a_second_writer_in_the_window_is_refused_rather_than_overwritten(
    client, workshop, monkeypatch
):
    assert _save(client, workshop, [A, B]).status_code == 200
    _seen, written = _at_the_window(
        monkeypatch, workshop, rewrite={"name": "Hers, typed while ours was in flight"}, write=True
    )

    response = _save(
        client, workshop, [{**A, "name": "Ours, from a stale form"}, {**B, "name": "Stole II"}]
    )
    assert response.status_code == 200, response.text
    assert written, "the competing write never landed; this test proved nothing"
    body = response.json()

    assert "sketch[0]" in body["errors"], (
        f"the contested row was not reported; errors were {body['errors']}"
    )
    assert (
        body["errors"]["sketch[0]"][service.STAGE_ROW_CONFLICT_KEY]
        == service.STAGE_ROW_CONFLICT_MESSAGE
    )
    assert body["refusedAnswers"] >= 1, "the headline count both clients render has to see it too"

    rows = _read(client, workshop)
    assert rows["sk-a"]["name"] == "Hers, typed while ours was in flight", (
        "the other designer's answer was overwritten — this is the exact failure the version "
        "predicate was added to make impossible"
    )
    assert rows["sk-b"]["name"] == "Stole II", (
        "one contested row must not cost the rest of the payload; a designer losing a whole stage "
        "to a collision on one row is worse than the collision"
    )


@needs_db
async def test_a_competing_write_of_the_same_answers_is_not_reported_as_a_conflict(
    client, workshop, monkeypatch
):
    assert _save(client, workshop, [A, B]).status_code == 200
    _seen, written = _at_the_window(monkeypatch, workshop, rewrite=None, write=True)

    response = _save(client, workshop, [A, B])
    assert response.status_code == 200, response.text
    assert written, "the competing bump never landed; this test proved nothing"
    body = response.json()
    assert body["errors"] == {}, f"a same-answers race was reported as a conflict: {body['errors']}"
    assert body["updated"] == 2
    assert _read(client, workshop)["sk-a"]["name"] == "Runner"


def _save_setup(client, workshop_id, data: dict[str, Any]):
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup", "ordinal": 0, "data": data}]},
    )


@needs_db
async def test_a_refused_stage_one_row_writes_nothing_onto_the_workshop_header(
    client, workshop, monkeypatch
):
    winner = {"workshopTitle": "Ikat cover", "craftName": "Sambalpuri Ikat", "venue": "Bargarh"}
    assert _save_setup(client, workshop, winner).status_code == 200
    header = client.get(f"/api/design-workshops/{workshop}").json()
    assert (header["title"], header["craftName"]) == ("Ikat cover", "Sambalpuri Ikat")

    _seen, written = _at_the_window(
        monkeypatch,
        workshop,
        rewrite={"craftName": "Sambalpuri Ikat, as she corrected it"},
        write=True,
        stage_key="WORKSHOP_SETUP",
        client_key=service.singleton_client_key("WORKSHOP_SETUP"),
    )

    response = _save_setup(
        client,
        workshop,
        {"workshopTitle": "Bandhani cover", "craftName": "Bandhani", "venue": "Bhuj"},
    )
    assert response.status_code == 200, response.text
    assert written, "the competing write never landed; this test proved nothing"
    body = response.json()
    assert (
        body["errors"].get("workshopSetup", {}).get(service.STAGE_ROW_CONFLICT_KEY)
        == service.STAGE_ROW_CONFLICT_MESSAGE
    ), f"the contested singleton was not reported; errors were {body['errors']}"

    header = client.get(f"/api/design-workshops/{workshop}").json()
    assert header["craftName"] == "Sambalpuri Ikat", (
        "the refused row's craft was promoted onto the header anyway — the designer was told "
        "their row was not saved while its values overwrote the cover page"
    )
    assert header["title"] == "Ikat cover", (
        "`title` is the promoted column `_coerce_promoted` refuses to blank, which makes it the "
        "one a refused row could only ever damage by OVERWRITING"
    )
