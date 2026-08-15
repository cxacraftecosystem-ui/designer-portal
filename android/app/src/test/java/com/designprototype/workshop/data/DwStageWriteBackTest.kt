package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.dwAdoptDeletionRecordAfterPush
import com.designprototype.workshop.ui.designworkshop.dwDeletionRecordOnDisk
import com.designprototype.workshop.ui.designworkshop.dwStageRowsToStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TWO WAYS THE STAGE SCREEN'S WRITE-BACK DESTROYED SOMETHING IT DID NOT OWN.
 *
 * Both are the same mistake in different clothes: `persistLocally` REBUILDS THE WHOLE `StageDraft` on
 * every debounced save, so anything it does not deliberately carry forward is deleted from disk by the
 * next keystroke. That rule is written out on `values`, on `custom`, on `stageSeen` and on `mediaIds`.
 * These are the two places it was not.
 *
 * ── 1. ROWS UNDER AN ENTITY THE REGISTRY HAS LOST ────────────────────────────────────────────────
 *
 * `fromDraft` and `persistLocally` both walk `stage.collections`, so a row filed under an entity this
 * registry does not declare was neither drawn nor written back: one save on any unrelated field and it
 * was gone. `dwFoldServerStage` starts from `ArrayList(base.rows)` and preserves exactly such rows, so
 * this was an asymmetry inside one feature rather than a policy — which is how it survived review.
 *
 * ── 2. THE DELETION RECORD THE SERVER HAD ALREADY ACKNOWLEDGED ───────────────────────────────────
 *
 * `emptied`/`deletedRows` are seeded from disk once, at load, and thereafter only ever GAIN keys.
 * `recordStageSent` removes exactly the keys an acknowledged payload carried; nothing told the
 * composition, so the screen's stale copy was unioned straight back onto disk and the NEXT differing
 * payload asserted the same sweep a second time. Meanwhile the office had entered two rows on the web
 * in that collection: the phone never re-reads a stage it has already seen, so the auto-save named no
 * rows for the entity, re-asserted the sweep, and the server soft-deleted them under an HTTP 200 the
 * screen reported as synced.
 */
class DwStageWriteBackTest {

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

    private fun row(entityKey: String, id: String) = DraftRow(
        id = dwRowId(entityKey, id),
        values = mapOf("step" to JsonPrimitive("$entityKey/$id")),
    )

    // ── 1. Rows this registry cannot draw ────────────────────────────────────────────────────────

    @Test
    fun `rows under an entity the registry no longer declares survive the save`() {
        val existing = StageDraft(
            stageId = spec.key,
            rows = listOf(
                row("processStep", "p1"),
                // Recorded when the registry still declared this collection; the cache has since been
                // lost or rolled back, so this build cannot draw it.
                row("materialUsed", "m1"),
                row("materialUsed", "m2"),
            ),
        )

        val written = dwStageRowsToStore(spec, drawn = listOf(row("processStep", "p1")), existing = existing)

        // THE ASSERTION THE DEFECT FAILED: `written` held one row and the designer's two were gone.
        assertEquals(3, written.size)
        assertEquals(
            listOf(dwRowId("materialUsed", "m1"), dwRowId("materialUsed", "m2")),
            written.filter { it.entityKey() == "materialUsed" }.map { it.id },
        )
    }

    @Test
    fun `a row the designer deleted from a declared collection still goes`() {
        // The whole point of the write-back: for entities the registry DOES declare, what is on screen
        // is the truth, and a deleted row must not be resurrected by the carry-through.
        val existing = StageDraft(
            stageId = spec.key,
            rows = listOf(row("processStep", "p1"), row("processStep", "p2")),
        )

        val written = dwStageRowsToStore(spec, drawn = listOf(row("processStep", "p1")), existing = existing)

        assertEquals(listOf(dwRowId("processStep", "p1")), written.map { it.id })
    }

    @Test
    fun `the drawn rows keep their order and come first`() {
        val existing = StageDraft(stageId = spec.key, rows = listOf(row("materialUsed", "m1")))
        val drawn = listOf(row("processStep", "p2"), row("processStep", "p1"))

        val written = dwStageRowsToStore(spec, drawn = drawn, existing = existing)

        assertEquals(
            "the order on screen is the order the report prints",
            listOf(dwRowId("processStep", "p2"), dwRowId("processStep", "p1"), dwRowId("materialUsed", "m1")),
            written.map { it.id },
        )
    }

    @Test
    fun `a stage with nothing on disk yet writes exactly what is drawn`() {
        val written = dwStageRowsToStore(spec, drawn = listOf(row("processStep", "p1")), existing = null)
        assertEquals(listOf(dwRowId("processStep", "p1")), written.map { it.id })
    }

    // ── 2. The acknowledged deletion record ──────────────────────────────────────────────────────

    /**
     * The screen's two fields, and the acknowledgement, run against [dwAdoptDeletionRecordAfterPush].
     *
     * WHAT THIS REPLACED, AND WHY. The test that used to stand here was named for the post-push
     * refresh and called [dwDeletionRecordOnDisk] — `saved?.let { emptiedEntities to deletedRowKeys }`
     * — so it passed whether the refresh ran, was cancelled, or did not exist. That is worse than no
     * test, because it was read as cover while the refresh it was named for was in fact never
     * completing: it sat inside `LaunchedEffect(revision)`, which the next keystroke cancels.
     */
    private fun adopt(
        onDisk: StageDraft?,
        sentEmptied: Set<String> = emptySet(),
        sentDeletedRows: Set<String> = emptySet(),
        screenEmptied: Set<String> = emptySet(),
        screenDeletedRows: Set<String> = emptySet(),
    ): Pair<Set<String>, Set<String>>? {
        var adopted: Pair<Set<String>, Set<String>>? = null
        runBlocking {
            dwAdoptDeletionRecordAfterPush(
                sentEmptied = sentEmptied,
                sentDeletedRows = sentDeletedRows,
                screenEmptied = { screenEmptied },
                screenDeletedRows = { screenDeletedRows },
                loadStage = { onDisk },
            ) { entities, rows -> adopted = entities to rows }
        }
        return adopted
    }

    @Test
    fun `after an acknowledged push the screen adopts the draft's record, not its own`() {
        // What `recordStageSent` left behind: it dropped `processStep` — the sweep the acknowledged
        // payload actually carried — and left `materialUsed`, which that payload did not.
        val onDisk = StageDraft(
            stageId = spec.key,
            emptiedEntities = listOf("materialUsed"),
            deletedRowKeys = listOf(dwRowId("processStep", "p9")),
        )

        val (entities, rows) = adopt(
            onDisk = onDisk,
            // What the screen was holding when the write was handed over, and still holds.
            sentEmptied = setOf("materialUsed", "processStep"),
            screenEmptied = setOf("materialUsed", "processStep"),
            sentDeletedRows = setOf(dwRowId("processStep", "p9"), dwRowId("processStep", "p8")),
            screenDeletedRows = setOf(dwRowId("processStep", "p9"), dwRowId("processStep", "p8")),
        )!!

        // THE ASSERTION THE DEFECT FAILED: the screen kept the acknowledged keys in RAM and
        // `persistLocally` unioned them straight back onto disk, so the next differing payload
        // asserted the same sweep a second time and soft-deleted rows the office had entered since.
        assertEquals(setOf("materialUsed"), entities)
        assertEquals(setOf(dwRowId("processStep", "p9")), rows)
    }

    @Test
    fun `a deletion made while the push was in flight is not adopted away`() {
        // The hazard the uncancellable refresh creates and must answer for. `recordStageSent` cleared
        // `processStep`; in the same seconds the designer emptied `materialUsed`, which the draft has
        // not been told about yet because that write is still ahead of it. Adopting the draft's answer
        // wholesale would erase the new deletion — and unlike a resurrected one, nothing later
        // re-asserts it: the server keeps the rows and no screen ever says so.
        val onDisk = StageDraft(stageId = spec.key, emptiedEntities = emptyList())

        val (entities, rows) = adopt(
            onDisk = onDisk,
            sentEmptied = setOf("processStep"),
            screenEmptied = setOf("processStep", "materialUsed"),
            sentDeletedRows = emptySet(),
            screenDeletedRows = setOf(dwRowId("materialUsed", "m4")),
        )!!

        assertEquals(setOf("materialUsed"), entities)
        assertEquals(setOf(dwRowId("materialUsed", "m4")), rows)
    }

    @Test
    fun `the refresh still lands when the next keystroke cancels the save`() {
        /*
          THE DEFECT IN THE FIX, MEASURED. The refresh was written inline in `saveAndSync`, which runs
          inside `LaunchedEffect(revision)`; `revision++` on every keystroke cancels that coroutine.
          There is a suspension point between `pushStage` returning and the draft read landing, so on
          a handset whose designer keeps typing the read never completed and the acknowledged key was
          resurrected by the next `persistLocally` — the very thing the fix was written to stop.

          This reproduces it exactly: the calling job is cancelled while `loadStage` is suspended.
          Remove `withContext(NonCancellable)` from `dwAdoptDeletionRecordAfterPush` and `gate.await()`
          throws `CancellationException` the moment `job.cancel()` runs, `adopt` is never called, and
          this test fails on the assertion below. (Run both ways: it does.)
        */
        var adopted: Pair<Set<String>, Set<String>>? = null
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val reached = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                dwAdoptDeletionRecordAfterPush(
                    sentEmptied = setOf("processStep"),
                    sentDeletedRows = emptySet(),
                    screenEmptied = { setOf("processStep") },
                    screenDeletedRows = { emptySet() },
                    loadStage = {
                        reached.complete(Unit)
                        // Stands in for `WorkshopDraftStore.load` — a suspending read, and the only
                        // suspension point the cancellation needs.
                        gate.await()
                        StageDraft(stageId = spec.key)
                    },
                ) { entities, rows -> adopted = entities to rows }
            }

            reached.await()
            job.cancel()          // the keystroke
            gate.complete(Unit)   // the disk answering, a moment later
            job.join()
        }

        assertEquals(
            "the acknowledgement must survive the keystroke that cancelled the save",
            emptySet<String>() to emptySet<String>(),
            adopted,
        )
    }

    @Test
    fun `nothing on disk leaves the screen alone rather than clearing it`() {
        // The screen's own record is not wiped by a stage that is not there — see
        // [dwDeletionRecordOnDisk]. The callback must simply not fire.
        assertNull(adopt(onDisk = null, screenEmptied = setOf("materialUsed")))
    }

    @Test
    fun `a draft the server acknowledged in full leaves the screen holding nothing`() {
        val cleared = StageDraft(stageId = spec.key)
        assertEquals(emptySet<String>() to emptySet<String>(), dwDeletionRecordOnDisk(cleared))
    }

    /** No stage on disk means "leave the screen alone" rather than "clear it". */
    @Test
    fun `nothing on disk changes nothing`() {
        assertNull(dwDeletionRecordOnDisk(null))
    }
}
