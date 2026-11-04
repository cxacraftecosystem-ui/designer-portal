package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.DuplicateCandidateDto
import com.designprototype.workshop.data.PossibleDuplicates

@Composable
fun PossibleDuplicateSheet(
    candidates: List<DuplicateCandidateDto>,
    saving: Boolean,
    onOpen: ((String) -> Unit)?,
    onDifferentPerson: () -> Unit,
    onCancel: () -> Unit,
) {
    val rows = candidates.take(PossibleDuplicates.SHOWN_MAX)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Already recorded?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                PossibleDuplicates.rowLine(candidate),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val reasons = candidate.reasons.joinToString(" · ") { it.label }
                            if (reasons.isNotBlank()) {
                                Text(reasons, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (onOpen != null) {
                            OutlinedButton(
                                onClick = { onOpen(candidate.artisan.id) },
                                enabled = !saving,
                                modifier = Modifier.heightIn(min = 40.dp),
                            ) {
                                Text("Open", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDifferentPerson, enabled = !saving) {
                Text("Different person — save", style = MaterialTheme.typography.labelMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel", style = MaterialTheme.typography.labelMedium) }
        },
    )
}
