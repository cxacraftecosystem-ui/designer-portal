package com.designprototype.workshop.data

import com.designprototype.workshop.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A 401 WITHOUT THE GATE'S HEADER, TO A REQUEST SENT WITH THE TOKEN THIS HANDSET STILL HOLDS, TELLS
 * THE SESSION ROOT THE SESSION MAY HAVE ENDED — AND NO OTHER ANSWER DOES.
 *
 * ── WHAT WENT WRONG WITHOUT IT ───────────────────────────────────────────────────────────────────
 *
 * Since 2026-10-09 a session is bound to the password it was opened with, so a change on the website
 * retires this handset's token. The handset read a session verdict only from `GET /me` at launch:
 * every request after the change came back 401, every queue kept its work and retried with the dead
 * token, and nothing sent the person to sign in until the app was killed and reopened.
 *
 * ── WHAT IS PINNED ───────────────────────────────────────────────────────────────────────────────
 *
 * Driven through the app's OWN stack — `ApiClient.httpClient`, every interceptor in its order — with
 * the socket replaced by an answer from memory, so the token, the header and the store are read
 * exactly where the app reads them:
 *
 *  1. A tokened request answered by a plain 401 raises [SessionEndedSignal], and not
 *     [PasswordChangeSignal] — while the caller still gets its own 401 and still keeps its work.
 *  2. A gated 401 raises only [PasswordChangeSignal].
 *  3. An untokened 401 — a refused sign-in — raises nothing.
 *  4. A 401 for a token replaced before the answer arrived raises nothing.
 *  5. A 401 from anywhere but the API raises nothing, and no other status raises anything.
 *
 * And the credential writes, which OkHttp must never send twice by itself: they reach the wire with
 * one-shot bodies, and over a real loopback socket OkHttp is seen resending an ordinary POST after a
 * 408 and NOT resending a change of password.
 *
 * The two signals are process-wide counters, so every assertion is about the DIFFERENCE one request
 * made, read immediately around it.
 */
class SessionEndedSignalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val held = "session-this-handset-holds"

    /** Where the app sends every request: the base its Retrofit is built on. */
    private val api: HttpUrl = BuildConfig.DEFAULT_API_BASE_URL.toHttpUrl()

    private val profile =
        """{"id":"u-1","email":"meera@example.org","name":"Meera","role":"DESIGNER","mustChangePassword":false}"""

    /** One canned answer. [headers] are sent spelled exactly as given. */
    private class Answer(val code: Int, val body: String = "{}", val headers: Map<String, String> = emptyMap())

    /** What a retired session gets: a 401, no gate header, the server's own sentence. */
    private val ended = Answer(401, """{"detail":"This session is no longer valid. Sign in again."}""")

    /** What a live session that owes a new password gets. */
    private val gated = Answer(
        401,
        """{"detail":"Choose a new password to continue."}""",
        headers = mapOf(PASSWORD_CHANGE_REQUIRED_HEADER to "1"),
    )

    /** Every request that reached the transport, as the app's interceptors left it. */
    private val sent = mutableListOf<Request>()

    /**
     * The app's own HTTP stack with the socket replaced by [answer]. Added after the last of the app's
     * interceptors, so each request arrives exactly as they left it — token, body and all — and the
     * answer travels back up through every one of them.
     */
    private fun stack(store: TokenStore, answer: (Request) -> Answer): OkHttpClient =
        ApiClient.httpClient(store).newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                sent += request
                val canned = answer(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(canned.code)
                    .message("Canned")
                    .apply { canned.headers.forEach { (name, value) -> header(name, value) } }
                    .body(canned.body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    /** The repository the app builds — its Retrofit, decoder and base URL — over [stack]. */
    private fun repository(store: TokenStore, answer: (Request) -> Answer): WorkshopRepository {
        val service = ApiClient.retrofit(store).newBuilder()
            .client(stack(store, answer))
            .build()
            .create(WorkshopRepositoryApi::class.java)
        return WorkshopRepository(service, store)
    }

    private fun signedIn(): TokenStore = TokenStore(InMemoryContext(folder.root)).apply { setToken(held) }

    private fun signedOut(): TokenStore = TokenStore(InMemoryContext(folder.root))

    /** What [block] raised, as (ended, gated). */
    private fun raisedBy(block: () -> Unit): Pair<Long, Long> {
        val endedBefore = SessionEndedSignal.raises.value
        val gatedBefore = PasswordChangeSignal.raises.value
        block()
        return (SessionEndedSignal.raises.value - endedBefore) to (PasswordChangeSignal.raises.value - gatedBefore)
    }

    // ── 1–4. Which 401 ends the session ──────────────────────────────────────────────────────────

    @Test
    fun `a tokened request answered by a plain 401 raises SessionEndedSignal and not PasswordChangeSignal`() {
        val store = signedIn()
        val repository = repository(store) { ended }
        var failure: Throwable? = null

        val (endedRaises, gatedRaises) = raisedBy {
            failure = runCatching { runBlocking { repository.refreshUser() } }.exceptionOrNull()
        }

        assertEquals("the session root was told", 1L, endedRaises)
        assertEquals("the gate was not", 0L, gatedRaises)
        assertEquals("Bearer $held", sent.single().header("Authorization"))

        // THE CALLER IS NOT TOLD ANYTHING NEW, AND THE QUEUES KEEP THEIR WORK. It still holds its own
        // 401, with the server's sentence inside, and both triages still read it as "later".
        val refused = failure as HttpException
        assertEquals(401, refused.code())
        assertTrue("the record outbox keeps the entry", repository.isTransient(refused))
        assertTrue("the design-workshop pass stops without parking anything", repository.isConnectionFailure(refused))
        assertEquals("This session is no longer valid. Sign in again.", refused.apiErrorMessage("no sentence"))
        // Nor is anybody signed out here: that is the root's, once `GET /me` has agreed.
        assertEquals(held, store.getToken())
    }

    @Test
    fun `a gated 401 raises only PasswordChangeSignal`() {
        val store = signedIn()
        val repository = repository(store) { gated }

        val raised = raisedBy { runCatching { runBlocking { repository.refreshUser() } } }

        assertEquals("a live session owing a password is not an ended one", 0L to 1L, raised)
    }

    @Test
    fun `an untokened 401 raises nothing`() {
        // A sign-in the server refused: no session went with it, so there is none to end.
        val store = signedOut()
        val repository = repository(store) { ended }

        val raised = raisedBy { runCatching { runBlocking { repository.login("meera@example.org", "a-wrong-guess") } } }

        assertEquals(0L to 0L, raised)
        assertNull("no token was sent", sent.single().header("Authorization"))
    }

    @Test
    fun `a 401 for a token replaced before the answer arrived raises nothing`() {
        // `changeOwnPassword` adopting the token its answer brought while a request sent with the old
        // one is still in flight — or a sign-out and somebody else's sign-in. That 401 is about a
        // session this handset has already left, and must not end the one it holds now.
        val store = signedIn()
        val repository = repository(store) {
            store.setToken("session-minted-after-a-change")
            ended
        }

        val raised = raisedBy { runCatching { runBlocking { repository.refreshUser() } } }

        assertEquals(0L to 0L, raised)
        assertEquals("the request did carry the old token", "Bearer $held", sent.single().header("Authorization"))
    }

    // ── 5. Only the API's 401, and only a 401 ────────────────────────────────────────────────────

    @Test
    fun `a 401 from anywhere but the API raises nothing`() {
        val store = signedIn()
        val client = stack(store) { ended }

        val elsewhere = raisedBy {
            client.newCall(Request.Builder().url("https://elsewhere.example.org/api/me").build()).execute().close()
        }
        // The control: the same answer to the API's own address does, so the address is the only
        // difference between the two.
        val fromTheApi = raisedBy {
            client.newCall(Request.Builder().url(api.resolve("me")!!).build()).execute().close()
        }

        assertEquals(0L to 0L, elsewhere)
        assertEquals(1L to 0L, fromTheApi)
    }

    @Test
    fun `no other status raises anything`() {
        // 500 rather than a gateway code, because `ApiClient` retries a GET on 502–504 with backoff.
        listOf(
            Answer(200, profile),
            Answer(403, """{"detail":"Your access has been suspended."}"""),
            Answer(500, """{"detail":"Internal Server Error"}"""),
        ).forEach { answer ->
            val store = signedIn()
            val repository = repository(store) { answer }

            val raised = raisedBy { runCatching { runBlocking { repository.refreshUser() } } }

            assertEquals("HTTP ${answer.code}", 0L to 0L, raised)
        }
    }

    // ── The credential writes, which OkHttp must never send twice by itself ──────────────────────

    @Test
    fun `the credential writes reach the wire one-shot, and nothing else does`() {
        val store = signedIn()
        val service = ApiClient.retrofit(store).newBuilder()
            .client(stack(store) { Answer(200, """{"ok":true}""") })
            .build()
            .create(WorkshopRepositoryApi::class.java)

        runBlocking {
            runCatching { service.changeOwnPassword(ChangePasswordRequest(currentPassword = "a", newPassword = "b")) }
            runCatching { service.setPasswordWithLink(SetPasswordRequest(token = "a-link", password = "chosen-by-meera")) }
            runCatching { service.issuePasswordLink(IssuePasswordLinkRequest(userId = "u-2")) }
            // Not credential writes: withdrawing a link ends one, signing in mints only a session, and
            // checking a link reads it — a POST only so that its token rides in a body, not a URL.
            runCatching { service.revokePasswordLink("link-1") }
            runCatching { service.checkPasswordLinkInBody(PasswordLinkCheckRequest(token = "a-link")) }
            runCatching { service.login(LoginRequest(email = "meera@example.org", password = "chosen-by-meera")) }
        }

        val oneShot = sent.associate { it.url.encodedPath.removePrefix(api.encodedPath) to it.body?.isOneShot() }
        assertEquals(
            mapOf(
                "auth/change-password" to true,
                "auth/set-password" to true,
                "auth/password-links" to true,
                "auth/password-links/link-1/revoke" to false,
                "auth/set-password/check" to false,
                "auth/login" to false,
            ),
            oneShot,
        )
    }

    @Test
    fun `OkHttp itself resends an ordinary POST after a 408, and never a change of password`() {
        // REAL SOCKETS, because the resend happens inside OkHttp's own retry layer, below anything an
        // interceptor can stand in for. The 408 is the deterministic door into it: OkHttp repeats the
        // request "even non-idempotent ones", in its own words, unless the body is one-shot.
        val client = ApiClient.httpClient(signedIn()).newBuilder()
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val json = "application/json".toMediaType()

        TimesOutOnce().use { server ->
            val ordinary = Request.Builder().url(server.url("/api/artisans")).post("{}".toRequestBody(json)).build()
            client.newCall(ordinary).execute().use { answer ->
                assertEquals("OkHttp sent it again and handed back the second answer", 200, answer.code)
            }
            assertEquals("the control: OkHttp does resend a POST", 2, server.requests)
        }

        TimesOutOnce().use { server ->
            val change = Request.Builder()
                .url(server.url("/api/auth/change-password"))
                .post("""{"currentPassword":"a","newPassword":"b"}""".toRequestBody(json))
                .build()
            client.newCall(change).execute().use { answer ->
                assertEquals("the caller gets the first answer, not a second copy's", 408, answer.code)
            }
            assertEquals("a change of password reached the server once", 1, server.requests)
        }
    }

    /**
     * A one-thread HTTP/1.1 server on the loopback interface. It answers its FIRST request 408 and
     * every later one 200, one request per connection, and counts what it was sent.
     */
    private class TimesOutOnce : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val served = AtomicInteger(0)

        val requests: Int get() = served.get()

        init {
            thread(isDaemon = true, name = "times-out-once") {
                while (!listener.isClosed) {
                    val connection = try {
                        listener.accept()
                    } catch (closed: IOException) {
                        return@thread
                    }
                    connection.use { socket ->
                        val input = socket.getInputStream().buffered()
                        if (!readRequest(input)) return@use
                        val status = if (served.getAndIncrement() == 0) "408 Request Timeout" else "200 OK"
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: 2\r\n" +
                                    "Connection: close\r\n\r\n{}").toByteArray()
                            )
                            flush()
                        }
                    }
                }
            }
        }

        fun url(path: String): HttpUrl = "http://127.0.0.1:${listener.localPort}$path".toHttpUrl()

        /** Reads one request — line, headers and a `Content-Length` body. False at end of stream. */
        private fun readRequest(input: InputStream): Boolean {
            var length = 0
            var first = true
            while (true) {
                val line = readLine(input) ?: return false
                if (line.isEmpty()) {
                    if (first) continue
                    break
                }
                first = false
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toInt()
                }
            }
            repeat(length) { if (input.read() < 0) return false }
            return true
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (line.isEmpty()) null else line.toString()
                if (byte == '\n'.code) return line.toString().trimEnd('\r')
                line.append(byte.toChar())
            }
        }

        override fun close() = listener.close()
    }
}
