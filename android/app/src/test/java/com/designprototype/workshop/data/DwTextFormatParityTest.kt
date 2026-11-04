package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwTextFormatParityTest {

    private data class Case(val format: String, val value: String, val expected: String?, val note: String?)

    private fun tableFile(): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "shared/text-format-vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("shared/text-format-vectors.json was not found above ${java.io.File("").absolutePath}")
    }

    private fun cases(): List<Case> {
        val root = Json.parseToJsonElement(tableFile().readText()).jsonObject
        return root["vectors"]!!.jsonArray.map { element ->
            val row: JsonObject = element.jsonObject
            Case(
                format = row["format"]!!.jsonPrimitive.content,
                value = row["value"]!!.jsonPrimitive.content,
                expected = row["error"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.contentOrNull },
                note = row["note"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    @Test
    fun `the shared table is not empty and covers every format this build knows`() {
        val rows = cases()
        assertTrue("the shared case table is suspiciously small: ${rows.size} rows", rows.size > 30)
        val covered = rows.map { it.format }.toSet()
        for (format in listOf("EMAIL", "PHONE_IN", "AADHAAR", "PEHCHAN", "PINCODE")) {
            assertTrue("no case for $format", format in covered)
        }
    }

    @Test
    fun `the handset and the browser agree on the shared vector table`() {
        val disagreements = cases().mapNotNull { row ->
            val got = DwTextFormats.error(row.format, row.value)
            if (got == row.expected) null else buildString {
                append("format=${row.format.ifEmpty { "(none)" }} value=${'"'}${row.value}${'"'}\n")
                append("  expected: ${row.expected}\n")
                append("  got:      $got")
                row.note?.let { append("\n  the case is here because: $it") }
            }
        }
        assertEquals("the handset does not match the shared table", "", disagreements.joinToString("\n\n"))
    }

    @Test
    fun `a typed string that is not an aadhaar is refused rather than masked`() {
        val field = FieldDto(
            key = "aadhaarNumber",
            label = "Aadhaar number",
            type = "TEXT",
            maxLength = 20,
            storeMasked = true,
            format = "AADHAAR",
        )
        val refused = DwValues.coerce(field, "hello world 1234")
        assertEquals(null, refused.value)
        assertEquals("Aadhaar number must be 12 digits — remove any letters or symbols.", refused.error)
    }

    @Test
    fun `a hydrated mask survives re-coercion without a refusal`() {
        val field = FieldDto(
            key = "aadhaarNumber",
            label = "Aadhaar number",
            type = "TEXT",
            maxLength = 20,
            storeMasked = true,
            format = "AADHAAR",
        )
        val accepted = DwValues.coerce(field, "XXXX XXXX 0124")
        assertEquals(null, accepted.error)
        assertEquals("XXXX XXXX 0124", DwValues.text(accepted.value))
    }

    @Test
    fun `the mask predicate refuses what an X anywhere would accept`() {
        assertEquals(
            "Aadhaar number must be 12 digits — remove any letters or symbols.",
            DwTextFormats.aadhaarOrMaskError("XxamplE 1234"),
        )
        assertEquals(
            "Aadhaar number must be 12 digits — remove any letters or symbols.",
            DwTextFormats.aadhaarOrMaskError("X"),
        )
    }

    @Test
    fun `an over-length answer keeps its own refusal rather than a format complaint`() {
        val field = FieldDto(key = "email", label = "Email", type = "EMAIL", maxLength = 10, format = "EMAIL")
        val refused = DwValues.coerce(field, "designer@dch.gov.in")
        assertEquals("Email is longer than 10 characters", refused.error)
    }

    @Test
    fun `a format nothing in this build knows enforces nothing`() {
        val field = FieldDto(key = "gstin", label = "GSTIN", type = "TEXT", format = "GSTIN")
        val coerced = DwValues.coerce(field, "not a gstin")
        assertEquals(null, coerced.error)
        assertEquals("not a gstin", DwValues.text(coerced.value))
    }
}
