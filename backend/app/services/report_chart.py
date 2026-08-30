"""The report's infographics: one :class:`~app.services.report_model.ChartBlock` to one PNG.

The figures the source document asks stage 18 and stage 17 for — how many designs, prototypes by
review decision, what a piece cost by head, which price band the range fell in, what adoption
looked like at three, six and twelve months — are all the same shape: ONE measure over a set of
categories. :class:`ChartBlock` says so, and this module draws the five forms that shape can
usefully take.

WHY IT RASTERISES, and why that is still the right default for four of the five renderers. Drawing
a bar chart into OOXML means either a ``c:chart`` part (a second XML schema, a second relationship
graph, and a picture Word renders from live data the reader can edit) or a table of shaded cells
pretending to be bars. Drawing one into a PDF means ReportLab's graphics package. Drawing them on
the phone means two more. Four implementations of one figure, which is four chances for the .docx
and the .pdf of one workshop to disagree about the same number. Rasterising once and handing the
PNG to the picture path each renderer already has is one implementation and one answer.

WHAT CHANGED. ``report_docx`` (and its Kotlin port) now writes a REAL ``c:chart`` part for the
kinds Word has a native form for, because a ministry that receives a .docx expects to click a bar
and see the number behind it, and because a vector chart survives being enlarged for a wall
display while a 200 dpi PNG does not. That is a SECOND renderer of the same block, so it is not
allowed its own opinions about the data: it reads :func:`clean_series` for which points survive,
:func:`slice_colours` for the ramp and :func:`chart_pixel_box` for the printed shape — the three
decisions that would otherwise drift — and it falls back to the PNG below for anything it cannot
express. Both PDF writers and the browser preview still rasterise, so those three functions are
load-bearing on both paths and must stay free of any DrawingML or Word vocabulary.

WHAT IS IN THE PNG AND WHAT IS NOT. Axis numbers, category labels and value labels are in it,
because they only mean anything where they are placed. The block's TITLE and CAPTION are not:
those are printed by the document renderers as real text, which is what lets a caption carry
Odia or Devanagari. The five-by-seven face in ``report_raster`` cannot draw an Indic script and
drops what it cannot draw, so a title baked into the picture would silently lose a craft's local
name — see that module's note on the font.

TWO NUMERICAL RULES, both of which are the difference between a wrong figure and no figure:

**A zero total does not divide.** A workshop whose follow-up recorded no units, or whose cost
sheet is all zeroes, is an ordinary early-stage record. Every proportion below is taken only
after the total is known to be positive, and a circular chart with nothing in it draws an empty
ring and says so rather than raising inside an export a designer is waiting on.

**A negative value cannot be a slice.** A pie of a negative margin is not a smaller slice, it is
a meaningless one, so the circular kinds drop negatives and name them under the figure. The bar
and line kinds keep them and give the axis a baseline, because "the follow-up at twelve months
was worse than at six" is exactly the finding that figure exists to show.

``backend/tests/test_report_graphics.py`` covers the zero total, the empty series and the PNG.
"""

from __future__ import annotations

import math

from app.services.report_model import ChartBlock, ChartKind, ReportTheme
from app.services.report_raster import (
    GLYPH_H,
    RENDER_DPI,
    RGB,
    Raster,
    ellipsise,
    mix,
    rgb_of,
    text_height,
    text_width,
)

# --------------------------------------------------------------------------------------
# Numbers, as a figure prints them
# --------------------------------------------------------------------------------------


def _group_indian(digits: str) -> str:
    """12,34,567 rather than 1,234,567.

    The same grouping ``report_builder.format_value`` applies to every money value in the report,
    repeated here rather than imported because the builder imports the model and the model must
    not import a renderer — and a chart axis that grouped Western while the cost table beside it
    grouped Indian would read as two different numbers.
    """
    if len(digits) <= 3:
        return digits
    head, tail = digits[:-3], digits[-3:]
    parts: list[str] = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return ",".join([*parts, tail])


def format_number(value: float) -> str:
    """A value as a figure prints it: no trailing zeros, grouped once it is long enough.

    Deliberately NOT the ``4.2 L`` / ``1.3 Cr`` abbreviation a dashboard would use. This figure is
    read beside a cost table that prints the full rupee amount, and an officer comparing the two
    must not have to convert between them to see they agree.
    """
    if math.isnan(value) or math.isinf(value):
        return "-"
    sign = "-" if value < 0 else ""
    magnitude = abs(value)
    if magnitude >= 1000 or magnitude == int(magnitude):
        whole = f"{magnitude:.0f}"
        return sign + _group_indian(whole)
    text = f"{magnitude:.2f}".rstrip("0").rstrip(".")
    return sign + text


def _nice_step(span: float, target_ticks: int) -> float:
    """A round axis step covering ``span`` in roughly ``target_ticks`` steps.

    1, 2, 2.5 or 5 times a power of ten — the four multipliers a reader adds up in their head. An
    axis stepping by 7 is legible and useless: nobody reads the third gridline as 21.
    """
    if span <= 0 or target_ticks <= 0:
        return 1.0
    raw = span / target_ticks
    power = 10.0 ** math.floor(math.log10(raw)) if raw > 0 else 1.0
    for multiplier in (1.0, 2.0, 2.5, 5.0, 10.0):
        if raw <= multiplier * power:
            return multiplier * power
    return 10.0 * power


# --------------------------------------------------------------------------------------
# Colour
# --------------------------------------------------------------------------------------


def slice_colours(count: int, theme: ReportTheme) -> list[RGB]:
    """A ramp from the theme's accent to a pale wash of it, one step per slice.

    PUBLIC because the native Word chart writer paints its ``c:dPt`` slices from this exact list.
    A second ramp over there would give the .docx's editable pie one set of colours and the .pdf's
    picture of the same pie another, for the same workshop on the same day.

    A MONOCHROME RAMP, not a categorical palette, and the reason is the photocopier. Every report
    this app generates is printed, copied and filed at least once; a hue-based palette collapses
    to four indistinguishable greys the first time that happens, and the legend then names four
    slices a reader cannot tell apart. A lightness ramp survives the copy, and it also keeps the
    figure inside whichever of the four template themes is in force instead of importing a fifth
    colour scheme into the document.
    """
    accent = rgb_of(theme.accent, (31, 56, 100))
    soft = rgb_of(theme.accent_soft, (47, 84, 150))
    if count <= 1:
        return [accent]
    out: list[RGB] = []
    for index in range(count):
        position = index / (count - 1)
        # Through accent_soft at the midpoint, so the ramp uses both colours the template chose
        # rather than fading one of them out.
        if position <= 0.5:
            out.append(mix(accent, soft, position * 2))
        else:
            out.append(mix(soft, (255, 255, 255), (position - 0.5) * 1.55))
    return out


# --------------------------------------------------------------------------------------
# The renderer
# --------------------------------------------------------------------------------------

#: Aspect ratio of the plot for the kinds whose height does not depend on the row count. Close to
#: the golden ratio: tall enough that a small difference between two bars is visible, short enough
#: that a figure and the paragraph introducing it fit on one page together.
_ASPECT = 0.62


def clean_series(block: ChartBlock) -> tuple[list[tuple[str, float]], list[str]]:
    """The series with unusable entries removed, and the note that says what was removed.

    PUBLIC because the native Word chart writer must plot exactly these points and print exactly
    this note. If it filtered for itself, a NaN that this function drops and that one keeps would
    put a category in the .docx's editable chart that is missing from the .pdf's picture of it.
    """
    kept: list[tuple[str, float]] = []
    dropped: list[str] = []
    for label, raw in block.series:
        try:
            value = float(raw)
        except (TypeError, ValueError):
            dropped.append(label)
            continue
        if math.isnan(value) or math.isinf(value):
            dropped.append(label)
            continue
        if block.kind.is_circular and value < 0:
            dropped.append(label)
            continue
        kept.append((label, value))
    notes: list[str] = []
    if dropped:
        notes.append("Not shown: " + ", ".join(dropped[:6]) + ("…" if len(dropped) > 6 else ""))
    return kept, notes


#: What a chart label is meant to MEASURE on the printed page, in points.
#:
#: The .docx emits the same three charts as native DrawingML with every text element at
#: ``sz="800"`` — 8 pt — so these are the numbers the two files have to agree on. The PDF's
#: rasteriser sized its glyphs from a magic ``width / 900`` instead, and on A4 with a 25 mm margin
#: a 74 %-wide figure is 932 px, which gave ``round(1.0356 * 1.9) = 2`` and a ``small`` of 1: a
#: 5x7 bitmap font 0.89 mm tall, about 2.5 pt, for the donut legend, the category labels, the axis
#: ticks and the data values alike. Body text in the same PDF is 10.5 pt. The two files a designer
#: submits together disagreed about whether the figures could be read at all.
_LABEL_PT = 8.0
_SMALL_PT = 7.0


def glyph_scales() -> tuple[int, int]:
    """The (label, small) glyph multipliers, derived from the point sizes above.

    ONE FUNCTION because ``chart_pixel_box`` and ``render_chart_png`` both need them and must
    agree: the first sizes the frame the .docx draws its vector chart into, the second draws the
    .pdf's picture, and a disagreement makes the same figure a different SHAPE in the two files.

    The raster's glyph is ``GLYPH_H`` pixels tall at ``RENDER_DPI``, so the smallest step this
    font can express is 7 × 72 / 200 = 2.52 pt and both targets round to the same multiplier at
    the moment. That is not a mistake to correct later: the .docx sets EVERY chart text element to
    one size, so landing on one size here is parity rather than a loss of hierarchy. The two
    constants stay separate because the DPI is not a promise.

    ``android/.../report/ReportChart.kt`` carries the identical arithmetic and must be changed
    with it, or the on-device PDF and the server's disagree about a figure of the same data.
    """
    return (
        max(2, round(_LABEL_PT * RENDER_DPI / 72.0 / GLYPH_H)),
        max(1, round(_SMALL_PT * RENDER_DPI / 72.0 / GLYPH_H)),
    )


def chart_pixel_box(block: ChartBlock, width_px: int, rows: int) -> tuple[int, int]:
    """The pixel box a chart of ``rows`` categories occupies at a requested width.

    PUBLIC, and the only place the answer is computed. The native Word chart has no bitmap and so
    no intrinsic size at all: it is drawn into whatever frame the drawing gives it. Deriving that
    frame's aspect from here is what stops the .docx's vector figure being a different SHAPE from
    the .pdf's picture of the same figure — two documents of one workshop where the cost chart is
    half a page on one and a strip on the other, which reads as two different reports.
    """
    width = max(240, min(2400, int(width_px)))
    scale = width / 900.0
    _label, small = glyph_scales()
    if block.kind is ChartKind.HORIZONTAL_BAR:
        # Height follows the row count instead of an aspect ratio. Six cost heads in a box shaped
        # like the bar chart above would be six hairlines with the labels on top of each other.
        row = max(18.0, 26.0 * scale)
        unit_room = (text_height(small) + 3 * scale) if block.unit else 0.0
        height = int(max(row * 3, row * max(1, rows) + 24 * scale + unit_room))
    else:
        height = int(width * _ASPECT)
    return width, height


def render_chart_png(
    block: ChartBlock, theme: ReportTheme, width_px: int
) -> tuple[bytes, int, int]:
    """Rasterise ``block`` and return ``(png_bytes, width_px, height_px)``.

    Never returns ``None`` and never raises on the data: unlike the map this needs no asset on
    disk, so the only way it can fail to produce a figure is a programming error, and every
    degenerate input below — no series, one category, a total of zero, every value negative —
    has a defined picture.
    """
    width = max(240, min(2400, int(width_px)))
    scale = width / 900.0
    glyph, small = glyph_scales()

    series, notes = clean_series(block)
    paper = (255, 255, 255)
    ink = rgb_of(theme.ink, (27, 27, 27))
    muted = rgb_of(theme.muted, (90, 107, 135))
    rule = rgb_of(theme.rule, (184, 196, 217))
    accent = rgb_of(theme.accent, (31, 56, 100))

    width, height = chart_pixel_box(block, width_px, len(series))

    canvas = Raster(width, height, paper)

    if not series:
        # A figure the template asked for and the data cannot fill. Drawn as an empty frame with
        # a line of prose in it rather than omitted: an omitted figure looks like a rendering
        # fault, while an empty one that says why is a statement about the record.
        canvas.rect(0, 0, width, height, mix(paper, rule, 0.18))
        message = ellipsise("No values recorded.", int(width * 0.9), glyph)
        canvas.draw_text_centred(
            width // 2, (height - text_height(glyph)) // 2, message, muted, glyph
        )
        return canvas.to_png(), width, height

    if block.kind.is_circular:
        _draw_circular(canvas, block, series, theme, scale, glyph, small)
    elif block.kind is ChartKind.HORIZONTAL_BAR:
        _draw_horizontal_bars(canvas, block, series, theme, scale, glyph, small)
    elif block.kind is ChartKind.LINE:
        _draw_cartesian(canvas, block, series, theme, scale, glyph, small, line=True)
    else:
        _draw_cartesian(canvas, block, series, theme, scale, glyph, small, line=False)

    for index, note in enumerate(notes):
        canvas.draw_text(
            int(4 * scale),
            int(4 * scale + index * (GLYPH_H + 2) * small),
            ellipsise(note, int(width - 8 * scale), small),
            muted,
            small,
        )

    del ink, accent  # kept above for symmetry with the helpers; each of them re-reads the theme
    return canvas.to_png(), width, height


def _axis_bounds(values: list[float]) -> tuple[float, float, float]:
    """``(low, high, step)`` for a value axis that includes zero and steps by a round number.

    ZERO IS ALWAYS INCLUDED. A bar chart whose axis starts at 40 makes a 3% difference look like a
    threefold one, and that is the single most common way a government figure misleads without a
    single wrong number in it.
    """
    high = max([*values, 0.0])
    low = min([*values, 0.0])
    if high == low:
        # Every value is zero. A flat axis of 0 to 1 draws a baseline and nothing above it, which
        # is the truthful picture, and it keeps every division below off zero.
        return 0.0, 1.0, 1.0
    step = _nice_step(high - low, 4)
    high = math.ceil(high / step) * step
    low = math.floor(low / step) * step
    if high == low:
        high = low + step
    return low, high, step


def _draw_cartesian(
    canvas: Raster,
    block: ChartBlock,
    series: list[tuple[str, float]],
    theme: ReportTheme,
    scale: float,
    glyph: int,
    small: int,
    *,
    line: bool,
) -> None:
    """Vertical bars or a line, sharing one axis, one grid and one category strip."""
    paper = (255, 255, 255)
    ink = rgb_of(theme.ink, (27, 27, 27))
    muted = rgb_of(theme.muted, (90, 107, 135))
    rule = rgb_of(theme.rule, (184, 196, 217))
    accent = rgb_of(theme.accent, (31, 56, 100))
    soft = rgb_of(theme.accent_soft, (47, 84, 150))

    values = [v for _label, v in series]
    low, high, step = _axis_bounds(values)
    span = high - low  # positive by construction of _axis_bounds

    axis_labels = []
    tick = low
    while tick <= high + step * 0.001:
        axis_labels.append((tick, format_number(tick)))
        tick += step
    gutter = max(text_width(t, small) for _v, t in axis_labels) + int(8 * scale)

    left = gutter
    right = canvas.width - int(10 * scale)
    top = int(14 * scale) + text_height(glyph)
    bottom = canvas.height - int(10 * scale) - text_height(small) * 2
    if right - left < 40 or bottom - top < 40:
        return

    def y_of(value: float) -> float:
        return bottom - (value - low) / span * (bottom - top)

    for value, text in axis_labels:
        y = y_of(value)
        canvas.rect(
            left, y, right - left, max(1.0, 0.8 * scale), rule, 1.0 if abs(value) < 1e-9 else 0.5
        )
        canvas.draw_text_right(
            left - int(5 * scale), int(y - text_height(small) / 2), text, muted, small
        )

    if block.unit:
        canvas.draw_text(
            int(4 * scale),
            int(2 * scale),
            ellipsise(block.unit, canvas.width // 3, small),
            muted,
            small,
        )

    count = len(series)
    slot = (right - left) / count
    zero_y = y_of(0.0)

    if line:
        points: list[tuple[float, float]] = []
        for index, (_label, value) in enumerate(series):
            points.append((left + slot * (index + 0.5), y_of(value)))
        if len(points) >= 2:
            canvas.stroke_polyline(points, accent, max(1.4, 2.4 * scale))
        for index, (x, y) in enumerate(points):
            canvas.disc(x, y, max(2.0, 4.0 * scale), paper)
            canvas.disc(x, y, max(1.4, 2.8 * scale), accent)
            text = format_number(series[index][1])
            canvas.draw_text_centred(
                int(x), int(y - text_height(small) - 6 * scale), text, ink, small
            )
    else:
        bar_w = slot * 0.62
        for index, (_label, value) in enumerate(series):
            x = left + slot * index + (slot - bar_w) / 2
            y = y_of(value)
            top_y, bar_h = (y, zero_y - y) if value >= 0 else (zero_y, y - zero_y)
            if bar_h < 1.0:
                # A value of exactly zero still gets a visible stub, so the category is not
                # mistaken for one the data omitted entirely.
                top_y, bar_h = zero_y - max(1.0, scale), max(1.0, scale)
            canvas.rect(x, top_y, bar_w, bar_h, soft if value >= 0 else mix(soft, ink, 0.35))
            text = format_number(value)
            label_y = (
                top_y - text_height(small) - 3 * scale if value >= 0 else top_y + bar_h + 3 * scale
            )
            canvas.draw_text_centred(int(x + bar_w / 2), int(label_y), text, ink, small)

    _draw_category_strip(canvas, series, left, right, bottom, scale, small, muted)


def _draw_category_strip(
    canvas: Raster,
    series: list[tuple[str, float]],
    left: int,
    right: int,
    bottom: int,
    scale: float,
    small: int,
    muted: RGB,
) -> None:
    """The category names under a cartesian plot, each ellipsised into its own slot.

    Ellipsising rather than rotating: there is no glyph rotation in the raster, and a rotated
    five-by-seven face drawn by resampling would be unreadable. A truncated category with the
    figure's own caption naming them in full is the better trade.
    """
    slot = (right - left) / len(series)
    y = bottom + int(4 * scale)
    for index, (label, _value) in enumerate(series):
        text = ellipsise(label, int(slot - 4 * scale), small)
        if text:
            canvas.draw_text_centred(int(left + slot * (index + 0.5)), y, text, muted, small)


def _draw_horizontal_bars(
    canvas: Raster,
    block: ChartBlock,
    series: list[tuple[str, float]],
    theme: ReportTheme,
    scale: float,
    glyph: int,
    small: int,
) -> None:
    """Bars running right, with the category name in a left-hand gutter.

    The form to use whenever the categories are words rather than a sequence — cost heads, price
    bands, buyer types. A vertical bar chart of six cost heads truncates every one of them to
    "Mate…", which is the failure ``_draw_category_strip`` can only mitigate.
    """
    ink = rgb_of(theme.ink, (27, 27, 27))
    muted = rgb_of(theme.muted, (90, 107, 135))
    rule = rgb_of(theme.rule, (184, 196, 217))
    soft = rgb_of(theme.accent_soft, (47, 84, 150))

    gutter = min(
        int(canvas.width * 0.34),
        max(
            [
                text_width(ellipsise(label, int(canvas.width * 0.34), small), small)
                for label, _v in series
            ]
            + [int(40 * scale)]
        )
        + int(8 * scale),
    )
    left = gutter
    right = canvas.width - int(10 * scale)
    # The unit sits above the rows rather than beside them, so the first row has to start below
    # it. Without this the unit is drawn straight over the first category's name — and the first
    # cost head, "Material", is the one an officer looks for first.
    top = int(8 * scale) + (text_height(small) + int(3 * scale) if block.unit else 0)
    bottom = canvas.height - int(8 * scale)
    if right - left < 40:
        return

    values = [v for _label, v in series]
    peak = max([abs(v) for v in values] + [0.0])
    # The one division in this function, guarded here rather than at each use. A series of all
    # zeroes gives every bar the same one-pixel stub, which is the honest picture.
    unit = (right - left - int(52 * scale)) / peak if peak > 0 else 0.0

    row = (bottom - top) / len(series)
    bar_h = min(row * 0.62, 22.0 * scale)
    for index, (label, value) in enumerate(series):
        centre = top + row * (index + 0.5)
        y = centre - bar_h / 2
        canvas.rect(
            left, centre - max(0.5, 0.4 * scale), right - left, max(1.0, 0.8 * scale), rule, 0.45
        )
        length = max(1.0, abs(value) * unit)
        canvas.rect(left, y, length, bar_h, soft if value >= 0 else mix(soft, ink, 0.35))
        canvas.draw_text_right(
            left - int(6 * scale),
            int(centre - text_height(small) / 2),
            ellipsise(label, gutter - int(8 * scale), small),
            muted,
            small,
        )
        canvas.draw_text(
            int(left + length + 5 * scale),
            int(centre - text_height(small) / 2),
            format_number(value),
            ink,
            small,
        )

    if block.unit:
        canvas.draw_text(
            int(4 * scale),
            int(1 * scale),
            ellipsise(block.unit, canvas.width // 3, small),
            muted,
            small,
        )
    del glyph


def _draw_circular(
    canvas: Raster,
    block: ChartBlock,
    series: list[tuple[str, float]],
    theme: ReportTheme,
    scale: float,
    glyph: int,
    small: int,
) -> None:
    """A pie or a donut, with a legend naming every slice and its value.

    THE LEGEND IS NOT OPTIONAL. Labels placed on the slices themselves need a leader line for
    anything under about eight percent, and a leader line needs a layout pass this rasteriser does
    not have; a report is not a dashboard and the reader has the page in their hand, so a list
    beside the figure is both cheaper and easier to read off.
    """
    paper = (255, 255, 255)
    ink = rgb_of(theme.ink, (27, 27, 27))
    muted = rgb_of(theme.muted, (90, 107, 135))

    total = sum(value for _label, value in series)
    colours = slice_colours(len(series), theme)

    # THE LEGEND COLUMN IS MEASURED, not guessed at a fraction of the width. It used to be the
    # `min` below alone, which was wide enough only because the labels were being drawn at about
    # 2.5 pt; at a legible size "Revise and resubmit — 1 (17%)" no longer fitted and the figure
    # started ellipsising the name of a review decision, which is the one thing the legend is for.
    # Measured the same way the horizontal-bar chart already measures its label gutter, and capped
    # so the ring cannot be squeezed out by one long category name.
    swatch_room = int(max(6.0, 10.0 * scale)) + int(11 * scale)
    wanted = (
        max(
            (
                text_width(f"{label} — {format_number(value)} (100%)", small)
                for label, value in series
            ),
            default=0,
        )
        + swatch_room
    )
    # A label longer than the cap still elides, and the ellipsis falls at the END — so the
    # category NAME, which is what a reader matches to a slice, survives and the count beside it
    # is the first thing to go. It is also in the table this figure sits under.
    legend_w = max(
        min(int(canvas.width * 0.46), int(canvas.width * 0.30) + int(120 * scale)),
        min(wanted, int(canvas.width * 0.52)),
    )
    plot_w = canvas.width - legend_w
    radius = min(plot_w, canvas.height) * 0.40
    cx = plot_w / 2
    cy = canvas.height / 2
    inner = radius * 0.55 if block.kind is ChartKind.DONUT else 0.0

    if total <= 0:
        # NOTHING IS DIVIDED BY THE TOTAL BELOW THIS LINE unless it is positive. An empty ring
        # with the categories still listed says "these heads exist and all of them are zero",
        # which is a real state of a cost sheet at the start of a workshop.
        canvas.ring(cx, cy, radius, max(inner, radius * 0.55), mix(paper, muted, 0.22))
        canvas.draw_text_centred(int(cx), int(cy - text_height(small) / 2), "0", muted, small)
    else:
        # Start at twelve o'clock and run clockwise, which is how every reader of a printed pie
        # expects to find the first category. Screen angles grow anticlockwise from three
        # o'clock, hence the negative sweep accumulated from -90 degrees.
        angle = -math.pi / 2
        for (label, value), colour in zip(series, colours):
            sweep = value / total * math.tau
            if sweep <= 0:
                continue
            canvas.ring(cx, cy, radius, inner, colour, start=angle, sweep=sweep)
            angle += sweep
            del label
        if block.kind is ChartKind.DONUT:
            canvas.disc(cx, cy, inner, paper)
            headline = format_number(total)
            canvas.draw_text_centred(
                int(cx),
                int(cy - text_height(glyph) / 2),
                ellipsise(headline, int(inner * 1.7), glyph),
                ink,
                glyph,
            )

    swatch = max(6.0, 10.0 * scale)
    line_h = max(text_height(small) + 6 * scale, 16 * scale)
    block_h = line_h * len(series)
    y = max(6.0 * scale, (canvas.height - block_h) / 2)
    x = plot_w + 6 * scale
    for (label, value), colour in zip(series, colours):
        if y + line_h > canvas.height:
            break
        canvas.rect(x, y + (line_h - swatch) / 2, swatch, swatch, colour)
        share = f" ({value / total * 100:.0f}%)" if total > 0 else ""
        text = f"{label} — {format_number(value)}{share}"
        text = ellipsise(text, int(canvas.width - x - swatch - 10 * scale), small)
        canvas.draw_text(
            int(x + swatch + 5 * scale),
            int(y + (line_h - text_height(small)) / 2),
            text,
            muted,
            small,
        )
        y += line_h
