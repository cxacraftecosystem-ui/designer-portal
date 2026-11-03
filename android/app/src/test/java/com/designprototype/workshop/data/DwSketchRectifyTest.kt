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

class DwSketchRectifyTest {

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

    private val tilt = DwHomography(
        1.1, 0.22, 140.0,
        0.13, 1.05, 90.0,
        0.00042, 0.00026, 1.0,
    )

    private fun project(p: DwPoint): DwPoint = DwPhotoMeasure.applyHomography(tilt, p)

    @Test
    fun `puts any click order into TL TR BR BL`() {
        val marks = listOf(
            point(320.0, 40.0),
            point(30.0, 220.0),
            point(20.0, 30.0),
            point(350.0, 260.0),
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
        assertEquals(50.0, bilinearSample(tiny, 0.5, 0.0), 1e-12)
        assertEquals(100.0, bilinearSample(tiny, 0.0, 0.5), 1e-12)
        assertEquals(138.75, bilinearSample(tiny, 0.5, 0.5), 1e-12)
        assertEquals(25.0, bilinearSample(tiny, 0.25, 0.0), 1e-12)
        assertEquals(116.5625, bilinearSample(tiny, 0.75, 0.25), 1e-12)
    }

    @Test
    fun `reads paper white outside the photograph rather than smearing the edge`() {
        assertEquals(255.0, bilinearSample(tiny, -0.6, 0.0), 0.0)
        assertEquals(255.0, bilinearSample(tiny, 1.6, 0.0), 0.0)
        assertEquals(255.0, bilinearSample(tiny, 0.0, -0.51), 0.0)
        assertEquals(255.0, bilinearSample(tiny, 0.0, 1.51), 0.0)
        assertEquals(255.0, bilinearSample(tiny, Double.NaN, 0.0), 0.0)
        assertEquals(0.0, bilinearSample(tiny, -0.5, 0.0), 1e-12)
        assertEquals(0.0, bilinearSample(tiny, -0.25, 0.0), 1e-12)
    }

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

        var emptyRows = 0
        for (y in 25 until 195) {
            var darkest = 255
            for (x in 0 until 220) darkest = min(darkest, at(rectified!!, x, y))
            if (darkest > 128) emptyRows += 1
        }
        assertEquals(0, emptyRows)
    }

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
        assertTrue("worst error was $worst counts", worst <= 1.0)
    }

    @Test
    fun `straightens a tilted rectangle back into a rectangle`() {
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

    @Test
    fun `takes the longer of each pair of opposite edges`() {
        val corners = listOf(
            point(0.0, 0.0),
            point(400.0, 0.0),
            point(300.0, 200.0),
            point(0.0, 200.0),
        )
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

    private class LampLitPage(val page: GreyPlane, val strokes: ByteArray)

    private fun lampLitPage(): LampLitPage {
        val width = 400
        val height = 200
        val paperReflectance = 0.92
        val inkReflectance = 0.38
        val strokes = ByteArray(width * height)
        val page = plane(width, height) { x, y ->
            val illumination = 70 + (185.0 * x) / (width - 1)
            val isStroke = x % 40 in 18..20 && y > 10 && y < height - 10
            if (isStroke) strokes[y * width + x] = 1
            illumination * (if (isStroke) inkReflectance else paperReflectance)
        }
        return LampLitPage(page, strokes)
    }

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

        assertTrue(blackFraction(local, fixture.strokes, 1, 0, half) > 0.9)
        assertTrue(blackFraction(local, fixture.strokes, 1, half, fixture.page.width) > 0.9)
        assertTrue(blackFraction(local, fixture.strokes, 0, 0, half) < 0.05)
        assertTrue(blackFraction(local, fixture.strokes, 0, half, fixture.page.width) < 0.05)
    }

    @Test
    fun `the best global threshold turns the dark half of the page black`() {
        val fixture = lampLitPage()
        val half = fixture.page.width / 2
        val global = globalThreshold(fixture.page, dwOtsuThreshold(fixture.page))

        assertTrue(blackFraction(global, fixture.strokes, 0, 0, half) > 0.5)
    }

    @Test
    fun `the difference between the two methods is the feature`() {
        val fixture = lampLitPage()
        val half = fixture.page.width / 2
        val local = sauvolaThreshold(fixture.page)
        val global = globalThreshold(fixture.page, dwOtsuThreshold(fixture.page))

        val localFlood = blackFraction(local, fixture.strokes, 0, 0, half)
        val globalFlood = blackFraction(global, fixture.strokes, 0, 0, half)
        assertTrue(globalFlood > localFlood * 10)
        assertTrue(globalFlood - localFlood > 0.45)
    }

    @Test
    fun `blank paper does not come out as speckle`() {
        val blank = plane(240, 180) { x, _ -> (70 + (185.0 * x) / 239) * 0.92 }
        val result = sauvolaThreshold(blank)
        var dark = 0
        for (index in 0 until result.data.size) if (result.at(index) < 128) dark += 1
        assertEquals(0, dark)
    }

    @Test
    fun `the window is odd and comfortably wider than a pencil stroke`() {
        val window = sauvolaWindow(plane(1200, 900) { _, _ -> 200.0 })
        assertEquals(1, window % 2)
        assertTrue(window > 6 * 3)
    }

    @Test
    fun `the integral tables agree with a brute-force window sum`() {
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
        val values = sortedSetOf<Int>()
        val lineArtPlane = (lineArt as DwPlateResult.Plate).plane
        for (index in 0 until lineArtPlane.data.size) values.add(lineArtPlane.at(index))
        assertEquals(listOf(0, 255), values.toList())

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
        assertTrue(
            (result as DwPlateResult.Refusal).reason.contains("handle", ignoreCase = true)
        )
    }
}
