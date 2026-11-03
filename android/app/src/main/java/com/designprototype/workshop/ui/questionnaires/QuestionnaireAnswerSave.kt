package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.CustomAnswerDto
import com.designprototype.workshop.data.CustomAnswerSaveResultDto

data class AnswerSaveOutcome(
    val baseline: Map<String, Pair<String, String>>,
    val locked: Set<String>,
    val problems: Map<String, String>,
    val message: String,
)

fun answerSaveOutcome(
    result: CustomAnswerSaveResultDto,
    sent: Collection<String>,
    baseline: Map<String, Pair<String, String>>,
    locked: Set<String>,
    previousProblems: Map<String, String>,
    me: String?,
    admin: Boolean,
): AnswerSaveOutcome {
    val rejected = result.saved.rejected.associate { it.questionId to it.message.ifBlank { "This answer was not saved." } }
    val nextBaseline = baseline + result.answers.associate { it.questionId to (it.answerText.orEmpty() to it.notes.orEmpty()) }
    val nextLocked = if (admin) {
        emptySet()
    } else {
        locked + othersAnswers(result.answers, me) +
            result.saved.rejected.filter { it.code == "ANSWER_OWNED_BY_OTHER" }.map { it.questionId }
    }
    val problems = previousProblems.filterKeys { it !in sent } + rejected
    val savedCount = result.saved.created + result.saved.updated
    val message = buildString {
        append(if (savedCount == 1) "Saved 1 answer." else "Saved $savedCount answers.")
        if (result.saved.unchanged > 0) append(" ${result.saved.unchanged} were already recorded.")
        if (rejected.isNotEmpty()) {
            append(if (rejected.size == 1) " 1 was not saved; see the marked question." else " ${rejected.size} were not saved; see the marked questions.")
        }
    }
    return AnswerSaveOutcome(nextBaseline, nextLocked, problems, message)
}

fun othersAnswers(answers: List<CustomAnswerDto>, me: String?): Set<String> =
    answers
        .filter { !it.answerText.isNullOrBlank() }
        .filter { !it.answeredById.isNullOrBlank() && it.answeredById != me }
        .map { it.questionId }
        .toSet()
