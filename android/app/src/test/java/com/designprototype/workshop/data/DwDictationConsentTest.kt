package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One workshop's answer to "may its recordings leave the device" — the reading of it, the ordering of
 * two copies of it, and the words it is put in.
 *
 * WHAT THIS FILE IS FOR. Every way this can be wrong is silent, and one of them is not recoverable by
 * anybody noticing later: a consent the artisan WITHDREW coming back because a phone that had been in a
 * pocket for six days pushed the answer it was carrying. There is nothing on any screen that would show
 * that happening, no error, and no record of it except a decision log that says the consent was granted
 * again. So the rule is a pure function and it is asserted here, on a machine with no draft store, no
 * network and no server.
 */
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

    // ---------------------------------------------------------------------------------------
    // Reading a stored token
    // ---------------------------------------------------------------------------------------

    /**
     * ANYTHING THAT IS NOT ONE OF THE THREE TOKENS GATES, and the permissive answer needs the exact one.
     *
     * The reading has to agree with the server's `consent_of` token for token, because a phone that
     * permitted what the server refuses would spend a six-megabyte upload per field to be told so — and
     * because the tolerance can only ever run in the direction that gates. `"granted"` is a token the
     * server's Postgres enum refuses; nothing in this system writes it; and if one ever arrives it must
     * not be the thing that clears an artisan's voice to leave the handset.
     */
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
        // And the round trip through the token is exact in both directions, so a draft this app wrote is
        // always readable by the app that wrote it.
        DwTier3Consent.values().forEach { consent ->
            assertEquals(consent, dwTier3ConsentOf(dwTier3ConsentToken(consent)))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Two copies of one answer
    // ---------------------------------------------------------------------------------------

    /**
     * THE DEFECT THIS WHOLE FUNCTION EXISTS FOR: A WITHDRAWN CONSENT MUST NOT COME BACK.
     *
     * The sequence, exactly as it happens. On the 3rd, in a courtyard with no signal, a designer records
     * GRANTED; it sits in the draft unsent. On the 5th the artisan changes their mind and somebody records
     * REFUSED in the browser. On the 9th the first designer opens this workshop with signal. The rule this
     * replaces — "an unsent local answer is newer by construction" — pushed the 3rd's grant over the 5th's
     * withdrawal, silently, and reopened the rung.
     */
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

    /**
     * AND THE OTHER DIRECTION IS UNTOUCHED, which is the half a fail-closed rule would have broken.
     *
     * An artisan who says no on the 3rd and agrees on the 5th, recorded in a courtyard both times, must
     * have the 5th's answer reach the server. A rule that simply preferred the server, or simply preferred
     * the refusal, would strand the newer answer on the phone for ever and there would be no screen
     * anywhere that said why.
     */
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

    /**
     * WITH NO ORDERING TO BE HAD, THE ANSWER THAT GATES WINS.
     *
     * A draft written by a build that did not stamp the moment, or a stamp that will not parse, leaves
     * nothing to compare. Inventing an order would be inventing the one fact the decision turns on, so the
     * tie goes the way everything else about consent goes: closed. The cost is that a genuine re-grant from
     * such a draft has to be recorded again by the person who is standing there anyway; the cost of the
     * other choice is a voice that was withdrawn leaving the device.
     */
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
            // And a refusal on this device is not overridden by a grant up there either: the refusal is
            // the gating answer on both sides of the comparison.
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

    /**
     * A DATE WITH NO TIME IN IT IS NOT AN ORDERING, even though it prints perfectly well.
     *
     * `dwConsentDay` accepts a bare date because "3 Aug 2026" is an honest thing to show a designer.
     * Ordering two consents by it is not honest: midnight is not when anybody answered, and two answers on
     * the same day would compare equal and hand the decision to the tie-break rather than to the facts.
     */
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

    /**
     * THE SERVER NOT BEING REACHED IS NOT AN ANSWER OF "NOBODY HAS ASKED".
     *
     * The DTO's consent field is defaulted, so a payload from a server that predates the column decodes as
     * NOT_RECORDED — indistinguishable, from the field alone, from a server that was never read. Told apart
     * here by `serverKnown`, because one of them leaves the device's answer alone and the other is an answer
     * to reconcile against.
     */
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

    /**
     * AGREEMENT IS AN ACKNOWLEDGEMENT AND NOT A CONFLICT.
     *
     * The server holding the same answer means it arrived — from the web, from a colleague's handset, or
     * from an earlier push whose response never got back. Marking it synced is what stops this phone
     * re-pushing an answer that is already up there on every single open of the screen.
     */
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

    /**
     * AN ANSWER THIS DEVICE HAS ALREADY SENT DOES NOT ARGUE WITH THE SERVER'S.
     *
     * Once the server has acknowledged an answer, the server is where later answers arrive from — a
     * withdrawal on the web, a colleague's handset — and this device has nothing newer to offer. Including
     * the case that gates: a synced GRANTED against a server saying NOT_RECORDED takes the server's, which
     * is the fail-closed direction and the honest one (the row up there is what the upload is checked
     * against).
     */
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
        // And a device with no answer at all learns whatever the server has, which is the workshop
        // authored in a browser arriving on this handset for the first time.
        assertEquals(
            DwConsentMerge.TAKE_SERVER,
            dwConsentMerge(DraftConsent(), DW_CONSENT_GRANTED, theThird, serverKnown = true),
        )
    }

    /**
     * NOT_RECORDED IS NEVER "UNSENT", because it is the absence of an answer rather than one.
     *
     * The route refuses it by name — "somebody deliberately wrote down that nobody has been asked" is not a
     * state anybody is in — so a device carrying it has nothing to push, and a merge that tried would spend
     * a request per open to earn a 422.
     */
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

    // ---------------------------------------------------------------------------------------
    // The words a person reads before they answer
    // ---------------------------------------------------------------------------------------

    /**
     * NO SENTENCE ON THIS SCREEN MAY CLAIM MORE THAN THE GATE DELIVERS — OR LESS THAN IT DOES.
     *
     * TWO SENTENCES ARE PINNED OUT HERE, AND THE SECOND IS WHY THIS TEST WAS REWRITTEN.
     *
     * The first: both gating states used to read "this app sends nothing from it to the transcription
     * service", while a recording ATTACHED to a workshop as audio went through the media queue to the
     * same `transcribe_audio_bytes` chain with no consent read anywhere on that path. A designer who
     * read it, recorded the artisan's refusal and then uploaded the interview sent the very voice this
     * screen had just told them nothing would send.
     *
     * The second, which replaced it and has since gone false in the other direction: "this answer was
     * not given the power to stop that. Until it is, do not attach a recording of anybody who has said
     * no." The server now reads this same column before a transcription job is created
     * (`media_queue.queue_media_processing` → `dictation_consent.transcription_verdict`), again at the
     * drain (`_process_job`), and again in `transcribe_media_now`; and `POST /{id}/consent` calls
     * `cancel_pending_transcriptions` on a REFUSED decision, so a withdrawal reaches the clips already
     * queued. An UNDERclaim is not the safe direction on a consent screen: it tells a designer to hold
     * back an interview the server would refuse to send anyway, and tells the artisan the app cannot
     * honour an answer it now honours.
     *
     * WHAT MUST STILL BE SAID IS THE UPLOAD, because only the transcription is gated: the clip does
     * leave the phone for this project's server and is kept with the workshop, which is what
     * `DW_CONSENT_QUESTION` promises the artisan and what `dictation_consent.MEDIA`'s `alternative`
     * tells the designer at the other end.
     */
    @Test
    fun `the gating sentences do not promise more than the gate does`() {
        listOf(DwTier3Consent.NOT_RECORDED, DwTier3Consent.REFUSED).forEach { state ->
            val sentence = dwConsentStateSentence(state, null)
            assertFalse(
                "The dictation gate is not the only gate, but it is still not everything: $sentence",
                sentence.contains("sends nothing"),
            )
            assertTrue(
                "It has to name the audio the answer reaches: $sentence",
                sentence.contains("attached to this workshop as audio"),
            )
            // THE UNDERCLAIM, WHICH IS THE HALF THIS TEST GAINED. The retired sentence told a designer to
            // withhold the recording because the answer had no power over it. It has that power now, and
            // saying otherwise costs the workshop its interview for nothing.
            assertFalse(
                "The media flow IS gated now, so this may not be said: $sentence",
                sentence.contains("not given the power to stop that") ||
                    sentence.contains("do not attach a recording of anybody who has said no"),
            )
            assertTrue(
                "A refusal stops the writing-down, and the sentence has to say so: $sentence",
                sentence.contains("not sent out to be written down"),
            )
            // AND THE HALF THAT IS STILL NOT GATED. Upload is not transcription; the clip is kept with
            // the workshop deliberately, and an artisan who reads "no" as "nothing leaves the phone" has
            // been told something this app does not do.
            assertTrue(
                "The upload is not gated and the sentence may not imply it is: $sentence",
                sentence.contains("still uploaded and kept with it"),
            )
            // The platform recogniser is a network service on most of this fleet, so no sentence here may
            // imply a refusal keeps the voice on the phone.
            assertFalse(
                "A refusal does not keep the voice on the phone: $sentence",
                sentence.contains("stays on the phone"),
            )
            assertTrue("A sentence, ending in a full stop: $sentence", sentence.trim().endsWith("."))
        }
    }

    /**
     * THE QUESTION MAY NOT TELL AN ARTISAN THE SERVER KEEPS NO AUDIO, BECAUSE IT KEEPS SOME.
     *
     * It asks about "recordings AND dictation" — the user's own wording — and only one of those is true of
     * "the server keeps no copy". A dictated passage is returned and stored nowhere; a recording added to
     * the workshop as audio is kept with the workshop, on purpose, so it can be listened to again. Printing
     * the stronger claim above a Yes button was the one thing a consent screen cannot get wrong.
     */
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
            DW_CONSENT_QUESTION.contains("Ask the artisan whose voice it is"),
        )
    }

    /**
     * A SERVER THAT ANSWERED AND REFUSED IS NOT A SERVER THAT COULD NOT BE REACHED.
     *
     * Without the distinction the note promised that the answer "goes to the server the next time this
     * workshop is opened here with a connection" — for a body the server will refuse identically on every
     * open, for ever, while every dictation from the workshop goes on being refused up there. The refusal a
     * field phone actually earns is a wrong date, and the server's own 422 names the fix.
     */
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
            note.contains("goes to the server the next time"),
        )
        assertTrue(
            "And the gate here still honours it, said first: $note",
            note.contains("saved on this phone"),
        )

        // The ordinary courtyard case is unchanged and does NOT read as a failure.
        val offline = dwConsentRecordedNote(DwTier3Consent.GRANTED, synced = false, storedOnDevice = true)
        assertTrue(offline.contains("goes to the server the next time"))
        assertFalse(offline.contains("would not record"))
    }
}
