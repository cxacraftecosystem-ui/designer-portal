package com.designprototype.workshop.ui

import com.designprototype.workshop.data.designWorkshopCardPurposeMessage
import com.designprototype.workshop.data.designWorkshopJoinAskingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwWorkshopJoinTest {

    private fun scan(
        workshopId: String = "cmsik2jg8000eh8xc1lcy661a",
        code: String = "DPW1:G:CMSIK2JG8000EH8XC1LCY661A:NEWD",
        deviceUtc: String = "2026-08-22T09:00:00Z",
        elapsed: Long = 1_000_000L,
        boot: Long = 1_700_000_000_000L,
        attempts: Int = 0,
    ) = DwPendingInduction(
        workshopId = workshopId,
        code = code,
        scannedAtDeviceUtc = deviceUtc,
        scannedAtElapsedMs = elapsed,
        bootWallClockMs = boot,
        attempts = attempts,
    )

    @Test
    fun `a re-scan keeps the first scan time and takes the newer code`() {
        val first = scan(elapsed = 1_000L, deviceUtc = "2026-08-22T09:00:00Z")
        val again = scan(
            elapsed = 601_000L,
            deviceUtc = "2026-08-22T09:10:00Z",
            code = "DPW1:G:CMSIK2JG8000EH8XC1LCY661A:XXXX",
        )

        val merged = dwMergeInduction(listOf(first), again)

        assertEquals("one row per workshop, always", 1, merged.size)
        assertEquals("the FIRST scan's time survives", 1_000L, merged.single().scannedAtElapsedMs)
        assertEquals("the first scan's wall-clock claim survives too", "2026-08-22T09:00:00Z", merged.single().scannedAtDeviceUtc)
        assertEquals("the card actually presented is the newer one", again.code, merged.single().code)
    }

    @Test
    fun `a re-scan does not reset the attempt count`() {
        val tried = scan(attempts = 4)
        val merged = dwMergeInduction(listOf(tried), scan(code = "DPW1:G:OTHER:AAAA"))
        assertEquals(4, merged.single().attempts)
    }

    @Test
    fun `a scan for a different workshop is a second row`() {
        val one = scan(workshopId = "cmsik2jg8000eh8xc1lcy661a")
        val two = scan(workshopId = "cmsvfnb4y0001qq1bzd2g48lq")
        val merged = dwMergeInduction(listOf(one), two)
        assertEquals(2, merged.size)
        assertEquals(listOf(one.workshopId, two.workshopId), merged.map { it.workshopId })
    }

    @Test
    fun `the first scan lands at the end of an empty queue`() {
        val merged = dwMergeInduction(emptyList(), scan())
        assertEquals(1, merged.size)
        assertEquals(scan(), merged.single())
    }

    @Test
    fun `the note gives the uptime measure and labels the device clock as a claim`() {
        val pending = scan(elapsed = 1_000L, boot = 1_700_000_000_000L, deviceUtc = "2026-08-22T09:00:00Z")
        val elapsedNow = 1_000L + 2L * 24 * 60 * 60 * 1000
        val note = dwInductionNote(pending, elapsedNow, 1_700_000_000_000L + elapsedNow)

        assertTrue("it must say the scan was offline", note.contains("no connection"))
        assertTrue("it must name the clock-proof measure", note.contains("cannot be set by hand"))
        assertTrue("it must give the span", note.contains("about 2 days"))
        assertTrue("the device's time is present", note.contains("2026-08-22T09:00:00Z"))
        assertTrue(
            "and it is labelled as the handset's claim",
            note.contains("what the handset claims"),
        )
        assertTrue(
            "and the ordering rule is stated so nobody infers a different one",
            note.contains("Whichever scan was uploaded first counts."),
        )
    }

    @Test
    fun `a reboot or a changed clock is admitted rather than papered over`() {
        val pending = scan(elapsed = 900_000L, boot = 1_700_000_000_000L)
        val note = dwInductionNote(pending, elapsedNowMs = 1_000L, wallNowMs = 1_600_000_000_000L)

        assertTrue(note.contains("restarted or had its clock changed"))
        assertFalse("no span may be claimed", note.contains("about "))
        assertTrue("the device's claim is still shown, still labelled", note.contains("what the handset claims"))
    }

    @Test
    fun `ordinary clock drift does not read as a clock change`() {
        val pending = scan(elapsed = 1_000L, boot = 1_700_000_000_000L)
        val elapsedNow = 1_000L + 5L * 60 * 1000
        val note = dwInductionNote(pending, elapsedNow, 1_700_000_000_000L + elapsedNow + 400L)
        assertFalse(note.contains("restarted or had its clock changed"))
        assertTrue(note.contains("about 5 minutes"))
    }

    @Test
    fun `no note ever claims to have been first`() {
        val notes = listOf(
            dwInductionNote(scan(elapsed = 0L), 60_000L, 1_700_000_060_000L),
            dwInductionNote(scan(elapsed = 900_000L, boot = 1L), 1_000L, 2_000L),
        )
        notes.forEach { note ->
            val lower = note.lowercase()
            assertFalse(lower.contains("first to scan"))
            assertFalse(lower.contains("i was first"))
            assertFalse(lower.contains("scanned first"))
            assertFalse(lower.contains("earlier than"))
        }
    }

    @Test
    fun `a span is described coarsely and never more precisely than it is known`() {
        assertEquals("less than a minute", dwSpanInWords(0L))
        assertEquals("less than a minute", dwSpanInWords(59_000L))
        assertEquals("about 1 minute", dwSpanInWords(60_000L))
        assertEquals("about 5 minutes", dwSpanInWords(5L * 60_000L))
        assertEquals("about 1 hour", dwSpanInWords(60L * 60_000L))
        assertEquals("about 3 hours", dwSpanInWords(3L * 60L * 60_000L))
        assertEquals("about 2 days", dwSpanInWords(48L * 60L * 60_000L))
        assertEquals("about 13 days", dwSpanInWords(13L * 24L * 60L * 60_000L))
        assertEquals("an unknown time", dwSpanInWords(-1L))
    }

    @Test
    fun `the queued sentence promises nothing and confirms the scan is kept`() {
        val message = dwJoinQueuedMessage()
        val lower = message.lowercase()

        assertFalse(lower.contains("request sent"))
        assertFalse(lower.contains("we have asked"))
        assertFalse("nobody has been asked yet", lower.contains("an administrator can now see"))
        assertTrue("it must say the scan is kept", lower.contains("kept on this device"))
        assertTrue("it must say the time was kept too", lower.contains("the time you scanned it"))
        assertTrue("it must say nothing has gone out", lower.contains("nothing is sent yet"))
        assertTrue("it must say they need not scan again", lower.contains("do not need to scan again"))
    }

    @Test
    fun `the asking sentence claims neither existence nor an opening`() {
        val message = designWorkshopJoinAskingMessage()
        val lower = message.lowercase()

        assertTrue(lower.contains("design workshop"))
        assertFalse("nothing may claim the workshop is there", lower.contains("the workshop is there"))
        assertFalse("it does not open anything", lower.contains("opening"))
        assertFalse(lower.contains("request sent"))
        assertTrue("it must read as in progress", message.endsWith("…"))
    }

    @Test
    fun `the workshop card says it asks rather than admits, and is not a password`() {
        val message = designWorkshopCardPurposeMessage()
        val lower = message.lowercase()

        assertTrue("it must say scanning asks", lower.contains("asks an administrator"))
        assertTrue("it must say it does not admit by itself", lower.contains("does not let them in by itself"))
        assertTrue("it must deny being a password", lower.contains("not a password"))
        assertFalse(lower.contains(" key"))
        assertFalse(lower.contains("secret"))
        assertFalse(lower.contains("grants access"))
    }

    @Test
    fun `the camera-unavailable sentences are distinct, never dead ends, and never offer settings`() {
        val missing = dwQrCameraUnavailable(rearLensMissing = true)
        val busy = dwQrCameraUnavailable(rearLensMissing = false)

        assertNotEquals("two situations, two sentences", missing, busy)
        assertTrue("a missing lens is a fact about the device", missing.contains("no rear camera"))
        assertTrue("a busy camera names the likely cause", busy.contains("another app"))

        listOf(missing, busy).forEach { message ->
            assertTrue(
                "must name the camera-free routes: $message",
                message.contains(DwCameraUse.QR_CODE.alternatives),
            )
            assertFalse(
                "must not send anybody to permission settings: $message",
                message.contains(DW_CAMERA_SETTINGS_BUTTON),
            )
            assertFalse(message.lowercase().contains("permission"))
        }
    }

    @Test
    fun `the live scanner's sentences name buttons and not positions`() {
        assertTrue(DW_QR_LIVE_AIMING.contains("inside the box"))
        assertFalse("aiming copy must not explain the machinery", DW_QR_LIVE_AIMING.lowercase().contains("camerax"))

        assertTrue("it names the photograph button by label", DW_QR_LIVE_STILL_TRYING.contains("“Scan a code”"))
        assertTrue("and the picture button by label", DW_QR_LIVE_STILL_TRYING.contains("“Use a picture”"))
        assertTrue("and says why a photograph is different", DW_QR_LIVE_STILL_TRYING.contains("full resolution"))
        listOf("below", "above", "on the left", "on the right").forEach { position ->
            assertFalse(
                "must not point at a position: $position",
                DW_QR_LIVE_STILL_TRYING.lowercase().contains(position),
            )
        }

        assertNotEquals(DW_QR_LIVE_STALLED, DW_QR_LIVE_STILL_TRYING)
        assertTrue(DW_QR_LIVE_STALLED.contains("stopped sending pictures"))
        assertTrue("and it names the way out", DW_QR_LIVE_STALLED.contains("open it again"))

        assertTrue(DW_QR_LIVE_FRONT_LENS.contains("no rear camera"))
        assertTrue(DW_QR_LIVE_FRONT_LENS.contains("facing the screen"))
    }
}
