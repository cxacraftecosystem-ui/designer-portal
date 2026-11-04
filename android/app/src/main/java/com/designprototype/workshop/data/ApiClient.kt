package com.designprototype.workshop.data

import com.designprototype.workshop.BuildConfig
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.BufferedSink
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    private val RETRIABLE_GATEWAY_CODES = setOf(502, 503, 504)
    private const val MAX_GATEWAY_ATTEMPTS = 4

    private fun isSafelyRetriable(method: String, path: String): Boolean {
        if (method.equals("GET", ignoreCase = true)) return true
        if (method.equals("POST", ignoreCase = true)) {
            return path.endsWith("/media/presign") ||
                path.endsWith("/media/multipart/create") ||
                path.endsWith("/media/multipart/presign-parts") ||
                path.endsWith("/media/multipart/abort") ||
                path.endsWith("/auth/set-password/check")
        }
        return false
    }

    private fun backoffMillis(attempt: Int): Long = minOf(4_000L, 600L * attempt)

    private fun isCredentialWrite(method: String, path: String): Boolean =
        method.equals("POST", ignoreCase = true) && (
            path.endsWith("/auth/change-password") ||
                path.endsWith("/auth/set-password") ||
                path.endsWith("/auth/password-links")
            )

    private fun Request.sentOnce(): Request {
        val original = body ?: return this
        if (original.isOneShot()) return this
        return newBuilder().method(method, object : RequestBody() {
            override fun contentType(): MediaType? = original.contentType()
            override fun contentLength(): Long = original.contentLength()
            override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
            override fun isOneShot(): Boolean = true
        }).build()
    }

    private fun HttpUrl.isUnder(base: HttpUrl): Boolean =
        scheme == base.scheme && host == base.host && port == base.port &&
            encodedPath.startsWith(base.encodedPath)

    internal fun sessionInterceptor(tokenStore: TokenStore, apiBase: HttpUrl?): Interceptor =
        Interceptor { chain ->
            val token = tokenStore.getToken()
            val request = if (token.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
            val response = chain.proceed(request)
            val gate = response.header(PASSWORD_CHANGE_REQUIRED_HEADER)
            if (!token.isNullOrBlank() && isPasswordChangeRequired(response.code, gate)) {
                PasswordChangeSignal.raise()
            }
            if (apiBase != null && request.url.isUnder(apiBase) &&
                isSessionEnded(response.code, gate, token, tokenStore.getToken())
            ) {
                SessionEndedSignal.raise()
            }
            response
        }

    fun create(tokenStore: TokenStore): WorkshopRepositoryApi =
        retrofit(tokenStore).create(WorkshopRepositoryApi::class.java)

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    fun retrofit(tokenStore: TokenStore): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.DEFAULT_API_BASE_URL)
            .client(httpClient(tokenStore))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    internal fun httpLogging(
        logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
        level: HttpLoggingInterceptor.Level =
            if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE,
    ): HttpLoggingInterceptor = HttpLoggingInterceptor(logger).apply {
        this.level = level
        redactQueryParams("token")
        redactHeader("Authorization")
        redactHeader(SESSION_TOKEN_HEADER)
    }

    internal fun httpClient(tokenStore: TokenStore): OkHttpClient {
        val logging = httpLogging()

        return OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val retriable = isSafelyRetriable(original.method, original.url.encodedPath)
                val maxAttempts = if (retriable) MAX_GATEWAY_ATTEMPTS else 1
                var attempt = 0
                var lastError: IOException? = null
                while (attempt < maxAttempts) {
                    attempt++
                    try {
                        val response = chain.proceed(original)
                        ServerReachability.recordAnswer()
                        if (retriable && response.code in RETRIABLE_GATEWAY_CODES && attempt < maxAttempts) {
                            response.close()
                            runCatching { Thread.sleep(backoffMillis(attempt)) }
                            continue
                        }
                        return@addInterceptor response
                    } catch (e: IOException) {
                        lastError = e
                        ServerReachability.recordFailure(e)
                        if (!retriable || attempt >= maxAttempts) throw e
                        runCatching { Thread.sleep(backoffMillis(attempt)) }
                    }
                }
                throw lastError ?: IOException(CONNECTION_FAILED_SENTENCE)
            }
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (isCredentialWrite(request.method, request.url.encodedPath)) request.sentOnce() else request
                )
            }
            .addInterceptor(sessionInterceptor(tokenStore, BuildConfig.DEFAULT_API_BASE_URL.toHttpUrlOrNull()))
            .addInterceptor(com.designprototype.workshop.questionnaire.data.ClientClock.interceptor())
            .addInterceptor(logging)
            .build()
    }
}
