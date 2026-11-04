package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.openable
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.RecordDangerZone
import com.designprototype.workshop.ui.RecordDangerZoneCard
import com.designprototype.workshop.ui.AppToggle
import com.designprototype.workshop.data.userMessage

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_CONSENT_NOT_YOURS_TO_RECORD
import com.designprototype.workshop.data.DW_CONSENT_QUESTION
import com.designprototype.workshop.data.DW_CONSENT_SWITCH_LABEL
import com.designprototype.workshop.data.DW_CONSENT_ROW_TITLE
import com.designprototype.workshop.data.DW_MAX_HITS
import com.designprototype.workshop.data.DW_MIN_QUERY_CHARS
import com.designprototype.workshop.data.DW_PROVENANCE_TITLE
import com.designprototype.workshop.data.DW_REPORT_HISTORY_TITLE
import com.designprototype.workshop.data.DraftConsent
import com.designprototype.workshop.data.DwConsentMerge
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwReportFreeze
import com.designprototype.workshop.data.DwSearchStatus
import com.designprototype.workshop.data.DwTier3Consent
import com.designprototype.workshop.data.DesignWorkshopDetailDto
import com.designprototype.workshop.data.DwStageCompleteness
import com.designprototype.workshop.data.DwStageFocus
import com.designprototype.workshop.data.DwSubmissionReadiness
import com.designprototype.workshop.data.DwWorkshopSearch
import com.designprototype.workshop.data.DwWorkshopSearchHit
import com.designprototype.workshop.data.DwWorkshopSearchIndex
import com.designprototype.workshop.data.StageCompletenessDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.DwCustomSectionStore
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.dwCustomDefinition
import com.designprototype.workshop.data.isHeld
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.computeWorkshopCompleteness
import com.designprototype.workshop.data.dwConsentMerge
import com.designprototype.workshop.data.dwConsentRecordedNote
import com.designprototype.workshop.data.dwConsentStateSentence
import com.designprototype.workshop.data.dwFrozenSentence
import com.designprototype.workshop.data.dwStageProgress
import com.designprototype.workshop.data.dwStageProgressLine
import com.designprototype.workshop.data.dwTier3ConsentOf
import com.designprototype.workshop.data.dwTier3ConsentToken
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import java.time.Instant
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun StageIndexScreen(
    repository: WorkshopRepository,
    workshopId: String,
    focusConsent: Boolean = false,
    onOpenStage: (stageKey: String, focus: DwStageFocus?) -> Unit,
    onOpenReport: () -> Unit,
    onOpenReportHistory: (() -> Unit)? = null,
    onOpenCodes: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenViewers: () -> Unit,
    onOpenInspectors: () -> Unit,
    onOpenProvenance: () -> Unit,
    onError: (String) -> Unit,
    onOpenAiLayers: (() -> Unit)? = null,
    canDelete: Boolean = false,
    onDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewer = remember(repository) { repository.cachedUser() }

    var stages by remember(workshopId) { mutableStateOf<List<DwStageCompleteness>>(emptyList()) }
    var title by remember(workshopId) { mutableStateOf("") }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var expanded by remember(workshopId) { mutableStateOf<String?>(null) }
    var serverNote by remember(workshopId) { mutableStateOf<String?>(null) }
    var onServer by remember(workshopId) { mutableStateOf(false) }
    var serverWorkshopId by remember(workshopId) { mutableStateOf<String?>(null) }
    var addresses by remember(workshopId) {
        mutableStateOf<Map<String, Map<String, DwStageFocus>>>(emptyMap())
    }
    var searchIndex by remember(workshopId) { mutableStateOf(DwWorkshopSearch.emptyIndex()) }
    var consent by remember(workshopId) { mutableStateOf(DraftConsent()) }
    var recordingConsent by remember(workshopId) { mutableStateOf(false) }
    var consentNote by remember(workshopId) { mutableStateOf<String?>(null) }
    var frozenNote by remember(workshopId) { mutableStateOf(DwReportFreeze.sentenceFor(workshopId)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            val schema = repository.designWorkshopSchema(appContext)
            val draft = WorkshopDraftStore.load(appContext, workshopId)
            var definition = DwCustomSectionStore.load(appContext, workshopId)
            var local = computeWorkshopCompleteness(schema, draft, definition)
            addresses = DwSubmissionReadiness.addressBook(
                DwSubmissionReadiness.assess(schema, draft, workshopId, definition)
            )
            searchIndex = DwWorkshopSearch.buildIndex(schema, draft)

            val remoteId = draft?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            onServer = remoteId != null
            serverWorkshopId = remoteId
            val remote = remoteId?.let {
                runCatching { repository.designWorkshop(it) }.getOrNull()
            }
            if (remote != null) {
                frozenNote = dwFrozenSentence(remote.status, remote.handedOnAt)
                DwReportFreeze.rememberRead(workshopId, remote)
                com.designprototype.workshop.data.DwTranscriptStore.keep(
                    appContext, workshopId, schema, remote.stages, remote.transcripts,
                    repository.cachedUser()?.id,
                )
            }
            if (remoteId != null) {
                val seeded = definition?.customSchemaVersion
                definition = runCatching {
                    repository.dwCustomDefinition(appContext, workshopId, remoteId)
                }.getOrNull() ?: definition
                if (definition?.customSchemaVersion != seeded) {
                    local = computeWorkshopCompleteness(schema, draft, definition)
                    addresses = DwSubmissionReadiness.addressBook(
                        DwSubmissionReadiness.assess(schema, draft, workshopId, definition)
                    )
                }
            }
            serverNote = when {
                remoteId == null -> "Not uploaded yet — figures from this phone."
                remote == null -> "Offline — figures from this phone."
                else -> null
            }

            consent = dwResolveDictationConsent(
                context = appContext,
                repository = repository,
                workshopId = workshopId,
                onDevice = draft?.consent ?: DraftConsent(),
                remote = remote,
                remoteId = remoteId,
            )
            DwDictationRun.rememberConsentAnswer(workshopId, dwTier3ConsentOf(consent.decision))

            val heldDigest = definition?.takeIf { it.isHeld }?.customSchemaVersion.orEmpty()
            val serverScoreUsable = remote != null && remote.customSchemaVersion == heldDigest
            if (remote != null && !serverScoreUsable && serverNote == null) {
                serverNote = "Figures from this phone. Reopen online to refresh this workshop's questions."
            }
            val merged = local.map { stage ->
                val touchedLocally = draft?.stages?.get(stage.stageKey)?.let {
                    it.values.isNotEmpty() || it.rows.isNotEmpty() || it.custom.isNotEmpty()
                } ?: false
                if (touchedLocally || !serverScoreUsable) stage
                else remote?.completeness?.get(stage.stageKey)?.let { fromServer(it, stage) } ?: stage
            }
            (remote?.title ?: draft?.title.orEmpty()) to merged
        }.onSuccess { (name, merged) ->
            title = name
            stages = merged
        }.onFailure { onError(it.userMessage("Couldn't load progress.")) }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading progress…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        val stageProgress = remember(stages) { dwStageProgress(stages) }

        Text(
            title.ifBlank { "Design workshop" },
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        FieldLinearProgressIndicator(progress = { stageProgress.percent / 100f }, modifier = Modifier.fillMaxWidth())
        Text(
            dwStageProgressLine(stageProgress),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        serverNote?.let { Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall) }
        frozenNote?.let { Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodyMedium) }

        serverWorkshopId?.let { id -> DwWorkshopRecordings(id) }

        val mayAdminister = mayAdministerViewers(viewer)
        ActionRow {
            Button(onClick = onOpenReport) {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Generate report")
            }
            onOpenReportHistory?.let { openHistory ->
                OutlinedButton(onClick = openHistory) {
                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(DW_REPORT_HISTORY_TITLE)
                }
            }
            OutlinedButton(onClick = onOpenCodes) {
                Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cards & tags")
            }
            OutlinedButton(onClick = onOpenPhotos) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import photos")
            }
            if (mayAdminister && onServer) {
                OutlinedButton(onClick = onOpenViewers) {
                    Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Who can open")
                }
                OutlinedButton(onClick = onOpenInspectors) {
                    Icon(Icons.Filled.FindInPage, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Inspectors")
                }
            }
            if (onServer) {
                onOpenAiLayers?.let { openAiLayers ->
                    OutlinedButton(onClick = openAiLayers) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(DW_AI_LAYERS_TITLE)
                    }
                }
            }
            if (mayReadWorkshopProvenance(viewer) && onServer) {
                OutlinedButton(onClick = onOpenProvenance) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(DW_PROVENANCE_TITLE)
                }
            }
        }
        when {
            mayAdminister && onServer -> Unit
            mayAdminister -> Text(
                "Upload this workshop from the list so others can open it.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            else -> Text(
                "Only you, its designers and admins can open this. Ask an admin to add a co-designer.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider()

        DwDictationConsentRow(
            consent = consent,
            focus = focusConsent,
            mayRecord = viewer != null && FieldPermissions.canRunDesignWorkshops(viewer),
            busy = recordingConsent,
            note = consentNote,
            onRecord = { decision ->
                recordingConsent = true
                consentNote = null
                scope.launch {
                    val written = dwRecordDictationConsent(
                        context = appContext,
                        repository = repository,
                        workshopId = workshopId,
                        decision = decision,
                        actor = viewer,
                    )
                    consent = written.consent
                    consentNote = dwConsentRecordedNote(
                        consent = dwTier3ConsentOf(written.consent.decision),
                        synced = written.consent.synced,
                        storedOnDevice = written.storedOnDevice,
                        serverRefusal = written.serverRefusal,
                    )
                    if (written.storedOnDevice || written.consent.synced) {
                        DwDictationRun.rememberConsentAnswer(workshopId, decision)
                    }
                    recordingConsent = false
                }
            },
        )

        HorizontalDivider()

        WorkshopSearchPanel(
            index = searchIndex,
            onOpenHit = { hit -> onOpenStage(hit.stageKey, DwWorkshopSearch.focusOf(hit)) },
        )

        HorizontalDivider()

        stages.forEach { stage ->
            StageIndexRow(
                stage = stage,
                expanded = expanded == stage.stageKey,
                addresses = addresses[stage.stageKey].orEmpty(),
                onToggle = { expanded = if (expanded == stage.stageKey) null else stage.stageKey },
                onOpen = { onOpenStage(stage.stageKey, null) },
                onOpenField = { focus -> onOpenStage(stage.stageKey, focus) }
            )
        }
        if (canDelete && onServer) {
            RecordDangerZoneCard(
                noun = "design workshop",
                delete = { repository.deleteDesignWorkshop(workshopId) },
                onDeleted = onDeleted,
                onError = { onError(it.userMessage(RecordDangerZone.FAILED)) },
                body = DW_DELETE_BODY,
                confirmBody = DW_DELETE_CONFIRM_BODY,
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

internal const val DW_DELETE_BODY = "Moves this design workshop to Deleted workshops. An admin can restore it."
internal const val DW_DELETE_CONFIRM_BODY = "An admin can restore it from Deleted workshops."

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DwDictationConsentRow(
    consent: DraftConsent,
    focus: Boolean,
    mayRecord: Boolean,
    busy: Boolean,
    note: String?,
    onRecord: (DwTier3Consent) -> Unit,
) {
    val state = dwTier3ConsentOf(consent.decision)
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.bringIntoViewRequester(requester),
    ) {
        Text(
            DW_CONSENT_ROW_TITLE,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state != DwTier3Consent.NOT_RECORDED || !mayRecord) {
            Text(
                dwConsentStateSentence(state, consent),
                color = if (state != DwTier3Consent.REFUSED) {
                    MaterialTheme.field.body
                } else {
                    MaterialTheme.field.warning
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (mayRecord) {
            AppToggle(
                label = DW_CONSENT_SWITCH_LABEL,
                description = DW_CONSENT_QUESTION,
                checked = state != DwTier3Consent.REFUSED,
                busy = busy,
                onCheckedChange = { on -> onRecord(if (on) DwTier3Consent.GRANTED else DwTier3Consent.REFUSED) },
            )
        } else {
            Text(DW_CONSENT_NOT_YOURS_TO_RECORD, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        note?.let {
            Text(
                it,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private suspend fun dwRecordDictationConsent(
    context: Context,
    repository: WorkshopRepository,
    workshopId: String,
    decision: DwTier3Consent,
    actor: UserDto?,
): DwConsentWriteResult {
    val recorded = DraftConsent(
        decision = dwTier3ConsentToken(decision),
        recordedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
        recordedById = actor?.id,
        recordedByName = actor?.name,
        synced = false,
    )
    val draft = runCatching {
        WorkshopDraftStore.update(context, workshopId) { it.copy(consent = recorded) }
    }.getOrNull()
    val remoteId = draft?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
    if (remoteId == null) return DwConsentWriteResult(recorded, storedOnDevice = draft != null)
    val failure = runCatching {
        repository.designWorkshopRecordDictationConsent(
            workshopId = remoteId,
            decision = decision,
            recordedAt = recorded.recordedAt,
        )
    }.exceptionOrNull()
    if (failure != null) {
        return DwConsentWriteResult(
            recorded,
            storedOnDevice = draft != null,
            serverRefusal = dwConsentPushRefusal(failure),
        )
    }
    val acknowledged = recorded.copy(synced = true)
    runCatching {
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(consent = acknowledged) }
    }
    return DwConsentWriteResult(acknowledged, storedOnDevice = draft != null)
}

private data class DwConsentWriteResult(
    val consent: DraftConsent,
    val storedOnDevice: Boolean,
    val serverRefusal: String? = null,
)

private fun dwConsentPushRefusal(failure: Throwable): String? {
    val http = failure as? HttpException ?: return null
    val code = http.code()
    if (code !in 400..499 || code == 401 || code == 429) return null
    return http.apiErrorMessage(
        "Couldn't save that answer. Check with an admin before dictating here."
    )
}

private suspend fun dwResolveDictationConsent(
    context: Context,
    repository: WorkshopRepository,
    workshopId: String,
    onDevice: DraftConsent,
    remote: DesignWorkshopDetailDto?,
    remoteId: String?,
): DraftConsent = when (
    dwConsentMerge(
        onDevice = onDevice,
        serverDecision = remote?.dictationConsent,
        serverRecordedAt = remote?.dictationConsentAt,
        serverKnown = remote != null,
    )
) {
    DwConsentMerge.KEEP_DEVICE -> onDevice
    DwConsentMerge.MARK_SYNCED -> dwMarkSynced(context, workshopId, onDevice)
    DwConsentMerge.PUSH_DEVICE ->
        dwPushUnsent(context, repository, workshopId, onDevice, remoteId)
    DwConsentMerge.TAKE_SERVER ->
        remote?.let { dwLearnFromServer(context, workshopId, it) } ?: onDevice
}

private suspend fun dwLearnFromServer(
    context: Context,
    workshopId: String,
    remote: DesignWorkshopDetailDto,
): DraftConsent {
    val learned = DraftConsent(
        decision = dwTier3ConsentToken(dwTier3ConsentOf(remote.dictationConsent)),
        recordedAt = remote.dictationConsentAt,
        recordedById = remote.dictationConsentById,
        recordedByName = remote.dictationConsentByName,
        synced = true,
    )
    runCatching {
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(consent = learned) }
    }
    return learned
}

private suspend fun dwPushUnsent(
    context: Context,
    repository: WorkshopRepository,
    workshopId: String,
    onDevice: DraftConsent,
    remoteId: String?,
): DraftConsent {
    if (remoteId == null) return onDevice
    val pushed = runCatching {
        repository.designWorkshopRecordDictationConsent(
            workshopId = remoteId,
            decision = dwTier3ConsentOf(onDevice.decision),
            recordedAt = onDevice.recordedAt,
        )
    }.isSuccess
    return if (pushed) dwMarkSynced(context, workshopId, onDevice) else onDevice
}

private suspend fun dwMarkSynced(
    context: Context,
    workshopId: String,
    onDevice: DraftConsent,
): DraftConsent {
    val acknowledged = onDevice.copy(synced = true)
    runCatching {
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(consent = acknowledged) }
    }
    return acknowledged
}

@Composable
private fun WorkshopSearchPanel(
    index: DwWorkshopSearchIndex,
    onOpenHit: (DwWorkshopSearchHit) -> Unit,
) {
    var query by remember(index) { mutableStateOf("") }
    val result = remember(index, query) { DwWorkshopSearch.search(index, query) }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search this workshop") },
        placeholder = {
            Text(
                "A word, name or code — any script",
                color = MaterialTheme.field.placeholder,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.field.muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        when {
            result.status == DwSearchStatus.IDLE ->
                "Searches ${index.fieldCount} answers across ${index.stageCount} stage${if (index.stageCount == 1) "" else "s"}. Works offline."

            result.status == DwSearchStatus.TOO_SHORT -> "Type at least $DW_MIN_QUERY_CHARS characters."

            result.status == DwSearchStatus.NO_TERMS ->
                "Nothing to search for."

            result.total == 0 ->
                "Nothing in this workshop holds " +
                    (if (result.terms.size == 1) "that word" else "all of those words") +
                    "."

            result.truncated ->
                "Top $DW_MAX_HITS of ${result.total}. Add a word to narrow it."

            else -> "${result.total} matching answer${if (result.total == 1) "" else "s"}."
        },
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )

    if (result.hits.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
            result.hits.forEach { hit -> SearchHitRow(hit = hit, onOpen = { onOpenHit(hit) }) }
        }
    }
}

@Composable
private fun SearchHitRow(hit: DwWorkshopSearchHit, onOpen: () -> Unit) {
    val markStyle = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.SemiBold,
    )
    val quoted = remember(hit, markStyle) { markedSnippet(hit, markStyle) }

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Open ${hit.fieldLabel} in stage ${hit.stageNumber}",
                onClick = onOpen,
            )
            .padding(vertical = 6.dp)
    ) {
        Text(
            buildString {
                append("${hit.stageNumber}. ${hit.stageTitle} · ${hit.entityTitle}")
                hit.recordTitle?.let { append(" · $it") }
                append(" · ${hit.fieldLabel}")
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(quoted, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun markedSnippet(hit: DwWorkshopSearchHit, mark: SpanStyle): AnnotatedString = buildAnnotatedString {
    if (hit.snippet.clippedStart) append("…")
    for (segment in hit.snippet.segments) {
        if (segment.match) withStyle(mark) { append(segment.text) } else append(segment.text)
    }
    if (hit.snippet.clippedEnd) append("…")
}

@Composable
private fun StageIndexRow(
    stage: DwStageCompleteness,
    expanded: Boolean,
    addresses: Map<String, DwStageFocus>,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenField: (DwStageFocus?) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .openable(stage.title, RoundedCornerShape(8.dp), onOpen = onOpen)
            ) {
                Text(
                    stage.number.toString().padStart(2, '0'),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stage.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    FieldLinearProgressIndicator(
                        progress = { stage.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        buildString {
                            append("${stage.percent}%")
                            if (stage.requiredTotal > 0) {
                                append(" · ${stage.requiredFilled}/${stage.requiredTotal} required")
                            } else {
                                append(" · nothing required here")
                            }
                            val rows = stage.collectionCounts.values.sum()
                            if (rows > 0) append(" · $rows entr${if (rows == 1) "y" else "ies"}")
                        },
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open stage ${stage.number}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (stage.missing.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                ) {
                    Text(
                        "${stage.missing.size} required field${if (stage.missing.size == 1) "" else "s"} still missing",
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Hide what is missing" else "Show what is missing",
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        stage.missing.forEach { label ->
                            val focus = addresses[label]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        onClickLabel = if (focus != null) {
                                            "Open in stage ${stage.number}"
                                        } else {
                                            "Open stage ${stage.number}"
                                        },
                                        onClick = { onOpenField(focus) }
                                    )
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    "· $label",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fromServer(dto: StageCompletenessDto, local: DwStageCompleteness): DwStageCompleteness =
    local.copy(
        requiredTotal = dto.requiredTotal,
        requiredFilled = dto.requiredFilled,
        optionalTotal = dto.optionalTotal,
        optionalFilled = dto.optionalFilled,
        collectionCounts = dto.collectionCounts,
        missing = dto.missing,
    )

@Composable
private fun DwWorkshopRecordings(designWorkshopId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val v2 = remember { com.designprototype.workshop.questionnaire.data.QuestionnaireV2.get(context) }
    val fixed = remember(designWorkshopId) {
        com.designprototype.workshop.questionnaire.data.QuestionnaireFilters().with("designWorkshopId", listOf(designWorkshopId))
    }
    com.designprototype.workshop.questionnaire.ui.RecordingsList(
        v2, fixed = fixed, screen = "dw-recordings-$designWorkshopId",
        onOpenSession = { sessionId ->
            com.designprototype.workshop.questionnaire.ui.QuestionnaireV2Nav.openServerSession.value = sessionId
        }
    )
}
