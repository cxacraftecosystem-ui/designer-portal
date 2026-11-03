package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwConditionalRequirementTest {

    private val designsOverride =
        FieldDto(key = "designsCountOverride", label = "Number of designs (override)", type = "INT")
    private val prototypesOverride =
        FieldDto(key = "prototypesCountOverride", label = "Number of prototypes (override)", type = "INT")
    private val reason =
        FieldDto(key = "countOverrideReason", label = "Reason for the override", type = "LONG_TEXT")

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
        assertEquals(
            "Reason for the override is required once Number of designs (override) or " +
                "Number of prototypes (override) is filled in.",
            errors.getValue("countOverrideReason"),
        )
    }

    @Test
    fun `the prototypes override triggers it on its own`() {
        assertEquals(
            setOf("countOverrideReason"),
            validate("prototypesCountOverride" to JsonPrimitive(3)).keys,
        )
    }

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

    @Test
    fun `a whitespace-only reason does not clear it`() {
        assertTrue(
            validate(
                "designsCountOverride" to JsonPrimitive(24),
                "countOverrideReason" to JsonPrimitive("   "),
            ).containsKey("countOverrideReason")
        )
    }

    @Test
    fun `no override means no requirement`() {
        assertTrue(validate().isEmpty())
        assertTrue(validate("countOverrideReason" to JsonPrimitive("only a note")).isEmpty())
    }

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
            enforceRequired = false,
        )

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
