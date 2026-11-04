package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val DW_COPYABLE_STAGES: Set<String> = setOf(
    "INTRODUCTORY_ADMIN_DOCUMENTATION",
    "CLUSTER_CRAFT_BACKGROUND",
    "TRADITIONAL_PROCESS_BASELINE",
    "SURVEY_PLANNING",
)

const val DW_COPIED_SOURCE = "copied"

@Serializable
data class DwCopySourceDto(
    val workshopId: String = "",
    val title: String = "",
    val craftName: String? = null,
    val clusterName: String? = null,
    val date: String? = null,
    val values: Map<String, JsonElement> = emptyMap(),
    val designerName: String? = null,
)

@Serializable
data class DwCopyFieldDto(val key: String = "", val label: String = "", val type: String = "")

@Serializable
data class DwCopySourcesDto(
    val fields: List<DwCopyFieldDto> = emptyList(),
    val own: List<DwCopySourceDto> = emptyList(),
    val reference: List<DwCopySourceDto> = emptyList(),
)

@Serializable
data class DwConfirmCopiedBody(val fields: List<String>)

@Serializable
data class DwConfirmCopiedDto(
    val provenance: Map<String, DwFieldStampDto> = emptyMap(),
    val confirmed: List<String> = emptyList(),
)

fun dwPendingCopies(
    copiedFrom: Map<String, String>,
    stamps: Map<String, DwFieldStampDto>,
    edited: Set<String>,
): Set<String> =
    copiedFrom.keys + stamps.filter { (key, stamp) -> stamp.source == DW_COPIED_SOURCE && key !in edited }.keys

fun dwCopiesAfterPush(
    copiedFrom: Map<String, String>,
    provenance: DwStageProvenanceDto,
    sent: Map<String, String>,
): Pair<Map<String, String>, DwStageProvenanceDto>? {
    val acknowledged = sent.filter { (key, source) -> copiedFrom[key] == source }
    if (acknowledged.isEmpty()) return null
    val stamps = provenance.singleton.toMutableMap()
    for (key in acknowledged.keys) {
        if (stamps[key]?.source != DW_COPIED_SOURCE) stamps[key] = DwFieldStampDto(source = DW_COPIED_SOURCE)
    }
    return (copiedFrom - acknowledged.keys) to provenance.copy(singleton = stamps)
}

fun dwIsEmptyValue(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> true
    is JsonPrimitive -> value.isString && value.content.isBlank()
    is JsonArray -> value.isEmpty()
    is JsonObject -> value.isEmpty()
}

fun dwCopyIntoEmpty(current: Map<String, JsonElement>, source: Map<String, JsonElement>): Map<String, JsonElement> =
    source.filter { (key, _) -> dwIsEmptyValue(current[key]) }

fun dwCopyPreview(value: JsonElement?, limit: Int = 220): String {
    val words = mutableListOf<String>()
    fun walk(node: JsonElement?) {
        when (node) {
            is JsonPrimitive -> if (node.isString) words += node.content
            is JsonArray -> node.forEach { walk(it) }
            is JsonObject -> {
                val text = node["text"]
                if (text is JsonPrimitive && text.isString) words += text.content
                node.forEach { (key, child) -> if (key != "text") walk(child) }
            }
            else -> Unit
        }
    }
    walk(value)
    val clean = words.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    return if (clean.length > limit) clean.take(limit - 1) + "…" else clean
}
