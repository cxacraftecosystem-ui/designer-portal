package com.designprototype.workshop.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Reading a QR code off a PICTURE — one the camera just took, or one somebody was sent.
 *
 * ── WHY THIS EXISTS NOW, WHEN TWO FILE HEADERS SAY IT DELIBERATELY DOES NOT ───────────────────
 *
 * `WorkshopCodesScreen` and `RecordCodeLookup` both stated, as a decision rather than a gap, that
 * this app does not read a QR at all and that the typed code is the route. That was a defensible
 * reading of `docs/DECISION-qr-scanning-on-android.md` and it has been overtaken by a requirement:
 * every QR surface is to offer the camera AND an image the designer already holds.
 *
 * THE PICKED-IMAGE HALF IS THE ONE THE TYPED BOX CANNOT REPLACE, and it is why the old reasoning
 * does not survive. "Typing is a shorter path" assumes somebody is standing in front of the card. A
 * screenshot forwarded on WhatsApp, a photograph of a tag taken last week, a card printed on a sheet
 * that is now in an office two districts away — in every one of those there is no card to read from,
 * and the app either decodes the picture or the record cannot be opened at all.
 *
 * ── THERE ARE NOW TWO CAMERA PATHS, AND THIS HEADER USED TO SAY THERE WAS ONLY ONE ────────────
 *
 * WHAT THIS SECTION SAID UNTIL 2026-08-24, kept so the reversal is legible rather than mysterious:
 *
 *     "IT TAKES A PHOTOGRAPH AND DECODES IT. There is no live preview and no frame loop, which
 *      means no CameraX (four more artifacts) and no analyser plumbing. … When a measurement of how
 *      long designers actually spend on this exists, which is the condition the decision document
 *      names, a live preview is the next thing to add and CameraX is what it costs."
 *
 * CameraX has been added and a live preview exists — `ui/DwQrLiveScanner.kt`, four artifacts,
 * 2,059,824 measured AAR bytes. What reopened it was NOT the measurement that clause asked for. It
 * was a defect the still path cannot fix at all: `ActivityResultContracts.TakePicture()` hands off
 * to the SYSTEM camera app, which reopens whatever lens it last used, so designers were met by the
 * FRONT camera and the lens cannot be forced through that contract. A live preview bound with an
 * explicit `CameraSelector.DEFAULT_BACK_CAMERA` is chosen by this app, per bind, and cannot drift.
 *
 * THE STILL PATH IS NOT REPLACED AND MUST NOT BE. It is the live scanner's own fallback in four
 * situations, each of which really happens: the camera permission is refused; the handset reports no
 * rear lens; `bindToLifecycle` throws or the camera is held by another app; and the live loop has
 * been looking at a code for twenty seconds without reading it — where a full-resolution still and
 * [DW_QR_SAMPLE_LADDER] are genuinely better than another thirty frames of the same blur. The
 * PICKED-image path is not a fallback for anything and is the one route a typed code cannot replace
 * — see the paragraph above.
 *
 * ── WHAT THE LIVE PATH SHARES WITH THIS ONE, AND THE ONE HINT IT DELIBERATELY DROPS ───────────
 *
 * Same library, same `POSSIBLE_FORMATS = [QR_CODE]` narrowing, same parser afterwards
 * ([decodeWorkshopCode]) — so a payment QR is refused by one sentence however it arrived. The one
 * divergence is TRY_HARDER, which is ON here and OFF on the live path. The reason is in this file's
 * own words below: it "costs milliseconds on a still picture that is already in memory". At thirty
 * frames a second those milliseconds ARE the frame budget, and the next frame is a better retry
 * than a harder look at this one. A still picture gets no next frame, so it keeps the flag.
 *
 * The live path's pure half lives at the bottom of THIS file — [dwQrCropInBuffer],
 * [dwQrCompactLuminance] and [DwQrLiveDecoder] — for the reason the ladder is pure: so it can be
 * asserted on a machine with no handset. `DwQrLiveFrameTest` pushes symbols made by this app's own
 * [DwQrEncode] through the live decoder over a synthetic Y plane whose row stride is deliberately
 * wider than its width, which is the shape a real handset hands over and the one a naive
 * implementation gets wrong.
 *
 * ── THE LADDER, AND WHY A FIRST FAILURE IS NOT AN ANSWER ──────────────────────────────────────
 *
 * A 12-megapixel photograph of a small QR is the case that fails, and it fails in BOTH directions:
 * decoded whole it is 4000 pixels wide and the binarizer is looking at a symbol occupying 4% of it;
 * decoded at a thumbnail it is a grey smudge. So [dwQrSampleLadder] gives the rungs — a bounded
 * pass first, because it is the one that succeeds on an ordinary screenshot in a few milliseconds,
 * then the full-resolution original, then a doubled one for a symbol photographed from too far away.
 * Each rung is a fresh decode of the same file; nothing is cached, because a Bitmap of a field
 * photograph is tens of megabytes on a handset that is also holding Compose and a draft.
 *
 * The ladder is PURE and lives apart from the decoding so it can be asserted without a device; the
 * ZXing call underneath it is exercised for real by `DwQrDecodeTest`, which decodes symbols produced
 * by this application's own [DwQrEncode]. The printer and the reader are checked against each other
 * on every build.
 *
 * ── WHAT IS DECIDED HERE AND WHAT IS DECIDED IN [decodeWorkshopCode] ──────────────────────────
 *
 * This file answers "what text is in this picture". It NEVER answers "what record is that". The
 * grammar, the version gate and the check digit stay in [DwWorkshopCodes], so a code that arrives by
 * camera and one typed by hand go through exactly the same parser — and a payment QR photographed by
 * mistake is refused by the same sentence either way, rather than by a second opinion written here.
 */

/** How the payload reached this app, so a refusal can name the right next move. */
enum class DwQrSource {
    /** A photograph this app just took. Retaking it is cheap and is usually the answer. */
    CAMERA,

    /** A picture the designer already had — a screenshot, a forwarded photograph, a file. */
    PICTURE,
}

/** What came back from looking at a picture. */
sealed interface DwQrReadResult {
    /** One QR was found and it carries [text]. Whether it is OURS is [decodeWorkshopCode]'s question. */
    data class Found(val text: String) : DwQrReadResult

    /** The picture was read and held no QR at all. [message] names the next move. */
    data class NothingFound(val message: String) : DwQrReadResult

    /** The picture itself could not be opened or decoded as an image. Not the card's fault. */
    data class Unreadable(val message: String) : DwQrReadResult
}

/**
 * The seam. Above it everything is pure and tested; below it is a bitmap and a native-ish library.
 *
 * An interface rather than a direct call for the reason [IdentityCardRecognizer] gives: it lets a
 * future instrumented test substitute a canned read, and it keeps the UI free of ZXing types so the
 * control can be reasoned about without them.
 */
interface DwQrImageDecoder {
    /**
     * The QR payload in the picture at [source], or null if the picture holds none.
     *
     * THROWS [DwQrPictureUnreadable] when the FILE could not be turned into an image at all. That
     * distinction is the whole reason this returns a nullable rather than only throwing: "there is
     * no code in this picture" and "this phone cannot open this picture" lead a designer to
     * completely different next moves, and collapsing them tells somebody their card is unreadable
     * when the card was never looked at.
     */
    suspend fun decode(context: Context, source: Uri): String?
}

/** The file is not an image this device can decode — see [DwQrImageDecoder.decode]. */
class DwQrPictureUnreadable(message: String = DW_QR_UNREADABLE_PICTURE) : IllegalStateException(message)

/**
 * How many times, and at what size, one picture is looked at before "no code here" is an answer.
 *
 * Each entry is a `inSampleSize` for [BitmapFactory.Options] — 1 is the original, 2 is half in each
 * dimension, and so on. They are tried IN ORDER and the first success wins.
 *
 * ── THE ORDER IS THE INTERESTING PART AND IT IS NOT "SMALLEST FIRST" ──────────────────────────
 *
 * The first rung is a HALVING, not the original and not a thumbnail. A modern handset photograph is
 * 4000 pixels wide and its QR occupies a few hundred of them; ZXing's grid sampler works fine on
 * that, but the binarizer spends most of its time on the 96% of the frame that is a courtyard. Half
 * size is four times less work and loses nothing for a symbol that is still 200 modules-worth of
 * pixels across. A screenshot — which is the picked-image case and the common one — is 1080 wide and
 * decodes on this rung in a few milliseconds.
 *
 * The second rung is the ORIGINAL, and it is a NECESSITY rather than belt and braces. `DwQrDecodeTest`
 * measures the reader's floor at TWO pixels per module — one pixel per module decodes to null,
 * because the grid sampler has no sub-pixel to find a module's centre in. So halving is safe only
 * while the symbol is at least four pixels per module in the original, which a code filling a
 * reasonable part of the frame clears easily and a code photographed from across a courtyard does
 * not. Rung one destroys exactly those, and rung two is what gets them back. Anybody tempted to drop
 * the un-halved pass should read that test first; the number is recorded nowhere else.
 *
 * The third rung is a halving again but is NOT redundant: `inSampleSize` 4 rescues the opposite
 * failure, a photograph so close and so high-resolution that module edges are noisy and the
 * binarizer sees texture. Going DOWN after going up covers both ends without a resize pass between.
 *
 * There is no upscaling rung. `BitmapFactory` cannot produce one with `inSampleSize`, and a symbol
 * too small to have survived the original is one whose modules are under the measured floor —
 * interpolation invents those pixels rather than recovering them, and a record id decoded from
 * invented pixels would pass its own check digit only by coincidence. The refusal says "fill the
 * frame", which is a thing the designer can actually do.
 *
 * PURE, so the sequence is pinned by a test rather than by somebody photographing cards.
 */
val DW_QR_SAMPLE_LADDER: List<Int> = listOf(2, 1, 4)

/**
 * What to tell somebody whose picture held no QR — which depends on how the picture got here.
 *
 * TWO SENTENCES BECAUSE THEY ARE TWO DIFFERENT NEXT ACTIONS, and one message covering both would
 * send half its readers to do something impossible. A designer at the camera can move closer and
 * press again. A designer who was SENT a screenshot cannot retake it and has no card to point at —
 * their next move is to ask for the code as text, or to type it if the sender's picture shows it
 * printed underneath, which every card this app prints does.
 *
 * Pure and public so the wording is pinned by a JVM test rather than by a screenshot.
 */
fun dwQrNothingFound(source: DwQrSource): String = when (source) {
    DwQrSource.CAMERA ->
        "No QR code was found in that photograph. Fill the frame with the code, hold the phone " +
            "steady and flat to the card, and try again — or type the code printed underneath it."
    DwQrSource.PICTURE ->
        "No QR code could be read in that picture. If it is a screenshot, a crop tight around the " +
            "code often reads when the whole screen does not. Otherwise, type the code printed " +
            "under the QR, or ask whoever sent it for the code as text."
}

/**
 * What to tell somebody whose FILE could not be opened at all.
 *
 * DELIBERATELY DISTINCT FROM "no code was found", and the distinction is the one thing this pair of
 * messages exists for. "This phone cannot open that format" reported as "your card is unreadable"
 * sends a designer to re-photograph a card that was always fine — the same failure the web lane
 * closed on its own decoder, where HEIC got its own refusal for exactly this reason.
 */
const val DW_QR_UNREADABLE_PICTURE =
    "That picture could not be opened on this phone. Some formats a camera or a chat app produces " +
        "(HEIC, for instance) cannot be read here. Take a photograph of the code with this app " +
        "instead, or type the code printed under the QR."

/**
 * ZXing, on a bitmap decoded off the device, walking [DW_QR_SAMPLE_LADDER].
 *
 * QR_CODE ONLY, and that is a narrowing rather than an oversight. This app prints QR and nothing
 * else, and every other symbology ZXing can read is a thing a designer might photograph BY MISTAKE —
 * a shop barcode on the next table, the DataMatrix on a courier label. Decoding one would hand
 * [decodeWorkshopCode] a string it refuses anyway, one step further from the truth: the honest
 * answer for a UPC barcode is "no QR code was found", not "that is not a workshop code".
 *
 * TRY_HARDER is set. It costs milliseconds on a still picture that is already in memory, and it is
 * the flag that finds a rotated or perspective-skewed symbol — which is every photograph of a card
 * lying on a table taken by somebody standing over it.
 */
object ZxingQrImageDecoder : DwQrImageDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    override suspend fun decode(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        // WHETHER THE FILE IS AN IMAGE AT ALL, tracked across the rungs rather than decided on the
        // first one. A rung can fail to produce a bitmap for a reason that is not about the format —
        // an OutOfMemory at `inSampleSize` 1 on a 108-megapixel photograph is the real case — so
        // "not an image" is only true once EVERY rung has failed to open it. Deciding it on rung one
        // would report a perfectly ordinary JPEG as a format this phone cannot read.
        var everOpened = false
        DW_QR_SAMPLE_LADDER.forEach { sample ->
            // A FRESH READ PER RUNG rather than one full-size bitmap resized three times. A 12 MP
            // photograph is ~48 MB as ARGB_8888, and holding one while a stage draft and a Compose
            // tree are also resident is how a mid-range handset kills this process. Decoding at
            // `inSampleSize` costs a re-read of a file that is in the page cache and bounds the peak.
            val bitmap = readBitmap(context, source, sample)
            if (bitmap != null) {
                everOpened = true
                try {
                    val text = decodeBitmap(bitmap)
                    if (text != null) return@withContext text
                } finally {
                    bitmap.recycle()
                }
            }
        }
        if (!everOpened) throw DwQrPictureUnreadable()
        null
    }

    /**
     * The file at [source] as a bitmap, sampled down by [sample].
     *
     * Returns null when the stream cannot be opened or the bytes are not an image this device
     * decodes — which the caller reports as "that picture could not be opened", never as "no code
     * was found". THROWING would be wrong here: a picked file that is not an image is an ordinary
     * mis-tap, not an exceptional condition.
     */
    private fun readBitmap(context: Context, source: Uri, sample: Int): Bitmap? = runCatching {
        context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    // ARGB_8888 explicitly. `BitmapFactory` may hand back HARDWARE or RGB_565 config
                    // depending on the source, and `getPixels` throws on a hardware bitmap — which
                    // would turn every read of a picture the platform happened to decode that way
                    // into "unreadable file" on some handsets and not others.
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = false
                }
            )
        }
    }.getOrNull()

    /** One decode attempt. Null simply means this bitmap holds no QR — an ordinary answer. */
    private fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        // MultiFormatReader is NOT shared between calls. It caches hints and internal readers on the
        // instance, and this can be entered from two surfaces at once (a scan running while a paste
        // is decoded); one per call is a few allocations against a class of bug that would show up
        // as a code decoding to the wrong text under load.
        return runCatching { MultiFormatReader().apply { setHints(hints) }.decode(binary).text }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

/**
 * Read a picture and say what happened, in the vocabulary a screen can render.
 *
 * The one place the three outcomes are told apart, so the two surfaces cannot come to describe the
 * same picture differently. [decoder] is a parameter with a default for the seam's stated reason.
 */
suspend fun dwReadQrPicture(
    context: Context,
    source: Uri,
    from: DwQrSource,
    decoder: DwQrImageDecoder = ZxingQrImageDecoder,
): DwQrReadResult = runCatching { decoder.decode(context, source) }
    .fold(
        onSuccess = { text ->
            if (text.isNullOrBlank()) {
                DwQrReadResult.NothingFound(dwQrNothingFound(from))
            } else {
                DwQrReadResult.Found(text)
            }
        },
        // A THROW IS THE FILE'S FAULT, NEVER THE CARD'S. [DwQrPictureUnreadable] says so explicitly;
        // anything else that escapes — a security exception on a Uri whose grant expired, an
        // OutOfMemory — is equally not something a designer fixes by photographing the card again,
        // so it lands on the same sentence rather than on "no code was found". Cancellation is
        // rethrown, so a screen left mid-scan does not report a failure that did not happen.
        onFailure = { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            DwQrReadResult.Unreadable(error.message?.takeIf { it.isNotBlank() } ?: DW_QR_UNREADABLE_PICTURE)
        }
    )

// ======================================================================================
// THE LIVE PATH — the pure half
// ======================================================================================

/**
 * Reading a QR out of a CAMERA FRAME rather than out of a file: everything below this line.
 *
 * ── WHY IT IS IN THIS FILE AND NOT IN A NEW ONE ───────────────────────────────────────────────
 *
 * It was designed as `data/DwQrFrame.kt` and it is here instead for one reason worth writing down:
 * the wave that added the live scanner owns this file and may not create new files under `data/`.
 * NOTHING ELSE ARGUES FOR THE SPLIT ANYWAY — the still path and the live path are one decoder with
 * two front doors, they share the QR_CODE narrowing and the "hand the raw text to
 * [decodeWorkshopCode] and judge nothing here" rule, and the one place they differ (TRY_HARDER) is
 * an argument that reads better beside the flag it is about than in a second file. If a later wave
 * does split them, move the whole block and keep the header's cross-references.
 *
 * ── PURE, AND WHY THAT IS THE WHOLE POINT ─────────────────────────────────────────────────────
 *
 * Not one line below touches an Android class. `ImageProxy`, `ImageAnalysis` and the lens live in
 * `ui/DwQrLiveScanner.kt`; what arrives here is a [java.nio.ByteBuffer] of luminance, two strides
 * and a rectangle. `IdentityCardRecognizer`'s header states the limit that makes this matter: ML Kit
 * "cannot run in a JVM unit test … every claim about recognition ACCURACY is therefore a hardware
 * claim that has not been made yet". ZXing is pure Java, so the two things a live scanner actually
 * gets wrong — the row stride, and the correspondence between the box drawn on screen and the region
 * the decoder looks at — are both asserted on this machine, on every build, by `DwQrLiveFrameTest`.
 *
 * WHAT IS STILL A HARDWARE CLAIM AND IS NOT MADE ANYWHERE: whether ZXing reads a bent card in
 * courtyard light off a live frame. `build.gradle.kts` records that as the accepted regression
 * against ML Kit and it is unchanged by any of this.
 */

/** A rectangle in whole pixels of a camera buffer. Half-open: [left, left + width). */
data class DwQrCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
    internal val right: Int get() = left + width
    internal val bottom: Int get() = top + height
    internal val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * A rectangle as FRACTIONS of the box a designer is looking at — 0 at the left/top edge, 1 at the
 * right/bottom.
 *
 * Fractions and not pixels, because the box on screen and the buffer under it are two different
 * resolutions and the scanner must never have to know both. See [dwQrCropInBuffer].
 */
data class DwQrFraction(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * How far outside the drawn reticle the decoder still looks, as a fraction of the reticle's own side.
 *
 * NOT DECORATION, AND NOT GENEROSITY EITHER — it is the answer to the one failure in a live scanner
 * that is INVISIBLE. If the region the decoder reads is even slightly smaller than the box drawn on
 * screen, a designer lines a code up perfectly inside a box the app is not looking at, and nothing
 * anywhere reports it: the code simply does not read, which is indistinguishable from a bad card.
 * Every rounding in [dwQrCropInBuffer] is therefore outward and the rectangle is inflated by this
 * much first, so "inside the box" is always inside the crop and never merely nearly inside it.
 *
 * The cost of the margin is the opposite failure and it is bounded and benign: the binarizer sees a
 * little courtyard around the card. 12% of a reticle that is itself a fraction of the frame is
 * nothing beside the whole-frame decode this replaces.
 */
const val DW_QR_RETICLE_MARGIN: Float = 0.12f

/**
 * The smallest crop worth decoding, in pixels on a side.
 *
 * `DwQrDecodeTest` measures the reader's floor at TWO pixels per module and the largest symbol this
 * app prints is version 6 at 41 modules, so 82 pixels is the arithmetic floor. 96 is that with a
 * little room, and below it the honest answer is to decode the whole displayed rectangle instead —
 * slower, never wrong — rather than to hand ZXing a crop it cannot resolve a module in.
 */
private const val DW_QR_MIN_CROP: Int = 96

/**
 * Where in a camera buffer the box drawn on screen actually is.
 *
 * ── THE PROBLEM THIS SOLVES ───────────────────────────────────────────────────────────────────
 *
 * Three coordinate spaces are in play and they are all different. The designer sees a Compose box
 * with a reticle in it. The buffer handed to the analyser is some other resolution entirely
 * ([bufferWidth] × [bufferHeight]). And the part of that buffer the viewfinder is actually SHOWING is
 * [displayed] — `ImageProxy.getCropRect()`, which CameraX fills in from the `ViewPort` the use cases
 * were bound with, so that what the preview shows and what the analyser receives are the same
 * picture. Without that ViewPort those two are allowed to differ and this function would be a guess;
 * `DwQrLiveScanner` binds one for exactly this reason and nothing else.
 *
 * ── WHY NOT THE COORDINATE TRANSFORMER ────────────────────────────────────────────────────────
 *
 * `viewfinder-compose` hands over a surface-to-view `Matrix` and inverting it is the textbook route.
 * It is NOT used, deliberately, and the reason is what happens when it is wrong: the matrix maps into
 * the PREVIEW stream, the analyser reads a DIFFERENT stream, and a mismatch produces a crop in the
 * wrong place that reports nothing at all — the invisible failure again, on a repository with no
 * handset to notice it on. Fractions of the displayed rectangle plus [DW_QR_RETICLE_MARGIN] need no
 * matrix, are exact for a reticle expressed against the same box the viewfinder fills, and are
 * asserted here on the JVM. The transformer is the better answer the day somebody can hold a phone.
 *
 * ── THE ROTATION, WHICH IS THE ONLY GENUINELY SUBTLE PART ─────────────────────────────────────
 *
 * The buffer is NEVER rotated — rotating a 1280×720 luminance plane per frame is the most expensive
 * line in a naive implementation and buys nothing, because ZXing's finder-pattern search is
 * rotation-invariant for QR. So rotation enters HERE and nowhere else: [rotationDegrees] is how far
 * clockwise the buffer is turned to become the picture on screen, and this maps a rectangle back the
 * other way. A centred square reticle comes out the same under all four angles, which is exactly why
 * the test uses an OFF-CENTRE rectangle — a centred one would pass with the rotation ignored.
 *
 * @return the rectangle to decode, or null when the answer cannot be computed honestly. NULL MEANS
 *   "decode the whole displayed rectangle", never "guess" — see [DW_QR_MIN_CROP].
 */
fun dwQrCropInBuffer(
    reticle: DwQrFraction,
    displayed: DwQrCrop,
    rotationDegrees: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    margin: Float = DW_QR_RETICLE_MARGIN,
): DwQrCrop? {
    if (bufferWidth <= 0 || bufferHeight <= 0) return null
    // The displayed rectangle has to be a real part of the buffer. A camera implementation that
    // reported one outside it would otherwise be trusted into an ArrayIndexOutOfBounds two functions
    // later, in a frame callback, on a handset.
    if (displayed.isEmpty) return null
    if (displayed.left < 0 || displayed.top < 0) return null
    if (displayed.right > bufferWidth || displayed.bottom > bufferHeight) return null
    if (rotationDegrees != 0 && rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) return null

    val safeMargin = if (margin.isFinite() && margin >= 0f) margin else 0f
    val inflated = reticle.inflated(safeMargin) ?: return null
    val inBuffer = inflated.unrotated(rotationDegrees)

    // OUTWARD ON EVERY EDGE — floor the near edges, ceil the far ones. Rounding to nearest would put
    // the crop half a pixel inside the drawn box on two edges out of four, which is the whole class
    // of error the margin above exists to make impossible.
    val left = displayed.left + floor(inBuffer.left * displayed.width).toInt()
    val top = displayed.top + floor(inBuffer.top * displayed.height).toInt()
    val right = displayed.left + ceil(inBuffer.right * displayed.width).toInt()
    val bottom = displayed.top + ceil(inBuffer.bottom * displayed.height).toInt()

    val clampedLeft = left.coerceIn(displayed.left, displayed.right)
    val clampedTop = top.coerceIn(displayed.top, displayed.bottom)
    val clampedRight = right.coerceIn(clampedLeft, displayed.right)
    val clampedBottom = bottom.coerceIn(clampedTop, displayed.bottom)

    val width = clampedRight - clampedLeft
    val height = clampedBottom - clampedTop
    if (width < DW_QR_MIN_CROP || height < DW_QR_MIN_CROP) return null
    // A crop that is the whole picture is not worth the arithmetic — say so by answering null and
    // letting the caller read the displayed rectangle it already has.
    if (width >= displayed.width && height >= displayed.height) return null
    return DwQrCrop(clampedLeft, clampedTop, width, height)
}

/** This rectangle grown by [margin] of its own side on every edge, clamped to the unit square. */
private fun DwQrFraction.inflated(margin: Float): DwQrFraction? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
    if (right <= left || bottom <= top) return null
    val growX = (right - left) * margin
    val growY = (bottom - top) * margin
    return DwQrFraction(
        left = (left - growX).coerceIn(0f, 1f),
        top = (top - growY).coerceIn(0f, 1f),
        right = (right + growX).coerceIn(0f, 1f),
        bottom = (bottom + growY).coerceIn(0f, 1f),
    )
}

/**
 * This rectangle, read in SCREEN space, expressed in the UNROTATED buffer's own space.
 *
 * [rotationDegrees] is clockwise, buffer to screen, so this applies the inverse. Derived rather than
 * copied: take a point (u, v) in the buffer's unit square and ask where a clockwise turn puts it.
 *
 *  * 90 degrees — the buffer's top-left corner ends up top-RIGHT, so screen = (1 − v, u), and
 *    inverting that gives u = screenY, v = 1 − screenX.
 *  * 180 degrees — screen = (1 − u, 1 − v), which is its own inverse.
 *  * 270 degrees — the buffer's top-left ends up bottom-LEFT, so screen = (v, 1 − u), inverting to
 *    u = 1 − screenY, v = screenX.
 *
 * The min/max wrappers are not defensive padding: two of the three cases flip an axis, which swaps
 * which of the pair is the smaller number, and a rectangle whose left exceeds its right is the sort
 * of thing that produces a negative width four lines later.
 */
private fun DwQrFraction.unrotated(rotationDegrees: Int): DwQrFraction = when (rotationDegrees) {
    90 -> DwQrFraction(left = top, top = 1f - right, right = bottom, bottom = 1f - left).ordered()
    180 -> DwQrFraction(left = 1f - right, top = 1f - bottom, right = 1f - left, bottom = 1f - top).ordered()
    270 -> DwQrFraction(left = 1f - bottom, top = left, right = 1f - top, bottom = right).ordered()
    else -> this.ordered()
}

private fun DwQrFraction.ordered(): DwQrFraction = DwQrFraction(
    left = min(left, right),
    top = min(top, bottom),
    right = max(left, right),
    bottom = max(top, bottom),
)

/**
 * Copy the luminance of [crop] out of a camera plane into a tightly packed byte array.
 *
 * ── ROW STRIDE IS THE BUG EVERY IMPLEMENTATION OF THIS HAS ────────────────────────────────────
 *
 * `Image.Plane.getRowStride()` is the number of bytes from the start of one row to the start of the
 * next, and it is at least the width — on a great many handsets it is strictly greater, because the
 * capture buffer is padded to an alignment. Treating the plane as tightly packed produces an image
 * sheared a few pixels further to the left on every row, which decodes to NOTHING on those handsets
 * and perfectly on the ones where the stride happens to equal the width. That is the worst shape a
 * defect can have in this repository: correct on the machine of whoever wrote it, silent in a
 * courtyard. `DwQrLiveFrameTest` therefore builds its synthetic plane with a stride WIDER than its
 * width, and would pass with a tightly-packed reader only if that padding were removed.
 *
 * `pixelStride` is 1 on the Y plane of every YUV_420_888 device anybody here has read about, and is
 * honoured anyway — because "every device anybody has read about" is not a claim this machine can
 * check, and the loop costs the same either way.
 *
 * ── ABSOLUTE INDEXING, AND ONE ALLOCATION-FREE BUFFER ─────────────────────────────────────────
 *
 * `ByteBuffer.get(index)` rather than the relative `get()`, so this neither reads nor moves the
 * buffer's position and can be called twice on one frame without a rewind. [into] is supplied by the
 * caller and REUSED across frames: at thirty frames a second a fresh array per frame is a megabyte a
 * second of garbage on a handset that is also holding Compose and a workshop draft.
 *
 * @return false when the arguments do not describe a readable region — a short buffer, a crop
 *   outside it, or an [into] too small. FALSE IS NOT AN ERROR; it is the analyser's cue to skip this
 *   frame, and the next one arrives in 33 ms.
 */
fun dwQrCompactLuminance(
    source: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    crop: DwQrCrop,
    into: ByteArray,
): Boolean {
    if (crop.isEmpty || crop.left < 0 || crop.top < 0) return false
    if (rowStride <= 0 || pixelStride <= 0) return false
    if (into.size < crop.width * crop.height) return false
    // The last byte this would read, computed before reading any of them. A frame whose buffer is
    // shorter than its own strides claim is a frame to drop, not a frame to crash on.
    val lastIndex = (crop.bottom - 1).toLong() * rowStride + (crop.right - 1).toLong() * pixelStride
    if (lastIndex >= source.capacity().toLong()) return false

    var out = 0
    for (row in crop.top until crop.bottom) {
        var index = row * rowStride + crop.left * pixelStride
        var column = 0
        while (column < crop.width) {
            into[out++] = source.get(index)
            index += pixelStride
            column++
        }
    }
    return true
}

/**
 * ZXing on one live frame — QR only, TRY_HARDER off, one reader and no allocation per call.
 *
 * ── A SHARED READER, WHICH THE FILE ABOVE ARGUES AGAINST, AND WHY IT IS RIGHT HERE ────────────
 *
 * `ZxingQrImageDecoder.decodeBitmap` says "MultiFormatReader is NOT shared between calls … this can
 * be entered from two surfaces at once (a scan running while a paste is decoded)". Both halves of
 * that are true of the STILL path and neither is true here. An `ImageAnalysis` analyser is invoked
 * serially on one executor, and one instance of this class belongs to one analyser and outlives no
 * scanner — so there is exactly one caller, and the alternative is thirty reader constructions a
 * second. A CLASS and not an `object` is what keeps that promise: an `object` would be shared across
 * two scanners the day a second one is mounted, which is the very hazard the still path names.
 *
 * ── TRY_HARDER IS OFF, WHICH IS THE ONE PLACE THE TWO PATHS DIVERGE ───────────────────────────
 *
 * The still path's own comment gives the reason to keep it there and to drop it here: it "costs
 * milliseconds on a still picture that is already in memory", and at thirty frames a second those
 * milliseconds are the frame budget. The next frame is the retry, and it is a better one — the hand
 * has moved, the focus has settled, the glare is somewhere else.
 *
 * PURE JAVA ALL THE WAY DOWN, so `DwQrLiveFrameTest` runs the real shipping decoder on the desktop.
 */
class DwQrLiveDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                // The same narrowing the still path makes, for the same reason it gives: every other
                // symbology ZXing can read is something a designer might point a phone at BY MISTAKE,
                // and the honest answer for a courier label is "no QR code", not "not a workshop code".
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            )
        )
    }

    /** One reusable buffer for the compacted crop, grown only when a bigger crop arrives. */
    private var scratch = ByteArray(0)

    /** A byte array of at least [size] bytes, reused between frames. */
    fun luminanceBuffer(size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        if (scratch.size < size) scratch = ByteArray(size)
        return scratch
    }

    /**
     * The QR payload in [luminance], or null — which is the ORDINARY answer, thirty times a second,
     * for every frame that does not happen to contain a code.
     *
     * [luminance] may be longer than `width * height`; only the first `width * height` bytes are
     * read, which is what lets [luminanceBuffer] hand back one oversized array for a whole session.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (luminance.size < width * height) return null
        val source = PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            // No mirroring. The BACK lens is bound explicitly and its frames are not mirrored; a
            // front-lens fallback is not mirrored in the BUFFER either — only the preview is — so
            // flipping here would break the one case it looks like it is for.
            false,
        )
        val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        // `reset()` after every attempt, hit or miss: the reader caches per-image state and this
        // instance is about to be handed the next frame.
        runCatching { reader.reset() }
        return text
    }
}

/**
 * How much of the shorter side of the viewfinder the reticle occupies.
 *
 * 0.72 rather than something smaller, for a reason that is about paper rather than taste: the cards
 * this app prints are cut to 26mm and are read at arm's length, so a small central square would ask a
 * designer to bring a 26mm card within a few centimetres of the lens — inside the minimum focus
 * distance of most of this fleet. A generous box is a box a designer can actually fill IN FOCUS.
 */
const val DW_QR_RETICLE_SIDE_FRACTION = 0.72f

/**
 * The reticle as fractions of the viewfinder box: a centred square on the shorter side.
 *
 * IT LIVES HERE AND NOT IN `ui/DwQrLiveScanner.kt`, WHICH IS WHERE IT IS USED, for one reason: a
 * JVM test must be able to load the class that holds it, and the scanner's file facade carries
 * top-level properties that touch Android types. Beside the crop arithmetic it pairs with, it can
 * be asserted on the desktop — which is the whole point of it being a function at all.
 *
 * PURE, AND THAT IS THE WHOLE REASON IT IS A FUNCTION. This one value is read by exactly two
 * consumers — the Canvas that DRAWS the box and the analyser that CROPS to it — and if they ever
 * disagree the failure is invisible: a designer lines a code up inside a box the app is not looking
 * at, and the code simply does not read. One pure function, one call site per frame, and
 * `DwQrLiveFrameTest` asserts the correspondence end to end.
 *
 * @return null when the box has not been measured yet. The analyser reads that as "no reticle", which
 *   means decode the whole frame — slower, never wrong. It must NEVER mean "guess a rectangle".
 */
fun dwQrReticleFraction(
    boxWidth: Int,
    boxHeight: Int,
    sideFraction: Float = DW_QR_RETICLE_SIDE_FRACTION,
): DwQrFraction? {
    if (boxWidth <= 0 || boxHeight <= 0) return null
    if (!sideFraction.isFinite() || sideFraction <= 0f || sideFraction > 1f) return null
    val side = minOf(boxWidth, boxHeight) * sideFraction
    val halfWidthFraction = (side / 2f) / boxWidth
    val halfHeightFraction = (side / 2f) / boxHeight
    return DwQrFraction(
        left = (0.5f - halfWidthFraction).coerceIn(0f, 1f),
        top = (0.5f - halfHeightFraction).coerceIn(0f, 1f),
        right = (0.5f + halfWidthFraction).coerceIn(0f, 1f),
        bottom = (0.5f + halfHeightFraction).coerceIn(0f, 1f),
    )
}
