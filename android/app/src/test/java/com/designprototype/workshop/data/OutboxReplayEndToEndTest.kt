package com.designprototype.workshop.data

import com.sun.net.httpserver.HttpServer
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

/**
 * THE RELEASE BLOCKER OF 2026-10-16, END TO END: an online create lands, its answer is lost, the form
 * moves it into the outbox under the same Idempotency-Key, and the replay finds the record the first
 * send made — one record, with its photograph, and no duplicate.
 *
 * The server here is a fake with the real middleware's rules (`backend/app/core/idempotency.py`): a
 * repeat of a key with the SAME body is answered from the stored record; the same key with a DIFFERENT
 * body is a 422 `IDEMPOTENCY_KEY_REUSED`; `GET /api/idempotency-keys/{key}` hands back the stored
 * answer. Storage is a real local HTTP server, because the photograph's bytes go through the
 * repository's own storage client.
 */
class OutboxReplayEndToEndTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: InMemoryContext
    private lateinit var storage: HttpServer
    private val user = UserDto(id = "u-designer", email = "d@example.org", name = "Dee", role = "DESIGNER")

    // ── The fake server's state ──────────────────────────────────────────────────────────────────
    private data class Stored(val body: String, val answer: String)
    private val keys = HashMap<String, Stored>()
    private val crafts = ArrayList<String>()
    private val mediaLinkedTo = ArrayList<String>()
    private var loseNextCreateAnswer = false
    private val sent = ArrayList<String>()

    @Before
    fun setUp() {
        context = InMemoryContext(folder.root)
        ConnectivityObserver.answerForTests = true
        storage = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/put") { exchange ->
                exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
    }

    @After
    fun tearDown() {
        ConnectivityObserver.answerForTests = null
        storage.stop(0)
    }

    private fun repository(): WorkshopRepository {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true; coerceInputValues = true }
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = FakeCall(request)
        }
        val api = Retrofit.Builder()
            .baseUrl("http://localhost:8000/api/")
            .callFactory(factory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkshopRepositoryApi::class.java)
        val tokens = TokenStore(context).apply { setToken("token"); setUser(user) }
        return WorkshopRepository(api, tokens)
    }

    private fun answer(route: String, key: String?, body: String): Pair<Int, String> = when {
        route == "POST /api/crafts" -> {
            val stored = key?.let { keys[it] }
            when {
                stored != null && stored.body == body -> 201 to stored.answer
                stored != null -> 422 to """{"detail":"This Idempotency-Key was already used for a different request.","code":"IDEMPOTENCY_KEY_REUSED"}"""
                else -> {
                    val id = "craft_${crafts.size + 1}"
                    crafts += id
                    val created = """{"id":"$id"}"""
                    if (key != null) keys[key] = Stored(body, created)
                    if (loseNextCreateAnswer) {
                        loseNextCreateAnswer = false
                        throw IOException("the answer was lost in a tunnel")
                    }
                    201 to created
                }
            }
        }
        route.startsWith("GET /api/idempotency-keys/") -> {
            val stored = keys[route.removePrefix("GET /api/idempotency-keys/")]
            if (stored == null) 404 to """{"detail":"No stored answer for that key."}"""
            else 200 to """{"statusCode":201,"body":${stored.answer}}"""
        }
        route == "POST /api/media/presign" ->
            200 to """{"uploadUrl":"http://127.0.0.1:${storage.address.port}/put/a","objectKey":"media/a.jpg","bucket":"b"}"""
        route == "POST /api/media/complete" -> {
            val linked = Regex(""""linkedRecordId":"([^"]+)"""").find(body)?.groupValues?.get(1).orEmpty()
            mediaLinkedTo += linked
            200 to """{"id":"m${mediaLinkedTo.size}","originalFilename":"a.jpg","mediaType":"IMAGE"}"""
        }
        else -> 404 to """{"detail":"Not Found"}"""
    }

    private inner class FakeCall(private val req: Request) : Call {
        override fun request(): Request = req
        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun execute(): Response = respond()
        override fun enqueue(responseCallback: Callback) {
            val response = try {
                respond()
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
        override fun clone(): Call = FakeCall(req)

        private fun respond(): Response {
            val route = "${req.method} ${req.url.encodedPath}"
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            sent += route
            val (code, text) = answer(route, req.header("Idempotency-Key"), body)
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code < 400) "OK" else "Refused")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    /** Queue the create the way the form's fallback does, and give it one staged photograph. */
    private fun queueWithPhoto(repository: WorkshopRepository, body: CraftCreateRequest, key: String): PendingEntry =
        runBlocking {
            val result = repository.queueOfflineEntry(
                context,
                "craft",
                Json.encodeToString(CraftCreateRequest.serializer(), body),
                body.name,
                items = emptyList(),
                idempotencyKey = key,
            )
            val photo = File(folder.root, "photo.jpg").apply { writeBytes(ByteArray(64) { 7 }) }
            val entry = OfflineOutbox.all(context).single { it.id == result.entryId }
            val withPhoto = entry.copy(
                media = listOf(PendingMedia(photo.absolutePath, "photo.jpg", "image/jpeg", "IMAGE", linkedType = "craft")),
            )
            OfflineOutbox.update(context, withPhoto)
            withPhoto
        }

    @Test
    fun `a create whose answer was lost is replayed into ONE record that gets its photograph`() {
        val repository = repository()
        val key = "4f1d2c7e-0000-4000-8000-000000000001"
        val body = CraftCreateRequest(name = "Ajrakh", recordedAt = "2026-10-16T08:00:00Z")

        loseNextCreateAnswer = true
        val online = runCatching { runBlocking { repository.createCraft(body, key) } }
        assertTrue("the online answer is lost", online.exceptionOrNull() is IOException)
        assertTrue("and the form would fall back", repository.isTransient(online.exceptionOrNull()!!))

        val queued = queueWithPhoto(repository, body, key)
        assertEquals(key, queued.idempotencyKey)
        assertNull("a carried key brings no second clientKey", queued.clientKey)

        runBlocking { repository.syncOutbox(context) }

        assertEquals("no duplicate", listOf("craft_1"), crafts)
        assertEquals("the photograph is on the record the first send made", listOf("craft_1"), mediaLinkedTo)
        assertTrue("the outbox is empty", runBlocking { OfflineOutbox.all(context) }.isEmpty())
    }

    @Test
    fun `a replay refused as a different request finds the stored record instead of losing the photograph`() {
        val repository = repository()
        val key = "4f1d2c7e-0000-4000-8000-000000000002"
        // The first send, as an older build made it: same key, a body that differs in one field.
        loseNextCreateAnswer = true
        runCatching { runBlocking { repository.createCraft(CraftCreateRequest(name = "Ajrakh", place = "Bhuj"), key) } }

        queueWithPhoto(repository, CraftCreateRequest(name = "Ajrakh", place = "Kutch"), key)
        runBlocking { repository.syncOutbox(context) }

        assertTrue("the replay was refused as a different request", sent.contains("GET /api/idempotency-keys/$key"))
        assertEquals("still one record", listOf("craft_1"), crafts)
        assertEquals("and its photograph attached to it", listOf("craft_1"), mediaLinkedTo)
        assertTrue(runBlocking { OfflineOutbox.all(context) }.isEmpty())
    }

    @Test
    fun `a queued product carrying the online key brings no second clientKey`() {
        val repository = repository()
        val carried = runBlocking {
            repository.queueOfflineEntry(context, "product", "{}", "Shawl", emptyList(), idempotencyKey = "k-carried-0001")
        }
        val fresh = runBlocking { repository.queueOfflineEntry(context, "product", "{}", "Shawl", emptyList()) }
        val all = runBlocking { OfflineOutbox.all(context) }
        assertNull(all.single { it.id == carried.entryId }.clientKey)
        val minted = all.single { it.id == fresh.entryId }
        assertTrue("an entry queued offline mints both", minted.clientKey != null && minted.idempotencyKey != null)
    }
}
