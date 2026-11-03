package com.designprototype.workshop.data

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

sealed interface DwPlateResult {

    data class Refusal(val reason: String) : DwPlateResult

    data class Plate(
        val plane: GreyPlane,
        val elapsedMs: Long,
    ) : DwPlateResult
}

data class DwPlateOptions(
    val aspect: Double? = null,
    val maxEdge: Int = DwSketchRectify.RECTIFY_MAX_EDGE_PX,
    val lineArt: Boolean = true,
    val window: Int? = null,
    val k: Double = DwSketchRectify.SAUVOLA_K,
    val r: Double = DwSketchRectify.SAUVOLA_R,
)

object DwSketchRectify {

    const val RECTIFY_MAX_EDGE_PX = 1600

    const val SAUVOLA_K = 0.2

    const val SAUVOLA_R = 128.0

    const val SAUVOLA_WINDOW_FRACTION = 1.0 / 24.0

    const val SAUVOLA_MIN_WINDOW = 15

    const val OUTSIDE_VALUE = 255.0

    private fun finite(vararg values: Double): Boolean {
        for (value in values) if (!value.isFinite()) return false
        return true
    }

    fun orderCorners(points: List<DwPoint>): List<DwPoint>? {
        if (points.size != 4) return null
        for (point in points) if (!finite(point.x, point.y)) return null

        val centreX = (points[0].x + points[1].x + points[2].x + points[3].x) / 4
        val centreY = (points[0].y + points[1].y + points[2].y + points[3].y) / 4

        val ring = points.sortedBy { atan2(it.y - centreY, it.x - centreX) }

        var start = 0
        for (index in 1 until 4) {
            if (ring[index].x + ring[index].y < ring[start].x + ring[start].y) start = index
        }
        return listOf(ring[start % 4], ring[(start + 1) % 4], ring[(start + 2) % 4], ring[(start + 3) % 4])
    }

    fun rectifiedSize(
        corners: List<DwPoint>,
        maxEdge: Int = RECTIFY_MAX_EDGE_PX,
        aspect: Double? = null,
    ): Pair<Int, Int>? {
        if (corners.size != 4) return null
        if (maxEdge < 1) return null
        for (point in corners) if (!finite(point.x, point.y)) return null
        val (topLeft, topRight, bottomRight, bottomLeft) = corners

        val top = hypot(topRight.x - topLeft.x, topRight.y - topLeft.y)
        val bottom = hypot(bottomRight.x - bottomLeft.x, bottomRight.y - bottomLeft.y)
        val left = hypot(bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y)
        val right = hypot(bottomRight.x - topRight.x, bottomRight.y - topRight.y)

        var width = max(top, bottom)
        var height = max(left, right)
        if (!(width >= 1.0) || !(height >= 1.0)) return null

        if (aspect != null) {
            if (!finite(aspect) || aspect <= 0.0) return null
            if (width / height > aspect) width = height * aspect else height = width / aspect
        }

        val scale = min(1.0, maxEdge.toDouble() / max(width, height))
        return max(1L, DwPhotoMeasure.jsRound(width * scale).toLong()).toInt() to
            max(1L, DwPhotoMeasure.jsRound(height * scale).toLong()).toInt()
    }

    fun bilinearSample(plane: GreyPlane, x: Double, y: Double, outside: Double = OUTSIDE_VALUE): Double {
        if (!finite(x, y)) return outside
        if (x < -0.5 || y < -0.5 || x > plane.width - 0.5 || y > plane.height - 0.5) return outside

        val x0 = floor(x)
        val y0 = floor(y)
        val fx = x - x0
        val fy = y - y0
        val left = min(max(x0.toInt(), 0), plane.width - 1)
        val right = min(max(x0.toInt() + 1, 0), plane.width - 1)
        val top = min(max(y0.toInt(), 0), plane.height - 1)
        val bottom = min(max(y0.toInt() + 1, 0), plane.height - 1)

        val topLeft = plane.at(top * plane.width + left).toDouble()
        val topRight = plane.at(top * plane.width + right).toDouble()
        val bottomLeft = plane.at(bottom * plane.width + left).toDouble()
        val bottomRight = plane.at(bottom * plane.width + right).toDouble()

        val upper = topLeft + (topRight - topLeft) * fx
        val lower = bottomLeft + (bottomRight - bottomLeft) * fx
        return upper + (lower - upper) * fy
    }

    fun rectifyHomography(corners: List<DwPoint>, width: Int, height: Int): DwHomography? {
        if (corners.size != 4) return null
        if (width < 2 || height < 2) return null
        val destination = listOf(
            DwPoint(0.0, 0.0),
            DwPoint(width - 1.0, 0.0),
            DwPoint(width - 1.0, height - 1.0),
            DwPoint(0.0, height - 1.0),
        )
        return DwPhotoMeasure.solveHomography(destination, corners)
    }

    fun rectifyPlane(plane: GreyPlane, corners: List<DwPoint>, width: Int, height: Int): GreyPlane? {
        val homography = rectifyHomography(corners, width, height) ?: return null
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val source = DwPhotoMeasure.applyHomography(homography, DwPoint(x.toDouble(), y.toDouble()))
                data[rowOffset + x] = DwImageQuality.clampToByte(bilinearSample(plane, source.x, source.y)).toByte()
            }
        }
        return GreyPlane(data, width, height)
    }

    class DwIntegralPlanes(val sum: DoubleArray, val squares: DoubleArray, val stride: Int)

    fun integralPlanes(plane: GreyPlane): DwIntegralPlanes {
        val stride = plane.width + 1
        val sum = DoubleArray(stride * (plane.height + 1))
        val squares = DoubleArray(stride * (plane.height + 1))
        for (y in 0 until plane.height) {
            var rowSum = 0.0
            var rowSquares = 0.0
            val source = y * plane.width
            val above = y * stride
            val current = (y + 1) * stride
            for (x in 0 until plane.width) {
                val value = plane.at(source + x).toDouble()
                rowSum += value
                rowSquares += value * value
                sum[current + x + 1] = sum[above + x + 1] + rowSum
                squares[current + x + 1] = squares[above + x + 1] + rowSquares
            }
        }
        return DwIntegralPlanes(sum, squares, stride)
    }

    fun sauvolaWindow(plane: GreyPlane): Int {
        val shortEdge = min(plane.width, plane.height)
        val scaled = DwPhotoMeasure.jsRound(shortEdge * SAUVOLA_WINDOW_FRACTION).toInt()
        val bounded = max(SAUVOLA_MIN_WINDOW, scaled)
        return if (bounded % 2 == 1) bounded else bounded + 1
    }

    fun sauvolaThreshold(
        plane: GreyPlane,
        window: Int = sauvolaWindow(plane),
        k: Double = SAUVOLA_K,
        r: Double = SAUVOLA_R,
    ): GreyPlane {
        val radius = max(1, window / 2)
        val tables = integralPlanes(plane)
        val sum = tables.sum
        val squares = tables.squares
        val stride = tables.stride
        val data = ByteArray(plane.width * plane.height)

        for (y in 0 until plane.height) {
            val top = max(0, y - radius)
            val bottom = min(plane.height - 1, y + radius)
            val topRow = top * stride
            val bottomRow = (bottom + 1) * stride
            val rows = bottom - top + 1
            for (x in 0 until plane.width) {
                val left = max(0, x - radius)
                val right = min(plane.width - 1, x + radius)
                val count = (rows * (right - left + 1)).toDouble()

                val total = sum[bottomRow + right + 1] - sum[bottomRow + left] -
                    sum[topRow + right + 1] + sum[topRow + left]
                val totalSquares = squares[bottomRow + right + 1] - squares[bottomRow + left] -
                    squares[topRow + right + 1] + squares[topRow + left]

                val mean = total / count
                val variance = max(0.0, totalSquares / count - mean * mean)
                val threshold = mean * (1 + k * (sqrt(variance) / r - 1))
                val index = y * plane.width + x
                data[index] = if (plane.at(index) < threshold) 0.toByte() else 255.toByte()
            }
        }
        return GreyPlane(data, plane.width, plane.height)
    }

    fun makePlate(plane: GreyPlane, corners: List<DwPoint>, options: DwPlateOptions = DwPlateOptions()): DwPlateResult {
        val startedAt = System.nanoTime()
        val size = rectifiedSize(corners, maxEdge = options.maxEdge, aspect = options.aspect)
            ?: return DwPlateResult.Refusal(
                "Those marks don't enclose a sheet. Drag handles to the paper's corners."
            )
        val rectified = rectifyPlane(plane, corners, size.first, size.second)
            ?: return DwPlateResult.Refusal(
                "Corners are in a straight line. Move a handle."
            )
        val finished = if (!options.lineArt) {
            rectified
        } else {
            sauvolaThreshold(
                rectified,
                window = options.window ?: sauvolaWindow(rectified),
                k = options.k,
                r = options.r,
            )
        }
        return DwPlateResult.Plate(finished, (System.nanoTime() - startedAt) / 1_000_000)
    }
}

internal fun dwOtsuThreshold(plane: GreyPlane): Int {
    val histogram = DoubleArray(256)
    for (index in plane.data.indices) histogram[plane.at(index)] += 1.0
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
        val delta = backgroundMean - foregroundMean
        val between = backgroundWeight * foregroundWeight * delta * delta
        if (between > bestVariance) {
            bestVariance = between
            best = level
        }
    }
    return best
}
