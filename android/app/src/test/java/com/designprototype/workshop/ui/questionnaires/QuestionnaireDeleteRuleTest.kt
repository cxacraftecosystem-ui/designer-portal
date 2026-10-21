package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.CustomAnswerDto
import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.qLiveSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's delete rule on the Android questionnaire screens (audit 21 Oct 2026, M2 and M17). */
class QuestionnaireDeleteRuleTest {

    private fun user(role: String) = UserDto(id = role, email = "$role@example.org", name = role, role = role)

    @Test
    fun `only content admins may remove a question`() {
        listOf("ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN").forEach { assertTrue(it, mayDeleteQuestionnaireItems(user(it))) }
        listOf("DESIGNER", "RESEARCHER", "PROFESSOR").forEach { assertFalse(it, mayDeleteQuestionnaireItems(user(it))) }
        assertFalse(mayDeleteQuestionnaireItems(null))
    }

    @Test
    fun `the Remove control is drawn for content admins alone`() {
        val screen = qLiveSource(
            "app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireDetailScreen.kt"
        ).readText()
        val row = screen.substringAfter("private fun QuestionRow(")
        val remove = row.substringBefore("TextButton(onClick = onRemove")
        assertTrue(remove.trimEnd().endsWith("if (mayDelete) {"))
        assertTrue(screen.contains("mayDeleteQuestionnaireItems(repository.cachedUser())"))
    }

    @Test
    fun `an answer with notes only retires the question`() {
        val question = CustomQuestionDto(id = "q1", prompt = "Which dyes?", hasAnswers = false)
        val notesOnly = CustomEntryDto(id = "e1", answers = listOf(CustomAnswerDto(questionId = "q1", answerText = " ", notes = "Asked twice")))
        val blank = CustomEntryDto(id = "e2", answers = listOf(CustomAnswerDto(questionId = "q1", answerText = "", notes = null)))
        assertTrue(questionRemovalRetires(question, listOf(notesOnly)))
        assertFalse(questionRemovalRetires(question, listOf(blank)))
        assertTrue(questionRemovalRetires(question.copy(hasAnswers = true), emptyList()))
    }
}
