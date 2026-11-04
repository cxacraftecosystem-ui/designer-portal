package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.ui.ActionRow
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import com.designprototype.workshop.data.DwQuestionnaireArtefact
import com.designprototype.workshop.data.QFormChangeReportDto
import com.designprototype.workshop.data.artefactContents
import com.designprototype.workshop.data.artefactCarriesRespondents
import com.designprototype.workshop.data.qFormProblemLine
import com.designprototype.workshop.data.qFormProblemsToShow
import com.designprototype.workshop.data.qFormProvenanceNotice
import com.designprototype.workshop.data.qFormUploadSummary
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

@Composable
internal fun ArtefactNotice(artefact: DwQuestionnaireArtefact) {
    val carries = artefactCarriesRespondents(artefact)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            artefactContents(artefact),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        Text(
            if (carries) {
                "Contains respondents' names and answers."
            } else {
                "No names or answers."
            },
            color = if (carries) MaterialTheme.field.warning else MaterialTheme.field.muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (carries) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun ArtefactDownloadRow(
    label: String,
    artefact: DwQuestionnaireArtefact,
    busy: Boolean,
    working: Boolean,
    savedTo: String?,
    onDownload: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionRow {
            OutlinedButton(
                onClick = onDownload,
                enabled = !busy,
                modifier = Modifier.heightIn48(),
            ) {
                if (working) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Saving…" else label, style = MaterialTheme.typography.labelLarge)
            }
        }
        ArtefactNotice(artefact)
        savedTo?.let {
            Text("Saved to $it", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun rememberWorkbookPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return {
        launcher.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/octet-stream",
                "*/*",
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkbookUploadRow(
    label: String,
    blurb: String,
    busy: Boolean,
    working: Boolean,
    onPick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionRow {
            OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.heightIn48()) {
                if (working) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Reading the workbook…" else label, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(blurb, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
    }
}

@Composable
internal fun UploadReportPanel(
    report: QFormChangeReportDto,
    onDismiss: () -> Unit,
    heading: String = "What the upload did",
) {
    val provenance = remember(report) { qFormProvenanceNotice(report) }
    val problems = remember(report) { qFormProblemsToShow(report) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            heading,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(qFormUploadSummary(report), color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        if (report.versionAfter > report.versionBefore) {
            Text(
                "Version changed from ${report.versionBefore} to ${report.versionAfter}; an in-progress sitting uses the older one.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        provenance?.let { notice ->
            HorizontalDivider(color = MaterialTheme.field.hairline)
            Text(
                notice.heading,
                color = if (notice.warn) MaterialTheme.field.warning else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            notice.tally?.let {
                Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                notice.reason,
                color = if (notice.warn) MaterialTheme.field.warning else MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        if (report.details.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.field.hairline)
            Text(
                "Questions that were superseded or retired",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            report.details.forEach { detail ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    detail.before?.takeIf { it.isNotBlank() }?.let {
                        Text("“$it”", color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
                    }
                    detail.after?.takeIf { it.isNotBlank() }?.let {
                        Text("became “$it”", color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(detail.reason, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
                }
            }
        }

        if (problems.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.field.hairline)
            Text(
                "Rows that couldn't be read (${problems.size})",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Sheet and row as shown in Excel (Ctrl+G to jump).",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            problems.forEach { problem ->
                Text(
                    qFormProblemLine(problem),
                    color = if (problem.severity == "error") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.field.warning
                    },
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        ActionRow {
            TextButton(onClick = onDismiss) { Text("Done", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

private fun Modifier.heightIn48(): Modifier = this.heightIn(min = 48.dp)
