package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException

const val QUESTIONNAIRE_DUPLICATE_SET_CODE = "duplicate_artisan_set"

const val QUESTIONNAIRE_MERGE_CONFLICT_CODE = "merge_answer_conflict"

data class QuestionnaireMergeConflictQuestion(
    val questionId: String,
    val sectionCode: String?,
    val prompt: String?,
) {
    val label: String
        get() {
            val code = sectionCode?.trim()?.takeIf { it.isNotEmpty() }
            val text = prompt?.trim()?.takeIf { it.isNotEmpty() } ?: questionId
            return if (code == null) text else "$code — $text"
        }
}

data class QuestionnaireSaveRefusal(
    val message: String,
    val code: String? = null,
    val holderId: String? = null,
    val holderTitle: String? = null,
    val conflicts: List<QuestionnaireMergeConflictQuestion> = emptyList(),
) {
    val isDuplicateSet: Boolean get() = code == QUESTIONNAIRE_DUPLICATE_SET_CODE

    val isAnswerConflict: Boolean get() = code == QUESTIONNAIRE_MERGE_CONFLICT_CODE
}

private val refusalJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun Throwable.questionnaireSaveRefusal(fallback: String): QuestionnaireSaveRefusal {
    val plain = message?.takeIf { it.isNotBlank() } ?: fallback
    val http = this as? HttpException ?: return QuestionnaireSaveRefusal(message = plain)
    if (http.code() != 409) return QuestionnaireSaveRefusal(message = apiErrorMessage(fallback))
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    return questionnaireSaveRefusal(raw, plain)
}

fun questionnaireSaveRefusal(rawBody: String?, fallback: String): QuestionnaireSaveRefusal {
    if (rawBody.isNullOrBlank()) return QuestionnaireSaveRefusal(message = fallback)
    val detail = (runCatching { refusalJson.parseToJsonElement(rawBody) }.getOrNull() as? JsonObject)
        ?.get("detail")
    val obj = detail as? JsonObject
        ?: return QuestionnaireSaveRefusal(
            message = (detail as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        )
    fun text(key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return QuestionnaireSaveRefusal(
        message = text("message") ?: fallback,
        code = text("code"),
        holderId = text("existingInterviewId"),
        holderTitle = text("existingInterviewTitle"),
        conflicts = (obj["conflicts"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            fun field(key: String): String? =
                (row[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            field("questionId")?.let {
                QuestionnaireMergeConflictQuestion(
                    questionId = it,
                    sectionCode = field("sectionCode"),
                    prompt = field("prompt"),
                )
            }
        },
    )
}
