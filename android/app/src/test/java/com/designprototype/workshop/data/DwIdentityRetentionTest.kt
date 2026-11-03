package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.notKeptSuffix
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwIdentityRetentionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `the two decisions round-trip through every spelling of themselves`() {
        assertEquals(DW_RETENTION_STORE, parseRetention("STORE"))
        assertEquals(DW_RETENTION_STORE, parseRetention("store"))
        assertEquals(DW_RETENTION_STORE, parseRetention("  Store  "))
        assertEquals(DW_RETENTION_DISCARD, parseRetention("DISCARD"))
        assertEquals(DW_RETENTION_DISCARD, parseRetention("discard"))
    }

    @Test
    fun `every unrecognised value resolves to discard rather than raising`() {
        listOf(null, "", "   ", "keep", "KEEP", "true", "1", "yes", "None", "delete", "STORED", "st ore")
            .forEach { raw ->
                assertEquals(
                    "“$raw” must not be able to retain an identity document",
                    DW_RETENTION_DISCARD,
                    parseRetention(raw),
                )
            }
    }

    @Test
    fun `the default is discard`() {
        assertEquals(DW_RETENTION_DISCARD, DW_RETENTION_DEFAULT)
    }

    @Test
    fun `only an explicit stored-false counts as the server having promised anything`() {
        val explicit = json.decodeFromString(
            DwIdentityOcrDto.serializer(),
            """{"aadhaarCandidates":[],"pehchanCandidates":[],"rejectedAadhaarCount":0,
                "provider":"openai","requiresConfirmation":true,
                "photograph":{"stored":false,"retention":"DISCARD",
                              "decisionRoute":"/design-workshops/ocr/identity/retention"}}"""
        )
        assertTrue(photographWasNotStored(explicit))
        assertEquals("DISCARD", explicit.photograph?.retention)
        assertEquals("/design-workshops/ocr/identity/retention", explicit.photograph?.decisionRoute)

        val olderDeployment = json.decodeFromString(
            DwIdentityOcrDto.serializer(),
            """{"aadhaarCandidates":[],"pehchanCandidates":[],"rejectedAadhaarCount":0,
                "provider":"openai","requiresConfirmation":true}"""
        )
        assertNull(olderDeployment.photograph)
        assertFalse(
            "a missing key must produce silence, never a promise",
            photographWasNotStored(olderDeployment),
        )
    }

    @Test
    fun `a store declaration is echoed while stored stays false`() {
        val result = json.decodeFromString(
            DwIdentityOcrDto.serializer(),
            """{"photograph":{"stored":false,"retention":"STORE",
                "decisionRoute":"/design-workshops/ocr/identity/retention"}}"""
        )
        assertEquals("STORE", result.photograph?.retention)
        assertTrue(
            "the route has no storage path whatever the caller declares",
            photographWasNotStored(result),
        )
    }

    @Test
    fun `a discard reply says the row went and says separately that the object did`() {
        val result = json.decodeFromString(
            DwRetentionResultDto.serializer(),
            """{"mediaId":"med_1","decision":"DISCARD","deleted":true,"objectDeleted":true,
                "retention":null}"""
        )
        assertEquals(DW_RETENTION_DISCARD, result.decision)
        assertTrue(result.deleted)
        assertTrue(result.objectDeleted)
        assertNull(
            "a row saying 'this was discarded' would be a row that still names the photograph",
            result.retention,
        )
    }

    @Test
    fun `a store reply omits objectDeleted entirely and must still decode`() {
        val result = json.decodeFromString(
            DwRetentionResultDto.serializer(),
            """{"mediaId":"med_1","decision":"STORE","deleted":false,
                "retention":{"decision":"STORE","decidedById":"usr_1",
                             "decidedByName":"Asha Devi","decidedAt":"2026-08-16T09:30:00+00:00"}}"""
        )
        assertEquals(DW_RETENTION_STORE, result.decision)
        assertFalse(result.deleted)
        assertFalse("absent must read as false, not crash", result.objectDeleted)
        assertEquals("Asha Devi", result.retention?.decidedByName)
    }

    @Test
    fun `the discard sentence says the file and the record are both gone`() {
        val sentence = retentionOutcomeSentence(
            DwRetentionResultDto(mediaId = "med_1", decision = DW_RETENTION_DISCARD, deleted = true)
        )
        assertTrue(sentence.contains("both gone"))
        assertTrue("the confirmed number is unaffected and must be said", sentence.contains("number"))
    }

    @Test
    fun `the store sentence names the decider and says the photograph is unmasked`() {
        val sentence = retentionOutcomeSentence(
            DwRetentionResultDto(
                mediaId = "med_1",
                decision = DW_RETENTION_STORE,
                deleted = false,
                retention = DwRetentionStampDto(
                    decision = DW_RETENTION_STORE,
                    decidedById = "usr_1",
                    decidedByName = "Asha Devi",
                ),
            )
        )
        assertTrue(sentence.contains("Asha Devi"))
        assertTrue(sentence.contains("unmasked"))
    }

    @Test
    fun `a nameless stamp degrades to the same warning without an empty possessive`() {
        val sentence = retentionOutcomeSentence(
            DwRetentionResultDto(decision = DW_RETENTION_STORE, retention = DwRetentionStampDto())
        )
        assertFalse("“in 's name” would be the giveaway", sentence.contains("'s name"))
        assertTrue(sentence.contains("unmasked"))
    }

    @Test
    fun `the choice blurb warns when keeping and reassures when not`() {
        assertTrue(retentionChoiceBlurb(keep = true).contains("unmasked"))
        assertTrue(retentionChoiceBlurb(keep = true).contains("KEPT"))
        assertTrue(retentionChoiceBlurb(keep = false).contains("not kept"))
    }

    @Test
    fun `the not-kept promise is made only where nothing can be kept`() {
        assertTrue(notKeptSuffix(canKeep = false).contains("not kept"))
        assertEquals("", notKeptSuffix(canKeep = true))
    }
}
