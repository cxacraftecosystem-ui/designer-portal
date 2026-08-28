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

/**
 * The automatic corner guess — [dwGuessSheetCorners] and the gate that decides whether it speaks.
 *
 * ── THE CASES ARE `frontend/e2e/sketch-rectify.spec.ts`, THE FOUR UNDER `sketchRectify — automatic
 *    corner guess`, CASE FOR CASE ─────────────────────────────────────────────────────────────
 *
 * `DwSketchRectifyTest` carries every other case in that spec file and points at this one for these
 * four, because the module they are the specification for is `DwSketchRectifyGuess.kt` rather than
 * `DwSketchRectify.kt`. Every fixture below is the web spec's
 * own construction — the same tilted quadrilateral, the same two strokes ruled across it, the same
 * flat frame, the same speck, the same L — so a case that passes here and fails there, or the
 * reverse, is a real divergence between two clients that must agree about the same photograph.
 *
 * ── THE NUMBERS ARE THE WEB'S OUTPUT, NOT THIS PORT'S ─────────────────────────────────────────
 *
 * Where the web spec asserts a tolerance ("within 12px of the truth") this asserts the tolerance too,
 * because that is the contract. But several cases below also pin the EXACT `fill`, `frameShare` and
 * `edgeSupport` the web module produces for the same fixture. Those numbers were taken by running the
 * JavaScript, not by printing what Kotlin did: a port whose expectations are its own first output is
 * a test that the code still does whatever it did on the day it was written, which is the one thing
 * this repository forbids a parity suite to be.
 *
 * ── WHY THERE IS A DOWNSCALING CASE THE WEB SPEC DOES NOT HAVE ────────────────────────────────
 *
 * All four web fixtures are 480x360, which is exactly [DW_GUESS_EDGE_PX] on the long edge, so `scale`
 * is 1 and the reduce-then-lift path never runs. That path — average down, find the corners in the
 * small plane, multiply back out with the half-pixel block centring — is the most typo-prone
 * arithmetic in the file and the four web cases cannot see it at all. `a sheet found on a plane too
 * large to search directly` is a fifth fixture, run through the same JavaScript to get its expected
 * corners, and it is the case that would catch a missing `+ 0.5` or an inverted `back`.
 *
 * ── AND WHY THE THIRD GATE IS TESTED TWICE ────────────────────────────────────────────────────
 *
 * [dwGuessSheetCorners] can only be observed returning `null`, so "the L was refused" is compatible
 * with the edge-support gate having been deleted and the fill gate tightened to compensate — which
 * would start refusing real sheets with one shadowed corner, silently, in the field. So the L is
 * tested twice: once through the whole function (it is refused) and once through [dwEdgeSupport]
 * directly on its own mask, where the measurement is 10/31 on the edge that flies across the notch
 * and 31/31 on the other three. The paired `solid` fixture — the L's own bounding rectangle, filled
 * in — is accepted, which is what makes the refusal a statement about the shape rather than about
 * the size, the brightness or the threshold, all three of which the pair holds constant.
 */
class DwSketchRectifyGuessTest {

    // ── Fixtures, transcribed from the web spec ──────────────────────────────────────────────────

    /** A plane whose value at each pixel is [value], clamped exactly as the web's assignment does. */
    private fun plane(width: Int, height: Int, value: (Int, Int) -> Double): GreyPlane {
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * width + x] = DwImageQuality.clampToByte(value(x, y)).toByte()
            }
        }
        return GreyPlane(data, width, height)
    }

    /** A bright convex quadrilateral (the sheet) on a dark background (the table). The web's helper. */
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

    /** The web spec's tilted sheet: the truth the guess is measured against. */
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

    // ── The web's four ───────────────────────────────────────────────────────────────────────────

    /**
     * `finds a tilted sheet and reports it as confident`.
     *
     * The tolerance is the web's own 12px and the reason is the web's own: the guess is a proposed
     * starting position for four handles the designer drags, not a committed answer, so locating a
     * corner to about a pixel of the search plane is all it has to do. The exact corners are pinned
     * beside it because they are available and a tolerance alone would not notice a systematic
     * half-pixel bias creeping into all four.
     */
    @Test
    fun `finds a tilted sheet and reports it as confident`() {
        val found = dwGuessSheetCorners(sheetOnTable(truth))
        assertNotNull("the tilted sheet is the case this feature exists for", found)
        val guess = found!!

        assertTrue("fill ${guess.fill} must clear the gate", guess.fill > DW_GUESS_MIN_FILL)
        truth.forEachIndexed { index, expected ->
            assertNear(expected, guess.corners[index], 12.0, "corner ${index + 1}")
        }

        // The web module's own output for this fixture, to eight places. The search runs on the plane
        // as given (480 is DW_GUESS_EDGE_PX exactly, so nothing is resampled) and reports the
        // component's four extreme pixels, centred in their blocks by the +0.5 lift.
        assertEquals(70.5, guess.corners[0].x, 1e-9)
        assertEquals(40.5, guess.corners[0].y, 1e-9)
        assertEquals(420.5, guess.corners[1].x, 1e-9)
        assertEquals(70.5, guess.corners[1].y, 1e-9)
        assertEquals(400.5, guess.corners[2].x, 1e-9)
        assertEquals(320.5, guess.corners[2].y, 1e-9)
        assertEquals(50.5, guess.corners[3].x, 1e-9)
        assertEquals(290.5, guess.corners[3].y, 1e-9)

        // 88121 bright pixels inside a quadrilateral of area 88100 — fill is very slightly over 1
        // because the component includes the boundary pixels the shoelace area of its own corners
        // does not. That is not a bug to clamp away: it is the honest ratio, and the gate is a floor.
        assertEquals(88121.0 / 88100.0, guess.fill, 1e-9)
        assertEquals(88100.0 / (480.0 * 360.0), guess.frameShare, 1e-9)
        assertEquals(1.0, guess.edgeSupport, 1e-9)
    }

    /**
     * `finds the sheet even when a stroke is ruled right across it`.
     *
     * THIS CASE WAS A REAL BUG ON THE WEB AND IT WAS INVISIBLE TO EVERY OTHER FIXTURE HERE, because
     * every other one is a blank bright quadrilateral. The sheet is found as the largest CONNECTED
     * bright region, and a line ruled from one edge of the paper to the other — a border, a fold, a
     * construction line — cuts that region in two. The search then returns the larger BAND as the
     * sheet: a confident, plausible, wrong answer that rectifies a slice of the drawing.
     *
     * The morphological closing is the fix and this is the test that fails without it. Delete the
     * `dwCloseBrightMask` call and the returned quadrilateral is roughly half this tall, so every
     * corner falls outside the tolerance — which is exactly the failure a designer in a courtyard
     * would otherwise have to notice for themselves.
     */
    @Test
    fun `finds the sheet even when a stroke is ruled right across it`() {
        val withDrawing = sheetOnTable(truth)
        // Two dark strokes spanning the whole sheet — one horizontal, one vertical — each three pixels
        // wide, exactly as a ruled line lands after the guess plane's downscale.
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

        // The WHOLE sheet, not the largest band between two strokes.
        truth.forEachIndexed { index, expected ->
            assertNear(expected, guess.corners[index], 12.0, "corner ${index + 1}")
        }
        // Identical to the blank sheet, which is the point: the closing put the outer boundary back
        // exactly where it was after swallowing the strokes.
        assertEquals(88121.0 / 88100.0, guess.fill, 1e-9)
        assertEquals(1.0, guess.edgeSupport, 1e-9)
    }

    /**
     * `stays silent when there is no sheet, so the manual path remains the default`.
     *
     * Two refusals for two different reasons, and neither may become a hedged answer. A frame with no
     * contrast is refused BEFORE anything is measured (see [DW_GUESS_MIN_CONTRAST]) — Otsu returns a
     * level for a constant image too, and the guess computed from that split is the most confident
     * possible answer drawn round the whole picture, from a photograph of a table. A small bright
     * object is real, connected and convex, and is refused on [DW_GUESS_MIN_FRAME_SHARE]: somebody
     * photographing a sketch fills the frame with it.
     */
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
        // 3000 px of quadrilateral in a 172,800 px frame — 1.7%, well under the 15% floor.
        assertNull(
            "a bright object filling 1.7% of the frame is not the sketch somebody photographed",
            dwGuessSheetCorners(speck),
        )

        // A plane too small to search at all. Not a gate — the extremes of a component smaller than a
        // handful of pixels are noise, and there is nothing to average down to.
        assertNull(dwGuessSheetCorners(plane(6, 6) { _, _ -> 200.0 }))
    }

    /**
     * `declines a bright region that is not a quadrilateral`.
     *
     * Two separate bright sheets bridged by nothing would not test this: the largest component is one
     * of them and the fill test passes for that one. What must fail is a SINGLE component whose shape
     * is not convex. An L covers a little over half of its own corner quadrilateral by area — see the
     * companion case below for the numbers, which are the whole reason [DW_GUESS_MIN_EDGE_SUPPORT]
     * exists.
     */
    @Test
    fun `declines a bright region that is not a quadrilateral`() {
        assertNull(dwGuessSheetCorners(ell()))
    }

    // ── The gate that does the refusing, measured directly ───────────────────────────────────────

    /** The web spec's L: an arm across the top and a leg down the left. */
    private fun ell(): GreyPlane = plane(480, 360) { x, y ->
        val inArm = x > 60 && x < 420 && y > 60 && y < 160
        val inLeg = x > 60 && x < 180 && y > 60 && y < 300
        if (inArm || inLeg) 225.0 else 55.0
    }

    /** The L's own bright mask, as [dwGuessSheetCorners] builds it. */
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

    /**
     * THE L PASSES THE FIRST TWO GATES AND IS STOPPED BY THE THIRD, which is the fact that justifies
     * the third gate's existence and cannot be seen through [dwGuessSheetCorners]'s `null`.
     *
     * Its fill is 0.868 — comfortably over the 0.85 floor — and it occupies 35% of the frame, three
     * times the 15% floor. Only the edges tell it apart from a sheet: the line joining the two extreme
     * corners either side of the notch flies across empty table, and 21 of its 31 inward samples miss.
     * Tightening the fill threshold to 0.87 would have caught this L and would also have started
     * refusing real sheets with one shadowed corner — trading a rare wrong answer for a common missing
     * one, which is the trade this repository does not make.
     *
     * The numbers are the JavaScript module's, run on the same construction.
     */
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

        // What the other two gates would have said, so the claim above is measured rather than
        // asserted: 52201 bright pixels in a quadrilateral of area 60144, in a 172,800 px frame.
        val fill = 52201.0 / 60144.0
        val frameShare = 60144.0 / (480.0 * 360.0)
        assertTrue("fill $fill clears the fill gate", fill > DW_GUESS_MIN_FILL)
        assertTrue("frame share $frameShare clears the frame-share gate", frameShare > DW_GUESS_MIN_FRAME_SHARE)
    }

    /**
     * The L's own bounding rectangle, filled in — accepted, with every edge fully supported.
     *
     * This is the half that makes the L's refusal a statement about SHAPE. The two fixtures cover the
     * same region of the frame, at the same two brightnesses, and Otsu splits both at the same level;
     * they differ only in whether the notch is paper. One is refused and one is not.
     *
     * They do NOT have the same corner quadrilateral, and that is the point rather than a slip: the
     * L's bottom-right extreme is (419, 159) — where its arm ends — while the solid's is (419, 299).
     * The corners a shape reports ARE its shape, which is why the extremes are taken along the two
     * diagonals and not from a bounding box.
     */
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

    // ── The path the web fixtures cannot reach ───────────────────────────────────────────────────

    /**
     * A sheet on a plane three times too large to search directly: the reduce, and the lift back.
     *
     * The four web fixtures are all 480x360, so `scale` is 1 for every one of them and the whole
     * reduce-then-lift path is dead code as far as they are concerned. Here the plane is 1440x1080,
     * the search runs on a 480x360 average of it, and the corners come back multiplied out — with the
     * half-pixel block centring that stops a systematic inward bias.
     *
     * A missing `+ 0.5` moves every corner 1.5px outward here; an inverted `back` moves them by a
     * factor of nine. Both are silent everywhere else in this file. The expected corners are the
     * JavaScript module's output for the same construction.
     */
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

        // (x + 0.5) * 3 for each of the four extreme pixels of the reduced plane.
        assertEquals(181.5, guess.corners[0].x, 1e-9)
        assertEquals(121.5, guess.corners[0].y, 1e-9)
        assertEquals(1258.5, guess.corners[1].x, 1e-9)
        assertEquals(121.5, guess.corners[1].y, 1e-9)
        assertEquals(1258.5, guess.corners[2].x, 1e-9)
        assertEquals(958.5, guess.corners[2].y, 1e-9)
        assertEquals(181.5, guess.corners[3].x, 1e-9)
        assertEquals(958.5, guess.corners[3].y, 1e-9)

        // The confidence figures are measured on the REDUCED plane, so they are ratios of small-plane
        // areas — which is why frame share is comparable with the 480x360 cases above.
        assertEquals(100800.0 / 100161.0, guess.fill, 1e-9)
        assertEquals(100161.0 / (480.0 * 360.0), guess.frameShare, 1e-9)
        assertEquals(1.0, guess.edgeSupport, 1e-9)
    }

    // ── Otsu ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * [dwOtsuThreshold] on the tilted-sheet fixture, and on a frame of one single value.
     *
     * 55 is the darker of the fixture's two populations, and that is Otsu working correctly rather
     * than oddly: the split is taken at the level BELOW which everything is background, and the mask
     * is then built with a strict `>` — so 55 puts the table on one side and the 225s on the other.
     *
     * The constant image is the case that motivates [DW_GUESS_MIN_CONTRAST]. Otsu returns a level for
     * it — it cannot do otherwise — and thresholding a constant image marks every pixel bright. That
     * is why the contrast refusal happens before Otsu is ever consulted, and this pins that Otsu
     * itself does NOT signal the problem.
     */
    @Test
    fun `otsu splits sheet from table and says nothing useful about a flat frame`() {
        assertEquals(55, dwOtsuThreshold(sheetOnTable(truth)))
        assertEquals(0, dwOtsuThreshold(plane(64, 64) { _, _ -> 40.0 }))
        assertEquals(128, dwOtsuThreshold(GreyPlane(ByteArray(0), 0, 0)))
    }
}
