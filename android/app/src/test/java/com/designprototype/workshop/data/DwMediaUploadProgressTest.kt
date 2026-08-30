package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE PROGRESS A 300 MB VIDEO IS ALLOWED TO SHOW, AND THE THROTTLE THAT MAKES IT AFFORDABLE.
 *
 * ── WHAT WAS BROKEN, AND WHY A COUNT WAS NOT ENOUGH ──────────────────────────────────────────────
 *
 * `WorkshopRepository.uploadDesignWorkshopMedia` passed a literal `onProgress = null`, so the design
 * workshop's uploads reported nothing at all. Everything a designer could see about a transfer was
 * `WorkshopSyncStatus.summary`'s count — "1 file waiting to upload" — which does not move for the
 * tens of minutes a loom video takes on a village link and is therefore indistinguishable from an
 * app that has hung. `DwPhotoGate.GalleryCounts.uploading` says the same thing from the other side:
 * it is documented as **"ALWAYS ZERO ON THIS CLIENT AND KEPT ANYWAY"**.
 *
 * ── WHY THE 1 Hz REPUBLISH IS THE HALF THAT MATTERS ──────────────────────────────────────────────
 *
 * The obvious throttle — republish when the whole percentage changes — is worse than useless on
 * exactly the file this exists for. A 300 MB video on a 30 kB/s link spends **over a minute and a
 * half inside every single percent**, so a percent-gated readout freezes for 100 seconds at a
 * stretch and reproduces the original complaint with a number in front of it. [movingBytesRepublish]
 * is the test that would fail if someone removed the clock and kept the cheap-looking test above it.
 */
class DwMediaUploadProgressTest {

    private val id = "cmmediaprogress0000000001"

    /**
     * The store is an `object` and therefore shared with every other test in the run.
     *
     * Time is driven explicitly through `sending(..., now = )` rather than left to
     * `SystemClock.elapsedRealtime`, which answers a constant 0 under `isReturnDefaultValues` — see
     * that parameter's own KDoc for why the seam exists at all.
     */
    @Before
    fun clear() = DwMediaUploadProgress.resetForTest()

    private fun state(): DwUploadState? = DwMediaUploadProgress.states.value[id]

    private fun sending(): DwUploadState.Sending? = state() as? DwUploadState.Sending

    /** An absent entry is not a state. Nothing may read it as "on this device" or as anything else. */
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

    /**
     * THE ONE THAT PROVES THE FIX. Two readings 1.1 seconds apart, both inside the same whole
     * percent of a 300 MB file — the exact shape of a village-link video — and the store must
     * publish the second, or the row goes on saying 0% while 33 KB moves.
     */
    @Test
    fun movingBytesRepublish() {
        DwMediaUploadProgress.sending(id, sent = 1_000_000, total = 300_000_000, now = 0L)
        assertEquals(0, sending()?.percent)
        DwMediaUploadProgress.sending(id, sent = 1_033_000, total = 300_000_000, now = 1_100L)
        assertEquals("still inside percent 0, and the figure must have moved anyway", 0, sending()?.percent)
        assertEquals(1_033_000L, sending()?.sent)
    }

    /** Below the interval and inside the same percent, nothing is republished — that is the throttle. */
    @Test
    fun readingsInsideTheIntervalAreDropped() {
        DwMediaUploadProgress.sending(id, sent = 1_000_000, total = 300_000_000, now = 0L)
        DwMediaUploadProgress.sending(id, sent = 1_000_064, total = 300_000_000, now = 200L)
        assertEquals("the 64 KB callback must not reach the UI", 1_000_000L, sending()?.sent)
    }

    /** A fast link is not held to 1 Hz: a whole percent of movement publishes immediately. */
    @Test
    fun aWholePercentPublishesWithoutWaitingForTheClock() {
        DwMediaUploadProgress.sending(id, sent = 0, total = 1_000_000, now = 0L)
        DwMediaUploadProgress.sending(id, sent = 20_000, total = 1_000_000, now = 5L)
        assertEquals(2, sending()?.percent)
    }

    /**
     * The last byte always publishes, whatever the throttle says.
     *
     * `/media/complete` on a field link is not instant, so without this the final thing a designer
     * sees of a SUCCESSFUL upload is a bar stuck at 97% — the shape of a failure.
     */
    @Test
    fun theFinalByteAlwaysPublishes() {
        DwMediaUploadProgress.sending(id, sent = 990_000, total = 1_000_000, now = 0L)
        DwMediaUploadProgress.sending(id, sent = 1_000_000, total = 1_000_000, now = 1L)
        assertEquals(100, sending()?.percent)
    }

    /** An empty file divides by nothing rather than crashing the row that draws it. */
    @Test
    fun aZeroLengthFileHasNoPercentage() {
        DwMediaUploadProgress.sending(id, sent = 0, total = 0, now = 0L)
        assertEquals(0, sending()?.percent)
    }

    /** Bytes beyond the declared size clamp; a bar past 100% would be a bar that means nothing. */
    @Test
    fun percentIsClamped() {
        DwMediaUploadProgress.sending(id, sent = 5_000, total = 1_000, now = 0L)
        assertEquals(100, sending()?.percent)
    }

    /**
     * `permanent` is carried through untouched, because it is the difference between "the app will
     * try again" and "nothing happens until you tap" — see [DwUploadState.Refused].
     */
    @Test
    fun aRefusalKeepsItsPermanence() {
        DwMediaUploadProgress.refused(id, "the file store refused it with HTTP 413.", permanent = true)
        assertEquals(true, (state() as? DwUploadState.Refused)?.permanent)
        DwMediaUploadProgress.refused(id, "the file store answered HTTP 503.", permanent = false)
        assertEquals(false, (state() as? DwUploadState.Refused)?.permanent)
    }

    /** A refusal replaces a live reading rather than being queued behind its throttle. */
    @Test
    fun aRefusalDisplacesProgressImmediately() {
        DwMediaUploadProgress.sending(id, sent = 500, total = 1_000, now = 0L)
        DwMediaUploadProgress.refused(id, "refused", permanent = true)
        assertTrue(state() is DwUploadState.Refused)
    }

    /**
     * Forgetting REMOVES the entry rather than blanking it, so the row falls back to the durable
     * `DraftMedia.remoteMediaId` it read before any of this existed. An emptied state would instead
     * assert something about a file nobody has touched.
     */
    @Test
    fun forgettingRemovesTheEntryEntirely() {
        DwMediaUploadProgress.sending(id, sent = 500, total = 1_000, now = 0L)
        DwMediaUploadProgress.forget(id)
        assertNull(state())
    }

    /** A finished upload says so at once, rather than waiting for a screen to re-read the draft. */
    @Test
    fun sentIsPublishedDirectly() {
        DwMediaUploadProgress.sending(id, sent = 500, total = 1_000, now = 0L)
        DwMediaUploadProgress.sent(id)
        assertEquals(DwUploadState.Sent, state())
    }

    /**
     * A restarted attempt re-publishes from zero without being throttled against the readings of the
     * attempt that was thrown away.
     *
     * `putToStorage` re-sends from byte zero on a retry and reports `(0, total)` when it does. If
     * that zero were dropped as "inside the interval", the row would sit at the abandoned attempt's
     * high-water mark — a bar reading 80% over a transfer that is back at the beginning.
     */
    @Test
    fun aRestartedAttemptIsNotThrottledAgainstTheOldOne() {
        DwMediaUploadProgress.sending(id, sent = 800_000, total = 1_000_000, now = 0L)
        // Exactly what `putToStorage` emits between attempts — no `forget`, and only 10 ms later, so
        // the clock alone would not have let it through.
        DwMediaUploadProgress.sending(id, sent = 0, total = 1_000_000, now = 10L)
        assertEquals(0, sending()?.percent)
        assertEquals(0L, sending()?.sent)
    }
}
