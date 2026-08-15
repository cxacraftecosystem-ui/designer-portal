package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Reason for the override" is required once either override count is filled in — on the phone too.
 *
 * The rule lives in `stage_schema._CONDITIONALLY_REQUIRED` and is applied by `_check_conditional` on
 * EVERY save, deliberately outside the `enforce_required` branch. The handset had no arm for it, so
 * a designer at stage 18 could type `designsCountOverride = 24`, leave the reason blank, and see a
 * stage bar reading 100% — the registry marks the reason `required: false`, so `computeStageCompleteness`
 * never counts it either. The refusal existed the whole time; it simply could not be shown until the
 * phone next found signal, which on this surface is a fortnight, in another district, as a card about
 * a stage the designer has stopped thinking about.
 *
 * These tests pin the copy to the original: same trigger set, same "already answered" bail, same
 * sentence. If the server's table grows a second entry, this file is where the phone's copy is proved
 * to have grown with it.
 */
class DwConditionalRequirementTest {

    private val designsOverride =
        FieldDto(key = "designsCountOverride", label = "Number of designs (override)", type = "INT")
    private val prototypesOverride =
        FieldDto(key = "prototypesCountOverride", label = "Number of prototypes (override)", type = "INT")
    private val reason =
        FieldDto(key = "countOverrideReason", label = "Reason for the override", type = "LONG_TEXT")

    /** Stage 18's singleton, as the registry asset declares it — note all three are `required = false`. */
    private val outcomes = EntityDto(
        key = "outcomes",
        cardinality = "SINGLETON",
        title = "Outcomes",
        fields = listOf(designsOverride, prototypesOverride, reason),
    )

    private fun validate(vararg values: Pair<String, JsonPrimitive>) =
        DwValues.validate(outcomes, mapOf(*values), enforceRequired = false)

    @Test
    fun `an override count with no reason is refused, exactly as the server refuses it`() {
        val errors = validate("designsCountOverride" to JsonPrimitive(24))
        assertEquals(setOf("countOverrideReason"), errors.keys)
        // The wording is the server's, character for character (stage_schema._check_conditional), so
        // the inline mark shown offline and the refusal card shown after a sync say the same thing.
        assertEquals(
            "Reason for the override is required once Number of designs (override) or " +
                "Number of prototypes (override) is filled in.",
            errors.getValue("countOverrideReason"),
        )
    }

    /** Either trigger fires it — the server's rule is `any`, not `all`. */
    @Test
    fun `the prototypes override triggers it on its own`() {
        assertEquals(
            setOf("countOverrideReason"),
            validate("prototypesCountOverride" to JsonPrimitive(3)).keys,
        )
    }

    /**
     * NOT GATED ON enforceRequired.
     *
     * This is the assertion that would have caught a "simplification" folding the check into the
     * `enforceRequired && field.required` branch above it. No client ever sets `submit` true, so
     * `enforceRequired` is never on in this app — a rule moved under it is a rule that never runs.
     */
    @Test
    fun `it fires while the stage is still a draft`() {
        val draft = DwValues.validate(
            outcomes,
            mapOf("designsCountOverride" to JsonPrimitive(24)),
            enforceRequired = false,
        )
        val submitted = DwValues.validate(
            outcomes,
            mapOf("designsCountOverride" to JsonPrimitive(24)),
            enforceRequired = true,
        )
        assertTrue("a draft must carry the mark too", draft.containsKey("countOverrideReason"))
        assertEquals(draft["countOverrideReason"], submitted["countOverrideReason"])
    }

    @Test
    fun `an answered reason clears it`() {
        assertTrue(
            validate(
                "designsCountOverride" to JsonPrimitive(24),
                "countOverrideReason" to JsonPrimitive("The register counts a set as one design."),
            ).isEmpty()
        )
    }

    /** A blank string is not an answer — `isFilled` decides that, here as everywhere else. */
    @Test
    fun `a whitespace-only reason does not clear it`() {
        assertTrue(
            validate(
                "designsCountOverride" to JsonPrimitive(24),
                "countOverrideReason" to JsonPrimitive("   "),
            ).containsKey("countOverrideReason")
        )
    }

    /** Neither override touched: the reason stays optional, which is the ordinary case on stage 18. */
    @Test
    fun `no override means no requirement`() {
        assertTrue(validate().isEmpty())
        assertTrue(validate("countOverrideReason" to JsonPrimitive("only a note")).isEmpty())
    }

    /**
     * A field already carrying its own complaint keeps it.
     *
     * The specific message ("is not a valid whole number") names what is wrong with what was typed;
     * replacing it with the conditional sentence would tell the designer to fill in a box they have
     * already filled in.
     */
    @Test
    fun `a coercion error on the dependent field is not overwritten`() {
        val entity = outcomes.copy(
            fields = listOf(designsOverride, prototypesOverride, reason.copy(type = "MONEY")),
        )
        val errors = DwValues.validate(
            entity,
            mapOf(
                "designsCountOverride" to JsonPrimitive(24),
                "countOverrideReason" to JsonPrimitive("NaN"),
            ),
            enforceRequired = false,
        )
        assertTrue(errors.getValue("countOverrideReason").contains("not a valid amount"))
        assertFalse(errors.getValue("countOverrideReason").contains("is required once"))
    }

    /** Every other entity in the registry is untouched by the table. */
    @Test
    fun `an entity with no rule is unaffected`() {
        val costLine = EntityDto(
            key = "costLine",
            cardinality = "COLLECTION",
            title = "Cost line",
            fields = listOf(FieldDto(key = "amount", label = "Amount", type = "MONEY")),
        )
        assertTrue(
            DwValues.validate(
                costLine,
                mapOf("amount" to JsonPrimitive("120.00")),
                enforceRequired = false,
            ).isEmpty()
        )
    }
}
