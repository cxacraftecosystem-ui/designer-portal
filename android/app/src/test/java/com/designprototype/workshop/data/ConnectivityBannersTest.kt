package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release smoke test of 2026-10-17 (dp/59-61): no offline line, no "can't reach the server"
 * line, a queued upload the banner could not open, and an upload that said "check your connection"
 * and kept nothing when storage was down. The words and decisions behind each fix, pinned.
 */
class ConnectivityBannersTest {

    @Test
    fun `offline wins over the server, and a working phone with a working server shows nothing`() {
        assertEquals(OFFLINE_SENTENCE, connectionNotice(online = false, serverUnreachable = false))
        assertEquals(OFFLINE_SENTENCE, connectionNotice(online = false, serverUnreachable = true))
        assertEquals(
            ServerReachability.SERVER_UNREACHABLE_SENTENCE,
            connectionNotice(online = true, serverUnreachable = true),
        )
        assertNull(connectionNotice(online = true, serverUnreachable = false))
    }

    @Test
    fun `a queue on a phone with signal and no server is not told to find a signal`() {
        val banner = outboxDeviceBanner(OutboxCounts(waiting = 1, refused = 0), online = true, serverReachable = false)!!
        val line = banner.lines.single().text
        assertEquals("1 entry saved on this phone — will be sent when the server is reachable.", line)
        assertFalse(line, line.contains("signal"))
        assertFalse(line, line.contains("sending now"))
    }

    @Test
    fun `a waiting entry makes the banner open the tray`() {
        assertTrue(outboxDeviceBanner(OutboxCounts(waiting = 1, refused = 0), online = false)!!.actionable)
        assertFalse(
            "another account's entries alone have no tray row behind them",
            outboxDeviceBanner(OutboxCounts(waiting = 0, refused = 0, otherAccount = 1), online = true)!!.actionable,
        )
    }

    private fun entry(id: String, createdAt: String, owner: String? = "me", failure: String? = null) = PendingEntry(
        id = id,
        type = OFFLINE_MEDIA_ONLY,
        payloadJson = "",
        label = "Upload $id",
        media = listOf(PendingMedia("/data/outbox/media/$id.jpg", "$id.jpg", "image/jpeg", "photo")),
        createdAt = createdAt,
        targetId = "rec-$id",
        ownerUserId = owner,
        failure = failure,
    )

    @Test
    fun `the tray lists this account's waiting entries, oldest first, with their files and status`() {
        val rows = outboxWaitingRows(
            listOf(
                entry("b", "2026-10-17T05:00:00Z"),
                entry("a", "2026-10-17T04:00:00Z"),
                entry("refused", "2026-10-17T03:00:00Z", failure = "Too large"),
                entry("theirs", "2026-10-17T02:00:00Z", owner = "someone-else"),
            ),
            signedInUserId = "me",
            online = false,
        )
        assertEquals(listOf("a", "b"), rows.map { it.entryId })
        assertEquals("Waiting for a signal", rows.first().status)
        assertEquals(listOf("a.jpg"), rows.first().files.map { it.name })
        assertEquals("/data/outbox/media/a.jpg", rows.first().files.single().localPath)
    }

    @Test
    fun `each waiting entry says where it stands`() {
        assertEquals("Waiting for a signal", outboxWaitingStatus(online = false, serverReachable = false))
        assertEquals("Waiting for the server", outboxWaitingStatus(online = true, serverReachable = false))
        assertEquals("Sending now", outboxWaitingStatus(online = true, serverReachable = true))
    }

    @Test
    fun `files kept because the server or storage did not answer say so, not find a signal`() {
        val kept = OfflineQueueResult(entryId = "e1", queuedFiles = 1)
        assertEquals(
            "Saved on this phone; it will upload when the server is reachable.",
            offlineSavedMessage(kept, isCorrection = false, serverDown = true),
        )
        assertEquals(
            "Saved on this device. It will be sent when you have a signal.",
            offlineSavedMessage(kept, isCorrection = false),
        )
    }
}
