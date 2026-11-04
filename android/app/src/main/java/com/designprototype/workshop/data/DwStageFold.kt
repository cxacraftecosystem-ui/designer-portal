package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class DwStageFold(
    val draft: StageDraft,
    val added: List<String>,
    val addedRows: Map<String, Int>,
    val addedCustom: List<String>,
    val sweptRows: Map<String, Int> = emptyMap(),
    val declinedRows: Map<String, Int> = emptyMap(),
) {
    val addedRowCount: Int get() = addedRows.values.sum()

    val sweptRowCount: Int get() = sweptRows.values.sum()

    val declinedRowCount: Int get() = declinedRows.values.sum()

    val isNothingNew: Boolean
        get() = added.isEmpty() && addedRows.isEmpty() && addedCustom.isEmpty() &&
            sweptRows.isEmpty() && declinedRows.isEmpty()

    val notice: String?
        get() {
            if (isNothingNew) return null
            val parts = ArrayList<String>(3)
            if (added.isNotEmpty()) {
                parts += "${added.size} answer${if (added.size == 1) "" else "s"} " +
                    "(${added.take(4).joinToString(", ")}${if (added.size > 4) ", …" else ""})"
            }
            if (addedRows.isNotEmpty()) {
                val count = addedRowCount
                parts += "$count entr${if (count == 1) "y" else "ies"} " +
                    "in ${addedRows.keys.joinToString(", ")}"
            }
            if (addedCustom.isNotEmpty()) {
                parts += "${addedCustom.size} custom question${if (addedCustom.size == 1) "" else "s"} (${addedCustom.take(4).joinToString(", ")}${if (addedCustom.size > 4) ", …" else ""})"
            }
            return buildString {
                append("Updated to the latest online version: ")
                if (parts.isNotEmpty()) {
                    append(parts.joinToString("; "))
                    append(" added below. ")
                    append("Nothing you had typed here was changed. ")
                }
                if (sweptRows.isNotEmpty()) {
                    val rows = sweptRowCount
                    val them = if (rows == 1) "it" else "them"
                    append("You had deleted everything in ")
                    append(sweptRows.keys.joinToString(", "))
                    append(" on this phone, so ")
                    append("$rows entr${if (rows == 1) "y" else "ies"} still saved online ")
                    append(if (rows == 1) "there has" else "there have")
                    append(" not been added back, and the next save will delete $them online — ")
                    append("including anything added there by somebody else since you deleted. Your ")
                    append("deletion stands, which is what you asked for. ")
                    append("If you did not mean to delete $them, say so before this stage is ")
                    append("submitted: ")
                    append("a deleted entry is kept, so an administrator can bring ")
                    append(if (rows == 1) "it" else "them")
                    append(" back.")
                }
                if (declinedRows.isNotEmpty()) {
                    val rows = declinedRowCount
                    if (isNotEmpty()) append(" ")
                    append("The $rows entr${if (rows == 1) "y" else "ies"} you deleted in ")
                    append(declinedRows.keys.joinToString(", "))
                    append(" ${if (rows == 1) "has" else "have"} not come back. That deletion has ")
                    append("not been sent yet; the next save from this phone sends it.")
                }
            }.trim()
        }
}

fun dwFoldServerStage(
    spec: StageDto?,
    local: StageDraft?,
    bucket: StageBucketDto,
    stageKey: String,
): DwStageFold {
    val base = local ?: StageDraft(
        stageId = stageKey,
        title = spec?.title.orEmpty(),
        order = spec?.number ?: 0,
    )

    val added = ArrayList<String>()
    val values = LinkedHashMap<String, JsonElement>(base.values)
    bucket.singleton.forEach { (key, value) ->
        if (!values.containsKey(key)) {
            values[key] = value
            if (!key.startsWith("_")) added += key
        }
    }

    val addedCustom = ArrayList<String>()
    val custom = LinkedHashMap<String, JsonElement>(base.custom)
    bucket.custom.forEach { (key, value) ->
        if (!custom.containsKey(key)) {
            custom[key] = value
            if (!key.startsWith("_")) addedCustom += key
        }
    }

    val emptied = base.emptiedEntities.toSet()
    val deleted = base.deletedRowKeys.toSet()
    val sweptRows = LinkedHashMap<String, Int>()
    val declinedRows = LinkedHashMap<String, Int>()
    val addedRows = LinkedHashMap<String, Int>()
    val rows = ArrayList(base.rows)
    val heldKeys = base.rows.mapTo(HashSet()) { it.id }
    val heldEntryIds = base.rows.mapNotNullTo(HashSet()) {
        (it.values["_entryId"] as? JsonPrimitive)?.content?.takeIf { id -> id.isNotBlank() }
    }
    bucket.collections.forEach { (entityKey, serverRows) ->
        val declared = spec == null || spec.collections.any { it.key == entityKey }
        if (!declared) return@forEach
        if (entityKey in emptied) {
            val unseen = serverRows.count { row ->
                val clientKey = (row["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                val entryId = (row["_entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                dwRowId(entityKey, clientKey ?: entryId ?: "") !in heldKeys &&
                    (entryId == null || entryId !in heldEntryIds)
            }
            if (unseen > 0) sweptRows[entityKey] = unseen
            return@forEach
        }
        var appended = 0
        var declined = 0
        serverRows.forEachIndexed { index, row ->
            val clientKey = (row["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val entryId = (row["_entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val identity = clientKey ?: entryId ?: "server-$index"
            if (dwRowId(entityKey, identity) in heldKeys) return@forEachIndexed
            if (entryId != null && entryId in heldEntryIds) return@forEachIndexed
            val deletedHere = dwRowId(entityKey, identity) in deleted ||
                (clientKey != null && dwRowId(entityKey, clientKey) in deleted) ||
                (entryId != null && dwRowId(entityKey, entryId) in deleted)
            if (deletedHere) {
                declined++
                return@forEachIndexed
            }
            rows += DraftRow(id = dwRowId(entityKey, identity), values = row.toMap())
            appended++
        }
        if (appended > 0) addedRows[entityKey] = appended
        if (declined > 0) declinedRows[entityKey] = declined
    }

    return DwStageFold(
        draft = base.copy(
            stageId = base.stageId.ifBlank { stageKey },
            title = base.title.ifBlank { spec?.title.orEmpty() },
            order = if (base.order != 0) base.order else (spec?.number ?: 0),
            values = values,
            custom = custom,
            provenance = bucket.provenance,
            customSeen = true,
            rows = rows,
            requiredKeys = if (spec == null) base.requiredKeys else
                spec.singleton?.liveFields.orEmpty().filter { it.required }.map { it.key },
            stageSeen = true,
            emptiedEntities = base.emptiedEntities,
            deletedRowKeys = base.deletedRowKeys,
        ),
        added = added,
        addedRows = addedRows,
        addedCustom = addedCustom,
        sweptRows = sweptRows,
        declinedRows = declinedRows,
    )
}
