package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.data.userMessage

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import com.designprototype.workshop.data.DwStageProvenanceDto
import com.designprototype.workshop.data.DwFieldStampDto
import com.designprototype.workshop.data.AppScope
import com.designprototype.workshop.data.CUSTOM_ENTITY_KEY
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DW_COPYABLE_STAGES
import com.designprototype.workshop.data.DW_PARTICIPANTS_STAGE
import com.designprototype.workshop.data.dwCopiesAfterPush
import com.designprototype.workshop.data.dwCopyIntoEmpty
import com.designprototype.workshop.data.designerPrefillIntoEmpty
import com.designprototype.workshop.data.dwPendingCopies
import com.designprototype.workshop.data.DW_ROW_KEY_SEPARATOR
import com.designprototype.workshop.data.DW_ROW_REFUSAL_KEY
import com.designprototype.workshop.data.DraftMedia
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwCustomCache
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwReportFreeze
import com.designprototype.workshop.data.DwTier3Consent
import com.designprototype.workshop.data.DwImageQuality
import com.designprototype.workshop.data.DwStageFocus
import com.designprototype.workshop.data.DwStageRefusalReport
import com.designprototype.workshop.data.DwTier
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StagePush
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.computeStageCompleteness
import com.designprototype.workshop.data.customFieldsForStage
import com.designprototype.workshop.data.dwCarryHoldings
import com.designprototype.workshop.data.dwCustomDefinition
import com.designprototype.workshop.data.dwDecodeStageRefusals
import com.designprototype.workshop.data.dwFoldServerStage
import com.designprototype.workshop.data.dwHoldingsFrom
import com.designprototype.workshop.data.dwRestoreStageRefusals
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.dwTier3ConsentOf
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.DwAutoNumber
import com.designprototype.workshop.data.DwStageAssist
import com.designprototype.workshop.data.DwStageAssistDto
import com.designprototype.workshop.data.DwStageComputed
import com.designprototype.workshop.data.dwComputedPatch
import com.designprototype.workshop.data.dwLocalStageAssist
import com.designprototype.workshop.data.dwDisplayText
import com.designprototype.workshop.data.entity
import com.designprototype.workshop.data.field
import com.designprototype.workshop.data.rowTitleField
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.data.singleton
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.SearchableMultiSelectSheet
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign

private const val SAVE_DEBOUNCE_MS = 800L

private const val FIELD_FLASH_MS = 1400L

@Immutable
private data class CollectionRow(
    val rowId: String,
    val values: Map<String, JsonElement>,
)

@Immutable
private data class StageState(
    val singleton: Map<String, JsonElement> = emptyMap(),
    val collections: Map<String, List<CollectionRow>> = emptyMap(),
    val provenance: DwStageProvenanceDto = DwStageProvenanceDto(),
    val custom: Map<String, JsonElement> = emptyMap(),
    val customSeen: Boolean = false,
    val copiedFrom: Map<String, String> = emptyMap(),
    val editedKeys: Set<String> = emptySet(),
)

private fun StageState.countedSingleton(): Map<String, JsonElement> {
    val hidden = dwPendingCopies(copiedFrom, provenance.singleton, editedKeys)
    return if (hidden.isEmpty()) singleton else singleton.filterKeys { it !in hidden }
}

private fun StageState.edited(keys: Collection<String>): StageState =
    copy(copiedFrom = copiedFrom - keys.toSet(), editedKeys = editedKeys + keys)

private enum class SaveState { CLEAN, PENDING, SAVING, ON_DEVICE, SYNCED }

private class PendingWrite {

    class Outstanding(
        val workshopId: String,
        val stage: StageDto,
        val state: StageState,
        val seen: Boolean,
        val emptied: Set<String>,
        val deletedRows: Set<String>,
        val syncable: Boolean,
    )

    var revision: Int = 0
        private set

    private var outstanding: Outstanding? = null

    fun record(
        revision: Int,
        workshopId: String,
        stage: StageDto,
        state: StageState,
        seen: Boolean,
        emptied: Set<String>,
        deletedRows: Set<String>,
        syncable: Boolean,
    ) {
        this.revision = revision
        outstanding = Outstanding(workshopId, stage, state, seen, emptied, deletedRows, syncable)
    }

    fun clear() {
        revision = 0
        outstanding = null
    }

    fun take(): Outstanding? = outstanding.also { clear() }

    fun adoptDeletionRecord(emptied: Set<String>, deletedRows: Set<String>) {
        val current = outstanding ?: return
        outstanding = Outstanding(
            workshopId = current.workshopId,
            stage = current.stage,
            state = current.state,
            seen = current.seen,
            emptied = emptied,
            deletedRows = deletedRows,
            syncable = current.syncable,
        )
    }
}

@Composable
fun StageScreen(
    repository: WorkshopRepository,
    workshopId: String,
    stageKey: String,
    focus: DwStageFocus? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    inlineRecords: DwInlineRecordHost? = null,
    onOpenConsent: (() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalDwOpenConsent provides onOpenConsent) {
        StageScreenBody(
            repository = repository,
            workshopId = workshopId,
            stageKey = stageKey,
            focus = focus,
            onMessage = onMessage,
            onError = onError,
            inlineRecords = inlineRecords,
        )
    }
}

@Composable
private fun StageScreenBody(
    repository: WorkshopRepository,
    workshopId: String,
    stageKey: String,
    focus: DwStageFocus? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    inlineRecords: DwInlineRecordHost? = null,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var stage by remember(stageKey) { mutableStateOf<StageDto?>(null) }
    var state by remember(stageKey) { mutableStateOf(StageState()) }
    var loading by remember(stageKey) { mutableStateOf(true) }
    var saveState by remember(stageKey) { mutableStateOf(SaveState.CLEAN) }
    var syncNote by remember(stageKey) { mutableStateOf<String?>(null) }
    var showAdvanced by remember(stageKey) { mutableStateOf(false) }
    var revision by remember(stageKey) { mutableIntStateOf(0) }
    var mediaIndex by remember(workshopId) { mutableStateOf<Map<String, DraftMedia>>(emptyMap()) }
    var previewSchema by remember(workshopId) { mutableStateOf<com.designprototype.workshop.data.SchemaResponse?>(null) }
    var syncId by remember(workshopId) { mutableStateOf<String?>(null) }
    var assist by remember(stageKey) { mutableStateOf<DwStageAssistDto?>(null) }
    var assistToken by remember(stageKey) { mutableIntStateOf(0) }
    var appliedSuggestions by remember(stageKey) { mutableStateOf<Set<String>>(emptySet()) }
    var venuePrompt by remember(stageKey) { mutableStateOf(false) }
    var seen by remember(stageKey) { mutableStateOf(false) }
    var emptied by remember(stageKey) { mutableStateOf<Set<String>>(emptySet()) }
    var deletedRows by remember(stageKey) { mutableStateOf<Set<String>>(emptySet()) }
    var downloadNote by remember(stageKey) { mutableStateOf<String?>(null) }
    var noteTitle by remember(stageKey) { mutableStateOf<String?>(null) }
    var refusals by remember(stageKey) { mutableStateOf<DwStageRefusalReport?>(null) }
    var readBucket by remember(stageKey) { mutableStateOf<StageBucketDto?>(null) }
    var definition by remember(workshopId) { mutableStateOf<DwCustomCache?>(null) }
    var consentPublication by remember { mutableStateOf(0L) }

    DisposableEffect(workshopId, stageKey) {
        onDispose { DwDictationRun.forgetWorkshopConsent(consentPublication) }
    }

    LaunchedEffect(workshopId, stageKey) {
        loading = true
        runCatching {
            val schema = repository.designWorkshopSchema(appContext)
            previewSchema = schema
            val spec = schema.stages.firstOrNull { it.key == stageKey }
                ?: error("Update the app to open this stage.")

            val draft = WorkshopDraftStore.load(appContext, workshopId)
            mediaIndex = draft?.media.orEmpty().associateBy { it.id }
            val serverId = draft?.remoteId?.takeIf { it.isNotBlank() }
                ?: workshopId.takeIf { it.isNotBlank() && !isLocalOnlyWorkshop(it) }
            syncId = serverId
            val fromDraft = dwTier3ConsentOf(draft?.consent?.decision)
            consentPublication = DwDictationRun.publishWorkshopConsent(
                consent = if (fromDraft == DwTier3Consent.NOT_RECORDED) {
                    DwDictationRun.consentAnswerSeen(workshopId) ?: fromDraft
                } else {
                    fromDraft
                },
                serverWorkshopId = serverId,
            )
            definition = runCatching {
                repository.dwCustomDefinition(appContext, workshopId, serverId)
            }.getOrNull()
            val local = draft?.stages?.get(stageKey)

            val remoteId = serverId
            val canReach = remoteId != null && ConnectivityObserver.isOnline(appContext)
            val plan = dwStageReadPlan(local, remoteExists = remoteId != null, canReach = canReach)
            val loaded = when (plan) {
                DwStageRead.DRAFT_AS_IS -> {
                    val held = local!!
                    StageLoad(
                        fromDraft(spec, held),
                        seen = held.stageSeen,
                        downloadFailed = !held.stageSeen && remoteId != null,
                        heldWorkAlready = true,
                    )
                }
                DwStageRead.FOLD_SERVER_COPY -> {
                    val remote = runCatching { repository.designWorkshopStage(remoteId!!, stageKey) }
                        .getOrNull()
                    if (remote == null) {
                        StageLoad(
                            fromDraft(spec, local!!),
                            seen = false,
                            downloadFailed = true,
                            heldWorkAlready = true,
                        )
                    } else {
                        readBucket = remote
                        val fold = dwFoldServerStage(spec, local, remote, stageKey)
                        WorkshopDraftStore.updateBookkeeping(appContext, workshopId) { draft ->
                            draft.copy(stages = draft.stages + (stageKey to fold.draft))
                        }
                        StageLoad(
                            fromDraft(spec, fold.draft),
                            seen = true,
                            downloadFailed = false,
                            heldWorkAlready = true,
                            foldNotice = fold.notice,
                        )
                    }
                }
                DwStageRead.SEED_FROM_SERVER -> {
                    val remote =
                        runCatching { repository.designWorkshopStage(remoteId!!, stageKey) }.getOrNull()
                    if (remote != null) {
                        readBucket = remote
                        StageLoad(fromRemote(spec, remote), seen = true, downloadFailed = false)
                    } else {
                        StageLoad(StageState(), seen = false, downloadFailed = true)
                    }
                }
                DwStageRead.SEED_BLANK ->
                    StageLoad(StageState(), seen = true, downloadFailed = false)
            }
            refusals = dwRestoreStageRefusals(
                spec = spec,
                record = draft?.sync?.stages?.get(stageKey)?.refusal,
                customFields = customFieldsForStage(definition, spec.key),
            )
            Triple(spec, loaded, local)
        }.onSuccess { (spec, loaded, local) ->
            stage = spec
            state = loaded.state
            seen = loaded.seen
            showAdvanced = focusOpensAdvanced(spec, focus)
            saveState = SaveState.CLEAN
            noteTitle = when {
                loaded.foldNotice != null -> "This stage has been updated"
                loaded.downloadFailed && loaded.heldWorkAlready ->
                    "This stage has not been downloaded yet"
                else -> null
            }
            downloadNote = when {
                loaded.foldNotice != null -> loaded.foldNotice
                !loaded.downloadFailed -> null
                !loaded.heldWorkAlready ->
                    "Offline — saved answers for this stage aren't shown. What you type will still be sent."
                else ->
                    "Not downloaded yet. Your answers will be sent; deletions sync once the stage downloads."
            }
            emptied = local?.emptiedEntities.orEmpty().toSet()
            deletedRows = local?.deletedRowKeys.orEmpty().toSet()
        }.onFailure { error ->
            onError(error.userMessage("Unable to open this stage."))
        }
        loading = false
    }

    val restoredNeedsHoldings = !loading &&
        refusals?.let { it.recordedAt != null && it.needsRead } == true
    LaunchedEffect(workshopId, stageKey, restoredNeedsHoldings) {
        if (!restoredNeedsHoldings) return@LaunchedEffect
        val spec = stage ?: return@LaunchedEffect
        val bucket = readBucket ?: run {
            val id = syncId ?: return@LaunchedEffect
            if (!ConnectivityObserver.isOnline(appContext)) return@LaunchedEffect
            runCatching { repository.designWorkshopStage(id, spec.key) }.getOrNull()
        } ?: return@LaunchedEffect
        readBucket = bucket
        refusals = refusals?.let { dwHoldingsFrom(it, bucket) }
    }

    val pending = remember { PendingWrite() }

    suspend fun saveAndSync() {
        val spec = stage ?: return
        saveState = SaveState.SAVING
        val at = revision
        val snapshot = state

        val emptiedSent = emptied
        val deletedRowsSent = deletedRows

        runCatching {
            persistLocally(appContext, workshopId, spec, snapshot, seen, emptiedSent, deletedRowsSent)
        }
            .onFailure { error ->
                if (error is CancellationException) throw error
                saveState = SaveState.PENDING
                onError(error.userMessage("Couldn't save this stage on the phone."))
                return
            }
        if (pending.revision == at) pending.clear()
        saveState = SaveState.ON_DEVICE

        if (syncId == null) return
        when (val push = runCatching {
            WorkshopSyncEngine.pushStage(appContext, repository, workshopId, spec)
        }.getOrElse {
            if (it is CancellationException) throw it
            StagePush.NotSent
        }) {
            is StagePush.Sent -> {
                saveState = SaveState.SYNCED
                dwCopiesAfterPush(state.copiedFrom, state.provenance, push.copiedFrom)?.let { (marks, stamps) ->
                    state = state.copy(copiedFrom = marks, provenance = stamps)
                }
                dwAdoptDeletionRecordAfterPush(
                    sentEmptied = emptiedSent,
                    sentDeletedRows = deletedRowsSent,
                    screenEmptied = { emptied },
                    screenDeletedRows = { deletedRows },
                    loadStage = {
                        WorkshopDraftStore.load(appContext, workshopId)?.stages?.get(spec.key)
                    },
                ) { nextEmptied, nextDeletedRows ->
                    emptied = nextEmptied
                    deletedRows = nextDeletedRows
                    pending.adoptDeletionRecord(nextEmptied, nextDeletedRows)
                }
                syncNote = if (push.result.droppedKeys.isEmpty()) {
                    null
                } else {
                    "${plural(push.result.droppedKeys.size, "field")} ${if (push.result.droppedKeys.size == 1) "wasn't" else "weren't"} saved because the form changed. Nothing you typed is lost."
                }
                val decoded = dwDecodeStageRefusals(
                    spec = spec,
                    entries = push.entries,
                    errors = push.result.errors,
                    customFields = customFieldsForStage(definition, spec.key),
                ).copy(
                    droppedCustomKeys = push.result.droppedCustomKeys,
                )
                val carried = dwCarryHoldings(refusals, decoded)
                refusals = if (carried.isEmpty) {
                    null
                } else if (!carried.needsRead) {
                    carried
                } else {
                    val bucket = syncId?.let { id ->
                        runCatching { repository.designWorkshopStage(id, spec.key) }.getOrNull()
                    }
                    if (bucket == null) carried else dwHoldingsFrom(carried, bucket)
                }
            }
            StagePush.AlreadySent -> {
                saveState = SaveState.SYNCED
                syncNote = null
            }
            is StagePush.HeldBack -> {
                saveState = SaveState.ON_DEVICE
                syncNote = "Saved. Waiting for ${plural(push.files, "file")} to upload."
            }
            StagePush.NoRemoteYet, StagePush.NothingToSend, StagePush.NotSent -> {
                saveState = SaveState.ON_DEVICE
                syncNote = null
            }
        }
    }

    LaunchedEffect(revision) {
        if (revision == 0) return@LaunchedEffect
        if (stage == null) return@LaunchedEffect
        saveState = SaveState.PENDING
        delay(SAVE_DEBOUNCE_MS)
        saveAndSync()
    }

    DisposableEffect(workshopId, stageKey) {
        onDispose {
            val outstanding = pending.take() ?: return@onDispose
            AppScope.io.launch {
                runCatching {
                    persistLocally(
                        appContext,
                        outstanding.workshopId,
                        outstanding.stage,
                        outstanding.state,
                        outstanding.seen,
                        outstanding.emptied,
                        outstanding.deletedRows,
                    )
                }.onSuccess {
                    if (outstanding.syncable) {
                        runCatching {
                            WorkshopSyncEngine.pushStage(
                                appContext, repository, outstanding.workshopId, outstanding.stage,
                            )
                        }
                    }
                }
            }
        }
    }

    fun edit(transform: (StageState) -> StageState) {
        val next = transform(state)
        state = next
        revision++
        stage?.let { spec ->
            pending.record(
                revision = revision,
                workshopId = workshopId,
                stage = spec,
                state = next,
                seen = seen,
                emptied = emptied,
                deletedRows = deletedRows,
                syncable = syncId != null,
            )
        }
    }

    var prefillDone by remember(workshopId, stageKey) { mutableStateOf(false) }
    LaunchedEffect(workshopId, stageKey, loading) {
        if (loading || prefillDone) return@LaunchedEffect
        val spec = stage ?: return@LaunchedEffect
        prefillDone = true
        val values = runCatching { repository.designerPrefillFor(appContext, workshopId, syncId) }
            .getOrDefault(emptyMap())
        if (values.isEmpty()) return@LaunchedEffect
        val filled = designerPrefillIntoEmpty(spec, state.singleton, values)
        if (filled.isNotEmpty()) {
            edit { current -> current.copy(singleton = current.singleton.putAll(designerPrefillIntoEmpty(spec, current.singleton, filled))) }
        }
    }

    val screeningStore = remember(workshopId, stageKey) { DwScreeningStore(scope) }

    val media = remember(workshopId, stageKey, mediaIndex, screeningStore) {
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
                        transcribe = descriptor.transcribe,
                        spokenLanguage = descriptor.spokenLanguage,
                    )
                }
            },
            setTranscribe = { id, on ->
                scope.launch {
                    WorkshopDraftStore.setTranscribe(appContext, workshopId, id, on)?.let { changed ->
                        mediaIndex = mediaIndex + (id to changed)
                    }
                }
            },
            setSpokenLanguage = { id, code ->
                scope.launch {
                    WorkshopDraftStore.setSpokenLanguage(appContext, workshopId, id, code)?.let { changed ->
                        mediaIndex = mediaIndex + (id to changed)
                    }
                }
            },
            attach = { uris: List<Uri>, field: FieldDto, onAttached: (List<String>) -> Unit ->
                scope.launch {
                    val imported = mutableListOf<String>()
                    val added = mutableMapOf<String, com.designprototype.workshop.data.DraftMedia>()
                    var failures = 0
                    for (uri in uris) {
                        runCatching {
                            WorkshopDraftStore.importMedia(
                                context = appContext,
                                workshopId = workshopId,
                                uri = uri,
                                stageId = stageKey,
                                fieldKey = field.key,
                            )
                        }.onSuccess { descriptor ->
                            added[descriptor.id] = descriptor
                            imported += descriptor.id
                        }.onFailure {
                            failures++
                        }
                    }
                    if (added.isNotEmpty()) {
                        mediaIndex = mediaIndex + added
                    }
                    if (imported.isNotEmpty()) {
                        onAttached(imported)
                    }
                    if (failures > 0) {
                        onError(
                            if (imported.isEmpty()) "That file could not be attached."
                            else "$failures of ${uris.size} files couldn't be attached."
                        )
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
                val dir = java.io.File(
                    WorkshopDraftStore.workshopDir(appContext, workshopId), "captures"
                ).apply { mkdirs() }
                java.io.File(dir, "capture-${UUID.randomUUID()}$suffix")
            },
        )
    }

    val services = remember(syncId, workshopId, stageKey, inlineRecords) {
        DwFieldServices(
            repository = repository,
            workshopId = syncId,
            onMessage = onMessage,
            onError = onError,
            inlineRecords = inlineRecords,
        )
    }

    if (loading) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text("Opening the stage…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    val spec = stage ?: return
    val completeness = remember(spec, state, definition) {
        computeStageCompletenessFor(spec, state, definition)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StageHeader(
            spec,
            completeness.percent,
            saveState,
            syncNote,
            localOnly = syncId == null,
            refused = refusals?.count ?: 0,
            notStored = refusals?.droppedCustomKeys?.size ?: 0,
        )

        val previewServerCopy = remember(workshopId, scope) { DwPreviewServerCopy(scope) }
        DwStageDocumentPreview(
            workshopId = workshopId,
            stageTitle = spec.title,
            schema = previewSchema,
            revision = revision,
            liveDraft = liveDraft@{
                val remoteId = syncId
                val read = if (remoteId == null) {
                    DwServerRead.NotNeeded
                } else {
                    previewServerCopy.read(online = ConnectivityObserver.isOnline(appContext)) {
                        repository.designWorkshopStages(remoteId)
                    }
                }
                val registry = previewSchema ?: return@liveDraft DwStagePreviewSource(null, read)
                DwStagePreviewSource(
                    draft = dwStagePreviewDraft(
                        schema = registry,
                        workshopId = workshopId,
                        stageKey = spec.key,
                        local = WorkshopDraftStore.load(appContext, workshopId),
                        remoteId = remoteId,
                        remote = (read as? DwServerRead.Read)?.copy,
                        live = { existing -> dwLiveStageDraft(spec, state, existing) },
                    ),
                    serverRead = read,
                )
            },
        )

        DwReportFreeze.sentenceFor(workshopId)?.let { sentence ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    sentence,
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        downloadNote?.let { note ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        noteTitle ?: "This stage could not be downloaded",
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(note, color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        refusals?.takeIf { !it.isEmpty }?.let { report ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        if (report.count > 0) "Some answers were not accepted"
                        else "Some answers were not stored",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        report.heading,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    report.refusals.forEach { refusal ->
                        Text(
                            "• ${refusal.sentence}",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    report.unplaced.forEach { line ->
                        Text(
                            "• $line — couldn't be matched to this stage. Nothing was lost; show this to an admin.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        DwRecordingPlaceCard(
            value = state.singleton[DW_RECORDING_PLACE_KEY],
            repository = repository,
            onChange = { next ->
                edit { current ->
                    current.copy(singleton = current.singleton.put(DW_RECORDING_PLACE_KEY, next))
                }
            },
            onMessage = onMessage,
        )

        LaunchedEffect(syncId, stageKey, assistToken) {
            val target = syncId ?: return@LaunchedEffect
            repository.designWorkshopStageAssist(target, stageKey)?.let {
                assist = it
                appliedSuggestions = emptySet()
            }
        }
        var setupDraft by remember(workshopId) { mutableStateOf<Map<String, JsonElement>?>(null) }
        LaunchedEffect(workshopId, stageKey, saveState) {
            if (stageKey == DW_ROSTER_STAGE) {
                setupDraft = WorkshopDraftStore.load(appContext, workshopId)?.stages?.get(DW_SETUP_STAGE)?.values
            }
        }
        val stageAssist = remember(assist, setupDraft, state.collections["workshopScheduleDay"]) {
            dwLocalStageAssist(
                stageKey,
                assist,
                setupDraft,
                state.collections["workshopScheduleDay"].orEmpty().map { it.values },
            )
        }
        LaunchedEffect(saveState) {
            if (saveState == SaveState.SYNCED) assistToken++
            if ((saveState == SaveState.SYNCED || saveState == SaveState.ON_DEVICE) && stageKey == DW_SETUP_STAGE) {
                val venue = state.singleton["venueLocation"]
                venuePrompt = venue == null || venue is JsonNull
            }
        }
        LaunchedEffect(state.collections, stageAssist) {
            val patch = dwComputedPatch(
                stageKey = stageKey,
                assist = stageAssist,
                singleton = state.singleton,
                collections = state.collections.mapValues { (_, rows) -> rows.map { it.rowId to it.values } },
            )
            if (patch.singleton.isNotEmpty() || patch.rows.isNotEmpty()) {
                edit { current ->
                    current.copy(
                        singleton = current.singleton + patch.singleton,
                        collections = current.collections + patch.rows.mapValues { (_, rows) ->
                            rows.map { (rowId, values) -> CollectionRow(rowId = rowId, values = values) }
                        },
                    )
                }
            }
        }
        if (venuePrompt && stageKey == DW_SETUP_STAGE &&
            state.singleton["venueLocation"].let { it == null || it is JsonNull }
        ) {
            DwVenuePrompt { location ->
                edit { current -> current.copy(singleton = current.singleton.put("venueLocation", dwLocationToValue(location))) }
                venuePrompt = false
            }
        }
        val asPairs = state.collections.mapValues { (_, rows) -> rows.map { it.rowId to it.values } }
        DwStageAssistCard(
            suggestions = stageAssist?.suggestions.orEmpty().filter { suggestion ->
                !(suggestion.kind == "ADD_ROWS" && suggestion.id in appliedSuggestions) &&
                    DwStageAssist.stillApplies(suggestion, state.singleton, asPairs)
            },
            enabled = saveState != SaveState.SAVING,
        ) { suggestion ->
            val (single, nextRows) = dwApplySuggestion(
                suggestion,
                state.singleton,
                asPairs[suggestion.entityKey].orEmpty(),
            ) { UUID.randomUUID().toString() }
            appliedSuggestions = appliedSuggestions + suggestion.id
            edit { current ->
                current.copy(
                    singleton = single ?: current.singleton,
                    collections = if (nextRows == null) current.collections else current.collections +
                        (suggestion.entityKey to nextRows.map { (rowId, values) -> CollectionRow(rowId = rowId, values = values) }),
                )
            }
        }
        if (stageKey == DW_ROSTER_STAGE) {
            DwAttendanceCard(
                days = DwStageComputed.workshopDays(
                    state.collections["workshopScheduleDay"].orEmpty().map { it.values },
                    stageAssist?.attendanceDays.orEmpty(),
                ),
                participants = asPairs["participant"].orEmpty(),
                startDate = stageAssist?.startDate,
                enabled = saveState != SaveState.SAVING,
            ) { next ->
                edit { current ->
                    current.copy(
                        collections = current.collections +
                            ("participant" to next.map { (rowId, values) -> CollectionRow(rowId = rowId, values = values) })
                    )
                }
            }
        }
        if (stageKey == DW_PROTOTYPE_STAGE) {
            DwLogTodayCard(
                prototypes = asPairs["prototype"].orEmpty(),
                logs = state.collections["prototypeStageLog"].orEmpty().map { it.values },
                enabled = saveState != SaveState.SAVING,
            ) { values ->
                edit { current ->
                    val logs = current.collections["prototypeStageLog"].orEmpty() +
                        CollectionRow(rowId = UUID.randomUUID().toString(), values = values)
                    current.copy(collections = current.collections + ("prototypeStageLog" to logs))
                }
            }
        }
        if (stageKey == DW_SURVEY_STAGE) DwSittingsCard(assist?.sittings.orEmpty())
        DW_ROWS_FROM_PHOTOS[stageKey]?.let { (entityKey, fieldKey, noun) ->
            val target = spec.entity(entityKey)
            val imageField = target?.field(fieldKey)
            if (target != null && imageField != null) {
                DwRowsFromPhotosCard(title = target.title, noun = noun, enabled = saveState != SaveState.SAVING) { uris ->
                    media.attach(uris, imageField) { ids ->
                        edit { current ->
                            val before = current.collections[entityKey].orEmpty()
                            val added = ids.map { id ->
                                CollectionRow(rowId = UUID.randomUUID().toString(), values = mapOf(fieldKey to JsonPrimitive(id)))
                            }
                            val numbered = DwAutoNumber.apply(
                                target,
                                before.map { it.rowId to it.values },
                                (before + added).map { it.rowId to it.values },
                                stageAssist?.startDate,
                            ).map { (rowId, values) -> CollectionRow(rowId = rowId, values = values) }
                            current.copy(collections = current.collections + (entityKey to numbered))
                        }
                    }
                }
            }
        }

        if (spec.key in DW_COPYABLE_STAGES) {
            val labels = spec.singleton?.liveFields.orEmpty().associate { it.key to it.label }
            DwCopyFromEarlierCard(
                repository = repository,
                serverId = syncId,
                stageKey = spec.key,
                online = ConnectivityObserver.isOnline(appContext),
                enabled = true,
                labels = labels,
                pending = dwPendingCopies(state.copiedFrom, state.provenance.singleton, state.editedKeys)
                    .filter { it in labels }
                    .toSet(),
                localMarks = state.copiedFrom.keys,
                onCopy = { sourceId, values ->
                    val filled = dwCopyIntoEmpty(state.singleton, values.filterKeys { it in labels })
                    if (filled.isNotEmpty()) {
                        edit { current ->
                            current.copy(
                                singleton = current.singleton.putAll(filled),
                                copiedFrom = current.copiedFrom + filled.keys.associateWith { sourceId },
                                editedKeys = current.editedKeys - filled.keys,
                            )
                        }
                    }
                    filled.size
                },
                onKept = { local, stamps ->
                    edit { current ->
                        current.copy(
                            copiedFrom = current.copiedFrom - local,
                            provenance = if (stamps != null) current.provenance.copy(singleton = stamps) else current.provenance,
                        )
                    }
                },
            )
        }

        if (spec.key == DW_PARTICIPANTS_STAGE) {
            DwRosterImportCard(
                context = appContext,
                repository = repository,
                serverId = syncId,
                online = ConnectivityObserver.isOnline(appContext),
                enabled = true,
                onImported = reload@{
                    val id = syncId ?: return@reload
                    if (saveState == SaveState.PENDING || saveState == SaveState.SAVING) {
                        onMessage("Wait for this stage to save, then reload.")
                        return@reload
                    }
                    scope.launch {
                        val remote = runCatching { repository.designWorkshopStage(id, spec.key) }.getOrNull()
                        if (remote == null) {
                            onMessage("Couldn't reload this stage. Try again.")
                            return@launch
                        }
                        val local = WorkshopDraftStore.load(appContext, workshopId)?.stages?.get(spec.key)
                        val fold = dwFoldServerStage(spec, local, remote, spec.key)
                        WorkshopDraftStore.updateBookkeeping(appContext, workshopId) { draft ->
                            draft.copy(stages = draft.stages + (spec.key to fold.draft))
                        }
                        state = fromDraft(spec, fold.draft)
                        seen = true
                        fold.notice?.let {
                            noteTitle = "This stage has been updated"
                            downloadNote = it
                        }
                    }
                },
            )
        }

        spec.singleton?.let { entity ->
            EntitySection(
                entity = entity,
                values = state.singleton,
                media = media,
                services = services,
                errors = refusals?.byAddress?.get(entity.key).orEmpty(),
                focusFieldKey = focus?.takeIf { it.entityKey == entity.key && it.rowKey == null }?.fieldKey,
                showAdvanced = showAdvanced,
                onToggleAdvanced = { showAdvanced = !showAdvanced },
                onValueChange = { key, value ->
                    edit { current -> current.copy(singleton = current.singleton.put(key, value)).edited(listOf(key)) }
                },
                stamps = state.provenance.singleton,
                onPatch = { patch ->
                    edit { current -> current.copy(singleton = current.singleton.putAll(patch)).edited(patch.keys) }
                }
            )
        }

        DwCustomSectionForm(
            definition = definition,
            stageKey = spec.key,
            values = state.custom,
            errors = refusals?.byAddress?.get(CUSTOM_ENTITY_KEY).orEmpty(),
            onValueChange = { key, value ->
                edit { current -> current.copy(custom = current.custom.put(key, value)) }
            },
            services = services,
        )

        spec.collections.forEach { entity ->
            CollectionSection(
                entity = entity,
                rows = state.collections[entity.key].orEmpty(),
                stampsByEntry = state.provenance.collections[entity.key].orEmpty(),
                media = media,
                services = services,
                stageEntries = state.collections.values.sumOf { it.size } +
                    (if (state.singleton.isNotEmpty()) 1 else 0) +
                    (if (state.custom.isNotEmpty()) 1 else 0),
                focus = focus?.takeIf { it.entityKey == entity.key },
                errorsByRow = refusals?.refusals.orEmpty()
                    .filter { it.entityKey == entity.key && it.rowIndex != null }
                    .groupBy { it.rowIndex!! }
                    .mapValues { (_, list) -> list.associate { it.fieldKey to it.message } },
                onRowsChange = { rows ->
                    val before = state.collections[entity.key].orEmpty()
                    val had = before.isNotEmpty()
                    emptied = when {
                        rows.isEmpty() && had -> emptied + entity.key
                        rows.isNotEmpty() -> emptied - entity.key
                        else -> emptied
                    }
                    val nowHeld = rows.mapTo(HashSet()) { dwRowId(entity.key, it.rowId) }
                    val gone = before
                        .map { dwRowId(entity.key, it.rowId) }
                        .filter { it !in nowHeld }
                    deletedRows = (deletedRows + gone) - nowHeld
                    val numbered = DwAutoNumber.apply(
                        entity,
                        before.map { it.rowId to it.values },
                        rows.map { it.rowId to it.values },
                        stageAssist?.startDate,
                    ).map { (rowId, values) -> CollectionRow(rowId = rowId, values = values) }
                    edit { current -> current.copy(collections = current.collections + (entity.key to numbered)) }
                }
            )
        }

        DwStageFindings(
            stage = spec,
            workshopId = workshopId,
            repository = repository,
            rows = remember(state) {
                state.collections.mapValues { (_, rows) -> rows.map { it.values } }
            },
            onUse = { sheetEntryId, key, value ->
                edit { current ->
                    val sheets = current.collections["costSheet"].orEmpty().map { row ->
                        if (row.values["_entryId"]?.let { dwDisplayText(it) } == sheetEntryId) {
                            row.copy(values = row.values + (key to JsonPrimitive(value)))
                        } else row
                    }
                    current.copy(collections = current.collections + ("costSheet" to sheets))
                }
            },
        )

        OutlinedButton(
            onClick = { scope.launch { saveAndSync() } },
            enabled = saveState != SaveState.SAVING,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save and sync this stage now") }

        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun StageHeader(
    stage: StageDto,
    percent: Int,
    saveState: SaveState,
    syncNote: String?,
    localOnly: Boolean,
    refused: Int = 0,
    notStored: Int = 0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Stage ${stage.number} of 22" + if (stage.optionalStage) " · optional" else "",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stage.title,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        if (stage.purpose.isNotBlank()) {
            Text(stage.purpose, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
        }
        FieldLinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            when (saveState) {
                SaveState.CLEAN -> "$percent% of required fields done."
                SaveState.PENDING -> "$percent% complete · unsaved changes"
                SaveState.SAVING -> "$percent% complete · saving…"
                SaveState.ON_DEVICE -> if (localOnly) {
                    "$percent% · saved on this phone. Upload the workshop from the list when online."
                } else {
                    "$percent% · saved, not yet synced"
                }
                SaveState.SYNCED -> when {
                    refused > 0 -> "$percent% · saved; $refused answer" +
                        (if (refused == 1) " was" else "s were") +
                        " not accepted — see below." +
                        if (notStored > 0) {
                            " $notStored more " + (if (notStored == 1) "was" else "were") +
                                " not stored — the form no longer asks for " +
                                (if (notStored == 1) "it" else "them") + "."
                        } else ""
                    notStored > 0 -> "$percent% · saved; $notStored answer" +
                        (if (notStored == 1) " was" else "s were") +
                        " not stored — the form no longer asks for " +
                        (if (notStored == 1) "it" else "them") +
                        ". See below."
                    else -> "$percent% complete · saved and synced"
                }
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        syncNote?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (stage.notes.isNotBlank()) {
            Text(stage.notes, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntitySection(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    media: DwMediaBridge,
    services: DwFieldServices,
    focusFieldKey: String? = null,
    errors: Map<String, String> = emptyMap(),
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onValueChange: (String, JsonElement?) -> Unit,
    onPatch: (Map<String, JsonElement?>) -> Unit,
    stamps: Map<String, DwFieldStampDto> = emptyMap(),
    resetKey: Any = entity.key,
) {
    val captions = remember(entity) { entity.liveFields.filter { it.captionFor.isNotBlank() } }
    val captionByTarget = remember(captions) { captions.associateBy { it.captionFor } }
    val visible = remember(entity, captions) {
        entity.liveFields.filter { it.captionFor.isBlank() && it.widget !in DW_KNOWN_WIDGETS }
    }
    val upfront = remember(visible) { visible.filter { DwTier.of(it.tier) != DwTier.ADVANCED } }
    val advanced = remember(visible) { visible.filter { DwTier.of(it.tier) == DwTier.ADVANCED } }
    val fieldsByKey = remember(entity) { entity.liveFields.associateBy { it.key } }

    val marks = remember(entity, values, errors) {
        DwValues.validate(entity, values, enforceRequired = false) + errors
    }

    val missingViews = remember(entity.key, values) { DwImageQuality.findMissingViews(entity.key, values) }

    val arrival = remember { BringIntoViewRequester() }
    var marked by remember(focusFieldKey) { mutableStateOf(false) }
    LaunchedEffect(focusFieldKey) {
        if (focusFieldKey == null) return@LaunchedEffect
        withFrameNanos { }
        try {
            arrival.bringIntoView()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
        marked = true
        delay(FIELD_FLASH_MS)
        marked = false
    }

    val anchor = Modifier
        .bringIntoViewRequester(arrival)
        .border(
            width = 2.dp,
            color = if (marked) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = RoundedCornerShape(10.dp),
        )
        .padding(6.dp)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        if (entity.description.isNotBlank()) {
            Text(entity.description, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        missingViews.firstOrNull()?.let { DwMissingViewsNote(it.message) }
        upfront.forEach { field ->
            FieldRenderer(
                field = field,
                value = values[field.key],
                onChange = { next -> onValueChange(field.key, next) },
                modifier = if (field.key == focusFieldKey) anchor else Modifier,
                error = marks[field.key],
                media = media,
                caption = captionByTarget[field.key],
                captionValue = captionByTarget[field.key]?.let { values[it.key] },
                onCaptionChange = { next ->
                    captionByTarget[field.key]?.let { onValueChange(it.key, next) }
                },
                resetKey = resetKey,
                services = services,
                siblings = fieldsByKey,
                rowValues = values,
                onPatch = onPatch,
                stamp = stamps[field.key],
            )
            if (field.refSuggest != null) {
                DwRefSuggestion(entity, field, values, services, enabled = true, onPatch = onPatch)
            }
        }
        if (advanced.isNotEmpty()) {
            DisclosureHeader(
                label = "More (${advanced.size})",
                expanded = showAdvanced,
                onToggle = onToggleAdvanced
            )
            if (showAdvanced) {
                advanced.forEach { field ->
                    FieldRenderer(
                        field = field,
                        value = values[field.key],
                        onChange = { next -> onValueChange(field.key, next) },
                        modifier = if (field.key == focusFieldKey) anchor else Modifier,
                        error = marks[field.key],
                        media = media,
                        caption = captionByTarget[field.key],
                        captionValue = captionByTarget[field.key]?.let { values[it.key] },
                        onCaptionChange = { next ->
                            captionByTarget[field.key]?.let { onValueChange(it.key, next) }
                        },
                        resetKey = resetKey,
                        services = services,
                        siblings = fieldsByKey,
                        rowValues = values,
                        onPatch = onPatch,
                        stamp = stamps[field.key],
                    )
                    if (field.refSuggest != null) {
                        DwRefSuggestion(entity, field, values, services, enabled = true, onPatch = onPatch)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclosureHeader(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.field.muted
        )
    }
}

@Composable
private fun CollectionSection(
    entity: EntityDto,
    rows: List<CollectionRow>,
    media: DwMediaBridge,
    services: DwFieldServices,
    stageEntries: Int = 0,
    focus: DwStageFocus? = null,
    errorsByRow: Map<Int, Map<String, String>> = emptyMap(),
    stampsByEntry: Map<String, Map<String, DwFieldStampDto>> = emptyMap(),
    onRowsChange: (List<CollectionRow>) -> Unit,
) {
    var expanded by remember(entity.key, focus, errorsByRow) {
        mutableStateOf(focus?.rowKey ?: errorsByRow.keys.minOrNull()?.let { rows.getOrNull(it)?.rowId })
    }
    val titleField = remember(entity) { entity.rowTitleField }

    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val gapPx = with(LocalDensity.current) { COLLECTION_ROW_GAP.toPx() }

    val heights = remember { mutableStateMapOf<String, Float>() }
    var drag by remember { mutableStateOf<DwCollectionDrag?>(null) }
    var announcement by remember(entity.key) { mutableStateOf("") }

    val currentRows by rememberUpdatedState(rows)
    val currentChange by rememberUpdatedState(onRowsChange)

    fun labelFor(rowId: String): String {
        val index = currentRows.indexOfFirst { it.rowId == rowId }
        val row = currentRows.getOrNull(index) ?: return entity.title
        return dwCollectionRowTitle(entity, titleField, row, index)
    }

    fun commitMove(rowId: String, from: Int, to: Int) {
        val before = currentRows
        val next = dwMovedTo(before, from, to)
        if (next == before) return
        val label = labelFor(rowId)
        currentChange(next)
        val landed = next.indexOfFirst { it.rowId == rowId }
        if (landed >= 0) announcement = dwRowMovedSentence(label, landed, next.size)
    }

    val reorderable = rows.size > 1

    val context = LocalContext.current
    val bulkField = remember(entity) { dwBulkField(entity) }
    val bulkFields = remember(entity) { entity.liveFields.associateBy { it.key } }
    var bulkOptions by remember(entity.key) { mutableStateOf<List<DwReferenceOption>>(emptyList()) }
    var bulkTruncated by remember(entity.key) { mutableStateOf(false) }
    var bulkFresh by remember(entity.key) { mutableStateOf(false) }
    var bulkLoading by remember(entity.key) { mutableStateOf(false) }
    var bulkSheet by remember(entity.key) { mutableStateOf(false) }
    var bulkQuery by remember(entity.key) { mutableStateOf("") }
    var bulkFound by remember(entity.key) { mutableStateOf<List<DwReferenceOption>?>(null) }
    val bulkSeen = remember(entity.key) { LinkedHashMap<String, DwReferenceOption>() }
    val bulkScope = rememberCoroutineScope()
    LaunchedEffect(bulkField?.refModel, bulkField?.refScope, services.workshopId) {
        val field = bulkField ?: return@LaunchedEffect
        services.repository.designWorkshopReferences(
            context = context,
            workshopId = services.workshopId,
            model = field.refModel,
            scope = field.refScope,
            filterValue = "",
            cacheOnly = true,
        ) { list, _ -> if (!bulkFresh) bulkOptions = list.items }
    }
    suspend fun refreshBulk() {
        val field = bulkField ?: return
        bulkLoading = true
        try {
            val answered = services.repository.designWorkshopReferences(
                context = context,
                workshopId = services.workshopId,
                model = field.refModel,
                scope = field.refScope,
                filterValue = "",
                limit = null,
            ) { list, truncated ->
                bulkOptions = list.items
                bulkTruncated = truncated
            }
            if (answered) bulkFresh = true
        } finally {
            bulkLoading = false
        }
    }
    val bulkChosen = remember(rows, bulkField) {
        bulkField?.let { field -> dwBulkChosenIds(rows.map { it.values }, field) }.orEmpty()
    }
    val bulkRemaining = remember(bulkOptions, bulkChosen) { bulkOptions.filter { it.id !in bulkChosen } }
    val bulkShown = remember(bulkFound, bulkRemaining, bulkChosen) {
        bulkFound?.filter { it.id !in bulkChosen } ?: bulkRemaining
    }
    SideEffect { bulkShown.forEach { bulkSeen.putIfAbsent(it.id, it) } }
    fun addFromReferences(picked: List<DwReferenceOption>, truncated: Boolean = false) {
        val field = bulkField ?: return
        val outcome = dwBulkAdd(picked, field, bulkFields, bulkChosen, stageEntries)
        if (outcome.created.isNotEmpty()) {
            onRowsChange(rows + outcome.created.map { CollectionRow(UUID.randomUUID().toString(), it) })
            expanded = null
        }
        announcement = dwBulkAddedSentence(outcome, truncated = truncated, fieldLabel = field.label)
    }
    fun addAllFromReferences() {
        bulkScope.launch {
            refreshBulk()
            val fresh = bulkOptions.filter { it.id !in bulkChosen }
            if (fresh.isEmpty()) {
                announcement = if (bulkFresh || bulkOptions.isNotEmpty()) "Everything is already on this list." else DW_BULK_NO_LIST
                return@launch
            }
            addFromReferences(fresh, truncated = bulkTruncated)
        }
    }
    fun searchBulk(term: String) {
        val field = bulkField ?: return
        bulkQuery = term
        if (term.isBlank()) {
            bulkFound = null
            return
        }
        bulkScope.launch {
            delay(300)
            if (bulkQuery != term) return@launch
            val answer = services.repository.designWorkshopReferenceSearch(
                workshopId = services.workshopId,
                model = field.refModel,
                scope = field.refScope,
                search = term,
                limit = DW_BULK_PAGE,
            )
            if (bulkQuery != term) return@launch
            bulkFound = answer?.options ?: dwBulkLocalSearch(bulkOptions, term)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(COLLECTION_ROW_GAP),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity.title,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (rows.isEmpty()) "None recorded yet" else "${rows.size} recorded",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = {
                val fresh = CollectionRow(rowId = UUID.randomUUID().toString(), values = emptyMap())
                onRowsChange(rows + fresh)
                expanded = fresh.rowId
            }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
        if (entity.description.isNotBlank()) {
            Text(entity.description, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (bulkField != null) {
            val allLabel = dwBulkAllLabel(bulkField.refModel)
            val atCap = stageEntries >= DW_STAGE_ENTRY_CAP
            val bulkOpen = !atCap && (bulkRemaining.isNotEmpty() || !bulkFresh)
            ActionRow {
                OutlinedButton(
                    onClick = {
                        bulkQuery = ""
                        bulkFound = null
                        bulkSheet = true
                        bulkScope.launch { refreshBulk() }
                    },
                    enabled = bulkOpen,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Add several", textAlign = TextAlign.Center)
                }
                if (allLabel != null) {
                    OutlinedButton(
                        onClick = { addAllFromReferences() },
                        enabled = bulkOpen && !bulkLoading,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (bulkRemaining.isEmpty()) allLabel else "$allLabel (${bulkRemaining.size})",
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (bulkSheet && bulkField != null) {
            SearchableMultiSelectSheet(
                title = bulkField.label,
                options = buildReferenceOptions(bulkShown, "", tentativeWord = ""),
                emptyMessage = when {
                    bulkQuery.isNotBlank() -> "No match on the list."
                    bulkLoading && bulkOptions.isEmpty() -> "Loading the list…"
                    !bulkFresh && bulkOptions.isEmpty() -> DW_BULK_NO_LIST
                    else -> "Everything is already on this list."
                },
                onSearch = if (bulkTruncated) ({ term: String -> searchBulk(term) }) else null,
                note = if (bulkTruncated && bulkQuery.isBlank()) dwBulkCutShortNote(bulkOptions.size) else null,
                onDismiss = {
                    bulkSheet = false
                    bulkQuery = ""
                    bulkFound = null
                },
                onApply = { ids ->
                    addFromReferences(bulkSeen.values.filter { it.id in ids && it.id !in bulkChosen })
                },
            )
        }

        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            if (announcement.isNotEmpty()) {
                Text(
                    announcement,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
            }
        }

        rows.forEachIndexed { index, row ->
            val rowId = row.rowId
            val dragging = drag?.rowId == rowId
            val rowTitle = dwCollectionRowTitle(entity, titleField, row, index)
            val shift by animateFloatAsState(
                targetValue = drag.dwShiftFor(index, gapPx),
                animationSpec = if (dragging || reduceMotion) tween(0) else tween(180),
                label = "dwCollectionRowShift",
            )
            key(rowId) {
                CollectionRowCard(
                    entity = entity,
                    row = row,
                    index = index,
                    total = rows.size,
                    title = rowTitle,
                    expanded = expanded == rowId,
                    media = media,
                    services = services,
                    focusFieldKey = focus?.takeIf { it.rowKey == rowId }?.fieldKey,
                    errors = errorsByRow[index].orEmpty(),
                    dragging = dragging,
                    reorderable = reorderable,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = shift }
                        .onSizeChanged { size ->
                            heights[rowId] = size.height.toFloat()
                        },
                    gripModifier = Modifier
                        .semantics {
                            contentDescription = if (reorderable) {
                                "Reorder $rowTitle. Drag, or use the move buttons."
                            } else {
                                "Only one entry — nothing to reorder."
                            }
                        }
                        .pointerInput(rowId, reorderable) {
                            if (!reorderable) return@pointerInput
                            detectDragGestures(
                                onDragStart = {
                                    val snapshot = currentRows.map { it.rowId }
                                    val from = snapshot.indexOf(rowId)
                                    drag = if (from < 0) {
                                        null
                                    } else {
                                        DwCollectionDrag(
                                            rowId = rowId,
                                            from = from,
                                            to = from,
                                            offset = 0f,
                                            snapshot = snapshot,
                                            heights = snapshot.map { heights[it] ?: 0f },
                                        )
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag = drag?.dwAdvancedBy(amount.y, gapPx)
                                },
                                onDragEnd = {
                                    val current = drag
                                    drag = null
                                    if (current != null && current.to != current.from) {
                                        if (current.snapshot != currentRows.map { it.rowId }) {
                                            announcement =
                                                dwRowDragAbandonedSentence(labelFor(current.rowId))
                                        } else {
                                            commitMove(current.rowId, current.from, current.to)
                                        }
                                    }
                                },
                                onDragCancel = { drag = null },
                            )
                        },
                    onToggle = { expanded = if (expanded == rowId) null else rowId },
                    onMove = { delta -> commitMove(rowId, index, index + delta) },
                    onDelete = {
                        onRowsChange(rows.filterNot { it.rowId == rowId })
                        if (expanded == rowId) expanded = null
                    },
                    onValueChange = { fieldKey, value ->
                        onRowsChange(
                            rows.map { existing ->
                                if (existing.rowId == rowId) {
                                    existing.copy(values = existing.values.put(fieldKey, value))
                                } else {
                                    existing
                                }
                            }
                        )
                    },
                    onPatch = { patch ->
                        onRowsChange(
                            rows.map { existing ->
                                if (existing.rowId == rowId) {
                                    existing.copy(values = existing.values.putAll(patch))
                                } else {
                                    existing
                                }
                            }
                        )
                    },
                    stamps = (row.values["_entryId"] as? JsonPrimitive)?.content
                        ?.let { stampsByEntry[it] }
                        .orEmpty(),
                )
            }
        }
    }
}

@Composable
private fun CollectionRowCard(
    entity: EntityDto,
    row: CollectionRow,
    index: Int,
    total: Int,
    title: String,
    expanded: Boolean,
    media: DwMediaBridge,
    services: DwFieldServices,
    focusFieldKey: String? = null,
    errors: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    reorderable: Boolean = false,
    gripModifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onValueChange: (String, JsonElement?) -> Unit,
    onPatch: (Map<String, JsonElement?>) -> Unit,
    stamps: Map<String, DwFieldStampDto> = emptyMap(),
) {
    var showAdvanced by remember(row.rowId) {
        mutableStateOf(isAdvanced(entity, focusFieldKey))
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (dragging) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val tentativeWord =
                    dwTentativeField(entity)?.label?.takeIf { dwIsTentativeRow(row.values) }
                Column(modifier = Modifier.weight(1f).clickable(onClick = onToggle)) {
                    Text(
                        "${index + 1}. $title",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (tentativeWord != null) {
                        Text(
                            tentativeWord,
                            color = MaterialTheme.field.warning,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(COLLECTION_GRIP_TOUCH_TARGET)
                        .then(gripModifier),
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = null,
                        tint = if (reorderable) {
                            MaterialTheme.field.muted
                        } else {
                            MaterialTheme.field.placeholder
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = "Move $title up",
                        tint = MaterialTheme.field.muted,
                    )
                }
                IconButton(onClick = { onMove(1) }, enabled = index < total - 1) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "Move $title down",
                        tint = MaterialTheme.field.muted,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.field.muted
                    )
                }
            }

            errors[DW_ROW_REFUSAL_KEY]?.let { note ->
                Text(
                    note,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }

            if (expanded) {
                EntitySection(
                    entity = entity,
                    values = row.values,
                    media = media,
                    services = services,
                    focusFieldKey = focusFieldKey,
                    errors = errors - DW_ROW_REFUSAL_KEY,
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    onValueChange = onValueChange,
                    onPatch = onPatch,
                    resetKey = row.rowId,
                    stamps = stamps,
                )
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Remove this entry", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private val COLLECTION_ROW_GAP = 8.dp

private val COLLECTION_GRIP_TOUCH_TARGET = 48.dp

internal fun <T> dwMovedTo(items: List<T>, from: Int, to: Int): List<T> {
    if (from < 0 || from >= items.size) return items
    val target = to.coerceIn(0, items.size - 1)
    if (target == from) return items
    val next = items.toMutableList()
    next.add(target, next.removeAt(from))
    return next
}

internal fun dwRowMovedSentence(label: String, landed: Int, total: Int): String =
    "$label moved to position ${landed + 1} of $total."

internal fun dwRowDragAbandonedSentence(label: String): String =
    "$label was not moved — the list changed. Try again."

@Immutable
internal data class DwCollectionDrag(
    val rowId: String,
    val from: Int,
    val to: Int,
    val offset: Float,
    val snapshot: List<String>,
    val heights: List<Float>,
)

internal fun DwCollectionDrag.dwAdvancedBy(delta: Float, gap: Float): DwCollectionDrag {
    val moved = offset + delta
    val tops = ArrayList<Float>(heights.size)
    var running = 0f
    for (height in heights) {
        tops.add(running)
        running += height + gap
    }
    val ownHeight = heights.getOrNull(from) ?: return copy(offset = moved)
    val ownTop = tops.getOrNull(from) ?: return copy(offset = moved)
    val centre = ownTop + ownHeight / 2f + moved
    var next = from
    heights.indices.forEach { index ->
        if (index == from) return@forEach
        val otherCentre = tops[index] + heights[index] / 2f
        if (index < from && centre < otherCentre) next = minOf(next, index)
        if (index > from && centre > otherCentre) next = maxOf(next, index)
    }
    return copy(offset = moved, to = next)
}

internal fun DwCollectionDrag?.dwShiftFor(index: Int, gap: Float): Float {
    val drag = this ?: return 0f
    val ownHeight = drag.heights.getOrNull(drag.from) ?: return 0f
    val step = ownHeight + gap
    return when {
        index == drag.from -> drag.offset
        index > drag.from && index <= drag.to -> -step
        index < drag.from && index >= drag.to -> step
        else -> 0f
    }
}

private fun dwCollectionRowTitle(
    entity: EntityDto,
    titleField: FieldDto?,
    row: CollectionRow,
    index: Int,
): String = titleField
    ?.let { DwValues.text(row.values[it.key]) }
    ?.takeIf { it.isNotBlank() }
    ?: "${entity.title} ${index + 1}"

private fun Map<String, JsonElement>.put(key: String, value: JsonElement?): Map<String, JsonElement> =
    if (value == null) this - key else this + (key to value)

private fun Map<String, JsonElement>.putAll(patch: Map<String, JsonElement?>): Map<String, JsonElement> {
    if (patch.isEmpty()) return this
    val next = LinkedHashMap(this)
    patch.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
    return next
}

internal enum class DwStageRead {
    DRAFT_AS_IS,

    FOLD_SERVER_COPY,

    SEED_FROM_SERVER,

    SEED_BLANK,
}

internal fun dwStageReadPlan(
    local: StageDraft?,
    remoteExists: Boolean,
    canReach: Boolean,
): DwStageRead {
    val holds = local != null && (
        local.values.isNotEmpty() ||
            local.rows.isNotEmpty() ||
            local.custom.isNotEmpty() ||
            local.emptiedEntities.isNotEmpty() ||
            local.deletedRowKeys.isNotEmpty()
        )
    return when {
        holds && (local!!.stageSeen || !canReach) -> DwStageRead.DRAFT_AS_IS
        holds -> DwStageRead.FOLD_SERVER_COPY
        remoteExists -> DwStageRead.SEED_FROM_SERVER
        else -> DwStageRead.SEED_BLANK
    }
}

private data class StageLoad(
    val state: StageState,
    val seen: Boolean,
    val downloadFailed: Boolean,
    val heldWorkAlready: Boolean = false,
    val foldNotice: String? = null,
)

private fun isAdvanced(entity: EntityDto, focusFieldKey: String?): Boolean {
    val key = focusFieldKey ?: return false
    return entity.liveFields.any { it.key == key && DwTier.of(it.tier) == DwTier.ADVANCED }
}

private fun focusOpensAdvanced(stage: StageDto, focus: DwStageFocus?): Boolean {
    val target = focus?.takeIf { it.rowKey == null } ?: return false
    val entity = stage.singleton?.takeIf { it.key == target.entityKey } ?: return false
    return isAdvanced(entity, target.fieldKey)
}

private fun fromDraft(stage: StageDto, draft: StageDraft): StageState = StageState(
    singleton = draft.values,
    custom = draft.custom,
    provenance = draft.provenance,
    customSeen = draft.customSeen,
    copiedFrom = draft.copiedFrom,
    collections = stage.collections.associate { entity ->
        entity.key to draft.rowsFor(entity.key).map { row ->
            CollectionRow(
                rowId = row.id.substringAfter(DW_ROW_KEY_SEPARATOR),
                values = row.values,
            )
        }
    }
)

private fun fromRemote(stage: StageDto, bucket: StageBucketDto): StageState = StageState(
    singleton = bucket.singleton.toMap(),
    custom = bucket.custom.toMap(),
    customSeen = true,
    collections = stage.collections.associate { entity ->
        entity.key to bucket.collections[entity.key].orEmpty().map { row ->
            CollectionRow(
                rowId = (row["_clientKey"] as? JsonPrimitive)?.content
                    ?: (row["_entryId"] as? JsonPrimitive)?.content
                    ?: UUID.randomUUID().toString(),
                values = row.toMap(),
            )
        }
    }
)

internal fun dwStageRowsToStore(
    stage: StageDto,
    drawn: List<DraftRow>,
    existing: StageDraft?,
): List<DraftRow> = drawn + existing?.rows.orEmpty().filterNot { row ->
    stage.collections.any { it.key == row.entityKey() }
}

internal fun dwDeletionRecordOnDisk(saved: StageDraft?): Pair<Set<String>, Set<String>>? =
    saved?.let { it.emptiedEntities.toSet() to it.deletedRowKeys.toSet() }

internal suspend fun dwAdoptDeletionRecordAfterPush(
    sentEmptied: Set<String>,
    sentDeletedRows: Set<String>,
    screenEmptied: () -> Set<String>,
    screenDeletedRows: () -> Set<String>,
    loadStage: suspend () -> StageDraft?,
    adopt: (Set<String>, Set<String>) -> Unit,
) {
    withContext(NonCancellable) {
        dwDeletionRecordOnDisk(loadStage())?.let { (entities, rows) ->
            adopt(
                entities + (screenEmptied() - sentEmptied),
                rows + (screenDeletedRows() - sentDeletedRows),
            )
        }
    }
}

private fun dwLiveStageDraft(stage: StageDto, state: StageState, existing: StageDraft?): StageDraft =
    (existing ?: StageDraft(stageId = stage.key, title = stage.title, order = stage.number)).copy(
        values = state.singleton,
        custom = state.custom,
        rows = dwStageRowsToStore(
            stage,
            drawn = stage.collections.flatMap { entity ->
                state.collections[entity.key].orEmpty().map { row ->
                    DraftRow(id = dwRowId(entity.key, row.rowId), values = row.values)
                }
            },
            existing = existing,
        ),
    )

private suspend fun persistLocally(
    context: Context,
    workshopId: String,
    stage: StageDto,
    state: StageState,
    seen: Boolean,
    emptied: Set<String>,
    deletedRows: Set<String>,
) {
    WorkshopDraftStore.update(context, workshopId) { draft ->
        val existing = draft.stages[stage.key]
        val merged = StageDraft(
            stageId = stage.key,
            title = stage.title,
            order = stage.number,
            values = state.singleton,
            custom = state.custom,
            customSeen = state.customSeen || existing?.customSeen == true,
            copiedFrom = state.copiedFrom,
            rows = dwStageRowsToStore(
                stage,
                drawn = stage.collections.flatMap { entity ->
                    state.collections[entity.key].orEmpty().map { row ->
                        DraftRow(id = dwRowId(entity.key, row.rowId), values = row.values)
                    }
                },
                existing = existing,
            ),
            mediaIds = existing?.mediaIds.orEmpty(),
            requiredKeys = stage.singleton?.liveFields.orEmpty()
                .filter { it.required }
                .map { it.key },
            completedAt = existing?.completedAt,
            notes = existing?.notes.orEmpty(),
            stageSeen = seen || existing?.stageSeen == true,
            emptiedEntities = (emptied + existing?.emptiedEntities.orEmpty())
                .filter { key -> state.collections[key].orEmpty().isEmpty() }
                .distinct(),
            deletedRowKeys = (deletedRows + existing?.deletedRowKeys.orEmpty())
                .filterNot { key ->
                    stage.collections.any { entity ->
                        state.collections[entity.key].orEmpty()
                            .any { row -> dwRowId(entity.key, row.rowId) == key }
                    }
                }
                .distinct(),
        )
        draft.copy(stages = draft.stages + (stage.key to merged))
    }
}

private fun computeStageCompletenessFor(
    stage: StageDto,
    state: StageState,
    definition: DwCustomCache? = null,
) =
    computeStageCompleteness(
        stage = stage,
        singleton = state.countedSingleton(),
        collections = state.collections.mapValues { (_, rows) -> rows.map { it.values } },
        customFields = customFieldsForStage(definition, stage.key),
        customValues = state.custom,
    )
