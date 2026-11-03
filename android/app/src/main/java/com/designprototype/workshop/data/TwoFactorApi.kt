package com.designprototype.workshop.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface TwoFactorApi {
    @POST("auth/two-factor/verify")
    suspend fun verify(@Body body: TwoFactorVerifyRequest): TokenResponse

    @GET("auth/two-factor")
    suspend fun status(): TwoFactorStatusDto

    @POST("auth/two-factor/setup")
    suspend fun setup(): TwoFactorSetupDto

    @POST("auth/two-factor/setup/cancel")
    suspend fun cancelSetup()

    @POST("auth/two-factor/enable")
    suspend fun enable(@Body body: TwoFactorCodeRequest): RecoveryCodesDto

    @POST("auth/two-factor/disable")
    suspend fun disable(@Body body: TwoFactorCodeRequest)

    @POST("auth/two-factor/recovery-codes")
    suspend fun recoveryCodes(@Body body: TwoFactorCodeRequest): RecoveryCodesDto

    @DELETE("users/{id}/two-factor")
    suspend fun resetForUser(@Path("id") userId: String): UserDto
}

@Serializable
data class SignInResponse(
    val accessToken: String? = null,
    val tokenType: String = "bearer",
    val user: UserDto? = null,
    @SerialName("two_factor_required") val twoFactorRequired: Boolean = false,
    @SerialName("challenge_token") val challengeToken: String? = null,
)

sealed interface SignInOutcome {
    data class SignedIn(val user: UserDto) : SignInOutcome
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
    val pending: Boolean = false,
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
