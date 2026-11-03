from __future__ import annotations

import inspect
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import design_workshops as routes
from app.services import design_workshop_viewers as viewers, design_workshops as service

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


LEAD = SimpleNamespace(id="designer-1", role="DESIGNER")
CO = SimpleNamespace(id="designer-2", role="DESIGNER")
STRANGER = SimpleNamespace(id="designer-9", role="DESIGNER")
ADMIN = SimpleNamespace(id="admin-1", role="ADMIN")
MASTER = SimpleNamespace(id="master-1", role="MASTER_ADMIN")

INSPECTOR = SimpleNamespace(id="inspector-1", role="INSPECTOR")


class _ListTripwire:
    def __init__(self) -> None:
        self.wheres: list[dict[str, Any]] = []

    async def count(self, where: dict[str, Any]) -> int:
        self.wheres.append(where)
        return 0

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.wheres.append(kwargs.get("where"))
        return []


async def _where_for(monkeypatch: Any, user: Any, **params: Any) -> dict[str, Any]:
    table = _ListTripwire()
    monkeypatch.setattr(routes, "db", SimpleNamespace(designworkshop=table))
    await routes.list_design_workshops(current_user=user, page=1, pageSize=20, **params)
    assert len(table.wheres) == 2, (
        f"the list route issued {len(table.wheres)} queries; the count and the page must be the "
        "only two, and both must carry the scope"
    )
    assert table.wheres[0] == table.wheres[1], (
        "the count and the page were filtered differently, so the total counts workshops the page "
        "may not show"
    )
    return table.wheres[0]


def _visibility_clauses(where: dict[str, Any]) -> list[Any]:
    return [term for term in where.get("AND", []) if "OR" in term]



async def test_a_designer_gets_the_visibility_clause_in_the_query(monkeypatch):
    where = await _where_for(monkeypatch, LEAD)
    assert _visibility_clauses(where) == [viewers.visible_to_clause(LEAD.id)]


@pytest.mark.parametrize("user", [ADMIN, MASTER], ids=["admin", "master_admin"])
async def test_an_admin_and_the_master_admin_get_no_clause_at_all(monkeypatch, user):
    where = await _where_for(monkeypatch, user)
    assert _visibility_clauses(where) == []


async def test_a_tier_that_merely_OUTRANKS_a_designer_is_still_scoped(monkeypatch):
    where = await _where_for(monkeypatch, INSPECTOR)
    assert _visibility_clauses(where) == [viewers.visible_to_clause(INSPECTOR.id)]


async def test_the_scope_and_the_search_box_do_not_collide(monkeypatch):
    where = await _where_for(monkeypatch, LEAD, search="Kalamkari")

    assert _visibility_clauses(where) == [viewers.visible_to_clause(LEAD.id)], (
        "typing in the search box removed the visibility scope, so the list now answers with every "
        "workshop matching the term regardless of who may see it"
    )
    titles = [term for term in where.get("OR", []) if "title" in term]
    assert titles, "the visibility scope overwrote the search filter, so the search stopped narrowing"


async def test_mineOnly_narrows_to_the_callers_OWN_and_does_not_fall_back_to_everything(monkeypatch):
    where = await _where_for(monkeypatch, LEAD, mineOnly=True)
    assert where["createdById"] == LEAD.id
    assert _visibility_clauses(where) == []


async def test_the_trash_is_refused_to_a_designer_before_any_query_runs(monkeypatch):
    table = _ListTripwire()
    monkeypatch.setattr(routes, "db", SimpleNamespace(designworkshop=table))
    for flag in ("includeDeleted", "deletedOnly"):
        with pytest.raises(HTTPException) as raised:
            await routes.list_design_workshops(
                current_user=LEAD, page=1, pageSize=20, **{flag: True}
            )
        assert raised.value.status_code == 403
    assert table.wheres == [], "the trash flags were refused only after the rows had been read"



def _record(**overrides: Any) -> Any:
    return SimpleNamespace(**{"id": "ws-1", "createdById": ADMIN.id, "deletedAt": None, **overrides})


def _load_against(monkeypatch: Any, *, record: Any, grantees: set[str]) -> None:
    async def _find_unique(where: dict[str, Any]) -> Any:
        return record if record is not None and where["id"] == record.id else None

    async def _has_grant(workshop_id: str, user_id: str) -> bool:
        return user_id in grantees

    monkeypatch.setattr(
        service, "db", SimpleNamespace(designworkshop=SimpleNamespace(find_unique=_find_unique))
    )
    monkeypatch.setattr(service, "has_viewer_grant", _has_grant)


async def test_every_named_designer_can_open_it_and_the_unnamed_one_cannot(monkeypatch):
    _load_against(monkeypatch, record=_record(), grantees={LEAD.id, CO.id})

    for user in (LEAD, CO):
        assert (await service.load_workshop_or_404("ws-1", user)).id == "ws-1"

    with pytest.raises(HTTPException) as raised:
        await service.load_workshop_or_404("ws-1", STRANGER)
    assert raised.value.status_code == 404


@pytest.mark.parametrize("user", [ADMIN, MASTER], ids=["admin", "master_admin"])
async def test_an_admin_needs_neither_a_grant_nor_to_have_created_it(monkeypatch, user):
    _load_against(monkeypatch, record=_record(createdById="somebody-else"), grantees=set())
    assert (await service.load_workshop_or_404("ws-1", user)).id == "ws-1"


async def test_the_refusal_cannot_be_told_apart_from_a_workshop_that_does_not_exist(monkeypatch):
    _load_against(monkeypatch, record=_record(), grantees={LEAD.id})

    with pytest.raises(HTTPException) as turned_away:
        await service.load_workshop_or_404("ws-1", STRANGER)
    with pytest.raises(HTTPException) as never_existed:
        await service.load_workshop_or_404("no-such-workshop", STRANGER)

    assert (turned_away.value.status_code, turned_away.value.detail) == (
        never_existed.value.status_code,
        never_existed.value.detail,
    )
    assert turned_away.value.detail == "Record not found"


async def test_a_designer_removed_from_the_workshop_is_refused_again_immediately(monkeypatch):
    grantees = {LEAD.id}
    _load_against(monkeypatch, record=_record(), grantees=grantees)
    assert (await service.load_workshop_or_404("ws-1", LEAD)).id == "ws-1"

    grantees.discard(LEAD.id)
    with pytest.raises(HTTPException) as raised:
        await service.load_workshop_or_404("ws-1", LEAD)
    assert raised.value.status_code == 404
    assert raised.value.detail == "Record not found"



def test_the_create_route_grants_through_the_viewer_table_and_invents_no_second_one():
    source = inspect.getsource(routes.create_design_workshop)
    assert "attach_the_named_designers(" in source
    assert "designworkshopdesigner" not in source.lower(), (
        "the create route reached for a second table to record who a workshop is for; access is "
        "decided by DesignWorkshopViewer and by nothing else"
    )


def test_the_list_and_the_read_consult_the_same_one_table():
    assert "visible_to_clause" in inspect.getsource(routes.list_design_workshops)
    assert "has_viewer_grant" in inspect.getsource(service.load_workshop_or_404)
    assert viewers.visible_to_clause("u") == {
        "OR": [{"createdById": "u"}, {"viewers": {"some": {"userId": "u"}}}]
    }
