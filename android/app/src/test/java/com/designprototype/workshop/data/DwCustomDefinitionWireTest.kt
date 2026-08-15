package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's reading of `GET /design-workshops/{id}/custom-sections`, asserted against bytes the
 * SERVER produced.
 *
 * ── WHY THIS TEST EXISTS AT ALL ───────────────────────────────────────────────────────────────────
 *
 * Encoding a [DwCustomDefinitionDto] and decoding it again would pass on the day the DTO named every
 * key wrong, because a self-consistent DTO is self-consistent. The only assertion with any power is
 * one that starts from the server's own output. This client has shipped that exact defect TWICE:
 * `DwReferenceStore` listened on a key the endpoint does not send and decoded a perfectly good
 * 50-record payload into an empty list on every one of the registry's 22 REF fields, and the
 * identity-OCR DTO declared `number`, `documentType` and `name`, none of which that endpoint has ever
 * sent, so a perfect read of an identity card was reported to the designer as unreadable. Both times
 * `ignoreUnknownKeys = true` plus all-defaulted properties turned the mistake into silence: nothing
 * threw, nothing logged, and the feature simply did nothing.
 *
 * **HERE THE SAME MISTAKE WOULD READ AS "THIS WORKSHOP HAS NO CUSTOM QUESTIONS"** — which is
 * indistinguishable from the truth for most workshops, on a screen, in a score, and in a .docx handed
 * to an officer. So the names are pinned.
 *
 * ── WHAT [LIVE_PAYLOAD] IS, EXACTLY, AND WHAT IT IS NOT ───────────────────────────────────────────
 *
 * It is the verbatim stdout of `definition_payload` (`backend/app/services/custom_sections.py:1553`)
 * EXECUTED on 2026-08-12 against a hand-built [CustomDefinition], through the backend's own venv:
 *
 *     backend/.venv/Scripts/python.exe -c "… print(json.dumps(definition_payload(d), indent=2))"
 *
 * So every key name, every nesting and every default below came out of the server's serialiser rather
 * than out of anybody's memory of it. **It is NOT a capture of a live HTTP 200 over the wire**, and
 * that difference is stated rather than glossed: the route wraps this same function and adds nothing
 * to it (`routes/design_workshops.py:1096-1113` returns `definition_payload(await
 * load_definition(workshop_id))` and nothing else), so the only thing an HTTP capture would prove in
 * addition is that FastAPI serialises a `dict[str, Any]` unchanged. Building one would have required
 * writing a definition into the shared development database, which this lane does not own.
 *
 * The `Json` is configured exactly as `ApiClient` configures the one Retrofit uses — `DwReferenceWireTest`
 * says why that matters more than it looks: `ignoreUnknownKeys = true` is what turns a wrong key name
 * into silence instead of an exception, so a test that decoded strictly would not reproduce the defect
 * it exists to catch.
 */
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

        // The whole class of defect in one assertion: a wrong key name anywhere above `sections`
        // reads as a workshop with no custom questions.
        assertEquals("9f2c1ab4d7e60351", payload.customSchemaVersion)
        assertEquals(1, payload.sections.size)
        assertTrue("fetchedAt must decode", payload.fetchedAt.isNotBlank())

        val section = payload.sections.single()
        assertEquals("cmsec0001", section.id)
        assertEquals("loomAudit", section.key)
        // `stageKey`, NOT `stage`. Every one of this client's readers filters sections by this
        // string, so the wrong spelling means every stage draws nothing and scores nothing while the
        // payload itself is perfect.
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
        // `field_payload` emits `CustomOption.display`, which falls back to the TOKEN when nobody
        // wrote a label. So a client must never invent "Tussar" from "TUSSAR": a word this app made
        // up would go into a ministry document under the designer's name. Pinned because a client
        // that expected "" here would be tempted to do exactly that.
        assertEquals("TUSSAR" to "TUSSAR", field.options[1].value to field.options[1].label)
    }

    @Test
    fun `a retired field arrives with its supersedence, and is not dropped`() {
        val fields = decode(LIVE_PAYLOAD).sections.single().fields
        val retired = fields.first { it.key == "looms" }

        // `definition_payload` includes retired fields ALWAYS and on purpose. A copy missing every
        // answer given under a superseded wording makes the two copies of one report disagree about
        // the fieldwork with nothing in either saying so.
        assertTrue(retired.retired)
        assertEquals("cmfld0001", retired.supersededById)
        assertEquals("How many looms?", retired.label)
    }

    @Test
    fun `the definition of a workshop that has none decodes, and reads as NONE_DEFINED`() {
        // `custom_schema_version` returns "" rather than a hash of nothing, deliberately, so that
        // "I hold nothing" and "there is nothing to hold" stay distinguishable. Executed against the
        // same serialiser with an empty `CustomDefinition`.
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
        // The insurance the all-defaulted rule buys. This is the same shape with one field carrying a
        // type this build has never heard of and two keys it does not declare. Nothing may throw, the
        // rest of the definition must survive, and the unknown token must reach the renderer INTACT —
        // that raw string is what the designer is shown in the read-only note, and a note that will
        // not name the type is a note nobody can report.
        val payload = decode(FUTURE_PAYLOAD)
        val fields = payload.sections.single().fields
        assertEquals(2, fields.size)
        assertEquals("INT", fields[0].type)
        assertEquals("SIGNATURE", fields[1].type)
        assertFalse("a v1.1 type must not be drawable", dwCustomFieldDrawable(fields[1].type))
        assertTrue(dwCustomUnsupportedNote(fields[1].type).contains("SIGNATURE"))
    }

    companion object {
        /**
         * Verbatim stdout of `definition_payload` on 2026-08-12. Nothing has been altered, added or
         * re-indented; the only editing was to build the [CustomDefinition] it was called with.
         */
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

        /** `definition_payload(EMPTY_DEFINITION)` — the answer for a workshop that has never had one. */
        private const val EMPTY_PAYLOAD = """
{
  "customSchemaVersion": "",
  "sections": [],
  "fetchedAt": "2026-08-12T12:43:26.620083+00:00"
}
"""

        /** [LIVE_PAYLOAD]'s shape with a v1.1 type and two keys this build does not declare. */
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
