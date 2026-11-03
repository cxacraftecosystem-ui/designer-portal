package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwStrandedStageTest {

    private val schema = SchemaResponse(
        stages = listOf(
            StageDto(number = 1, key = "ORIENTATION", title = "Orientation"),
            StageDto(number = 2, key = "CLUSTER_PROFILE", title = "Cluster profile"),
        )
    )

    private fun draftHolding(vararg stages: StageDraft) = WorkshopDraft(
        workshopId = "w1",
        stages = stages.associateBy { it.stageId },
    )

    @Test
    fun `a stage the registry no longer declares, holding answers, is found`() {
        val stranded = dwStrandedStages(
            schema,
            draftHolding(
                StageDraft(stageId = "CLUSTER_PROFILE", values = mapOf("looms" to JsonPrimitive(12))),
                StageDraft(
                    stageId = "MARKET_LINKAGE",
                    title = "Market linkage",
                    values = mapOf("buyer" to JsonPrimitive("Dastkar")),
                ),
            ),
        )

        assertEquals(1, stranded.size)
        assertEquals("MARKET_LINKAGE", stranded.single().stageId)
    }

    @Test
    fun `rows alone and custom answers alone are both work`() {
        val rowsOnly = StageDraft(
            stageId = "COSTING",
            rows = listOf(DraftRow(id = dwRowId("costLine", "r1"), values = emptyMap())),
        )
        val customOnly = StageDraft(
            stageId = "SKETCH_REVIEW",
            custom = mapOf("q1" to JsonPrimitive("The buyer preferred the second sketch.")),
        )
        assertEquals(2, dwStrandedStages(schema, draftHolding(rowsOnly, customOnly)).size)
    }

    @Test
    fun `a stage holding nothing is not reported`() {
        val stranded = dwStrandedStages(schema, draftHolding(StageDraft(stageId = "MARKET_LINKAGE")))
        assertTrue(stranded.isEmpty())
    }

    @Test
    fun `an ordinary device with a matching registry reports nothing`() {
        val stranded = dwStrandedStages(
            schema,
            draftHolding(
                StageDraft(stageId = "ORIENTATION", values = mapOf("a" to JsonPrimitive("b"))),
                StageDraft(stageId = "CLUSTER_PROFILE", values = mapOf("c" to JsonPrimitive("d"))),
            ),
        )
        assertTrue(stranded.isEmpty())
    }
}
