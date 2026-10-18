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
            CrossPortalCopy.FILL,
            CrossPortalCopy.NOT_THIS,
            CrossPortalCopy.NOT_FOUND,
            "Filled from the Field Repository",
            "Linked to the Field Repository.",
        )) {
            assertTrue("web lacks: $phrase", web.contains(phrase))
        }
        val lib = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "frontend/lib/crossPortal.ts") }
            .firstOrNull { it.isFile }
            ?.readText() ?: return
        assertTrue(lib.contains(CrossPortalCopy.SHARED_FIELDS_FALLBACK))
        assertTrue(lib.contains("The artisan agrees to share their ${'$'}{sharedFields} between the two portals"))
    }

    @Test
    fun theConsentNamesEverythingTheLookupSends() {
        val words = CrossPortalCopy.consent(null)
        for (item in listOf("phone number", "address", "place", "craft", "date of birth", "Pehchan card")) {
            assertTrue("consent lacks: $item", words.contains(item))
        }
        assertEquals(
            "The artisan agrees to share their name and phone number between the two portals",
            CrossPortalCopy.consent("name and phone number"),
        )
        assertEquals("x", json.decodeFromString(CrossPortalStatusDto.serializer(), """{"enabled":true,"sharedFields":"x"}""").sharedFields)
    }

    @Test
    fun aTypedPehchanNumberSurvivesAMatchThatHasNone() {
        val none = CrossPortalPrefill(pehchanCardAvailable = false)
        val kept = CrossPortalPrefill.keepTypedPehchan(none, " PEH777 ")
        assertEquals(true, kept.pehchanCardAvailable)
        assertEquals("PEH777", kept.pehchanCardNumber)
        val theirs = CrossPortalPrefill(pehchanCardNumber = "PEH1")
        assertEquals("PEH1", CrossPortalPrefill.keepTypedPehchan(theirs, "PEH777").pehchanCardNumber)
        assertEquals(none, CrossPortalPrefill.keepTypedPehchan(none, ""))
    }

    @Test
    fun aRefreshHandsTheFormOnlyTheChangedIdentityValues() {
        val refreshed = json.decodeFromString(
            CrossPortalRefreshDto.serializer(),
            """{"link":{"linked":true},"changed":["name","dateOfBirth"],"artisan":{"id":"a1","name":"Meena Bai","gender":"Female","dateOfBirth":"1981-01-02T00:00:00","phone":"1"}}""",
        )
        val values = CrossPortalPrefill.fromRefreshed(refreshed.artisan!!, refreshed.changed)
        assertEquals("Meena Bai", values.name)
        assertEquals("1981-01-02", values.dateOfBirth)
        assertNull("gender did not change", values.gender)
        assertNull(json.decodeFromString(CrossPortalRefreshDto.serializer(), """{"changed":[]}""").artisan)
    }

    @Test
    fun theStatedAddressGoesOntoTheFixWithoutMovingIt() {
        val here = LocationRequest(latitude = 23.2, longitude = 69.6)
        val next = CrossPortalPrefill.withStatedAddress(here, CrossPortalAddressDto("Gujarat", "Kachchh", " Nirona "))
        assertEquals("Gujarat", next.state)
        assertEquals("Kachchh", next.district)
        assertEquals("Nirona", next.village)
        assertEquals(23.2, next.latitude, 0.0)
        val noState = CrossPortalPrefill.withStatedAddress(here, CrossPortalAddressDto(null, "Kachchh", null))
        assertNull(noState.district)
    }

    @Test
    fun theCardPrintsTheServersSentenceAndAsksBeforeUnlinking() {
        val card = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/designprototype/workshop/ui/CrossPortalCard.kt") }
            .firstOrNull { it.isFile }
            ?.readText() ?: return
        assertTrue(card.contains("apiRefusal(CrossPortalCopy.UNREACHABLE).message"))
        assertTrue(!card.contains(".onFailure { note = CrossPortalCopy.UNREACHABLE }"))
        assertTrue(card.contains("onClick = { confirmUnlink = true }"))
    }
}
