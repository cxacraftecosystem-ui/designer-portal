package com.designprototype.workshop.data

import com.designprototype.workshop.data.DwSketchRectify.RECTIFY_MAX_EDGE_PX
import com.designprototype.workshop.data.DwSketchRectify.bilinearSample
import com.designprototype.workshop.data.DwSketchRectify.makePlate
import com.designprototype.workshop.data.DwSketchRectify.orderCorners
import com.designprototype.workshop.data.DwSketchRectify.rectifiedSize
import com.designprototype.workshop.data.DwSketchRectify.rectifyHomography
import com.designprototype.workshop.data.DwSketchRectify.rectifyPlane
import com.designprototype.workshop.data.DwSketchRectify.sauvolaThreshold
import com.designprototype.workshop.data.DwSketchRectify.sauvolaWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * [DwSketchRectify] — the geometry and the statistics behind "make a plate out of a photograph of a
 * sketch".
 *
 * THE CASES ARE `frontend/e2e/sketch-rectify.spec.ts`, CASE FOR CASE AND NUMBER FOR NUMBER, for the
 * part of that module this port carries. That file is the specification: a case that exists there and
 * not here is a place the two clients are free to disagree, and every expectation below is the web's
 * own rather than whatever this port printed the day it was written.
 *
 * THE CASES THAT ARE DELIBERATELY ABSENT are the four under `sketchRectify — automatic corner guess`.
 * The guess is not ported — [DwSketchRectify]'s header sets out why, and the short version is that
 * the web module itself calls it a convenience on top of the manual path, which IS what the handset
 * panel builds. Nothing here tests a capability this app does not have.
 *
 * WHY SYNTHETIC IMAGES AND NOT PHOTOGRAPHS, the same argument as DwPhotoMeasureTest: every case below
 * is CONSTRUCTED from its answer. The rectification cases warp a plane whose value at every point is
 * known in closed form, so the expected output is computed rather than recorded. The thresholding case
 * builds a page whose illumination, paper reflectance and stroke reflectance are each a number chosen
 * in advance, so "the strokes were recovered" is a count against a mask rather than a judgement about
 * how a picture looks. A test against a real photograph could only assert that the module still
 * prints whatever it printed the day it was written.
 *
 * THE THRESHOLDING CASE IS THE ONE THAT MATTERS. Everything else here checks that arithmetic was typed
 * correctly. `a lamp gradient defeats a global threshold` is the test that justifies the existence of
 * the local method at all: it runs BOTH methods over the same page and asserts the difference between
 * them, so if somebody ever replaces the local threshold with a global one to simplify the file, the
 * suite says exactly what was lost and where.
 *
 * THERE IS NO WALL-CLOCK CASE. The web spec carries one ("stays well inside a frame budget"), and it
 * is not reproduced because this suite runs on a shared build machine where several Gradle daemons
 * compete for the same cores. A timing assertion there is a test that fails for reasons that have
 * nothing to do with the code, and the only way anybody ever fixes such a test is by loosening it —
 * which is the one thing this repository forbids. What the budget actually rests on is that the
 * window is read through a summed-area table, so the cost is linear in the pixel count whatever the
 * window is; `the integral tables agree with a brute-force window sum` pins that machinery directly,
 * which is a stronger statement than a stopwatch and does not depend on the machine.
 */
class DwSketchRectifyTest {

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

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

    private fun at(target: GreyPlane, x: Int, y: Int): Int = target.at(y * target.width + x)

    private fun point(x: Double, y: Double) = DwPoint(x, y)

    /**
     * A deliberately awkward projective transform with a NON-ZERO bottom row, so parallel lines in the
     * world genuinely converge in the image.
     *
     * The same fixture as the web spec's `TILT`, and the same reasoning as DwPhotoMeasureTest's: an
     * affine transform (h31 = h32 = 0) is recovered perfectly by code containing no projective
     * handling whatsoever, so a test built on one proves nothing about the part that is hard.
     */
    private val tilt = DwHomography(
        1.1, 0.22, 140.0,
        0.13, 1.05, 90.0,
        0.00042, 0.00026, 1.0,
    )

    private fun project(p: DwPoint): DwPoint = DwPhotoMeasure.applyHomography(tilt, p)

    // ── Corner ordering ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `puts any click order into TL TR BR BL`() {
        val marks = listOf(
            point(320.0, 40.0), // top right
            point(30.0, 220.0), // bottom left
            point(20.0, 30.0), // top left
            point(350.0, 260.0), // bottom right
        )
        assertEquals(
            listOf(
                point(20.0, 30.0),
                point(320.0, 40.0),
                point(350.0, 260.0),
                point(30.0, 220.0),
            ),
            orderCorners(marks),
        )
    }

    @Test
    fun `makes the reading-order bow-tie unrepresentable`() {
        // TL, TR, BOTTOM-LEFT, BOTTOM-RIGHT — the natural reading order, and a crossed quadrilateral.
        // [DwPhotoMeasure] REFUSES this configuration; this module reorders it into the sheet the
        // designer plainly meant, and the test is that the result is the non-crossing ring rather than
        // the input.
        val readingOrder = listOf(
            point(10.0, 10.0),
            point(200.0, 10.0),
            point(10.0, 150.0),
            point(200.0, 150.0),
        )
        val ordered = orderCorners(readingOrder)
        assertEquals(
            listOf(
                point(10.0, 10.0),
                point(200.0, 10.0),
                point(200.0, 150.0),
                point(10.0, 150.0),
            ),
            ordered,
        )

        // The signed area of the ring is what proves it does not cross: a bow-tie's two lobes cancel,
        // so its shoelace sum is far below the true area. Here it must equal the full rectangle.
        var shoelace = 0.0
        for (index in 0 until 4) {
            val current = ordered!![index]
            val next = ordered[(index + 1) % 4]
            shoelace += current.x * next.y - next.x * current.y
        }
        assertEquals(190.0 * 140.0, abs(shoelace) / 2, 1e-6)
    }

    @Test
    fun `refuses anything that is not four finite points`() {
        assertNull(orderCorners(listOf(point(0.0, 0.0))))
        assertNull(
            orderCorners(
                listOf(point(0.0, 0.0), point(1.0, 0.0), point(1.0, 1.0), point(Double.NaN, 1.0))
            )
        )
    }

    // ── Bilinear resampling, against values computed by hand ─────────────────────────────────────

    /**
     * A 2x2 plane, so every expected value below is one line of arithmetic:
     *
     *     (0,0) = 0     (1,0) = 100
     *     (0,1) = 200   (1,1) = 255
     */
    private val tiny = GreyPlane(byteArrayOf(0, 100, 200.toByte(), 255.toByte()), 2, 2)

    @Test
    fun `reproduces the sample points exactly`() {
        assertEquals(0.0, bilinearSample(tiny, 0.0, 0.0), 0.0)
        assertEquals(100.0, bilinearSample(tiny, 1.0, 0.0), 0.0)
        assertEquals(200.0, bilinearSample(tiny, 0.0, 1.0), 0.0)
        assertEquals(255.0, bilinearSample(tiny, 1.0, 1.0), 0.0)
    }

    @Test
    fun `matches hand-computed interpolations`() {
        // Halfway along the top edge: (0 + 100) / 2.
        assertEquals(50.0, bilinearSample(tiny, 0.5, 0.0), 1e-12)
        // Halfway down the left edge: (0 + 200) / 2.
        assertEquals(100.0, bilinearSample(tiny, 0.0, 0.5), 1e-12)
        // Dead centre: the mean of all four, (0 + 100 + 200 + 255) / 4.
        assertEquals(138.75, bilinearSample(tiny, 0.5, 0.5), 1e-12)
        // A quarter along the top: 0 + (100 - 0) * 0.25.
        assertEquals(25.0, bilinearSample(tiny, 0.25, 0.0), 1e-12)
        // Three quarters across, one quarter down — the full tensor product:
        //   upper = 0   + (100 - 0)   * 0.75 = 75
        //   lower = 200 + (255 - 200) * 0.75 = 241.25
        //   value = 75  + (241.25 - 75) * 0.25 = 116.5625
        assertEquals(116.5625, bilinearSample(tiny, 0.75, 0.25), 1e-12)
    }

    @Test
    fun `reads paper white outside the photograph rather than smearing the edge`() {
        // Beyond the half-pixel border there is genuinely no photograph. See OUTSIDE_VALUE.
        assertEquals(255.0, bilinearSample(tiny, -0.6, 0.0), 0.0)
        assertEquals(255.0, bilinearSample(tiny, 1.6, 0.0), 0.0)
        assertEquals(255.0, bilinearSample(tiny, 0.0, -0.51), 0.0)
        assertEquals(255.0, bilinearSample(tiny, 0.0, 1.51), 0.0)
        assertEquals(255.0, bilinearSample(tiny, Double.NaN, 0.0), 0.0)
        // Inside the border the nearest real pixel is the honest answer, not the outside value.
        assertEquals(0.0, bilinearSample(tiny, -0.5, 0.0), 1e-12)
        assertEquals(0.0, bilinearSample(tiny, -0.25, 0.0), 1e-12)
    }

    /**
     * A stroke must not come out of rectification in pieces.
     *
     * WHAT THIS PINS, PRECISELY, carried across from the web spec because it is less than it first
     * appears: this is a regression guard on STROKE CONTINUITY THROUGH THE WHOLE RESAMPLING PATH — it
     * fails if the geometry drifts, if the sampler starts reading the wrong neighbourhood, or if a
     * future change begins skipping output rows. It does NOT prove that bilinear is necessary; a
     * three-pixel stroke survives nearest-neighbour at this scale too. The case that discriminates the
     * two samplers is `matches hand-computed interpolations`, which reads 100 where the arithmetic
     * says 50 under nearest neighbour.
     */
    @Test
    fun `keeps a thin diagonal stroke continuous instead of dashing it`() {
        val source = plane(300, 300) { x, y -> if (abs(x - y) < 1.5) 0.0 else 255.0 }
        val marked = listOf(
            point(30.0, 12.0),
            point(268.0, 34.0),
            point(262.0, 276.0),
            point(18.0, 258.0),
        )
        val rectified = rectifyPlane(source, marked, 220, 220)
        assertNotNull(rectified)

        // Rows 25..194 only: the diagonal enters the marked quadrilateral through its left edge at
        // about plate row 15 and leaves through the right edge at about row 206, so rows outside that
        // span are legitimately blank and counting them would be asserting against the fixture's own
        // geometry.
        var emptyRows = 0
        for (y in 25 until 195) {
            var darkest = 255
            for (x in 0 until 220) darkest = min(darkest, at(rectified!!, x, y))
            if (darkest > 128) emptyRows += 1
        }
        assertEquals(0, emptyRows)
    }

    // ── The homography, as this module uses it ───────────────────────────────────────────────────

    @Test
    fun `carries the plate's own corners onto the marked corners`() {
        val marked = listOf(
            point(140.0, 90.0),
            point(470.0, 118.0),
            point(505.0, 372.0),
            point(96.0, 340.0),
        )
        val width = 200
        val height = 300
        val homography = rectifyHomography(marked, width, height)
        assertNotNull(homography)

        val plateCorners = listOf(
            point(0.0, 0.0),
            point(width - 1.0, 0.0),
            point(width - 1.0, height - 1.0),
            point(0.0, height - 1.0),
        )
        plateCorners.forEachIndexed { index, corner ->
            val mapped = DwPhotoMeasure.applyHomography(homography!!, corner)
            assertEquals(marked[index].x, mapped.x, 1e-9)
            assertEquals(marked[index].y, mapped.y, 1e-9)
        }
    }

    /**
     * THE FIFTH POINT IS THE WHOLE TEST. Any solve that returns anything at all reproduces the four
     * correspondences it was fitted to — that is what "fitted" means, and a completely wrong
     * implementation still passes the case above. A point that took no part in the fit is the only
     * evidence that what came back is the transform rather than a curve through four dots.
     */
    @Test
    fun `recovers a known projective transform at a point that took no part in the fit`() {
        val width = 210
        val height = 297
        val plateCorners = listOf(
            point(0.0, 0.0),
            point(width - 1.0, 0.0),
            point(width - 1.0, height - 1.0),
            point(0.0, height - 1.0),
        )
        val marked = plateCorners.map(::project)
        val recovered = rectifyHomography(marked, width, height)
        assertNotNull(recovered)

        // Points deep inside the sheet, on no edge and no diagonal, so they are constrained by nothing
        // that was fitted.
        for (probe in listOf(point(73.0, 129.0), point(151.0, 44.0), point(19.0, 268.0))) {
            val expected = project(probe)
            val actual = DwPhotoMeasure.applyHomography(recovered!!, probe)
            assertEquals(expected.x, actual.x, 1e-9)
            assertEquals(expected.y, actual.y, 1e-9)
        }
    }

    @Test
    fun `refuses corners that lie in a straight line`() {
        val collinear = listOf(
            point(0.0, 0.0),
            point(50.0, 50.0),
            point(100.0, 100.0),
            point(150.0, 150.0),
        )
        assertNull(rectifyHomography(collinear, 100, 100))
        assertNull(rectifyPlane(plane(20, 20) { _, _ -> 128.0 }, collinear, 100, 100))
        assertTrue(makePlate(plane(20, 20) { _, _ -> 128.0 }, collinear) is DwPlateResult.Refusal)
    }

    // ── Resampling the whole plane ───────────────────────────────────────────────────────────────

    /**
     * A source whose value is a LINEAR function of its own pixel coordinates.
     *
     * Chosen because bilinear interpolation is EXACT for such a function — it is a tensor-product
     * linear interpolant — so the expected value at every output pixel is `a·px + b·py + c` evaluated
     * at the projected source point, computed in closed form rather than sampled. Any error the test
     * sees is therefore the geometry being wrong, not interpolation blur being blamed on it.
     */
    @Test
    fun `lands every output pixel on the source point the homography names`() {
        val a = 0.11
        val b = 0.07
        val c = 30.0
        val source = plane(600, 450) { x, y -> a * x + b * y + c }

        val marked = listOf(
            point(90.0, 60.0),
            point(470.0, 95.0),
            point(512.0, 372.0),
            point(61.0, 331.0),
        )
        val width = 160
        val height = 220
        val rectified = rectifyPlane(source, marked, width, height)
        assertNotNull(rectified)
        val homography = rectifyHomography(marked, width, height)!!

        var worst = 0.0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = DwPhotoMeasure.applyHomography(homography, point(x.toDouble(), y.toDouble()))
                val expected = a * p.x + b * p.y + c
                worst = max(worst, abs(at(rectified!!, x, y) - expected))
                x += 5
            }
            y += 7
        }
        // One count of an 8-bit channel: the only loss left is the clamped-byte write rounding the
        // exact interpolated value to an integer.
        assertTrue("worst error was $worst counts", worst <= 1.0)
    }

    @Test
    fun `straightens a tilted rectangle back into a rectangle`() {
        // A black bar across the middle of a world sheet, projected through `tilt` into an image, then
        // rectified. In the world the bar is horizontal; in the image it is not; after rectification it
        // must be horizontal again — which is the entire user-visible promise of the feature.
        val worldWidth = 200
        val worldHeight = 260
        val barTop = 100
        val barBottom = 130
        val worldCorners = listOf(
            point(0.0, 0.0),
            point(worldWidth - 1.0, 0.0),
            point(worldWidth - 1.0, worldHeight - 1.0),
            point(0.0, worldHeight - 1.0),
        )
        val marked = worldCorners.map(::project)

        // The image is built by SCATTERING the world through `project` at quarter-pixel steps — dense
        // enough that the bar lands solid, and gaps elsewhere do not matter because the assertion below
        // is about which ROWS of the rectified plate are dark, not about pixel-perfect coverage.
        val image = plane(700, 560) { _, _ -> 255.0 }
        var wy = 0.0
        while (wy < worldHeight) {
            if (wy >= barTop && wy <= barBottom) {
                var wx = 0.0
                while (wx < worldWidth) {
                    val p = project(point(wx, wy))
                    val px = DwPhotoMeasure.jsRound(p.x).toInt()
                    val py = DwPhotoMeasure.jsRound(p.y).toInt()
                    if (px in 0 until image.width && py in 0 until image.height) {
                        image.data[py * image.width + px] = 0
                    }
                    wx += 0.25
                }
            }
            wy += 0.25
        }

        val rectified = rectifyPlane(image, marked, worldWidth, worldHeight)!!
        // Every row inside the bar must be almost entirely black; every row well outside it almost
        // entirely white. A bar still tilted would make both counts middling on the rows near its ends.
        fun darkFraction(row: Int): Double {
            var dark = 0
            for (x in 5 until worldWidth - 5) if (at(rectified, x, row) < 128) dark += 1
            return dark.toDouble() / (worldWidth - 10)
        }
        assertTrue(darkFraction(barTop + 8) > 0.95)
        assertTrue(darkFraction((barTop + barBottom) / 2) > 0.95)
        assertTrue(darkFraction(barBottom - 8) > 0.95)
        assertTrue(darkFraction(barTop - 20) < 0.05)
        assertTrue(darkFraction(barBottom + 20) < 0.05)
    }

    // ── Output sizing ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `takes the longer of each pair of opposite edges`() {
        val corners = listOf(
            point(0.0, 0.0),
            point(400.0, 0.0),
            point(300.0, 200.0),
            point(0.0, 200.0),
        )
        // Top edge 400, bottom edge 300 -> width 400. Left edge 200, right edge sqrt(100²+200²)=223.6.
        assertEquals(400 to 224, rectifiedSize(corners))
    }

    @Test
    fun `never upscales a sheet that occupied few pixels`() {
        val small = listOf(
            point(0.0, 0.0),
            point(300.0, 0.0),
            point(300.0, 200.0),
            point(0.0, 200.0),
        )
        assertEquals(300 to 200, rectifiedSize(small, maxEdge = RECTIFY_MAX_EDGE_PX))
    }

    @Test
    fun `honours a declared sheet proportion without enlarging the plate`() {
        val corners = listOf(
            point(0.0, 0.0),
            point(400.0, 0.0),
            point(400.0, 400.0),
            point(0.0, 400.0),
        )
        // A4 portrait. The estimate was square; the declared proportion is narrower, so the WIDTH comes
        // in and the height stays — the plate never grows to satisfy an aspect.
        val size = rectifiedSize(corners, aspect = 210.0 / 297.0)!!
        assertEquals(400, size.second)
        assertEquals(DwPhotoMeasure.jsRound(400.0 * (210.0 / 297.0)).toInt(), size.first)
    }

    @Test
    fun `clamps the long edge to the ceiling`() {
        val huge = listOf(
            point(0.0, 0.0),
            point(4000.0, 0.0),
            point(4000.0, 3000.0),
            point(0.0, 3000.0),
        )
        val size = rectifiedSize(huge)!!
        assertEquals(RECTIFY_MAX_EDGE_PX, max(size.first, size.second))
        assertEquals(3000.0 / 4000.0, size.second.toDouble() / size.first, 0.01)
    }

    // ── THE JUSTIFICATION: local thresholding against a lamp gradient ────────────────────────────

    /**
     * A page as a tube light on one wall actually leaves it.
     *
     * ILLUMINATION IS MULTIPLICATIVE, WHICH IS THE WHOLE REASON A GLOBAL THRESHOLD CANNOT WORK. What a
     * camera records is illumination times reflectance. The paper's reflectance is a constant of the
     * paper and the pencil's is a constant of the pencil, but the illumination ramps across the sheet,
     * so the RECORDED value of a pencil stroke at the bright end (0.38 x 255 = 97) is higher than the
     * recorded value of BLANK PAPER at the dark end (0.92 x 70 = 64). No single number can put those
     * two on the correct sides of itself. That is not a hard case for a global threshold; it is a
     * contradiction, and it is what the field photographs actually look like.
     */
    private class LampLitPage(val page: GreyPlane, val strokes: ByteArray)

    private fun lampLitPage(): LampLitPage {
        val width = 400
        val height = 200
        val paperReflectance = 0.92
        val inkReflectance = 0.38
        val strokes = ByteArray(width * height)
        val page = plane(width, height) { x, y ->
            val illumination = 70 + (185.0 * x) / (width - 1)
            // Vertical strokes every 40px, three pixels wide, all the way down the page — so both
            // halves of the page carry the same drawing and any difference in the result is the
            // method, not the input.
            val isStroke = x % 40 in 18..20 && y > 10 && y < height - 10
            if (isStroke) strokes[y * width + x] = 1
            illumination * (if (isStroke) inkReflectance else paperReflectance)
        }
        return LampLitPage(page, strokes)
    }

    /** Fraction of the pixels in [mask] that came out black, over the given horizontal band. */
    private fun blackFraction(
        result: GreyPlane,
        mask: ByteArray,
        wanted: Int,
        fromX: Int,
        toX: Int,
    ): Double {
        var hits = 0
        var total = 0
        for (y in 0 until result.height) {
            for (x in fromX until toX) {
                val index = y * result.width + x
                if (mask[index].toInt() != wanted) continue
                total += 1
                if (result.at(index) < 128) hits += 1
            }
        }
        return if (total == 0) 0.0 else hits.toDouble() / total
    }

    /**
     * Otsu's global threshold, and the plate it produces — THE COUNTEREXAMPLE, WHICH LIVES HERE AND
     * NOT IN THE MODULE.
     *
     * The web keeps `otsuThreshold` and `globalThreshold` in `sketchRectify.ts` because its automatic
     * corner guess genuinely uses Otsu (telling bright sheet from dark table IS a two-population
     * question about a whole frame). That guess is not ported, so on this client a global threshold
     * has no caller at all — and R8 is enabled on the release build, so an exported function nothing
     * calls is not merely useless, it is deleted from the shipped APK. Shipping it to satisfy a test
     * would be shipping a definition that is not a use.
     *
     * It belongs here anyway: it is not a capability this app offers, it is the thing the app's actual
     * method is being measured AGAINST. Otsu rather than a hand-picked level or a plain mean, so that
     * "a global threshold cannot do this" is a statement about the strongest global method there is
     * and not about a strawman.
     */
    private fun otsuThreshold(plane: GreyPlane): Int {
        val histogram = DoubleArray(256)
        for (index in 0 until plane.data.size) histogram[plane.at(index)] += 1
        val total = plane.data.size
        if (total == 0) return 128

        var sumAll = 0.0
        for (level in 0 until 256) sumAll += level * histogram[level]

        var backgroundWeight = 0.0
        var backgroundSum = 0.0
        var best = 0
        var bestVariance = -1.0
        for (level in 0 until 256) {
            backgroundWeight += histogram[level]
            if (backgroundWeight == 0.0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0.0) break
            backgroundSum += level * histogram[level]
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (sumAll - backgroundSum) / foregroundWeight
            val between = backgroundWeight * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (between > bestVariance) {
                bestVariance = between
                best = level
            }
        }
        return best
    }

    private fun globalThreshold(plane: GreyPlane, level: Int): GreyPlane {
        val data = ByteArray(plane.data.size)
        for (index in data.indices) {
            data[index] = if (plane.at(index) <= level) 0.toByte() else 255.toByte()
        }
        return GreyPlane(data, plane.width, plane.height)
    }

    @Test
    fun `the local method recovers the strokes at both ends and leaves the paper clean`() {
        val fixture = lampLitPage()
        val half = fixture.page.width / 2
        val local = sauvolaThreshold(fixture.page)

        // Strokes recovered in the DARK half and in the BRIGHT half alike.
        assertTrue(blackFraction(local, fixture.strokes, 1, 0, half) > 0.9)
        assertTrue(blackFraction(local, fixture.strokes, 1, half, fixture.page.width) > 0.9)
        // And the blank paper stayed blank at both ends — the Niblack failure this method avoids.
        assertTrue(blackFraction(local, fixture.strokes, 0, 0, half) < 0.05)
        assertTrue(blackFraction(local, fixture.strokes, 0, half, fixture.page.width) < 0.05)
    }

    @Test
    fun `the best global threshold turns the dark half of the page black`() {
        val fixture = lampLitPage()
        val half = fixture.page.width / 2
        val global = globalThreshold(fixture.page, otsuThreshold(fixture.page))

        // The blank paper of the dark half comes out as ink. This is the failure in one line.
        assertTrue(blackFraction(global, fixture.strokes, 0, 0, half) > 0.5)
    }

    /**
     * The assertion that is the point of the whole file: the two methods are run over the SAME page
     * and the difference between them is pinned. Replacing the local threshold with a global one to
     * "simplify" the module fails here, and the failure names the damage.
     */
    @Test
    fun `the difference between the two methods is the feature`() {
        val fixture = lampLitPage()
        val half = fixture.page.width / 2
        val local = sauvolaThreshold(fixture.page)
        val global = globalThreshold(fixture.page, otsuThreshold(fixture.page))

        val localFlood = blackFraction(local, fixture.strokes, 0, 0, half)
        val globalFlood = blackFraction(global, fixture.strokes, 0, 0, half)
        // Not "a bit better" — more than an order of magnitude less blank paper wrongly inked.
        assertTrue(globalFlood > localFlood * 10)
        assertTrue(globalFlood - localFlood > 0.45)
    }

    @Test
    fun `blank paper does not come out as speckle`() {
        // The Sauvola-over-Niblack case, isolated: a page with a lamp gradient and NO strokes at all
        // must threshold to entirely white. Niblack's `m + k*s` would put roughly half the sensor
        // noise below the threshold and produce a page of dirt.
        val blank = plane(240, 180) { x, _ -> (70 + (185.0 * x) / 239) * 0.92 }
        val result = sauvolaThreshold(blank)
        var dark = 0
        for (index in 0 until result.data.size) if (result.at(index) < 128) dark += 1
        assertEquals(0, dark)
    }

    @Test
    fun `the window is odd and comfortably wider than a pencil stroke`() {
        // The constraint SAUVOLA_WINDOW_FRACTION exists to satisfy — a window narrower than a stroke
        // makes the local mean equal the ink and erases the stroke it was supposed to find.
        val window = sauvolaWindow(plane(1200, 900) { _, _ -> 200.0 })
        assertEquals(1, window % 2)
        assertTrue(window > 6 * 3)
    }

    /**
     * The summed-area tables are what make a window's mean and variance O(1), and this is the case
     * that says they are the right numbers.
     *
     * IT IS NOT A TIMING TEST. It compares the table's four-corner subtraction against a brute-force
     * sum over the same window, for windows at the edges and corners of the plane where the clipping
     * arithmetic is easiest to get wrong by one. A sign error or an off-by-one in the stride is the
     * whole failure mode of a summed-area table: it produces plausible numbers everywhere and wrong
     * ones along one border, which in a plate is a black or white frame around the sheet that nobody
     * questions because a scanned page often has one.
     */
    @Test
    fun `the integral tables agree with a brute-force window sum`() {
        // Deliberately not a flat plane and not a gradient: a value that varies in both axes and does
        // not repeat, so a transposed index or a wrong stride cannot coincidentally agree.
        val width = 23
        val height = 17
        val source = plane(width, height) { x, y -> ((x * 37 + y * 91) % 251).toDouble() }
        val tables = DwSketchRectify.integralPlanes(source)

        for (centreY in listOf(0, 1, 8, height - 2, height - 1)) {
            for (centreX in listOf(0, 1, 11, width - 2, width - 1)) {
                for (radius in listOf(1, 3, 7)) {
                    val top = max(0, centreY - radius)
                    val bottom = min(height - 1, centreY + radius)
                    val left = max(0, centreX - radius)
                    val right = min(width - 1, centreX + radius)

                    var bruteSum = 0.0
                    var bruteSquares = 0.0
                    for (y in top..bottom) {
                        for (x in left..right) {
                            val value = source.at(y * width + x).toDouble()
                            bruteSum += value
                            bruteSquares += value * value
                        }
                    }

                    val topRow = top * tables.stride
                    val bottomRow = (bottom + 1) * tables.stride
                    val tableSum = tables.sum[bottomRow + right + 1] - tables.sum[bottomRow + left] -
                        tables.sum[topRow + right + 1] + tables.sum[topRow + left]
                    val tableSquares = tables.squares[bottomRow + right + 1] -
                        tables.squares[bottomRow + left] - tables.squares[topRow + right + 1] +
                        tables.squares[topRow + left]

                    assertEquals("sum at ($centreX,$centreY) r=$radius", bruteSum, tableSum, 1e-9)
                    assertEquals(
                        "squares at ($centreX,$centreY) r=$radius",
                        bruteSquares,
                        tableSquares,
                        1e-9,
                    )
                }
            }
        }
    }

    // ── makePlate ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `produces a bilevel plate by default and a grey one on request`() {
        val fixture = lampLitPage()
        val corners = listOf(
            point(5.0, 5.0),
            point(394.0, 5.0),
            point(394.0, 194.0),
            point(5.0, 194.0),
        )

        val lineArt = makePlate(fixture.page, corners)
        assertTrue(lineArt is DwPlateResult.Plate)
        // Bilevel: every pixel is exactly 0 or exactly 255, which is what makes it printable line art.
        val values = sortedSetOf<Int>()
        val lineArtPlane = (lineArt as DwPlateResult.Plate).plane
        for (index in 0 until lineArtPlane.data.size) values.add(lineArtPlane.at(index))
        assertEquals(listOf(0, 255), values.toList())

        // "Straighten it but leave the tones alone" is a first-class outcome, not a debug switch: a
        // faint construction line lives in the greys and thresholding destroys it.
        val grey = makePlate(fixture.page, corners, DwPlateOptions(lineArt = false))
        assertTrue(grey is DwPlateResult.Plate)
        val greyValues = sortedSetOf<Int>()
        val greyPlane = (grey as DwPlateResult.Plate).plane
        for (index in 0 until greyPlane.data.size) greyValues.add(greyPlane.at(index))
        assertTrue(greyValues.size > 2)
    }

    @Test
    fun `refuses with a sentence a designer can act on`() {
        val result = makePlate(
            plane(50, 50) { _, _ -> 200.0 },
            listOf(point(0.0, 0.0), point(10.0, 10.0), point(20.0, 20.0), point(30.0, 30.0)),
        )
        assertTrue(result is DwPlateResult.Refusal)
        // A sentence about the HANDLES, because that is the only thing the designer can move.
        assertTrue(
            (result as DwPlateResult.Refusal).reason.contains("handle", ignoreCase = true)
        )
    }
}
