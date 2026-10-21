package com.designprototype.workshop.ui.questionnaires

/*
 * What a questionnaire's screen says about the connection (2026-10-17). Pure strings, pinned by
 * `QuestionnaireOfflineCopyTest`, so the two actions that need a signal say so in the same words on
 * the control and when tapped, and the one that does not says what happens next.
 *
 * WHY TWO STAY ONLINE (recorded in docs/OPEN_FINDINGS.md):
 *  - Uploading an edited workbook replaces this questionnaire's questions with the file's, and its
 *    answer is the report of which questions were kept, reworded or retired. Applied hours later it
 *    would be applied to whatever the questionnaire holds by then — questions somebody added on the
 *    web in between would be retired as "not in the file" — and the report would reach nobody.
 *  - A sitting's answers are saved against the questionnaire's current questions and are refused for
 *    a retired one, which only the server can tell, so they are not kept offline; a sitting started
 *    offline could not be answered until there was a signal anyway.
 * Copying a questionnaire as a template changes nothing on the original, so it is kept on the phone
 * and made when there is a connection.
 */

/** Under "Upload an edited workbook", always. */
const val WORKBOOK_UPLOAD_NEEDS_CONNECTION = "Needs a connection."

/** When "Upload an edited workbook" is tapped with no signal. */
const val WORKBOOK_UPLOAD_OFFLINE =
    "Uploading a workbook needs a connection, so you can see straight away which questions it " +
        "changed and which answers it kept. Edit the file whenever you like and upload it when you " +
        "have a signal."

/** Under "Start a sitting", always. */
const val SITTING_START_NEEDS_CONNECTION = "Starting a sitting needs a connection."

/** When "Start a sitting" is tapped with no signal. */
const val SITTING_START_OFFLINE =
    "Starting a sitting needs a connection, because each answer is checked against the " +
        "questionnaire's current questions as it is saved. Try again when you have a signal."

/** After "Make the copy" with no signal: the copy is kept on this phone. */
const val REUSE_KEPT_OFFLINE =
    "Kept on this phone. The copy is made as soon as there is a connection, and appears in your " +
        "questionnaires then."

/**
 * M12 (21 Oct 2026): a questionnaire created with no signal is known by a temporary id until its
 * queued create lands. Actions that name the questionnaire on the server wait for that.
 */
const val QUESTIONNAIRE_STILL_UPLOADING =
    "This questionnaire is still being sent. Try again once it has uploaded."

/** How often the detail screen checks whether its queued create has landed. */
const val TEMP_ID_POLL_MS = 3_000L

/** How often a saved copy on the answer screen checks whether the signal is back. */
const val CACHED_COPY_RECHECK_MS = 5_000L
