package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_LEDGER_REFUSED
import com.designprototype.workshop.data.DW_LEDGER_UNREACHABLE
import com.designprototype.workshop.data.DW_RATING_NEEDS_A_SCORE
import com.designprototype.workshop.data.DW_RATING_NOT_SENT
import com.designprototype.workshop.data.DW_RATING_QUEUED
import com.designprototype.workshop.data.DW_ROUND_REFUSED
import com.designprototype.workshop.data.DW_SCORES
import com.designprototype.workshop.data.DesignRatingBody
import com.designprototype.workshop.data.DesignRatingDto
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwArrangementPlan
import com.designprototype.workshop.data.DwFixedOrderStamp
import com.designprototype.workshop.data.DwRateableEntity
import com.designprototype.workshop.data.DwRatingOutcome
import com.designprototype.workshop.data.DwRatingRound
import com.designprototype.workshop.data.RankedSubjectDto
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.SubjectLedgerDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwFixedOrderStamp
import com.designprototype.workshop.data.dwHeldOrder
import com.designprototype.workshop.data.dwLedgerEmptyNote
import com.designprototype.workshop.data.dwLedgerNamesNote
import com.designprototype.workshop.data.dwMayArrange
import com.designprototype.workshop.data.dwOpeningOrder
import com.designprototype.workshop.data.dwPlanArrangement
import com.designprototype.workshop.data.dwPoolOpenCount
import com.designprototype.workshop.data.dwPositionText
import com.designprototype.workshop.data.dwPushNote
import com.designprototype.workshop.data.dwRatingAttribution
import com.designprototype.workshop.data.dwRatingClockLine
import com.designprototype.workshop.data.dwRatingDay
import com.designprototype.workshop.data.dwRatingSavedNote
import com.designprototype.workshop.data.dwReconcileOrder
import com.designprototype.workshop.data.dwRoundFailure
import com.designprototype.workshop.data.dwRowSubtitle
import com.designprototype.workshop.data.dwScoreText
import com.designprototype.workshop.data.dwEntryId
import com.designprototype.workshop.data.dwStageKeyForEntity
import com.designprototype.workshop.data.dwTodayStamp
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.designWorkshopHint
import com.designprototype.workshop.ui.designWorkshopLabel
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.requiredMarked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DesignReviewScreen(
    repository: WorkshopRepository,
    onOpenStage: (workshopId: String, stageKey: String) -> Unit,
) {
    val user = remember { repository.cachedUser() }

    if (user == null || !FieldPermissions.canRunDesignWorkshops(user)) {
        DesignReviewAccessRefusal(roleLabel = FieldPermissions.label(user?.role))
        return
    }

    var workshops by remember { mutableStateOf<List<DesignWorkshopDto>?>(null) }
    var workshopTotal by remember { mutableStateOf(0) }
    var listFailure by remember { mutableStateOf<String?>(null) }
    var workshopId by remember { mutableStateOf("") }
    var typedId by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(DwRatingRound.PEER) }
    var entity by remember { mutableStateOf(DwRateableEntity.PROTOTYPE) }

    LaunchedEffect(Unit) {
        runCatching { repository.designWorkshops(page = 1, pageSize = CHOOSER_PAGE) }
            .onSuccess { page ->
                workshops = page.items
                workshopTotal = page.total
            }
            .onFailure { error ->
                workshops = emptyList()
                listFailure = if (repository.isConnectionFailure(error)) {
                    "Offline — couldn't list workshops."
                } else {
                    error.apiErrorMessage(
                        "Couldn't list workshops. Try again."
                    )
                }
            }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Design review",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Rate sketches and prototypes, suggest changes, and see the ranking.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp,
        )

        SearchableSelectField(
            label = "A workshop you can open yourself",
            options = (workshops ?: emptyList()).map { summary ->
                SelectOption(
                    value = summary.id,
                    label = designWorkshopLabel(summary),
                    hint = listOfNotNull(
                        summary.workshopCode?.takeIf { it.isNotBlank() },
                        designWorkshopHint(summary),
                    ).joinToString(" · ").takeIf { it.isNotBlank() },
                )
            },
            selectedValue = workshopId,
            placeholder = when {
                workshops == null -> "Looking for your workshops…"
                listFailure != null -> "This list could not be loaded"
                workshops?.isEmpty() == true -> "No workshops are listed for this account"
                else -> "Choose one of your design workshops"
            },
            emptyMessage = when {
                workshops == null -> null
                listFailure != null -> "This list could not be loaded"
                workshops?.isEmpty() == true -> "No workshops are listed for this account"
                else -> null
            },
            includeNone = false,
            enabled = workshops?.isNotEmpty() == true,
            onSelect = {
                workshopId = it
                typedId = ""
            },
        )
        Text(
            "Workshops you can open. For others, paste the link or ID below.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        listFailure?.let { note ->
            Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
        }
        val hidden = (workshopTotal - (workshops?.size ?: 0)).coerceAtLeast(0)
        if (hidden > 0) {
            Text(
                "Showing ${workshops?.size ?: 0} of $workshopTotal. Paste a link or ID for others.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        OutlinedTextField(
            value = typedId,
            onValueChange = { typedId = it },
            label = { Text("Another workshop's link or ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Paste a link or ID shared by its designers.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        ActionRow {
            OutlinedButton(
                enabled = typedId.isNotBlank(),
                onClick = {
                    workshopId = WORKSHOP_URL.find(typedId.trim())?.groupValues?.get(1)
                        ?: typedId.trim()
                },
            ) { Text("Open this round") }
        }

        if (workshopId.isBlank()) {
            Text(
                "Choose a workshop above, or paste a link.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
            )
            return@Column
        }

        HorizontalDivider()

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            DwRatingRound.entries.forEach { option ->
                FilterChip(
                    selected = round == option,
                    onClick = { round = option },
                    label = {
                        Text(
                            if (option == DwRatingRound.PEER) "Peer review" else "The wider pool",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            DwRateableEntity.entries.forEach { option ->
                FilterChip(
                    selected = entity == option,
                    onClick = { entity = option },
                    label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Text(
            entity.hint(round),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        androidx.compose.runtime.key(workshopId, round, entity) {
            DesignReviewRound(
                repository = repository,
                workshopId = workshopId,
                round = round,
                entity = entity,
                onOpenStage = onOpenStage,
            )
        }
    }
}

private const val CHOOSER_PAGE = 100

private val WORKSHOP_URL = Regex("""design-workshops/([^/?#]+)""")

@Composable
private fun DesignReviewAccessRefusal(roleLabel: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Design review",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Designer access required",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            "Design review is for designers and admins.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
        )
        Text(
            "You're signed in as $roleLabel. Ask an admin for access.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DesignReviewRound(
    repository: WorkshopRepository,
    workshopId: String,
    round: DwRatingRound,
    entity: DwRateableEntity,
    onOpenStage: (String, String) -> Unit,
) {
    val context = LocalContext.current
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
    var offline by remember { mutableStateOf(false) }
    var orderNote by remember { mutableStateOf<String?>(null) }
    var orderProblem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var pushTick by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        problem = null
        offline = false
        val schema = runCatching { repository.designWorkshopSchema(context) }.getOrNull()
        val stageKey = schema?.let { dwStageKeyForEntity(it, entity.wire) }
        stageSpec = stageKey?.let { key -> schema.stages.firstOrNull { it.key == key } }
        val stage = stageKey?.let {
            runCatching { WorkshopDraftStore.load(context, workshopId) }.getOrNull()?.stages?.get(it)
        }
        held = stage?.rowsFor(entity.wire).orEmpty()
        stageSeen = stage?.stageSeen == true
        stamp = dwFixedOrderStamp(held)

        var rankingCachedAt: String? = null
        var rankingFromCache = false
        runCatching {
            com.designprototype.workshop.data.ReadCache.read(
                context.applicationContext,
                "rating-round:${round.wire}:$workshopId:${entity.wire}",
                com.designprototype.workshop.data.RoundRankingDto.serializer(),
            ) { repository.designRatingRound(round.wire, workshopId, entity.wire) }
                .also { rankingFromCache = it.fromCache; rankingCachedAt = it.cachedAt }
                .value
        }
            .onSuccess { ranking ->
                if (rankingFromCache) {
                    offline = true
                    problem = com.designprototype.workshop.data.readCacheNotice(rankingCachedAt)
                }
                items = ranking.items
                order = if (arranged && order.isNotEmpty()) {
                    dwReconcileOrder(order, ranking.items)
                } else {
                    dwOpeningOrder(ranking.items, stamp, dwHeldOrder(held))
                }
            }
            .onFailure { error ->
                items = null
                val unreachable = repository.isConnectionFailure(error)
                val failure = dwRoundFailure(
                    offline = unreachable,
                    refusal = if (unreachable) "" else error.apiErrorMessage(DW_ROUND_REFUSED),
                )
                offline = failure.offline
                problem = failure.message
            }
        loading = false
    }

    LaunchedEffect(pushTick) {
        if (pushTick == 0) return@LaunchedEffect
        delay(PUSH_QUIET_MS)
        val spec = stageSpec ?: return@LaunchedEffect
        runCatching {
            WorkshopSyncEngine.pushStage(
                context = context,
                repository = repository,
                workshopId = workshopId,
                spec = spec,
            )
        }
            .onSuccess { orderNote = dwPushNote(it) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                orderNote = if (repository.isConnectionFailure(error)) {
                    "Saved. Uploads when you're online."
                } else {
                    "Saved. Upload didn't finish; it retries next sync."
                }
            }
    }

    val byId = remember(items) { (items ?: emptyList()).associateBy { it.subjectId } }
    val rowById = remember(held) { held.associateBy({ it.dwEntryId() ?: "" }, { it }) }
    val ranked = items

    val seesWholeCollection = ranked != null && dwMayArrange(ranked)
    val mayOpenStage = seesWholeCollection && stageSpec != null
    val canArrange = mayOpenStage && held.isNotEmpty()

    fun persist(next: List<String>, nextStamp: DwFixedOrderStamp?) {
        val spec = stageSpec
        if (spec == null) {
            orderProblem =
                "Not saved — open the workshop once online, then retry."
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
                        val draft = WorkshopDraftStore.load(context, workshopId)
                        val stage = draft?.stages?.get(spec.key)
                            ?: error("no local copy of ${spec.key}")
                        val others = stage.rows.filterNot { it.entityKey() == entity.wire }
                        WorkshopDraftStore.updateStage(
                            context,
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
                            orderProblem =
                                "Couldn't save on this phone. Reload and retry."
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

    fun returnToDefault() {
        val current = items ?: return
        arranged = false
        val next = dwOpeningOrder(current, null)
        order = next
        persist(next, null)
    }

    val specForOpen = stageSpec
    val openStageHere: (() -> Unit)? = if (mayOpenStage && specForOpen != null) {
        { onOpenStage(workshopId, specForOpen.key) }
    } else {
        null
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    round.title,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    round.blurb,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
            OutlinedButton(enabled = !loading, onClick = { attempt += 1 }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Refresh", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    stamp != null -> {
                        Text(
                            "Order set by ${stamp?.by} on ${dwRatingDay(stamp?.at)}. New ratings won't move it.",
                            color = MaterialTheme.field.body,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 17.sp,
                        )
                        if (canArrange) {
                            ActionRow {
                                OutlinedButton(onClick = { returnToDefault() }) {
                                    Text("Return to score order", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    arranged -> Text(
                        "You've arranged this list; it's recorded under your name.",
                        color = MaterialTheme.field.body,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                    canArrange -> Text(
                        "Highest score first; unrated last. Moving one makes the order yours.",
                        color = MaterialTheme.field.body,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                    else -> Text(
                        "Highest score first; unrated last.",
                        color = MaterialTheme.field.body,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                }
                orderNote?.let {
                    Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
                }
                orderProblem?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
            },
        ) {
            problem?.let { note ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (offline) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        note,
                        color = if (offline) MaterialTheme.field.body else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                }
            }
        }

        if (loading && items == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading this round…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (offline && held.isNotEmpty()) {
            held.forEachIndexed { index, row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        "${index + 1}",
                        color = MaterialTheme.field.body,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.rowName(),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            dwRowSubtitle(row).ifBlank {
                                "On this phone. Scores load when online."
                            },
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (ranked != null && ranked.isEmpty()) {
            Text(
                round.emptyNote,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
            )
            if (round == DwRatingRound.POOL && held.isNotEmpty()) {
                val open = dwPoolOpenCount(held)
                Text(
                    if (open == 0) {
                        "None of the ${held.size} ${entity.label.lowercase()} have a “Peer review closed on” date, which opens them to others. Set it on each piece's stage."
                    } else {
                        "$open of ${held.size} ${entity.label.lowercase()} have that date. Missing ones may still be uploading."
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        if (ranked != null && ranked.isNotEmpty()) {
            DwRankableList(
                order = order,
                labelFor = { byId[it]?.label?.ifBlank { UNTITLED } ?: UNTITLED },
                onReorder = { reorder(it) },
                disabledReason = when {
                    canArrange -> null
                    !seesWholeCollection ->
                        "Score order. Only this workshop's designers or an admin can rearrange it."
                    stageSpec == null ->
                        "Open the workshop once online to rearrange."
                    else ->
                        "Open any card once online (“Open the record”) to rearrange."
                },
            ) { id, _, _, _ ->
                val item = byId[id]
                if (item != null) {
                    DwReviewCard(
                        repository = repository,
                        item = item,
                        subtitle = dwRowSubtitle(rowById[id]),
                        round = round,
                        showPlaced = seesWholeCollection,
                        fixedOrder = stamp != null || arranged,
                        openStage = openStageHere,
                        onRated = { rating ->
                            items = items?.map { existing ->
                                if (existing.subjectId == id) {
                                    existing.copy(myRating = rating)
                                } else {
                                    existing
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private const val PUSH_QUIET_MS = 1200L

private const val UNTITLED = "Untitled piece"

private fun DraftRow.rowName(): String {
    val value = values["name"]
    val text = (value as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it !is kotlinx.serialization.json.JsonNull && it.isString }
        ?.content
        ?.trim()
        .orEmpty()
    return text.ifBlank { UNTITLED }
}

@Composable
private fun DwReviewCard(
    repository: WorkshopRepository,
    item: RankedSubjectDto,
    subtitle: String,
    round: DwRatingRound,
    showPlaced: Boolean,
    fixedOrder: Boolean,
    openStage: (() -> Unit)?,
    onRated: (DesignRatingDto) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mine = item.myRating

    var score by remember(mine?.score) { mutableStateOf(mine?.score) }
    var comment by remember(mine?.comment) { mutableStateOf(mine?.comment.orEmpty()) }
    var suggestion by remember(mine?.suggestion) { mutableStateOf(mine?.suggestion.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var ledgerOpen by remember { mutableStateOf(false) }
    var ledger by remember { mutableStateOf<SubjectLedgerDto?>(null) }
    var ledgerProblem by remember { mutableStateOf<String?>(null) }
    var ledgerLoading by remember { mutableStateOf(false) }
    var ledgerTick by remember { mutableStateOf(0) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            item.label.ifBlank { UNTITLED },
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                dwScoreText(item.score, item.ratingCount),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            dwPositionText(item, showPlaced = showPlaced, fixedOrder = fixedOrder),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        openStage?.let { open ->
            ActionRow {
                OutlinedButton(onClick = open) { Text("Open the record", style = MaterialTheme.typography.labelMedium) }
            }
        }

        HorizontalDivider()

        Text("Your score for this piece", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            DW_SCORES.forEach { value ->
                val chosen = score == value
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .background(
                            if (chosen) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = if (chosen) 2.dp else 1.dp,
                            color = if (chosen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.field.hairline
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                        .selectable(
                            selected = chosen,
                            role = Role.RadioButton,
                            onClick = { score = value },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (chosen) "$value chosen" else "$value",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        DwReviewTextBox(
            value = comment,
            onValueChange = { comment = it },
            label = "What you think of it",
            placeholder = "Your assessment of the piece as it stands.",
        )
        DwReviewTextBox(
            value = suggestion,
            onValueChange = { suggestion = it },
            label = "What you would change",
            placeholder = "A suggestion the maker can act on.",
        )

        Text(
            "Ratings and comments aren't printed in the report — only the final order. Put report content on the piece's stage.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
            },
        ) {
            problem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
            }
        }
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            saved?.let { (text, queued) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (queued) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text,
                        color = if (queued) MaterialTheme.field.body else MaterialTheme.field.success,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                enabled = !saving,
                onClick = {
                    val chosen = score
                    if (chosen == null) {
                        problem = DW_RATING_NEEDS_A_SCORE
                        return@OutlinedButton
                    }
                    saving = true
                    problem = null
                    saved = null
                    scope.launch {
                        runCatching {
                            repository.submitDesignRating(
                                context = context,
                                body = DesignRatingBody(
                                    subjectId = item.subjectId,
                                    round = round.wire,
                                    score = chosen,
                                    comment = comment.trim().ifBlank { null },
                                    suggestion = suggestion.trim().ifBlank { null },
                                    ratedAt = null,
                                ),
                                label = "Rating · ${item.label.ifBlank { UNTITLED }}",
                            )
                        }
                            .onSuccess { outcome ->
                                when (outcome) {
                                    is DwRatingOutcome.Sent -> {
                                        onRated(outcome.saved.rating)
                                        saved = dwRatingSavedNote(
                                            replayed = outcome.saved.replayed,
                                            amended = mine != null,
                                        ) to false
                                        if (ledgerOpen) {
                                            ledger = null
                                            ledgerTick += 1
                                        }
                                    }
                                    DwRatingOutcome.Queued -> saved = DW_RATING_QUEUED to true
                                }
                            }
                            .onFailure { error ->
                                problem = error.apiErrorMessage(DW_RATING_NOT_SENT)
                            }
                        saving = false
                    }
                },
            ) {
                Text(if (mine != null) "Amend my rating" else "Submit my rating", style = MaterialTheme.typography.bodySmall)
            }
            if (mine != null) {
                Text(
                    "You rated this ${mine.score} on ${dwRatingDay(mine.ratedAt ?: mine.createdAt)}.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = if (ledgerOpen) "Expanded" else "Collapsed"
                }
                .clickable(
                    onClickLabel = if (ledgerOpen) {
                        "Hide who rated this piece"
                    } else {
                        "Show who rated this piece"
                    },
                ) { ledgerOpen = !ledgerOpen }
                .padding(vertical = 4.dp),
        ) {
            Icon(
                if (ledgerOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Who rated this, when and how",
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (ledgerOpen) {
            LaunchedEffect(item.subjectId, round, ledgerTick) {
                if (ledger != null || ledgerLoading) return@LaunchedEffect
                ledgerLoading = true
                ledgerProblem = null
                runCatching {
                    com.designprototype.workshop.data.ReadCache.read(
                        context.applicationContext,
                        "rating-ledger:${item.subjectId}:${round.wire}",
                        com.designprototype.workshop.data.SubjectLedgerDto.serializer(),
                    ) { repository.designRatingLedger(item.subjectId, round.wire) }
                }
                    .onSuccess { read ->
                        ledger = read.value
                        if (read.fromCache) {
                            ledgerProblem = com.designprototype.workshop.data.readCacheNotice(read.cachedAt)
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        ledgerProblem = if (repository.isConnectionFailure(error)) {
                            DW_LEDGER_UNREACHABLE
                        } else {
                            error.apiErrorMessage(DW_LEDGER_REFUSED)
                        }
                    }
                ledgerLoading = false
            }
            if (ledgerLoading) {
                Text(
                    "Reading the review history…",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ledgerProblem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
            }
            ledger?.let { DwLedgerBlock(it) }
        }
    }
}

private const val MAX_REVIEW_TEXT = 4000

@Composable
private fun DwReviewTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    var dropped by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { typed ->
                dropped = (typed.length - MAX_REVIEW_TEXT).coerceAtLeast(0)
                onValueChange(typed.take(MAX_REVIEW_TEXT))
            },
            label = { Text(requiredMarked(label)) },
            placeholder = { Text(placeholder) },
            minLines = 2,
            isError = dropped > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            if (dropped > 0) {
                Text(
                    "$dropped character${if (dropped == 1) "" else "s"} cut — max $MAX_REVIEW_TEXT.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun DwLedgerBlock(ledger: SubjectLedgerDto) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        dwLedgerEmptyNote(ledger)?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
        }
        dwLedgerNamesNote(ledger)?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
        }
        ledger.ratings.forEach { rating ->
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${rating.score ?: "—"}/5",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        dwRatingAttribution(rating),
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    dwRatingClockLine(rating),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                rating.comment?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                }
                rating.suggestion?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Suggested: $it",
                        color = MaterialTheme.field.body,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}
