package com.designprototype.workshop.ui

import com.designprototype.workshop.data.SignInHint
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.signInHint
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

/**
 * THE FIRST-LOGIN PASSWORD, THE ADMINISTRATOR'S LINK, AND THE SECOND REFUSAL HEADER.
 *
 * ── WHAT IS BEING PINNED, AND WHY A TEST RATHER THAN A READING ───────────────────────────────────
 *
 * Every failure this file guards against renders perfectly. The screen draws, the words are English,
 * and the only thing wrong is that a designer is told the wrong thing about a credential — which is
 * exactly the class of defect `AccessRefusalCopyTest` was written after, one wave earlier.
 *
 * Four rules, and each of them has a way of being quietly broken:
 *
 * 1. **A null `mustChangePassword` means "no gate".** The column is nullable because a deployment
 *    older than it sends nothing, and a handset in the field may well be talking to one. A `!= false`
 *    written by somebody tidying up would hold every account on that deployment at a screen whose
 *    only working button signs them out.
 * 2. **The six link refusals are six different sentences.** "This link is not valid" is true of all
 *    of them and useful for none: expired means "ask for another", already-used means "go and sign
 *    in", which is the opposite of asking anybody for anything. A `when` that collapsed two of them
 *    would leave a person with no next action and would look completely fine on screen.
 * 3. **The token is extracted from a pasted LINK, and left alone when it is a bare token.** Get this
 *    wrong in either direction and the redeem screen refuses a link the server would have accepted.
 * 4. **The identifier hint is read off the HEADER and never out of the body.**
 *    `tests/test_platform_access_gate.py` asserts the refusal body holds nothing but `detail`, and
 *    `auth.py` records that a second field there "would be the first crack in a rule the whole
 *    feature's privacy argument rests on". A client that started parsing the body would make that
 *    server-side rule impossible to keep.
 *
 * ── AND ONE RULE ABOUT WORDS THAT MUST MATCH ACROSS TWO CLIENTS ──────────────────────────────────
 *
 * A designer who cannot get into the phone opens the website next. `signInHintHeading` here and
 * `signInHintHeading` in `frontend/lib/signIn.ts` carry the same two headings, and
 * `passwordRuleLine` here and there carry the same first clause. Nothing can check the web from a
 * JVM test, so what is pinned below is the SHAPE — that the heading exists, that it is not the
 * server's sentence, and that the rule line names the length floor — which is what stops a
 * well-meaning edit here drifting away from a file it cannot see.
 */
class PasswordSetupCopyTest {

    private fun user(mustChange: Boolean?): UserDto = UserDto(
        id = "u1",
        email = "designer@example.org",
        name = "A Designer",
        role = "DESIGNER",
        mustChangePassword = mustChange
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

    // ── 1. The gate ──────────────────────────────────────────────────────────────────────────────

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
        // THE LOAD-BEARING ONE. A null is "this deployment cannot answer", which is neither "must"
        // nor "need not" — and the only safe reading of it is that nothing is being demanded. The
        // opposite reading would hold every account on such a deployment at a screen whose only
        // working control signs them out. `usageConsentBlocks` takes the identical position on the
        // identical shape of column.
        assertFalse(mustChangePasswordBlocks(user(null)))
    }

    @Test
    fun `nobody signed in is not gated`() {
        assertFalse(mustChangePasswordBlocks(null))
    }

    // ── 2. The six link refusals ─────────────────────────────────────────────────────────────────

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
        // The one refusal whose next action is NOT "ask somebody": they already set the password.
        // Telling them to ask for another link is how a person ends up spending an administrator's
        // per-subject throttle on a link they do not need.
        val spent = passwordLinkRefusal("spent").lowercase()
        assertTrue("it points at signing in", spent.contains("sign in"))
        assertFalse("and does not send them to an administrator", spent.contains("administrator"))
    }

    @Test
    fun `expired and revoked both send the person to an administrator`() {
        // Neither is recoverable by the person holding the link, and there is exactly one remedy.
        listOf("expired", "revoked").forEach {
            assertTrue(it, passwordLinkRefusal(it).lowercase().contains("administrator"))
        }
    }

    @Test
    fun `a reason word this build has never heard of still says something`() {
        // A server newer than the handset. It must say only what a bare refusal proves — never
        // borrow another branch's next action, which would send somebody to the wrong remedy.
        val unknown = passwordLinkRefusal("some-new-reason")
        assertEquals(passwordLinkRefusal(null), unknown)
        assertTrue(unknown.isNotBlank())
        assertFalse(unknown.lowercase().contains("expired"))
        assertFalse(unknown.lowercase().contains("already been used"))
    }

    // ── 3. The token out of whatever was pasted ──────────────────────────────────────────────────

    @Test
    fun `a whole pasted link yields the token`() {
        assertEquals(
            "abc.def",
            passwordLinkToken("https://portal.example.org/set-password?token=abc.def")
        )
    }

    @Test
    fun `a bare token is left exactly as it is`() {
        // The other half of the same rule: an administrator may read the token out without the
        // address around it, and a parser that insisted on a URL would refuse it.
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
        // A token is base64url and the link encodes it; Retrofit encodes whatever it is given AGAIN,
        // so sending the encoded form would put "%3D" inside the signed payload and the HMAC would
        // not verify — a valid link refused, with the server's "not a link this site issued".
        assertEquals("abc=def", passwordLinkToken("https://x/set-password?token=abc%3Ddef"))
    }

    @Test
    fun `a malformed escape is left standing rather than throwing`() {
        // Somebody pasted something odd. They are owed the SERVER's refusal, not a crash.
        assertEquals("abc%zz", passwordLinkToken("https://x/set-password?token=abc%zz"))
    }

    @Test
    fun `an empty paste is an empty token, not a request`() {
        assertEquals("", passwordLinkToken("   "))
    }

    // ── 4. The identifier hint rides the header ──────────────────────────────────────────────────

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
        // A proxy that strips unknown headers, or a deployment older than this handset, produces the
        // same absence as an ordinary mistyped password. Neutral chrome around the server's own
        // sentence is the documented safe direction; guessing is the only way to produce a WRONG
        // heading, which is worse than producing none.
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
        // The heading is drawn AROUND the server's `detail`, never instead of it, so the two must
        // not be the same words. Nothing here composes advice: the server's sentence already names
        // the one next move, which is why these panels are terser than `accessRefusalChrome`'s.
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

    // ── 5. The password rule line ────────────────────────────────────────────────────────────────

    @Test
    fun `the rule line names the length floor the server enforces`() {
        assertTrue(passwordRuleLine().contains(MIN_PASSWORD_LENGTH.toString()))
        assertEquals(8, MIN_PASSWORD_LENGTH)
    }

    @Test
    fun `the second clause is the caller's and the first is not`() {
        // The gate must not tell somebody about a link they are not holding, and the redeem screen
        // must say that its link works once. So the suffix varies and the floor does not.
        val gate = passwordRuleLine("Other devices stay signed in.")
        val redeem = passwordRuleLine("This link works once.")
        assertTrue(gate.startsWith(passwordRuleLine()))
        assertTrue(redeem.startsWith(passwordRuleLine()))
        assertFalse("the gate never mentions a link", gate.lowercase().contains("link"))
    }

    // ── 6. The purpose line beside an issued link ────────────────────────────────────────────────

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
}
