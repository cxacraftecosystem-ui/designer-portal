package com.designprototype.workshop.ui

import android.content.Context
import com.designprototype.workshop.BuildConfig
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.apiErrorMessage
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

const val STT_KEY_NO_KEY = "NO_KEY"
const val STT_KEY_UNTESTED = "UNTESTED"
const val STT_KEY_FAILING = "FAILING"
const val STT_KEY_PASSING = "PASSING"

@Serializable
data class SttProviderDto(
    val id: String,
    val name: String = "",
    val keyName: String = "",
    val keyLabel: String = "",
    val configured: Boolean = false,
    val keyState: String = STT_KEY_UNTESTED,
    val rankable: Boolean = false,
    val frozenReason: String? = null,
    val testedAt: String? = null,
    val testError: String? = null
)

@Serializable
data class SttProviderOrderDto(
    val providers: List<SttProviderDto> = emptyList(),
    val effectiveChain: List<String> = emptyList(),
    val verifiedChain: List<String> = emptyList(),
    val normalised: Boolean = false,
    val normalisedNote: String? = null
)

@Serializable
data class SttProviderOrderBody(val order: List<String>)

interface SttProviderApi {
    @GET("settings/transcription-providers")
    suspend fun providerOrder(): SttProviderOrderDto

    @PUT("settings/transcription-providers")
    suspend fun setProviderOrder(@Body body: SttProviderOrderBody): SttProviderOrderDto

    @POST("settings/transcription-providers/{provider}/test")
    suspend fun testProvider(@Path("provider") provider: String): SttProviderOrderDto
}

object SttProviderClient {
    @Volatile
    private var cached: SttProviderApi? = null

    fun get(context: Context): SttProviderApi =
        cached ?: synchronized(this) { cached ?: build(context.applicationContext).also { cached = it } }

    private fun build(context: Context): SttProviderApi {
        val tokenStore = TokenStore(context)
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val retriable = request.method.equals("GET", ignoreCase = true)
                var attempt = 0
                var lastError: IOException? = null
                while (attempt < if (retriable) 3 else 1) {
                    attempt++
                    try {
                        val response = chain.proceed(request)
                        if (retriable && response.code in GATEWAY_CODES && attempt < 3) {
                            response.close()
                            runCatching { Thread.sleep(600L * attempt) }
                            continue
                        }
                        return@addInterceptor response
                    } catch (e: IOException) {
                        lastError = e
                        if (!retriable || attempt >= 3) throw e
                        runCatching { Thread.sleep(600L * attempt) }
                    }
                }
                throw lastError ?: IOException("Request failed after $attempt attempts")
            }
            .addInterceptor { chain ->
                val token = tokenStore.getToken()
                val request = if (token.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                }
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.DEFAULT_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SttProviderApi::class.java)
    }

    private val GATEWAY_CODES = setOf(502, 503, 504)
}

val STT_PROVIDER_BLURBS: Map<String, String> = mapOf(
    "elevenlabs" to "Best for Indian languages and accents. Slower, most expensive.",
    "deepgram" to "Fastest and cheapest. Weaker with background noise.",
    "whisper" to "Reliable fallback; no speaker labels."
)

val BUILT_IN_STT_PROVIDERS: List<SttProviderDto> = listOf(
    SttProviderDto(id = "elevenlabs", name = "ElevenLabs", keyLabel = "ElevenLabs Scribe", keyName = "ELEVENLABS_API_KEY"),
    SttProviderDto(id = "deepgram", name = "Deepgram", keyLabel = "Deepgram", keyName = "DEEPGRAM_API_KEY"),
    SttProviderDto(id = "whisper", name = "Whisper (OpenAI)", keyLabel = "OpenAI", keyName = "OPENAI_API_KEY"),
    SttProviderDto(id = "muse", name = "Meta Muse", keyLabel = "Meta Muse", keyName = "META_MODEL_API_KEY")
)

fun sttOrderViolations(list: List<SttProviderDto>): Int {
    var frozenSoFar = 0
    var total = 0
    for (provider in list) {
        if (provider.rankable) total += frozenSoFar else frozenSoFar += 1
    }
    return total
}

fun sttReorder(list: List<SttProviderDto>, from: Int, to: Int): List<SttProviderDto> {
    val next = list.toMutableList()
    next.add(to, next.removeAt(from))
    return next
}

fun sttMovePermitted(list: List<SttProviderDto>, from: Int, to: Int): Boolean {
    if (to < 0 || to >= list.size || from == to) return false
    return sttOrderViolations(sttReorder(list, from, to)) <= sttOrderViolations(list)
}

fun sameSttOrder(a: List<SttProviderDto>, b: List<SttProviderDto>): Boolean =
    a.size == b.size && a.indices.all { a[it].id == b[it].id }

data class SttTrouble(
    val headline: String,
    val advice: String,
    val technical: String,
    val retryable: Boolean
)

fun describeSttTrouble(error: Throwable, action: String): SttTrouble {
    val status = (error as? HttpException)?.code() ?: 0
    val sentence = error.apiErrorMessage("").takeIf { it.isNotBlank() }
    val technical = buildString {
        append("HTTP ")
        append(if (status == 0) "—" else status.toString())
        append(" from /settings/transcription-providers")
        if (sentence != null) append(" — “$sentence”")
    }

    return when {
        status == 404 -> SttTrouble(
            headline = "Provider order unavailable right now.",
            advice = "Nothing is lost. Try again later.",
            technical = technical,
            retryable = true
        )
        status == 403 -> SttTrouble(
            headline = "You can't view or change this ranking.",
            advice = "Needs the Admin role. Ask a master admin.",
            technical = technical,
            retryable = false
        )
        status == 401 -> SttTrouble(
            headline = "Your session has ended.",
            advice = "Sign in again; the ranking is unchanged.",
            technical = technical,
            retryable = false
        )
        status >= 500 -> SttTrouble(
            headline = "Something went wrong.",
            advice = "Wait a minute and tap Try again. Tell your admin if it persists.",
            technical = technical,
            retryable = true
        )
        status == 0 -> SttTrouble(
            headline = "Could not connect.",
            advice = "Check your connection and tap Try again.",
            technical = technical,
            retryable = true
        )
        else -> SttTrouble(
            headline = "The provider order could not be ${if (action == "load") "loaded" else if (action == "save") "saved" else "tested"}.",
            advice = sentence ?: "Tap Try again; tell an admin if it persists.",
            technical = technical,
            retryable = true
        )
    }
}

fun sttTestedAgo(iso: String?, now: Instant = Instant.now()): String {
    if (iso.isNullOrBlank()) return ""
    val then = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: return ""
    val seconds = maxOf(0L, (now.toEpochMilli() - then.toEpochMilli()) / 1000L)
    if (seconds < 60) return "just now"
    val minutes = (seconds / 60.0).roundToLong()
    if (minutes < 60) return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
    val hours = (minutes / 60.0).roundToLong()
    if (hours < 48) return if (hours == 1L) "1 hour ago" else "$hours hours ago"
    return "${(hours / 24.0).roundToLong()} days ago"
}

fun joinSttNames(names: List<String>): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
}
