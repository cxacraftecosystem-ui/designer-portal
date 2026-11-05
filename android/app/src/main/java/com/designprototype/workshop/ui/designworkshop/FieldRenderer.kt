package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.CalendarField
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.destructiveAction
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.data.DwFieldStampDto
import com.designprototype.workshop.data.DwCustomFieldDto
import com.designprototype.workshop.data.DwDerived
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.customFieldToFieldDto
import com.designprototype.workshop.data.dwCustomFieldDrawable
import com.designprototype.workshop.data.dwCustomUnsupportedNote
import com.designprototype.workshop.data.dwEffectiveMaxItems
import com.designprototype.workshop.data.DwTextFormats
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.MapPickerDialog
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectCreateAction
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.WorkshopListKind
import com.designprototype.workshop.ui.WorkshopListState
import com.designprototype.workshop.ui.workshopListNotice
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.dwWithoutRequiredMark
import com.designprototype.workshop.ui.FieldDateField
import com.designprototype.workshop.ui.FieldTimeField
import com.designprototype.workshop.ui.ArtisanPhoneField
import com.designprototype.workshop.ui.NumberedListInput
import com.designprototype.workshop.ui.joinNumbered
import com.designprototype.workshop.ui.splitNumbered
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.formatFieldDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.designprototype.workshop.ui.SelectLoadMore
import com.designprototype.workshop.ui.serverHasMore
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDate

@Immutable
data class DwMediaItem(
    val id: String,
    val displayName: String,
    val absolutePath: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val remoteMediaId: String? = null,
    val transcribe: Boolean? = null,
)

@Immutable
class DwMediaBridge(
    val workshopId: String,
    val resolve: (String) -> DwMediaItem?,
    val screening: DwScreeningStore,
    val attach: (List<Uri>, FieldDto, (List<String>) -> Unit) -> Unit,
    val detach: (String) -> Unit,
    val newCaptureFile: (String) -> File,
    val setTranscribe: (String, Boolean) -> Unit = { _, _ -> },
)

@Immutable
class DwFieldServices(
    val repository: WorkshopRepository,
    val workshopId: String?,
    val onMessage: (String) -> Unit,
    val onError: (String) -> Unit,
    val inlineRecords: DwInlineRecordHost? = null,
) {
    val references: DwReferenceBridge = DwReferenceBridge(repository, workshopId, inlineRecords)
}

@Composable
fun FieldRenderer(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    media: DwMediaBridge? = null,
    caption: FieldDto? = null,
    captionValue: JsonElement? = null,
    onCaptionChange: (JsonElement?) -> Unit = {},
    resetKey: Any = field.key,
    services: DwFieldServices? = null,
    siblings: Map<String, FieldDto> = emptyMap(),
    rowValues: Map<String, JsonElement> = emptyMap(),
    onPatch: (Map<String, JsonElement?>) -> Unit = {},
    stamp: DwFieldStampDto? = null,
) {
    val type = remember(field.type) { DwFieldType.of(field.type) }
    val parentField = remember(field.refFilterBy, siblings) { siblings[field.refFilterBy] }
    val parentValue = rowValues[field.refFilterBy]

    DwFieldHelpHost(field, modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (field.computedKind.isNotBlank()) {
            DwComputedCount(field, value)
            return@Column
        }
        when (type) {
            DwFieldType.RICH_TEXT -> {
                RichTextEditor(
                    value = value,
                    onChange = onChange,
                    enabled = enabled,
                    label = fieldLabel(field),
                    help = field.help,
                    helpRow = { FieldCaption(field) },
                    listKind = if (field.reportRole == "BULLETS") BlockKind.ORDERED_ITEM else null,
                    media = media,
                    mediaField = field,
                    onMessage = { text -> services?.onMessage?.invoke(text) },
                    onError = { text -> services?.onError?.invoke(text) },
                )
                InlineError(error)
            }

            DwFieldType.LONG_TEXT -> if (field.reportRole == "BULLETS") {
                DwNumberedPointsInput(
                    field, value, onChange, enabled, error, resetKey = resetKey, services = services,
                )
            } else {
                ScalarInput(
                    field, value, onChange, enabled, error, minLines = 4, resetKey = resetKey,
                    services = services,
                )
            }

            DwFieldType.INT ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Number, resetKey = resetKey, rowValues = rowValues)

            DwFieldType.DECIMAL, DwFieldType.PERCENT ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Decimal, resetKey = resetKey, rowValues = rowValues)

            DwFieldType.MONEY ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Decimal, resetKey = resetKey, rowValues = rowValues)

            DwFieldType.URL ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Uri, resetKey = resetKey)

            DwFieldType.EMAIL ->
                ScalarInput(field, value, onChange, enabled, error, keyboard = KeyboardType.Email, resetKey = resetKey)

            DwFieldType.DATE -> DateField(field, value, onChange, error)

            DwFieldType.TIME -> {
                FieldCaption(field)
                FieldTimeField(
                    label = fieldLabel(field),
                    value = DwValues.text(value),
                    onValueChange = { text -> onChange(text.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)) }
                )
                InlineError(error)
            }

            DwFieldType.BOOL -> BoolField(field, value, onChange, enabled, error)

            DwFieldType.ENUM -> {
                FieldCaption(field)
                SearchableSelectField(
                    label = fieldLabel(field),
                    options = remember(field.options) {
                        field.options.map { SelectOption(it.value, it.label) }
                    },
                    selectedValue = DwValues.text(value),
                    enabled = enabled,
                    includeNone = true,
                    onSelect = { token -> onChange(token.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)) }
                )
                InlineError(error)
            }

            DwFieldType.MULTI_ENUM -> {
                val declaredCap = field.maxItems.takeIf { it > 0 }
                val ceiling = dwEffectiveMaxItems(field.maxItems)
                val held = remember(value) { DwValues.list(value) }
                var capNotice by remember(field.key, resetKey) { mutableStateOf<String?>(null) }

                fun keepWhatFits(next: List<String>): List<String> {
                    val kept = dwCapListGrowth(held, next, ceiling)
                    val dropped = next.size - kept.size
                    capNotice = if (dropped == 0) {
                        null
                    } else {
                        "${dwListCeilingClause(dwWithoutRequiredMark(fieldLabel(field)), declaredCap)}. " +
                            "$dropped of the " +
                            "${next.size} you chose ${if (dropped == 1) "was" else "were"} not kept — " +
                            "untick something first if you need ${if (dropped == 1) "it" else "them"} instead."
                    }
                    return kept
                }

                if (field.refModel.isNotBlank()) {
                    DwReferenceMultiSelectField(
                        field = field,
                        value = value,
                        parentField = parentField,
                        parentValue = parentValue,
                        bridge = services?.references,
                        enabled = enabled,
                        error = error,
                        label = fieldLabel(field),
                        onChange = { next ->
                            val chosen = DwValues.list(next)
                            val kept = keepWhatFits(chosen)
                            if (kept.size == chosen.size) onChange(next) else onChange(DwValues.ofList(kept))
                        },
                    )
                } else {
                    FieldCaption(field)
                    SearchableMultiSelectField(
                        label = fieldLabel(field),
                        options = remember(field.options) {
                            field.options.map { SelectOption(it.value, it.label) }
                        },
                        selected = remember(value) { DwValues.list(value).toSet() },
                        enabled = enabled,
                        onSelectedChange = { chosen ->
                            val ordered = field.options.map { it.value }.filter { it in chosen }
                            onChange(DwValues.ofList(keepWhatFits(ordered)))
                        }
                    )
                    InlineError(error)
                }
                DwListCapHint(declaredCap, held.size)
                DwListCapNotice(capNotice)
            }

            DwFieldType.TAGS -> TagsField(field, value, onChange, enabled, error, resetKey)

            DwFieldType.GEO -> if (services != null) {
                DwGeoField(
                    label = fieldLabel(field),
                    help = field.help,
                    helpRow = { FieldCaption(field) },
                    required = field.required,
                    value = value,
                    repository = services.repository,
                    enabled = enabled,
                    error = error,
                    onChange = onChange,
                    onMessage = services.onMessage,
                )
                DwLocationFromPhoto(enabled = enabled) { location -> onChange(dwLocationToValue(location)) }
            } else {
                GeoField(field, value, onChange, enabled, error, resetKey)
            }

            DwFieldType.PHONE -> {
                FieldCaption(field)
                val phoneProblem = DwTextFormats.error(field.format, DwValues.text(value))
                ArtisanPhoneField(
                    value = DwValues.text(value),
                    error = error ?: phoneProblem,
                    onValueChange = { text ->
                        onChange(text.trim().takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
                    }
                )
            }

            DwFieldType.REF -> if (services != null) {
                DwReferenceSelectField(
                    field = field,
                    value = value,
                    parentField = parentField,
                    parentValue = parentValue,
                    bridge = services.references,
                    enabled = enabled,
                    error = error,
                    label = fieldLabel(field),
                    rowValues = rowValues,
                    writableFields = siblings,
                    onChange = onChange,
                    onHydrate = onPatch,
                )
            } else {
                ScalarInput(
                    field, value, onChange, enabled, error,
                    helpOverride = listOfNotNull(
                        field.help.takeIf { it.isNotBlank() },
                        field.refModel.takeIf { it.isNotBlank() }?.let { "Identifier of the linked $it record." }
                    ).joinToString(" "),
                    resetKey = resetKey,
                )
            }

            DwFieldType.IMAGE, DwFieldType.IMAGE_LIST, DwFieldType.FILE,
            DwFieldType.AUDIO, DwFieldType.VIDEO ->
                MediaField(
                    field = field,
                    type = type,
                    value = value,
                    onChange = onChange,
                    enabled = enabled,
                    error = error,
                    media = media,
                    caption = caption,
                    captionValue = captionValue,
                    onCaptionChange = onCaptionChange,
                    resetKey = resetKey,
                    services = services,
                    siblings = siblings,
                    rowValues = rowValues,
                    onPatch = onPatch,
                )

            DwFieldType.TEXT -> if (services != null && dwOwnWorkshopTitleRole(field)) {
                DwWorkshopNameField(
                    field = field,
                    value = value,
                    onChange = onChange,
                    enabled = enabled,
                    error = error,
                    resetKey = resetKey,
                    services = services,
                    rowValues = rowValues,
                )
            } else {
                ScalarInput(
                    field, value, onChange, enabled, error, resetKey = resetKey,
                    keyboard = if (dwNumericTextField(field)) KeyboardType.Number else KeyboardType.Text,
                    services = services,
                    rowValues = rowValues,
                    siblings = siblings,
                )
            }
        }

        stamp?.attribution()?.let { line ->
            Text(line, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    }
}

@Composable
fun DwCustomFieldRow(
    field: DwCustomFieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    services: DwFieldServices? = null,
    siblings: Map<String, FieldDto> = emptyMap(),
    rowValues: Map<String, JsonElement> = emptyMap(),
) {
    val spec = remember(field) { customFieldToFieldDto(field) }
    if (!dwCustomFieldDrawable(field.type)) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScalarInput(
                field = spec,
                value = value,
                onChange = {},
                enabled = false,
                error = null,
                helpOverride = dwCustomUnsupportedNote(field.type),
            )
        }
        return
    }
    FieldRenderer(
        field = spec,
        value = value,
        onChange = onChange,
        modifier = modifier,
        error = error,
        media = null,
        resetKey = field.key,
        services = services,
        siblings = siblings,
        rowValues = rowValues,
    )
}

private fun fieldLabel(field: FieldDto): String = buildString {
    append(field.label)
    if (field.unit.isNotBlank()) append(" (${field.unit})")
    if (field.required) append(" *")
}

@Composable
internal fun FieldCaption(field: FieldDto, override: String? = null) {
    val help = override ?: field.help
    val openHelp = LocalDwFieldHelp.current
    if (openHelp != null && DwFieldDetails.has(field)) {
        DwHelpCaptionText(help, field, openHelp)
        return
    }
    if (help.isBlank()) return
    Text(help, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InlineError(error: String?) {
    if (error.isNullOrBlank()) return
    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

internal fun dwNumericTextField(field: FieldDto): Boolean =
    DwFieldType.of(field.type) == DwFieldType.TEXT && field.format == "PINCODE"

private const val DW_NAME_OFFER_PAGE_SIZE = 80

private const val DW_NAME_SEARCH_DEBOUNCE_MS = 350L

internal fun dwOwnWorkshopTitleRole(field: FieldDto): Boolean =
    field.type == "TEXT" && !field.deprecated && field.key == "workshopTitle"

internal fun dwWorkshopNameCreateLabel(query: String): String? =
    query.trim().takeIf { it.isNotEmpty() }?.let { "Use “$it” as the name" }

@Immutable
internal data class DwWorkshopNameOffer(val names: List<String>, val withheld: Int)

internal fun dwWorkshopNamesOnRecord(titles: List<String>, maxLength: Int): DwWorkshopNameOffer {
    val kept = LinkedHashSet<String>()
    var withheld = 0
    for (raw in titles) {
        val title = raw.trim()
        if (title.isEmpty()) continue
        if (maxLength > 0 && title.length > maxLength) {
            withheld += 1
            continue
        }
        kept += title
    }
    return DwWorkshopNameOffer(kept.toList(), withheld)
}

internal fun dwWorkshopNameOptions(current: String, names: List<String>): List<SelectOption> {
    val typed = current.trim()
    val rows = mutableListOf<SelectOption>()
    if (typed.isNotEmpty() && names.none { it == typed }) {
        rows += SelectOption(value = typed, label = typed, hint = "already on this workshop")
    }
    names.forEach { rows += SelectOption(value = it, label = it) }
    return rows
}

internal fun dwWorkshopNameOfferLine(
    workshopKind: String,
    shown: Int,
    total: Int,
    searching: Boolean = false,
): String =
    buildString {
        append(
            if (workshopKind.isBlank()) "Names from workshops you can open."
            else "Names from workshops of this type."
        )
        append(
            if (searching) " All searched. Type a new one if it isn't here."
            else " Type to search, or add a new one."
        )
        if (shown > 0 && total > shown) append(" Showing $shown of $total.")
    }

internal fun dwWorkshopNameNotice(
    state: WorkshopListState,
    online: Boolean,
    query: String,
): String? {
    val term = query.trim()
    if (state is WorkshopListState.Listed && state.count == 0 && term.isNotEmpty()) {
        return "No workshop name matches “$term”."
    }
    return workshopListNotice(state, WorkshopListKind.DESIGN, online)
}

internal fun dwWorkshopNamesWithheldLine(withheld: Int, maxLength: Int): String? =
    if (withheld <= 0) null
    else "$withheld name${if (withheld == 1) "" else "s"} over $maxLength characters hidden."

@Composable
private fun DwWorkshopNameField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    resetKey: Any,
    services: DwFieldServices,
    rowValues: Map<String, JsonElement>,
) {
    val workshopKind = DwValues.text(rowValues["workshopKind"])

    var list by remember(field.key, resetKey) {
        mutableStateOf<WorkshopListState>(WorkshopListState.Loading)
    }
    var offer by remember(field.key, resetKey) { mutableStateOf(DwWorkshopNameOffer(emptyList(), 0)) }
    var online by remember(field.key, resetKey) { mutableStateOf(true) }
    var localError by remember(field.key, resetKey) { mutableStateOf<String?>(null) }
    var spoken by remember(field.key, resetKey) { mutableStateOf("") }
    var nameQuery by remember(field.key, resetKey) { mutableStateOf("") }
    var titles by remember(field.key, resetKey) { mutableStateOf<List<String>>(emptyList()) }
    var namePages by remember(field.key, resetKey) { mutableStateOf(1) }
    var nameMoreLoading by remember(field.key, resetKey) { mutableStateOf(false) }
    var nameMoreFailed by remember(field.key, resetKey) { mutableStateOf(false) }
    val nameScope = rememberCoroutineScope()

    val current = DwValues.text(value)

    fun commit(text: String) {
        val coerced = DwValues.coerce(field, text)
        localError = coerced.error
        if (coerced.error == null) onChange(coerced.value)
    }

    LaunchedEffect(workshopKind, nameQuery, field.key, resetKey) {
        if (nameQuery.isNotBlank()) delay(DW_NAME_SEARCH_DEBOUNCE_MS)
        list = WorkshopListState.Loading
        val answer = try {
            services.repository.designWorkshops(
                page = 1,
                pageSize = DW_NAME_OFFER_PAGE_SIZE,
                search = nameQuery.trim().takeIf { it.isNotEmpty() },
                workshopKind = workshopKind.takeIf { it.isNotBlank() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            online = !services.repository.isTransient(failure)
            list = WorkshopListState.Failed
            if (nameQuery.isNotBlank()) offer = DwWorkshopNameOffer(emptyList(), 0)
            return@LaunchedEffect
        }
        titles = answer.items.map { it.title }
        namePages = 1
        nameMoreFailed = false
        val built = dwWorkshopNamesOnRecord(titles, field.maxLength)
        offer = built
        list = WorkshopListState.Listed(count = built.names.size, total = answer.total)
    }

    val nameTotal = (list as? WorkshopListState.Listed)?.total ?: 0
    val nameMore = if (serverHasMore(namePages, DW_NAME_OFFER_PAGE_SIZE, nameTotal) || nameMoreFailed) {
        SelectLoadMore(
            shown = titles.size,
            total = nameTotal,
            loading = nameMoreLoading,
            failed = nameMoreFailed,
            onClick = {
                if (!nameMoreLoading) {
                    val asked = nameQuery
                    nameScope.launch {
                        nameMoreLoading = true
                        nameMoreFailed = false
                        try {
                            val next = services.repository.designWorkshops(
                                page = namePages + 1,
                                pageSize = DW_NAME_OFFER_PAGE_SIZE,
                                search = asked.trim().takeIf { it.isNotEmpty() },
                                workshopKind = workshopKind.takeIf { it.isNotBlank() },
                            )
                            if (asked == nameQuery) {
                                titles = titles + next.items.map { it.title }
                                namePages += 1
                                val built = dwWorkshopNamesOnRecord(titles, field.maxLength)
                                offer = built
                                list = WorkshopListState.Listed(count = built.names.size, total = next.total)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            nameMoreFailed = true
                        } finally {
                            nameMoreLoading = false
                        }
                    }
                }
            },
        )
    } else {
        null
    }

    val options = remember(current, offer) { dwWorkshopNameOptions(current, offer.names) }
    val notice = dwWorkshopNameNotice(list, online, nameQuery)
    val listed = list as? WorkshopListState.Listed

    val canDictate = rememberDictationAvailable() && dictatable(DwFieldType.of(field.type))

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldCaption(field)
        Row(verticalAlignment = Alignment.Bottom) {
            SearchableSelectField(
                modifier = Modifier.weight(1f),
                label = fieldLabel(field),
                options = options,
                selectedValue = current,
                includeNone = false,
                searchable = true,
                enabled = enabled,
                emptyMessage = notice,
                createAction = SelectCreateAction(
                    label = ::dwWorkshopNameCreateLabel,
                    onClick = ::commit,
                ),
                onSearch = { typed -> nameQuery = typed },
                loadMore = nameMore,
                onSelect = { picked -> if (picked.isNotBlank()) commit(picked) },
            )
            if (canDictate) {
                DwDictationButton(
                    enabled = enabled,
                    onPartial = { partial -> spoken = partial },
                    onCommit = { finalText ->
                        val merged = appendSpoken(current, finalText)
                        spoken = ""
                        commit(merged)
                    },
                    onError = { message ->
                        spoken = ""
                        services.onError(message)
                    },
                )
            }
        }
        if (canDictate) {
            if (spoken.isNotBlank()) {
                Text(
                    appendSpoken(current, spoken),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
            DwDictationHint(listening = spoken.isNotBlank())
        }
        Text(
            dwWorkshopNameOfferLine(
                workshopKind = workshopKind,
                shown = titles.size,
                total = listed?.total ?: 0,
                searching = nameQuery.isNotBlank(),
            ),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 15.sp,
        )
        notice?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
        }
        dwWorkshopNamesWithheldLine(offer.withheld, field.maxLength)?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
        }
        InlineError(localError ?: error)
    }
}

internal const val DW_CRAFT_NAME_FROM_LIST = "From the craft picked from the list."

internal fun dwCraftNameLocked(field: FieldDto, siblings: Map<String, FieldDto>): Boolean =
    field.key == "craftName" && siblings["craftRef"]?.refModel == "Craft"

@Composable
private fun ScalarInput(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    helpOverride: String? = null,
    resetKey: Any = field.key,
    services: DwFieldServices? = null,
    rowValues: Map<String, JsonElement> = emptyMap(),
    siblings: Map<String, FieldDto> = emptyMap(),
) {
    var buffer by remember(field.key, resetKey) { mutableStateOf(DwValues.text(value)) }
    var localError by remember(field.key, resetKey) { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    var spoken by remember(field.key, resetKey) { mutableStateOf("") }
    var keptMediaId by remember(field.key, resetKey) { mutableStateOf<String?>(null) }

    val dictationAvailable = rememberDictationAvailable()
    val canDictate = services != null && dictationAvailable &&
        dictatable(DwFieldType.of(field.type)) && !dwNumericTextField(field)
    val canReadCard = services != null &&
        DwFieldType.of(field.type) == DwFieldType.TEXT && isIdentityNumberField(field)

    val derivedHint = remember(field, rowValues) {
        if (!DwDerived.isDerived(field)) null else DwDerived.value(field, rowValues)?.let { computed ->
            val shown = DwValues.text(computed)
            if (field.unit.isBlank()) "$shown (computed)" else "$shown ${field.unit} (computed)"
        }
    }

    LaunchedEffect(value) {
        val stored = DwValues.text(value)
        val bufferAsStored = DwValues.text(DwValues.coerce(field, buffer).value)
        if (bufferAsStored != stored) buffer = stored
    }

    var leftOnce by remember(field.key, resetKey) { mutableStateOf(buffer.isNotBlank()) }
    val formatProblem = remember(field.format, buffer, leftOnce) {
        buffer.trim().takeIf { it.isNotEmpty() && leftOnce }?.let { DwTextFormats.error(field.format, it) }
    }

    fun commit(text: String) {
        buffer = text
        val coerced = DwValues.coerce(field, text)
        localError = coerced.error
        if (coerced.error == null) onChange(coerced.value)
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldCaption(field, helpOverride)
        OutlinedTextField(
            value = if (spoken.isBlank()) buffer else appendSpoken(buffer, spoken),
            onValueChange = { raw -> if (spoken.isBlank()) commit(raw) },
            label = { Text(requiredMarked(fieldLabel(field))) },
            enabled = enabled && !dwCraftNameLocked(field, siblings),
            readOnly = spoken.isNotBlank(),
            isError = error != null || localError != null || formatProblem != null,
            supportingText = (if (dwCraftNameLocked(field, siblings)) DW_CRAFT_NAME_FROM_LIST else derivedHint?.takeIf { buffer.isBlank() })
                ?.let { hint -> { Text(hint) } },
            minLines = minLines,
            singleLine = minLines == 1 && spoken.isBlank(),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                imeAction = if (minLines == 1) ImeAction.Next else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            trailingIcon = if (!canDictate) null else {
                {
                    DwDictationButton(
                        enabled = enabled,
                        onPartial = { partial -> spoken = partial },
                        onCommit = { finalText ->
                            val merged = appendSpoken(buffer, finalText)
                            spoken = ""
                            commit(merged)
                        },
                        onError = { message ->
                            spoken = ""
                            services?.onError?.invoke(message)
                        },
                        onKept = { id -> keptMediaId = id },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { state -> if (!state.isFocused && buffer.isNotBlank()) leftOnce = true }
        )
        DwDictationHint(listening = spoken.isNotBlank())
        DwDictationKept(keptMediaId)
        if (canReadCard) {
            DwIdentityCardControl(
                targetLabel = field.label,
                kind = identityKindFor(field),
                repository = services!!.repository,
                enabled = enabled,
                storeMasked = field.storeMasked,
                onUse = { number -> commit(number) },
                onError = services.onError,
            )
        }
        InlineError(localError ?: formatProblem ?: error)
    }
}

private fun appendSpoken(existing: String, spoken: String): String = when {
    spoken.isBlank() -> existing
    existing.isBlank() -> spoken
    existing.last().isWhitespace() -> existing + spoken
    else -> "$existing $spoken"
}

@Composable
private fun DwNumberedPointsInput(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    resetKey: Any = field.key,
    services: DwFieldServices? = null,
) {
    var rows by remember(field.key, resetKey) { mutableStateOf(splitNumbered(DwValues.text(value))) }
    var localError by remember(field.key, resetKey) { mutableStateOf<String?>(null) }
    var spokenRow by remember(field.key, resetKey) { mutableStateOf(-1) }
    var spoken by remember(field.key, resetKey) { mutableStateOf("") }
    var keptMediaId by remember(field.key, resetKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(value) {
        val stored = DwValues.text(value)
        if (joinNumbered(rows) != stored) rows = splitNumbered(stored)
    }

    fun emit(next: List<String>) {
        rows = next
        val coerced = DwValues.coerce(field, joinNumbered(next))
        localError = coerced.error
        if (coerced.error == null) onChange(coerced.value)
    }

    val dictationAvailable = rememberDictationAvailable()
    val canDictate = services != null && dictationAvailable && dictatable(DwFieldType.LONG_TEXT)

    FieldCaption(field)
    NumberedListInput(
        label = fieldLabel(field),
        required = false,
        mutedLabel = true,
        items = rows,
        error = localError ?: error,
        helper = null,
        enabled = enabled,
        rowOverlay = { index ->
            if (index != spokenRow || spoken.isBlank()) null
            else appendSpoken(rows.getOrElse(index) { "" }, spoken)
        },
        rowsLocked = spokenRow >= 0,
        rowTrailing = if (!canDictate) null else {
            { index ->
                DwDictationButton(
                    enabled = enabled && (spokenRow < 0 || spokenRow == index),
                    onPartial = { partial ->
                        spokenRow = index
                        spoken = partial
                    },
                    onCommit = { finalText ->
                        val merged = appendSpoken(rows.getOrElse(index) { "" }, finalText)
                        spoken = ""
                        spokenRow = -1
                        emit(rows.toMutableList().also { current ->
                            if (index < current.size) current[index] = merged else current.add(merged)
                        })
                    },
                    onError = { message ->
                        spoken = ""
                        spokenRow = -1
                        services?.onError?.invoke(message)
                    },
                    onKept = { id -> keptMediaId = id },
                )
            }
        },
        onChange = ::emit,
    )
    DwDictationHint(listening = spoken.isNotBlank())
    DwDictationKept(keptMediaId)
}

@Composable
private fun DateField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    error: String?,
) {
    val parsed = remember(value) {
        val text = DwValues.text(value)
        if (text.isBlank()) null else runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    }
    FieldCaption(field)
    CalendarField(
        label = fieldLabel(field),
        value = parsed,
        clearable = true,
        onValueChange = { day -> onChange(day?.let { JsonPrimitive(it.toString()) }) },
        supportingText = parsed?.let { "Saved as ${formatFieldDate(it)}" }
    )
    InlineError(error)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoolField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
) {
    val current = remember(value) { DwValues.bool(value) }
    Text(requiredMarked(fieldLabel(field)), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    FieldCaption(field)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = current == true,
            enabled = enabled,
            onClick = { onChange(if (current == true) null else JsonPrimitive(true)) },
            label = { Text("Yes") }
        )
        FilterChip(
            selected = current == false,
            enabled = enabled,
            onClick = { onChange(if (current == false) null else JsonPrimitive(false)) },
            label = { Text("No") }
        )
        if (current != null) {
            TextButton(onClick = { onChange(null) }, enabled = enabled) { Text("Clear") }
        }
    }
    InlineError(error)
}

internal fun dwListCeilingClause(label: String, declaredCap: Int?): String =
    if (declaredCap == null) {
        "$label is full"
    } else {
        "$label holds at most $declaredCap entr${if (declaredCap == 1) "y" else "ies"}"
    }

internal fun dwCapListGrowth(held: List<String>, next: List<String>, ceiling: Int): List<String> {
    if (next.size <= ceiling || next.size <= held.size) return next
    val keep = LinkedHashSet(next.filter { it in held })
    for (candidate in next) {
        if (keep.size >= ceiling) break
        keep.add(candidate)
    }
    return next.filter { it in keep }
}

@Composable
private fun DwListCapNotice(notice: String?) {
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Assertive
        },
    ) {
        notice?.let { sentence ->
            Text(sentence, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun DwListCapHint(declaredCap: Int?, held: Int) {
    if (declaredCap == null) return
    val room = (declaredCap - held).coerceAtLeast(0)
    Text(
        if (room == 0) {
            "Full at $declaredCap. Remove one to add another."
        } else {
            "Up to $declaredCap — $room more can be added."
        },
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    resetKey: Any = field.key,
) {
    val tags = remember(value) { DwValues.list(value) }
    var pending by remember(field.key, resetKey) { mutableStateOf("") }
    val declaredCap = field.maxItems.takeIf { it > 0 }
    val ceiling = dwEffectiveMaxItems(field.maxItems)
    var capNotice by remember(field.key, resetKey) { mutableStateOf<String?>(null) }

    fun commit() {
        val cleaned = pending.trim()
        if (cleaned.isNotEmpty() && tags.none { it.equals(cleaned, ignoreCase = true) } && tags.size >= ceiling) {
            capNotice = "${dwListCeilingClause(dwWithoutRequiredMark(fieldLabel(field)), declaredCap)}. " +
                "“$cleaned” was not " +
                "added — remove one first if you need it instead."
            return
        }
        pending = ""
        if (cleaned.isEmpty() || tags.any { it.equals(cleaned, ignoreCase = true) }) return
        capNotice = null
        onChange(DwValues.ofList(tags + cleaned))
    }

    Text(requiredMarked(fieldLabel(field)), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    FieldCaption(field)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = pending,
            onValueChange = { pending = it.replace("\n", "") },
            label = { Text("Add") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = ::commit, enabled = enabled && pending.isNotBlank()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add")
        }
    }
    DwListCapHint(declaredCap, tags.size)
    DwListCapNotice(capNotice)
    if (tags.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            tags.forEach { tag ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Text(tag, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                    IconButton(
                        onClick = {
                            capNotice = null
                            onChange(DwValues.ofList(tags.filterNot { it == tag }))
                        },
                        enabled = enabled,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove $tag",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
    InlineError(error)
}

@Composable
private fun GeoField(
    field: FieldDto,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    resetKey: Any = field.key,
) {
    val point = remember(value) { DwValues.geo(value) }
    var latText by remember(field.key, resetKey) { mutableStateOf(point?.first?.let { trimCoordinate(it) } ?: "") }
    var lonText by remember(field.key, resetKey) { mutableStateOf(point?.second?.let { trimCoordinate(it) } ?: "") }
    var showMap by remember { mutableStateOf(false) }

    LaunchedEffect(point) {
        val sameLat = point?.first == latText.trim().toDoubleOrNull()
        val sameLon = point?.second == lonText.trim().toDoubleOrNull()
        if (!sameLat || !sameLon) {
            latText = point?.first?.let { trimCoordinate(it) } ?: ""
            lonText = point?.second?.let { trimCoordinate(it) } ?: ""
        }
    }

    fun emit() {
        val lat = latText.trim().toDoubleOrNull()
        val lon = lonText.trim().toDoubleOrNull()
        when {
            lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 ->
                onChange(DwValues.geoOf(lat, lon))
            latText.isBlank() && lonText.isBlank() -> onChange(null)
            else -> Unit
        }
    }

    Text(requiredMarked(fieldLabel(field)), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    FieldCaption(field)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = latText,
            onValueChange = { latText = it; emit() },
            label = { Text("Latitude") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = lonText,
            onValueChange = { lonText = it; emit() },
            label = { Text("Longitude") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
    }
    ActionRow {
        OutlinedButton(onClick = { showMap = true }, enabled = enabled) {
            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pick on map")
        }
        if (point != null) {
            TextButton(modifier = Modifier.destructiveAction(), onClick = { latText = ""; lonText = ""; onChange(null) }, enabled = enabled) {
                Text("Clear")
            }
        }
    }
    InlineError(error)

    if (showMap) {
        MapPickerDialog(
            initialLat = latText.trim().toDoubleOrNull() ?: point?.first,
            initialLng = lonText.trim().toDoubleOrNull() ?: point?.second,
            onDismiss = { showMap = false },
            onPick = { lat, lon ->
                latText = trimCoordinate(lat)
                lonText = trimCoordinate(lon)
                onChange(DwValues.geoOf(lat, lon))
                showMap = false
            }
        )
    }
}

private fun trimCoordinate(value: Double): String = String.format(java.util.Locale.ROOT, "%.6f", value)

@Composable
private fun MediaField(
    field: FieldDto,
    type: DwFieldType,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
    enabled: Boolean,
    error: String?,
    media: DwMediaBridge?,
    caption: FieldDto?,
    captionValue: JsonElement?,
    onCaptionChange: (JsonElement?) -> Unit,
    resetKey: Any,
    services: DwFieldServices? = null,
    siblings: Map<String, FieldDto> = emptyMap(),
    rowValues: Map<String, JsonElement> = emptyMap(),
    onPatch: (Map<String, JsonElement?>) -> Unit = {},
) {
    val ids = remember(value, type) {
        if (type == DwFieldType.IMAGE_LIST) DwValues.list(value)
        else listOfNotNull(DwValues.text(value).takeIf { it.isNotBlank() })
    }

    Text(requiredMarked(fieldLabel(field)), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    FieldCaption(field)

    if (media == null) {
        Text("Attachments open once you can edit this workshop.",
            color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        return
    }

    DwMediaCaptureCard(
        field = field,
        type = type,
        ids = ids,
        media = media,
        slotKey = "${field.key}#$resetKey",
        enabled = enabled,
        onIdsChange = { next ->
            onChange(
                if (type == DwFieldType.IMAGE_LIST) DwValues.ofList(next)
                else next.firstOrNull()?.let(::JsonPrimitive)
            )
        },
        onMessage = services?.onMessage ?: {},
        onError = services?.onError ?: {},
    )

    if (dwOffersPhotoMeasure(field, siblings)) {
        val targets = remember(siblings) { dwMeasurableLengthFields(siblings) }
        val photos = ids.mapNotNull(media.resolve).filter { it.mediaType.equals("IMAGE", ignoreCase = true) }
        DwPhotoMeasurePanel(
            photos = photos,
            targets = targets,
            rowValues = rowValues,
            enabled = enabled,
            onPropose = { key, proposed, _ -> onPatch(mapOf(key to proposed)) },
        )
    }

    val sketchDerivationSources =
        if (dwOffersSketchRectify(field, siblings)) {
            dwSketchSourceFields(siblings).flatMap { imageField ->
                val imageIds = if (DwFieldType.of(imageField.type) == DwFieldType.IMAGE_LIST) {
                    DwValues.list(rowValues[imageField.key])
                } else {
                    listOfNotNull(DwValues.text(rowValues[imageField.key]).takeIf { it.isNotBlank() })
                }
                imageIds.mapNotNull(media.resolve)
                    .filter { it.mediaType.equals("IMAGE", ignoreCase = true) }
                    .map { DwSketchSource(fieldLabel = imageField.label, item = it) }
            }
        } else {
            emptyList()
        }

    if (dwOffersSketchRectify(field, siblings)) {
        DwSketchRectifyPanel(
            field = field,
            sources = sketchDerivationSources,
            media = media,
            currentFileName = ids.firstOrNull()?.let(media.resolve)?.displayName,
            enabled = enabled,
            onAttached = { id -> onChange(JsonPrimitive(id)) },
            onMessage = services?.onMessage ?: {},
            onError = services?.onError ?: {},
        )
    }

    if (dwOffersSketchTrace(field, siblings)) {
        val exportRepository = services?.repository
        DwSketchTracePanel(
            field = field,
            sources = sketchDerivationSources,
            runtime = rememberDwTraceRuntime(),
            media = media,
            recordCategory = DwValues.text(rowValues["category"]).takeIf { it.isNotBlank() },
            currentFileName = ids.firstOrNull()?.let(media.resolve)?.displayName,
            enabled = enabled,
            onAttached = { id -> onChange(JsonPrimitive(id)) },
            onMessage = services?.onMessage ?: {},
            onError = services?.onError ?: {},
            exportCard = { slot ->
                if (exportRepository != null) {
                    DwSketchTraceExportCard(
                        repository = exportRepository,
                        traceSvg = slot.result.svg,
                        geometry = slot.result.geometry,
                        documentWidth = slot.result.width,
                        documentHeight = slot.result.height,
                        documentBackground = slot.documentBackground,
                        sourceName = slot.sourceName,
                        shapeCount = slot.result.shapeCount,
                        nodeCount = slot.result.nodeCount,
                        frameNote = slot.result.frameNote,
                        isPreview = slot.result.isPreview,
                        exporter = rememberDwTraceExporter(),
                        busy = slot.busy,
                        onBusyChange = slot.onBusyChange,
                        onError = services?.onError ?: {},
                        onBackgroundChange = slot.onBackgroundChange,
                        onNeedFullResolution = slot.onNeedFullResolution,
                    )
                }
            },
        )
    }

    if (caption != null) {
        Box(modifier = Modifier.padding(start = 12.dp, top = 2.dp)) {
            ScalarInput(
                field = caption,
                value = captionValue,
                onChange = onCaptionChange,
                enabled = enabled,
                error = null,
                minLines = 2,
                resetKey = resetKey,
            )
        }
    }

    InlineError(error)
}

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

