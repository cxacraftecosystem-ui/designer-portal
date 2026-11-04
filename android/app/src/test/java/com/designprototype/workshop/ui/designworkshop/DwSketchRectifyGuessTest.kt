package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwImageQuality
import com.designprototype.workshop.data.DwPoint
import com.designprototype.workshop.data.GreyPlane
import com.designprototype.workshop.data.dwOtsuThreshold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class DwSketchRectifyGuessTest {

    private fun plane(width: Int, height: Int, value: (Int, Int) -> Double): GreyPlane {
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * width + x] = DwImageQuality.clampToByte(value(x, y)).toByte()
            }
        }
        return GreyPlane(data, width, height)
    }

    private fun sheetOnTable(
        corners: List<DwPoint>,
        width: Int = 480,
        height: Int = 360,
    ): GreyPlane {
        fun inside(x: Int, y: Int): Boolean {
            var sign = 0
            for (index in corners.indices) {
                val a = corners[index]
                val b = corners[(index + 1) % corners.size]
                val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
                val current = if (cross > 0) 1 else if (cross < 0) -1 else 0
                if (current == 0) continue
                if (sign == 0) sign = current else if (sign != current) return false
            }
            return true
        }
        return plane(width, height) { x, y -> if (inside(x, y)) 225.0 else 55.0 }
    }

    private val truth = listOf(
        DwPoint(70.0, 40.0),
        DwPoint(420.0, 70.0),
        DwPoint(400.0, 320.0),
        DwPoint(50.0, 290.0),
    )

    private fun assertNear(expected: DwPoint, actual: DwPoint, tolerance: Double, what: String) {
        val distance = hypot(actual.x - expected.x, actual.y - expected.y)
        assertTrue(
            "$what: expected (${expected.x}, ${expected.y}) but got (${actual.x}, ${actual.y}), " +
                "which is $distance away",
            distance < tolerance,
        )
    }

    @Test
    fun `finds a tilted sheet and reports it as confident`() {
        val found = dwGuessSheetCorners(sheetOnTable(truth))
        assertNotNull("the tilted sheet is the case this feature exists for", found)
        val guess = found!!

        assertTrue("fill ${guess.fill} must clear the gate", guess.fill > DW_GUESS_MIN_FILL)
        truth.forEachIndexed { index, expected ->
            assertNear(expected, guess.corners[index], 12.0, "corner ${index + 1}")
        }

        assertEquals(70.5, guess.corners[0].x, 1e-9)
        assertEquals(40.5, guess.corners[0].y, 1e-9)
        assertEquals(420.5, guess.corners[1].x, 1e-9)
        assertEquals(70.5, guess.corners[1].y, 1e-9)
        assertEquals(400.5, guess.corners[2].x, 1e-9)
        assertEquals(320.5, guess.corners[2].y, 1e-9)
        assertEquals(50.5, guess.corners[3].x, 1e-9)
        assertEquals(290.5, guess.corners[3].y, 1e-9)

        assertEquals(88121.0 / 88100.0, guess.fill, 1e-9)
        assertEquals(88100.0 / (480.0 * 360.0), guess.frameShare, 1e-9)
        assertEquals(1.0, guess.edgeSupport, 1e-9)
    }

    @Test
    fun `finds the sheet even when a stroke is ruled right across it`() {
        val withDrawing = sheetOnTable(truth)
        for (y in 0 until withDrawing.height) {
            for (x in 0 until withDrawing.width) {
                val onStroke = abs(y - 180) < 1.5 || abs(x - 240) < 1.5
                val index = y * withDrawing.width + x
                if (onStroke && withDrawing.at(index) > 100) withDrawing.data[index] = 45.toByte()
            }
        }

        val found = dwGuessSheetCorners(withDrawing)
        assertNotNull("a sheet with a drawing on it is the only kind there is", found)
        val guess = found!!

        truth.forEachIndexed { index, expected ->
            assertNear(expected, guess.corners[index], 12.0, "corner ${index + 1}")
        }
        assertEquals(88121.0 / 88100.0, guess.fill, 1e-9)
        assertEquals(1.0, guess.edgeSupport, 1e-9)
    }

    @Test
    fun `stays silent when there is no sheet, so the manual path remains the default`() {
        assertNull(
            "a flat frame has no two populations to separate, so there is nothing to be confident about",
            dwGuessSheetCorners(plane(480, 360) { _, _ -> 40.0 }),
        )

        val speck = sheetOnTable(
            listOf(
                DwPoint(200.0, 160.0),
                DwPoint(260.0, 160.0),
                DwPoint(260.0, 210.0),
                DwPoint(200.0, 210.0),
            ),
        )
        assertNull(
            "a bright object filling 1.7% of the frame is not the sketch somebody photographed",
            dwGuessSheetCorners(speck),
        )

        assertNull(dwGuessSheetCorners(plane(6, 6) { _, _ -> 200.0 }))
    }

    @Test
    fun `declines a bright region that is not a quadrilateral`() {
        assertNull(dwGuessSheetCorners(ell()))
    }

    private fun ell(): GreyPlane = plane(480, 360) { x, y ->
        val inArm = x > 60 && x < 420 && y > 60 && y < 160
        val inLeg = x > 60 && x < 180 && y > 60 && y < 300
        if (inArm || inLeg) 225.0 else 55.0
    }

    private fun ellMask(): ByteArray {
        val mask = ByteArray(480 * 360)
        for (y in 0 until 360) {
            for (x in 0 until 480) {
                val inArm = x > 60 && x < 420 && y > 60 && y < 160
                val inLeg = x > 60 && x < 180 && y > 60 && y < 300
                mask[y * 480 + x] = if (inArm || inLeg) 1.toByte() else 0.toByte()
            }
        }
        return mask
    }

    @Test
    fun `the L is refused by edge support and not by fill`() {
        val corners = listOf(
            DwPoint(61.0, 61.0),
            DwPoint(419.0, 61.0),
            DwPoint(419.0, 159.0),
            DwPoint(61.0, 299.0),
        )
        val support = dwEdgeSupport(corners, ellMask(), 480, 360)
        assertEquals("the notch edge scores 10 of 31 inward samples", 10.0 / 31.0, support, 1e-12)
        assertTrue("and that is what refuses it", support < DW_GUESS_MIN_EDGE_SUPPORT)

        val fill = 52201.0 / 60144.0
        val frameShare = 60144.0 / (480.0 * 360.0)
        assertTrue("fill $fill clears the fill gate", fill > DW_GUESS_MIN_FILL)
        assertTrue("frame share $frameShare clears the frame-share gate", frameShare > DW_GUESS_MIN_FRAME_SHARE)
    }

    @Test
    fun `the same quadrilateral filled in is accepted`() {
        val solid = plane(480, 360) { x, y ->
            if (x > 60 && x < 420 && y > 60 && y < 300) 225.0 else 55.0
        }
        val found = dwGuessSheetCorners(solid)
        assertNotNull("a solid bright quadrilateral filling half the frame is a sheet", found)
        val guess = found!!
        assertEquals(61.5, guess.corners[0].x, 1e-9)
        assertEquals(61.5, guess.corners[0].y, 1e-9)
        assertEquals(419.5, guess.corners[2].x, 1e-9)
        assertEquals(299.5, guess.corners[2].y, 1e-9)
        assertEquals(85801.0 / 85204.0, guess.fill, 1e-9)
        assertEquals(85204.0 / (480.0 * 360.0), guess.frameShare, 1e-9)
        assertEquals(1.0, guess.edgeSupport, 1e-9)
    }

    @Test
    fun `a sheet found on a plane too large to search directly comes back in the caller's pixels`() {
        val big = sheetOnTable(
            listOf(
                DwPoint(180.0, 120.0),
                DwPoint(1260.0, 120.0),
                DwPoint(1260.0, 960.0),
                DwPoint(180.0, 960.0),
            ),
            width = 1440,
            height = 1080,
        )
        val found = dwGuessSheetCorners(big)
        assertNotNull(found)
        val guess = found!!

        assertEquals(181.5, guess.corners[0].x, 1e-9)
        assertEquals(121.5, guess.corners[0].y, 1e-9)
        assertEquals(1258.5, guess.corners[1].x, 1e-9)
        assertEquals(121.5, guess.corners[1].y, 1e-9)
        assertEquals(1258.5, guess.corners[2].x, 1e-9)
        assertEquals(958.5, guess.corners[2].y, 1e-9)
        assertEquals(181.5, guess.corners[3].x, 1e-9)
        assertEquals(958.5, guess.corners[3].y, 1e-9)

        assertEquals(100800.0 / 100161.0, guess.fill, 1e-9)
        assertEquals(100161.0 / (480.0 * 360.0), guess.frameShare, 1e-9)
        assertEquals(1.0, guess.edgeSupport, 1e-9)
    }

    @Test
    fun `otsu splits sheet from table and says nothing useful about a flat frame`() {
        assertEquals(55, dwOtsuThreshold(sheetOnTable(truth)))
        assertEquals(0, dwOtsuThreshold(plane(64, 64) { _, _ -> 40.0 }))
        assertEquals(128, dwOtsuThreshold(GreyPlane(ByteArray(0), 0, 0)))
    }
}
