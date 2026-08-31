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
import androidx.compose.material3.CircularProgressIndicator
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

/**
 * The REVIEW tab: this workshop's PEER round, in the order the designers put it in.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS IS BUILT OUT OF `DesignReviewScreen`'S PIECES AND IS NOT `DesignReviewScreen`
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `DesignReviewScreen` is a whole destination: it refuses the tier, asks WHICH WORKSHOP with a picker
 * of its own, offers a typed-id box for a workshop this account cannot list, and switches between the
 * PEER and POOL rounds. Composing it inside a tab that has ALREADY asked which workshop would put two
 * workshop choosers on one screen, one of them ignored — and its round body, `DesignReviewRound`, is
 * `private` to that file, so it cannot be reached from here at all.
 *
 * **`DesignReviewScreen.kt` IS NOT THIS CHANGE'S TO EDIT**, so this tab is built from the pieces that
 * screen is itself built from, and from no others:
 *
 *   * `repository.designRatingRound(round, workshopId, entityKey)` — the same request;
 *   * the pure rules in `data/DwDesignRatings.kt` — `dwStageKeyForEntity`, `dwFixedOrderStamp`,
 *     `dwOpeningOrder`, `dwReconcileOrder`, `dwHeldOrder`, `dwMayArrange`, `dwPlanArrangement`,
 *     `dwScoreText`, `dwPositionText`, `dwRowSubtitle`, `dwPushNote` — every one of them shared, so
 *     the two surfaces cannot come to disagree about an order or a number;
 *   * `DwRankableList` — the same drag, the same arrows, the same announcements;
 *   * `WorkshopDraftStore.updateStage` + `WorkshopSyncEngine.pushStage` — the same two calls, in the
 *     same order, with the same coalescing.
 *
 * ── WHAT IS DELIBERATELY NOT HERE, AND WHY THAT IS NOT A GAP ────────────────────────────────────
 *
 * **THE RATING FORM ITSELF.** Scores, the written note and the ledger of who said what live in
 * `DwReviewCard`, which is `private` to `DesignReviewScreen.kt`. Reimplementing it would be a second
 * rating form with its own copy of `DW_RATING_NEEDS_A_SCORE`, its own queued-rating promise and its
 * own idea of what a replay is — which is the "one feature, two implementations" failure this whole
 * screen's history is about. So this tab shows the round and hands the rating over, by name and in
 * one tap, and says so on screen rather than leaving a designer hunting for stars that are not here.
 * Making `DwReviewCard` (or `DesignReviewRound`) `internal` is a one-word change in a file this
 * lane does not own; it is reported under HANDOFF.
 *
 * **THE POOL ROUND.** Fixed to PEER, and that is a fact about the CALLER'S PERMISSION rather than a
 * missing feature — the same decision `SketchesWorkspace.tsx` records for the web's identical tab.
 * The pool round is read by designers `load_workshop_or_404` turns away, and this screen's chooser is
 * `GET /design-workshops`, which is that same door asked in list form. Offering POOL here would
 * advertise a surface whose Upload half cannot serve a pool reviewer at all. `/design-review` is
 * where the pool round lives on both clients, and it must keep being.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE ARRANGEMENT IS DURABLE AT ONCE AND THE PUSH IS COALESCED
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `DesignReviewRound`'s split, kept exactly: the draft write is a local file and costs nothing, so it
 * happens on every move and nothing can be lost; the PUSH is a metered PUT on a one-bar connection,
 * so it waits for a quiet second. Ranking eight pieces is eight cheap local writes and one request.
 *
 * **THIS COMPOSABLE MUST BE RE-KEYED ON THE WORKSHOP,** and the caller does it. `SketchesWorkspace`'s
 * header records what happens otherwise, and the hazard is identical here: the push effect closes
 * over the stage spec and the workshop, the stage key is the SAME STRING in every workshop, and a
 * designer who nudged a card in workshop A and then changed the chooser to workshop B inside the
 * quiet window would have A's arrangement written into B's draft — a wrong ordinal in a real record
 * with nothing on screen to say it happened.
 */
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
        /*
          THE TWO RATEABLE ENTITIES ARE A CHOICE, NOT TWO PANELS. `RATEABLE_ENTITIES` on the server is
          exactly {sketch, prototype}; the child rows of a prototype are refused by name with a 422,
          so offering them would be a control that can only produce a refusal. Prototypes lead —
          the web's order — because they are what a round is mostly about.
        */
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DwRateableEntity.entries.forEach { option ->
                val chosen = option == entity
                FilterChip(
                    selected = chosen,
                    onClick = { entity = option },
                    label = { Text(option.label, fontSize = 12.sp) },
                    /*
                      HOUSE RULE 4: THE FILL IS NOT THE ONLY THING SAYING WHICH CHIP IS ON, and
                      Material3's `FilterChip` gives no mark of its own — the selected state is a
                      container tint and a border, both of which vanish in greyscale, in
                      forced-colours mode and for a colour-blind reader. So the tick is passed in
                      explicitly here rather than assumed, and `stateDescription` says the same thing
                      in a word for the reader who has neither.
                    */
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
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )

        /*
          RE-KEYED ON THE WORKSHOP AND ON THE PIECE. Both change in place under this composable — the
          chooser above changes the first, the chips above change the second — and the round below
          holds a coalesced push that closes over both. `key` makes each change a dispose and a fresh
          mount, so the pending push flushes against the workshop it was made in. See the header.
        */
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

/** How long a reorder waits for quiet before the stage is offered to the repository. */
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
    /** True from the moment a card moves — which is BEFORE the stamp exists on the rows. */
    var arranged by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var orderNote by remember { mutableStateOf<String?>(null) }
    var orderProblem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    /** Bumped by every committed reorder. Debounces the PUSH; see the effect below. */
    var pushTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        problem = null
        /*
          THE REGISTRY, THEN THIS DEVICE'S ROWS, THEN THE REPOSITORY'S SCORES — `DesignReviewRound`'s
          order, kept. `designWorkshopSchema` never throws on a network failure (filesDir, then the
          copy built into the APK), so the stage this entity lives in can be named with no signal; the
          rows come from the draft store, which is the only place this device holds them and what
          makes an arrangement writable at all.
        */
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
                // A REFRESH MUST NOT UNDO A MOVE THE DESIGNER HAS JUST MADE — `dwReconcileOrder`
                // keeps the arrangement across the re-read, where `dwOpeningOrder` would recompute
                // from the stamp and put an unsaved nudge back where it came from.
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
                // A CANCELLED PUSH SAYS NOTHING. This effect's key changes on the ORDINARY gesture —
                // every committed reorder bumps `pushTick` — so a designer arranging six pieces
                // cancels five of these, and each of those would otherwise raise a failure about a
                // request that was never meant to complete.
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

    /*
      THREE QUESTIONS, ASKED IN THE ORDER THE FACTS OVERRIDE EACH OTHER — `DesignReviewRound`'s
      construction, and the ORDER is the part that took a defect to get right. `seesWholeCollection`
      is the SERVER's disclosure (the raw ordinal arrives only for the workshop's own party or an
      admin); `stageSpec` is this device's registry; `held` is this device's rows. Permission first,
      then the registry, then the rows — most fundamental to least.
    */
    val seesWholeCollection = ranked != null && dwMayArrange(ranked)
    val mayOpenStage = seesWholeCollection && stageSpec != null
    val canArrange = mayOpenStage && held.isNotEmpty()

    /** Write one arrangement to the draft, and schedule the push. */
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
                        /*
                          ONLY THIS ENTITY'S ROWS ARE REPLACED. `StageDraft.rows` is ONE flat list
                          holding every collection on the stage — stage 13 carries a prototype's stage
                          logs and its material lines beside the prototypes themselves — with the
                          entity encoded in each row's id. Writing `plan.rows` as the whole list would
                          delete every other collection, which is a fortnight of costing lines
                          destroyed by a reorder.
                        */
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
                                "Back to score order, saved on this phone. Sending it…"
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
        // THE NAME, NOT THE ACCOUNT ID. `rankFixedBy` is TEXT for a checked reason — `User` is not a
        // model a reference field resolves against, and a name is what the report can print where a
        // cuid cannot. The email is the fallback; `dwPlanArrangement` refuses a blank outright.
        val who = repository.cachedUser()
        val by = who?.name?.trim().orEmpty().ifBlank { who?.email?.trim().orEmpty() }
        persist(next, DwFixedOrderStamp(by = by, at = dwTodayStamp()))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ONE STATUS NODE, ALWAYS COMPOSED, INSIDE A LIVE REGION — the same construction the workshop
        // list's status region uses, and for the same reason: a region created in the same frame as
        // its first sentence is a region whose first sentence is never announced.
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            when {
                // COULD NOT ASK, first, so an empty round can never draw over a read that failed.
                problem != null -> Text(
                    problem.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        "Reading this workshop's peer round…",
                        color = MaterialTheme.field.muted,
                        fontSize = 13.sp,
                    )
                }

                // ANSWERED, AND THE ANSWER IS NONE — the round's own words, and an ordinary state
                // rather than a fault. Never reached while `problem` is set, which is the whole point
                // of the branch order.
                ranked != null && ranked.isEmpty() -> Text(
                    DwRatingRound.PEER.emptyNote,
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                orderProblem != null -> Text(
                    orderProblem.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                orderNote != null -> Text(
                    orderNote.orEmpty(),
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
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
                Text("Try again", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }

        if (order.isNotEmpty()) {
            /*
              ══════════════════════════════════════════════════════════════════════════════════════
              THIS LIST IS NOT PARTITIONED TENTATIVE-FIRST, AND HERE IS WHY NOT
              ══════════════════════════════════════════════════════════════════════════════════════

              `sketch.isTentative` landed on 2026-08-30 with the owner's rule that tentative sketches
              come to the top of the list. [dwTentativeFirst] carries that partition and the rule for
              where it may be applied: a surface that READS a list, never one that WRITES it. This is
              one of the two that write, and it fails the test three times over:

                * `order` IS THE WRITE. Whatever this list draws is what `dwPlanArrangement` turns
                  into rows and what `buildStageBody` then turns into `ordinal`. Partitioning the
                  display here would not be a view of the arrangement — it would BE a new one, saved,
                  and unticking the box could no longer restore a row's place.
                * AN ARRANGEMENT SOMEBODY FIXED IS A DECISION. Where `rankFixedBy`/`rankFixedAt` are
                  set a designer took responsibility for this exact order and the note above says so
                  by name; moving rows inside it on the strength of a flag nobody stamped is the
                  score-re-sorts-a-fixed-list failure the whole override rule exists to prevent.
                * THE UNFIXED LIST IS THE SCORE ORDER and the note says so in those words. It would
                  also be partitioned only HERE: `DesignReviewScreen` holds no stage rows at all, so
                  it cannot read the flag, and one list would then be ordered two ways depending on
                  which screen a designer opened it from.

              The word is drawn on the row instead — see [DwChooserReviewRow]. A reviewer choosing
              between eight sketches is owed the fact that the maker has not settled on one of them;
              what they are not owed is an order nobody chose.
            */
            val tentativeWord = dwTentativeField(dwChooserEntity(stageSpec, entity.wire))?.label
            DwRankableList(
                order = order,
                labelFor = { id -> byId[id]?.label?.ifBlank { DW_CHOOSER_UNTITLED } ?: DW_CHOOSER_UNTITLED },
                onReorder = { reorder(it) },
                /*
                  THE REASON IS ASKED IN THE ORDER THE FACTS OVERRIDE EACH OTHER, and that order is
                  `DesignReviewRound`'s hard-won one: the registry case tested FIRST told a reader who
                  may not rearrange the list at all that their phone was missing a registry. A true
                  sentence about the wrong obstacle is worse than a vague one, because it sends a
                  designer to fix something that was never in their way.
                */
                disabledReason = when {
                    canArrange -> null
                    !seesWholeCollection ->
                        "The order here is the score order, and it is not yours to rearrange: the " +
                            "placed order is the makers' own stage row order, which only that " +
                            "workshop's designers and an admin can change."

                    stageSpec == null ->
                        "This arrangement cannot be changed from here: this phone has no field " +
                            "registry for the stage these pieces live in."

                    else ->
                        "The pieces are here but the rows they are arranged in are not: this phone " +
                            "has not read the stage these ${entity.label.lowercase()} live in, so " +
                            "there is nothing on it to rearrange. Open the Upload tab beside this " +
                            "one with a connection, or open the stage, and the arrows and the drag " +
                            "handle come back."
                },
            ) { id, position, total, _ ->
                DwChooserReviewRow(
                    item = byId[id],
                    subtitle = dwRowSubtitle(rowById[id]),
                    /*
                      NULL WHEREVER IT CANNOT BE KNOWN, never `false`. This tab holds the rows, so it
                      can answer; `DesignReviewScreen` does not and draws nothing rather than
                      implying every piece is settled. Read off the registry so the word is the
                      schema's label and matches the stage form and the web exactly.
                    */
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

        // SAID ONCE, PLAINLY, AND POINTING AT WHERE THE STARS ARE. A tab that shows a round with no
        // way to rate it, and does not say where rating lives, is a tab a designer reads as broken.
        Text(
            DW_SKETCH_CHOOSER_RATING_LIVES_IN_REVIEW,
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/**
 * One piece in the round: what it is called, what it scores, and where it stands.
 *
 * DELIBERATELY NOT A SECOND `DwReviewCard`. It carries no score control, no note box and no ledger,
 * because those are that card's and it is private to `DesignReviewScreen.kt` — see this file's
 * header. What it does carry is drawn with the SHARED functions, so the number under a piece is the
 * same number the review screen prints for it.
 */
@Composable
private fun DwChooserReviewRow(
    item: RankedSubjectDto?,
    subtitle: String,
    /**
     * The registry's word for the tentative flag when this piece carries it, or null.
     *
     * A STRING RATHER THAN A BOOLEAN so the word on screen is the schema's, and NULL COVERS TWO
     * FACTS on purpose — not ticked, and this surface cannot read the rows — because the row draws
     * nothing for either and must not claim the piece is settled where it does not know. It does not
     * MOVE the row: see the argument above [DwRankableList] in this file.
     */
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
            // THE POSITION IS PRINTED, not merely implied by where the card sits. A reader who cannot
            // see the layout, and a reader comparing this against the laptop, both need the number.
            "$position of $total · ${item.label.ifBlank { DW_CHOOSER_UNTITLED }}",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        if (tentative != null) {
            // ABOVE the identifier line and not folded into it: the subtitle is the piece's IDENTITY
            // (its sketch number, its designer), and a working state written into an identifier reads
            // as part of the name. Amber — "wants attention, nothing is wrong" — the same pair the
            // stage form's row and the web's chip use, so one concept has one colour on three screens.
            Text(tentative, color = MaterialTheme.field.warning, fontSize = 12.sp)
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        Text(
            // "Not rated yet" AND NEVER "0.0" — `dwScoreText`'s rule, shared rather than restated: a
            // sketch nobody has got to has not been judged badly, it has not been judged.
            "${dwScoreText(item.score, item.ratingCount)} · " +
                dwPositionText(item, showPlaced = showPlaced, fixedOrder = fixedOrder),
            color = MaterialTheme.field.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        onOpenStage?.let { open ->
            OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.OpenInFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("Open the record", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/**
 * The identity a held row answers to in the round — its server entry id, or its own key.
 *
 * The ranking response keys on the SERVER's `_entryId`, so that is what a subtitle lookup wants. A
 * row this device minted and has not pushed has none, and falling back to its own key means it is at
 * least findable by something rather than colliding with every other unsent row under one blank
 * string — which is what `associateBy { dwEntryId() }` would have done, keeping exactly one of them.
 */
private fun DraftRow.dwEntryIdOrKey(): String = dwEntryId() ?: dwChooserRowKey(this)

// ══════════════════════════════════════════════════════════════════════════════════════════════════
// THE SENTENCES THIS TAB CAN SAY
// ══════════════════════════════════════════════════════════════════════════════════════════════════

/** What a piece with no name of its own is called. `DesignReviewScreen`'s word for the same state. */
internal const val DW_CHOOSER_UNTITLED: String = "Untitled piece"

/** COULD NOT ASK, with no signal — worded so it cannot be read as "this round is empty". */
internal const val DW_SKETCH_CHOOSER_ROUND_OFFLINE: String =
    "No connection, so this workshop's peer round could not be read — a round that could not be " +
        "asked for, not a round with nothing in it."

/** The arrangement is on the phone; the request that would carry it did not complete. */
internal const val DW_SKETCH_CHOOSER_ARRANGEMENT_NOT_SENT: String =
    "This arrangement is saved on this phone but sending it did not complete. It goes up with the " +
        "next sync — the sync tray follows it — and nothing has been lost."

/** The arrangement could not even be written locally. Nothing was claimed and nothing was stored. */
internal const val DW_SKETCH_CHOOSER_ARRANGEMENT_NOT_SAVED: String =
    "This arrangement has NOT been saved: the local copy of this stage has gone. Reload this " +
        "screen and try again — the pieces themselves are untouched."

/** No registry for the stage the pieces live in, so there is nothing to write an order into. */
internal const val DW_SKETCH_CHOOSER_NO_REGISTRY_FOR_ORDER: String =
    "This arrangement has not been saved: this phone has no field registry for the stage these " +
        "pieces live in. Open the workshop once with a connection."

/**
 * WHERE THE STARS ARE, said on the tab that does not have them.
 *
 * The tab is honest about its own shape rather than leaving a designer to conclude the feature is
 * broken — the same duty `SketchesAndPrototypesScreen`'s own KDoc records about a stale
 * "this does not exist" note: a comment, or a screen, that names a missing feature is how a reader
 * comes to look for the wrong gap.
 */
internal const val DW_SKETCH_CHOOSER_RATING_LIVES_IN_REVIEW: String =
    "This tab shows the round and the order it stands in. Giving a piece a score and writing what " +
        "you would change is Design review, in the menu — it opens the same round for the same " +
        "workshop, with the ledger of who said what."
