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

class PasswordLinkCheckTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val token = "eyJ1IjoidXNlci0xIiwicCI6IlJFU0VUIn0.c2lnbmF0dXJlLWJ5dGVz_-"

    private val expired = """{"valid":false,"reason":"expired","purpose":"RESET"}"""

    private class Answer(val code: Int?, val body: String = "{}")

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

    @Test
    fun `a 2xx body with no verdict in it is thrown, never read as a dead link`() {
        val repository = repository { Answer(200, """{"reason":null}""") }

        val thrown = runCatching { runBlocking { repository.checkPasswordLink(token) } }.exceptionOrNull()

        assertTrue("a body with no verdict fails to decode: $thrown", thrown is kotlinx.serialization.SerializationException)
        assertTrue("and it brings no GET", sent.none { it.method == "GET" })
    }

    @Test
    fun `the request log prints neither the fallback's token nor either session`() {
        val lines = mutableListOf<String>()
        val logger = object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                lines += message
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(ApiClient.httpLogging(logger, HttpLoggingInterceptor.Level.HEADERS))
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header(SESSION_TOKEN_HEADER, "the-fresh-session")
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
        assertTrue("the response header is named: $log", log.contains(SESSION_TOKEN_HEADER))
        assertFalse("but the fresh session is not: $log", log.contains("the-fresh-session"))
    }
}
