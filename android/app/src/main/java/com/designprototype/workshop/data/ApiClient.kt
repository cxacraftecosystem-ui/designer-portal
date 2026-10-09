package com.designprototype.workshop.data

import com.designprototype.workshop.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
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
    // Gateway/proxy timeouts that mean "the origin was too slow", not "the request was bad". CloudFront
    // returns 504 when the EC2 origin doesn't respond within its origin-response timeout, and 502/503
    // under transient origin trouble — all worth a quick retry rather than surfacing as a hard failure.
    private val RETRIABLE_GATEWAY_CODES = setOf(502, 503, 504)
    private const val MAX_GATEWAY_ATTEMPTS = 4

    /**
     * Only requests that are safe to repeat are auto-retried, so a 504 (where the origin may or may not
     * have already processed the call) can never create a duplicate record. GETs are always safe; among
     * POSTs only the side-effect-free upload-setup calls qualify — presigning a URL or starting/aborting
     * a multipart upload can be re-issued harmlessly. Record-creating calls (complete, create*, update*)
     * are deliberately excluded; their resilience comes from the save/back-guard flow instead.
     */
    private fun isSafelyRetriable(method: String, path: String): Boolean {
        if (method.equals("GET", ignoreCase = true)) return true
        if (method.equals("POST", ignoreCase = true)) {
            return path.endsWith("/media/presign") ||
                path.endsWith("/media/multipart/create") ||
                path.endsWith("/media/multipart/presign-parts") ||
                path.endsWith("/media/multipart/abort")
        }
        return false
    }

    /** Linear-ish backoff with a hard cap, so a struggling origin gets breathing room without long stalls. */
    private fun backoffMillis(attempt: Int): Long = minOf(4_000L, 600L * attempt)

    /**
     * The writes that set or mint a credential: changing one's own password, redeeming a link, and
     * issuing one. None is ever repeated by the loop in [httpClient] — [isSafelyRetriable] excludes
     * every write — and none may be repeated by OkHttp underneath it either; see [sentOnce].
     *
     * The link's withdrawal (`…/password-links/{id}/revoke`) is not one of them: it ends a credential
     * rather than making one, and sending it twice withdraws the same link twice.
     */
    private fun isCredentialWrite(method: String, path: String): Boolean =
        method.equals("POST", ignoreCase = true) && (
            path.endsWith("/auth/change-password") ||
                path.endsWith("/auth/set-password") ||
                path.endsWith("/auth/password-links")
            )

    /**
     * This request with its body marked ONE-SHOT, OkHttp's own word for "never transmit this twice".
     *
     * ── WHY: OKHTTP RESENDS A POST BY ITSELF ─────────────────────────────────────────────────────────
     *
     * `retryOnConnectionFailure(true)` lets OkHttp transparently send a request again after a
     * connection fails mid-exchange (when another route remains, which a CDN's several addresses
     * provide), and after a 408 answer, POST or not. For a credential write the first copy may already
     * have landed: the password changed, every older session retired, the link spent. The copy then
     * goes out with a token or a link the first one killed, and its refusal is what the person reads —
     * "This session is no longer valid" after a change that worked, "already used" after a redemption
     * that worked.
     *
     * A one-shot body is never resent after a failure that may have reached the server, and is never
     * carried into a redirect, a 408 or a 503 follow-up. A request that never left the phone (no route,
     * no connection) is still retried, which is harmless. Nothing else changes: the bytes are the same
     * bytes, read once.
     */
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

    /** Is this a request to the API itself: its scheme, host and port, and a path under the base? */
    private fun HttpUrl.isUnder(base: HttpUrl): Boolean =
        scheme == base.scheme && host == base.host && port == base.port &&
            encodedPath.startsWith(base.encodedPath)

    /**
     * Puts the session on every request, and tells the session root what the answers say about it.
     *
     * HEADERS ONLY, BOTH WAYS. The body belongs to the caller, and reading it here would consume it.
     * Each caller still gets its own 401 and triages it as it always has — every queue keeps its work
     * on one — so the two signals below change nothing a caller sees. They only make sure the screen
     * learns what the server has said about the SESSION, from whichever request heard it first.
     *
     * [apiBase] is where the API lives. Only an answer from there may end the session: a 401 from any
     * other host would be about that host's credentials, not this one's.
     */
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
            // THE PASSWORD GATE IS NOTICED HERE, ONCE, FOR EVERY CALLER. This tells the session root
            // that the account owes a new password, so the gate reaches the screen instead of a run of
            // unexplained failures.
            if (!token.isNullOrBlank() && isPasswordChangeRequired(response.code, gate)) {
                PasswordChangeSignal.raise()
            }
            // AND SO IS A SESSION THAT HAS ENDED — since 2026-10-09 most often because the password
            // was changed somewhere else, which retires every session opened with the old one. The
            // store is read AGAIN, after the answer: a token replaced while this request was in
            // flight (the change-password answer adopting a fresh one, a sign-out) means the 401 is
            // about a session this handset has already left. See [isSessionEnded].
            if (apiBase != null && request.url.isUnder(apiBase) &&
                isSessionEnded(response.code, gate, token, tokenStore.getToken())
            ) {
                SessionEndedSignal.raise()
            }
            response
        }

    fun create(tokenStore: TokenStore): WorkshopRepositoryApi =
        retrofit(tokenStore).create(WorkshopRepositoryApi::class.java)

    /**
     * The decoder Retrofit is built with — hoisted out of [retrofit] so the ONE caller that decodes
     * a response Retrofit deliberately did not decode can use the identical settings.
     *
     * That caller is `WorkshopRepository.readManifest`, which reads the streamed download manifest a
     * line at a time (see `data/ManifestStream.kt` for the OutOfMemoryError that made it necessary).
     * It MUST share these four flags rather than construct its own `Json`: `ignoreUnknownKeys` is
     * what stops a manifest entry gaining a field on the server from turning every installed
     * handset's download into 20,000 unreadable lines, and a second `Json` built beside this one is
     * how that guarantee gets lost silently the next time somebody adds a flag here and not there.
     */
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // The API serializes Prisma Decimal columns (measurements, costs) as JSON *strings*
        // (e.g. "12.5"). isLenient lets numeric DTO fields decode from quoted values, and
        // coerceInputValues falls back to defaults if a non-null field arrives null — together
        // these stop a single measured record from failing an entire list deserialization.
        isLenient = true
        coerceInputValues = true
    }

    /**
     * The configured Retrofit — the gateway retry, the auth header, the timeouts and the lenient JSON
     * above — so a feature can declare its OWN typed service without standing up a second HTTP stack
     * beside this one. A second stack is not a style question here: it would silently opt that
     * feature out of the 504 retry that exists because CloudFront times out this origin, out of the
     * Decimal-as-string leniency that keeps one measured record from failing a whole list, and out of
     * the two session signals in [sessionInterceptor] that put the gate or the sign-in card on screen.
     */
    fun retrofit(tokenStore: TokenStore): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.DEFAULT_API_BASE_URL)
            .client(httpClient(tokenStore))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    /**
     * The OkHttp stack under [retrofit]: the gateway retry, the credential writes sent once, the
     * session and its two signals, the timeouts.
     *
     * Internal, and separate from [retrofit], so a JVM test can run the app's OWN interceptors over a
     * transport that answers from memory: `httpClient(store).newBuilder().addInterceptor(…)` puts the
     * canned answer after the last of them, where the socket would be, and nothing is copied.
     */
    internal fun httpClient(tokenStore: TokenStore): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            // Mobile data is slower and drops connections more than Wi-Fi, so allow generous timeouts
            // and let OkHttp retry a connection that fails mid-handshake (e.g. a NAT64 path settling).
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Outermost interceptor: transparently retry safe requests on gateway timeouts (HTTP 504 and
            // friends) and on transport errors, with backoff. This re-runs the whole chain each attempt
            // (so the auth header below is freshly applied), turning a flaky/overloaded origin into a
            // brief delay instead of an "Upload failed" the user sees. Unsafe requests pass through once.
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
                        if (retriable && response.code in RETRIABLE_GATEWAY_CODES && attempt < maxAttempts) {
                            response.close()
                            runCatching { Thread.sleep(backoffMillis(attempt)) }
                            continue
                        }
                        return@addInterceptor response
                    } catch (e: IOException) {
                        lastError = e
                        if (!retriable || attempt >= maxAttempts) throw e
                        runCatching { Thread.sleep(backoffMillis(attempt)) }
                    }
                }
                throw lastError ?: IOException("Request failed after $maxAttempts attempts")
            }
            // A CREDENTIAL WRITE IS SENT ONCE — by OkHttp as well as by the loop above. An
            // application interceptor, so OkHttp's own retry layer, which runs after every one of
            // these, sees the one-shot body. See [sentOnce].
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (isCredentialWrite(request.method, request.url.encodedPath)) request.sentOnce() else request
                )
            }
            .addInterceptor(sessionInterceptor(tokenStore, BuildConfig.DEFAULT_API_BASE_URL.toHttpUrlOrNull()))
            .addInterceptor(logging)
            .build()
    }
}
