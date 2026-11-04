package com.designprototype.workshop.ui

import com.designprototype.workshop.data.SignInHint
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.signInHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class PasswordSetupCopyTest {

    private fun user(mustChange: Boolean?): UserDto = UserDto(
        id = "u1",
        email = "designer@example.org",
        name = "A Designer",
        role = "DESIGNER",
        mustChangePassword = mustChange
    )

    private fun account(provider: String?, passwordSetAt: String?): UserDto = UserDto(
        id = "u2",
        email = "someone@example.org",
        name = "Someone",
        role = "DESIGNER",
        authProvider = provider,
        passwordSetAt = passwordSetAt
    )

    private fun refusal(code: Int, hint: String?): HttpException {
        val headers = if (hint == null) Headers.headersOf() else Headers.headersOf("X-Sign-In-Hint", hint)
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
    fun `an account whose password an administrator typed is gated`() {
        assertTrue(mustChangePasswordBlocks(user(true)))
    }

    @Test
    fun `an account that chose its own password is not`() {
        assertFalse(mustChangePasswordBlocks(user(false)))
    }

    @Test
    fun `a server older than the column blocks nobody`() {
        assertFalse(mustChangePasswordBlocks(user(null)))
    }

    @Test
    fun `nobody signed in is not gated`() {
        assertFalse(mustChangePasswordBlocks(null))
    }

    @Test
    fun `every link refusal is its own sentence`() {
        val reasons = listOf("missing", "malformed", "expired", "revoked", "spent", "unknown-account")
        val sentences = reasons.map { passwordLinkRefusal(it) }
        assertEquals(
            "six reasons must produce six distinct sentences, or one of them is telling somebody " +
                "to do the wrong thing",
            reasons.size,
            sentences.toSet().size
        )
        sentences.forEach { assertTrue("a refusal must say something", it.isNotBlank()) }
    }

    @Test
    fun `a spent link sends the person to sign in, and never to an administrator`() {
        val spent = passwordLinkRefusal("spent").lowercase()
        assertTrue("it points at signing in", spent.contains("sign in"))
        assertFalse("and does not send them to an administrator", spent.contains("administrator"))
    }

    @Test
    fun `expired and revoked both send the person to an administrator`() {
        listOf("expired", "revoked").forEach {
            assertTrue(it, passwordLinkRefusal(it).lowercase().contains("administrator"))
        }
    }

    @Test
    fun `a reason word this build has never heard of still says something`() {
        val unknown = passwordLinkRefusal("some-new-reason")
        assertEquals(passwordLinkRefusal(null), unknown)
        assertTrue(unknown.isNotBlank())
        assertFalse(unknown.lowercase().contains("expired"))
        assertFalse(unknown.lowercase().contains("already been used"))
    }

    @Test
    fun `a whole pasted link yields the token`() {
        assertEquals(
            "abc.def",
            passwordLinkToken("https://portal.example.org/set-password?token=abc.def")
        )
    }

    @Test
    fun `a bare token is left exactly as it is`() {
        assertEquals("abc.def", passwordLinkToken("abc.def"))
    }

    @Test
    fun `surrounding whitespace from a paste is trimmed`() {
        assertEquals("abc.def", passwordLinkToken("  abc.def\n"))
    }

    @Test
    fun `a parameter after the token is not swallowed into it`() {
        assertEquals(
            "abc.def",
            passwordLinkToken("https://portal.example.org/set-password?token=abc.def&from=email")
        )
        assertEquals(
            "abc.def",
            passwordLinkToken("https://portal.example.org/set-password?token=abc.def#top")
        )
    }

    @Test
    fun `a token that is not the first parameter is still found`() {
        assertEquals(
            "abc.def",
            passwordLinkToken("https://portal.example.org/set-password?lang=hi&token=abc.def")
        )
    }

    @Test
    fun `the percent-encoding a link carries is undone`() {
        assertEquals("abc=def", passwordLinkToken("https://x/set-password?token=abc%3Ddef"))
    }

    @Test
    fun `a malformed escape is left standing rather than throwing`() {
        assertEquals("abc%zz", passwordLinkToken("https://x/set-password?token=abc%zz"))
    }

    @Test
    fun `an empty paste is an empty token, not a request`() {
        assertEquals("", passwordLinkToken("   "))
    }

    @Test
    fun `a link carrying the token in its fragment yields the token`() {
        assertEquals(
            "abc.def",
            passwordLinkToken("https://designer-repository.vercel.app/set-password#token=abc.def")
        )
    }

    @Test
    fun `the query form and the fragment form of one link give one token`() {
        val token = "eyJ1IjoidXNlci0xIn0.c2lnbmF0dXJl_-"
        val query = passwordLinkToken("https://designer-repository.vercel.app/set-password?token=$token")
        val fragment = passwordLinkToken("https://designer-repository.vercel.app/set-password#token=$token")
        assertEquals(token, query)
        assertEquals(query, fragment)
    }

    @Test
    fun `other parameters in the fragment are not swallowed into the token`() {
        assertEquals("abc.def", passwordLinkToken("https://x/set-password#token=abc.def&from=email"))
        assertEquals("abc.def", passwordLinkToken("https://x/set-password#from=email&token=abc.def"))
        assertEquals("abc.def", passwordLinkToken("https://x/set-password?lang=hi#token=abc.def"))
    }

    @Test
    fun `the percent-encoding a fragment carries is undone too`() {
        assertEquals("abc=def", passwordLinkToken("https://x/set-password#token=abc%3Ddef"))
    }

    @Test
    fun `a fragment that carries no token is not read as one`() {
        assertEquals("https://x/set-password#top", passwordLinkToken("https://x/set-password#top"))
        assertEquals(
            "https://x/set-password#notoken=abc",
            passwordLinkToken("https://x/set-password#notoken=abc")
        )
    }

    @Test
    fun `words pasted after the link are not part of the token`() {
        assertEquals("abc.def", passwordLinkToken("https://x/set-password?token=abc.def expires at 14:12"))
        assertEquals("abc.def", passwordLinkToken("https://x/set-password#token=abc.def\nAsk me if it fails."))
    }

    @Test
    fun `words pasted before the link are skipped, a hash among them included`() {
        assertEquals(
            "abc.def",
            passwordLinkToken("Message #3 from the admin: https://x/set-password?token=abc.def")
        )
    }

    @Test
    fun `a pasted token parameter on its own is read`() {
        assertEquals("abc.def", passwordLinkToken("token=abc.def"))
    }

    @Test
    fun `when a link carries both, the fragment wins, as on the web`() {
        assertEquals("BBB", passwordLinkToken("https://x/set-password?token=AAA#token=BBB"))
    }

    @Test
    fun `only an https link to the web app's set-password path is an administrator's link`() {
        assertTrue(isSetPasswordLinkAddress("https", "designer-repository.vercel.app", "/set-password"))
        assertTrue(isSetPasswordLinkAddress("HTTPS", "Designer-Repository.vercel.app", "/set-password/"))
        assertFalse(isSetPasswordLinkAddress("content", "designer-repository.vercel.app", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("http", "designer-repository.vercel.app", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("https", "evil.example.org", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("https", "designer-repository.vercel.app.evil.org", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("https", "designer-repository.vercel.app", "/set-password-x"))
        assertFalse(isSetPasswordLinkAddress("content", "media", "/external/downloads/form.dpwq"))
        assertFalse(isSetPasswordLinkAddress(null, null, null))
    }

    @Test
    fun `both hints are read off the header`() {
        assertEquals(SignInHint.AMBIGUOUS_IDENTIFIER, refusal(401, "AMBIGUOUS_IDENTIFIER").signInHint())
        assertEquals(SignInHint.PASSWORD_NOT_SET, refusal(401, "PASSWORD_NOT_SET").signInHint())
    }

    @Test
    fun `the header is read case-insensitively and trimmed`() {
        assertEquals(SignInHint.PASSWORD_NOT_SET, refusal(401, " password_not_set ").signInHint())
    }

    @Test
    fun `an absent header is NONE, which draws no panel at all`() {
        assertEquals(SignInHint.NONE, refusal(401, null).signInHint())
        assertNull(signInHintHeading(SignInHint.NONE))
    }

    @Test
    fun `a hint this build has never heard of is NONE`() {
        assertEquals(SignInHint.NONE, refusal(401, "SOMETHING_NEW").signInHint())
    }

    @Test
    fun `a failure that is not an HTTP response carries no hint`() {
        assertEquals(SignInHint.NONE, IOException("no signal").signInHint())
    }

    @Test
    fun `each hint has a heading, and it is not the server's own sentence`() {
        listOf(SignInHint.AMBIGUOUS_IDENTIFIER, SignInHint.PASSWORD_NOT_SET).forEach { hint ->
            val heading = signInHintHeading(hint)
            assertNotNull(hint.name, heading)
            assertTrue(hint.name, heading!!.isNotBlank())
            assertFalse(hint.name, heading.contains("whatever the server wrote"))
        }
        assertFalse(
            "the two hints must not share a heading",
            signInHintHeading(SignInHint.AMBIGUOUS_IDENTIFIER) == signInHintHeading(SignInHint.PASSWORD_NOT_SET)
        )
    }

    @Test
    fun `the rule line names the length floor the server enforces`() {
        assertTrue(passwordRuleLine().contains(MIN_PASSWORD_LENGTH.toString()))
        assertEquals(8, MIN_PASSWORD_LENGTH)
    }

    @Test
    fun `the second clause is the caller's and the first is not`() {
        val gate = passwordRuleLine(PASSWORD_CHANGE_SESSIONS)
        val redeem = passwordRuleLine("This link works once.")
        assertTrue(gate.startsWith(passwordRuleLine()))
        assertTrue(redeem.startsWith(passwordRuleLine()))
        assertFalse("the gate never mentions a link", gate.lowercase().contains("link"))
    }

    @Test
    fun `the gate says the change signs every other device out, as the web's forms do`() {
        assertEquals("You stay signed in here, and are signed out everywhere else.", PASSWORD_CHANGE_SESSIONS)
    }

    @Test
    fun `an invitation and a reset read differently`() {
        val invite = passwordLinkPurposeLine("INVITE")
        val reset = passwordLinkPurposeLine("RESET")
        assertFalse(invite == reset)
        assertTrue(invite.isNotBlank())
        assertTrue(reset.isNotBlank())
    }

    @Test
    fun `a purpose this build does not know still says what the link is`() {
        assertTrue(passwordLinkPurposeLine(null).isNotBlank())
        assertTrue(passwordLinkPurposeLine("SOMETHING_NEW").isNotBlank())
    }

    @Test
    fun `the gate's sentence is the server's own, word for word`() {
        assertEquals("Choose a new password to continue.", PASSWORD_GATE_SENTENCE)
    }

    @Test
    fun `the gate does not say an administrator chose the password`() {
        listOf(PASSWORD_GATE_HEADING, PASSWORD_GATE_SENTENCE).forEach { words ->
            assertTrue(words.isNotBlank())
            assertFalse(words, words.lowercase().contains("administrator"))
            assertFalse(words, words.lowercase().contains("your own"))
        }
        assertFalse("a heading that repeats the sentence says nothing", PASSWORD_GATE_HEADING == PASSWORD_GATE_SENTENCE)
    }

    @Test
    fun `a new password that matches its repeat and is not the current one may be sent`() {
        assertNull(newPasswordRefusal(next = "brand-new-1", confirm = "brand-new-1", current = "temporary-1"))
    }

    @Test
    fun `a mismatched repeat is caught here, because the server never sees it`() {
        assertEquals(
            "The two passwords do not match.",
            newPasswordRefusal(next = "brand-new-1", confirm = "brand-new-2", current = "temporary-1")
        )
    }

    @Test
    fun `the password being replaced is refused as its own replacement`() {
        val same = newPasswordRefusal(next = "temporary-1", confirm = "temporary-1", current = "temporary-1")
        assertNotNull(same)
        assertFalse(
            "it must not be mistaken for a typo in the repeat box",
            same == newPasswordRefusal(next = "a-1234567", confirm = "b-1234567")
        )
    }

    @Test
    fun `with no current password to compare, nothing is compared`() {
        assertNull(newPasswordRefusal(next = "brand-new-1", confirm = "brand-new-1"))
        assertNull(newPasswordRefusal(next = "brand-new-1", confirm = "brand-new-1", current = ""))
    }

    @Test
    fun `passwords are compared exactly, never trimmed`() {
        assertNull(newPasswordRefusal(next = " temporary-1", confirm = " temporary-1", current = "temporary-1"))
        assertNull(newPasswordRefusal(next = "Temporary-1", confirm = "Temporary-1", current = "temporary-1"))
    }

    @Test
    fun `the floor and the ceiling are the server's`() {
        assertEquals(200, MAX_PASSWORD_LENGTH)
        val floor = "a".repeat(MIN_PASSWORD_LENGTH)
        val ceiling = "a".repeat(MAX_PASSWORD_LENGTH)
        val over = "a".repeat(MAX_PASSWORD_LENGTH + 1)
        val under = "a".repeat(MIN_PASSWORD_LENGTH - 1)
        assertNull(newPasswordRefusal(next = floor, confirm = floor))
        assertNull(newPasswordRefusal(next = ceiling, confirm = ceiling))
        assertEquals(passwordRuleLine(), newPasswordRefusal(next = under, confirm = under))
        val tooLong = newPasswordRefusal(next = over, confirm = over)
        assertNotNull(tooLong)
        assertTrue("the sentence names the ceiling", tooLong!!.contains(MAX_PASSWORD_LENGTH.toString()))
    }

    @Test
    fun `the ceiling counts characters, as the server does, not UTF-16 units`() {
        val face = "😀"
        val atCeiling = face.repeat(MAX_PASSWORD_LENGTH)
        val overCeiling = face.repeat(MAX_PASSWORD_LENGTH + 1)
        assertNull(newPasswordRefusal(next = atCeiling, confirm = atCeiling))
        assertNotNull(newPasswordRefusal(next = overCeiling, confirm = overCeiling))
    }

    @Test
    fun `a 400 puts the current-password box on screen`() {
        assertTrue(passwordGateAsksForCurrentAfter(400))
    }

    @Test
    fun `nothing else does`() {
        listOf(401, 403, 422, 429, 500, null).forEach { status ->
            assertFalse("HTTP $status", passwordGateAsksForCurrentAfter(status))
        }
    }

    @Test
    fun `an account with a password is offered a link whatever its provider says`() {
        assertTrue(passwordLinkOffered(account(provider = "GOOGLE", passwordSetAt = "2026-10-01T09:00:00Z")))
        assertTrue(passwordLinkOffered(account(provider = "LOCAL", passwordSetAt = "2026-10-01T09:00:00Z")))
    }

    @Test
    fun `an account that has never had a password is not offered one, whatever its provider`() {
        assertFalse(passwordLinkOffered(account(provider = "GOOGLE", passwordSetAt = null)))
        assertFalse(passwordLinkOffered(account(provider = "LOCAL", passwordSetAt = null)))
        assertFalse(passwordLinkOffered(account(provider = null, passwordSetAt = null)))
        assertFalse(passwordLinkOffered(account(provider = "GOOGLE", passwordSetAt = "")))
    }

    private fun answered(code: Int, vararg headers: String): HttpException = HttpException(
        Response.error<Any>(
            "{\"detail\":\"whatever the server wrote\"}".toResponseBody("application/json".toMediaTypeOrNull()),
            okhttp3.Response.Builder()
                .code(code)
                .message("refused")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .headers(Headers.headersOf(*headers))
                .request(okhttp3.Request.Builder().url("http://localhost/api/auth/change-password").build())
                .build()
        )
    )

    @Test
    fun `a sign-in that has ended says why, and which password to type`() {
        assertEquals(
            "This sign-in has ended. If your password was changed on another device or by an " +
                "administrator, sign in with the new one.",
            SESSION_ENDED_SENTENCE
        )
    }

    @Test
    fun `a change that may have landed names both passwords, new first`() {
        assertEquals(
            "Your new password may already be in effect. Sign in with it; if it is refused, use the one " +
                "you were given.",
            PASSWORD_MAY_ALREADY_BE_IN_EFFECT
        )
    }

    @Test
    fun `a change nobody could confirm never says nothing has changed`() {
        listOf(PASSWORD_MAY_ALREADY_BE_IN_EFFECT, PASSWORD_CHANGE_UNCONFIRMED).forEach { words ->
            assertFalse(words, words.contains("nothing has changed"))
            assertTrue(words, words.contains("use the one you were given"))
        }
        assertTrue(PASSWORD_CHANGE_UNCONFIRMED.contains("Try again"))
    }

    @Test
    fun `the gate's usual words are the ones it always showed`() {
        assertEquals(
            "Your new password could not be sent, so nothing has changed. Try again.",
            PASSWORD_CHANGE_NOT_SENT
        )
        assertEquals(
            "This phone has no connection, so nothing has changed. Try again where there is a signal.",
            PASSWORD_CHANGE_NOT_SENT_OFFLINE
        )
    }

    @Test
    fun `an answer from the route settles the change`() {
        listOf(400, 403, 404, 422, 429).forEach { status ->
            assertTrue("HTTP $status", changePasswordOutcomeKnown(answered(status)))
        }
        assertTrue(changePasswordOutcomeKnown(answered(401, "X-Password-Change-Required", "1")))
    }

    @Test
    fun `no answer, a 5xx or a plain 401 settles nothing`() {
        assertFalse(changePasswordOutcomeKnown(IOException("timeout")))
        assertFalse(changePasswordOutcomeKnown(IOException()))
        listOf(500, 502, 503, 504).forEach { status ->
            assertFalse("HTTP $status", changePasswordOutcomeKnown(answered(status)))
        }
        assertFalse("a plain 401", changePasswordOutcomeKnown(answered(401)))
    }

    @Test
    fun `a refusal the route answered is shown at once, and nothing is asked`() {
        var probed = false
        val verdict = runBlocking {
            passwordGateAfterFailure(answered(400), online = true) {
                probed = true
                user(mustChange = true)
            }
        }
        assertEquals(PasswordGateAfterFailure.Stay("whatever the server wrote"), verdict)
        assertFalse("a settled refusal must not be followed by a probe", probed)
    }

    @Test
    fun `a probe that confirms the flag shows the usual words, online or not`() {
        val flagged = user(mustChange = true)
        assertEquals(
            PasswordGateAfterFailure.Stay(PASSWORD_CHANGE_NOT_SENT),
            runBlocking { passwordGateAfterFailure(IOException(), online = true) { flagged } }
        )
        assertEquals(
            PasswordGateAfterFailure.Stay(PASSWORD_CHANGE_NOT_SENT_OFFLINE),
            runBlocking { passwordGateAfterFailure(IOException(), online = false) { flagged } }
        )
    }

    @Test
    fun `a screen that has left decides nothing`() {
        var probed = false
        try {
            runBlocking {
                passwordGateAfterFailure(CancellationException("left"), online = true) {
                    probed = true
                    user(mustChange = true)
                }
            }
            fail("a cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("left", expected.message)
        }
        assertFalse("a cancelled screen must not probe", probed)
    }
}
