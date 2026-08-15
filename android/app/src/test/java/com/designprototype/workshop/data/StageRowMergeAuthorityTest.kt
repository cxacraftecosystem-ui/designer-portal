package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A COLLECTION ROW'S CONTENTS ARE NOT SAFER THAN A SINGLETON'S, AND THIS IS THE ARM THAT SAID THEY
 * WERE.
 *
 * `isAuthoritative` decides one thing in three places, and until this test existed it was asked in
 * only two of them. It decided `replaceCollections` and `emptiedEntities` — WHICH ROWS SURVIVE — and
 * it decided the singleton's own `merge`. Nothing anywhere asked it about the CONTENTS of a row that
 * does survive: `buildStageBody`'s collection loop built every [StageEntryBody] with `merge` left at
 * its default of false, which on the wire is the claim "I am sending every key there IS".
 * `save_stage` believes it, writes the row's `data` wholesale, writes no `RecordRevision`, and
 * answers `saved=1 errors={} removed=0`.
 *
 * ── THE WALK, WHICH NEEDS NO FAILED DOWNLOAD AND NO SWEEP ────────────────────────────────────────
 *
 * Every previously-fixed defect in this lane started with a stage seeded blank by a download that
 * failed. This one does not, which is why none of the assertions written for those caught it:
 *
 *   1. The designer adds a process step in a courtyard. The phone mints its `_clientKey`, and the row
 *      goes up carrying `{stepNumber: 1, name: "Warping"}`. The stage has never been READ — there was
 *      no signal to read it with — so [StageDraft.stageSeen] is false, correctly.
 *   2. The office opens that same row on the web and fills it in: `localName`, `description`,
 *      `timeTaken`, `performedBy`, `problems`.
 *   3. The designer corrects the step's name on the phone. The phone STILL holds only its own two
 *      keys, because nothing has read the stage in between.
 *   4. The save matches the row on `_clientKey` and replaces it wholesale. The office's five fields
 *      are gone, in place, with a success reported on both surfaces.
 *
 * Reproduced against the running API and read back out of Postgres, before this test was written:
 *
 *   before `{"name": "Warping", "problems": "…", "localName": "Tana", "timeTaken": 6.5,
 *            "stepNumber": 1, "description": "…", "performedBy": "Lakshmi and Saroja"}`
 *   after  `{"name": "Warping (revised)", "stepNumber": 1}`   — `saved=1 errors={} removed=0`
 *
 * and the same payload with `merge: true` on the entry left all seven keys standing with the
 * designer's correction applied, which is what these assertions now require.
 *
 * ── WHAT IT COSTS, SAID OUT LOUD ─────────────────────────────────────────────────────────────────
 *
 * The same as the singleton's merge costs, one loop up: a designer who CLEARS one cell of a row on a
 * stage this device has never read does not clear it on the server until the stage has been read
 * once. That is not a new cost and it is not a silent one — it is precisely what `StageScreen`'s
 * download note already promises the designer ("clearing an answer or deleting a row here does NOT
 * clear or delete it on the server"), which is a promise this arm was the sole reason the app could
 * not keep. Weighed the way the rest of this lane weighs it: a stale cell that the next read heals is
 * cheaper than the office's fieldwork deleted with no revision behind it.
 */
class StageRowMergeAuthorityTest {

    private val spec = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "traditionalProcess", cardinality = "SINGLETON", title = "Traditional process",
                fields = listOf(
                    FieldDto(key = "currentProblems", label = "Current problems", type = "LONG_TEXT")
                )
            ),
            EntityDto(
                key = "processStep", cardinality = "COLLECTION", title = "Process step",
                fields = listOf(
                    FieldDto(key = "stepNumber", label = "Step number", type = "INT"),
                    FieldDto(key = "name", label = "Name", type = "TEXT"),
                )
            ),
        )
    )

    /**
     * The draft at step 3 of the walk: one row this phone created and synced, on a stage no read has
     * ever landed on. `_entryId` is present because the row has been up and come back through
     * nothing at all — it is set from the acknowledgement path — but its ABSENCE would not change the
     * question, and the last test here says so.
     */
    private fun rowCreatedInACourtyard(seen: Boolean) = StageDraft(
        stageId = spec.key,
        rows = listOf(
            DraftRow(
                id = dwRowId("processStep", "K-ROW-1"),
                values = mapOf(
                    "stepNumber" to JsonPrimitive(1),
                    "name" to JsonPrimitive("Warping (revised)"),
                ),
            )
        ),
        stageSeen = seen,
    )

    private fun build(stored: StageDraft?) =
        buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, null)).body

    private fun rowEntry(stored: StageDraft?) =
        build(stored).entries.single { it.entityKey == "processStep" }

    // ── The assertion that would have saved the office's five fields ─────────────────────────────

    @Test
    fun `a row on a stage this device has never read goes up as a merge`() {
        val entry = rowEntry(rowCreatedInACourtyard(seen = false))

        assertTrue(
            "a phone that has not read the stage is sending every key it HAS, never every key there IS",
            entry.merge
        )
        // The designer's correction still travels in full. Refusing authority is not refusing to sync.
        assertEquals(JsonPrimitive("Warping (revised)"), entry.data["name"])
        assertEquals(
            "and the row still names itself, so the server updates rather than duplicating",
            JsonPrimitive("K-ROW-1"), entry.data["_clientKey"]
        )
    }

    @Test
    fun `a row on a stage this device HAS read replaces, because an absent key is a real deletion`() {
        val entry = rowEntry(rowCreatedInACourtyard(seen = true))
        assertFalse("a read stage may say 'these are exactly the keys of this row'", entry.merge)
    }

    // ── The invariant the collection loop was breaking ───────────────────────────────────────────

    @Test
    fun `every entry of one payload makes the SAME claim about what this device has seen`() {
        /*
          The defect in one line. A payload built from an unread stage went out with the singleton
          saying "keep what I do not carry" and every row beside it saying "delete what I do not
          carry" — two contradictory claims about one device's knowledge, in one request, decided by
          the same flag. Whichever of the two is wrong destroys something, and it was the rows.
        */
        val unread = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("The vat has gone cold.")),
            rows = rowCreatedInACourtyard(seen = false).rows,
            stageSeen = false,
        )
        val unreadBody = build(unread)
        assertEquals(2, unreadBody.entries.size)
        assertTrue(
            "an unread stage merges everything it sends",
            unreadBody.entries.all { it.merge }
        )

        val readBody = build(unread.copy(stageSeen = true))
        assertTrue(
            "a read stage replaces everything it sends",
            readBody.entries.none { it.merge }
        )
    }

    // ── A row the server has never seen ──────────────────────────────────────────────────────────

    @Test
    fun `a brand new row merges too, and the server treats that as the no-op it is`() {
        /*
          `merge` is only ever consulted by `save_stage` as `if entry.merge and previous:` — a row
          with no stored predecessor has `previous == {}`, so the flag changes nothing about what is
          written. Asserted rather than reasoned about because the alternative is a special case:
          "send merge only for rows that already exist" would need this device to know which rows the
          server holds, which is the very knowledge it is disclaiming by sending merge at all.
        */
        val brandNew = StageDraft(
            stageId = spec.key,
            rows = listOf(
                DraftRow(
                    id = dwRowId("processStep", "K-ROW-NEW"),
                    values = mapOf("name" to JsonPrimitive("Sizing")),
                )
            ),
            stageSeen = false,
        )
        val entry = rowEntry(brandNew)
        assertTrue(entry.merge)
        assertEquals(
            "nothing invents an entry id for a row the server has never acknowledged",
            null, entry.entryId
        )
    }

    // ── The re-push this change costs, stated rather than discovered ─────────────────────────────

    @Test
    fun `earning the read changes the payload, so the stage re-sends itself once and by itself`() {
        // The same fact `dwFoldServerStage` relies on: a merge body and a replace body are different
        // bytes, so `pushStages` sees a signature that no longer matches and sends the corrected
        // payload with nobody tapping anything. It is also the reason this change costs one PUT per
        // stage holding rows, once, and never more than once.
        val before = build(rowCreatedInACourtyard(seen = false))
        val after = build(rowCreatedInACourtyard(seen = true))
        assertTrue(before.entries.single { it.entityKey == "processStep" }.merge)
        assertFalse(after.entries.single { it.entityKey == "processStep" }.merge)
    }
}
