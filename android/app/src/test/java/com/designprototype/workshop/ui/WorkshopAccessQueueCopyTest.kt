package com.designprototype.workshop.ui

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * THE FOUR ANSWERS AN EMPTY WORKSHOP-ACCESS QUEUE CAN GIVE, AND THE RULE THAT NO TWO OF THEM MAY BE
 * THE SAME ONE.
 *
 * WHY THIS FILE EXISTS. `WorkshopAccessQueueCard` drew its empty state from `rows.isEmpty()` and
 * nothing else, so an admin whose role had been withdrawn on the server, and an admin standing in a
 * cluster with no signal, both read "Nothing waiting — the queue is clear". The failure was routed
 * to a snackbar that is gone by the time anybody scrolls to the card, so the only lasting sentence
 * on screen was the one that was false. Nobody notices that by reading the code: the card renders,
 * the words are English, and the only thing wrong is that a queue with people in it reports itself
 * empty.
 *
 * That is the same shape as the sign-in refusal defect [AccessRefusalCopyTest] guards ("wrong
 * password and pending approval should be differentiated") and the `/secrets` defect
 * [ApiKeysAccessTest] guards ("no signal is not a refusal"), and it is pinned the same way — by
 * walking the combinations and reading the words, because a wrong reassurance cannot be seen.
 *
 * THE THREE THINGS THAT WOULD BREAK IT
 *
 * 1. Collapsing the classifier — treating every failure as one thing, which is what a bare
 *    `onFailure { onError(…) }` did. Pinned by `every situation is its own answer`.
 * 2. Wording a refusal or a dead network as though the queue had been read and found empty, which is
 *    the original defect and would come straight back if somebody reused the old string. Pinned by
 *    `a queue that was never read never claims to be clear`.
 * 3. Dropping the staleness warning, so a failed refresh leaves yesterday's rows on screen looking
 *    live and an approver presses Approve on a request somebody else already answered. Pinned by
 *    `a failed refresh over rows still on screen warns instead of falling silent`.
 */
class WorkshopAccessQueueCopyTest {

    private fun httpError(code: Int, detail: String): HttpException = HttpException(
        Response.error<Any>(
            code,
            "{\"detail\":\"$detail\"}".toResponseBody("application/json".toMediaTypeOrNull()),
        ),
    )

    private fun notice(
        view: WorkshopAccessQueueView = WorkshopAccessQueueView.PENDING,
        error: Throwable? = null,
        rowsOnScreen: Int = 0,
    ): WorkshopAccessQueueNotice? = workshopAccessQueueNotice(
        view = view,
        failure = error?.workshopAccessQueueFailure(),
        rowsOnScreen = rowsOnScreen,
    )

    @Test
    fun `403 is the server refusing this account, which is a state and not a failure`() {
        // The hub tile is gated on the CACHED user, so an account whose role changed after sign-in
        // still reaches this card and is still refused by the server. That refusal has to be a
        // sentence about permission, never a sentence about how many people are waiting.
        val failure = httpError(403, "Admin access required").workshopAccessQueueFailure()
        assertTrue("GET /workshops/access-requests is require_admin", failure.refused)
        assertEquals("the server's own words survive", "Admin access required", failure.message)
    }

    @Test
    fun `no signal is not a refusal`() {
        // The three shapes a village handset actually produces, plus the gateway's own 502. None of
        // them is the server saying "not you", and reading any of them that way would send an admin
        // to ask for a permission they already hold.
        assertFalse("DNS failure offline", UnknownHostException("api").workshopAccessQueueFailure().refused)
        assertFalse("one bar, then nothing", SocketTimeoutException("timeout").workshopAccessQueueFailure().refused)
        assertFalse("captive portal / socket closed", IOException("closed").workshopAccessQueueFailure().refused)
        assertFalse("CloudFront could not reach the origin", httpError(502, "Bad Gateway").workshopAccessQueueFailure().refused)
    }

    @Test
    fun `every situation is its own answer`() {
        val headings = listOf(
            notice(WorkshopAccessQueueView.PENDING),
            notice(WorkshopAccessQueueView.HISTORY),
            notice(error = httpError(403, "Admin access required")),
            notice(error = UnknownHostException("api")),
        ).map { said -> requireNotNull(said).heading }

        assertEquals(
            "no two situations may share a heading: $headings",
            headings.size,
            headings.toSet().size,
        )
    }

    @Test
    fun `a queue that was never read never claims to be clear`() {
        // The exact regression: a refusal or a dead network borrowing the empty-queue wording. Both
        // must report that the question was not answered, and neither may use the vocabulary the
        // answered states own.
        val calm = listOf("nothing waiting", "no requests yet", "the queue is clear", "has been answered")
        for (error in listOf(httpError(403, "Admin access required"), UnknownHostException("api"))) {
            val said = requireNotNull(notice(error = error))
            assertFalse("an unread queue must not claim to have been read: ${said.heading}", said.answered)
            val words = "${said.heading} ${said.body}".lowercase()
            for (phrase in calm) {
                assertFalse("\"$phrase\" belongs to an answered queue, not to ${said.heading}", phrase in words)
            }
        }
    }

    @Test
    fun `a refusal says who may read the queue and what to do about it`() {
        val said = requireNotNull(notice(error = httpError(403, "Admin access required")))
        val body = said.body.lowercase()
        assertTrue("names the rank that may read it", "master admin" in body)
        // Actionable, per the repo rule: there is exactly one next move and it is not "try again".
        assertTrue("says how to recover the permission", "sign out" in body)
        assertTrue("passes the server's own sentence through", "Admin access required" in said.body)
        assertTrue(
            "says plainly that people may be waiting behind the refusal",
            "waiting" in body,
        )
    }

    @Test
    fun `an unreachable server says the requests are safe, because they are`() {
        val said = requireNotNull(notice(error = SocketTimeoutException("timed out")))
        val body = said.body.lowercase()
        assertTrue("the queue lives on the server, not on this handset", "held on the server" in body)
        assertTrue("names the next condition rather than the next tap", "signal" in body)
        assertTrue("passes the platform's own sentence through", "timed out" in said.body)
    }

    @Test
    fun `an answered empty queue reads differently on the two chips`() {
        // Mirrors frontend/components/settings/WorkshopAccessQueuePanel.tsx: "Nothing waiting" for
        // PENDING and "No requests yet" for the full history. Nobody has asked, and everybody has
        // been answered, are opposite pieces of news about the same empty list.
        val pending = requireNotNull(notice(WorkshopAccessQueueView.PENDING))
        val history = requireNotNull(notice(WorkshopAccessQueueView.HISTORY))
        assertTrue("PENDING was read and found clear", pending.answered)
        assertTrue("HISTORY was read and found empty", history.answered)
        assertEquals("Nothing waiting", pending.heading)
        assertEquals("Every workshop-access request has been answered.", pending.body)
        assertEquals("No requests yet", history.heading)
        assertEquals("Nobody has asked for access to a workshop.", history.body)
    }

    @Test
    fun `a list that loaded says nothing extra`() {
        assertNull("rows on screen and no failure needs no banner", notice(rowsOnScreen = 3))
        assertNull("the same on the history chip", notice(WorkshopAccessQueueView.HISTORY, rowsOnScreen = 3))
    }

    @Test
    fun `a failed refresh over rows still on screen warns instead of falling silent`() {
        // refresh() keeps the previous rows when the reload throws, so the list stays on screen
        // looking live. Without this line an approver can press Approve on a request another admin
        // answered ten minutes ago and get a 409 they cannot make sense of.
        val said = requireNotNull(notice(error = UnknownHostException("api"), rowsOnScreen = 4))
        assertFalse(said.answered)
        assertTrue("warns that what is drawn is old", "may already have been answered" in said.body)

        val fresh = requireNotNull(notice(error = UnknownHostException("api"), rowsOnScreen = 0))
        assertFalse(
            "with nothing on screen there is nothing to call stale",
            "may already have been answered" in fresh.body,
        )
    }
}
