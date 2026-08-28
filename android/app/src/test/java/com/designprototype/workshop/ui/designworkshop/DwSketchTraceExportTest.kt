package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE EXPORT SURFACE OF THE SKETCH TRACER, HELD TO THE FOUR THINGS THAT CAN GO WRONG SILENTLY.**
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR, AND WHAT IT CANNOT REACH
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * There is no Robolectric in this module (`app/build.gradle.kts` declares JUnit 4 and nothing else),
 * so nothing here composes a card, opens a share sheet or writes a byte to flash. What it CAN reach is
 * the half where the defects are invisible rather than loud: a table that has drifted from the web's,
 * a file name that collides with another file name, a background that means one thing to the vector
 * writers and another to the rasteriser, a picture that came out a different number of pixels across
 * from the portal's, and a sentence about the report that has quietly stopped being true. Every one
 * of those renders perfectly and is wrong.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE FOUR PROPERTIES
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **1. THE TABLE IS A BIJECTION WITH THE ENGINE'S TEN FORMATS.** `ExportFormat` has ten members and
 * every one must be offered or refused in writing. The web's `sketch-export-formats-unit.spec.ts`
 * holds that against the real enum; nothing in the Kotlin tree can see a TypeScript enum, so this
 * holds the ARITHMETIC and says so rather than pretending to more. That asymmetry is the honest one.
 *
 * **2. TWO FILES FROM ONE PHOTOGRAPH MUST NOT SHARE A NAME.** The panel produces the drawing and a
 * picture of the drawing, and both land in one Downloads folder where the record's own provenance is
 * not there to tell them apart. `traceExport.ts` names this as the reason the suffix is a parameter at
 * all.
 *
 * **3. THE ROUTE AN SVG TAKES IS A PROOF, AND THE PROOF HAS A PRECONDITION.** The engine's own SVG
 * string is saved verbatim, which is sound only because the export passes the DOCUMENT'S OWN
 * background through rather than choosing one — `dwTraceExportBackground`. The moment an export-time
 * white appears that the traced document does not have, the string in hand stops being `exportSvg`'s
 * answer and a `<rect>` this side may not write would be missing from the file.
 *
 * **4. THE SENTENCE ABOUT THE REPORT IS PRINTED AGAINST EVERY SAVE.** `ReportBuilder.
 * attachments_named_but_not_carried` records that this claim "cannot be verified from any client" and
 * counts five passes over one wrong version of it in a single day. The defence is that there is ONE of
 * it and that it is unconditional; both are asserted here.
 *
 * Pinned in the spirit of `DwSketchChooserSentenceTest`: the property, not the prose. Where a literal
 * string IS the property — the report sentence being one constant rather than five phrasings — the
 * assertion is about identity and placement, not about wording, so a copy edit does not turn this red.
 */
class DwSketchTraceExportTest {

    /* ────────────────────────────────────────────────────────────────────────
     * 1. The table
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * Ten formats exist in `engine/exportFormats.ExportFormat`; five are offered and five are refused.
     *
     * The count is the whole of what this side can check. If a writer is ever added to the engine, the
     * web's spec fails first (it compares against the enum) and this one fails second, when somebody
     * adds the row here — which is the right order, because the decision about whether to offer it is
     * made once, on the web's table, and copied.
     */
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

    /** A refusal with no reason is an omission wearing a list's clothes. */
    @Test
    fun `every refusal carries a reason`() {
        DW_TRACE_NOT_OFFERED.forEach { absence ->
            assertTrue(
                "${absence.engineFormat} is refused with no reason",
                absence.reason.length > 40,
            )
        }
    }

    /**
     * No id, extension or engine format appears twice, and no format is both offered and refused.
     *
     * A duplicated extension is the one that bites: two rows writing `.svg` would give the card two
     * chips that produce the same file name, and the second save would silently overwrite the first in
     * the pre-Q branch of `persistFileToDownloads`, which copies rather than uniquifies.
     */
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

    /**
     * The row order, the ids and the MIME types are the web's.
     *
     * WRITTEN OUT RATHER THAN DERIVED, because that is the only way this assertion can catch anything:
     * a check that the table matches itself is not a check. These five strings were read off
     * `frontend/components/sketches/upload/traceExport.ts` on 2026-08-27; re-check with
     *
     *     grep -n "    id: \"\|    mime: \"" frontend/components/sketches/upload/traceExport.ts
     *
     * SVG leads because it is what `sketch.lineArtFile` is declared for, and the two attachable rows
     * come first so that a chooser above and the buttons below list them in one order.
     */
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

    /**
     * `isVector` matches `ExportOptions.isVector`, which is SVG, PDF, EPS, DXF (and PROJECT, absent).
     *
     * It decides the suffix and whether the raster cap is quoted, so a wrong flag renames a file and
     * mis-states a limit in the same breath.
     */
    @Test
    fun `only the PNG is a raster`() {
        assertEquals(
            setOf("png"),
            DW_TRACE_EXPORT_FORMATS.filterNot { it.isVector }.map { it.id }.toSet(),
        )
    }

    /**
     * The attachable set is DERIVED and is exactly the web's two.
     *
     * The three take-away formats are `attachable = false` deliberately: the record is a shared archive
     * the handset and the web both read, and filing a `.dxf` on `sketch.lineArtFile` would put a file
     * in it that neither client can preview or explain, to buy a designer nothing they do not already
     * get by saving it.
     */
    @Test
    fun `the attachable formats are the derived two`() {
        assertEquals(listOf("svg", "png"), DW_TRACE_ATTACHABLE_FORMATS.map { it.id })
        assertEquals(
            DW_TRACE_EXPORT_FORMATS.count { it.attachable },
            DW_TRACE_ATTACHABLE_FORMATS.size,
        )
    }

    /** Every row has words on its button and a sentence under it. A blank hint is a silent control. */
    @Test
    fun `every row has a button label and a hint`() {
        DW_TRACE_EXPORT_FORMATS.forEach { row ->
            assertTrue(row.id, row.save.isNotBlank())
            assertTrue(row.id, row.hint.length > 40)
            assertTrue(row.id, row.label.isNotBlank())
        }
    }

    /**
     * The PNG hint quotes the cap from the constant, not from a typed-out number.
     *
     * §1.10 of the frontend contract only holds if the stated cap IS the enforced one, and a number
     * written into copy is a second copy that goes stale. The assertion is that the digits in the hint
     * and the digits [dwTracePngScale] enforces are one value.
     */
    @Test
    fun `the PNG hint states the cap the scale enforces`() {
        val png = dwTraceExportFormat("png")
        assertNotNull(png)
        assertTrue(png!!.hint.contains(DW_TRACE_PNG_MAX_EDGE_PX.toString()))
        // And the enforcement agrees at the boundary, in both directions.
        assertEquals(1.0, dwTracePngScale(DW_TRACE_PNG_MAX_EDGE_PX, 100), 0.0)
        assertTrue(dwTracePngScale(DW_TRACE_PNG_MAX_EDGE_PX + 1, 100) < 1.0)
    }

    /* ────────────────────────────────────────────────────────────────────────
     * 2. Naming
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * The transliteration of `geometryToSvg.derivedFileName`, case for case.
     *
     * These are the rules that decide whether two clients name one file one way, and each line here is
     * one of that function's own behaviours rather than a case invented for this suite.
     */
    @Test
    fun `derived names follow the web's rules`() {
        // Stem kept, one extension stripped, suffix added.
        assertEquals(
            "sheet-line-art.svg",
            dwTraceExportFileName("sheet.jpg", "svg", DW_TRACE_ATTACH_SUFFIX),
        )
        // Only the LAST extension goes: a name with dots in it keeps them.
        assertEquals(
            "sheet.v2-line-art.svg",
            dwTraceExportFileName("sheet.v2.jpg", "svg", DW_TRACE_ATTACH_SUFFIX),
        )
        // An empty or extension-only name falls back to "sketch" rather than producing "-line-art.svg".
        assertEquals("sketch-line-art.svg", dwTraceExportFileName("", "svg"))
        assertEquals("sketch-line-art.svg", dwTraceExportFileName(".jpg", "svg"))
        // Spaces survive, because MediaStore has never objected to one and every phone gallery
        // produces them. See dwTraceExportFileName's note on why the web's rule is kept rather than
        // tightened to ReportExport's stricter filter.
        assertEquals(
            "my sheet-line-art.svg",
            dwTraceExportFileName("my sheet.jpg", "svg"),
        )
        // An empty suffix produces no trailing hyphen.
        assertEquals("sheet.svg", dwTraceExportFileName("sheet.jpg", "svg", ""))
    }

    /**
     * The two characters that took a whole rendered report down are replaced.
     *
     * `ReportExport.defaultName` records the incident: "A craft name carrying a slash (\"Ikat/Bandha\")
     * or a colon produced a MediaStore insert that failed with a bare IllegalArgumentException after
     * the whole report had already been rendered." A slash is also a path separator, so the leaf is
     * taken first and what is left cannot contain one — which this asserts from both directions.
     */
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

    /** The stem is capped at 80 characters, as the web caps it, so a long gallery name cannot run away. */
    @Test
    fun `the stem is capped`() {
        val long = "a".repeat(400)
        val name = dwTraceExportFileName("$long.jpg", "svg")
        assertEquals("${"a".repeat(80)}-line-art.svg", name)
    }

    /**
     * **PROPERTY 2.** The drawing and the picture of the drawing never share a name.
     *
     * This is the whole reason two suffix constants exist. Asserted across every offered format from
     * one photograph, which is the situation a designer actually creates: five saves, one Downloads
     * folder, no record provenance in sight.
     */
    @Test
    fun `no two saved formats from one photograph share a file name`() {
        val names = DW_TRACE_EXPORT_FORMATS.map { format ->
            dwTraceExportFileName("sheet.jpg", format.extension, dwTraceSaveSuffix(format))
        }
        assertEquals(names.toString(), names.size, names.toSet().size)

        // And specifically the pair that would otherwise collide: an ATTACHED png and a SAVED png.
        val attachedPng = dwTraceExportFileName("sheet.jpg", "png", DW_TRACE_ATTACH_SUFFIX)
        val savedPng = dwTraceExportFileName(
            "sheet.jpg",
            "png",
            dwTraceSaveSuffix(dwTraceExportFormat("png")!!),
        )
        assertEquals("sheet-line-art.png", attachedPng)
        assertEquals("sheet-traced.png", savedPng)
    }

    /**
     * The saved SVG carries the SAME word as the attach, deliberately.
     *
     * It is byte-for-byte the file the record holds, and a different name on the designer's phone
     * would invite the belief that it is a different drawing. The other three vector forms share the
     * word for the same reason and are told apart by their extension.
     */
    @Test
    fun `every vector form shares the attach word and the raster does not`() {
        DW_TRACE_EXPORT_FORMATS.filter { it.isVector }.forEach { format ->
            assertEquals(format.id, DW_TRACE_ATTACH_SUFFIX, dwTraceSaveSuffix(format))
        }
        DW_TRACE_EXPORT_FORMATS.filterNot { it.isVector }.forEach { format ->
            assertEquals(format.id, DW_TRACE_RENDER_SUFFIX, dwTraceSaveSuffix(format))
        }
    }

    /* ────────────────────────────────────────────────────────────────────────
     * 3. Sizes and the background
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * The raster cap never upscales, bites only above the ceiling, and does not divide by zero.
     *
     * The degenerate case is not hypothetical: `svgWriter.sanitizeDimension` exists because a
     * non-positive canvas dimension reaches these writers, and an export that threw there would be a
     * crash in the middle of an unsaved stage.
     */
    @Test
    fun `the PNG scale is a ceiling and never an enlargement`() {
        assertEquals(1.0, dwTracePngScale(800, 600), 0.0)
        assertEquals(1.0, dwTracePngScale(2048, 2048), 0.0)
        assertEquals(0.5, dwTracePngScale(4096, 3072), 1e-9)
        assertEquals(1.0, dwTracePngScale(0, 0), 0.0)
        assertEquals(1.0, dwTracePngScale(-10, -10), 0.0)
        // Long edge, not width: a portrait sheet is capped on its height.
        assertEquals(dwTracePngScale(4096, 100), dwTracePngScale(100, 4096), 1e-12)
    }

    /**
     * The export passes the document's own background through and never substitutes.
     *
     * THIS IS THE ASSERTION THAT KEEPS THE FIVE FORMATS AGREEING, and it looks like a tautology until
     * you read what the alternative does. `ExportOptions.background = null` means "leave the document
     * alone" to `prepareVectorDoc` and "transparent" to `raster.render`; those coincide only when the
     * document is itself transparent, so an export that "helpfully" passed null over a white document
     * would write a white PDF beside a transparent PNG of one drawing. An identity function with a
     * name is what makes that decision visible at the call site — see [dwTraceExportBackground].
     *
     * `0xFFFFFFFF` narrowed to an `Int` is -1, which is what an engine reading packed ARGB expects and
     * what a reader unfamiliar with the narrowing will assume is a bug. Pinned so that "fixing" it to
     * `0xFFFFFF` fails here rather than in a print shop.
     */
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

    /**
     * The background is read off `appliedParams`, and the narrowing is the conversion.
     *
     * The leaf is a `Double` because the parameter tree carries numbers; `4294967295.0` narrowed
     * through `Long` to `Int` is `-1`, which is the packed ARGB opaque white the engine and `Paint`
     * both want. `DwTraceStyle`'s KDoc states the same rule for stroke and fill: "The narrowing IS the
     * conversion, not a loss of information." An absent leaf is transparent, and that is the engine's
     * only spelling of it (`engine/params.ts:359`).
     */
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

        // A leaf the engine never sent reads the same as an explicit transparent, which is correct
        // here and would not be for a control's own state — see DwTraceValue.Absent's own KDoc.
        assertNull(dwTraceDocumentBackground(DwTraceValues(emptyMap(), "{}")))

        // Not white-only: a document written on any other ground narrows the same way. 0xff000000.
        val black = DwTraceValues(
            mapOf("output.background" to DwTraceValue.Num(4278190080.0)),
            "{}",
        )
        assertEquals(-16777216, dwTraceDocumentBackground(black))
    }

    /**
     * There is exactly ONE background value in this feature, and it is a leaf of the engine's tree.
     *
     * `DwSketchTraceParams.kt` relocates the CONTROL to `DwTraceTier.EXPORT` and keeps the VALUE in
     * `output.background`, spelled `DW_TRACE_OPAQUE_WHITE` for white and absent for transparent. This
     * asserts that the two spellings agree — that the number the params table writes into the tree is
     * the number this side reads back as white — so a second, drifting notion of "white" cannot appear
     * on the export step. Two paths to one value is the shape `traceParamTable.ts` refuses for
     * `matte.mode = SUBJECT`: "two paths to one value means the two can disagree about which decided".
     */
    @Test
    fun `white is spelled the same on both sides of the parameter tree`() {
        assertEquals(0xFFFFFFFFL.toInt(), DW_TRACE_OPAQUE_WHITE.toLong().toInt())
        assertTrue(dwTraceBackgroundIsWhite(DW_TRACE_OPAQUE_WHITE.toLong().toInt()))
    }

    /* ────────────────────────────────────────────────────────────────────────
     * 4. Routing
     * ──────────────────────────────────────────────────────────────────────── */

    private val svg = dwTraceExportFormat("svg")!!
    private val pdf = dwTraceExportFormat("pdf")!!
    private val png = dwTraceExportFormat("png")!!

    /** Opaque white as packed ARGB, and transparent. The only two values `output.background` takes. */
    private val WHITE: Int = 0xFFFFFFFFL.toInt()
    private val BACKGROUNDS: List<Int?> = listOf(WHITE, null)

    /**
     * **PROPERTY 3.** The SVG comes from the engine's own string; the three vector take-aways need a
     * writer; the PNG needs neither.
     *
     * The SVG shortcut is a proof, not a convenience — `DwSketchTraceExporter.kt`'s header sets it
     * out: `exportSvg` reconstitutes `DEFAULT_SVG_OPTIONS` from `ExportOptions`' defaults,
     * `prepareVectorDoc` at scale 1 either returns the document or rebuilds it with the background it
     * already had, and `traceParityRun.ts:103` writes the parity string with those same defaults. So
     * the string in hand IS `exportSvg`'s answer, for either background.
     *
     * The proof depends on the export never choosing a background of its own, which is why
     * [dwTraceExportBackground] is a named pass-through and why the test above pins it.
     *
     * THE PNG'S ROUTE IS A DIFFERENT KIND OF CLAIM and is the web's own arrangement rather than a
     * handset shortcut: `EXPORT_FORMATS`' PNG row records that the portal writes its picture with
     * `canvas.toBlob` rather than with the engine's `pngEncoder`, because "the platform layer owns
     * the pixel formats the browser already has an encoder for". `Bitmap.compress` is this platform's
     * half of that sentence.
     */
    @Test
    fun `each format takes the route its bytes actually come from`() {
        assertEquals(DwTraceExportPlan.FromTraceSvg, dwTraceExportPlan(svg, null))
        assertEquals(DwTraceExportPlan.FromPlatformRaster, dwTraceExportPlan(png, null))
        listOf("pdf", "dxf", "eps").forEach { id ->
            val format = dwTraceExportFormat(id)!!
            assertEquals(id, DwTraceExportPlan.FromExporter, dwTraceExportPlan(format, null))
        }
    }

    /**
     * A host with no writers behind it refuses THREE and still writes the drawing and the picture.
     *
     * **NO LONGER THE SHIPPING STATE, AND STILL THE PROPERTY WORTH PINNING.** It was the shipping
     * state while the engine was a JavaScript bundle whose host surface carried no writer, for a
     * measured 56,839 bytes of bundle; `rememberDwTraceExporter` now mounts `DwTraceKotlinExporter`
     * and `:core-export` writes all five. What this holds is the routing rule that made that port a
     * one-line change: two of the five formats never ask an exporter anything, so an exporter's
     * refusal can never cost a designer the vector line work.
     *
     * The web reached the same promise independently — `traceExport.WRITER_UNAVAILABLE`: "The SVG
     * download needs nothing extra and works either way."
     */
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

    /**
     * A trace with no shapes is a DIFFERENT refusal from a bundle with no writers, and says so.
     *
     * The card used to fold the two together, deliberately: they were "different facts inside the app
     * and the SAME fact to the designer — the other four formats cannot be written here, and the SVG
     * can". That stopped being true when the picture left through the platform's own encoder. A phone
     * with no writers now saves two formats; a trace with no geometry saves one. Different sets of
     * working controls are different sentences, which is `DwSketchChooserSentenceTest`'s rule read the
     * other way round — and this asserts that the sentences really are different, not just the states.
     */
    @Test
    fun `no geometry and no writers are two refusals and not one`() {
        // The SVG survives both, which is what both sentences promise.
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
        // Missing shapes are reported as missing shapes even on a build that also has no writers:
        // the remedy differs — trace again, against wait for an update — so the closer cause wins.
        val both = dwTraceExportPlan(pdf, DW_TRACE_NO_EXPORTER_SENTENCE, hasGeometry = false)
        assertEquals(
            DW_TRACE_NO_GEOMETRY_SENTENCE,
            (both as DwTraceExportPlan.Refused).reason,
        )
        assertNotEquals(DW_TRACE_NO_GEOMETRY_SENTENCE, DW_TRACE_NO_EXPORTER_SENTENCE)
    }

    /**
     * The unavailable exporter answers a refusal rather than throwing, and defaults to the right one.
     *
     * A press that reaches this is a designer who chose PDF on a host with no writers behind it,
     * which is a sentence and not a crash. Nothing in the app mounts this exporter any more; the
     * second assertion is what keeps the constructor honest for a host that does.
     */
    @Test
    fun `the unavailable exporter refuses in a sentence`() {
        assertEquals(DW_TRACE_NO_EXPORTER_SENTENCE, DwTraceExporterUnavailable().refusal)
        assertEquals("a WebView from 2019.", DwTraceExporterUnavailable("a WebView from 2019.").refusal)
    }

    /* ────────────────────────────────────────────────────────────────────────
     * 5. What is said beside the file
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * **PROPERTY 4.** Every format, on every background, states what the report does with this file —
     * first, and in the one constant.
     *
     * Unconditional because the ambiguity it closes is unconditional: a designer holding a drawing
     * cannot tell from any surface whether an officer will see it. And ONE constant because
     * `ReportBuilder.attachments_named_but_not_carried` records that this claim cannot be verified
     * from any client, and counts five passes over one wrong version of it in a single day.
     */
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

    /**
     * The report sentence does not promise the file reaches the officer, and does not tell the
     * designer to stop.
     *
     * The property, not the prose: it must contain a denial and a remedy. A sentence that only denied
     * would read as "this was pointless", which is false — sending the drawing on is the whole point of
     * the four take-away formats, and it is what
     * `ReportBuilder.attachments_named_but_not_carried` settles on ("the person who has to act is the
     * designer, on the day, whose action is to send those files with it").
     */
    @Test
    fun `the report sentence denies and then names the remedy`() {
        val sentence = DW_TRACE_EXPORT_REPORT_SENTENCE
        assertTrue(sentence.contains("does not go into the workshop report"))
        assertTrue(sentence.contains("send it to them"))
    }

    /**
     * The formats with no provenance channel say so, and the ones that carry the engine's name say so.
     *
     * `writeDxf` takes no metadata argument at all and `pngEncoder` writes no text chunk, so a saved
     * `.dxf` or `.png` records nothing about the photograph it came from. That is skipped work, and
     * skipped work is stated beside the file rather than quietly tolerated.
     */
    @Test
    fun `the losses name each format's own gap`() {
        // ALL FIVE HAVE NOWHERE TO PUT THE PROVENANCE NOTE, and it was three until the handset's
        // engine changed. `:core-export`'s `ExportOptions` has no title field, so `PdfWriter` and
        // `EpsWriter` can only write their own hard-coded strings — which `DwTraceKotlinExporter`
        // switches off as another product's branding. Getting this set wrong is how a designer is
        // told a file says something it does not, which is what the old set did for the PDF.
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, WHITE)
            assertTrue(format.id, lines.contains(DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE))
        }

        // ONE FORMAT STILL CARRIES "Offline Tracer" AND FOUR DO NOT. `EpsWriter.kt:143` writes
        // `%%Creator` outside the `includeMetadata` guard and no option removes it;
        // `DwTraceKotlinExporterTest` reads the actual bytes of all three vector files and holds that
        // claim to them. Trap 2 in DwSketchTraceExport.kt's header is the argument for saying it.
        val branded = setOf("eps")
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, WHITE)
            val saysSo = lines.contains(DW_TRACE_EXPORT_ENGINE_NAME_SENTENCE)
            assertEquals(format.id, format.id in branded, saysSo)
        }

        // The DXF's own row, which is the one nobody guesses: it is R12 and curves are flattened.
        val dxfLines = dwTraceExportLosses(dwTraceExportFormat("dxf")!!, WHITE)
        assertTrue(dxfLines.any { it.contains("DXF R12") && it.contains("short straight lines") })
    }

    /**
     * The PNG's cap is stated when it bites and when nothing is known, and dropped when it does not.
     *
     * The safe direction is stated in [dwTraceExportLosses]: a cap named when it did not apply costs a
     * reader one sentence, and a cap that applied and was not named costs them a drawing softer than
     * the one they approved. So an unknown document size is treated as "it bites".
     */
    @Test
    fun `the raster cap is stated exactly when it can bite`() {
        val big = dwTraceExportLosses(png, WHITE, 4096)
        assertTrue(big.any { it.contains(DW_TRACE_PNG_MAX_EDGE_PX.toString()) })

        val unknown = dwTraceExportLosses(png, WHITE, 0)
        assertTrue(unknown.any { it.contains(DW_TRACE_PNG_MAX_EDGE_PX.toString()) })

        val small = dwTraceExportLosses(png, WHITE, 900)
        assertFalse(small.any { it.contains(DW_TRACE_PNG_MAX_EDGE_PX.toString()) })
    }

    /**
     * The transparency warning is drawn for every format that can BE transparent, and only for those.
     *
     * That is four of the five: the DXF carries no background at all, so warning about one there would
     * be a warning about a property the file does not have — and a block that warns about things that
     * cannot happen is a block a reader learns to skip, which costs the sentence beside it that
     * matters. The PNG IS warned, because a transparent PNG dropped into a letter shows the page
     * through it exactly as a transparent SVG does in a PDF.
     */
    @Test
    fun `the transparency warning is drawn for every format that has a background`() {
        val warning = "Transparent means the page shows through"
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, null)
            val warned = lines.any { it.startsWith(warning) }
            assertEquals(format.id, format.id != "dxf", warned)
        }
        // And never over a white document, for any format.
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, WHITE)
            assertFalse(format.id, lines.any { it.startsWith(warning) })
        }
    }

    /**
     * The twelve sentences this feature ships are twelve DIFFERENT sentences.
     *
     * `DwSketchChooserSentenceTest` pins the property from the other side, on the defect that made it
     * matter: one sentence reused for two states means the branch order stops mattering and the wrong
     * state can be reported through any of them. These twelve are twelve different facts — what the
     * report carries about a SAVED copy and what it carries about an ATTACHED one, whose engine wrote
     * the file, that this build's bundle cannot write three of the formats, that a trace came back
     * with no shapes, that there is no Uri to share, what the share sheet cannot tell us, that the
     * trace on screen is a preview, what changing the background costs, that three of the formats
     * cannot say what they were traced from, that those same three cannot record a crop, and that a
     * picture would not fit in memory — and only one of them may be shown for each.
     *
     * THE TWO REPORT SENTENCES ARE THE PAIR WORTH LOOKING AT TWICE, because they are the one place
     * this list comes closest to breaking its own rule. They are not two phrasings of one claim: one
     * describes a file on the designer's phone that reaches no field, the other a file in the shared
     * archive that an officer can open but that the printed document does not carry. Saying either of
     * them in the other's place would be false. `DW_TRACE_ATTACH_REPORT_SENTENCE`'s own KDoc argues it.
     *
     * Reads the two constants that live in `DwSketchTraceExportFile.kt` beside the rest. They are
     * `const val`, so they inline at compile time and no Android class is loaded to read them.
     */
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

    /**
     * The attach sentence carries the web's claim about the report and no longer the opposite of it.
     *
     * **THIS IS A REGRESSION TEST FOR A SHIPPED FALSE STATEMENT.** The panel used to tell a designer
     * the line art was "vector line work a report can print at any size", twice, on the only surface
     * they can reach — while the correcting sentence sat in a card nothing mounted. The property
     * asserted is the one that matters and not the prose: the sentence must DENY that the report
     * carries the file, and it must say where the file actually is, because a denial without a
     * destination reads as "attaching was pointless" and attaching is the whole point.
     */
    @Test
    fun `the attach sentence says what the report does and does not do with the file`() {
        val sentence = DW_TRACE_ATTACH_REPORT_SENTENCE
        assertTrue(sentence.contains("does not carry it"))
        assertTrue(sentence.contains("workshop record"))
        // And it must not make the claim it replaced. "print at any size" is fine — that is a fact
        // about vectors; "a report can print at any size" is the promise that was false.
        assertFalse(sentence.contains("a report can print"))
    }

    /* ────────────────────────────────────────────────────────────────────────
     * 7. The picture — the one format this device writes itself
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * The output size is `exportPngFile`'s three lines, transcribed.
     *
     *     const scale  = Math.min(1, maxEdge / Math.max(sourceWidth, sourceHeight));
     *     const width  = Math.max(1, Math.round(sourceWidth  * scale));
     *     const height = Math.max(1, Math.round(sourceHeight * scale));
     *
     * THIS IS THE ONE THING THE TWO CLIENTS MUST AGREE ON ABOUT THE PICTURE. Everything else about a
     * PNG is the platform's — `canvas.toBlob` there, `Bitmap.compress` here — but a drawing saved on
     * a laptop and on a handset that came out different numbers of pixels across would be two
     * products, and a print shop would be the one to find out.
     */
    @Test
    fun `the PNG size is the web's arithmetic, edge for edge`() {
        // Under the cap: untouched, both edges.
        assertEquals(DwTracePngSize(1600, 1200, reduced = false), dwTracePngSize(1600, 1200))
        // Exactly on it: untouched, and NOT reported as a reduction.
        assertEquals(
            DwTracePngSize(DW_TRACE_PNG_MAX_EDGE_PX, 1024, reduced = false),
            dwTracePngSize(DW_TRACE_PNG_MAX_EDGE_PX, 1024),
        )
        // Over it: the long edge lands exactly on the cap and the aspect is kept.
        assertEquals(DwTracePngSize(2048, 1536, reduced = true), dwTracePngSize(4096, 3072))
        // Portrait is capped on its height, which is the whole reason the rule reads "long edge".
        assertEquals(DwTracePngSize(1536, 2048, reduced = true), dwTracePngSize(3072, 4096))

        // ROUNDING, NOT TRUNCATION. 3000 x 1001 scales by 2048/3000; the short edge is 683.35…,
        // which rounds to 683 and truncates to 683 — so the case that actually separates the two is
        // one that lands above .5: 3000 x 1005 gives 686.08 (both 686), and 3000 x 1002 gives 684.03.
        // The value pinned here is one where truncation would differ: 3000 x 1003 -> 684.72 -> 685.
        assertEquals(685, dwTracePngSize(3000, 1003).height)

        // THE FLOOR OF ONE PIXEL IS NOT DECORATION: `Bitmap.createBitmap` throws on a zero edge. A
        // short edge rounds to zero once it is under one part in 4,096 of the long one — 20480 x 4
        // scales by 0.1 and lands the short edge on 0.4 — and without the floor that is an
        // IllegalArgumentException inside a save, on a stage with unsaved work. The web's
        // `Math.max(1, …)` guards the identical thing one runtime over.
        assertEquals(1, dwTracePngSize(20480, 4).height)
        assertEquals(DW_TRACE_PNG_MAX_EDGE_PX, dwTracePngSize(20480, 4).width)
        // And a degenerate document does not divide by zero on the way there.
        assertEquals(DwTracePngSize(1, 1, reduced = false), dwTracePngSize(0, 0))
        assertEquals(DwTracePngSize(1, 1, reduced = false), dwTracePngSize(-10, -10))
    }

    /**
     * The reduction is reported after the fact, with the two real sizes, and only when it happened.
     *
     * The cap is stated twice on purpose and the two statements are different tenses of it.
     * [dwTraceExportLosses] warns in the future tense beside the chips, for a designer choosing a
     * format; this is the past tense, beside a file that now exists in their Downloads folder, and it
     * is the only place the actual numbers appear. A reduction that happened and was not named costs
     * a designer a drawing softer than the one they approved, discovered at a desk four days later.
     */
    @Test
    fun `the reduction sentence appears exactly when the cap bit and carries both sizes`() {
        val reduced = dwTracePngSize(4096, 3072)
        val note = dwTracePngReductionNote(4096, 3072, reduced)
        assertTrue(note, note.contains("2048x1536"))
        assertTrue(note, note.contains("4096x3072"))
        // It points at the format that has no such limit, which is the remedy and is one chip away.
        assertTrue(note, note.contains("SVG"))

        // Nothing at all when the drawing fitted, rather than a sentence saying it was not reduced.
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

    /**
     * The PNG hint quotes the cap with no space, exactly as the web's row interpolates it.
     *
     * A ONE-CHARACTER DIVERGENCE THAT IS REPORTED BECAUSE OF WHAT THE FILE CLAIMS ABOUT ITSELF:
     * `DW_TRACE_EXPORT_FORMATS`' own KDoc says the hints are "carried across rather than re-written"
     * and "verbatim from the web's table", and a lane whose rule is that one format is described one
     * way on both clients cannot spell one number two ways. Kotlin's `$CONST px` put the space in;
     * `${CONST}px` takes it out.
     */
    @Test
    fun `the PNG hint spells the cap the way the web spells it`() {
        val hint = dwTraceExportFormat("png")!!.hint
        assertTrue(hint, hint.contains("${DW_TRACE_PNG_MAX_EDGE_PX}px on its long edge"))
        assertFalse(hint, hint.contains("$DW_TRACE_PNG_MAX_EDGE_PX px"))
    }

    /**
     * A crop the file cannot record is stated beside the file, for the three formats that cannot.
     *
     * **THE SET IS NOT THE WEB'S AND THAT IS NOT A DIVERGENCE.** The portal warns for the PNG and the
     * DXF, because its own `geometryToSvg.buildSvg` writes the note into the SVG as an XML comment.
     * The handset writes all five with vendored writers that take no title, so on this client every
     * format is silent — which is exactly the set that gets [DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE].
     * Asserted as that coupling rather than as a literal list, so the two cannot drift apart when a
     * sixth format arrives.
     */
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
        // All five, spelled out once so a reader of this test knows which they are without running it.
        assertEquals(
            listOf("svg", "png", "pdf", "dxf", "eps"),
            DW_TRACE_EXPORT_FORMATS
                .filter { DW_TRACE_EXPORT_NO_FRAME_SENTENCE in dwTraceExportLosses(it, WHITE, 0, cropped) }
                .map { it.id },
        )
        // And NOTHING is said when the whole sheet was traced, in any format. A warning about a crop
        // nobody made teaches a reader that this block describes situations they are not in.
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

    /**
     * The report sentence stays first even when every other line is present.
     *
     * PROPERTY 4 AGAIN, UNDER LOAD. The earlier test walks the two backgrounds; this one is the
     * worst case that now exists — a transparent, cropped document over the cap — which is where a
     * new line appended in the wrong place would push the one that matters down the list. The block
     * is read top to bottom by somebody about to press a button.
     */
    @Test
    fun `the report sentence is still first with every other loss in the list`() {
        val cropped = "Cropped on the device to 900x1200 at (30, 40) of 3000x4000."
        DW_TRACE_EXPORT_FORMATS.forEach { format ->
            val lines = dwTraceExportLosses(format, null, 4096, cropped)
            assertEquals(format.id, DW_TRACE_EXPORT_REPORT_SENTENCE, lines.first())
            assertEquals(format.id, lines.size, lines.toSet().size)
        }
    }

    /**
     * The two refusals a designer can meet name a way forward, rather than only a wall.
     *
     * The rule every refusal in this feature is held to: a dead button teaches a designer the feature
     * is broken, and a sentence teaches them what still works. Both of these are the shape that
     * argument asks for — a denial and then something to do — and the assertion is on the remedy being
     * present, not on its wording.
     */
    @Test
    fun `both refusals name a remedy`() {
        // No exporter: the SVG still works, and the portal can write the rest.
        assertTrue(DW_TRACE_NO_EXPORTER_SENTENCE.contains("SVG"))
        assertTrue(DW_TRACE_NO_EXPORTER_SENTENCE.contains("portal"))
        // A preview: trace it again at full size.
        assertTrue(DW_TRACE_EXPORT_PREVIEW_SENTENCE.contains("full size"))
    }

    /* ────────────────────────────────────────────────────────────────────────
     * 6. Provenance
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * The provenance sentence names the photograph and the counts, and nothing about a person.
     *
     * `geometryToSvg.buildSvg`'s header sets the limit and the reason: the file is uploaded to a shared
     * archive and handed on, and a comment naming the person who traced it would be a disclosure
     * nobody asked for. Asserted as a shape — leaf name, two counts — rather than as a string, so a
     * copy edit does not turn this red while a leaked field would.
     */
    @Test
    fun `the provenance note names the photograph and the counts`() {
        val note = dwTraceProvenanceNote("/storage/emulated/0/DCIM/sheet.jpg", 193, 812)
        assertTrue(note.contains("sheet.jpg"))
        assertFalse("the path must not travel into the file", note.contains("/storage/"))
        assertTrue(note.contains("193 paths"))
        assertTrue(note.contains("812 nodes"))
    }

    /** A blank source name still produces a sentence rather than a dangling "from ." */
    @Test
    fun `the provenance note survives a nameless photograph`() {
        val note = dwTraceProvenanceNote("", 1, 2)
        assertTrue(note.contains("a photograph"))
        assertFalse(note.contains("from  "))
    }

    /**
     * The frame sentence is appended when there is one and adds nothing when there is not.
     *
     * The handset has no frame panel today — `FramePanel` and `lib/trace/imageEdit.ts` are web-only —
     * so the parameter exists to give whoever ports it one place to hand its sentence to, rather than
     * a second sentence to invent. This holds that seam open.
     */
    @Test
    fun `the frame sentence is appended only when there is one`() {
        val plain = dwTraceProvenanceNote("sheet.jpg", 1, 2)
        val framed = dwTraceProvenanceNote("sheet.jpg", 1, 2, "Cropped to the sheet, sharpened 0.8.")
        assertTrue(framed.startsWith(plain))
        assertTrue(framed.endsWith("Cropped to the sheet, sharpened 0.8."))
        assertEquals(plain, dwTraceProvenanceNote("sheet.jpg", 1, 2, "   "))
    }
}
