package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.InterviewResponseDto
import com.designprototype.workshop.data.MediaFileDto
import com.designprototype.workshop.data.QuestionnaireQuestionDto
import com.designprototype.workshop.data.QuestionnaireSectionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPENING AN EDIT THAT ALREADY HAS SOMETHING IN IT, PINNED.
 *
 * ── WHY THIS IS A TEST AND NOT A LOOK AT THE SCREEN ──────────────────────────────────────────────
 *
 * Every failure below renders perfectly. The form draws, every card is in its place, the header
 * counts are right — and the researcher's own answers and their own recordings are behind a closed
 * card they have no reason to open, because the screen gives no sign that anything is in there. That
 * is the owner's complaint word for word (*"already existing entries and media do not show up in the
 * respective sections"*) and it was never a fetching bug: the data was in hand both times.
 *
 * There is no Compose renderer in this project's unit tests, so a rule left inside `QuestionnaireForm`
 * — a 700-line composable in a 21,000-line file — is exercised only by somebody holding a phone,
 * looking at an interview they already know the contents of.
 *
 * ── THE RULES, AND WHAT BREAKING EACH ONE COSTS ──────────────────────────────────────────────────
 *
 * 1. **A section with a typed answer opens.** Break it and this test's subject is the whole
 *    complaint: an interview whose answers are all present reads as blank.
 * 2. **A section whose ONLY content is a recording opens.** This is the important one. These
 *    sections are captured as a single whole-section take with the answer boxes hidden, so the
 *    best-conducted sittings have ZERO response rows — a rule that counted only answers would leave
 *    exactly those looking untouched.
 * 3. **A section nobody touched stays shut**, or the reveal is just "open everything" and the
 *    twenty-section instrument composes every input on a handset for nothing.
 * 4. **Hidden answer boxes still SHOW a recorded answer** — without touching the preference, which
 *    is the reader's own choice about capture and stays exactly as they set it.
 */
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

    // ── 1. Answers ───────────────────────────────────────────────────────────────────────────────

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
        // The web turned this box into a formatted editor, so the column holds `{"blocks":…}` for any
        // answer somebody bolded a word in. Comparing the raw string against "" would call that
        // content (it is), but a rule that only checked `isNullOrBlank` on a DOCUMENT WITH NO WORDS
        // in it would open a section over an empty document. Flattening is what tells them apart.
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
        // `notes` is the researcher's own words typed against that question — the merge route treats
        // it beside `answerText` for exactly this reason. A section whose only content is a note
        // would otherwise stay shut over it.
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

    // ── 2. Recordings, which on this instrument are the interview ────────────────────────────────

    @Test
    fun `a whole-section take opens its section although nothing was typed`() {
        // THE CASE THE WHOLE FEATURE IS FOR. Sections are recorded in one take with the answer boxes
        // hidden, so a correctly conducted sitting has NO responses at all and many recordings. A
        // reveal that read only responses would leave the best interviews looking empty.
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
        // The prefix fallback in `questionnaireCaptionBelongsToSection`. Without it, editing a
        // section's title orphans every clip recorded under the old wording — into the catch-all,
        // while the section it belongs to stays shut.
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
        // It is drawn by the "Other saved recordings & media" catch-all, which is outside every
        // section card and needs no card opened to be read.
        assertFalse(questionnaireCaptionBelongsToSection("Field media for Interview with Giriraj", sectionD))
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = emptyList(),
            savedMedia = listOf(clip("Field media for Interview with Giriraj")),
        )
        assertTrue(revealed.isEmpty())
    }

    // ── 3. What stays shut ───────────────────────────────────────────────────────────────────────

    @Test
    fun `sections nobody touched stay closed`() {
        val revealed = questionnaireSectionsToReveal(
            sections = instrument,
            responses = listOf(InterviewResponseDto(questionId = "d1", answerText = "Forty years.")),
            savedMedia = listOf(clip("Section audio: K Section K")),
        )
        // D by its answer and K by its recording — and F, which this sitting never reached, shut.
        assertEquals(setOf("sec-D", "sec-K"), revealed)
    }

    @Test
    fun `a new interview opens nothing at all`() {
        assertTrue(questionnaireSectionsToReveal(instrument, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a section whose questions have all been retired is never revealed`() {
        // `QuestionnaireForm` draws a section only when it has an ACTIVE question, so naming a
        // retired one would put an id in the expanded set that no control on the screen can clear.
        val retired = section("Z", question("z1", "Z", 1, active = false))
        val revealed = questionnaireSectionsToReveal(
            sections = listOf(retired),
            responses = listOf(InterviewResponseDto(questionId = "z1", answerText = "An answer to a retired question.")),
            savedMedia = listOf(clip("Section audio: Z Section Z")),
        )
        assertTrue(revealed.isEmpty())
    }

    // ── 4. Showing a recorded answer without touching the preference ─────────────────────────────

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
        // THE SECOND HALF OF THE COMPLAINT. `hideAnswers` defaults TRUE, so before this rule a
        // researcher opening their own interview could not see a single answer they had typed — on
        // any screen, at all — and the preference is NOT flipped to fix that: the words are drawn
        // read-only instead, and the switch goes on meaning what it says.
        assertEquals(
            QuestionnaireAnswerDisplay.RECORDED,
            questionnaireAnswerDisplay(hideAnswers = true, current = "She learnt it from her mother."),
        )
    }

    @Test
    fun `hidden boxes draw nothing when nothing was typed`() {
        // The capture screen this default was chosen for is untouched: no boxes, no plates, just the
        // record button.
        assertEquals(QuestionnaireAnswerDisplay.NONE, questionnaireAnswerDisplay(hideAnswers = true, current = ""))
        assertEquals(QuestionnaireAnswerDisplay.NONE, questionnaireAnswerDisplay(hideAnswers = true, current = null))
        assertEquals(QuestionnaireAnswerDisplay.NONE, questionnaireAnswerDisplay(hideAnswers = true, current = "  \n "))
    }

    @Test
    fun `a stored document is shown as words and never as its braces`() {
        val document = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"She dyes it in indigo","marks":["BOLD"]}]}]}"""
        assertEquals(QuestionnaireAnswerDisplay.RECORDED, questionnaireAnswerDisplay(hideAnswers = true, current = document))
        // What the read-only plate prints. `{"blocks":[{"kind":"PARAGRAPH"… in the place an artisan's
        // answer belongs is the defect `questionnaireAnswerPlain` exists to end.
        assertEquals("She dyes it in indigo", questionnaireAnswerPlain(document))
    }
}
