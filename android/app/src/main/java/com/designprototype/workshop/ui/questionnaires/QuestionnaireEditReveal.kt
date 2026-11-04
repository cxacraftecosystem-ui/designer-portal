package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.InterviewResponseDto
import com.designprototype.workshop.data.MediaFileDto
import com.designprototype.workshop.data.QuestionnaireSectionDto

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

enum class QuestionnaireAnswerDisplay {
    EDITOR,

    RECORDED,

    NONE,
}

fun questionnaireAnswerDisplay(hideAnswers: Boolean, current: String?): QuestionnaireAnswerDisplay =
    when {
        !hideAnswers -> QuestionnaireAnswerDisplay.EDITOR
        questionnaireAnswerPlain(current).isNotBlank() -> QuestionnaireAnswerDisplay.RECORDED
        else -> QuestionnaireAnswerDisplay.NONE
    }

const val QUESTIONNAIRE_RECORDED_ANSWER_HINT =
    "Answer boxes are hidden. Turn off “Do not display answer text boxes” to edit."
