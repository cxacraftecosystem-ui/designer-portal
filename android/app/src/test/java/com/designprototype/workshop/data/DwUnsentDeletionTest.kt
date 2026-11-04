package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwUnsentDeletionTest {

    private fun status(
        pendingStages: Int = 0,
        pendingMedia: Int = 0,
        failedStages: Int = 0,
        failedMedia: Int = 0,
        refusedAnswers: Int = 0,
        unsentDeletions: Int = 0,
    ) = WorkshopSyncStatus(
        workshopId = "w1",
        remoteId = "srv-1",
        pendingStages = pendingStages,
        pendingMedia = pendingMedia,
        pendingMediaBytes = 0,
        failedStages = failedStages,
        failedMedia = failedMedia,
        problems = emptyList(),
        lastSuccessAt = null,
        lastError = null,
        releasableMedia = 0,
        releasableBytes = 0,
        refusedAnswers = refusedAnswers,
        unsentDeletions = unsentDeletions,
    )

    @Test
    fun `a workshop holding a deletion it cannot send is NOT backed up`() {
        assertFalse(status(unsentDeletions = 1).isFullySynced)
        assertTrue("and everything else really is clean, which is why nothing else caught it",
            status().isFullySynced)
    }

    @Test
    fun `a deletion is not a refused stage`() {
        assertFalse(status(unsentDeletions = 2).hasFailures)
    }

    @Test
    fun `the row says what is outstanding rather than falling through to a blank sentence`() {
        val summary = status(unsentDeletions = 1).summary
        assertEquals("1 stage with a deletion not sent — the rest is saved online", summary)
        assertFalse("nothing here is waiting for a connection to carry it", summary.contains("waiting to upload"))
    }

    @Test
    fun `two stages holding deletions are not called one`() {
        assertEquals(
            "2 stages with a deletion not sent — the rest is saved online",
            status(unsentDeletions = 2).summary,
        )
    }

    @Test
    fun `a refusal and a deletion each get their own clause in the row`() {
        assertEquals(
            "2 answers not accepted, 1 stage with a deletion not sent — the rest is saved online",
            status(refusedAnswers = 2, unsentDeletions = 1).summary,
        )
    }

    @Test
    fun `pending work still says it is waiting to upload`() {
        assertEquals(
            "1 stage waiting to upload",
            status(pendingStages = 1, unsentDeletions = 1).summary,
        )
    }

    @Test
    fun `a deletion is never described as waiting for a connection`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 1,
        )!!
        assertFalse(
            "the headline fell through to the pending default:\n${banner.headline}",
            banner.headline == "Waiting to upload",
        )
        assertEquals("1 stage with a deletion not sent", banner.headline)
        assertFalse("a sync will not move it, so it must not wear the cloud-off icon", banner.waiting)
        assertFalse(
            "the sentence that sends a designer looking for signal:\n${banner.detail}",
            banner.detail.contains("it uploads whenever there is a connection"),
        )
        assertTrue(
            "it has to say a sync cannot do this:\n${banner.detail}",
            banner.detail.contains("syncing alone will not send it"),
        )
        assertTrue(
            "and name the one action that can — opening the stage, which only the stage screen does",
            banner.detail.contains("1 stage has a deletion that has not been sent"),
        )
        assertTrue(
            banner.detail,
            banner.detail.contains("Open the workshop, then the stage, with a connection"),
        )
        assertTrue("and where the rest of the work is", banner.detail.contains("Everything else is saved online"))
    }

    @Test
    fun `two stages holding deletions are not described as one`() {
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 2,
        )!!
        assertEquals("2 stages with a deletion not sent", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("have deletions that have not been sent"))
        assertTrue(banner.detail, banner.detail.contains("syncing alone will not send them"))
        assertTrue(banner.detail, banner.detail.contains("then those stages, with a connection"))
    }

    @Test
    fun `pending work and a deletion each get their own clause in the banner`() {
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 1, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 1,
        )!!
        assertEquals("1 stage · 1 stage with a deletion not sent", banner.headline)
        assertTrue("something IS waiting, so the cloud icon is honest here", banner.waiting)
        assertTrue(
            banner.detail,
            banner.detail.contains("the stages and files above upload whenever there is a connection"),
        )
        assertTrue(banner.detail, banner.detail.contains("syncing alone will not send it"))
    }

    @Test
    fun `a refusal and a deletion are both explained, with their different remedies`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 2, unsentDeletions = 1,
        )!!
        assertEquals("2 answers not accepted · 1 stage with a deletion not sent", banner.headline)
        assertFalse(banner.waiting)
        assertTrue(
            "the refusal's remedy",
            banner.detail.contains("to see which and correct them"),
        )
        assertTrue(
            "and the deletion's, which is a different one",
            banner.detail.contains("Open the workshop, then the stage, with a connection"),
        )
    }

    @Test
    fun `a device with no deletions is unchanged`() {
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 3, files = 4, bytesText = "1.2 MB",
            failures = 0, refusedAnswers = 0,
        )!!
        assertEquals("3 stages · 4 files, 1.2 MB", banner.headline)
        assertTrue(banner.waiting)
        assertTrue(banner.detail, banner.detail.contains("it uploads whenever there is a connection"))
        assertFalse(banner.detail, banner.detail.contains("deletion"))
    }
}
