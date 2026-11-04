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
import com.designprototype.workshop.report.ImageBlock
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.MetricRowBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportEntityParityTest {

    private fun richField(key: String, label: String, role: String, required: Boolean = false) =
        FieldDto(key = key, label = label, type = "RICH_TEXT", reportRole = role, required = required)

    private fun textField(key: String, label: String, role: String = "KEY_VALUE", required: Boolean = false) =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = role, required = required)

    private fun imageField(key: String, label: String, required: Boolean = false) =
        FieldDto(
            key = key, label = label, type = "IMAGE",
            reportRole = "GALLERY", required = required,
        )

    private fun refField(
        key: String,
        label: String,
        model: String,
        role: String = "KEY_VALUE",
        hydration: Map<String, String> = emptyMap(),
    ) = FieldDto(
        key = key, label = label, type = "REF", refModel = model, reportRole = role,
        refHydration = hydration,
    )

    private fun attachmentField(key: String, label: String, type: String, required: Boolean = false) =
        FieldDto(key = key, label = label, type = type, reportRole = "KEY_VALUE", required = required)

    private fun metricField(key: String, label: String, unit: String = "") =
        FieldDto(key = key, label = label, type = "INT", reportRole = "METRIC", unit = unit)

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

    private fun schemaOf(vararg stages: StageDto) =
        SchemaResponse(version = "test", stages = stages.toList())

    private fun draftOf(
        stageKey: String,
        values: Map<String, JsonElement> = emptyMap(),
        rows: List<DraftRow> = emptyList(),
        extra: Map<String, StageDraft> = emptyMap(),
    ) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(stageKey to StageDraft(stageId = stageKey, values = values, rows = rows)) + extra,
    )

    private val everyImageResolves: (String) -> ImageRef? =
        { id -> ImageRef(source = "/dev/null/$id", widthPx = 800, heightPx = 600) }

    private fun build(
        schema: SchemaResponse,
        draft: WorkshopDraft,
        templateId: String = "DCH_STANDARD",
        imageFor: (String) -> ImageRef? = { null },
    ): ReportDocument = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = draft.workshopId,
        templateId = templateId,
        warnings = emptyList(),
        accent = "",
        imageFor = imageFor,
        generatedAt = "2026-02-10T09:00:00Z",
    )

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

    private fun headings(document: ReportDocument): List<String> =
        document.blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs).trim() }

    private fun shape(document: ReportDocument): List<String> =
        document.blocks.map { it::class.simpleName.orEmpty() }

    private fun indexOfFirst(document: ReportDocument, kind: String): Int =
        shape(document).indexOfFirst { it == kind }

    private fun stageTwoSchema() = schemaOf(
        StageDto(
            number = 2, key = "INTRODUCTORY_ADMIN_DOCUMENTATION",
            title = "Introductory & Administrative Documentation",
            entities = listOf(
                EntityDto(
                    key = "introduction", cardinality = "SINGLETON", title = "Introduction",
                    fields = listOf(
                        richField("acknowledgement", "Acknowledgement", "NARRATIVE"),
                        richField("purpose", "Purpose", "NARRATIVE"),
                        richField("programmeObjectives", "Programme objectives", "BULLETS"),
                    ),
                )
            )
        )
    )

    @Test
    fun `every narrative answer prints under its own field label`() {
        val document = build(
            stageTwoSchema(),
            draftOf(
                "INTRODUCTORY_ADMIN_DOCUMENTATION",
                values = mapOf(
                    "acknowledgement" to rich("PARAGRAPH" to "The weavers gave a fortnight."),
                    "purpose" to rich("PARAGRAPH" to "To revive the bandha tradition."),
                ),
            ),
        )
        val printed = printedText(document)

        assertTrue("the acknowledgement is unlabelled:\n$printed", printed.contains("Acknowledgement:"))
        assertTrue("the purpose is unlabelled:\n$printed", printed.contains("Purpose:"))
        assertTrue(printed.contains("The weavers gave a fortnight."))
        assertTrue(printed.contains("To revive the bandha tradition."))
    }

    @Test
    fun `a long narrative answer takes a sub-heading instead of a lead-in`() {
        val long = "The cluster's weavers gave a fortnight of their time to this workshop, and the " +
            "opening session ran over by two hours because every household wanted to show the " +
            "sarees they had woven for the last co-operative order."
        assertTrue("the fixture must exceed the 160-character rule", long.length > 160)

        val document = build(
            stageTwoSchema(),
            draftOf(
                "INTRODUCTORY_ADMIN_DOCUMENTATION",
                values = mapOf("acknowledgement" to rich("PARAGRAPH" to long)),
            ),
        )

        assertTrue(
            "a long answer must head its own sub-section: ${headings(document)}",
            headings(document).contains("Acknowledgement"),
        )
        assertFalse(
            "and must NOT also carry the short form's lead-in",
            printedText(document).contains("Acknowledgement:"),
        )
    }

    @Test
    fun `a bulleted answer is always headed, however short it is`() {
        val document = build(
            stageTwoSchema(),
            draftOf(
                "INTRODUCTORY_ADMIN_DOCUMENTATION",
                values = mapOf(
                    "programmeObjectives" to rich(
                        "BULLET_ITEM" to "Re-establish the natural indigo vat",
                        "BULLET_ITEM" to "Link the cluster to two export buyers",
                    )
                ),
            ),
        )

        assertTrue(
            "the bulleted answer is unheaded: ${headings(document)}",
            headings(document).contains("Programme objectives"),
        )
        assertEquals(
            listOf("Re-establish the natural indigo vat", "Link the cluster to two export buyers"),
            document.blocks.filterIsInstance<BulletListBlock>().single().items.map(::runText),
        )
    }

    @Test
    fun `an unfilled required field prints Not recorded and an unfilled optional one prints nothing`() {
        val schema = schemaOf(
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "detail", cardinality = "SINGLETON", title = "Prototype development",
                        fields = listOf(
                            textField("materials", "Materials", required = true),
                            textField("remarks", "Remarks"),
                            textField("supervisor", "Supervisor"),
                        ),
                    )
                )
            )
        )
        val printed = printedText(
            build(
                schema,
                draftOf("PROTOTYPE_DEVELOPMENT", values = mapOf("supervisor" to JsonPrimitive("B. Meher"))),
            )
        )

        assertTrue("the required gap is invisible:\n$printed", printed.contains("Materials: Not recorded."))
        assertFalse("an optional gap must stay silent:\n$printed", printed.contains("Remarks"))
        assertTrue(printed.contains("Supervisor: B. Meher"))
    }

    @Test
    fun `an unfilled required TABLE_COLUMN prints the note in its cell, not a blank`() {
        val schema = schemaOf(
            StageDto(
                number = 22, key = "FOLLOW_UP", title = "Follow up",
                entities = listOf(
                    EntityDto(
                        key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Code", role = "TABLE_COLUMN"),
                            textField("materials", "Materials", role = "TABLE_COLUMN", required = true),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "FOLLOW_UP",
                rows = listOf(
                    DraftRow(id = "followUp#row-1", values = mapOf("code" to JsonPrimitive("PT-01"))),
                ),
            ),
        )

        val table = document.blocks.filterIsInstance<TableBlock>().single()
        assertEquals(
            listOf("PT-01", "Not recorded."),
            table.rows.single().map(::runText),
        )
    }

    @Test
    fun `an unfilled required rich-text field is not swallowed by the empty-document check`() {
        val schema = schemaOf(
            StageDto(
                number = 2, key = "INTRODUCTORY_ADMIN_DOCUMENTATION", title = "Introduction",
                entities = listOf(
                    EntityDto(
                        key = "introduction", cardinality = "SINGLETON", title = "Introduction",
                        fields = listOf(
                            richField("purpose", "Purpose", "NARRATIVE", required = true),
                            textField("agency", "Agency"),
                        ),
                    )
                )
            )
        )
        val printed = printedText(
            build(
                schema,
                draftOf(
                    "INTRODUCTORY_ADMIN_DOCUMENTATION",
                    values = mapOf(
                        "purpose" to rich("PARAGRAPH" to ""),
                        "agency" to JsonPrimitive("Sambalpuri Bastralaya"),
                    ),
                ),
            )
        )

        assertTrue("the required narrative gap is invisible:\n$printed", printed.contains("Purpose: Not recorded."))
    }

    @Test
    fun `a stage singleton prints grid, then prose, then bullets, then metrics, then photographs`() {
        val schema = schemaOf(
            StageDto(
                number = 6, key = "EXISTING_PRODUCTS_BASELINE", title = "Baseline",
                entities = listOf(
                    EntityDto(
                        key = "baseline", cardinality = "SINGLETON", title = "Baseline",
                        fields = listOf(
                            richField("background", "Cluster background", "NARRATIVE"),
                            richField("gaps", "Gaps observed", "BULLETS"),
                            metricField("looms", "Looms in use"),
                            textField("village", "Village"),
                            imageField("photo", "Baseline photograph"),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "EXISTING_PRODUCTS_BASELINE",
                values = mapOf(
                    "background" to rich("PARAGRAPH" to "Fourteen households weave for the co-operative."),
                    "gaps" to rich("BULLET_ITEM" to "No natural dye vat"),
                    "looms" to JsonPrimitive(14),
                    "village" to JsonPrimitive("Barpali"),
                    "photo" to JsonPrimitive("media-1"),
                ),
            ),
            imageFor = everyImageResolves,
        )

        val grid = indexOfFirst(document, "KeyValueBlock")
        val prose = document.blocks.indexOfFirst { it is ParagraphBlock && textOf(it).startsWith("Cluster background") }
        val bullets = indexOfFirst(document, "BulletListBlock")
        val metrics = indexOfFirst(document, "MetricRowBlock")
        val photo = indexOfFirst(document, "ImageBlock")

        assertTrue("every block must be present: ${shape(document)}", listOf(grid, prose, bullets, metrics, photo).none { it < 0 })
        assertTrue("the key-value grid comes first: ${shape(document)}", grid < prose)
        assertTrue("then the prose: ${shape(document)}", prose < bullets)
        assertTrue("then the bullets: ${shape(document)}", bullets < metrics)
        assertTrue("then the metrics, and the photographs last: ${shape(document)}", metrics < photo)
    }

    @Test
    fun `a card puts its photographs under the heading and before its fields`() {
        val schema = schemaOf(
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", cardinality = "COLLECTION", title = "Prototype",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Code"),
                            richField("processSummary", "Process summary", "NARRATIVE"),
                            imageField("photo", "Prototype photograph"),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "PROTOTYPE_DEVELOPMENT",
                rows = listOf(
                    DraftRow(
                        id = "prototype#row-1",
                        values = mapOf(
                            "code" to JsonPrimitive("PT-01"),
                            "processSummary" to rich("PARAGRAPH" to "Woven on loom 14 over three days."),
                            "photo" to JsonPrimitive("media-1"),
                        ),
                    )
                ),
            ),
            imageFor = everyImageResolves,
        )

        val photo = indexOfFirst(document, "ImageBlock")
        val grid = indexOfFirst(document, "KeyValueBlock")
        assertTrue("the card must carry both: ${shape(document)}", photo >= 0 && grid >= 0)
        assertTrue("a card's photograph goes first: ${shape(document)}", photo < grid)
    }

    private fun sevenColumnSchema() = schemaOf(
        StageDto(
            number = 22, key = "FOLLOW_UP", title = "Follow up",
            entities = listOf(
                EntityDto(
                    key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                    labelField = "product",
                    fields = (1..7).map { n ->
                        textField("col$n", "Column $n", role = "TABLE_COLUMN")
                    },
                )
            )
        )
    )

    private fun sevenColumnRow() = DraftRow(
        id = "followUp#row-1",
        values = (1..7).associate { n -> "col$n" to (JsonPrimitive("value $n") as JsonElement) },
    )

    @Test
    fun `a table is capped at six columns, as the server caps it`() {
        val document = build(sevenColumnSchema(), draftOf("FOLLOW_UP", rows = listOf(sevenColumnRow())))
        val table = document.blocks.filterIsInstance<TableBlock>().single()

        assertEquals("`_table_columns` returns columns[:6]", 6, table.columns.size)
        assertEquals(
            (1..6).map { "Column $it" },
            table.columns.map { it.header },
        )
    }

    @Test
    fun `the seventh column is carried under the row rather than dropped`() {
        val printed = printedText(
            build(sevenColumnSchema(), draftOf("FOLLOW_UP", rows = listOf(sevenColumnRow())))
        )

        assertTrue("the overflow column is missing from the file:\n$printed", printed.contains("Column 7: value 7"))
        assertFalse("a drawn column must not print twice:\n$printed", printed.contains("Column 1: value 1"))
    }

    @Test
    fun `a media TABLE_COLUMN prints as a photograph instead of vanishing`() {
        val schema = schemaOf(
            StageDto(
                number = 22, key = "FOLLOW_UP", title = "Follow up",
                entities = listOf(
                    EntityDto(
                        key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                        labelField = "product",
                        fields = listOf(
                            textField("product", "Product", role = "TABLE_COLUMN"),
                            FieldDto(
                                key = "shot", label = "Shop shelf", type = "IMAGE",
                                reportRole = "TABLE_COLUMN",
                            ),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "FOLLOW_UP",
                rows = listOf(
                    DraftRow(
                        id = "followUp#row-1",
                        values = mapOf(
                            "product" to JsonPrimitive("Bandha runner"),
                            "shot" to JsonPrimitive("media-1"),
                        ),
                    )
                ),
            ),
            imageFor = everyImageResolves,
        )

        assertEquals(1, document.blocks.filterIsInstance<TableBlock>().single().columns.size)
        assertTrue(
            "the media column vanished from the document: ${shape(document)}",
            document.blocks.any { it is ImageBlock || it is ImageGridBlock },
        )
    }

    @Test
    fun `a record with no photograph of its own borrows the one on the record it references`() {
        val schema = schemaOf(
            StageDto(
                number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development",
                entities = listOf(
                    EntityDto(
                        key = "sketch", cardinality = "COLLECTION", title = "Sketch",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Sketch code"),
                            imageField("sketchImage", "Sketch"),
                        ),
                    )
                )
            ),
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", cardinality = "COLLECTION", title = "Prototype",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Code"),
                            refField("sketchRef", "Sketch", "DwSketch"),
                        ),
                    )
                )
            ),
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "SKETCH_DEVELOPMENT" to StageDraft(
                    stageId = "SKETCH_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "sketch#kzzq1p8n4m2wd7xr",
                            values = mapOf(
                                "code" to JsonPrimitive("SK-04"),
                                "sketchImage" to JsonPrimitive("media-sketch-4"),
                            ),
                        )
                    ),
                ),
                "PROTOTYPE_DEVELOPMENT" to StageDraft(
                    stageId = "PROTOTYPE_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "prototype#row-1",
                            values = mapOf(
                                "code" to JsonPrimitive("PT-01"),
                                "sketchRef" to JsonPrimitive("kzzq1p8n4m2wd7xr"),
                            ),
                        )
                    ),
                ),
            ),
        )

        val document = build(schema, draft, imageFor = everyImageResolves)
        val borrowed = document.blocks.filterIsInstance<ImageBlock>()
            .filter { it.image.source.endsWith("media-sketch-4") }

        assertEquals(
            "the prototype printed no picture at all: ${shape(document)}",
            2,
            borrowed.size,
        )
        assertTrue(
            "the borrowed plate must be captioned with the referenced record: ${borrowed.map { it.caption }}",
            borrowed.any { it.caption == "SK-04" },
        )
    }

    @Test
    fun `a photograph already on the row is not printed twice by the reference pass`() {
        val schema = schemaOf(
            StageDto(
                number = 3, key = "WORKSHOP_PLAN_PARTICIPANTS_OPENING", title = "Participants",
                entities = listOf(
                    EntityDto(
                        key = "roster", cardinality = "COLLECTION", title = "Roster entry",
                        labelField = "who",
                        fields = listOf(
                            textField("who", "Name"),
                            imageField("photo", "Photograph"),
                        ),
                    ),
                    EntityDto(
                        key = "participant", cardinality = "COLLECTION", title = "Participant",
                        labelField = "name",
                        fields = listOf(
                            textField("name", "Name"),
                            imageField("photo", "Photograph"),
                            refField("artisanRef", "Artisan", "Artisan"),
                        ),
                    ),
                )
            )
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "WORKSHOP_PLAN_PARTICIPANTS_OPENING" to StageDraft(
                    stageId = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                    rows = listOf(
                        DraftRow(
                            id = "roster#kzzq1p8n4m2wd7xr",
                            values = mapOf(
                                "who" to JsonPrimitive("Bhikari Meher"),
                                "photo" to JsonPrimitive("media-artisan-1"),
                            ),
                        ),
                        DraftRow(
                            id = "participant#row-1",
                            values = mapOf(
                                "name" to JsonPrimitive("Bhikari Meher"),
                                "photo" to JsonPrimitive("media-artisan-1"),
                                "artisanRef" to JsonPrimitive("kzzq1p8n4m2wd7xr"),
                            ),
                        ),
                    ),
                )
            ),
        )

        val document = build(schema, draft, imageFor = everyImageResolves)
        val onTheParticipantCard = document.blocks
            .filterIsInstance<ImageBlock>()
            .count { it.image.source.endsWith("media-artisan-1") }

        assertEquals(
            "the reference pass duplicated a picture the row already carried: ${shape(document)}",
            2,
            onTheParticipantCard,
        )
    }


    @Test
    fun `a document, a recording and a video print a count and a noun where a photograph prints neither`() {
        val schema = schemaOf(
            StageDto(
                number = 1, key = "WORKSHOP_SETUP", title = "Setup",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Setup",
                        fields = listOf(
                            attachmentField("sanctionOrder", "Sanction order", "FILE"),
                            attachmentField("interviewClips", "Interview recordings", "AUDIO"),
                            attachmentField("processClip", "Process video", "VIDEO"),
                            attachmentField("consentForm", "Consent form", "FILE", required = true),
                            imageField("venuePhoto", "Venue photograph"),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "WORKSHOP_SETUP",
                values = mapOf(
                    "sanctionOrder" to JsonPrimitive("media-file-1"),
                    "interviewClips" to JsonArray(
                        listOf(JsonPrimitive("media-audio-1"), JsonPrimitive("media-audio-2"))
                    ),
                    "processClip" to JsonPrimitive("media-video-1"),
                    "venuePhoto" to JsonPrimitive("media-photo-1"),
                ),
            ),
            imageFor = everyImageResolves,
        )
        val printed = printedText(document)

        assertTrue("the sanction order is unmentioned:\n" + printed, printed.contains("Sanction order: 1 document attached"))
        assertTrue("the plural noun:\n" + printed, printed.contains("Interview recordings: 2 recordings attached"))
        assertTrue("VIDEO has its own noun:\n" + printed, printed.contains("Process video: 1 video attached"))

        assertFalse("a media id must not reach paper:\n" + printed, printed.contains("media-file-1"))
        assertFalse(printed.contains("media-audio-1"))

        assertFalse("a photograph must not print as text:\n" + printed, printed.contains("Venue photograph:"))
        assertTrue(
            "the photograph must still be placed: " + shape(document),
            document.blocks.any { it is ImageBlock || it is ImageGridBlock },
        )

        assertTrue("an unattached required document must be reported:\n" + printed, printed.contains("Consent form: Not recorded."))
    }

    @Test
    fun `a 3D model prints as a model and names the photographs of it on the same row`() {
        val schema = schemaOf(
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototypes",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Prototype",
                        fields = listOf(
                            attachmentField("modelFile", "3D model", "FILE"),
                            FieldDto(key = "turntablePhotos", label = "360° capture", type = "IMAGE_LIST", reportRole = "GALLERY"),
                            attachmentField("measurementSheet", "Measurement sheet", "FILE"),
                        ),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf(
                "PROTOTYPE_DEVELOPMENT",
                values = mapOf(
                    "modelFile" to JsonPrimitive("media-model-1"),
                    "turntablePhotos" to JsonArray(listOf(JsonPrimitive("f1"), JsonPrimitive("f2"))),
                    "measurementSheet" to JsonPrimitive("media-sheet-1"),
                ),
            ),
            imageFor = everyImageResolves,
        )
        val printed = printedText(document)
        assertTrue(printed, printed.contains("3D model: 1 3D model attached; 2 photographs of it under “360° capture”"))
        assertTrue(printed, printed.contains("Measurement sheet: 1 document attached"))
        assertFalse(printed, printed.contains("3D model: 1 document attached"))
    }

    @Test
    fun `a required photograph that was taken prints its plate and no note`() {
        val schema = schemaOf(
            StageDto(
                number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development",
                entities = listOf(
                    EntityDto(
                        key = "sketch", cardinality = "COLLECTION", title = "Sketch",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Sketch code", role = "TABLE_COLUMN"),
                            imageField("image", "Sketch image", required = true),
                        ),
                    )
                )
            )
        )
        val taken = build(
            schema,
            draftOf(
                "SKETCH_DEVELOPMENT",
                rows = listOf(
                    DraftRow(
                        id = "sketch#1",
                        values = mapOf(
                            "code" to JsonPrimitive("SK-01"),
                            "image" to JsonPrimitive("media-sketch-1"),
                        ),
                    )
                ),
            ),
            imageFor = everyImageResolves,
        )
        val printedTaken = printedText(taken)
        assertTrue(
            "the photograph was not placed at all: " + shape(taken),
            taken.blocks.any { it is ImageBlock || it is ImageGridBlock },
        )
        assertFalse(
            "an attached photograph is called missing beside itself:\n" + printedTaken,
            printedTaken.contains("Sketch image: Not recorded."),
        )
        assertFalse(
            "a GALLERY field reached the key-value grid:\n" + printedTaken,
            printedTaken.contains("Sketch image:"),
        )

        val notTaken = build(
            schemaOf(
                StageDto(
                    number = 22, key = "FOLLOW_UP", title = "Follow up",
                    entities = listOf(
                        EntityDto(
                            key = "followUp", cardinality = "COLLECTION", title = "Follow up",
                            labelField = "product",
                            fields = listOf(
                                textField("product", "Product", role = "TABLE_COLUMN"),
                                imageField("shelfPhoto", "Shop shelf", required = true),
                            ),
                        )
                    )
                )
            ),
            draftOf(
                "FOLLOW_UP",
                rows = listOf(
                    DraftRow(
                        id = "followUp#1",
                        values = mapOf("product" to JsonPrimitive("Bandha runner")),
                    ),
                ),
            ),
            imageFor = everyImageResolves,
        )
        val printedMissing = printedText(notTaken)
        assertTrue(
            "this half only means something on the table path: " + shape(notTaken),
            notTaken.blocks.any { it is TableBlock },
        )
        assertFalse(
            "the office prints no such note for a GALLERY field:\n" + printedMissing,
            printedMissing.contains("Shop shelf: Not recorded."),
        )
        assertFalse(
            "a sub-heading was drawn over a row with nothing left to print: " + headings(notTaken),
            headings(notTaken).any { it.contains("Bandha runner") },
        )
    }

    @Test
    fun `an attached document is never placed as a plate`() {
        val schema = schemaOf(
            StageDto(
                number = 1, key = "WORKSHOP_SETUP", title = "Setup",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Setup",
                        fields = listOf(attachmentField("sanctionOrder", "Sanction order", "FILE")),
                    )
                )
            )
        )
        val document = build(
            schema,
            draftOf("WORKSHOP_SETUP", values = mapOf("sanctionOrder" to JsonPrimitive("media-file-1"))),
            imageFor = everyImageResolves,
        )
        assertTrue(
            "a document was placed as a photograph: " + shape(document),
            document.blocks.none { it is ImageBlock || it is ImageGridBlock },
        )
        assertTrue(printedText(document).contains("Sanction order: 1 document attached"))
    }

    @Test
    fun `a borrowed photograph is captioned with the row's frozen name, not the live record`() {
        val schema = schemaOf(
            StageDto(
                number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development",
                entities = listOf(
                    EntityDto(
                        key = "sketch", cardinality = "COLLECTION", title = "Sketch",
                        labelField = "code",
                        fields = listOf(textField("code", "Sketch code"), imageField("sketchImage", "Sketch")),
                    )
                )
            ),
            StageDto(
                number = 13, key = "PROTOTYPE_DEVELOPMENT", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", cardinality = "COLLECTION", title = "Prototype",
                        labelField = "code",
                        fields = listOf(
                            textField("code", "Code"),
                            textField("sketchName", "Sketch"),
                            refField("sketchRef", "Sketch", "DwSketch", hydration = mapOf("name" to "sketchName")),
                        ),
                    )
                )
            ),
        )
        val draft = WorkshopDraft(
            workshopId = "local-test",
            stages = mapOf(
                "SKETCH_DEVELOPMENT" to StageDraft(
                    stageId = "SKETCH_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "sketch#kzzq1p8n4m2wd7xr",
                            values = mapOf(
                                "code" to JsonPrimitive("SK-04 revised 2027"),
                                "sketchImage" to JsonPrimitive("media-sketch-4"),
                            ),
                        )
                    ),
                ),
                "PROTOTYPE_DEVELOPMENT" to StageDraft(
                    stageId = "PROTOTYPE_DEVELOPMENT",
                    rows = listOf(
                        DraftRow(
                            id = "prototype#row-1",
                            values = mapOf(
                                "code" to JsonPrimitive("PT-01"),
                                "sketchName" to JsonPrimitive("SK-04"),
                                "sketchRef" to JsonPrimitive("kzzq1p8n4m2wd7xr"),
                            ),
                        )
                    ),
                ),
            ),
        )

        val borrowed = build(schema, draft, imageFor = everyImageResolves).blocks
            .filterIsInstance<ImageBlock>()
            .filter { it.image.source.endsWith("media-sketch-4") }
        assertTrue("the borrowed plate is missing", borrowed.isNotEmpty())
        assertTrue(
            "the caption re-resolved the live record: " + borrowed.map { it.caption },
            borrowed.any { it.caption == "SK-04" },
        )
        assertTrue(
            "no plate may carry the renamed live label: " + borrowed.map { it.caption },
            borrowed.none { it.caption == "SK-04 revised 2027" },
        )
    }

    @Test
    fun `a collection row gets no metric row, and a stage singleton still does`() {
        val schema = schemaOf(
            StageDto(
                number = 17, key = "COST_INTEGRITY", title = "Costing",
                entities = listOf(
                    EntityDto(
                        key = "costing", cardinality = "SINGLETON", title = "Costing",
                        fields = listOf(metricField("sheets", "Cost sheets")),
                    ),
                    EntityDto(
                        key = "costSheet", cardinality = "COLLECTION", title = "Cost sheet",
                        labelField = "name",
                        fields = listOf(textField("name", "Product"), metricField("units", "Units")),
                    ),
                )
            )
        )
        val singletonOnly = build(
            schema,
            draftOf("COST_INTEGRITY", values = mapOf("sheets" to JsonPrimitive(4))),
        )
        assertTrue(
            "the stage singleton must still get its metric row: " + shape(singletonOnly),
            singletonOnly.blocks.any { it is MetricRowBlock },
        )

        val rowsOnly = build(
            schema,
            draftOf(
                "COST_INTEGRITY",
                rows = listOf(
                    DraftRow(
                        id = "costSheet#row-1",
                        values = mapOf("name" to JsonPrimitive("Bandha runner"), "units" to JsonPrimitive(12)),
                    )
                ),
            ),
        )
        assertTrue(
            "a collection row must not grow a metric row: " + shape(rowsOnly),
            rowsOnly.blocks.none { it is MetricRowBlock },
        )
    }

    @Test
    fun `no answer is lost by any of the five changes`() {
        val schema = schemaOf(
            StageDto(
                number = 6, key = "EXISTING_PRODUCTS_BASELINE", title = "Baseline",
                entities = listOf(
                    EntityDto(
                        key = "baseline", cardinality = "SINGLETON", title = "Baseline",
                        fields = listOf(
                            richField("background", "Background", "NARRATIVE"),
                            richField("gaps", "Gaps", "BULLETS"),
                            metricField("looms", "Looms"),
                            textField("village", "Village"),
                            textField("block", "Block", role = "COVER_FIELD"),
                            textField("district", "District", role = "TABLE_COLUMN"),
                            textField("unknownRole", "Something new", role = "SOMETHING_THE_SERVER_ADDED"),
                            textField("hidden", "Internal", role = "HIDDEN"),
                        ),
                    )
                )
            )
        )
        val printed = printedText(
            build(
                schema,
                draftOf(
                    "EXISTING_PRODUCTS_BASELINE",
                    values = mapOf(
                        "background" to rich("PARAGRAPH" to "Fourteen households."),
                        "gaps" to rich("BULLET_ITEM" to "No dye vat"),
                        "looms" to JsonPrimitive(14),
                        "village" to JsonPrimitive("Barpali"),
                        "block" to JsonPrimitive("Bargarh"),
                        "district" to JsonPrimitive("Bargarh district"),
                        "unknownRole" to JsonPrimitive("a role this build has never heard of"),
                        "hidden" to JsonPrimitive("never printed"),
                    ),
                ),
            )
        )

        listOf(
            "Fourteen households.", "No dye vat", "Barpali", "Bargarh", "Bargarh district", "Looms: 14",
            "a role this build has never heard of",
        ).forEach { expected ->
            assertTrue("'$expected' did not reach the file:\n$printed", printed.contains(expected))
        }
        assertFalse("a HIDDEN field must never print:\n$printed", printed.contains("never printed"))
    }
}
