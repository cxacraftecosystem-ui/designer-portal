package com.designprototype.workshop.ui

import com.designprototype.workshop.data.AccessRosterCreateBody
import com.designprototype.workshop.data.FirstPasswordProof
import com.designprototype.workshop.data.FirstPasswordRequest
import com.designprototype.workshop.data.OidcLoginRequest
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.accountHasPassword
import com.designprototype.workshop.data.confirmProviderFor
import com.designprototype.workshop.data.firstPasswordConfirmPrompt
import com.designprototype.workshop.data.firstPasswordRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GETTING BACK IN: the forgotten-password screen, Settings → Password, the administrator's "help them
 * back in" actions and the access list's "How will they sign in?". Every rule here renders fine when
 * broken, so each is pinned.
 */
class AccountRecoveryCopyTest {
    /** The app decoder's two encoding-relevant flags (`ApiClient.json`), without starting the client. */
    private val wire = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun user(
        provider: String? = "LOCAL",
        passwordSetAt: String? = null,
        hasPassword: Boolean? = null,
    ) = UserDto(
        id = "u1",
        email = "asha@example.org",
        name = "Asha",
        role = "RESEARCHER",
        authProvider = provider,
        passwordSetAt = passwordSetAt,
        hasPassword = hasPassword,
    )

    @Test
    fun `the server's hasPassword wins whenever it is sent`() {
        assertTrue(accountHasPassword(user(provider = "GOOGLE", hasPassword = true)))
        assertFalse(accountHasPassword(user(provider = "LOCAL", passwordSetAt = "2026-01-01", hasPassword = false)))
    }

    @Test
    fun `an older server reads a provider account with no password stamp as password-less`() {
        assertFalse(accountHasPassword(user(provider = "GOOGLE")))
        assertFalse(accountHasPassword(user(provider = "microsoft")))
        assertTrue(accountHasPassword(user(provider = "GOOGLE", passwordSetAt = "2026-01-01")))
        // A password account from before `passwordSetAt` existed must still be asked for its current one.
        assertTrue(accountHasPassword(user(provider = "LOCAL")))
        assertTrue(accountHasPassword(null))
    }

    @Test
    fun `forgot password checks only the shape of the address`() {
        assertEquals("Enter your email address.", forgotPasswordEmailProblem("   "))
        assertNotNull(forgotPasswordEmailProblem("asha"))
        assertNotNull(forgotPasswordEmailProblem("a@b@c.org"))
        assertNull(forgotPasswordEmailProblem(" asha+field@example.org "))
    }

    @Test
    fun `the server's one answer is printed verbatim`() {
        assertEquals("Exactly this.", forgotPasswordShown("  Exactly this. "))
        assertEquals(FORGOT_PASSWORD_FALLBACK, forgotPasswordShown(""))
        assertEquals(FORGOT_PASSWORD_FALLBACK, forgotPasswordShown(null))
        assertEquals("If that address has an account, a reset link is on its way.", FORGOT_PASSWORD_FALLBACK)
    }

    @Test
    fun `the forgotten-password screen never admits anything is missing`() {
        listOf(FORGOT_PASSWORD_INTRO, FORGOT_PASSWORD_FALLBACK, FORGOT_PASSWORD_NO_EMAIL_NOTE, FORGOT_PASSWORD_TWO_STEP_NOTE)
            .forEach { line ->
                val lower = line.lowercase()
                assertFalse(line, "not available" in lower || "coming soon" in lower || "not built" in lower)
            }
    }

    @Test
    fun `the password card names what it does and how they sign in`() {
        assertEquals("Change your password", passwordCardTitle(true))
        assertEquals("Set a password", passwordCardTitle(false))
        assertTrue(passwordCardIntro(false, user(provider = "GOOGLE")).contains("Google"))
        assertTrue(passwordCardIntro(false, user(provider = "YAHOO")).contains("either"))
        assertTrue(passwordCardIntro(true, user()).contains(PASSWORD_CHANGE_SESSIONS))
        assertEquals("another sign-in", providerLabel(null))
    }

    @Test
    fun `a temporary password follows the same length rules as every other password`() {
        assertEquals(passwordRuleLine(), temporaryPasswordProblem("short"))
        assertEquals(passwordRuleLine(), temporaryPasswordProblem("        "))
        assertNull(temporaryPasswordProblem("long enough"))
        assertNotNull(temporaryPasswordProblem("x".repeat(MAX_PASSWORD_LENGTH + 1)))
    }

    @Test
    fun `email links are offered only where mail is sent and a copied link would be`() {
        val withPassword = user(passwordSetAt = "2026-01-01")
        assertTrue(emailLinkOffered(withPassword, mailAvailable = true))
        assertFalse(emailLinkOffered(withPassword, mailAvailable = false))
        assertFalse(emailLinkOffered(user(provider = "GOOGLE"), mailAvailable = true))
    }

    @Test
    fun `adding with a password needs a name and a valid password, a provider sign-in needs neither`() {
        assertNull(accessAddProblem(AccessSignIn.PROVIDER, "", ""))
        assertNotNull(accessAddProblem(AccessSignIn.PASSWORD, "long enough", " "))
        assertEquals(passwordRuleLine(), accessAddProblem(AccessSignIn.PASSWORD, "short", "Asha"))
        assertNull(accessAddProblem(AccessSignIn.PASSWORD, "long enough", "Asha"))
    }

    @Test
    fun `the access body carries no password keys for a provider sign-in`() {
        val provider = wire.encodeToString(AccessRosterCreateBody(email = "a@b.org"))
        assertFalse(provider.contains("password"))
        assertFalse(provider.contains("mustChangePassword"))
        val withPassword = wire.encodeToString(
            AccessRosterCreateBody(email = "a@b.org", password = "long enough", mustChangePassword = true)
        )
        assertTrue(withPassword.contains("\"password\":\"long enough\""))
        assertTrue(withPassword.contains("\"mustChangePassword\":true"))
    }

    @Test
    fun `a first password is sent with no current password key at all`() {
        val body = wire.encodeToString(FirstPasswordRequest(newPassword = "long enough"))
        assertEquals("{\"newPassword\":\"long enough\"}", body)
    }

    @Test
    fun `a first password carries the two-step code only when there is one`() {
        val body = wire.encodeToString(FirstPasswordRequest(newPassword = "long enough", twoFactorCode = "123456"))
        assertEquals("{\"newPassword\":\"long enough\",\"twoFactorCode\":\"123456\"}", body)
    }

    @Test
    fun `a first password carries a fresh Google proof as the sign-in does`() {
        val body = wire.encodeToString(firstPasswordRequest("long enough", " ", FirstPasswordProof.Google("id-token")))
        assertEquals("{\"newPassword\":\"long enough\",\"googleIdToken\":\"id-token\"}", body)
    }

    @Test
    fun `a first password carries a Microsoft or Yahoo code with its verifier, redirect and nonce`() {
        val proof = FirstPasswordProof.Oidc(
            OidcLoginRequest(
                oidcProvider = "MICROSOFT",
                oidcCode = "c",
                oidcCodeVerifier = "v",
                oidcRedirectUri = "r",
                oidcNonce = "n",
            )
        )
        val body = wire.encodeToString(firstPasswordRequest("long enough", "123456", proof))
        assertTrue(body.contains("\"twoFactorCode\":\"123456\""))
        assertTrue(body.contains("\"oidcProvider\":\"MICROSOFT\""))
        assertTrue(body.contains("\"oidcCodeVerifier\":\"v\""))
        assertTrue(body.contains("\"oidcNonce\":\"n\""))
        assertFalse(body.contains("googleIdToken"))
    }

    @Test
    fun `the provider to confirm with is the one the account signs in with`() {
        assertEquals("GOOGLE", confirmProviderFor(user(provider = "GOOGLE")))
        assertEquals("MICROSOFT", confirmProviderFor(user(provider = "microsoft")))
        assertEquals("YAHOO", confirmProviderFor(user(provider = "YAHOO")))
        assertEquals("GOOGLE", confirmProviderFor(user(provider = "LOCAL")))
        assertEquals("GOOGLE", confirmProviderFor(null))
        assertEquals(
            "To add a password, first confirm it's you by signing in with Microsoft again.",
            firstPasswordConfirmPrompt("MICROSOFT"),
        )
        assertTrue(firstPasswordConfirmNote("YAHOO").contains("Yahoo"))
    }

    @Test
    fun `the code box is asked for only when a first password is set with two-step on`() {
        val twoStep = user(provider = "GOOGLE", hasPassword = false).copy(twoFactorEnabled = true)
        assertTrue(firstPasswordNeedsCode(hasPassword = false, user = twoStep))
        assertFalse(firstPasswordNeedsCode(hasPassword = true, user = twoStep))
        assertFalse(firstPasswordNeedsCode(hasPassword = false, user = user(provider = "GOOGLE", hasPassword = false)))
        assertFalse(firstPasswordNeedsCode(hasPassword = false, user = null))
    }

    @Test
    fun `sign out everywhere says what stays as it is`() {
        val body = signOutEverywhereBody("Asha")
        assertTrue(body.contains("sign in again"))
        assertTrue(body.contains("two-step"))
    }
}
