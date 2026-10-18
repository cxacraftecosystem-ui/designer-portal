package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Links on sync": a card scanned offline into a stage's artisan box is resolved before the stage is
 * sent, or the stage is held with the server's sentence. Never dropped, never sent half-linked.
 */
class PendingCardsTest {
    private val pending = "card:D-7K3QM-9XA2F-HQ"
    private val stage = JsonObject(
        mapOf(
            "participants" to JsonArray(
                listOf(
                    JsonObject(mapOf("artisanRef" to JsonPrimitive(pending), "village" to JsonPrimitive("Bagru"))),
                    JsonObject(mapOf("artisanRef" to JsonPrimitive("cart0000000000000000001"))),
                )
            ),
            "count" to JsonPrimitive(2),
        )
    )

    @Test
    fun `pending card values are found wherever they sit in a stage`() {
        assertEquals(setOf(pending), pendingCardsIn(stage))
        assertEquals(emptySet<String>(), pendingCardsIn(JsonObject(mapOf("note" to JsonPrimitive("card games")))))
    }

    @Test
    fun `a resolved card is replaced by the artisan id and nothing else changes`() = runBlocking {
        val outcome = resolvePendingCards(setOf(pending)) { input ->
            assertEquals("D-7K3QM-9XA2F-HQ", input)
            CardResolution.Found("cart0000000000000000042", "Sita Devi", null)
        }
        assertTrue(outcome is PendingCardOutcome.Resolved)
        val replaced = substitutePendingCards(stage, (outcome as PendingCardOutcome.Resolved).ids) as JsonObject
        assertEquals(emptySet<String>(), pendingCardsIn(replaced))
        val rows = replaced["participants"] as JsonArray
        assertEquals(JsonPrimitive("cart0000000000000000042"), (rows[0] as JsonObject)["artisanRef"])
        assertEquals(JsonPrimitive("Bagru"), (rows[0] as JsonObject)["village"])
        assertEquals(JsonPrimitive("cart0000000000000000001"), (rows[1] as JsonObject)["artisanRef"])
        assertEquals(JsonPrimitive(2), replaced["count"])
    }

    @Test
    fun `a card the server cannot match holds the stage with its sentence`() = runBlocking {
        val outcome = resolvePendingCards(setOf(pending)) { CardResolution.Refused(CARD_NO_MATCH) }
        assertEquals(PendingCardOutcome.Held("Card D-7K3QM-9XA2F-HQ: No artisan matches this card."), outcome)
    }

    @Test
    fun `still no signal waits for the next pass`() = runBlocking {
        val card = (ArtisanCardCode.parse("D-7K3QM-9XA2F-HQ") as ArtisanCardCode.Result.Ok).card
        assertEquals(PendingCardOutcome.Offline, resolvePendingCards(setOf(pending)) { CardResolution.Pending(card) })
    }

    @Test
    fun `a replaced card still links its artisan, and a Field Repository artisan not here holds`() = runBlocking {
        val replaced = resolvePendingCards(setOf(pending)) { CardResolution.Replaced("cart0000000000000000042") }
        assertEquals(PendingCardOutcome.Resolved(mapOf(pending to "cart0000000000000000042")), replaced)
        val remote = resolvePendingCards(setOf(pending)) { CardResolution.Remote }
        assertTrue(remote is PendingCardOutcome.Held)
    }

    @Test
    fun `a queued workshop body links its pending card and keeps every other byte`() {
        val body = """{"title":"Block printing","artisanIds":["cart0000000000000000001","$pending"],"place":"Bagru"}"""
        assertEquals(setOf(pending), pendingCardsInPayload(body))
        assertEquals(
            """{"title":"Block printing","artisanIds":["cart0000000000000000001","cart0000000000000000042"],"place":"Bagru"}""",
            substitutePendingCardsInPayload(body, mapOf(pending to "cart0000000000000000042")),
        )
    }

    @Test
    fun `a body with no pending card, or one that will not parse, has nothing to link`() {
        assertEquals(emptySet<String>(), pendingCardsInPayload("""{"artisanIds":["cart0000000000000000001"]}"""))
        assertEquals(emptySet<String>(), pendingCardsInPayload("not json"))
    }
}
