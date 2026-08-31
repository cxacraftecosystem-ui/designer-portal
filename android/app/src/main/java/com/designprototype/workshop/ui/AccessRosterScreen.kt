package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.AccessRosterDto
import com.designprototype.workshop.data.AccessStatus
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WHO MAY SIGN IN — the platform allow-list, and the queue of people waiting to be let in.
 *
 * ── WHAT THIS TABLE IS, AND WHAT IT IS NOT ───────────────────────────────────────────────────────
 *
 * NOT the designer roster, which sits one entry above it in the menu and reads almost the same.
 * `DesignerRoster` says who the institution recognises as a DESIGNER; this says who may reach the
 * application at all, whatever their tier. Two tables, two endpoints, two refusals with two
 * different remedies — an admin who suspends the wrong one has taken away something they did not
 * mean to, and the person is then told the wrong reason for it on the sign-in screen.
 *
 * ── THE QUEUE IS THE POINT OF THE SCREEN ─────────────────────────────────────────────────────────
 *
 * A row is added to it when somebody PROVED an identity — a correct password, or a Google token that
 * verified — and was turned away because no ACTIVE row carried their address. That is the
 * notification the requirement asks for, in the only channel either application has: this codebase
 * has no email sender and no push transport, so "tell the admins somebody is waiting" is the count
 * on the menu entry plus this queue. It is drawn FIRST, above the search box and above everything
 * else, because an admin who has to go looking for it has not been notified.
 *
 * ── REJECT IS FINAL, AND THE DIALOG SAYS SO ──────────────────────────────────────────────────────
 *
 * A refused person's next sign-in does NOT re-queue them: the server bumps their attempt count,
 * leaves the status alone, and tells them their request was not approved. Any other choice makes the
 * queue unworkable — the admin clears it, the same people retry overnight, and by morning it is full
 * of entries they already decided. An admin who believes Refuse is temporary will use it as "not
 * now" and never see that person again, so the confirmation says the opposite in words.
 *
 * ── THE SERVER PAGES, THE SERVER SEARCHES, AND NOW THE SERVER FILTERS AND ORDERS ─────────────────
 *
 * This screen has asked the server for one page at a time and sent the search term with it since it
 * was written, because of what the table IS: it holds every address ever admitted OR REFUSED,
 * including every stranger who has ever tried a password against the front door, so it grows without
 * bound and in a direction nobody controls. Walking it on a handset to find three pending rows would
 * download the history of the front door over mobile data.
 *
 * Requirement 30 puts the REST of the narrowing on the same footing. The standing chips, the tier
 * ladder, the date range and the order are all query parameters — `ui/RosterFilters.kt` builds them,
 * and there is no predicate over a fetched page anywhere in this file. That is not tidiness: a
 * client-side box over a server-truncated page answers "no matches" about records that exist, and
 * `docs/OPEN_FINDINGS.md` records four closed defects in the viewer picker of which three were that
 * exact shape — one page fetched, filtered locally, with nothing saying the answer was a prefix, so
 * eligible people were invisible and looked exactly like people who had never existed.
 *
 * [com.designprototype.workshop.ui.designworkshop.DesignerRosterScreen] used to be the counterexample
 * — it walked its whole table and filtered on the device — and stopped being one in the same change
 * as this. Both screens now share one filter grammar and one set of sentences, so an admin moving
 * between them is not learning two products.
 *
 * Every count on screen is the SERVER's `total`, and the page indicator says which page of how many
 * is being read.
 *
 * ── AND WHEN THE SERVER IS OLDER THAN THIS BUILD ─────────────────────────────────────────────────
 *
 * A handset updates over the air and may be ahead of the API. FastAPI DROPS an undeclared query
 * parameter in silence — it does not refuse it and it does not log it — so against a deployment that
 * predates §4.1 a filtered request is answered with the whole unfiltered list and a 200, while the
 * controls on screen say otherwise. That is the same lie a silently empty picker tells, in the other
 * direction, and it is detected here rather than hoped away: `roleMatchTruncated` is specified to
 * ride every answer from both roster routes, so its ABSENCE is the signal, and
 * `rosterFilterGrammarNotice` turns it into a sentence naming which controls did not reach the
 * server.
 *
 * ── NOTHING HERE DELETES ─────────────────────────────────────────────────────────────────────────
 *
 * Suspend, never Remove. The row holds the joining date, the attempt history and who admitted them —
 * and because the sign-in gate reads a MISSING row as PENDING, a real delete would put the person
 * straight back into the queue they were just removed from.
 */

/** One page of the main list. Small: this is a phone, and every row is three lines tall. */
private const val ACCESS_PAGE_SIZE = 15

/**
 * The queue's page size, deliberately smaller again.
 *
 * It sits above the main list, so a queue that paged at fifteen would push the list below two
 * screenfuls of requests on the one day an admin needs both. Paged rather than capped: a queue that
 * silently stopped at eight would be a person nobody ever decides about.
 */
private const val ACCESS_QUEUE_PAGE_SIZE = 5

/** How long after the last keystroke the search is sent. Long enough that typing costs one request. */
private const val ACCESS_SEARCH_DEBOUNCE_MS = 400L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessRosterScreen(
    repository: WorkshopRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    /**
     * Tell the host the queue length changed, so the badge on the menu entry stops claiming that
     * somebody an admin has just decided is still waiting.
     *
     * The host owns the number because the app-wide poll owns the number — see `MainActivity`. This
     * screen only ever CORRECTS it, at the two moments it knows better than a 45-second loop does:
     * when it has just read the queue, and when it has just changed it.
     */
    onPendingCount: (Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val viewer = remember(repository) { repository.cachedUser() }
    val canManage = remember(viewer) { mayManageAccessRoster(viewer) }

    /** The waiting requests — their own fetch, because `?status=PENDING` is the queue. */
    var queue by remember { mutableStateOf<List<AccessRosterDto>>(emptyList()) }
    var queueTotal by remember { mutableIntStateOf(0) }
    var queuePages by remember { mutableIntStateOf(0) }
    var queuePage by remember { mutableIntStateOf(1) }
    var queueLoaded by remember { mutableStateOf(false) }

    var rows by remember { mutableStateOf<List<AccessRosterDto>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var search by remember { mutableStateOf("") }

    /**
     * Everything the SERVER is asked to narrow and order by.
     *
     * `filters.search` is the APPLIED term — what actually went into the last request — while [search]
     * is the box. Keeping them apart is what makes "nobody matches X" safe to print: it can never name
     * a term the server has not been asked about. [emptyRosterFilters] is rule (ii) as a value, so the
     * screen opens on every standing, every tier and no date bound.
     */
    var filters by remember { mutableStateOf(emptyRosterFilters(RosterKind.ACCESS)) }
    var filterSheet by remember { mutableStateOf(false) }

    /**
     * What the last ANSWER said about itself — three facts the rows cannot carry.
     *
     * [readFailure] is the server's own words for a read that did not come back, kept so the list area
     * can say the list could not be loaded instead of drawing an empty roster, which on this screen is
     * the most alarming possible lie: "nobody may sign in" over a request that simply timed out.
     *
     * [roleMatchTruncated] and [grammarUnderstood] are both read off `roleMatchTruncated` on the
     * envelope and they are different questions — see `RosterPageDto`. `true`/`false` is the tier
     * filter's completeness; PRESENT-versus-ABSENT is whether this deployment understands §4.1's
     * grammar at all, because FastAPI drops an undeclared query parameter in silence and the screen
     * would otherwise show an unnarrowed list under controls that say otherwise.
     */
    var readFailure by remember { mutableStateOf<String?>(null) }
    var roleMatchTruncated by remember { mutableStateOf<Boolean?>(null) }
    var grammarUnderstood by remember { mutableStateOf<Boolean?>(null) }
    /** The §4.1-only keys the last request actually carried, for the sentence that names them. */
    var grammarKeysSent by remember { mutableStateOf<List<String>>(emptyList()) }

    var loading by remember { mutableStateOf(canManage) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccessRosterDto?>(null) }
    var deciding by remember { mutableStateOf<Pair<AccessRosterDto, String>?>(null) }
    var suspending by remember { mutableStateOf<AccessRosterDto?>(null) }

    // The search box, debounced. Keyed on the raw text and NOT on the applied term, so a keystroke
    // restarts the timer rather than queueing a second request behind the first.
    LaunchedEffect(search) {
        if (search == filters.search) return@LaunchedEffect
        delay(ACCESS_SEARCH_DEBOUNCE_MS)
        filters = filters.copy(search = search)
        page = 1
    }

    LaunchedEffect(reload, canManage, queuePage) {
        // Not issued at all for a non-admin. Issuing it and swallowing the 403 would put an
        // unexplained failure in the error channel every time such an account opened the screen, and
        // would ask the server to refuse something this client already knows it may not have.
        if (!canManage) return@LaunchedEffect
        try {
            val served = repository.accessRoster(
                page = queuePage,
                pageSize = ACCESS_QUEUE_PAGE_SIZE,
                status = AccessStatus.PENDING,
            )
            queue = served.items
            queueTotal = served.total
            queuePages = served.pages
            queueLoaded = true
            // DECIDING THE LAST REQUEST ON A PAGE SHORTENS THE QUEUE UNDERNEATH THE ADMIN. The
            // server answers a page past the end with an empty list and a `total` that is still
            // positive, and this section would then say "Nobody is waiting" over a queue that is
            // not empty — the exact shape of the picker defects this repository has already closed
            // four times: rows that exist and cannot be seen. Step back instead.
            if (served.items.isEmpty() && served.pages > 0 && queuePage > served.pages) {
                queuePage = served.pages
            }
            // The freshest number anyone has. The poll's copy is up to 45 seconds old and this one
            // was measured a moment ago, so the badge adopts it.
            onPendingCount(served.total)
        } catch (cancelled: CancellationException) {
            // RETHROWN, never reported. Every mutation bumps `reload`, which cancels a fetch still in
            // flight — a `runCatching` here would announce "could not load" at the exact moment an
            // approval had just succeeded, over a screen that was already reloading correctly.
            throw cancelled
        } catch (error: Throwable) {
            onError(error.apiErrorMessage("Could not load the approval queue."))
        }
    }

    LaunchedEffect(reload, canManage, page, filters) {
        if (!canManage) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        // BUILT HERE, INSIDE THE EFFECT, AND NEVER REMEMBERED. `rosterQueryParams` resolves the date
        // presets to concrete instants at the moment it is called, against this device's clock, so
        // hoisting it into a `remember` would freeze "the last 30 days" at whatever it meant when the
        // screen opened — and an admin who leaves the app running overnight would keep asking about
        // yesterday. Every filter goes into ONE request rather than being applied in passes: a second
        // pass is a second request, and two requests over one list is how a screen ends up showing the
        // intersection of two different moments.
        val query = rosterQueryParams(RosterKind.ACCESS, filters)
        try {
            val served = repository.accessRoster(
                page = page,
                pageSize = ACCESS_PAGE_SIZE,
                status = query.status,
                search = query.search,
                roles = query.roles,
                dateField = query.dateField,
                dateFrom = query.dateFrom,
                dateTo = query.dateTo,
                sort = query.sort,
                dir = query.dir,
            )
            rows = served.items
            total = served.total
            pages = served.pages
            readFailure = null
            roleMatchTruncated = served.roleMatchTruncated
            // PRESENT-VERSUS-ABSENT, NOT TRUE-VERSUS-FALSE. See the state's own note and
            // `RosterPageDto`: this key is the only observable difference between a deployment that
            // has §4.1's filter grammar and one that silently drops every parameter of it.
            grammarUnderstood = served.roleMatchTruncated != null
            grammarKeysSent = query.newGrammarKeys
            // The same step-back as the queue above, for the same reason: suspending the last row of
            // the last page must not leave the admin on an empty page below a total that says
            // otherwise.
            if (served.items.isEmpty() && served.pages > 0 && page > served.pages) page = served.pages
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val said = error.apiErrorMessage("Could not load the list of who may sign in.")
            onError(said)
            // RECORDED ON THE FAILURE PATH TOO, because a refusal is one of the two ways a server that
            // predates §4.1 answers: it DROPS a parameter it does not declare, and it 422s a value it
            // cannot parse — `?status=PENDING,SUSPENDED` against a route that still takes one status
            // is the second. `rosterFilterRefusalHint` names that below rather than leaving the admin
            // reading a refusal about four values they can see they ticked two of.
            grammarKeysSent = query.newGrammarKeys
            // KEPT, so the list area can say what happened. A toast is gone in four seconds and the
            // rows below it are not: without this the screen draws an empty allow-list under a
            // heading that says who may sign in, and an admin reading that re-adds addresses that
            // are already on it. The ROWS ARE LEFT STANDING when there are any — see the stale note
            // below — because replacing a list an admin can still read with "nobody may sign in" is
            // indistinguishable from an emptied institution.
            readFailure = said
        }
        loading = false
    }

    /**
     * Run one mutation, re-checking the permission first.
     *
     * The re-check is the point. A disabled button is a statement about a layout and a recomposition
     * can undo it; this is the rule. It reads the CACHED account rather than the captured [viewer],
     * so an account demoted since the screen opened is refused here too.
     */
    fun mutate(what: String, block: suspend () -> Unit) {
        if (!mayManageAccessRoster(repository.cachedUser())) {
            onError("Only an administrator can decide who may sign in.")
            return
        }
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    onMessage(what)
                    reload++
                }
                .onFailure { error -> onError(error.apiErrorMessage("That change did not go through.")) }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Who may sign in",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )

        if (!canManage) {
            Text(
                "This is the list of every address allowed into the application, and the queue of " +
                    "people who tried to get in — a list of named individuals either way. Reading it " +
                    "is restricted for the same reason deciding it is.",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            )
            return@Column
        }

        Text(
            "Everybody signs in through this list — except the master admin, who is never gated by " +
                "it, so there is always somebody who can let people back in. Nothing here is ever " +
                "deleted: access is granted, refused, suspended and restored, and the entry keeps " +
                "the history either way.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        // ── The queue ────────────────────────────────────────────────────────────────────────────
        // ALWAYS RENDERED, including when it is empty. A section that vanished when nobody was
        // waiting would leave an admin unable to tell "nobody has asked" from "the queue is
        // somewhere else on this screen" — and the empty state is the only place the mechanism is
        // ever explained to them.
        Text(
            if (queueTotal > 0) "Waiting for a decision · $queueTotal" else "Waiting for a decision",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
        Text(
            "Each of these people proved who they are — a correct password, or a verified Google " +
                "account — and was turned away because this list did not carry their address. They " +
                "are told, in words, that they are waiting for you.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        when {
            !queueLoaded -> RosterLoadingRow("Loading the approval queue…")
            queue.isEmpty() -> Text(
                "Nobody is waiting. When somebody who is not on this list proves their identity at " +
                    "the sign-in screen, they appear here — with their address, when they asked and " +
                    "how many times they have tried.",
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )

            else -> queue.forEach { row ->
                QueueCard(
                    row = row,
                    busy = busy,
                    onApprove = { deciding = row to AccessStatus.ACTIVE },
                    onReject = { deciding = row to AccessStatus.REJECTED },
                )
            }
        }
        if (queuePages > 1) {
            RosterPageBar(
                page = queuePage,
                pages = queuePages,
                total = queueTotal,
                noun = "waiting",
                busy = busy,
                onPage = { queuePage = it },
            )
        }

        Spacer(Modifier.height(4.dp))

        // ── The whole list ───────────────────────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                // THE COLUMNS ARE NAMED, and that is §4.8 rather than verbosity: this box reaches an
                // admin's private NOTE as well as the address and the name, and which three columns a
                // search covers is the one thing a reader cannot guess.
                //
                // The sentence is the field's SUPPORTING TEXT rather than its label — see
                // `RosterLabels.SEARCH` for why. It is visible and it is announced, which is the
                // property that matters: nothing here is spoken that is not on screen, and nothing on
                // screen goes unspoken.
                label = { Text(RosterLabels.SEARCH) },
                supportingText = { Text(RosterLabels.ACCESS_SEARCH) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { adding = true }, enabled = !busy) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }

        // EVERY STATUS BY DEFAULT, and the filter is opt-in. An admin arrives here holding a message
        // from somebody who cannot sign in, and the row that explains why is the REFUSED or
        // SUSPENDED one — exactly what a tidier default would hide. They would then re-add the
        // address, collect the 409, and still not be able to see what is refusing their colleague.
        //
        // MULTI NOW, AND STILL ON THE SCREEN RATHER THAN IN THE SHEET. This is the one filter an
        // admin toggles constantly, and burying it one tap down would cost the screen its shape. The
        // "Everyone" chip stays a chip like the others so the widest view is somewhere they can get
        // BACK to — a filter you can enter and not leave is how a screen ends up looking empty for
        // reasons nothing on it explains.
        AccessStatusChips(
            selected = filters.status,
            onSelect = { picked -> filters = filters.copy(status = picked); page = 1 },
        )

        // The rest of §4.1's grammar, one tap down, plus the order. Both chips reset the pager: a
        // filter or a sort change re-orders the whole list, so the rows at OFFSET 30 are not the rows
        // that were there a moment ago and staying on page 3 lands the admin somewhere arbitrary.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            RosterFilterButton(RosterKind.ACCESS, filters) { filterSheet = true }
            RosterSortButton(RosterKind.ACCESS, filters) { filterSheet = true }
        }

        // ── What this answer cannot say for itself ───────────────────────────────────────────────
        // Both of these change what an empty or short list MEANS, and neither is visible in the rows.
        rosterFilterGrammarNotice(grammarUnderstood, grammarKeysSent)?.let { RosterNotice(it) }
        accessRoleCutNotice(roleMatchTruncated)?.let { RosterNotice(it) }
        // A REFRESH FAILED WHILE ROWS WERE ALREADY ON SCREEN. The rows are deliberately left standing
        // — replacing a list an admin can still read with "nobody may sign in" is indistinguishable
        // from an emptied institution — so this says what they are: an older answer.
        if (readFailure != null && rows.isNotEmpty()) {
            RosterNotice(
                "These rows are the last answer that arrived. The most recent refresh failed, so a " +
                    "decision made since then — by you or by another administrator — may not be " +
                    "shown here yet."
            )
        }

        when {
            loading -> RosterLoadingRow("Loading…")

            // THE READ FAILED AND THERE IS NOTHING ON SCREEN. Tested BEFORE the empty arms, because
            // this is the state the repository is worst at everywhere: an empty allow-list drawn from
            // a network error is a claim about an institution's access control, and an admin who
            // believes it re-adds addresses that are already on the list.
            readFailure != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RosterEmptyState(
                    title = ACCESS_LIST_UNREADABLE_TITLE,
                    body = ACCESS_LIST_UNREADABLE_BODY,
                )
                // The server's own words are already in the error channel; this says what most likely
                // produced them when the request carried grammar this deployment may not have.
                rosterFilterRefusalHint(grammarUnderstood, grammarKeysSent)?.let { RosterNotice(it) }
            }

            // ROWS EXIST AND NONE OF THEM ARE HERE — the pager is past the end. It looks like
            // emptiness and is not, and it happens for the instant between deciding the last request
            // on the last page and the step-back guard re-reading.
            rows.isEmpty() && total > 0 -> RosterEmptyState(
                title = ACCESS_PAST_END_TITLE,
                body = "None of the $total entries could be listed on this page — this is not an " +
                    "empty allow-list. Step back a page.",
            )

            // NEVER "narrow your search" over a search nobody typed. That advice, printed over an
            // empty result, is the closed viewer-picker defect arriving on a new screen. The
            // predicate is the same one that decides whether "Clear every filter" is on screen, so
            // this sentence can never tell a reader to clear filters while the button is absent.
            rows.isEmpty() && hasActiveRosterFilters(RosterKind.ACCESS, filters) -> RosterEmptyState(
                title = ACCESS_NO_MATCH_TITLE,
                body = ACCESS_NO_MATCH_BODY,
            )

            rows.isEmpty() -> RosterEmptyState(
                title = ACCESS_NOBODY_YET_TITLE,
                body = ACCESS_NOBODY_YET_BODY,
            )

            else -> rows.forEach { row ->
                AccessCard(
                    row = row,
                    busy = busy,
                    onEdit = { editing = row },
                    onApprove = { deciding = row to AccessStatus.ACTIVE },
                    onReject = { deciding = row to AccessStatus.REJECTED },
                    onSuspend = { suspending = row },
                )
            }
        }
        if (pages > 1) {
            RosterPageBar(
                page = page,
                pages = pages,
                total = total,
                noun = RosterKind.ACCESS.noun,
                busy = busy,
                onPage = { page = it },
            )
        } else if (rows.isNotEmpty()) {
            Text(
                "$total ${RosterKind.ACCESS.noun} in all",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (filterSheet) {
        RosterFilterSheet(
            kind = RosterKind.ACCESS,
            filters = filters,
            onChange = { next ->
                // THE BOX FOLLOWS THE APPLIED TERM, AND THIS LINE IS THE WHOLE OF "Clear every filter"
                // MEANING WHAT IT SAYS.
                //
                // [search] is the keystroke and `filters.search` is what the last request carried;
                // they are held apart on purpose (see the state's own note) so that "nobody matches
                // X" can never name a term the server was not asked about. The sheet's clear-all sets
                // the APPLIED term to blank, and without this the two halves are left disagreeing in
                // the other direction — the BOX naming a term the server was not asked about, which
                // is the same lie read from the other end. An admin who searches for a colleague,
                // finds nothing, and presses "Clear every filter" to check would get page 1 of the
                // whole allow-list with that address still sitting in the box, and every sentence
                // around it — the empty arms, the "Clear every filter" button that has just
                // disappeared because `hasActiveRosterFilters` is now false — quietly describing a
                // different screen from the one they are looking at.
                //
                // AND IT UNDOES THE SHARPER HALF: the debounce below is keyed on [search], so a
                // clear-all pressed within 400 ms of the last keystroke left a coroutine in flight
                // that finished its delay and put the term BACK, four hundred milliseconds after the
                // button said it had gone. Writing [search] here re-keys that effect, which cancels
                // it; the guard on its first line then sees the two already agree and returns
                // without spending a request.
                search = next.search
                filters = next
                // THE SHEET CANNOT DO THIS ITSELF and says so: the page number is the screen's state
                // and is deliberately not part of `RosterFilters`, because a filter value carrying a
                // page could be restored onto the wrong page of a list it had just re-filtered.
                page = 1
            },
            onDismiss = { filterSheet = false },
        )
    }

    if (adding) {
        AccessEditDialog(
            existing = null,
            busy = busy,
            onDismiss = { adding = false },
            onSubmit = { email, fullName, role, notes ->
                adding = false
                mutate("$email may sign in.") {
                    repository.addToAccessRoster(email = email, fullName = fullName, role = role, notes = notes)
                }
            }
        )
    }

    editing?.let { row ->
        AccessEditDialog(
            existing = row,
            busy = busy,
            onDismiss = { editing = null },
            onSubmit = { _, fullName, role, notes ->
                editing = null
                mutate("The entry for ${row.email} has been updated.") {
                    repository.updateAccessEntry(id = row.id, fullName = fullName, role = role, notes = notes)
                }
            }
        )
    }

    deciding?.let { (row, decision) ->
        val approving = decision == AccessStatus.ACTIVE
        AlertDialog(
            onDismissRequest = { if (!busy) deciding = null },
            title = { Text(if (approving) "Let ${row.email} in?" else "Refuse ${row.email}?") },
            text = {
                Text(
                    if (approving) {
                        "They will be able to sign in from their next attempt, at " +
                            (row.admitRole?.let { FieldPermissions.label(it) } ?: "the default joining tier — the lowest rung") +
                            ". If they already have an account at a lower tier it is raised to match; " +
                            "an account that is already higher is never lowered by approving somebody."
                    } else {
                        // The two things an admin gets wrong about Refuse, both said out loud.
                        "They will be told their request was reviewed and not approved, and whom to " +
                            "contact. Nothing is deleted — the entry stays, with the record of when " +
                            "they asked and how many times they have tried. Trying again will NOT put " +
                            "them back in the queue: only you can reopen it, by approving them later."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        deciding = null
                        if (approving) {
                            mutate("${row.email} may sign in.") {
                                repository.approveAccessRequest(row.id, role = row.admitRole)
                            }
                        } else {
                            mutate("${row.email} was refused. The entry stays on the list.") {
                                repository.rejectAccessRequest(row.id)
                            }
                        }
                    }
                ) { Text(if (approving) "Approve" else "Refuse") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deciding = null }) { Text("Cancel") } }
        )
    }

    suspending?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!busy) suspending = null },
            title = { Text("Suspend ${row.email}?") },
            text = {
                Text(
                    "They will be refused at their next sign-in and told that their access to this " +
                        "application was ended — not that their password is wrong. The entry is kept: " +
                        "it records when they joined, and that record outlives their access. Approving " +
                        "them again here restores it, and their joining date is not moved by the round trip."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        suspending = null
                        mutate("${row.email} can no longer sign in.") { repository.suspendAccessEntry(row.id) }
                    }
                ) { Text("Suspend") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { suspending = null }) { Text("Cancel") } }
        )
    }
}

// --------------------------------------------------------------------------------------
// The rule
// --------------------------------------------------------------------------------------

/**
 * `require_access_manager` — Admin and above, for reads as well as writes.
 *
 * A FUNCTION rather than a remembered Boolean, so the screen's chrome and every mutation's own guard
 * are provably the same rule instead of two readings of it that can drift by one clause.
 */
private fun mayManageAccessRoster(viewer: UserDto?): Boolean =
    viewer != null && FieldPermissions.canManageAccessRoster(viewer)

// --------------------------------------------------------------------------------------
// Rows
// --------------------------------------------------------------------------------------

/**
 * One waiting request: who, when they asked, how hard they have been trying, and the two answers.
 *
 * WHEN AND HOW MANY TIMES, BOTH, because the pair is the whole signal an admin has to work with. One
 * attempt three weeks ago is somebody who gave up; eleven attempts today is somebody standing in a
 * courtyard unable to start work. Nothing else about them is shown because nothing else is known —
 * the server stores no name for a person who has only ever been refused, deliberately, so that there
 * is nowhere in this queue for a stranger to write a sentence pretending to come from the product.
 */
@Composable
private fun QueueCard(
    row: AccessRosterDto,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                row.email,
                display = true,
                color = MaterialTheme.field.onWarningContainer,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                listOfNotNull(
                    row.requestedAt?.let { "Asked ${it.take(10)}" } ?: "Asked at an unrecorded time",
                    "${row.attemptCount} attempt${if (row.attemptCount == 1) "" else "s"}",
                    row.lastAttemptAt?.let { "last ${it.take(10)}" },
                ).joinToString(" · "),
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onApprove, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Approve")
                }
                OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refuse")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessCard(
    row: AccessRosterDto,
    busy: Boolean,
    onEdit: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onSuspend: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    // The name is whatever an ADMIN typed; a person who has only ever been refused
                    // has none, and the absence is stated rather than left blank. Nothing here is
                    // ever populated from an unverified profile.
                    row.fullName?.takeIf { it.isNotBlank() } ?: row.email,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(row.email, color = MaterialTheme.field.muted, fontSize = 12.sp)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AccessBadge(row)
                // The requirement's "date of joining the platform". Written once and never moved by
                // a suspension and a restore, so it is safe to read as what it says.
                row.joinedAt?.let {
                    AccessChip("Joined ${it.take(10)}", MaterialTheme.field.surface200, MaterialTheme.field.body)
                }
                if (row.status == AccessStatus.ACTIVE && row.firstSeenAt == null) {
                    // The allow-list's version of an outstanding invitation: admitted, never arrived.
                    AccessChip(
                        "Has not signed in yet",
                        MaterialTheme.field.warningContainer,
                        MaterialTheme.field.onWarningContainer
                    )
                }
                if (row.attemptCount > 0) {
                    AccessChip(
                        "${row.attemptCount} refused attempt${if (row.attemptCount == 1) "" else "s"}",
                        MaterialTheme.field.surface200,
                        MaterialTheme.field.body
                    )
                }
            }

            row.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.field.body, fontSize = 12.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                if (row.status == AccessStatus.ACTIVE) {
                    // Never labelled "Remove". The verb has to say what happens, and what happens is
                    // that the entry stays.
                    OutlinedButton(onClick = onSuspend, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Suspend")
                    }
                } else {
                    OutlinedButton(onClick = onApprove, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (row.status == AccessStatus.PENDING) "Approve" else "Let back in")
                    }
                }
            }
            if (row.status == AccessStatus.PENDING) {
                OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refuse this request")
                }
            }
        }
    }
}

/**
 * What this entry means for its holder.
 *
 * COLOUR NEVER CARRIES THE MEANING ALONE — every state is worded — and the two that end in a refusal
 * carry their date, because "when did this person lose access" is the question the row is usually
 * opened to answer.
 */
@Composable
private fun AccessBadge(row: AccessRosterDto) {
    when (row.status) {
        AccessStatus.ACTIVE -> AccessChip(
            row.admitRole?.let { "May sign in as ${FieldPermissions.label(it)}" } ?: "May sign in",
            MaterialTheme.field.successContainer,
            MaterialTheme.colorScheme.onSurface
        )

        AccessStatus.PENDING -> AccessChip(
            "Waiting for a decision",
            MaterialTheme.field.warningContainer,
            MaterialTheme.field.onWarningContainer
        )

        else -> AccessChip(
            (if (row.status == AccessStatus.REJECTED) "Refused" else "Suspended") +
                (row.decidedAt?.let { " ${it.take(10)}" } ?: ""),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun AccessChip(label: String, background: Color, foreground: Color) {
    Text(
        label,
        color = foreground,
        fontSize = 11.sp,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

// --------------------------------------------------------------------------------------
// The four things an empty list can mean — §3.5, said for a list screen rather than a form field
// --------------------------------------------------------------------------------------
//
// FOUR STATES AND FOUR PAIRS OF SENTENCES, and the whole point is that they are not one sentence.
// "Nobody has been admitted yet" is a claim about an institution's access control; printed over a
// read that failed it is a lie drawn from a network error, and an admin who believes it re-adds
// addresses that are already on the list and collects a 409 they cannot explain. The two clients
// share these words: the web's are in `admin/access/rosterQuery.ts`, byte for byte.

/**
 * THE READ FAILED AND NOTHING IS ON SCREEN. §3.5's *could-not-be-listed*, for a list.
 *
 * Both halves of the body are load-bearing: **this is not showing what exists**, and **nothing has
 * been changed**. The picker version of this sentence reassures a researcher that their record can
 * still be saved; nothing is being saved here, so this one reassures an admin about the thing they
 * would actually fear — that the allow-list itself has been emptied.
 */
private const val ACCESS_LIST_UNREADABLE_TITLE = "The list could not be loaded"

private const val ACCESS_LIST_UNREADABLE_BODY =
    "This is not showing what exists, and it is not a claim that nobody may sign in — the request " +
        "for the allow-list did not come back. Nobody's access has been changed by the failure."

/**
 * THE FILTERS EXCLUDED EVERYONE. Not the same fact as an empty list, and the difference is the whole
 * of §3.5.
 *
 * The body names what clearing gets back IN FULL, refused and suspended rows included, because this
 * screen's widest default is the reason it is usable at all.
 */
private const val ACCESS_NO_MATCH_TITLE = "Nobody matches these filters"

private const val ACCESS_NO_MATCH_BODY =
    "Clear them to see everyone this application has ever admitted, refused or suspended — the " +
        "refused and suspended entries included, which are the ones that explain why somebody " +
        "cannot sign in. The filters are applied on the server, over the whole list and not only " +
        "the rows this page had loaded."

/**
 * THE LIST IS GENUINELY EMPTY, ANSWERED AND NONE. §3.5's *genuinely-empty, unscoped*.
 *
 * The one sentence on this screen that may make a claim about the repository, because it is the only
 * one reached from a request that SUCCEEDED, with no filter narrowing it and no term in the box.
 */
private const val ACCESS_NOBODY_YET_TITLE = "Nobody is on the list yet"

private const val ACCESS_NOBODY_YET_BODY =
    "Add the first address above — the master admin can always sign in regardless of this list, " +
        "which is what makes it safe to start empty."

/** THE PAGER IS PAST THE END. Rows exist, none of them are here; it looks like emptiness and is not. */
private const val ACCESS_PAST_END_TITLE = "Nothing on this page"

// --------------------------------------------------------------------------------------
// Add / edit
// --------------------------------------------------------------------------------------

/**
 * One dialog for both actions, because they write the same three admin-typed columns.
 *
 * THE EMAIL IS READ-ONLY WHILE EDITING, and that is a rule rather than a nicety: the address IS the
 * gate, so an admin who changed it while meaning to fix a name would hand one person's admission to
 * a different mailbox — and the person who lost it would simply stop being able to sign in, with the
 * entry on screen still saying they may. The server's PATCH does not accept an email either; the two
 * agree on purpose.
 *
 * ADDING SOMEBODY HERE IS APPROVING THEM. The row is created ACTIVE, not pending: there is nobody
 * else for the request to be routed to, and a form that produced a request the same admin then had
 * to approve would be a form that does nothing.
 */
@Composable
private fun AccessEditDialog(
    existing: AccessRosterDto?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (email: String, fullName: String, role: String?, notes: String) -> Unit,
) {
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    var role by remember(existing) { mutableStateOf(existing?.admitRole) }
    var rolesOpen by remember { mutableStateOf(false) }

    val cleaned = email.trim().lowercase()
    // Deliberately the shallowest possible check — one '@' with something either side. The server
    // validates properly with an EmailStr; a stricter regex here would reject real addresses (plus
    // addressing, long TLDs, non-ASCII local parts) and the admin would have no way to override it.
    val emailLooksUsable = existing != null || (
        cleaned.count { it == '@' } == 1 &&
            cleaned.substringBefore('@').isNotBlank() &&
            cleaned.substringAfter('@').contains('.')
        )

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "Let somebody in" else "Correct this entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (existing == null) {
                        "No account has to exist yet: the address is admitted now and the account is " +
                            "created the first time that person signs in. Adding somebody here IS " +
                            "approving them, so they never appear in the queue."
                    } else {
                        "The name, tier and note are your own record of whom you admitted and why. " +
                            "Changing them cannot change whether this person may sign in — use " +
                            "Approve, Refuse or Suspend for that."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { if (existing == null) email = it },
                    readOnly = existing != null,
                    label = {
                        Text(requiredMarked(if (existing == null) "Email *" else "Email (cannot be changed here)"))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Full name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // THE TIER THEY JOIN AT. The first option is the platform's documented default — the
                // lowest rung — because that is this platform's rule for a new joiner: everybody
                // starts at the bottom unless somebody deliberately promotes them.
                Column {
                    OutlinedButton(onClick = { rolesOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(role?.let { "Joins as ${FieldPermissions.label(it)}" } ?: "Joins at the default tier")
                    }
                    DropdownMenu(expanded = rolesOpen, onDismissRequest = { rolesOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Default joining tier (lowest rung)") },
                            onClick = { role = null; rolesOpen = false }
                        )
                        // Offered up to the tier the server would accept from THIS admin anyway; the
                        // server enforces the same ceiling (`assert_role`), so this is a mirror
                        // rather than the rule.
                        ACCESS_GRANTABLE_ROLES.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(FieldPermissions.label(option)) },
                                onClick = { role = option; rolesOpen = false }
                            )
                        }
                    }
                }

                // `AccessRoster.notes` — an administrator's note about why this person is on the
                // list. Dictation only: it is read in a list beside the row and nowhere else, so
                // there is nothing for formatting to survive into, and a toolbar inside an
                // AlertDialog would cost the dialog most of its height. Same choice, for the same
                // reason, as the designer roster's notes box.
                RecordProseField(
                    label = "Note",
                    value = notes,
                    onValueChange = { notes = it },
                    minLines = 3,
                    dictate = true,
                    resetKey = existing?.id,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && emailLooksUsable,
                onClick = { onSubmit(cleaned, fullName, role, notes) }
            ) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The tiers this screen offers, highest last.
 *
 * ADMIN AND MASTER_ADMIN ARE ABSENT ON PURPOSE. The server allows an admin to grant up to their own
 * tier, so an admin COULD mint another admin here — but the place to do that is Manage users, where
 * the consequences of the ladder are on screen. This dialog exists to let a field researcher in, and
 * an accidental tap on a one-line dropdown is a poor way to create somebody who can lock the
 * institution out. Anybody who genuinely needs to be an admin is promoted deliberately, afterwards.
 */
private val ACCESS_GRANTABLE_ROLES = listOf(
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "DESIGNER",
    // OFFERED, and it was a decision rather than a default. An inspector is someone brought in to
    // look at a designer's work, which is exactly the kind of person an admin admits from this
    // dialog rather than promoting afterwards from Manage users. It is also the safe side of the
    // line this list draws: the tiers held back are the two that can lock the institution out, and
    // an inspector can do neither — no account creation, no roster edit, no workshop authority.
    "INSPECTOR",
    "PROFESSOR",
)
