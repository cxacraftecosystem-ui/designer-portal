package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.offlinetracer.export.ExportFormat
import com.offlinetracer.export.ExportOptions
import com.offlinetracer.export.Exporter
import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * **THE PDF, THE EPS AND THE DXF, WRITTEN ON THE HANDSET BY THE VENDORED WRITERS.**
 *
 * ── WHAT THIS CLOSES ──────────────────────────────────────────────────────────────────────────
 *
 * `DwSketchTraceExporter.kt` declares [DwTraceExporter] and, until this file, the only implementation
 * was [DwTraceExporterUnavailable] — a refusal with a measured reason. The reason was about the
 * JavaScript bundle: its host surface exposed six calls and none of them wrote a file, because putting
 * the engine's export namespace on the isolate's global cost 56,839 measured bytes of bundle. That
 * whole route is deleted. `:core-export` is compiled into this APK and carries `PdfWriter`,
 * `EpsWriter` and `DxfWriter` outright, so the constraint is gone and so is the refusal.
 *
 * The panel above did not change for this. `dwTraceExportPlan` already routed the SVG to the string in
 * hand and the PNG to `Bitmap.compress`, and asked an exporter only for the other three; what changed
 * is that the exporter now answers.
 *
 * ── THE ONE THING THIS HAS TO BUILD: A DOCUMENT OUT OF FLAT ARRAYS ────────────────────────────
 *
 * Every writer takes a `VecDocument`, and what the surface holds is [DwTraceGeometry] — the flat
 * mirror `DwSketchTraceWire.kt` declares and forbids re-modelling. [dwTraceKotlinDocumentOf] is the
 * exact inverse of `dwTraceKotlinGeometryOf`, and it is a **port of the web's
 * `components/sketches/upload/geometryToDocument.ts`**, decision for decision, because that file
 * already answered the same questions for the portal and two answers would be two drawings:
 *
 *  * **One layer, named "Line art"** ([DW_TRACE_KOTLIN_LAYER_NAME]). The flat arrays concatenate
 *    shapes across layers in layer order and keep no boundary between them, so layer identity is not
 *    recoverable here and inventing one would be a fiction. It costs nothing: `Stages.kt:1167`
 *    assembles exactly one layer, so there has never been a second one to lose. The name is
 *    load-bearing in exactly one format — `DxfWriter` emits one DXF layer per `VecLayer` and a CAD
 *    operator assigns a tool per layer — and the alternative is that writer's own `LAYER0`, which
 *    says nothing to the person opening it in a machine controller.
 *  * **The same shape ceiling, and the same sentence when it bites.** [DW_TRACE_KOTLIN_MAX_SHAPES] is
 *    already the portal's `MAX_SHAPES_PER_FILE` to the digit, and `dwTraceKotlinTruncationNote` is
 *    already its sentence. A designer who saves one drawing as an SVG and again as a PDF must get one
 *    drawing, so both stop at the same count and report the cut the same way.
 *  * **A style index outside the table draws as a plain black hairline** rather than being dropped.
 *    That is `styleFor`'s decision in that file and `buildSvg`'s before it: a shape whose paint did
 *    not survive is still a line the designer drew.
 *
 * ── AND THE TWO THINGS THE VENDORED WRITERS WILL NOT DO, MEASURED IN THEIR SOURCE ─────────────
 *
 * **`includeMetadata = false`, for the reason `dwTraceKotlinSvgOf` sets it: branding.**
 * `PdfWriter.kt:135-136` writes `/Producer (Offline Tracer) /Creator (Offline Tracer) /Title (Offline
 * Tracer export)` into an `/Info` object, and `EpsWriter.kt:144` writes `%%Title: Offline Tracer
 * export`. Another product's name has no business in a ministry submission. False switches that off
 * for the PDF entirely (`PdfWriter.kt:67` omits the whole `/Info` object) and for the EPS `%%Title`.
 *
 * **IT DOES NOT SWITCH OFF `%%Creator`, AND THAT IS STATED RATHER THAN HIDDEN.**
 * `EpsWriter.kt:143` appends `%%Creator: Offline Tracer` UNCONDITIONALLY, outside the
 * `includeMetadata` guard. So an EPS saved from this handset carries that string in its header
 * comments and there is no option that removes it. It is a comment line in a PostScript preamble, not
 * anything a print shop renders, and the remedy — editing a vendored file — would break the SHA-256 in
 * `android/UPSTREAM-MANIFEST-KOTLIN.txt` and the parity discipline it exists to enforce. Recorded here
 * so nobody discovers it in a file that has already been sent. The PDF and the DXF are clean:
 * `DxfWriter` writes no product name at all.
 *
 * **[DwTraceExportRequest.provenanceNote] CANNOT REACH THESE FILES.** That field's own KDoc says it
 * "Reaches `/Title` in a PDF and `%%Title:` in an EPS", which was true of a writer taking a metadata
 * argument and is not true of these: `ExportOptions` has ten fields and none of them is a title, so
 * the only strings those two slots can hold are the hard-coded ones above. The note is therefore
 * carried and dropped for all three formats rather than one, which is a widening of a loss the surface
 * already states on screen — `dwTraceExportLosses` is where a designer is told, and it is the function
 * to widen if this is to be said per format. Writing the note in by hand would mean this file
 * assembling PDF or PostScript syntax beside a vendored writer, which is the second speller of one
 * format that the whole vendoring discipline exists to prevent.
 *
 * ── MAIN-SAFETY AND CANCELLATION ──────────────────────────────────────────────────────────────
 *
 * [export] does its work on `Dispatchers.Default` and checks for cancellation twice — once before
 * building the document and once before writing — because both halves are single synchronous calls
 * that cannot be interrupted from inside. A cancelled export throws `CancellationException` and is
 * never reported as a failure, which is [DwTraceExporter]'s rule and `DwTraceRuntime`'s before it.
 */

/**
 * The name the single layer carries, and therefore the DXF layer a CAD operator sees.
 *
 * `geometryToDocument.ts`'s `TRACE_LAYER_NAME`, verbatim. `DxfWriter.sanitizeLayerName` upper-cases it
 * and replaces the space, so it arrives in the file as `LINE_ART`.
 */
const val DW_TRACE_KOTLIN_LAYER_NAME: String = "Line art"

/** The layer's id. Never shown; `DxfWriter` falls back to it only if the name is empty. */
private const val DW_TRACE_KOTLIN_LAYER_ID: String = "trace"

/**
 * What [dwTraceKotlinDocumentOf] produced: the document, how much of the drawing reached it, and the
 * cut. The mirror of [DwTraceKotlinSvg], and of `geometryToDocument.ts`'s `DocumentResult`.
 */
class DwTraceKotlinDocument(
    val document: VecDocument,
    val shapesWritten: Int,
    /** Non-null exactly when [DW_TRACE_KOTLIN_MAX_SHAPES] truncated the drawing. Ready to show. */
    val truncationNote: String?,
)

/**
 * The flat arrays, back as the document the vendored writers take.
 *
 * **THE EXACT INVERSE OF `dwTraceKotlinGeometryOf`**, walking the layout that function wrote and
 * `DwTraceGeometry`'s KDoc specifies: a shape's coordinate run begins with its start point, then two
 * floats per line, four per quad and six per cubic, with the two `starts` arrays one longer than the
 * shape count so an extent is a subtraction.
 *
 * [DwTraceGeometry.validate] is what makes the walk safe to write without a bounds check per read —
 * it proves every extent increases, every verb is one of the three, and every shape's coordinate count
 * is exactly what its verbs need. It is called here rather than assumed, because this function is
 * reachable from an export button and the arrays it is handed came off a `DwTraceResult` that may have
 * been sitting in a composition for minutes.
 *
 * @param width the DOCUMENT's own frame width, not a bounding box of the coordinates. See
 *   [DwTraceExportRequest]: a bounding box would move the artwork relative to the photograph.
 * @param background the document's own background, passed through — `dwTraceExportBackground`.
 * @throws DwTraceHostFailure when the geometry is self-inconsistent.
 */
fun dwTraceKotlinDocumentOf(
    geometry: DwTraceGeometry,
    width: Int,
    height: Int,
    background: Int?,
): DwTraceKotlinDocument {
    geometry.validate()

    val shapeCount = geometry.shapeCount
    val written = if (shapeCount > DW_TRACE_KOTLIN_MAX_SHAPES) DW_TRACE_KOTLIN_MAX_SHAPES else shapeCount
    val shapes = ArrayList<VecShape>(written)

    for (i in 0 until written) {
        val coordStart = geometry.coordStarts[i]
        val verbFrom = geometry.verbStarts[i]
        val verbTo = geometry.verbStarts[i + 1]

        var c = coordStart
        val start = VecPoint(geometry.coords[c], geometry.coords[c + 1])
        c += 2

        val segments = ArrayList<VecSeg>(verbTo - verbFrom)
        for (v in verbFrom until verbTo) {
            when (geometry.verbs[v]) {
                DW_TRACE_VERB_LINE -> {
                    segments.add(VecSeg.Line(VecPoint(geometry.coords[c], geometry.coords[c + 1])))
                    c += 2
                }
                DW_TRACE_VERB_QUAD -> {
                    segments.add(
                        VecSeg.Quad(
                            VecPoint(geometry.coords[c], geometry.coords[c + 1]),
                            VecPoint(geometry.coords[c + 2], geometry.coords[c + 3]),
                        )
                    )
                    c += 4
                }
                else -> {
                    // CUBIC. `validate` has already refused any fourth value, so this is exhaustive
                    // rather than a silent default — an `else` that could swallow an unknown verb is
                    // why that check runs first.
                    segments.add(
                        VecSeg.Cubic(
                            VecPoint(geometry.coords[c], geometry.coords[c + 1]),
                            VecPoint(geometry.coords[c + 2], geometry.coords[c + 3]),
                            VecPoint(geometry.coords[c + 4], geometry.coords[c + 5]),
                        )
                    )
                    c += 6
                }
            }
        }

        shapes.add(
            VecShape(
                path = VecPath(start = start, segments = segments, closed = geometry.isClosed(i)),
                style = dwTraceKotlinVecStyleOf(geometry.styleTable.getOrNull(geometry.styleIndex[i])),
            )
        )
    }

    return DwTraceKotlinDocument(
        document = VecDocument(
            width = dwTraceKotlinDimension(width),
            height = dwTraceKotlinDimension(height),
            layers = listOf(
                VecLayer(id = DW_TRACE_KOTLIN_LAYER_ID, name = DW_TRACE_KOTLIN_LAYER_NAME, shapes = shapes)
            ),
            background = background,
        ),
        shapesWritten = written,
        truncationNote = dwTraceKotlinTruncationNote(shapeCount, written),
    )
}

/**
 * One row of the style table, back as the engine's own `VecStyle`.
 *
 * A NULL STYLE IS A BLACK HAIRLINE, NOT A DROPPED SHAPE. `geometryToDocument.styleFor` makes the same
 * choice and `buildSvg` made it first: a style index outside the table means the geometry was not
 * built by the serialiser this expects, and a line the designer actually drew is worth more than a
 * consistent paint. `VecStyle`'s own defaults are a black stroke at 1.5, so the only overrides here
 * are `cap` and `join`, which the web pins to BUTT/MITER for this case.
 *
 * The three enums arrive as STRINGS because `DwTraceStyle` keeps them as strings — its KDoc argues why
 * — and an unrecognised value falls back to the engine's own default rather than throwing. A newer
 * upstream that adds a join style must not be a crash on a phone in a courtyard, which is the rule
 * `DwSketchTracePlates` already applies where it draws.
 */
private fun dwTraceKotlinVecStyleOf(style: DwTraceStyle?): VecStyle {
    if (style == null) return VecStyle(cap = LineCap.BUTT, join = LineJoin.MITER)
    return VecStyle(
        stroke = style.stroke,
        strokeWidth = if (style.strokeWidth.isFinite() && style.strokeWidth > 0f) style.strokeWidth else 1f,
        fill = style.fill,
        fillRule = if (style.fillRule == "NONZERO") FillRule.NONZERO else FillRule.EVENODD,
        cap = when (style.cap) {
            "ROUND" -> LineCap.ROUND
            "SQUARE" -> LineCap.SQUARE
            else -> LineCap.BUTT
        },
        join = when (style.join) {
            "ROUND" -> LineJoin.ROUND
            "BEVEL" -> LineJoin.BEVEL
            else -> LineJoin.MITER
        },
        miterLimit = if (style.miterLimit.isFinite() && style.miterLimit > 0f) style.miterLimit else 4f,
        opacity = if (style.opacity.isFinite()) style.opacity.coerceIn(0f, 1f) else 1f,
    )
}

/**
 * A page dimension the writers can use: at least one, never a NaN.
 *
 * `geometryToDocument.sanitizeDimension`'s job. Every vector writer divides by the document size to
 * work out its scale — `ExportGeom.scaleXY` substitutes 1 for anything not finite and positive — so a
 * zero here would not crash, it would silently produce a page of the wrong size. Clamping at the
 * source means one answer instead of four writers each rescuing themselves.
 */
private fun dwTraceKotlinDimension(value: Int): Float = if (value < 1) 1f else value.toFloat()

/* ────────────────────────────────────────────────────────────────────────────
 * The exporter
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The [DwTraceExporter] backed by `:core-export`.
 *
 * **[refusal] IS NULL AND CANNOT BE ANYTHING ELSE.** The writers are compiled into the APK by the same
 * build that compiles this file, so there is no device-shaped question left to ask — the same argument
 * `dwTraceKotlinRuntime` makes for the tracer. A format nothing can write still refuses, and it does
 * so per press with a sentence naming that format, which is a better answer than one string decided at
 * construction for all five.
 *
 * Stateless and allocation-free, so `remember` is a formality rather than a cache.
 */
class DwTraceKotlinExporter : DwTraceExporter {

    override val refusal: String? = null

    override suspend fun export(request: DwTraceExportRequest): DwTraceExportOutcome =
        withContext(Dispatchers.Default) {
            val format = dwTraceKotlinExportFormat(request.format)
                ?: return@withContext DwTraceExportOutcome.Refused(
                    dwTraceKotlinUnwritableSentence(request.format)
                )

            // BEFORE THE DOCUMENT AND BEFORE THE WRITE. Both are single synchronous calls over as many
            // as 200,000 shapes and neither can be interrupted from inside, so these two checks are the
            // whole of this route's cancellation granularity — the same bargain `DwTraceRuntime`
            // documents for a stage boundary, one level coarser because there are no stages here.
            currentCoroutineContextEnsureActive()

            val built = try {
                dwTraceKotlinDocumentOf(
                    geometry = request.geometry,
                    width = request.width,
                    height = request.height,
                    background = request.background,
                )
            } catch (failure: DwTraceHostFailure) {
                return@withContext DwTraceExportOutcome.Refused(failure.message.orEmpty())
            }

            currentCoroutineContextEnsureActive()

            val bytes = try {
                Exporter.export(
                    doc = built.document,
                    // NULL, SO THE WRITER RASTERISES IF IT NEEDS TO. It never needs to here: this
                    // exporter is reached only for PDF, EPS and DXF, and all three are vector writers
                    // that ignore the argument entirely. Passing a plate would be handing a raster to a
                    // path that does not read one.
                    raster = null,
                    o = ExportOptions(
                        format = format,
                        // 0/0 means "the document's own size" — `ExportOptions`' own rule, and the
                        // frame the geometry's coordinates are already in.
                        width = 0,
                        height = 0,
                        scale = 1f,
                        background = request.background,
                        // A PNG-NAMED CONSTANT ON THREE VECTOR FORMATS, DELIBERATELY. `dpi` is what
                        // converts document units to physical ones in a PDF, an EPS and a DXF, and 72
                        // is the PostScript/PDF identity — one document pixel becomes one point. Its
                        // own docblock argues the choice and says it is "the right answer for
                        // `ExportOptions.dpi`" generally: nobody in this flow has measured how big the
                        // sheet was, so 300 would assert a physical size nobody knows.
                        dpi = DW_TRACE_PNG_DPI,
                        precision = 2,
                        // See the file header. This is the switch that keeps another product's name
                        // out of a file that reaches a ministry.
                        includeMetadata = false,
                        // FALSE, so `groupByLayer` stays true. There is one layer either way, and
                        // `DxfWriter` is the writer that reads the structure — flattening would cost
                        // the layer name a CAD operator assigns a tool by.
                        flattenLayers = false,
                    ),
                )
            } catch (unsupported: UnsupportedOperationException) {
                // `Exporter.export` throws for JPEG, WEBP and PROJECT. None is offered by
                // `DW_TRACE_EXPORT_FORMATS`, so reaching this is a table that gained a row without a
                // route — a wiring bug, which is why the sentence names the format rather than
                // apologising for the device.
                return@withContext DwTraceExportOutcome.Refused(
                    dwTraceKotlinUnwritableSentence(request.format)
                )
            } catch (memory: OutOfMemoryError) {
                // A DRAWING TOO BIG TO HOLD TWICE. The document and the encoded bytes are both live at
                // the moment of return, and at the shape ceiling that is tens of megabytes on a heap
                // that has just finished a trace. It is caught rather than allowed to kill the app
                // because the trace itself is still on screen and still saveable as an SVG.
                return@withContext DwTraceExportOutcome.Refused(DW_TRACE_EXPORT_MEMORY_SENTENCE)
            }

            DwTraceExportOutcome.Done(bytes)
        }
}

/**
 * `DwTraceExportFormat.engineFormat` as the engine's own enum, or null when it names nothing.
 *
 * THE STRING IS THE SEAM AND THIS IS THE ONLY PLACE IT IS RESOLVED, which is exactly what that field's
 * KDoc asks for: "The runtime maps this string; nothing here does." A second Kotlin register of
 * `ExportFormat`'s ten members is the failure that file says this repository has already shipped twice.
 *
 * Null rather than a throw for an unknown name, because a table row naming a format the engine dropped
 * is a sentence on a button and not a crash on a phone.
 */
private fun dwTraceKotlinExportFormat(format: DwTraceExportFormat): ExportFormat? {
    val named = ExportFormat.entries.firstOrNull { it.name == format.engineFormat } ?: return null
    return if (Exporter.supports(named)) named else null
}

/**
 * The sentence for a format this build cannot write, naming the format and what still works.
 *
 * NAMES WHAT WORKS RATHER THAN ONLY WHAT DOES NOT, which is the shape every refusal in this feature is
 * held to. Unreachable on this build — all five rows of `DW_TRACE_EXPORT_FORMATS` map to a format
 * `Exporter.supports` — and written anyway, because the alternative when a row is added without a
 * route is a button that does nothing.
 */
private fun dwTraceKotlinUnwritableSentence(format: DwTraceExportFormat): String =
    "This app cannot write ${format.label} files. The SVG and the picture still save, and the portal " +
        "can write every format from this same photograph on a laptop when you next have a connection."

/**
 * The sentence for an export that ran out of memory.
 *
 * IT NAMES THE REMEDY THAT ACTUALLY CHANGES THE ARITHMETIC — fewer shapes — rather than "try again",
 * which on a heap this full will fail the same way. The two controls named are the same two
 * `dwTraceKotlinTruncationNote` names for the shape ceiling, because the designer's problem is the
 * same problem in both: this drawing has too many separate paths in it.
 */
const val DW_TRACE_EXPORT_MEMORY_SENTENCE: String =
    "This drawing has too many separate paths for this phone to write out as a file. The SVG still " +
        "saves. Raise “Minimum speck” or “Simplify” and trace again for a drawing that fits."

/**
 * The exporter a host mounts, in one place, so a mount site does not have to know which it is.
 *
 * This was the one line that changed on the day the writers landed. It used to hand back
 * [DwTraceExporterUnavailable] with a measured argument for why a JavaScript bundle could not carry
 * them; the bundle is gone and `:core-export` is in the APK, so it hands back the real one.
 */
@Composable
fun rememberDwTraceExporter(): DwTraceExporter = remember { DwTraceKotlinExporter() }

/**
 * `ensureActive` on the current coroutine context.
 *
 * A named one-liner rather than the two-line incantation repeated twice above, so the two cancellation
 * points read as what they are. `withContext(Dispatchers.Default)` does not itself poll for
 * cancellation between two synchronous calls, which is why they have to be written.
 */
private suspend fun currentCoroutineContextEnsureActive() {
    kotlin.coroutines.coroutineContext.ensureActive()
}
