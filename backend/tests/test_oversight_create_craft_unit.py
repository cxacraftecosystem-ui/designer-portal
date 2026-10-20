"""The officers' create door writes the craft the way the ordinary create door does.

``POST /design-workshop-oversight/workshops`` used to write ``craftName`` alone, so a workshop an
officer opened had no ``craftId`` and fell out of every craft filter and off the craft's own page.
It now runs the body through ``craft_master.craft_columns``, the same call
``POST /design-workshops`` makes: a ``craftId`` must name an ACTIVE craft and its name follows it;
a bare name is matched onto the list and kept as typed when nothing matches.

**NO DATABASE.** The opener and the craft reads are replaced by recorders; what is asserted is
which columns and which stage-1 values reach ``open_design_workshop``.
"""

import asyncio
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.api.routes import design_workshop_oversight as routes
from app.schemas.design_workshop_oversight import DesignWorkshopOversightCreateIn
from app.services import craft_master

IKAT = SimpleNamespace(id="craft-ikat", name="Sambalpuri Ikat", status="ACTIVE")
RETIRED = SimpleNamespace(id="craft-old", name="Old Craft", status="MERGED")


class _Crafts:
    async def find_unique(self, where):
        return {IKAT.id: IKAT, RETIRED.id: RETIRED}.get(where["id"])


@pytest.fixture
def opened(monkeypatch: pytest.MonkeyPatch):
    calls: dict = {}

    async def _open(*, actor, columns, designer_id, designer_ids, seeded):
        calls["columns"] = dict(columns)
        calls["seeded"] = dict(seeded)
        return SimpleNamespace(id="w1")

    async def _match(client, text):
        return IKAT if text.strip().lower() in {"ikat", "sambalpuri ikat"} else None

    monkeypatch.setattr(routes, "open_design_workshop", _open)
    monkeypatch.setattr(routes, "workshop_summary", lambda record: {"id": record.id})
    monkeypatch.setattr(routes, "db", SimpleNamespace(craft=_Crafts()))
    monkeypatch.setattr(craft_master, "match_craft_name", _match)
    return calls


def _create(**body):
    payload = DesignWorkshopOversightCreateIn(title="Bargarh Ikat 2026", **body)
    actor = SimpleNamespace(id="u-ministry", role="MINISTRY_ADMIN")
    return asyncio.run(routes.open_workshop_from_oversight(payload, current_user=actor))


def test_a_picked_craft_writes_its_id_and_the_lists_own_name(opened):
    _create(craftId=IKAT.id, craftName="whatever the client typed")
    assert opened["columns"]["craftId"] == IKAT.id
    assert opened["columns"]["craftName"] == IKAT.name
    assert opened["seeded"]["craftRef"] == IKAT.id
    assert opened["seeded"]["craftName"] == IKAT.name


def test_a_bare_name_is_matched_onto_the_list(opened):
    _create(craftName="ikat")
    assert opened["columns"]["craftId"] == IKAT.id
    assert opened["columns"]["craftName"] == IKAT.name
    assert opened["seeded"]["craftRef"] == IKAT.id


def test_a_name_the_list_does_not_hold_is_kept_as_typed_with_no_id(opened):
    _create(craftName="  Unlisted weave ")
    assert "craftId" not in opened["columns"]
    assert opened["columns"]["craftName"] == "Unlisted weave"
    assert "craftRef" not in opened["seeded"]
    assert opened["seeded"]["craftName"] == "Unlisted weave"


def test_no_craft_writes_neither_column(opened):
    _create()
    assert "craftId" not in opened["columns"] and "craftName" not in opened["columns"]
    assert "craftRef" not in opened["seeded"] and "craftName" not in opened["seeded"]


def test_a_craft_that_is_not_active_is_refused_before_anything_is_opened(opened):
    with pytest.raises(HTTPException) as refused:
        _create(craftId=RETIRED.id)
    assert refused.value.status_code == 422
    assert "columns" not in opened


def test_the_door_calls_the_same_craft_resolver_as_the_ordinary_create():
    import inspect

    from app.api.routes import design_workshops

    assert "craft_master.craft_columns" in inspect.getsource(routes.open_workshop_from_oversight)
    assert "craft_master.craft_columns" in inspect.getsource(design_workshops.create_design_workshop)
