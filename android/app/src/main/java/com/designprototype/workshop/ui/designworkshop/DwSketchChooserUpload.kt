package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.OpenInFull
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DraftMedia
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwFoldServerStage
import com.designprototype.workshop.data.dwStageKeyForEntity
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.UUID

@Composable
internal fun DwSketchChooserUploadTab(
    repository: WorkshopRepository,
    workshopId: String,
    onOpenStage: (workshopId: String, stageKey: String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var sketch by remember(workshopId) { mutableStateOf<DwChooserHalf?>(null) }
    var prototype by remember(workshopId) { mutableStateOf<DwChooserHalf?>(null) }
    var sketchRow by remember(workshopId) { mutableStateOf("") }
    var prototypeRow by remember(workshopId) { mutableStateOf("") }
    var mediaIndex by remember(workshopId) { mutableStateOf<Map<String, DraftMedia>>(emptyMap()) }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var notice by remember(workshopId) { mutableStateOf<String?>(null) }
    var problem by remember(workshopId) { mutableStateOf<String?>(null) }
    var attempt by remember(workshopId) { mutableIntStateOf(0) }

    val screeningStore = remember(workshopId) { DwScreeningStore(scope) }

    suspend fun read() {
        val schema = runCatching { repository.designWorkshopSchema(appContext) }.getOrNull()
        val draft = runCatching { WorkshopDraftStore.load(appContext, workshopId) }.getOrNull()
        mediaIndex = draft?.media.orEmpty().associateBy { it.id }

        suspend fun half(entityKey: String): DwChooserHalf {
            val stageKey = schema?.let { dwStageKeyForEntity(it, entityKey) }
            val spec = schema?.stages?.firstOrNull { it.key == stageKey }
            if (stageKey == null || spec == null) {
                return DwChooserHalf(entityKey = entityKey, stageKey = null, spec = null)
            }
            val local = draft?.stages?.get(stageKey)
            return runCatching { repository.designWorkshopStage(workshopId, stageKey) }
                .fold(
                    onSuccess = { bucket ->
                        val folded = dwFoldServerStage(spec, local, bucket, stageKey)
                        val saved = runCatching {
                            WorkshopDraftStore.updateStage(appContext, workshopId, folded.draft)
                        }.getOrNull()
                        if (saved != null) mediaIndex = saved.media.associateBy { it.id }
                        DwChooserHalf(
                            entityKey = entityKey,
                            stageKey = stageKey,
                            spec = spec,
                            stage = folded.draft,
                            reconciled = true,
                        )
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        DwChooserHalf(
                            entityKey = entityKey,
                            stageKey = stageKey,
                            spec = spec,
                            stage = local,
                            reconciled = false,
                            failure = if (repository.isConnectionFailure(error)) {
                                DW_SKETCH_CHOOSER_STAGE_OFFLINE
                            } else {
                                error.apiErrorMessage(DW_SKETCH_CHOOSER_STAGE_REFUSED)
                            },
                        )
                    },
                )
        }

        sketch = half(DW_CHOOSER_SKETCH_ENTITY)
        prototype = half(DW_CHOOSER_PROTOTYPE_ENTITY)
        sketchRow = dwChooserKeepSelection(sketchRow, sketch?.rows.orEmpty())
        prototypeRow = dwChooserKeepSelection(prototypeRow, prototype?.rows.orEmpty())
    }

    LaunchedEffect(workshopId, attempt) {
        loading = true
        problem = null
        runCatching { read() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                problem = error.apiErrorMessage(DW_SKETCH_CHOOSER_STAGE_REFUSED)
            }
        loading = false
    }

    val media = remember(workshopId, screeningStore) {
        DwMediaBridge(
            workshopId = workshopId,
            screening = screeningStore,
            resolve = { id ->
                mediaIndex[id]?.let { descriptor ->
                    DwMediaItem(
                        id = descriptor.id,
                        displayName = descriptor.originalFilename.ifBlank { descriptor.id },
                        absolutePath = WorkshopDraftStore
                            .mediaFile(appContext, workshopId, descriptor).absolutePath,
                        mediaType = descriptor.mediaType,
                        sizeBytes = descriptor.sizeBytes,
                        sha256 = descriptor.sha256,
                        remoteMediaId = descriptor.remoteMediaId,
                    )
                }
            },
            attach = { uris: List<Uri>, field: FieldDto, onAttached: (List<String>) -> Unit ->
                scope.launch {
                    val imported = mutableListOf<String>()
                    val added = mutableMapOf<String, DraftMedia>()
                    var failures = 0
                    for (uri in uris) {
                        runCatching {
                            WorkshopDraftStore.importMedia(
                                context = appContext,
                                workshopId = workshopId,
                                uri = uri,
                                stageId = dwChooserStageForField(field.key, sketch, prototype),
                                fieldKey = field.key,
                            )
                        }.onSuccess { descriptor ->
                            added[descriptor.id] = descriptor
                            imported += descriptor.id
                        }.onFailure { failures++ }
                    }
                    if (added.isNotEmpty()) mediaIndex = mediaIndex + added
                    if (imported.isNotEmpty()) onAttached(imported)
                    if (failures > 0) {
                        problem = if (imported.isEmpty()) {
                            "That file could not be attached."
                        } else {
                            "$failures of ${uris.size} files couldn't be attached."
                        }
                    }
                }
            },
            detach = { id: String ->
                scope.launch {
                    runCatching { WorkshopDraftStore.removeMedia(appContext, workshopId, id) }
                    mediaIndex = mediaIndex - id
                }
            },
            newCaptureFile = { suffix ->
                val dir = File(
                    WorkshopDraftStore.workshopDir(appContext, workshopId), "captures"
                ).apply { mkdirs() }
                File(dir, "capture-${UUID.randomUUID()}$suffix")
            },
        )
    }

    fun addRow(half: DwChooserHalf, select: (String) -> Unit) {
        val spec = half.spec ?: return
        val stageKey = half.stageKey ?: return
        scope.launch {
            notice = null
            problem = null
            val row = dwChooserNewRow(half.entityKey, UUID.randomUUID().toString())
            val written = runCatching {
                val draft = WorkshopDraftStore.load(appContext, workshopId)
                val next = dwChooserAppendRow(spec, draft?.stages?.get(stageKey), row)
                WorkshopDraftStore.updateStage(appContext, workshopId, next)
            }
            written.onFailure { error ->
                if (error is CancellationException) throw error
                problem = DW_SKETCH_CHOOSER_ROW_NOT_ADDED
                return@launch
            }
            select(dwChooserRowKey(row))
            notice = dwChooserSendingNote(half.what)
            attempt++
            val push = runCatching {
                WorkshopSyncEngine.pushStage(
                    context = appContext,
                    repository = repository,
                    workshopId = workshopId,
                    spec = spec,
                )
            }.getOrNull()
            notice = dwChooserSaveNote(push, half.what)
        }
    }

    fun writeMedia(half: DwChooserHalf, rowKey: String, field: FieldDto, ids: List<String>) {
        val spec = half.spec ?: return
        val stageKey = half.stageKey ?: return
        scope.launch {
            notice = null
            problem = null
            val written = runCatching {
                val draft = WorkshopDraftStore.load(appContext, workshopId)
                val stage = draft?.stages?.get(stageKey) ?: return@runCatching null
                val rows = dwChooserWriteMedia(
                    rows = dwChooserRows(stage, half.entityKey),
                    rowKey = rowKey,
                    fieldKey = field.key,
                    ids = ids,
                    asList = DwFieldType.of(field.type) == DwFieldType.IMAGE_LIST,
                )
                WorkshopDraftStore.updateStage(
                    appContext,
                    workshopId,
                    dwChooserReplaceRows(stage, half.entityKey, rows),
                )
            }
            written.onFailure { error ->
                if (error is CancellationException) throw error
                problem = DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED
                return@launch
            }
            if (written.getOrNull() == null) {
                problem = DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED
                return@launch
            }
            notice = dwChooserSendingNote("This file")
            attempt++
            val push = runCatching {
                WorkshopSyncEngine.pushStage(
                    context = appContext,
                    repository = repository,
                    workshopId = workshopId,
                    spec = spec,
                )
            }.getOrNull()
            notice = dwChooserSaveNote(push, "This file")
        }
    }

    fun writeScalar(half: DwChooserHalf, rowKey: String, fieldKey: String, value: JsonElement?) {
        val spec = half.spec ?: return
        val stageKey = half.stageKey ?: return
        scope.launch {
            notice = null
            problem = null
            val written = runCatching {
                val draft = WorkshopDraftStore.load(appContext, workshopId)
                val stage = draft?.stages?.get(stageKey) ?: return@runCatching null
                val rows = dwChooserWriteScalar(
                    rows = dwChooserRows(stage, half.entityKey),
                    rowKey = rowKey,
                    fieldKey = fieldKey,
                    value = value,
                )
                WorkshopDraftStore.updateStage(
                    appContext,
                    workshopId,
                    dwChooserReplaceRows(stage, half.entityKey, rows),
                )
            }
            written.onFailure { error ->
                if (error is CancellationException) throw error
                problem = DW_SKETCH_CHOOSER_MEASUREMENT_NOT_SAVED
                return@launch
            }
            if (written.getOrNull() == null) {
                problem = DW_SKETCH_CHOOSER_MEASUREMENT_NOT_SAVED
                return@launch
            }
            notice = dwChooserSendingNote("This measurement")
            attempt++
            val push = runCatching {
                WorkshopSyncEngine.pushStage(
                    context = appContext,
                    repository = repository,
                    workshopId = workshopId,
                    spec = spec,
                )
            }.getOrNull()
            notice = dwChooserSaveNote(push, "This measurement")
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            when {
                problem != null -> Text(
                    problem.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 18.sp,
                )

                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        "Loading sketches and prototypes…",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                notice != null -> Text(
                    notice.orEmpty(),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )

                else -> Unit
            }
        }

        DwChooserUploadHalf(
            half = sketch,
            icon = Icons.Filled.Brush,
            fieldKeys = DW_CHOOSER_SKETCH_FIELDS,
            chosenRow = sketchRow,
            onChooseRow = { sketchRow = it },
            onAdd = { half -> addRow(half) { sketchRow = it } },
            onWriteMedia = { half, rowKey, field, ids -> writeMedia(half, rowKey, field, ids) },
            onWriteScalar = { half, rowKey, key, value -> writeScalar(half, rowKey, key, value) },
            onOpenStage = onOpenStage,
            workshopId = workshopId,
            repository = repository,
            media = media,
            onMessage = { notice = it },
            onProblem = { problem = it },
        )

        DwChooserUploadHalf(
            half = prototype,
            icon = Icons.Filled.Category,
            fieldKeys = DW_CHOOSER_PROTOTYPE_FIELDS,
            chosenRow = prototypeRow,
            onChooseRow = { prototypeRow = it },
            onAdd = { half -> addRow(half) { prototypeRow = it } },
            onWriteMedia = { half, rowKey, field, ids -> writeMedia(half, rowKey, field, ids) },
            onWriteScalar = { half, rowKey, key, value -> writeScalar(half, rowKey, key, value) },
            onOpenStage = onOpenStage,
            workshopId = workshopId,
            repository = repository,
            media = media,
            onMessage = { notice = it },
            onProblem = { problem = it },
        )
    }
}

@Composable
private fun DwChooserUploadHalf(
    half: DwChooserHalf?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fieldKeys: List<String>,
    chosenRow: String,
    onChooseRow: (String) -> Unit,
    onAdd: (DwChooserHalf) -> Unit,
    onWriteMedia: (DwChooserHalf, String, FieldDto, List<String>) -> Unit,
    onWriteScalar: (DwChooserHalf, String, String, JsonElement?) -> Unit,
    onOpenStage: (String, String) -> Unit,
    workshopId: String,
    repository: WorkshopRepository,
    media: DwMediaBridge,
    onMessage: (String) -> Unit,
    onProblem: (String) -> Unit,
) {
    if (half == null) return
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                half.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val stageKey = half.stageKey
        val spec = half.spec
        if (stageKey == null || spec == null) {
            Text(
                DW_SKETCH_CHOOSER_NO_STAGE,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
            )
            return@Column
        }

        half.failure?.let { sentence ->
            Text(
                sentence,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
            )
            Text(
                DW_SKETCH_CHOOSER_STALE,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        val rows = half.rows
        if (rows.isEmpty()) {
            Text(
                half.emptyNote,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
            )
        } else {
            val tentativeWord = dwTentativeField(dwChooserEntity(spec, half.entityKey))?.label
            SearchableSelectField(
                label = half.pickerLabel,
                options = dwTentativeFirst(
                    rows.mapIndexed { index, row -> row to index },
                ) { (row, _) -> dwIsTentativeRow(row.values) }.map { (row, index) ->
                    val position = "Row ${index + 1} of ${rows.size}"
                    SelectOption(
                        value = dwChooserRowKey(row),
                        label = dwChooserRowLabel(row, index),
                        hint = if (tentativeWord != null && dwIsTentativeRow(row.values)) {
                            "$tentativeWord · $position"
                        } else {
                            position
                        },
                    )
                },
                selectedValue = chosenRow,
                placeholder = half.pickerPlaceholder,
                includeNone = false,
                onSelect = onChooseRow,
            )
        }

        ActionRow {
            OutlinedButton(
                onClick = { onAdd(half) },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(half.addLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = { onOpenStage(workshopId, stageKey) },
            ) {
                Icon(
                    Icons.Filled.OpenInFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("Open the stage", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Text(
            half.stageNote,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        val entity = dwChooserEntity(spec, half.entityKey)
        val fields = dwChooserMediaFields(entity, fieldKeys)
        val chosen = rows.firstOrNull { dwChooserRowKey(it) == chosenRow }
        if (fields.isNotEmpty()) {
            half.mediaNotes.forEachIndexed { index, note ->
                Text(
                    note,
                    color = MaterialTheme.field.muted,
                    style = if (index == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                )
            }
        }
        when {
            fields.isEmpty() -> Text(
                DW_SKETCH_CHOOSER_NO_MEDIA_FIELDS,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
            )

            chosen == null -> Unit

            else -> fields.forEach { field ->
                DwMediaCaptureCard(
                    field = field,
                    type = DwFieldType.of(field.type),
                    ids = dwChooserHeldMedia(chosen, field.key),
                    media = media,
                    slotKey = "${half.entityKey}:$chosenRow:${field.key}",
                    enabled = half.reconciled,
                    onIdsChange = { ids -> onWriteMedia(half, chosenRow, field, ids) },
                    onMessage = onMessage,
                    onError = onProblem,
                )
            }
        }

        val siblings = remember(entity) { entity?.liveFields.orEmpty().associateBy { it.key } }
        val derivationSources = dwChooserDerivationSources(chosen, siblings, media)
        val plateField = siblings.values.firstOrNull { dwOffersSketchRectify(it, siblings) }
        val measureTargets = if (siblings.values.any { dwOffersPhotoMeasure(it, siblings) }) {
            dwMeasurableLengthFields(siblings)
        } else {
            emptyList()
        }

        if (chosen != null) {
            DwSketchDerivationSection(
                sources = derivationSources,
                photoFieldLabels = dwSketchSourceFields(siblings).map { it.label },
                rowName = dwChooserRowLabel(chosen, rows.indexOf(chosen)),
                plateField = plateField,
                plateFileName = plateField
                    ?.let { dwChooserHeldMedia(chosen, it.key).firstOrNull() }
                    ?.let(media.resolve)
                    ?.displayName,
                targets = measureTargets,
                rowValues = chosen.values,
                recordCategory = DwValues.text(chosen.values["category"]).takeIf { it.isNotBlank() },
                media = media,
                runtime = rememberDwTraceRuntime(),
                enabled = half.reconciled,
                onAttachedToPlate = { id ->
                    plateField?.let { onWriteMedia(half, chosenRow, it, listOf(id)) }
                },
                onPropose = { key, value, _ -> onWriteScalar(half, chosenRow, key, value) },
                onMessage = onMessage,
                onError = onProblem,
                exportCard = { slot ->
                    DwSketchTraceExportCard(
                        repository = repository,
                        traceSvg = slot.result.svg,
                        geometry = slot.result.geometry,
                        documentWidth = slot.result.width,
                        documentHeight = slot.result.height,
                        documentBackground = slot.documentBackground,
                        sourceName = slot.sourceName,
                        shapeCount = slot.result.shapeCount,
                        nodeCount = slot.result.nodeCount,
                        frameNote = slot.result.frameNote,
                        isPreview = slot.result.isPreview,
                        exporter = rememberDwTraceExporter(),
                        busy = slot.busy,
                        onBusyChange = slot.onBusyChange,
                        onError = onProblem,
                        onBackgroundChange = slot.onBackgroundChange,
                        onNeedFullResolution = slot.onNeedFullResolution,
                    )
                },
            )
        }
    }
}

private fun dwChooserDerivationSources(
    row: DraftRow?,
    siblings: Map<String, FieldDto>,
    media: DwMediaBridge,
): List<DwSketchSource> {
    if (row == null) return emptyList()
    return dwSketchSourceFields(siblings).flatMap { imageField ->
        dwChooserHeldMedia(row, imageField.key)
            .mapNotNull(media.resolve)
            .filter { it.mediaType.equals("IMAGE", ignoreCase = true) }
            .map { DwSketchSource(fieldLabel = imageField.label, item = it) }
    }
}

private data class DwChooserHalf(
    val entityKey: String,
    val stageKey: String?,
    val spec: StageDto?,
    val stage: StageDraft? = null,
    val reconciled: Boolean = false,
    val failure: String? = null,
) {
    val rows: List<DraftRow> get() = dwChooserRows(stage, entityKey)

    private val isSketch: Boolean get() = entityKey == DW_CHOOSER_SKETCH_ENTITY

    val title: String get() = if (isSketch) "Sketches" else "Prototypes"
    val what: String get() = if (isSketch) "This sketch" else "This prototype"
    val addLabel: String get() = if (isSketch) "Add a sketch" else "Add a prototype"
    val pickerLabel: String get() = if (isSketch) "Which sketch" else "Which prototype"

    val pickerPlaceholder: String
        get() = if (isSketch) "Choose a sketch" else "Choose a prototype"

    val emptyNote: String
        get() = if (isSketch) DW_SKETCH_CHOOSER_NO_SKETCHES_YET else DW_SKETCH_CHOOSER_NO_PROTOTYPES_YET

    val mediaNotes: List<String>
        get() = if (isSketch) {
            emptyList()
        } else {
            listOf(
                DW_PROTOTYPE_3D_IN_THE_REPORT,
                DW_TURNTABLE_CAPTURE_ADVICE,
                DW_PROTOTYPE_MODEL_FORMATS,
            )
        }

    val stageNote: String
        get() = if (isSketch) DW_SKETCH_CHOOSER_SKETCH_ELSEWHERE else DW_SKETCH_CHOOSER_PROTOTYPE_ELSEWHERE
}

private fun dwChooserStageForField(
    fieldKey: String,
    sketch: DwChooserHalf?,
    prototype: DwChooserHalf?,
): String? = when (fieldKey) {
    in DW_CHOOSER_SKETCH_FIELDS -> sketch?.stageKey
    in DW_CHOOSER_PROTOTYPE_FIELDS -> prototype?.stageKey
    else -> null
}

internal const val DW_SKETCH_CHOOSER_NO_SKETCHES_YET: String =
    "No sketches yet. Tap “Add a sketch”."

internal const val DW_SKETCH_CHOOSER_NO_PROTOTYPES_YET: String =
    "No prototypes yet. Tap “Add a prototype”."

internal const val DW_SKETCH_CHOOSER_SKETCH_ELSEWHERE: String =
    "Name, caption, category and manual dimensions are on the stage form."

internal const val DW_SKETCH_CHOOSER_PROTOTYPE_ELSEWHERE: String =
    "Name, materials, stage log and costing are on the stage form."

internal const val DW_SKETCH_CHOOSER_STAGE_OFFLINE: String =
    "No connection — couldn't load this stage. Try again."

internal const val DW_SKETCH_CHOOSER_STAGE_REFUSED: String =
    "This stage could not be loaded."

internal const val DW_SKETCH_CHOOSER_STALE: String =
    "Adding is paused until this stage loads. Nothing has been lost."

internal const val DW_SKETCH_CHOOSER_NO_STAGE: String =
    "Nothing can be added yet. Open the workshop online; if this persists, update the app."

internal const val DW_SKETCH_CHOOSER_NO_MEDIA_FIELDS: String =
    "Add files from the stage form."

internal const val DW_SKETCH_CHOOSER_ROW_NOT_ADDED: String =
    "Row has NOT been added — couldn't save on this phone (storage full?). Nothing has changed."

internal const val DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED: String =
    "File has NOT been attached — couldn't save on this phone. The file is not lost; attach it from the stage form."

internal const val DW_SKETCH_CHOOSER_MEASUREMENT_NOT_SAVED: String =
    "Measurement NOT saved — couldn't save on this phone (storage full?). Note it down before leaving."
