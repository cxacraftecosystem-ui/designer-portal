package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwCustomCompletenessTest {

    private val stage = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "traditionalProcess", cardinality = "SINGLETON", title = "Traditional process",
                fields = listOf(
                    FieldDto(key = "currentProblems", label = "Current problems", type = "LONG_TEXT", required = true),
                ),
            ),
            EntityDto(
                key = "processStep", cardinality = "COLLECTION", title = "Process step",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT", required = true)),
            ),
        ),
    )

    private val collectionsOnly = StageDto(
        number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development",
        entities = listOf(
            EntityDto(
                key = "sketch", cardinality = "COLLECTION", title = "Sketch",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT", required = true)),
            ),
        ),
    )

    private fun custom(
        key: String,
        label: String = key,
        required: Boolean = false,
        retired: Boolean = false,
    ) = DwCustomFieldDto(id = "id-$key", key = key, label = label, required = required, retired = retired)

    private fun rows(vararg values: Map<String, JsonElement>) =
        mapOf("processStep" to values.toList())

    @Test
    fun `a required custom field raises the required total and appears in missing`() {
        val score = computeStageCompleteness(
            stage = stage,
            singleton = mapOf("currentProblems" to JsonPrimitive("Slow warping")),
            collections = emptyMap(),
            customFields = listOf(custom("loomsWorking", "Looms in working order", required = true)),
            customValues = emptyMap(),
        )
        assertEquals(2, score.requiredTotal)
        assertEquals(1, score.requiredFilled)
        assertEquals(listOf("Looms in working order"), score.missing)
        assertFalse(score.isComplete)
    }

    @Test
    fun `an optional custom field moves the optional counters and never the required ones`() {
        val score = computeStageCompleteness(
            stage = stage,
            singleton = mapOf("currentProblems" to JsonPrimitive("x")),
            collections = emptyMap(),
            customFields = listOf(custom("note"), custom("other")),
            customValues = mapOf("note" to JsonPrimitive("something")),
        )
        assertEquals(1, score.requiredTotal)
        assertEquals(2, score.optionalTotal)
        assertEquals(1, score.optionalFilled)
        assertTrue(score.missing.isEmpty())
        assertEquals(100, score.percent)
    }

    @Test
    fun `custom questions are counted between the singleton and the collections`() {
        val score = computeStageCompleteness(
            stage = stage,
            singleton = emptyMap(),
            collections = rows(emptyMap()),
            customFields = listOf(custom("loomsWorking", "Looms in working order", required = true)),
            customValues = emptyMap(),
        )
        assertEquals(
            listOf("Current problems", "Looms in working order", "Process step: Name"),
            score.missing,
        )
    }

    @Test
    fun `a custom label is filed bare, and two of them collapse in missing while the total counts both`() {
        val score = computeStageCompleteness(
            stage = stage,
            singleton = mapOf("currentProblems" to JsonPrimitive("x")),
            collections = emptyMap(),
            customFields = listOf(
                custom("a", "Looms", required = true),
                custom("b", "Looms", required = true),
            ),
            customValues = emptyMap(),
        )
        assertEquals(listOf("Looms"), score.missing)
        assertEquals(3, score.requiredTotal)
        assertEquals(1, score.requiredFilled)
    }

    @Test
    fun `a retired required field is not counted, filled or not`() {
        val fields = listOf(custom("old", "How many looms?", required = true, retired = true))
        val blank = computeStageCompleteness(stage, emptyMap(), emptyMap(), fields, emptyMap())
        val answered = computeStageCompleteness(
            stage, emptyMap(), emptyMap(), fields, mapOf("old" to JsonPrimitive(12)),
        )
        assertEquals(1, blank.requiredTotal)
        assertEquals(1, answered.requiredTotal)
        assertEquals(listOf("Current problems"), blank.missing)
        assertEquals(0, blank.optionalTotal)
    }

    @Test
    fun `a container of blanks is not an answer`() {
        val bucket: Map<String, JsonElement> = mapOf(
            "a" to JsonPrimitive(""),
            "b" to JsonPrimitive("   "),
        )
        assertTrue("the trap: the container itself is truthy", DwValues.isFilled(JsonObject(bucket)))
        val score = computeStageCompleteness(
            stage = stage,
            singleton = mapOf("currentProblems" to JsonPrimitive("x")),
            collections = emptyMap(),
            customFields = listOf(custom("a", "A", required = true), custom("b", "B", required = true)),
            customValues = bucket,
        )
        assertEquals(2, score.requiredTotal - 1)
        assertEquals(1, score.requiredFilled)
        assertEquals(listOf("A", "B"), score.missing)
    }

    @Test
    fun `false and zero count as answered, as they do for a registry field`() {
        val score = computeStageCompleteness(
            stage = stage,
            singleton = mapOf("currentProblems" to JsonPrimitive("x")),
            collections = emptyMap(),
            customFields = listOf(custom("hasPower", "Has power", required = true), custom("looms", "Looms", required = true)),
            customValues = mapOf("hasPower" to JsonPrimitive(false), "looms" to JsonPrimitive(0)),
        )
        assertEquals(3, score.requiredTotal)
        assertEquals(3, score.requiredFilled)
        assertTrue(score.isComplete)
    }

    @Test
    fun `omitting the two new arguments changes nothing`() {
        val before = computeStageCompleteness(stage, mapOf("currentProblems" to JsonPrimitive("x")), emptyMap())
        val after = computeStageCompleteness(
            stage, mapOf("currentProblems" to JsonPrimitive("x")), emptyMap(),
            customFields = emptyList(), customValues = emptyMap(),
        )
        assertEquals(before, after)
        assertEquals(1, before.requiredTotal)
    }

    @Test
    fun `the whole-workshop walk pairs each stage's questions with its own answers`() {
        val schema = SchemaResponse(version = "v", stages = listOf(stage, collectionsOnly))
        val definition = DwCustomCache(
            workshopId = "w1", customSchemaVersion = "d1", complete = true,
            sections = listOf(
                DwCustomSectionDto(
                    key = "loomAudit", stageKey = stage.key,
                    fields = listOf(custom("looms", "Looms", required = true)),
                ),
                DwCustomSectionDto(
                    key = "sketchAudit", stageKey = collectionsOnly.key,
                    fields = listOf(custom("sheets", "Sheets", required = true)),
                ),
            ),
        )
        val draft = WorkshopDraft(
            workshopId = "w1",
            stages = mapOf(
                stage.key to StageDraft(stageId = stage.key, custom = mapOf("looms" to JsonPrimitive(12))),
                collectionsOnly.key to StageDraft(stageId = collectionsOnly.key),
            ),
        )
        val scores = computeWorkshopCompleteness(schema, draft, definition).associateBy { it.stageKey }

        assertEquals(2, scores.getValue(stage.key).requiredTotal)
        assertEquals(1, scores.getValue(stage.key).requiredFilled)
        assertEquals(1, scores.getValue(collectionsOnly.key).requiredTotal)
        assertEquals(0, scores.getValue(collectionsOnly.key).requiredFilled)
        assertEquals(listOf("Sheets"), scores.getValue(collectionsOnly.key).missing)

        val without = computeWorkshopCompleteness(schema, draft, null).associateBy { it.stageKey }
        assertEquals(1, without.getValue(stage.key).requiredTotal)
        assertEquals(0, without.getValue(collectionsOnly.key).requiredTotal)
    }

    @Test
    fun `submission readiness counts the scorer's numbers and can address a custom question`() {
        val schema = SchemaResponse(version = "v", stages = listOf(collectionsOnly))
        val definition = DwCustomCache(
            workshopId = "w1", customSchemaVersion = "d1", complete = true,
            sections = listOf(
                DwCustomSectionDto(
                    key = "sketchAudit", stageKey = collectionsOnly.key, title = "Sketch audit",
                    fields = listOf(custom("sheets", "Sheets used", required = true)),
                ),
            ),
        )
        val draft = WorkshopDraft(workshopId = "w1", stages = emptyMap())
        val readiness = DwSubmissionReadiness.assess(schema, draft, "w1", definition)

        val item = readiness.blocking.single { it.label == "Sheets used" }
        assertEquals(collectionsOnly.key, item.stageKey)
        assertEquals("_custom:sketchAudit", item.address?.entityKey)
        assertEquals("sheets", item.address?.anchorFieldKey)
    }
}
