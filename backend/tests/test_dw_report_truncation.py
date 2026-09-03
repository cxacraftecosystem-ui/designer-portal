"""What ``/data/report`` says about the design-workshop rows it could NOT carry.

THE DEFECT, AND WHY IT IS THE WORST KIND. ``_dw_report_load`` reads ``DwStageEntry`` with
``take=REPORT_TAKE`` (5,000) across EVERY workshop in scope, and until 2026-09-03 not one thing in
the design-workshop block tested that cap. ``_dw_entity_sheet`` tested its own slice
(``len(rows) >= REPORT_TAKE``), which a 200-row table cut out of a 900-row one can never satisfy;
the "DW tables" index page — the page that exists to say "this table has N rows and did not fit" —
computed its Rows column with ``len()`` over the same truncated merge; and the overview's "Rows
recorded" counted the rows the workbook happened to be holding. So a workshop whose stage rows fell
past the cap appeared in the workbook as a workshop nobody had filled in, and every page that could
have said otherwise agreed with it. The repository's own measurement records 6,952 stage rows, so at
the root this is not a ceiling that might one day be reached.

The identical bug had already been found and fixed for the media sheets — see the comment above
``_transcript_sheet``, which states the rule this file tests: the truncation flag must test the list
the sheet was CUT FROM, never the rows that survived.

NO DATABASE, AND THE CAP IS MOVED RATHER THAN THE CORPUS. Seeding 5,000 stage rows to prove an
inequality would cost minutes and a Postgres; what is under test is WHICH LIST each number is
counted over, so ``REPORT_TAKE`` is lowered for the duration and the route is awaited with the
storage layer replaced — exactly the shape ``test_media_convert_bound`` uses on the other cap in
this module.
"""

from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import data_browser
from app.services import design_workshop_data as dwd

# THE CAP THE TESTS RUN AGAINST. Not as small as it could be: the "DW tables" index page carries one
# row per registry entity (44 today) and ``_sheet`` clips ANY sheet longer than ``REPORT_TAKE``, so a
# cap below the registry's size would truncate the very page whose honesty is under test and the
# failure would look like the defect rather than the fixture. Sixty leaves headroom; the guard below
# turns "the registry outgrew this number" into a legible failure rather than a mystifying one.
CAP = 60

# Three COLLECTION entities from three stages, and three workshops. The first two workshops fill the
# cap exactly (35 + 25 = 60) and the third falls entirely past it — which is the case the old code
# reported as "0 rows recorded, no rows found", and the case a research reader cannot tell from a
# workshop nobody touched. The third workshop is the only user of ``prototype``, so that entity is a
# table whose EVERY row was cut: the sharpest version of the defect, and the one the index page's
# "No rows found" verdict used to describe.
SEED: dict[str, dict[str, int]] = {
    "dw_1": {"sketch": 25, "participant": 10},
    "dw_2": {"sketch": 20, "participant": 5},
    "dw_3": {"prototype": 15},
}

ADMIN = SimpleNamespace(
    id="user-1", role="ADMIN", canDownloadDataset=True, email="admin@example.test"
)


def _sheet_name(entity_key: str) -> str:
    """The tab name ``_dw_entity_sheet`` builds, derived rather than typed out.

    A retitled entity must not fail this file: what it asserts is about counts, not captions.
    """
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
    """Every seeded stage row, in the order the loader's query asks for.

    ``order=[{designWorkshopId: asc}, {ordinal: asc}]`` — so the cap falls on whole workshops from
    the tail, which is what makes the third workshop's rows disappear together.
    """
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
    """What the OLD code would have printed: a count over the rows that survived the cap."""
    carried: dict[str, int] = {}
    for entry in _entries()[:cap]:
        carried[entry.entityKey] = carried.get(entry.entityKey, 0) + 1
    return carried


class _Empty:
    """A delegate for every table the root report reads and this fixture does not care about."""

    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return []


class _Workshops:
    async def find_many(self, **_kwargs: Any) -> list[Any]:
        return _workshops()


class _Entries:
    """``DwStageEntry``: a capped read and the two groupings beside it.

    The groupings answer over EVERY seeded row, which is the whole point — a count that obeyed
    ``take`` would be the same lie the sheets were telling, told one layer down.
    """

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
    """The root report as the View Data page receives it: ``format=json``, every sheet."""
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
    """``{table key: the Rows column}`` off the "DW tables" index page."""
    sheet = _by_name(sheets, "DW tables")
    key_at = sheet["columns"].index("Table key")
    rows_at = sheet["columns"].index("Rows")
    return {row[key_at]: row[rows_at] for row in sheet["rows"]}


# --------------------------------------------------------------------------------------
# The cap, and what every sheet says about it
# --------------------------------------------------------------------------------------


def test_every_design_workshop_sheet_says_the_rows_were_cut(monkeypatch):
    """THE DEFECT, on the flag. Not one of these sheets was flagged before 2026-09-03.

    Asserted over the whole block rather than sheet by sheet, because the failure was a whole block
    NOBODY had wired the cap into — so the test has to be the one a future sheet cannot slip past by
    being added after it.
    """
    sheets, _entries_delegate = _report(monkeypatch)
    block = _dw_sheets(sheets)
    # The overview, the index, and one tab per seeded entity — every one of which is built from a
    # list the cap cut, and not one of which said so.
    assert len(block) == len(_true_by_entity()) + 2, [sheet["name"] for sheet in block]
    for sheet in block:
        assert sheet["truncated"] is True, sheet["name"]
        assert f"capped at {CAP}" in (sheet.get("truncatedNote") or ""), sheet["name"]


def test_the_note_is_in_the_file_and_not_only_on_the_screen(monkeypatch):
    """A workbook outlives the page it was downloaded from.

    ``truncatedNote`` is the web viewer's banner; the trailing row is the only copy an .xlsx opened
    a year later carries, and the viewer's DEFAULT banner ("download the .xlsx for the rest") is
    actively wrong for a cut the .xlsx shares.
    """
    sheets, _entries_delegate = _report(monkeypatch)
    for sheet in _dw_sheets(sheets):
        if sheet["name"] == "DW tables":
            # The index page is 44 rows of registry and was never row-capped; its sentence is set
            # beside the sheet rather than inside it. See ``_dw_index_sheet``.
            continue
        assert sheet["rows"][-1][0] == sheet["truncatedNote"], sheet["name"]


def test_the_index_page_reports_true_row_counts_and_not_the_capped_ones(monkeypatch):
    """THE DEFECT, on the number. The anti-silence page was quoting the silence.

    The second assertion is the regression: the entity every one of whose rows fell past the cap
    read ``0`` — "No rows found" — which is precisely the sentence the index page exists to make
    impossible.
    """
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
    """The plan is drawn on the true counts, so the index cannot promise a sheet that is absent.

    The tab carries its headers and the cap sentence and no rows — which is "this table has rows and
    none of them fitted", said in the one place a reader looking for that table will go.
    """
    sheets, _entries_delegate = _report(monkeypatch)
    sheet = _by_name(sheets, _sheet_name("prototype"))
    assert sheet["truncated"] is True
    # One row only: the note ``_sheet`` appends. Nothing this entity recorded survived the cap.
    assert len(sheet["rows"]) == 1
    assert f"capped at {CAP}" in sheet["rows"][0][0]


def test_the_overview_counts_rows_the_workbook_could_not_carry(monkeypatch):
    """"Rows recorded" is a coverage column, and a coverage column may not read zero for a full one.

    "Stages answered" cannot be counted the same way without an unbounded grouping (see
    ``_dw_overview_sheet``), so it carries a ``+`` wherever rows were cut — "at least this many" —
    rather than a bare number that would contradict the row count beside it.
    """
    sheets, _entries_delegate = _report(monkeypatch)
    sheet = _by_name(sheets, "Design workshops")
    id_at = sheet["columns"].index("Workshop id")
    stages_at = sheet["columns"].index("Stages answered")
    rows_at = sheet["columns"].index("Rows recorded")
    by_id = {row[id_at]: row for row in sheet["rows"] if row[id_at] in SEED}
    true_totals = _true_by_workshop()

    for workshop_id, expected in true_totals.items():
        assert by_id[workshop_id][rows_at] == str(expected), workshop_id

    # dw_1 and dw_2 were carried whole, so their stage counts are exact and unchanged.
    assert "+" not in by_id["dw_1"][stages_at]
    assert "+" not in by_id["dw_2"][stages_at]
    # dw_3 lost every row it had: a stage's worth of rows exists, none of them are in this workbook,
    # and "0 of 22" beside "15 rows recorded" would be a contradiction on one line.
    assert by_id["dw_3"][stages_at] == f"0+ of {len(dwd.stages())}"
    assert by_id["dw_3"][rows_at] == str(true_totals["dw_3"])


def test_the_counts_are_taken_over_the_same_predicate_as_the_rows(monkeypatch):
    """A total counted over a different ``where`` is a different corpus, honestly reported.

    The three reads are issued together and must all narrow to the workshops in scope and to rows
    that are not deleted; asserting they were handed the SAME predicate is the only way to be sure
    the index page is vouching for the list beside it.
    """
    _sheets, entries = _report(monkeypatch)
    assert len(entries.wheres) == 3
    assert entries.wheres[0] == entries.wheres[1] == entries.wheres[2]
    assert entries.wheres[0]["deletedAt"] is None
    assert sorted(entries.wheres[0]["designWorkshopId"]["in"]) == sorted(SEED)


# --------------------------------------------------------------------------------------
# And where the cap does not bite, nothing changed
# --------------------------------------------------------------------------------------


def test_an_untruncated_report_is_flagged_nowhere_and_counts_what_it_holds(monkeypatch):
    """The per-workshop path this block is normally read through must be untouched.

    With every row carried, the true counts and the carried counts are the same number, so the
    workbook a researcher already knows is byte-for-byte the one they got before the fix — no
    banner, no note row, no ``+``.
    """
    roomy = sum(_true_by_workshop().values()) + len(dwd.tables()) + 10
    sheets, _entries_delegate = _report(monkeypatch, cap=roomy)
    block = _dw_sheets(sheets)
    assert block, "the fixture produced no design-workshop sheets at all"
    for sheet in block:
        assert sheet["truncated"] is False, sheet["name"]
        assert "truncatedNote" not in sheet, sheet["name"]

    assert _index_rows(sheets)["participant"] == _true_by_entity()["participant"]
    overview = _by_name(sheets, "Design workshops")
    stages_at = overview["columns"].index("Stages answered")
    assert not any("+" in row[stages_at] for row in overview["rows"])


def test_sheets_built_from_a_hand_made_bundle_still_count_their_own_rows(monkeypatch):
    """``dwEntityCounts`` empty means "nobody counted", not "no rows".

    ``_dw_sheets`` is called directly by other readers' tests with a bundle assembled from
    ``_REPORT_KEYS`` and no counts in it (see ``test_entry_provenance_readers``). Those callers must
    keep getting the workbook they got before — the counts fall back to the rows in hand rather than
    to zero, which would have turned every index row into "No rows found".
    """
    monkeypatch.setattr(data_browser, "REPORT_TAKE", CAP)
    data: dict[str, Any] = {key: [] for key in data_browser._REPORT_KEYS}
    data["designWorkshops"] = _workshops()[:1]
    data["dwEntries"] = [entry for entry in _entries() if entry.designWorkshopId == "dw_1"]

    sheets = data_browser._dw_sheets(data)
    rows = _index_rows(sheets)
    assert rows["sketch"] == SEED["dw_1"]["sketch"]
    assert rows["participant"] == SEED["dw_1"]["participant"]
    assert all(sheet["truncated"] is False for sheet in sheets)
