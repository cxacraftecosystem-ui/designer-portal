package com.designprototype.workshop.data

import android.content.Context
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The `/usage` service: the consent a person answers at sign-in, their own record, and the admin
 * aggregate.
 *
 * ── WHY A SERVICE OF ITS OWN RATHER THAN METHODS ON `WorkshopRepositoryApi` ───────────────────
 *
 * The same reason [DwReportHistoryApi] gives, plus one this feature adds. Built from
 * [ApiClient.retrofit], which exists precisely "so a feature can declare its OWN typed service
 * without standing up a second HTTP stack beside this one" — so these calls keep the CloudFront 504
 * retry (every read here is a GET and is safely retriable), the bearer header, the field timeouts
 * and the lenient decoder. A hand-rolled OkHttp client here would silently opt out of all four.
 *
 * THE ONE THIS FEATURE ADDS: [consentNotice] is called with **no token at all**, from the sign-in
 * screen, before any account exists. `ApiClient.retrofit`'s auth interceptor already handles that —
 * it omits the header when the store is empty rather than sending "Bearer null" — and that is the
 * behaviour this route needs. It is the only ungated route in the module, and it has to be, because
 * a gate on it would mean the only way to read what you are agreeing to is to agree first.
 *
 * ── AND WHY THE SERVICE IS A PROCESS-WIDE SINGLETON ──────────────────────────────────────────
 *
 * [UsageClient] holds ONE instance for the life of the process. `ApiClient.retrofit` builds a new
 * `OkHttpClient` on every call, so a screen that rebuilt the service per retry would leave a
 * connection pool and a dispatcher behind for each one — the failure `WorkshopRepository`'s own
 * `reportHistoryApi` note names, met here by a screen that a designer with no signal will
 * legitimately retry a dozen times. One instance is safe across sign-out and sign-in because the
 * token is read per request by the interceptor and never captured.
 *
 * ── WHAT IS DELIBERATELY NOT DECLARED HERE ───────────────────────────────────────────────────
 *
 * `GET /usage/accounts/{user_id}/trail` — one named colleague's request-by-request trail. It exists
 * on the server, at MASTER ADMIN and only where that person's own consent is GRANTED, and this
 * handset does not bind it. That is a decision and not an omission: the route's own docstring
 * records that each read is written to the server log naming the reader, the subject and the window,
 * and that **there is no durable audit table yet**. A surface that reads a named colleague's
 * afternoon minute by minute should be reachable from one console with one owner, not from a phone
 * that travels in a pocket to a village; and adding it here would mean the most sensitive read in
 * the product had two front doors before it had one audit row. If it is ever wanted on the handset
 * it is a new screen with its own written argument, not a method added to this list.
 */
interface UsageApi {

    /**
     * The whole text a person is agreeing to, versioned. **UNGATED — no token needed.**
     *
     * Read on the sign-in screen before any credential is offered. Carries no figure about anybody:
     * every field is the published method, computed from the collection policy actually in force,
     * which is what makes serving it to an anonymous caller safe.
     */
    @GET("usage/consent/notice")
    suspend fun consentNotice(): UsageNoticeDto

    /** This account's own answer, its history, and whether it must be asked again. Signed in only —
     *  and nothing more, because reading your own answer about your own data needs permission from
     *  nobody. */
    @GET("usage/consent")
    suspend fun myConsent(): UsageConsentStateDto

    /**
     * Record this account's own answer: GRANTED or REFUSED, with the circumstance it was given in.
     *
     * There is no `userId` in the path or the body — the account comes from the bearer token. A
     * consent an administrator can enter on a colleague's behalf is not a consent, so no
     * admin-facing spelling of this exists and this client must never grow one.
     */
    @POST("usage/consent")
    suspend fun recordConsent(@Body body: UsageConsentBody): UsageConsentStateDto

    /**
     * Take it back: stop recording this account and delete what has already been recorded.
     *
     * **IT COSTS THE PERSON NOTHING AND THE SCREEN MUST NOT SUGGEST OTHERWISE** — no sign-out, no
     * lost capability, no re-consent demanded on the next request. That asymmetry is the whole
     * defence of the turnstile at the door: an agreement that cannot be taken back without losing
     * access is not an agreement. A withdrawal flow on this client that logged somebody out would
     * make the sign-in gate indefensible, whatever the copy beside it said.
     */
    @POST("usage/consent/withdraw")
    suspend fun withdrawConsent(@Body body: UsageWithdrawBody): UsageConsentStateDto

    /** What this platform recorded about the person asking, aggregated by screen. The caller's own,
     *  and only ever the caller's own. */
    @GET("usage/me")
    suspend fun myUsage(
        @Query("from") from: String,
        @Query("to") to: String,
    ): UsageMineDto

    /**
     * The caller's own trail, request by request, newest first.
     *
     * [limit] is capped at `maxRows` (200) by a `Query` validator on the server — over it is a 422,
     * not a silent truncation. The screen asks for a page and prints what came back beside the cap
     * rather than implying it has everything.
     */
    @GET("usage/me/trail")
    suspend fun myTrail(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): UsageTrailDto

    /**
     * Per-screen aggregates across every account. `require_usage_reader` — Admin and above.
     *
     * Paged from the MOUNTED ROUTE TABLE and not from the data, because "every route in the window"
     * is a whole-table scan on the highest-write table in the schema. A row whose figures come back
     * null is withheld, not empty.
     */
    @GET("usage/routes")
    suspend fun usageRoutes(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
    ): UsageRoutesPageDto

    /** How this record was made: what is collected, what is not, the caps, the losses and the consent
     *  posture. Rendered ABOVE every figure — a number with no stated method is a number nobody can
     *  check. Admin and above. */
    @GET("usage/collection")
    suspend fun usageCollection(): UsageCollectionDto
}

/**
 * The one [UsageApi] this process uses.
 *
 * `@Volatile` plus double-checked locking rather than `by lazy` on an object: the notice is fetched
 * from the sign-in screen while the rest of the app is still cold, and two composables racing to
 * build two Retrofit instances would each build an `OkHttpClient` — see the header above for why one
 * is the number that matters here.
 */
object UsageClient {
    @Volatile
    private var service: UsageApi? = null

    fun of(context: Context): UsageApi {
        service?.let { return it }
        return synchronized(this) {
            service ?: ApiClient.retrofit(TokenStore(context.applicationContext))
                .create(UsageApi::class.java)
                .also { service = it }
        }
    }
}
