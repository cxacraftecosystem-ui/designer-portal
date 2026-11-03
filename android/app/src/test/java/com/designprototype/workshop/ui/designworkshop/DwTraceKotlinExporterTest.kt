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

class DwTraceKotlinExporterTest {

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

        assertEquals(0xFF102030.toInt(), out[0].style.stroke)
        assertEquals(LineCap.ROUND, out[0].style.cap)
        assertEquals(FillRule.EVENODD, out[0].style.fillRule)
        assertNull("a stroke-only style still has no fill", out[0].style.fill)
        assertEquals(0xFF405060.toInt(), out[1].style.fill)
        assertNull("a fill-only style still has no stroke", out[1].style.stroke)
        assertEquals(LineJoin.MITER, out[1].style.join)
    }

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

    @Test
    fun `a zero page dimension is clamped rather than passed to a writer`() {
        val geometry = dwTraceKotlinGeometryOf(threeShapeDocument())
        val doc = dwTraceKotlinDocumentOf(geometry, width = 0, height = -5, background = null).document
        assertEquals(1f, doc.width, 0f)
        assertEquals(1f, doc.height, 0f)
    }

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

    @Test
    fun `the DXF names its layer after the drawing rather than falling back`() = runBlocking {
        val dxf = String(
            (DwTraceKotlinExporter().export(requestFor("dxf")) as DwTraceExportOutcome.Done).bytes,
            Charsets.ISO_8859_1,
        )
        assertTrue("the DXF layer must be named: $DW_TRACE_KOTLIN_LAYER_NAME", dxf.contains("LINE_ART"))
    }

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

    @Test
    fun `an inconsistent geometry is refused rather than walked`() = runBlocking {
        val good = dwTraceKotlinGeometryOf(threeShapeDocument())
        val bad = DwTraceGeometry(
            coords = good.coords,
            verbs = good.verbs,
            verbStarts = good.verbStarts,
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

    @Test
    fun `the document cap is the SVG cap and the cut is reported in the same sentence`() {
        val geometry = dwTraceKotlinGeometryOf(threeShapeDocument())
        assertEquals(200000, DW_TRACE_KOTLIN_MAX_SHAPES)
        assertNull(dwTraceKotlinTruncationNote(3, 3))
        val note = dwTraceKotlinTruncationNote(shapeCount = 250000, shapesWritten = DW_TRACE_KOTLIN_MAX_SHAPES)
        assertNotNull(note)
        assertTrue("the cut names a remedy", note!!.contains("Simplify") || note.contains("Minimum speck"))
        assertEquals("nothing is cut when nothing is over", 3, geometry.shapeCount)
    }

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
