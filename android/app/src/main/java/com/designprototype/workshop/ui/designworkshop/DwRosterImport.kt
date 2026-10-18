package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwArtisanImportDto
import com.designprototype.workshop.data.DwArtisanImportReportDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dwRosterHistoryLine
import com.designprototype.workshop.data.dwRosterProblemWhere
import com.designprototype.workshop.data.dwRosterTallies
import com.designprototype.workshop.data.userMessage
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.Text
import kotlinx.coroutines.launch

/*
 * C11 (owner decision, 2026-10-17): "Import artisans from a spreadsheet" on stage 3 — the web's
 * `RosterImportPanel`, on the handset. Download the pro-forma (already naming this workshop), fill it
 * in, pick it, upload it; the report is shown in full and the earlier uploads are listed. Online only,
 * like the copy card: the server reads the workbook, and an import report nobody sees is the point of
 * the upload thrown away, so nothing here is queued. After an import the participant rows exist on the
 * server, so the card offers to reload the stage rather than pretending the form already shows them.
 */

@Composable
internal fun DwRosterImportCard(
    context: Context,
    repository: WorkshopRepository,
    /** The SERVER id; null for a workshop only this device holds. */
    serverId: String?,
    online: Boolean,
    enabled: Boolean,
    /** Re-read the stage from the server once an import has added participants. */
    onImported: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<DwArtisanImportReportDto?>(null) }
    var history by remember { mutableStateOf<List<DwArtisanImportDto>?>(null) }

    fun reloadHistory(id: String) {
        scope.launch {
            history = runCatching { repository.rosterImports(id).imports }.getOrDefault(emptyList())
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val id = serverId
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        busy = "upload"
        note = null
        report = null
        scope.launch {
            runCatching { repository.uploadRoster(context, id, uri) }
                .onSuccess {
                    report = it
                    reloadHistory(id)
                }
                .onFailure { note = it.userMessage("Couldn't import that list. Try again.") }
            busy = null
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    open = !open
                    val id = serverId
                    if (open && history == null && id != null && online) reloadHistory(id)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Import artisans from a spreadsheet") }
            if (!open) return@Column
            if (serverId == null || !online) {
                Text("Connect to import an artisan list.", fontSize = 13.sp)
                return@Column
            }
            Text(
                "Contains Aadhaar numbers. Don't email or share the filled file; delete it after uploading. " +
                    "The file isn't stored.",
                fontSize = 12.sp,
            )
            ActionRow {
                OutlinedButton(
                    onClick = {
                        busy = "download"
                        note = null
                        scope.launch {
                            note = runCatching { repository.downloadRosterProForma(context, serverId) }
                                .fold(
                                    onSuccess = { "Saved to $it." },
                                    onFailure = { it.userMessage("Couldn't download the pro-forma. Try again.") },
                                )
                            busy = null
                        }
                    },
                    enabled = busy == null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (busy == "download") "Preparing…" else "Download the pro-forma") }
                Button(
                    onClick = { pick.launch(arrayOf(XLSX_PICK_MIME)) },
                    enabled = enabled && busy == null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (busy == "upload") "Reading the list…" else "Upload the filled-in list") }
            }
            note?.let { Text(it, fontSize = 13.sp) }
            report?.let { done ->
                Text(
                    dwRosterTallies(done).joinToString(" · ") { (label, value) -> "$value $label" },
                    fontSize = 13.sp,
                )
                val errors = done.problems.filter { it.severity == "error" }
                val warnings = done.problems.filter { it.severity != "error" }
                if (errors.isEmpty() && warnings.isEmpty()) {
                    Text("Every row was read.", fontSize = 13.sp)
                }
                if (errors.isNotEmpty()) {
                    Text("Not imported — fix and upload again", fontWeight = FontWeight.Bold)
                    errors.forEach { problem ->
                        Text(
                            "${dwRosterProblemWhere(problem)}: ${problem.reason}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (warnings.isNotEmpty()) {
                    Text("Imported — check these", fontWeight = FontWeight.Bold)
                    warnings.forEach { problem ->
                        Text("${dwRosterProblemWhere(problem)}: ${problem.reason}", fontSize = 13.sp)
                    }
                }
                if (done.participantsCreated > 0) {
                    ActionRow {
                        Button(onClick = onImported, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Reload this stage")
                        }
                    }
                }
            }
            Text("Earlier uploads", fontWeight = FontWeight.Bold)
            when {
                history == null -> Text("Loading…", fontSize = 13.sp)
                history!!.isEmpty() -> Text("No uploads yet.", fontSize = 13.sp)
                else -> history!!.forEach { entry ->
                    Text(entry.sourceFilename ?: "An artisan list", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(dwRosterHistoryLine(entry), fontSize = 12.sp)
                }
            }
        }
    }
}

private const val XLSX_PICK_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
