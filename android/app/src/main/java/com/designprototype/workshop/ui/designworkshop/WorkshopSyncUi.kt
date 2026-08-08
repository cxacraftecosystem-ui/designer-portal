package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.WorkshopSyncStatus
// The two-typeface `Text`, shadowing androidx.compose.material3.Text. Without this import the bare
// `Text` below resolves to Material's, inherits whatever family LocalTextStyle carries, and quietly
// sets these lines in the wrong face — the exact failure ui/FieldText.kt exists to prevent.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * The surfaces that answer "is my work safe yet?" without the designer having to guess.
 *
 * WHY THIS IS NOT ONE SPINNER. Before it, the only thing the workshop list said about syncing was a
 * badge reading "On this device only", shown when a workshop had no server id — which is a true
 * statement about the HEADER and says nothing whatever about the fortnight of stages and photographs
 * hanging off it. A workshop created on the server on day one and captured offline for the next
 * thirteen days showed no badge at all, and looked identical to one that was fully backed up. The
 * question a designer actually asks before they leave a cluster is not "does a record exist" but
 * "has everything I captured left this phone", and nothing on screen could answer it.
 *
 * So every surface here counts THINGS rather than showing a percentage or a state colour: nine
 * stages, four photographs, one refusal and the reason for it. A number a designer can compare
 * against what they know they captured is checkable; a green tick is something they have to trust.
 *
 * NOTHING HERE EVER DELETES ANYTHING ON ITS OWN. The one action that removes bytes is
 * [WorkshopSyncActions]'s "free up space", it is a button a person presses, it names the amount
 * first, and it only ever releases files the server has acknowledged an id for. See the KDoc on
 * `WorkshopDraftStore.releaseUploadedMedia` for why keeping them is the default: the phone is where
 * the offline report is generated, and a workshop whose photographs were swept still exports —
 * silently, with the pictures missing.
 */

/** Sizes in the units a person reads, so "is this a big send?" is answerable at a glance. */
internal fun syncBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/**
 * The one-line state of a single workshop, drawn on its row in the list.
 *
 * [sending] is passed rather than derived so the row that is actually being uploaded says so, while
 * the nineteen behind it keep showing what they are waiting on rather than all claiming to be busy.
 */
@Composable
internal fun WorkshopSyncChip(status: WorkshopSyncStatus, sending: Boolean) {
    val (icon, tint, container) = when {
        sending -> Triple(
            Icons.Filled.CloudUpload,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primaryContainer
        )
        status.hasFailures -> Triple(
            Icons.Filled.ErrorOutline,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.errorContainer
        )
        status.isFullySynced -> Triple(
            Icons.Filled.CheckCircle,
            MaterialTheme.field.onSuccessContainer,
            MaterialTheme.field.successContainer
        )
        else -> Triple(
            Icons.Filled.CloudOff,
            MaterialTheme.field.onWarningContainer,
            MaterialTheme.field.warningContainer
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(
            if (sending) "Sending…" else status.summary,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Everything one workshop is waiting on, plus the two things a person can do about it.
 *
 * The problem list is shown IN FULL rather than summarised into "3 problems". Each line is already a
 * sentence naming the file or the stage and the server's own words for refusing it, and those words
 * are the only thing that tells a designer whether the fix is theirs (sign in again, shorten a
 * field) or somebody else's. A count would send them to a support request to find out.
 */
@Composable
internal fun WorkshopSyncActions(
    status: WorkshopSyncStatus,
    busy: Boolean,
    onRetry: () -> Unit,
    onFreeSpace: () -> Unit,
) {
    var showProblems by remember(status.workshopId) { mutableStateOf(false) }
    val problems = status.problems

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        if (status.pendingMedia > 0) {
            Text(
                "${status.pendingMedia} file(s) — ${syncBytes(status.pendingMediaBytes)} — are on this " +
                    "phone and nowhere else.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
        status.lastError?.takeIf { !status.isFullySynced }?.let { error ->
            // A transient reason, kept apart from the refusals below and worded as the network's
            // fault rather than the data's, because the two call for completely different reactions
            // and mixing them teaches the designer to ignore both.
            Text(
                "Last attempt: $error Nothing has been lost — it will try again on its own.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }

        if (problems.isNotEmpty()) {
            Text(
                if (showProblems) "Hide details" else "${problems.size} thing(s) need attention — show details",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { showProblems = !showProblems }
            )
            if (showProblems) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    problems.forEach { line ->
                        Text("• $line", color = MaterialTheme.field.muted, fontSize = 11.sp)
                    }
                    Text(
                        "Nothing above has been deleted from this device. Everything listed is still " +
                            "here and still editable.",
                        color = MaterialTheme.field.muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!status.isFullySynced) {
                OutlinedButton(onClick = onRetry, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (status.remoteId == null) "Send to server" else "Try again now")
                }
            }
            Spacer(Modifier.weight(1f))
            // Offered ONLY when there is something genuinely duplicated on the server, and it names
            // the amount before it is pressed. An automatic sweep would take away the ability to
            // generate the report on the device that captured it, which is this product's whole
            // claim — so it is a decision, and it is always the designer's.
            if (status.releasableMedia > 0) {
                TextButton(onClick = onFreeSpace, enabled = !busy) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Free ${syncBytes(status.releasableBytes)}")
                }
            }
        }
    }
}

/**
 * The whole device in one strip above the list: what is outstanding everywhere, and one button.
 *
 * Shown only when there IS something outstanding. A permanent "everything is synced" bar is a bar
 * people stop reading, and then the day it changes nobody notices.
 */
@Composable
internal fun DeviceSyncBanner(
    workshops: Int,
    stages: Int,
    files: Int,
    bytes: Long,
    refusals: Int,
    busy: Boolean,
    onSyncNow: () -> Unit,
) {
    if (workshops == 0 && refusals == 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                color = MaterialTheme.field.onWarningContainer,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.field.onWarningContainer,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                buildList {
                    if (stages > 0) add("$stages stage(s)")
                    if (files > 0) add("$files file(s), ${syncBytes(bytes)}")
                    if (refusals > 0) add("$refusals refused")
                }.joinToString(" · ").ifBlank { "Waiting to upload" },
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Across $workshops workshop(s) on this device. Everything is saved here and editable " +
                    "offline; it uploads whenever there is a connection.",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 10.sp
            )
        }
        OutlinedButton(onClick = onSyncNow, enabled = !busy) {
            Text(if (busy) "Syncing…" else "Sync now", fontSize = 12.sp)
        }
    }
}
