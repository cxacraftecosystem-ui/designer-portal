package com.designprototype.workshop.ui.questionnaires

const val WORKBOOK_UPLOAD_NEEDS_CONNECTION = "Needs a connection."

const val WORKBOOK_UPLOAD_OFFLINE =
    "Uploading a workbook needs a connection, so you can see straight away which questions it " +
        "changed and which answers it kept. Edit the file whenever you like and upload it when you " +
        "have a signal."

const val SITTING_START_NEEDS_CONNECTION = "Starting a sitting needs a connection."

const val SITTING_START_OFFLINE =
    "Starting a sitting needs a connection, because each answer is checked against the " +
        "questionnaire's current questions as it is saved. Try again when you have a signal."

const val REUSE_KEPT_OFFLINE =
    "Kept on this phone. The copy is made as soon as there is a connection, and appears in your " +
        "questionnaires then."

const val QUESTIONNAIRE_STILL_UPLOADING =
    "This questionnaire is still being sent. Try again once it has uploaded."

const val TEMP_ID_POLL_MS = 3_000L

const val CACHED_COPY_RECHECK_MS = 5_000L
