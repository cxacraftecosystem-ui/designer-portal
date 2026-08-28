package com.designprototype.workshop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PROPERTY, NOT THE PROSE — the same bar [TaskPickerCopyTest] sets, for the same kind of
 * function.
 *
 * `GET /review/pending` caps each of six record types and orders each source newest first, so the
 * rows it drops are the OLDEST. Nothing on either client can page or search past them: that route
 * takes no `page`, `pageSize` or search parameter at all. Four properties carry the feature, and
 * every one of them would be a defect if it changed:
 *
 *  1. A queue that was not cut says NOTHING. A standing notice over a complete queue teaches a
 *     reviewer to ignore the notice, which is worse than never having written one.
 *  2. A cut queue never sends the reader to a search box or tells them to narrow anything. There is
 *     no box, and the closed viewer-picker finding (docs/OPEN_FINDINGS.md, 2026-08-13) is on record
 *     for what telling somebody to narrow an unnarrowable list costs.
 *  3. It says the queue was cut EVEN WHEN the numbers are missing or contradictory. The flag is the
 *     server's answer; silence in that state is an unstated cut, which is the whole defect.
 *  4. It never prints arithmetic that contradicts itself.
 *
 * The wordings are the handset's copy of `queueCutNotice`
 * (frontend/components/data/cappedList.ts). TRUE AS OF 2026-08-27, re-check with:
 *
 *     grep -n "export function queueCutNotice" -A 20 frontend/components/data/cappedList.ts
 */
class ReviewQueueCopyTest {

    @Test
    fun `a queue that was not cut says nothing at all`() {
        assertNull(reviewQueueCutNotice(truncated = false, shown = 200, total = 200, cap = 200))
        // Not even when the numbers alone would look like a cut: the server decides this by reading
        // one row beyond the cap, and the flag is the answer.
        assertNull(reviewQueueCutNotice(truncated = false, shown = 20, total = 340, cap = 200))
    }

    @Test
    fun `a cut queue never sends the reviewer to a search box`() {
        val said = reviewQueueCutNotice(truncated = true, shown = 200, total = 340, cap = 200)!!
        assertFalse(
            "this route takes no search parameter, so there is nothing to search or narrow",
            said.contains("search", ignoreCase = true) || said.contains("narrow", ignoreCase = true),
        )
        assertTrue("both numbers, or the reader cannot tell how much is missing", said.contains("200"))
        assertTrue(said.contains("340"))
        assertTrue("and what actually brings the rest forward", said.contains("oldest"))
    }

    @Test
    fun `a cut is still stated when the server sent no cap`() {
        // What a deployment predating the `cap` key produces. The cut must still be announced, and
        // the sentence must not claim a ceiling of zero.
        val said = reviewQueueCutNotice(truncated = true, shown = 200, total = 340, cap = 0)!!
        assertTrue(said.contains("not shown") || said.contains("oldest"))
        assertFalse("a cap that was never sent must not be printed", said.contains("0 of each"))
    }

    @Test
    fun `it never prints arithmetic that contradicts itself`() {
        // `total <= shown` cannot happen beside a true flag from a server that sends both, but a
        // sentence reading "Showing 200 of 200" under a cut queue would be nonsense on screen.
        val said = reviewQueueCutNotice(truncated = true, shown = 200, total = 200, cap = 200)!!
        assertFalse(said.contains("Showing 200 of 200"))
        assertTrue("it still says the queue was cut", said.contains("not shown"))
        // And the state the server says it cannot produce — a cut answer is never an empty one —
        // is handled rather than printed as "Showing 0 of 340".
        val empty = reviewQueueCutNotice(truncated = true, shown = 0, total = 340, cap = 200)!!
        assertFalse(empty.contains("Showing 0"))
        assertTrue(empty.contains("not an empty queue"))
    }
}
