package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireTranscriptsTest {

    @Test
    fun `a section key is the web's key, character for character`() {
        assertEquals("section:abc", questionnaireSectionClipKey("abc"))
        assertTrue(isQuestionnaireSectionClipKey("section:abc"))
    }

    @Test
    fun `a question id is not a section key`() {
        assertFalse(isQuestionnaireSectionClipKey("clx123abc"))
    }

    @Test
    fun `an empty box takes the transcript directly`() {
        val outcome = questionnaireTranscriptOutcome("q1", "She dyes the warp first.", "", null)
        assertEquals(
            QuestionnaireTranscriptOutcome.Written("She dyes the warp first."),
            outcome
        )
    }

    @Test
    fun `a second take is APPENDED to the machine's first, not substituted for it`() {
        val outcome = questionnaireTranscriptOutcome(
            key = "q1",
            text = "Then the loom is dressed.",
            inBox = "She dyes the warp first.",
            previousMachine = "She dyes the warp first."
        )
        assertEquals(
            QuestionnaireTranscriptOutcome.Written("She dyes the warp first. Then the loom is dressed."),
            outcome
        )
    }

    @Test
    fun `the machine's copy carries the WHOLE merged value`() {
        val outcome = questionnaireTranscriptOutcome("q1", "second", "first", "first")
        val merged = (outcome as QuestionnaireTranscriptOutcome.Written).merged
        assertEquals("first second", merged)
        assertEquals(false, questionnaireTranscriptEdited(merged, merged))
    }

    @Test
    fun `a box the researcher typed into is OFFERED the transcript, never overwritten`() {
        val outcome = questionnaireTranscriptOutcome(
            key = "q1",
            text = "the machine's version",
            inBox = "what the researcher typed while the artisan spoke",
            previousMachine = null
        )
        assertEquals(QuestionnaireTranscriptOutcome.Offered("the machine's version"), outcome)
    }

    @Test
    fun `a box holding the machine's own words is not an edit`() {
        val outcome = questionnaireTranscriptOutcome("q1", "b", "  a  ", "a")
        assertTrue(outcome is QuestionnaireTranscriptOutcome.Written)
    }

    @Test
    fun `a box the researcher CHANGED after dictating is offered, not overwritten`() {
        val outcome = questionnaireTranscriptOutcome(
            key = "q1",
            text = "another take",
            inBox = "the machine said this, and I corrected it",
            previousMachine = "the machine said this"
        )
        assertEquals(QuestionnaireTranscriptOutcome.Offered("another take"), outcome)
    }

    @Test
    fun `accepting an offer appends and cannot lose a syllable`() {
        assertEquals(
            "my own words the offered take",
            questionnaireAcceptOffer("my own words", "the offered take")
        )
    }

    @Test
    fun `a section take is never filed under a question's answer`() {
        val outcome = questionnaireTranscriptOutcome("section:s1", "the whole sitting", "", null)
        assertEquals(QuestionnaireTranscriptOutcome.SectionOnly("the whole sitting"), outcome)
    }

    @Test
    fun `a section take is offered nothing and overwrites nothing, even with text on screen`() {
        val outcome = questionnaireTranscriptOutcome(
            key = "section:s1",
            text = "second half of the sitting",
            inBox = "somebody's typing",
            previousMachine = "first half"
        )
        assertEquals(
            QuestionnaireTranscriptOutcome.SectionOnly("first half second half of the sitting"),
            outcome
        )
    }

    @Test
    fun `no machine text means NO flag at all, not "Not edited"`() {
        assertNull(questionnaireTranscriptEdited(null, "typed by a person"))
        assertNull(questionnaireEditedFlagLabel(null))
    }

    @Test
    fun `an untouched transcript reads as Not edited`() {
        assertEquals(false, questionnaireTranscriptEdited("the machine said this", "the machine said this"))
        assertEquals("Not edited", questionnaireEditedFlagLabel(false))
    }

    @Test
    fun `a corrected transcript reads as Edited`() {
        assertEquals(true, questionnaireTranscriptEdited("the machine said this", "the machine said that"))
        assertEquals("Edited", questionnaireEditedFlagLabel(true))
    }

    @Test
    fun `whitespace either side is not an edit`() {
        assertEquals(false, questionnaireTranscriptEdited("a sentence", "  a sentence\n"))
    }

    @Test
    fun `both refusals say the clip is still saved, because it is`() {
        listOf(questionnaireNoWorkshopLine(), questionnaireClipTooLongLine()).forEach {
            assertTrue(it, it.contains(QUESTIONNAIRE_CLIP_KEPT_CLAUSE))
        }
    }

    @Test
    fun `the no-workshop refusal names the workshop picker and not a password or a signal`() {
        assertTrue(questionnaireNoWorkshopLine().contains("design workshop"))
    }

    @Test
    fun `the byte ceiling is checked against the file and not against the clock`() {
        assertFalse(questionnaireClipTooLongToDictate(6L * 1024 * 1024, 6L * 1024 * 1024))
        assertTrue(questionnaireClipTooLongToDictate(6L * 1024 * 1024 + 1, 6L * 1024 * 1024))
    }

    @Test
    fun `the recorder's ceiling is the web's fifteen minutes and fits in an Int`() {
        assertEquals(15L * 60L * 1000L, QUESTIONNAIRE_CLIP_MAX_MILLIS)
        assertEquals(QUESTIONNAIRE_CLIP_MAX_MILLIS, QUESTIONNAIRE_CLIP_MAX_MILLIS.toInt().toLong())
    }

    @Test
    fun `stopping at the ceiling is announced, and says the take was kept`() {
        val line = questionnaireClipCapLine()
        assertTrue(line, line.contains("15"))
        assertTrue(line, line.contains("The take is kept"))
    }
}
