package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertFalse(
            "the headline fell through to the pending default:\n${banner.headline}",
            banner.headline == "Waiting to upload",
        )
        assertEquals("2 answers not accepted", banner.headline)
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
            banner.detail.contains("they will not upload by themselves"),
        )
        assertTrue(
            "and where the rest of the work is:\n${banner.detail}",
            banner.detail.contains("Everything else is saved online"),
        )
    }

    @Test
    fun `one refused answer is not called two`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 1,
        )!!
        assertEquals("1 answer not accepted", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("Across 1 workshop on this device."))
        assertTrue(banner.detail, banner.detail.contains("it will not upload by itself"))
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

    @Test
    fun `pending work and a refusal each get their own clause`() {
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 1, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 3,
        )!!
        assertEquals("1 stage · 3 answers not accepted", banner.headline)
        assertTrue("something IS waiting, so the cloud icon is honest here", banner.waiting)
        assertTrue(
            banner.detail,
            banner.detail.contains("the stages and files above upload whenever there is a connection"),
        )
        assertTrue(
            banner.detail,
            banner.detail.contains("The 3 answers that were not accepted will not"),
        )
    }

    @Test
    fun `an outright refusal and a refused answer are counted apart`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 2, refusedAnswers = 1,
        )!!
        assertEquals("2 not saved · 1 answer not accepted", banner.headline)
    }

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
