package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C27 on the handset: the lookup answer decodes, fills the right boxes, and says the web's words.
 */
class CrossPortalPrefillTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val answer = """
        {"match": {"remoteArtisanId": "fr1", "matchedBy": "AADHAAR",
          "fields": {"name": " Meena Devi ", "gender": "Female", "dateOfBirth": "1980-05-01T00:00:00",
                     "pehchanCardAvailable": true, "pehchanCardNumber": "PEH12345", "experienceYears": 20,
                     "phone": "", "place": "Bhuj"},
          "identityFields": ["name", "localName", "gender", "dateOfBirth", "pehchanCardAvailable", "pehchanCardNumber"],
          "craft": {"code": "bandhani", "name": "Bandhani", "craftId": "c-local", "craftName": "Bandhej"},
          "statedAddress": {"state": "Gujarat", "district": "Kachchh", "village": null},
          "unknownKey": 1}}
    """.trimIndent()

    @Test
    fun aMatchFillsTheFormAndLeavesBlanksAlone() {
        val match = json.decodeFromString(CrossPortalLookupDto.serializer(), answer).match!!
        val prefill = CrossPortalPrefill.from(match)
        assertEquals("Meena Devi", prefill.name)
        assertEquals("1980-05-01", prefill.dateOfBirth)
        assertEquals(20, prefill.experienceYears)
        assertEquals("c-local", prefill.craftId)
        assertNull("an empty phone is not a value", prefill.phone)
        assertNull(prefill.localName)
        assertEquals("Meena Devi · Bandhani · Kachchh", CrossPortalPrefill.summary(match))
    }

    @Test
    fun noMatchDecodesAsNull() {
        assertNull(json.decodeFromString(CrossPortalLookupDto.serializer(), """{"match": null}""").match)
        assertEquals(false, json.decodeFromString(CrossPortalStatusDto.serializer(), "{}").enabled)
    }

    @Test
    fun theWordsAreTheWebsWords() {
        val web = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "frontend/components/forms/CrossPortalLookup.tsx") }
            .firstOrNull { it.isFile }
            ?.readText() ?: return
        for (phrase in listOf(
            CrossPortalCopy.CONSENT,
            CrossPortalCopy.FILL,
            CrossPortalCopy.NOT_THIS,
            CrossPortalCopy.NOT_FOUND,
            "Filled from the Field Repository",
            "Linked to the Field Repository.",
        )) {
            assertTrue("web lacks: $phrase", web.contains(phrase))
        }
    }
}
