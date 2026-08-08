package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DwIdentityKind
import com.designprototype.workshop.ui.designworkshop.identityChoices
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's reading of `POST /design-workshops/ocr/identity`, asserted against the payload the
 * server actually sends.
 *
 * WHY THIS IS A PARITY TEST AND NOT A ROUND TRIP — the same reason [DwReferenceWireTest] gives, and
 * the same defect, on a second endpoint. Encoding a [DwIdentityOcrDto] and decoding it again would
 * have passed every day this feature has existed, because the DTO was self-consistent: it simply
 * named five keys the server has never sent. It declared `number`, `documentType`, `name`,
 * `confidence` and `message`; `IdentityOcrResult.payload()` returns `aadhaarCandidates`,
 * `pehchanCandidates`, `rejectedAadhaarCount`, `provider` and `requiresConfirmation`. With
 * `ignoreUnknownKeys = true` nothing threw — every field took its default, `number` came back "",
 * and a PERFECT read was reported to the designer as "No number could be read from that photograph".
 * The card looked unreadable; the reader was listening on the wrong keys. The web client
 * (`IdentityCardReader.tsx`) had the identical bug against the identical payload.
 *
 * [LIVE_PAYLOAD] below is the verbatim output of `IdentityOcrResult.payload()`, produced by RUNNING
 * it against the backend venv rather than transcribed from the source, with a Verhoeff-valid test
 * number substituted for the digits. `backend/tests/test_workshop_transcripts.py` guards the same
 * shape from the other side.
 *
 * The converter is configured exactly as `ApiClient` configures the one Retrofit uses. That matters
 * more than it looks: `ignoreUnknownKeys = true` is what turned a wrong key name into silence rather
 * than an exception, and a test that decoded strictly would not reproduce the defect at all.
 */
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

        // The whole defect in one assertion: this read 0 candidates for every card ever
        // photographed, on both clients, while the request itself returned 200 with the number in it.
        assertEquals(1, payload.aadhaarCandidates.size)
        val candidate = payload.aadhaarCandidates.first()
        assertEquals("234567890124", candidate.value)
        assertEquals("AADHAAR", candidate.kind)
        assertEquals(0.8, candidate.confidence, 0.0001)
        // The masked form travels on the wire so that a surface which must not print the number has
        // one to print without deriving it. It has to agree with the handset's own masking, or the
        // same artisan reads as two different people on two screens.
        assertEquals("XXXX XXXX 0124", candidate.masked)
        assertEquals(ArtisanIdentity.mask(candidate.value), candidate.masked)
        assertEquals("gemini", payload.provider)
        assertTrue(payload.requiresConfirmation)
    }

    @Test
    fun `a server that omits requiresConfirmation is read as requiring it`() {
        // The default is the safe direction on purpose. An older deployment, or a proxy that rewrote
        // the body, must never be read as PERMISSION TO WRITE an identity number without a person.
        val payload = decode("""{"aadhaarCandidates": [], "pehchanCandidates": []}""")
        assertTrue(payload.requiresConfirmation)
    }

    @Test
    fun `the shape this client used to expect carries nothing`() {
        // Kept as a regression witness rather than deleted with the bug. If somebody "restores" the
        // old key names because a stale comment or an older branch says so, this fails and says why.
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
        // The count is what tells a designer the card WAS found and misread — better light — rather
        // than not found at all — fill the frame. Two different next actions, so it is carried.
        assertEquals(3, payload.rejectedAadhaarCount)
    }

    // ── What the panel is willing to offer ───────────────────────────────────────────────────────

    @Test
    fun `a candidate that fails the checksum is refused rather than warned about`() {
        // The server has already applied Verhoeff, so in a healthy system this rejects nothing. If it
        // ever fires, the two ports of the rule have drifted — and the moment to find that out is
        // before the number is offered, not after it is stored as a deduplication key.
        val choices = identityChoices(
            aadhaar = listOf(
                DwIdentityCandidateDto(value = "234567890124", kind = "AADHAAR", confidence = 0.9),
                // One digit changed from the valid number above: still twelve digits, still starts
                // 2-9, and it belongs to nobody. This is exactly the shape of an OCR misread.
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
        // Spacing as the card prints it comes off the wire and is stripped before anything is
        // offered, because "2345 6789 0124" and "234567890124" are one number and the unique index
        // that deduplicates artisans does not know that.
        assertEquals("234567890124", forAadhaar.first().value)

        val forPehchan = identityChoices(aadhaar, pehchan, DwIdentityKind.PEHCHAN)
        assertEquals(1, forPehchan.size)
        assertEquals("PMVW12345", forPehchan.first().value)
        assertEquals(DwIdentityKind.PEHCHAN, forPehchan.first().kind)
    }

    @Test
    fun `a card-number field takes either, aadhaar first`() {
        // The registry's `artisanCardNo` is filled from whichever card the artisan produced.
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
        // …and the rule that refused it is the server's own, not a second one invented here.
        assertNull(ArtisanIdentity.pehchanError("ABCD"))
    }

    private companion object {
        /**
         * Printed by running `IdentityOcrResult(...).payload()` under `backend/.venv`, not copied
         * out of the source. Whitespace is the `json.dumps(indent=2)` output verbatim.
         */
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
