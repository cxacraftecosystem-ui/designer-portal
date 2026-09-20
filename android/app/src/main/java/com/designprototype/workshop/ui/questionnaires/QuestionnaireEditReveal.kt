package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.InterviewResponseDto
import com.designprototype.workshop.data.MediaFileDto
import com.designprototype.workshop.data.QuestionnaireSectionDto

/*
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * OPENING AN INTERVIEW THAT ALREADY HAS SOMETHING IN IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ── THE COMPLAINT, IN THE OWNER'S WORDS ────────────────────────────────────────────────────────────
 *
 * *"when edit page is opened, already existing entries and media do not show up in the respective
 * sections, those should show up while editing as well, on both android and web"*
 *
 * ── WHAT WAS ACTUALLY WRONG, BECAUSE IT WAS NOT THE SEEDING ────────────────────────────────────────
 *
 * The answers were seeded correctly and always have been: `QuestionnaireForm` builds `answers` from
 * `editing?.responses` at composition, and `savedMedia` loads every recording for the interview (and
 * for its siblings covering the same artisan set) a beat later. Nothing was missing. **Nothing was
 * drawn**, because of two display gates that are invisible from a diff:
 *
 *  1. `expandedSections` started EMPTY, and a collapsed section composes none of its inputs. A
 *     researcher opening their own sitting saw a column of closed cards — not their answers, not
 *     their recordings — with the header count line as the only clue that anything was in there.
 *  2. The answer boxes are hidden by default (see [questionnaireAnswerDisplay]).
 *
 * So the fix is not to fetch anything. It is to OPEN WHAT HAS CONTENT, and that judgement is here,
 * outside the 700-line composable, for `QuestionnaireTranscripts`' stated reason: there is no Compose
 * renderer in this project's unit tests, and a rule that decides whether a researcher can see their
 * own work fails silently — the screen renders perfectly either way.
 *
 * ── WHY EVERY SECTION WITH CONTENT, RATHER THAN THE FIRST ONE OR THREE ─────────────────────────────
 *
 * A cap would reintroduce the whole complaint for every section past it, and it would do so
 * invisibly: the researcher whose section K recording is the one behind the cap sees exactly what
 * they saw before this change. The cost is bounded by the sitting rather than by the instrument —
 * researchers title these by the sections they covered ("Section K & L", "Rudraprayag G,H,I,J,Q"),
 * so a real interview opens two or three cards out of twenty, and a section nobody touched stays
 * closed and composes nothing. A brand-new interview has no content at all and therefore opens
 * nothing, which is exactly the create form's behaviour today.
 */

/**
 * Does a saved media item's caption file it under [section]?
 *
 * **THE CAPTION IS THE ONLY LINK.** `MediaFile` carries no section column; this form writes
 * `"Section audio: <code> <title>"` and `"Question audio: <code><n> <prompt>"` at save time and both
 * clients parse those back. Hoisted out of `QuestionnaireForm`, where it was a local function, so
 * that [questionnaireSectionsToReveal] can use the SAME rule the drawing code uses — two spellings
 * of "does this recording belong to section F" is how a recording ends up in the catch-all while its
 * section stays shut.
 *
 * Exact match first, then a resilient prefix fallback, because a prompt or a section title edited
 * after the recording was made would otherwise orphan every clip taken under the old wording.
 */
fun questionnaireCaptionBelongsToSection(caption: String?, section: QuestionnaireSectionDto): Boolean {
    val cap = caption?.trim().orEmpty()
    if (cap.isEmpty()) return false
    val expected = buildSet {
        add("Section audio: ${section.code} ${section.title}".trim())
        section.questions.forEach { q -> add("Question audio: ${q.sectionCode}${q.sortOrder} ${q.prompt}".trim()) }
    }
    if (cap in expected) return true
    if (cap.startsWith("Section audio:")) {
        val rest = cap.removePrefix("Section audio:").trim()
        return rest == section.code || rest.startsWith("${section.code} ")
    }
    if (cap.startsWith("Question audio:")) {
        val rest = cap.removePrefix("Question audio:").trim()
        return rest.startsWith(section.code) && rest.length > section.code.length && rest[section.code.length].isDigit()
    }
    return false
}

/**
 * The sections an edit must OPEN because this sitting already put something in them.
 *
 * A section qualifies on either kind of content, and both are needed:
 *
 *  - **A RECORDED ANSWER** — a response against one of its questions with a non-blank `answerText`
 *    or non-blank `notes`. `notes` counts because it is the researcher's own words typed against
 *    that question; a section whose only content is a note would otherwise stay shut over it.
 *  - **A SAVED RECORDING** — media whose caption files it under this section. On this instrument the
 *    recordings ARE the interview: sections are captured as one whole-section take with the answer
 *    boxes hidden, so a correctly recorded sitting has ZERO response rows and many media rows. A
 *    rule that looked only at responses would leave exactly the best-conducted interviews looking
 *    empty, which is the same miscount the shared-entry banner makes.
 *
 * ⚠ **ONLY SECTIONS THAT ARE ACTUALLY DRAWN.** `QuestionnaireForm` renders a section only when it
 * has at least one ACTIVE question, so naming a retired one here would put an id in the expanded set
 * that nothing can ever collapse again — invisible, but it makes the set a lie about the screen.
 * Answers are counted against active questions for the same reason: a response to a question that
 * has since been retired is not on screen to be revealed.
 *
 * [savedMedia] is the caller's already-filtered list and is empty on a create, so a new interview
 * opens nothing. Order follows [sections], so a caller that turns this into a list gets the
 * instrument's own order rather than a hash order.
 */
fun questionnaireSectionsToReveal(
    sections: List<QuestionnaireSectionDto>,
    responses: List<InterviewResponseDto>,
    savedMedia: List<MediaFileDto>,
): Set<String> {
    val answered = responses
        .filter { questionnaireAnswerPlain(it.answerText).isNotBlank() || !it.notes.isNullOrBlank() }
        .map { it.questionId }
        .toSet()
    return sections.filter { section ->
        val active = section.questions.filter { it.isActive }
        if (active.isEmpty()) return@filter false
        active.any { it.id in answered } ||
            savedMedia.any { questionnaireCaptionBelongsToSection(it.caption, section) }
    }.map { it.id }.toSet()
}

/**
 * What to draw for one question's answer, given the reader's stored capture preference.
 *
 * ── THE PREFERENCE IS NOT TOUCHED, AND THIS IS THE ARGUMENT FOR DRAWING TEXT RATHER THAN A BOX ─────
 *
 * *"Do not display answer text boxes"* is ON by default and it is the reader's own choice about
 * CAPTURE: these interviews are conducted by talking through a whole section with the artisan, and a
 * column of empty text boxes under the record button is noise in front of somebody who is
 * interviewing. Flipping that default — or quietly flipping the switch on an edit — would be the app
 * overruling a control the reader can see is on, and it would put an EDITOR over words a colleague
 * typed at the moment the reader only wanted to read them back.
 *
 * **But a preference about empty boxes is not a claim that recorded answers should be invisible**,
 * and read as one it is the second half of the owner's complaint: a researcher opening their own
 * interview could not see a single answer they had typed. So an answer that EXISTS is drawn as text,
 * read-only, beside the recorder; the switch keeps its meaning (no box), the words are on screen,
 * and turning the switch off is still the one way to type. On a pure-audio sitting — the common case
 * this default was chosen for — there are no stored answers, so nothing new is drawn at all and the
 * capture screen is exactly what it was.
 *
 * [current] IS THE LIVE VALUE, not the stored column, and deliberately: a researcher who turns the
 * switch off, types, and turns it back on must see what they just typed rather than the server's
 * older copy. It is flattened through [questionnaireAnswerPlain] because the column may hold a rich
 * document, and `{"blocks":[{"kind":"PARAGRAPH"…` is not an answer — it is the braces, printed where
 * an artisan's words belong.
 */
enum class QuestionnaireAnswerDisplay {
    /** The rich text box, with its microphone and its transcript panel. */
    EDITOR,

    /** The recorded words, read-only, because the boxes are hidden and this question has an answer. */
    RECORDED,

    /** Nothing: the boxes are hidden and there is nothing recorded to show. */
    NONE,
}

fun questionnaireAnswerDisplay(hideAnswers: Boolean, current: String?): QuestionnaireAnswerDisplay =
    when {
        !hideAnswers -> QuestionnaireAnswerDisplay.EDITOR
        questionnaireAnswerPlain(current).isNotBlank() -> QuestionnaireAnswerDisplay.RECORDED
        else -> QuestionnaireAnswerDisplay.NONE
    }

/**
 * The one line under a read-only answer that says why it cannot be typed in and what to do about it.
 *
 * WORDS, NOT A DISABLED BOX. A greyed-out text field would say "this answer is locked", which is
 * false — nothing about this record is read-only to a researcher who may edit it; the boxes are off
 * because they asked for them to be off. The remedy names the control by the exact label it carries
 * at the top of the form, so it can be found by reading rather than by hunting.
 */
const val QUESTIONNAIRE_RECORDED_ANSWER_HINT =
    "Answer boxes are hidden. Switch “Do not display answer text boxes” off above to edit this answer."
