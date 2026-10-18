package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "Links on sync": an artisan card scanned into a stage's artisan box with no signal and no match in
 * the cached register leaves `card:<shortCode>` in the row (`ArtisanCardCode.pendingValue`).
 * The workshop form's artisan picker keeps the same value in a queued entry's body. Before the
 * stage or the entry is sent, `WorkshopSync.pushStages` and `WorkshopRepository`'s outbox pass
 * resolve every such value with the server and write the artisan's id in its place. A card the
 * server cannot match holds the stage or entry with the server's sentence; nothing is dropped and
 * nothing half-linked is sent.
 *
 * Pure apart from [resolvePendingCards]'s injected lookup, so `PendingCardsTest` drives it directly.
 */

/** Every pending card value anywhere in [element]. */
fun pendingCardsIn(element: JsonElement): Set<String> = when (element) {
    is JsonPrimitive -> if (element.isString && ArtisanCardCode.isPending(element.content)) setOf(element.content) else emptySet()
    is JsonArray -> element.flatMapTo(LinkedHashSet()) { pendingCardsIn(it) }
    is JsonObject -> element.values.flatMapTo(LinkedHashSet()) { pendingCardsIn(it) }
}

/** [element] with each pending value in [ids] replaced by its artisan id. */
fun substitutePendingCards(element: JsonElement, ids: Map<String, String>): JsonElement = when (element) {
    is JsonPrimitive -> if (element.isString) ids[element.content]?.let(::JsonPrimitive) ?: element else element
    is JsonArray -> JsonArray(element.map { substitutePendingCards(it, ids) })
    is JsonObject -> JsonObject(element.mapValues { (_, value) -> substitutePendingCards(value, ids) })
}

/** Every pending card value in a queued entry's body (`PendingEntry.payloadJson`); none for a body that won't parse. */
fun pendingCardsInPayload(payloadJson: String): Set<String> =
    runCatching { pendingCardsIn(Json.parseToJsonElement(payloadJson)) }.getOrDefault(emptySet())

/** [payloadJson] with each pending value in [ids] replaced by its artisan id; key order is kept. */
fun substitutePendingCardsInPayload(payloadJson: String, ids: Map<String, String>): String =
    substitutePendingCards(Json.parseToJsonElement(payloadJson), ids).toString()

sealed interface PendingCardOutcome {
    /** Every card resolved: pending value → artisan id. */
    data class Resolved(val ids: Map<String, String>) : PendingCardOutcome
    /** Still no signal: try again on the next pass. */
    data object Offline : PendingCardOutcome
    /** The server could not match one: the stage waits, with this sentence. */
    data class Held(val message: String) : PendingCardOutcome
}

/** "Card D-7K3QM-9XA2F-HQ: No artisan matches this card." */
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
            // The card was reissued, and it still names its artisan: link them.
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
