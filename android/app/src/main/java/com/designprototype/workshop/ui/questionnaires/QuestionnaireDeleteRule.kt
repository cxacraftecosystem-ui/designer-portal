package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.ui.FieldPermissions

fun mayDeleteQuestionnaireItems(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

fun mayDeleteQuestionnaire(user: UserDto?, ownerId: String?): Boolean =
    FieldPermissions.isContentAdmin(user) ||
        (user != null && !ownerId.isNullOrBlank() && ownerId == user.id)

fun questionRemovalRetires(question: CustomQuestionDto, entries: List<CustomEntryDto>): Boolean =
    question.hasAnswers || entries.any { entry ->
        entry.answers.any {
            it.questionId == question.id &&
                (!it.answerText.isNullOrBlank() || !it.notes.isNullOrBlank())
        }
    }
