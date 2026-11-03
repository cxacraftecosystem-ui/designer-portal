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
    return datetime(year, month, day)  # noqa: DTZ001




@pytest.mark.parametrize("cached", ["#REF!", "#VALUE!", "#NULL!", "#N/A", "#DIV/0!"])
def test_a_ref_error_over_a_formula_still_reports_the_formula(cached: str):
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
    data = _workbook_with({"A1": "Workshop No.", "B3": "#NAME?"})
    values = sheet_value_rows(_sheet(data), {(3, 2): "=NOSUCHFN(B2)"}, max_rows=50)
    row3 = next(cells for number, cells in values if number == 3)
    assert not isinstance(row3[1], FormulaCell)
    assert cell_text(row3[1]) == "#NAME?"


def test_a_value_row_and_a_text_row_agree_except_on_dates():
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
    data = _workbook_with({"A1": "Workshop No.", "B3": "#REF!"})
    values = sheet_value_rows(_sheet(data), {}, max_rows=50)
    row3 = next(cells for number, cells in values if number == 3)
    assert not isinstance(row3[1], FormulaCell)
    assert cell_text(row3[1]) == ""


def test_load_value_sheets_refuses_a_non_workbook_with_the_magic_byte_sentence():
    ole2 = xlsx_table.OLE2_MAGIC + b"\x00" * 64
    with pytest.raises(xlsx_table.XlsxTableError) as excinfo:
        xlsx_table.load_value_sheets(ole2, max_sheets=4, max_rows=50, pro_forma="the pro-forma")
    assert "older .xls format" in str(excinfo.value)


def test_the_value_loader_and_the_text_loader_read_the_same_sheets():
    data = _workbook_with({"A1": "Workshop No.", "A2": "DPW/2026/017"})
    text = xlsx_table.load_sheets(data, max_sheets=4, max_rows=50)
    values = xlsx_table.load_value_sheets(data, max_sheets=4, max_rows=50)
    assert list(text) == list(values)
    for name in text:
        assert as_text_rows(values[name]) == text[name]
