package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A DELETION THAT CANNOT TRAVEL WAS REPORTED AS BACKED UP.
 *
 * ── THE DEFECT, IN THE THREE PLACES IT LIVED ─────────────────────────────────────────────────────
 *
 * Deleting the LAST row of a collection is invisible in a stage payload — entries are built from
 * `rowsFor(key)`, which is simply empty — so the deletion has to be STATED, in
 * [StageDraft.emptiedEntities]. `buildStageBody` sends that list only when the draft is authoritative,
 * which is right: asserting a deletion while disclaiming the knowledge that justifies one is how a
 * fortnight of the office's rows gets swept by a phone that never read them.
 *
 * Nothing then counted the deletions that were consequently NOT SENT. `statusOf` never read
 * `emptiedEntities`, and [WorkshopSyncStatus.isFullySynced] had no term for it — so the payload's
 * signature matched, no stage was pending, and a workshop holding a deletion that PHYSICALLY CANNOT
 * TRAVEL scored fully synced with the list row reading "Backed up to the server". The designer deleted
 * six process steps in a courtyard, was told their work was safe, and the six rows are still in the
 * .docx handed to the ministry.
 *
 * ── AND THE SAME SHAPE AS THE REFUSAL DEFECT ONE FIELD ALONG ─────────────────────────────────────
 *
 * [WorkshopSyncStatus.refusedAnswers] was added for exactly this: work this device holds that no future
 * payload will carry, so the signature matches for ever and nothing is ever pending. These pin the
 * deletion's half of it, including the two surfaces that told the refusal's story wrongly before
 * anybody thought to check them — the row's own sentence, and the device-wide banner drawn above it.
 */
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

    // ── The row's own answer ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a workshop holding a deletion it cannot send is NOT backed up`() {
        // THE ASSERTION THE DEFECT FAILED, and the one line that made "Backed up to the server" a lie.
        assertFalse(status(unsentDeletions = 1).isFullySynced)
        assertTrue("and everything else really is clean, which is why nothing else caught it",
            status().isFullySynced)
    }

    /**
     * IT IS NOT A FAILURE EITHER, and must not be counted as one. A failed stage did not save; this
     * stage saved everything it had. Folding the two together would send a designer back over a stage
     * that is fine.
     */
    @Test
    fun `a deletion is not a refused stage`() {
        assertFalse(status(unsentDeletions = 2).hasFailures)
    }

    /**
     * WITH NOTHING PENDING IT USED TO PRODUCE A SENTENCE WITH NO SUBJECT. Falling through to the
     * "waiting to upload" arm, every clause of its `buildList` was false, so the joined string was
     * EMPTY and the row read " waiting to upload" — a blank sentence over a workshop that is not backed
     * up, in the one place a designer looks to decide whether they can leave a cluster.
     */
    @Test
    fun `the row says what is outstanding rather than falling through to a blank sentence`() {
        val summary = status(unsentDeletions = 1).summary
        assertEquals("1 stage with a deletion not sent — the rest is backed up", summary)
        assertFalse("nothing here is waiting for a connection to carry it", summary.contains("waiting to upload"))
    }

    @Test
    fun `two stages holding deletions are not called one`() {
        assertEquals(
            "2 stages with a deletion not sent — the rest is backed up",
            status(unsentDeletions = 2).summary,
        )
    }

    /**
     * BOTH KINDS OF STUCK WORK GET NAMED. The refusal arm ran first and said nothing about the
     * deletion, so a workshop holding one of each reported only the refusal — and a designer who
     * corrected the answer would have watched the row go on refusing to say "backed up" with nothing
     * left on screen explaining why.
     */
    @Test
    fun `a refusal and a deletion each get their own clause in the row`() {
        assertEquals(
            "2 answers refused, 1 stage with a deletion not sent — the rest is backed up",
            status(refusedAnswers = 2, unsentDeletions = 1).summary,
        )
    }

    /** Pending work still dominates the line, because it is the thing a connection will actually fix. */
    @Test
    fun `pending work still says it is waiting to upload`() {
        assertEquals(
            "1 stage waiting to upload",
            status(pendingStages = 1, unsentDeletions = 1).summary,
        )
    }

    // ── The banner above the list ─────────────────────────────────────────────────────────────────

    /**
     * THE BANNER MUST NOT SAY "IT UPLOADS WHENEVER THERE IS A CONNECTION" OVER THIS.
     *
     * Making `unsentDeletions` a term of `isFullySynced` is what puts such a workshop into the caller's
     * `outstanding` list — and with no counter handed down, the banner would have drawn a workshop it
     * could not name and fallen through to its pending default. That is word-for-word the defect
     * [dwDeviceSyncBanner] was written to end, arriving one field along, and the comment on its call
     * site in `WorkshopListScreen` predicted it.
     *
     * A DELETION IS ON THE "NOT WAITING" SIDE OF THE LINE even though its remedy needs signal, because
     * of what the cloud-off icon claims: that the pass carries it away by itself on the next bar.
     * `pushStages` only ever PUSHES, so no background pass will ever read a stage, and the deletion sits
     * there through a hundred passes on perfect Wi-Fi until a person opens the stage.
     */
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
            banner.detail.contains("a sync will NOT move it"),
        )
        assertTrue(
            "and name the one action that can — a READ, which only the stage screen makes",
            banner.detail.contains("has to READ that stage once"),
        )
        assertTrue(
            banner.detail,
            banner.detail.contains("Open the workshop, then the stage, with a connection"),
        )
        assertTrue("and where the rest of the work is", banner.detail.contains("Everything else is on the server"))
    }

    @Test
    fun `two stages holding deletions are not described as one`() {
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 2,
        )!!
        assertEquals("2 stages with a deletion not sent", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("hold row deletions"))
        assertTrue(banner.detail, banner.detail.contains("a sync will NOT move them"))
        assertTrue(banner.detail, banner.detail.contains("has to READ those stages once"))
    }

    /**
     * BOTH AT ONCE CANNOT BE TOLD IN ONE SENTENCE — the same argument the refusal case makes. The
     * stages and files WILL upload on the next bar of signal; the deletion will not, ever, until
     * somebody opens the stage.
     */
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
        assertTrue(banner.detail, banner.detail.contains("a sync will NOT move it"))
    }

    /**
     * A REFUSAL AND A DELETION HAVE DIFFERENT REMEDIES — correct an answer, versus open a stage — so
     * neither sentence can stand in for the other, and the refusal arm running first must not swallow
     * the deletion.
     */
    @Test
    fun `a refusal and a deletion are both explained, with their different remedies`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 2, unsentDeletions = 1,
        )!!
        assertEquals("2 answers refused · 1 stage with a deletion not sent", banner.headline)
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

    /**
     * The existing callers' sentences are untouched: the parameter is defaulted, so a device with no
     * deletions anywhere says exactly what it said before.
     */
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
