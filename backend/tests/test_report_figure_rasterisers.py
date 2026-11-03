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

_DEGENERATE = (
    (),
    (("Selected", 0.0),),
    (("Selected", 0.0), ("Rejected", 0.0)),
    (("Selected", 0.0), ("Rejected", 3.0)),
    (("Material", -400.0), ("Labour", 900.0)),
    (("Only one", 5.0),),
)


@pytest.mark.parametrize("kind", list(ChartKind))
@pytest.mark.parametrize("series", _DEGENERATE)
def test_a_chart_with_a_zero_total_does_not_divide_by_zero(kind, series):
    png, width, height = render_chart_png(
        ChartBlock(kind=kind, series=series, title="A figure", unit="INR"), ReportTheme(), 640
    )
    assert png.startswith(_PNG_MAGIC), f"{kind.value} did not produce a PNG"
    assert width == 640
    assert height > 0


def test_a_chart_of_nothing_says_so_rather_than_drawing_an_empty_axis():
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
    png, _w, _h = render_chart_png(
        ChartBlock(kind=ChartKind.BAR,
                   series=(("Good", 5.0), ("Bad", float("nan")), ("Worse", float("inf"))),
                   title="A figure"),
        ReportTheme(), 640,
    )
    assert png.startswith(_PNG_MAGIC)


def test_a_theme_with_unusable_colours_still_renders():
    png, _w, _h = render_chart_png(
        ChartBlock(kind=ChartKind.DONUT, series=(("A", 1.0), ("B", 1.0))),
        ReportTheme(accent="not a colour", ink="", muted="#####"), 640,
    )
    assert png.startswith(_PNG_MAGIC)



def _skip_without_assets() -> None:
    if not assets_available():
        pytest.skip("boundary geometry is not on this machine; report_map returns None by design")


def test_a_map_with_no_points_still_renders():
    _skip_without_assets()
    rendered = render_map_png(MapBlock(points=(), highlight=frozenset({"Odisha"})),
                              ReportTheme(), 720)
    assert rendered is not None
    png, width, height = rendered
    assert png.startswith(_PNG_MAGIC)
    assert width == 720 and height > 0


def test_a_map_with_no_points_and_no_highlight_still_renders():
    _skip_without_assets()
    rendered = render_map_png(MapBlock(), ReportTheme(), 720)
    assert rendered is not None
    assert rendered[0].startswith(_PNG_MAGIC)


def test_a_pin_outside_india_is_dropped_rather_than_clamped_to_the_coast():
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
    _skip_without_assets()
    rendered = render_map_png(
        MapBlock(highlight=frozenset({"Atlantis", "Odisha"})), ReportTheme(), 720
    )
    assert rendered is not None
    assert rendered[0].startswith(_PNG_MAGIC)


def test_a_folded_pin_carrying_a_count_renders():
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



def test_a_chart_label_is_the_size_the_docx_sets_the_same_label_to():
    from app.services.report_chart import _LABEL_PT, glyph_scales
    from app.services.report_raster import GLYPH_H, RENDER_DPI

    label, small = glyph_scales()
    printed_pt = label * GLYPH_H * 72.0 / RENDER_DPI
    assert abs(printed_pt - _LABEL_PT) <= GLYPH_H * 72.0 / RENDER_DPI
    assert printed_pt >= 6.0, "a figure a person has to read cannot be lettered under 6 pt"
    assert small >= 2, "and neither can the axis ticks"


def test_the_two_sizing_paths_cannot_disagree():
    import inspect

    from app.services import report_chart

    for name in ("chart_pixel_box", "render_chart_png"):
        source = inspect.getsource(getattr(report_chart, name))
        assert "glyph_scales()" in source, f"{name} sizes its glyphs on its own"


def test_a_legend_keeps_the_category_name_at_the_readable_size():
    from app.services.report_chart import glyph_scales
    from app.services.report_raster import text_width

    block = ChartBlock(
        kind=ChartKind.DONUT,
        series=(("Selected", 4), ("Revise and resubmit", 1), ("Rejected", 1)),
        title="Prototype status",
    )
    png, width, _height = render_chart_png(block, ReportTheme(), 932)
    assert png[:8] == b"\x89PNG\r\n\x1a\n"

    _label, small = glyph_scales()
    assert text_width("Revise and resubmit", small) < width * 0.52
