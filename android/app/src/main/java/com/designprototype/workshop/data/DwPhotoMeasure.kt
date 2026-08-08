package com.designprototype.workshop.data

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Measuring a real-world dimension off a photograph, from a reference object of known size —
 * the Kotlin half of `frontend/lib/photoMeasure.ts`.
 *
 * ── WHY THIS IS ARITHMETIC AND NOT A MODEL, AND WHY THAT IS THE POINT ─────────────────────────
 *
 * Stage 13's Advanced tier asks for calibrated measurements. Today every dimension in the registry
 * — `lengthCm`, `widthCm`, `heightCm`, `diameterCm` — is typed off a tape measure, and a mistyped
 * dimension does not stay put: it is multiplied into the cost sheet, printed on the product card,
 * and read by somebody costing a production run from a document nobody can re-measure. So the
 * feature has to be available at the moment the object is still in the designer's hands, which in
 * this application means a village with no connection for days. Everything below is plane projective
 * geometry over four to eight marked points — no network, no model, no image decoding, and no matrix
 * library. It is the same reasoning as [DwImageQuality]: a finding that arrives at a desk a fortnight
 * later, about an object three districts away, is true and completely useless.
 *
 * ── THE TWO METHODS, AND WHY BOTH HAVE TO EXIST ───────────────────────────────────────────────
 *
 *  1. [measureBySameScale] — the honest simple one. Mark the two ends of something whose length you
 *     know, mark the two ends of the thing you want, and the answer is a ratio of pixel distances.
 *     IT IS ONLY TRUE WHEN BOTH LIE IN THE SAME PLANE, PARALLEL TO THE SENSOR. That is not a
 *     footnote: a scale bar lying flat on a table and a pot standing on it are not in the same plane,
 *     and the pot's height read this way is wrong by however much the perspective happens to be —
 *     silently, plausibly, and by an amount nothing downstream can detect. The caller is required to
 *     say this on screen (the web's `PhotoMeasureField` renders it in an amber card beside the answer
 *     every time), because a designer who discovers it from a cost sheet discovers it too late.
 *
 *  2. [measureByRectification] — the four-point correction, for when 1 is not true. Mark the four
 *     corners of a rectangle whose real size you know (an A4 sheet, a scale card, a floor tile), and
 *     the homography that carries those four image points onto that rectangle carries EVERY point of
 *     that plane onto its true position. Measuring in the rectified plane is then exact, whatever the
 *     tilt, and [DwMeasureResult.Measurement.tiltCorrection] reports how much the tilt was worth —
 *     which is the number that tells a designer whether method 1 would have done.
 *
 * ── NOTHING HERE IS ALLOWED TO RETURN A NUMBER IT CANNOT STAND BEHIND ─────────────────────────
 *
 * Every entry point returns a [DwMeasureResult.Measurement] or a [DwMeasureResult.Refusal], and the
 * refusals are the larger half of the module on purpose — the same discipline as
 * `market_analysis.py` withholding quantiles below five observations. A measurement is refused when
 * the reference is too short to measure against, when the four corners are collinear or crossed, when
 * a perturbation of the marks lands on a degenerate configuration, and whenever any input is not
 * finite. A refusal carries a `reason` and NO `value` MEMBER AT ALL, which is why the two outcomes
 * are a sealed interface rather than one nullable-field class: there is then nothing for a caller to
 * read by accident, and the compiler says so rather than a runtime check.
 *
 * THIS IS ALSO WHY THE FLOOR IS A REFUSAL AND NOT A WARNING. The house rule is that nothing may block
 * fieldwork — but that rule is about RECORDING WHAT WAS SEEN, and this module records nothing. It
 * proposes a computed number, and the designer always has the tape measure and the keyboard. A
 * proposal whose error bar is wider than the answer is not a weaker measurement, it is a worse
 * outcome than none, because it is the confidence that travels.
 *
 * ── THE ROUNDING, WHICH IS THE TRAP OF THIS PORT ──────────────────────────────────────────────
 *
 * [roundToUncertainty] rounds through [jsRound] — JavaScript's `Math.round`, ties toward POSITIVE
 * INFINITY — and NOT through [DwPy.round], which is the reflex in this package and is wrong here.
 * [DwPy] exists because `market_analysis.py` and `cost_integrity.py` are Python and the SERVER is the
 * authority for those two files. There is no Python counterpart to this module: the proposal is
 * computed on whichever client the designer is holding, written into `lengthCm` as a plain number,
 * and the server only stores it. So the authority is `frontend/lib/photoMeasure.ts`, and parity here
 * means web↔handset.
 *
 * The two rules disagree on every exact binary tie, and the ties are ordinary rather than exotic: a
 * 100 mm reference marked 200 px long against a 401 px target is exactly 200.5 mm, quoted to the
 * units column beside a ±3.4 mm bar. `Math.round` proposes 201 and `DwPy.round` (half to EVEN)
 * proposes 200 — one number on the laptop at the desk and a different one on the phone in the
 * courtyard, for the same photograph and the same marks, in the field that is multiplied into a cost
 * sheet. That is exactly the divergence [DwPy] was written to prevent, arrived at by reaching for
 * [DwPy] in the one module where it does not apply. `String.format` is wrong twice over — it is
 * HALF_UP on the DECIMAL rendering rather than on the binary value, and it returns a string.
 *
 * For the same reason [DwPy.sum] is NOT used in [propagateUncertainty] or in the horizon test inside
 * [measureByRectification]. CPython 3.12's `sum()` is compensated; JavaScript's `+=` is a plain
 * running total, and it is `+=` that both of those loops are ports of. Compensating here would make
 * the handset's error bar differ from the browser's in the last place, which is the same defect with
 * the sign flipped.
 *
 * ── WHAT DIFFERS FROM THE TYPESCRIPT, AND WHY ─────────────────────────────────────────────────
 *
 *  * `Point` is [DwPoint]. `android.graphics.Point` exists, is an Int pair, and would compile at
 *    every call site in a Compose screen while quantising a mark to the nearest whole image pixel.
 *  * `LengthUnit` — a TypeScript string union — is a plain [String] checked against [LENGTH_UNITS],
 *    the same shape `DwCostCheck.verdict` uses. An enum would make the unknown-unit refusals
 *    unreachable, and those refusals are the point: `stageFieldRoles.measurableLengthFields` asks
 *    this very map whether a registry field's declared `unit` is a length before offering that field
 *    as a destination, so a unit this module cannot convert can never become a field it writes into.
 *    A future `unit="hands"` must be refused, not assumed.
 *  * `corners` is a `List<DwPoint>` where TypeScript has a four-tuple, so its length is checked at
 *    runtime and a wrong count is a refusal — never an `IndexOutOfBoundsException` in a courtyard.
 *
 * PURITY IS THE POINT, NOT AN AESTHETIC. No Android, no Compose, no I/O, no bitmap. That is what lets
 * every case in DwPhotoMeasureTest be a construction with a known answer — the same cases as
 * `e2e/photo-measure.spec.ts`, which is this module's specification — rather than a screenshot
 * comparison.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Shapes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A point in NATURAL IMAGE PIXELS — never in screen or CSS pixels. See [markSigmaForDisplayScale].
 *
 * [Double] and not [Float]: a mark on a 4000 px frame carries seven significant digits before the
 * point, and the rectified length is a difference of two such numbers put through an 8x8 solve.
 */
data class DwPoint(val x: Double, val y: Double)

/**
 * A 3x3 homography, row-major.
 *
 * NINE NAMED FIELDS rather than an array, for the reason the TypeScript gives for preferring a flat
 * tuple to a `number[][]`: this is passed around, compared and ported, and a mutable row invites an
 * aliasing bug the day somebody reuses one. A `DoubleArray` would reintroduce exactly that — it is
 * shared by reference and its contents can be written through any copy of the handle.
 */
data class DwHomography(
    val h11: Double, val h12: Double, val h13: Double,
    val h21: Double, val h22: Double, val h23: Double,
    val h31: Double, val h32: Double, val h33: Double,
) {
    /** Whether every entry is finite — a singular solve is caught here, before a millimetre figure. */
    val allFinite: Boolean
        get() = h11.isFinite() && h12.isFinite() && h13.isFinite() &&
            h21.isFinite() && h22.isFinite() && h23.isFinite() &&
            h31.isFinite() && h32.isFinite() && h33.isFinite()
}

/** The two ends of the thing being measured, as the designer marked them. */
data class DwSegment(val from: DwPoint, val to: DwPoint)

/**
 * The two ends of something whose length is known, and that length in [unit].
 *
 * [unit] must be a key of [LENGTH_UNITS]; anything else is refused by name rather than assumed.
 */
data class DwScaleReference(
    val from: DwPoint,
    val to: DwPoint,
    val length: Double,
    val unit: String,
)

/** A rectangle whose true size is known. `corners[0]→corners[1]` is the [width] edge. */
data class DwKnownRectangle(val width: Double, val height: Double, val unit: String)

/**
 * A measurement, or a refusal — and never something that is halfway between the two.
 *
 * The TypeScript is a discriminated union on `ok`, and its spec asserts that a refusal has no `value`
 * property at all. A sealed interface is that assertion made by the compiler: there is no cast, no
 * default and no null that gets a caller to a number this module declined to produce.
 */
sealed interface DwMeasureResult {

    /**
     * Why these marks cannot be measured.
     *
     * [reason] is a sentence for the designer, not a code. It says what is wrong with the MARKS,
     * because that is the only thing they can do anything about.
     */
    data class Refusal(val reason: String) : DwMeasureResult

    data class Measurement(
        /** [METHOD_SCALE] | [METHOD_RECTIFIED]. */
        val method: String,
        /** The measured length, in [unit]. */
        val value: Double,
        val unit: String,
        /**
         * One standard deviation, in the same unit. Zero ONLY when the caller declared a zero
         * per-mark sigma — every path that starts from a real mark gives it a positive width, which
         * is what [distanceSigma] is for.
         */
        val uncertainty: Double,
        /** `uncertainty / value`, precomputed because every caller wants to show a percentage. */
        val relativeUncertainty: Double,
        /** Pixel length of the reference the scale came from — the number the error bar hangs off. */
        val referencePixels: Double,
        /** Pixel length of the thing being measured, straight-line in the image. */
        val targetPixels: Double,
        /**
         * RECTIFIED only: what the SAME marks would have said under [measureBySameScale], using the
         * rectangle's first marked edge as the scale bar.
         *
         * This exists so the four-point method can justify itself. A designer who marks four corners
         * and is told "correcting for the tilt changed this by 0.2%" has learned that two marks would
         * have done; one told "by 8%" has learned why the extra two were worth it.
         */
        val uncorrectedValue: Double? = null,
        /** RECTIFIED only: `|value - uncorrectedValue| / value`. */
        val tiltCorrection: Double? = null,
    ) : DwMeasureResult
}

/** A value rounded to the precision its own error bar supports, and how many decimals that was. */
data class DwRoundedValue(val value: Double, val decimals: Int)

/* ────────────────────────────────────────────────────────────────────────────
 * The module
 * ──────────────────────────────────────────────────────────────────────────── */

object DwPhotoMeasure {

    /** A ratio of pixel distances, true only in one plane square to the sensor. */
    const val METHOD_SCALE = "SCALE"

    /** Four marked corners of a known rectangle, and the tilt divided out. */
    const val METHOD_RECTIFIED = "RECTIFIED"

    /**
     * Every length unit this module knows, and how many millimetres one of each is.
     *
     * Public because it is also the membership test elsewhere: the Android equivalent of
     * `stageFieldRoles.measurableLengthFields` asks this map whether a registry field's declared
     * `unit` is a length before offering that field as somewhere a measurement may be proposed. ONE
     * MAP, so a unit this module cannot convert can never become a destination it writes into.
     *
     * Millimetres is the base because it makes mm↔cm↔m exact in binary floating point.
     */
    val LENGTH_UNITS: Map<String, Double> = mapOf(
        "mm" to 1.0,
        "cm" to 10.0,
        "m" to 1000.0,
        "in" to 25.4,
    )

    /**
     * Below this pixel length a reference is refused outright.
     *
     * DERIVED, not chosen. The scale's relative uncertainty is `distanceSigma(σ) / referencePixels`,
     * so at the default [DEFAULT_MARK_SIGMA_PX] of 2 px a 40 px reference already carries 7% doubt
     * from the reference alone — a 12 cm dimension proposed as "12 cm ± 0.85 cm". That is the widest
     * bar this module is willing to put a number next to. Anything shorter is a scale bar
     * photographed from too far away, and the fix is one the designer can act on in the two seconds
     * they are still holding the object: step closer, or zoom in and re-place the marks.
     */
    const val MIN_REFERENCE_PIXELS = 40.0

    /**
     * How precisely a person places one mark, in SCREEN pixels, once they have zoomed in far enough
     * to see what they are aiming at.
     *
     * Not a measurement of anybody's hands — it is the smallest displacement that is visible on a
     * screen at all, and it is deliberately not smaller. Claiming sub-pixel marking accuracy would
     * narrow every error bar in the feature by pure assertion.
     */
    const val SCREEN_MARK_SIGMA_PX = 1.5

    /**
     * The fallback per-mark uncertainty in IMAGE pixels, used when a caller cannot say what zoom the
     * mark was placed at. Equivalent to placing a mark on a photograph displayed at 1:1.
     */
    const val DEFAULT_MARK_SIGMA_PX = 2.0

    /**
     * `|sin θ|` below which three marked points count as collinear.
     *
     * 0.02 is about 1.15°. Three corners of a rectangle that subtend less than that in the image are
     * not a rectangle any more — the sheet is edge-on — and the 8x8 system built from them is
     * singular or so close to it that Gaussian elimination returns numbers of magnitude 1e12 that
     * look perfectly finite all the way into a millimetre figure. The check is on the SINE rather
     * than on the raw cross product because a cross product scales with the size of the quad, so a
     * fixed threshold on it would mean something different for a sheet filling the frame and one in
     * the corner of it.
     */
    private const val COLLINEAR_SIN = 0.02

    /**
     * How far inside the horizon a target point has to stay, as a fraction of the rectangle's own
     * distance from it.
     *
     * A homography maps one line of the image — the vanishing line of the plane — to infinity. A
     * target mark placed near it maps to a colossal world coordinate, and the distance to it is a
     * large finite number with no meaning. Requiring the target's homogeneous denominator to keep the
     * sign of the rectangle's and at least this fraction of its magnitude is what keeps "I marked
     * something on the far wall" from becoming "this pot is 4,180 cm across".
     */
    private const val MIN_HORIZON_MARGIN = 0.05

    /**
     * √2, spelled as the web spells it.
     *
     * `Math.SQRT2` in JavaScript and `sqrt(2.0)` here are the same double —
     * 1.4142135623730951 — so [distanceSigma] agrees to the bit with the browser's.
     */
    private val SQRT2 = sqrt(2.0)

    /* ── Primitives ──────────────────────────────────────────────────────────────────────────── */

    private fun finite(vararg values: Double): Boolean {
        for (value in values) if (!value.isFinite()) return false
        return true
    }

    private fun pointsFinite(points: List<DwPoint>): Boolean {
        for (point in points) if (!finite(point.x, point.y)) return false
        return true
    }

    /**
     * `x ** 2` as the TypeScript writes it.
     *
     * Named rather than inlined so that nobody replaces it with `pow(x, 2.0)` on the grounds that it
     * reads closer to the source: `Math.pow` is specified only to within 1 ulp, while `x * x` is a
     * single correctly-rounded multiply and is what V8 emits for `** 2`. In a sum of three squares
     * under a square root, one ulp is the difference between two clients printing the same
     * percentage and printing two.
     */
    private fun sq(value: Double): Double = value * value

    /**
     * JavaScript's `Math.round`, in full: ties go toward POSITIVE INFINITY, so `-3.5` rounds to `-3`.
     *
     * This is the whole rounding rule of this module — see the file header for why [DwPy.round] is
     * NOT the right call here despite being the reflex in this package.
     *
     * Written as `floor(x) + (x - floor(x) >= 0.5 ? 1 : 0)` and NOT as [DwImageQuality]'s
     * `floor(x + 0.5)`, which is the same function for every value that file feeds it (a sharpness
     * score, already positive and far from a boundary) and is not the same function in general:
     * `floor(0.49999999999999994 + 0.5)` is 1 because the addition itself rounds up, where
     * `Math.round(0.49999999999999994)` is 0 — a case ECMA-262 calls out by name. This one takes an
     * arbitrary `value * 10^decimals`, so the general rule is the one it needs.
     *
     * The negative zero is carried deliberately: `Math.round(-0.4)` is `-0` in JavaScript, and
     * `-0 / 10` is `-0`, so a value rounded away to nothing keeps the sign the designer's marks gave
     * it rather than silently becoming a positive zero on one client only.
     */
    private fun jsRound(value: Double): Double {
        if (!value.isFinite()) return value
        val floored = floor(value)
        val rounded = if (value - floored >= 0.5) floored + 1.0 else floored
        if (rounded == 0.0 && (value < 0.0 || 1.0 / value < 0.0)) return -0.0
        return rounded
    }

    /**
     * The straight-line distance between two marks, in whatever units they are in.
     *
     * `hypot` and not `sqrt(dx*dx + dy*dy)`: the intermediate squares of two 4000 px coordinates are
     * nowhere near overflowing, but `hypot` is also the function the web calls, and matching the
     * call is the cheapest way to keep the last digit the same on both clients.
     */
    fun distanceBetween(a: DwPoint, b: DwPoint): Double = hypot(b.x - a.x, b.y - a.y)

    /**
     * The uncertainty of a DISTANCE between two independently placed marks, given the uncertainty of
     * one mark.
     *
     * √2, and the factor is worth stating because the obvious answer is 1. Both ends are marked
     * separately, so both contribute; to first order only the component along the line between them
     * moves the length, giving `σ_d = √(σ² + σ²) = √2 σ`. A module that used σ directly would report
     * every error bar in the feature about 30% narrower than it is, which is exactly the flattering
     * direction to be wrong in.
     */
    fun distanceSigma(markSigmaPx: Double): Double = SQRT2 * markSigmaPx

    /**
     * The per-mark uncertainty in IMAGE pixels, for a mark placed while the photograph was displayed
     * at [displayScale] (screen pixels per image pixel).
     *
     * THIS IS WHY ZOOMING IN GENUINELY MAKES THE MEASUREMENT BETTER, and why the error bar on screen
     * narrows as the designer pinches in. A 4000 px photograph shown 400 px wide is displayed at 0.1,
     * so one screen pixel IS ten image pixels and a mark placed at that zoom is worth ±15 image px
     * however carefully it was aimed. At 4:1 the same care is worth ±0.375 px. Marks are therefore
     * stored in image pixels — invariant under zoom — while their UNCERTAINTY is recorded from the
     * zoom they were placed at, and a measurement takes the worst of the marks it used.
     *
     * A non-positive or non-finite scale returns the 1:1 fallback rather than dividing by it. Never a
     * NaN sigma, which would silently erase the whole error bar rather than widen it.
     */
    fun markSigmaForDisplayScale(
        displayScale: Double,
        screenSigmaPx: Double = SCREEN_MARK_SIGMA_PX,
    ): Double {
        if (!finite(displayScale, screenSigmaPx) || displayScale <= 0.0 || screenSigmaPx < 0.0) {
            return DEFAULT_MARK_SIGMA_PX
        }
        return screenSigmaPx / displayScale
    }

    /**
     * Convert between length units, or null when either unit is one this module does not know.
     *
     * NULL RATHER THAN A GUESS. The target of a proposal is a registry field whose `unit` is declared
     * in `stage_definitions.py`; if a future field declares `unit="hands"` the honest response is to
     * not offer that field as a destination, not to write a centimetre figure into it.
     */
    fun convertLength(value: Double, from: String, to: String): Double? {
        val fromFactor = LENGTH_UNITS[from] ?: return null
        val toFactor = LENGTH_UNITS[to] ?: return null
        if (!finite(value)) return null
        // Same unit in and out returns the value UNTOUCHED rather than multiplying and dividing by
        // the same factor, which is not the identity for every double.
        if (from == to) return value
        return (value * fromFactor) / toFactor
    }

    /**
     * Round a measurement to the precision its own error bar can support, and report how many decimal
     * places that was.
     *
     * WHY A MEASUREMENT MAY NOT BE PROPOSED AT FULL PRECISION. `19.98471 cm ± 0.3 cm` is two claims,
     * and the first one contradicts the second. Once that number is written into `lengthCm` the error
     * bar is gone — the registry has one column for the dimension and none for the doubt — so the
     * ONLY thing left carrying the honesty is how many digits were written. A designer reading `20.0`
     * a season later knows roughly what they were told; one reading `19.98471` has been handed a
     * precision nobody measured, in the field that is multiplied into a cost sheet.
     *
     * The rule is the ordinary one from physical measurement: round the uncertainty to one
     * significant figure, and quote the value to that same decimal place. Capped at four decimals,
     * because past that the arithmetic is describing floating-point noise rather than an object.
     *
     * `log10` is safe to share between the two languages at the one place it could bite: both Java
     * and ECMA-262 require an exact integer result for an exact power of ten, which is where a
     * `floor` of an almost-integer would otherwise land on either side.
     */
    fun roundToUncertainty(value: Double, uncertainty: Double): DwRoundedValue {
        if (!finite(value, uncertainty) || uncertainty <= 0.0) {
            return DwRoundedValue(value, if (finite(value)) 2 else 0)
        }
        // The decimal place of the uncertainty's leading digit: 3.35 → 0, 0.335 → 1, 0.0335 → 2.
        val place = floor(log10(uncertainty))
        val decimals = min(4.0, max(0.0, -place)).toInt()
        val factor = 10.0.pow(decimals)
        val rounded = jsRound(value * factor) / factor
        return DwRoundedValue(if (finite(rounded)) rounded else value, decimals)
    }

    /* ── Linear algebra — written out rather than imported ───────────────────────────────────── */

    /**
     * Solve `A x = b` by Gaussian elimination with partial pivoting. Returns null for a singular
     * system.
     *
     * WRITTEN OUT, AND THAT IS THE REQUIREMENT RATHER THAN A PREFERENCE. Adding a matrix package for
     * eight equations puts a dependency on the one code path that has to run identically in a
     * browser, in Node and inside an Android app with no npm anywhere near it. Eight equations is
     * thirty lines.
     *
     * PARTIAL PIVOTING IS NOT OPTIONAL HERE. Without a row swap the very first elimination step of a
     * perfectly ordinary correspondence set can divide by a zero (a corner at x = 0), and every
     * subsequent entry is NaN — which travels all the way to a blank measurement with no reason
     * attached.
     *
     * NULL AND NOT AN EXCEPTION: a singular system is a normal thing for a designer to produce by
     * marking three points along an edge, and the caller has a sentence to say about it.
     */
    fun solveLinearSystem(matrix: List<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        if (matrix.size != n) return null
        // Copy: the caller's arrays are theirs, and elimination is destructive. This is the one place
        // a DoubleArray's sharing-by-reference would otherwise reach back into a caller's data.
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
            // A pivot that is exactly zero after choosing the largest available one means the column
            // is entirely zero below the diagonal: the system has no unique solution. Testing for
            // exact zero rather than a tolerance is deliberate — a tolerance here would be a second,
            // hidden degeneracy threshold competing with COLLINEAR_SIN, which is the one the caller
            // can explain to a designer.
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
        // A system that was singular only to within rounding produces ±Infinity or NaN here rather
        // than at the pivot test. Catching it now is what keeps a non-finite number out of a
        // millimetre figure.
        if (!x.all { it.isFinite() }) return null
        return x
    }

    /** Map a point through a homography, dividing by the third row. */
    fun applyHomography(h: DwHomography, point: DwPoint): DwPoint {
        val w = h.h31 * point.x + h.h32 * point.y + h.h33
        return DwPoint(
            x = (h.h11 * point.x + h.h12 * point.y + h.h13) / w,
            y = (h.h21 * point.x + h.h22 * point.y + h.h23) / w,
        )
    }

    /**
     * The homogeneous denominator alone — the sign and magnitude of a point's distance from the
     * horizon.
     */
    private fun homographyDenominator(h: DwHomography, point: DwPoint): Double =
        h.h31 * point.x + h.h32 * point.y + h.h33

    /*
     * A REFUSAL TO ADD HARTLEY NORMALISATION, carried across from the web module because the textbook
     * says to add it and the next reader of either file will wonder why it is not there.
     *
     * The standard advice for the direct linear transform is to translate each point set's centroid
     * to the origin and scale it so the mean distance from there is √2, then undo the transforms
     * afterwards. That advice is about the OVER-DETERMINED case: many correspondences, a 2n×9 matrix,
     * and the answer taken as the smallest singular vector, where mixing entries of magnitude 1 with
     * entries of magnitude x·u (1.6e7 on a 4000 px photograph) genuinely destroys the nullspace.
     *
     * This module has four correspondences and eight unknowns — an EXACT solve with partial pivoting,
     * not a fit — and pivoting already handles the dynamic range. The web implemented it both ways
     * and measured, recovering a fifth point that took no part in the fit:
     *
     *     A4 sheet imaged across    normalised        raw
     *       500 px .............   7.1e-14 mm      9.0e-14 mm
     *      4000 px .............   6.4e-14 mm      4.3e-14 mm
     *     40000 px .............   0.0e+00 mm      2.8e-14 mm
     *
     * Both sit on machine epsilon for a 297 mm coordinate, and the raw solve is marginally better as
     * often as it is worse. The normalisation was therefore deleted rather than kept as insurance,
     * and re-adding it HERE would be worse than useless: it would be a difference between the two
     * clients that no test could see and that would surface as a last-digit disagreement.
     *
     * The property that MATTERS is pinned instead, in DwPhotoMeasureTest: a length rectified off a
     * 4000x3000 frame is exact to a nanometre on an A4 sheet, however that is achieved.
     */

    /** |sin θ| at `b`, between `b→a` and `b→c`. Zero when the three are collinear. */
    private fun sineAt(a: DwPoint, b: DwPoint, c: DwPoint): Double {
        val ax = a.x - b.x
        val ay = a.y - b.y
        val cx = c.x - b.x
        val cy = c.y - b.y
        val lengths = hypot(ax, ay) * hypot(cx, cy)
        if (lengths == 0.0) return 0.0
        return abs(ax * cy - ay * cx) / lengths
    }

    /**
     * Whether four points, taken in the order given, walk around a simple convex quadrilateral.
     *
     * WHAT THIS CATCHES is a designer marking the corners of a sheet as top-left, top-right,
     * BOTTOM-LEFT, bottom-right — the natural reading order, and a crossed quadrilateral. The
     * homography from a bow-tie is a perfectly valid projective map; it solves, it produces finite
     * numbers, and it measures the object on a plane that has been folded through itself. The
     * resulting millimetre figure is wrong by an arbitrary amount and looks exactly like a right one,
     * so there is no later stage at which anything could notice. It is caught here or it is not
     * caught.
     */
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

    /**
     * The homography carrying `from[i]` onto `to[i]` for four correspondences, or null when the
     * configuration cannot determine one.
     *
     * The system is the standard direct linear transform with `h33` fixed at 1: each correspondence
     * `(x, y) → (u, v)` contributes
     *
     *     h11·x + h12·y + h13 − h31·x·u − h32·y·u = u
     *     h21·x + h22·y + h23 − h31·x·v − h32·y·v = v
     *
     * which is eight equations in eight unknowns for four points — an exact solve, not a fit, so
     * there is no residual to report and no least squares to run.
     *
     * FIXING h33 = 1 IS SAFE HERE AND IS NOT SAFE IN GENERAL: it excludes homographies that send the
     * origin of the source plane to infinity. Both call sites map marked image points onto a
     * rectangle the designer can see all four corners of, so the origin is a corner of a sheet in
     * front of the camera. The alternative — a nine-unknown nullspace solve — needs an SVD, which is
     * a great deal more code to write out by hand and would still have to be checked against the same
     * test cases.
     */
    fun solveHomography(from: List<DwPoint>, to: List<DwPoint>): DwHomography? {
        if (from.size != 4 || to.size != 4) return null
        if (!pointsFinite(from) || !pointsFinite(to)) return null

        // Degeneracy is tested on the RAW points, so the reason a solve is refused is a property of
        // what the designer marked rather than of an intermediate transform.
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

    /* ── Uncertainty ─────────────────────────────────────────────────────────────────────────── */

    /**
     * First-order propagation of a single per-coordinate uncertainty through an arbitrary scalar
     * function, by central differences.
     *
     * A NUMERICAL JACOBIAN RATHER THAN AN ANALYTIC ONE, DELIBERATELY. The rectified length is the
     * distance between two points mapped through a homography that was itself solved from eight other
     * coordinates; its derivative with respect to any one of those twelve inputs is a page of algebra
     * whose only reviewer would be the person who wrote it. Two extra solves per coordinate is a few
     * microseconds — even on the handset this is twenty-four 8x8 eliminations, not twenty-four
     * thousand — and the correctness is checkable: DwPhotoMeasureTest runs this propagator against a
     * case whose analytic answer (√2·σ) is known.
     *
     * THE STEP IS σ ITSELF. For a response that is linear over the neighbourhood — which is what a
     * first-order propagation assumes anyway — `(f(c+σ) − f(c−σ)) / 2` IS the contribution, with no
     * separate step size to choose and no division by a small number.
     *
     * NULL WHEN ANY PERTURBATION FAILS. An error bar assembled from eleven of its twelve terms is
     * narrower than the truth, and narrower is the direction that gets believed.
     *
     * The accumulation is a plain `+=` and NOT [DwPy.sum]: the source of truth for this file is
     * JavaScript, whose `+=` is an uncompensated running total. Compensating it here would be the
     * handset quietly computing a different error bar from the browser.
     */
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

    /* ── Method 1 — same plane, two marks ────────────────────────────────────────────────────── */

    /**
     * Measure by the ratio of two pixel distances.
     *
     * [markSigmaPx] is the per-mark uncertainty in IMAGE pixels — the WORST of the four marks, since
     * a measurement is only as well aimed as its sloppiest end. See [markSigmaForDisplayScale].
     *
     * [referenceLengthSigma] is one standard deviation on the reference's own stated length, in its
     * own unit. Zero by default, which is the right answer for a printed scale card or a steel rule
     * and the wrong one for "that brick is about 23 cm". A caller that lets a designer nominate an
     * approximate reference must pass this, or the error bar will be missing its largest term.
     */
    fun measureBySameScale(
        reference: DwScaleReference,
        target: DwSegment,
        markSigmaPx: Double = DEFAULT_MARK_SIGMA_PX,
        referenceLengthSigma: Double = 0.0,
    ): DwMeasureResult {
        if (!pointsFinite(listOf(reference.from, reference.to, target.from, target.to))) {
            return DwMeasureResult.Refusal("One of the marks has no position. Place all four marks again.")
        }
        if (!finite(reference.length, markSigmaPx, referenceLengthSigma) ||
            markSigmaPx < 0.0 || referenceLengthSigma < 0.0
        ) {
            return DwMeasureResult.Refusal("The reference length is not a number this can measure against.")
        }
        if (LENGTH_UNITS[reference.unit] == null) {
            return DwMeasureResult.Refusal("“${reference.unit}” is not a length unit this can convert.")
        }
        if (reference.length <= 0.0) {
            return DwMeasureResult.Refusal("The reference must have a real length greater than zero.")
        }

        val referencePixels = distanceBetween(reference.from, reference.to)
        val targetPixels = distanceBetween(target.from, target.to)
        if (referencePixels < MIN_REFERENCE_PIXELS) {
            return DwMeasureResult.Refusal(
                "The reference is only ${jsRound(referencePixels).toLong()} pixels long in this photograph, " +
                    "which is too short to measure against — the error bar would be wider than the answer. " +
                    "Zoom in and place the two marks further apart, or photograph the object closer to the scale."
            )
        }
        if (targetPixels == 0.0) {
            return DwMeasureResult.Refusal(
                "The two marks on the object are in the same place, so there is nothing to measure."
            )
        }

        val value = (reference.length * targetPixels) / referencePixels
        val sigmaDistance = distanceSigma(markSigmaPx)
        // A ratio's relative variance is the sum of its terms' relative variances; the reference's own
        // stated length is a third independent term when the caller admits it is not exact.
        val relative = sqrt(
            sq(sigmaDistance / referencePixels) +
                sq(sigmaDistance / targetPixels) +
                sq(referenceLengthSigma / reference.length)
        )
        val uncertainty = relative * value
        if (!finite(value, relative, uncertainty)) {
            return DwMeasureResult.Refusal("These marks do not produce a measurement that can be stood behind.")
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

    /* ── Method 2 — four corners of a known rectangle, rectified ─────────────────────────────── */

    /**
     * Measure by rectifying the plane a known rectangle lies in.
     *
     * [corners] are the four marked corners of that rectangle, IN ORDER AROUND IT — either direction,
     * but around. Reading order (top-left, top-right, bottom-left, bottom-right) is a crossed
     * quadrilateral and is refused; see [isSimpleConvexQuad].
     *
     * [markSigmaPx] is the per-mark uncertainty in IMAGE pixels — the worst of the six marks.
     */
    fun measureByRectification(
        corners: List<DwPoint>,
        rectangle: DwKnownRectangle,
        target: DwSegment,
        markSigmaPx: Double = DEFAULT_MARK_SIGMA_PX,
    ): DwMeasureResult {
        // TypeScript's four-tuple is a compile-time guarantee that a List is not. A wrong count is a
        // refusal and never an IndexOutOfBoundsException: this runs on a handset in a courtyard, and
        // a crash here loses whatever else the designer was in the middle of recording.
        if (corners.size != 4) {
            return DwMeasureResult.Refusal(
                "The rectangle needs four marked corners and there ${if (corners.size == 1) "is" else "are"} " +
                    "${corners.size}. Mark each corner once, in order around the sheet."
            )
        }
        if (!pointsFinite(corners) || !pointsFinite(listOf(target.from, target.to))) {
            return DwMeasureResult.Refusal(
                "One of the marks has no position. Place the corners and the two ends again."
            )
        }
        if (!finite(rectangle.width, rectangle.height, markSigmaPx) || markSigmaPx < 0.0) {
            return DwMeasureResult.Refusal(
                "The rectangle's size is not a pair of numbers this can measure against."
            )
        }
        if (LENGTH_UNITS[rectangle.unit] == null) {
            return DwMeasureResult.Refusal("“${rectangle.unit}” is not a length unit this can convert.")
        }
        if (rectangle.width <= 0.0 || rectangle.height <= 0.0) {
            return DwMeasureResult.Refusal("The rectangle must have a real width and height greater than zero.")
        }
        if (distanceBetween(target.from, target.to) == 0.0) {
            return DwMeasureResult.Refusal(
                "The two marks on the object are in the same place, so there is nothing to measure."
            )
        }
        if (!isSimpleConvexQuad(corners)) {
            return DwMeasureResult.Refusal(
                "Those four corners cross over one another, so they do not enclose the rectangle. Mark them in " +
                    "order around it — each corner next to the one before it — rather than in reading order."
            )
        }

        /** The rectangle in its own units, matching the corner order the caller marked. */
        val world = listOf(
            DwPoint(0.0, 0.0),
            DwPoint(rectangle.width, 0.0),
            DwPoint(rectangle.width, rectangle.height),
            DwPoint(0.0, rectangle.height),
        )

        /*
         * The whole measurement as a function of its twelve marked coordinates, so the value and its
         * error bar are computed by ONE piece of code. Two implementations of the same arithmetic —
         * one for the answer, one for the propagation — is how an error bar comes to describe a
         * different quantity from the number it is printed beside.
         *
         * A local fun rather than a lambda so that every failure arm is a plain `return null`, which
         * is what the TypeScript reads as. A labelled `return@` on twelve of them is the kind of
         * thing that gets one arm wrong in review and falls through to a measurement.
         */
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

            // The rectangle's own distance from the vanishing line, as the scale the target is judged
            // against. All four corners share a sign for any homography valid on the quad.
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
                "Those four corners do not define a plane this can rectify — three of them are in a straight " +
                    "line, or an end of the object falls where the surface runs out of view. Re-mark the corners " +
                    "of the rectangle, and keep both ends of the object on it."
            )
        if (value == 0.0) {
            return DwMeasureResult.Refusal(
                "Both ends of the object rectify to the same place, so there is nothing to measure."
            )
        }

        val uncertainty = propagateUncertainty(coordinates, markSigmaPx, ::evaluate)
            // The marks sit close enough to a degenerate arrangement that nudging one of them by the
            // marking error alone breaks the solve. The measurement may well be finite; it is simply
            // not one this module can put an error bar next to, and it does not offer numbers without
            // one.
            ?: return DwMeasureResult.Refusal(
                "These marks are too close to an arrangement this cannot measure for an error bar to be worked " +
                    "out, and a measurement is not offered without one. Photograph the rectangle less edge-on " +
                    "and mark it again."
            )

        val referencePixels = distanceBetween(corners[0], corners[1])
        val targetPixels = distanceBetween(target.from, target.to)
        // What two marks would have said: the first marked edge used as a plain scale bar. See
        // DwMeasureResult.Measurement.uncorrectedValue for why this is reported rather than merely
        // computed.
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
            return DwMeasureResult.Refusal("These marks do not produce a measurement that can be stood behind.")
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
