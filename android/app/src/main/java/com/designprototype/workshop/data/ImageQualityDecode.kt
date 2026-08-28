package com.designprototype.workshop.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest
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
/**
 * A photograph decoded small enough to hold in memory while somebody marks it up, and the size of the
 * frame it was decoded from.
 *
 * [sourceWidth]/[sourceHeight] are the ORIGINAL frame's dimensions AS DISPLAYED — the EXIF rotation is
 * already applied to both the bitmap and these numbers, so the two can never disagree. They are here
 * only so a surface can tell the designer what it is showing them a reduced copy of; nothing may
 * measure in them, because the pixels that would justify that precision are not on the screen. See
 * [DwImageDecode.decodeForDisplay].
 */
class DwDisplayImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

object DwImageDecode {

    /**
     * The long edge a photograph is decoded down to before it is put on screen to be marked up.
     *
     * A 4000x3000 frame at ARGB_8888 is 48 MB and this app runs on 2 GB handsets, so the full frame is
     * never decoded. 2400 chosen rather than something smaller because this is the working copy a
     * designer AIMS AT: a mark placed on a 600 px preview of a 4000 px photograph cannot be better than
     * ±7 source pixels however careful the person is, and [DwPhotoMeasure]'s error bar would then be
     * quoting a precision the screen never offered. At 2400 the common 4000 px frame subsamples by 2
     * to 2000 px, which is ~6 MB at RGB_565 — see [decodeForDisplay] for why 565 is honest here and
     * would not be in [measure].
     */
    const val DISPLAY_EDGE_PX = 2400

    /**
     * Decode [path] small enough to draw and mark up, with the EXIF rotation applied, or null.
     *
     * ── WHY THIS APPLIES EXIF ORIENTATION WHEN [measure] DELIBERATELY DOES NOT ────────────────
     *
     * The file header's refusal to rotate is about MEASURING: a blur score and a hash are invariant
     * under quarter turns, and rotating there would make the dimensions in a warning disagree with the
     * dimensions in the draft. Neither argument survives here. This bitmap is put on a screen and a
     * person puts their finger on it, so a portrait photograph drawn side-on is not a cosmetic
     * difference — every coordinate the designer marks would be in a frame that does not match what
     * they can see, and the "length" they measured would be of something they never pointed at. So the
     * two paths differ on purpose and neither should be "fixed" to match the other.
     *
     * A measurement taken in the rotated frame is the same measurement: rotation and mirroring are
     * isometries, so every pixel distance is unchanged, and a homography onto a known rectangle
     * absorbs them entirely.
     *
     * ── RGB_565, WHICH WOULD BE WRONG IN [measure] AND IS RIGHT HERE ──────────────────────────
     *
     * [measure] pins ARGB_8888 because quantising the channels changes the luma plane and therefore
     * the blur score — the verdict would depend on the handset. Nothing downstream of this function
     * reads a pixel VALUE at all: `DwPhotoMeasure` is plane geometry over where a person pointed, and
     * these bytes exist only to be looked at. Halving the memory of the one large allocation this
     * feature makes is worth the banding on a photograph nobody is grading.
     *
     * ── THE BITMAP IS NOT RECYCLED BY ITS CALLER, AND MUST NOT BE ─────────────────────────────
     *
     * Compose holds it through an `ImageBitmap` for as long as the frame is on screen, and recycling
     * one that is still being drawn throws "Canvas: trying to use a recycled bitmap" — a crash in a
     * courtyard, in the middle of a stage the designer has not saved. Dropping the reference is enough:
     * one 6 MB bitmap becomes garbage the moment the panel closes or moves to another photograph.
     */
    fun decodeForDisplay(path: String, maxEdgePx: Int = DISPLAY_EDGE_PX): DwDisplayImage? =
        runCatching { decodeForDisplayOrNull(path, maxEdgePx) }.getOrNull()

    private fun decodeForDisplayOrNull(path: String, maxEdgePx: Int): DwDisplayImage? {
        if (maxEdgePx < 1) return null

        // Header only — no pixels allocated. These are the dimensions as STORED; the rotation below
        // decides whether the displayed frame swaps them.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 1 || height < 1) return null

        val longEdge = max(width, height)
        // The smallest power-of-two subsample that brings the long edge AT OR UNDER the ceiling —
        // the opposite direction from [measure], which must stay at or above its working edge so the
        // box average that follows is a downscale. Here there is no later resample: whatever this
        // decodes is what goes on the screen, so overshooting the ceiling would be the 48 MB
        // allocation this function exists to avoid.
        var sample = 1
        while (longEdge / sample > maxEdgePx) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null

        val (rotation, mirrored) = exifTransform(path)
        val oriented = orient(decoded, rotation, mirrored)
        val swapped = rotation == 90 || rotation == 270
        return DwDisplayImage(
            bitmap = oriented,
            sourceWidth = if (swapped) height else width,
            sourceHeight = if (swapped) width else height,
        )
    }

    /**
     * The EXIF orientation as (degrees clockwise, mirrored), matching
     * [WorkshopDraftStore.withImageMetadata] tag for tag.
     *
     * The PLATFORM `android.media.ExifInterface`, and the same eight-case mapping, deliberately: two
     * readings of the same tag that disagree would put the draft's recorded `rotationDegrees` and the
     * frame a designer marked into different orientations, and only one of them can be right.
     */
    private fun exifTransform(path: String): Pair<Int, Boolean> {
        val exif = runCatching { ExifInterface(path) }.getOrNull() ?: return 0 to false
        val orientation = runCatching {
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
        val mirrored = orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
            orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return rotation to mirrored
    }

    /**
     * [source] turned the way the camera held it, or [source] itself when there is nothing to do.
     *
     * A FAILED ROTATION RETURNS THE UNROTATED FRAME rather than null. `createBitmap` allocates a
     * second copy — briefly both exist — and on a handset already at the edge that is where an
     * OutOfMemoryError lands. A sideways photograph a designer can still measure something on beats no
     * photograph at all, and the panel above draws whatever it is given.
     */
    private fun orient(source: Bitmap, rotation: Int, mirrored: Boolean): Bitmap {
        if (rotation == 0 && !mirrored) return source
        val matrix = Matrix()
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        // Mirrored AFTER the rotation, because the EXIF transposes are a flip of the ROTATED frame.
        if (mirrored) matrix.postScale(-1f, 1f)
        val rotated = runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull() ?: return source
        // Safe: createBitmap copied the pixels, so nothing holds the original any more. Skipped when
        // the platform handed back the same instance for a no-op matrix.
        if (rotated !== source) source.recycle()
        return rotated
    }

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

    // ── Screening: measuring a file that has NOT been imported yet ────────────────────────────────

    /**
     * Everything [DwPhotoGate] needs about one candidate photograph, read from bytes this app does
     * not own yet.
     *
     * [sha256] is null when the stream could not be read to the end. Absent is "unknown", NEVER
     * "unique": the gate refuses an exact duplicate and a hash it could not compute must not be
     * allowed to produce a claim in either direction.
     */
    class DwScreenedImage(val measurement: ImageMeasurement, val sha256: String?)

    /**
     * Measure and hash the image behind [uri] WITHOUT importing it, or null if this device cannot.
     *
     * ── WHY THIS EXISTS BESIDE [measure], WHICH TAKES A PATH ──────────────────────────────────
     *
     * [measure] reads a file the workshop already owns — a descriptor's copy under `filesDir`. This
     * one reads a candidate: a `content://` handle from the gallery picker, or the FileProvider Uri
     * the camera just wrote into `captures/`. The distinction is the entire point of the gate.
     * [WorkshopDraftStore.importMedia] copies every byte into the workshop's media directory before
     * it hands back an id, so a check that ran on the imported copy would be judging a photograph
     * that is already in the draft, already counted, already in the set the sync pass walks — and
     * removing it afterwards would mean writing a descriptor and deleting it, on a phone, for every
     * photograph a designer chooses. Measuring the candidate costs three reads of a file that is
     * already on this device and writes nothing at all.
     *
     * ── IT READS, THREE TIMES, AND NEVER WRITES ───────────────────────────────────────────────
     *
     * A content stream is not rewindable, so the passes cannot share one open: the digest drains the
     * whole file, `inJustDecodeBounds` parses the header, and the third decodes the subsampled
     * frame. Three sequential reads of a 3-6 MB JPEG off flash is a few tens of milliseconds against
     * the hundreds the convolution costs — see this file's header for the measured budget, which is
     * why every caller runs this off the main thread.
     *
     * THE HEADER READ COMES FIRST, AND THAT ORDERING IS ABOUT FILES THAT ARE NOT PHOTOGRAPHS AT ALL.
     * `inJustDecodeBounds` allocates no pixels and stops at the first few hundred bytes, so a video,
     * a PDF or an audio recording is refused a reading almost instantly. With the digest first, a 300
     * MB loom video handed to this function would be read end to end — SHA-256 over every byte — only
     * to fail the decode on the next line and produce nothing. That is minutes of flash reads on a
     * field handset to answer a question about a file that was never an image.
     *
     * Nothing downstream loses by it: a file with no bounds has no measurement, and a null
     * [DwScreenedImage] is admitted rather than refused, so the checksum would have had nobody to
     * report to.
     *
     * ── IT RE-USES THE PURE CORE AND RE-IMPLEMENTS NOTHING ────────────────────────────────────
     *
     * Same sample-size rule, same ARGB_8888 pin, same [DwImageQuality.workingSizeFor], same box
     * average. A second decode path with its own arithmetic would be a second opinion about whether
     * a photograph is sharp, and the two would be consulted on the same file — the advisory card
     * measures the imported copy through [measure] moments later. They must agree.
     *
     * ── AND SHA-256, TO MATCH THE ONE THE IMPORT WILL COMPUTE ─────────────────────────────────
     *
     * [WorkshopDraftStore.importMedia] hashes the bytes as it copies them and stores the result on
     * the descriptor. This computes the same digest over the same bytes before the copy, so the
     * value the gate compares against a field's existing attachments is the value that WOULD have
     * been stored — not a different hash of a different thing.
     *
     * FAILURE IS SILENT AND ALWAYS null, exactly as [measure]'s is: a HEIC the platform will not
     * open, a truncated file, a permission that expired between the pick and the read, an
     * OutOfMemoryError. None of those is a bad photograph, and the caller admits what it cannot
     * measure — see [DwPhotoGate]'s header on failing open by construction.
     */
    fun screen(resolver: ContentResolver, uri: Uri): DwScreenedImage? =
        runCatching { screenOrNull(resolver, uri) }.getOrNull()

    private fun screenOrNull(resolver: ContentResolver, uri: Uri): DwScreenedImage? {
        val startedAt = System.nanoTime()

        // Header only, and first: this is what tells a photograph from a video, a PDF or a sound
        // recording, and it does it without allocating a pixel or reading past the first block.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openOrNull(resolver, uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 1 || height < 1) return null

        val sha256 = runCatching { digestOf(resolver, uri) }.getOrNull()

        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= DwImageQuality.WORK_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // ARGB_8888 explicitly, for the reason [measure] pins it: RGB_565 quantises every
            // channel, which changes the luma plane and therefore the blur score, and a REFUSAL that
            // depends on the handset rather than on the photograph is the worst version of this
            // feature there is.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = openOrNull(resolver, uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        val decoded = try {
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

        return DwScreenedImage(
            measurement = ImageMeasurement(
                width = width,
                height = height,
                blurScore = DwImageQuality.laplacianVariance(work),
                contrast = DwImageQuality.contrastStdDev(work),
                perceptualHash = DwImageQuality.differenceHash(work),
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            ),
            sha256 = sha256,
        )
    }

    private fun openOrNull(resolver: ContentResolver, uri: Uri): InputStream? =
        runCatching { resolver.openInputStream(uri) }.getOrNull()

    /**
     * SHA-256 of everything behind [uri], lower-case hex — the same shape [DraftMedia.sha256] holds.
     *
     * Null rather than an exception when the stream cannot be opened or read, because a hash is an
     * input to a duplicate CLAIM: the honest answer to "could not read it" is "unknown", and the
     * gate treats unknown as no claim rather than as a refusal.
     */
    private fun digestOf(resolver: ContentResolver, uri: Uri): String? {
        val stream = openOrNull(resolver, uri) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        stream.use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
