package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxOwnerAccountTest {

    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun entry(
        id: String,
        owner: String? = null,
        failure: String? = null,
    ) = PendingEntry(
        id = id,
        type = "artisan",
        payloadJson = """{"name":"Giriraj Prasad"}""",
        label = "Giriraj Prasad",
        createdAt = "2026-09-02T10:00:00Z",
        failure = failure,
        ownerUserId = owner,
    )

    @Test
    fun `an entry another account captured is not this account's to send`() {
        assertTrue(
            dwDraftIsForAnotherAccount(entry("e1", owner = "designer-A").ownerUserId, "designer-B"),
        )
        assertFalse(
            "and the owner's own entries go up exactly as they always did",
            dwDraftIsForAnotherAccount(entry("e2", owner = "designer-A").ownerUserId, "designer-A"),
        )
    }

    @Test
    fun `an entry queued before this field existed decodes as ownerless and still drains`() {
        val onDisk = """
            {"id":"old-1","type":"artisan","payloadJson":"{\"name\":\"Sushila Meher\"}",
             "label":"Sushila Meher","media":[],"createdAt":"2026-08-20T09:00:00Z",
             "createdStepIds":[],"uploadedMedia":[],"conflict":false}
        """.trimIndent()
        val decoded = queueJson.decodeFromString(PendingEntry.serializer(), onDisk)

        assertNull("the new state must be absent, not invented", decoded.ownerUserId)
        assertFalse(
            "a null owner passes for ANY signed-in account, which is what keeps the fleet draining",
            dwDraftIsForAnotherAccount(decoded.ownerUserId, "designer-B"),
        )
        assertEquals("artisan", decoded.type)
        assertNull(decoded.failure)
    }

    @Test
    fun `the stamp survives a write and a read of the queue file`() {
        val stamped = entry("e1", owner = "designer-A")
        val round = queueJson.decodeFromString(
            PendingEntry.serializer(),
            queueJson.encodeToString(PendingEntry.serializer(), stamped),
        )
        assertEquals("designer-A", round.ownerUserId)
        assertTrue(dwDraftIsForAnotherAccount(round.ownerUserId, "designer-B"))
    }

    @Test
    fun `nobody signed in is not a mismatch`() {
        assertFalse(dwDraftIsForAnotherAccount("designer-A", null))
        assertFalse(dwDraftIsForAnotherAccount(null, null))
    }

    @Test
    fun `the counts are a partition and another account's entry is not counted as waiting`() {
        val counts = outboxCountsOf(
            listOf(
                entry("mine-1"),
                entry("mine-2", owner = "designer-B"),
                entry("theirs-1", owner = "designer-A"),
                entry("theirs-2", owner = "designer-A"),
                entry("refused-1", owner = "designer-A", failure = "Name is too long"),
            ),
            signedInUserId = "designer-B",
        )

        assertEquals("an ownerless entry and this account's own are both waiting", 2, counts.waiting)
        assertEquals(2, counts.otherAccount)
        assertEquals(1, counts.refused)
        assertEquals(5, counts.waiting + counts.otherAccount + counts.refused)
        assertFalse(counts.isEmpty)
    }

    @Test
    fun `with the owner signed in nothing is withheld`() {
        val counts = outboxCountsOf(
            listOf(entry("a", owner = "designer-A"), entry("b", owner = "designer-A"), entry("c")),
            signedInUserId = "designer-A",
        )
        assertEquals(3, counts.waiting)
        assertEquals(0, counts.otherAccount)
    }

    @Test
    fun `a queue that is only another account's is not an empty queue`() {
        val counts = outboxCountsOf(listOf(entry("a", owner = "designer-A")), "designer-B")
        assertFalse(counts.isEmpty)
        assertEquals(0, counts.waiting)
    }

    @Test
    fun `another account's refusal is listed, flagged, and not actionable`() {
        val rows = outboxFailureRows(
            listOf(
                entry("mine", owner = "designer-B", failure = "Name is too long"),
                entry("theirs", owner = "designer-A", failure = "Only the person who recorded this may change it"),
                entry("ownerless", failure = "The server refused this"),
            ),
            signedInUserId = "designer-B",
        )

        assertEquals(3, rows.size)
        val theirs = rows.single { it.entryId == "theirs" }
        assertTrue("the tray reads this flag and draws no buttons on the row", theirs.otherAccount)
        assertEquals(
            "and the server's own sentence is still under it",
            "Only the person who recorded this may change it",
            theirs.reason,
        )
        assertFalse(rows.single { it.entryId == "mine" }.otherAccount)
        assertFalse(rows.single { it.entryId == "ownerless" }.otherAccount)
    }

    @Test
    fun `with nobody signed in no row is another account's`() {
        val rows = outboxFailureRows(listOf(entry("a", owner = "designer-A", failure = "Refused")))
        assertFalse(rows.single().otherAccount)
    }

    @Test
    fun `bulk retry leaves another account's failure intact`() {
        val queue = listOf(
            entry("mine", owner = "designer-B", failure = "Name is too long"),
            entry("ownerless", failure = "The server refused this"),
            entry("theirs", owner = "designer-A", failure = "Only the person who recorded this may change it"),
            entry("waiting", owner = "designer-B"),
        )

        val retryable = outboxRetryableFailures(queue, "designer-B").map { it.id }
        assertEquals(listOf("mine", "ownerless"), retryable)
        assertFalse("the one whose reason a retry would have destroyed", "theirs" in retryable)
        assertFalse("and nothing that was merely waiting is a retry's business", "waiting" in retryable)
    }

    @Test
    fun `with the owner signed in the bulk button reaches everything again`() {
        val queue = listOf(
            entry("a", owner = "designer-A", failure = "Refused"),
            entry("b", owner = "designer-A", failure = "Refused"),
        )
        assertEquals(2, outboxRetryableFailures(queue, "designer-A").size)
        assertEquals(1, outboxRetryableFailures(listOf(entry("old", failure = "Refused")), "designer-B").size)
    }

}
