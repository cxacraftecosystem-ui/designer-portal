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
        val failure = httpError(403, "Admin access required").workshopAccessQueueFailure()
        assertTrue("GET /workshops/access-requests is require_admin", failure.refused)
        assertEquals("the server's own words survive", "Admin access required", failure.message)
    }

    @Test
    fun `no signal is not a refusal`() {
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
        assertTrue("the queue lives on the server, not on this handset", "kept online" in body)
        assertTrue("names the next condition rather than the next tap", "signal" in body)
        assertTrue("passes the plain connection sentence through", "Could not connect" in said.body)
    }

    @Test
    fun `an answered empty queue reads differently on the two chips`() {
        val pending = requireNotNull(notice(WorkshopAccessQueueView.PENDING))
        val history = requireNotNull(notice(WorkshopAccessQueueView.HISTORY))
        assertTrue("PENDING was read and found clear", pending.answered)
        assertTrue("HISTORY was read and found empty", history.answered)
        assertEquals("Nothing waiting", pending.heading)
        assertEquals("All requests answered.", pending.body)
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
