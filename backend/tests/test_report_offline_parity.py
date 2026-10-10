"""The browser's copy of the report builder is held to the server's — this half proves the target.

``frontend/lib/offlineReport`` builds a workshop's report in the browser when there is no
connection, and ``frontend/e2e/offline-report-parity-unit.spec.ts`` holds it, block for block, to
``shared/report-parity/expected.json``. That file is only worth comparing against if it is what the
SERVER builds for ``shared/report-parity/workshop.json`` today, so this test rebuilds it with
``tools/report_offline_parity.py`` and fails when the committed copy is stale. A change to the
builder, a template, a warning's wording or the gazetteer then cannot land without both files being
regenerated — and regenerating them is what turns the frontend spec red until the port agrees.

No database: the tool builds documents in memory from the fixture.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path

TOOL = Path(__file__).resolve().parents[1] / "tools" / "report_offline_parity.py"


def _tool():
    spec = importlib.util.spec_from_file_location("report_offline_parity", TOOL)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_the_expected_reports_are_what_the_server_builds_today():
    tool = _tool()
    committed = json.loads(tool.EXPECTED.read_text(encoding="utf-8"))
    rebuilt = json.loads(tool._dump(tool.expected()))
    stale = [name for name in rebuilt["cases"] if committed["cases"].get(name) != rebuilt["cases"][name]]
    assert not stale and committed == rebuilt, (
        f"shared/report-parity/expected.json is stale for {stale or 'its header'} — run "
        "`python tools/report_offline_parity.py --write` from backend/ and make the browser's builder "
        "(frontend/lib/offlineReport) agree with the new output."
    )


def test_the_gazetteer_is_what_the_server_geocodes_with_today():
    tool = _tool()
    committed = json.loads(tool.GAZETTEER.read_text(encoding="utf-8"))
    assert committed == json.loads(tool._dump(tool.gazetteer())), (
        "frontend/lib/offlineReport/gazetteer.json is stale — run "
        "`python tools/report_offline_parity.py --write` from backend/."
    )


def test_the_fixture_reaches_the_branches_it_exists_to_reach():
    """A fixture that stopped exercising a branch would let the port drift there unseen."""
    tool = _tool()
    cases = tool.expected()["cases"]
    every = [block for case in cases.values() for block in case["blocks"]]
    kinds = {block["type"] for block in every}
    assert {"COVER", "TOC", "HEADING", "PARAGRAPH", "BULLETLIST", "KEYVALUE", "TABLE", "IMAGE",
            "IMAGEGRID", "METRICROW", "SIGNATURE", "SPACER", "PAGEBREAK", "MAP", "CHART"} <= kinds
    charts = {block["title"] for block in every if block["type"] == "CHART"}
    assert len(charts) == 7, charts
    warnings = " ".join(w for case in cases.values() for w in case["warnings"])
    for phrase in ("capture tier", "photograph cap", "attached file(s) are named", "cover field(s)",
                   "required field(s) not recorded", "own section(s)"):
        assert phrase in warnings, phrase
    # Odia text sits in table cells and key/value pairs, so the whole output is searched.
    assert '"script": "ODIA"' in json.dumps(cases)
