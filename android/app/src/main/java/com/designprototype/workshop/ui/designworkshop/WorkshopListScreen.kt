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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DW_LOCAL_ID_PREFIX
import com.designprototype.workshop.data.DW_WORKSHOP_CREATE_REFUSAL
import com.designprototype.workshop.data.DesignWorkshopDefaultDto
import com.designprototype.workshop.data.DesignWorkshopCreateBody
import com.designprototype.workshop.data.DwEligibleViewerDto
import com.designprototype.workshop.data.DwEligibleViewers
import com.designprototype.workshop.data.DW_MAX_NAMED_DESIGNERS
import com.designprototype.workshop.data.DW_LOCAL_DRAFT_LINK_PROMPT
import com.designprototype.workshop.data.DW_LOCAL_DRAFT_UNLINKED
import com.designprototype.workshop.data.DW_LOCAL_START_ACTION
import com.designprototype.workshop.data.DW_LOCAL_START_NOTE
import com.designprototype.workshop.data.DwDraftStart
import com.designprototype.workshop.data.dwClassifyDraftStart
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
import com.designprototype.workshop.data.dwAdoptCandidateNotice
import com.designprototype.workshop.data.dwAdoptNoCandidatesMessage
import com.designprototype.workshop.data.dwNamedDesignerId
import com.designprototype.workshop.data.dwNamedDesignerTeam
import com.designprototype.workshop.data.dwOrderedDesignerPicks
import com.designprototype.workshop.data.dwPersonLabel
import com.designprototype.workshop.data.dwViewerAdministrationMissing
import com.designprototype.workshop.data.dwViewerOfferNotice
import com.designprototype.workshop.data.dwViewerSearchTerm
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.overallPercent
import com.designprototype.workshop.data.visibleDesignWorkshops
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.requiredMarked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
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
 * How long a search box on this screen waits before it asks the server.
 *
 * TWO BOXES USE IT NOW, and they are paced together deliberately. The workshop search above the list
 * is the first: its local half is filtered in memory and is therefore instant either way, so this
 * only paces the network call and can be generous enough to cover an ordinary typing speed. The
 * second is the designer picker inside `CreateWorkshopDialog`, whose search is entirely the server's
 * — `GET /design-workshops/eligible-viewers` — and which therefore needs the pacing more, not less:
 * that endpoint's `ILIKE '%term%'` over `User` is a scan no index can answer, so every keystroke that
 * escapes this is a full scan of the largest table in the repository. One number rather than two,
 * because two boxes on one screen that respond at visibly different speeds read as one of them being
 * broken. It is the same 350ms `WorkshopViewersScreen` uses for the same endpoint.
 */
private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * May this account be offered "Move into a workshop" — the web's `offerMove`, as a pure rule.
 *
 * LIFTED OUT OF THE COMPOSABLE for the reason `resolveSearchable` gives for the same move: there is
 * no `ui-test-junit4` and no Robolectric in `app/build.gradle.kts`, so the JVM suite cannot render
 * this screen to look at it, and a decision left inline inside an `@Composable` cannot be asserted
 * at all. The browser pins its own copy by reading the SOURCE TEXT —
 * `design-workshop-adopt-scope-unit.spec.ts` asserts the literal
 * `const offerMove = !allowCreate && allowWork;` — which is that instinct reaching for the only tool
 * that file had. This one can be called instead.
 *
 * See the `offerMove` value inside [WorkshopListScreen] for the argument each half carries.
 */
internal fun dwOfferDraftMove(mayCreate: Boolean, mayRunWorkshops: Boolean): Boolean =
    !mayCreate && mayRunWorkshops

/**
 * Does a DEVICE-ONLY row survive the type filter? True means "draw it".
 *
 * THREE ARMS AND THE MIDDLE ONE IS THE WHOLE POINT. No filter shows everything; a draft that names
 * its type is tested exactly as a server row is; and a draft with NO type is shown whatever the
 * filter says, because hiding a fortnight of fieldwork behind a filter that has nothing to test is
 * the worse of the two errors. The rows that take that third arm are counted and named on screen —
 * see [WorkshopListScreen]'s `unfilterableLocal`.
 *
 * BLANK IS TREATED AS NULL. A draft can carry `""` only from a build that wrote one before
 * [com.designprototype.workshop.data.WorkshopDraft.workshopKind] folded a blank away; testing the
 * filter against a string no workshop can have would hide the row rather than show it, which is the
 * one direction this rule must never fail in.
 */
internal fun dwLocalRowPassesKind(workshopKind: String?, kindFilter: String): Boolean =
    kindFilter.isBlank() || workshopKind.isNullOrBlank() || workshopKind == kindFilter

/**
 * How many names the create form asks for when offering the ones already on record.
 *
 * ONE PAGE, DELIBERATELY SMALL, AND THE PICKER SAYS SO. The offer is a convenience over a box that
 * always works, so the whole corpus is not what is wanted here — and the anchored menu builds every
 * row eagerly inside a scrolling column, which is right for twenty and is not where two hundred
 * belong. Because it IS one truncated page, the picker passes `searchable = false` (a filter box over
 * a page filters the page and answers "nothing matches" about a workshop that exists) and the caller
 * owes the sentence naming what reaches the rest — which, on this control, is typing the name.
 */
private const val NAME_OFFER_PAGE_SIZE = 20

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
    /**
     * THE SERVER RETURNED THIS ROW ON THE WALK THAT BUILT THIS LIST — not "this device remembers a
     * workshop by that id".
     *
     * **A DIFFERENT FACT FROM [remoteId], AND THE DIFFERENCE IS AN ACCESS CHECK.** A draft on the
     * disk carries the id of the workshop it was last pointed at, and that id survives everything:
     * a grant revoked from the viewers screen, a designer taken off a workshop, the narrowing that
     * came with naming several designers at create. So a row can hold a perfectly good-looking
     * `remoteId` for a workshop `GET /design-workshops` no longer offers this account — this
     * device's memory is stale in the PERMISSIVE direction, which is the only direction that
     * matters.
     *
     * It is read by exactly one thing, and that is why it exists: [AdoptIntoWorkshopDialog] offers
     * DESTINATIONS. Adoption is one-way and unrepeatable, and pointing a fortnight of fieldwork at
     * a workshop the server will answer 404 for strands it behind a refusal no sync can clear.
     * Everywhere else, showing a remembered row is right — a designer's own list must not go blank
     * because a request failed.
     */
    val fromServer: Boolean = false,
) {
    val localOnly: Boolean get() = remoteId == null
}

/**
 * What a pass says about the workshops it would not even attempt — see
 * [SyncPassResult.blockedByRefusal].
 *
 * A CLAUSE RATHER THAN A SENTENCE OF ITS OWN, and it leads with a space, exactly like
 * `refusedAnswersLine` and `deletionsLine` beside it: the same fact has to be appendable to a pass
 * that did plenty and to a pass that did nothing at all, and there is one copy of the wording.
 *
 * It says a sync CANNOT move them, in those words. "Could not be sent" reads as a connection and
 * sends a designer walking up a hill for signal; what is actually waiting is an admin, or the Try
 * again button after a person has done something about a refusal.
 */
private fun blockedLine(blocked: Int): String = if (blocked <= 0) "" else {
    " $blocked workshop${if (blocked == 1) "" else "s"} " +
        "${if (blocked == 1) "was" else "were"} not sent and a sync cannot move " +
        "${if (blocked == 1) "it" else "them"} — ${if (blocked == 1) "it is" else "they are"} " +
        "waiting on a person, not on the connection. Nothing has been deleted: open the workshop " +
        "below to see what is needed."
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
     * The `WORKSHOP_KIND` this list is narrowed to, or `""` for every type.
     *
     * EMPTY MEANS EVERYTHING, BY ABSENCE (R1) — never by an all-selected state. `""` is what the
     * picker's "none" row writes, `WorkshopRepository.designWorkshops` folds it to an absent query
     * parameter, and there is no second spelling of "show me all of them" for a later reader to
     * disagree with. The same rule and the same reserved-empty as the web's type filter on this
     * screen.
     */
    var kindFilter by remember { mutableStateOf("") }
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
    /**
     * May this account do the WORK of a design workshop - the wider set, read for exactly one
     * question: [dwClassifyDraftStart]'s middle arm, which is the owner's clause about a designer
     * with no signal.
     *
     * A SECOND PREDICATE AND NOT A LOOSENING OF THE FIRST. [mayCreate] answers "may this account
     * bring a workshop into existence" and is unchanged. Keyed the same way and for the same reason.
     */
    val mayRunWorkshops = remember(cachedSession?.id, cachedSession?.role) {
        cachedSession != null && FieldPermissions.canRunDesignWorkshops(cachedSession)
    }
    /**
     * May this account be offered "Move into a workshop" AT ALL — the web's `offerMove`, arm for arm.
     *
     * ── IT NARROWS A CONTROL THIS FILE USED TO OFFER TO EVERYONE, AND THE OLD ARGUMENT WAS SOUND ──
     *
     * The rule that stood on [WorkshopCard]'s `onAdopt` read: *"OFFERED ON EVERY DEVICE-ONLY ROW, to
     * every account that holds the draft — INCLUDING a designer, deliberately. Nothing here brings a
     * workshop into existence; it decides which EXISTING workshop this device's unsent fortnight
     * belongs to, which is the designer's own judgement about their own fieldwork."* Every clause of
     * that is still true, and NONE of it is overruled: a designer is admitted by this predicate
     * exactly as before, because a designer is the whole reason the control exists.
     *
     * WHAT IT OVERRULES IS THE WORD "EVERY", AND ONLY FOR AN ADMIN. `!mayCreate` is the browser's
     * narrowing and its reason is on `design-workshops/page.tsx`: *"An admin holding a device-only
     * draft does not need this: their next sync creates the workshop and the draft resolves itself.
     * Showing them a control that quietly re-files a fortnight of fieldwork into a DIFFERENT
     * workshop, for no benefit, is a way to lose work by mis-tap."* The move is not undoable from
     * this screen, so the trade is a control that can only lose work against a control that can only
     * do what the next sync does anyway.
     *
     * `mayRunWorkshops` is the other half and it is not redundant: the sheet files a fortnight into a
     * workshop, which is work, and an account outside `DESIGN_WORKSHOP_ROLES` holding a stray draft
     * must not be handed a filing control for a surface it does not run.
     *
     * THIS SCREEN ALREADY BELIEVED IT — IN ONE OF THE TWO PLACES. The banner below has always been
     * gated on `!mayCreate`, so an admin was told nothing about unlinked drafts and then shown the
     * button the sentence would have named. One predicate now answers both, which is what stops them
     * disagreeing again.
     */
    val offerMove = dwOfferDraftMove(mayCreate = mayCreate, mayRunWorkshops = mayRunWorkshops)
    /** The title in the offline start box, and whether it is being written. */
    var localTitle by remember { mutableStateOf("") }
    var showCreate by remember(startCreating) { mutableStateOf(startCreating && mayCreate) }
    /** The device-only draft the designer is moving into a real workshop, while they choose one. */
    var adopting by remember { mutableStateOf<WorkshopRow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var offline by remember { mutableStateOf(false) }
    /** Rows gathered vs rows the server says exist, set only when the walk came back short. */
    var partial by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /**
     * How many device-only workshops are on screen that the type filter could not be applied to.
     *
     * COUNTED AND SAID, never quietly left in the list and never quietly dropped from it. Dropping
     * such a row would hide a fortnight of work behind a filter; leaving it silently would be a
     * filtered list quietly showing rows that do not match it, which teaches a designer that the
     * filter does not work. So they stay, and the sentence below says how many and why.
     *
     * ── IT USED TO BE EVERY DEVICE-ONLY ROW, AND THAT REASON HAS BEEN REMOVED RATHER THAN RESTATED ─
     *
     * The clause here read: *"A workshop minted in a courtyard has no type on this device at all —
     * `WorkshopDraft` carries none, and the column is promoted from stage 1 by the server — so the
     * filter has nothing to test it against."* The first half of that is no longer true.
     * [WorkshopDraft.workshopKind] now records the type the designer picked in the create dialog, so
     * a courtyard workshop is filtered on its own answer; the second half is still true and still
     * irrelevant, because promotion from stage 1 is what happens AFTER a sync, not what this list
     * has to wait for.
     *
     * WHAT IS LEFT IS THE ROW NOBODY CHOSE A TYPE FOR — a draft written before that field existed, or
     * one started with the type left blank. Those genuinely have nothing to test, which is the
     * original argument still doing its work over a much smaller set.
     */
    var unfilterableLocal by remember { mutableIntStateOf(0) }
    /**
     * Which workshop the background pass is uploading right now, straight from the engine.
     *
     * Read rather than inferred, so the row that is genuinely being sent says "Sending…" and the
     * nineteen behind it keep saying what they are still waiting on. A single global spinner would
     * make every row look busy, which is precisely the ambiguity this screen exists to remove.
     */
    val sendingId by WorkshopSyncEngine.busyWorkshop.collectAsState()
    val syncRevision by WorkshopSyncEngine.revision.collectAsState()

    LaunchedEffect(reload, search, kindFilter) {
        // Debounced, and only for typing. Keying the effect on `search` means every keystroke
        // cancels the previous run and starts a new one, so without this a five-letter craft name is
        // five list requests on a metered field connection — and the first four are replies nobody
        // will ever look at. `reload` is a deliberate action (a create, a send) and must not wait.
        //
        // `kindFilter` is keyed here too and is DELIBERATELY NOT DEBOUNCED: it changes by a tap on a
        // picker, not by typing, so there is no run of intermediate values to swallow and making a
        // deliberate choice wait a third of a second reads as the control being slow.
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
                repository.visibleDesignWorkshops(
                    search = search.takeIf { it.isNotBlank() },
                    // NARROWED ON THE SERVER, never over the rows this walk gathered — see
                    // [visibleDesignWorkshops]. A local filter would search only the pages that
                    // happened to be fetched and answer "none of that type" over a repository full
                    // of them, and it would leave `total` counting every type while the list showed
                    // one, so the "Showing N of M" sentence beneath would be arithmetic about two
                    // different lists.
                    workshopKind = kindFilter.takeIf { it.isNotBlank() },
                )
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
                ).copy(fromServer = true)
            }

            val covered = fromServer.map { it.localId }.toSet()
            val localOnly = drafts
                .filterNot { (id, _) -> id in covered }
                /*
                  THE TYPE FILTER NOW REACHES MOST OF THESE, which it could not before
                  [WorkshopDraft.workshopKind] existed. A draft minted since then carries the type
                  the designer picked in the create dialog, so a filtered list can honestly leave it
                  out — exactly as the server leaves out its own rows.

                  A DRAFT WITH NO TYPE STILL STAYS, and that is the surviving half of the old rule:
                  a draft written before that field existed, or one started with no type chosen, has
                  nothing for the filter to test, and hiding a fortnight of work behind a filter that
                  cannot read it is the worse of the two errors. [unfilterableLocal] counts exactly
                  those and the sentence under the filter says how many and why.

                  APPLIED BEFORE `rowFor`, which is not a micro-optimisation: `rowFor` reads the
                  custom-section store off the disk for every row it builds, so filtering afterwards
                  would do that work for rows nobody is going to see.
                */
                .filter { (_, draft) -> dwLocalRowPassesKind(draft.workshopKind, kindFilter) }
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

            // THE ROWS THE FILTER STILL CANNOT BE APPLIED TO — the UNTYPED ones, and only those.
            //
            // It used to be every device-only row, because the draft held no kind at all: "the draft
            // on this device holds no kind, so the only honest choices are to show them and say so,
            // or to hide a fortnight of work behind a filter that has nothing to test." That trade
            // is unchanged and still decides these rows; what changed is how many of them there are,
            // because a draft now records the type its designer chose. The count is still set even
            // when it is zero, so a designer who clears the filter loses the sentence with it.
            //
            // Counted off the DRAFT rather than the row, because `WorkshopRow` deliberately carries
            // only what a row draws, and after the search filter, so the number describes the rows
            // actually on screen rather than the ones a title search removed.
            unfilterableLocal = if (kindFilter.isBlank()) {
                0
            } else {
                localOnly.count { row -> draftById[row.localId]?.workshopKind.isNullOrBlank() }
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

        /*
          FILTER BY TYPE — the read half of the pair the owner asked for, and the handset's copy of
          the control the browser grew on this same screen.

          DRAWN ONLY WHEN THERE ARE TYPES TO OFFER. `workshopKindOptions` answers off the registry,
          which on this client always resolves — memory, then `filesDir`, then the bundled APK asset
          — so the empty case means the enum has been RETIRED server-side, and a filter whose only
          row is "Any type" is a control that cannot do anything.

          "Any type" IS THE EMPTY VALUE AND IT IS FIRST, spelled exactly as the web spells it and
          built as an ordinary first option rather than through `includeNone`: this is the row that
          takes the filter off, and the reader has to be able to see the way back on the same list
          they used to get here.

          `searchable = false` — six members of a vocabulary compiled into this app is precisely the
          class the shared threshold answers correctly, and six is under it anyway; passing the
          ruling explicitly would be overruling a count that is already right.
        */
        val kindChoices = workshopKindOptions(schema)
        if (kindChoices.isNotEmpty()) {
            SearchableSelectField(
                label = "Type of workshop",
                options = listOf(com.designprototype.workshop.ui.SelectOption("", "Any type")) + kindChoices,
                selectedValue = kindFilter,
                includeNone = false,
                enabled = !busy,
                onSelect = { picked -> kindFilter = picked }
            )
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
                /*
                  THE LAST CLAUSE OF THAT REFUSAL, TURNED INTO A CONTROL.

                  It ends "Any workshop you already have access to is open to you now", which was
                  true and was not actionable: the designer had to read it and then find the right
                  row in a list that may be twenty long and is paged. The owner asked for the other
                  half on 2026-08-28 — a chooser of the workshops they are already part of, opening
                  by default on the one they were MOST RECENTLY GIVEN ACCESS TO.

                  ONE ROW AND NOT A DROPDOWN, which is where this departs from the web deliberately.
                  The web puts a `<select>` here because its refusal panel sits above a table the
                  reader has to scroll past; on a handset the designer's whole list is already
                  directly below this panel and is scrollable with one thumb, so a second control
                  listing the same rows would be two ways to pick one thing. What the list CANNOT do
                  is say which row is the most recently allocated one — that is
                  `DesignWorkshopViewer.createdAt`, which is on no payload this client can see — so
                  the answer that only the server has is what gets a control.

                  IT OPENS; IT NEVER CREATES. Nothing here widens `canCreateDesignWorkshops`, and the
                  sentence above still says whose job that is.
                */
                DwMostRecentlyAllocated(repository = repository, onOpen = onOpen)

                /*
                  AND THE OTHER HALF OF THE CLAUSE - THE ONE THAT ONLY APPLIES WITH NO SIGNAL.

                  The owner: "if they are offline, let them create one for the time being, and when
                  the internet comes back up, let them link it to one of the workshops that they
                  have access to." With a connection, the row above IS the better answer and this is
                  not drawn at all; with none, there is no answer at all and a designer standing in
                  a courtyard opens a paper notebook instead.

                  INSIDE THIS PANEL RATHER THAN IN THE HEADER, and that placement is the honesty.
                  The header's New button is gated to nothing for this account on purpose, and a
                  different button in the same place would read as that rule quietly reversing. Here
                  the refusal above it is still the first thing read, and this is plainly the
                  exception it names.

                  THE GATE IS ASKED AT THE TAP, NOT AT THE DRAW - see the confirm below. A phone can
                  find a bar of signal between this composing and a thumb landing on it.
                */
                if (offline && mayRunWorkshops) {
                    Text(
                        DW_LOCAL_START_NOTE,
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    OutlinedTextField(
                        value = localTitle,
                        onValueChange = { localTitle = it },
                        label = { Text("Workshop title") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        // Disabled on an empty title only. Everything else this control could
                        // refuse is refused by [dwClassifyDraftStart] below, which is the one place
                        // that knows the rule.
                        enabled = !busy && localTitle.isNotBlank(),
                        onClick = {
                            /*
                              READ AT THE TAP AND FROM THE CACHED USER, exactly as the create dialog
                              does and for the same reason: the answer must not depend on a round
                              trip, and the phone already holds the signed-in account.

                              `reachable` IS `ConnectivityObserver`, NOT THE `offline` FLAG ABOVE.
                              That flag records a list read that failed some seconds ago, which is
                              the right evidence for DRAWING this control and the wrong evidence for
                              writing to disk: the whole point of the clause is that a workshop is
                              minted here only while the repository genuinely cannot be reached, and
                              a phone that has since found signal has the better answer available to
                              it again.
                            */
                            val cached = repository.cachedUser()
                            val start = dwClassifyDraftStart(
                                mayCreate = mayMintLocalWorkshop(
                                    known = cached != null,
                                    role = cached?.role,
                                ),
                                mayRunWorkshops = cached != null &&
                                    FieldPermissions.canRunDesignWorkshops(cached),
                                reachable = ConnectivityObserver.isOnline(appContext),
                            )
                            if (start != DwDraftStart.LINK_LATER) {
                                onError(DW_WORKSHOP_CREATE_REFUSAL)
                            } else {
                                busy = true
                                val typed = localTitle.trim()
                                localTitle = ""
                                scope.launch {
                                    val id = DW_LOCAL_ID_PREFIX + UUID.randomUUID()
                                    val written = runCatching {
                                        // Seeded immediately, before the screen opens, for the
                                        // reason the create dialog gives: a workshop whose draft is
                                        // only written on the first stage save vanishes from this
                                        // list if the designer backs out of stage 1.
                                        WorkshopDraftStore.update(appContext, id) { draft ->
                                            draft.copy(
                                                title = typed,
                                                // NO `remoteId`, and no `createSentAt` either:
                                                // nothing was sent, so nothing is outstanding, and a
                                                // stamp here would arm `resolveInterruptedCreate`
                                                // against a request that never left the handset.
                                                ownerUserId = cached?.id,
                                            )
                                        }
                                    }.isSuccess
                                    busy = false
                                    if (!written) {
                                        onError(
                                            "That could not be written to this phone, so nothing " +
                                                "has been started and nothing has been lost."
                                        )
                                    } else {
                                        reload++
                                        onOpen(id)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(DW_LOCAL_START_ACTION, fontSize = 13.sp) }
                }
            }
        }

        if (offline) {
            Text(
                "The server could not be reached. Showing what is stored on this device.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp
            )
        }

        /*
          THE PROMPT THE OWNER ASKED FOR: "when the internet comes back up, let them link it to one
          of the workshops that they have access to."

          ONLINE ONLY, which is the whole point - the row is marked from the moment the draft is
          minted, and this is the arrival of the moment to act. Offline it would be an instruction
          nobody can follow, because the adopt sheet holds the move until the repository has said
          which workshops this account may actually open.

          IT DOES NOT OPEN THE SHEET ITSELF. With more than one unlinked workshop the screen cannot
          know which one is meant, and choosing for the designer is how a fortnight is filed under
          the wrong cluster - so it names the count and sends them to the row, where the title is.
        */
        // [offerMove] AND NOT `!mayCreate` ALONE, so this sentence cannot name a control the row
        // below has decided not to draw. The browser counts the same way — `unlinkedCount =
        // offerMove ? orphanDrafts.size : 0`.
        val unlinkedCount = if (offerMove) rows.count { it.localOnly && it.hasLocalDraft } else 0
        if (!offline && unlinkedCount > 0) {
            Text(
                (if (unlinkedCount == 1) {
                    "One workshop here was started on this device and is not linked to a workshop yet. "
                } else {
                    "$unlinkedCount workshops here were started on this device and are not linked " +
                        "to a workshop yet. "
                }) + DW_LOCAL_DRAFT_LINK_PROMPT + " Use “Move into a workshop” on the row.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp,
                lineHeight = 17.sp
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

        // Said rather than left to be inferred from a filtered list with unfiltered rows in it. See
        // [unfilterableLocal] for why the filter cannot reach them and why hiding them is worse.
        if (unfilterableLocal > 0) {
            Text(
                // "…once they sync" UNTIL 2026-08-31, AND IT IS NO LONGER THE REASON. A draft has
                // recorded its type since [WorkshopDraft.workshopKind] landed, so a synced-or-not
                // row is filtered on what the designer picked; what is left here is the row nobody
                // picked a type FOR, which is a different fact and a different next move.
                "$unfilterableLocal workshop${if (unfilterableLocal == 1) "" else "s"} on this device " +
                    "${if (unfilterableLocal == 1) "is" else "are"} shown whatever the filter says — " +
                    "no type was chosen for ${if (unfilterableLocal == 1) "it" else "them"}.",
                color = MaterialTheme.field.muted,
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
                                // A workshop the pass would not even attempt is invisible in every
                                // number above it, and a pass that moved nineteen stages of one
                                // workshop while a second one sits behind a refusal must not report
                                // only the nineteen. Same argument as `result.refused` two clauses
                                // up, for the case where nothing was sent AT ALL.
                                append(blockedLine(result.blockedByRefusal))
                                // Nothing was filed, an id was recovered — and the counts above say
                                // "0 stage(s)" for a pass whose whole achievement was that.
                                if (result.workshopsResumed > 0) {
                                    append(
                                        " ${result.workshopsResumed} workshop(s) turned out to be on " +
                                            "the server already and have been linked to this phone " +
                                            "rather than sent a second time."
                                    )
                                }
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
                                append(blockedLine(result.blockedByRefusal))
                            }
                        )
                        // ABOVE THE OFFLINE LINE FOR THE REASON THE ARM ABOVE IS, AND IT IS THE ARM
                        // THAT WAS MISSING. A workshop this pass would not attempt — an account that
                        // may not create one, or a create failure only "Try again" clears — leaves
                        // every counter at zero, so this fell all the way to "Everything on this
                        // device is already on the server" over a fortnight with no server record at
                        // all. That is the same untrue sentence the two blocks above this button
                        // were written to stop, reaching the designer by a third door.
                        result.blockedByRefusal > 0 -> onMessage(
                            buildString {
                                append(blockedLine(result.blockedByRefusal).trimStart())
                                if (result.stoppedOffline) {
                                    append(" The connection then dropped, so nothing after that was ")
                                    append("tried — none of it has been lost.")
                                }
                                append(refusedAnswersLine)
                                append(deletionsLine)
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
                    // OFFERED ON EVERY DEVICE-ONLY ROW THIS ACCOUNT SHOULD BE OFFERED ONE FOR —
                    // INCLUDING a designer, deliberately, which is the half of the old rule that
                    // still stands and is the whole reason the control exists. Nothing here brings a
                    // workshop into existence; it decides which EXISTING workshop this device's
                    // unsent fortnight belongs to, which is the designer's own judgement about their
                    // own fieldwork. See `WorkshopDraftStore.adoptIntoWorkshop`.
                    //
                    // It is the whole reason this rule can ship without costing anybody a fortnight,
                    // so it is on the row rather than behind a menu.
                    //
                    // THE WORD THAT CHANGED IS "EVERY": this used to read `row.localOnly &&
                    // row.hasLocalDraft` alone, which offered an ADMIN a control that can only lose
                    // their work — theirs syncs itself. See [offerMove] for that argument in full and
                    // for why the banner above had already been narrowed and this had not.
                    onAdopt = if (offerMove && row.localOnly && row.hasLocalDraft) {
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
                                // "Try again" CLEARS a create failure before the pass runs (see
                                // `retryWorkshop`), so what reaches this arm is the refusal the pass
                                // then recorded again from scratch: this account may not create a
                                // workshop. Without it the button answered "“…” is already fully on
                                // the server" over a draft that has never been anywhere near it.
                                result.blockedByRefusal > 0 -> onError(
                                    "“${row.title}” was not sent, and a sync cannot move it — it is " +
                                        "waiting on a person, not on the connection. Nothing has " +
                                        "been deleted: open the details on the row to see what is " +
                                        "needed."
                                )
                                result.didAnything -> onMessage(
                                    "“${row.title}”: sent ${result.stagesSent} stage(s) and " +
                                        "${result.mediaUploaded} file(s)." +
                                        // The id was recovered rather than a workshop filed; the two
                                        // counts above are both 0 on the pass that does only that.
                                        if (result.workshopsResumed > 0) {
                                            " It turned out to be on the server already and has been " +
                                                "linked to this phone rather than sent a second time."
                                        } else {
                                            ""
                                        }
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
            /*
              EVERY WORKSHOP THE SERVER JUST SAID THIS ACCOUNT MAY OPEN — and no request of its own.

              STILL NO ROUND TRIP. A designer doing this is by definition holding a draft that could
              not be created, which is very often because they are in a courtyard; a picker that
              needed the network would be unavailable at exactly the moment it is reached for. The
              list screen has already walked every page this account may see, so its rows are the
              candidate set and this dialog spends nothing.

              BUT ONLY THE ROWS THE WALK ACTUALLY RETURNED, and that filter is new. `remoteId` is a
              memory on the disk: it outlives a grant revoked from the viewers screen, a designer
              taken off a workshop, and the narrowing that arrived with naming several designers at
              create — so a row can carry a good-looking id for a workshop the server no longer
              offers this account. Everywhere else on this screen showing that row is right. As a
              DESTINATION it is not: adoption is one-way and unrepeatable, and pointing a fortnight
              at a workshop that answers 404 strands it behind a refusal no sync pass can clear. See
              [WorkshopRow.fromServer].

              OFFLINE THE REMEMBERED ROWS ARE SHOWN AND MAY NOT BE CHOSEN, which is the same split
              the browser makes. With no answer from the server there is nothing to confirm against,
              and refusing to draw anything would take the feature away in the courtyard it exists
              for — so the rows are offered as a LIST, [dwAdoptCandidateNotice] says exactly what
              they are, and [AdoptIntoWorkshopDialog]'s confirm button is disabled. Its header
              carries the argument in full.

              THIS PARAGRAPH USED TO END "What is NOT needed here is the browser's extra hold the
              button until the repository has answered once gate — this control lives ON a row, so
              the walk has already finished (or already failed, setting `offline`) before the dialog
              can be opened at all", and every clause of that was true except the conclusion. A walk
              that has FINISHED is not a walk that has SUCCEEDED: `offline` is set precisely when it
              failed, and the candidate set is then this phone's memory, which is stale in the
              PERMISSIVE direction. The gate is here, it is the confirm button, and it has been since
              the same wave that put it on the browser. Two comments on one screen disagreeing about
              whether a one-way write is gated is how the gate comes out again.
            */
            candidates = rows.filter { it.remoteId != null && (offline || it.fromServer) },
            offline = offline,
            // The two other ways this list can be a PREFIX, neither of which is visible from inside
            // a dialog that covers the screen causing it. See [dwAdoptCandidateNotice].
            searched = search.isNotBlank(),
            listTruncated = partial != null,
            onDismiss = { adopting = null },
            /*
              THE ADOPT PATH WAS CHECKED FOR THE INTERRUPTED-CREATE HAZARD ON 2026-08-22 AND IS SAFE,
              which is worth writing down because the answer is not obvious.

              `adoptedIntoWorkshop` (data/DwWorkshopCreation.kt) does not clear
              `DraftSyncState.createSentAt`, so a draft adopted while a create of its own was still
              unaccounted for keeps the stamp. It cannot be acted on: the stamp is read at exactly one
              place, inside `WorkshopSync`'s `if (remoteIdOf(draft) == null)` arm, and adoption sets
              `remoteId`. The stale value is inert data, not a live create.

              WHAT ADOPTION CANNOT DO IS RECONCILE THE ORPHAN. If the interrupted create DID land and
              the designer moves the draft into a different workshop, the workshop their own tap made
              stays on the server, empty, and nothing in this app will ever join the two. That is why
              the sync pass resolves before it posts rather than leaving this dialog to sort it out,
              and why the resolver refuses to guess when two candidates match: this control is the
              place a wrong guess becomes unpickable.
            */
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
            // THE SAME FLAG THE ADOPT DIALOG IS GIVEN, and it stands the designer picker down rather
            // than styling it. Eligibility is two roster reads on the SERVER — the empanelment roster
            // and the platform allow-list — and no useful part of that question can be answered from
            // this device, so with no connection the control has nothing honest to offer. Every other
            // field on that form works in a courtyard, which is the whole reason the create mints a
            // local id; this is the one that cannot, and it says so.
            offline = offline,
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
                // TERSE, AND IT NO LONGER TELLS EVERY DESIGNER TO GO AND ASK AN ADMIN. The
                // paragraph this replaces was written when the only drafts that could reach this row
                // were the ones stranded by the create rule, whose owner genuinely had no workshop
                // to move into. A designer who starts one here deliberately, offline, usually has
                // several - the picker is the next move, not a conversation - and the admin sentence
                // is still on screen for the other case, in the adopt sheet's own empty state, which
                // is the one surface that KNOWS the account has nothing to move into. Two facts and
                // nothing else: what this row is, and what to do with it. Byte-for-byte the web's.
                Text(
                    DW_LOCAL_DRAFT_UNLINKED + " " + DW_LOCAL_DRAFT_LINK_PROMPT,
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
 *
 * ── THIS DIALOG IS A SECOND WRITER OF `POST /design-workshops`, AND THAT MATTERS ────────────────
 *
 * `WorkshopSync` spent a paragraph asserting it was the only thing in this app that creates a
 * workshop. It is not: this dialog posts too, and a READ TIMEOUT here is classified transient, so a
 * workshop the server committed becomes a local draft with no remote id and the next sync pass files
 * it again. The draft therefore carries `DraftSyncState.createSentAt`, stamped in the disk write
 * below, and the pass resolves it against the server before posting. `DwInterruptedCreateTest` pins
 * the decision.
 *
 * THE STAMP IS FOR A LOST ANSWER, NOT FOR A CREATE THAT NEVER LEFT. It is written only when the
 * handset had a validated connection at the moment of the POST, so the ordinary offline create —
 * which is the whole point of the paragraph above — carries none and the resolver is never armed for
 * it. The stamp's own comment has the fortnight that costs.
 *
 * ── AND IT NAMES WHO THE WORKSHOP IS FOR — WHICH IS BOTH WHO MAY OPEN IT AND WHOSE NAME IS ON IT ─
 *
 * TWO QUESTIONS, and the field used to be one. WHO MAY OPEN IT is SEVERAL people and it is a
 * security boundary: a design workshop is visible only to its creator, to admins, and to whoever
 * holds a `DesignWorkshopViewer` row, and a DESIGNER cannot create one — so `createdById` never
 * matches for them and the workshops a designer can see are exactly the ones they are named on.
 * A real workshop is a fortnight worked by two designers alongside a master craftsperson and a
 * reviewing officer. With one name on the create, everybody after the first had to be added
 * afterwards from "Designers on a workshop", and an admin who forgot left a designer who could not
 * open the workshop their own stage 1 already named. The picker below is a multi-select for that
 * reason, and the create writes one viewer row per name.
 *
 * WHOSE NAME IS ON IT is exactly ONE — stage 1 and stage 3 declare a single designer block, and
 * `report_meta` feeds the promoted name into the .docx's `dc:creator`, which the file format cannot
 * express as a list. So the form also resolves a LEAD and PRINTS who it is, because a multi-select
 * draws its ticks in the server's name order and "the first one you ticked" is invisible to the
 * person ticking. [dwNamedDesignerTeam] is the rule, shared with the body below.
 *
 * `seed_designer_prefill` copies a `DesignerProfile` into stage 1 and stage 3 the instant the record
 * exists, and until [DesignWorkshopCreateBody.designerUserId] could be SENT the profile it copied
 * was always the CREATOR'S. Every account that reaches this dialog is an ADMIN or the master admin
 * (`DW_WORKSHOP_CREATOR_ROLES`), so "the creator" is very often exactly the wrong person: an admin
 * opening a workshop for a designer in another cluster put their own name on a ministry document,
 * and not by mistake — `GET /designers/me/profile` upserts a profile row for any admin who so much
 * as opens the Designer Profile screen, and `prefill_from_profile`'s tail fallback then writes
 * `profile.user.name` even from a wholly empty one. The designer picker below is how this handset
 * answers instead, and `WorkshopDesignerPicker.tsx` is the browser's.
 *
 * THE PICKER'S OPTIONS ARE THE SERVER'S AND THE SEARCH IS THE SERVER'S. `GET /design-workshops/
 * eligible-viewers` is the same endpoint `WorkshopViewersScreen` uses, deliberately: the create
 * route's `assert_every_designer_may_be_named` delegates to the same `_assert_every_id_may_be_granted`
 * that endpoint is built on, so offering somebody here whom the create would refuse is impossible by
 * construction rather than by agreement. It asks the rule ONCE for the whole set and names every
 * account it objected to, so an ineligible id anywhere in the list refuses the WHOLE create with a
 * 422 — and leaves no orphan record, because the question is asked above the create. It is capped at 2000 accounts and the cap is REACHED on a
 * real repository (2543 eligible), so the box below asks the server rather than filtering what
 * arrived — a local filter would search only the part of the alphabet that fitted and answer "no
 * such person" about a colleague who is perfectly eligible and merely sorts late.
 *
 * NAMING SOMEBODY ALSO PUTS THEM ON THE WORKSHOP: the create route grants their viewer row in the
 * same call. That replaces two admin steps with one, and forgetting the second is how a designer
 * ends up locked out of the workshop whose stage 1 already carries their name. It matters twice over
 * now that the list is scoped — a designer who was not named cannot see the workshop at all, and
 * therefore cannot use "Move into a workshop" to get a fortnight captured offline into it either.
 */
@Composable
private fun CreateWorkshopDialog(
    repository: WorkshopRepository,
    /** No connection — see the call site. The designer picker, and only it, stands down. */
    offline: Boolean,
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
    /** The chosen `WORKSHOP_KIND` token, or `""` for "not stated". See the control for both halves. */
    var workshopKind by remember { mutableStateOf("") }
    /** The six kinds, off the registry. Always answerable on this client — see [workshopKindOptions]. */
    var kinds by remember { mutableStateOf<List<com.designprototype.workshop.ui.SelectOption>>(emptyList()) }
    /**
     * Names already used by workshops this account can open, narrowed by [workshopKind].
     *
     * WHY THEY ARE OFFERED AT ALL. A design workshop's name is copied into stage 1, promoted onto the
     * column, and printed on the cover of a document a ministry receives; "Bagru Block Print Workshop
     * 2025" and "Bagru block-printing workshop, 2025" are one fortnight to a reader and two different
     * strings to every group-by. A workshop that runs every year, or in three clusters at once, is
     * named three ways by three designers unless the names already on record are in front of them.
     *
     * IT IS AN OFFER AND NEVER A GATE, which is the half the browser's `stageFieldRoles.ts` objects
     * to losing: a workshop that has no record anywhere is most of them on the day they start, so the
     * box below is the default arm and the picker is the shortcut.
     */
    var namesOnRecord by remember { mutableStateOf<List<String>>(emptyList()) }
    /** The server's count against what it sent, so the cut can be stated with its number (R4). */
    var namesTotal by remember { mutableIntStateOf(0) }
    /**
     * Which half of the name combo is on screen. `false` — the plain box — is the default.
     *
     * THE OPPOSITE DEFAULT TO THE WEB'S STAGE-1 FIELD, AND THE DIFFERENCE IS THE SURFACE'S RATHER
     * THAN A PARAPHRASE. There the box is usually already filled — stage 1 is opened on a workshop
     * that exists and whose name was chosen weeks ago — so the list is the common act and the typing
     * is the escape. Here the workshop is being created: its name is nearly always new, and putting a
     * picker in front of the one act this form exists for would cost two taps on every create to
     * save one on the rare reuse. Both arms accept anything typed and both offer the names on record;
     * only which one is on top differs.
     */
    var pickingName by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    /**
     * The designers ticked in the picker, in the order they were added. Empty is "not decided yet".
     *
     * A LIST AND NOT THE SHEET'S `Set`, because with no lead chosen the FIRST of these is the
     * designer whose profile stage 1 carries — and a `Set` promises no order at all. See
     * [dwOrderedDesignerPicks], which is what keeps this one deterministic and visible.
     */
    var designerUserIds by remember { mutableStateOf<List<String>>(emptyList()) }
    /*
      SEEDED WITH THE CREATOR WHEN — AND ONLY WHEN — THE CREATOR IS THEMSELVES A DESIGNER.

      The owner's instruction of 2026-08-28: *"The designer initiating the workflow should be the
      default designer. By default, the designer list/selection should contain that designer
      themselves."*

      THE GUARD IS NOT CAUTION; IT IS THE ONE CASE THE INSTRUCTION CANNOT MEAN. `seed_designer_prefill`
      on the server argues the opposite behaviour for an admin at length — with no designer named,
      "the CREATOR's profile is copied, which for an admin opening a workshop on somebody else's
      behalf is the wrong person's name on a ministry document". An admin holds the sanction order
      and is almost never a participant, so defaulting them in would put an administrator on the
      report cover, seed their `DesignerProfile` into stage 1 and stage 3, and give them the .docx's
      `dc:creator`. "The designer initiating the workflow" excludes them by its own words, and
      `role == "DESIGNER"` is the only test that tells the two apart — `canRunDesignWorkshops` is a
      SET that CONTAINS admin and master admin, which is precisely the case being excluded.

      A SEED AND NOT A LOCK: one tick the creator can remove, with the lead still derived from the
      first ticked rather than pinned, so nothing about the previous behaviour is lost for an admin
      who is also empanelled and is opening a workshop for a colleague.

      FROM THE CACHED ACCOUNT, with no network: this dialog opens offline (it mints a local id), and
      a default that needed a request would be absent in exactly the courtyard it is for.
    */
    LaunchedEffect(Unit) {
        val me = repository.cachedUser()
        if (me?.role == "DESIGNER" && me.id.isNotBlank() && designerUserIds.isEmpty()) {
            designerUserIds = listOf(me.id)
        }
    }
    /**
     * Whose name the report carries, or "" to let it be derived (the first ticked, never the admin).
     *
     * SEPARATE FROM THE SELECTION BECAUSE THEY ARE SEPARATE QUESTIONS. Several people may OPEN the
     * workshop; exactly one name is ON it — stage 1 and stage 3 declare a single designer block and
     * `report_meta` feeds it into the .docx's `dc:creator`. Resolved through [dwNamedDesignerTeam]
     * everywhere it is read, so the sentence on the form and the body on the wire cannot disagree.
     */
    var leadDesignerId by remember { mutableStateOf("") }
    /** What the admin has typed. [dwViewerSearchTerm] turns it into what the server is asked. */
    var designerQuery by remember { mutableStateOf("") }
    /**
     * The eligible set as the server last served it, PAIRED WITH THE TERM IT ANSWERS.
     *
     * Null means there is no list at all — not loaded yet, stood down offline, or a read that
     * failed — and it is deliberately not `DwEligibleViewers()`, which would claim `complete` and let
     * [dwViewerOfferNotice] fall silent over an empty picker. When it is null [designerStandDown]
     * carries the sentence saying why.
     */
    var designerOffer by remember { mutableStateOf<DwEligibleViewers?>(null) }
    /**
     * Every eligible account this dialog has been shown since it opened, first-seen order.
     *
     * **THE ANTI-AMNESIA STORE, and it is the same one `WorkshopViewersScreen` keeps for the same
     * reason.** A search REPLACES [designerOffer], so an admin who found a colleague under one
     * surname, picked them, then typed a second surname would be left with a picker whose trigger
     * reads "Not decided yet" while [designerUserIds] still holds the first pick — a form that has
     * quietly stopped agreeing with itself on the field that decides who may open the workshop AND
     * whose name the report prints. The picks are put back into the options from here, ALL of them:
     * a multi-select can hold ticks from four different searches at once. It is also what lets the
     * lead line print a NAME rather than a bare cuid.
     */
    var seenDesigners by remember { mutableStateOf<Map<String, DwEligibleViewerDto>>(emptyMap()) }
    var designerSearching by remember { mutableStateOf(false) }
    /** Why there is no list to pick from, or null while there is one. See [dwDesignerPickerStandDown]. */
    var designerStandDown by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        templates = runCatching { repository.designWorkshopTemplates(appContext) }.getOrDefault(emptyList())
        // NETWORK-FREE IN THE ORDINARY CASE and never dependent on one: `designWorkshopSchema` peeks
        // the process cache first and otherwise ends in `StageSchemaStore.load`, which falls through
        // to the APK's bundled asset. That is why this vocabulary needs no compiled-in floor of its
        // own on the handset where the browser needs `WORKSHOP_KIND_FLOOR` — see [workshopKindOptions],
        // which states the check rather than the assumption.
        kinds = runCatching { workshopKindOptions(repository.designWorkshopSchema(appContext)) }
            .getOrDefault(emptyList())
    }

    /**
     * The names already on record, re-read whenever the chosen type changes.
     *
     * NARROWED ON THE SERVER, and narrowing what is OFFERED can never narrow what can be TYPED — the
     * box below is untouched by this. With a type chosen the offer is the workshops of that type,
     * because that is the set whose naming convention is worth copying; a Skill Upgradation sitting
     * and a Design Intervention are named to different patterns and mixing them is how a designer
     * copies the wrong precedent.
     *
     * A FAILED READ IS AN EMPTY OFFER AND NOTHING ELSE. There is no banner and nothing stands down:
     * this dialog opens offline by design — it mints a local id — and the name has always been
     * typeable. The picker simply is not offered when there is nothing to offer, which is the honest
     * shape for a convenience.
     */
    LaunchedEffect(workshopKind) {
        val answer = try {
            repository.designWorkshops(
                page = 1,
                pageSize = NAME_OFFER_PAGE_SIZE,
                workshopKind = workshopKind.takeIf { it.isNotBlank() },
            )
        } catch (cancelled: CancellationException) {
            // NEVER SWALLOWED, and this is why the arm is a `try` rather than the `runCatching` the
            // two effects above can afford: this one is KEYED, so changing the type cancels the run
            // in flight, and `runCatching` catches that like any other throwable — leaving the
            // previous type's names standing under the new type's heading.
            throw cancelled
        } catch (offline: Throwable) {
            null
        }
        namesOnRecord = answer?.items.orEmpty()
            .mapNotNull { it.title.trim().takeIf { name -> name.isNotEmpty() } }
            // DEDUPLICATED because only the NAME is offered: two workshops may share one, and a row
            // drawn twice is a control that appears to distinguish two answers it cannot.
            .distinct()
        namesTotal = answer?.total ?: 0
        // A picker with nothing in it is not a picker. Falling back to the box costs nothing here,
        // because the box is what this field was and is still the whole answer.
        if (namesOnRecord.isEmpty()) pickingName = false
    }

    /**
     * Ask the SERVER for the eligible set, once on open and again whenever the typed term changes.
     *
     * Keyed on the raw text, so each keystroke cancels the run before it, and debounced for exactly
     * the reason the workshop search above is: five letters would otherwise be five requests on a
     * metered field connection, four of them replies nobody will look at. The CLEAR is not debounced
     * — emptying the box is a deliberate act, not typing, and making an admin wait 350ms to un-narrow
     * a list they have just cleared reads as a stuck screen. That is the same split the list makes.
     */
    LaunchedEffect(offline, designerQuery) {
        if (offline) {
            // Nothing is requested at all, and the picker is disabled below. An empty picker with
            // nothing said is indistinguishable from a repository with no eligible designers, which
            // is the failure rule 10 of this repo exists to stop.
            designerOffer = null
            designerSearching = false
            designerStandDown = dwDesignerPickerStandDown(
                offline = true,
                error = null,
                // Never consulted on this branch — nothing was attempted, so there is no failure to
                // classify. Named rather than left to a trailing lambda so that is obvious.
                isConnectionFailure = { false },
            )
            return@LaunchedEffect
        }
        val term = dwViewerSearchTerm(designerQuery)
        val loaded = designerOffer
        // Already the answer on screen: clearing "abc " back to "abc" is the same question and must
        // not cost a request.
        if (loaded != null && term == loaded.search) return@LaunchedEffect
        if (term != null) delay(SEARCH_DEBOUNCE_MS)
        designerSearching = true
        val answered = runCatching { repository.eligibleDesignWorkshopViewers(term) }
        designerSearching = false
        answered
            .onSuccess { served ->
                designerOffer = served
                // MERGED, never replaced — see [seenDesigners].
                seenDesigners = seenDesigners + served.users.associateBy { it.id }
                designerStandDown = null
            }
            .onFailure { error ->
                // A CANCELLED SEARCH IS NOT A FAILED ONE. This effect is keyed on the box, so every
                // keystroke cancels the request in flight and `runCatching` catches that like
                // anything else; reported, it would flash a failure over a connection that is fine,
                // mid-word. The house pattern is to guard the HANDLING rather than rethrow, and the
                // run that replaced this one sets both fields a moment later.
                if (error is CancellationException) return@onFailure
                // CLEARED, not left standing. The previous term's list sitting under the new term is
                // a picker answering a question nobody asked, on the field that decides whose name
                // the report carries; the sentence below says what happened instead.
                designerOffer = null
                designerStandDown = dwDesignerPickerStandDown(
                    offline = false,
                    error = error,
                    isConnectionFailure = { repository.isConnectionFailure(it) },
                )
            }
    }

    /**
     * The picker's rows: the server's answer, then the still-selected account it no longer contains.
     *
     * **NEVER RE-SORTED.** The server orders by `name` then `id` — a total order, so which accounts
     * fell inside the 2000 ceiling is stable between two identical requests — and `sortedBy` on a
     * Kotlin String orders by UTF-16 code unit, which disagrees with Postgres's collation on exactly
     * the names this repository is full of. A picker whose order changes between the phone and the
     * browser is a picker two admins describe differently.
     *
     * The retained pick is appended last rather than merged in place, for the same reason
     * `dwViewerChoices` keeps its groups in order: it is not part of the answer to the term that is
     * currently typed, and pretending otherwise would move a row the admin is looking at.
     */
    val designerOptions = remember(designerOffer, seenDesigners, designerUserIds) {
        val offered = designerOffer?.users.orEmpty()
        val answered = offered.mapTo(HashSet()) { it.id }
        // EVERY TICK THE CURRENT ANSWER NO LONGER CONTAINS, not just one. The singular control only
        // ever had to rescue a single `value`; a multi-select can hold ticks from four different
        // searches at once, and rescuing only the newest would be a control that forgets three of
        // them while still sending all four.
        val retained = designerUserIds.filterNot { it in answered }.mapNotNull { seenDesigners[it] }
        (offered + retained).map { person ->
            com.designprototype.workshop.ui.SelectOption(
                value = person.id,
                // `dwPersonLabel` and not `name.ifBlank { email }`: a name that is nothing but a
                // no-break space — what a directory row pasted out of a ministry PDF leaves behind —
                // is falsy in the browser and survives Kotlin's `isBlank`, so the two clients would
                // draw the same person differently and this one would offer an invisible label.
                label = dwPersonLabel(person.name, person.email),
                // Two colleagues share a display name more often than this repository would like,
                // and the address is what tells them apart on a screen where picking the wrong row
                // puts a stranger's name on a ministry document AND grants them the workshop.
                hint = listOfNotNull(
                    person.email.takeIf { it.isNotBlank() },
                    FieldPermissions.label(person.role).takeIf { it.isNotBlank() },
                ).joinToString(" · ").takeIf { it.isNotBlank() }
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New design workshop") },
        text = {
            // SCROLLABLE, and it has to be. Material3's `AlertDialog` gives its text slot a
            // `weight(1f, fill = false)` box and nothing else: content taller than the dialog is
            // CLIPPED, not scrolled. Adding the designer picker put three more controls on this form,
            // and on a small handset with the IME up the "Start" button was still there while the
            // last field was not — a form that silently loses its bottom is worse than a long one.
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                /*
                  -- NAME OF WORKSHOP: A CREATABLE COMBO, NOT A PICKER AND NOT A BARE BOX ---------

                  The owner asked for the workshop's name to OFFER the names already on record while
                  still ACCEPTING a new one typed straight in. The browser's `stageFieldRoles.ts`
                  carries a written objection to putting a dropdown on this fact -- "a dropdown there
                  would refuse a workshop that has no `Workshop` record yet, which is most of them on
                  the day they start" -- and every word of it is right about the control it refuses: a
                  CLOSED list. It says nothing against this one, because nothing here can refuse an
                  answer. Both arms below take any name; only which one is on top differs.

                  THE ESCAPE IS `createAction`, WHICH THIS PRIMITIVE HAS HAD ALL ALONG and which the
                  browser has only just grown. Its own rule is why the list half is usable at all:
                  "A cluster whose artisan register holds three names takes the anchored menu, and
                  three names is precisely the case where the artisan being looked for is the one that
                  was never documented." Offered at the foot of whichever surface opens, and offered
                  whether or not anything matched, so it never has to be discovered twice.

                  `searchable = false` because [namesOnRecord] is ONE SERVER-TRUNCATED PAGE: a filter
                  box over a page filters the page, and typing the name of a workshop sitting past the
                  cut would answer "nothing matches" about a workshop that exists -- which on a naming
                  control is worse than usual, because the next thing a person does after "no matches"
                  is type the name again slightly differently. The sentence under it names what does
                  reach the rest, which on this control is simply typing.
                */
                if (pickingName && namesOnRecord.isNotEmpty()) {
                    SearchableSelectField(
                        label = "Workshop title *",
                        // The name already typed is always a row, so the trigger can read back the
                        // control's own answer. A picker that cannot draw its current value reads as
                        // blank, and the obvious repair for a blank box is to answer it again.
                        options = (listOfNotNull(title.trim().takeIf { it.isNotEmpty() }) + namesOnRecord)
                            .distinct()
                            .map { com.designprototype.workshop.ui.SelectOption(value = it, label = it) },
                        selectedValue = title,
                        includeNone = false,
                        searchable = false,
                        enabled = !busy,
                        createAction = com.designprototype.workshop.ui.SelectCreateAction(
                            label = "Type a name that is not on this list"
                        ) { pickingName = false },
                        onSelect = { picked -> if (picked.isNotBlank()) title = picked }
                    )
                    // TWO FACTS AND NOTHING ELSE: what the list is (R3 -- a narrowing nobody
                    // announced reads as non-existence), and the cut with its number where there is
                    // one (R4). The reasoning for both lives in the block above; the standing
                    // instruction on this product is that the screen carries the fact and the file
                    // carries the argument.
                    Text(
                        buildString {
                            append(
                                if (workshopKind.isBlank()) {
                                    "Names from workshops you can open."
                                } else {
                                    "Names from workshops of this type."
                                }
                            )
                            if (namesTotal > namesOnRecord.size) {
                                append(" Showing ${namesOnRecord.size} of $namesTotal.")
                            }
                        },
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(requiredMarked("Workshop title *")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // DRAWN ONLY WHEN THERE IS SOMETHING TO REUSE. An offer that opens on an empty
                    // list is the wordless picker this app's own primitive spends a page refusing.
                    if (namesOnRecord.isNotEmpty()) {
                        TextButton(enabled = !busy, onClick = { pickingName = true }) {
                            Text("Reuse a name already on record")
                        }
                    }
                }
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
                /*
                  -- TYPE OF WORKSHOP: IMMEDIATELY UNDER THE REPORT TEMPLATE, ON PURPOSE ----------

                  These two are the reason this requirement read as half-built for so long, on both
                  clients. This form has always drawn a six-value dropdown under the title and it is
                  REPORT TEMPLATE -- the output document's format, not the workshop's kind -- so the
                  screen looked as though it carried a type/name pair and carried neither half.
                  Putting the real type directly beside it, with its own label and its own sentence,
                  is what makes the two legible as different questions; separating them would leave
                  the template still sitting where a reader expects the type. The browser's create
                  form places them the same way for the same reason.

                  `searchable` IS NOT PASSED, and the template above does not pass it either -- but
                  the two silences mean different things, and the browser's copy of this pair says so.
                  Six members of a vocabulary compiled into this app is exactly the class the shared
                  threshold answers correctly on its own; the template's options are fetched ROWS, and
                  on this client the count still decides for them, because nothing here has yet had
                  reason to overrule it. Neither list is over eight today, so both draw the anchored
                  menu and the divergence is invisible on this form.

                  DRAWN ONLY WHEN THERE ARE TYPES. Empty means the registry has retired the enum, and
                  a picker offering tokens the server would refuse is worse than no picker at all.
                */
                if (kinds.isNotEmpty()) {
                    SearchableSelectField(
                        label = "Type of workshop",
                        options = kinds,
                        selectedValue = workshopKind,
                        includeNone = false,
                        placeholder = "Not stated",
                        enabled = !busy,
                        onSelect = { picked -> workshopKind = picked }
                    )
                    // NO HELP LINE UNDER IT, DELIBERATELY. The true thing to say — "stage 1 asks this
                    // too and its answer is the one that counts" — is a fact about where the column
                    // comes from, not something a designer filling in a create form can act on, and
                    // the standing instruction on this product is that the screen carries the answer
                    // and the file carries the reasoning. The label and the six options are the whole
                    // question. See [DesignWorkshopCreateBody.workshopKind] for the mechanism.
                }

                // ── Who the workshop is for ──────────────────────────────────────────────────────
                //
                // LAST AMONG THE QUESTIONS, deliberately: the title is what an admin opened this
                // form to type, and who the workshop is FOR is the answer they most often have to
                // leave open — which is exactly why the field is optional on the server.
                //
                // THE BOX BELOW IS THE ONE THAT REACHES EVERYBODY. The picker's own sheet has a
                // filter of its own and it narrows what came BACK; this decides what comes back, and
                // on a repository with 2543 eligible accounts under a 2000 ceiling those are not the
                // same list. Drawn only when there is a list to search — with the control stood down
                // a search box is a control that cannot do anything.
                if (designerStandDown == null) {
                    OutlinedTextField(
                        value = designerQuery,
                        onValueChange = { designerQuery = it },
                        label = { Text("Search designers by name or email") },
                        singleLine = true,
                        enabled = !busy,
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.field.muted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            when {
                                designerSearching -> CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                designerQuery.isNotEmpty() -> IconButton(onClick = { designerQuery = "" }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.field.muted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // AT MOST ONE LINE, EVER, and silence is a real answer and the common one: a complete
                // list has nothing to explain, and a standing note about paging on every visit is the
                // padding this app has twice been asked not to have. When there IS a list, the four
                // states are chosen by [dwViewerOfferNotice] — the same four sentences in the same
                // order as the viewers screen and as the web, because an admin moves between the
                // three and must not be told three different stories about one cut list.
                //
                // THE FIFTH STATE — a complete, unsearched, EMPTY eligible set — IS NOT SAID HERE.
                // `dwViewerOfferNotice` deliberately says nothing about it because the viewers screen
                // answers it in its picker's `emptyMessage`, and now so does this one: a multi-select
                // has that slot where the single-select did not, and on this handset `emptyMessage`
                // REPLACES the trigger on the form rather than hiding inside a sheet, so it is a
                // sentence on the page and not a sentence behind a control nobody would open. Said in
                // both places it would be said twice.
                val designerNotice = designerStandDown ?: designerOffer?.let { dwViewerOfferNotice(it) }
                designerNotice?.let {
                    Text(
                        it,
                        // The WORD carries it; the colour only separates "somebody is hidden from
                        // you, or there is nobody to show" from "nothing matched", and neither is an
                        // error the admin has made.
                        color = if (designerStandDown != null || designerOffer?.truncated == true) {
                            MaterialTheme.field.warning
                        } else {
                            MaterialTheme.field.muted
                        },
                        fontSize = 12.sp
                    )
                }

                /*
                  ── A MULTI-SELECT, AND THAT IS A SECURITY BOUNDARY RATHER THAN A CONVENIENCE ────

                  A design workshop is visible ONLY to its creator, to admins, and to whoever holds a
                  `DesignWorkshopViewer` row. A DESIGNER cannot create one, so `createdById` never
                  matches for them: the workshops a designer can see are exactly the ones they are
                  named on. A real workshop is a fortnight worked by two designers alongside a master
                  craftsperson and a reviewing officer, and with one name on the create everybody
                  after the first had to be added afterwards from "Designers on a workshop" — an
                  admin who forgot left a designer who could not open the workshop their own stage 1
                  already named. That gap is what this control closes; the create writes one viewer
                  row per name, in the same call.

                  THE SHEET'S OWN FILTER IS NOT THE SEARCH BOX. It narrows what came BACK; the field
                  above decides what comes back, and on a repository with 2543 eligible accounts
                  under a 2000 ceiling those are not the same list.
                */
                // DRAWN ONLY WHEN IT CAN SAY SOMETHING TRUE. With the picker stood down there is no
                // list, and a multi-select with no options replaces its trigger with `emptyMessage` —
                // which here would read "no account on this repository may be named", a claim about
                // the empanelment roster that a failed read does not support and that would sit
                // directly under a sentence saying the list could not be read. Rule 10, twice over.
                //
                // THE ONE EXCEPTION IS A SELECTION ALREADY MADE. If the connection dropped after an
                // admin ticked somebody, those ids are still what the create will carry, so the
                // control stays on screen — disabled, but showing its chips — rather than hiding the
                // answer the form is about to send.
                if (designerStandDown == null || designerUserIds.isNotEmpty()) {
                    SearchableMultiSelectField(
                        label = "Designers this workshop is for",
                        options = designerOptions,
                        selected = designerUserIds.toSet(),
                        // "NOT DECIDED YET" IS A REAL ANSWER AND IT IS THE DEFAULT ONE — a workshop is
                        // opened in a room on day one and the admin may genuinely not know yet who will
                        // run it. An EMPTY SELECTION already says it, which is why there is no row in the
                        // sheet offering it as well: two controls for one answer, one of which you would
                        // have to untick the other to reach. (The single-select needed the row because
                        // there was no other way to undo a pick without closing the form.)
                        placeholder = "Not decided yet",
                        // TWO MESSAGES, because they are two different facts and the whole defect this
                        // surface was fixed for is those two looking identical. "Nobody is eligible" is a
                        // statement about the empanelment roster; "nothing matched" is a statement about
                        // the term just typed. Same split, same words, as `WorkshopViewersScreen`.
                        emptyMessage = if (designerOffer?.search != null) {
                            "No eligible account matches that search."
                        } else {
                            "No account on this repository may be named as this workshop's designer. " +
                                "An account has to be able to run a design workshop, and be on the " +
                                "ACTIVE designer roster, before it can be named on one."
                        },
                        enabled = !busy && designerStandDown == null,
                        onSelectedChange = { picked ->
                            // ORDERED, and the lead written down rather than inferred. The sheet hands
                            // back a `Set`, which promises no order — and with no lead the FIRST of the
                            // team is whose profile stage 1 carries. See [dwOrderedDesignerPicks].
                            val next = dwOrderedDesignerPicks(
                                previous = designerUserIds,
                                picked = picked,
                                offered = designerOptions.map { it.value },
                            )
                            designerUserIds = next
                            // RESOLVED AND STORED on every change, so the state always equals what the
                            // line below prints and what the body will carry. Unticking the lead PROMOTES
                            // the first remaining designer; it never puts them back, because an admin who
                            // unticked somebody has removed them and that is the one direction an access
                            // control must not drift in.
                            leadDesignerId = dwNamedDesignerTeam(next, leadDesignerId).lead.orEmpty()
                        }
                    )
                    Text(
                        "Everybody named here can open this workshop and fill in its stages — a design " +
                            "workshop is visible only to the designers on it, and to admins. One of them " +
                            "is the one whose designer profile is copied into stage 1 and stage 3, and " +
                            "whose name the report carries. Leave it as “Not decided yet” if you do not " +
                            "know — stage 1 then carries whoever creates the workshop, and designers can " +
                            "be added later from “Designers on a workshop”.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }

                // ── The cap, refused rather than trimmed ─────────────────────────────────────────
                //
                // The SERVER refuses an over-long list outright rather than keeping the first
                // hundred, and so does the "Designers on a workshop" screen against the same table.
                // Trimming here would drop designers the admin ticked and could not see go, and it
                // would make this form disagree with the server about what was asked for. "Select all
                // N shown" is what can cross it in one tap.
                if (designerUserIds.size > DW_MAX_NAMED_DESIGNERS) {
                    Text(
                        "A workshop can be opened for at most $DW_MAX_NAMED_DESIGNERS designers, and " +
                            "this list has ${designerUserIds.size}. Take some off before starting.",
                        color = MaterialTheme.field.warning,
                        fontSize = 12.sp
                    )
                }

                // ── Whose name is on it ─────────────────────────────────────────────────────────
                //
                // PRINTED, NEVER LEFT IMPLICIT. The picker draws its ticks in the SERVER'S name
                // order, so "the first one you ticked" is invisible to the person ticking — and that
                // is exactly what the server promotes to lead when no lead is sent. Whose designer
                // profile is copied into stage 1 and whose name reaches the .docx's `dc:creator` must
                // not be decided by an order nobody can see.
                //
                // Resolved through the SAME function the body below uses, so the sentence and the
                // wire cannot disagree. The chooser appears only from two designers upward: with one
                // there is nothing to choose, and a picker holding a single row is a question with a
                // single answer.
                val namedTeam = dwNamedDesignerTeam(designerUserIds, leadDesignerId)
                namedTeam.lead?.let { lead ->
                    Text(
                        "Stage 1, stage 3 and the report will carry " +
                            (seenDesigners[lead]?.let { dwPersonLabel(it.name, it.email) } ?: lead) +
                            " — their designer profile is the one copied in. Everybody ticked can " +
                            "open the workshop.",
                        color = MaterialTheme.field.body,
                        fontSize = 12.sp
                    )
                    if (namedTeam.team.size > 1) {
                        SearchableSelectField(
                            label = "Whose name the report carries",
                            // The ticked set only. Every row is already on this screen and there are
                            // at most [DW_MAX_NAMED_DESIGNERS] of them, so unlike the picker above
                            // nothing here has been cut by a server.
                            options = namedTeam.team.map { id ->
                                val person = seenDesigners[id]
                                com.designprototype.workshop.ui.SelectOption(
                                    value = id,
                                    label = person?.let { dwPersonLabel(it.name, it.email) } ?: id,
                                    hint = person?.email?.takeIf { it.isNotBlank() },
                                )
                            },
                            selectedValue = lead,
                            includeNone = false,
                            enabled = !busy,
                            onSelect = { picked -> if (picked.isNotBlank()) leadDesignerId = picked }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // The over-cap selection is refused HERE rather than trimmed on the way out, for the
                // reason the notice above gives: the server refuses it too, and a client that quietly
                // sent something else would be disagreeing with the admin about what they asked for.
                enabled = !busy && title.isNotBlank() &&
                    designerUserIds.size <= DW_MAX_NAMED_DESIGNERS,
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
                        // RESOLVED ONCE, and by the same function that printed the sentence above.
                        // Whose name lands on a ministry document is not a place for the screen and
                        // the body to compute an answer each.
                        val designers = dwNamedDesignerTeam(designerUserIds, leadDesignerId)
                        val body = DesignWorkshopCreateBody(
                            title = title.trim(),
                            templateId = templateId,
                            // FOLDED TO NULL RATHER THAN SENT AS "", the same way the designer keys
                            // below are and for the same two reasons: a body carrying the key with
                            // nothing in it reads on the wire as an answer given, and a null property
                            // is left off the request entirely by `ApiClient.json` — which is what
                            // keeps this handset compatible with an API that predates the field,
                            // where a body merely CARRYING it is answered 422 `extra_forbidden` and a
                            // 422 is never queued. Stage 1 remains the authority for this column; see
                            // [DesignWorkshopCreateBody.workshopKind].
                            workshopKind = workshopKind.takeIf { it.isNotBlank() },
                            // FOLDED TO NULL RATHER THAN SENT AS "", because a body carrying the key
                            // with nothing in it reads on the wire as an answer given. The server
                            // would fold it itself — `(payload.designerUserId or "").strip() or
                            // None` — but the same value is about to be written to the disk, where
                            // "" and null would be two spellings of one state for every later pass
                            // to disagree about. See [dwNamedDesignerId].
                            designerUserId = designers.lead,
                            // THE WHOLE TEAM, lead first — and null, not `[]`, when nobody was named.
                            // Whether this key actually reaches the wire is decided one layer down by
                            // `WorkshopRepository.createDesignWorkshop`: it is OMITTED for a
                            // one-designer create, because `APIModel` is `extra="forbid"` and an API
                            // that predates the field would 422 a body that merely carries it — and a
                            // 422 is never queued, so that would strand the fortnight rather than
                            // refuse a request. The body written to the DISK below is this one, so
                            // the draft remembers the whole team whatever the wire chose to send.
                            designerUserIds = designers.team.takeIf { it.isNotEmpty() },
                            craftName = craft.trim().takeIf { it.isNotEmpty() },
                            clusterName = cluster.trim().takeIf { it.isNotEmpty() },
                        )
                        // READ BEFORE THE POST, NOT AFTER IT. Afterwards this answers the question
                        // "is there signal now", which for a request that failed BECAUSE the signal
                        // went mid-flight is false — and that request is exactly the one whose
                        // answer may have been lost and which therefore has to be stamped. See the
                        // stamp below for what the answer is used for.
                        val couldHaveReachedServer = ConnectivityObserver.isOnline(appContext)
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
                                    // FROM THE BODY FOR THE SAME REASON THE DESIGNER KEYS BELOW ARE,
                                    // and written whether or not the create landed for the same two
                                    // reasons: inert if it landed (the column is already set and
                                    // `WorkshopSync` reads this only inside its `remoteIdOf(draft)
                                    // == null` arm), and the whole point if it did not — a workshop
                                    // started in a courtyard remembers the type the designer picked
                                    // before the signal went, instead of being posted untyped days
                                    // later and sitting un-filterable on this list until stage 1.
                                    // Stage 1 stays the authority; see [WorkshopDraft.workshopKind].
                                    workshopKind = body.workshopKind,
                                    // PERSISTED FROM THE BODY, NOT RE-READ FROM THE PICKER, so the
                                    // draft carries exactly what was sent (or what will be sent) and
                                    // there is one folded value rather than two.
                                    //
                                    // WRITTEN WHETHER OR NOT THE CREATE LANDED, and the two cases
                                    // are not the same thing. If it landed, the field has already
                                    // been consumed by `seed_designer_prefill` and this value is
                                    // inert — `WorkshopSync` reads it only inside its
                                    // `remoteIdOf(draft) == null` arm, which a draft with a remote id
                                    // never enters. If it did NOT land, this is the whole point: a
                                    // workshop started in a courtyard remembers the designer the
                                    // admin picked before the signal went, and names them on the
                                    // create the sync pass makes days later.
                                    //
                                    // AND IT IS NOT `designerName`. This draft holds no such field
                                    // and must not grow one here: the display name is DENORMALISED
                                    // from stage 1 by the server's `promoted_values()` after the seed
                                    // runs, and a picker that wrote both would give one fact two
                                    // writers.
                                    designerUserId = body.designerUserId,
                                    // AND EVERYBODY ELSE THE WORKSHOP WAS OPENED FOR, for exactly
                                    // the same reason: a workshop is visible only to the designers
                                    // named on it, so a courtyard create that forgot the other three
                                    // would file a fortnight into a workshop three of the four people
                                    // who worked it cannot open. Empty is "the lead alone, or
                                    // nobody"; it is never read without `designerUserId` beside it.
                                    designerUserIds = body.designerUserIds.orEmpty(),
                                    remoteId = remote.getOrNull()?.id,
                                    ownerUserId = repository.cachedUser()?.id,
                                    /*
                                      THIS DIALOG IS THE SECOND WRITER OF `POST /design-workshops`,
                                      AND `WorkshopSync` SPENT A PARAGRAPH SAYING IT WAS THE ONLY ONE.

                                      A READ TIMEOUT is a transient failure, so `classifyCreate`
                                      answers [CreateOutcome.Local] and the line above writes
                                      `remoteId = null` — for a workshop the server may have committed
                                      before the reply was lost. The sync pass then finds no remote id
                                      and posts it again, and the create route de-duplicates nothing:
                                      one tap, two records in a government index, one of them empty
                                      for ever. See [DraftSyncState.createSentAt], which the pass now
                                      reads before it posts.

                                      STAMPED HERE RATHER THAN BEFORE THE POST BECAUSE THERE IS
                                      NOTHING TO STAMP BEFORE IT. No draft exists yet, and the draft's
                                      own key is the SERVER's id when the create lands — a pre-write
                                      would have to be made under a local id and then moved. This is
                                      the first instant a draft exists at all, which is also the first
                                      instant anything else could send a second create, and that is
                                      the property the stamp has to beat.

                                      ── AND IT IS NOT STAMPED ON A CREATE THAT NEVER LEFT THIS PHONE ──

                                      This first read `if (remote.isFailure)`, argued as deliberate
                                      over-recording: `isTransient` covers a request that never
                                      reached the network, and stamping one was said to cost only a
                                      list request on the next pass.

                                      THAT IS THE FIELD PATH, NOT AN EDGE OF IT. A create with no
                                      signal is an `IOException`, `classifyCreate` answers
                                      [CreateOutcome.Local] by design, and the local draft it mints is
                                      the whole offline feature — so EVERY workshop started in a
                                      courtyard would have carried a stamp from birth. The stamp is
                                      what arms `dwResumedCreateFrom`, whose single-candidate arm
                                      ADOPTS: an admin who already had a workshop of this exact title
                                      on the server would have had this draft pointed at it silently,
                                      and a fortnight of stages pushed into the wrong ministry record
                                      under a 200. Arming that on the ordinary path to catch the rare
                                      one is the wrong way round.

                                      SO THE TEST IS "COULD THIS REQUEST HAVE REACHED THE SERVER" —
                                      the connectivity the rest of the app already gates on, read at
                                      the moment of the POST rather than after it, so a network that
                                      dropped mid-flight still counts as reachable and is stamped. It
                                      still over-records within that: a validated connection that
                                      failed to connect at all is stamped, and that costs one list
                                      request which finds nothing. What it no longer does is arm the
                                      resolver for a phone that was plainly offline.
                                    */
                                    sync = draft.sync.copy(
                                        createSentAt = if (remote.isFailure && couldHaveReachedServer) {
                                            Instant.now().toString()
                                        } else {
                                            null
                                        },
                                    ),
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
 * every page the server says this account may see, so its own rows are the honest candidate set.
 *
 * WHAT IT IS NO LONGER FED FROM IS THIS DEVICE'S MEMORY. A draft on the disk keeps the id of the
 * workshop it was last pointed at, and that id outlives the access: a grant revoked from the
 * viewers screen, a designer taken off a workshop, the narrowing that came with naming several
 * designers at create. As a row on a list, showing a remembered workshop is right. As a
 * DESTINATION for a one-way move it is not — the fortnight would be filed against an id this
 * account cannot open, and every later sync would answer 404 with nothing able to undo it. So while
 * the server is answering, only the rows it returned are offered.
 *
 * ── AND WITH NO CONNECTION THE REMEMBERED ROWS ARE SHOWN AND MAY NOT BE CHOSEN ──────────────────
 *
 * That paragraph used to end "With no connection there is nothing to confirm against, the remembered
 * rows are offered instead, and [dwAdoptCandidateNotice] says exactly that." Offered as a LIST, yes.
 * Offered as a DESTINATION, no — which is what the sentence above spends its whole length forbidding,
 * and the offline branch was quietly the exception to it. `DROPDOWN_DESIGN.md` R6 calls caching an
 * ACCESS list FORBIDDEN rather than unattractive and cites this control's web twin as its authority;
 * both clients then let a failed fetch unlock the write anyway.
 *
 * WITHDRAWING IT COSTS THE DESIGNER NOTHING, which is why this is the right way round rather than
 * merely the stricter one. Adoption sends nothing. The draft stays on this phone, nothing automatic
 * may delete it (`Offline.kt:709`), and not one stage can reach the chosen workshop until there is a
 * connection — the same moment the list becomes confirmable. So [offline] disables "Move it" and
 * [dwAdoptCandidateNotice] says why, instead of filing a fortnight against a grant that may have
 * been revoked in March. `AdoptLocalDraftDialog.tsx` makes the identical split for the identical
 * reason.
 *
 * AND IT SAYS WHENEVER THE SET IS PARTIAL — offline, narrowed by the list's own search box, or cut
 * short by a bounded page walk. All three are invisible from inside a dialog that covers the screen
 * causing them, and a designer who cannot find the workshop an admin made an hour ago will conclude
 * the admin never made it. Saying so is the difference between "your workshop is not here yet" and
 * "this app has lost your workshop".
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
    /** The workshops screen's search box holds something, so [candidates] is narrowed by it. */
    searched: Boolean,
    /** The page walk stopped before it had covered what the server said this account may see. */
    listTruncated: Boolean,
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
                    /*
                      NOT "you have no workshops" — unless that is actually what this means.

                      Since a workshop is visible only to the designers NAMED on it, the common cause
                      of an empty destination list is an admin who created it and did not tick this
                      designer, and the sentence has to name the two doors out of that: being named,
                      and the join card. See [dwAdoptNoCandidatesMessage].

                      BUT IT IS A CLAIM ABOUT ACCESS, so it may only be made when the list is the
                      whole answer. Narrowed by the search box, or cut short by the page walk, an
                      empty picker says nothing whatever about what this account may open — and
                      telling a designer no workshop is open to them when one is three letters away
                      is the same absence-reads-as-non-existence failure one layer up. So a caveat,
                      where there is one, is what gets said instead of the claim.
                    */
                    Text(
                        if (offline) {
                            dwAdoptNoCandidatesMessage(offline = true)
                        } else {
                            dwAdoptCandidateNotice(
                                offline = false,
                                searched = searched,
                                listTruncated = listTruncated,
                            ) ?: dwAdoptNoCandidatesMessage(offline = false)
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
                    // PARTIAL, AND SAID SO — in one line, whichever of the three reasons applies. A
                    // designer who cannot find the workshop the admin just made would otherwise
                    // conclude the admin had not made it, and this picker is the one control on the
                    // screen whose wrong answer cannot be undone.
                    dwAdoptCandidateNotice(
                        offline = offline,
                        searched = searched,
                        listTruncated = listTruncated,
                    )?.let {
                        Text(it, color = MaterialTheme.field.warning, fontSize = 11.sp)
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
                // HELD OFFLINE. The candidates are then this phone's remembered rows, and a
                // remembered row is stale in the PERMISSIVE direction — see this dialog's header for
                // why that is fatal HERE specifically and merely untidy on a list. The reason is on
                // screen: `dwAdoptCandidateNotice`'s offline arm is the sentence for this state, and
                // a second one beside it would be two amber paragraphs saying one thing.
                enabled = chosen.isNotBlank() && !offline,
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

/**
 * WHY THE DESIGNER PICKER HAS NO LIST TO OFFER — or null when it has one and must say nothing.
 *
 * ── WHY THIS IS A FUNCTION AND NOT A `when` INSIDE THE DIALOG ───────────────────────────────────
 *
 * The same argument `dwViewerOfferNotice` makes one layer down, and the same one `classifyCreate`
 * above makes: these are DECISIONS, not layout, and a `when` inside a composable is only ever
 * exercised by somebody looking at a phone. The predicate is injected for the second half of that
 * reason — so the decision can be asserted with no HTTP stack, and so there is no second opinion in
 * this app about what "offline" means. `classifyCreate` takes `isTransient` for exactly this.
 *
 * ── THE FOUR ANSWERS, AND WHY NONE OF THEM IS AN EMPTY PICKER ───────────────────────────────────
 *
 * Rule 10 of this repo: a list that quietly stops is indistinguishable from a place with no records.
 * An empty designer picker with nothing said reads as "this repository has no eligible designers",
 * which is a statement about the empanelment roster that none of these four failures supports.
 *
 *  1. **No connection.** Eligibility is two roster reads on the SERVER — the DESIGNER empanelment
 *     roster and the platform allow-list — and no useful part of it can be answered from this
 *     device, so the control stands down. It says the workshop can still be started, because it can:
 *     that is the whole point of the local id this dialog mints, and an admin who thought otherwise
 *     would stand in a courtyard waiting for a bar of signal they may not get for two days.
 *  2. **A 404 from the id-less endpoint** — the only honest probe for "this deployment predates the
 *     feature", because a 404 with no id in the request cannot mean a missing record. See
 *     [dwViewerAdministrationMissing], which is where that reasoning lives.
 *  3. **The connection failed under us**, which reads to the admin as case 1 and is told as case 1.
 *  4. **Anything else** — a 500, a 401, a body that would not parse. Said plainly rather than dressed
 *     up as an offline message, which is the split this whole screen already carries for the create
 *     itself: a refusal is not a disconnection.
 *
 * @param error null when nothing was attempted (the offline stand-down), otherwise the failure.
 */
internal fun dwDesignerPickerStandDown(
    offline: Boolean,
    error: Throwable?,
    isConnectionFailure: (Throwable) -> Boolean,
): String? {
    val cannotReach =
        "There is no connection, so the list of designers cannot be read. Start the workshop now " +
            "and name its designers once this phone is back online — nothing is lost by leaving " +
            "it, and stage 1 carries whoever started it until then."
    if (offline) return cannotReach
    if (error == null) return null
    if (dwViewerAdministrationMissing((error as? HttpException)?.code())) {
        return "This repository does not offer the designer list yet. The workshop can still be " +
            "started; stage 1 will carry whoever started it."
    }
    if (isConnectionFailure(error)) return cannotReach
    return "The list of designers could not be read just now. The workshop can still be started; " +
        "stage 1 will carry whoever started it, and its designers can be added afterwards from " +
        "“Designers on a workshop”."
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
/**
 * The six workshop KINDS, off the served registry — the type filter's rows and the create form's.
 *
 * ── NO COMPILED-IN FLOOR ON THIS CLIENT, AND THE CLAIM WAS CHECKED RATHER THAN ASSUMED ─────────
 *
 * `DROPDOWN_DESIGN.md` §3.1 files a served enum as a class-(a) vocabulary on Android — *"always
 * answerable, may be required, says nothing, no work"* — and gives the reason: `StageSchemaStore`
 * resolves memory, then `filesDir`, then the BUNDLED APK ASSET, and a build shipped without that
 * asset throws rather than degrading to an empty registry. The web needs `WORKSHOP_KIND_FLOOR`
 * because a browser that has never reached this API holds nothing at all; a handset always holds the
 * copy that shipped with it.
 *
 * VERIFIED ON THIS TREE, 2026-08-31, rather than taken on the document's word:
 * `assets/design-workshop-schema.json` carries `enums.WORKSHOP_KIND` with all six members;
 * [SchemaResponse.enums] decodes it; `StageSchemaStore.load` falls through to `readAsset` when both
 * memory and disk miss, and `readAsset` RAISES rather than returning an empty registry; and
 * `WorkshopRepository.designWorkshopSchema` ends in `StageSchemaStore.load(context)` whether or not
 * the network answered. So a fresh install with no signal draws all six, and the claim holds. The
 * one thing that could break it is the bundled asset going stale, which is what the regenerate step
 * and `backend/tests/test_controlled_vocabularies.py` already hold.
 *
 * AN EMPTY LIST IS STILL RETURNED HONESTLY rather than substituted for, because the one state this
 * cannot rule out is a registry that has RETIRED the enum — and quietly drawing six members the
 * server no longer accepts would offer a token every save refuses. The callers draw nothing then.
 */
internal fun workshopKindOptions(schema: SchemaResponse?): List<com.designprototype.workshop.ui.SelectOption> =
    schema?.enums?.get("WORKSHOP_KIND").orEmpty().map { option ->
        com.designprototype.workshop.ui.SelectOption(value = option.value, label = option.label)
    }

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

/**
 * "The design workshop you were most recently given access to" — one row, under the create refusal.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE SERVER ANSWERS THIS AND NOT THIS SCREEN
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The list on this screen is ordered `createdAt DESC`, which answers "most recently CREATED". The
 * question the owner asked is "most recently ALLOCATED", and for a workshop the Ministry opened in
 * March and handed to a designer in August those are different rows — the wrong one being the one
 * this screen could compute. Allocation is `DesignWorkshopViewer.createdAt` and that column is on no
 * payload any client receives, by design: `has_viewer_grant` reads the EXISTENCE of the row and
 * nothing on it. So `GET /design-workshops/default-for-me` is the only thing that can answer, and
 * both clients read the same answer rather than each guessing.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * IT DRAWS NOTHING UNTIL IT HAS SOMETHING TO SAY, AND SAYS NOTHING WHEN IT FAILS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Three states collapse to "draw nothing": still asking, answered-and-none, and could-not-ask. That
 * is the OPPOSITE of this repository's usual rule, and it is right here for one reason: this control
 * sits INSIDE a panel that is already an answer to a refused action, above a list that already
 * works. A second sentence in there would bury the one that matters, and none of the three states
 * costs the designer anything — the workshops are on screen either way, one scroll down.
 *
 * The one state it must not produce is a row naming a workshop that is not there, so nothing is
 * drawn until a title has actually arrived.
 */
@Composable
private fun DwMostRecentlyAllocated(
    repository: WorkshopRepository,
    onOpen: (workshopId: String) -> Unit,
) {
    var answer by remember { mutableStateOf<DesignWorkshopDefaultDto?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repository.designWorkshopDefaultForMe() }
            .onSuccess { answer = it }
            .onFailure { error ->
                // Leaving the screen is not a failure, and rethrowing is what stops a dead
                // composable writing state — the rule every load on this client follows.
                if (error is CancellationException) throw error
            }
    }

    val row = answer ?: return
    val id = row.workshopId?.takeIf { it.isNotBlank() } ?: return
    val title = row.title?.takeIf { it.isNotBlank() } ?: return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            // WHICH DOOR, in words, because "you were added to it" and "you opened it" are different
            // facts and a designer told the wrong one goes looking for an allocation that never
            // happened. Anything this client does not recognise falls back to the neutral phrasing
            // rather than being dressed as one of the two known answers.
            when (row.reason) {
                "GRANTED" -> "Most recently allocated to you: $title"
                "CREATED" -> "Most recently opened by you: $title"
                else -> "Ready to open: $title"
            },
            color = MaterialTheme.field.onWarningContainer,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp
        )
        OutlinedButton(onClick = { onOpen(id) }) {
            Text("Open this workshop", fontSize = 12.sp)
        }
    }
}
