package com.designprototype.workshop.ui.designworkshop

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
import com.designprototype.workshop.data.DwCopySourceDto
import com.designprototype.workshop.data.DwCopySourcesDto
import com.designprototype.workshop.data.DwFieldStampDto
import com.designprototype.workshop.data.dwCopyPreview
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.Text
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/*
 * C9 (owner decision, 2026-10-17): start stages 2, 4, 5 and 7 from an earlier workshop — the
 * handset's twin of the web's `CopyFromEarlierPanel`. The designer's OWN earlier text is copied into
 * EMPTY boxes only and marked; other designers' text is shown read only and never put in a box; a
 * copied box counts toward completeness only once it is edited or kept ("Keep as is").
 *
 * The marks ride `StageDraft.copiedFrom` into the next push as `copiedFrom`; the server verifies
 * them against the source. Fetching the sources needs the network — the card says so offline.
 */

@Composable
internal fun DwCopyFromEarlierCard(
    repository: WorkshopRepository,
    /** The SERVER id; null for a workshop only this device holds. */
    serverId: String?,
    stageKey: String,
    online: Boolean,
    enabled: Boolean,
    labels: Map<String, String>,
    pending: Set<String>,
    /** Fill empty boxes from this source; answers how many were filled. */
    onCopy: (sourceWorkshopId: String, values: Map<String, JsonElement>) -> Int,
    /** "Keep as is": drop local marks, and the server stamps that came back for the rest. */
    onKept: (local: Set<String>, stamps: Map<String, DwFieldStampDto>?) -> Unit,
    localMarks: Set<String>,
) {
    val scope = rememberCoroutineScope()
    var open by remember(stageKey) { mutableStateOf(false) }
    var sources by remember(stageKey) { mutableStateOf<DwCopySourcesDto?>(null) }
    var note by remember(stageKey) { mutableStateOf<String?>(null) }
    var busy by remember(stageKey) { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending.isNotEmpty()) {
                Text(
                    "Copied, not counted until edited or kept: " +
                        pending.mapNotNull { labels[it] }.joinToString(", ") + ".",
                    fontSize = 13.sp,
                )
                ActionRow {
                    OutlinedButton(
                        onClick = {
                            val local = pending.filter { it in localMarks }.toSet()
                            val remote = pending - local
                            if (remote.isEmpty() || serverId == null || !online) {
                                onKept(local, null)
                                if (remote.isNotEmpty()) note = "Connect to keep the text copied earlier."
                                return@OutlinedButton
                            }
                            busy = true
                            scope.launch {
                                val answer = runCatching {
                                    repository.confirmCopiedFields(serverId, stageKey, remote.toList())
                                }.getOrNull()
                                busy = false
                                onKept(local, answer?.provenance)
                                if (answer == null) note = "Couldn't keep the copied text. Try again."
                            }
                        },
                        enabled = enabled && !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (busy) "Keeping…" else "Keep as is") }
                }
            }
            OutlinedButton(
                onClick = {
                    open = !open
                    if (open && sources == null && serverId != null && online) {
                        scope.launch {
                            sources = runCatching { repository.stageCopySources(serverId, stageKey) }.getOrNull()
                            if (sources == null) note = "Couldn't load earlier workshops."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Start from an earlier workshop") }
            if (open) {
                when {
                    serverId == null || !online -> Text("Connect to see your earlier workshops.", fontSize = 13.sp)
                    sources == null && note == null -> Text("Loading…", fontSize = 13.sp)
                }
                note?.let { Text(it, fontSize = 13.sp) }
                sources?.let { found ->
                    val fieldLabels = found.fields.associate { it.key to it.label }
                    Text("Your earlier workshops", fontWeight = FontWeight.Bold)
                    if (found.own.isEmpty()) {
                        Text("Nothing of yours to copy for this stage yet.", fontSize = 13.sp)
                    }
                    found.own.forEach { source ->
                        DwCopySourceBlock(source, fieldLabels)
                        ActionRow {
                            Button(
                                onClick = {
                                    val filled = onCopy(source.workshopId, source.values)
                                    note = if (filled > 0) {
                                        "${if (filled == 1) "1 box" else "$filled boxes"} filled from “${source.title}”."
                                    } else {
                                        "Every box already has an answer. Nothing was replaced."
                                    }
                                },
                                enabled = enabled,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Copy into empty boxes") }
                        }
                    }
                    if (found.reference.isNotEmpty()) {
                        Text("Other designers in this cluster", fontWeight = FontWeight.Bold)
                        Text("Read only. Write your own in the boxes below.", fontSize = 12.sp)
                        found.reference.forEach { source -> DwCopySourceBlock(source, fieldLabels) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DwCopySourceBlock(source: DwCopySourceDto, labels: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(source.title, fontWeight = FontWeight.SemiBold)
        listOfNotNull(source.date, source.designerName).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        source.values.forEach { (key, value) ->
            Text(labels[key] ?: key, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(dwCopyPreview(value), fontSize = 13.sp)
        }
    }
}
