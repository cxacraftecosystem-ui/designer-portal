package com.designprototype.workshop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class FormPrimitivesRuleTest {

    private val root = File("src/main/java/com/designprototype/workshop")

    private fun offenders(pattern: Regex, allowed: Set<String>): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
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
