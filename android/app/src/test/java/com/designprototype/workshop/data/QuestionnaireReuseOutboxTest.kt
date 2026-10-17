package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

/**
 * "Use this questionnaire again" with and without a signal (2026-10-17): when it is sent now, when it
 * waits in the outbox, and that the drain sends it with its key and with every temporary id swapped
 * for the server's — over a real Retrofit and a scripted transport, so the path, the header and the
 * body are the ones the annotations produce.
 */
class QuestionnaireReuseOutboxTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: InMemoryContext
    private val script = HashMap<String, () -> Pair<Int, String>>()
    private val sent = ArrayList<Triple<String, String, String?>>()

    private val designer = UserDto(id = "u-1", email = "d@example.org", name = "Dev", role = "DESIGNER")

    @Before
    fun setUp() {
        context = InMemoryContext(folder.root)
    }

    private fun repository(): WorkshopRepository {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true; coerceInputValues = true }
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = ScriptedCall(request)
        }
        val api = Retrofit.Builder()
            .baseUrl("http://localhost:8000/api/")
            .callFactory(factory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkshopRepositoryApi::class.java)
        return WorkshopRepository(api, TokenStore(context).apply { setUser(designer) })
    }

    private val copied =
        """{"questionnaire":{"id":"q-copy","title":"Loom survey (copy)"},"sourceQuestionnaireId":"q-1"}"""

    // ── When it is sent now ───────────────────────────────────────────────────────────────────

    @Test
    fun `with a signal and nothing queued for the source, the copy is made now`() {
        assertTrue(reuseCanSendNow("q-1", "dw-1", emptyMap(), pendingForSource = 0, online = true))
    }

    @Test
    fun `with no signal it waits`() {
        assertFalse(reuseCanSendNow("q-1", null, emptyMap(), pendingForSource = 0, online = false))
    }

    @Test
    fun `it waits behind earlier steps for the same questionnaire, so the copy includes them`() {
        assertFalse(reuseCanSendNow("q-1", null, emptyMap(), pendingForSource = 2, online = true))
    }

    @Test
    fun `it waits while the source or the workshop exists only on this phone, and not once it is saved`() {
        assertFalse(reuseCanSendNow("tmp-q", null, emptyMap(), pendingForSource = 0, online = true))
        assertFalse(reuseCanSendNow("q-1", "tmp-dw", emptyMap(), pendingForSource = 0, online = true))
        assertTrue(
            reuseCanSendNow(
                "tmp-q", "tmp-dw", mapOf("tmp-q" to "q-1", "tmp-dw" to "dw-1"), pendingForSource = 0, online = true,
            )
        )
    }

    // ── Queued, then drained ──────────────────────────────────────────────────────────────────

    @Test
    fun `with no signal the copy is queued with a key, and nothing is sent`() {
        val made = runBlocking {
            repository().reuseQuestionnaireOrQueue(
                context, "q-1", designWorkshopId = "dw-1", title = "Loom survey", label = "Copy",
            )
        }
        assertNull(made)
        assertTrue(sent.isEmpty())
        val entry = runBlocking { OfflineOutbox.all(context) }.single()
        assertEquals(OFFLINE_QFORM_OP, entry.type)
        assertTrue(!entry.idempotencyKey.isNullOrBlank())
        val op = Json.decodeFromString(QFormOp.serializer(), entry.payloadJson)
        assertEquals(QFormOp.REUSE, op.op)
        assertEquals("dw-1", op.designWorkshopId)
    }

    @Test
    fun `the drain sends the copy with its key and the server ids, and a replay carries the same key`() {
        script["POST /api/questionnaires/q-1/reuse"] = { 201 to copied }
        runBlocking {
            TempIdMap.put(context, "tmp-q", "q-1")
            TempIdMap.put(context, "tmp-dw", "dw-1")
        }
        val op = QFormOp(op = QFormOp.REUSE, questionnaireId = "tmp-q", designWorkshopId = "tmp-dw", title = "Loom survey")
        val repo = repository()
        runBlocking {
            repo.applyQuestionnaireOp(context, op, "key-12345678")
            // The answer was lost and the outbox sends it again: the same key, so the server answers
            // from the first copy instead of making a second.
            repo.applyQuestionnaireOp(context, op, "key-12345678")
        }
        assertEquals(2, sent.size)
        sent.forEach { (route, body, key) ->
            assertEquals("POST /api/questionnaires/q-1/reuse", route)
            assertEquals("key-12345678", key)
            assertTrue(body, body.contains("\"designWorkshopId\":\"dw-1\""))
            assertFalse(body, body.contains("tmp-"))
        }
    }

    @Test
    fun `a copy for a workshop that was never saved waits and sends nothing`() {
        val op = QFormOp(op = QFormOp.REUSE, questionnaireId = "q-1", designWorkshopId = "tmp-dw")
        val failure = runCatching { runBlocking { repository().applyQuestionnaireOp(context, op, "key-12345678") } }
        assertTrue(failure.exceptionOrNull().toString(), failure.exceptionOrNull() is QFormParentMissing)
        assertTrue(sent.isEmpty())
    }

    private inner class ScriptedCall(private val req: Request) : Call {
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
            } catch (e: IOException) {
                responseCallback.onFailure(this, e)
                return
            }
            responseCallback.onResponse(this, response)
        }
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ScriptedCall(req)

        private fun answer(): Response {
            val route = "${req.method} ${req.url.encodedPath}"
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            sent += Triple(route, body, req.header("Idempotency-Key"))
            val (code, text) = checkNotNull(script[route]) { "unscripted request: $route" }.invoke()
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code < 400) "OK" else "Refused")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
