package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone actually puts in the .docx and the .pdf it generates with no signal.
 *
 * THE FIRST TEST THIS FUNCTION HAS EVER HAD, which is the point. `buildWorkshopDocument` dropped
 * every one of the registry's 98 RICH_TEXT fields — 81 narratives and 17 bulleted lists, i.e. the
 * entire prose of the report — and printed a raw record id in all eleven of its printed REF fields,
 * and neither was visible from anywhere: the editor renders the prose the file omits, and the
 * headings still print with nothing beneath them. Both defects survived because the only way to see
 * the output was to install the app and open the file it wrote.
 *
 * The assertions below are deliberately about CONTENT, not about block counts or ordering. A test
 * that pinned the shape of the document would fail on every legitimate template change and would be
 * deleted within a month; what must never regress is that the words a designer typed are in the file.
 */
class ReportDocumentTest {

    // ── The registry, cut down to the shapes that matter ──────────────────────────────────────────

    private fun richField(key: String, label: String, role: String) = FieldDto(
        key = key, label = label, type = "RICH_TEXT", reportRole = role
    )

    private fun textField(key: String, label: String, role: String = "KEY_VALUE") = FieldDto(
        key = key, label = label, type = "TEXT", reportRole = role
    )

    private fun refField(key: String, label: String, model: String, role: String) = FieldDto(
        key = key, label = label, type = "REF", refModel = model, reportRole = role
    )

    /** `{"blocks":[{"kind":…,"spans":[{"text":…}]}]}` — the shape `rich_text.to_json` writes. */
    private fun rich(vararg blocks: Pair<String, String>): JsonElement = buildJsonObject {
        putJsonArray("blocks") {
            blocks.forEach { (kind, text) ->
                add(
                    buildJsonObject {
                        put("kind", kind)
                        putJsonArray("spans") { add(buildJsonObject { put("text", text) }) }
                    }
                )
            }
        }
    }

    private fun schemaOf(vararg stages: StageDto) = SchemaResponse(version = "test", stages = stages.toList())

    private fun draftOf(
        stageKey: String,
        values: Map<String, JsonElement> = emptyMap(),
        rows: List<DraftRow> = emptyList(),
    ) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(stageKey to StageDraft(stageId = stageKey, values = values, rows = rows)),
    )

    private fun build(schema: SchemaResponse, draft: WorkshopDraft): ReportDocument =
        buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = "DCH_STANDARD",
            templateName = "DCH standard",
            warnings = emptyList(),
            accent = "",
            // No media on this device: an image block resolves to nothing and is skipped, which is
            // the documented contract and keeps the test free of the filesystem.
            imageFor = { null },
        )

    // ── Reading the built document ───────────────────────────────────────────────────────────────

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    /** Every word the document would print, from every block type that carries any. */
    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is BulletListBlock -> block.items.joinToString("\n") { runText(it) }
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        else -> ""
    }

    private fun tables(document: ReportDocument) = document.blocks.filterIsInstance<TableBlock>()

    // ── RICH_TEXT ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a NARRATIVE rich-text field prints its prose`() {
        val schema = schemaOf(
            StageDto(
                number = 1, key = "INTRODUCTION", title = "Introduction",
                entities = listOf(
                    EntityDto(
                        key = "intro", cardinality = "SINGLETON", title = "Introduction",
                        fields = listOf(richField("acknowledgement", "Acknowledgement", "NARRATIVE"))
                    )
                )
            )
        )
        val prose = "The cluster's weavers gave a fortnight of their time to this workshop."
        val document = build(
            schema,
            draftOf("INTRODUCTION", values = mapOf("acknowledgement" to rich("PARAGRAPH" to prose)))
        )

        // The exact failure: before the RICH_TEXT arm existed, `DwValues.text` answered "" for the
        // JsonObject a rich value is, `builder.para("")` dropped the empty paragraph, and the
        // acknowledgement was absent from a file that still carried its heading.
        assertTrue(
            "the acknowledgement is missing from the on-device report:\n${printedText(document)}",
            printedText(document).contains(prose)
        )
    }

    @Test
    fun `a BULLETS rich-text field prints one list item per block`() {
        val schema = schemaOf(
            StageDto(
                number = 21, key = "RECOMMENDATIONS", title = "Recommendations",
                entities = listOf(
                    EntityDto(
                        key = "recommendation", cardinality = "SINGLETON", title = "Recommendations",
                        fields = listOf(richField("actions", "Recommended actions", "BULLETS"))
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "RECOMMENDATIONS",
                values = mapOf(
                    "actions" to rich(
                        "BULLET_ITEM" to "Re-establish the natural indigo vat",
                        "BULLET_ITEM" to "Link the cluster to two export buyers",
                    )
                )
            )
        )

        val lists = document.blocks.filterIsInstance<BulletListBlock>()
        assertEquals("the two bullets should merge into ONE list block", 1, lists.size)
        assertEquals(
            listOf("Re-establish the natural indigo vat", "Link the cluster to two export buyers"),
            lists.single().items.map(::runText)
        )
    }

    @Test
    fun `a rich-text TABLE_COLUMN prints its words in the cell`() {
        val schema = schemaOf(
            StageDto(
                number = 18, key = "FEEDBACK", title = "Artisan feedback",
                entities = listOf(
                    EntityDto(
                        key = "feedback", cardinality = "COLLECTION", title = "Feedback",
                        labelField = "who",
                        fields = listOf(
                            textField("who", "Artisan", role = "TABLE_COLUMN"),
                            richField("said", "What they said", "TABLE_COLUMN"),
                        )
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "FEEDBACK",
                rows = listOf(
                    DraftRow(
                        id = "feedback#row-1",
                        values = mapOf(
                            "who" to JsonPrimitive("Sushila Meher"),
                            "said" to rich("PARAGRAPH" to "The new warp width suits our loom."),
                        )
                    )
                )
            )
        )

        // A cell holds runs and cannot hold a block, so this is the `plain_runs` path — but it must
        // still carry the words. It used to carry "" for exactly the same reason the narrative did.
        assertTrue(
            "the feedback cell is empty:\n${printedText(document)}",
            printedText(document).contains("The new warp width suits our loom.")
        )
    }

    @Test
    fun `no rich-text value ever prints as raw JSON`() {
        val schema = schemaOf(
            StageDto(
                number = 1, key = "INTRODUCTION", title = "Introduction",
                entities = listOf(
                    EntityDto(
                        key = "intro", cardinality = "SINGLETON", title = "Introduction",
                        fields = listOf(richField("purpose", "Purpose", "NARRATIVE"))
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf("INTRODUCTION", values = mapOf("purpose" to rich("PARAGRAPH" to "To revive the bandha tradition.")))
        )

        // The other half of the trap the server documents at `format_value`: a rich value that is
        // stringified rather than flattened prints `{"blocks":[…]}` into a ministry's document, and
        // every emptiness check reads that JSON-shaped string as a filled field.
        val printed = printedText(document)
        assertFalse("the report printed raw JSON:\n$printed", printed.contains("\"blocks\""))
        assertFalse("the report printed raw JSON:\n$printed", printed.contains("kind="))
        assertTrue(printed.contains("To revive the bandha tradition."))
    }

    // ── REF ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Stage 17's shape: cost sheets in one entity referring to final products in another, with the
     * cost sheet's own label field being that reference. This is the table that was headed by a cuid.
     */
    private fun costSheetSchema() = schemaOf(
        StageDto(
            number = 16, key = "FINAL_PRODUCTS", title = "Final products",
            entities = listOf(
                EntityDto(
                    key = "finalProduct", cardinality = "COLLECTION", title = "Final product",
                    labelField = "name",
                    fields = listOf(textField("name", "Product name", role = "TABLE_COLUMN"))
                )
            )
        ),
        StageDto(
            number = 17, key = "COSTING", title = "Costing",
            entities = listOf(
                EntityDto(
                    key = "costSheet", cardinality = "COLLECTION", title = "Cost sheet",
                    labelField = "productRef",
                    fields = listOf(
                        refField("productRef", "Product", "DwFinalProduct", "TABLE_COLUMN"),
                        textField("total", "Total", role = "TABLE_COLUMN"),
                    )
                )
            )
        ),
    )

    @Test
    fun `a REF column prints the name of the record it points at, not its id`() {
        val productId = "cmsik2jg8000eh8xc1lcy661a"
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "FINAL_PRODUCTS" to StageDraft(
                    stageId = "FINAL_PRODUCTS",
                    rows = listOf(
                        DraftRow(
                            id = "finalProduct#row-1",
                            values = mapOf(
                                "_entryId" to JsonPrimitive(productId),
                                "name" to JsonPrimitive("Bandha table runner"),
                            )
                        )
                    )
                ),
                "COSTING" to StageDraft(
                    stageId = "COSTING",
                    rows = listOf(
                        DraftRow(
                            id = "costSheet#row-1",
                            values = mapOf(
                                "productRef" to JsonPrimitive(productId),
                                "total" to JsonPrimitive("1250.00"),
                            )
                        )
                    )
                ),
            )
        )

        val document = build(costSheetSchema(), draft)
        val printed = printedText(document)

        assertTrue("the cost sheet does not name its product:\n$printed", printed.contains("Bandha table runner"))
        assertFalse("the report printed a raw record id:\n$printed", printed.contains(productId))
    }

    @Test
    fun `a REF made offline resolves through the local row id the picker stored`() {
        // A workshop authored entirely in a courtyard: neither row has a server id yet, so the
        // reference holds the local DraftRow key. Indexing only `_entryId` would leave exactly this
        // workshop — the one the offline export path exists for — still printing an id.
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "FINAL_PRODUCTS" to StageDraft(
                    stageId = "FINAL_PRODUCTS",
                    rows = listOf(
                        DraftRow(
                            id = "finalProduct#kzzq1p8n4m2wd7xr",
                            values = mapOf("name" to JsonPrimitive("Sambalpuri stole"))
                        )
                    )
                ),
                "COSTING" to StageDraft(
                    stageId = "COSTING",
                    rows = listOf(
                        DraftRow(
                            id = "costSheet#row-1",
                            values = mapOf("productRef" to JsonPrimitive("kzzq1p8n4m2wd7xr"))
                        )
                    )
                ),
            )
        )

        val printed = printedText(build(costSheetSchema(), draft))
        assertTrue("the offline reference did not resolve:\n$printed", printed.contains("Sambalpuri stole"))
        assertFalse(printed.contains("kzzq1p8n4m2wd7xr"))
    }

    @Test
    fun `an unresolvable REF prints nothing when it is an id and itself when it is not`() {
        fun costRow(value: String) = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "COSTING" to StageDraft(
                    stageId = "COSTING",
                    rows = listOf(DraftRow(id = "costSheet#row-1", values = mapOf("productRef" to JsonPrimitive(value))))
                )
            )
        )

        // The row it named was deleted after this one cited it. A bare cuid in a ministry's table is
        // worse than a visible gap.
        val orphanId = "cmsiusb3a002yrmg1gnl4mfc1"
        assertFalse(printedText(build(costSheetSchema(), costRow(orphanId))).contains(orphanId))

        // A REF may hold text a designer typed by hand — a sketch number, a value migrated before
        // the picker existed. Blanking that silently drops a field somebody filled in.
        assertTrue(printedText(build(costSheetSchema(), costRow("SK-01"))).contains("SK-01"))
    }

    @Test
    fun `a row headed by a REF label field is titled by the name, not the id`() {
        // Five entities in the registry take their heading from a REF. Fixing only the table cells
        // would leave whole sub-sections of the report titled by a record id.
        val productId = "cmsik2jg8000eh8xc1lcy661a"
        val schema = schemaOf(
            StageDto(
                number = 16, key = "FINAL_PRODUCTS", title = "Final products",
                entities = listOf(
                    EntityDto(
                        key = "finalProduct", cardinality = "COLLECTION", title = "Final product",
                        labelField = "name",
                        fields = listOf(textField("name", "Product name", role = "TABLE_COLUMN"))
                    )
                )
            ),
            StageDto(
                number = 22, key = "FOLLOW_UP", title = "Follow up",
                entities = listOf(
                    // No TABLE_COLUMN at all, so this entity renders as one headed block per row —
                    // the path `rowHeading` titles.
                    EntityDto(
                        key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                        labelField = "productRef",
                        fields = listOf(
                            refField("productRef", "Product", "DwFinalProduct", "KEY_VALUE"),
                            textField("note", "Note"),
                        )
                    )
                )
            ),
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "FINAL_PRODUCTS" to StageDraft(
                    stageId = "FINAL_PRODUCTS",
                    rows = listOf(
                        DraftRow(
                            id = "finalProduct#row-1",
                            values = mapOf(
                                "_entryId" to JsonPrimitive(productId),
                                "name" to JsonPrimitive("Bandha table runner"),
                            )
                        )
                    )
                ),
                "FOLLOW_UP" to StageDraft(
                    stageId = "FOLLOW_UP",
                    rows = listOf(
                        DraftRow(
                            id = "followUp#row-1",
                            values = mapOf(
                                "productRef" to JsonPrimitive(productId),
                                "note" to JsonPrimitive("Reordered in March."),
                            )
                        )
                    )
                ),
            )
        )

        val headings = build(schema, draft).blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs) }
        assertTrue(
            "no heading names the product; headings were $headings",
            headings.any { it.contains("Bandha table runner") }
        )
        assertFalse("a heading printed a raw record id: $headings", headings.any { it.contains(productId) })
    }

    @Test
    fun `two rows referring to each other do not overflow the stack`() {
        // The data comes from a phone. A cycle here used to be a crash in the middle of generating
        // the one report a designer is standing in a cluster waiting for.
        val schema = schemaOf(
            StageDto(
                number = 16, key = "FINAL_PRODUCTS", title = "Final products",
                entities = listOf(
                    EntityDto(
                        key = "finalProduct", cardinality = "COLLECTION", title = "Final product",
                        labelField = "twinRef",
                        fields = listOf(
                            refField("twinRef", "Twin", "DwFinalProduct", "TABLE_COLUMN"),
                            textField("note", "Note", role = "TABLE_COLUMN"),
                        )
                    )
                )
            )
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "FINAL_PRODUCTS" to StageDraft(
                    stageId = "FINAL_PRODUCTS",
                    rows = listOf(
                        DraftRow(
                            id = "finalProduct#a",
                            values = mapOf("_entryId" to JsonPrimitive("aaa"), "twinRef" to JsonPrimitive("bbb"))
                        ),
                        DraftRow(
                            id = "finalProduct#b",
                            values = mapOf("_entryId" to JsonPrimitive("bbb"), "twinRef" to JsonPrimitive("aaa"))
                        ),
                    )
                )
            )
        )

        val document = build(schema, draft)   // must simply return
        assertEquals(1, tables(document).size)
    }

    // ── The two defects together ─────────────────────────────────────────────────────────────────

    @Test
    fun `a stage carrying only prose still produces a report with the prose in it`() {
        // The shape that made the bug invisible: the stage is not empty (so it is not skipped and its
        // heading prints), every value in it is RICH_TEXT, and the document that came out held the
        // heading and nothing else. A reader saw a section title with a blank page under it.
        val schema = schemaOf(
            StageDto(
                number = 2, key = "BACKGROUND", title = "Cluster background",
                entities = listOf(
                    EntityDto(
                        key = "background", cardinality = "SINGLETON", title = "Background",
                        fields = listOf(
                            richField("history", "History", "NARRATIVE"),
                            richField("problems", "Problems faced", "BULLETS"),
                        )
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "BACKGROUND",
                values = mapOf(
                    "history" to rich("PARAGRAPH" to "Weaving here is recorded from the 1920s."),
                    "problems" to rich("BULLET_ITEM" to "Yarn supply is irregular."),
                )
            )
        )

        val printed = printedText(document)
        assertTrue(printed.contains("Weaving here is recorded from the 1920s."))
        assertTrue(printed.contains("Yarn supply is irregular."))
        assertTrue(
            "the stage heading should still print",
            document.blocks.filterIsInstance<HeadingBlock>().any { runText(it.runs).contains("Cluster background") }
        )
    }
}
