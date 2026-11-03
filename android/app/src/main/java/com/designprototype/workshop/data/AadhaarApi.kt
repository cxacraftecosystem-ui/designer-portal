package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class FeaturesDto(
    val aadhaarOfflineVerification: Boolean = false,
    val aadhaarOnlineAuth: Boolean = false,
    val aadhaarOnlineEkyc: Boolean = false,
)

data class AadhaarFeatures(val offline: Boolean, val online: Boolean) {
    val enabled: Boolean get() = offline || online

    companion object {
        val OFF = AadhaarFeatures(offline = false, online = false)

        fun of(user: UserDto?): AadhaarFeatures {
            val features = user?.features ?: return OFF
            return AadhaarFeatures(
                offline = features.aadhaarOfflineVerification,
                online = features.aadhaarOnlineAuth,
            )
        }
    }
}

@Serializable
data class AadhaarSummaryDto(
    val status: String? = null,
    val verified: Boolean = false,
    val method: String? = null,
    val verifiedAt: String? = null,
    val last4: String? = null,
    val name: String? = null,
    val recordName: String? = null,
    val nameMatch: Boolean? = null,
    val birthYear: Int? = null,
    val dateOfBirth: String? = null,
    val gender: String? = null,
    val signedAt: String? = null,
    val decidedAt: String? = null,
)

@Serializable
data class AadhaarSecureQrRequest(val consent: Boolean, val qrText: String)

@Serializable
data class AadhaarOfflineXmlRequest(
    val consent: Boolean,
    val zipBase64: String,
    val shareCode: String,
    val mobile: String? = null,
    val email: String? = null,
)

@Serializable
data class AadhaarOtpRequest(val consent: Boolean, val uid: String)

@Serializable
data class AadhaarOtpIssuedDto(val txn: String, val token: String, val expiresAt: String, val uidType: String)

@Serializable
data class AadhaarOtpVerifyRequest(
    val consent: Boolean,
    val uid: String,
    val otp: String,
    val txn: String,
    val token: String,
)

@Serializable
data class AadhaarDecisionRequest(val accept: Boolean)

interface AadhaarApi {
    @GET("aadhaar/self")
    suspend fun readSelf(): AadhaarSummaryDto

    @POST("aadhaar/self/secure-qr")
    suspend fun selfSecureQr(@Body body: AadhaarSecureQrRequest): AadhaarSummaryDto

    @POST("aadhaar/self/offline-xml")
    suspend fun selfOfflineXml(@Body body: AadhaarOfflineXmlRequest): AadhaarSummaryDto

    @POST("aadhaar/self/online/otp")
    suspend fun selfOtp(@Body body: AadhaarOtpRequest): AadhaarOtpIssuedDto

    @POST("aadhaar/self/online/verify")
    suspend fun selfVerifyOtp(@Body body: AadhaarOtpVerifyRequest): AadhaarSummaryDto

    @DELETE("aadhaar/self")
    suspend fun withdrawSelf(): AadhaarSummaryDto

    @GET("aadhaar/artisans/{id}")
    suspend fun readArtisan(@Path("id") id: String): AadhaarSummaryDto

    @POST("aadhaar/artisans/{id}/secure-qr")
    suspend fun artisanSecureQr(@Path("id") id: String, @Body body: AadhaarSecureQrRequest): AadhaarSummaryDto

    @POST("aadhaar/artisans/{id}/offline-xml")
    suspend fun artisanOfflineXml(@Path("id") id: String, @Body body: AadhaarOfflineXmlRequest): AadhaarSummaryDto

    @POST("aadhaar/artisans/{id}/online/otp")
    suspend fun artisanOtp(@Path("id") id: String, @Body body: AadhaarOtpRequest): AadhaarOtpIssuedDto

    @POST("aadhaar/artisans/{id}/online/verify")
    suspend fun artisanVerifyOtp(@Path("id") id: String, @Body body: AadhaarOtpVerifyRequest): AadhaarSummaryDto

    @DELETE("aadhaar/artisans/{id}")
    suspend fun withdrawArtisan(@Path("id") id: String): AadhaarSummaryDto

    @POST("aadhaar/users/{id}/decision")
    suspend fun decideUser(@Path("id") id: String, @Body body: AadhaarDecisionRequest): AadhaarSummaryDto

    @POST("aadhaar/artisans/{id}/decision")
    suspend fun decideArtisan(@Path("id") id: String, @Body body: AadhaarDecisionRequest): AadhaarSummaryDto
}

sealed interface AadhaarSubject {
    data object Self : AadhaarSubject
    data class Artisan(val id: String) : AadhaarSubject
}

class AadhaarClient(private val api: AadhaarApi) {
    suspend fun read(subject: AadhaarSubject): AadhaarSummaryDto = when (subject) {
        AadhaarSubject.Self -> api.readSelf()
        is AadhaarSubject.Artisan -> api.readArtisan(subject.id)
    }

    suspend fun secureQr(subject: AadhaarSubject, qrText: String): AadhaarSummaryDto {
        val body = AadhaarSecureQrRequest(consent = true, qrText = qrText.filter { it.isDigit() })
        return when (subject) {
            AadhaarSubject.Self -> api.selfSecureQr(body)
            is AadhaarSubject.Artisan -> api.artisanSecureQr(subject.id, body)
        }
    }

    suspend fun offlineXml(
        subject: AadhaarSubject,
        zip: ByteArray,
        shareCode: String,
        mobile: String?,
        email: String?,
    ): AadhaarSummaryDto {
        val body = AadhaarOfflineXmlRequest(
            consent = true,
            zipBase64 = java.util.Base64.getEncoder().encodeToString(zip),
            shareCode = shareCode.trim(),
            mobile = mobile?.trim()?.ifBlank { null },
            email = email?.trim()?.ifBlank { null },
        )
        return when (subject) {
            AadhaarSubject.Self -> api.selfOfflineXml(body)
            is AadhaarSubject.Artisan -> api.artisanOfflineXml(subject.id, body)
        }
    }

    suspend fun requestOtp(subject: AadhaarSubject, uid: String): AadhaarOtpIssuedDto {
        val body = AadhaarOtpRequest(consent = true, uid = uid.filter { it.isDigit() })
        return when (subject) {
            AadhaarSubject.Self -> api.selfOtp(body)
            is AadhaarSubject.Artisan -> api.artisanOtp(subject.id, body)
        }
    }

    suspend fun verifyOtp(
        subject: AadhaarSubject,
        uid: String,
        otp: String,
        issued: AadhaarOtpIssuedDto,
    ): AadhaarSummaryDto {
        val body = AadhaarOtpVerifyRequest(
            consent = true,
            uid = uid.filter { it.isDigit() },
            otp = otp.filter { it.isDigit() },
            txn = issued.txn,
            token = issued.token,
        )
        return when (subject) {
            AadhaarSubject.Self -> api.selfVerifyOtp(body)
            is AadhaarSubject.Artisan -> api.artisanVerifyOtp(subject.id, body)
        }
    }

    suspend fun withdraw(subject: AadhaarSubject): AadhaarSummaryDto = when (subject) {
        AadhaarSubject.Self -> api.withdrawSelf()
        is AadhaarSubject.Artisan -> api.withdrawArtisan(subject.id)
    }

    suspend fun decideUser(userId: String, accept: Boolean): AadhaarSummaryDto =
        api.decideUser(userId, AadhaarDecisionRequest(accept))

    suspend fun decideArtisan(artisanId: String, accept: Boolean): AadhaarSummaryDto =
        api.decideArtisan(artisanId, AadhaarDecisionRequest(accept))

    companion object {
        fun create(context: Context): AadhaarClient =
            AadhaarClient(ApiClient.retrofit(TokenStore(context.applicationContext)).create(AadhaarApi::class.java))
    }
}

fun looksLikeSecureQr(text: String): Boolean {
    val digits = text.filterNot { it.isWhitespace() }
    return digits.length > 200 && digits.all { it.isDigit() }
}
