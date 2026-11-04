package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DwSketchTraceCropTest {

    @Test
    fun `a box wider than the frame becomes the frame rather than hanging off it`() {
        val clamped = dwTraceClampCrop(DwTraceCropRect(3000, 0, 5000, 100), 4000, 3000)
        assertEquals(0, clamped.x)
        assertEquals(4000, clamped.width)
        assertTrue("the far edge must be inside the frame", clamped.x + clamped.width <= 4000)
    }

    @Test
    fun `an origin that would push the box past the far edge is pulled back`() {
        val clamped = dwTraceClampCrop(DwTraceCropRect(3900, 2900, 200, 200), 4000, 3000)
        assertEquals(3800, clamped.x)
        assertEquals(2800, clamped.y)
        assertEquals(200, clamped.width)
        assertEquals(200, clamped.height)
    }

    @Test
    fun `a box smaller than the minimum edge grows to it`() {
        val clamped = dwTraceClampCrop(DwTraceCropRect(10, 10, 1, 1), 4000, 3000)
        assertEquals(DW_TRACE_CROP_MIN_EDGE_PX, clamped.width)
        assertEquals(DW_TRACE_CROP_MIN_EDGE_PX, clamped.height)
    }

    @Test
    fun `a frame smaller than the minimum edge clamps to the frame`() {
        val clamped = dwTraceClampCrop(DwTraceCropRect(0, 0, 16, 16), 10, 8)
        assertEquals(10, clamped.width)
        assertEquals(8, clamped.height)
        assertTrue(dwTraceIsWholeFrame(clamped, 10, 8))
    }

    @Test
    fun `the whole frame is recognised as the whole frame and nothing else is`() {
        assertTrue(dwTraceIsWholeFrame(dwTraceWholeFrame(4000, 3000), 4000, 3000))
        assertFalse(dwTraceIsWholeFrame(DwTraceCropRect(1, 0, 3999, 3000), 4000, 3000))
        assertFalse(dwTraceIsWholeFrame(DwTraceCropRect(0, 0, 4000, 2999), 4000, 3000))
    }

    @Test
    fun `dragging a corner past its opposite stops at the minimum and does not move the other corner`() {
        val start = DwTraceCropRect(100, 100, 200, 200)
        val dragged = dwTraceMoveCorner(start, DwTraceCropCorner.TOP_LEFT, 5000, 5000, 1000, 1000)

        assertEquals("the right edge must not have moved", 300, dragged.x + dragged.width)
        assertEquals("the bottom edge must not have moved", 300, dragged.y + dragged.height)
        assertEquals(DW_TRACE_CROP_MIN_EDGE_PX, dragged.width)
        assertEquals(DW_TRACE_CROP_MIN_EDGE_PX, dragged.height)
    }

    @Test
    fun `dragging the bottom-right corner outwards stops at the frame`() {
        val dragged = dwTraceMoveCorner(
            DwTraceCropRect(100, 100, 200, 200),
            DwTraceCropCorner.BOTTOM_RIGHT,
            5000,
            5000,
            1000,
            800,
        )
        assertEquals(100, dragged.x)
        assertEquals(100, dragged.y)
        assertEquals(900, dragged.width)
        assertEquals(700, dragged.height)
    }

    @Test
    fun `sliding the box keeps its size and stays inside the frame`() {
        val moved = dwTraceMoveCrop(DwTraceCropRect(100, 100, 200, 200), 5000, -5000, 1000, 1000)
        assertEquals(200, moved.width)
        assertEquals(200, moved.height)
        assertEquals(800, moved.x)
        assertEquals(0, moved.y)
    }

    @Test
    fun `a crop aimed in a larger frame is scaled into the frame that was actually decoded`() {
        val choice = DwTraceFrameChoice(DwTraceCropRect(100, 50, 400, 300), 4096, 3072)
        val applied = dwTraceCropIn(choice, 2048, 1536)
        assertEquals(50, applied.x)
        assertEquals(25, applied.y)
        assertEquals(200, applied.width)
        assertEquals(150, applied.height)
    }

    @Test
    fun `a crop aimed in the frame it is applied in is untouched`() {
        val rect = DwTraceCropRect(100, 50, 400, 300)
        assertEquals(rect, dwTraceCropIn(DwTraceFrameChoice(rect, 4096, 3072), 4096, 3072))
    }

    @Test
    fun `a crop copies the rows it names and nothing else`() {
        val width = 40
        val height = 30
        val src = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = (y * width + x) * 4
                src[at] = x.toByte()
                src[at + 1] = y.toByte()
                src[at + 2] = (x + y).toByte()
                src[at + 3] = 255.toByte()
            }
        }

        val cropped = dwTraceCropRgba(src, width, height, DwTraceCropRect(17, 11, 20, 16))
        assertNotNull(cropped)
        val out = cropped!!.rgba
        assertEquals(20, cropped.rect.width)
        assertEquals(16, cropped.rect.height)
        assertEquals(20 * 16 * 4, out.size)

        val rowBytes = 20 * 4
        assertEquals(17, out[0].toInt())
        assertEquals(11, out[1].toInt())
        assertEquals(28, out[2].toInt())
        assertEquals(18, out[4].toInt())
        assertEquals(17, out[rowBytes].toInt())
        assertEquals(12, out[rowBytes + 1].toInt())
        assertEquals(17, out[15 * rowBytes].toInt())
        assertEquals(26, out[15 * rowBytes + 1].toInt())
    }

    @Test
    fun `cropping to the whole frame hands back the same buffer`() {
        val src = ByteArray(4 * 3 * 4)
        val cropped = dwTraceCropRgba(src, 4, 3, dwTraceWholeFrame(4, 3))
        assertNotNull(cropped)
        assertSame(src, cropped!!.rgba)
    }

    @Test
    fun `a source shorter than its own frame is refused`() {
        assertNull(dwTraceCropRgba(ByteArray(8), 4, 3, DwTraceCropRect(0, 0, 2, 2)))
    }

    @Test
    fun `the provenance clause is the portal's sentence exactly`() {
        assertEquals(
            "Cropped on the device to 400x300 at (100, 50) of 4096x3072.",
            dwTraceCropNote(DwTraceCropRect(100, 50, 400, 300), 4096, 3072),
        )
    }

    @Test
    fun `the whole photograph says nothing about a crop`() {
        assertEquals("", dwTraceCropNote(dwTraceWholeFrame(4096, 3072), 4096, 3072))
    }

    @Test
    fun `the readout states the share of the frame by area`() {
        val readout = dwTraceCropReadout(DwTraceCropRect(0, 0, 2048, 1536), 4096, 3072)
        assertTrue("area share, not edge share: $readout", readout.contains("25% of the frame"))
        assertTrue(readout.startsWith("2048×1536 of 4096×3072"))
        assertTrue(
            "the designer must be told what a crop costs: $readout",
            readout.contains("Everything outside it is absent from the drawing."),
        )
    }

    @Test
    fun `the readout names the whole photograph when nothing is cropped`() {
        assertEquals(
            "The whole photograph, 4096×3072.",
            dwTraceCropReadout(dwTraceWholeFrame(4096, 3072), 4096, 3072),
        )
    }

    @Test
    fun `a clamped origin and a clamped size send the designer to different controls`() {
        val originApplied = dwTraceClampCrop(DwTraceCropRect(3900, 0, 200, 200), 4000, 3000)
        val origin = dwTraceCropClampNote("Left", 3900, originApplied, 4000, 3000)

        val sizeApplied = dwTraceClampCrop(DwTraceCropRect(0, 0, 9, 200), 4000, 3000)
        val size = dwTraceCropClampNote("Width", 9, sizeApplied, 4000, 3000)

        assertTrue("an origin refusal must name the size control: $origin", origin.contains("Reduce Width"))
        assertTrue("it must say where it ended up: $origin", origin.contains("It was set to 3800."))
        assertFalse("a size refusal must not send anyone to Width: $size", size.contains("Reduce Width"))
        assertTrue("a size refusal must name the range: $size", size.contains("between 16 and 4000 pixels"))
        assertTrue("a size refusal must say where it ended up: $size", size.contains("It was set to 16."))
    }

    @Test
    fun `the crop drag reads a live box and applies the whole gesture, not a per-event step`() {
        val panel = cropPanelSource()

        assertTrue(
            "DwTraceCropOverlay must hold the current box in a rememberUpdatedState — without it the " +
                "drag lambda captures the rectangle from the composition that started it and every " +
                "event after the first is computed against a stale origin",
            panel.contains("rememberUpdatedState(box)"),
        )
        assertTrue(
            "the drag must snapshot the box at onDragStart",
            panel.contains("start = latestBox"),
        )

        val totals = Regex("""total[XY]\.toInt\(\)""").findAll(panel).count()
        assertEquals(
            "both dwTraceMoveCrop and dwTraceMoveCorner must be handed the gesture's total delta " +
                "(two arguments each, four in all)",
            4,
            totals,
        )

        assertFalse(
            "the per-event carry/step accumulator is back; it applies a step to whatever rectangle " +
                "the lambda captured, which is the defect this test exists for",
            panel.contains("carryX -= stepX"),
        )
    }

    @Test
    fun `the corner handles keep their touch target inside the preview`() {
        val panel = cropPanelSource()
        assertTrue(
            "the handle offset must be clamped into the preview, or the part of the target outside " +
                "the parent is not touchable — which is most of it while the crop is the whole frame",
            panel.contains("coerceIn(0f, maxX)") && panel.contains("coerceIn(0f, maxY)"),
        )
    }

    private fun cropPanelSource(): String {
        val relative = listOf(
            "src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceCropPanel.kt",
            "app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceCropPanel.kt",
            "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTraceCropPanel.kt",
        )
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = java.io.File(dir, path)
                if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        throw AssertionError("DwSketchTraceCropPanel.kt not found from ${java.io.File(".").absolutePath}")
    }
}
