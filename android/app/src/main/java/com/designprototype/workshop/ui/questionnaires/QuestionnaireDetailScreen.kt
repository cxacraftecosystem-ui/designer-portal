package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
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
@Composable
fun QuestionnaireDetailScreen(
    repository: WorkshopRepository,
    questionnaireId: String,
    onOpenEntry: (entryId: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The application context, so the cache write below cannot outlive an Activity it holds.
    val appContext = LocalContext.current.applicationContext

    var form by remember(questionnaireId) { mutableStateOf<CustomQuestionnaireDto?>(null) }
    var loading by remember(questionnaireId) { mutableStateOf(true) }
    var loadError by remember(questionnaireId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember(questionnaireId) { mutableIntStateOf(0) }
    var workshops by remember { mutableStateOf<List<SelectOption>>(emptyList()) }

    var renaming by remember { mutableStateOf(false) }
    var addingSection by remember { mutableStateOf(false) }
    var renamingSection by remember { mutableStateOf<CustomSectionDto?>(null) }
    var addingQuestionTo by remember { mutableStateOf<CustomSectionDto?>(null) }
    var editingQuestion by remember { mutableStateOf<CustomQuestionDto?>(null) }
    var removingQuestion by remember { mutableStateOf<CustomQuestionDto?>(null) }
    var startingSitting by remember { mutableStateOf(false) }

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

    /**
     * Set when the questionnaire on screen came off this device's disk.
     *
     * Every write on this screen goes through `mutate`, which will fail with a network error while
     * this is set — and that is left as it is deliberately. This screen's writes are edits to the
     * INSTRUMENT (rename a section, retire a question), and those genuinely cannot be composed
     * offline: `guard_question_edit` decides between "updated" and "superseded" from whether the
     * question already has answers, which is a fact this copy cannot know. Saying so up front is
     * this notice's job; refusing per-button would be four more places to keep the same sentence.
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
            repository.customQuestionnaireCached(
                context = appContext,
                id = questionnaireId,
                includeRetired = true,
            )
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
                runCatching { DwQuestionnaireStore.mergeQuestionnaire(appContext, it) }
            }
            .onFailure { error ->
                if (error !is CancellationException) {
                    loadError = error.apiErrorMessage("This questionnaire could not be opened.")
                }
            }
        loading = false
    }

    LaunchedEffect(questionnaireId) { workshops = designWorkshopOptions(repository) }

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

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            loading && loaded == null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            loadError != null && loaded == null ->
                Text(loadError.orEmpty(), color = MaterialTheme.field.warning, fontSize = 13.sp)

            loaded == null -> EmptyNote("This questionnaire could not be opened.")

            else -> {
                cachedNotice?.let { notice ->
                    Text(notice, color = MaterialTheme.field.warning, fontSize = 12.sp, lineHeight = 17.sp)
                }
                HeaderCard(
                    form = loaded,
                    mayEdit = mayEdit,
                    busy = busy,
                    workshops = workshops,
                    onRename = { renaming = true },
                    onAttach = { workshopId ->
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
                    onSetActive = { active ->
                        mutate("The questionnaire could not be updated.") {
                            repository.updateCustomQuestionnaire(id = questionnaireId, isActive = active)
                            if (active) {
                                "Back in the lists."
                            } else {
                                "Deactivated. It is out of every list and dropdown; its answers are untouched."
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
                    onDownload = ::download,
                    onPickWorkbook = pickWorkbook,
                )

                // THE OFFLINE HANDOFF, directly beneath the two .xlsx controls and sharing their one
                // busy flag on purpose: all three end in `persistFileToDownloads`, writing into the
                // same folder, and two of those racing is how one file ends up truncated with no
                // error anywhere. The neighbouring card is the SERVER interchange — it needs a
                // connection at both ends; this one is built on the handset and needs none, which is
                // the whole distinction a designer standing in a courtyard is choosing between.
                QuestionnaireHandoffCard(
                    repository = repository,
                    questionnaireId = questionnaireId,
                    busy = interchangeBusy,
                    onBusyChange = { interchangeBusy = it },
                    onError = onError,
                )

                uploadReport?.let { report ->
                    UploadReportPanel(report = report, onDismiss = { uploadReport = null })
                }

                SittingsCard(
                    form = loaded,
                    busy = busy,
                    onStart = { startingSitting = true },
                    onOpenEntry = onOpenEntry,
                )

                FormCard(
                    form = loaded,
                    mayEdit = mayEdit,
                    busy = busy,
                    onAddSection = { addingSection = true },
                    onRenameSection = { renamingSection = it },
                    onAddQuestion = { addingQuestionTo = it },
                    onEditQuestion = { editingQuestion = it },
                    onRemoveQuestion = { removingQuestion = it },
                )
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
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
                    repository.updateCustomQuestionnaire(
                        id = questionnaireId,
                        title = title,
                        description = description,
                        changeDescription = true,
                    )
                    "Renamed."
                }
            }
        )
    }

    if (addingSection) {
        SingleFieldDialog(
            heading = "New section",
            blurb = "Sections are how a long questionnaire is walked through in a room, one at a time. " +
                "Leave the code blank and one is derived from the title.",
            label = "Section title",
            initial = "",
            busy = busy,
            onDismiss = { addingSection = false },
            onSave = { title ->
                addingSection = false
                mutate("The section could not be added.") {
                    repository.addCustomSection(questionnaireId, title)
                    "Section added."
                }
            }
        )
    }

    renamingSection?.let { section ->
        SingleFieldDialog(
            heading = "Rename section",
            // Worth saying out loud, because it is the exception to everything else on this screen.
            blurb = "A section heading can be changed even after its questions have been answered — " +
                "a heading is not what an answer answers.",
            label = "Section title",
            initial = section.title,
            busy = busy,
            onDismiss = { renamingSection = null },
            onSave = { title ->
                renamingSection = null
                mutate("The section could not be renamed.") {
                    repository.updateCustomSection(questionnaireId, section.id, title = title)
                    "Section renamed."
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
                    repository.addCustomQuestion(
                        id = questionnaireId,
                        sectionId = section.id,
                        prompt = prompt,
                        helpText = help,
                        isRequired = required,
                    )
                    "Question added."
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
                    val result = repository.updateCustomQuestion(
                        id = questionnaireId,
                        questionId = question.id,
                        // Sent ONLY when the wording actually changed. Sending an unchanged prompt on
                        // an answered question would be indistinguishable from a rewording to the
                        // guard, so ticking "required" alone would supersede the question and split
                        // its answers across two rows saying the same thing.
                        prompt = prompt.takeIf { it.trim() != question.prompt.trim() },
                        helpText = help,
                        changeHelpText = true,
                        isRequired = required.takeIf { it != question.isRequired },
                    )
                    // The server's own sentence, verbatim, whenever it did something other than the
                    // plain edit. See CustomQuestionEditResultDto.
                    result.detail ?: "Question updated."
                }
            }
        )
    }

    removingQuestion?.let { question ->
        val retires = question.hasAnswers
        /*
         * A sitting holds an answer ROW for this question whose text is blank.
         *
         * VERIFIED AGAINST THE RUNNING API, and it is the one case where the two halves of the rule
         * disagree. `guard_question_edit` counts only answers with text, so a blank row leaves
         * `hasAnswers` false and the server takes the real-delete path — while the database's
         * ON DELETE RESTRICT counts the ROW, and refuses. The DELETE comes back 500
         * (ForeignKeyViolationError), which reaches the designer as "something went wrong on the
         * server" about a question they can see perfectly well.
         *
         * Blank rows are ordinary: clearing an answer that was recorded earlier leaves one. So the
         * warning states the fact this client can actually see — a sitting points at this question —
         * rather than promising an outcome. Nothing here works around it; the fix belongs on the
         * server, which owns both halves of the rule.
         */
        val blankAnswerRow = !retires && loaded != null &&
            loaded.entries.any { entry -> entry.answers.any { it.questionId == question.id } }
        AlertDialog(
            onDismissRequest = { if (!busy) removingQuestion = null },
            title = { Text(if (retires) "Retire this question?" else "Remove this question?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(question.prompt, color = MaterialTheme.field.body, fontSize = 13.sp)
                    Text(
                        if (retires) {
                            "This question has answers recorded against it, so it cannot be deleted. " +
                                "It will be retired instead: it stops being asked, and its answers stay " +
                                "in the record and in the download."
                        } else {
                            "Nobody has answered this question, so it will be deleted outright."
                        },
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    if (blankAnswerRow) {
                        Text(
                            "One of the sittings still holds a blank answer against this question — " +
                                "somebody opened it and cleared it. The server may refuse to delete it " +
                                "for that reason. If it does, nothing has been lost: leave the question " +
                                "in place, or remove the sitting's blank answer first.",
                            color = MaterialTheme.field.warning,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        removingQuestion = null
                        mutate("The question could not be removed.") {
                            val result = repository.removeCustomQuestion(questionnaireId, question.id)
                            result.detail ?: "Question deleted."
                        }
                    }
                ) { Text(if (retires) "Retire" else "Delete") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { removingQuestion = null }) { Text("Cancel") }
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
                scope.launch {
                    runCatching {
                        repository.startCustomEntry(
                            id = questionnaireId,
                            respondentName = respondent,
                            notes = notes,
                        )
                    }.onSuccess { entry ->
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
    workshops: List<SelectOption>,
    onRename: () -> Unit,
    onAttach: (String) -> Unit,
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
                fontSize = 21.sp
            )
            form.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.field.body, fontSize = 12.sp)
            }
            Text(
                "${activeQuestionCount(form)} question(s) in " +
                    "${form.sections.count { it.isActive }} section(s) · version ${form.version}",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
            form.sourceFilename?.takeIf { it.isNotBlank() }?.let {
                Text("From $it", color = MaterialTheme.field.muted, fontSize = 11.sp)
            }
            if (!form.isActive) {
                Text(
                    "Deactivated. It is out of every list and dropdown and no new sittings can be " +
                        "started against it. Nothing has been deleted.",
                    color = MaterialTheme.field.warning,
                    fontSize = 12.sp
                )
            }

            HorizontalDivider(color = MaterialTheme.field.hairline)

            if (mayEdit) {
                SearchableSelectField(
                    label = "Attached to a design workshop",
                    options = workshops,
                    selectedValue = form.designWorkshopId.orEmpty(),
                    placeholder = "Not attached",
                    includeNone = true,
                    enabled = !busy,
                    onSelect = onAttach
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onRename, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Rename")
                    }
                    OutlinedButton(
                        onClick = { onSetActive(!form.isActive) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (form.isActive) "Deactivate" else "Reactivate") }
                }
            } else {
                Text(
                    form.designWorkshopId?.takeIf { it.isNotBlank() }
                        ?.let { id -> workshops.firstOrNull { it.value == id }?.label ?: "A design workshop" }
                        ?.let { "Attached to $it" }
                        ?: "Not attached to a design workshop",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
                // Said rather than left as an absence of buttons. The person this form was handed to
                // can still answer it, and a screen that merely looked read-only would send them off
                // to make a second copy of somebody else's questionnaire.
                Text(
                    "Only the designer who created this questionnaire, or an admin, can change its " +
                        "questions. You can still record answers against it.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
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
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            // THE DISTINCTION, ONCE, ABOVE BOTH BUTTONS. Each row states its own contents as well —
            // see ArtefactNotice — but a designer scanning two adjacent buttons with a colleague
            // waiting needs the difference before they read either label.
            Text(
                "Two different files. One carries your questions; the other carries every person you " +
                    "interviewed. Both are named after this questionnaire and both land in Downloads, " +
                    "so read what each one holds before you send it to anybody.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
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
                    "This one belongs to the designer who created the questionnaire, a designer on " +
                        "its design workshop, or an admin. If the server refuses it, it will say so " +
                        "and point you at the question set — which is yours to take.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            if (mayEdit) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                WorkbookUploadRow(
                    label = "Upload an edited workbook",
                    blurb = "Replaces THIS questionnaire's questions with the ones in the file — " +
                        "download it above, edit it on a computer, and bring it back. A question " +
                        "that already has answers is never overwritten: its old wording is kept with " +
                        "its answers and your new wording is added beside it, and you are told which " +
                        "ones. A file downloaded from a DIFFERENT questionnaire is refused rather " +
                        "than applied.",
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
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "One sitting is one filled-in copy of this questionnaire. Answers typed here and " +
                    "answers that arrived already filled in on the spreadsheet are the same thing.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
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
                !form.isActive -> EmptyNote("This questionnaire is deactivated, so no new answers can be recorded against it.")
                total == 0 -> EmptyNote("There is nothing to answer yet — add some questions first.")
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(entrySubtitle(entry, total), color = MaterialTheme.field.muted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onOpen) { Text("Answer") }
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
                        fontSize = 11.sp
                    )
                    Text(
                        answer.answerText.orEmpty().ifBlank { "—" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    )
                    answer.notes?.takeIf { it.isNotBlank() }?.let {
                        Text("Note: $it", color = MaterialTheme.field.muted, fontSize = 11.sp)
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
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (form.sections.isEmpty()) {
                EmptyNote(
                    "No sections yet. Build the form in the .xlsx pro-forma on a computer and upload " +
                        "it there, or add sections and questions here one at a time."
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
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!section.isActive) {
                                Text(
                                    "Retired section — kept because its questions carry answers.",
                                    color = MaterialTheme.field.warning,
                                    fontSize = 11.sp
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
                            busy = busy,
                            onEdit = { onEditQuestion(question) },
                            onRemove = { onRemoveQuestion(question) },
                        )
                    }

                    if (mayEdit) {
                        TextButton(onClick = { onAddQuestion(section) }, enabled = !busy) {
                            Text("Add a question to this section")
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
    busy: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            question.prompt + if (question.isRequired) " *" else "",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp
        )
        question.helpText?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp)
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
                    "This wording was replaced after it had been answered. It is no longer asked, and " +
                        "the answers given under it are still in the record."
                } else {
                    "Retired. It is no longer asked, and its answers are still in the record."
                },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
        // A retired question is offered NO controls at all. Editing one is refused by the server (and
        // the re-upload path says so in as many words), and "deleting" something already gone would
        // only be a way to hit the ON DELETE RESTRICT on its answers.
        if (mayEdit && question.isActive) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit, enabled = !busy) { Text("Edit", fontSize = 12.sp) }
                TextButton(onClick = onRemove, enabled = !busy) {
                    Text(if (question.hasAnswers) "Retire" else "Delete", fontSize = 12.sp)
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
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
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
                Text(blurb, color = MaterialTheme.field.muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
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
                        "This question already has answers recorded against it. Changing the WORDING " +
                            "will not overwrite them: the original wording keeps its answers and your " +
                            "new wording is added as a new question in the same place. The hint and " +
                            "the required tick can be changed freely.",
                        color = MaterialTheme.field.warning,
                        fontSize = 12.sp
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Question *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (hasAnswers && reworded) {
                    Text(
                        "Saving this will add a new question and retire the current wording.",
                        color = MaterialTheme.field.warning,
                        fontSize = 11.sp
                    )
                }
                OutlinedTextField(
                    value = help,
                    onValueChange = { help = it },
                    label = { Text("Hint shown under the question") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = required, onCheckedChange = { required = it })
                    Text("Required", color = MaterialTheme.field.body, fontSize = 13.sp)
                }
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
                    "Naming the person answering is what tells two sittings apart — in this list and " +
                        "in the .xlsx download, where each sitting becomes its own answer column.",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = respondent,
                    onValueChange = { respondent = it },
                    label = { Text("Who is answering") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
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
