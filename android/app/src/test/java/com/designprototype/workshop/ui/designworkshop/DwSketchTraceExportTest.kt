package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwSketchTraceExportTest {

    @Test
    fun `every engine format is either offered or refused in writing`() {
        assertEquals(
            "engine/exportFormats.ExportFormat has ten members",
            10,
            DW_TRACE_EXPORT_FORMAT_COUNT + DW_TRACE_NOT_OFFERED.size,
        )
        assertEquals(5, DW_TRACE_EXPORT_FORMAT_COUNT)
        assertEquals(5, DW_TRACE_NOT_OFFERED.size)
    }

    @Test
    fun `every refusal carries a reason`() {
        DW_TRACE_NOT_OFFERED.forEach { absence ->
            assertTrue(
                "${absence.engineFormat} is refused with no reason",
                absence.reason.length > 40,
            )
        }
    }

    @Test
    fun `the table has no duplicates`() {
        val ids = DW_TRACE_EXPORT_FORMATS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        val extensions = DW_TRACE_EXPORT_FORMATS.map { it.extension }
        assertEquals(extensions.size, extensions.toSet().size)

        val engineFormats = DW_TRACE_EXPORT_FORMATS.map { it.engineFormat }
        assertEquals(engineFormats.size, engineFormats.toSet().size)

        val refused = DW_TRACE_NOT_OFFERED.map { it.engineFormat }.toSet()
        assertTrue(
            "a format cannot be both offered and refused",
            engineFormats.none { it in refused },
        )
    }

    @Test
    fun `the rows match the web table, in order`() {
        assertEquals(
            listOf("svg", "png", "pdf", "dxf", "eps"),
            DW_TRACE_EXPORT_FORMATS.map { it.id },
        )
        assertEquals(
            listOf(
                "image/svg+xml",
                "image/png",
                "application/pdf",
                "image/vnd.dxf",
                "application/postscript",
            ),
            DW_TRACE_EXPORT_FORMATS.map { it.mime },
        )
        assertEquals(
            listOf("svg", "png", "pdf", "dxf", "eps"),
            DW_TRACE_EXPORT_FORMATS.map { it.extension },
        )
    }

    @Test
    fun `only the PNG is a raster`() {
        assertEquals(
            setOf("png"),
            DW_TRACE_EXPORT_FORMATS.filterNot { it.isVector }.map { it.id }.toSet(),
        )
    }

    @Test
    fun `the attachable formats are the derived two`() {
        assertEquals(listOf("svg", "png"), DW_TRACE_ATTACHABLE_FORMATS.map { it.id })
        assertEquals(
            DW_TRACE_EXPORT_FORMATS.count { it.attachable },
            DW_TRACE_ATTACHABLE_FORMATS.size,
        )
    }

    @Test
    fun `every row has a button label and a hint`() {
        DW_TRACE_EXPORT_FORMATS.forEach { row ->
            assertTrue(row.id, row.save.isNotBlank())
            assertTrue(row.id, row.hint.length > 40)
            assertTrue(row.id, row.label.isNotBlank())
        }
    }

    @Test
    fun `the PNG hint states the cap the scale enforces`() {
        val png = dwTraceExportFormat("png")
        assertNotNull(png)
        assertTrue(png!!.hint.contains(DW_TRACE_PNG_MAX_EDGE_PX.toString()))
        assertEquals(1.0, dwTracePngScale(DW_TRACE_PNG_MAX_EDGE_PX, 100), 0.0)
        assertTrue(dwTracePngScale(DW_TRACE_PNG_MAX_EDGE_PX + 1, 100) < 1.0)
    }

    @Test
    fun `derived names follow the web's rules`() {
        assertEquals(
            "sheet-line-art.svg",
            dwTraceExportFileName("sheet.jpg", "svg", DW_TRACE_ATTACH_SUFFIX),
        )
        assertEquals(
            "sheet.v2-line-art.svg",
            dwTraceExportFileName("sheet.v2.jpg", "svg", DW_TRACE_ATTACH_SUFFIX),
        )
        assertEquals("sketch-line-art.svg", dwTraceExportFileName("", "svg"))
        assertEquals("sketch-line-art.svg", dwTraceExportFileName(".jpg", "svg"))
        assertEquals(
            "my sheet-line-art.svg",
            dwTraceExportFileName("my sheet.jpg", "svg"),
        )
        assertEquals("sheet.svg", dwTraceExportFileName("sheet.jpg", "svg", ""))
    }

    @Test
    fun `a path is reduced to its leaf and the dangerous punctuation is replaced`() {
        assertEquals(
            "sheet-line-art.svg",
            dwTraceExportFileName("/storage/emulated/0/DCIM/sheet.jpg", "svg"),
        )
        assertEquals(
            "sheet-line-art.svg",
            dwTraceExportFileName("C:\\photos\\sheet.jpg", "svg"),
        )
        val awkward = dwTraceExportFileName("Ikat:Bandha*sheet?.jpg", "svg")
        assertFalse(awkward.contains(':'))
        assertFalse(awkward.contains('*'))
        assertFalse(awkward.contains('?'))
        assertFalse(awkward.contains('/'))
        assertTrue(awkward.endsWith("-line-art.svg"))
    }

    @Test
    fun `the stem is capped`() {
        val long = "a".repeat(400)
        val name = dwTraceExportFileName("$long.jpg", "svg")
        assertEquals("${"a".repeat(80)}-line-art.svg", name)
    }

    @Test
    fun `no two saved formats from one photograph share a file name`() {
        val names = DW_TRACE_EXPORT_FORMATS.map { format ->
            dwTraceExportFileName("sheet.jpg", format.extension, dwTraceSaveSuffix(format))
        }
        assertEquals(names.toString(), names.size, names.toSet().size)

        val attachedPng = dwTraceExportFileName("sheet.jpg", "png", DW_TRACE_ATTACH_SUFFIX)
        val savedPng = dwTraceExportFileName(
            "sheet.jpg",
            "png",
            dwTraceSaveSuffix(dwTraceExportFormat("png")!!),
        )
        assertEquals("sheet-line-art.png", attachedPng)
        assertEquals("sheet-traced.png", savedPng)
    }

    @Test
    fun `every vector form shares the attach word and the raster does not`() {
        DW_TRACE_EXPORT_FORMATS.filter { it.isVector }.forEach { format ->
            assertEquals(format.id, DW_TRACE_ATTACH_SUFFIX, dwTraceSaveSuffix(format))
        }
        DW_TRACE_EXPORT_FORMATS.filterNot { it.isVector }.forEach { format ->
            assertEquals(format.id, DW_TRACE_RENDER_SUFFIX, dwTraceSaveSuffix(format))
        }
    }

    @Test
    fun `the PNG scale is a ceiling and never an enlargement`() {
        assertEquals(1.0, dwTracePngScale(800, 600), 0.0)
        assertEquals(1.0, dwTracePngScale(2048, 2048), 0.0)
        assertEquals(0.5, dwTracePngScale(4096, 3072), 1e-9)
        assertEquals(1.0, dwTracePngScale(0, 0), 0.0)
        assertEquals(1.0, dwTracePngScale(-10, -10), 0.0)
        assertEquals(dwTracePngScale(4096, 100), dwTracePngScale(100, 4096), 1e-12)
    }

    @Test
    fun `the export passes the document's own background through`() {
        val white = 0xFFFFFFFFL.toInt()
        assertEquals(-1, white)
        assertEquals(0xFF, (white ushr 24) and 0xFF)

        assertEquals(white, dwTraceExportBackground(white))
        assertNull(dwTraceExportBackground(null))

        assertTrue(dwTraceBackgroundIsWhite(white))
        assertFalse(dwTraceBackgroundIsWhite(null))
        assertEquals("White", dwTraceBackgroundLabel(white))
        assertEquals("Transparent", dwTraceBackgroundLabel(null))
    }

    @Test
    fun `the document background is read off the applied parameters`() {
        val white = DwTraceValues(
            mapOf("output.background" to DwTraceValue.Num(DW_TRACE_OPAQUE_WHITE)),
            "{}",
        )
        assertEquals(WHITE, dwTraceDocumentBackground(white))
        assertTrue(dwTraceBackgroundIsWhite(dwTraceDocumentBackground(white)))

        val transparent = DwTraceValues(
            mapOf("output.background" to DwTraceValue.Absent),
            "{}",
        )
        assertNull(dwTraceDocumentBackground(transparent))
        assertFalse(dwTraceBackgroundIsWhite(dwTraceDocumentBackground(transparent)))

        assertNull(dwTraceDocumentBackground(DwTraceValues(emptyMap(), "{}")))

        val black = DwTraceValues(
            mapOf("output.background" to DwTraceValue.Num(4278190080.0)),
            "{}",
        )
        assertEquals(-16777216, dwTraceDocumentBackground(black))
    }

    @Test
    fun `white is spelled the same on both sides of the parameter tree`() {
        assertEquals(0xFFFFFFFFL.toInt(), DW_TRACE_OPAQUE_WHITE.toLong().toInt())
        assertTrue(dwTraceBackgroundIsWhite(DW_TRACE_OPAQUE_WHITE.toLong().toInt()))
    }

    private val svg = dwTraceExportFormat("svg")!!
    private val pdf = dwTraceExportFormat("pdf")!!
    private val png = dwTraceExportFormat("png")!!

    private val WHITE: Int = 0xFFFFFFFFL.toInt()
    private val BACKGROUNDS: List<Int?> = listOf(WHITE, null)

    @Test
    fun `each format takes the route its bytes actually come from`() {
        assertEquals(DwTraceExportPlan.FromTraceSvg, dwTraceExportPlan(svg, null))
        assertEquals(DwTraceExportPlan.FromPlatformRaster, dwTraceExportPlan(png, null))
        listOf("pdf", "dxf", "eps").forEach { id ->
            val format = dwTraceExportFormat(id)!!
            assertEquals(id, DwTraceExportPlan.FromExporter, dwTraceExportPlan(format, null))
        }
    }

    @Test
    fun `a build with no writers refuses three and still writes the SVG and the PNG`() {
        val refusal = DW_TRACE_NO_EXPORTER_SENTENCE
        assertEquals(DwTraceExportPlan.FromTraceSvg, dwTraceExportPlan(svg, refusal))
        assertEquals(DwTraceExportPlan.FromPlatformRaster, dwTraceExportPlan(png, refusal))

        val refused = DW_TRACE_EXPORT_FORMATS.filter { format ->
            dwTraceExportPlan(format, refusal) is DwTraceExportPlan.Refused
        }
        assertEquals(listOf("pdf", "dxf", "eps"), refused.map { it.id })
        refused.forEach { format ->
            val plan = dwTraceExportPlan(format, refusal) as DwTraceExportPlan.Refused
            assertEquals(format.id, refusal, plan.reason)
        }
    }

    @Test
    fun `no geometry and no writers are two refusals and not one`() {
        assertEquals(
            DwTraceExportPlan.FromTraceSvg,
            dwTraceExportPlan(svg, DW_TRACE_NO_EXPORTER_SENTENCE, hasGeometry = false),
        )
        DW_TRACE_EXPORT_FORMATS.filter { it.id != "svg" }.forEach { format ->
            val plan = dwTraceExportPlan(format, null, hasGeometry = false)
            assertTrue(format.id, plan is DwTraceExportPlan.Refused)
            assertEquals(
                format.id,
                DW_TRACE_NO_GEOMETRY_SENTENCE,
                (plan as DwTraceExportPlan.Refused).reason,
            )
        }
        val both = dwTraceExportPlan(pdf, DW_TRACE_NO_EXPORTER_SENTENCE, hasGeometry = false)
        assertEquals(
            DW_TRACE_NO_GEOMETRY_SENTENCE,
            (both as DwTraceExportPlan.Refused).reason,
        )
        assertNotEquals(DW_TRACE_NO_GEOMETRY_SENTENCE, DW_TRACE_NO_EXPORTER_SENTENCE)
    }

    @Test
    fun `the unavailable exporter refuses in a sentence`() {
        assertEquals(DW_TRACE_NO_EXPORTER_SENTENCE, DwTraceExporterUnavailable().refusal)
        assertEquals("a WebView from 2019.", DwTraceExporterUnavailable("a WebView from 2019.").refusal)
    }

    @Test
    fun `every save states what the report carries, first, from one constant`() {
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            BACKGROUNDS.forEach { background ->
                val lines = dwTraceExportLosses(format, background)
                assertEquals(
                    format.id + "/" + dwTraceBackgroundLabel(background),
                    DW_TRACE_EXPORT_REPORT_SENTENCE,
                    lines.first(),
                )
                assertEquals(
                    "the report sentence must appear exactly once",
                    1,
                    lines.count { it == DW_TRACE_EXPORT_REPORT_SENTENCE },
                )
            }
        }
    }

    @Test
    fun `the report sentence denies and then names the remedy`() {
        val sentence = DW_TRACE_EXPORT_REPORT_SENTENCE
        assertTrue(sentence.contains("does not go into the workshop report"))
        assertTrue(sentence.contains("send it to them"))
    }

    @Test
    fun `the losses name each format's own gap`() {
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, WHITE)
            assertTrue(format.id, lines.contains(DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE))
        }

        val branded = setOf("eps")
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, WHITE)
            val saysSo = lines.contains(DW_TRACE_EXPORT_ENGINE_NAME_SENTENCE)
            assertEquals(format.id, format.id in branded, saysSo)
        }

        val dxfLines = dwTraceExportLosses(dwTraceExportFormat("dxf")!!, WHITE)
        assertTrue(dxfLines.any { it.contains("DXF R12") && it.contains("short straight lines") })
    }

    @Test
    fun `the raster cap is stated exactly when it can bite`() {
        val big = dwTraceExportLosses(png, WHITE, 4096)
        assertTrue(big.any { it.contains(DW_TRACE_PNG_MAX_EDGE_PX.toString()) })

        val unknown = dwTraceExportLosses(png, WHITE, 0)
        assertTrue(unknown.any { it.contains(DW_TRACE_PNG_MAX_EDGE_PX.toString()) })

        val small = dwTraceExportLosses(png, WHITE, 900)
        assertFalse(small.any { it.contains(DW_TRACE_PNG_MAX_EDGE_PX.toString()) })
    }

    @Test
    fun `the transparency warning is drawn for every format that has a background`() {
        val warning = "Transparent means the page shows through"
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, null)
            val warned = lines.any { it.startsWith(warning) }
            assertEquals(format.id, format.id != "dxf", warned)
        }
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, WHITE)
            assertFalse(format.id, lines.any { it.startsWith(warning) })
        }
    }

    @Test
    fun `the feature's sentences are all distinct and all actionable`() {
        val sentences = listOf(
            DW_TRACE_EXPORT_REPORT_SENTENCE,
            DW_TRACE_ATTACH_REPORT_SENTENCE,
            DW_TRACE_EXPORT_ENGINE_NAME_SENTENCE,
            DW_TRACE_NO_EXPORTER_SENTENCE,
            DW_TRACE_NO_GEOMETRY_SENTENCE,
            DW_TRACE_EXPORT_NO_SHARE_SENTENCE,
            DW_TRACE_EXPORT_SHARE_CAVEAT,
            DW_TRACE_EXPORT_PREVIEW_SENTENCE,
            DW_TRACE_BACKGROUND_RETRACE_SENTENCE,
            DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE,
            DW_TRACE_EXPORT_NO_FRAME_SENTENCE,
            DW_TRACE_PNG_MEMORY_REFUSAL,
        )
        assertEquals(sentences.size, sentences.toSet().size)
        sentences.forEach { sentence ->
            assertTrue(sentence, sentence.length > 60)
            assertTrue("a sentence ends", sentence.trimEnd().endsWith("."))
        }
    }

    @Test
    fun `the attach sentence says what the report does and does not do with the file`() {
        val sentence = DW_TRACE_ATTACH_REPORT_SENTENCE
        assertTrue(sentence.contains("does not carry it"))
        assertTrue(sentence.contains("workshop record"))
        assertFalse(sentence.contains("a report can print"))
    }

    @Test
    fun `the PNG size is the web's arithmetic, edge for edge`() {
        assertEquals(DwTracePngSize(1600, 1200, reduced = false), dwTracePngSize(1600, 1200))
        assertEquals(
            DwTracePngSize(DW_TRACE_PNG_MAX_EDGE_PX, 1024, reduced = false),
            dwTracePngSize(DW_TRACE_PNG_MAX_EDGE_PX, 1024),
        )
        assertEquals(DwTracePngSize(2048, 1536, reduced = true), dwTracePngSize(4096, 3072))
        assertEquals(DwTracePngSize(1536, 2048, reduced = true), dwTracePngSize(3072, 4096))

        assertEquals(685, dwTracePngSize(3000, 1003).height)

        assertEquals(1, dwTracePngSize(20480, 4).height)
        assertEquals(DW_TRACE_PNG_MAX_EDGE_PX, dwTracePngSize(20480, 4).width)
        assertEquals(DwTracePngSize(1, 1, reduced = false), dwTracePngSize(0, 0))
        assertEquals(DwTracePngSize(1, 1, reduced = false), dwTracePngSize(-10, -10))
    }

    @Test
    fun `the reduction sentence appears exactly when the cap bit and carries both sizes`() {
        val reduced = dwTracePngSize(4096, 3072)
        val note = dwTracePngReductionNote(4096, 3072, reduced)
        assertTrue(note, note.contains("2048x1536"))
        assertTrue(note, note.contains("4096x3072"))
        assertTrue(note, note.contains("SVG"))

        assertEquals("", dwTracePngReductionNote(1600, 1200, dwTracePngSize(1600, 1200)))
        assertEquals(
            "",
            dwTracePngReductionNote(
                DW_TRACE_PNG_MAX_EDGE_PX,
                DW_TRACE_PNG_MAX_EDGE_PX,
                dwTracePngSize(DW_TRACE_PNG_MAX_EDGE_PX, DW_TRACE_PNG_MAX_EDGE_PX),
            ),
        )
    }

    @Test
    fun `the PNG hint spells the cap the way the web spells it`() {
        val hint = dwTraceExportFormat("png")!!.hint
        assertTrue(hint, hint.contains("${DW_TRACE_PNG_MAX_EDGE_PX}px on its long edge"))
        assertFalse(hint, hint.contains("$DW_TRACE_PNG_MAX_EDGE_PX px"))
    }

    @Test
    fun `a crop is stated beside every format that cannot record it`() {
        val cropped = "Cropped on the device to 900x1200 at (30, 40) of 3000x4000."
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, WHITE, 0, cropped)
            val silent = lines.contains(DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE)
            assertEquals(
                format.id,
                silent,
                lines.contains(DW_TRACE_EXPORT_NO_FRAME_SENTENCE),
            )
        }
        assertEquals(
            listOf("svg", "png", "pdf", "dxf", "eps"),
            DW_TRACE_EXPORT_FORMATS
                .filter { DW_TRACE_EXPORT_NO_FRAME_SENTENCE in dwTraceExportLosses(it, WHITE, 0, cropped) }
                .map { it.id },
        )
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            assertFalse(
                format.id,
                dwTraceExportLosses(format, WHITE).contains(DW_TRACE_EXPORT_NO_FRAME_SENTENCE),
            )
            assertFalse(
                format.id,
                dwTraceExportLosses(format, WHITE, 0, "   ")
                    .contains(DW_TRACE_EXPORT_NO_FRAME_SENTENCE),
            )
        }
    }

    @Test
    fun `the report sentence is still first with every other loss in the list`() {
        val cropped = "Cropped on the device to 900x1200 at (30, 40) of 3000x4000."
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, null, 4096, cropped)
            assertEquals(format.id, DW_TRACE_EXPORT_REPORT_SENTENCE, lines.first())
            assertEquals(format.id, lines.size, lines.toSet().size)
        }
    }

    @Test
    fun `both refusals name a remedy`() {
        assertTrue(DW_TRACE_NO_EXPORTER_SENTENCE.contains("SVG"))
        assertTrue(DW_TRACE_NO_EXPORTER_SENTENCE.contains("picture"))
        assertTrue(DW_TRACE_EXPORT_PREVIEW_SENTENCE.contains("full size"))
    }

    @Test
    fun `the provenance note names the photograph and the counts`() {
        val note = dwTraceProvenanceNote("/storage/emulated/0/DCIM/sheet.jpg", 193, 812)
        assertTrue(note.contains("sheet.jpg"))
        assertFalse("the path must not travel into the file", note.contains("/storage/"))
        assertTrue(note.contains("193 paths"))
        assertTrue(note.contains("812 nodes"))
    }

    @Test
    fun `the provenance note survives a nameless photograph`() {
        val note = dwTraceProvenanceNote("", 1, 2)
        assertTrue(note.contains("a photograph"))
        assertFalse(note.contains("from  "))
    }

    @Test
    fun `the frame sentence is appended only when there is one`() {
        val plain = dwTraceProvenanceNote("sheet.jpg", 1, 2)
        val framed = dwTraceProvenanceNote("sheet.jpg", 1, 2, "Cropped to the sheet, sharpened 0.8.")
        assertTrue(framed.startsWith(plain))
        assertTrue(framed.endsWith("Cropped to the sheet, sharpened 0.8."))
        assertEquals(plain, dwTraceProvenanceNote("sheet.jpg", 1, 2, "   "))
    }
}
