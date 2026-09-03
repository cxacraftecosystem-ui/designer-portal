"""``DwStageEntry.version`` — the optimistic guard that stopped a stage UPDATE being last-write-wins.

WHAT THIS DEFENDS
==================

``SINGLETON_CLIENT_KEY`` closed the INSERT half of the two-designer race: two saves of one stage each
found no singleton, each planned an INSERT, and the unique index refuses the second —
``_absorb_key_collisions`` then rewrites the refusal into an UPDATE of the row the winner made.

**THE UPDATE IT REWRITES INTO WAS ITSELF UNGUARDED, AND SO WAS EVERY ORDINARY ONE BESIDE IT.**
``save_stage`` reads the stage's rows near the top, spends a validation pass, a ``hydrate_entries``
pass that issues its own queries, and a provenance merge on them, and then writes ``data`` WHOLESALE
by row id. Anything a second writer committed in that window was overwritten: no index refused it,
both designers were told ``updated: 1``, and only ``updatedAt`` recorded that anything had happened
at all. A payload without ``merge: true`` carries EVERY key of the row, so the loser's
stale-but-complete picture replaced the winner's edit to a field the loser had never opened — and
``DesignWorkshopViewer`` exists precisely so that two designers run one workshop over one set of
rows.

NO LATENCY IS QUOTED FOR THAT WINDOW. This paragraph first said "a database measured 756 ms away";
production moved to a co-located database on 2026-09-02, and ``prisma/schema.prisma``'s note on
``DwStageEntry.version`` records that figure as history and deliberately does not replace it —
quoting one invites the reading the column exists to refuse, that a small window is an acceptable
one. Two designers on one stage collide inside it at any speed, which is why the race below is
driven by an injected write rather than by timing. (Corrected 2026-09-03.)

WHAT IS PINNED HERE
===================

* the counter moves on an ordinary save, so the predicate is real rather than decorative;
* a competing write inside the read/write window makes the loser's UPDATE match nothing, and the row
  is REFUSED into the response's ``errors`` map instead of clobbering the winner;
* the refusal is per ROW: the other rows of the same payload still save, which is the same judgement
  the validation pass makes about one bad number in a stage of twenty good ones;
* a competing write that stored the SAME answers is not reported as a conflict, because that is what
  an offline replay looks like and a designer must not be shown "somebody else got there first" over
  their own retry;
* A REFUSED ROW CONTRIBUTES NOTHING TO THE WORKSHOP HEADER — added 2026-09-03. The fourteen promoted
  columns are copied off stage 1's ``workshopSetup`` singleton, they were computed once before the
  first write attempt, and the header write re-read that dict on every re-run. So the loser of a
  stage-1 race got the refusal sentence AND wrote its stale craft, cluster, state, district, venue
  and dates over the winner's — onto the cover page of the report and the columns the workshop list
  filters by. Pinned at both altitudes: ``_promotions_from_plan`` with no database, and the race
  itself through the API.

TWO HALVES, DELIBERATELY. The decision function ``_settle_version_conflicts`` is pure apart from one
read, so it is exercised against a fake table with no Postgres — that is where the subtlety lives.
The race itself cannot be faked and is driven end to end through the API, with the competing write
injected at ``hydrate_entries``: the one await inside ``save_stage`` that sits AFTER the read the
plan was built from and BEFORE the transaction that applies it. That is the window, and injecting
there is the only way to land in it deterministically rather than by racing two clients and hoping.
"""

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

#: ``anyio`` AND NOT PLAIN ``asyncio``, for the reason ``test_stage_sync`` gives: the database half
#: below needs a MODULE-SCOPED async fixture (one client, one portal, one loop, one Prisma
#: connection), and that is the runner this repository's database tests are written against. It
#: costs the pure half nothing.
pytestmark = pytest.mark.anyio


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


# --------------------------------------------------------------------------------------
# Half one: the decision, with no database
# --------------------------------------------------------------------------------------


def _row(row_id: str, data: dict[str, Any], version: int) -> Any:
    """A ``DwStageEntry`` carrying exactly what ``_settle_version_conflicts`` reads."""
    return SimpleNamespace(id=row_id, data=data, version=version)


def _planned(
    row_id: str,
    values: dict[str, Any],
    seen: int,
    scope: str,
    entity_key: str = "sketch",
) -> service._RowUpdate:
    """One planned UPDATE.

    ``entity_key`` DEFAULTS BECAUSE THE SETTLING TESTS DO NOT CARE WHICH ENTITY A ROW BELONGS TO —
    they decide kept-versus-refused from the version and the stored answers alone. The promotion
    tests below pass it, because for them it is the whole question.
    """
    return service._RowUpdate(
        row_id=row_id,
        seen_version=seen,
        columns={"data": values, "ordinal": 0, "deletedAt": None},
        values=values,
        scope=scope,
        entity_key=entity_key,
    )


class _EntryTable:
    """``db.dwstageentry`` — the re-read the settler makes, and nothing else."""

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
    """The plan is re-run whole, so every row that passed its predicate has to survive the settling.

    Dropping them would turn one contested row into a save that silently stored nothing else.
    """
    _install(monkeypatch, [_row("r1", {"name": "Runner"}, 3)])
    quiet = _planned("r2", {"name": "Stole"}, 0, "sketch[1]")
    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", [quiet], set())
    assert kept == [quiet]
    assert refused == []


async def test_a_winner_that_stored_the_same_answers_is_not_a_conflict(monkeypatch):
    """AN OFFLINE REPLAY MUST NOT RAISE A CONFLICT AGAINST ITSELF.

    A phone that never received the acknowledgement for a sync sends the queue again; two copies of
    one payload overlap in flight; the second finds the row at a version it did not read. Nothing was
    lost and nobody disagreed — the stored answers are the ones this request was about to write — so
    the update is re-planned at the fresh version and applied. Telling the designer that somebody
    else got there first, when the somebody else is their own retry, is a false alarm on the most
    ordinary path this table has.
    """
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
    """THE WHOLE FINDING, IN ONE ASSERTION.

    Re-planning this row at the fresh version would be last-write-wins arriving through the back
    door — the loser's complete-but-stale picture written over an edit it never saw. It leaves the
    plan instead, and the caller files one sentence about it.
    """
    _install(monkeypatch, [_row("r1", {"name": "Runner", "notes": "hers"}, 7)])
    planned = _planned("r1", {"name": "Runner"}, 4, "sketch[0]")
    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", [planned], {"r1"})
    assert kept == []
    assert [r.row_id for r in refused] == ["r1"]
    assert refused[0].scope == "sketch[0]"


async def test_a_row_that_has_vanished_is_refused_and_not_re_created(monkeypatch):
    """There is nothing left to update, and inserting a replacement would resurrect a deleted row.

    That would turn a concurrency problem into a data problem: a row somebody removed reappearing
    under a 200, in a document submitted to a ministry.
    """
    _install(monkeypatch, [])
    planned = _planned("r1", {"name": "Runner"}, 4, "sketch[0]")
    kept, refused = await service._settle_version_conflicts("dw-1", "SKETCH", [planned], {"r1"})
    assert kept == []
    assert [r.row_id for r in refused] == ["r1"]


async def test_one_contested_row_does_not_take_its_siblings_with_it(monkeypatch):
    """The per-row rule, which is what makes the refusal survivable.

    ``save_stage`` already refuses to lose twenty good fields to one typo; a collision on one row of
    a thirty-row participant list must not lose the other twenty-nine either.
    """
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
    """The identity test is given up on the final attempt, and giving it up is the point.

    A row re-planned at a refreshed version can be beaten again, so a request unlucky enough to be
    overtaken twice would go round for ever — or, bounded by a bare raise, answer 500 and store
    NOTHING, losing every other row the designer typed. ``surrender`` refuses the contested rows
    unconditionally instead, which leaves a plan that cannot collide with anything that has already
    happened, so the write lands and the refusals are sentences rather than a lost stage.

    The same input that is kept without it is refused with it — asserted as a pair, because the flag
    is only meaningful as a difference.
    """
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
    """The premise is that the rows moved, so the pre-write picture is the one thing that is wrong.

    ``_absorb_key_collisions`` makes the same choice for the same reason, and this pins the query
    that makes it true: the whole stage, by workshop and stage key, soft-deleted rows included.
    """
    table = _install(monkeypatch, [_row("r1", {"name": "Runner"}, 7)])
    await service._settle_version_conflicts(
        "dw-1", "SKETCH_DEVELOPMENT", [_planned("r1", {}, 0, "sketch[0]")], {"r1"}
    )
    assert table.finds == [
        {"where": {"designWorkshopId": "dw-1", "stageKey": "SKETCH_DEVELOPMENT"}}
    ]


def test_the_refusal_sentence_is_one_line_and_names_the_next_move():
    """UI copy, asserted because it is copy a designer reads in a courtyard on one bar of signal.

    One sentence, state then action. It never says "try again", because re-sending the same stale
    answers would be refused identically — the next move is to look at what the other person wrote.
    """
    sentence = service.STAGE_ROW_CONFLICT_MESSAGE
    assert sentence.count(".") <= 1
    assert len(sentence) < 130
    assert "try again" not in sentence.lower()
    assert service.STAGE_ROW_CONFLICT_KEY.startswith("_"), (
        "a row-level refusal must not occupy a key that could collide with a real field, and the "
        "underscore is this protocol's own mark for 'not workshop data'"
    )


# --------------------------------------------------------------------------------------
# Half one and a half: what a refused row is allowed to contribute to the workshop HEADER
#
# `DesignWorkshop` carries fourteen PROMOTED columns copied off stage 1's singleton — the cover page
# of every report and every column the workshop list filters by. They were computed ONCE, before the
# first write attempt, over every entry in the payload; the settling above then dropped a contested
# row from the plan and filed a refusal for it, and the header write went on reading the same dict.
# So the loser of a stage-1 race was told "someone else saved this row first" AND had its craft,
# cluster, state, district, venue and dates written over the winner's. (2026-09-03)
# --------------------------------------------------------------------------------------


def test_a_refused_row_takes_its_promoted_columns_out_of_the_header_with_it():
    """THE FINDING, AT THE UNIT THE HEADER IS ACTUALLY BUILT FROM.

    The plan handed to the header write is the plan the settling left behind. With the only stage-1
    row refused there is nothing left to promote — and, just as importantly, no entity to promote
    FROM, because naming one with no values is how ``_coerce_promoted`` is told to NULL its columns.
    """
    loser = _planned("r1", {"craftName": "Bandhani"}, 0, "workshopSetup", "workshopSetup")
    promoted, entities = service._promotions_from_plan([], [loser], {})
    assert promoted == {"craftName": "Bandhani"}
    assert entities == {"workshopSetup"}

    # What the settling leaves when that row is refused: an empty plan.
    promoted, entities = service._promotions_from_plan([], [], {})
    assert promoted == {} and entities == set()
    assert service._coerce_promoted(promoted, entities) == {}, (
        "a refused row must contribute NOTHING to the header — neither its own values nor the "
        "instruction to blank the winner's"
    )


def test_a_surviving_sibling_still_promotes_when_another_row_is_refused():
    """The per-row rule again, from the header's side: refusing one row must not cost the others.

    A stage-1 payload carries collection rows beside the singleton. Dropping one of those — which is
    exactly what the settling above hands back — must leave the cover page where the singleton put
    it, and a collection row must never have been contributing to it in the first place.
    """
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
    """The first save of stage 1 is an INSERT, and its values live beside the create, not in it.

    A ``creates`` entry is handed to Prisma verbatim and its ``data`` is a wrapped ``Json``, so the
    unwrapped answers travel in ``_CreatePlan`` — keyed by the same ``(entityKey, clientKey)`` pair
    ``_absorb_key_collisions`` already looks rows up by.
    """
    key = ("workshopSetup", "__dw_singleton__:WORKSHOP_SETUP")
    creates = [{"entityKey": key[0], "clientKey": key[1], "data": object()}]
    plans = {key: service._CreatePlan(scope="workshopSetup", values={"craftName": "Ikat"})}
    promoted, entities = service._promotions_from_plan(creates, [], plans)
    assert promoted == {"craftName": "Ikat"}
    assert entities == {"workshopSetup"}


def test_a_create_with_no_plan_promotes_nothing_and_blanks_nothing():
    """Unreachable today — every create registers a plan — and the arm still has to be the safe one.

    With no values there is nothing to promote, so naming the entity anyway would only tell
    ``_coerce_promoted`` to NULL that entity's columns: a cover page wiped by a save that stored a
    row perfectly well.
    """
    creates = [{"entityKey": "workshopSetup", "clientKey": None, "data": object()}]
    promoted, entities = service._promotions_from_plan(creates, [], {})
    assert promoted == {} and entities == set()


def test_the_retry_bound_is_small_and_finite():
    """A race a designer is waiting on must not be allowed to become a hang.

    A refused row leaves the plan and cannot collide again, so divergent conflicts shrink to nothing
    on their own. A row re-planned at a refreshed version has no such guarantee — a third writer can
    move it again — so the bound is what actually ends that arm, and it has to stay small. The last
    settled attempt surrenders rather than raising (see the test above), so the number is a ceiling
    on how long a contested save keeps trying, not on whether it stores anything.
    """
    assert 2 <= service._STAGE_WRITE_ATTEMPTS <= 3


# --------------------------------------------------------------------------------------
# Half two: the race itself, against a real database
#
# **THE LOOP RULE, WHICH THIS FILE OBEYS AND WHICH `test_stage_sync` WROTE DOWN.** `TestClient` runs
# the app — and therefore `db.connect()` — inside its OWN portal event loop, and Prisma's HTTP
# session is bound to the loop that opened it. So NO TEST BODY BELOW AWAITS `db`. Everything a test
# needs to know about the stored rows it learns either over HTTP or from the injected hook, which
# `save_stage` calls from inside the request and therefore in the portal loop that owns the
# connection. Reading a row directly from a test body is what took two of that module's tests down
# as setup errors, with a traceback pointing at `asyncio.locks.Event._get_loop` and nothing pointing
# back at the cause.
# --------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
async def client():
    """A TestClient with a signed-in admin, sharing one Prisma connection with the app.

    Lifted verbatim from ``test_stage_sync.py``, which is the module this one is a sibling of: the
    same fixture shape, so a reader moving between them is not comparing two ways of doing one thing.
    """
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
    """Run inside ``save_stage``'s read/write window, recording the row and optionally writing it.

    ``hydrate_entries`` is the await that sits AFTER the read the plan was built from and BEFORE the
    transaction that applies it, so wrapping it lands exactly in the window this guard exists for.
    ``test_stage_sync``'s own singleton-race test injects at the same point and for the same reason:
    racing two real clients and hoping is not a test.

    STILL ``async def`` AND STILL AWAITING ``db``, which does not break the loop rule above: this
    body is called by ``save_stage`` from inside the request, so it runs in the portal loop that
    owns the connection.

    ``write=False`` observes only — it is how a test body learns a version without awaiting anything
    itself. With ``write=True``, ``rewrite=None`` bumps the version and leaves the answers alone
    (the offline-replay shape: somebody stored what we were about to store), and a dict is a genuine
    divergence. The write fires ONCE: the re-run calls this function again, and a competitor that
    fired on every attempt would be modelling a machine nobody has.

    ``stage_key`` AND ``client_key`` DEFAULT TO THE SKETCH COLLECTION the race tests below use. The
    header test points them at stage 1's singleton instead, whose key is the reserved
    ``__dw_singleton__:WORKSHOP_SETUP`` rather than anything a client sent — that is what
    ``save_stage`` stores for every singleton, so it is what the competing write has to address.
    """
    from prisma import Json

    original = service.hydrate_entries
    #: The `version` of the sk-a row as each save read it, in order. The test body reads this list
    #: after the request has returned, which needs no await.
    seen: list[int] = []
    written: list[str] = []

    # `**kwargs` AND NOT A NAMED `workshop_id`: this is a pass-through around the real function, and
    # enumerating its parameters here would make it a second declaration of the signature that has
    # to be edited every time the real one grows. It grew `workshop_id` on 2026-09-03, for the
    # internal reference carry. What this double is FOR is the write it interleaves.
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
    """The predicate is only worth anything if the write it guards actually advances it.

    Observed from inside the request rather than read from the test body — see the loop rule above.
    The first save records nothing because the row does not exist yet when the hook runs, which is
    itself the assertion that a CREATE takes the column's default rather than naming a version.
    """
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
    """THE INCIDENT THIS COLUMN EXISTS FOR, END TO END.

    Two designers hold one workshop. The second read the sketch, started typing, and while their
    save was in flight the first one changed the same row. Before the counter, the second save wrote
    its whole picture over the first's edit and reported success to both of them.
    """
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
    """The replay case: the version moved, nothing disagreed, and nobody is told anything.

    This is what a phone re-sending a queue it already sent looks like from the server, and it is
    common. A conflict message here would train designers to ignore the one sentence that matters.
    """
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
    """Stage 1, whose ``workshopSetup`` singleton is the source of all fourteen promoted columns."""
    return client.put(
        f"/api/design-workshops/{workshop_id}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup", "ordinal": 0, "data": data}]},
    )


@needs_db
async def test_a_refused_stage_one_row_writes_nothing_onto_the_workshop_header(
    client, workshop, monkeypatch
):
    """THE HEADER HALF OF THE RACE, END TO END, AND IT WAS THE HALF THAT STAYED SILENT.

    Two designers hold one workshop and both open stage 1. The second's save is in flight when the
    first changes the same singleton — there is only ever one ``workshopSetup`` row, so they are
    editing literally the same row. The row write is refused and the designer is told so.

    The COLUMNS were not. ``DesignWorkshop.craftName``, ``title``, ``clusterName``, ``state``,
    ``district``, ``venue`` and the dates are copied off that same entry, and they were computed
    before the first attempt and re-read on every re-run — so the loser's stale cover page went onto
    the workshop under a 200 that also said the row had been refused. That is the report's front
    page, and every column the workshop list filters by.
    """
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
