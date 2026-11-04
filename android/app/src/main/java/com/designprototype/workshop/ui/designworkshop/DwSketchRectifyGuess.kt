package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwImageQuality
import com.designprototype.workshop.data.DwPhotoMeasure
import com.designprototype.workshop.data.DwPoint
import com.designprototype.workshop.data.DwSketchRectify
import com.designprototype.workshop.data.GreyPlane
import com.designprototype.workshop.data.dwOtsuThreshold
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class DwCornerGuess(
    val corners: List<DwPoint>,
    val fill: Double,
    val frameShare: Double,
    val edgeSupport: Double,
)

internal const val DW_GUESS_EDGE_PX = 480

internal const val DW_GUESS_MIN_FILL = 0.85

internal const val DW_GUESS_MIN_FRAME_SHARE = 0.15

internal const val DW_GUESS_MIN_EDGE_SUPPORT = 0.85

internal const val DW_GUESS_MIN_CONTRAST = 12.0

internal const val DW_GUESS_CLOSE_FRACTION = 1.0 / 120.0

internal const val DW_EDGE_SAMPLES = 32

private fun dwCloseBrightMask(mask: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
    if (radius < 1) return mask

    fun boxPass(source: ByteArray, keep: (Double, Double) -> Boolean): ByteArray {
        val stride = width + 1
        val table = DoubleArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0.0
            for (x in 0 until width) {
                row += source[y * width + x].toDouble()
                table[(y + 1) * stride + x + 1] = table[y * stride + x + 1] + row
            }
        }
        val out = ByteArray(source.size)
        for (y in 0 until height) {
            val top = max(0, y - radius)
            val bottom = min(height - 1, y + radius)
            val topRow = top * stride
            val bottomRow = (bottom + 1) * stride
            for (x in 0 until width) {
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                val sum = table[bottomRow + right + 1] - table[bottomRow + left] -
                    table[topRow + right + 1] + table[topRow + left]
                val count = ((bottom - top + 1) * (right - left + 1)).toDouble()
                out[y * width + x] = if (keep(sum, count)) 1.toByte() else 0.toByte()
            }
        }
        return out
    }

    val dilated = boxPass(mask) { sum, _ -> sum > 0.0 }
    return boxPass(dilated) { sum, count -> sum == count }
}

internal fun dwEdgeSupport(
    corners: List<DwPoint>,
    bright: ByteArray,
    width: Int,
    height: Int,
    samples: Int = DW_EDGE_SAMPLES,
): Double {
    val centreX = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) / 4
    val centreY = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) / 4
    var weakest = 1.0
    for (edge in 0 until 4) {
        val from = corners[edge]
        val to = corners[(edge + 1) % 4]
        var hits = 0
        var total = 0
        for (step in 1 until samples) {
            val t = step.toDouble() / samples
            val x = from.x + (to.x - from.x) * t
            val y = from.y + (to.y - from.y) * t
            val towardX = centreX - x
            val towardY = centreY - y
            val length = hypot(towardX, towardY)
            if (length < 1) continue
            val px = DwPhotoMeasure.jsRound(x + (towardX / length) * 2).toInt()
            val py = DwPhotoMeasure.jsRound(y + (towardY / length) * 2).toInt()
            if (px < 0 || py < 0 || px >= width || py >= height) continue
            total += 1
            if (bright[py * width + px].toInt() == 1) hits += 1
        }
        if (total == 0) return 0.0
        weakest = min(weakest, hits.toDouble() / total)
    }
    return weakest
}

private fun dwShoelaceArea(corners: List<DwPoint>): Double {
    var total = 0.0
    for (index in 0 until 4) {
        val current = corners[index]
        val next = corners[(index + 1) % 4]
        total += current.x * next.y - next.x * current.y
    }
    return abs(total) / 2
}

internal fun dwGuessSheetCorners(plane: GreyPlane): DwCornerGuess? {
    if (plane.width < 8 || plane.height < 8) return null

    val scale = min(1.0, DW_GUESS_EDGE_PX.toDouble() / max(plane.width, plane.height))
    val width = max(4, DwPhotoMeasure.jsRound(plane.width * scale).toInt())
    val height = max(4, DwPhotoMeasure.jsRound(plane.height * scale).toInt())
    val small = if (scale < 1.0) DwImageQuality.resampleGrey(plane, width, height) else plane

    if (DwImageQuality.contrastStdDev(small) < DW_GUESS_MIN_CONTRAST) return null

    val cut = dwOtsuThreshold(small)
    val raw = ByteArray(small.width * small.height)
    for (index in raw.indices) raw[index] = if (small.at(index) > cut) 1.toByte() else 0.toByte()
    val radius = max(
        1,
        DwPhotoMeasure.jsRound(min(small.width, small.height) * DW_GUESS_CLOSE_FRACTION).toInt(),
    )
    val bright = dwCloseBrightMask(raw, small.width, small.height, radius)

    val labels = IntArray(bright.size) { -1 }
    val stack = IntArray(bright.size)
    var stackTop = 0
    var bestArea = 0
    var bestLabel = -1
    var label = 0
    for (seed in bright.indices) {
        if (bright[seed].toInt() == 0 || labels[seed] != -1) continue
        labels[seed] = label
        stack[stackTop++] = seed
        var area = 0
        while (stackTop > 0) {
            val index = stack[--stackTop]
            area += 1
            val x = index % small.width
            val y = (index - x) / small.width
            if (x > 0 && bright[index - 1].toInt() == 1 && labels[index - 1] == -1) {
                labels[index - 1] = label
                stack[stackTop++] = index - 1
            }
            if (x < small.width - 1 && bright[index + 1].toInt() == 1 && labels[index + 1] == -1) {
                labels[index + 1] = label
                stack[stackTop++] = index + 1
            }
            if (y > 0 && bright[index - small.width].toInt() == 1 && labels[index - small.width] == -1) {
                labels[index - small.width] = label
                stack[stackTop++] = index - small.width
            }
            if (y < small.height - 1 &&
                bright[index + small.width].toInt() == 1 &&
                labels[index + small.width] == -1
            ) {
                labels[index + small.width] = label
                stack[stackTop++] = index + small.width
            }
        }
        if (area > bestArea) {
            bestArea = area
            bestLabel = label
        }
        label += 1
    }
    if (bestLabel == -1 || bestArea < 16) return null

    var topLeft = DwPoint(0.0, 0.0)
    var topRight = DwPoint(0.0, 0.0)
    var bottomRight = DwPoint(0.0, 0.0)
    var bottomLeft = DwPoint(0.0, 0.0)
    var minSum = Double.POSITIVE_INFINITY
    var maxSum = Double.NEGATIVE_INFINITY
    var minDiff = Double.POSITIVE_INFINITY
    var maxDiff = Double.NEGATIVE_INFINITY
    for (index in labels.indices) {
        if (labels[index] != bestLabel) continue
        val x = index % small.width
        val y = (index - x) / small.width
        if (x + y < minSum) {
            minSum = (x + y).toDouble()
            topLeft = DwPoint(x.toDouble(), y.toDouble())
        }
        if (x + y > maxSum) {
            maxSum = (x + y).toDouble()
            bottomRight = DwPoint(x.toDouble(), y.toDouble())
        }
        if (x - y > maxDiff) {
            maxDiff = (x - y).toDouble()
            topRight = DwPoint(x.toDouble(), y.toDouble())
        }
        if (x - y < minDiff) {
            minDiff = (x - y).toDouble()
            bottomLeft = DwPoint(x.toDouble(), y.toDouble())
        }
    }

    val ordered = DwSketchRectify.orderCorners(listOf(topLeft, topRight, bottomRight, bottomLeft))
        ?: return null
    val quadArea = dwShoelaceArea(ordered)
    if (quadArea <= 0) return null

    val fill = bestArea / quadArea
    val frameShare = quadArea / (small.width.toDouble() * small.height)
    val support = dwEdgeSupport(ordered, bright, small.width, small.height)
    if (fill < DW_GUESS_MIN_FILL ||
        frameShare < DW_GUESS_MIN_FRAME_SHARE ||
        support < DW_GUESS_MIN_EDGE_SUPPORT
    ) {
        return null
    }

    val back = 1 / (if (scale < 1.0) scale else 1.0)
    val lifted = DwSketchRectify.orderCorners(
        ordered.map { DwPoint((it.x + 0.5) * back, (it.y + 0.5) * back) },
    ) ?: return null
    return DwCornerGuess(corners = lifted, fill = fill, frameShare = frameShare, edgeSupport = support)
}
