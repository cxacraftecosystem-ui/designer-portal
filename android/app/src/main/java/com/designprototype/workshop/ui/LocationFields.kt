package com.designprototype.workshop.ui

import android.content.Context
import android.location.Geocoder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import com.designprototype.workshop.report.INDIAN_STATES_AND_UNION_TERRITORIES
import com.designprototype.workshop.PINCODE_LENGTH
import com.designprototype.workshop.data.AddressReferenceDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.LocationRequest
import com.designprototype.workshop.matchIndianState
import com.designprototype.workshop.pincodeValidationError
import com.designprototype.workshop.sameCoordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

const val LOCATION_META_DISTRICT: String = "district"
const val LOCATION_META_VILLAGE: String = "village"

const val LOCATION_META_ARTISAN_LAT: String = "artisanLatitude"
const val LOCATION_META_ARTISAN_LNG: String = "artisanLongitude"

private fun LocationRequest.meta(key: String): String =
    (extraMetadata?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

private fun LocationRequest.withMeta(vararg pairs: Pair<String, JsonPrimitive?>): LocationRequest {
    val merged = LinkedHashMap<String, JsonElement>(extraMetadata.orEmpty())
    pairs.forEach { (key, value) -> if (value == null) merged.remove(key) else merged[key] = value }
    return copy(extraMetadata = if (merged.isEmpty()) null else JsonObject(merged))
}

private fun metaText(value: String): JsonPrimitive? =
    value.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }

private data class StatedPlace(
    val state: String = "",
    val district: String = "",
    val village: String = "",
    val pincode: String = "",
    val pinLat: String = "",
    val pinLng: String = ""
) {
    val isEmpty: Boolean
        get() = state.isBlank() && district.isBlank() && village.isBlank() && pincode.isBlank() &&
            pinLat.isBlank() && pinLng.isBlank()
}

private fun LocationRequest.statedPlace(): StatedPlace = StatedPlace(
    state = state.orEmpty(),
    district = district?.trim().orEmpty().ifEmpty { meta(LOCATION_META_DISTRICT) },
    village = village?.trim().orEmpty().ifEmpty { meta(LOCATION_META_VILLAGE) },
    pincode = pincode.orEmpty(),
    pinLat = subjectLatitude?.let { trimCoordinate(it) } ?: meta(LOCATION_META_ARTISAN_LAT),
    pinLng = subjectLongitude?.let { trimCoordinate(it) } ?: meta(LOCATION_META_ARTISAN_LNG)
)

private fun LocationRequest.withStatedPlace(place: StatedPlace): LocationRequest {
    val pin = place.pinLat.trim().toDoubleOrNull()?.let { lat ->
        place.pinLng.trim().toDoubleOrNull()?.let { lng -> lat to lng }
    }
    return copy(
        state = place.state.ifBlank { null },
        district = place.district.trim().ifBlank { null },
        village = place.village.trim().ifBlank { null },
        pincode = place.pincode.ifBlank { null },
        subjectLatitude = pin?.first,
        subjectLongitude = pin?.second
    ).withMeta(
        LOCATION_META_DISTRICT to metaText(place.district),
        LOCATION_META_VILLAGE to metaText(place.village),
        LOCATION_META_ARTISAN_LAT to pin?.let { JsonPrimitive(it.first) },
        LOCATION_META_ARTISAN_LNG to pin?.let { JsonPrimitive(it.second) }
    )
}

private const val REFERENCE_CACHE_FILE = "address-reference.json"

private const val REFERENCE_CACHE_STAMP_FILE = "address-reference-fetched-at.txt"

internal val BUNDLED_STATES: List<String> = INDIAN_STATES_AND_UNION_TERRITORIES

private val referenceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal object AddressReferenceCache {

    fun read(context: Context): AddressReferenceDto? = runCatching {
        val file = File(context.filesDir, REFERENCE_CACHE_FILE)
        if (!file.exists()) return null
        referenceJson.decodeFromString(AddressReferenceDto.serializer(), file.readText())
    }.getOrNull()

    fun write(context: Context, value: AddressReferenceDto): Boolean = runCatching {
        val file = File(context.filesDir, REFERENCE_CACHE_FILE)
        val encoded = referenceJson.encodeToString(AddressReferenceDto.serializer(), value)
        if (file.exists()) {
            val existing = read(context)
            if (existing != null && existing.version == value.version) return false
            if (file.readText() == encoded) return false
        }
        file.writeText(encoded)
        true
    }.getOrDefault(false)

    fun readFetchedAt(context: Context): String? = runCatching {
        val file = File(context.filesDir, REFERENCE_CACHE_STAMP_FILE)
        if (!file.exists()) return null
        file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun stamp(context: Context, at: String = Instant.now().toString()) {
        runCatching { File(context.filesDir, REFERENCE_CACHE_STAMP_FILE).writeText(at) }
    }
}

internal const val ADDRESS_REFERENCE_ASSET = "address-reference.json"

internal object AddressReferenceBaseline {

    @Volatile
    private var loaded: AddressReferenceDto? = null

    fun read(context: Context): AddressReferenceDto? = loaded ?: runCatching {
        context.assets.open(ADDRESS_REFERENCE_ASSET).bufferedReader(Charsets.UTF_8).use { decode(it.readText()) }
    }.getOrNull()?.also { loaded = it }

    fun decode(text: String): AddressReferenceDto? = runCatching {
        referenceJson.decodeFromString(AddressReferenceDto.serializer(), text)
    }.getOrNull()?.takeIf { it.statesAndUnionTerritories.isNotEmpty() || it.states.isNotEmpty() }
}

internal data class StartingReference(val reference: AddressReferenceDto, val bundled: Boolean)

internal fun chooseAddressReference(
    cached: AddressReferenceDto?,
    bundled: AddressReferenceDto?
): StartingReference? {
    if (bundled == null) return cached?.let { StartingReference(it, bundled = false) }
    if (cached == null) return StartingReference(bundled, bundled = true)
    val cachedHasDistricts = !cached.districts?.byState.isNullOrEmpty()
    val bundledHasDistricts = !bundled.districts?.byState.isNullOrEmpty()
    return when {
        bundled.version > cached.version -> StartingReference(bundled, bundled = true)
        bundled.version == cached.version && bundledHasDistricts && !cachedHasDistricts ->
            StartingReference(bundled, bundled = true)
        else -> StartingReference(cached, bundled = false)
    }
}

@Immutable
data class AddressReferenceState(
    val reference: AddressReferenceDto = AddressReferenceDto(),
    val fetchedAt: String? = null,
    val loading: Boolean = true,
    val servedThisSession: Boolean = false,
    val online: Boolean = false,
    val bundled: Boolean = false
)

@Composable
fun rememberAddressReference(repository: WorkshopRepository): AddressReferenceState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(AddressReferenceState()) }
    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { AddressReferenceCache.read(context) }
        val stamp = withContext(Dispatchers.IO) { AddressReferenceCache.readFetchedAt(context) }
        val bundled = withContext(Dispatchers.IO) { AddressReferenceBaseline.read(context) }
        chooseAddressReference(cached, bundled)?.let { start ->
            state = state.copy(
                reference = start.reference,
                fetchedAt = if (start.bundled) null else stamp,
                bundled = start.bundled
            )
        }
        val fresh = runCatching { repository.addressReference() }
        val payload = fresh.getOrNull()
        if (payload == null) {
            val cause = fresh.exceptionOrNull()
            state = state.copy(
                loading = false,
                online = cause != null && !repository.isTransient(cause)
            )
            return@LaunchedEffect
        }
        if (payload.statesAndUnionTerritories.isEmpty() && payload.states.isEmpty()) {
            state = state.copy(loading = false)
            return@LaunchedEffect
        }
        val now = Instant.now().toString()
        state = AddressReferenceState(
            reference = payload,
            fetchedAt = now,
            loading = false,
            servedThisSession = true,
            online = true
        )
        withContext(Dispatchers.IO) {
            AddressReferenceCache.write(context, payload)
            AddressReferenceCache.stamp(context, now)
        }
    }
    return state
}

private fun requiredLabel(label: String, required: Boolean): String =
    if (required) "$label *" else label

internal fun addressListNotice(
    noun: String,
    rows: Int,
    state: AddressReferenceState,
    bundled: Boolean = false
): String? = when {
    rows > 0 && state.servedThisSession -> null
    rows > 0 && bundled -> BUNDLED_LIST_HAS_NO_SENTENCE
    rows > 0 -> state.fetchedAt
        ?.let { readableStamp(it) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { cachedListLine(rows, noun, it) }
    state.loading -> loadingListLine(noun)
    state.online -> couldNotListLine(noun)
    else -> offlineListLine(noun)
}

private fun stateOptions(current: String, reference: AddressReferenceDto): List<Pair<String, String>> {
    val served = reference.statesAndUnionTerritories
        .ifEmpty { reference.states }
        .ifEmpty { BUNDLED_STATES }
    val known = served.any { it.equals(current, ignoreCase = true) }
    val all = if (current.isNotBlank() && !known) listOf(current) + served else served
    return all.map { it to it }
}

private fun districtOptions(
    state: String,
    current: String,
    reference: AddressReferenceDto
): List<Pair<String, String>> {
    val served = reference.districts?.byState?.get(state).orEmpty()
    val known = served.any { it.equals(current, ignoreCase = true) }
    val all = if (current.isNotBlank() && !known) listOf(current) + served else served
    return all.map { it to it }
}

private const val GEOCODE_ACCURACY_LIMIT_METRES = 1000.0

private const val LOOKUP_DEBOUNCE_MS = 400L

private val DISTRICT_SUFFIXES = listOf("district", "districts", "zila", "zilla", "jila", "jilla")

private fun foldName(value: String): String =
    value.lowercase(Locale.UK).replace("&", "and").filter { it in 'a'..'z' || it in '0'..'9' }

internal fun normaliseDistrictName(raw: String?): String {
    var name = raw.orEmpty().trim().trim(',', '.', '-').trim()
    for (suffix in DISTRICT_SUFFIXES) {
        if (name.length > suffix.length + 1 && name.lowercase(Locale.UK).endsWith(" $suffix")) {
            name = name.dropLast(suffix.length + 1).trim()
            break
        }
    }
    return name
}

private fun matchDistrict(text: String, districts: List<String>): String {
    val wanted = foldName(normaliseDistrictName(text))
    if (wanted.isEmpty()) return ""
    districts.firstOrNull { foldName(it) == wanted }?.let { return it }
    return districts.firstOrNull { entry ->
        val name = foldName(entry)
        (name.length >= 5 && wanted.contains(name)) || (wanted.length >= 5 && name.contains(wanted))
    }.orEmpty()
}

private fun usablePincode(text: String?): String {
    val digits = text.orEmpty().filter { it in '0'..'9' }
    return if (pincodeValidationError(digits) == null) digits else ""
}

private data class PlaceSuggestion(
    val state: String = "",
    val district: String = "",
    val pincode: String = ""
) {
    val isEmpty: Boolean get() = state.isBlank() && district.isBlank() && pincode.isBlank()
}

private enum class PlaceField(val label: String) {
    State("state"),
    District("district"),
    Pincode("pincode")
}

private fun fieldNames(fields: List<PlaceField>): String = when (fields.size) {
    0 -> ""
    1 -> fields.first().label
    else -> fields.dropLast(1).joinToString(", ") { it.label } + " and " + fields.last().label
}

private enum class PlaceIntent {
    Explicit,

    Passive
}

private data class AppliedWrite(
    val fields: List<PlaceField>,
    val previous: PlaceSuggestion,
    val intent: PlaceIntent
)

private fun StatedPlace.geocodable(): PlaceSuggestion = PlaceSuggestion(state, district, pincode)

private fun PlaceSuggestion.crediting(fields: List<PlaceField>, values: PlaceSuggestion) = PlaceSuggestion(
    state = if (PlaceField.State in fields) values.state else state,
    district = if (PlaceField.District in fields) values.district else district,
    pincode = if (PlaceField.Pincode in fields) values.pincode else pincode
)

private suspend fun geocode(context: Context, lat: Double, lng: Double): Triple<String?, String?, String?> =
    withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext Triple(null, null, null)
        runCatching {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.UK).getFromLocation(lat, lng, 1)
            val first = results?.firstOrNull() ?: return@runCatching Triple(null, null, null)
            Triple(first.adminArea, first.subAdminArea, first.postalCode)
        }.getOrDefault(Triple(null, null, null))
    }

private fun coarseRadius(accuracy: Double?): Double? =
    accuracy?.takeIf { it > GEOCODE_ACCURACY_LIMIT_METRES }

private suspend fun suggestPlaceFor(
    context: Context,
    lat: Double,
    lng: Double,
    accuracy: Double?,
    reference: AddressReferenceDto
): PlaceSuggestion {
    if (coarseRadius(accuracy) != null) return PlaceSuggestion()
    val (adminArea, subAdminArea, postal) = geocode(context, lat, lng)
    val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
    val state = adminArea?.let { matchIndianState(it, served) }.orEmpty()
    val district = if (state.isBlank()) {
        ""
    } else {
        matchDistrict(subAdminArea.orEmpty(), reference.districts?.byState?.get(state).orEmpty())
    }
    return PlaceSuggestion(state = state, district = district, pincode = usablePincode(postal))
}

private fun trimCoordinate(value: Double): String = String.format(Locale.UK, "%.6f", value)

private fun radiusLabel(metres: Double): String =
    if (metres < 1000) "±${Math.round(metres)} m" else "±${String.format(Locale.UK, "%.1f", metres / 1000)} km"

private fun isoNow(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.UK).format(Date())

@Composable
private fun GroupHeading(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(subtitle, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GroupNotice(warn: Boolean, text: String) {
    val container = if (warn) MaterialTheme.field.warningContainer else MaterialTheme.field.surface100
    val ink = if (warn) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted
    Text(
        text,
        color = ink,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
}

@Immutable
data class LocationSubject(
    val heading: String,
    val subtitle: String,
    val label: String,
    val possessive: String,
    val pinLabel: String
)

val ARTISAN_LOCATION_SUBJECT: LocationSubject = LocationSubject(
    heading = "Artisan location",
    subtitle = "Where the artisan works — used for the map and exports. A pin fills state, district and pincode; GPS fills only blanks.",
    label = "the artisan",
    possessive = "the artisan's",
    pinLabel = "Artisan pin"
)

val DESIGNER_LOCATION_SUBJECT: LocationSubject = LocationSubject(
    heading = "Where you are based",
    subtitle = "Where you work. Not captured automatically.",
    label = "the designer",
    possessive = "the designer's",
    pinLabel = "Designer pin"
)

fun artisanLocationRequirementError(value: LocationRequest?): String? {
    val place = value?.statedPlace() ?: return LOCATION_REQUIRED_MESSAGE
    if (place.state.isBlank()) return "Choose the artisan's state under 'Artisan location'."
    if (place.district.isBlank()) return "Choose the artisan's district under 'Artisan location'."
    return null
}

@Composable
fun LocationFieldsSection(
    repository: WorkshopRepository,
    value: LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = true,
    isEdit: Boolean = false,
    showRequirementError: Boolean = false,
    subject: LocationSubject = ARTISAN_LOCATION_SUBJECT,
    referenceState: AddressReferenceState = rememberAddressReference(repository),
    onStatedAddressNeedsCoordinate: (Boolean) -> Unit = {},
    village: String? = null,
    onVillageChange: ((String) -> Unit)? = null,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reference = referenceState.reference
    var parked by remember { mutableStateOf(StatedPlace()) }
    var expanded by remember { mutableStateOf(false) }
    var pincodeProblemShown by remember { mutableStateOf(false) }
    var showArtisanMap by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(PlaceSuggestion()) }
    var autofill by remember { mutableStateOf<AppliedWrite?>(null) }
    var coarseFixMetres by remember { mutableStateOf<Double?>(null) }
    var offer by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var offerPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var appliedNowStale by remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(value)
    val lookup = remember { mutableStateOf<Job?>(null) }
    var lookupIntent by remember { mutableStateOf(PlaceIntent.Passive) }
    var coordinateState by remember { mutableStateOf("") }
    var sessionPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val place = value?.statedPlace() ?: parked
    val pincodeProblem = pincodeValidationError(place.pincode)
    val showPincodeProblem = pincodeProblem != null &&
        (pincodeProblemShown || place.pincode.length == PINCODE_LENGTH)
    val districts = districtOptions(place.state, place.district, reference)

    val stateRows = remember(place.state, reference) {
        stateOptions(place.state, reference).asSelectOptions()
    }
    val districtRows = remember(districts) { districts.asSelectOptions() }

    val stateRequired = required && !isEdit && listIsAnswerable(stateRows)
    val districtRequired = required && !isEdit && listIsAnswerable(districtRows)

    val stateNotice = addressListNotice(
        "states",
        stateRows.size,
        referenceState,
        bundled = referenceState.bundled || (
            referenceState.reference.statesAndUnionTerritories.isEmpty() &&
                referenceState.reference.states.isEmpty()
            )
    )
    val districtNotice = when {
        place.state.isBlank() -> null
        districtRows.isNotEmpty() ->
            addressListNotice("districts", districtRows.size, referenceState, bundled = referenceState.bundled)
        reference.statesAndUnionTerritories.isNotEmpty() || reference.states.isNotEmpty() ->
            "Districts for ${place.state} aren't downloaded. Connect once to save them offline." +
                if (required && !isEdit) {
                    " New records need a district; existing ones can still be saved."
                } else {
                    ""
                }
        else -> addressListNotice("districts", districtRows.size, referenceState)
    }

    val statedAddressNeedsCoordinate = value == null && !place.isEmpty
    val reportNeedsCoordinate = rememberUpdatedState(onStatedAddressNeedsCoordinate)
    LaunchedEffect(statedAddressNeedsCoordinate) {
        reportNeedsCoordinate.value(statedAddressNeedsCoordinate)
    }

    fun setPlace(next: StatedPlace) {
        appliedNowStale = false
        autofill = null
        val current = latest.value
        if (current == null) parked = next else onChange(current.withStatedPlace(next))
    }

    fun emitCoordinate(next: LocationRequest?) {
        if (next == null) {
            parked = place
            sessionPoint = null
            onChange(null)
            return
        }
        sessionPoint = next.latitude to next.longitude
        val moved = !sameCoordinate(next, value)
        onChange(
            next
                .copy(capturedAt = if (moved) isoNow() else value?.capturedAt)
                .withStatedPlace(place)
        )
    }

    fun applyPlace(fresh: PlaceSuggestion, intent: PlaceIntent): AppliedWrite {
        val held = latest.value?.statedPlace() ?: parked
        val previous = held.geocodable()
        val overwrite = intent == PlaceIntent.Explicit
        val touched = mutableListOf<PlaceField>()
        var next = held

        if (fresh.state.isNotBlank() && fresh.state != previous.state &&
            (overwrite || previous.state.isBlank())
        ) {
            next = next.copy(state = fresh.state)
            touched += PlaceField.State
        }
        val stateAgrees = fresh.state.isNotBlank() && next.state.equals(fresh.state, ignoreCase = true)
        val district = if (stateAgrees) fresh.district else ""
        val contradicted = next.state.isNotBlank() && fresh.state.isNotBlank() && !stateAgrees
        val pincode = if (contradicted) "" else fresh.pincode

        if (district != previous.district && (overwrite || previous.district.isBlank()) &&
            (district.isNotBlank() || overwrite)
        ) {
            next = next.copy(district = district)
            touched += PlaceField.District
        }
        if (pincode != previous.pincode && (overwrite || previous.pincode.isBlank()) &&
            (pincode.isNotBlank() || overwrite)
        ) {
            next = next.copy(pincode = pincode)
            touched += PlaceField.Pincode
        }

        val write = AppliedWrite(fields = touched.toList(), previous = previous, intent = intent)
        if (touched.isEmpty()) return write
        applied = applied.crediting(touched, next.geocodable())
        pincodeProblemShown = false
        setPlace(next)
        autofill = write
        return write
    }

    fun undoAutofill() {
        val write = autofill ?: return
        val held = latest.value?.statedPlace() ?: parked
        applied = applied.crediting(write.fields, PlaceSuggestion())
        setPlace(
            held.copy(
                state = write.previous.state,
                district = write.previous.district,
                pincode = write.previous.pincode
            )
        )
    }

    fun lookupPlace(lat: Double, lng: Double, accuracy: Double?, intent: PlaceIntent) {
        val running = lookup.value
        val explicitInFlight = lookupIntent == PlaceIntent.Explicit && running?.isActive == true
        if (intent == PlaceIntent.Passive && explicitInFlight) return
        running?.cancel()
        lookupIntent = intent
        offer = null
        offerPoint = null
        coarseFixMetres = coarseRadius(accuracy)
        if (coarseFixMetres != null) return

        lookup.value = scope.launch {
            delay(LOOKUP_DEBOUNCE_MS)
            val fresh = suggestPlaceFor(context, lat, lng, accuracy, reference)

            when (intent) {
                PlaceIntent.Explicit -> {
                    val held = latest.value?.statedPlace() ?: parked
                    if (held.pinLat != trimCoordinate(lat) || held.pinLng != trimCoordinate(lng)) {
                        return@launch
                    }
                }

                PlaceIntent.Passive -> {
                    val current = latest.value ?: return@launch
                    val looked = LocationRequest(latitude = lat, longitude = lng)
                    if (!sameCoordinate(looked, current)) return@launch
                }
            }

            if (fresh.isEmpty) {
                val held = latest.value?.statedPlace() ?: parked
                val stale = !applied.isEmpty && (
                    (applied.state.isNotBlank() && held.state == applied.state) ||
                        (applied.district.isNotBlank() && held.district == applied.district) ||
                        (applied.pincode.isNotBlank() && held.pincode == applied.pincode)
                    )
                appliedNowStale = stale
                if (stale) autofill = null
                if (intent == PlaceIntent.Explicit) {
                    onMessage("No address for that pin — set state and district yourself.")
                }
                return@launch
            }

            appliedNowStale = false
            val write = applyPlace(fresh, intent)
            if (intent == PlaceIntent.Explicit) return@launch
            val previous = write.previous
            val leftOver = (fresh.state.isNotBlank() && fresh.state != previous.state &&
                PlaceField.State !in write.fields) ||
                (fresh.district.isNotBlank() && fresh.district != previous.district &&
                    PlaceField.District !in write.fields) ||
                (fresh.pincode.isNotBlank() && fresh.pincode != previous.pincode &&
                    PlaceField.Pincode !in write.fields)
            if (leftOver) {
                offer = fresh
                offerPoint = lat to lng
            }
        }
    }

    fun acceptOffer(fresh: PlaceSuggestion) {
        offer = null
        offerPoint = null
        applyPlace(fresh, PlaceIntent.Explicit)
    }

    fun clearApplied() {
        val held = latest.value?.statedPlace() ?: parked
        setPlace(
            held.copy(
                state = if (held.state == applied.state) "" else held.state,
                district = if (held.district == applied.district) "" else held.district,
                pincode = if (held.pincode == applied.pincode) "" else held.pincode
            )
        )
        applied = PlaceSuggestion()
    }

    LaunchedEffect(value?.latitude, value?.longitude, reference.version) {
        coordinateState = ""
        val current = value ?: return@LaunchedEffect
        if (sessionPoint == current.latitude to current.longitude) return@LaunchedEffect
        val metres = current.accuracy
        if (metres != null && metres > GEOCODE_ACCURACY_LIMIT_METRES) return@LaunchedEffect
        val (adminArea, _, _) = geocode(context, current.latitude, current.longitude)
        val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
        coordinateState = adminArea?.let { matchIndianState(it, served) }.orEmpty()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        GroupHeading(title = subject.heading, subtitle = subject.subtitle)

        val written = autofill
        if (written != null) {
            val source = when (written.intent) {
                PlaceIntent.Explicit -> "Filled in from the place you pointed at: "
                PlaceIntent.Passive -> "Filled from this device's location (empty boxes only): "
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    source + fieldNames(written.fields) + ". Check and correct if needed.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    OutlinedButton(
                        onClick = { undoAutofill() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Undo") }
                }
            }
        }

        val pending = offer
        val pendingPoint = offerPoint
        if (pending != null && pendingPoint != null) {
            val named = listOfNotNull(
                pending.district.takeIf { it.isNotBlank() },
                pending.state.takeIf { it.isNotBlank() },
                pending.pincode.takeIf { it.isNotBlank() }
            ).joinToString(", ")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "This device is at ${trimCoordinate(pendingPoint.first)}, ${trimCoordinate(pendingPoint.second)}" +
                        (value?.accuracy?.let { " (${radiusLabel(it)})" } ?: "") +
                        " ($named). Use as ${subject.possessive} location?",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    OutlinedButton(
                        onClick = { acceptOffer(pending) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Use $named") }
                    TextButton(
                        onClick = { offer = null; offerPoint = null },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Not here") }
                }
            }
        }

        if (appliedNowStale) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "No place name for the new point. State, district and pincode still describe the previous one — check them.",
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    OutlinedButton(
                        onClick = { clearApplied() },
                        modifier = Modifier.destructiveAction().heightIn(min = 48.dp)
                    ) { Text("Clear the copied answers") }
                }
            }
        }

        val coarse = coarseFixMetres
        if (coarse != null && (place.state.isBlank() || place.district.isBlank())) {
            GroupNotice(
                warn = true,
                text = "Fix too imprecise (${radiusLabel(coarse)}) to read an address. Choose state and district, or drop a pin."
            )
        }

        SearchableSelectField(
            label = requiredLabel("State / union territory", stateRequired),
            options = stateRows,
            selectedValue = place.state,
            enabled = listIsAnswerable(stateRows),
            placeholder = "Select state",
            emptyMessage = stateNotice,
            searchable = true,
            onSelect = { next ->
                val keepDistrict = next.equals(place.state, ignoreCase = true)
                setPlace(place.copy(state = next, district = if (keepDistrict) place.district else ""))
            }
        )
        stateNotice?.takeIf { listIsAnswerable(stateRows) }?.let { line ->
            Text(line, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }

        SearchableSelectField(
            label = requiredLabel("District", districtRequired),
            options = districtRows,
            selectedValue = place.district,
            enabled = place.state.isNotBlank() && listIsAnswerable(districtRows),
            placeholder = when {
                place.state.isBlank() -> "Choose a state first"
                else -> "Select district"
            },
            emptyMessage = districtNotice,
            searchable = true,
            onSelect = { setPlace(place.copy(district = it)) }
        )
        districtNotice?.takeIf { place.state.isNotBlank() && listIsAnswerable(districtRows) }?.let { line ->
            Text(line, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = village ?: place.village,
                onValueChange = { next ->
                    if (onVillageChange != null) onVillageChange(next) else setPlace(place.copy(village = next))
                },
                label = { Text("Village / place") },
                supportingText = {
                    Text(
                        "Just the village or town name.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = place.pincode,
                onValueChange = { input ->
                    pincodeProblemShown = false
                    setPlace(place.copy(pincode = input.filter { it in '0'..'9' }.take(PINCODE_LENGTH)))
                },
                label = { Text("Pincode (optional)") },
                placeholder = { Text("303007") },
                isError = showPincodeProblem,
                supportingText = {
                    val shown = pincodeProblem?.takeIf { showPincodeProblem }
                    Text(
                        shown ?: "Six digits, if known.",
                        color = if (shown != null) MaterialTheme.colorScheme.error else MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (!focus.isFocused && place.pincode.isNotEmpty()) pincodeProblemShown = true
                    }
            )
        }

        if (place.pinLat.isNotBlank() && place.pinLng.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${subject.pinLabel}: ${place.pinLat}, ${place.pinLng}",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { setPlace(place.copy(pinLat = "", pinLng = "")) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Remove pin") }
            }
        }
        OutlinedButton(
            onClick = { showArtisanMap = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(
                if (place.pinLat.isBlank()) {
                    "Pin ${subject.possessive} location on the map (optional)"
                } else {
                    "Move ${subject.possessive} pin"
                }
            )
        }

        if (statedAddressNeedsCoordinate) {
            GroupNotice(
                warn = true,
                text = "Add a location below (GPS, pin or coordinates) — the address saves with it."
            )
        }

        if (coordinateState.isNotBlank() && place.state.isNotBlank() &&
            !coordinateState.equals(place.state, ignoreCase = true)
        ) {
            GroupNotice(
                warn = true,
                text = "Coordinates are in $coordinateState but the location says ${place.state}. Fine if written up elsewhere; correct whichever is wrong."
            )
        } else if (coordinateState.isNotBlank() && place.state.isBlank()) {
            GroupNotice(
                warn = true,
                text = "Coordinates are in $coordinateState but no location is stated. Add where ${subject.label} is above."
            )
        }

        HorizontalDivider(color = MaterialTheme.field.hairline)

        val stamp = readableStamp(value?.capturedAt)
        val summary = when {
            value == null && required -> "not captured yet"
            value == null -> "not captured"
            else -> trimCoordinate(value.latitude) + ", " + trimCoordinate(value.longitude) +
                (value.accuracy?.let { " · ${radiusLabel(it)}" } ?: "") +
                (if (stamp.isNotEmpty()) " · $stamp" else "")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp))
                .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Captured at. $summary. Device location, not ${subject.possessive}."
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    heading()
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Captured at",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(summary, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (expanded) "Hide" else "Show",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }

        if (required && value == null && showRequirementError && !expanded) {
            Text(LOCATION_REQUIRED_MESSAGE, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                "Where this device was when the record was written. Filled automatically." +
                    when {
                        value == null -> ""
                        stamp.isNotEmpty() -> " Taken $stamp."
                        else -> " No timestamp."
                    },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LocationCaptureCard(
            value = value,
            onChange = { next ->
                val hadCoordinate = value != null
                emitCoordinate(next)
                if (next != null && (!hadCoordinate || !sameCoordinate(next, value))) {
                    lookupPlace(next.latitude, next.longitude, next.accuracy, PlaceIntent.Passive)
                }
            },
            required = required,
            isEdit = isEdit,
            showRequirementError = showRequirementError,
            title = if (required) "Captured coordinates *" else "Captured coordinates",
            description = "A GPS fix, pin or typed coordinates.",
            collapsed = !expanded,
            onNeedsAttention = { if (it) expanded = true },
            onMessage = onMessage
        )
    }

    if (showArtisanMap) {
        MapPickerDialog(
            initialLat = place.pinLat.toDoubleOrNull() ?: value?.latitude,
            initialLng = place.pinLng.toDoubleOrNull() ?: value?.longitude,
            onDismiss = { showArtisanMap = false },
            onPick = { lat, lng ->
                setPlace(place.copy(pinLat = trimCoordinate(lat), pinLng = trimCoordinate(lng)))
                onMessage("${subject.pinLabel} set: ${trimCoordinate(lat)}, ${trimCoordinate(lng)}")
                showArtisanMap = false
                lookupPlace(lat, lng, accuracy = null, intent = PlaceIntent.Explicit)
            }
        )
    }
}
