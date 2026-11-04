package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageListDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ReportSource(
    val draft: WorkshopDraft?,
    val filledFromServer: List<String>,
    val keptFromDevice: List<String>,
    val deviceOnlyNote: String?,
    val builtFromLine: String,
    val serverCopyUnread: Boolean,
)

internal fun reportSourceFor(
    schema: SchemaResponse,
    workshopId: String,
    local: WorkshopDraft?,
    remoteId: String?,
    remote: StageListDto?,
): ReportSource {
    val specs = schema.stages.associateBy { it.key }
    val remoteStages = remote?.stages.orEmpty()

    val merged = LinkedHashMap(local?.stages.orEmpty())
    val filled = ArrayList<String>()
    val kept = ArrayList<String>()

    remoteStages.forEach { (stageKey, bucket) ->
        val stored = merged[stageKey]
        val touchedLocally = stored != null && stored.holdsWork()
        if (touchedLocally) {
            kept += stageKey
            return@forEach
        }
        if (bucket.singleton.isEmpty() && bucket.collections.values.all { it.isEmpty() } &&
            bucket.custom.isEmpty()
        ) return@forEach
        val downloaded = stageDraftFromRemote(stageKey, bucket, specs[stageKey], stored)
        if (!downloaded.holdsWork()) return@forEach
        merged[stageKey] = downloaded
        filled += stageKey
    }

    local?.stages.orEmpty().forEach { (stageKey, stored) ->
        if (stageKey in kept || stageKey in filled) return@forEach
        if (stored.holdsWork()) kept += stageKey
    }

    val draft = when {
        local != null -> local.copy(stages = merged)
        merged.isEmpty() -> null
        else -> WorkshopDraft(workshopId = workshopId, remoteId = remoteId, stages = merged)
    }

    val deviceOnlyNote = when {
        remoteId == null ->
            "This workshop has not been uploaded yet — the report uses only what's on this phone."
        remote == null ->
            "The latest copy could not be read (no connection, or the request failed) — this report uses only what's on this device. Mention that when sharing it."
        else -> null
    }

    return ReportSource(
        draft = draft,
        filledFromServer = filled,
        keptFromDevice = kept,
        deviceOnlyNote = deviceOnlyNote,
        builtFromLine = builtFromLine(deviceOnlyNote == null, filled.size, kept.size),
        serverCopyUnread = remoteId != null && remote == null,
    )
}

internal fun StageDraft.holdsWork(): Boolean =
    values.keys.any { !it.startsWith("_") } || rows.isNotEmpty() || custom.isNotEmpty()

private fun builtFromLine(serverAnswered: Boolean, fromServer: Int, fromDevice: Int): String = when {
    !serverAnswered && fromDevice == 0 ->
        "Nothing recorded yet."
    !serverAnswered ->
        "From ${plural(fromDevice, "stage")} saved on this device."
    fromServer == 0 && fromDevice == 0 ->
        "Nothing recorded yet."
    fromServer == 0 ->
        "From ${plural(fromDevice, "stage")} saved on this device."
    fromDevice == 0 ->
        "From ${plural(fromServer, "stage")} downloaded."
    else ->
        "From ${plural(fromServer, "stage")} downloaded and ${plural(fromDevice, "stage")} saved on this device (this device's copy wins)."
}

private fun stageDraftFromRemote(
    stageKey: String,
    bucket: StageBucketDto,
    spec: StageDto?,
    existing: StageDraft?,
): StageDraft {
    val emptied = existing?.emptiedEntities.orEmpty().toSet()
    return StageDraft(
        stageId = stageKey,
        title = spec?.title ?: existing?.title.orEmpty(),
        order = spec?.number ?: existing?.order ?: 0,
        values = bucket.singleton.toMap() +
            existing?.values.orEmpty().filterKeys { it.startsWith("_") },
        custom = bucket.custom.toMap(),
        customSeen = true,
        rows = bucket.collections.entries
            .filter { (entityKey, _) -> entityKey !in emptied }
            .flatMap { (entityKey, rows) ->
                rows.mapIndexed { index, row ->
                    DraftRow(id = dwRowId(entityKey, remoteRowId(row, index)), values = row.toMap())
                }
            },
        mediaIds = existing?.mediaIds.orEmpty(),
        requiredKeys = spec?.singleton?.liveFields.orEmpty().filter { it.required }.map { it.key },
        completedAt = existing?.completedAt,
        notes = existing?.notes.orEmpty(),
        stageSeen = true,
        emptiedEntities = existing?.emptiedEntities.orEmpty(),
    )
}

private fun remoteRowId(row: JsonObject, index: Int): String =
    (row["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        ?: (row["_entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        ?: "row-$index"
