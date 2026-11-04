package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `a question this build draws still points at its box`() {
        val refusal = only(
            "designBrief" to mapOf("intendedPriceLow" to "Intended price from is not a valid money"),
        )
        assertTrue(refusal.drawn)
        assertTrue(refusal.sentence, refusal.sentence.contains("is still in the box above"))
        assertFalse(refusal.sentence, refusal.sentence.contains("has no box for that question"))
    }

    @Test
    fun `the three holdings each keep their own sentence`() {
        val unrecorded = only("designBrief" to mapOf("intendedPriceLow" to "not a valid money"))
        assertTrue(unrecorded.sentence, unrecorded.sentence.contains("it is not shown here."))

        val holds = unrecorded.copy(held = DwHeld.holding("6500.00"))
        assertTrue(holds.sentence, holds.sentence.contains("The answer saved online is still: “6500.00”."))

        val nothing = unrecorded.copy(held = DwHeld.nothing())
        assertTrue(nothing.sentence, nothing.sentence.contains("no earlier answer to this question online."))
    }
}
