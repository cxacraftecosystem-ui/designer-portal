package com.designprototype.workshop.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.designprototype.workshop.data.OutboxFailureRow
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.outboxFailureRows
import com.designprototype.workshop.data.outboxRetryAllMessage
import com.designprototype.workshop.data.outboxRetryMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * THE SCREEN `outboxFailures` WAS WRITTEN FOR AND NEVER GOT.
 *
 * ── WHAT WAS ACTUALLY WRONG ───────────────────────────────────────────────────────────────────
 *
 * `WorkshopRepository.outboxFailures` carries this comment on the durable half of a refusal:
 * "readable through `outboxFailures` by whatever screen shows it next". A repo-wide grep for the name
 * returned the declaration and nothing else. Nothing showed it. So the state of affairs on a field
 * handset was:
 *
 *  - a record the server had refused for good sat in the queue for ever;
 *  - the banner counted it under the words "uploading when you're online";
 *  - the only mention of the refusal was a Toast, fired once, and only when the REASON changed;
 *  - and `OfflineOutbox` had `markFailure` with NO INVERSE, so even a designer who had read the
 *    Toast, gone back to the office and had the permission granted could not make the app try again.
 *    The only route out was to reinstall the app, which destroys the queue.
 *
 * Compare `DraftMedia.uploadFailure` in `data/WorkshopDraftStore.kt`, forty lines of the same app:
 * "Cleared by a manual retry, which is what makes 'the file limit was raised, try again' one tap
 * rather than a support request." That is the sentence this tray exists to make true for records.
 *
 * ── THE THREE RULES IT KEEPS ──────────────────────────────────────────────────────────────────
 *
 * 1. THE SERVER'S OWN REASON, VERBATIM. Those sentences are written for the person holding the phone
 *    — "only the person who recorded this answer can change it", "an artisan with this Aadhaar number
 *    is already on the register". Nothing here summarises one into "Upload failed".
 * 2. RETRY IS ALWAYS OFFERED, EVEN WHEN THE APP EXPECTS IT TO FAIL. The app cannot know that an
 *    administrator has just granted the permission, or that the office has deleted the duplicate. It
 *    can only know what happened last time.
 * 3. DISCARD IS A SEPARATE, NAMED, CONFIRMED ACT. Nothing automatic in this app may delete a queued
 *    entry that has not been sent; see `OfflineOutbox.discard`. The confirmation says how many files
 *    go with it, because that is the number the designer is actually deciding about.
 */
@Composable
fun OfflineOutboxTray(
    repository: WorkshopRepository,
    onClose: () -> Unit,
    /** Called after anything changed, so the banner behind the dialog re-reads its counts. */
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    var rows by remember { mutableStateOf<List<OutboxFailureRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf<OutboxFailureRow?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        // The PROJECTION and not the entries — the tray never holds `PendingEntry.payloadJson`, which
        // is the whole record body including an artisan's identity answers. See OutboxFailureRow.
        rows = runCatching { outboxFailureRows(repository.outboxFailures(appContext)) }
            .getOrDefault(emptyList())
        loading = false
    }

    /** Run one action, then re-read. Everything that writes goes through here. */
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
                        note = "That did not work: ${error.message ?: "no reason given"}."
                    }
                }
            busy = false
        }
    }

    confirmDiscard?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Throw this away?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "“${row.label}” and ${
                        if (row.mediaCount == 1) "1 file" else "${row.mediaCount} files"
                    } saved with it will be deleted from this device. This cannot be undone, and " +
                        "nothing about it has reached the server.",
                    color = MaterialTheme.field.body,
                    fontSize = 13.sp,
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
                            "“${doomed.label}” was deleted from this device."
                        }
                    }
                ) { Text("Delete it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = null }) { Text("Keep it", fontSize = 13.sp) }
            },
        )
        // Deliberately NOT an early return: the tray stays composed underneath, so cancelling the
        // confirmation puts the designer back on the list they were reading rather than closing
        // everything and making them find it again.
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                "Records this device could not send",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "The server refused these, so waiting for a signal will not send them. Nothing " +
                        "has been deleted — the record and its photographs are still on this phone.",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                when {
                    loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Reading the queue…", color = MaterialTheme.field.muted, fontSize = 12.sp)
                    }

                    rows.isEmpty() -> Text(
                        "Nothing is refused any more.",
                        color = MaterialTheme.field.muted,
                        fontSize = 13.sp,
                    )

                    else -> rows.forEach { row ->
                        OutboxFailureCard(
                            row = row,
                            busy = busy,
                            onRetry = {
                                act {
                                    // The SENTENCE is `outboxRetryMessage`'s and not this
                                    // composable's, so it is pinned by a JVM test. What was here
                                    // before read `syncOutbox`'s total as though it were the answer
                                    // about this row, and said "was sent" about an entry that had not
                                    // moved because a different one had.
                                    outboxRetryMessage(
                                        label = row.label,
                                        result = repository.retryOutboxEntry(appContext, row.entryId),
                                    )
                                }
                            },
                            onDiscard = { confirmDiscard = row },
                        )
                    }
                }
                note?.let {
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                    Text(it, color = MaterialTheme.field.body, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", fontSize = 13.sp) } },
        dismissButton = {
            if (rows.size > 1) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        act {
                            outboxRetryAllMessage(repository.retryAllOutboxFailures(appContext))
                        }
                    }
                ) { Text("Try all again", fontSize = 13.sp) }
            }
        },
    )
}

/** One refused entry: what it is, why it will not go, and the two things a person can do about it. */
@Composable
private fun OutboxFailureCard(
    row: OutboxFailureRow,
    busy: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(row.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            fontSize = 11.sp,
        )
        // VERBATIM, and never truncated. It is the server's sentence, written for this person.
        Text(row.reason, color = MaterialTheme.field.warning, fontSize = 12.sp, lineHeight = 17.sp)
        if (row.awaitingUpdate) {
            // The one refusal nobody can act on. `blocksRetry` already re-attempts this class on the
            // next app run; saying so stops a designer deleting a good record to make a message go
            // away. Same sentence-shape as `skewSentence` on the sync path.
            Text(
                "This one is not your fault and not something you can fix: this copy of the app and " +
                    "the server disagree about the shape of the request. It will be tried again by " +
                    "itself after the app is updated. Do not delete it.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Try again", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Throw away", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
