"""``report_chart`` and ``report_map`` against the inputs the builder promises never to send.

The builder refuses to emit a figure the record cannot fill, and ``tests/test_report_figures.py``
pins that refusal. This file pins the other side of the same contract: what the rasterisers do
when they are handed a degenerate block anyway.

Both halves are needed and neither substitutes for the other. The builder's rules are editorial
judgement and will be relaxed one day by somebody adding a figure; the rasterisers are the last
code between a bad series and a 500 on the download button of a finished workshop. A pie is the
dangerous shape — every slice's angle is ``value / total`` — and a total of zero is not exotic: it
is what a cost sheet whose amounts were all typed as "0" produces.

Neither module is allowed to raise on data, ever. A report generation that fails is a designer in
a field with no report; a report generated without one figure is a report.
"""

import pytest

from app.services.report_chart import render_chart_png
from app.services.report_map import assets_available, render_map_png
from app.services.report_model import (
    ChartBlock,
    ChartKind,
    MapBlock,
    MapPoint,
    MapPointKind,
    ReportTheme,
)

_PNG_MAGIC = b"\x89PNG\r\n\x1a\n"

#: Every series shape that has ever reached a renderer from a real record, plus the two that
#: cannot but would be catastrophic: an empty series and one where every value is zero.
_DEGENERATE = (
    (),                                             # a figure the template asked for, no data
    (("Selected", 0.0),),                           # one category, and it is zero
    (("Selected", 0.0), ("Rejected", 0.0)),         # the zero total — the division-by-zero case
    (("Selected", 0.0), ("Rejected", 3.0)),         # a zero category beside a real one
    (("Material", -400.0), ("Labour", 900.0)),      # a negative, from a credited cost line
    (("Only one", 5.0),),                           # a single real value
)


@pytest.mark.parametrize("kind", list(ChartKind))
@pytest.mark.parametrize("series", _DEGENERATE)
def test_a_chart_with_a_zero_total_does_not_divide_by_zero(kind, series):
    """Every chart kind against every degenerate series, including a total of exactly zero.

    Parametrised over the whole enum rather than over the kinds in use today, so a sixth chart
    kind added next year is covered by this test before it is covered by a template.
    """
    png, width, height = render_chart_png(
        ChartBlock(kind=kind, series=series, title="A figure", unit="INR"), ReportTheme(), 640
    )
    assert png.startswith(_PNG_MAGIC), f"{kind.value} did not produce a PNG"
    assert width == 640
    assert height > 0


def test_a_chart_of_nothing_says_so_rather_than_drawing_an_empty_axis():
    """An omitted figure looks like a rendering fault; an empty one that says why is a statement
    about the record. Both come out of the same call, so the difference is bytes."""
    empty, _w, _h = render_chart_png(
        ChartBlock(kind=ChartKind.BAR, series=(), title="A figure"), ReportTheme(), 640
    )
    filled, _w2, _h2 = render_chart_png(
        ChartBlock(kind=ChartKind.BAR, series=(("A", 1.0), ("B", 2.0)), title="A figure"),
        ReportTheme(), 640,
    )
    assert empty != filled
    assert len(empty) < len(filled)


def test_an_unparseable_value_is_dropped_and_named_rather_than_crashing_the_export():
    """A stage entry can hold a string where a number belongs — hand-edited JSON, a client one
    release ahead. The figure loses that category and the export survives."""
    png, _w, _h = render_chart_png(
        ChartBlock(kind=ChartKind.BAR,
                   series=(("Good", 5.0), ("Bad", float("nan")), ("Worse", float("inf"))),
                   title="A figure"),
        ReportTheme(), 640,
    )
    assert png.startswith(_PNG_MAGIC)


def test_a_theme_with_unusable_colours_still_renders():
    """Colours cross the wire as bare RRGGBB strings from a template a future release edits."""
    png, _w, _h = render_chart_png(
        ChartBlock(kind=ChartKind.DONUT, series=(("A", 1.0), ("B", 1.0))),
        ReportTheme(accent="not a colour", ink="", muted="#####"), 640,
    )
    assert png.startswith(_PNG_MAGIC)


# --------------------------------------------------------------------------------------
# The map
# --------------------------------------------------------------------------------------


def _skip_without_assets() -> None:
    if not assets_available():
        pytest.skip("boundary geometry is not on this machine; report_map returns None by design")


def test_a_map_with_no_points_still_renders():
    """A workshop whose participants all came from villages the atlas cannot place is ordinary,
    and its map is the tinted state and nothing else. Returning nothing for that case would
    silently remove a figure the template asked for, which reads as a rendering fault rather than
    as an absence of data."""
    _skip_without_assets()
    rendered = render_map_png(MapBlock(points=(), highlight=frozenset({"Odisha"})),
                              ReportTheme(), 720)
    assert rendered is not None
    png, width, height = rendered
    assert png.startswith(_PNG_MAGIC)
    assert width == 720 and height > 0


def test_a_map_with_no_points_and_no_highlight_still_renders():
    """The emptiest block the builder can emit: stage 1 named a district this build's state table
    does not recognise, so there is not even a region to tint."""
    _skip_without_assets()
    rendered = render_map_png(MapBlock(), ReportTheme(), 720)
    assert rendered is not None
    assert rendered[0].startswith(_PNG_MAGIC)


def test_a_pin_outside_india_is_dropped_rather_than_clamped_to_the_coast():
    """0,0 is the Gulf of Guinea and is what a form that never obtained a fix writes. Clamped to
    the edge it becomes a pin on the Konkan coast, which reads as a finding."""
    _skip_without_assets()
    off_map = render_map_png(
        MapBlock(points=(MapPoint(label="Nowhere", lat=0.0, lon=0.0,
                                  kind=MapPointKind.VENUE),)),
        ReportTheme(), 720,
    )
    empty = render_map_png(MapBlock(), ReportTheme(), 720)
    assert off_map is not None and empty is not None
    assert off_map[0] == empty[0], "a point off the map must leave the image untouched"


def test_a_state_the_geometry_cannot_tint_is_not_an_error():
    """``highlight`` carries names, and a name this build cannot seed a fill from — a renamed
    state, a typo that survived — must cost the figure its tint and nothing more."""
    _skip_without_assets()
    rendered = render_map_png(
        MapBlock(highlight=frozenset({"Atlantis", "Odisha"})), ReportTheme(), 720
    )
    assert rendered is not None
    assert rendered[0].startswith(_PNG_MAGIC)


def test_a_folded_pin_carrying_a_count_renders():
    """``MapPoint.count`` is what keeps six weavers from Barpali from looking like one weaver."""
    _skip_without_assets()
    rendered = render_map_png(
        MapBlock(points=(
            MapPoint(label="Kharagpur", lat=22.3149, lon=87.3105, kind=MapPointKind.VENUE),
            MapPoint(label="Bagru", lat=26.8149, lon=75.5449, kind=MapPointKind.ARTISAN, count=6),
        )),
        ReportTheme(), 720,
    )
    assert rendered is not None
    assert rendered[0].startswith(_PNG_MAGIC)


# --------------------------------------------------------------------------------------
# How big the words in a figure actually are
# --------------------------------------------------------------------------------------


def test_a_chart_label_is_the_size_the_docx_sets_the_same_label_to():
    """THE REGRESSION: the PDF's figures were lettered at about 2.5 pt.

    The glyph multiplier came from a magic `width / 900`, and on A4 with a 25 mm margin a
    74 %-wide figure is 932 px — so `round(1.0356 * 1.9)` was 2 and `small` was 1: a 5x7 bitmap
    font 0.89 mm tall for the donut legend, the category labels, the axis ticks and the data
    values alike, in a document whose body text is 10.5 pt. The .docx of the same workshop emits
    the same three charts as native DrawingML with every text element at `sz="800"`, so the two
    files a designer submits together disagreed about whether the figures could be read.
    """
    from app.services.report_chart import _LABEL_PT, glyph_scales
    from app.services.report_raster import GLYPH_H, RENDER_DPI

    label, small = glyph_scales()
    printed_pt = label * GLYPH_H * 72.0 / RENDER_DPI
    # Within one quantum of the .docx's 8 pt — the raster's glyph is a whole number of pixels, so
    # 7 x 72 / 200 = 2.52 pt is the finest step this font can express.
    assert abs(printed_pt - _LABEL_PT) <= GLYPH_H * 72.0 / RENDER_DPI
    assert printed_pt >= 6.0, "a figure a person has to read cannot be lettered under 6 pt"
    assert small >= 2, "and neither can the axis ticks"


def test_the_two_sizing_paths_cannot_disagree():
    """`chart_pixel_box` sizes the frame the .docx draws its VECTOR chart into and
    `render_chart_png` draws the .pdf's picture. They read the same function, because a
    disagreement makes one figure two different shapes in the two files of one workshop."""
    import inspect

    from app.services import report_chart

    for name in ("chart_pixel_box", "render_chart_png"):
        source = inspect.getsource(getattr(report_chart, name))
        assert "glyph_scales()" in source, f"{name} sizes its glyphs on its own"


def test_a_legend_keeps_the_category_name_at_the_readable_size():
    """The legend column is measured from the labels rather than fixed at a fraction of the
    width. It was wide enough only because the text was tiny; at a legible size the name of a
    review decision — the one thing a legend exists to carry — started disappearing."""
    from app.services.report_chart import glyph_scales
    from app.services.report_raster import text_width

    block = ChartBlock(
        kind=ChartKind.DONUT,
        series=(("Selected", 4), ("Revise and resubmit", 1), ("Rejected", 1)),
        title="Prototype status",
    )
    png, width, _height = render_chart_png(block, ReportTheme(), 932)
    assert png[:8] == b"\x89PNG\r\n\x1a\n"

    # The longest category name has to fit in the legend column at the label size.
    _label, small = glyph_scales()
    assert text_width("Revise and resubmit", small) < width * 0.52
