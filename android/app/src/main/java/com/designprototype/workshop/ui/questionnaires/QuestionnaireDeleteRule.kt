package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.ui.FieldPermissions

/**
 * THE OWNER'S DELETE RULE (21 Oct 2026): deleting questionnaire items — forms, questions, sittings,
 * recordings — is for content admins only (Admin, Master Admin, Ministry Admin), on every path. The
 * server refuses everybody else, so a Remove offered to an owner only ends in a refusal, or in a
 * queued step refused later. One function, so every control on these screens asks the same thing.
 */
fun mayDeleteQuestionnaireItems(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

/**
 * Whether removing [question] retires it rather than deleting it (M17, 21 Oct 2026). An answer
 * counts when it has text OR notes, which is the server's rule: notes-only rows are kept, so the
 * question they hang off is retired. Blank rows (no text, no notes) are cleared by the server first.
 */
fun questionRemovalRetires(question: CustomQuestionDto, entries: List<CustomEntryDto>): Boolean =
    question.hasAnswers || entries.any { entry ->
        entry.answers.any {
            it.questionId == question.id &&
                (!it.answerText.isNullOrBlank() || !it.notes.isNullOrBlank())
        }
    }
