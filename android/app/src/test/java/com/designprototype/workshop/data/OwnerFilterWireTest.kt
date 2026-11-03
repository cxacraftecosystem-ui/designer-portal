package com.designprototype.workshop.data

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class OwnerFilterWireTest {

    private val owner = "cmsj1uoe900032ruqnx9518pw"

    @Test
    fun `every record list My Activity reads asks the server for one owner`() {
        assertEquals("artisans", owner, urlOf { it.artisans(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("products", owner, urlOf { it.products(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("tools", owner, urlOf { it.tools(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("processes", owner, urlOf { it.processes(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("crafts", owner, urlOf { it.crafts(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("workshops", owner, urlOf { it.workshops(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("interviews", owner, urlOf { it.interviews(createdBy = owner) }.queryParameter("createdBy"))
    }

    @Test
    fun `the completeness filter rides on every list that carries a meter, and only when asked`() {
        assertEquals("required", urlOf { it.artisans(completeness = "required") }.queryParameter("completeness"))
        assertEquals("incomplete", urlOf { it.products(completeness = "incomplete") }.queryParameter("completeness"))
        assertEquals("incomplete", urlOf { it.tools(completeness = "incomplete") }.queryParameter("completeness"))
        assertEquals("incomplete", urlOf { it.workshops(completeness = "incomplete") }.queryParameter("completeness"))
        assertEquals("required", urlOf { it.interviews(completeness = "required") }.queryParameter("completeness"))
        assertNull(urlOf { it.artisans() }.queryParameter("completeness"))
        assertEquals("records/completeness-rules", urlOf { it.completenessRules() }.encodedPath.removePrefix("/api/"))
    }

    @Test
    fun `media asks by uploadedBy, which is not createdBy`() {
        val url = urlOf { it.media(uploadedBy = owner) }

        assertEquals("a media row's owner column is uploadedById", owner, url.queryParameter("uploadedBy"))
        assertNull(
            "spelling media's owner filter `createdBy` is answered 200 with the whole repository",
            url.queryParameter("createdBy")
        )
    }

    @Test
    fun `a null owner sends no owner parameter, so every other caller is unaffected`() {
        assertNull(urlOf { it.artisans() }.queryParameter("createdBy"))
        assertNull(urlOf { it.products() }.queryParameter("createdBy"))
        assertNull(urlOf { it.tools() }.queryParameter("createdBy"))
        assertNull(urlOf { it.processes() }.queryParameter("createdBy"))
        assertNull(urlOf { it.crafts() }.queryParameter("createdBy"))
        assertNull(urlOf { it.workshops() }.queryParameter("createdBy"))
        assertNull(urlOf { it.interviews() }.queryParameter("createdBy"))
        assertNull(urlOf { it.media() }.queryParameter("uploadedBy"))
    }

    @Test
    fun `the owner filter does not displace the filters that already shared these routes`() {
        val artisans = urlOf { it.artisans(workshopIds = "w1,w2", createdBy = owner) }
        assertEquals("w1,w2", artisans.queryParameter("workshopIds"))
        assertEquals(owner, artisans.queryParameter("createdBy"))

        val media = urlOf { it.media(linkedRecordType = "artisan", linkedRecordId = "a1", uploadedBy = owner) }
        assertEquals("artisan", media.queryParameter("linkedRecordType"))
        assertEquals("a1", media.queryParameter("linkedRecordId"))
        assertEquals(owner, media.queryParameter("uploadedBy"))
    }

    private val emptyPage = """{"items":[],"total":0,"page":1,"pageSize":100,"pages":0}"""

    private var captured: HttpUrl? = null

    private fun api(): WorkshopRepositoryApi {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call {
                captured = request.url
                return CannedCall(request, emptyPage)
            }
        }
        return Retrofit.Builder()
            .baseUrl("http://localhost:8000/api/")
            .callFactory(factory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkshopRepositoryApi::class.java)
    }

    private fun urlOf(call: suspend (WorkshopRepositoryApi) -> Any): HttpUrl {
        captured = null
        val service = api()
        drive { call(service) }
        return checkNotNull(captured) { "the service never issued a request" }
    }

    private fun <T> drive(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome) { "the call suspended; the canned transport answers synchronously" }
            .getOrThrow()
    }

    private class CannedCall(private val req: Request, private val body: String) : Call {
        override fun request(): Request = req
        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun execute(): Response = canned()
        override fun enqueue(responseCallback: Callback) = responseCallback.onResponse(this, canned())
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = CannedCall(req, body)

        private fun canned(): Response = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
