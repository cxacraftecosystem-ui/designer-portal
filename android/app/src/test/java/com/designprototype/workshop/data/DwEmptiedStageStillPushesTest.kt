package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwEmptiedStageStillPushesTest {

    private val spec = StageDto(
        number = 17,
        key = "COSTING",
        title = "Costing",
        entities = listOf(
            EntityDto(
                key = "costLine", cardinality = "COLLECTION", title = "Cost line",
                fields = listOf(
                    FieldDto(key = "item", label = "Item", type = "TEXT"),
                    FieldDto(key = "rate", label = "Rate", type = "DECIMAL"),
                ),
            ),
        ),
    )

    private fun emptiedAfterReading() = StageDraft(
        stageId = spec.key,
        rows = emptyList(),
        stageSeen = true,
        emptiedEntities = listOf("costLine"),
        deletedRowKeys = listOf(
            dwRowId("costLine", "c1"),
            dwRowId("costLine", "c2"),
        ),
    )

    @Test
    fun `a stage holding nothing but a deletion it can send is not silence`() {
        val stored = emptiedAfterReading()
        val record: StageSyncRecord? = null

        assertTrue(
            "the draft really does hold nothing else — that is what makes this the swallowed case",
            stored.values.isEmpty() && stored.rows.isEmpty() && stored.custom.isEmpty(),
        )
        assertTrue(
            "and the server has never acknowledged a payload for it",
            record?.signature.isNullOrBlank(),
        )

        assertFalse(
            "a recorded deletion the draft is entitled to state is something to say",
            dwStageSaysNothing(spec, stored, record, workshopIsRemote = true),
        )
    }

    @Test
    fun `and the payload it is now allowed to build actually carries the deletion`() {
        val stored = emptiedAfterReading()
        val built = buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, null))

        assertTrue(built.body.replaceCollections)
        assertEquals(listOf("costLine"), built.body.emptiedEntities)
        assertTrue(
            "a payload that named the rows again would sweep nothing",
            built.body.entries.none { it.entityKey == "costLine" },
        )
        val record: StageSyncRecord? = null
        assertTrue(
            "falling through the gate is only worth something if the comparison below it says pending",
            signatureOf(built.body) != record?.signature,
        )
    }

    @Test
    fun `a stage holding only individual row deletions speaks too`() {
        val stored = StageDraft(
            stageId = spec.key,
            stageSeen = true,
            deletedRowKeys = listOf(dwRowId("costLine", "c9")),
        )
        assertFalse(dwStageSaysNothing(spec, stored, record = null, workshopIsRemote = true))
    }

    @Test
    fun `a deletion this device cannot yet state is still silence, because a push cannot fix it`() {
        val unread = emptiedAfterReading().copy(stageSeen = false)
        assertTrue(dwStageSaysNothing(spec, unread, record = null, workshopIsRemote = true))

        val built = buildStageBody(spec, unread, emptyMap(), isAuthoritative(unread, null))
        assertFalse(built.body.replaceCollections)
        assertTrue(
            "so there would have been nothing in the payload to justify sending it",
            built.body.emptiedEntities.isEmpty(),
        )
    }

    @Test
    fun `a deletion naming an entity this build does not declare is not a reason to push`() {
        val stale = StageDraft(
            stageId = spec.key,
            stageSeen = true,
            emptiedEntities = listOf("anEntityTheRegistryDropped"),
            deletedRowKeys = listOf(dwRowId("anEntityTheRegistryDropped", "x1")),
        )
        assertTrue(dwOwedDeletions(spec, stale).entities.isEmpty())
        assertTrue(dwOwedDeletions(spec, stale).rows.isEmpty())
        assertTrue(dwStageSaysNothing(spec, stale, record = null, workshopIsRemote = true))
    }

    @Test
    fun `a deletion on a workshop that exists nowhere else is not a reason to push`() {
        val stored = emptiedAfterReading()
        assertTrue(dwStageSaysNothing(spec, stored, record = null, workshopIsRemote = false))
    }

    @Test
    fun `an empty stage with nothing recorded is still silence`() {
        assertTrue(dwStageSaysNothing(spec, StageDraft(stageId = spec.key), null, workshopIsRemote = true))
        assertTrue(dwStageSaysNothing(spec, null, null, workshopIsRemote = true))
    }

    @Test
    fun `an empty stage the server HAS acknowledged still speaks`() {
        val sent = StageSyncRecord(signature = "whatever-the-last-save-hashed-to")
        assertFalse(dwStageSaysNothing(spec, StageDraft(stageId = spec.key), sent, workshopIsRemote = true))
    }

    @Test
    fun `a stage holding answers is never silence, custom answers included`() {
        val typed = StageDraft(
            stageId = spec.key,
            rows = listOf(DraftRow(id = dwRowId("costLine", "c1"), values = mapOf("item" to JsonPrimitive("Warp")))),
        )
        assertFalse(dwStageSaysNothing(spec, typed, null, workshopIsRemote = true))
        val customOnly = StageDraft(
            stageId = spec.key,
            custom = mapOf("dyeSource" to JsonPrimitive("Madder, bought in Bhuj")),
        )
        assertFalse(dwStageSaysNothing(spec, customOnly, null, workshopIsRemote = true))
    }
}
