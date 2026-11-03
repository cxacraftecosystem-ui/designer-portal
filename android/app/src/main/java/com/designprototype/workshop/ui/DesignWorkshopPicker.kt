package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageSchemaStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopTypeOptionDto
import com.designprototype.workshop.data.loadAllottedDesignWorkshops
import com.designprototype.workshop.data.unfiledLinkReason
import kotlinx.coroutines.CancellationException

internal const val DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY: String = "DESIGN_PROTOTYPE_DEVELOPMENT"

internal fun workshopTypeFloor(schema: SchemaResponse?): List<WorkshopTypeOptionDto> =
    workshopKindOptions(schema).mapIndexed { index, option ->
        WorkshopTypeOptionDto(
            id = "floor:${option.value}",
            key = option.value,
            label = option.label,
            sortOrder = (index + 1) * 10,
            isActive = true,
            routesToDesignWorkshop = option.value == DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY,
        )
    }

internal fun sortWorkshopTypes(types: List<WorkshopTypeOptionDto>): List<WorkshopTypeOptionDto> =
    types.sortedWith(compareBy({ it.sortOrder }, { it.key }))

internal fun firstTypeRouting(
    types: List<WorkshopTypeOptionDto>,
    toDesignWorkshop: Boolean,
): WorkshopTypeOptionDto? =
    sortWorkshopTypes(types.filter { it.isActive }).firstOrNull { it.routesToDesignWorkshop == toDesignWorkshop }

internal fun defaultWorkshopType(
    types: List<WorkshopTypeOptionDto>,
    preferDesignWorkshops: Boolean,
): WorkshopTypeOptionDto? {
    val ordered = sortWorkshopTypes(types.filter { it.isActive })
    if (preferDesignWorkshops) {
        ordered.firstOrNull { it.routesToDesignWorkshop }?.let { return it }
    }
    return ordered.firstOrNull()
}

internal fun storedWorkshopRouting(workshopId: String?, designWorkshopId: String?): Boolean? = when {
    !designWorkshopId.isNullOrBlank() -> true
    !workshopId.isNullOrBlank() -> false
    else -> null
}

internal fun openingWorkshopTypeKey(
    types: List<WorkshopTypeOptionDto>,
    storedRouting: Boolean?,
    preferDesignWorkshops: Boolean,
): String {
    if (storedRouting != null) {
        firstTypeRouting(types, storedRouting)?.let { return it.key }
        return if (storedRouting) DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY else ""
    }
    return defaultWorkshopType(types, preferDesignWorkshops)?.key.orEmpty()
}

internal fun routesToDesignWorkshopFor(
    types: List<WorkshopTypeOptionDto>,
    typeKey: String,
    storedRouting: Boolean?,
): Boolean = types.firstOrNull { it.key == typeKey }?.routesToDesignWorkshop ?: storedRouting ?: false

internal fun workshopTypeOptions(
    types: List<WorkshopTypeOptionDto>,
    currentKey: String,
    floor: List<WorkshopTypeOptionDto> = emptyList(),
): List<SelectOption> {
    val active = sortWorkshopTypes(types.filter { it.isActive })
    val rows = if (currentKey.isBlank() || active.any { it.key == currentKey }) {
        active
    } else {
        active + WorkshopTypeOptionDto(
            id = "retired:$currentKey",
            key = currentKey,
            label = floor.firstOrNull { it.key == currentKey }?.label ?: currentKey,
            sortOrder = Int.MAX_VALUE,
            isActive = false,
            routesToDesignWorkshop = floor.firstOrNull { it.key == currentKey }?.routesToDesignWorkshop ?: false,
        )
    }
    return rows.map { SelectOption(value = it.key, label = it.label) }
}

internal fun workshopTypeHint(served: Boolean): String = if (served) {
    "Narrows the list below. It is not saved on this record."
} else {
    "These are the standard types of workshop until you connect; they are not saved on this record."
}

@Stable
class WorkshopTypePickerState internal constructor(
    private val storedRouting: Boolean?,
    private val preferDesignWorkshops: Boolean,
) {
    var types by mutableStateOf<List<WorkshopTypeOptionDto>>(emptyList())
        private set

    var floor by mutableStateOf<List<WorkshopTypeOptionDto>>(emptyList())
        private set

    var served by mutableStateOf(false)
        private set

    var chosenKey by mutableStateOf<String?>(null)
        private set

    var touched by mutableStateOf(false)
        private set

    val typeKey: String
        get() = chosenKey ?: openingWorkshopTypeKey(types, storedRouting, preferDesignWorkshops)

    val routesToDesignWorkshop: Boolean
        get() = routesToDesignWorkshopFor(types, typeKey, storedRouting)

    fun choose(key: String) {
        chosenKey = key
        touched = true
    }

    internal fun applyCarriedRouting() {
        firstTypeRouting(types, toDesignWorkshop = false)?.let { chosenKey = it.key }
    }

    internal fun offer(rows: List<WorkshopTypeOptionDto>, served: Boolean) {
        if (served) {
            types = sortWorkshopTypes(rows)
            this.served = true
        } else {
            floor = rows
            if (!this.served) types = rows
        }
    }
}

@Composable
fun rememberWorkshopTypePicker(
    repository: WorkshopRepository,
    storedWorkshopId: String?,
    storedDesignWorkshopId: String?,
    preferDesignWorkshops: Boolean,
    resetKey: Any? = null,
): WorkshopTypePickerState {
    val appContext = LocalContext.current.applicationContext
    val state = remember(resetKey) {
        WorkshopTypePickerState(
            storedRouting = storedWorkshopRouting(storedWorkshopId, storedDesignWorkshopId),
            preferDesignWorkshops = preferDesignWorkshops,
        ).also { fresh -> fresh.offer(workshopTypeFloor(StageSchemaStore.peek()), served = false) }
    }

    LaunchedEffect(resetKey) {
        runCatching { workshopTypeFloor(StageSchemaStore.load(appContext)) }
            .onSuccess { rows -> if (rows.isNotEmpty()) state.offer(rows, served = false) }
            .onFailure { error -> if (error is CancellationException) throw error }
    }

    LaunchedEffect(resetKey) {
        runCatching { repository.workshopTypes() }
            .onSuccess { rows -> state.offer(rows, served = true) }
            .onFailure { error -> if (error is CancellationException) throw error }
    }

    return state
}

@Composable
internal fun WorkshopTypeField(
    state: WorkshopTypePickerState,
    saving: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val options = remember(state.types, state.typeKey, state.floor) {
        workshopTypeOptions(state.types, state.typeKey, state.floor)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "Type of workshop",
            options = options,
            selectedValue = state.typeKey,
            includeNone = false,
            enabled = !saving && listIsAnswerable(options),
            onSelect = { picked -> state.choose(picked) },
        )
        Text(
            workshopTypeHint(state.served),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 15.sp,
        )
    }
}

class DesignWorkshopPickerState(initialId: String) {
    var workshops by mutableStateOf<List<DesignWorkshopDto>>(emptyList())

    var selectedId by mutableStateOf(initialId)

    var baselineId by mutableStateOf(initialId)
        private set

    var prefillNote by mutableStateOf<String?>(null)
        private set

    var listState by mutableStateOf<WorkshopListState>(WorkshopListState.Loading)
        private set

    var online by mutableStateOf(true)
        private set

    var total by mutableStateOf(0)
        private set

    internal var cached by mutableStateOf(RegisterLoad())
        private set

    fun value(): String? = selectedId.ifBlank { null }

    fun unfiledReason(): String? = unfiledLinkReason(
        selectedId = selectedId,
        baselineId = baselineId,
        hadOptions = workshops.isNotEmpty(),
    )

    fun isDirty(): Boolean = selectedId != baselineId

    fun choose(id: String) {
        selectedId = id
        prefillNote = null
    }

    fun applyDefault(id: String, note: String?) {
        selectedId = id
        baselineId = id
        prefillNote = note
    }

    internal fun markCached(rows: List<DesignWorkshopDto>, load: RegisterLoad) {
        workshops = rows
        cached = load
    }

    internal fun markListed(page: DesignWorkshopPageDto) {
        workshops = page.items
        total = page.total
        listState = WorkshopListState.Listed(count = page.items.size, total = page.total)
    }

    internal fun markFailed(transient: Boolean) {
        online = !transient
        listState = WorkshopListState.Failed
    }
}

private const val DESIGN_WORKSHOP_PAGE = 20

@Composable
fun rememberDesignWorkshopPicker(
    repository: WorkshopRepository,
    isEdit: Boolean,
    initialId: String?,
    resetKey: Any? = null,
): DesignWorkshopPickerState {
    val state = remember(resetKey) { DesignWorkshopPickerState(initialId.orEmpty()) }
    val context = LocalContext.current

    LaunchedEffect(resetKey) {
        val half = repository.loadAllottedDesignWorkshops(
            context = context,
            fetch = { repository.designWorkshops(page = 1, pageSize = DESIGN_WORKSHOP_PAGE) },
            rowsOf = { it.items },
            onCached = { rows, load -> state.markCached(rows, load) },
        )
        val page = half.page
        if (page != null) {
            state.markListed(page)
        } else {
            state.markFailed(transient = !half.load.online)
        }
    }

    LaunchedEffect(resetKey) {
        if (!isEdit && state.selectedId.isBlank()) {
            runCatching { repository.designWorkshopDefaultForMe() }
                .onSuccess { answer ->
                    val id = answer.workshopId
                    if (!id.isNullOrBlank()) {
                        state.applyDefault(id, designWorkshopPrefillNote(answer.reason, answer.accessAt))
                    }
                }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }
    return state
}

internal fun designWorkshopPrefillNote(reason: String?, accessAt: String?): String? {
    val day = formatIsoDay(accessAt)
    val tail = if (day == null) "" else " on $day"
    return when (reason) {
        "GRANTED" ->
            "Your latest assigned workshop$tail. Change if needed."
        "CREATED" ->
            "Your latest opened workshop$tail. Change if needed."
        else -> null
    }
}

private fun formatIsoDay(iso: String?): String? {
    val text = iso?.takeIf { it.length >= 10 } ?: return null
    val day = text.substring(0, 10)
    return if (day.getOrNull(4) == '-' && day.getOrNull(7) == '-') day else null
}

@Composable
fun DesignWorkshopField(
    state: DesignWorkshopPickerState,
    saving: Boolean = false,
    label: String = "Workshop",
    noneLabel: String = NO_FIELD_WORKSHOP,
    modifier: Modifier = Modifier,
) {
    val options = remember(state.workshops, state.selectedId) {
        designWorkshopOptions(rows = state.workshops, offPageId = state.selectedId)
    }

    val notice = workshopListNotice(
        state = state.listState,
        kind = WorkshopListKind.DESIGN,
        online = state.online,
        cached = state.cached,
        cachedRows = state.workshops.size,
    )

    val enabled = !saving && listIsAnswerable(options)

    val standDown = options.isEmpty() && !enabled

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = label,
            options = options,
            selectedValue = state.selectedId,
            placeholder = noneLabel,
            includeNone = true,
            enabled = enabled,
            searchable = false,
            emptyMessage = notice,
            onSelect = { state.choose(it) },
        )
        state.prefillNote?.let { note ->
            Text(note, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
        }
        Text(
            "Files this record under a design workshop. Doesn't change who can read it.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 15.sp,
        )
        workshopCapLine(state.workshops.size, state.total, WorkshopListKind.DESIGN)?.let { cap ->
            Text(cap, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
        }
        if (!standDown) {
            notice?.let { line ->
                Text(line, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
            }
        }
    }
}
