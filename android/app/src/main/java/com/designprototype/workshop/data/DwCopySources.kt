package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * C9 (owner decision, 2026-10-17): the wire shapes and the pure rules behind "Start from an earlier
 * workshop". The card that draws them is `ui/designworkshop/DwCopyFromEarlier.kt`.
 */

/** The stages a designer may start from an earlier workshop — `workshop_copy.COPYABLE_STAGES`. */
val DW_COPYABLE_STAGES: Set<String> = setOf(
    "INTRODUCTORY_ADMIN_DOCUMENTATION",
    "CLUSTER_CRAFT_BACKGROUND",
    "TRADITIONAL_PROCESS_BASELINE",
    "SURVEY_PLANNING",
)

/** The provenance `source` of copied text not yet edited or kept. */
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

/** The boxes holding copied text that is not counted yet: unsent marks, or server marks not edited. */
fun dwPendingCopies(
    copiedFrom: Map<String, String>,
    stamps: Map<String, DwFieldStampDto>,
    edited: Set<String>,
): Set<String> =
    copiedFrom.keys + stamps.filter { (key, stamp) -> stamp.source == DW_COPIED_SOURCE && key !in edited }.keys

/**
 * C9: what a stage's copy marks become once the server has ACCEPTED a push that carried [sent] —
 * the web's `copiesAfterPush`, rule for rule.
 *
 * The marks then live in the server's stamp, so they leave the draft: kept, the first "Keep as is"
 * only dropped the local mark and left the server's `copied` stamp standing, and every later save
 * re-sent the mark. A key leaves only when the draft still holds the SAME source it sent. Each one
 * acknowledged is mirrored into [provenance] as a `copied` stamp (unless it already carries one), so
 * the box stays out of completeness and "Keep as is" confirms it on the server. Null when nothing
 * the push carried is still marked here.
 */
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

/** Whether a stored answer is empty — the only boxes a copy may fill. */
fun dwIsEmptyValue(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> true
    is JsonPrimitive -> value.isString && value.content.isBlank()
    is JsonArray -> value.isEmpty()
    is JsonObject -> value.isEmpty()
}

/** The values a copy writes: the source's, for the boxes that are still empty. */
fun dwCopyIntoEmpty(current: Map<String, JsonElement>, source: Map<String, JsonElement>): Map<String, JsonElement> =
    source.filter { (key, _) -> dwIsEmptyValue(current[key]) }

/** A value as one readable line — rich text flattened to its words. */
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
