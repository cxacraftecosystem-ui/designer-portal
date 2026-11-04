package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwFoldHonoursDeletedRowTest {

    private val spec = StageDto(
        number = 9,
        key = "PROTOTYPE_MAKING",
        title = "Prototype making",
        entities = listOf(
            EntityDto(
                key = "processStep", cardinality = "COLLECTION", title = "Process step",
                fields = listOf(FieldDto(key = "step", label = "Step", type = "TEXT")),
            ),
        ),
    )

    private fun serverRow(entryId: String, step: String, clientKey: String? = null) = buildJsonObject {
        put("_entryId", entryId)
        if (clientKey != null) put("_clientKey", clientKey)
        put("step", step)
    }

    private fun heldRow(entryId: String, step: String) = DraftRow(
        id = dwRowId("processStep", entryId),
        values = mapOf("_entryId" to JsonPrimitive(entryId), "step" to JsonPrimitive(step)),
    )

    private fun deletedTheMiddleStep() = StageDraft(
        stageId = spec.key,
        rows = listOf(heldRow("srv-1", "Warp the loom"), heldRow("srv-3", "Finish and press")),
        stageSeen = false,
        deletedRowKeys = listOf(dwRowId("processStep", "srv-2")),
    )

    private fun serverStillHasThree() = StageBucketDto(
        collections = mapOf(
            "processStep" to listOf(
                serverRow("srv-1", "Warp the loom"),
                serverRow("srv-2", "Dye the weft"),
                serverRow("srv-3", "Finish and press"),
            ),
        ),
    )

    @Test
    fun `a row the designer deleted is not folded back in`() {
        val fold = dwFoldServerStage(spec, deletedTheMiddleStep(), serverStillHasThree(), spec.key)

        assertEquals(2, fold.draft.rowsFor("processStep").size)
        assertNull(
            "the deleted step came back from the server and nothing declined it",
            fold.draft.rows.firstOrNull { it.id == dwRowId("processStep", "srv-2") },
        )
        assertEquals(
            "the two rows the designer kept are untouched and in order",
            listOf("Warp the loom", "Finish and press"),
            fold.draft.rowsFor("processStep").map { (it.values["step"] as JsonPrimitive).content },
        )
    }

    @Test
    fun `the record of the deletion survives the fold, because it is still owed`() {
        val fold = dwFoldServerStage(spec, deletedTheMiddleStep(), serverStillHasThree(), spec.key)

        assertEquals(
            listOf(dwRowId("processStep", "srv-2")),
            fold.draft.deletedRowKeys,
        )
        assertTrue("and the read still earns the authority that lets it be stated", fold.draft.stageSeen)
    }

    @Test
    fun `the fold says so, in its own words rather than the emptied collection's`() {
        val fold = dwFoldServerStage(spec, deletedTheMiddleStep(), serverStillHasThree(), spec.key)

        assertEquals(mapOf("processStep" to 1), fold.declinedRows)
        assertEquals(1, fold.declinedRowCount)
        assertFalse("a fold that declined a row has changed something the designer can see", fold.isNothingNew)
        val notice = fold.notice!!
        assertTrue(notice, notice.contains("not come back"))
        assertFalse(
            "this is the designer's own deletion holding, not the collateral warning:\n$notice",
            notice.contains("You had deleted everything in"),
        )
        assertTrue("nothing was swept, so nothing may claim it was", fold.sweptRows.isEmpty())
    }

    @Test
    fun `the payload built after the fold does not name the deleted row, so the sweep removes it`() {
        val fold = dwFoldServerStage(spec, deletedTheMiddleStep(), serverStillHasThree(), spec.key)
        val body = buildStageBody(
            spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null),
        ).body

        assertTrue("the fold earned the authority to state a deletion", body.replaceCollections)
        val steps = body.entries.filter { it.entityKey == "processStep" }
        assertEquals(2, steps.size)
        assertFalse(
            "naming the row is what made the sweep a no-op and the deletion a lie",
            steps.any { it.entryId == "srv-2" },
        )
        assertEquals(
            "and the two survivors are named, so the sweep takes only the third",
            listOf("srv-1", "srv-3"),
            steps.mapNotNull { it.entryId },
        )
    }

    @Test
    fun `a row deleted while held under its client key is recognised however the server files it`() {
        val local = StageDraft(
            stageId = spec.key,
            rows = listOf(heldRow("srv-1", "Warp the loom")),
            deletedRowKeys = listOf(dwRowId("processStep", "ck-77")),
        )
        val bucket = StageBucketDto(
            collections = mapOf(
                "processStep" to listOf(
                    serverRow("srv-1", "Warp the loom"),
                    serverRow("srv-9", "Dye the weft", clientKey = "ck-77"),
                ),
            ),
        )

        val fold = dwFoldServerStage(spec, local, bucket, spec.key)
        assertEquals(1, fold.draft.rowsFor("processStep").size)
        assertEquals(mapOf("processStep" to 1), fold.declinedRows)
    }

    @Test
    fun `a server row nobody deleted is still added, which is the whole point of the fold`() {
        val bucket = StageBucketDto(
            collections = mapOf(
                "processStep" to listOf(
                    serverRow("srv-1", "Warp the loom"),
                    serverRow("srv-2", "Dye the weft"),
                    serverRow("srv-3", "Finish and press"),
                    serverRow("srv-4", "Block and label"),
                ),
            ),
        )
        val fold = dwFoldServerStage(spec, deletedTheMiddleStep(), bucket, spec.key)

        assertEquals("three kept or arrived, one declined", 3, fold.draft.rowsFor("processStep").size)
        assertEquals(mapOf("processStep" to 1), fold.addedRows)
        assertEquals(mapOf("processStep" to 1), fold.declinedRows)
        assertTrue(
            "the office's new step is here",
            fold.draft.rows.any { it.id == dwRowId("processStep", "srv-4") },
        )
    }

    @Test
    fun `a draft holding no deletions folds exactly as it did before`() {
        val local = StageDraft(stageId = spec.key, rows = listOf(heldRow("srv-1", "Warp the loom")))
        val fold = dwFoldServerStage(spec, local, serverStillHasThree(), spec.key)

        assertEquals(3, fold.draft.rowsFor("processStep").size)
        assertTrue(fold.declinedRows.isEmpty())
        assertEquals(mapOf("processStep" to 2), fold.addedRows)
    }
}
