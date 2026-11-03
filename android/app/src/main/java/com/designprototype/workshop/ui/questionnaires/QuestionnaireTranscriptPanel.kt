package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

const val QUESTIONNAIRE_OFFERED_TRANSCRIPT_FILENAME_BASE: String = "Offered-transcript"

@Composable
fun QuestionnaireQuickTranscript(
    busy: Boolean,
    problem: String?,
    machine: String?,
    current: String,
    offered: String?,
    onAccept: () -> Unit,
    onDiscard: () -> Unit,
    filenameBase: String = QUESTIONNAIRE_OFFERED_TRANSCRIPT_FILENAME_BASE,
    onSave: ((fileName: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val edited = questionnaireTranscriptEdited(machine, current)
    val flag = questionnaireEditedFlagLabel(edited)
    if (!busy && problem == null && offered == null && flag == null) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (busy) {
                FieldCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Text(
                    QUESTIONNAIRE_TRANSCRIBING_LINE,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            flag?.let { label ->
                val amber = edited == true
                Row(
                    modifier = Modifier
                        .background(
                            if (amber) MaterialTheme.field.warningContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (amber) Icons.Filled.Edit else Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = if (amber) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        label,
                        color = if (amber) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        offered?.let { text ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    QUESTIONNAIRE_OFFER_LINE,
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp
                )
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme.copy(
                        primary = MaterialTheme.field.onWarningContainer
                    )
                ) {
                    QuestionnaireTranscriptActions(
                        text = text,
                        filenameBase = filenameBase,
                        onSave = onSave
                    )
                }
                Text(
                    text,
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 19.sp
                )
                ActionRow {
                    OutlinedButton(onClick = onAccept) {
                        Text(QUESTIONNAIRE_OFFER_ACCEPT, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = onDiscard) {
                        Text(QUESTIONNAIRE_OFFER_DISCARD, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        problem?.let {
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp
            )
        }
    }
}
