package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DwIdentityKind
import com.designprototype.workshop.ui.designworkshop.DwIdentitySource
import com.designprototype.workshop.ui.designworkshop.identityChoices
import com.designprototype.workshop.ui.designworkshop.nothingFoundOffline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two readers converging on one confirm path, and what the designer is told about which answered.
 *
 * A second reader is exactly how a rule stops being enforced on one surface: the on-device path grows
 * its own confirm flow, and six months later one of them is offering numbers that fail the checksum
 * or printing a full Aadhaar number in an error message, while both screens still look right. So
 * every number from either reader goes through the same `identityChoices`, and these tests hold both
 * to the same guarantees — with the SOURCE preserved, because the phone and the server do not have
 * the same accuracy and a person proofreading twelve digits is entitled to know which one produced
 * them.
 *
 * Numbers are Verhoeff-valid strings computed for these tests, not real Aadhaar numbers.
 */
class DwIdentityLocalReadTest {

    private fun deviceRead(vararg aadhaar: String) =
        IdentityCardText.IdentityCardReading(aadhaar = aadhaar.toList())

    // ── Provenance reaches the button ───────────────────────────────────────────────────────────

    @Test
    fun `a number read on this phone is labelled as read on this phone`() {
        val choices = identityChoices(deviceRead("234567890124"), DwIdentityKind.AADHAAR)
        assertEquals(1, choices.size)
        assertEquals(DwIdentitySource.ON_DEVICE, choices.first().source)
    }

    @Test
    fun `a number read by the server is labelled as read by the server`() {
        val choices = identityChoices(
            aadhaar = listOf(DwIdentityCandidateDto(value = "234567890124", kind = "AADHAAR")),
            pehchan = emptyList(),
            kind = DwIdentityKind.AADHAAR,
        )
        assertEquals(DwIdentitySource.SERVER, choices.first().source)
    }

    @Test
    fun `an on-device read carries no confidence rather than an invented one`() {
        // ML Kit returns no calibrated per-number confidence for this, and "0.9 because it passed the
        // checksum" would put a reassuring figure beside digits whose whole purpose on that screen is
        // to be doubted. A missing confidence prints nothing; it must not print a guess.
        assertNull(identityChoices(deviceRead("234567890124"), DwIdentityKind.ANY).first().confidence)
    }

    // ── One filter, both readers ────────────────────────────────────────────────────────────────

    @Test
    fun `the on-device path refuses a bad checksum exactly as the server path does`() {
        // IdentityCardText has already applied this rule, so in a healthy build this rejects nothing.
        // It is here for the unhealthy one: if the local reader ever proposes something the shared
        // filter refuses, the two have drifted, and the moment to find that out is before the number
        // is offered rather than after it is stored as a deduplication key.
        val local = identityChoices(deviceRead("234567890123"), DwIdentityKind.AADHAAR)
        assertEquals(0, local.size)

        val server = identityChoices(
            aadhaar = listOf(DwIdentityCandidateDto(value = "234567890123", kind = "AADHAAR")),
            pehchan = emptyList(),
            kind = DwIdentityKind.AADHAAR,
        )
        assertEquals(0, server.size)
    }

    @Test
    fun `both readers normalise the same number to the same string`() {
        // "2345 6789 0124" and "234567890124" are one number, and the unique index that deduplicates
        // artisans does not know that. If the two readers disagreed about the spelling, the same
        // artisan read on a phone and read on the server would become two records.
        val local = identityChoices(deviceRead("2345 6789 0124"), DwIdentityKind.AADHAAR)
        val server = identityChoices(
            aadhaar = listOf(DwIdentityCandidateDto(value = "2345-6789-0124", kind = "AADHAAR")),
            pehchan = emptyList(),
            kind = DwIdentityKind.AADHAAR,
        )
        assertEquals("234567890124", local.single().value)
        assertEquals(local.single().value, server.single().value)
    }

    @Test
    fun `the same number read twice on the device is offered once`() {
        assertEquals(1, identityChoices(deviceRead("234567890124", "2345 6789 0124"), DwIdentityKind.ANY).size)
    }

    // ── What the phone cannot do, held to explicitly ────────────────────────────────────────────

    @Test
    fun `a pehchan field gets nothing from the device, because the device does not read pehchan`() {
        // Not an accident of the data: a Pehchan number has no checksum and no fixed length, so
        // picking one out of raw recognised text would be a guess with nothing downstream able to
        // catch it being wrong. The flow falls through to the server, and the control says so.
        assertEquals(0, identityChoices(deviceRead("234567890124"), DwIdentityKind.PEHCHAN).size)
    }

    @Test
    fun `a card-number field takes the device's aadhaar read`() {
        // `artisanCardNo` is filled from whichever card the artisan produced, and most produce an
        // Aadhaar card — which is why the offline path is worth having at all.
        val choices = identityChoices(deviceRead("234567890124"), DwIdentityKind.ANY)
        assertEquals(1, choices.size)
        assertEquals(DwIdentityKind.AADHAAR, choices.first().kind)
    }

    @Test
    fun `an unlabelled proposal is dropped rather than validated by guesswork`() {
        // ANY is a question a FIELD asks, not an answer a reader gives. Applying the wrong validator
        // to an unlabelled string would offer an artisan's name as a card number.
        val choices = identityChoices(
            proposals = listOf(
                com.designprototype.workshop.ui.designworkshop.DwIdentityProposal(
                    value = "234567890124",
                    kind = DwIdentityKind.ANY,
                    confidence = null,
                )
            ),
            kind = DwIdentityKind.ANY,
            source = DwIdentitySource.ON_DEVICE,
        )
        assertEquals(0, choices.size)
    }

    // ── The masking rule, on the strings this lane added ────────────────────────────────────────

    @Test
    fun `no offline refusal ever contains an identity number`() {
        // The full number appears in exactly two places in this feature: the confirm panel and the
        // edit box. Everything else is masked or digit-free. These messages are produced on the path
        // where numbers WERE read and refused, which is precisely where a careless string would print
        // one.
        val messages = listOf(
            nothingFoundOffline(DwIdentityKind.AADHAAR, readableOnDevice = true, rejected = 0),
            nothingFoundOffline(DwIdentityKind.AADHAAR, readableOnDevice = true, rejected = 3),
            nothingFoundOffline(DwIdentityKind.ANY, readableOnDevice = true, rejected = 0),
            nothingFoundOffline(DwIdentityKind.PEHCHAN, readableOnDevice = false, rejected = 0),
        )
        messages.forEach { message ->
            // A count may appear ("3 number(s) were read"); a run of four or more digits may not,
            // because four digits is where ArtisanIdentity.mask stops masking.
            assertFalse(message, Regex("[0-9]{4,}").containsMatchIn(message))
        }
    }

    @Test
    fun `the offline refusals give three different next actions`() {
        val notFound = nothingFoundOffline(DwIdentityKind.AADHAAR, readableOnDevice = true, rejected = 0)
        val misread = nothingFoundOffline(DwIdentityKind.AADHAAR, readableOnDevice = true, rejected = 2)
        val noPehchanReader = nothingFoundOffline(DwIdentityKind.PEHCHAN, readableOnDevice = false, rejected = 0)

        // "Nothing was found" means the card was not in the frame — fill it.
        assertTrue(notFound, notFound.contains("Fill the frame"))
        // "Found and misread" means the light was wrong — a second photograph is worth taking.
        assertTrue(misread, misread.contains("better light"))
        // And a Pehchan card offline is not a photography problem at all, so it must not suggest one.
        assertFalse(noPehchanReader, noPehchanReader.contains("Fill the frame"))
        assertTrue(noPehchanReader, noPehchanReader.contains("connection"))

        // Every one of them says the server was NOT asked, so a designer who moves into signal knows
        // there is something left to try rather than concluding the card is unreadable.
        listOf(notFound, misread, noPehchanReader).forEach {
            assertTrue(it, it.contains("connection"))
        }
    }
}
