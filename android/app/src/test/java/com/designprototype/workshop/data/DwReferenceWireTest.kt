package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.buildReferenceOptions
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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


    // ── TENTATIVE-FIRST WHERE A SKETCH IS *CHOSEN* ────────────────────────────────────────────
    //
    // Stage 11's sketches sort tentative-first wherever they are LISTED (`dwTentativeFirst`, the
    // upload chooser). They did not where one is CHOSEN: the three `DwSketch` REF pickers are
    // answered by this endpoint and the flag was not on the wire at all.
    //
    // THE ORDERING IS THE SERVER'S AND THESE TESTS ARE PARTLY ABOUT WHY. `options` is one capped
    // page, so partitioning it on the handset would partition the page and leave a tentative sketch
    // stranded behind the cap — which nothing on this device can recover. The server orders above
    // its own truncation; this client's whole remaining duty is to draw the order it was given and
    // say, in the registry's own word, which rows are marked.

    @Test
    fun `a sketch payload carries the flag on the row and the word on the answer`() {
        val payload = decode(SKETCH_PAYLOAD)
        assertTrue(payload.tentativeFirst)
        assertEquals("Tentative", payload.tentativeLabel)
        assertEquals(listOf(true, false), payload.options.map { it.tentative })
        // AND THE ORDER IS TAKEN AS SENT. The tentative row is first because the SERVER put it
        // first; nothing here re-sorts, and `dwTentativeFirst` must never be pointed at this list.
        assertEquals("ent_2", payload.options.first().id)
    }

    @Test
    fun `the flag is not inside data, so choosing a sketch cannot tick the new row's box`() {
        // `data` is the hydration dictionary and `sketch.supersedesSketch` is a DwSketch picker
        // mounted on a `sketch` row, which declares `isTentative` itself. An `isTentative` in `data`
        // would be a standing offer to copy one sketch's working state onto another.
        val option = decode(SKETCH_PAYLOAD).options.first()
        assertTrue("data must stay empty on an in-record option: ${option.data}", option.data.isEmpty())
    }

    @Test
    fun `a server without the feature makes no claim, and neither may the picker`() {
        // The live Artisan capture predates the pair entirely. Both must default to the inert
        // answer, because a chip drawn over an ordering nobody applied is worse than no chip.
        val payload = decode(LIVE_PAYLOAD)
        assertFalse(payload.tentativeFirst)
        assertEquals("", payload.tentativeLabel)
        assertFalse(payload.options.first().tentative)
    }

    @Test
    fun `the word is cached with the options it explains`() {
        // The order is baked into the stored list; the word that accounts for it arrives on the same
        // response. Losing it on the first offline open would leave a reordered picker with nothing
        // on screen saying why — on the handset that goes a fortnight without signal.
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
        // The same guarantee `@JsonNames` buys for the sublabel. A phone about to lose signal must
        // not have its artisan list blanked by an update that added a field.
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
        // The settled sketch keeps its own second line and gains nothing.
        assertEquals("Indigo, first pass", options[1].hint)
    }

    @Test
    fun `the word comes first where a row has both, and the two are joined not replaced`() {
        // The flag explains the row's POSITION. A reader scanning for why the third sketch is at the
        // top should not have to read past a caption to find it — and the caption must survive.
        val row = DwReferenceOption(id = "x", label = "Tote", hint = "Indigo", tentative = true)
        val options = buildReferenceOptions(listOf(row), selectedId = "", tentativeWord = "Tentative")
        assertEquals("Tentative · Indigo", options.first().hint)
    }

    @Test
    fun `a blank word draws nothing at all`() {
        // The answer for every model with no such flag, for a list cached by an older build, and for
        // the merged cache that cannot claim an ordering. A row with no second line must come back
        // with a null hint rather than an empty string, or the picker draws a blank line under it.
        val row = DwReferenceOption(id = "x", label = "Tote", tentative = true)
        val options = buildReferenceOptions(listOf(row), selectedId = "", tentativeWord = "")
        assertNull(options.first().hint)
    }

    private companion object {
        /**
         * HTTP 200 from the running API, verbatim but for the option list being cut to two. Its
         * top-level keys are `filtered, model, options, scope, scopedToWorkshop, truncated` —
         * `items` is absent, and always was.
         *
         * A CAPTURE IS DATED AND THIS ONE HAS BEEN OVERTAKEN. `_reference_payload` has since grown
         * two more keys — `outOfScope` and `outOfScopeOption` — which answer a by-id lookup that the
         * reference picker's card reader now DOES make
         * (`WorkshopRepository.designWorkshopReferenceById`). The capture is left as it was because
         * it is a capture of the LIST request, which never sends `recordId` and can therefore never
         * be answered with either key — and because `ignoreUnknownKeys = true` means an added key
         * changes nothing here either way. `DwReferenceScanTest` is where the by-id shape is pinned.
         * Do not read the list above as the current shape of the payload; read
         * `_reference_payload` in `design_workshops.py`, which is the authority.
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

        /**
         * A `model=DwSketch` answer, as `_in_record_options` composes one.
         *
         * NOT A CAPTURE, and said plainly: the two live captures in this file are real bytes off the
         * running API, this one is written to the shape `_reference_payload` emits, because a
         * `DwSketch` list needs a workshop with stage 11 filled in and this test module has none.
         * `tests/test_reference_resolver.py` is what holds the server to it — the section at the
         * foot of that file asserts the ordering, the flag, the empty `data` and the two payload
         * keys against the real function.
         *
         * THE TENTATIVE ROW IS SECOND BY `ordinal` AND FIRST HERE, deliberately: that is what the
         * server's partition does, and reproducing it in the fixture is the only way a test on this
         * side can show that the client takes the order rather than making one.
         */
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
