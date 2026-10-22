package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/*
 * The form detail's newer cards (UNIFY §4.2, 22 Oct 2026): sessions on this form, its workshop
 * bindings, the overflow menu, the kind dialog, P16 and the deleted-form banner.
 */

@Composable
private fun FormSurfaceCard(content: @Composable () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun CardHeading(text: String) {
    Text(text, display = true, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

/**
 * Sessions on this form (v2 on): Start session is the primary action; with [onOpenSessions] the two
 * sit on one line. A form needs live questions to be answered; a deactivated one takes none.
 */
@Composable
internal fun FormSessionsCard(
    canStart: Boolean,
    reason: String?,
    onStart: () -> Unit,
    onOpenSessions: (() -> Unit)?,
) {
    FormSurfaceCard {
        CardHeading("Sessions")
        Text("Each session is one sitting with an artisan, a group or a guest.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        ActionRow(modifier = Modifier.fillMaxWidth()) {
            onOpenSessions?.let { OutlinedButton(onClick = it) { Text("Sessions") } }
            Button(onClick = onStart, enabled = canStart) { Text("Start session") }
        }
        reason?.let { EmptyNote(it) }
    }
}

/** Why a form cannot start a session now, or null when it can. */
fun formSessionBlock(isActive: Boolean, deleted: Boolean, activeQuestions: Int, onPhoneOnly: Boolean): String? = when {
    deleted -> "Deleted — restore it to start a session."
    !isActive -> "Deactivated — can't record new answers."
    activeQuestions == 0 -> "Add questions first."
    onPhoneOnly -> "Uploads first; then a session can start."
    else -> null
}

/** A9: the design workshops this form is bound to, primary first. Read-only on this handset. */
@Composable
internal fun FormBindingsCard(lines: List<BindingLine>) {
    FormSurfaceCard {
        CardHeading("Workshops")
        if (lines.isEmpty()) {
            EmptyNote("Not on a workshop. Sessions can still use it.")
        } else {
            lines.forEach { line ->
                Text(bindingLabel(line), color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
            }
            EmptyNote("A session on one of these workshops opens on its primary form.")
        }
    }
}

/** The form's overflow menu. Nothing is drawn when there is nothing to offer. */
@Composable
internal fun FormOverflowMenu(items: List<FormMenuItem>, onPick: (FormMenuItem) -> Unit) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            item.label,
                            color = if (item == FormMenuItem.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; onPick(item) },
                )
            }
        }
    }
}

/** The kind (A8), as its own small dialog from the menu. Blank is "not stated". */
@Composable
internal fun FormKindDialog(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var picked by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kind") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchableSelectField(
                    label = "Kind",
                    options = QUESTIONNAIRE_KIND_LABELS.map { (token, text) -> SelectOption(token, text) },
                    selectedValue = picked,
                    placeholder = "Not stated",
                    includeNone = true,
                    onSelect = { picked = it },
                )
                EmptyNote("Sets which report stage these answers go under.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** P16: Delete this form? · Admins can restore it from Deleted. · Cancel · Delete. */
@Composable
internal fun FormDeleteDialog(busy: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(FormDeleteCopy.TITLE) },
        text = { Text(FormDeleteCopy.BODY, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(enabled = !busy, onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A deleted form opened from Deleted: one sentence and, for its author or a content admin, Restore. */
@Composable
internal fun DeletedFormBanner(mayRestore: Boolean, busy: Boolean, onRestore: () -> Unit) {
    FormSurfaceCard {
        Text(FormDeleteCopy.DELETED_BANNER, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodyMedium)
        if (mayRestore) {
            ActionRow(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onRestore, enabled = !busy) { Text("Restore") }
            }
        }
    }
}
