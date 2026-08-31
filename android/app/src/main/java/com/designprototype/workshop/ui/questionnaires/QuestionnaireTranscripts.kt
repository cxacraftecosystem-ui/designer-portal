package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.ui.appendSpokenToRecord

/**
 * THE QUESTIONNAIRE VOICE NOTE'S QUICK TRANSCRIPT — the rules, with no Compose in sight.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS IS AND WHERE IT CAME FROM
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Owner, 2026-08-30: *"whenever the conversation is recorded even using the voice note, that voice
 * note is then to be streamed to the same api through which the dictate button is facilitated as
 * well, until the elevenlabs, deepgram, or whisper api transcription and translation comes in"*, and
 * *"even when the translation comes in, it should appear in the rich text box with the flag of
 * whether it has been edited by the user or not"*, and — the rule everything below turns on —
 * a later transcript replaces the earlier one *"unless the designer has edited the text, in which
 * case it must not silently overwrite their words. Offer it, do not impose it."*
 *
 * The web built this on 2026-08-31 in `frontend/app/(protected)/questionnaire/page.tsx`. This is the
 * handset's half, and the web IS the specification: a researcher moves between the two apps mid
 * workshop, so the words and the behaviour are copied rather than re-decided.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE RULES ARE HERE AND NOT INSIDE THE FORM
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `QuestionnaireForm` is a ~700-line composable inside an 18,000-line file, and there is no Compose
 * renderer in this project's unit tests. Every judgement below can be wrong in a way that renders
 * perfectly — an untouched answer flagged "Edited", a researcher's typing replaced by a machine's
 * second guess, a section take filed under one question — so the judgements live where a JVM test
 * can call them. `QuestionnaireTranscriptsTest` does. It is the same split, for the same reason, as
 * `components/ui/selectFilter.ts` on the web and `AccessRefusalCopy.kt` here.
 */

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 1. Clip keys
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Recorded clips are keyed by WHAT THEY ANSWER: a question id, or `section:<id>` for one take that
 * covers a whole section.
 *
 * THE SAME KEYING AS THE WEB, and it has been for longer than this feature — `MainActivity` has
 * written `"section:$id"` since the questionnaire form was built, and `frontend`'s
 * `SECTION_CLIP_PREFIX` matches it, so the same caption reaches the server from either client. It is
 * named here rather than left as a literal because three new call sites now branch on it and a
 * fourth spelling of `"section:"` is how the two clients quietly stop agreeing.
 */
const val QUESTIONNAIRE_SECTION_CLIP_PREFIX = "section:"

fun questionnaireSectionClipKey(sectionId: String): String =
    "$QUESTIONNAIRE_SECTION_CLIP_PREFIX$sectionId"

fun isQuestionnaireSectionClipKey(key: String): Boolean =
    key.startsWith(QUESTIONNAIRE_SECTION_CLIP_PREFIX)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 2. The ceiling on a take
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * How long one questionnaire take may run.
 *
 * ── WHY A CEILING EXISTS AT ALL, AND WHY IT IS THIS NUMBER ──────────────────────────────────────
 *
 * `AudioClipRecorder` ran until somebody pressed Stop, so a recorder left going through a lunch
 * break produced a file whose only ceiling was the phone's storage. That was survivable while the
 * clip only ever went to the media queue. It is not survivable now that the same bytes are posted to
 * `POST /design-workshops/{id}/dictate`, which refuses anything over
 * [com.designprototype.workshop.data.DW_DICTATION_MAX_BYTES] — six megabytes — because uploading
 * first and learning that afterwards is a failure paid for on a village connection.
 *
 * FIFTEEN MINUTES IS THE WEB'S NUMBER (`CLIP_MAX_MS`), taken rather than re-derived. It is longer
 * than [com.designprototype.workshop.data.DW_DICTATION_MAX_MILLIS], the four-minute cap on a
 * DICTATION, and deliberately so: a dictation is one person composing one field and four minutes is
 * generous for that, whereas this is an artisan answering a section and cutting her off at four
 * minutes would be the app editing the interview.
 *
 * ── AND IT STOPS THE RECORDER, IT DOES NOT DISCARD THE CLIP ─────────────────────────────────────
 *
 * Hitting the ceiling ends the take and keeps every second of it — the clip uploads and the queue
 * transcribes it exactly as before. It is ANNOUNCED rather than left to look like a crash
 * ([questionnaireClipCapLine]), because a recorder that stops by itself with nothing said is
 * indistinguishable from one that failed.
 *
 * ── THE ONE PLACE THIS DIVERGES FROM THE WEB, STATED RATHER THAN HIDDEN ─────────────────────────
 *
 * The browser pins its encoder to 32 kbps so that the duration cap IS a size guarantee, because
 * Chrome's Opus default is around 128 kbps and Safari's differs again. This recorder is not
 * re-tuned to match: `createAudioRecorder` produces 44.1 kHz / 96 kbps AAC, which is the quality the
 * INTERVIEW ARTEFACT has always been captured at, and dropping it to 32 kbps to make one arithmetic
 * argument tidy would degrade every recording this project keeps — including the ones the queue
 * transcribes, which is still the path that produces the refined, translated text. At 96 kbps
 * fifteen minutes is about 10.8 MB, which is OVER the dictation ceiling, and that is why
 * [questionnaireClipTooLongToDictate] exists and is checked against the ACTUAL FILE: the duration
 * cap bounds the storage cost, the byte check decides whether an instant transcript is possible, and
 * a take too long for one is still saved and still transcribed by the other.
 */
const val QUESTIONNAIRE_CLIP_MAX_MILLIS: Long = 15L * 60L * 1000L

/** What the recorder says when it stopped itself. See [QUESTIONNAIRE_CLIP_MAX_MILLIS]. */
fun questionnaireClipCapLine(): String =
    "Recording stopped at ${QUESTIONNAIRE_CLIP_MAX_MILLIS / 60000} minutes. The take is kept — " +
        "record again to continue."

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 3. Why an instant transcript was not attempted
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The two sentences that refuse an instant transcript, and the clause both of them end with.
 *
 * **"The clip is saved and transcribed later." IS NOT RE-ASSURANCE, IT IS THE FACT.** The queue path
 * is untouched by any of this: the clip uploads with the interview and the refined, translated
 * transcript arrives hours later exactly as it always did. A refusal that did not say so would read
 * as the recording having been thrown away — which is the one thing a researcher sitting with an
 * artisan cannot check and cannot undo. The web prints the identical clause on both of its refusals
 * and its own spec asserts that it appears at least twice.
 */
const val QUESTIONNAIRE_CLIP_KEPT_CLAUSE = "The clip is saved and transcribed later."

/**
 * No design workshop named on this interview, so NO REQUEST IS MADE AT ALL.
 *
 * ── THE CONSENT DECISION, AND IT IS THE WHOLE REASON THIS BRANCH EXISTS ─────────────────────────
 *
 * `POST /design-workshops/{id}/dictate` is the only route in this application that sends audio to a
 * third-party provider under a gate, and its gate is `DesignWorkshop.dictationConsent` — per
 * workshop, because a consent given for one cluster would silently cover the next one and the
 * artisan whose voice it is changes between them. So the interview's own design workshop is what
 * goes in the URL, and where the picker is empty — which is legitimate; an interview is often taken
 * outside any design workshop — nothing is sent. Reaching for the id-less route (which still exists
 * and answers 410) or inventing a fallback id would be a send with nobody's consent behind it.
 */
fun questionnaireNoWorkshopLine(): String =
    "Instant transcript needs a design workshop named above. $QUESTIONNAIRE_CLIP_KEPT_CLAUSE"

/**
 * The clip is bigger than the dictation route will accept.
 *
 * CHECKED AGAINST THE ACTUAL FILE and not against elapsed time — `setAudioEncodingBitRate` is a hint
 * an encoder may miss, and on this recorder a full-length take is expected to exceed the ceiling
 * anyway (see [QUESTIONNAIRE_CLIP_MAX_MILLIS]). Saying it plainly beats spending the upload to be
 * told, which on a district-town connection is minutes of somebody's afternoon.
 */
fun questionnaireClipTooLongLine(): String =
    "Too long for an instant transcript. $QUESTIONNAIRE_CLIP_KEPT_CLAUSE"

/** Whether this finished clip may be posted for an instant transcript. */
fun questionnaireClipTooLongToDictate(bytes: Long, maxBytes: Long): Boolean = bytes > maxBytes

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 4. Where the words go
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * What to do with one transcript that has just come back.
 *
 * Three outcomes, and the interesting one is [Offered]. See [questionnaireTranscriptOutcome].
 */
sealed interface QuestionnaireTranscriptOutcome {
    /**
     * Put [merged] in the answer box AND record it as the machine's own copy.
     *
     * BOTH, and the second half is what makes the edited flag mean anything: the flag is the
     * comparison between the box and this value, so writing one without the other would flag an
     * untouched answer as edited the instant it landed.
     */
    data class Written(val merged: String) : QuestionnaireTranscriptOutcome

    /**
     * Hold [text] back and ASK. The box holds words a person wrote and this is the owner's rule made
     * literal: offer it, do not impose it.
     */
    data class Offered(val text: String) : QuestionnaireTranscriptOutcome

    /**
     * A whole-section take. Record [merged] as the section's machine text and write into NO answer
     * box at all — see [questionnaireTranscriptOutcome] for why that is not an oversight to fix.
     */
    data class SectionOnly(val merged: String) : QuestionnaireTranscriptOutcome
}

/**
 * Put the machine's words where they belong, and never over a person's own.
 *
 * ── A QUESTION KEY: APPENDED, NEVER SUBSTITUTED ─────────────────────────────────────────────────
 *
 * Two clips against one question are two parts of one answer — a researcher stops the recorder when
 * the artisan pauses and starts it again when she resumes, so the second take is the rest of the
 * sentence and not a better version of the first. [appendSpokenToRecord] is the joiner every dictated
 * box in this app already uses ("one space, only where one is missing"), reused rather than
 * re-decided, so the microphone in a field's own toolbar and the voice note behave identically.
 *
 * ── WHERE THE BOX HAS BEEN EDITED, NOTHING IS WRITTEN AT ALL ────────────────────────────────────
 *
 * "Edited" means: the box holds something, and that something is not what this page last put there.
 * An EMPTY box and a box still holding exactly the machine's words both count as untouched — there
 * is nothing of anybody's to lose in either. Trimmed on both sides of the comparison, because a
 * trailing newline a researcher never typed is not an edit.
 *
 * ── `machineText` IS SET TO THE WHOLE MERGED VALUE, NOT TO THE NEW FRAGMENT ─────────────────────
 *
 * It is what the edited flag compares the box against. Storing only the last take would make the box
 * differ from it the instant a second clip landed, and flag an untouched answer as edited.
 *
 * ── A SECTION KEY HAS NO BOX, AND THAT IS NOT AN OVERSIGHT ──────────────────────────────────────
 *
 * A whole-section take covers a dozen questions, so there is no single answer it is the answer to.
 * Writing it into the first question would file a section's worth of conversation under one prompt;
 * splitting it across the section's boxes would be the app guessing at an attribution only the
 * researcher can make. It is shown under the section recorder instead, where it can be read.
 * A section's takes accumulate the same way, so a second take does not erase the first.
 */
fun questionnaireTranscriptOutcome(
    key: String,
    text: String,
    /** What is in the answer box RIGHT NOW — never a value captured when Record was pressed. */
    inBox: String,
    /** The machine's own words as last written into that box, or null if it never wrote any. */
    previousMachine: String?,
): QuestionnaireTranscriptOutcome {
    if (isQuestionnaireSectionClipKey(key)) {
        return QuestionnaireTranscriptOutcome.SectionOnly(
            appendSpokenToRecord(previousMachine.orEmpty(), text)
        )
    }
    val edited = inBox.isNotBlank() && inBox.trim() != previousMachine.orEmpty().trim()
    if (edited) return QuestionnaireTranscriptOutcome.Offered(text)
    return QuestionnaireTranscriptOutcome.Written(appendSpokenToRecord(inBox, text))
}

/**
 * Accepting an offer: the offered text APPENDED to what the researcher wrote.
 *
 * ADDED TO THE ANSWER, NOT SUBSTITUTED FOR IT. The offer only exists because the box holds words a
 * person wrote, so the button that accepts it must not be the one control on this screen that
 * deletes them — "offer it, do not impose it" is not satisfied by asking first and then overwriting.
 * Appending means neither branch of the whole feature can lose a syllable.
 *
 * **AND THE CALLER MUST LEAVE `machineText` ALONE ON THIS PATH.** The box then holds the
 * researcher's words AND the machine's, so it IS edited and the flag must go on saying so. Updating
 * the machine's copy here would relabel a mixed answer as untouched machine output, which is exactly
 * the claim the flag exists to prevent. That is why this returns only the merged answer and not a
 * second value: there is nothing else to write.
 */
fun questionnaireAcceptOffer(inBox: String, offered: String): String =
    appendSpokenToRecord(inBox, offered)

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 5. The flag
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Has a person changed the machine's words? THREE ANSWERS, and the third is not a bug.
 *
 * ── WHY IT IS DERIVED AND NOT STORED ────────────────────────────────────────────────────────────
 *
 * "Has a human changed this" is unanswerable from the answer alone: a researcher who types an answer
 * out by hand and one who accepts a transcript verbatim both end up holding a string. Keeping the
 * machine's own copy is the only thing that makes the two distinguishable — which is also what lets
 * a second take be offered rather than imposed.
 *
 * ── NULL MEANS "NOTHING TO SAY", AND STAMPING "Not edited" THERE WOULD BE A LIE ─────────────────
 *
 * A box nobody dictated into has no machine text to have departed from. Drawing "Not edited" on a
 * hand-typed sentence would credit a provider with a researcher's words, which is the mirror image
 * of the failure the flag was asked for. The migration that added `MediaFile.transcriptEditedAt`
 * refuses to backfill `false` for the identical reason, in its own words: NULL must be read as "not
 * stated" and never as "never edited".
 */
fun questionnaireTranscriptEdited(machine: String?, current: String): Boolean? {
    if (machine == null) return null
    return current.trim() != machine.trim()
}

/**
 * The two words, or null for "draw nothing".
 *
 * A WORD AND NEVER A COLOUR ALONE — house rule 5. The web draws a chip with an icon and the same two
 * words (`EditedFlag` in `components/richtext/MarkdownDocument.tsx`); this returns the words so the
 * handset's chip cannot come to say something different from the browser's.
 */
fun questionnaireEditedFlagLabel(edited: Boolean?): String? = when (edited) {
    true -> "Edited"
    false -> "Not edited"
    null -> null
}

/**
 * The one line above an offer, and it states the only thing a person needs before pressing either
 * button: their own words survive whichever they choose.
 *
 * The web's wording, verbatim, and the buttons below it are "Add to answer" and "Discard" — not "Use
 * this", because the accept path APPENDS, and a button whose word implies replacement would have a
 * person expecting to lose their edits, or worse, pressing Discard to protect words that were never
 * at risk.
 */
const val QUESTIONNAIRE_OFFER_LINE = "Another take was transcribed. Your words are kept either way."
const val QUESTIONNAIRE_OFFER_ACCEPT = "Add to answer"
const val QUESTIONNAIRE_OFFER_DISCARD = "Discard"

/** Said while a clip is in flight, because silence in the box reads as a recorder that ate the take. */
const val QUESTIONNAIRE_TRANSCRIBING_LINE = "Transcribing…"
