package com.designprototype.workshop.data

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turning a photograph of a pencil sketch into a printable plate: perspective correction, then
 * line-art extraction by local thresholding — the Kotlin half of `frontend/lib/sketchRectify.ts`.
 *
 * ── WHY THIS BELONGS ON THE HANDSET AT ALL, WHICH IS THE QUESTION THAT WAS ASKED ──────────────
 *
 * Stage 11's `sketch.image` is a REQUIRED IMAGE field, and the way a sketch enters this archive is
 * that somebody photographs a sheet of paper lying on a table, at whatever angle they were standing,
 * under whatever light the room has — which in a village workshop is a tube light on one wall. The
 * phone IS the camera. Every other client sees the photograph for the first time days later, at a
 * desk, with the sheet three districts away; this one sees it while the paper is still on the table
 * and a second attempt costs ten seconds. That asymmetry is the whole argument, and it is the same
 * one [DwImageQuality] and [DwPhotoMeasure] are built on.
 *
 * ── WHAT IS GENUINELY ADDITIONAL, GIVEN WHAT THE PHONE ALREADY HAS ────────────────────────────
 *
 * A handset can already crop, and a scanner application (Drive, Lens) can already rectify and
 * binarise. Neither replaces this, for three reasons that are about the ARCHIVE rather than about
 * the arithmetic:
 *
 *  1. Both edit or export through the gallery, and what comes back is a file the designer then has
 *     to attach by hand. Nothing then records that it was derived from the photograph in `image` —
 *     the pairing that makes a plate evidence rather than a picture.
 *  2. The obvious gesture in a photo editor is to correct the photograph IN PLACE.
 *     docs/MEDIA_PIPELINE.md §5 refuses exactly that: this is a heritage archive, the original file
 *     IS the artifact, and it may not be re-encoded. A route that makes it easy to overwrite the
 *     original is a route that will eventually be used that way.
 *  3. Neither is guaranteed present, and neither works with the phone in aeroplane mode two weeks
 *     into a study on a handset with 900 MB free. This is thirty kilobytes of arithmetic.
 *
 * ── THE ORIGINAL IS NEVER TOUCHED. NOTHING IN THIS FILE WRITES ANYTHING AT ALL. ───────────────
 *
 * This file has no `File`, no `Bitmap`, no I/O and no Android import. It reads a [GreyPlane] and
 * returns a new one. The surface above it ([DwSketchRectifyPanel]) writes the derived plate into a
 * DIFFERENT registry field — `sketch.lineArtFile` — precisely so the value in `sketch.image` still
 * points at the untouched photograph. A derived plate is a second artifact that cites the first; it
 * is never a corrected version of it.
 *
 * ── WHAT WAS DELIBERATELY NOT PORTED, AND WHY THAT IS THE HONEST LINE ─────────────────────────
 *
 * The web module carries an AUTOMATIC CORNER GUESS — Otsu, a morphological closing, a flood fill for
 * the largest bright component, its extremes along the two diagonals, and three separate confidence
 * gates. It is about a quarter of that file and by far its most delicate quarter. It is NOT here,
 * and the web module's own comment says why it can be left out without taking the feature with it:
 *
 *     "THE MANUAL PATH IS THE REAL FEATURE AND THIS IS A CONVENIENCE ON TOP OF IT. `SketchRectifyField`
 *      starts every sketch with four draggable handles inset from the frame edges, and it does that
 *      whether or not this function found anything."
 *
 * A guess that fires MOVES four handles the designer can drag anyway; when it declines — which it
 * does for anything that is not unmistakably a sheet — the panel is exactly what is built here. So
 * the guess buys four drags on the photographs where it works, at the price of five hundred lines
 * whose failure mode (a confidently wrong quadrilateral) the web module itself calls worse than
 * silence. If it is ever wanted, it goes in as a whole with its gates and its tests, or not at all;
 * a half-ported guess with the gates left off is the one version that must never exist.
 *
 * [DwSketchPlate] is the only file in this feature that knows what a Bitmap is, for the same reason
 * [DwImageDecode] is for image quality: everything here has to be testable on a desktop JVM, and
 * this module has no Robolectric.
 *
 * ── THE ROUNDING TRAP, AGAIN, AND IT IS NOT THE SAME ONE AS [DwPhotoMeasure]'S ────────────────
 *
 * There are TWO rounding rules in this file and they are different rules:
 *
 *  * Writing a sampled value into a plane goes through [DwImageQuality.clampToByte] — ECMAScript's
 *    `ToUint8Clamp`, ties to EVEN — because the web writes into a `Uint8ClampedArray` and that is
 *    what its assignment conversion performs. `roundToInt` here would disagree on every exact .5,
 *    which a bilinear resample produces constantly; [DwImageQuality]'s header records the 18-of-39
 *    divergence that mistake caused the last time it was made in this package.
 *  * Choosing a pixel COUNT — a plate's width, a window's side — goes through
 *    [DwPhotoMeasure.jsRound], JavaScript's `Math.round`, ties toward POSITIVE INFINITY, because
 *    that is the function the web calls there.
 *
 * There is no Python counterpart to this module, so [DwPy] does not apply: the authority is
 * `frontend/lib/sketchRectify.ts` and parity means web↔handset.
 */

/** A refusal carrying a sentence for the designer, never a code. Mirrors [DwMeasureResult.Refusal]. */
sealed interface DwPlateResult {

    /**
     * Why these four marks cannot be made into a plate.
     *
     * A sealed interface rather than a nullable field, for the reason [DwMeasureResult] gives: there
     * is then nothing for a caller to read by accident, and the compiler says so rather than a
     * runtime check.
     */
    data class Refusal(val reason: String) : DwPlateResult

    data class Plate(
        val plane: GreyPlane,
        /** Resampling and thresholding only — no decode, which the adapter times separately. */
        val elapsedMs: Long,
    ) : DwPlateResult
}

/** What [DwSketchRectify.makePlate] should produce. */
data class DwPlateOptions(
    /**
     * Force a known sheet proportion, width / height (A4 portrait is 210/297). Null keeps the
     * estimate the four marks imply — see [DwSketchRectify.rectifiedSize].
     */
    val aspect: Double? = null,
    val maxEdge: Int = DwSketchRectify.RECTIFY_MAX_EDGE_PX,
    /** False produces the rectified GREY plate with no thresholding — see [DwSketchRectify.makePlate]. */
    val lineArt: Boolean = true,
    /** Null uses [DwSketchRectify.sauvolaWindow] for the plate's own size. */
    val window: Int? = null,
    val k: Double = DwSketchRectify.SAUVOLA_K,
    val r: Double = DwSketchRectify.SAUVOLA_R,
)

object DwSketchRectify {

    /* ────────────────────────────────────────────────────────────────────────────
     * Constants, every one of them with the reason it is that number
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * The long edge the rectified plate is produced at.
     *
     * DERIVED FROM THE REPORT'S OWN GEOMETRY, not chosen for looking round.
     * [DwImageQuality.MIN_LONG_EDGE_PX] is 1280 and its comment records where that comes from: the
     * report is A4 with 25 mm margins, so a full-width plate is 160 mm ≈ 6.3 in, and at the dpi this
     * repository commits to that is well under 1280 — the threshold below which a photograph is
     * called too small to print. 1600 sits above that with room to spare, so a plate is never the
     * thing that trips the report's own resolution warning, while still bounding the work: 1600x1200
     * is 1.9M pixels and every pass below is linear in that.
     *
     * IT IS ALSO A CEILING, NEVER A TARGET. [rectifiedSize] scales DOWN to fit this and never scales
     * up — a sheet that occupied 900 px of the working copy is resampled to 900 px, because inventing
     * pixels a camera never recorded would make a plate that looks more detailed than the evidence
     * behind it, which is the one direction this archive must never move in.
     */
    const val RECTIFY_MAX_EDGE_PX = 1600

    /**
     * Sauvola's `k`. Higher pulls the threshold further below the local mean, so less survives as ink.
     *
     * 0.2 is the value from the original paper and the one every document-binarisation implementation
     * uses. It is left at the standard rather than tuned to the sketches in this repository's
     * fixtures, because tuning a constant to a handful of photographs produces a number that is worse
     * on the next photograph and impossible for anyone to re-derive.
     */
    const val SAUVOLA_K = 0.2

    /** Sauvola's `R`: the dynamic range the local standard deviation is normalised against. Half of 255. */
    const val SAUVOLA_R = 128.0

    /**
     * The local window's side length, as a fraction of the plate's SHORT edge.
     *
     * THE WINDOW MUST BE COMFORTABLY WIDER THAN A PENCIL STROKE, and that is the entire constraint.
     * The local mean is what a pixel is compared against; a window that fits INSIDE a stroke sees only
     * ink, so the mean is ink, so the stroke is not darker than its neighbourhood and the stroke is
     * ERASED. A pencil line on an A4 sheet resampled to 1200 px across is roughly 3-6 px wide, and
     * 1/24 of the short edge is 50 px at that size — an order of magnitude of headroom.
     *
     * The other end is bounded by the gradient this whole method exists to defeat: the window must be
     * SMALLER than the scale over which the tube light varies. A lamp gradient runs across the whole
     * sheet, so anything under a few percent of the page is safe. 1/24 sits between the two by a wide
     * margin in both directions, which is why it is a fraction of the image rather than a pixel count
     * — a pixel count would be correct at one output size only.
     */
    const val SAUVOLA_WINDOW_FRACTION = 1.0 / 24.0

    /** The smallest window that is ever used, for a plate small enough that the fraction underflows. */
    const val SAUVOLA_MIN_WINDOW = 15

    /**
     * What a sample taken outside the source photograph reads as: paper white.
     *
     * WHY WHITE AND NOT THE CLAMPED EDGE PIXEL. Both are wrong, and they are wrong in different ways.
     * A designer marks a corner beyond the frame when the sheet was photographed cut off. Clamping
     * replicates the last row of real photograph across the whole missing region, which produces a
     * smear that looks like content — a streak of table-coloured pixels the reader has no way to know
     * was invented. White reads as "there was nothing here", which is the truth: that part of the
     * sheet was not photographed. An honest absence beats a plausible fabrication, the same rule
     * `market_analysis.py` follows when it withholds a quantile it cannot support.
     */
    const val OUTSIDE_VALUE = 255.0

    /* ────────────────────────────────────────────────────────────────────────────
     * Corner handling
     * ──────────────────────────────────────────────────────────────────────────── */

    private fun finite(vararg values: Double): Boolean {
        for (value in values) if (!value.isFinite()) return false
        return true
    }

    /**
     * Put four marked points into TL, TR, BR, BL order, whatever order they were placed in.
     *
     * WHAT THIS PREVENTS is the bow-tie. [DwPhotoMeasure]'s `isSimpleConvexQuad` documents the failure
     * at length: four corners taken in reading order — top-left, top-right, BOTTOM-LEFT, bottom-right
     * — describe a crossed quadrilateral, and the homography from a crossed quadrilateral is a
     * perfectly valid projective map that folds the plane through itself. It solves, it produces
     * finite numbers, and it resamples the sheet inside out. There is no later stage at which anything
     * notices.
     *
     * [DwPhotoMeasure] CATCHES the bow-tie and refuses. This makes it unrepresentable instead, and the
     * difference is a deliberate response to what the two features ask of a designer. Measuring asks
     * for four corners of a rectangle of KNOWN SIZE, where which corner is which carries meaning the
     * caller has to respect, so a wrong order must be reported rather than silently reinterpreted.
     * Rectifying asks only "where is the sheet"; every ordering of the same four points describes the
     * same sheet, and refusing one of them would be refusing to answer a question that was asked
     * perfectly clearly.
     *
     * Sorting by angle about the centroid is what makes it unrepresentable: points taken in angular
     * order around an interior point cannot cross, for any four points that have an interior at all.
     * The rotation afterwards picks the corner nearest the origin as TL, so the output is not merely
     * non-crossing but consistently labelled — which is what lets the panel say "drag the top-left
     * handle" and mean it.
     *
     * Returns null only for input that is not four finite points; every genuine degeneracy (coincident
     * points, three in a line) is left to [DwPhotoMeasure.solveHomography], which already tests for all
     * of them and already has the sentences.
     */
    fun orderCorners(points: List<DwPoint>): List<DwPoint>? {
        if (points.size != 4) return null
        for (point in points) if (!finite(point.x, point.y)) return null

        val centreX = (points[0].x + points[1].x + points[2].x + points[3].x) / 4
        val centreY = (points[0].y + points[1].y + points[2].y + points[3].y) / 4

        // Ascending atan2 walks clockwise ON SCREEN, because image y grows downward. The visual
        // direction does not matter to the homography — what matters is that it is monotonic, which is
        // what makes the ring non-crossing. `sortedBy` is stable, as JavaScript's `Array#sort` is, so
        // two marks at the identical angle keep the order the designer placed them in on both clients.
        val ring = points.sortedBy { atan2(it.y - centreY, it.x - centreX) }

        // Rotate so the corner closest to the image origin leads. `x + y` rather than a true distance
        // because it is exact in integers and picks the same corner for every sheet that is not tilted
        // past 45 degrees — and a sheet tilted past 45 degrees has no "top left" that a designer would
        // agree on anyway, so any consistent choice is as good as another.
        var start = 0
        for (index in 1 until 4) {
            if (ring[index].x + ring[index].y < ring[start].x + ring[start].y) start = index
        }
        return listOf(ring[start % 4], ring[(start + 1) % 4], ring[(start + 2) % 4], ring[(start + 3) % 4])
    }

    /**
     * The pixel size of the rectified plate for a given marked quadrilateral, or null when the marks
     * do not enclose one.
     *
     * THE ASPECT RATIO THIS PRODUCES IS AN ESTIMATE, AND THAT IS NOT A DEFECT THAT CAN BE FIXED HERE.
     * Four image points of an unknown rectangle do not determine its true proportions: recovering
     * those needs the camera's focal length, which a photograph in this archive does not reliably
     * carry. So the estimate is the honest one — take each pair of opposite edges at the length they
     * appear, and use the longer of each pair — and the caller is given [DwPlateOptions.aspect] to
     * override it. The panel offers the A-series proportion, because a designer who knows the sheet
     * was A4 holds information this function cannot derive, and the whole reason the manual path
     * exists is that the person has knowledge the pixels do not.
     *
     * THE LONGER OF EACH PAIR, not the average, because the longer edge is the one that was nearer the
     * camera and therefore the better resolved; sizing to the average would throw away detail that was
     * actually recorded on that side of the sheet.
     */
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
        // Written as `!(x >= 1)` and not `x < 1` so a NaN edge — which compares false against
        // everything — refuses instead of falling through to a plate of NaN pixels.
        if (!(width >= 1.0) || !(height >= 1.0)) return null

        // An explicit aspect replaces the estimate but must not ENLARGE the plate, so the edge that
        // would have to grow is left alone and the other is brought to it.
        if (aspect != null) {
            if (!finite(aspect) || aspect <= 0.0) return null
            if (width / height > aspect) width = height * aspect else height = width / aspect
        }

        // Never upscale — see RECTIFY_MAX_EDGE_PX.
        val scale = min(1.0, maxEdge.toDouble() / max(width, height))
        return max(1L, DwPhotoMeasure.jsRound(width * scale).toLong()).toInt() to
            max(1L, DwPhotoMeasure.jsRound(height * scale).toLong()).toInt()
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * Resampling
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * Bilinear sample of [plane] at a real-valued coordinate, in the pixel-centre convention: integer
     * `(x, y)` names the centre of pixel `(x, y)`.
     *
     * BILINEAR AND NOT NEAREST-NEIGHBOUR, for a reason specific to what is being resampled. Nearest
     * neighbour on a rotated grid drops and repeats whole source pixels, and a pencil line is 3-6 px
     * wide: dropping every other sample along it turns a continuous stroke into a dashed one. That
     * damage then goes THROUGH the threshold below and comes out the other side as a dashed black line
     * in the plate — a change to what the sketch says, made by a resampling choice, invisible to
     * everyone downstream.
     *
     * Interpolation before thresholding, never after, for the same reason: the threshold is the step
     * that destroys the intermediate values interpolation needs.
     *
     * Returns a Double rather than a byte. The caller writes it through
     * [DwImageQuality.clampToByte], which is the web's `Uint8ClampedArray` assignment — see this
     * file's header for why `roundToInt` there is the trap.
     */
    fun bilinearSample(plane: GreyPlane, x: Double, y: Double, outside: Double = OUTSIDE_VALUE): Double {
        if (!finite(x, y)) return outside
        // Half a pixel of slack at each edge is the region still covered by a real pixel's own area;
        // beyond it there is genuinely no photograph, and OUTSIDE_VALUE explains why that reads as paper.
        if (x < -0.5 || y < -0.5 || x > plane.width - 0.5 || y > plane.height - 0.5) return outside

        val x0 = floor(x)
        val y0 = floor(y)
        val fx = x - x0
        val fy = y - y0
        // Clamped taps: inside the half-pixel border above, one of the four neighbours legitimately
        // lies outside the array, and the nearest real pixel is the correct value for it — this is the
        // boundary of a sample that IS covered, not the fabricated-content case OUTSIDE_VALUE guards.
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

    /**
     * The homography carrying the OUTPUT plate's corners onto the marked corners in the source
     * photograph, or null when the marks cannot determine one.
     *
     * THE DIRECTION IS THE WHOLE TRICK, and it is why this module needs no matrix inversion anywhere.
     * Resampling has to answer, for each pixel of the output, "which point of the input does this come
     * from" — so the transform wanted is output→input. Solving in that direction directly means the
     * eight unknowns come straight out of [DwPhotoMeasure.solveHomography] and are used as they are.
     *
     * The alternative — solve input→output the way a reader expects, then invert a 3x3 — is a second
     * hand-written linear-algebra routine, with its own degeneracies and its own tests, to arrive at
     * exactly these numbers.
     *
     * WHY NOT FORWARD-MAP THE INPUT INSTEAD. Pushing each source pixel to where it lands scatters
     * rather than gathers: wherever the map is locally expanding, output pixels are left that no source
     * pixel hit, and the plate comes out perforated with holes that then threshold to white speckle
     * through the strokes. Gathering visits every output pixel exactly once by construction.
     *
     * REUSED, NOT REWRITTEN. [DwPhotoMeasure.solveHomography] is already the hand-written 8x8 direct
     * linear transform with partial pivoting this needs — no matrix dependency, `h33` fixed at 1,
     * degeneracy refused on the raw marks — and DwPhotoMeasureTest already proves it recovers a known
     * transform including a fifth point that took no part in the fit. A second Gaussian elimination
     * here would be a second implementation to keep correct, on both clients.
     */
    fun rectifyHomography(corners: List<DwPoint>, width: Int, height: Int): DwHomography? {
        if (corners.size != 4) return null
        if (width < 2 || height < 2) return null
        // The output rectangle's own corners, in the same TL, TR, BR, BL order. `width - 1` because
        // these are pixel CENTRES: the last addressable centre of a 100 px-wide plate is at 99, and
        // using 100 here would resample the sheet a fraction small and shift the whole plate half a
        // pixel up and left.
        val destination = listOf(
            DwPoint(0.0, 0.0),
            DwPoint(width - 1.0, 0.0),
            DwPoint(width - 1.0, height - 1.0),
            DwPoint(0.0, height - 1.0),
        )
        return DwPhotoMeasure.solveHomography(destination, corners)
    }

    /**
     * Resample the quadrilateral [corners] of [plane] into an upright plate of the given size.
     *
     * Returns null when the marks cannot determine a homography — coincident corners, or three of them
     * in a line. That refusal comes from [DwPhotoMeasure.solveHomography] and is the designer's to fix
     * by moving a handle, which is why [makePlate] turns it into a sentence rather than a silent empty
     * plate.
     */
    fun rectifyPlane(plane: GreyPlane, corners: List<DwPoint>, width: Int, height: Int): GreyPlane? {
        val homography = rectifyHomography(corners, width, height) ?: return null
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val source = DwPhotoMeasure.applyHomography(homography, DwPoint(x.toDouble(), y.toDouble()))
                // clampToByte and NOT roundToInt — see this file's header for the 18-of-39 divergence
                // that mistake produced in [DwImageQuality] the last time it was made here.
                data[rowOffset + x] = DwImageQuality.clampToByte(bilinearSample(plane, source.x, source.y)).toByte()
            }
        }
        return GreyPlane(data, width, height)
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * Thresholding — turning grey paper into black line on white
     * ──────────────────────────────────────────────────────────────────────────── */

    /** Summed-area tables of a plane and of its squares, with the zero row and column already in. */
    class DwIntegralPlanes(val sum: DoubleArray, val squares: DoubleArray, val stride: Int)

    /**
     * Summed-area tables of the plane and of its squares, so any window's mean and variance are O(1).
     *
     * Both are `DoubleArray` and both are `(width + 1) * (height + 1)` with a zero row and column,
     * which removes every boundary branch from the inner loop below.
     *
     * THE SQUARES GENUINELY NEED 64 BITS. A 1600x1200 plate of pure white sums to 1.9M * 255² ≈ 1.2e11.
     * That is far past a 32-bit integer and past the point where a `Float` has unit precision — in
     * single precision the running total stops changing once the increment falls below its ULP, so the
     * bottom-right of the table would silently stop accumulating and every window in the lower half of
     * the image would get a wrong variance. A Double carries integers exactly to 2^53, which is seven
     * orders of magnitude of headroom over the worst case here.
     */
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

    /** The window side length used for a plate of this size — odd, so it has a centre pixel. */
    fun sauvolaWindow(plane: GreyPlane): Int {
        val shortEdge = min(plane.width, plane.height)
        val scaled = DwPhotoMeasure.jsRound(shortEdge * SAUVOLA_WINDOW_FRACTION).toInt()
        val bounded = max(SAUVOLA_MIN_WINDOW, scaled)
        return if (bounded % 2 == 1) bounded else bounded + 1
    }

    /**
     * Sauvola local thresholding: black where the pixel is darker than its own neighbourhood expects.
     *
     *     T(x, y) = m(x, y) · (1 + k · (s(x, y) / R − 1))
     *
     * where `m` and `s` are the mean and standard deviation of the window around the pixel.
     *
     * WHY A LOCAL METHOD AND NOT A GLOBAL ONE — THIS IS THE ENTIRE JUSTIFICATION FOR THE FILE
     *
     * The sketch is photographed under a tube light on one wall, so one side of the sheet is
     * materially brighter than the other. A global threshold is a single number for the whole page,
     * and there is no single number that works: it has to be high enough to catch a pencil stroke on
     * the BRIGHT side, where the stroke is still lighter than plain paper on the dark side — and any
     * threshold that high classifies the entire dark half of the blank page as ink. The plate comes
     * out with the sketch visible at one end and a solid black rectangle at the other. Lower the
     * threshold to fix that and the strokes on the bright side vanish instead. The two failures are
     * not a tuning problem; they are the same threshold being asked to mean two different things about
     * two differently-lit pieces of paper.
     *
     * A local threshold is not a refinement of that. It removes the premise: every pixel is compared
     * with the paper immediately around it, which is lit the way it is lit, so the lamp gradient
     * cancels instead of being fought. DwSketchRectifyTest pins exactly this and nothing else is the
     * point of that case: on a synthetic page with a lamp gradient and strokes of constant local
     * contrast, Otsu — the strongest global method, chosen so the comparison is not a strawman —
     * floods the dark half, while this function recovers the strokes at both ends and leaves the paper
     * clean.
     *
     * WHY SAUVOLA AND NOT PLAIN LOCAL-MEAN (NIBLACK). Niblack's `T = m + k·s` has no term that
     * recognises a window containing no ink at all. On blank paper `s` is just sensor noise, the
     * threshold sits a hair below the mean, and roughly half the noise falls below it: a clean margin
     * comes out as dense black speckle. Sauvola's `s/R` factor collapses toward `T ≈ m·(1 − k)`
     * exactly there, which is well below every pixel of an unmarked region, so blank paper stays
     * blank. On a sketch that is mostly blank paper by area — which every sketch is — that difference
     * is the difference between a plate and a page of dirt.
     *
     * The output is 0 for ink and 255 for paper: black line art on white, ready to print.
     */
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
            // The window is CLIPPED to the plane rather than the image being padded, and the pixel
            // count is the clipped one. Padding with a constant would put invented paper in every
            // window along the border, biasing the mean there toward the pad value and drawing a black
            // or white frame around every plate — on a rectified sheet the border is the edge of the
            // paper, which is exactly where a sketch's own frame lines tend to be.
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
                // Clamped at zero because the algebraic identity E[x²] − E[x]² is very slightly
                // negative for a constant window once rounding is involved, and the square root of
                // that is NaN — which would propagate into the threshold and paint an arbitrary patch.
                val variance = max(0.0, totalSquares / count - mean * mean)
                val threshold = mean * (1 + k * (sqrt(variance) / r - 1))
                val index = y * plane.width + x
                // 255 does not fit a signed Byte, so the paper value is written as the unsigned
                // pattern `at()` reads back as 255. Spelled out rather than left to a literal,
                // because `data[i] = 255` does not compile and `data[i] = -1` would not read as paper.
                data[index] = if (plane.at(index) < threshold) 0.toByte() else 255.toByte()
            }
        }
        return GreyPlane(data, plane.width, plane.height)
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * The whole operation, still pure
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * Rectify, then optionally extract line art. The one entry point the panel's preview calls.
     *
     * `lineArt = false` IS A FIRST-CLASS OUTCOME, not a debugging switch. A pencil sketch with soft
     * shading or a very faint construction line is a drawing whose information is in the greys, and
     * thresholding it to two levels throws that away; a designer who can see both previews is the only
     * one who can tell. So "straighten it but leave the tones alone" is a real answer, and so — one
     * level up, in the panel — is keeping the original photograph and producing nothing at all.
     */
    fun makePlate(plane: GreyPlane, corners: List<DwPoint>, options: DwPlateOptions = DwPlateOptions()): DwPlateResult {
        val startedAt = System.nanoTime()
        val size = rectifiedSize(corners, maxEdge = options.maxEdge, aspect = options.aspect)
            ?: return DwPlateResult.Refusal(
                "Those four marks do not enclose a sheet. Drag the handles to the corners of the paper."
            )
        val rectified = rectifyPlane(plane, corners, size.first, size.second)
            ?: return DwPlateResult.Refusal(
                "Those corners lie in a straight line, so there is no sheet between them. Move one of the handles."
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
