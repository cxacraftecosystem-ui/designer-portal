package com.designprototype.workshop.report

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/*
 * The report's infographics: one [ChartBlock] to one PNG — a port of
 * `backend/app/services/report_chart.py`, drawn through [Raster] so the server's figure and the
 * phone's are the same picture rather than two pictures of the same numbers.
 *
 * The figures the source document asks stage 18 and stage 17 for — how many designs, prototypes by
 * review decision, what a piece cost by head, which price band the range fell in, what adoption
 * looked like at three, six and twelve months — are all the same shape: ONE measure over a set of
 * categories. [ChartBlock] says so, and this file draws the five forms that shape can usefully take.
 *
 * WHY IT RASTERISES, and why that is not a compromise. Drawing a bar chart into OOXML means either a
 * `c:chart` part (a second XML schema, a second relationship graph, and a picture Word renders from
 * live data the reader can edit) or a table of shaded cells pretending to be bars. Drawing one into a
 * PDF here means Canvas paths whose anti-aliasing is Skia's and not the server's. Four
 * implementations of one figure is four chances for the .docx and the .pdf of one workshop to
 * disagree about the same number. Rasterising once and handing the PNG to the picture path each
 * renderer already has is one implementation and one answer.
 *
 * WHAT IS IN THE PNG AND WHAT IS NOT. Axis numbers, category labels and value labels are in it,
 * because they only mean anything where they are placed. The block's TITLE and CAPTION are not:
 * those are printed by [DocxWriter] and [PdfWriter] as real text, which is what lets a caption carry
 * Odia or Devanagari. The five-by-seven face in [Raster] cannot draw an Indic script and drops what
 * it cannot draw, so a title baked into the picture would silently lose a craft's local name — see
 * that file's note on the font.
 *
 * TWO NUMERICAL RULES, both of which are the difference between a wrong figure and no figure:
 *
 * A ZERO TOTAL DOES NOT DIVIDE. A workshop whose follow-up recorded no units, or whose cost sheet is
 * all zeroes, is an ordinary early-stage record. Every proportion below is taken only after the total
 * is known to be positive, and a circular chart with nothing in it draws an empty ring and says so
 * rather than throwing inside an export a designer is waiting on in a field.
 *
 * A NEGATIVE VALUE CANNOT BE A SLICE. A pie of a negative margin is not a smaller slice, it is a
 * meaningless one, so the circular kinds drop negatives and name them under the figure. The bar and
 * line kinds keep them and give the axis a baseline, because "the follow-up at twelve months was
 * worse than at six" is exactly the finding that figure exists to show.
 */

// --------------------------------------------------------------------------------------
// Numbers, as a figure prints them
// --------------------------------------------------------------------------------------

/**
 * 12,34,567 rather than 1,234,567.
 *
 * The same grouping the report's cost tables apply to every money value, repeated here rather than
 * shared because a chart axis that grouped Western while the cost table beside it grouped Indian
 * would read as two different numbers to an officer checking one against the other.
 */
private fun groupIndian(digits: String): String {
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

/**
 * A double rendered exactly as Python's `f"{value:.<places>f}"` renders it.
 *
 * NOT `String.format("%.2f", value)`. Java's `Formatter` rounds HALF_UP and Python rounds HALF_EVEN,
 * so a cost of 1500.5 prints as "1,501" on the phone and "1,500" on the server — one figure, two
 * numbers, in two documents for the same workshop. Constructing the [BigDecimal] from the double
 * rather than from its string form is equally deliberate: it takes the EXACT binary value, which is
 * the value Python's formatter is also looking at, so the two agree on which side of a tie a
 * representable-looking number such as 2.675 actually falls.
 */
private fun fixed(value: Double, places: Int): String =
    BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString()

/**
 * A value as a figure prints it: no trailing zeros, grouped once it is long enough.
 *
 * Deliberately NOT the `4.2 L` / `1.3 Cr` abbreviation a dashboard would use. This figure is read
 * beside a cost table that prints the full rupee amount, and an officer comparing the two must not
 * have to convert between them to see they agree.
 */
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

/**
 * A round axis step covering [span] in roughly [targetTicks] steps.
 *
 * 1, 2, 2.5 or 5 times a power of ten — the four multipliers a reader adds up in their head. An axis
 * stepping by 7 is legible and useless: nobody reads the third gridline as 21.
 */
internal fun niceStep(span: Double, targetTicks: Int): Double {
    if (span <= 0 || targetTicks <= 0) return 1.0
    val raw = span / targetTicks
    val power = if (raw > 0) Math.pow(10.0, floor(log10(raw))) else 1.0
    for (multiplier in doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)) {
        if (raw <= multiplier * power) return multiplier * power
    }
    return 10.0 * power
}

// --------------------------------------------------------------------------------------
// Colour
// --------------------------------------------------------------------------------------

/**
 * A ramp from the theme's accent to a pale wash of it, one step per slice.
 *
 * A MONOCHROME RAMP, not a categorical palette, and the reason is the photocopier. Every report this
 * app generates is printed, copied and filed at least once; a hue-based palette collapses to four
 * indistinguishable greys the first time that happens, and the legend then names four slices a reader
 * cannot tell apart. A lightness ramp survives the copy, and it also keeps the figure inside whichever
 * of the four template themes is in force instead of importing a fifth colour scheme into the
 * document.
 *
 * INTERNAL rather than private because [DocxWriter] paints its native chart's `c:dPt` slices from
 * this exact list. A second ramp over there would give the .docx's editable pie one set of colours
 * and the .pdf's picture of the same pie another, for the same workshop on the same day.
 */
internal fun sliceColours(count: Int, theme: ReportTheme): List<Rgb> {
    val accent = rgbOf(theme.accent, rgb(31, 56, 100))
    val soft = rgbOf(theme.accentSoft, rgb(47, 84, 150))
    if (count <= 1) return listOf(accent)
    val out = ArrayList<Rgb>(count)
    for (index in 0 until count) {
        val position = index.toDouble() / (count - 1)
        // Through accentSoft at the midpoint, so the ramp uses both colours the template chose rather
        // than fading one of them out.
        if (position <= 0.5) {
            out.add(mix(accent, soft, position * 2))
        } else {
            out.add(mix(soft, PAPER, (position - 0.5) * 1.55))
        }
    }
    return out
}

// --------------------------------------------------------------------------------------
// The renderer
// --------------------------------------------------------------------------------------

/**
 * Aspect ratio of the plot for the kinds whose height does not depend on the row count.
 *
 * Close to the golden ratio: tall enough that a small difference between two bars is visible, short
 * enough that a figure and the paragraph introducing it fit on one page together.
 */
private const val ASPECT = 0.62

/**
 * What a chart label is meant to MEASURE on the printed page, in points.
 *
 * [DocxWriter] emits the same three charts as native DrawingML with every text element at `sz="800"`
 * — 8 pt — so these are the numbers the two files have to agree on. This rasteriser sized its glyphs
 * from a magic `width / 900` instead, and on A4 with a 25 mm margin a 74 %-wide figure is 932 px,
 * which gave `rint(1.0356 * 1.9) = 2` and a `small` of 1: a 5x7 bitmap font 0.89 mm tall, about
 * 2.5 pt, for the donut legend, the category labels, the axis ticks and the data values alike. Body
 * text in the same PDF is 10.5 pt. The two files a designer submits together disagreed about whether
 * the figures could be read at all.
 */
private const val LABEL_PT = 8.0
private const val SMALL_PT = 7.0

/**
 * The (label, small) glyph multipliers, derived from the point sizes above.
 *
 * ONE FUNCTION because [chartPixelBox] and [renderChartPng] both need them and must agree: the first
 * sizes the frame the .docx draws its vector chart into, the second draws the .pdf's picture, and a
 * disagreement makes the same figure a different SHAPE in the two files.
 *
 * The raster's glyph is [GLYPH_H] pixels tall at [RENDER_DPI], so the smallest step this font can
 * express is 7 × 72 / 200 = 2.52 pt and both targets round to the same multiplier at the moment. That
 * is not a mistake to correct later: the .docx sets EVERY chart text element to one size, so landing
 * on one size here is parity rather than a loss of hierarchy. The two constants stay separate because
 * the DPI is not a promise.
 *
 * `rint` and not `Math.round`: this is the port of `report_chart.glyph_scales`, Python's `round` is
 * half-to-even, and a half-up rounding here would silently pick a different multiplier the first time
 * a point size or a DPI landed on a tie — one figure, two sizes, in the two documents of one
 * workshop. Change this file and `backend/app/services/report_chart.py` together, or they disagree.
 */
internal fun glyphScales(): Pair<Int, Int> = Pair(
    max(2, Math.rint(LABEL_PT * RENDER_DPI / 72.0 / GLYPH_H).toInt()),
    max(1, Math.rint(SMALL_PT * RENDER_DPI / 72.0 / GLYPH_H).toInt()),
)

/**
 * The series with unusable entries removed, and the note that says what was removed.
 *
 * The server also catches a value that is not a number at all, because its series arrives out of a
 * JSON column and can hold a string. Here the model has already made that impossible — [ChartBlock]
 * carries Doubles — so the only survivors of that arm are NaN and infinity, which a division by a
 * zero denominator upstream produces just as easily on this surface as on the other. They are dropped
 * rather than drawn: an infinite bar has no height the axis can express, and NaN compares false
 * against every bound, so it would silently become a bar of zero beside real ones.
 *
 * INTERNAL rather than private because the native Word chart in [DocxWriter] must plot exactly
 * these points and print exactly this note. If it filtered for itself, a NaN that this function
 * drops and that one keeps would put a category in the .docx's editable chart that is missing from
 * the .pdf's picture of it.
 */
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

/**
 * Rasterise [block] and return the PNG with the pixel size it was drawn at.
 *
 * Never returns null and never throws on the data: unlike [renderMapPng] this needs no asset on disk,
 * so the only way it can fail to produce a figure is a programming error, and every degenerate input
 * below — no series, one category, a total of zero, every value negative — has a defined picture.
 */
/**
 * The pixel box a chart of [rows] categories occupies at a requested width.
 *
 * INTERNAL, and the only place the answer is computed. The native Word chart [DocxWriter] writes has
 * no bitmap and so no intrinsic size at all: it is drawn into whatever frame the drawing gives it.
 * Deriving that frame's aspect from here is what stops the .docx's vector figure being a different
 * SHAPE from the .pdf's picture of the same figure — two documents of one workshop where the cost
 * chart is half a page on one and a strip on the other, which reads as two different reports.
 */
internal fun chartPixelBox(block: ChartBlock, widthPx: Int, rows: Int): Pair<Int, Int> {
    val width = max(240, min(2400, widthPx))
    val scale = width / 900.0
    val (_, small) = glyphScales()
    val height: Int
    if (block.kind == ChartKind.HORIZONTAL_BAR) {
        // Height follows the row count instead of an aspect ratio. Six cost heads in a box shaped
        // like the bar chart above would be six hairlines with the labels on top of each other.
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
        // A figure the template asked for and the data cannot fill. Drawn as an empty frame with a
        // line of prose in it rather than omitted: an omitted figure looks like a rendering fault,
        // while an empty one that says why is a statement about the record.
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

/**
 * `(low, high, step)` for a value axis that includes zero and steps by a round number.
 *
 * ZERO IS ALWAYS INCLUDED. A bar chart whose axis starts at 40 makes a 3% difference look like a
 * threefold one, and that is the single most common way a government figure misleads without a single
 * wrong number in it.
 */
internal fun axisBounds(values: List<Double>): Triple<Double, Double, Double> {
    var high = 0.0
    var low = 0.0
    for (v in values) {
        if (v > high) high = v
        if (v < low) low = v
    }
    if (high == low) {
        // Every value is zero. A flat axis of 0 to 1 draws a baseline and nothing above it, which is
        // the truthful picture, and it keeps every division below off zero.
        return Triple(0.0, 1.0, 1.0)
    }
    val step = niceStep(high - low, 4)
    high = ceil(high / step) * step
    low = floor(low / step) * step
    if (high == low) high = low + step
    return Triple(low, high, step)
}

/** Vertical bars or a line, sharing one axis, one grid and one category strip. */
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
    val span = high - low // positive by construction of axisBounds

    // The ticks are accumulated by repeated addition rather than by `low + i * step`, exactly as the
    // server does it. The two differ in the last bits once a few dozen ticks have gone by, and a tick
    // that lands a hair the other side of the `<=` is a whole extra gridline on one surface only.
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
    // Below this there is no plot left, only labels overprinting one another. Drawing nothing leaves
    // the empty frame the caller already painted, which is legible; drawing anyway is a smear.
    if (right - left < 40 || bottom - top < 40) return

    fun yOf(value: Double): Double = bottom - (value - low) / span * (bottom - top)

    for ((value, text) in axisLabels) {
        val y = yOf(value)
        // The zero line at full strength and every other gridline at half: a reader needs to find the
        // baseline instantly on a chart that has negative values, and on one that does not the zero
        // line is the axis itself.
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
            // A paper disc under the accent one, so a marker sitting on the gridline it happens to
            // equal is still a marker rather than a thickening of the line.
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
                // A value of exactly zero still gets a visible stub, so the category is not mistaken
                // for one the data omitted entirely.
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

/**
 * The category names under a cartesian plot, each ellipsised into its own slot.
 *
 * Ellipsising rather than rotating: there is no glyph rotation in [Raster], and a rotated five-by-
 * seven face drawn by resampling would be unreadable. A truncated category with the figure's own
 * caption naming them in full is the better trade.
 */
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

/**
 * Bars running right, with the category name in a left-hand gutter.
 *
 * The form to use whenever the categories are words rather than a sequence — cost heads, price bands,
 * buyer types. A vertical bar chart of six cost heads truncates every one of them to "Mate…", which is
 * the failure [drawCategoryStrip] can only mitigate.
 */
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
    // The unit sits above the rows rather than beside them, so the first row has to start below it.
    // Without this the unit is drawn straight over the first category's name — and the first cost
    // head, "Material", is the one an officer looks for first.
    val top = (8 * scale).toInt() + (if (block.unit.isNotEmpty()) textHeight(small) + (3 * scale).toInt() else 0)
    val bottom = canvas.height - (8 * scale).toInt()
    if (right - left < 40) return

    var peak = 0.0
    for ((_, value) in series) if (abs(value) > peak) peak = abs(value)
    // The one division in this function, guarded here rather than at each use. A series of all zeroes
    // gives every bar the same one-pixel stub, which is the honest picture.
    val unit = if (peak > 0) (right - left - (52 * scale).toInt()) / peak else 0.0

    val row = (bottom - top).toDouble() / series.size
    val barH = min(row * 0.62, 22.0 * scale)
    for (index in series.indices) {
        val (label, value) = series[index]
        val centre = top + row * (index + 0.5)
        val y = centre - barH / 2
        // A hairline through every row, so a bar of nearly zero still has a row a reader's eye can
        // follow from the category name across to the value printed at its end.
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

/**
 * A pie or a donut, with a legend naming every slice and its value.
 *
 * THE LEGEND IS NOT OPTIONAL. Labels placed on the slices themselves need a leader line for anything
 * under about eight percent, and a leader line needs a layout pass this rasteriser does not have; a
 * report is not a dashboard and the reader has the page in their hand, so a list beside the figure is
 * both cheaper and easier to read off.
 */
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

    // Summed left to right in Double, as the server sums it. Reordering or pairwise-summing would
    // give a different last bit, and the total is the denominator of every slice angle below.
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
        // NOTHING IS DIVIDED BY THE TOTAL BELOW THIS LINE unless it is positive. An empty ring with
        // the categories still listed says "these heads exist and all of them are zero", which is a
        // real state of a cost sheet at the start of a workshop.
        canvas.ring(cx, cy, radius, max(inner, radius * 0.55), mix(PAPER, muted, 0.22))
        canvas.drawTextCentred(cx.toInt(), (cy - textHeight(small) / 2.0).toInt(), "0", muted, small)
    } else {
        // Start at twelve o'clock and run clockwise, which is how every reader of a printed pie
        // expects to find the first category. Screen angles grow anticlockwise from three o'clock,
        // hence the negative sweep accumulated from -90 degrees.
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
        // A legend entry that would run off the bottom is DROPPED rather than drawn over the edge.
        // A twelve-category pie is unreadable anyway; a twelve-category pie with three entries
        // half-printed across the trim looks like the export failed.
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
