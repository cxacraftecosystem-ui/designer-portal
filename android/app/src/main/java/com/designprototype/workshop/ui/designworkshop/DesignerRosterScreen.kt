package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
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
import com.designprototype.workshop.data.DesignerDirectoryEntryDto
import com.designprototype.workshop.data.DesignerRosterDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.ui.DesignerStandingChips
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.InstitutionVocabulary
import com.designprototype.workshop.ui.RosterEmptyState
import com.designprototype.workshop.ui.RosterFilterButton
import com.designprototype.workshop.ui.RosterFilterSheet
import com.designprototype.workshop.ui.RosterHint
import com.designprototype.workshop.ui.RosterKind
import com.designprototype.workshop.ui.RosterLabels
import com.designprototype.workshop.ui.RosterLoadingRow
import com.designprototype.workshop.ui.RosterNotice
import com.designprototype.workshop.ui.RosterPageBar
import com.designprototype.workshop.ui.RosterSortButton
import com.designprototype.workshop.ui.emptyRosterFilters
import com.designprototype.workshop.ui.hasActiveRosterFilters
import com.designprototype.workshop.ui.roleMatchCutNotice
import com.designprototype.workshop.ui.rosterFilterGrammarNotice
import com.designprototype.workshop.ui.rosterFilterRefusalHint
import com.designprototype.workshop.ui.rosterQueryParams
// The shared record-form prose box: on-device dictation and the rich editor, both opt-in.
import com.designprototype.workshop.ui.RecordProseField
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt. Every file
// in this feature imports it, or its headings are quietly set in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The institution's roster of recognised designers — the list that decides who may sign in at all.
 *
 * WHAT THIS TABLE IS. A row here is an admin's statement that a named individual is empanelled. It is
 * keyed by EMAIL and not by user id, and it usually exists BEFORE the account does: an admin adds
 * somebody, and the account provisions itself the first time that person signs in with Google. That
 * is why every row shows whether the invitation has been taken up — [DesignerRosterDto.firstSeenAt]
 * is the only place in the system that answers "did the person I added two weeks ago ever turn up",
 * and without it an admin re-adds them, gets a 409 on a unique email, and has no idea why.
 *
 * ── NOTHING HERE DELETES ─────────────────────────────────────────────────────────────────────────
 *
 * There is a Suspend and there is a Restore and there is no Remove, on purpose and not as an
 * omission. Reports already delivered to a ministry carry these people's names and empanelment
 * numbers; a roster that cannot account for a name on a delivered document makes that document
 * unverifiable. Suspension revokes the sign-in, stamps `revokedAt`, keeps the history, and is
 * reversible. Deleting the row would revoke the sign-in and destroy the evidence in the same tap,
 * and there is no version of "we need to withdraw someone's access" that also requires that.
 *
 * ── ADMIN ONLY, ENFORCED ─────────────────────────────────────────────────────────────────────────
 *
 * `GET /designers/roster` is `can_manage_designer_roster` (Admin and above) for READS as much as
 * writes — the roster is a list of named individuals and their institutional standing, not something
 * a peer should be able to browse. So this screen does not merely hide its buttons from a non-admin:
 * it does not issue the request at all, and every mutation re-derives the permission from the cached
 * account at the moment of the tap rather than trusting the composition that drew the button.
 *
 * ── THE SERVER FILTERS, ORDERS AND PAGES. THE DEVICE DOES NONE OF IT ─────────────────────────────
 *
 * THIS SCREEN USED TO WALK THE WHOLE TABLE — five requests of a hundred rows — and then sort and
 * filter the result in Kotlin. The walk, the sort, the local filter and the notice describing the
 * walk's truncation were all deleted together, because leaving any one of them behind would have left
 * a screen saying something about itself that had stopped being true. Three reasons, each of them a
 * rule in DROPDOWN_DESIGN §4.6:
 *
 *  - **The box answered "no match" about designers who exist.** 100 × 5 is a 500-row ceiling against
 *    a roster of about 1,300 (`design_workshop_viewers.py:106`), so the device-side search was
 *    filtering a PREFIX and reporting its result as an answer about the roster. That is this
 *    repository's most repeated bug class, on the screen where the wrong conclusion — "this person
 *    was never empanelled" — is about somebody's access.
 *  - **It lost the wrong end.** The walk read `createdAt desc` from page one, so a short read kept the
 *    NEWEST empanelments and dropped the OLDEST — and the oldest is the row this screen is opened
 *    for: the designer empanelled two seasons ago who cannot sign in today.
 *  - **The device-side sort could not be right across pages.** "Outstanding invitations first, then
 *    by name" was a reordering of whichever rows had arrived. It survives as `sort=firstSeen&dir=desc`
 *    — Postgres puts NULLs first on `desc`, so outstanding invitations float to the top of the WHOLE
 *    roster, page by page, which the old sort never managed.
 *
 * Everything is a query parameter now, built by `ui/RosterFilters.kt` and shared byte for byte with
 * `AccessRosterScreen` and with the web's two admin pages. There is no predicate over a fetched page
 * anywhere in this file and there must never be one again.
 *
 * ── AND WHEN THE SERVER IS OLDER THAN THIS BUILD ─────────────────────────────────────────────────
 *
 * FastAPI drops an undeclared query parameter in SILENCE, so a deployment predating §4.1 answers a
 * filtered request with the whole unfiltered roster and a 200 while the controls say otherwise —
 * the same lie a silently empty picker tells, in the other direction. `roleMatchTruncated` rides
 * every §4.1 answer, so its ABSENCE is the signal, and `rosterFilterGrammarNotice` names on screen
 * which controls did not reach the server. This screen needs the network and says so rather than
 * rendering an empty list: an empty roster reads as "nobody is empanelled", which here is the most
 * alarming possible untruth.
 *
 * ── TWO REQUESTS, AND WHY THE SECOND ONE IS NOT OPTIONAL ─────────────────────────────────────────
 *
 * A roster row is keyed by EMAIL and carries no account id, while the profile an admin opens from it
 * is addressed as `/designers/{userId}/profile`. `GET /designers/directory` is the only join between
 * the two, and it is fetched here for that single purpose — exactly as the web's roster page fetches
 * it for the same purpose. Without it "Open designer profile" renders for nobody, which is precisely
 * how the admin editor behind it shipped unreachable.
 *
 * The directory read is BEST EFFORT and its failure is silent: it costs the profile action for this
 * session and nothing else, and emptying a roster an admin can still read and still suspend from,
 * because a secondary lookup failed, would be the worse trade. Its 500-account ceiling is stated on
 * screen when it is reached, because a missing action on a row whose account exists is the kind of
 * silence that reads as "this designer never signed up".
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignerRosterScreen(
    repository: WorkshopRepository,
    /**
     * Open one designer's own profile, as an administrator, for the twenty values their reports
     * print. Offered only for rows whose email [accountsByEmail] resolved to an account — a roster
     * row is keyed by EMAIL and may name somebody who has never signed in, and there is no profile
     * to open for an account that does not exist yet.
     */
    onOpenProfile: (userId: String, label: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewer = remember(repository) { repository.cachedUser() }
    val canManage = remember(viewer) { mayManageDesignerRoster(viewer) }

    var rows by remember { mutableStateOf<List<DesignerRosterDto>>(emptyList()) }
    /** THE SERVER'S numbers, always. What this page holds is never presented as what the roster holds. */
    var rosterTotal by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    /** Lower-cased email -> the id of the account that signed up under it, for "Open designer profile". */
    var accounts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    /** Account id -> name, so "Empanelled by" can name the admin `addedById` points at. */
    var directoryNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var directoryCapped by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(canManage) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    /** The BOX. [RosterFilters.search] is what was actually sent — see the debounce below. */
    var search by remember { mutableStateOf("") }
    /** Everything the server is asked to narrow and order by. Its default narrows nothing. */
    var filters by remember { mutableStateOf(emptyRosterFilters(RosterKind.DESIGNER)) }
    var filterSheet by remember { mutableStateOf(false) }
    /** The institution vocabulary behind the filter, and which of §3.5's states it is in. */
    var institutions by remember {
        mutableStateOf<InstitutionVocabulary>(InstitutionVocabulary.Loading)
    }
    /** The server's own words for a read that did not come back. Null while the last read answered. */
    var readFailure by remember { mutableStateOf<String?>(null) }
    var roleMatchTruncated by remember { mutableStateOf<Boolean?>(null) }
    /** Present-versus-absent on `roleMatchTruncated`: does this deployment know §4.1's grammar at all? */
    var grammarUnderstood by remember { mutableStateOf<Boolean?>(null) }
    var grammarKeysSent by remember { mutableStateOf<List<String>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DesignerRosterDto?>(null) }
    var confirming by remember { mutableStateOf<DesignerRosterDto?>(null) }

    // THE SEARCH BOX IS DEBOUNCED NOW, AND IT HAS TO BE. It used to filter a list already on the
    // device, so a keystroke cost nothing and there was nothing to wait for; it now reaches the
    // server, and an undebounced box would spend one request per letter on the connection this
    // screen is most often opened over. The same 400 ms the allow-list has always used, so the two
    // screens do not feel like two products.
    LaunchedEffect(search) {
        if (search == filters.search) return@LaunchedEffect
        delay(DESIGNER_SEARCH_DEBOUNCE_MS)
        filters = filters.copy(search = search)
        page = 1
    }

    LaunchedEffect(reload, canManage, page, filters) {
        // The request is not made at all for a non-admin. Making it and swallowing the 403 would put
        // an unexplained failure in the error channel every time such an account opened the screen,
        // and would ask the server to refuse something this client already knows it may not have.
        if (!canManage) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        // BUILT INSIDE THE EFFECT AND NEVER REMEMBERED: `rosterQueryParams` resolves the date presets
        // against this device's clock at the moment it is called, so a screen left open overnight
        // does not keep asking about yesterday.
        val query = rosterQueryParams(RosterKind.DESIGNER, filters)
        try {
            val served = repository.designerRoster(
                page = page,
                pageSize = DESIGNER_PAGE_SIZE,
                search = query.search,
                standing = query.standing,
                roles = query.roles,
                institutions = query.institutions,
                dateField = query.dateField,
                dateFrom = query.dateFrom,
                dateTo = query.dateTo,
                sort = query.sort,
                dir = query.dir,
            )
            // NO `sortedWith` AND NO `filter`. The server ordered and narrowed this page; re-ordering
            // it here would be a second order over a fragment of the first, which is exactly how the
            // deleted walk managed to be wrong on every page but the first.
            rows = served.items
            rosterTotal = served.total
            pages = served.pages
            readFailure = null
            roleMatchTruncated = served.roleMatchTruncated
            grammarUnderstood = served.roleMatchTruncated != null
            grammarKeysSent = query.newGrammarKeys
            // Suspending the last row of the last page must not leave the admin on an empty page
            // below a total that says otherwise — the allow-list's step-back, for the same reason.
            if (served.items.isEmpty() && served.pages > 0 && page > served.pages) page = served.pages
        } catch (cancelled: CancellationException) {
            // RETHROWN, never reported. Every roster mutation bumps `reload`, which cancels a read
            // still in flight — so a `runCatching` here announced "Could not load the designer
            // roster." at the exact moment a suspension had just succeeded, over a screen that was
            // already reloading correctly.
            throw cancelled
        } catch (error: Throwable) {
            val said = error.apiErrorMessage("Could not load the designer roster.")
            onError(said)
            // Kept on the failure path too: a server predating §4.1 answers either by DROPPING a
            // parameter it does not declare or by refusing a value it cannot parse, and the second is
            // a 422 an admin would otherwise read as the product being broken.
            grammarKeysSent = query.newGrammarKeys
            // KEPT, because a toast is gone in four seconds and an empty list is not. An empty roster
            // drawn from a failed read reads as "nobody is empanelled", which on THIS screen is the
            // most alarming possible lie — it is the list that decides who may sign in.
            readFailure = said
        }
        loading = false
    }

    // The institution vocabulary. Its own read, fetched once, and NOT keyed on `reload`: a roster
    // mutation can add an institution, but re-reading a two-hundred-row vocabulary after every
    // Suspend would spend a request to catch a case the admin can fix by reopening the screen.
    //
    // ITS FAILURE IS NEVER SILENT AND NEVER FATAL. The endpoint ships in the same wave as this
    // screen, so a handset ahead of the API meets a 404 — an ANSWERED refusal, not a transient one,
    // so §3.5's could-not-be-listed sentence is the honest one and the picker says it. The roster
    // itself stays readable, filterable and suspendable throughout.
    LaunchedEffect(canManage) {
        if (!canManage) return@LaunchedEffect
        institutions = try {
            val served = repository.designerRosterInstitutions()
            InstitutionVocabulary.Listed(names = served.items, truncated = served.truncated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            InstitutionVocabulary.Failed(online = !repository.isTransient(error))
        }
    }

    // The email -> account join. Deliberately NOT keyed on `reload`: a roster mutation cannot create
    // an account (the account provisions itself at the person's first sign-in), so re-fetching the
    // directory after every Add or Suspend would spend a request on a list that cannot have changed.
    LaunchedEffect(canManage) {
        if (!canManage) return@LaunchedEffect
        val served = try {
            repository.designerDirectory()
        } catch (cancelled: CancellationException) {
            // RETHROWN, for the same reason the walk above rethrows it. `runCatching` here swallowed
            // the cancellation Compose raises when this screen leaves composition and turned it into
            // "the directory is unavailable" — harmless only for as long as nothing runs after this
            // point. Rethrowing is what keeps it harmless when something does.
            throw cancelled
        } catch (offline: Throwable) {
            // Best effort, and its failure is silent by design — see the class KDoc. Silent rather
            // than `onError(...)`: an admin told "could not load the directory" over a roster that
            // loaded perfectly well would reasonably conclude the screen is broken.
            return@LaunchedEffect
        }
        accounts = accountsByEmail(served)
        directoryNames = served.associate { it.id to (it.name?.takeIf(String::isNotBlank) ?: it.email) }
        directoryCapped = served.size >= DESIGNER_DIRECTORY_CAP
    }

    /**
     * Run one roster mutation, re-checking the permission first.
     *
     * The re-check is the point. A disabled button is a statement about a layout and a recomposition
     * can undo it; this is the rule. It reads the CACHED account rather than the captured `viewer`,
     * so an account demoted since the screen opened is refused here too.
     */
    fun mutate(what: String, block: suspend () -> Unit) {
        if (!mayManageDesignerRoster(repository.cachedUser())) {
            onError("Only an administrator can change the designer roster.")
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
            "Designer roster",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )

        if (!canManage) {
            Text(
                "The designer roster is an administrator's list of named individuals and their " +
                    "institutional standing, so it is not open to other accounts — reading it is " +
                    "restricted for the same reason changing it is.",
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
            "A designer signs in only while an ACTIVE row here carries their email. Add somebody " +
                "before they have an account and the account creates itself the first time they " +
                "sign in with Google.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        // THE SERVER'S TOTAL, AND NOTHING DERIVED FROM THE PAGE.
        //
        // What stood here counted outstanding invitations and suspensions out of `rows` and printed
        // them beside a roster count. Over a walk that had gathered everything those tallies were
        // true; over ONE PAGE they are counts of fifteen cards presented as facts about the
        // institution, which is the same defect as a capped list drawn as a whole one. Both questions
        // they answered are now askable properly and the line says where: standing is a chip, and
        // "who have I added who has not turned up" is the `First signed in` order, whose nullable
        // column floats every outstanding invitation to the top across every page — which a
        // device-side sort over whichever rows had arrived never did.
        Text(
            "$rosterTotal on the roster",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
        RosterHint(
            "To see who has not turned up, order by \"First signed in\" — outstanding invitations " +
                "come first. To see who cannot sign in, use the standing chips."
        )

        // Web parity, and the same sentence: the account list stops at a cap, so the profile action
        // can be missing from a row whose account exists. Stated rather than left as a button that
        // is simply not there on some rows and there on others.
        if (directoryCapped) {
            RosterNotice(
                "The account list this screen reads to match a row to a person stops at " +
                    "$DESIGNER_DIRECTORY_CAP accounts, so \"Open designer profile\" may be missing " +
                    "from a row whose account does exist. The roster itself is unaffected."
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                // NAMES THE THREE COLUMNS IT REACHES, which is §4.8 and not verbosity: this box now
                // goes to the server and is OR-ed over email, full name and institution there. The
                // old label said "name or email" over a device-side match that also read the
                // institution — a control describing itself wrongly in the one place a reader is
                // deciding whether to trust it. Supporting text rather than the label, for the reason
                // on `RosterLabels.SEARCH`: visible AND announced, at every font scale.
                label = { Text(RosterLabels.SEARCH) },
                supportingText = { Text(RosterLabels.DESIGNER_SEARCH) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { adding = true }, enabled = !busy) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }

        // SUSPENDED ROWS ARE LISTED BY DEFAULT — the first chip is the widest view and is chosen on
        // open. A suspended designer hidden by default is a person an admin cannot find in order to
        // restore them, and the only way back would be re-adding an email the unique index already
        // holds: a 409 that reads as "this person is already on the roster" while the roster on
        // screen visibly does not contain them.
        DesignerStandingChips(
            selected = filters.standing,
            onSelect = { picked -> filters = filters.copy(standing = picked); page = 1 },
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            RosterFilterButton(RosterKind.DESIGNER, filters) { filterSheet = true }
            RosterSortButton(RosterKind.DESIGNER, filters) { filterSheet = true }
        }

        // ── The three things this answer cannot say for itself ───────────────────────────────────
        rosterFilterGrammarNotice(grammarUnderstood, grammarKeysSent)?.let { RosterNotice(it) }
        // THE ROLE FILTER ITSELF MAY BE INCOMPLETE, and that is worse than a short page: a designer
        // whose account fell past the read limit is missing from EVERY page of this filter, as though
        // they had never been empanelled.
        roleMatchCutNotice(roleMatchTruncated)?.let { RosterNotice(it) }
        if (readFailure != null && rows.isNotEmpty()) {
            RosterNotice(
                "These rows are the last answer that arrived. The most recent refresh failed, so an " +
                    "empanelment or a suspension made since then may not be shown here yet."
            )
        }

        when {
            loading -> RosterLoadingRow("Loading the roster…")

            // TESTED BEFORE EVERY EMPTY ARM. An empty roster drawn from a failed read says "nobody is
            // empanelled", and on this screen that is a claim about the institution's access control
            // made from a network error — the admin then re-adds addresses the unique index holds.
            readFailure != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RosterEmptyState(
                    title = "The roster could not be loaded",
                    body = "This is not showing who is empanelled, and it is not a claim that " +
                        "nobody is — the request did not come back. Nothing on the server has changed.",
                )
                rosterFilterRefusalHint(grammarUnderstood, grammarKeysSent)?.let { RosterNotice(it) }
            }

            rows.isEmpty() && rosterTotal > 0 -> RosterEmptyState(
                title = "Nothing on this page",
                body = "None of the $rosterTotal empanelments could be listed on this page — this " +
                    "is not an empty roster. Step back a page.",
            )

            rows.isEmpty() && hasActiveRosterFilters(RosterKind.DESIGNER, filters) -> RosterEmptyState(
                title = "Nobody matches these filters",
                body = "The filters are applied on the server, over the whole roster and not only " +
                    "the rows this page had loaded, so this is an answer about every empanelment " +
                    "there has ever been. Clear every filter to see everybody again, suspended " +
                    "entries included.",
            )

            rows.isEmpty() -> RosterEmptyState(
                title = "Nobody is on the designer roster yet",
                body = "Until somebody is, no account can hold the Designer role and sign in with " +
                    "it. Add the first address above — the account creates itself when that person " +
                    "signs in.",
            )

            else -> rows.forEach { row ->
                RosterCard(
                    row = row,
                    busy = busy,
                    // Who empanelled them: the server's own join when a deployment sends one, and
                    // otherwise the directory this screen already fetched. `addedById` is an ADMIN's
                    // id, and an admin outranks a designer, so they are in the directory too.
                    addedByLabel = row.addedByName?.takeIf { it.isNotBlank() }
                        ?: row.addedById?.let { directoryNames[it] },
                    onEdit = { editing = row },
                    onToggleAccess = { confirming = row },
                    // Resolved by email against the directory, never fabricated from the email
                    // itself: `/designers/{userId}/profile` takes an account id, and inventing one
                    // would send the admin to a 404 on a control they were invited to tap.
                    onOpenProfile = accounts[row.email.lowercase()]?.let { id ->
                        { onOpenProfile(id, row.fullName?.takeIf { n -> n.isNotBlank() } ?: row.email) }
                    }
                )
            }
        }
        if (pages > 1) {
            RosterPageBar(
                page = page,
                pages = pages,
                total = rosterTotal,
                noun = RosterKind.DESIGNER.noun,
                busy = busy,
                onPage = { page = it },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (filterSheet) {
        RosterFilterSheet(
            kind = RosterKind.DESIGNER,
            filters = filters,
            institutions = institutions,
            onChange = { next ->
                // THE BOX FOLLOWS THE APPLIED TERM. The allow-list carries this line for the same
                // reason and in the same place; see `AccessRosterScreen` for the argument in full.
                //
                // Short version, because it bites hardest on THIS screen: [search] is the keystroke,
                // `filters.search` is what the last request carried, and the sheet's clear-all blanks
                // only the second. Left alone, an admin who searches for a designer, finds nothing,
                // and presses "Clear every filter" to check whether a filter was hiding them gets the
                // whole roster back with that name still in the box — and on the one screen whose
                // empty answer is read as "this person was never empanelled", a box and a list that
                // disagree is the disagreement that matters. The debounce below is keyed on [search],
                // so this also cancels an in-flight one that would otherwise have put the term back
                // four hundred milliseconds after the button said it was gone; its guard then sees the
                // two agree and returns without spending a request.
                search = next.search
                filters = next
                // A filter or a sort change re-orders the whole list, so the rows at OFFSET 30 are
                // not the rows that were there a moment ago. The sheet cannot do this itself — the
                // page number is the screen's state and is deliberately not part of `RosterFilters`.
                page = 1
            },
            onDismiss = { filterSheet = false },
        )
    }

    if (adding) {
        RosterEditDialog(
            existing = null,
            busy = busy,
            onDismiss = { adding = false },
            onSubmit = { email, fullName, institution, notes ->
                adding = false
                mutate("$email has been added to the designer roster.") {
                    repository.addDesignerToRoster(email, fullName, institution, notes)
                }
            }
        )
    }

    editing?.let { row ->
        RosterEditDialog(
            existing = row,
            busy = busy,
            onDismiss = { editing = null },
            onSubmit = { email, fullName, institution, notes ->
                editing = null
                mutate("The roster entry has been updated.") {
                    repository.updateDesignerRosterEntry(
                        id = row.id,
                        // Sent only when it actually changed. The email is the unique join key to the
                        // account, so an unnecessary write of it is a chance to collide with another
                        // row for no gain.
                        email = email.takeIf { !it.equals(row.email, ignoreCase = true) },
                        fullName = fullName,
                        institution = institution,
                        notes = notes,
                    )
                }
            }
        )
    }

    confirming?.let { row ->
        val suspending = row.isActive
        AlertDialog(
            onDismissRequest = { if (!busy) confirming = null },
            title = { Text(if (suspending) "Suspend this designer?" else "Restore this designer?") },
            text = {
                Text(
                    if (suspending) {
                        "${row.email} will be refused at sign-in from their next attempt, and told " +
                            "in words that their access was withdrawn. The roster row, and the " +
                            "record that they were empanelled, are kept — nothing is deleted, and " +
                            "you can restore them here at any time."
                    } else {
                        "${row.email} will be able to sign in again from their next attempt. Their " +
                            "existing workshops and reports were never touched by the suspension."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirming = null
                        if (suspending) {
                            mutate("${row.email} has been suspended.") {
                                repository.suspendDesigner(row.id)
                            }
                        } else {
                            mutate("${row.email} has been restored.") {
                                repository.restoreDesigner(row.id)
                            }
                        }
                    }
                ) { Text(if (suspending) "Suspend" else "Restore") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirming = null }) { Text("Cancel") }
            }
        )
    }
}

// --------------------------------------------------------------------------------------
// The rule
// --------------------------------------------------------------------------------------

/**
 * `can_manage_designer_roster` — Admin and above, for reads as well as writes.
 *
 * A FUNCTION rather than a remembered Boolean, so the screen's chrome and every mutation's own guard
 * are provably the same rule instead of two readings of it that can drift by one clause.
 */
private fun mayManageDesignerRoster(viewer: UserDto?): Boolean =
    viewer != null && FieldPermissions.isAdmin(viewer)

/**
 * How many accounts `GET /designers/directory` returns before it stops. Server-side `take=500`
 * (backend/app/api/routes/designers.py), mirrored here so the screen can SAY when it hit the cap.
 * The web carries the identical constant for the identical reason.
 */
internal const val DESIGNER_DIRECTORY_CAP = 500

/**
 * One page of the roster. Small: this is a phone and every card is five lines tall.
 *
 * The same number the allow-list uses, because the two screens now behave the same way and an admin
 * moving between them should not find one of them paging at fifteen and the other at fifty. The web
 * uses 20 on both, which is the right number for a table.
 */
private const val DESIGNER_PAGE_SIZE = 15

/**
 * How long after the last keystroke the search is sent.
 *
 * NEW, AND IT IS NEW BECAUSE THE BOX IS NEW. Until requirement 30 this box filtered a list already on
 * the device, so a keystroke cost nothing; it now reaches the server, and an undebounced box would
 * spend one request per letter. The allow-list's number exactly — one request per typed word, on both
 * screens, so neither feels different from the other.
 */
private const val DESIGNER_SEARCH_DEBOUNCE_MS = 400L

/**
 * Lower-cased email -> account id, the join a roster row needs to reach a profile.
 *
 * LOWER-CASED ON BOTH SIDES. The roster lower-cases the address on write, `User.email` carries
 * whatever the identity provider sent, and Google returns the address as the person typed it at
 * sign-up. Comparing them verbatim drops the profile action from exactly the rows whose owner
 * capitalised their own name — and there is nothing on screen to suggest why one row has the button
 * and the next does not.
 *
 * A blank id or a blank email is skipped rather than stored: neither can be opened, and a blank key
 * would collapse every such account into one entry that answers for all of them.
 */
internal fun accountsByEmail(directory: List<DesignerDirectoryEntryDto>): Map<String, String> =
    directory.asSequence()
        .filter { it.id.isNotBlank() && it.email.isNotBlank() }
        .associate { it.email.trim().lowercase() to it.id }

// THE DEVICE-SIDE `matches()` PREDICATE THAT STOOD HERE IS GONE, AND NOTHING REPLACES IT.
//
// It was a case-insensitive AND over email, full name and institution, applied to whichever rows the
// walk had gathered. Over a complete table that is a reasonable search box; over the 500-row prefix
// the walk actually produced on a roster of about 1,300 it was a box that answered "No roster row
// matches that filter" about designers who are on the roster — the most repeated bug class in this
// repository, on the one screen where the conclusion an admin draws from it ("this person was never
// empanelled") is about somebody's access.
//
// `search` now goes to the server, which ORs it over the same three columns
// (`api/routes/designers.py`) with `records.contains` — so the pasted-address case works properly
// too: control bytes are stripped and LIKE metacharacters escaped, where the device-side version
// treated `_` and `%` as literals and the server does not. One search, over the whole table.
//
// Do not put a predicate back here. `RosterFilterWireTest` asserts this file contains no `.filter(`
// over the fetched page, which is rule (iv) of DROPDOWN_DESIGN §4.6.

// --------------------------------------------------------------------------------------
// Rows
// --------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RosterCard(
    row: DesignerRosterDto,
    busy: Boolean,
    /** Who empanelled them, already resolved to a name; null when nothing can name them. */
    addedByLabel: String?,
    onEdit: () -> Unit,
    onToggleAccess: () -> Unit,
    /** Null when this row has no account behind it yet; the action is then not rendered at all. */
    onOpenProfile: (() -> Unit)?,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    // The admin's typed name until the account exists, and the email after that
                    // — never "Unknown". A blank name here is not missing data, it is an admin who
                    // added an address without one, and saying so keeps the row identifiable.
                    row.fullName?.takeIf { it.isNotBlank() } ?: row.email,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(row.email, color = MaterialTheme.field.muted, fontSize = 12.sp)
                row.institution?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (row.isActive) {
                    RosterBadge(
                        "Active",
                        MaterialTheme.field.successContainer,
                        MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    RosterBadge(
                        row.revokedAt?.let { "Suspended ${it.take(10)}" } ?: "Suspended",
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                // The whole reason `firstSeenAt` is on the wire. An admin who cannot tell an accepted
                // invitation from an outstanding one has no way to chase the second kind, and will
                // eventually re-add the address and be told by a unique-index 409 that the person is
                // already on a roster they are looking at.
                if (row.firstSeenAt == null) {
                    RosterBadge(
                        "Invitation outstanding",
                        MaterialTheme.field.warningContainer,
                        MaterialTheme.field.onWarningContainer
                    )
                } else {
                    RosterBadge(
                        "Signed in ${row.firstSeenAt.take(10)}",
                        MaterialTheme.field.surface200,
                        MaterialTheme.field.body
                    )
                }
            }

            row.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.field.body, fontSize = 12.sp)
            }
            addedByLabel?.takeIf { it.isNotBlank() }?.let {
                Text("Empanelled by $it", color = MaterialTheme.field.muted, fontSize = 11.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onToggleAccess, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (row.isActive) Icons.Filled.Block else Icons.Filled.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (row.isActive) "Suspend" else "Restore")
                }
            }
            // Rendered only when there IS an account, and never rendered disabled: a greyed
            // "Open profile" beside a row whose invitation is outstanding reads as a bug, where its
            // absence reads — correctly — as "there is nothing there yet".
            onOpenProfile?.let { open ->
                OutlinedButton(onClick = open, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open designer profile")
                }
            }
        }
    }
}

// `RosterNotice` MOVED TO `ui/RosterFilterBar.kt` and is imported above. It was identical to the
// allow-list's own warning box in everything but its file, and two copies of the one surface that
// says "what you are reading is not the whole answer" is exactly the drift these two screens were
// rewritten to remove.

@Composable
private fun RosterBadge(label: String, background: Color, foreground: Color) {
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
// Add / edit
// --------------------------------------------------------------------------------------

/**
 * One dialog for both actions, because they write the same four columns.
 *
 * The EMAIL is the only required field and the only one that matters — everything else is the
 * admin's note to themselves about whom they added and why. It is lower-cased on submit as well as
 * on the server: the roster is keyed by a unique email, so "A.Sharma@…" and "a.sharma@…" are one row
 * on the server and two in an admin's head, and the 409 the second attempt earns says a duplicate
 * exists without saying that the difference is a capital letter nobody can see.
 */
@Composable
private fun RosterEditDialog(
    existing: DesignerRosterDto?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (email: String, fullName: String, institution: String, notes: String) -> Unit,
) {
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var institution by remember(existing) { mutableStateOf(existing?.institution.orEmpty()) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }

    val cleaned = email.trim().lowercase()
    // Deliberately the shallowest possible check — one '@' with something either side. The server
    // validates properly with an EmailStr; a stricter regex here would reject real addresses (plus
    // addressing, long TLDs, non-ASCII local parts) and the admin would have no way to override it.
    val emailLooksUsable = cleaned.count { it == '@' } == 1 &&
        cleaned.substringBefore('@').isNotBlank() &&
        cleaned.substringAfter('@').contains('.')

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "Add a designer" else "Edit roster entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (existing == null) {
                        "The email is the whole gate. It does not need an account behind it yet — " +
                            "one is created the first time this person signs in with Google."
                    } else {
                        "Changing the email moves the gate to the new address. Anyone signing in " +
                            "with the old one will be refused."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(requiredMarked("Email *")) },
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
                OutlinedTextField(
                    value = institution,
                    onValueChange = { institution = it },
                    label = { Text("Institution") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // `DesignerRoster.notes` — an administrator's note about why this person is on the
                // roster. Dictation only: it is read in a list beside the row and nowhere else, so
                // there is nothing for formatting to survive into, and a toolbar inside an
                // AlertDialog would cost the dialog most of its height.
                RecordProseField(
                    label = "Notes",
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
                onClick = { onSubmit(cleaned, fullName, institution, notes) }
            ) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
