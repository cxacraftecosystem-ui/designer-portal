package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.ui.AppToggle
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.designprototype.workshop.data.CustomQuestionnaireDto
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.ATTACH_LATER
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

internal const val REUSE_BLURB: String =
    "Makes a new questionnaire you own, with the questions only — no answers."

@Composable
internal fun ReuseCard(busy: Boolean, onOpen: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Use this questionnaire again",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = onOpen, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy it to another workshop", style = MaterialTheme.typography.labelLarge)
            }
            Text(REUSE_BLURB, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun ReuseQuestionnaireDialog(
    source: CustomQuestionnaireDto,
    workshops: AttachableWorkshops,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCopy: (designWorkshopId: String?, title: String?, description: String?, changeDescription: Boolean) -> Unit,
) {
    var workshopId by remember(source.id) { mutableStateOf("") }
    var title by remember(source.id) { mutableStateOf("") }
    var changeDescription by remember(source.id) { mutableStateOf(false) }
    var description by remember(source.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Use “${source.title.ifBlank { "this questionnaire" }}” again", display = true) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(REUSE_BLURB, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)

                val options = workshops.options()
                SearchableSelectField(
                    label = "Attach the copy to",
                    options = options,
                    selectedValue = workshopId,
                    placeholder = ATTACH_LATER,
                    includeNone = true,
                    enabled = !busy,
                    emptyMessage = workshops.notice(),
                    onSelect = { workshopId = it },
                )
                if (options.isEmpty()) {
                    workshops.notice()?.let { line ->
                        Text(
                            "$line The copy can be attached later.",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                    }
                } else {
                    Text(
                        "Only workshops you can open.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp,
                    )
                    workshops.capNotice()?.let { cap ->
                        Text(
                            cap,
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title for the copy (optional)") },
                    placeholder = { Text("${source.title.ifBlank { "Untitled" }} (reused)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Defaults to “${source.title.ifBlank { "Untitled" }} (reused)”.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )

                AppToggle(
                    label = "Give the copy a different description",
                    checked = changeDescription,
                    onCheckedChange = { changeDescription = it },
                    enabled = !busy,
                )
                if (changeDescription) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description for the copy") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    if (changeDescription) {
                        "Left blank, there's no description."
                    } else {
                        source.description?.takeIf { it.isNotBlank() }
                            ?.let { "The copy carries the same description: “$it”" }
                            ?: "The source has no description."
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onCopy(
                        workshopId.takeIf { it.isNotBlank() },
                        title.takeIf { it.isNotBlank() },
                        description,
                        changeDescription,
                    )
                }
            ) {
                if (busy) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "Copying…" else "Make the copy")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
