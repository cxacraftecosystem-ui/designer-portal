package com.designprototype.workshop.ui

import com.designprototype.workshop.data.PLACE_SEARCH_MIN_QUERY_LENGTH
import com.designprototype.workshop.data.PlaceHit
import com.designprototype.workshop.data.PlaceSearchException
import com.designprototype.workshop.data.describePlaceSearchFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence the designer is shown, for every way the search can end.
 *
 * WHY THIS IS A SEPARATE FILE FROM `PlaceSearchParityTest`. That file diffs the port against
 * `frontend/lib/placeSearch.ts` and can therefore only cover what the web module owns — the request,
 * the parse, and the three FAILURE sentences. The fourth outcome is not in it: an empty list from a
 * request that SUCCEEDED is not a failure, so `placeSearch.ts` deliberately declines to word it and
 * leaves it to the caller. On this client the caller is [placeSearchStatusLine], and until this file
 * existed that branch was reachable by no test at all.
 *
 * WHY THE FOURTH OUTCOME IS THE ONE WORTH GUARDING. The three failures are told apart by an HTTP
 * status, which is machine-checkable and is checked. "There is no such village" is told apart from
 * them by nothing but this function, and the wrong answer here is the one that costs the most in the
 * field: a designer standing in Barpali with one bar of signal, told the village does not exist,
 * stops searching and starts doubting their spelling. The whole point of splitting these apart is
 * that the designer's NEXT MOVE differs — wait, type more, try another name, or give up on the
 * search and place the pin by hand — and a screen that says "No results" to all four hides which.
 *
 * The assertions are therefore mostly about DISTINCTNESS rather than about exact wording. Copy is
 * allowed to be edited; four outcomes collapsing into three is the regression.
 */
class PlaceSearchMessageTest {

    private val hit = PlaceHit(
        id = "place.2592481",
        name = "Barpali",
        context = "Paikmal, Odisha, India",
        lon = 82.74589028209448,
        lat = 20.89405824525621
    )

    private fun line(
        state: PlaceSearchState,
        query: String = "Barpali",
        hits: List<PlaceHit> = emptyList(),
        problem: String? = null
    ) = placeSearchStatusLine(state, query, hits, problem)

    /** The three failure sentences, as [describePlaceSearchFailure] actually produces them. */
    private val offline = describePlaceSearchFailure(PlaceSearchException(null, "x"))
    private val serviceDown = describePlaceSearchFailure(PlaceSearchException(500, "x"))
    private val refused = describePlaceSearchFailure(PlaceSearchException(403, "x"))

    /**
     * A SUCCESSFUL SEARCH THAT FOUND NOTHING MUST NOT READ AS A FAILURE.
     *
     * This is the assertion the file exists for. Wiring the empty-list branch to any of the three
     * failure sentences is a one-line edit, it looks tidier than a fourth string, and nothing else in
     * this repository would notice — the parity test would still pass, because the web has no
     * counterpart for this branch to be diffed against.
     */
    @Test
    fun `no results is its own sentence and not any of the three failures`() {
        val empty = line(PlaceSearchState.Done)

        assertNotEquals("no results must not be worded as the offline failure", offline, empty)
        assertNotEquals("no results must not be worded as the service failure", serviceDown, empty)
        assertNotEquals("no results must not be worded as the refusal", refused, empty)

        // The three failures each say what a designer should DO next, and none of those moves is the
        // right one here. Naming "connection" over a request that succeeded is the specific lie: it
        // sends somebody out of a courtyard looking for signal they already had.
        assertTrue(empty, !empty.contains("connection", ignoreCase = true))
        assertTrue(empty, !empty.contains("key", ignoreCase = true))
        assertTrue(empty, !empty.contains("Try again", ignoreCase = true))
    }

    /**
     * It quotes what was actually typed.
     *
     * The designer's next move is almost always to re-read their own spelling, and a village name
     * transliterated from Odia can be spelt four ways. Showing the query back is what makes that
     * possible without retyping it.
     */
    @Test
    fun `no results names the query it searched for`() {
        assertTrue(line(PlaceSearchState.Done, query = "Barpalii").contains("Barpalii"))
        // Trimmed, because the query is trimmed before it is sent: quoting the untrimmed string would
        // show the designer a name with a space they cannot see and did not mean to type.
        assertTrue(line(PlaceSearchState.Done, query = "  Bagru  ").contains("“Bagru”"))
    }

    /**
     * The failure sentence reaches the screen VERBATIM.
     *
     * `describePlaceSearchFailure` is where the three buckets are decided and it is diffed against
     * the web — but that is worth nothing if this function paraphrases the result on the way out.
     * These are the same three strings, asserted through the function that actually renders them.
     */
    @Test
    fun `each failure bucket is passed through unaltered and the three stay distinct`() {
        assertEquals(offline, line(PlaceSearchState.Failed, problem = offline))
        assertEquals(serviceDown, line(PlaceSearchState.Failed, problem = serviceDown))
        assertEquals(refused, line(PlaceSearchState.Failed, problem = refused))

        assertEquals(
            "the three failure buckets must be three different sentences",
            3,
            setOf(offline, serviceDown, refused).size
        )
    }

    /**
     * A failure with nothing to say still says something.
     *
     * `problem` is nullable and a blank panel under a search box reads as a hung app rather than as a
     * failed request.
     */
    @Test
    fun `a failure with no description still produces a sentence`() {
        val fallback = line(PlaceSearchState.Failed, problem = null)
        assertTrue(fallback, fallback.isNotBlank())
    }

    /**
     * "Type more" is advice, not an error, and it names the number.
     *
     * A designer who has typed "Ba" and is told only that the search failed will try again with "Ba".
     */
    @Test
    fun `too short says how many letters are needed`() {
        val short = line(PlaceSearchState.Short, query = "Ba")
        assertTrue(short, short.contains(PLACE_SEARCH_MIN_QUERY_LENGTH.toString()))
        assertNotEquals(offline, short)
    }

    /** Nothing has been typed, so the panel claims nothing. */
    @Test
    fun `idle is silent`() {
        assertEquals("", line(PlaceSearchState.Idle, query = ""))
    }

    /**
     * All five states produce five different lines.
     *
     * Walked as a set rather than asserted pairwise: the failure being guarded against is two
     * outcomes collapsing into one, and which two is not knowable in advance.
     */
    @Test
    fun `every outcome is distinguishable from every other`() {
        val lines = listOf(
            line(PlaceSearchState.Idle, query = ""),
            line(PlaceSearchState.Short, query = "Ba"),
            line(PlaceSearchState.Searching),
            line(PlaceSearchState.Done),
            line(PlaceSearchState.Done, hits = listOf(hit)),
            line(PlaceSearchState.Failed, problem = offline),
            line(PlaceSearchState.Failed, problem = serviceDown),
            line(PlaceSearchState.Failed, problem = refused)
        )
        assertEquals("two outcomes are wearing the same sentence: $lines", lines.size, lines.toSet().size)
    }

    /** One place is not "1 places". Small, and it is the line a designer reads most often. */
    @Test
    fun `the found count is singular for one hit`() {
        assertTrue(line(PlaceSearchState.Done, hits = listOf(hit)).contains("1 place found"))
        assertTrue(line(PlaceSearchState.Done, hits = listOf(hit, hit)).contains("2 places found"))
    }
}
