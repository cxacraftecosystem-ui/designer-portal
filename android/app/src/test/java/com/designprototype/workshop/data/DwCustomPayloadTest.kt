package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwCustomPayloadTest {

    private val spec = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "traditionalProcess", cardinality = "SINGLETON", title = "Traditional process",
                fields = listOf(FieldDto(key = "currentProblems", label = "Current problems", type = "LONG_TEXT")),
            ),
            EntityDto(
                key = "processStep", cardinality = "COLLECTION", title = "Process step",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT")),
            ),
        ),
    )

    private fun build(
        stored: StageDraft?,
        authoritative: Boolean,
        customHeld: Boolean = true,
    ) = buildStageBody(spec, stored, emptyMap(), authoritative, customHeld)

    private fun customEntry(stored: StageDraft?, authoritative: Boolean, customHeld: Boolean = true) =
        build(stored, authoritative, customHeld).body.entries.firstOrNull { it.entityKey == "_custom" }

    @Test
    fun `a never-read client with no custom answers emits no entry`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            customSeen = true,
        )
        assertNull(customEntry(stored, authoritative = false, customHeld = false))
        assertNull(customEntry(stored, authoritative = false, customHeld = true))
        assertNull("a draft with no custom bucket at all", customEntry(null, authoritative = false))
    }

    @Test
    fun `an authoritative client that holds no definition still emits no empty container`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            customSeen = true,
        )
        assertNull(customEntry(stored, authoritative = true, customHeld = false))
    }

    @Test
    fun `a client that has never read the server's container never sends an empty one`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            customSeen = false,
        )
        assertNull(customEntry(stored, authoritative = true, customHeld = true))
    }

    @Test
    fun `a client that has never read the container sends its own answers as a merge`() {
        val stored = StageDraft(
            stageId = spec.key,
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
            customSeen = false,
        )
        val entry = customEntry(stored, authoritative = true, customHeld = true)!!
        assertTrue("authority over the stage is not authority over this row", entry.merge)
        assertEquals(JsonPrimitive(12), entry.data["loomsWorking"])
    }

    @Test
    fun `an authoritative client that has read the container sends an empty one as a clearance`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            customSeen = true,
        )
        val entry = customEntry(stored, authoritative = true, customHeld = true)
        assertEquals("_custom", entry?.entityKey)
        assertTrue(entry!!.data.isEmpty())
        assertFalse("a replace, and honestly so", entry.merge)
    }

    @Test
    fun `a never-read client with one answer sends it as a merge`() {
        val stored = StageDraft(stageId = spec.key, custom = mapOf("loomsWorking" to JsonPrimitive(12)))
        val entry = customEntry(stored, authoritative = false)!!
        assertTrue(entry.merge)
        assertEquals(JsonPrimitive(12), entry.data["loomsWorking"])
    }

    @Test
    fun `an answered container is sent even when this device has lost the definition`() {
        val stored = StageDraft(stageId = spec.key, custom = mapOf("loomsWorking" to JsonPrimitive(12)))
        val entry = customEntry(stored, authoritative = false, customHeld = false)
        assertEquals(JsonPrimitive(12), entry?.data?.get("loomsWorking"))
    }

    @Test
    fun `an authoritative client sends a replace, and merge is left at its default so it is omitted`() {
        val stored = StageDraft(
            stageId = spec.key,
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
            customSeen = true,
        )
        val entry = customEntry(stored, authoritative = true)!!
        assertFalse(entry.merge)
        assertTrue(build(stored, authoritative = true).body.entries.none { it.merge })
    }

    @Test
    fun `underscore-prefixed keys never leave the phone`() {
        val stored = StageDraft(
            stageId = spec.key,
            custom = mapOf(
                "loomsWorking" to JsonPrimitive(12),
                "_scratch" to JsonPrimitive("local only"),
            ),
        )
        val entry = customEntry(stored, authoritative = true)!!
        assertEquals(setOf("loomsWorking"), entry.data.keys)
    }

    @Test
    fun `_custom can never enter emptiedEntities`() {
        val stored = StageDraft(
            stageId = spec.key,
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
            emptiedEntities = listOf("processStep", "_custom"),
        )
        val body = build(stored, authoritative = true).body
        assertEquals(listOf("processStep"), body.emptiedEntities)
    }

    @Test
    fun `the custom answers travel in their own entry and not in the singleton's data`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("Slow warping")),
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
        )
        val body = build(stored, authoritative = true).body
        val singleton = body.entries.single { it.entityKey == "traditionalProcess" }
        assertEquals(setOf("currentProblems"), singleton.data.keys)
        assertEquals(1, body.entries.count { it.entityKey == "_custom" })
    }

    @Test
    fun `the custom entry is last, after every registry entry`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            rows = listOf(DraftRow(id = dwRowId("processStep", "r1"), values = mapOf("name" to JsonPrimitive("Warp")))),
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
        )
        val keys = build(stored, authoritative = true).body.entries.map { it.entityKey }
        assertEquals(listOf("traditionalProcess", "processStep", "_custom"), keys)
    }

    @Test
    fun `an empty container is an instruction only where the definition asks something on this stage`() {
        val held = DwCustomCache(
            workshopId = "w1", customSchemaVersion = "d1", complete = true,
            sections = listOf(
                DwCustomSectionDto(
                    key = "loomAudit", stageKey = spec.key,
                    fields = listOf(DwCustomFieldDto(key = "loomsWorking", label = "Looms")),
                ),
            ),
        )
        assertTrue(dwCustomHeldFor(held, spec.key))
        assertFalse(dwCustomHeldFor(held, "SKETCH_DEVELOPMENT"))
        assertFalse(dwCustomHeldFor(null, spec.key))
        assertFalse(dwCustomHeldFor(DwCustomCache(workshopId = "w1", complete = true), spec.key))
        val retiredOnly = held.copy(
            sections = listOf(
                held.sections.single().copy(
                    fields = listOf(DwCustomFieldDto(key = "looms", label = "Looms", retired = true)),
                ),
            ),
        )
        assertTrue(dwCustomHeldFor(retiredOnly, spec.key))
    }
}
