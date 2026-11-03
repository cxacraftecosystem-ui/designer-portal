package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.destructiveAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_INSPECTOR_LIMIT
import com.designprototype.workshop.data.DwEligibleInspectorDto
import com.designprototype.workshop.data.DwEligibleInspectors
import com.designprototype.workshop.data.DwInspectionAttempt
import com.designprototype.workshop.data.DwInspectorChoice
import com.designprototype.workshop.data.DwInspectorDto
import com.designprototype.workshop.data.DwInspectorSelection
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwInspectionAdministrationMissing
import com.designprototype.workshop.data.dwInspectionFailureMessage
import com.designprototype.workshop.data.dwInspectorChoices
import com.designprototype.workshop.data.dwInspectorOfferNotice
import com.designprototype.workshop.data.dwInspectorSearchTerm
import com.designprototype.workshop.data.dwPersonLabel
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

private const val SEARCH_DEBOUNCE_MS = 350L

@Composable
fun WorkshopInspectorsScreen(
    repository: WorkshopRepository,
    workshopId: String,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    val viewer = remember(repository) { repository.cachedUser() }
    val canAdminister = remember(viewer) { mayAdministerInspections(viewer) }

    var loading by remember(workshopId) { mutableStateOf(canAdminister) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var saving by remember(workshopId) { mutableStateOf(false) }

    var title by remember(workshopId) { mutableStateOf("") }
    var onServer by remember(workshopId) { mutableStateOf(true) }
    var featureMissing by remember(workshopId) { mutableStateOf(false) }

    var offer by remember(workshopId) { mutableStateOf(DwEligibleInspectors()) }

    var seenEligible by remember(workshopId) {
        mutableStateOf<Map<String, DwEligibleInspectorDto>>(emptyMap())
    }
    var rows by remember(workshopId) { mutableStateOf<List<DwInspectorDto>?>(null) }
    var selection by remember(workshopId) { mutableStateOf(DwInspectorSelection()) }

    var query by remember(workshopId) { mutableStateOf("") }
    var searching by remember(workshopId) { mutableStateOf(false) }

    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    var saveError by remember(workshopId) { mutableStateOf<String?>(null) }
    var searchError by remember(workshopId) { mutableStateOf<String?>(null) }

    LaunchedEffect(workshopId, reload, canAdminister) {
        if (!canAdminister) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null

        val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
            ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
        if (remoteId == null) {
            onServer = false
            loading = false
            return@LaunchedEffect
        }
        onServer = true

        runCatching { repository.designWorkshop(remoteId) }
            .onSuccess { title = it.title }
            .onFailure { error ->
                loadError = error.inspectionFailure(DwInspectionAttempt.READ)
                loading = false
                return@LaunchedEffect
            }

        val term = dwInspectorSearchTerm(query)
        runCatching { repository.eligibleDesignWorkshopInspectors(term) }
            .onSuccess { answered ->
                offer = answered
                seenEligible = answered.users.associateBy { it.id }
                searchError = null
                featureMissing = false
            }
            .onFailure { error ->
                val status = (error as? HttpException)?.code()
                if (dwInspectionAdministrationMissing(status)) {
                    featureMissing = true
                    offer = DwEligibleInspectors()
                    loading = false
                    return@LaunchedEffect
                }
                offer = DwEligibleInspectors()
                loadError = error.inspectionFailure(DwInspectionAttempt.READ)
                loading = false
                return@LaunchedEffect
            }

        runCatching { repository.designWorkshopInspectors(remoteId) }
            .onSuccess { served ->
                rows = served
                selection = DwInspectorSelection.adopt(served)
            }
            .onFailure { error ->
                rows = null
                if (loadError == null) loadError = error.inspectionFailure(DwInspectionAttempt.READ)
            }
        loading = false
    }

    LaunchedEffect(query) {
        val term = dwInspectorSearchTerm(query)
        if (rows == null || term == offer.search) return@LaunchedEffect
        if (term != null) delay(SEARCH_DEBOUNCE_MS)
        searching = true
        val answered = runCatching { repository.eligibleDesignWorkshopInspectors(term) }
        searching = false
        answered
            .onSuccess { served ->
                offer = served
                seenEligible = seenEligible + served.users.associateBy { it.id }
                searchError = null
            }
            .onFailure { error ->
                if (error is CancellationException) return@onFailure
                searchError = error.inspectionFailure(DwInspectionAttempt.READ)
            }
    }

    fun save() {
        if (!mayAdministerInspections(repository.cachedUser())) {
            saveError = "Only an admin can assign inspectors."
            return
        }
        saving = true
        saveError = null
        scope.launch {
            val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
                ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            if (remoteId == null) {
                saveError = "Workshop not found."
                saving = false
                return@launch
            }
            runCatching { repository.setDesignWorkshopInspectors(remoteId, selection.payload()) }
                .onSuccess { served ->
                    rows = served
                    selection = DwInspectorSelection.adopt(served)
                    val count = selection.baseline.size
                    onMessage(
                        if (count == 0) {
                            "No inspectors assigned."
                        } else {
                            "$count ${if (count == 1) "inspector" else "inspectors"} assigned (read-only)."
                        }
                    )
                }
                .onFailure { error -> saveError = error.inspectionFailure(DwInspectionAttempt.SAVE) }
            saving = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Inspection of a design workshop",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        if (title.isNotBlank()) {
            Text(title, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }

        if (!canAdminister) {
            InspectionNotice(
                "Only an admin assigns inspectors, to keep reviews independent.",
                warning = true
            )
            return@Column
        }

        Text(
            "Who can inspect this workshop.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Inspectors can read every stage but change nothing. Admins appointed here lose edit access while they hold the post.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            "Needs a connection.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        if (!onServer) {
            InspectionNotice(
                "Upload this workshop from the list first.",
                warning = true
            )
            return@Column
        }

        if (featureMissing) {
            return@Column
        }

        loadError?.let { InspectionNotice(it, warning = false) }

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        val served = rows
        if (served == null) {
            OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            return@Column
        }

        val retained = remember(seenEligible, offer, selection.selected) {
            val answered = offer.users.mapTo(HashSet()) { it.id }
            seenEligible.values.filter { it.id !in answered && it.id in selection.selected }
        }
        val choices = remember(offer, retained, served, viewer?.id) {
            dwInspectorChoices(
                eligible = offer.users,
                inspectors = served,
                readerId = viewer?.id,
                eligibleListComplete = offer.complete,
                retained = retained,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search by name or email") },
            singleLine = true,
            enabled = !saving,
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
                    searching -> FieldCircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
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
        if (searchError == null) {
            dwInspectorOfferNotice(offer)?.let {
                Text(
                    it,
                    color = if (offer.truncated) MaterialTheme.field.warning else MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        searchError?.let { InspectionNotice(it, warning = false) }

        SearchableMultiSelectField(
            label = "Inspectors assigned to this workshop",
            options = choices.map { it.asOption() },
            selected = selection.selected,
            placeholder = "Select one or more inspectors",
            emptyMessage = if (offer.search != null) {
                "No matches."
            } else {
                "Nobody else may be appointed to inspect yet"
            },
            enabled = !saving,
            onSelectedChange = { picked -> selection = selection.withSelection(picked) }
        )
        Text(
            "Inspector and admin accounts that can sign in (not you). People who worked on this workshop are refused. Max $DW_INSPECTOR_LIMIT.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        if (served.isEmpty()) {
            Text(
                "Not under inspection.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            served.forEach { row ->
                AssignedRow(row = row, willEnd = row.userId in selection.removed)
            }
        }

        val added = choices.filter { it.userId in selection.added }
        if (added.isNotEmpty()) {
            Text(
                "Will be assigned on save: " +
                    added.joinToString(", ") { dwPersonLabel(it.name, it.email) } + ".",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (selection.overLimit) {
            val over = selection.payload().size - DW_INSPECTOR_LIMIT
            InspectionNotice(
                "Max $DW_INSPECTOR_LIMIT. Untick $over to save.",
                warning = true
            )
        }

        saveError?.let { InspectionNotice(it, warning = false) }

        ActionRow {
            Button(
                onClick = { save() },
                enabled = selection.dirty && !saving && !selection.overLimit,
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (saving) "Saving…" else "Save")
            }
            if (selection.dirty) {
                OutlinedButton(modifier = Modifier.destructiveAction(), 
                    onClick = { selection = selection.discard(); saveError = null },
                    enabled = !saving,
                ) { Text("Discard") }
            }
        }
        if (selection.dirty) {
            val pending = selection.added.size + selection.removed.size
            Text(
                "$pending unsaved change${if (pending == 1) "" else "s"} · " +
                    "${selection.resultingCount} " +
                    (if (selection.resultingCount == 1) "account" else "accounts") +
                    " would be inspecting this workshop.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

internal fun mayAdministerInspections(user: UserDto?): Boolean =
    user != null && FieldPermissions.isAdmin(user)

internal fun Throwable.inspectionFailure(attempt: DwInspectionAttempt): String {
    val status = (this as? HttpException)?.code()
        ?: return dwInspectionFailureMessage(null, null, attempt)
    val said = apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
    return dwInspectionFailureMessage(status, said, attempt)
}

private fun DwInspectorChoice.asOption(): SelectOption = SelectOption(
    value = userId,
    label = dwPersonLabel(name, email),
    hint = listOfNotNull(
        email.takeIf { it.isNotBlank() },
        FieldPermissions.label(role).takeIf { it.isNotBlank() },
        "assigned, no longer eligible".takeIf { assignedButIneligible },
    ).joinToString(" · ").takeIf { it.isNotBlank() }
)

@Composable
private fun AssignedRow(row: DwInspectorDto, willEnd: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            dwPersonLabel(row.name, row.email),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            listOfNotNull(
                row.email.takeIf { it.isNotBlank() },
                FieldPermissions.label(row.role).takeIf { it.isNotBlank() },
                row.assignedAt?.takeIf { it.isNotBlank() }?.let { "assigned ${it.take(10)}" },
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (willEnd) {
            Text(
                "inspection ends on save",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
internal fun InspectionNotice(text: String, warning: Boolean) {
    Text(
        text,
        color = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) MaterialTheme.field.warningContainer else MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp)
    )
}
