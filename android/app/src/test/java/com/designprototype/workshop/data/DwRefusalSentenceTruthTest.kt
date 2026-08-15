package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE BULLET, TWO CONTRADICTORY SENTENCES, IN THE ONE CASE A DESIGNER CANNOT WORK OUT FOR THEMSELVES.
 *
 * [DwStageRefusal.sentence] appended [DwHeld.sentence] — every arm of which ended "…and is still in
 * the box above" — and then, when [DwStageRefusal.drawn] was false, appended "This copy of the app has
 * no box for that question". The designer was pointed at a box and told the box does not exist, in one
 * line, about the refusal they are least equipped to interpret.
 *
 * NOT A HYPOTHETICAL, AND IT NEEDS NO CUSTOM SECTION. `StageDraft.values` is
 * `Map<String, JsonElement>` precisely so a key written by a newer build survives a round trip
 * through an older one; `buildStageBody` sends what the draft holds; `validate_entry` on the server
 * validates every key ITS registry declares and files a per-field message for the ones it cannot
 * read. So an older handset opening a draft touched by a newer one is refused a key its own registry
 * has never heard of, `entity?.fields?.firstOrNull { … }` is null, and `drawn` is false.
 *
 * These pin the property rather than the wording: no bullet may claim BOTH that the answer is in a
 * box above and that there is no such box.
 */
class DwRefusalSentenceTruthTest {

    private val spec = StageDto(
        number = 10,
        key = "DESIGN_BRIEF",
        title = "Design Brief & Concept",
        entities = listOf(
            EntityDto(
                key = "designBrief", cardinality = "SINGLETON", title = "Design brief",
                fields = listOf(
                    FieldDto(key = "intendedPriceLow", label = "Intended price from", type = "MONEY"),
                    FieldDto(key = "oldRate", label = "Old rate", type = "MONEY", deprecated = true),
                ),
            ),
        ),
    )

    private fun entries() = listOf(
        StageEntryBody(entityKey = "designBrief", data = JsonObject(emptyMap())),
    )

    private fun errors(vararg pairs: Pair<String, Map<String, String>>): Map<String, JsonElement> =
        pairs.associate { (scope, fields) ->
            scope to buildJsonObject { fields.forEach { (key, message) -> put(key, message) } }
        }

    private fun only(vararg pairs: Pair<String, Map<String, String>>): DwStageRefusal =
        dwDecodeStageRefusals(spec, entries(), errors(*pairs)).refusals.single()

    @Test
    fun `a question this build cannot draw is never said to be in a box above`() {
        // The wire case: an older build, a draft touched by a newer one, a key the server knows and
        // this registry does not.
        val refusal = only(
            "designBrief" to mapOf("addedLastRelease" to "Added last release is not a valid money"),
        )
        assertFalse("nothing draws a key this registry has never heard of", refusal.drawn)
        assertTrue(refusal.sentence, refusal.sentence.contains("has no box for that question"))
        assertFalse(
            "it cannot say both. The bullet was:\n${refusal.sentence}",
            refusal.sentence.contains("is still in the box above"),
        )
        assertTrue(
            "it must still say the typed text survived:\n${refusal.sentence}",
            refusal.sentence.contains("What you typed is still on this phone"),
        )
    }

    @Test
    fun `a deprecated field is the same case and reads the same way`() {
        val refusal = only("designBrief" to mapOf("oldRate" to "Old rate is not a valid money"))
        assertFalse(refusal.drawn)
        assertFalse(refusal.sentence, refusal.sentence.contains("is still in the box above"))
        assertTrue(refusal.sentence, refusal.sentence.startsWith("Old rate: "))
    }

    /**
     * The ordinary case must not have regressed while the contradictory one was fixed: a field this
     * build DOES draw still points at its box, because that is the whole use of the line.
     */
    @Test
    fun `a question this build draws still points at its box`() {
        val refusal = only(
            "designBrief" to mapOf("intendedPriceLow" to "Intended price from is not a valid money"),
        )
        assertTrue(refusal.drawn)
        assertTrue(refusal.sentence, refusal.sentence.contains("is still in the box above"))
        assertFalse(refusal.sentence, refusal.sentence.contains("has no box for that question"))
    }

    /**
     * WHAT THE REPOSITORY HOLDS IS STILL SAID, IN THE WORD, and it is the half [DwHeld] kept. Measured
     * on the live API for this exact stage: `intendedPriceLow` had been saved as 6500 and came back
     * `"6500.00"` after the refusal, while `intendedPriceHigh` had never been stored and came back
     * absent — two different facts that must not read alike.
     */
    @Test
    fun `the three holdings each keep their own sentence`() {
        val unrecorded = only("designBrief" to mapOf("intendedPriceLow" to "not a valid money"))
        assertTrue(unrecorded.sentence, unrecorded.sentence.contains("UNRECORDED here."))

        val holds = unrecorded.copy(held = DwHeld.holding("6500.00"))
        assertTrue(holds.sentence, holds.sentence.contains("The repository still holds: “6500.00”."))

        val nothing = unrecorded.copy(held = DwHeld.nothing())
        assertTrue(nothing.sentence, nothing.sentence.contains("holds no answer to this question."))
    }
}
