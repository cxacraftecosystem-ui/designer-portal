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


def _group_indian(digits: str) -> str:
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
    if span <= 0 or target_ticks <= 0:
        return 1.0
    raw = span / target_ticks
    power = 10.0 ** math.floor(math.log10(raw)) if raw > 0 else 1.0
    for multiplier in (1.0, 2.0, 2.5, 5.0, 10.0):
        if raw <= multiplier * power:
            return multiplier * power
    return 10.0 * power



def slice_colours(count: int, theme: ReportTheme) -> list[RGB]:
    accent = rgb_of(theme.accent, (31, 56, 100))
    soft = rgb_of(theme.accent_soft, (47, 84, 150))
    if count <= 1:
        return [accent]
    out: list[RGB] = []
    for index in range(count):
        position = index / (count - 1)
        if position <= 0.5:
            out.append(mix(accent, soft, position * 2))
        else:
            out.append(mix(soft, (255, 255, 255), (position - 0.5) * 1.55))
    return out


_ASPECT = 0.62


def clean_series(block: ChartBlock) -> tuple[list[tuple[str, float]], list[str]]:
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


_LABEL_PT = 8.0
_SMALL_PT = 7.0


def glyph_scales() -> tuple[int, int]:
    return (
        max(2, round(_LABEL_PT * RENDER_DPI / 72.0 / GLYPH_H)),
        max(1, round(_SMALL_PT * RENDER_DPI / 72.0 / GLYPH_H)),
    )


def chart_pixel_box(block: ChartBlock, width_px: int, rows: int) -> tuple[int, int]:
    width = max(240, min(2400, int(width_px)))
    scale = width / 900.0
    _label, small = glyph_scales()
    if block.kind is ChartKind.HORIZONTAL_BAR:
        row = max(18.0, 26.0 * scale)
        unit_room = (text_height(small) + 3 * scale) if block.unit else 0.0
        height = int(max(row * 3, row * max(1, rows) + 24 * scale + unit_room))
    else:
        height = int(width * _ASPECT)
    return width, height


def render_chart_png(
    block: ChartBlock, theme: ReportTheme, width_px: int
) -> tuple[bytes, int, int]:
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

    del ink, accent
    return canvas.to_png(), width, height


def _axis_bounds(values: list[float]) -> tuple[float, float, float]:
    high = max([*values, 0.0])
    low = min([*values, 0.0])
    if high == low:
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
    paper = (255, 255, 255)
    ink = rgb_of(theme.ink, (27, 27, 27))
    muted = rgb_of(theme.muted, (90, 107, 135))
    rule = rgb_of(theme.rule, (184, 196, 217))
    accent = rgb_of(theme.accent, (31, 56, 100))
    soft = rgb_of(theme.accent_soft, (47, 84, 150))

    values = [v for _label, v in series]
    low, high, step = _axis_bounds(values)
    span = high - low

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
    top = int(8 * scale) + (text_height(small) + int(3 * scale) if block.unit else 0)
    bottom = canvas.height - int(8 * scale)
    if right - left < 40:
        return

    values = [v for _label, v in series]
    peak = max([abs(v) for v in values] + [0.0])
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
    paper = (255, 255, 255)
    ink = rgb_of(theme.ink, (27, 27, 27))
    muted = rgb_of(theme.muted, (90, 107, 135))

    total = sum(value for _label, value in series)
    colours = slice_colours(len(series), theme)

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
        canvas.ring(cx, cy, radius, max(inner, radius * 0.55), mix(paper, muted, 0.22))
        canvas.draw_text_centred(int(cx), int(cy - text_height(small) / 2), "0", muted, small)
    else:
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
