package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body's first reading in these traces: 8,192 bytes, what the API 37 emulator's first published
 * reading carried in run 37939404066. Any non-zero count would do — what the traces pin is that the
 * rate's window opens at the first byte, whatever that first reading weighs.
 */
private const val FIRST_READ = 8_192L

/**
 * **THE TRANSFER READOUT: EVERY NUMBER ON IT, DRIVEN BY A SCRIPTED CLOCK.**
 *
 * `DwDownload.kt`'s header has claimed since it was written that *"every function takes the clock as
 * an argument, which is what lets `DwDownloadTest` drive a stalled connection, a sub-second download,
 * a server that never sent a length and a zero-byte start on a desktop JVM in microseconds"*. **That
 * file did not exist.** 621 lines of arithmetic behind the one sentence the owner asked for first —
 * *"the loading bar should show speed, expected duration, and how much of it has been downloaded"* —
 * had no test at all, in a lane opened because a number on this surface was not believed.
 *
 * ── WHAT THESE TESTS ARE FOR, WHICH IS NOT "COVERAGE" ─────────────────────────────────────────
 *
 * A fabricated figure is the defect this whole surface is being reviewed for, so every test below
 * asserts against a number computed **independently of the code under test** — a byte count and a
 * clock written out by hand, and the answer worked out in the assertion's own arithmetic. Nothing here
 * reads a constant and asserts the constant equals itself; `DwAsrModelTest` records what this
 * repository already paid for doing that.
 *
 * The two that matter most, because they are the two ways a progress readout lies:
 *
 *  * [aFastStartThenAStallDoesNotPromiseAnEarlyFinish] — the failure `DwDownload.kt`'s sliding window
 *    exists to prevent, asserted against what total-over-elapsed would have printed at the same moment.
 *  * [theTimeRemainingConvergesRatherThanJumping] — an ETA that swings is one a designer learns to
 *    disbelieve, and they then disbelieve the honest one too.
 *
 * And a third, found on a real socket rather than reasoned out:
 * [aSlowFirstByteNeitherDragsTheSpeedNorInventsHoursLeft] — a host's wait before its first byte,
 * divided into the first reading. Since that fix the rate's window opens at the first observation that
 * carries bytes, so the traces below record the zero a fetch takes when its headers arrive AND a first
 * byte, as `DwAsrModelController` does.
 */
class DwDownloadTest {

    // -----------------------------------------------------------------------------------------
    // The line the owner asked for
    // -----------------------------------------------------------------------------------------

    /**
     * **THE OWNER'S SENTENCE, AS ONE STRING, OFF A MEASURED TRACE.**
     *
     * 349 MB total; 173 MB on disk at the top of the window and 185 MB five seconds later. Every
     * clause is worked out here by hand: 12 MB in 5 s is 2.4 MB/s; 185 of 349 is 53%; the remaining
     * 164 MB at 2.4 MB/s is 68.3 s, which rounds up to 69 and reads as one minute.
     */
    @Test
    fun theLineCarriesBytesPercentSpeedAndTimeLeft() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
        meter.observe(173_000_000L, 0L)
        val readout = meter.observe(185_000_000L, 5_000L)

        assertEquals(185_000_000L, readout.receivedBytes)
        assertEquals(53, readout.percent)
        assertEquals(2_400_000.0, readout.bytesPerSecond!!, 1.0)
        assertEquals(69L, readout.secondsRemaining)
        assertEquals(DwRateStability.STEADY, readout.stability)
        assertFalse(readout.stalled)

        assertEquals(
            "185 MB of 349 MB · 53% · 2.4 MB/s · about 1 min left",
            dwTransferLine(readout),
        )
    }

    /** Nothing has arrived yet: a total, a zero, and the word for "not measured", never a speed. */
    @Test
    fun beforeAnythingHasArrivedTheSpeedIsMeasuringNotZero() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
        val first = meter.observe(0L, 8_000L)

        assertEquals(0, first.percent)
        assertNull(first.bytesPerSecond)
        assertNull(first.secondsRemaining)
        assertEquals(DwRateStability.UNKNOWN, first.stability)
        assertEquals("0 kB of 349 MB · 0% · measuring…", dwTransferLine(first))
    }

    /**
     * A transfer that finished inside the minimum window prints no speed **and no ETA**.
     *
     * One 64 KiB buffer landing 200 ms in reads as 300 MB/s, and a rate nobody could reproduce is the
     * same defect as a rate nobody measured.
     */
    @Test
    fun aSubSecondTransferNeverPrintsASpeed() {
        val meter = DwTransferMeter(totalBytes = 2_000_000L)
        meter.observe(0L, 0L)
        val done = meter.observe(2_000_000L, 400L)

        assertEquals(100, done.percent)
        assertNull("400 ms is not a measurement of a rate.", done.bytesPerSecond)
        assertNull(done.secondsRemaining)
        assertEquals("2 MB of 2 MB · 100% · measuring…", dwTransferLine(done))
    }

    // -----------------------------------------------------------------------------------------
    // The two ways a progress readout lies
    // -----------------------------------------------------------------------------------------

    /**
     * **THE FAILURE THE SLIDING WINDOW EXISTS FOR, ASSERTED AGAINST THE NAIVE ANSWER.**
     *
     * 20 MB off a warm cache in the first ten seconds, then 40 kB/s for fifty seconds. The naive
     * readout — everything received over everything elapsed — is computed in the assertion below from
     * the same two numbers and is nearly ten times the truth; the ETA it implies is about eighteen
     * minutes against a real answer above two hours. **A designer plans their afternoon around that
     * difference.**
     */
    @Test
    fun aFastStartThenAStallDoesNotPromiseAnEarlyFinish() {
        val total = 349_000_000L
        val meter = DwTransferMeter(totalBytes = total)
        // The warm-cache burst.
        meter.observe(0L, 0L)
        meter.observe(20_000_000L, 10_000L)
        // Then a district-town link at 40 kB/s, a sample a second, out to one minute.
        var moved = 20_000_000L
        var readout = meter.readAt(10_000L)
        for (second in 11..60) {
            moved += 40_000L
            readout = meter.observe(moved, second * 1_000L)
        }

        // What the naive readout would have printed at this same moment, worked out here.
        val naiveBytesPerSecond = moved * 1_000.0 / 60_000L
        assertTrue(
            "The naive rate should be the optimistic one this test is about.",
            naiveBytesPerSecond > 350_000.0,
        )

        val honest = readout.bytesPerSecond!!
        assertEquals(40_000.0, honest, 4_000.0)
        assertTrue(
            "The window must not carry the fast start into the stall.",
            honest < naiveBytesPerSecond / 5.0,
        )

        // And therefore the time left is the true one, not the flattering one.
        val naiveSeconds = ((total - moved) / naiveBytesPerSecond).toLong()
        assertTrue("The naive ETA is the one a designer would plan around.", naiveSeconds < 1_200L)
        assertTrue(
            "The honest ETA is hours, and the readout must say so.",
            readout.secondsRemaining!! > 5_000L,
        )
        assertTrue(dwEtaLabel(readout.secondsRemaining)!!.startsWith("about 2 hr"))
    }

    /**
     * **THE ETA CONVERGES.** On a steady link it counts down, never jumps up, and lands on the truth.
     *
     * A number that walks backwards is one a designer learns to disbelieve. 100 MB at a flat 2 MB/s is
     * fifty seconds; the assertion checks every reading is within four seconds of the remainder worked
     * out here and that no reading is larger than the one before it.
     */
    @Test
    fun theTimeRemainingConvergesRatherThanJumping() {
        val total = 100_000_000L
        val perSecond = 2_000_000L
        val meter = DwTransferMeter(totalBytes = total)
        meter.observe(0L, 0L)

        var previous = Long.MAX_VALUE
        var checked = 0
        for (second in 1..45) {
            val moved = perSecond * second
            val readout = meter.observe(moved, second * 1_000L)
            val eta = readout.secondsRemaining ?: continue
            val truth = (total - moved) / perSecond
            assertEquals(
                "At $second s the readout should agree with the arithmetic.",
                truth.toDouble(),
                eta.toDouble(),
                4.0,
            )
            assertTrue("The ETA walked backwards at $second s: $previous then $eta.", eta <= previous)
            previous = eta
            checked++
        }
        assertTrue("The trace should have produced a run of ETAs.", checked > 40)
    }

    // -----------------------------------------------------------------------------------------
    // The wait for the first byte, which is latency and not throughput
    // -----------------------------------------------------------------------------------------

    /**
     * **A HOST THAT THINKS FOR THREE SECONDS BEFORE ITS FIRST BYTE MUST NOT PRINT "3 kB/s".**
     *
     * The shape of what `DwAsrModelTransferProbeTest` recorded on the API 37 emulator (run
     * 37939404066): the response headers at 0, the host silent for 2.9 s, an 8,192-byte first reading,
     * and then the body arriving steadily. The card printed `3 kB/s · about 6 hr 50 min left`, because
     * the window's origin was the zero recorded at the headers and the first reading was divided by the
     * server's thinking time. Here the body runs at a flat 1.1 MB/s, sampled every 250 ms as
     * `publishProgress` does, and every figure is worked out in this test's own arithmetic.
     */
    @Test
    fun aSlowFirstByteNeitherDragsTheSpeedNorInventsHoursLeft() {
        val total = 71_082_637L
        val meter = DwTransferMeter(totalBytes = total)
        meter.observe(0L, 0L)
        val firstByteAt = 2_900L
        val first = meter.observe(FIRST_READ, firstByteAt)

        // What the old origin printed at this moment, from the same two numbers.
        val zeroOriginRate = FIRST_READ * 1_000.0 / firstByteAt
        assertEquals("3 kB/s", dwRateLabel(zeroOriginRate))
        val zeroOriginEta = ((total - FIRST_READ) / zeroOriginRate).toLong()
        assertTrue(dwEtaLabel(zeroOriginEta)!!.startsWith("about 6 hr"))

        assertNull("One reading is not a rate, however long the server took to send it.", first.bytesPerSecond)
        assertNull(first.secondsRemaining)
        assertFalse(first.stalled)
        assertEquals("8 kB of 71 MB · 0% · measuring…", dwTransferLine(first))

        val perSecond = 1_100_000L
        var measured = 0
        var twoSecondsIn: DwTransferReadout? = null
        for (step in 1..24) {
            val at = firstByteAt + step * 250L
            val moved = FIRST_READ + perSecond * (at - firstByteAt) / 1_000L
            val readout = meter.observe(moved, at)
            if (at - firstByteAt == 2_000L) twoSecondsIn = readout
            if (at - firstByteAt < DW_RATE_MIN_WINDOW_MILLIS) {
                assertNull("Under a second of body is still 'measuring…' at $at ms.", readout.bytesPerSecond)
                assertNull(readout.secondsRemaining)
                continue
            }
            assertEquals(
                "At $at ms the speed is the body's, not the body's diluted by the wait.",
                perSecond.toDouble(), readout.bytesPerSecond!!, 1.0,
            )
            assertEquals(DwRateStability.STEADY, readout.stability)
            val truth = (total - moved).toDouble() / perSecond
            assertEquals("At $at ms the time left is the body's.", truth, readout.secondsRemaining!!.toDouble(), 1.0)
            measured++
        }
        assertTrue("The trace should have produced a run of measured readings.", measured >= 20)

        // Two seconds into the body: 8,192 + 2,200,000 = 2,208,192 of 71,082,637 bytes (3%), and
        // 68,874,445 to go at 1.1 MB/s is 62.6 s, which rounds up to 63 and reads as one minute.
        assertEquals(
            "2 MB of 71 MB · 3% · 1.1 MB/s · about 1 min left",
            dwTransferLine(twoSecondsIn!!),
        )
    }

    /**
     * **AND THE STALL CLOCK STILL STARTS AT THE HEADERS.** Moving the rate's origin to the first byte
     * must not move this one with it: a host that answers and then sends nothing is the stall a
     * designer most needs to hear about, and it would otherwise read "measuring…" for ever.
     */
    @Test
    fun aServerThatAnswersAndThenSendsNothingStallsOnTheHeaderClock() {
        val meter = DwTransferMeter(totalBytes = 71_082_637L)
        val headersAt = 1_000L
        meter.observe(0L, headersAt)

        val nearly = meter.readAt(headersAt + DW_RATE_STALL_MILLIS - 1L)
        assertFalse("A wait shorter than the threshold is not a stall.", nearly.stalled)
        assertEquals("0 kB of 71 MB · 0% · measuring…", dwTransferLine(nearly))

        val stalled = meter.readAt(headersAt + DW_RATE_STALL_MILLIS)
        assertTrue("Ten seconds with no byte after the headers is a stall.", stalled.stalled)
        assertEquals("0 kB of 71 MB · 0% · stalled", dwTransferLine(stalled))
        assertNotNull(dwStalledSentence(stalled, DwTransferPhase.FETCHING))

        // The first byte does come, late. It clears the stall, and the rate is measured from it — not
        // from the headers eleven seconds earlier, which would read 2 MB over 13 s instead of over 2 s.
        val firstByteAt = headersAt + DW_RATE_STALL_MILLIS + 1_000L
        assertFalse(meter.observe(FIRST_READ, firstByteAt).stalled)
        val later = meter.observe(FIRST_READ + 2_000_000L, firstByteAt + 2_000L)
        assertFalse(later.stalled)
        assertEquals(1_000_000.0, later.bytesPerSecond!!, 1.0)
    }

    // -----------------------------------------------------------------------------------------
    // A connection that stops, and one nobody can describe
    // -----------------------------------------------------------------------------------------

    /**
     * Ten seconds without a byte is **stalled** — said instead of a speed, with the ETA withheld and a
     * sentence naming what happens to the bytes already on the phone.
     */
    @Test
    fun tenSilentSecondsReadAsStalledAndWithholdTheSpeedAndTheEta() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
        meter.observe(0L, 0L)
        meter.observe(184_000_000L, 5_000L)

        val nearly = meter.readAt(5_000L + DW_RATE_STALL_MILLIS - 1L)
        assertFalse("A pause shorter than the threshold is not a stall.", nearly.stalled)

        val stalled = meter.readAt(5_000L + DW_RATE_STALL_MILLIS)
        assertTrue(stalled.stalled)
        assertEquals(184_000_000L, stalled.receivedBytes)
        assertNull("A stalled transfer has no time left to project.", stalled.secondsRemaining)
        assertEquals("184 MB of 349 MB · 52% · stalled", dwTransferLine(stalled))

        val sentence = dwStalledSentence(stalled, DwTransferPhase.FETCHING)
        assertNotNull(sentence)
        assertTrue(
            "A stalled fetch must say what happens to what has arrived.",
            sentence!!.contains("keeps what has arrived"),
        )
        assertNull(
            "Nothing is said while the transfer is moving.",
            dwStalledSentence(nearly, DwTransferPhase.FETCHING),
        )
    }

    /**
     * A window whose halves disagree many-fold prints the measured speed and **refuses the ETA**,
     * saying so in words rather than printing a figure that would swing.
     */
    @Test
    fun anErraticWindowPrintsTheSpeedAndRefusesTheTimeLeft() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
        // The headers at 0 and the first byte at 500 ms: the window runs from 500 ms to 5.5 s, its
        // first half moving 10 MB and its second 0.1 MB.
        meter.observe(0L, 0L)
        meter.observe(FIRST_READ, 500L)
        meter.observe(FIRST_READ + 10_000_000L, 3_000L)
        val readout = meter.observe(FIRST_READ + 10_100_000L, 5_500L)

        assertEquals(DwRateStability.ERRATIC, readout.stability)
        assertNotNull("The speed was measured, so it is printed.", readout.bytesPerSecond)
        assertNull("The ETA was not, so it is not.", readout.secondsRemaining)
        assertTrue(
            dwTransferLine(readout).endsWith("time left is not steady enough to guess"),
        )
    }

    /**
     * **READING THE METER DOES NOT ADVANCE IT**, which is the only reason a stall is visible at all.
     *
     * The surface asks on its own clock while the connection delivers nothing. Every answer must keep
     * the same byte count and a rate that decays towards zero — a sampling read would instead record
     * that the count is still climbing and hold the old speed for ever.
     */
    @Test
    fun readingTheMeterNeitherMovesTheBytesNorHoldsTheOldSpeed() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
        // The headers at 0, the body's first byte at 500 ms, and 5 MB more by 2.5 s.
        meter.observe(0L, 0L)
        meter.observe(FIRST_READ, 500L)
        meter.observe(FIRST_READ + 5_000_000L, 2_500L)

        val afterTwoSeconds = meter.readAt(2_500L)
        val afterFour = meter.readAt(4_500L)
        val afterEight = meter.readAt(8_500L)

        val received = FIRST_READ + 5_000_000L
        assertEquals(received, afterTwoSeconds.receivedBytes)
        assertEquals(received, afterFour.receivedBytes)
        assertEquals(received, afterEight.receivedBytes)
        val rateAfterTwo = afterTwoSeconds.bytesPerSecond!!
        val rateAfterFour = afterFour.bytesPerSecond!!
        val rateAfterEight = afterEight.bytesPerSecond!!
        assertTrue("Silence must lower the reported rate.", rateAfterFour < rateAfterTwo)
        assertTrue(rateAfterEight < rateAfterFour)
        // 2.5 MB/s measured over the two seconds after the first byte, halved by two more seconds of
        // nothing arriving. The first byte's own buffer is the origin, so it is in neither figure.
        assertEquals(2_500_000.0, rateAfterTwo, 1.0)
        assertEquals(1_250_000.0, rateAfterFour, 1.0)
        assertTrue(dwTransferLine(afterFour).startsWith("5 MB of 349 MB · 1% · "))
    }

    // -----------------------------------------------------------------------------------------
    // What the server did or did not say
    // -----------------------------------------------------------------------------------------

    /** No `Content-Length` means no percentage and no ETA — bytes and a speed, and nothing invented. */
    @Test
    fun aServerThatSentNoLengthGetsNoPercentageAndNoBar() {
        val meter = DwTransferMeter(totalBytes = null)
        meter.observe(0L, 0L)
        meter.observe(FIRST_READ, 1_000L)
        val readout = meter.observe(FIRST_READ + 3_000_000L, 4_000L)

        assertNull("A percentage needs a denominator.", readout.percent)
        assertNull("So does a time remaining.", readout.secondsRemaining)
        assertEquals(FIRST_READ + 3_000_000L, readout.receivedBytes)
        assertEquals("3 MB · 1.0 MB/s", dwTransferLine(readout))
    }

    /** A host that over-serves cannot push the percentage past 100 or the bar off the end. */
    @Test
    fun anOverServingHostCannotDriveThePercentagePastOneHundred() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
        meter.observe(0L, 0L)
        meter.observe(FIRST_READ, 500L)
        val readout = meter.observe(400_000_000L, 2_500L)

        assertEquals(100, readout.percent)
        assertEquals(0L, readout.secondsRemaining)
    }

    /** The readout refuses to be constructed with a percentage that is not one. */
    @Test
    fun aReadoutRefusesAPercentageOutsideItsRange() {
        val rejected = runCatching {
            DwTransferReadout(
                receivedBytes = 1L,
                totalBytes = 2L,
                percent = 140,
                bytesPerSecond = null,
                secondsRemaining = null,
                stability = DwRateStability.UNKNOWN,
                stalled = false,
            )
        }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
    }

    // -----------------------------------------------------------------------------------------
    // Resuming
    // -----------------------------------------------------------------------------------------

    /**
     * **A RESUME'S PERCENTAGE IS ABOUT THE FILE; ITS SPEED IS ABOUT THIS ATTEMPT.**
     *
     * 200 MB was already on the phone. Conflating the two is how a resumed download reads "100%" in
     * its first millisecond, or claims 200 MB/s.
     */
    @Test
    fun aResumeCountsThePrefixInThePercentageButNotInTheRate() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L, resumedFromBytes = 200_000_000L)
        meter.observe(1_000_000L, 0L)
        val readout = meter.observe(3_000_000L, 2_000L)

        assertEquals(203_000_000L, readout.receivedBytes)
        assertEquals(58, readout.percent)
        assertEquals(1_000_000.0, readout.bytesPerSecond!!, 1.0)
        assertEquals("203 MB of 349 MB · 58% · 1.0 MB/s · about 2 min left", dwTransferLine(readout))
    }

    /**
     * The resume decision, arm by arm. **Each of the three refusals is a real corruption avoided**, and
     * the numbers are the pinned container's.
     */
    @Test
    fun theResumePlanRefusesEveryPrefixItCannotReasonAbout() {
        val total = 292_571_207L

        assertEquals(DwResumeDecision.START_FRESH, dwResumePlan(0L, total, true))
        assertEquals(DwResumeDecision.START_FRESH, dwResumePlan(0L, total, false))
        assertEquals(
            DwResumeDecision.RESUME_FROM_PARTIAL,
            dwResumePlan(200_000_000L, total, true),
        )
        assertEquals(
            "A host that will not honour a range cannot be resumed from.",
            DwResumeDecision.DISCARD_AND_RESTART,
            dwResumePlan(200_000_000L, total, false),
        )
        assertEquals(
            "A prefix at the pinned length has nothing to resume to.",
            DwResumeDecision.DISCARD_AND_RESTART,
            dwResumePlan(total, total, true),
        )
        assertEquals(
            "Past the pinned length is not a prefix of anything.",
            DwResumeDecision.DISCARD_AND_RESTART,
            dwResumePlan(total + 1L, total, true),
        )
        assertEquals(
            "With no length there is no telling a prefix from a whole file.",
            DwResumeDecision.DISCARD_AND_RESTART,
            dwResumePlan(200_000_000L, null, true),
        )
    }

    /** A 200 with the whole file is not a resume, whatever it says. */
    @Test
    fun onlyATwoOhSixThatStartsWhereWeAskedIsAResume() {
        assertTrue(dwRangeHonoured(206, 200_000_000L, 200_000_000L))
        assertFalse("200 is the whole file again.", dwRangeHonoured(200, null, 200_000_000L))
        assertFalse(
            "A 206 starting somewhere else would stitch the wrong bytes together.",
            dwRangeHonoured(206, 0L, 200_000_000L),
        )
        assertFalse(dwRangeHonoured(206, null, 200_000_000L))
        assertFalse(dwRangeHonoured(416, 0L, 200_000_000L))
    }

    /** `Content-Range` parsing, including the 416 form that must never read as an offset of zero. */
    @Test
    fun theContentRangeOffsetIsReadOrRefused() {
        assertEquals(200L, dwParseContentRangeStart("bytes 200-1000/1001"))
        assertEquals(0L, dwParseContentRangeStart("bytes 0-1000/1001"))
        assertEquals(200L, dwParseContentRangeStart("  bytes 200-1000/*  "))
        assertNull("A 416 says the range was unsatisfiable.", dwParseContentRangeStart("bytes */1001"))
        assertNull(dwParseContentRangeStart(null))
        assertNull(dwParseContentRangeStart("items 200-1000/1001"))
        assertNull(dwParseContentRangeStart("bytes -1-1000/1001"))
    }

    /** The part-file's name is not the finished file's, and the sweep can tell them apart by it. */
    @Test
    fun aPartialIsNamedSoItCannotBeMistakenForAFinishedFile() {
        val partial = dwPartialFileName("model-omnilingual.zip")
        assertEquals("model-omnilingual.zip.part", partial)
        assertTrue(dwIsPartialFileName(partial))
        assertFalse(dwIsPartialFileName("model-omnilingual.zip"))
        assertFalse(dwIsPartialFileName("model.int8.onnx"))
    }

    // -----------------------------------------------------------------------------------------
    // The words around the numbers
    // -----------------------------------------------------------------------------------------

    /** Null in, null out — the readout drops a clause rather than printing "0 MB/s" for unmeasured. */
    @Test
    fun aRateIsNeverInventedAndItsUnitsAreTheOnesTheSizesUse() {
        assertNull(dwRateLabel(null))
        assertNull(dwRateLabel(-1.0))
        assertEquals("2.4 MB/s", dwRateLabel(2_400_000.0))
        assertEquals("400 kB/s", dwRateLabel(400_000.0))
        assertEquals("40 B/s", dwRateLabel(40.0))
        // Decimal MB, matching dwBytesLabel — a 349 MB file at "2.4 MiB/s" would not divide.
        assertEquals("1.0 MB/s", dwRateLabel(1_000_000.0))
    }

    /** Coarse on purpose: minutes change slowly enough to be believed. */
    @Test
    fun theTimeLeftIsRoundedTheWayAPersonWaitingWouldRoundIt() {
        assertNull(dwEtaLabel(null))
        assertNull(dwEtaLabel(-5L))
        assertEquals("under a minute left", dwEtaLabel(0L))
        assertEquals("under a minute left", dwEtaLabel(59L))
        assertEquals("about 1 min left", dwEtaLabel(60L))
        assertEquals("about 18 min left", dwEtaLabel(1_100L))
        assertEquals("about 1 hr left", dwEtaLabel(3_600L))
        assertEquals("about 2 hr 16 min left", dwEtaLabel(8_175L))
    }

    /** Pause keeps the prefix and says how much; the two labels for the two intents are not one word. */
    @Test
    fun aPauseNamesWhatItKeptInBytes() {
        val sentence = dwPausedSentence(184_000_000L)
        assertTrue(sentence.startsWith("184 MB kept on this phone."))
        assertTrue(sentence.contains("Cancel deletes it"))

        assertEquals("Pause", dwPauseLabel(DwTransferControlState.RUNNING))
        assertEquals("Resume", dwPauseLabel(DwTransferControlState.PAUSED))
        assertEquals("Start again", dwPauseLabel(DwTransferControlState.CANCELLED))
    }

    /** Room is asked for before a byte moves, and an unmeasured volume is a refusal, not a guess. */
    @Test
    fun theSpaceRefusalNamesBothFiguresOrRefusesToGuess() {
        assertNull(dwTransferSpaceRefusal(2_000_000_000L, 1_000_000_000L))

        val tight = dwTransferSpaceRefusal(500_000_000L, 1_000_000_000L)
        assertNotNull(tight)
        assertTrue("It must name what is needed.", tight!!.contains("1.0 GB"))
        assertTrue("And what the phone reports.", tight.contains("500 MB"))

        val unknown = dwTransferSpaceRefusal(null, 1_000_000_000L)
        assertNotNull(unknown)
        assertTrue(unknown!!.contains("unknown"))
    }

    /** Each phase has one heading, so the copy route and the fetch cannot describe themselves twice. */
    @Test
    fun everyPhaseHasItsOwnHeadingAndNoTwoShareOne() {
        val headings = DwTransferPhase.entries.map { dwTransferHeading(it) }
        assertEquals(DwTransferPhase.entries.size, headings.toSet().size)
        assertEquals("Downloading", dwTransferHeading(DwTransferPhase.FETCHING))
        assertEquals("Copying onto this phone", dwTransferHeading(DwTransferPhase.COPYING))
    }

    // -----------------------------------------------------------------------------------------
    // The volume filling up while the bytes were arriving
    // -----------------------------------------------------------------------------------------

    /**
     * **THE MESSAGE A FULL PHONE ACTUALLY PRODUCES, WHICH IS THE ONLY REASON THIS IS A STRING TEST.**
     *
     * There is no `DiskFullException` on Android: a full volume is an [java.io.IOException] whose
     * message carries the `errno` name. The exact texts below are the two shapes seen — bionic's
     * `write failed: ENOSPC (No space left on device)`, and the human clause on its own from a wrapped
     * rethrow. `DW_TRANSFER_DISK_FULL_SENTENCE` was written for this moment and was drawn nowhere at
     * all, so what a designer read was the first of those strings.
     */
    @Test
    fun aFullVolumeIsRecognisedFromWhatThePlatformActuallySays() {
        assertTrue(dwIsDiskFull("write failed: ENOSPC (No space left on device)"))
        assertTrue(dwIsDiskFull("java.io.IOException: No space left on device"))
        assertTrue("The errno name may arrive in any case.", dwIsDiskFull("enospc"))

        assertFalse("A dropped connection is not a full disk.", dwIsDiskFull("unexpected end of stream"))
        assertFalse(dwIsDiskFull("write failed: EACCES (Permission denied)"))
        assertFalse(dwIsDiskFull(null))
        assertFalse(dwIsDiskFull(""))
    }

    /**
     * A fetch promises a resume because it kept the prefix; a copy must not, because it did not.
     *
     * Offering "tap Resume" after a copy would name a button that is not drawn — `pause()` refuses any
     * phase but FETCHING, and the copy's source files are still on the phone anyway.
     */
    @Test
    fun theDiskFullSentencePromisesAResumeOnlyWhereThereIsOneToPromise() {
        val fetching = dwTransferDiskFullSentence(DwTransferPhase.FETCHING)
        assertEquals(DW_TRANSFER_DISK_FULL_SENTENCE, fetching)
        assertTrue("A fetch kept its prefix, so it says so.", fetching.contains("kept"))
        assertTrue(fetching.contains("Resume"))

        listOf(DwTransferPhase.COPYING, DwTransferPhase.VERIFYING, DwTransferPhase.UNPACKING).forEach { phase ->
            val sentence = dwTransferDiskFullSentence(phase)
            assertFalse(
                "$phase has nothing to resume from, so it must not offer a Resume.",
                sentence.contains("Resume"),
            )
            assertTrue("It still has to say what to do.", sentence.contains("Free some space"))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Two threads on one meter
    // -----------------------------------------------------------------------------------------

    /**
     * **THE METER IS FED FROM THE IO DISPATCHER AND READ FROM THE MAIN THREAD, AND IT USED TO RACE.**
     *
     * `DwAsrModelController.publishProgress` calls [DwTransferMeter.observe] from inside
     * `withContext(Dispatchers.IO)`; the surface's once-a-second tick calls [DwTransferMeter.readAt] on
     * the main thread, because that is the only way a stall is ever noticed. Both walk the sample
     * deque and one of them mutates it. `kotlin.collections.ArrayDeque` nulls the head slot before it
     * advances the head index, so an interleaved `first()` could read that null and throw inside
     * `windowRate` — a main-thread crash during a download, in the one code path where losing the
     * readout also loses the Pause button.
     *
     * This drives the same two callers on two threads over a hundred thousand samples. It is a race, so
     * a green run is not a proof — but the unsynchronised version fails it, and a regression that
     * removes the lock has something that objects.
     */
    @Test
    fun aReaderOnAnotherThreadCannotTripTheMeterMidWrite() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val reads = java.util.concurrent.atomic.AtomicLong()

        val writer = Thread {
            runCatching {
                var moved = 0L
                for (i in 1..100_000) {
                    moved += 64 * 1024
                    // A clock that advances fast enough to keep the window pruning on every call,
                    // which is where the mutation this test is about happens.
                    meter.observe(moved, i * 3L)
                }
            }.onFailure { failures += it }
        }
        val reader = Thread {
            runCatching {
                for (i in 1..100_000) {
                    val readout = meter.readAt(i * 3L)
                    // Touch the derived figures so the whole read path is exercised, not just the call.
                    if (readout.receivedBytes >= 0L) reads.incrementAndGet()
                    dwTransferLine(readout)
                }
            }.onFailure { failures += it }
        }
        writer.start(); reader.start()
        writer.join(30_000); reader.join(30_000)

        assertTrue(
            "Reading the meter while it was being fed threw: " +
                failures.joinToString { "${it::class.java.simpleName}: ${it.message}" },
            failures.isEmpty(),
        )
        assertTrue("The reader should have taken a great many readings.", reads.get() > 50_000L)
    }
}
