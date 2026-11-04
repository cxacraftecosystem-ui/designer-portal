package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StagePush
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.data.singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal const val DW_CHOOSER_SKETCH_ENTITY: String = "sketch"

internal const val DW_CHOOSER_PROTOTYPE_ENTITY: String = "prototype"

internal val DW_CHOOSER_SKETCH_FIELDS: List<String> = listOf("image", "lineArtFile")

internal val DW_CHOOSER_PROTOTYPE_FIELDS: List<String> = listOf("turntablePhotos", "modelFile")

private fun DraftRow.chooserText(key: String): String {
    val value = values[key]
    if (value !is JsonPrimitive || value is JsonNull || !value.isString) return ""
    return value.content.trim()
}

internal fun dwChooserRowLabel(row: DraftRow, index: Int): String {
    for (key in listOf("name", "sketchNo", "prototypeCode")) {
        val value = row.chooserText(key)
        if (value.isNotEmpty()) return value
    }
    return "Untitled ${index + 1}"
}

internal fun dwChooserRowKey(row: DraftRow): String = row.id

internal fun dwChooserNewRow(entityKey: String, rowId: String): DraftRow =
    DraftRow(id = dwRowId(entityKey, rowId), values = emptyMap())

internal fun dwChooserAppendRow(
    spec: StageDto,
    existing: StageDraft?,
    row: DraftRow,
): StageDraft {
    val base = existing ?: StageDraft(
        stageId = spec.key,
        title = spec.title,
        order = spec.number,
        requiredKeys = spec.singleton?.liveFields.orEmpty().filter { it.required }.map { it.key },
    )
    return base.copy(rows = base.rows + row)
}

internal fun dwChooserRows(stage: StageDraft?, entityKey: String): List<DraftRow> =
    stage?.rowsFor(entityKey).orEmpty()

internal fun dwChooserEntity(spec: StageDto?, entityKey: String): EntityDto? =
    spec?.collections?.firstOrNull { it.key == entityKey }

internal fun dwChooserMediaFields(entity: EntityDto?, keys: List<String>): List<FieldDto> {
    val live = entity?.liveFields.orEmpty()
    return keys.mapNotNull { key -> live.firstOrNull { it.key == key } }
}

internal fun dwChooserHeldMedia(row: DraftRow?, fieldKey: String): List<String> {
    val value = row?.values?.get(fieldKey) ?: return emptyList()
    return when {
        value is JsonArray ->
            value.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
                    ?.takeIf { it.isNotBlank() }
            }

        value is JsonPrimitive && value !is JsonNull && value.isString ->
            listOfNotNull(value.content.takeIf { it.isNotBlank() })

        else -> emptyList()
    }
}

internal const val DW_TENTATIVE_FIELD_KEY: String = "isTentative"

internal fun dwTentativeField(entity: EntityDto?): FieldDto? =
    entity?.liveFields?.firstOrNull {
        it.key == DW_TENTATIVE_FIELD_KEY && DwFieldType.of(it.type) == DwFieldType.BOOL
    }

internal fun dwIsTentativeRow(values: Map<String, JsonElement>?): Boolean =
    DwValues.bool(values?.get(DW_TENTATIVE_FIELD_KEY)) == true

internal fun <T> dwTentativeFirst(items: List<T>, isTentative: (T) -> Boolean): List<T> {
    val (first, rest) = items.partition(isTentative)
    return first + rest
}

internal fun dwChooserWriteMedia(
    rows: List<DraftRow>,
    rowKey: String,
    fieldKey: String,
    ids: List<String>,
    asList: Boolean,
): List<DraftRow> = rows.map { row ->
    if (dwChooserRowKey(row) != rowKey) {
        row
    } else {
        val values = when {
            ids.isEmpty() -> row.values - fieldKey
            asList -> row.values + (fieldKey to JsonArray(ids.map { JsonPrimitive(it) }))

            else -> row.values + (fieldKey to JsonPrimitive(ids.first()))
        }
        row.copy(values = values)
    }
}

internal fun dwChooserWriteScalar(
    rows: List<DraftRow>,
    rowKey: String,
    fieldKey: String,
    value: JsonElement?,
): List<DraftRow> = rows.map { row ->
    if (dwChooserRowKey(row) != rowKey) {
        row
    } else {
        val values = if (value == null || value is JsonNull) {
            row.values - fieldKey
        } else {
            row.values + (fieldKey to value)
        }
        row.copy(values = values)
    }
}

internal fun dwChooserReplaceRows(
    stage: StageDraft,
    entityKey: String,
    rows: List<DraftRow>,
): StageDraft = stage.copy(rows = stage.rows.filterNot { it.entityKey() == entityKey } + rows)

internal fun dwChooserDefaultWorkshop(
    workshops: List<DesignWorkshopDto>,
    serverDefaultId: String?,
    chosen: String,
): String {
    if (workshops.isEmpty()) return ""
    if (chosen.isNotBlank() && workshops.any { it.id == chosen }) return chosen
    val fromServer = serverDefaultId?.trim().orEmpty()
    if (fromServer.isNotBlank() && workshops.any { it.id == fromServer }) return fromServer
    return workshops.first().id
}

internal fun dwChooserKeepSelection(chosen: String, rows: List<DraftRow>): String {
    if (rows.isEmpty()) return ""
    if (chosen.isNotBlank() && rows.any { dwChooserRowKey(it) == chosen }) return chosen
    return dwChooserRowKey(dwTentativeFirst(rows) { dwIsTentativeRow(it.values) }.first())
}

internal fun dwChooserSaveNote(push: StagePush?, what: String): String = when (push) {
    is StagePush.Sent -> "$what is saved on this phone and online."
    StagePush.AlreadySent -> "$what is saved. It is already online."
    is StagePush.HeldBack ->
        "$what saved on this phone. Uploads with ${push.files} pending attachment${if (push.files == 1) "" else "s"}."
    StagePush.NoRemoteYet ->
        "$what saved on this phone. Uploads after the workshop does."
    StagePush.NothingToSend ->
        "$what saved on this phone. Uploads after you open this stage."
    StagePush.NotSent, null ->
        "$what saved on this phone. Upload didn't finish; it retries next sync."
}

internal fun dwChooserSendingNote(what: String): String =
    "$what is saved on this phone. Sending it…"
