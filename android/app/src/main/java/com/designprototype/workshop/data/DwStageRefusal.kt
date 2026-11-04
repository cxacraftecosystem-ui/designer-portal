package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class DwStageRefusal(
    val entityKey: String,
    val rowIndex: Int?,
    val rowKey: String? = null,
    val fieldKey: String,
    val label: String,
    val message: String,
    val drawn: Boolean,
    val held: DwHeld = DwHeld.unrecorded(),
) {
    val address: String
        get() = if (rowIndex == null) entityKey else "$entityKey[$rowIndex]"

    val isRowLevel: Boolean
        get() = fieldKey == DW_ROW_REFUSAL_KEY

    val carryKey: String
        get() = rowKey ?: address

    val sentence: String
        get() = buildString {
            append(if (rowIndex == null) label else "$label (row ${rowIndex + 1})")
            append(": ")
            append(message.trim())
            if (!message.trim().endsWith('.')) append('.')
            if (isRowLevel) return@buildString
            append(' ')
            append(held.sentence)
            append(" What you typed is still on this phone")
            if (drawn) {
                append(" and is still in the box above.")
            } else {
                append(
                    ", but this app version has no box for that question."
                )
            }
        }
}

const val DW_ROW_REFUSAL_KEY: String = "_row"

data class DwHeld(val state: DwHeldState, val text: String) {

    val sentence: String
        get() = when (state) {
            DwHeldState.UNRECORDED -> "The earlier answer was kept online, but it is not shown here."
            DwHeldState.NOTHING -> "There is no earlier answer to this question online."
            DwHeldState.HOLDS -> "The answer saved online is still: “$text”."
        }

    companion object {
        fun unrecorded(): DwHeld = DwHeld(DwHeldState.UNRECORDED, DW_UNRECORDED)
        fun nothing(): DwHeld = DwHeld(DwHeldState.NOTHING, "")
        fun holding(text: String): DwHeld =
            if (text.isBlank()) nothing() else DwHeld(DwHeldState.HOLDS, text)
    }
}

enum class DwHeldState { UNRECORDED, NOTHING, HOLDS }

const val DW_UNRECORDED: String = "UNRECORDED"

data class DwStageRefusalReport(
    val refusals: List<DwStageRefusal> = emptyList(),
    val unplaced: List<String> = emptyList(),
    val droppedCustomKeys: List<String> = emptyList(),
    val recordedAt: String? = null,
) {
    val isEmpty: Boolean
        get() = refusals.isEmpty() && unplaced.isEmpty() && droppedCustomKeys.isEmpty()

    val count: Int get() = refusals.size + unplaced.size

    val needsRead: Boolean
        get() = refusals.any { !it.isRowLevel && it.held.state == DwHeldState.UNRECORDED }

    val byAddress: Map<String, Map<String, String>>
        get() = refusals.filterNot { it.isRowLevel }
            .groupBy { it.address }
            .mapValues { (_, list) -> list.associate { it.fieldKey to it.message } }

    val heading: String
        get() = buildString {
            val holds = refusals.count { it.held.state == DwHeldState.HOLDS }
            val nothing = refusals.count { it.held.state == DwHeldState.NOTHING }
            val them = if (count == 1) "it" else "them"
            if (count > 0) {
                append("$count answer${if (count == 1) " was" else "s were"} not accepted")
                when {
                    holds > 0 -> append(", and the earlier answer${if (count == 1) " was" else "s were"} kept")
                    nothing == refusals.size && refusals.isNotEmpty() ->
                        append(", and there is no earlier answer for ${if (count == 1) "it" else "any of them"}")
                }
                append(". ")
            }
            if (droppedCustomKeys.isEmpty()) {
                if (count > 0) append("Everything else in this stage was saved. ")
            } else {
                val keys = droppedCustomKeys.size
                append(
                    "$keys custom answer${if (keys == 1) " wasn't" else "s weren't"} saved, which the sections no longer ask — open this workshop once with a connection to refresh. "
                )
            }
            if (count > 0) {
                append("Nothing you typed has been thrown away — it's still on this phone, and in ")
                append("the boxes below.")
            } else {
                append("Nothing you typed has been thrown away.")
            }
            recordedAt?.let { append(" Recorded when this stage was last saved online, at $it.") }
        }
}

fun dwDecodeStageRefusals(
    spec: StageDto?,
    entries: List<StageEntryBody>,
    errors: Map<String, JsonElement>,
    customFields: List<DwCustomFieldDto> = emptyList(),
): DwStageRefusalReport = dwDecodeStageRefusalsFromSent(
    spec = spec,
    sent = entries.map(::dwSentEntryOf),
    errors = errors,
    customFields = customFields,
)

fun dwDecodeStageRefusalsFromSent(
    spec: StageDto?,
    sent: List<DwSentEntry>,
    errors: Map<String, JsonElement>,
    customFields: List<DwCustomFieldDto> = emptyList(),
): DwStageRefusalReport {
    if (errors.isEmpty()) return DwStageRefusalReport()

    val customByKey = customFields.associateBy { it.key }
    val refusals = ArrayList<DwStageRefusal>()
    val unplaced = ArrayList<String>()

    errors.forEach { (scope, payload) ->
        val fields = fieldMessages(payload)
        if (fields.isEmpty()) {
            unplaced += "$scope: ${DwValues.text(payload).ifBlank { "not accepted, with no reason given" }}"
            return@forEach
        }
        val match = SCOPED_ROW.matchEntire(scope)
        val entityKey: String
        val rowIndex: Int?
        var rowKey: String? = null
        if (match == null) {
            entityKey = scope
            rowIndex = null
        } else {
            entityKey = match.groupValues[1]
            val entryIndex = match.groupValues[2].toIntOrNull()
            val entry = entryIndex?.let { sent.getOrNull(it) }
            if (entry == null || entry.entityKey != entityKey) {
                fields.forEach { (key, message) -> unplaced += "$scope.$key: $message" }
                return@forEach
            }
            rowIndex = entry.ordinal
                ?: sent.take(entryIndex).count { it.entityKey == entityKey }
            rowKey = entry.rowKey
        }

        val entity = spec?.entity(entityKey)
        fields.forEach { (fieldKey, message) ->
            if (fieldKey == DW_ROW_REFUSAL_KEY) {
                refusals += DwStageRefusal(
                    entityKey = entityKey,
                    rowIndex = rowIndex,
                    rowKey = rowKey,
                    fieldKey = fieldKey,
                    label = dwRowRefusalLabel(entityKey, entity),
                    message = message,
                    drawn = entity != null || entityKey == CUSTOM_ENTITY_KEY,
                )
            } else {
                val registryField = entity?.fields?.firstOrNull { it.key == fieldKey }
                val customField = customByKey[fieldKey]?.takeIf { entityKey == CUSTOM_ENTITY_KEY }
                val label = registryField?.label?.takeIf { it.isNotBlank() }
                    ?: customField?.label?.takeIf { it.isNotBlank() }
                    ?: fieldKey
                val drawn = when {
                    registryField != null -> !registryField.deprecated
                    customField != null -> dwCustomFieldDrawable(customField.type) && !customField.retired
                    else -> false
                }
                refusals += DwStageRefusal(
                    entityKey = entityKey,
                    rowIndex = rowIndex,
                    rowKey = rowKey,
                    fieldKey = fieldKey,
                    label = label,
                    message = message,
                    drawn = drawn,
                )
            }
        }
    }

    return DwStageRefusalReport(refusals = refusals, unplaced = unplaced)
}

private fun dwRowRefusalLabel(entityKey: String, entity: EntityDto?): String = when {
    entityKey == CUSTOM_ENTITY_KEY -> "This workshop's own questions"
    else -> entity?.title?.takeIf { it.isNotBlank() } ?: entityKey
}

fun dwHoldingsFrom(report: DwStageRefusalReport, bucket: StageBucketDto): DwStageRefusalReport =
    report.copy(
        refusals = report.refusals.map { refusal ->
            if (refusal.isRowLevel) return@map refusal
            val value: JsonElement? = when {
                refusal.entityKey == CUSTOM_ENTITY_KEY -> bucket.custom[refusal.fieldKey]
                refusal.rowIndex == null -> bucket.singleton[refusal.fieldKey]
                else -> {
                    val rows = bucket.collections[refusal.entityKey].orEmpty()
                    val row = refusal.rowKey
                        ?.let { key ->
                            rows.firstOrNull { (it["_clientKey"] as? JsonPrimitive)?.content == key }
                        }
                        ?: rows.getOrNull(refusal.rowIndex)
                    row?.get(refusal.fieldKey)
                }
            }
            refusal.copy(
                held = if (value == null) DwHeld.nothing() else DwHeld.holding(DwValues.text(value))
            )
        }
    )

fun dwCarryHoldings(previous: DwStageRefusalReport?, next: DwStageRefusalReport): DwStageRefusalReport {
    if (previous == null || previous.refusals.isEmpty()) return next
    val measured = previous.refusals
        .filter { it.held.state != DwHeldState.UNRECORDED }
        .associateBy { it.carryKey to it.fieldKey }
    if (measured.isEmpty()) return next
    return next.copy(
        refusals = next.refusals.map { refusal ->
            measured[refusal.carryKey to refusal.fieldKey]
                ?.let { refusal.copy(held = it.held) }
                ?: refusal
        }
    )
}

@Serializable
data class DwStageRefusalRecord(
    val errors: Map<String, JsonElement> = emptyMap(),
    val sent: List<DwSentEntry> = emptyList(),
    val at: String? = null,
    val droppedCustomKeys: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = errors.isEmpty() && droppedCustomKeys.isEmpty()
}

@Serializable
data class DwSentEntry(
    val entityKey: String,
    val ordinal: Int? = null,
    val rowKey: String? = null,
)

fun dwSentEntryOf(entry: StageEntryBody): DwSentEntry = DwSentEntry(
    entityKey = entry.entityKey,
    ordinal = entry.ordinal,
    rowKey = (entry.data["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
)

fun dwRestoreStageRefusals(
    spec: StageDto?,
    record: DwStageRefusalRecord?,
    customFields: List<DwCustomFieldDto> = emptyList(),
): DwStageRefusalReport? {
    if (record == null || record.isEmpty) return null
    val report = dwDecodeStageRefusalsFromSent(
        spec = spec,
        sent = record.sent,
        errors = record.errors,
        customFields = customFields,
    )
    return report.copy(recordedAt = record.at, droppedCustomKeys = record.droppedCustomKeys)
        .takeIf { !it.isEmpty }
}

private val SCOPED_ROW = Regex("^(.+)\\[(\\d+)]$")

private fun fieldMessages(payload: JsonElement): Map<String, String> {
    val obj = payload as? JsonObject ?: return emptyMap()
    return obj.entries
        .associate { (key, value) ->
            key to ((value as? JsonPrimitive)?.content ?: DwValues.text(value))
                .ifBlank { "was not accepted" }
        }
}
