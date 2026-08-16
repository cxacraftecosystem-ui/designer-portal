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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
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
 * ── THE SERVER PAGES AND THE SERVER SEARCHES ─────────────────────────────────────────────────────
 *
 * Unlike [com.designprototype.workshop.ui.designworkshop.DesignerRosterScreen], which walks its
 * whole table and filters on the device, this screen asks the server for one page at a time and
 * sends the search term with it. The difference is what the two tables ARE: a roster is a few
 * hundred empanelments an admin controls, while this holds every address ever admitted OR REFUSED,
 * including every stranger who has ever tried a password against the front door — it grows without
 * bound and in a direction nobody controls. Walking it on a handset to find three pending rows would
 * download the history of the front door over mobile data.
 *
 * The consequence is stated rather than hidden: every count on screen is the SERVER's `total`, and
 * the page indicator says which page of how many is being read. `docs/OPEN_FINDINGS.md` records four
 * closed defects in the viewer picker and three were the same shape — one page fetched, filtered
 * locally, with nothing saying the answer was a prefix, so eligible people were invisible and looked
 * exactly like people who had never existed.
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
    /** What was actually SENT. Separate from [search] so the debounce cannot re-fire on every frame. */
    var applied by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<String?>(null) }

    var loading by remember { mutableStateOf(canManage) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccessRosterDto?>(null) }
    var deciding by remember { mutableStateOf<Pair<AccessRosterDto, String>?>(null) }
    var suspending by remember { mutableStateOf<AccessRosterDto?>(null) }

    // The search box, debounced. Keyed on the raw text and NOT on `applied`, so a keystroke restarts
    // the timer rather than queueing a second request behind the first.
    LaunchedEffect(search) {
        if (search == applied) return@LaunchedEffect
        delay(ACCESS_SEARCH_DEBOUNCE_MS)
        applied = search
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

    LaunchedEffect(reload, canManage, page, applied, statusFilter) {
        if (!canManage) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val served = repository.accessRoster(
                page = page,
                pageSize = ACCESS_PAGE_SIZE,
                status = statusFilter,
                search = applied,
            )
            rows = served.items
            total = served.total
            pages = served.pages
            // The same step-back as the queue above, for the same reason: suspending the last row of
            // the last page must not leave the admin on an empty page below a total that says
            // otherwise.
            if (served.items.isEmpty() && served.pages > 0 && page > served.pages) page = served.pages
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onError(error.apiErrorMessage("Could not load the list of who may sign in."))
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
            !queueLoaded -> LoadingRow("Loading the approval queue…")
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
            PageBar(
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
                label = { Text("Search email, name or note") },
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
        AccessStatusFilter(selected = statusFilter, onSelect = { statusFilter = it; page = 1 })

        when {
            loading -> LoadingRow("Loading…")
            rows.isEmpty() -> Text(
                // NEVER "narrow your search" over a search nobody typed. That advice, printed over
                // an empty result, is the closed viewer-picker defect arriving on a new screen.
                if (applied.isNotBlank() || statusFilter != null) {
                    "No entry matches that search or filter. Clear both to see everyone this " +
                        "application has ever admitted, refused or suspended."
                } else {
                    "Nobody has been admitted or turned away yet. The master admin can always sign " +
                        "in regardless of this list, which is what makes it safe to start empty."
                },
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
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
            PageBar(page = page, pages = pages, total = total, noun = "in all", busy = busy, onPage = { page = it })
        } else if (rows.isNotEmpty()) {
            Text("$total in all", color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
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

@Composable
private fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(label, color = MaterialTheme.field.muted, fontSize = 13.sp)
    }
}

/**
 * Which page of how many, and how many rows there are in all.
 *
 * THE SERVER'S NUMBERS, NOT THE SCREEN'S. A list that says "12" when the table holds 300 is the
 * defect this repository has already closed four times in one picker: the rows an admin cannot see
 * look exactly like people who were never there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageBar(page: Int, pages: Int, total: Int, noun: String, busy: Boolean, onPage: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(onClick = { onPage(page - 1) }, enabled = !busy && page > 1) { Text("Previous") }
        Text(
            "Page $page of $pages · $total $noun",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = { onPage(page + 1) }, enabled = !busy && page < pages) { Text("Next") }
    }
}

/**
 * The status filter, as chips.
 *
 * "Everyone" is the default and is a chip like the others rather than the absence of one, so the
 * widest view is somewhere an admin can get BACK to. A filter you can enter and not leave is how a
 * screen ends up looking empty for reasons nothing on it explains.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessStatusFilter(selected: String?, onSelect: (String?) -> Unit) {
    val options = listOf(
        null to "Everyone",
        AccessStatus.ACTIVE to "May sign in",
        AccessStatus.PENDING to "Waiting",
        AccessStatus.REJECTED to "Refused",
        AccessStatus.SUSPENDED to "Suspended",
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

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
                    label = { Text(if (existing == null) "Email *" else "Email (cannot be changed here)") },
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
    "PROFESSOR",
)
