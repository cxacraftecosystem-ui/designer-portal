"""``xlsx_table``'s value-row readers — and the one fixture no other suite in this repo builds.

WHY THIS FILE EXISTS AT ALL. ``sheet_value_rows`` was added on 2026-09-13 so the annual-plan
directory could read a DATE cell as the ``datetime`` openpyxl already resolved, instead of as the
string ``cell_text`` renders it ("2026-03-12 00:00:00") and then guessing a date back out of that.
The obvious way to write it — "empty means ``None`` or whitespace" — is subtly and silently wrong,
and this file is the proof.

``cell_text`` maps most of Excel's ERROR family to ``""`` — anything of the shape ``#…!`` (``#REF!``,
``#VALUE!``, ``#NULL!``, ``#DIV/0!``) plus the exact string ``#N/A``. (``#NAME?`` is NOT in it, ends
in a question mark, and is pinned below as the known gap it is.) So a cell holding a cached ``#REF!`` over a formula IS empty for the purpose of the
formula lookup, and must therefore come back as a ``FormulaCell`` — the marker that turns "row 12 is
blank" into "row 12 holds a formula Excel has never calculated". Under the cheaper predicate that
cell is appended raw, the marker is never made, and the "formula whose result is not saved" report
stops firing for exactly the cells most likely to hold a broken formula.

No existing questionnaire or artisan test builds a ``#REF!``-over-a-formula fixture, which is why
the wrong predicate would otherwise have shipped green.

Nothing here needs a database or a network; openpyxl only.
"""

from datetime import datetime
from io import BytesIO
from typing import Any

import pytest
from openpyxl import Workbook, load_workbook

from app.services import xlsx_table
from app.services.xlsx_table import (
    FormulaCell,
    as_text_rows,
    cell_text,
    sheet_rows,
    sheet_value_rows,
)


def _workbook_with(cells: dict[str, Any]) -> bytes:
    """A one-sheet workbook holding *cells* as CACHED VALUES.

    The formula half is handed to the reader SEPARATELY, as the ``formulas`` mapping, because one
    openpyxl cell holds either a formula or a value and never both — which is exactly the asymmetry
    ``FormulaCell`` exists to bridge, and exactly why ``sheet_value_rows`` takes the map as a
    parameter rather than reading it off the worksheet it was handed.
    """
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    for ref, value in cells.items():
        ws[ref] = value
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def _sheet(data: bytes):
    return load_workbook(BytesIO(data), data_only=True, read_only=True).worksheets[0]

def _excel_datetime(year: int, month: int, day: int) -> datetime:
    """A NAIVE ``datetime``, which is what an Excel date cell IS and the only thing openpyxl writes.

    ruff's DTZ001 is right about instants and wrong about spreadsheet dates: openpyxl raises
    ``ValueError: Excel does not support timezones in datetimes`` for a tz-aware value, and a
    workshop planned for 12 March is planned for 12 March in Bhuj and in Delhi alike. Said once,
    here, rather than as a bare ``noqa`` at every fixture that needs a date cell.
    """
    return datetime(year, month, day)  # noqa: DTZ001




@pytest.mark.parametrize("cached", ["#REF!", "#VALUE!", "#NULL!", "#N/A", "#DIV/0!"])
def test_a_ref_error_over_a_formula_still_reports_the_formula(cached: str):
    """An Excel error cached over a formula must come back as the FORMULA, in BOTH readers.

    This is the invariant the value-row reader was most likely to lose. ``#REF!`` is what a formula
    reads as after somebody deletes the column it pointed at — the single likeliest content of a
    broken formula cell in an uploaded file — and the whole point of ``FormulaCell`` is that the
    uploader is told "this cell holds a formula whose result is not saved" instead of "this cell is
    blank". A predicate that tests ``value is None or not value.strip()`` reports neither.
    """
    data = _workbook_with({"A1": "Workshop No.", "B3": cached})
    formulas = {(3, 2): "=B2&\" (2026)\""}

    values = sheet_value_rows(_sheet(data), formulas, max_rows=50)
    row3 = next(cells for number, cells in values if number == 3)
    assert isinstance(row3[1], FormulaCell), f"{cached} over a formula must become a FormulaCell"
    assert str(row3[1]) == '=B2&" (2026)"'

    text = as_text_rows(values)
    text3 = next(cells for number, cells in text if number == 3)
    assert isinstance(text3[1], FormulaCell), "the text view must not flatten the marker to ''"


def test_a_name_error_over_a_formula_is_a_KNOWN_GAP_and_reads_as_content():
    """``#NAME?`` is NOT in the family ``cell_text`` empties, and the reader is honest about that.

    THIS TEST PINS A PRE-EXISTING GAP RATHER THAN AN INTENTION. ``cell_text`` empties a cell whose
    text ``startswith("#") and endswith("!")`` — which covers ``#REF!``, ``#VALUE!``, ``#NULL!`` and
    ``#DIV/0!`` — plus the two exact strings ``#N/A`` and ``#NULL!``. ``#NAME?`` ends in a QUESTION
    MARK and matches none of those, so it is ordinary text: it suppresses no formula marker, and a
    reader that stores that column stores the literal string "#NAME?".

    IT IS DELIBERATELY NOT FIXED HERE. ``cell_text`` is shared by three .xlsx readers, one of which
    (the questionnaire) has shipped and whose already-imported forms would RE-IMPORT DIFFERENTLY the
    day the predicate widens — a silent change to a shipped path is worse than a narrow gap that is
    written down. Widening it is its own change with its own regression run, and the day somebody
    makes it, this test is where they find out it was known.
    """
    data = _workbook_with({"A1": "Workshop No.", "B3": "#NAME?"})
    values = sheet_value_rows(_sheet(data), {(3, 2): "=NOSUCHFN(B2)"}, max_rows=50)
    row3 = next(cells for number, cells in values if number == 3)
    assert not isinstance(row3[1], FormulaCell)
    assert cell_text(row3[1]) == "#NAME?"


def test_a_value_row_and_a_text_row_agree_except_on_dates():
    """``as_text_rows(sheet_value_rows(...))`` is byte-for-byte ``sheet_rows(...)``.

    That equivalence is what makes the new reader safe to put underneath the old one's callers: the
    two differ only in WHAT THEY KEEP, never in what they decide is empty or in how they render an
    ordinary cell. The date column is the documented exception and is asserted as one, so that a
    future "simplification" collapsing the two functions has to delete this assertion to pass.
    """
    data = _workbook_with(
        {
            "A1": "Workshop No.",
            "B1": "Venue",
            "C1": "Start Date",
            "A2": "DPW/2026/017",
            "B2": "  DIC Hall, Bhuj  ",
            "C2": _excel_datetime(2026, 3, 12),
        }
    )
    ws = _sheet(data)
    text_direct = sheet_rows(ws, {}, max_rows=50)
    values = sheet_value_rows(_sheet(data), {}, max_rows=50)
    assert as_text_rows(values) == text_direct

    row2_values = next(cells for number, cells in values if number == 2)
    row2_text = next(cells for number, cells in text_direct if number == 2)
    assert row2_values[2] == _excel_datetime(2026, 3, 12), "the resolved datetime must survive"
    assert row2_text[2] == "2026-03-12 00:00:00", "and text is exactly what it always was"
    assert row2_values[2] != row2_text[2]


def test_the_error_family_reads_as_empty_in_both_readers_when_no_formula_is_behind_it():
    """``#REF!`` with NO formula behind it is content nobody typed, and is empty in both views.

    The pair with the test above: the marker appears only where there is actually a formula to name.
    Otherwise the error string would reach a stored field and a ministry's directory would carry a
    venue called "#REF!".
    """
    data = _workbook_with({"A1": "Workshop No.", "B3": "#REF!"})
    values = sheet_value_rows(_sheet(data), {}, max_rows=50)
    row3 = next(cells for number, cells in values if number == 3)
    assert not isinstance(row3[1], FormulaCell)
    assert cell_text(row3[1]) == ""


def test_load_value_sheets_refuses_a_non_workbook_with_the_magic_byte_sentence():
    """The whole-file refusal is the SAME function both loaders use, chosen from the file's bytes.

    A second copy of the magic-byte branch is precisely what this module exists to prevent, so the
    value loader must reach ``unreadable_file_message`` and not invent its own wording.
    """
    ole2 = xlsx_table.OLE2_MAGIC + b"\x00" * 64
    with pytest.raises(xlsx_table.XlsxTableError) as excinfo:
        xlsx_table.load_value_sheets(ole2, max_sheets=4, max_rows=50, pro_forma="the pro-forma")
    assert "older .xls format" in str(excinfo.value)


def test_the_value_loader_and_the_text_loader_read_the_same_sheets():
    """Same sheet names, same row numbers, same emptiness decisions — only the cell type differs."""
    data = _workbook_with({"A1": "Workshop No.", "A2": "DPW/2026/017"})
    text = xlsx_table.load_sheets(data, max_sheets=4, max_rows=50)
    values = xlsx_table.load_value_sheets(data, max_sheets=4, max_rows=50)
    assert list(text) == list(values)
    for name in text:
        assert as_text_rows(values[name]) == text[name]
