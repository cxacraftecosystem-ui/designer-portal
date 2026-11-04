package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIRST_READ = 8_192L

class DwDownloadTest {

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

    @Test
    fun aFastStartThenAStallDoesNotPromiseAnEarlyFinish() {
        val total = 349_000_000L
        val meter = DwTransferMeter(totalBytes = total)
        meter.observe(0L, 0L)
        meter.observe(20_000_000L, 10_000L)
        var moved = 20_000_000L
        var readout = meter.readAt(10_000L)
        for (second in 11..60) {
            moved += 40_000L
            readout = meter.observe(moved, second * 1_000L)
        }

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

        val naiveSeconds = ((total - moved) / naiveBytesPerSecond).toLong()
        assertTrue("The naive ETA is the one a designer would plan around.", naiveSeconds < 1_200L)
        assertTrue(
            "The honest ETA is hours, and the readout must say so.",
            readout.secondsRemaining!! > 5_000L,
        )
        assertTrue(dwEtaLabel(readout.secondsRemaining)!!.startsWith("about 2 hr"))
    }

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

    @Test
    fun aSlowFirstByteNeitherDragsTheSpeedNorInventsHoursLeft() {
        val total = 71_082_637L
        val meter = DwTransferMeter(totalBytes = total)
        meter.observe(0L, 0L)
        val firstByteAt = 2_900L
        val first = meter.observe(FIRST_READ, firstByteAt)

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

        assertEquals(
            "2 MB of 71 MB · 3% · 1.1 MB/s · about 1 min left",
            dwTransferLine(twoSecondsIn!!),
        )
    }

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

        val firstByteAt = headersAt + DW_RATE_STALL_MILLIS + 1_000L
        assertFalse(meter.observe(FIRST_READ, firstByteAt).stalled)
        val later = meter.observe(FIRST_READ + 2_000_000L, firstByteAt + 2_000L)
        assertFalse(later.stalled)
        assertEquals(1_000_000.0, later.bytesPerSecond!!, 1.0)
    }

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

    @Test
    fun anErraticWindowPrintsTheSpeedAndRefusesTheTimeLeft() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
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

    @Test
    fun readingTheMeterNeitherMovesTheBytesNorHoldsTheOldSpeed() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
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
        assertEquals(2_500_000.0, rateAfterTwo, 1.0)
        assertEquals(1_250_000.0, rateAfterFour, 1.0)
        assertTrue(dwTransferLine(afterFour).startsWith("5 MB of 349 MB · 1% · "))
    }

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

    @Test
    fun anOverServingHostCannotDriveThePercentagePastOneHundred() {
        val meter = DwTransferMeter(totalBytes = 349_000_000L)
        meter.observe(0L, 0L)
        meter.observe(FIRST_READ, 500L)
        val readout = meter.observe(400_000_000L, 2_500L)

        assertEquals(100, readout.percent)
        assertEquals(0L, readout.secondsRemaining)
    }

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

    @Test
    fun aPartialIsNamedSoItCannotBeMistakenForAFinishedFile() {
        val partial = dwPartialFileName("model-omnilingual.zip")
        assertEquals("model-omnilingual.zip.part", partial)
        assertTrue(dwIsPartialFileName(partial))
        assertFalse(dwIsPartialFileName("model-omnilingual.zip"))
        assertFalse(dwIsPartialFileName("model.int8.onnx"))
    }

    @Test
    fun aRateIsNeverInventedAndItsUnitsAreTheOnesTheSizesUse() {
        assertNull(dwRateLabel(null))
        assertNull(dwRateLabel(-1.0))
        assertEquals("2.4 MB/s", dwRateLabel(2_400_000.0))
        assertEquals("400 kB/s", dwRateLabel(400_000.0))
        assertEquals("40 B/s", dwRateLabel(40.0))
        assertEquals("1.0 MB/s", dwRateLabel(1_000_000.0))
    }

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

    @Test
    fun aPauseNamesWhatItKeptInBytes() {
        val sentence = dwPausedSentence(184_000_000L)
        assertTrue(sentence.startsWith("184 MB kept on this phone."))
        assertTrue(sentence.contains("Cancel deletes it"))

        assertEquals("Pause", dwPauseLabel(DwTransferControlState.RUNNING))
        assertEquals("Resume", dwPauseLabel(DwTransferControlState.PAUSED))
        assertEquals("Start again", dwPauseLabel(DwTransferControlState.CANCELLED))
    }

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

    @Test
    fun everyPhaseHasItsOwnHeadingAndNoTwoShareOne() {
        val headings = DwTransferPhase.entries.map { dwTransferHeading(it) }
        assertEquals(DwTransferPhase.entries.size, headings.toSet().size)
        assertEquals("Downloading", dwTransferHeading(DwTransferPhase.FETCHING))
        assertEquals("Copying onto this phone", dwTransferHeading(DwTransferPhase.COPYING))
    }

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
                    meter.observe(moved, i * 3L)
                }
            }.onFailure { failures += it }
        }
        val reader = Thread {
            runCatching {
                for (i in 1..100_000) {
                    val readout = meter.readAt(i * 3L)
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
