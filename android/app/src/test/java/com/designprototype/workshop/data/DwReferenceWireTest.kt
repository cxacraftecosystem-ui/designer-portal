package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.buildReferenceOptions
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwReferenceWireTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun decode(raw: String) = json.decodeFromString(DwReferenceResponseDto.serializer(), raw)

    @Test
    fun `the live payload decodes to a populated option list`() {
        val payload = decode(LIVE_PAYLOAD)

        assertEquals(2, payload.options.size)
        assertEquals("cmsiusb3a002yrmg1gnl4mfc1", payload.options.first().id)
        assertEquals("Inline Edit Artisan 1786101615619", payload.options.first().label)
    }

    @Test
    fun `an option carries the second line the server calls sublabel`() {
        val option = decode(LIVE_PAYLOAD).options.first()
        assertTrue("the sublabel is missing: '${option.hint}'", option.hint.contains("Barpali"))
    }

    @Test
    fun `an option carries the data that hydrates the row`() {
        val data = decode(LIVE_PAYLOAD).options.first().data
        assertEquals("Barpali", data["village"]?.toString()?.trim('"'))
    }

    @Test
    fun `the flags the picker has to speak about are read`() {
        val payload = decode(LIVE_PAYLOAD)
        assertEquals("Artisan", payload.model)
        assertEquals("ALL", payload.scope)
        assertTrue(payload.truncated)
        assertFalse(payload.scopedToWorkshop)
        assertFalse(payload.filtered)
    }

    @Test
    fun `a cache written under the previous key name keeps its second line`() {
        val legacy = """{"id":"a1","label":"Ram Meher","hint":"Ikat weaving · Barpali"}"""
        val option = json.decodeFromString(DwReferenceOption.serializer(), legacy)
        assertEquals("Ikat weaving · Barpali", option.hint)
    }

    @Test
    fun `the option list survives the round trip through the on-disk cache shape`() {
        val fetched = decode(LIVE_PAYLOAD)
        val cached = DwReferenceList(model = fetched.model, filteredBy = "", items = fetched.options)
        assertEquals(2, cached.narrowedTo("").size)
        assertEquals(2, cached.narrowedTo("some-artisan-id").size)
    }


    @Test
    fun `a sketch payload carries the flag on the row and the word on the answer`() {
        val payload = decode(SKETCH_PAYLOAD)
        assertTrue(payload.tentativeFirst)
        assertEquals("Tentative", payload.tentativeLabel)
        assertEquals(listOf(true, false), payload.options.map { it.tentative })
        assertEquals("ent_2", payload.options.first().id)
    }

    @Test
    fun `the flag is not inside data, so choosing a sketch cannot tick the new row's box`() {
        val option = decode(SKETCH_PAYLOAD).options.first()
        assertTrue("data must stay empty on an in-record option: ${option.data}", option.data.isEmpty())
    }

    @Test
    fun `a server without the feature makes no claim, and neither may the picker`() {
        val payload = decode(LIVE_PAYLOAD)
        assertFalse(payload.tentativeFirst)
        assertEquals("", payload.tentativeLabel)
        assertFalse(payload.options.first().tentative)
    }

    @Test
    fun `the word is cached with the options it explains`() {
        val fetched = decode(SKETCH_PAYLOAD)
        val cached = DwReferenceList(
            model = fetched.model,
            filteredBy = "",
            items = fetched.options,
            tentativeLabel = if (fetched.tentativeFirst) fetched.tentativeLabel else "",
        )
        assertEquals("Tentative", cached.tentativeLabel)
        assertTrue(cached.narrowedTo("").first().tentative)
    }

    @Test
    fun `a list cached by an older build decodes without losing its options`() {
        val legacy = """{"model":"DwSketch","filteredBy":"","items":[{"id":"a1","label":"Tote"}]}"""
        val list = json.decodeFromString(DwReferenceList.serializer(), legacy)
        assertEquals(1, list.items.size)
        assertEquals("", list.tentativeLabel)
        assertFalse(list.items.first().tentative)
    }

    @Test
    fun `the picker puts the registry's word on a ticked row and on no other`() {
        val options = buildReferenceOptions(decode(SKETCH_PAYLOAD).options, selectedId = "", tentativeWord = "Tentative")
        assertEquals("Tentative", options.first().hint)
        assertEquals("Indigo, first pass", options[1].hint)
    }

    @Test
    fun `the word comes first where a row has both, and the two are joined not replaced`() {
        val row = DwReferenceOption(id = "x", label = "Tote", hint = "Indigo", tentative = true)
        val options = buildReferenceOptions(listOf(row), selectedId = "", tentativeWord = "Tentative")
        assertEquals("Tentative · Indigo", options.first().hint)
    }

    @Test
    fun `a blank word draws nothing at all`() {
        val row = DwReferenceOption(id = "x", label = "Tote", tentative = true)
        val options = buildReferenceOptions(listOf(row), selectedId = "", tentativeWord = "")
        assertNull(options.first().hint)
    }

    private companion object {
        const val LIVE_PAYLOAD = """
{
  "model": "Artisan",
  "scope": "ALL",
  "scopedToWorkshop": false,
  "filtered": false,
  "truncated": true,
  "options": [
    {
      "id": "cmsiusb3a002yrmg1gnl4mfc1",
      "label": "Inline Edit Artisan 1786101615619",
      "sublabel": "Ikat weaving 05584d59 · Barpali",
      "data": {
        "name": "Inline Edit Artisan 1786101615619",
        "specialisation": "Ikat weaving 05584d59",
        "village": "Barpali"
      }
    },
    {
      "id": "cmsiuwde0003vrmg1vzdriyqu",
      "label": "Inline Edit Artisan 1786101805173",
      "sublabel": "Ikat weaving 05584d59 · Barpali",
      "data": {
        "name": "Inline Edit Artisan 1786101805173",
        "specialisation": "Ikat weaving 05584d59",
        "village": "Barpali"
      }
    }
  ]
}
"""

        const val SKETCH_PAYLOAD = """
{
  "model": "DwSketch",
  "scope": "WORKSHOP",
  "scopedToWorkshop": true,
  "filtered": false,
  "truncated": false,
  "outOfScope": false,
  "outOfScopeOption": null,
  "tentativeFirst": true,
  "tentativeLabel": "Tentative",
  "options": [
    { "id": "ent_2", "label": "Shoulder bag", "sublabel": "", "data": {}, "tentative": true },
    { "id": "ent_1", "label": "Market tote", "sublabel": "Indigo, first pass", "data": {}, "tentative": false }
  ]
}
"""
    }
}
