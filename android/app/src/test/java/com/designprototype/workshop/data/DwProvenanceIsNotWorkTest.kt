package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DwStageRead
import com.designprototype.workshop.ui.designworkshop.dwStageReadPlan
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwProvenanceIsNotWorkTest {

    private val spec = StageDto(
        number = 14,
        key = "SAMPLING",
        title = "Sampling",
        entities = listOf(
            EntityDto(
                key = "sampling", cardinality = "SINGLETON", title = "Sampling",
                fields = listOf(FieldDto(key = "notes", label = "Notes", type = "LONG_TEXT")),
            ),
            EntityDto(
                key = "sampleLine", cardinality = "COLLECTION", title = "Sample",
                fields = listOf(FieldDto(key = "item", label = "Item", type = "TEXT")),
            ),
        ),
    )

    private val recordingPlace = buildJsonObject {
        put("village", JsonPrimitive("Bagru"))
        put("district", JsonPrimitive("Jaipur"))
        put("state", JsonPrimitive("Rajasthan"))
    }

    private fun provenanceOnly(stageId: String = spec.key) = StageDraft(
        stageId = stageId,
        values = mapOf("_recordingPlace" to recordingPlace),
    )

    @Test
    fun `a stage holding only untransmittable provenance has nothing to say`() {
        val stored = provenanceOnly()

        assertTrue("the draft really does hold a key — that is the trap", stored.values.isNotEmpty())

        assertTrue(
            "a key `wireData` strips is not something to say to the server",
            dwStageSaysNothing(spec, stored, record = null, workshopIsRemote = true),
        )
    }

    @Test
    fun `and the payload it would have sent proves the point`() {
        val built = buildStageBody(spec, provenanceOnly(), emptyMap(), authoritative = false)
        assertTrue(
            "the singleton entry the PUT would have carried is empty",
            built.body.entries.single { it.entityKey == "sampling" }.data.isEmpty(),
        )
    }

    @Test
    fun `a retired stage holding only provenance is not a stranded stage`() {
        val schema = SchemaResponse(stages = listOf(StageDto(number = 1, key = "ORIENTATION", title = "Orientation")))
        val draft = WorkshopDraft(
            workshopId = "w1",
            stages = mapOf("SAMPLING" to provenanceOnly()),
        )

        assertTrue(
            "an untransmittable provenance note is the 'no answers at all' case that comment excludes",
            dwStrandedStages(schema, draft).isEmpty(),
        )
    }

    @Test
    fun `one real answer beside the provenance is work again`() {
        val stored = provenanceOnly().copy(
            values = mapOf(
                "_recordingPlace" to recordingPlace,
                "notes" to JsonPrimitive("Two looms idle; the warp yarn had not arrived."),
            ),
        )
        assertFalse(dwStageSaysNothing(spec, stored, record = null, workshopIsRemote = true))

        val schema = SchemaResponse(stages = listOf(StageDto(number = 1, key = "ORIENTATION", title = "Orientation")))
        assertEquals(
            1,
            dwStrandedStages(schema, WorkshopDraft(workshopId = "w1", stages = mapOf("SAMPLING" to stored))).size,
        )
    }

    @Test
    fun `rows and custom answers are untouched by this rule`() {
        val rowsOnly = StageDraft(
            stageId = spec.key,
            values = mapOf("_recordingPlace" to recordingPlace),
            rows = listOf(DraftRow(id = dwRowId("sampleLine", "r1"), values = mapOf("item" to JsonPrimitive("Indigo")))),
        )
        val customOnly = StageDraft(
            stageId = spec.key,
            values = mapOf("_recordingPlace" to recordingPlace),
            custom = mapOf("dyeSource" to JsonPrimitive("Madder, bought in Bhuj")),
        )
        assertFalse(dwStageSaysNothing(spec, rowsOnly, record = null, workshopIsRemote = true))
        assertFalse(dwStageSaysNothing(spec, customOnly, record = null, workshopIsRemote = true))
    }

    @Test
    fun `a deletion the draft is entitled to state still speaks over the provenance`() {
        val emptied = StageDraft(
            stageId = spec.key,
            values = mapOf("_recordingPlace" to recordingPlace),
            stageSeen = true,
            emptiedEntities = listOf("sampleLine"),
        )
        assertFalse(dwStageSaysNothing(spec, emptied, record = null, workshopIsRemote = true))
    }

    @Test
    fun `an empty stage the server has acknowledged still speaks`() {
        val sent = StageSyncRecord(signature = "whatever-the-last-save-hashed-to")
        assertFalse(dwStageSaysNothing(spec, provenanceOnly(), sent, workshopIsRemote = true))
    }

    @Test
    fun `the stage READ still counts provenance as something this device holds`() {
        assertEquals(
            DwStageRead.FOLD_SERVER_COPY,
            dwStageReadPlan(provenanceOnly(), remoteExists = true, canReach = true),
        )
        assertEquals(
            DwStageRead.DRAFT_AS_IS,
            dwStageReadPlan(provenanceOnly(), remoteExists = true, canReach = false),
        )
    }
}
