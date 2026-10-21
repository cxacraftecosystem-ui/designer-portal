package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.CustomAnswerDto
import com.designprototype.workshop.data.CustomAnswerRejectedDto
import com.designprototype.workshop.data.CustomAnswerSaveCountsDto
import com.designprototype.workshop.data.CustomAnswerSaveResultDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 and M6 (audit 21 Oct 2026): a per-answer save on the Android answer screen. */
class QuestionnaireAnswerSaveTest {

    @Test
    fun `the per-item reply decodes`() {
        val reply = Json { ignoreUnknownKeys = true }.decodeFromString(
            CustomAnswerSaveResultDto.serializer(),
            """{"saved":{"created":1,"updated":0,"unchanged":0,"rejected":[{"questionId":"q2","code":"QUESTION_RETIRED","message":"This question was retired."}]},"answers":[]}""",
        )
        assertEquals("QUESTION_RETIRED", reply.saved.rejected.single().code)
    }

    @Test
    fun `accepted answers are kept and each refusal is shown on its question`() {
        val result = CustomAnswerSaveResultDto(
            saved = CustomAnswerSaveCountsDto(
                created = 1,
                rejected = listOf(
                    CustomAnswerRejectedDto("q2", "QUESTION_RETIRED", "This question was retired."),
                    CustomAnswerRejectedDto("q3", "ANSWER_OWNED_BY_OTHER", "Priya recorded this answer."),
                ),
            ),
            answers = listOf(CustomAnswerDto(questionId = "q1", answerText = "Indigo", answeredById = "me")),
        )
        val outcome = answerSaveOutcome(
            result = result,
            sent = listOf("q1", "q2", "q3"),
            // q9 belongs to somebody else and is not in the narrowed reply: it must survive.
            baseline = mapOf("q9" to ("theirs" to "")),
            locked = setOf("q9"),
            previousProblems = mapOf("q1" to "old problem"),
            me = "me",
            admin = false,
        )
        assertEquals("Indigo" to "", outcome.baseline["q1"])
        assertEquals("theirs" to "", outcome.baseline["q9"])
        assertEquals(setOf("q9", "q3"), outcome.locked)
        assertEquals(setOf("q2", "q3"), outcome.problems.keys)
        assertTrue(outcome.message, outcome.message.startsWith("Saved 1 answer. 2 were not saved"))
    }
}
