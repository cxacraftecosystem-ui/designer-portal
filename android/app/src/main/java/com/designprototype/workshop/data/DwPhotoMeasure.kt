package com.designprototype.workshop.data

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class DwPoint(val x: Double, val y: Double)

data class DwHomography(
    val h11: Double, val h12: Double, val h13: Double,
    val h21: Double, val h22: Double, val h23: Double,
    val h31: Double, val h32: Double, val h33: Double,
) {
    val allFinite: Boolean
        get() = h11.isFinite() && h12.isFinite() && h13.isFinite() &&
            h21.isFinite() && h22.isFinite() && h23.isFinite() &&
            h31.isFinite() && h32.isFinite() && h33.isFinite()
}

data class DwSegment(val from: DwPoint, val to: DwPoint)

data class DwScaleReference(
    val from: DwPoint,
    val to: DwPoint,
    val length: Double,
    val unit: String,
)

data class DwKnownRectangle(val width: Double, val height: Double, val unit: String)

sealed interface DwMeasureResult {

    data class Refusal(val reason: String) : DwMeasureResult

    data class Measurement(
        val method: String,
        val value: Double,
        val unit: String,
        val uncertainty: Double,
        val relativeUncertainty: Double,
        val referencePixels: Double,
        val targetPixels: Double,
        val uncorrectedValue: Double? = null,
        val tiltCorrection: Double? = null,
    ) : DwMeasureResult
}

data class DwRoundedValue(val value: Double, val decimals: Int)

object DwPhotoMeasure {

    const val METHOD_SCALE = "SCALE"

    const val METHOD_RECTIFIED = "RECTIFIED"

    val LENGTH_UNITS: Map<String, Double> = mapOf(
        "mm" to 1.0,
        "cm" to 10.0,
        "m" to 1000.0,
        "in" to 25.4,
    )

    const val MIN_REFERENCE_PIXELS = 40.0

    const val SCREEN_MARK_SIGMA_PX = 1.5

    const val DEFAULT_MARK_SIGMA_PX = 2.0

    private const val COLLINEAR_SIN = 0.02

    private const val MIN_HORIZON_MARGIN = 0.05

    private val SQRT2 = sqrt(2.0)

    private fun finite(vararg values: Double): Boolean {
        for (value in values) if (!value.isFinite()) return false
        return true
    }

    private fun pointsFinite(points: List<DwPoint>): Boolean {
        for (point in points) if (!finite(point.x, point.y)) return false
        return true
    }

    private fun sq(value: Double): Double = value * value

    internal fun jsRound(value: Double): Double {
        if (!value.isFinite()) return value
        val floored = floor(value)
        val rounded = if (value - floored >= 0.5) floored + 1.0 else floored
        if (rounded == 0.0 && (value < 0.0 || 1.0 / value < 0.0)) return -0.0
        return rounded
    }

    fun distanceBetween(a: DwPoint, b: DwPoint): Double = hypot(b.x - a.x, b.y - a.y)

    fun distanceSigma(markSigmaPx: Double): Double = SQRT2 * markSigmaPx

    fun markSigmaForDisplayScale(
        displayScale: Double,
        screenSigmaPx: Double = SCREEN_MARK_SIGMA_PX,
    ): Double {
        if (!finite(displayScale, screenSigmaPx) || displayScale <= 0.0 || screenSigmaPx < 0.0) {
            return DEFAULT_MARK_SIGMA_PX
        }
        return screenSigmaPx / displayScale
    }

    fun convertLength(value: Double, from: String, to: String): Double? {
        val fromFactor = LENGTH_UNITS[from] ?: return null
        val toFactor = LENGTH_UNITS[to] ?: return null
        if (!finite(value)) return null
        if (from == to) return value
        return (value * fromFactor) / toFactor
    }

    fun roundToUncertainty(value: Double, uncertainty: Double): DwRoundedValue {
        if (!finite(value, uncertainty) || uncertainty <= 0.0) {
            return DwRoundedValue(value, if (finite(value)) 2 else 0)
        }
        val place = floor(log10(uncertainty))
        val decimals = min(4.0, max(0.0, -place)).toInt()
        val factor = 10.0.pow(decimals)
        val rounded = jsRound(value * factor) / factor
        return DwRoundedValue(if (finite(rounded)) rounded else value, decimals)
    }

    fun solveLinearSystem(matrix: List<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        if (matrix.size != n) return null
        val a = Array(n) { matrix[it].copyOf() }
        val b = rhs.copyOf()
        for (row in a) {
            if (row.size != n) return null
            if (!row.all { it.isFinite() }) return null
        }
        if (!b.all { it.isFinite() }) return null

        for (column in 0 until n) {
            var pivotRow = column
            for (row in column + 1 until n) {
                if (abs(a[row][column]) > abs(a[pivotRow][column])) pivotRow = row
            }
            val pivot = a[pivotRow][column]
            if (pivot == 0.0) return null
            if (pivotRow != column) {
                val swap = a[pivotRow]
                a[pivotRow] = a[column]
                a[column] = swap
                val swapB = b[pivotRow]
                b[pivotRow] = b[column]
                b[column] = swapB
            }
            for (row in column + 1 until n) {
                val factor = a[row][column] / a[column][column]
                if (factor == 0.0) continue
                for (k in column until n) a[row][k] -= factor * a[column][k]
                b[row] -= factor * b[column]
            }
        }

        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = b[row]
            for (column in row + 1 until n) sum -= a[row][column] * x[column]
            x[row] = sum / a[row][row]
        }
        if (!x.all { it.isFinite() }) return null
        return x
    }

    fun applyHomography(h: DwHomography, point: DwPoint): DwPoint {
        val w = h.h31 * point.x + h.h32 * point.y + h.h33
        return DwPoint(
            x = (h.h11 * point.x + h.h12 * point.y + h.h13) / w,
            y = (h.h21 * point.x + h.h22 * point.y + h.h23) / w,
        )
    }

    private fun homographyDenominator(h: DwHomography, point: DwPoint): Double =
        h.h31 * point.x + h.h32 * point.y + h.h33

    private fun sineAt(a: DwPoint, b: DwPoint, c: DwPoint): Double {
        val ax = a.x - b.x
        val ay = a.y - b.y
        val cx = c.x - b.x
        val cy = c.y - b.y
        val lengths = hypot(ax, ay) * hypot(cx, cy)
        if (lengths == 0.0) return 0.0
        return abs(ax * cy - ay * cx) / lengths
    }

    private fun isSimpleConvexQuad(points: List<DwPoint>): Boolean {
        if (points.size != 4) return false
        var sign = 0
        for (index in 0 until 4) {
            val a = points[index]
            val b = points[(index + 1) % 4]
            val c = points[(index + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0.0) return false
            val current = if (cross > 0.0) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    fun solveHomography(from: List<DwPoint>, to: List<DwPoint>): DwHomography? {
        if (from.size != 4 || to.size != 4) return null
        if (!pointsFinite(from) || !pointsFinite(to)) return null

        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                if (distanceBetween(from[i], from[j]) == 0.0) return null
                if (distanceBetween(to[i], to[j]) == 0.0) return null
            }
        }
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                for (k in j + 1 until 4) {
                    if (sineAt(from[i], from[j], from[k]) < COLLINEAR_SIN) return null
                    if (sineAt(to[i], to[j], to[k]) < COLLINEAR_SIN) return null
                }
            }
        }

        val matrix = ArrayList<DoubleArray>(8)
        val rhs = DoubleArray(8)
        for (index in 0 until 4) {
            val x = from[index].x
            val y = from[index].y
            val u = to[index].x
            val v = to[index].y
            matrix.add(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u))
            rhs[index * 2] = u
            matrix.add(doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v))
            rhs[index * 2 + 1] = v
        }
        val solved = solveLinearSystem(matrix, rhs) ?: return null

        val h = DwHomography(
            solved[0], solved[1], solved[2],
            solved[3], solved[4], solved[5],
            solved[6], solved[7], 1.0,
        )
        if (!h.allFinite) return null
        return h
    }

    fun propagateUncertainty(
        coordinates: DoubleArray,
        sigma: Double,
        evaluate: (DoubleArray) -> Double?,
    ): Double? {
        if (!finite(sigma) || sigma < 0.0) return null
        if (!coordinates.all { it.isFinite() }) return null
        if (sigma == 0.0) return 0.0
        var sumOfSquares = 0.0
        for (index in coordinates.indices) {
            val up = coordinates.copyOf()
            val down = coordinates.copyOf()
            up[index] += sigma
            down[index] -= sigma
            val high = evaluate(up) ?: return null
            val low = evaluate(down) ?: return null
            if (!finite(high, low)) return null
            val contribution = (high - low) / 2
            sumOfSquares += contribution * contribution
        }
        val total = sqrt(sumOfSquares)
        return if (finite(total)) total else null
    }

    fun measureBySameScale(
        reference: DwScaleReference,
        target: DwSegment,
        markSigmaPx: Double = DEFAULT_MARK_SIGMA_PX,
        referenceLengthSigma: Double = 0.0,
    ): DwMeasureResult {
        if (!pointsFinite(listOf(reference.from, reference.to, target.from, target.to))) {
            return DwMeasureResult.Refusal("A mark is missing. Place all four again.")
        }
        if (!finite(reference.length, markSigmaPx, referenceLengthSigma) ||
            markSigmaPx < 0.0 || referenceLengthSigma < 0.0
        ) {
            return DwMeasureResult.Refusal("The reference length isn't a number.")
        }
        if (LENGTH_UNITS[reference.unit] == null) {
            return DwMeasureResult.Refusal("“${reference.unit}” isn't a length unit.")
        }
        if (reference.length <= 0.0) {
            return DwMeasureResult.Refusal("The reference length must be above zero.")
        }

        val referencePixels = distanceBetween(reference.from, reference.to)
        val targetPixels = distanceBetween(target.from, target.to)
        if (referencePixels < MIN_REFERENCE_PIXELS) {
            return DwMeasureResult.Refusal(
                "The reference is only ${jsRound(referencePixels).toLong()} pixels — too short. Zoom in and place the marks further apart."
            )
        }
        if (targetPixels == 0.0) {
            return DwMeasureResult.Refusal(
                "Both object marks are in the same place."
            )
        }

        val value = (reference.length * targetPixels) / referencePixels
        val sigmaDistance = distanceSigma(markSigmaPx)
        val relative = sqrt(
            sq(sigmaDistance / referencePixels) +
                sq(sigmaDistance / targetPixels) +
                sq(referenceLengthSigma / reference.length)
        )
        val uncertainty = relative * value
        if (!finite(value, relative, uncertainty)) {
            return DwMeasureResult.Refusal("These marks don't give a reliable measurement.")
        }

        return DwMeasureResult.Measurement(
            method = METHOD_SCALE,
            value = value,
            unit = reference.unit,
            uncertainty = uncertainty,
            relativeUncertainty = relative,
            referencePixels = referencePixels,
            targetPixels = targetPixels,
        )
    }

    fun measureByRectification(
        corners: List<DwPoint>,
        rectangle: DwKnownRectangle,
        target: DwSegment,
        markSigmaPx: Double = DEFAULT_MARK_SIGMA_PX,
    ): DwMeasureResult {
        if (corners.size != 4) {
            return DwMeasureResult.Refusal(
                "Mark all four corners (${corners.size} so far), in order around the sheet."
            )
        }
        if (!pointsFinite(corners) || !pointsFinite(listOf(target.from, target.to))) {
            return DwMeasureResult.Refusal(
                "A mark is missing. Place the corners and both ends again."
            )
        }
        if (!finite(rectangle.width, rectangle.height, markSigmaPx) || markSigmaPx < 0.0) {
            return DwMeasureResult.Refusal(
                "Rectangle size isn't a pair of numbers."
            )
        }
        if (LENGTH_UNITS[rectangle.unit] == null) {
            return DwMeasureResult.Refusal("“${rectangle.unit}” isn't a length unit.")
        }
        if (rectangle.width <= 0.0 || rectangle.height <= 0.0) {
            return DwMeasureResult.Refusal("Width and height must be above zero.")
        }
        if (distanceBetween(target.from, target.to) == 0.0) {
            return DwMeasureResult.Refusal(
                "Both object marks are in the same place."
            )
        }
        if (!isSimpleConvexQuad(corners)) {
            return DwMeasureResult.Refusal(
                "Corners cross over. Mark them in order around the rectangle."
            )
        }

        val world = listOf(
            DwPoint(0.0, 0.0),
            DwPoint(rectangle.width, 0.0),
            DwPoint(rectangle.width, rectangle.height),
            DwPoint(0.0, rectangle.height),
        )

        fun evaluate(c: DoubleArray): Double? {
            val marked = listOf(
                DwPoint(c[0], c[1]),
                DwPoint(c[2], c[3]),
                DwPoint(c[4], c[5]),
                DwPoint(c[6], c[7]),
            )
            val from = DwPoint(c[8], c[9])
            val to = DwPoint(c[10], c[11])
            val h = solveHomography(marked, world) ?: return null

            var cornerDenominator = 0.0
            var cornerSign = 0
            for (corner in marked) {
                val denominator = homographyDenominator(h, corner)
                if (!finite(denominator) || denominator == 0.0) return null
                val sign = if (denominator > 0.0) 1 else -1
                if (cornerSign == 0) cornerSign = sign else if (cornerSign != sign) return null
                cornerDenominator += abs(denominator)
            }
            cornerDenominator /= 4

            for (point in listOf(from, to)) {
                val denominator = homographyDenominator(h, point)
                if (!finite(denominator)) return null
                if ((if (denominator > 0.0) 1 else -1) != cornerSign) return null
                if (abs(denominator) < MIN_HORIZON_MARGIN * cornerDenominator) return null
            }

            val rectifiedFrom = applyHomography(h, from)
            val rectifiedTo = applyHomography(h, to)
            if (!pointsFinite(listOf(rectifiedFrom, rectifiedTo))) return null
            val length = distanceBetween(rectifiedFrom, rectifiedTo)
            return if (finite(length)) length else null
        }

        val coordinates = doubleArrayOf(
            corners[0].x, corners[0].y,
            corners[1].x, corners[1].y,
            corners[2].x, corners[2].y,
            corners[3].x, corners[3].y,
            target.from.x, target.from.y,
            target.to.x, target.to.y,
        )

        val value = evaluate(coordinates)
            ?: return DwMeasureResult.Refusal(
                "Those corners can't be used. Re-mark them, keeping both ends of the object on the rectangle."
            )
        if (value == 0.0) {
            return DwMeasureResult.Refusal(
                "Both ends land in the same place."
            )
        }

        val uncertainty = propagateUncertainty(coordinates, markSigmaPx, ::evaluate)
            ?: return DwMeasureResult.Refusal(
                "Can't estimate the error. Photograph less edge-on and mark again."
            )

        val referencePixels = distanceBetween(corners[0], corners[1])
        val targetPixels = distanceBetween(target.from, target.to)
        val uncorrectedValue =
            if (referencePixels > 0.0) (rectangle.width * targetPixels) / referencePixels else Double.NaN
        val tiltCorrection =
            if (finite(uncorrectedValue)) abs(value - uncorrectedValue) / value else Double.NaN

        val relativeUncertainty = uncertainty / value
        if (!finite(
                value, uncertainty, relativeUncertainty, referencePixels,
                targetPixels, uncorrectedValue, tiltCorrection,
            )
        ) {
            return DwMeasureResult.Refusal("These marks don't give a reliable measurement.")
        }

        return DwMeasureResult.Measurement(
            method = METHOD_RECTIFIED,
            value = value,
            unit = rectangle.unit,
            uncertainty = uncertainty,
            relativeUncertainty = relativeUncertainty,
            referencePixels = referencePixels,
            targetPixels = targetPixels,
            uncorrectedValue = uncorrectedValue,
            tiltCorrection = tiltCorrection,
        )
    }
}
