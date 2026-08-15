package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FOLD HONOURED ONE OF THE TWO DELETION RECORDS, AND THE OTHER DELETION WAS UNDONE BY THE ONE
 * ACTION THE APP TELLS THE DESIGNER TO TAKE.
 *
 * ── THE DEFECT ───────────────────────────────────────────────────────────────────────────────────
 *
 * Deleting the LAST row of a collection is stated in [StageDraft.emptiedEntities], and
 * [dwFoldServerStage] declines to re-add those rows — that half was written, argued for at length in
 * the file's header, and pinned by `StageAuthorityEarnedByReadingTest`. Deleting ONE row out of three
 * is stated in [StageDraft.deletedRowKeys], the field added for exactly that case, written by
 * `StageScreen.onRowsChange`, counted by `statusOf` and read on the wire by [dwOwedDeletions] — and
 * `deletedRowKeys` appeared NOWHERE in the fold. So the one reader whose job is to decide which of
 * the server's rows come back never consulted it.
 *
 * The consequence is the worse of the two possible ones, because the record was destroyed in the same
 * breath as the deletion. The fold re-added the deleted row; the row was then in the draft, so the
 * next payload NAMED it and `replaceCollections` swept nothing; and `persistLocally`'s row filter
 * drops a key from `deletedRowKeys` whose row is back in the draft — so the instruction was gone too.
 * The designer's deletion was silently reversed and no evidence survived anywhere on the device.
 *
 * ── AND THE APP SENDS THEM DOWN IT ───────────────────────────────────────────────────────────────
 *
 * `statusOf` prints "Open the stage once with a connection and it goes up on the save straight after"
 * over exactly this stage, and following that instruction is what ran the fold. Same shape as
 * `DwEmptiedStageSurvivesTheOpenTest`, one field along.
 *
 * ── WHAT IS PINNED HERE ──────────────────────────────────────────────────────────────────────────
 *
 * That the fold declines the row, that it says so, that it carries the record forward, and — the half
 * that makes the other three worth anything — that the payload built from the folded draft is one
 * whose sweep actually removes the row on the server.
 */
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

    /** A row as the server sends it: an entry id always, a client key when the phone minted one. */
    private fun serverRow(entryId: String, step: String, clientKey: String? = null) = buildJsonObject {
        put("_entryId", entryId)
        if (clientKey != null) put("_clientKey", clientKey)
        put("step", step)
    }

    /** A row as the draft holds it once it has been seeded from the server. */
    private fun heldRow(entryId: String, step: String) = DraftRow(
        id = dwRowId("processStep", entryId),
        values = mapOf("_entryId" to JsonPrimitive(entryId), "step" to JsonPrimitive(step)),
    )

    /**
     * Three steps came from the web; the designer deleted the middle one in a courtyard and the PUT
     * never landed. The draft holds two rows and the record of the third.
     */
    private fun deletedTheMiddleStep() = StageDraft(
        stageId = spec.key,
        rows = listOf(heldRow("srv-1", "Warp the loom"), heldRow("srv-3", "Finish and press")),
        stageSeen = false,
        deletedRowKeys = listOf(dwRowId("processStep", "srv-2")),
    )

    /** What the server still holds, because the deletion has not travelled. */
    private fun serverStillHasThree() = StageBucketDto(
        collections = mapOf(
            "processStep" to listOf(
                serverRow("srv-1", "Warp the loom"),
                serverRow("srv-2", "Dye the weft"),
                serverRow("srv-3", "Finish and press"),
            ),
        ),
    )

    // ── The fold itself ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a row the designer deleted is not folded back in`() {
        val fold = dwFoldServerStage(spec, deletedTheMiddleStep(), serverStillHasThree(), spec.key)

        // THE ASSERTION THE DEFECT FAILED: before the fix this list held three rows.
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

        // Clearing it here would leave the row alive on the server for ever with nothing on any screen
        // saying so. `recordStageSent` is the only place a deletion is cleared, and only for the keys
        // an acknowledged payload carried.
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
        assertTrue(notice, notice.contains("NOT come back"))
        assertFalse(
            "this is the designer's own deletion holding, not the collateral warning:\n$notice",
            notice.contains("You had deleted everything in"),
        )
        assertTrue("nothing was swept, so nothing may claim it was", fold.sweptRows.isEmpty())
    }

    // ── The half that makes the other three worth anything ───────────────────────────────────────

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

    // ── The identities, and the ordinary case that must not regress ──────────────────────────────

    @Test
    fun `a row deleted while held under its client key is recognised however the server files it`() {
        // The row was created on this phone, synced (so the server minted an `_entryId` for it) and
        // then deleted. The draft's record is keyed by the CLIENT key; the server's copy carries both.
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
