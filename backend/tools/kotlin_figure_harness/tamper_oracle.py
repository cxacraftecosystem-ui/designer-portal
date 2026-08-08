"""Negative control: dump a DELIBERATELY WRONG oracle and prove the harness notices.

A parity harness that passes tells you nothing until you have watched it fail. This nudges three
constants the Python actually uses at call time — one in each of the three modules under test — and
re-dumps. Each nudge is the size of a plausible edit, not a wrecking ball:

  * report_chart._ASPECT   0.62  -> 0.615  : every non-horizontal chart comes out a few pixels shorter
  * report_map._PIN_RADIUS VENUE 11.0 -> 11.4 : one pin on two map figures is a shade larger
  * report_raster.PIXELS_PER_MM x 1.001    : the scalar that decides how wide every figure is

If the harness still says PARITY OK after this, it is checking nothing.
"""

import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND / "tools"))

# E402 below is unavoidable and deliberate: this is a standalone script, and neither the oracle
# nor `app` is importable until the two sys.path entries above exist.
import report_figure_oracle as oracle  # noqa: E402

from app.services import report_chart, report_map, report_raster  # noqa: E402
from app.services.report_model import MapPointKind  # noqa: E402

report_chart._ASPECT = 0.615
report_map._PIN_RADIUS = dict(report_map._PIN_RADIUS) | {MapPointKind.VENUE: 11.4}
report_raster.PIXELS_PER_MM = report_raster.PIXELS_PER_MM * 1.001

raise SystemExit(oracle.main(Path(sys.argv[1]).resolve()))
