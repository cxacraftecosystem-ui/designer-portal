from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace

from app.services.datareport import build
from app.services.datareport.sheets import extras_changes, extras_everything
from tests import report_fixtures as rf

TITLE = "Dummy withheld design workshop"
NOTE = "dummy-withheld-note"


def _context(downloads: bool):
    ctx = rf.context(build.options_from({"extras": "everything,changes"}))
    ctx.scope = SimpleNamespace(records={}, media={}, design_workshops=True, design_workshop_downloads=downloads)
    ctx.user = SimpleNamespace(id="p1", role="PROFESSOR", name="P")
    now = datetime(2026, 11, 5, 10, 30, tzinfo=UTC)
    ctx.material["records"]["designWorkshops"] = [
        SimpleNamespace(id="dw1", title=TITLE, notes=NOTE, status="PRE_SUBMISSION", createdAt=now, updatedAt=now, createdById="u1")
    ]
    return ctx


def _everything_text(ctx) -> str:
    return " ".join(str(row.get("value")) for row in extras_everything.flatten(ctx))


def test_the_everything_sheet_leaves_out_design_workshops_the_reader_may_not_download():
    ctx = _context(downloads=False)
    text = _everything_text(ctx)
    assert TITLE not in text and NOTE not in text
    changes = extras_changes.record_changes(ctx, datetime(2026, 1, 1, tzinfo=UTC))
    assert not [c for c in changes if c["kind"] == "designWorkshops"]


def test_the_everything_sheet_keeps_design_workshops_for_a_reader_who_may_download_them():
    ctx = _context(downloads=True)
    text = _everything_text(ctx)
    assert TITLE in text and NOTE in text
    changes = extras_changes.record_changes(ctx, datetime(2026, 1, 1, tzinfo=UTC))
    assert [c for c in changes if c["kind"] == "designWorkshops"]
