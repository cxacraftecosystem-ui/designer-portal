package com.designprototype.workshop.ui.designworkshop

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
                "Sent back. The report now reads Needs revision and your suggestion is on the record."
            report.sent > 0 -> "Filed. Your suggestion is on the record against this submission round."
            report.held > 0 -> "Not filed — the reason is under your note below. Nothing has been lost."
            else -> "Kept on this phone. It will be sent when this phone is back online, after the " +
                "report is checked again."
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
                note = ""
                stageKey = ""
                applied(report, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "Your note could not be kept on this phone, so it is still in the box. " +
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
                                    outcome = "Your note is back in the box, as you wrote it."
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
                                        problem = e.message ?: "That did not go through. Your note is kept."
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
                    "Suggestions cannot be filed on this workshop from this account.",
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
                            Text("Name what is wrong and what it should say. This goes to the designers as written.")
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { file(DwInspectionNoteKind.SUGGESTION) },
                            enabled = !busy && note.isNotBlank()
                        ) { Text("File a suggestion") }
                        Button(
                            onClick = { confirmSendBack = true },
                            enabled = !busy && note.isNotBlank()
                        ) { Text("Send the report back") }
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        "Filing a suggestion leaves the report where it is. Sending it back moves it to " +
                            "Needs revision, which is what puts it on its designers' desks. Neither can be " +
                            "edited or withdrawn once it is on the record. Without signal your note is kept " +
                            "on this phone and sent when you are back online.",
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
                    "The report moves to Needs revision and its designers are the ones who act on it " +
                        "next. Your suggestion is recorded with your name against this submission round, " +
                        "and it stays on the record afterwards. They hand it back in by correcting the " +
                        "stages. Suggestions cannot be edited or withdrawn afterwards."
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
                    "It has not reached the record, so discarding it removes the only copy, which is " +
                        "on this phone. If you still want to say it, put it back in the box instead."
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mayRefile) {
                    OutlinedButton(onClick = onRefile, enabled = !busy) { Text("File against this round") }
                }
                OutlinedButton(onClick = onBackToBox, enabled = !busy) { Text("Back to the box") }
            }
        }
        TextButton(onClick = onDiscard, enabled = !busy) { Text("Discard") }
    }
}
