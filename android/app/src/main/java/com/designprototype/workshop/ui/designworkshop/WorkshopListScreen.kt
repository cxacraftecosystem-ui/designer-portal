package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_LOCAL_ID_PREFIX
import com.designprototype.workshop.data.DW_WORKSHOP_CREATE_REFUSAL
import com.designprototype.workshop.data.DesignWorkshopCreateBody
import com.designprototype.workshop.data.mayMintLocalWorkshop
import com.designprototype.workshop.data.ReportTemplateDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.WorkshopSyncStatus
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.DwCustomSectionStore
import com.designprototype.workshop.data.computeWorkshopCompleteness
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.overallPercent
import com.designprototype.workshop.data.visibleDesignWorkshops
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Every design workshop this account can reach, from the server AND from this device, in one list.
 *
 * MERGED RATHER THAN TOGGLED, and that is the decision the screen turns on. A tab or a filter for
 * "offline drafts" would mean a designer who opened the app in a courtyard sees an empty list where
 * their fortnight of work is, decides the app has lost it, and reinstalls — which is the one action
 * that would actually lose it. So a workshop is one row whether its data currently lives on the
 * server, on the phone, or both, and the row itself says which.
 *
 * The completeness ring is computed from the LOCAL draft against the registry, not from the server's
 * `completeness` block, whenever a local draft exists. The server's figure is by definition as old as
 * the last successful sync; showing it beside a stage the designer filled in an hour ago would tell
 * them their work had not counted.
 */

/**
 * How long the search box waits before it asks the server.
 *
 * The local half of the list is filtered in memory and is therefore instant either way; this only
 * paces the network call, so it can be generous enough to cover an ordinary typing speed.
 */
private const val SEARCH_DEBOUNCE_MS = 350L

/** One row: a workshop, wherever it currently lives. */
@Immutable
private data class WorkshopRow(
    /** The id the draft store files this workshop under, and the id the stage screens open. */
    val localId: String,
    /** The server's id, or null while the workshop has never been created remotely. */
    val remoteId: String?,
    val title: String,
    val subtitle: String,
    val percent: Int,
    val requiredFilled: Int,
    val requiredTotal: Int,
    val updatedAt: String,
    val hasLocalDraft: Boolean,
    /**
     * What is still on this phone and not on the server, computed from the draft with no network
     * call. Null only for a row that exists on the server and has never been opened on this device,
     * which is the one case where there is nothing local to be behind.
     */
    val status: WorkshopSyncStatus? = null,
) {
    val localOnly: Boolean get() = remoteId == null
}

@Composable
fun WorkshopListScreen(
    repository: WorkshopRepository,
    /**
     * Arrive with the "New design workshop" dialog already open.
     *
     * The dashboard's Design workshop card is the only caller that passes true, and it is what makes
     * that card's primary button start a workshop in one tap instead of landing on a list with the
     * form shut — the state the web tile's "New workshop" was in until this change.
     *
     * CONSUMED ONCE PER ARRIVAL. `remember(startCreating)` seeds [showCreate] and then leaves it
     * alone, so cancelling the dialog leaves the designer on the list instead of watching it reopen
     * on the next recomposition. The route out of a workshop rebuilds this screen with false, so
     * backing out never re-offers the form (MainActivity's `goBack`).
     */
    startCreating: Boolean = false,
    onOpen: (workshopId: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var schema by remember { mutableStateOf<SchemaResponse?>(null) }
    var rows by remember { mutableStateOf<List<WorkshopRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    /**
     * May this account start a workshop at all — read from the CACHED user, with no network.
     *
     * `remember`ed with no key on purpose, and for the reason `canReadIdentityCards` gives on the
     * artisan form: a role cannot change while this screen is mounted (it takes a fresh sign-in,
     * which rebuilds the tree), so the boolean is stable and a control cannot appear and disappear
     * between frames.
     *
     * FAILS OPEN — no cached user reads as "may create" — because that is what [mayMintLocalWorkshop]
     * decides and it is the deliberate half of its tri-state: refusing an ADMIN in the second before
     * the cached user is read would be a refusal for a rule that does not apply to them. The gate
     * that is actually load-bearing is `POST /design-workshops`, and it is not this.
     */
    /*
     * KEYED ON THE CACHED SESSION, not `remember {}` with no key.
     *
     * Unkeyed, this is computed once for the life of the composition and never again — so a sign-out
     * and sign-in as a different role, or an admin's role being lowered while this screen is open,
     * leaves the New button and the refusal panel showing the OLD account's answer. The web
     * equivalent re-runs on `user?.role`, and two surfaces disagreeing about who may create is
     * exactly the kind of difference that gets reported as "the phone let me and the laptop did not".
     *
     * The role rather than the whole user object: it is the only field this reads, and keying on the
     * object would recompute on every unrelated profile change.
     */
    val cachedSession = repository.cachedUser()
    val mayCreate = remember(cachedSession?.id, cachedSession?.role) {
        mayMintLocalWorkshop(known = cachedSession != null, role = cachedSession?.role)
    }
    var showCreate by remember(startCreating) { mutableStateOf(startCreating && mayCreate) }
    /** The device-only draft the designer is moving into a real workshop, while they choose one. */
    var adopting by remember { mutableStateOf<WorkshopRow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var offline by remember { mutableStateOf(false) }
    /** Rows gathered vs rows the server says exist, set only when the walk came back short. */
    var partial by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /**
     * Which workshop the background pass is uploading right now, straight from the engine.
     *
     * Read rather than inferred, so the row that is genuinely being sent says "Sending…" and the
     * nineteen behind it keep saying what they are still waiting on. A single global spinner would
     * make every row look busy, which is precisely the ambiguity this screen exists to remove.
     */
    val sendingId by WorkshopSyncEngine.busyWorkshop.collectAsState()
    val syncRevision by WorkshopSyncEngine.revision.collectAsState()

    LaunchedEffect(reload, search) {
        // Debounced, and only for typing. Keying the effect on `search` means every keystroke
        // cancels the previous run and starts a new one, so without this a five-letter craft name is
        // five list requests on a metered field connection — and the first four are replies nobody
        // will ever look at. `reload` is a deliberate action (a create, a send) and must not wait.
        if (search.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        loading = true
        runCatching {
            val registry = repository.designWorkshopSchema(appContext)
            schema = registry

            // The device first, always, and without a network call in front of it. A list that waits
            // on a request before it can show the drafts already on the phone is a list that shows
            // nothing at all for thirty seconds on a dying connection.
            val localIds = WorkshopDraftStore.list(appContext).map { it.workshopId }
            val drafts = localIds.mapNotNull { id -> WorkshopDraftStore.load(appContext, id)?.let { id to it } }
            val draftById = drafts.toMap()
            val remoteToLocal = drafts.mapNotNull { (id, draft) -> draft.remoteId?.let { it to id } }.toMap()

            // EVERY page the server says this account may see, not the first one.
            //
            // This asked for `pageSize = 100` and used `items`, which reads as "give me everything"
            // and is not: `normalize_pagination` clamps at MAX_PAGE_SIZE = 100 without saying so, and
            // `total` was thrown away. Ordinarily that is a long-list limitation; since an admin can
            // put a second designer on a workshop it is a correctness bug, because a viewer grant
            // WIDENS this list while the rows stay ordered `createdAt desc`, and a grant is issued
            // against a workshop that already exists — older than anything the grantee started, so it
            // sorts to the end. Verified live: designer@example.org with one grant sees total 120 and
            // the granted workshop is the 119th row. See data/DesignWorkshopListing.kt.
            val remote = runCatching {
                repository.visibleDesignWorkshops(search = search.takeIf { it.isNotBlank() })
            }
            // A CANCELLED walk IS NOT AN OFFLINE ONE. This effect is keyed on the search box, so a
            // keystroke landing while a later page is in flight cancels it — and `runCatching` catches
            // that like any other throwable. Reported as a failure it would flash "the server could
            // not be reached" over a connection that is fine, on the screen whose job is telling a
            // designer whether their fortnight of work has left the phone. The house pattern is to
            // guard the HANDLING rather than rethrow (see QuestionnaireAnswerScreen); the run that
            // replaces this one sets both flags a moment later.
            val cancelled = remote.exceptionOrNull() is CancellationException
            if (!cancelled) {
                offline = remote.isFailure
                // Advisory and dismissible by ignoring it: the rows that DID arrive are real and the
                // screen works. What must not happen again is showing a prefix in silence and leaving
                // the designer to conclude from a missing workshop that their grant did not work.
                partial = remote.getOrNull()?.takeIf { it.truncated }?.let { it.items.size to it.total }
            }

            val fromServer = remote.getOrNull()?.items.orEmpty().map { dto ->
                val localId = remoteToLocal[dto.id] ?: dto.id
                val draft = draftById[localId]
                rowFor(
                    context = appContext,
                    localId = localId,
                    remoteId = dto.id,
                    title = dto.title,
                    subtitle = listOfNotNull(
                        dto.craftName, dto.clusterName, dto.district, dto.state,
                        dto.status.takeIf { it.isNotBlank() }?.lowercase()?.replace('_', ' ')
                    ).joinToString(" · "),
                    updatedAt = dto.updatedAt.orEmpty(),
                    schema = registry,
                    draft = draft,
                )
            }

            val covered = fromServer.map { it.localId }.toSet()
            val localOnly = drafts
                .filterNot { (id, _) -> id in covered }
                .map { (id, draft) ->
                    rowFor(
                        context = appContext,
                        localId = id,
                        remoteId = draft.remoteId,
                        title = draft.title.ifBlank { "Untitled workshop" },
                        subtitle = if (isLocalOnlyWorkshop(id)) "On this device only" else "Not synced",
                        updatedAt = draft.updatedAt,
                        schema = registry,
                        draft = draft,
                    )
                }
                .filter { row ->
                    // The server did the filtering for its own rows; the local ones have to be
                    // filtered here or a search would quietly stop matching offline.
                    search.isBlank() || row.title.contains(search, ignoreCase = true)
                }

            (fromServer + localOnly).sortedByDescending { it.updatedAt }
        }.onSuccess { rows = it }
            .onFailure { onError(it.message ?: "Unable to list the design workshops.") }
        loading = false
    }

    // A background pass that finishes nine photographs while this list is on screen has to be
    // visible without the designer pulling to refresh — the indicator is worthless if it is stale.
    // Only the LOCAL statuses are recomputed here: re-running the whole effect above would re-issue
    // the workshop list request once per uploaded file, on the metered connection the upload is
    // already using. Debounced for the same reason, so a burst of forty files costs one recompute.
    LaunchedEffect(syncRevision) {
        val registry = schema ?: return@LaunchedEffect
        if (syncRevision == 0 || rows.isEmpty()) return@LaunchedEffect
        delay(400)
        rows = rows.map { row ->
            val draft = WorkshopDraftStore.load(appContext, row.localId)
            row.copy(status = draft?.let { WorkshopSyncEngine.statusOf(appContext, registry, it) })
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Design & prototype workshops",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        Text(
            "Each workshop is captured across 22 stages. Everything is saved on this device first, " +
                "and synced whenever there is a connection.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            // GONE, NOT GREYED, for a designer. A disabled control with its reason somewhere else is
            // a control people tap repeatedly; and the answer here is not "not yet" but "not you,
            // and here is who" — which is a sentence, not a tooltip. The refusal panel below carries
            // it, so nothing is hidden except a dead end.
            if (mayCreate) {
                Button(onClick = { showCreate = true }, enabled = !busy) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
            }
        }

        // Answered BY NAME rather than by an absence — and answered here even when the designer
        // arrived from the dashboard's "New workshop" tile, which passes `startCreating` and would
        // otherwise land them on a list with nothing to explain why no form opened.
        if (!mayCreate) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Starting a workshop is an admin's job",
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    DW_WORKSHOP_CREATE_REFUSAL,
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        if (offline) {
            Text(
                "The server could not be reached. Showing what is stored on this device.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp
            )
        }

        // Says so rather than letting a designer infer it from a workshop that is not there — which
        // is the failure this whole paging change exists to end. Advisory: nothing is blocked, and
        // the search box above narrows on the SERVER, so typing a craft or cluster name reaches the
        // rows this list stopped short of.
        partial?.let { (shown, total) ->
            Text(
                "Showing $shown of $total workshops. Search by title, craft or cluster to reach the rest.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp
            )
        }

        // Everything outstanding across the device, and one button to try it now. Counted from the
        // rows already on screen rather than from a second read of the disk, so the banner and the
        // per-row chips can never disagree about the same fact.
        //
        // THAT CLAIM WAS FALSE FOR ONE OF THE FOUR THINGS A ROW CAN BE OUTSTANDING FOR, and it was
        // the one added last. `refusedAnswers` is a term of `isFullySynced`, so a refusal-only
        // workshop reached `outstanding` — but the only counters handed down were the pending pair
        // and `failedStages + failedMedia`, all zero for it. The banner therefore drew a workshop it
        // could not name and fell through to "Waiting to upload … it uploads whenever there is a
        // connection", directly above a row reading "2 answers refused — the rest is backed up".
        // Measured on the handset; see [dwDeviceSyncBanner], which now decides both sentences.
        val outstanding = rows.mapNotNull { it.status }.filterNot { it.isFullySynced }
        /*
          WHAT A SYNC PASS CANNOT MOVE, READ BEFORE THE PASS RUNS.

          [SyncPassResult.refused] counts items the server would not take AT ALL — a create, a file,
          a stage that threw. An answer refused INSIDE a 200 never reaches it, and in the case that
          matters most nothing is even attempted: the stage's signature already matches, so the pass
          sends nothing, `didAnything` is false and `refused` is 0. The button below then fell to its
          last arm and told the designer "Everything on this device is already on the server" over
          answers that are on this device and are NOT on the server — the last sentence in a sequence
          of three that all said the same untrue thing (see [dwDeviceSyncBanner] for the other two).
          Counted from the rows for the same reason the banner is: one number, one story.
        */
        val refusedAnswersNow = outstanding.sumOf { it.refusedAnswers }
        val refusedAnswersLine = if (refusedAnswersNow == 0) "" else {
            " $refusedAnswersNow answer${if (refusedAnswersNow == 1) "" else "s"} " +
                "${if (refusedAnswersNow == 1) "is" else "are"} still refused — a sync cannot move " +
                "${if (refusedAnswersNow == 1) "it" else "them"}; open the workshop and correct " +
                "${if (refusedAnswersNow == 1) "it" else "them"}."
        }
        /*
          AND THE OTHER THING A SYNC PASS CANNOT MOVE, WHICH IS NOT AN ANSWER BUT A DELETION.

          Same shape as the line above and found by looking for it: a stage holding `emptiedEntities`
          it is not yet authoritative enough to send has a signature that already MATCHES, so the pass
          sends nothing, `didAnything` is false, `refused` is 0 — and this button fell to its last arm
          and said "Everything on this device is already on the server" over a row deletion that is on
          this device and is NOT on the server. The remedy is different from the refusal's and has to be
          said differently: not "correct it" but "open the stage", because what is missing is a READ.
          See [WorkshopSyncStatus.unsentDeletions].
        */
        val deletionsNow = outstanding.sumOf { it.unsentDeletions }
        val deletionsLine = if (deletionsNow == 0) "" else {
            " $deletionsNow stage${if (deletionsNow == 1) "" else "s"} " +
                "${if (deletionsNow == 1) "holds a row deletion" else "hold row deletions"} a sync " +
                "cannot move — open the stage once with a connection so this phone can read it, and " +
                "the deletion goes up on the save straight after."
        }
        DeviceSyncBanner(
            workshops = outstanding.size,
            stages = outstanding.sumOf { it.pendingStages },
            files = outstanding.sumOf { it.pendingMedia },
            bytes = outstanding.sumOf { it.pendingMediaBytes },
            refusals = outstanding.sumOf { it.failedStages + it.failedMedia },
            refusedAnswers = outstanding.sumOf { it.refusedAnswers },
            unsentDeletions = outstanding.sumOf { it.unsentDeletions },
            busy = busy || sendingId != null,
            onSyncNow = {
                busy = true
                scope.launch {
                    val result = runCatching { repository.syncDesignWorkshops(appContext) }.getOrNull()
                    when {
                        result == null -> onError("The sync could not be started.")
                        result.skipped -> onMessage(
                            "No connection. Everything is saved on this device and will upload by itself."
                        )
                        result.didAnything -> onMessage(
                            buildString {
                                append("Sent ${result.stagesSent} stage(s) and ${result.mediaUploaded} file(s).")
                                if (result.stoppedOffline) {
                                    append(" The connection dropped — the rest is still here.")
                                }
                                // A pass that sends nineteen stages and is refused the twentieth is
                                // not a clean pass, and reporting only the nineteen is how a designer
                                // packs up believing all twenty went. `buildString` rather than a
                                // chain of `+ if (…) … else ""`: Kotlin binds the second `if` inside
                                // the first one's else-branch, so the two clauses would have been
                                // mutually exclusive.
                                if (result.refused > 0) {
                                    append(" ${result.refused} item(s) were refused — open the workshop below to see why.")
                                }
                                // A stage that WAS sent and came back with some of its answers
                                // declined is counted by neither `stagesSent` (it went) nor
                                // `refused` (the request succeeded), so it said nothing here at all.
                                append(refusedAnswersLine)
                                // Counted by neither `stagesSent` nor `refused` either, and for a
                                // blunter reason: the stage was never sent at all, because its
                                // signature already matched.
                                append(deletionsLine)
                            }
                        )
                        // ABOVE BOTH the offline line and the "already on the server" line, and each
                        // ordering closes a different way of telling a designer a refusal did not
                        // happen. A pass that sent nothing and was refused something used to fall
                        // through to "already on the server" and report the fortnight safe. And
                        // whenever a refusal was FOLLOWED by a genuine drop — workshop 1 refused, the
                        // signal gone by workshop 2 — it fell to the offline line instead, which is
                        // the answered-5xx defect itself arriving by the other door: the server
                        // answered and refused an item, and the designer was told the connection was
                        // at fault and went looking for better signal. `stoppedOffline` is still
                        // said, second, because "the rest has not been tried yet" is the other half
                        // of the news and it is the half that says nothing was lost.
                        result.refused > 0 -> onMessage(
                            buildString {
                                append("${result.refused} item(s) were refused by the server and are ")
                                append("still on this device. Open the workshop below to see which, ")
                                append("and why.")
                                if (result.stoppedOffline) {
                                    append(" The connection then dropped, so nothing after that was ")
                                    append("tried — none of it has been lost.")
                                }
                            }
                        )
                        result.stoppedOffline -> onMessage(
                            "The connection dropped before anything could be sent. Nothing has been lost."
                        )
                        // NOT "everything is already on the server" WHEN IT IS NOT. This arm is
                        // reached by exactly the phone the banner above was describing wrongly: a
                        // clean pass with nothing to send, because the only outstanding thing is an
                        // answer the repository has already read and declined. See `refusedAnswersNow`.
                        refusedAnswersNow > 0 || deletionsNow > 0 -> onMessage(
                            "There was nothing to send.$refusedAnswersLine$deletionsLine"
                        )
                        else -> onMessage("Everything on this device is already on the server.")
                    }
                    busy = false
                    reload++
                }
            }
        )

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            rows.isEmpty() -> Text(
                if (mayCreate) {
                    "No design workshops yet. Tap New to start one — you can do it with no connection."
                } else {
                    // A DIFFERENT SENTENCE, because the other one names a button this account does
                    // not have. "Tap New" over a header with no New in it is the app telling somebody
                    // their screen is broken.
                    "No design workshops yet. An admin creates the workshop and gives you access; it " +
                        "then appears here and everything inside it is yours to fill in, with or " +
                        "without a connection."
                },
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )

            else -> rows.forEach { row ->
                WorkshopCard(
                    row = row,
                    busy = busy,
                    sending = sendingId == row.localId,
                    // OFFERED ON EVERY DEVICE-ONLY ROW, to every account that holds the draft —
                    // INCLUDING a designer, deliberately. Nothing here brings a workshop into
                    // existence; it decides which EXISTING workshop this device's unsent fortnight
                    // belongs to, which is the designer's own judgement about their own fieldwork.
                    // See `WorkshopDraftStore.adoptIntoWorkshop`.
                    //
                    // It is the whole reason this rule can ship without costing anybody a fortnight,
                    // so it is on the row rather than behind a menu.
                    onAdopt = if (row.localOnly && row.hasLocalDraft) {
                        { adopting = row }
                    } else {
                        null
                    },
                    onOpen = { onOpen(row.localId) },
                    onSend = {
                        busy = true
                        scope.launch {
                            // One engine, one order of operations: create the record if it is still
                            // local-only, upload every unacknowledged file, then push each stage
                            // whose payload has changed — resuming from whatever already landed
                            // rather than starting over. This used to be a bespoke routine on this
                            // screen that created the header, pushed the stages and sent no
                            // photographs at all, with no record of what had succeeded.
                            val result = runCatching {
                                repository.retryDesignWorkshopSync(appContext, row.localId)
                            }.getOrNull()
                            when {
                                result == null -> onError("“${row.title}” could not be sent.")
                                result.skipped -> onMessage(
                                    "No connection. “${row.title}” is safe on this device and will " +
                                        "upload as soon as there is signal."
                                )
                                result.refused > 0 -> onError(
                                    "${result.refused} item(s) of “${row.title}” were refused by the " +
                                        "server. Nothing has been deleted — open the details on the row " +
                                        "to see why."
                                )
                                result.didAnything -> onMessage(
                                    "“${row.title}”: sent ${result.stagesSent} stage(s) and " +
                                        "${result.mediaUploaded} file(s)."
                                )
                                result.stoppedOffline -> onMessage(
                                    "The connection dropped. Nothing has been lost — it will pick up " +
                                        "where it stopped."
                                )
                                else -> onMessage("“${row.title}” is already fully on the server.")
                            }
                            busy = false
                            reload++
                        }
                    },
                    onFreeSpace = {
                        busy = true
                        scope.launch {
                            // Only ever the bytes of files the server acknowledged an id for, and
                            // only because a person asked. See the KDoc on `releaseUploadedMedia`.
                            val (count, bytes) = runCatching {
                                WorkshopDraftStore.releaseUploadedMedia(appContext, row.localId)
                            }.getOrDefault(0 to 0L)
                            if (count == 0) {
                                onMessage("There was nothing safe to remove.")
                            } else {
                                onMessage(
                                    "Freed ${syncBytes(bytes)} by removing $count uploaded file(s) from " +
                                        "this phone. They are still on the server, but reports " +
                                        "generated on this device will no longer include them."
                                )
                            }
                            busy = false
                            reload++
                        }
                    }
                )
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    adopting?.let { row ->
        AdoptIntoWorkshopDialog(
            repository = repository,
            row = row,
            // EVERY WORKSHOP THIS LIST ALREADY KNOWS ABOUT, and no request of its own. A designer
            // doing this is by definition holding a draft that could not be created, which is very
            // often because they are in a courtyard — so a picker that needed the network would be
            // unavailable at exactly the moment it is reached for. The list has already walked every
            // page this account may see; those rows are the candidates.
            candidates = rows.filter { !it.localOnly },
            offline = offline,
            onDismiss = { adopting = null },
            onAdopt = { target ->
                adopting = null
                busy = true
                scope.launch {
                    val moved = runCatching {
                        WorkshopDraftStore.adoptIntoWorkshop(
                            context = appContext,
                            workshopId = row.localId,
                            remoteId = target,
                        )
                    }.getOrNull()
                    if (moved == null) {
                        onError(
                            "That draft could not be moved. If it has since been sent to the server " +
                                "on its own, nothing needed moving and nothing has been lost."
                        )
                    } else {
                        onMessage(
                            "“${row.title}” now belongs to the workshop you chose. Everything on this " +
                                "phone goes up into it on the next sync — nothing has been deleted."
                        )
                    }
                    busy = false
                    reload++
                }
            },
        )
    }

    if (showCreate) {
        CreateWorkshopDialog(
            repository = repository,
            onDismiss = { showCreate = false },
            onCreated = { id, wasLocal ->
                showCreate = false
                reload++
                if (wasLocal) {
                    onMessage("Started on this device. Send it to the server from this list once you have a connection.")
                }
                onOpen(id)
            },
            onError = onError,
        )
    }
}

// --------------------------------------------------------------------------------------
// Row
// --------------------------------------------------------------------------------------

@Composable
private fun WorkshopCard(
    row: WorkshopRow,
    busy: Boolean,
    sending: Boolean,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onFreeSpace: () -> Unit,
    /** Non-null only for a draft with no server workshop behind it — see the call site. */
    onAdopt: (() -> Unit)? = null,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
            ) {
                CompletenessRing(percent = row.percent)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        row.title,
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (row.subtitle.isNotBlank()) {
                        Text(row.subtitle, color = MaterialTheme.field.muted, fontSize = 12.sp)
                    }
                    Text(
                        "${row.requiredFilled} of ${row.requiredTotal} required fields",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
            }
            // THE SYNC STATE, ON EVERY ROW AND NOT ONLY ON THE LOCAL-ONLY ONES. The badge this
            // replaced appeared only when a workshop had no server id, which is a statement about
            // the HEADER: a workshop created on the server on day one and then captured offline for
            // thirteen days showed nothing at all and looked identical to one that was fully backed
            // up. What a designer needs before they leave a cluster is not "does a record exist" but
            // "has everything I captured left this phone".
            val status = row.status
            if (status != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    WorkshopSyncChip(status = status, sending = sending)
                    Spacer(Modifier.weight(1f))
                }
                if (!status.isFullySynced || status.releasableMedia > 0) {
                    WorkshopSyncActions(
                        status = status,
                        busy = busy || sending,
                        onRetry = onSend,
                        onFreeSpace = onFreeSpace,
                    )
                }
            }
            // ON THE ROW, NOT IN A MENU. This is the whole route out for a fortnight of fieldwork
            // captured in a draft that can no longer be created as a workshop of its own, and a
            // designer looking at a row that will not sync has to be able to see the answer from
            // here.
            onAdopt?.let { adopt ->
                Text(
                    "This workshop exists only on this phone. Once an admin has created it on the " +
                        "server, move this draft into it and everything you have captured — every " +
                        "stage, photograph and recording — goes up into that workshop.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                OutlinedButton(
                    onClick = adopt,
                    enabled = !busy && !sending,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Move into a workshop", fontSize = 13.sp) }
            }
        }
    }
}

/**
 * The completeness ring.
 *
 * Drawn rather than approximated with a determinate [androidx.compose.material3.CircularProgressIndicator]
 * because that control leaves a gap at the top for its indeterminate animation, so a workshop at 100%
 * renders as a ring that is visibly not closed — which reads, on a list a designer is scanning to
 * decide what is finished, as "almost".
 */
@Composable
private fun CompletenessRing(percent: Int) {
    val track = MaterialTheme.field.surface300
    val fill = if (percent >= 100) MaterialTheme.field.success else MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
        Canvas(modifier = Modifier.size(46.dp)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2f
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = box,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = fill,
                startAngle = -90f,
                sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = box,
                style = Stroke(width = stroke)
            )
        }
        Text("$percent", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --------------------------------------------------------------------------------------
// Create
// --------------------------------------------------------------------------------------

/**
 * Start a workshop.
 *
 * ONLY THE TITLE IS REQUIRED, matching the API. A workshop is created in a room on day one, before
 * the sanction order number is to hand; asking for the scheme, the cluster and the dates before the
 * record can exist would make the app unusable at the exact moment it is opened. The Basic-tier
 * fields of stage 1 are what the completeness gate enforces, later.
 *
 * A CREATE THAT CANNOT REACH THE SERVER STILL SUCCEEDS — FOR AN ACCOUNT THAT MAY CREATE ONE. It
 * becomes a local-only workshop with a [DW_LOCAL_ID_PREFIX] id, fully editable and fully exportable,
 * and the list offers to send it later. Refusing to create offline would mean the app works
 * everywhere except the field.
 *
 * ── AND THE ONE ACCOUNT FOR WHICH OFFLINE-STILL-SUCCEEDS WAS A TRAP ──────────────────────────────
 *
 * [classifyCreate] below has always refused a 403 correctly and written nothing to the device, for
 * the reason its own comment gives. But that refusal needs an ANSWER, and a designer in a courtyard
 * does not get one: the create fails as transient, a local draft is minted by design, twenty-two
 * stages and a fortnight of photographs go into it, and the 403 arrives at the first bar of signal —
 * permanently. A designer learning at sync that the fortnight in their hand can never be accepted is
 * the exact failure the create rule must not cause.
 *
 * So [mayMintLocalWorkshop] is asked FIRST, from the cached role, before the request and before a
 * byte is written. Nothing about the network is consulted, because the answer does not depend on it.
 */
@Composable
private fun CreateWorkshopDialog(
    repository: WorkshopRepository,
    onDismiss: () -> Unit,
    onCreated: (workshopId: String, localOnly: Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var craft by remember { mutableStateOf("") }
    var cluster by remember { mutableStateOf("") }
    var templateId by remember { mutableStateOf("DCH_STANDARD") }
    var templates by remember { mutableStateOf<List<ReportTemplateDto>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        templates = runCatching { repository.designWorkshopTemplates(appContext) }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New design workshop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Workshop title *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = craft,
                    onValueChange = { craft = it },
                    label = { Text("Craft") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cluster,
                    onValueChange = { cluster = it },
                    label = { Text("Cluster") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SearchableSelectField(
                    label = "Report template",
                    options = templateOptions(templates),
                    selectedValue = templateId,
                    includeNone = false,
                    onSelect = { picked -> if (picked.isNotBlank()) templateId = picked }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank(),
                onClick = {
                    // BEFORE THE NETWORK AND BEFORE THE DISK. See this dialog's header for the
                    // fortnight this ordering saves. The cached user is what the whole app already
                    // gates on offline; `known` is false only when nobody is signed in on this
                    // device at all, and the tri-state deliberately allows that case through rather
                    // than refusing an admin over a fact it has not read yet.
                    val cached = repository.cachedUser()
                    if (!mayMintLocalWorkshop(known = cached != null, role = cached?.role)) {
                        onError(DW_WORKSHOP_CREATE_REFUSAL)
                        onDismiss()
                    } else {
                    busy = true
                    scope.launch {
                        val body = DesignWorkshopCreateBody(
                            title = title.trim(),
                            templateId = templateId,
                            craftName = craft.trim().takeIf { it.isNotEmpty() },
                            clusterName = cluster.trim().takeIf { it.isNotEmpty() },
                        )
                        val remote = runCatching { repository.createDesignWorkshop(body) }
                        val outcome = classifyCreate(remote.exceptionOrNull()) { repository.isTransient(it) }
                        if (outcome is CreateOutcome.Refused) {
                            // Nothing is written to the device. A local draft would be a promise
                            // this app cannot keep, and it would sit in the list offering to sync
                            // for as long as the account lacks the capability.
                            onError(outcome.message)
                            busy = false
                            return@launch
                        }
                        val id = remote.getOrNull()?.id ?: (DW_LOCAL_ID_PREFIX + UUID.randomUUID())
                        runCatching {
                            // Seeded immediately, before the screen opens. A workshop whose local
                            // draft is only created on the first stage save is a workshop that
                            // vanishes from the offline list if the designer backs out of stage 1.
                            WorkshopDraftStore.update(appContext, id) { draft ->
                                draft.copy(
                                    title = body.title,
                                    templateId = body.templateId,
                                    remoteId = remote.getOrNull()?.id,
                                    ownerUserId = repository.cachedUser()?.id,
                                )
                            }
                        }.onFailure { error ->
                            onError(error.message ?: "Could not start the workshop on this device.")
                            busy = false
                            return@launch
                        }
                        busy = false
                        onCreated(id, remote.isFailure)
                    }
                    }
                }
            ) { Text(if (busy) "Starting…" else "Start") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Move a device-only draft into a workshop that exists on the server.
 *
 * ── WHY THE PICKER IS FED FROM THE LIST AND MAKES NO REQUEST OF ITS OWN ─────────────────────────
 *
 * A designer reaching this control is, by definition, holding a draft that could not be created —
 * which very often means they are in the courtyard where they captured it. A picker that needed a
 * round trip would be empty at exactly the moment it is used. The list screen has already walked
 * every page the server says this account may see and has merged them with what is on the disk, so
 * its own rows are the honest candidate set.
 *
 * AND IT SAYS WHEN THAT SET IS PARTIAL. If the list could not reach the server this dialog is
 * offering only the workshops this phone happens to have opened before, which may not include the
 * one the admin created an hour ago. Saying so is the difference between "your workshop is not here
 * yet" and "this app has lost your workshop".
 *
 * ── WHAT IT WARNS ABOUT BEFORE IT DOES IT ───────────────────────────────────────────────────────
 *
 * Adoption is not reversible from this screen: once the draft points at a workshop, the next sync
 * pushes twenty-two stages into it. Choosing the wrong workshop puts a fortnight of one cluster's
 * fieldwork inside another cluster's record, and unpicking that is a database job. So the
 * confirmation names the workshop being moved INTO, in full, rather than saying "this workshop".
 */
@Composable
private fun AdoptIntoWorkshopDialog(
    repository: WorkshopRepository,
    row: WorkshopRow,
    candidates: List<WorkshopRow>,
    offline: Boolean,
    onDismiss: () -> Unit,
    onAdopt: (remoteId: String) -> Unit,
) {
    var chosen by remember(row.localId) { mutableStateOf("") }
    val options = remember(candidates) {
        candidates.mapNotNull { candidate ->
            candidate.remoteId?.takeIf { it.isNotBlank() }?.let { id ->
                com.designprototype.workshop.ui.SelectOption(
                    value = id,
                    label = candidate.title,
                    hint = candidate.subtitle.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${row.title}” into a workshop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Everything captured on this phone under “${row.title}” — every stage, every " +
                        "photograph, every recording — starts belonging to the workshop you pick, and " +
                        "goes up into it on the next sync. Nothing is deleted and nothing is sent " +
                        "anywhere else.",
                    color = MaterialTheme.field.body,
                    fontSize = 12.sp
                )
                if (options.isEmpty()) {
                    Text(
                        if (offline) {
                            "There are no workshops on this phone to move it into, and the server " +
                                "could not be reached — so this list may not be the whole story. Ask " +
                                "an admin to create the workshop, then open this list once with a " +
                                "connection and try again."
                        } else {
                            "You do not have access to any workshop on the server yet. Ask an admin " +
                                "to create one for your cluster and give you access; it appears here " +
                                "and this draft can then be moved into it. Nothing on this phone is " +
                                "at risk in the meantime."
                        },
                        color = MaterialTheme.field.warning,
                        fontSize = 12.sp
                    )
                } else {
                    SearchableSelectField(
                        label = "Workshop to move it into",
                        options = options,
                        selectedValue = chosen,
                        includeNone = false,
                        onSelect = { picked -> chosen = picked }
                    )
                    if (offline) {
                        // PARTIAL, AND SAID SO. A designer who cannot find the workshop the admin
                        // just made would otherwise conclude the admin had not made it.
                        Text(
                            "The server could not be reached, so this list holds only the workshops " +
                                "this phone already knows about. A workshop created for you today may " +
                                "not be here until you open this list with a connection.",
                            color = MaterialTheme.field.warning,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        "Check the name. Moving it into the wrong workshop files this fortnight's " +
                            "fieldwork under another cluster, and this screen cannot move it back.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotBlank(),
                onClick = { onAdopt(chosen) }
            ) { Text("Move it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * What became of `POST /design-workshops`, told apart the only way that matters to the person
 * standing there.
 *
 * ── A REFUSAL IS NOT A DISCONNECTION ─────────────────────────────────────────────────────────────
 *
 * Every failure used to become one sentence: "Started on this device. Send it to the server from
 * this list once you have a connection." For a 403 that sentence is false in both halves. There IS a
 * connection — the server refused the account — and the work is not queued for anything, because
 * every future attempt will be refused too. A researcher told that goes on to capture 22 stages and
 * a fortnight of photographs believing they are on their way to the office. This is the same shape
 * as the report-download bug in SESSION_HANDOVER.md: a permission failure reaching a person as a
 * network message.
 *
 * `isTransient` is the ONE test for "this will succeed later", the same predicate
 * `WorkshopSync.syncOneWorkshop` triages with. Passed in rather than reached for so this decision can
 * be asserted without an HTTP stack — and so there is no second opinion about what "offline" means,
 * which would either strand a queue for ever or claim a rejection had been queued.
 */
internal sealed interface CreateOutcome {
    /** It landed, or it failed for a reason that will pass. A local draft is an honest promise. */
    data object Local : CreateOutcome

    /** The server said no and will keep saying no. [message] is the server's own words. */
    data class Refused(val message: String) : CreateOutcome
}

internal fun classifyCreate(error: Throwable?, isTransient: (Throwable) -> Boolean): CreateOutcome =
    when {
        error == null -> CreateOutcome.Local
        isTransient(error) -> CreateOutcome.Local
        else -> CreateOutcome.Refused(
            error.apiErrorMessage("The server refused to start this workshop.")
        )
    }

// --------------------------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------------------------

/**
 * The template picker's options.
 *
 * The description rides along as the trailing hint rather than being dropped, because "DCH standard"
 * and "Implementing agency format" are indistinguishable to anyone who has not laid both out, and
 * picking the wrong one is only discovered when the .docx comes back from the department.
 */
internal fun templateOptions(templates: List<ReportTemplateDto>): List<com.designprototype.workshop.ui.SelectOption> =
    templates.map { template ->
        com.designprototype.workshop.ui.SelectOption(
            value = template.id,
            label = template.name.ifBlank { template.id },
            hint = template.description.takeIf { it.isNotBlank() }
        )
    }

private suspend fun rowFor(
    context: android.content.Context,
    localId: String,
    remoteId: String?,
    title: String,
    subtitle: String,
    updatedAt: String,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
): WorkshopRow {
    // OFF DISK ONLY. This runs once per row of the workshop list, so a network read here would be
    // one request per workshop on a screen a designer opens forty times a day; and the list's job is
    // to say what this device holds. A workshop whose definition has never been read scores exactly
    // as it did before this feature existed, which is the honest answer for it — see
    // [computeWorkshopCompleteness].
    val stages = computeWorkshopCompleteness(
        schema, draft, DwCustomSectionStore.load(context, localId),
    )
    return WorkshopRow(
        localId = localId,
        remoteId = remoteId,
        title = title.ifBlank { "Untitled workshop" },
        subtitle = subtitle,
        percent = overallPercent(stages),
        requiredFilled = stages.sumOf { it.requiredFilled },
        requiredTotal = stages.sumOf { it.requiredTotal },
        updatedAt = updatedAt.ifBlank { Instant.EPOCH.toString() },
        hasLocalDraft = draft != null,
        status = draft?.let { WorkshopSyncEngine.statusOf(context, schema, it) },
    )
}

// `sendToServer` USED TO LIVE HERE and has been deleted rather than kept beside the engine. It
// created the header, wrote the id back and pushed the stages — and that was all it did. It sent no
// photographs whatsoever, which meant every stage it pushed carried this device's private media
// UUIDs into the server's media references; it kept no record of which stages had landed, so a
// second tap re-sent all twenty-two; and it swallowed every stage failure, so a workshop could
// report "created on the server and all its stages pushed" having pushed none of them. All of that
// is now [WorkshopSyncEngine], where the order, the resumability and the triage are written down
// once and tested against `backend/tests/test_stage_sync.py`'s semantics.
