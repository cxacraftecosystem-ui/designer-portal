package com.designprototype.workshop.ui.questionnaires

val QUESTIONNAIRE_KIND_LABELS: Map<String, String> = linkedMapOf(
    "WORKSHOP_INTERVIEW" to "Workshop interview",
    "MARKET_SURVEY" to "Market survey",
)

const val QUESTIONNAIRE_KIND_NOT_STATED = "Kind not stated"

val QUESTIONNAIRE_KIND_STAGE_KEYS: Map<String, String> = mapOf(
    "WORKSHOP_INTERVIEW" to "EXISTING_PRODUCTS_BASELINE",
    "MARKET_SURVEY" to "MARKET_SURVEY_CAPTURE",
)

fun labelForQuestionnaireKind(kind: String?): String {
    if (kind.isNullOrBlank()) return QUESTIONNAIRE_KIND_NOT_STATED
    return QUESTIONNAIRE_KIND_LABELS[kind] ?: kind
}
