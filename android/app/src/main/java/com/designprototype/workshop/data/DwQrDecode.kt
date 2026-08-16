package com.designprototype.workshop.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * ── WHAT THE CAMERA PATH IS, AND WHAT IT DELIBERATELY IS NOT ──────────────────────────────────
 *
 * IT TAKES A PHOTOGRAPH AND DECODES IT. There is no live preview and no frame loop, which means no
 * CameraX (four more artifacts) and no analyser plumbing. That is the same shape
 * `DwIdentityCardControl` already uses for a document held under a lens, on the same handsets, and
 * it works there.
 *
 * The cost is stated rather than hidden: a live scanner reads a code the moment it lines up, while
 * this needs a deliberate shutter press and gives back a refusal a second later if the frame was
 * poor. What buys that back is the LADDER below — a failed read is retried at full resolution before
 * anybody is told anything — and the fact that retaking a photograph costs two seconds. When a
 * measurement of how long designers actually spend on this exists, which is the condition the
 * decision document names, a live preview is the next thing to add and CameraX is what it costs.
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
