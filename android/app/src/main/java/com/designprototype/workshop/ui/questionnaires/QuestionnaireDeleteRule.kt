package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.ui.FieldPermissions

/**
 * THE OWNER'S DELETE RULE FOR SITTINGS AND RECORDINGS (21 Oct 2026): deleting or restoring a sitting
 * or a recording is for content admins only (Admin, Master Admin, Ministry Admin), on every path. The
 * server refuses everybody else, so a Delete offered to anyone else only ends in a refusal.
 */
fun mayDeleteQuestionnaireItems(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

/**
 * THE FORM ITSELF (owner, 22 Oct 2026): "the author should be able to edit/delete the questionnaire
 * apart from the admins". A form and everything in it — its sections, its questions (Retire/Delete),
 * the form's Delete and its Restore — is its author's ([ownerId]) or a content admin's, which is the
 * server's `_require_owner`. Designers author their own forms on this handset, so the author's
 * controls are drawn here as on the web; only admin-only authoring stays web-only.
 */
fun mayDeleteQuestionnaire(user: UserDto?, ownerId: String?): Boolean =
    FieldPermissions.isContentAdmin(user) ||
        (user != null && !ownerId.isNullOrBlank() && ownerId == user.id)

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
