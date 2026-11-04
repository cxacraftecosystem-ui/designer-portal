package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

fun interviewCorrectionBody(
    payload: JsonObject,
    changedKeys: Set<String>?,
    baseUpdatedAt: String?,
    cleared: Set<String> = emptySet(),
): JsonObject {
    val sent = payload.filterValues { it != JsonNull } + cleared.associateWith { JsonNull }
    return restrictCorrectionBody(JsonObject(sent), changedKeys, baseUpdatedAt)
}

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

private fun statusWords(status: String): String = when (status.uppercase(java.util.Locale.ROOT)) {
    "DRAFT" -> "Draft"
    "PENDING" -> "Pending"
    "APPROVED" -> "Approved"
    "REJECTED" -> "Rejected"
    "NEEDS_REVISION" -> "Needs revision"
    else -> status.replace('_', ' ').lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
}

fun interviewQuestionLabel(question: QuestionnaireQuestionDto): String =
    listOfNotNull(
        "${question.sectionCode}${question.sortOrder}".takeIf { it.isNotBlank() },
        question.prompt.takeIf { it.isNotBlank() },
    ).joinToString(". ")

fun interviewCorrectionCanQueue(baseUpdatedAt: String?): Boolean = !baseUpdatedAt.isNullOrBlank()

const val INTERVIEW_CORRECTION_NEEDS_SIGNAL =
    "This correction cannot be kept on this device to send later, because other interviewers may " +
        "also be answering this interview. Your answers are still on screen — save again when you " +
        "have a signal."
