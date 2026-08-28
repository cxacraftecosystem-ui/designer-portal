package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device photograph quality checks — blur, resolution, duplicates and missing views.
 *
 * THE KOTLIN HALF OF frontend/lib/imageQuality.ts. Everything here is a value-for-value port of that
 * module's pure core; the Android decode adapter that feeds it lives in [DwImageDecode]
 * (ImageQualityDecode.kt), which is the only file that knows what a Bitmap is.
 *
 * ── WHY THIS EXISTS ON THE HANDSET, WHICH IS THE WHOLE POINT ──────────────────────────────────
 *
 * The web module's header argues the case and the argument applies here twice over: a designer
 * photographs a product in a village a couple of hundred kilometres from the next reliable
 * connection, and the upload happens hours or days later. A check that runs on the server reports
 * "that photograph is out of focus" to somebody at a desk, about an object that has since been sold,
 * in a cluster nobody is going back to this month. The finding is true and completely useless. The
 * only moment it is worth anything is while the designer is still standing in front of the object,
 * and on this app that moment happens on the phone — which was, until this file, the one client where
 * the check did not run. A blurred prototype photograph was an irreversible loss discovered weeks
 * later at the office.
 *
 * ── THIS MEASURES AND NEVER MODIFIES, AND THAT IS A HARD RULE ─────────────────────────────────
 *
 * docs/MEDIA_PIPELINE.md §5 records a deliberate refusal to re-encode images: this is a heritage
 * archive, the original file IS the artifact, and a decode-and-re-save round trip destroys full
 * resolution and strips the EXIF that [WorkshopDraftStore.importMedia] captures on purpose. So there
 * is no write path in this file or in its adapter, and there must never be one — a "helpful"
 * downscale here would quietly become the thing the pipeline document exists to forbid.
 *
 * ── A FINDING IS ADVICE, NEVER A REFUSAL ──────────────────────────────────────────────────────
 *
 * Every function returns descriptions. None of them can stop an import, delete a file or swap one
 * out. A designer may have deliberately photographed something blurred (a loom in motion), or may be
 * holding the ONLY photograph that will ever exist of an object that is about to leave. Blocking
 * that is a worse failure than every problem this file can detect, put together.
 *
 * **AND ON 2026-08-28 THE SURFACING STOPPED KEEPING IT THAT WAY, ON AN OWNER'S INSTRUCTION.** This
 * paragraph used to end "and the surfacing must keep it that way"; it no longer can.
 * [DwPhotoGate] refuses a photograph outright for blur, for low resolution and for being byte-identical
 * to one already attached, before [WorkshopDraftStore.importMedia] copies anything — the owner's
 * decision that a shaky or poor photograph must not reach the server, which is theirs to make. THE
 * ARGUMENT ABOVE SURVIVES EVERYWHERE ELSE and the gate is narrow because of it: a near-duplicate is
 * still only warned about, an image this device cannot measure is admitted rather than refused, and
 * nothing in this file gained the power to stop anything. Read DwPhotoGate.kt's header before
 * widening either side of that line — including the loom-in-motion case, which is now the strongest
 * argument for an override that does not exist yet.
 *
 * ONE CONSEQUENCE FOR THE SENTENCES BELOW. [findQualityIssues] still says "If it is meant to be soft,
 * or it is the only shot you have, keep it", and that stays true because of WHERE it runs: the
 * advisory card reads photographs that are ALREADY attached — imported by an older build, attached in
 * the browser and synced down, or produced by a panel that derives its own file — and for those there
 * is genuinely nothing to do but keep them. A blurred photograph a designer picks today never gets
 * that far.
 *
 * ── WHY THE PORT IS PINNED BY VALUE ───────────────────────────────────────────────────────────
 *
 * ImageQualityParityTest runs a shared case table through this file and diffs it against numbers
 * produced by the REAL frontend/lib/imageQuality.ts source. That is not ceremony. JavaScript stores a
 * greyscale plane in a `Uint8ClampedArray`, whose assignment conversion rounds HALF TO EVEN
 * (ECMAScript ToUint8Clamp), while `Double.roundToInt` — the obvious Kotlin choice — rounds ties
 * toward positive infinity. Written that way, this port disagreed with the web on 18 of 39 measured
 * values, INCLUDING two perceptual hashes: `a5a54a4aa5a54a4a` became `8585424285854242`. Two handsets
 * and a browser would then have disagreed about whether two photographs are the same shot. See
 * [clampToByte], and note that `kotlin.math.round` happens to be ties-to-even where `roundToInt` is
 * not, which is exactly the sort of thing nobody should have to remember while reading this.
 */

// ---------------------------------------------------------------------------
// The pure numeric core — no Android, no network, no Compose
// ---------------------------------------------------------------------------

/**
 * A single-channel (luma) image.
 *
 * [data] is a `ByteArray` holding UNSIGNED bytes, read through [at]. It mirrors the web's
 * `Uint8ClampedArray` exactly — same storage width, same clamping rule (see [clampToByte]) — and the
 * width matters on a handset: the largest plane this feature ever holds is the post-`inSampleSize`
 * decode, and an `IntArray` would make that four times the allocation on the device that can least
 * afford it, at the moment the designer wants to take the next photograph.
 */
class GreyPlane(val data: ByteArray, val width: Int, val height: Int) {
    /** The unsigned value at [index]. Kotlin's `Byte` is signed; every read here must mask. */
    fun at(index: Int): Int = data[index].toInt() and 0xFF
}

object DwImageQuality {

    /**
     * The long edge every measurement is taken at, and the reason the thresholds below are numbers
     * rather than opinions.
     *
     * A 12 MP phone photograph is around 4000x3000. Convolving twelve million pixels on the main
     * thread of a low-end handset is a visible freeze at exactly the moment the designer wants to take
     * the next picture, and it is unnecessary: defocus is a LOW-frequency phenomenon — a photograph
     * that is genuinely out of focus is still obviously out of focus at 640px, because the blur radius
     * scales down with everything else. 640 on the long edge is ~307k pixels for a 4:3 frame, about
     * forty times less arithmetic than full size.
     *
     * TWO CONSEQUENCES, BOTH DELIBERATE.
     *
     * First, the blur threshold is meaningless without this number: variance of the Laplacian depends
     * entirely on the scale it is measured at, so moving this constant invalidates
     * [BLUR_VARIANCE_FLOOR] and the calibration must be re-run on BOTH sides. They move together or
     * not at all — which is why ImageQualityParityTest asserts this value rather than reading it.
     *
     * Second, downscaling is itself a low-pass filter, so a photograph that is only SLIGHTLY soft
     * looks sharp here — a 4px blur at 4000px wide is a 0.64px blur at 640px, which is nothing. That
     * is a deliberate bias toward silence, not an oversight: this check is meant to catch the
     * photograph that is badly out of focus, and to say nothing at all about the marginal ones.
     */
    const val WORK_EDGE_PX = 640

    /**
     * Below this the "blurred" warning is withheld no matter how low the Laplacian variance is,
     * because the measure stops meaning anything. IT IS NOT OPTIONAL.
     *
     * Variance of the Laplacian scales with the SQUARE of the image's own contrast, so a perfectly
     * sharp photograph of a low-contrast subject scores like a blurred one. That is not a rare corner
     * in this archive — undyed cotton on a white sheet, raw bamboo against a mud wall and a pale
     * terracotta pot in flat shade are all ordinary craft documentation, and all of them are
     * low-contrast by nature. Warning on those would train designers to ignore the warning, which
     * costs more than the blurred photographs it would have caught.
     *
     * MEASURED, and re-measured by this port's own parity run: a perfectly sharp 1/f field scaled to a
     * flat subject scores a blur variance of 58.98 — BELOW [BLUR_VARIANCE_FLOOR], i.e. it would be
     * reported as blurred — at a contrast of 9.03. Blurring, by contrast, barely moves this number at
     * all (the same field measures 32.23 sharp and 29.58 after a radius-4 blur), because defocus
     * removes high-frequency detail and leaves the overall spread of tones intact. That asymmetry is
     * what makes contrast a safe guard: it can distinguish "flat" from "unfocused", so withholding the
     * warning below 12 costs no genuine blur detections while removing the one false positive that
     * matters.
     */
    const val MIN_CONTRAST_STDDEV = 12.0

    /**
     * The variance-of-Laplacian score below which a photograph is called blurred, measured on a
     * [WORK_EDGE_PX] greyscale downscale.
     *
     * CALIBRATED, NOT ASSERTED. The corpus, re-derived by this port from the same generators the web
     * calibration uses (ImageQualityParityTest pins every figure):
     *
     *   SHARP                                     BLURRED (same source, box blur radius 1 / 2 / 4)
     *   1/f "photo-like" field, seed 1337 ..  732.55   ................  5.12 /  2.36 /  1.76
     *   1/f "photo-like" field, seed 90210 .  806.59   ................................  1.77
     *   checkerboard, 8px cells ........... 40106.18   (synthetic ceiling, not a photograph)
     *
     * So the entire realistic sharp population sits at 732 and above, and everything genuinely out of
     * focus sits well below 60 — a gap of more than two orders of magnitude with nothing in it. 60 is
     * placed at the BOTTOM of that gap, hard against the blurred population rather than in the middle.
     *
     * That asymmetry is the whole design. A missed blurred photograph costs one photograph. A false
     * "this is blurred" on a photograph that is perfectly sharp costs the FEATURE: a designer who is
     * wrongly warned twice stops reading warnings, and then the real one goes past unread too. The
     * cost of that choice is real and accepted — photographs that are only slightly soft score in the
     * hundreds and pass unremarked. Do not tighten this toward the middle of the gap without re-running
     * the calibration on both clients and thinking again about which error this feature can afford.
     */
    const val BLUR_VARIANCE_FLOOR = 60.0

    /**
     * The long edge a photograph needs to survive being printed as a plate in the generated report.
     *
     * DERIVED FROM THE REPORT'S OWN GEOMETRY, not from a round number. The report is A4 (210mm wide,
     * `PageSize.sizeMm` in report/ReportModel.kt) with 25mm margins (`ReportMeta.marginMm`), so the
     * text column — `textWMm = w - 2 * marginMm` in report/DocxWriter.kt — is 160mm, which is 6.2992in
     * of paper, and a full-width plate is exactly that wide. The full-width landscape plate is the
     * binding case: the portrait height cap needs fewer pixels, and every multi-up gallery cell needs
     * far fewer.
     *
     * The dpi to multiply it by is the one figure this repository actually commits to: RENDER_DPI =
     * 200.0 in report/ReportRaster.kt, whose own comment says it "must equal report_raster.RENDER_DPI"
     * — the resolution the app rasterises its own maps and charts at. 6.2992in x 200dpi = 1260 pixels;
     * 1280 is that rounded up.
     *
     * WHY NOT THE 300 DPI PRINT STANDARD, which would put this at 1890. Because a warning has to be
     * unarguable to survive being read twice. Below 1280 a photograph provably cannot fill the plate at
     * the resolution this app itself considers adequate — there is nothing to debate. Between 1280 and
     * 1890 it merely falls short of commercial offset print, which is a real but arguable concern, and
     * firing there would flag a great many photographs that look perfectly good in the delivered
     * document. Silence is the better answer in that band. (Photographs are embedded at their original
     * size and never resampled, so nothing downstream will rescue a small one — which is why saying so
     * at capture time is the only chance anybody gets.)
     */
    const val MIN_LONG_EDGE_PX = 1280

    /**
     * How many of a 64-bit perceptual hash's bits may differ before two photographs are called the
     * same shot.
     *
     * Two genuinely unrelated photographs sit near 32 (half the bits, i.e. chance). The same frame
     * re-encoded or re-imported sits at 0-2. Two exposures of one object seconds apart land in the low
     * single digits. 6 therefore separates "you have photographed this twice" from "these are two
     * different products" with a wide margin on both sides — and, again, it is set at the tight end on
     * purpose, because telling a designer that two distinct products are duplicates is the failure that
     * gets the whole warning ignored.
     */
    const val NEAR_DUPLICATE_MAX_DISTANCE = 6

    /** The dHash grid: 8 rows of 8 comparisons, taken from a 9x8 resample. */
    private const val HASH_EDGE = 8

    // ── Byte arithmetic ──────────────────────────────────────────────────────────────────────────

    /**
     * ECMAScript's `ToUint8Clamp`, which is what a `Uint8ClampedArray` assignment performs — clamp to
     * 0..255 and round HALF TO EVEN.
     *
     * THIS IS THE TRAP OF THE WHOLE PORT and it is not theoretical. `Double.roundToInt` — the obvious
     * thing to reach for, and the one that reads as harmless in review — rounds ties toward positive
     * infinity, so it disagrees with the web on every value that lands exactly on .5, and a greyscale
     * conversion produces those constantly. Written that way and run against the shared case table,
     * this port differed on 18 of 39 measured values: three of six brute-forced RGB triples whose
     * Rec.601 luma is an exact .5, the calibration checkerboard's dark tone (1 instead of 0) and
     * therefore its contrast (127.0 instead of 127.5) — and, worst, two perceptual hashes, with
     * `a5a54a4aa5a54a4a` coming out as `8585424285854242`. Two handsets and a browser would then have
     * given different answers to "is this the same shot", which is exactly the sort of silent
     * divergence a report submitted to a ministry cannot carry.
     *
     * Written out rather than delegated to `kotlin.math.round`, which happens to be ties-to-even and
     * would therefore work: relying on that means every future reader has to already know that `round`
     * and `roundToInt` break ties differently, and the one who does not know is the one who "tidies"
     * this into the wrong one.
     *
     * NaN maps to 0 rather than throwing: a plane is arithmetic on bytes and must never be the thing
     * that takes down an import.
     */
    fun clampToByte(value: Double): Int {
        if (value.isNaN()) return 0
        if (value <= 0.0) return 0
        if (value >= 255.0) return 255
        val f = floor(value)
        return when {
            f + 0.5 < value -> (f + 1).toInt()
            value < f + 0.5 -> f.toInt()
            // The exact tie. Round to the EVEN neighbour, as the spec does.
            f.toInt() % 2 != 0 -> (f + 1).toInt()
            else -> f.toInt()
        }
    }

    /**
     * JavaScript's `Math.round` — floor(x + 0.5), i.e. half rounds UP rather than away from zero.
     *
     * Used only where the web uses `Math.round`: the sharpness score printed in the warning, and —
     * since 2026-08-28 — the same score printed in [DwPhotoGate]'s refusal. It is a different rule
     * from [clampToByte] on purpose, because the two are different rules in the source being ported,
     * and quietly unifying them would put a different number in front of the designer than the web
     * shows for the same photograph.
     *
     * `internal` rather than private for that second caller alone. The gate is the surface that now
     * PRINTS this number where a designer can act on it, and a second rounding rule written out
     * there would be two answers to "what was the sharpness reading" on one screen.
     */
    internal fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

    /**
     * The exact plane size a photograph of [width] x [height] must be measured at.
     *
     * Lives in the tested core rather than in the decode adapter because it is part of the
     * calibration, not part of the plumbing: [BLUR_VARIANCE_FLOOR] is only meaningful against a plane
     * this size, so a handset that arrived at 512 or 683 through some rounding of its own would be
     * comparing a photograph against a threshold set for a different image. An image already at or
     * below the working edge is measured at its own size and never enlarged — upscaling invents
     * high-frequency detail, which is precisely what the blur measure reads.
     */
    fun workingSizeFor(width: Int, height: Int): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= 0) return 0 to 0
        val scale = if (longEdge <= WORK_EDGE_PX) 1.0 else WORK_EDGE_PX.toDouble() / longEdge
        return max(1L, jsRound(width * scale)).toInt() to max(1L, jsRound(height * scale)).toInt()
    }

    // ── Planes ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Rec.601 luma from packed ARGB pixels — the same weighting every image library uses for "convert
     * to greyscale".
     *
     * Takes an `IntArray` of packed ARGB because that is what `Bitmap.getPixels` produces, and taking
     * it here rather than in the adapter keeps the conversion inside the tested half. The web's
     * `toGreyPlane` takes RGBA bytes off a canvas; the channel values are identical, so the arithmetic
     * is identical, and the parity test drives this function with the same sweep the web module is
     * driven with.
     *
     * It is a NAMED step rather than three inlined multiplications because the blur measure and the
     * perceptual hash must agree on what grey means — two different greyscale conversions would make
     * the two measurements quietly incomparable with each other and with the web.
     */
    fun greyPlaneFromArgb(pixels: IntArray, width: Int, height: Int): GreyPlane {
        val data = ByteArray(width * height)
        for (index in data.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            data[index] = clampToByte((red * 299 + green * 587 + blue * 114) / 1000.0).toByte()
        }
        return GreyPlane(data, width, height)
    }

    /**
     * Box-average [plane] down to [width] x [height].
     *
     * Averaging every source pixel that falls in a target cell, rather than sampling one of them, is
     * what makes the perceptual hash stable: a nearest-neighbour shrink of the same photograph taken a
     * frame apart lands on different source pixels and produces a different hash, which is precisely
     * the near-duplicate this is supposed to catch.
     *
     * The bounds use Int division where the web uses `Math.floor` of a float division. For
     * non-negative integers below 2^53 the two are the same value, and Int division cannot suffer the
     * rounding-to-nearest that would let a float quotient land on the wrong side of an integer.
     */
    fun resampleGrey(plane: GreyPlane, width: Int, height: Int): GreyPlane {
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            val sourceTop = (y * plane.height) / height
            val sourceBottom = max(sourceTop + 1, ((y + 1) * plane.height) / height)
            for (x in 0 until width) {
                val sourceLeft = (x * plane.width) / width
                val sourceRight = max(sourceLeft + 1, ((x + 1) * plane.width) / width)
                // Double rather than Int, so the accumulation is bit-for-bit what a JavaScript number
                // does. Every term is an exact small integer, so nothing is lost either way — but
                // "either way" is not a claim a parity test should have to make.
                var total = 0.0
                var count = 0
                for (sy in sourceTop until sourceBottom) {
                    for (sx in sourceLeft until sourceRight) {
                        total += plane.at(sy * plane.width + sx)
                        count += 1
                    }
                }
                data[y * width + x] = clampToByte(if (count > 0) total / count else 0.0).toByte()
            }
        }
        return GreyPlane(data, width, height)
    }

    /** Standard deviation of the luma plane, 0-255 — the contrast guard of [MIN_CONTRAST_STDDEV]. */
    fun contrastStdDev(plane: GreyPlane): Double {
        val length = plane.data.size
        if (length == 0) return 0.0
        var sum = 0.0
        for (index in 0 until length) sum += plane.at(index)
        val mean = sum / length
        var squares = 0.0
        for (index in 0 until length) {
            val delta = plane.at(index) - mean
            squares += delta * delta
        }
        return sqrt(squares / length)
    }

    /**
     * Variance of the Laplacian — the standard sharpness measure.
     *
     * The 4-neighbour Laplacian [[0,1,0],[1,-4,1],[0,1,0]] is a second-derivative filter, so it
     * responds to edges and to nothing else. A sharp photograph is full of hard edges and its response
     * has a wide spread; blurring smears every edge across many pixels, which flattens the response
     * toward zero and collapses the variance. Higher is sharper.
     *
     * The border ring is skipped rather than clamped or wrapped: a clamped edge invents a
     * zero-gradient boundary and a wrapped one invents an enormous artificial edge where the top row
     * meets the bottom. Both would be measuring the convolution's own boundary policy rather than the
     * photograph.
     */
    fun laplacianVariance(plane: GreyPlane): Double {
        val width = plane.width
        val height = plane.height
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val response = (
                    plane.at(index - width) + plane.at(index + width) +
                        plane.at(index - 1) + plane.at(index + 1) - 4 * plane.at(index)
                    ).toDouble()
                sum += response
                squares += response * response
                count += 1
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return squares / count - mean * mean
    }

    /**
     * A 64-bit dHash as 16 hex characters: box-average to 9x8 greyscale, then record whether each
     * pixel is brighter than the one to its right.
     *
     * A DIFFERENCE hash rather than an average hash on purpose. aHash compares every pixel to the
     * frame's mean, so it is dominated by overall exposure — two different products shot against the
     * same wall in the same light hash alike, which is a false duplicate. dHash encodes the direction
     * of local gradients instead, which is a property of the SUBJECT, and it is naturally immune to the
     * exposure and white-balance drift between two shots of one object.
     *
     * Hex rather than a Long so a hash can sit in a map key, a Compose key or a log line without any
     * unsigned-arithmetic ceremony — and so it is directly comparable with the string the web stores.
     */
    fun differenceHash(plane: GreyPlane): String {
        val small = resampleGrey(plane, HASH_EDGE + 1, HASH_EDGE)
        val hex = StringBuilder(HASH_EDGE * 2)
        for (y in 0 until HASH_EDGE) {
            var byte = 0
            for (x in 0 until HASH_EDGE) {
                val left = small.at(y * (HASH_EDGE + 1) + x)
                val right = small.at(y * (HASH_EDGE + 1) + x + 1)
                byte = (byte shl 1) or (if (left > right) 1 else 0)
            }
            hex.append(byte.toString(16).padStart(2, '0'))
        }
        return hex.toString()
    }

    /** Differing bits between two [differenceHash] values; 64 means nothing in common. */
    fun hammingDistance(left: String, right: String): Int {
        if (left.length != right.length) return HASH_EDGE * HASH_EDGE
        var distance = 0
        for (index in left.indices) {
            val a = Character.digit(left[index], 16)
            val b = Character.digit(right[index], 16)
            // A character that is not hex contributes nothing rather than throwing. The web reaches
            // the same place through NaN; either way a malformed hash must not be able to crash a
            // capture screen, and it must never be able to CLAIM a duplicate.
            if (a < 0 || b < 0) continue
            var diff = a xor b
            while (diff != 0) {
                distance += diff and 1
                diff = diff shr 1
            }
        }
        return distance
    }

    // ── Verdicts ─────────────────────────────────────────────────────────────────────────────────

    fun isBlurred(measurement: ImageMeasurement): Boolean = isBlurred(measurement.blurScore, measurement.contrast)

    fun isBlurred(blurScore: Double, contrast: Double): Boolean {
        // The contrast guard comes FIRST and is not a tie-breaker: where there is too little contrast
        // for the measure to discriminate, the answer is "no finding", never "probably blurred".
        if (contrast < MIN_CONTRAST_STDDEV) return false
        return blurScore < BLUR_VARIANCE_FLOOR
    }

    fun isUnderResolution(width: Int, height: Int): Boolean = max(width, height) < MIN_LONG_EDGE_PX

    // ── Findings ─────────────────────────────────────────────────────────────────────────────────

    private fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many

    /**
     * Everything wrong with one photograph, given what it is being compared against.
     *
     * Pure and synchronous: it takes measurements and returns sentences. It cannot import, cannot
     * upload and cannot refuse.
     *
     * @param checksum this file's own SHA-256, when it has been computed. Absent is "unknown", NEVER
     *   "unique" — a legacy descriptor with no hash must produce no claim in either direction.
     */
    fun findQualityIssues(
        measurement: ImageMeasurement,
        checksum: String? = null,
        attached: List<AttachedImage> = emptyList(),
    ): List<QualityFinding> {
        val findings = mutableListOf<QualityFinding>()

        if (isBlurred(measurement)) {
            findings += QualityFinding(
                flag = QualityFlag.BLUR,
                severity = QualitySeverity.MEDIUM,
                message = "Sharpness score ${jsRound(measurement.blurScore)} — a sharp photograph " +
                    "normally scores well above ${BLUR_VARIANCE_FLOOR.toInt()} here. This one may be " +
                    "out of focus. If it is meant to be soft, or it is the only shot you have, keep it.",
            )
        }

        if (isUnderResolution(measurement.width, measurement.height)) {
            findings += QualityFinding(
                flag = QualityFlag.LOW_RESOLUTION,
                severity = QualitySeverity.MEDIUM,
                message = "${measurement.width}x${measurement.height} — a catalogue plate needs about " +
                    "${MIN_LONG_EDGE_PX}px on the long edge to print sharply at A4. This will still be " +
                    "saved; it will simply print soft.",
            )
        }

        // Exact first, and exclusively: when the bytes are identical there is nothing a perceptual
        // hash can add, and reporting "duplicate" twice about one file reads as two separate problems.
        val identical = if (checksum.isNullOrBlank()) {
            emptyList()
        } else {
            attached.filter { !it.checksum.isNullOrBlank() && it.checksum == checksum }
        }
        if (identical.isNotEmpty()) {
            findings += QualityFinding(
                flag = QualityFlag.DUPLICATE,
                severity = QualitySeverity.LOW,
                message = "The same file is already attached here as " +
                    identical.joinToString(", ") { "\"${it.label}\"" } +
                    " — identical content, so this looks like the same import twice.",
            )
        } else {
            val near = attached
                .map { item ->
                    item to if (!item.perceptualHash.isNullOrBlank() && measurement.perceptualHash.isNotBlank()) {
                        hammingDistance(measurement.perceptualHash, item.perceptualHash)
                    } else {
                        Int.MAX_VALUE
                    }
                }
                .filter { (_, distance) -> distance <= NEAR_DUPLICATE_MAX_DISTANCE }
                .sortedBy { (_, distance) -> distance }
            if (near.isNotEmpty()) {
                findings += QualityFinding(
                    flag = QualityFlag.DUPLICATE,
                    severity = QualitySeverity.LOW,
                    message = "This looks like the same shot as " +
                        near.joinToString(", ") { (item, _) -> "\"${item.label}\"" } +
                        ". Two views of one object are worth keeping; two copies of one view are not.",
                )
            }
        }

        return findings
    }

    // ── Missing views — ONLY where the registry actually has named view slots ─────────────────────

    /**
     * The named single-image view slots that genuinely exist in the stage registry.
     *
     * VERIFIED AGAINST app/src/main/assets/design-workshop-schema.json RATHER THAN ASSUMED, because
     * this is exactly the check that is tempting to invent. Stage 6's `existingProduct` collection
     * really does declare `viewFront` / `viewBack` / `viewDetail` as three separate Advanced IMAGE
     * fields, so "you have a front but no back" is a statement about a slot the designer can see and
     * fill.
     *
     * Nowhere else has them. Stage 11's `sketch` has one `image`; stage 16's `finalProduct` has the
     * `finalPhotos` gallery, `catalogPhotos` and `turntablePhotos` — galleries, not named views. A
     * "missing back view" warning on either would point at a field that does not exist, which is worse
     * than no warning at all: it asks for something the form cannot accept. So this map is the whole
     * feature, and it grows only when the registry does.
     *
     * `linkedMapOf` and not `mapOf`, because the sentence lists the missing views in declaration
     * order and a reordered map would print "no detail view, no back view" on one build and the
     * reverse on the next.
     */
    val NAMED_VIEW_SLOTS: Map<String, Map<String, String>> = mapOf(
        "existingProduct" to linkedMapOf(
            "viewFront" to "Front view",
            "viewBack" to "Back view",
            "viewDetail" to "Detail view",
        )
    )

    /**
     * Which named views this row is missing — but only once at least one has been filled.
     *
     * The "at least one" rule is what keeps this from nagging. Every one of these slots is
     * Advanced-tier and optional; a designer who filled none of them has decided this product does not
     * need a multi-view record, and telling them three times that they have not started is noise. A
     * designer who filled the front and the detail has plainly begun the set, and THAT is the person
     * for whom "no back view" is useful while the product is still on the table.
     */
    fun findMissingViews(entityKey: String, values: Map<String, JsonElement?>): List<QualityFinding> {
        val slots = NAMED_VIEW_SLOTS[entityKey] ?: return emptyList()
        val keys = slots.keys.toList()
        val present = keys.filter { key -> viewSlotFilled(values[key]) }
        if (present.isEmpty() || present.size == keys.size) return emptyList()
        val missing = keys.filterNot { it in present }
        return listOf(
            QualityFinding(
                flag = QualityFlag.MISSING_VIEW,
                severity = QualitySeverity.LOW,
                message = "${present.size} of ${keys.size} views captured — no " +
                    missing.joinToString(", no ") { slots.getValue(it).lowercase() } + ". " +
                    plural(missing.size, "That view is", "Those views are") +
                    " easiest to add now, while the product is still in front of you.",
            )
        )
    }

    /**
     * Does this slot hold a photograph?
     *
     * An EMPTY LIST is not a filled slot, and that is the case worth stating: a designer who attached
     * a back view and then removed it leaves `[]` behind in the draft, and counting that as filled
     * would make the missing-view hint disappear at exactly the moment it became true again.
     */
    private fun viewSlotFilled(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonArray -> value.isNotEmpty()
        // These slots are IMAGE fields, so the primitive is a media id or an empty string; the web's
        // `Boolean(value)` on the same data is the same test.
        is JsonPrimitive -> value.content.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }
}

// ---------------------------------------------------------------------------
// The vocabulary
// ---------------------------------------------------------------------------

/**
 * The flag names are MEDIA_QUALITY_FLAG in backend/app/services/stage_schema.py, verbatim — the same
 * words stage 21's "Media quality flags" table records against a file. Inventing a second set of names
 * here would mean the warning a designer reads in the field and the flag the archive stores describe
 * the same problem differently.
 */
enum class QualityFlag { BLUR, LOW_RESOLUTION, DUPLICATE, MISSING_VIEW }

/** SEVERITY in the same registry. Advisory in every case; nothing here blocks anything. */
enum class QualitySeverity { LOW, MEDIUM, HIGH }

data class QualityFinding(
    val flag: QualityFlag,
    val severity: QualitySeverity,
    /**
     * What to put in front of the designer — and it always carries the MEASUREMENT, not just the
     * verdict. "Sharpness score 42 (sharp photographs score well above 60)" lets somebody who
     * deliberately photographed a moving loom dismiss it in one read; "Image may be blurred" gives them
     * nothing to judge with and is indistinguishable from the app being wrong.
     */
    val message: String,
)

/** Everything one photograph yields, before any of it is judged. */
data class ImageMeasurement(
    /** The file's own pixel dimensions, NOT the working size — this is what the report has to print. */
    val width: Int,
    val height: Int,
    /** Variance of the Laplacian at [DwImageQuality.WORK_EDGE_PX]. Higher is sharper. */
    val blurScore: Double,
    /** Luma standard deviation at the working size; below the guard, blur is unjudgeable. */
    val contrast: Double,
    /** 64-bit dHash as 16 hex characters, for near-duplicate comparison. */
    val perceptualHash: String,
    /** Wall-clock cost of decode + measurement, so the on-device budget can be asserted, not assumed. */
    val elapsedMs: Long,
)

/**
 * A photograph already attached to this field, as far as duplicate detection is concerned.
 *
 * [checksum] is the SHA-256 [WorkshopDraftStore.importMedia] already computed during the copy, so an
 * EXACT duplicate is a lookup against data that already exists on this handset rather than a second
 * hash of the same bytes.
 */
data class AttachedImage(
    val label: String,
    val checksum: String? = null,
    /** Null until this photograph has been measured; a null hash never matches anything. */
    val perceptualHash: String? = null,
)
