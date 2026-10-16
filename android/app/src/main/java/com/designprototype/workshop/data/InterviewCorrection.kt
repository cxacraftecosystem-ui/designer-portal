package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * AN INTERVIEW CORRECTION QUEUED WITH NO SIGNAL (2026-10-17).
 *
 * Interview corrections used to be refused offline because the interview's version did not move when
 * another interviewer wrote answers: answers live in their own table, so a correction composed before
 * their answers passed its precondition and replaced what they wrote. The server now moves the
 * interview's `updatedAt` on every answer write and claims it with a compare-and-set
 * (`records.claim_expected_updated_at`), so the correction is queued like the six record types: only
 * its changed keys, guarded by the version it was composed against, and held for "Review changes"
 * when the interview moved. Pinned by `InterviewCorrectionTest`.
 */

/**
 * The PATCH body a queued interview correction sends. The form's payload is encoded with explicit
 * nulls, and the online save sends none (`ApiClient`'s `explicitNulls = false`), where the route
 * reads a present null on place, language or notes as "clear it". Dropping them here keeps the
 * queued save saying exactly what the online one would have said, before it is narrowed to its
 * changed keys and given its precondition.
 *
 * [cleared] is the exception: a workshop box the person deliberately emptied
 * ([PendingEntry.clearedLinkKeys]) goes as an explicit null, which is how the route un-files it.
 */
fun interviewCorrectionBody(
    payload: JsonObject,
    changedKeys: Set<String>?,
    baseUpdatedAt: String?,
    cleared: Set<String> = emptySet(),
): JsonObject {
    val sent = payload.filterValues { it != JsonNull } + cleared.associateWith { JsonNull }
    return restrictCorrectionBody(JsonObject(sent), changedKeys, baseUpdatedAt)
}

/**
 * "Review changes" for an interview: each thing this correction changes, in words, with the phone's
 * value against the interview's value now. Answers are named by their question ([questionLabels],
 * question id to label) rather than by an id. Things both sides already agree on are left out, and
 * answers the other interviewer added that this correction does not touch are not listed: sending
 * this correction leaves them as they are.
 */
fun interviewCorrectionReview(
    mine: QuestionnaireInterviewUpdateRequest,
    record: QuestionnaireInterviewDetailDto,
    questionLabels: Map<String, String>,
): StaleCorrectionReview {
    val fields = mutableListOf<StaleCorrectionField>()
    fun compare(label: String, yours: String?, theirs: String?) {
        if (yours == null) return
        if (yours.trim() == theirs.orEmpty().trim()) return
        fields += StaleCorrectionField(label, yours.trim(), theirs.orEmpty().trim())
    }
    compare("Title", mine.title, record.title)
    compare("Place", mine.place, record.place)
    compare("Language", mine.language, record.language)
    compare("Notes", mine.notes, record.notes)
    mine.artisanIds?.let { wanted ->
        val names = record.artisans.associate { link -> link.artisanId to (link.artisan?.name ?: "") }
        val now = record.artisans.map { it.artisanId }
        if (wanted.toSet() != now.toSet()) {
            fun describe(ids: List<String>): String {
                val known = ids.mapNotNull { id -> names[id]?.takeIf { it.isNotBlank() } }
                val unknown = ids.size - known.size
                val more = when (unknown) {
                    0 -> null
                    1 -> "1 more artisan"
                    else -> "$unknown more artisans"
                }
                return (known + listOfNotNull(more)).joinToString(", ")
            }
            fields += StaleCorrectionField("Artisans", describe(wanted), describe(now))
        }
    }
    val answersNow = record.responses.associateBy { it.questionId }
    mine.responses.orEmpty().forEach { answer ->
        compare(
            questionLabels[answer.questionId]?.takeIf { it.isNotBlank() } ?: "An answer",
            answer.answerText.orEmpty(),
            answersNow[answer.questionId]?.answerText,
        )
    }
    return StaleCorrectionReview(fields, record.updatedAt)
}

/** "B3. What tools are used…" — how a question is named in "Review changes". */
fun interviewQuestionLabel(question: QuestionnaireQuestionDto): String =
    listOfNotNull(
        "${question.sectionCode}${question.sortOrder}".takeIf { it.isNotBlank() },
        question.prompt.takeIf { it.isNotBlank() },
    ).joinToString(". ")
