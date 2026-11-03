package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwDecodeResult
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DW_REFERENCE_PAGE
import com.designprototype.workshop.data.DwReferenceList
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.DwReferenceResponseDto
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.DwWorkshopCodeRef
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.decodeWorkshopCode
import com.designprototype.workshop.data.ArtisanCardCode
import com.designprototype.workshop.data.CARD_REMOTE
import com.designprototype.workshop.data.CARD_REPLACED
import com.designprototype.workshop.data.CardResolution
import com.designprototype.workshop.data.DwEncodeResult
import com.designprototype.workshop.data.encodeWorkshopCode
import com.designprototype.workshop.ui.resolveScannedCard
import com.designprototype.workshop.data.dwRefId
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.DwQrLiveScanControl
import com.designprototype.workshop.ui.DwQrScanControl
import com.designprototype.workshop.ui.RecordOpen
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.CraftPickerField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectCreateAction
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.selectOptionsMatch
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Immutable
data class DwInlineRecordOutcome(
    val saved: Boolean,
    val createdId: String = "",
    val createdLabel: String = "",
    val createdHint: String = "",
)

@Immutable
class DwInlineRecordHost(
    val open: (
        model: String,
        recordId: String?,
        seed: DwInlineSeed,
        onFinished: (DwInlineRecordOutcome) -> Unit,
    ) -> Unit,
)

@Immutable
data class DwInlineSeed(
    val artisanId: String = "",
    val artisanName: String = "",
) {
    val isEmpty: Boolean get() = artisanId.isBlank() && artisanName.isBlank()

    companion object {
        val NONE = DwInlineSeed()
    }
}

private val INLINE_CREATABLE: Map<String, String> = mapOf(
    "Artisan" to "artisan",
    "ProductDocumentation" to "product",
    "ToolDocumentation" to "tool",
    "Process" to "process",
)

internal const val DW_CRAFT_REGISTER_NOTE =
    "Not on the list? Add it from “Add craft” in the menu (Professor and above), or ask an admin to add it."

@Immutable
class DwReferenceBridge(
    val repository: WorkshopRepository,
    val workshopId: String?,
    val inlineRecords: DwInlineRecordHost? = null,
)

@Composable
internal fun DwReferenceSelectField(
    field: FieldDto,
    value: JsonElement?,
    parentField: FieldDto?,
    parentValue: JsonElement?,
    bridge: DwReferenceBridge?,
    enabled: Boolean,
    error: String?,
    label: String,
    rowValues: Map<String, JsonElement>,
    writableFields: Map<String, FieldDto>,
    onChange: (JsonElement?) -> Unit,
    onHydrate: (Map<String, JsonElement?>) -> Unit,
) {
    val context = LocalContext.current
    val selectedId = remember(value) { dwRefId(value) }
    val parentId = remember(parentValue) { dwRefId(parentValue) }
    val needsParent = field.refFilterBy.isNotBlank() && parentId.isBlank()

    var list by remember(field.key) { mutableStateOf<DwReferenceList?>(null) }
    var truncated by remember(field.key) { mutableStateOf(false) }
    var lastHydration by remember(field.key) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }

    var locallyCreated by remember(field.key) { mutableStateOf<List<DwReferenceOption>>(emptyList()) }
    var refreshTick by remember(field.key) { mutableIntStateOf(0) }
    var pendingHydration by remember(field.key) { mutableStateOf("") }
    var scannedOption by remember(field.key) { mutableStateOf<DwScannedAside?>(null) }

    LaunchedEffect(field.refModel, field.refScope, parentId, bridge?.workshopId, needsParent, refreshTick) {
        if (bridge == null || field.refModel.isBlank() || needsParent) return@LaunchedEffect
        bridge.repository.designWorkshopReferences(
            context = context,
            workshopId = bridge.workshopId,
            model = field.refModel,
            scope = field.refScope,
            filterValue = parentId,
        ) { fetched, wasTruncated ->
            list = fetched
            truncated = wasTruncated
        }
    }

    var cascadeCleared by remember(field.key) { mutableStateOf(false) }

    val lastParentId = remember(field.key) { mutableStateOf(parentId) }

    LaunchedEffect(parentId) {
        val moved = lastParentId.value != parentId
        lastParentId.value = parentId
        if (!dwCascadeClearsChild(field.refFilterBy, moved, selectedId)) return@LaunchedEffect
        onChange(null)
        pendingHydration = ""
        cascadeCleared = true
    }

    LaunchedEffect(list, pendingHydration) {
        val awaited = pendingHydration
        if (awaited.isBlank()) return@LaunchedEffect
        val option = list?.items?.firstOrNull { it.id == awaited } ?: return@LaunchedEffect
        val incoming = hydratedValues(option, field.refHydration, writableFields)
        val patch = hydrationPatch(incoming, lastHydration, rowValues, writableFields)
        lastHydration = incoming
        pendingHydration = ""
        if (patch.isNotEmpty()) onHydrate(patch)
    }

    val options = remember(list, parentId, selectedId, locallyCreated, scannedOption) {
        val narrowed = list?.narrowedTo(parentId).orEmpty()
        val known = narrowed.map { it.id }.toSet()
        val scanned = scannedOption?.takeIf { it.underParent == parentId }?.option
        val aside = (listOfNotNull(scanned) + locallyCreated).distinctBy { it.id }
        buildReferenceOptions(
            aside.filterNot { it.id in known } + narrowed,
            selectedId,
            tentativeWord = list?.tentativeLabel.orEmpty(),
        )
    }

    var searchTerm by remember(field.key) { mutableStateOf("") }
    DwReferenceSearchOnMiss(
        term = searchTerm,
        options = options,
        field = field,
        bridge = bridge,
        parentId = parentId,
        active = !needsParent,
        onList = { list = it },
    )

    val noun = INLINE_CREATABLE[field.refModel]
    val inlineHost = bridge?.inlineRecords?.takeIf { noun != null && enabled }

    fun inlineSeed(): DwInlineSeed {
        if (parentField?.refModel != "Artisan" || parentId.isBlank()) return DwInlineSeed.NONE
        val nameKey = parentField.refHydration.entries.firstOrNull { it.key == "name" }?.value
        val name = nameKey?.let { DwValues.text(rowValues[it]).trim() }.orEmpty()
        return DwInlineSeed(artisanId = parentId, artisanName = name)
    }

    fun openInlineRecord(recordId: String?) {
        val host = inlineHost ?: return
        host.open(field.refModel, recordId, if (recordId == null) inlineSeed() else DwInlineSeed.NONE) { outcome ->
            if (!outcome.saved) return@open
            refreshTick++
            val newId = outcome.createdId
            if (newId.isBlank()) return@open
            locallyCreated = listOf(
                DwReferenceOption(
                    id = newId,
                    label = outcome.createdLabel,
                    hint = outcome.createdHint,
                    filterValue = "",
                )
            ) + locallyCreated.filterNot { it.id == newId }
            onChange(JsonPrimitive(newId))
            pendingHydration = newId
        }
    }

    fun choose(chosen: String, known: DwReferenceOption? = null) {
        cascadeCleared = false
        if (chosen.isBlank()) {
            onChange(null)
            pendingHydration = ""
            return
        }
        onChange(JsonPrimitive(chosen))
        val option = known ?: list?.items?.firstOrNull { it.id == chosen }
        if (option == null) {
            pendingHydration = chosen
            return
        }
        pendingHydration = ""
        val incoming = hydratedValues(option, field.refHydration, writableFields)
        val patch = hydrationPatch(incoming, lastHydration, rowValues, writableFields)
        lastHydration = incoming
        if (patch.isNotEmpty()) onHydrate(patch)
    }

    val scannable = remember(field.refModel) { dwScannableRecordType(field.refModel) }
    val craftScope = rememberCoroutineScope()
    val currentValue = rememberUpdatedState(value)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldCaption(field)

        if (needsParent) {
            Text(
                "Choose ${parentField?.label ?: "the field above"} first.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (field.refModel == "Craft") {
            CraftPickerField(
                label = label.removeSuffix(" *"),
                selectedIds = listOfNotNull(selectedId.ifBlank { null }),
                enabled = enabled && bridge != null,
                required = field.required,
                fallbackNames = options.filter { it.value.isNotBlank() }.associate { it.value to it.label },
                refresh = { bridge?.repository?.craftCatalogue(context) },
            ) { ids ->
                val chosen = ids.firstOrNull().orEmpty()
                val known = list?.items?.firstOrNull { it.id == chosen }
                choose(chosen, known)
                val target = bridge?.workshopId
                if (chosen.isNotBlank() && known == null && target != null) {
                    craftScope.launch {
                        val described = runCatching {
                            bridge.repository.designWorkshopReferenceById(
                                workshopId = target,
                                model = field.refModel,
                                scope = field.refScope,
                                filterValue = "",
                                recordId = chosen,
                            ).options.firstOrNull { it.id == chosen }
                        }.getOrNull()
                        if (described != null && dwRefId(currentValue.value) == chosen) choose(chosen, described)
                    }
                }
            }
        } else SearchableSelectField(
            label = label,
            options = options,
            selectedValue = selectedId,
            enabled = enabled && !needsParent && bridge != null,
            includeNone = true,
            placeholder = when {
                needsParent -> "Waiting for ${parentField?.label ?: "the field above"}"
                options.isEmpty() -> "No records on this device yet"
                else -> "Search and choose"
            },
            createAction = if (noun != null && inlineHost != null && !needsParent) {
                SelectCreateAction("Create a new $noun") { openInlineRecord(null) }
            } else {
                null
            },
            onQueryChange = { searchTerm = it },
            onSelect = { chosen -> choose(chosen) }
        )

        if (cascadeCleared) {
            Text(
                dwCascadeClearedMessage(parentField?.label.orEmpty()),
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        if (scannable != null && bridge != null && enabled && !needsParent) {
            DwReferenceScanPanel(
                field = field,
                bridge = bridge,
                wanted = scannable,
                list = list,
                parentId = parentId,
                cascadeLabel = if (field.refFilterBy.isNotBlank()) parentField?.label.orEmpty() else "",
                enabled = enabled,
                onPick = { option ->
                    scannedOption = DwScannedAside(option, parentId)
                    choose(option.id, option)
                },
            )
        }

        if (noun != null && inlineHost != null && selectedId.isNotBlank()) {
            ActionRow {
                TextButton(
                    onClick = { openInlineRecord(selectedId) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("${RecordOpen.UPDATE_LABEL} this $noun", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (field.refModel == "Craft" && selectedId.isBlank()) {
            Text(
                DW_CRAFT_REGISTER_NOTE,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        ReferenceProvenance(list = list, truncated = truncated, bridge = bridge, visible = !needsParent)
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun DwReferenceMultiSelectField(
    field: FieldDto,
    value: JsonElement?,
    parentField: FieldDto?,
    parentValue: JsonElement?,
    bridge: DwReferenceBridge?,
    enabled: Boolean,
    error: String?,
    label: String,
    onChange: (JsonElement?) -> Unit,
) {
    val context = LocalContext.current
    val selected = remember(value) { DwValues.list(value).toSet() }
    val parentId = remember(parentValue) { dwRefId(parentValue) }
    val needsParent = field.refFilterBy.isNotBlank() && parentId.isBlank()

    var list by remember(field.key) { mutableStateOf<DwReferenceList?>(null) }
    var truncated by remember(field.key) { mutableStateOf(false) }
    var locallyCreated by remember(field.key) { mutableStateOf<List<DwReferenceOption>>(emptyList()) }
    var refreshTick by remember(field.key) { mutableIntStateOf(0) }

    LaunchedEffect(field.refModel, field.refScope, parentId, bridge?.workshopId, needsParent, refreshTick) {
        if (bridge == null || field.refModel.isBlank() || needsParent) return@LaunchedEffect
        bridge.repository.designWorkshopReferences(
            context = context,
            workshopId = bridge.workshopId,
            model = field.refModel,
            scope = field.refScope,
            filterValue = parentId,
        ) { fetched, wasTruncated ->
            list = fetched
            truncated = wasTruncated
        }
    }

    val options = remember(list, parentId, selected, locallyCreated) {
        val fetchedIds = list?.items.orEmpty().map { it.id }.toSet()
        val narrowed = locallyCreated.filterNot { it.id in fetchedIds } + list?.narrowedTo(parentId).orEmpty()
        val known = narrowed.map { it.id }.toSet()
        buildReferenceOptions(narrowed, "", tentativeWord = list?.tentativeLabel.orEmpty()) +
            selected.filterNot { it in known }.map { held -> heldOption(held) }
    }

    var searchTerm by remember(field.key) { mutableStateOf("") }
    DwReferenceSearchOnMiss(
        term = searchTerm,
        options = options,
        field = field,
        bridge = bridge,
        parentId = parentId,
        active = !needsParent,
        onList = { list = it },
    )

    val noun = INLINE_CREATABLE[field.refModel]
    val inlineHost = bridge?.inlineRecords?.takeIf { noun != null && enabled && !needsParent }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldCaption(field)
        if (needsParent) {
            Text(
                "Choose ${parentField?.label ?: "the field above"} first.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        SearchableMultiSelectField(
            label = label,
            options = options,
            selected = selected,
            enabled = enabled && !needsParent && bridge != null,
            emptyMessage = if (bridge == null) {
                "Available once you can edit this workshop."
            } else {
                "Nothing here yet. Connect once and reopen this stage."
            },
            createAction = if (noun != null && inlineHost != null) {
                SelectCreateAction("Create a new $noun") {
                    inlineHost.open(field.refModel, null, DwInlineSeed.NONE) { outcome ->
                        if (!outcome.saved) return@open
                        refreshTick++
                        val newId = outcome.createdId
                        if (newId.isBlank()) return@open
                        locallyCreated = listOf(
                            DwReferenceOption(id = newId, label = outcome.createdLabel, hint = outcome.createdHint)
                        ) + locallyCreated.filterNot { it.id == newId }
                        onChange(DwValues.ofList((selected + newId).toList()))
                    }
                }
            } else {
                null
            },
            onQueryChange = { searchTerm = it },
            onSelectedChange = { chosen ->
                onChange(DwValues.ofList(options.map { it.value }.filter { it in chosen }))
            }
        )
        ReferenceProvenance(list = list, truncated = truncated, bridge = bridge, visible = !needsParent)
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DwReferenceSearchOnMiss(
    term: String,
    options: List<SelectOption>,
    field: FieldDto,
    bridge: DwReferenceBridge?,
    parentId: String,
    active: Boolean,
    onList: (DwReferenceList) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(term, options, parentId, active) {
        val query = term.trim()
        if (bridge == null || !active || query.length < 2 || field.refModel.isBlank()) return@LaunchedEffect
        if (selectOptionsMatch(options, query)) return@LaunchedEffect
        delay(DW_REFERENCE_SEARCH_DEBOUNCE_MS)
        val found = bridge.repository.designWorkshopReferenceSearch(
            workshopId = bridge.workshopId,
            model = field.refModel,
            scope = field.refScope,
            search = query,
            limit = DW_REFERENCE_PAGE,
            filterValue = parentId,
        )?.options.orEmpty()
        if (found.isEmpty()) return@LaunchedEffect
        bridge.repository.designWorkshopReferenceMerge(
            context = context,
            workshopId = bridge.workshopId,
            model = field.refModel,
            scope = field.refScope,
            filterValue = parentId,
            found = found,
        )?.let(onList)
    }
}

internal const val DW_REFERENCE_SEARCH_DEBOUNCE_MS = 350L

@Composable
private fun ReferenceProvenance(
    list: DwReferenceList?,
    truncated: Boolean,
    bridge: DwReferenceBridge?,
    visible: Boolean,
) {
    if (!visible) return
    if (bridge == null) {
        Text(
            "Linking opens once you can edit this workshop.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    if (list == null) {
        Text(
            "Not downloaded yet. Connect once and reopen the stage.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    val age = friendlyAge(list.fetchedAt)
    Text(
        buildString {
            append("${list.items.size} record${if (list.items.size == 1) "" else "s"} on this device")
            if (age != null) append(", last refreshed $age")
            append(".")
            if (bridge.workshopId == null) {
                append(" Upload the workshop to refresh.")
            }
        },
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall
    )
    if (truncated) {
        Text(
            "Partly loaded. Refresh online if a record is missing.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

internal fun dwScannableRecordType(refModel: String): DwWorkshopRecordType? = when (refModel) {
    "Artisan" -> DwWorkshopRecordType.ARTISAN
    "Craft" -> DwWorkshopRecordType.CRAFT
    "ProductDocumentation" -> DwWorkshopRecordType.PRODUCT
    "ToolDocumentation" -> DwWorkshopRecordType.TOOL
    "Process" -> DwWorkshopRecordType.PROCESS
    "DwPrototype" -> DwWorkshopRecordType.PROTOTYPE
    else -> null
}

@Immutable
private data class DwScannedAside(val option: DwReferenceOption, val underParent: String)

internal sealed interface DwScanAnswer {
    data class Pick(val option: DwReferenceOption) : DwScanAnswer

    data class Refused(val message: String) : DwScanAnswer
}

internal sealed interface DwScanStep {
    data class Settled(val answer: DwScanAnswer) : DwScanStep

    data class AskServer(val ref: DwWorkshopCodeRef) : DwScanStep
}

private fun article(noun: String): String =
    if (noun.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

internal fun dwPickerNoun(field: FieldDto): String =
    dwScannableRecordType(field.refModel)?.label?.lowercase() ?: field.label.lowercase()

internal fun dwWrongRecordTypeMessage(field: FieldDto, ref: DwWorkshopCodeRef): String? {
    val wanted = dwScannableRecordType(field.refModel)
    if (wanted == ref.recordType) return null
    val scanned = ref.recordType.label.lowercase()
    if (wanted == null) {
        return "That code is ${article(scanned)} $scanned; rows in “${field.label}” carry no printed code. Choose from the list."
    }
    val noun = dwPickerNoun(field)
    return "That code is ${article(scanned)} $scanned, but “${field.label}” needs ${article(noun)} $noun."
}

internal fun dwReferenceOutOfScopeMessage(option: DwReferenceOption): String =
    "“${option.label}” belongs to another workshop. Nothing on this row has been changed. Link that record to this workshop, or choose another."

internal fun dwUnresolvedScanMessage(field: FieldDto, cascadeLabel: String): String {
    val noun = dwPickerNoun(field)
    val reasons = listOfNotNull(
        "it may not have been recorded",
        "it may belong to work this account cannot open",
        cascadeLabel.takeIf { it.isNotBlank() }?.let { "it may not belong to the ${it.lowercase()} chosen on this row" },
    )
    return "No $noun this box can offer matches — ${reasons.joinToString(", or ")}. Nothing on this row has been changed; search by name instead."
}

internal const val DW_SCAN_OFFLINE_MESSAGE =
    "There's no connection, so the code couldn't be looked up (it's valid). Nothing on this row has been changed."

internal const val DW_SCAN_LOOKUP_FAILED_MESSAGE =
    "Couldn't look up the code. Nothing on this row has been changed — try again or search by name."

internal const val DW_SCAN_UNSENT_WORKSHOP_MESSAGE =
    "Workshop not uploaded, so only local records can match — this isn't one. Nothing on this row has been changed."

internal fun dwCascadeClearsChild(
    refFilterBy: String,
    parentMoved: Boolean,
    selectedId: String,
): Boolean = refFilterBy.isNotBlank() && parentMoved && selectedId.isNotBlank()

internal fun dwCascadeClearedMessage(cascadeLabel: String): String {
    val moved = cascadeLabel.takeIf { it.isNotBlank() }
        ?.let { "The ${it.lowercase()} on this row" }
        ?: "The record this list narrows to"
    return "$moved changed, so the previous choice is cleared when the row is saved. Pick again."
}

internal fun dwScanCascadeMovedMessage(cascadeLabel: String): String {
    val moved = cascadeLabel.takeIf { it.isNotBlank() }
        ?.let { "The ${it.lowercase()} on this row" }
        ?: "The record this list narrows to"
    return "$moved changed during the lookup. Nothing on this row has been changed — read the code again."
}

internal fun dwScanLocalStep(
    scanned: String,
    field: FieldDto,
    list: DwReferenceList?,
    parentId: String,
): DwScanStep {
    val ref = when (val decoded = decodeWorkshopCode(scanned)) {
        is DwDecodeResult.Refused -> return DwScanStep.Settled(DwScanAnswer.Refused(decoded.message))
        is DwDecodeResult.Ok -> decoded.ref
    }
    dwWrongRecordTypeMessage(field, ref)?.let {
        return DwScanStep.Settled(DwScanAnswer.Refused(it))
    }
    val cached = list?.narrowedTo(parentId)?.firstOrNull { it.id == ref.id }
    if (cached != null && dwCascadeIsProven(field, list, cached, parentId)) {
        return DwScanStep.Settled(DwScanAnswer.Pick(cached))
    }
    return DwScanStep.AskServer(ref)
}

private fun dwCascadeIsProven(
    field: FieldDto,
    list: DwReferenceList,
    option: DwReferenceOption,
    parentId: String,
): Boolean {
    if (field.refFilterBy.isBlank()) return true
    if (parentId.isBlank()) return false
    if (option.filterValue == parentId) return true
    return list.filteredBy == parentId
}

internal fun dwScanServerAnswer(
    field: FieldDto,
    ref: DwWorkshopCodeRef,
    payload: DwReferenceResponseDto,
    cascadeLabel: String,
): DwScanAnswer {
    payload.options.firstOrNull { it.id == ref.id }?.let { return DwScanAnswer.Pick(it) }
    if (ref.recordType == DwWorkshopRecordType.PROTOTYPE && !payload.truncated && payload.options.size == 1) {
        return DwScanAnswer.Pick(payload.options.first())
    }
    val excluded = payload.outOfScopeOption
    if (payload.outOfScope && excluded != null) {
        return DwScanAnswer.Refused(dwReferenceOutOfScopeMessage(excluded))
    }
    return DwScanAnswer.Refused(dwUnresolvedScanMessage(field, cascadeLabel))
}

private fun dwScanConfirmation(option: DwReferenceOption, wanted: DwWorkshopRecordType): String {
    val name = option.label.trim().takeIf { it.isNotEmpty() }
        ?: return "Linked the ${wanted.label.lowercase()} from the code."
    return "Linked from the code you read: “$name”."
}

@Composable
private fun DwReferenceScanPanel(
    field: FieldDto,
    bridge: DwReferenceBridge,
    wanted: DwWorkshopRecordType,
    list: DwReferenceList?,
    parentId: String,
    cascadeLabel: String,
    enabled: Boolean,
    onPick: (DwReferenceOption) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberUpdatedState(onPick)
    val currentList = rememberUpdatedState(list)
    val currentParent = rememberUpdatedState(parentId)

    var expanded by remember(field.key) { mutableStateOf(false) }
    var busy by remember(field.key) { mutableStateOf(false) }
    var typed by remember(field.key) { mutableStateOf("") }
    var refusal by remember(field.key) { mutableStateOf<String?>(null) }
    var confirmed by remember(field.key) { mutableStateOf<String?>(null) }

    fun settle(answer: DwScanAnswer) {
        when (answer) {
            is DwScanAnswer.Pick -> {
                confirmed = dwScanConfirmation(answer.option, wanted)
                pick.value(answer.option)
            }

            is DwScanAnswer.Refused -> refusal = answer.message
        }
    }

    fun resolve(input: String) {
        if (input.isBlank() || busy) return
        if (wanted == DwWorkshopRecordType.ARTISAN && ArtisanCardCode.looksLikeCard(input)) {
            refusal = null
            confirmed = null
            busy = true
            scope.launch {
                val answer = resolveScannedCard(context, bridge.repository, input)
                busy = false
                when (answer) {
                    is CardResolution.Found -> when (val code = encodeWorkshopCode(DwWorkshopRecordType.ARTISAN, answer.artisanId)) {
                        is DwEncodeResult.Ok -> resolve(code.code)
                        is DwEncodeResult.Refused -> refusal = code.message
                    }
                    is CardResolution.Pending -> {
                        val value = ArtisanCardCode.pendingValue(answer.card)
                        confirmed = ArtisanCardCode.pendingLabel(value)
                        pick.value(DwReferenceOption(id = value, label = answer.card.shortCode))
                    }
                    is CardResolution.Replaced -> refusal = CARD_REPLACED
                    CardResolution.Remote -> refusal = CARD_REMOTE
                    is CardResolution.Refused -> refusal = answer.message
                }
            }
            return
        }
        refusal = null
        confirmed = null
        when (val step = dwScanLocalStep(input, field, currentList.value, currentParent.value)) {
            is DwScanStep.Settled -> settle(step.answer)
            is DwScanStep.AskServer -> {
                val target = bridge.workshopId?.takeUnless { it.isBlank() || isLocalOnlyWorkshop(it) }
                if (target == null) {
                    refusal = DW_SCAN_UNSENT_WORKSHOP_MESSAGE
                    return
                }
                if (!bridge.repository.isOnline(context)) {
                    refusal = DW_SCAN_OFFLINE_MESSAGE
                    return
                }
                busy = true
                val askedUnder = currentParent.value
                scope.launch {
                    val answer = try {
                        val payload = bridge.repository.designWorkshopReferenceById(
                            workshopId = target,
                            model = field.refModel,
                            scope = field.refScope,
                            filterValue = askedUnder,
                            recordId = step.ref.id,
                        )
                        dwScanServerAnswer(field, step.ref, payload, cascadeLabel)
                    } catch (e: HttpException) {
                        DwScanAnswer.Refused(DW_SCAN_LOOKUP_FAILED_MESSAGE)
                    } catch (e: Exception) {
                        DwScanAnswer.Refused(DW_SCAN_OFFLINE_MESSAGE)
                    }
                    busy = false
                    expanded = true
                    if (askedUnder != currentParent.value) {
                        confirmed = null
                        refusal = dwScanCascadeMovedMessage(cascadeLabel)
                        return@launch
                    }
                    settle(answer)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionRow {
            TextButton(
                onClick = {
                    expanded = !expanded
                    if (!expanded) {
                        refusal = null
                        confirmed = null
                    }
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(
                    Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    if (expanded) "Hide the code reader" else "Scan a card or tag",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (expanded) {
            Text(
                "Scan the ${wanted.label.lowercase()}'s card or tag to link it.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            DwQrLiveScanControl(
                enabled = enabled && !busy,
                onText = { text ->
                    typed = text
                    resolve(text)
                },
                onRefusal = { message ->
                    confirmed = null
                    refusal = message
                },
            )
            DwQrScanControl(
                enabled = enabled && !busy,
                onText = { text ->
                    typed = text
                    resolve(text)
                },
                onRefusal = { message ->
                    confirmed = null
                    refusal = message
                },
            )
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    refusal = null
                    confirmed = null
                },
                label = { Text("Record code") },
                placeholder = {
                    Text(if (wanted == DwWorkshopRecordType.ARTISAN) ArtisanCardCode.PLACEHOLDER else "DPW1 :${wanted.letter}: …")
                },
                singleLine = true,
                enabled = enabled && !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { resolve(typed) },
                    enabled = enabled && typed.isNotBlank() && !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Link this code")
                }
                if (busy) FieldCircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            Text(
                "Spaces and capitals don't matter. The last four characters are a check.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )

            refusal?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            confirmed?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onSuccessContainer,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(MaterialTheme.field.successContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

internal fun buildReferenceOptions(
    items: List<DwReferenceOption>,
    selectedId: String,
    tentativeWord: String,
): List<SelectOption> {
    val options = items.map { option ->
        SelectOption(
            value = option.id,
            label = option.label.ifBlank { orphanLabel(option.id) },
            hint = listOfNotNull(
                tentativeWord.takeIf { it.isNotBlank() && option.tentative },
                option.hint.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotBlank() }
        )
    }
    if (selectedId.isBlank() || options.any { it.value == selectedId }) return options
    return listOf(
        SelectOption(
            value = selectedId,
            label = com.designprototype.workshop.data.DwReferenceStore.labelFor(selectedId)
                ?: orphanLabel(selectedId),
            hint = "already linked on this row"
        )
    ) + options
}

@Suppress("UNUSED_PARAMETER")
private fun orphanLabel(id: String): String = "A linked record not yet on this phone"

private fun heldOption(token: String): SelectOption =
    if (OPAQUE_ID.matches(token.trim())) {
        SelectOption(token, orphanLabel(token), "on this row, not in this device's list")
    } else {
        SelectOption(token, token, "typed on this field, not a linked record")
    }

internal fun hydratedValues(
    option: DwReferenceOption,
    mapping: Map<String, String>,
    writable: Map<String, FieldDto>,
): Map<String, JsonElement> {
    val out = LinkedHashMap<String, JsonElement>()
    mapping.forEach { (sourceKey, targetKey) ->
        if (targetKey.startsWith("_")) return@forEach
        val target = writable[targetKey] ?: return@forEach
        if (target.deprecated) return@forEach
        val raw = option.data[sourceKey] ?: return@forEach
        out[targetKey] = DwValues.coerceHydrated(target, raw) ?: return@forEach
    }
    return out
}

internal fun hydrationPatch(
    incoming: Map<String, JsonElement>,
    lastHydration: Map<String, JsonElement>,
    current: Map<String, JsonElement>,
    writable: Map<String, FieldDto>,
): Map<String, JsonElement?> {
    val patch = LinkedHashMap<String, JsonElement?>()

    incoming.forEach { (key, next) ->
        val existing = current[key]
        val multi = writable[key]?.let { DwFieldType.of(it.type).isMulti } ?: false
        val mayWrite = !DwValues.isFilled(existing) || (!multi && existing == lastHydration[key])
        if (!mayWrite) return@forEach
        patch[key] = next
    }

    lastHydration.forEach { (key, written) ->
        if (key in incoming || key !in writable) return@forEach
        if (current[key] == written) patch[key] = null
    }
    return patch
}

private fun friendlyAge(iso: String): String? {
    if (iso.isBlank()) return null
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val then = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    val days = java.time.temporal.ChronoUnit.DAYS.between(then, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 14L -> "$days days ago"
        else -> "on " + then.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}
