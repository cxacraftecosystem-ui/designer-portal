package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.destructiveAction
import com.designprototype.workshop.data.userMessage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_NOT_UNDER_REVIEW_SENTENCE
import com.designprototype.workshop.data.DwInspectionDetailDto
import com.designprototype.workshop.data.DwInspectionFeedbackDto
import com.designprototype.workshop.data.DwInspectionNoteKind
import com.designprototype.workshop.data.DwInspectionSyncReport
import com.designprototype.workshop.data.DwQueuedInspectionNote
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dwFeedbackAuthor
import com.designprototype.workshop.data.dwFeedbackRounds
import com.designprototype.workshop.data.dwInspectionNoteProblem
import com.designprototype.workshop.data.dwIsUnderReview
import com.designprototype.workshop.data.dwQueuedNoteSentence
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * THE FEEDBACK BOX ON THE HANDSET — the register, the box, and the notes waiting on this phone.
 *
 * The web's `FeedbackPanel` in `design-workshop-inspections/[id]/page.tsx`, with one difference that
 * is the reason this panel exists on a phone: a note is KEPT ON THE PHONE FIRST and then sent. With a
 * connection that is the same as the web — it is on the record a moment later. Without one it waits,
 * and when it goes it is checked against the report as it then stands (`dwInspectionNoteCheck`). A
 * note that is held back or refused stays here, with the reason beside it, until its author re-files
 * it, puts it back in the box, or discards it.
 *
 * TWO BUTTONS, BECAUSE THEY ARE TWO ACTS — the server's rule and the web's. The second moves the
 * report onto its designers' desks, so it is behind a confirmation that says so.
 */
@Composable
internal fun InspectionFeedbackPanel(
    repository: WorkshopRepository,
    record: DwInspectionDetailDto,
    stages: List<StageDto>,
    /** A newer read of this workshop, from a note that just went or a check that just ran. */
    onRecord: (DwInspectionDetailDto) -> Unit,
    /** Bumped by the screen when it has just read the workshop, so the queued list is re-read. */
    refreshKey: Int,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var note by rememberSaveable(record.id) { mutableStateOf("") }
    var stageKey by rememberSaveable(record.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var confirmSendBack by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<DwQueuedInspectionNote?>(null) }
    var queued by remember(record.id) { mutableStateOf<List<DwQueuedInspectionNote>>(emptyList()) }
    var reread by remember { mutableIntStateOf(0) }

    // THE NOTE BEING TYPED, ON DISK (2026-10-16). `rememberSaveable` above survives Android recycling
    // the process in the background but not a swipe-away, a force-stop or an update; this survives
    // all of them. Cleared the moment the note is handed to `fileInspectionNote`, which owns it after.
    val noteDraft = com.designprototype.workshop.ui.rememberFormDraft(
        key = "inspection-note:${record.id}",
        snapshot = { mapOf("note" to note, "stageKey" to stageKey) },
        restore = { v ->
            note = v["note"] ?: note
            stageKey = v["stageKey"] ?: stageKey
        },
    )

    LaunchedEffect(record.id, refreshKey, reread) {
        queued = runCatching { repository.queuedInspectionNotes(appContext, record.id) }.getOrDefault(emptyList())
    }

    val stageTitles = remember(stages) { stages.associate { it.key to it.title } }
    val rows = record.inspectionFeedback
    val rounds = remember(rows) { dwFeedbackRounds(rows) }
    val underReview = dwIsUnderReview(record.status)

    fun applied(report: DwInspectionSyncReport, kind: DwInspectionNoteKind) {
        report.refreshed[record.id]?.let(onRecord)
        outcome = when {
            report.sent > 0 && kind == DwInspectionNoteKind.SEND_BACK ->
                "Sent back — now Needs revision."
            report.sent > 0 -> "Suggestion filed."
            report.held > 0 -> "Not filed — see the reason below your note."
            else -> "Saved on this phone. Sends when you're online."
        }
        reread++
    }

    fun file(kind: DwInspectionNoteKind) {
        problem = dwInspectionNoteProblem(note)
        outcome = null
        if (problem != null) return
        busy = true
        scope.launch {
            try {
                val report = repository.fileInspectionNote(
                    context = appContext,
                    read = record,
                    kind = kind,
                    note = note,
                    stageKey = stageKey
                )
                noteDraft.discard()
                note = ""
                stageKey = ""
                applied(report, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "Couldn't save your note here; it's still in the box. " +
                    (e.message?.takeIf { it.isNotBlank() } ?: "Try again.")
            } finally {
                busy = false
            }
        }
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Correction suggestions",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${rows.size} on record" + if (record.inspectionFeedbackTruncated) " (older ones not shown)" else "",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
            }

            rounds.forEach { group ->
                Text(
                    "Round ${group.round}",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                group.rows.forEach { row -> key(row.id) { RegisterRow(row, stageTitles) } }
            }

            // ── What is waiting on this phone ───────────────────────────────────────────────────
            if (queued.isNotEmpty()) {
                Text(
                    "On this phone, not yet on the record",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                queued.forEach { item ->
                    key(item.id) {
                        QueuedNoteCard(
                            item = item,
                            stageTitles = stageTitles,
                            mayRefile = underReview && record.mayRecordFeedback,
                            busy = busy,
                            onBackToBox = {
                                scope.launch {
                                    note = item.note
                                    stageKey = item.stageKey.orEmpty()
                                    runCatching { repository.discardInspectionNote(appContext, item.id) }
                                    outcome = "Your note is back in the box."
                                    reread++
                                }
                            },
                            onRefile = {
                                busy = true
                                scope.launch {
                                    try {
                                        applied(repository.refileInspectionNote(appContext, item.id, record), item.kind)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        problem = e.userMessage("That did not go through. Your note is kept.")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            onDiscard = { confirmDiscard = item }
                        )
                    }
                }
            }

            // ── The box ─────────────────────────────────────────────────────────────────────────
            when {
                !record.mayRecordFeedback -> Text(
                    "You can't file suggestions on this workshop.",
                    color = MaterialTheme.field.body,
                    fontSize = 13.sp
                )

                !underReview -> InspectionNotice(DW_NOT_UNDER_REVIEW_SENTENCE, warning = true)

                else -> {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it; problem = null },
                        label = { Text("What should be corrected?") },
                        placeholder = {
                            Text("What's wrong and what it should say.")
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
                    )
                    SearchableSelectField(
                        label = "Which stage is it about?",
                        options = listOf(SelectOption("", "The report as a whole")) +
                            stages.sortedBy { it.number }.map { SelectOption(it.key, "${it.number}. ${it.title}") },
                        selectedValue = stageKey,
                        includeNone = false,
                        enabled = !busy,
                        onSelect = { stageKey = it }
                    )
                    // Short labels so the pair stays on one line in two equal halves at 360dp.
                    ActionRow {
                        OutlinedButton(
                            onClick = { file(DwInspectionNoteKind.SUGGESTION) },
                            enabled = !busy && note.isNotBlank()
                        ) { Text("Suggest") }
                        Button(
                            onClick = { confirmSendBack = true },
                            enabled = !busy && note.isNotBlank()
                        ) { Text("Send back") }
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).align(Alignment.CenterHorizontally),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        "Suggest keeps the report as is; Send back moves it to Needs revision. Neither can be undone.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
            }

            problem?.let { InspectionNotice(it, warning = false) }
            outcome?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onSuccessContainer,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.successContainer, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                )
            }
        }
    }

    if (confirmSendBack) {
        AlertDialog(
            onDismissRequest = { confirmSendBack = false },
            title = { Text("Send this report back to its designers?") },
            text = {
                Text(
                    "The report goes back to its designers as Needs revision, with your suggestion recorded under your name. This can't be undone."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmSendBack = false
                    file(DwInspectionNoteKind.SEND_BACK)
                }) { Text("Send it back") }
            },
            dismissButton = { TextButton(onClick = { confirmSendBack = false }) { Text("Cancel") } }
        )
    }

    confirmDiscard?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard this note?") },
            text = {
                Text(
                    "This is the only copy. Put it back in the box instead?"
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmDiscard = null
                    scope.launch {
                        runCatching { repository.discardInspectionNote(appContext, doomed.id) }
                        outcome = "Discarded."
                        reread++
                    }
                }) { Text("Discard it") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Keep it") } }
        )
    }
}

/** One suggestion on the record. Never a guess at a name, never a raw stage key where a title exists. */
@Composable
private fun RegisterRow(row: DwInspectionFeedbackDto, stageTitles: Map<String, String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(row.note, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        Text(
            listOfNotNull(
                dwFeedbackAuthor(row),
                row.stageKey?.let { "about ${stageTitles[it] ?: it}" } ?: "about the report as a whole",
                (row.recordedAt ?: row.createdAt)?.take(10),
                if (row.sentBack) "sent the report back" else null,
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
    }
}

/** A note written on this phone: waiting to go, or held with the reason beside it. */
@Composable
private fun QueuedNoteCard(
    item: DwQueuedInspectionNote,
    stageTitles: Map<String, String>,
    mayRefile: Boolean,
    busy: Boolean,
    onBackToBox: () -> Unit,
    onRefile: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(item.note, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        Text(
            listOfNotNull(
                if (item.kind == DwInspectionNoteKind.SEND_BACK) "Send-back" else "Suggestion",
                item.stageKey?.let { "about ${stageTitles[it] ?: it}" } ?: "about the report as a whole",
                "written ${item.recordedAt.take(10)}",
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
        val held = item.held
        if (held == null) {
            Text(dwQueuedNoteSentence(item), color = MaterialTheme.field.muted, fontSize = 11.sp)
        } else {
            InspectionNotice(held, warning = true)
            ActionRow {
                if (mayRefile) {
                    OutlinedButton(onClick = onRefile, enabled = !busy) { Text("File against this round") }
                }
                OutlinedButton(onClick = onBackToBox, enabled = !busy) { Text("Back to the box") }
            }
        }
        ActionRow {
            TextButton(modifier = Modifier.destructiveAction(), onClick = onDiscard, enabled = !busy) { Text("Discard") }
        }
    }
}
