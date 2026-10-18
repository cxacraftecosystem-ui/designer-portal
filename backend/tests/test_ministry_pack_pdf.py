"""THE MINISTRY PACK'S PDF: a real PDF, one or two pages, and the month-to-date banner when owed."""

from __future__ import annotations

from datetime import UTC, datetime
from io import BytesIO

from pypdf import PdfReader

from app.services.ministry_pack_pdf import build_pack_pdf
from app.services.ministry_pack_sheets import empty_counts


def _summary(**extra):
    counts = {
        **empty_counts(),
        "workshops": 41,
        "completed": 16,
        "participants": 388,
        "sanctionedAmount": "1234500.00",
    }
    data = {
        "periodLabel": "September 2026",
        "schemeLabel": "Skill Upgradation",
        "snapshotAt": datetime(2026, 10, 1, 0, 40, tzinfo=UTC),
        "monthToDate": False,
        "revision": 1,
        "counts": counts,
        "byState": [
            {"state": f"State {i}", "workshops": 20 - i, "completed": 1, "participants": 9}
            for i in range(14)
        ],
        "cap": 2000,
        "planYearLabel": "2026-27",
        "planned": 12,
        "promoted": 5,
    }
    data.update(extra)
    return data


def _pages(body: bytes) -> int:
    return len(PdfReader(BytesIO(body)).pages)


def _text(body: bytes) -> str:
    return " ".join(page.extract_text() or "" for page in PdfReader(BytesIO(body)).pages)


def test_it_is_a_pdf_of_one_or_two_pages() -> None:
    body = build_pack_pdf(_summary())
    assert body.startswith(b"%PDF")
    assert 1 <= _pages(body) <= 2


def test_the_month_to_date_banner_appears_only_when_owed() -> None:
    plain = _text(build_pack_pdf(_summary()))
    banner = _text(build_pack_pdf(_summary(monthToDate=True)))
    assert "Month to date" not in plain
    assert "Month to date" in banner


def test_only_the_top_ten_states_are_listed() -> None:
    text = _text(build_pack_pdf(_summary()))
    assert "State 9" in text
    assert "State 10" not in text and "State 13" not in text
