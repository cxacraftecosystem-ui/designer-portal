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

/**
 * "Where is the sheet?" — the automatic corner guess, ported from `frontend/lib/sketchRectify.ts`.
 *
 * ── WHAT THIS IS, AND WHAT IT IS EMPHATICALLY NOT ─────────────────────────────────────────────
 *
 * [DwSketchRectifyPanel] puts four draggable handles on a photograph, seeded as an inset rectangle,
 * and the designer drags them onto the corners of the paper. That manual path is the feature; it is
 * complete on the handset, it includes the nudge arrows that are the only route to a corner for
 * somebody who cannot aim a fingertip, and NOTHING IN THIS FILE CHANGES IT. What this adds is one
 * button that says "I think the sheet is HERE" and draws a shape. The handles do not move until a
 * person presses a second button. See [dwGuessSheetCorners] for why that second press exists on this
 * client and not on the web one.
 *
 * ── WHY IT IS HERE NOW WHEN [DwSketchRectify]'s HEADER ARGUED FOR LEAVING IT OUT ──────────────
 *
 * That header set the condition rather than closing the door: *"If it is ever wanted, it goes in as
 * a whole with its gates and its tests, or not at all; a half-ported guess with the gates left off
 * is the one version that must never exist."* This is the whole thing — Otsu, the morphological
 * closing, the flood fill, the diagonal extremes, and ALL THREE confidence gates plus the contrast
 * refusal that precedes them — with the web spec's four cases carried over as
 * `DwSketchRectifyGuessTest`. The gates are the reason the port is worth having; a version with them
 * loosened "because it declines too often" would be the version the original comment forbids.
 *
 * ── WHY THIS FILE SITS IN `ui.designworkshop` AND NOT BESIDE [DwSketchRectify] IN `data` ──────
 *
 * It is pure arithmetic — no Compose, no Android, no I/O, exactly like the module it is a port of —
 * so by this package's usual habit it would live in `data`. It is here because the panel is its only
 * caller and the two were written together, and it is written so that moving it costs an import
 * line: nothing below refers to a composable, a `Bitmap` or a resource. If a second caller ever
 * appears, move it.
 *
 * ONE PIECE OF IT HAS ALREADY GONE, on exactly that rule. [dwOtsuThreshold] acquired a second caller
 * — `DwSketchRectifyTest` uses it as the counterexample Sauvola is measured against — so it now lives
 * in `data/DwSketchRectify.kt` beside [DwSketchRectify.sauvolaThreshold], the other thresholding
 * decision in this feature, and is imported above. The rest of this file still has the one caller.
 *
 * ── THE TWO ROUNDING RULES, WHICH ARE [DwSketchRectify]'s TWO RULES ───────────────────────────
 *
 * Unchanged from that file, because the same divergence is available here: a pixel COUNT (the guess
 * plane's width, the closing radius, a sample position) goes through [DwPhotoMeasure.jsRound],
 * JavaScript's `Math.round` with ties toward positive infinity; a value WRITTEN INTO A PLANE goes
 * through [DwImageQuality.clampToByte], ECMAScript's `ToUint8Clamp` with ties to even. Nothing in
 * this file writes into a plane — [DwImageQuality.resampleGrey] is the only step that does, and it
 * already applies the right rule — so every rounding below is `jsRound`.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Constants, every one of them with the reason it is that number
 * ──────────────────────────────────────────────────────────────────────────── */

/** What the search found, in the coordinates of the plane it was handed. */
internal data class DwCornerGuess(
    /**
     * TL, TR, BR, BL, in the coordinates of the plane passed to [dwGuessSheetCorners] — NOT of the
     * reduced plane the search actually ran on. On this client that plane is the decoded WORKING
     * COPY, which is the same grid the panel stores its handles in, so these are directly usable.
     */
    val corners: List<DwPoint>,
    /** How much of the guessed quadrilateral was actually sheet, 0..1. See [DW_GUESS_MIN_FILL]. */
    val fill: Double,
    /** How much of the frame the guessed sheet occupies, 0..1. See [DW_GUESS_MIN_FRAME_SHARE]. */
    val frameShare: Double,
    /** The weakest of the four edges, 0..1. See [DW_GUESS_MIN_EDGE_SUPPORT]. */
    val edgeSupport: Double,
)

/**
 * The long edge the guess runs at.
 *
 * Small on purpose, and the reason is ROBUSTNESS RATHER THAN SPEED. The guess looks for the sheet as
 * the largest bright region; at full resolution the paper's own texture, the pencil strokes on it and
 * the JPEG noise all fragment that region into pieces, and the largest connected component becomes an
 * arbitrary patch. Averaging down to 480px is a low-pass filter that removes exactly that structure
 * and leaves the shape of the sheet.
 *
 * THE PRECISION COST IS REAL AND IS ACCEPTED because of what the guess is FOR: at 480px a corner is
 * located to about ±1px, which is ±4px on a 1920px original. That is visibly off, and it does not
 * matter, because the guess is only ever a proposed starting position for four handles the designer
 * drags — see [dwGuessSheetCorners].
 *
 * IT IS THE SAME 480 THE WEB USES even though the plane reaching it here is a different size, and
 * **not reliably a smaller one** — an earlier version of this paragraph claimed it was, and that was
 * wrong. The browser hands `guessSheetCorners` a plane at `RECTIFY_MAX_EDGE_PX` (1600). The handset
 * hands it [com.designprototype.workshop.data.DwImageDecode]'s working copy, which is the largest
 * power-of-two subsample at or under `DISPLAY_EDGE_PX` (2400) — so a 4000 px photograph is marked on
 * 2000 px here against the browser's 1600, a 2000 px one is marked at its own size, and only the
 * range that subsamples past 1600 (a 3000 px frame lands on 1500) is smaller. The two also differ in
 * filter and in depth, so identical numbers would still not give identical planes.
 *
 * None of that changes this constant. Both planes are far larger than 480 for every photograph either
 * client is given, so both downscale TO it, and the guess's own answers stay comparable — which is
 * the only property this number has to hold.
 */
internal const val DW_GUESS_EDGE_PX = 480

/**
 * The fraction of the guessed quadrilateral that must actually be sheet before the guess is offered.
 *
 * A true sheet fills its own corner quadrilateral almost completely — the shape IS a quadrilateral, so
 * the only shortfall is the pixels the threshold missed. A guess that has latched onto something else
 * (two bright objects bridged by a highlight, a window in the background, the designer's sleeve) makes
 * a quadrilateral whose interior is mostly not the bright region, and this ratio collapses. 0.85 is
 * strict enough to reject those and loose enough to survive a shadowed corner.
 *
 * BELOW THIS THE GUESS IS NOT SHOWN AT ALL — not greyed, not shown with a caveat. A wrong
 * quadrilateral presented as a suggestion is worse than no suggestion, because dragging four handles
 * back off a confident wrong answer is more work than placing them from nothing, and some designers
 * will not notice it is wrong.
 */
internal const val DW_GUESS_MIN_FILL = 0.85

/**
 * The fraction of the FRAME the guessed sheet must occupy before the guess is offered.
 *
 * Somebody photographing a sketch fills the frame with it. A "sheet" occupying 6% of the picture is a
 * bright object on a table, not the subject, and rectifying to it would crop the sketch away entirely.
 */
internal const val DW_GUESS_MIN_FRAME_SHARE = 0.15

/**
 * How much of each edge of the guessed quadrilateral must run along actual sheet.
 *
 * WHAT THIS CATCHES THAT [DW_GUESS_MIN_FILL] DOES NOT, and the reason the web had to add it: fill is
 * a ratio of AREAS, and area is a blunt instrument for the question "is this shape a quadrilateral".
 * An L-shaped bright region — the sort of thing a sheet half-occluded by a sleeve or a second sheet
 * makes — fills 87% of the quadrilateral drawn through its four extreme points, which sails past a
 * fill test set anywhere a genuinely shadowed corner could survive. The web's test suite found this
 * with a synthetic L (`declines a bright region that is not a quadrilateral`, carried over verbatim);
 * tightening the fill threshold enough to reject it would have started rejecting real sheets, which
 * is trading a rare wrong answer for a common missing one.
 *
 * Edges are the discriminating measurement because a quadrilateral is DEFINED by them. On a real
 * sheet the straight line between two adjacent corners lies along the paper's own boundary, so a
 * sample taken just inside it is on the sheet. On the L, the edge joining the two extreme corners
 * either side of the notch flies across empty table, and every sample along it misses.
 */
internal const val DW_GUESS_MIN_EDGE_SUPPORT = 0.85

/**
 * The standard deviation, in luma counts, below which the frame is too flat to contain a sheet.
 *
 * THIS IS A REFUSAL TO ANSWER, and it exists because the search cannot fail on its own. Otsu's method
 * always returns a level, even for a frame of one single value, and a threshold applied to a constant
 * image marks EVERY pixel as bright — one component, covering the frame, filling its own bounding
 * quadrilateral perfectly. So a photograph of a blank dark table, an out-of-focus wall, or a lens cap
 * produced a guess with fill 1.0 and frame share 0.99: the most confident possible answer, drawn round
 * the whole picture, from an image containing no sheet at all. Confidence computed from a degenerate
 * split is not weak evidence, it is no evidence, and it has to be rejected before it is measured
 * rather than by hoping the measurements notice.
 *
 * A sheet against a table is a two-population frame by construction, so any real subject clears this
 * easily. [DwImageQuality.MIN_CONTRAST_STDDEV] is the same number reached for a different judgement
 * there (whether a photograph is flat enough that a blur score would be meaningless); the two are
 * deliberately separate constants — on both clients — because they answer different questions and
 * either could move without the other.
 */
internal const val DW_GUESS_MIN_CONTRAST = 12.0

/**
 * How far the closing in [dwCloseBrightMask] reaches, as a fraction of the guess plane's short edge.
 *
 * It has to exceed half the width of the widest stroke that might cross the sheet, and stay far below
 * the smallest gap between the sheet and anything else bright in the frame. At [DW_GUESS_EDGE_PX] a
 * pencil line off a 12 MP photograph is on the order of one pixel, so 1/120 of the short edge — three
 * pixels on a 360px-tall guess plane, bridging gaps up to six — clears the first bound by a wide
 * margin while remaining under one percent of the frame.
 */
internal const val DW_GUESS_CLOSE_FRACTION = 1.0 / 120.0

/** How many samples are taken along each edge by [dwEdgeSupport]. The web's default. */
internal const val DW_EDGE_SAMPLES = 32

/* ────────────────────────────────────────────────────────────────────────────
 * The pieces
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Binary closing — dilate by [radius], then erode by the same — over the bright mask.
 *
 * WHY THE SEARCH CANNOT WORK WITHOUT THIS, discovered on the web by running the guess against a
 * generated photograph of an actual sketch rather than against a blank rectangle. The sheet is found
 * as the largest CONNECTED bright region, and a drawing is made of dark strokes ON that sheet. Any
 * stroke that runs from one edge of the paper to the other — a border, a fold line, a construction
 * line ruled across the page, all of which are ordinary things to draw — CUTS THE BRIGHT REGION IN
 * TWO. The largest component is then a band between two strokes, and the "sheet" that comes back is a
 * slice of the real one. The failure is silent and looks like a plausible answer.
 *
 * Closing removes it because a stroke is thin and a sheet is not. Dilating swallows any dark line
 * narrower than twice the radius, joining the paper back into one region; eroding by the same amount
 * puts the outer boundary back where it was, so the corners this is all in aid of do not move. That
 * asymmetry — thin features vanish, large ones are restored exactly — is the entire reason the two
 * passes are done in that order rather than either alone.
 *
 * Both passes go through a summed-area table so the cost is independent of the radius: a pixel is
 * bright after dilation when its window sum is above zero, and survives erosion when its window sum is
 * the whole window. [DoubleArray] where an [IntArray] would hold a 0/1 mask's running total at this
 * size, because it is what the web uses and what makes the `sum == count` comparison exact for any
 * radius rather than only for small ones.
 */
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

/**
 * The weakest of the four edges: for each, the fraction of samples taken just inside it that are
 * actually on the bright region.
 *
 * Sampling INWARD, along the line from the edge toward the quadrilateral's centre, rather than exactly
 * on the edge: the extreme-point corners sit ON the boundary, where a pixel is as likely to have
 * fallen on the dark side of the threshold as the bright one, so a sample taken exactly on the line
 * would report a real sheet's edges as half-supported. Two pixels in is unambiguously interior for
 * anything that is genuinely a sheet, and still unambiguously exterior where the edge crosses a notch.
 *
 * INTERNAL RATHER THAN PRIVATE, WHICH IS DELIBERATE AND IS THE ONLY EXPOSURE IN THIS FILE. This is
 * the gate that rejects the L, and [dwGuessSheetCorners] can only be observed returning `null` — a
 * whole-function test cannot say WHICH gate fired, so it cannot fail if this one is quietly deleted
 * while the fill threshold is tightened to compensate. `DwSketchRectifyGuessTest` calls it directly
 * on the L's own mask and pins 10/31; that is a measured fact about the discriminating measurement
 * rather than an assertion in a comment.
 */
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
        // NOT `continue`: an edge with nothing samplable at all is an edge this cannot vouch for, and
        // the whole guess is refused rather than scored on its three remaining sides. Verbatim from
        // the web, where the same early return is the reason a degenerate quadrilateral cannot sneak
        // past the support gate by having one unmeasurable side.
        if (total == 0) return 0.0
        weakest = min(weakest, hits.toDouble() / total)
    }
    return weakest
}

/** Twice the signed area of the polygon — the shoelace sum — halved and made positive. */
private fun dwShoelaceArea(corners: List<DwPoint>): Double {
    var total = 0.0
    for (index in 0 until 4) {
        val current = corners[index]
        val next = corners[(index + 1) % 4]
        total += current.x * next.y - next.x * current.y
    }
    return abs(total) / 2
}

/* ────────────────────────────────────────────────────────────────────────────
 * The guess
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Guess where the sheet is: the largest bright region in the frame, taken at its four extreme corners.
 *
 * THE MANUAL PATH IS THE REAL FEATURE AND THIS IS A CONVENIENCE ON TOP OF IT. [DwSketchRectifyPanel]
 * starts every sketch with four draggable handles inset from the frame edges, and it does that whether
 * or not this function found anything. It never commits, never rectifies on its own, and is never the
 * only way to place a corner.
 *
 * ON THIS CLIENT IT DOES NOT EVEN MOVE THE HANDLES, WHICH IS ONE STEP STRICTER THAN THE WEB. There,
 * `runGuess` calls `setCorners(guess.corners)` and says so in a note; a wrong guess is caught within
 * the second because that panel recomputes a live plate preview 60 ms after any corner changes, so
 * the designer is looking at the consequence before they have finished reading the note. This panel
 * has no live preview — a plate is built only when "Make the plate" is pressed — so the same
 * behaviour would replace four handles with a proposal and show nothing that contradicts it. So the
 * guess is DRAWN as an outline beside the handles and a second, explicit press moves them. That is
 * the rule [DwPhotoMeasurePanel] states as "it never writes a dimension by itself" and
 * [DwIdentityCardControl] applies to a read number: a machine-produced value is a proposal on screen
 * until a person accepts it. A wrong guess that silently moved the designer's corners would be worse
 * than no guess at all.
 *
 * HOW IT DECIDES, and why each step is defensible rather than merely effective on the fixtures:
 *
 *  1. Average the plane down to [DW_GUESS_EDGE_PX]. Paper texture, pencil strokes and JPEG noise all
 *     fragment the sheet into pieces at full resolution; averaging removes that structure and leaves
 *     the shape.
 *  2. Split bright from dark at [dwOtsuThreshold]. "Sheet against table" is a two-population question
 *     about the whole frame, which is the situation Otsu's method is actually for — unlike the
 *     line-art step, where the two populations differ from one end of the page to the other.
 *  3. Heal the drawing's own strokes out of the mask ([dwCloseBrightMask]) before looking for the
 *     sheet, then take the LARGEST 4-CONNECTED BRIGHT COMPONENT, not every bright pixel. A bright wall
 *     behind the table, or a sheet of paper further away, is bright too; without this step the extremes
 *     would be taken across all of them at once and the "sheet" would span the whole picture.
 *  4. Take that component's extremes along the two diagonals: min(x+y) is the top-left corner,
 *     max(x−y) the top-right, max(x+y) the bottom-right, min(x−y) the bottom-left. For a convex
 *     quadrilateral these are its actual corners, which is why the shape is recovered rather than its
 *     bounding box — a bounding box would be wrong for every sheet that is not already square to the
 *     frame, which is every sheet this feature exists for.
 *  5. REFUSE unless the result looks like a photograph of a sheet: it must fill its own quadrilateral
 *     ([DW_GUESS_MIN_FILL]), occupy a real share of the frame ([DW_GUESS_MIN_FRAME_SHARE]), and have
 *     all four edges running along actual sheet ([DW_GUESS_MIN_EDGE_SUPPORT]).
 *
 * Step 5 is the one that matters, and it is the reason this function returns `null` rather than a
 * low-confidence answer with a caveat. Uncertain means silent, and silent means the manual default
 * stands untouched.
 *
 * ── COST, AND WHERE THIS MAY BE CALLED FROM ───────────────────────────────────────────────────
 *
 * Bounded by construction: every step below is linear in the pixels of the REDUCED plane, which is at
 * most [DW_GUESS_EDGE_PX] on its long edge — 480x360 is 172,800 pixels, and there are a dozen or so
 * passes over it (the resample, the histogram, the threshold, four summed-area sweeps for the
 * closing, the fill, the extremes, the edge samples). Nothing here is quadratic and nothing scales
 * with the caller's plane except [DwImageQuality.resampleGrey]'s single output allocation, which IS
 * that reduced plane. It is still tens of milliseconds on a slow handset and it MUST NOT run on the
 * main thread; the panel calls it inside `withContext(Dispatchers.Default)`.
 */
internal fun dwGuessSheetCorners(plane: GreyPlane): DwCornerGuess? {
    if (plane.width < 8 || plane.height < 8) return null

    val scale = min(1.0, DW_GUESS_EDGE_PX.toDouble() / max(plane.width, plane.height))
    val width = max(4, DwPhotoMeasure.jsRound(plane.width * scale).toInt())
    val height = max(4, DwPhotoMeasure.jsRound(plane.height * scale).toInt())
    val small = if (scale < 1.0) DwImageQuality.resampleGrey(plane, width, height) else plane

    // Before anything is measured: a frame with no contrast has no two populations for Otsu to
    // separate, and every measurement below would be computed from a split that means nothing. See
    // DW_GUESS_MIN_CONTRAST — this is the difference between "no sheet found" and a maximally
    // confident rectangle drawn round a photograph of a table.
    if (DwImageQuality.contrastStdDev(small) < DW_GUESS_MIN_CONTRAST) return null

    val cut = dwOtsuThreshold(small)
    val raw = ByteArray(small.width * small.height)
    for (index in raw.indices) raw[index] = if (small.at(index) > cut) 1.toByte() else 0.toByte()
    val radius = max(
        1,
        DwPhotoMeasure.jsRound(min(small.width, small.height) * DW_GUESS_CLOSE_FRACTION).toInt(),
    )
    val bright = dwCloseBrightMask(raw, small.width, small.height, radius)

    /*
     * Iterative flood fill over an explicit stack. Recursion here would be one frame per pixel and a
     * 480x360 region is 172,800 of them — a guaranteed StackOverflowError, which on a phone is a
     * crashed form in the middle of fieldwork rather than a caught error.
     *
     * The stack is an IntArray with a top index rather than the web's `number[]`, and rather than the
     * `ArrayDeque<Int>` that reads more naturally here: every push through a boxed collection is an
     * `Integer` allocation, and the worst case is one push per pixel, so an ordinary photograph would
     * put a hundred thousand short-lived boxes through the young generation while the designer waits.
     * A pixel is pushed only at the moment its label is set, so it is pushed at most once and the
     * array can never overflow.
     */
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
        // Strictly greater / strictly less, and the scan runs in index order, so a tie keeps the pixel
        // found FIRST — the topmost row, then the leftmost column. That is not arbitrary tidiness: the
        // top and bottom edges of a sheet square to the frame are whole rows at the same x+y, and the
        // two clients have to pick the same end of them or their guesses differ by the sheet's width.
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

    // Back to the caller's coordinates. The reduced plane's pixel (x, y) covers a block of the
    // original, so the +0.5 centres the corner in that block rather than pinning it to the block's
    // top-left — a half-block bias applied to all four corners would shrink the quadrilateral
    // systematically.
    val back = 1 / (if (scale < 1.0) scale else 1.0)
    val lifted = DwSketchRectify.orderCorners(
        ordered.map { DwPoint((it.x + 0.5) * back, (it.y + 0.5) * back) },
    ) ?: return null
    return DwCornerGuess(corners = lifted, fill = fill, frameShare = frameShare, edgeSupport = support)
}
