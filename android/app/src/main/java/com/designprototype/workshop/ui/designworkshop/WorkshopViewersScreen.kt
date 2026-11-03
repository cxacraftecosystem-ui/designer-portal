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
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import com.designprototype.workshop.data.DwEligibleViewerDto
import com.designprototype.workshop.data.DwEligibleViewers
import com.designprototype.workshop.data.DwViewerAttempt
import com.designprototype.workshop.data.DwViewerChoice
import com.designprototype.workshop.data.DwViewerDto
import com.designprototype.workshop.data.DwViewerSelection
import com.designprototype.workshop.data.DW_VIEWER_LIMIT
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwPersonLabel
import com.designprototype.workshop.data.dwViewerAdministrationMissing
import com.designprototype.workshop.data.dwViewerChoices
import com.designprototype.workshop.data.dwViewerFailureMessage
import com.designprototype.workshop.data.dwViewerOfferNotice
import com.designprototype.workshop.data.dwViewerSearchTerm
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
fun WorkshopViewersScreen(
    repository: WorkshopRepository,
    workshopId: String,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    val viewer = remember(repository) { repository.cachedUser() }
    val canAdminister = remember(viewer) { mayAdministerViewers(viewer) }
    val readerIsAdmin = remember(viewer) { viewer != null && FieldPermissions.isAdmin(viewer) }

    var loading by remember(workshopId) { mutableStateOf(canAdminister) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var saving by remember(workshopId) { mutableStateOf(false) }

    var title by remember(workshopId) { mutableStateOf("") }
    var creatorId by remember(workshopId) { mutableStateOf<String?>(null) }
    var onServer by remember(workshopId) { mutableStateOf(true) }
    var featureMissing by remember(workshopId) { mutableStateOf(false) }

    var offer by remember(workshopId) { mutableStateOf(DwEligibleViewers()) }
    var seenEligible by remember(workshopId) {
        mutableStateOf<Map<String, DwEligibleViewerDto>>(emptyMap())
    }
    var rows by remember(workshopId) { mutableStateOf<List<DwViewerDto>?>(null) }
    var selection by remember(workshopId) { mutableStateOf(DwViewerSelection()) }

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

        val detail = runCatching { repository.designWorkshop(remoteId) }
        detail.onSuccess {
            title = it.title
            creatorId = it.createdById.orEmpty()
        }.onFailure { error ->
            loadError = error.viewerFailure(DwViewerAttempt.READ, readerIsAdmin)
            loading = false
            return@LaunchedEffect
        }

        val term = dwViewerSearchTerm(query)
        runCatching { repository.eligibleDesignWorkshopViewers(term) }
            .onSuccess { answered ->
                offer = answered
                seenEligible = answered.users.associateBy { it.id }
                searchError = null
                featureMissing = false
            }
            .onFailure { error ->
                val status = (error as? HttpException)?.code()
                if (dwViewerAdministrationMissing(status)) {
                    featureMissing = true
                    offer = DwEligibleViewers()
                    loading = false
                    return@LaunchedEffect
                }
                offer = DwEligibleViewers()
                loadError = error.viewerFailure(DwViewerAttempt.READ, readerIsAdmin)
                loading = false
                return@LaunchedEffect
            }

        runCatching { repository.designWorkshopViewers(remoteId) }
            .onSuccess { served ->
                rows = served
                selection = DwViewerSelection.adopt(served, creatorId.orEmpty())
            }
            .onFailure { error ->
                rows = null
                if (loadError == null) loadError = error.viewerFailure(DwViewerAttempt.READ, readerIsAdmin)
            }
        loading = false
    }

    LaunchedEffect(query) {
        val term = dwViewerSearchTerm(query)
        if (rows == null || term == offer.search) return@LaunchedEffect
        if (term != null) delay(SEARCH_DEBOUNCE_MS)
        searching = true
        val answered = runCatching { repository.eligibleDesignWorkshopViewers(term) }
        searching = false
        answered
            .onSuccess { served ->
                offer = served
                seenEligible = seenEligible + served.users.associateBy { it.id }
                searchError = null
            }
            .onFailure { error ->
                if (error is CancellationException) return@onFailure
                searchError = error.viewerFailure(DwViewerAttempt.READ, readerIsAdmin)
            }
    }

    fun save() {
        if (!mayAdministerViewers(repository.cachedUser())) {
            saveError = "Only an admin can change who opens a workshop."
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
            runCatching { repository.setDesignWorkshopViewers(remoteId, selection.payload()) }
                .onSuccess { served ->
                    rows = served
                    selection = DwViewerSelection.adopt(served, creatorId.orEmpty())
                    val count = selection.baseline.size
                    onMessage(
                        if (count == 0) {
                            "Only the creator can open this workshop."
                        } else {
                            "$count more designer${if (count == 1) "" else "s"} can open this workshop."
                        }
                    )
                }
                .onFailure { error -> saveError = error.viewerFailure(DwViewerAttempt.SAVE, readerIsAdmin) }
            saving = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Who can open this workshop",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        if (title.isNotBlank()) {
            Text(title, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }

        if (!canAdminister) {
            Notice(
                "Only admins can change access. Ask one to add your co-designer.",
                warning = true
            )
            return@Column
        }

        Text(
            "Besides the creator, only designers named here can open this workshop.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            "Needs a connection.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        if (!onServer) {
            Notice(
                "Upload this workshop from the list first.",
                warning = true
            )
            return@Column
        }

        if (featureMissing) {
            return@Column
        }

        loadError?.let { Notice(it, warning = false) }

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

        val creator = creatorId.orEmpty()
        val retained = remember(seenEligible, offer, selection.selected) {
            val answered = offer.users.mapTo(HashSet()) { it.id }
            seenEligible.values.filter { it.id !in answered && it.id in selection.selected }
        }
        val choices = remember(offer, retained, served, creator, viewer?.id) {
            dwViewerChoices(
                eligible = offer.users,
                viewers = served,
                creatorId = creator,
                readerId = viewer?.id,
                eligibleListComplete = offer.complete,
                retained = retained,
            )
        }

        CreatorCard(creatorId = creator, rows = served, eligible = seenEligible.values.toList())

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
            dwViewerOfferNotice(offer)?.let {
                Text(
                    it,
                    color = if (offer.truncated) MaterialTheme.field.warning else MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        searchError?.let { Notice(it, warning = false) }

        SearchableMultiSelectField(
            label = "Designers who may see this workshop",
            options = choices.map { it.asOption() },
            selected = selection.selected,
            placeholder = "Select one or more designers",
            emptyMessage = if (offer.search != null) {
                "No eligible account matches that search."
            } else {
                "No eligible designers."
            },
            enabled = !saving,
            onSelectedChange = { picked -> selection = selection.withSelection(picked) }
        )
        Text(
            "Unticking removes access when you save.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        val granted = served.filter { it.userId != creator }
        if (granted.isEmpty()) {
            Text(
                "Only the creator and admins can open this workshop.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            granted.forEach { row ->
                GrantedRow(row = row, willBeRemoved = row.userId in selection.removed)
            }
        }

        val added = choices.filter { it.userId in selection.added }
        if (added.isNotEmpty()) {
            Text(
                "Will be added on save: " + added.joinToString(", ") { dwPersonLabel(it.name, it.email) } + ".",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (selection.overLimit) {
            Notice(
                "Max $DW_VIEWER_LIMIT designers. Remove ${selection.payload().size - DW_VIEWER_LIMIT} to save.",
                warning = true
            )
        }

        saveError?.let { Notice(it, warning = false) }

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
                "$pending unsaved change${if (pending == 1) "" else "s"} · ${selection.resultingCount} designer${if (selection.resultingCount == 1) "" else "s"} with access.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

internal fun mayAdministerViewers(user: UserDto?): Boolean =
    user != null && FieldPermissions.isAdmin(user)

private fun Throwable.viewerFailure(attempt: DwViewerAttempt, readerIsAdmin: Boolean): String {
    val status = (this as? HttpException)?.code()
        ?: return dwViewerFailureMessage(null, null, attempt, readerIsAdmin = readerIsAdmin)
    val said = apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
    return dwViewerFailureMessage(status, said, attempt, readerIsAdmin = readerIsAdmin)
}

private fun DwViewerChoice.asOption(): SelectOption = SelectOption(
    value = userId,
    label = dwPersonLabel(name, email),
    hint = listOfNotNull(
        email.takeIf { it.isNotBlank() },
        FieldPermissions.label(role).takeIf { it.isNotBlank() },
        "has access, no longer eligible".takeIf { grantedButIneligible },
    ).joinToString(" · ").takeIf { it.isNotBlank() }
)

@Composable
private fun CreatorCard(
    creatorId: String,
    rows: List<DwViewerDto>,
    eligible: List<DwEligibleViewerDto>,
) {
    val named = rows.firstOrNull { it.userId == creatorId }?.let { dwPersonLabel(it.name, it.email) to it.role }
        ?: eligible.firstOrNull { it.id == creatorId }?.let { dwPersonLabel(it.name, it.email) to it.role }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "ALWAYS HAS ACCESS",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                named?.first ?: "The designer who created it",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            named?.second?.takeIf { it.isNotBlank() }?.let {
                Text(FieldPermissions.label(it), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Creator — always has access.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (creatorId.isBlank()) {
                Text(
                    "Creator unknown; they always have access regardless of this list.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun GrantedRow(row: DwViewerDto, willBeRemoved: Boolean) {
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
                row.grantedAt?.takeIf { it.isNotBlank() }?.let { "added ${it.take(10)}" },
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (willBeRemoved) {
            Text(
                "will be removed on save",
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
private fun Notice(text: String, warning: Boolean) {
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
