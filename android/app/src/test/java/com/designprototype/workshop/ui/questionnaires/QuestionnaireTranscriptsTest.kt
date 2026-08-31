package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE QUESTIONNAIRE VOICE NOTE'S QUICK TRANSCRIPT, PINNED.
 *
 * ── WHY THIS IS A TEST AND NOT A READING ─────────────────────────────────────────────────────────
 *
 * Every failure below renders perfectly. The form draws, the words are English, and the only thing
 * wrong is that a researcher's typing was replaced by a machine's second guess — or that an answer
 * nobody dictated is stamped "Not edited", crediting a provider with a person's words. Neither is
 * visible in a diff and neither is visible on screen unless you already know what should have
 * happened. There is no Compose renderer in this project's unit tests, so a judgement left inside
 * `QuestionnaireForm` would only ever be exercised by somebody looking at a phone.
 *
 * ── THE FOUR RULES, AND WHAT BREAKING EACH ONE COSTS ─────────────────────────────────────────────
 *
 * 1. **A transcript never silently replaces an EDITED box.** The owner's rule, in as many words:
 *    *"unless the designer has edited the text, in which case it must not silently overwrite their
 *    words. Offer it, do not impose it."* Break this and a researcher who typed while the artisan was
 *    still speaking loses the sentence they typed, with nothing said.
 * 2. **Both branches APPEND.** Two clips against one question are two parts of one answer — the
 *    recorder is stopped when the artisan pauses and started when she resumes. A commit that replaced
 *    the box would delete everything in it at the first pause for breath, and the ACCEPT button would
 *    become the one control on the screen that destroys writing.
 * 3. **The machine's copy is the whole merged value, never the last fragment.** The edited flag is
 *    the comparison against it, so storing only the newest take makes the box differ from it the
 *    instant a second clip lands, and flags an untouched answer as edited.
 * 4. **A whole-section take is filed under no question.** It covers a dozen of them; choosing one
 *    would be the app inventing an attribution only the researcher can make.
 *
 * ── AND ONE RULE ABOUT WORDS, BECAUSE TWO CLIENTS SAY THEM ───────────────────────────────────────
 *
 * A researcher moves between the phone and the browser mid-workshop. The refusal sentences here end
 * with the same clause the web's do — "The clip is saved and transcribed later." — because a refusal
 * that did not say so reads as the recording having been thrown away, which is the one thing a
 * person sitting with an artisan cannot check and cannot undo.
 */
class QuestionnaireTranscriptsTest {

    // ── 1. Clip keys ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a section key is the web's key, character for character`() {
        // Both clients write this into a caption the server parses, and have since before this
        // feature. A second spelling is how the two quietly stop agreeing about one recording.
        assertEquals("section:abc", questionnaireSectionClipKey("abc"))
        assertTrue(isQuestionnaireSectionClipKey("section:abc"))
    }

    @Test
    fun `a question id is not a section key`() {
        assertFalse(isQuestionnaireSectionClipKey("clx123abc"))
    }

    // ── 2. Where the words go ────────────────────────────────────────────────────────────────────

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
        // Rule 2. The researcher stopped the recorder when the artisan paused and started it again
        // when she resumed; the second take is the rest of the sentence.
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
        // Rule 3, stated as the thing a caller must write back. `Written.merged` is both what goes in
        // the box and what is recorded as the machine's words, which is why there is one field and
        // not two: storing the fragment instead would flag an untouched answer as edited.
        val outcome = questionnaireTranscriptOutcome("q1", "second", "first", "first")
        val merged = (outcome as QuestionnaireTranscriptOutcome.Written).merged
        assertEquals("first second", merged)
        assertEquals(false, questionnaireTranscriptEdited(merged, merged))
    }

    @Test
    fun `a box the researcher typed into is OFFERED the transcript, never overwritten`() {
        // Rule 1 — the load-bearing one. The box holds words nobody dictated, so nothing is written.
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
        // "Edited" means "differs from what this screen last put there" — not "holds something". A
        // researcher who has touched nothing must not be asked to approve a second take.
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
        // Rule 2's other half. The accept button exists precisely because the box holds a person's
        // writing, so it must not be the thing that deletes it.
        assertEquals(
            "my own words the offered take",
            questionnaireAcceptOffer("my own words", "the offered take")
        )
    }

    // ── 3. A whole-section take ──────────────────────────────────────────────────────────────────

    @Test
    fun `a section take is never filed under a question's answer`() {
        // Rule 4. `SectionOnly` carries the text and no answer key at all, so a caller CANNOT write
        // it into a box by accident — the type refuses it rather than a comment asking nicely.
        val outcome = questionnaireTranscriptOutcome("section:s1", "the whole sitting", "", null)
        assertEquals(QuestionnaireTranscriptOutcome.SectionOnly("the whole sitting"), outcome)
    }

    @Test
    fun `a section take is offered nothing and overwrites nothing, even with text on screen`() {
        // The section branch returns BEFORE the edited comparison, so an answer box somewhere in the
        // section holding typing cannot turn a section take into an offer against one question.
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

    // ── 4. The flag ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no machine text means NO flag at all, not "Not edited"`() {
        // THE ONE THAT MATTERS. An answer nobody dictated has no machine words to have departed
        // from, and stamping "Not edited" on a hand-typed sentence credits a provider with a
        // researcher's words. The migration that added `MediaFile.transcriptEditedAt` refuses to
        // backfill `false` for the identical reason.
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
        // A trailing newline a researcher never typed — a soft keyboard's autocorrect, a paste — is
        // not somebody correcting a transcript, and flagging it as one would make the flag noise.
        assertEquals(false, questionnaireTranscriptEdited("a sentence", "  a sentence\n"))
    }

    // ── 5. The refusals, and the clause both of them carry ───────────────────────────────────────

    @Test
    fun `both refusals say the clip is still saved, because it is`() {
        // The queue path is untouched: the clip uploads with the interview and is transcribed later.
        // A refusal that did not say so reads as the recording having been thrown away.
        listOf(questionnaireNoWorkshopLine(), questionnaireClipTooLongLine()).forEach {
            assertTrue(it, it.contains(QUESTIONNAIRE_CLIP_KEPT_CLAUSE))
        }
    }

    @Test
    fun `the no-workshop refusal names the workshop picker and not a password or a signal`() {
        // The next move is on this screen, above this box. Anything vaguer sends a researcher to
        // check a connection that is fine.
        assertTrue(questionnaireNoWorkshopLine().contains("design workshop"))
    }

    @Test
    fun `the byte ceiling is checked against the file and not against the clock`() {
        assertFalse(questionnaireClipTooLongToDictate(6L * 1024 * 1024, 6L * 1024 * 1024))
        assertTrue(questionnaireClipTooLongToDictate(6L * 1024 * 1024 + 1, 6L * 1024 * 1024))
    }

    // ── 6. The take ceiling ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the recorder's ceiling is the web's fifteen minutes and fits in an Int`() {
        // `MediaRecorder.setMaxDuration` takes an Int of milliseconds. The constant is a Long so the
        // arithmetic in its KDoc stays honest, and this pins that narrowing it is lossless.
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
