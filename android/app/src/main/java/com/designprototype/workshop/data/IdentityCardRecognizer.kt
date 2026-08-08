package com.designprototype.workshop.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Reading an identity card WITHOUT A CONNECTION, on the handset in the designer's hand.
 *
 * ── WHAT CHANGED AND WHY ──────────────────────────────────────────────────────────────────────
 *
 * `docs/DECISION-identity-card-ocr-on-android.md` measured the bundled ML Kit recogniser and
 * recommended against it on APK size. That recommendation was OVERRULED, deliberately and on the
 * record: this application is used in villages where there is no signal for days, and a reader that
 * only works with a connection is a reader that does not work where the work happens. The document
 * keeps its measurements and now also keeps the reversal and the reasoning. The cost is real and
 * measured — see that document for the byte figures from a genuine `assembleRelease`.
 *
 * ── THE SPLIT, AND THE HONEST LIMIT OF THE TEST SUITE ─────────────────────────────────────────
 *
 * [IdentityCardRecognizer] is the seam. Above it, [IdentityCardText] decides what counts as an
 * Aadhaar number and is exercised exhaustively on the JVM. Below it, [MlKitIdentityCardRecognizer]
 * hands a bitmap to a native pipeline and is exercised NOWHERE in this repository — ML Kit cannot run
 * in a JVM unit test, and there is no device and no emulator on the machine this was written on
 * (`adb` is not installed). Every claim about recognition ACCURACY is therefore a hardware claim that
 * has not been made yet. What is claimed here is only that the plumbing is right.
 *
 * ── LATIN ONLY: THE SCRIPT QUESTION, SETTLED ──────────────────────────────────────────────────
 *
 * `com.google.mlkit:text-recognition` (Latin) ships. `com.google.mlkit:text-recognition-devanagari`
 * does NOT, and the reason is not size — it is that the extraction could not use its output.
 *
 * The number is ASCII digits or it is nothing, at three independent layers: [IdentityCardText] scans
 * `'0'..'9'` and never `Char.isDigit`; [ArtisanIdentity.aadhaarError] refuses a non-ASCII digit BY
 * NAME ("remove any letters or symbols"); and the server's `_AADHAAR_RUN` does the same. All three
 * carry the same comment and the same reason — a Devanagari "१२३४५६७८९०१२" stored next to
 * "123456789012" is one artisan recorded as two people, in the column whose entire job is to prevent
 * that. So a Devanagari model would recognise glyphs that this pipeline then discards, at 2,015,832
 * measured bytes for the artifact plus a second inference pass on a mid-range handset while a
 * designer waits.
 *
 * It is also not a new assumption. The server reader has always extracted with an ASCII-digit regex,
 * that path is the one in production today, and it works — so "the number line is printed in Western
 * digits" is already load-bearing here rather than something introduced by this lane.
 *
 * If a card is ever found whose number is printed ONLY in Devanagari, the fix is two parts — the
 * model to see the glyphs AND a transliteration step before [ArtisanIdentity] — and the second part
 * does not exist. Adding the model alone would change no outcome at all.
 */
interface IdentityCardRecognizer {

    /**
     * Every printed row the recogniser found in the photograph at [source].
     *
     * Returns an EMPTY LIST for a photograph with no text in it, and THROWS when the read itself
     * failed — an unreadable file, a model that could not be loaded. The caller treats those
     * differently: nothing found means "try again, fill the frame", while a failure means the local
     * reader is unavailable and the server should be asked if there is a connection.
     */
    suspend fun scan(context: Context, source: Uri): List<IdentityCardText.RecognisedLine>
}

/**
 * The bundled ML Kit Latin recogniser, which needs no connection and no Play Services download.
 *
 * BUNDLED, not `play-services-mlkit-text-recognition`. The unbundled variant fetches its model from
 * Play Services on first use, and first use is precisely the moment a designer is standing in a
 * courtyard that has had no signal for two days — it would fail there, silently, as "the card would
 * not read". That is the failure `docs/DECISION-qr-scanning-on-android.md` already rejected once.
 */
object MlKitIdentityCardRecognizer : IdentityCardRecognizer {

    /**
     * The longest edge the photograph is decoded to before recognition.
     *
     * NOT a quality compromise: ML Kit asks for characters at least 16 px tall, and an Aadhaar number
     * filling most of the frame at 2,200 px across gives each digit something like a hundred pixels.
     * It is a memory rule. The camera on the target handset produces a 64 MP frame; decoded whole at
     * ARGB_8888 that is roughly 256 MB of bitmap, which is an OutOfMemoryError on a 6 GB phone even
     * with `largeHeap` — thrown from inside a Compose callback, with a photograph of somebody's
     * Aadhaar card sitting on disk waiting for the `finally` that the crash skipped.
     */
    private const val MAX_EDGE_PX = 2200

    override suspend fun scan(
        context: Context,
        source: Uri,
    ): List<IdentityCardText.RecognisedLine> = withContext(Dispatchers.IO) {
        val bitmap = decodeBounded(context, source)
            ?: throw IllegalStateException("That photograph could not be opened on this device.")
        // The rotation is handed to ML Kit rather than applied to the bitmap: rotating it here would
        // allocate a second full-size bitmap for no gain, and ML Kit rotates in its own pipeline.
        val image = InputImage.fromBitmap(bitmap, rotationDegrees(context, source))
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = awaitText(client, image)
            text.textBlocks.flatMap { block -> block.lines }.map { line ->
                val box = line.boundingBox
                IdentityCardText.RecognisedLine(
                    text = line.text,
                    top = box?.top ?: 0,
                    bottom = box?.bottom ?: 0,
                )
            }
        } finally {
            // Closed on EVERY path. The recogniser holds a native pipeline; leaking one per photograph
            // is a handset that gets slower the more cards a designer reads in an afternoon, which is
            // exactly the session this feature exists for.
            runCatching { client.close() }
            // Recycled here rather than left to the collector for the same reason MAX_EDGE_PX exists:
            // several reads in a row on a 6 GB phone should not depend on a GC running in time.
            runCatching { bitmap.recycle() }
        }
    }

    /**
     * A Play Services `Task` awaited without pulling in `kotlinx-coroutines-play-services`.
     *
     * That artifact exists and does exactly this; it is not worth a dependency for fifteen lines in a
     * build whose whole subject this week is bytes. `invokeOnCancellation` is deliberately empty of a
     * cancel call — an ML Kit `Task` cannot be cancelled once started, and pretending otherwise would
     * let the caller believe a running inference stopped when it has not.
     */
    private suspend fun awaitText(
        client: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage,
    ): com.google.mlkit.vision.text.Text = suspendCancellableCoroutine { continuation ->
        client.process(image)
            .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    /**
     * Decode the photograph at [source] with its longest edge at or below [MAX_EDGE_PX].
     *
     * Two passes over the stream, because `inJustDecodeBounds` reads the header only and the stream
     * cannot be rewound once consumed — a single reused `InputStream` here silently decodes null,
     * which reads as "that photograph could not be opened" for a file that is perfectly fine.
     *
     * ── THE DEFECT THIS SHAPE REPLACED, WHICH DISABLED THE WHOLE RECOGNISER ───────────────────
     *
     * The measuring pass was written as
     *
     *     context.contentResolver.openInputStream(source)?.use {
     *         BitmapFactory.decodeStream(it, null, bounds)
     *     } ?: return null
     *
     * and the elvis is applied to the value of `use { … }`, not to the stream. `decodeStream`
     * with `inJustDecodeBounds = true` RETURNS NULL BY CONTRACT — that is the entire point of the
     * flag; the answer comes back in `bounds`. So the elvis fired on every single call, for every
     * image, on every device: [decodeBounded] returned null, [scan] threw "That photograph could
     * not be opened on this device.", and `DwIdentityCardControl.send` caught it and fell through
     * to the server. The on-device reader — and the 19.5 MB of bundled model shipped to make it
     * possible — was unreachable in one hundred per cent of reads.
     *
     * NOTHING IN THIS REPOSITORY COULD HAVE CAUGHT IT. `BitmapFactory` is an android.jar stub in a
     * JVM unit test, R8 keeps the code because it IS called, and the failure presents to a designer
     * as "the card would not read" — indistinguishable from a bad photograph, and invisible
     * whenever there is a connection, because the server answers instead. It was found by
     * installing the release build on the Galaxy M32 and watching `dumpsys meminfo`:
     * `libmlkit_google_ocr_pipeline.so` never appeared in `.so mmap`, because no ML Kit class was
     * ever touched.
     *
     * So the null check now covers exactly what it was meant to cover — a content Uri that will not
     * open — and the decode's own answer is read from `bounds`, which is where it lives.
     */
    private fun decodeBounded(context: Context, source: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val header = context.contentResolver.openInputStream(source) ?: return null
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /**
     * How far the photograph has to be turned before the text is the right way up.
     *
     * A card photographed with the phone held normally lands rotated 90° in the file with an EXIF tag
     * saying so; without this the recogniser is handed sideways text and finds nothing at all, which
     * presents to the designer as "the card would not read" on a perfectly good photograph.
     *
     * `android.media.ExifInterface` and not the AndroidX one, because it is on the platform and this
     * lane is already spending seventeen megabytes. Any failure reads as 0°, which is what an image
     * with no EXIF block — every image the system photo picker re-encodes — means anyway.
     */
    private fun rotationDegrees(context: Context, source: Uri): Int = runCatching {
        context.contentResolver.openInputStream(source)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)
}
