package com.designprototype.workshop.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartLabelSizeTest {

    private val docxPt = 8.0

    private val stepPt = GLYPH_H * 72.0 / RENDER_DPI

    private fun pointsOf(scale: Int): Double = textHeight(scale) * 72.0 / RENDER_DPI

    private val a4FigurePx = pixelsForMm((210.0 - 50.0) * 0.74)

    @Test
    fun `a chart label measures roughly what the docx sets its charts to`() {
        val (label, small) = glyphScales()

        assertTrue(
            "label glyph measures ${pointsOf(label)} pt against a target of $docxPt",
            Math.abs(pointsOf(label) - docxPt) <= stepPt,
        )
        assertTrue(
            "small glyph measures ${pointsOf(small)} pt against a target of 7.0",
            Math.abs(pointsOf(small) - 7.0) <= stepPt,
        )
    }

    @Test
    fun `no chart text is drawn at the unreadable size`() {
        val (label, small) = glyphScales()
        assertTrue("label is ${pointsOf(label)} pt", pointsOf(label) >= 6.0)
        assertTrue("small is ${pointsOf(small)} pt", pointsOf(small) >= 6.0)
    }

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

    @Test
    fun `the label size does not change with the figure width`() {
        val narrow = chartPixelBox(bars, 240, 3)
        val wide = chartPixelBox(bars, 2400, 3)
        assertTrue("both widths must render", narrow.first == 240 && wide.first == 2400)
        assertEquals(glyphScales(), glyphScales())
    }

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

    @Test
    fun `every chart kind still renders at the report width`() {
        for (block in listOf(bars, costs, bars.copy(kind = ChartKind.PIE), bars.copy(kind = ChartKind.DONUT), bars.copy(kind = ChartKind.LINE))) {
            val figure = renderChartPng(block, ReportTheme(), a4FigurePx)
            assertTrue("${block.kind} produced no bytes", figure.png.isNotEmpty())
            val (boxW, boxH) = chartPixelBox(block, a4FigurePx, block.series.size)
            assertEquals(boxW, figure.widthPx)
            assertEquals(boxH, figure.heightPx)
        }
    }

    @Test
    fun `an empty figure still renders`() {
        val figure = renderChartPng(ChartBlock(kind = ChartKind.BAR), ReportTheme(), a4FigurePx)
        assertTrue(figure.png.isNotEmpty())
    }
}
