package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwCustomDefinitionWireTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun decode(raw: String) = json.decodeFromString(DwCustomDefinitionDto.serializer(), raw)

    @Test
    fun `the server's own payload decodes to a populated definition`() {
        val payload = decode(LIVE_PAYLOAD)

        assertEquals("9f2c1ab4d7e60351", payload.customSchemaVersion)
        assertEquals(1, payload.sections.size)
        assertTrue("fetchedAt must decode", payload.fetchedAt.isNotBlank())

        val section = payload.sections.single()
        assertEquals("cmsec0001", section.id)
        assertEquals("loomAudit", section.key)
        assertEquals("TRADITIONAL_PROCESS_BASELINE", section.stageKey)
        assertEquals("Loom audit", section.title)
        assertEquals(2, section.revision)
        assertFalse(section.retired)
        assertEquals(3, section.fields.size)
    }

    @Test
    fun `every field key of field_payload is read, including the ones at their defaults`() {
        val field = decode(LIVE_PAYLOAD).sections.single().fields.first { it.key == "loomsWorking" }

        assertEquals("cmfld0001", field.id)
        assertEquals("Looms in working order", field.label)
        assertEquals("INT", field.type)
        assertEquals("BASIC", field.tier)
        assertTrue(field.required)
        assertEquals("Count only the looms a weaver could sit at today.", field.help)
        assertEquals("looms", field.unit)
        assertEquals(0, field.maxLength)
        assertEquals(0.0, field.minValue!!, 0.0)
        assertEquals(500.0, field.maxValue!!, 0.0)
        assertEquals(0, field.sortOrder)
        assertFalse(field.retired)
        assertNull(field.supersededById)
    }

    @Test
    fun `an option's label arrives already resolved, and a token with no label is not blank`() {
        val field = decode(LIVE_PAYLOAD).sections.single().fields.first { it.key == "warpMaterial" }

        assertEquals(2, field.options.size)
        assertEquals("COTTON" to "Cotton", field.options[0].value to field.options[0].label)
        assertEquals("TUSSAR" to "TUSSAR", field.options[1].value to field.options[1].label)
    }

    @Test
    fun `a retired field arrives with its supersedence, and is not dropped`() {
        val fields = decode(LIVE_PAYLOAD).sections.single().fields
        val retired = fields.first { it.key == "looms" }

        assertTrue(retired.retired)
        assertEquals("cmfld0001", retired.supersededById)
        assertEquals("How many looms?", retired.label)
    }

    @Test
    fun `the definition of a workshop that has none decodes, and reads as NONE_DEFINED`() {
        val payload = decode(EMPTY_PAYLOAD)
        assertEquals("", payload.customSchemaVersion)
        assertTrue(payload.sections.isEmpty())

        val cache = DwCustomCache(
            workshopId = "w1",
            customSchemaVersion = payload.customSchemaVersion,
            sections = payload.sections,
            complete = true,
        )
        assertEquals(DwCustomCopy.NONE_DEFINED, dwCustomCopy(cache))
    }

    @Test
    fun `a payload from a server that has moved past v1 still decodes, key by key`() {
        val payload = decode(FUTURE_PAYLOAD)
        val fields = payload.sections.single().fields
        assertEquals(2, fields.size)
        assertEquals("INT", fields[0].type)
        assertEquals("SIGNATURE", fields[1].type)
        assertFalse("a v1.1 type must not be drawable", dwCustomFieldDrawable(fields[1].type))
        assertTrue(dwCustomUnsupportedNote(fields[1].type).contains("Update the app"))
    }

    companion object {
        private const val LIVE_PAYLOAD = """
{
  "customSchemaVersion": "9f2c1ab4d7e60351",
  "sections": [
    {
      "id": "cmsec0001",
      "key": "loomAudit",
      "stageKey": "TRADITIONAL_PROCESS_BASELINE",
      "title": "Loom audit",
      "description": "Questions this cluster asked that the standard form does not.",
      "sortOrder": 0,
      "revision": 2,
      "retired": false,
      "fields": [
        {
          "id": "cmfld0001",
          "key": "loomsWorking",
          "label": "Looms in working order",
          "type": "INT",
          "tier": "BASIC",
          "required": true,
          "help": "Count only the looms a weaver could sit at today.",
          "unit": "looms",
          "options": [],
          "maxLength": 0,
          "minValue": 0.0,
          "maxValue": 500.0,
          "sortOrder": 0,
          "retired": false,
          "supersededById": null
        },
        {
          "id": "cmfld0002",
          "key": "warpMaterial",
          "label": "Warp material",
          "type": "ENUM",
          "tier": "STANDARD",
          "required": false,
          "help": "",
          "unit": "",
          "options": [
            {
              "value": "COTTON",
              "label": "Cotton"
            },
            {
              "value": "TUSSAR",
              "label": "TUSSAR"
            }
          ],
          "maxLength": 0,
          "minValue": null,
          "maxValue": null,
          "sortOrder": 1,
          "retired": false,
          "supersededById": null
        },
        {
          "id": "cmfld0003",
          "key": "looms",
          "label": "How many looms?",
          "type": "INT",
          "tier": "STANDARD",
          "required": false,
          "help": "",
          "unit": "",
          "options": [],
          "maxLength": 0,
          "minValue": null,
          "maxValue": null,
          "sortOrder": 2,
          "retired": true,
          "supersededById": "cmfld0001"
        }
      ]
    }
  ],
  "fetchedAt": "2026-08-12T12:43:26.620083+00:00"
}
"""

        private const val EMPTY_PAYLOAD = """
{
  "customSchemaVersion": "",
  "sections": [],
  "fetchedAt": "2026-08-12T12:43:26.620083+00:00"
}
"""

        private const val FUTURE_PAYLOAD = """
{
  "customSchemaVersion": "aa11bb22",
  "sections": [
    {
      "id": "s1", "key": "future", "stageKey": "SKETCH_DEVELOPMENT", "title": "Future",
      "description": "", "sortOrder": 0, "revision": 1, "retired": false,
      "collapsedByDefault": true,
      "fields": [
        {
          "id": "f1", "key": "count", "label": "Count", "type": "INT", "tier": "STANDARD",
          "required": false, "help": "", "unit": "", "options": [], "maxLength": 0,
          "minValue": null, "maxValue": null, "sortOrder": 0, "retired": false,
          "supersededById": null
        },
        {
          "id": "f2", "key": "signedBy", "label": "Signed by", "type": "SIGNATURE",
          "tier": "STANDARD", "required": false, "help": "", "unit": "", "options": [],
          "maxLength": 0, "minValue": null, "maxValue": null, "sortOrder": 1, "retired": false,
          "supersededById": null, "penColour": "BLACK"
        }
      ]
    }
  ],
  "fetchedAt": "2026-08-12T12:43:26.620083+00:00"
}
"""
    }
}
