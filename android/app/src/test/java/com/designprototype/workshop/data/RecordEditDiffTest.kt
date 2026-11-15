package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordEditDiffTest {
    private val formJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val clearable = setOf(
        "localName", "gender", "phone", "email", "address", "notes", "dateOfBirth", "craftStartDate",
        "experienceYears", "experienceMonths", "dos", "donts", "designWorkshopId", "workshopId",
    )

    private val stored = ArtisanDetailDto(
        id = "a1",
        name = "Ramesh",
        phone = "+91 98765 43210",
        phoneCountryCode = "+91",
        email = "ramesh@example.org",
        place = "Bhuj",
        dateOfBirth = "1980-04-02T00:00:00Z",
        craftStartDate = "2000-01-01T00:00:00+00:00",
        experienceYears = 26,
        dos = "Keep tools clean\nUse natural dyes",
        donts = "Rush the drying",
        craftId = "c1",
        workshopId = "w1",
        status = "PENDING",
        location = LocationDto(latitude = 23.25, longitude = 69.67, state = "Gujarat"),
        updatedAt = "2026-11-10T08:00:00Z",
        editAccess = "FILL_EMPTY",
    )

    private fun unchangedRequest() = ArtisanCreateRequest(
        name = "Ramesh",
        phone = "9876543210",
        phoneCountryCode = "+91",
        email = "ramesh@example.org",
        place = "Bhuj",
        dateOfBirth = "1980-04-02",
        craftStartDate = "2000-01-01",
        experienceYears = 26,
        pehchanCardAvailable = true,
        dos = "Keep tools clean\nUse natural dyes",
        donts = "Rush the drying",
        craftId = "c1",
        workshopId = "w1",
        status = "PENDING",
        location = null,
        formContract = ARTISAN_FORM_CONTRACT,
    )

    private fun sentBody(request: ArtisanCreateRequest): JsonObject {
        val record = formJson.encodeToJsonElement(ArtisanDetailDto.serializer(), stored).jsonObject
        val asTyped = formJson.encodeToJsonElement(ArtisanCreateRequest.serializer(), request).jsonObject
        val changed = correctionChangedKeys(asTyped, record)
        val wire = wireJson.encodeToJsonElement(ArtisanCreateRequest.serializer(), request).jsonObject.toMutableMap()
        for (column in clearable) if (column !in wire) wire[column] = JsonNull
        return restrictEditBody(JsonObject(wire), changed)
    }

    @Test
    fun an_unchanged_artisan_edit_sends_only_the_form_contract() {
        val body = sentBody(unchangedRequest())
        assertEquals(setOf("formContract"), body.keys)
        assertEquals(ARTISAN_FORM_CONTRACT.toString(), body["formContract"]!!.jsonPrimitive.content)
    }

    @Test
    fun date_and_phone_formatting_are_not_changes() {
        assertTrue(sameMoment("1980-04-02", "1980-04-02T00:00:00Z"))
        assertTrue(sameMoment("1980-04-02", "1980-04-02T00:00:00+00:00"))
        assertTrue(sameMoment("2026-11-10T08:00:00Z", "2026-11-10T08:00:00.000+00:00"))
        assertFalse(sameMoment("1980-04-03", "1980-04-02T00:00:00Z"))
        assertTrue(samePhone("9876543210", "+91 98765 43210"))
        assertTrue(samePhone("+91", "91"))
        assertFalse(samePhone("9876543210", "9123456780"))
        assertTrue(sameRecordValue("phone", JsonPrimitive("9876543210"), JsonPrimitive("98765-43210")))
        assertTrue(sameRecordValue("dateOfBirth", JsonPrimitive("1980-04-02"), JsonPrimitive("1980-04-02T00:00:00Z")))
    }

    @Test
    fun a_real_change_is_sent() {
        val body = sentBody(unchangedRequest().copy(place = "Anjar", experienceYears = 27))
        assertEquals(setOf("formContract", "place", "experienceYears"), body.keys)
        assertEquals("Anjar", body["place"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_cleared_field_is_sent_as_null_and_untouched_empty_columns_are_not() {
        val body = sentBody(unchangedRequest().copy(email = null))
        assertEquals(setOf("formContract", "email"), body.keys)
        assertEquals(JsonNull, body["email"])
        assertFalse("notes was empty before and after", "notes" in body)
        assertFalse("designWorkshopId was never set", "designWorkshopId" in body)
    }

    @Test
    fun a_kept_workshop_link_is_not_cleared_and_a_removed_one_is() {
        assertFalse("workshopId" in sentBody(unchangedRequest()))
        val body = sentBody(unchangedRequest().copy(workshopId = null))
        assertEquals(JsonNull, body["workshopId"])
    }

    @Test
    fun link_lists_compare_as_sets_and_creation_metadata_is_never_a_change() {
        val record = buildRecord("artisanIds" to listOf("a1", "a2"))
        val same = buildRecord("artisanIds" to listOf("a2", "a1")) + ("recordedTimezone" to JsonPrimitive("Asia/Kolkata"))
        assertEquals(emptySet<String>(), recordEditChangedKeys(JsonObject(same), record))
        val added = buildRecord("artisanIds" to listOf("a1", "a2", "a3"))
        assertEquals(setOf("artisanIds"), recordEditChangedKeys(added, record))
    }

    @Test
    fun reflowed_numbered_points_are_not_a_change() {
        assertTrue(
            sameRecordValue("dos", JsonPrimitive("Keep tools clean\nUse natural dyes"), JsonPrimitive("Keep tools clean\r\n\r\nUse  natural dyes "))
        )
        assertFalse(sameRecordValue("dos", JsonPrimitive("Keep tools clean"), JsonPrimitive("Keep tools clean\nUse natural dyes")))
    }

    @Test
    fun without_a_loaded_record_the_whole_body_is_sent() {
        val body = JsonObject(mapOf("name" to JsonPrimitive("Ramesh"), "place" to JsonPrimitive("Bhuj")))
        assertEquals(body, restrictEditBody(body, null))
    }

    private fun buildRecord(vararg lists: Pair<String, List<String>>): JsonObject =
        JsonObject(lists.associate { (key, ids) -> key to kotlinx.serialization.json.JsonArray(ids.map { JsonPrimitive(it) }) })
}
