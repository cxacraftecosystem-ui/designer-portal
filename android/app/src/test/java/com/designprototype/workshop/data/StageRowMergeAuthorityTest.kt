package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `a row on a stage this device has never read goes up as a merge`() {
        val entry = rowEntry(rowCreatedInACourtyard(seen = false))

        assertTrue(
            "a phone that has not read the stage is sending every key it HAS, never every key there IS",
            entry.merge
        )
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

    @Test
    fun `every entry of one payload makes the SAME claim about what this device has seen`() {
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

    @Test
    fun `a brand new row merges too, and the server treats that as the no-op it is`() {
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

    @Test
    fun `earning the read changes the payload, so the stage re-sends itself once and by itself`() {
        val before = build(rowCreatedInACourtyard(seen = false))
        val after = build(rowCreatedInACourtyard(seen = true))
        assertTrue(before.entries.single { it.entityKey == "processStep" }.merge)
        assertFalse(after.entries.single { it.entityKey == "processStep" }.merge)
    }
}
