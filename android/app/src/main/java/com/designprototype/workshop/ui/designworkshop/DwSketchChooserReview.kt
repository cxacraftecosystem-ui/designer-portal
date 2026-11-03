package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_ROUND_REFUSED
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwArrangementPlan
import com.designprototype.workshop.data.DwFixedOrderStamp
import com.designprototype.workshop.data.DwRateableEntity
import com.designprototype.workshop.data.DwRatingRound
import com.designprototype.workshop.data.RankedSubjectDto
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwEntryId
import com.designprototype.workshop.data.dwFixedOrderStamp
import com.designprototype.workshop.data.dwHeldOrder
import com.designprototype.workshop.data.dwMayArrange
import com.designprototype.workshop.data.dwOpeningOrder
import com.designprototype.workshop.data.dwPlanArrangement
import com.designprototype.workshop.data.dwPositionText
import com.designprototype.workshop.data.dwPushNote
import com.designprototype.workshop.data.dwReconcileOrder
import com.designprototype.workshop.data.dwRowSubtitle
import com.designprototype.workshop.data.dwScoreText
import com.designprototype.workshop.data.dwStageKeyForEntity
import com.designprototype.workshop.data.dwTodayStamp
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DwSketchChooserReviewTab(
    repository: WorkshopRepository,
    workshopId: String,
    onOpenStage: (workshopId: String, stageKey: String) -> Unit,
) {
    var entity by remember(workshopId) { mutableStateOf(DwRateableEntity.PROTOTYPE) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DwRateableEntity.entries.forEach { option ->
                val chosen = option == entity
                FilterChip(
                    selected = chosen,
                    onClick = { entity = option },
                    label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = if (chosen) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics {
                        stateDescription = if (chosen) "showing" else "not showing"
                    },
                )
            }
        }
        Text(
            entity.hint(DwRatingRound.PEER),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp,
        )

        key(workshopId, entity) {
            DwChooserPeerRound(
                repository = repository,
                workshopId = workshopId,
                entity = entity,
                onOpenStage = onOpenStage,
            )
        }
    }
}

private const val DW_CHOOSER_PUSH_QUIET_MS = 1_200L

@Composable
private fun DwChooserPeerRound(
    repository: WorkshopRepository,
    workshopId: String,
    entity: DwRateableEntity,
    onOpenStage: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<RankedSubjectDto>?>(null) }
    var order by remember { mutableStateOf<List<String>>(emptyList()) }
    var stamp by remember { mutableStateOf<DwFixedOrderStamp?>(null) }
    var held by remember { mutableStateOf<List<DraftRow>>(emptyList()) }
    var stageSpec by remember { mutableStateOf<StageDto?>(null) }
    var stageSeen by remember { mutableStateOf(false) }
    var arranged by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var orderNote by remember { mutableStateOf<String?>(null) }
    var orderProblem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var pushTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        problem = null
        val schema = runCatching { repository.designWorkshopSchema(appContext) }.getOrNull()
        val stageKey = schema?.let { dwStageKeyForEntity(it, entity.wire) }
        stageSpec = schema?.stages?.firstOrNull { it.key == stageKey }
        val stage = stageKey?.let { key ->
            runCatching { WorkshopDraftStore.load(appContext, workshopId) }.getOrNull()
                ?.stages?.get(key)
        }
        held = stage?.rowsFor(entity.wire).orEmpty()
        stageSeen = stage?.stageSeen == true
        stamp = dwFixedOrderStamp(held)

        runCatching { repository.designRatingRound(DwRatingRound.PEER.wire, workshopId, entity.wire) }
            .onSuccess { ranking ->
                items = ranking.items
                order = if (arranged && order.isNotEmpty()) {
                    dwReconcileOrder(order, ranking.items)
                } else {
                    dwOpeningOrder(ranking.items, stamp, dwHeldOrder(held))
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                items = null
                problem = if (repository.isConnectionFailure(error)) {
                    DW_SKETCH_CHOOSER_ROUND_OFFLINE
                } else {
                    error.apiErrorMessage(DW_ROUND_REFUSED)
                }
            }
        loading = false
    }

    LaunchedEffect(pushTick) {
        if (pushTick == 0) return@LaunchedEffect
        delay(DW_CHOOSER_PUSH_QUIET_MS)
        val spec = stageSpec ?: return@LaunchedEffect
        runCatching {
            WorkshopSyncEngine.pushStage(
                context = appContext,
                repository = repository,
                workshopId = workshopId,
                spec = spec,
            )
        }
            .onSuccess { orderNote = dwPushNote(it) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                orderNote = null
                orderProblem = DW_SKETCH_CHOOSER_ARRANGEMENT_NOT_SENT
            }
    }

    val ranked = items
    val byId = remember(ranked) { ranked.orEmpty().associateBy { it.subjectId } }
    val rowById = remember(held) {
        held.associateBy { row -> row.dwEntryIdOrKey() }
    }

    val seesWholeCollection = ranked != null && dwMayArrange(ranked)
    val mayOpenStage = seesWholeCollection && stageSpec != null
    val canArrange = mayOpenStage && held.isNotEmpty()

    fun persist(next: List<String>, nextStamp: DwFixedOrderStamp?) {
        val spec = stageSpec ?: run {
            orderProblem = DW_SKETCH_CHOOSER_NO_REGISTRY_FOR_ORDER
            return
        }
        when (val plan = dwPlanArrangement(held, next, nextStamp, stageSeen)) {
            is DwArrangementPlan.Refused -> {
                orderProblem = plan.reason
                orderNote = null
            }

            is DwArrangementPlan.Write -> {
                orderProblem = null
                orderNote = "Arranged. Saving it on this phone…"
                scope.launch {
                    runCatching {
                        val draft = WorkshopDraftStore.load(appContext, workshopId)
                        val stage = draft?.stages?.get(spec.key)
                            ?: error("no local copy of ${spec.key}")
                        val others = stage.rows.filterNot { it.entityKey() == entity.wire }
                        WorkshopDraftStore.updateStage(
                            appContext,
                            workshopId,
                            stage.copy(rows = others + plan.rows),
                        )
                        plan.rows
                    }
                        .onSuccess { written ->
                            held = written
                            stamp = plan.stamp
                            orderNote = if (plan.stamp == null) {
                                "Back to score order. Sending…"
                            } else {
                                "Saved on this phone. Sending it…"
                            }
                            pushTick += 1
                        }
                        .onFailure {
                            orderNote = null
                            orderProblem = DW_SKETCH_CHOOSER_ARRANGEMENT_NOT_SAVED
                        }
                }
            }
        }
    }

    fun reorder(next: List<String>) {
        arranged = true
        order = next
        val who = repository.cachedUser()
        val by = who?.name?.trim().orEmpty().ifBlank { who?.email?.trim().orEmpty() }
        persist(next, DwFixedOrderStamp(by = by, at = dwTodayStamp()))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            when {
                problem != null -> Text(
                    problem.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 18.sp,
                )

                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        "Reading this workshop's peer round…",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                ranked != null && ranked.isEmpty() -> Text(
                    DwRatingRound.PEER.emptyNote,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                )

                orderProblem != null -> Text(
                    orderProblem.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )

                orderNote != null -> Text(
                    orderNote.orEmpty(),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )

                else -> Unit
            }
        }

        if (problem != null) {
            OutlinedButton(
                onClick = { attempt++ },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Try again", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
            }
        }

        if (order.isNotEmpty()) {
            val tentativeWord = dwTentativeField(dwChooserEntity(stageSpec, entity.wire))?.label
            DwRankableList(
                order = order,
                labelFor = { id -> byId[id]?.label?.ifBlank { DW_CHOOSER_UNTITLED } ?: DW_CHOOSER_UNTITLED },
                onReorder = { reorder(it) },
                disabledReason = when {
                    canArrange -> null
                    !seesWholeCollection ->
                        "Score order. Only this workshop's designers or an admin can rearrange it."

                    stageSpec == null ->
                        "Open the workshop once online to rearrange."

                    else ->
                        "Open this stage once online to rearrange."
                },
            ) { id, position, total, _ ->
                DwChooserReviewRow(
                    item = byId[id],
                    subtitle = dwRowSubtitle(rowById[id]),
                    tentative = tentativeWord?.takeIf { dwIsTentativeRow(rowById[id]?.values) },
                    position = position,
                    total = total,
                    showPlaced = seesWholeCollection,
                    fixedOrder = stamp != null || arranged,
                    onOpenStage = if (mayOpenStage) {
                        { stageSpec?.let { spec -> onOpenStage(workshopId, spec.key) } }
                    } else {
                        null
                    },
                )
            }
        }

        Text(
            DW_SKETCH_CHOOSER_RATING_LIVES_IN_REVIEW,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun DwChooserReviewRow(
    item: RankedSubjectDto?,
    subtitle: String,
    tentative: String?,
    position: Int,
    total: Int,
    showPlaced: Boolean,
    fixedOrder: Boolean,
    onOpenStage: (() -> Unit)?,
) {
    if (item == null) return
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            "$position of $total · ${item.label.ifBlank { DW_CHOOSER_UNTITLED }}",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (tentative != null) {
            Text(tentative, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "${dwScoreText(item.score, item.ratingCount)} · " +
                dwPositionText(item, showPlaced = showPlaced, fixedOrder = fixedOrder),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp,
        )
        onOpenStage?.let { open ->
            OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.OpenInFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("Open the record", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

private fun DraftRow.dwEntryIdOrKey(): String = dwEntryId() ?: dwChooserRowKey(this)

internal const val DW_CHOOSER_UNTITLED: String = "Untitled piece"

internal const val DW_SKETCH_CHOOSER_ROUND_OFFLINE: String =
    "No connection — couldn't load the peer round. Try again."

internal const val DW_SKETCH_CHOOSER_ARRANGEMENT_NOT_SENT: String =
    "Saved. Upload didn't finish; it retries next sync."

internal const val DW_SKETCH_CHOOSER_ARRANGEMENT_NOT_SAVED: String =
    "NOT saved — this stage's local copy is gone. Reload and retry."

internal const val DW_SKETCH_CHOOSER_NO_REGISTRY_FOR_ORDER: String =
    "Not saved. Open the workshop once online, then retry."

internal const val DW_SKETCH_CHOOSER_RATING_LIVES_IN_REVIEW: String =
    "Scoring and comments are in Design review (menu)."
