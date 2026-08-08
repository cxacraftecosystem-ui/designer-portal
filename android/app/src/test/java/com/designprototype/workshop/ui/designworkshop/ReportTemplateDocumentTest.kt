package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.EnumOption
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.ChartBlock
import com.designprototype.workshop.report.CoverBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ImageBlock
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.MapBlock
import com.designprototype.workshop.report.MapPointKind
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.SpecialSection
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TemplateSection
import com.designprototype.workshop.report.TocBlock
import com.designprototype.workshop.report.UNSUPPORTED_SECTIONS
import com.designprototype.workshop.report.reportMetaFor
import com.designprototype.workshop.report.ReportRecord
import com.designprototype.workshop.report.ReportTheme
import com.designprototype.workshop.report.reportTemplate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the chosen template and the stage-20 switches actually do to the file the handset writes.
 *
 * WHAT THIS PINS. Before this, `buildWorkshopDocument` walked `schema.stages.sortedBy { it.number }`
 * and emitted a table of contents. That meant: all six templates produced the same document; the
 * sections ran in CAPTURE order rather than the reader's; stage 20 and stage 21 printed, so every
 * report ended with the designer's own template choice, accent hex and the export log's SHA-256
 * checksums, submitted to a ministry; there was no cover page at all; and seventeen switches the
 * form saved were inert.
 *
 * The assertions below are about CONTENT and ORDER — what a reader would notice — rather than about
 * block counts, so a legitimate template change on the server (which regenerates the pin in
 * `ReportTemplatePinTest`) does not break them for its own sake.
 */
class ReportTemplateDocumentTest {

    // ── the registry, cut down to real stage keys ────────────────────────────────────────────────

    private fun text(key: String, label: String, role: String = "KEY_VALUE", tier: String = "BASIC") =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = role, tier = tier)

    private fun singletonStage(number: Int, key: String, title: String, vararg fields: FieldDto) =
        StageDto(
            number = number, key = key, title = title,
            entities = listOf(
                EntityDto(
                    key = "${key.lowercase()}Entity", cardinality = "SINGLETON", title = title,
                    fields = fields.toList(),
                )
            ),
        )

    private fun draftOf(vararg stages: Pair<String, Map<String, JsonElement>>) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = stages.associate { (key, values) ->
            key to StageDraft(stageId = key, values = values)
        },
    )

    private fun build(
        schema: SchemaResponse,
        draft: WorkshopDraft?,
        templateId: String = "DCH_STANDARD",
        format: String = "DOCX",
        imageFor: (String) -> ImageRef? = { null },
    ): ReportDocument = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = draft?.workshopId ?: "local-test",
        templateId = templateId,
        warnings = emptyList(),
        accent = "",
        imageFor = imageFor,
        format = format,
        generatedAt = "2026-03-04T09:30:00Z",
    )

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun headings(document: ReportDocument): List<String> =
        document.blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs) }

    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        is CoverBlock -> (listOf(block.title, block.subtitle) + block.orgLines +
            block.infoRows.map { "${it.first}: ${it.second}" } + block.footerLines).joinToString("\n")
        else -> ""
    }

    // ── the reader's order ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the sections run in the template's order, not in stage-number order`() {
        val schema = SchemaResponse(
            version = "test",
            stages = listOf(
                singletonStage(1, "WORKSHOP_SETUP", "Workshop setup", text("venue", "Venue")),
                singletonStage(3, "WORKSHOP_PLAN_PARTICIPANTS_OPENING", "Plan and participants", text("plan", "Plan")),
                singletonStage(16, "FINAL_PROTOTYPE_DOCUMENTATION", "Final products", text("what", "What")),
                singletonStage(18, "WORKSHOP_OUTCOMES", "Workshop outcomes", text("result", "Result")),
            ),
        )
        val draft = draftOf(
            "WORKSHOP_SETUP" to mapOf("venue" to JsonPrimitive("Barpali weavers' hall")),
            "WORKSHOP_PLAN_PARTICIPANTS_OPENING" to mapOf("plan" to JsonPrimitive("Thirty artisans")),
            "FINAL_PROTOTYPE_DOCUMENTATION" to mapOf("what" to JsonPrimitive("Four table runners")),
            "WORKSHOP_OUTCOMES" to mapOf("result" to JsonPrimitive("Two buyers linked")),
        )

        // The agency format leads with the outcomes and demotes the roster to "Annexure A", which is
        // the whole reason it is a separate format. Sorted by stage number it would lead with the
        // workshop setup — which this template does not carry at all.
        val document = build(schema, draft, templateId = "IMPLEMENTING_AGENCY")
        val order = headings(document).filter { it.isNotBlank() }

        assertTrue(
            "the outcomes must come before the final products: $order",
            order.indexOfFirst { it.contains("Workshop outcomes") } <
                order.indexOfFirst { it.contains("Final products") },
        )
        assertTrue(
            "the roster prints under the template's own heading, not the stage's title: $order",
            order.any { it.contains("Annexure A — Participants") },
        )
        assertFalse(
            "this template carries no workshop-setup section, so the stage must not print: $order",
            order.any { it.contains("Workshop setup") },
        )
    }

    @Test
    fun `stage 20 and stage 21 never print`() {
        val schema = SchemaResponse(
            version = "test",
            stages = listOf(
                singletonStage(18, "WORKSHOP_OUTCOMES", "Workshop outcomes", text("result", "Result")),
                singletonStage(20, "REPORT_GENERATION", "Report Generation & Submission", text("templateId", "Report template")),
                singletonStage(21, "DATA_QUALITY_ARCHIVE", "Data Quality & Archive", text("note", "Note")),
            ),
        )
        val draft = draftOf(
            "WORKSHOP_OUTCOMES" to mapOf("result" to JsonPrimitive("Two buyers linked")),
            "REPORT_GENERATION" to mapOf("templateId" to JsonPrimitive("DCH_STANDARD")),
            "DATA_QUALITY_ARCHIVE" to mapOf("note" to JsonPrimitive("Archived to the district office")),
        )

        val printed = printedText(build(schema, draft))
        assertFalse(
            "the report was describing its own generation:\n$printed",
            printed.contains("Report Generation & Submission"),
        )
        assertFalse(printed.contains("Data Quality & Archive"))
        assertFalse("the export log must not reach the file", printed.contains("Archived to the district office"))
    }

    // ── the cover ────────────────────────────────────────────────────────────────────────────────

    private fun coverSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            singletonStage(
                1, "WORKSHOP_SETUP", "Workshop setup",
                text("craftName", "Craft", role = "COVER_FIELD"),
                text("clusterName", "Cluster", role = "COVER_FIELD"),
                text("state", "State", role = "COVER_FIELD"),
                text("implementingAgency", "Implementing agency", role = "COVER_FIELD"),
            ),
            singletonStage(20, "REPORT_GENERATION", "Report generation", text("reportTitle", "Report title")),
        ),
    )

    @Test
    fun `the cover carries the title, the organisation and the submission line stage 20 was given`() {
        val draft = draftOf(
            "WORKSHOP_SETUP" to mapOf(
                "craftName" to JsonPrimitive("Sambalpuri Ikat"),
                "clusterName" to JsonPrimitive("Barpali"),
                "state" to JsonPrimitive("Odisha"),
                "implementingAgency" to JsonPrimitive("Sambalpuri Bastralaya"),
            ),
            "REPORT_GENERATION" to mapOf(
                "reportTitle" to JsonPrimitive("Design & Prototype Development Workshop"),
                "organisationLine" to JsonPrimitive("National Institute of Design"),
                "letterheadText" to JsonPrimitive("Paldi\nAhmedabad 380 007"),
                "submittedTo" to JsonPrimitive("Development Commissioner (Handicrafts)"),
                "submissionDate" to JsonPrimitive("2026-03-04"),
            ),
        )

        val document = build(coverSchema(), draft)
        val cover = document.blocks.firstOrNull() as? CoverBlock
        assertNotNull(
            "the report opened on something other than a cover page; blocks were " +
                "${document.blocks.map { it::class.simpleName }}",
            cover,
        )
        cover!!
        assertEquals("Design & Prototype Development Workshop", cover.title)
        assertEquals("Sambalpuri Ikat — Barpali, Odisha", cover.subtitle)
        assertTrue(
            "the designer's own organisation line must win over the template's ministry: ${cover.orgLines}",
            cover.orgLines.contains("National Institute of Design"),
        )
        assertTrue("the letterhead prints one line each: ${cover.orgLines}", cover.orgLines.contains("Paldi"))
        assertTrue(
            "the one page whose job is to say who the report is for said nothing about it: " +
                "${cover.footerLines}",
            cover.footerLines.any {
                it == "Submitted to Development Commissioner (Handicrafts) on 04 Mar 2026"
            },
        )
        assertTrue(
            "the cover table takes the stage-1 COVER_FIELDs: ${cover.infoRows}",
            cover.infoRows.any { it.first == "Craft" && it.second == "Sambalpuri Ikat" },
        )
    }

    @Test
    fun `the running header and foot come from stage 20 when it names them`() {
        val draft = draftOf(
            "WORKSHOP_SETUP" to mapOf("craftName" to JsonPrimitive("Dhokra")),
            "REPORT_GENERATION" to mapOf(
                "headerText" to JsonPrimitive("Bastar cluster — draft"),
                "footerText" to JsonPrimitive("Not for circulation"),
                "pageSize" to JsonPrimitive("LETTER"),
            ),
        )
        val meta = build(coverSchema(), draft).meta
        assertEquals("Bastar cluster — draft", meta.headerText)
        assertEquals("Not for circulation", meta.footerText)
        assertEquals("LETTER", meta.pageSize.name)
    }

    // ── the switches ─────────────────────────────────────────────────────────────────────────────

    private fun outcomesSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            singletonStage(18, "WORKSHOP_OUTCOMES", "Workshop outcomes", text("result", "Result")),
            singletonStage(20, "REPORT_GENERATION", "Report generation", text("reportTitle", "Report title")),
        ),
    )

    private fun outcomesDraft(settings: Map<String, JsonElement>) = draftOf(
        "WORKSHOP_OUTCOMES" to mapOf("result" to JsonPrimitive("Two buyers linked")),
        "REPORT_GENERATION" to settings,
    )

    @Test
    fun `turning the table of contents off removes it`() {
        val on = build(outcomesSchema(), outcomesDraft(emptyMap()))
        assertTrue(on.blocks.any { it is TocBlock })

        val off = build(
            outcomesSchema(),
            outcomesDraft(mapOf("includeTableOfContents" to JsonPrimitive(false))),
        )
        assertFalse(
            "a designer who turned the contents page off got one anyway, with no way to tell the " +
                "switch was inert",
            off.blocks.any { it is TocBlock },
        )
    }

    @Test
    fun `turning the heading numbers off unnumbers every heading`() {
        val numbered = build(outcomesSchema(), outcomesDraft(emptyMap()))
        assertTrue(
            "the DCH template numbers its headings",
            numbered.blocks.filterIsInstance<HeadingBlock>().any { it.number.isNotEmpty() },
        )

        val plain = build(
            outcomesSchema(),
            outcomesDraft(mapOf("numberHeadings" to JsonPrimitive(false))),
        )
        assertTrue(
            "every heading must lose its number: " +
                plain.blocks.filterIsInstance<HeadingBlock>().map { it.number },
            plain.blocks.filterIsInstance<HeadingBlock>().all { it.number.isEmpty() },
        )
    }

    @Test
    fun `a stage named in excludedStages does not print`() {
        val document = build(
            outcomesSchema(),
            outcomesDraft(
                mapOf(
                    "excludedStages" to buildJsonArray {
                        add(JsonPrimitive("WORKSHOP_OUTCOMES"))
                        add(JsonPrimitive("NOT_A_STAGE"))
                    }
                )
            ),
        )
        assertFalse(
            "the excluded stage printed:\n${printedText(document)}",
            printedText(document).contains("Two buyers linked"),
        )
    }

    // ── photographs ──────────────────────────────────────────────────────────────────────────────

    private fun photoSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 16, key = "FINAL_PROTOTYPE_DOCUMENTATION", title = "Final products",
                entities = listOf(
                    EntityDto(
                        key = "finalProduct", cardinality = "SINGLETON", title = "Final product",
                        fields = listOf(
                            text("name", "Name"),
                            FieldDto(
                                key = "photos", label = "Photographs", type = "IMAGE_LIST",
                                reportRole = "GALLERY", tier = "BASIC",
                            ),
                        ),
                    )
                ),
            ),
            singletonStage(20, "REPORT_GENERATION", "Report generation", text("reportTitle", "Report title")),
        ),
    )

    private fun photoDraft(settings: Map<String, JsonElement>) = draftOf(
        "FINAL_PROTOTYPE_DOCUMENTATION" to mapOf(
            "name" to JsonPrimitive("Bandha table runner"),
            "photos" to buildJsonArray {
                add(JsonPrimitive("m1")); add(JsonPrimitive("m2")); add(JsonPrimitive("m3"))
            },
        ),
        "REPORT_GENERATION" to settings,
    )

    private val fakeImages: (String) -> ImageRef? =
        { id -> ImageRef(source = "$id.jpg", widthPx = 800, heightPx = 600) }

    @Test
    fun `photographs per row is honoured`() {
        val two = build(photoSchema(), photoDraft(emptyMap()), imageFor = fakeImages)
        assertEquals(2, two.blocks.filterIsInstance<ImageGridBlock>().single().columns)

        val three = build(
            photoSchema(),
            photoDraft(mapOf("photoColumns" to JsonPrimitive(3))),
            imageFor = fakeImages,
        )
        assertEquals(
            "'Photographs per row' printed two to a row whatever the designer set",
            3, three.blocks.filterIsInstance<ImageGridBlock>().single().columns,
        )
    }

    @Test
    fun `turning photographs off leaves them out of the document entirely`() {
        val document = build(
            photoSchema(),
            photoDraft(mapOf("includePhotographs" to JsonPrimitive(false))),
            imageFor = fakeImages,
        )
        assertTrue(
            "no image block may survive",
            document.blocks.none { it is ImageGridBlock || it is ImageBlock },
        )
        assertTrue(
            "and the bytes must not be collected for embedding either",
            document.images.isEmpty(),
        )
    }

    // ── the capture tier ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the compact summary prints the basic tier and nothing else`() {
        val schema = SchemaResponse(
            version = "test",
            stages = listOf(
                singletonStage(
                    18, "WORKSHOP_OUTCOMES", "Workshop outcomes",
                    text("result", "Result", tier = "BASIC"),
                    text("detail", "Loom-by-loom detail", tier = "ADVANCED"),
                ),
            ),
        )
        val draft = draftOf(
            "WORKSHOP_OUTCOMES" to mapOf(
                "result" to JsonPrimitive("Two buyers linked"),
                "detail" to JsonPrimitive("Loom 14 wove the third sample"),
            )
        )

        val compact = printedText(build(schema, draft, templateId = "COMPACT_SUMMARY"))
        assertTrue(compact.contains("Two buyers linked"))
        assertFalse(
            "'Basic-tier fields only' is what makes the compact summary a few pages:\n$compact",
            compact.contains("Loom 14 wove the third sample"),
        )

        val detailed = printedText(build(schema, draft, templateId = "DETAILED_TECHNICAL"))
        assertTrue("the archival copy keeps everything", detailed.contains("Loom 14 wove the third sample"))
    }

    // ── a registry newer than this build ─────────────────────────────────────────────────────────

    @Test
    fun `a stage no template names is printed at the end and reported, never dropped`() {
        val schema = SchemaResponse(
            version = "test",
            stages = listOf(
                singletonStage(18, "WORKSHOP_OUTCOMES", "Workshop outcomes", text("result", "Result")),
                singletonStage(23, "CLUSTER_FOLLOW_UP_VISIT", "Follow-up visit", text("seen", "What was seen")),
            ),
        )
        val draft = draftOf(
            "WORKSHOP_OUTCOMES" to mapOf("result" to JsonPrimitive("Two buyers linked")),
            "CLUSTER_FOLLOW_UP_VISIT" to mapOf("seen" to JsonPrimitive("The vat is running again")),
        )

        val document = build(schema, draft)
        assertTrue(
            "a stage this build's catalogue has never heard of must still reach the file — the " +
                "registry refreshes from the API and the catalogue ships in the APK:\n" +
                printedText(document),
            printedText(document).contains("The vat is running again"),
        )
        assertTrue(
            "and the designer must be told it was placed at the end: ${document.warnings}",
            document.warnings.any { it.contains("Follow-up visit") },
        )
    }

    // ── the honest gaps ──────────────────────────────────────────────────────────────────────────

    // ── the annexures ────────────────────────────────────────────────────────────────────────────
    //
    // DETAILED_TECHNICAL is the only template that carries the photographic and completeness
    // annexures, so it is what these build with. That is the point of building through
    // `buildWorkshopDocument` rather than calling the renderers: it proves the sections are REACHED
    // from the template a designer can actually pick, which a test of an emittable block does not.

    /**
     * A workshop with a photograph, a required field left blank, and a cost sheet whose product
     * reference points at a row that no longer exists.
     */
    private fun annexureSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 16, key = "FINAL_PROTOTYPE_DOCUMENTATION", title = "Final products",
                entities = listOf(
                    EntityDto(
                        key = "finalProduct", cardinality = "SINGLETON", title = "Final product",
                        fields = listOf(
                            FieldDto(
                                key = "name", label = "Name", type = "TEXT",
                                tier = "BASIC", required = true,
                            ),
                            FieldDto(
                                key = "dimensions", label = "Dimensions", type = "TEXT",
                                tier = "BASIC", required = true,
                            ),
                            FieldDto(
                                key = "photos", label = "Photographs", type = "IMAGE_LIST",
                                reportRole = "GALLERY", tier = "BASIC",
                            ),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 17, key = "COSTING_MARKET_LINKAGE", title = "Costing",
                entities = listOf(
                    EntityDto(
                        key = "costSheet", cardinality = "COLLECTION", title = "Cost sheets",
                        fields = listOf(
                            FieldDto(
                                key = "productRef", label = "Product", type = "REF",
                                tier = "BASIC", required = true,
                            ),
                        ),
                    )
                ),
            ),
            singletonStage(20, "REPORT_GENERATION", "Report generation", text("reportTitle", "Report title")),
        ),
    )

    private fun annexureDraft(settings: Map<String, JsonElement>) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            "FINAL_PROTOTYPE_DOCUMENTATION" to StageDraft(
                stageId = "FINAL_PROTOTYPE_DOCUMENTATION",
                values = mapOf(
                    "name" to JsonPrimitive("Bandha table runner"),
                    // "dimensions" is deliberately absent: the completeness annexure has to have an
                    // outstanding field to name, or it is only being tested on the happy row.
                    "photos" to buildJsonArray {
                        add(JsonPrimitive("m1")); add(JsonPrimitive("m2"))
                    },
                ),
            ),
            "COSTING_MARKET_LINKAGE" to StageDraft(
                stageId = "COSTING_MARKET_LINKAGE",
                rows = listOf(
                    DraftRow(
                        id = "costSheet#1",
                        // A cuid pointing at a row that was deleted after this one cited it.
                        values = mapOf("productRef" to JsonPrimitive("cmsik2jg8000eh8xc1lcy661a")),
                    )
                ),
            ),
            "REPORT_GENERATION" to StageDraft(stageId = "REPORT_GENERATION", values = settings),
        ),
    )

    private fun tableOf(document: ReportDocument, header: String): TableBlock? =
        document.blocks.filterIsInstance<TableBlock>()
            .firstOrNull { block -> block.columns.any { it.header == header } }

    @Test
    fun `the photographic annexure is built from the copies already on this device`() {
        val document = build(
            annexureSchema(), annexureDraft(emptyMap()),
            templateId = "DETAILED_TECHNICAL", imageFor = fakeImages,
        )
        assertTrue(
            "the detailed technical template carries 'Annexure — Photographic record' and the " +
                "handset drew nothing there:\n" + headings(document),
            headings(document).any { it.contains("Photographic record") },
        )
        // Three across, and every photograph in the record — not just the two the stage section
        // above it already printed.
        val contactSheet = document.blocks.filterIsInstance<ImageGridBlock>().last()
        assertEquals(3, contactSheet.columns)
        assertEquals(2, contactSheet.images.size)
        assertTrue(
            "a plate with no line under it is a picture, not evidence",
            contactSheet.images.all { it.second.isNotBlank() },
        )
    }

    @Test
    fun `turning the photographic annexure off removes it`() {
        val document = build(
            annexureSchema(),
            annexureDraft(mapOf("includeMediaAnnexure" to JsonPrimitive(false))),
            templateId = "DETAILED_TECHNICAL", imageFor = fakeImages,
        )
        assertFalse(
            "a designer who turned the annexure off got one anyway",
            headings(document).any { it.contains("Photographic record") },
        )
    }

    @Test
    fun `the completeness annexure prints every stage and what is outstanding`() {
        val document = build(
            annexureSchema(), annexureDraft(emptyMap()),
            templateId = "DETAILED_TECHNICAL", imageFor = fakeImages,
        )
        val table = tableOf(document, "Outstanding")
        assertNotNull(
            "the detailed technical template carries 'Annexure — Data completeness' and the " +
                "handset drew no table there:\n" + headings(document),
            table,
        )
        val rows = table!!.rows
        val printed = rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        assertEquals(
            "every registry stage gets a row, including the ones never opened:\n$printed",
            annexureSchema().stages.size, rows.size,
        )
        assertTrue(
            "the outstanding required field must be named:\n$printed",
            printed.contains("Dimensions"),
        )
    }

    @Test
    fun `the completeness annexure counts a reference whose row was deleted as unfilled`() {
        // THE DEFECT THIS EXISTS TO PREVENT, from the server's own record of it: the annexure read
        // "17. Costing | 1/1 | 100% | Complete" while the same document printed "Product | Not
        // recorded." for the very field it had counted as filled. The renderer blanks an id whose
        // row was deleted and a scorer that only checks for a non-empty string does not.
        val document = build(
            annexureSchema(), annexureDraft(emptyMap()),
            templateId = "DETAILED_TECHNICAL", imageFor = fakeImages,
        )
        val row = tableOf(document, "Outstanding")!!.rows
            .single { runText(it.first()).startsWith("17.") }
        val printed = row.joinToString(" | ") { runText(it) }
        assertTrue(
            "a dangling reference was counted as a filled field, so this annexure now contradicts " +
                "the cost sheet eighteen pages earlier in the same file: $printed",
            printed.contains("0/1"),
        )
        assertTrue("and it must be named as outstanding: $printed", printed.contains("Product"))
    }

    @Test
    fun `turning the completeness annexure off removes it`() {
        val document = build(
            annexureSchema(),
            annexureDraft(mapOf("includeCompletenessAnnexure" to JsonPrimitive(false))),
            templateId = "DETAILED_TECHNICAL", imageFor = fakeImages,
        )
        assertTrue(
            "a designer who turned the completeness annexure off got one anyway",
            tableOf(document, "Outstanding") == null,
        )
    }

    @Test
    fun `neither annexure is warned about now that both are built`() {
        // The ledger and the warnings are one claim. A file that carries the photographic record
        // AND a note saying it does not is worse than either alone.
        val document = build(
            annexureSchema(), annexureDraft(emptyMap()),
            templateId = "DETAILED_TECHNICAL", imageFor = fakeImages,
        )
        assertTrue(
            "the warnings still disown a section this build now draws: ${document.warnings}",
            document.warnings.none {
                it.contains("photographic annexure") || it.contains("completeness annexure")
            },
        )
    }

    @Test
    fun `every special section is either rendered here or named as unsupported`() {
        // The completeness check the ledger cannot make on its own: a section that is neither
        // drawn nor declared is a silent hole in the delivered file.
        //
        // THE PROBE DRAFT HAS TO ANSWER THE QUESTION THIS TEST IS ACTUALLY ASKING, which is "does
        // this build know how to draw the section", not "did this particular workshop fill it in".
        // Every special section the device builds omits ITSELF when the workshop said nothing it
        // could print — an acknowledgement with no text, a metric row with no records, a signature
        // block with nobody to sign — and each of those omissions is deliberate and matches the
        // server. Probing with an empty draft therefore reports a WORKING section as an unbuilt
        // one, and the only way to quieten that false alarm is to add the section to
        // UNSUPPORTED_SECTIONS, i.e. to warn a designer they are losing a section they are in fact
        // getting. That is the ledger lying in the expensive direction, so each section is given
        // the one value it needs to have something to say.
        // COMPLETENESS scores the REGISTRY's stages, and ANNEXURE_MEDIA walks them looking for
        // photographs, so both report "unbuilt" against an empty schema however well they work.
        // One stage carrying one required text field and one photograph is the least that lets
        // either of them have anything to say. MAP is gated on stage 1 naming a state or a district
        // and CHART on a figure having two categories, so both get the least that satisfies them.
        val schema = SchemaResponse(
            version = "test",
            stages = listOf(
                StageDto(
                    number = 1, key = "WORKSHOP_SETUP", title = "Workshop setup",
                    entities = listOf(
                        EntityDto(
                            key = "workshopSetup", cardinality = "SINGLETON", title = "Workshop setup",
                            fields = listOf(
                                FieldDto(
                                    key = "designerName", label = "Designer", type = "TEXT",
                                    tier = "BASIC", required = true,
                                ),
                                FieldDto(
                                    key = "state", label = "State", type = "TEXT", tier = "BASIC",
                                ),
                                FieldDto(
                                    key = "coverPhoto", label = "Cover photograph", type = "IMAGE",
                                    tier = "BASIC", reportRole = "GALLERY",
                                ),
                            ),
                        )
                    ),
                )
            ),
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            title = "Barpali cluster",
            stages = mapOf(
                // COVER's author line, SIGNATURES' first signatory, ANNEXURE_MEDIA's one plate, and
                // MAP's gate — a state and nothing else, which is the case the server says is worth
                // drawing on its own.
                "WORKSHOP_SETUP" to StageDraft(
                    stageId = "WORKSHOP_SETUP",
                    values = mapOf(
                        "designerName" to JsonPrimitive("A. Mohanty"),
                        "state" to JsonPrimitive("Odisha"),
                        "coverPhoto" to JsonPrimitive("media-1"),
                    ),
                ),
                // ACKNOWLEDGEMENT prints exactly one field and nothing without it.
                "INTRODUCTORY_ADMIN_DOCUMENTATION" to StageDraft(
                    stageId = "INTRODUCTORY_ADMIN_DOCUMENTATION",
                    values = mapOf(
                        "acknowledgement" to
                            JsonPrimitive("The team thanks the weavers of Barpali."),
                    ),
                ),
                // SUMMARY_METRICS counts records; with none it has no row to draw. The two rows
                // together are also CHART's two categories — one alone is a one-bar bar chart, which
                // the builder correctly refuses to draw.
                "SKETCH_DEVELOPMENT" to StageDraft(
                    stageId = "SKETCH_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(id = "sketch#1", values = mapOf("name" to JsonPrimitive("Runner"))),
                    ),
                ),
                "PROTOTYPE_DEVELOPMENT" to StageDraft(
                    stageId = "PROTOTYPE_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "prototype#1",
                            values = mapOf("name" to JsonPrimitive("Bandha table runner")),
                        )
                    ),
                ),
            ),
        )
        SpecialSection.entries.forEach { special ->
            val template = reportTemplate("DCH_STANDARD").copy(
                sections = listOf(TemplateSection(special = special)),
            )
            val plan = ReportPlan(
                template = template,
                meta = reportMetaFor(ReportRecord(id = "x", title = "T"), "DCH_STANDARD", null, ""),
                theme = ReportTheme(),
                settings = emptyMap(),
                warnings = emptyList(),
            )
            val document = buildWorkshopDocument(
                schema = schema, draft = draft, workshopId = "x", templateId = "DCH_STANDARD",
                warnings = emptyList(), accent = "",
                imageFor = { id -> ImageRef(source = "/tmp/$id.jpg", widthPx = 1600, heightPx = 1200) },
                plan = plan,
            )
            val drew = document.blocks.isNotEmpty()
            val declared = special in UNSUPPORTED_SECTIONS
            assertTrue(
                "$special draws nothing and is not listed in UNSUPPORTED_SECTIONS, so a template " +
                    "carrying it loses a whole section of the report with nothing said",
                drew || declared,
            )
            assertFalse(
                "$special is drawn AND listed as unsupported; the warning is now a lie",
                drew && declared,
            )
        }
    }

    // ── the infographics and the map ─────────────────────────────────────────────────────────────
    //
    // WHAT THESE PIN. The rasterisers shipped in the APK, both writers dispatched ChartBlock and
    // MapBlock, and the India boundary rings are an offline asset — and no block was ever
    // CONSTRUCTED, so the DEFAULT template came off the phone with no figures at all while the
    // office's copy of the same workshop printed five. These build through `buildWorkshopDocument`
    // rather than calling the builders directly, for the same reason the annexure tests do: the
    // defect was never in the drawing, it was in nothing reaching it.

    private fun figuresSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 15, key = "PROTOTYPE_VALIDATION", title = "Prototype validation",
                entities = listOf(
                    EntityDto(
                        key = "prototypeValidation", cardinality = "COLLECTION", title = "Reviews",
                        fields = listOf(
                            FieldDto(
                                key = "decision", label = "Decision", type = "ENUM", tier = "BASIC",
                                reportRole = "TABLE_COLUMN",
                                options = listOf(
                                    EnumOption("SELECTED", "Selected"),
                                    EnumOption("REJECTED", "Rejected"),
                                ),
                            ),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 17, key = "COSTING_MARKET_LINKAGE", title = "Costing",
                entities = listOf(
                    EntityDto(
                        key = "costSheet", cardinality = "COLLECTION", title = "Cost sheets",
                        fields = listOf(
                            FieldDto(
                                key = "materialCost", label = "Material cost", type = "MONEY",
                                tier = "BASIC", reportRole = "TABLE_COLUMN",
                            ),
                            FieldDto(
                                key = "labourCost", label = "Labour cost", type = "MONEY",
                                tier = "BASIC", reportRole = "TABLE_COLUMN",
                            ),
                            FieldDto(
                                key = "transportCost", label = "Transport", type = "MONEY",
                                tier = "BASIC", reportRole = "TABLE_COLUMN",
                            ),
                        ),
                    )
                ),
            ),
            singletonStage(18, "WORKSHOP_OUTCOMES", "Workshop outcomes", text("result", "Result")),
            singletonStage(20, "REPORT_GENERATION", "Report generation", text("reportTitle", "Report title")),
        ),
    )

    /** Three sketches, two prototypes, two reviews and two cost sheets — enough for three figures. */
    private fun figuresDraft(settings: Map<String, JsonElement> = emptyMap()) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            "SKETCH_DEVELOPMENT" to StageDraft(
                stageId = "SKETCH_DEVELOPMENT",
                rows = (1..3).map { DraftRow(id = "sketch#$it") },
            ),
            "PROTOTYPE_DEVELOPMENT" to StageDraft(
                stageId = "PROTOTYPE_DEVELOPMENT",
                rows = (1..2).map { DraftRow(id = "prototype#$it") },
            ),
            "PROTOTYPE_VALIDATION" to StageDraft(
                stageId = "PROTOTYPE_VALIDATION",
                rows = listOf(
                    DraftRow(id = "prototypeValidation#1", values = mapOf("decision" to JsonPrimitive("SELECTED"))),
                    DraftRow(id = "prototypeValidation#2", values = mapOf("decision" to JsonPrimitive("REJECTED"))),
                ),
            ),
            "COSTING_MARKET_LINKAGE" to StageDraft(
                stageId = "COSTING_MARKET_LINKAGE",
                rows = listOf(
                    DraftRow(
                        id = "costSheet#1",
                        values = mapOf(
                            "materialCost" to JsonPrimitive(400),
                            "labourCost" to JsonPrimitive(250),
                            // Entered as zero, which is not the same as entered: it must not appear.
                            "transportCost" to JsonPrimitive(0),
                        ),
                    ),
                    DraftRow(
                        id = "costSheet#2",
                        values = mapOf(
                            "materialCost" to JsonPrimitive(100),
                            "labourCost" to JsonPrimitive(50),
                        ),
                    ),
                ),
            ),
            "WORKSHOP_OUTCOMES" to StageDraft(
                stageId = "WORKSHOP_OUTCOMES",
                values = mapOf("result" to JsonPrimitive("Two buyers linked")),
            ),
            "REPORT_GENERATION" to StageDraft(stageId = "REPORT_GENERATION", values = settings),
        ),
    )

    private fun charts(document: ReportDocument) = document.blocks.filterIsInstance<ChartBlock>()

    @Test
    fun `the front-page infographics are drawn from the records`() {
        val document = build(figuresSchema(), figuresDraft())
        val yield_ = charts(document).firstOrNull { it.title.contains("Designs, prototypes") }
        assertNotNull(
            "DCH_STANDARD places 'Outcomes in figures' on its front page and the handset drew " +
                "nothing there: ${charts(document).map { it.title }}",
            yield_,
        )
        // Counted from the records, and only categories that carry a figure — a "Final products 0"
        // bar would state the workshop produced none when what the record says is that nobody
        // entered any yet.
        assertEquals(
            listOf("Sketches" to 3.0, "Prototypes" to 2.0),
            yield_!!.series,
        )
        assertTrue(
            "the review donut is the second of the two the template asks for: " +
                charts(document).map { it.title },
            charts(document).any { it.title.contains("review decision") },
        )
    }

    @Test
    fun `a figure the template asks for twice is drawn once`() {
        // DCH_STANDARD carries OUTCOME_FIGURES at the front AND the WORKSHOP_OUTCOMES stage section
        // that owns the same two figures. Printing the yield chart twice makes a reader hunt for the
        // difference between two identical pictures.
        val document = build(figuresSchema(), figuresDraft())
        assertEquals(
            "the same figure printed more than once: ${charts(document).map { it.title }}",
            1,
            charts(document).count { it.title.contains("Designs, prototypes") },
        )
    }

    @Test
    fun `the cost breakdown sums every sheet and drops the heads nobody entered`() {
        val cost = charts(build(figuresSchema(), figuresDraft()))
            .firstOrNull { it.title.contains("Cost by head") }
        assertNotNull("the costing stage's own figure was not drawn", cost)
        assertEquals(
            "a head entered as zero reads as 'transport was free' on a document that becomes a " +
                "sanctioned amount, and the record says nobody entered it: ${cost!!.series}",
            listOf("Material cost" to 500.0, "Labour cost" to 300.0),
            cost.series,
        )
    }

    @Test
    fun `a section that refuses figures gets none`() {
        // PHOTO_CATALOGUE's price list sets includeFigures = false: the cost-by-head figure prints
        // the maker's material and labour cost beside the price the buyer is being quoted.
        val catalogue = build(figuresSchema(), figuresDraft(), templateId = "PHOTO_CATALOGUE")
        assertTrue(
            "the buyer-facing catalogue handed the buyer the cluster's margin: " +
                charts(catalogue).map { it.title },
            charts(catalogue).none { it.title.contains("Cost by head") },
        )
    }

    @Test
    fun `nothing at all is emitted when the record supports no figure`() {
        // Not the heading, not an empty frame, not a note. A heading over nothing is the commonest
        // way a generated report looks broken, and this section exists for records that have no
        // figures in them yet.
        val document = build(outcomesSchema(), outcomesDraft(emptyMap()))
        assertTrue("no figure has data here", charts(document).isEmpty())
        assertTrue(
            "an empty CHART section still printed its heading: ${headings(document)}",
            headings(document).none { it.contains("Outcomes in figures") },
        )
    }

    private fun mapDraft(setup: Map<String, JsonElement>) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf("WORKSHOP_SETUP" to StageDraft(stageId = "WORKSHOP_SETUP", values = setup)),
    )

    private fun mapSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            singletonStage(
                1, "WORKSHOP_SETUP", "Workshop setup",
                text("state", "State"), text("village", "Village"),
            ),
        ),
    )

    @Test
    fun `the map is drawn from the state alone, with no pin and a caption that says so`() {
        val document = build(mapSchema(), mapDraft(mapOf("state" to JsonPrimitive("Odisha"))))
        val map = document.blocks.filterIsInstance<MapBlock>().firstOrNull()
        assertNotNull("a workshop that named its state has said enough to tint the region", map)
        assertEquals(setOf("Odisha"), map!!.highlight)
        assertTrue("nothing in the record places a point", map.points.isEmpty())
        assertTrue(
            "a map with no pins must say why, or it reads as a broken renderer: ${map.caption}",
            map.caption.contains("could be resolved to a position"),
        )
    }

    @Test
    fun `the map is not drawn when stage 1 names neither a state nor a district`() {
        val document = build(mapSchema(), mapDraft(mapOf("village" to JsonPrimitive("Barpali"))))
        assertTrue(
            "a map of India with no idea which bit of it this workshop happened in is a decoration",
            document.blocks.filterIsInstance<MapBlock>().isEmpty(),
        )
    }

    @Test
    fun `the venue pin comes from the measured fix and from nothing else`() {
        // The server's own first branch: a coordinate somebody stood at and captured wins and is
        // never averaged with anything, so a pin drawn here lands where the office's copy puts it.
        // Where there is no fix the server geocodes the typed address through a place atlas that is
        // not on this device, and inventing a pin from the village name would put the two copies of
        // one report in different places.
        val fixed = build(
            mapSchema(),
            mapDraft(
                mapOf(
                    "state" to JsonPrimitive("Odisha"),
                    "village" to JsonPrimitive("Barpali"),
                    "venueLocation" to DwValues.geoOf(21.1958, 83.5872),
                )
            ),
        ).blocks.filterIsInstance<MapBlock>().first()
        assertEquals(1, fixed.points.size)
        assertEquals("Barpali", fixed.points.first().label)
        assertEquals(MapPointKind.VENUE, fixed.points.first().kind)
        assertTrue(
            "the venue sentence must name the place the RECORD names: ${fixed.caption}",
            fixed.caption.contains("Workshop venue: Barpali"),
        )

        // 0,0 is the Gulf of Guinea and is what a form that never obtained a fix writes.
        val unfixed = build(
            mapSchema(),
            mapDraft(
                mapOf(
                    "state" to JsonPrimitive("Odisha"),
                    "village" to JsonPrimitive("Barpali"),
                    "venueLocation" to DwValues.geoOf(0.0, 0.0),
                )
            ),
        ).blocks.filterIsInstance<MapBlock>().first()
        assertTrue("a zero fix is not a fix: ${unfixed.points}", unfixed.points.isEmpty())
    }

    @Test
    fun `the tinted state is keyed by the canonical name the rasteriser can seed`() {
        // WHAT THIS PINS. `highlight` is not a caption, it is a LOOKUP KEY: `ReportMap.tintStates`
        // resolves each name against the 36-entry STATE_SEATS table to find the pixel it seeds the
        // flood fill from. A raw "Orissa" finds no seat, tints nothing, and the figure prints
        // "Not tinted: Orissa" across a map of India — in the one case where the tinted region is
        // the ENTIRE content of the figure, because no fix and no atlas means there is no pin.
        //
        // AND THE SPELLING IS REAL. The state column predates its validator and is nullable, so
        // rows holding "Orissa" and "Pondicherry" exist in the data; that is why `address._ALIASES`
        // exists on the server and why `canonicalState` ports it. Nothing asserted the builder
        // actually routes stage 1's typed answer through it.
        val alias = build(mapSchema(), mapDraft(mapOf("state" to JsonPrimitive("  orissa "))))
            .blocks.filterIsInstance<MapBlock>().first()
        assertEquals(
            "a spelling that is in the data must tint the state it names, not print a complaint",
            setOf("Odisha"),
            alias.highlight,
        )

        // An unrecognised name is carried through AS TYPED, matching the server's
        // `canonical_state(state) or state`. It cannot tint — there is no seat for it — and the
        // renderer NAMES it on the figure. Dropping it instead would leave a reader attributing an
        // untinted map to the data rather than to a state nobody can resolve.
        val unknown = build(mapSchema(), mapDraft(mapOf("state" to JsonPrimitive("Atlantis"))))
            .blocks.filterIsInstance<MapBlock>().first()
        assertEquals(setOf("Atlantis"), unknown.highlight)
    }

    @Test
    fun `the file says which copy of the report it is`() {
        // The officer reads the phone's PDF as THE report: same cover, same running foot, a
        // self-consistent contents page. One line under the cover is what stops a section present in
        // the office's copy and absent from this one reading as a document somebody altered.
        val document = build(
            annexureSchema(),
            annexureDraft(mapOf("includeTranscripts" to JsonPrimitive(true))),
            templateId = "DETAILED_TECHNICAL", imageFor = fakeImages,
        )
        val printed = printedText(document)
        assertTrue(
            "the file does not say it was made on a handset:\n$printed",
            printed.contains("Generated on a handset in the field on 04 Mar 2026"),
        )
        assertTrue(
            "it must name what the office's copy carries and this one does not:\n$printed",
            printed.contains("also carries the transcripts of the recordings"),
        )
        // And it shortens by itself: with nothing outstanding it states only where the file was made.
        val nothingOutstanding = build(annexureSchema(), annexureDraft(emptyMap()))
        assertFalse(
            "a report with no outstanding section apologised for one anyway:\n" +
                printedText(nothingOutstanding),
            printedText(nothingOutstanding).contains("also carries"),
        )
    }

    @Test
    fun `the plan's warnings travel with the document`() {
        val document = build(
            outcomesSchema(),
            outcomesDraft(mapOf("fontPreset" to JsonPrimitive("GARAMOND"))),
            format = "PDF",
        )
        assertTrue(
            "a typeface a handset PDF cannot carry has to reach the designer: ${document.warnings}",
            document.warnings.any { it.contains("Garamond") },
        )
    }

    // ── the presentation the template asks for ───────────────────────────────────────────────────

    @Test
    fun `the same records are a table in one template and cards in another`() {
        val schema = SchemaResponse(
            version = "test",
            stages = listOf(
                StageDto(
                    number = 16, key = "FINAL_PROTOTYPE_DOCUMENTATION", title = "Final products",
                    entities = listOf(
                        EntityDto(
                            key = "finalProduct", cardinality = "COLLECTION", title = "Final product",
                            labelField = "name",
                            fields = listOf(
                                text("name", "Name", role = "TABLE_COLUMN"),
                                text("price", "Price", role = "TABLE_COLUMN"),
                            ),
                        )
                    ),
                ),
            ),
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            title = "Barpali cluster",
            stages = mapOf(
                "FINAL_PROTOTYPE_DOCUMENTATION" to StageDraft(
                    stageId = "FINAL_PROTOTYPE_DOCUMENTATION",
                    rows = listOf(
                        DraftRow(
                            id = "finalProduct#1",
                            values = mapOf(
                                "name" to JsonPrimitive("Bandha table runner"),
                                "price" to JsonPrimitive("1250"),
                            ),
                        )
                    ),
                )
            ),
        )

        val compact = build(schema, draft, templateId = "COMPACT_SUMMARY")
        assertTrue(
            "the compact summary asks for a TABLE",
            compact.blocks.any { it is TableBlock },
        )

        val catalogue = build(schema, draft, templateId = "PHOTO_CATALOGUE")
        assertTrue(
            "the buyer-facing catalogue asks for CARDS — a heading and a picture per product, " +
                "not a spreadsheet: ${catalogue.blocks.map { it::class.simpleName }}",
            catalogue.blocks.none { it is TableBlock },
        )
        assertTrue(
            "and each product still gets its own heading: ${headings(catalogue)}",
            headings(catalogue).any { it.contains("Bandha table runner") },
        )
    }
}
