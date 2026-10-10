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
 * 3. **The token is extracted from a pasted LINK — from its query or, since 2026-10-09, from its
 *    fragment — and left alone when it is a bare token.** Get this wrong in any direction and the
 *    redeem screen refuses a link the server would have accepted.
 * 4. **The identifier hint is read off the HEADER and never out of the body.**
 *    `tests/test_platform_access_gate.py` asserts the refusal body holds nothing but `detail`, and
 *    `auth.py` records that a second field there "would be the first crack in a rule the whole
 *    feature's privacy argument rests on". A client that started parsing the body would make that
 *    server-side rule impossible to keep.
 *
 * Four more arrived with the server-side gate on 2026-10-09 (sections 7 to 10): the gate's words
 * blame nobody and are the server's own; a password is refused as its own replacement; the carried
 * one becomes a box once the server has refused it; and a set-password link is offered by "has a
 * password", never by "is not Google".
 *
 * And one more with the sessions bound to their password (section 11): an ended sign-in says which
 * password to type, and a change whose answer was lost is never reported as "nothing has changed"
 * on the strength of the missing answer alone. The probe paths themselves, over the app's own
 * Retrofit, are in `ChangePasswordSessionTest`.
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

    // ── 3b. The same token when the link carries it in the FRAGMENT ─────────────────────────────
    //
    // Where the server is moving links: `/set-password#token=…`, because a fragment is never sent to
    // any server, so the token stays out of the web host's request log and the browser's history.
    // Links of the old shape stay in chat histories until they expire, so both are read, and they
    // must give the same answer — a link that worked yesterday and is refused today because its
    // token moved would look exactly like an expired one.

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
        // A query that names something else, and the token in the fragment after it.
        assertEquals("abc.def", passwordLinkToken("https://x/set-password?lang=hi#token=abc.def"))
    }

    @Test
    fun `the percent-encoding a fragment carries is undone too`() {
        assertEquals("abc=def", passwordLinkToken("https://x/set-password#token=abc%3Ddef"))
    }

    @Test
    fun `a fragment that carries no token is not read as one`() {
        // No token anywhere: the text goes to the server as typed, and its refusal word says why.
        assertEquals("https://x/set-password#top", passwordLinkToken("https://x/set-password#top"))
        // `token=` must open a parameter; the end of a longer name is not it.
        assertEquals(
            "https://x/set-password#notoken=abc",
            passwordLinkToken("https://x/set-password#notoken=abc")
        )
    }

    // ── 3c. A paste that is the whole message, and a link that carries both ─────────────────────
    //
    // A designer copies the administrator's whole message more often than the link alone, and a
    // base64url token holds no whitespace. Read whole, the words after the link were sent as part of
    // the token, came back "malformed", and hid the password boxes for a link that was good.

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
        // `takeLinkTokenFromAddress` on the web reads the fragment first; one link pasted on either
        // client must give one token.
        assertEquals("BBB", passwordLinkToken("https://x/set-password?token=AAA#token=BBB"))
    }

    // ── 3d. What the activity will take from an intent ──────────────────────────────────────────

    @Test
    fun `only an https link to the web app's set-password path is an administrator's link`() {
        assertTrue(isSetPasswordLinkAddress("https", "designer-repository.vercel.app", "/set-password"))
        assertTrue(isSetPasswordLinkAddress("HTTPS", "Designer-Repository.vercel.app", "/set-password/"))
        // An explicit intent from another app is never seen by the manifest's filter.
        assertFalse(isSetPasswordLinkAddress("content", "designer-repository.vercel.app", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("http", "designer-repository.vercel.app", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("https", "evil.example.org", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("https", "designer-repository.vercel.app.evil.org", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("https", "designer-repository.vercel.app", "/set-password-x"))
        // A questionnaire file arriving through the VIEW filters is not a password link either.
        assertFalse(isSetPasswordLinkAddress("content", "media", "/external/downloads/form.dpwq"))
        assertFalse(isSetPasswordLinkAddress(null, null, null))
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
        val gate = passwordRuleLine(PASSWORD_CHANGE_SESSIONS)
        val redeem = passwordRuleLine("This link works once.")
        assertTrue(gate.startsWith(passwordRuleLine()))
        assertTrue(redeem.startsWith(passwordRuleLine()))
        assertFalse("the gate never mentions a link", gate.lowercase().contains("link"))
    }

    @Test
    fun `the gate says the change signs every other device out, as the web's forms do`() {
        // Since 2026-10-09 a change ends every session of the account but the fresh one its answer
        // carries. "Other devices stay signed in." — what this line said until then — is now the one
        // thing it must not say, about the tablet in the next room and about whoever else signed in
        // with a temporary password. The web's `PASSWORD_CHANGE_SESSIONS`, word for word.
        assertEquals("You stay signed in here, and are signed out everywhere else.", PASSWORD_CHANGE_SESSIONS)
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

    // ── 7. The gate's words ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the gate's sentence is the server's own, word for word`() {
        // The detail a session held at the gate is refused with, and the web gate's sentence. A
        // person who meets the gate in three places must read one thing.
        assertEquals("Choose a new password to continue.", PASSWORD_GATE_SENTENCE)
    }

    @Test
    fun `the gate does not say an administrator chose the password`() {
        // It is no longer true of everybody it is shown to: an administrator can require a change of
        // a password the person chose themselves, and "an administrator set your password" then
        // reads as somebody having been into the account.
        listOf(PASSWORD_GATE_HEADING, PASSWORD_GATE_SENTENCE).forEach { words ->
            assertTrue(words.isNotBlank())
            assertFalse(words, words.lowercase().contains("administrator"))
            assertFalse(words, words.lowercase().contains("your own"))
        }
        assertFalse("a heading that repeats the sentence says nothing", PASSWORD_GATE_HEADING == PASSWORD_GATE_SENTENCE)
    }

    // ── 8. What the screens refuse before spending a request ─────────────────────────────────────

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
        // THE LOAD-BEARING ONE. Re-entering the temporary password used to satisfy the gate and leave
        // the shared secret as the live password, with every screen treating it as self-chosen.
        val same = newPasswordRefusal(next = "temporary-1", confirm = "temporary-1", current = "temporary-1")
        assertNotNull(same)
        assertFalse(
            "it must not be mistaken for a typo in the repeat box",
            same == newPasswordRefusal(next = "a-1234567", confirm = "b-1234567")
        )
    }

    @Test
    fun `with no current password to compare, nothing is compared`() {
        // The redeem screen holds no current password, and the gate holds none until one is typed:
        // an empty one must never match anything and refuse a perfectly good choice.
        assertNull(newPasswordRefusal(next = "brand-new-1", confirm = "brand-new-1"))
        assertNull(newPasswordRefusal(next = "brand-new-1", confirm = "brand-new-1", current = ""))
    }

    @Test
    fun `passwords are compared exactly, never trimmed`() {
        // A space is part of a password. Trimming here would refuse a different password as "the
        // same", or let the same one through as different on a server that does not trim either.
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
        // An emoji is one character to the server and two `Char`s to Kotlin. Counting units would
        // refuse a password the server accepts.
        val face = "😀"
        val atCeiling = face.repeat(MAX_PASSWORD_LENGTH)
        val overCeiling = face.repeat(MAX_PASSWORD_LENGTH + 1)
        assertNull(newPasswordRefusal(next = atCeiling, confirm = atCeiling))
        assertNotNull(newPasswordRefusal(next = overCeiling, confirm = overCeiling))
    }

    // ── 9. When the carried password becomes a box ───────────────────────────────────────────────

    @Test
    fun `a 400 puts the current-password box on screen`() {
        // A wrong current password is a 400 since 2026-10-09. Without the box, the hidden carried
        // password could not be corrected and "Sign out instead" was the only control that worked.
        assertTrue(passwordGateAsksForCurrentAfter(400))
    }

    @Test
    fun `nothing else does`() {
        // 401 is the session, not the password; 429 is the guessing budget; 422 is the new password's
        // shape; null is no answer at all. None of them says the carried password was wrong.
        listOf(401, 403, 422, 429, 500, null).forEach { status ->
            assertFalse("HTTP $status", passwordGateAsksForCurrentAfter(status))
        }
    }

    // ── 10. Who an administrator may send a password link to ─────────────────────────────────────

    @Test
    fun `an account with a password is offered a link whatever its provider says`() {
        // THE CASE THAT WAS WRONG. A Google sign-in used to rewrite the provider to GOOGLE and keep
        // the password; the button then vanished for an account that most needed a reset.
        assertTrue(passwordLinkOffered(account(provider = "GOOGLE", passwordSetAt = "2026-10-01T09:00:00Z")))
        assertTrue(passwordLinkOffered(account(provider = "LOCAL", passwordSetAt = "2026-10-01T09:00:00Z")))
    }

    @Test
    fun `an account that has never had a password is not offered one, whatever its provider`() {
        // A link would give a password to an account that never had one without anybody deciding
        // to; that is a decision for the web's users page. The web keys its button identically.
        assertFalse(passwordLinkOffered(account(provider = "GOOGLE", passwordSetAt = null)))
        assertFalse(passwordLinkOffered(account(provider = "LOCAL", passwordSetAt = null)))
        assertFalse(passwordLinkOffered(account(provider = null, passwordSetAt = null)))
        assertFalse(passwordLinkOffered(account(provider = "GOOGLE", passwordSetAt = "")))
    }

    // ── 11. When a session has ended, and when a change's answer is lost ─────────────────────────

    /** An answer from `POST /auth/change-password`, with the headers given as name, value, …. */
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
        // "Your session expired" gave no reason, and since 2026-10-09 the usual one is a password
        // changed somewhere else — so the person must be told to use the NEW one.
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
        // THE CLAIM THAT LOCKED PEOPLE OUT. Told nothing had changed, they typed the temporary password
        // at the door, were refused, and concluded they were locked out of an account whose new
        // password worked all along.
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
        // Refused before the write, with a sentence that says why.
        listOf(400, 403, 404, 422, 429).forEach { status ->
            assertTrue("HTTP $status", changePasswordOutcomeKnown(answered(status)))
        }
        // The gate's own 401 is a live session still owing a password: nothing was written.
        assertTrue(changePasswordOutcomeKnown(answered(401, "X-Password-Change-Required", "1")))
    }

    @Test
    fun `no answer, a 5xx or a plain 401 settles nothing`() {
        // The server commits before it answers and retires the token the change was sent with, so a
        // failure that may have come after the send says nothing about the password.
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
        // A failure with no words of its own, then `GET /me` says the account still owes a password:
        // nothing landed, so "nothing has changed" is now true.
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
        // `runCatching` in the gate catches a cancellation along with everything else; it must come
        // straight back out rather than be read as a failed change and probed.
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
