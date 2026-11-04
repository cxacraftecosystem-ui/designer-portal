package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.entity
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.ui.joinNumbered
import com.designprototype.workshop.ui.numberedRowsAfterEdit
import com.designprototype.workshop.ui.splitNumbered
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwBulletListFieldTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private fun entityOf(stageKey: String, entityKey: String): EntityDto =
        schema.stages.firstOrNull { it.key == stageKey }?.entity(entityKey)
            ?: throw AssertionError("the registry declares no `$stageKey`.`$entityKey`")

    private val allLiveFields: List<Pair<String, FieldDto>>
        get() = schema.stages.flatMap { stage ->
            stage.entities.flatMap { ent -> ent.liveFields.map { "${ent.key}.${it.key}" to it } }
        }

    private val bulletedLongText: List<Pair<String, FieldDto>>
        get() = allLiveFields
            .filter { (_, field) -> DwFieldType.of(field.type) == DwFieldType.LONG_TEXT }
            .filter { (_, field) -> field.reportRole == "BULLETS" }

    @Test
    fun `the registry's bulleted long-text fields include the four this surface was written for`() {
        val bulleted = bulletedLongText.map { it.first }
        listOf(
            "participant.dos",
            "participant.donts",
            "traditionalProcess.documentedSteps",
            "tool.usedByArtisans",
        ).forEach { key ->
            assertTrue("$key must still be a bulleted LONG_TEXT field — it is $bulleted", key in bulleted)
        }
    }

    @Test
    fun `a bulleted long-text field says in its help that one line is one point`() {
        bulletedLongText.forEach { (name, field) ->
            assertTrue(
                "$name is printed as bullets but its help never says a line is a point: “${field.help}”",
                field.help.lowercase().contains("per line")
            )
        }
    }

    @Test
    fun `a long-text field with any other report role keeps the plain box`() {
        val description = entityOf("TRADITIONAL_PROCESS_BASELINE", "processStep").liveFields
            .firstOrNull { it.key == "description" }
            ?: throw AssertionError("stage 5's processStep no longer declares `description`")
        assertEquals("LONG_TEXT", description.type)
        assertFalse(
            "processStep.description is a TABLE_COLUMN — numbered rows would print a paragraph as a list",
            description.reportRole == "BULLETS"
        )
    }

    @Test
    fun `the numbered-points codec round-trips and drops blank rows`() {
        assertEquals(listOf("do not wash hot", "dry in shade"), splitNumbered("do not wash hot\ndry in shade"))
        assertEquals("do not wash hot\ndry in shade", joinNumbered(listOf("do not wash hot", "dry in shade")))
        assertEquals("a\nb", joinNumbered(listOf("a", "b", "")))
        assertEquals("a\nb", joinNumbered(listOf(" a ", "b", "   ")))
        assertEquals("", joinNumbered(listOf("")))
        assertEquals("", joinNumbered(emptyList()))
        assertEquals(listOf(""), splitNumbered(null))
        assertEquals(listOf(""), splitNumbered(""))
        val stored = "one\ntwo\nthree"
        assertEquals(stored, joinNumbered(splitNumbered(stored)))
    }

    @Test
    fun `enter inside a row pushes the remainder into new bullets`() {
        val rows = listOf("do not wash hot", "dry in shade")
        assertEquals(listOf("do not wash hot ", "dry in shade"), numberedRowsAfterEdit(rows, 0, "do not wash hot "))
        assertEquals(
            listOf("do not wash hot", "", "dry in shade"),
            numberedRowsAfterEdit(rows, 0, "do not wash hot\n")
        )
        assertEquals(
            listOf("one", "two", "three", "dry in shade"),
            numberedRowsAfterEdit(rows, 0, "one\n  two  \nthree")
        )
        assertEquals(rows, numberedRowsAfterEdit(rows, 7, "anything"))
    }

    @Test
    fun `a locked row set never grows or shifts under an in-flight dictation`() {
        val rows = listOf("soaking", "printing")
        val locked = numberedRowsAfterEdit(rows, 0, "soaking\nrinsing", rowsLocked = true)
        assertEquals(rows.size, locked.size)
        assertEquals(listOf("soaking rinsing", "printing"), locked)
        assertEquals("printing", locked[1])
        assertEquals(
            listOf("soaking", "rinsing", "printing"),
            numberedRowsAfterEdit(rows, 0, "soaking\nrinsing", rowsLocked = false)
        )
        assertEquals(listOf("soaking", "printing now"), numberedRowsAfterEdit(rows, 1, "printing now", rowsLocked = true))
    }

    @Test
    fun `every box the registry declares a pincode on gets the number pad`() {
        val numeric = allLiveFields.filter { (_, field) -> dwNumericTextField(field) }.map { it.first }
        val declared = allLiveFields.filter { (_, field) -> field.format == "PINCODE" }.map { it.first }
        assertTrue(
            "the bundled registry declares no PINCODE field at all — either the asset or " +
                "`FieldDto.format` changed shape, and everything below would pass vacuously",
            declared.isNotEmpty()
        )
        declared.forEach { key ->
            assertTrue("$key declares format PINCODE and must open the number pad — numeric is $numeric", key in numeric)
        }
        numeric.forEach { name ->
            assertTrue(
                "$name is not a pincode-shaped key and must not be forced onto the number pad",
                name.lowercase().endsWith("pincode")
            )
        }
    }

    @Test
    fun `the address boxes beside a pincode are not numeric`() {
        val tool = entityOf("TRADITIONAL_PROCESS_BASELINE", "tool").liveFields.associateBy { it.key }
        listOf("recordState", "recordDistrict", "recordVillage", "name", "place").forEach { key ->
            val field = tool[key] ?: throw AssertionError("stage 5's tool no longer declares `$key`")
            assertFalse("tool.$key must keep the ordinary keyboard", dwNumericTextField(field))
        }
    }

    @Test
    fun `the type test comes before the format test and the key is never consulted`() {
        assertFalse(dwNumericTextField(FieldDto(key = "pincode", type = "DECIMAL", format = "PINCODE")))
        assertFalse(dwNumericTextField(FieldDto(key = "pincode", type = "LONG_TEXT", format = "PINCODE")))
        assertTrue(dwNumericTextField(FieldDto(key = "pincode", type = "TEXT", format = "PINCODE")))
        assertTrue(dwNumericTextField(FieldDto(key = "f12", type = "TEXT", format = "PINCODE")))
        assertFalse(dwNumericTextField(FieldDto(key = "pincode", type = "TEXT")))
        assertFalse(dwNumericTextField(FieldDto(key = "recordPincode", type = "TEXT", format = "")))
        assertFalse(dwNumericTextField(FieldDto(key = "email", type = "TEXT", format = "EMAIL")))
    }
}
