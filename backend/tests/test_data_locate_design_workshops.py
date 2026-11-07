from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import data_browser, search
from app.api.routes.data_browser import Scope
from app.services.data_explorer import counts, walk


class Row(SimpleNamespace):
    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


def _match(row: Any, where: dict[str, Any]) -> bool:
    for key, wanted in (where or {}).items():
        if key == "AND":
            if not all(_match(row, part) for part in wanted):
                return False
            continue
        value = getattr(row, key, None)
        if isinstance(wanted, dict) and "in" in wanted:
            if value not in wanted["in"]:
                return False
        elif value != wanted:
            return False
    return True


class Table:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.calls = 0

    async def find_first(self, where: dict[str, Any] | None = None, **_: Any) -> Any:
        self.calls += 1
        return next((r for r in self.rows if _match(r, where or {})), None)

    async def find_unique(self, where: dict[str, Any], **_: Any) -> Any:
        return await self.find_first(where=where)

    async def find_many(self, where: dict[str, Any] | None = None, **_: Any) -> list[Any]:
        self.calls += 1
        return [r for r in self.rows if _match(r, where or {})]


def _db(monkeypatch: pytest.MonkeyPatch, **tables: list[Any]) -> SimpleNamespace:
    names = ("workshop", "workshopcraft", "workshopartisan", "designworkshop", "artisan", "craft")
    fake = SimpleNamespace(**{name: Table(tables.get(name, [])) for name in names})
    monkeypatch.setattr(data_browser, "db", fake)
    return fake


DW = Scope(records={}, media={}, design_workshops=True)
NO_DW = Scope(records={}, media={}, design_workshops=False)


def test_design_workshops_are_the_default_and_empty_legacy_trees_are_hidden():
    shown = data_browser._taxonomies_for(DW, legacy=False)
    assert [t["id"] for t in shown] == ["by-design-workshop", "by-type"]
    assert [t["id"] for t in shown if t["default"]] == ["by-design-workshop"]


def test_legacy_trees_stay_when_they_hold_workshops_but_design_workshops_lead():
    shown = data_browser._taxonomies_for(DW, legacy=True)
    assert shown[0]["id"] == "by-design-workshop" and shown[0]["default"]
    assert {"by-workshop", "by-uploader", "by-type"} <= {t["id"] for t in shown}
    assert sum(t["default"] for t in shown) == 1


def test_without_design_workshops_the_workshop_tree_stays_the_default():
    shown = data_browser._taxonomies_for(NO_DW, legacy=True)
    assert [t["id"] for t in shown] == ["by-workshop", "by-uploader", "by-type"]
    assert shown[0]["default"]
    only_types = data_browser._taxonomies_for(NO_DW, legacy=False)
    assert [(t["id"], t["default"]) for t in only_types] == [("by-type", True)]


def test_the_static_taxonomy_list_is_never_mutated():
    before = [dict(t) for t in data_browser.TAXONOMIES]
    data_browser._taxonomies_for(DW, legacy=False)
    assert before == data_browser.TAXONOMIES


def test_visible_taxonomies_ask_whether_any_legacy_workshop_exists(monkeypatch):
    _db(monkeypatch)
    ids = [t["id"] for t in asyncio.run(data_browser._visible_taxonomies(DW))]
    assert "by-workshop" not in ids and "by-uploader" not in ids
    _db(monkeypatch, workshop=[Row(id="w1")])
    ids = [t["id"] for t in asyncio.run(data_browser._visible_taxonomies(DW))]
    assert "by-workshop" in ids


def test_a_craft_with_no_legacy_workshop_is_located_through_its_design_workshop(monkeypatch):
    _db(monkeypatch, designworkshop=[Row(id="dw1", craftId="c1", deletedAt=None)])
    assert asyncio.run(data_browser._locate_path("craft", "c1", DW)) == "by-design-workshop/dw1"
    assert asyncio.run(data_browser._locate_path("craft", "c1", NO_DW)) is None


def test_a_craft_in_a_legacy_workshop_keeps_its_legacy_folder(monkeypatch):
    _db(
        monkeypatch,
        workshopcraft=[Row(craftId="c1", workshopId="w1")],
        workshop=[Row(id="w1")],
        designworkshop=[Row(id="dw1", craftId="c1", deletedAt=None)],
    )
    assert asyncio.run(data_browser._locate_path("craft", "c1", DW)) == "by-workshop/w1/crafts/c1"


def test_an_artisan_filed_under_a_design_workshop_is_located_there(monkeypatch):
    _db(
        monkeypatch,
        artisan=[Row(id="a1", designWorkshopId="dw1")],
        designworkshop=[Row(id="dw1", deletedAt=None)],
    )
    assert asyncio.run(data_browser._locate_path("artisan", "a1", DW)) == "by-design-workshop/dw1"


def test_locate_says_when_a_record_cannot_be_located_and_offers_its_page(monkeypatch):
    _db(monkeypatch, craft=[Row(id="c9")])
    monkeypatch.setattr(data_browser, "_scope_for", lambda _user: _done(DW))
    answer = asyncio.run(data_browser.data_locate(type="craft", id="c9", current_user=Row(id="u")))
    assert answer == {"path": None, "locatable": False, "record": {"kind": "craft", "id": "c9"}}
    gone = asyncio.run(data_browser.data_locate(type="craft", id="nope", current_user=Row(id="u")))
    assert gone == {"path": None, "locatable": False, "record": None}


def test_locate_flags_a_located_record(monkeypatch):
    _db(monkeypatch, designworkshop=[Row(id="dw1", craftId="c1", deletedAt=None)])
    monkeypatch.setattr(data_browser, "_scope_for", lambda _user: _done(DW))
    answer = asyncio.run(data_browser.data_locate(type="craft", id="c1", current_user=Row(id="u")))
    assert answer == {"path": "by-design-workshop/dw1", "locatable": True, "record": None}


async def _done(value: Any) -> Any:
    return value


def test_locatable_ids_batch_crafts_and_artisans(monkeypatch):
    _db(
        monkeypatch,
        workshopcraft=[Row(craftId="c1", workshopId="w1")],
        workshop=[Row(id="w1")],
        designworkshop=[Row(id="dw1", craftId="c2", deletedAt=None)],
        artisan=[Row(id="a1", designWorkshopId="dw1"), Row(id="a2")],
    )
    assert asyncio.run(data_browser.locatable_ids("craft", ["c1", "c2", "c3"], DW)) == {"c1", "c2"}
    assert asyncio.run(data_browser.locatable_ids("craft", ["c1", "c2", "c3"], NO_DW)) == {"c1"}
    assert asyncio.run(data_browser.locatable_ids("artisan", ["a1", "a2"], DW)) == {"a1"}


def test_search_marks_which_crafts_and_artisans_open_in_data(monkeypatch):
    monkeypatch.setattr(search, "can_download_dataset", lambda _user: True)
    monkeypatch.setattr(data_browser, "scope_for_user", lambda _user: _done(DW))

    async def found(kind: str, ids: list[str], _scope: Any) -> set[str]:
        return {"c1"} if kind == "craft" else set()

    monkeypatch.setattr(data_browser, "locatable_ids", found)
    groups = [
        {"type": "crafts", "items": [{"id": "c1", "local": True}, {"id": "c2", "local": True}, {"id": "r", "local": False}]},
        {"type": "artisans", "items": [{"id": "a1", "local": True}]},
        {"type": "media", "items": [{"id": "m1", "local": True}]},
    ]
    asyncio.run(search.mark_locatable(Row(id="u"), groups))
    assert [i.get("locatable") for i in groups[0]["items"]] == [True, False, None]
    assert groups[1]["items"][0]["locatable"] is False
    assert "locatable" not in groups[2]["items"][0]


def test_search_never_offers_open_in_data_without_dataset_access(monkeypatch):
    monkeypatch.setattr(search, "can_download_dataset", lambda _user: False)
    groups = [{"type": "crafts", "items": [{"id": "c1", "local": True}]}]
    asyncio.run(search.mark_locatable(Row(id="u"), groups))
    assert groups[0]["items"][0]["locatable"] is False


def test_a_design_workshop_folder_names_its_record():
    record = Row(id="dw1", title="Bagru block printing")
    info = data_browser._dw_info(record, 3, 12)
    assert info["record"] == {"kind": "designWorkshop", "id": "dw1"}
    assert info["title"] == "Bagru block printing"


def test_design_workshop_badges_count_what_the_header_counts(monkeypatch):
    walked: list[str] = []

    async def manifest(_list_level: Any, _scope: Any, paths: list[tuple[str, str]], include: Any, **kw: Any) -> Any:
        walked.append(paths[0][0])
        files = [{"content": "table"}, {"content": "details"}, {"mediaId": "m", "mediaType": "IMAGE", "sizeBytes": 40}]
        return files, False

    monkeypatch.setattr(walk, "manifest_files", manifest)
    entries = [
        {"kind": "folder", "name": "A", "path": "by-design-workshop/dw1"},
        {"kind": "folder", "name": "B", "path": "by-design-workshop/dw2"},
    ]
    asyncio.run(counts.fill("by-design-workshop", entries, DW))
    assert walked == ["by-design-workshop/dw1", "by-design-workshop/dw2"]
    assert [e["count"] for e in entries] == [3, 3]
    assert entries[0]["bytes"] == len(b"table") + len(b"details") + 40
