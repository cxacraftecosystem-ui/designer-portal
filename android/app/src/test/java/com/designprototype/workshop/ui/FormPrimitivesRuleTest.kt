package com.designprototype.workshop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE OWNER'S RULE (2026-10-17), held mechanically on the handset as `e2e/form-primitives-rule-unit.spec.ts`
 * holds it on the web: every on/off setting is an [AppToggle], every date a [CalendarField], every six-digit
 * code an [OtpCodeField] — never a bare Material `Switch`, never the word "ON"/"OFF" as a setting's state,
 * never a raw [FieldDateField] outside the primitive that wraps it.
 *
 * A checkbox choosing ROWS (select these three artisans) is legitimate and cannot be told from a setting by
 * a scan, so that half is held by review and the field-repo-frontend skill (§11.11), not here.
 */
class FormPrimitivesRuleTest {

    private val root = File("src/main/java/com/designprototype/workshop")

    private fun offenders(pattern: Regex, allowed: Set<String>): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    // Prose is not code: KDoc and block-comment lines quote the old controls by name.
                    val trimmed = line.trimStart()
                    val prose = trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")
                    val code = line.substringBefore("//")
                    if (!prose && pattern.containsMatchIn(code)) "${file.name}:${index + 1}" else null
                }
            }
            .toList()

    @Test
    fun `a setting is never a bare Material Switch`() {
        assertEquals(emptyList<String>(), offenders(Regex("""(^|[^A-Za-z.])Switch\("""), setOf("AppToggle.kt")))
    }

    @Test
    fun `a setting never reads as an ON or OFF word`() {
        assertEquals(
            emptyList<String>(),
            offenders(Regex(""""On" else "Off"|: ON"|: OFF""""), emptySet()),
        )
    }

    @Test
    fun `every date field is the CalendarField`() {
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""(^|[^A-Za-z])FieldDateField\("""), setOf("DateTimeField.kt", "DateTimeFields.kt", "CalendarField.kt")),
        )
    }
}
