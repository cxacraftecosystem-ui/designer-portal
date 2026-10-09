package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import java.io.IOException

/**
 * A SET-PASSWORD LINK IS CHECKED WITH ITS TOKEN IN A BODY, AND IN AN ADDRESS ONLY WHEN THE SERVER
 * CANNOT HEAR A BODY.
 *
 * The token is the link's whole authority until it is used or expires, and a URL is what proxies and
 * access logs write down. So `WorkshopRepository.checkPasswordLink` asks
 * `POST /api/auth/set-password/check` with `{"token": …}` first, and falls back to the old
 * `GET /api/auth/set-password?token=…` ONLY when that POST is answered 404 or 405 — the answer of a
 * server from before the route existed. Pinned here, both halves:
 *
 *  1. Against a server that has the route, the token never appears in a URL, and the answer is read
 *     into the same `{valid, reason, purpose}` the GET answers.
 *  2. Against an older server, a 404 or a 405 brings the GET, and the screen gets the same verdict.
 *  3. NOTHING ELSE brings the GET. A 5xx, a 422, a 429, a 401 or a dropped connection says nothing
 *     about whether the route exists, and re-sending the token in an address on those would put it
 *     back in the logs exactly when the server is struggling.
 *  4. A gateway error on the POST is retried, as it was on the GET it replaces — the check reads and
 *     writes nothing.
 *  5. The debug request log prints the fallback GET's `token=` and a bearer header as `██`.
 *
 * Driven through the app's OWN stack — `ApiClient.httpClient` and `ApiClient.retrofit`, every
 * interceptor in order — with the socket replaced by an answer from memory, the shape
 * `SessionEndedSignalTest` uses, so the method, the address and the body are read exactly as they
 * would leave the phone.
 */
class PasswordLinkCheckTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A token in the shape the server issues: base64url payload, a dot, base64url signature. */
    private val token = "eyJ1IjoidXNlci0xIiwicCI6IlJFU0VUIn0.c2lnbmF0dXJlLWJ5dGVz_-"

    /** What both routes answer for an expired reset link. */
    private val expired = """{"valid":false,"reason":"expired","purpose":"RESET"}"""

    /** One canned answer, or a dropped connection when [code] is null. */
    private class Answer(val code: Int?, val body: String = "{}")

    /** Every request that reached the transport, and its body as the transport received it. */
    private val sent = mutableListOf<Request>()
    private val bodies = mutableListOf<String?>()

    private fun stack(store: TokenStore, answer: (Request) -> Answer): OkHttpClient =
        ApiClient.httpClient(store).newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                sent += request
                bodies += request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() }
                val canned = answer(request)
                val code = canned.code ?: throw IOException("the connection dropped")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Canned")
                    .body(canned.body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun repository(answer: (Request) -> Answer): WorkshopRepository {
        // Signed out, as everybody holding one of these links is.
        val store = TokenStore(InMemoryContext(folder.root))
        val service = ApiClient.retrofit(store).newBuilder()
            .client(stack(store, answer))
            .build()
            .create(WorkshopRepositoryApi::class.java)
        return WorkshopRepository(service, store)
    }

    private fun Request.isTheCheck(): Boolean =
        method == "POST" && url.encodedPath.endsWith("/auth/set-password/check")

    private fun Request.isTheOldGet(): Boolean =
        method == "GET" && url.encodedPath.endsWith("/auth/set-password")

    // ── 1. The route that keeps the token out of the address ────────────────────────────────────

    @Test
    fun `a server with the route is asked with the token in a POST body and never in the address`() {
        val repository = repository { request ->
            if (request.isTheCheck()) Answer(200, expired) else Answer(500, """{"detail":"unexpected"}""")
        }

        val verdict = runBlocking { repository.checkPasswordLink(token) }

        assertEquals(false, verdict.valid)
        assertEquals("expired", verdict.reason)
        assertEquals("RESET", verdict.purpose)
        assertEquals("exactly one request, the POST", 1, sent.size)
        assertTrue(sent.single().isTheCheck())
        assertNull("no query string at all", sent.single().url.query)
        assertFalse("the token is nowhere in the address", sent.single().url.toString().contains(token))
        assertEquals("""{"token":"$token"}""", bodies.single())
        assertEquals("json", sent.single().body?.contentType()?.subtype)
    }

    @Test
    fun `a valid link reads as valid, with its purpose`() {
        val repository = repository { Answer(200, """{"valid":true,"reason":null,"purpose":"INVITE"}""") }

        val verdict = runBlocking { repository.checkPasswordLink(token) }

        assertEquals(true, verdict.valid)
        assertNull(verdict.reason)
        assertEquals("INVITE", verdict.purpose)
    }

    // ── 2. An older server, which has only the GET ──────────────────────────────────────────────

    @Test
    fun `an older server's 404 or 405 brings the GET, and the same verdict`() {
        listOf(404, 405).forEach { status ->
            sent.clear()
            bodies.clear()
            val repository = repository { request ->
                when {
                    request.isTheCheck() -> Answer(status, """{"detail":"Not Found"}""")
                    request.isTheOldGet() -> Answer(200, expired)
                    else -> Answer(500)
                }
            }

            val verdict = runBlocking { repository.checkPasswordLink(token) }

            assertEquals("HTTP $status", "expired", verdict.reason)
            assertEquals("HTTP $status", "RESET", verdict.purpose)
            assertEquals("HTTP $status: the POST, then the GET", listOf("POST", "GET"), sent.map { it.method })
            assertTrue("HTTP $status", sent[1].isTheOldGet())
            assertEquals("HTTP $status: the GET carries the token as before", token, sent[1].url.queryParameter("token"))
        }
    }

    // ── 3. Nothing else re-sends the token in an address ────────────────────────────────────────

    @Test
    fun `no other refusal or failure brings the GET`() {
        listOf(
            Answer(500, """{"detail":"Internal Server Error"}"""),
            Answer(422, """{"detail":"Field required"}"""),
            Answer(429, """{"detail":"Too many attempts."}"""),
            Answer(401, """{"detail":"Not authenticated"}"""),
            Answer(400, """{"detail":"Bad request"}"""),
        ).forEach { answer ->
            sent.clear()
            val repository = repository { answer }

            val thrown = runCatching { runBlocking { repository.checkPasswordLink(token) } }.exceptionOrNull()

            assertTrue("HTTP ${answer.code} is thrown for the screen to read as unknown", thrown is HttpException)
            assertEquals(answer.code, (thrown as HttpException).code())
            assertTrue("HTTP ${answer.code}: no GET", sent.none { it.method == "GET" })
            assertTrue("HTTP ${answer.code}: no address carried the token", sent.none { it.url.toString().contains(token) })
        }
    }

    @Test
    fun `a dropped connection is thrown, not answered with the GET`() {
        val repository = repository { Answer(code = null) }

        val thrown = runCatching { runBlocking { repository.checkPasswordLink(token) } }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue("the transport's failure reaches the screen", thrown is IOException)
        assertTrue("every attempt was the POST", sent.isNotEmpty() && sent.all { it.isTheCheck() })
    }

    // ── 4. Retried like the GET it replaces ─────────────────────────────────────────────────────

    @Test
    fun `a gateway error on the check is retried, as the GET was`() {
        var attempts = 0
        val repository = repository {
            attempts += 1
            if (attempts == 1) Answer(503, "<html>503</html>") else Answer(200, expired)
        }

        val verdict = runBlocking { repository.checkPasswordLink(token) }

        assertEquals("expired", verdict.reason)
        assertEquals("the 503, then the answer", 2, sent.size)
        assertTrue("both were the POST", sent.all { it.isTheCheck() })
    }

    // ── 5. The request log ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the request log prints neither the fallback's token nor a bearer header`() {
        val lines = mutableListOf<String>()
        val logger = object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                lines += message
            }
        }
        val client = OkHttpClient.Builder()
            // HEADERS rather than the BASIC a debug build uses, so the header redaction is exercised
            // as well; the request line is printed, and redacted, at both.
            .addInterceptor(ApiClient.httpLogging(logger, HttpLoggingInterceptor.Level.HEADERS))
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(expired.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.example.org/api/auth/set-password?token=$token")
                .header("Authorization", "Bearer the-session-itself")
                .build()
        ).execute().close()

        val log = lines.joinToString("\n")
        assertTrue("the line is still there to read: $log", log.contains("/api/auth/set-password?token="))
        assertFalse("the token is not: $log", log.contains(token))
        assertFalse("nor is the session: $log", log.contains("the-session-itself"))
    }
}
