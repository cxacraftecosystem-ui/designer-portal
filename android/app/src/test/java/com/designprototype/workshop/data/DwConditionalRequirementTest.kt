package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ── THE RULE HAS TO EXIST *AND* BE CALLED ────────────────────────────────────────────────────
    //
    // Everything above is run against `outcomes` as this file declares it. That proves the port is
    // faithful and proves nothing at all about whether a designer ever meets it, and the two failures
    // it cannot see are exactly the two that were found:
    //
    //   1. the entity this file hand-builds drifts from the one the APK ships — `checkConditional`
    //      bails with `entity.field(dependent) ?: return`, silently, and every test above still
    //      passes;
    //   2. nothing in production calls [DwValues.validate] at all. That was true when the port landed:
    //      the rule was ported, tested and unreachable, which is the same as not having it. A ported
    //      rule with no caller is a rule that does not exist.
    //
    // The two tests below are the ones that fail in either case.

    /** The module root differs between the IDE and Gradle; same walk-up the shipped-registry tests use. */
    private fun repoFile(vararg relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }

    private fun asset(name: String): File = repoFile(
        "src/main/assets/$name",
        "app/src/main/assets/$name",
        "android/app/src/main/assets/$name",
    )

    private fun source(name: String): File = repoFile(
        "src/main/java/com/designprototype/workshop/$name",
        "app/src/main/java/com/designprototype/workshop/$name",
        "android/app/src/main/java/com/designprototype/workshop/$name",
    )

    @Test
    fun `the entity the APK ships fires the rule, with its own labels in the sentence`() {
        val schema: SchemaResponse = Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString(SchemaResponse.serializer(), asset("design-workshop-schema.json").readText())

        val shipped = schema.stages
            .flatMap { it.entities }
            .firstOrNull { it.key == "outcomes" }
        assertTrue(
            "the registry the APK ships no longer declares the `outcomes` entity the rule is keyed " +
                "by — CONDITIONALLY_REQUIRED is keyed by entity key and would now match nothing",
            shipped != null,
        )
        val entity = shipped!!

        val errors = DwValues.validate(
            entity,
            mapOf("designsCountOverride" to JsonPrimitive(24)),
            // The production call's own argument — see `marks` in StageScreen.kt.
            enforceRequired = false,
        )

        // Built from the SHIPPED labels rather than repeated from this file, so a renamed label in the
        // asset moves the expectation with it and a renamed KEY fails outright — which is the drift
        // `checkConditional`'s `?: return` would otherwise swallow in silence.
        val labelOf = { key: String ->
            entity.field(key)?.label
                ?: throw AssertionError("the shipped `outcomes` entity no longer declares `$key`")
        }
        assertEquals(
            "${labelOf("countOverrideReason")} is required once ${labelOf("designsCountOverride")} " +
                "or ${labelOf("prototypesCountOverride")} is filled in.",
            errors["countOverrideReason"],
        )
    }

    @Test
    fun `the stage form calls the validator, and draws what it says on the box`() {
        /*
          THE CALLER GUARD. When the rule was ported, `DwValues.validate` had no caller anywhere in
          `android/app/src` outside this file — so the refusal it exists to show offline was still
          only ever met a fortnight later, as a sync card about a stage finished in another district.
          It is now called once, in `EntitySection`, and the result is drawn on the box itself.

          Asserted on the source because there is no other way to reach it from a JVM unit test:
          `EntitySection` is a private @Composable. The same argument DwTier2GateTest makes for
          scanning DwTier2ModelUi.kt — when the whole of a protection is the presence or absence of
          one call, the presence or absence of that call is the thing to assert.
        */
        val screen = source("ui/designworkshop/StageScreen.kt").readText()

        assertTrue(
            "StageScreen.kt no longer calls DwValues.validate. The handset's copy of the server's " +
                "`validate_entry` is then dead code again, and stage 18's override reason is refused " +
                "for the first time on a sync card a fortnight later. Wire it back into the marks " +
                "`EntitySection` draws, not into a new second validator.",
            Regex("""DwValues\.validate\(""").containsMatchIn(screen),
        )
        assertTrue(
            "StageScreen.kt computes validation marks and no longer hands them to a field as " +
                "`error = marks[...]`. A mark nothing draws is the same as no mark: the designer " +
                "meets the refusal only after a sync.",
            Regex("""error\s*=\s*marks\[""").containsMatchIn(screen),
        )
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
