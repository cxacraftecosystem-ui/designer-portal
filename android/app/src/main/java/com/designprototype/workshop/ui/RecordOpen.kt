package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object RecordOpen {
    const val UPDATE_LABEL = "Update"

    const val OPEN_LABEL = "Open"

    val VIEWABLE: Set<String> = setOf(
        "ARTISAN", "PRODUCT", "PROCESS", "TOOL", "QUESTIONNAIRE", "MEDIA", "CRAFT", "WORKSHOP"
    )

    fun hasView(modeName: String): Boolean = modeName in VIEWABLE

    fun mayUpdate(modeName: String, user: UserDto?): Boolean {
        if (user == null) return false
        return when (modeName) {
            "CRAFT" -> FieldPermissions.canManageCrafts(user)
            "WORKSHOP" -> FieldPermissions.canManageWorkshops(user)
            "ARTISAN", "PRODUCT", "PROCESS", "TOOL", "QUESTIONNAIRE" -> true
            else -> false
        }
    }

    fun mergedSurvivorId(code: Int, body: String?): String? {
        if (code != 404 || body.isNullOrBlank()) return null
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val detail = root["detail"] as? JsonObject ?: return null
        if ((detail["code"] as? JsonPrimitive)?.content != "artisan_merged") return null
        return (detail["survivorId"] as? JsonPrimitive)?.content?.trim()?.ifBlank { null }
    }

    fun openClickLabel(title: String): String =
        title.trim().takeIf { it.isNotEmpty() }?.let { "$OPEN_LABEL $it" } ?: OPEN_LABEL
}
