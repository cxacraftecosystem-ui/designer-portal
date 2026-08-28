package com.designprototype.workshop.ui

import android.Manifest
import android.content.Context
import android.util.Rational
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.designprototype.workshop.data.DwQrFraction
import com.designprototype.workshop.data.DwQrFrameRead
import com.designprototype.workshop.data.DwQrFrameReader
import com.designprototype.workshop.data.MlKitQrFrameReader
import com.designprototype.workshop.data.ReferenceQrFrameReader
import com.designprototype.workshop.data.dwQrReticleFraction
import com.designprototype.workshop.ui.designworkshop.hasPermission
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A LIVE QR scanner — the BACK lens, an area-of-interest reticle, a sweep, and a read the moment the
 * code lines up.
 *
 * ── THE DEFECT THIS EXISTS TO FIX, WHICH IS NOT "A LIVE PREVIEW IS NICER" ─────────────────────
 *
 * [DwQrScanControl] photographs a code with `ActivityResultContracts.TakePicture()`, which hands off
 * to the SYSTEM camera app. That app reopens whatever lens IT last used, so designers were met by
 * the front camera pointing at their own face, and there is no argument to that contract that can
 * change it — the lens is not this application's to choose through that door. `bindToLifecycle` with
 * an explicit [CameraSelector.DEFAULT_BACK_CAMERA] is chosen here, on every bind, and cannot drift.
 * Live detection, the reticle and the animation are the requirement; the back lens is the reason it
 * could not be declined.
 *
 * ── WHAT THIS FILE DOES NOT DO, AND THE LIST IS THE IMPORTANT PART ────────────────────────────
 *
 *  * IT DOES NOT JUDGE A CODE. [DwQrLiveScannerDialog]'s `onText` receives the RAW payload. The
 *    grammar, the version gate and the check digit stay in `DwWorkshopCodes.decodeWorkshopCode`,
 *    which is the same parser the typed box uses — [DwQrScanControl]'s stated seam, and the reason a
 *    payment QR photographed by mistake is refused by one sentence however it arrived. A second
 *    opinion written here is how a scanned code and a typed one come to be judged differently.
 *  * IT DOES NOT DECIDE WHO WAS FIRST. An offline scan hands back a payload and a scan TIME beside
 *    it as evidence, and nothing more — see `DwWorkshopJoin.kt`. A scanner that stamped an
 *    authoritative time would be the device-clock spoof the requirement names: server arrival order
 *    decides, and a handset clock is settable by whoever is holding the handset.
 *  * IT INVENTS NO NEW REFUSAL VOCABULARY. Every sentence a refused camera produces comes from
 *    `DwCameraRefusal.kt`, which exists because "copies of a scanner drift, and the half that drifts
 *    is always the refusal wording". There is ONE new sentence here — the camera is unavailable, or
 *    there is no rear lens — and [dwQrCameraUnavailable] says why it is in this file rather than in
 *    that one, and builds it out of that file's own `DwCameraUse.alternatives` so that the clause
 *    which would actually drift cannot.
 *
 * ── WHAT READS THE FRAME CHANGED ON 2026-08-28, AND THIS FILE NO LONGER DECIDES IT ────────────
 *
 * Until then this file cropped each frame to the reticle and ran ZXing on it directly. The owner
 * reported on 2026-08-27 that a code held inside the box was not being read, and
 * `app/build.gradle.kts` had already recorded — dated and measured — that ML Kit reads a bent,
 * angled or glared live frame better than ZXing, as an ACCEPTED REGRESSION. The regression became
 * the defect, so the decoding moved behind a seam: `data/DwQrFrameReader.kt` holds
 * `MlKitQrFrameReader` (the live camera's reader, bundled model, QR only) and
 * `ReferenceQrFrameReader` (the ZXing arrangement that used to live here, kept because it is the
 * only reader a build on a machine with no handset can make an accuracy claim about, and because it
 * is what a device where ML Kit cannot start falls back to).
 *
 * TWO CONSEQUENCES LAND IN THIS FILE AND NOWHERE ELSE. ML Kit has no crop parameter, so the reticle
 * is honoured by REFUSING a sighting whose centre falls outside the box rather than by never showing
 * the reader anything else — and a refusal is therefore something a designer must be told about, so
 * [DW_QR_LIVE_OUTSIDE_BOX] exists. And a device that could not start ML Kit is running the behaviour
 * that was complained about, so [DW_QR_LIVE_REFERENCE_READER] says which of the two it has.
 *
 * ── IT IS A DIALOG AND NOT A NAVIGATION DESTINATION ───────────────────────────────────────────
 *
 * Partly because a scanner is a modal task you return from rather than a place in the app, and partly
 * because the wave that wrote this owns neither `MainActivity.kt` nor `ui/AppNavigation.kt`. Both
 * reasons point the same way, so nothing was traded for the other.
 *
 * ── WHERE IT IS MOUNTED, AND WHERE IT OUGHT TO BE ─────────────────────────────────────────────
 *
 * Mounted from `ui/designworkshop/WorkshopCodesScreen.kt` and `ui/RecordCodeLookup.kt` as
 * [DwQrLiveScanControl], immediately ABOVE the existing [DwQrScanControl] — so the photograph and
 * the picked-picture routes stay visible at all times and completely unchanged, and neither surface
 * loses a door. THE RIGHT FINAL SHAPE IS ONE MOUNT INSIDE [DwQrScanControl], where the third surface
 * (`DwReferenceField.DwReferenceScanPanel`) would get it too and there would be one control again.
 * That file is not this wave's to edit; the mount is a single call and is deliberately left as the
 * next wave's one-line change rather than forked here.
 */

/**
 * WHAT TO SAY WHEN THE LENS ITSELF IS UNAVAILABLE — a refusal with no precedent in this app.
 *
 * THREE SITUATIONS, TWO SENTENCES, and they are one situation from the designer's side: there is no
 * usable camera. `bindToLifecycle` throws `IllegalArgumentException` when no camera matches the
 * selector; the camera can be held by another application; and a tablet in this fleet's price bracket
 * can genuinely have no rear lens at all. None of them is a refused permission, so none of them may
 * borrow that wording — pressing "Camera settings" would do nothing whatsoever for any of them.
 *
 * ⚠ THIS BELONGS IN `DwCameraRefusal.kt` AND IS HERE ONLY BECAUSE OF FILE OWNERSHIP. That file
 * exists to stop exactly this kind of sentence being written twice, its header says so, and the wave
 * that added the live scanner may not edit it. MOVE THIS FUNCTION THERE at the first opportunity.
 * What keeps the drift bounded in the meantime is that the clause which would actually drift — the
 * camera-free routes — is not retyped here. It is read out of [DwCameraUse.QR_CODE] itself, so a
 * change to those routes changes this sentence with it.
 *
 * @param rearLensMissing true when the provider reported no back camera, which is a permanent fact
 *   about the device and reads completely differently from "try again in a moment".
 */
fun dwQrCameraUnavailable(rearLensMissing: Boolean): String = if (rearLensMissing) {
    "This device has no rear camera, so a code cannot be scanned live. Everything else still " +
        "works: " + DwCameraUse.QR_CODE.alternatives
} else {
    "The camera could not be opened — another app may be using it. Close that app and try again, " +
        "or carry on without the camera: " + DwCameraUse.QR_CODE.alternatives
}

/**
 * The sentence under the reticle while nothing has been read yet.
 *
 * It says what to DO and never how the scanner works. Pure and public so `DwQrLiveScannerTest` pins
 * it, on the same terms as every other sentence in this feature.
 */
const val DW_QR_LIVE_AIMING = "Hold the code inside the box. It reads on its own."

/**
 * Said when the back lens is missing and the front one has been bound instead.
 *
 * SAID AND NOT SUBSTITUTED SILENTLY. A designer holding a card against the back of a device that is
 * watching the front would otherwise conclude the scanner is broken, which is the correct conclusion
 * from what they can see and the wrong one about the app.
 */
const val DW_QR_LIVE_FRONT_LENS =
    "This device has no rear camera, so the front one is being used — hold the code facing the screen."

/**
 * The sentence offered after a long look with nothing read.
 *
 * IT NAMES THE TWO BUTTONS BY LABEL AND NOT BY POSITION, which is `dwCameraRefusal`'s own rule and
 * for its own reason: this dialog is mounted from two screens that lay their panels out differently,
 * and a sentence pointing at a position goes wrong the first time a caller moves something. The
 * labels are [DwQrScanControl]'s and are quoted from the screen the designer is returning to.
 *
 * WHY IT SENDS THEM TO A PHOTOGRAPH RATHER THAN SAYING "TRY AGAIN": the still path decodes at FULL
 * resolution and walks `DW_QR_SAMPLE_LADDER`, which is genuinely better than another thirty frames of
 * the same blur on a small, dim or distant code. It is a different tool, not a retry.
 */
const val DW_QR_LIVE_STILL_TRYING =
    "Still not reading? Close this and press “Scan a code” to take a photograph instead — a " +
        "photograph is decoded at full resolution, which often reads a small or dim code the live " +
        "view cannot. “Use a picture” reads a screenshot or a photograph you were sent."

/** Said when frames have stopped arriving, so a stalled pipeline does not read as a bad card. */
const val DW_QR_LIVE_STALLED =
    "The camera has stopped sending pictures. Close this and open it again."

/**
 * Said when a code WAS read and was then refused for sitting outside the box.
 *
 * ── THIS SENTENCE IS THE PRICE OF READING THE WHOLE FRAME, AND IT IS WORTH PAYING ─────────────
 *
 * The reader is given the whole picture and a sighting is judged afterwards, because the defect
 * reported on 2026-08-27 was codes INSIDE the box not reading, and cropping is what manufactures
 * that. The cost of reading everything is that a code the designer did not aim at can be seen, and
 * refusing it silently would produce the identical symptom from the opposite direction: a designer
 * looking at a code, and an app that says nothing. So the refusal is said, and it says the one thing
 * that fixes it.
 *
 * IT NAMES THE BOX AND NOT A DIRECTION. Which way to move depends on where the code is, which this
 * sentence does not know and a designer holding the phone can see.
 */
const val DW_QR_LIVE_OUTSIDE_BOX =
    "A code was seen, but outside the box — line it up inside the brackets to read it."

/**
 * Said when this device could not start the better reader and the simpler one is carrying the scan.
 *
 * ── SAID, RATHER THAN DEGRADED IN SILENCE, WHICH IS THIS FEATURE'S WHOLE DISCIPLINE ───────────
 *
 * `MlKitQrFrameReader` is the live path; `ReferenceQrFrameReader` is ZXing and is measurably worse on
 * a bent, angled or glared frame — `app/build.gradle.kts` records that as the trade this change
 * reversed. A device where ML Kit cannot start therefore has the OLD behaviour, which is the
 * behaviour that was complained about, and a designer meeting it deserves to be told which of the
 * two things is happening rather than concluding the fix never landed.
 *
 * IT SENDS THEM TO THE PHOTOGRAPH, on [DW_QR_LIVE_STILL_TRYING]'s reasoning and not on a hunch: the
 * still path decodes at full resolution and walks `DW_QR_SAMPLE_LADDER`, which is a genuinely
 * different tool rather than a retry.
 */
const val DW_QR_LIVE_REFERENCE_READER =
    "This device could not start the better code reader, so a simpler one is being used — it needs " +
        "the code flatter, steadier and better lit. If it will not read, close this and press " +
        "“Scan a code” to take a photograph instead."

/** How long a refused sighting stays on screen after the last one, so it does not flicker. */
private const val DW_QR_LIVE_OUTSIDE_BOX_MS = 1_500L

/** How long a fruitless look lasts before [DW_QR_LIVE_STILL_TRYING] is offered. */
private const val DW_QR_LIVE_PATIENCE_MS = 20_000L

/** How long without a frame counts as a stall rather than as a slow start. */
private const val DW_QR_LIVE_STALL_MS = 6_000L

/** How often the main thread looks for a hit, a stall or a spent patience timer. */
private const val DW_QR_LIVE_POLL_MS = 200L

/**
 * How far the measured box must move before the camera is re-bound.
 *
 * A window inset settling by a pixel must not tear down and rebuild a camera session, which on a
 * mid-range handset is most of a second of black frames. 24 pixels is under a tenth of the reticle's
 * own side at any screen size this app runs on, so a change small enough to be ignored here cannot
 * move the reticle anywhere a designer would notice.
 */
private const val DW_QR_REBIND_SLOP_PX = 24

/**
 * The analysis resolution asked for.
 *
 * 1280×720 and not the highest available. At a 720-pixel short side a reticle covering 72% of it is
 * ~518 pixels, and the largest symbol this app prints (version 6, 41 modules) is then 12 pixels per
 * module — six times `DwQrDecodeTest`'s measured two-pixel floor. Asking for more would cost the
 * compaction loop and the binarizer real milliseconds per frame for headroom nothing uses.
 *
 * THAT ARITHMETIC IS THE REFERENCE READER'S AND IS UNCHANGED; ML KIT'S IS NOT DERIVED HERE, AND THAT
 * IS AN HONEST GAP RATHER THAN AN OVERSIGHT (2026-08-28). Nothing on this machine can measure how
 * many pixels per module ML Kit needs — it cannot run in a JVM test — so no number is invented for
 * it. What can be said is that 1280×720 is the resolution the shipping fallback was sized for and is
 * comfortably above the floor of the only reader anybody here can measure. If a handset ever reads
 * worse than the reference reader on the same card, this constant is the first thing to raise, and
 * the cost of raising it is now ML Kit's inference time rather than a compaction loop.
 *
 * A FUNCTION AND NOT A TOP-LEVEL `val`, WHICH IS ABOUT TESTABILITY AND NOT STYLE. A top-level
 * property runs in this file's facade-class initialiser, so merely calling
 * [dwQrCameraUnavailable] from a JVM test would construct an `android.util.Size` against the
 * stub `android.jar` — and the sentences in this file are exactly the kind of claim a machine with
 * no handset CAN check. Kept as a function, the facade holds nothing but compile-time constants and
 * `DwQrLiveScannerTest` can load it.
 */
private fun dwQrAnalysisSize(): android.util.Size = android.util.Size(1280, 720)

/**
 * The camera button, the way out when Android has stopped asking, and the dialog it opens.
 *
 * The permission dance is [DwQrScanControl]'s, step for step and deliberately not improved on:
 * granted opens the scanner; denied reads [dwCameraBlocked] INSIDE the callback — the only place it
 * can be read honestly — and hands [dwCameraRefusal] to the host; blocked additionally offers
 * Android's own permission page, which is the only place a blocked permission can be undone.
 *
 * @param onText the RAW decoded payload. See the file header on why nothing here judges it.
 * @param onRefusal a sentence already written for the person reading it; show it as given.
 */
@Composable
fun DwQrLiveScanControl(
    enabled: Boolean,
    onText: (String) -> Unit,
    onRefusal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    /**
     * Android has stopped asking for the camera, so a way out is offered.
     *
     * Set only from inside the permission callback, and cleared the moment the permission is found
     * granted again — so a designer who goes to Settings, turns it on and comes back is not left
     * looking at a button for a problem they have already fixed.
     */
    var cameraBlocked by remember { mutableStateOf(false) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraBlocked = false
            scanning = true
        } else {
            // READ HERE AND NOWHERE ELSE — inside this callback the prompt has by definition just
            // been made, which is the only state in which `shouldShowRequestPermissionRationale`
            // separates "denied once" from "Android has stopped asking".
            val blocked = dwCameraBlocked(context)
            cameraBlocked = blocked
            onRefusal(dwCameraRefusal(DwCameraUse.QR_CODE, blocked))
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    if (hasPermission(context, Manifest.permission.CAMERA)) {
                        cameraBlocked = false
                        scanning = true
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = enabled,
                // The 48dp floor this app applies wherever a control was thought about — see
                // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt.
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan with the camera", fontSize = 13.sp)
            }
            /*
             * Offered when and only when Android has stopped asking, on `DwQrScanControl`'s own
             * reasoning: before that the button beside it IS the way forward, and a settings button
             * would send a designer through a system screen for a prompt they could have answered in
             * place. Not gated on `enabled` — a blocked permission is worth fixing at any time, and
             * this button neither reads nor writes a code.
             */
            if (cameraBlocked) {
                OutlinedButton(
                    onClick = { context.dwOpenAppPermissionSettings() },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(DW_CAMERA_SETTINGS_BUTTON, fontSize = 13.sp)
                }
            }
        }
        Text(
            "The rear camera, with a box to line the code up in — it reads as soon as it lines up. " +
                "The buttons below still take a photograph or read a picture you were sent, and " +
                "none of the three needs a connection.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }

    if (scanning) {
        DwQrLiveScannerDialog(
            onText = { text ->
                scanning = false
                onText(text)
            },
            onRefusal = { message ->
                scanning = false
                onRefusal(message)
            },
            onDismiss = { scanning = false },
        )
    }
}

/**
 * The full-bleed scanner itself.
 *
 * ── THE ONE THING IN HERE THAT FAILS INVISIBLY ────────────────────────────────────────────────
 *
 * The rectangle drawn on screen and the rectangle the decoder reads must be the same rectangle. If
 * they are not, a designer lines a code up perfectly inside a box the app is not looking at and
 * NOTHING REPORTS IT — the code just does not read, which is indistinguishable from a bad card.
 * Three things hold them together and each is deliberate:
 *
 *  1. ONE VALUE. [dwQrReticleFraction] is computed once per box size and is both drawn and cropped
 *     to. There is no second expression of "where the box is".
 *  2. A [ViewPort] built from the measured box's own aspect ratio is bound WITH the use cases, so
 *     `ImageProxy.cropRect` is the region the viewfinder is showing. Without it the preview stream
 *     and the analysis stream may frame differently and the correspondence is a guess.
 *  3. `contentScale = ContentScale.Crop` and `alignment = Alignment.Center` are passed EXPLICITLY to
 *     match `ViewPort.FILL_CENTER`, rather than relying on the composable's defaults. With the
 *     view-port ratio equal to the box's, Crop and Fit coincide — which is the point: it is stated
 *     rather than left to a default that could move in a version bump.
 *
 * The arithmetic between (1) and (2) is `dwQrCropInBuffer` for the reference reader and
 * `dwQrReticleInUprightFrame` for ML Kit — two functions because they answer in two different
 * spaces, both pure, both asserted by `DwQrLiveFrameTest` on this machine. What is NOT asserted
 * anywhere is whether CameraX fills `cropRect` on a real handset the way its documentation says;
 * that is a hardware claim this repository cannot make.
 *
 * THAT UNVERIFIABLE CLAIM IS WHY THE TWO READERS TREAT IT DIFFERENTLY, AND IT IS THE HEART OF THE
 * 2026-08-28 FIX. A cropping reader that is handed a wrong `cropRect` looks at the wrong part of the
 * picture and reports nothing, silently — the invisible failure, and the reported defect. A reader
 * given the WHOLE frame reads the code regardless, and a wrong `cropRect` can then only misplace the
 * acceptance box, which produces [DW_QR_LIVE_OUTSIDE_BOX] on screen instead of silence. The same
 * unknown fact fails loudly on one path and quietly on the other, and the live camera is now on the
 * loud one.
 */
@Composable
fun DwQrLiveScannerDialog(
    onText: (String) -> Unit,
    onRefusal: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val reduceMotion = LocalAppPreferences.current.reducedMotion

    /**
     * The reticle, written by the composition and read by the analyser thread.
     *
     * An [AtomicReference] and not Compose state, because the reader is not the composition: the
     * analyser runs on its own executor, and reading a snapshot state object from a background
     * thread is the sort of thing that works until it does not.
     */
    val reticleSink = remember { AtomicReference<DwQrFraction?>(null) }
    /** Where the analyser leaves a hit. Read back on the main thread by the poll loop. */
    val hitSink = remember { AtomicReference<String?>(null) }
    /** The bound camera, so the torch button and the unbind can reach it from outside the bind. */
    val cameraSink = remember { AtomicReference<Camera?>(null) }
    /** The provider, so `unbindAll` runs on dispose even if the bind effect was cancelled. */
    val providerSink = remember { AtomicReference<ProcessCameraProvider?>(null) }
    /**
     * The analysis use case, held ONLY so the analyser can be detached before the executor goes.
     *
     * Without it the teardown has a real race rather than a theoretical one: `unbindAll` does not
     * synchronously guarantee that no frame is already on its way to `setAnalyzer`'s executor, and a
     * task submitted to an executor that has just been shut down is a `RejectedExecutionException`
     * thrown on one of CameraX's own threads. `clearAnalyzer()` first is the documented way to stop
     * the callbacks; then the unbind; then the shutdown.
     */
    val analysisSink = remember { AtomicReference<ImageAnalysis?>(null) }
    /** `System.currentTimeMillis` of the last frame, and how many have arrived. */
    val lastFrameAt = remember { AtomicLong(0L) }
    val framesSeen = remember { AtomicLong(0L) }
    /** When a code was last seen and refused for sitting outside the box. Zero means never. */
    val outsideBoxAt = remember { AtomicLong(0L) }

    /**
     * THE TWO READERS, BOTH BUILT ONCE PER SCANNER AND NEITHER SHARED ACROSS TWO.
     *
     * `remember` and not a top-level singleton, for the reason `DwQrLiveDecoder`'s own header gives
     * about being a class rather than an object: one reader belongs to one analyser, which is what
     * makes its single-threaded use a fact rather than a hope the day a second scanner is mounted.
     *
     * BOTH ARE BUILT EVEN THOUGH ONLY ONE USUALLY READS. `ReferenceQrFrameReader` costs one
     * `MultiFormatReader` and a zero-length byte array, and building it lazily inside the analyser
     * would put an allocation on the frame that has just discovered ML Kit is unusable — the one
     * frame least able to afford it.
     */
    val mlKitReader = remember { MlKitQrFrameReader() }
    val referenceReader = remember { ReferenceQrFrameReader() }
    /**
     * Whether the reference reader is carrying the scan, so the screen can say so.
     *
     * Compose state written by the main-thread poll loop from `DwQrFrameReader.unavailable`, rather
     * than by the analyser: the flag is a fact about the device that settles on the first frame or
     * two, and reading it once every 200 ms costs nothing against a volatile boolean.
     */
    var referenceReaderInUse by remember { mutableStateOf(false) }
    var sawOutsideBox by remember { mutableStateOf(false) }

    /**
     * ONE thread for the analyser, and it now carries THREE loads rather than one.
     *
     * `newSingleThreadExecutor` and not a pool, because:
     *
     *  1. `DwQrLiveDecoder`'s shared `MultiFormatReader` is not thread-safe, and two frames decoded
     *     at once through one reader is a bug that shows up as a code decoding to the WRONG text
     *     under load — the still path's own comment says so at length.
     *  2. `MlKitQrFrameReader.read` BLOCKS this thread while an inference runs. On a pool that would
     *     be several inferences in flight over several frames, which is throughput nobody asked for
     *     and memory a mid-range handset does not have; here it simply means the next frames are
     *     dropped by `STRATEGY_KEEP_ONLY_LATEST`, which is that strategy's entire purpose.
     *  3. It is what makes the dispose ordering below EXACT rather than probable: the readers' close
     *     is queued as a task and cannot start until the frame in flight has finished.
     */
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var frontLensFallback by remember { mutableStateOf(false) }
    var hit by remember { mutableStateOf<String?>(null) }
    /** Bumped every time a camera binds, so the torch observer re-attaches to the new one. */
    var cameraGeneration by remember { mutableIntStateOf(0) }
    var patienceSpent by remember { mutableStateOf(false) }
    var stalled by remember { mutableStateOf(false) }

    val reticle = remember(boxSize) { dwQrReticleFraction(boxSize.width, boxSize.height) }
    // Published to the analyser as a plain effect rather than inside the draw pass: the draw pass runs
    // on every frame of the sweep animation and this value changes only when the box is re-measured.
    LaunchedEffect(reticle) { reticleSink.set(reticle) }

    /**
     * The camera, bound once the box has been measured.
     *
     * KEYED ON THE BOX AND THE ORIENTATION, because the [ViewPort] is built from both: a rotation
     * changes which of the box's sides is the long one, and a view port built for the old one would
     * frame the analysis stream differently from the preview — silently, which is the failure mode
     * this whole section is written against. `boxSize` is quantised by [DW_QR_REBIND_SLOP_PX] so an
     * inset settling by a pixel does not tear the session down.
     */
    LaunchedEffect(boxSize, configuration.orientation) {
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull()
        if (provider == null) {
            onRefusal(dwQrCameraUnavailable(rearLensMissing = false))
            return@LaunchedEffect
        }
        providerSink.set(provider)

        val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        if (!hasBack && !hasFront) {
            onRefusal(dwQrCameraUnavailable(rearLensMissing = true))
            return@LaunchedEffect
        }
        // THE HONEST FALLBACK. A tablet with only a front lens exists in this fleet's price bracket,
        // and a scanner that silently bound nothing reads as a broken camera. It is SAID on screen
        // rather than quietly substituted — see [DW_QR_LIVE_FRONT_LENS].
        val selector = if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        frontLensFallback = !hasBack

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        val analysis = ImageAnalysis.Builder()
            // KEEP_ONLY_LATEST and not BLOCK_PRODUCER: a decode that outlasts a frame interval must
            // DROP frames rather than build a queue, or the preview lags behind the hand holding the
            // card and lining a code up becomes guesswork.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            dwQrAnalysisSize(),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            // The output format is left at its YUV_420_888 default DELIBERATELY. RGBA_8888 would have
            // CameraX convert every frame and then this code would throw the colour away — the
            // decoder reads luminance, which is plane 0 of YUV and is nothing at all in RGBA.
            .build()
            .also { analysisSink.set(it) }
            .apply {
                setAnalyzer(
                    analysisExecutor,
                    DwQrFrameAnalyzer(
                        primary = mlKitReader,
                        reference = referenceReader,
                        reticle = { reticleSink.get() },
                        onFrame = { at ->
                            lastFrameAt.set(at)
                            framesSeen.incrementAndGet()
                        },
                        onOutsideTheBox = { at -> outsideBoxAt.set(at) },
                        onDecoded = { text -> hitSink.compareAndSet(null, text) },
                    ),
                )
            }

        val viewPort = ViewPort.Builder(
            Rational(boxSize.width, boxSize.height),
            dwDisplayRotation(context),
        )
            // FILL_CENTER, matched by `ContentScale.Crop` on the viewfinder below. The pair is what
            // makes `cropRect` and the drawn reticle describe one rectangle.
            .setScaleType(ViewPort.FILL_CENTER)
            .build()

        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(analysis)
            .build()

        val camera = runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, group)
        }.getOrNull()
        if (camera == null) {
            // `bindToLifecycle` throws IllegalArgumentException for a selector nothing matches, and
            // the camera can simply be held by another app. Neither is a refused permission and
            // neither may borrow that wording — see [dwQrCameraUnavailable].
            onRefusal(dwQrCameraUnavailable(rearLensMissing = false))
            return@LaunchedEffect
        }
        torchAvailable = runCatching { camera.cameraInfo.hasFlashUnit() }.getOrDefault(false)
        cameraSink.set(camera)
        cameraGeneration++
    }

    DisposableEffect(Unit) {
        onDispose {
            // THE ORDER IS THE WHOLE CONTENT OF THIS BLOCK.
            //
            //  1. `clearAnalyzer()` — stop frames reaching the executor at all.
            //  2. `unbindAll()` — release the camera. ALWAYS, and not left to the lifecycle, because
            //     "eventually" here means a torch left burning and a camera the next app cannot open.
            //  3. QUEUE THE READERS' `close()` on the analyser's own thread — see below.
            //  4. `shutdown()` — only now, and `shutdown` rather than `shutdownNow`, so a task
            //     already accepted is allowed to finish rather than interrupted mid-decode. That is
            //     also what lets step 3's queued task run at all.
            //
            // Reversed, step 4 leaves CameraX submitting frames to a dead executor for the moment in
            // between, which surfaces as a RejectedExecutionException on one of its own threads.
            runCatching { analysisSink.get()?.clearAnalyzer() }
            runCatching { providerSink.get()?.unbindAll() }
            // 3b. THE READERS ARE CLOSED **ON THE ANALYSER'S OWN THREAD**, AS ITS LAST TASK.
            //
            //
            //     `close()` releases ML Kit's native detector, and releasing it while a frame is
            //     still inside `read` is a crash in code this app does not own. `shutdown()` does not
            //     wait for the running task, and waiting for it here would block the MAIN thread on
            //     an inference at the moment the dialog is being dismissed. Submitting the close as a
            //     task instead uses the one property this executor was chosen for: it is SINGLE
            //     THREADED, so a queued task cannot start until the frame in flight has finished.
            //
            //     Closing inline is the fallback for a submit that is rejected, which would mean the
            //     executor was already down and no frame can be in flight either.
            val closeReaders = Runnable {
                runCatching { mlKitReader.close() }
                runCatching { referenceReader.close() }
            }
            runCatching { analysisExecutor.execute(closeReaders) }.onFailure { closeReaders.run() }
            runCatching { analysisExecutor.shutdown() }
        }
    }

    /**
     * The torch's state, read from the PLATFORM rather than from the button that asked for it.
     *
     * `enableTorch` is asynchronous and the platform turns the torch off on unbind, so a local
     * boolean goes stale in the one direction that matters — a lit icon over a dark frame, which
     * reads as a broken torch. `observeAsState` would need `androidx.compose.runtime:runtime-livedata`,
     * which is in the Compose BOM but is NOT on this module's classpath (checked `deps.txt`); a
     * `DisposableEffect` plus an `Observer` needs nothing new.
     */
    DisposableEffect(cameraGeneration) {
        val info = cameraSink.get()?.cameraInfo
        val observer = Observer<Int> { state -> torchOn = state == TorchState.ON }
        info?.torchState?.observeForever(observer)
        onDispose { info?.torchState?.removeObserver(observer) }
    }

    /**
     * The main-thread poll: a hit, the patience timer and the stall detector, in ONE loop.
     *
     * One loop and not three effects, because all three read the same two atomics and the order they
     * are read in matters — a hit must win over a stall, or a code read on the last frame before the
     * pipeline hiccupped would be reported as a broken camera.
     */
    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            val text = hitSink.get()
            if (text != null) {
                hit = text
                break
            }
            val now = System.currentTimeMillis()
            // A stall is only claimable once frames HAVE arrived. Before the first one there is
            // nothing to distinguish a stall from a camera that is still opening, and calling the
            // second one a failure is how a slow handset comes to look broken.
            stalled = framesSeen.get() > 0L && now - lastFrameAt.get() > DW_QR_LIVE_STALL_MS
            patienceSpent = now - startedAt > DW_QR_LIVE_PATIENCE_MS
            // Both read here rather than pushed from the analyser thread, on the same reasoning the
            // hit is: the analyser writes atomics and this loop is the only thing that turns them
            // into Compose state. `unavailable` cannot be claimed before a frame has arrived, or a
            // device whose ML Kit is perfectly fine would announce a fallback while the camera opens.
            referenceReaderInUse = framesSeen.get() > 0L && mlKitReader.unavailable
            val refusedAt = outsideBoxAt.get()
            sawOutsideBox = refusedAt > 0L && now - refusedAt < DW_QR_LIVE_OUTSIDE_BOX_MS
            delay(DW_QR_LIVE_POLL_MS)
        }
    }

    /**
     * What happens on a hit, in this order and for these reasons.
     *
     * STOP FIRST, REPORT SECOND. The analyser has already latched itself shut (see
     * [DwQrFrameAnalyzer]) so no later frame can produce a second callback, and the camera is unbound
     * here before anything is reported. Without the latch one code decodes five to ten times before
     * an unbind takes effect, which on `RecordCodeLookup` is five network lookups.
     *
     * The haptic is not decoration: in courtyard glare it is the cue that actually reaches the person
     * holding the phone, which is why the flash of the reticle is a bonus rather than the signal.
     */
    LaunchedEffect(hit) {
        val text = hit ?: return@LaunchedEffect
        runCatching { analysisSink.get()?.clearAnalyzer() }
        runCatching { providerSink.get()?.unbindAll() }
        runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        // Long enough for the success stroke to register as a flash, short enough not to feel like a
        // wait. Skipped under reduced motion, where there is nothing to see.
        if (!reduceMotion) delay(220)
        onText(text)
    }

    /**
     * The sweep, and the two rules it obeys.
     *
     * REDUCED MOTION IS HONOURED. `LocalAppPreferences.current.reducedMotion` is read exactly as
     * `MapScreen.kt:1793` and `AppNavigation.kt:955` read it, and with stillness on there is no sweep
     * at all — the line is a LIVENESS cue and not information, so nothing is lost.
     *
     * IT IS THE SAME PRIMITIVE THE RECORDING INDICATOR USES (`MediaPlayers.kt:228`:
     * `rememberInfiniteTransition` + `infiniteRepeatable(tween(…, LinearEasing), RepeatMode.Reverse)`).
     * One motion vocabulary, not two.
     *
     * AND IT IS READ IN THE DRAW SCOPE, not used to offset a composable. A value read inside
     * `Canvas`'s draw lambda REPAINTS where a composable animating its own offset RECOMPOSES — the
     * discipline `DwQrSymbolImage`'s header already states for the same reason.
     */
    val sweep = rememberInfiniteTransition(label = "qr-sweep")
    val sweepAt by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qr-sweep-position",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Black in every theme, on the reasoning `RecordCodeCard`'s header gives for a QR
                // never inverting: this is a window onto a lens, not a surface in the app.
                .background(Color.Black)
                .onSizeChanged { measured ->
                    val current = boxSize
                    val moved = kotlin.math.abs(measured.width - current.width) > DW_QR_REBIND_SLOP_PX ||
                        kotlin.math.abs(measured.height - current.height) > DW_QR_REBIND_SLOP_PX
                    if (current == IntSize.Zero || moved) boxSize = measured
                },
        ) {
            surfaceRequest?.let { request ->
                /*
                 * TWO ARGUMENTS AND NO MORE, WHICH IS A DECISION ABOUT SOMETHING ELSE.
                 *
                 * `CameraXViewfinder` also takes an implementation mode, a coordinate transformer, an
                 * alignment and a content scale. None is passed, and the alignment/scale pair is the
                 * interesting omission: with a [ViewPort] whose aspect ratio IS this box's, the
                 * displayed image already fills the box exactly, so Crop and Fit coincide and the
                 * composable's default cannot move the picture relative to the reticle. The
                 * correspondence rests on the view port, not on a scale argument — which is the
                 * stronger place for it to rest, because a default that changes in a version bump
                 * then changes nothing that matters here.
                 *
                 * The coordinate transformer is likewise not asked for: `dwQrCropInBuffer` explains
                 * at length why `cropRect` is used instead of a surface-to-view matrix.
                 */
                CameraXViewfinder(request, Modifier.fillMaxSize())
            }

            /*
             * THE OVERLAY: scrim, reticle, corner brackets and sweep, in ONE Canvas.
             *
             * FOUR OPAQUE BANDS AND NOT `BlendMode.Clear` for the hole. Clear requires
             * `Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)` or it
             * punches through the window and paints solid black over the preview — and offscreen
             * compositing costs real GPU time on the Mali-class parts in this fleet. Four rectangles
             * cost nothing and cannot produce that artefact.
             */
            Canvas(modifier = Modifier.fillMaxSize()) {
                val box = reticle ?: return@Canvas
                val left = box.left * size.width
                val top = box.top * size.height
                val right = box.right * size.width
                val bottom = box.bottom * size.height
                val scrim = Color.Black.copy(alpha = 0.55f)

                drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, top))
                drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
                drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
                drawRect(scrim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

                // The stroke turns green for the flash on a hit. It is the SECOND cue; the haptic is
                // the first, because in glare the stroke may not be seen at all.
                val edge = if (hit != null) Color(0xFF34D399) else Color.White
                val strokePx = 2.dp.toPx()
                drawRoundRect(
                    color = edge.copy(alpha = 0.9f),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(width = strokePx),
                )
                // Corner brackets, drawn heavier than the box itself: they are what a camera-shaped
                // affordance looks like, and they survive being drawn over a bright card.
                val armPx = (right - left) * 0.16f
                val heavy = Stroke(width = strokePx * 2.5f)
                listOf(
                    Offset(left, top) to (1f to 1f),
                    Offset(right, top) to (-1f to 1f),
                    Offset(left, bottom) to (1f to -1f),
                    Offset(right, bottom) to (-1f to -1f),
                ).forEach { (corner, direction) ->
                    val (dx, dy) = direction
                    drawLine(
                        color = edge,
                        start = corner,
                        end = Offset(corner.x + armPx * dx, corner.y),
                        strokeWidth = heavy.width,
                    )
                    drawLine(
                        color = edge,
                        start = corner,
                        end = Offset(corner.x, corner.y + armPx * dy),
                        strokeWidth = heavy.width,
                    )
                }

                // The sweep. Nothing at all under reduced motion, and nothing once a code is read —
                // a line still travelling over a decoded code says the scanner is still looking.
                if (!reduceMotion && hit == null) {
                    val y = top + (bottom - top) * sweepAt
                    drawLine(
                        color = Color(0xFF7DD3FC).copy(alpha = 0.85f),
                        start = Offset(left + strokePx, y),
                        end = Offset(right - strokePx, y),
                        strokeWidth = strokePx * 1.5f,
                    )
                }
            }

            // Close and torch, at the top, clear of the reticle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Close", color = Color.White, fontSize = 14.sp)
                }
                // Gated on `hasFlashUnit`, because a torch button that does nothing is worse than no
                // torch button: it is the control a designer in a dark room presses twice and then
                // stops trusting the app about.
                if (torchAvailable) {
                    TextButton(
                        onClick = { runCatching { cameraSink.get()?.cameraControl?.enableTorch(!torchOn) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (torchOn) "Light on" else "Light", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (hit != null) "Read." else DW_QR_LIVE_AIMING,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        // Polite, not assertive: a designer aiming a camera should not have the
                        // reading interrupted, and both states here are things they act on.
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (frontLensFallback) {
                    DwQrLiveNote(DW_QR_LIVE_FRONT_LENS)
                }
                // THE READER THIS DEVICE ENDED UP WITH, said whenever it is the worse one. It is not
                // in the ladder below because it is not a diagnosis of the current attempt — it is a
                // standing fact about the handset, true from the first frame to the last, and it
                // changes what every other sentence here means.
                if (referenceReaderInUse && hit == null) {
                    DwQrLiveNote(DW_QR_LIVE_REFERENCE_READER)
                }
                // A STALL, A REFUSED SIGHTING AND A FRUITLESS LOOK ARE THREE SENTENCES AND ONLY ONE
                // IS SHOWN, in this order and for this reason. The stall wins outright: telling
                // somebody to take a photograph instead when the pipeline has died would send them to
                // a second camera path for a problem that is not about the card at all. A refused
                // sighting beats the patience timer because it is the more specific answer — the app
                // is reading fine and the code is in the wrong place, which "take a photograph"
                // would talk them out of doing anything about.
                if (stalled) {
                    DwQrLiveNote(DW_QR_LIVE_STALLED)
                } else if (sawOutsideBox && hit == null) {
                    DwQrLiveNote(DW_QR_LIVE_OUTSIDE_BOX)
                } else if (patienceSpent && hit == null) {
                    DwQrLiveNote(DW_QR_LIVE_STILL_TRYING)
                }
            }
        }
    }
}

/** One line of advice over the preview: white on a dark plate, so it survives a bright frame. */
@Composable
private fun DwQrLiveNote(message: String) {
    Text(
        message,
        color = Color.White,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * One live frame, handed to whichever reader is working on this device.
 *
 * ── WHAT THIS CLASS STOPPED DOING ON 2026-08-28, AND WHY THAT IS THE FIX ──────────────────────
 *
 * It used to crop the frame to the reticle and run ZXing on the copied-out luminance itself. Every
 * line of that still exists and still ships — it is `ReferenceQrFrameReader` — but it is no longer
 * where the decoding decision lives. The owner reported on 2026-08-27 that a code held inside the box
 * was not being read, `app/build.gradle.kts` had already recorded that ML Kit reads a bent, angled or
 * glared live frame better than ZXing as an ACCEPTED REGRESSION, and the regression became the defect.
 * `data/DwQrFrameReader.kt` holds the seam, both readers and the whole argument.
 *
 * ── EVERY LINE IN `analyze` IS STILL THERE FOR A FAILURE THAT REALLY HAPPENS ──────────────────
 *
 *  * `image.close()` IN A `finally`, ON EVERY PATH, UNCHANGED. An unclosed `ImageProxy` stalls the
 *    CameraX pipeline dead after `imageQueueDepth` frames, and it presents to a designer as "the
 *    preview froze" — which is why this class also reports every frame it sees, so a stall can be
 *    SAID. It survives a reader that BLOCKS this thread only because [DwQrFrameReader]'s contract
 *    requires a reader to be finished with the image before `read` returns; ML Kit's own sample
 *    closes the proxy in a task callback instead, and that shape was rejected for this reason.
 *  * THE LATCH, UNCHANGED. [done] is set inside the analyser, so a code that is still in front of the
 *    lens cannot produce a second callback while the unbind is in flight. Five to ten repeats is the
 *    ordinary case without it, and on `RecordCodeLookup` each one is a network lookup.
 *  * NO ROTATION OF THE BUFFER, STILL — BY EITHER READER. ZXing's finder-pattern search is
 *    rotation-invariant for QR and takes the rotation through the coordinate map. ML Kit is HANDED
 *    the rotation as a fact and turns the picture inside its own native code. Neither costs this
 *    thread a rotation of a 1280x720 plane, which is what that rule was ever about.
 *  * A NULL RETICLE MEANS THE WHOLE FRAME, never a guessed rectangle. Slower, never wrong.
 *  * A REFUSED SIGHTING IS REPORTED, WHICH IS NEW. A code read and then rejected for sitting outside
 *    the box is the one failure this change introduces, and an unreported one would look exactly like
 *    the defect being fixed. [onOutsideTheBox] is what puts [DW_QR_LIVE_OUTSIDE_BOX] on screen.
 *
 * [onFrame], [onOutsideTheBox] and [onDecoded] are called ON THE ANALYSER THREAD and must not touch
 * Compose state. All three write atomics that the dialog's own main-thread loop reads; that
 * indirection is the point.
 */
private class DwQrFrameAnalyzer(
    private val primary: DwQrFrameReader,
    private val reference: DwQrFrameReader,
    private val reticle: () -> DwQrFraction?,
    private val onFrame: (Long) -> Unit,
    private val onOutsideTheBox: (Long) -> Unit,
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val done = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (done.get()) return
            val box = reticle()
            val first = if (primary.unavailable) reference.read(image, box) else primary.read(image, box)
            // THE SAME FRAME IS OFFERED TO THE REFERENCE READER when the primary declined it AND has
            // just declared itself unusable on this device. That is one frame rather than a wasted
            // one, and it is the only place the two readers ever both see a picture: after the
            // primary is down, the branch above sends every later frame straight to the reference.
            val read = if (first is DwQrFrameRead.None && primary.unavailable) {
                reference.read(image, box)
            } else {
                first
            }
            when (read) {
                is DwQrFrameRead.Read ->
                    if (done.compareAndSet(false, true)) onDecoded(read.sighting.text)
                is DwQrFrameRead.OutsideTheBox -> onOutsideTheBox(System.currentTimeMillis())
                DwQrFrameRead.None -> Unit
            }
        } catch (_: Throwable) {
            // A frame is never worth a crash. CameraX invokes this on its own executor, so anything
            // escaping here takes the analyser thread with it and the preview freezes — the same
            // symptom as the unclosed-proxy stall, from the opposite cause.
        } finally {
            onFrame(System.currentTimeMillis())
            runCatching { image.close() }
        }
    }
}

/**
 * The display's rotation, as one of `Surface.ROTATION_*`.
 *
 * Needed for the [ViewPort], which is the thing that makes `cropRect` and the drawn reticle describe
 * one rectangle — so getting it wrong is the invisible failure again, and it is worth the two
 * branches. `Context.getDisplay()` exists from API 30; below that the deprecated
 * `WindowManager.getDefaultDisplay()` is the only route, and this module's floor is 26.
 *
 * ROTATION_0 on failure rather than a throw: a portrait guess is right on the overwhelming majority
 * of scans on this fleet, and a scanner that refused to open because it could not ask the window
 * manager a question would be worse than one whose crop is a quarter turn out in a rare case.
 */
@Suppress("DEPRECATION")
private fun dwDisplayRotation(context: Context): Int = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.display?.rotation
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay
            ?.rotation
    }
}.getOrNull() ?: Surface.ROTATION_0
