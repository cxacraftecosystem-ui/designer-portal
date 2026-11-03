package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface InstitutionVocabulary {
    data object Loading : InstitutionVocabulary

    data class Failed(val online: Boolean) : InstitutionVocabulary

    data class Listed(val names: List<String>, val truncated: Boolean?) : InstitutionVocabulary
}

fun institutionEmptyLine(state: InstitutionVocabulary): String? = when (state) {
    InstitutionVocabulary.Loading -> loadingListLine("institutions")
    is InstitutionVocabulary.Failed ->
        if (state.online) couldNotListLine("institutions") else offlineListLine("institutions")
    is InstitutionVocabulary.Listed ->
        if (state.names.isEmpty()) unscopedEmptyLine("institutions") else null
}

@Composable
fun RosterFilterButton(kind: RosterKind, filters: RosterFilters, onOpen: () -> Unit) {
    val hidden = sheetFilterCount(kind, filters)
    val text = if (hidden > 0) "${RosterLabels.FILTERS} · $hidden" else RosterLabels.FILTERS
    FilterChip(
        selected = hidden > 0,
        onClick = onOpen,
        label = { Text(text) },
        leadingIcon = {
            Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        modifier = Modifier.semantics {
            contentDescription = if (hidden > 0) {
                "Filters, $hidden set. Opens filters."
            } else {
                "Filters. Opens the filter and order sheet."
            }
        }
    )
}

@Composable
fun RosterSortButton(kind: RosterKind, filters: RosterFilters, onOpen: () -> Unit) {
    val spec = rosterSortSpec(kind, filters.sort)
    val phrase = spec?.let { sortDirectionPhrase(it.values, filters.dir) }
    val label = spec?.label ?: filters.sort
    FilterChip(
        selected = filters.sort != ROSTER_DEFAULT_SORT || filters.dir != ROSTER_DEFAULT_DIR,
        onClick = onOpen,
        label = { Text(if (phrase != null) "$label · $phrase" else label) },
        leadingIcon = {
            Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        modifier = Modifier.semantics {
            contentDescription = if (phrase != null) {
                "${RosterLabels.SORT}: $label, $phrase. Opens filters."
            } else {
                "${RosterLabels.SORT}: $label. Opens filters."
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterFilterSheet(
    kind: RosterKind,
    filters: RosterFilters,
    onChange: (RosterFilters) -> Unit,
    onDismiss: () -> Unit,
    institutions: InstitutionVocabulary = InstitutionVocabulary.Loading,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val access = kind == RosterKind.ACCESS
    val institutionNames = (institutions as? InstitutionVocabulary.Listed)?.names.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    RosterLabels.FILTERS,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (hasActiveRosterFilters(kind, filters)) {
                    TextButton(onClick = { onChange(clearRosterFilters(kind, filters)) }) {
                        Text(RosterLabels.CLEAR_ALL)
                    }
                }
            }

            SearchableMultiSelectField(
                label = if (access) RosterLabels.ACCESS_ROLES else RosterLabels.DESIGNER_ROLES,
                options = roleOptions(kind),
                selected = filters.roles,
                placeholder = "Every tier",
                searchable = false,
                bulk = false,
                onSelectedChange = { picked -> onChange(filters.copy(roles = picked)) }
            )
            RosterHint(
                if (access) {
                    "None ticked means every tier. The last row is the default tier."
                } else {
                    DESIGNER_ROLE_HINT
                }
            )

            if (access) {
                RosterHint(ACCESS_INSTITUTION_NOTE)
            } else {
                val emptyLine = institutionEmptyLine(institutions)
                SearchableMultiSelectField(
                    label = RosterLabels.DESIGNER_INSTITUTIONS,
                    options = institutionOptions(institutionNames),
                    selected = filters.institutions,
                    placeholder = "Every institution",
                    emptyMessage = emptyLine ?: "No institutions are recorded on the roster.",
                    searchable = true,
                    bulk = false,
                    onSelectedChange = { picked -> onChange(filters.copy(institutions = picked)) }
                )
                emptyLine?.let { RosterHint(it) }
                (institutions as? InstitutionVocabulary.Listed)?.let { listed ->
                    institutionCutNotice(listed.truncated, listed.names.size)?.let { RosterNotice(it) }
                }
            }

            SearchableSelectField(
                label = RosterLabels.DATE_FIELD,
                options = dateFieldOptions(kind),
                selectedValue = filters.dateField,
                includeNone = false,
                searchable = false,
                onSelect = { picked -> onChange(filters.copy(dateField = picked)) }
            )
            SearchableSelectField(
                label = RosterLabels.DATE_PERIOD,
                options = RANGE_OPTIONS,
                selectedValue = filters.range.id,
                includeNone = false,
                searchable = false,
                onSelect = { picked ->
                    val next = RosterRange.entries.firstOrNull { it.id == picked } ?: RosterRange.ANY
                    onChange(filters.copy(range = next))
                }
            )
            if (filters.range == RosterRange.CUSTOM) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        RosterLabels.DATE_RANGE,
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    CalendarField(
                        label = RosterLabels.DATE_FROM,
                        value = filters.from,
                        onValueChange = { picked -> onChange(filters.copy(from = picked)) },
                        maximum = filters.to,
                        placeholder = "Any date",
                        clearable = true
                    )
                    CalendarField(
                        label = RosterLabels.DATE_TO,
                        value = filters.to,
                        onValueChange = { picked -> onChange(filters.copy(to = picked)) },
                        minimum = filters.from,
                        placeholder = "Any date",
                        clearable = true
                    )
                }
                RosterHint(
                    "Either box can be left empty."
                )
            }

            SearchableSelectField(
                label = RosterLabels.SORT,
                options = sortOptions(kind, filters),
                selectedValue = filters.sort,
                includeNone = false,
                searchable = false,
                onSelect = { picked -> onChange(nextRosterSort(kind, filters, picked)) }
            )
            RosterHint(
                "Choosing the current order reverses it."
            )

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessStatusChips(selected: Set<String>, onSelect: (Set<String>) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = { onSelect(emptySet()) },
            label = { Text("Everyone") },
            modifier = Modifier.semantics {
                contentDescription = if (selected.isEmpty()) {
                    "Everyone, selected. All standings shown."
                } else {
                    "Everyone. Shows all standings."
                }
            }
        )
        ACCESS_STATUS_OPTIONS.forEach { option ->
            val on = option.value in selected
            FilterChip(
                selected = on,
                onClick = { onSelect(toggledAccessStatus(selected, option.value)) },
                label = { Text(option.label) },
                modifier = Modifier.semantics {
                    contentDescription = "${RosterLabels.ACCESS_STATUS}: ${option.label}, " +
                        (if (on) "ticked. Untick to widen the list." else "not ticked. Tick to narrow to it.")
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignerStandingChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        DESIGNER_STANDING_OPTIONS.forEach { option ->
            val on = selected == option.value
            FilterChip(
                selected = on,
                onClick = { onSelect(option.value) },
                label = { Text(option.label) },
                modifier = Modifier.semantics {
                    contentDescription = "${RosterLabels.DESIGNER_STANDING}: ${option.label}" +
                        (if (on) ", chosen." else ".")
                }
            )
        }
    }
}

@Composable
fun RosterNotice(text: String) {
    Text(
        text,
        color = MaterialTheme.field.onWarningContainer,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
fun RosterHint(text: String) {
    Text(text, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
}

@Composable
fun RosterLoadingRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(label, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun RosterEmptyState(title: String, body: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(
            title,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
        Text(body, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
    }
}

@Composable
fun RosterPageBar(
    page: Int,
    pages: Int,
    total: Int,
    noun: String,
    busy: Boolean,
    onPage: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = { onPage(page - 1) },
            enabled = !busy && page > 1,
            modifier = Modifier.semantics { contentDescription = "Previous page. Page $page of $pages." }
        ) { Text("Previous") }
        Text(
            "Page $page of $pages · $total $noun",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = { onPage(page + 1) },
            enabled = !busy && page < pages,
            modifier = Modifier.semantics { contentDescription = "Next page. Page $page of $pages." }
        ) { Text("Next") }
    }
}
