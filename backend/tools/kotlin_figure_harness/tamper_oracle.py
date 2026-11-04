import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND / "tools"))

import report_figure_oracle as oracle  # noqa: E402

from app.services import report_chart, report_map, report_raster  # noqa: E402
from app.services.report_model import MapPointKind  # noqa: E402

report_chart._ASPECT = 0.615
report_map._PIN_RADIUS = dict(report_map._PIN_RADIUS) | {MapPointKind.VENUE: 11.4}
report_raster.PIXELS_PER_MM = report_raster.PIXELS_PER_MM * 1.001

raise SystemExit(oracle.main(Path(sys.argv[1]).resolve()))
