package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE FRAME DECIDES WHICH PIXELS REACH THE MINISTRY, SO THE FRAME IS PINNED.**
 *
 * A crop is the one control in this feature that DISCARDS something. Everything else the panel offers
 * moves a threshold or a smoothing radius, and a designer who does not like the answer traces again.
 * A crop that was off by a factor of two, or that clamped the wrong edge, produces a drawing that
 * looks entirely plausible and is of the wrong part of the sheet — and the photograph it should have
 * been checked against is the thing the crop removed from view.
 *
 * So these cases are about the four ways that happens quietly:
 *
 *  - the CLAMP ORDER, which decides whether a box pushed at the edge stays inside the frame or reads
 *    memory that is not there;
 *  - the CORNER ARITHMETIC, which decides whether a dragged handle moves the corner under the finger
 *    or teleports the box;
 *  - the SCALE between the frame a designer aimed in and the frame a decode actually produced;
 *  - the SENTENCE, which is the only record of any of it once the drawing is in an archive, and which
 *    must be character for character the portal's because both files land in one submission.
 */
class DwSketchTraceCropTest {

    /* ── the clamp ──────────────────────────────────────────────────────────────────────────── */

    /**
     * Size first, then origin, then size again — `imageEdit.ts:138`'s order.
     *
     * The case that separates the two orders is a box wider than its frame. Clamping the origin first
     * leaves x where it was and then trims the width, so the box hangs off the far edge; clamping the
     * size first makes the box the frame and forces x to 0.
     */
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

    /** Sixteen, and the reason is downstream: global statistics over a handful of pixels are noise. */
    @Test
    fun `a box smaller than the minimum edge grows to it`() {
        val clamped = dwTraceClampCrop(DwTraceCropRect(10, 10, 1, 1), 4000, 3000)
        assertEquals(DW_TRACE_CROP_MIN_EDGE_PX, clamped.width)
        assertEquals(DW_TRACE_CROP_MIN_EDGE_PX, clamped.height)
    }

    /**
     * A frame smaller than the minimum is the frame, not the minimum.
     *
     * This is the third clamp in `imageEdit.ts:138`'s order and it exists for exactly this input. A
     * 10 px frame with a 16 px floor would otherwise produce a 16 px box on a 10 px picture, which is
     * a read past the end of the buffer dressed as a legal rectangle.
     */
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

    /* ── the corners ────────────────────────────────────────────────────────────────────────── */

    /**
     * A dragged corner moves ITS OWN two edges and leaves the opposite corner exactly where it is.
     *
     * The failure this catches is the one that only appears under a finger: build a rectangle from the
     * drag and clamp the finished thing, and dragging the left handle past the right one produces a
     * negative width, which the clamp turns into a minimum-width box at the ORIGINAL x — so the box
     * jumps sideways away from the handle being dragged.
     */
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

    /* ── the two frames ─────────────────────────────────────────────────────────────────────── */

    /**
     * A rectangle aimed in one frame, applied in another, is SCALED and not merely clamped.
     *
     * `decodeForTrace`'s exact resize is allowed to fail and return the power-of-two subsampled frame
     * instead, which is half the size. A clamp would trace the top-left quarter of what the designer
     * framed, at the same numbers, with nothing on screen to say so.
     */
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

    /* ── the bytes ──────────────────────────────────────────────────────────────────────────── */

    /**
     * The row copy lands the right pixels in the right places.
     *
     * Each pixel carries its own x in red and its own y in green, so a wrong stride shows up as the
     * wrong number rather than as a plausible picture — and green is what catches it, because the
     * first pixel of every row has the same red.
     *
     * The frame is 40x30 and not something tiny, deliberately: at 4x3 the 16 px minimum edge makes
     * every legal crop the whole frame, so a case that small would assert nothing about copying.
     */
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
        // First pixel of the first copied row is the source's (17, 11).
        assertEquals(17, out[0].toInt())
        assertEquals(11, out[1].toInt())
        assertEquals(28, out[2].toInt())
        // Second pixel along is (18, 11) — the column offset is honoured.
        assertEquals(18, out[4].toInt())
        // First pixel of the SECOND copied row is (17, 12), which is what a wrong stride gets wrong.
        assertEquals(17, out[rowBytes].toInt())
        assertEquals(12, out[rowBytes + 1].toInt())
        // And the last copied row is (17, 26) — the far edge is exclusive, so it is y = 11 + 16 - 1.
        assertEquals(17, out[15 * rowBytes].toInt())
        assertEquals(26, out[15 * rowBytes + 1].toInt())
    }

    /**
     * The whole frame is not copied at all — the caller gets the source it passed in.
     *
     * A second copy of a decoded photograph is up to 48 MB on a handset that is also holding a camera,
     * and there is nothing downstream that writes into it.
     */
    @Test
    fun `cropping to the whole frame hands back the same buffer`() {
        val src = ByteArray(4 * 3 * 4)
        val cropped = dwTraceCropRgba(src, 4, 3, dwTraceWholeFrame(4, 3))
        assertNotNull(cropped)
        assertSame(src, cropped!!.rgba)
    }

    /** A buffer too small for the frame it claims is refused rather than read past its end. */
    @Test
    fun `a source shorter than its own frame is refused`() {
        assertNull(dwTraceCropRgba(ByteArray(8), 4, 3, DwTraceCropRect(0, 0, 2, 2)))
    }

    /* ── the words ──────────────────────────────────────────────────────────────────────────── */

    /**
     * The provenance clause is the portal's, character for character.
     *
     * `imageEdit.ts:411` builds "Cropped on the device to WxH at (x, y) of WxH." and a handset drawing
     * and a portal drawing land in the same ministry submission. A reviewer holding one of each must
     * not have to work out whether two phrasings describe two operations — which is why this asserts
     * the whole string, including the lower-case letter between the numbers that the rest of this
     * client would set as a multiplication sign.
     */
    @Test
    fun `the provenance clause is the portal's sentence exactly`() {
        assertEquals(
            "Cropped on the device to 400x300 at (100, 50) of 4096x3072.",
            dwTraceCropNote(DwTraceCropRect(100, 50, 400, 300), 4096, 3072),
        )
    }

    /** Nothing was cropped, so nothing is claimed — a caller can concatenate it unconditionally. */
    @Test
    fun `the whole photograph says nothing about a crop`() {
        assertEquals("", dwTraceCropNote(dwTraceWholeFrame(4096, 3072), 4096, 3072))
    }

    /**
     * The readout's percentage is of AREA.
     *
     * Half the width and half the height keeps a quarter of the sheet. A reader told "50%" would be
     * told the wrong thing twice over, and the number is the whole reason the sentence exists.
     */
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

    /**
     * A clamped origin and a clamped size get DIFFERENT sentences, because they have different
     * remedies: one is fixed by making the frame smaller first, the other by typing a legal size.
     */
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
}
