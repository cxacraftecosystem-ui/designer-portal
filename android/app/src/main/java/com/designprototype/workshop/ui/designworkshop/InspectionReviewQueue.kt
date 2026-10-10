package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DwInspectionAttempt
import com.designprototype.workshop.data.DwInspectionReviewQueue
import com.designprototype.workshop.data.DwQueuedInspectionNote
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.canInspectDesignWorkshops
import com.designprototype.workshop.data.dwInspectionReviewQueue
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException

/** The section's heading, pinned by a test against the walkthrough card that teaches it. */
const val DW_INSPECTION_QUEUE_TITLE = "Workshops waiting for your decision"

/**
 * THE INSPECTOR'S HALF OF THE REVIEW QUEUE: the assigned workshops sorted by whose move it is.
 *
 * Drawn at the top of the Review screen for an Inspector / Reviewer, above the record queue every
 * reviewer shares. A report in Pre-submission has been handed in and is waiting for an officer —
 * that is the queue. Reports sent back and waiting for their designers are listed under it, and the
 * ones not yet handed in are counted, so nothing assigned silently disappears from the screen.
 *
 * Read from the same list route as Workshops to inspect, walked to the server's ceiling, and from the
 * copy kept on this phone when there is no signal — said so on screen. Notes written on this phone
 * and not yet on the record are counted per workshop, held ones included, so an officer sees where
 * something of theirs still needs attention.
 */
@Composable
fun InspectionAwaitingSection(
    repository: WorkshopRepository,
    onOpenWorkshop: (workshopId: String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewer = remember(repository) { repository.cachedUser() }
    val mayInspect = remember(viewer) { viewer != null && canInspectDesignWorkshops(viewer.role) }
    if (!mayInspect) return

    var queue by remember { mutableStateOf<DwInspectionReviewQueue?>(null) }
    var more by remember { mutableStateOf(false) }
    var savedAt by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<DwQueuedInspectionNote>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        loadError = null
        try {
            val (rows, cut, kept) = repository.inspectionReviewQueue(appContext)
            queue = dwInspectionReviewQueue(rows)
            more = cut
            savedAt = kept
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.inspectionFailure(DwInspectionAttempt.READ)
        }
        notes = runCatching { repository.queuedInspectionNotes(appContext) }.getOrDefault(emptyList())
        loading = false
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                DW_INSPECTION_QUEUE_TITLE,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Design & prototype workshops you were appointed to inspect whose report has been " +
                    "handed in and is waiting for an officer. Open one to read it and file suggestions " +
                    "or send it back.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
            savedAt?.let {
                InspectionNotice(
                    "No connection — this is the list this phone last saw, on ${it.take(10)}. A report " +
                        "may have moved since; each one is checked again before anything you write is sent.",
                    warning = true
                )
            }
            loadError?.let { InspectionNotice(it, warning = false) }

            val served = queue
            when {
                served == null && loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading…", color = MaterialTheme.field.muted, fontSize = 12.sp)
                }

                served == null -> OutlinedButton(onClick = { reload++ }) { Text("Try again") }

                else -> {
                    if (served.awaitingYou.isEmpty()) {
                        Text(
                            "Nothing is waiting for your decision.",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        )
                    }
                    served.awaitingYou.forEach { row ->
                        QueueRow(row, notes.filter { it.workshopId == row.id }) { onOpenWorkshop(row.id) }
                    }
                    if (served.withDesigners.isNotEmpty()) {
                        Text(
                            "Sent back, waiting for their designers",
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        served.withDesigners.forEach { row ->
                            QueueRow(row, notes.filter { it.workshopId == row.id }) { onOpenWorkshop(row.id) }
                        }
                    }
                    if (served.notHandedIn.isNotEmpty()) {
                        val n = served.notHandedIn.size
                        Text(
                            "$n other assigned ${if (n == 1) "workshop has" else "workshops have"} not been " +
                                "handed in yet; ${if (n == 1) "it is" else "they are"} on Workshops to inspect.",
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp
                        )
                    }
                    if (more) {
                        Text(
                            "You hold more assignments than this screen reads at once. Search Workshops " +
                                "to inspect to reach the rest.",
                            color = MaterialTheme.field.warning,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(row: DesignWorkshopDto, notes: List<DwQueuedInspectionNote>, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            row.title.ifBlank { "Untitled workshop" },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        val line = listOfNotNull(
            row.workshopCode?.takeIf { it.isNotBlank() },
            row.craftName?.takeIf { it.isNotBlank() },
            row.clusterName?.takeIf { it.isNotBlank() },
            row.submissionRound.takeIf { it > 0 }?.let { "round $it" },
        ).joinToString(" · ")
        if (line.isNotBlank()) Text(line, color = MaterialTheme.field.muted, fontSize = 12.sp)
        if (notes.isNotEmpty()) {
            val held = notes.count { !it.waiting }
            Text(
                "${notes.size} ${if (notes.size == 1) "note" else "notes"} of yours on this phone, not yet " +
                    "on the record" + if (held > 0) " — $held need${if (held == 1) "s" else ""} your attention." else ".",
                color = if (held > 0) MaterialTheme.field.warning else MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
    }
}
