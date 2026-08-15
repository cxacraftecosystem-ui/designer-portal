package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BOTH SYNC WALKS ENUMERATE THE REGISTRY, SO NEITHER COULD SEE A STAGE THE REGISTRY HAD LOST.
 *
 * `statusOf` (`schema.stages.forEach`) and `pushStages` (`for (spec in schema.stages…)`) each walk the
 * REGISTRY and look every stage up in the draft. There was no residual term for the other direction,
 * `draft.stages.keys - schema.stages.keys` — so a stage the draft holds answers for, under a key this
 * build's resolved registry does not declare, contributed nothing to `pendingStages`, nothing to
 * `problems`, was never built and never sent. `isFullySynced` was true over it and the list row read
 * "Backed up to the server" about answers with no route off the phone.
 *
 * ── IT CANNOT BE CONJURED, WHICH IS WHY IT IS NARROW RATHER THAN IMPOSSIBLE ──────────────────────
 *
 * Every key in a draft was written by `persistLocally` from SOME resolved registry, so reaching this
 * needs the stage set to actually change under a draft: the server renaming or dropping a key, or a
 * registry cache this build cannot decode (`readCacheFile` deletes it and `load` falls back to the
 * bundled asset) on a device that then stays offline. Note the write side is NOT a trigger —
 * `writeCacheFile` is a temp → `fd.sync()` → rename, so a power loss during a store cannot produce a
 * half-written cache.
 */
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
        // THE ASSERTION THE DEFECT FAILED: nothing walked this direction, so this list was never built.
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

    /**
     * ROWS AND CUSTOM ANSWERS COUNT AS WORK, exactly as they do in [dwStageSaysNothing]. Eight of the
     * twenty-two stages declare no singleton at all, so "a stage whose only content is rows" is the
     * ordinary case rather than an edge one, and asking only about `values` would have left the
     * commonest shape of stranded stage invisible for a second time.
     */
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

    /**
     * AN EMPTY RECORD UNDER A RETIRED KEY IS NOT A PROBLEM. It is a stage somebody opened once and
     * never typed into; naming it would put a permanent sentence on the status screen of every handset
     * that survived a registry change, for no answers at all.
     */
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
