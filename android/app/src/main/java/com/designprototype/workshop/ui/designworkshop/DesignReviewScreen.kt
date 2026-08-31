package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material3.CircularProgressIndicator
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

/**
 * DESIGN REVIEW on the handset — the second half of Sketches & Prototypes, which until now was web
 * only.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS IS, AND WHY IT IS ONE SCREEN WHERE THE WEB HAS TWO
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The owner's rule, in their words: *"designers rate peers' work qualitatively and quantitatively,
 * leave suggestions, and RANK sketches and prototypes by drag-and-drop AND by up/down arrows —
 * sorted by score by default, with the designer having the final say"*, over *"two review levels:
 * workshop peers first, then the whole pool of designers once prototypes are finalised"*.
 *
 * The web spends two surfaces on that: a Review tab inside the workshop (the PEER round, where the
 * stage rows are already in hand) and a top-level `/design-review` page (the POOL round, where they
 * are not). A laptop can afford both. A handset has one column and one menu, and a designer who has
 * to guess which of two screens holds the round they want has been given a worse product than one
 * chooser — so the round is a CONTROL here, and every sentence that differed between the two web
 * surfaces is selected by [DwRatingRound] rather than blended into something vaguer. The frontend
 * contract's rule for this port is exactly that: where a platform genuinely differs, comment the
 * difference and pick the equivalent shape; never paraphrase the copy.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * TWO WAYS IN, AND THE SHORTCUT IS NARROWER THAN THE ROUND. THE COPY SAYS SO
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The picker lists `GET /design-workshops`, which scopes rows to what this account can already open
 * (its own, plus anything an admin granted it — the whole archive for an admin). The POOL round is
 * by construction WIDER than that: `load_ratable_workshop_or_404` lets any design-workshop role
 * through the door and `pool_visible` then keeps the pieces whose `peerRoundClosedAt` is set. So the
 * two sets are not nested the flattering way, and a workshop's absence from the picker says nothing
 * about whether its round can be read.
 *
 * That is why the id box below the picker is not a fallback. It is the ONLY route to the set this
 * feature exists for — a piece opened to the pool by a workshop this account is not in — and it is
 * the route that still works when the list fails. The sentence beside it is the web's, and it admits
 * what does not exist: there is no endpoint listing the workshops that have opened something, so
 * browsing the archive is still a different question with no answer.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT A REFUSAL LOOKS LIKE, AND WHY THIS SCREEN NEVER EXPLAINS ONE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A workshop with nothing finished, a workshop this caller may not reach, and an id that never
 * existed all answer 404 with one sentence. That is the API's decision and this screen does not try
 * to tell them apart: the archive is keyed by cuid, and a client that distinguished them would turn
 * any designer login into an enumeration of the ministry's records one paste at a time.
 *
 * The one refusal this screen states for itself is the tier, and it is a MIRROR of the API's own
 * first line rather than a narrowing: `load_ratable_workshop_or_404` opens with "if not
 * can_run_design_workshops(user): raise not_found", so nobody is stopped here whom the server would
 * have served. Without saying it on the screen, a field contributor who reached this destination got
 * the whole shell and then a 404 that reads as a broken page rather than a locked one.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHERE AN ARRANGEMENT GOES
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Into the local draft, and then up with the ordinary stage push — see `DwDesignRatings`' header and
 * `dwPlanArrangement`. There is no reorder endpoint and there should not be one: the ordinal is
 * written by the stage save, inside the transaction that writes the rest of the stage. So this screen
 * makes the same two calls the stage screen makes and inherits the whole protocol nobody should
 * reimplement — including the reason returning to score order is sometimes REFUSED with a sentence
 * instead of being reported as done.
 */
@Composable
fun DesignReviewScreen(
    repository: WorkshopRepository,
    /**
     * Open the stage that owns a piece — the handset's "Open the record".
     *
     * Offered only where the caller may actually open it, which is the same set that gets the raw
     * ordinal; see [dwMayArrange]. A button that led to a 404 would be worse than no button.
     */
    onOpenStage: (workshopId: String, stageKey: String) -> Unit,
) {
    val user = remember { repository.cachedUser() }

    // ── The tier, refused first and in the API's own words ─────────────────────────────────────
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
                /*
                  NO `CancellationException` RETHROW HERE, AND THE REASON IS THE KEY. `Unit` never
                  changes, so the only thing that cancels this coroutine is the screen leaving — at
                  which moment `workshops` and `listFailure` are discarded with the composable that
                  remembers them, and nothing here calls a hoisted callback that could surface on the
                  screen the designer moved ON to. The ledger read and the arrangement push in this
                  file DO rethrow, because both of their keys change in place; add the same guard here
                  the moment this effect takes a key that can.
                */
                // `emptyList()` and NOT left null: null is "still asking" below, and a failed load
                // that stayed null would spin for ever. The id box does not go through this request
                // and keeps working, which the sentence beside it says.
                workshops = emptyList()
                listFailure = if (repository.isConnectionFailure(error)) {
                    "The repository could not be reached, so this shortcut is empty — a list that " +
                        "could not be loaded, not a list with nothing in it. Reading a round needs " +
                        "the same connection, so the box below will not reach one either until the " +
                        "signal is back."
                } else {
                    error.apiErrorMessage(
                        "The repository could not list the design workshops you can open."
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
            fontSize = 22.sp,
        )
        /*
          THE WEB'S PAGE DESCRIPTION, VERBATIM, INCLUDING THE CLAUSE THAT USED TO BE WRONG. It read
          "declared finished, opened to every designer on the platform" — which describes only
          `pool_visible`'s STRANGER branch. A member of the workshop and any admin get the WHOLE
          collection, `peerRoundClosedAt` irrelevant, and nothing on the wire marks which rows were
          opened. A description that is false for the default path is worse than a vague one: it
          teaches a reader that a piece on this screen has been released when for their own workshops
          it has not.
        */
        Text(
            "Sketches and prototypes from a workshop's round: for a workshop you are a member of, " +
                "or any workshop if you are an admin, the same pieces its own stages list; for " +
                "everyone else, the ones it has declared finished and opened beyond itself. Rate " +
                "them, say what you would change, and see where the scores put them.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )

        // ── Way in one: the workshops this account can already open ────────────────────────────
        SearchableSelectField(
            label = "A workshop you can open yourself",
            options = (workshops ?: emptyList()).map { summary ->
                SelectOption(
                    value = summary.id,
                    // `designWorkshopLabel`, not the title-plus-date this call used to build by
                    // hand: the shared vocabulary's rule is that the label is the title ALONE (see
                    // `WorkshopOptions.kt`) precisely so a designer meets one description of a
                    // workshop across the app, and the date belongs in the hint below instead.
                    label = designWorkshopLabel(summary),
                    // The workshop CODE ahead of the shared status/craft/place/date hint — same
                    // composition `SketchesAndPrototypesScreen`'s sibling picker uses, so the same
                    // workshop reads the same way in both design-workshop choosers. Still rides in
                    // the hint, which this picker also SEARCHES, so a code sent in a message can be
                    // typed straight into the filter.
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
            /*
              MIRRORS `placeholder` ABOVE, AND THAT DUPLICATION IS THE FIX. `emptyMessage` is a
              SEPARATE parameter from `placeholder` — `SearchableSelectField`'s own KDoc on
              `emptyMessage` names this exact call site as the one that proved the harm of leaving
              it unset: `SelectTrigger.speech` (`SearchableSelect.kt`) builds the TalkBack
              announcement from `label` and `emptyMessage` alone and never reads `placeholder` at
              all, so a screen reader heard only "A workshop you can open yourself. Nothing
              selected." — never "This list could not be loaded" or the scoped-empty sentence a
              sighted designer reads a breath away. `null` while still asking, exactly as
              `placeholder`'s first branch is not a claim either: `emptyMessage` is only ever spoken
              or drawn once `options.isEmpty()`, so a still-loading list keeps saying nothing rather
              than announcing a state it has not reached yet.
            */
            emptyMessage = when {
                workshops == null -> null
                listFailure != null -> "This list could not be loaded"
                workshops?.isEmpty() == true -> "No workshops are listed for this account"
                else -> null
            },
            // No "none" row: emptying the picker would leave the screen with no round open and a
            // control implying that is a state worth choosing. The way to a different workshop is
            // another workshop, or the box below.
            includeNone = false,
            enabled = workshops?.isNotEmpty() == true,
            onSelect = {
                workshopId = it
                typedId = ""
            },
        )
        Text(
            "This is a shortcut, not a list of what is open to the pool. It holds the design " +
                "workshops this account can already open — the ones you created, the ones an admin " +
                "granted you, and every workshop on the platform if you are an admin. The pool " +
                "round is wider than that by design: any workshop can declare a piece finished and " +
                "open it to designers outside it, and nothing lists those workshops. A workshop " +
                "missing from this list is not a workshop you cannot read.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        listFailure?.let { note ->
            Text(note, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 17.sp)
        }
        // RULE 10: EVERY CAP SAYS SO. A designer with two hundred workshops must not read a hundred
        // as "these are mine" — and the sentence names the route to the rest rather than leaving the
        // absence to be read as non-existence.
        val hidden = (workshopTotal - (workshops?.size ?: 0)).coerceAtLeast(0)
        if (hidden > 0) {
            Text(
                "Showing the first ${workshops?.size ?: 0} of $workshopTotal you can open. Reach " +
                    "any of the rest from its link or its id below.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        // ── Way in two: the box, which is the only way to everything else ──────────────────────
        OutlinedTextField(
            value = typedId,
            onValueChange = { typedId = it },
            label = { Text("Or any other workshop, from its link or its id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The round is read one workshop at a time, because the ranking it shows is that " +
                "workshop's own row order and there is no such thing as a place across two " +
                "workshops. What does not exist yet is a list of every workshop that has opened a " +
                "piece to the pool — so browsing the whole archive is still a different question " +
                "with no answer, and a piece made outside your own workshops reaches you as a link " +
                "its designers sent you.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        OutlinedButton(
            enabled = typedId.isNotBlank(),
            onClick = {
                /*
                  A PASTED LINK IS ACCEPTED AS WELL AS A BARE ID, exactly as the web accepts one. A
                  designer sent "come and look at this" has the workshop's URL on their clipboard and
                  not its cuid, and refusing it would send them editing a string by hand. Anything
                  that is not a workshop URL is passed through untouched and the API answers for it.

                  THE BOX IS NOT CLEARED. The only feedback a wrong id gets is the round's
                  one-sentence 404 — which by design cannot say whether the id was mistyped, revoked
                  or never existed — so the reader needs the string they pasted still in front of
                  them to compare against the link they were sent.
                */
                workshopId = WORKSHOP_URL.find(typedId.trim())?.groupValues?.get(1)
                    ?: typedId.trim()
            },
        ) { Text("Open this round") }

        if (workshopId.isBlank()) {
            Text(
                "Nothing is open yet. Choose one of your own workshops above, or paste the link a " +
                    "workshop's designers sent you, to read its sketches and prototypes.",
                color = MaterialTheme.field.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            return@Column
        }

        HorizontalDivider()

        // ── Which round, and which kind of piece ───────────────────────────────────────────────
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
                            fontSize = 12.sp,
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
                    label = { Text(option.label, fontSize = 12.sp) },
                )
            }
        }
        Text(
            entity.hint(round),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        /*
          THE ROUND ITSELF, KEYED SO A CHANGE OF WORKSHOP, ROUND OR PIECE IS A FRESH SCREEN.

          `key(...)` and not three `LaunchedEffect` dependencies inside one body: the arrangement, the
          stamp and the held rows are all properties of ONE (workshop, round, entity) and carrying any
          of them across a change would show a designer the previous round's order under this round's
          scores. A new key is a new state block, which is the same reason `StageScreen`'s page scroll
          is `key(screen)`-ed.
        */
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

/**
 * How many workshops the shortcut asks for.
 *
 * The SERVER's ceiling and not a preference: `normalize_pagination` clamps `pageSize` to 100, so
 * asking for 500 silently returns 100 and would leave this screen believing it held the whole of
 * something. Asking for exactly the ceiling means the picker's own filter — which searches the rows
 * it was handed — is searching as much of the archive as one request can hold, and the one
 * truncation sentence below the picker describes the rest.
 *
 * THIS NUMBER GOVERNS THE SIZE OF A CONTROL AND NOTHING ELSE. It is not a boundary of what may be
 * read: an id outside these rows opens exactly as one inside them does. That is the one thing it must
 * never come to mean — the web's sketches page shipped with this same constant deciding a refusal,
 * and told designers their own workshops did not exist.
 */
private const val CHOOSER_PAGE = 100

/** The id inside a pasted workshop URL. See the paste handler. */
private val WORKSHOP_URL = Regex("""design-workshops/([^/?#]+)""")

// `workshopLabel` used to live here, building "$title · $day" by hand — retired 2026-08-30 in
// favour of `designWorkshopLabel` (title alone, matching every other picker) plus `designWorkshopHint`
// (which carries the day, alongside the status word this local copy never had) at the one call site
// above that used it. See `WorkshopOptions.kt`'s own file header on why a workshop's label must not
// vary by which picker on the phone is drawing it.

/**
 * The tier refusal — the API's own first line, said before the shell is drawn.
 *
 * It names the tier and offers nothing to press, because there is nothing here for this account to
 * do; the way in is an admin raising their access. The wording is the web's guard panel.
 */
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
            fontSize = 22.sp,
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
                fontSize = 15.sp,
            )
        }
        Text(
            "Rating another workshop's finished pieces is part of the design work itself, so it " +
                "belongs to designers, admins and the master admin. The rounds are read through a " +
                "route that refuses everybody else before it looks at the workshop at all.",
            color = MaterialTheme.field.muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Text(
            "You are signed in as $roleLabel. An admin can raise your access.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
        )
    }
}

/**
 * One round of one workshop: the banner, the list, and the arrangement.
 *
 * Split out from the chooser above so that the whole of its state is created and destroyed with the
 * (workshop, round, entity) it belongs to — see the `key(...)` at the call site.
 */
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
    /** True from the moment a card moves — which is BEFORE the stamp exists on the rows. */
    var arranged by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var orderNote by remember { mutableStateOf<String?>(null) }
    var orderProblem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    /** Bumped by every committed reorder. Debounces the PUSH; see the effect below. */
    var pushTick by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        problem = null
        offline = false
        /*
          THE REGISTRY, THEN THIS DEVICE'S ROWS, THEN THE REPOSITORY'S SCORES — in that order.

          `designWorkshopSchema` never throws on a network failure (it answers from filesDir, then
          from the copy built into the APK), so the stage this entity lives in can be named on a phone
          with no signal. The rows come from the draft store, which is the only place this device
          holds them, and they are what makes an arrangement writable at all.

          NONE OF THE THREE `runCatching`S BELOW RETHROWS A CANCELLATION, AND THAT WAS CHECKED RATHER
          THAN ASSUMED. This effect cannot be cancelled in place: `attempt` is bumped only by the
          Refresh button, which is `enabled = !loading` and therefore unpressable for exactly as long
          as this body runs, and a change of workshop, round or entity destroys this whole composable
          through the `key(...)` at its call site. So the only cancellation is disposal, and every
          write here — `problem`, `offline`, `held`, `loading` — is state that dies with the composable
          in the same breath. Nothing hoisted is called from this effect either. Give `attempt` a
          sibling key that can change while a load is in flight and all three need the guard, because
          then a cancelled run's tail would clear `loading` and raise a refusal over a live read.
        */
        val schema = runCatching { repository.designWorkshopSchema(context) }.getOrNull()
        val stageKey = schema?.let { dwStageKeyForEntity(it, entity.wire) }
        stageSpec = stageKey?.let { key -> schema.stages.firstOrNull { it.key == key } }
        val stage = stageKey?.let {
            runCatching { WorkshopDraftStore.load(context, workshopId) }.getOrNull()?.stages?.get(it)
        }
        held = stage?.rowsFor(entity.wire).orEmpty()
        stageSeen = stage?.stageSeen == true
        stamp = dwFixedOrderStamp(held)

        runCatching { repository.designRatingRound(round.wire, workshopId, entity.wire) }
            .onSuccess { ranking ->
                items = ranking.items
                /*
                  A REFRESH MUST NOT UNDO A MOVE THE DESIGNER HAS JUST MADE. `dwReconcileOrder` keeps
                  the arrangement on screen across the re-read — dropping pieces that have gone and
                  appending ones that are new — where `dwOpeningOrder` would recompute from the stamp
                  and put an unsaved nudge back where it came from. The `arranged` guard is what tells
                  the two cases apart, and it is reset only by a change of workshop, round or piece,
                  each of which re-keys this whole block.
                */
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

    /*
      THE ARRANGEMENT IS DURABLE AT ONCE AND THE PUSH IS COALESCED, which is a different split from
      the web's and a better one for a phone.

      The web coalesces BOTH: a nudge moves the list and the whole write waits for a quiet second, so
      a tab closed inside that window loses the last move. Here the draft write is a local file and
      costs nothing, so it happens on every move and nothing can be lost — while the PUSH, which is a
      metered PUT on a one-bar connection, waits. Ranking eight pieces is therefore eight cheap local
      writes and one request, with nothing disabled in between.

      RE-KEYED ON EVERY REORDER, so each new move cancels the previous delay. That is the debounce:
      `LaunchedEffect` cancels its coroutine when its key changes.

      AND IF THE SCREEN IS LEFT INSIDE THE WINDOW, NOTHING IS LOST AND NOTHING IS CLAIMED. The
      arrangement is already on disk; `buildStageBody` derives the ordinal from the row order and the
      pass's signature check will see it differs from what was last sent, so the ordinary background
      sync carries it. That is why this effect does not need to survive the composition, and why
      nothing here promises the repository has it until a push has answered.
    */
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
                /*
                  A CANCELLED PUSH SAYS NOTHING, because this effect's key changes on the ORDINARY
                  gesture. `pushTick` is bumped by every committed reorder, so a designer arranging
                  eight pieces cancels the previous run seven times — and where that cancellation
                  lands after `delay` has already elapsed, it lands here rather than at the `delay`.

                  `runCatching` catches `CancellationException`, and `isConnectionFailure` answers
                  false for it, so it fell through to the OTHER branch and told a designer with four
                  bars that "sending it did not complete" over a push that was superseded a moment
                  later by the very next move. Rethrown, the replacement run owns the note — which is
                  exactly what the paragraph above promises: nothing is lost and nothing is claimed.
                */
                if (error is CancellationException) throw error
                // The arrangement IS on this device — that happened before this effect ran and is not
                // in doubt here. Only the sending is, so only the sending is what this sentence is
                // about.
                orderNote = if (repository.isConnectionFailure(error)) {
                    "Saved on this phone. There is no connection, so it sends itself when one returns."
                } else {
                    "Saved on this phone, but sending it did not complete. It goes up with the next " +
                        "sync — the sync tray follows it."
                }
            }
    }

    val byId = remember(items) { (items ?: emptyList()).associateBy { it.subjectId } }
    val rowById = remember(held) { held.associateBy({ it.dwEntryId() ?: "" }, { it }) }
    val ranked = items

    /*
      ══════════════════════════════════════════════════════════════════════════════════════════════
      THREE QUESTIONS AND NOT ONE, BECAUSE THEY HAVE THREE DIFFERENT ANSWERS ON THIS CLIENT
      ══════════════════════════════════════════════════════════════════════════════════════════════

      These were one `canArrange` until 2026-08-26, and collapsing them shipped two defects that only
      show up on the handset. The web keeps them apart deliberately — `ReviewPanel` passes
      `showPlaced={readsStageRows}` while gating its controls on a separate `canArrange` — and the
      reason it can get away with ONE extra term where this screen needs TWO is the difference in
      where the two clients get their rows, which the second block below is entirely about.

      1. [seesWholeCollection] — A DISCLOSURE THE SERVER MADE, and nothing else. `ranked_payload`
         sends the raw `ordinal` only to a caller who already sees every row of the collection, so
         its PRESENCE is the server's own answer to "is `placedPosition` a position in the whole
         collection or in a subset this reader was narrowed to". That is the only question
         [dwPositionText]'s `showPlaced` is asking, and it must not be contaminated by anything
         about this device: a workshop's own designer is entitled to read "The designers place it 3"
         whatever state this phone's registry or draft store is in. It used to be `canArrange`, so a
         registry lookup that came back empty silently deleted the makers' position from every card
         — the one number [dwPositionText]'s own KDoc says must ALWAYS print where it is knowable,
         because the GAP between the two orders is the whole feature.

      2. [mayOpenStage] — WHETHER "Open the record" LEADS ANYWHERE. The stage read is gated by
         `load_workshop_or_404`, which admits exactly the set that gets the ordinal, so the
         permission half is (1); what this adds is the local half — there is no stage to open if the
         registry could not name one. Deliberately NOT gated on holding rows: a designer whose phone
         holds none of this stage's rows is precisely the designer who needs this button, and it is
         the remedy every refusal below points at.

      3. [canArrange] — WHETHER A REORDER CAN ACTUALLY BE HONOURED. This is where the handset differs
         from the browser and why the extra term is not belt-and-braces:

         THE WEB READS THE STAGE FROM THE REPOSITORY HERE AND THIS SCREEN DOES NOT. `readStageRows`
         calls `getDesignWorkshopStage` and adopts the answer into the draft, so on the web's
         workshop surface `held` is populated by the act of opening the panel. This screen reads
         `WorkshopDraftStore` and nothing else — there is no stage fetch on this path at all — so an
         EMPTY `held` is not the rare accident the web's guard-2 comment describes ("the ranking
         request succeeded while the stage read beside it did not"). It is the ORDINARY state of a
         handset that has never walked to stage 11 or 13 for this workshop, which is most handsets,
         because this screen is reached from the nav menu and not from inside a stage.

         [dwPlanArrangement]'s second guard refuses that write, correctly and for a good reason. But
         a refusal AFTER the gesture is the wrong shape for a state that is knowable BEFORE it: the
         arrows were enabled, the card moved, the banner above it said "it is being recorded against
         your name", and a red line underneath said it had not been saved. Two sentences about one
         act, the louder one false, and the list left in an arrangement nothing will ever store. So
         the condition is asked here, the control is disabled WITH ITS REASON (see `disabledReason`
         below), and the guard in `dwPlanArrangement` stays where it is as the backstop for any
         other caller — pre-empting a refusal is not the same as being allowed to remove it.
    */
    val seesWholeCollection = ranked != null && dwMayArrange(ranked)
    val mayOpenStage = seesWholeCollection && stageSpec != null
    val canArrange = mayOpenStage && held.isNotEmpty()

    /** Write one arrangement to the draft, and schedule the push. */
    fun persist(next: List<String>, nextStamp: DwFixedOrderStamp?) {
        val spec = stageSpec
        if (spec == null) {
            orderProblem =
                "This arrangement has not been saved: this phone has no field registry for the " +
                    "stage these pieces live in. Open the workshop once with a connection."
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
                        /*
                          ONLY THIS ENTITY'S ROWS ARE REPLACED. A stage holds several collections —
                          stage 13 carries a prototype's stage logs and its material lines beside the
                          prototypes themselves — and `StageDraft.rows` is one flat list with the
                          entity encoded in each row's id. Writing `plan.rows` as the whole list would
                          delete every OTHER collection on that stage, which is a fortnight of costing
                          lines destroyed by a reorder.
                        */
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
                                "Back to score order, saved on this phone. Sending it…"
                            } else {
                                "Saved on this phone. Sending it…"
                            }
                            pushTick += 1
                        }
                        .onFailure {
                            // No cancellation guard, checked: this runs in the panel's
                            // `rememberCoroutineScope`, which is cancelled only when the panel is
                            // disposed — and `orderNote`/`orderProblem` are its own remembered state,
                            // gone in the same frame. The draft write itself is atomic
                            // (`WorkshopDraftStore` writes a temp file in the target directory and
                            // renames), so a cancellation mid-write cannot leave a half-written stage.
                            orderNote = null
                            orderProblem =
                                "This arrangement could not be saved on this phone: " +
                                    it.apiErrorMessage("the local copy of this stage has gone.") +
                                    " Reload this screen and try again."
                        }
                }
            }
        }
    }

    fun reorder(next: List<String>) {
        arranged = true
        order = next
        /*
          THE NAME, NOT THE ACCOUNT ID. `rankFixedBy` is TEXT for a checked reason — `User` is not one
          of the models a reference field can resolve against, and a name is what the report can print
          where a cuid cannot. The email is the fallback for an account with no name, because "fixed
          by — on 12 August" is not a sentence; `dwPlanArrangement` refuses a blank outright.
        */
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

    /*
      "Open the record" IS OFFERED ONLY WHERE IT LEADS SOMEWHERE. The stage save and the stage READ
      are both gated by `load_workshop_or_404`, which admits exactly the set that gets the raw ordinal
      — so the permission half is [seesWholeCollection] and asking it a second way here would be two
      answers waiting to disagree. A button that led to a 404 is worse than no button.

      [mayOpenStage] AND NOT [canArrange], which is the distinction the block above draws: a designer
      whose phone holds none of this stage's rows cannot rearrange the list, and is exactly the
      designer this button is the remedy for. Gating it on the write condition would remove the way
      out of the only state that produces the refusal.
    */
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
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    round.blurb,
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            OutlinedButton(enabled = !loading, onClick = { attempt += 1 }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Refresh", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }

        // ── Which order is on screen, said in words. Four states, and none of them may guess ────
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
                            "This order was settled deliberately — fixed by ${stamp?.by} on " +
                                "${dwRatingDay(stamp?.at)}. A new rating changes the scores on the " +
                                "cards and does not move them.",
                            color = MaterialTheme.field.body,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        if (canArrange) {
                            OutlinedButton(onClick = { returnToDefault() }) {
                                Text("Return to score order", fontSize = 12.sp)
                            }
                        }
                    }
                    /*
                      THE WINDOW BETWEEN THE MOVE AND THE WRITE, said rather than papered over. The
                      stamp is read off the ROWS, and for the moment between a nudge and the file
                      write neither of the two settled sentences is true of this screen.
                    */
                    arranged -> Text(
                        "You have arranged this list — it is yours from here, and it is being " +
                            "recorded against your name. A new rating will change the scores on the " +
                            "cards and will not move them.",
                        color = MaterialTheme.field.body,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    canArrange -> Text(
                        "This is the default order — highest score first, and pieces nobody has " +
                            "rated yet at the end. Move one and the arrangement becomes yours, " +
                            "recorded against your name.",
                        color = MaterialTheme.field.body,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    /*
                      A READER WHO CANNOT ARRANGE IS TOLD WHAT THE ORDER IS, NOT WHOSE IT IS. Whether
                      this workshop's own designers settled an order of their own is not on this
                      response — the stamp lives in the stage rows, which this caller does not hold —
                      so the screen does not claim either way. The web's pool banner says exactly
                      this, and it used to say the opposite while every card under it disagreed.
                    */
                    else -> Text(
                        "These are in score order — highest first, and pieces nobody has rated yet " +
                            "at the end. Whether this workshop's own designers have settled an " +
                            "order of their own is not on this response, so this screen does not " +
                            "claim either way.",
                        color = MaterialTheme.field.body,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                orderNote?.let {
                    Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
                orderProblem?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        // ── What could not be reached, never disguised as an empty list ─────────────────────────
        /*
          SPOKEN, THROUGH A REGION PRESENT FROM FIRST PAINT, and this is the widest of the three such
          regions on this screen: it stands in for the WHOLE ROUND when the round could not be read.
          `dwRoundFailure` decides between "no signal" and a refusal, and the list below is EMPTY
          either way. A bare `Text` therefore left a designer using TalkBack with a screen holding a
          heading, a set of controls and nothing else — which is exactly the "FAILURE rendered as an
          ordinary empty state" that rule 10 exists to forbid, arriving through the assistive layer
          rather than through the pixels.

          ASSERTIVE, BY MEANING: it is not a receipt for anything the designer did, it is the reason
          the screen is blank, and it has to reach them before they go looking for pieces that are not
          there. The idiom — always composed, `mergeDescendants`, content optional — is
          [DwRankableList]'s; see it for why a region created with its first sentence never announces
          that sentence, and why an unmerged region over a childless node announces nothing at all.

          The `offline` mark stays unlabelled for the reason the review card's does: it is a redundant
          mark for the sentence in the same row, and describing it would have the region announce
          "cloud off" ahead of the words that already say there is no connection.
        */
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
                        fontSize = 12.sp,
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
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading this round…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
        }

        /*
          THE OFFLINE LIST IS THE STAGE ROWS THEMSELVES, in the order this phone holds them. It
          carries no scores because there are none on this device, and it SAYS SO rather than printing
          a zero — an unrated piece and an unreachable server must not look alike, which is the one
          disguise this repository keeps having to un-ship.
        */
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.rowName(),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            dwRowSubtitle(row).ifBlank {
                                "On this phone — its score and its reviews are on the repository."
                            },
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        if (ranked != null && ranked.isEmpty()) {
            Text(
                round.emptyNote,
                color = MaterialTheme.field.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            /*
              THE ROUND GATE, MADE VISIBLE WHERE THIS PHONE CAN SEE IT. An empty POOL round has two
              causes a designer cannot tell apart from the sentence above — this workshop has opened
              nothing, or nothing it opened is of this kind — and where the rows are on this device
              the answer is readable: `peerRoundClosedAt` is what opens a piece, per piece, and the
              registry labels it "Peer review closed on". Saying the count is the difference between
              a screen that explains itself and one a designer reloads.
            */
            if (round == DwRatingRound.POOL && held.isNotEmpty()) {
                val open = dwPoolOpenCount(held)
                Text(
                    if (open == 0) {
                        "None of the ${held.size} ${entity.label.lowercase()} this phone holds for " +
                            "this workshop carries a \"Peer review closed on\" date, and that date " +
                            "is what opens one piece to designers outside the workshop. It is set " +
                            "on the piece's own stage form, one piece at a time."
                    } else {
                        "$open of the ${held.size} ${entity.label.lowercase()} this phone holds " +
                            "carry a \"Peer review closed on\" date. If they are not listed above, " +
                            "the repository has not been sent them yet."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        if (ranked != null && ranked.isNotEmpty()) {
            DwRankableList(
                order = order,
                labelFor = { byId[it]?.label?.ifBlank { UNTITLED } ?: UNTITLED },
                onReorder = { reorder(it) },
                /*
                  THE REASON IS ASKED IN THE ORDER THE FACTS OVERRIDE EACH OTHER, and the order used
                  to be wrong: the registry case was tested first, so a POOL reader — somebody who
                  may not rearrange this list at all — was told the phone was missing a registry if
                  its lookup had also come back empty. A true sentence about the wrong obstacle is
                  worse than a vague one, because it sends a designer to fix something that was never
                  in their way. Permission first, then this device's registry, then this device's
                  rows, which is most-fundamental to least.
                */
                disabledReason = when {
                    canArrange -> null
                    !seesWholeCollection ->
                        "The order here is the score order, and it is not yours to rearrange: the " +
                            "placed order is the makers' own stage row order, which only that " +
                            "workshop's designers and an admin can change. Your rating is what you " +
                            "contribute to the ranking on this screen."
                    stageSpec == null ->
                        "This arrangement cannot be changed from here: this phone has no field " +
                            "registry for the stage these pieces live in."
                    /*
                      THE ONE THAT IS ORDINARY RATHER THAN EXCEPTIONAL ON A HANDSET — see the
                      three-questions block above. The cards on screen came from the repository; the
                      rows an arrangement is stored in did not, because this phone has never opened
                      that stage. Naming the remedy matters more here than in the two above it: this
                      is a state a designer gets out of in one tap, with the button this same card
                      offers, and the sentence has to say so or the list reads as somebody else's.
                    */
                    else ->
                        "The pieces are here but the rows they are arranged in are not: this phone " +
                            "has not read the stage these ${entity.label.lowercase()} live in, so " +
                            "there is nothing on it to rearrange. Open the record once with a " +
                            "connection — the button on any card below does it — and the arrows and " +
                            "the drag handle come back."
                },
            ) { id, _, _, _ ->
                val item = byId[id]
                if (item != null) {
                    DwReviewCard(
                        repository = repository,
                        item = item,
                        subtitle = dwRowSubtitle(rowById[id]),
                        round = round,
                        // Whether `placedPosition` describes the WHOLE collection. The same set that
                        // gets the raw ordinal is the set that sees every row, so this is the one
                        // question asked once rather than a second guess about membership.
                        //
                        // [seesWholeCollection] AND NOT [canArrange]: this is the server's
                        // disclosure and has nothing to do with what this phone holds. It was
                        // `canArrange`, which meant an empty registry lookup or an unopened stage
                        // silently deleted the makers' position from every card — see the
                        // three-questions block where these two were separated.
                        showPlaced = seesWholeCollection,
                        fixedOrder = stamp != null || arranged,
                        openStage = openStageHere,
                        onRated = { rating ->
                            /*
                              PATCHED IN PLACE RATHER THAN RE-FETCHED, AND THE LIST IS DELIBERATELY
                              NOT RE-SORTED. The average this designer just changed would otherwise
                              move the card out from under their thumb the instant they pressed the
                              button — the score re-sorting a list while somebody is working through
                              it, which is the exact behaviour the override rule exists to prevent.
                              Refresh brings the new averages in with the ordering rules applied once.
                            */
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

/** How long the list stays quiet before an arrangement is offered to the repository. */
private const val PUSH_QUIET_MS = 1200L

/** The one fallback name, so the card, the announcements and the queue label cannot disagree. */
private const val UNTITLED = "Untitled piece"

/**
 * A row's own display name, for the offline list.
 *
 * The registry gives both rateable entities `labelField = "name"`, which is what the server's own
 * `_entry_label` tries first, so this agrees with the label the ranking response would have carried
 * for the same row.
 */
private fun DraftRow.rowName(): String {
    val value = values["name"]
    val text = (value as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it !is kotlinx.serialization.json.JsonNull && it.isString }
        ?.content
        ?.trim()
        .orEmpty()
    return text.ifBlank { UNTITLED }
}

/**
 * One piece under review: what it is, what it scored, what this designer thinks of it, and — for the
 * people entitled to it — who else has judged it, when and how.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE QUANTITATIVE AND THE QUALITATIVE ARE THREE CONTROLS, NOT ONE BOX
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The owner asked for both, and the server keeps them in three columns for a reason its own schema
 * states: an assessment and a proposed change are different speech acts with different readers, and
 * collapsed into one box the suggestions are unfindable inside the prose. So this card has a score, a
 * comment and a separate suggestion — the same three columns the ledger row has.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE SCORE IS A RADIO GROUP AND NOT A ROW OF STARS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Five real `Role.RadioButton` targets inside a `selectableGroup`, which is the shape TalkBack
 * announces as "3 of 5, selected" without a label being invented for it. A star strip is a row of
 * buttons that has to reimplement all of that — and the NUMBER is what the ranking is actually
 * computed from, so the control a designer uses should be the number they are choosing. The chosen
 * one is marked by a WORD as well as by the fill: colour never carries meaning alone here, and a
 * filled chip among four pale ones is exactly the signal a colour-blind reader loses.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS CARD MAY SHOW IS DECIDED BY THE SERVER, NOT HERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The disclosure renders `ratings` exactly as `GET /design-ratings/subjects/{id}` returns it. Admins
 * and master admins get every row with a reviewer on it; a designer gets every row on their OWN
 * record; everybody else gets the aggregate and their own row, and no other row is in the response at
 * all. **No column is hidden here.** Hiding a column in a client is not a control, and a card that
 * filtered rows would be a second, weaker opinion about a rule the server already enforces.
 */
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
    /**
     * The outcome sentence, and whether it is a landing or a promise.
     *
     * ONE STATE AND NOT TWO, because the two facts are never both true and a card holding "sent" and
     * "queued" at once would have to decide which to draw. The [queued] half is what keeps the tone
     * honest: a rating sitting in the outbox must not read like one the repository has acknowledged.
     */
    var saved by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var ledgerOpen by remember { mutableStateOf(false) }
    var ledger by remember { mutableStateOf<SubjectLedgerDto?>(null) }
    var ledgerProblem by remember { mutableStateOf<String?>(null) }
    var ledgerLoading by remember { mutableStateOf(false) }
    /**
     * Bumped when the ledger has to be read AGAIN — after this designer's own amendment lands.
     *
     * A NONCE AND NOT A NULLED-OUT CACHE, because the read below is keyed on the subject and the
     * round, neither of which changes when a rating is amended: clearing the cache alone would leave
     * the disclosure empty with nothing to refill it. The ledger carries other people's rows, so it
     * is re-read rather than patched — this caller's amendment may be one of several that landed
     * since it was opened.
     */
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
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = MaterialTheme.field.muted, fontSize = 11.sp)
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
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            dwPositionText(item, showPlaced = showPlaced, fixedOrder = fixedOrder),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        openStage?.let { open ->
            OutlinedButton(onClick = open) { Text("Open the record", fontSize = 12.sp) }
        }

        HorizontalDivider()

        Text("Your score for this piece", color = MaterialTheme.field.muted, fontSize = 11.sp)
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
                        fontSize = 12.sp,
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
            placeholder = "A suggestion or recommendation the maker can act on.",
        )

        /*
          WHERE THESE THREE ANSWERS GO, AND WHERE THEY DO NOT — said once, under the boxes.

          The score, the assessment and the suggestion are stored in `DwReviewRating`, and NO report
          section reads that table: the report builder, the templates and the report model have no
          reference to it. What the printed document DOES carry out of this screen is the ARRANGEMENT
          these ratings settle, and the line saying who settled it.

          A designer writing four hundred words of assessment into a box on a screen whose other half
          feeds a ministry report will reasonably assume the words go there too. Rule 10 of this
          repository's contract is about a list that quietly stops; this is the same rule read one step
          further — work that is captured and printed nowhere has to say so on the screen that captures
          it, or the only person who ever finds out is the one reading the finished document looking
          for their own paragraph.
        */
        Text(
            "Scores, assessments and suggestions stay in this workshop's review ledger — they are " +
                "read here and in the ranking, and the printed report does not carry them. What the " +
                "report takes from this screen is the ORDER the pieces end up in, and a line saying " +
                "who settled it. Anything that has to appear in the document belongs on the piece's " +
                "own stage form.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        /*
          THE REFUSAL, SPOKEN, IN A REGION THAT EXISTS FROM FIRST PAINT.

          ════════════════════════════════════════════════════════════════════════════════════════════
          THE DEFECT: A REFUSAL NOBODY HEARD, ON THE ONE CONTROL THAT REFUSES
          ════════════════════════════════════════════════════════════════════════════════════════════

          Press "Submit my rating" with no score chosen and [DW_RATING_NEEDS_A_SCORE] appears here —
          "Choose a score from 1 to 5". It was a bare `Text`, so a designer using TalkBack pressed the
          button, focus stayed on the button, nothing was announced, and the only evidence that the
          submission had been REFUSED rather than accepted was a red line they could not see. The same
          node also carries [DW_RATING_NOT_SENT], which is the repository declining a rating outright
          — the sentence that says "nothing has been recorded" about work still sitting in the boxes.

          The region is composed whether or not it holds a sentence, and merged, for the two reasons
          [DwRankableList] and [DwReviewTextBox] give in full: assistive technology announces a change
          INSIDE a region that already existed, and a live region over a node with no text of its own
          announces nothing unless its descendants are merged into it.

          ASSERTIVE, BY MEANING. This is a refusal with an action in it, standing between the designer
          and the thing they just tried to do; it interrupts. The receipt below does not.

          AND WHAT THE IDIOM COSTS, SAID RATHER THAN GLOSSED: this Column arranges its children
          `spacedBy(8.dp)`, so a region that is composed empty still occupies a slot. The two of them
          add 16dp above the button row on a card holding neither sentence, on every card in the list.
          That is the price [DwRankableList] pays for the same guarantee and it is the right trade:
          16dp of white space against a refusal a designer using TalkBack cannot hear. It is NOT
          worth avoiding by wrapping the pair in a container of their own, which buys back 8dp and
          puts a second shape into the one part of this codebase where every live region is meant to
          look identical.
        */
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
            },
        ) {
            problem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        /*
          AND THE RECEIPT, POLITELY — which is the whole of the difference in tone.

          A rating that landed, was already held unchanged, or went into the outbox is an OUTCOME of
          something the designer chose to do a moment ago, not an obstacle: it can wait for a pause in
          whatever they are reading. It still has to be announced, and for the queued flavour that is
          not a nicety — [DW_RATING_QUEUED] is the only place this screen says the score on these cards
          will NOT move until a sync happens, and a sighted designer reads that from the CloudOff mark
          beside it while a TalkBack user had neither the mark nor the sentence.

          THE ICON STAYS UNLABELLED ON PURPOSE. `contentDescription = null` is correct here: it is a
          redundant mark for the words in the very same row (rule 5's requirement that the signal not
          be carried by colour alone), and giving it a description would have the region announce
          "cloud off" ahead of a sentence that already says the rating has not reached the repository.
        */
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            saved?.let { (text, queued) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (queued) {
                        // A STATIC MARK, NOT A COLOUR ALONE. The icon is what tells a colour-blind
                        // reader that this one is still outstanding.
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
                        fontSize = 12.sp,
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
                                    // OMITTED ON THE DIRECT PATH. The row's own `createdAt` IS the
                                    // moment the designer moved the control; the queue stamps it.
                                    ratedAt = null,
                                ),
                                // What a designer will recognise in the outbox tray a week later:
                                // the piece's own name. An endpoint is not that.
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
                                        // Re-read rather than patched: the ledger carries other
                                        // people's rows, and this caller's amendment may be one of
                                        // several that landed since it was opened.
                                        if (ledgerOpen) {
                                            ledger = null
                                            ledgerTick += 1
                                        }
                                    }
                                    /*
                                      A QUEUED RATING IS NOT PASSED TO `onRated`, AND THAT IS NOT AN
                                      OVERSIGHT. `onRated` hands the list a stored row — with its id,
                                      its reviewer and the aggregate recomputed around it — and there
                                      is no such row yet. Manufacturing one from what is in these
                                      boxes would put a score into the average printed on every card
                                      of this round that the repository has never seen and might yet
                                      refuse. The boxes keep the text either way.
                                    */
                                    DwRatingOutcome.Queued -> saved = DW_RATING_QUEUED to true
                                }
                            }
                            .onFailure { error ->
                                // No cancellation guard, checked: the card's own
                                // `rememberCoroutineScope` is cancelled only by disposal, `problem` is
                                // the card's remembered state, and the one hoisted call — `onRated` —
                                // is on the success path only. `submitDesignRating` also QUEUES rather
                                // than fails when there is no connection, so the failure this sentence
                                // is about is a refusal, not an abandoned request.
                                problem = error.apiErrorMessage(DW_RATING_NOT_SENT)
                            }
                        saving = false
                    }
                },
            ) {
                Text(if (mine != null) "Amend my rating" else "Submit my rating", fontSize = 12.sp)
            }
            if (mine != null) {
                Text(
                    "You rated this ${mine.score} on ${dwRatingDay(mine.ratedAt ?: mine.createdAt)}.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                )
            }
        }

        HorizontalDivider()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            /*
              `clickable` PLUS A STATE DESCRIPTION, NOT `selectable`. A disclosure is not a choice
              among options: `selectable` makes TalkBack announce "selected" / "not selected", which
              for a section that opens and closes is the wrong noun and gives a reader no idea that
              pressing it reveals something. `stateDescription` is what announces "Expanded" /
              "Collapsed", and `onClickLabel` is what says what the press will DO — the chevron
              beside the words carries neither of those to somebody who cannot see it.
            */
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
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (ledgerOpen) {
            // LOADED ON DEMAND rather than with the round: a workshop with thirty prototypes would
            // otherwise make thirty extra requests to fill in disclosures nobody has opened.
            LaunchedEffect(item.subjectId, round, ledgerTick) {
                if (ledger != null || ledgerLoading) return@LaunchedEffect
                ledgerLoading = true
                ledgerProblem = null
                runCatching { repository.designRatingLedger(item.subjectId, round.wire) }
                    .onSuccess { ledger = it }
                    .onFailure { error ->
                        /*
                          ══════════════════════════════════════════════════════════════════════════
                          A CANCELLED READ IS NOT A REFUSED READ, AND THIS ONE IS CANCELLED OFTEN
                          ══════════════════════════════════════════════════════════════════════════

                          `runCatching` catches `Throwable`, so it catches `CancellationException`
                          too, and every one of this effect's keys changes IN PLACE on a card that
                          stays composed:

                           * closing the disclosure removes the `if (ledgerOpen)` block and cancels
                             this coroutine;
                           * a successful rating bumps `ledgerTick` (see the submit above) precisely
                             so the ledger is re-read, which cancels the read already in flight.

                          Reported, that lands as "the repository refused…" — `isConnectionFailure`
                          answers false for a cancellation, so it takes the REFUSAL branch and says
                          the server rejected a request the app itself abandoned. And it is durable:
                          `ledgerProblem` is remembered by the CARD (declared above, outside the
                          disclosure), so the cancelled run's tail sets it AFTER the replacement run
                          has already set it null, leaving a red refusal standing over a ledger that
                          then loads perfectly — plus `ledgerLoading = false` below killing the
                          "Reading the review history…" line while the new read is still running.

                          Rethrown, the cancellation propagates as structured concurrency requires
                          and NOTHING is written: the effect that replaced this one owns the state.
                          This is the same guard `loadMyActivity`'s caller and `dwReadQrPicture` use,
                          for the same reason.
                        */
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
                    fontSize = 12.sp,
                )
            }
            ledgerProblem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 17.sp)
            }
            ledger?.let { DwLedgerBlock(it) }
        }
    }
}

/** The server's own cap on both text columns — `MAX_COMMENT_CHARS` and `MAX_SUGGESTION_CHARS`. */
private const val MAX_REVIEW_TEXT = 4000

/**
 * One of the two prose boxes on a review card — and the sentence that says when it stopped taking
 * what was given to it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS IS A COMPOSABLE AND NOT TWO `OutlinedTextField`s WITH `.take(MAX_REVIEW_TEXT)`
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * It was exactly that until 2026-08-26, and `it.take(MAX_REVIEW_TEXT)` is a SILENT TRUNCATION —
 * which this repository refuses everywhere else it appears, by name. `DwValues.coerce` answers an
 * over-long value with "<label> is longer than <n> characters" and stores nothing; `FieldDto.maxItems`
 * records why `coerce_value` REFUSES an over-long array "rather than trimming it — because silently
 * keeping the first N of a list the client believes it stored is exactly the 'Stage saved, and the
 * photographs are gone' failure"; and the browser's own dictation path prints the number of
 * characters it would not add rather than cutting the sentence to fit. Rule 10 of this repository's
 * contract is the general form of all three: every cap, every truncation and every skipped row says
 * so on screen.
 *
 * TYPING IS NOT WHERE THIS BITES — a thumb on the 4,000th character notices the keyboard stop.
 * PASTING IS. A designer who has written their assessment somewhere else and pastes six thousand
 * characters in gets four thousand, ending mid-word, with nothing on screen to suggest a third of it
 * is gone; and the box they can see IS what gets sent, so the loss is silent all the way through to
 * the stored row. The card two paragraphs above this one anticipates exactly that person — "a
 * designer writing four hundred words of assessment" — which is the point at which a silent cap
 * stops being theoretical.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT STILL TRUNCATES RATHER THAN REFUSING THE WHOLE PASTE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `DwValues.coerce` can refuse outright because it sits between a finished value and the store. This
 * sits under a keyboard, and a `TextField` whose `value` does not change when a key is pressed is a
 * box that appears broken — the compositions and the IME both fight it. So the cap holds (the server
 * answers a longer body with a 422 naming a field the designer never saw, which is worse than either
 * option) and the DIFFERENCE is what gets said, with the number in it. The kept text is the first
 * 4,000 characters and not the last, matching what the browser's `maxLength` does with the same
 * paste, so a designer working across the two clients gets the same text on both.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * AND WHY THE SENTENCE IS INSIDE A LIVE REGION THAT EXISTS FROM FIRST PAINT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A reader who cannot see the box cannot see it stop. The pattern — a `mergeDescendants` region that
 * is composed empty and filled later — is [DwRankableList]'s, for the reason its own comment gives:
 * assistive technology announces a change INSIDE a region that already existed, so a region created
 * at the same moment as its first message is a region whose first message is never announced.
 */
@Composable
private fun DwReviewTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    /*
      HOW MANY CHARACTERS THE LAST EDIT COULD NOT KEEP — not a running "3,940 of 4,000" counter.

      A counter is furniture on a box almost nobody fills, and it describes the cap rather than the
      loss: the fact a designer needs is that something they just added is NOT THERE, which is an
      event and not a level. Cleared by the next edit that fits, so it describes the state of the box
      in front of them and never a paste two minutes old.
    */
    var dropped by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { typed ->
                dropped = (typed.length - MAX_REVIEW_TEXT).coerceAtLeast(0)
                onValueChange(typed.take(MAX_REVIEW_TEXT))
            },
            /*
              [requiredMarked] AS INSURANCE, NOT AS A FIX — the one form-field label render on this
              screen that the red-asterisk wave deliberately left unwrapped.

              Its rule is that the mark IS a trailing " *" and that honouring the convention at the
              RENDERER is what catches every label, "including the two dozen that spell it into a
              literal and would have been missed by a per-call-site Boolean". Both of this box's call
              sites today pass optional labels carrying no mark, so this wrapper changes nothing on
              screen right now: [dwRequiredMarked] returns a plain single-span string for a label
              with no trailing mark, which is why that file says it is "safe to wrap around EVERY
              label a control renders rather than only the ones known to be required".

              WHAT IT BUYS IS THE NEXT CALL SITE. A required one added here would otherwise compile
              perfectly and draw a plain-ink asterisk — the exact silent failure RequiredMark.kt
              exists to prevent, on a screen where a designer is typing a justification that goes
              into an audit ledger. Mark-safe by construction beats mark-safe by inspection.
            */
            label = { Text(requiredMarked(label)) },
            placeholder = { Text(placeholder) },
            minLines = 2,
            // The refusal is the box's own, so it belongs on the box for a reader who navigates by
            // control rather than by line — `isError` is what colours the outline and the label with
            // it, which is the one signal that survives not reading the sentence underneath.
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
                    "$dropped character${if (dropped == 1) "" else "s"} " +
                        "${if (dropped == 1) "was" else "were"} not kept. This box holds at most " +
                        "$MAX_REVIEW_TEXT and the repository refuses a longer answer outright, so " +
                        "what went past that is not in the box and will not be sent. Shorten what " +
                        "is here, or put the rest on the piece's own stage form.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

/**
 * The ledger rows, exactly as the server sent them.
 *
 * NOTHING IS FILTERED AND NOTHING IS SORTED. The rows arrive oldest first and redacted, and a client
 * that reordered them would be inventing a reading of an audit trail. The two notes above the list
 * come from [dwLedgerEmptyNote] and [dwLedgerNamesNote], which is where the difference between "no
 * ratings yet" and "not yours to see" is decided — once, off the server's own two flags.
 */
@Composable
private fun DwLedgerBlock(ledger: SubjectLedgerDto) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        dwLedgerEmptyNote(ledger)?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        dwLedgerNamesNote(ledger)?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        dwRatingAttribution(rating),
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    dwRatingClockLine(rating),
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                )
                rating.comment?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.field.body, fontSize = 12.sp, lineHeight = 17.sp)
                }
                rating.suggestion?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Suggested: $it",
                        color = MaterialTheme.field.body,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}
