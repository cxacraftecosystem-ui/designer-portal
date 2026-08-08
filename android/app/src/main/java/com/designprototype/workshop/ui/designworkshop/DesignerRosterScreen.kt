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
import androidx.compose.material3.CircularProgressIndicator
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
import com.designprototype.workshop.data.DesignerRosterDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.ui.FieldPermissions
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt. Every file
// in this feature imports it, or its headings are quietly set in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
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
 */

@Composable
fun DesignerRosterScreen(
    repository: WorkshopRepository,
    /**
     * Open one designer's own profile, as an administrator, for the twenty values their reports
     * print. Offered only for rows whose [DesignerRosterDto.userId] the server resolved — a roster
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
    var loading by remember { mutableStateOf(canManage) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var showSuspended by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DesignerRosterDto?>(null) }
    var confirming by remember { mutableStateOf<DesignerRosterDto?>(null) }

    LaunchedEffect(reload, canManage) {
        // The request is not made at all for a non-admin. Making it and swallowing the 403 would put
        // an unexplained failure in the error channel every time such an account opened the screen,
        // and would ask the server to refuse something this client already knows it may not have.
        if (!canManage) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        runCatching { repository.designerRoster() }
            .onSuccess { served ->
                // Outstanding invitations first, then everyone else by name. An admin opens this
                // screen to answer "who have I added who has not turned up", so the rows that answer
                // it are the rows at the top; sorting by creation date buries them under whoever was
                // added most recently, which is the one thing they already know.
                rows = served.sortedWith(
                    compareBy<DesignerRosterDto> { it.firstSeenAt != null }
                        .thenBy { (it.fullName ?: it.email).lowercase() }
                )
            }
            .onFailure { error ->
                onError(error.apiErrorMessage("Could not load the designer roster."))
            }
        loading = false
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

        val outstanding = rows.count { it.firstSeenAt == null }
        val suspended = rows.count { !it.isActive }
        Text(
            listOfNotNull(
                "${rows.size} on the roster",
                "$outstanding invitation${if (outstanding == 1) "" else "s"} outstanding"
                    .takeIf { outstanding > 0 },
                "$suspended suspended".takeIf { suspended > 0 },
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search name or email") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { adding = true }, enabled = !busy) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }

        // A FILTER and never the default. A suspended designer hidden by default is a person an admin
        // cannot find in order to restore them, and the only way back would be re-adding an email the
        // unique index already holds — a 409 that reads as "this person is already on the roster"
        // while the roster on screen visibly does not contain them.
        FilterChip(
            selected = showSuspended,
            onClick = { showSuspended = !showSuspended },
            label = { Text(if (showSuspended) "Showing suspended designers" else "Active designers only") }
        )

        val visible = rows.filter { row ->
            (showSuspended || row.isActive) && row.matches(search)
        }

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            rows.isEmpty() -> Text(
                "Nobody is on the designer roster yet. Until somebody is, no account can hold the " +
                    "Designer role and sign in with it.",
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )

            visible.isEmpty() -> Text(
                "No roster row matches that filter.",
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )

            else -> visible.forEach { row ->
                RosterCard(
                    row = row,
                    busy = busy,
                    onEdit = { editing = row },
                    onToggleAccess = { confirming = row },
                    onOpenProfile = row.userId?.let { id ->
                        { onOpenProfile(id, row.fullName?.takeIf { n -> n.isNotBlank() } ?: row.email) }
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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

/** Case-insensitive match over the two columns an admin actually remembers somebody by. */
private fun DesignerRosterDto.matches(query: String): Boolean {
    val terms = query.trim().split(' ').filter { it.isNotBlank() }
    if (terms.isEmpty()) return true
    val haystack = "$email ${fullName.orEmpty()} ${institution.orEmpty()}"
    return terms.all { haystack.contains(it, ignoreCase = true) }
}

// --------------------------------------------------------------------------------------
// Rows
// --------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RosterCard(
    row: DesignerRosterDto,
    busy: Boolean,
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
            row.addedByName?.takeIf { it.isNotBlank() }?.let {
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
                    label = { Text("Email *") },
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
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
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
