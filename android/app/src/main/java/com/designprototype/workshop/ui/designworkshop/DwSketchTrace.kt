package com.designprototype.workshop.ui.designworkshop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **HOW A SCREEN GETS THE TRACER, AND THE TWO THINGS EVERY TRACE NEEDS FROM ANDROID.**
 *
 * ── WHAT USED TO BE HERE, AND WHERE IT WENT ───────────────────────────────────────────────────
 *
 * Until this file was rewritten it held `DwSketchTraceRuntime`: a [DwTraceRuntime] that shipped the
 * vendored engine as a minified JavaScript bundle in `assets/`, started an `androidx.javascriptengine`
 * isolate in the WebView's process, and spoke a base64 envelope protocol to it. That whole route is
 * gone — the class, the 128,026-byte asset, the `DwSketchTraceSandbox.kt` that named the library, the
 * dependency, and the script and CI step that built the bundle. **`DwTraceKotlinRuntime` replaces it**:
 * the same upstream engine, vendored instead as the four `:core-*` Gradle modules and compiled into
 * this APK. That file's header carries the argument for what the move buys (a real mid-trace cancel,
 * no bundle to package, no WebView floor) and what it costs (the working set moves into this app's
 * own Java heap, which is why it refuses a trace it has measured as too large).
 *
 * The interface did not move. `DwSketchTraceEngine.kt` still declares [DwTraceRuntime] and the ~8,000
 * lines of panel above it still compile against nothing else, which is what made this a one-file
 * switch rather than a rewrite.
 *
 * ── SO WHAT IS LEFT HERE IS THE PART THAT IS ANDROID'S ────────────────────────────────────────
 *
 * [rememberDwTraceRuntime] is the mount. [dwTraceDecodeForTrace] turns a file path into pixels, and
 * [dwTracePlateResult] turns a finished trace into the two display plates the comparator draws. Both
 * import `android.graphics`, which is exactly why they are not in `DwTraceKotlinRuntime.kt`: that file
 * is reachable by a JVM unit test and these two are not.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Construction
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The tracer, which every build of this app has and every phone can run.
 *
 * ── THERE IS NOTHING TO PROBE ANY MORE, AND THAT IS THE POINT OF THE PORT ─────────────────────
 *
 * This function used to ask two questions before it could answer — is the bundle in this APK, and
 * will `JavaScriptSandbox.isSupported()` say yes on this WebView — and either one could hand back a
 * runtime that did nothing but explain itself. The second was a real state and not a theoretical one:
 * that check needs an Android System WebView at Chromium M97 or newer, WebView updates arrive through
 * Play, and this product's premise is a handset that has been in a village for a fortnight.
 *
 * Neither question survives the port. The engine is `:core-imaging`, `:core-vector`, `:core-pipeline`
 * and `:core-export`, compiled into the APK by the same Gradle build that compiles this file, so
 * **if this app runs, it traces.** There is no probe here because there is nothing a probe could
 * discover.
 *
 * What CAN still stop one trace is memory, and that is measured per trace against the frame actually
 * being traced rather than answered once at construction — `dwTraceKotlinMemoryRefusal`, which runs
 * after the decode and before the first stage. A ceiling on the resolution is separate again and
 * lives on [DwTraceAvailability], which is now only about how big a trace this phone should attempt.
 *
 * ── NO `DisposableEffect`, BECAUSE THERE IS NO LONGER ANYTHING TO HOLD ────────────────────────
 *
 * The old mount retained a process-wide sandbox connection for as long as the composition, and
 * released and disposed it in an `onDispose` — a service bind in another process, reference-counted,
 * with a delayed close, and an isolate to shut down beside it. A `DwTraceKotlinRuntime` owns no
 * process, no connection and no native handle; it is a small object holding two integers. `remember`
 * keyed on the application context is the whole of its lifetime.
 */
@Composable
fun rememberDwTraceRuntime(): DwTraceRuntime {
    val app = LocalContext.current.applicationContext
    return remember(app) { dwTraceKotlinRuntime(app) }
}

/* ────────────────────────────────────────────────────────────────────────────
 * What Android has to do for a trace, either side of the engine
 *
 * THESE TWO WERE PRIVATE MEMBERS OF THE JAVASCRIPT RUNTIME THIS FILE USED TO HOLD, AND THEY ARE
 * UNCHANGED BY ITS REMOVAL. They were lifted to file scope when the Kotlin runtime landed beside the
 * isolate one and both needed them; the isolate route is now gone and they have exactly one caller,
 * `DwTraceKotlinRuntime`. They stay here rather than moving into that file for the reason the file
 * header gives: they import `android.graphics` and `android.media`, and that file is reachable by a
 * JVM unit test.
 * ──────────────────────────────────────────────────────────────────────────── */


/**
 * The finished result, with the two display plates when they could be made and a sentence when
 * they could not.
 *
 * ── NOTHING IN HERE MAY COST THE DRAWING ──────────────────────────────────────────────────
 *
 * This function used to throw three ways, and each throw was caught at the runtime's `trace`
 * boundary and
 * turned into a whole-run [DwTraceOutcome.Refused] — so a display artefact nobody attaches
 * destroyed the artefact that reaches the ministry. On the device least able to allocate two
 * 1024 px ARGB bitmaps, an out-of-memory in a courtyard threw away a trace that had already
 * finished. The web has always been the other way round: its plates are built in an effect of
 * their own keyed on the result, and a refusal there sets one string and leaves the SVG alone
 * (`comparisonPlates.ts:288-295` and `SketchTraceField.tsx:757-780`).
 *
 * So the three failures are now three sentences carried on [DwTraceResult.plateRefusal], the SVG
 * is returned in every one of them, and the panel prints the sentence where the comparator would
 * have been.
 *
 * ── THE SIZE CHECK STAYS, AND KEEPS ITS BETTER REMEDY ─────────────────────────────────────
 *
 * `comparisonPlates.ts:9-42`'s third decision is that both plates are the same size and *"a
 * mismatch beyond a rounding pixel is a REFUSAL rather than an assumption"*. The one thing that
 * causes a mismatch here is `preprocess.perspectiveCorrect`, which makes the document frame the
 * rectified page rather than the photograph — and on this client that switch is redundant anyway,
 * because the plate handed in has already been straightened by `DwSketchRectify`. So the refusal
 * still names that control by the label the panel shows for it; only what it costs has changed.
 */
internal fun dwTracePlateResult(
    decoded: DwTraceDecoded,
    rgba: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
    frameNote: String,
    request: DwTraceRequest,
): DwTraceResult {
    var tracePlate: Bitmap? = null
    var photographPlate: Bitmap? = null
    var plateRefusal = ""

    if (decoded.width != sourceWidth || decoded.height != sourceHeight) {
        plateRefusal = dwTraceSentence(
            DwTraceFailureKind.FRAME_MISMATCH,
            "${decoded.width}x${decoded.height} from a ${sourceWidth}x$sourceHeight photograph",
        )
    } else {
        val (plateWidth, plateHeight) = dwTraceWorkingSize(
            decoded.width,
            decoded.height,
            request.plateLongEdgePx,
        )
        tracePlate = DwSketchTracePlates.renderTrace(
            geometry = decoded.geometry,
            documentWidth = decoded.width,
            documentHeight = decoded.height,
            plateWidth = plateWidth,
            plateHeight = plateHeight,
        )
        photographPlate = tracePlate?.let {
            DwSketchTracePlates.photographPlate(
                rgba = rgba,
                width = sourceWidth,
                height = sourceHeight,
                plateWidth = plateWidth,
                plateHeight = plateHeight,
            )
        }
        if (tracePlate == null || photographPlate == null) {
            // BOTH OR NEITHER. A comparator with one layer is not a comparator, and the half that
            // did allocate is 4.2 MB held for nothing on a phone that has just proved it is short.
            tracePlate?.recycle()
            photographPlate?.recycle()
            tracePlate = null
            photographPlate = null
            plateRefusal = DW_TRACE_PLATE_MEMORY_REFUSAL
        }
    }

    return DwTraceResult(
        svg = decoded.svg,
        // Carried rather than dropped, because the PNG export paints from it — see
        // [DwTraceResult.geometry] for the arithmetic on what holding it costs. It is the same
        // object the plates above were painted from; nothing is copied here.
        geometry = decoded.geometry,
        tracePlate = tracePlate,
        photographPlate = photographPlate,
        plateRefusal = plateRefusal,
        width = decoded.width,
        height = decoded.height,
        workingWidth = decoded.workingWidth,
        workingHeight = decoded.workingHeight,
        shapeCount = decoded.shapeCount,
        nodeCount = decoded.nodeCount,
        stages = decoded.stages,
        totalMillis = decoded.totalMillis,
        // EVERY SENTENCE THE PIPELINE SAID, IN ORDER AND WITHOUT EXCEPTION, which `Pipeline.kt:124-129`
        // states as a requirement and names the bug it prevents: "a pipeline that silently discarded
        // four thousand paths and one that genuinely found nothing produce the same blank canvas".
        //
        // This used to be two lists concatenated. The first was the JavaScript bundle's own `hello()`
        // notes — a channel for a packaged artefact to say something about itself — and it went when
        // the bundle did. Four compiled Gradle modules have no such channel, and inventing one here
        // would be this host writing sentences the engine did not say.
        notes = decoded.notes,
        appliedParams = decoded.appliedParams,
        autoSubjectId = decoded.autoSubjectId,
        suggestedStyleId = decoded.suggestedStyleId,
        frameNote = frameNote,
    )
}

/* ── decoding the plate ─────────────────────────────────────────────────────────────────── */

/**
 * The photograph as **ARGB_8888**, capped at the web's own decode ceiling, un-rotated.
 *
 * ── ARGB_8888 AND NOT RGB_565, WHICH IS THE OPPOSITE OF WHAT THIS APP USUALLY DOES ────────
 *
 * `DwImageDecode.decodeForDisplay` pins RGB_565 and argues for it: nothing downstream reads a
 * pixel VALUE, so halving the memory of the one large allocation is worth the banding "on a
 * photograph nobody is grading". **Every clause of that argument fails here.** This photograph is
 * about to be graded, pixel by pixel, by Canny's sub-pixel ridge interpolation and a difference
 * of Gaussians. RGB_565 quantises each channel to five or six bits — roughly ±4 luma counts — and
 * the vendored engine's own cross-engine parity budget is 1e-4 of normalised intensity
 * (`engine/edgeFlow.ts:99`, `engine/edgeDog.ts:101`, `engine/contrast.ts:142`). Four counts out of
 * 255 is 1.6%, which is over a hundred times that budget. A handset tracing 565 pixels would
 * produce visibly different line art from the portal tracing the same sheet, which is the one
 * failure this whole feature is disciplined against. So `decodeForDisplay` is deliberately NOT
 * reused, and this is the reason.
 *
 * ── IT APPLIES NO ROTATION, AND REFUSES A FILE THAT WOULD NEED ONE ────────────────────────
 *
 * `decodeToPixels.ts`'s header states the rule this is obeying from the other side: *"a second
 * decoder here would be a second opinion about EXIF orientation."* `DwImageDecode` owns that
 * opinion for this app, in one eight-case mapping that matches `WorkshopDraftStore`'s tag for
 * tag, and writing a ninth case here would be exactly the second opinion both files forbid.
 *
 * So this reads the orientation tag only to REFUSE, which is not an opinion about the transform,
 * and the refusal is the right answer anyway: what stage 11 traces is the straightened plate that
 * `DwSketchRectify` produced and `DwSketchPlate.platePng` wrote, and a plate this app wrote has
 * no EXIF orientation to disagree about. A file that arrives here with a rotation tag is a raw
 * camera frame that has skipped the straightening step, and the sentence says so.
 *
 * ── THE SIZE MATCHES THE WEB'S; THE RESAMPLER DOES NOT, AND THAT IS AN OPEN GAP ───────────
 *
 * [dwTraceWorkingSize] is a line-for-line mirror of `decodeToPixels.workingSizeFor`, so both
 * clients hand the engine a frame of the same dimensions and every coordinate the engine reports
 * is in the same system. **What is NOT the same is the filter**: this uses
 * `Bitmap.createScaledBitmap`, the web uses `createImageBitmap`'s resize, and the two do not
 * produce identical pixels. It binds only above 4096 px, which the rectified plate never reaches
 * (`DwSketchRectify.RECTIFY_MAX_EDGE_PX` is 1600), so on the intended input the cap does nothing
 * at all — but it is a real difference on a path somebody could take, it is the same
 * decoder-parity gap `frontend/e2e/trace-parity-unit.spec.ts` names as deliberately outside its
 * harness, and it is written down here rather than discovered later.
 */
internal suspend fun dwTraceDecodeForTrace(path: String): Bitmap = withContext(Dispatchers.IO) {
    if (path.isBlank() || !runCatching { File(path).isFile }.getOrDefault(false)) {
        throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_UNREADABLE, "no file at that path")
    }
    if (dwTraceRotationTagged(path)) {
        throw DwTraceHostFailure(
            DwTraceFailureKind.IMAGE_UNREADABLE,
            "this photograph carries a rotation the tracer will not guess at — " +
                "straighten the sheet first, and trace the plate that produces",
        )
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val storedWidth = bounds.outWidth
    val storedHeight = bounds.outHeight
    if (storedWidth < 1 || storedHeight < 1) {
        throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_EMPTY, "${storedWidth}x$storedHeight")
    }

    val (targetWidth, targetHeight) = dwTraceWorkingSize(storedWidth, storedHeight)
    // The smallest power-of-two subsample that stays AT OR ABOVE the target, so the exact resize
    // that follows is a downscale. Overshooting downwards here and scaling back up would be
    // upsampling a decode, which invents detail the engine would then trace.
    var sample = 1
    while (maxOf(storedWidth, storedHeight) / (sample * 2) >= maxOf(targetWidth, targetHeight)) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
        ?: throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_UNREADABLE, "the decoder refused it")

    if (decoded.width == targetWidth && decoded.height == targetHeight) return@withContext decoded
    val scaled = runCatching {
        Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
    }.getOrNull()
    if (scaled == null || scaled === decoded) {
        // A failed resize returns the subsampled frame rather than nothing. It is a different
        // size from the web's, which is a parity difference on a path the rectified plate never
        // takes, and it beats refusing a trace a designer is waiting for. `DwImageDecode.orient`
        // makes the same call about a failed rotation, in the same words.
        return@withContext decoded
    }
    decoded.recycle()
    scaled
}

/**
 * Whether [path] claims an orientation other than "upright".
 *
 * Reads ONE tag and compares it, which is not the eight-case mapping `DwImageDecode.exifTransform`
 * owns — see [dwTraceDecodeForTrace]. `ORIENTATION_UNDEFINED` counts as upright: a plate written by this
 * app has no EXIF at all, and treating "the file did not say" as "rotate it somehow" would refuse
 * every trace on the intended input.
 */
private fun dwTraceRotationTagged(path: String): Boolean {
    val exif = runCatching { ExifInterface(path) }.getOrNull() ?: return false
    val orientation = runCatching {
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return orientation != ExifInterface.ORIENTATION_NORMAL &&
        orientation != ExifInterface.ORIENTATION_UNDEFINED
}
