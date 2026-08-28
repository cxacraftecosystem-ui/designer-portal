package com.designprototype.workshop.data

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * WHO READS A LIVE CAMERA FRAME — one seam, two readers, and the reason there are two.
 *
 * ── THE COMPLAINT THAT PUT THIS FILE HERE, 2026-08-27 ─────────────────────────────────────────
 *
 * "QR scan on android devices does not pick up the region of interest and scan while the camera is
 * on." Until this file the live camera was ZXing on a cropped luminance plane, and
 * `app/build.gradle.kts` had already written down, dated and measured, that ML Kit reads a bent,
 * angled or glared code off a live frame better than ZXing does — recorded there as an ACCEPTED
 * REGRESSION. The regression is now the reported defect, so the trade has been re-taken. That build
 * file's argument has been corrected in place rather than deleted; read it for what was traded away
 * and what the replacement costs in bytes.
 *
 * ── WHY BOTH READERS SURVIVE, WHICH IS THE POINT OF THE SEAM ──────────────────────────────────
 *
 * ML Kit cannot run in a JVM unit test. Nothing on this machine can make an accuracy claim about it,
 * exactly as `IdentityCardRecognizer`'s header says of the text recogniser. ZXing is pure Java, and
 * that is why `DwQrLiveFrameTest` can push symbols made by this app's own `DwQrEncode` through the
 * real shipping decoder on the desktop and assert the round trip. DELETING THAT WOULD HAVE TRADED
 * THE ONLY ACCURACY EVIDENCE THIS REPOSITORY CAN PRODUCE for accuracy nobody here can measure.
 *
 * So [DwQrFrameReader] is the seam. [MlKitQrFrameReader] is what the live camera uses.
 * [ReferenceQrFrameReader] is the ZXing one, kept for three jobs it actually does:
 *
 *  1. IT IS THE FALLBACK ON THIS DEVICE. `BarcodeScanning.getClient` can fail, and a frame can
 *     arrive in a format that yields no `android.media.Image`. On either the reference reader takes
 *     the frame, and the scanner SAYS SO on screen rather than quietly reading worse.
 *  2. IT IS THE ONLY THING A BUILD CAN CHECK. Its decoder, its crop arithmetic and its stride
 *     handling are all pure and are all still asserted on every build.
 *  3. ZXING HAS NOT LEFT THE APP. The photograph and picked-picture routes are `ZxingQrImageDecoder`
 *     and are untouched by any of this — see the four doors listed below.
 *
 * ── THE FOUR WAYS A CODE GETS IN, AND WHAT DECODES EACH ONE NOW ───────────────────────────────
 *
 *  * LIVE CAMERA — [MlKitQrFrameReader], bundled model, QR only, whole frame, reticle applied by
 *    refusing a sighting whose centre falls outside the box. [ReferenceQrFrameReader] when ML Kit
 *    cannot be started on this device.
 *  * A PHOTOGRAPH THIS APP TOOK — [ZxingQrImageDecoder] walking [DW_QR_SAMPLE_LADDER]. UNCHANGED.
 *  * A PICTURE THE DESIGNER ALREADY HAD — the same [ZxingQrImageDecoder]. UNCHANGED.
 *  * CHARACTERS TYPED BY HAND — no decoder at all; `decodeWorkshopCode` parses the string. UNCHANGED.
 *
 * All four hand a RAW payload to `decodeWorkshopCode`, which is the one place a code is judged. A
 * payment QR is refused by the same sentence however it arrived, and that has not moved.
 */

/**
 * What one reader made of one frame.
 *
 * THREE OUTCOMES AND NOT A NULLABLE PAYLOAD, because the third one is the whole reason the reticle
 * is trustworthy. A code that was READ and then REFUSED for sitting outside the box is the one new
 * failure this change introduces, and it has the exact shape of the failure the reticle apparatus
 * exists to prevent: a designer can see a code, the app says nothing, and the app looks broken. So
 * it is a case in this type rather than a null, the analyser reports it, and the dialog puts a
 * sentence on screen — see `DW_QR_LIVE_OUTSIDE_BOX` in `ui/DwQrLiveScanner.kt`.
 */
sealed interface DwQrFrameRead {

    /** Nothing in this frame. Ordinary, and the great majority of frames. */
    data object None : DwQrFrameRead

    /** A code was found inside the box. [sighting] carries the RAW payload; nothing judges it here. */
    data class Read(val sighting: DwQrSighting) : DwQrFrameRead

    /** A code was found and its centre was outside the box, so it was not used. */
    data class OutsideTheBox(val sighting: DwQrSighting) : DwQrFrameRead
}

/**
 * One live frame in, one [DwQrFrameRead] out.
 *
 * ── THE CONTRACT ABOUT THE IMAGE, WHICH IS THE PART THAT BITES ────────────────────────────────
 *
 * An implementation MUST be finished with [image] and everything reachable from it by the time
 * [read] returns, and MUST NOT close it. The analyser owns the proxy and closes it in a `finally` on
 * every path — an unclosed `ImageProxy` stalls the CameraX pipeline dead after `imageQueueDepth`
 * frames and presents to a designer as a frozen preview.
 *
 * That contract is why [MlKitQrFrameReader] blocks rather than handing back a callback: the orthodox
 * ML Kit sample closes the proxy inside the task's completion listener, and adopting that here would
 * have moved `image.close()` out of the `finally` and split ownership of the proxy across two
 * threads. Its header has the full argument.
 *
 * [reticle] is the box drawn on screen, as fractions of the viewfinder, or null when the box has not
 * been measured yet. NULL MEANS "read the whole frame", never "guess a rectangle".
 */
interface DwQrFrameReader {

    /** Called on the analyser thread, serially. Must not touch Compose state and must not throw. */
    fun read(image: ImageProxy, reticle: DwQrFraction?): DwQrFrameRead

    /**
     * True once this reader has found it cannot work on this device, so the screen can say so.
     *
     * Read from the main thread while [read] runs on the analyser thread, so it must be answerable
     * without a lock and without blocking.
     */
    val unavailable: Boolean get() = false

    /** Release anything native. Called once, on the scanner dialog's dispose. */
    fun close() {}
}

/**
 * ML KIT ON THE WHOLE FRAME — the live camera's reader.
 *
 * ── BUNDLED MODEL, AND THE UNBUNDLED ONE IS STILL DISQUALIFIED ────────────────────────────────
 *
 * `com.google.mlkit:barcode-scanning` carries its model inside the APK.
 * `com.google.android.gms:play-services-mlkit-barcode-scanning` is a fraction of the size and
 * DOWNLOADS the model from Play Services on first use — and first use is a courtyard that has had no
 * signal for two days, where it fails as "the camera does not read cards". The owner's own words
 * when overturning the ZXing choice were "use it if it guarantees the behaviour", and a model that
 * must be fetched guarantees nothing. It is the same reason `text-recognition` is the bundled one,
 * and `app/build.gradle.kts` carries the measured byte cost of both halves of that choice.
 *
 * ── ROTATION: THE OLD REASONING HAS CHANGED SHAPE RATHER THAN BEEN DROPPED ────────────────────
 *
 * [dwQrCompactLuminance]'s path never rotates the buffer, because rotating a 1280x720 luminance
 * plane per frame is the most expensive line a naive implementation has and ZXing's finder-pattern
 * search is rotation-invariant for QR. THIS READER STILL DOES NOT ROTATE ANYTHING EITHER — it hands
 * ML Kit the buffer and the rotation as two separate facts through `InputImage.fromMediaImage`, and
 * ML Kit does whatever turning it needs inside its own native code, where it is not this app's
 * per-frame cost.
 *
 * What DID change is where the rotation lands in the arithmetic. For ZXing the rotation entered the
 * coordinate map, because the reticle had to be expressed in the unrotated buffer. ML Kit reports
 * bounding boxes in the UPRIGHT frame, so the rotation now enters from the other side: the reticle is
 * expressed in the upright frame by [dwQrReticleInUprightFrame] and the sighting is tested against it
 * there. Two spaces, two functions, both pure, both asserted by `DwQrLiveFrameTest`.
 *
 * ── THE REGION OF INTEREST, WHICH IS THE OWNER'S ACTUAL COMPLAINT ─────────────────────────────
 *
 * ML Kit has no crop parameter. The two honest options were to hand it a cropped image, or to let it
 * read the frame and refuse a result whose box falls outside the reticle. THIS IS THE SECOND, and
 * the reason is the direction of the reported defect: codes inside the box were NOT BEING READ.
 * Cropping is the operation that manufactures exactly that failure — a crop a few pixels tight, or
 * one taken against a `cropRect` a handset filled in differently from the documentation, hides a
 * code that is plainly inside the brackets, and nothing anywhere reports it. Reading the frame and
 * judging afterwards cannot fail in that direction at all, and when it does refuse a sighting it can
 * SAY so, which a crop can never do about a code it never saw.
 *
 * ── WHY THIS BLOCKS THE ANALYSER THREAD ON PURPOSE ────────────────────────────────────────────
 *
 * ML Kit's API is a `Task`, and Google's own CameraX sample closes the `ImageProxy` inside the
 * completion listener. Doing that here would move the close out of the analyser's `finally` and split
 * ownership of the proxy across two threads — the arrangement that produces an unclosed proxy on the
 * one path somebody forgot, which presents as a frozen preview. So [read] waits for the task, and the
 * wait is safe for reasons that are properties of this arrangement rather than hopes:
 *
 *  * The analyser runs on ITS OWN single thread (`DwQrLiveScannerDialog` builds it with
 *    `newSingleThreadExecutor`). The main thread is never blocked by this and never waits on it.
 *  * `STRATEGY_KEEP_ONLY_LATEST` is bound, so frames arriving during the wait are DROPPED rather
 *    than queued. Blocking is what that strategy is for.
 *  * The completion listener is registered against a DIRECT executor, so the wait does not depend on
 *    the main thread being free to deliver the callback.
 *
 * THERE IS NO TIMEOUT, DELIBERATELY. A wait that gave up would return while ML Kit was still reading
 * a buffer the analyser is about to close, which is a use-after-free in native code — worse than any
 * delay. If ML Kit ever did hang the failure is bounded and is SAID: the proxy is never closed,
 * frames stop arriving, and the dialog's existing stall detector puts `DW_QR_LIVE_STALLED` on screen
 * after six seconds.
 */
/**
 * How many inferences in a row must come back unsuccessful before ML Kit is declared unusable here.
 *
 * TEN, WHICH IS A THIRD OF A SECOND OF FRAMES rather than a round number. It has to be well above
 * any transient — a detector recovering from a rotation, a frame arriving mid-teardown — and well
 * below a designer's patience, because the whole point of the count is to reach the fallback and the
 * sentence that explains it while somebody is still holding the card up.
 */
const val DW_QR_MLKIT_FAILURE_RUN: Int = 10

class MlKitQrFrameReader : DwQrFrameReader {

    /**
     * QR ONLY, and the narrowing is the same one both ZXing paths make for the same reason.
     *
     * A detector left at its default looks for every symbology it knows on every frame — a UPC on the
     * next table, the DataMatrix on a courier label, PDF417 on a driving licence. This app prints QR
     * and nothing else, so every one of those is a thing a designer might point a phone at BY
     * MISTAKE, and the honest answer for a courier label is "no QR code was found", not a payload
     * `decodeWorkshopCode` then refuses one step further from the truth. It is also the frame budget:
     * a dozen detectors running on every frame instead of one.
     *
     * IT SAVES NO SPACE, WHICH IS WORTH STATING BECAUSE IT LOOKS AS THOUGH IT SHOULD. Two of the
     * three model files this dependency packages are `oned_*` — one-dimensional barcode models,
     * 490,432 measured bytes of the APK — and they ship whatever this line says, because assets are
     * not stripped by format. `app/build.gradle.kts` has the measured table. Narrowing harder is not
     * a size optimisation and nobody should spend an afternoon discovering that.
     */
    private val scanner: BarcodeScanner? = runCatching {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }.getOrNull()

    /**
     * Set once this reader has proved it cannot work here, so the caller can fall back and say so.
     *
     * TWO KINDS OF EVIDENCE SET IT, and they are different in kind rather than in degree.
     *
     *  * A STRUCTURAL FAILURE, believed on the FIRST occurrence: `getClient` threw, the frame
     *    yields no `android.media.Image`, or `InputImage` cannot be built from it. Each of those is
     *    a fact about the device and the pipeline format rather than a bad moment, and retrying one
     *    thirty times a second would be thirty exceptions a second.
     *  * A RUN OF FAILED INFERENCES, believed only after [DW_QR_MLKIT_FAILURE_RUN] of them. A task
     *    that completes UNSUCCESSFULLY throws nothing and returns nothing, which is
     *    indistinguishable from "no code in this frame" at the call site — so without this counter a
     *    detector that failed on every frame would report a clean, silent nothing for ever and the
     *    fallback would never engage. That is the exact failure this whole change exists to stop,
     *    one library down. One failure is not evidence: it is a frame.
     */
    @Volatile
    private var down: Boolean = scanner == null

    /** Consecutive unsuccessful inferences. Reset by any success, including an empty one. */
    private val failures = AtomicInteger(0)

    override val unavailable: Boolean get() = down

    @ExperimentalGetImage
    override fun read(image: ImageProxy, reticle: DwQrFraction?): DwQrFrameRead {
        val client = scanner ?: return DwQrFrameRead.None
        val media = runCatching { image.image }.getOrNull()
        if (media == null) {
            down = true
            return DwQrFrameRead.None
        }

        val rotation = image.imageInfo.rotationDegrees
        val input = runCatching { InputImage.fromMediaImage(media, rotation) }.getOrNull()
        if (input == null) {
            down = true
            return DwQrFrameRead.None
        }

        val found = AtomicReference<List<Barcode>?>(null)
        val settled = CountDownLatch(1)
        val direct = Executor { it.run() }
        val started = runCatching {
            client.process(input)
                .addOnSuccessListener(direct) { barcodes -> found.set(barcodes) }
                .addOnCompleteListener(direct) { settled.countDown() }
        }.isSuccess
        if (!started) {
            down = true
            return DwQrFrameRead.None
        }
        try {
            settled.await()
        } catch (_: InterruptedException) {
            // The executor is being shut down under us. Restore the flag so the shutdown completes,
            // and drop this frame — but only AFTER the wait, so ML Kit is no longer holding the
            // buffer the caller is about to close.
            Thread.currentThread().interrupt()
            return DwQrFrameRead.None
        }

        val result = found.get()
        if (result == null) {
            // The task completed WITHOUT success. Nothing threw and nothing came back, which at this
            // call site is indistinguishable from an empty frame — hence the counter rather than a
            // verdict. See [down].
            if (failures.incrementAndGet() >= DW_QR_MLKIT_FAILURE_RUN) down = true
            return DwQrFrameRead.None
        }
        failures.set(0)

        val barcodes = result
        if (barcodes.isEmpty()) return DwQrFrameRead.None

        val frameWidth = dwQrUprightWidth(image.width, image.height, rotation)
        val frameHeight = dwQrUprightHeight(image.width, image.height, rotation)
        val box = reticle?.let {
            dwQrReticleInUprightFrame(
                reticle = it,
                displayed = image.displayedRect(),
                rotationDegrees = rotation,
                bufferWidth = image.width,
                bufferHeight = image.height,
            )
        }

        // THE FIRST ACCEPTED SIGHTING WINS, and a refused one is still reported so the dialog can say
        // a code was seen. Two codes in one frame is a real situation — a printed card sheet has many
        // — and the box is what says which of them was meant.
        var refused: DwQrSighting? = null
        for (barcode in barcodes) {
            val text = barcode.rawValue?.takeIf { it.isNotBlank() } ?: continue
            val bounds = barcode.boundingBox?.asCrop()
            val sighting = DwQrSighting(text, bounds)
            if (dwQrSightingInReticle(bounds, frameWidth, frameHeight, box)) return DwQrFrameRead.Read(sighting)
            if (refused == null) refused = sighting
        }
        return refused?.let { DwQrFrameRead.OutsideTheBox(it) } ?: DwQrFrameRead.None
    }

    override fun close() {
        runCatching { scanner?.close() }
    }
}

/**
 * ZXING ON THE CROPPED RETICLE — the reference reader, and the live path's fallback.
 *
 * This is the arrangement that shipped until 2026-08-28 and the code is unchanged: map the drawn box
 * into the unrotated buffer with [dwQrCropInBuffer], copy that region out honouring `rowStride` and
 * `pixelStride` with [dwQrCompactLuminance], and hand it to [DwQrLiveDecoder]. Every line of it is
 * pure below the `ImageProxy`, which is why `DwQrLiveFrameTest` can run the whole chain on a desktop
 * over symbols this app's own encoder produced.
 *
 * IT HONOURS THE RETICLE BY CROPPING, WHICH IS THE OPPOSITE OF WHAT [MlKitQrFrameReader] DOES, and
 * the asymmetry is deliberate rather than an inconsistency left behind. A reader that takes a
 * rectangle should be given one — the binarizer never looks at the courtyard, and a miss costs 33 ms.
 * A reader that takes no rectangle is judged afterwards. Both end in the same place: a code inside
 * the brackets reads, a code outside them does not.
 *
 * IT NEVER ANSWERS [DwQrFrameRead.OutsideTheBox], and that is a property of cropping rather than an
 * omission: it is shown only the box, so anything it reads was inside it.
 */
class ReferenceQrFrameReader : DwQrFrameReader {

    private val decoder = DwQrLiveDecoder()

    override fun read(image: ImageProxy, reticle: DwQrFraction?): DwQrFrameRead {
        val plane = image.planes.firstOrNull() ?: return DwQrFrameRead.None
        val displayed = image.displayedRect()
        val target = reticle?.let { fraction ->
            dwQrCropInBuffer(
                reticle = fraction,
                displayed = displayed,
                rotationDegrees = image.imageInfo.rotationDegrees,
                bufferWidth = image.width,
                bufferHeight = image.height,
            )
        } ?: displayed
        if (target.width <= 0 || target.height <= 0) return DwQrFrameRead.None

        val luminance = decoder.luminanceBuffer(target.width * target.height)
        val compacted = dwQrCompactLuminance(
            source = plane.buffer,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            crop = target,
            into = luminance,
        )
        // FALSE IS NOT AN ERROR. It means this frame's strides did not describe a readable region —
        // a buffer shorter than it claims — and the next frame arrives in 33 ms.
        if (!compacted) return DwQrFrameRead.None

        val text = decoder.decode(luminance, target.width, target.height) ?: return DwQrFrameRead.None
        // The bounds are in UNROTATED BUFFER space, not the upright space [DwQrSighting] documents for
        // ML Kit's boxes. Nothing consumes them on this path — the crop already is the reticle — and
        // they are carried only so a caller can tell a sighting from nothing.
        return DwQrFrameRead.Read(DwQrSighting(text, target))
    }
}

/**
 * The part of this buffer the viewfinder is actually showing, in unrotated buffer pixels.
 *
 * `ImageProxy.getCropRect()` is what CameraX fills in from the bound `ViewPort`, and it DEFAULTS to
 * the whole image when no view port was bound — which is exactly the right fallback, because the
 * reticle fraction is then taken against the whole frame and the preview is showing the whole frame
 * too.
 */
private fun ImageProxy.displayedRect(): DwQrCrop = cropRect.let { rect ->
    DwQrCrop(left = rect.left, top = rect.top, width = rect.width(), height = rect.height())
}

/** An Android rectangle as this app's own, so nothing above this line imports `android.graphics`. */
private fun Rect.asCrop(): DwQrCrop = DwQrCrop(left = left, top = top, width = width(), height = height())
