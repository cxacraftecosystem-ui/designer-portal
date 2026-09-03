"""``clientKey`` — the create-idempotency key that stops a lost answer becoming a second record.

WHAT THIS DEFENDS
==================

A queued create is POSTed, the row is written, and the answer dies on the way back: a tunnel, a
captive portal, the process killed while the request was in flight. The client learned nothing, so
the entry is still in its outbox and the next pass sends the identical body — a SECOND government
record for one save, under one designer's name, in an index nobody reconciles.

**BOTH CLIENTS ALREADY GUARD THE CASE THEY CAN SEE, AND NEITHER CAN GUARD THIS ONE.**
``frontend/lib/offline.ts`` writes ``createdId`` the moment an answer lands and ``entryAlreadyCreated``
reads it back; Android's ``PendingEntry.createdId`` does the same. Both are records of a REPLY, so
both are structurally blind to a reply that never came. The web outbox names the missing piece by
name in ``persistProgress``, and the sentence is the specification for this column:

    "a few milliseconds of IndexedDB is as small as that window gets without idempotency keys on the
     API."

FOUR MODELS, AND THE OTHER TWO ARE DELIBERATELY ABSENT
=======================================================

``Artisan`` is already idempotent under a better key: ``aadhaarNumber`` is ``@unique`` and
``artisans._guard_identity_conflicts`` answers a pre-write 409 that NAMES the artisan already holding
the number. ``Craft.name`` is ``@unique`` with its own 409. A second mechanism beside either would be
two guards that can disagree about what a duplicate is — and worse, both outboxes' 409 arms are
written on the assumption that a clash is SOMEBODY ELSE'S record, which a collision with the caller's
own earlier create would falsify.

WHAT IS PINNED HERE
====================

* an absent key costs nothing and changes nothing — no read, no column, today's behaviour exactly,
  which is what every fielded 0.0.7 APK and every cached web bundle sends;
* a repeated key returns the row the first create made, through the same handler and the same
  encoder, so the caller cannot tell a replay from a first landing;
* the replay is answered ABOVE the write gates and above ``attach_location``, so a lapsed workshop
  grant cannot refuse a create that already succeeded and a replay mints no orphan ``Location``;
* another account's key is a 403 and never that account's record;
* a ``clientKey`` unique violation — the race the pre-read cannot settle — is turned into the winner's
  row, while any other exception from the same ``create`` goes on being raised.

NO DATABASE. The subtlety is in the decision and in the ORDER, and both are exercised against fake
delegates, the way ``test_workshops.py`` drives the sibling list route and ``test_stage_version_guard``
drives its settler. A fake table also lets a test assert something Postgres cannot be asked politely:
that the gates below the replay branch were never reached.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.api.routes import (
    processes as process_routes,
    products as product_routes,
    tools as tool_routes,
    workshops as workshop_routes,
)
from app.core.db import db
from app.schemas.records import (
    ProcessCreate,
    ProductCreate,
    ToolCreate,
    WorkshopCreate,
)
from app.services import records as records_service

pytestmark = pytest.mark.anyio


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


#: The caller every request below is filed as.
#:
#: FOUR FIELDS, EACH READ BY SOMETHING ON THE CREATE PATH: ``id`` by ``client_key_replay`` and by
#: ``createdById``, ``name`` by ``merge_field_provenance``'s ``byName`` stamp, ``role`` by
#: ``apply_status_policy_create``'s rank test, and ``email`` because the identity mask in
#: ``public_encode`` resolves a viewer. A DESIGNER deliberately — below PROFESSOR, so the status
#: policy forces PENDING and the test is not accidentally exercising the self-approval branch.
CALLER = SimpleNamespace(
    id="u-caller", name="R. Menon", email="r@example.org", role="DESIGNER"
)

#: The account that did NOT create the rows below.
STRANGER = SimpleNamespace(
    id="u-stranger", name="Someone Else", email="s@example.org", role="DESIGNER"
)


class _Table:
    """One Prisma delegate, recording every call so a test can assert what was NOT asked for.

    ``create`` raises whatever ``raises`` holds, which is how the race branch is reached without a
    database and without guessing at Prisma's error text — the text is quoted from the real one in
    ``_UNIQUE_VIOLATION`` below.
    """

    def __init__(self, rows: list[Any] | None = None, raises: Exception | None = None) -> None:
        self.rows = rows or []
        self.raises = raises
        self.find_unique_calls: list[dict[str, Any]] = []
        self.create_calls: list[dict[str, Any]] = []

    async def find_unique(self, **kwargs: Any) -> Any:
        self.find_unique_calls.append(kwargs)
        where = kwargs.get("where") or {}
        for row in self.rows:
            if all(getattr(row, key, None) == value for key, value in where.items()):
                return row
        return None

    async def find_many(self, **kwargs: Any) -> list[Any]:
        return []

    async def create(self, **kwargs: Any) -> Any:
        self.create_calls.append(kwargs)
        if self.raises is not None:
            raise self.raises
        row = SimpleNamespace(id="new-row", **kwargs.get("data", {}))
        self.rows.append(row)
        return row


#: PRISMA'S OWN WORDS, near enough. ``is_client_key_violation`` matches on "unique" plus the column
#: name, exactly as ``artisans._violated_identity_field`` does — so the string this test raises has to
#: carry both, and a string carrying only one has to go on being re-raised (see the tests below).
_UNIQUE_VIOLATION = RuntimeError(
    'Unique constraint failed on the fields: (`clientKey`)'
)


def _row(model_id: str, *, client_key: str | None, created_by: str = "u-caller") -> Any:
    """A stored record carrying only what the replay branch and the encoder read off it."""
    return SimpleNamespace(
        id=model_id,
        clientKey=client_key,
        createdById=created_by,
        createdAt=datetime(2026, 9, 3, 10, 0, tzinfo=UTC),
        updatedAt=datetime(2026, 9, 3, 10, 0, tzinfo=UTC),
        status="PENDING",
        extraMetadata=None,
    )


# --------------------------------------------------------------------------------------
# Half one: the decision, with no route around it
# --------------------------------------------------------------------------------------


async def test_an_absent_key_costs_not_even_a_read():
    """The wire contract, and the cheapest possible statement of it.

    Every client shipped to date sends no key. If this ever starts issuing a query, the change has
    made every create on the deployment pay for a guard that none of them asked for — and it would do
    it silently, which is how a 683 ms round trip gets added to a write path nobody re-measures.
    """
    table = _Table()
    assert await records_service.client_key_replay(table, None, user_id="u-caller") is None
    assert await records_service.client_key_replay(table, "", user_id="u-caller") is None
    assert table.find_unique_calls == []


async def test_an_unseen_key_reads_once_and_answers_none():
    table = _Table()
    assert await records_service.client_key_replay(table, "k-1", user_id="u-caller") is None
    assert table.find_unique_calls == [{"where": {"clientKey": "k-1"}}]


async def test_a_seen_key_answers_with_the_row_the_first_create_made():
    stored = _row("p-1", client_key="k-1")
    table = _Table([stored])
    assert await records_service.client_key_replay(table, "k-1", user_id="u-caller") is stored


async def test_the_include_is_passed_through_so_the_replay_carries_the_same_relations():
    # The response of a replay has to be the same SHAPE as the response of a first landing, and on
    # three of the four models that shape is defined by ``INCLUDE``. Omitting it here would answer a
    # record with no craft, no location and no media — a different payload for the same request.
    table = _Table([_row("p-1", client_key="k-1")])
    await records_service.client_key_replay(
        table, "k-1", user_id="u-caller", include={"craft": True}
    )
    assert table.find_unique_calls == [
        {"where": {"clientKey": "k-1"}, "include": {"craft": True}}
    ]


async def test_another_accounts_key_is_a_403_and_never_their_record():
    """The rule ``complete_media_upload`` applies to ``uploadedById``, ported.

    A v4 UUID is unguessable, so this is not really a defence against an attacker — it is a defence
    against ANSWERING WITH THE WRONG PERSON'S RECORD if a key is ever copied between accounts. 201
    would hand over a stranger's fieldwork; 409 would invite a retry that can only fetch the same
    answer. A 403 is the honest answer to "your key is taken".
    """
    table = _Table([_row("p-1", client_key="k-1", created_by="u-caller")])
    with pytest.raises(HTTPException) as caught:
        await records_service.client_key_replay(table, "k-1", user_id=STRANGER.id)
    assert caught.value.status_code == 403
    assert "another account" in str(caught.value.detail)


# --------------------------------------------------------------------------------------
# The race the pre-read cannot settle
# --------------------------------------------------------------------------------------


def test_only_a_clientkey_unique_violation_is_read_as_a_replay():
    """Both halves have to hold, and the second is the one that matters.

    Shaped like ``artisans._violated_identity_field``: Prisma raises a generic error whose TEXT names
    the constraint. A test for "unique" alone would read a clash on ``Craft.name`` — or on a column
    added next year — as somebody's replayed create and answer 201 with an unrelated row.
    """
    assert records_service.is_client_key_violation(_UNIQUE_VIOLATION)
    assert not records_service.is_client_key_violation(
        RuntimeError("Unique constraint failed on the fields: (`name`)")
    )
    assert not records_service.is_client_key_violation(RuntimeError("clientKey was not saved"))
    assert not records_service.is_client_key_violation(RuntimeError("connection reset"))


async def test_a_lost_race_answers_with_the_winners_row():
    # Two passes of one queue in flight together — two browser tabs, a sync that fired twice, a
    # restored queue drained beside the original. Each found no row, each planned an INSERT, and only
    # the index can settle it. The loser's answer is the winner's row, not a 500.
    winner = _row("p-1", client_key="k-1")
    table = _Table([winner])
    answered = await records_service.client_key_replay_after_violation(
        table, "k-1", _UNIQUE_VIOLATION, user_id="u-caller"
    )
    assert answered is winner


async def test_any_other_failure_of_the_create_is_re_raised_by_the_caller():
    # ``None`` means "this was not a clientKey collision, so it is not mine to answer". Every call
    # site turns that into a bare ``raise``. Swallowing it here would report a create that FAILED as
    # one that succeeded — the worst answer this route could give.
    table = _Table([_row("p-1", client_key="k-1")])
    assert (
        await records_service.client_key_replay_after_violation(
            table, "k-1", RuntimeError("could not connect"), user_id="u-caller"
        )
        is None
    )
    # And an entry that sent no key can never take this branch at all.
    assert (
        await records_service.client_key_replay_after_violation(
            table, None, _UNIQUE_VIOLATION, user_id="u-caller"
        )
        is None
    )


# --------------------------------------------------------------------------------------
# Half two: the four routes, and the ORDER that makes the replay safe
# --------------------------------------------------------------------------------------


#: A location every create schema will accept. ``require_location`` demands a coordinate AND a stated
#: state and district — the rule written up on the ``Location`` model after fifteen artisans
#: documented in four states were all found carrying Kharagpur coordinates. A payload with only a
#: coordinate is a 422 before any of this module's machinery is reached.
_LOCATION = {
    "latitude": 23.24,
    "longitude": 69.66,
    "state": "Gujarat",
    "district": "Kachchh",
}


def _product_payload(**extra: Any) -> ProductCreate:
    return ProductCreate(
        craftName="Pottery",
        place="Bhuj",
        artisanName="Giriraj Prasad",
        productName="Bowl",
        location=_LOCATION,
        **extra,
    )


def _tool_payload(**extra: Any) -> ToolCreate:
    return ToolCreate(
        craftName="Pottery",
        place="Bhuj",
        artisanName="Giriraj Prasad",
        toolkitName="Chisel",
        location=_LOCATION,
        **extra,
    )


def _workshop_payload(**extra: Any) -> WorkshopCreate:
    return WorkshopCreate(
        title="Bhuj visit",
        place="Bhuj",
        date=datetime(2026, 9, 3, tzinfo=UTC),
        location=_LOCATION,
        **extra,
    )


def _process_payload(**extra: Any) -> ProcessCreate:
    return ProcessCreate(name="Throwing", productId="p-1", **extra)


async def test_the_product_route_answers_a_replay_without_reaching_a_single_gate(monkeypatch):
    """THE ORDERING TEST, AND IT IS THE ONE THAT JUSTIFIES THE PLACEMENT.

    The replay branch is the first statement in the function, above ``attach_location`` and above
    both workshop gates. That is not tidiness:

      * ``attach_location`` WRITES — it mints a ``Location`` row from the payload. One per replay,
        referenced by nothing, is a leak that only ever grows.
      * the gates decide whether this caller MAY create the row, and on a replay the row already
        exists — so re-asking can only turn a create that succeeded into a 403 for a designer whose
        workshop grant lapsed while their phone was out of coverage. That is precisely the population
        this whole mechanism serves.

    Both are asserted by making them explode: if the route ever reaches one, this test fails with the
    name of the thing it should not have called.
    """
    stored = _row("p-1", client_key="k-1")
    table = _Table([stored])
    monkeypatch.setattr(db, "productdocumentation", table)

    async def _must_not_run(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError("a replay reached a write gate")

    monkeypatch.setattr(product_routes, "attach_location", _must_not_run)
    monkeypatch.setattr(product_routes, "enforce_workshop_submission", _must_not_run)
    monkeypatch.setattr(product_routes, "assert_payload_workshop", _must_not_run)

    answered = await product_routes.create_product(
        _product_payload(clientKey="k-1"), current_user=CALLER
    )
    assert answered["id"] == "p-1"
    # Nothing was written. The row in the answer is the row that was already there.
    assert table.create_calls == []


async def test_the_second_answer_mirrors_the_first_and_carries_no_replay_flag(monkeypatch):
    """The caller must not be able to tell a replay from a first landing — that is the point.

    A client that COULD tell would have to decide what to do about it, and the only thing it would
    have learned is that its own queue is older than it thought: not a fact about the record, and not
    one a designer can act on. The one route in this API that does answer "replayed" is
    ``POST /design-ratings``, and it is a different shape on purpose — one route for create, amend
    and replay, where the flag is the only way a caller can learn which it got. Here there is nothing
    to learn.
    """
    table = _Table()
    monkeypatch.setattr(db, "productdocumentation", table)

    async def _passthrough(data: dict[str, Any]) -> dict[str, Any]:
        return data

    async def _noop(*args: Any, **kwargs: Any) -> Any:
        return None

    monkeypatch.setattr(product_routes, "attach_location", _passthrough)
    monkeypatch.setattr(product_routes, "enforce_workshop_submission", _noop)
    monkeypatch.setattr(product_routes, "assert_payload_workshop", _noop)

    first = await product_routes.create_product(
        _product_payload(clientKey="k-1"), current_user=CALLER
    )
    # The stored row now carries the key, so the SECOND send finds it.
    second = await product_routes.create_product(
        _product_payload(clientKey="k-1"), current_user=CALLER
    )
    assert len(table.create_calls) == 1, "one row, however many times the create was sent"
    assert second["id"] == first["id"]
    assert "replayed" not in second


async def test_an_absent_key_leaves_the_product_route_exactly_as_it_was(monkeypatch):
    """The compatibility claim, made against the route rather than against the helper.

    Every fielded 0.0.7 APK and every cached web bundle sends no key. Two sends of the same body must
    therefore still make two rows — the behaviour those clients have and rely on — and neither send
    may cost a lookup.
    """
    table = _Table()
    monkeypatch.setattr(db, "productdocumentation", table)

    async def _passthrough(data: dict[str, Any]) -> dict[str, Any]:
        return data

    async def _noop(*args: Any, **kwargs: Any) -> Any:
        return None

    monkeypatch.setattr(product_routes, "attach_location", _passthrough)
    monkeypatch.setattr(product_routes, "enforce_workshop_submission", _noop)
    monkeypatch.setattr(product_routes, "assert_payload_workshop", _noop)

    await product_routes.create_product(_product_payload(), current_user=CALLER)
    await product_routes.create_product(_product_payload(), current_user=CALLER)
    assert len(table.create_calls) == 2
    assert table.find_unique_calls == [], "no key, no probe"
    # And no column is written, so the row keeps NULL and stays exempt from the unique index.
    assert "clientKey" not in table.create_calls[0]["data"]


async def test_the_tool_route_answers_a_replay_with_the_stored_row(monkeypatch):
    stored = _row("t-1", client_key="k-2")
    table = _Table([stored])
    monkeypatch.setattr(db, "tooldocumentation", table)

    async def _must_not_run(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError("a replay reached a write gate")

    monkeypatch.setattr(tool_routes, "attach_location", _must_not_run)
    monkeypatch.setattr(tool_routes, "enforce_workshop_submission", _must_not_run)
    monkeypatch.setattr(tool_routes, "assert_payload_workshop", _must_not_run)

    answered = await tool_routes.create_tool(_tool_payload(clientKey="k-2"), current_user=CALLER)
    assert answered["id"] == "t-1"
    assert table.create_calls == []


async def test_the_workshop_route_answers_a_replay_and_writes_neither_roster(monkeypatch):
    """A replayed workshop used to duplicate TWO ROSTERS as well as the row.

    ``replace_workshop_artisans`` and ``replace_workshop_crafts`` run below the create, so a second
    landing produced a second workshop with a second full copy of "Artisans attending" and "Crafts
    covered". Answering from the stored row reaches neither.
    """
    stored = _row("w-1", client_key="k-3")
    table = _Table([stored])
    monkeypatch.setattr(db, "workshop", table)

    async def _must_not_run(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError("a replay reached a roster write")

    monkeypatch.setattr(workshop_routes, "attach_location", _must_not_run)
    monkeypatch.setattr(workshop_routes, "replace_workshop_artisans", _must_not_run)
    monkeypatch.setattr(workshop_routes, "replace_workshop_crafts", _must_not_run)

    async def _no_relations(rows: Any, relations: Any) -> None:
        return None

    monkeypatch.setattr(workshop_routes, "hydrate_relations", _no_relations)

    answered = await workshop_routes.create_workshop(
        _workshop_payload(clientKey="k-3", artisanIds=["a-1"], craftIds=["c-1"]),
        current_user=CALLER,
    )
    assert answered["id"] == "w-1"
    assert table.create_calls == []


async def test_the_process_route_answers_a_replay_and_writes_no_steps(monkeypatch):
    """The model where a replay hurt most, because it duplicated CHILDREN as well as itself.

    ``create_process`` writes its ``ProcessStep`` rows AFTER the row, so a second landing produced a
    second process carrying a second full copy of every step of a making sequence. The replay branch
    is above ``require_record`` and above ``_apply_steps``, so neither is reached — and the steps in
    the answer are the ones the FIRST landing made, which both outboxes need: a queued step
    photograph is addressed by index into the create response's ``steps[]``.
    """
    stored = _row("pr-1", client_key="k-4")
    stored.steps = [SimpleNamespace(id="st-1", sortOrder=0, name="Throwing", notes=None)]
    table = _Table([stored])
    monkeypatch.setattr(db, "process", table)

    async def _must_not_run(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError("a replay reached the step planner")

    monkeypatch.setattr(process_routes, "require_record", _must_not_run)
    monkeypatch.setattr(process_routes, "_apply_steps", _must_not_run)

    async def _no_relations(rows: Any, relations: Any) -> None:
        return None

    async def _hydrate(process: Any, viewer: Any) -> dict[str, Any]:
        return {"id": process.id, "steps": [{"id": step.id} for step in process.steps]}

    monkeypatch.setattr(process_routes, "hydrate_relations", _no_relations)
    monkeypatch.setattr(process_routes, "_hydrate", _hydrate)

    answered = await process_routes.create_process(
        _process_payload(clientKey="k-4", steps=[{"name": "Throwing"}]), current_user=CALLER
    )
    assert answered["id"] == "pr-1"
    assert answered["steps"] == [{"id": "st-1"}], "the FIRST landing's step ids, not a second set"
    assert table.create_calls == []


async def test_a_replay_of_someone_elses_key_is_refused_by_the_route_too(monkeypatch):
    # The helper's 403 reaching the wire. Asserted at the route because that is where a future edit
    # could accidentally swallow it into a create.
    table = _Table([_row("p-1", client_key="k-1", created_by="u-caller")])
    monkeypatch.setattr(db, "productdocumentation", table)
    with pytest.raises(HTTPException) as caught:
        await product_routes.create_product(
            _product_payload(clientKey="k-1"), current_user=STRANGER
        )
    assert caught.value.status_code == 403
    assert table.create_calls == []


async def test_a_key_that_loses_the_index_race_is_answered_not_five_hundred(monkeypatch):
    """The branch below the write, driven end to end through the route.

    The pre-read found nothing (the row appears only once the create has raised), so the route
    planned an INSERT and lost. ``artisans.create_artisan`` puts the identical belt-and-braces behind
    its own pre-check, for the reason ``_guard_identity_conflicts`` states: *"two researchers can
    submit the same artisan in the same instant and only the index can settle that race."*
    """
    winner = _row("p-1", client_key="k-1")

    class _RacingTable(_Table):
        async def create(self, **kwargs: Any) -> Any:
            self.create_calls.append(kwargs)
            # The winner commits between our probe and our insert.
            self.rows.append(winner)
            raise _UNIQUE_VIOLATION

    table = _RacingTable()
    monkeypatch.setattr(db, "productdocumentation", table)

    async def _passthrough(data: dict[str, Any]) -> dict[str, Any]:
        return data

    async def _noop(*args: Any, **kwargs: Any) -> Any:
        return None

    monkeypatch.setattr(product_routes, "attach_location", _passthrough)
    monkeypatch.setattr(product_routes, "enforce_workshop_submission", _noop)
    monkeypatch.setattr(product_routes, "assert_payload_workshop", _noop)

    answered = await product_routes.create_product(
        _product_payload(clientKey="k-1"), current_user=CALLER
    )
    assert answered["id"] == "p-1"
    assert len(table.create_calls) == 1


async def test_a_create_that_fails_for_any_other_reason_still_fails(monkeypatch):
    # The half that keeps the branch honest. A connection error must not be quietly reported as a
    # successful replay just because the request happened to carry a key.
    table = _Table(raises=RuntimeError("could not connect"))
    monkeypatch.setattr(db, "productdocumentation", table)

    async def _passthrough(data: dict[str, Any]) -> dict[str, Any]:
        return data

    async def _noop(*args: Any, **kwargs: Any) -> Any:
        return None

    monkeypatch.setattr(product_routes, "attach_location", _passthrough)
    monkeypatch.setattr(product_routes, "enforce_workshop_submission", _noop)
    monkeypatch.setattr(product_routes, "assert_payload_workshop", _noop)

    with pytest.raises(RuntimeError, match="could not connect"):
        await product_routes.create_product(
            _product_payload(clientKey="k-1"), current_user=CALLER
        )


# --------------------------------------------------------------------------------------
# The schemas, and the two models that must NOT have grown the field
# --------------------------------------------------------------------------------------


def test_the_four_create_schemas_accept_a_key_and_default_it_to_none():
    # Optional and defaulted. A REQUIRED key would 422 every client in the field; a server-minted
    # default would be different on every request and make every replay a fresh create while looking,
    # in the schema, exactly like a guard.
    for payload in (
        _product_payload(),
        _tool_payload(),
        _workshop_payload(),
        _process_payload(),
    ):
        assert payload.clientKey is None
    assert _product_payload(clientKey="k-1").clientKey == "k-1"


def test_the_update_schemas_refuse_a_key_outright():
    """A correction may not carry one, and the refusal is what both clients' comments rely on.

    ``APIModel`` is ``extra="forbid"``, so this is already true — the test states it so that a future
    edit adding ``clientKey`` to an update schema for symmetry is stopped here rather than in the
    field, where a key on a PATCH would be a silently duplicated idempotency contract on a route that
    does not create anything.
    """
    from app.schemas.records import ProductUpdate, WorkshopUpdate

    for model in (ProductUpdate, WorkshopUpdate):
        with pytest.raises(ValidationError, match="extra_forbidden"):
            model(clientKey="k-1")


def test_the_key_is_never_attributed_to_the_designer_as_a_field_they_filled_in():
    """It reaches ``data`` on all four create routes, so it has to be excluded from provenance.

    ``merge_field_provenance`` stamps ``{by, byName, at}`` against every key of ``data`` it does not
    skip, and the web client's "Field contributions" panel builds its rows from whatever keys that
    object holds. Without this entry every replayable record would carry a row attributing a v4 UUID
    to the designer, as though they had typed it — which is the reasoning ``MARKER_BODY_KEY`` already
    carries in the same set: *"it is not a field anybody filled in."*

    ``expectedUpdatedAt`` deliberately needs no entry: the six update routes pop it out of ``data``
    before anything reads the dict, so it never reaches this function at all.
    """
    assert records_service.CLIENT_KEY_FIELD in records_service.PROVENANCE_SKIP_FIELDS
    data: dict[str, Any] = {"productName": "Bowl", "clientKey": "k-1"}
    records_service.merge_field_provenance(data, CALLER, previous=None)
    stamped = data.get("extraMetadata")
    provenance = getattr(stamped, "data", stamped) or {}
    fields = (provenance or {}).get("fieldProvenance", {})
    assert "productName" in fields, "a field a person typed IS attributed"
    assert "clientKey" not in fields, "and a send's bookkeeping is not"


def test_the_already_protected_models_did_not_grow_a_second_mechanism():
    """``Artisan`` and ``Craft`` are guarded by an identity key, and must stay guarded by only that.

    Two guards over one question can disagree about what a duplicate is — and both outboxes' 409 arms
    are written on the assumption that a clash is somebody ELSE'S record, which a collision with the
    caller's own earlier create would falsify. Both clients' mint lists mirror this refusal
    (``CLIENT_KEY_ENDPOINTS`` in ``frontend/lib/offline.ts``, ``outboxMintsClientKey`` on Android),
    and a field added here without them would be a key nothing sends.
    """
    from app.schemas.records import ArtisanCreate, CraftCreate

    assert "clientKey" not in ArtisanCreate.model_fields
    assert "clientKey" not in CraftCreate.model_fields
