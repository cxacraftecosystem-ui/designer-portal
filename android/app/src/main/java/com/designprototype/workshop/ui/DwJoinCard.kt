package com.designprototype.workshop.ui

import android.content.Context
import android.os.SystemClock
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * THE JOIN CARD, ON THE HANDSET: minting one, showing one, and redeeming one.
 *
 * ── WHAT WAS MISSING, STATED PLAINLY BECAUSE IT IS THE WHOLE REASON THIS FILE EXISTS ──────────
 *
 * `backend/app/services/design_workshop_grants.py` shipped a complete join-card feature — mint,
 * list, revoke, redeem, a provisional foothold for the late-comer, a 30-day offline sync grace and
 * five columns of scan-time evidence — and **NOTHING ANYWHERE CALLED ANY OF IT.** No client minted a
 * card, no screen displayed one, and nothing posted to `POST /design-workshop-access/redemptions`.
 *
 * Worse than unreachable: the handset actively refused the artefact the server had begun to mint.
 * `DwWorkshopCodes.SUPPORTED_VERSIONS` was `setOf(1)`, so a genuine `DPW2:J:…` card scanned on a
 * current build was answered "This card was printed by a newer version of the app (code format 2)
 * than the one on this device. **Update the app to read it**" — and there was no newer app. Somebody
 * standing in a courtyard with a valid credential in their hand was told to go and find a build that
 * did not exist. The server's own refusal at the other door made the same promise ("Scan it on the
 * join screen instead. If your app has no such screen, update it").
 *
 * So this file is the client, and `DwWorkshopCodes.readWorkshopScan` is the door it is reached
 * through: the letter `J` is answered by the join parser BEFORE the version gate, which is what turns
 * "update the app" into "you are on the workshop".
 *
 * ── THE FOUR RULES IT TAKES FROM THE SERVER AND DOES NOT RE-DECIDE ────────────────────────────
 *
 *  1. **THE SENTENCE IS THE SERVER'S, SHOWN AS GIVEN.** Three of the redemption's answers are
 *     deliberately distinguishable (full, provisional, already a member) and three are deliberately
 *     identical (unknown card, revoked card, expired beyond the grace window all answer one uniform
 *     403). Rewriting either half here would either invent a distinction the server refuses to make —
 *     which is the enumeration oracle its header gives up informative answers to avoid — or lose one
 *     it makes on purpose, and the provisional sentence is the one somebody in a courtyard most needs.
 *  2. **A PROVISIONAL FOOTHOLD IS NOT MEMBERSHIP AND MUST NEVER BE PAINTED AS ONE.** See
 *     [DwJoinOutcome.Inducted.fullMember].
 *  3. **THE CARD IS NEVER TREATED AS ADMISSION OFFLINE.** There is no signature on a card and no key
 *     on this device: authority is 110 bits looked up in a UNIQUE index, so a card can only be
 *     adjudicated online. An offline scan is written down and sent later, and until the server answers
 *     nothing on this screen claims anything.
 *  4. **THE CLOCK IS EVIDENCE, NEVER ORDER.** `serverArrivedAt` decides who was first. What this file
 *     sends is a monotonic pair plus the boot it was taken in, so the server can estimate when a scan
 *     really happened WITHOUT trusting a number the phone's settings screen can change.
 *
 * ── AND THE FIVE EVIDENCE COLUMNS ARE FILLED IN, WHICH IS THE OTHER HALF OF THE FINDING ───────
 *
 * The migration added `scannedAtElapsedSec`, `syncedAtElapsedSec`, `bootId`, `clockJumpObserved` and
 * `serverArrivedAt`, `JoinCardRedeemIn` accepted all of them, and the only client path posted to a
 * route that takes none of them — so the clock evidence travelled as ENGLISH PROSE inside a `note`,
 * where no screen can sort or compare it. [dwRedeemJoinCard] sends the structured form.
 */

// --------------------------------------------------------------------------------------
// The wire
// --------------------------------------------------------------------------------------

/**
 * `POST /design-workshop-access/redemptions` — presenting a card.
 *
 * ⚠ **[code] IS A LIVE CREDENTIAL.** It is the one field on any wire in this app that is. Do not log
 * this object, do not put it in a crash report, and do not echo it into a message on screen — the
 * server's schema module refuses even to put a Pydantic `pattern` on the field for exactly this
 * reason ("a pattern failure is reported by Pydantic with the offending input inside it, which would
 * put whole join cards into 422 bodies, into access logs, and into whatever aggregates them").
 *
 * ── WHY THE ELAPSED PAIR IS SENT IN SECONDS WHEN THE QUEUE HOLDS MILLISECONDS ─────────────────
 *
 * Because the column is `Int` and the schema says `ge=0`. A fortnight in milliseconds is 1.2e9, which
 * fits, but a device that has been up for 25 days does not — and the precision is worthless anyway:
 * the server derives "about how long ago", and `dwSpanInWords` already rounds to hours and days for
 * the human-readable version of the same fact.
 *
 * ── AND WHY [syncedAtElapsedSec] IS SOMETIMES ABSENT ──────────────────────────────────────────
 *
 * **A monotonic pair is only a duration within one boot.** If the device restarted between the scan
 * and this call, the difference between the two readings is not a duration at all — it is a number
 * that would make the server's estimate silently wrong. So the pair is sent only when the boot
 * matches, and the scan reading is sent alone otherwise. Absent evidence is honest; a nonsense
 * subtraction is not, which is the same reasoning that keeps every one of these fields nullable
 * instead of defaulting to 0.
 */
@Serializable
data class DwJoinCardRedeemBody(
    val code: String,
    val scannedAt: String? = null,
    val scannedAtElapsedSec: Long? = null,
    val syncedAtElapsedSec: Long? = null,
    val bootId: String? = null,
    val clockJumpObserved: Boolean = false,
)

/**
 * The redemption's answer. 200 for all three outcomes, deliberately — see the route's own docstring:
 * distinguishing them by status code would turn the status line into the oracle the body is careful
 * not to be.
 */
@Serializable
data class DwJoinCardRedeemAck(
    val outcome: String = "",
    val reason: String? = null,
    val workshopId: String? = null,
    val detail: String? = null,
)

/** `POST /design-workshop-access/grants` — printing a card. */
@Serializable
data class DwJoinCardMintBody(
    val recordType: String = "DESIGN_WORKSHOP",
    val recordId: String,
    /**
     * ONE, AND THE DEFAULT IS THE SAFE ONE — the same value the column carries. **Anything else is
     * admin-only** and is refused by `mint_grant` with a 403 naming the screen that can do it. This
     * client never asks for more: the case it exists for is handing one card to the person standing
     * next to you, and a designer who needs a card for a group is told to ask an administrator.
     */
    val maxUses: Int? = 1,
    val daysValid: Int? = null,
    val label: String? = null,
)

/** Who minted a card, for the list. */
@Serializable
data class DwJoinCardIssuer(
    val id: String? = null,
    val name: String = "",
    val email: String = "",
)

/**
 * One card as the admin's list reads it.
 *
 * [code] IS PRESENT ON EXACTLY ONE RESPONSE — the mint — and is null on every other. That is the
 * server's `grant_payload` plus one field, and it is the only moment the secret exists anywhere but
 * on paper. A screen holding one must not persist it.
 */
@Serializable
data class DwJoinCardDto(
    val id: String = "",
    val recordType: String = "",
    val recordId: String = "",
    val secretLast4: String = "",
    val maxUses: Int? = null,
    val usesConsumed: Int = 0,
    val expiresAt: String? = null,
    val revokedAt: String? = null,
    val label: String? = null,
    val createdAt: String? = null,
    val issuedBy: DwJoinCardIssuer? = null,
    val code: String? = null,
)

/**
 * Every card printed for one workshop.
 *
 * [truncated] SAYS THE ANSWER WAS CUT, and a screen must say so when it is true: the route's own
 * docstring is explicit that "a card an admin cannot see is a card they cannot revoke".
 */
@Serializable
data class DwJoinCardList(
    val grants: List<DwJoinCardDto> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * The four join-card routes as a typed service.
 *
 * DECLARED HERE RATHER THAN IN `WorkshopRepositoryApi`, on the licence `ApiClient.retrofit`'s own
 * docstring gives and that `DwWorkshopJoinApi` next door already takes: a feature may declare its own
 * typed service without standing up a second HTTP stack. Going through `ApiClient` is what keeps
 * these calls inside the 504 retry that exists because CloudFront times this origin out, and inside
 * the auth interceptor that reads a FRESH token per request.
 */
interface DwJoinCardApi {
    @POST("design-workshop-access/redemptions")
    suspend fun redeem(@Body body: DwJoinCardRedeemBody): DwJoinCardRedeemAck

    @POST("design-workshop-access/grants")
    suspend fun mint(@Body body: DwJoinCardMintBody): DwJoinCardDto

    @GET("design-workshop-access/grants/{recordId}")
    suspend fun list(@Path("recordId") recordId: String): DwJoinCardList

    @POST("design-workshop-access/grants/{tokenId}/revoke")
    suspend fun revoke(@Path("tokenId") tokenId: String): DwJoinCardDto
}

// --------------------------------------------------------------------------------------
// Redeeming
// --------------------------------------------------------------------------------------

/**
 * Whether the server's outcome word means the person is actually on the workshop. PURE.
 *
 * `FULL` and `ALREADY_A_MEMBER` both mean yes — the second is somebody who scanned the card at the
 * wall when they were already in, and the server deliberately spends no seat on it. `PROVISIONAL`
 * means NO, and conflating the two is the single most damaging mistake a client can make here: the
 * whole point of the foothold is that the person keeps capturing while knowing they are not in.
 *
 * AN UNKNOWN WORD IS NOT MEMBERSHIP. A build one release behind a server that grew a fourth outcome
 * must fail towards "you are not in", because the cost of the two errors is not symmetric.
 */
internal fun dwJoinCardOutcomeIsMembership(outcome: String): Boolean =
    outcome == "FULL" || outcome == "ALREADY_A_MEMBER"

/**
 * How the redemption evidence is built from a queued row, at the moment of sending. PURE.
 *
 * SPLIT OUT FROM THE NETWORK CALL SO IT IS ASSERTED RATHER THAN TRUSTED — the boot rule below is the
 * kind of thing that is silently wrong for a year.
 *
 * @param sameBoot whether the device is still in the boot the scan was taken in. When it is not, the
 *   sync reading is omitted: see [DwJoinCardRedeemBody].
 */
internal fun dwJoinCardEvidence(
    row: DwPendingInduction,
    elapsedNowMs: Long,
    clockJumped: Boolean,
    sameBoot: Boolean,
): DwJoinCardRedeemBody = DwJoinCardRedeemBody(
    code = row.code,
    // The DEVICE's claim about when the scan happened, sent as written down. Untrusted, stored,
    // shown to an admin beside the server's own arrival time so a human can weigh the two.
    scannedAt = row.scannedAtDeviceUtc.takeIf { it.isNotBlank() && it != "unknown" },
    scannedAtElapsedSec = (row.scannedAtElapsedMs / 1000L).coerceAtLeast(0L),
    syncedAtElapsedSec = if (sameBoot) (elapsedNowMs / 1000L).coerceAtLeast(0L) else null,
    bootId = row.bootId.takeIf { it.isNotBlank() },
    clockJumpObserved = clockJumped,
)

/**
 * Present one card to the server. The whole redemption in one call.
 *
 * ── WHICH STATUSES MEAN WHAT, AND WHY ONLY TWO ARE SHOWN ──────────────────────────────────────
 *
 * **200** — one of three outcomes, and the body says which. Shown as given.
 *
 * **403** — the uniform refusal. Unknown card, cancelled card, or a card that expired more than the
 * sync grace ago; the sentence names no workshop and does not say which of the three happened. It is
 * TERMINAL, so the queue row is dropped: re-sending a card the server has refused is how a queue
 * becomes an infinite retry, and the sentence already tells the person what to do instead.
 *
 * **422** — about the BODY: the card is damaged, or it is a record tag rather than a join card. Also
 * terminal, and also safe to show, because none of it depends on which records exist.
 *
 * **Everything else, including no answer at all** — the queue. A 500, a 401 or a dead network is not
 * the card's fault and re-scanning would not help, so the row stays and the flush tries again. This
 * is also the branch that makes the server's "give the seat back and re-raise" behaviour work: a
 * grant that failed mid-transaction returns the seat and answers 500, and the retry is what turns
 * that into an induction rather than a dead card.
 */
suspend fun dwRedeemJoinCard(context: Context, row: DwPendingInduction): DwJoinOutcome =
    withContext(Dispatchers.IO) {
        if (!ConnectivityObserver.isOnline(context)) {
            return@withContext DwJoinOutcome.Queued(dwJoinQueuedMessage())
        }
        val api = runCatching {
            ApiClient.retrofit(TokenStore(context)).create(DwJoinCardApi::class.java)
        }.getOrNull() ?: return@withContext DwJoinOutcome.Queued(dwJoinQueuedMessage())

        val elapsedNow = SystemClock.elapsedRealtime()
        // THE BOOT IS COMPARED BY IDENTITY AND NOT BY ARITHMETIC. An earlier draft asked whether the
        // monotonic clock had gone backwards since the scan, and that is only a REBOOT DETECTOR at
        // the moment of the reboot: a scan taken ten seconds into a boot, followed by a restart and a
        // minute of uptime, reads as "still the same boot" and would have shipped a subtraction
        // across two boots as a duration. The persisted mark answers exactly, because a new boot is
        // exactly what mints a new id — see [DwBootMark].
        val mark = DwBootMark.observe(context, elapsedNow, System.currentTimeMillis()).first
        val sameBoot = row.bootId.isNotBlank() && row.bootId == mark.bootId
        // ONLY MEANINGFUL WITHIN ONE BOOT. Across boots the row's boot-instant estimate belongs to a
        // run that has ended, so comparing it would report a clock jump for every restart and make
        // the flag noise. The reboot is already reported, by the absent sync reading.
        val clockJumped = sameBoot && DwBootMark.clockMovedSince(context, row)

        try {
            val ack = api.redeem(dwJoinCardEvidence(row, elapsedNow, clockJumped, sameBoot))
            DwJoinOutcome.Inducted(
                message = ack.detail?.takeIf { it.isNotBlank() }
                    ?: dwJoinCardFallbackDetail(ack.outcome),
                fullMember = dwJoinCardOutcomeIsMembership(ack.outcome),
            )
        } catch (error: HttpException) {
            when (error.code()) {
                403, 422 -> DwJoinOutcome.Refused(
                    dwJoinCardBodyDetail(error) ?: dwJoinCardFallbackRefusal()
                )
                else -> DwJoinOutcome.Queued(dwJoinQueuedMessage())
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            DwJoinOutcome.Queued(dwJoinQueuedMessage())
        }
    }

/**
 * The queue's entry point for a card that has been waiting. Named separately from [dwRedeemJoinCard]
 * only so that `DwInductionQueue.flush` reads as a dispatch and not as a special case.
 */
suspend fun dwRedeemQueuedJoinCard(context: Context, row: DwPendingInduction): DwJoinOutcome =
    dwRedeemJoinCard(context, row)

/**
 * Write the scan down, then present it. The whole join-card path in one call.
 *
 * WRITTEN DOWN FIRST, ALWAYS, AND BEFORE THE NETWORK IS TOUCHED — the same rule
 * `dwJoinDesignWorkshop` keeps and for the same reason: the failure that matters is the process being
 * killed mid-request in a courtyard, where there is no `catch` block to reach the queue.
 *
 * A TERMINAL ANSWER CLEARS THE ROW, which for a card means the credential stops being on disk the
 * moment it stops being needed.
 */
suspend fun dwScanJoinCard(context: Context, workshopId: String, code: String): DwJoinOutcome {
    val pending = DwInductionQueue.record(context, workshopId, code, kind = DW_INDUCTION_JOIN)
    val outcome = dwRedeemJoinCard(context, pending)
    if (outcome !is DwJoinOutcome.Queued) DwInductionQueue.clear(context, pending.queueKey)
    return outcome
}

/**
 * What to say when the server answered 200 but a proxy ate the body.
 *
 * ONE SENTENCE PER OUTCOME AND NOT ONE FOR ALL THREE, because the difference between "you are on the
 * workshop" and "you are not on it yet but nothing is lost" is the entire content of the answer. They
 * are deliberately plainer than the server's, so nobody mistakes a fallback for the real wording, and
 * the unknown case claims nothing at all.
 */
private fun dwJoinCardFallbackDetail(outcome: String): String = when (outcome) {
    "FULL" ->
        "You are on this workshop. The card has been used up, so it will not let anybody else in."
    "ALREADY_A_MEMBER" ->
        "You are already on this workshop, so the card was not used up. Somebody else can still use it."
    "PROVISIONAL" ->
        "You are not on the workshop yet, but nothing you record is lost — an administrator can see " +
            "that you scanned the card, and once they confirm you everything you have recorded is " +
            "already in place. Until then you will not see anybody else's stages."
    else ->
        "The card was sent and the server answered, but this version of the app could not read what " +
            "it said. Check the workshop list, and ask an administrator if you cannot see it."
}

/** Shown only if a 403 or 422 arrives with no readable body. It claims nothing about any workshop. */
private fun dwJoinCardFallbackRefusal(): String =
    "That join card cannot be used. It may have been cancelled, or it may have run out of date. Ask " +
        "whoever runs the workshop for a fresh card, or ask an administrator to add you from the " +
        "workshop's viewers screen. Everything you have already recorded on this device stays where " +
        "it is."

/**
 * The `detail` out of a FastAPI error body, or null.
 *
 * Read out of the response rather than restated here, because the sentence is the service module's
 * and the whole discipline is that this client does not write a second version of it. Reading the
 * body consumes it, so this is called once and its answer used.
 */
private fun dwJoinCardBodyDetail(error: HttpException): String? = runCatching {
    val raw = error.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }
    val element = raw?.let { ApiClient.json.parseToJsonElement(it) }
    val detail = (element as? JsonObject)?.get("detail")
    // FastAPI answers `detail` as a string for an HTTPException and as a LIST of objects for a
    // validation error. Only the first is a sentence written for a person; the second is a schema
    // complaint, and showing it to a designer in a courtyard is worse than the generic line.
    (detail as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}.getOrNull()

// --------------------------------------------------------------------------------------
// Minting, listing, cancelling
// --------------------------------------------------------------------------------------

/** What came of asking the server for a card, or for the cards that exist. */
sealed interface DwJoinCardAction {
    /**
     * A card was minted. [card] carries [DwJoinCardDto.code] and **that is the only moment the
     * secret exists anywhere but on paper** — hold it in memory for as long as the symbol is on
     * screen and never write it down.
     */
    data class Minted(val card: DwJoinCardDto) : DwJoinCardAction

    /** The cards printed for this workshop. [truncated] must be said out loud when true. */
    data class Listed(val cards: List<DwJoinCardDto>, val truncated: Boolean) : DwJoinCardAction

    /** One card cancelled. Idempotent, and it removes nobody it has already let in. */
    data class Revoked(val card: DwJoinCardDto) : DwJoinCardAction

    /**
     * The server said no, and [message] is ITS sentence.
     *
     * THE 404 IS NOT SPECIAL-CASED, deliberately. `_workshop_for_issuer_or_404` answers "Record not
     * found" both for a workshop that does not exist and for one this account may not print cards
     * for, precisely so that minting is not an existence oracle — and a client that guessed which it
     * was would put the oracle back. The 403 (a designer asking for a multi-use card) and the 409
     * (three unused cards already outstanding) both name the remedy in their own words.
     */
    data class Refused(val message: String) : DwJoinCardAction

    /** The server was never reached. A card cannot be minted offline: there is nothing to mint with. */
    data class Offline(val message: String) : DwJoinCardAction
}

/** The one sentence for "the server was not reachable", said the same way by all three actions. */
private fun dwJoinCardOfflineMessage(): String =
    "There is no connection, so a join card cannot be printed or cancelled right now. A card has to " +
        "be made by the server — it is a key, not something this device can invent — so try again " +
        "when there is signal."

/**
 * Print one single-use join card for this workshop.
 *
 * NOT ADMIN-GATED HERE, AND THAT IS THE SERVER'S DESIGN RATHER THAN AN OVERSIGHT: the courtyard case
 * the whole feature exists for is somebody already on the workshop handing a card to the person next
 * to them, with no administrator within two districts. What is admin-only is a card for more than one
 * person, refused inside `mint_grant` where the rule can see both the actor's role and the record. So
 * this client asks for `maxUses = 1` and never offers anything else.
 */
suspend fun dwMintJoinCard(
    context: Context,
    workshopId: String,
    label: String? = null,
): DwJoinCardAction = dwJoinCardCall(context) { api ->
    DwJoinCardAction.Minted(
        api.mint(DwJoinCardMintBody(recordId = workshopId, maxUses = 1, label = label))
    )
}

/** Every card printed for this workshop, newest first. */
suspend fun dwListJoinCards(context: Context, workshopId: String): DwJoinCardAction =
    dwJoinCardCall(context) { api ->
        val answer = api.list(workshopId)
        DwJoinCardAction.Listed(answer.grants, answer.truncated)
    }

/** Cancel one card. It stops admitting anybody further and removes nobody it already let in. */
suspend fun dwRevokeJoinCard(context: Context, tokenId: String): DwJoinCardAction =
    dwJoinCardCall(context) { api -> DwJoinCardAction.Revoked(api.revoke(tokenId)) }

/**
 * ONE try/catch for all three card actions rather than one each.
 *
 * A per-call catch is how one of them comes to explain a dead network as a refusal — the same
 * argument `lookUpRecordCode` makes about its own single catch. An [HttpException] means the server
 * answered and its sentence is shown; anything else means it was never reached, and those are
 * completely different next actions.
 */
private suspend fun dwJoinCardCall(
    context: Context,
    call: suspend (DwJoinCardApi) -> DwJoinCardAction,
): DwJoinCardAction = withContext(Dispatchers.IO) {
    if (!ConnectivityObserver.isOnline(context)) {
        return@withContext DwJoinCardAction.Offline(dwJoinCardOfflineMessage())
    }
    val api = runCatching {
        ApiClient.retrofit(TokenStore(context)).create(DwJoinCardApi::class.java)
    }.getOrNull() ?: return@withContext DwJoinCardAction.Offline(dwJoinCardOfflineMessage())
    try {
        call(api)
    } catch (error: HttpException) {
        DwJoinCardAction.Refused(
            dwJoinCardBodyDetail(error)
                ?: "That could not be done. Ask an administrator to print the card, or to add the " +
                "person from the workshop's viewers screen."
        )
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        DwJoinCardAction.Offline(dwJoinCardOfflineMessage())
    }
}
