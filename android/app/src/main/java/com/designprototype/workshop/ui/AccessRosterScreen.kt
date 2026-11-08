package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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

private const val ACCESS_PAGE_SIZE = 15

private const val NEW_ACCOUNTS_ONLY = "New accounts only. To change an existing account, use Manage users."

internal fun heldSentence(answer: Any?): String? =
    (answer as? AccessRosterDto)?.accountPromotionHeld?.trim()?.ifBlank { null }

private const val ACCESS_QUEUE_PAGE_SIZE = 5

private const val ACCESS_SEARCH_DEBOUNCE_MS = 400L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessRosterScreen(
    repository: WorkshopRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    onPendingCount: (Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val viewer = remember(repository) { repository.cachedUser() }
    val canManage = remember(viewer) { mayManageAccessRoster(viewer) }

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

    var filters by remember { mutableStateOf(emptyRosterFilters(RosterKind.ACCESS)) }
    var filterSheet by remember { mutableStateOf(false) }

    var readFailure by remember { mutableStateOf<String?>(null) }
    var roleMatchTruncated by remember { mutableStateOf<Boolean?>(null) }
    var grammarUnderstood by remember { mutableStateOf<Boolean?>(null) }
    var grammarKeysSent by remember { mutableStateOf<List<String>>(emptyList()) }

    var loading by remember { mutableStateOf(canManage) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccessRosterDto?>(null) }
    var deciding by remember { mutableStateOf<Pair<AccessRosterDto, String>?>(null) }
    var suspending by remember { mutableStateOf<AccessRosterDto?>(null) }

    LaunchedEffect(search) {
        if (search == filters.search) return@LaunchedEffect
        delay(ACCESS_SEARCH_DEBOUNCE_MS)
        filters = filters.copy(search = search)
        page = 1
    }

    LaunchedEffect(reload, canManage, queuePage) {
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
            if (served.items.isEmpty() && served.pages > 0 && queuePage > served.pages) {
                queuePage = served.pages
            }
            onPendingCount(served.total)
        } catch (cancelled: CancellationException) {
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
            grammarUnderstood = served.roleMatchTruncated != null
            grammarKeysSent = query.newGrammarKeys
            if (served.items.isEmpty() && served.pages > 0 && page > served.pages) page = served.pages
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val said = error.apiErrorMessage("Could not load the list of who may sign in.")
            onError(said)
            grammarKeysSent = query.newGrammarKeys
            readFailure = said
        }
        loading = false
    }

    fun mutate(what: String, block: suspend () -> Any?) {
        if (!mayManageAccessRoster(repository.cachedUser())) {
            onError("Only an admin can manage sign-in access.")
            return
        }
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess { answer ->
                    onMessage(heldSentence(answer) ?: what)
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
            style = MaterialTheme.typography.headlineSmall
        )

        if (!canManage) {
            Text(
                "Who may sign in, and who's waiting. Admin-only.",
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            )
            return@Column
        }

        Text(
            "Everyone signs in through this list (except the master admin). Entries are never deleted.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            if (queueTotal > 0) "Waiting for a decision · $queueTotal" else "Waiting for a decision",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "These people signed in successfully but aren't on the list yet.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        when {
            !queueLoaded -> RosterLoadingRow("Loading the approval queue…")
            queue.isEmpty() -> Text(
                "Nobody is waiting.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
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

        AccessStatusChips(
            selected = filters.status,
            onSelect = { picked -> filters = filters.copy(status = picked); page = 1 },
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            RosterFilterButton(RosterKind.ACCESS, filters) { filterSheet = true }
            RosterSortButton(RosterKind.ACCESS, filters) { filterSheet = true }
        }

        rosterFilterGrammarNotice(grammarUnderstood, grammarKeysSent)?.let { RosterNotice(it) }
        accessRoleCutNotice(roleMatchTruncated)?.let { RosterNotice(it) }
        if (readFailure != null && rows.isNotEmpty()) {
            RosterNotice(
                "Last refresh failed — recent changes may not show."
            )
        }

        when {
            loading -> RosterLoadingRow("Loading…")

            readFailure != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RosterEmptyState(
                    title = ACCESS_LIST_UNREADABLE_TITLE,
                    body = ACCESS_LIST_UNREADABLE_BODY,
                )
                rosterFilterRefusalHint(grammarUnderstood, grammarKeysSent)?.let { RosterNotice(it) }
            }

            rows.isEmpty() && total > 0 -> RosterEmptyState(
                title = ACCESS_PAST_END_TITLE,
                body = "Nothing on this page. Go back a page.",
            )

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
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (filterSheet) {
        RosterFilterSheet(
            kind = RosterKind.ACCESS,
            filters = filters,
            onChange = { next ->
                search = next.search
                filters = next
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
            },
            onSubmitWithPassword = { email, fullName, role, notes, password, mustChange ->
                adding = false
                mutate(accessAddedLine(email, AccessSignIn.PASSWORD)) {
                    repository.addToAccessRoster(
                        email = email,
                        fullName = fullName,
                        role = role,
                        notes = notes,
                        password = password,
                        mustChangePassword = mustChange
                    )
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
                        "They can sign in from their next attempt, as " +
                            (row.admitRole?.let { FieldPermissions.label(it) } ?: "the default joining tier — the lowest rung") +
                            ". " + NEW_ACCOUNTS_ONLY
                    } else {
                        "They'll be told they weren't approved and whom to contact. Any session they are in now ends with it. Only you can reopen this later."
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
                            mutate("${row.email} refused.") {
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
                    "Suspending signs them out now and blocks future sign-ins. Approve again to restore; their join date is kept."
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

private fun mayManageAccessRoster(viewer: UserDto?): Boolean =
    viewer != null && FieldPermissions.canManageAccessRoster(viewer)

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
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                listOfNotNull(
                    row.requestedAt?.let { "Asked ${it.take(10)}" } ?: "Asked at an unrecorded time",
                    "${row.attemptCount} attempt${if (row.attemptCount == 1) "" else "s"}",
                    row.lastAttemptAt?.let { "last ${it.take(10)}" },
                ).joinToString(" · "),
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall
            )
            ActionRow {
                Button(onClick = onApprove, enabled = !busy) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Approve")
                }
                OutlinedButton(onClick = onReject, enabled = !busy) {
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
    var viewing by remember(row.id) { mutableStateOf(false) }
    val name = row.fullName?.takeIf { it.isNotBlank() } ?: row.email
    if (viewing) {
        RecordDetailsDialog(
            title = name,
            details = listOf(
                "Email" to row.email,
                "Standing" to when (row.status) {
                    AccessStatus.ACTIVE -> row.admitRole?.let { "May sign in as ${FieldPermissions.label(it)}" } ?: "May sign in"
                    AccessStatus.PENDING -> "Waiting for a decision"
                    else -> (if (row.status == AccessStatus.REJECTED) "Refused" else "Suspended") +
                        (row.decidedAt?.let { " ${it.take(10)}" } ?: "")
                },
                "Added" to (row.createdAt?.take(10) ?: "Not recorded"),
                "Added by" to row.addedByName,
                "Decided by" to row.decidedByName.takeIf { row.status != AccessStatus.PENDING },
                "Joined" to (row.joinedAt?.take(10) ?: "Not yet"),
                "First signed in" to (row.firstSeenAt?.take(10)
                    ?: if (row.joinedAt != null) "Has not signed in yet" else "Has never been admitted"),
                "Requests" to if (row.attemptCount > 0) {
                    "${row.attemptCount} refused attempt${if (row.attemptCount == 1) "" else "s"}" +
                        (row.lastAttemptAt?.let { ", last ${it.take(10)}" } ?: "")
                } else {
                    "None"
                },
                "Note" to row.notes
            ),
            onDismiss = { viewing = false },
            onUpdate = if (busy) null else ({ viewing = false; onEdit() })
        )
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = RecordOpen.openClickLabel(name), role = Role.Button) { viewing = true }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.fullName?.takeIf { it.isNotBlank() } ?: row.email,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(row.email, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AccessBadge(row)
                row.joinedAt?.let {
                    AccessChip("Joined ${it.take(10)}", MaterialTheme.field.surface200, MaterialTheme.field.body)
                }
                if (row.status == AccessStatus.ACTIVE && row.firstSeenAt == null) {
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
                Text(it, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
            }

            ActionRow {
                OutlinedButton(onClick = onEdit, enabled = !busy) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(RecordOpen.UPDATE_LABEL)
                }
                if (row.status == AccessStatus.ACTIVE) {
                    OutlinedButton(onClick = onSuspend, enabled = !busy) {
                        Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Suspend")
                    }
                } else {
                    OutlinedButton(onClick = onApprove, enabled = !busy) {
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
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

private const val ACCESS_LIST_UNREADABLE_TITLE = "The list could not be loaded"

private const val ACCESS_LIST_UNREADABLE_BODY =
    "Couldn't load the list. No one's access changed."

private const val ACCESS_NO_MATCH_TITLE = "Nobody matches these filters"

private const val ACCESS_NO_MATCH_BODY =
    "Clear filters to see everyone, including refused and suspended."

private const val ACCESS_NOBODY_YET_TITLE = "Nobody is on the list yet"

private const val ACCESS_NOBODY_YET_BODY =
    "Add the first email above. The master admin can always sign in."

private const val ACCESS_PAST_END_TITLE = "Nothing on this page"

@Composable
private fun AccessEditDialog(
    existing: AccessRosterDto?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (email: String, fullName: String, role: String?, notes: String) -> Unit,
    onSubmitWithPassword: (
        email: String,
        fullName: String,
        role: String?,
        notes: String,
        password: String,
        mustChangePassword: Boolean,
    ) -> Unit = { _, _, _, _, _, _ -> },
) {
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var signIn by remember(existing) { mutableStateOf(AccessSignIn.PROVIDER) }
    var password by remember(existing) { mutableStateOf("") }
    var mustChange by remember(existing) { mutableStateOf(true) }
    var revealPassword by remember { mutableStateOf(false) }
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    var role by remember(existing) { mutableStateOf(existing?.admitRole) }
    var rolesOpen by remember { mutableStateOf(false) }

    val cleaned = email.trim().lowercase()
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
                        "Adding someone approves them; their account is created at first sign-in."
                    } else {
                        "Name, tier and note are for your records only. Use Approve, Refuse or Suspend to change access."
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
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
                    label = { Text(requiredMarked(if (signIn == AccessSignIn.PASSWORD) "Full name *" else "Full name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (existing == null) {
                    Text(ACCESS_SIGN_IN_QUESTION, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    listOf(
                        AccessSignIn.PROVIDER to ACCESS_SIGN_IN_PROVIDER,
                        AccessSignIn.PASSWORD to ACCESS_SIGN_IN_PASSWORD,
                    ).forEach { (option, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = signIn == option,
                                    role = Role.RadioButton,
                                    onClick = { signIn = option }
                                )
                        ) {
                            FieldRadioButton(selected = signIn == option, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(accessSignInNote(signIn), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    if (signIn == AccessSignIn.PASSWORD) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(requiredMarked("Password *")) },
                            supportingText = { Text(passwordRuleLine()) },
                            singleLine = true,
                            visualTransformation = passwordTransformation(revealPassword),
                            trailingIcon = { PasswordRevealIcon(revealPassword, { revealPassword = !revealPassword }) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        AppToggle(
                            label = ACCESS_MUST_CHANGE_LABEL,
                            checked = mustChange,
                            onCheckedChange = { mustChange = it }
                        )
                    }
                }

                SearchableSelectField(
                    label = "Joins as",
                    options = ACCESS_GRANTABLE_ROLES.map { SelectOption(it, FieldPermissions.label(it)) },
                    selectedValue = role.orEmpty(),
                    placeholder = "Default joining tier (lowest rung)",
                    includeNone = true,
                    onSelect = { picked -> role = picked.ifBlank { null } }
                )
                Text(NEW_ACCOUNTS_ONLY, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

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
            val addProblem = if (existing == null) accessAddProblem(signIn, password, fullName) else null
            TextButton(
                enabled = !busy && emailLooksUsable && addProblem == null,
                onClick = {
                    if (existing == null && signIn == AccessSignIn.PASSWORD) {
                        onSubmitWithPassword(cleaned, fullName, role, notes, password, mustChange)
                    } else {
                        onSubmit(cleaned, fullName, role, notes)
                    }
                }
            ) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

private val ACCESS_GRANTABLE_ROLES = listOf(
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "DESIGNER",
    "INSPECTOR",
    "PROFESSOR",
    "ASSISTANT_DIRECTOR",
    "REGIONAL_DIRECTOR",
    "MINISTRY_ADMIN",
)
