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

@Serializable
data class DwJoinCardRedeemBody(
    val code: String,
    val scannedAt: String? = null,
    val scannedAtElapsedSec: Long? = null,
    val syncedAtElapsedSec: Long? = null,
    val bootId: String? = null,
    val clockJumpObserved: Boolean = false,
)

@Serializable
data class DwJoinCardRedeemAck(
    val outcome: String = "",
    val reason: String? = null,
    val workshopId: String? = null,
    val detail: String? = null,
)

@Serializable
data class DwJoinCardMintBody(
    val recordType: String = "DESIGN_WORKSHOP",
    val recordId: String,
    val maxUses: Int? = 1,
    val daysValid: Int? = null,
    val label: String? = null,
)

@Serializable
data class DwJoinCardIssuer(
    val id: String? = null,
    val name: String = "",
    val email: String = "",
)

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

@Serializable
data class DwJoinCardList(
    val grants: List<DwJoinCardDto> = emptyList(),
    val truncated: Boolean = false,
)

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

internal fun dwJoinCardOutcomeIsMembership(outcome: String): Boolean =
    outcome == "FULL" || outcome == "ALREADY_A_MEMBER"

internal fun dwJoinCardEvidence(
    row: DwPendingInduction,
    elapsedNowMs: Long,
    clockJumped: Boolean,
    sameBoot: Boolean,
): DwJoinCardRedeemBody = DwJoinCardRedeemBody(
    code = row.code,
    scannedAt = row.scannedAtDeviceUtc.takeIf { it.isNotBlank() && it != "unknown" },
    scannedAtElapsedSec = (row.scannedAtElapsedMs / 1000L).coerceAtLeast(0L),
    syncedAtElapsedSec = if (sameBoot) (elapsedNowMs / 1000L).coerceAtLeast(0L) else null,
    bootId = row.bootId.takeIf { it.isNotBlank() },
    clockJumpObserved = clockJumped,
)

suspend fun dwRedeemJoinCard(context: Context, row: DwPendingInduction): DwJoinOutcome =
    withContext(Dispatchers.IO) {
        if (!ConnectivityObserver.isOnline(context)) {
            return@withContext DwJoinOutcome.Queued(dwJoinQueuedMessage())
        }
        val api = runCatching {
            ApiClient.retrofit(TokenStore(context)).create(DwJoinCardApi::class.java)
        }.getOrNull() ?: return@withContext DwJoinOutcome.Queued(dwJoinQueuedMessage())

        val elapsedNow = SystemClock.elapsedRealtime()
        val mark = DwBootMark.observe(context, elapsedNow, System.currentTimeMillis()).first
        val sameBoot = row.bootId.isNotBlank() && row.bootId == mark.bootId
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

suspend fun dwRedeemQueuedJoinCard(context: Context, row: DwPendingInduction): DwJoinOutcome =
    dwRedeemJoinCard(context, row)

suspend fun dwScanJoinCard(context: Context, workshopId: String, code: String): DwJoinOutcome {
    val pending = DwInductionQueue.record(context, workshopId, code, kind = DW_INDUCTION_JOIN)
    val outcome = dwRedeemJoinCard(context, pending)
    if (outcome !is DwJoinOutcome.Queued) DwInductionQueue.clear(context, pending.queueKey)
    return outcome
}

private fun dwJoinCardFallbackDetail(outcome: String): String = when (outcome) {
    "FULL" ->
        "You've joined. This card is now used up."
    "ALREADY_A_MEMBER" ->
        "You're already on this workshop; the card wasn't used."
    "PROVISIONAL" ->
        "Pending admin confirmation. Your work is kept and will appear once confirmed."
    else ->
        "Card sent. Check your workshop list or ask an admin."
}

private fun dwJoinCardFallbackRefusal(): String =
    "This card can't be used (cancelled or expired). Ask for a new one or ask an admin to add you."

private fun dwJoinCardBodyDetail(error: HttpException): String? = runCatching {
    val raw = error.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }
    val element = raw?.let { ApiClient.json.parseToJsonElement(it) }
    val detail = (element as? JsonObject)?.get("detail")
    (detail as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}.getOrNull()

sealed interface DwJoinCardAction {
    data class Minted(val card: DwJoinCardDto) : DwJoinCardAction

    data class Listed(val cards: List<DwJoinCardDto>, val truncated: Boolean) : DwJoinCardAction

    data class Revoked(val card: DwJoinCardDto) : DwJoinCardAction

    data class Refused(val message: String) : DwJoinCardAction

    data class Offline(val message: String) : DwJoinCardAction
}

private fun dwJoinCardOfflineMessage(): String =
    "Join cards need a connection."

suspend fun dwMintJoinCard(
    context: Context,
    workshopId: String,
    label: String? = null,
): DwJoinCardAction = dwJoinCardCall(context) { api ->
    DwJoinCardAction.Minted(
        api.mint(DwJoinCardMintBody(recordId = workshopId, maxUses = 1, label = label))
    )
}

suspend fun dwListJoinCards(context: Context, workshopId: String): DwJoinCardAction =
    dwJoinCardCall(context) { api ->
        val answer = api.list(workshopId)
        DwJoinCardAction.Listed(answer.grants, answer.truncated)
    }

suspend fun dwRevokeJoinCard(context: Context, tokenId: String): DwJoinCardAction =
    dwJoinCardCall(context) { api -> DwJoinCardAction.Revoked(api.revoke(tokenId)) }

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
                ?: "Couldn't do that. Ask an admin to print the card or add the person."
        )
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        DwJoinCardAction.Offline(dwJoinCardOfflineMessage())
    }
}
