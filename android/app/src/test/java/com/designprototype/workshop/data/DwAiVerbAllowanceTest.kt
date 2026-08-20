package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handset's mirror of the server's daily allowance for the five AI verbs, on the desktop JVM.
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────────
 *
 * The mirror decides whether a designer's next press is attempted at all, and every way it can be
 * wrong is silent. Too eager and it withholds a capability that has already been paid for, with nothing
 * on screen naming a stale copy as the cause. Too slack and every control on the stage spends its own
 * round trip to be told the same thing. Neither shows up as a crash.
 *
 * ── AND THE ONE THING IT MUST NOT DO, WHICH IS THE OBVIOUS THING ──────────────────────────────────
 *
 * **`aiVerbsRemaining` IS NULL ON AN UNCAPPED DEPLOYMENT AND 0 ON A SPENT ONE, AND THE `?: 0` AN
 * IMPLEMENTER REACHES FOR TURNS THE FIRST INTO THE SECOND** — which withdraws all five verbs from
 * every handset on a deployment that never had a ceiling at all. `allowance_payload` states it: both
 * numbers are null when there is no cap, *because 0 remaining and "no ceiling" must not look alike*.
 *
 * ── THE DAY BOUNDARY IS DELIBERATELY NOT RE-TESTED HERE ───────────────────────────────────────────
 *
 * It is [dwDictationIstDay] — the same function, not a second one — and `DwDictationAllowanceTest`
 * asserts it from both sides of midnight IST. A second reckoning of the boundary, even a correct one,
 * would be a second definition, and the first day the two disagreed a designer would hold two
 * allowances or none. What is tested here is the FRESHNESS rule that consumes it.
 *
 * NOT EXECUTED IN THIS WORKING COPY: there is no gradle here, so every assertion was written to be
 * checkable by reading and none has been run.
 */
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

    // ---------------------------------------------------------------------------------------
    // The freshness rule
    // ---------------------------------------------------------------------------------------

    /** Nothing stored: no verb has succeeded or been refused on this phone yet. Not spent. */
    @Test
    fun `an empty mirror is not a spent allowance`() {
        val view = dwAiVerbCapView(null, designer, today)
        assertFalse(view.spent)
        assertNull(view.limit)
        assertNull(view.remaining)
        assertTrue(view.byVerb.isEmpty())
    }

    /** Nobody signed in: read as unknown rather than as the last account's allowance. */
    @Test
    fun `a signed-out phone holds nobody's allowance`() {
        assertFalse(dwAiVerbCapView(stored(remaining = 0), null, today).spent)
        assertFalse(dwAiVerbCapView(stored(remaining = 0), "  ", today).spent)
    }

    /**
     * **THE CEILING IS PER DESIGNER, AND A FIELD PHONE IS HANDED BETWEEN THEM.** One designer's spent
     * afternoon must not refuse the colleague who signs in after them, and one designer's ceiling must
     * not be printed to another.
     */
    @Test
    fun `one designer's spent day says nothing about the next designer's`() {
        val spent = stored(userId = designer, remaining = 0)
        assertTrue(dwAiVerbCapView(spent, designer, today).spent)

        val forTheColleague = dwAiVerbCapView(spent, colleague, today)
        assertFalse(forTheColleague.spent)
        assertNull(forTheColleague.limit)
    }

    /**
     * A STALE RECORD RESOLVES TO NOT SPENT, which is the opposite direction from consent and both are
     * deliberate: an unknown CONSENT costs a named artisan's voice leaving the device, so it fails
     * closed, while a stale ALLOWANCE costs one round trip per designer per day boundary — the phone
     * tries once and learns the truth from the server.
     */
    @Test
    fun `yesterday's refusal does not refuse today`() {
        val view = dwAiVerbCapView(stored(day = yesterday, remaining = 0), designer, today)
        assertFalse(view.spent)
        // And yesterday's ceiling is not printed beside today's count either.
        assertNull(view.limit)
        assertNull(view.remaining)
    }

    @Test
    fun `a record with no day at all is not an allowance`() {
        assertFalse(dwAiVerbCapView(stored(day = "", remaining = 0), designer, today).spent)
    }

    // ---------------------------------------------------------------------------------------
    // Zero is not null
    // ---------------------------------------------------------------------------------------

    /**
     * `remaining: 0` MEANS THE LAST RUN USED THE LAST OF THE ALLOWANCE, so the NEXT one is the one to
     * refuse. `>= 0` rather than `> 0` is why the server compares `used >= limit`: a master admin who
     * lowers the cap at noon must not hand every designer already over it an unbounded afternoon.
     */
    @Test
    fun `nothing remaining means the next press is the one refused`() {
        assertTrue(dwAiVerbCapView(stored(remaining = 0), designer, today).spent)
        assertTrue(dwAiVerbCapView(stored(remaining = -4), designer, today).spent)
        assertFalse(dwAiVerbCapView(stored(remaining = 1), designer, today).spent)
    }

    /**
     * **AN UNCAPPED DEPLOYMENT CAN NEVER BE SPENT.** This is the assertion that would fail on the day
     * somebody writes `remaining ?: 0`, and the failure it prevents is the whole feature disappearing
     * from every handset on a deployment that has no ceiling configured.
     */
    @Test
    fun `no ceiling is not an exhausted ceiling`() {
        val view = dwAiVerbCapView(stored(limit = null, remaining = null), designer, today)
        assertFalse(view.spent)
        assertNull(view.limit)
        assertNull(view.remaining)
    }

    /**
     * A CEILING OF 0 IS A REAL SETTING — these verbs switched off — and it is not the same fact as "no
     * ceiling" or as "this phone has not been told one". All three read differently to a designer, and
     * the sentence for the zero case is the server's to write rather than this client's to guess.
     */
    @Test
    fun `a ceiling of zero is spent and keeps its number`() {
        val view = dwAiVerbCapView(stored(limit = 0, remaining = 0), designer, today)
        assertTrue(view.spent)
        assertEquals(0, view.limit)
    }

    // ---------------------------------------------------------------------------------------
    // Learning the numbers off a 201
    // ---------------------------------------------------------------------------------------

    /**
     * **A RESPONSE WITH NO `aiVerbDay` IS NOT AN ALLOWANCE.** The day is what the whole freshness rule
     * turns on, so a payload without one must leave whatever is stored alone rather than overwrite it
     * with a record that can never match a day — a record that would then make every press pay its
     * round trip for the rest of the deployment's life.
     */
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

    /** The 201's five numbers land in the mirror under this designer's id and the SERVER's day. */
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

        // And it resolves as not spent, with three left — which is exactly the countdown threshold.
        val view = dwAiVerbCapView(learned, designer, today)
        assertFalse(view.spent)
        assertEquals(DW_AI_VERB_COUNTDOWN_FROM, view.remaining)
    }

    /**
     * AN UNCAPPED SERVER'S 201 IS STILL WORTH STORING, because the day and the breakdown are facts
     * even where the ceiling is not — and because a stored null `remaining` is what makes the next
     * press go ahead without a round trip, rather than being read as "not told".
     */
    @Test
    fun `an uncapped answer is stored as uncapped and not as unknown`() {
        val dto = DwAiVerbResultDto(aiVerbDay = today, aiVerbsUsed = 9, aiVerbsByVerb = mapOf("EXPAND" to 9))
        val learned = dwAiVerbAllowanceOf(dto, designer)!!
        assertNull(learned.limit)
        assertNull(learned.remaining)
        assertFalse(dwAiVerbCapView(learned, designer, today).spent)
    }

    // ---------------------------------------------------------------------------------------
    // Learning it off a 429
    // ---------------------------------------------------------------------------------------

    /**
     * **THE VERB ROUTES' 429 CARRIES NO NUMBERS AT ALL**, which is the difference from the 201 and is
     * read off `_verb_gate` rather than assumed: it raises with `detail=` the cap sentence and nothing
     * else. So a refusal record is `remaining = 0` plus whatever ceiling this phone was ALREADY told
     * about today — and a ceiling learned yesterday is not a fact about today's, so it is dropped.
     */
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

    /** A colleague's stored ceiling is never carried onto this designer's refusal. */
    @Test
    fun `a refusal does not inherit another designer's ceiling`() {
        val record = dwAiVerbCapSpentRecord(stored(userId = colleague, limit = 25), designer, today)
        assertEquals(designer, record.userId)
        assertNull(record.limit)
        assertEquals(0, record.remaining)
    }

    /** With nothing stored at all, the refusal is still a refusal — with no number to name. */
    @Test
    fun `a refusal on a phone that knew nothing still refuses`() {
        val record = dwAiVerbCapSpentRecord(null, designer, today)
        assertNull(record.limit)
        assertTrue(dwAiVerbCapView(record, designer, today).spent)
    }

    // ---------------------------------------------------------------------------------------
    // Telling the cap from the courtesy limiter
    // ---------------------------------------------------------------------------------------

    /**
     * TWO THINGS ANSWER 429 AND THEY WANT OPPOSITE HANDLING. The cap will not clear until midnight IST
     * and is worth remembering; `app/scale/rate_limit.py` — whose own docstring calls itself *"NOT a
     * security control"* — is about this instant, carries `retryAfterSeconds`, and must NOT be
     * remembered, or a burst of taps would withdraw all five verbs for the rest of the day.
     *
     * TOLD APART BY SHAPE AND NOT BY ENGLISH: matching prose would break the first time somebody
     * reworded a sentence, and this client has already been bitten by matching a provider's message
     * rather than its status.
     */
    @Test
    fun `only the refusal that says when to try again is treated as transient`() {
        val cap = DwAiVerbCapRefused(detail = "You have used all 25 of today's runs.", retryAfterSeconds = null)
        assertFalse(cap.transientThrottle)

        val throttle = DwAiVerbCapRefused(detail = "Too many requests.", retryAfterSeconds = 3)
        assertTrue(throttle.transientThrottle)
    }

    /**
     * A BODY WITH NEITHER KEY IS THE CAP. That is a decision with a cost, so it is asserted rather than
     * left implicit: read as the cap, a mistake costs the five verbs until midnight IST and says so in
     * words; read as transient, a mistake spends a round trip per press for the rest of the day. The
     * bounded, visible, self-clearing error is the one to prefer.
     */
    @Test
    fun `a bodiless 429 is read as the ceiling and not as a burst`() {
        assertFalse(DwAiVerbCapRefused(detail = null, retryAfterSeconds = null).transientThrottle)
    }

    // ---------------------------------------------------------------------------------------
    // The 503, which is per verb
    // ---------------------------------------------------------------------------------------

    /**
     * **THE 503 IS A FACT ABOUT ONE VERB AND NOT ABOUT THE SERVER**, which is why the type carries the
     * verb. A deployment with only an OpenAI key can proofread all day and cannot subtitle at all,
     * because that rung is asked for `response_format=json`, which carries no timings. A single "the
     * server has no AI" flag would retire four working verbs on the first failed subtitle.
     */
    @Test
    fun `an unavailable verb is named, and its sentence is the server's`() {
        val words = "Subtitling is not configured on this server: no provider returns word timings."
        val refusal = DwAiVerbNotConfigured(DwAiVerb.SUBTITLES, words)
        assertEquals(DwAiVerb.SUBTITLES, refusal.verb)
        assertEquals(words, refusal.detail)
        assertEquals(words, refusal.message)
    }

    /**
     * EVERY OTHER REFUSAL KEEPS ITS CODE AND ITS SENTENCE AND IS CLASSIFIED NO FURTHER. A verb route
     * answers 409 for four different states — a deleted workshop, the consent gate, a layer that holds
     * structured data rather than prose, and a file of the wrong kind for the verb — with no
     * discriminator in the body. A client that named it "consent" would tell a designer who picked an
     * audio file for a caption to go and ask an artisan a question that is already on record.
     */
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
