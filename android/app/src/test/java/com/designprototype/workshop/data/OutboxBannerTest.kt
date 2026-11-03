package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertFalse(
            "a refused entry was described as uploading:\n${line.text}",
            line.text.contains("uploading when you're online"),
        )
        assertTrue(
            "the designer must be told the walk to find a signal is pointless:\n${line.text}",
            line.text.contains("Syncing will not send them"),
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
        assertTrue("a waiting entry has a tray row behind it", offline.actionable)
        assertEquals(
            "3 entries saved on this phone — will be sent when you have a signal.",
            offline.lines.single().text,
        )

        val online = outboxDeviceBanner(OutboxCounts(waiting = 3, refused = 0), online = true)!!
        assertEquals(
            "3 entries saved on this phone — sending now.",
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
            one.lines.single().text.contains("Syncing will not send it"),
        )
        val many = outboxDeviceBanner(OutboxCounts(waiting = 0, refused = 4), online = true)!!
        assertTrue(many.lines.single().text.contains("Syncing will not send them"))
    }

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
        assertTrue(line.text, line.text.contains("2 entries captured on this phone by another account"))
        assertTrue(line.text, line.text.contains("Sign in as them"))
        assertTrue(line.text, line.text.contains("nothing has been deleted"))
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
        assertTrue(waitingLine, waitingLine.contains("will be sent when you have a signal"))
        assertFalse(otherLine, otherLine.contains("when you have a signal"))
        assertFalse(otherLine, otherLine.contains("the server would not accept"))
        assertFalse(refusedLine, refusedLine.contains("Sign in as them"))
        assertTrue(waitingLine, waitingLine.startsWith("2 entries"))
        assertTrue(refusedLine, refusedLine.startsWith("1 entry"))
        assertTrue(otherLine, otherLine.startsWith("3 entries"))
    }

    @Test
    fun `a queue that is only another account's still draws something`() {
        assertNull(outboxDeviceBanner(OutboxCounts(0, 0, otherAccount = 0), online = true))
        assertEquals(
            1,
            outboxDeviceBanner(OutboxCounts(0, 0, otherAccount = 1), online = true)!!.lines.size,
        )
    }

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
        assertEquals("Photographs and recordings", outboxKindLabel(OFFLINE_MEDIA_ONLY, isUpdate = false))
        assertEquals("Report delivery note", outboxKindLabel(OFFLINE_EXPORT_RECORD, isUpdate = false))
        assertEquals("somethingnew", outboxKindLabel("somethingnew", isUpdate = false))
    }

    @Test
    fun `a media-only entry has a targetId and is still not a correction`() {
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
        assertEquals(2, row.mediaCount)
    }

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
        assertTrue(text, text.contains("1 other entry"))

        val went = OutboxRetryResult(requestedSent = true, refusedSent = 1, refusedTried = 1, othersSent = 0)
        val ok = outboxRetryMessage("Rekha Devi", went)
        assertTrue(ok, ok.contains("“Rekha Devi” was sent."))
        assertFalse(ok, ok.contains("other entr"))
    }

    @Test
    fun `try-all counts the refusals it was about, not everything the pass moved`() {
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

        val nothing = outboxRetryAllMessage(
            OutboxRetryResult(requestedSent = false, refusedSent = 0, refusedTried = 0, othersSent = 0)
        )
        assertTrue(nothing, nothing.contains("nothing to try again"))
    }

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
        assertTrue(one, one.contains("nothing has been deleted"))

        val all = outboxRetryAllMessage(offline)
        assertTrue(all, all.contains("no connection, so nothing was tried"))
        assertFalse(all, all.contains("none went"))
    }
}
