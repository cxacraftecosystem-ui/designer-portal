package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Getting back into an account — the handset's half of the routes both portals share:
 *
 *   * `POST /auth/forgot-password` — "Forgot your password?" on the sign-in card. Always the same
 *     answer for a well-formed address, known or not; only a malformed address (422) or too many
 *     requests from this connection (429) answers differently, and both carry the server's sentence.
 *   * `POST /auth/change-password` with no current password — a Google, Microsoft or Yahoo account
 *     adding its first password. Allowed only with a provider sign-in run again just now for this
 *     account ([FirstPasswordProof]); otherwise a 400 carrying [CONFIRM_PROVIDER_HEADER].
 *   * `POST /auth/password-links` with `delivery: "EMAIL"` — an administrator e-mailing the person a
 *     link instead of copying one. The answer carries no link.
 *   * `PATCH /users/{id}` with a password — a temporary password, changed at the next sign-in.
 *   * `POST /users/{id}/sign-out-everywhere` — every device that account is signed in on has to sign in
 *     again.
 *   * `GET /preferences/notifications` — read only for `available`: whether this deployment sends mail,
 *     which decides whether "Email them a link" is offered.
 *
 * A SERVICE OF ITS OWN, built from [ApiClient.retrofit] as `TwoFactorApi` is, so this file is the wire
 * and nothing else, and copyable between the two apps. None of these routes mints a session: a person
 * who sets a password through a link or the forgotten-password e-mail signs in afterwards and meets
 * the two-step code like any other sign-in.
 */
interface AccountRecoveryApi {
    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): ForgotPasswordAnswer

    /** A first password for an account that has none. A `Response` for the session-token header. */
    @POST("auth/change-password")
    suspend fun setFirstPassword(@Body body: FirstPasswordRequest): Response<ChangePasswordResponse>

    @POST("auth/password-links")
    suspend fun issuePasswordLink(@Body body: PasswordLinkDeliveryRequest): IssuedPasswordLinkDto

    @PATCH("users/{id}")
    suspend fun setTemporaryPassword(@Path("id") userId: String, @Body body: TemporaryPasswordBody): UserDto

    @POST("users/{id}/sign-out-everywhere")
    suspend fun signOutEverywhere(@Path("id") userId: String): UserDto

    @GET("preferences/notifications")
    suspend fun mailStatus(): MailStatusDto

    /**
     * "Add a person": the account, its allow-list admission and its role's own list, in one step on
     * the server. A `Response` so a 409 can be read for [EXISTING_ACCOUNT_HEADER].
     */
    @POST("users")
    suspend fun createAccount(@Body body: CreateAccountBody): Response<CreatedAccountDto>
}

/** How a new person will sign in — the `passwordMode` of `POST /users`. */
object PasswordMode {
    const val SET = "SET"
    const val GENERATE = "GENERATE"
    const val NONE = "NONE"
}

/**
 * `POST /users` for "Add a person". Null fields are absent from the body (`explicitNulls = false`):
 * `password` only for [PasswordMode.SET], `sendPasswordLink` only for [PasswordMode.NONE].
 */
@Serializable
data class CreateAccountBody(
    val email: String,
    val name: String,
    val role: String,
    val passwordMode: String,
    val password: String? = null,
    val mustChangePassword: Boolean? = null,
    val sendPasswordLink: Boolean? = null
)

/**
 * The account as created, plus what came with it ONCE: a generated [temporaryPassword], the
 * e-mailed [passwordLink] (no link inside it — it went to their inbox), or why it could not be sent.
 */
@Serializable
data class CreatedAccountDto(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    val role: String = "",
    val temporaryPassword: String? = null,
    val passwordLink: IssuedPasswordLinkDto? = null,
    val passwordLinkError: String? = null
)

/** The header a duplicate-address 409 names the existing account in. */
const val EXISTING_ACCOUNT_HEADER = "X-Existing-Account-Id"

/** The address already has an account: the server's sentence, and which account, when it said. */
class AccountAlreadyExists(message: String, val existingId: String?) : Exception(message)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ForgotPasswordAnswer(val message: String = "")

/**
 * No `currentPassword` key at all: there is none to send, and the server reads its absence.
 * [twoFactorCode] is sent only for an account with two-step verification on. The provider proof
 * (2026-10-17) is either [googleIdToken] or the five `oidc*` fields, exactly as `POST /auth/login`
 * takes them; every null is left out of the body.
 */
@Serializable
data class FirstPasswordRequest(
    val newPassword: String,
    val twoFactorCode: String? = null,
    val googleIdToken: String? = null,
    val oidcProvider: String? = null,
    val oidcCode: String? = null,
    val oidcCodeVerifier: String? = null,
    val oidcRedirectUri: String? = null,
    val oidcNonce: String? = null,
)

/**
 * What a first password travels with: the provider's own answer to a sign-in run again just now.
 * The server accepts it only when it was issued in the last few minutes and names this account's
 * mailbox, so a session on its own can no longer add a password.
 */
sealed interface FirstPasswordProof {
    data class Google(val idToken: String) : FirstPasswordProof
    data class Oidc(val request: OidcLoginRequest) : FirstPasswordProof
}

/** The request body for [proof]. */
fun firstPasswordRequest(newPassword: String, twoFactorCode: String?, proof: FirstPasswordProof?): FirstPasswordRequest {
    val code = twoFactorCode?.trim()?.takeIf { it.isNotEmpty() }
    return when (proof) {
        is FirstPasswordProof.Google -> FirstPasswordRequest(newPassword, code, googleIdToken = proof.idToken)
        is FirstPasswordProof.Oidc -> FirstPasswordRequest(
            newPassword,
            code,
            oidcProvider = proof.request.oidcProvider,
            oidcCode = proof.request.oidcCode,
            oidcCodeVerifier = proof.request.oidcCodeVerifier,
            oidcRedirectUri = proof.request.oidcRedirectUri,
            oidcNonce = proof.request.oidcNonce,
        )
        null -> FirstPasswordRequest(newPassword, code)
    }
}

/**
 * Which provider an account confirms with before a first password: the one it signs in with, or
 * Google for an account that names none — the server's own fallback.
 */
fun confirmProviderFor(user: UserDto?): String = when (user?.authProvider?.trim()?.uppercase()) {
    "MICROSOFT" -> "MICROSOFT"
    "YAHOO" -> "YAHOO"
    else -> "GOOGLE"
}

@Serializable
data class PasswordLinkDeliveryRequest(
    val userId: String,
    /** `COPY_LINK` or `EMAIL` — see [PASSWORD_LINK_BY_EMAIL]. */
    val delivery: String
)

/** A password typed for somebody else is temporary: they choose their own at the next sign-in. */
@Serializable
data class TemporaryPasswordBody(
    val password: String,
    val mustChangePassword: Boolean = true
)

@Serializable
data class MailStatusDto(val available: Boolean = false)

/** The `delivery` value that e-mails the link to the account's own address. */
const val PASSWORD_LINK_BY_EMAIL = "EMAIL"

/**
 * The header a refused first-password answer carries when the provider proof was missing, stale or
 * for another mailbox. Its value names the provider to confirm with: GOOGLE, MICROSOFT or YAHOO.
 */
const val CONFIRM_PROVIDER_HEADER = "X-Confirm-Provider"

/** Thrown by `WorkshopRepository.saveOwnPassword` for the [CONFIRM_PROVIDER_HEADER] refusal. */
class ProviderConfirmationRequired(message: String, val provider: String) : Exception(message)

/** The sentence the set-password card shows before it asks the provider — the server's words. */
fun firstPasswordConfirmPrompt(provider: String?): String {
    val label = when (provider?.trim()?.uppercase()) {
        "MICROSOFT" -> "Microsoft"
        "YAHOO" -> "Yahoo"
        else -> "Google"
    }
    return "To add a password, first confirm it's you by signing in with $label again."
}

/**
 * The header a refused first-password answer carries when the account has two-step verification on
 * and the code was missing or wrong. The server's sentence says which.
 */
const val TWO_FACTOR_REQUIRED_HEADER = "X-Two-Factor-Required"

/** The server's sentence for a missing code, also said by the form before it sends anything. */
const val FIRST_PASSWORD_CODE_MISSING = "Enter the code from your authenticator app to set a password."

/** Thrown by `WorkshopRepository.saveOwnPassword` for the [TWO_FACTOR_REQUIRED_HEADER] refusal. */
class TwoFactorCodeRequired(message: String) : Exception(message)

/**
 * Does this account hold a password of its own?
 *
 * The server's `hasPassword` when it sends one. An older server does not, and then the account is
 * read as password-less only when it signs in with a provider AND has never had a password set — a
 * password account from before `passwordSetAt` existed carries LOCAL, and must keep being asked for
 * its current password rather than offered a "set a first password" form the server would refuse.
 */
fun accountHasPassword(user: UserDto?): Boolean {
    if (user == null) return true
    user.hasPassword?.let { return it }
    val provider = user.authProvider?.trim()?.uppercase().orEmpty()
    val providerAccount = provider == "GOOGLE" || provider == "MICROSOFT" || provider == "YAHOO"
    return !(providerAccount && user.passwordSetAt.isNullOrBlank())
}
