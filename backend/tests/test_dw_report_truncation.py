from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import data_browser
from app.services import design_workshop_data as dwd

CAP = 60

SEED: dict[str, dict[str, int]] = {
    "dw_1": {"sketch": 25, "participant": 10},
    "dw_2": {"sketch": 20, "participant": 5},
    "dw_3": {"prototype": 15},
}

ADMIN = SimpleNamespace(
    id="user-1", role="ADMIN", canDownloadDataset=True, email="admin@example.test"
)


def _sheet_name(entity_key: str) -> str:
    stage, entity = dwd.entity_by_key(entity_key)
    return f"{stage.number:02d} {entity.title}"


def _workshops() -> list[Any]:
    return [
        SimpleNamespace(
            id=workshop_id,
            title=f"Workshop {workshop_id}",
            workshopCode=f"DW-{workshop_id}",
            createdAt=None,
        )
        for workshop_id in SEED
    ]


def _entries() -> list[Any]:
    rows: list[Any] = []
    for workshop_id, per_entity in SEED.items():
        ordinal = 0
        for entity_key, count in per_entity.items():
            for _ in range(count):
                rows.append(
                    SimpleNamespace(
                        id=f"{workshop_id}-{entity_key}-{ordinal}",
                        designWorkshopId=workshop_id,
                        entityKey=entity_key,
                        ordinal=ordinal,
                        data={},
                    )
                )
                ordinal += 1
    return rows


def _true_by_entity() -> dict[str, int]:
    totals: dict[str, int] = {}
    for per_entity in SEED.values():
        for entity_key, count in per_entity.items():
            totals[entity_key] = totals.get(entity_key, 0) + count
    return totals


def _true_by_workshop() -> dict[str, int]:
    return {workshop_id: sum(per.values()) for workshop_id, per in SEED.items()}


def _carried_by_entity(cap: int) -> dict[str, int]:
    carried: dict[str, int] = {}
    for entry in _entries()[:cap]:
        carried[entry.entityKey] = carried.get(entry.entityKey, 0) + 1
    return carried


class _Empty:
    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return []

    async def group_by(self, **_kwargs: Any) -> list[Any]:
        return []


class _Workshops:
    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return _workshops()


class _Entries:
    def __init__(self) -> None:
        self.wheres: list[Any] = []

    async def find_many(self, *, where: Any = None, take: int, **_kwargs: Any) -> list[Any]:
        self.wheres.append(where)
        return _entries()[:take]

    async def group_by(self, *, by: list[str], where: Any = None, **_kwargs: Any) -> list[Any]:
        self.wheres.append(where)
        column = by[0]
        totals: dict[str, int] = {}
        for entry in _entries():
            key = str(getattr(entry, column))
            totals[key] = totals.get(key, 0) + 1
        return [{column: key, "_count": {"_all": count}} for key, count in totals.items()]


def _install(monkeypatch: pytest.MonkeyPatch, *, cap: int = CAP) -> _Entries:
    assert cap > len(dwd.tables()) + 1, (
        "the cap under test must stay above the registry's own size, or the index page is clipped "
        "by its row count and this file stops testing the entries cap — raise CAP"
    )
    monkeypatch.setattr(data_browser, "REPORT_TAKE", cap)

    entries = _Entries()

    async def _scope(*_args: Any, **_kwargs: Any) -> Any:
        return data_browser.Scope(
            records={},
            media={},
            design_workshops=True,
            design_workshop_downloads=True,
        )

    monkeypatch.setattr(data_browser, "_scope_for", _scope)
    monkeypatch.setattr(
        data_browser,
        "db",
        SimpleNamespace(
            workshop=_Empty(),
            craft=_Empty(),
            artisan=_Empty(),
            productdocumentation=_Empty(),
            process=_Empty(),
            tooldocumentation=_Empty(),
            questionnaireinterview=_Empty(),
            mediafile=_Empty(),
            designworkshop=_Workshops(),
            dwstageentry=entries,
        ),
    )
    return entries


def _report(monkeypatch: pytest.MonkeyPatch, *, cap: int = CAP) -> tuple[list[Any], _Entries]:
    entries = _install(monkeypatch, cap=cap)
    response = asyncio.run(
        data_browser.data_report(path="", format="json", current_user=ADMIN)
    )
    return json.loads(response.body)["sheets"], entries


def _dw_sheets(sheets: list[Any]) -> list[Any]:
    return [sheet for sheet in sheets if sheet.get("group") == data_browser.DW_SHEET_GROUP]


def _by_name(sheets: list[Any], name: str) -> Any:
    return next(sheet for sheet in sheets if sheet["name"] == name)


def _index_rows(sheets: list[Any]) -> dict[str, int]:
    sheet = _by_name(sheets, "DW tables")
    key_at = sheet["columns"].index("Table key")
    rows_at = sheet["columns"].index("Rows")
    return {row[key_at]: row[rows_at] for row in sheet["rows"]}



def test_every_design_workshop_sheet_says_the_rows_were_cut(monkeypatch):
    sheets, _entries_delegate = _report(monkeypatch)
    block = _dw_sheets(sheets)
    assert len(block) == len(_true_by_entity()) + 2, [sheet["name"] for sheet in block]
    for sheet in block:
        assert sheet["truncated"] is True, sheet["name"]
        assert f"capped at {CAP}" in (sheet.get("truncatedNote") or ""), sheet["name"]


def test_the_note_is_in_the_file_and_not_only_on_the_screen(monkeypatch):
    sheets, _entries_delegate = _report(monkeypatch)
    for sheet in _dw_sheets(sheets):
        if sheet["name"] == "DW tables":
            continue
        assert sheet["rows"][-1][0] == sheet["truncatedNote"], sheet["name"]


def test_the_index_page_reports_true_row_counts_and_not_the_capped_ones(monkeypatch):
    sheets, _entries_delegate = _report(monkeypatch)
    rows = _index_rows(sheets)
    true_counts = _true_by_entity()
    carried = _carried_by_entity(CAP)

    for entity_key, expected in true_counts.items():
        assert rows[entity_key] == expected, entity_key
    assert carried != true_counts, "the fixture stopped truncating — the assertions above are void"
    assert carried.get("prototype", 0) == 0
    assert rows["prototype"] == true_counts["prototype"]


def test_a_table_whose_rows_all_fell_past_the_cap_still_gets_its_tab(monkeypatch):
    sheets, _entries_delegate = _report(monkeypatch)
    sheet = _by_name(sheets, _sheet_name("prototype"))
    assert sheet["truncated"] is True
    assert len(sheet["rows"]) == 1
    assert f"capped at {CAP}" in sheet["rows"][0][0]


def test_the_overview_counts_rows_the_workbook_could_not_carry(monkeypatch):
    sheets, _entries_delegate = _report(monkeypatch)
    sheet = _by_name(sheets, "Design workshop progress")
    id_at = sheet["columns"].index("Workshop id")
    stages_at = sheet["columns"].index("Stages answered")
    rows_at = sheet["columns"].index("Rows recorded")
    by_id = {row[id_at]: row for row in sheet["rows"] if row[id_at] in SEED}
    true_totals = _true_by_workshop()

    for workshop_id, expected in true_totals.items():
        assert by_id[workshop_id][rows_at] == str(expected), workshop_id

    assert "+" not in by_id["dw_1"][stages_at]
    assert "+" not in by_id["dw_2"][stages_at]
    assert by_id["dw_3"][stages_at] == f"0+ of {len(dwd.stages())}"
    assert by_id["dw_3"][rows_at] == str(true_totals["dw_3"])


def test_the_counts_are_taken_over_the_same_predicate_as_the_rows(monkeypatch):
    _sheets, entries = _report(monkeypatch)
    assert len(entries.wheres) == 3
    assert entries.wheres[0] == entries.wheres[1] == entries.wheres[2]
    assert entries.wheres[0]["deletedAt"] is None
    assert sorted(entries.wheres[0]["designWorkshopId"]["in"]) == sorted(SEED)



def test_an_untruncated_report_is_flagged_nowhere_and_counts_what_it_holds(monkeypatch):
    roomy = sum(_true_by_workshop().values()) + len(dwd.tables()) + 10
    sheets, _entries_delegate = _report(monkeypatch, cap=roomy)
    block = _dw_sheets(sheets)
    assert block, "the fixture produced no design-workshop sheets at all"
    for sheet in block:
        assert sheet["truncated"] is False, sheet["name"]
        assert "truncatedNote" not in sheet, sheet["name"]

    assert _index_rows(sheets)["participant"] == _true_by_entity()["participant"]
    overview = _by_name(sheets, "Design workshop progress")
    stages_at = overview["columns"].index("Stages answered")
    assert not any("+" in row[stages_at] for row in overview["rows"])


def test_sheets_built_from_a_hand_made_bundle_still_count_their_own_rows(monkeypatch):
    monkeypatch.setattr(data_browser, "REPORT_TAKE", CAP)
    data: dict[str, Any] = {key: [] for key in data_browser._REPORT_KEYS}
    data["designWorkshops"] = _workshops()[:1]
    data["dwEntries"] = [entry for entry in _entries() if entry.designWorkshopId == "dw_1"]

    sheets = data_browser._dw_sheets(data)
    rows = _index_rows(sheets)
    assert rows["sketch"] == SEED["dw_1"]["sketch"]
    assert rows["participant"] == SEED["dw_1"]["participant"]
    assert all(sheet["truncated"] is False for sheet in sheets)


def test_no_design_workshop_sheet_shares_a_name_with_a_record_sheet(monkeypatch):
    from app.services.datareport import tables

    sheets, _entries_delegate = _report(monkeypatch)
    legacy = [sheet["name"] for sheet in _dw_sheets(sheets)]
    records = {table.title for table in tables.tables_for("designer")}
    assert "Design workshop progress" in legacy
    assert "Design workshops" in records
    assert len(legacy) == len(set(legacy))
    assert not set(legacy) & records
