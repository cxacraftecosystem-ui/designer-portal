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

/**
 * THE TWO SENTENCES A REFUSED SIGN-IN CAN CARRY, AND THE RULE THAT THEY MAY NEVER BE THE SAME ONE.
 *
 * ── WHAT IS BEING PINNED, AND WHY IT IS PINNED IN A TEST RATHER THAN READ ────────────────────────
 *
 * The product owner's ruling on this feature is one sentence: "wrong password and pending approval
 * should be differentiated". Everything below is that sentence turned into assertions, because the
 * failure it guards against is invisible by inspection — the card still renders, the words are still
 * English, and the only thing wrong is that the person waiting for an administrator is told to check
 * their password. That failure has already happened once in this app: before
 * `Throwable.signInErrorMessage` existed, every refusal rendered `HttpException.message` ("HTTP 403
 * Forbidden") or a hand-written "invalid email or password", and suspended designers reset passwords
 * that were never wrong.
 *
 * ── THE THREE THINGS THAT WOULD BREAK IT ─────────────────────────────────────────────────────────
 *
 * 1. Collapsing the classifier — treating every 403 as one thing, which is what [isAccountRefusal]
 *    did and why it was widened. Pinned by `every refusal is its own answer`.
 * 2. Wording the PENDING panel as though something had been taken away, which is what the card said
 *    before this feature and would say again if somebody reused the old strings. Pinned by
 *    `a person waiting to be approved is never told anything was withdrawn`.
 * 3. Guessing a category when the server did not send one — a proxy strips the header and every
 *    refused person is suddenly told their access was suspended. Pinned by
 *    `an unlabelled refusal says only what the server said`.
 */
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

    // ── The classifier ───────────────────────────────────────────────────────────────────────────

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
        // THE RULING, from the other side. A 401 must not be dressed up as an account problem: doing
        // so would send everybody who fat-fingers a password to email an administrator, which is
        // this feature's own mistake made backwards.
        assertEquals(AccessRefusal.BAD_CREDENTIAL, refusal(401, null).accessRefusal())
        assertNull(accessRefusalChrome(AccessRefusal.BAD_CREDENTIAL))
    }

    @Test
    fun `an unlabelled refusal says only what the server said`() {
        // A deployment older than this handset, or a proxy that strips unknown headers. The card must
        // fall back to neutral chrome around the server's own sentence — never to a guessed heading,
        // because the wrong heading here is worse than none: it tells a person waiting to be approved
        // that their access was withdrawn.
        assertEquals(AccessRefusal.UNCLASSIFIED, refusal(403, null).accessRefusal())
        assertNull(accessRefusalChrome(AccessRefusal.UNCLASSIFIED))
    }

    @Test
    fun `an unlabelled 503 is an outage and not a statement about the person`() {
        // CloudFront answers 503 when the origin is briefly unhealthy. Reading that as "the approval
        // queue is full" would tell somebody to contact an administrator about a capacity condition
        // that does not exist, and would hide a real outage behind a policy message.
        assertEquals(AccessRefusal.NOT_REFUSED, refusal(503, null).accessRefusal())
    }

    @Test
    fun `no signal is not a refusal`() {
        assertEquals(AccessRefusal.NOT_REFUSED, UnknownHostException("api").accessRefusal())
        assertEquals(AccessRefusal.NOT_REFUSED, IOException("closed").accessRefusal())
        assertEquals(AccessRefusal.NOT_REFUSED, refusal(500, null).accessRefusal())
    }

    // ── The words ────────────────────────────────────────────────────────────────────────────────

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
        // The other half of the ruling, said out loud rather than merely implied: the reason a
        // distinct answer was chosen at all is that the vague one sends people to a password reset.
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
        // The colour split. Everything else on this list is an answer somebody gave; only PENDING is
        // an answer nobody has given yet, and an admin-facing colour that said otherwise would tell
        // people to stop waiting.
        assertTrue(accessRefusalChrome(AccessRefusal.PENDING)!!.waiting)
        assertFalse(accessRefusalChrome(AccessRefusal.REJECTED)!!.waiting)
        assertFalse(accessRefusalChrome(AccessRefusal.SUSPENDED)!!.waiting)
        assertFalse(accessRefusalChrome(AccessRefusal.DESIGNER_SUSPENDED)!!.waiting)
        assertFalse(accessRefusalChrome(AccessRefusal.QUEUE_FULL)!!.waiting)
    }

    @Test
    fun `the empanelment refusal is the only one that names the designer roster`() {
        // Two lists, two remedies. An admin who bars a crowdsource volunteer from the application
        // must not have them told to ask about a "designer roster" they were never on — and the
        // designer whose empanelment ended must still be pointed at the list that actually refused
        // them.
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
        // The one refusal where waiting is actively wrong: nobody will ever see this person, so a
        // panel that read like the pending one would leave them waiting on a queue they are not in.
        val chrome = accessRefusalChrome(AccessRefusal.QUEUE_FULL)!!
        assertTrue("${chrome.heading} ${chrome.advice}".lowercase().contains("recorded"))
    }
}
