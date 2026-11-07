package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.data.userMessage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import com.designprototype.workshop.ui.RecordOpen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import com.designprototype.workshop.ui.FieldProgressDefaults
import com.designprototype.workshop.ui.CompletenessRing
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.designprototype.workshop.questionnaire.ui.rememberKept
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DW_LOCAL_ID_PREFIX
import com.designprototype.workshop.data.DW_WORKSHOP_CREATE_REFUSAL
import com.designprototype.workshop.data.DesignWorkshopDefaultDto
import com.designprototype.workshop.data.DesignWorkshopCreateBody
import com.designprototype.workshop.data.DwEligibleViewerDto
import com.designprototype.workshop.data.DwEligibleViewers
import com.designprototype.workshop.data.DW_MAX_NAMED_DESIGNERS
import com.designprototype.workshop.data.DW_DRAFT_OTHER_ACCOUNT_ROW
import com.designprototype.workshop.data.DW_LOCAL_DRAFT_LINK_PROMPT
import com.designprototype.workshop.data.DW_LOCAL_DRAFT_UNLINKED
import com.designprototype.workshop.data.DW_LOCAL_START_ACTION
import com.designprototype.workshop.data.DW_LOCAL_START_NOTE
import com.designprototype.workshop.data.DwDraftStart
import com.designprototype.workshop.data.dwClassifyDraftStart
import com.designprototype.workshop.data.mayMintLocalWorkshop
import com.designprototype.workshop.data.ReportTemplateDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.WorkshopSyncStatus
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.DwCustomSectionStore
import com.designprototype.workshop.data.computeWorkshopCompleteness
import com.designprototype.workshop.data.dwAdoptCandidateNotice
import com.designprototype.workshop.data.dwAdoptNoCandidatesMessage
import com.designprototype.workshop.data.dwDraftIsForAnotherAccount
import com.designprototype.workshop.data.dwNamedDesignerId
import com.designprototype.workshop.data.dwNamedDesignerTeam
import com.designprototype.workshop.data.dwOrderedDesignerPicks
import com.designprototype.workshop.data.dwPersonLabel
import com.designprototype.workshop.data.dwViewerAdministrationMissing
import com.designprototype.workshop.data.dwViewerOfferNotice
import com.designprototype.workshop.data.dwViewerSearchTerm
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.overallPercent
import com.designprototype.workshop.data.visibleDesignWorkshops
import com.designprototype.workshop.data.workshopCreateTitle
import com.designprototype.workshop.data.workshopDisplayTitle
import com.designprototype.workshop.ui.ANY_WORKSHOP_KIND
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.designWorkshopStatusLabel
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.workshopKindOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.Instant
import java.util.UUID
import com.designprototype.workshop.ui.ListReachText
import com.designprototype.workshop.ui.ListReachButton

private const val SEARCH_DEBOUNCE_MS = 350L

internal fun dwOfferDraftMove(mayCreate: Boolean, mayRunWorkshops: Boolean): Boolean =
    !mayCreate && mayRunWorkshops

internal fun dwLocalRowPassesKind(workshopKind: String?, kindFilter: String): Boolean =
    kindFilter.isBlank() || workshopKind.isNullOrBlank() || workshopKind == kindFilter

private const val NAME_OFFER_PAGE_SIZE = 20

@Immutable
private data class WorkshopRow(
    val localId: String,
    val remoteId: String?,
    val title: String,
    val subtitle: String,
    val percent: Int,
    val requiredFilled: Int,
    val requiredTotal: Int,
    val updatedAt: String,
    val hasLocalDraft: Boolean,
    val status: WorkshopSyncStatus? = null,
    val fromServer: Boolean = false,
    val otherAccount: Boolean = false,
) {
    val localOnly: Boolean get() = remoteId == null
}

private fun blockedLine(blocked: Int): String = if (blocked <= 0) "" else {
    " ${if (blocked == 1) "1 workshop needs" else "$blocked workshops need"} your attention before uploading — open below."
}

@Composable
fun WorkshopListScreen(
    repository: WorkshopRepository,
    startCreating: Boolean = false,
    initialSearch: String? = null,
    onOpen: (workshopId: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var schema by rememberKept("dw.list.schema") { null as SchemaResponse? }
    var rows by rememberKept("dw.list.rows") { emptyList<WorkshopRow>() }
    var loading by remember { mutableStateOf(true) }
    var search by rememberKept("dw.list.search") { "" }
    LaunchedEffect(initialSearch) { initialSearch?.takeIf { it.isNotBlank() }?.let { search = it } }
    var kindFilter by rememberKept("dw.list.kind") { "" }
    val cachedSession = repository.cachedUser()
    val mayCreate = remember(cachedSession?.id, cachedSession?.role) {
        mayMintLocalWorkshop(known = cachedSession != null, role = cachedSession?.role)
    }
    val mayRunWorkshops = remember(cachedSession?.id, cachedSession?.role) {
        cachedSession != null && FieldPermissions.canRunDesignWorkshops(cachedSession)
    }
    val offerMove = dwOfferDraftMove(mayCreate = mayCreate, mayRunWorkshops = mayRunWorkshops)
    var localTitle by remember { mutableStateOf("") }
    var showCreate by remember(startCreating) { mutableStateOf(startCreating && mayCreate) }
    var adopting by remember { mutableStateOf<WorkshopRow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var offline by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var unfilterableLocal by remember { mutableIntStateOf(0) }
    val sendingId by WorkshopSyncEngine.busyWorkshop.collectAsState()
    val syncRevision by WorkshopSyncEngine.revision.collectAsState()

    LaunchedEffect(reload, search, kindFilter) {
        if (search.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        loading = true
        runCatching {
            val registry = repository.designWorkshopSchema(appContext)
            schema = registry

            val localIds = WorkshopDraftStore.list(appContext).map { it.workshopId }
            val drafts = localIds.mapNotNull { id -> WorkshopDraftStore.load(appContext, id)?.let { id to it } }
            val draftById = drafts.toMap()
            val remoteToLocal = drafts.mapNotNull { (id, draft) -> draft.remoteId?.let { it to id } }.toMap()
            val signedInUserId = repository.cachedUser()?.id

            val remote = runCatching {
                repository.visibleDesignWorkshops(
                    search = search.takeIf { it.isNotBlank() },
                    workshopKind = kindFilter.takeIf { it.isNotBlank() },
                )
            }
            val cancelled = remote.exceptionOrNull() is CancellationException
            if (!cancelled) {
                offline = remote.isFailure
                partial = remote.getOrNull()?.takeIf { it.truncated }?.let { it.items.size to it.total }
            }

            val fromServer = remote.getOrNull()?.items.orEmpty().map { dto ->
                val localId = remoteToLocal[dto.id] ?: dto.id
                val draft = draftById[localId]
                rowFor(
                    context = appContext,
                    localId = localId,
                    remoteId = dto.id,
                    title = workshopDisplayTitle(
                        dto.title,
                        date = dto.startDate,
                        workshopCode = dto.workshopCode,
                        clusterName = dto.clusterName,
                        district = dto.district,
                        state = dto.state,
                        craftName = dto.craftName,
                    ),
                    subtitle = listOfNotNull(
                        dto.craftName, dto.clusterName, dto.district, dto.state,
                        dto.status.takeIf { it.isNotBlank() }?.let { designWorkshopStatusLabel(it, dto.handedOnAt) }
                    ).joinToString(" · "),
                    updatedAt = dto.updatedAt.orEmpty(),
                    schema = registry,
                    draft = draft,
                ).copy(fromServer = true)
            }

            val covered = fromServer.map { it.localId }.toSet()
            val localOnly = drafts
                .filterNot { (id, _) -> id in covered }
                .filter { (_, draft) -> dwLocalRowPassesKind(draft.workshopKind, kindFilter) }
                .map { (id, draft) ->
                    rowFor(
                        context = appContext,
                        localId = id,
                        remoteId = draft.remoteId,
                        title = workshopCreateTitle(draft.title, draft.createdAt),
                        subtitle = if (isLocalOnlyWorkshop(id)) "On this device only" else "Not synced",
                        updatedAt = draft.updatedAt,
                        schema = registry,
                        draft = draft,
                    ).copy(
                        otherAccount = dwDraftIsForAnotherAccount(draft.ownerUserId, signedInUserId),
                    )
                }
                .filter { row ->
                    search.isBlank() || row.title.contains(search, ignoreCase = true)
                }

            unfilterableLocal = if (kindFilter.isBlank()) {
                0
            } else {
                localOnly.count { row -> draftById[row.localId]?.workshopKind.isNullOrBlank() }
            }

            (fromServer + localOnly).sortedByDescending { it.updatedAt }
        }.onSuccess { rows = it }
            .onFailure { onError(it.userMessage("Unable to list the design workshops.")) }
        loading = false
    }

    LaunchedEffect(syncRevision) {
        val registry = schema ?: return@LaunchedEffect
        if (syncRevision == 0 || rows.isEmpty()) return@LaunchedEffect
        delay(400)
        rows = rows.map { row ->
            val draft = WorkshopDraftStore.load(appContext, row.localId)
            row.copy(status = draft?.let { WorkshopSyncEngine.statusOf(appContext, registry, it) })
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Design & prototype workshops",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Saved on this phone first; syncs when you're online.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (mayCreate) {
                Button(onClick = { showCreate = true }, enabled = !busy) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
            }
        }

        val kindChoices = workshopKindOptions(schema)
        if (kindChoices.isNotEmpty()) {
            SearchableSelectField(
                label = "Type of workshop",
                options = listOf(com.designprototype.workshop.ui.SelectOption("", ANY_WORKSHOP_KIND)) + kindChoices,
                selectedValue = kindFilter,
                includeNone = false,
                enabled = !busy,
                onSelect = { picked -> kindFilter = picked }
            )
        }

        if (!mayCreate) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Only an admin can start a workshop",
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    DW_WORKSHOP_CREATE_REFUSAL,
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp
                )
                DwMostRecentlyAllocated(repository = repository, onOpen = onOpen)

                if (offline && mayRunWorkshops) {
                    Text(
                        DW_LOCAL_START_NOTE,
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp
                    )
                    OutlinedTextField(
                        value = localTitle,
                        onValueChange = { localTitle = it },
                        label = { Text("Workshop title") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ActionRow {
                        OutlinedButton(
                            enabled = !busy && localTitle.isNotBlank(),
                            onClick = {
                                val cached = repository.cachedUser()
                                val start = dwClassifyDraftStart(
                                    mayCreate = mayMintLocalWorkshop(
                                        known = cached != null,
                                        role = cached?.role,
                                    ),
                                    mayRunWorkshops = cached != null &&
                                        FieldPermissions.canRunDesignWorkshops(cached),
                                    reachable = ConnectivityObserver.isOnline(appContext),
                                )
                                if (start != DwDraftStart.LINK_LATER) {
                                    onError(DW_WORKSHOP_CREATE_REFUSAL)
                                } else {
                                    busy = true
                                    val typed = localTitle.trim()
                                    localTitle = ""
                                    scope.launch {
                                        val id = DW_LOCAL_ID_PREFIX + UUID.randomUUID()
                                        val written = runCatching {
                                            WorkshopDraftStore.update(appContext, id) { draft ->
                                                draft.copy(
                                                    title = typed,
                                                    ownerUserId = cached?.id,
                                                )
                                            }
                                        }.isSuccess
                                        busy = false
                                        if (!written) {
                                            onError(
                                                "Couldn't save on this phone. Nothing was lost."
                                            )
                                        } else {
                                            reload++
                                            onOpen(id)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(DW_LOCAL_START_ACTION, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }

        if (offline) {
            Text(
                "Offline — showing what's on this phone.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall
            )
        }

        val unlinkedCount = if (offerMove) {
            rows.count { it.localOnly && it.hasLocalDraft && !it.otherAccount }
        } else {
            0
        }
        if (!offline && unlinkedCount > 0) {
            Text(
                (if (unlinkedCount == 1) {
                    "1 workshop isn't linked to a workshop online yet. "
                } else {
                    "$unlinkedCount workshops aren't linked to a workshop online yet. "
                }) + DW_LOCAL_DRAFT_LINK_PROMPT + " Use “Move into a workshop” on the row.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp
            )
        }

        partial?.let { (shown, total) ->
            ListReachButton(
                shown = shown,
                total = total,
                label = ListReachText.loadAll(total),
                loading = loading,
                tag = "workshops.loadall",
                onClick = { reload++ },
            )
        }

        if (unfilterableLocal > 0) {
            Text(
                "$unfilterableLocal workshop${if (unfilterableLocal == 1) " has" else "s have"} no type, so ${if (unfilterableLocal == 1) "it shows" else "they show"} under every filter.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        val outstanding = rows.filterNot { it.otherAccount }
            .mapNotNull { it.status }
            .filterNot { it.isFullySynced }
        val refusedAnswersNow = outstanding.sumOf { it.refusedAnswers }
        val refusedAnswersLine = if (refusedAnswersNow == 0) "" else {
            " $refusedAnswersNow answer${if (refusedAnswersNow == 1) " was" else "s were"} refused — open the workshop to fix ${if (refusedAnswersNow == 1) "it" else "them"}."
        }
        val deletionsNow = outstanding.sumOf { it.unsentDeletions }
        val deletionsLine = if (deletionsNow == 0) "" else {
            " $deletionsNow deletion${if (deletionsNow == 1) "" else "s"} will upload after you open ${if (deletionsNow == 1) "its stage" else "those stages"} online."
        }
        val droppedNow = outstanding.sumOf { it.droppedAnswers }
        val droppedLine = if (droppedNow == 0) "" else {
            " $droppedNow answer${if (droppedNow == 1) " is" else "s are"} no longer asked for. Open the workshop online to clear ${if (droppedNow == 1) "it" else "them"}."
        }
        DeviceSyncBanner(
            workshops = outstanding.size,
            stages = outstanding.sumOf { it.pendingStages },
            files = outstanding.sumOf { it.pendingMedia },
            bytes = outstanding.sumOf { it.pendingMediaBytes },
            refusals = outstanding.sumOf { it.failedStages + it.failedMedia },
            refusedAnswers = outstanding.sumOf { it.refusedAnswers },
            unsentDeletions = outstanding.sumOf { it.unsentDeletions },
            droppedAnswers = droppedNow,
            busy = busy || sendingId != null,
            onSyncNow = {
                busy = true
                scope.launch {
                    val result = runCatching { repository.syncDesignWorkshops(appContext) }.getOrNull()
                    when {
                        result == null -> onError("Couldn't start the sync.")
                        result.skipped -> onMessage(
                            "Offline. Everything is saved and will upload later."
                        )
                        result.didAnything -> onMessage(
                            buildString {
                                append("Sent ${plural(result.stagesSent, "stage")} and ${plural(result.mediaUploaded, "file")}.")
                                if (result.stoppedOffline) {
                                    append(" The connection dropped — the rest is still here.")
                                }
                                if (result.refused > 0) {
                                    append(" ${plural(result.refused, "item")} refused — open the workshop to see why.")
                                }
                                append(refusedAnswersLine)
                                append(deletionsLine)
                                append(droppedLine)
                                append(blockedLine(result.blockedByRefusal))
                                if (result.workshopsResumed > 0) {
                                    append(
                                        " ${plural(result.workshopsResumed, "workshop")} ${if (result.workshopsResumed == 1) "was already online and is" else "were already online and are"} now linked."
                                    )
                                }
                            }
                        )
                        result.refused > 0 -> onMessage(
                            buildString {
                                append("${plural(result.refused, "item")} couldn't upload. Open the workshop to see why.")
                                if (result.stoppedOffline) {
                                    append(" Connection lost; the rest is still here.")
                                }
                                append(blockedLine(result.blockedByRefusal))
                            }
                        )
                        result.blockedByRefusal > 0 -> onMessage(
                            buildString {
                                append(blockedLine(result.blockedByRefusal).trimStart())
                                if (result.stoppedOffline) {
                                    append(" Connection lost; the rest is still here.")
                                }
                                append(refusedAnswersLine)
                                append(deletionsLine)
                                append(droppedLine)
                            }
                        )
                        result.stoppedOffline -> onMessage(
                            "Connection lost before anything was sent. Nothing was lost."
                        )
                        refusedAnswersNow > 0 || deletionsNow > 0 || droppedNow > 0 -> onMessage(
                            "Nothing to send.$refusedAnswersLine$deletionsLine$droppedLine"
                        )
                        else -> onMessage("Everything is uploaded.")
                    }
                    busy = false
                    reload++
                }
            }
        )

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            rows.isEmpty() -> Text(
                if (mayCreate) {
                    "No workshops yet. Tap New to start one."
                } else {
                    "No workshops yet. They appear here once an admin gives you access."
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )

            else -> rows.forEach { row ->
                WorkshopCard(
                    row = row,
                    busy = busy,
                    sending = sendingId == row.localId,
                    onAdopt = if (offerMove && row.localOnly && row.hasLocalDraft && !row.otherAccount) {
                        { adopting = row }
                    } else {
                        null
                    },
                    onOpen = { onOpen(row.localId) },
                    onSend = {
                        busy = true
                        scope.launch {
                            val result = runCatching {
                                repository.retryDesignWorkshopSync(appContext, row.localId)
                            }.getOrNull()
                            when {
                                result == null -> onError("Couldn't send “${row.title}”.")
                                result.skipped -> onMessage(
                                    "Offline. “${row.title}” is saved and will upload later."
                                )
                                result.refused > 0 -> onError(
                                    "${plural(result.refused, "item")} of “${row.title}” couldn't upload. Open the row for details."
                                )
                                result.blockedByRefusal > 0 -> onError(
                                    "“${row.title}” needs your attention before it can upload. Open the row for details."
                                )
                                result.didAnything -> onMessage(
                                    "“${row.title}”: sent ${plural(result.stagesSent, "stage")} and " +
                                        "${plural(result.mediaUploaded, "file")}." +
                                        if (result.workshopsResumed > 0) {
                                            " It was already online and is now linked."
                                        } else {
                                            ""
                                        }
                                )
                                result.stoppedOffline -> onMessage(
                                    "Connection lost. It will resume where it stopped."
                                )
                                else -> onMessage("“${row.title}” is fully uploaded.")
                            }
                            busy = false
                            reload++
                        }
                    },
                    onFreeSpace = {
                        busy = true
                        scope.launch {
                            val (count, bytes) = runCatching {
                                WorkshopDraftStore.releaseUploadedMedia(appContext, row.localId)
                            }.getOrDefault(0 to 0L)
                            if (count == 0) {
                                onMessage("Nothing to remove.")
                            } else {
                                onMessage(
                                    "Freed ${syncBytes(bytes)} (${plural(count, "file")}). They stay online but leave reports made on this phone."
                                )
                            }
                            busy = false
                            reload++
                        }
                    }
                )
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    adopting?.let { row ->
        AdoptIntoWorkshopDialog(
            repository = repository,
            row = row,
            candidates = rows.filter { it.remoteId != null && (offline || it.fromServer) },
            offline = offline,
            searched = search.isNotBlank(),
            listTruncated = partial != null,
            onDismiss = { adopting = null },
            onAdopt = { target ->
                adopting = null
                busy = true
                scope.launch {
                    val moved = runCatching {
                        WorkshopDraftStore.adoptIntoWorkshop(
                            context = appContext,
                            workshopId = row.localId,
                            remoteId = target,
                        )
                    }.getOrNull()
                    if (moved == null) {
                        onError(
                            "Couldn't move that draft. Nothing was lost."
                        )
                    } else {
                        onMessage(
                            "“${row.title}” moved. It uploads on the next sync."
                        )
                    }
                    busy = false
                    reload++
                }
            },
        )
    }

    if (showCreate) {
        CreateWorkshopDialog(
            repository = repository,
            offline = offline,
            onDismiss = { showCreate = false },
            onCreated = { id, wasLocal ->
                showCreate = false
                reload++
                if (wasLocal) {
                    onMessage("Started on this phone. Upload it when you're online.")
                }
                onOpen(id)
            },
            onError = onError,
        )
    }
}

@Composable
private fun WorkshopCard(
    row: WorkshopRow,
    busy: Boolean,
    sending: Boolean,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onFreeSpace: () -> Unit,
    onAdopt: (() -> Unit)? = null,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let {
                    if (row.otherAccount) it
                    else it.clickable(onClickLabel = RecordOpen.openClickLabel(row.title), role = Role.Button, onClick = onOpen)
                }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CompletenessRing(percent = row.percent)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        row.title,
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (row.subtitle.isNotBlank()) {
                        Text(row.subtitle, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${row.requiredFilled} of ${row.requiredTotal} required fields",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (row.otherAccount) {
                Text(
                    DW_DRAFT_OTHER_ACCOUNT_ROW,
                    color = MaterialTheme.field.warning,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }
            val status = row.status.takeUnless { row.otherAccount }
            if (status != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    WorkshopSyncChip(status = status, sending = sending)
                    Spacer(Modifier.weight(1f))
                }
                if (!status.isFullySynced || status.releasableMedia > 0) {
                    WorkshopSyncActions(
                        status = status,
                        busy = busy || sending,
                        onRetry = onSend,
                        onFreeSpace = onFreeSpace,
                    )
                }
            }
            onAdopt?.let { adopt ->
                Text(
                    DW_LOCAL_DRAFT_UNLINKED + " " + DW_LOCAL_DRAFT_LINK_PROMPT,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
                ActionRow {
                    OutlinedButton(
                        onClick = adopt,
                        enabled = !busy && !sending,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Move into a workshop", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
private fun CreateWorkshopDialog(
    repository: WorkshopRepository,
    offline: Boolean,
    onDismiss: () -> Unit,
    onCreated: (workshopId: String, localOnly: Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var craft by remember { mutableStateOf("") }
    var cluster by remember { mutableStateOf("") }
    var templateId by remember { mutableStateOf("DCH_STANDARD") }
    var templates by remember { mutableStateOf<List<ReportTemplateDto>>(emptyList()) }
    var workshopKind by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf<List<com.designprototype.workshop.ui.SelectOption>>(emptyList()) }
    var namesOnRecord by remember { mutableStateOf<List<String>>(emptyList()) }
    var namesTotal by remember { mutableIntStateOf(0) }
    var pickingName by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var designerUserIds by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val me = repository.cachedUser()
        if (me?.role == "DESIGNER" && me.id.isNotBlank() && designerUserIds.isEmpty()) {
            designerUserIds = listOf(me.id)
        }
    }
    var leadDesignerId by remember { mutableStateOf("") }
    var designerQuery by remember { mutableStateOf("") }
    var designerOffer by remember { mutableStateOf<DwEligibleViewers?>(null) }
    var seenDesigners by remember { mutableStateOf<Map<String, DwEligibleViewerDto>>(emptyMap()) }
    var designerSearching by remember { mutableStateOf(false) }
    var designerStandDown by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        templates = runCatching { repository.designWorkshopTemplates(appContext) }.getOrDefault(emptyList())
        kinds = runCatching { workshopKindOptions(repository.designWorkshopSchema(appContext)) }
            .getOrDefault(emptyList())
    }

    LaunchedEffect(workshopKind) {
        val answer = try {
            repository.designWorkshops(
                page = 1,
                pageSize = NAME_OFFER_PAGE_SIZE,
                workshopKind = workshopKind.takeIf { it.isNotBlank() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (offline: Throwable) {
            null
        }
        namesOnRecord = answer?.items.orEmpty()
            .mapNotNull { it.title.trim().takeIf { name -> name.isNotEmpty() } }
            .distinct()
        namesTotal = answer?.total ?: 0
        if (namesOnRecord.isEmpty()) pickingName = false
    }

    LaunchedEffect(offline, designerQuery) {
        if (offline) {
            designerOffer = null
            designerSearching = false
            designerStandDown = dwDesignerPickerStandDown(
                offline = true,
                error = null,
                isConnectionFailure = { false },
            )
            return@LaunchedEffect
        }
        val term = dwViewerSearchTerm(designerQuery)
        val loaded = designerOffer
        if (loaded != null && term == loaded.search) return@LaunchedEffect
        if (term != null) delay(SEARCH_DEBOUNCE_MS)
        designerSearching = true
        val answered = runCatching { repository.eligibleDesignWorkshopViewers(term) }
        designerSearching = false
        answered
            .onSuccess { served ->
                designerOffer = served
                seenDesigners = seenDesigners + served.users.associateBy { it.id }
                designerStandDown = null
            }
            .onFailure { error ->
                if (error is CancellationException) return@onFailure
                designerOffer = null
                designerStandDown = dwDesignerPickerStandDown(
                    offline = false,
                    error = error,
                    isConnectionFailure = { repository.isConnectionFailure(it) },
                )
            }
    }

    val designerOptions = remember(designerOffer, seenDesigners, designerUserIds) {
        val offered = designerOffer?.users.orEmpty()
        val answered = offered.mapTo(HashSet()) { it.id }
        val retained = designerUserIds.filterNot { it in answered }.mapNotNull { seenDesigners[it] }
        (offered + retained).map { person ->
            com.designprototype.workshop.ui.SelectOption(
                value = person.id,
                label = dwPersonLabel(person.name, person.email),
                hint = listOfNotNull(
                    person.email.takeIf { it.isNotBlank() },
                    FieldPermissions.label(person.role).takeIf { it.isNotBlank() },
                ).joinToString(" · ").takeIf { it.isNotBlank() }
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New design workshop") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (pickingName && namesOnRecord.isNotEmpty()) {
                    SearchableSelectField(
                        label = "Workshop title *",
                        options = (listOfNotNull(title.trim().takeIf { it.isNotEmpty() }) + namesOnRecord)
                            .distinct()
                            .map { com.designprototype.workshop.ui.SelectOption(value = it, label = it) },
                        selectedValue = title,
                        includeNone = false,
                        searchable = false,
                        enabled = !busy,
                        createAction = com.designprototype.workshop.ui.SelectCreateAction(
                            label = "Type a new name"
                        ) { pickingName = false },
                        onSelect = { picked -> if (picked.isNotBlank()) title = picked }
                    )
                    Text(
                        buildString {
                            append(
                                if (workshopKind.isBlank()) {
                                    "From workshops you can open."
                                } else {
                                    "From workshops of this type."
                                }
                            )
                            if (namesTotal > namesOnRecord.size) {
                                append(" Showing ${namesOnRecord.size} of $namesTotal.")
                            }
                        },
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(requiredMarked("Workshop title *")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (namesOnRecord.isNotEmpty()) {
                        ActionRow {
                            TextButton(enabled = !busy, onClick = { pickingName = true }) {
                                Text("Reuse a name")
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = craft,
                    onValueChange = { craft = it },
                    label = { Text("Craft") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cluster,
                    onValueChange = { cluster = it },
                    label = { Text("Cluster") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SearchableSelectField(
                    label = "Report template",
                    options = templateOptions(templates),
                    selectedValue = templateId,
                    includeNone = false,
                    onSelect = { picked -> if (picked.isNotBlank()) templateId = picked }
                )
                if (kinds.isNotEmpty()) {
                    SearchableSelectField(
                        label = "Type of workshop",
                        options = kinds,
                        selectedValue = workshopKind,
                        includeNone = false,
                        placeholder = "Not stated",
                        enabled = !busy,
                        onSelect = { picked -> workshopKind = picked }
                    )
                }

                if (designerStandDown == null) {
                    OutlinedTextField(
                        value = designerQuery,
                        onValueChange = { designerQuery = it },
                        label = { Text("Search designers") },
                        singleLine = true,
                        enabled = !busy,
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.field.muted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            when {
                                designerSearching -> FieldCircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                designerQuery.isNotEmpty() -> IconButton(onClick = { designerQuery = "" }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.field.muted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val designerNotice = designerStandDown ?: designerOffer?.let { dwViewerOfferNotice(it) }
                designerNotice?.let {
                    Text(
                        it,
                        color = if (designerStandDown != null || designerOffer?.truncated == true) {
                            MaterialTheme.field.warning
                        } else {
                            MaterialTheme.field.muted
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (designerStandDown == null || designerUserIds.isNotEmpty()) {
                    SearchableMultiSelectField(
                        label = "Designers this workshop is for",
                        options = designerOptions,
                        selected = designerUserIds.toSet(),
                        placeholder = "Not decided yet",
                        emptyMessage = if (designerOffer?.search != null) {
                            "No matches."
                        } else {
                            "No eligible designers yet."
                        },
                        enabled = !busy && designerStandDown == null,
                        onSelectedChange = { picked ->
                            val next = dwOrderedDesignerPicks(
                                previous = designerUserIds,
                                picked = picked,
                                offered = designerOptions.map { it.value },
                            )
                            designerUserIds = next
                            leadDesignerId = dwNamedDesignerTeam(next, leadDesignerId).lead.orEmpty()
                        }
                    )
                    Text(
                        "Everyone ticked can open and fill in this workshop. The lead's profile fills stages 1 and 3 and the report.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (designerUserIds.size > DW_MAX_NAMED_DESIGNERS) {
                    Text(
                        "Up to $DW_MAX_NAMED_DESIGNERS designers. Remove ${designerUserIds.size - DW_MAX_NAMED_DESIGNERS} to continue.",
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                val namedTeam = dwNamedDesignerTeam(designerUserIds, leadDesignerId)
                namedTeam.lead?.let { lead ->
                    Text(
                        "Lead designer: " +
                            (seenDesigners[lead]?.let { dwPersonLabel(it.name, it.email) } ?: lead) +
                            ".",
                        color = MaterialTheme.field.body,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (namedTeam.team.size > 1) {
                        SearchableSelectField(
                            label = "Whose name the report carries",
                            options = namedTeam.team.map { id ->
                                val person = seenDesigners[id]
                                com.designprototype.workshop.ui.SelectOption(
                                    value = id,
                                    label = person?.let { dwPersonLabel(it.name, it.email) } ?: id,
                                    hint = person?.email?.takeIf { it.isNotBlank() },
                                )
                            },
                            selectedValue = lead,
                            includeNone = false,
                            enabled = !busy,
                            onSelect = { picked -> if (picked.isNotBlank()) leadDesignerId = picked }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank() &&
                    designerUserIds.size <= DW_MAX_NAMED_DESIGNERS,
                onClick = {
                    val cached = repository.cachedUser()
                    if (!mayMintLocalWorkshop(known = cached != null, role = cached?.role)) {
                        onError(DW_WORKSHOP_CREATE_REFUSAL)
                        onDismiss()
                    } else {
                    busy = true
                    scope.launch {
                        val designers = dwNamedDesignerTeam(designerUserIds, leadDesignerId)
                        val body = DesignWorkshopCreateBody(
                            title = title.trim(),
                            templateId = templateId,
                            workshopKind = workshopKind.takeIf { it.isNotBlank() },
                            designerUserId = designers.lead,
                            designerUserIds = designers.team.takeIf { it.isNotEmpty() },
                            craftName = craft.trim().takeIf { it.isNotEmpty() },
                            clusterName = cluster.trim().takeIf { it.isNotEmpty() },
                        )
                        val couldHaveReachedServer = ConnectivityObserver.isOnline(appContext)
                        val remote = runCatching { repository.createDesignWorkshop(body) }
                        val outcome = classifyCreate(remote.exceptionOrNull()) { repository.isTransient(it) }
                        if (outcome is CreateOutcome.Refused) {
                            onError(outcome.message)
                            busy = false
                            return@launch
                        }
                        val id = remote.getOrNull()?.id ?: (DW_LOCAL_ID_PREFIX + UUID.randomUUID())
                        runCatching {
                            WorkshopDraftStore.update(appContext, id) { draft ->
                                draft.copy(
                                    title = body.title,
                                    templateId = body.templateId,
                                    workshopKind = body.workshopKind,
                                    designerUserId = body.designerUserId,
                                    designerUserIds = body.designerUserIds.orEmpty(),
                                    remoteId = remote.getOrNull()?.id,
                                    ownerUserId = repository.cachedUser()?.id,
                                    sync = draft.sync.copy(
                                        createSentAt = if (remote.isFailure && couldHaveReachedServer) {
                                            Instant.now().toString()
                                        } else {
                                            null
                                        },
                                    ),
                                )
                            }
                        }.onFailure { error ->
                            onError(error.userMessage("Couldn't start the workshop."))
                            busy = false
                            return@launch
                        }
                        busy = false
                        onCreated(id, remote.isFailure)
                    }
                    }
                }
            ) { Text(if (busy) "Starting…" else "Start") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AdoptIntoWorkshopDialog(
    repository: WorkshopRepository,
    row: WorkshopRow,
    candidates: List<WorkshopRow>,
    offline: Boolean,
    searched: Boolean,
    listTruncated: Boolean,
    onDismiss: () -> Unit,
    onAdopt: (remoteId: String) -> Unit,
) {
    var chosen by remember(row.localId) { mutableStateOf("") }
    val options = remember(candidates) {
        candidates.mapNotNull { candidate ->
            candidate.remoteId?.takeIf { it.isNotBlank() }?.let { id ->
                com.designprototype.workshop.ui.SelectOption(
                    value = id,
                    label = candidate.title,
                    hint = candidate.subtitle.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${row.title}” into a workshop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Everything captured under “${row.title}” moves into the workshop you pick and uploads on the next sync.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )
                if (options.isEmpty()) {
                    Text(
                        if (offline) {
                            dwAdoptNoCandidatesMessage(offline = true)
                        } else {
                            dwAdoptCandidateNotice(
                                offline = false,
                                searched = searched,
                                listTruncated = listTruncated,
                            ) ?: dwAdoptNoCandidatesMessage(offline = false)
                        },
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    SearchableSelectField(
                        label = "Workshop to move it into",
                        options = options,
                        selectedValue = chosen,
                        includeNone = false,
                        onSelect = { picked -> chosen = picked }
                    )
                    dwAdoptCandidateNotice(
                        offline = offline,
                        searched = searched,
                        listTruncated = listTruncated,
                    )?.let {
                        Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Check the name — this can't be undone here.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotBlank() && !offline,
                onClick = { onAdopt(chosen) }
            ) { Text("Move it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

internal sealed interface CreateOutcome {
    data object Local : CreateOutcome

    data class Refused(val message: String) : CreateOutcome
}

internal fun classifyCreate(error: Throwable?, isTransient: (Throwable) -> Boolean): CreateOutcome =
    when {
        error == null -> CreateOutcome.Local
        isTransient(error) -> CreateOutcome.Local
        else -> CreateOutcome.Refused(
            error.apiErrorMessage("Couldn't start this workshop.")
        )
    }

internal fun dwDesignerPickerStandDown(
    offline: Boolean,
    error: Throwable?,
    isConnectionFailure: (Throwable) -> Boolean,
): String? {
    val cannotReach =
        "Offline. Start the workshop now and add designers later; stage 1 uses your profile until then."
    if (offline) return cannotReach
    if (error == null) return null
    if (dwViewerAdministrationMissing((error as? HttpException)?.code())) {
        return "Couldn't load designers. You can still start; stage 1 uses your profile."
    }
    if (isConnectionFailure(error)) return cannotReach
    return "Couldn't load designers. You can still start and add them later."
}

internal fun templateOptions(templates: List<ReportTemplateDto>): List<com.designprototype.workshop.ui.SelectOption> =
    templates.map { template ->
        com.designprototype.workshop.ui.SelectOption(
            value = template.id,
            label = template.name.ifBlank { template.id },
            hint = template.description.takeIf { it.isNotBlank() }
        )
    }

private suspend fun rowFor(
    context: android.content.Context,
    localId: String,
    remoteId: String?,
    title: String,
    subtitle: String,
    updatedAt: String,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
): WorkshopRow {
    val stages = computeWorkshopCompleteness(
        schema, draft, DwCustomSectionStore.load(context, localId),
    )
    return WorkshopRow(
        localId = localId,
        remoteId = remoteId,
        title = workshopDisplayTitle(title),
        subtitle = subtitle,
        percent = overallPercent(stages),
        requiredFilled = stages.sumOf { it.requiredFilled },
        requiredTotal = stages.sumOf { it.requiredTotal },
        updatedAt = updatedAt.ifBlank { Instant.EPOCH.toString() },
        hasLocalDraft = draft != null,
        status = draft?.let { WorkshopSyncEngine.statusOf(context, schema, it) },
    )
}

@Composable
private fun DwMostRecentlyAllocated(
    repository: WorkshopRepository,
    onOpen: (workshopId: String) -> Unit,
) {
    var answer by remember { mutableStateOf<DesignWorkshopDefaultDto?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repository.designWorkshopDefaultForMe() }
            .onSuccess { answer = it }
            .onFailure { error ->
                if (error is CancellationException) throw error
            }
    }

    val row = answer ?: return
    val id = row.workshopId?.takeIf { it.isNotBlank() } ?: return
    val title = row.title?.takeIf { it.isNotBlank() } ?: return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            when (row.reason) {
                "GRANTED" -> "Recently allocated: $title"
                "CREATED" -> "Recently opened: $title"
                else -> "Ready to open: $title"
            },
            color = MaterialTheme.field.onWarningContainer,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp
        )
        ActionRow {
            OutlinedButton(onClick = { onOpen(id) }) {
                Text("Open this workshop", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
