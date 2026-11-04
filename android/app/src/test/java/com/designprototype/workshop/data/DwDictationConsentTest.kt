package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwDictationConsentTest {

    private val theThird = "2026-08-03T10:15:00.000Z"
    private val theFifth = "2026-08-05T09:00:00.000Z"

    private fun granted(recordedAt: String? = theThird, synced: Boolean = false) = DraftConsent(
        decision = DW_CONSENT_GRANTED,
        recordedAt = recordedAt,
        recordedById = "usr_meera",
        recordedByName = "Meera Joshi",
        synced = synced,
    )

    private fun refused(recordedAt: String? = theThird, synced: Boolean = false) =
        granted(recordedAt, synced).copy(decision = DW_CONSENT_REFUSED)

    @Test
    fun `only the three tokens are readable and everything else gates`() {
        assertEquals(DwTier3Consent.GRANTED, dwTier3ConsentOf(DW_CONSENT_GRANTED))
        assertEquals(DwTier3Consent.REFUSED, dwTier3ConsentOf(DW_CONSENT_REFUSED))
        assertEquals(DwTier3Consent.NOT_RECORDED, dwTier3ConsentOf(DW_CONSENT_NOT_RECORDED))
        listOf(null, "", "   ", "granted", " GRANTED ", "GRANTED_WITH_NOTE", "yes", "true", "1")
            .forEach { token ->
                assertEquals(
                    "Only an exact GRANTED may open the rung: $token",
                    DwTier3Consent.NOT_RECORDED,
                    dwTier3ConsentOf(token),
                )
            }
        DwTier3Consent.values().forEach { consent ->
            assertEquals(consent, dwTier3ConsentOf(dwTier3ConsentToken(consent)))
        }
    }

    @Test
    fun `a withdrawal on the server beats an older grant this phone never sent`() {
        assertEquals(
            DwConsentMerge.TAKE_SERVER,
            dwConsentMerge(
                onDevice = granted(recordedAt = theThird),
                serverDecision = DW_CONSENT_REFUSED,
                serverRecordedAt = theFifth,
                serverKnown = true,
            ),
        )
    }

    @Test
    fun `an answer recorded here later than the server's is the one that is pushed`() {
        assertEquals(
            DwConsentMerge.PUSH_DEVICE,
            dwConsentMerge(
                onDevice = granted(recordedAt = theFifth),
                serverDecision = DW_CONSENT_REFUSED,
                serverRecordedAt = theThird,
                serverKnown = true,
            ),
        )
        assertEquals(
            DwConsentMerge.PUSH_DEVICE,
            dwConsentMerge(
                onDevice = refused(recordedAt = theFifth),
                serverDecision = DW_CONSENT_GRANTED,
                serverRecordedAt = theThird,
                serverKnown = true,
            ),
        )
    }

    @Test
    fun `an unorderable pair resolves to the refusal`() {
        listOf(null, "", "not a date", "2026-08-03").forEach { unusable ->
            assertEquals(
                "A grant that cannot be shown to be newer must not beat a refusal: $unusable",
                DwConsentMerge.TAKE_SERVER,
                dwConsentMerge(
                    onDevice = granted(recordedAt = unusable),
                    serverDecision = DW_CONSENT_REFUSED,
                    serverRecordedAt = theFifth,
                    serverKnown = true,
                ),
            )
            assertEquals(
                DwConsentMerge.PUSH_DEVICE,
                dwConsentMerge(
                    onDevice = refused(recordedAt = unusable),
                    serverDecision = DW_CONSENT_GRANTED,
                    serverRecordedAt = theFifth,
                    serverKnown = true,
                ),
            )
        }
    }

    @Test
    fun `a bare date is not treated as a moment`() {
        assertEquals(
            DwConsentMerge.TAKE_SERVER,
            dwConsentMerge(
                onDevice = granted(recordedAt = "2026-08-09"),
                serverDecision = DW_CONSENT_REFUSED,
                serverRecordedAt = "2026-08-05T09:00:00Z",
                serverKnown = true,
            ),
        )
    }

    @Test
    fun `an unreachable server leaves the answer here alone`() {
        assertEquals(
            DwConsentMerge.PUSH_DEVICE,
            dwConsentMerge(granted(), serverDecision = null, serverRecordedAt = null, serverKnown = false),
        )
        assertEquals(
            DwConsentMerge.KEEP_DEVICE,
            dwConsentMerge(
                onDevice = granted(synced = true),
                serverDecision = null,
                serverRecordedAt = null,
                serverKnown = false,
            ),
        )
        assertEquals(
            "Nothing recorded and nothing read is nothing to do",
            DwConsentMerge.KEEP_DEVICE,
            dwConsentMerge(DraftConsent(), serverDecision = null, serverRecordedAt = null, serverKnown = false),
        )
    }

    @Test
    fun `a server that already holds this answer is an acknowledgement`() {
        assertEquals(
            DwConsentMerge.MARK_SYNCED,
            dwConsentMerge(granted(), DW_CONSENT_GRANTED, theFifth, serverKnown = true),
        )
        assertEquals(
            DwConsentMerge.KEEP_DEVICE,
            dwConsentMerge(granted(synced = true), DW_CONSENT_GRANTED, theThird, serverKnown = true),
        )
    }

    @Test
    fun `a synced answer yields to the server`() {
        assertEquals(
            DwConsentMerge.TAKE_SERVER,
            dwConsentMerge(granted(synced = true), DW_CONSENT_REFUSED, theFifth, serverKnown = true),
        )
        assertEquals(
            DwConsentMerge.TAKE_SERVER,
            dwConsentMerge(granted(synced = true), DW_CONSENT_NOT_RECORDED, null, serverKnown = true),
        )
        assertEquals(
            DwConsentMerge.TAKE_SERVER,
            dwConsentMerge(DraftConsent(), DW_CONSENT_GRANTED, theThird, serverKnown = true),
        )
    }

    @Test
    fun `a workshop nobody has answered for has nothing to push`() {
        assertEquals(
            DwConsentMerge.KEEP_DEVICE,
            dwConsentMerge(DraftConsent(), null, null, serverKnown = false),
        )
        assertEquals(
            DwConsentMerge.KEEP_DEVICE,
            dwConsentMerge(DraftConsent(), DW_CONSENT_NOT_RECORDED, null, serverKnown = true),
        )
    }

    @Test
    fun `the gating sentences do not promise more than the gate does`() {
        assertTrue(
            dwConsentStateSentence(DwTier3Consent.NOT_RECORDED, null).startsWith("On by default"),
        )
        listOf(DwTier3Consent.REFUSED).forEach { state ->
            val sentence = dwConsentStateSentence(state, null)
            assertFalse(
                "The dictation gate is not the only gate, but it is still not everything: $sentence",
                sentence.contains("sends nothing"),
            )
            assertTrue(
                "It has to name the audio the answer reaches: $sentence",
                sentence.contains("attached to this workshop as audio"),
            )
            assertFalse(
                "The media flow IS gated now, so this may not be said: $sentence",
                sentence.contains("not given the power to stop that") ||
                    sentence.contains("do not attach a recording of anybody who has said no"),
            )
            assertTrue(
                "A refusal stops the writing-down, and the sentence has to say so: $sentence",
                sentence.contains("not sent out to be written down"),
            )
            assertTrue(
                "The upload is not gated and the sentence may not imply it is: $sentence",
                sentence.contains("still uploaded and kept with it"),
            )
            assertFalse(
                "A refusal does not keep the voice on the phone: $sentence",
                sentence.contains("stays on the phone"),
            )
            assertTrue("A sentence, ending in a full stop: $sentence", sentence.trim().endsWith("."))
        }
    }

    @Test
    fun `the question tells both halves apart`() {
        assertFalse(
            "Not true of workshop audio: $DW_CONSENT_QUESTION",
            DW_CONSENT_QUESTION.contains("the server keeps no copy of the audio"),
        )
        assertTrue(
            "The dictated passage is the half that is not kept",
            DW_CONSENT_QUESTION.contains("dictated into a field is not kept"),
        )
        assertTrue(
            "And the attached recording is the half that is",
            DW_CONSENT_QUESTION.contains("kept with the workshop"),
        )
        assertTrue(
            "The question has to be put to the person whose voice it is",
            DW_CONSENT_QUESTION.contains("Tell the artisan whose voice it is"),
        )
        assertTrue("On by default, and switched off on a no", DW_CONSENT_QUESTION.startsWith("On by default"))
        assertEquals("Recordings may be transcribed", DW_CONSENT_SWITCH_LABEL)
    }

    @Test
    fun `a refused push is not reported as one that will arrive later`() {
        val refusal = "The device's clock is wrong. Fix the date and time on the phone and sync again."
        val note = dwConsentRecordedNote(
            consent = DwTier3Consent.GRANTED,
            synced = false,
            storedOnDevice = true,
            serverRefusal = refusal,
        )
        assertTrue("The server's own sentence is passed through: $note", note.contains(refusal))
        assertFalse(
            "It will not arrive on the next open, so do not say it will: $note",
            note.contains("uploads the next time"),
        )
        assertTrue(
            "And the gate here still honours it, said first: $note",
            note.contains("saved on this phone"),
        )

        val offline = dwConsentRecordedNote(DwTier3Consent.GRANTED, synced = false, storedOnDevice = true)
        assertTrue(offline.contains("uploads the next time"))
        assertFalse(offline.contains("could not be saved online"))
    }
}
