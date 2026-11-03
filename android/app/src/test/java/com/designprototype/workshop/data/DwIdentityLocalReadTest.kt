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

class DwIdentityLocalReadTest {

    private fun deviceRead(vararg aadhaar: String) =
        IdentityCardText.IdentityCardReading(aadhaar = aadhaar.toList())

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
        assertNull(identityChoices(deviceRead("234567890124"), DwIdentityKind.ANY).first().confidence)
    }

    @Test
    fun `the on-device path refuses a bad checksum exactly as the server path does`() {
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

    @Test
    fun `a pehchan field gets nothing from the device, because the device does not read pehchan`() {
        assertEquals(0, identityChoices(deviceRead("234567890124"), DwIdentityKind.PEHCHAN).size)
    }

    @Test
    fun `a card-number field takes the device's aadhaar read`() {
        val choices = identityChoices(deviceRead("234567890124"), DwIdentityKind.ANY)
        assertEquals(1, choices.size)
        assertEquals(DwIdentityKind.AADHAAR, choices.first().kind)
    }

    @Test
    fun `an unlabelled proposal is dropped rather than validated by guesswork`() {
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

    @Test
    fun `no offline refusal ever contains an identity number`() {
        val messages = listOf(
            nothingFoundOffline(DwIdentityKind.AADHAAR, readableOnDevice = true, rejected = 0),
            nothingFoundOffline(DwIdentityKind.AADHAAR, readableOnDevice = true, rejected = 3),
            nothingFoundOffline(DwIdentityKind.ANY, readableOnDevice = true, rejected = 0),
            nothingFoundOffline(DwIdentityKind.PEHCHAN, readableOnDevice = false, rejected = 0),
        )
        messages.forEach { message ->
            assertFalse(message, Regex("[0-9]{4,}").containsMatchIn(message))
        }
    }

    @Test
    fun `the offline refusals give three different next actions`() {
        val notFound = nothingFoundOffline(DwIdentityKind.AADHAAR, readableOnDevice = true, rejected = 0)
        val misread = nothingFoundOffline(DwIdentityKind.AADHAAR, readableOnDevice = true, rejected = 2)
        val noPehchanReader = nothingFoundOffline(DwIdentityKind.PEHCHAN, readableOnDevice = false, rejected = 0)

        assertTrue(notFound, notFound.contains("Fill the frame"))
        assertTrue(misread, misread.contains("better light"))
        assertFalse(noPehchanReader, noPehchanReader.contains("Fill the frame"))
        assertTrue(noPehchanReader, noPehchanReader.contains("connection"))

        listOf(notFound, misread, noPehchanReader).forEach {
            assertTrue(it, it.contains("connection"))
        }
    }
}
