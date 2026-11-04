package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.ui.MediaActionButton
import com.designprototype.workshop.ui.ActionRow
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
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
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
import com.designprototype.workshop.data.dwDeviceSyncBanner
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

internal fun syncBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

@Composable
internal fun WorkshopSyncChip(status: WorkshopSyncStatus, sending: Boolean) {
    val (icon, tint, container) = when {
        sending -> Triple(
            Icons.Filled.CloudUpload,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primaryContainer
        )
        status.hasFailures || status.refusedAnswers > 0 || status.unsentDeletions > 0 -> Triple(
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
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

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
                "${plural(status.pendingMedia, "file")} (${syncBytes(status.pendingMediaBytes)}) only on this phone.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        status.lastError?.takeIf { !status.isFullySynced }?.let { error ->
            Text(
                "Last attempt: $error It will retry automatically.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (problems.isNotEmpty()) {
            Text(
                if (showProblems) "Hide details" else "${problems.size} need attention — details",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
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
                        Text("• $line", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Nothing here has been deleted from this phone.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        ActionRow {
            if (!status.isFullySynced) {
                MediaActionButton(
                    label = if (status.remoteId == null) "Upload" else "Try again now",
                    icon = Icons.Filled.Refresh,
                    onClick = onRetry,
                    enabled = !busy,
                )
            }
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

@Composable
internal fun DeviceSyncBanner(
    workshops: Int,
    stages: Int,
    files: Int,
    bytes: Long,
    refusals: Int,
    refusedAnswers: Int,
    unsentDeletions: Int,
    droppedAnswers: Int,
    busy: Boolean,
    onSyncNow: () -> Unit,
) {
    val banner = dwDeviceSyncBanner(
        workshops = workshops,
        stages = stages,
        files = files,
        bytesText = syncBytes(bytes),
        failures = refusals,
        refusedAnswers = refusedAnswers,
        unsentDeletions = unsentDeletions,
        droppedAnswers = droppedAnswers,
    ) ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (busy) {
            FieldCircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                color = MaterialTheme.field.onWarningContainer,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                if (banner.waiting) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.field.onWarningContainer,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                banner.headline,
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                banner.detail,
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedButton(onClick = onSyncNow, enabled = !busy) {
            Text(if (busy) "Syncing…" else "Sync now", style = MaterialTheme.typography.labelMedium)
        }
    }
}
