package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.CoverBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ImageBlock
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.KeyValueBlock
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
        // either of them have anything to say.
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
                // COVER's author line, SIGNATURES' first signatory, and ANNEXURE_MEDIA's one plate.
                "WORKSHOP_SETUP" to StageDraft(
                    stageId = "WORKSHOP_SETUP",
                    values = mapOf(
                        "designerName" to JsonPrimitive("A. Mohanty"),
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
                // SUMMARY_METRICS counts records; with none it has no row to draw.
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
