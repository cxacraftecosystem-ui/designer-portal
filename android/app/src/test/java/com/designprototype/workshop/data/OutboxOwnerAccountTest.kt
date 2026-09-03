package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RECORDS OUTBOX DRAINED UNDER WHICHEVER ACCOUNT HAPPENED TO SIGN IN.
 *
 * ── WHAT WAS ACTUALLY WRONG ───────────────────────────────────────────────────────────────────────
 *
 * [PendingEntry] carried no owner, `WorkshopRepository.syncOutbox` replayed every entry under the
 * current token, and `MainActivity`'s sign-in effect calls that within a second of a sign-in. Logout
 * clears the token store and the form cache and nothing else, so the queue and its staged bytes stay
 * on disk across a change of account.
 *
 * Two designers share one field handset — the case `WorkshopDraft.ownerUserId` is written for. A
 * captures a fortnight of artisans, products and photographs with no signal and signs out; B signs
 * in; every one of A's queued records is created on the server under B's token. B is `createdById`,
 * the rows land in B's lists, and A has to be granted access to their own fieldwork.
 *
 * IT IS WORSE THAN THE DRAFT CASE IN ONE RESPECT, which is why this queue needed the guard as much as
 * the workshops did: a synced entry is DELETED (`OfflineOutbox.remove`) along with its staged
 * captures, so by the time anybody notices the attribution there is no local copy left to compare.
 *
 * ── WHAT IS PINNED HERE ───────────────────────────────────────────────────────────────────────────
 *
 * The rule the drain consults, the compatibility direction that makes it safe to ship, and the three
 * numbers the banner is given — because a guard that merely stops sending, and says nothing, turns a
 * fortnight of fieldwork into a queue that never moves for a reason nobody can see. The SENTENCES
 * built from those numbers belong to `OutboxBannerTest`, which is where every wording claim about
 * this banner lives. `DwDraftOwnershipTest` is this file's other neighbour: it pins the same rule for
 * the design-workshop half.
 */
class OutboxOwnerAccountTest {

    /** The exact reader `OfflineOutbox` uses, so a decoding claim here is a claim about the queue. */
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

    // ── The rule the drain consults ──────────────────────────────────────────────────────────────

    @Test
    fun `an entry another account captured is not this account's to send`() {
        // THE ASSERTION THE DEFECT FAILED: nothing asked this question of a queued record at all.
        assertTrue(
            dwDraftIsForAnotherAccount(entry("e1", owner = "designer-A").ownerUserId, "designer-B"),
        )
        assertFalse(
            "and the owner's own entries go up exactly as they always did",
            dwDraftIsForAnotherAccount(entry("e2", owner = "designer-A").ownerUserId, "designer-A"),
        )
    }

    /**
     * THE COMPATIBILITY DIRECTION, AND THE ONE THAT WOULD COST MOST TO GET WRONG.
     *
     * The queue on a handset that has been out of coverage for a fortnight was written by the build
     * installed a fortnight ago and carries no owner at all. Refusing those would be a silent, total
     * drain stop on every handset upgraded into this build — real fieldwork stranded rather than
     * merely misfiled, which is the trade `dwDraftIsForAnotherAccount`'s own KDoc refuses.
     */
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
        // And every other field an earlier build wrote still decodes into what it meant.
        assertEquals("artisan", decoded.type)
        assertNull(decoded.failure)
    }

    @Test
    fun `the stamp survives a write and a read of the queue file`() {
        // `encodeDefaults = true`, so it is really on disk. An owner that vanished when the process
        // died would put A's fortnight back in B's drain on the next app open, which is the whole
        // failure — and the drain is the one pass that DELETES what it sends.
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
        // There is nothing to compare against, and `syncOutbox` returns before the loop in that state
        // anyway (`hasToken`). Reading null as "everything belongs to somebody else" would freeze the
        // whole queue on a phone whose session merely expired.
        assertFalse(dwDraftIsForAnotherAccount("designer-A", null))
        assertFalse(dwDraftIsForAnotherAccount(null, null))
    }

    // ── The three numbers the banner is given ────────────────────────────────────────────────────

    @Test
    fun `the counts are a partition and another account's entry is not counted as waiting`() {
        // THE ASSERTION THE DEFECT FAILED ON THE OTHER SIDE. A skipped entry has no `failure` — the
        // server was never asked — so it fell into `waiting`, and the banner drew it under a cloud-off
        // icon saying "sending now". No amount of signal moves it.
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
        // A refusal is a refusal whoever captured it: it is the one a person can act on, and it is
        // the one the tray lists. Counted twice, the three numbers would exceed the queue.
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
        // It must not draw nothing. The entries are real fieldwork on the flash storage of a shared
        // handset, and a count that silently goes to zero is how somebody concludes the work was lost.
        val counts = outboxCountsOf(listOf(entry("a", owner = "designer-A")), "designer-B")
        assertFalse(counts.isEmpty)
        assertEquals(0, counts.waiting)
    }

    // ── The tray, which offered three buttons the drain would not honour ─────────────────────────

    /**
     * "TRY AGAIN" ON ANOTHER ACCOUNT'S REFUSED ENTRY DESTROYED THE REASON AND SENT NOTHING.
     *
     * `outboxFailureRows` filtered on `failure != null` with no owner term at all, so A's refused
     * entry was listed to B under the same three buttons as B's own. The tap ran `clearFailure`,
     * which nulls `failure`, `failedAt`, `skewRun`, `conflict` and `danglingField`; `syncOutbox` then
     * SKIPPED the entry on the owner check, so nothing went. The row left the tray on the next read —
     * it lists a row only while a refusal stands — taking the server's own sentence with it, and the
     * entry went back to being invisible work no pass will ever move. *Try all again* did it to every
     * such row at once.
     *
     * The row is still LISTED. Its reason is still on it. What it does not get is a button.
     */
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

        // COUNTED AND VISIBLE, for `OutboxCounts.otherAccount`'s reason: a row that silently vanishes
        // is how somebody concludes a fortnight of fieldwork was lost.
        assertEquals(3, rows.size)
        val theirs = rows.single { it.entryId == "theirs" }
        assertTrue("the tray reads this flag and draws no buttons on the row", theirs.otherAccount)
        assertEquals(
            "and the server's own sentence is still under it",
            "Only the person who recorded this may change it",
            theirs.reason,
        )
        // OWN-ACCOUNT BEHAVIOUR UNCHANGED, and an ownerless entry from an older build with it: both
        // still carry every button they always had.
        assertFalse(rows.single { it.entryId == "mine" }.otherAccount)
        assertFalse(rows.single { it.entryId == "ownerless" }.otherAccount)
    }

    @Test
    fun `with nobody signed in no row is another account's`() {
        // The tray's default and the compatibility direction in one: null means there is nothing to
        // compare against, so every row keeps the buttons it has always had.
        val rows = outboxFailureRows(listOf(entry("a", owner = "designer-A", failure = "Refused")))
        assertFalse(rows.single().otherAccount)
    }

    // ── "Try all again", which cleared refusals it could not act on ──────────────────────────────

    /**
     * THE BULK BUTTON'S SELECTION, OWNED BY ONE FUNCTION AND ASKED BY BOTH HALVES.
     *
     * `OfflineOutbox.clearAllFailures` decides which refusals to unmark and
     * `WorkshopRepository.retryAllOutboxFailures` decides which to COUNT as tried; they walked the
     * queue separately, and an entry another account captured was in both walks. So one tap wiped the
     * reason off every refused entry on a shared handset while sending none of them.
     */
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
        // And on a handset upgraded into this build, where nothing carries an owner at all.
        assertEquals(1, outboxRetryableFailures(listOf(entry("old", failure = "Refused")), "designer-B").size)
    }

    // WHAT THE BANNER SAYS ABOUT THEM IS PINNED IN `OutboxBannerTest`, NOT HERE, and deliberately:
    // that file is the one place this repository decides which sentences the records banner may say
    // and which icon it may draw, and a second copy of those assertions here would be a second thing
    // to keep in step with the wording — the exact shape of defect `dwStageSaysNothing` is named
    // after. This file owns the rule and the partition; that one owns the words.
}
