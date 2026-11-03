package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwTier
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.MetricRowBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.Presentation
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.ReportMeta
import com.designprototype.workshop.report.ReportTemplate
import com.designprototype.workshop.report.ReportTheme
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TemplateSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLossWarningsTest {

    private fun galleryField(key: String, label: String, tier: String = "BASIC") =
        FieldDto(key = key, label = label, type = "IMAGE_LIST", tier = tier, reportRole = "GALLERY")

    private fun singleImageField(key: String, label: String, tier: String = "BASIC") =
        FieldDto(key = key, label = label, type = "IMAGE", tier = tier, reportRole = "GALLERY")

    private fun attachmentField(
        key: String,
        label: String,
        type: String,
        tier: String = "BASIC",
        role: String = "KEY_VALUE",
    ) = FieldDto(key = key, label = label, type = type, tier = tier, reportRole = role)

    private fun ids(vararg values: String): JsonElement =
        JsonArray(values.map { JsonPrimitive(it) })

    private fun schemaOf(vararg stages: StageDto) =
        SchemaResponse(version = "test", stages = stages.toList())

    private fun singletonStage(number: Int, key: String, title: String, vararg fields: FieldDto) =
        StageDto(
            number = number, key = key, title = title,
            entities = listOf(
                EntityDto(
                    key = key.lowercase() + "Singleton", cardinality = "SINGLETON", title = title,
                    fields = fields.toList(),
                )
            )
        )

    private fun collectionStage(number: Int, key: String, title: String, vararg fields: FieldDto) =
        StageDto(
            number = number, key = key, title = title,
            entities = listOf(
                EntityDto(
                    key = "piece", cardinality = "COLLECTION", title = "Pieces",
                    fields = fields.toList(),
                )
            )
        )

    private fun draftOf(vararg stages: Pair<String, StageDraft>) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = stages.toMap(),
    )

    private fun stageValues(stageKey: String, values: Map<String, JsonElement>) =
        stageKey to StageDraft(stageId = stageKey, values = values)

    private fun stageRows(stageKey: String, rows: List<DraftRow>) =
        stageKey to StageDraft(stageId = stageKey, rows = rows)

    private fun templateOf(
        vararg sections: TemplateSection,
        name: String = "Compact summary",
        maxTier: DwTier = DwTier.ADVANCED,
    ) = ReportTemplate(
        id = "TEST_TEMPLATE",
        name = name,
        description = "A template that exists to have a cap.",
        sections = sections.toList(),
        maxTier = maxTier,
    )

    private fun planFor(template: ReportTemplate) = ReportPlan(
        template = template,
        meta = ReportMeta(
            title = "Barpali cluster",
            templateId = template.id,
            templateName = template.name,
            generatedAt = "2026-02-10T09:00:00Z",
        ),
        theme = ReportTheme(),
        settings = emptyMap(),
        warnings = emptyList(),
    )

    private val everyImageResolves: (String) -> ImageRef? =
        { id -> ImageRef(source = "/dev/null/$id", widthPx = 800, heightPx = 600) }

    private val NEVER_CALLED = listOf("onReportLosses was never called")

    private fun build(
        schema: SchemaResponse,
        draft: WorkshopDraft,
        template: ReportTemplate,
        imageFor: (String) -> ImageRef? = everyImageResolves,
    ): Pair<ReportDocument, List<String>> {
        var notes: List<String> = NEVER_CALLED
        val document = buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = template.id,
            warnings = emptyList(),
            accent = "",
            imageFor = imageFor,
            plan = planFor(template),
            generatedAt = "2026-02-10T09:00:00Z",
            onReportLosses = { it -> notes = it },
        )
        return document to notes
    }

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is BulletListBlock -> block.items.joinToString("\n") { runText(it) }
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        is MetricRowBlock -> block.metrics.joinToString("\n") { (label, value, unit) -> "$label: $value $unit".trim() }
        else -> ""
    }

    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    private fun cappedSingletonSchema() = schemaOf(
        singletonStage(
            13, "FINAL_PROTOTYPE_DOCUMENTATION", "Final prototype documentation",
            galleryField("photos", "Product photographs"),
        )
    )

    private fun fivePhotographs() = draftOf(
        stageValues(
            "FINAL_PROTOTYPE_DOCUMENTATION",
            mapOf("photos" to ids("m1", "m2", "m3", "m4", "m5")),
        )
    )

    @Test
    fun `photographs the cap kept out are counted, named and attributed to their stage`() {
        val (_, notes) = build(
            cappedSingletonSchema(),
            fivePhotographs(),
            templateOf(
                TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION", maxPhotos = 2),
            ),
        )

        assertEquals(
            listOf(
                "3 photographs recorded in this workshop did not fit Compact summary's " +
                    "photograph cap and are not in this file — stage 13. Generate the report with " +
                    "a template that prints every photograph to include them."
            ),
            notes,
        )
    }

    @Test
    fun `a template with no cap loses nothing and says nothing, and the callback still fires`() {
        val (_, notes) = build(
            cappedSingletonSchema(),
            fivePhotographs(),
            templateOf(TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION")),
        )

        assertEquals(emptyList<String>(), notes)
    }

    @Test
    fun `the cap sentence is never written into the document`() {
        val (document, notes) = build(
            cappedSingletonSchema(),
            fivePhotographs(),
            templateOf(
                TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION", maxPhotos = 2),
            ),
        )
        val printed = printedText(document)

        assertEquals(1, notes.size)
        assertFalse("the cap warning leaked into the document:\n$printed", printed.contains("did not fit"))
        assertFalse(printed.contains("photograph cap"))
        assertFalse(document.warnings.any { it.contains("photograph cap") })
    }

    @Test
    fun `the pooled gallery plate counts what its cap dropped too`() {
        val schema = schemaOf(
            collectionStage(
                4, "CLUSTER_CRAFT_BACKGROUND", "Cluster and craft background",
                singleImageField("photo", "Motif"),
            )
        )
        val draft = draftOf(
            stageRows(
                "CLUSTER_CRAFT_BACKGROUND",
                listOf(
                    DraftRow(id = "piece#r1", values = mapOf("photo" to JsonPrimitive("m1"))),
                    DraftRow(id = "piece#r2", values = mapOf("photo" to JsonPrimitive("m2"))),
                    DraftRow(id = "piece#r3", values = mapOf("photo" to JsonPrimitive("m3"))),
                    DraftRow(id = "piece#r4", values = mapOf("photo" to JsonPrimitive("m4"))),
                ),
            )
        )

        val (_, notes) = build(
            schema, draft,
            templateOf(
                TemplateSection(
                    stageKey = "CLUSTER_CRAFT_BACKGROUND",
                    presentation = Presentation.GALLERY, maxPhotos = 2,
                ),
            ),
        )

        assertEquals(1, notes.size)
        assertTrue(notes[0], notes[0].startsWith("2 photographs recorded in this workshop"))
        assertTrue(notes[0], notes[0].contains("— stage 4."))
    }

    @Test
    fun `two capped stages are named in registry order, not in the order the template prints them`() {
        val schema = schemaOf(
            singletonStage(4, "CLUSTER_CRAFT_BACKGROUND", "Cluster", galleryField("photos", "Motifs")),
            singletonStage(13, "FINAL_PROTOTYPE_DOCUMENTATION", "Prototypes", galleryField("photos", "Products")),
        )
        val draft = draftOf(
            stageValues("CLUSTER_CRAFT_BACKGROUND", mapOf("photos" to ids("a1", "a2", "a3"))),
            stageValues("FINAL_PROTOTYPE_DOCUMENTATION", mapOf("photos" to ids("b1", "b2"))),
        )

        val (_, notes) = build(
            schema, draft,
            templateOf(
                TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION", maxPhotos = 1),
                TemplateSection(stageKey = "CLUSTER_CRAFT_BACKGROUND", maxPhotos = 1),
            ),
        )

        assertEquals(1, notes.size)
        assertTrue(notes[0], notes[0].startsWith("3 photographs"))
        assertTrue(notes[0], notes[0].contains("— stage 4, stage 13."))
    }

    private fun stagesOnly(vararg numbered: Pair<Int, String>) =
        schemaOf(*numbered.map { (n, k) -> singletonStage(n, k, k) }.toTypedArray())

    @Test
    fun `the tally sums per stage and ignores a count of none`() {
        val schema = stagesOnly(4 to "A", 13 to "B")
        val losses = DwReportLosses()

        losses.stageKey = "A"
        losses.notePhotographsOverCap(2)
        losses.notePhotographsOverCap(0)
        losses.notePhotographsOverCap(3)
        losses.stageKey = "B"
        losses.notePhotographsOverCap(1)

        assertEquals(
            listOf("A" to 2 + 3, "B" to 1),
            losses.photographsOverCap(schema).map { (stage, count) -> stage.key to count },
        )
    }

    @Test
    fun `a loss with no stage set is filed nowhere rather than against the wrong stage`() {
        val schema = stagesOnly(4 to "A")
        val losses = DwReportLosses()

        losses.notePhotographsOverCap(4)

        assertTrue(losses.photographsOverCap(schema).isEmpty())
    }

    @Test
    fun `a negative count cannot create a stage entry`() {
        val schema = stagesOnly(4 to "A")
        val losses = DwReportLosses()
        losses.stageKey = "A"
        losses.notePhotographsOverCap(-1)
        assertTrue(losses.photographsOverCap(schema).isEmpty())
    }

    @Test
    fun `no photographs dropped is null and not an empty bullet`() {
        assertNull(dwPhotographsOverCapNote(templateOf(), emptyList()))
    }

    @Test
    fun `more than four capped stages are elided exactly as the server elides them`() {
        val counted = listOf(1, 2, 3, 4, 5).map {
            singletonStage(it, "S$it", "Stage $it") to it
        }

        val note = dwPhotographsOverCapNote(templateOf(name = "Compact summary"), counted)

        assertEquals(
            "15 photographs recorded in this workshop did not fit Compact summary's photograph " +
                "cap and are not in this file — stage 1, stage 2, stage 3, stage 4…. Generate the " +
                "report with a template that prints every photograph to include them.",
            note,
        )
    }

    @Test
    fun `every named file, recording and video is counted, once per file`() {
        val schema = schemaOf(
            singletonStage(
                1, "WORKSHOP_SETUP", "Workshop setup",
                attachmentField("sanctionDocument", "Sanction order", "FILE"),
                attachmentField("openingRecording", "Opening remarks", "AUDIO"),
                attachmentField("processVideo", "Process video", "VIDEO"),
            )
        )
        val draft = draftOf(
            stageValues(
                "WORKSHOP_SETUP",
                mapOf(
                    "sanctionDocument" to ids("f1", "f2"),
                    "openingRecording" to JsonPrimitive("a1"),
                    "processVideo" to ids("v1"),
                ),
            )
        )
        val template = templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP"))

        assertEquals(
            listOf("WORKSHOP_SETUP" to 4),
            dwAttachmentsNamedButNotCarried(schema, draft, template)
                .map { (stage, count) -> stage.key to count },
        )

        assertEquals(
            "4 attached files are named in this report but the files themselves are not inside " +
                "it — stage 1. A report file cannot carry a document, a recording or a video; send " +
                "them alongside it.",
            dwAttachmentsNotCarriedNote(dwAttachmentsNamedButNotCarried(schema, draft, template)),
        )
    }

    @Test
    fun `photographs are not counted, because photographs are the two types that ARE carried`() {
        val schema = schemaOf(
            singletonStage(
                13, "FINAL_PROTOTYPE_DOCUMENTATION", "Prototypes",
                galleryField("photos", "Product photographs"),
                singleImageField("hero", "Hero shot"),
            )
        )
        val draft = draftOf(
            stageValues(
                "FINAL_PROTOTYPE_DOCUMENTATION",
                mapOf("photos" to ids("m1", "m2"), "hero" to JsonPrimitive("m3")),
            )
        )

        assertTrue(
            dwAttachmentsNamedButNotCarried(
                schema, draft, templateOf(TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION")),
            ).isEmpty()
        )
    }

    @Test
    fun `a HIDDEN attachment is not counted, because it is nowhere in the document to be about`() {
        val schema = schemaOf(
            singletonStage(
                1, "WORKSHOP_SETUP", "Workshop setup",
                attachmentField("internalScan", "Internal scan", "FILE", role = "HIDDEN"),
            )
        )
        val draft = draftOf(stageValues("WORKSHOP_SETUP", mapOf("internalScan" to ids("f1"))))

        assertTrue(
            dwAttachmentsNamedButNotCarried(
                schema, draft, templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP")),
            ).isEmpty()
        )
    }

    @Test
    fun `an attachment above the template's capture tier is left to the tier warning`() {
        val schema = schemaOf(
            singletonStage(
                1, "WORKSHOP_SETUP", "Workshop setup",
                attachmentField("sanctionDocument", "Sanction order", "FILE", tier = "ADVANCED"),
            )
        )
        val draft = draftOf(stageValues("WORKSHOP_SETUP", mapOf("sanctionDocument" to ids("f1"))))

        assertTrue(
            dwAttachmentsNamedButNotCarried(
                schema, draft,
                templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP"), maxTier = DwTier.BASIC),
            ).isEmpty()
        )
        assertEquals(
            1,
            dwAttachmentsNamedButNotCarried(
                schema, draft,
                templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP"), maxTier = DwTier.ADVANCED),
            ).single().second,
        )
    }

    @Test
    fun `a stage the template does not print names nothing`() {
        val schema = schemaOf(
            singletonStage(1, "WORKSHOP_SETUP", "Setup", attachmentField("doc", "Order", "FILE")),
            singletonStage(5, "MARKET_ANALYSIS_DIRECTION", "Market", attachmentField("tape", "Interview", "AUDIO")),
        )
        val draft = draftOf(
            stageValues("WORKSHOP_SETUP", mapOf("doc" to ids("f1"))),
            stageValues("MARKET_ANALYSIS_DIRECTION", mapOf("tape" to ids("a1", "a2"))),
        )

        assertEquals(
            listOf("WORKSHOP_SETUP" to 1),
            dwAttachmentsNamedButNotCarried(
                schema, draft, templateOf(TemplateSection(stageKey = "WORKSHOP_SETUP")),
            ).map { (stage, count) -> stage.key to count },
        )
    }

    @Test
    fun `attachments on a collection are counted across every row`() {
        val schema = schemaOf(
            collectionStage(
                14, "PROTOTYPE_ITERATIONS", "Iterations",
                attachmentField("clip", "Process clip", "VIDEO"),
            )
        )
        val draft = draftOf(
            stageRows(
                "PROTOTYPE_ITERATIONS",
                listOf(
                    DraftRow(id = "piece#r1", values = mapOf("clip" to ids("v1"))),
                    DraftRow(id = "piece#r2", values = mapOf("clip" to ids("v2", "v3"))),
                    DraftRow(id = "piece#r3", values = emptyMap()),
                ),
            )
        )

        assertEquals(
            3,
            dwAttachmentsNamedButNotCarried(
                schema, draft, templateOf(TemplateSection(stageKey = "PROTOTYPE_ITERATIONS")),
            ).single().second,
        )
    }

    @Test
    fun `nothing attached is null and not an empty bullet`() {
        assertNull(dwAttachmentsNotCarriedNote(emptyList()))
    }

    @Test
    fun `more than four stages of attachments are elided exactly as the server elides them`() {
        val counted = listOf(1, 2, 3, 4, 5).map { singletonStage(it, "S$it", "Stage $it") to 1 }

        assertEquals(
            "5 attached files are named in this report but the files themselves are not inside " +
                "it — stage 1, stage 2, stage 3, stage 4…. A report file cannot carry a document, " +
                "a recording or a video; send them alongside it.",
            dwAttachmentsNotCarriedNote(counted),
        )
    }

    @Test
    fun `both losses reach the caller in build_report's order and neither reaches the file`() {
        val schema = schemaOf(
            singletonStage(
                13, "FINAL_PROTOTYPE_DOCUMENTATION", "Final prototype documentation",
                galleryField("photos", "Product photographs"),
                attachmentField("spec", "Specification sheet", "FILE"),
            )
        )
        val draft = draftOf(
            stageValues(
                "FINAL_PROTOTYPE_DOCUMENTATION",
                mapOf("photos" to ids("m1", "m2", "m3"), "spec" to ids("f1")),
            )
        )

        val (document, notes) = build(
            schema, draft,
            templateOf(TemplateSection(stageKey = "FINAL_PROTOTYPE_DOCUMENTATION", maxPhotos = 1)),
        )

        assertEquals(2, notes.size)
        assertTrue(notes[0], notes[0].contains("photograph cap"))
        assertTrue(notes[1], notes[1].contains("named in this report but the"))

        val printed = printedText(document)
        assertTrue("the attachment is not named in the file:\n$printed", printed.contains("1 document attached"))
        assertFalse(printed.contains("send them alongside it"))
        assertFalse(printed.contains("did not fit"))
    }
}
