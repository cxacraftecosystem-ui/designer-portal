package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DwMediaUploadProgressTest {

    private val id = "cmmediaprogress0000000001"

    @Before
    fun clear() = DwMediaUploadProgress.resetForTest()

    private fun state(): DwUploadState? = DwMediaUploadProgress.states.value[id]

    private fun sending(): DwUploadState.Sending? = state() as? DwUploadState.Sending

    @Test
    fun nothingIsKnownUntilSomethingReports() {
        assertNull(state())
    }

    @Test
    fun theFirstReadingAlwaysPublishes() {
        DwMediaUploadProgress.sending(id, sent = 1_000, total = 300_000_000, now = 0L)
        assertEquals(1_000L, sending()?.sent)
        assertEquals(300_000_000L, sending()?.total)
    }

    @Test
    fun movingBytesRepublish() {
        DwMediaUploadProgress.sending(id, sent = 1_000_000, total = 300_000_000, now = 0L)
        assertEquals(0, sending()?.percent)
        DwMediaUploadProgress.sending(id, sent = 1_033_000, total = 300_000_000, now = 1_100L)
        assertEquals("still inside percent 0, and the figure must have moved anyway", 0, sending()?.percent)
        assertEquals(1_033_000L, sending()?.sent)
    }

    @Test
    fun readingsInsideTheIntervalAreDropped() {
        DwMediaUploadProgress.sending(id, sent = 1_000_000, total = 300_000_000, now = 0L)
        DwMediaUploadProgress.sending(id, sent = 1_000_064, total = 300_000_000, now = 200L)
        assertEquals("the 64 KB callback must not reach the UI", 1_000_000L, sending()?.sent)
    }

    @Test
    fun aWholePercentPublishesWithoutWaitingForTheClock() {
        DwMediaUploadProgress.sending(id, sent = 0, total = 1_000_000, now = 0L)
        DwMediaUploadProgress.sending(id, sent = 20_000, total = 1_000_000, now = 5L)
        assertEquals(2, sending()?.percent)
    }

    @Test
    fun theFinalByteAlwaysPublishes() {
        DwMediaUploadProgress.sending(id, sent = 990_000, total = 1_000_000, now = 0L)
        DwMediaUploadProgress.sending(id, sent = 1_000_000, total = 1_000_000, now = 1L)
        assertEquals(100, sending()?.percent)
    }

    @Test
    fun aZeroLengthFileHasNoPercentage() {
        DwMediaUploadProgress.sending(id, sent = 0, total = 0, now = 0L)
        assertEquals(0, sending()?.percent)
    }

    @Test
    fun percentIsClamped() {
        DwMediaUploadProgress.sending(id, sent = 5_000, total = 1_000, now = 0L)
        assertEquals(100, sending()?.percent)
    }

    @Test
    fun aRefusalKeepsItsPermanence() {
        DwMediaUploadProgress.refused(id, "the file store refused it with HTTP 413.", permanent = true)
        assertEquals(true, (state() as? DwUploadState.Refused)?.permanent)
        DwMediaUploadProgress.refused(id, "the file store answered HTTP 503.", permanent = false)
        assertEquals(false, (state() as? DwUploadState.Refused)?.permanent)
    }

    @Test
    fun aRefusalDisplacesProgressImmediately() {
        DwMediaUploadProgress.sending(id, sent = 500, total = 1_000, now = 0L)
        DwMediaUploadProgress.refused(id, "refused", permanent = true)
        assertTrue(state() is DwUploadState.Refused)
    }

    @Test
    fun forgettingRemovesTheEntryEntirely() {
        DwMediaUploadProgress.sending(id, sent = 500, total = 1_000, now = 0L)
        DwMediaUploadProgress.forget(id)
        assertNull(state())
    }

    @Test
    fun sentIsPublishedDirectly() {
        DwMediaUploadProgress.sending(id, sent = 500, total = 1_000, now = 0L)
        DwMediaUploadProgress.sent(id)
        assertEquals(DwUploadState.Sent, state())
    }

    @Test
    fun aRestartedAttemptIsNotThrottledAgainstTheOldOne() {
        DwMediaUploadProgress.sending(id, sent = 800_000, total = 1_000_000, now = 0L)
        DwMediaUploadProgress.sending(id, sent = 0, total = 1_000_000, now = 10L)
        assertEquals(0, sending()?.percent)
        assertEquals(0L, sending()?.sent)
    }
}
