package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAiVerbAllowanceTest {

    private val designer = "usr_meera"
    private val colleague = "usr_ravi"
    private val today = "2026-08-19"
    private val yesterday = "2026-08-18"

    private fun stored(
        userId: String = designer,
        day: String = today,
        limit: Int? = 25,
        remaining: Int? = 3,
        byVerb: Map<String, Int> = mapOf("PROOFREAD" to 12, "CAPTION" to 10),
    ) = DwAiVerbAllowance(
        userId = userId,
        day = day,
        limit = limit,
        remaining = remaining,
        byVerb = byVerb,
    )

    @Test
    fun `an empty mirror is not a spent allowance`() {
        val view = dwAiVerbCapView(null, designer, today)
        assertFalse(view.spent)
        assertNull(view.limit)
        assertNull(view.remaining)
        assertTrue(view.byVerb.isEmpty())
        assertFalse(view.told)
    }

    @Test
    fun `a told uncapped allowance is not an unknown one`() {
        val uncapped = dwAiVerbCapView(stored(limit = null, remaining = null), designer, today)
        val unknown = dwAiVerbCapView(null, designer, today)

        assertEquals(unknown.limit, uncapped.limit)
        assertEquals(unknown.remaining, uncapped.remaining)
        assertEquals(unknown.spent, uncapped.spent)

        assertTrue(uncapped.told)
        assertFalse(unknown.told)
    }

    @Test
    fun `a stale or borrowed row has told nobody anything`() {
        assertFalse(dwAiVerbCapView(stored(day = yesterday), designer, today).told)
        assertFalse(dwAiVerbCapView(stored(), colleague, today).told)
        assertFalse(dwAiVerbCapView(stored(), null, today).told)
        assertFalse(dwAiVerbCapView(stored(day = ""), designer, today).told)
        assertTrue(dwAiVerbCapView(stored(), designer, today).told)
    }

    @Test
    fun `a signed-out phone holds nobody's allowance`() {
        assertFalse(dwAiVerbCapView(stored(remaining = 0), null, today).spent)
        assertFalse(dwAiVerbCapView(stored(remaining = 0), "  ", today).spent)
    }

    @Test
    fun `one designer's spent day says nothing about the next designer's`() {
        val spent = stored(userId = designer, remaining = 0)
        assertTrue(dwAiVerbCapView(spent, designer, today).spent)

        val forTheColleague = dwAiVerbCapView(spent, colleague, today)
        assertFalse(forTheColleague.spent)
        assertNull(forTheColleague.limit)
    }

    @Test
    fun `yesterday's refusal does not refuse today`() {
        val view = dwAiVerbCapView(stored(day = yesterday, remaining = 0), designer, today)
        assertFalse(view.spent)
        assertNull(view.limit)
        assertNull(view.remaining)
    }

    @Test
    fun `a record with no day at all is not an allowance`() {
        assertFalse(dwAiVerbCapView(stored(day = "", remaining = 0), designer, today).spent)
    }

    @Test
    fun `nothing remaining means the next press is the one refused`() {
        assertTrue(dwAiVerbCapView(stored(remaining = 0), designer, today).spent)
        assertTrue(dwAiVerbCapView(stored(remaining = -4), designer, today).spent)
        assertFalse(dwAiVerbCapView(stored(remaining = 1), designer, today).spent)
    }

    @Test
    fun `no ceiling is not an exhausted ceiling`() {
        val view = dwAiVerbCapView(stored(limit = null, remaining = null), designer, today)
        assertFalse(view.spent)
        assertNull(view.limit)
        assertNull(view.remaining)
    }

    @Test
    fun `a ceiling of zero is spent and keeps its number`() {
        val view = dwAiVerbCapView(stored(limit = 0, remaining = 0), designer, today)
        assertTrue(view.spent)
        assertEquals(0, view.limit)
    }

    @Test
    fun `an answer that named no day is not written down`() {
        val noDay = DwAiVerbResultDto(aiVerbsLimit = 25, aiVerbsUsed = 22, aiVerbsRemaining = 3)
        assertNull(dwAiVerbAllowanceOf(noDay, designer))

        val blankDay = DwAiVerbResultDto(aiVerbDay = "   ", aiVerbsRemaining = 0)
        assertNull(dwAiVerbAllowanceOf(blankDay, designer))
    }

    @Test
    fun `an answer with nobody signed in is not written down`() {
        val dto = DwAiVerbResultDto(aiVerbDay = today, aiVerbsRemaining = 3)
        assertNull(dwAiVerbAllowanceOf(dto, null))
        assertNull(dwAiVerbAllowanceOf(dto, ""))
    }

    @Test
    fun `a run's own answer is what the mirror learns from`() {
        val dto = DwAiVerbResultDto(
            aiVerbsLimit = 25,
            aiVerbsUsed = 22,
            aiVerbsRemaining = 3,
            aiVerbDay = today,
            aiVerbsByVerb = mapOf("PROOFREAD" to 12, "CAPTION" to 10),
        )
        val learned = dwAiVerbAllowanceOf(dto, designer)!!
        assertEquals(designer, learned.userId)
        assertEquals(today, learned.day)
        assertEquals(25, learned.limit)
        assertEquals(3, learned.remaining)
        assertEquals(mapOf("PROOFREAD" to 12, "CAPTION" to 10), learned.byVerb)

        val view = dwAiVerbCapView(learned, designer, today)
        assertFalse(view.spent)
        assertEquals(DW_AI_VERB_COUNTDOWN_FROM, view.remaining)
    }

    @Test
    fun `an uncapped answer is stored as uncapped and not as unknown`() {
        val dto = DwAiVerbResultDto(aiVerbDay = today, aiVerbsUsed = 9, aiVerbsByVerb = mapOf("EXPAND" to 9))
        val learned = dwAiVerbAllowanceOf(dto, designer)!!
        assertNull(learned.limit)
        assertNull(learned.remaining)
        assertFalse(dwAiVerbCapView(learned, designer, today).spent)
    }

    @Test
    fun `a refusal keeps today's known ceiling and drops yesterday's`() {
        val fromToday = dwAiVerbCapSpentRecord(stored(limit = 25), designer, today)
        assertEquals(0, fromToday.remaining)
        assertEquals(25, fromToday.limit)
        assertEquals(today, fromToday.day)
        assertTrue(dwAiVerbCapView(fromToday, designer, today).spent)

        val fromYesterday = dwAiVerbCapSpentRecord(stored(day = yesterday, limit = 25), designer, today)
        assertEquals(0, fromYesterday.remaining)
        assertNull("yesterday's ceiling is not today's", fromYesterday.limit)
        assertTrue(fromYesterday.byVerb.isEmpty())
    }

    @Test
    fun `a refusal does not inherit another designer's ceiling`() {
        val record = dwAiVerbCapSpentRecord(stored(userId = colleague, limit = 25), designer, today)
        assertEquals(designer, record.userId)
        assertNull(record.limit)
        assertEquals(0, record.remaining)
    }

    @Test
    fun `a refusal on a phone that knew nothing still refuses`() {
        val record = dwAiVerbCapSpentRecord(null, designer, today)
        assertNull(record.limit)
        assertTrue(dwAiVerbCapView(record, designer, today).spent)
    }

    @Test
    fun `only the refusal that says when to try again is treated as transient`() {
        val cap = DwAiVerbCapRefused(detail = "You have used all 25 of today's runs.", retryAfterSeconds = null)
        assertFalse(cap.transientThrottle)

        val throttle = DwAiVerbCapRefused(detail = "Too many requests.", retryAfterSeconds = 3)
        assertTrue(throttle.transientThrottle)
    }

    @Test
    fun `a bodiless 429 is read as the ceiling and not as a burst`() {
        assertFalse(DwAiVerbCapRefused(detail = null, retryAfterSeconds = null).transientThrottle)
    }

    @Test
    fun `an unavailable verb is named, and its sentence is the server's`() {
        val words = "Subtitling is not configured on this server: no provider returns word timings."
        val refusal = DwAiVerbNotConfigured(DwAiVerb.SUBTITLES, words)
        assertEquals(DwAiVerb.SUBTITLES, refusal.verb)
        assertEquals(words, refusal.detail)
        assertEquals(words, refusal.message)
    }

    @Test
    fun `a refusal carries the server's words and a bodiless one says only what it can`() {
        val wrongFile = DwAiVerbRefused(
            409,
            "Describing a photograph needs a photograph or a video, and that file is audio.",
        )
        assertEquals(409, wrongFile.status)
        assertTrue(wrongFile.message!!.contains("that file is audio"))

        val stripped = DwAiVerbRefused(409, null)
        assertNull(stripped.detail)
        assertTrue(stripped.message!!.contains("409"))
    }
}
