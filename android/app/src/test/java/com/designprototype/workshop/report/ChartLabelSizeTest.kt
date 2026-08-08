package com.designprototype.workshop.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How big a chart label actually comes out on the page.
 *
 * THE DEFECT THIS PINS. Both [chartPixelBox] and [renderChartPng] sized their glyphs from a magic
 * `rint(width / 900 * 1.9)`, with `small` a step below it. On A4 with a 25 mm margin a 74 %-wide
 * figure is 932 px, so that arithmetic gave a label multiplier of 2 and a `small` of 1 — a 5x7 bitmap
 * font 0.89 mm tall, about 2.5 pt, for the donut legend, the category labels, the axis ticks and the
 * data values alike. Body text in the same PDF is 10.5 pt, and [DocxWriter] emits the SAME three
 * charts as native DrawingML with every text element at `sz="800"`, i.e. 8 pt. The two files a
 * designer submits together disagreed about whether their figures could be read at all — and the PDF
 * is the one that gets printed and filed.
 *
 * The assertions are about MEASURED POINTS on the page rather than about the multiplier, because the
 * multiplier is an implementation detail of a bitmap font and the point size is the thing the .docx
 * and the .pdf have to agree on. `backend/app/services/report_chart.py::glyph_scales` is the same
 * arithmetic; change the two together.
 */
class ChartLabelSizeTest {

    /** What the .docx sets every chart text element to. */
    private val docxPt = 8.0

    /** The smallest step this 7-pixel bitmap font can express: 7 × 72 / 200. */
    private val stepPt = GLYPH_H * 72.0 / RENDER_DPI

    /** The height a glyph multiplier actually measures on the printed page. */
    private fun pointsOf(scale: Int): Double = textHeight(scale) * 72.0 / RENDER_DPI

    /**
     * A 74 %-wide figure in the report's A4 text column — the width every chart in the document is
     * actually rendered at, and the width the broken arithmetic was tuned against.
     */
    private val a4FigurePx = pixelsForMm((210.0 - 50.0) * 0.74)

    @Test
    fun `a chart label measures roughly what the docx sets its charts to`() {
        val (label, small) = glyphScales()

        // Within one expressible step of the 8 pt target, on both sides. Exactness is impossible —
        // the font quantises to 2.52 pt — but "as close as this font can get" is the requirement.
        assertTrue(
            "label glyph measures ${pointsOf(label)} pt against a target of $docxPt",
            Math.abs(pointsOf(label) - docxPt) <= stepPt,
        )
        assertTrue(
            "small glyph measures ${pointsOf(small)} pt against a target of 7.0",
            Math.abs(pointsOf(small) - 7.0) <= stepPt,
        )
    }

    /**
     * The floor that matters: nothing may come out at the 2.5 pt the old arithmetic produced.
     *
     * A separate assertion from the one above because it is a different claim — that one says the
     * size is right, this one says the failure mode cannot come back by any route, including one that
     * happened to land inside a loose tolerance.
     */
    @Test
    fun `no chart text is drawn at the unreadable size`() {
        val (label, small) = glyphScales()
        assertTrue("label is ${pointsOf(label)} pt", pointsOf(label) >= 6.0)
        assertTrue("small is ${pointsOf(small)} pt", pointsOf(small) >= 6.0)
    }

    /**
     * And specifically not what `rint(width / 900 * 1.9)` gave at the width the report uses.
     *
     * Spelled out as the legacy arithmetic rather than as the numbers 2 and 1, so the test says WHICH
     * formula is forbidden and not merely which answers are.
     */
    @Test
    fun `the label size no longer follows the figure width`() {
        val legacyLabel = Math.max(1, Math.rint(a4FigurePx / 900.0 * 1.9).toInt())
        val legacySmall = Math.max(1, if (legacyLabel > 1) legacyLabel - 1 else 1)
        assertEquals("the legacy formula gave 2 at this width", 2, legacyLabel)
        assertEquals("the legacy formula gave 1 at this width", 1, legacySmall)

        val (label, small) = glyphScales()
        assertTrue("label must not be the legacy $legacyLabel", label > legacyLabel)
        assertTrue("small must not be the legacy $legacySmall", small > legacySmall)
    }

    /**
     * One size at every width.
     *
     * The old arithmetic scaled the type with the figure, so the same chart drawn narrow lost its
     * labels entirely while a wide one grew them. A point size is a property of the PAGE, not of the
     * picture's pixel dimensions — the whole reason the .docx can state one number for all of them.
     */
    @Test
    fun `the label size does not change with the figure width`() {
        val narrow = chartPixelBox(bars, 240, 3)
        val wide = chartPixelBox(bars, 2400, 3)
        assertTrue("both widths must render", narrow.first == 240 && wide.first == 2400)
        // `glyphScales` takes no width at all, which is the structural half of the fix; this asserts
        // the property that made it necessary.
        assertEquals(glyphScales(), glyphScales())
    }

    // ── The figure still draws ───────────────────────────────────────────────────────────────────

    private val bars = ChartBlock(
        kind = ChartKind.BAR,
        series = listOf("Accepted" to 12.0, "Revise and resubmit" to 1.0, "Rejected" to 4.0),
        unit = "prototypes",
    )

    private val costs = ChartBlock(
        kind = ChartKind.HORIZONTAL_BAR,
        series = listOf("Material" to 1200.0, "Labour" to 650.0, "Packaging" to 90.0),
        unit = "INR",
    )

    /**
     * A larger glyph must not push a label off the canvas or crash the ellipsiser, which is the one
     * way a legibility fix could take an export down — and an export is generated in a field, at the
     * end of a trip, with the designer waiting on it.
     */
    @Test
    fun `every chart kind still renders at the report width`() {
        for (block in listOf(bars, costs, bars.copy(kind = ChartKind.PIE), bars.copy(kind = ChartKind.DONUT), bars.copy(kind = ChartKind.LINE))) {
            val figure = renderChartPng(block, ReportTheme(), a4FigurePx)
            assertTrue("${block.kind} produced no bytes", figure.png.isNotEmpty())
            // The two callers must agree on the box, or the .docx's vector chart is a different shape
            // from the .pdf's picture of it.
            val (boxW, boxH) = chartPixelBox(block, a4FigurePx, block.series.size)
            assertEquals(boxW, figure.widthPx)
            assertEquals(boxH, figure.heightPx)
        }
    }

    /** A figure with nothing in it is still a figure, and it still has to be readable. */
    @Test
    fun `an empty figure still renders`() {
        val figure = renderChartPng(ChartBlock(kind = ChartKind.BAR), ReportTheme(), a4FigurePx)
        assertTrue(figure.png.isNotEmpty())
    }
}
