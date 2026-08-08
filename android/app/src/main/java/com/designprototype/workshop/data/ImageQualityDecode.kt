package com.designprototype.workshop.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

/**
 * The Android half of [DwImageQuality]: turn a file on this phone into an [ImageMeasurement].
 *
 * This is the ONLY file in the feature that knows what a Bitmap is. Everything it hands back is
 * measured by the pure core, which is pinned value-for-value against the web module — so the split is
 * not tidiness, it is what makes the arithmetic testable on the desktop JVM at all. There is no
 * Robolectric in this module (app/build.gradle.kts declares JUnit 4 and nothing else), so anything
 * that touches BitmapFactory is, by construction, code no unit test can reach.
 *
 * ── IT READS. IT NEVER WRITES. ────────────────────────────────────────────────────────────────
 *
 * docs/MEDIA_PIPELINE.md §5 refuses re-encoding outright: this is a heritage archive, the original
 * file IS the artifact, and the app promises on screen that captured files go up unchanged.
 * [WorkshopDraftStore.importMedia] captures the EXIF orientation, the original camera timestamp and
 * the GPS fix at import precisely because a re-encode would destroy them. So this file opens the JPEG,
 * decodes a scratch bitmap, reads numbers off it, recycles it, and leaves the bytes on disk untouched.
 * There is no compress(), no FileOutputStream and no rename anywhere below, and there must never be
 * one — a "helpful" downscale here is exactly the thing the pipeline document exists to forbid.
 *
 * ── WHY inSampleSize, AND WHY IT IS NOT THE WHOLE ANSWER ──────────────────────────────────────
 *
 * A 12 MP photograph is ~4000x3000, which is 48 MB of ARGB_8888 heap. Decoding that on a 2 GB field
 * handset while the designer is taking the next picture is how a process gets killed, and it is
 * unnecessary: [DwImageQuality.WORK_EDGE_PX] is 640 and defocus is a low-frequency phenomenon that
 * survives the downscale intact. `inSampleSize` is the platform's way of not allocating the other 47
 * MB — the decoder subsamples while it decodes, so the full frame never exists in memory.
 *
 * But inSampleSize only takes powers of two, and THE WORKING SIZE AND THE BLUR THRESHOLD MUST MOVE
 * TOGETHER: variance of the Laplacian scales with the resolution it is measured at, so measuring a
 * 4000px original at 1000px (sample 4) and a 1600px original at 800px (sample 2) would compare two
 * photographs against a threshold calibrated for neither. So the sample is chosen as the largest power
 * of two that still leaves the long edge at or above 640, and the exact working size is then reached by
 * [DwImageQuality.resampleGrey] — a box average, which is the same kind of filter the web's
 * `resizeQuality: "high"` applies, and deliberately not a nearest-neighbour shrink: a cheap resize
 * aliases, aliasing is high-frequency energy, and high-frequency energy is exactly what the blur
 * measure reads, so it would make blurred photographs score as sharp.
 *
 * ── EXIF ORIENTATION IS DELIBERATELY NOT APPLIED ──────────────────────────────────────────────
 *
 * `BitmapFactory` ignores the EXIF orientation tag, so everything below measures the frame as it is
 * STORED, not as it is displayed. That is the correct choice here and it should not be "fixed":
 * [WorkshopDraftStore.withImageMetadata] records `width`/`height` from the same header read and keeps
 * `rotationDegrees` as a separate field, so applying rotation here would make the dimensions in the
 * warning disagree with the dimensions in the draft for exactly the portrait photographs that make up
 * most of a workshop.
 *
 * Nor does it cost accuracy. The resolution check compares the LONG edge, which no rotation changes.
 * The 4-neighbour Laplacian kernel is symmetric under quarter turns and the interior maps onto itself,
 * so the blur score and the contrast are identical either way. The perceptual hash is the one
 * measurement a rotation would change — and every hash compared against another is produced by this
 * same function on this same device, so they stay comparable with each other, which is all the
 * duplicate check needs. (A hash from here is never compared against one computed by the web, and
 * must not start being, since browsers DO apply EXIF orientation when decoding.)
 *
 * ── WHAT IT COSTS, MEASURED RATHER THAN ASSERTED — AND IT IS NOT ONE NUMBER ───────────────────
 *
 * This exact code path has been dexed and run through `app_process` on Android 15 (API 35, x86_64)
 * over a generated 12 MP 4000x3000 JPEG, twice, on the same machine under different load:
 *
 *   idle machine ....... first call ~1080 ms, then 91-354 ms, most runs 110-180 ms
 *   machine under load . first call 458 ms,   then 279-785 ms, median 448 ms over 11 runs
 *
 * QUOTE THE RANGE AND NOT THE BEST FIGURE. The second run was taken while several Gradle builds were
 * competing for the same cores, which is not a contrived worst case — it is an ordinary field handset
 * that is also running the camera, the sync and whatever else Android decided to wake. The honest
 * budget for this call is therefore "a few hundred milliseconds, sometimes approaching a second", and
 * a real ARM handset is slower again than any x86_64 emulator. That is exactly why it never runs on
 * the main thread: see [DwPhotoQualityAdvisories], which runs it on `Dispatchers.Default`, one
 * photograph at a time, and cancels when the stage leaves the screen. At 785 ms this would be an
 * unmistakable freeze at the moment the designer reaches for the shutter again.
 *
 * The first call in a process is dominated by class loading and JIT warm-up rather than arithmetic,
 * and it happens once, at the first photograph of a session.
 *
 * ── WHAT THE VERDICTS DID ON REAL JPEGs, AND ONE CAVEAT WORTH CARRYING ────────────────────────
 *
 * The same runs exercised the checks end to end on decoded JPEGs rather than synthetic planes: a
 * defocused 12 MP frame was reported (blur 7.99 at contrast 31.27 — under the floor and OVER the
 * contrast guard, so the guard correctly stayed open); a downscale was reported as under-resolution
 * with its real dimensions in the sentence; and the same photograph re-encoded at JPEG quality 40 and
 * half the pixels hashed 0 bits from the original, well inside the near-duplicate threshold of 6.
 *
 * THE CAVEAT: how far a SHARP photograph sits above the floor depends on the subject, not just on
 * focus. The 1/f fields the parity corpus uses — the standard statistical model of a photograph —
 * score 732-806, the wide margin [DwImageQuality.BLUR_VARIANCE_FLOOR] is calibrated against. But a
 * frame whose detail is mostly per-pixel noise over smooth structure averages away under the
 * downscale to [DwImageQuality.WORK_EDGE_PX] and measured 103.34 here: correctly silent, but only
 * 1.7x clear of the floor rather than twelve. Nothing to fix — the verdict was right and defocus
 * still drops it by an order of magnitude — but do not read "sharp photographs score in the hundreds
 * or thousands" as a guarantee with margin to spare, and re-measure rather than assume if this
 * threshold is ever revisited.
 *
 * ── FAILURE IS SILENT AND ALWAYS null ─────────────────────────────────────────────────────────
 *
 * A HEIC the platform decoder will not open, a truncated file, a bitmap the GPU refused, an
 * OutOfMemoryError on a phone that was already at the edge — none of those is the designer's problem
 * and none of them may reach the screen or interrupt the import. A photograph this cannot measure is a
 * photograph with no findings, which is the same thing as a photograph with nothing wrong with it as
 * far as everything downstream is concerned.
 */
object DwImageDecode {

    /**
     * Measure the image at [path], or null if this device cannot.
     *
     * BLOCKING AND CPU-BOUND — call it from a background dispatcher. The decode reads from flash and
     * the convolution runs over ~307k pixels; on the main thread that is a stutter at the exact moment
     * the designer wants to take the next photograph, which is the whole reason the check is worth
     * having.
     */
    fun measure(path: String): ImageMeasurement? = runCatching { measureOrNull(path) }.getOrNull()

    private fun measureOrNull(path: String): ImageMeasurement? {
        val startedAt = System.nanoTime()

        // Header only: `inJustDecodeBounds` parses the dimensions and allocates not one pixel. These
        // are the ORIGINAL dimensions, which is what the resolution check and the report's plate
        // sizing both need — not the size the measurement is taken at.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 1 || height < 1) return null

        val longEdge = max(width, height)
        // The largest power-of-two subsample that still leaves the long edge at or above the working
        // edge, so the box average that follows is always a downscale. Enlarging a decoded plane back
        // up to the working size would invent detail and report a soft photograph as sharp.
        var sample = 1
        while (longEdge / (sample * 2) >= DwImageQuality.WORK_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // ARGB_8888 explicitly. A device defaulting to RGB_565 would quantise every channel to
            // 5-6 bits, which changes the luma plane and therefore the blur score — the measurement
            // would depend on the handset rather than on the photograph.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null

        val decoded = try {
            // Peak here is the bitmap plus one Int per pixel: worst case (a 1279px original, which
            // subsamples by 1) about 4.9 MB + 4.9 MB, against the 48 MB a full-size decode of a 12 MP
            // frame would have cost. The bitmap is released before anything else is allocated.
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            DwImageQuality.greyPlaneFromArgb(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }

        val (workWidth, workHeight) = DwImageQuality.workingSizeFor(width, height)
        val work = if (decoded.width == workWidth && decoded.height == workHeight) {
            decoded
        } else {
            DwImageQuality.resampleGrey(decoded, workWidth, workHeight)
        }

        return ImageMeasurement(
            width = width,
            height = height,
            blurScore = DwImageQuality.laplacianVariance(work),
            contrast = DwImageQuality.contrastStdDev(work),
            perceptualHash = DwImageQuality.differenceHash(work),
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }
}
