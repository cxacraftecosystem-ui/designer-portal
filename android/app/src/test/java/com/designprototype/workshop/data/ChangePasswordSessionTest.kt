package com.designprototype.workshop.data

import com.designprototype.workshop.ui.PASSWORD_CHANGE_UNCONFIRMED
import com.designprototype.workshop.ui.PASSWORD_MAY_ALREADY_BE_IN_EFFECT
import com.designprototype.workshop.ui.PasswordGateAfterFailure
import com.designprototype.workshop.ui.passwordGateAfterFailure
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException

/**
 * CHANGING THE PASSWORD ENDS THE SESSION IT WAS CHANGED FROM, SO THE ANSWER'S TOKEN MUST BE ADOPTED.
 *
 * Since 2026-10-09 every session token carries a fingerprint of the password it was opened with,
 * and `POST /api/auth/change-password` answers `{"ok": true}` with the new session in an
 * `X-Session-Token` response header: the write retires every older token of the account — the
 * handset's own included — and the token in that header is the session that goes on.
 * `WorkshopRepository.changeOwnPassword` has two jobs and an ORDER between them: store that token,
 * THEN re-read `/me`. The other order sends the re-read with a dead token, gets a plain 401, and
 * leaves a person who has just satisfied the gate holding a session every request refuses.
 *
 * THE BODY STAYS `{"ok": true}` FOR THE HANDSETS ALREADY IN THE FIELD. Builds 0.0.6–0.0.15 decode it
 * as `Map<String, Boolean>`, and a token beside `ok` fails that decode with the token quoted in the
 * message the gate puts on screen. So nothing here may read a token out of the body, and the last
 * test pins the body both decoders accept.
 *
 * Driven through the SAME Retrofit interface and the SAME decoder the app builds (`ApiClient.json`),
 * over a transport answering from memory — so the field names and the header are checked against
 * what is decoded, not against what a fake hands back. `ApiClient`'s interceptor reads the token
 * store afresh for every request, so "what the store held when `/me` went out" is exactly the token
 * `/me` carried.
 *
 * AND WHEN THE ANSWER IS LOST, THE GATE ASKS BEFORE IT SPEAKS. The same write that retires the old
 * token is why a change whose answer never arrived may still have landed; the second half of this
 * file drives the gate's verdict (`passwordGateAfterFailure`) over the same transport, with
 * `GET /me` as its probe.
 */
class ChangePasswordSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val opened = "session-opened-with-the-temporary-password"
    private val fresh = "session-minted-after-the-change"

    private fun profile(mustChange: Boolean) =
        """{"id":"u-1","email":"meera@example.org","name":"Meera","role":"DESIGNER",""" +
            """"mustChangePassword":$mustChange}"""

    /**
     * One canned answer, or no answer at all (a dropped connection) when [code] is null. [headers]
     * are sent spelled exactly as given, because the spelling is part of what is under test.
     */
    private class Canned(val code: Int?, val body: String = "", val headers: Map<String, String> = emptyMap())

    /** What the server sends today: the body every build decodes, and the new session beside it. */
    private fun changed(token: String, header: String = "X-Session-Token") =
        Canned(200, """{"ok":true}""", headers = mapOf(header to token))

    /**
     * The server: `change-password` answers [change], `/me` answers [me], and every `/me` records
     * what the token store held at the moment it was sent.
     */
    private inner class Server(
        private val store: TokenStore,
        private val change: Canned,
        private val me: Canned,
    ) : Call.Factory {
        val storeHeldAtReRead = mutableListOf<String?>()

        override fun newCall(request: Request): Call {
            val path = request.url.encodedPath
            val canned = when {
                request.method == "POST" && path.endsWith("/auth/change-password") -> change
                request.method == "GET" && path.endsWith("/me") -> {
                    storeHeldAtReRead += store.getToken()
                    me
                }
                else -> throw AssertionError("an unexpected request: ${request.method} $path")
            }
            return CannedCall(request, canned)
        }
    }

    private fun repository(server: Call.Factory, store: TokenStore): WorkshopRepository {
        val api = Retrofit.Builder()
            .baseUrl("http://localhost:8000/api/")
            .callFactory(server)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkshopRepositoryApi::class.java)
        return WorkshopRepository(api, store)
    }

    /** A handset signed in with a temporary password, standing on the gate. */
    private fun gatedHandset(): TokenStore = TokenStore(InMemoryContext(folder.root)).apply {
        setToken(opened)
        setUser(ApiClient.json.decodeFromString(UserDto.serializer(), profile(mustChange = true)))
    }

    @Test
    fun `the fresh session is adopted from the header before the profile is re-read`() {
        // Both spellings: HTTP/2 carries every header name in lower case, so the name as the server
        // wrote it is not the name that necessarily arrives.
        listOf("X-Session-Token", "x-session-token").forEach { header ->
            val store = gatedHandset()
            val server = Server(
                store,
                change = changed(fresh, header),
                me = Canned(200, profile(mustChange = false)),
            )

            runBlocking { repository(server, store).changeOwnPassword("temporary-pass", "chosen-by-meera") }

            assertEquals("$header: the re-read must go out with the new session", listOf(fresh), server.storeHeldAtReRead)
            assertEquals(header, fresh, store.getToken())
            assertEquals(header, false, store.getUser()?.mustChangePassword)
        }
    }

    @Test
    fun `an answer with no session header keeps the session this handset already has`() {
        // A server older than the fingerprint answers `{"ok": true}` with no header, and there the old
        // token still works. A blank header is the same answer: storing it would sign the person out
        // of the session they have just repaired. And a token in the BODY is not a session this build
        // reads at all — the body is decoded for `ok` alone — so it neither fails the change nor
        // replaces the token the handset holds.
        val answers = mapOf(
            "no header" to Canned(200, """{"ok":true}"""),
            "a blank header" to Canned(200, """{"ok":true}""", headers = mapOf("X-Session-Token" to "")),
            "a token in the body" to Canned(200, """{"ok":true,"accessToken":"$fresh"}"""),
        )
        answers.forEach { (case, change) ->
            val store = gatedHandset()
            val server = Server(store, change = change, me = Canned(200, profile(mustChange = false)))

            runBlocking { repository(server, store).changeOwnPassword("temporary-pass", "chosen-by-meera") }

            assertEquals(case, listOf(opened), server.storeHeldAtReRead)
            assertEquals(case, opened, store.getToken())
            assertEquals(case, false, store.getUser()?.mustChangePassword)
        }
    }

    @Test
    fun `a re-read that never answers still leaves the new session and an ungated profile`() {
        // The connection drops between the two requests, which on this fleet is an ordinary event.
        // The password has changed and the old token is dead, so the new one must already be in the
        // store; and the flag is cleared locally, or a cold start puts the person back on the gate.
        val store = gatedHandset()
        val server = Server(
            store,
            change = changed(fresh),
            me = Canned(code = null),
        )

        runBlocking { repository(server, store).changeOwnPassword("temporary-pass", "chosen-by-meera") }

        assertEquals(fresh, store.getToken())
        assertEquals(false, store.getUser()?.mustChangePassword)
    }

    @Test
    fun `a refused change adopts nothing and re-reads nothing`() {
        // The header rides on the refusal here only to prove nothing looks at it there: a change the
        // server refused retired no session, so there is nothing to adopt whatever came back.
        val store = gatedHandset()
        val server = Server(
            store,
            change = Canned(
                400,
                """{"detail":"Current password is incorrect"}""",
                headers = mapOf("X-Session-Token" to fresh),
            ),
            me = Canned(200, profile(mustChange = false)),
        )

        try {
            runBlocking { repository(server, store).changeOwnPassword("a-wrong-guess", "chosen-by-meera") }
            fail("a refused change must reach the screen as a failure")
        } catch (refused: HttpException) {
            assertEquals(400, refused.code())
            // The gate's status-then-sentence reading: the declaration now returns a `Response`, and
            // the repository must throw the refusal with its body still inside it.
            assertEquals("Current password is incorrect", refused.apiErrorMessage("no sentence"))
        }

        assertTrue("nothing was re-read", server.storeHeldAtReRead.isEmpty())
        assertEquals(opened, store.getToken())
        assertEquals("the gate stays up", true, store.getUser()?.mustChangePassword)
    }

    @Test
    fun `the body is the one every build decodes, shipped or not`() {
        // `ApiClient.json` stands in for the shipped decoder honestly: v0.0.15 builds it with the same
        // four flags. It does so on kotlinx-serialization 1.7.3, and this build has run 1.11.0 since
        // 2026-10-09 — so this passing is also the check that the two still read this body alike.
        val shipped = MapSerializer(String.serializer(), Boolean.serializer())
        val body = """{"ok":true}"""
        assertEquals(mapOf("ok" to true), ApiClient.json.decodeFromString(shipped, body))
        assertEquals(true, ApiClient.json.decodeFromString(ChangePasswordResponse.serializer(), body).ok)

        // WHY THE TOKEN IS NOT IN IT: a string beside `ok` fails the shipped map after the password
        // has changed, and those builds put the failure on screen. This build's type skips the key.
        val withToken = """{"ok":true,"accessToken":"$fresh"}"""
        try {
            ApiClient.json.decodeFromString(shipped, withToken)
            fail("the shipped decoder took a string beside `ok`")
        } catch (expected: SerializationException) {
            // The hazard this contract exists for.
        }
        assertEquals(true, ApiClient.json.decodeFromString(ChangePasswordResponse.serializer(), withToken).ok)
    }

    // ── WHEN THE CHANGE'S ANSWER IS LOST: the gate asks `GET /me` before it says anything ─────────
    //
    // The server commits the new password before it answers and retires the token the change was sent
    // with, so a change that failed WITHOUT an answer from the route may well have landed. The gate's
    // whole reaction is `passwordGateAfterFailure`; these drive it exactly as `PasswordGateScreen`
    // does — the change through the repository, then the verdict, with `refreshUser` as the probe —
    // over the same transport, so the probe is a real `GET /me` sent with what the store holds.

    /** The plain 401 a retired session gets: no gate header, the server's own sentence. */
    private val retired = Canned(401, """{"detail":"This session is no longer valid. Sign in again."}""")

    private fun gateAfterFailedChange(server: Server, store: TokenStore): PasswordGateAfterFailure =
        runBlocking {
            val repository = repository(server, store)
            val failure = runCatching { repository.changeOwnPassword("temporary-pass", "chosen-by-meera") }
                .exceptionOrNull()
                ?: throw AssertionError("the change was meant to fail")
            passwordGateAfterFailure(failure, online = true) { repository.refreshUser() }
        }

    @Test
    fun `a lost answer followed by a refused probe signs out, saying the new password may be in force`() {
        // THE CASE THAT LOCKED PEOPLE OUT. The connection drops after the change is sent; the server
        // has the new password and has retired this session. The gate used to say nothing had
        // changed, and the person typed the temporary password at the door and was refused.
        val store = gatedHandset()
        val server = Server(store, change = Canned(code = null), me = retired)

        val verdict = gateAfterFailedChange(server, store)

        assertEquals(PasswordGateAfterFailure.SignOut(PASSWORD_MAY_ALREADY_BE_IN_EFFECT), verdict)
        assertEquals("the probe went out with the session this handset holds", listOf(opened), server.storeHeldAtReRead)
        // The verdict signs nobody out by itself: that is the root's, through `onSessionEnded`.
        assertEquals(opened, store.getToken())
    }

    @Test
    fun `a lost answer followed by a profile that still owes a password keeps today's message`() {
        // Nothing landed, so what the gate always said is true: the failure's own words, here the
        // transport's, exactly as `apiErrorMessage` gave them before the probe existed.
        val store = gatedHandset()
        val server = Server(store, change = Canned(code = null), me = Canned(200, profile(mustChange = true)))

        assertEquals(PasswordGateAfterFailure.Stay("the connection dropped"), gateAfterFailedChange(server, store))
        assertEquals(listOf(opened), server.storeHeldAtReRead)
        assertEquals("the gate stays up", true, store.getUser()?.mustChangePassword)
    }

    @Test
    fun `a 401 on the change itself takes the same probe path`() {
        // A retry of a change that landed — by the person, or by a transport resending it — is sent
        // with the session the first one retired, and comes back a plain 401. That sentence is true
        // ("This session is no longer valid") and useless: the probe decides.
        val store = gatedHandset()
        val server = Server(store, change = retired, me = retired)

        assertEquals(PasswordGateAfterFailure.SignOut(PASSWORD_MAY_ALREADY_BE_IN_EFFECT), gateAfterFailedChange(server, store))
        assertEquals("the probe was asked", listOf(opened), server.storeHeldAtReRead)
    }

    @Test
    fun `a gateway's 504 after the send takes the probe path too`() {
        // CloudFront gives up on a slow origin that may already have committed the write.
        val store = gatedHandset()
        val server = Server(store, change = Canned(504, "<html>504 Gateway Timeout</html>"), me = retired)

        assertEquals(PasswordGateAfterFailure.SignOut(PASSWORD_MAY_ALREADY_BE_IN_EFFECT), gateAfterFailedChange(server, store))
    }

    @Test
    fun `a probe that goes unanswered too leaves the outcome unknown, and says so`() {
        // No signal for either request: nothing is known, so the gate stays up and names both
        // passwords for whoever signs out instead of retrying.
        listOf(
            "no answer" to Canned(code = null),
            "a gateway error" to Canned(503, "<html>503</html>"),
            "a refused account" to Canned(403, """{"detail":"Your access has been suspended."}"""),
        ).forEach { (case, probe) ->
            val store = gatedHandset()
            val server = Server(store, change = Canned(code = null), me = probe)

            assertEquals(case, PasswordGateAfterFailure.Stay(PASSWORD_CHANGE_UNCONFIRMED), gateAfterFailedChange(server, store))
            assertEquals(case, opened, store.getToken())
        }
    }

    @Test
    fun `a probe that finds no password owed satisfies the gate`() {
        // The server no longer asks for a change — a server older than the fingerprint keeps the
        // session and clears the flag in the same write — so holding the gate up would ask for a
        // "current" password that may no longer be current.
        val store = gatedHandset()
        val server = Server(store, change = Canned(code = null), me = Canned(200, profile(mustChange = false)))

        val verdict = gateAfterFailedChange(server, store)

        assertTrue(verdict is PasswordGateAfterFailure.Satisfied)
        assertEquals(false, (verdict as PasswordGateAfterFailure.Satisfied).profile.mustChangePassword)
        assertEquals(false, store.getUser()?.mustChangePassword)
    }

    @Test
    fun `a refusal from the route is shown as it was, and nothing is probed`() {
        // The route answered INSTEAD of writing: its sentence is the whole story, and a probe would
        // spend a request to learn nothing. The gate's own 401 is one of these too — a live session
        // that still owes a password.
        listOf(
            Canned(400, """{"detail":"Current password is incorrect"}""") to "Current password is incorrect",
            Canned(429, """{"detail":"Too many attempts. Try again in a few minutes."}""") to
                "Too many attempts. Try again in a few minutes.",
            Canned(
                401,
                """{"detail":"Choose a new password to continue."}""",
                headers = mapOf(PASSWORD_CHANGE_REQUIRED_HEADER to "1"),
            ) to "Choose a new password to continue.",
        ).forEach { (change, sentence) ->
            val store = gatedHandset()
            val server = Server(store, change = change, me = Canned(200, profile(mustChange = true)))

            assertEquals(sentence, PasswordGateAfterFailure.Stay(sentence), gateAfterFailedChange(server, store))
            assertTrue("$sentence: nothing was probed", server.storeHeldAtReRead.isEmpty())
        }
    }

    /** An OkHttp call that never opens a socket: it answers from [canned], synchronously. */
    private class CannedCall(private val req: Request, private val canned: Canned) : Call {
        override fun request(): Request = req
        // OkHttp 5.3 added these five to `okhttp3.Call`. A canned call carries no event listener
        // and no tags, so each answers as an empty one would.
        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun execute(): Response = answer()
        override fun enqueue(responseCallback: Callback) {
            val response = try {
                answer()
            } catch (dropped: IOException) {
                responseCallback.onFailure(this, dropped)
                return
            }
            responseCallback.onResponse(this, response)
        }
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = CannedCall(req, canned)

        private fun answer(): Response {
            val code = canned.code ?: throw IOException("the connection dropped")
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code < 400) "OK" else "Refused")
                .apply { canned.headers.forEach { (name, value) -> header(name, value) } }
                .body(canned.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
