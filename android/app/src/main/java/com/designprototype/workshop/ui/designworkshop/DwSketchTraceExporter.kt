package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Immutable

/**
 * **THE SEAM BETWEEN "SAVE THIS DRAWING AS A PDF" AND WHATEVER RUNS THE VENDORED WRITERS.**
 *
 * ── WHY THERE IS A SECOND SEAM AT ALL ─────────────────────────────────────────────────────────
 *
 * `DwSketchTraceEngine.kt` already draws the boundary for TRACING, and a finished trace hands back
 * one written artefact: `DwTraceResult.svg`. That is the right thing for it to hand back — it is what
 * the record is offered and it is the string the cross-runtime parity harness compares exactly — but
 * it is not enough to write a PDF, an EPS, a DXF or a PNG. Every one of those writers takes a
 * `VecDocument`, and a `VecDocument` never crosses a boundary: `worker/protocol.ts:70-105` explains
 * why, and `DwTraceGeometry`'s own KDoc in `DwSketchTraceWire.kt` restates it — a 50,000-path trace is
 * roughly a million coordinates, and as objects that is a million allocations.
 *
 * So this seam asks for BYTES, and hands back the flat arrays the engine already produced.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * ALL FIVE FORMATS WRITE ON THIS HANDSET, BY THREE DIFFERENT ROUTES
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [dwTraceExportPlan] chooses between them, in the order of what each needs:
 *
 *  * **SVG** needs nothing. The engine's own writer ran on the way out and its string is in hand; see
 *    this file's proof below that re-printing it here would be a second SVG writer.
 *  * **PNG** needs the geometry and this device's own encoder. `Bitmap.compress` here,
 *    `canvas.toBlob` in the browser, on `exportFormats.ts`'s rule that the platform layer owns the
 *    pixel formats it already has an encoder for.
 *  * **PDF, EPS and DXF** need the vendored writers, and [DwTraceKotlinExporter] is the implementation
 *    that calls them — `:core-export`'s `PdfWriter`, `EpsWriter` and `DxfWriter`, compiled into this
 *    APK.
 *
 * ── THIS FILE USED TO SAY TWO OF THE FIVE, AND THE REASON EXPIRED ─────────────────────────────
 *
 * The tracer was a minified JavaScript bundle in an `androidx.javascriptengine` isolate, and its host
 * surface exposed six calls — `about`, `presets`, `defaults`, `sanitize`, `trace`, `cancel` — with a
 * measured argument for stopping there: putting the engine's `Engine` namespace on the isolate's
 * global cost 56,839 bytes of bundle, measured 2026-08-27 by building the same entry both ways. That
 * was the right call for a trace panel, and it was the whole of why a phone could not write a PDF.
 *
 * The bundle is deleted. The engine is four Gradle modules and `:core-export` came with it, so the
 * writers are simply present and there is no bundle budget to spend. [DwTraceExporterUnavailable]
 * survives as the stub a host with no exporter mounts; nothing in this app mounts it any more.
 *
 * ── AND WHY THE GEOMETRY GOES BACK IN RATHER THAN A DOCUMENT BEING KEPT ───────────────────────
 *
 * The cheap-looking alternative is for the runtime to RETAIN the last traced `VecDocument` and write
 * from it. It is the wrong shape, and the reason survived the port intact — it only moved from one
 * heap to another. A document at the shape ceiling is tens of megabytes; a designer who traces a
 * sheet, looks at it, decides, and then presses Save may be minutes past the trace; and those
 * megabytes would be held all that time against the composition, the draft store and every bitmap the
 * workshop has open — exactly the heap `DwTraceKotlinRuntime` refuses oversized traces to protect.
 * Passing the geometry in is stateless, costs one walk on a button press, and cannot fail that way.
 *
 * ── WHAT THIS SEAM DELIBERATELY DOES NOT DO ───────────────────────────────────────────────────
 *
 * **It never touches the engine's own SVG string.** [dwTraceExportPlan] routes every SVG save straight
 * to `DwTraceResult.svg`, and that is a PROOF rather than an approximation:
 *
 *  * `exportFormats.exportSvg` builds its options as
 *    `svgOptions({precision: o.precision, includeMetadata: o.includeMetadata, groupByLayer:
 *    !o.flattenLayers})`, and the defaults of `ExportOptions` are precision 2, `includeMetadata` true
 *    and `flattenLayers` false — which reconstitutes `DEFAULT_SVG_OPTIONS` exactly.
 *  * `prepareVectorDoc` at scale 1 either returns the document untouched (background null) or rebuilds
 *    it with the background it already had — see [dwTraceExportBackground], which is why the export
 *    passes the document's own value. `svgWriter.write` reads only width, height, layers and
 *    background, so both branches print the same string.
 *  * `frontend/e2e/support/traceParityRun.ts:103` produces the parity string as
 *    `SvgWriter.write(result.document)` — the same defaults, on the same document.
 *
 * So the SVG in hand IS `exportSvg`'s answer, for either background, and re-printing, re-indenting or
 * "tidying" it here would be a second SVG writer in a second language — the divergence the whole
 * vendoring discipline exists to prevent — as well as putting the saved copy out of step with the file
 * the record holds.
 *
 * **The proof depends on the export not choosing a background of its own.** The moment somebody adds
 * an export-time white that the traced document does not have, this shortcut stops being valid for
 * that case and the SVG must route through the exporter like the other four. That is the reason
 * [dwTraceExportBackground] exists as a named pass-through instead of an argument anybody can fill in.
 *
 * ── MAIN-SAFETY AND CANCELLATION, INHERITED RATHER THAN RESTATED ──────────────────────────────
 *
 * [DwTraceExporter.export] is a `suspend fun` for the reasons `DwTraceRuntime` gives at length: an
 * implementation puts its own `withContext` inside, because every caller here is a composable's scope
 * and that is the main thread; and cancelling it is cancelling its job, because the vendored
 * `CancellationToken` is deliberately not an `AbortSignal` so that "the engine must run identically
 * under vitest, in a worker and on a JVM-shaped API" (`pipeline.ts:120-122`).
 *
 * A cancelled export throws `CancellationException` and **must not be reported as a failure**. Writing
 * a file is not a pipeline run, so there are no stages and no progress: the longest of these is one
 * marshalling pass plus, for the PNG, one rasterisation. The card shows a working line, not a bar.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * What an export asks for
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One export, fully specified.
 *
 * ── THE GEOMETRY IS `DwSketchTraceWire.kt`'s, NOT A SECOND SHAPE ──────────────────────────────
 *
 * [DwTraceGeometry] is the flat-array mirror that file already declares and validates. It is used
 * here rather than wrapped, copied or re-abstracted, under its own rule: "no other file in this app
 * holds geometry in any other shape". The moment there were two shapes there would be a conversion,
 * and a conversion is a place for one client to disagree with the other about what a cubic segment is.
 *
 * [width] and [height] are the DOCUMENT's own frame — the source photograph's size, or the rectified
 * page — carried beside the geometry because the geometry does not know them and both
 * `ExportOptions.outputSize` and [dwTracePngScale] need them. They come straight off
 * `DwTraceDecoded`/`DwTraceResult`; nothing here derives them from the coordinates, which would be a
 * bounding box rather than a frame and would move the artwork relative to the photograph
 * (`svgWriter.write`: "a user who traces a photo and then imports the SVG over it expects the two to
 * line up").
 *
 * ── THREE OPTIONS OUT OF `ExportOptions`' TEN, AND THE SEVEN ARE NOT AN OVERSIGHT ─────────────
 *
 * `ExportOptions` has ten fields (`format`, `width`, `height`, `scale`, `background`, `quality`,
 * `dpi`, `precision`, `includeMetadata`, `flattenLayers`). This exposes three, because the other
 * seven have one correct answer here and a control over any of them would be a way to get it wrong:
 *
 *  * `width`/`height` stay 0, which means "the document's own size" — see the frame note above.
 *  * `scale` is derived, never chosen: 1 for every vector format, [dwTracePngScale] for the PNG.
 *  * `quality` is JPEG-only and JPEG is not offered.
 *  * `precision` stays at the engine's default of 2. `svgPathData.ts` measures the elisions at that
 *    precision as "roughly one character in eight of the largest thing this app writes", and a
 *    coordinate printed to two decimals on a 400px document is 4.75 µm across an A4 column — a fifth
 *    of a 1200-dpi imagesetter dot. There is nothing to buy by moving it and a parity story to lose.
 *  * `includeMetadata` stays true. Trap 2 in `DwSketchTraceExport.kt`'s header is the argument.
 *  * `flattenLayers` stays false, so `groupByLayer` stays true and the SVG keeps its `<g>` per layer —
 *    which `svgWriter.write` warns is the only form that preserves layer visibility, since "flattened
 *    output drops layer visibility, so a hidden layer must not silently become visible".
 *  * `dpi` is [DW_TRACE_PNG_DPI], whose own doc-comment explains why it is 72 and not 300.
 *
 * A runtime therefore fills those seven from this file's constants and does not take instructions
 * about them. An option surface is a place for two clients to diverge, and this one is as small as
 * the five formats allow.
 */
@Immutable
class DwTraceExportRequest(
    /** The traced geometry, exactly as `DwSketchTraceWire.kt` decoded it. Never re-shaped. */
    val geometry: DwTraceGeometry,
    /** The document's own frame width. See the class note. */
    val width: Int,
    /** The document's own frame height. */
    val height: Int,
    val format: DwTraceExportFormat,
    /**
     * The DOCUMENT'S OWN background, passed through rather than chosen — `dwTraceExportBackground`.
     *
     * Never null-as-a-shortcut. `ExportOptions.background = null` means "leave the document alone" to
     * the vector writers and "transparent" to the rasteriser, so passing the document's real value is
     * what keeps a PDF and a PNG of one drawing from disagreeing about their ground.
     */
    val background: Int?,
    /**
     * The sentence to write into the file where the writer has somewhere to put one.
     *
     * Reaches `/Title` in a PDF and `%%Title:` in an EPS. `writeDxf` takes no metadata argument and
     * `pngEncoder` writes no text chunk, so for those two it is carried and dropped — which
     * [dwTraceExportLosses] states on screen. Built by [dwTraceProvenanceNote]; never assembled at a
     * call site, so that one sentence describes one drawing on both clients.
     */
    val provenanceNote: String,
)

/** Bytes, or a refusal in one sentence. Cancellation is NEITHER — see [DwTraceExporter.export]. */
sealed class DwTraceExportOutcome {
    /**
     * The encoded file.
     *
     * A `ByteArray` and not a `File`: whoever writes it to the flash is [dwSaveTraceExport], which is
     * the only place in this feature that knows what a Downloads folder is. Splitting it that way is
     * what lets `DwSketchTraceExportTest` exercise the naming, the losses and the plan on a JVM with
     * no device attached.
     *
     * SIZE IS BOUNDED BY THE DRAWING AND IS NOT ENORMOUS. The web measured a 20,975-shape CANNY trace
     * as a 1.5 MB result string; a 2048px PNG of line art compresses to a few hundred kilobytes
     * because it is very nearly bilevel. Holding one in memory to write it is the same shape
     * `ReportExport` uses for a report an order of magnitude larger.
     */
    class Done(val bytes: ByteArray) : DwTraceExportOutcome()

    /**
     * The file could not be written, in one sentence a designer can act on.
     *
     * A REFUSAL IS NOT AN EXCEPTION, for `DwTraceOutcome.Refused`'s reason: the caller has to print
     * it, and a sentence that has to be printed is a value. Exceptions are for the cases nobody wrote
     * a sentence for.
     */
    class Refused(val reason: String) : DwTraceExportOutcome()
}

/* ────────────────────────────────────────────────────────────────────────────
 * The exporter
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Whatever runs the vendored engine's writers on this phone.
 *
 * SEPARATE FROM `DwTraceRuntime` AS AN INTERFACE, THOUGH ON THIS BUILD ONE OBJECT COULD SERVE BOTH.
 * **This paragraph used to read "the handset can trace today and cannot write a PDF today" and gave
 * the shipped bundle's surface as the reason. Both halves are now false** — `:core-export` is in the
 * APK, [DwTraceKotlinExporter] calls its three writers, and this file's header lists all five formats
 * as writing here. The split survives that on a different argument: tracing and writing can fail for
 * unrelated reasons and are asked about at different moments, so a host composing
 * [DwSketchTraceExportCard] with no engine behind it can still be handed an exporter, and a future
 * build that loses one capability can say so without claiming the other went with it.
 *
 * @throws kotlinx.coroutines.CancellationException when the job is cancelled. Not a failure.
 */
interface DwTraceExporter {

    /**
     * Why this phone cannot write the PDF, the EPS and the DXF, or null when it can.
     *
     * **NULL ON EVERY BUILD OF THIS APP** — [DwTraceKotlinExporter] answers null and it is the only
     * implementation `rememberDwTraceExporter` mounts. The field stays because it is what
     * [dwTraceExportPlan] routes on, and what lets a host with nothing behind it say so: a sentence
     * here is the difference between a greyed row that explains itself and a button that fails on the
     * press.
     *
     * A sentence and not a boolean, on the rule every refusal in this feature is held to — a dead
     * button teaches a designer the feature is broken, and a sentence teaches them what still works.
     *
     * IT NEVER GOVERNED THE PNG. That format leaves through [DwTraceExportPlan.FromPlatformRaster]
     * and never reaches this interface, so even an exporter that refused everything left two of the
     * five formats working.
     */
    val refusal: String?

    /** Write one file. Main-safe: the implementation puts its own `withContext` inside. */
    suspend fun export(request: DwTraceExportRequest): DwTraceExportOutcome
}

/**
 * The exporter for a host with no writers behind it, which SAYS SO and does nothing else.
 *
 * **NOTHING IN THIS APP MOUNTS IT ANY MORE.** It was the shipping answer while the engine was a
 * JavaScript bundle whose host surface carried no writer — see this file's header for the measured
 * reason that surface stopped at six calls — and [DwTraceKotlinExporter] replaced it when the engine
 * became `:core-export`. It is kept, rather than deleted with the bundle, because
 * [DwSketchTraceExportCard] deliberately takes primitives instead of a runtime so that a host with
 * nothing behind it can still compose the card, and such a host needs an exporter to hand it. That is
 * the same reason [DW_TRACE_NO_GEOMETRY_SENTENCE] exists for a caller with no geometry.
 *
 * [export] answers a [DwTraceExportOutcome.Refused] rather than throwing. A press that reaches this is
 * a designer who chose a format this host cannot write, which is a sentence and not a crash.
 */
class DwTraceExporterUnavailable(reason: String = DW_TRACE_NO_EXPORTER_SENTENCE) : DwTraceExporter {
    override val refusal: String = reason

    override suspend fun export(request: DwTraceExportRequest): DwTraceExportOutcome =
        DwTraceExportOutcome.Refused(refusal)
}

/**
 * The sentence a host with no writers behind it shows.
 *
 * **UNREACHABLE IN THIS APP AS SHIPPED**, because `rememberDwTraceExporter` mounts
 * [DwTraceKotlinExporter] and its `refusal` is null. It is the default of
 * [DwTraceExporterUnavailable], which is what a host composing the card with nothing behind it gets.
 * Re-check which exporter this build mounts with:
 *
 *     grep -rn "fun rememberDwTraceExporter" android/app/src/main/java --include=*.kt
 *
 * IT NAMES WHAT WORKS RATHER THAN ONLY WHAT DOES NOT, which is the shape every refusal in this
 * feature is held to and which the web reached independently: `traceExport.WRITER_UNAVAILABLE` ends
 * "The SVG download needs nothing extra and works either way."
 */
const val DW_TRACE_NO_EXPORTER_SENTENCE: String =
    "This phone can save the drawing as an SVG, which is the full vector line work, and as a " +
        "picture. PDF, EPS and DXF are not available here yet — the portal can write all three from " +
        "this same photograph on a laptop when you next have a connection."

/* ────────────────────────────────────────────────────────────────────────────
 * Which route a save takes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How one save will be satisfied: from the string already in hand, or by asking the exporter.
 *
 * A VALUE RATHER THAN A BRANCH INSIDE THE BUTTON, so that the card can grey a row it cannot satisfy
 * BEFORE a designer presses it, and so that `DwSketchTraceExportTest` can pin the routing without an
 * exporter, a runtime or a device. The web has the same split and learned it the hard way — its
 * `WRITER_UNAVAILABLE` sentence exists because a failed dynamic import surfaced through a catch-all
 * as "Failed to fetch dynamically imported module: …/chunk-a91f2c.js".
 */
sealed class DwTraceExportPlan {
    /**
     * The bytes are the engine's own SVG string, UTF-8, unaltered.
     *
     * See this file's header for the proof that this is the same call `exportSvg` would make, and for
     * why it applies only to a transparent background.
     */
    data object FromTraceSvg : DwTraceExportPlan()

    /**
     * The bytes are a picture this device paints and this device's own PNG encoder writes.
     *
     * **THE THIRD ROUTE, AND THE REASON THIS BUILD OFFERS TWO FORMATS RATHER THAN ONE.** It needs no
     * bundle, no isolate and no marshalling pass: `DwSketchTracePlates.renderTrace` already walks
     * `DwTraceGeometry` into `android.graphics.Path` in production for the comparator, and
     * `Bitmap.compress` is the platform's PNG encoder. [dwTraceRenderPngBytes] is the whole of it.
     *
     * IT IS THE WEB'S ARRANGEMENT AND NOT A HANDSET SHORTCUT. `EXPORT_FORMATS`' own PNG row records
     * that the portal writes its PNG with `canvas.toBlob` rather than with `pngEncoder`, on
     * `exportFormats.ts`'s rule that "the platform layer owns the pixel formats the browser already
     * has an encoder for". Two platforms, two platform encoders, and one cap — [dwTracePngSize] — so
     * the two files are the same number of pixels across.
     *
     * WHAT IT NEEDS THAT THE SVG DOES NOT: the geometry. A trace whose shapes did not survive the
     * boundary can still save its SVG and cannot paint anything, which is why
     * [dwTraceExportPlan] takes `hasGeometry` and why that is a DIFFERENT refusal from a bundle with
     * no writers — see [DW_TRACE_NO_GEOMETRY_SENTENCE].
     */
    data object FromPlatformRaster : DwTraceExportPlan()

    /** The runtime has to write it. */
    data object FromExporter : DwTraceExportPlan()

    /**
     * Nothing can write it on this handset, and this is the sentence to show.
     *
     * Reached only for a format the SVG shortcut does not cover, on a build whose exporter refuses.
     * Carries the exporter's OWN refusal rather than a generic one, because the causes a designer
     * might meet are different sentences with different remedies — a bundle without the writers, and
     * a WebView too old to host the engine at all.
     */
    data class Refused(val reason: String) : DwTraceExportPlan()
}

/**
 * Decide the route for one format.
 *
 * PURE, and it takes the exporter's refusal as a STRING rather than the exporter itself, so the whole
 * routing rule is testable with no interface to stub. Pass null when an exporter is present and
 * willing.
 *
 * ── THREE ROUTES, IN THE ORDER OF WHAT THEY NEED ──────────────────────────────────────────────
 *
 * The SVG needs nothing — the string is in hand. The PNG needs the geometry and this device's own
 * encoder. The other three need the vendored writers, which this build's bundle does not carry.
 * Asking the questions in that order is what lets a phone with no writers still offer two formats.
 *
 * ── "NO GEOMETRY" AND "NO WRITERS" USED TO BE ONE SENTENCE AND MUST NOW BE TWO ────────────────
 *
 * The card used to fold them together, deliberately and with a reason: they were "different facts
 * inside the app and the SAME fact to the designer — the other four formats cannot be written here,
 * and the SVG can". That reason expired with the raster route. A build with no writers but with
 * geometry now saves the SVG **and** the picture; a trace that came back with no geometry saves the
 * SVG alone. Those are different sets of working controls, so they are different sentences with
 * different remedies, which is the same test `DwSketchChooserSentenceTest` applies from the other
 * side: one fact spelled two ways needs one sentence, and two facts need two.
 */
fun dwTraceExportPlan(
    format: DwTraceExportFormat,
    exporterRefusal: String?,
    hasGeometry: Boolean = true,
): DwTraceExportPlan {
    if (format.id == "svg") return DwTraceExportPlan.FromTraceSvg
    if (!hasGeometry) return DwTraceExportPlan.Refused(DW_TRACE_NO_GEOMETRY_SENTENCE)
    if (format.id == "png") return DwTraceExportPlan.FromPlatformRaster
    if (exporterRefusal != null) return DwTraceExportPlan.Refused(exporterRefusal)
    return DwTraceExportPlan.FromExporter
}

/**
 * The sentence for a trace whose shapes are not in hand.
 *
 * ── WHEN THIS IS REACHED, WHICH IS ALMOST NEVER, AND WHY IT IS STILL WRITTEN ──────────────────
 *
 * `DwTraceResult.geometry` is filled from `DwTraceDecoded.geometry` at the one place a result is
 * built, and that field is not nullable — so on this build the answer is always yes. It is nullable
 * on the RESULT, and this sentence exists, because [DwSketchTraceExportCard] deliberately takes
 * primitives rather than a result object precisely so it can be composed by a host that has no
 * geometry to give (a preview, a screen that only wants the SVG door). A host in that position must
 * get a sentence naming what still works, not a button that fails on the press.
 *
 * NAMES WHAT WORKS RATHER THAN ONLY WHAT DOES NOT, which is the shape `DwTraceAvailability.refusal`
 * asks for and which every refusal in this feature is held to.
 */
const val DW_TRACE_NO_GEOMETRY_SENTENCE: String =
    "The drawing's shapes did not come back with this trace, so the picture and the three take-away " +
        "formats cannot be made from it here. The SVG still saves — the engine wrote it on the way " +
        "out and it is the full vector line work. Trace the sheet again if you need the rest."
