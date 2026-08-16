package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every questionnaire this designer authored, newest first.
 *
 * THE LIST IS NOT MERGED WITH ANYTHING LOCAL, unlike the design-workshop list next door, and the
 * asymmetry is deliberate. A design workshop is a fortnight of capture in a courtyard, so it exists
 * on the phone first and syncs later. A questionnaire is a form somebody typed into a spreadsheet on
 * a laptop and uploaded; there is nothing on this device that could stand in for it, and pretending
 * otherwise would mean showing a stale question set to somebody about to record answers against it.
 * When there is no signal this screen says so instead of inventing a list.
 *
 * There is deliberately no DELETE anywhere in this feature. A questionnaire with answers against it
 * is somebody's fieldwork; the API offers deactivation instead, which takes it out of every list and
 * keeps the answers — so the "Show deactivated" switch below is the only way back to one, and hiding
 * that switch would make deactivation indistinguishable from destruction.
 */

/** How long the search box waits before it asks the server. Paces an ordinary typing speed. */
private const val SEARCH_DEBOUNCE_MS = 350L

@Composable
fun QuestionnaireListScreen(
    repository: WorkshopRepository,
    onOpen: (questionnaireId: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var rows by remember { mutableStateOf<List<CustomQuestionnaireSummaryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var showDeactivated by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    /** Rows shown, of the total the server reports — set only when the walk stopped short. */
    var partial by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // ── The .xlsx interchange ────────────────────────────────────────────────────────────────────
    // The application context, so a download that is still copying into MediaStore when the designer
    // navigates away cannot hold an Activity alive behind it.
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
        // Cleared BEFORE the request. The seconds an upload takes are exactly when the PREVIOUS
        // upload's report is still on screen, and a designer reading "38 questions added" over a
        // spinner for a different file is being told about work that is not the work they are
        // waiting on.
        uploadReport = null
        scope.launch {
            runCatching { repository.uploadQuestionnaireWorkbook(appContext, uri) }
                .onSuccess { result ->
                    uploadReport = result.report
                    reload++
                    onMessage("“${result.questionnaire.title}” was created from that workbook.")
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

    LaunchedEffect(reload, search, showDeactivated) {
        // Debounced only for TYPING. Keying on `search` cancels and restarts the effect per keystroke,
        // so without this a five-letter craft name is five list requests and four replies nobody will
        // read. `reload` is a deliberate act (a create) and must not wait behind a delay.
        if (search.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        loading = true
        runCatching {
            // EVERY page the server says this account may see, not the first one.
            //
            // This asked for one page of 50 and dropped `total`. That is a correctness bug rather than
            // a long-list limitation, because `GET /questionnaires` widens for a grant-holder — a
            // non-admin sees their own forms PLUS the forms attached to a design workshop an admin put
            // them on — while the rows stay ordered `createdAt desc`. The granted form was uploaded by
            // somebody else BEFORE the grant, so it is older than everything the grantee has built and
            // sorts LAST: the one row a grant exists to reveal is the one row a single page cannot
            // reach. See data/QuestionnaireListing.kt for the live reading this was written from.
            repository.visibleQuestionnaires(
                search = search.takeIf { it.isNotBlank() },
                activeOnly = !showDeactivated,
            )
        }.onSuccess {
            rows = it.items
            offline = false
            // Advisory, never blocking. Silently showing a prefix is the defect itself, and the search
            // box above narrows on the SERVER, so it reaches the rows this walk stopped short of.
            partial = if (it.truncated) it.items.size to it.total else null
        }.onFailure { error ->
            if (error !is CancellationException) {
                offline = true
                onError(error.apiErrorMessage("The questionnaires could not be listed."))
                // Cleared, not left standing. "Showing 100 of 340" is a claim about a list that was
                // just read; kept across a failed reload it would sit under the offline notice quoting
                // a count from a request that never landed — two banners contradicting each other
                // about the same screen. A CANCELLED walk is left alone deliberately: the run that
                // replaces it sets both a moment later, and blanking here would flicker the banner off
                // and on again with every keystroke.
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
            fontSize = 22.sp
        )
        // NOT "your own" any more, and the correction matters. Since an admin can put a second
        // designer on a design workshop, this list carries the forms attached to those workshops too —
        // uploaded by a colleague, under a name the reader did not choose. Calling them all "yours"
        // makes a co-designer distrust the row that is most likely to be the one they came here for.
        // Each card already names its owner and the workshop it hangs off, so the line only has to
        // stop contradicting them.
        // NOT "built on a computer, answered here" any more. That sentence was true when this client
        // bound none of the .xlsx endpoints; leaving it standing over a "Upload a workbook" button
        // would be the screen contradicting itself about what the handset can do.
        Text(
            "Questionnaires you built, and the ones attached to a design workshop you work on. " +
                "Open one to record a sitting, section by section — or download it as a spreadsheet, " +
                "send its questions to another designer, and upload one you were sent.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New")
            }
        }

        // ── Building a questionnaire from a spreadsheet, on the handset ─────────────────────────
        //
        // These two controls were absent by decision until 2026-08-16 and the decision was reversed
        // by the person the app is for; the argument, including the part of it that was wrong, is in
        // `data/WorkshopRepositoryApi.kt` where the endpoints are bound.
        //
        // WHAT IS STILL TRUE IS SAID RATHER THAN ENFORCED BY AN ABSENCE. A phone is a poor place to
        // author forty questions, so the pro-forma's line says what to do with the file — send it to
        // a computer — instead of implying the typing happens here.
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "The spreadsheet",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                ArtefactDownloadRow(
                    label = "Download the pro-forma",
                    artefact = DwQuestionnaireArtefact.PRO_FORMA,
                    busy = interchangeBusy,
                    working = downloadingProForma,
                    savedTo = proFormaSavedTo,
                    onDownload = {
                        if (interchangeBusy) return@ArtefactDownloadRow
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
                )
                Text(
                    "Send it to a computer to type your questions into — a spreadsheet with forty " +
                        "rows is not a thing a phone is good at. Then bring the filled-in file back " +
                        "and upload it here, or upload it from the website.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )

                HorizontalDivider(color = MaterialTheme.field.hairline)

                WorkbookUploadRow(
                    label = "Upload a workbook",
                    // NAMED BEFORE A FILE IS CHOSEN, which is the point. Three different files end up
                    // in a designer's Downloads folder with .xlsx on the end, one of them behaves
                    // differently from the other two, and a designer finds that out AFTER the upload
                    // unless the door says so first.
                    blurb = "Creates a NEW questionnaire. This door takes a filled-in pro-forma, or a " +
                        "question set a colleague sent you. If the file came out of this platform — " +
                        "it has a Questionnaire ID or Question IDs in it — its QUESTIONS are imported " +
                        "and its ANSWERS are not: those answers already exist here under the names of " +
                        "the people who recorded them. You are told either way.",
                    busy = interchangeBusy,
                    working = uploading,
                    onPick = pickWorkbook,
                )
            }
        }

        uploadReport?.let { report ->
            UploadReportPanel(report = report, onDismiss = { uploadReport = null })
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show deactivated", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                Text(
                    "Deactivation is what this app has instead of deleting. The answers are still there.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            }
            Switch(checked = showDeactivated, onCheckedChange = { showDeactivated = it })
        }

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            // Said plainly rather than shown as an empty list. "You have no questionnaires" and "this
            // phone cannot reach the server" look identical on screen and mean opposite things — one
            // of them sends a designer off to build a form they have already built.
            offline -> Text(
                "The server could not be reached, so this list is not the whole story. " +
                    "A questionnaire is only ever on the server; nothing is stored on this phone.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp
            )

            rows.isEmpty() -> EmptyNote(
                if (search.isNotBlank()) {
                    "Nothing matches “$search”."
                } else {
                    "No questionnaires yet. Download the pro-forma above, type your questions into it " +
                        "on a computer and upload the filled-in file — here or on the website — or " +
                        "tap New to start an empty one and add questions by hand."
                }
            )

            else -> rows.forEach { row ->
                QuestionnaireRow(row = row, onOpen = { onOpen(row.id) })
            }
        }

        // Said rather than left for the designer to infer from a questionnaire that is not there —
        // which is the failure this paging change exists to end. Below the rows, because it describes
        // what is missing from them.
        partial?.let { (shown, total) ->
            Text(
                "Showing $shown of $total questionnaires. Search by title or description to reach the rest.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp
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
                onMessage("Questionnaire started. Add sections and questions, or upload a filled-in pro-forma on a computer.")
                onOpen(id)
            },
            onError = onError,
        )
    }
}

@Composable
private fun QuestionnaireRow(row: CustomQuestionnaireSummaryDto, onOpen: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                row.title.ifBlank { "Untitled questionnaire" },
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            row.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.field.body, fontSize = 12.sp, maxLines = 3)
            }
            Text(
                row.designWorkshopTitle?.takeIf { it.isNotBlank() }
                    ?.let { "Attached to $it" }
                    ?: "Not attached to a design workshop",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
            val trail = listOfNotNull(
                row.ownerName?.takeIf { it.isNotBlank() },
                row.sourceFilename?.takeIf { it.isNotBlank() },
                row.updatedAt?.take(10)?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (trail.isNotBlank()) {
                Text(trail, color = MaterialTheme.field.muted, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!row.isActive) {
                    QChip(
                        "Deactivated",
                        MaterialTheme.field.warningContainer,
                        MaterialTheme.field.onWarningContainer
                    )
                }
                // The version moves on every supersede and every retire, which is the only cheap way a
                // designer can tell that the form changed under a sitting they are part-way through.
                if (row.version > 1) {
                    QChip(
                        "version ${row.version}",
                        MaterialTheme.field.surface200,
                        MaterialTheme.field.muted
                    )
                }
            }
        }
    }
}

/**
 * Start an empty questionnaire.
 *
 * ONLY THE TITLE IS REQUIRED, matching `QuestionnaireCreate`. The sections and questions are what the
 * pro-forma is for, and asking for them here would put a spreadsheet-shaped form on a phone screen.
 */
@Composable
private fun CreateQuestionnaireDialog(
    repository: WorkshopRepository,
    onDismiss: () -> Unit,
    onCreated: (questionnaireId: String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var workshopId by remember { mutableStateOf("") }
    var workshops by remember { mutableStateOf<List<SelectOption>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { workshops = designWorkshopOptions(repository) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New questionnaire") },
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
                SearchableSelectField(
                    label = "Attach to a design workshop",
                    options = workshops,
                    selectedValue = workshopId,
                    placeholder = "Not attached",
                    includeNone = true,
                    onSelect = { workshopId = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            repository.createCustomQuestionnaire(
                                title = title,
                                description = description,
                                designWorkshopId = workshopId.takeIf { it.isNotBlank() },
                            )
                        }.onSuccess { onCreated(it.id) }
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
