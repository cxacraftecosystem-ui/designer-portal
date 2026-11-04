package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun pendingCardsIn(element: JsonElement): Set<String> = when (element) {
    is JsonPrimitive -> if (element.isString && ArtisanCardCode.isPending(element.content)) setOf(element.content) else emptySet()
    is JsonArray -> element.flatMapTo(LinkedHashSet()) { pendingCardsIn(it) }
    is JsonObject -> element.values.flatMapTo(LinkedHashSet()) { pendingCardsIn(it) }
}

fun substitutePendingCards(element: JsonElement, ids: Map<String, String>): JsonElement = when (element) {
    is JsonPrimitive -> if (element.isString) ids[element.content]?.let(::JsonPrimitive) ?: element else element
    is JsonArray -> JsonArray(element.map { substitutePendingCards(it, ids) })
    is JsonObject -> JsonObject(element.mapValues { (_, value) -> substitutePendingCards(value, ids) })
}

fun pendingCardsInPayload(payloadJson: String): Set<String> =
    runCatching { pendingCardsIn(Json.parseToJsonElement(payloadJson)) }.getOrDefault(emptySet())

fun substitutePendingCardsInPayload(payloadJson: String, ids: Map<String, String>): String =
    substitutePendingCards(Json.parseToJsonElement(payloadJson), ids).toString()

fun pendingCardsInDraft(draft: WorkshopDraft): Set<String> =
    draft.stages.values.flatMapTo(LinkedHashSet()) { stage ->
        pendingCardsIn(JsonObject(stage.values)) + pendingCardsIn(JsonObject(stage.custom))
    }

fun cardWaitingToSync(
    card: ArtisanCardCode.Parsed,
    payloads: List<String>,
    drafts: List<WorkshopDraft> = emptyList(),
): Boolean {
    val value = ArtisanCardCode.pendingValue(card)
    return payloads.any { value in pendingCardsInPayload(it) } || drafts.any { value in pendingCardsInDraft(it) }
}

sealed interface PendingCardOutcome {
    data class Resolved(val ids: Map<String, String>) : PendingCardOutcome
    data object Offline : PendingCardOutcome
    data class Held(val message: String) : PendingCardOutcome
}

fun heldCardSentence(pendingValue: String, reason: String): String =
    "Card ${ArtisanCardCode.pendingInput(pendingValue)}: $reason"

suspend fun resolvePendingCards(
    pending: Set<String>,
    resolve: suspend (input: String) -> CardResolution,
): PendingCardOutcome {
    val ids = LinkedHashMap<String, String>()
    for (value in pending) {
        when (val answer = resolve(ArtisanCardCode.pendingInput(value))) {
            is CardResolution.Found -> ids[value] = answer.artisanId
            is CardResolution.Replaced ->
                if (answer.artisanId.isNotBlank()) ids[value] = answer.artisanId
                else return PendingCardOutcome.Held(heldCardSentence(value, CARD_REPLACED))
            is CardResolution.Pending -> return PendingCardOutcome.Offline
            CardResolution.Remote -> return PendingCardOutcome.Held(heldCardSentence(value, CARD_REMOTE))
            is CardResolution.Refused -> return PendingCardOutcome.Held(heldCardSentence(value, answer.message))
        }
    }
    return PendingCardOutcome.Resolved(ids)
}
