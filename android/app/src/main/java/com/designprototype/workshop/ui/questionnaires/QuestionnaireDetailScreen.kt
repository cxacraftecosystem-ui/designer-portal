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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuestionnaireDetailScreen(
    repository: WorkshopRepository,
    questionnaireId: String,
    onOpenEntry: (entryId: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    onResolvedId: (serverId: String) -> Unit = {},
    onDeleted: () -> Unit = {},
    onStartSession: ((questionnaireId: String) -> Unit)? = null,
    onOpenSessions: ((questionnaireId: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    var form by remember(questionnaireId) { mutableStateOf<CustomQuestionnaireDto?>(null) }
    var loading by remember(questionnaireId) { mutableStateOf(true) }
    var loadError by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember(questionnaireId) { mutableIntStateOf(0) }
    var workshops by remember { mutableStateOf(AttachableWorkshops()) }
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
    var deletingEntry by remember { mutableStateOf<CustomEntryDto?>(null) }
    var startingSitting by remember { mutableStateOf(false) }
    var unansweredStart by remember(questionnaireId) { mutableStateOf<Pair<String, String>?>(null) }

    var interchangeBusy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<DwQuestionnaireArtefact?>(null) }
    var reuploading by remember { mutableStateOf(false) }
    var savedQuestionSetTo by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var savedWorkbookTo by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var uploadReport by remember(questionnaireId) { mutableStateOf<QFormChangeReportDto?>(null) }

    var reusing by remember { mutableStateOf(false) }
    var reuseBusy by remember { mutableStateOf(false) }
    var reuseReport by remember(questionnaireId) { mutableStateOf<QFormChangeReportDto?>(null) }

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
                    reload++
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
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
        runCatching {
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

    fun uploadedOrSay(): Boolean {
        if (!isTempId(questionnaireId)) return true
        onError(QUESTIONNAIRE_STILL_UPLOADING)
        return false
    }

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
                    onPickWorkbook = {
                        if (!uploadedOrSay()) return@InterchangeCard
                        if (repository.isOnline(appContext)) pickWorkbook() else onError(WORKBOOK_UPLOAD_OFFLINE)
                    },
                )

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

                ReuseCard(busy = reuseBusy, onOpen = { if (uploadedOrSay()) reusing = true })

                uploadReport?.let { report ->
                    UploadReportPanel(report = report, onDismiss = { uploadReport = null })
                }

                reuseReport?.let { report ->
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
            workshops = workshops,
            busy = reuseBusy,
            onDismiss = { if (!reuseBusy) reusing = false },
            onCopy = { targetWorkshopId, newTitle, newDescription, changeDescription ->
                if (!reuseBusy) {
                    reuseBusy = true
                    reuseReport = null
                    scope.launch {
                        runCatching {
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
                                onMessage(
                                    "Copied. The new questionnaire is " +
                                        "“${result.questionnaire.title.ifBlank { "untitled" }}”."
                                )
                            }
                            .onFailure { error ->
                                if (error !is CancellationException) {
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
                            prompt = prompt.takeIf { it.trim() != question.prompt.trim() },
                            helpText = help,
                            changeHelpText = true,
                            isRequired = required.takeIf { it != question.isRequired },
                        ),
                        label = "Edit question · ${question.prompt}",
                    ).let { if (it == "Saved.") "Question updated." else it }
                }
            }
        )
    }

    removingQuestion?.let { question ->
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
                val attachOptions = workshops.options(form.designWorkshopId.orEmpty())
                SearchableSelectField(
                    label = "Attached to a design workshop",
                    options = attachOptions,
                    selectedValue = form.designWorkshopId.orEmpty(),
                    placeholder = "Not attached",
                    includeNone = true,
                    enabled = !busy,
                    emptyMessage = workshops.notice(),
                    onSelect = onAttach
                )
                workshops.capNotice()?.let { EmptyNote(it) }
                if (attachOptions.isEmpty() && !busy) {
                    workshops.notice()?.let { EmptyNote(it) }
                }
                SearchableSelectField(
                    label = "Kind",
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
                            workshops.rows.firstOrNull { it.id == id }
                                ?.let(::designWorkshopLabel)
                                ?: "A design workshop"
                        }
                        ?.let { "Attached to $it" }
                        ?: "Not attached to a design workshop",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Only its creator or an admin can edit questions. You can still answer.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

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

@Composable
private fun SittingsCard(
    form: CustomQuestionnaireDto,
    busy: Boolean,
    onStart: () -> Unit,
    onOpenEntry: (String) -> Unit,
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
        if ((mayEdit || mayDelete) && question.isActive) {
            ActionRow {
                if (mayEdit) {
                    TextButton(onClick = onEdit, enabled = !busy) { Text(RecordOpen.UPDATE_LABEL, style = MaterialTheme.typography.labelMedium) }
                }
                if (mayDelete) {
                    TextButton(onClick = onRemove, enabled = !busy) {
                        Text(if (question.hasAnswers) "Retire" else "Delete", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

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
