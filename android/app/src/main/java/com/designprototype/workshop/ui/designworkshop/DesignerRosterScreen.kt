package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import com.designprototype.workshop.ui.RecordDetailsDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.designprototype.workshop.questionnaire.ui.rememberKept
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
import com.designprototype.workshop.ui.RecordOpen
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
import com.designprototype.workshop.ui.RecordProseField
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignerRosterScreen(
    repository: WorkshopRepository,
    onOpenProfile: (userId: String, label: String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewer = remember(repository) { repository.cachedUser() }
    val canManage = remember(viewer) { mayManageDesignerRoster(viewer) }

    var rows by rememberKept("roster.rows") { emptyList<DesignerRosterDto>() }
    var rosterTotal by rememberKept("roster.total") { 0 }
    var pages by rememberKept("roster.pages") { 0 }
    var page by rememberKept("roster.page") { 1 }
    var accounts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var directoryNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var directoryCapped by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(canManage) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var search by rememberKept("roster.search") { "" }
    var filters by rememberKept("roster.filters") { emptyRosterFilters(RosterKind.DESIGNER) }
    var filterSheet by remember { mutableStateOf(false) }
    var institutions by remember {
        mutableStateOf<InstitutionVocabulary>(InstitutionVocabulary.Loading)
    }
    var readFailure by remember { mutableStateOf<String?>(null) }
    var roleMatchTruncated by remember { mutableStateOf<Boolean?>(null) }
    var grammarUnderstood by remember { mutableStateOf<Boolean?>(null) }
    var grammarKeysSent by remember { mutableStateOf<List<String>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DesignerRosterDto?>(null) }
    var confirming by remember { mutableStateOf<DesignerRosterDto?>(null) }

    LaunchedEffect(search) {
        if (search == filters.search) return@LaunchedEffect
        delay(DESIGNER_SEARCH_DEBOUNCE_MS)
        filters = filters.copy(search = search)
        page = 1
    }

    LaunchedEffect(reload, canManage, page, filters) {
        if (!canManage) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
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
            rows = served.items
            rosterTotal = served.total
            pages = served.pages
            readFailure = null
            roleMatchTruncated = served.roleMatchTruncated
            grammarUnderstood = served.roleMatchTruncated != null
            grammarKeysSent = query.newGrammarKeys
            if (served.items.isEmpty() && served.pages > 0 && page > served.pages) page = served.pages
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val said = error.apiErrorMessage("Could not load the designer roster.")
            onError(said)
            grammarKeysSent = query.newGrammarKeys
            readFailure = said
        }
        loading = false
    }

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

    LaunchedEffect(canManage) {
        if (!canManage) return@LaunchedEffect
        val served = try {
            repository.designerDirectory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (offline: Throwable) {
            return@LaunchedEffect
        }
        accounts = accountsByEmail(served)
        directoryNames = served.associate { it.id to (it.name?.takeIf(String::isNotBlank) ?: it.email) }
        directoryCapped = served.size >= DESIGNER_DIRECTORY_CAP
    }

    fun mutate(what: String, block: suspend () -> Unit) {
        if (!mayManageDesignerRoster(repository.cachedUser())) {
            onError("Only an admin can change the roster.")
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
            style = MaterialTheme.typography.headlineSmall
        )

        if (!canManage) {
            Text(
                "The designer roster is admin-only.",
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
            "Only emails on this roster can sign in as designers. Accounts are created on first Google sign-in.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            "$rosterTotal on the roster",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        RosterHint(
            "Sort by “First signed in” to see who hasn't joined; use the chips to see who can't sign in."
        )

        if (directoryCapped) {
            RosterNotice(
                "Only the first $DESIGNER_DIRECTORY_CAP accounts are matched, so some profile links may be missing."
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
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

        rosterFilterGrammarNotice(grammarUnderstood, grammarKeysSent)?.let { RosterNotice(it) }
        roleMatchCutNotice(roleMatchTruncated)?.let { RosterNotice(it) }
        if (readFailure != null && rows.isNotEmpty()) {
            RosterNotice(
                "Last refresh failed — recent changes may not show."
            )
        }

        when {
            loading -> RosterLoadingRow("Loading the roster…")

            readFailure != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RosterEmptyState(
                    title = "The roster could not be loaded",
                    body = "Couldn't load the roster. Nothing changed. Try again online.",
                )
                rosterFilterRefusalHint(grammarUnderstood, grammarKeysSent)?.let { RosterNotice(it) }
            }

            rows.isEmpty() && rosterTotal > 0 -> RosterEmptyState(
                title = "Nothing on this page",
                body = "Nothing on this page. Go back a page.",
            )

            rows.isEmpty() && hasActiveRosterFilters(RosterKind.DESIGNER, filters) -> RosterEmptyState(
                title = "Nobody matches these filters",
                body = "No matches. Clear filters to see everyone.",
            )

            rows.isEmpty() -> RosterEmptyState(
                title = "Nobody is on the designer roster yet",
                body = "Add the first email above to let a designer sign in.",
            )

            else -> rows.forEach { row ->
                RosterCard(
                    row = row,
                    busy = busy,
                    addedByLabel = row.addedByName?.takeIf { it.isNotBlank() }
                        ?: row.addedById?.let { directoryNames[it] },
                    onEdit = { editing = row },
                    onToggleAccess = { confirming = row },
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
                search = next.search
                filters = next
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
                mutate("$email added to the roster.") {
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
                        "${row.email} won't be able to sign in. Nothing is deleted; you can restore them any time."
                    } else {
                        "${row.email} can sign in again."
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

private fun mayManageDesignerRoster(viewer: UserDto?): Boolean =
    viewer != null && FieldPermissions.isAdmin(viewer)

internal const val DESIGNER_DIRECTORY_CAP = 500

private const val DESIGNER_PAGE_SIZE = 15

private const val DESIGNER_SEARCH_DEBOUNCE_MS = 400L

internal fun accountsByEmail(directory: List<DesignerDirectoryEntryDto>): Map<String, String> =
    directory.asSequence()
        .filter { it.id.isNotBlank() && it.email.isNotBlank() }
        .associate { it.email.trim().lowercase() to it.id }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RosterCard(
    row: DesignerRosterDto,
    busy: Boolean,
    addedByLabel: String?,
    onEdit: () -> Unit,
    onToggleAccess: () -> Unit,
    onOpenProfile: (() -> Unit)?,
) {
    var viewing by remember(row.id) { mutableStateOf(false) }
    val name = row.fullName?.takeIf { it.isNotBlank() } ?: row.email
    if (viewing) {
        RecordDetailsDialog(
            title = name,
            details = listOf(
                "Email" to row.email,
                "Institution" to (row.institution ?: "Not recorded"),
                "May sign in" to if (row.isActive) "Yes" else ("Suspended" + (row.revokedAt?.let { " ${it.take(10)}" } ?: "")),
                "Invitation" to (row.firstSeenAt?.let { "Signed in ${it.take(10)}" } ?: "Outstanding"),
                "Empanelled by" to addedByLabel,
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
                row.institution?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
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
                Text(it, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
            }
            addedByLabel?.takeIf { it.isNotBlank() }?.let {
                Text("Empanelled by $it", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            ActionRow {
                OutlinedButton(onClick = onEdit, enabled = !busy) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(RecordOpen.UPDATE_LABEL)
                }
                OutlinedButton(onClick = onToggleAccess, enabled = !busy) {
                    Icon(
                        if (row.isActive) Icons.Filled.Block else Icons.Filled.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (row.isActive) "Suspend" else "Restore")
                }
            }
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

@Composable
private fun RosterBadge(label: String, background: Color, foreground: Color) {
    Text(
        label,
        color = foreground,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

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
                        "This email is their access. An account is created on first Google sign-in."
                    } else {
                        "The old email will no longer sign in."
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
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
