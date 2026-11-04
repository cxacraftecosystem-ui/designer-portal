package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.QuestionnaireSaveRefusal

data class QuestionnaireMergeOffer(
    val targetId: String,
    val targetTitle: String,
    val heading: String,
    val body: String,
    val confirmLabel: String,
)

fun questionnaireMergeOffer(
    refusal: QuestionnaireSaveRefusal,
    answeredQuestions: Int,
    pendingUploads: Int,
): QuestionnaireMergeOffer? {
    if (!refusal.isDuplicateSet) return null
    val id = refusal.holderId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val title = refusal.holderTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val moving = buildList {
        add(if (answeredQuestions == 1) "1 typed answer" else "$answeredQuestions typed answers")
        add("every saved recording")
        if (pendingUploads > 0) {
            add(if (pendingUploads == 1) "the 1 file still uploading" else "the $pendingUploads files still uploading")
        }
    }
    return QuestionnaireMergeOffer(
        targetId = id,
        targetTitle = title,
        heading = "“$title” already covers these artisans",
        body = "Another interview already covers these artisans. Move ${moving.joinToString(", ")} into “$title”? This interview is then removed; nothing recorded is deleted.",
        confirmLabel = "Move into “$title”",
    )
}

data class QuestionnaireMergeConflictReport(
    val heading: String,
    val questions: List<String>,
    val remedy: String,
)

fun questionnaireMergeConflictReport(refusal: QuestionnaireSaveRefusal): QuestionnaireMergeConflictReport? {
    if (!refusal.isAnswerConflict) return null
    val labels = refusal.conflicts.map { it.label }
    if (labels.isEmpty()) {
        return QuestionnaireMergeConflictReport(
            heading = "Nothing was moved.",
            questions = emptyList(),
            remedy = refusal.message,
        )
    }
    return QuestionnaireMergeConflictReport(
        heading = if (labels.size == 1) {
            "Nothing was moved — 1 question is answered differently:"
        } else {
            "Nothing was moved — ${labels.size} questions are answered differently:"
        },
        questions = labels,
        remedy = "Open each one, keep the wording that is right, then merge.",
    )
}

fun questionnaireMergedSentence(targetTitle: String): String =
    "Moved into “$targetTitle”. This interview was removed."
