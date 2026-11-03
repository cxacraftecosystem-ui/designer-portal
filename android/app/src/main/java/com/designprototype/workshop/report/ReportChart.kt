package com.designprototype.workshop.report

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

internal fun groupIndian(digits: String): String {
    if (digits.length <= 3) return digits
    var head = digits.substring(0, digits.length - 3)
    val tail = digits.substring(digits.length - 3)
    val parts = ArrayList<String>()
    while (head.length > 2) {
        parts.add(0, head.substring(head.length - 2))
        head = head.substring(0, head.length - 2)
    }
    if (head.isNotEmpty()) parts.add(0, head)
    parts.add(tail)
    return parts.joinToString(",")
}

private fun fixed(value: Double, places: Int): String =
    BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString()

internal fun formatNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "-"
    val sign = if (value < 0) "-" else ""
    val magnitude = abs(value)
    if (magnitude >= 1000 || magnitude == floor(magnitude)) {
        return sign + groupIndian(fixed(magnitude, 0))
    }
    val text = fixed(magnitude, 2).trimEnd('0').trimEnd('.')
    return sign + text
}

internal fun niceStep(span: Double, targetTicks: Int): Double {
    if (span <= 0 || targetTicks <= 0) return 1.0
    val raw = span / targetTicks
    val power = if (raw > 0) Math.pow(10.0, floor(log10(raw))) else 1.0
    for (multiplier in doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)) {
        if (raw <= multiplier * power) return multiplier * power
    }
    return 10.0 * power
}

internal fun sliceColours(count: Int, theme: ReportTheme): List<Rgb> {
    val accent = rgbOf(theme.accent, rgb(31, 56, 100))
    val soft = rgbOf(theme.accentSoft, rgb(47, 84, 150))
    if (count <= 1) return listOf(accent)
    val out = ArrayList<Rgb>(count)
    for (index in 0 until count) {
        val position = index.toDouble() / (count - 1)
        if (position <= 0.5) {
            out.add(mix(accent, soft, position * 2))
        } else {
            out.add(mix(soft, PAPER, (position - 0.5) * 1.55))
        }
    }
    return out
}

private const val ASPECT = 0.62

private const val LABEL_PT = 8.0
private const val SMALL_PT = 7.0

internal fun glyphScales(): Pair<Int, Int> = Pair(
    max(2, Math.rint(LABEL_PT * RENDER_DPI / 72.0 / GLYPH_H).toInt()),
    max(1, Math.rint(SMALL_PT * RENDER_DPI / 72.0 / GLYPH_H).toInt()),
)

internal fun cleanSeries(block: ChartBlock): Pair<List<Pair<String, Double>>, List<String>> {
    val kept = ArrayList<Pair<String, Double>>(block.series.size)
    val dropped = ArrayList<String>()
    for ((label, value) in block.series) {
        if (value.isNaN() || value.isInfinite()) {
            dropped.add(label)
            continue
        }
        if (block.kind.isCircular && value < 0) {
            dropped.add(label)
            continue
        }
        kept.add(label to value)
    }
    val notes = ArrayList<String>()
    if (dropped.isNotEmpty()) {
        notes.add(
            "Not shown: " + dropped.take(6).joinToString(", ") + (if (dropped.size > 6) "…" else "")
        )
    }
    return kept to notes
}

internal fun chartPixelBox(block: ChartBlock, widthPx: Int, rows: Int): Pair<Int, Int> {
    val width = max(240, min(2400, widthPx))
    val scale = width / 900.0
    val (_, small) = glyphScales()
    val height: Int
    if (block.kind == ChartKind.HORIZONTAL_BAR) {
        val row = max(18.0, 26.0 * scale)
        val unitRoom = if (block.unit.isNotEmpty()) textHeight(small) + 3 * scale else 0.0
        height = max(row * 3, row * max(1, rows) + 24 * scale + unitRoom).toInt()
    } else {
        height = (width * ASPECT).toInt()
    }
    return width to height
}

internal fun renderChartPng(block: ChartBlock, theme: ReportTheme, widthPx: Int): RasterFigure {
    val (series, notes) = cleanSeries(block)
    val muted = rgbOf(theme.muted, rgb(90, 107, 135))
    val rule = rgbOf(theme.rule, rgb(184, 196, 217))

    val (width, height) = chartPixelBox(block, widthPx, series.size)
    val scale = width / 900.0
    val (glyph, small) = glyphScales()

    val canvas = Raster(width, height, PAPER)

    if (series.isEmpty()) {
        canvas.rect(0.0, 0.0, width.toDouble(), height.toDouble(), mix(PAPER, rule, 0.18))
        val message = ellipsise("No values recorded.", (width * 0.9).toInt(), glyph)
        canvas.drawTextCentred(width / 2, (height - textHeight(glyph)) / 2, message, muted, glyph)
        return RasterFigure(canvas.toPng(), width, height)
    }

    when {
        block.kind.isCircular -> drawCircular(canvas, block, series, theme, scale, glyph, small)
        block.kind == ChartKind.HORIZONTAL_BAR ->
            drawHorizontalBars(canvas, block, series, theme, scale, small)
        block.kind == ChartKind.LINE ->
            drawCartesian(canvas, block, series, theme, scale, glyph, small, line = true)
        else -> drawCartesian(canvas, block, series, theme, scale, glyph, small, line = false)
    }

    for ((index, note) in notes.withIndex()) {
        canvas.drawText(
            (4 * scale).toInt(),
            (4 * scale + index * (GLYPH_H + 2) * small).toInt(),
            ellipsise(note, (width - 8 * scale).toInt(), small), muted, small,
        )
    }
    return RasterFigure(canvas.toPng(), width, height)
}

internal fun axisBounds(values: List<Double>): Triple<Double, Double, Double> {
    var high = 0.0
    var low = 0.0
    for (v in values) {
        if (v > high) high = v
        if (v < low) low = v
    }
    if (high == low) {
        return Triple(0.0, 1.0, 1.0)
    }
    val step = niceStep(high - low, 4)
    high = ceil(high / step) * step
    low = floor(low / step) * step
    if (high == low) high = low + step
    return Triple(low, high, step)
}

private fun drawCartesian(
    canvas: Raster,
    block: ChartBlock,
    series: List<Pair<String, Double>>,
    theme: ReportTheme,
    scale: Double,
    glyph: Int,
    small: Int,
    line: Boolean,
) {
    val ink = rgbOf(theme.ink, rgb(27, 27, 27))
    val muted = rgbOf(theme.muted, rgb(90, 107, 135))
    val rule = rgbOf(theme.rule, rgb(184, 196, 217))
    val accent = rgbOf(theme.accent, rgb(31, 56, 100))
    val soft = rgbOf(theme.accentSoft, rgb(47, 84, 150))

    val (low, high, step) = axisBounds(series.map { it.second })
    val span = high - low

    val axisLabels = ArrayList<Pair<Double, String>>()
    var tick = low
    while (tick <= high + step * 0.001) {
        axisLabels.add(tick to formatNumber(tick))
        tick += step
    }
    val gutter = axisLabels.maxOf { textWidth(it.second, small) } + (8 * scale).toInt()

    val left = gutter
    val right = canvas.width - (10 * scale).toInt()
    val top = (14 * scale).toInt() + textHeight(glyph)
    val bottom = canvas.height - (10 * scale).toInt() - textHeight(small) * 2
    if (right - left < 40 || bottom - top < 40) return

    fun yOf(value: Double): Double = bottom - (value - low) / span * (bottom - top)

    for ((value, text) in axisLabels) {
        val y = yOf(value)
        canvas.rect(
            left.toDouble(), y, (right - left).toDouble(), max(1.0, 0.8 * scale), rule,
            if (abs(value) < 1e-9) 1.0 else 0.5,
        )
        canvas.drawTextRight(
            left - (5 * scale).toInt(), (y - textHeight(small) / 2.0).toInt(), text, muted, small,
        )
    }

    if (block.unit.isNotEmpty()) {
        canvas.drawText(
            (4 * scale).toInt(), (2 * scale).toInt(),
            ellipsise(block.unit, canvas.width / 3, small), muted, small,
        )
    }

    val count = series.size
    val slot = (right - left).toDouble() / count
    val zeroY = yOf(0.0)

    if (line) {
        val points = DoubleArray(count * 2)
        for (index in 0 until count) {
            points[index * 2] = left + slot * (index + 0.5)
            points[index * 2 + 1] = yOf(series[index].second)
        }
        if (count >= 2) canvas.strokePolyline(points, accent, max(1.4, 2.4 * scale))
        for (index in 0 until count) {
            val x = points[index * 2]
            val y = points[index * 2 + 1]
            canvas.disc(x, y, max(2.0, 4.0 * scale), PAPER)
            canvas.disc(x, y, max(1.4, 2.8 * scale), accent)
            canvas.drawTextCentred(
                x.toInt(), (y - textHeight(small) - 6 * scale).toInt(),
                formatNumber(series[index].second), ink, small,
            )
        }
    } else {
        val barW = slot * 0.62
        for (index in 0 until count) {
            val value = series[index].second
            val x = left + slot * index + (slot - barW) / 2
            val y = yOf(value)
            var topY = if (value >= 0) y else zeroY
            var barH = if (value >= 0) zeroY - y else y - zeroY
            if (barH < 1.0) {
                barH = max(1.0, scale)
                topY = zeroY - barH
            }
            canvas.rect(x, topY, barW, barH, if (value >= 0) soft else mix(soft, ink, 0.35))
            val labelY =
                if (value >= 0) topY - textHeight(small) - 3 * scale else topY + barH + 3 * scale
            canvas.drawTextCentred(
                (x + barW / 2).toInt(), labelY.toInt(), formatNumber(value), ink, small,
            )
        }
    }

    drawCategoryStrip(canvas, series, left, right, bottom, scale, small, muted)
}

private fun drawCategoryStrip(
    canvas: Raster,
    series: List<Pair<String, Double>>,
    left: Int,
    right: Int,
    bottom: Int,
    scale: Double,
    small: Int,
    muted: Rgb,
) {
    val slot = (right - left).toDouble() / series.size
    val y = bottom + (4 * scale).toInt()
    for (index in series.indices) {
        val text = ellipsise(series[index].first, (slot - 4 * scale).toInt(), small)
        if (text.isNotEmpty()) {
            canvas.drawTextCentred((left + slot * (index + 0.5)).toInt(), y, text, muted, small)
        }
    }
}

private fun drawHorizontalBars(
    canvas: Raster,
    block: ChartBlock,
    series: List<Pair<String, Double>>,
    theme: ReportTheme,
    scale: Double,
    small: Int,
) {
    val ink = rgbOf(theme.ink, rgb(27, 27, 27))
    val muted = rgbOf(theme.muted, rgb(90, 107, 135))
    val rule = rgbOf(theme.rule, rgb(184, 196, 217))
    val soft = rgbOf(theme.accentSoft, rgb(47, 84, 150))

    val cap = (canvas.width * 0.34).toInt()
    var widest = (40 * scale).toInt()
    for ((label, _) in series) {
        val w = textWidth(ellipsise(label, cap, small), small)
        if (w > widest) widest = w
    }
    val gutter = min(cap, widest + (8 * scale).toInt())
    val left = gutter
    val right = canvas.width - (10 * scale).toInt()
    val top = (8 * scale).toInt() + (if (block.unit.isNotEmpty()) textHeight(small) + (3 * scale).toInt() else 0)
    val bottom = canvas.height - (8 * scale).toInt()
    if (right - left < 40) return

    var peak = 0.0
    for ((_, value) in series) if (abs(value) > peak) peak = abs(value)
    val unit = if (peak > 0) (right - left - (52 * scale).toInt()) / peak else 0.0

    val row = (bottom - top).toDouble() / series.size
    val barH = min(row * 0.62, 22.0 * scale)
    for (index in series.indices) {
        val (label, value) = series[index]
        val centre = top + row * (index + 0.5)
        val y = centre - barH / 2
        canvas.rect(
            left.toDouble(), centre - max(0.5, 0.4 * scale), (right - left).toDouble(),
            max(1.0, 0.8 * scale), rule, 0.45,
        )
        val length = max(1.0, abs(value) * unit)
        canvas.rect(left.toDouble(), y, length, barH, if (value >= 0) soft else mix(soft, ink, 0.35))
        canvas.drawTextRight(
            left - (6 * scale).toInt(), (centre - textHeight(small) / 2.0).toInt(),
            ellipsise(label, gutter - (8 * scale).toInt(), small), muted, small,
        )
        canvas.drawText(
            (left + length + 5 * scale).toInt(), (centre - textHeight(small) / 2.0).toInt(),
            formatNumber(value), ink, small,
        )
    }

    if (block.unit.isNotEmpty()) {
        canvas.drawText(
            (4 * scale).toInt(), (1 * scale).toInt(),
            ellipsise(block.unit, canvas.width / 3, small), muted, small,
        )
    }
}

private fun drawCircular(
    canvas: Raster,
    block: ChartBlock,
    series: List<Pair<String, Double>>,
    theme: ReportTheme,
    scale: Double,
    glyph: Int,
    small: Int,
) {
    val ink = rgbOf(theme.ink, rgb(27, 27, 27))
    val muted = rgbOf(theme.muted, rgb(90, 107, 135))

    var total = 0.0
    for ((_, value) in series) total += value
    val colours = sliceColours(series.size, theme)

    val legendW = min((canvas.width * 0.46).toInt(), (canvas.width * 0.30).toInt() + (120 * scale).toInt())
    val plotW = canvas.width - legendW
    val radius = min(plotW, canvas.height) * 0.40
    val cx = plotW / 2.0
    val cy = canvas.height / 2.0
    val inner = if (block.kind == ChartKind.DONUT) radius * 0.55 else 0.0

    if (total <= 0) {
        canvas.ring(cx, cy, radius, max(inner, radius * 0.55), mix(PAPER, muted, 0.22))
        canvas.drawTextCentred(cx.toInt(), (cy - textHeight(small) / 2.0).toInt(), "0", muted, small)
    } else {
        var angle = -Math.PI / 2
        for (index in series.indices) {
            val sweep = series[index].second / total * TAU
            if (sweep <= 0) continue
            canvas.ring(cx, cy, radius, inner, colours[index], start = angle, sweep = sweep)
            angle += sweep
        }
        if (block.kind == ChartKind.DONUT) {
            canvas.disc(cx, cy, inner, PAPER)
            canvas.drawTextCentred(
                cx.toInt(), (cy - textHeight(glyph) / 2.0).toInt(),
                ellipsise(formatNumber(total), (inner * 1.7).toInt(), glyph), ink, glyph,
            )
        }
    }

    val swatch = max(6.0, 10.0 * scale)
    val lineH = max(textHeight(small) + 6 * scale, 16 * scale)
    val blockH = lineH * series.size
    var y = max(6.0 * scale, (canvas.height - blockH) / 2)
    val x = plotW + 6 * scale
    for (index in series.indices) {
        val (label, value) = series[index]
        if (y + lineH > canvas.height) break
        canvas.rect(x, y + (lineH - swatch) / 2, swatch, swatch, colours[index])
        val share = if (total > 0) " (" + fixed(value / total * 100, 0) + "%)" else ""
        val text = ellipsise(
            "$label — ${formatNumber(value)}$share",
            (canvas.width - x - swatch - 10 * scale).toInt(), small,
        )
        canvas.drawText(
            (x + swatch + 5 * scale).toInt(), (y + (lineH - textHeight(small)) / 2).toInt(),
            text, muted, small,
        )
        y += lineH
    }
}
