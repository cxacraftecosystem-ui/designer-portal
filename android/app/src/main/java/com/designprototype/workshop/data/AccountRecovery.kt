package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface AccountRecoveryApi {
    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): ForgotPasswordAnswer

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

    @POST("users")
    suspend fun createAccount(@Body body: CreateAccountBody): Response<CreatedAccountDto>
}

object PasswordMode {
    const val SET = "SET"
    const val GENERATE = "GENERATE"
    const val NONE = "NONE"
}

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

const val EXISTING_ACCOUNT_HEADER = "X-Existing-Account-Id"

class AccountAlreadyExists(message: String, val existingId: String?) : Exception(message)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ForgotPasswordAnswer(val message: String = "")

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

sealed interface FirstPasswordProof {
    data class Google(val idToken: String) : FirstPasswordProof
    data class Oidc(val request: OidcLoginRequest) : FirstPasswordProof
}

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

fun confirmProviderFor(user: UserDto?): String = when (user?.authProvider?.trim()?.uppercase()) {
    "MICROSOFT" -> "MICROSOFT"
    "YAHOO" -> "YAHOO"
    else -> "GOOGLE"
}

@Serializable
data class PasswordLinkDeliveryRequest(
    val userId: String,
    val delivery: String
)

@Serializable
data class TemporaryPasswordBody(
    val password: String,
    val mustChangePassword: Boolean = true
)

@Serializable
data class MailStatusDto(val available: Boolean = false)

const val PASSWORD_LINK_BY_EMAIL = "EMAIL"

const val CONFIRM_PROVIDER_HEADER = "X-Confirm-Provider"

class ProviderConfirmationRequired(message: String, val provider: String) : Exception(message)

fun firstPasswordConfirmPrompt(provider: String?): String {
    val label = when (provider?.trim()?.uppercase()) {
        "MICROSOFT" -> "Microsoft"
        "YAHOO" -> "Yahoo"
        else -> "Google"
    }
    return "To add a password, first confirm it's you by signing in with $label again."
}

const val TWO_FACTOR_REQUIRED_HEADER = "X-Two-Factor-Required"

const val FIRST_PASSWORD_CODE_MISSING = "Enter the code from your authenticator app to set a password."

class TwoFactorCodeRequired(message: String) : Exception(message)

fun accountHasPassword(user: UserDto?): Boolean {
    if (user == null) return true
    user.hasPassword?.let { return it }
    val provider = user.authProvider?.trim()?.uppercase().orEmpty()
    val providerAccount = provider == "GOOGLE" || provider == "MICROSOFT" || provider == "YAHOO"
    return !(providerAccount && user.passwordSetAt.isNullOrBlank())
}
