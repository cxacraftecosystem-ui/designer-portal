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

class ChangePasswordSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val opened = "session-opened-with-the-temporary-password"
    private val fresh = "session-minted-after-the-change"

    private fun profile(mustChange: Boolean) =
        """{"id":"u-1","email":"meera@example.org","name":"Meera","role":"DESIGNER",""" +
            """"mustChangePassword":$mustChange}"""

    private class Canned(val code: Int?, val body: String = "", val headers: Map<String, String> = emptyMap())

    private fun changed(token: String, header: String = "X-Session-Token") =
        Canned(200, """{"ok":true}""", headers = mapOf(header to token))

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

    private fun gatedHandset(): TokenStore = TokenStore(InMemoryContext(folder.root)).apply {
        setToken(opened)
        setUser(ApiClient.json.decodeFromString(UserDto.serializer(), profile(mustChange = true)))
    }

    @Test
    fun `the fresh session is adopted from the header before the profile is re-read`() {
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
            assertEquals("Current password is incorrect", refused.apiErrorMessage("no sentence"))
        }

        assertTrue("nothing was re-read", server.storeHeldAtReRead.isEmpty())
        assertEquals(opened, store.getToken())
        assertEquals("the gate stays up", true, store.getUser()?.mustChangePassword)
    }

    @Test
    fun `the body is the one every build decodes, shipped or not`() {
        val shipped = MapSerializer(String.serializer(), Boolean.serializer())
        val body = """{"ok":true}"""
        assertEquals(mapOf("ok" to true), ApiClient.json.decodeFromString(shipped, body))
        assertEquals(true, ApiClient.json.decodeFromString(ChangePasswordResponse.serializer(), body).ok)

        val withToken = """{"ok":true,"accessToken":"$fresh"}"""
        try {
            ApiClient.json.decodeFromString(shipped, withToken)
            fail("the shipped decoder took a string beside `ok`")
        } catch (expected: SerializationException) {
        }
        assertEquals(true, ApiClient.json.decodeFromString(ChangePasswordResponse.serializer(), withToken).ok)
    }

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
        val store = gatedHandset()
        val server = Server(store, change = Canned(code = null), me = retired)

        val verdict = gateAfterFailedChange(server, store)

        assertEquals(PasswordGateAfterFailure.SignOut(PASSWORD_MAY_ALREADY_BE_IN_EFFECT), verdict)
        assertEquals("the probe went out with the session this handset holds", listOf(opened), server.storeHeldAtReRead)
        assertEquals(opened, store.getToken())
    }

    @Test
    fun `a lost answer followed by a profile that still owes a password keeps today's message`() {
        val store = gatedHandset()
        val server = Server(store, change = Canned(code = null), me = Canned(200, profile(mustChange = true)))

        assertEquals(PasswordGateAfterFailure.Stay("the connection dropped"), gateAfterFailedChange(server, store))
        assertEquals(listOf(opened), server.storeHeldAtReRead)
        assertEquals("the gate stays up", true, store.getUser()?.mustChangePassword)
    }

    @Test
    fun `a 401 on the change itself takes the same probe path`() {
        val store = gatedHandset()
        val server = Server(store, change = retired, me = retired)

        assertEquals(PasswordGateAfterFailure.SignOut(PASSWORD_MAY_ALREADY_BE_IN_EFFECT), gateAfterFailedChange(server, store))
        assertEquals("the probe was asked", listOf(opened), server.storeHeldAtReRead)
    }

    @Test
    fun `a gateway's 504 after the send takes the probe path too`() {
        val store = gatedHandset()
        val server = Server(store, change = Canned(504, "<html>504 Gateway Timeout</html>"), me = retired)

        assertEquals(PasswordGateAfterFailure.SignOut(PASSWORD_MAY_ALREADY_BE_IN_EFFECT), gateAfterFailedChange(server, store))
    }

    @Test
    fun `a probe that goes unanswered too leaves the outcome unknown, and says so`() {
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
        val store = gatedHandset()
        val server = Server(store, change = Canned(code = null), me = Canned(200, profile(mustChange = false)))

        val verdict = gateAfterFailedChange(server, store)

        assertTrue(verdict is PasswordGateAfterFailure.Satisfied)
        assertEquals(false, (verdict as PasswordGateAfterFailure.Satisfied).profile.mustChangePassword)
        assertEquals(false, store.getUser()?.mustChangePassword)
    }

    @Test
    fun `a refusal from the route is shown as it was, and nothing is probed`() {
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

    private class CannedCall(private val req: Request, private val canned: Canned) : Call {
        override fun request(): Request = req
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
