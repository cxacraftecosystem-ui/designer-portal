package com.designprototype.workshop.ui.designworkshop

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable

/**
 * **THE SEAM BETWEEN THE TRACE SURFACE AND WHATEVER RUNS THE VENDORED ENGINE.**
 *
 * ── WHAT THIS FILE IS, IN ONE PARAGRAPH ───────────────────────────────────────────────────────
 *
 * Stage 11 lets a designer photograph a paper sketch and turn it into vector line art that reaches a
 * report submitted to a ministry. Both clients do it, from ONE upstream engine vendored twice: the
 * web runs `frontend/lib/trace/engine/` — 43 TypeScript files, hashed file by file in
 * `UPSTREAM-MANIFEST.txt` — and the handset runs `android/core-imaging`, `core-vector`,
 * `core-pipeline` and `core-export`, 101 Kotlin files hashed in `UPSTREAM-MANIFEST-KOTLIN.txt`.
 * This file is the boundary the handset's UI is written against, and NOTHING on the other side of it
 * is decided here: the surface takes a [DwTraceRuntime], and a runtime is whatever runs that engine.
 *
 * ── WHY THE UI IS WRITTEN AGAINST A PORT, AND WHAT THAT ALREADY BOUGHT ────────────────────────
 *
 * It was written this way while the runtime question was open, and the openness was real: the
 * feasibility spike (2026-08-27) proved the vendored engine ran unmodified on
 * `androidx.javascriptengine` — 119,837 bytes minified, one host global, geometry reproducing this
 * repository's spec fixtures to the digit — and it also found that the same upstream publishes an
 * **Android Kotlin engine held to bit-for-bit parity with that TypeScript** against shared fixtures
 * (`worker/unthrottledTimers.ts:36`, `engine/buffers.ts:3`, `engine/matte.ts:9`, `engine/index.ts:4`,
 * `engine/params.ts:727`). Both were built. The owner chose the Kotlin and the JavaScript route was
 * deleted — the bundle, the isolate, the dependency and the bridge — and **the ~8,000 lines of panel
 * above this interface did not change a line for it.** That is what the seam was for; it has been
 * paid for once and is worth keeping for the next such question.
 *
 * That is not a hedge, it is the same split the vendored engine itself argues for. `pipeline.ts:120`
 * refuses to use `AbortSignal` for cancellation because "the engine must run identically under
 * vitest, in a worker and on a JVM-shaped API, and `AbortSignal` exists in only one of those".
 * The upstream author already drew this line. This file draws it in Kotlin.
 *
 * `DwTraceKotlinRuntime` is the implementation, `DwSketchTrace.kt` mounts it, and there is no second
 * one and no fallback. A build that wanted one would add it here and change nothing above.
 *
 * ── WHAT MUST NEVER HAPPEN ON THIS SIDE OF THE SEAM ───────────────────────────────────────────
 *
 * **No Kotlin clamp table, ever.** `engine/params.ts` declares 74 leaves with 74 individually-argued
 * clamps, several of which encode a MEASURED INCIDENT rather than a taste — `xdogEpsilon = 0.08`
 * carries the longest comment in that file and a note about a terracotta pot that came back with
 * 18–66% of its body inked as tone at 0.5. `sanitizeTraceParams` is the sole authority on what is
 * legal, and it is documented idempotent (`params.ts:806-810`) precisely so a UI may run it on every
 * slider tick without ever disagreeing with the pipeline. So [DwTraceRuntime.withOverrides] is the
 * engine's own `withOverrides` and this side never merges, never clamps and never rounds a bound.
 *
 * The ranges in `DwSketchTraceParams.kt` are therefore **display ranges, not legality**: they are the
 * web panel's own narrowed maxima, carried verbatim so the two clients agree about what a designer
 * may ask for. Widening one here would let a handset ask for something the portal will not.
 *
 * ── AND WHY THE VALUES CROSS AS A FLAT MAP ────────────────────────────────────────────────────
 *
 * The tree has seven sections and one nested object (`edge.flow`). Modelling it as Kotlin data
 * classes would be a second declaration of 74 leaves, in a second language, that nothing can check
 * against the first — the same divergence `UPSTREAM-MANIFEST.txt` structurally cannot detect across
 * a language boundary. A flat `Map<String, DwTraceValue>` keyed by the SAME dot paths the web table
 * uses (`edge.flow.sigmaM`) has no opinion about the tree's shape, so a vendored update that adds a
 * leaf adds a key and breaks nothing. [DwTraceValues.wire] carries the tree itself, untouched, so
 * whatever goes back to the engine is what the engine handed over.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Values
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One leaf of the engine's parameter tree, as it crosses this boundary.
 *
 * [Absent] is not "missing". It is how `output.background: null` is spelled — the ONLY spelling of a
 * transparent export (`engine/params.ts:359`) — and it is a sealed case rather than a nullable Double
 * because a nullable would not round-trip: a `Double?` set to null is indistinguishable from a key the
 * engine never sent, and the two mean opposite things at the document stage.
 */
@Immutable
sealed class DwTraceValue {
    data class Num(val value: Double) : DwTraceValue()

    data class Flag(val value: Boolean) : DwTraceValue()

    /** A string enum, or `styleId`. The engine's enums have values equal to their names. */
    data class Choice(val value: String) : DwTraceValue()

    /** `null` — today only `output.background`. See the class header. */
    data object Absent : DwTraceValue()
}

/**
 * The sanitised parameter tree, flattened for reading and carried whole for sending back.
 *
 * TWO REPRESENTATIONS OF ONE THING, AND THE SECOND IS THE AUTHORITY. [leaves] exists so a slider can
 * read its own value in one map lookup. [wire] is the tree exactly as the runtime produced it, and it
 * is what goes back — so a leaf this Kotlin has never heard of survives a round trip untouched
 * instead of being silently dropped by a re-serialisation from [leaves].
 */
@Immutable
class DwTraceValues(
    private val leaves: Map<String, DwTraceValue>,
    /** The whole sanitised tree, opaque to this side. Handed straight back to the runtime. */
    val wire: String,
) {
    operator fun get(key: String): DwTraceValue? = leaves[key]

    fun number(key: String): Double? = (leaves[key] as? DwTraceValue.Num)?.value

    fun flag(key: String): Boolean? = (leaves[key] as? DwTraceValue.Flag)?.value

    fun choice(key: String): String? = (leaves[key] as? DwTraceValue.Choice)?.value

    /**
     * True when the leaf holds a value rather than the engine's `null`.
     *
     * This is what the "White background" toggle reads, mirroring the web's
     * `read: (p) => p.output.background !== null`.
     */
    fun present(key: String): Boolean {
        val leaf = leaves[key] ?: return false
        return leaf != DwTraceValue.Absent
    }

    val keys: Set<String> get() = leaves.keys

    /** The style this tree belongs to. `preset()` forces it, so it cannot lie (`styles.ts:46-54`). */
    val styleId: String get() = choice(DW_TRACE_STYLE_ID_KEY).orEmpty()
}

/** The key `TraceParams.styleId` flattens to. Written into anything persisted (`styles.ts:15-17`). */
const val DW_TRACE_STYLE_ID_KEY: String = "styleId"

/* ────────────────────────────────────────────────────────────────────────────
 * Presets, as the engine's own tables
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One entry of `engine/styles.ALL` or `engine/subjects.ALL`.
 *
 * READ FROM THE ENGINE AT RUN TIME, NEVER TRANSCRIBED HERE. The twenty style ids are binding — they
 * are written into `TraceParams.styleId` and therefore into anything persisted — so a shortened or
 * re-typed list on one client cannot open the other client's saved trace. A hand-copied table would
 * also be a second copy of somebody else's register, which is the failure mode this repository has
 * already shipped twice; the frontend skill file's own dashboard-tile list carried eleven rows of
 * twenty for months, and "the honest reading of a missing tile was 'this tile is not expected'".
 *
 * [group] is empty for a subject; subjects are a flat list, and **how long that list is depends on
 * which runtime is wired.** The TypeScript register has ten (`subjects.ts:89`), the vendored Kotlin
 * one has twelve (`Subjects.kt:408-421`), and `DwTraceKotlinPresets.kt` states which it takes, why,
 * and what it says on screen about the difference. Nothing here counts them.
 */
@Immutable
data class DwTracePreset(
    val id: String,
    val name: String,
    /** The upstream's own sentence. Rendered under the row, never as a tooltip — a phone has no hover. */
    val description: String,
    val group: String,
)

/** Both tables, in the engine's own order. `groups()` order for styles; FEATURES.md §8 for subjects. */
@Immutable
data class DwTracePresetTables(
    val styles: List<DwTracePreset>,
    val subjects: List<DwTracePreset>,
)

/* ────────────────────────────────────────────────────────────────────────────
 * A run
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Why a trace is being run — which decides the resolution and what happens to the answer.
 *
 * ONE IN-FLIGHT RUN COVERS ALL OF THESE, and the panel holds exactly one busy flag for them. The web
 * learned that the hard way: `SketchTraceField.tsx:223-231` records what two independent busy flags
 * cost — "the loser would report 'the trace did not finish' while the winner quietly succeeded".
 */
enum class DwTraceRunKind {
    /**
     * A small, fast trace to look at while tuning. Emits NO progress events — the vendored worker
     * calls `runPreview` without a listener (`worker/trace.worker.ts:113-118`), so there is nothing to
     * drive a bar with and the surface shows a working line instead of one.
     */
    PREVIEW,

    /** Full resolution, because the answer is about to be attached to the record. */
    ATTACH,

    /** Full resolution, because the answer is about to be written out as a file the designer keeps. */
    EXPORT,
}

/** True for the two kinds that run at full resolution and therefore report progress. */
val DwTraceRunKind.isFullResolution: Boolean get() = this != DwTraceRunKind.PREVIEW

/** What the designer asked for. */
@Immutable
data class DwTraceRequest(
    /** The photograph on the record, already copied into the workshop's media directory. */
    val photographPath: String,
    val params: DwTraceValues,
    val kind: DwTraceRunKind,
    /**
     * Which REGION of the decoded photograph the engine is handed, or null for all of it.
     *
     * A TRACE INPUT AND NOT AN EDIT. Nothing about this writes to the record: the photograph is
     * untouched, the crop is taken afresh from the whole decode on every run, and widening the frame
     * back out is therefore always possible. `DwSketchTraceCrop.kt`'s header states the three rules
     * that make it a trace input rather than a file — docs/MEDIA_PIPELINE.md section 5, stage 11's
     * single image slot, and the registry change a second slot would need.
     *
     * It carries the frame it was aimed in, because a rectangle without one cannot be re-scaled, and
     * the decode this meets is allowed to come back at a size the panel did not predict.
     */
    val frame: DwTraceFrameChoice? = null,
    /**
     * The long edge both display plates come back at.
     *
     * NOT NEGOTIABLE ON A HANDSET, and 1024 is the web's own number (`comparisonPlates.ts:59`). Two
     * 1024x1024 ARGB_8888 bitmaps are 8.4 MB, which is affordable; the full-resolution pair at 4096 is
     * ~134 MB, and `comparisonPlates.ts:55-57` already names three copies of one big buffer as how a
     * 2 GB handset kills the page. On the web that is a slow tab. Here it is an OOM in a courtyard.
     */
    val plateLongEdgePx: Int = DW_TRACE_PLATE_LONG_EDGE_PX,
)

/** See [DwTraceRequest.plateLongEdgePx]. Mirrors `COMPARISON_LONG_EDGE_PX`. */
const val DW_TRACE_PLATE_LONG_EDGE_PX: Int = 1024

/** One stage boundary. Mirrors the client-side `TraceProgress` field for field (`traceClient.ts:117`). */
@Immutable
data class DwTraceProgress(
    /** One of [DW_TRACE_STAGES]' ids. "Stable: the UI keys its progress rows on them" (`pipeline.ts:210`). */
    val stageId: String,
    /** The engine's own label. Rendered as sent — see [DW_TRACE_STAGES] for why it is never reworded. */
    val label: String,
    /** `index / 12` at the START of the stage. Never reaches 1.0; the last event is 0.917. */
    val fraction: Float,
)

/** One measured stage. Mirrors `SerializedStage` (`worker/protocol.ts:49-54`). */
@Immutable
data class DwTraceStageTiming(val id: String, val label: String, val millis: Long)

/**
 * What a finished trace hands back.
 *
 * The two bitmaps are DISPLAY PLATES and neither of them may ever reach the record — the same refusal
 * `comparisonPlates.ts:9-42` states for the web, and docs/MEDIA_PIPELINE.md §5 states for this app.
 * What gets attached is [svg], which is the engine's own writer output and has been through no canvas.
 */
@Immutable
class DwTraceResult(
    /**
     * The vector document, as the engine's own writer spelled it.
     *
     * THIS STRING IS THE ARTEFACT. The cross-runtime parity harness compares it EXACTLY
     * (`frontend/e2e/trace-parity-unit.spec.ts`, its tier 2) because what reaches the ministry is a
     * string, so nothing on this side may re-print, re-indent or "tidy" it.
     */
    val svg: String,
    /**
     * The shapes themselves, kept so the export can paint a picture of them. Null when a host has none.
     *
     * ── WHY THE RESULT HOLDS THIS AT ALL, WHICH IS A COST AND NOT A CONVENIENCE ───────────────
     *
     * It used to be discarded the moment the plates were painted. The PNG export cannot be built from
     * anything else: the SVG is the artefact and re-parsing it would need an SVG reader on this side,
     * which is a second opinion about the one string the cross-runtime parity harness compares
     * exactly; and the display plates are 1024 px, forced onto white, and forbidden from reaching a
     * file. So a picture of the drawing means the drawing, and this is where it lives between the run
     * that produced it and the button that saves it.
     *
     * WHAT IT COSTS, AS ARITHMETIC RATHER THAN AS A GUESS. [DwTraceGeometry] is flat arrays: the
     * coordinates dominate at four bytes each, and its own KDoc puts a 50,000-path trace at "roughly a
     * million coordinates" — so the worst case this feature admits is about 4 MB retained, against the
     * 8.4 MB of display plates the same result already holds and the 16.8 MB the PNG itself allocates
     * while it is being written. It is retained for as long as a result is on screen and released with
     * it, on the same event that drops the plates.
     *
     * NULLABLE BECAUSE THE EXPORT CARD IS DELIBERATELY COMPOSABLE WITHOUT ONE, not because a run can
     * fail to produce it — `DwTraceDecoded.geometry` is not null and the one construction site fills
     * this from it. A host that has no geometry to give gets [DW_TRACE_NO_GEOMETRY_SENTENCE] and the
     * SVG door, which still works.
     */
    val geometry: DwTraceGeometry?,
    /**
     * The trace, rendered for the comparator, PAINTED ON OPAQUE WHITE — or null.
     *
     * The white belongs to the COMPARISON and not to the export, and it is not optional:
     * `output.background` defaults to null, and a transparent AFTER layer stacked over the photograph
     * shows the photograph through both layers — the divider then moves and nothing changes, which is
     * indistinguishable from a broken slider (`comparisonPlates.ts:21-27`).
     *
     * **NULLABLE, AND THAT IS THE WHOLE POINT.** A plate is a display artefact and [svg] is the
     * archive one, so a plate that could not be built must cost the comparison and nothing else. It
     * used to cost the run: the plates were assembled inside `runTrace`, so a failed allocation threw
     * and discarded a finished drawing on the device least able to spare 4.2 MB. When this is null so
     * is [photographPlate], and [plateRefusal] says why in a sentence written to be read.
     */
    val tracePlate: Bitmap?,
    /**
     * The photograph, from THE DECODED PIXELS THE ENGINE WAS HANDED, at the same size as [tracePlate].
     *
     * Not from the file a second time. Two decoders hold different EXIF opinions, so one layer can
     * arrive rotated and the other upright — which reads on screen as "the trace came out sideways".
     * These are also the pixels the engine actually traced, which is what the comparison is about.
     *
     * Null exactly when [tracePlate] is null. The two are built together or not at all — a comparator
     * with one layer is not a comparator.
     */
    val photographPlate: Bitmap?,
    /**
     * Why there are no plates, for a trace that itself succeeded. Empty when there are.
     *
     * A SENTENCE AND NOT A FLAG, for `DwTraceOutcome.Refused`'s reason: a sentence that has to be
     * printed is a value. The panel prints it where the comparator would have been, and the drawing
     * above it stays attachable — which is what the web has always done
     * (`SketchTraceField.tsx:757-780`, where a refusal only ever sets `compareProblem`).
     */
    val plateRefusal: String,
    /** The document's own frame: the source size, or the rectified page. */
    val width: Int,
    val height: Int,
    /** The resolution the trace RAN at. Smaller than [width] for a preview, and that must be stated. */
    val workingWidth: Int,
    val workingHeight: Int,
    val shapeCount: Int,
    val nodeCount: Int,
    val stages: List<DwTraceStageTiming>,
    val totalMillis: Long,
    /**
     * **EVERY SENTENCE, RENDERED WITHOUT EXCEPTION.**
     *
     * `pipeline.ts:46-50`: "the UI is **required** to show them. A pipeline that silently discards
     * 4 000 paths and one that found nothing look identical on screen otherwise, and that ambiguity is
     * the bug class this project takes most seriously." Restated at `worker/protocol.ts:123`.
     */
    val notes: List<String>,
    /**
     * The parameters the stages ACTUALLY RAN WITH, which is not always the ones that were sent.
     *
     * Auto-detection runs before the first stage. Rendering the panel from the request instead would
     * leave a dock that says one thing beside a drawing produced by another (`protocol.ts:139-149`).
     */
    val appliedParams: DwTraceValues,
    /** The subject the engine applied by itself, or empty. Stated on screen when non-empty. */
    val autoSubjectId: String,
    /**
     * The style the engine's classifier suggests for this photograph, or empty on a preview.
     *
     * `SerializedProfile.suggestion` is a styles preset id and is never empty on a full trace
     * (`engine/classify.ts:82-83`). **The web computes it, ships it and renders it nowhere**
     * (`grep -n suggestion SketchTraceField.tsx` → nothing, verified 2026-08-27). The handset renders
     * it: it is ALGORITHMS §12's "named suggestion with a one-tap override", already paid for.
     */
    val suggestedStyleId: String,
    /**
     * What was done to the photograph before the engine saw it, as a clause for the exported file's
     * provenance note. Empty when the whole photograph was traced.
     *
     * **THIS IS THE CHANNEL `dwTraceProvenanceNote(frameNote = …)` WAS DECLARED FOR AND HAS NEVER
     * BEEN GIVEN.** That parameter's own docblock said why it was empty — there was no crop on this
     * client — and now there is, so `DwSketchTraceExportCard` has something true to pass. The
     * sentence is built by [dwTraceCropNote] beside the arithmetic that produced it, in the web's
     * exact words, because a handset's drawing and the portal's drawing land in one archive and a
     * reviewer holding both should not have to decide whether two phrasings mean two operations.
     */
    val frameNote: String = "",
) {
    /** True when the trace ran below full resolution — the sentence the panel must print. */
    val isPreview: Boolean get() = workingWidth < width || workingHeight < height

    /** True when both plates are here, i.e. when there is a comparison to show. */
    val hasPlates: Boolean get() = tracePlate != null && photographPlate != null
}

/** Done, or refused in a sentence. Cancellation is NEITHER — see [DwTraceRuntime.trace]. */
sealed class DwTraceOutcome {
    data class Done(val result: DwTraceResult) : DwTraceOutcome()

    /**
     * The trace could not run, in one sentence a designer can act on.
     *
     * A REFUSAL IS NOT AN EXCEPTION here for the same reason it is not one in `DwSketchRectify`'s
     * `DwPlateResult.Refusal`: the caller has to print it, and a sentence that has to be printed is a
     * value. Exceptions are for the cases nobody wrote a sentence for.
     */
    data class Refused(val reason: String) : DwTraceOutcome()
}

/* ────────────────────────────────────────────────────────────────────────────
 * What this handset can actually do
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the runtime on THIS phone will and will not do, measured rather than assumed.
 *
 * ── WHY THE TWO CEILINGS ARE HERE AND NOT IN THE UI ───────────────────────────────────────────
 *
 * The spike measured, on a laptop's V8 at the product's own input cap of 1600x1200: a full trace is
 * **2.9 s with ADAPTIVE and 16.7 s with the shipped FDOG default**, because FDOG is 5.7x everything
 * else and 13,037 of those 16,655 ms are in one stage. Scaled to the fleet's Galaxy M32 that is
 * roughly 12–20 s against 67–117 s. **The desktop-to-handset factor was reasoned from published
 * single-thread figures, not measured on a device** (there was no handset and no emulator on the
 * machine the spike ran on), and it is the single number that decides whether this ships.
 *
 * So the ceilings are a property of the runtime, which is the half that can measure them, and
 * [measuredOn] is the sentence that says whether anybody has. A UI that hard-coded 2048 would be
 * writing an unmeasured claim into a screen, which is exactly what docs/DEVICE-TIER-MEASUREMENT.md
 * exists to stop; `DwDeviceTier.kt:1115` records the one phone this fleet is characterised by and
 * `DwDictationLadder.kt:430` says out loud that one phone is one data point.
 *
 * ── THIS IS ONLY ABOUT HOW BIG, NEVER ABOUT WHETHER — AND IT USED NOT TO BE ───────────────────
 *
 * There were two more fields here, `canTrace: Boolean` and `refusal: String?`, and they answered a
 * question that no longer has two answers. The tracer used to be a JavaScript bundle in an
 * `androidx.javascriptengine` isolate, which needed the bundle to be in the APK and needed
 * `JavaScriptSandbox.isSupported()` to say yes — and that check needs an Android System WebView at
 * Chromium M97 or newer, updated through Play, on a product whose premise is a handset that has been
 * in a village for a fortnight. On such a phone the tracer DID NOT EXIST, so a runtime had to be able
 * to hand the panel an explanation instead of a feature.
 *
 * The engine is now `:core-imaging`, `:core-vector`, `:core-pipeline` and `:core-export`, compiled
 * into the APK. **If the app runs, it traces.** A boolean that is true on every device and a refusal
 * string that is null on every device are not a gate; they are two fields every reader has to check
 * before they can conclude nothing happens, and a sentence about updating WebView that no phone can
 * reach is a false apology waiting to be shown by a future edit. Both are gone, along with
 * `DwTraceRuntimeUnavailable`, which existed only to carry them.
 *
 * What can still stop ONE trace is memory, and that is answered per trace against the frame actually
 * being traced — `dwTraceKotlinMemoryRefusal`, which runs after the decode and before the first stage
 * and names both numbers in its sentence. That is a better answer than a field here could ever be: a
 * phone with a full heap at four o'clock is not a phone that cannot trace.
 *
 * ── AND WHY FDOG GETS A CEILING OF ITS OWN RATHER THAN A SUBSTITUTION ─────────────────────────
 *
 * The obvious fix — quietly swap FDOG for ADAPTIVE on a phone — is the worst available option and the
 * one this repository exists to prevent: one sheet of paper would then produce two different drawings
 * depending on which client traced it, and the vendored engine's whole discipline is that it does not.
 * So the handset REFUSES and names the remedy, the designer chooses, and both clients then agree
 * because they are running the same parameters. See `DwSketchTraceParams.kt`'s cut list.
 */
@Immutable
data class DwTraceAvailability(
    /** The largest `preprocess.workingLongEdge` this device has been measured to survive. */
    val maxWorkingLongEdge: Int,
    /** The largest working long edge the FDOG edge engine may run at here. See the class header. */
    val fdogMaxWorkingLongEdge: Int,
    /**
     * What the two ceilings were measured on and when, e.g. "Galaxy M32 (SM-M325F), Android 13,
     * 2026-08-27". **Null means nobody has measured them and they are conservative guesses** — which
     * the panel says out loud rather than presenting a guess as a limit.
     */
    val measuredOn: String?,
)

/* ────────────────────────────────────────────────────────────────────────────
 * The runtime
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Whatever runs the vendored engine on this phone.
 *
 * ── CANCELLATION IS KOTLIN'S, AND THAT IS A DELIBERATE PORT OF THE ENGINE'S OWN CHOICE ────────
 *
 * [trace] is a `suspend fun`, so cancelling it is cancelling its job. There is no handle object, no
 * `cancel()` method and no second flag, because the vendored `CancellationToken`
 * (`pipeline.ts:124-138`) is deliberately not an `AbortSignal` — "the engine must run identically
 * under vitest, in a worker and on a JVM-shaped API". A structured-concurrency job IS the JVM-shaped
 * API that comment is describing.
 *
 * **A cancelled trace throws `CancellationException` and MUST NOT be reported as a failure.**
 * `worker/trace.worker.ts:156-157` states it for the web — a cancel "must never reach the user as
 * one" — and the panel honours it by never turning a `CancellationException` into an error line.
 *
 * **Cancellation is granular between stages and NOWHERE ELSE.** The engine checks exactly once, in
 * `RunContext.begin` (`pipeline.ts:238-246`). Worst-case latency is therefore the duration of the
 * longest single stage — `edge` or `vectorize`, seconds on a phone at full resolution. A runtime is
 * free to be faster (killing a JavaScript isolate is immediate) but the SURFACE must not promise
 * instant, which is why it says "Stopping…" rather than disappearing.
 *
 * ── SUPERSEDING ───────────────────────────────────────────────────────────────────────────────
 *
 * A second [trace] while one is in flight is the caller's job to sequence: cancel, then launch. The
 * web's worker does this itself and posts NOTHING for the superseded id (`trace.worker.ts:78-88`),
 * which is why `traceClient.ts:172-186` has to settle the older promise locally — without that it
 * leaks a promise, a progress closure and a listener PER DRAG TICK OF A SLIDER. A cancelled coroutine
 * cannot leak that way, which is one more reason this port is shaped like a suspend function.
 *
 * ── IMPLEMENTATIONS MUST BE MAIN-SAFE ─────────────────────────────────────────────────────────
 *
 * Every method here is called from a composable's scope, which is the main thread. A trace is seconds
 * of solid arithmetic, so an implementation that ran it on the caller's dispatcher would be an ANR.
 * `withContext` belongs inside the implementation, exactly as `DwSketchRectify`'s callers do it.
 */
interface DwTraceRuntime {

    /** What this phone can do. Cheap and synchronous — the panel reads it while composing. */
    val availability: DwTraceAvailability

    /** `engine/styles.ALL` and `engine/subjects.ALL`, read from the engine rather than transcribed. */
    suspend fun presets(): DwTracePresetTables

    /**
     * `defaultTraceParams()`, sanitised.
     *
     * NOTE FOR WHOEVER IMPLEMENTS THIS: the shipped default carries `edge.engine = FDOG` and
     * `auto.mode = SUGGEST`. Neither is changed here — the default tree is the engine's to state — but
     * the panel bars FDOG above [DwTraceAvailability.fdogMaxWorkingLongEdge] and asks for
     * `auto.mode = OFF` on previews. Both arguments are in `DwSketchTraceParams.kt`.
     */
    suspend fun defaults(): DwTraceValues

    /**
     * The engine's own `withOverrides(base, patch)` — a merge followed by `sanitizeTraceParams`.
     *
     * ONE CALL, NOT TWO, and no Kotlin merge in between. The web splits them (`mergeParams` locally,
     * sanitise in the engine) only because a static value import of `engine/params` would put ~28 KB
     * of engine source into a page bundle. No such cost exists here, so the copy does not exist here
     * either — and a copy of somebody else's merge rule is one more thing that drifts.
     */
    suspend fun withOverrides(base: DwTraceValues, patch: Map<String, DwTraceValue>): DwTraceValues

    /**
     * Apply a style preset: `styles.byId(styleId).params`, whole.
     *
     * A STYLE IS A COMPLETE TREE, NOT A DIFF (`styles.ts:19-21`): "a user who switches styles expects
     * the second one to look like itself rather than like a blend of the two". So this does not merge
     * onto [base] — [base] is passed only so an implementation can keep what a style legitimately does
     * not name.
     */
    suspend fun applyStyle(base: DwTraceValues, styleId: String): DwTraceValues

    /**
     * Apply a subject preset: `subjects.byId(subjectId).adjust(base)`.
     *
     * A SUBJECT IS A MODIFIER ON A STYLE, NOT A SECOND STYLE LIST (`subjects.ts:21-23`) — it nudges
     * denoise, blob area and engine choice for the MATERIAL while leaving the look the style chose
     * intact. `adjust` is documented idempotent (`subjects.ts:41-43`), which is what lets the panel
     * re-apply it after any edit without compounding.
     */
    suspend fun applySubject(base: DwTraceValues, subjectId: String): DwTraceValues

    /**
     * Run one trace, reporting each stage boundary.
     *
     * [onProgress] is called on the main thread, once per stage, and NEVER for a
     * [DwTraceRunKind.PREVIEW] — the vendored worker passes no listener to `runPreview`.
     *
     * **HOW MANY STAGES THERE ARE IS THE RUNTIME'S TO SAY, NOT THIS PORT'S.** It was twelve here
     * while the JavaScript route was the only one; the vendored Kotlin engine reports nineteen for
     * the same trace, because it separates steps the TypeScript fuses. `DW_TRACE_KOTLIN_STAGES` in
     * `DwTraceKotlinRuntime.kt` states which is which and what the difference costs the progress UI.
     *
     * @throws kotlinx.coroutines.CancellationException when the job is cancelled. Not a failure.
     */
    suspend fun trace(
        request: DwTraceRequest,
        onProgress: (DwTraceProgress) -> Unit,
    ): DwTraceOutcome
}

/* ────────────────────────────────────────────────────────────────────────────
 * The one sentence left about the runtime itself
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence for a panel that finished loading and has no parameters to draw.
 *
 * ── WHAT REPLACED WHAT, SO NOBODY RESTORES THE OLD ONE ────────────────────────────────────────
 *
 * `DW_TRACE_NO_RUNTIME_SENTENCE` stood here and said the tracer was "not available on this phone
 * yet". That was true of a build whose engine was a JavaScript bundle needing a WebView at Chromium
 * M97, and it is false of this one: the engine is four Gradle modules inside the APK, so there is no
 * phone this app installs on that cannot trace. A sentence apologising for a device is worse than no
 * sentence when the device is not the problem — it sends a designer to update something that will not
 * change the answer, and it is not recoverable by anything they can do in a courtyard.
 *
 * ── WHEN THIS IS ACTUALLY REACHED, WHICH IS ALMOST NEVER ──────────────────────────────────────
 *
 * `DwSketchTracePanel` shows it in exactly one state: the load coroutine ran to completion, threw
 * nothing, and left `params` or `presets` null. [DwTraceRuntime.defaults] and [DwTraceRuntime.presets]
 * both read compiled-in tables and neither can return null, so on this build the state is
 * unreachable — the honest reading is that it is a backstop for a future runtime, not a description
 * of anything a designer meets today. A thrown failure takes the other branch and is reported with
 * the reason it carried, which is a better sentence than this one and is preferred wherever there is
 * one.
 *
 * SO IT BLAMES NOTHING AND NAMES WHAT SURVIVES. It cannot know what went wrong — that is the whole
 * shape of the state — so it does not guess at a cause, and it says the two things that are true
 * whatever the cause: the photograph is untouched, and trying again is free.
 */
const val DW_TRACE_ENGINE_SILENT_SENTENCE: String =
    "The tracing controls did not come back this time, and nothing has said why. Close this and open " +
        "it again — the photograph and the straightened plate are untouched either way."
