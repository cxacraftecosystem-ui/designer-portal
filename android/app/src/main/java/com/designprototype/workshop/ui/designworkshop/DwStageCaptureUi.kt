package com.designprototype.workshop.ui.designworkshop

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwAssistSittingDto
import com.designprototype.workshop.data.DwAssistSuggestionDto
import com.designprototype.workshop.data.DwAutoNumber
import com.designprototype.workshop.data.DwStageAssist
import com.designprototype.workshop.data.DwStageComputed
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.LocationRequest
import com.designprototype.workshop.data.dwDisplayText
import com.designprototype.workshop.data.dwLastStageName
import com.designprototype.workshop.data.dwRowKeys
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The handset's half of the 2026-10-17 capture work, drawn by [StageScreen]: suggestions, attendance,
 * "Log today", the market-survey sittings, one row per photo, the computed counts, the carry from a
 * chosen sketch or prototype, and a photo's location. Words match the web character for character.
 */

/** Widgets this build draws itself; a field declaring one is left out of the generic form. */
internal val DW_KNOWN_WIDGETS = setOf("ATTENDANCE")

internal const val DW_ROSTER_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
internal const val DW_SURVEY_STAGE = "MARKET_SURVEY_CAPTURE"
internal const val DW_CLOSING_STAGE = "INSPECTION_CLOSING"
internal const val DW_SETUP_STAGE = "WORKSHOP_SETUP"
internal const val DW_PROTOTYPE_STAGE = "PROTOTYPE_DEVELOPMENT"

/** B11: the collections that take one row per selected photo, and the box each file fills. */
internal val DW_ROWS_FROM_PHOTOS = mapOf(
    "SKETCH_DEVELOPMENT" to Triple("sketch", "image", "sketch"),
    "MARKET_SURVEY_CAPTURE" to Triple("competitorProduct", "photo", "product"),
)

@Composable
private fun CaptureCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun CaptureLine(title: String, detail: String, actions: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        ActionRow { actions() }
    }
}

/** The server's one-tap suggestions for this stage. Hidden when there is nothing to offer. */
@Composable
internal fun DwStageAssistCard(
    suggestions: List<DwAssistSuggestionDto>,
    enabled: Boolean,
    onApply: (DwAssistSuggestionDto) -> Unit,
) {
    if (suggestions.isEmpty()) return
    CaptureCard("Suggestions") {
        suggestions.forEach { suggestion ->
            CaptureLine(
                title = if (suggestion.due) "${suggestion.title} · Due" else suggestion.title,
                detail = suggestion.detail,
            ) {
                OutlinedButton(
                    onClick = { onApply(suggestion) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(DwStageAssist.actionWord(suggestion)) }
            }
        }
    }
}

private fun shortDay(iso: String?): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("en-IN")))
}.getOrDefault("")

/** C7: one tick-list per workshop day, starting "All present". Writes `absentDays` only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DwAttendanceCard(
    days: List<Int>,
    participants: List<Pair<String, Map<String, JsonElement>>>,
    startDate: String?,
    enabled: Boolean,
    onChange: (List<Pair<String, Map<String, JsonElement>>>) -> Unit,
) {
    val people = participants.filter { (_, values) -> DwStageComputed.named(listOf(values)).isNotEmpty() }
    if (days.isEmpty() || people.isEmpty()) return
    var picked by remember { mutableStateOf<Int?>(null) }
    val day = picked?.takeIf { it in days } ?: days.first()
    val present = people.count { (_, v) -> day.toString() !in DwStageComputed.absentDays(v) }
    CaptureCard("Attendance") {
        Text(
            if (present == people.size) "All present" else "$present of ${people.size} present",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.muted,
        )
        // A choice between named days, not an action row.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            days.forEach { n ->
                val date = DwAutoNumber.dateOfDay(startDate, JsonPrimitive(n))
                FilterChip(
                    selected = n == day,
                    onClick = { picked = n },
                    label = { Text(if (date != null) "Day $n · ${shortDay(date)}" else "Day $n", style = MaterialTheme.typography.bodySmall) },
                )
            }
        }
        people.forEach { (id, values) ->
            val isPresent = day.toString() !in DwStageComputed.absentDays(values)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = isPresent,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { now ->
                            onChange(participants.map { (rowId, row) ->
                                if (rowId == id) rowId to DwStageComputed.withAttendance(row, day, now) else rowId to row
                            })
                        },
                    ),
            ) {
                Checkbox(checked = isPresent, onCheckedChange = null, enabled = enabled)
                Text(dwDisplayText(values["name"]).ifBlank { "Unnamed" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (present < people.size) {
            ActionRow {
                OutlinedButton(
                    onClick = {
                        onChange(participants.map { (rowId, row) ->
                            if (people.any { it.first == rowId }) rowId to DwStageComputed.withAttendance(row, day, true) else rowId to row
                        })
                    },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Mark all present") }
            }
        }
    }
}

/** A15: "Log today" on each prototype, and the last stage name again in one tap. */
@Composable
internal fun DwLogTodayCard(
    prototypes: List<Pair<String, Map<String, JsonElement>>>,
    logs: List<Map<String, JsonElement>>,
    enabled: Boolean,
    onAdd: (Map<String, JsonElement>) -> Unit,
) {
    val named = prototypes.filter { (_, v) -> dwDisplayText(v["name"]).isNotBlank() || dwDisplayText(v["prototypeCode"]).isNotBlank() }
    if (named.isEmpty()) return
    val today = LocalDate.now().toString()
    CaptureCard("Daily log") {
        named.forEach { (id, values) ->
            val keys = dwRowKeys(values, id)
            val ref = dwDisplayText(values["_entryId"]).ifBlank { id }
            val last = dwLastStageName(keys, logs)
            val title = listOf(dwDisplayText(values["prototypeCode"]), dwDisplayText(values["name"]))
                .filter { it.isNotBlank() }.joinToString(" · ")
            val loggedToday = logs.any { dwDisplayText(it["prototypeRef"]) in keys && dwDisplayText(it["logDate"]) == today }
            CaptureLine(title = title, detail = if (loggedToday) "Logged today" else "") {
                OutlinedButton(
                    onClick = { onAdd(mapOf("prototypeRef" to JsonPrimitive(ref), "logDate" to JsonPrimitive(today))) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Log today") }
                if (last.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            onAdd(
                                mapOf(
                                    "prototypeRef" to JsonPrimitive(ref),
                                    "logDate" to JsonPrimitive(today),
                                    "stageName" to JsonPrimitive(last),
                                )
                            )
                        },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Again: $last") }
                }
            }
        }
    }
}

/** C8: the linked questionnaire sittings, counted and summarised — never copied in as rows. */
@Composable
internal fun DwSittingsCard(sittings: List<DwAssistSittingDto>) {
    if (sittings.isEmpty()) return
    val total = sittings.sumOf { it.count }
    CaptureCard("Questionnaire sittings · $total") {
        sittings.forEach { s ->
            Text(
                "${s.title} — ${s.count} ${if (s.count == 1) "sitting" else "sittings"}${s.latest?.let { " · latest $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text("Counted in “Responses collected”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
    }
}

/** B11: one row per selected photo; each is numbered (C6) and left unnamed until named. */
@Composable
internal fun DwRowsFromPhotosCard(
    title: String,
    noun: String,
    enabled: Boolean,
    onPicked: (List<Uri>) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    CaptureCard("$title: one row per photo") {
        ActionRow {
            OutlinedButton(
                onClick = { picker.launch("image/*") },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Add from photos") }
        }
        Text("Each $noun gets its own numbered row. Name each one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
    }
}

/** C7/C8: a count the stage works out for itself, where the typed box used to be. */
@Composable
internal fun DwComputedCount(field: FieldDto, value: JsonElement?) {
    val shown = dwDisplayText(value).ifBlank { "—" }
    val source = when (field.computedKind) {
        "ROSTER_COUNT", "ROSTER_WOMEN" -> "Counted from the roster."
        "ATTENDANCE_DAYS" -> "From the attendance ticks."
        "ROSTER_COMPLETED" -> "Present on every day."
        "SURVEY_RESPONSES" -> "Responses plus questionnaire sittings."
        else -> "Worked out from the stage."
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(field.label, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Text(
            if (shown != "—" && field.unit.isNotBlank()) "$shown ${field.unit}" else shown,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Text(source, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** C10: "Use from the sketch" while a mapped box is blank; the intended size is shown, never copied. */
@Composable
internal fun DwRefSuggestion(
    entity: EntityDto,
    field: FieldDto,
    rowValues: Map<String, JsonElement>,
    services: DwFieldServices?,
    enabled: Boolean,
    onPatch: (Map<String, JsonElement?>) -> Unit,
) {
    val suggest = field.refSuggest ?: return
    val id = dwDisplayText(rowValues[field.key])
    if (id.isBlank() || services == null) return
    val context = LocalContext.current
    var source by remember(id) { mutableStateOf<Map<String, JsonElement>?>(null) }
    LaunchedEffect(id, field.refModel) {
        services.repository.designWorkshopReferences(
            context = context.applicationContext,
            workshopId = services.workshopId,
            model = field.refModel,
            scope = "WORKSHOP",
            filterValue = "",
        ) { list, _ ->
            list.items.firstOrNull { it.id == id }?.let { source = it.data }
        }
    }
    val data = source ?: return
    fun blank(value: JsonElement?) = value == null || value is JsonNull || dwDisplayText(value).isBlank() ||
        (value is JsonArray && value.isEmpty())
    val offered = suggest.fill.mapNotNull { (from, to) ->
        data[from]?.takeIf { !blank(it) && blank(rowValues[to]) }?.let { to to it }
    }.toMap()
    val hints = suggest.hint.keys.mapNotNull { key -> data[key]?.takeIf { !blank(it) }?.let { dwDisplayText(it) } }
    if (offered.isEmpty() && hints.isEmpty()) return
    val noun = when (field.refModel) { "DwSketch" -> "sketch"; "DwPrototype" -> "prototype"; else -> "record" }
    val parts = offered.map { (key, value) ->
        if (key == "artisanRef") "the same maker"
        else "${entity.fields.firstOrNull { it.key == key }?.label?.lowercase() ?: key} ${dwDisplayText(value)}"
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (parts.isNotEmpty()) Text("From the $noun: ${parts.joinToString("; ")}.", style = MaterialTheme.typography.bodySmall)
        if (hints.isNotEmpty()) {
            Text("Intended size ${hints.joinToString(" × ")} cm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        }
        if (offered.isNotEmpty()) {
            ActionRow {
                OutlinedButton(
                    onClick = { onPatch(offered) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Use from the $noun") }
            }
        }
    }
}

/**
 * C12: the venue location was left empty on a stage 1 save. A photo taken at the venue fills it in
 * one tap; the box itself is in the form below.
 */
@Composable
internal fun DwVenuePrompt(onUse: (LocationRequest) -> Unit) {
    CaptureCard("Add the venue location") {
        Text("The artisan list can't be imported without it.", style = MaterialTheme.typography.bodyMedium)
        DwLocationFromPhoto(enabled = true, onUse = onUse)
    }
}

/** A21: the position and time a photo carries, read from its EXIF; null when it has none. */
internal data class DwPhotoPosition(val latitude: Double, val longitude: Double, val takenAt: String?)

internal fun dwPhotoPosition(context: android.content.Context, uri: Uri): DwPhotoPosition? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val exif = android.media.ExifInterface(stream)
        val latLong = FloatArray(2)
        if (!exif.getLatLong(latLong)) return@use null
        val lat = latLong[0].toDouble()
        val lon = latLong[1].toDouble()
        if (lat == 0.0 && lon == 0.0) return@use null
        DwPhotoPosition(lat, lon, dwExifTime(exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)))
    }
}.getOrNull()

/** "2026:10:12 10:42:05" as "12 Oct 2026, 10:42", or null. Same wording as the web's. */
internal fun dwExifTime(raw: String?): String? {
    val match = Regex("^(\\d{4})[:-](\\d{2})[:-](\\d{2})[ T](\\d{2}):(\\d{2})").find(raw.orEmpty()) ?: return null
    val (y, m, d, hh, mm) = match.destructured
    val month = runCatching {
        LocalDate.of(y.toInt(), m.toInt(), 1).format(DateTimeFormatter.ofPattern("MMM", Locale.forLanguageTag("en-IN")))
    }.getOrNull() ?: return null
    return "${d.toInt()} $month $y, $hh:$mm"
}

/** A21 on the handset: pick a photo, read where it was taken, apply it on a tap. */
@Composable
internal fun DwLocationFromPhoto(enabled: Boolean, onUse: (LocationRequest) -> Unit) {
    val context = LocalContext.current
    var found by remember { mutableStateOf<DwPhotoPosition?>(null) }
    var none by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val position = dwPhotoPosition(context, uri)
            found = position
            none = position == null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ActionRow {
            OutlinedButton(
                onClick = { picker.launch("image/*") },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("From a photo") }
        }
        if (none) Text("That photo has no location.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        found?.let { position ->
            CaptureLine(
                title = String.format(Locale.ROOT, "%.5f, %.5f", position.latitude, position.longitude),
                detail = position.takenAt?.let { "Taken $it" }.orEmpty(),
            ) {
                OutlinedButton(
                    onClick = {
                        onUse(LocationRequest(latitude = position.latitude, longitude = position.longitude))
                        found = null
                    },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Use this location") }
            }
        }
    }
}

/** Apply one suggestion to the screen's state: a FILL into blank boxes, or ADD_ROWS appended. */
internal fun dwApplySuggestion(
    suggestion: DwAssistSuggestionDto,
    singleton: Map<String, JsonElement>,
    rows: List<Pair<String, Map<String, JsonElement>>>,
    newRowId: () -> String,
): Pair<Map<String, JsonElement>?, List<Pair<String, Map<String, JsonElement>>>?> {
    if (suggestion.kind == "ADD_ROWS") {
        return null to (rows + suggestion.rows.map { newRowId() to it })
    }
    if (suggestion.rowKey == null) {
        return (singleton + DwStageAssist.fillValues(suggestion, singleton)) to null
    }
    return null to rows.map { (id, values) ->
        if (suggestion.rowKey in dwRowKeys(values, id)) id to (values + DwStageAssist.fillValues(suggestion, values)) else id to values
    }
}
