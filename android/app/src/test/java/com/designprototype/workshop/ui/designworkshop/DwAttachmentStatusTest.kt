package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwUploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAttachmentStatusTest {

    private fun status(
        backedUp: Boolean = false,
        bytesPresent: Boolean = true,
        state: DwUploadState? = null,
        sizeBytes: Long = 2_100_000,
        mediaType: String = "IMAGE",
    ) = dwAttachmentStatus(mediaType, sizeBytes, bytesPresent, backedUp, state)

    @Test
    fun aFreshCaptureSaysThePhoneIsTheOnlyCopy() {
        val s = status()
        assertEquals("image · 2.1 MB · on this device only", s.line)
        assertEquals(DwAttachmentTone.NEUTRAL, s.tone)
        assertFalse(s.canRetry)
        assertEquals(null, s.percent)
    }

    @Test
    fun anAcknowledgedFileSaysSo() {
        assertEquals("image · 2.1 MB · saved online", status(backedUp = true).line)
        assertEquals(DwAttachmentTone.NEUTRAL, status(backedUp = true).tone)
    }

    @Test
    fun aTransferInFlightShowsMovingBytes() {
        val s = status(state = DwUploadState.Sending(sent = 4_200_000, total = 11_200_000, atElapsedRealtime = 0L))
        assertEquals("Uploading… 37% · 4.2 MB of 11.2 MB", s.line)
        assertEquals(DwAttachmentTone.PROGRESS, s.tone)
        assertEquals(37, s.percent)
        assertFalse(s.canRetry)
    }

    @Test
    fun onlyAParkedFileOffersARetry() {
        val parked = status(state = DwUploadState.Refused("the file store refused it with HTTP 413.", permanent = true))
        assertTrue(parked.canRetry)
        assertEquals(DwAttachmentTone.WARNING, parked.tone)
        assertTrue(parked.line.startsWith("Not uploaded — "))
        assertTrue("the reason has to survive into the line", parked.line.contains("HTTP 413"))
    }

    @Test
    fun aMomentaryRefusalSaysTheAppWillRetry() {
        val blip = status(state = DwUploadState.Refused("the file store answered HTTP 503.", permanent = false))
        assertFalse(blip.canRetry)
        assertEquals(DwAttachmentTone.WARNING, blip.tone)
        assertTrue(blip.line.startsWith("Upload did not go through — "))
    }

    @Test
    fun missingBytesOutrankALiveReading() {
        val s = status(
            bytesPresent = false,
            backedUp = true,
            state = DwUploadState.Sending(sent = 1, total = 2, atElapsedRealtime = 0L),
        )
        assertEquals("This file is no longer on this device.", s.line)
        assertEquals(DwAttachmentTone.WARNING, s.tone)
        assertFalse(s.canRetry)
        assertEquals(null, s.percent)
    }

    @Test
    fun aLiveRefusalOutranksTheStoredId() {
        val s = status(backedUp = true, state = DwUploadState.Refused("refused", permanent = true))
        assertTrue(s.line.startsWith("Not uploaded — "))
        assertEquals(DwAttachmentTone.WARNING, s.tone)
    }

    @Test
    fun sentIsReportedWithoutTheStoredIdHavingArrivedYet() {
        val s = status(backedUp = false, state = DwUploadState.Sent)
        assertEquals("image · 2.1 MB · saved online", s.line)
        assertEquals(DwAttachmentTone.NEUTRAL, s.tone)
    }

    @Test
    fun theNounComesFromTheDescriptor() {
        assertTrue(status(mediaType = "VIDEO", sizeBytes = 287_400_000).line.startsWith("video · 287.4 MB"))
    }
}
