package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RECORDS QUEUE, MADE TO SAY WHAT IS ACTUALLY IN IT.
 *
 * The shipped banner drew one number under a cloud-off icon and one sentence:
 *
 *     "3 entries saved on this device — uploading when you're online."
 *
 * [OfflineOutbox.count] counted every row including refusals, and `syncOutbox` steps over a refused
 * entry for ever — so a record the server had permanently rejected sat inside a promise that it was
 * on its way, and stayed there for the rest of the installation's life. `notifyUser`'s own KDoc names
 * the defect in those words. The design-workshop side had already fixed the identical bug properly
 * (`dwDeviceSyncBanner`, measured on an SM-M325F) and `DwDeviceSyncBannerTest` pins it there.
 *
 * These pin it here: which sentences the records banner may say, which icon it may draw, and that a
 * refusal is never described as waiting for a signal. Every one of these strings is read by somebody
 * standing in a courtyard with no connection, so a JVM test is the only place they can be checked.
 *
 * ── AND A THIRD THING A CONNECTION DOES NOT MOVE (2026-09-03) ─────────────────────────────────────
 *
 * [OutboxCounts.otherAccount]: entries captured on this handset by the OTHER designer sharing it,
 * which `syncOutbox` now skips rather than filing their fieldwork under this account's name. It
 * arrived here as "waiting", exactly as a refusal once did, and it gets its own line for the reason a
 * refusal has one — the remedy is neither a signal nor a correction. The rule that decides which
 * entries those are is `OutboxOwnerAccountTest`'s; this file owns what is said about them.
 */
class OutboxBannerTest {

    @Test
    fun `an empty queue draws nothing`() {
        assertNull(outboxDeviceBanner(OutboxCounts(waiting = 0, refused = 0), online = false))
        assertNull(outboxDeviceBanner(OutboxCounts(waiting = 0, refused = 0), online = true))
    }

    @Test
    fun `a refusal is never drawn under the cloud-off icon`() {
        val banner = outboxDeviceBanner(OutboxCounts(waiting = 0, refused = 2), online = false)!!
        assertFalse(
            "the cloud-off icon is a claim about the network, and walking up the hill will not move " +
                "a refused entry",
            banner.showCloudOff,
        )
        assertTrue("a refusal is the one case where a person can act", banner.actionable)
        assertEquals(1, banner.lines.size)
        val line = banner.lines.single()
        assertTrue(line.warn)
        // The exact sentence the app used to say about these entries, and may never say again.
        assertFalse(
            "a refused entry was described as uploading:\n${line.text}",
            line.text.contains("uploading when you're online"),
        )
        assertTrue(
            "the designer must be told the walk to find a signal is pointless:\n${line.text}",
            line.text.contains("A sync will NOT move them"),
        )
        assertTrue(
            "nothing has been deleted, and saying so is what stops a panic:\n${line.text}",
            line.text.contains("Nothing has been deleted"),
        )
    }

    @Test
    fun `waiting entries say which situation the phone is actually in`() {
        val offline = outboxDeviceBanner(OutboxCounts(waiting = 3, refused = 0), online = false)!!
        assertTrue(offline.showCloudOff)
        assertFalse("nothing here needs a person", offline.actionable)
        assertEquals(
            "3 entries saved on this device — will be sent when you have a signal.",
            offline.lines.single().text,
        )

        // FOUR BARS AND A QUEUE THAT IS NOT MOVING is a different situation, and it was described
        // with the same sentence as no signal at all.
        val online = outboxDeviceBanner(OutboxCounts(waiting = 3, refused = 0), online = true)!!
        assertEquals(
            "3 entries saved on this device — sending now.",
            online.lines.single().text,
        )
        assertFalse(
            "a phone with a connection must not be told to wait for one:\n${online.lines.single().text}",
            online.lines.single().text.contains("when you have a signal"),
        )
    }

    @Test
    fun `both halves get their own line and the icon follows the waiting half`() {
        val banner = outboxDeviceBanner(OutboxCounts(waiting = 2, refused = 1), online = false)!!
        assertEquals(2, banner.lines.size)
        assertFalse("the waiting line is not a warning", banner.lines[0].warn)
        assertTrue("the refusal line is", banner.lines[1].warn)
        assertTrue("there is genuinely something on its way", banner.showCloudOff)
        assertTrue("and something a person can do", banner.actionable)
        // Neither number is folded into the other. A total of three would be the old lie with an
        // extra sentence under it.
        assertTrue(banner.lines[0].text.startsWith("2 entries"))
        assertTrue(banner.lines[1].text.startsWith("1 entry"))
    }

    @Test
    fun `one entry reads as one entry`() {
        assertEquals("1 entry", outboxEntryCount(1))
        assertEquals("2 entries", outboxEntryCount(2))
        assertEquals("0 entries", outboxEntryCount(0))
        val one = outboxDeviceBanner(OutboxCounts(waiting = 0, refused = 1), online = true)!!
        assertTrue(
            "the singular has to carry through the whole sentence, not just the count:\n" +
                one.lines.single().text,
            one.lines.single().text.contains("A sync will NOT move it"),
        )
        val many = outboxDeviceBanner(OutboxCounts(waiting = 0, refused = 4), online = true)!!
        assertTrue(many.lines.single().text.contains("A sync will NOT move them"))
    }

    // ── The third thing a connection does not move ─────────────────────────────────────────────

    /**
     * AN ENTRY ANOTHER ACCOUNT CAPTURED, WHICH ARRIVED HERE AS "WAITING".
     *
     * `syncOutbox` steps over an entry whose `PendingEntry.ownerUserId` is not the signed-in account,
     * because sending it files one designer's fieldwork under another's name on a shared handset (see
     * that field, and `OutboxOwnerAccountTest` for the rule). Nothing is refused on such an entry and
     * nothing failed, so it carried a null `failure` and fell into `waiting` — inside "sending now",
     * under a cloud-off icon, for an entry no amount of signal will ever move. That is the defect this
     * whole file exists to end, reaching the banner by a third door.
     */
    @Test
    fun `another account's entries get their own line, naming the one act that moves them`() {
        val banner = outboxDeviceBanner(
            OutboxCounts(waiting = 0, refused = 0, otherAccount = 2),
            online = true,
        )!!
        val line = banner.lines.single()

        assertTrue("it is the amber one: something here is not going anywhere", line.warn)
        assertFalse(
            "the cloud-off icon is a claim about the NETWORK; this is a claim about a person:\n" +
                line.text,
            banner.showCloudOff,
        )
        // Two facts and nothing else: whose they are, and what moves them.
        assertTrue(line.text, line.text.contains("2 entries captured on this phone by another account"))
        assertTrue(line.text, line.text.contains("Sign in as them"))
        // The standing promise of this queue on every path where something is not going.
        assertTrue(line.text, line.text.contains("nothing has been deleted"))
        // NOT TAPPABLE. The tray lists refusals (`outboxFailureRows` filters on `failure`), so a tap
        // would open a screen with nothing on it — a dead end wearing the costume of a remedy, which
        // is the phrase this queue already uses about the two buttons it had to stop offering.
        assertFalse("there is no tray row behind this line", banner.actionable)
    }

    @Test
    fun `one entry of another account's reads as one, through the whole sentence`() {
        val one = outboxDeviceBanner(OutboxCounts(waiting = 0, refused = 0, otherAccount = 1), true)!!
        val text = one.lines.single().text
        assertTrue(text, text.contains("1 entry captured"))
        assertTrue(text, text.contains("Sign in as them to send it"))
    }

    @Test
    fun `the three states get three lines and none of them borrows another's remedy`() {
        val banner = outboxDeviceBanner(
            OutboxCounts(waiting = 2, refused = 1, otherAccount = 3),
            online = false,
        )!!
        assertEquals(3, banner.lines.size)
        assertFalse("the waiting line is not a warning", banner.lines[0].warn)
        assertTrue("there is genuinely something on its way", banner.showCloudOff)
        assertTrue("and a refusal a person can act on", banner.actionable)

        val waitingLine = banner.lines[0].text
        val refusedLine = banner.lines[1].text
        val otherLine = banner.lines[2].text
        // No two of them may word each other. One waits for a signal, one for a correction, one for a
        // different person to sign in — and a designer sent to the wrong remedy loses a day. This is
        // R7's rule about collapsing two absences, applied to three.
        assertTrue(waitingLine, waitingLine.contains("will be sent when you have a signal"))
        assertFalse(otherLine, otherLine.contains("when you have a signal"))
        assertFalse(otherLine, otherLine.contains("the server would not accept"))
        assertFalse(refusedLine, refusedLine.contains("Sign in as them"))
        // And no number is folded into another: a total of six would be the old lie with two more
        // sentences under it.
        assertTrue(waitingLine, waitingLine.startsWith("2 entries"))
        assertTrue(refusedLine, refusedLine.startsWith("1 entry"))
        assertTrue(otherLine, otherLine.startsWith("3 entries"))
    }

    @Test
    fun `a queue that is only another account's still draws something`() {
        // It must not draw nothing. The entries are real fieldwork on the flash of a shared handset,
        // and a banner that goes silent is how somebody concludes the work was lost and stops looking.
        assertNull(outboxDeviceBanner(OutboxCounts(0, 0, otherAccount = 0), online = true))
        assertEquals(
            1,
            outboxDeviceBanner(OutboxCounts(0, 0, otherAccount = 1), online = true)!!.lines.size,
        )
    }

    // ── The tray ───────────────────────────────────────────────────────────────────────────────

    private fun entry(
        id: String,
        type: String,
        label: String,
        failure: String? = null,
        failedAt: String? = null,
        createdAt: String = "2026-08-20T10:00:00Z",
        targetId: String? = null,
        skewRun: String? = null,
        media: List<PendingMedia> = emptyList(),
    ) = PendingEntry(
        id = id,
        type = type,
        payloadJson = """{"secret":"an artisan's identity answers live in here"}""",
        label = label,
        media = media,
        createdAt = createdAt,
        targetId = targetId,
        failure = failure,
        failedAt = failedAt,
        skewRun = skewRun,
    )

    @Test
    fun `the tray lists only refusals, newest first, and never the payload`() {
        val rows = outboxFailureRows(
            listOf(
                entry("a", "artisan", "Rekha Devi", failure = "Duplicate", failedAt = "2026-08-21T09:00:00Z"),
                entry("b", "craft", "Bidri", failure = null),
                entry("c", "product", "Bowl", failure = "Refused", failedAt = "2026-08-22T09:00:00Z"),
            )
        )
        assertEquals(listOf("c", "a"), rows.map { it.entryId })
        // A row that held `payloadJson` would sooner or later print a field out of it, and the fields
        // in there include an artisan's identity answers.
        rows.forEach { row ->
            assertFalse(
                "the tray must not carry the record's body",
                row.reason.contains("identity answers") || row.label.contains("identity answers"),
            )
        }
    }

    @Test
    fun `a queued entry with no label still has something to point at`() {
        val row = outboxFailureRows(listOf(entry("a", "artisan", "   ", failure = "Refused"))).single()
        assertEquals("(untitled)", row.label)
    }

    @Test
    fun `the tray names what the entry was, in words a designer uses`() {
        assertEquals("Artisan", outboxKindLabel("artisan", isUpdate = false))
        assertEquals("Artisan — a correction", outboxKindLabel("artisan", isUpdate = true))
        assertEquals("Interview", outboxKindLabel("questionnaire", isUpdate = false))
        assertEquals("Toolkit", outboxKindLabel("tool", isUpdate = false))
        // Neither of these is a record, and printing the wire value would show a designer the word
        // "designWorkshopExport" and leave them to work it out.
        assertEquals("Photographs and recordings", outboxKindLabel(OFFLINE_MEDIA_ONLY, isUpdate = false))
        assertEquals("Report delivery note", outboxKindLabel(OFFLINE_EXPORT_RECORD, isUpdate = false))
        // An unknown type from a future build is shown as it is rather than swallowed.
        assertEquals("somethingnew", outboxKindLabel("somethingnew", isUpdate = false))
    }

    @Test
    fun `a media-only entry has a targetId and is still not a correction`() {
        // `targetId != null` is what marks a correction — and a media-only entry carries one too,
        // because the files attach to a record that already exists. Reading the flag alone would
        // label a rescued photograph "Photographs and recordings — a correction", which describes
        // something that never happened.
        val row = outboxFailureRows(
            listOf(entry("m", OFFLINE_MEDIA_ONLY, "Attachments", failure = "Gone", targetId = "rec_1"))
        ).single()
        assertEquals("Photographs and recordings", row.kind)
    }

    @Test
    fun `an entry waiting on a newer build is marked as such`() {
        val rows = outboxFailureRows(
            listOf(
                entry("a", "artisan", "A", failure = "Refused"),
                entry("b", "artisan", "B", failure = "Refused", skewRun = "run-7"),
            )
        )
        assertFalse(rows.first { it.entryId == "a" }.awaitingUpdate)
        assertTrue(
            "`blocksRetry` treats a skew run differently, so the tray must not offer the same tap",
            rows.first { it.entryId == "b" }.awaitingUpdate,
        )
    }

    @Test
    fun `a refusal with no failedAt still sorts, on the time it was captured`() {
        // An entry marked by an older build has no `failedAt`. Sorting on a null would drop it to
        // the end of the list regardless of age, which is where a designer stops looking.
        val rows = outboxFailureRows(
            listOf(
                entry("old", "craft", "Old", failure = "x", createdAt = "2026-08-01T00:00:00Z"),
                entry("new", "craft", "New", failure = "x", createdAt = "2026-08-23T00:00:00Z"),
            )
        )
        assertEquals(listOf("new", "old"), rows.map { it.entryId })
    }

    @Test
    fun `the count of files it is carrying is on the row`() {
        val row = outboxFailureRows(
            listOf(
                entry(
                    "a", "artisan", "Rekha", failure = "Refused",
                    media = listOf(
                        PendingMedia(
                            localPath = "/x/1.jpg", originalFilename = "1.jpg",
                            mimeType = "image/jpeg", mediaType = "IMAGE",
                        ),
                        PendingMedia(
                            localPath = "/x/2.jpg", originalFilename = "2.jpg",
                            mimeType = "image/jpeg", mediaType = "IMAGE",
                        ),
                    ),
                )
            )
        ).single()
        // The number is the reason discarding is a separate, explicit act: two photographs go with it.
        assertEquals(2, row.mediaCount)
    }

    // ── What a retry is allowed to claim ───────────────────────────────────────────────────────

    /**
     * THE ONE SENTENCE THIS QUEUE MAY NEVER SAY: that something was sent before it was.
     *
     * A retry drains the whole queue, which is right — a person who has just found a signal wants
     * everything to go. But the tray used to read that pass's TOTAL as the answer about the row that
     * was tapped, so with entry A refused and entry B merely waiting behind it, retrying A drained B,
     * the count came back 1, and the tray printed "“A” was sent." directly above A, still listed,
     * still carrying its refusal. The next thing that invites is throwing A away.
     */
    @Test
    fun `a retry never credits one entry with another entry's success`() {
        val drainedSomethingElse = OutboxRetryResult(
            requestedSent = false,
            refusedSent = 0,
            refusedTried = 1,
            othersSent = 1,
        )
        val text = outboxRetryMessage("Rekha Devi", drainedSomethingElse)
        assertFalse("this is the defect: $text", text.contains("was sent"))
        assertTrue(text, text.contains("still did not go"))
        // The other entry is still ACCOUNTED FOR, because the designer watched the banner's count
        // drop and is owed an explanation for where it went.
        assertTrue(text, text.contains("1 other entry"))

        val went = OutboxRetryResult(requestedSent = true, refusedSent = 1, refusedTried = 1, othersSent = 0)
        val ok = outboxRetryMessage("Rekha Devi", went)
        assertTrue(ok, ok.contains("“Rekha Devi” was sent."))
        assertFalse(ok, ok.contains("other entr"))
    }

    @Test
    fun `try-all counts the refusals it was about, not everything the pass moved`() {
        // Four refusals tried, none of them went, and three entries that were only ever waiting did.
        // "Tried all of them again; 3 went." was the old sentence, over a tray still listing four.
        val none = outboxRetryAllMessage(
            OutboxRetryResult(requestedSent = false, refusedSent = 0, refusedTried = 4, othersSent = 3)
        )
        assertTrue(none, none.contains("Tried 4 entries again; none went."))
        assertTrue(none, none.contains("3 other entries that were only waiting"))

        val all = outboxRetryAllMessage(
            OutboxRetryResult(requestedSent = true, refusedSent = 4, refusedTried = 4, othersSent = 0)
        )
        assertTrue(all, all.contains("all of them went"))

        val some = outboxRetryAllMessage(
            OutboxRetryResult(requestedSent = true, refusedSent = 1, refusedTried = 3, othersSent = 1)
        )
        assertTrue(some, some.contains("1 went and 2 still did not"))
        assertTrue(some, some.contains("1 other entry that was only waiting"))

        // Nothing refused is not "0 went", which reads as a failure.
        val nothing = outboxRetryAllMessage(
            OutboxRetryResult(requestedSent = false, refusedSent = 0, refusedTried = 0, othersSent = 0)
        )
        assertTrue(nothing, nothing.contains("nothing refused"))
    }

    /**
     * A RETRY WITH NO SIGNAL ATTEMPTED NOTHING, and must not be described as an attempt.
     *
     * `syncOutbox` returns 0 without sending a byte when the device is offline, which at the call site
     * is indistinguishable from "sent and refused again". So a designer in a courtyard who tapped
     * "Try again" was told "the reason under it is what came back this time" about a request that was
     * never made — and the reason printed under the row is hours old.
     */
    @Test
    fun `a retry with no connection says nothing was tried`() {
        val offline = OutboxRetryResult(
            requestedSent = false,
            refusedSent = 0,
            refusedTried = 3,
            othersSent = 0,
            attempted = false,
        )
        val one = outboxRetryMessage("Rekha Devi", offline)
        assertTrue(one, one.contains("no connection, so nothing was tried"))
        assertFalse(one, one.contains("came back this time"))
        // And it says the work is safe, which is this queue's standing promise on every refusal path.
        assertTrue(one, one.contains("nothing has been deleted"))

        val all = outboxRetryAllMessage(offline)
        assertTrue(all, all.contains("no connection, so nothing was tried"))
        assertFalse(all, all.contains("none went"))
    }
}
