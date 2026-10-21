package com.designprototype.workshop.data

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handset's copy of `designers.prefill_from_profile`: every mapped column reaches its stage box,
 * and a stage only ever takes these values into EMPTY boxes.
 */
class DesignerPrefillTest {

    private val full = DesignerProfileDto(
        userId = "u1",
        displayName = "Latha Rao",
        localName = "ಲತಾ ರಾವ್",
        designation = "Senior Designer",
        institution = "NID Ahmedabad",
        department = "Textile Design",
        qualification = "M.Des",
        specialisation = "Weaving",
        experienceYears = 12,
        experienceMonths = 6,
        biography = "Works with handloom clusters.",
        phone = "98765 43210",
        phoneCountryCode = "+91",
        email = "latha@example.org",
        website = "https://example.org",
        addressLine = "12 Nagar Road\nNear the temple",
        city = "Jaipur",
        state = "Rajasthan",
        pincode = "302001",
        photoMediaId = "media-photo",
        signatureMediaId = "media-sign",
        cvMediaId = "media-cv",
        empanelmentNo = "EMP-42",
        empanelmentDate = "2026-08-25T00:00:00.000Z",
    )

    private val values = designerPrefillFromProfile(full, "Account Name")

    private fun text(key: String): String? = (values[key] as? JsonPrimitive)?.content

    @Test fun displayName_reaches_designerName() = assertEquals("Latha Rao", text("designerName"))
    @Test fun institution_reaches_designerInstitution() = assertEquals("NID Ahmedabad", text("designerInstitution"))
    @Test fun biography_reaches_designerProfile() = assertEquals("Works with handloom clusters.", text("designerProfile"))
    @Test fun experienceYears_reaches_designerExperience_as_a_number() {
        val value = values["designerExperience"] as JsonPrimitive
        assertFalse(value.isString)
        assertEquals("12", value.content)
    }
    @Test fun localName_reaches_designerLocalName() = assertEquals("ಲತಾ ರಾವ್", text("designerLocalName"))
    @Test fun designation_reaches_designerDesignation() = assertEquals("Senior Designer", text("designerDesignation"))
    @Test fun department_reaches_designerDepartment() = assertEquals("Textile Design", text("designerDepartment"))
    @Test fun qualification_reaches_designerQualification() = assertEquals("M.Des", text("designerQualification"))
    @Test fun specialisation_reaches_designerSpecialisation() = assertEquals("Weaving", text("designerSpecialisation"))
    @Test fun phone_reaches_designerPhone_with_its_country_code() = assertEquals("+91 9876543210", text("designerPhone"))
    @Test fun email_reaches_designerEmail() = assertEquals("latha@example.org", text("designerEmail"))
    @Test fun website_reaches_designerWebsite() = assertEquals("https://example.org", text("designerWebsite"))
    @Test fun addressLine_reaches_designerAddress_on_one_line() = assertEquals("12 Nagar Road Near the temple", text("designerAddress"))
    @Test fun city_reaches_designerCity() = assertEquals("Jaipur", text("designerCity"))
    @Test fun state_reaches_designerState() = assertEquals("Rajasthan", text("designerState"))
    @Test fun pincode_reaches_designerPincode() = assertEquals("302001", text("designerPincode"))
    @Test fun empanelmentNo_reaches_designerEmpanelmentNo() = assertEquals("EMP-42", text("designerEmpanelmentNo"))
    @Test fun empanelmentDate_reaches_designerEmpanelmentDate_as_a_date() = assertEquals("2026-08-25", text("designerEmpanelmentDate"))
    @Test fun photoMediaId_reaches_designerPhoto() = assertEquals("media-photo", text("designerPhoto"))
    @Test fun signatureMediaId_reaches_designerSignature() = assertEquals("media-sign", text("designerSignature"))
    @Test fun cvMediaId_reaches_designerCv() = assertEquals("media-cv", text("designerCv"))

    @Test
    fun every_pair_is_answered_by_a_full_profile() {
        assertEquals(DESIGNER_PREFILL_MAP.map { it.second }.toSet(), values.keys)
    }

    @Test
    fun a_phone_that_will_not_normalise_is_carried_as_typed() {
        assertEquals("12", designerPrefillPhone("+91", " 12 "))
        assertEquals(null, designerPrefillPhone("+91", "  "))
        assertEquals("+91 9876543210", designerPrefillPhone(null, "09876543210"))
    }

    @Test
    fun a_rich_text_address_is_flattened_to_one_line() {
        val stored = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"12 Nagar Road"}]},{"kind":"PARAGRAPH","spans":[{"text":"Jaipur"}]}]}"""
        val answer = designerPrefillFromProfile(DesignerProfileDto(addressLine = stored), null)
        assertEquals("12 Nagar Road Jaipur", (answer["designerAddress"] as JsonPrimitive).content)
    }

    @Test
    fun the_one_line_fold_collapses_carriage_returns_and_blank_lines() {
        assertEquals("a b c", designerPrefillOneLine(" a \r\n\r\n b\rc "))
    }

    @Test
    fun the_stated_location_fills_blank_address_columns() {
        val located = DesignerProfileDto(
            location = LocationDto(
                latitude = 26.81, longitude = 75.54,
                address = "Bagru main road", district = "Jaipur", state = "Rajasthan", pincode = "303007",
            ),
        )
        val answer = designerPrefillFromProfile(located, null)
        assertEquals("Bagru main road", (answer["designerAddress"] as JsonPrimitive).content)
        assertEquals("Jaipur", (answer["designerCity"] as JsonPrimitive).content)
        assertEquals("Rajasthan", (answer["designerState"] as JsonPrimitive).content)
        assertEquals("303007", (answer["designerPincode"] as JsonPrimitive).content)
    }

    @Test
    fun the_place_name_stands_in_for_a_missing_district() {
        val located = DesignerProfileDto(location = LocationDto(latitude = 1.0, longitude = 1.0, placeName = "Bagru"))
        assertEquals("Bagru", (designerPrefillFromProfile(located, null)["designerCity"] as JsonPrimitive).content)
    }

    @Test
    fun a_filled_column_wins_over_the_location() {
        val both = DesignerProfileDto(
            city = "Ahmedabad", state = "Gujarat",
            location = LocationDto(latitude = 1.0, longitude = 1.0, district = "Jaipur", state = "Rajasthan"),
        )
        val answer = designerPrefillFromProfile(both, null)
        assertEquals("Ahmedabad", (answer["designerCity"] as JsonPrimitive).content)
        assertEquals("Gujarat", (answer["designerState"] as JsonPrimitive).content)
    }

    @Test
    fun the_account_name_stands_in_for_a_missing_display_name() {
        val answer = designerPrefillFromProfile(DesignerProfileDto(displayName = "  "), " R. Mohanty ")
        assertEquals("R. Mohanty", (answer["designerName"] as JsonPrimitive).content)
        assertEquals(setOf("designerName"), answer.keys)
    }

    @Test
    fun an_empty_profile_and_no_account_name_answer_nothing() {
        assertTrue(designerPrefillFromProfile(DesignerProfileDto(), null).isEmpty())
    }

    private val stage = StageDto(
        key = "workshopPlan",
        entities = listOf(
            EntityDto(
                key = "plan",
                cardinality = "SINGLETON",
                fields = listOf(
                    FieldDto(key = "designerName"),
                    FieldDto(key = "designerInstitution"),
                    FieldDto(key = "designerPhone"),
                    FieldDto(key = "designerCity", deprecated = true),
                ),
            ),
            EntityDto(key = "rows", cardinality = "COLLECTION", fields = listOf(FieldDto(key = "designerEmail"))),
        ),
    )

    @Test
    fun only_empty_boxes_are_filled_and_typed_ones_are_kept() {
        val current: Map<String, JsonElement> = mapOf(
            "designerName" to JsonPrimitive("Typed by the designer"),
            "designerInstitution" to JsonPrimitive("  "),
        )
        val filled = designerPrefillIntoEmpty(stage, current, values)
        assertEquals(setOf("designerInstitution", "designerPhone"), filled.keys)
        assertEquals("NID Ahmedabad", (filled["designerInstitution"] as JsonPrimitive).content)
    }

    @Test
    fun keys_outside_the_stage_singletons_and_deprecated_fields_are_never_written() {
        val filled = designerPrefillIntoEmpty(stage, emptyMap(), values)
        assertFalse("designerEmail" in filled)
        assertFalse("designerCity" in filled)
        assertFalse("designerAddress" in filled)
    }

    @Test
    fun a_queued_save_becomes_the_cached_profile_and_keeps_the_location() {
        val previous = DesignerProfileDto(userId = "u1", location = LocationDto(latitude = 1.0, longitude = 1.0, state = "Goa"))
        val body = DesignerProfileUpdateBody(displayName = "New Name", institution = "NIFT")
        val next = body.asProfile(previous)
        assertEquals("New Name", next.displayName)
        assertEquals("NIFT", next.institution)
        assertEquals("Goa", next.location?.state)
    }
}

/** The Kotlin map is the Python `designers.PREFILL_MAP`, pair for pair and in order. */
class DesignerPrefillParityTest {
    @Test
    fun the_handset_map_matches_the_backend_map() {
        val source = listOf(
            File("../../backend/app/services/designers.py"),
            File("../backend/app/services/designers.py"),
        ).first { it.isFile }.readText()
        val start = source.indexOf("PREFILL_MAP: tuple[tuple[str, str], ...] = (")
        assertTrue("PREFILL_MAP not found in designers.py", start >= 0)
        val end = source.indexOf("\n)", start)
        val block = source.substring(start, end)
        val pairs = Regex("""\(\s*"([A-Za-z]+)"\s*,\s*"([A-Za-z]+)"\s*\)""").findAll(block)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertEquals(pairs, DESIGNER_PREFILL_MAP)
    }
}
