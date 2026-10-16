package com.designprototype.workshop.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Two-step verification (TOTP) — `/api/auth/two-factor` and the administrator's reset.
 *
 * The mechanism lives on the server (`backend/app/services/two_factor.py`): the code is checked and
 * SPENT there, the recovery codes are hashed there, and the QR square is drawn there as SVG. This
 * file is the wire and nothing else. The field names are snake_case because the designer portal and
 * the documentation portal implement one shared spec with one shape, and this file is meant to be
 * copyable between the two apps.
 *
 * A SERVICE OF ITS OWN, built from [ApiClient.retrofit] for the reason [UsageApi] gives: the bearer
 * header, the 504 retry, the timeouts and the lenient decoder come with it.
 */
interface TwoFactorApi {
    /** The second half of a sign-in. Unauthenticated: the challenge token is the whole authority. */
    @POST("auth/two-factor/verify")
    suspend fun verify(@Body body: TwoFactorVerifyRequest): TokenResponse

    @GET("auth/two-factor")
    suspend fun status(): TwoFactorStatusDto

    @POST("auth/two-factor/setup")
    suspend fun setup(): TwoFactorSetupDto

    @POST("auth/two-factor/enable")
    suspend fun enable(@Body body: TwoFactorCodeRequest): RecoveryCodesDto

    /** 204 on success. */
    @POST("auth/two-factor/disable")
    suspend fun disable(@Body body: TwoFactorCodeRequest)

    @POST("auth/two-factor/recovery-codes")
    suspend fun recoveryCodes(@Body body: TwoFactorCodeRequest): RecoveryCodesDto

    /** An account provisioner turning somebody else's off. Ends that person's sessions. */
    @DELETE("users/{id}/two-factor")
    suspend fun resetForUser(@Path("id") userId: String): UserDto
}

/**
 * What `POST /auth/login` answers: a session, OR — for an account with two-step verification on — a
 * challenge and no session. One class for both because Retrofit decodes one type per method;
 * [WorkshopRepository]'s sign-in calls turn it into a [SignInOutcome], and nothing else reads it.
 */
@Serializable
data class SignInResponse(
    val accessToken: String? = null,
    val tokenType: String = "bearer",
    val user: UserDto? = null,
    @SerialName("two_factor_required") val twoFactorRequired: Boolean = false,
    @SerialName("challenge_token") val challengeToken: String? = null,
)

/** What a first factor led to. */
sealed interface SignInOutcome {
    data class SignedIn(val user: UserDto) : SignInOutcome
    /** The account has two-step verification on: ask for a code, then [WorkshopRepository.verifyTwoStep]. */
    data class TwoStepRequired(val challengeToken: String) : SignInOutcome
}

@Serializable
data class TwoFactorVerifyRequest(
    @SerialName("challenge_token") val challengeToken: String,
    val code: String,
)

@Serializable
data class TwoFactorCodeRequest(val code: String)

@Serializable
data class TwoFactorStatusDto(
    val enabled: Boolean = false,
    @SerialName("recovery_codes_remaining") val recoveryCodesRemaining: Int = 0,
    @SerialName("enabled_at") val enabledAt: String? = null,
)

@Serializable
data class TwoFactorSetupDto(
    val secret: String,
    @SerialName("otpauth_uri") val otpauthUri: String,
    @SerialName("qr_svg") val qrSvg: String,
)

@Serializable
data class RecoveryCodesDto(
    @SerialName("recovery_codes") val recoveryCodes: List<String> = emptyList(),
)
