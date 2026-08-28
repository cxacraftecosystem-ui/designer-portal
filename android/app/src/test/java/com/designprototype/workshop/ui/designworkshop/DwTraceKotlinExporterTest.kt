package com.designprototype.workshop.ui.designworkshop

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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE THREE FORMATS THE HANDSET COULD NOT WRITE UNTIL THE ENGINE WAS VENDORED.**
 *
 * `DwTraceKotlinExporter` closes the gap `DwSketchTraceExporter.kt` documented with a measurement:
 * the JavaScript bundle carried no writers because reaching them cost 56,839 bytes of bundle, so a
 * designer could save an SVG and a picture and nothing else. `:core-export` is compiled into the APK
 * and the constraint is gone. What has to be held is that the bytes are actually a PDF, an EPS and a
 * DXF, that the round trip through the flat geometry does not lose the drawing, and that another
 * product's name does not travel with them.
 *
 * NO ANDROID IN ANY OF IT. Every case here runs on the desktop JVM: the exporter takes a
 * `DwTraceGeometry` and returns a `ByteArray`, and `dwSaveTraceExport` — the half that knows what a
 * Downloads folder is — is a different file. That split is what makes this testable at all.
 */
class DwTraceKotlinExporterTest {

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * The round trip: flat arrays back to a document
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * `dwTraceKotlinDocumentOf` is the EXACT INVERSE of `dwTraceKotlinGeometryOf`.
     *
     * The pair is the one place a drawing can be quietly changed rather than lost: a verb read at the
     * wrong offset does not crash, it reads a neighbouring shape's numbers as this shape's curve and
     * produces something plausible and wrong. So this asserts the geometry back, point for point,
     * rather than asserting that a file was produced.
     *
     * The layer structure is deliberately NOT round-tripped, and that is asserted too — the flat
     * arrays concatenate shapes across layers and keep no boundary, so two layers in become one layer
     * out. `geometryToDocument.ts` makes the same collapse for the portal.
     */
    @Test
    fun `the document rebuilt from flat arrays is the document that was flattened`() {
        val source = threeShapeDocument()
        val geometry = dwTraceKotlinGeometryOf(source)

        val rebuilt = dwTraceKotlinDocumentOf(geometry, width = 40, height = 30, background = null)
        assertNull("nothing was dropped, so there is no cut to report", rebuilt.truncationNote)
        assertEquals(3, rebuilt.shapesWritten)

        val doc = rebuilt.document
        assertEquals(40f, doc.width, 0f)
        assertEquals(30f, doc.height, 0f)
        assertEquals("two layers in, one layer out — the arrays keep no boundary", 1, doc.layers.size)
        assertEquals(DW_TRACE_KOTLIN_LAYER_NAME, doc.layers[0].name)

        val out = doc.layers[0].shapes
        val original = source.layers.flatMap { it.shapes }
        assertEquals(original.size, out.size)
        for (i in original.indices) {
            assertEquals("shape $i start", original[i].path.start, out[i].path.start)
            assertEquals("shape $i closed", original[i].path.closed, out[i].path.closed)
            assertEquals("shape $i segments", original[i].path.segments, out[i].path.segments)
        }

        // The style table survives by value, including the three enums that cross as strings.
        assertEquals(0xFF102030.toInt(), out[0].style.stroke)
        assertEquals(LineCap.ROUND, out[0].style.cap)
        assertEquals(FillRule.EVENODD, out[0].style.fillRule)
        assertNull("a stroke-only style still has no fill", out[0].style.fill)
        assertEquals(0xFF405060.toInt(), out[1].style.fill)
        assertNull("a fill-only style still has no stroke", out[1].style.stroke)
        assertEquals(LineJoin.MITER, out[1].style.join)
    }

    /**
     * A shape whose style index is outside the table REFUSES THE WHOLE EXPORT rather than losing a line.
     *
     * `dwTraceKotlinDocumentOf` calls `DwTraceGeometry.validate` before it walks anything, and that
     * check refuses a style index outside the table — so the `getOrNull` fallback inside the walk is
     * defence in depth and not a live branch. Stated as a test rather than as a comment because the
     * two possible wrong answers here are both silent: dropping the shape makes the file short, and
     * `getOrElse` with a default makes it plausible and wrong.
     *
     * The fallback the walk carries — a plain black hairline for an absent style, `LineCap.BUTT` and
     * `LineJoin.MITER` — is `geometryToDocument.styleFor`'s choice on the portal and `buildSvg`'s
     * before it: a style that did not survive is a paint problem, and a line the designer actually
     * drew is worth more than a consistent paint.
     */
    @Test
    fun `a style index outside the table refuses the export rather than losing the shape`() {
        val good = dwTraceKotlinGeometryOf(threeShapeDocument())
        val outOfRange = DwTraceGeometry(
            coords = good.coords,
            verbs = good.verbs,
            verbStarts = good.verbStarts,
            coordStarts = good.coordStarts,
            closed = good.closed,
            styleTable = good.styleTable,
            styleIndex = IntArray(good.shapeCount) { good.styleTable.size + 7 },
        )
        val failure = runCatching {
            dwTraceKotlinDocumentOf(outOfRange, width = 40, height = 30, background = null)
        }.exceptionOrNull()
        assertTrue("must refuse, not walk: $failure", failure is DwTraceHostFailure)
        assertEquals(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            (failure as DwTraceHostFailure).kind,
        )
    }

    /** A zero page dimension becomes 1 rather than reaching a writer that would divide by it. */
    @Test
    fun `a zero page dimension is clamped rather than passed to a writer`() {
        val geometry = dwTraceKotlinGeometryOf(threeShapeDocument())
        val doc = dwTraceKotlinDocumentOf(geometry, width = 0, height = -5, background = null).document
        assertEquals(1f, doc.width, 0f)
        assertEquals(1f, doc.height, 0f)
    }

    /**
     * The document's background is passed through, never chosen here.
     *
     * `dwTraceExportBackground` exists as a named pass-through for this reason: `ExportOptions.background
     * = null` means "leave the document alone" to a vector writer and "transparent" to a rasteriser, so
     * an export-time colour this file invented would put a PDF and a PNG of one drawing on two grounds.
     */
    @Test
    fun `the background reaches the document rather than being chosen`() {
        val geometry = dwTraceKotlinGeometryOf(threeShapeDocument())
        val white = 0xFFFFFFFF.toInt()
        assertEquals(
            white,
            dwTraceKotlinDocumentOf(geometry, 40, 30, white).document.background,
        )
        assertNull(dwTraceKotlinDocumentOf(geometry, 40, 30, null).document.background)
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * The three files
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * All three formats produce bytes that are actually that format.
     *
     * The magic strings are each format's own required opening, so this is a check that the right
     * writer ran rather than a check that something ran: `%PDF-` opens a PDF, `%!PS-Adobe-3.0 EPSF`
     * opens an Encapsulated PostScript, and a DXF R12 file opens with a `SECTION` group.
     */
    @Test
    fun `the exporter writes a PDF, an EPS and a DXF`() = runBlocking {
        val exporter = DwTraceKotlinExporter()
        assertNull("the writers are in the APK; there is nothing to refuse", exporter.refusal)

        val expected = mapOf("pdf" to "%PDF-", "eps" to "%!PS-Adobe-3.0 EPSF", "dxf" to "SECTION")
        for ((id, magic) in expected) {
            val outcome = exporter.export(requestFor(id))
            assertTrue("$id must be written, not refused", outcome is DwTraceExportOutcome.Done)
            val bytes = (outcome as DwTraceExportOutcome.Done).bytes
            assertTrue("$id produced no bytes", bytes.isNotEmpty())
            assertTrue(
                "$id does not open as one: ${String(bytes.copyOfRange(0, minOf(40, bytes.size)))}",
                String(bytes, Charsets.ISO_8859_1).take(200).contains(magic),
            )
        }
    }

    /**
     * **NO OTHER PRODUCT'S NAME REACHES A MINISTRY SUBMISSION — AND ONE LINE OF ONE FORMAT STILL DOES.**
     *
     * `PdfWriter.kt:135-136` writes `/Producer (Offline Tracer) /Creator (Offline Tracer) /Title
     * (Offline Tracer export)` and `EpsWriter.kt:144` writes `%%Title: Offline Tracer export`, both
     * under `includeMetadata`, which the exporter sets false for exactly this reason — the same switch
     * `dwTraceKotlinSvgOf` sets for the SVG.
     *
     * `EpsWriter.kt:143` writes `%%Creator: Offline Tracer` OUTSIDE that guard, so no option removes
     * it. This case asserts the difference rather than papering over it: the PDF and the DXF are
     * clean, the EPS carries the name in one header comment, and the day somebody changes either
     * expectation this goes red instead of a file going out.
     */
    @Test
    fun `the PDF and the DXF carry no branding and the EPS carries exactly one line of it`() = runBlocking {
        val exporter = DwTraceKotlinExporter()

        for (id in listOf("pdf", "dxf")) {
            val bytes = (exporter.export(requestFor(id)) as DwTraceExportOutcome.Done).bytes
            assertFalse(
                "$id must not carry the engine vendor's name",
                String(bytes, Charsets.ISO_8859_1).contains("Offline Tracer"),
            )
        }

        val eps = String(
            (exporter.export(requestFor("eps")) as DwTraceExportOutcome.Done).bytes,
            Charsets.ISO_8859_1,
        )
        assertFalse("%%Title is under includeMetadata and is off", eps.contains("%%Title"))
        assertEquals(
            "exactly one unavoidable mention, in %%Creator — see this test's docblock",
            1,
            Regex("Offline Tracer").findAll(eps).count(),
        )
        assertTrue(eps.contains("%%Creator: Offline Tracer"))
    }

    /**
     * The provenance note does not reach any of the three, and this pins that rather than hoping.
     *
     * `DwTraceExportRequest.provenanceNote` says it "Reaches `/Title` in a PDF and `%%Title:` in an
     * EPS". That was true of a writer taking a metadata argument; `ExportOptions` has ten fields and
     * none is a title, so with these writers the note is carried and dropped for all three. It is a
     * loss `dwTraceExportLosses` states on screen, and this is where the claim is held honest.
     */
    @Test
    fun `the provenance note reaches none of the three files`() = runBlocking {
        val marker = "TRACED-BY-A-NAMED-DESIGNER-ON-A-HANDSET"
        val exporter = DwTraceKotlinExporter()
        for (id in listOf("pdf", "eps", "dxf")) {
            val bytes = (exporter.export(requestFor(id, provenance = marker)) as DwTraceExportOutcome.Done).bytes
            assertFalse(
                "$id must not be claimed to carry the note — no writer here takes one",
                String(bytes, Charsets.ISO_8859_1).contains(marker),
            )
        }
    }

    /**
     * The layer a CAD operator sees is named, not `LAYER0`.
     *
     * This is the one place the single layer's NAME is load-bearing: `DxfWriter` emits one DXF layer
     * per `VecLayer` and a router, cutter or plotter assigns a tool per layer. `sanitizeLayerName`
     * upper-cases and replaces the space, so "Line art" arrives as `LINE_ART`.
     */
    @Test
    fun `the DXF names its layer after the drawing rather than falling back`() = runBlocking {
        val dxf = String(
            (DwTraceKotlinExporter().export(requestFor("dxf")) as DwTraceExportOutcome.Done).bytes,
            Charsets.ISO_8859_1,
        )
        assertTrue("the DXF layer must be named: $DW_TRACE_KOTLIN_LAYER_NAME", dxf.contains("LINE_ART"))
    }

    /**
     * A format the engine will not encode is a sentence naming the format, not a crash.
     *
     * Unreachable through `DW_TRACE_EXPORT_FORMATS` — all five rows map to a format
     * `Exporter.supports` — so this drives it through a hand-made row, which is exactly the state a
     * sixth row added without a route would create.
     */
    @Test
    fun `a format the engine cannot encode is refused in a sentence`() = runBlocking {
        val webp = DwTraceExportFormat(
            id = "webp",
            label = "WEBP",
            engineFormat = "WEBP",
            extension = "webp",
            mime = "image/webp",
            attachable = false,
            isVector = false,
            save = "Save",
            hint = "",
        )
        val outcome = DwTraceKotlinExporter().export(requestFor("pdf").copyWithFormat(webp))
        assertTrue(outcome is DwTraceExportOutcome.Refused)
        val reason = (outcome as DwTraceExportOutcome.Refused).reason
        assertTrue("the sentence must name the format: $reason", reason.contains("WEBP"))
        assertTrue("and what still works", reason.contains("SVG"))
    }

    /**
     * A self-inconsistent geometry is refused with the validator's own sentence.
     *
     * `DwTraceGeometry.validate` is called before the walk rather than after, so a bad envelope is a
     * sentence on screen and not an `ArrayIndexOutOfBoundsException` out of a writer.
     */
    @Test
    fun `an inconsistent geometry is refused rather than walked`() = runBlocking {
        val good = dwTraceKotlinGeometryOf(threeShapeDocument())
        val bad = DwTraceGeometry(
            coords = good.coords,
            verbs = good.verbs,
            verbStarts = good.verbStarts,
            // One short, so the extents no longer describe the shapes.
            coordStarts = good.coordStarts.copyOf(good.coordStarts.size - 1),
            closed = good.closed,
            styleTable = good.styleTable,
            styleIndex = good.styleIndex,
        )
        val outcome = DwTraceKotlinExporter().export(requestFor("pdf", geometry = bad))
        assertTrue(outcome is DwTraceExportOutcome.Refused)
        assertNotNull((outcome as DwTraceExportOutcome.Refused).reason)
        assertTrue(outcome.reason.isNotBlank())
    }

    /**
     * The shape ceiling is the SVG's, and it is REPORTED.
     *
     * A designer who saves one drawing as an SVG and again as a PDF must get one drawing, so both stop
     * at `DW_TRACE_KOTLIN_MAX_SHAPES` and both report the cut with the same sentence. A vector file
     * with no ceiling of its own would quietly not match the SVG beside it.
     */
    @Test
    fun `the document cap is the SVG cap and the cut is reported in the same sentence`() {
        val geometry = dwTraceKotlinGeometryOf(threeShapeDocument())
        // The real ceiling is 200,000 shapes, which is too large to build in a unit test; the property
        // under test is that BOTH writers ask the same function for the sentence, so it is asserted on
        // the function they share.
        assertEquals(200000, DW_TRACE_KOTLIN_MAX_SHAPES)
        assertNull(dwTraceKotlinTruncationNote(3, 3))
        val note = dwTraceKotlinTruncationNote(shapeCount = 250000, shapesWritten = DW_TRACE_KOTLIN_MAX_SHAPES)
        assertNotNull(note)
        assertTrue("the cut names a remedy", note!!.contains("Simplify") || note.contains("Minimum speck"))
        assertEquals("nothing is cut when nothing is over", 3, geometry.shapeCount)
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════
     * Fixtures
     * ══════════════════════════════════════════════════════════════════════════════════════════ */

    /** Two layers, three shapes, all three verb kinds and two distinct styles. */
    private fun threeShapeDocument(): VecDocument {
        val strokeOnly = VecStyle(
            stroke = 0xFF102030.toInt(),
            strokeWidth = 1.5f,
            fill = null,
            fillRule = FillRule.EVENODD,
            cap = LineCap.ROUND,
            join = LineJoin.ROUND,
        )
        val fillOnly = VecStyle(
            stroke = null,
            strokeWidth = 2f,
            fill = 0xFF405060.toInt(),
            fillRule = FillRule.NONZERO,
            cap = LineCap.BUTT,
            join = LineJoin.MITER,
        )
        val line = VecShape(
            VecPath(VecPoint(1f, 2f), listOf(VecSeg.Line(VecPoint(3f, 4f))), closed = false),
            strokeOnly,
        )
        val quad = VecShape(
            VecPath(
                VecPoint(5f, 6f),
                listOf(VecSeg.Quad(VecPoint(7f, 8f), VecPoint(9f, 10f))),
                closed = true,
            ),
            fillOnly,
        )
        val cubic = VecShape(
            VecPath(
                VecPoint(11f, 12f),
                listOf(VecSeg.Cubic(VecPoint(13f, 14f), VecPoint(15f, 16f), VecPoint(17f, 18f))),
                closed = false,
            ),
            strokeOnly,
        )
        return VecDocument(
            width = 40f,
            height = 30f,
            layers = listOf(
                VecLayer("a", "A", listOf(line, quad)),
                VecLayer("b", "B", listOf(cubic)),
            ),
            background = null,
        )
    }

    private fun requestFor(
        id: String,
        provenance: String = "",
        geometry: DwTraceGeometry = dwTraceKotlinGeometryOf(threeShapeDocument()),
    ): DwTraceExportRequest = DwTraceExportRequest(
        geometry = geometry,
        width = 40,
        height = 30,
        format = requireNotNull(dwTraceExportFormat(id)) { "no `$id` row in DW_TRACE_EXPORT_FORMATS" },
        background = null,
        provenanceNote = provenance,
    )

    private fun DwTraceExportRequest.copyWithFormat(next: DwTraceExportFormat) = DwTraceExportRequest(
        geometry = geometry,
        width = width,
        height = height,
        format = next,
        background = background,
        provenanceNote = provenanceNote,
    )
}
