package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handset's mirror of the server's daily dictation allowance, on the desktop JVM.
 *
 * WHAT THIS FILE IS FOR. The mirror decides whether a designer's next dictation is attempted at all, and
 * every way it can be wrong is silent. Too eager and it withholds craft-aware transcription that has
 * already been paid for, with nothing on screen naming a stale cache as the cause. Too slack and every
 * one of the several hundred prose fields on a stage spends a six-megabyte upload to be told the same
 * thing on a connection that is the scarcest resource in a district town. Neither shows up as a crash,
 * and neither is reachable by looking at the screen — which is why the freshness rule is a pure function
 * and this file exercises it directly.
 *
 * The day boundary is here too. "Daily" means nothing without one, and this one has to agree with the
 * server's `dictation_cap.ist_day` to the character, because a phone that computed a different day would
 * hold an allowance the server has never heard of.
 */
class DwDictationAllowanceTest {

    private val designer = "usr_meera"
    private val colleague = "usr_ravi"

    // ---------------------------------------------------------------------------------------
    // The day boundary
    // ---------------------------------------------------------------------------------------

    /**
     * MIDNIGHT INDIA TIME, AND NOT MIDNIGHT UTC, asserted from both sides of the boundary.
     *
     * A UTC day would reset the allowance at 05:30 IST — mid-morning to this fleet — which the census
     * route calls "visibly wrong to the people it is for" about exactly this choice. The two instants
     * below are the ones that would disagree: 18:45 UTC is already tomorrow in India, and 18:15 UTC is
     * not yet.
     */
    @Test
    fun `the day turns at midnight India time and not at midnight UTC`() {
        // 2026-08-12T18:29:59Z is 2026-08-12T23:59:59+05:30 — the last second of the Indian day.
        val lastSecond = java.time.Instant.parse("2026-08-12T18:29:59Z").toEpochMilli()
        assertEquals("2026-08-12", dwDictationIstDay(lastSecond))

        // One second later it is the 13th in India and still the 12th in UTC.
        val firstSecond = java.time.Instant.parse("2026-08-12T18:30:00Z").toEpochMilli()
        assertEquals("2026-08-13", dwDictationIstDay(firstSecond))

        // And the UTC midnight in between changes nothing, which is the whole point.
        val utcMidnight = java.time.Instant.parse("2026-08-13T00:00:00Z").toEpochMilli()
        assertEquals("2026-08-13", dwDictationIstDay(utcMidnight))
    }

    /** The shape the server's key is in: a bare calendar date, comparable as a string. */
    @Test
    fun `the day is a plain calendar date, which is what the server keys its row by`() {
        val day = dwDictationIstDay(java.time.Instant.parse("2026-01-05T04:00:00Z").toEpochMilli())
        assertEquals("2026-01-05", day)
        assertTrue("It must sort chronologically as text", "2026-01-05" < "2026-01-06")
    }

    // ---------------------------------------------------------------------------------------
    // Fresh, stale, and somebody else's
    // ---------------------------------------------------------------------------------------

    /**
     * NOTHING KNOWN IS NOT "SPENT", AND IT IS NOT A NUMBER EITHER.
     *
     * A phone that has never completed or been refused a dictation has no allowance to report, and both
     * halves of that matter: it must attempt the upload (the ordinary first dictation of a day) and it
     * must not print a ceiling it was never told.
     */
    @Test
    fun `a phone that has been told nothing is not spent and names no number`() {
        val view = dwDictationCapView(stored = null, userId = designer, today = "2026-08-12")
        assertFalse(view.spent)
        assertNull(view.limit)
    }

    /** Nobody signed in: read as unknown rather than as whoever was signed in last. */
    @Test
    fun `with nobody signed in there is no allowance to read`() {
        val stored = DwDictationAllowance(designer, "2026-08-12", limit = 40, remaining = 0)
        listOf(null, "", "   ").forEach { userId ->
            val view = dwDictationCapView(stored, userId, "2026-08-12")
            assertFalse("An unsigned phone must not inherit a refusal: $userId", view.spent)
            assertNull(view.limit)
        }
    }

    /**
     * THE CAP IS PER DESIGNER, AND A FIELD PHONE IS HANDED BETWEEN THEM.
     *
     * One designer's spent afternoon must not refuse the colleague who signs in after them, and one
     * designer's ceiling must not be printed to another — the same reason `CarryContextStore` keys every
     * row by user id and repeats it inside the payload.
     */
    @Test
    fun `one designer's spent day does not refuse their colleague`() {
        val spent = DwDictationAllowance(designer, "2026-08-12", limit = 40, remaining = 0)
        assertTrue(dwDictationCapView(spent, designer, "2026-08-12").spent)
        val theirs = dwDictationCapView(spent, colleague, "2026-08-12")
        assertFalse("A different account has a different allowance", theirs.spent)
        assertNull("And must not be shown somebody else's ceiling", theirs.limit)
    }

    /**
     * A RECORD FROM ANOTHER DAY FAILS OPEN, WHICH IS THE OPPOSITE DIRECTION FROM CONSENT.
     *
     * Both directions are deliberate. A stale allowance costs one upload per designer per day boundary —
     * the phone tries once and learns the truth from the server — while a phone that failed CLOSED at the
     * wrong midnight would silently withhold a capability that has already been paid for, with nothing on
     * screen naming the cache as the cause. An unknown consent costs a named artisan's recorded voice
     * leaving the device, so that one fails closed. This test is what stops the two being "made
     * consistent".
     */
    @Test
    fun `a refusal from yesterday does not refuse today`() {
        val yesterday = DwDictationAllowance(designer, "2026-08-11", limit = 40, remaining = 0)
        val view = dwDictationCapView(yesterday, designer, "2026-08-12")
        assertFalse("Midnight India time gives the allowance back", view.spent)
        assertNull("And yesterday's ceiling is not today's fact either", view.limit)
    }

    /** A record with no day at all is unusable rather than eternally fresh. */
    @Test
    fun `a record with no day is not trusted`() {
        val dayless = DwDictationAllowance(designer, day = "", limit = 40, remaining = 0)
        assertFalse(dwDictationCapView(dayless, designer, "2026-08-12").spent)
    }

    /**
     * SPENT IS `remaining <= 0`, and the ceiling comes with it so the refusal can name a number.
     *
     * A server that answered `remaining: 0` is saying the last dictation used the last of the allowance,
     * so the NEXT one is the one to refuse. Negative is treated the same way rather than as an error: a
     * cap lowered at noon, or the bounded overshoot the server's own increment can record, leaves a count
     * above the limit and it still means no.
     */
    @Test
    fun `nothing left means the next one is refused, and the ceiling comes with it`() {
        listOf(0, -1, -7).forEach { remaining ->
            val view = dwDictationCapView(
                DwDictationAllowance(designer, "2026-08-12", limit = 40, remaining = remaining),
                designer,
                "2026-08-12",
            )
            assertTrue("remaining=$remaining is spent", view.spent)
            assertEquals(40, view.limit)
        }
    }

    /** One left is not none left. */
    @Test
    fun `an allowance with one left is not spent`() {
        val view = dwDictationCapView(
            DwDictationAllowance(designer, "2026-08-12", limit = 40, remaining = 1),
            designer,
            "2026-08-12",
        )
        assertFalse(view.spent)
        assertEquals(40, view.limit)
    }

    /**
     * AN UNCAPPED DEPLOYMENT CAN NEVER BE SPENT, and "no ceiling" is not "none left".
     *
     * The server sends null for both numbers when there is no cap, precisely so a client cannot render
     * "0 remaining" for a deployment that has no limit at all. A mirror that collapsed the two would
     * withdraw rung 2 from every designer on every uncapped deployment in the fleet.
     */
    @Test
    fun `an uncapped deployment is never spent`() {
        val view = dwDictationCapView(
            DwDictationAllowance(designer, "2026-08-12", limit = null, remaining = null),
            designer,
            "2026-08-12",
        )
        assertFalse(view.spent)
        assertNull(view.limit)
    }

    /** A ceiling of none is a real setting, and it reads as spent with the number kept. */
    @Test
    fun `a ceiling of none reads as spent and keeps the zero`() {
        val view = dwDictationCapView(
            DwDictationAllowance(designer, "2026-08-12", limit = 0, remaining = 0),
            designer,
            "2026-08-12",
        )
        assertTrue(view.spent)
        assertEquals("Zero is a setting, and the sentence for it needs to know: 0", 0, view.limit)
    }

    // ---------------------------------------------------------------------------------------
    // What is written down, and from what
    // ---------------------------------------------------------------------------------------

    /**
     * AN ANSWER WITH NO DAY IN IT IS NOT AN ALLOWANCE, so it overwrites nothing.
     *
     * This is what makes the mirror safe against a deployment older than the cap: it sends none of the
     * keys, and a record stored from it would carry an empty day that no comparison could ever match —
     * which is harmless but would also have destroyed whatever real record was there before.
     */
    @Test
    fun `a dictation from a server that predates the cap writes nothing down`() {
        assertNull(dwDictationAllowanceOf(DwDictateDto(status = "COMPLETED", text = "dabu"), designer))
        assertNull(
            "And nor does one with nobody signed in",
            dwDictationAllowanceOf(DwDictateDto(dictationDay = "2026-08-12"), null),
        )
    }

    /** The four keys, as the server sends them, into the record the mirror keeps. */
    @Test
    fun `a successful dictation writes down the day, the ceiling and what is left`() {
        val allowance = dwDictationAllowanceOf(
            DwDictateDto(
                status = "COMPLETED",
                text = "dabu",
                dictationsLimit = 40,
                dictationsUsed = 28,
                dictationsRemaining = 12,
                dictationDay = "2026-08-12",
            ),
            designer,
        )
        assertEquals(
            DwDictationAllowance(designer, "2026-08-12", limit = 40, remaining = 12),
            allowance,
        )
        assertFalse(dwDictationCapView(allowance, designer, "2026-08-12").spent)
    }

    /**
     * A REFUSAL KEEPS A CEILING THIS PHONE WAS TOLD TODAY, AND INVENTS ONE ON NO OTHER DAY.
     *
     * The number is what plan §6 asks the refusal to name, and the only honest source for it is a
     * dictation that succeeded earlier today. Yesterday's ceiling is not a fact about today's allowance,
     * and a phone that had never been told one prints the sentence without a figure — which is
     * `dwDownloadCostSentence`'s rule about a pack size, applied to a number.
     */
    @Test
    fun `a refusal carries over today's ceiling and no other day's`() {
        val earlierToday = DwDictationAllowance(designer, "2026-08-12", limit = 40, remaining = 3)
        val fromToday = dwDictationCapSpentRecord(earlierToday, designer, "2026-08-12")
        assertEquals(40, fromToday.limit)
        assertEquals(0, fromToday.remaining)
        assertTrue(dwDictationCapView(fromToday, designer, "2026-08-12").spent)

        val yesterday = DwDictationAllowance(designer, "2026-08-11", limit = 40, remaining = 3)
        assertNull(
            "Yesterday's ceiling may not be printed beside today's refusal",
            dwDictationCapSpentRecord(yesterday, designer, "2026-08-12").limit,
        )
        assertNull(
            "Nor a colleague's",
            dwDictationCapSpentRecord(
                DwDictationAllowance(colleague, "2026-08-12", limit = 9, remaining = 0),
                designer,
                "2026-08-12",
            ).limit,
        )
        assertNull(
            "Nor one from nothing at all",
            dwDictationCapSpentRecord(null, designer, "2026-08-12").limit,
        )
    }

    /**
     * A 429 REFUSAL AND THE LADDER, END TO END, WITH NO NUMBER ANYWHERE.
     *
     * The state a first-thing-in-the-morning refusal actually leaves: spent, with no ceiling ever
     * reported, so the ladder must drop rung 2 and take the limit-unknown sentence rather than print a
     * figure or a zero.
     */
    @Test
    fun `a refusal learned before any success drops rung 2 and names no figure`() {
        val record = dwDictationCapSpentRecord(null, designer, "2026-08-12")
        val view = dwDictationCapView(record, designer, "2026-08-12")
        assertTrue(view.spent)
        assertNull(view.limit)

        val plan = dwDictationLadder(
            DwDictationConditions(
                languageLabel = "Odia",
                packState = DwPackState.NO_OFFLINE_PACK,
                onDeviceEngine = true,
                networkRecogniser = true,
                online = true,
                serverRouteUnavailable = false,
                tier3Consent = DwTier3Consent.GRANTED,
                dailyCapSpent = view.spent,
                dailyCapLimit = view.limit,
                // A workshop that has been sent up, so the ONLY thing withholding rung 2 here is the
                // allowance — which is what this test is about. Stated rather than defaulted, for the
                // reason the data class has no default: on this path the value decides whether an
                // artisan's recording leaves the device.
                workshopOnServer = true,
            )
        )
        assertEquals(listOf(DwDictationRung.NETWORK_RECOGNISER), plan.rungs)
        val note = plan.suppressed!!
        assertFalse("No invented ceiling on the panel either: $note", note.any { it.isDigit() })
    }
}
