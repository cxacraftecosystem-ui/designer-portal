package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BANNER AND THE ROW BENEATH IT, TOLD ONE STORY EACH, ABOUT THE SAME PHONE.
 *
 * [WorkshopSyncStatus.summary] already refuses to route a refused answer through its "waiting to
 * upload" arm, and says why on that branch: *nothing is waiting, the save has already happened and
 * the repository has already declined it.* The device-wide banner drawn directly above the same list
 * did the opposite, because its caller counted `failedStages + failedMedia` and never
 * [WorkshopSyncStatus.refusedAnswers].
 *
 * Measured on SM-M325F / Android 13 against the live API, with a stage whose only outstanding item
 * was two refused MONEY answers and the phone on Wi-Fi:
 *
 *   banner  "Waiting to upload"  ·  "Across 1 workshop(s) on this device. Everything is saved here
 *            and editable offline; it uploads whenever there is a connection."   [cloud-off icon]
 *   row     "2 answers refused — the rest is backed up"                          [error icon]
 *
 * These pin the three sentences the banner may now say and the one it may not: a refusal is never
 * described as waiting for a connection, and the cloud-off icon — which is a claim ABOUT THE NETWORK
 * — is not drawn over one.
 */
class DwDeviceSyncBannerTest {

    @Test
    fun `nothing outstanding draws no banner at all`() {
        assertNull(
            dwDeviceSyncBanner(
                workshops = 0, stages = 0, files = 0, bytesText = "0 B",
                failures = 0, refusedAnswers = 0,
            )
        )
    }

    @Test
    fun `a refusal is never described as waiting for a connection`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 2,
        )!!
        // The exact headline the handset drew before this existed.
        assertFalse(
            "the headline fell through to the pending default:\n${banner.headline}",
            banner.headline == "Waiting to upload",
        )
        assertEquals("2 answers refused", banner.headline)
        assertFalse(
            "a refusal is not a signal problem and must not wear the cloud-off icon",
            banner.waiting,
        )
        assertFalse(
            "the sentence that sent a designer looking for signal:\n${banner.detail}",
            banner.detail.contains("uploads whenever there is a connection"),
        )
        assertTrue(
            "it has to say the refusal will not fix itself:\n${banner.detail}",
            banner.detail.contains("Those answers will NOT upload by themselves"),
        )
        assertTrue(
            "and where the rest of the work is:\n${banner.detail}",
            banner.detail.contains("Everything else is on the server"),
        )
    }

    @Test
    fun `one refused answer is not called two`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 1,
        )!!
        assertEquals("1 answer refused", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("Across 1 workshop on this device."))
        assertTrue(banner.detail, banner.detail.contains("That answer will NOT upload by itself"))
        assertTrue(banner.detail, banner.detail.contains("correct it."))
        assertFalse(banner.waiting)
    }

    @Test
    fun `pending work still says it uploads when there is a connection`() {
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 3, files = 4, bytesText = "1.2 MB",
            failures = 0, refusedAnswers = 0,
        )!!
        assertEquals("3 stages · 4 files, 1.2 MB", banner.headline)
        assertTrue(banner.waiting)
        assertTrue(banner.detail, banner.detail.contains("uploads whenever there is a connection"))
    }

    /**
     * BOTH AT ONCE IS THE CASE THAT CANNOT BE TOLD IN ONE SENTENCE. The stages and files WILL upload
     * on the next bar of signal; the refused answers will not, ever, until somebody corrects them.
     * A banner that says only the first over both is the original defect with a truer headline.
     */
    @Test
    fun `pending work and a refusal each get their own clause`() {
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 1, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 3,
        )!!
        assertEquals("1 stage · 3 answers refused", banner.headline)
        assertTrue("something IS waiting, so the cloud icon is honest here", banner.waiting)
        assertTrue(
            banner.detail,
            banner.detail.contains("the stages and files above upload whenever there is a connection"),
        )
        assertTrue(
            banner.detail,
            banner.detail.contains("The 3 refused answers will not"),
        )
    }

    /**
     * A stage or file the server refused OUTRIGHT is a different fact from an answer refused inside a
     * save that otherwise succeeded — [WorkshopSyncStatus] keeps them apart for the reason written on
     * `refusedAnswers`, and the banner must not fold them into one number either.
     */
    @Test
    fun `an outright refusal and a refused answer are counted apart`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 2, refusedAnswers = 1,
        )!!
        assertEquals("2 refused outright · 1 answer refused", banner.headline)
    }

    /**
     * The one thing that may still reach the old default: a workshop that is outstanding for a reason
     * none of the four counters names — it has no server record at all. That genuinely is waiting for
     * a connection, so the sentence is true of it and only of it.
     */
    @Test
    fun `a workshop that exists only here is still waiting to upload`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0,
        )!!
        assertEquals("Waiting to upload", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("uploads whenever there is a connection"))
        assertTrue("this one really is waiting for signal, so the cloud icon is right", banner.waiting)
    }
}
