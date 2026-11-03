package com.designprototype.workshop.ui

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import com.designprototype.workshop.data.userMessage

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.ArtisanIdentity
import com.designprototype.workshop.data.DW_DRAFT_OTHER_ACCOUNT_ROW
import com.designprototype.workshop.data.REFERENCE_FIELD_NOUNS
import com.designprototype.workshop.data.OutboxFailureRow
import com.designprototype.workshop.data.OutboxQueuedFile
import com.designprototype.workshop.data.OutboxWaitingRow
import com.designprototype.workshop.data.outboxWaitingRows
import com.designprototype.workshop.data.RepickChoices
import com.designprototype.workshop.data.StaleCorrectionReview
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.outboxDiscardConfirmation
import com.designprototype.workshop.data.outboxFailureRows
import com.designprototype.workshop.data.outboxRetryAllMessage
import com.designprototype.workshop.data.outboxRetryMessage
import com.designprototype.workshop.data.outboxSendWithoutConflictsLabel
import com.designprototype.workshop.data.repickEmptyLine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OfflineOutboxTray(
    repository: WorkshopRepository,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    online: Boolean = true,
    serverReachable: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    var rows by remember { mutableStateOf<List<OutboxFailureRow>>(emptyList()) }
    var waiting by remember { mutableStateOf<List<OutboxWaitingRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf<OutboxFailureRow?>(null) }
    var repicking by remember { mutableStateOf<OutboxFailureRow?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var reviewing by remember { mutableStateOf<OutboxFailureRow?>(null) }
    var retyping by remember { mutableStateOf<OutboxFailureRow?>(null) }

    LaunchedEffect(reload) {
        loading = true
        rows = outboxFailureRows(
            runCatching { repository.outboxFailures(appContext) }.getOrDefault(emptyList()),
            repository.cachedUser()?.id,
        )
        waiting = outboxWaitingRows(
            runCatching { repository.outboxEntries(appContext) }.getOrDefault(emptyList()),
            repository.cachedUser()?.id,
            online = online,
            serverReachable = serverReachable,
        )
        loading = false
    }

    fun act(block: suspend () -> String?) {
        if (busy) return
        busy = true
        note = null
        scope.launch {
            runCatching { block() }
                .onSuccess { message ->
                    note = message
                    reload++
                    onChanged()
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        note = error.userMessage("That did not work. Try again.")
                    }
                }
            busy = false
        }
    }

    reviewing?.let { row ->
        StaleCorrectionDialog(
            repository = repository,
            row = row,
            busy = busy,
            onDismiss = { reviewing = null },
            onSendMine = { currentUpdatedAt ->
                reviewing = null
                act {
                    repository.keepMyCorrection(appContext, row.entryId, currentUpdatedAt)
                    "Your changes to “${row.label}” will be sent over the newer version (changed fields only)."
                }
            },
            onKeepTheirs = {
                reviewing = null
                act {
                    repository.keepTheirVersion(appContext, row.entryId)
                    if (row.mediaCount > 0) {
                        "Kept the server version. Your ${filesPhrase(row.mediaCount)} from “${row.label}” " +
                            "will still be added."
                    } else {
                        "Kept the server version. Your copy of “${row.label}” was deleted."
                    }
                }
            },
        )
    }

    confirmDiscard?.let { row ->
        val isConflict = row.conflict
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = {
                Text(
                    if (isConflict) "Throw away this copy?" else "Throw this away?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    outboxDiscardConfirmation(
                        label = row.label,
                        files = row.mediaCount,
                        isConflict = isConflict,
                        savedOnServer = row.savedOnServer,
                        isDangling = row.danglingNouns.isNotEmpty(),
                    ),
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val doomed = row
                        confirmDiscard = null
                        act {
                            repository.discardOutboxEntry(appContext, doomed.entryId)
                            "“${doomed.label}” deleted from this phone."
                        }
                    }
                ) { Text("Delete it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = null }) { Text("Keep it", style = MaterialTheme.typography.labelLarge) }
            },
        )
    }

    retyping?.let { row ->
        RetypeAadhaarDialog(
            row = row,
            onDismiss = { retyping = null },
            onTyped = { number ->
                val target = row
                retyping = null
                act {
                    outboxRetryMessage(
                        label = target.label,
                        result = repository.retypeOutboxAadhaar(appContext, target.entryId, number),
                    )
                }
            },
        )
    }

    repicking?.let { row ->
        RepickDialog(
            repository = repository,
            row = row,
            onDismiss = { repicking = null },
            onChosen = { field, chosen ->
                val target = row
                repicking = null
                act {
                    outboxRetryMessage(
                        label = target.label,
                        result = repository.repickOutboxEntry(appContext, target.entryId, field, chosen),
                    )
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (waiting.isEmpty() && rows.isNotEmpty()) "Records this device could not send" else "Saved on this phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rows.isNotEmpty()) {
                    Text(
                        "These couldn't be saved and won't send by waiting. Nothing was deleted.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                }
                when {
                    loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Reading the queue…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }

                    rows.isEmpty() && waiting.isEmpty() -> Text(
                        "Nothing is waiting on you any more.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> rows.forEach { row ->
                        OutboxFailureCard(
                            row = row,
                            busy = busy,
                            onRepick = { repicking = row },
                            onRetype = { retyping = row },
                            onRetry = {
                                act {
                                    outboxRetryMessage(
                                        label = row.label,
                                        result = repository.retryOutboxEntry(appContext, row.entryId),
                                    )
                                }
                            },
                            onDiscard = { confirmDiscard = row },
                            onReview = { reviewing = row },
                            onSamePerson = { artisanId ->
                                act {
                                    outboxRetryMessage(
                                        label = row.label,
                                        result = repository.resolvePossibleDuplicate(appContext, row.entryId, artisanId),
                                    )
                                }
                            },
                            onDifferentPerson = {
                                act {
                                    outboxRetryMessage(
                                        label = row.label,
                                        result = repository.resolvePossibleDuplicate(appContext, row.entryId, null),
                                    )
                                }
                            },
                            onSendWithoutConflicts = {
                                act {
                                    outboxRetryMessage(
                                        label = row.label,
                                        result = repository.sendWithoutConflictingAnswers(appContext, row.entryId),
                                    )
                                }
                            },
                        )
                    }
                }
                if (!loading && waiting.isNotEmpty()) {
                    if (rows.isNotEmpty()) HorizontalDivider(color = MaterialTheme.field.hairline)
                    Text(
                        "Waiting to send",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    waiting.forEach { row ->
                        OutboxWaitingCard(
                            row = row,
                            busy = busy,
                            onSendNow = {
                                act {
                                    outboxRetryMessage(
                                        label = row.label,
                                        result = repository.retryOutboxEntry(appContext, row.entryId),
                                    )
                                }
                            },
                        )
                    }
                }
                note?.let {
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                    Text(it, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", style = MaterialTheme.typography.labelLarge) } },
        dismissButton = {
            if (rows.count { !it.otherAccount } > 1) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        act {
                            outboxRetryAllMessage(repository.retryAllOutboxFailures(appContext))
                        }
                    }
                ) { Text("Try all again", style = MaterialTheme.typography.labelLarge) }
            }
        },
    )
}

@Composable
private fun OutboxFailureCard(
    row: OutboxFailureRow,
    busy: Boolean,
    onRepick: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onReview: () -> Unit = {},
    onRetype: () -> Unit = {},
    onSamePerson: (String) -> Unit = {},
    onDifferentPerson: () -> Unit = {},
    onSendWithoutConflicts: () -> Unit = {},
) {
    val heldFor = row.possibleDuplicates.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(row.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                row.kind,
                if (row.mediaCount > 0) {
                    if (row.mediaCount == 1) "1 file with it" else "${row.mediaCount} files with it"
                } else {
                    null
                },
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (row.files.isNotEmpty()) OutboxQueuedFiles(row.files)
        if (row.danglingNouns.isNotEmpty()) {
            Text(
                "Links to a missing ${row.danglingNouns.joinToString(" or ")}",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 14.sp,
            )
        }
        if (row.stale) {
            Text(
                "CHANGED ON THE SERVER SINCE YOU EDITED IT",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (row.needsAadhaar) {
            Text(
                "NEEDS THE AADHAAR NUMBER AGAIN",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (heldFor != null) {
            Text(
                "MAY ALREADY BE RECORDED",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (row.conflict) {
            Text(
                "CLASHES WITH A RECORD THE OFFICE ALREADY HAS",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(row.reason, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
        if (row.otherAccount) {
            Text(
                DW_DRAFT_OTHER_ACCOUNT_ROW,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
        if (row.awaitingUpdate) {
            Text(
                "Needs an app update; it'll retry after updating. Don't delete it.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
        if (row.danglingNouns.isNotEmpty() && row.repickKeys.isEmpty()) {
            Text(
                "Fix the link in the original record and this will send.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
        if (!row.otherAccount && heldFor != null) {
            ActionRow {
                OutlinedButton(onClick = { onSamePerson(heldFor.id) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Same person", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = onDifferentPerson, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Different person", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (!row.otherAccount) {
            ActionRow {
                if (row.needsAadhaar) {
                    OutlinedButton(onClick = onRetype, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Type it again", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (row.stale) {
                    OutlinedButton(onClick = onReview, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Review changes", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (row.conflictingAnswers > 0) {
                    OutlinedButton(onClick = onSendWithoutConflicts, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(outboxSendWithoutConflictsLabel(row.conflictingAnswers), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (row.repickKeys.isNotEmpty()) {
                    OutlinedButton(onClick = onRepick, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Re-pick it", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (!row.needsAadhaar && heldFor == null) {
                    MediaActionButton(label = "Try again", icon = Icons.Filled.Refresh, onClick = onRetry, enabled = !busy)
                }
                OutlinedButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).destructiveAction()) {
                    Text(
                        if (row.conflict) "Throw away this copy" else "Throw away",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun RetypeAadhaarDialog(
    row: OutboxFailureRow,
    onDismiss: () -> Unit,
    onTyped: (String) -> Unit,
) {
    var typed by remember(row.entryId) { mutableStateOf("") }
    var error by remember(row.entryId) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type the Aadhaar number again", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    row.aadhaarLast4?.let { "“${row.label}” is safe. Type the number ending $it to send it." }
                        ?: "“${row.label}” is safe. Type the artisan's Aadhaar number to send it.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { input ->
                        typed = input.filter { it in '0'..'9' }.take(ArtisanIdentity.AADHAAR_LENGTH)
                        error = null
                    },
                    label = { Text("Aadhaar number") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = typed.length == ArtisanIdentity.AADHAAR_LENGTH,
                onClick = {
                    val problem = ArtisanIdentity.aadhaarError(typed)
                        ?: row.aadhaarLast4?.takeIf { !typed.endsWith(it) }
                            ?.let { "That number doesn't end in $it. Check the card." }
                    if (problem != null) error = problem else onTyped(typed)
                },
            ) { Text("Send it", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", style = MaterialTheme.typography.labelLarge) } },
    )
}

@Composable
private fun RepickDialog(
    repository: WorkshopRepository,
    row: OutboxFailureRow,
    onDismiss: () -> Unit,
    onChosen: (field: String, value: String?) -> Unit,
) {
    val field = row.repickKeys.first()
    val noun = REFERENCE_FIELD_NOUNS[field] ?: "workshop"

    var choices by remember(row.entryId, field) { mutableStateOf<RepickChoices?>(null) }
    var chosen by remember(row.entryId, field) { mutableStateOf("") }

    LaunchedEffect(row.entryId, field) {
        choices = runCatching { repository.repickOptions(field) }
            .getOrDefault(RepickChoices(emptyList(), listed = false))
    }

    val answered = choices
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Point it at a $noun that exists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "“${row.label}” is safe. Choose a $noun to file it under and it will send.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 19.sp,
                )
                when {
                    answered == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Reading the $noun list…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }

                    answered.options.isEmpty() -> Text(
                        repickEmptyLine(noun, answered.listed),
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )

                    else -> SearchableSelectField(
                        label = noun.replaceFirstChar { it.uppercase() },
                        options = answered.options.map { option ->
                            SelectOption(value = option.id, label = option.label, hint = option.hint)
                        },
                        selectedValue = chosen,
                        placeholder = "Choose one",
                        includeNone = true,
                        searchable = true,
                        onSelect = { chosen = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = answered != null && answered.options.isNotEmpty(),
                onClick = { onChosen(field, chosen.ifBlank { null }) },
            ) {
                Text(if (chosen.isBlank()) "File it under nothing and send" else "Send it", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", style = MaterialTheme.typography.labelLarge) } },
    )
}

@Composable
private fun StaleCorrectionDialog(
    repository: WorkshopRepository,
    row: OutboxFailureRow,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSendMine: (String?) -> Unit,
    onKeepTheirs: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    var review by remember(row.entryId) { mutableStateOf<StaleCorrectionReview?>(null) }
    var problem by remember(row.entryId) { mutableStateOf<String?>(null) }
    LaunchedEffect(row.entryId) {
        runCatching { repository.reviewStaleCorrection(appContext, row.entryId) }
            .onSuccess { review = it; if (it == null) problem = "This entry can no longer be compared." }
            .onFailure { error ->
                if (error is CancellationException) throw error
                problem = "Couldn't load the current version. Review again online — nothing changed."
            }
    }
    LaunchedEffect(review) {
        val loaded = review
        if (loaded != null && loaded.fields.isEmpty()) onSendMine(loaded.currentUpdatedAt)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review changes to “${row.label}”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                val loaded = review
                when {
                    problem != null -> Text(problem.orEmpty(), color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
                    loaded == null -> FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                    loaded.fields.isEmpty() -> Text(
                        "Your change is already on the record. Send mine to finish saving it.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> loaded.fields.forEach { field ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(field.key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("Yours: ${field.mine.ifBlank { "(empty)" }}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "On the server now: ${field.theirs.ifBlank { "(empty)" }}",
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (row.mediaCount > 0) {
                    Text(
                        "Your ${filesPhrase(row.mediaCount)} ${if (row.mediaCount == 1) "stays" else "stay"} queued " +
                            "whichever you choose, and ${if (row.mediaCount == 1) "is" else "are"} added to the record.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSendMine(review?.currentUpdatedAt) },
                enabled = !busy && review != null,
            ) { Text("Send mine", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onKeepTheirs, enabled = !busy) {
                    Text("Keep theirs", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = onDismiss) { Text("Not now", style = MaterialTheme.typography.labelLarge) }
            }
        },
    )
}

@Composable
private fun OutboxQueuedFiles(files: List<OutboxQueuedFile>) {
    val downloader = rememberMediaDownloader()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        files.forEach { file ->
            val local = remember(file.localPath) { File(file.localPath) }
            val exists = remember(file.localPath) { local.isFile }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                MediaDownloadButton(
                    fileName = file.name,
                    onDownload = { downloader.save(Uri.fromFile(local), file.name, file.mimeType) },
                    unavailableReason = if (exists) null else "This copy is no longer on this device.",
                )
            }
        }
        MediaDownloadNotice(downloader)
    }
}

@Composable
private fun OutboxWaitingCard(row: OutboxWaitingRow, busy: Boolean, onSendNow: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(row.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                row.kind,
                row.status,
                when (row.files.size) {
                    0 -> null
                    1 -> "1 file"
                    else -> "${row.files.size} files"
                },
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (row.files.isNotEmpty()) OutboxQueuedFiles(row.files)
        OutlinedButton(onClick = onSendNow, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Send now", style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun filesPhrase(count: Int): String =
    if (count == 1) "1 photograph or recording" else "$count photographs and recordings"
