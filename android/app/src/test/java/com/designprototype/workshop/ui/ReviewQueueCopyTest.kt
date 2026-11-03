package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueCopyTest {

    @Test
    fun `a queue that is all on screen says nothing at all`() {
        assertNull(reviewQueueCutNotice(truncated = false, shown = 200, total = 200, cap = 200))
        assertNull(reviewQueueCutNotice(truncated = false, shown = 20, total = 340, cap = 50))
    }

    @Test
    fun `a partly loaded queue is a count, newest first`() {
        assertEquals(
            "50 of 340 waiting, newest first.",
            reviewQueueCutNotice(truncated = true, shown = 50, total = 340, cap = 50),
        )
    }

    @Test
    fun `it never narrates the server's limits or admits a gap`() {
        val said = reviewQueueCutNotice(truncated = true, shown = 200, total = 340, cap = 200)!!
        for (leak in listOf("at most", "limit", "holds", "oldest", "cannot", "not shown")) {
            assertFalse("\"$leak\" describes the implementation, not the queue: $said", said.contains(leak))
        }
    }

    @Test
    fun `it never prints arithmetic that contradicts itself`() {
        val whole = reviewQueueCutNotice(truncated = true, shown = 200, total = 200, cap = 200)!!
        assertFalse(whole.contains("Showing 200 of 200"))
        assertTrue(whole.contains("Load older"))
        val empty = reviewQueueCutNotice(truncated = true, shown = 0, total = 340, cap = 50)!!
        assertFalse(empty.contains("Showing 0"))
        assertTrue(empty.contains("Try again"))
    }
}
