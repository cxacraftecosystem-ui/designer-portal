package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import com.designprototype.workshop.data.dwProbeDevice
import com.offlinetracer.export.ExportFormat
import com.offlinetracer.export.ExportOptions
import com.offlinetracer.export.SvgExport
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.pipeline.CancellationToken
import com.offlinetracer.pipeline.CancelledException
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.Pipeline
import com.offlinetracer.pipeline.ProgressListener
import com.offlinetracer.pipeline.Stages
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.pipeline.TraceResult
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **THE [DwTraceRuntime] BACKED BY THE VENDORED KOTLIN ENGINE, IN THIS PROCESS.**
 *
 * ── WHAT THIS IS, AND WHAT IT REPLACED ────────────────────────────────────────────────────────
 *
 * `DwSketchTraceEngine.kt` is the port the whole trace surface is written against, and **this is its
 * one implementation.** There was a second — `DwSketchTraceRuntime`, which drove the vendored
 * TypeScript inside an `androidx.javascriptengine` isolate — and a third that did no work,
 * `DwTraceRuntimeUnavailable`, which existed because that route could be unavailable on a phone. The
 * owner chose this one and both are deleted along with the bundle, the dependency and the sandbox
 * file. Nothing above the interface knew which was wired and nothing above it changed for the swap:
 * the panel takes a [DwTraceRuntime], and this file imports no composable, no panel and no export
 * card.
 *
 * The engine is the same engine. `android/UPSTREAM-MANIFEST-KOTLIN.txt` hashes the 101 vendored
 * Kotlin sources against `F:/Offline-Tracer/android`, `frontend/lib/trace/UPSTREAM-MANIFEST.txt`
 * hashes the 43 vendored TypeScript ones, and `:core-pipeline`'s own `ParityTest` replays the three
 * shared fixtures under `docs/fixtures` through the Kotlin engine and holds it to the TypeScript's
 * numbers. (A glob is spelled out rather than written, because Kotlin block comments NEST and a
 * slash-star inside one opens a second comment that never closes — which is how this file failed to
 * compile the first time.)
 *
 * ── WHAT THIS RUNTIME BUYS OVER THE ISOLATE, AND WHAT IT GIVES UP ─────────────────────────────
 *
 * BUYS. **A real mid-trace cancel.** The isolate route's only hard stop was closing the isolate — a
 * process-boundary kill of an engine that was told to stop and might not have been listening. Here
 * `CancellationToken.cancel()` is a volatile write on the same heap, the engine checks it at every
 * stage boundary and inside every long stage (`Pipeline.kt:80-88`), and a cancelled coroutine sets it
 * synchronously through the guard in [dwTraceKotlinRunEngine]. It also buys the elimination of an
 * entire protocol: no base64, no envelope, no pump loop, no `SANDBOX_DIED`, no bundle to package and
 * no `BUNDLE_CONTRACT_MISMATCH` — the four failure kinds that describe the bridge cannot occur.
 *
 * GIVES UP. **The other process, and this is the real cost of the port.** `dwTraceHeapCapBytes`'s
 * docblock names it as "the single strongest argument for `JavaScriptSandbox` over an in-process
 * engine": the isolate's 72–278 MB peak lived in the WebView sandbox's own address space, and this
 * runtime's identical peak lives in THIS app's Java heap, beside the composition, the draft store and
 * every bitmap the workshop is holding. That is the one thing about this route that can hurt a
 * designer, so it is measured before every trace rather than hoped about — see [dwTraceKotlinHeapBytes]
 * and the peak-heap arithmetic below it. **Nobody has run either route on a handset**, so this
 * paragraph is the argument and not the verdict.
 *
 * ── AND THE THREE PLACES THIS ENGINE AND THE PORTAL'S ARE NOT THE SAME ENGINE ─────────────────
 *
 * Each is argued where it is decided, and each is pinned by a case in `DwTraceKotlinRuntimeTest` so
 * the claim cannot rot:
 *
 *  1. **Nineteen stages against twelve** — [DW_TRACE_KOTLIN_STAGES]. The progress bar and its label
 *     absorb it; the spoken "Stage n of 12" does not.
 *  2. **A different SVG writer** — [dwTraceKotlinSvgOf]. The same drawing, different bytes, measured.
 *  3. **No suggested style** — [DW_TRACE_KOTLIN_NO_SUGGESTION_NOTE]. The Kotlin classifier answers
 *     with a sentence where the TypeScript answers with a preset id.
 *
 * A fourth, the preview rule, is a divergence this file chose ON PURPOSE and against the vendored
 * Kotlin — [dwTraceKotlinPreviewParams] carries the argument and the remedy.
 *
 * ── THE MEMORY A TRACE COSTS, AS ARITHMETIC ───────────────────────────────────────────────────
 *
 * The input is bounded twice over: `DwSketchRectify.RECTIFY_MAX_EDGE_PX` (`DwSketchRectify.kt:151`)
 * caps a rectified plate at 1600 px on its long edge, and [DW_TRACE_DECODE_MAX_EDGE_PX] caps any
 * other photograph at 4096. Take the intended input, a 1600x1200 plate — 1,920,000 pixels:
 *
 *  - **This file's own buffers: 15.4 MB.** `DwSketchTracePlates.readRgba` produces one RGBA
 *    `ByteArray` (4 B/px, 7.7 MB) and [dwTraceKotlinImageOf] turns it into the packed-ARGB `IntArray`
 *    an [RgbaImage] is (4 B/px, 7.7 MB). Both are live for the whole trace: the engine holds the
 *    second, and the first is what the comparison plate is painted from afterwards.
 *  - **The engine's working set: 40–152 bytes per WORKING pixel**, which is [DW_TRACE_KOTLIN_BPP].
 *    Those numbers are the feasibility spike's own measurements at exactly this size, quoted in
 *    `dwTraceHeapCapBytes`: ADAPTIVE +93 MB, LOG +76 MB, FDOG +72 MB, **CANNY +278 MB**. They were
 *    measured on V8, and they are used here for the JVM because the two engines allocate the same
 *    planes at the same sizes — a `Float32Array` and a `FloatArray` are both 4 B/px, a `Uint8Array`
 *    and a `BooleanArray` are both 1 B/px — so the count of buffers is a property of the pipeline
 *    rather than of the language. Structural agreement, checked: the stages hold three `RgbaImage`
 *    (12 B/px), five `GrayF` plus the edge response and the distance transform (28 B/px) and six
 *    `Mask` (6 B/px) simultaneously, which is 46 B/px — beside ADAPTIVE's measured 51.
 *  - **The result: up to [DW_TRACE_KOTLIN_RESULT_BYTES], 24 MB.** The geometry's flat arrays (the
 *    spike's worst serialised result was 2.88 MiB at 20,975 shapes), the SVG string, and the
 *    `ByteArray` `SvgExport` returns before it is decoded into that string.
 *  - **The two display plates are NOT on this heap.** `minSdk = 26` and Android 8.0 moved `Bitmap`
 *    pixel storage to native memory, so the 8.4 MB pair costs the process without costing
 *    `Runtime.maxMemory()`. They are named here because they are real, and excluded from the estimate
 *    because counting them against the Java heap would refuse traces that fit.
 *
 * So a full-resolution trace of the intended input costs **112 MB with the shipped FDOG default and
 * 317 MB with CANNY**, in this app's heap — 14.7 + 73.2 + 24, and 14.7 + 278.3 + 24, in MiB.
 * `DwTraceKotlinRuntimeTest` pins both figures, so this paragraph cannot rot quietly.
 *
 * `AndroidManifest.xml:57` declares `android:largeHeap`, which on most handsets is the difference
 * between `dalvik.vm.heapgrowthlimit` (typically 128–256 MB)
 * and `dalvik.vm.heapsize` (typically 256–512 MB) — but "typically" is not a bound, so nothing here
 * assumes it. [dwTraceKotlinHeapBytes] asks the running VM what it actually has, every time.
 *
 * **AND IT IS BOUNDED, NOT JUST STATED.** [dwTraceKotlinMemoryRefusal] runs after the decode and
 * before the first stage, compares the estimate above against what this VM can still hand out, and
 * refuses in a sentence naming both numbers and the two remedies. A refusal costs a designer one
 * re-tap at a lower resolution; an `OutOfMemoryError` at stage fourteen costs them the trace, and on
 * a phone that is also holding an unsaved stage it can cost more than that.
 *
 * ── EVERY NOTE REACHES THE SCREEN, INCLUDING THE ONE THIS FILE ADDS ───────────────────────────
 *
 * `Pipeline.kt:124-129` calls rendering [TraceResult.notes] a REQUIREMENT and names the bug it
 * prevents: "a pipeline that silently discarded four thousand paths and one that genuinely found
 * nothing produce the same blank canvas". They are carried into [DwTraceDecoded.notes] unabridged and
 * in order, and the SVG writer's own cap ([DW_TRACE_KOTLIN_MAX_SHAPES]) appends one more sentence
 * when it bites — the portal's own sentence, copied rather than re-worded, so one cut is not
 * described two ways in one archive.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * No clamp table, no merge, no preset table and no flattener. `DwTraceKotlinParams.kt` owns the
 * translation between the panel's flat `DwTraceValues` and the engine's nested `TraceParams`;
 * `DwTraceKotlinPresets.kt` owns `Styles.ALL`/`Subjects.ALL` and the two `apply` verbs. This file is
 * the composition of those two with `Pipeline.run`, the pixels and the plates, and nothing else —
 * `DwSketchTrace.kt` is the same shape for the isolate, for the same reason.
 *
 * It also never writes. `sketch.lineArtFile` is written by a button in the panel; a runtime that
 * wrote would make the record assert that a named human produced a value no human pressed for, which
 * is `DwSketchTraceWire.kt`'s header's rule and the incident behind it.
 */

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 1. IDENTITY — which writer wrote the file, and which stage list ran
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Which SVG writer produced [DwTraceResult.svg] on this route. The mirror of `DwTraceHello.svgWriter`.
 *
 * Reported so a bug report can name it without opening the file, because **the two runtimes do not
 * write the same bytes** and that is the first thing anyone comparing two drawings needs to know. See
 * [dwTraceKotlinSvgOf] for what differs and what does not.
 */
const val DW_TRACE_KOTLIN_SVG_WRITER: String = "core-export/SvgExport#export"

/**
 * The engine's own stage list, read from `Stages.ALL` rather than transcribed.
 *
 * **NINETEEN, WHERE `DW_TRACE_STAGES` HAS TWELVE, AND THAT IS A REAL DIVERGENCE BETWEEN THE TWO
 * VENDORED ENGINES.** The TypeScript's `STAGES` fuses several steps the Kotlin reports separately:
 * its `prepare` is `orient` + `perspective` + `downscale`, its `cleanup` is `binarise` + `morphology`
 * + `blobs` + `bridge`, and it spells three ids differently (`gray`/`grayscale`,
 * `vectorize`/`vectorise`, `document`/`assemble`).
 *
 * Most of the surface absorbs that correctly and by design. The progress row renders
 * [DwTraceProgress.label] — the engine's own string — and never the table's
 * (`DwSketchTraceStages.kt:13-19`); `DwTraceProgressWeights.fractionAt` falls back to the fraction
 * the engine sent for an id it does not know; and after one completed run
 * `DwTraceProgressWeights.from` rebuilds the weights from the engine's OWN ids and timings, so this
 * route's nineteen stages get correctly measured weights with nothing edited.
 *
 * **`dwTraceProgressSentence` did NOT absorb it, and that was fixed when this runtime was wired.**
 * It looked the id up in the twelve-row table and said "Stage n of 12". Seven of these nineteen ids
 * collide with that table — `denoise`, `contrast`, `matte`, `crop`, `edge`, `skeleton`, `distance` —
 * and five of the seven sit at a different position in it, so a screen reader would have announced a
 * wrong stage number the moment a Kotlin trace ran. The remedy this comment used to name is the one
 * that was taken: the list is a parameter now, the panel passes this constant, and the sentence says
 * "Stage 7 of 19" against the stages that actually ran. `DwTraceKotlinRuntimeTest` pins both the
 * divergence and the fix, so neither claim can rot.
 */
val DW_TRACE_KOTLIN_STAGES: List<DwTraceStage> =
    Stages.ALL.map { DwTraceStage(id = it.id, label = it.label) }

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 2. MEMORY — measured before the first stage, refused in a sentence
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Peak working-set bytes per WORKING pixel, per edge engine. See the file header for where they
 * come from and why V8's measurements are used for the JVM.
 *
 * Each is the spike's figure at 1600x1200 (1,920,000 px) divided by that pixel count and rounded up:
 * CANNY 278 MiB → 152, ADAPTIVE 93 MiB → 51, LOG 76 MiB → 42, FDOG 72 MiB → 40.
 *
 * **XDOG AND MODEL WERE NOT MEASURED AND TAKE THE LARGEST MEASURED VALUE.** That is deliberately
 * pessimistic and it is this repository's own discipline for an unknown — `dwTraceCeilings` takes the
 * cautious half for a phone whose memory read failed, on the argument that "a handset that would have
 * said it was small must not be promoted by a lookup that failed". Being wrong here costs a refusal
 * with a named remedy at the very top of the resolution range; being wrong the other way costs an
 * `OutOfMemoryError` in a courtyard. Neither is free, and only one is recoverable. The remedy is to
 * measure them: run the spike's throughput matrix on a device and replace two entries.
 */
val DW_TRACE_KOTLIN_BPP: Map<EdgeEngine, Int> = linkedMapOf(
    EdgeEngine.CANNY to 152,
    EdgeEngine.ADAPTIVE to 51,
    EdgeEngine.LOG to 42,
    EdgeEngine.FDOG to 40,
    EdgeEngine.XDOG to 152,
    EdgeEngine.MODEL to 152,
)

/** Bytes per pixel this file's own two input buffers cost: one RGBA byte array plus one ARGB int array. */
const val DW_TRACE_KOTLIN_INPUT_BPP: Int = 8

/** Headroom for the finished geometry, the SVG string and the byte array it is decoded from. */
const val DW_TRACE_KOTLIN_RESULT_BYTES: Long = 24L * 1024L * 1024L

/**
 * Java heap left for the rest of the app while a trace runs.
 *
 * The composition, the draft store, the questionnaire the designer is halfway through and whatever
 * the workshop has open behind this panel. A trace that fits only by taking all of it is a trace that
 * finishes and then kills the screen it was going to be shown on.
 */
const val DW_TRACE_KOTLIN_HEAP_RESERVE_BYTES: Long = 32L * 1024L * 1024L

/**
 * The Java heap this VM could still hand out, in bytes.
 *
 * `maxMemory` is the ceiling ART will grow to for this process — `android:largeHeap` is what makes it
 * the large one — and `total - free` is what is already committed and live. The difference is what a
 * trace may ask for. It is read at the moment of asking rather than once at construction, because a
 * designer who has been photographing a courtyard for ten minutes has a fuller heap than one who has
 * just opened the app, and the answer for the two is genuinely different.
 */
fun dwTraceKotlinHeapBytes(runtime: Runtime = Runtime.getRuntime()): Long =
    runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

/**
 * What one trace will cost this heap, in bytes.
 *
 * @param sourcePixels the photograph as the engine is handed it, after any crop
 * @param workingPixels what the pipeline will actually run the stages at, i.e. [sourcePixels] scaled
 *   down to `preprocess.workingLongEdge`. The stages dominate and they run at the working size, so
 *   using the source size here would refuse traces the downscale makes comfortable.
 */
fun dwTraceKotlinPeakBytes(sourcePixels: Long, workingPixels: Long, engine: EdgeEngine): Long {
    val perPixel = DW_TRACE_KOTLIN_BPP[engine] ?: DW_TRACE_KOTLIN_BPP.values.max()
    return sourcePixels * DW_TRACE_KOTLIN_INPUT_BPP + workingPixels * perPixel + DW_TRACE_KOTLIN_RESULT_BYTES
}

/**
 * The working pixel count a trace of [width] x [height] will run at under [params].
 *
 * `Preview.kt:66-71`'s rule, which is also `StageOps.downscale`'s: the working long edge is the
 * smaller of what was asked for and what the source has, because the pipeline never upscales.
 */
fun dwTraceKotlinWorkingPixels(width: Int, height: Int, params: TraceParams): Long {
    val (w, h) = dwTraceWorkingSize(width, height, params.preprocess.workingLongEdge)
    return w.toLong() * h.toLong()
}

/**
 * @returns the sentence to refuse this trace with, or null when it fits.
 *
 * REFUSED BEFORE THE FIRST STAGE AND NOT AFTER THE LAST. The check is worth having only if it runs
 * while nothing has been allocated and nothing has been computed — a refusal after twelve seconds of
 * arithmetic is a failure with extra steps.
 *
 * It errs high by one buffer, knowingly. The caller reads [dwTraceKotlinHeapBytes] with the RGBA
 * bytes already allocated — they are what the crop was taken out of — while [dwTraceKotlinPeakBytes]
 * counts them again in its 8 bytes per source pixel. That is 7.7 MB of double-counting at the input
 * cap, in the direction of refusing a trace that would just have fitted rather than starting one that
 * will not.
 *
 * The sentence carries both numbers because "not enough memory" without them cannot be acted on, and
 * it names the two remedies that actually change the arithmetic: the resolution multiplies
 * [workingPixels], and the edge engine multiplies the bytes each of them costs. Closing other apps
 * is third because it moves the smallest term — this app's heap ceiling does not grow when another
 * app closes; only what is already live inside it can shrink.
 */
fun dwTraceKotlinMemoryRefusal(
    sourcePixels: Long,
    workingPixels: Long,
    engine: EdgeEngine,
    heapBytes: Long,
): String? {
    val peak = dwTraceKotlinPeakBytes(sourcePixels, workingPixels, engine)
    if (peak + DW_TRACE_KOTLIN_HEAP_RESERVE_BYTES <= heapBytes) return null
    val need = dwTraceKotlinMegabytes(peak)
    val have = dwTraceKotlinMegabytes(heapBytes - DW_TRACE_KOTLIN_HEAP_RESERVE_BYTES)
    return "This trace needs about $need MB of memory and this phone can spare about $have MB right " +
        "now, so it has not been started rather than started and lost part-way. Set the trace " +
        "resolution lower, or choose a different edge engine — Canny needs about three times the " +
        "memory of the others — and close other apps if you can."
}

/**
 * Whole megabytes, never negative, for a sentence a person reads.
 *
 * Rounded rather than truncated so the number in the refusal is the same number the file header
 * states for the same trace — 317 and not 316. A sentence and a comment that disagree by one about
 * the same arithmetic is a reader's afternoon.
 */
private fun dwTraceKotlinMegabytes(bytes: Long): Long =
    if (bytes <= 0L) 0L else Math.round(bytes / (1024.0 * 1024.0))

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 3. PIXELS IN — the engine's packing, and nobody else's
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The bytes `DwSketchTracePlates.readRgba` produced, as the [RgbaImage] the engine takes.
 *
 * **THE EXACT INVERSE OF [dwTraceArgbRowToRgba], AND THAT IS ASSERTED RATHER THAN INTENDED.** That
 * function's own docblock says why it is the piece most able to be quietly wrong: "swap two channels
 * and the engine still traces — it traces a picture with red and blue exchanged, which on a pencil
 * sketch on cream paper looks very nearly right and comes out quietly different from the portal's
 * answer forever". `DwTraceKotlinRuntimeTest` round-trips a block of pixels through both functions
 * and demands the original integers back, so the pair cannot drift apart.
 *
 * `RgbaImage.argb` packs `(a shl 24) or (r shl 16) or (g shl 8) or b`, which is Android's own
 * `Color`/`Bitmap.getPixels` layout, so the ints this produces are the ints the bitmap held. The RGBA
 * byte order in between is the marshalling the isolate route needs; going bitmap → bytes → ints
 * rather than bitmap → ints keeps ONE crop implementation ([dwTraceCropRgba]) and ONE plate builder
 * (`DwSketchTracePlates.photographPlate`) serving both runtimes, at the cost of the second buffer
 * counted in the file header.
 *
 * @throws DwTraceHostFailure when [rgba] is shorter than `width * height * 4`
 */
fun dwTraceKotlinImageOf(rgba: ByteArray, width: Int, height: Int): RgbaImage {
    if (width < 1 || height < 1) {
        throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_EMPTY, "${width}x$height")
    }
    val count = width.toLong() * height.toLong()
    if (rgba.size.toLong() < count * 4L) {
        throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "${rgba.size} bytes for a ${width}x$height image, which needs ${count * 4L}",
        )
    }
    val pixels = IntArray(count.toInt())
    var b = 0
    for (i in pixels.indices) {
        val r = rgba[b].toInt() and 0xFF
        val g = rgba[b + 1].toInt() and 0xFF
        val bl = rgba[b + 2].toInt() and 0xFF
        val a = rgba[b + 3].toInt() and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        b += 4
    }
    return RgbaImage(width, height, pixels)
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 4. GEOMETRY OUT — the worker's own serialisation, in Kotlin
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * A finished [VecDocument] as the flat arrays [DwTraceGeometry] mirrors.
 *
 * **A LINE-FOR-LINE PORT OF `worker/trace.worker.ts:199-272`'s `serializeGeometry`**, which is what
 * the other runtime's geometry has been through, because `DwSketchTracePlates` and the PNG export
 * walk these arrays and must find the same layout whichever engine filled them: shapes concatenated
 * across layers in layer order, a shape's coordinate run beginning with its start point, two floats
 * per line, four per quad, six per cubic, and `starts` arrays one longer than the shape count so an
 * extent is a subtraction.
 *
 * The style table is de-duplicated exactly as the worker's is — it keys on all eight fields, so a run
 * of 50,000 identically-styled paths is one entry. The worker builds a string key out of the eight;
 * this uses the `VecStyle` data class itself as the map key, which compares the same eight fields by
 * value. Same partition, no string per shape.
 *
 * NOT A RE-MODEL. `DwTraceGeometry`'s header forbids rebuilding the geometry into Kotlin path
 * objects — "the moment there is a Kotlin opinion about what a cubic segment IS, there is something
 * for the two clients to disagree about". This copies numbers into arrays and forms no opinion.
 */
fun dwTraceKotlinGeometryOf(doc: VecDocument): DwTraceGeometry {
    val shapes = ArrayList<VecShape>(doc.shapeCount())
    for (layer in doc.layers) shapes.addAll(layer.shapes)

    var segTotal = 0
    var coordTotal = 0
    for (shape in shapes) {
        val segs = shape.path.segments
        segTotal += segs.size
        coordTotal += 2
        for (seg in segs) coordTotal += dwTraceKotlinCoordsOf(seg)
    }

    val coords = FloatArray(coordTotal)
    val verbs = ByteArray(segTotal)
    val verbStarts = IntArray(shapes.size + 1)
    val coordStarts = IntArray(shapes.size + 1)
    val closed = ByteArray(shapes.size)
    val styleIndex = IntArray(shapes.size)
    val styleTable = ArrayList<DwTraceStyle>()
    val styleKeys = LinkedHashMap<VecStyle, Int>()

    var v = 0
    var c = 0
    for (s in shapes.indices) {
        val shape = shapes[s]
        verbStarts[s] = v
        coordStarts[s] = c
        closed[s] = if (shape.path.closed) 1 else 0

        styleIndex[s] = styleKeys.getOrPut(shape.style) {
            styleTable.add(dwTraceKotlinStyleOf(shape.style))
            styleTable.size - 1
        }

        coords[c++] = shape.path.start.x
        coords[c++] = shape.path.start.y
        for (seg in shape.path.segments) {
            when (seg) {
                is VecSeg.Line -> {
                    verbs[v++] = DW_TRACE_VERB_LINE
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }

                is VecSeg.Quad -> {
                    verbs[v++] = DW_TRACE_VERB_QUAD
                    coords[c++] = seg.c.x
                    coords[c++] = seg.c.y
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }

                is VecSeg.Cubic -> {
                    verbs[v++] = DW_TRACE_VERB_CUBIC
                    coords[c++] = seg.c1.x
                    coords[c++] = seg.c1.y
                    coords[c++] = seg.c2.x
                    coords[c++] = seg.c2.y
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }
            }
        }
    }
    verbStarts[shapes.size] = v
    coordStarts[shapes.size] = c

    return DwTraceGeometry(
        coords = coords,
        verbs = verbs,
        verbStarts = verbStarts,
        coordStarts = coordStarts,
        closed = closed,
        styleTable = styleTable,
        styleIndex = styleIndex,
    )
}

/** Coordinates one segment contributes. The same two/four/six the worker counts. */
private fun dwTraceKotlinCoordsOf(seg: VecSeg): Int = when (seg) {
    is VecSeg.Line -> 2
    is VecSeg.Quad -> 4
    is VecSeg.Cubic -> 6
}

/**
 * One `VecStyle` as the mirror the plates read.
 *
 * The three enums cross as their NAMES, which is what `DwTraceStyle` asks for and why: the vendored
 * TypeScript's `FillRule`, `LineCap` and `LineJoin` are string enums whose values equal their names,
 * so `LineCap.ROUND.name` here is the `"ROUND"` the isolate route sends, and `DwSketchTracePlates`
 * maps both with one `when`.
 */
private fun dwTraceKotlinStyleOf(style: VecStyle): DwTraceStyle = DwTraceStyle(
    stroke = style.stroke,
    strokeWidth = style.strokeWidth,
    fill = style.fill,
    fillRule = style.fillRule.name,
    cap = style.cap.name,
    join = style.join.name,
    miterLimit = style.miterLimit,
    opacity = style.opacity,
)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 5. THE DOCUMENT OUT — the vendored writer, with the branding off and the cap reported
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * How many shapes one SVG may carry. `geometryToSvg.ts:215`'s `MAX_SHAPES_PER_FILE`, to the digit.
 *
 * The number is the portal's because the two clients' files land in one archive and a ceiling that
 * differed between them would be a drawing that fits on a laptop and is cut on a handset. It is high
 * enough that a real sketch never meets it and a trace of a photograph of gravel does.
 */
const val DW_TRACE_KOTLIN_MAX_SHAPES: Int = 200000

/** What [dwTraceKotlinSvgOf] produced: the file, how much of the drawing reached it, and the cut. */
class DwTraceKotlinSvg(
    val svg: String,
    val shapesWritten: Int,
    /** Non-null exactly when [DW_TRACE_KOTLIN_MAX_SHAPES] truncated the drawing. Ready to show. */
    val truncationNote: String?,
)

/**
 * The vector document, as the vendored writer spells it.
 *
 * ── THREE OPTIONS, AND EACH ONE IS LOAD-BEARING ───────────────────────────────────────────────
 *
 * `includeMetadata = false` — **this is the one that matters.** `SvgWriter.write` stamps
 * `<title>Offline Tracer export</title>` and a `<desc>` into every file it writes by default, and
 * `android/bridge.ts:89-96` says exactly what that is: "another product's branding, which must not
 * reach a ministry submission". The portal's own writer emits neither. Turning it off is not a
 * formatting preference; leaving it on would put a third party's name into an archived government
 * document.
 *
 * `flattenLayers = true` — the pipeline assembles exactly one layer (`Stages.kt:1167`), and the
 * portal's writer emits bare `<path>` elements with no `<g>` at all. Flattening a single visible
 * layer at full opacity discards nothing and makes the two files the same shape.
 *
 * `precision = 2` — both writers' own default, and the parity harness's
 * (`trace-parity-unit.spec.ts:363`). Coordinates run to 4096 with sub-pixel meaning; two places
 * resolve a hundredth of a pixel.
 *
 * ── WHAT STILL DIFFERS FROM THE FILE THE PORTAL ATTACHES, MEASURED NOT GUESSED ────────────────
 *
 * The portal attaches `components/sketches/upload/geometryToSvg.ts`'s `buildSvg`, and the isolate
 * route on this handset imports that same function so the two agree. This route cannot: `buildSvg` is
 * TypeScript, and the alternatives were re-typing it in Kotlin — a THIRD speller of the `d`
 * attribute, which `bridge.ts:98-101` names as the thing to avoid — or using the vendored writer,
 * which is what the brief asks for and what is manifest-pinned. So the bytes differ, in ways
 * `trace-parity-unit.spec.ts:334-352` had already measured between these same two writers:
 *
 *     buildSvg     M7.25 1 C7.83 1.08 8.42 1.17 9 1.25 C9 100.08 … 7.25 1 Z
 *     SvgWriter    M7.25 1C7.83 1.08 8.42 1.17 9 1.25 9 100.08 … 7.25 1Z
 *
 * — a space after the command letter, an explicit `C` on every cubic where `SvgPathData.toD` elides
 * the letter for a run of one type, and a space before `Z`. Observed in a real trace on 2026-08-28,
 * beyond what that spec measured: the XML declaration carries `standalone="no"`, the root element
 * carries `version="1.1"` and `px` units, every `<path>` carries the vectoriser's own `id="p0"`, and
 * the elements are indented two spaces. **None of it changes the drawing**: that spec parses both
 * writers' `d` strings back
 * through the engine's own reader and asserts identical start points, identical segments and
 * identical closure, shape for shape. What it does mean is that a byte comparison of a handset's file
 * against a laptop's will differ while the geometry does not, and anyone diffing two SVGs needs
 * [DW_TRACE_KOTLIN_SVG_WRITER] to know which they are holding.
 *
 * ── THE CAP IS REPORTED, NEVER SILENT ─────────────────────────────────────────────────────────
 *
 * `SvgWriter` has no ceiling of its own, so the document is trimmed to [DW_TRACE_KOTLIN_MAX_SHAPES]
 * before it is written and the cut comes back as a sentence the caller appends to the notes — the
 * portal's own sentence, copied rather than re-worded, because two phrasings of one cut read as two
 * different faults. The geometry is NOT trimmed, exactly as on the other route: the comparison plate
 * still shows the whole drawing, and only the file is cut.
 */
fun dwTraceKotlinSvgOf(doc: VecDocument): DwTraceKotlinSvg {
    val shapeCount = doc.shapeCount()
    val written = if (shapeCount > DW_TRACE_KOTLIN_MAX_SHAPES) DW_TRACE_KOTLIN_MAX_SHAPES else shapeCount
    val target = if (written == shapeCount) doc else dwTraceKotlinTrimmed(doc, written)
    val bytes = SvgExport.export(
        target,
        ExportOptions(
            format = ExportFormat.SVG,
            precision = 2,
            includeMetadata = false,
            flattenLayers = true,
        ),
    )
    return DwTraceKotlinSvg(
        svg = String(bytes, Charsets.UTF_8),
        shapesWritten = written,
        truncationNote = dwTraceKotlinTruncationNote(shapeCount, written),
    )
}

/** [doc] carrying only its first [limit] shapes, in the order [dwTraceKotlinGeometryOf] walks them. */
private fun dwTraceKotlinTrimmed(doc: VecDocument, limit: Int): VecDocument {
    var left = limit
    val layers = ArrayList<VecLayer>(doc.layers.size)
    for (layer in doc.layers) {
        if (left <= 0) break
        if (layer.shapes.size <= left) {
            layers.add(layer)
            left -= layer.shapes.size
        } else {
            layers.add(layer.copy(shapes = ArrayList(layer.shapes.subList(0, left))))
            left = 0
        }
    }
    return VecDocument(doc.width, doc.height, layers, doc.background)
}

/**
 * The sentence a designer is shown when the shape ceiling cut a drawing short, or null when it did not.
 *
 * `geometryToSvg.ts:295-302`'s `truncationNoteFor`, word for word, including the Indian digit
 * grouping its `toLocaleString("en-IN")` produces and the two control labels it names — both of which
 * exist on this panel verbatim (`DwSketchTraceParams.kt:535` and `:582`), so the remedy it offers
 * points at controls the designer can actually see.
 */
fun dwTraceKotlinTruncationNote(shapeCount: Int, shapesWritten: Int): String? {
    if (shapeCount <= shapesWritten) return null
    val count = dwTraceKotlinCount(shapeCount)
    val kept = dwTraceKotlinCount(shapesWritten)
    return "This drawing has $count separate paths and the file holds the first $kept. Raise " +
        "\u201cMinimum speck\u201d or \u201cSimplify\u201d and trace again to get a drawing that fits."
}

/**
 * A count as the portal's `toLocaleString("en-IN")` spells it — 2,00,000 and not 200,000.
 *
 * ── WRITTEN OUT RATHER THAN DELEGATED TO `NumberFormat`, WHICH WAS TRIED AND IS WRONG ─────────
 *
 * `NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(250_000)` returns
 * **"250,000"** on the Adoptium 21 this repository's unit tests run on, and **"2,50,000"** on
 * Android, whose formatter is ICU. Measured, not assumed: it is what made this function's first
 * version fail its own test. That is the worst shape a formatting bug can take — the sentence a
 * designer reads would differ from the sentence the test that guards it reads, and it would differ
 * again between two phones with different locale data.
 *
 * `ExportGeom.num` in `:core-export` makes exactly this call for exactly this reason ("the
 * locale-sensitive formatters emit a comma decimal separator in half of Europe … that failure only
 * reproduces on the affected device, which makes it exactly the kind of bug that ships").
 *
 * The rule is the Indian system's own: the last three digits, then twos.
 */
private fun dwTraceKotlinCount(value: Int): String {
    if (value < 1000) return value.toString()
    val digits = value.toString()
    val head = digits.substring(0, digits.length - 3)
    val out = StringBuilder()
    var i = head.length
    while (i > 2) {
        out.insert(0, "," + head.substring(i - 2, i))
        i -= 2
    }
    out.insert(0, head.substring(0, i))
    return out.append(',').append(digits.substring(digits.length - 3)).toString()
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 6. ONE TRACE — off the main thread, cancelled for real, decoded into the port's own shapes
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The working long edge a preview runs at. `worker/trace.worker.ts:46`'s `PREVIEW_LONG_EDGE`, and
 * `Preview.DEFAULT_LONG_EDGE` — the two vendored engines already agree about this number.
 */
const val DW_TRACE_KOTLIN_PREVIEW_LONG_EDGE: Int = 720

/**
 * [base] as a preview runs it: the working long edge lowered, and NOTHING ELSE TOUCHED.
 *
 * ── WHY THIS IS NOT `Preview.runPreview`, WHICH IS THE ONE PLACE THIS FILE DECLINES THE ────────
 * ── VENDORED KOTLIN AND FOLLOWS THE VENDORED TYPESCRIPT INSTEAD ────────────────────────────────
 *
 * The two vendored engines implement `runPreview` differently and this is not a spelling difference.
 * `frontend/lib/trace/engine/pipeline.ts:866-886` lowers `workingLongEdge` and re-sanitises, and that
 * is all it does. `:core-pipeline`'s `Preview.scaleToPreview` (`Preview.kt:88-119`) additionally
 * rescales THIRTEEN geometric knobs — `medianRadius`, `matte.feather`, `adaptiveRadius`,
 * `minBlobArea`, `closeRadius`, `openRadius`, `maxGap`, `pruneSpurs`, `fillHolesUpTo`, `simplify`,
 * `fitError`, `strokeWidth`, `minPathLength` — and its header argues the case well: a 24 px blob is
 * three pixels at a third of the resolution, so a preview that did not scale it drops things the
 * export keeps.
 *
 * **THE PANEL IS WHAT DECIDES THIS, AND IT DECIDES IT AGAINST THE BETTER IMPLEMENTATION.**
 * `DwSketchTracePanel.kt:549` adopts `traced.appliedParams` into the dock after every run, for a
 * reason of its own that is correct ("a dock that says one thing beside a drawing produced by
 * another"). `Pipeline.run` reports the parameters the stages RAN with. Put those two together with
 * `scaleToPreview` and one preview silently rewrites thirteen of the designer's settings to
 * preview-sized values — `minBlobArea` 24 → 3, `strokeWidth` 1.5 → 0.5 — and the full-resolution
 * trace they press next runs with them and attaches the result. Following the TypeScript instead
 * rewrites exactly one field, `preprocess.workingLongEdge`, which is what the isolate route on this
 * same handset already does today, so the two runtimes leave the dock in the same state.
 *
 * That is the whole argument, and it is a real cost: this route's previews therefore drop specks the
 * export keeps, which is the thing `Preview.kt`'s header exists to prevent. The better fix is for the
 * panel to stop adopting a PREVIEW's applied parameters — a preview is a rehearsal — after which this
 * function should become a call to `Preview.runPreview` and this comment should be deleted. That is a
 * change to a file this wave may not rewrite, so it is handed up rather than made.
 */
fun dwTraceKotlinPreviewParams(base: TraceParams): TraceParams = base
    .copy(preprocess = base.preprocess.copy(workingLongEdge = DW_TRACE_KOTLIN_PREVIEW_LONG_EDGE))
    .sanitized()

/**
 * Run one trace on [src] and hand back everything the port carries except the plates.
 *
 * ── OFF THE MAIN THREAD, AND THE PROGRESS EVENTS BACK ONTO THE CALLER'S ───────────────────────
 *
 * `Pipeline.run` is seconds of solid array arithmetic on the calling thread — it is not a suspend
 * function and it never yields — so it runs inside `withContext(Dispatchers.Default)` and the
 * `ProgressListener` fires on that pool's thread. The events are then handed back through an
 * unbounded [Channel] to a pump coroutine launched in the CALLER'S context: whatever dispatcher
 * called this is where [onProgress] is invoked, which is how the runtime below satisfies
 * `DwTraceRuntime.trace`'s "called on the main thread" without this function knowing what a main
 * thread is (and is why a JVM test can drive it with no looper at all).
 *
 * The channel also fixes the ordering a bare `launch(Main)` per event would not: it is drained in
 * order by one consumer, and it is closed and JOINED before this function returns, so no progress
 * event can land after the result it belongs to. A fire-and-forget dispatch can, and a progress row
 * that appears after the drawing is the kind of bug nobody can reproduce.
 *
 * ── ONE EVENT PER STAGE, AT ITS START, WHICH IS THE OTHER ENGINE'S SHAPE ──────────────────────
 *
 * The Kotlin `ProgressListener` is called TWICE per stage — once at the start with the fraction of
 * stages already finished and once at the end (`Pipeline.kt:68-77`) — where the TypeScript posts once
 * (`pipeline.ts:244`). The end event carries the same id and label as the start it follows, so
 * forwarding both would show every stage twice and would make the fraction mean two different things.
 * Only the first event for a given id is forwarded, which reproduces the other runtime's `index/n` at
 * the start of each stage exactly. `DwTraceProgress.fraction` therefore still never reaches 1.0 —
 * 18/19 here, 11/12 there — which is what `DwSketchTraceStages.kt:80-84` already tells the bar.
 *
 * ── AND CANCELLED FOR REAL ────────────────────────────────────────────────────────────────────
 *
 * A guard coroutine on [Dispatchers.Unconfined] parks in `awaitCancellation` and calls
 * `CancellationToken.cancel()` from its `finally`. Unconfined is the point: the guard resumes on
 * whatever thread cancelled the job, with no dispatch to wait for, so the token is set the instant
 * the coroutine is cancelled even though every thread in the Default pool is busy tracing. The engine
 * then unwinds at its next check — a stage boundary, or a sub-step inside a long stage — and throws
 * `CancelledException`, which is converted to the coroutine cancellation the port promises.
 *
 * Worst-case latency is one stage, which for `edge` at full resolution is seconds; the surface says
 * "Stopping…" rather than disappearing for exactly that reason. It is still a genuine improvement on
 * the isolate route, whose only stop was killing the isolate — and this one cannot leave the engine
 * mid-write, because `CancellationToken`'s own header explains that the engine never interrupts.
 *
 * @throws CancellationException when the calling job is cancelled. Not a failure.
 * @throws DwTraceHostFailure with a sentence for anything else.
 */
suspend fun dwTraceKotlinTrace(
    src: RgbaImage,
    params: TraceParams,
    preview: Boolean,
    onProgress: suspend (DwTraceProgress) -> Unit,
): DwTraceDecoded = coroutineScope {
    val events = Channel<DwTraceProgress>(Channel.UNLIMITED)
    val pump = launch { for (event in events) onProgress(event) }
    val result = try {
        dwTraceKotlinRunEngine(src, params, preview, events)
    } finally {
        events.close()
    }
    pump.join()
    dwTraceKotlinDecodedOf(result)
}

/** The engine call itself: the dispatcher, the cancellation guard and the two failure conversions. */
private suspend fun dwTraceKotlinRunEngine(
    src: RgbaImage,
    params: TraceParams,
    preview: Boolean,
    events: Channel<DwTraceProgress>,
): TraceResult {
    val token = CancellationToken()
    // Only the FIRST event for an id is forwarded — see the caller's docblock. Written and read on
    // the one thread the engine calls the listener from, so it needs no synchronisation.
    var lastStageId: String? = null
    val listener = if (preview) {
        // No listener at all for a preview, because the vendored worker passes none to `runPreview`
        // (`trace.worker.ts:113-118`) and `DwTraceRuntime.trace` repeats the promise: a preview that
        // reported progress would be this runtime inventing events the other one does not send.
        null
    } else {
        ProgressListener { stageId, label, fraction ->
            if (stageId != lastStageId) {
                lastStageId = stageId
                events.trySend(DwTraceProgress(stageId = stageId, label = label, fraction = fraction))
            }
        }
    }
    val effective = if (preview) dwTraceKotlinPreviewParams(params) else params

    return try {
        withContext(Dispatchers.Default) {
            val guard = launch(Dispatchers.Unconfined) {
                try {
                    awaitCancellation()
                } finally {
                    token.cancel()
                }
            }
            try {
                // Classification off for a preview: `Classify` answers from its own 512 px proxy, so
                // it would return the same profile for the same source and cost the same on every
                // keystroke (`Preview.kt:47-50`), and `pipeline.ts:877` is where the TypeScript's
                // own preview passes the same false.
                Pipeline.run(src, effective, listener, token, classify = !preview)
            } finally {
                // Ends the guard. `withContext` does not return until its children complete, so this
                // is not optional — and cancelling the token after a finished run is inert.
                guard.cancel()
            }
        }
    } catch (cancelled: CancelledException) {
        // The engine unwound because the token was set. Almost always that is this coroutine being
        // cancelled, and `ensureActive` turns it into the CancellationException the port promises —
        // a cancel "must never reach the user as one" (`trace.worker.ts:156-157`). If the job is
        // somehow still active, something set the token that had no business doing so, and that is a
        // fault worth a sentence rather than a silent empty drawing.
        currentCoroutineContext().ensureActive()
        throw DwTraceHostFailure(
            DwTraceFailureKind.ENGINE_ERROR,
            "the trace stopped before it finished, and nothing had asked it to stop",
            cancelled,
        )
    } catch (exhausted: OutOfMemoryError) {
        // CAUGHT DELIBERATELY, WHICH IS NOT THE USUAL RULE. The pre-flight check above should make
        // this unreachable, but its per-pixel figures were measured on another runtime's heap, so
        // "should" is doing work there. The alternative to catching it is the process dying with an
        // unsaved stage on screen, and this frame owns several tens of megabytes it is about to drop
        // — which is the one situation where an OutOfMemoryError is genuinely recoverable.
        throw DwTraceHostFailure(DwTraceFailureKind.OUT_OF_MEMORY, "", exhausted)
    }
}

/**
 * A finished [TraceResult] as the port's own [DwTraceDecoded].
 *
 * Every field is carried, nothing is recomputed, and the two that could be taken from the request
 * instead are deliberately taken from the result: [TraceResult.appliedParams], because auto-detection
 * runs before the first stage and the dock has to show what ran; and the document's own frame, which
 * is the rectified page rather than the photograph when perspective correction fired — the one thing
 * that makes the comparator refuse, and it must refuse rather than lay two frames over each other.
 */
fun dwTraceKotlinDecodedOf(result: TraceResult): DwTraceDecoded {
    val doc = result.document
    val svg = dwTraceKotlinSvgOf(doc)
    return DwTraceDecoded(
        svg = svg.svg,
        geometry = dwTraceKotlinGeometryOf(doc),
        background = doc.background,
        width = result.sourceWidth,
        height = result.sourceHeight,
        workingWidth = result.workingWidth,
        workingHeight = result.workingHeight,
        shapeCount = doc.shapeCount(),
        nodeCount = doc.nodeCount(),
        stages = result.stages.map { DwTraceStageTiming(it.id, it.label, it.millis) },
        totalMillis = result.totalMillis,
        // EVERY sentence the pipeline said, then the writer's own cut if there was one. The second
        // half is `bridge.ts:226-230` on the other route, in the same order and for its reason: a
        // truncated drawing with nothing on screen to say so is the bug this project takes most
        // seriously.
        notes = if (svg.truncationNote == null) result.notes else result.notes + svg.truncationNote,
        appliedParams = dwTraceValuesOfParams(result.appliedParams),
        autoSubjectId = result.autoSubjectId,
        // ALWAYS EMPTY ON THIS ROUTE, AND THAT IS A FEATURE THE KOTLIN ENGINE DOES NOT HAVE RATHER
        // THAN A FIELD THIS FILE FORGOT. See DW_TRACE_KOTLIN_NO_SUGGESTION_NOTE.
        suggestedStyleId = "",
    )
}

/**
 * Why `DwTraceResult.suggestedStyleId` is empty on this runtime, in one place, so nobody re-derives it.
 *
 * ── THE TWO VENDORED CLASSIFIERS PRODUCE DIFFERENT THINGS UNDER ONE FIELD NAME ────────────────
 *
 * `SerializedProfile.suggestion` is documented on the TypeScript side as "a {@link module:styles}
 * preset id. Never empty" (`engine/classify.ts:82-83`), chosen by a five-line ladder over
 * `SourceKind` (`classify.ts:306-311`: line art → `single-stroke`, flat graphic → `stencil`, textured
 * → `minimal`, smooth object → `silhouette`, otherwise `clean-line`). The vendored Kotlin's
 * `Classify.SourceProfile.suggestion` is "**One human-readable sentence** naming the recommended
 * preset and why" (`Classify.kt:111`) — for the same five kinds, a paragraph like *"This is already
 * line art (82% bimodal, 61% confident), so the suggested preset is \"Ink Scan\"…"*.
 *
 * So the Kotlin value cannot be put in this field: it is prose, and a panel that renders it as a
 * style would show a designer a sentence where a preset name belongs.
 *
 * ── AND THE THREE WAYS OF RECOVERING AN ID WERE ALL WORSE ─────────────────────────────────────
 *
 * Reading the preset out of the sentence does not work even in principle: the names it recommends —
 * "Ink Scan", "Flat Graphic", "Coherent Line" — **are not in `Styles.ALL`** (checked against all
 * twenty names on 2026-08-28), so it is naming another product's preset table. Transcribing the
 * TypeScript's ladder into Kotlin would put a second register of style ids in this app, which is the
 * one thing `DwSketchTraceEngine.kt`'s header forbids outright, and it would have this runtime
 * suggest a style the engine that is actually running does not agree with. Inventing a mapping from
 * `SourceKind` is the same thing with fewer sources.
 *
 * ── WHAT IS ACTUALLY LOST, WHICH IS LESS THAN IT SOUNDS ───────────────────────────────────────
 *
 * The panel's suggestion row disappears on this runtime. **The portal has never had it**:
 * `DwTraceResult.suggestedStyleId`'s own docblock records that the web "computes it, ships it and
 * renders it nowhere", verified against `SketchTraceField.tsx` on 2026-08-27. So this is a
 * handset-only nicety going quiet on one of two handset runtimes, not a capability leaving the
 * product — and the empty string is a state the panel already handles, because a preview has always
 * produced one.
 */
const val DW_TRACE_KOTLIN_NO_SUGGESTION_NOTE: String =
    "The Kotlin engine's classifier answers with a sentence rather than a style id, so this runtime " +
        "suggests no style. See DwTraceKotlinRuntime.kt for the three alternatives and why each is " +
        "worse than an empty field."

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 7. THE RUNTIME
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * A refusal with a sentence this file wrote, on its way to [DwTraceOutcome.Refused].
 *
 * [DwTraceHostFailure] would be the obvious carrier and it is the wrong one: its sentence is built
 * from a [DwTraceFailureKind], and the nearest kind — `OUT_OF_MEMORY` — says the phone "ran out of
 * memory part-way through the trace", which is false for a trace that was refused before it started.
 * A sentence that is nearly right is worse than one more exception type.
 */
private class DwTraceKotlinRefusal(message: String) : Exception(message)

/**
 * The tracer that runs the vendored Kotlin engine in this process.
 *
 * Built by [dwTraceKotlinRuntime]. Holds no Activity, no View and no composition — an in-flight trace
 * belongs to the job that called [trace], so a panel that launches it from a composition's scope
 * loses it on rotation and a caller that wants otherwise can hold the job somewhere longer-lived.
 * There is nothing here to dispose, which is why this class has no `dispose()` and why
 * `rememberDwTraceRuntime` is a bare `remember` with no `DisposableEffect` around it. The route this
 * replaced needed one: it held a reference-counted, process-wide binding to the WebView sandbox and a
 * long-lived parameter isolate inside it, and releasing without disposing leaked a live V8 context in
 * another process.
 *
 * **IT DOES NOT EVEN HOLD A `Context`**, which is worth saying because the other runtime does and
 * because the absence is load-bearing rather than tidy: there is no service to bind, no isolate to
 * start and no asset to read out of the APK. The engine is on the classpath. [dwTraceKotlinRuntime]
 * takes a `Context` only to ask the platform how much memory this phone has, once, before returning.
 */
class DwTraceKotlinRuntime internal constructor(
    override val availability: DwTraceAvailability,
) : DwTraceRuntime {

    /** Which writer spelled [DwTraceResult.svg]. The mirror of `DwTraceHello.svgWriter`. */
    val svgWriter: String = DW_TRACE_KOTLIN_SVG_WRITER

    /** The engine's nineteen stages. See [DW_TRACE_KOTLIN_STAGES] for why the number matters. */
    val stages: List<DwTraceStage> = DW_TRACE_KOTLIN_STAGES

    /*
      THE FIVE PARAMETER VERBS ARE ARITHMETIC, AND THEY STILL HOP.

      Each is a handful of field copies and one `sanitized()` — microseconds, as
      `DwTraceKotlinPresets.kt` measures where it declines to make its own halves suspend. They are
      wrapped in `withContext(Dispatchers.Default)` anyway because `DwTraceRuntime`'s header requires
      an implementation to be main-safe and because `presets()` and `defaults()` also serialise a tree
      to JSON, which is not free on a cold class-load path in front of a first frame. The cost is one
      dispatch per slider tick against work the panel already sequences behind a mutex.
    */

    override suspend fun presets(): DwTracePresetTables = withContext(Dispatchers.Default) {
        dwTraceKotlinPresetTables()
    }

    override suspend fun defaults(): DwTraceValues = withContext(Dispatchers.Default) {
        dwTraceValuesOfParams(TraceParams())
    }

    override suspend fun withOverrides(
        base: DwTraceValues,
        patch: Map<String, DwTraceValue>,
    ): DwTraceValues = withContext(Dispatchers.Default) {
        // ONE CALL: the engine's own merge-then-sanitise, with no Kotlin merge in between. See
        // `DwTraceRuntime.withOverrides` and `DwTraceKotlinParams.kt`'s header.
        dwTraceValuesOfParams(dwTraceApplyLeaves(dwTraceParamsOf(base), patch))
    }

    override suspend fun applyStyle(base: DwTraceValues, styleId: String): DwTraceValues =
        withContext(Dispatchers.Default) { dwTraceKotlinApplyStyle(base, styleId) }

    override suspend fun applySubject(base: DwTraceValues, subjectId: String): DwTraceValues =
        withContext(Dispatchers.Default) { dwTraceKotlinApplySubject(base, subjectId) }

    /**
     * One trace, start to finish.
     *
     * The three-way catch is `DwSketchTrace.kt`'s, deliberately identical: a cancellation is rethrown
     * and never becomes an error line, a classified failure becomes the sentence it carries, and
     * anything else becomes a sentence naming its class so a bug report has something to say. A
     * runtime that let an exception escape here would take a screen with an unsaved stage on it down
     * with it.
     */
    override suspend fun trace(
        request: DwTraceRequest,
        onProgress: (DwTraceProgress) -> Unit,
    ): DwTraceOutcome = try {
        DwTraceOutcome.Done(runTrace(request, onProgress))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: DwTraceKotlinRefusal) {
        DwTraceOutcome.Refused(refused.message.orEmpty())
    } catch (failure: DwTraceHostFailure) {
        DwTraceOutcome.Refused(failure.message.orEmpty())
    } catch (unexpected: Throwable) {
        DwTraceOutcome.Refused(
            dwTraceSentence(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                unexpected::class.java.simpleName,
            ),
        )
    }

    private suspend fun runTrace(
        request: DwTraceRequest,
        onProgress: (DwTraceProgress) -> Unit,
    ): DwTraceResult {
        val preview = !request.kind.isFullResolution

        // ONE DECODER FOR BOTH RUNTIMES. `dwTraceDecodeForTrace` is the function `DwSketchTrace.kt`
        // has always used, moved to file scope so this one can call it rather than copy it — its own
        // docblock states the rule a copy would break: "a second decoder here would be a second
        // opinion about EXIF orientation".
        val source = dwTraceDecodeForTrace(request.photographPath)

        // The pixels, the crop and the conversion together, off the caller's thread. Reading 1.9M
        // pixels back out of a bitmap and packing them twice is tens of milliseconds — not an ANR,
        // but not something to spend a frame on either.
        val prepared = withContext(Dispatchers.Default) {
            val rgba = DwSketchTracePlates.readRgba(source)
                ?: throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_UNREADABLE, "reading the pixels back")
            val decodedWidth = source.width
            val decodedHeight = source.height
            // Dropped as early as the other route drops it, and for its reason: the bitmap is up to
            // 67 MB and every pixel of it now exists in `rgba`.
            source.recycle()

            // The crop is a trace input and not an edit — the photograph is untouched and the frame
            // is taken afresh from the whole decode on every run. `dwTraceCropIn` rather than a bare
            // clamp because the frame the designer aimed in and the frame this decode produced are
            // allowed to differ.
            val frame = request.frame
            val cropped = if (frame == null) {
                null
            } else {
                dwTraceCropRgba(
                    rgba,
                    decodedWidth,
                    decodedHeight,
                    dwTraceCropIn(frame, decodedWidth, decodedHeight),
                ) ?: throw DwTraceHostFailure(
                    DwTraceFailureKind.OUT_OF_MEMORY,
                    "taking the chosen frame out of the photograph",
                )
            }
            DwTraceKotlinInput(
                rgba = cropped?.rgba ?: rgba,
                width = cropped?.rect?.width ?: decodedWidth,
                height = cropped?.rect?.height ?: decodedHeight,
                // Built from the box that was actually applied after clamping rather than from what
                // the panel asked for: a sentence in an archived file has to describe what happened.
                frameNote = cropped?.let { dwTraceCropNote(it.rect, decodedWidth, decodedHeight) }.orEmpty(),
            )
        }

        val params = dwTraceParamsOf(request.params)
        val effective = if (preview) dwTraceKotlinPreviewParams(params) else params

        // BEFORE THE FIRST STAGE. See the file header: this engine's working set is in this app's
        // heap, where the isolate route's was in another process.
        dwTraceKotlinMemoryRefusal(
            sourcePixels = prepared.width.toLong() * prepared.height.toLong(),
            workingPixels = dwTraceKotlinWorkingPixels(prepared.width, prepared.height, effective),
            engine = effective.edge.engine,
            heapBytes = dwTraceKotlinHeapBytes(),
        )?.let { throw DwTraceKotlinRefusal(it) }

        val image = withContext(Dispatchers.Default) {
            dwTraceKotlinImageOf(prepared.rgba, prepared.width, prepared.height)
        }

        val decoded = dwTraceKotlinTrace(image, params, preview) { progress ->
            // ON THE MAIN THREAD, because `DwTraceRuntime.trace` says so and a panel's state is
            // written from a composition. The pump this runs on already inherits the caller's
            // context, so when the panel calls from its own scope this is a no-op fast path.
            withContext(Dispatchers.Main) { onProgress(progress) }
        }

        return withContext(Dispatchers.Default) {
            // THE PLATE BUILDER, WHICH LIVES IN `DwSketchTrace.kt` FOR THE SAME REASON AS THE DECODER:
            // it touches `android.graphics.Bitmap`, and this file is reachable by a JVM unit test.
            dwTracePlateResult(
                decoded = decoded,
                rgba = prepared.rgba,
                sourceWidth = prepared.width,
                sourceHeight = prepared.height,
                frameNote = prepared.frameNote,
                request = request,
            )
        }
    }
}

/** The photograph after the decode and the crop, with the sentence the crop earned. */
private class DwTraceKotlinInput(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val frameNote: String,
)

/**
 * The Kotlin-engine tracer for this phone.
 *
 * **IT CANNOT REFUSE, WHICH IS THE POINT OF IT.** The function this replaced asked two probes before
 * it could answer — is the bundle in this build, will this WebView start `JavaScriptSandbox` — and
 * either could hand back a runtime that did nothing but explain itself. Both were questions about the
 * isolate, and the isolate is gone: the engine is four Gradle modules compiled into this APK, so if
 * this app runs, it traces. That is why [DwTraceAvailability] no longer carries a `canTrace` or a
 * `refusal`; its header records the removal. The one thing that can still stop a trace is memory, and
 * that is measured per trace against the frame actually being traced.
 *
 * **THE TWO CEILINGS ARE STILL THE ISOLATE ROUTE'S NUMBERS AND STILL UNMEASURED**, which is what
 * [DwTraceAvailability.measuredOn] being null says out loud. They came from `dwTraceCeilings`, which
 * reasoned them from a laptop's V8 and published single-thread figures for the fleet's Galaxy M32,
 * and **nobody has run either engine on a handset**. The JVM's arithmetic is very likely faster than
 * V8's on these array loops, which would make them conservative rather than wrong — but "very likely"
 * is not a measurement, and a UI that presented a guess as a limit is what
 * `docs/DEVICE-TIER-MEASUREMENT.md` exists to prevent. Deleting the JavaScript route did not measure
 * anything; it removed the second consumer of one table, and the table is as unverified as it was.
 */
fun dwTraceKotlinRuntime(context: Context): DwTraceRuntime {
    val app = context.applicationContext
    // One call, so a pair of figures describes one moment — `dwProbeDevice`'s own rule. Only
    // `totalRamBytes` is read and it does not change.
    val totalRam = runCatching { dwProbeDevice(app).totalRamBytes }.getOrNull()
    val (maxEdge, fdogMaxEdge) = dwTraceCeilings(totalRam)
    return DwTraceKotlinRuntime(
        availability = DwTraceAvailability(
            maxWorkingLongEdge = maxEdge,
            fdogMaxWorkingLongEdge = fdogMaxEdge,
            measuredOn = null,
        ),
    )
}
