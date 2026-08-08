package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwStageCompleteness
import com.designprototype.workshop.data.DwStageFocus
import com.designprototype.workshop.data.DwSubmissionReadiness
import com.designprototype.workshop.data.StageCompletenessDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.computeWorkshopCompleteness
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.overallPercent
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * The 22 stages of one workshop, each with its percentage and the list of what is still missing.
 *
 * ── WHERE THE NUMBERS COME FROM, AND WHY IT IS NOT SIMPLY "THE API" ──────────────────────────────
 *
 * The API returns a `completeness` block per stage and it is authoritative — for the data the SERVER
 * holds. That is not the same data the designer is looking at. A stage filled in an hour ago in a
 * courtyard has not reached the server, so rendering the server's figure beside it would tell the
 * designer their morning did not count, and the natural response to that is to type it again.
 *
 * So the score is computed HERE, from the local draft, through [computeWorkshopCompleteness], which
 * is a line-for-line port of the server's `stage_completeness`. The API's copy is fetched anyway and
 * used for exactly one thing: a stage this device has never opened, whose data therefore only exists
 * server-side (a workshop somebody else started, opened here for the first time). Local wins wherever
 * local knows anything.
 *
 * ── "WHAT IS MISSING" ────────────────────────────────────────────────────────────────────────────
 *
 * The missing list is BASIC-tier fields only, because BASIC is what the completeness gate counts and
 * what the report needs. Listing the unanswered STANDARD and ADVANCED fields too would produce a list
 * of two hundred items per stage, which is a list nobody reads — and the tiers exist precisely so a
 * workshop held without facilities can still be complete.
 *
 * ── AND EACH ONE IS A DESTINATION ────────────────────────────────────────────────────────────────
 *
 * Every entry in that list is tappable and lands on the box itself. It was inert text, and inert is
 * expensive in exactly the place this screen is read: the designer taps into stage 11, and then
 * scrolls a form of several hundred fields by eye hunting for "Warp count", once per gap, in the
 * last hour of a workshop. The web has had these as links throughout.
 *
 * The addresses come from [DwSubmissionReadiness], which walks the registry a second time to work
 * out WHERE each label lives and decides nothing about completeness — so a label it cannot place
 * still shows and still opens the stage, and this screen still prints exactly the labels the scorer
 * produced. See that module's header for why the asymmetry is the whole design.
 */
@Composable
fun StageIndexScreen(
    repository: WorkshopRepository,
    workshopId: String,
    /** [DwStageFocus] is null for "just open it" — the row itself, and any label with no address. */
    onOpenStage: (stageKey: String, focus: DwStageFocus?) -> Unit,
    onOpenReport: () -> Unit,
    /**
     * Open the artisan cards and prototype tags for this workshop.
     *
     * It hangs off the INDEX rather than off stage 13, and that is the point of putting it here: the
     * tags are wanted at the close of a workshop, for prototypes entered over a fortnight across three
     * stages, and a designer looking for them is looking at the workshop rather than at any one stage.
     * The report button beside it is reached the same way for the same reason.
     */
    onOpenCodes: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    var stages by remember(workshopId) { mutableStateOf<List<DwStageCompleteness>>(emptyList()) }
    var title by remember(workshopId) { mutableStateOf("") }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var expanded by remember(workshopId) { mutableStateOf<String?>(null) }
    var serverNote by remember(workshopId) { mutableStateOf<String?>(null) }
    /**
     * `stageKey → (missing label → the box it names)`, for turning the list below into destinations.
     *
     * Held apart from `stages` rather than folded into [DwStageCompleteness] because the two answer
     * different questions and only one of them is allowed to decide what is outstanding. This map is
     * a lookup; a label absent from it costs the row its precision and nothing else.
     */
    var addresses by remember(workshopId) {
        mutableStateOf<Map<String, Map<String, DwStageFocus>>>(emptyMap())
    }

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            val schema = repository.designWorkshopSchema(appContext)
            val draft = WorkshopDraftStore.load(appContext, workshopId)
            val local = computeWorkshopCompleteness(schema, draft)
            // Computed from the same registry and the same draft, with no network in it — which is
            // the point: the question "where is the field I am missing" is asked in a courtyard on
            // the last afternoon, not from a desk with a connection.
            addresses = DwSubmissionReadiness.addressBook(
                DwSubmissionReadiness.assess(schema, draft, workshopId)
            )

            val remoteId = draft?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            val remote = remoteId?.let {
                runCatching { repository.designWorkshop(it) }.getOrNull()
            }
            serverNote = when {
                remoteId == null -> "This workshop has not been created on the server yet."
                remote == null -> "The server could not be reached. These figures are from this device."
                else -> null
            }

            val merged = local.map { stage ->
                val touchedLocally = draft?.stages?.get(stage.stageKey)?.let {
                    it.values.isNotEmpty() || it.rows.isNotEmpty()
                } ?: false
                // Only fall back to the server for a stage this device has never touched — see the
                // KDoc. Anything else would let a stale snapshot overwrite this morning's work.
                if (touchedLocally) stage
                else remote?.completeness?.get(stage.stageKey)?.let { fromServer(it, stage) } ?: stage
            }
            (remote?.title ?: draft?.title.orEmpty()) to merged
        }.onSuccess { (name, merged) ->
            title = name
            stages = merged
        }.onFailure { onError(it.message ?: "Unable to read this workshop's progress.") }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading progress…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
            return@Column
        }

        val overall = remember(stages) { overallPercent(stages) }
        val done = remember(stages) { stages.count { it.isComplete } }

        Text(
            title.ifBlank { "Design workshop" },
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        LinearProgressIndicator(progress = { overall / 100f }, modifier = Modifier.fillMaxWidth())
        Text(
            "$overall% overall · $done of ${stages.size} stages complete",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
        serverNote?.let { Text(it, color = MaterialTheme.field.warning, fontSize = 12.sp) }

        Button(onClick = onOpenReport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Generate the report")
        }

        // OUTLINED, under the filled report button, because the report is what the workshop is FOR and
        // the cards are a tool used along the way — two filled buttons of equal weight would make a
        // designer choose between them at the moment they are looking for the report.
        OutlinedButton(onClick = onOpenCodes, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cards & tags")
        }

        HorizontalDivider()

        stages.forEach { stage ->
            StageIndexRow(
                stage = stage,
                expanded = expanded == stage.stageKey,
                addresses = addresses[stage.stageKey].orEmpty(),
                onToggle = { expanded = if (expanded == stage.stageKey) null else stage.stageKey },
                onOpen = { onOpenStage(stage.stageKey, null) },
                onOpenField = { focus -> onOpenStage(stage.stageKey, focus) }
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun StageIndexRow(
    stage: DwStageCompleteness,
    expanded: Boolean,
    /** Where each of [DwStageCompleteness.missing] lives, by label. Missing entries open the stage. */
    addresses: Map<String, DwStageFocus>,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenField: (DwStageFocus?) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
            ) {
                Text(
                    stage.number.toString().padStart(2, '0'),
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stage.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { stage.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        buildString {
                            append("${stage.percent}%")
                            if (stage.requiredTotal > 0) {
                                append(" · ${stage.requiredFilled}/${stage.requiredTotal} required")
                            } else {
                                // Said out loud rather than left as a bare 100%. A stage with nothing
                                // required reads as complete by construction, and a designer who
                                // does not know that will trust the figure and skip the stage.
                                append(" · nothing required here")
                            }
                            val rows = stage.collectionCounts.values.sum()
                            if (rows > 0) append(" · $rows entr${if (rows == 1) "y" else "ies"}")
                        },
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open stage ${stage.number}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (stage.missing.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                ) {
                    Text(
                        "${stage.missing.size} required field${if (stage.missing.size == 1) "" else "s"} still missing",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Hide what is missing" else "Show what is missing",
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        stage.missing.forEach { label ->
                            // EVERY ENTRY IS A DESTINATION, including the ones with no address: a
                            // label the registry walk could not place still opens its stage, which
                            // is the same degradation `DwSubmissionReadiness` builds into `href`.
                            // A row that does nothing when tapped is worse than a row that does
                            // less than the row beside it.
                            val focus = addresses[label]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        // Read out instead of the bare "double tap to activate":
                                        // twelve rows that all announce the same verb are twelve
                                        // rows a screen-reader user has to open to tell apart.
                                        onClickLabel = if (focus != null) {
                                            "Open this field in stage ${stage.number}"
                                        } else {
                                            "Open stage ${stage.number}"
                                        },
                                        onClick = { onOpenField(focus) }
                                    )
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    "· $label",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Adopt the server's score for a stage this device has never touched.
 *
 * [local] supplies the title and number rather than the payload, so a server one registry behind
 * cannot rename a stage on screen while the form beneath it is drawn from this build's registry —
 * which would leave the index and the stage screen disagreeing about what stage 14 is called.
 */
private fun fromServer(dto: StageCompletenessDto, local: DwStageCompleteness): DwStageCompleteness =
    local.copy(
        requiredTotal = dto.requiredTotal,
        requiredFilled = dto.requiredFilled,
        optionalTotal = dto.optionalTotal,
        optionalFilled = dto.optionalFilled,
        collectionCounts = dto.collectionCounts,
        missing = dto.missing,
    )
