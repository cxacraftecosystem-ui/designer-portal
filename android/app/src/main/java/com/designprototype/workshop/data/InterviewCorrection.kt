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
 * question id to label) rather than by an id, and every part of an answer the correction sends is
 * compared (the answer and its note). Things both sides already agree on are left out, and answers
 * the other interviewer added that this correction does not touch are not listed: sending this
 * correction leaves them as they are.
 *
 * [changedKeys] is the set the queued correction actually sends ([PendingEntry.changedKeys]); null
 * means the whole body is sent, as from an older build. An EMPTY review therefore means the server
 * already holds everything this correction would write, which the outbox reads as "it already
 * landed" (a PATCH whose answer was lost) and sends again without asking.
 */
fun interviewCorrectionReview(
    mine: QuestionnaireInterviewUpdateRequest,
    record: QuestionnaireInterviewDetailDto,
    questionLabels: Map<String, String>,
    changedKeys: Set<String>? = null,
): StaleCorrectionReview {
    val fields = mutableListOf<StaleCorrectionField>()
    fun sends(key: String) = changedKeys == null || key in changedKeys
    fun compare(label: String, yours: String?, theirs: String?) {
        if (yours == null) return
        if (yours.trim() == theirs.orEmpty().trim()) return
        fields += StaleCorrectionField(label, yours.trim(), theirs.orEmpty().trim())
    }
    if (sends("title")) compare("Title", mine.title, record.title)
    if (sends("place")) compare("Place", mine.place, record.place)
    if (sends("language")) compare("Language", mine.language, record.language)
    if (sends("notes")) compare("Notes", mine.notes, record.notes)
    if (sends("status")) compare("Review status", mine.status?.let(::statusWords), statusWords(record.status))
    fun compareLink(label: String, yours: String?, theirs: String?) {
        if (yours.orEmpty() == theirs.orEmpty()) return
        fields += StaleCorrectionField(
            label,
            if (yours.isNullOrBlank()) "" else "The one you picked",
            if (theirs.isNullOrBlank()) "" else "A different one",
        )
    }
    if (sends("workshopId") && (mine.workshopId != null || changedKeys != null)) {
        compareLink("Workshop", mine.workshopId, record.workshopId)
    }
    if (sends("designWorkshopId") && (mine.designWorkshopId != null || changedKeys != null)) {
        compareLink("Design & prototype workshop", mine.designWorkshopId, record.designWorkshopId)
    }
    mine.location?.takeIf { sends("location") }?.let { wanted ->
        val now = record.location
        if (now == null || now.latitude != wanted.latitude || now.longitude != wanted.longitude) {
            fun place(lat: Double, lng: Double) = "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lng)
            fields += StaleCorrectionField(
                "Location",
                place(wanted.latitude, wanted.longitude),
                now?.let { place(it.latitude, it.longitude) }.orEmpty(),
            )
        }
    }
    mine.artisanIds?.takeIf { sends("artisanIds") }?.let { wanted ->
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
    mine.responses.orEmpty().takeIf { sends("responses") }?.forEach { answer ->
        val label = questionLabels[answer.questionId]?.takeIf { it.isNotBlank() } ?: "An answer"
        val now = answersNow[answer.questionId]
        compare(label, answer.answerText.orEmpty(), now?.answerText)
        compare("$label (note)", answer.notes, now?.notes)
    }
    return StaleCorrectionReview(fields, record.updatedAt)
}

/** A review status, worded as the web's `StatusBadge` words it. */
private fun statusWords(status: String): String = when (status.uppercase(java.util.Locale.ROOT)) {
    "DRAFT" -> "Draft"
    "PENDING" -> "Pending"
    "APPROVED" -> "Approved"
    "REJECTED" -> "Rejected"
    "NEEDS_REVISION" -> "Needs revision"
    else -> status.replace('_', ' ').lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
}

/** "B3. What tools are used…" — how a question is named in "Review changes". */
fun interviewQuestionLabel(question: QuestionnaireQuestionDto): String =
    listOfNotNull(
        "${question.sectionCode}${question.sortOrder}".takeIf { it.isNotBlank() },
        question.prompt.takeIf { it.isNotBlank() },
    ).joinToString(". ")

/**
 * Can this interview correction be queued? Only when the interview it was composed against carries
 * its version: the version is what makes the server refuse the correction if another interviewer
 * answered since. Without it the answer diff would replay bare and could drop their answers, so the
 * correction is refused in the room instead ([INTERVIEW_CORRECTION_NEEDS_SIGNAL]).
 */
fun interviewCorrectionCanQueue(baseUpdatedAt: String?): Boolean = !baseUpdatedAt.isNullOrBlank()

/** Said when [interviewCorrectionCanQueue] is false. The answers stay on screen. */
const val INTERVIEW_CORRECTION_NEEDS_SIGNAL =
    "This correction cannot be kept on this device to send later, because other interviewers may " +
        "also be answering this interview. Your answers are still on screen — save again when you " +
        "have a signal."
