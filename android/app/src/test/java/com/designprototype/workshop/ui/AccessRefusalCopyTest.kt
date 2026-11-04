package com.designprototype.workshop.ui

import com.designprototype.workshop.data.AccessRefusal
import com.designprototype.workshop.data.accessRefusal
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.UnknownHostException

class AccessRefusalCopyTest {

    private fun refusal(code: Int, accessStatus: String?): HttpException {
        val headers = if (accessStatus == null) {
            Headers.headersOf()
        } else {
            Headers.headersOf("X-Access-Status", accessStatus)
        }
        return HttpException(
            Response.error<Any>(
                "{\"detail\":\"whatever the server wrote\"}"
                    .toResponseBody("application/json".toMediaTypeOrNull()),
                okhttp3.Response.Builder()
                    .code(code)
                    .message("refused")
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .headers(headers)
                    .request(okhttp3.Request.Builder().url("http://localhost/api/auth/login").build())
                    .build()
            )
        )
    }

    @Test
    fun `every refusal the server can send is its own answer`() {
        assertEquals(AccessRefusal.PENDING, refusal(403, "PENDING").accessRefusal())
        assertEquals(AccessRefusal.REJECTED, refusal(403, "REJECTED").accessRefusal())
        assertEquals(AccessRefusal.SUSPENDED, refusal(403, "SUSPENDED").accessRefusal())
        assertEquals(AccessRefusal.DESIGNER_SUSPENDED, refusal(403, "DESIGNER_SUSPENDED").accessRefusal())
        assertEquals(AccessRefusal.QUEUE_FULL, refusal(503, "NOT_RECORDED").accessRefusal())
    }

    @Test
    fun `a wrong password is a wrong password and is never an account refusal`() {
        assertEquals(AccessRefusal.BAD_CREDENTIAL, refusal(401, null).accessRefusal())
        assertNull(accessRefusalChrome(AccessRefusal.BAD_CREDENTIAL))
    }

    @Test
    fun `an unlabelled refusal says only what the server said`() {
        assertEquals(AccessRefusal.UNCLASSIFIED, refusal(403, null).accessRefusal())
        assertNull(accessRefusalChrome(AccessRefusal.UNCLASSIFIED))
    }

    @Test
    fun `an unlabelled 503 is an outage and not a statement about the person`() {
        assertEquals(AccessRefusal.NOT_REFUSED, refusal(503, null).accessRefusal())
    }

    @Test
    fun `no signal is not a refusal`() {
        assertEquals(AccessRefusal.NOT_REFUSED, UnknownHostException("api").accessRefusal())
        assertEquals(AccessRefusal.NOT_REFUSED, IOException("closed").accessRefusal())
        assertEquals(AccessRefusal.NOT_REFUSED, refusal(500, null).accessRefusal())
    }

    @Test
    fun `a person waiting to be approved is never told anything was withdrawn`() {
        val chrome = accessRefusalChrome(AccessRefusal.PENDING)
        assertNotNull("a pending refusal must draw its own panel", chrome)
        val words = "${chrome!!.heading} ${chrome.advice}".lowercase()
        listOf("withdrawn", "suspended", "revoked", "not approved", "invalid").forEach { forbidden ->
            assertFalse(
                "a person awaiting a first approval must not read \"$forbidden\": nothing has been " +
                    "taken away from them and nobody has refused them",
                words.contains(forbidden)
            )
        }
        assertTrue("it has to say what they are waiting for", words.contains("approve"))
        assertTrue("it has to say the password is not the problem", words.contains("password"))
        assertTrue("waiting is not a refusal, and the colour follows that", chrome.waiting)
    }

    @Test
    fun `the five panels are five different headings`() {
        val headings = listOf(
            AccessRefusal.PENDING,
            AccessRefusal.REJECTED,
            AccessRefusal.SUSPENDED,
            AccessRefusal.DESIGNER_SUSPENDED,
            AccessRefusal.QUEUE_FULL,
        ).map { accessRefusalChrome(it)?.heading }
        assertEquals("no refusal may be silent", 5, headings.filterNotNull().size)
        assertEquals("two refusals sharing a heading is two refusals collapsed", 5, headings.toSet().size)
    }

    @Test
    fun `only the pending panel is drawn as waiting`() {
        assertTrue(accessRefusalChrome(AccessRefusal.PENDING)!!.waiting)
        assertFalse(accessRefusalChrome(AccessRefusal.REJECTED)!!.waiting)
        assertFalse(accessRefusalChrome(AccessRefusal.SUSPENDED)!!.waiting)
        assertFalse(accessRefusalChrome(AccessRefusal.DESIGNER_SUSPENDED)!!.waiting)
        assertFalse(accessRefusalChrome(AccessRefusal.QUEUE_FULL)!!.waiting)
    }

    @Test
    fun `the empanelment refusal is the only one that names the designer roster`() {
        val designer = accessRefusalChrome(AccessRefusal.DESIGNER_SUSPENDED)!!
        assertTrue(designer.advice.contains("designer roster"))
        listOf(AccessRefusal.PENDING, AccessRefusal.REJECTED, AccessRefusal.SUSPENDED, AccessRefusal.QUEUE_FULL)
            .forEach { kind ->
                val chrome = accessRefusalChrome(kind)!!
                assertFalse(
                    "$kind is a platform-access decision and has nothing to do with the designer roster",
                    "${chrome.heading} ${chrome.advice}".contains("designer roster")
                )
            }
    }

    @Test
    fun `the queue-full panel says the request was not recorded`() {
        val chrome = accessRefusalChrome(AccessRefusal.QUEUE_FULL)!!
        assertTrue("${chrome.heading} ${chrome.advice}".lowercase().contains("recorded"))
    }
}
