package com.designprototype.workshop.ui.designworkshop

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * **THE EXECUTION LAYER FOR [DwTraceRuntime], MINUS EVERYTHING THAT NEEDS A DEVICE.**
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * READ THIS FIRST: HALF OF THIS FILE IS NO LONGER REACHED, AND WHICH HALF IS NAMED HERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * This file was written as the core of a JavaScript bridge. **That bridge is deleted** — the minified
 * engine bundle in `assets/`, the `androidx.javascriptengine` dependency, `DwSketchTraceSandbox.kt`
 * which was the only file naming it, and the `DwSketchTraceRuntime` that drove it. The handset now
 * runs the engine as compiled Kotlin (`DwTraceKotlinRuntime` over `:core-pipeline`), which is the
 * owner decision the section below this one used to describe as open.
 *
 * WHAT IS STILL LIVE HERE, and is used by the Kotlin runtime, the panel and the export card:
 * [DwTraceFailureKind] and [dwTraceSentence] for the failures that are about an IMAGE rather than a
 * bridge; the geometry mirror ([DwTraceGeometry], [DwTraceStyle], the three verb codes); the
 * marshalling in [dwTraceArgbRowToRgba]; [DwTraceDecoded]; [dwTraceWorkingSize] and
 * [DW_TRACE_DECODE_MAX_EDGE_PX]; [dwTraceCeilings]; and the JSON codecs [dwTraceValuesOf] and
 * [dwTracePatchJson], which `DwTraceKotlinParams.kt` uses to move a parameter tree between the
 * panel's flat shape and the engine's nested one.
 *
 * WHAT IS NO LONGER REACHED FROM ANYTHING THAT SHIPS, and is left in place rather than deleted
 * because this file was outside the port's remit: [DwTraceJsHost], [DwTraceSession] and its envelope
 * and pump machinery ([DwTraceEnvelope], [DW_TRACE_MAX_PUMPS], [DW_TRACE_CANCEL_PUMPS],
 * [dwTraceReadEnvelope]), the bundle contract ([DW_TRACE_CONTRACT], [DW_TRACE_GLOBAL],
 * [DW_TRACE_BUNDLE_ASSET], [DW_TRACE_IMAGE_DATA_NAME], [DW_TRACE_PROTOCOL_NOTE], [DwTraceHello]),
 * [dwTraceHeapCapBytes], and the four failure kinds that describe a bridge —
 * [DwTraceFailureKind.SANDBOX_UNSUPPORTED], [SANDBOX_DIED][DwTraceFailureKind.SANDBOX_DIED],
 * [BUNDLE_MISSING][DwTraceFailureKind.BUNDLE_MISSING] and
 * [BUNDLE_CONTRACT_MISMATCH][DwTraceFailureKind.BUNDLE_CONTRACT_MISMATCH]. **No designer can see any
 * of their sentences**, because nothing constructs them. `DwSketchTraceSessionTest` still exercises
 * the protocol against a fake host, so it is verified dead code rather than unverified dead code.
 * Deleting it is a decision for whoever owns this file next; the reason it is flagged here rather
 * than quietly left is that every paragraph below describing "what the handset does" is describing
 * what it did until this port.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Nothing in this file imports `android.*` or Compose. That is not tidiness, it is the only way any
 * of it can be tested: `app/build.gradle.kts` declares `testImplementation("junit:junit:4.13.2")` and
 * nothing else — no Robolectric — so anything that touches `android.graphics` is by construction code
 * no unit test on this machine can reach. `DwSketchPlate.kt` states the same split for the rectify
 * feature in the same words, and it is why the marshalling, the geometry mirror and every failure
 * sentence live HERE.
 *
 * The files this one is the core of:
 *
 *  - `DwSketchTracePlates.kt` — the ONLY file that knows what a `Bitmap` is. It hands this file an
 *    `IntArray` of packed ARGB and takes back a [DwTraceGeometry] to draw.
 *  - `DwSketchTrace.kt` — the mount, the decoder and the plate builder.
 *  - `DwTraceKotlinRuntime.kt` — the [DwTraceRuntime] implementation, over the vendored Kotlin engine.
 *  - `DwSketchTraceEngine.kt` — NOT ours. It is the port that implements against, written by the
 *    surface wave; every type it declares is used here rather than redeclared.
 *
 * ── WHAT RUNS THE ENGINE, AND WHY NEITHER CLIENT HAND-WROTE IT ────────────────────────────────
 *
 * ONE upstream, vendored twice. `frontend/lib/trace/engine/` is 43 TypeScript files and 16,557 lines
 * with a SHA-256 per file in `UPSTREAM-MANIFEST.txt`; `android/core-imaging`, `core-vector`,
 * `core-pipeline` and `core-export` are 101 Kotlin files with a SHA-256 per file in
 * `android/UPSTREAM-MANIFEST-KOTLIN.txt`. Neither is a hand-written port, and that is the whole
 * discipline: a port would fork a numerical library into a second language — Otsu, Canny, Bézier
 * fitting, thinning, morphology — where two independent implementations do not agree to the digit
 * about the line art printed in a ministry document.
 *
 * Two vendorings are still two floating-point implementations, so the thing that holds them together
 * is not a manifest but a test: `:core-pipeline`'s own `ParityTest` replays the shared fixtures under
 * `docs/fixtures/` through the Kotlin engine and asserts the TypeScript's numbers. Upstream states
 * the same guarantee from its side — `worker/unthrottledTimers.ts:36` ("The engine is shared with the
 * Kotlin client and is held to bit-for-bit parity against the JSON fixtures under that repository's
 * `docs/fixtures`" — the glob is spelled out rather than written, because a glob inside a block
 * comment ends the comment, which `app/build.gradle.kts` records having already eaten one paragraph
 * of this repository's prose), and again at `engine/buffers.ts:3`, `engine/matte.ts:9`,
 * `engine/index.ts:4` and `engine/params.ts:727`.
 *
 * ── THE ONE THING THIS LAYER MAY NEVER DO ─────────────────────────────────────────────────────
 *
 * **It proposes; it never writes.** A traced document is a machine-produced value, and this
 * repository's rule for those is written out at `MainActivity.kt:4801-4822` and enforced in the two
 * other places it names: `DwPhotoMeasureField`, under a heading reading "IT NEVER WRITES A DIMENSION
 * BY ITSELF" — *"the number is a proposal from an inference a person can check against the object in
 * their hands"* — and the web's `IdentityCardReader`, *"The ONLY write in the whole OCR path, and it
 * happens because a person read the candidate against the card in their hand and pressed Confirm."*
 * The reason is not taste: `records.merge_field_provenance` stamps every changed field with the
 * `{by, byName, at}` of the account that pressed Save, so a machine value written without a press
 * makes the record positively assert that A NAMED HUMAN produced it. Nothing in these four files
 * opens a record, attaches a file, or touches `sketch.lineArtFile`. [DwTraceOutcome.Done] is a value
 * handed back for a person to look at, and the button that accepts it belongs to the panel.
 */

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 1. FAILURE — every kind gets its own sentence, and the sentences are the API
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The ways a trace can fail on a handset. **One kind per remedy, never one per exception class.**
 *
 * This repository forbids "Something went wrong": a designer four days from a connection cannot act
 * on it, and neither can whoever reads the screenshot afterwards. The test that keeps this honest is
 * `DwSketchTraceFailureTest` — it asserts every kind produces a DIFFERENT sentence, that none is
 * generic, and that each names something the reader can do next.
 */
enum class DwTraceFailureKind {
    /**
     * `JavaScriptSandbox.isSupported()` said no.
     *
     * It needs a WebView provider at Chromium M97 (January 2022) or newer. WebView updates arrive
     * through Play, and this product's premise is a handset that has been in a village for a
     * fortnight — so this is a real state, not a theoretical one, and the remedy (get signal, let
     * Play update WebView) is something the designer can actually do, later, deliberately.
     */
    SANDBOX_UNSUPPORTED,

    /**
     * The sandbox started but will not take a binary buffer
     * (`JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER`) or will not resolve a promise
     * (`JS_FEATURE_PROMISE_RETURN`).
     *
     * A separate kind from [SANDBOX_UNSUPPORTED] because the remedy is the same but the diagnosis is
     * not, and a bug report that says "unsupported" about a sandbox that started is a bug report
     * nobody can act on. Both features predate M97, so reaching this means something stranger than an
     * old WebView and the sentence asks for the model.
     */
    SANDBOX_FEATURE_MISSING,

    /**
     * The sandbox process died mid-trace.
     *
     * It is bound to this app's importance, so a designer who switched apps during a fifteen-second
     * trace can land here. The sentence names the thing that works: keep the screen on.
     */
    SANDBOX_DIED,

    /**
     * The isolate hit its heap cap, or the phone could not give it one.
     *
     * The spike measured a full-resolution trace wanting +93 MB with ADAPTIVE and **+278 MB with
     * CANNY**. Both remedies are in the designer's hands and both are named.
     */
    OUT_OF_MEMORY,

    /**
     * The finished geometry was larger than the isolate may hand back in one evaluation.
     *
     * `IsolateStartupParameters.DEFAULT_MAX_EVALUATION_RETURN_SIZE_BYTES` is 20 MiB and the spike's
     * worst measured result was 2.88 MiB (CANNY at 1600x1200, 20,975 shapes), so this needs a trace
     * seven times larger than anything measured. It gets its own sentence anyway, because the remedy
     * — fewer shapes, i.e. a bigger "Minimum speck" — is completely different from the remedy for
     * [OUT_OF_MEMORY], and a designer told the wrong one will try the wrong thing and conclude the
     * feature is broken.
     */
    RESULT_TOO_LARGE,

    /** The engine itself refused. Its own sentence is carried through verbatim in `detail`. */
    ENGINE_ERROR,

    /**
     * The trace succeeded, in a frame that is not the photograph's, so the comparator cannot lay the
     * two pictures over each other.
     *
     * `comparisonPlates.ts:9-42`'s third decision is that both plates are the same size and that *"a
     * mismatch beyond a rounding pixel is a REFUSAL rather than an assumption"* — because the
     * alternative is a wipe in which the two layers do not correspond, and every line the designer
     * checks is checked against the wrong part of their drawing.
     *
     * Exactly one thing causes it: `preprocess.perspectiveCorrect`, which makes the document frame
     * the rectified page rather than the source. On this client that switch is redundant — what stage
     * 11 traces has already been straightened by `DwSketchRectify` — so the remedy is a control the
     * designer can see, named by the label the panel shows for it. **Its own kind and not an
     * [ENGINE_ERROR]** because the engine did not fail, and because the sentence a designer needs
     * here would not survive [ENGINE_ERROR]'s detail cap.
     *
     * **IT COSTS THE COMPARISON AND NOT THE DRAWING**, which is a change from how this kind was first
     * written. It used to be thrown from inside `runTrace` before either plate was allocated, so an
     * advanced toggle the engine had honoured correctly threw away a finished SVG. It is now carried
     * as `DwTraceResult.plateRefusal` beside a drawing that is still on screen and still attachable —
     * the web's behaviour (`comparisonPlates.ts:288-295`, whose refusal ends "The drawing above is
     * unaffected") with this client's better remedy kept, because the web's sentence names neither
     * the control nor why it is redundant here. So this is the one kind in this enum that is not a
     * failed trace, and its sentence says so.
     */
    FRAME_MISMATCH,

    /**
     * The photograph could not be read at all.
     *
     * A HEIC the platform decoder will not open, a truncated file from an interrupted copy, or a
     * bitmap the phone had no memory for. The photograph on the record is untouched either way and
     * the sentence says so — a designer who reads "could not be read" about a file they can see in
     * the gallery will otherwise assume the record is damaged.
     */
    IMAGE_UNREADABLE,

    /** The file decoded, to nothing. A different remedy: choose another photograph. */
    IMAGE_EMPTY,

    /**
     * The engine bundle was not in the build.
     *
     * **UNREACHABLE — SEE THE FILE HEADER.** There is no bundle: the engine is compiled Kotlin, so
     * "it was not packaged" is not a state this app can be in. Nothing constructs this and no
     * designer sees its sentence.
     */
    BUNDLE_MISSING,

    /**
     * The bundle loaded and was not the one this Kotlin was written against.
     *
     * **UNREACHABLE — SEE THE FILE HEADER.** It described the one failure `UPSTREAM-MANIFEST.txt`
     * structurally could not catch: that manifest hashes the vendored TypeScript and not the
     * JavaScript blob built from it, so an app shipping a stale bundle traced with an old engine
     * while the portal traced with a new one and both stayed green. A compiled module cannot be
     * stale against its own build, so the gap this guarded is closed rather than unguarded.
     */
    BUNDLE_CONTRACT_MISMATCH,

    /**
     * The bundle answered with something this file could not read.
     *
     * Its own kind rather than folded into [ENGINE_ERROR] because the two point at different people:
     * an engine error is the engine's own sentence about an image, and this is the bridge and the
     * host disagreeing about a protocol. Malformed geometry lands here too — see
     * [DwTraceGeometry.validate], which refuses rather than letting a bad envelope become an
     * `ArrayIndexOutOfBoundsException` inside a draw call three frames later.
     */
    PROTOCOL_UNREADABLE,
}

/** See [dwTraceSentence]. `worker/trace.worker.ts:sentenceFor` caps its own detail here too. */
const val DW_TRACE_DETAIL_MAX: Int = 160

/**
 * @returns the sentence to put on screen for [kind]. Never a code, never a stack trace.
 *
 * [detail] is appended only where the engine or the platform said something a reader can use, and it
 * is length-capped for the reason `worker/trace.worker.ts:sentenceFor` caps its own at 160: a message
 * that long is a stack trace wearing a sentence's clothes.
 */
fun dwTraceSentence(kind: DwTraceFailureKind, detail: String = ""): String {
    val trimmed = detail.trim().let { if (it.isNotEmpty() && it.length <= DW_TRACE_DETAIL_MAX) it else "" }
    return when (kind) {
        DwTraceFailureKind.SANDBOX_UNSUPPORTED ->
            "This phone's Android System WebView is too old to trace a sketch — tracing needs the " +
                "version released in January 2022 or later. Update Android System WebView from the " +
                "Play Store next time you have a connection. The photograph and the straightened " +
                "plate are unaffected."

        DwTraceFailureKind.SANDBOX_FEATURE_MISSING ->
            "This phone's Android System WebView started but will not accept an image to trace. " +
                "Update Android System WebView from the Play Store next time you have a connection, " +
                "and report this phone's model if the update does not fix it."

        DwTraceFailureKind.SANDBOX_DIED ->
            "The trace stopped because Android reclaimed it while this app was in the background. " +
                "Start it again and leave this screen on until it finishes — a full-resolution " +
                "trace takes from several seconds to about a minute on this phone."

        DwTraceFailureKind.OUT_OF_MEMORY ->
            "This phone ran out of memory part-way through the trace. Set the trace resolution to " +
                "Fast, or choose a different edge engine — Canny needs about three times the memory " +
                "of the others. Closing other apps first also helps."

        DwTraceFailureKind.RESULT_TOO_LARGE ->
            "The trace found more separate shapes than can be handed back in one piece. Raise " +
                "\u201cMinimum speck\u201d so grit on the paper stops becoming shapes, or set the " +
                "trace resolution to Fast, and trace it again."

        DwTraceFailureKind.ENGINE_ERROR ->
            if (trimmed.isEmpty()) {
                "The tracing engine could not finish this photograph. Try a different edge engine, " +
                    "or set the trace resolution to Fast."
            } else {
                "The tracing engine could not finish this photograph: $trimmed"
            }

        DwTraceFailureKind.FRAME_MISMATCH ->
            "The trace finished in a different frame from the photograph, so the two cannot be laid " +
                "over each other and there is no comparison to show. The drawing itself is " +
                "unaffected and can still be attached. Turn off “Rectify the page” and trace " +
                "again if you want the comparison as well — this plate has already been " +
                "straightened." + if (trimmed.isEmpty()) "" else " ($trimmed)"

        DwTraceFailureKind.IMAGE_UNREADABLE ->
            "This phone could not read that photograph, so there is nothing to trace. The " +
                "photograph on the record is unaffected — it can still be attached as it is, and " +
                "the portal can trace it on a laptop."

        DwTraceFailureKind.IMAGE_EMPTY ->
            "That photograph decoded to no pixels at all, so there is nothing to trace. Take or " +
                "choose another photograph of the sheet."

        DwTraceFailureKind.BUNDLE_MISSING ->
            "This build of the app was packaged without the tracing engine, so no phone running it " +
                "can trace a sketch. Nothing on this handset will fix it — report the app version, " +
                "and trace on a laptop in the meantime."

        DwTraceFailureKind.BUNDLE_CONTRACT_MISMATCH ->
            "The tracing engine packaged with this app is not the version this app knows how to " +
                "drive, so it has not been run rather than run wrongly. Report the app version; a " +
                "rebuild is needed. Trace on a laptop in the meantime." +
                if (trimmed.isEmpty()) "" else " ($trimmed)"

        DwTraceFailureKind.PROTOCOL_UNREADABLE ->
            "The tracing engine answered with something this app could not read, so nothing has " +
                "been attached and the photograph is unaffected. Try once more; if it happens " +
                "again, report the app version and trace on a laptop." +
                if (trimmed.isEmpty()) "" else " ($trimmed)"
    }
}

/**
 * A failure carrying a classified [kind], thrown across the [DwTraceJsHost] seam.
 *
 * An exception here and a [DwTraceOutcome.Refused] at the [DwTraceRuntime] boundary, which is not an
 * inconsistency: `DwSketchRectify`'s `DwPlateResult.Refusal` states the rule — a sentence that has to
 * be printed is a value — and that is true of the boundary the panel reads. Inside, a failure has to
 * unwind a `provideNamedData`/`evaluate`/`close` sequence from wherever it happened, which is what
 * exceptions are for. `DwSketchTrace.kt` converts one into the other in exactly one place.
 */
class DwTraceHostFailure(
    val kind: DwTraceFailureKind,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception(dwTraceSentence(kind, detail), cause)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 2. THE HOST PORT — three methods, so the whole protocol can be driven from a JVM test
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * A JavaScript isolate, narrowed to the three things the protocol needs.
 *
 * NARROW ON PURPOSE, and the precedent is the file this whole feature mirrors: `traceClient.ts`
 * declares `TraceWorkerLike` with five members rather than using the DOM `Worker` type, and its
 * header says why — *"Narrowing rather than using the DOM type is what lets `TracerOptions.spawn`
 * take a stand-in, so the whole of this file can be driven from a Node spec … It is not an extension
 * point for production code: there is exactly one real implementation."* Word for word the same here.
 * There is one real implementation, `DwSketchTraceSandbox.kt`, and one stand-in, in
 * `DwSketchTraceSessionTest`, and the stand-in is what makes the pump loop below testable on a
 * machine with no handset attached.
 *
 * Implementations must be main-safe: a trace is seconds of solid arithmetic, and an implementation
 * that ran it on the caller's dispatcher would be an ANR.
 */
interface DwTraceJsHost {
    /**
     * Hand [bytes] to the isolate under [name], for `android.consumeNamedDataAsArrayBuffer(name)`.
     *
     * BINARY, NOT BASE64. `JavaScriptIsolate.provideNamedData` writes through an
     * `AssetFileDescriptor` pipe rather than a binder transaction, so there is no 1 MB cap and no
     * text encoding: a 1600x1200 plate is 7,680,000 bytes of RGBA and crosses as itself.
     */
    suspend fun provideNamedData(name: String, bytes: ByteArray)

    /** Evaluate [script] and return what it produced, already coerced to a string by the isolate. */
    suspend fun evaluate(script: String): String

    /**
     * Destroy the isolate. Idempotent, and never throws.
     *
     * This is also the hard stop behind a Cancel the engine's own token would not honour — see
     * [DwTraceSession.trace].
     */
    fun close()
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 3. THE PROTOCOL
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The version of the host-to-bundle protocol this file speaks.
 *
 * ── WHY A VERSION AT ALL, WHEN BOTH HALVES SHIP IN ONE APK ────────────────────────────────────
 *
 * Because they are built from different trees by different steps. The bundle is GENERATED from
 * `frontend/lib/trace/` and copied into `assets/`; this Kotlin is compiled from `android/`. Nothing
 * mechanical ties the two together, and the failure that follows is the quiet one: an app shipping a
 * stale bundle traces with an old engine while the portal traces with a new one, and
 * `UPSTREAM-MANIFEST.txt` stays green throughout, because it hashes the vendored TypeScript and not
 * the blob built from it. A handshake that refuses is how that becomes a sentence instead of a
 * different drawing in a government document.
 *
 * BUMP THIS whenever the shape below changes. The failing handshake is the point.
 */
const val DW_TRACE_CONTRACT: Int = 1

/**
 * The global the bundle installs. One name, one door — `lib/trace/README.md` §3's "Import
 * `traceClient.ts` and nothing else", in this host's dialect.
 */
const val DW_TRACE_GLOBAL: String = "__DwTrace"

/**
 * The asset the bundle was packaged as.
 *
 * **THE FILE THIS NAMES NO LONGER EXISTS.** It was deleted with the JavaScript route — see the file
 * header. The constant is unreferenced by anything that ships and is kept only because this file was
 * outside that port's remit; do not reintroduce a reader for it.
 */
const val DW_TRACE_BUNDLE_ASSET: String = "dw-trace-engine.js"

/** The name the plate's pixels are handed over under. One isolate per trace, so one name suffices. */
const val DW_TRACE_IMAGE_DATA_NAME: String = "dwTraceImage"

/**
 * **THE CONTRACT THE BUNDLE MUST SATISFY**, written here because this is the half that can refuse.
 *
 * **NOTHING SATISFIES THIS CONTRACT ANY MORE.** The other half was `frontend/lib/trace/android/`,
 * deleted with the rest of the bridge; see the file header. What follows is the specification that
 * bundle was written against, kept as a record of a protocol rather than as a live requirement.
 *
 * ── NINE FUNCTIONS ON `__DwTrace`, EACH RETURNING A JSON STRING ───────────────────────────────
 *
 *     __DwTrace.hello()                                    -> {"v":1,"state":"hello",...}
 *     __DwTrace.presets()                                  -> {"v":1,"state":"presets",...}
 *     __DwTrace.defaults()                                 -> {"v":1,"state":"value","params":"<json>"}
 *     __DwTrace.withOverrides(paramsJson, patchJson)       -> value
 *     __DwTrace.applyStyle(paramsJson, styleId)            -> value
 *     __DwTrace.applySubject(paramsJson, subjectId)        -> value
 *     __DwTrace.start(dataName, w, h, paramsJson, preview) -> running | done | error
 *     __DwTrace.pump()                                     -> running | done | error | cancelled
 *     __DwTrace.cancel()                                   -> running | done | error | cancelled
 *
 * A JSON **string** and not an object, because `evaluateJavaScriptAsync` resolves a
 * `ListenableFuture<String>`: whatever the last expression evaluates to arrives here as text, so the
 * bundle spelling it with `JSON.stringify` is the only version of this where both halves agree about
 * what the text is. Every parameter tree crosses as a STRING for the same reason it comes back as one
 * — see [DwTraceValues.wire]: a tree re-serialised on this side is a tree this side has had an
 * opinion about, and this side is not allowed to have one.
 *
 * ── WHY `start`/`pump` AND NOT ONE `trace()` CALL — THE LOAD-BEARING DECISION IN THIS FILE ────
 *
 * A bare `JavaScriptIsolate` has microtasks and **no task queue**. `Pipeline.run` yields between
 * stages with `setTimeout(resolve, 0)`, and `engine/pipeline.ts:214-224` says exactly what that yield
 * is for: it is the only thing that lets a `cancel` be observed mid-trace. Shim `setTimeout` as a
 * microtask — which is what a host with no task queue is forced into, and what the deleted
 * `lib/trace/android/hostGlobals.ts` did — and the consequence was written in that file's own header:
 * *"A trace runs to completion as one uninterruptible microtask chain … `CancellationToken.cancel()`
 * therefore cannot be signalled from Android part-way through a trace."* Cancel then degrades to
 * killing the isolate, and progress cannot be reported at all, because nothing can come out of an
 * evaluation that has not returned.
 *
 * (The Kotlin runtime that replaced all of this has neither problem: `CancellationToken.cancel()` is
 * a volatile write on the same heap and the engine checks it at every stage boundary. See
 * `DwTraceKotlinRuntime.kt`'s header, which names this as the thing the port bought.)
 *
 * **The host can simply BE the task queue.** `setTimeout` pushes onto an array and schedules nothing;
 * each `pump()` evaluation runs one queued callback, which advances the trace by exactly one stage
 * and parks it again at the next `await yieldToEventLoop()`. That restores both properties the web
 * has and this host was assumed to have lost:
 *
 *  - **Progress is real** — twelve stage boundaries, drained from the vendored worker's own outbox
 *    and returned by the same call that advanced the trace. No console callback, no `MessagePort`,
 *    and no need for `androidx.javascriptengine:1.1.0`.
 *  - **Cancel is between stages, exactly as the engine defines it** (`pipeline.ts:238-246`), because
 *    `cancel()` runs in a gap where nothing is executing. It is the vendored `CancellationToken`
 *    being set, not a process being killed.
 *
 * The cost is about fourteen binder round trips per trace instead of one, against a trace the spike
 * measured at 2.9–16.7 s on a laptop. It is not close.
 *
 * ── AND WHAT HAPPENS IF THE BUNDLE DOES IT THE OTHER WAY ──────────────────────────────────────
 *
 * Nothing breaks. A bundle whose `setTimeout` is a microtask cannot park, so its `start()` answers
 * `done` (or `error`) rather than `running`, and [DwTraceSession.trace]'s loop — written as "keep
 * pumping while the answer is `running`" — simply never goes round. Such a bundle reports
 * `"pumped": false` from `hello()`, `DwSketchTrace` carries that into
 * [DwTraceAvailability.measuredOn], and a trace on it has no progress and a Cancel that closes the
 * isolate. **Degrading is deliberate; degrading SILENTLY is not.**
 *
 * ── THE THREE THINGS THE BUNDLE MUST GET RIGHT THAT NOTHING HERE CAN CHECK ────────────────────
 *
 *  1. **Every typed array is base64 of LITTLE-ENDIAN bytes.** Typed arrays use platform endianness
 *     and every Android ABI this app ships is little-endian, so a bundle that writes them straight
 *     out is already right — but it must be deliberate, because the day it is not, the coordinates
 *     arrive byte-swapped and the trace draws noise rather than failing.
 *  2. **The SVG is written by the writer the PORTAL attaches with.** See [DwTraceHello.svgWriter].
 *  3. **`params` and `appliedParams` are the engine's own `JSON.stringify` of the sanitised tree**,
 *     handed back untouched. `engine/params.ts` is the sole authority on what is legal
 *     (`params.ts:806-810` documents `sanitizeTraceParams` idempotent precisely so a UI may run it on
 *     every slider tick), and nothing on this side may clamp, round or merge.
 */
const val DW_TRACE_PROTOCOL_NOTE: String =
    "__DwTrace.{hello,presets,defaults,withOverrides,applyStyle,applySubject,start,pump,cancel}, " +
        "each returning a JSON string envelope. See this constant's KDoc for the full contract."

/**
 * What `hello()` said about the bundle on the other side.
 *
 * [engineManifestSha256] and [svgWriter] are CARRIED rather than checked. This file is in no position
 * to know which SHA-256 is current — that is `UPSTREAM-MANIFEST.txt`'s job, on a machine with the
 * upstream on it — but a handset that can SAY which engine it traced with turns a silent divergence
 * into a readable fact, and both belong in a bug report beside the app version.
 *
 * **[svgWriter] is not decoration, and the value it reports matters.** `engine/svgWriter.ts:159-161`
 * stamps `<title>Offline Tracer export</title>` and `<desc>Generated by Offline Tracer. All
 * processing performed on device.</desc>` into every file it writes — another product's branding,
 * which must not reach a ministry submission. The portal does not attach that:
 * `sketch.lineArtFile` is written by `components/sketches/upload/geometryToSvg.ts`'s `buildSvg`,
 * whose spelling of the `d` attribute also differs from the engine writer's (a space after the
 * command letter, and an explicit `C` on every cubic where `engine/svgPathData.ts:29-44` elides it
 * for a run). So the two writers produce the same drawing as different bytes, and only one of them is
 * what the portal attaches. **The handset must use the same one the portal attaches, or one sheet of
 * paper yields two different files.** This field is how anyone can tell which was used without
 * opening the SVG, and it is deliberately reported rather than enforced, because which writer is
 * correct is a decision for whoever owns `components/sketches/upload/` and not for this file.
 */
class DwTraceHello(
    val contract: Int,
    /** True when `start`/`pump` can park between stages — see [DW_TRACE_PROTOCOL_NOTE]. */
    val pumped: Boolean,
    val engineManifestSha256: String,
    val svgWriter: String,
    /** Anything the bundle wants said on screen, rendered with the trace's own notes, unabridged. */
    val notes: List<String>,
)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 4. THE GEOMETRY MIRROR — thin, faithful, and deliberately not a re-model
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/** Segment kind codes, mirroring `worker/protocol.ts:66-68`. `DwSketchTraceWireTest` pins all three. */
const val DW_TRACE_VERB_LINE: Byte = 0

/** @see DW_TRACE_VERB_LINE */
const val DW_TRACE_VERB_QUAD: Byte = 1

/** @see DW_TRACE_VERB_LINE */
const val DW_TRACE_VERB_CUBIC: Byte = 2

/**
 * One entry of `SerializedGeometry.styleTable`. A field-for-field mirror of `engine/path.ts:559-568`.
 *
 * [stroke] and [fill] are packed ARGB, or null for "none" — `null` is the only spelling of absent,
 * and a `0` would be transparent black, which is a different thing that draws. They arrive as JSON
 * numbers up to 4,294,967,295 (`0xff000000` is 4,278,190,080), so they are read as `Long` and
 * narrowed: `4278190080L.toInt()` is `-16777216`, which is exactly the `Int` `Paint.setColor` wants.
 * The narrowing IS the conversion, not a loss of information.
 *
 * [fillRule], [cap] and [join] stay STRINGS. The engine's enums have values equal to their names
 * (`FillRule.EVENODD = 'EVENODD'`), and re-declaring three Kotlin enums here would be three more
 * lists to keep in step with a vendored file for no benefit. `DwSketchTracePlates` maps them where it
 * draws, and an unrecognised value there falls back to the engine's own default rather than crashing
 * — a newer upstream that adds a join style must not be a crash on a phone in a courtyard.
 */
class DwTraceStyle(
    val stroke: Int?,
    val strokeWidth: Float,
    val fill: Int?,
    val fillRule: String,
    val cap: String,
    val join: String,
    val miterLimit: Float,
    val opacity: Float,
)

/**
 * The traced geometry, as the flat arrays the engine already produced.
 *
 * **THIS IS A MIRROR AND MUST STAY ONE.** `worker/protocol.ts:70-105` explains the shape and the
 * reason for it — a 50,000-path trace is roughly a million coordinates, and as `{x, y}` objects that
 * is a million allocations. Rebuilding it here into Kotlin `VecPath`/`VecSeg` classes would pay that
 * cost AND start the second implementation this whole approach exists to avoid: the moment there is a
 * Kotlin opinion about what a cubic segment IS, there is something for the two clients to disagree
 * about, and nothing that can check it. So the arrays cross as arrays, `DwSketchTracePlates` walks
 * them once to draw, and no other file in this app holds geometry in any other shape.
 *
 * Layout, from `worker/trace.worker.ts:serializeGeometry`:
 *  - shape `i` owns `verbs[verbStarts[i] until verbStarts[i + 1]]` and
 *    `coords[coordStarts[i] until coordStarts[i + 1]]`;
 *  - a shape's coordinate run BEGINS with its start point, then two floats per line, four per quad,
 *    six per cubic;
 *  - [verbStarts] and [coordStarts] are `shapeCount + 1` long, so an extent is a subtraction.
 */
class DwTraceGeometry(
    val coords: FloatArray,
    val verbs: ByteArray,
    val verbStarts: IntArray,
    val coordStarts: IntArray,
    val closed: ByteArray,
    val styleTable: List<DwTraceStyle>,
    val styleIndex: IntArray,
) {
    val shapeCount: Int get() = closed.size

    fun isClosed(shape: Int): Boolean = closed[shape].toInt() != 0

    fun styleOf(shape: Int): DwTraceStyle = styleTable[styleIndex[shape]]

    /**
     * Refuses a self-inconsistent envelope rather than letting it become a crash inside a canvas.
     *
     * Everything here is cheap integer arithmetic over `shapeCount + 1` entries, run once per trace,
     * and it is the difference between a sentence on screen and an `ArrayIndexOutOfBoundsException`
     * thrown out of a draw call three frames later — by which point nothing on screen says the
     * geometry was the problem. The web never needed this because its arrays are transferred within
     * one process by one function; here they have crossed a process boundary as base64 and been
     * reassembled by a decoder that could be a version behind.
     *
     * The coordinate-count check is the one that earns its keep. A mismatch there does not crash: it
     * reads a neighbouring shape's numbers as this shape's curve and draws something plausible and
     * wrong, which is the worse failure.
     *
     * @throws DwTraceHostFailure with [DwTraceFailureKind.PROTOCOL_UNREADABLE]
     */
    fun validate() {
        val n = shapeCount
        if (verbStarts.size != n + 1) bad("verbStarts is ${verbStarts.size} for $n shapes")
        if (coordStarts.size != n + 1) bad("coordStarts is ${coordStarts.size} for $n shapes")
        if (styleIndex.size != n) bad("styleIndex is ${styleIndex.size} for $n shapes")
        if (n > 0 && styleTable.isEmpty()) bad("the style table is empty for $n shapes")
        if (verbStarts[n] != verbs.size) {
            bad("verbs is ${verbs.size} long, verbStarts ends at ${verbStarts[n]}")
        }
        if (coordStarts[n] != coords.size) {
            bad("coords is ${coords.size} long, coordStarts ends at ${coordStarts[n]}")
        }
        for (i in 0 until n) {
            if (verbStarts[i] < 0 || verbStarts[i] > verbStarts[i + 1]) {
                bad("verbStarts does not increase at shape $i")
            }
            if (coordStarts[i] < 0 || coordStarts[i] > coordStarts[i + 1]) {
                bad("coordStarts does not increase at shape $i")
            }
            if (styleIndex[i] !in styleTable.indices) bad("shape $i names style ${styleIndex[i]}")
            var want = 2
            for (v in verbStarts[i] until verbStarts[i + 1]) {
                want += when (verbs[v]) {
                    DW_TRACE_VERB_LINE -> 2
                    DW_TRACE_VERB_QUAD -> 4
                    DW_TRACE_VERB_CUBIC -> 6
                    else -> bad("shape $i has verb ${verbs[v]}, which is not a line, quad or cubic")
                }
            }
            val have = coordStarts[i + 1] - coordStarts[i]
            if (have != want) bad("shape $i has $have coordinates where its verbs need $want")
        }
    }

    private fun bad(why: String): Nothing =
        throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, why)
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 5. WHAT A FINISHED TRACE IS, BEFORE ANYTHING HAS DRAWN IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Everything `SerializedTraceResult` carries, decoded, and NOT ONE THING MORE.
 *
 * The split from [DwTraceResult] is the testability split this file's header describes: that class
 * holds two `Bitmap`s, so nothing that constructs one can be reached by a JVM test. Everything worth
 * pinning — the geometry, the notes, the counts, the parameters that actually ran — is here, where it
 * can be.
 */
class DwTraceDecoded(
    /**
     * The vector document as a string. **Carried, never re-printed.**
     *
     * `frontend/e2e/trace-parity-unit.spec.ts` compares this tier EXACTLY, because what reaches the
     * ministry is a string. Nothing on this side may re-indent it, normalise its numbers or "tidy"
     * its path data — `engine/svgPathData.ts:38`: "Both engines must spell it identically."
     */
    val svg: String,
    val geometry: DwTraceGeometry,
    /** The document background, packed ARGB, or null for transparent (`engine/params.ts:359`). */
    val background: Int?,
    val width: Int,
    val height: Int,
    val workingWidth: Int,
    val workingHeight: Int,
    val shapeCount: Int,
    val nodeCount: Int,
    val stages: List<DwTraceStageTiming>,
    val totalMillis: Long,
    val notes: List<String>,
    val appliedParams: DwTraceValues,
    val autoSubjectId: String,
    val suggestedStyleId: String,
)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 6. MARSHALLING IN — Android's packed ARGB to the engine's RGBA
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * One row of `Bitmap.getPixels` output, written into [out] as the bytes `RgbaImage.fromImageData`
 * reads.
 *
 * **PURE, AND THAT IS THE WHOLE REASON IT IS HERE** rather than three lines inside the file that owns
 * the bitmap: it is the one piece of the marshalling that can be wrong in a way nothing else would
 * notice. Swap two channels and the engine still traces — it traces a picture with red and blue
 * exchanged, which on a pencil sketch on cream paper looks very nearly right and comes out quietly
 * different from the portal's answer forever. `DwSketchTraceWireTest` pins it against the arithmetic
 * in `engine/buffers.ts:317-319`.
 *
 * Android's `getPixels` hands back `0xAARRGGBB`; the engine reads `[R, G, B, A]` and packs it back to
 * `(a shl 24) or (r shl 16) or (g shl 8) or b`. So this function and `fromImageData` are exact
 * inverses, and the byte order stays where `engine/buffers.ts:16` insists it stays — inside the
 * engine, in the only two functions allowed to know about it.
 *
 * @param pixels packed ARGB, [width] of them starting at index 0
 * @param out    at least `rowStart + width * 4` bytes
 */
fun dwTraceArgbRowToRgba(pixels: IntArray, width: Int, out: ByteArray, rowStart: Int) {
    var o = rowStart
    for (x in 0 until width) {
        val p = pixels[x]
        out[o] = ((p ushr 16) and 0xFF).toByte()
        out[o + 1] = ((p ushr 8) and 0xFF).toByte()
        out[o + 2] = (p and 0xFF).toByte()
        out[o + 3] = ((p ushr 24) and 0xFF).toByte()
        o += 4
    }
}

/**
 * The longest edge a decode may produce, mirroring `decodeToPixels.ts:37`'s `DECODE_MAX_EDGE_PX`.
 *
 * 4096 is that file's number, and the reason it gives is that it is exactly the ceiling
 * `traceParamTable.ts` puts on "Trace resolution" — decoding below it would silently cap a slider the
 * designer can still see at its top end. **On a handset the panel offers a lower choice** (see
 * [DwTraceAvailability.maxWorkingLongEdge]), and that is a different thing from decoding lower: a cap
 * the designer picks with the cost named is a choice, and a limit nobody is told about is a
 * disagreement between two clients that nobody finds for a year. They must not be conflated, which is
 * why this constant equals the web's and is not quietly reduced.
 */
const val DW_TRACE_DECODE_MAX_EDGE_PX: Int = 4096

/**
 * @returns the working size a source of [width] x [height] is decoded down to, honouring [maxEdge].
 *
 * A LINE-FOR-LINE MIRROR of `components/sketches/upload/decodeToPixels.ts:110-125`, including the
 * `Math.round` and the `max(1, …)`, because the two clients must hand the engine the same number of
 * pixels or they are not tracing the same picture. A `floor` here instead of a round is a one-pixel
 * difference in the working frame, and every coordinate the engine reports moves with it.
 *
 * Never upscales: a source already inside the cap comes back untouched, so the common scanned A4 at
 * 2480x3508 is not resampled for nothing.
 */
fun dwTraceWorkingSize(width: Int, height: Int, maxEdge: Int = DW_TRACE_DECODE_MAX_EDGE_PX): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= 0 || maxEdge <= 0 || longest <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longest.toDouble()
    val w = maxOf(1, Math.round(width * scale).toInt())
    val h = maxOf(1, Math.round(height * scale).toInt())
    return w to h
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 6b. WHAT THIS PHONE IS ALLOWED TO ASK FOR — arithmetic, so it can be tested and argued with
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The working long edge a handset may trace at until somebody measures one.
 *
 * **THIS IS A GUESS AND IS LABELLED AS ONE**, which is why [DwTraceAvailability.measuredOn] is null
 * everywhere in this build. `docs/DEVICE-TIER-MEASUREMENT.md` is the standard: a ceiling is a claim
 * about the world, and this repository's docs gate flags an undated claim as rot.
 *
 * What IS measured, on a laptop's V8 on 2026-08-27 at the product's own input cap of 1600x1200: a
 * full trace is 2.9 s with `ADAPTIVE` and **16.7 s with the shipped FDOG default**, and 13,037 of
 * those 16,655 ms are in one stage. What is NOT measured is the desktop-to-handset factor. Published
 * single-thread figures put the fleet's Galaxy M32 (SM-M325F, `DwDeviceTier.kt:1115`) 4–7x below that
 * laptop, which would be 12–20 s for ADAPTIVE and 67–117 s for FDOG — and `DwDictationLadder.kt:430`
 * already says out loud that one phone is one data point for a whole fleet.
 *
 * 2048 rather than the web's 4096 because `traceParamTable.ts:154-157` narrowed its own slider to
 * 4096 with the sentence *"a 4096 trace is already several seconds of a worker thread on the phones
 * this application is used from"* — written about a browser on those phones, before anybody had run
 * the engine in an isolate on one.
 *
 * Re-check by running the spike's throughput matrix inside a real isolate on an M32 and writing the
 * answer into `docs/DEVICE-TIER-MEASUREMENT.md`. Until then this number stays where it is and
 * `measuredOn` stays null, because raising it on a hunch is how a designer waits two minutes in a
 * courtyard for something they will cancel.
 */
const val DW_TRACE_DEFAULT_MAX_WORKING_EDGE: Int = 2048

/**
 * The same ceiling for the FDOG edge engine, which is 5.7x the cost of every alternative.
 *
 * **A CEILING AND NOT A SUBSTITUTION.** The obvious fix — quietly swap FDOG for ADAPTIVE on a phone
 * — is the worst option available and is the one this repository exists to prevent: one sheet of
 * paper would then produce two different drawings depending on which client traced it. So the handset
 * refuses above this edge and names the remedy, the designer chooses, and both clients then agree
 * because they are running the same parameters. `DwSketchTraceEngine.kt` states the same argument
 * where it declares [DwTraceAvailability.fdogMaxWorkingLongEdge].
 */
const val DW_TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE: Int = 1024

/**
 * The two ceilings for a phone with [totalRamBytes] of memory, halved on a small one.
 *
 * The threshold is 3 GB and it is `DwDeviceTier.kt`'s own erring-low discipline rather than a new
 * band: `totalMem` is always below the number on the box (the firmware's reservations are taken
 * before Android sees them), so a handset sold as 4 GB reports something in the threes and lands
 * above this, while a genuine 2 GB device lands below. A failed memory read (`null`) takes the
 * cautious half, for the reason `DwDeviceMeasurement.lowRamDevice` gives about its own null: a
 * handset that would have said it was small must not be promoted by a lookup that failed.
 */
fun dwTraceCeilings(totalRamBytes: Long?): Pair<Int, Int> {
    val small = totalRamBytes == null || totalRamBytes < 3L * 1024L * 1024L * 1024L
    return if (small) {
        (DW_TRACE_DEFAULT_MAX_WORKING_EDGE / 2) to (DW_TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE / 2)
    } else {
        DW_TRACE_DEFAULT_MAX_WORKING_EDGE to DW_TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE
    }
}

/**
 * The heap cap to give an isolate, in bytes, for a phone with [totalRamBytes] of memory.
 *
 * **UNREACHED — THERE IS NO ISOLATE.** See the file header. Its argument is preserved because
 * `DwTraceKotlinRuntime.kt` quotes the measurements below and cites this docblock by name for the one
 * thing the port genuinely gave up: those 72–278 MB used to live in the WebView sandbox's own
 * process and now live in this app's Java heap. The remedy there is `dwTraceKotlinMemoryRefusal`,
 * which measures before every trace instead of capping.
 *
 * ── WHY THERE WAS A CAP AT ALL, WHEN NOT SETTING ONE IS EASIER ────────────────────────────────
 *
 * Because of what happens without it. The spike measured peak JS heap across a whole trace at
 * 1600x1200: ADAPTIVE +93 MB, LOG +76 MB, FDOG +72 MB, and **CANNY +278 MB**. That memory lives in
 * the WebView sandbox's own process rather than this app's, which is the saving grace and the single
 * strongest argument for `JavaScriptSandbox` over an in-process engine — but an uncapped isolate on a
 * loaded phone does not fail politely, it gets the sandbox killed by the low-memory killer and
 * arrives here as `SandboxDeadException`, which is [DwTraceFailureKind.SANDBOX_DIED]'s sentence about
 * backgrounding and is the WRONG sentence. With a cap it arrives as `MemoryLimitExceededException`,
 * which is [DwTraceFailureKind.OUT_OF_MEMORY] and names the two remedies that actually work.
 *
 * ── THE NUMBER IS A FRACTION OF TOTAL MEMORY AND IT IS NOT MEASURED ON A DEVICE ───────────────
 *
 * A twelfth of total memory, floored at 192 MB and ceilinged at 512 MB. On the fleet's M32
 * (5,927,968,768 bytes, `DwDeviceTier.kt:1115`) that is 471 MB — above the 278 MB CANNY wanted on a
 * laptop, with enough headroom that an ordinary trace never meets the cap and a runaway one does.
 * The floor exists so a 2 GB handset still gets more than the 93 MB an ADAPTIVE trace needs; the
 * ceiling exists because a cap larger than the phone can spare is not a cap.
 *
 * **What is NOT known is whether a handset's V8 peaks where a laptop's V8 peaked.** Nothing about
 * this ratio has been read off a device, and the honest thing to do with it is to measure it on an
 * M32 and replace this arithmetic with a table, exactly as `DwDeviceTier.kt` did for the speech
 * models. Until then it is placed so that being wrong costs a sentence rather than a dead sandbox.
 */
fun dwTraceHeapCapBytes(totalRamBytes: Long?): Long {
    val floor = 192L * 1024L * 1024L
    val ceiling = 512L * 1024L * 1024L
    val total = totalRamBytes ?: return floor
    return (total / 12L).coerceIn(floor, ceiling)
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 7. THE SESSION — one trace, start to finish, over a [DwTraceJsHost]
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/** What one evaluation answered. Internal to the pump loop; the runtime never sees one. */
internal sealed class DwTraceEnvelope {
    class Hello(val hello: DwTraceHello) : DwTraceEnvelope()
    class Value(val params: DwTraceValues) : DwTraceEnvelope()
    class Presets(val tables: DwTracePresetTables) : DwTraceEnvelope()
    class Running(val events: List<DwTraceProgress>) : DwTraceEnvelope()
    class Done(val events: List<DwTraceProgress>, val decoded: DwTraceDecoded) : DwTraceEnvelope()
    class Failed(val message: String) : DwTraceEnvelope()
    object Cancelled : DwTraceEnvelope()
}

/** Twelve stages, one start, and slack for a bundle that reports a stage in more than one turn. */
const val DW_TRACE_MAX_PUMPS: Int = 64

/** How many turns a cancelled trace is given to unwind before the isolate is closed underneath it. */
const val DW_TRACE_CANCEL_PUMPS: Int = 4

/**
 * Runs the [DW_TRACE_PROTOCOL_NOTE] conversation against one isolate.
 *
 * Holds the host and nothing else, so a test can build one per case and a runtime can build one per
 * trace. It does NOT own the host's lifetime: whoever created the isolate closes it, in a `finally`.
 */
class DwTraceSession(private val host: DwTraceJsHost) {

    /**
     * The handshake. Called once per isolate, before anything else.
     *
     * @throws DwTraceHostFailure [DwTraceFailureKind.BUNDLE_CONTRACT_MISMATCH] when the bundle speaks
     *   a different protocol — refusing rather than attempting, because a protocol that is one field
     *   different does not fail, it succeeds differently.
     */
    suspend fun hello(): DwTraceHello {
        val envelope = dwTraceReadEnvelope(host.evaluate("$DW_TRACE_GLOBAL.hello()"))
        val hello = (envelope as? DwTraceEnvelope.Hello)?.hello
            ?: throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                "hello() did not answer with a handshake",
            )
        if (hello.contract != DW_TRACE_CONTRACT) {
            throw DwTraceHostFailure(
                DwTraceFailureKind.BUNDLE_CONTRACT_MISMATCH,
                "the app speaks $DW_TRACE_CONTRACT, the packaged engine speaks ${hello.contract}",
            )
        }
        return hello
    }

    suspend fun presets(): DwTracePresetTables =
        expectPresets(host.evaluate("$DW_TRACE_GLOBAL.presets()"))

    suspend fun defaults(): DwTraceValues = expectValue(host.evaluate("$DW_TRACE_GLOBAL.defaults()"))

    suspend fun withOverrides(base: DwTraceValues, patch: Map<String, DwTraceValue>): DwTraceValues =
        expectValue(
            host.evaluate(
                "$DW_TRACE_GLOBAL.withOverrides(" +
                    "${dwTraceJsString(base.wire)}, ${dwTraceJsString(dwTracePatchJson(patch))})",
            ),
        )

    suspend fun applyStyle(base: DwTraceValues, styleId: String): DwTraceValues =
        expectValue(
            host.evaluate(
                "$DW_TRACE_GLOBAL.applyStyle(" +
                    "${dwTraceJsString(base.wire)}, ${dwTraceJsString(styleId)})",
            ),
        )

    suspend fun applySubject(base: DwTraceValues, subjectId: String): DwTraceValues =
        expectValue(
            host.evaluate(
                "$DW_TRACE_GLOBAL.applySubject(" +
                    "${dwTraceJsString(base.wire)}, ${dwTraceJsString(subjectId)})",
            ),
        )

    /**
     * One whole trace: hand over the pixels, start, pump to the end, report every stage boundary.
     *
     * ── HOW A CANCEL ACTUALLY LANDS, WHICH IS THE PART WORTH READING ──────────────────────────
     *
     * The loop checks the coroutine's own job at the top of every turn. That check falls between two
     * pumps, which is between two stages, which is precisely where `engine/pipeline.ts:238-246`
     * checks its own token — so the two notions of "cancelled" line up exactly rather than one being
     * an approximation of the other. On seeing a cancelled job the loop does NOT return: it calls
     * `cancel()`, which sets the vendored `CancellationToken`, and then keeps pumping until the
     * bundle answers `cancelled`, which is the engine unwinding through its own `CancelledError`.
     * `worker/trace.worker.ts:156-157` says why that unwind must never be reported as a failure, and
     * this function honours it by rethrowing `CancellationException` — never by returning a refusal.
     *
     * [DW_TRACE_CANCEL_PUMPS] bounds the courtesy. A bundle that will not stop does not get to hold a
     * designer's screen: after that many turns the isolate is closed underneath it, which is the
     * blunt instrument, used second rather than first.
     *
     * **The worst-case latency of a Cancel is one stage**, not zero, and a surface must not promise
     * otherwise. At full resolution `edge` or `vectorize` is seconds on a phone. "Stopping…" is
     * honest; a button that vanishes and then does nothing for four seconds is not.
     *
     * @throws kotlinx.coroutines.CancellationException when the caller's job was cancelled
     * @throws DwTraceHostFailure for everything a designer has to be told about
     */
    suspend fun trace(
        rgba: ByteArray,
        width: Int,
        height: Int,
        params: DwTraceValues,
        preview: Boolean,
        onProgress: suspend (DwTraceProgress) -> Unit,
    ): DwTraceDecoded {
        if (width < 1 || height < 1) {
            throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_EMPTY, "${width}x$height")
        }
        val needed = width.toLong() * height.toLong() * 4L
        if (rgba.size.toLong() < needed) {
            throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                "${rgba.size} bytes for a ${width}x$height frame, which needs $needed",
            )
        }

        host.provideNamedData(DW_TRACE_IMAGE_DATA_NAME, rgba)

        var envelope = dwTraceReadEnvelope(
            host.evaluate(
                "$DW_TRACE_GLOBAL.start(${dwTraceJsString(DW_TRACE_IMAGE_DATA_NAME)}, " +
                    "$width, $height, ${dwTraceJsString(params.wire)}, $preview)",
            ),
        )

        var cancelling = false
        var pumpsSinceCancel = 0
        var turns = 0
        while (true) {
            // Bound to a val rather than smart-cast off the loop's own `var`: the reassignment at the
            // bottom is what makes the difference between a smart cast the compiler allows today and
            // one it stops allowing after somebody wraps a branch in a lambda.
            when (val answer = envelope) {
                is DwTraceEnvelope.Running -> emit(answer.events, onProgress)

                is DwTraceEnvelope.Done -> {
                    emit(answer.events, onProgress)
                    // Checked before returning, always. It throws only when a cancel lost its race
                    // with the last stage — and then it must, because resolving a result onto
                    // somebody who has already moved on is the race `traceClient.ts:172-186` settles
                    // the same way, for the same reason.
                    coroutineContext.ensureActive()
                    return answer.decoded
                }

                is DwTraceEnvelope.Failed -> throw DwTraceHostFailure(
                    DwTraceFailureKind.ENGINE_ERROR,
                    answer.message,
                )

                is DwTraceEnvelope.Cancelled -> {
                    // The engine unwound through CancelledError. That is not a failure and is never
                    // reported as one: this rethrows the caller's own cancellation. Reaching here
                    // with a LIVE job means the bundle cancelled a trace nobody asked it to, which
                    // is a protocol fault and does get a sentence.
                    coroutineContext.ensureActive()
                    throw DwTraceHostFailure(
                        DwTraceFailureKind.PROTOCOL_UNREADABLE,
                        "the engine reported a cancel nobody asked for",
                    )
                }

                else -> throw DwTraceHostFailure(
                    DwTraceFailureKind.PROTOCOL_UNREADABLE,
                    "the engine answered a trace with something else",
                )
            }

            turns += 1
            if (turns > DW_TRACE_MAX_PUMPS) {
                throw DwTraceHostFailure(
                    DwTraceFailureKind.PROTOCOL_UNREADABLE,
                    "the engine did not finish within $DW_TRACE_MAX_PUMPS stage turns",
                )
            }

            if (!cancelling && !coroutineContext.isActive) {
                cancelling = true
                envelope = dwTraceReadEnvelope(host.evaluate("$DW_TRACE_GLOBAL.cancel()"))
                continue
            }
            if (cancelling) {
                pumpsSinceCancel += 1
                if (pumpsSinceCancel > DW_TRACE_CANCEL_PUMPS) {
                    // The token was set and the engine kept going. Close is the hard stop, and the
                    // ensureActive() below is what the caller actually sees.
                    host.close()
                    coroutineContext.ensureActive()
                    throw DwTraceHostFailure(
                        DwTraceFailureKind.PROTOCOL_UNREADABLE,
                        "the engine ignored a cancel",
                    )
                }
            }
            envelope = dwTraceReadEnvelope(host.evaluate("$DW_TRACE_GLOBAL.pump()"))
        }
    }

    private suspend fun emit(
        events: List<DwTraceProgress>,
        onProgress: suspend (DwTraceProgress) -> Unit,
    ) {
        for (event in events) onProgress(event)
    }

    private fun expectValue(text: String): DwTraceValues {
        val envelope = dwTraceReadEnvelope(text)
        return (envelope as? DwTraceEnvelope.Value)?.params
            ?: throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                if (envelope is DwTraceEnvelope.Failed) envelope.message else "expected a parameter tree",
            )
    }

    private fun expectPresets(text: String): DwTracePresetTables {
        val envelope = dwTraceReadEnvelope(text)
        return (envelope as? DwTraceEnvelope.Presets)?.tables
            ?: throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                if (envelope is DwTraceEnvelope.Failed) envelope.message else "expected the preset tables",
            )
    }
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 8. DECODING — the half of the protocol that can be got wrong quietly
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The parser. Lenient about fields it does not know, strict about the ones it does.
 *
 * `ignoreUnknownKeys` is not set because nothing here is deserialised into a data class; every read
 * below goes through the element tree by hand, which ignores unknown keys by construction. That
 * matters for the same reason it matters on the wire types in `ApiModels.kt`: a newer bundle that
 * adds a field must not stop an older app from tracing.
 */
private val dwTraceJson = Json

/** Reads one envelope, or refuses. Internal so the tests can drive it without going through a host. */
internal fun dwTraceReadEnvelope(text: String): DwTraceEnvelope {
    val root = runCatching { dwTraceJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            // The first characters and not the whole answer: a 3 MB result string in a log line is
            // how a crash report becomes unreadable, and the first 80 characters are what identifies
            // a stray exception message or an HTML error page masquerading as an envelope.
            "not JSON: ${text.take(80)}",
        )

    return when (val state = root.str("state")) {
        "hello" -> DwTraceEnvelope.Hello(
            DwTraceHello(
                contract = root.int("contract", -1),
                pumped = root.bool("pumped", false),
                engineManifestSha256 = root.str("engine").orEmpty(),
                svgWriter = root.str("svgWriter").orEmpty(),
                notes = root.strings("notes"),
            ),
        )

        "presets" -> DwTraceEnvelope.Presets(
            DwTracePresetTables(
                styles = root.presets("styles"),
                subjects = root.presets("subjects"),
            ),
        )

        "value" -> DwTraceEnvelope.Value(dwTraceValuesOf(root.str("params").orEmpty()))

        "running" -> DwTraceEnvelope.Running(root.events())

        "done" -> DwTraceEnvelope.Done(root.events(), dwTraceDecodeResult(root["result"]))

        "error" -> DwTraceEnvelope.Failed(root.str("message").orEmpty())

        "cancelled" -> DwTraceEnvelope.Cancelled

        else -> throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            if (state == null) "an envelope with no state" else "an envelope in state \"$state\"",
        )
    }
}

/**
 * The engine's sanitised tree, flattened for reading and carried whole for sending back.
 *
 * TWO REPRESENTATIONS OF ONE THING, AND [DwTraceValues.wire] IS THE AUTHORITY. The flat map exists so
 * a slider can read its own value in one lookup; the text is what goes back, unaltered, so a leaf
 * this Kotlin has never heard of survives a round trip instead of being dropped by a re-serialisation.
 * `DwSketchTraceEngine.kt` makes the same argument where it declares the class.
 */
internal fun dwTraceValuesOf(wire: String): DwTraceValues {
    val tree = runCatching { dwTraceJson.parseToJsonElement(wire) }.getOrNull() as? JsonObject
        ?: throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "a parameter tree that is not a JSON object",
        )
    val leaves = LinkedHashMap<String, DwTraceValue>()
    dwTraceFlatten(tree, "", leaves)
    return DwTraceValues(leaves, wire)
}

/**
 * Walks the tree into dot paths — `edge.flow.sigmaM`, the same keys the web's table uses.
 *
 * **`JsonNull` IS A `JsonPrimitive`**, so it has to be tested first or `output.background: null`
 * decodes as the string "null" and the "White background" toggle reads as ON forever. That is the
 * kind of bug a `when` over a sealed hierarchy invites and a test has to catch;
 * `DwSketchTraceWireTest` does.
 *
 * ARRAYS ARE NOT LEAVES AND ARE SKIPPED. There is exactly one today — `auto.handTuned`, a
 * `string[]` — and it is not something a control reads. It is carried in [DwTraceValues.wire] with
 * everything else, which is why skipping it here loses nothing.
 */
private fun dwTraceFlatten(tree: JsonObject, prefix: String, out: MutableMap<String, DwTraceValue>) {
    for ((key, value) in tree) {
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        when {
            value is JsonNull -> out[path] = DwTraceValue.Absent
            value is JsonObject -> dwTraceFlatten(value, path, out)
            value is JsonArray -> Unit
            value is JsonPrimitive -> out[path] = dwTraceLeafOf(value, path)
            else -> Unit
        }
    }
}

private fun dwTraceLeafOf(primitive: JsonPrimitive, path: String): DwTraceValue {
    if (primitive.isString) return DwTraceValue.Choice(primitive.content)
    return when (val raw = primitive.content) {
        "true" -> DwTraceValue.Flag(true)
        "false" -> DwTraceValue.Flag(false)
        else -> raw.toDoubleOrNull()?.let { DwTraceValue.Num(it) }
            ?: throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                "$path is neither a number, a flag nor a name",
            )
    }
}

/**
 * A patch, as the JSON the bundle hands to the engine's own `withOverrides`.
 *
 * NON-FINITE IS A REFUSAL, NOT A SILENT SUBSTITUTION. `NaN` and the infinities have no JSON spelling,
 * so the alternatives are to emit invalid JSON, to drop the key, or to say so. Dropping it is the
 * worst of the three — the slider moves, the trace runs, and the parameter the designer changed is
 * the one that did not change. `NonFiniteValueTest` exists in this module because that class of bug
 * has already been shipped here once.
 */
internal fun dwTracePatchJson(patch: Map<String, DwTraceValue>): String {
    val out = StringBuilder(patch.size * 24 + 2)
    out.append('{')
    var first = true
    for ((key, value) in patch) {
        if (!first) out.append(',')
        first = false
        out.append(dwTraceJsonString(key)).append(':')
        when (value) {
            is DwTraceValue.Num -> {
                if (!value.value.isFinite()) {
                    throw DwTraceHostFailure(
                        DwTraceFailureKind.PROTOCOL_UNREADABLE,
                        "$key was set to ${value.value}, which is not a number the engine can be sent",
                    )
                }
                out.append(value.value.toString())
            }
            is DwTraceValue.Flag -> out.append(if (value.value) "true" else "false")
            is DwTraceValue.Choice -> out.append(dwTraceJsonString(value.value))
            DwTraceValue.Absent -> out.append("null")
        }
    }
    out.append('}')
    return out.toString()
}

/** Decodes the `result` of a `done` envelope. Refuses rather than half-reads. */
private fun dwTraceDecodeResult(element: JsonElement?): DwTraceDecoded {
    val result = element as? JsonObject
        ?: throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, "a done with no result")
    val geometry = dwTraceDecodeGeometry(result["geometry"])
    geometry.validate()
    return DwTraceDecoded(
        svg = result.str("svg").orEmpty(),
        geometry = geometry,
        background = result.argb("background"),
        width = result.int("width", 0),
        height = result.int("height", 0),
        workingWidth = result.int("workingWidth", 0),
        workingHeight = result.int("workingHeight", 0),
        shapeCount = result.int("shapeCount", 0),
        nodeCount = result.int("nodeCount", 0),
        stages = result.stages(),
        totalMillis = result.long("totalMillis", 0L),
        notes = result.strings("notes"),
        appliedParams = dwTraceValuesOf(result.str("appliedParams").orEmpty()),
        autoSubjectId = result.str("autoSubjectId").orEmpty(),
        suggestedStyleId = result.str("suggestedStyleId").orEmpty(),
    )
}

private fun dwTraceDecodeGeometry(element: JsonElement?): DwTraceGeometry {
    val g = element as? JsonObject
        ?: throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, "a result with no geometry")
    return DwTraceGeometry(
        coords = dwTraceFloats(g.str("coords").orEmpty(), "coords"),
        verbs = dwTraceBytes(g.str("verbs").orEmpty(), "verbs"),
        verbStarts = dwTraceInts(g.str("verbStarts").orEmpty(), "verbStarts"),
        coordStarts = dwTraceInts(g.str("coordStarts").orEmpty(), "coordStarts"),
        closed = dwTraceBytes(g.str("closed").orEmpty(), "closed"),
        styleTable = (g["styleTable"] as? JsonArray).orEmpty().map { dwTraceStyleOf(it) },
        styleIndex = dwTraceInts(g.str("styleIndex").orEmpty(), "styleIndex"),
    )
}

private fun dwTraceStyleOf(element: JsonElement): DwTraceStyle {
    val s = element as? JsonObject
        ?: throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, "a style that is not an object")
    return DwTraceStyle(
        stroke = s.argb("stroke"),
        strokeWidth = s.float("strokeWidth", 1.5f),
        fill = s.argb("fill"),
        // The engine's own defaults (`engine/path.ts:571-580`), used only when a field is absent —
        // which a conforming bundle never leaves it. A default is not a clamp: nothing here narrows a
        // value the engine did send.
        fillRule = s.str("fillRule") ?: "EVENODD",
        cap = s.str("cap") ?: "ROUND",
        join = s.str("join") ?: "ROUND",
        miterLimit = s.float("miterLimit", 4f),
        opacity = s.float("opacity", 1f),
    )
}

/* ── base64 to typed arrays ───────────────────────────────────────────────────────────────────
 *
 * LITTLE-ENDIAN, EXPLICITLY. `ByteBuffer` defaults to BIG-endian, and JavaScript's typed arrays are
 * platform-endian — little on every ABI this app ships. Leaving the default in place would read every
 * coordinate byte-swapped, which does not throw: it draws noise, on a phone, in a courtyard.
 */

private fun dwTraceRawBytes(base64: String, field: String): ByteArray =
    runCatching { Base64.getDecoder().decode(base64) }.getOrElse {
        throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, "$field is not base64")
    }

private fun dwTraceBytes(base64: String, field: String): ByteArray = dwTraceRawBytes(base64, field)

private fun dwTraceFloats(base64: String, field: String): FloatArray {
    val bytes = dwTraceRawBytes(base64, field)
    if (bytes.size % 4 != 0) {
        throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "$field is ${bytes.size} bytes, which is not a whole number of float32s",
        )
    }
    val out = FloatArray(bytes.size / 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    return out
}

private fun dwTraceInts(base64: String, field: String): IntArray {
    val bytes = dwTraceRawBytes(base64, field)
    if (bytes.size % 4 != 0) {
        throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "$field is ${bytes.size} bytes, which is not a whole number of uint32s",
        )
    }
    val out = IntArray(bytes.size / 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
    return out
}

/* ── small readers over the element tree ──────────────────────────────────────────────────────
 *
 * Written against `JsonObject`, `JsonArray` and `JsonPrimitive.content` alone, without the
 * `kotlinx.serialization.json` extension properties (`.int`, `.double`, `.boolean`). Those throw on a
 * type they did not expect; these return a default, and a protocol fault is meant to arrive as one
 * sentence from one place rather than as whichever field happened to be read first.
 */

private fun JsonObject.prim(key: String): JsonPrimitive? {
    val value = this[key]
    if (value is JsonNull) return null
    return value as? JsonPrimitive
}

private fun JsonObject.str(key: String): String? = prim(key)?.content

private fun JsonObject.int(key: String, fallback: Int): Int =
    prim(key)?.content?.toDoubleOrNull()?.toInt() ?: fallback

private fun JsonObject.long(key: String, fallback: Long): Long =
    prim(key)?.content?.toDoubleOrNull()?.toLong() ?: fallback

private fun JsonObject.float(key: String, fallback: Float): Float =
    prim(key)?.content?.toFloatOrNull() ?: fallback

private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
    when (prim(key)?.content) {
        "true" -> true
        "false" -> false
        else -> fallback
    }

/**
 * A packed ARGB colour, or null for the engine's "none".
 *
 * Through `Double` and then `Long` because `0xff000000` is 4,278,190,080 — past `Int.MAX_VALUE`, so a
 * direct `toIntOrNull()` answers null and every stroke would silently become "no stroke". `toInt()`
 * on the `Long` is the narrowing that yields `-16777216`, which is the ARGB `Int` Android draws with.
 */
private fun JsonObject.argb(key: String): Int? =
    prim(key)?.content?.toDoubleOrNull()?.toLong()?.toInt()

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

private fun JsonObject.events(): List<DwTraceProgress> =
    (this["events"] as? JsonArray).orEmpty().mapNotNull { element ->
        val event = element as? JsonObject ?: return@mapNotNull null
        DwTraceProgress(
            stageId = event.str("stageId").orEmpty(),
            label = event.str("label").orEmpty(),
            fraction = event.float("fraction", 0f),
        )
    }

private fun JsonObject.stages(): List<DwTraceStageTiming> =
    (this["stages"] as? JsonArray).orEmpty().mapNotNull { element ->
        val stage = element as? JsonObject ?: return@mapNotNull null
        DwTraceStageTiming(
            id = stage.str("id").orEmpty(),
            label = stage.str("label").orEmpty(),
            millis = stage.long("millis", 0L),
        )
    }

private fun JsonObject.presets(key: String): List<DwTracePreset> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
        val preset = element as? JsonObject ?: return@mapNotNull null
        val id = preset.str("id").orEmpty()
        if (id.isEmpty()) return@mapNotNull null
        DwTracePreset(
            id = id,
            name = preset.str("name").orEmpty(),
            description = preset.str("description").orEmpty(),
            group = preset.str("group").orEmpty(),
        )
    }

/* ── string literals, on the way out ──────────────────────────────────────────────────────────── */

/**
 * [value] as a JSON string literal.
 *
 * Hand-written rather than `Json.encodeToString`, and the reason is the same one `ApiModels.kt` gives
 * for building its metadata by hand: this is three lines of escaping used in two places, against a
 * serializer call that would have to be given a type. `U+2028` and `U+2029` are escaped even though
 * JSON permits them raw, because the output of [dwTraceJsString] below is JavaScript SOURCE, where
 * they were a syntax error until ES2019 and are still a hazard in anything that re-parses it.
 */
internal fun dwTraceJsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch == '\b' -> out.append("\\b")
            ch == '\u000C' -> out.append("\\f")
            ch < ' ' || ch == '\u2028' || ch == '\u2029' ->
                out.append("\\u").append(String.format("%04x", ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}

/**
 * [value] as a JavaScript string literal, for splicing into a script.
 *
 * The same escaping, named separately because the two uses are different obligations and only one of
 * them is about a parameter tree. A future host that stops building scripts as text — passing
 * arguments some other way — deletes this one and keeps [dwTraceJsonString].
 */
internal fun dwTraceJsString(value: String): String = dwTraceJsonString(value)
