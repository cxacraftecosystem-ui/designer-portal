package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ActionRow(toolbar = true)`: controls that act on one thing share ONE line in equal cells and never
 * stack. The stage preview's zoom (Smaller / Larger / Fit / 100%) stacked one per row on a phone as an
 * action row (release smoke 2026-10-17, dp-final/36).
 */
class ActionRowToolbarTest {
    @Test
    fun fourCellsShareA360dpLineAtAThumbsWidth() {
        // 360dp phone minus the card's padding, at 1x density: four cells with 4dp gaps.
        val cell = toolbarCellWidth(width = 328, count = 4, gap = 4)
        assertEquals(79, cell)
        assertTrue("each cell must stay a 48dp target", cell >= 48)
        assertTrue("the cells and gaps fit the line", cell * 4 + 4 * 3 <= 328)
    }

    @Test
    fun degenerateRowsDoNotGoNegative() {
        assertEquals(0, toolbarCellWidth(width = 100, count = 0, gap = 8))
        assertEquals(0, toolbarCellWidth(width = 4, count = 3, gap = 8))
    }

    @Test
    fun theStagePreviewZoomIsAToolbar() {
        val sheet = File("src/main/java/com/designprototype/workshop/ui/designworkshop/DwReportSheets.kt").readText()
        assertTrue("the zoom must render as a toolbar row", sheet.contains("ActionRow(toolbar = true"))
        assertTrue("each zoom control keeps a 48dp target", sheet.contains("heightIn(min = 48.dp)"))
        for (name in listOf("\"Fit width\"", "\"Actual size\"")) {
            assertTrue("TalkBack still hears $name", sheet.contains(name))
        }
        assertFalse("no stacked text buttons left", sheet.contains("Text(\"Fit width\", fontSize = 12.sp)"))
    }
}
