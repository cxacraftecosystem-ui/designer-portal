package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwUploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **WHERE THIS PHOTOGRAPH IS** — the sentence the handset's attachment row could not say.
 *
 * ── WHAT THE ROW SAID BEFORE ─────────────────────────────────────────────────────────────────────
 *
 * `DwAttachmentRow`'s second line was `"image · 2.1 MB · tap to play"`, for every file, in every
 * state: on the device only, in flight, acknowledged by the server, or permanently refused. The only
 * other string it could produce was the missing-bytes sentence. So a designer looking straight at a
 * photograph had no way to learn whether the server had it — while the RECORD forms' capture surface
 * (`MainActivity.MediaCaptureSection`) had always said "All uploaded ✓ — ready to save" and
 * "Uploading… 42% (2/5 files done)", because that path uploads eagerly and had the numbers to hand.
 *
 * ── WHY THESE SENTENCES ARE WORTH A TEST ─────────────────────────────────────────────────────────
 *
 * Each one is a claim about where somebody's fieldwork is. "Backed up to the server" over a file the
 * server has never seen is the sentence that ends with a designer wiping a phone;
 * `DraftMedia.remoteMediaId` spends a paragraph on the same point and concludes that it is *"the
 * only thing in the app entitled to say otherwise"*. The precedence rules below are subtle enough to
 * be got wrong by a later edit and invisible enough that nobody would notice, which is exactly the
 * shape of thing that belongs in a spec rather than in a comment.
 */
class DwAttachmentStatusTest {

    private fun status(
        backedUp: Boolean = false,
        bytesPresent: Boolean = true,
        state: DwUploadState? = null,
        sizeBytes: Long = 2_100_000,
        mediaType: String = "IMAGE",
    ) = dwAttachmentStatus(mediaType, sizeBytes, bytesPresent, backedUp, state)

    /**
     * THE DEFAULT, AND DELIBERATELY NOT A WARNING.
     *
     * This is the correct and expected condition of every capture in a fortnight-long workshop.
     * Colouring it as a problem would train a designer to ignore the colour by the second day, and
     * the day it matters is the day it is ignored. It still says plainly that the phone is the only
     * copy, which is the fact `DwPhotoGate.GalleryCounts.onDevice` exists to keep visible.
     */
    @Test
    fun aFreshCaptureSaysThePhoneIsTheOnlyCopy() {
        val s = status()
        assertEquals("image · 2.1 MB · on this device only", s.line)
        assertEquals(DwAttachmentTone.NEUTRAL, s.tone)
        assertFalse(s.canRetry)
        assertEquals(null, s.percent)
    }

    /** The durable id is what says the server has it, and nothing else is allowed to. */
    @Test
    fun anAcknowledgedFileSaysSo() {
        assertEquals("image · 2.1 MB · backed up to the server", status(backedUp = true).line)
        assertEquals(DwAttachmentTone.NEUTRAL, status(backedUp = true).tone)
    }

    /**
     * BYTES, NOT JUST A PERCENTAGE — the whole reason the progress store carries a byte count.
     *
     * On a village link a 300 MB video spends over a minute and a half inside every single percent.
     * A row showing only "42%" is therefore frozen for minutes at a stretch and is indistinguishable
     * from a hung app, which is the complaint this answers. The figure beside it moves every second.
     */
    @Test
    fun aTransferInFlightShowsMovingBytes() {
        val s = status(state = DwUploadState.Sending(sent = 4_200_000, total = 11_200_000, atElapsedRealtime = 0L))
        assertEquals("Uploading… 37% · 4.2 MB of 11.2 MB", s.line)
        assertEquals(DwAttachmentTone.PROGRESS, s.tone)
        assertEquals(37, s.percent)
        // Nothing to press: a Try again beside a running transfer invites a designer to restart a
        // 300 MB upload from byte zero at 90%.
        assertFalse(s.canRetry)
    }

    /**
     * A PERMANENT REFUSAL IS THE ONLY STATE THAT GETS A BUTTON, because it is the only one where
     * nothing whatever happens until a person acts: `uploadPending` filters on
     * `it.uploadFailure == null`, so a parked file is excluded from every future pass.
     */
    @Test
    fun onlyAParkedFileOffersARetry() {
        val parked = status(state = DwUploadState.Refused("the file store refused it with HTTP 413.", permanent = true))
        assertTrue(parked.canRetry)
        assertEquals(DwAttachmentTone.WARNING, parked.tone)
        assertTrue(parked.line.startsWith("Not uploaded — "))
        assertTrue("the reason has to survive into the line", parked.line.contains("HTTP 413"))
    }

    /**
     * A momentary refusal says the app has this one, and offers nothing — otherwise a designer
     * spends a metered connection re-sending something that was already going to be re-sent.
     */
    @Test
    fun aMomentaryRefusalSaysTheAppWillRetry() {
        val blip = status(state = DwUploadState.Refused("the file store answered HTTP 503.", permanent = false))
        assertFalse(blip.canRetry)
        assertEquals(DwAttachmentTone.WARNING, blip.tone)
        assertTrue(blip.line.startsWith("Upload did not go through — "))
    }

    /**
     * PRECEDENCE 1 — missing bytes outrank everything, including a live reading.
     *
     * A transfer of a file that is not there cannot be happening, and the descriptor is now the only
     * surviving record that the photograph ever existed. It offers no retry: a Try again over an
     * absent file is a button that can only fail.
     */
    @Test
    fun missingBytesOutrankALiveReading() {
        val s = status(
            bytesPresent = false,
            backedUp = true,
            state = DwUploadState.Sending(sent = 1, total = 2, atElapsedRealtime = 0L),
        )
        assertEquals("The bytes for this attachment are no longer on this device.", s.line)
        assertEquals(DwAttachmentTone.WARNING, s.tone)
        assertFalse(s.canRetry)
        assertEquals(null, s.percent)
    }

    /**
     * PRECEDENCE 2 — a live refusal outranks a stale remote id.
     *
     * The pass that recorded the refusal is the one that just touched this file, so its reading is
     * newer than anything durable by construction. Letting `backedUp` win here would draw "backed up
     * to the server" over a file that has just been refused, which is the single most expensive
     * sentence this row can print.
     */
    @Test
    fun aLiveRefusalOutranksTheStoredId() {
        val s = status(backedUp = true, state = DwUploadState.Refused("refused", permanent = true))
        assertTrue(s.line.startsWith("Not uploaded — "))
        assertEquals(DwAttachmentTone.WARNING, s.tone)
    }

    /** A file acknowledged during this run says so at once, without waiting for a disk re-read. */
    @Test
    fun sentIsReportedWithoutTheStoredIdHavingArrivedYet() {
        val s = status(backedUp = false, state = DwUploadState.Sent)
        assertEquals("image · 2.1 MB · backed up to the server", s.line)
        assertEquals(DwAttachmentTone.NEUTRAL, s.tone)
    }

    /** The media noun is the descriptor's, lower-cased, so a video does not read as an image. */
    @Test
    fun theNounComesFromTheDescriptor() {
        assertTrue(status(mediaType = "VIDEO", sizeBytes = 287_400_000).line.startsWith("video · 287.4 MB"))
    }
}
