package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.ui.appendSpokenToRecord

const val QUESTIONNAIRE_SECTION_CLIP_PREFIX = "section:"

fun questionnaireSectionClipKey(sectionId: String): String =
    "$QUESTIONNAIRE_SECTION_CLIP_PREFIX$sectionId"

fun isQuestionnaireSectionClipKey(key: String): Boolean =
    key.startsWith(QUESTIONNAIRE_SECTION_CLIP_PREFIX)

const val QUESTIONNAIRE_CLIP_MAX_MILLIS: Long = 15L * 60L * 1000L

fun questionnaireClipCapLine(): String =
    "Stopped at ${QUESTIONNAIRE_CLIP_MAX_MILLIS / 60000} minutes. The take is kept — record again to continue."

const val QUESTIONNAIRE_CLIP_KEPT_CLAUSE = "The clip is saved and transcribed later."

fun questionnaireNoWorkshopLine(): String =
    "Instant transcripts need a design workshop named above. $QUESTIONNAIRE_CLIP_KEPT_CLAUSE"

fun questionnaireClipTooLongLine(): String =
    "Too long for an instant transcript. $QUESTIONNAIRE_CLIP_KEPT_CLAUSE"

fun questionnaireClipTooLongToDictate(bytes: Long, maxBytes: Long): Boolean = bytes > maxBytes

sealed interface QuestionnaireTranscriptOutcome {
    data class Written(val merged: String) : QuestionnaireTranscriptOutcome

    data class Offered(val text: String) : QuestionnaireTranscriptOutcome

    data class SectionOnly(val merged: String) : QuestionnaireTranscriptOutcome
}

fun questionnaireTranscriptOutcome(
    key: String,
    text: String,
    inBox: String,
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

fun questionnaireAcceptOffer(inBox: String, offered: String): String =
    questionnaireAnswerAppend(inBox, offered)

fun questionnaireTranscriptEdited(machine: String?, current: String): Boolean? {
    if (machine == null) return null
    return current.trim() != machine.trim()
}

fun questionnaireEditedFlagLabel(edited: Boolean?): String? = when (edited) {
    true -> "Edited"
    false -> "Not edited"
    null -> null
}

const val QUESTIONNAIRE_OFFER_LINE = "Another take was transcribed. Your text is kept."
const val QUESTIONNAIRE_OFFER_ACCEPT = "Add to answer"
const val QUESTIONNAIRE_OFFER_DISCARD = "Discard"

const val QUESTIONNAIRE_TRANSCRIBING_LINE = "Transcribing…"
