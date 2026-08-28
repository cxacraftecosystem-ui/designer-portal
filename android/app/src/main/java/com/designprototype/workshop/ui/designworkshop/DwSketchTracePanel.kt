package com.designprototype.workshop.ui.designworkshop

// The panel holds the three display plates as platform bitmaps, because that is what the runtime
// hands back and what `DwSketchTracePlates` takes. Nothing here reads a pixel.
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.ui.LocalAppPreferences
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt for why a
// bare Material `Text` here would quietly set this panel's headings in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * **"TRACE THIS SKETCH INTO LINE ART" — the handset's surface over [DwTraceRuntime].**
 *
 * ── WHAT THIS IS FOR ──────────────────────────────────────────────────────────────────────────
 *
 * Stage 11's `sketch.lineArtFile` is declared as "An SVG or vector export, if one was produced", and
 * until now only the portal could produce one. This product's premise is a designer working offline
 * in a village for a fortnight, so the client that could not do it is the client that matters most.
 *
 * ── NOTHING IS WRITTEN WITHOUT THE DESIGNER SEEING IT FIRST ───────────────────────────────────
 *
 * Two buttons, in order: one makes a drawing and shows it, the other attaches the drawing that is on
 * screen. There is NO path from a trace to an attachment that does not pass through a person looking
 * at the result — the same discipline [DwSketchRectifyPanel], [DwPhotoMeasurePanel] and
 * [DwIdentityCardControl] apply to a plate, a measurement and a read number, and it matters more here
 * than in any of them because a trace is a THRESHOLD — a decision to discard everything on one side
 * of a line — and the only person who can tell whether that mattered is the designer with the actual
 * sheet still in front of them.
 *
 * The "Attach" button is disabled while what is on screen is a PREVIEW. That is property five of the
 * portal's own panel (`SketchTraceField.tsx:64-70`) turned from a re-trace side effect into a
 * structural fact: saving a preview hands the designer a coarser drawing than the one they approved,
 * with nothing on screen to say so.
 *
 * **Declining costs one press and needs no explanation.** "Keep the photograph as it is" is a
 * first-class outcome and not a cancel, because deciding a trace is not good enough is a correct
 * decision made by the only person who can make it.
 *
 * ── THE THREE THINGS A HANDSET NEEDS THAT THE PORTAL DOES NOT HAVE ────────────────────────────
 *
 *  1. **A visible Cancel.** The portal ships none — `grep -n "Cancel" SketchTraceField.tsx` finds only
 *     `runtime.isCancelled(error)`, verified 2026-08-27 — because cancellation there is implicit: a
 *     moved slider aborts the running preview after a 220 ms debounce and `busy` merely disables three
 *     buttons. That is defensible on a laptop where a preview is a few hundred milliseconds. It is not
 *     defensible here, where a full-resolution run is seconds to tens of seconds of one core on a
 *     mid-range phone, the designer is on battery in a courtyard, and an artisan is waiting.
 *  2. **Progress driven by the engine's own stage names**, weighted by what this device measured last
 *     time. See [DwTraceProgressWeights] for why `index / 12` is not good enough here.
 *  3. **Previews that stop costing battery when nobody is looking**, and that turn themselves off when
 *     THIS phone proves them too slow to be live. See [DW_TRACE_AUTO_PREVIEW_BUDGET_MS].
 *
 * ── ONE BUSY FLAG FOR EVERY DESTINATION ───────────────────────────────────────────────────────
 *
 * A preview, a full trace and an export are the same operation with different endings, so they share
 * one in-flight [Job] and one `running` value. `SketchTraceField.tsx:223-231` records what two
 * independent busy flags cost: "the loser would report 'the trace did not finish' while the winner
 * quietly succeeded".
 *
 * A press does not need to remember to disarm a pending preview either — `startRun` cancels and JOINS
 * whatever was running before it starts, so the bug `SketchTraceField.tsx:336-341` describes (an
 * attach begins, a preview armed 220 ms earlier fires and aborts it, and the attach reports "nothing
 * finished" beside a finished drawing) is not possible rather than merely handled.
 *
 * ── A CANCELLED TRACE IS NOT A FAILURE ────────────────────────────────────────────────────────
 *
 * `worker/trace.worker.ts:156-157` states it for the portal — a cancel "must never reach the user as
 * one" — and this file honours it by never turning a `CancellationException` into an error line.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Numbers this surface owns
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How long the panel waits after the last edit before re-tracing a preview.
 *
 * **NOT the portal's 220 ms** (`RETRACE_DEBOUNCE_MS`, `SketchTraceField.tsx:193`), and the difference
 * is not taste. That number is tuned for a preview that costs a few hundred milliseconds on a laptop.
 * The feasibility spike measured a 720 px preview at 0.6 s on a desktop's V8 with the ADAPTIVE engine
 * and 3.1 s with the shipped flow default, which extrapolates to roughly 2.5-4.4 s and 12-21 s on the
 * fleet's Galaxy M32 — so at 220 ms a designer sliding a control would queue and abandon a trace
 * every fifth of a second and heat the phone for nothing.
 *
 * **450 ms is a starting value and is explicitly NOT MEASURED on a device** (2026-08-27: there was no
 * handset and no emulator on the machine the spike ran on). It is roughly the pause between two
 * deliberate thumb adjustments. Re-check it against a real device before treating it as settled; the
 * self-disabling budget below is what stops it doing damage in the meantime.
 */
const val DW_TRACE_PREVIEW_DEBOUNCE_MS: Long = 450L

/**
 * The longest a preview may take before this phone stops running them automatically.
 *
 * ── WHY THE DEVICE DECIDES THIS AND NOT A TABLE ───────────────────────────────────────────────
 *
 * The single unmeasured number in the whole feasibility argument is the desktop-to-handset factor —
 * "reasoned from published single-thread benchmarks, not measured on a device", and the spike says
 * outright that it is what decides whether this ships. A constant here that assumed 4x or 7x would be
 * writing that guess into a screen.
 *
 * So nothing is assumed. `TraceResult.totalMillis` comes back from every run, and if a preview on THIS
 * phone with THESE parameters took longer than this, live previewing is switched off and the panel
 * says so in a sentence with the measured number in it. The designer keeps a "Update the preview"
 * button and loses nothing but the automatic part. It is the same discipline
 * docs/DEVICE-TIER-MEASUREMENT.md applies to everything else on this handset: measure the device in
 * front of you rather than the device in the specification.
 *
 * Two and a half seconds is the point at which a live preview stops being live — past it a designer
 * has already looked away.
 */
const val DW_TRACE_AUTO_PREVIEW_BUDGET_MS: Long = 2500L

/**
 * How long the disclosure's chevron takes to turn over.
 *
 * ── THE CHEVRON IS THE ONLY THING THAT MOVES, AND THAT IS A DECISION ──────────────────────────
 *
 * The obvious animation for an accordion is the height of the thing it opens, and this one has none.
 * A height animation on this section would run a measure pass over up to twenty-four control rows on
 * every frame for the length of the tween — on the phone class this whole feature is hardest on, at
 * the moment a designer has just asked to see more — and `DwTraceSliderRow` re-seeds a remembered
 * thumb position from the committed value, so an animated row is also a row being laid out while its
 * slider is being re-created. `SketchTraceField.tsx` reaches the same answer from the other side and
 * says so at its own disclosure: "NO HEIGHT ANIMATION, AND THAT IS A DECISION."
 *
 * So one 18 dp icon turns, which costs a draw-time rotation and nothing else, and it is the only
 * place `LocalAppPreferences.current.reducedMotion` has anything to switch off — where it collapses
 * this tween to zero and the chevron simply IS at its new angle, exactly as
 * `DwSketchTraceCompare.kt:247` and `MapScreen.kt:1793` read the same preference.
 */
const val DW_TRACE_DISCLOSURE_TURN_MS: Int = 180

/* ────────────────────────────────────────────────────────────────────────────
 * The export slot
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Everything the export step needs from a finished trace, and the three ways back into the panel.
 *
 * ── WHY THIS EXISTS RATHER THAN THE PANEL COMPOSING THE CARD ITSELF ───────────────────────────
 *
 * `DwSketchTraceExportCard` needs a `WorkshopRepository` and a `DwTraceExporter`; the tuning surface
 * needs neither, and attaching to the record needs neither. Taking them as parameters would make a
 * panel that cannot be composed — or read, or previewed — without two dependencies that have nothing
 * to do with what it is for. So the host passes a lambda, and a host that cannot offer file saving
 * simply passes nothing and loses nothing else.
 *
 * ── THE THREE CALLBACKS, AND WHY THE CARD CANNOT DO ANY OF THEM ITSELF ────────────────────────
 *
 * [onBackgroundChange] — `output.background` is a LEAF OF THE PARAMETER TREE, read at the twelfth and
 * last pipeline stage, so changing it means patching the params through the engine's own sanitiser
 * and running the pipeline again. That machinery is this panel's, and a second copy of it inside the
 * export card would be a second thing that can disagree about what the current parameters are.
 *
 * [onNeedFullResolution] — a preview is never saved and never attached, which is the same gate on
 * both sides; only the panel can start a full-resolution run.
 *
 * [busy] and [onBusyChange] — ONE busy flag across the whole surface. `SketchTraceField.tsx:223-231`
 * records what two independent ones cost: "the loser would report 'the trace did not finish' while
 * the winner quietly succeeded". The export card's own header makes the same argument from the file
 * -writing side, where two concurrent MediaStore writes into one folder truncate each other.
 */
@Immutable
class DwTraceExportSlot(
    val result: DwTraceResult,
    /**
     * What the document stage ACTUALLY wrote — packed ARGB, or null for transparent.
     *
     * Taken from `appliedParams` rather than from the request, because auto-detection runs before the
     * first stage and the two can legitimately differ (`worker/protocol.ts:139-149`). Null is a real
     * value here and not a missing one: it is the only spelling of a transparent export
     * (`engine/params.ts:359`).
     */
    val documentBackground: Int?,
    /**
     * The photograph's own display name — what the exported file is named after.
     *
     * Carried because only this panel knows WHICH of the record's photographs was traced, and a saved
     * file named after the wrong sheet is a file nobody can match back to its original four days
     * later. `DwSketchTraceExportCard` takes only the last path segment.
     */
    val sourceName: String,
    val busy: Boolean,
    val onBusyChange: (Boolean) -> Unit,
    val onBackgroundChange: (Boolean) -> Unit,
    val onNeedFullResolution: () -> Unit,
)

/* ────────────────────────────────────────────────────────────────────────────
 * Which field gets the offer
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Should this FILE field offer "trace this sketch into line art"?
 *
 * **DELEGATES TO [dwOffersSketchRectify] RATHER THAN RE-DERIVING THE ANSWER**, and the delegation is
 * the point. Both panels write a derived artefact into the same destination field for the same
 * reason: a single-valued field REPLACES its value when something is attached, so neither may be
 * offered on `sketch.image` or it would DETACH the photograph — which is what docs/MEDIA_PIPELINE.md
 * §5 refuses. A second regex here would be a second copy of that decision, and the two would
 * eventually disagree about which fields are safe, which is the whole class of bug this repository's
 * field-role helpers were consolidated to prevent.
 */
internal fun dwOffersSketchTrace(field: FieldDto, siblings: Map<String, FieldDto>): Boolean =
    dwOffersSketchRectify(field, siblings)

/* ────────────────────────────────────────────────────────────────────────────
 * The panel
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The whole surface, collapsed until the designer asks for it.
 *
 * COLLAPSED BY DEFAULT AND EVERY LARGE ALLOCATION LIVES INSIDE THE OPEN HALF, for the reason
 * [DwSketchRectifyPanel] states for itself and one more of its own: opening this panel starts a
 * runtime, loads an engine and holds two display plates, and closing it drops all of them. On a phone
 * whose other job right now is a camera preview that is the right trade.
 *
 * Split into two composables because a composable whose remembered slots sit below a conditional
 * return appears and disappears between frames.
 */
@Composable
internal fun DwSketchTracePanel(
    field: FieldDto,
    sources: List<DwSketchSource>,
    runtime: DwTraceRuntime,
    media: DwMediaBridge,
    /** The record's own `sketch.category`, for seeding the subject. Null when the record has none. */
    recordCategory: String?,
    /** The file already in this field, so the panel can say what attaching would replace. */
    currentFileName: String?,
    enabled: Boolean,
    /** Write the new media id into this FILE field. Called only from a button the designer pressed. */
    onAttached: (String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    /** Where the host wires `DwSketchTraceExportCard`, if it can. See [DwTraceExportSlot]. */
    exportCard: (@Composable (DwTraceExportSlot) -> Unit)? = null,
) {
    if (sources.isEmpty()) return
    var open by remember { mutableStateOf(false) }

    if (!open) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Gesture,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Trace a photographed sketch into line art",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                // "as lines that print at any size" AND NOT "a report can print at any size": the
                // second is a claim about the ministry document and it is false. See
                // DW_TRACE_ATTACH_REPORT_SENTENCE, which is the corrected version and is printed
                // under the Attach button where a designer is deciding.
                "Turns the pencil in one of the photographs on this record into vector line work — the " +
                    "same drawing, as lines that print at any size without going blocky. The result " +
                    "is attached here as ${field.label}; the photograph itself is never changed. It " +
                    "runs on this device and needs no connection.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            // ALWAYS THE BUTTON. There used to be a branch here that drew a sentence instead, for a
            // phone whose WebView was too old to start the JavaScript isolate the tracer ran in — a
            // real state on a handset that has been in a village for a fortnight, and the reason
            // `DwTraceAvailability` used to carry a `canTrace` and a `refusal`. The engine is
            // compiled into this APK now, so there is no such phone and no such sentence: see that
            // class's header. What can still stop one trace is memory, and that is said at the
            // moment it is true rather than guessed at before the panel opens.
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trace a sketch", fontSize = 13.sp)
            }
        }
        return
    }

    DwSketchTraceOpen(
        field = field,
        sources = sources,
        runtime = runtime,
        media = media,
        recordCategory = recordCategory,
        currentFileName = currentFileName,
        enabled = enabled,
        onClose = { open = false },
        onAttached = onAttached,
        onMessage = onMessage,
        onError = onError,
        exportCard = exportCard,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwSketchTraceOpen(
    field: FieldDto,
    sources: List<DwSketchSource>,
    runtime: DwTraceRuntime,
    media: DwMediaBridge,
    recordCategory: String?,
    currentFileName: String?,
    enabled: Boolean,
    onClose: () -> Unit,
    onAttached: (String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    exportCard: (@Composable (DwTraceExportSlot) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availability = runtime.availability

    var sourceId by remember { mutableStateOf(sources.first().item.id) }
    val source = sources.firstOrNull { it.item.id == sourceId } ?: sources.first()

    var presets by remember { mutableStateOf<DwTracePresetTables?>(null) }
    var params by remember { mutableStateOf<DwTraceValues?>(null) }

    /**
     * The tree as the last PRESET left it — the baseline the "you changed this" marks measure against.
     *
     * Set by a style and by nothing else, which is the portal's rule and the engine's shape: a style
     * REPLACES the settings, so it becomes both the live parameters and the baseline; a subject is a
     * one-way modifier that says something about the material rather than about the drawing wanted,
     * so it leaves the baseline alone (`SketchTraceField.tsx:802-818`).
     */
    var baseline by remember { mutableStateOf<DwTraceValues?>(null) }

    var styleId by remember { mutableStateOf("") }
    var subjectId by remember { mutableStateOf(dwTraceSubjectFor(recordCategory)) }
    var subjectSeeded by remember { mutableStateOf(true) }

    var result by remember { mutableStateOf<DwTraceResult?>(null) }
    /** The tree the on-screen [result] was produced from, so "these settings have moved on" is exact. */
    var resultWire by remember { mutableStateOf<String?>(null) }

    /**
     * Which region of the photograph the engine is handed, or null for all of it.
     *
     * Cleared with the photograph, for the chooser's own reason one level up: a frame chosen on one
     * sheet is meaningless on the next, and leaving it applied would trace a region of a photograph
     * nobody framed. `FramePanel`'s `onEdited(null)` on a source change is the same clearing.
     */
    var frame by remember { mutableStateOf<DwTraceFrameChoice?>(null) }

    /*
      THE THIRD PLATE, BUILT ON THE FIRST PRESS AND NOT WITH THE OTHER TWO.

      Held here rather than inside the comparator because building it needs a `Bitmap`, and
      `DwSketchTraceCompare.kt` is deliberately the file that does not know what one is. Cleared
      whenever a new result arrives: a difference belongs to ONE pair of plates, and one left on screen
      under a newer drawing is a picture of a comparison that is no longer being made.

      NOT RECYCLED when it is dropped. `DwImageDecode.decodeForDisplay`'s header settles it: Compose
      holds a bitmap through an `ImageBitmap` for as long as the frame is on screen, and recycling one
      that is still being drawn throws in the middle of an unsaved stage. Dropping the reference is
      enough — it is 4.2 MB of garbage the moment the panel stops pointing at it.
    */
    var difference by remember { mutableStateOf<Bitmap?>(null) }
    var differenceRefusal by remember { mutableStateOf("") }
    var differenceRunning by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf<DwTraceRunKind?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<DwTraceProgress?>(null) }
    var weights by remember { mutableStateOf(DwTraceProgressWeights.Unweighted) }

    var notice by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var advancedOpen by remember { mutableStateOf(false) }
    var autoPreview by remember { mutableStateOf(true) }
    var attaching by remember { mutableStateOf(false) }

    var job by remember { mutableStateOf<Job?>(null) }

    /*
      ONE PATCH AT A TIME. Every parameter change is a round trip to the runtime's own
      `withOverrides` — a call into another process where the portal's equivalent is a synchronous
      function in the same page — so two taps in quick succession would otherwise both read the same
      `params`, both apply their own patch to it, and the second would land on top of the first with
      the first's change erased. The mutex makes each patch read the tree AFTER the previous one wrote
      it, which is the only ordering that cannot lose an edit.
    */
    val patchLock = remember { Mutex() }

    /*
      PREVIEWS STOP WHILE NOBODY IS LOOKING. A trace is seconds of solid arithmetic; running one
      because a slider moved just before the designer answered a phone call is battery spent on a
      picture nobody will see. Full-resolution runs are deliberately NOT gated on this — a designer who
      starts a twenty-second trace and locks the screen to wait for it should get their drawing.
    */
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val stale = result != null && resultWire != null && resultWire != params?.wire

    /* ── Loading the engine's own tables ────────────────────────────────────────────────────── */

    LaunchedEffect(runtime) {
        loading = true
        failure = null
        try {
            val tables = runtime.presets()
            val defaults = runtime.defaults()
            presets = tables
            params = defaults
            baseline = defaults
            styleId = defaults.styleId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // Named where it is true rather than reported as "this phone cannot trace". The engine is
            // compiled into this APK and every phone has it, so a failure here is this load and not
            // this device, and those are different sentences with different remedies. The portal
            // makes the same distinction at `SketchTraceField.tsx:410`.
            failure = "The tracing engine did not start on this device. " +
                (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
        }
        loading = false
    }

    /* ── Running one trace ──────────────────────────────────────────────────────────────────── */

    /**
     * Start a run, superseding whatever was running.
     *
     * `cancelAndJoin` and not a bare `cancel`, deliberately: the new run WAITS for the old one to
     * actually stop before it touches any state. Without the join the two bodies race, and the loser's
     * cleanup runs after the winner's set-up — which is how a finished trace ends up beside a spinner
     * that never clears. It is also what makes "Stopping…" honest, because the old run's `finally` is
     * reached only when the runtime really has stopped, and the engine checks cancellation between
     * stages and nowhere else (`pipeline.ts:238-246`).
     */
    fun startRun(kind: DwTraceRunKind, debounceMs: Long) {
        val values = params ?: return
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            if (debounceMs > 0L) delay(debounceMs)
            running = kind
            stopping = false
            progress = null
            failure = null
            try {
                val outcome = runtime.trace(
                    DwTraceRequest(
                        photographPath = source.item.absolutePath,
                        params = values,
                        kind = kind,
                        frame = frame,
                    ),
                ) { progress = it }
                when (outcome) {
                    is DwTraceOutcome.Refused -> {
                        result = null
                        resultWire = null
                        difference = null
                        differenceRefusal = ""
                        failure = outcome.reason
                    }

                    is DwTraceOutcome.Done -> {
                        val traced = outcome.result
                        result = traced
                        // The old difference belongs to the old pair of plates. See the note where it
                        // is declared.
                        difference = null
                        differenceRefusal = ""
                        /*
                          THE PANEL IS RE-RENDERED FROM `appliedParams`, NOT FROM WHAT WAS SENT.
                          Auto-detection runs before the first stage, so a request and its result can
                          legitimately differ; dropping this "would leave the client with a dock that
                          says one thing and a drawing produced by another" (`protocol.ts:139-149`).
                        */
                        params = traced.appliedParams
                        resultWire = traced.appliedParams.wire
                        if (traced.stages.isNotEmpty()) weights = DwTraceProgressWeights.from(traced.stages)
                        if (kind == DwTraceRunKind.PREVIEW &&
                            traced.totalMillis > DW_TRACE_AUTO_PREVIEW_BUDGET_MS
                        ) {
                            // Measured on THIS phone, said with the number in it. See the constant.
                            autoPreview = false
                            notice = "That preview took ${dwTraceSeconds(traced.totalMillis)} on this " +
                                "phone, so the panel has stopped re-tracing by itself. Change what you " +
                                "like and press “Update the preview” when you are ready."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // NEVER AN ERROR. `trace.worker.ts:156-157`: a cancel "must never reach the user as
                // one". Rethrown so the coroutine machinery sees a cancellation rather than a swallowed
                // one, which is what keeps `cancelAndJoin` above correct.
                throw cancelled
            } catch (t: Throwable) {
                result = null
                resultWire = null
                failure = "The trace could not be completed. " +
                    (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
            } finally {
                running = null
                stopping = false
                progress = null
            }
        }
    }

    /** Arm a preview, if a preview is a thing this panel should be doing right now. */
    fun armPreview() {
        if (!autoPreview || !resumed) return
        // A full-resolution run is the answer the designer asked for; a preview must not evict it.
        if (running?.isFullResolution == true) return
        startRun(DwTraceRunKind.PREVIEW, DW_TRACE_PREVIEW_DEBOUNCE_MS)
    }

    /*
      ONE PREVIEW WHEN THE PANEL OPENS, and never a second one it did not ask for.

      A panel that opened onto an empty frame would make a designer press a button to find out what
      the feature even does, and the answer to "what will this look like" is the whole reason the
      preview exists. Keyed so it fires once per opening: `primed` is remembered beside the rest of
      the panel's state, so closing and re-opening asks again and a recomposition does not.
    */
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(loading, autoPreview, resumed) {
        if (loading || params == null || primed) return@LaunchedEffect
        if (!autoPreview || !resumed) return@LaunchedEffect
        primed = true
        startRun(DwTraceRunKind.PREVIEW, 0L)
    }

    /**
     * Build the difference plate, once, for the pair of plates currently on screen.
     *
     * ON A BACKGROUND DISPATCHER, because it reads and writes every pixel of a 1024 px pair — about
     * a million iterations of [dwTraceDifferenceRow] — and doing that on the composition would drop
     * frames on exactly the phones this feature is hardest on.
     *
     * A REFUSAL IS A SENTENCE AND NOT A DISABLED CHIP. A phone that could not spare a third 4.2 MB
     * bitmap has lost one of four views and nothing else; the drawing, the wipe and the two whole
     * pictures are all still there, so what is owed is a line saying which view is missing.
     */
    fun buildDifference() {
        if (differenceRunning || difference != null) return
        val traced = result ?: return
        val photograph = traced.photographPlate ?: return
        val trace = traced.tracePlate ?: return
        differenceRunning = true
        differenceRefusal = ""
        scope.launch {
            val built = withContext(Dispatchers.Default) {
                DwSketchTracePlates.differencePlate(photograph, trace)
            }
            // Dropped if the designer moved on while it was being built: a plate for a pair of plates
            // that are no longer on screen is a picture of a comparison nobody is making.
            if (result === traced) {
                difference = built
                differenceRefusal = if (built == null) DW_TRACE_DIFFERENCE_REFUSAL else ""
            }
            differenceRunning = false
        }
    }

    /** Apply a patch through the ENGINE's own merge-and-sanitise, then arm a preview. */
    fun patch(over: Map<String, DwTraceValue>) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val next = try {
                    runtime.withOverrides(base, over)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "That setting could not be applied. " +
                        (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
                    return@withLock
                }
                params = next
                notice = null
            }
            armPreview()
        }
    }

    /** Apply a style: the engine's own complete tree for that preset. */
    fun pickStyle(id: String) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val name = dwTracePresetName(presets?.styles.orEmpty(), id)
                val next = try {
                    runtime.applyStyle(base, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "That style could not be applied. " +
                        (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
                    return@withLock
                }
                notice = dwTraceOverwriteNotice("The “$name” style", base, next)
                params = next
                // A style REPLACES the settings, so it becomes the baseline too.
                baseline = next
                styleId = next.styleId.ifBlank { id }
            }
            armPreview()
        }
    }

    /** Apply a subject: the engine's own idempotent adjustment, on top of whatever is there. */
    fun pickSubject(id: String) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val name = dwTracePresetName(presets?.subjects.orEmpty(), id)
                val next = try {
                    runtime.applySubject(base, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "That adjustment could not be applied. " +
                        (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
                    return@withLock
                }
                notice = dwTraceOverwriteNotice("The “$name” adjustment", base, next)
                params = next
                subjectId = id
                subjectSeeded = false
            }
            armPreview()
        }
    }

    /** File the drawing on screen as this field's line art. */
    fun attach() {
        val traced = result ?: return
        if (traced.isPreview) return
        attaching = true
        scope.launch {
            // The staging file goes under filesDir, never cacheDir — see [DwMediaBridge.newCaptureFile]
            // — and its name says what it is, because months later somebody reading the workshop's
            // media directory has only the filenames. "line-art" is the portal's own suffix for this
            // artefact (`traceExport.ts:105-122`), where the attached file and the vector download
            // share it precisely because they are byte for byte the same file.
            val file = media.newCaptureFile("-$DW_TRACE_ATTACH_SUFFIX.svg")
            val written = withContext(Dispatchers.IO) {
                runCatching { file.writeText(traced.svg, Charsets.UTF_8) }.isSuccess
            }
            attaching = false
            if (!written) {
                runCatching { file.delete() }
                onError("The line art could not be written to this device.")
                return@launch
            }
            // Not gated on the import completing, exactly as [DwSketchRectifyPanel]'s attach is not:
            // the bridge launches its own coroutine, reports its own failures, and calls back only on
            // success. A flag waiting on a callback a failed import never makes would leave this button
            // disabled for the rest of the session.
            media.attach(listOf(dwCaptureUri(context, file)), field) { ids ->
                val id = ids.firstOrNull()
                if (id != null) {
                    onAttached(id)
                    onMessage("The line art is attached as ${field.label}. The photograph is unchanged.")
                }
                // Only after the callback: the import made its own durable copy, so deleting earlier
                // would be deleting the only copy if the import had failed.
                runCatching { file.delete() }
            }
        }
    }

    /* ── The surface ────────────────────────────────────────────────────────────────────────── */

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Trace a sketch into line art",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Close", fontSize = 12.sp)
            }
        }

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Starting the tracing engine…", color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
            return@Column
        }

        val current = params
        val tables = presets
        if (current == null || tables == null) {
            DwPanelNote(warning = true, text = failure ?: DW_TRACE_ENGINE_SILENT_SENTENCE, polite = true)
            return@Column
        }

        /* ── Which photograph ───────────────────────────────────────────────────────────────── */

        if (sources.size > 1) {
            DwPanelLabel("Photograph to trace")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEach { candidate ->
                    DwPanelChip(
                        label = "${candidate.fieldLabel} · ${candidate.item.displayName}",
                        selected = candidate.item.id == source.item.id,
                        enabled = enabled && running == null,
                    ) {
                        // The result belongs to ONE photograph. Leaving it on screen under a different
                        // one is a drawing the designer would attach believing it came from what they
                        // are looking at — [DwSketchRectifyPanel] clears its plate for the same reason.
                        sourceId = candidate.item.id
                        result = null
                        resultWire = null
                        difference = null
                        differenceRefusal = ""
                        // A frame chosen on one sheet is meaningless on the next, and leaving it
                        // applied would trace a region of a photograph nobody framed. `FramePanel`
                        // clears its own the same way, on the same event.
                        frame = null
                    }
                }
            }
        }

        /* ── Which part of it ───────────────────────────────────────────────────────────────── */

        DwTraceFramePanel(
            sourcePath = source.item.absolutePath,
            sourceKey = source.item.id,
            applied = frame,
            enabled = enabled && running == null,
            onApply = {
                frame = it
                // The drawing on screen came from the OLD frame, so a preview of the new one is what
                // a designer who just pressed that button is asking for. `armPreview` declines when
                // auto-preview is off or a full run is in flight, which are both the right answers.
                armPreview()
            },
        )

        /* ── What came back ─────────────────────────────────────────────────────────────────── */

        val traced = result
        val photographPlate = traced?.photographPlate
        val tracePlate = traced?.tracePlate
        /*
          AN ABSENCE IS A SENTENCE. This panel used to compose the comparator only when a result
          existed and put NOTHING in its place — no card, no placeholder, no line — which is the same
          empty area this repository keeps having to distinguish from a place with no records.
          `dwTraceComparisonStatus` holds the five answers and the reason each is worded as it is.
        */
        val comparisonStatus = dwTraceComparisonStatus(
            hasPlates = photographPlate != null && tracePlate != null,
            running = running != null,
            failed = failure != null,
            plateRefusal = traced?.plateRefusal.orEmpty(),
            hasResult = traced != null,
        )
        // THE HEADING IS ALWAYS DRAWN, whether or not there is anything under it. The portal's card
        // appears as soon as a photograph is chosen (`SketchTraceField.tsx:1372`) for the reason this
        // whole block exists: a section that only exists once it has content leaves a designer unable
        // to tell "there is nothing here yet" from "this feature is not on this screen".
        DwPanelLabel("The trace against the photograph")
        if (comparisonStatus.isNotEmpty()) {
            DwPanelNote(
                warning = traced?.plateRefusal?.isNotBlank() == true,
                text = comparisonStatus,
                polite = true,
            )
        }
        if (traced != null) {
            if (photographPlate != null && tracePlate != null) {
                DwSketchTraceCompare(
                    photograph = photographPlate.asImageBitmap(),
                    trace = tracePlate.asImageBitmap(),
                    tracedWidth = traced.width,
                    tracedHeight = traced.height,
                    difference = difference?.asImageBitmap(),
                    differenceRefusal = differenceRefusal,
                    onDifferenceWanted = { buildDifference() },
                    enabled = enabled && running == null,
                )
            }
            DwTraceStatsRow(traced)
            if (traced.isPreview) {
                DwPanelNote(
                    warning = false,
                    // Property five of the portal's own panel. The numbers are in the sentence because
                    // "this is a preview" without them does not tell a designer how much coarser.
                    text = "This is a preview at ${traced.workingWidth}×${traced.workingHeight}, not " +
                        "the full ${traced.width}×${traced.height}. Press “Trace the sketch” for the " +
                        "drawing that gets attached.",
                    polite = true,
                )
            }
            if (stale) {
                DwPanelNote(
                    warning = false,
                    text = "The settings have changed since this drawing was made.",
                    polite = true,
                )
            }
            if (traced.autoSubjectId.isNotBlank()) {
                DwPanelNote(
                    warning = false,
                    text = "The engine applied the “${dwTracePresetName(tables.subjects, traced.autoSubjectId)}” " +
                        "subject adjustment on its own.",
                )
            }
            DwTraceNotes(traced.notes)
        }

        /* ── Progress and cancellation ──────────────────────────────────────────────────────── */

        if (running != null) {
            DwTraceProgressRow(
                kind = running,
                progress = progress,
                weights = weights,
                stopping = stopping,
                enabled = enabled,
            ) {
                // Set BEFORE the cancel so the sentence is on screen while the engine finishes the
                // stage it is in. The `finally` above clears it when the runtime really has stopped,
                // so "Stopping…" lasts exactly as long as stopping takes rather than for a guessed
                // interval — which is the only version of this a designer can trust.
                stopping = true
                job?.cancel()
            }
        }

        failure?.let { DwPanelNote(warning = true, text = it, polite = true) }
        notice?.let { DwPanelNote(warning = false, text = it, polite = true) }

        val missing = remember(current) { dwTraceMissingKeys(current) }
        if (missing.isNotEmpty()) {
            DwPanelNote(
                warning = true,
                text = "${missing.size} of this app's ${DW_TRACE_PARAM_COUNT} settings were not offered " +
                    "by the tracing engine on this device and are not shown: ${missing.joinToString(", ")}. " +
                    "The trace still runs; this app and the engine are a version apart.",
            )
        }

        /* ── Presets ────────────────────────────────────────────────────────────────────────── */

        DwTraceStylePicker(
            styles = tables.styles,
            selectedId = styleId,
            enabled = enabled && running == null,
            onPick = { pickStyle(it) },
        )
        DwTraceSubjectPicker(
            subjects = tables.subjects,
            selectedId = subjectId,
            enabled = enabled && running == null,
            seededFromRecord = subjectSeeded,
            onPick = { pickSubject(it) },
        )
        if (subjectSeeded) {
            // The seed is a PRE-SELECTION and nothing has been applied yet, which has to be said or
            // the control is claiming a state the tree is not in. One press applies it.
            OutlinedButton(
                onClick = { pickSubject(subjectId) },
                enabled = enabled && running == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    "Adjust for ${dwTracePresetName(tables.subjects, subjectId).lowercase(Locale.ROOT)}",
                    fontSize = 13.sp,
                )
            }
        }
        traced?.let {
            DwTraceStyleSuggestion(
                styles = tables.styles,
                suggestedStyleId = it.suggestedStyleId,
                currentStyleId = styleId,
                enabled = enabled && running == null,
                onApply = { pickStyle(it) },
            )
        }

        /* ── The controls ───────────────────────────────────────────────────────────────────── */

        val changedFromPreset = remember(baseline, current) {
            baseline?.let { dwTraceChangedLabels(it, current) }.orEmpty().toSet()
        }
        /*
          WHICH TIERS ARE ACTUALLY IN FRONT OF THE DESIGNER RIGHT NOW.

          PRIMARY always; ADVANCED while the disclosure is open; and EXPORT only while the export card
          is really composed, which it is not until a trace has finished AND a host has passed one.
          That third condition used to be missing, and the bug it produced is small and exactly the
          class this panel is disciplined against: a style that moved `output.background` while the
          card WAS on screen made the panel print "One setting that is not on screen has moved: White
          background" directly above the chips that were showing it.
        */
        val exportVisible = traced != null && exportCard != null
        val visibleTiers = remember(advancedOpen, exportVisible) {
            setOfNotNull(
                DwTraceTier.PRIMARY,
                DwTraceTier.ADVANCED.takeIf { advancedOpen },
                DwTraceTier.EXPORT.takeIf { exportVisible },
            )
        }
        val hiddenChanged = remember(baseline, current, visibleTiers) {
            baseline?.let { dwTraceChangedHiddenLabels(it, current, visibleTiers) }.orEmpty()
        }
        // What THIS press would reveal, which is a narrower question than "what is out of sight" —
        // see `dwTraceChangedBehindDisclosure` for why the toggle must not count the export tier.
        val changedBehindDisclosure = remember(baseline, current) {
            baseline?.let { dwTraceChangedBehindDisclosure(it, current) }.orEmpty()
        }

        DW_TRACE_CONTROLS.filter { it.tier == DwTraceTier.PRIMARY }.forEach { control ->
            DwTraceControlRow(
                control = control,
                values = current,
                availability = availability,
                changed = control.label in changedFromPreset,
                enabled = enabled && running == null,
                onPatch = { patch(it) },
            )
        }

        DwTraceAdvancedSection(
            values = current,
            availability = availability,
            open = advancedOpen,
            changedFromPreset = changedFromPreset,
            hiddenChanged = hiddenChanged,
            changedBehindDisclosure = changedBehindDisclosure,
            // THE PRESS STAYS LIVE WHILE A TRACE RUNS and the rows inside it do not. Reading what a
            // style just did to a folded-away setting is exactly what somebody watching a twenty-
            // second trace wants to do, and it changes nothing; moving a slider mid-run would.
            enabled = enabled,
            rowsEnabled = enabled && running == null,
            onToggle = { advancedOpen = !advancedOpen },
            onPatch = { patch(it) },
        )

        /* ── Previews ───────────────────────────────────────────────────────────────────────── */

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = autoPreview,
                onCheckedChange = { autoPreview = it },
                enabled = enabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Re-trace a preview as I change settings", color = MaterialTheme.field.body, fontSize = 12.sp)
                Text(
                    if (resumed) {
                        "A small, fast trace after each pause. Turn it off to save battery."
                    } else {
                        "Paused while this screen is in the background."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        OutlinedButton(
            onClick = { startRun(DwTraceRunKind.PREVIEW, 0L) },
            enabled = enabled && running == null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Update the preview", fontSize = 13.sp)
        }

        /* ── The two full-resolution buttons ────────────────────────────────────────────────── */

        val bar = dwTraceCostRefusal(current, availability)
        bar?.let { DwPanelNote(warning = true, text = it) }
        if (availability.measuredOn == null) {
            // AN UNMEASURED CEILING IS A GUESS, AND IT SAYS SO. docs/DEVICE-TIER-MEASUREMENT.md's rule:
            // what has and has not been weighed is written down where it is used.
            Text(
                "No timing has been measured on this model of phone yet, so the limits above are " +
                    "cautious estimates rather than readings.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { startRun(DwTraceRunKind.ATTACH, 0L) },
                enabled = enabled && running == null && bar == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trace the sketch", fontSize = 13.sp)
            }
            Button(
                onClick = { attach() },
                // THE ONE GUARD THAT MATTERS: what is on screen must be the full-resolution drawing,
                // made from the settings that are on screen now. A preview or a stale result would
                // hand the designer a file that is not the one they approved.
                enabled = enabled && running == null && !attaching &&
                    traced != null && !traced.isPreview && !stale,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Attach as ${field.label}", fontSize = 13.sp)
            }
            /*
              DECLINING IS A FIRST-CLASS OUTCOME AND NOT A CANCEL. `SketchTraceField.tsx:45-57`: a
              threshold is a decision to discard everything on one side of it, and the person who can
              tell whether that mattered is the designer with the actual sheet in front of them. It
              needs no explanation and nothing is written.
            */
            TextButton(onClick = onClose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep the photograph as it is", fontSize = 12.sp)
            }
        }

        /*
          THE EXPORT STEP IS A SLOT, AND EVERYTHING IN IT BELONGS TO ANOTHER LANE.

          `DwSketchTraceExportCard` writes files to the phone — SVG, and four more formats when a
          build has a writer for them — and it owns the "White background" chips that
          `DwSketchTraceParams.kt` relocates to [DwTraceTier.EXPORT]. That card is deliberately built
          out of primitives so it does not depend on the data classes this lane owns, and its own
          header states the seam from its side: "the card draws the chips and calls back; the host
          patches through `DwTraceRuntime.withOverrides` and re-traces". This is that host.

          A SLOT RATHER THAN A DIRECT CALL, because composing it here would drag a `WorkshopRepository`
          and a `DwTraceExporter` into a file whose whole job is the tuning surface — and the two would
          then have to be threaded through `dwOffersSketchTrace`'s caller for a panel that works
          perfectly well without either. Attaching to the record does not need them; SAVING A COPY TO
          THE PHONE does, and that is exactly the part a host may or may not be able to offer.

          The background is read from `appliedParams` and not from what was sent, for the reason
          `worker/protocol.ts:139-149` gives: auto-detection runs before the first stage, so a request
          and its result can differ, and the card must describe the document that exists.
        */
        if (traced != null && exportCard != null) {
            exportCard(
                DwTraceExportSlot(
                    result = traced,
                    // Through `Long` and then `Int`: the leaf holds an UNSIGNED packed ARGB, so
                    // opaque white is 4294967295, which a direct `toInt()` on a Double saturates to
                    // Int.MAX_VALUE (0x7FFFFFFF — a transparent-ish grey) instead of wrapping to
                    // 0xFFFFFFFF. The long hop is the wrap, and it is the difference between a card
                    // that says "White" and one that says something no colour is.
                    documentBackground = current.number("output.background")?.toLong()?.toInt(),
                    sourceName = source.item.displayName,
                    busy = running != null || attaching,
                    onBusyChange = { attaching = it },
                    onBackgroundChange = { white ->
                        val control = DW_TRACE_TOGGLES.first { it.key == "output.background" }
                        patch(control.patch(white))
                    },
                    onNeedFullResolution = { startRun(DwTraceRunKind.ATTACH, 0L) },
                ),
            )
        }

        currentFileName?.let {
            DwPanelNote(warning = true, text = "“$it” is attached here now. Attaching replaces it.")
        }
        Text(
            // The one thing about this file that a designer cannot see and would want to know — and
            // it used to be stated backwards. "Vector line work a report can print at any size" reads
            // as a promise that an officer will see the drawing, and `DwSketchTraceExport.kt`'s header
            // establishes from three backend modules that they will not: `report_builder._images`
            // filters on IMAGE and IMAGE_LIST, and a FILE field prints "1 document attached". The
            // corrected claim is one constant, beside the one the export block prints for the SAVED
            // copy, because the two files have different fates and the same authority behind them.
            DW_TRACE_ATTACH_REPORT_SENTENCE,
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * What this device will not do, and why
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence barring a full-resolution run on this device, or null when there is nothing to bar.
 *
 * ── WHY THIS REFUSES RATHER THAN QUIETLY SUBSTITUTING ─────────────────────────────────────────
 *
 * The obvious fix for a slow edge engine on a phone is to swap it for a fast one. That is the worst
 * available option and the one this repository exists to prevent: one sheet of paper would then
 * produce two different drawings depending on which client traced it, and the vendored engine's whole
 * discipline — float32 quantisation, a 1e-4 parity tolerance, a cross-runtime fixture corpus — is
 * that it does not. So the handset refuses, NAMES THE REMEDY, and the designer chooses; both clients
 * then agree because they are running the same parameters.
 *
 * The numbers behind it: the feasibility spike measured the flow engine at 5.7x every alternative at
 * the same size, with 13,037 of a 16,655 ms trace inside one stage, which extrapolates to 67-117 s on
 * the fleet's Galaxy M32 against 12-20 s for the adaptive engine. `DwTraceAvailability` carries both
 * ceilings and whether anybody has actually measured them.
 */
internal fun dwTraceCostRefusal(
    values: DwTraceValues,
    availability: DwTraceAvailability,
): String? {
    // No "can this phone trace at all" question is asked here any more, because there is no longer
    // one to ask — `DwTraceAvailability`'s header records what was removed and why. Everything this
    // function refuses is about HOW BIG, which is the only thing that ceiling was ever about.
    val longEdge = values.number("preprocess.workingLongEdge")?.toInt() ?: return null
    if (longEdge > availability.maxWorkingLongEdge) {
        return "This phone has been measured up to ${availability.maxWorkingLongEdge} px and the trace " +
            "resolution is set to $longEdge px. Choose a lower resolution."
    }
    if (values.choice("edge.engine") == "FDOG" && longEdge > availability.fdogMaxWorkingLongEdge) {
        // THE REMEDY NAMES THE PRESS BY ITS CURRENT NAME, and takes that name from the constant both
        // clients share rather than from a copy of it here. This sentence used to spell the
        // disclosure's OLD label out by hand, and that label stopped existing the day the two clients
        // agreed on one — a refusal that sends a designer to a control the screen does not have is
        // worse than one that names no control at all, because it reads as the application describing
        // a different version of itself. `DwSketchTraceParamsTest` fails if the old name comes back.
        return "The Flow edge engine at $longEdge px is far slower than this phone can finish in a " +
            "reasonable time — it has been measured up to ${availability.fdogMaxWorkingLongEdge} px. " +
            "Either lower the trace resolution, or choose a different edge engine under " +
            "“$DW_TRACE_DISCLOSURE_ACTION”. The portal will produce the same drawing from whichever " +
            "you choose."
    }
    return null
}

/** "3.4 seconds" / "820 milliseconds", for a sentence rather than for a table. */
internal fun dwTraceSeconds(millis: Long): String =
    if (millis < 1000L) {
        "$millis milliseconds"
    } else {
        String.format(Locale.ROOT, "%.1f seconds", millis / 1000.0)
    }

/* ────────────────────────────────────────────────────────────────────────────
 * Progress
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The stage that is running, the bar, and the way out.
 *
 * **THE LABEL IS THE PRIMARY SIGNAL AND THE BAR IS SECONDARY**, which is the order the measurements
 * imply: two of the twelve stages are most of the wall clock, so any bar will appear to stall. The
 * label is the engine's own string, rendered as sent (`trace.worker.ts:114-117`).
 *
 * A PREVIEW SHOWS NO BAR AT ALL, because there is nothing to drive one with: the vendored worker calls
 * `runPreview` without a listener (`worker/trace.worker.ts:113-118`), so no stage events are emitted.
 * A bar with no events would sit at zero and read as a hang; a working line is the truth.
 */
@Composable
private fun DwTraceProgressRow(
    kind: DwTraceRunKind?,
    progress: DwTraceProgress?,
    weights: DwTraceProgressWeights,
    stopping: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val sentence = when {
            stopping -> "Stopping…"
            // THE ENGINE'S OWN LIST, NOT THE TWELVE-ROW TABLE. `DW_TRACE_KOTLIN_STAGES` is read from
            // `Stages.ALL` at run time and is nineteen long; passing the table instead would speak a
            // stage number that is wrong for five of the seven ids the two lists share. See
            // `dwTraceProgressSentence`.
            progress != null -> dwTraceProgressSentence(progress, DW_TRACE_KOTLIN_STAGES)
            kind == DwTraceRunKind.PREVIEW -> "Tracing a preview…"
            else -> "Tracing…"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(sentence, color = MaterialTheme.field.body, fontSize = 12.sp)
        }

        if (kind != null && kind.isFullResolution && progress != null) {
            val fraction = weights.fractionAt(progress.stageId, progress.fraction)
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            if (!weights.measured) {
                // The bar is a STAGE COUNT until this device has finished one trace, and a stage count
                // will visibly stall. Saying so costs one line and stops it reading as a hang.
                Text(
                    "The bar counts stages, not time, until this phone has finished one trace.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        TextButton(
            onClick = onCancel,
            enabled = enabled && !stopping,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            // "Stopping…" and not a button that vanishes. The engine checks cancellation between
            // stages and nowhere else (`pipeline.ts:238-246`), so the worst case is the length of the
            // longest single stage — seconds at full resolution. A control that promised instant would
            // be wrong, and a control that appeared to do nothing for four seconds is worse.
            Text(if (stopping) "Stopping…" else "Stop", fontSize = 12.sp)
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * What the pipeline said, and what it produced
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **EVERY SENTENCE THE PIPELINE PRODUCED, WITHOUT EXCEPTION.**
 *
 * `pipeline.ts:46-50`: "the UI is **required** to show them. A pipeline that silently discards 4 000
 * paths and one that found nothing look identical on screen otherwise, and that ambiguity is the bug
 * class this project takes most seriously." Restated at `worker/protocol.ts:123`.
 *
 * No filtering, no de-duplication, no "only the important ones", and no truncation with a "show more".
 * The notes carry the matte's removed fraction and its alarm above 60%, the dropped-blob and
 * dropped-path counts, the "no paths were produced" remedy sentence, the downscale statement and the
 * outcome of perspective correction — and which of those matters is a judgement only the designer
 * looking at the drawing can make.
 */
@Composable
private fun DwTraceNotes(notes: List<String>) {
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        notes.forEach { note ->
            Text(note, color = MaterialTheme.field.body, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/** Paths, nodes and how long it took — the portal's own stats row (`SketchTraceField.tsx:1277-1296`). */
@Composable
private fun DwTraceStatsRow(result: DwTraceResult) {
    Text(
        "${result.shapeCount} paths · ${result.nodeCount} nodes · ${dwTraceSeconds(result.totalMillis)}",
        color = MaterialTheme.field.muted,
        fontSize = 11.sp,
    )
}

/* ────────────────────────────────────────────────────────────────────────────
 * The one disclosure
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **EVERYTHING THAT IS NOT ESSENTIAL, BEHIND ONE PRESS.**
 *
 * ── THE REPORT THIS ANSWERS ───────────────────────────────────────────────────────────────────
 *
 * The owner's words: "selecting this functionality exposes all settings simultaneously, which can
 * overwhelm the user". A 6" screen makes that worse than a laptop does rather than better, because
 * every row costs a scroll and a designer looking for the one control they came for has to read past
 * two dozen they did not. What the panel opens with is now the photograph, the frame summary, the
 * style and subject presets, the six controls that change the KIND of drawing that comes out
 * ([DW_TRACE_PRIMARY_KEYS]), the comparison, the preview controls and the two full-resolution
 * buttons. Everything else is in here.
 *
 * ── THE FOUR PROPERTIES THAT MAKE THAT SAFE RATHER THAN MERELY TIDIER ─────────────────────────
 *
 *  1. **NOTHING BECOMES UNREACHABLE, BY CONSTRUCTION.** The rows above this section and the rows
 *     inside it are selected from ONE `tier` field by opposite tests, so the two halves are
 *     exhaustive and disjoint and a control added to `DW_TRACE_CONTROLS` lands in one of them without
 *     anybody choosing. `DwSketchTraceParamsTest` asserts exactly that sum, because the way a later
 *     tidy-up loses a control is not by deleting it — it is by leaving a gap between two lists that
 *     somebody maintains by hand.
 *
 *  2. **THE COUNT IS DERIVED, AND IT COUNTS ROWS RATHER THAN TABLE ENTRIES.**
 *     [dwTraceAdvancedRevealed] drops a control whose leaf this device's engine copy did not send —
 *     the same set [dwTraceMissingKeys] reports in its own sentence further up — so the toggle cannot
 *     promise a row the press does not produce. `traceParamTable.ts:553-564` records what the other
 *     kind of number cost the portal: a button reading "Show all 32 controls" that revealed 25.
 *
 *  3. **WHAT IS HIDDEN CAN STILL ANNOUNCE ITSELF.** A folded-away control that no longer holds its
 *     preset's value says so ON THE TOGGLE — the press a designer is about to make — and names itself
 *     in a sentence underneath it. A setting quietly affecting the drawing from out of sight is the
 *     single defect class this panel is most written against.
 *
 *  4. **COLLAPSING DESTROYS NOTHING.** Every parameter lives in `DwSketchTraceOpen`'s own `params`,
 *     which is the sanitised tree the runtime handed back; these rows only read it, and the toggle
 *     writes one Boolean. There is no state under this press for a collapse to throw away — which is
 *     why this can be a plain conditional where `SketchTraceField.tsx` needed a mounted-but-hidden
 *     subtree to protect a rectangle its designer was aiming at.
 *
 * ── AND WHAT IS DELIBERATELY NOT IN HERE, WHERE THE PORTAL PUT IT ─────────────────────────────
 *
 * The portal's one disclosure swallowed its frame chooser and its download buttons too. Neither is in
 * this one. `DwTraceFramePanel` is ALREADY a single row with a summary line that is true whether it
 * is open or shut — it is the thing this section is being built to be, so nesting it would be a
 * second disclosure over a surface that has one, and unmounting it on collapse would throw away an
 * aimed rectangle for no gain. The export card is a slot on the step that writes the file. See
 * [dwTraceDisclosureBlurb], which is worded to claim neither of them.
 */
@Composable
private fun DwTraceAdvancedSection(
    values: DwTraceValues,
    availability: DwTraceAvailability,
    open: Boolean,
    changedFromPreset: Set<String>,
    /** Everything out of sight anywhere, for the sentence. Named, so a designer can go and look. */
    hiddenChanged: List<String>,
    /** Only what THIS press reveals, for the count on the toggle. The two are different questions. */
    changedBehindDisclosure: List<String>,
    enabled: Boolean,
    /** The rows are frozen mid-trace; the press is not. See the call site. */
    rowsEnabled: Boolean,
    onToggle: () -> Unit,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    val groups = remember(values) { dwTraceAdvancedGroups(values) }
    // ONE DERIVATION SITE FOR THE NUMBER, in the file that owns the table — not a second sum here
    // that could disagree with it after somebody changes what counts as a drawn row.
    val revealed = remember(values) { dwTraceAdvancedRevealed(values) }

    // The one animation on this surface, and the only thing reduced motion has to switch off. See
    // [DW_TRACE_DISCLOSURE_TURN_MS] for why the height is not animated and the chevron is.
    val stillness = LocalAppPreferences.current.reducedMotion
    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = if (stillness) 0 else DW_TRACE_DISCLOSURE_TURN_MS),
        label = "trace-advanced-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A BORDER AND A GROUND, so this reads as one section rather than as a button with some
            // loose rows under it. "An internal accordion" is the owner's own noun and a press
            // followed by unbounded content is not one — nothing on screen would say where the
            // revealed settings stop and the preview controls begin.
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (revealed == 0) {
            /*
              AN EMPTY SECTION AND A SECTION THAT IS NOT THERE ARE DIFFERENT STATES, and so are an
              empty one and a broken one. This can only happen on a build whose engine is far enough
              apart to have dropped every advanced leaf, which is the same skew the missing-keys note
              above is already reporting in detail — but a toggle offering nought settings is a
              control that does nothing, and silently omitting the section would leave a designer who
              used it on the portal unable to tell it from a feature this application lacks.
            */
            DwPanelLabel("The other settings")
            Text(
                "None of this app's other $DW_TRACE_ADVANCED_COUNT settings were offered by the " +
                    "tracing engine on this device, so there is nothing behind this section. The " +
                    "trace still runs on the settings above; this app and the engine are a version " +
                    "apart.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                /*
                  A REAL CONTROL, AND THREE SEPARATE THINGS A SCREEN READER IS OWED.

                  `mergeDescendants` makes the label, the count and the changed mark ONE announcement
                  instead of three stops on the way to the press. `stateDescription` says what the
                  section IS — "Expanded" / "Collapsed", the same two words `DesignReviewScreen.kt`
                  uses, because a reader who has met one disclosure in this application should not
                  have to learn that another calls the same state something else. `onClickLabel` says
                  what the press will DO, in the verb grammar TalkBack speaks it in. And `Role.Button`
                  is what stops it being announced as plain text with a mysterious action on it.

                  The chevron carries none of that to somebody who cannot see it, which is the whole
                  reason all three are written out.
                */
                .semantics(mergeDescendants = true) {
                    stateDescription = dwTraceDisclosureState(open)
                }
                .clickable(
                    enabled = enabled,
                    onClickLabel = dwTraceDisclosureClickLabel(open, revealed),
                    role = Role.Button,
                ) { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = turn },
            )
            Text(
                // Never assembled here. The words and the number both come from the table's own
                // file — see [DW_TRACE_PARAM_COUNT]'s header for the incident that rule exists to
                // prevent, and [DW_TRACE_DISCLOSURE_ACTION] for why the phrase is not this client's
                // to choose.
                dwTraceDisclosureLabel(open, revealed, changedBehindDisclosure.size),
                color = MaterialTheme.field.body,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        if (!open) {
            Text(
                dwTraceDisclosureBlurb(revealed),
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            // PROGRESSIVE DISCLOSURE IS ONLY HONEST IF WHAT IT HIDES CAN STILL ANNOUNCE ITSELF —
            // `traceParamTable.ts:639-645`, and the portal prints this sentence character for
            // character. The toggle carries the COUNT because a designer who has learned to skip a
            // paragraph still reads the button they are about to press; this NAMES them, because a
            // count on its own cannot be acted on.
            dwTraceHiddenChangedSentence(hiddenChanged)?.let {
                DwPanelNote(warning = false, text = it, polite = true)
            }
            return@Column
        }

        groups.forEach { (group, rows) ->
            // THE TABLE'S OWN HEADINGS, UNCHANGED. A designer looks for a control by the pipeline
            // stage it belongs to, so the taxonomy inside the disclosure is the same one outside it.
            DwPanelLabel(group)
            rows.forEach { control ->
                DwTraceControlRow(
                    control = control,
                    values = values,
                    availability = availability,
                    changed = control.label in changedFromPreset,
                    enabled = rowsEnabled,
                    onPatch = onPatch,
                )
            }
        }
        DW_TRACE_CUT.forEach { (key, why) ->
            // The cut list is DRAWN, not merely commented. Somebody looking for the thinning
            // control needs to find the answer where they looked for the control.
            Text(
                "“$key” is deliberately not offered here. $why",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * One control
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of the panel, whichever kind of control it is.
 *
 * ── A CONTROL WHOSE LEAF THE ENGINE DID NOT SEND IS NOT DRAWN AT ALL ──────────────────────────
 *
 * `read` returns null when the runtime's engine copy has no such leaf, and the row is skipped rather
 * than drawn at its minimum — see [DwTraceSlider.read]'s own header for why a plausible-looking zero
 * is the worse answer. The panel counts what it skipped with [dwTraceMissingKeys] and says so, so a
 * version skew shows up as a sentence rather than as a control that silently stops existing.
 *
 * ── EVERY HINT IS ALWAYS-VISIBLE TEXT, NEVER A TOOLTIP ────────────────────────────────────────
 *
 * A phone has no hover, and these hints are the upstream's own words and **the only documentation a
 * designer offline for a fortnight has**. The portal renders them the same way
 * (`SketchTraceField.tsx:1823-1825`), and this is one of the few places where copying it exactly is
 * the right answer rather than a starting point.
 */
@Composable
private fun DwTraceControlRow(
    control: DwTraceControl,
    values: DwTraceValues,
    availability: DwTraceAvailability,
    changed: Boolean,
    enabled: Boolean,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    // Read out here, at the function's own level, so a leaf the engine did not send can `return`
    // rather than needing a labelled escape from inside a layout lambda.
    val inactive = dwTraceInactiveReason(control, values)
    when (control) {
        is DwTraceSlider -> {
            val value = control.read(values) ?: return
            DwTraceSliderRow(control, value, changed, enabled, inactive, onPatch)
        }

        is DwTraceToggle -> {
            val on = control.read(values) ?: return
            DwTraceToggleRow(control, on, changed, enabled, inactive, onPatch)
        }

        is DwTraceChoice -> {
            val selected = control.read(values) ?: return
            DwTraceChoiceRow(control, selected, changed, enabled, inactive, onPatch)
        }

        is DwTraceNumberChoice -> {
            val value = control.read(values) ?: return
            DwTraceNumberChoiceRow(control, value, availability, changed, enabled, inactive, onPatch)
        }
    }
}

/**
 * A slider, with its value held locally while a thumb is on it.
 *
 * ── WHY THE VALUE IS NOT COMMITTED ON EVERY TICK ──────────────────────────────────────────────
 *
 * The portal patches on every change, and it can afford to: its sanitiser is a synchronous function
 * in the same page. Here every commit is a round trip into whatever runs the engine — another
 * process, in the design the feasibility spike recommends — so committing per pixel of drag would be
 * hundreds of cross-process calls for one adjustment, on a phone, on battery. The local value drives
 * the READOUT so the number under the thumb is still live; the commit happens when the thumb lifts.
 *
 * ── AND WHY THE TRACK IS 0..1 RATHER THAN THE PARAMETER'S OWN RANGE ───────────────────────────
 *
 * Because three of these sliders are not linear ([DwTraceScale]). Driving Material's `Slider` in
 * TRAVEL space and converting through [valueAt] keeps one mapping in one place, and makes a
 * square-law control and a linear one the same code path rather than two.
 */
@Composable
private fun DwTraceSliderRow(
    control: DwTraceSlider,
    value: Double,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    // Re-seeded whenever the COMMITTED value changes, so a preset that moves this control moves the
    // thumb with it — `remember(value)` and not a bare `remember`.
    var travel by remember(value) { mutableFloatStateOf(control.fractionOf(value)) }
    val live = control.valueAt(travel)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DwTraceRowHeading(control.label, dwTraceFormatValue(live, control.step), changed)
        Slider(
            value = travel,
            onValueChange = { travel = it },
            onValueChangeFinished = { onPatch(control.patch(control.valueAt(travel))) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    // The bare Slider announces a 0..1 travel figure, which is meaningless for a
                    // control whose value is "1000 pixels" or "0.35" — and doubly so on the three
                    // sliders whose travel is not linear in their value. The state description is the
                    // number the row is showing.
                    stateDescription = "${control.label}, ${dwTraceFormatValue(live, control.step)}"
                },
            enabled = enabled,
            valueRange = 0f..1f,
        )
        DwTraceRowTail(control, inactive)
    }
}

/** A toggle: the switch and its label on one row, everything else underneath. */
@Composable
private fun DwTraceToggleRow(
    control: DwTraceToggle,
    on: Boolean,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = on, onCheckedChange = { onPatch(control.patch(it)) }, enabled = enabled)
            Box(modifier = Modifier.weight(1f)) {
                DwTraceRowHeading(control.label, null, changed)
            }
        }
        DwTraceRowTail(control, inactive)
    }
}

/**
 * A one-of-many control, as chips.
 *
 * CHIPS AND NOT A DROPDOWN, because none of these lists reaches five options and
 * `SearchableSelect.kt`'s own threshold for opening a sheet is eight — "below it there is nothing to
 * search, and making the researcher cross a sheet and dismiss a keyboard to pick one of four is worse
 * than the dropdown it replaced". Chips are also already this panel's vocabulary for a short list of
 * alternatives ([DwSketchRectifyPanel]'s sheet proportions), and every one of them is a 40 dp target
 * in a `FlowRow` that wraps rather than clipping at a 200% font scale.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwTraceChoiceRow(
    control: DwTraceChoice,
    selected: String,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DwTraceRowHeading(control.label, null, changed)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            control.options.forEach { option ->
                DwPanelChip(
                    label = option.label,
                    selected = option.value == selected,
                    enabled = enabled,
                ) { onPatch(control.patch(option.value)) }
            }
        }
        DwTraceRowTail(control, inactive)
    }
}

/**
 * The trace resolution, as three named options with their cost in the row.
 *
 * An option this device has not been measured to survive is DRAWN AND DISABLED rather than hidden, so
 * a designer who used it on the portal can see both that it exists and that this phone will not do it
 * — which is a different thing from the option never having been there, and is the same choice
 * `dwTraceKotlinMemoryRefusal` makes about one trace that will not fit. The ceiling comes from
 * the runtime, which is the half that can measure it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwTraceNumberChoiceRow(
    control: DwTraceNumberChoice,
    value: Double,
    availability: DwTraceAvailability,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DwTraceRowHeading(control.label, null, changed)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            control.options.forEach { option ->
                val reachable = option.value <= availability.maxWorkingLongEdge.toDouble()
                DwPanelChip(
                    label = option.label,
                    selected = abs(option.value - value) < 0.5,
                    enabled = enabled && reachable,
                ) { onPatch(control.patch(option.value)) }
            }
        }
        control.options.firstOrNull { abs(it.value - value) < 0.5 }?.let {
            Text(it.note, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        DwTraceRowTail(control, inactive)
    }
}

/** The label, its numeric readout, and the mark saying a preset's value has been moved. */
@Composable
private fun DwTraceRowHeading(label: String, readout: String?, changed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // "Changed" is carried by the WORD and not only by a colour or a weight, so it survives
            // greyscale, colour blindness, direct sunlight on a courtyard screen, and a screen reader
            // that never sees the styling at all.
            if (changed) "$label · changed" else label,
            color = MaterialTheme.field.body,
            fontSize = 12.sp,
            fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal,
        )
        readout?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** The upstream's sentence, this client's sentence, and "this does nothing right now" if it does not. */
@Composable
private fun DwTraceRowTail(control: DwTraceControl, inactive: String?) {
    Text(control.hint, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
    control.handsetNote?.let {
        Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
    inactive?.let {
        // A SENTENCE, NOT A DISABLED ROW. Greying the control out would stop a designer setting a
        // value for the configuration they are about to switch to, and would say "you may not" where
        // the truth is "this is not being read". See [dwTraceInactiveReason].
        Text(
            "Not used by the current settings. $it",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
