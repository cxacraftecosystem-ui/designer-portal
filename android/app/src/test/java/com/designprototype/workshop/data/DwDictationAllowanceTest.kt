package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwDictationAllowanceTest {

    private val designer = "usr_meera"
    private val colleague = "usr_ravi"

    @Test
    fun `the day turns at midnight India time and not at midnight UTC`() {
        val lastSecond = java.time.Instant.parse("2026-08-12T18:29:59Z").toEpochMilli()
        assertEquals("2026-08-12", dwDictationIstDay(lastSecond))

        val firstSecond = java.time.Instant.parse("2026-08-12T18:30:00Z").toEpochMilli()
        assertEquals("2026-08-13", dwDictationIstDay(firstSecond))

        val utcMidnight = java.time.Instant.parse("2026-08-13T00:00:00Z").toEpochMilli()
        assertEquals("2026-08-13", dwDictationIstDay(utcMidnight))
    }

    @Test
    fun `the day is a plain calendar date, which is what the server keys its row by`() {
        val day = dwDictationIstDay(java.time.Instant.parse("2026-01-05T04:00:00Z").toEpochMilli())
        assertEquals("2026-01-05", day)
        assertTrue("It must sort chronologically as text", "2026-01-05" < "2026-01-06")
    }

    @Test
    fun `a phone that has been told nothing is not spent and names no number`() {
        val view = dwDictationCapView(stored = null, userId = designer, today = "2026-08-12")
        assertFalse(view.spent)
        assertNull(view.limit)
    }

    @Test
    fun `with nobody signed in there is no allowance to read`() {
        val stored = DwDictationAllowance(designer, "2026-08-12", limit = 40, remaining = 0)
        listOf(null, "", "   ").forEach { userId ->
            val view = dwDictationCapView(stored, userId, "2026-08-12")
            assertFalse("An unsigned phone must not inherit a refusal: $userId", view.spent)
            assertNull(view.limit)
        }
    }

    @Test
    fun `one designer's spent day does not refuse their colleague`() {
        val spent = DwDictationAllowance(designer, "2026-08-12", limit = 40, remaining = 0)
        assertTrue(dwDictationCapView(spent, designer, "2026-08-12").spent)
        val theirs = dwDictationCapView(spent, colleague, "2026-08-12")
        assertFalse("A different account has a different allowance", theirs.spent)
        assertNull("And must not be shown somebody else's ceiling", theirs.limit)
    }

    @Test
    fun `a refusal from yesterday does not refuse today`() {
        val yesterday = DwDictationAllowance(designer, "2026-08-11", limit = 40, remaining = 0)
        val view = dwDictationCapView(yesterday, designer, "2026-08-12")
        assertFalse("Midnight India time gives the allowance back", view.spent)
        assertNull("And yesterday's ceiling is not today's fact either", view.limit)
    }

    @Test
    fun `a record with no day is not trusted`() {
        val dayless = DwDictationAllowance(designer, day = "", limit = 40, remaining = 0)
        assertFalse(dwDictationCapView(dayless, designer, "2026-08-12").spent)
    }

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

    @Test
    fun `a dictation from a server that predates the cap writes nothing down`() {
        assertNull(dwDictationAllowanceOf(DwDictateDto(status = "COMPLETED", text = "dabu"), designer))
        assertNull(
            "And nor does one with nobody signed in",
            dwDictationAllowanceOf(DwDictateDto(dictationDay = "2026-08-12"), null),
        )
    }

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
                workshopOnServer = true,
            )
        )
        assertEquals(listOf(DwDictationRung.NETWORK_RECOGNISER), plan.rungs)
        val note = plan.suppressed!!
        assertFalse("No invented ceiling on the panel either: $note", note.any { it.isDigit() })
    }
}
