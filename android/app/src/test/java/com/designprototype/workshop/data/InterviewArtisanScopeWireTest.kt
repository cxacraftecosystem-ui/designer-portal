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
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class InterviewArtisanScopeWireTest {

    private val workshop = "cmw0rk5h0p00000000000000"
    private val designWorkshop = "cmd3s1gnw0rksh0p00000000"

    @Test
    fun `an ordinary workshop travels as the plural workshopIds and nothing else`() {
        val url = urlFor(
            interviewArtisanScope(
                routesToDesignWorkshop = false,
                workshopId = workshop,
                designWorkshopId = null,
            )
        )

        assertEquals(workshop, url.queryParameter("workshopIds"))
        assertNull("the singular is a NARROWER filter, not a synonym", url.queryParameter("workshopId"))
        assertNull(
            "one scope at a time — see the contract's artisanScope.singular",
            url.queryParameter("designWorkshopId")
        )
    }

    @Test
    fun `a design workshop travels as designWorkshopId and nothing else`() {
        val url = urlFor(
            interviewArtisanScope(
                routesToDesignWorkshop = true,
                workshopId = null,
                designWorkshopId = designWorkshop,
            )
        )

        assertEquals(designWorkshop, url.queryParameter("designWorkshopId"))
        assertNull(url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("workshopId"))
    }

    @Test
    fun `the two are never sent together, whichever ids the two halves are holding`() {
        val design = urlFor(
            interviewArtisanScope(
                routesToDesignWorkshop = true,
                workshopId = workshop,
                designWorkshopId = designWorkshop,
            )
        )
        assertEquals(designWorkshop, design.queryParameter("designWorkshopId"))
        assertNull("an AND of two rosters is a handful of rows, not a scope", design.queryParameter("workshopIds"))

        val ordinary = urlFor(
            interviewArtisanScope(
                routesToDesignWorkshop = false,
                workshopId = workshop,
                designWorkshopId = designWorkshop,
            )
        )
        assertEquals(workshop, ordinary.queryParameter("workshopIds"))
        assertNull(ordinary.queryParameter("designWorkshopId"))
    }

    @Test
    fun `the half that is not routed to cannot leak even when it is the only id held`() {
        val url = urlFor(
            interviewArtisanScope(
                routesToDesignWorkshop = true,
                workshopId = workshop,
                designWorkshopId = null,
            )
        )
        assertNull(url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("designWorkshopId"))
    }

    @Test
    fun `no workshop chosen sends no scope at all, so every artisan is still offered`() {
        val url = urlFor(interviewArtisanScope(routesToDesignWorkshop = false, workshopId = "", designWorkshopId = ""))

        assertNull(url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("designWorkshopId"))
        assertNull(url.queryParameter("workshopId"))
    }

    @Test
    fun `a blank id is not a scope, on either half`() {
        val ordinary = interviewArtisanScope(routesToDesignWorkshop = false, workshopId = "   ", designWorkshopId = null)
        val design = interviewArtisanScope(routesToDesignWorkshop = true, workshopId = null, designWorkshopId = "  ")

        assertNull(ordinary.workshopIds)
        assertNull(design.designWorkshopId)
        assertTrue("a blank id is the unnarrowed state, not a narrowing", !ordinary.narrowed && !design.narrowed)
    }

    @Test
    fun `the type key never reaches the wire`() {
        val url = urlFor(
            interviewArtisanScope(
                routesToDesignWorkshop = true,
                workshopId = null,
                designWorkshopId = designWorkshop,
            )
        )
        for (spelling in listOf("workshopKind", "workshopType", "workshopTypeKey", "type", "kind")) {
            assertNull("the chosen type is not a filter on /artisans", url.queryParameter(spelling))
        }
    }

    @Test
    fun `the scope key changes with the workshop, which is what re-fetches the roster`() {
        val a = interviewArtisanScope(routesToDesignWorkshop = false, workshopId = workshop, designWorkshopId = null)
        val b = interviewArtisanScope(routesToDesignWorkshop = true, workshopId = null, designWorkshopId = designWorkshop)
        val none = interviewArtisanScope(routesToDesignWorkshop = false, workshopId = "", designWorkshopId = "")

        assertEquals(3, setOf(a.key, b.key, none.key).size)
        val sameId = interviewArtisanScope(routesToDesignWorkshop = true, workshopId = null, designWorkshopId = workshop)
        assertTrue(a.key != sameId.key)
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

    private fun urlFor(scope: InterviewArtisanScope): HttpUrl {
        captured = null
        val service = api()
        drive { fetchInterviewArtisans(service, scope) }
        return checkNotNull(captured) { "the roster load never issued a request" }
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
