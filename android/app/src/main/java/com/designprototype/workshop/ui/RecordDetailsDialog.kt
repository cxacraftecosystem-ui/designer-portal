package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The read-only view of a record whose whole record is its card — an allow-list entry, a roster
 * entry — opened by a tap anywhere on that card (owner ruling, 2026-10-16: a record opens to be
 * read; changing it is the separate "Update"). The web's `RecordDetailsDialog`, on the handset.
 *
 * A row whose value is null or blank is left out rather than printed empty. [onUpdate] draws the
 * Update button; the caller passes it only where the card's own Update would be drawn.
 */
@Composable
fun RecordDetailsDialog(
    title: String,
    details: List<Pair<String, String?>>,
    onDismiss: () -> Unit,
    onUpdate: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, display = true, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                details.forEach { (label, value) ->
                    if (!value.isNullOrBlank()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                label,
                                color = MaterialTheme.field.muted,
                                fontSize = 12.sp,
                                modifier = Modifier.width(120.dp)
                            )
                            Text(value, color = MaterialTheme.field.body, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onUpdate != null) {
                TextButton(onClick = onUpdate, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(RecordOpen.UPDATE_LABEL)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        }
    )
}
