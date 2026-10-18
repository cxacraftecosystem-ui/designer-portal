package com.designprototype.workshop.data

import com.designprototype.workshop.ui.RecordOpen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A CREATE HELD AS A LIKELY DUPLICATE (409 `artisan_possible_duplicate`) is its own outbox arm, never
 * a clash: the tray says who it looks like and offers "Same person" and "Different person".
 */
class OutboxPossibleDuplicateTest {
    private val holdBody = """
        {"detail":{"code":"artisan_possible_duplicate","message":"This may be Giriraj Prasaad (Bhuj), already recorded.",
         "candidates":[{"artisan":{"id":"a1","name":"Giriraj Prasaad","place":"Bhuj","craft":"Ajrakh","birthYear":1971},
           "score":0.86,"band":"LIKELY","reasons":[{"code":"NAME","label":"Names 92% alike"}]},
          {"artisan":{"id":"a2","name":"G. Prasad","place":"Anjar"},"score":0.62,"band":"POSSIBLE","reasons":[]}],
         "existingArtisan":{"id":"a1","name":"Giriraj Prasaad","place":"Bhuj","craft":"Ajrakh"}}}
    """.trimIndent()

    private fun entry(
        candidates: List<OutboxDuplicateCandidate> = emptyList(),
        payload: String = """{"name":"Giriraj Prasad","place":"Bhuj","duplicateAcknowledged":[]}""",
    ) = PendingEntry(
        id = "e1",
        type = "artisan",
        payloadJson = payload,
        label = "Giriraj Prasad",
        createdAt = "2026-10-18T00:00:00Z",
        failure = if (candidates.isEmpty()) null else PossibleDuplicates.traySentence(candidates),
        duplicateCandidates = candidates,
    )

    @Test
    fun the_hold_is_read_from_its_code_and_nothing_else_is() {
        val held = PossibleDuplicates.heldCandidates(409, holdBody)
        assertNotNull(held)
        assertEquals(listOf("a1", "a2"), held!!.map { it.artisan.id })
        assertEquals("Names 92% alike", held[0].reasons.single().label)
        // An identity clash is a 409 too, and stays the clash arm's.
        val clash = """{"detail":{"code":"artisan_identity_conflict","message":"x"}}"""
        assertNull(PossibleDuplicates.heldCandidates(409, clash))
        assertNull(PossibleDuplicates.heldCandidates(422, holdBody))
        assertNull(PossibleDuplicates.heldCandidates(409, "not json"))
    }

    @Test
    fun the_tray_names_the_top_candidate_and_the_row_is_not_a_clash() {
        val kept = PossibleDuplicates.forOutbox(PossibleDuplicates.heldCandidates(409, holdBody)!!)
        assertEquals("Not saved yet. This looks like Giriraj Prasaad (Bhuj).", PossibleDuplicates.traySentence(kept))
        assertEquals("Not saved yet. This may already be recorded.", PossibleDuplicates.traySentence(emptyList()))
        val row = outboxFailureRows(listOf(entry(kept))).single()
        assertFalse(row.conflict)
        assertEquals(listOf("a1", "a2"), row.possibleDuplicates.map { it.id })
    }

    @Test
    fun same_person_points_the_entry_at_the_existing_record_and_creates_nothing() {
        val held = entry(listOf(OutboxDuplicateCandidate("a1", "Giriraj Prasaad", "Bhuj")))
        assertTrue(samePersonAllowed(held, "a1"))
        assertFalse(samePersonAllowed(held, "someone-else"))
        val after = samePersonEntry(held, "a1")
        assertEquals("a1", after.createdId)
        assertTrue(after.attachedToExisting)
        assertNull(after.failure)
        assertTrue(after.duplicateCandidates.isEmpty())
        // The queued record's fields are not copied anywhere: the body is untouched.
        assertEquals(held.payloadJson, after.payloadJson)
    }

    @Test
    fun different_person_sends_again_with_the_candidates_acknowledged() {
        val held = entry(
            listOf(OutboxDuplicateCandidate("a1", "Giriraj Prasaad", "Bhuj"), OutboxDuplicateCandidate("a2", "G. Prasad")),
            payload = """{"name":"Giriraj Prasad","place":"Bhuj","duplicateAcknowledged":["a0"]}""",
        )
        val after = differentPersonEntry(held)
        val acked = Json.parseToJsonElement(after.payloadJson).jsonObject["duplicateAcknowledged"]!!.jsonArray
        assertEquals(listOf("a0", "a1", "a2"), acked.map { it.jsonPrimitive.content })
        assertNull(after.failure)
        assertNull(after.createdId)
        assertTrue(after.duplicateCandidates.isEmpty())
    }

    @Test
    fun acknowledged_ids_are_capped_at_twenty() {
        val many = (1..30).map { "id$it" }
        val out = PossibleDuplicates.payloadWithAcknowledged("""{"name":"x"}""", many)
        assertEquals(20, (Json.parseToJsonElement(out) as JsonObject)["duplicateAcknowledged"]!!.jsonArray.size)
        assertEquals("not json", PossibleDuplicates.payloadWithAcknowledged("not json", many))
    }

    @Test
    fun the_sheet_shows_at_most_three_above_the_threshold_and_not_the_ones_set_aside() {
        val held = PossibleDuplicates.heldCandidates(409, holdBody)!!
        assertEquals(listOf("a1", "a2"), PossibleDuplicates.toShow(held, emptySet()).map { it.artisan.id })
        assertEquals(listOf("a2"), PossibleDuplicates.toShow(held, setOf("a1")).map { it.artisan.id })
        val low = held.map { it.copy(score = 0.5) }
        assertTrue(PossibleDuplicates.toShow(low, emptySet()).isEmpty())
        assertEquals("Giriraj Prasaad · Bhuj · Ajrakh", PossibleDuplicates.rowLine(held[0]))
    }

    @Test
    fun a_queued_create_carries_an_empty_acknowledgement_and_an_edit_carries_none() {
        val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val create = queueJson.parseToJsonElement(
            queueJson.encodeToString(ArtisanCreateRequest.serializer(), ArtisanCreateRequest(name = "A", place = "B", duplicateAcknowledged = emptyList()))
        ).jsonObject
        assertEquals(JsonArray(emptyList()), create["duplicateAcknowledged"])
        val edit = queueJson.parseToJsonElement(
            queueJson.encodeToString(ArtisanCreateRequest.serializer(), ArtisanCreateRequest(name = "A", place = "B"))
        ).jsonObject
        assertFalse(edit.containsKey("duplicateAcknowledged"))
    }

    @Test
    fun a_merged_artisan_opens_its_survivor() {
        val merged = """{"detail":{"code":"artisan_merged","survivorId":"s1"}}"""
        assertEquals("s1", RecordOpen.mergedSurvivorId(404, merged))
        assertNull(RecordOpen.mergedSurvivorId(404, """{"detail":"Artisan not found"}"""))
        assertNull(RecordOpen.mergedSurvivorId(409, merged))
        assertNull(RecordOpen.mergedSurvivorId(404, null))
    }
}
