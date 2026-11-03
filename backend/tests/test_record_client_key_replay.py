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


async def _nothing_found(*_args: Any, **_kwargs: Any) -> None:
    return None


async def _no_rows(*_args: Any, **_kwargs: Any) -> list[Any]:
    return []


_NO_CRAFTS = SimpleNamespace(find_unique=_nothing_found, find_first=_nothing_found, find_many=_no_rows)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


CALLER = SimpleNamespace(
    id="u-caller", name="R. Menon", email="r@example.org", role="DESIGNER"
)

STRANGER = SimpleNamespace(
    id="u-stranger", name="Someone Else", email="s@example.org", role="DESIGNER"
)


class _Table:
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


_UNIQUE_VIOLATION = RuntimeError(
    'Unique constraint failed on the fields: (`clientKey`)'
)


def _row(model_id: str, *, client_key: str | None, created_by: str = "u-caller") -> Any:
    return SimpleNamespace(
        id=model_id,
        clientKey=client_key,
        createdById=created_by,
        createdAt=datetime(2026, 9, 3, 10, 0, tzinfo=UTC),
        updatedAt=datetime(2026, 9, 3, 10, 0, tzinfo=UTC),
        status="PENDING",
        extraMetadata=None,
    )



async def test_an_absent_key_costs_not_even_a_read():
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
    table = _Table([_row("p-1", client_key="k-1")])
    await records_service.client_key_replay(
        table, "k-1", user_id="u-caller", include={"craft": True}
    )
    assert table.find_unique_calls == [
        {"where": {"clientKey": "k-1"}, "include": {"craft": True}}
    ]


async def test_another_accounts_key_is_a_403_and_never_their_record():
    table = _Table([_row("p-1", client_key="k-1", created_by="u-caller")])
    with pytest.raises(HTTPException) as caught:
        await records_service.client_key_replay(table, "k-1", user_id=STRANGER.id)
    assert caught.value.status_code == 403
    assert "another account" in str(caught.value.detail)



def test_only_a_clientkey_unique_violation_is_read_as_a_replay():
    assert records_service.is_client_key_violation(_UNIQUE_VIOLATION)
    assert not records_service.is_client_key_violation(
        RuntimeError("Unique constraint failed on the fields: (`name`)")
    )
    assert not records_service.is_client_key_violation(RuntimeError("clientKey was not saved"))
    assert not records_service.is_client_key_violation(RuntimeError("connection reset"))


async def test_a_lost_race_answers_with_the_winners_row():
    winner = _row("p-1", client_key="k-1")
    table = _Table([winner])
    answered = await records_service.client_key_replay_after_violation(
        table, "k-1", _UNIQUE_VIOLATION, user_id="u-caller"
    )
    assert answered is winner


async def test_any_other_failure_of_the_create_is_re_raised_by_the_caller():
    table = _Table([_row("p-1", client_key="k-1")])
    assert (
        await records_service.client_key_replay_after_violation(
            table, "k-1", RuntimeError("could not connect"), user_id="u-caller"
        )
        is None
    )
    assert (
        await records_service.client_key_replay_after_violation(
            table, None, _UNIQUE_VIOLATION, user_id="u-caller"
        )
        is None
    )



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
    stored = _row("p-1", client_key="k-1")
    table = _Table([stored])
    monkeypatch.setattr(db, "productdocumentation", table)
    monkeypatch.setattr(db, "craft", _NO_CRAFTS)
    monkeypatch.setattr(db, "craftspelling", _NO_CRAFTS)

    async def _must_not_run(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError("a replay reached a write gate")

    monkeypatch.setattr(product_routes, "attach_location", _must_not_run)
    monkeypatch.setattr(product_routes, "enforce_workshop_submission", _must_not_run)
    monkeypatch.setattr(product_routes, "assert_payload_workshop", _must_not_run)

    answered = await product_routes.create_product(
        _product_payload(clientKey="k-1"), current_user=CALLER
    )
    assert answered["id"] == "p-1"
    assert table.create_calls == []


async def test_the_second_answer_mirrors_the_first_and_carries_no_replay_flag(monkeypatch):
    table = _Table()
    monkeypatch.setattr(db, "productdocumentation", table)
    monkeypatch.setattr(db, "craft", _NO_CRAFTS)
    monkeypatch.setattr(db, "craftspelling", _NO_CRAFTS)

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
    second = await product_routes.create_product(
        _product_payload(clientKey="k-1"), current_user=CALLER
    )
    assert len(table.create_calls) == 1, "one row, however many times the create was sent"
    assert second["id"] == first["id"]
    assert "replayed" not in second


async def test_an_absent_key_leaves_the_product_route_exactly_as_it_was(monkeypatch):
    table = _Table()
    monkeypatch.setattr(db, "productdocumentation", table)
    monkeypatch.setattr(db, "craft", _NO_CRAFTS)
    monkeypatch.setattr(db, "craftspelling", _NO_CRAFTS)

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
    table = _Table([_row("p-1", client_key="k-1", created_by="u-caller")])
    monkeypatch.setattr(db, "productdocumentation", table)
    monkeypatch.setattr(db, "craft", _NO_CRAFTS)
    monkeypatch.setattr(db, "craftspelling", _NO_CRAFTS)
    with pytest.raises(HTTPException) as caught:
        await product_routes.create_product(
            _product_payload(clientKey="k-1"), current_user=STRANGER
        )
    assert caught.value.status_code == 403
    assert table.create_calls == []


async def test_a_key_that_loses_the_index_race_is_answered_not_five_hundred(monkeypatch):
    winner = _row("p-1", client_key="k-1")

    class _RacingTable(_Table):
        async def create(self, **kwargs: Any) -> Any:
            self.create_calls.append(kwargs)
            self.rows.append(winner)
            raise _UNIQUE_VIOLATION

    table = _RacingTable()
    monkeypatch.setattr(db, "productdocumentation", table)
    monkeypatch.setattr(db, "craft", _NO_CRAFTS)
    monkeypatch.setattr(db, "craftspelling", _NO_CRAFTS)

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
    table = _Table(raises=RuntimeError("could not connect"))
    monkeypatch.setattr(db, "productdocumentation", table)
    monkeypatch.setattr(db, "craft", _NO_CRAFTS)
    monkeypatch.setattr(db, "craftspelling", _NO_CRAFTS)

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



def test_the_four_create_schemas_accept_a_key_and_default_it_to_none():
    for payload in (
        _product_payload(),
        _tool_payload(),
        _workshop_payload(),
        _process_payload(),
    ):
        assert payload.clientKey is None
    assert _product_payload(clientKey="k-1").clientKey == "k-1"


def test_the_update_schemas_refuse_a_key_outright():
    from app.schemas.records import ProductUpdate, WorkshopUpdate

    for model in (ProductUpdate, WorkshopUpdate):
        with pytest.raises(ValidationError, match="extra_forbidden"):
            model(clientKey="k-1")


def test_the_key_is_never_attributed_to_the_designer_as_a_field_they_filled_in():
    assert records_service.CLIENT_KEY_FIELD in records_service.PROVENANCE_SKIP_FIELDS
    data: dict[str, Any] = {"productName": "Bowl", "clientKey": "k-1"}
    records_service.merge_field_provenance(data, CALLER, previous=None)
    stamped = data.get("extraMetadata")
    provenance = getattr(stamped, "data", stamped) or {}
    fields = (provenance or {}).get("fieldProvenance", {})
    assert "productName" in fields, "a field a person typed IS attributed"
    assert "clientKey" not in fields, "and a send's bookkeeping is not"


def test_the_already_protected_models_did_not_grow_a_second_mechanism():
    from app.schemas.records import ArtisanCreate, CraftCreate

    assert "clientKey" not in ArtisanCreate.model_fields
    assert "clientKey" not in CraftCreate.model_fields
