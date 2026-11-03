package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DwIdentityKind
import com.designprototype.workshop.ui.designworkshop.identityChoices
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwIdentityOcrWireTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun decode(raw: String) = json.decodeFromString(DwIdentityOcrDto.serializer(), raw)

    @Test
    fun `the live payload decodes to a populated candidate list`() {
        val payload = decode(LIVE_PAYLOAD)

        assertEquals(1, payload.aadhaarCandidates.size)
        val candidate = payload.aadhaarCandidates.first()
        assertEquals("234567890124", candidate.value)
        assertEquals("AADHAAR", candidate.kind)
        assertEquals(0.8, candidate.confidence, 0.0001)
        assertEquals("XXXX XXXX 0124", candidate.masked)
        assertEquals(ArtisanIdentity.mask(candidate.value), candidate.masked)
        assertEquals("gemini", payload.provider)
        assertTrue(payload.requiresConfirmation)
    }

    @Test
    fun `a server that omits requiresConfirmation is read as requiring it`() {
        val payload = decode("""{"aadhaarCandidates": [], "pehchanCandidates": []}""")
        assertTrue(payload.requiresConfirmation)
    }

    @Test
    fun `the shape this client used to expect carries nothing`() {
        val payload = decode("""{"number": "234567890124", "documentType": "AADHAAR", "confidence": 0.9}""")
        assertEquals(0, payload.aadhaarCandidates.size)
        assertEquals(0, payload.pehchanCandidates.size)
    }

    @Test
    fun `a card the reader could not read is an ordinary answer, not an error`() {
        val payload = decode(
            """{"aadhaarCandidates": [], "pehchanCandidates": [], "rejectedAadhaarCount": 3,
                "provider": "gemini", "requiresConfirmation": true}"""
        )
        assertEquals(0, payload.aadhaarCandidates.size)
        assertEquals(3, payload.rejectedAadhaarCount)
    }

    @Test
    fun `a candidate that fails the checksum is refused rather than warned about`() {
        val choices = identityChoices(
            aadhaar = listOf(
                DwIdentityCandidateDto(value = "234567890124", kind = "AADHAAR", confidence = 0.9),
                DwIdentityCandidateDto(value = "234567890123", kind = "AADHAAR", confidence = 0.9),
            ),
            pehchan = emptyList(),
            kind = DwIdentityKind.AADHAAR,
        )
        assertEquals(1, choices.size)
        assertEquals("234567890124", choices.first().value)
    }

    @Test
    fun `an aadhaar field is not offered pehchan numbers, and the reverse`() {
        val aadhaar = listOf(DwIdentityCandidateDto(value = "2345 6789 0124", kind = "AADHAAR"))
        val pehchan = listOf(DwIdentityCandidateDto(value = "pm-vw/12345", kind = "PEHCHAN"))

        val forAadhaar = identityChoices(aadhaar, pehchan, DwIdentityKind.AADHAAR)
        assertEquals(1, forAadhaar.size)
        assertEquals("234567890124", forAadhaar.first().value)

        val forPehchan = identityChoices(aadhaar, pehchan, DwIdentityKind.PEHCHAN)
        assertEquals(1, forPehchan.size)
        assertEquals("PMVW12345", forPehchan.first().value)
        assertEquals(DwIdentityKind.PEHCHAN, forPehchan.first().kind)
    }

    @Test
    fun `a card-number field takes either, aadhaar first`() {
        val choices = identityChoices(
            aadhaar = listOf(DwIdentityCandidateDto(value = "234567890124", kind = "AADHAAR")),
            pehchan = listOf(DwIdentityCandidateDto(value = "PMVW12345", kind = "PEHCHAN")),
            kind = DwIdentityKind.ANY,
        )
        assertEquals(2, choices.size)
        assertEquals(DwIdentityKind.AADHAAR, choices[0].kind)
        assertEquals(DwIdentityKind.PEHCHAN, choices[1].kind)
    }

    @Test
    fun `the same number read twice is offered once`() {
        val choices = identityChoices(
            aadhaar = listOf(
                DwIdentityCandidateDto(value = "234567890124", kind = "AADHAAR"),
                DwIdentityCandidateDto(value = "2345-6789-0124", kind = "AADHAAR"),
            ),
            pehchan = emptyList(),
            kind = DwIdentityKind.AADHAAR,
        )
        assertEquals(1, choices.size)
    }

    @Test
    fun `a pehchan number too short to be one is refused`() {
        val choices = identityChoices(
            aadhaar = emptyList(),
            pehchan = listOf(DwIdentityCandidateDto(value = "AB", kind = "PEHCHAN")),
            kind = DwIdentityKind.PEHCHAN,
        )
        assertEquals(0, choices.size)
        assertNull(ArtisanIdentity.pehchanError("ABCD"))
    }

    private companion object {
        const val LIVE_PAYLOAD = """
{
  "aadhaarCandidates": [
    {
      "value": "234567890124",
      "kind": "AADHAAR",
      "confidence": 0.8,
      "masked": "XXXX XXXX 0124"
    }
  ],
  "pehchanCandidates": [],
  "rejectedAadhaarCount": 0,
  "provider": "gemini",
  "requiresConfirmation": true
}
"""
    }
}
