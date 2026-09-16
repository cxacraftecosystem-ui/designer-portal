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
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * THE INTERVIEW FORM'S ARTISAN ROSTER IS NARROWED ON THE WIRE, AND THE URL IS WHERE THAT IS TRUE.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE DEFECT THIS PINS, WHICH SHIPPED ON THIS CLIENT AND WAS REPORTED MORE THAN ONCE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `QuestionnaireForm` offered EVERY ARTISAN IN THE DEPLOYMENT under whichever workshop its own
 * picker was showing. The roster came from `loadArtisanRegister(context, repository)`, whose whole
 * fetch was a bare `repository.artisans()` — the shared, ALL-scoped, offline-cached register that
 * five other record forms read — and nothing re-asked when the workshop box moved.
 *
 * `shared/questionnaire-form-contract.json` carried it as the `handset-artisan-register-is-unscoped`
 * row of `openDrifts` until 2026-09-17. The browser closed the same defect in
 * `frontend/components/questionnaires/interviewArtisans.ts`; this file is the handset half of it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT IS PINNED AT THE WIRE AND NOT AT THE SCREEN
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The fix is a query parameter, so the only thing that can prove it is a URL. A screen-level test
 * would pass against a form that computed a perfect scope and handed it to a repository that
 * dropped it — and it would ALSO have passed against the shipped defect, because the picker's
 * options are a `List<ArtisanDto>` either way and a wrong list has the same type as a right one.
 * That is the shape this class of bug hides in: nothing is missing, nothing errors, and the control
 * simply answers a wider question than the one on screen.
 *
 * [fetchInterviewArtisans] is the production call path, not a re-spelling of it: the form reaches it
 * through `WorkshopRepository.interviewArtisans`, which is one delegating line, and this test hands
 * it the same [WorkshopRepositoryApi] Retrofit builds for the app over a transport that answers from
 * memory. Nothing is mocked between the scope and the query string, which is the point — the
 * `@Query` annotations are what is under test.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT MADE THIS RED BEFORE THE FIX
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `WorkshopRepositoryApi.artisans()` DECLARED NO `designWorkshopId` PARAMETER AT ALL, though
 * `backend/app/api/routes/artisans.py::list_artisans` has accepted one the entire time. So the
 * design-routed half of this test did not merely fail — it did not compile, which is the honest
 * reading of "this client cannot express the filter". The route is unchanged by this work; nothing
 * was added to the server.
 */
class InterviewArtisanScopeWireTest {

    private val workshop = "cmw0rk5h0p00000000000000"
    private val designWorkshop = "cmd3s1gnw0rksh0p00000000"

    // ── The rule: which parameter, decided by the routing the type box already exposes ───────────

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
        // THE PLURAL AND NOT THE SINGULAR, and they are not the same filter on this route. The
        // singular narrows on the artisan's own column OR the `WorkshopArtisan` join; the plural goes
        // through `artisan_workshop_clause`, which also counts having SAT IN an interview taken at
        // the workshop. `list_artisans` ANDs everything it is given, so sending both would silently
        // intersect down to the singular's narrower answer — an emptier picker that looks careful.
        // `ConsolidatedQuestionnaireScreen` already sends the plural, so this is also the spelling
        // that keeps the two handset surfaces answering one question once.
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
        // `RecordWorkshopLink` mounts BOTH halves at once and each computes its own default while the
        // other is on screen, so a form routed at a design workshop is routinely holding a perfectly
        // good ordinary `workshopId` as well. That is the state this assertion is about: the ROUTING
        // decides, never "whatever is non-blank".
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
        // The type box routes at a design workshop and the design half has not answered yet, while the
        // ordinary half is sitting on its own default. Sending that default would scope the roster by
        // a workshop this record is NOT being filed under — the reported defect with the sign flipped,
        // and harder to see, because the list would look narrowed and plausible.
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
        // R5 — "Not linked to a workshop" is an answer, and an interview carries a NULLABLE workshop.
        // An empty picker under "choose a workshop first" would make that record unfileable, and the
        // researcher's only way out would be to attach the interview to a workshop it was not taken
        // at, corrupting the very scoping this change exists to establish. `resolve_workshop_ids`
        // already reads an absent scope as "every workshop"; inventing a second meaning for it on one
        // screen of one client is how two surfaces come to answer one question differently.
        val url = urlFor(interviewArtisanScope(routesToDesignWorkshop = false, workshopId = "", designWorkshopId = ""))

        assertNull(url.queryParameter("workshopIds"))
        assertNull(url.queryParameter("designWorkshopId"))
        assertNull(url.queryParameter("workshopId"))
    }

    @Test
    fun `a blank id is not a scope, on either half`() {
        // `""` is this repository's spelling of "not linked to a workshop" and it is what both picker
        // states hold before anybody answers. Sent as a blank parameter it would be ONE BLANK ID,
        // which matches nothing — an empty picker over a full corpus, which `craft_workshop_clause`'s
        // own docstring names as this repository's most repeated bug class.
        val ordinary = interviewArtisanScope(routesToDesignWorkshop = false, workshopId = "   ", designWorkshopId = null)
        val design = interviewArtisanScope(routesToDesignWorkshop = true, workshopId = null, designWorkshopId = "  ")

        assertNull(ordinary.workshopIds)
        assertNull(design.designWorkshopId)
        assertTrue("a blank id is the unnarrowed state, not a narrowing", !ordinary.narrowed && !design.narrowed)
    }

    @Test
    fun `the type key never reaches the wire`() {
        // The type box is a ROUTER and is stored on nothing: no foreign key points at
        // `WorkshopTypeOption` from any record table, and `GET /artisans` has no parameter for it. A
        // key sent "for completeness" would be a query parameter FastAPI silently drops — which reads
        // as a filter that works, right up until somebody relies on it.
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
        // The form keys its load effect on this string. If two different workshops produced one key
        // the roster would never be re-read, and the picker would go on offering the PREVIOUS
        // workshop's people under the new workshop's name — the defect this file is about, surviving
        // the fix that was supposed to close it.
        val a = interviewArtisanScope(routesToDesignWorkshop = false, workshopId = workshop, designWorkshopId = null)
        val b = interviewArtisanScope(routesToDesignWorkshop = true, workshopId = null, designWorkshopId = designWorkshop)
        val none = interviewArtisanScope(routesToDesignWorkshop = false, workshopId = "", designWorkshopId = "")

        assertEquals(3, setOf(a.key, b.key, none.key).size)
        // And an ordinary workshop and a design workshop that somehow shared an id are still two
        // different scopes, because the key carries WHICH TABLE as well as which row.
        val sameId = interviewArtisanScope(routesToDesignWorkshop = true, workshopId = null, designWorkshopId = workshop)
        assertTrue(a.key != sameId.key)
    }

    // ── The harness: a real Retrofit over a canned transport, so the URL is the real one ─────────
    //
    // Lifted from `OwnerFilterWireTest`, which pins the owner filter on these same routes the same
    // way. It is duplicated rather than shared because the two files pin DIFFERENT parameters and a
    // shared harness would be a third file to keep in step for no assertion either one gains; if a
    // third wire test appears, that is the moment to lift it into a fixture.

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

    /** The URL the form's own load produces for one scope — [fetchInterviewArtisans] and nothing else. */
    private fun urlFor(scope: InterviewArtisanScope): HttpUrl {
        captured = null
        val service = api()
        drive { fetchInterviewArtisans(service, scope) }
        return checkNotNull(captured) { "the roster load never issued a request" }
    }

    /**
     * Runs one suspend call to completion on this thread.
     *
     * `kotlin.coroutines.startCoroutine` from the STDLIB rather than `runBlocking`, so this test adds
     * no `kotlinx-coroutines-test` dependency to `app/build.gradle.kts` — the same reason
     * `OwnerFilterWireTest` drives its calls this way. [CannedCall] answers inside `enqueue`, so the
     * call never actually suspends.
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
