package com.designprototype.workshop.ui

import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.CONNECTION_FAILED_SENTENCE
import com.designprototype.workshop.data.OfflineQueueResult
import com.designprototype.workshop.data.offlineSavedMessage
import com.designprototype.workshop.data.serverDidNotAnswer
import com.designprototype.workshop.data.sessionExpired
import com.designprototype.workshop.data.shouldReprobeServer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException

/**
 * The review of the 2026-10-17 smoke-test fixes: the files-ready card's rows, the banners over the
 * keyboard and the consent gate, a stale "can't reach the server" line, the wrong sentence for an
 * expired session, a duplicate "Could not connect", and the admin-view memory's names.
 */
class SmokeFixesFollowUpTest {

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    // ── The admin-view switch, remembered per account (ui/AdminViewMemory.kt) ────────────────

    @Test
    fun `the admin view opens where this account left it, else at the role default`() {
        assertTrue(restoredAdminView(stored = true, isMasterAdmin = false))
        assertFalse(restoredAdminView(stored = false, isMasterAdmin = true))
        assertTrue(restoredAdminView(stored = null, isMasterAdmin = true))
        assertFalse(restoredAdminView(stored = null, isMasterAdmin = false))
    }

    @Test
    fun `each account on a shared phone keeps its own switch`() {
        assertEquals("admin_view:u1", adminViewKey("u1"))
        assertNotEquals(adminViewKey("u1"), adminViewKey("u2"))
    }

    // ── No "Could not connect" under the offline line ─────────────────────────────────────

    @Test
    fun `the connection error is swallowed only while a banner already says it`() {
        assertTrue(bannerAlreadySays(CONNECTION_FAILED_SENTENCE, AppMessageTone.ERROR, bannerShowing = true))
        assertFalse(bannerAlreadySays(CONNECTION_FAILED_SENTENCE, AppMessageTone.ERROR, bannerShowing = false))
        assertFalse(bannerAlreadySays("Name is required.", AppMessageTone.ERROR, bannerShowing = true))
        assertFalse(bannerAlreadySays(CONNECTION_FAILED_SENTENCE, AppMessageTone.SUCCESS, bannerShowing = true))
    }

    @Test
    fun `a banner counts as shown only while it reserves room`() {
        assertFalse(bannerDrawn(bannerClearance(48.dp, shown = false)))
        assertTrue(bannerDrawn(bannerClearance(48.dp, shown = true)))
    }

    @Test
    fun `both message paths pass the offline check`() {
        for (fn in listOf("fun showMessage(text: String?)", "fun showError(text: String?)")) {
            val start = MAIN.indexOf(fn)
            assertTrue(fn, start >= 0)
            assertTrue(fn, MAIN.substring(start, start + 400).contains("offlineLineSays(text)"))
        }
    }

    // ── Which sentence a failed upload is queued with ─────────────────────────────────────

    @Test
    fun `only no answer, a 5xx, a 408 or a 429 means the server is down`() {
        assertTrue(serverDidNotAnswer(IOException("timeout")))
        assertTrue(serverDidNotAnswer(http(503)))
        assertTrue(serverDidNotAnswer(http(408)))
        assertTrue(serverDidNotAnswer(http(429)))
        assertFalse(serverDidNotAnswer(http(401)))
        assertFalse(serverDidNotAnswer(http(409)))
        assertFalse(serverDidNotAnswer(IllegalStateException("bad presign")))
        assertTrue(sessionExpired(http(401)))
        assertFalse(sessionExpired(http(503)))
    }

    @Test
    fun `an expired session is told to sign in, not to wait for the server`() {
        val kept = OfflineQueueResult(entryId = "e1", queuedFiles = 1)
        assertEquals(
            "Saved on this phone; it will upload once you sign in again.",
            offlineSavedMessage(kept, isCorrection = false, serverDown = false, signInNeeded = true),
        )
        val start = MAIN.indexOf("failures.all { repository.isTransient(it) }) && queueForLater(")
        assertTrue(start >= 0)
        val call = MAIN.substring(start, start + 500)
        assertTrue(call.contains("failures.all { serverDidNotAnswer(it) }"))
        assertTrue(call.contains("signInNeeded = failures.any { sessionExpired(it) }"))
    }

    // ── The server line after reconnecting ────────────────────────────────────────────────

    @Test
    fun `coming back online asks a server marked unreachable once`() {
        assertTrue(shouldReprobeServer(wasOnline = false, nowOnline = true, serverUnreachable = true))
        assertFalse(shouldReprobeServer(wasOnline = false, nowOnline = true, serverUnreachable = false))
        assertFalse(shouldReprobeServer(wasOnline = true, nowOnline = true, serverUnreachable = true))
        assertFalse(shouldReprobeServer(wasOnline = true, nowOnline = false, serverUnreachable = true))
        assertTrue(MAIN.contains("if (shouldReprobeServer(wasOnline, nowOnline, ServerReachability.unreachable.value))"))
    }

    // ── Where the banners stand down ──────────────────────────────────────────────────────

    @Test
    fun `the banners step aside for the keyboard and the consent gate`() {
        assertTrue(MAIN.contains("val bannersHeld = user == null || passwordHeld || usageConsentBlocks(user)"))
        assertTrue(MAIN.contains("val bannersShown = !rootImeOpen && (banner != null || connectionLine != null)"))
    }

    // ── The files-ready card's rows take the tile's ink ───────────────────────────────────

    @Test
    fun `each file row on the purple card draws in the tile's ink`() {
        val preview = MAIN.indexOf("private fun AndroidUriPreview(")
        assertTrue(preview >= 0)
        val thumb = MAIN.indexOf("MediaThumb(", preview)
        assertTrue(MAIN.substring(thumb, thumb + 600).contains("onTile = onTile"))
        val media = source("ui/MediaPlayers.kt")
        val row = media.substring(media.indexOf("fun MediaThumb("))
        assertTrue(row.contains("color = onTile ?: MaterialTheme.colorScheme.onSurface"))
        assertTrue(row.contains("MaterialTheme.field.onBrandTileMuted"))
        assertTrue(row.contains("colors = onTileButtonColors(onTile)"))
    }

    private companion object {
        fun source(relative: String): String {
            val tails = listOf(
                "src/main/java/com/designprototype/workshop/$relative",
                "app/src/main/java/com/designprototype/workshop/$relative",
                "android/app/src/main/java/com/designprototype/workshop/$relative",
            )
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                for (tail in tails) {
                    val candidate = File(dir, tail)
                    if (candidate.isFile) return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n")
                }
                dir = dir.parentFile
            }
            throw AssertionError("none of $tails found from ${File(".").absolutePath}")
        }

        val MAIN: String by lazy { source("MainActivity.kt") }
    }
}
