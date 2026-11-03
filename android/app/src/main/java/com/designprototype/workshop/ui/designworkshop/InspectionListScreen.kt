package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.openable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DwInspectionAttempt
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.canInspectDesignWorkshops
import com.designprototype.workshop.data.dwInspectorSearchTerm
import com.designprototype.workshop.data.workshopDisplayTitle
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.designWorkshopStatusLabel
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val INSPECTION_SEARCH_DEBOUNCE_MS = 350L

private const val INSPECTION_PAGE_SIZE = 20

const val DW_INSPECTION_LIST_TITLE = "Workshops to inspect"

@Composable
fun InspectionListScreen(
    repository: WorkshopRepository,
    onOpenWorkshop: (workshopId: String) -> Unit,
) {
    val viewer = remember(repository) { repository.cachedUser() }
    val mayInspect = remember(viewer) { viewer != null && canInspectDesignWorkshops(viewer.role) }

    var page by remember { mutableIntStateOf(1) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    var rows by remember { mutableStateOf<List<DesignWorkshopDto>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(mayInspect) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var answeredFor by remember { mutableStateOf<String?>(null) }
    var savedAt by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    LaunchedEffect(page, reload, query, mayInspect) {
        if (!mayInspect) {
            loading = false
            return@LaunchedEffect
        }
        val term = dwInspectorSearchTerm(query)
        if (term != null && term != answeredFor) delay(INSPECTION_SEARCH_DEBOUNCE_MS)
        loading = true
        loadError = null
        runCatching {
            repository.readInspectableWorkshops(
                context = appContext,
                page = page,
                pageSize = INSPECTION_PAGE_SIZE,
                search = term
            )
        }
            .onSuccess { (answered, kept) ->
                rows = answered.items
                total = answered.total
                pages = answered.pages
                answeredFor = term
                savedAt = kept
            }
            .onFailure { error ->
                if (error is CancellationException) return@onFailure
                loadError = error.inspectionFailure(DwInspectionAttempt.READ)
            }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            DW_INSPECTION_LIST_TITLE,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )

        if (!mayInspect) {
            InspectionNotice(
                "For Inspector / Reviewer accounts. Shows workshops assigned to you.",
                warning = true
            )
            return@Column
        }

        Text(
            "Workshops assigned to you to inspect (read-only).",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        savedAt?.let {
            InspectionNotice(
                "Offline — list from ${it.take(10)}. Suggestions send when you're back online.",
                warning = true
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it; page = 1 },
            label = { Text("Search these workshops") },
            singleLine = true,
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
                    loading -> FieldCircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    query.isNotEmpty() -> IconButton(onClick = { query = ""; page = 1 }) {
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

        loadError?.let { InspectionNotice(it, warning = false) }

        val served = rows
        when {
            served == null && loadError != null -> OutlinedButton(
                onClick = { reload++ },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Try again") }

            served == null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            served.isEmpty() && answeredFor != null -> {
                Text(
                    "No matching workshops",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Clear the search to see all.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            served.isEmpty() -> {
                Text(
                    "No workshop is assigned to you",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Workshops an admin assigns to you appear here.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            else -> {
                Text(
                    "$total ${if (total == 1) "workshop" else "workshops"} assigned to you" +
                        if (answeredFor != null) ", matching this search." else ".",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                served.forEach { workshop ->
                    InspectableWorkshopCard(
                        workshop = workshop,
                        onOpen = { onOpenWorkshop(workshop.id) }
                    )
                }
                if (pages > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { page-- },
                            enabled = page > 1 && !loading,
                            modifier = Modifier.weight(1f)
                        ) { Text("Previous") }
                        Text(
                            "Page $page of $pages",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { page++ },
                            enabled = page < pages && !loading,
                            modifier = Modifier.weight(1f)
                        ) { Text("Next") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InspectableWorkshopCard(workshop: DesignWorkshopDto, onOpen: () -> Unit) {
    val shownTitle = workshopDisplayTitle(
        workshop.title,
        date = workshop.startDate,
        workshopCode = workshop.workshopCode,
        clusterName = workshop.clusterName,
        district = workshop.district,
        state = workshop.state,
        craftName = workshop.craftName,
    )
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .openable(shownTitle, RoundedCornerShape(12.dp), onOpen = onOpen)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                shownTitle,
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val line = listOfNotNull(
                workshop.craftName?.takeIf { it.isNotBlank() },
                workshop.clusterName?.takeIf { it.isNotBlank() },
                listOfNotNull(
                    workshop.district?.takeIf { it.isNotBlank() },
                    workshop.state?.takeIf { it.isNotBlank() },
                ).joinToString(", ").takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (line.isNotBlank()) {
                Text(line, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
            }
            val second = listOfNotNull(
                workshop.workshopCode?.takeIf { it.isNotBlank() },
                workshop.status.takeIf { it.isNotBlank() }?.let { designWorkshopStatusLabel(it, workshop.handedOnAt) },
                listOfNotNull(
                    workshop.startDate?.takeIf { it.isNotBlank() }?.take(10),
                    workshop.endDate?.takeIf { it.isNotBlank() }?.take(10),
                ).joinToString(" – ").takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (second.isNotBlank()) {
                Text(second, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Read-only",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
