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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class DwReferenceScopeWireTest {

    private val workshop = "cmsik2jg8000eh8xc1lcy661a"

    @Test
    fun `a WORKSHOP-scoped picker asks the server to narrow to this workshop`() {
        val url = urlOf { it.designWorkshopReferences(id = workshop, model = "Artisan", scope = "WORKSHOP") }

        assertEquals("WORKSHOP", url.queryParameter("scope"))
        assertEquals("Artisan", url.queryParameter("model"))
    }

    @Test
    fun `an ALL-scoped picker says ALL out loud rather than relying on the server's default`() {
        val url = urlOf { it.designWorkshopReferences(id = workshop, model = "DwParticipant", scope = "ALL") }

        assertEquals("ALL", url.queryParameter("scope"))
    }

    @Test
    fun `a registry that named no scope sends no scope, rather than an empty one`() {
        val url = urlOf { it.designWorkshopReferences(id = workshop, model = "Artisan", scope = null) }

        assertNull("`scope=` is a 422, not a fallback", url.queryParameter("scope"))
        assertEquals("the rest of the request must be unaffected", "Artisan", url.queryParameter("model"))
    }

    @Test
    fun `the scope travels beside the cascade rather than displacing it`() {
        val url = urlOf {
            it.designWorkshopReferences(
                id = workshop,
                model = "ProductDocumentation",
                scope = "WORKSHOP",
                filterBy = "cmsiusb3a002yrmg1gnl4mfc1",
            )
        }

        assertEquals("WORKSHOP", url.queryParameter("scope"))
        assertEquals("cmsiusb3a002yrmg1gnl4mfc1", url.queryParameter("filterBy"))
        assertEquals("ProductDocumentation", url.queryParameter("model"))
        assertNull(url.queryParameter("search"))
    }

    @Test
    fun `a workshop-owned cache written before the fix can no longer be reached`() {
        val stale = DwReferenceStore.cacheKey("Artisan", "WORKSHOP", workshop, "")
        val fresh = DwReferenceStore.cacheKey("Artisan", "WORKSHOP", dwReferenceCacheOwner(workshop), "")

        assertNotEquals("the pre-fix file is still being read", stale, fresh)
    }

    @Test
    fun `retiring the poisoned entries leaves every ALL-scoped register on the device`() {
        assertEquals(
            DwReferenceStore.cacheKey("DwParticipant", "ALL", workshop, ""),
            DwReferenceStore.cacheKey("DwParticipant", "ALL", dwReferenceCacheOwner(workshop), ""),
        )
    }

    @Test
    fun `a workshop that has never reached the server is not given a generation`() {
        assertEquals("", dwReferenceCacheOwner(""))
    }

    private val emptyPayload = """{"model":"Artisan","scope":"ALL","options":[]}"""

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
                return CannedCall(request, emptyPayload)
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
