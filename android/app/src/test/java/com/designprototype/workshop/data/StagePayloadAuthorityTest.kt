package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePayloadAuthorityTest {

    private val spec = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "traditionalProcess", cardinality = "SINGLETON", title = "Traditional process",
                fields = listOf(FieldDto(key = "currentProblems", label = "Current problems", type = "LONG_TEXT"))
            ),
            EntityDto(
                key = "processStep", cardinality = "COLLECTION", title = "Process step",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT"))
            ),
            EntityDto(
                key = "tool", cardinality = "COLLECTION", title = "Tool",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT"))
            ),
            EntityDto(
                key = "rawMaterial", cardinality = "COLLECTION", title = "Raw material",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT"))
            ),
        )
    )

    private fun row(entity: String, key: String, name: String) =
        DraftRow(id = dwRowId(entity, key), values = mapOf("name" to JsonPrimitive(name)))

    private fun typedOneFieldOffline() = StageDraft(
        stageId = spec.key,
        values = mapOf("currentProblems" to JsonPrimitive("The vat has gone cold.")),
        rows = emptyList(),
        stageSeen = false,
    )

    private fun build(stored: StageDraft?, record: StageSyncRecord? = null) =
        buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, record)).body

    @Test
    fun `a stage seeded blank after a failed download claims no authority`() {
        val body = build(typedOneFieldOffline())

        assertFalse(
            "a draft that has never seen the server's copy must not ask for a sweep",
            body.replaceCollections
        )
        assertEquals(1, body.entries.size)
        assertEquals("traditionalProcess", body.entries.single().entityKey)
    }

    @Test
    fun `a stage read successfully from the server does claim authority`() {
        val body = build(typedOneFieldOffline().copy(stageSeen = true))
        assertTrue(body.replaceCollections)
    }

    @Test
    fun `a stage the server has merely ACKNOWLEDGED claims nothing`() {
        val body = build(typedOneFieldOffline(), StageSyncRecord(signature = "abc123"))
        assertFalse(
            "a signature says these bytes landed, never that this draft is what the server holds",
            body.replaceCollections
        )
        assertTrue("and the singleton must still go up as a merge", body.entries.single().merge)
    }

    @Test
    fun `authority is having read, and nothing else is accepted as evidence of it`() {
        val neverRead = typedOneFieldOffline()
        assertFalse(isAuthoritative(neverRead, null))
        assertFalse(isAuthoritative(neverRead, StageSyncRecord(signature = "")))
        assertFalse(isAuthoritative(null, null))
        assertTrue(isAuthoritative(neverRead.copy(stageSeen = true), null))
        assertFalse(isAuthoritative(neverRead, StageSyncRecord(signature = "x")))
        assertFalse(
            isAuthoritative(neverRead, StageSyncRecord(signature = "x", syncedAt = "2026-08-12T00:00:00Z"))
        )
    }

    @Test
    fun `deleting the last row of a collection is stated on the wire`() {
        val draft = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            rows = listOf(row("processStep", "s1", "Tying")),
            stageSeen = true,
            emptiedEntities = listOf("tool"),
        )
        val body = build(draft)

        assertTrue(body.replaceCollections)
        assertEquals(listOf("tool"), body.emptiedEntities)
        assertFalse(body.emptiedEntities.contains("processStep"))
    }

    @Test
    fun `a deletion is never asserted without the knowledge that justifies one`() {
        val draft = typedOneFieldOffline().copy(emptiedEntities = listOf("tool"))
        val body = build(draft)
        assertFalse(body.replaceCollections)
        assertEquals(emptyList<String>(), body.emptiedEntities)
    }

    @Test
    fun `a stale entity key cannot be sent as a deletion instruction`() {
        val draft = StageDraft(
            stageId = spec.key,
            stageSeen = true,
            emptiedEntities = listOf("tool", "somethingTheRegistryDropped", "traditionalProcess"),
        )
        assertEquals(listOf("tool"), build(draft).emptiedEntities)
    }

    @Test
    fun `the payload that swept stage 5 can no longer ask for a sweep`() {
        val body = build(typedOneFieldOffline())
        assertEquals(1, body.entries.size)
        assertEquals(emptyList<String>(), body.emptiedEntities)
        assertFalse(body.replaceCollections)
    }

    @Test
    fun `a draft holding every row still replaces them wholesale`() {
        val draft = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            rows = listOf(
                row("processStep", "s1", "Tying"),
                row("processStep", "s2", "Dyeing"),
                row("tool", "t1", "Charkha"),
            ),
            stageSeen = true,
        )
        val body = build(draft)
        assertTrue(body.replaceCollections)
        assertEquals(4, body.entries.size)
        assertEquals(
            listOf("traditionalProcess", "processStep", "processStep", "tool"),
            body.entries.map { it.entityKey }
        )
    }

    @Test
    fun `every row still travels with the client key the server matches on`() {
        val draft = StageDraft(
            stageId = spec.key,
            rows = listOf(row("processStep", "s1", "Tying")),
            stageSeen = true,
        )
        val entry = build(draft).entries.single { it.entityKey == "processStep" }
        assertEquals(JsonPrimitive("s1"), entry.data["_clientKey"])
        assertEquals(0, entry.ordinal)
    }
}
