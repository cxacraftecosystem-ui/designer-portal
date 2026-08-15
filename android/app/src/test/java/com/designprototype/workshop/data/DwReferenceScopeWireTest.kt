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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * "HOW WIDE IS THIS PICKER" IS ASKED OF THE SERVER, AND THE URL IS WHERE THAT IS TRUE OR NOT.
 *
 * ── THE DEFECT THIS PINS ──────────────────────────────────────────────────────────────────────────
 *
 * `WorkshopRepository.designWorkshopReferences` has always taken a `scope: String`, and always spent
 * every bit of it on the CACHE KEY. The Retrofit method it called declared four parameters — `id`,
 * `model`, `filterBy`, `search` — and no `scope`, so the narrowing never left the phone.
 *
 * That is not a parameter the server works out for itself, and deliberately so: the route declares
 * `scope: str = Query(REF_SCOPE_ALL, max_length=16)` and `reference_options` adds the workshop clause
 * only under `if scope == REF_SCOPE_WORKSHOP and spec.workshop_where and record.workshopId`
 * (backend/app/services/design_workshops.py), with a docstring saying that deriving it server-side was
 * rejected precisely so the form and the server cannot hold two ideas of how wide the net is. An
 * omitted parameter is therefore not "unspecified", it is the client saying ALL.
 *
 * The bundled registry has four WORKSHOP-scoped REF fields — `processStep.processRef` (Process),
 * `existingProduct.artisanRef` (Artisan), `existingProduct.productRef` and `prototype.productRef`
 * (ProductDocumentation); counted in `app/src/main/assets/design-workshop-schema.json`, which holds
 * exactly four `"refScope":"WORKSHOP"` occurrences. All four were answered with the first fifty rows
 * of the whole table, name-ascending, on every handset. A designer at stage 6 taps the artisan picker
 * and is offered strangers; `hydrateFromReference` writes the stranger's name, village and craft onto
 * the row; the report prints it.
 *
 * The browser has never had this bug — `StageReferenceField.tsx` passes `scope: field.refScope` and
 * `listStageReferences` forwards it — so the same field narrowed one way in a browser and another way
 * on the phone. That is the cross-surface parity class this repository has been bitten by repeatedly,
 * which is why the ALL case below is asserted just as hard as the WORKSHOP one: a client that sends
 * the scope only when it feels narrow is a client that has invented a default of its own.
 *
 * ── WHY IT IS PINNED AT THE URL AND NOT AT THE REPOSITORY ARGUMENT ────────────────────────────────
 *
 * The Kotlin argument was ALWAYS correct. `DwReferenceField` has passed `field.refScope` into the
 * repository since the picker was written, and the repository has always accepted it — a test that
 * asserted on that argument, or on the cache key it feeds, would have passed every single day this
 * defect existed. The only witness with any power is the serialised request, so this drives the real
 * Retrofit interface over a canned transport and reads the URL that came out. The harness is
 * [OwnerFilterWireTest]'s, for the same reason it exists there: the annotations are the thing under
 * test, so nothing may sit between the interface and the URL.
 */
class DwReferenceScopeWireTest {

    private val workshop = "cmsik2jg8000eh8xc1lcy661a"

    // ── The wire ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a WORKSHOP-scoped picker asks the server to narrow to this workshop`() {
        // The whole defect in one assertion. This read null while the picker showed a designer every
        // artisan in the repository and the response said `"scope": "ALL", "scopedToWorkshop": false`.
        val url = urlOf { it.designWorkshopReferences(id = workshop, model = "Artisan", scope = "WORKSHOP") }

        assertEquals("WORKSHOP", url.queryParameter("scope"))
        assertEquals("Artisan", url.queryParameter("model"))
    }

    @Test
    fun `an ALL-scoped picker says ALL out loud rather than relying on the server's default`() {
        // Sending nothing here would produce the same list, and that is exactly the trap: the two
        // surfaces would agree by coincidence today and disagree the day the route's default changes.
        // The registry's word is what travels, in both directions, so there is one authority for how
        // wide the net is and it is not this client.
        val url = urlOf { it.designWorkshopReferences(id = workshop, model = "DwParticipant", scope = "ALL") }

        assertEquals("ALL", url.queryParameter("scope"))
    }

    @Test
    fun `a registry that named no scope sends no scope, rather than an empty one`() {
        // `DwField.refScope` defaults to "" meaning "the server did not say", and the repository maps
        // that to null with `takeIf { it.isNotBlank() }`. It must reach the wire as an ABSENT
        // parameter and never as `scope=`: FastAPI parses the empty string as a value, and
        // `reference_options` answers a value outside REF_SCOPES with 422 "scope must be one of ALL,
        // WORKSHOP". The repository swallows that 422 by design (`runCatching { … }.getOrNull()
        // ?: return`), so the symptom would not be an error anyone sees — it would be a picker that
        // silently never refreshes from the server again, on every field of a deployment whose
        // registry omitted the attribute.
        val url = urlOf { it.designWorkshopReferences(id = workshop, model = "Artisan", scope = null) }

        assertNull("`scope=` is a 422, not a fallback", url.queryParameter("scope"))
        assertEquals("the rest of the request must be unaffected", "Artisan", url.queryParameter("model"))
    }

    @Test
    fun `the scope travels beside the cascade rather than displacing it`() {
        // `productRef` is the one field that is BOTH workshop-scoped and cascaded: this workshop's
        // products, narrowed to the artisan chosen on the row. Losing either half is a wrong list that
        // still looks like a list — the whole repository's products, or another workshop's.
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
        // Never sent by this client — the picker caches the whole list and searches it offline.
        assertNull(url.queryParameter("search"))
    }

    // ── The disk, which holds the wrong answer from before the wire was fixed ─────────────────────

    @Test
    fun `a workshop-owned cache written before the fix can no longer be reached`() {
        // Fixing the request does nothing for a phone that already has the un-narrowed list on disk:
        // `DwReferenceStore.cacheKey` files a WORKSHOP-scoped list under the workshop's own id, the
        // repository answers from that file BEFORE it tries the network, and with no signal it answers
        // from it forever. Nothing inside the file says which scope produced it, so it cannot be
        // repaired — only retired. `dwReferenceCacheOwner` stamps the generation of the answer onto
        // the owner segment, and this asserts the two keys are genuinely different files.
        val stale = DwReferenceStore.cacheKey("Artisan", "WORKSHOP", workshop, "")
        val fresh = DwReferenceStore.cacheKey("Artisan", "WORKSHOP", dwReferenceCacheOwner(workshop), "")

        assertNotEquals("the pre-fix file is still being read", stale, fresh)
    }

    @Test
    fun `retiring the poisoned entries leaves every ALL-scoped register on the device`() {
        // The blunt fix — wipe `dw-references/` — would strip an offline handset of the participant,
        // sketch, prototype and tool lists it holds legitimately, to fix a bug that touched none of
        // them. `cacheKey` ignores the workshop id when the scope is ALL, so the generation stamp
        // cannot reach those files. This is the assertion that keeps the blast radius honest.
        assertEquals(
            DwReferenceStore.cacheKey("DwParticipant", "ALL", workshop, ""),
            DwReferenceStore.cacheKey("DwParticipant", "ALL", dwReferenceCacheOwner(workshop), ""),
        )
    }

    @Test
    fun `a workshop that has never reached the server is not given a generation`() {
        // A local-only workshop has no id, is refused the fetch a few lines later, and shares the
        // store's "unnamed" bucket. Stamping a generation onto "" would invent a second spelling of it
        // and hide whatever ALL-scoped cache that device does hold.
        assertEquals("", dwReferenceCacheOwner(""))
    }

    // ── The harness: a real Retrofit over a canned transport, so the URL is the real one ──────────

    /** Enough of a reference payload to decode; the options are irrelevant to a URL assertion. */
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

    /**
     * Runs one suspend service call to completion on this thread, with `kotlin.coroutines`'
     * `startCoroutine` rather than `runBlocking` — so this test adds no `kotlinx-coroutines-test`
     * dependency to `app/build.gradle.kts`, for the reason its comment on JUnit 4 gives. [CannedCall]
     * answers inside `enqueue`, so the call never actually suspends.
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
