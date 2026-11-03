package com.designprototype.workshop.data

val DW_INTERVIEW_LANGUAGES: List<String> = StandardValues.LANGUAGES.map { it.label }

const val DW_INTERVIEW_LANGUAGE_PLACEHOLDER: String = "Select language"

fun dwInterviewLanguageOptions(current: String?): List<Pair<String, String>> =
    StandardValues.languageOptions(current)
