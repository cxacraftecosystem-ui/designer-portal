package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's reading of `GET /design-workshops/{id}/references`, asserted against the payload the
 * running server actually sends.
 *
 * WHY THIS IS A PARITY TEST AND NOT A ROUND TRIP. Encoding a [DwReferenceResponseDto] and decoding it
 * again would have passed every day this app has existed, because the DTO was self-consistent — it
 * simply named a key the server does not send. The only assertion with any power is one that starts
 * from the SERVER's bytes, so [LIVE_PAYLOAD] below is a verbatim capture of a 200 response (workshop
 * cmsik2jg8000eh8xc1lcy661a, model=Artisan) with the option list cut to two entries and nothing else
 * altered. `tests/test_reference_resolver.py` guards the same shape from the other side.
 *
 * The converter is configured exactly as `ApiClient` configures the one Retrofit uses. That matters
 * more than it looks: `ignoreUnknownKeys = true` is what turned a wrong key name into silence instead
 * of an exception, and a test that decoded strictly would not reproduce the defect at all.
 */
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

        // The whole defect in one assertion. This read 0 for every one of the registry's 22 REF
        // fields, on every workshop, online and offline, while the request itself returned 200 with
        // fifty artisans in it — and the picker rendered "No records on this device yet".
        assertEquals(2, payload.options.size)
        assertEquals("cmsiusb3a002yrmg1gnl4mfc1", payload.options.first().id)
        assertEquals("Inline Edit Artisan 1786101615619", payload.options.first().label)
    }

    @Test
    fun `an option carries the second line the server calls sublabel`() {
        // Two artisans in a cluster are called Ram. The sublabel is the whole of how a designer tells
        // them apart, so a picker that shows the label alone invites the wrong one to be linked —
        // silently, and permanently, because a REF is a join key.
        val option = decode(LIVE_PAYLOAD).options.first()
        assertTrue("the sublabel is missing: '${option.hint}'", option.hint.contains("Barpali"))
    }

    @Test
    fun `an option carries the data that hydrates the row`() {
        // `fromref(...)` fields are filled in from here. An empty `data` is a form that looks like it
        // accepted the choice and wrote none of the artisan's details into the record.
        val data = decode(LIVE_PAYLOAD).options.first().data
        assertEquals("Barpali", data["village"]?.toString()?.trim('"'))
    }

    @Test
    fun `the flags the picker has to speak about are read`() {
        val payload = decode(LIVE_PAYLOAD)
        assertEquals("Artisan", payload.model)
        assertEquals("ALL", payload.scope)
        // A truncated list looks exactly like a complete one to the person scrolling it.
        assertTrue(payload.truncated)
        assertFalse(payload.scopedToWorkshop)
        assertFalse(payload.filtered)
    }

    @Test
    fun `a cache written under the previous key name keeps its second line`() {
        // Devices in the field already hold reference lists on disk written with `hint`. Reading them
        // is why `@JsonNames` is there: an update that silently blanked every sublabel on a phone
        // about to lose signal for three days would be this fix causing a smaller version of the
        // problem it fixes.
        val legacy = """{"id":"a1","label":"Ram Meher","hint":"Ikat weaving · Barpali"}"""
        val option = json.decodeFromString(DwReferenceOption.serializer(), legacy)
        assertEquals("Ikat weaving · Barpali", option.hint)
    }

    @Test
    fun `the option list survives the round trip through the on-disk cache shape`() {
        // The picker reads `DwReferenceList`, not the response, so the wire fix is only worth
        // anything if the hand-off preserves the options. This is the shape
        // `WorkshopRepository.designWorkshopReferences` builds.
        val fetched = decode(LIVE_PAYLOAD)
        val cached = DwReferenceList(model = fetched.model, filteredBy = "", items = fetched.options)
        assertEquals(2, cached.narrowedTo("").size)
        // An option carrying no filterValue is KEPT under a cascade rather than dropped — the server
        // does the narrowing and does not populate the field, so dropping them would empty every
        // cascading dropdown in the app.
        assertEquals(2, cached.narrowedTo("some-artisan-id").size)
    }

    private companion object {
        /**
         * HTTP 200 from the running API, verbatim but for the option list being cut to two. The six
         * top-level keys are exactly `filtered, model, options, scope, scopedToWorkshop, truncated`
         * — `items` is absent, and always was.
         */
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
    }
}
