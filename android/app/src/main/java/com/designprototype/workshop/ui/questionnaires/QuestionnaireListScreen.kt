package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.ui.AppToggle
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import com.designprototype.workshop.ui.RecordOpen
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.designprototype.workshop.data.CustomQuestionnaireSummaryDto
import com.designprototype.workshop.data.DwQuestionnaireArtefact
import com.designprototype.workshop.data.QFormChangeReportDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.visibleQuestionnaires
import com.designprototype.workshop.data.ReadCache
import com.designprototype.workshop.data.readCacheNotice
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.RecordProseField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.designprototype.workshop.ui.ListReachText
import com.designprototype.workshop.ui.ListReachButton

private const val SEARCH_DEBOUNCE_MS = 350L

@Composable
fun QuestionnaireListScreen(
    repository: WorkshopRepository,
    onOpen: (questionnaireId: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    incoming: Uri? = null,
    onIncomingConsumed: () -> Unit = {},
    newFormRequest: Int = 0,
    onStartSession: ((questionnaireId: String) -> Unit)? = null,
    onOpenSessions: ((questionnaireId: String) -> Unit)? = null,
) {
    var rows by com.designprototype.workshop.questionnaire.ui.rememberKept("forms.rows") { emptyList<CustomQuestionnaireSummaryDto>() }
    var loading by remember { mutableStateOf(true) }
    var offline by com.designprototype.workshop.questionnaire.ui.rememberKept("forms.offline") { false }
    var cachedNote by com.designprototype.workshop.questionnaire.ui.rememberKept("forms.cachedNote") { null as String? }
    val v2Context = LocalContext.current.applicationContext
    val v2 = remember { com.designprototype.workshop.questionnaire.data.QuestionnaireV2.get(v2Context) }
    val (formFilters, setFormFilters) = com.designprototype.workshop.questionnaire.ui.rememberKeptFilters(
        v2, com.designprototype.workshop.questionnaire.data.FilterStore.FORMS
    )
    val search = formFilters.q
    val narrowed = formFilters.without("q")
    var showDeactivated by com.designprototype.workshop.questionnaire.ui.rememberKept("forms.showDeactivated") { false }
    var showCreate by remember { mutableStateOf(false) }
    var tab by com.designprototype.workshop.questionnaire.ui.rememberKept("forms.tab") { FormsTab.MINE }
    var pickedBundle by remember { mutableStateOf<Uri?>(null) }
    val pickBundle = rememberQuestionnaireBundlePicker { pickedBundle = it }
    var answeredNewForm by com.designprototype.workshop.questionnaire.ui.rememberKept("forms.answeredNewForm") { newFormRequest }
    LaunchedEffect(newFormRequest) { if (newFormRequest > answeredNewForm) { answeredNewForm = newFormRequest; showCreate = true } }
    var reload by remember { mutableIntStateOf(0) }
    var partial by com.designprototype.workshop.questionnaire.ui.rememberKept("forms.partial") { null as Pair<Int, Int>? }

    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var interchangeBusy by remember { mutableStateOf(false) }
    var downloadingProForma by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var proFormaSavedTo by remember { mutableStateOf<String?>(null) }
    var uploadReport by remember { mutableStateOf<QFormChangeReportDto?>(null) }

    val pickWorkbook = rememberWorkbookPicker { uri ->
        if (interchangeBusy) return@rememberWorkbookPicker
        interchangeBusy = true
        uploading = true
        uploadReport = null
        scope.launch {
            runCatching { repository.uploadQuestionnaireWorkbook(appContext, uri) }
                .onSuccess { result ->
                    uploadReport = result.report
                    reload++
                    onMessage("“${result.questionnaire.title}” created.")
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        onError(error.apiErrorMessage("That workbook could not be uploaded."))
                    }
                }
            uploading = false
            interchangeBusy = false
        }
    }

    LaunchedEffect(reload, formFilters, showDeactivated) {
        if (search.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        loading = true
        cachedNote = null
        runCatching {
            if (search.isBlank() && narrowed.isEmpty) {
                var listing: com.designprototype.workshop.data.PagedListing<CustomQuestionnaireSummaryDto>? = null
                val read = ReadCache.read(
                    appContext,
                    "questionnaires:${if (showDeactivated) "all" else "active"}",
                    kotlinx.serialization.builtins.ListSerializer(CustomQuestionnaireSummaryDto.serializer()),
                ) { repository.visibleQuestionnaires(activeOnly = !showDeactivated).also { listing = it }.items }
                if (read.fromCache) {
                    cachedNote = readCacheNotice(read.cachedAt)
                    com.designprototype.workshop.data.PagedListing(items = read.value, total = read.value.size, truncated = false)
                } else {
                    listing!!
                }
            } else {
                repository.visibleQuestionnaires(
                    search = search.ifBlank { null },
                    activeOnly = !showDeactivated,
                    filters = narrowed.toParams(),
                )
            }
        }.onSuccess {
            val waiting = if (search.isBlank() && narrowed.isEmpty) {
                runCatching { repository.pendingQuestionnaires(appContext) }.getOrDefault(emptyList()).map { queued ->
                    CustomQuestionnaireSummaryDto(
                        id = queued.id,
                        title = queued.title,
                        description = "Waiting to upload — kept on this phone.",
                        ownerId = queued.ownerId,
                        designWorkshopId = queued.designWorkshopId,
                    )
                }
            } else {
                emptyList()
            }
            rows = waiting + it.items
            offline = false
            partial = if (it.truncated) it.items.size to it.total else null
        }.onFailure { error ->
            if (error !is CancellationException) {
                offline = true
                onError(error.apiErrorMessage("The questionnaires could not be listed."))
                partial = null
            }
        }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Questionnaires",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Your questionnaires and those on your workshops. Open one to record answers, or exchange them as spreadsheets.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        com.designprototype.workshop.questionnaire.ui.FilterBar(
            formFilters, setFormFilters,
            com.designprototype.workshop.questionnaire.data.QuestionnaireCopy.t("filterSearchForms"),
            options = { facet, q, ids -> v2.filterOptions(facet, q, ids) },
            fields = com.designprototype.workshop.questionnaire.ui.FilterField.FORMS,
        )

        fun downloadProForma() {
            if (interchangeBusy) return
            interchangeBusy = true
            downloadingProForma = true
            proFormaSavedTo = null
            scope.launch {
                runCatching {
                    repository.downloadQuestionnaireArtefact(
                        context = appContext,
                        artefact = DwQuestionnaireArtefact.PRO_FORMA,
                    )
                }
                    .onSuccess { proFormaSavedTo = it }
                    .onFailure { error ->
                        if (error !is CancellationException) {
                            onError(error.apiErrorMessage("The pro-forma could not be downloaded."))
                        }
                    }
                downloadingProForma = false
                interchangeBusy = false
            }
        }
        LibraryActionPills(
            listOf(
                LibraryAction("New form", LibraryIcons.NEW) { showCreate = true },
                LibraryAction("Upload workbook", LibraryIcons.UPLOAD, enabled = !interchangeBusy) {
                    if (repository.isOnline(appContext)) pickWorkbook() else onError(WORKBOOK_UPLOAD_OFFLINE)
                },
                LibraryAction("Download pro-forma", LibraryIcons.DOWNLOAD, enabled = !interchangeBusy) { downloadProForma() },
                LibraryAction("Open bundle", LibraryIcons.OPEN) { pickBundle() },
            )
        )
        when {
            uploading -> Text("Uploading the workbook…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            downloadingProForma -> Text("Downloading the pro-forma…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            proFormaSavedTo != null -> Text("Pro-forma saved to ${proFormaSavedTo.orEmpty()}.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }

        uploadReport?.let { report ->
            UploadReportPanel(report = report, onDismiss = { uploadReport = null })
        }

        ReceivedQuestionnairesCard(
            repository = repository,
            incoming = incoming ?: pickedBundle,
            onIncomingConsumed = { if (incoming != null) onIncomingConsumed() else { pickedBundle = null } },
            onAdopted = { reload++ },
            onMessage = onMessage,
            onError = onError,
        )

        val trashUser = repository.cachedUser()
        val mayListDeleted = trashUser != null &&
            (mayDeleteQuestionnaireItems(trashUser) || com.designprototype.workshop.ui.FieldPermissions.canRunDesignWorkshops(trashUser))
        val meId = trashUser?.id
        val tabs = formsTabs(mayListDeleted)
        if (tab !in tabs) tab = FormsTab.MINE
        FormsTabRow(
            tabs = tabs,
            selected = tab,
            counts = tabs.filter { it != FormsTab.DELETED }.associateWith { formsInTab(rows, it, meId).size },
            onSelect = { tab = it },
        )
        val shown = formsInTab(rows, tab, meId)
        if (tab == FormsTab.DELETED) {
            DeletedQuestionnairesCard(
                repository = repository,
                ownOnly = !mayDeleteQuestionnaireItems(trashUser),
                onRestored = { reload++ },
                onMessage = onMessage,
                onError = onError,
            )
        }

        if (tab != FormsTab.DELETED) AppToggle(
            label = "Show deactivated",
            checked = showDeactivated,
            onCheckedChange = { showDeactivated = it },
            description = "Hidden from new interviews; answers kept.",
        )

        when {
            tab == FormsTab.DELETED -> Unit

            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            cachedNote != null && rows.isNotEmpty() -> {
                com.designprototype.workshop.ui.CachedCopyNote(cachedNote.orEmpty())
                shown.forEach { row -> QuestionnaireRow(row = row, onOpen = { onOpen(row.id) }, onStartSession = onStartSession) }
            }

            offline -> Text(
                "Offline — this list may be incomplete.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall
            )

            shown.isEmpty() -> EmptyNote(
                if (search.isNotBlank() || !narrowed.isEmpty) {
                    com.designprototype.workshop.questionnaire.data.QuestionnaireCopy.t("filterNoMatches")
                } else {
                    formsTabEmpty(tab)
                }
            )

            else -> shown.forEach { row ->
                QuestionnaireRow(row = row, onOpen = { onOpen(row.id) }, onStartSession = onStartSession)
            }
        }

        partial?.let { (shown, total) ->
            ListReachButton(
                shown = shown,
                total = total,
                label = ListReachText.loadAll(total),
                loading = loading,
                tag = "forms.loadall",
                onClick = { reload++ },
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    if (showCreate) {
        CreateQuestionnaireDialog(
            repository = repository,
            onDismiss = { showCreate = false },
            onCreated = { id ->
                showCreate = false
                onMessage("Questionnaire created. Add sections and questions.")
                onOpen(id)
            },
            onError = onError,
        )
    }
}

@Composable
private fun QuestionnaireRow(
    row: CustomQuestionnaireSummaryDto,
    onOpen: () -> Unit,
    onStartSession: ((questionnaireId: String) -> Unit)? = null,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = RecordOpen.openClickLabel(row.title.ifBlank { "Untitled questionnaire" }),
                    role = Role.Button,
                    onClick = onOpen
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                row.title.ifBlank { "Untitled questionnaire" },
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            row.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            Text(
                row.designWorkshopTitle?.takeIf { it.isNotBlank() }
                    ?.let { "Attached to $it" }
                    ?: "Not attached to a design workshop",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            val trail = listOfNotNull(
                row.ownerName?.takeIf { it.isNotBlank() },
                row.sourceFilename?.takeIf { it.isNotBlank() },
                row.updatedAt?.take(10)?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (trail.isNotBlank()) {
                Text(trail, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.isShared) {
                    QChip(
                        "Standard form",
                        MaterialTheme.field.surface200,
                        MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!row.isActive) {
                    QChip(
                        "Deactivated",
                        MaterialTheme.field.warningContainer,
                        MaterialTheme.field.onWarningContainer
                    )
                }
                if (!row.kind.isNullOrBlank()) {
                    QChip(
                        row.kindLabel.ifBlank { labelForQuestionnaireKind(row.kind) },
                        MaterialTheme.field.surface200,
                        MaterialTheme.field.muted
                    )
                }
                if (row.version > 1) {
                    QChip(
                        "version ${row.version}",
                        MaterialTheme.field.surface200,
                        MaterialTheme.field.muted
                    )
                }
            }
            if (onStartSession != null && row.isActive && row.deletedAt == null && !com.designprototype.workshop.data.isTempId(row.id)) {
                TextButton(onClick = { onStartSession(row.id) }) { Text("Start session") }
            }
        }
    }
}

@Composable
private fun CreateQuestionnaireDialog(
    repository: WorkshopRepository,
    onDismiss: () -> Unit,
    onCreated: (questionnaireId: String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var workshopId by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    var workshops by remember { mutableStateOf(AttachableWorkshops()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { workshops = attachableDesignWorkshops(repository) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New questionnaire") },
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
                val attachOptions = workshops.options()
                SearchableSelectField(
                    label = "Attach to a design workshop",
                    options = attachOptions,
                    selectedValue = workshopId,
                    placeholder = "Not attached",
                    includeNone = true,
                    enabled = !busy,
                    emptyMessage = workshops.notice(),
                    onSelect = { workshopId = it }
                )
                workshops.capNotice()?.let { cap ->
                    EmptyNote(cap)
                }
                if (attachOptions.isEmpty() && !busy) {
                    workshops.notice()?.let { EmptyNote(it) }
                }
                SearchableSelectField(
                    label = "Kind",
                    options = QUESTIONNAIRE_KIND_LABELS.map { (token, text) ->
                        SelectOption(value = token, label = text)
                    },
                    selectedValue = kind,
                    placeholder = "Not stated",
                    includeNone = true,
                    enabled = !busy,
                    onSelect = { kind = it }
                )
                EmptyNote("Sets which report stage these answers go under.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            repository.createCustomQuestionnaireOrQueue(
                                context = appContext,
                                title = title,
                                description = description,
                                designWorkshopId = workshopId.takeIf { it.isNotBlank() },
                                kind = kind.takeIf { it.isNotBlank() },
                            )
                        }.onSuccess { created ->
                            if (created != null && com.designprototype.workshop.data.isTempId(created.id)) {
                                onError(
                                    "“${title.trim()}” saved on this phone; uploads when online. You can add questions now."
                                )
                                onCreated(created.id)
                            } else if (created != null) {
                                onCreated(created.id)
                            } else {
                                onDismiss()
                            }
                        }
                            .onFailure { error ->
                                if (error !is CancellationException) {
                                    onError(error.apiErrorMessage("The questionnaire could not be created."))
                                }
                            }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Starting…" else "Start") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
