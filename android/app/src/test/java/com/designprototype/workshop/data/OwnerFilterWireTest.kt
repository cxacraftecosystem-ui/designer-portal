package com.designprototype.workshop.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
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

/**
 * "WHOSE RECORDS ARE THESE" IS ASKED OF THE SERVER, AND THE URL IS WHERE THAT IS TRUE OR NOT.
 *
 * ── THE DEFECT THIS PINS ──────────────────────────────────────────────────────────────────────────
 *
 * My Activity and the sharing screen's record picker built "my records" by fetching one page of a
 * hundred rows per type and filtering it on `createdById` in the client. That is correct only while
 * reading the repository is owner-scoped, and it is not: `backend/app/services/records.py`
 * `viewable_where` returns an empty `where` — everything, for every signed-in account — so page one
 * is the hundred most recent rows ANYONE recorded.
 *
 * MEASURED against http://localhost:8000 as designer@example.org while writing this test, not
 * inferred: 437 artisans, 618 products, 125 tools, 125 processes, 126 crafts and 144 workshops
 * exist. This designer owns 1 artisan and 1 media file and NEITHER is on page one of its list, so
 * the screen said "You haven't recorded anything yet" over work they had recorded, and the picker
 * said "You have no records to share" over records they own.
 *
 * ── WHY IT IS PINNED AT THE WIRE AND NOT AT THE SCREEN ────────────────────────────────────────────
 *
 * The fix is a query parameter, so the only thing that can prove it is a URL. A screen-level test
 * would pass against a repository that accepted `createdBy` and quietly dropped it — which is
 * exactly the shape of the bug, since the client-side filter is deliberately KEPT on top and hides
 * the difference whenever the caller's own rows happen to be on page one anyway.
 *
 * The three properties below are each a way the fix can be undone without any other test noticing:
 *
 *  1. the owner parameter reaches the URL at all, on every list My Activity reads;
 *  2. MEDIA spells it `uploadedBy`, because a media row's owner column is `uploadedById` while every
 *     record's is `createdById`. FastAPI ignores an unknown query parameter rather than rejecting
 *     it, so "tidying" this one to `createdBy` for consistency would be answered 200 with the WHOLE
 *     repository — silently restoring the defect on the one list that cannot be spot-checked by
 *     eye, because a designer's own uploads look like everyone else's;
 *  3. a null owner omits the parameter entirely, which is what keeps every OTHER caller of these
 *     same functions — the form pickers, the completion matrix, the View Data browser — reading the
 *     whole repository as they always did.
 */
class OwnerFilterWireTest {

    private val owner = "cmsj1uoe900032ruqnx9518pw"

    // ── The three properties ─────────────────────────────────────────────────────────────────────

    @Test
    fun `every record list My Activity reads asks the server for one owner`() {
        // One row per `attempt { ... }` in `loadMyActivity`. A list added to that screen without an
        // owner parameter belongs here and will be missing from this table.
        assertEquals("artisans", owner, urlOf { it.artisans(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("products", owner, urlOf { it.products(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("tools", owner, urlOf { it.tools(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("processes", owner, urlOf { it.processes(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("crafts", owner, urlOf { it.crafts(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("workshops", owner, urlOf { it.workshops(createdBy = owner) }.queryParameter("createdBy"))
        assertEquals("interviews", owner, urlOf { it.interviews(createdBy = owner) }.queryParameter("createdBy"))
    }

    @Test
    fun `media asks by uploadedBy, which is not createdBy`() {
        val url = urlOf { it.media(uploadedBy = owner) }

        assertEquals("a media row's owner column is uploadedById", owner, url.queryParameter("uploadedBy"))
        // The half that matters. `createdBy` on /media is not an error the server reports — it is a
        // parameter it has never heard of, dropped, and answered with everyone's uploads.
        assertNull(
            "spelling media's owner filter `createdBy` is answered 200 with the whole repository",
            url.queryParameter("createdBy")
        )
    }

    @Test
    fun `a null owner sends no owner parameter, so every other caller is unaffected`() {
        // `WorkshopRepository` maps a blank id to null before it reaches here, and null must mean
        // "everyone's rows" — the form pickers and the completion matrix depend on it. If a default
        // ever leaked in, those screens would quietly narrow to one person.
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
        // `artisans` carries the plural workshop scope the completion matrix uses, and `media` the
        // linked-record pair the record detail uses. The owner filter was added beside them, so both
        // must still travel — a regression here would be a screen quietly widening, not erroring.
        val artisans = urlOf { it.artisans(workshopIds = "w1,w2", createdBy = owner) }
        assertEquals("w1,w2", artisans.queryParameter("workshopIds"))
        assertEquals(owner, artisans.queryParameter("createdBy"))

        val media = urlOf { it.media(linkedRecordType = "artisan", linkedRecordId = "a1", uploadedBy = owner) }
        assertEquals("artisan", media.queryParameter("linkedRecordType"))
        assertEquals("a1", media.queryParameter("linkedRecordId"))
        assertEquals(owner, media.queryParameter("uploadedBy"))
    }

    // ── The harness: a real Retrofit over a canned transport, so the URL is the real one ──────────

    /** The empty page every canned response carries; `items` is empty so no DTO has to be built. */
    private val emptyPage = """{"items":[],"total":0,"page":1,"pageSize":100,"pages":0}"""

    private var captured: HttpUrl? = null

    /**
     * Builds the service the app builds — same converter configuration as
     * [ApiClient.retrofit] — over a [Call.Factory] that answers every request from memory and
     * records the URL Retrofit produced. Nothing is mocked between the interface and the URL, which
     * is the whole point: the annotations are what is under test.
     */
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

    /**
     * Runs one suspend service call to completion on this thread.
     *
     * `kotlin.coroutines.startCoroutine` from the STDLIB rather than `runBlocking`, so this test adds
     * no `kotlinx-coroutines-test` dependency to `app/build.gradle.kts` — the same reason
     * [QuestionnaireListingTest] drives its walker this way. [CannedCall] answers inside `enqueue`,
     * so the call never actually suspends.
     */
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

    /** An OkHttp call that never opens a socket: it hands back [body] for whatever it is asked. */
    private class CannedCall(private val req: Request, private val body: String) : Call {
        override fun request(): Request = req
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
