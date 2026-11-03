package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.InterviewResponseDto
import com.designprototype.workshop.data.MediaFileDto
import com.designprototype.workshop.data.QuestionnaireQuestionDto
import com.designprototype.workshop.data.QuestionnaireSectionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireEditRevealTest {

    private fun question(id: String, sectionCode: String, order: Int, active: Boolean = true) =
        QuestionnaireQuestionDto(
            id = id,
            sectionId = "sec-$sectionCode",
            sectionCode = sectionCode,
            sectionTitle = "Section $sectionCode",
            prompt = "Prompt $order",
            sortOrder = order,
            isActive = active,
        )

    private fun section(code: String, vararg questions: QuestionnaireQuestionDto) =
        QuestionnaireSectionDto(
            id = "sec-$code",
            code = code,
            title = "Section $code",
            sortOrder = code.first().code,
            questions = questions.toList(),
        )

    private fun clip(caption: String) = MediaFileDto(
        id = "media-${caption.hashCode()}",
        originalFilename = "clip.m4a",
        mediaType = "AUDIO",
        caption = caption,
    )

    private val sectionD = section("D", question("d1", "D", 1), question("d2", "D", 2))
    private val sectionF = section("F", question("f1", "F", 1))
    private val sectionK = section("K", question("k1", "K", 1))
    private val instrument = listOf(sectionD, sectionF, sectionK)

    @Test
    fun `a section holding a typed answer is opened`() {
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = listOf(InterviewResponseDto(questionId = "f1", answerText = "She learnt it from her mother.")),
            savedMedia = emptyList(),
        )
        assertEquals(setOf("sec-F"), revealed)
    }

    @Test
    fun `a rich-text answer counts, because the column may hold a document`() {
        val document = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"She dyes it in indigo","marks":["BOLD"]}]}]}"""
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = listOf(InterviewResponseDto(questionId = "d2", answerText = document)),
            savedMedia = emptyList(),
        )
        assertEquals(setOf("sec-D"), revealed)
    }

    @Test
    fun `a note on its own is content`() {
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = listOf(InterviewResponseDto(questionId = "k1", notes = "Asked again after the tea break.")),
            savedMedia = emptyList(),
        )
        assertEquals(setOf("sec-K"), revealed)
    }

    @Test
    fun `whitespace is not an answer`() {
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = listOf(InterviewResponseDto(questionId = "f1", answerText = "   ", notes = "")),
            savedMedia = emptyList(),
        )
        assertTrue(revealed.isEmpty())
    }

    @Test
    fun `a whole-section take opens its section although nothing was typed`() {
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = emptyList(),
            savedMedia = listOf(clip("Section audio: F Section F")),
        )
        assertEquals(setOf("sec-F"), revealed)
    }

    @Test
    fun `a per-question take opens its section`() {
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = emptyList(),
            savedMedia = listOf(clip("Question audio: K1 Prompt 1")),
        )
        assertEquals(setOf("sec-K"), revealed)
    }

    @Test
    fun `a recording whose section title was reworded afterwards still opens its section`() {
        assertTrue(questionnaireCaptionBelongsToSection("Section audio: D The black pottery of Nizamabad", sectionD))
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = emptyList(),
            savedMedia = listOf(clip("Section audio: D The black pottery of Nizamabad")),
        )
        assertEquals(setOf("sec-D"), revealed)
    }

    @Test
    fun `general media belonging to no section opens nothing`() {
        assertFalse(questionnaireCaptionBelongsToSection("Field media for Interview with Giriraj", sectionD))
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = emptyList(),
            savedMedia = listOf(clip("Field media for Interview with Giriraj")),
        )
        assertTrue(revealed.isEmpty())
    }

    @Test
    fun `sections nobody touched stay closed`() {
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = listOf(InterviewResponseDto(questionId = "d1", answerText = "Forty years.")),
            savedMedia = listOf(clip("Section audio: K Section K")),
        )
        assertEquals(setOf("sec-D", "sec-K"), revealed)
    }

    @Test
    fun `a new interview opens nothing at all`() {
        assertTrue(questionnaireSectionsToReveal(instrument, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a section whose questions have all been retired is never revealed`() {
        val retired = section("Z", question("z1", "Z", 1, active = false))
        val revealed = questionnaireSectionsToReveal(
            sections = listOf(retired),
            responses = listOf(InterviewResponseDto(questionId = "z1", answerText = "An answer to a retired question.")),
            savedMedia = listOf(clip("Section audio: Z Section Z")),
        )
        assertTrue(revealed.isEmpty())
    }

    @Test
    fun `the switch off always gives the editor`() {
        assertEquals(QuestionnaireAnswerDisplay.EDITOR, questionnaireAnswerDisplay(hideAnswers = false, current = ""))
        assertEquals(
            QuestionnaireAnswerDisplay.EDITOR,
            questionnaireAnswerDisplay(hideAnswers = false, current = "She learnt it from her mother."),
        )
    }

    @Test
    fun `hidden boxes still show an answer that was recorded`() {
        assertEquals(
            QuestionnaireAnswerDisplay.RECORDED,
            questionnaireAnswerDisplay(hideAnswers = true, current = "She learnt it from her mother."),
        )
    }

    @Test
    fun `hidden boxes draw nothing when nothing was typed`() {
        assertEquals(QuestionnaireAnswerDisplay.NONE, questionnaireAnswerDisplay(hideAnswers = true, current = ""))
        assertEquals(QuestionnaireAnswerDisplay.NONE, questionnaireAnswerDisplay(hideAnswers = true, current = null))
        assertEquals(QuestionnaireAnswerDisplay.NONE, questionnaireAnswerDisplay(hideAnswers = true, current = "  \n "))
    }

    @Test
    fun `a stored document is shown as words and never as its braces`() {
        val document = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"She dyes it in indigo","marks":["BOLD"]}]}]}"""
        assertEquals(QuestionnaireAnswerDisplay.RECORDED, questionnaireAnswerDisplay(hideAnswers = true, current = document))
        assertEquals("She dyes it in indigo", questionnaireAnswerPlain(document))
    }
}
