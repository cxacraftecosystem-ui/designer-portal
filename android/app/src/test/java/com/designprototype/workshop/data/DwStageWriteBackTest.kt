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

    @Test
    fun `rows under an entity the registry no longer declares survive the save`() {
        val existing = StageDraft(
            stageId = spec.key,
            rows = listOf(
                row("processStep", "p1"),
                row("materialUsed", "m1"),
                row("materialUsed", "m2"),
            ),
        )

        val written = dwStageRowsToStore(spec, drawn = listOf(row("processStep", "p1")), existing = existing)

        assertEquals(3, written.size)
        assertEquals(
            listOf(dwRowId("materialUsed", "m1"), dwRowId("materialUsed", "m2")),
            written.filter { it.entityKey() == "materialUsed" }.map { it.id },
        )
    }

    @Test
    fun `a row the designer deleted from a declared collection still goes`() {
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
        val onDisk = StageDraft(
            stageId = spec.key,
            emptiedEntities = listOf("materialUsed"),
            deletedRowKeys = listOf(dwRowId("processStep", "p9")),
        )

        val (entities, rows) = adopt(
            onDisk = onDisk,
            sentEmptied = setOf("materialUsed", "processStep"),
            screenEmptied = setOf("materialUsed", "processStep"),
            sentDeletedRows = setOf(dwRowId("processStep", "p9"), dwRowId("processStep", "p8")),
            screenDeletedRows = setOf(dwRowId("processStep", "p9"), dwRowId("processStep", "p8")),
        )!!

        assertEquals(setOf("materialUsed"), entities)
        assertEquals(setOf(dwRowId("processStep", "p9")), rows)
    }

    @Test
    fun `a deletion made while the push was in flight is not adopted away`() {
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
                        gate.await()
                        StageDraft(stageId = spec.key)
                    },
                ) { entities, rows -> adopted = entities to rows }
            }

            reached.await()
            job.cancel()
            gate.complete(Unit)
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
        assertNull(adopt(onDisk = null, screenEmptied = setOf("materialUsed")))
    }

    @Test
    fun `a draft the server acknowledged in full leaves the screen holding nothing`() {
        val cleared = StageDraft(stageId = spec.key)
        assertEquals(emptySet<String>() to emptySet<String>(), dwDeletionRecordOnDisk(cleared))
    }

    @Test
    fun `nothing on disk changes nothing`() {
        assertNull(dwDeletionRecordOnDisk(null))
    }
}
