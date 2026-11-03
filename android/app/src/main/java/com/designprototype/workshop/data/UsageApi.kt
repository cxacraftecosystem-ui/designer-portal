package com.designprototype.workshop.data

import android.content.Context
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface UsageApi {

    @GET("usage/consent/notice")
    suspend fun consentNotice(): UsageNoticeDto

    @GET("usage/consent")
    suspend fun myConsent(): UsageConsentStateDto

    @POST("usage/consent")
    suspend fun recordConsent(@Body body: UsageConsentBody): UsageConsentStateDto

    @POST("usage/consent/withdraw")
    suspend fun withdrawConsent(@Body body: UsageWithdrawBody): UsageConsentStateDto

    @GET("usage/me")
    suspend fun myUsage(
        @Query("from") from: String,
        @Query("to") to: String,
    ): UsageMineDto

    @GET("usage/me/trail")
    suspend fun myTrail(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): UsageTrailDto

    @GET("usage/routes")
    suspend fun usageRoutes(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): UsageRoutesPageDto

    @GET("usage/collection")
    suspend fun usageCollection(): UsageCollectionDto
}

object UsageClient {
    @Volatile
    private var service: UsageApi? = null

    fun of(context: Context): UsageApi {
        service?.let { return it }
        return synchronized(this) {
            service ?: ApiClient.retrofit(TokenStore(context.applicationContext))
                .create(UsageApi::class.java)
                .also { service = it }
        }
    }
}
