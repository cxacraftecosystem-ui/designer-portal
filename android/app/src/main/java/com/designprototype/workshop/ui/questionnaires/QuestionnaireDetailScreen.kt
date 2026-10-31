package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.ui.AppToggle
import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.QFormOp
import com.designprototype.workshop.data.newTempId
import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.CustomQuestionnaireDto
import com.designprototype.workshop.data.CustomSectionDto
import com.designprototype.workshop.data.DwQuestionnaireArtefact
import com.designprototype.workshop.data.DwQuestionnaireStore
import com.designprototype.workshop.data.QFormChangeReportDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.cachedQuestionnaireNotice
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.ui.RecordOpen
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.designWorkshopLabel
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.RecordProseField
import kotlinx.coroutines.CancellationException
import com.designprototype.workshop.data.TempIdMap
import com.designprototype.workshop.data.isTempId
import com.designprototype.workshop.data.resolveTempId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One questionnaire: its sittings, its questions, and — for the designer who owns it — its editor.
 *
 * LOADED WITH `includeRetired = true`, which is the opposite of what the answer screen asks for and
 * is the reason the two are separate screens rather than two modes of one. A retired question has to
 * stay visible HERE, because the answers recorded against it are still in the record and this is
 * where they are read; it must never appear THERE, because it can no longer be answered.
 *
 * WHAT THIS SCREEN REFUSES TO OFFER. There is no "delete question" button and no "delete
 * questionnaire" button, and both absences are the feature rather than an omission:
 *
 *  * A question that has been answered cannot be deleted. Asking the server to remove it RETIRES it
 *    instead — it stops being asked and keeps its answers. So the control says "Retire" the moment
 *    `hasAnswers` is true, and the confirmation says what will happen in the server's own words. A
 *    button labelled Delete that produces a retire teaches a designer that the app ignores them.
 *  * A questionnaire is never deleted at all. `PATCH {isActive: false}` takes it out of every list
 *    and every dropdown and keeps every answer, which is what the control below does and says.
 *
 * Rewording an answered question is the third case and the subtle one: the server does not refuse it
 * and does not overwrite it — it keeps the old wording with its answers and adds the new wording as a
 * new question. The editor warns before the designer types, and repeats the server's own sentence
 * afterwards, because a designer whose six corrections came back as six NEW questions needs to be
 * told that happened and that their answers are safe.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuestionnaireDetailScreen(
    repository: WorkshopRepository,
    questionnaireId: String,
    onOpenEntry: (entryId: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    /**
     * M12 (21 Oct 2026): a questionnaire created offline is opened under its temporary id. Once its
     * queued create lands, the router reopens it under the server's id so every server call on this
     * screen names a real questionnaire.
     */
    onResolvedId: (serverId: String) -> Unit = {},
    /** L9: called after a content admin deletes this questionnaire. */
    onDeleted: () -> Unit = {},
    /**
     * Questionnaire v2 (UNIFY §4.2): when set, the form answers through SESSIONS. "Start session" opens
     * the session start sheet on this form and the legacy sittings card is not drawn. Null keeps the
     * sittings flow exactly as it was (questionnaire v2 switched off on the server).
     */
    onStartSession: ((questionnaireId: String) -> Unit)? = null,
    /** v2: the sessions list filtered to this form. Null hides the button. */
    onOpenSessions: ((questionnaireId: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // The application context, so the cache write below cannot outlive an Activity it holds.
    val appContext = LocalContext.current.applicationContext

    var form by remember(questionnaireId) { mutableStateOf<CustomQuestionnaireDto?>(null) }
    var loading by remember(questionnaireId) { mutableStateOf(true) }
    var loadError by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember(questionnaireId) { mutableIntStateOf(0) }
    var workshops by remember { mutableStateOf(AttachableWorkshops()) }
    /** A9: the server's bindings for this form; null until read, or when it has none to say. */
    var bindings by remember(questionnaireId) { mutableStateOf<com.designprototype.workshop.data.FormBindingsDto?>(null) }
    var choosingKind by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val handoffAnchor = remember { BringIntoViewRequester() }
    val bindingsAnchor = remember { BringIntoViewRequester() }

    var renaming by remember { mutableStateOf(false) }
    var addingSection by remember { mutableStateOf(false) }
    var renamingSection by remember { mutableStateOf<CustomSectionDto?>(null) }
    var addingQuestionTo by remember { mutableStateOf<CustomSectionDto?>(null) }
    var editingQuestion by remember { mutableStateOf<CustomQuestionDto?>(null) }
    var removingQuestion by remember { mutableStateOf<CustomQuestionDto?>(null) }
    /** L9: the sitting a content admin asked to delete, awaiting the confirmation. */
    var deletingEntry by remember { mutableStateOf<CustomEntryDto?>(null) }
    var startingSitting by remember { mutableStateOf(false) }
    /**
     * M13 (21 Oct 2026): the last sitting start that did not come back, as (what was sent, its key).
     * Pressing Start again with the same details reuses the key, so a start whose answer was lost
     * is answered with that sitting rather than making a second one.
     */
    var unansweredStart by remember(questionnaireId) { mutableStateOf<Pair<String, String>?>(null) }

    // ── The .xlsx interchange ────────────────────────────────────────────────────────────────────
    // Absent by decision until 2026-08-16; the reversal and its reasoning are in
    // `data/WorkshopRepositoryApi.kt`, beside the endpoints.
    var interchangeBusy by remember { mutableStateOf(false) }
    /** Which artefact is being fetched right now — so only ITS button shows a spinner. */
    var downloading by remember { mutableStateOf<DwQuestionnaireArtefact?>(null) }
    var reuploading by remember { mutableStateOf(false) }
    var savedQuestionSetTo by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var savedWorkbookTo by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var uploadReport by remember(questionnaireId) { mutableStateOf<QFormChangeReportDto?>(null) }

    // ── Reuse ────────────────────────────────────────────────────────────────────────────────────
    // A SERVER-SIDE COPY AND NOT A FILE, which is why it keeps its own busy flag rather than sharing
    // `interchangeBusy`. That flag exists because the three controls above it all end in
    // `persistFileToDownloads` and two of those racing truncates a file; nothing here touches disk,
    // so borrowing it would disable a spreadsheet download during a copy for no reason at all.
    var reusing by remember { mutableStateOf(false) }
    var reuseBusy by remember { mutableStateOf(false) }
    var reuseReport by remember(questionnaireId) { mutableStateOf<QFormChangeReportDto?>(null) }

    /**
     * Set when the questionnaire on screen came off this device's disk.
     *
     * Edits to the INSTRUMENT made while this is set (add, rename, edit, retire) are QUEUED on the
     * phone since 2026-10-16 (`authorQuestionnaireOrQueue`) and replayed in order; the server still
     * decides between "updated" and "superseded" when each one lands, because only it knows whether
     * the question has answers by then. Actions that name the questionnaire itself on the server
     * (attach, kind, deactivate, a sitting, the spreadsheet) still need a connection and say so.
     */
    var cachedNotice by remember(questionnaireId) { mutableStateOf<String?>(null) }

    fun download(artefact: DwQuestionnaireArtefact) {
        if (interchangeBusy) return
        interchangeBusy = true
        downloading = artefact
        scope.launch {
            runCatching {
                repository.downloadQuestionnaireArtefact(
                    context = appContext,
                    artefact = artefact,
                    questionnaireId = questionnaireId,
                    fallbackStem = form?.title.orEmpty(),
                )
            }
                .onSuccess { location ->
                    if (artefact == DwQuestionnaireArtefact.QUESTION_SET) {
                        savedQuestionSetTo = location
                    } else {
                        savedWorkbookTo = location
                    }
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        // The server's own sentence. The 403 on `/xlsx` is the one place a
                        // non-owner is told that the QUESTION SET exists and is theirs to take;
                        // "download failed" would send them to find an admin for a file they never
                        // needed. `apiErrorMessage` already prefers `detail` over the status line,
                        // and the repository lifts it off a streamed Response for the same reason.
                        onError(error.apiErrorMessage("That workbook could not be downloaded."))
                    }
                }
            downloading = null
            interchangeBusy = false
        }
    }

    val pickWorkbook = rememberWorkbookPicker { uri ->
        if (interchangeBusy) return@rememberWorkbookPicker
        interchangeBusy = true
        reuploading = true
        uploadReport = null
        scope.launch {
            runCatching {
                repository.reuploadQuestionnaireWorkbook(
                    context = appContext,
                    questionnaireId = questionnaireId,
                    uri = uri,
                )
            }
                .onSuccess { result ->
                    uploadReport = result.report
                    // Re-read rather than trusting the questionnaire in the reply. The edit path
                    // returns the form WITH retired questions, which is what this screen wants — but
                    // the reload also refreshes the sittings and re-warms the offline cache, and a
                    // screen that skipped it would show a question list that no longer matches the
                    // answer counts printed above it.
                    reload++
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        // A 409 here means the file's Details sheet names a DIFFERENT questionnaire —
                        // the designer picked the wrong .xlsx out of Downloads. That refusal just
                        // saved this questionnaire's entire question set from being retired as
                        // "absent from the upload", so its sentence is shown as written.
                        onError(error.apiErrorMessage("That workbook could not be uploaded."))
                    }
                }
            reuploading = false
            interchangeBusy = false
        }
    }

    LaunchedEffect(questionnaireId, reload) {
        loading = true
        loadError = null
        cachedNotice = null
        // CACHE-BACKED, so this screen opens in a courtyard. It is the screen a designer reads a
        // questionnaire's sittings on, and the one that warms `DwQuestionnaireStore` for the report —
        // being unable to open it with no signal meant being unable to review what a colleague
        // recorded that morning, which writes nothing at all. See
        // `WorkshopRepository.customQuestionnaireCached`; a 403/404 still reaches `onFailure`, so a
        // questionnaire this account may no longer read is never served out of the device's memory
        // of when they could.
        runCatching {
            // THE FORM TO AUTHOR (2026-10-16): the server's copy, this phone's saved copy, or — for a
            // questionnaire started offline — the queued row, with every step still waiting on this
            // phone drawn on top. See `data/QuestionnaireAuthoring.kt`.
            repository.customQuestionnaireForAuthoring(appContext, questionnaireId)
        }
            .map { read ->
                if (read.fromCache) {
                    cachedNotice = cachedQuestionnaireNotice(read.cachedAt, read.form.version)
                }
                read.form
            }
            .onSuccess {
                form = it
                // THE ANSWERS ARE IN HAND HERE, SO THE DEVICE WRITES THEM DOWN. This screen is where
                // a designer reads a questionnaire's sittings, which means the one thing the report
                // annexure needs has just crossed the network and been paid for. Kept, the export
                // three days later in a courtyard with no signal carries the fieldwork instead of a
                // note apologising for it; not kept, that export is the defect this cache exists to
                // end and there is no second chance to fetch it.
                //
                // THIS READ AND NOT THE ANSWER SCREEN'S. `includeRetired = true` is what makes the
                // copy match the office's: `report_items` applies no retirement filter, so a cache
                // fed from the answer screen's `includeRetired = false` read would silently drop
                // every answer given under a wording that has since been reworded. See
                // [dwQuestionnaireItemOf], which states the requirement it cannot check.
                //
                // Best-effort and silent: a full disk must not turn reading a questionnaire into an
                // error message about a cache the designer never asked for.
                runCatching { DwQuestionnaireStore.mergeQuestionnaire(appContext, it, repository.cachedUser()?.id) }
            }
            .onFailure { error ->
                if (error !is CancellationException) {
                    loadError = error.apiErrorMessage("This questionnaire could not be opened.")
                }
            }
        loading = false
    }

    LaunchedEffect(questionnaireId) { workshops = attachableDesignWorkshops(repository) }
    LaunchedEffect(questionnaireId, reload) {
        if (!isTempId(questionnaireId)) bindings = repository.formBindings(questionnaireId)
    }

    // M12: watch for the queued create to land, then hand the server id to the router.
    LaunchedEffect(questionnaireId) {
        if (!isTempId(questionnaireId)) return@LaunchedEffect
        while (true) {
            val real = runCatching { TempIdMap.all(appContext) }.getOrNull()?.let { resolveTempId(questionnaireId, it) }
            if (real != null && !isTempId(real)) {
                onResolvedId(real)
                break
            }
            delay(TEMP_ID_POLL_MS)
        }
    }

    /**
     * M12: the server calls that name the questionnaire itself (attach, kind, deactivate, a new
     * sitting, the spreadsheet and the handoff) wait until it has uploaded. Authoring steps do not:
     * they are queued under the temporary id and replayed in order.
     */
    fun uploadedOrSay(): Boolean {
        if (!isTempId(questionnaireId)) return true
        onError(QUESTIONNAIRE_STILL_UPLOADING)
        return false
    }

    /** Run a write, then re-read the form. Every mutation on this screen goes through here. */
    fun mutate(failure: String, block: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess { note -> note?.let(onMessage); reload++ }
                .onFailure { error ->
                    if (error !is CancellationException) onError(error.apiErrorMessage(failure))
                }
            busy = false
        }
    }

    val loaded = form
    val mayEdit = remember(loaded?.ownerId) { mayEditQuestionnaire(repository, loaded?.ownerId) }
    // Removing a question and deleting the form are the author's or a content admin's (owner,
    // 22 Oct 2026); deleting or restoring a sitting stays a content admin's alone.
    val mayDelete = remember(loaded?.ownerId) { mayDeleteQuestionnaire(repository.cachedUser(), loaded?.ownerId) }
    val mayDeleteSittings = remember { mayDeleteQuestionnaireItems(repository.cachedUser()) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            loading && loaded == null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            loadError != null && loaded == null ->
                Text(loadError.orEmpty(), color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodyMedium)

            loaded == null -> EmptyNote("This questionnaire could not be opened.")

            else -> {
                cachedNotice?.let { notice ->
                    Text(notice, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                }
                val deleted = loaded.deletedAt != null
                val menu = formMenuItems(mayEdit = mayEdit, mayDelete = mayDelete, deleted = deleted, onPhoneOnly = isTempId(questionnaireId))
                if (menu.isNotEmpty()) Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    FormOverflowMenu(menu) { item ->
                        when (item) {
                            FormMenuItem.WORKBOOK_EXPORT -> if (uploadedOrSay()) download(DwQuestionnaireArtefact.FULL_WORKBOOK)
                            FormMenuItem.QUESTION_SET -> if (uploadedOrSay()) download(DwQuestionnaireArtefact.QUESTION_SET)
                            FormMenuItem.PRO_FORMA -> download(DwQuestionnaireArtefact.PRO_FORMA)
                            FormMenuItem.REUPLOAD -> if (uploadedOrSay()) {
                                if (repository.isOnline(appContext)) pickWorkbook() else onError(WORKBOOK_UPLOAD_OFFLINE)
                            }
                            FormMenuItem.REUSE -> if (uploadedOrSay()) reusing = true
                            FormMenuItem.SEND_TO_PHONE -> scope.launch { runCatching { handoffAnchor.bringIntoView() } }
                            FormMenuItem.BINDINGS -> scope.launch { runCatching { bindingsAnchor.bringIntoView() } }
                            FormMenuItem.KIND -> choosingKind = true
                            FormMenuItem.DELETE -> confirmingDelete = true
                            FormMenuItem.RESTORE -> mutate("The questionnaire could not be restored.") {
                                repository.restoreCustomQuestionnaire(questionnaireId)
                                "Restored."
                            }
                        }
                    }
                }
                if (deleted) {
                    DeletedFormBanner(mayRestore = mayDelete, busy = busy) {
                        mutate("The questionnaire could not be restored.") {
                            repository.restoreCustomQuestionnaire(questionnaireId)
                            "Restored."
                        }
                    }
                }
                HeaderCard(
                    form = loaded,
                    mayEdit = mayEdit,
                    busy = busy,
                    workshops = workshops,
                    onRename = { renaming = true },
                    onAttach = { workshopId ->
                        if (!uploadedOrSay()) return@HeaderCard
                        mutate("The questionnaire could not be attached.") {
                            repository.updateCustomQuestionnaire(
                                id = questionnaireId,
                                // `changeWorkshop` is what turns a blank id into DETACH rather than
                                // into "leave it alone" — see customQuestionnaireUpdateJson.
                                designWorkshopId = workshopId.takeIf { it.isNotBlank() },
                                changeWorkshop = true,
                            )
                            if (workshopId.isBlank()) {
                                "Detached from its design workshop."
                            } else {
                                "Attached to the design workshop."
                            }
                        }
                    },
                    onSetKind = { kind ->
                        if (!uploadedOrSay()) return@HeaderCard
                        mutate("The kind could not be changed.") {
                            repository.updateCustomQuestionnaire(
                                id = questionnaireId,
                                // `changeKind` is what turns a blank into "clear it" rather than
                                // into "leave it alone" — the same pairing `changeWorkshop` above
                                // needs, and for the same reason. See customQuestionnaireUpdateJson.
                                kind = kind.takeIf { it.isNotBlank() },
                                changeKind = true,
                            )
                            if (kind.isBlank()) {
                                "Kind cleared. Answers stay unfiled in the report."
                            } else {
                                "Filed as ${labelForQuestionnaireKind(kind)}."
                            }
                        }
                    },
                    onSetActive = { active ->
                        if (!uploadedOrSay()) return@HeaderCard
                        mutate("The questionnaire could not be updated.") {
                            repository.updateCustomQuestionnaire(id = questionnaireId, isActive = active)
                            if (active) {
                                "Back in the lists."
                            } else {
                                "Deactivated. Answers are kept."
                            }
                        }
                    },
                )

                InterchangeCard(
                    mayEdit = mayEdit,
                    busy = interchangeBusy,
                    downloading = downloading,
                    reuploading = reuploading,
                    savedQuestionSetTo = savedQuestionSetTo,
                    savedWorkbookTo = savedWorkbookTo,
                    onDownload = { if (uploadedOrSay()) download(it) },
                    // Asked at the tap, before the file picker opens: see QuestionnaireOfflineCopy.kt.
                    onPickWorkbook = {
                        if (!uploadedOrSay()) return@InterchangeCard
                        if (repository.isOnline(appContext)) pickWorkbook() else onError(WORKBOOK_UPLOAD_OFFLINE)
                    },
                )

                // THE OFFLINE HANDOFF, directly beneath the two .xlsx controls and sharing their one
                // busy flag on purpose: all three end in `persistFileToDownloads`, writing into the
                // same folder, and two of those racing is how one file ends up truncated with no
                // error anywhere. The neighbouring card is the SERVER interchange — it needs a
                // connection at both ends; this one is built on the handset and needs none, which is
                // the whole distinction a designer standing in a courtyard is choosing between.
                Box(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bindingsAnchor)) {
                    FormBindingsCard(
                        bindingLines(bindings?.bindings, loaded.designWorkshopId) { id ->
                            workshops.rows.firstOrNull { it.id == id }?.let { designWorkshopLabel(it) }
                        }
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(handoffAnchor)) {
                    QuestionnaireHandoffCard(
                        repository = repository,
                        questionnaireId = questionnaireId,
                        busy = interchangeBusy,
                        onBusyChange = { interchangeBusy = it },
                        onError = onError,
                    )
                }

                /*
                 * USE THIS QUESTIONNAIRE AGAIN — the client half of `POST /questionnaires/{id}/reuse`,
                 * which shipped with no caller on any handset.
                 *
                 * UNGATED, on both counts, and both are argued at length in QuestionnaireReuseUi.kt:
                 * `mayEdit` is not consulted, because reuse edits nothing and the route is not
                 * owner-gated; and a DEACTIVATED source still offers it, because `isActive: false` is
                 * this API's stand-in for a delete and a retired instrument is exactly the thing a
                 * designer lifts for a new round.
                 */
                ReuseCard(busy = reuseBusy, onOpen = { if (uploadedOrSay()) reusing = true })

                uploadReport?.let { report ->
                    UploadReportPanel(report = report, onDismiss = { uploadReport = null })
                }

                reuseReport?.let { report ->
                    // The SAME panel, deliberately — the reuse response is the upload response's
                    // shape key for key so that this component renders both — with the one line that
                    // would otherwise claim a file was read replaced. `qFormProvenanceNotice` handles
                    // the other half: `action: "reused"` has a branch of its own there, so the block
                    // under this heading says the questions were copied rather than that answers were
                    // imported.
                    UploadReportPanel(
                        report = report,
                        onDismiss = { reuseReport = null },
                        heading = "What the copy did",
                    )
                }

                if (onStartSession != null) {
                    FormSessionsCard(
                        canStart = !busy && formSessionBlock(loaded.isActive, deleted, activeQuestionCount(loaded), isTempId(questionnaireId)) == null,
                        reason = formSessionBlock(loaded.isActive, deleted, activeQuestionCount(loaded), isTempId(questionnaireId)),
                        onStart = { onStartSession(questionnaireId) },
                        onOpenSessions = onOpenSessions?.let { open -> { open(questionnaireId) } },
                    )
                    // The sessions on this form, with the shared search and filters and each one's
                    // chips (owner, 31 Oct 2026). Opening one opens it in the questionnaire screen.
                    if (!isTempId(questionnaireId)) FormSurfaceCard {
                        com.designprototype.workshop.questionnaire.ui.FormSessionsList(
                            com.designprototype.workshop.questionnaire.data.QuestionnaireV2.get(appContext), questionnaireId,
                            onOpenSession = { session ->
                                com.designprototype.workshop.questionnaire.ui.QuestionnaireV2Nav.openServerSession.value = session.id
                                onOpenSessions?.invoke(questionnaireId)
                            }
                        )
                    }
                } else SittingsCard(
                    form = loaded,
                    busy = busy,
                    onDeleteEntry = if (mayDeleteSittings && !isTempId(questionnaireId)) ({ deletingEntry = it }) else null,
                    onStart = {
                        if (!uploadedOrSay()) return@SittingsCard
                        if (repository.isOnline(appContext)) startingSitting = true else onError(SITTING_START_OFFLINE)
                    },
                    onOpenEntry = onOpenEntry,
                )

                FormCard(
                    form = loaded,
                    mayEdit = mayEdit,
                    mayDelete = mayDelete,
                    busy = busy,
                    onAddSection = { addingSection = true },
                    onRenameSection = { renamingSection = it },
                    onAddQuestion = { addingQuestionTo = it },
                    onEditQuestion = { editingQuestion = it },
                    onRemoveQuestion = { removingQuestion = it },
                )

                // L9 (21 Oct 2026): a sitting's trash, content admins only.
                if (onStartSession == null && mayDeleteSittings && !isTempId(questionnaireId)) {
                    DeletedSittingsCard(
                        repository = repository,
                        questionnaireId = questionnaireId,
                        reloadKey = reload,
                        onRestored = { reload++ },
                        onMessage = onMessage,
                        onError = onError,
                    )
                }
                // The form's own soft delete: its author or a content admin (owner, 22 Oct 2026).
                if (mayDelete && !deleted && !isTempId(questionnaireId)) {
                    com.designprototype.workshop.ui.RecordDangerZoneCard(
                        noun = "questionnaire",
                        delete = { repository.deleteCustomQuestionnaire(questionnaireId) },
                        onDeleted = {
                            onMessage("Questionnaire deleted. Its author or an admin can restore it.")
                            onDeleted()
                        },
                        onError = { onError(it.apiErrorMessage("The questionnaire could not be deleted.")) },
                        body = QuestionnaireTrashCopy.QUESTIONNAIRE_BODY,
                        confirmBody = QuestionnaireTrashCopy.QUESTIONNAIRE_CONFIRM,
                    )
                }
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    if (choosingKind && loaded != null) {
        FormKindDialog(current = loaded.kind, onDismiss = { choosingKind = false }) { kind ->
            choosingKind = false
            if (uploadedOrSay()) mutate("The kind could not be changed.") {
                repository.updateCustomQuestionnaire(id = questionnaireId, kind = kind.takeIf { it.isNotBlank() }, changeKind = true)
                if (kind.isBlank()) "Kind cleared. Answers stay unfiled in the report." else "Filed as ${labelForQuestionnaireKind(kind)}."
            }
        }
    }

    if (confirmingDelete) {
        FormDeleteDialog(busy = busy, onDismiss = { confirmingDelete = false }) {
            confirmingDelete = false
            if (!busy) {
                busy = true
                scope.launch {
                    runCatching { repository.deleteCustomQuestionnaire(questionnaireId) }
                        .onSuccess {
                            onMessage("Questionnaire deleted. Its author or an admin can restore it.")
                            onDeleted()
                        }
                        .onFailure { error ->
                            if (error !is CancellationException) onError(error.apiErrorMessage("The questionnaire could not be deleted."))
                        }
                    busy = false
                }
            }
        }
    }

    if (reusing && loaded != null) {
        ReuseQuestionnaireDialog(
            source = loaded,
            // The SAME list the attach control above uses — `attachableDesignWorkshops`, which
            // walks every page rather than the first hundred. The server 404s a workshop it has
            // not shown this account, so offering one that is merely known to exist would produce
            // a refusal the designer cannot act on. The whole state travels, not just the rows,
            // so the dialog can say why the list is short instead of implying there is nothing.
            workshops = workshops,
            busy = reuseBusy,
            onDismiss = { if (!reuseBusy) reusing = false },
            onCopy = { targetWorkshopId, newTitle, newDescription, changeDescription ->
                if (!reuseBusy) {
                    reuseBusy = true
                    reuseReport = null
                    scope.launch {
                        runCatching {
                            // Kept on this phone with no signal (2026-10-17): the copy changes
                            // nothing on this questionnaire, so it is safe to make later.
                            repository.reuseQuestionnaireOrQueue(
                                context = appContext,
                                id = questionnaireId,
                                designWorkshopId = targetWorkshopId,
                                title = newTitle,
                                description = newDescription,
                                changeDescription = changeDescription,
                                label = "Copy of “${newTitle?.takeIf { it.isNotBlank() } ?: loaded.title.ifBlank { "questionnaire" }}”",
                            )
                        }
                            .onSuccess { made ->
                                reusing = false
                                val result = made ?: run {
                                    onMessage(REUSE_KEPT_OFFLINE)
                                    return@onSuccess
                                }
                                reuseReport = result.report
                                // NAMED, because this screen does not move. The copy is a different
                                // questionnaire and nothing here navigates to it, so the one thing a
                                // designer needs is what to look for in the list — and the title is
                                // the server's, counted up if it collided, not the one they typed.
                                onMessage(
                                    "Copied. The new questionnaire is " +
                                        "“${result.questionnaire.title.ifBlank { "untitled" }}”."
                                )
                            }
                            .onFailure { error ->
                                if (error !is CancellationException) {
                                    // The server's own sentence, kept. A 404 here means the chosen
                                    // workshop is one this account cannot see and a 409 that it has
                                    // been soft-deleted; both are answers a designer can act on, and
                                    // "the copy could not be made" is neither.
                                    onError(error.apiErrorMessage("That questionnaire could not be copied."))
                                }
                            }
                        reuseBusy = false
                    }
                }
            },
        )
    }

    if (renaming && loaded != null) {
        RenameQuestionnaireDialog(
            initialTitle = loaded.title,
            initialDescription = loaded.description.orEmpty(),
            busy = busy,
            onDismiss = { renaming = false },
            onSave = { title, description ->
                renaming = false
                mutate("The questionnaire could not be renamed.") {
                    repository.authorQuestionnaireOrQueue(
                        appContext,
                        QFormOp(
                            op = QFormOp.RENAME_QUESTIONNAIRE,
                            questionnaireId = questionnaireId,
                            title = title,
                            description = description,
                            changeDescription = true,
                        ),
                        label = "Rename questionnaire · $title",
                    ).let { if (it == "Saved.") "Renamed." else it }
                }
            }
        )
    }

    if (addingSection) {
        SingleFieldDialog(
            heading = "New section",
            blurb = "Sections split a long questionnaire. Leave code blank to derive it.",
            label = "Section title",
            initial = "",
            busy = busy,
            onDismiss = { addingSection = false },
            onSave = { title ->
                addingSection = false
                mutate("The section could not be added.") {
                    // A TEMPORARY ID, so questions can be added to this section before it uploads.
                    repository.authorQuestionnaireOrQueue(
                        appContext,
                        QFormOp(op = QFormOp.ADD_SECTION, questionnaireId = questionnaireId, tempId = newTempId(), title = title),
                        label = "New section · $title",
                    ).let { if (it == "Saved.") "Section added." else it }
                }
            }
        )
    }

    renamingSection?.let { section ->
        SingleFieldDialog(
            heading = "Rename section",
            // Worth saying out loud, because it is the exception to everything else on this screen.
            blurb = "Headings can change even after answers exist.",
            label = "Section title",
            initial = section.title,
            busy = busy,
            onDismiss = { renamingSection = null },
            onSave = { title ->
                renamingSection = null
                mutate("The section could not be renamed.") {
                    repository.authorQuestionnaireOrQueue(
                        appContext,
                        QFormOp(op = QFormOp.RENAME_SECTION, questionnaireId = questionnaireId, sectionId = section.id, title = title),
                        label = "Rename section · $title",
                    ).let { if (it == "Saved.") "Section renamed." else it }
                }
            }
        )
    }

    addingQuestionTo?.let { section ->
        QuestionDialog(
            heading = "New question in ${section.title.ifBlank { section.code }}",
            initialPrompt = "",
            initialHelp = "",
            initialRequired = false,
            hasAnswers = false,
            busy = busy,
            onDismiss = { addingQuestionTo = null },
            onSave = { prompt, help, required ->
                addingQuestionTo = null
                mutate("The question could not be added.") {
                    repository.authorQuestionnaireOrQueue(
                        appContext,
                        QFormOp(
                            op = QFormOp.ADD_QUESTION,
                            questionnaireId = questionnaireId,
                            tempId = newTempId(),
                            // May itself be a temporary id: the section is created first at replay.
                            sectionId = section.id,
                            prompt = prompt,
                            helpText = help,
                            isRequired = required,
                        ),
                        label = "New question · $prompt",
                    ).let { if (it == "Saved.") "Question added." else it }
                }
            }
        )
    }

    editingQuestion?.let { question ->
        QuestionDialog(
            heading = "Edit question",
            initialPrompt = question.prompt,
            initialHelp = question.helpText.orEmpty(),
            initialRequired = question.isRequired,
            hasAnswers = question.hasAnswers,
            busy = busy,
            onDismiss = { editingQuestion = null },
            onSave = { prompt, help, required ->
                editingQuestion = null
                mutate("The question could not be edited.") {
                    repository.authorQuestionnaireOrQueue(
                        appContext,
                        QFormOp(
                            op = QFormOp.EDIT_QUESTION,
                            questionnaireId = questionnaireId,
                            questionId = question.id,
                            // Sent ONLY when the wording actually changed. Sending an unchanged prompt on
                            // an answered question would be indistinguishable from a rewording to the
                            // guard, so ticking "required" alone would supersede the question and split
                            // its answers across two rows saying the same thing.
                            prompt = prompt.takeIf { it.trim() != question.prompt.trim() },
                            helpText = help,
                            changeHelpText = true,
                            isRequired = required.takeIf { it != question.isRequired },
                        ),
                        label = "Edit question · ${question.prompt}",
                    // The server's own sentence, verbatim, whenever it did something other than the
                    // plain edit. See CustomQuestionEditResultDto.
                    ).let { if (it == "Saved.") "Question updated." else it }
                }
            }
        )
    }

    removingQuestion?.let { question ->
        // M17 (21 Oct 2026): an answer with notes but no text still counts, as on the server, so
        // its question is retired and the notes kept. Blank rows are cleared by the server first.
        val retires = questionRemovalRetires(question, loaded?.entries.orEmpty())
        AlertDialog(
            onDismissRequest = { if (!busy) removingQuestion = null },
            title = { Text(if (retires) "Retire this question?" else "Remove this question?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(question.prompt, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (retires) {
                            "Has answers, so it will be retired (no longer asked; answers kept)."
                        } else {
                            "No answers yet, so it will be deleted."
                        },
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        removingQuestion = null
                        mutate("The question could not be removed.") {
                            repository.authorQuestionnaireOrQueue(
                                appContext,
                                QFormOp(op = QFormOp.REMOVE_QUESTION, questionnaireId = questionnaireId, questionId = question.id),
                                label = "Remove question · ${question.prompt}",
                            ).let { if (it == "Saved.") "Question deleted." else it }
                        }
                    }
                ) { Text(if (retires) "Retire" else "Delete") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { removingQuestion = null }) { Text("Cancel") }
            }
        )
    }

    deletingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!busy) deletingEntry = null },
            title = { Text(QuestionnaireTrashCopy.SITTING_CONFIRM_TITLE) },
            text = {
                Text(
                    (entry.respondentName?.takeIf { it.isNotBlank() } ?: entry.title.ifBlank { "This sitting" }) +
                        ". " + QuestionnaireTrashCopy.SITTING_CONFIRM,
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        deletingEntry = null
                        mutate("The sitting could not be deleted.") {
                            repository.deleteCustomEntry(questionnaireId, entry.id)
                            "Sitting deleted. An admin can restore it."
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deletingEntry = null }) { Text("Cancel") }
            }
        )
    }

    if (startingSitting && loaded != null) {
        StartSittingDialog(
            busy = busy,
            onDismiss = { startingSitting = false },
            onStart = { respondent, notes ->
                startingSitting = false
                if (busy) return@StartSittingDialog
                busy = true
                val sent = "$respondent|$notes"
                val key = unansweredStart?.takeIf { it.first == sent }?.second ?: java.util.UUID.randomUUID().toString()
                unansweredStart = sent to key
                scope.launch {
                    runCatching {
                        repository.startCustomEntry(
                            id = questionnaireId,
                            respondentName = respondent,
                            notes = notes,
                            idempotencyKey = key,
                        )
                    }.onSuccess { entry ->
                        unansweredStart = null
                        // Straight into the answer sheet. A designer who has just named the person in
                        // front of them wants the first question, not a list with one more row on it.
                        onOpenEntry(entry.id)
                    }.onFailure { error ->
                        if (error !is CancellationException) {
                            onError(error.apiErrorMessage("The sitting could not be started."))
                        }
                    }
                    busy = false
                }
            }
        )
    }
}

// --------------------------------------------------------------------------------------
// The header: what this questionnaire is, and the two things about it that can change
// --------------------------------------------------------------------------------------

@Composable
private fun HeaderCard(
    form: CustomQuestionnaireDto,
    mayEdit: Boolean,
    busy: Boolean,
    workshops: AttachableWorkshops,
    onRename: () -> Unit,
    onAttach: (String) -> Unit,
    onSetKind: (String) -> Unit,
    onSetActive: (Boolean) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                form.title.ifBlank { "Untitled questionnaire" },
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge
            )
            form.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${plural(activeQuestionCount(form), "question")} in " +
                    "${plural(form.sections.count { it.isActive }, "section")} · version ${form.version}",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            form.sourceFilename?.takeIf { it.isNotBlank() }?.let {
                Text("From $it", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            if (!form.isActive) {
                Text(
                    "Deactivated — no new sittings. Nothing deleted.",
                    color = MaterialTheme.field.warning,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider(color = MaterialTheme.field.hairline)

            if (mayEdit) {
                /*
                  THE ATTACHED WORKSHOP IS PASSED IN AS THE OFF-PAGE ID, AND THAT IS THE POINT.

                  This is the one attach control that opens over a value that is already stored, and
                  a stored `designWorkshopId` can very easily be outside whatever this walk managed
                  to gather: past the 500-row ceiling, or absent because the walk failed on page one
                  in a courtyard. The trigger draws its label by looking the selected value up in the
                  options, so without this the screen printed **"Not attached"** over a questionnaire
                  that IS attached — and the designer's obvious next move, picking a workshop, would
                  have quietly re-filed somebody's fieldwork.

                  `options(attachedId)` keeps a row for it that says what it is rather than
                  pretending to be the workshop's name, so detaching stays possible offline and
                  nothing on screen claims a link that is not there or denies one that is.
                */
                val attachOptions = workshops.options(form.designWorkshopId.orEmpty())
                SearchableSelectField(
                    label = "Attached to a design workshop",
                    options = attachOptions,
                    selectedValue = form.designWorkshopId.orEmpty(),
                    placeholder = "Not attached",
                    includeNone = true,
                    enabled = !busy,
                    // §3.5's sentence for whichever state the walk is in. Never the primitive's
                    // fallback: only this screen knows whether the list is loading, failed, or
                    // genuinely empty for this account, and those have three different next moves.
                    emptyMessage = workshops.notice(),
                    onSelect = onAttach
                )
                workshops.capNotice()?.let { EmptyNote(it) }
                if (attachOptions.isEmpty() && !busy) {
                    // Empty AND enabled, so the picker's own stand-down line cannot fire — see the
                    // same guard on the create dialog.
                    workshops.notice()?.let { EmptyNote(it) }
                }
                /*
                  THE KIND, CHANGEABLE HERE — the same control and the same wording as the create
                  dialog and as the web, and it needs no off-page handling of the sort the workshop
                  picker above does: the vocabulary is two fixed members that every build carries, so
                  a stored value is always one of the rows unless it came from a NEWER server, and
                  [labelForQuestionnaireKind] prints such a token as itself rather than as "Not
                  stated". Nothing here can silently re-file a questionnaire the way a mislabelled
                  workshop trigger could.

                  It saves on select, like the attach control beside it: a pick has nothing to
                  proofread, and a designer who chose a kind and left the screen would have chosen
                  nothing.
                */
                SearchableSelectField(
                    label = "Kind",
                    // ``(token, text)`` AND NOT the tidier ``(value, label)``, and this is not a
                    // preference: naming them after the parameters they fill would write the
                    // parameter name twice in a row here — and that exact two-word sequence is what
                    // ``QuestionnaireDictationParityTest`` searches for, taking its FIRST occurrence
                    // in the file, to prove the shared section-title dialog passes its caption
                    // through to a dictated box. This dropdown sits above that dialog, so the
                    // obvious names quietly stole the assertion and left a real guarantee passing
                    // for a reason that had nothing to do with it. Measured, 2026-08-30: the test
                    // went red on the first spelling and green on this one.
                    //
                    // THE SAME TRAP CATCHES A COMMENT. Do not restore the sequence in prose here
                    // either — a source-reading test cannot tell code from the paragraph describing
                    // it, which is how this note failed once before being reworded.
                    options = QUESTIONNAIRE_KIND_LABELS.map { (token, text) ->
                        SelectOption(value = token, label = text)
                    },
                    selectedValue = form.kind.orEmpty(),
                    placeholder = "Not stated",
                    includeNone = true,
                    enabled = !busy,
                    onSelect = onSetKind
                )
                EmptyNote("Sets which report stage these answers go under.")
                ActionRow {
                    OutlinedButton(onClick = onRename, enabled = !busy) {
                        Text("Rename")
                    }
                    OutlinedButton(
                        onClick = { onSetActive(!form.isActive) },
                        enabled = !busy,
                    ) { Text(if (form.isActive) "Deactivate" else "Reactivate") }
                }
            } else {
                Text(
                    form.designWorkshopId?.takeIf { it.isNotBlank() }
                        ?.let { id ->
                            // "A design workshop" rather than a name this device could not fetch —
                            // and never "not attached", which is what a `firstOrNull` alone would
                            // read as on a phone whose walk failed. The read-only half of the same
                            // rule the picker above follows.
                            workshops.rows.firstOrNull { it.id == id }
                                ?.let(::designWorkshopLabel)
                                ?: "A design workshop"
                        }
                        ?.let { "Attached to $it" }
                        ?: "Not attached to a design workshop",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                // Said rather than left as an absence of buttons. The person this form was handed to
                // can still answer it, and a screen that merely looked read-only would send them off
                // to make a second copy of somebody else's questionnaire.
                Text(
                    "Only its creator or an admin can edit questions. You can still answer.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// The spreadsheet: two downloads that are not the same file, and the owner's re-upload
// --------------------------------------------------------------------------------------

/**
 * The .xlsx interchange for ONE questionnaire.
 *
 * ── WHY THE QUESTION SET IS OFFERED TO EVERYBODY AND THE WORKBOOK IS OFFERED TO EVERYBODY TOO ──
 *
 * Neither download is hidden, and that is a decision rather than an oversight.
 *
 * The QUESTION SET is genuinely ungated on the server: `_require_designer` and nothing more, on the
 * stated reasoning that `read_questionnaire` already hands the questions of any questionnaire to any
 * designer, so refusing the same content as a spreadsheet would protect nothing. Hiding the button
 * from a non-owner would therefore hide a file they are entitled to, and it is the file a colleague
 * who was handed this form most needs.
 *
 * The WORKBOOK is owner-gated, and this client still shows the button, because the server's 403
 * carries a sentence that is worth more than a hidden control: it says the question set exists, that
 * it holds the questions and no answers, and that any designer may take it. A greyed-out button
 * teaches nobody that. This is the one place in the questionnaire screens where a control is left
 * standing over a refusal on purpose, and the reason is that the refusal is a signpost rather than a
 * wall. `mayEditQuestionnaire`'s own note — "do not add a guard here over anything that is only
 * readable" — is the same principle read the other way.
 *
 * THE RE-UPLOAD IS OWNER-ONLY AND IS HIDDEN, unlike the two downloads, because its refusal is not a
 * signpost: there is no other door a non-owner could be sent to, and offering somebody a control
 * that can only ever answer 403 is the greyed-button failure with an extra network round trip.
 */
@Composable
private fun InterchangeCard(
    mayEdit: Boolean,
    busy: Boolean,
    downloading: DwQuestionnaireArtefact?,
    reuploading: Boolean,
    savedQuestionSetTo: String?,
    savedWorkbookTo: String?,
    onDownload: (DwQuestionnaireArtefact) -> Unit,
    onPickWorkbook: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "As a spreadsheet",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            // THE DISTINCTION, ONCE, ABOVE BOTH BUTTONS. Each row states its own contents as well —
            // see ArtefactNotice — but a designer scanning two adjacent buttons with a colleague
            // waiting needs the difference before they read either label.
            Text(
                "Two files: one has your questions, the other has every respondent. Check before sharing.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp
            )

            ArtefactDownloadRow(
                label = "Download question set",
                artefact = DwQuestionnaireArtefact.QUESTION_SET,
                busy = busy,
                working = downloading == DwQuestionnaireArtefact.QUESTION_SET,
                savedTo = savedQuestionSetTo,
                onDownload = { onDownload(DwQuestionnaireArtefact.QUESTION_SET) },
            )

            HorizontalDivider(color = MaterialTheme.field.hairline)

            ArtefactDownloadRow(
                label = "Download everything (.xlsx)",
                artefact = DwQuestionnaireArtefact.FULL_WORKBOOK,
                busy = busy,
                working = downloading == DwQuestionnaireArtefact.FULL_WORKBOOK,
                savedTo = savedWorkbookTo,
                onDownload = { onDownload(DwQuestionnaireArtefact.FULL_WORKBOOK) },
            )
            if (!mayEdit) {
                Text(
                    "For its creator, its workshop's designers, or admins. You can always copy the questions.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }

            if (mayEdit) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                WorkbookUploadRow(
                    label = "Upload an edited workbook",
                    blurb = "Replaces this questionnaire's questions with the file's. Answered questions keep their old wording alongside. Files from other questionnaires are refused. $WORKBOOK_UPLOAD_NEEDS_CONNECTION",
                    busy = busy,
                    working = reuploading,
                    onPick = onPickWorkbook,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// The sittings
// --------------------------------------------------------------------------------------

@Composable
private fun SittingsCard(
    form: CustomQuestionnaireDto,
    busy: Boolean,
    onStart: () -> Unit,
    onOpenEntry: (String) -> Unit,
    /** L9: null unless this account may delete sittings (content admins). */
    onDeleteEntry: ((CustomEntryDto) -> Unit)? = null,
) {
    val total = activeQuestionCount(form)
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Sittings",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "One sitting is one filled-in copy of this questionnaire.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )

            if (form.entries.isEmpty()) {
                EmptyNote("No answers recorded yet.")
            }
            form.entries.forEach { entry ->
                SittingRow(
                    entry = entry,
                    total = total,
                    form = form,
                    onOpen = { onOpenEntry(entry.id) },
                    onDelete = onDeleteEntry?.let { delete -> { delete(entry) } },
                )
            }

            Button(
                onClick = onStart,
                // The server refuses a new sitting on a deactivated questionnaire with a 409, so the
                // button says why instead of buying one.
                enabled = !busy && form.isActive && total > 0,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start a sitting") }
            when {
                !form.isActive -> EmptyNote("Deactivated — can't record new answers.")
                total == 0 -> EmptyNote("Add questions first.")
                else -> EmptyNote(SITTING_START_NEEDS_CONNECTION)
            }
        }
    }
}

/**
 * One sitting, expandable into the answers it holds.
 *
 * The answers are read from the form loaded WITH retired questions, so a question that was retired
 * after somebody answered it still shows its wording here beside the answer it was given under. Left
 * to the answer screen's own copy of the form it would render as an answer to a question that no
 * longer exists — which is how recorded fieldwork becomes unreadable without being deleted.
 */
@Composable
private fun SittingRow(
    entry: CustomEntryDto,
    total: Int,
    form: CustomQuestionnaireDto,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val promptById = remember(form) {
        form.sections.flatMap { it.questions }.associate { it.id to it }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f).clickable { expanded = !expanded },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    entry.respondentName?.takeIf { it.isNotBlank() } ?: entry.title.ifBlank { "Answers" },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(entrySubtitle(entry, total), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onOpen) { Text("Answer") }
        }
        if (onDelete != null) {
            // L9: content admins only; the sitting and its answers are kept and can be restored.
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                Text("Delete sitting", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (expanded) {
            val recorded = entry.answers.filter { !it.answerText.isNullOrBlank() || !it.notes.isNullOrBlank() }
            if (recorded.isEmpty()) {
                EmptyNote("Nothing recorded on this sitting yet.")
            }
            recorded.forEach { answer ->
                val question = promptById[answer.questionId]
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        question?.prompt ?: "(a question no longer on this form)",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        answer.answerText.orEmpty().ifBlank { "—" },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    answer.notes?.takeIf { it.isNotBlank() }?.let {
                        Text("Note: $it", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (question != null && !question.isActive) {
                        QChip(
                            "question retired",
                            MaterialTheme.field.warningContainer,
                            MaterialTheme.field.onWarningContainer
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.field.hairline)
    }
}

// --------------------------------------------------------------------------------------
// The form itself
// --------------------------------------------------------------------------------------

@Composable
private fun FormCard(
    form: CustomQuestionnaireDto,
    mayEdit: Boolean,
    mayDelete: Boolean,
    busy: Boolean,
    onAddSection: () -> Unit,
    onRenameSection: (CustomSectionDto) -> Unit,
    onAddQuestion: (CustomSectionDto) -> Unit,
    onEditQuestion: (CustomQuestionDto) -> Unit,
    onRemoveQuestion: (CustomQuestionDto) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "The questions",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (form.sections.isEmpty()) {
                EmptyNote(
                    "No sections yet. Add them here, or upload a filled-in pro-forma."
                )
            }
            form.sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${section.code} — ${section.title}".trim(' ', '—'),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!section.isActive) {
                                Text(
                                    "Retired — kept because it has answers.",
                                    color = MaterialTheme.field.warning,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (mayEdit) {
                            TextButton(onClick = { onRenameSection(section) }, enabled = !busy) {
                                Text("Rename")
                            }
                        }
                    }

                    if (section.questions.isEmpty()) {
                        EmptyNote("No questions in this section.")
                    }
                    section.questions.forEach { question ->
                        QuestionRow(
                            question = question,
                            mayEdit = mayEdit,
                            mayDelete = mayDelete,
                            busy = busy,
                            onEdit = { onEditQuestion(question) },
                            onRemove = { onRemoveQuestion(question) },
                        )
                    }

                    if (mayEdit) {
                        ActionRow {
                            TextButton(onClick = { onAddQuestion(section) }, enabled = !busy) {
                                Text("Add a question to this section")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                }
            }
            if (mayEdit) {
                OutlinedButton(onClick = onAddSection, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Add a section")
                }
            }
        }
    }
}

@Composable
private fun QuestionRow(
    question: CustomQuestionDto,
    mayEdit: Boolean,
    mayDelete: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            requiredMarked(question.prompt + if (question.isRequired) " *" else ""),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        question.helpText?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!question.isActive) {
                QChip(
                    if (question.supersededById != null) "replaced" else "retired",
                    MaterialTheme.field.warningContainer,
                    MaterialTheme.field.onWarningContainer
                )
            } else if (question.hasAnswers) {
                // The single fact that decides what the two buttons below actually do. Shown BEFORE
                // the designer opens the editor, not after they press save.
                QChip(
                    "has answers",
                    MaterialTheme.field.successContainer,
                    MaterialTheme.field.onSuccessContainer
                )
            }
        }
        if (!question.isActive) {
            Text(
                if (question.supersededById != null) {
                    "Replaced wording. No longer asked; answers kept."
                } else {
                    "Retired. No longer asked; answers kept."
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // A retired question is offered NO controls at all. Editing one is refused by the server (and
        // the re-upload path says so in as many words), and "deleting" something already gone would
        // only be a way to hit the ON DELETE RESTRICT on its answers.
        if ((mayEdit || mayDelete) && question.isActive) {
            ActionRow {
                if (mayEdit) {
                    TextButton(onClick = onEdit, enabled = !busy) { Text(RecordOpen.UPDATE_LABEL, style = MaterialTheme.typography.labelMedium) }
                }
                // M2: the form's author or a content admin, the server's rule on every path.
                if (mayDelete) {
                    TextButton(onClick = onRemove, enabled = !busy) {
                        Text(if (question.hasAnswers) "Retire" else "Delete", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// Dialogs
// --------------------------------------------------------------------------------------

@Composable
private fun RenameQuestionnaireDialog(
    initialTitle: String,
    initialDescription: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Rename questionnaire") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                /*
                 * ── THE AUTHORING SURFACE GETS A MICROPHONE TOO — 2026-08-28 ──────────────────
                 *
                 * The owner: *"dictation should be a default for other record pages as well."* A
                 * designer builds a questionnaire on the same handset they later carry into the
                 * workshop, and every box in these dialogs is prose somebody composes. The web's
                 * twin (`app/(protected)/questionnaires/[id]/page.tsx`) carries the same five
                 * microphones in the same five places and leaves the same one box without one — the
                 * section CODE, which is an identifier a recogniser would return as a dictionary
                 * word.
                 *
                 * `RecordProseField` rather than a control of this file's own: one composable so the
                 * twenty screens cannot drift in what dictation does or how it refuses. See its
                 * header.
                 */
                RecordProseField(
                    label = "Title *",
                    value = title,
                    onValueChange = { title = it },
                    enabled = !busy,
                    dictate = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                RecordProseField(
                    label = "Description",
                    value = description,
                    onValueChange = { description = it },
                    enabled = !busy,
                    dictate = !busy,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && title.isNotBlank(), onClick = { onSave(title, description) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SingleFieldDialog(
    heading: String,
    blurb: String,
    label: String,
    initial: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(blurb, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                /* Both call sites of this dialog pass "Section title" — a heading a designer writes,
                   which is prose. If a third caller ever passes a CODE or a number, give this
                   composable a `dictate` parameter rather than reaching for `label == ...`: the
                   record forms' rule is that a box expecting digits must opt OUT explicitly, and a
                   string comparison on a label is not an opt-out anybody would find. */
                RecordProseField(
                    label = label,
                    value = value,
                    onValueChange = { value = it },
                    enabled = !busy,
                    dictate = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && value.isNotBlank(), onClick = { onSave(value) }) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Add or edit one question.
 *
 * [hasAnswers] changes nothing about what is sent and everything about what is said. The warning is
 * shown BEFORE the wording box, because after the save the correction has already become a second
 * question and the designer is reading an explanation of something that already happened.
 */
@Composable
private fun QuestionDialog(
    heading: String,
    initialPrompt: String,
    initialHelp: String,
    initialRequired: Boolean,
    hasAnswers: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (prompt: String, help: String, required: Boolean) -> Unit,
) {
    var prompt by remember { mutableStateOf(initialPrompt) }
    var help by remember { mutableStateOf(initialHelp) }
    var required by remember { mutableStateOf(initialRequired) }
    val reworded = prompt.trim() != initialPrompt.trim()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasAnswers) {
                    Text(
                        "Has answers. Changing the wording adds a new question; the old one keeps its answers. Hint and required can change freely.",
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                RecordProseField(
                    label = "Question *",
                    value = prompt,
                    onValueChange = { prompt = it },
                    enabled = !busy,
                    dictate = !busy,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (hasAnswers && reworded) {
                    Text(
                        "Saving adds a new question and retires this wording.",
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                RecordProseField(
                    label = "Hint shown under the question",
                    value = help,
                    onValueChange = { help = it },
                    enabled = !busy,
                    dictate = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                AppToggle(label = "Required", checked = required, onCheckedChange = { required = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && prompt.isNotBlank(),
                onClick = { onSave(prompt, help, required) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StartSittingDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onStart: (respondent: String, notes: String) -> Unit,
) {
    var respondent by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Start a sitting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Name who's answering to tell sittings apart.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = respondent,
                    onValueChange = { respondent = it },
                    label = { Text("Who is answering") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                /* "Who is answering" above stays bare and this does not, which is the split the
                   record forms already make: a recogniser is at its worst on a proper noun, and the
                   respondent's name is the string these sittings are later searched by. Notes are
                   prose. `QuestionnaireAnswerScreen`'s copy of this dialog says the same. */
                RecordProseField(
                    label = "Notes",
                    value = notes,
                    onValueChange = { notes = it },
                    enabled = !busy,
                    dictate = !busy,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onStart(respondent, notes) }) { Text("Start") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
