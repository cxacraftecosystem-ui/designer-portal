package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An interview correction queued with no signal (2026-10-17) — see `InterviewCorrection.kt`. */
class InterviewCorrectionTest {

    private val formJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun payload(request: QuestionnaireInterviewUpdateRequest): JsonObject =
        formJson.encodeToJsonElement(QuestionnaireInterviewUpdateRequest.serializer(), request) as JsonObject

    @Test
    fun the_queued_body_drops_the_forms_nulls_so_nothing_is_cleared_by_accident() {
        val body = interviewCorrectionBody(
            payload(QuestionnaireInterviewUpdateRequest(title = "Lac turning", place = null, notes = null)),
            changedKeys = null,
            baseUpdatedAt = null,
        )
        assertEquals("Lac turning", body["title"]!!.jsonPrimitive.content)
        assertFalse("place" in body)
        assertFalse("notes" in body)
    }

    @Test
    fun the_queued_body_carries_only_its_changes_and_the_version_it_was_composed_against() {
        val request = QuestionnaireInterviewUpdateRequest(
            title = "Lac turning",
            place = "Channapatna",
            responses = listOf(QuestionnaireResponseRequest(questionId = "q-1", answerText = "Hand lathe")),
        )
        val body = interviewCorrectionBody(
            payload(request),
            changedKeys = setOf("responses"),
            baseUpdatedAt = "2026-10-17T09:00:00.000Z",
        )
        assertEquals(setOf("responses", "expectedUpdatedAt"), body.keys)
        assertEquals(JsonPrimitive("2026-10-17T09:00:00.000Z"), body["expectedUpdatedAt"])
    }

    @Test
    fun a_workshop_box_emptied_on_purpose_is_sent_as_a_clearance() {
        val body = interviewCorrectionBody(
            payload(QuestionnaireInterviewUpdateRequest(title = "Lac turning", workshopId = null)),
            changedKeys = setOf("workshopId"),
            baseUpdatedAt = "2026-10-17T09:00:00.000Z",
            cleared = setOf("workshopId"),
        )
        assertEquals(JsonNull, body["workshopId"])
    }

    private val record = QuestionnaireInterviewDetailDto(
        id = "iv-1",
        title = "Lac turning",
        place = "Channapatna",
        artisans = listOf(
            WorkshopArtisanLinkDto(artisanId = "a-1", artisan = ArtisanDto(id = "a-1", name = "Asha", place = "Channapatna", status = "APPROVED")),
        ),
        responses = listOf(
            InterviewResponseDto(questionId = "q-1", answerText = "Lathe and chisel"),
            InterviewResponseDto(questionId = "q-2", answerText = "Added by the other interviewer"),
        ),
        updatedAt = "2026-10-17T10:00:00.000Z",
    )

    @Test
    fun review_names_answers_by_their_question_and_shows_both_values() {
        val review = interviewCorrectionReview(
            QuestionnaireInterviewUpdateRequest(
                title = "Lac turning",
                responses = listOf(QuestionnaireResponseRequest(questionId = "q-1", answerText = "Hand lathe")),
            ),
            record,
            mapOf("q-1" to "B3. What tools are used?"),
        )
        assertEquals(1, review.fields.size)
        val field = review.fields.single()
        assertEquals("B3. What tools are used?", field.key)
        assertEquals("Hand lathe", field.mine)
        assertEquals("Lathe and chisel", field.theirs)
        assertEquals("2026-10-17T10:00:00.000Z", review.currentUpdatedAt)
    }

    @Test
    fun review_leaves_out_what_both_already_agree_on_and_answers_this_correction_does_not_touch() {
        val review = interviewCorrectionReview(
            QuestionnaireInterviewUpdateRequest(
                title = "Lac turning",
                place = "Channapatna",
                responses = listOf(QuestionnaireResponseRequest(questionId = "q-1", answerText = "Lathe and chisel")),
            ),
            record,
            emptyMap(),
        )
        assertTrue(review.fields.isEmpty())
    }

    @Test
    fun review_says_who_the_artisans_are_rather_than_their_ids() {
        val review = interviewCorrectionReview(
            QuestionnaireInterviewUpdateRequest(artisanIds = listOf("a-1", "a-9")),
            record,
            emptyMap(),
        )
        val field = review.fields.single()
        assertEquals("Artisans", field.key)
        assertEquals("Asha, 1 more artisan", field.mine)
        assertEquals("Asha", field.theirs)
    }

    @Test
    fun an_unnamed_question_is_still_called_an_answer_and_never_an_id() {
        val review = interviewCorrectionReview(
            QuestionnaireInterviewUpdateRequest(
                responses = listOf(QuestionnaireResponseRequest(questionId = "q-7", answerText = "New")),
            ),
            record,
            emptyMap(),
        )
        assertEquals("An answer", review.fields.single().key)
    }

    @Test
    fun a_question_is_labelled_by_its_number_and_prompt() {
        val question = QuestionnaireQuestionDto(
            id = "q-1", sectionCode = "B", sectionTitle = "Tools", prompt = "What tools are used?", sortOrder = 3,
        )
        assertEquals("B3. What tools are used?", interviewQuestionLabel(question))
    }

    @Test
    fun an_answer_note_that_differs_is_listed_so_send_mine_cannot_overwrite_it_unseen() {
        val withNote = record.copy(
            responses = listOf(InterviewResponseDto(questionId = "q-1", answerText = "Lathe and chisel", notes = "Theirs")),
        )
        val review = interviewCorrectionReview(
            QuestionnaireInterviewUpdateRequest(
                responses = listOf(
                    QuestionnaireResponseRequest(questionId = "q-1", answerText = "Lathe and chisel", notes = "Mine"),
                ),
            ),
            withNote,
            mapOf("q-1" to "B3. What tools are used?"),
        )
        val field = review.fields.single()
        assertEquals("B3. What tools are used? (note)", field.key)
        assertEquals("Mine", field.mine)
        assertEquals("Theirs", field.theirs)
    }

    @Test
    fun only_the_keys_the_correction_sends_are_compared() {
        val review = interviewCorrectionReview(
            QuestionnaireInterviewUpdateRequest(title = "Something else", status = "APPROVED", place = "Channapatna"),
            record,
            emptyMap(),
            changedKeys = setOf("place"),
        )
        assertTrue(review.fields.isEmpty())
    }

    @Test
    fun a_changed_status_and_workshop_are_named_in_words_not_codes_or_ids() {
        val review = interviewCorrectionReview(
            QuestionnaireInterviewUpdateRequest(status = "NEEDS_REVISION", workshopId = "ws-2"),
            record.copy(workshopId = "ws-1"),
            emptyMap(),
            changedKeys = setOf("status", "workshopId"),
        )
        assertEquals(listOf("Review status", "Workshop"), review.fields.map { it.key })
        assertEquals("Needs revision", review.fields[0].mine)
        assertEquals("Pending", review.fields[0].theirs)
        assertFalse(review.fields[1].mine.contains("ws-"))
        assertFalse(review.fields[1].theirs.contains("ws-"))
    }

    @Test
    fun a_correction_the_server_already_holds_reviews_empty_so_the_outbox_sends_it_without_asking() {
        // The lost-response case: the PATCH committed, its answer did not arrive, and the queued
        // copy is refused against the old version. Nothing differs, so nothing needs choosing.
        val review = interviewCorrectionReview(
            QuestionnaireInterviewUpdateRequest(
                title = "Lac turning",
                responses = listOf(QuestionnaireResponseRequest(questionId = "q-1", answerText = "Lathe and chisel")),
            ),
            record,
            emptyMap(),
            changedKeys = setOf("responses"),
        )
        assertTrue(review.fields.isEmpty())
    }

    @Test
    fun a_correction_with_no_version_is_refused_in_the_room_rather_than_queued_bare() {
        assertFalse(interviewCorrectionCanQueue(null))
        assertFalse(interviewCorrectionCanQueue(" "))
        assertTrue(interviewCorrectionCanQueue("2026-10-17T10:00:00.000Z"))
        assertFalse(INTERVIEW_CORRECTION_NEEDS_SIGNAL.contains("updatedAt"))
        assertTrue(INTERVIEW_CORRECTION_NEEDS_SIGNAL.contains("still on screen"))
    }
}
