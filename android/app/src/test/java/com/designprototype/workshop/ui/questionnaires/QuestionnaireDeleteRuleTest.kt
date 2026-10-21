package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.CustomAnswerDto
import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.qLiveSource
import com.designprototype.workshop.ui.FieldPermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's delete rule on the Android questionnaire screens (audit 21 Oct 2026, M2 and M17). */
class QuestionnaireDeleteRuleTest {

    private fun user(role: String) = UserDto(id = role, email = "$role@example.org", name = role, role = role)

    @Test
    fun `only content admins may delete a sitting or a recording`() {
        // The content-admin set itself, not a copy of it: a list typed here would be a second ladder.
        FieldPermissions.CONTENT_ADMIN_ROLES.forEach { assertTrue(it, mayDeleteQuestionnaireItems(user(it))) }
        listOf("DESIGNER", "RESEARCHER").forEach { assertFalse(it, mayDeleteQuestionnaireItems(user(it))) }
        assertFalse(mayDeleteQuestionnaireItems(null))
    }

    @Test
    fun `a form and its questions are deleted by its author or a content admin and nobody else`() {
        // Owner, 22 Oct 2026: "the author should be able to edit/delete the questionnaire apart from
        // the admins". The author is the form's ownerId.
        val author = user("DESIGNER")
        assertTrue(mayDeleteQuestionnaire(author, author.id))
        FieldPermissions.CONTENT_ADMIN_ROLES.forEach { assertTrue(it, mayDeleteQuestionnaire(user(it), "someone-else")) }
        listOf("DESIGNER", "RESEARCHER").forEach { assertFalse(it, mayDeleteQuestionnaire(user(it), "someone-else")) }
        assertFalse(mayDeleteQuestionnaire(null, "someone-else"))
        assertFalse(mayDeleteQuestionnaire(author, null))
        assertFalse(mayDeleteQuestionnaire(author, ""))
    }

    @Test
    fun `the Remove control is drawn for the author or a content admin, a sitting's Delete for admins`() {
        val screen = qLiveSource(
            "app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireDetailScreen.kt"
        ).readText()
        val row = screen.substringAfter("private fun QuestionRow(")
        val remove = row.substringBefore("TextButton(onClick = onRemove")
        assertTrue(remove.trimEnd().endsWith("if (mayDelete) {"))
        assertTrue(screen.contains("mayDeleteQuestionnaire(repository.cachedUser(), loaded?.ownerId)"))
        assertTrue(screen.contains("val mayDeleteSittings = remember { mayDeleteQuestionnaireItems(repository.cachedUser()) }"))
        // Editing asks the same author-or-content-admin rule as deleting.
        val common = qLiveSource(
            "app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireCommon.kt"
        ).readText()
        assertTrue(common.contains("mayDeleteQuestionnaire(repository.cachedUser(), ownerId)"))
        // A workbook re-upload is an edit: drawn for the author or a content admin, nobody else.
        val reupload = screen.substringBefore("label = \"Upload an edited workbook\"")
            .substringBeforeLast("WorkbookUploadRow(")
        assertTrue(reupload.trimEnd().endsWith("HorizontalDivider(color = MaterialTheme.field.hairline)"))
        assertTrue(
            reupload.trimEnd().removeSuffix("HorizontalDivider(color = MaterialTheme.field.hairline)")
                .trimEnd().endsWith("if (mayEdit) {")
        )
    }

    @Test
    fun `an answer with notes only retires the question`() {
        val question = CustomQuestionDto(id = "q1", prompt = "Which dyes?", hasAnswers = false)
        val notesOnly = CustomEntryDto(id = "e1", answers = listOf(CustomAnswerDto(questionId = "q1", answerText = " ", notes = "Asked twice")))
        val blank = CustomEntryDto(id = "e2", answers = listOf(CustomAnswerDto(questionId = "q1", answerText = "", notes = null)))
        assertTrue(questionRemovalRetires(question, listOf(notesOnly)))
        assertFalse(questionRemovalRetires(question, listOf(blank)))
        assertTrue(questionRemovalRetires(question.copy(hasAnswers = true), emptyList()))
        val screen = qLiveSource(
            "app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireDetailScreen.kt"
        ).readText()
        assertTrue(screen.contains("val retires = questionRemovalRetires(question, loaded?.entries.orEmpty())"))
        // The old warning sent the designer to remove a blank answer the server now clears itself.
        assertFalse(screen.contains("so it can't be deleted. Remove that blank first"))
    }
}
