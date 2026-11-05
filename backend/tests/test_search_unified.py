from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
import pytest_asyncio
from conftest import needs_db
from fastapi import HTTPException
from search_support import index_world, new_stamp, seed, user_ns

from app.api.routes import search as routes
from app.core.db import db
from app.services import cross_portal, search_indexer, search_query

pytestmark = [needs_db, pytest.mark.asyncio(loop_scope="module")]


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def world():
    await db.connect()
    try:
        w = await seed(new_stamp())
        await index_world(w)
        yield w
    finally:
        await db.disconnect()


def _settings(**over: Any) -> SimpleNamespace:
    base = {
        "unified_search_enabled": True,
        "search_embeddings_enabled": False,
        "search_embedding_provider": "openai",
        "search_embedding_model": "text-embedding-3-small",
        "openai_api_key": None,
        "gemini_api_keys": [],
        "cross_portal_search_enabled": False,
        "cross_portal_token": None,
        "aadhaar_fingerprint_key": None,
        "field_repository_api_url": None,
    }
    base.update(over)
    return SimpleNamespace(**base)


@pytest.fixture
def settings(monkeypatch):
    s = _settings()
    monkeypatch.setattr(routes, "get_settings", lambda: s)
    return s


async def _search(user: Any, q: str, **kw: Any) -> dict[str, Any]:
    args = {
        "types": None,
        "scope": "all",
        "limit": 5,
        "type": None,
        "page": 1,
        "craftId": None,
        "place": None,
        "dateFrom": None,
        "dateTo": None,
        "workshopIds": None,
    }
    args.update(kw)
    return await routes.unified_search(current_user=user_ns(user), q=q, **args)


def _ids(result: dict[str, Any], kind: str) -> list[str]:
    group = next((g for g in result["groups"] if g["type"] == kind), None)
    return [i["id"] for i in group["items"]] if group else []


def _scope(w, types: list[str]) -> search_query.Scope:
    return search_query.Scope(types=types, may_read_dw=True, craft_ids=[w.craft.id])


async def test_an_exact_title_hit_ranks_first(world):
    result = await search_query.run("indigo stole", _scope(world, ["products"]), limit=5)
    ids = [i["id"] for i in result.items["products"]]
    assert ids[0] == world.product_title.id
    assert world.product_body.id in ids


async def test_a_typo_finds_the_word_through_trigram(world):
    result = await search_query.run("indgo", _scope(world, ["crafts", "artisans"]), limit=5)
    assert [i["id"] for i in result.items["crafts"]] == [world.craft.id]


async def test_a_devanagari_token_matches_through_simple(world):
    result = await search_query.run("नील", _scope(world, ["crafts"]), limit=5)
    assert [i["id"] for i in result.items.get("crafts", [])] == [world.craft.id]


async def test_an_english_stem_matches(world):
    result = await search_query.run("dyes", _scope(world, ["crafts"]), limit=5)
    assert world.craft.id in [i["id"] for i in result.items.get("crafts", [])]


async def test_snippets_are_plain_text_with_highlight_ranges(world):
    result = await search_query.run("indigo", _scope(world, ["products"]), limit=5)
    item = next(i for i in result.items["products"] if i["id"] == world.product_body.id)
    assert "<" not in item["snippet"]
    start, end = item["highlights"][0]
    assert item["snippet"][start:end].lower() == "indigo"


async def test_groups_come_in_vocabulary_order_with_totals(world, settings):
    result = await _search(world.professor, world.stamp)
    kinds = [g["type"] for g in result["groups"]]
    assert kinds == [t for t in cross_portal.SEARCH_TYPES if t in kinds]
    assert {"artisans", "crafts", "designWorkshops", "products"} <= set(kinds)
    products = next(g for g in result["groups"] if g["type"] == "products")
    assert products["total"] == products["localTotal"] == 3 and products["remoteTotal"] == 0
    assert all(i["local"] is True and i["portal"] == "designer" for i in products["items"])
    assert result["mode"] == "text"
    assert result["remote"]["status"] == "off"


async def test_limit_caps_each_group_and_type_pages(world, settings):
    first = await _search(world.professor, world.stamp, limit=2)
    products = next(g for g in first["groups"] if g["type"] == "products")
    assert len(products["items"]) == 2 and products["total"] == 3
    page1 = await _search(world.professor, world.stamp, type="products", limit=2, page=1)
    page2 = await _search(world.professor, world.stamp, type="products", limit=2, page=2)
    assert [g["type"] for g in page1["groups"]] == ["products"]
    seen = _ids(page1, "products") + _ids(page2, "products")
    assert len(seen) == 3 and len(set(seen)) == 3


ANDROID_ITEM_KEYS = {
    "portal", "local", "type", "id", "title", "subtitle", "snippet", "highlights", "place",
    "craftCodes", "recordDate", "href", "openUrl", "score",
}


async def test_the_all_view_answers_every_group_with_readable_items(world, settings):
    result = await _search(world.professor, world.stamp, scope="local")
    assert result["groups"], "the All view came back empty"
    assert result["page"] == 1 and result["limit"] == 5 and "type" not in result
    for group in result["groups"]:
        assert set(group) == {"type", "label", "total", "localTotal", "remoteTotal", "items"}
        assert group["items"], group["type"]
        for item in group["items"]:
            assert item["id"] and item["type"] == group["type"]
            optional = {"startMs", "mediaType", "alsoIn"}
            assert ANDROID_ITEM_KEYS <= set(item) <= ANDROID_ITEM_KEYS | optional


async def test_the_all_view_pages_every_group_to_its_last_record(world, settings):
    first = await _search(world.professor, world.stamp, limit=2, page=1)
    second = await _search(world.professor, world.stamp, limit=2, page=2)
    assert second["page"] == 2 and second["limit"] == 2
    totals = {g["type"]: g["total"] for g in first["groups"]}
    assert {g["type"]: g["total"] for g in second["groups"]} == totals
    for kind, total in totals.items():
        seen = _ids(first, kind) + _ids(second, kind)
        assert len(set(seen)) == len(seen) == min(total, 4), kind
    products = _ids(first, "products") + _ids(second, "products")
    assert len(products) == 3
    beyond = await _search(world.professor, world.stamp, limit=2, page=3)
    assert {g["type"]: g["total"] for g in beyond["groups"]} == totals
    assert _ids(beyond, "products") == []


async def test_filters_narrow_the_result(world, settings):
    by_craft = await _search(world.professor, world.stamp, craftId=world.other_craft.id)
    assert {g["type"] for g in by_craft["groups"]} == {"crafts"}
    by_workshop = await _search(world.professor, world.stamp, workshopIds=[world.workshop.id])
    assert _ids(by_workshop, "artisans") == [world.unlinked.id]
    future = await _search(
        world.professor, world.stamp, dateFrom=__import__("datetime").datetime(2999, 1, 1)
    )
    assert future["groups"] == []


async def test_the_query_is_cleaned_and_bounded(world, settings):
    cleaned = await _search(world.professor, f"\x00{world.stamp}\x00")
    assert cleaned["query"] == world.stamp
    with pytest.raises(HTTPException) as short:
        await _search(world.professor, "a")
    assert short.value.status_code == 422
    with pytest.raises(HTTPException) as unknown:
        await _search(world.professor, world.stamp, types=["nonsense"])
    assert unknown.value.status_code == 422


async def test_the_route_is_absent_while_the_flag_is_off(world, monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: _settings(unified_search_enabled=False))
    with pytest.raises(HTTPException) as off:
        await _search(world.professor, world.stamp)
    assert off.value.status_code == 404


async def test_a_researcher_is_refused_design_workshop_types(world, settings):
    result = await _search(
        world.researcher, world.stamp, types=["designWorkshops", "reports", "products"]
    )
    assert result["typesRefused"] == ["designWorkshops", "reports"]
    assert [g["type"] for g in result["groups"]] == ["products"]
    every = await _search(world.researcher, world.stamp)
    assert world.transcript_dw.id not in _ids(every, "transcripts")


async def test_a_designer_sees_only_the_workshops_they_may_open(world, settings):
    result = await _search(world.designer, world.stamp, types=["designWorkshops", "reports"])
    assert result["typesRefused"] == []
    assert _ids(result, "designWorkshops") == [world.dw_designer.id]
    assert _ids(result, "reports") == [world.report.id]


async def test_a_professor_sees_every_workshop(world, settings):
    result = await _search(world.professor, world.stamp, types=["designWorkshops"])
    assert set(_ids(result, "designWorkshops")) == {world.dw_designer.id, world.dw_professor.id}


def test_build_sql_applies_the_permission_predicate_in_every_list():
    sql, params = search_query.build_sql(
        "indigo", search_query.Scope(types=["artisans"]), limit=5, qvec="[0]"
    )
    assert sql.count("FROM base b") >= 4
    assert "d.\"audience\" = 'ALL'" in sql and "<=> " in sql
    assert params[0] == "indigo"


async def test_a_workshop_filter_finds_a_tool_through_its_linked_artisan(world, settings):
    token = new_stamp()
    other = await db.workshop.create(
        data={
            "title": f"Akola meet {token}",
            "date": datetime(2026, 9, 3, tzinfo=UTC),
            "place": "Akola",
            "createdById": world.professor.id,
        }
    )
    artisan = await db.artisan.create(
        data={
            "name": f"Kamla Devi {token}",
            "workshopId": other.id,
            "place": "Akola",
            "createdById": world.professor.id,
        }
    )
    tool = await db.tooldocumentation.create(
        data={
            "toolkitName": f"Dabu block {token}",
            "craftName": world.craft.name,
            "artisanName": artisan.name,
            "craftId": world.craft.id,
            "place": "Akola",
            "createdById": world.professor.id,
        }
    )
    await db.toolartisan.create(data={"toolId": tool.id, "artisanId": artisan.id})
    try:
        for kind, record_id in (("workshops", other.id), ("artisans", artisan.id), ("tools", tool.id)):
            await search_indexer.index_one(kind, record_id)
        there = await _search(world.professor, token, workshopIds=[other.id])
        assert _ids(there, "tools") == [tool.id]
        elsewhere = await _search(world.professor, token, workshopIds=[world.workshop.id])
        assert _ids(elsewhere, "tools") == []
    finally:
        await db.tooldocumentation.delete(where={"id": tool.id})
        await db.artisan.delete(where={"id": artisan.id})
        await db.workshop.delete(where={"id": other.id})
