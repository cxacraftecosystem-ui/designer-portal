package com.designprototype.workshop.ui

import com.designprototype.workshop.data.designWorkshopCardPurposeMessage
import com.designprototype.workshop.data.designWorkshopJoinAskingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE JOIN PATH'S RULES AND ITS WORDING, asserted on a machine with no handset and no server.
 *
 * ── WHY THE WORDING IS THE PART WORTH A TEST ──────────────────────────────────────────────────
 *
 * `DwCameraRefusalTest`'s header states the principle this file follows: "That is a claim about
 * wording, and wording claims are the ones a repository with no handset can actually make." Here they
 * are also the claims that do the most damage when they go wrong. A designer told a request has been
 * sent will WAIT; a designer told nothing has been sent will walk over and ask. An administrator told
 * a scan happened at 09:00 will hand a single-use place to whoever's phone said the earliest time —
 * which is precisely the spoof the requirement rules out, arriving through a sentence rather than
 * through code.
 *
 * ── AND THE ONE RULE THAT IS NOT ABOUT WORDING ────────────────────────────────────────────────
 *
 * [dwMergeInduction] decides whose scan time survives a re-scan, which is a decision about somebody's
 * place in a queue. It is pure and it is pinned here.
 *
 * ── WHAT IS NOT ASSERTED ANYWHERE, STATED SO NOBODY ASSUMES IT IS ─────────────────────────────
 *
 * Nothing here touches the network, the disk queue or the 202 itself. There is no server on this
 * machine, so "the route accepts this body" is a claim for `backend/tests`, and "a scan survives the
 * process being killed" is a claim for a device. What is verified is every decision the handset makes
 * on its own.
 */
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

    // ── The queue's one rule ────────────────────────────────────────────────────────────────────

    /**
     * A SECOND SCAN OF THE SAME CARD DOES NOT MOVE THE SCAN TIME, AND THAT IS SOMEBODY'S PLACE.
     *
     * The server applies the same asymmetry to its own row for the same reason — "a replay is a no-op
     * and does not move createdAt" — and the local consequence is sharper: a designer who scans again
     * after ten minutes because nothing seemed to happen has not just arrived, and letting the second
     * scan reset the recorded time would quietly hand their place to somebody who scanned later.
     */
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

    /** Attempts are not forgotten on a re-scan, or a hopeless send becomes an infinite one. */
    @Test
    fun `a re-scan does not reset the attempt count`() {
        val tried = scan(attempts = 4)
        val merged = dwMergeInduction(listOf(tried), scan(code = "DPW1:G:OTHER:AAAA"))
        assertEquals(4, merged.single().attempts)
    }

    /** Two different workshops are two rows. A designer can be waiting on more than one. */
    @Test
    fun `a scan for a different workshop is a second row`() {
        val one = scan(workshopId = "cmsik2jg8000eh8xc1lcy661a")
        val two = scan(workshopId = "cmsvfnb4y0001qq1bzd2g48lq")
        val merged = dwMergeInduction(listOf(one), two)
        assertEquals(2, merged.size)
        assertEquals(listOf(one.workshopId, two.workshopId), merged.map { it.workshopId })
    }

    /** The first scan into an empty queue is simply appended, oldest first. */
    @Test
    fun `the first scan lands at the end of an empty queue`() {
        val merged = dwMergeInduction(emptyList(), scan())
        assertEquals(1, merged.size)
        assertEquals(scan(), merged.single())
    }

    // ── The evidence note ───────────────────────────────────────────────────────────────────────

    /**
     * THE NOTE NAMES THE CLOCK-PROOF MEASURE AND LABELS THE DEVICE'S CLAIM AS A CLAIM.
     *
     * This is the sentence an administrator reads while deciding who gets a place. It must give them
     * the number that cannot be faked, and it must not let them mistake the number that can for it.
     */
    @Test
    fun `the note gives the uptime measure and labels the device clock as a claim`() {
        val pending = scan(elapsed = 1_000L, boot = 1_700_000_000_000L, deviceUtc = "2026-08-22T09:00:00Z")
        // Two days later on the same boot: elapsed advanced, boot estimate unchanged.
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
            note.contains("reached the server first"),
        )
    }

    /**
     * WHEN THE CLOCK HAS MOVED OR THE DEVICE REBOOTED, THE NOTE SAYS SO AND CLAIMS NOTHING.
     *
     * `elapsedRealtime` restarts at zero on a reboot, so the difference across one is meaningless —
     * and a wall clock that has been changed makes the boot-instant estimate move too. Inventing a
     * span from either would be inventing precision, and the direction it would be wrong in is the
     * dangerous one: it would make a two-day-old scan look like a recent one.
     */
    @Test
    fun `a reboot or a changed clock is admitted rather than papered over`() {
        val pending = scan(elapsed = 900_000L, boot = 1_700_000_000_000L)
        // A different boot instant: either a restart, or somebody moved the date.
        val note = dwInductionNote(pending, elapsedNowMs = 1_000L, wallNowMs = 1_600_000_000_000L)

        assertTrue(note.contains("restarted or had its clock changed"))
        assertFalse("no span may be claimed", note.contains("about "))
        assertTrue("the device's claim is still shown, still labelled", note.contains("what the handset claims"))
    }

    /**
     * A FEW MILLISECONDS OF DRIFT IS NOT A CLOCK CHANGE.
     *
     * `currentTimeMillis() - elapsedRealtime()` is not perfectly stable even on an untouched device —
     * the two are read a few instructions apart and NTP nudges the wall clock — so a strict comparison
     * would print the warning on every scan and the warning would stop meaning anything.
     */
    @Test
    fun `ordinary clock drift does not read as a clock change`() {
        val pending = scan(elapsed = 1_000L, boot = 1_700_000_000_000L)
        val elapsedNow = 1_000L + 5L * 60 * 1000
        // 400 ms of drift in the boot estimate, which is ordinary.
        val note = dwInductionNote(pending, elapsedNow, 1_700_000_000_000L + elapsedNow + 400L)
        assertFalse(note.contains("restarted or had its clock changed"))
        assertTrue(note.contains("about 5 minutes"))
    }

    /** No note ever argues that this scan was first. Arrival order decides, and only that. */
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

    /** The span reads in the words a decision is made in, and never invents precision. */
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
        // A negative span is a broken measurement, not a negative duration.
        assertEquals("an unknown time", dwSpanInWords(-1L))
    }

    // ── The sentences a designer reads ──────────────────────────────────────────────────────────

    /**
     * THE QUEUED SENTENCE MUST NOT SAY A REQUEST HAS BEEN SENT.
     *
     * `DwWorkshopCodesTest` already makes this assertion of two neighbouring sentences, for the reason
     * that matters more here than there: nothing has left the device, so a designer who believes an
     * administrator can see their ask will stand and wait instead of walking over and asking.
     *
     * AND IT MUST SAY THE SCAN IS SAFE, which is the other half of what somebody in a courtyard needs
     * to know — otherwise the reasonable reading of "no connection" is "do this again later", and
     * doing it again later is exactly what they must not have to remember.
     */
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

    /**
     * THE ASKING SENTENCE SAYS WHAT THE CARD IS AND WHAT IS HAPPENING — AND NOTHING MORE.
     *
     * It is on screen for the seconds a POST takes on a village connection, and both things it must
     * not do are things a well-meaning rewrite would: claim the workshop exists (nothing has been
     * asked yet, and the route answers one sentence for all seven outcomes precisely so that nobody
     * can read existence off the wording), or promise that the workshop is about to open.
     */
    @Test
    fun `the asking sentence claims neither existence nor an opening`() {
        val message = designWorkshopJoinAskingMessage()
        val lower = message.lowercase()

        assertTrue(lower.contains("design workshop"))
        assertFalse("nothing may claim the workshop is there", lower.contains("the workshop is there"))
        assertFalse("it does not open anything", lower.contains("opening"))
        assertFalse(lower.contains("request sent"))
        // It is transient, so it must read as in-progress rather than as an outcome.
        assertTrue("it must read as in progress", message.endsWith("…"))
    }

    /**
     * THE CARD'S OWN SENTENCE SAYS BOTH OF THE THINGS PEOPLE GET WRONG ABOUT IT.
     *
     * That scanning ASKS rather than admits — so somebody who scans it and sees nothing appear does
     * not scan it again — and that the code is NOT A PASSWORD. The second is the one that matters
     * later: a printed line treated as a secret is how somebody comes to believe a photograph of it
     * was safe, and `design_workshop_access.py` is explicit that the four check characters are a typo
     * detector whose algorithm ships in every browser.
     */
    @Test
    fun `the workshop card says it asks rather than admits, and is not a password`() {
        val message = designWorkshopCardPurposeMessage()
        val lower = message.lowercase()

        assertTrue("it must say scanning asks", lower.contains("asks an administrator"))
        assertTrue("it must say it does not admit by itself", lower.contains("does not let them in by itself"))
        assertTrue("it must deny being a password", lower.contains("not a password"))
        // NEVER the language of a key. A card described as one is a card people treat as a secret.
        assertFalse(lower.contains(" key"))
        assertFalse(lower.contains("secret"))
        assertFalse(lower.contains("grants access"))
    }

    // ── The one new camera refusal ──────────────────────────────────────────────────────────────

    /**
     * A MISSING LENS AND A BUSY CAMERA ARE DIFFERENT SITUATIONS, AND NEITHER IS A REFUSED PERMISSION.
     *
     * `DwCameraRefusalTest` asserts of the two permission sentences that neither is ever a dead end
     * and that they are never the same string. The same two claims apply here, plus one more: this
     * sentence must NOT offer the settings page, because turning a permission back on does nothing at
     * all for a tablet with no rear camera or for a camera another app is holding.
     */
    @Test
    fun `the camera-unavailable sentences are distinct, never dead ends, and never offer settings`() {
        val missing = dwQrCameraUnavailable(rearLensMissing = true)
        val busy = dwQrCameraUnavailable(rearLensMissing = false)

        assertNotEquals("two situations, two sentences", missing, busy)
        assertTrue("a missing lens is a fact about the device", missing.contains("no rear camera"))
        assertTrue("a busy camera names the likely cause", busy.contains("another app"))

        listOf(missing, busy).forEach { message ->
            // NEVER A DEAD END: both must name the routes that need no lens, and they are read out of
            // `DwCameraUse.QR_CODE` itself rather than retyped, so this also pins that reuse.
            assertTrue(
                "must name the camera-free routes: $message",
                message.contains(DwCameraUse.QR_CODE.alternatives),
            )
            // The settings page fixes a permission and nothing else.
            assertFalse(
                "must not send anybody to permission settings: $message",
                message.contains(DW_CAMERA_SETTINGS_BUTTON),
            )
            assertFalse(message.lowercase().contains("permission"))
        }
    }

    /**
     * THE SCANNER'S OWN SENTENCES SAY WHAT TO DO, NAME BUTTONS RATHER THAN POSITIONS, AND STAY APART.
     *
     * The position rule is `dwCameraRefusal`'s and it is here for its reason: this dialog is opened
     * from two screens that lay their panels out differently, so a sentence pointing at a place goes
     * wrong the first time a caller moves something. The labels quoted are `DwQrScanControl`'s.
     */
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

        // A stall is the camera's fault and a fruitless look is not, so the two must not read alike —
        // telling somebody to take a photograph when the pipeline has died sends them to a second
        // camera path for a problem that is not about the card at all.
        assertNotEquals(DW_QR_LIVE_STALLED, DW_QR_LIVE_STILL_TRYING)
        assertTrue(DW_QR_LIVE_STALLED.contains("stopped sending pictures"))
        assertTrue("and it names the way out", DW_QR_LIVE_STALLED.contains("open it again"))

        // The front-lens fallback must be SAID, or a designer holding a card to the back of a device
        // that is watching the front concludes the scanner is broken.
        assertTrue(DW_QR_LIVE_FRONT_LENS.contains("no rear camera"))
        assertTrue(DW_QR_LIVE_FRONT_LENS.contains("facing the screen"))
    }
}
