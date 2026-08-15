package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic a designer is shown, with a designer's own questions in it.
 *
 * ONE SCORER, and this is what it must agree with: `stage_completeness(..., custom_fields=,
 * custom_values=)` in `backend/app/services/stage_schema.py:1323-1352` and `scoreStageData` in
 * `frontend/lib/designWorkshopStore.ts`. Three surfaces print this number — the stage bar, the
 * 22-stage index and the report's Outstanding column — and the server refuses a submit on its own
 * copy of it, so a rule that differs by one anywhere produces a stage that reads 100% on the form and
 * 422s on submit, with nothing on either surface able to explain the disagreement.
 */
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

    /** A stage with no singleton at all — eight of the twenty-two are shaped like this. */
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

    // ── Counting ────────────────────────────────────────────────────────────────────────────────

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

    /**
     * BETWEEN THE SINGLETON AND THE COLLECTIONS, AND THE ORDER IS NOT COSMETIC.
     *
     * `missing` is printed in order and truncated — the report's Outstanding column and the phone's
     * report screen take the first three — so whatever this list puts first is what a designer and a
     * ministry officer actually read. It is also the order the form draws them in, so a "still
     * missing" link and the form agree about which question comes first.
     */
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

    /**
     * FILED UNDER THE BARE LABEL, like a singleton field and unlike a collection field.
     *
     * That is what makes a duplicate LABEL a definition-time refusal on the server rather than a
     * document disagreeing with itself: two required questions filing the same string collapse into
     * one row through the de-duplication while `requiredTotal` still counts two. The behaviour is
     * pinned here so that anybody tempted to prefix the label ("Loom audit: …") sees what it breaks.
     */
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

    /**
     * A RETIRED FIELD IS SKIPPED, exactly as a deprecated registry field is.
     *
     * It is no longer asked, so counting it would make a stage permanently incomplete because of a
     * question the designer corrected — and the server skips it too, so counting it here would also
     * make the phone and the office disagree about one stage of one workshop.
     */
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

    /**
     * SCORED KEY BY KEY, AND THE CONTAINER IS NEVER TESTED AS A WHOLE.
     *
     * `DwValues.isFilled` answers true for any JsonObject with keys, so a bucket holding twenty blank
     * answers is truthy. A scorer that asked "is there a custom bucket" would report a stage complete
     * on the strength of the bucket existing.
     */
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

    /** `false` and `0` are answers — "this cluster has no power supply" is a finding, not a blank. */
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

    // ── The default, and the fan-out ────────────────────────────────────────────────────────────

    /**
     * A CALLER WITH NO DEFINITION SCORES EXACTLY AS THIS FUNCTION DID BEFORE THE FEATURE EXISTED.
     *
     * It cannot invent a higher total and it cannot invent a lower one — which is also why
     * `StageIndexScreen` refuses the SERVER's score rather than adopting a number computed under a
     * definition this device does not hold.
     */
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

    /**
     * The fan-out reads each stage's questions and each stage's answers, and does not cross them.
     *
     * A workshop-wide walk that handed stage 5's bucket to stage 11's fields would score every stage
     * off one stage's answers — which on a 22-stage index is a column of numbers that all look
     * plausible.
     */
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
        // A collections-only stage whose ONLY required question is the designer's own. This is the
        // shape eight of the twenty-two stages have, and the one a designer is likeliest to extend.
        assertEquals(1, scores.getValue(collectionsOnly.key).requiredTotal)
        assertEquals(0, scores.getValue(collectionsOnly.key).requiredFilled)
        assertEquals(listOf("Sheets"), scores.getValue(collectionsOnly.key).missing)

        // And with no definition held, the same draft scores as it always did.
        val without = computeWorkshopCompleteness(schema, draft, null).associateBy { it.stageKey }
        assertEquals(1, without.getValue(stage.key).requiredTotal)
        assertEquals(0, without.getValue(collectionsOnly.key).requiredTotal)
    }

    /**
     * ASSEMBLY, NOT ARITHMETIC. The readiness screen counts nothing of its own, and its address book
     * can place a custom label so the "still missing" link lands on the section that asks it.
     */
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
        // The address is the SECTION's rendering identity, which is the string the stage form draws
        // the section under; anything else lands on a stage with nothing highlighted.
        assertEquals("_custom:sketchAudit", item.address?.entityKey)
        assertEquals("sheets", item.address?.anchorFieldKey)
    }
}
