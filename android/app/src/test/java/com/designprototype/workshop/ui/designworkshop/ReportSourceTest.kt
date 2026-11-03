package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageListDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSourceTest {

    private fun textField(key: String, label: String, role: String = "KEY_VALUE") =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = role)

    private fun collectionStage(number: Int, key: String, title: String, entityKey: String, entityTitle: String) =
        StageDto(
            number = number, key = key, title = title,
            entities = listOf(
                EntityDto(
                    key = entityKey, cardinality = "COLLECTION", title = entityTitle,
                    fields = listOf(textField("name", "Name"), textField("detail", "Detail")),
                )
            )
        )

    private val schema = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 1, key = "WORKSHOP_SETUP", title = "Workshop setup",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Workshop setup",
                        fields = listOf(
                            textField("craftName", "Craft"),
                            textField("clusterName", "Cluster"),
                            textField("state", "State"),
                        ),
                    )
                )
            ),
            collectionStage(3, "WORKSHOP_PLAN_PARTICIPANTS_OPENING", "Participants", "participant", "Participants"),
            collectionStage(11, "SKETCH_DEVELOPMENT", "Sketch development", "sketch", "Sketches"),
            collectionStage(17, "COSTING_MARKET_LINKAGE", "Costing", "costSheet", "Cost sheets"),
        )
    )

    private val stageTitles = schema.stages.map { it.title }.toSet()

    private fun jsonRow(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.toMap().mapValues { JsonPrimitive(it.value) })

    private fun rows(entityKey: String, count: Int): List<JsonObject> = (1..count).map { index ->
        JsonObject(
            mapOf(
                "name" to JsonPrimitive("$entityKey $index"),
                "detail" to JsonPrimitive("recorded on the web"),
                "_entryId" to JsonPrimitive("$entityKey-entry-$index"),
                "_ordinal" to JsonPrimitive(index),
            )
        )
    }

    private val remote = StageListDto(
        stages = mapOf(
            "WORKSHOP_SETUP" to StageBucketDto(
                singleton = jsonRow(
                    "craftName" to "Sambalpuri ikat",
                    "clusterName" to "Barpali",
                    "state" to "Odisha",
                )
            ),
            "WORKSHOP_PLAN_PARTICIPANTS_OPENING" to StageBucketDto(
                collections = mapOf("participant" to rows("participant", 20))
            ),
            "SKETCH_DEVELOPMENT" to StageBucketDto(
                collections = mapOf("sketch" to rows("sketch", 10))
            ),
            "COSTING_MARKET_LINKAGE" to StageBucketDto(
                collections = mapOf("costSheet" to rows("costSheet", 4))
            ),
        )
    )

    private fun build(draft: WorkshopDraft?): ReportDocument = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = WORKSHOP_ID,
        templateId = "DCH_STANDARD",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        generatedAt = "2026-02-10T09:00:00Z",
    )

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun stageSections(document: ReportDocument): Int =
        document.blocks.filterIsInstance<HeadingBlock>()
            .count { it.level == 1 && runText(it.runs).trim() in stageTitles }

    private fun sourceFor(
        local: WorkshopDraft?,
        remoteId: String? = WORKSHOP_ID,
        answer: StageListDto? = remote,
    ) = reportSourceFor(
        schema = schema,
        workshopId = WORKSHOP_ID,
        local = local,
        remoteId = remoteId,
        remote = answer,
    )

    private fun localDraft(vararg stages: Pair<String, StageDraft>) = WorkshopDraft(
        workshopId = WORKSHOP_ID,
        title = "Barpali cluster",
        remoteId = WORKSHOP_ID,
        stages = stages.toMap(),
    )

    @Test
    fun `a workshop whose stages are on the server does not export an empty document`() {
        val onDevice = localDraft()

        val deviceOnly = build(onDevice)
        assertEquals(
            "this is the delivered defect — a cover page and no stages",
            0,
            stageSections(deviceOnly),
        )

        val merged = build(sourceFor(onDevice).draft)
        assertEquals(
            "every stage the server holds should reach the document",
            schema.stages.size,
            stageSections(merged),
        )
        assertTrue(
            "the merged document should be substantially longer than the device-only one " +
                "(${merged.blocks.size} vs ${deviceOnly.blocks.size} blocks)",
            merged.blocks.size > deviceOnly.blocks.size,
        )
    }

    @Test
    fun `every row the server holds reaches the merged draft`() {
        val source = sourceFor(localDraft())
        val stages = source.draft!!.stages

        assertEquals(20, stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING").rowsFor("participant").size)
        assertEquals(10, stages.getValue("SKETCH_DEVELOPMENT").rowsFor("sketch").size)
        assertEquals(4, stages.getValue("COSTING_MARKET_LINKAGE").rowsFor("costSheet").size)
        assertEquals(3, stages.getValue("WORKSHOP_SETUP").values.size)
        assertEquals(schema.stages.size, source.filledFromServer.size)
    }

    @Test
    fun `a stage this device has work for is NOT overwritten by the server's copy`() {
        val unsynced = StageDraft(
            stageId = "SKETCH_DEVELOPMENT",
            values = mapOf("intent" to JsonPrimitive("drawn at the loom, not yet sent") as JsonElement),
            rows = (1..3).map { index ->
                DraftRow(
                    id = dwRowId("sketch", "local-$index"),
                    values = mapOf("name" to JsonPrimitive("courtyard sketch $index") as JsonElement),
                )
            },
        )

        val source = sourceFor(localDraft("SKETCH_DEVELOPMENT" to unsynced))
        val merged = source.draft!!
        val kept = merged.stages.getValue("SKETCH_DEVELOPMENT")

        assertEquals("the local rows must survive, all three of them", 3, kept.rowsFor("sketch").size)
        assertEquals("the local stage must be kept whole, not blended", unsynced, kept)
        assertTrue(source.keptFromDevice.contains("SKETCH_DEVELOPMENT"))
        assertTrue(source.filledFromServer.none { it == "SKETCH_DEVELOPMENT" })

        assertEquals(3, source.filledFromServer.size)
        assertEquals(20, merged.stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING").rowsFor("participant").size)
    }

    @Test
    fun `an empty local shell does not keep the server's copy out`() {
        val shell = StageDraft(stageId = "WORKSHOP_PLAN_PARTICIPANTS_OPENING", title = "Participants")
        val source = sourceFor(localDraft("WORKSHOP_PLAN_PARTICIPANTS_OPENING" to shell))

        assertEquals(
            20,
            source.draft!!.stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING").rowsFor("participant").size,
        )
        assertTrue(source.filledFromServer.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))
    }

    @Test
    fun `a stage whose only local content is untransmittable provenance is not the device's stage`() {
        val provenanceOnly = StageDraft(
            stageId = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
            title = "Participants",
            values = mapOf(
                DW_RECORDING_PLACE_KEY to JsonPrimitive("Barpali, Bargarh") as JsonElement,
            ),
        )
        val source = sourceFor(localDraft("WORKSHOP_PLAN_PARTICIPANTS_OPENING" to provenanceOnly))

        assertEquals(
            "the server's twenty participants must reach the document",
            20,
            source.draft!!.stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING").rowsFor("participant").size,
        )
        assertTrue(source.filledFromServer.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))
        assertFalse(source.keptFromDevice.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))
    }

    @Test
    fun `one real answer beside the provenance still makes the stage the device's`() {
        val realWork = StageDraft(
            stageId = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
            title = "Participants",
            values = mapOf(
                DW_RECORDING_PLACE_KEY to JsonPrimitive("Barpali, Bargarh") as JsonElement,
                "openingNote" to JsonPrimitive("Nine weavers attended the opening.") as JsonElement,
            ),
        )
        val source = sourceFor(localDraft("WORKSHOP_PLAN_PARTICIPANTS_OPENING" to realWork))

        assertTrue(source.keptFromDevice.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))
        assertEquals(
            0,
            source.draft!!.stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING").rowsFor("participant").size,
        )
    }

    @Test
    fun `a stage held only on this device is untouched and still printed`() {
        val onlyHere = StageDraft(
            stageId = "FIELD_ONLY_STAGE",
            values = mapOf("note" to JsonPrimitive("captured with no signal") as JsonElement),
        )
        val source = sourceFor(localDraft("FIELD_ONLY_STAGE" to onlyHere))

        assertEquals(onlyHere, source.draft!!.stages.getValue("FIELD_ONLY_STAGE"))
        assertTrue(source.keptFromDevice.contains("FIELD_ONLY_STAGE"))
    }

    @Test
    fun `a workshop that is not on the server says so and keeps the local draft`() {
        val onDevice = localDraft(
            "SKETCH_DEVELOPMENT" to StageDraft(
                stageId = "SKETCH_DEVELOPMENT",
                values = mapOf("intent" to JsonPrimitive("local only") as JsonElement),
            )
        )
        val source = sourceFor(onDevice, remoteId = null, answer = null)

        val note = source.deviceOnlyNote
        assertNotNull("a device-only export must be announced before the file is handed over", note)
        assertTrue(note!!.contains("has not been uploaded yet"))
        assertEquals(onDevice.stages, source.draft!!.stages)
        assertTrue(source.builtFromLine.isNotEmpty())
    }

    @Test
    fun `a failed read is announced and never mistaken for an empty workshop`() {
        val onDevice = localDraft(
            "SKETCH_DEVELOPMENT" to StageDraft(
                stageId = "SKETCH_DEVELOPMENT",
                values = mapOf("intent" to JsonPrimitive("local only") as JsonElement),
            )
        )
        val source = sourceFor(onDevice, remoteId = WORKSHOP_ID, answer = null)

        val note = source.deviceOnlyNote
        assertNotNull(note)
        assertTrue(note!!.contains("no connection"))
        assertEquals(onDevice.stages, source.draft!!.stages)
        assertTrue(source.filledFromServer.isEmpty())
    }

    @Test
    fun `a successful read carries no warning and still says what it was built from`() {
        val source = sourceFor(localDraft())
        assertNull("nothing to warn about once the server has answered", source.deviceOnlyNote)
        assertTrue(source.builtFromLine.isNotEmpty())
    }

    @Test
    fun `merging is deterministic so a re-export is byte-identical`() {
        val first = sourceFor(localDraft())
        val second = sourceFor(localDraft())
        assertEquals(first.draft, second.draft)
        assertEquals(build(first.draft).blocks, build(second.draft).blocks)
    }

    private companion object {
        const val WORKSHOP_ID = "22222222-2222-4222-8222-222222222222"
    }
}
