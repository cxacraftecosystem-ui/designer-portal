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
// The 36 states, derived from the report map's seat table rather than typed out again — see
// BUNDLED_STATES for why a second copy of these names is the thing to avoid.
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

/*
 * ---------------------------------------------------------------------------
 * TWO LOCATIONS, NOT ONE.
 *
 * THE FINDING. All fifteen artisans on the live database that carry a location sit inside three
 * hundred metres of each other at 22.31 N, 87.31 E — Kharagpur, West Bengal. The places the
 * researchers TYPED are Bagru, Balotra, Kutch, Rudraprayag, Ballupur, Sanganer and Kappaladoddi:
 * Rajasthan, Gujarat, Uttarakhand, Andhra Pradesh. Fifteen hundred kilometres out, every one.
 *
 * The GPS was not broken. The coordinates jitter naturally and carry honest accuracy radii from
 * 26 m to 2.5 km, which is what a real receiver produces. What was broken is what the number MEANT:
 * the field recorded where the DEVICE was when the record was saved, and every reader — the map,
 * the exports, the research dataset — read it as where the ARTISAN is. The pilot data was entered
 * at a desk in Kharagpur about artisans a long way away, which is ordinary and reasonable, and the
 * schema had no way to say so. The researchers had already worked it out for themselves and were
 * hand-encoding "Bagru, Jaipur, Rajasthan" into the free-text place box, because there was nowhere
 * else to put it.
 *
 * SO THE FORM ASKS TWICE, and calls the two answers different things.
 *
 *   ARTISAN LOCATION — state, district, village, and an optional pin. A STATEMENT BY THE
 *   RESEARCHER about the person being documented. This is what the map, the exports and the dataset
 *   use. TWO ACTS may fill it in, and the difference between them is the rule the whole file turns
 *   on. PINNING THE ARTISAN'S PLACE overwrites the state, district and pincode, because pointing at
 *   a place on a map IS a statement about that place and asking somebody to confirm their own
 *   action twice is friction that gets tapped through rather than read. A GPS FIX ARRIVING BY
 *   ITSELF fills only the boxes that are still empty and offers the rest, because the device is
 *   very often at a desk in another state from the artisan. Both announce what they wrote, by name,
 *   with one control that puts back exactly what was there. Nothing here is written silently.
 *
 *   CAPTURED AT — coordinates, accuracy radius, timestamp. Provenance, collapsed by default,
 *   automatic. It says where the phone was, it is labelled as saying that, and it is never
 *   presented as the artisan's location.
 *
 * NOTHING IS BACKFILLED. The fifteen existing records keep their coordinates exactly as recorded.
 * Where the stated location and the captured coordinates disagree the form SAYS SO, in the record's
 * own edit screen, and leaves the correction to the researcher who was there. Guessing a village
 * from a coordinate that was never about the village is how the problem was created.
 *
 * Word for word the same two groups, the same field names and very nearly the same sentences as
 * frontend/components/forms/LocationFields.tsx. A researcher who uses the phone in the workshop and
 * the laptop afterwards should not have to work out that they are the same two questions.
 *
 * AND THE SUBJECT IS NOW A PARAMETER, because `Location` grew a seventh owner. `DesignerProfile`
 * relates to the same table, so a designer's own district and map point are stored the same way an
 * artisan's are — which is what "like the rest of the record pages" was always meant to mean, and
 * which brings this card, its district picker, its coarse-fix guard and its flag-never-rewrite rule
 * with it instead of a seventh reimplementation of an address. Only the WORDING moves: see
 * [LocationSubject], and the note on [LocationFieldsSection]'s `isEdit` for the one thing a profile
 * must never be allowed to do.
 * ---------------------------------------------------------------------------
 */

// ---------------------------------------------------------------------------------------------
// Where the two new answers are stored
// ---------------------------------------------------------------------------------------------

/*
 * District, village and the subject's pin are REAL COLUMNS — `district`, `village`,
 * `subjectLatitude`, `subjectLongitude` on Location, promoted by migration
 * 20260727120000_location_stated_address. This card reads them and writes them.
 *
 * IT ALSO KEEPS WRITING THE METADATA KEYS BELOW, AND FALLS BACK TO THEM ON READ. That is not
 * belt-and-braces; it is the only arrangement in which a fleet mid-update stays coherent, and it
 * has to hold in BOTH directions:
 *
 *   READ THE COLUMN, THEN THE KEY. Everything this app has ever created keeps the stated address in
 *   `extraMetadata`, because until the migration there was nowhere else to put it. An edit form that
 *   read the column alone would show an empty district box over a record that has a district — and
 *   then save that blank over it, which is exactly the class of silent loss this file exists to end.
 *
 *   WRITE BOTH. A phone that has not taken this update reads only the keys. A build that wrote the
 *   columns alone would make every record it touched look district-less on the other phones in the
 *   same workshop until all of them had updated, which is not a state anybody can see happening.
 *
 * The server accepts either shape and normalises to the columns on the way in (`_stated_district`
 * in schemas/common.py, `lift_stated_address` in services/records.py, guarded by
 * tests/test_android_location_compat.py). None of that is a shim with an expiry date: records
 * written in the metadata form keep it for as long as they exist, and an edit re-sends what it was
 * given. Do not "simplify" either half away.
 *
 * THE KEYS ARE THE COLUMN NAMES, which is what made the promotion a rename of nothing at all —
 * except the pin, whose keys were named before the model settled on calling the documented party
 * the SUBJECT rather than the artisan (a workshop and a tool carry one too). The server maps both
 * spellings onto `subjectLatitude`/`subjectLongitude`.
 */
const val LOCATION_META_DISTRICT: String = "district"
const val LOCATION_META_VILLAGE: String = "village"

/**
 * The artisan's own pin, when the researcher dropped one — the metadata half of
 * [LocationRequest.subjectLatitude] / [LocationRequest.subjectLongitude].
 *
 * Deliberately NOT the row's `latitude`/`longitude`. Those two columns are the captured-at reading
 * and are written by the GPS; a statement about the artisan that shared them would be overwritten
 * by the next fix, which is the entire bug this file exists to end.
 */
const val LOCATION_META_ARTISAN_LAT: String = "artisanLatitude"
const val LOCATION_META_ARTISAN_LNG: String = "artisanLongitude"

/** A metadata value as text, whether it was written as a JSON string or as a JSON number. */
private fun LocationRequest.meta(key: String): String =
    (extraMetadata?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

/**
 * Replace metadata keys, dropping any whose answer is null.
 *
 * Entries this file knows nothing about are carried through untouched. The API creates a NEW
 * Location row on every update rather than patching the old one, so anything dropped here is
 * dropped permanently — and a client has no business deleting a key it does not recognise.
 */
private fun LocationRequest.withMeta(vararg pairs: Pair<String, JsonPrimitive?>): LocationRequest {
    val merged = LinkedHashMap<String, JsonElement>(extraMetadata.orEmpty())
    pairs.forEach { (key, value) -> if (value == null) merged.remove(key) else merged[key] = value }
    return copy(extraMetadata = if (merged.isEmpty()) null else JsonObject(merged))
}

/** A typed answer as a metadata value, or null when the researcher left the box empty. */
private fun metaText(value: String): JsonPrimitive? =
    value.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }

/** The four artisan-location answers, held together so they can be parked before a coordinate exists. */
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

/** The column when it holds an answer, the pre-column metadata key when it does not. */
private fun LocationRequest.statedPlace(): StatedPlace = StatedPlace(
    state = state.orEmpty(),
    district = district?.trim().orEmpty().ifEmpty { meta(LOCATION_META_DISTRICT) },
    village = village?.trim().orEmpty().ifEmpty { meta(LOCATION_META_VILLAGE) },
    pincode = pincode.orEmpty(),
    pinLat = subjectLatitude?.let { trimCoordinate(it) } ?: meta(LOCATION_META_ARTISAN_LAT),
    pinLng = subjectLongitude?.let { trimCoordinate(it) } ?: meta(LOCATION_META_ARTISAN_LNG)
)

/** Both shapes, every time. See the note at the top of this file for why neither may be dropped. */
private fun LocationRequest.withStatedPlace(place: StatedPlace): LocationRequest {
    // Half a pin is not a place, and the API says so with a 422 (`_pin_is_a_pair`). A pair that does
    // not parse as two numbers is stored as no pin at all rather than as a refused save.
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
        // Written as JSON NUMBERS, matching the columns they mirror. `lift_stated_address` copies a
        // metadata value straight into a Float column when the column is absent, so a quoted string
        // here would be a string handed to Postgres for a `double precision` — a 500 on the save
        // rather than a validation message, on the one build that is meant to be fixing this.
        LOCATION_META_ARTISAN_LAT to pin?.let { JsonPrimitive(it.first) },
        LOCATION_META_ARTISAN_LNG to pin?.let { JsonPrimitive(it.second) }
    )
}

// ---------------------------------------------------------------------------------------------
// The reference list, cached for a phone with no signal
// ---------------------------------------------------------------------------------------------

/**
 * The cached copy of `GET /reference/address`, as a plain file in the app's private storage.
 *
 * WHY THIS IS NOT OPTIONAL. This is the field client. A rural workshop with no bars is the normal
 * condition, not the edge case — the whole offline outbox exists because of it — and a state
 * dropdown that renders "Loading the state list…" for ever is a required field the researcher
 * cannot answer. The old code fetched on every composition with an in-memory fallback that died
 * with the process, so a phone that had been online an hour ago still showed an empty list.
 *
 * WHY A FILE RATHER THAN SharedPreferences. The rest of the app's small settings live in
 * preferences, correctly: they are a handful of short strings. This payload is ~12 KB of JSON, and
 * SharedPreferences parses its entire XML file into memory the first time any key in it is touched
 * — so parking it beside the auth token would make reading the auth token twelve kilobytes more
 * expensive for the life of the process. A file is read when this card is opened and not otherwise.
 *
 * HOW IT IS INVALIDATED. The server stamps the payload with `version`, and bumps it when the lists
 * move — a union territory merges, a district is renamed, a new one is created. Every successful
 * fetch overwrites the cache when the JSON differs, so a version bump propagates on the next
 * request the phone manages to make, and an unchanged payload costs no write. There is deliberately
 * no time-based expiry: a list that has not changed is not stale, and expiring it would blank the
 * dropdowns of exactly the offline phone this exists for.
 */
private const val REFERENCE_CACHE_FILE = "address-reference.json"

/**
 * WHEN THE CACHED LISTS LAST CAME OFF A SERVER — kept in a file of its own, beside the payload.
 *
 * WHY IT HAD TO EXIST AT ALL (DROPDOWN_DESIGN.md 3.2, item B3). Every other cache in this app
 * records and SHOWS a `fetchedAt`; this one recorded only the payload and its `version`, so the
 * address card had no way to print the one sentence that lets a researcher judge an offline list:
 * "36 states on this device, last refreshed 14 Aug 2026". `DwReferenceStore` states the argument
 * this rests on, and it is the whole reason a date beats an expiry - "A list last refreshed an hour
 * ago that does not contain Ram Kumar means Ram Kumar has no artisan record and one should be
 * created; the same list refreshed nine days ago means nothing of the kind." Nothing here ever
 * deletes on the strength of it. It is SHOWN, never used to decide whether the cache may be used.
 *
 * WHY A SECOND FILE RATHER THAN A FIELD ON THE PAYLOAD. [AddressReferenceDto] is the SERVER's
 * object, decoded straight off the wire; a stamp folded into it would be a client-invented key
 * inside a document the server owns, and wrapping the payload in an envelope would make every
 * `address-reference.json` already sitting in `filesDir` fail to decode - blanking the state list of
 * exactly the phone this cache exists for, on the release that was meant to be improving it. A
 * missing stamp beside a present payload is therefore a REAL and expected state (every phone that
 * has ever run an earlier build is in it), and the card must be able to say nothing rather than
 * guess a date.
 *
 * WRITTEN ON EVERY SUCCESSFUL FETCH, INCLUDING ONE THAT CHANGED NOTHING. "Last refreshed" means the
 * last time the server confirmed the list, not the last time it differed - and the two are far apart
 * here, because the payload is a near-constant that may go a year without moving. That is also why
 * this is not the payload file's own `lastModified()`: that timestamp answers "when did this list
 * last CHANGE", which would tell a researcher their perfectly current list was eleven months stale.
 */
private const val REFERENCE_CACHE_STAMP_FILE = "address-reference-fetched-at.txt"

/**
 * THE 36 STATES AND UNION TERRITORIES, COMPILED INTO THE APK — DROPDOWN_DESIGN §3.2's cheapest gap.
 *
 * ── WHAT WAS BROKEN, AND FOR WHOM ───────────────────────────────────────────
 *
 * The state box on this card was fed by [AddressReferenceCache] and nothing else, so A FRESH INSTALL
 * WITH NO SIGNAL COULD NOT ANSWER "State / union territory" AT ALL. That is not an exotic case on
 * this fleet: a handset is set up at the office and taken to a cluster, and the first thing it is
 * asked to do is record an artisan. The web has never had that failure — `OFFLINE_STATES` in
 * `frontend/.../LocationFields.tsx` has been bundled since the incident that produced it, in which a
 * required closed list with no members refused a submit, `saveOrQueue` was never reached, and *"the
 * interview and its photographs died with the tab"*.
 *
 * ── DERIVED, NOT HAND-COPIED, WHICH IS THE WHOLE OF WHY THIS IS SAFE ─────────────────
 *
 * Thirty-six names typed into a second file are thirty-six names that can drift from the list the
 * API validates against, and the drift is silent until a researcher picks a state the server
 * refuses. The web avoids that by deriving `OFFLINE_STATES` from `POSTAL_ZONES`, a table it already
 * had and already depended on — *"there is one list in this file, not two, and it cannot drift from
 * the zone check that sits beside it"*.
 *
 * This client has no postal-zone table. What it has is `report/ReportMap.STATE_SEATS`: the seat of
 * every one of the 36, ported from `services/geography.STATE_SEATS`, declared in the order
 * `address.INDIAN_STATES` lists them, and already load-bearing — the report map TINTS by it, and a
 * name out of step with the server produces a state the figure refuses to shade, which is a defect
 * somebody chases. [INDIAN_STATES_AND_UNION_TERRITORIES] is that table read out. One list on this
 * client, not two.
 *
 * ── AND WHY THE DISTRICTS ARE NOT HERE, WHICH IS NOT AN OVERSIGHT ───────────────────
 *
 * 795 names, revised several times a year, meaningful only per state. The web makes the same split
 * for the same reason and stands the district DOWN from required instead. The states change on the
 * order of once a decade, which is what makes bundling them honest.
 *
 * THE SERVED LIST STILL WINS WHENEVER IT EXISTS ([stateOptions] prefers it), so the day the register
 * changes the deployed API is the authority and no APK needs shipping.
 */
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

    /**
     * Overwrite when the served payload differs. Returns true when the cache changed.
     *
     * ── THE SERVER'S `version` DECIDES, AND UNTIL NOW NOTHING READ IT ───────────────────
     *
     * `backend/app/api/routes/reference.py` asks for exactly this in the route's own docstring —
     * *"The payload is a pure constant — no database read — so a client should cache it and re-fetch
     * only when `version` changes."* It is the ONE server-provided invalidation signal anywhere in
     * this API, and both clients were paying for it and throwing it away: the web stored nothing at
     * all, and this method reached the right ANSWER by the expensive route — encode 11.7 KB, read
     * 11.7 KB back off the disk, compare 11.7 KB of JSON, on every successful fetch, for a document
     * that may go a year without moving.
     *
     * [AddressReferenceDto.version] is compared first and settles it in two integers. The byte
     * compare is KEPT as the tie-break rather than deleted, and that is not belt-and-braces: the DTO
     * defaults `version` to 1, so a deployment that stops sending the key, or a proxy that rewrites
     * the body, would make every payload look unchanged and freeze this cache at whatever it holds.
     * Two payloads at the same version are compared as they always were; two at different versions
     * skip the read entirely.
     *
     * NOTHING HERE EXPIRES, and the stamp is written by [stamp] on every ANSWER rather than on every
     * change — see [REFERENCE_CACHE_STAMP_FILE], which spends a paragraph on why those are different
     * dates and why the researcher needs the first one.
     */
    fun write(context: Context, value: AddressReferenceDto): Boolean = runCatching {
        val file = File(context.filesDir, REFERENCE_CACHE_FILE)
        val encoded = referenceJson.encodeToString(AddressReferenceDto.serializer(), value)
        if (file.exists()) {
            val existing = read(context)
            // Same version, same document — the route's own promise. Fall through to the byte
            // compare only where that promise cannot be checked (see the KDoc's second paragraph).
            if (existing != null && existing.version == value.version) return false
            if (file.readText() == encoded) return false
        }
        file.writeText(encoded)
        true
    }.getOrDefault(false)

    /**
     * The ISO-8601 instant of the last successful fetch, or null when this device has none.
     *
     * NULL IS A REAL ANSWER AND NOT AN ERROR: every phone that cached a list under a build older
     * than this one has a payload and no stamp. The caller then prints no date rather than inventing
     * one - see [cachedListLine], which refuses to be used without a real one.
     */
    fun readFetchedAt(context: Context): String? = runCatching {
        val file = File(context.filesDir, REFERENCE_CACHE_STAMP_FILE)
        if (!file.exists()) return null
        file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Record that the server answered, just now, on the DEVICE clock.
     *
     * The device clock, exactly as `DwReferenceStore` uses it, and for the reason written there:
     * this is shown to the person holding the phone so they can judge the list against their own
     * sense of how long ago they last had signal. A handset whose clock is wrong shows a wrong date,
     * which is a far smaller harm than showing none - and nothing in this file ever decides anything
     * on it.
     *
     * Swallows its own failure. A stamp that could not be written costs the sentence its date; it
     * must never cost the researcher the list.
     */
    fun stamp(context: Context, at: String = Instant.now().toString()) {
        runCatching { File(context.filesDir, REFERENCE_CACHE_STAMP_FILE).writeText(at) }
    }
}

/**
 * The address reference AS THIS DEVICE ACTUALLY HAS IT - the lists, plus the four facts a picker
 * needs in order to say which of DROPDOWN_DESIGN.md 3.5's states it is in.
 *
 * A silently empty dropdown reads as "there are none", which this repository names as its single
 * most repeated bug class, and the state list is where that was first paid for: offline the list was
 * empty, a REQUIRED closed list had no members, validation refused the submit and "the interview and
 * its photographs die with the tab". The remedy has two halves and neither works alone - R2, a field
 * may only be mandatory where it is answerable; and R3, the control must SAY which case it is in.
 * Both halves need more than the payload, which is why this type exists and why
 * [rememberAddressReference] no longer returns a bare [AddressReferenceDto].
 */
@Immutable
data class AddressReferenceState(
    val reference: AddressReferenceDto = AddressReferenceDto(),
    /** The stamp from [AddressReferenceCache.readFetchedAt]; null on a device that never fetched. */
    val fetchedAt: String? = null,
    /** A request is genuinely in flight. The ONE state in which "Loading..." is a true sentence. */
    val loading: Boolean = true,
    /**
     * This session's own fetch answered with lists. Only then is the list neither stale nor absent,
     * and only then has the card nothing at all to report.
     */
    val servedThisSession: Boolean = false,
    /**
     * The last failure was an ANSWERED refusal rather than a phone that could not reach the server.
     *
     * The classification is the outbox's own - `WorkshopRepository.isTransient` - and deliberately
     * not a network probe: a second idea of what "offline" means is how one screen comes to call a
     * dead tunnel a server fault while the queue behind it calls the same throwable worth retrying.
     */
    val online: Boolean = false
)

/**
 * The state and district lists: the cached copy first, then whatever the server has to add — and the
 * record of WHICH of those two a caller is looking at, so that a picker can say which.
 *
 * The cache is read BEFORE the request goes out, so the dropdowns are populated on the first frame
 * on a phone that has ever been online, and a failed fetch changes nothing on screen. A fetch that
 * comes back empty-handed — which is what a 401 mid-refresh or a captive-portal HTML page decodes
 * to — is discarded rather than allowed to blank a list that was working.
 *
 * ONE IMPLEMENTATION, AND IT IS HOISTABLE. The designer-profile screen carried a byte-for-byte copy of
 * the effect below, which meant two fetches on any screen showing both an address card and a state
 * box, and — once the stamp existed — two places to remember to write it. A caller that already
 * holds one of these passes it into [LocationFieldsSection] rather than letting the default fire a
 * second request.
 *
 * WHAT IT REPORTS BEYOND THE LISTS, and why each is reported rather than guessed at the call site:
 *
 *  - [AddressReferenceState.loading] is true only while a request is genuinely in flight. It is what
 *    stops "Loading the state list..." being printed for ever on a phone that has never been online,
 *    which is DROPDOWN_DESIGN.md 3.2's B2: on a fresh install that is a PERMANENT state rather than
 *    a transient one, and telling somebody to wait for something that will never arrive is worse
 *    than saying nothing at all.
 *  - [AddressReferenceState.servedThisSession] separates "these are today's lists" from "these came
 *    off the disk", which is the difference between saying nothing and printing a date.
 *  - [AddressReferenceState.online] is the OUTBOX'S classification of the failure, never a probe.
 */
@Composable
fun rememberAddressReference(repository: WorkshopRepository): AddressReferenceState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(AddressReferenceState()) }
    LaunchedEffect(Unit) {
        // The cache first, and the stamp with it: a phone with no signal must reach its first frame
        // with the lists AND with the date that lets its holder judge them.
        val cached = withContext(Dispatchers.IO) { AddressReferenceCache.read(context) }
        val stamp = withContext(Dispatchers.IO) { AddressReferenceCache.readFetchedAt(context) }
        if (cached != null) state = state.copy(reference = cached, fetchedAt = stamp)
        val fresh = runCatching { repository.addressReference() }
        val payload = fresh.getOrNull()
        if (payload == null) {
            /*
             * ANSWERED-AND-REFUSED versus COULD-NOT-BE-REACHED, told apart by the OUTBOX'S rule and
             * not by a second one of this card's own. The two get different sentences and different
             * next moves — "connect once and the list is kept on the device from then on" against
             * "this is not showing what exists" — and a second idea of what "offline" means is how one
             * screen comes to call a dead tunnel a server fault while the queue behind it calls the
             * same throwable worth retrying.
             */
            val cause = fresh.exceptionOrNull()
            state = state.copy(
                loading = false,
                online = cause != null && !repository.isTransient(cause)
            )
            return@LaunchedEffect
        }
        if (payload.statesAndUnionTerritories.isEmpty() && payload.states.isEmpty()) {
            // A 200 carrying nothing is not an answer this card acts on — see the paragraph above.
            // It is also not a failure, so whatever was cached still stands and only the spinner
            // stops. Deliberately NOT stamped: nothing was confirmed.
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
            // Stamped even when the payload was byte-identical: this records that the server
            // ANSWERED, and these lists are near-constants that can go a year without moving.
            AddressReferenceCache.stamp(context, now)
        }
    }
    return state
}

/**
 * The trailing " *" this app spells "required" with, applied only where the answer is available.
 *
 * R2a — NEVER LABEL A FIELD REQUIRED WHILE ITS LIST IS EMPTY. The asterisks on this card were
 * LITERAL TEXT ("State / union territory *"), so on a handset that had never been online they marked
 * two closed lists with no members as things that had to be answered. The web has had the computed
 * form since it was written — both its flags end in `&& options.length > 0` — and states why it is
 * written out even where the bundled list means it can never fire: "the invariant is what matters
 * — this card never demands an answer it is not offering — and a later change that narrowed or
 * dropped the bundled list would otherwise reintroduce a lost interview in silence."
 *
 * The mark itself is `FieldRenderer.fieldLabel`'s, so one mark means one thing on every screen.
 */
private fun requiredLabel(label: String, required: Boolean): String =
    if (required) "$label *" else label

/**
 * The DROPDOWN_DESIGN.md 3.5 sentence for one of the two address lists, or null when the control has
 * nothing to report.
 *
 * INTERNAL, because a THIRD picker prints it. The designer profile keeps its own flat `state` box
 * beside this card until the retiring migration moves the four columns across, and two spellings of
 * "this device has not been given the state list" on one screen is two facts to whoever reads them.
 *
 * ONE FUNCTION FOR BOTH PICKERS AND FOR BOTH OF `SearchableSelectField`'s SLOTS, which is what stops
 * the eye and the screen reader being told different things: that control draws `emptyMessage`
 * inside whichever surface opens, speaks it as part of the trigger's accessibility name, and prints
 * it on the form itself when the field has been stood down.
 *
 * The strings come from `WorkshopOptions.kt` and are not re-worded here. 3.5 fixes them and says
 * both clients print them byte for byte; a second wording of one fact is a second fact as far as a
 * reader is concerned. The noun is this caller's plural — "states", "districts".
 *
 * @param rows how many options the picker is actually offering.
 * @param state what this device knows, from [rememberAddressReference].
 */
internal fun addressListNotice(
    noun: String,
    rows: Int,
    state: AddressReferenceState,
    /**
     * These rows are [BUNDLED_STATES], not a served or cached list — so there is nothing to report.
     *
     * PASSED IN RATHER THAN DERIVED, because this one function serves both pickers and only one of
     * them has a floor: 795 districts genuinely cannot be compiled in, and a bundled arm that
     * guessed from a row count would eventually claim a district list was compiled in because a
     * state happened to have 36 of them.
     */
    bundled: Boolean = false
): String? = when {
    /*
     * THE LIST ARRIVED THIS SESSION. There is nothing to report and nothing to apologise for, and
     * this is one of the two branches that may say nothing.
     *
     * Deliberately NOT [BUNDLED_LIST_HAS_NO_SENTENCE], although the answer is the same null: that
     * constant names a vocabulary compiled into the APK, and this branch is about a list that came
     * off a server a moment ago. The distinction is worth keeping even now that the two can both
     * occur here — a reader who conflates them will next conclude the cached branch is unnecessary.
     */
    rows > 0 && state.servedThisSession -> null
    /*
     * BUNDLED — and this branch is NEW, because until [BUNDLED_STATES] landed this client had no
     * compiled-in list and this paragraph said so in as many words. It read: *"ANDROID HAS NO
     * BUNDLED STATE LIST — the web derives one from POSTAL_ZONES and this client has only the disk
     * cache below it"*, and it was right when it was written. §3.2 named the gap; the gap is closed.
     *
     * A vocabulary compiled into the APK has no fact to report: it is always answerable and always
     * current on the timescale it moves. Saying "last refreshed" over it would be worse than
     * silence, because the date would describe a cache the reader is not looking at.
     */
    rows > 0 && bundled -> BUNDLED_LIST_HAS_NO_SENTENCE
    /*
     * CACHED, AND ONLY WHERE A REAL DATE CAN BE PRINTED. `cachedListLine`'s own note refuses the
     * sentence without one, and it is right to: the date IS the sentence. A list described as "last
     * refreshed" with no date is the one form of this that stops a researcher judging it. Every
     * phone that cached a payload under a build older than the stamp file is in exactly that state,
     * and it says nothing rather than guessing.
     */
    rows > 0 -> state.fetchedAt
        ?.let { readableStamp(it) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { cachedListLine(rows, noun, it) }
    // Nothing to offer. WHICH of the three empty states this is decides both the sentence and
    // whether the field stands down, so it may not be collapsed into one "no options" branch.
    state.loading -> loadingListLine(noun)
    state.online -> couldNotListLine(noun)
    else -> offlineListLine(noun)
}

/**
 * The states the dropdown offers, with a stored value kept at the front until the list arrives.
 *
 * [BUNDLED_STATES] IS THE FLOOR AND THE SERVED LIST IS THE AUTHORITY, in that order — the shape
 * `MainActivity`'s `workshopLevelOptions` already uses for a served vocabulary with a compiled-in
 * fallback, and the shape the web's own `stateOptions` uses for this exact list. It is what lets a
 * fresh install in a courtyard answer this box at all; see [BUNDLED_STATES].
 */
private fun stateOptions(current: String, reference: AddressReferenceDto): List<Pair<String, String>> {
    val served = reference.statesAndUnionTerritories
        .ifEmpty { reference.states }
        .ifEmpty { BUNDLED_STATES }
    val known = served.any { it.equals(current, ignoreCase = true) }
    // An edit form whose record holds a state must not show "Select state" over it: that reads as
    // "not answered" and invites the researcher to answer it again, differently.
    val all = if (current.isNotBlank() && !known) listOf(current) + served else served
    return all.map { it to it }
}

/** The districts of [state], with the same kept-at-the-front rule for a stored value. */
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

// ---------------------------------------------------------------------------------------------
// What the geocoder is allowed to say, and when
// ---------------------------------------------------------------------------------------------

/**
 * The accuracy radius past which a fix may not choose an address.
 *
 * ANDROID HAD NO SUCH LINE AT ALL. The web card has had `PINCODE_ACCURACY_LIMIT_METRES` since it
 * was written; this client reverse-geocoded whatever it was handed, including a 2.5 km network
 * estimate — and two of the fifteen live records carry radii over two kilometres, so this is not
 * hypothetical. A satellite fix is good to tens of metres; a phone with no lock, indoors or under a
 * tin roof, silently falls back to the mobile network and reports kilometres while returning
 * coordinates that look every bit as precise. Rural districts are tens of kilometres across and
 * rural PIN areas a few, so past a kilometre the geocoder is choosing between neighbours on the
 * strength of an error term. A blank box is honest; a district that arrived by itself reads as
 * measured fact and gets exported as one.
 *
 * WHAT THE LIMIT GOVERNS NOW THAT A POINT CAN WRITE. Both halves, and it must not be softened at
 * either: above the line a fix neither WRITES nor OFFERS. A one-tap "yes" to a district picked out
 * of a 2.5 km circle is exactly as wrong as writing it in silently, and rather easier to tap. The
 * coordinates themselves are still kept, with their radius — a rough position beats none — and the
 * card says in words why no address came with them.
 *
 * The same 1000 m, for the same physical reason, as the web card and as
 * [SATELLITE_FIX_LIMIT_METRES] in LocationCapture.kt.
 */
private const val GEOCODE_ACCURACY_LIMIT_METRES = 1000.0

/**
 * How long a new point waits before it is looked up.
 *
 * Two unrelated reasons, both real. Typing a coordinate by hand re-emits on every keystroke and each
 * emit is a different point, so this keeps it to one lookup per place rather than one per digit. And
 * on the pin path it guarantees the pin's own write has been committed before the address write reads
 * the boxes back. Invisible next to the geocoder's own round trip either way.
 */
private const val LOOKUP_DEBOUNCE_MS = 400L

/** Trailing administrative words MapTiler and Android's geocoder disagree about attaching. */
private val DISTRICT_SUFFIXES = listOf("district", "districts", "zila", "zilla", "jila", "jilla")

/** A name reduced to its comparison key. Same fold as `_fold` in services/address.py. */
private fun foldName(value: String): String =
    value.lowercase(Locale.UK).replace("&", "and").filter { it in 'a'..'z' || it in '0'..'9' }

/**
 * A geocoded district name, stripped of the administrative word.
 *
 * The geocoders are inconsistent about it in both case and presence — "Jammu district" lowercase,
 * "Akola District" capitalised, "Bagru" bare — and the served list holds none of them, so a name
 * that keeps its suffix matches nothing and is silently dropped.
 */
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

/** The entry of [districts] that [text] names, or "" when the list does not hold it. */
private fun matchDistrict(text: String, districts: List<String>): String {
    val wanted = foldName(normaliseDistrictName(text))
    if (wanted.isEmpty()) return ""
    districts.firstOrNull { foldName(it) == wanted }?.let { return it }
    return districts.firstOrNull { entry ->
        val name = foldName(entry)
        (name.length >= 5 && wanted.contains(name)) || (wanted.length >= 5 && name.contains(wanted))
    }.orEmpty()
}

/** A geocoded postal code, kept only when it satisfies the rule the researcher is held to. */
private fun usablePincode(text: String?): String {
    val digits = text.orEmpty().filter { it in '0'..'9' }
    return if (pincodeValidationError(digits) == null) digits else ""
}

/**
 * What the geocoder says is at a point, already resolved against the API's own closed lists.
 *
 * EVERY FIELD MAY BE "", AND "" IS AN ANSWER. Sampled across rural Rajasthan, Uttarakhand and
 * Jammu & Kashmir, 57 of 60 points carry no postal code at all — so "no pincode here" is the
 * ordinary reply, not a failure, and the caller must treat it as a reply. Reading it as "leave
 * whatever was there" is precisely the staleness bug this file removes.
 */
private data class PlaceSuggestion(
    val state: String = "",
    val district: String = "",
    val pincode: String = ""
) {
    val isEmpty: Boolean get() = state.isBlank() && district.isBlank() && pincode.isBlank()
}

/**
 * The three boxes a point is allowed to fill, named so a write can say which ones it touched.
 *
 * The village is deliberately absent. No closed list of Indian villages exists, so there is nothing
 * to resolve a geocoded settlement name against — and `locality` names a tehsil, a bypass or a
 * national highway often enough that a village taken from it is wrong more often than it is right.
 */
private enum class PlaceField(val label: String) {
    State("state"),
    District("district"),
    Pincode("pincode")
}

/**
 * "state, district and pincode" — the boxes a write touched, as a sentence.
 *
 * Named rather than counted. "3 fields were filled in" sends the researcher hunting for them; naming
 * them says where to look, which is the whole value of announcing the write at all. Same sentence,
 * built the same way, as `FIELD_NAMES` in the web card.
 */
private fun fieldNames(fields: List<PlaceField>): String = when (fields.size) {
    0 -> ""
    1 -> fields.first().label
    else -> fields.dropLast(1).joinToString(", ") { it.label } + " and " + fields.last().label
}

/**
 * Why a point is being looked up, and therefore what its answer is allowed to do to the boxes.
 *
 * The two are not a preference and not a setting; they are two different human acts, and the whole
 * of the difference between them is enforced in `applyPlace`.
 */
private enum class PlaceIntent {
    /**
     * The researcher pointed at the artisan's place, or accepted an offer. A request for THIS
     * place's address, so it overwrites — a blank pincode included.
     */
    Explicit,

    /**
     * A fix arrived by itself, or the device's own position was captured. Fills empty boxes and
     * overwrites nothing.
     */
    Passive
}

/**
 * One automatic write, kept so it can be announced and taken back.
 *
 * [previous] is the point of it. An "undo" that CLEARED the boxes instead of restoring them would
 * destroy a typed answer just as thoroughly as the silent overwrite this replaces, so what was there
 * is snapshotted at the moment of the write and put back verbatim.
 */
private data class AppliedWrite(
    val fields: List<PlaceField>,
    val previous: PlaceSuggestion,
    val intent: PlaceIntent
)

/**
 * The three geocodable answers as the boxes currently hold them.
 *
 * [PlaceSuggestion] doubles as this snapshot rather than earning a near-identical twin: it is
 * already exactly these three values, and every comparison in the write path is "what is in the box
 * against what the point says", which wants both sides in one shape.
 */
private fun StatedPlace.geocodable(): PlaceSuggestion = PlaceSuggestion(state, district, pincode)

/**
 * The record of what this card wrote, updated for one write of [fields] to [values].
 *
 * Fields the write did not touch keep whatever they were credited with before — which is what lets a
 * researcher's own answer in one box sit beside a machine-filled one in the next without either
 * being mistaken for the other. Undo passes an empty [values]: a restored box holds a human's value
 * again, so this card is no longer the author of it.
 */
private fun PlaceSuggestion.crediting(fields: List<PlaceField>, values: PlaceSuggestion) = PlaceSuggestion(
    state = if (PlaceField.State in fields) values.state else state,
    district = if (PlaceField.District in fields) values.district else district,
    pincode = if (PlaceField.Pincode in fields) values.pincode else pincode
)

/**
 * Ask the device's geocoder what is at these coordinates.
 *
 * `adminArea` is the state and `subAdminArea` is the DISTRICT — the Android equivalent of
 * MapTiler's `region` and `subregion`, and the same trap avoided: `locality` and the thoroughfare
 * fields name a tehsil, a bypass or a national highway often enough that a place name taken from
 * them is wrong more often than it is right.
 *
 * Runs off the main thread and swallows everything. A geocoder is a network service behind a system
 * API — absent on some builds, rate-limited on others, and simply wrong about a hamlet often enough
 * in rural India to matter — so nothing it does may reach the researcher as an error and nothing it
 * fails to do may block a save.
 */
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

/**
 * The accuracy radius when it is too wide for a point to be allowed to name a place, or null when
 * the point may be looked up.
 *
 * A HAND-PLACED PIN PASSES NULL AND IS EXEMPT, and that is not a loophole in the guard: the
 * researcher pointed at the place, and a pointer has no error radius to disqualify it. What the
 * limit disqualifies is a MEASUREMENT that admits to being kilometres wide.
 */
private fun coarseRadius(accuracy: Double?): Double? =
    accuracy?.takeIf { it > GEOCODE_ACCURACY_LIMIT_METRES }

/**
 * The suggestion for a point, or an empty one when the point is too coarse to have an address.
 *
 * A fix wider than [GEOCODE_ACCURACY_LIMIT_METRES] is not looked up at all. The caller checks the
 * radius too, so it can say why nothing appeared; the check is repeated here because the rule is the
 * one thing in this file that may not be softened, and a guard that lives only at the call site is a
 * guard the next call site forgets.
 */
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

// ---------------------------------------------------------------------------------------------
// Presentation helpers
// ---------------------------------------------------------------------------------------------

/**
 * A coordinate as six decimal places, in a fixed locale.
 *
 * [Locale.UK] and not the device's: a handset set to a comma-decimal locale would render 22,310000
 * — which is not a number this API parses, and which a reader copying it into a map would get
 * wrong.
 *
 * PRIVATE, AND IT HAS TO STAY PRIVATE. `LocationCapture.kt` declares a file-private function of the
 * same name and signature in this same package; widening either one to `internal` makes the pair a
 * CONFLICTING OVERLOAD rather than a shadowed name, and every call site in the other file stops
 * resolving. A screen outside this file that needs the same six decimal places formats them itself
 * against this rule — or the two declarations are merged first, in one commit, which is a change to
 * both files.
 */
private fun trimCoordinate(value: Double): String = String.format(Locale.UK, "%.6f", value)

private fun radiusLabel(metres: Double): String =
    if (metres < 1000) "±${Math.round(metres)} m" else "±${String.format(Locale.UK, "%.1f", metres / 1000)} km"

/** ISO 8601 with an offset, which is what the API's `capturedAt` parses. */
private fun isoNow(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.UK).format(Date())

/*
 * `readableStamp` USED TO LIVE HERE, PRIVATE, AND IT NOW LIVES IN `WorkshopOptions.kt`.
 *
 * It was the only formatter in the app that could turn a stored ISO stamp into the date
 * [cachedListLine] asks for, and that sentence is written for the four REGISTERS as well as for this
 * card. Leaving it private here meant the record forms had the sentence and no way to build its one
 * load-bearing argument, so they printed nothing at all — DROPDOWN_DESIGN §3.3's cached case,
 * unreachable on the four controls it was written for. MOVED rather than copied: the block comment
 * inside it is about a fraction that reads as eight and a half minutes of drift, and a second copy
 * is a second chance to reintroduce exactly that.
 *
 * Every stored coordinate written before the `capturedAt` change is undated, and "" is still how
 * this card says so.
 */

/** A group heading, marked as one so TalkBack's heading navigation can jump between the two. */
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
            fontSize = 15.sp,
            modifier = Modifier.semantics { heading() }
        )
        Text(subtitle, color = MaterialTheme.field.muted, fontSize = 12.sp)
    }
}

/** A notice inside a group. [warn] for something to act on, plain for what is merely so. */
@Composable
private fun GroupNotice(warn: Boolean, text: String) {
    val container = if (warn) MaterialTheme.field.warningContainer else MaterialTheme.field.surface100
    val ink = if (warn) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted
    Text(
        text,
        color = ink,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
}

// ---------------------------------------------------------------------------------------------
// The card
// ---------------------------------------------------------------------------------------------

/**
 * WHO THE STATED ADDRESS IS ABOUT, in the words this card puts on screen.
 *
 * The card was written for one subject and said so in twenty-odd literal strings — "Artisan
 * location", "Use it as the artisan's location?", "Pin the artisan's location on the map". Every one
 * of those is correct on the six field-record forms and wrong on the seventh owner of `Location`,
 * the designer's own profile, where the subject IS the person filling the form in.
 *
 * A TYPE RATHER THAN FOUR LOOSE `String` PARAMETERS, because the four have to agree. A caller that
 * passed a heading and forgot the possessive would produce a card headed "Designer location" that
 * then asked whether to use a point as "the artisan's" — and on a screen whose whole purpose is to
 * stop one person's place being recorded as another's, that is not a typo, it is the bug. There are
 * exactly two of these and both are declared below; a third would need the same four answers.
 *
 * NOTHING HERE CHANGES WHAT IS STORED. The columns, the metadata mirror and the two-group split are
 * identical for every subject — this is the wording, and only the wording.
 */
@Immutable
data class LocationSubject(
    /** The group heading. Title case, because it is a heading. */
    val heading: String,
    /** What sits under the heading, explaining what this group is for on THIS form. */
    val subtitle: String,
    /** Mid-sentence, with its article: "the artisan", "the designer". */
    val label: String,
    /** Mid-sentence possessive: "the artisan's", "the designer's". */
    val possessive: String,
    /** The subject's own pin, as a label: "Artisan pin", "Designer pin". */
    val pinLabel: String
)

/** The six field-record forms. The default, so no existing call site changes a word. */
val ARTISAN_LOCATION_SUBJECT: LocationSubject = LocationSubject(
    heading = "Artisan location",
    subtitle = "Where the artisan actually works — what the map, the exports and the dataset use. " +
        "Pinning the artisan's place on the map fills in the state, district and pincode, and says " +
        "so, so you can put it back. A GPS fix fills in only what is still blank, because the " +
        "device is very often at a desk in another state from the artisan.",
    label = "the artisan",
    possessive = "the artisan's",
    pinLabel = "Artisan pin"
)

/**
 * The designer's own profile — the seventh owner of `Location`, and the one whose subject is the
 * person holding the phone.
 *
 * THE SUBTITLE SAYS THE OPPOSITE THING FROM THE ARTISAN'S, and that is the point of having two. On a
 * record form the warning is that the device is probably NOT where the artisan is. On a profile the
 * device is very likely exactly where the designer is — and the danger is the other one: a fix taken
 * because a form opened would record whichever desk, hotel or airport the profile happened to be
 * edited from as the place the designer is based. So nothing is captured unless it is asked for, the
 * card is always mounted as an edit (see [LocationFieldsSection]'s `isEdit`), and the subtitle says
 * out loud that this is where the designer WORKS rather than where they are standing.
 */
val DESIGNER_LOCATION_SUBJECT: LocationSubject = LocationSubject(
    heading = "Where you are based",
    subtitle = "The district and the map point for your own practice — where you WORK, not where " +
        "this phone happens to be while you fill the form in. Nothing is captured automatically " +
        "here: a profile is edited at a desk, on a train, or at somebody else's institution, and a " +
        "fix taken because a screen opened would file you wherever you happened to be sitting.",
    label = "the designer",
    possessive = "the designer's",
    pinLabel = "Designer pin"
)

/**
 * Why a NEW record cannot be saved without a coordinate, a state and a district — and why an
 * existing one still can.
 *
 * THE ASTERISKS ARE NOW ENFORCED, at the save button of every form that opens a record. The server
 * enforces exactly this on create (`require_location`, schemas/common.py) and the same reference
 * payload that fills these dropdowns is what its validators check against, so a form that offered
 * the save anyway would be spending a round trip to fetch a 422 the phone could already read. That
 * matters most where there is no round trip to spend: a record saved with no signal goes into the
 * outbox, and a body the server will refuse sits there being retried until somebody notices it
 * failed, days later, a long way from the artisan.
 *
 * IT IS FOR CREATES ONLY, and the caller is the one that knows. `forbid_clearing_location`
 * deliberately does NOT ask an update for a state and a district: the records written before those
 * columns existed have neither, and a researcher who opened one to correct a phone number must be
 * able to save it without inventing a district from a desk. Guessed data is worse than absent data.
 * The card flags the gap and invites them to close it; this refuses to close it on their behalf.
 *
 * A null [value] answers with the location message rather than null, because on create the server
 * demands a Location at all — and a pin or two typed numbers satisfy that as well as a fix does.
 */
fun artisanLocationRequirementError(value: LocationRequest?): String? {
    val place = value?.statedPlace() ?: return LOCATION_REQUIRED_MESSAGE
    if (place.state.isBlank()) return "Choose the artisan's state under 'Artisan location' before saving."
    if (place.district.isBlank()) return "Choose the artisan's district under 'Artisan location' before saving."
    return null
}

/**
 * Both groups, in the order a researcher answers them: what they know about the artisan first, what
 * the phone happens to know about itself second and collapsed.
 *
 * Drop-in for `LocationAddressEditor` in MainActivity — same parameters minus `onUseGps`, which
 * nothing needed once [LocationCaptureCard] started driving its own permission flow.
 *
 * WHERE THE ANSWERS LIVE. On the [LocationRequest] itself, read straight back out of it, so a
 * record whose location arrives after mount (every edit form fetches its record) shows what it
 * stored rather than a blank box. A [LocationRequest] cannot exist without a coordinate, though, so
 * before there is one the four artisan answers are parked in [StatedPlace] and folded in the moment
 * a fix or a pin arrives — and the card says so, because four answers that silently do not save is
 * the worst version of this.
 *
 * WHICH PATH IS WHICH, because everything below reads better with the two named. The artisan's own
 * MapPickerDialog at the bottom of this function is the EXPLICIT path: it writes the state, district
 * and pincode of the point the researcher tapped, over whatever was there. Everything arriving
 * through [LocationCaptureCard] — the fix taken on open, "Use current GPS", the capture pin, typed
 * coordinates — is the PASSIVE path: it fills empty boxes only, and offers the rest.
 */
@Composable
fun LocationFieldsSection(
    repository: WorkshopRepository,
    value: LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = true,
    /**
     * THIS IS AN EDIT OF AN EXISTING RECORD, so nothing captures a fix on its own.
     *
     * ── THE TRAP, WHICH HAS ALREADY SHIPPED AS A BUG ON BOTH CLIENTS ─────────────────────────
     *
     * The automatic capture is what put fifteen artisans documented in Rajasthan, Gujarat,
     * Uttarakhand and Andhra Pradesh at 22.31 N, 87.31 E — a desk in Kharagpur. It is forwarded
     * to [LocationCaptureCard], where `true` short-circuits the fix outright; the card also waits
     * out [EDIT_FETCH_GRACE_MS] for a record to arrive, but that is a HEURISTIC and this is the
     * rule. Its web twin spells the same switch `isEditForm = initial !== undefined`, where
     * OMITTING `initial` is the only thing that turns capture on and passing `initial={null}`
     * still counts as an edit.
     *
     * ANY FORM WHOSE RECORD ALREADY EXISTS MUST PASS `true`, and the designer profile is the
     * sharpest case: it is ALWAYS an edit of one's own row — the server upserts the row on read,
     * so there is no create path anywhere in that feature — and the subject of the address is the
     * person holding the phone. Left to the grace period, a profile opened on a train would offer
     * to file its owner in whichever district the train was passing through, and nothing
     * downstream could tell that from a district they chose.
     */
    isEdit: Boolean = false,
    showRequirementError: Boolean = false,
    /**
     * Who the stated address is ABOUT, in the words this card prints. See [LocationSubject].
     *
     * The default is the artisan, so the six record forms read exactly as they always have.
     */
    subject: LocationSubject = ARTISAN_LOCATION_SUBJECT,
    /**
     * The state and district lists, when the HOST already has them.
     *
     * The default fetches its own, which is right for every form that shows this card and nothing
     * else. The designer profile shows this card BESIDE its own flat state box — the two addresses
     * live side by side until the retiring migration — and two copies of this would mean two
     * requests for one near-constant payload and two chances to write the fetched-at stamp
     * differently.
     */
    referenceState: AddressReferenceState = rememberAddressReference(repository),
    /**
     * Raised while this card is holding a STATED ADDRESS THAT CANNOT BE SAVED, because there is no
     * coordinate under it.
     *
     * ── WHY THE HOST HAS TO KNOW, AND WHY THIS CARD CANNOT ANSWER IT ALONE ────────────────────
     *
     * `Location.latitude`/`longitude` are NOT NULL for all seven owners of that table, and
     * `LocationInput.latitude`/`longitude` are required floats with no default, so a state, a
     * district, a village and a pincode have nowhere to live until a point exists. Before there is
     * one they are parked in [StatedPlace], INSIDE this composable, and `onChange` is never called
     * — so a host that only watches its `LocationRequest?` sees null and sends no address at all.
     * Four typed answers then disappear at save time with a 200 and nothing on screen.
     *
     * The notice below already says so. This is the other half: a form that can REFUSE the save
     * says it at the moment the person presses the button, which is the only moment they are
     * looking. The web card does the identical thing through `setCustomValidity` on both
     * coordinate boxes, with the sentence "The state and district are stored with the coordinates,
     * so this record needs one before they can be saved."
     *
     * IT IS NOT A SECOND NULL CHECK. `value == null` alone would fire on an untouched card; this
     * fires only when something has actually been typed into the stated group.
     */
    onStatedAddressNeedsCoordinate: (Boolean) -> Unit = {},
    /**
     * The village, when the FORM owns it rather than this card.
     *
     * Artisan, craft, workshop, product, tool and questionnaire all have a real `place` column, and
     * it is the column the shipped "Village/Place" export field already reads — it is where
     * "Bagru, Jaipur, Rajasthan" was being hand-encoded for want of a state and a district box.
     * A form that passes its own `place` through here gets ONE village question in the group where
     * it belongs; a form that passes nothing keeps a village of its own in the location metadata.
     * Leaving both is the one arrangement that is actually wrong, so see the call-site note in the
     * report: each form should hand its `place` down and drop its separate box.
     */
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
    /*
     * What this card last WROTE into the three boxes, and the only thing afterwards that can tell a
     * machine-filled answer from one a human gave.
     *
     * It is no longer what decides whether a write may happen — the passive path asks whether a box
     * is EMPTY, which is both a stricter question and a simpler one. What still needs this is the
     * case where a LATER point has nothing to say: the values standing in the boxes then describe
     * the point BEFORE it, and only the ones this card copied in may be offered for clearing.
     * Recording the VALUES rather than a "the user touched it" flag is what makes that survive a
     * record that loads after mount, and hands a box back to the geocoder when a researcher empties
     * it by hand.
     */
    var applied by remember { mutableStateOf(PlaceSuggestion()) }
    /*
     * WHAT WAS JUST FILLED IN FOR THE RESEARCHER, AND WHAT IT REPLACED.
     *
     * An automatic write with no visible trace is exactly the bug the offer below was built to avoid
     * — a Bagru pincode saved onto a Dehradun record, because 95% of rural points return no postal
     * code and the stale value survived. So the write is loud instead of silent: this drives a notice
     * naming every box that changed, and one Undo that puts back precisely what was there.
     *
     * Cleared when a human edits any of the three boxes (see `setPlace`), and NOT cleared merely
     * because another lookup went out — which is where this deliberately parts company with the web
     * card. The live fix streams an update a second, so clearing the announcement at request time
     * would erase the Undo for a pin the researcher dropped a moment ago, on a lookup that is not
     * allowed to write anything anyway.
     */
    var autofill by remember { mutableStateOf<AppliedWrite?>(null) }
    /** The radius of the last fix that was too coarse to name a place, for the notice saying so. */
    var coarseFixMetres by remember { mutableStateOf<Double?>(null) }
    /*
     * The geocoder's reading of the current DEVICE point, for the parts of it the passive path was
     * not allowed to write, waiting to be accepted or waved away.
     *
     * IT IS NOT WRITTEN ANYWHERE UNTIL SOMEBODY TAPS. That is what separates the passive path from
     * what produced the fifteen wrong records: a device reading may fill a box that is EMPTY, but it
     * may not replace an answer a researcher gave, so what it would have said is put on the table
     * instead. On site that is one tap; at a desk in Kharagpur it is correctly declined, and
     * declining it costs nothing and leaves nothing behind.
     */
    var offer by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var offerPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    /*
     * The point moved, the map has nothing to say about where it moved to, and the boxes still hold
     * what this card copied in for the point BEFORE it.
     *
     * This is the last hiding place of the staleness bug, and it needed a third answer rather than
     * one of the two obvious ones. Keeping the old values silently is what put Bagru's pincode on a
     * Dehradun record. Clearing them silently is no better: a researcher affirmed those values by
     * tapping, and a form that quietly un-answers a question they answered is a form they stop
     * trusting. So it says what happened and offers the button, which is the only version where
     * nobody is guessing on anybody's behalf.
     */
    var appliedNowStale by remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(value)
    val lookup = remember { mutableStateOf<Job?>(null) }
    /** What the in-flight lookup is for, so a passive one cannot cancel an explicit one. */
    var lookupIntent by remember { mutableStateOf(PlaceIntent.Passive) }
    // The state the CURRENT coordinate is really in, when the geocoder can say and the answer is
    // worth showing. Read only — see the effect below.
    var coordinateState by remember { mutableStateOf("") }
    /*
     * The point this card itself produced, so a coordinate that came out of a RECORD can be told
     * apart from one this session captured.
     *
     * That distinction is what the flag below runs on, and it is better than an `isEdit` flag for
     * the same reason [LocationCaptureCard] does not trust one: none of the three call sites passes
     * it, every edit form fetches its record after mount, and a card that has to be told what it is
     * looking at will eventually be told wrong.
     */
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

    /*
     * R2 — A FIELD MAY ONLY BE MANDATORY WHERE IT IS ANSWERABLE, computed rather than typed.
     *
     * Two clauses, and both of them are load-bearing:
     *
     *   `required && !isEdit` is exactly what [artisanLocationRequirementError] enforces, and the
     *   mark has to match the rule. The asterisks here were literal text, so an EDIT — which the
     *   server deliberately does not ask for a state or a district, because the records written
     *   before those columns existed have neither — wore two marks nothing would ever enforce.
     *
     *   `listIsAnswerable(...)` is the half the OFFLINE_STATES incident was paid for: a required
     *   closed list with no members meant validation refused the submit, the save was never
     *   reached, and "the interview and its photographs die with the tab". It is written out even
     *   though the state list is normally there, because the invariant is what matters — this card
     *   never demands an answer it is not offering.
     */
    val stateRequired = required && !isEdit && listIsAnswerable(stateRows)
    val districtRequired = required && !isEdit && listIsAnswerable(districtRows)

    /*
     * R3 — THE CONTROL MUST SAY WHICH CASE IT IS IN. A picker that opens on nothing reads as
     * "there are none", and on a handset in a workshop with no signal the truthful reading is
     * almost always "this device has not been given the list yet". Those are opposite facts with
     * opposite next moves and until now they looked identical here.
     */
    val stateNotice = addressListNotice(
        "states",
        stateRows.size,
        referenceState,
        // The rows are the compiled-in floor exactly when neither served list arrived, which is what
        // [stateOptions]'s `ifEmpty` chain falls through to. Derived from the same condition rather
        // than from a count, so the two cannot disagree about which list is on screen.
        bundled = referenceState.reference.statesAndUnionTerritories.isEmpty() &&
            referenceState.reference.states.isEmpty()
    )
    val districtNotice = when {
        // NOT AN EMPTY-LIST CLAIM AT ALL. With no state chosen there is no list to be empty; the
        // placeholder below says what to do and this must not talk over it with a sentence about
        // a district register that was never consulted.
        place.state.isBlank() -> null
        districtRows.isNotEmpty() -> addressListNotice("districts", districtRows.size, referenceState)
        /*
         * THE REFERENCE ARRIVED AND CARRIES NO DISTRICTS FOR THIS STATE. That is a different fact
         * from "this phone has not been given the list", and it needs a different sentence: 795
         * districts genuinely cannot be bundled into an APK, and a deployment whose reference
         * payload stops at the state level is a real and supported configuration rather than a
         * fault. The operational half is only true where a create is actually being refused, so it
         * is said only there — on an edit, and on the designer's own profile, the record saves
         * perfectly well without a district and promising otherwise would send somebody hunting
         * for a signal they do not need.
         */
        reference.statesAndUnionTerritories.isNotEmpty() || reference.states.isNotEmpty() ->
            "The district list on this phone has nothing for ${place.state}. That is not a claim " +
                "that it has no districts — the reference this phone last received does not carry " +
                "them. Connect once and it is kept on the device for good, after which this " +
                "dropdown works with no signal." +
                // GATED ON "THIS IS A CREATE", NOT ON [districtRequired] — which is false right
                // here, because it ends in `listIsAnswerable` and this branch is the one where the
                // list is empty. Written the other way the sentence could never appear on the one
                // form it is about.
                if (required && !isEdit) {
                    " Until then a NEW record cannot be started, because the API asks every new " +
                        "record for a district; an existing one can still be corrected and saved."
                } else {
                    ""
                }
        // Nothing has arrived at all. Loading, offline or refused — whichever it is, 3.5 has the
        // sentence and it is the same one the state box above is printing.
        else -> addressListNotice("districts", districtRows.size, referenceState)
    }

    /*
     * FOUR ANSWERS THAT CANNOT BE SAVED YET, REPORTED TO THE FORM — see
     * [onStatedAddressNeedsCoordinate]. Raised from an effect rather than read during composition,
     * so a host that turns this into form state cannot loop.
     */
    val statedAddressNeedsCoordinate = value == null && !place.isEmpty
    val reportNeedsCoordinate = rememberUpdatedState(onStatedAddressNeedsCoordinate)
    LaunchedEffect(statedAddressNeedsCoordinate) {
        reportNeedsCoordinate.value(statedAddressNeedsCoordinate)
    }

    /** Write one edited artisan answer back, wherever the answers currently live. */
    fun setPlace(next: StatedPlace) {
        // A human has just had their say about these boxes, so there is nothing left to warn about.
        appliedNowStale = false
        /*
         * And nothing left to offer taking back, either. Once a researcher has edited one of the
         * three boxes, an Undo restoring the snapshot from before the write would overwrite the edit
         * they just made — a second silent overwrite, wearing the hat of the control that exists to
         * prevent the first. The announcement goes rather than half of it being honoured.
         *
         * `applyPlace` and `undoAutofill` both write through here and then set this themselves,
         * which is why clearing it unconditionally is safe.
         */
        autofill = null
        val current = latest.value
        if (current == null) parked = next else onChange(current.withStatedPlace(next))
    }

    /**
     * Fold the artisan answers onto whatever coordinate the captured-at card just produced.
     *
     * [LocationCaptureCard] rebuilds its [LocationRequest] from scratch on every change, so
     * anything not re-applied here is dropped the next time a digit is typed into the latitude box.
     */
    fun emitCoordinate(next: LocationRequest?) {
        if (next == null) {
            // The coordinate was cleared and the row the answers live on goes with it. Park them so
            // clearing a mis-tapped pin does not also silently discard a typed district.
            parked = place
            sessionPoint = null
            onChange(null)
            return
        }
        sessionPoint = next.latitude to next.longitude
        // A reading is dated when it is TAKEN. Carrying the stored stamp through an unchanged
        // coordinate is what stops opening a record for an unrelated correction from re-dating a
        // measurement nobody re-took.
        val moved = !sameCoordinate(next, value)
        onChange(
            next
                .copy(capturedAt = if (moved) isoNow() else value?.capturedAt)
                .withStatedPlace(place)
        )
    }

    /**
     * Write a point's address into the three boxes it is allowed to touch, and report what changed.
     *
     * [intent] IS THE WHOLE DIFFERENCE, and it is not a preference:
     *
     *   * EXPLICIT — the researcher has just pointed at the artisan's place, or tapped "Use …" on an
     *     offer. That is a request for THIS place's address, so every box is written, INCLUDING with
     *     a BLANK pincode. Leaving the previous point's PIN standing under this point's coordinates
     *     is exactly how a Bagru pincode ended up on a Dehradun record, and 57 of 60 sampled rural
     *     Indian points carry no postal code at all — so "no answer" is the ORDINARY answer here and
     *     reading it as "leave what is there" is the staleness bug itself, not a kindness.
     *   * PASSIVE — a fix arrived by itself, or the device's own position was captured. Only EMPTY
     *     boxes are filled and nothing is ever overwritten. A researcher at a desk in Kharagpur
     *     documenting a Bagru artisan must not have "Rajasthan" replaced by "West Bengal", and one
     *     typing the district while the receiver warms must not have it replaced a second later by a
     *     satellite.
     *
     * Returns what it wrote and what it replaced — with [AppliedWrite.fields] empty when the point
     * had nothing to add — so the caller can offer the leftovers, and so the write can be announced
     * and undone from one place rather than at each call site.
     */
    fun applyPlace(fresh: PlaceSuggestion, intent: PlaceIntent): AppliedWrite {
        // Wherever the answers currently live: a pin can be dropped before any coordinate exists, in
        // which case the boxes are the parked ones and there is no LocationRequest to read.
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
        /*
         * THE STATE GATES THE OTHER TWO, and it gates them differently.
         *
         * A DISTRICT NEEDS A POSITIVE MATCH. It is only meaningful inside its own state — Bilaspur
         * belongs to two of them, which is why the API resolves a district WITHIN a state — so it may
         * be written only when the state now standing in the box IS the state the geocoder resolved
         * it in. `suggestPlaceFor` already refuses to resolve a district under a state that did not
         * match the served list; this is the other half of the same rule, and on the passive path it
         * is the half that does the work. A researcher at a desk in Kharagpur who has typed Rajasthan
         * for a Bagru artisan must not have Paschim Medinipur dropped in underneath it: that pairing
         * is one the API refuses and no export could interpret. It goes into the offer instead, where
         * accepting it is an explicit act and replaces both halves at once.
         *
         * A PINCODE NEEDS ONLY NOT TO BE CONTRADICTED. A code is a code OF a state — the leading
         * digit is the postal zone — so 721302 written under a typed "Rajasthan" is a contradiction
         * the API's own zone check would then accuse the researcher of having made. But with no state
         * in the box there is nothing to contradict, and the code is still this point's own answer,
         * so a positive match is more than this needs.
         */
        val stateAgrees = fresh.state.isNotBlank() && next.state.equals(fresh.state, ignoreCase = true)
        val district = if (stateAgrees) fresh.district else ""
        val contradicted = next.state.isNotBlank() && fresh.state.isNotBlank() && !stateAgrees
        val pincode = if (contradicted) "" else fresh.pincode

        // Written even when BLANK on the explicit path: a district belonging to the point before this
        // one is worse than no district, and the state above may have just changed underneath it.
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

        // `toList()` because this outlives the function: an AppliedWrite parked in composable state
        // must not share a mutable list with the builder above it.
        val write = AppliedWrite(fields = touched.toList(), previous = previous, intent = intent)
        if (touched.isEmpty()) return write
        applied = applied.crediting(touched, next.geocodable())
        // A pincode this card wrote has not been abandoned half-typed by anybody, so the on-blur
        // complaint must not fire over it — and a blank one it wrote is not a problem at all.
        pincodeProblemShown = false
        setPlace(next)
        // AFTER setPlace, which drops any earlier announcement: this is the one that stands now.
        autofill = write
        return write
    }

    /** Put back exactly what was in the three boxes before the last automatic write. */
    fun undoAutofill() {
        val write = autofill ?: return
        val held = latest.value?.statedPlace() ?: parked
        // The restored values are a human's again, so this card is no longer their author.
        applied = applied.crediting(write.fields, PlaceSuggestion())
        setPlace(
            held.copy(
                state = write.previous.state,
                district = write.previous.district,
                pincode = write.previous.pincode
            )
        )
    }

    /**
     * Look up what is at a point and hand the answer to [applyPlace].
     *
     * [accuracy] is the radius of the reading, and null for a hand-placed pin — which is what exempts
     * a pin from the coarse-fix guard below. Pass the pin's own coordinates on the explicit path: the
     * artisan's address is resolved from where the researcher said the ARTISAN is, never from where
     * the device happens to be standing.
     */
    fun lookupPlace(lat: Double, lng: Double, accuracy: Double?, intent: PlaceIntent) {
        /*
         * AN EXPLICIT LOOKUP OUTRANKS A PASSIVE ONE. The live fix streams an update a second, so
         * without this a pin dropped on the artisan's place would routinely have its lookup
         * cancelled by a satellite update that is not allowed to write anything anyway — and the
         * explicit path would then fail at random, which is the worst way for it to fail.
         */
        val running = lookup.value
        val explicitInFlight = lookupIntent == PlaceIntent.Explicit && running?.isActive == true
        if (intent == PlaceIntent.Passive && explicitInFlight) return
        running?.cancel()
        lookupIntent = intent
        // The previous point's offer is off the table whatever happens next, and it is dropped when
        // the request GOES OUT rather than when it comes back: the seconds a rural lookup takes are
        // exactly when the last place's answer sits under this place's coordinates.
        offer = null
        offerPoint = null
        coarseFixMetres = coarseRadius(accuracy)
        // ABSOLUTE, AND NOT TO BE SOFTENED — see [GEOCODE_ACCURACY_LIMIT_METRES]. Above the line
        // there is no write and no offer either, only the notice explaining the silence.
        if (coarseFixMetres != null) return

        lookup.value = scope.launch {
            delay(LOOKUP_DEBOUNCE_MS)
            val fresh = suggestPlaceFor(context, lat, lng, accuracy, reference)

            /*
             * The researcher may have moved on while the lookup ran, and the newer point is the one
             * that must survive — so an answer about a point that is no longer the one in question
             * is dropped rather than written. WHICH point that is differs by path: the explicit
             * answer belongs to the artisan's pin and the passive one to the captured coordinate.
             */
            when (intent) {
                PlaceIntent.Explicit -> {
                    // The pin itself is the point in question, so the pin is what is checked — a
                    // researcher who moved it again, or removed it, has retracted this question.
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
                /*
                 * NOTHING FOUND IS NOT NOTHING TO SAY — and it is deliberately NOT treated as "this
                 * place has no address", which is the one shortcut that would undo the rest of this
                 * function. A geocoder that is absent from the build, rate-limited, or simply offline
                 * in a rural workshop returns exactly this, so blanking the boxes here would let a
                 * dropped connection erase a researcher's typed answer.
                 *
                 * What is left is the third answer, and it needed to be a third one. The boxes still
                 * hold what was copied in for the PREVIOUS point: keeping that silently is what put
                 * Bagru's pincode on a Dehradun record, and clearing it silently would un-answer a
                 * question a researcher answered. So the card says what happened and offers the
                 * button, which is the only version where nobody guesses on anybody's behalf.
                 */
                val held = latest.value?.statedPlace() ?: parked
                val stale = !applied.isEmpty && (
                    (applied.state.isNotBlank() && held.state == applied.state) ||
                        (applied.district.isNotBlank() && held.district == applied.district) ||
                        (applied.pincode.isNotBlank() && held.pincode == applied.pincode)
                    )
                appliedNowStale = stale
                // The warning describes the same values and carries its own control, so leaving the
                // announcement of the write it supersedes would be a second story about one thing.
                if (stale) autofill = null
                if (intent == PlaceIntent.Explicit) {
                    // The researcher asked a direct question by tapping the map and is owed an
                    // answer even when the answer is "the map does not know".
                    onMessage("The map has no address for that pin — set the state and district yourself.")
                }
                return@launch
            }

            appliedNowStale = false
            val write = applyPlace(fresh, intent)
            // Everything was written on the explicit path, and the notice says which. There is
            // nothing left to put on the table.
            if (intent == PlaceIntent.Explicit) return@launch
            /*
             * PASSIVE ONLY. Whatever the fix could not fill — because a human had already answered
             * it — is still worth OFFERING, so a disagreement between the device and the typed answer
             * is visible rather than swallowed. Read against the write's own snapshot rather than the
             * boxes: the write has only just gone out through `onChange` and `latest` does not catch
             * up until the next composition.
             */
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

    /**
     * Take the offer, whole.
     *
     * Tapping "Use Dehradun, Uttarakhand" is an EXPLICIT request for that place's address, so it
     * overwrites — which is both what the words on the button promise and the only behaviour in which
     * they are not a lie. The alternative shipped here until now: the accept path protected any box a
     * human had answered, so a researcher who tapped "Use Dehradun, Uttarakhand" over a typed
     * "Rajasthan" watched the button do nothing to the state.
     *
     * INCLUDING THE PARTS THAT ARE EMPTY. A point with no postal code clears the pincode box rather
     * than leaving the last point's six digits standing under this point's state, which is the
     * original bug wearing its last available disguise. A typed pincode is lost when this is pressed
     * and that is the right trade: the button is a request for a different place's address, a value
     * from the place before it is not worth more than the one just asked for, and the box is one
     * keystroke from being right — with the Undo beside it in the meantime.
     */
    fun acceptOffer(fresh: PlaceSuggestion) {
        offer = null
        offerPoint = null
        applyPlace(fresh, PlaceIntent.Explicit)
    }

    /** Drop only what this card copied in, leaving anything a human typed exactly where it is. */
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

    /*
     * FLAG, NEVER REWRITE.
     *
     * On a record that already has both a coordinate and a stated state, ask the geocoder which
     * state the coordinate is actually in and say so when the two disagree — which is the case on
     * all fifteen live records, whose coordinates are in West Bengal and whose stated places are in
     * Rajasthan, Gujarat, Uttarakhand and Andhra Pradesh. It reads; it never writes. The researcher
     * who was there decides whether the coordinate was the desk or the workshop, and this cannot
     * know.
     */
    LaunchedEffect(value?.latitude, value?.longitude, reference.version) {
        coordinateState = ""
        val current = value ?: return@LaunchedEffect
        // Only a coordinate that arrived from the RECORD is worth reporting on. One captured a
        // moment ago is already described, accurately, by the card that captured it.
        if (sessionPoint == current.latitude to current.longitude) return@LaunchedEffect
        val metres = current.accuracy
        if (metres != null && metres > GEOCODE_ACCURACY_LIMIT_METRES) return@LaunchedEffect
        val (adminArea, _, _) = geocode(context, current.latitude, current.longitude)
        val served = reference.statesAndUnionTerritories.ifEmpty { reference.states }
        coordinateState = adminArea?.let { matchIndianState(it, served) }.orEmpty()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // ----- Group one: the subject's stated location -----
        GroupHeading(title = subject.heading, subtitle = subject.subtitle)

        /*
         * WHAT WAS JUST WRITTEN, AND HOW TO UNDO IT.
         *
         * A form that fills itself in silently is the bug the offer below was built to avoid — a
         * Bagru pincode saved onto a Dehradun record, because 95% of rural points return no postal
         * code and the stale value survived with nothing on screen saying anything had happened.
         * Filling boxes in automatically is fine; doing it invisibly is not. So every automatic write
         * names the boxes it touched and offers exactly one button that restores what was there.
         */
        val written = autofill
        if (written != null) {
            // Which act wrote it, because the two mean different things to a reader: one is their own
            // pin read back to them, the other is a machine that found some boxes empty.
            val source = when (written.intent) {
                PlaceIntent.Explicit -> "Filled in from the place you pointed at: "
                PlaceIntent.Passive -> "Filled in from this device's location (only the boxes that " +
                    "were empty): "
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    source + fieldNames(written.fields) + ". Check it, and change anything that is " +
                        "wrong — you know this place and the geocoder does not.",
                    color = MaterialTheme.field.body,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = { undoAutofill() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Undo") }
            }
        }

        /*
         * The offer: what the PASSIVE path read off the device's own point and was not allowed to
         * write, because a researcher had already answered those boxes. One tap to take it, one to
         * wave it away, and nothing at all if it is ignored.
         *
         * It names the coordinate and the radius it was read from, because "is this where the
         * artisan is?" is not a question anybody can answer about an unnamed point — and being able
         * to answer it is the difference between a researcher standing in the workshop and one
         * sitting at a desk fifteen hundred kilometres away, which is the entire finding.
         */
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
                    "This device is at ${trimCoordinate(pendingPoint.first)}, " +
                        "${trimCoordinate(pendingPoint.second)}" +
                        (value?.accuracy?.let { " (${radiusLabel(it)})" } ?: "") +
                        ", and the map calls that $named. Use it as ${subject.possessive} location?",
                    color = MaterialTheme.field.body,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { acceptOffer(pending) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
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
                    "The location moved, and the map has nothing to say about the new point — which " +
                        "is the ordinary answer in rural India rather than a fault. The state, " +
                        "district and pincode below still describe the PREVIOUS point. Check them, " +
                        "or clear the ones that were copied in.",
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = { clearApplied() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Clear the copied answers") }
            }
        }

        /*
         * THE COARSE FIX, NAMED, and the reason no address arrived with it.
         *
         * The coordinates ARE kept: dropping them would leave the record with no location at all over
         * a radius nobody would otherwise have noticed, and a rough position beats none. What must not
         * happen is a district arriving from a 2.5 km circle — silently, or with a one-tap Yes beside
         * it, which is no better.
         *
         * Shown only while it explains something the researcher can see. With the state and the
         * district both answered there was nothing for the fix to add in the first place, and the
         * live stream would otherwise re-raise this every second it reported.
         */
        val coarse = coarseFixMetres
        if (coarse != null && (place.state.isBlank() || place.district.isBlank())) {
            GroupNotice(
                warn = true,
                text = "No address was read from this device's location: the fix is only accurate to " +
                    "${radiusLabel(coarse)}, which is its network estimate of where it is rather " +
                    "than a satellite reading, and a circle that wide covers more than one district. " +
                    "The coordinates have been kept with their radius, so nothing is lost. Choose the " +
                    "state and district below, or pin ${subject.possessive} place on the map — a pin " +
                    "has no radius to be wrong about."
            )
        }

        SearchableSelectField(
            // COMPUTED, NEVER TYPED — see [requiredLabel]. This label carried a literal " *" and so
            // marked an empty closed list as something that had to be answered.
            label = requiredLabel("State / union territory", stateRequired),
            options = stateRows,
            selectedValue = place.state,
            /*
             * STOOD DOWN WHEN THERE IS NOTHING TO PICK, which is what turns [stateNotice] into a
             * sentence ON THE FORM: `SearchableSelectField` prints its `emptyMessage` beneath a
             * disabled, empty control precisely because a disabled trigger cannot be opened to
             * find out why. An enabled trigger over an empty list opens a popup with nothing in
             * it, which is the wordless version of "there are none".
             */
            enabled = listIsAnswerable(stateRows),
            /*
             * "Loading the state list…" IS FALSE FOR EVER ON A PHONE THAT HAS NEVER BEEN ONLINE,
             * and that is DROPDOWN_DESIGN.md 3.2's B2. It was shown whenever both served lists were
             * empty, which on a fresh install is a permanent state rather than a transient one:
             * nothing is loading, nothing will load, and the sentence reads as something to wait
             * through. The honest sentence for each of the three empty states is in [stateNotice];
             * this now says only what a placeholder can truthfully say.
             */
            placeholder = "Select state",
            emptyMessage = stateNotice,
            /*
             * PINNED OPEN RATHER THAN LEFT TO THE COUNT (3.6). Thirty-six states is over
             * SEARCH_THRESHOLD so this normally makes no difference at all — but a list that
             * shrinks offline would otherwise lose its filter box, its "N options" live region and
             * its empty sentence in one step, and a reader cannot learn a control that changes
             * shape with what the network did. The web forces the same flag on both address
             * fields for the same reason.
             */
            searchable = true,
            onSelect = { next ->
                // A district only exists inside its own state, so changing the state discards a
                // district that is now in the wrong one rather than leaving a pairing the server
                // would refuse and no export could interpret.
                val keepDistrict = next.equals(place.state, ignoreCase = true)
                setPlace(place.copy(state = next, district = if (keepDistrict) place.district else ""))
            }
        )
        stateNotice?.takeIf { listIsAnswerable(stateRows) }?.let { line ->
            // Drawn here only for the CACHED case, where the control is enabled and the primitive
            // therefore prints nothing of its own. The empty cases are already on screen, once,
            // under the stood-down control — saying them twice would be two facts to a reader.
            Text(line, color = MaterialTheme.field.muted, fontSize = 12.sp)
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
            // 3.6's second reason, and the one the district field IS: Goa has 2 districts, Sikkim 6
            // and Uttar Pradesh 75, so left to the count this control is an anchored menu under one
            // answer and a bottom sheet under the next. A reader cannot learn a control that
            // changes shape with the answer above it.
            searchable = true,
            onSelect = { setPlace(place.copy(district = it)) }
        )
        districtNotice?.takeIf { place.state.isNotBlank() && listIsAnswerable(districtRows) }?.let { line ->
            Text(line, color = MaterialTheme.field.muted, fontSize = 12.sp)
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
                        "The settlement itself — just its name. The state and district go in the " +
                            "two boxes above rather than into this one.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = place.pincode,
                // Filtered rather than validated after the fact, so a pasted "380 001" becomes six
                // digits instead of an error message about spaces.
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
                        shown ?: "Six digits, if you know it. Most rural points have no postcode " +
                            "the geocoder can find, which is why the district above is the one " +
                            "that is required.",
                        color = if (shown != null) MaterialTheme.colorScheme.error else MaterialTheme.field.muted,
                        fontSize = 12.sp
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

        // The subject's own pin. Optional, and deliberately a separate coordinate from the one the
        // GPS writes: a statement about the subject that shared the captured-at row would be
        // overwritten by the next fix.
        if (place.pinLat.isNotBlank() && place.pinLng.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${subject.pinLabel}: ${place.pinLat}, ${place.pinLng}",
                    color = MaterialTheme.field.body,
                    fontSize = 12.sp,
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
            // The API keeps this half of the address on the location row, which cannot exist
            // without a coordinate — so say it here rather than letting four answers vanish at save
            // time. A host that can refuse the save is told as well, through
            // [onStatedAddressNeedsCoordinate]; a notice above the fold is not read at the moment
            // somebody presses Save.
            GroupNotice(
                warn = true,
                text = "Add a captured location below — a GPS fix, a map pin or typed coordinates. " +
                    "The state, district, village and pincode are stored on the same row as the " +
                    "coordinates, and without one they are not saved."
            )
        }

        /*
         * FLAGGED, NEVER REWRITTEN.
         *
         * Two shapes of the same problem. Either the record states a location and its coordinates
         * are somewhere else, or it states none at all and its coordinates are the only thing a
         * reader has — which is the condition of all fifteen live records, whose coordinates are in
         * West Bengal and whose places were typed into a free-text box as Bagru, Kutch and
         * Rudraprayag. Both say what was found and change nothing. The researcher who was there is
         * the only one who knows whether the coordinate was the workshop or the desk, and a
         * migration that guessed would bury the evidence it guessed from.
         */
        if (coordinateState.isNotBlank() && place.state.isNotBlank() &&
            !coordinateState.equals(place.state, ignoreCase = true)
        ) {
            GroupNotice(
                warn = true,
                text = "The coordinates saved on this record are in $coordinateState, but its " +
                    "stated location says ${place.state}. That is normal if the record was written " +
                    "up away from the workshop — the coordinates say where the device was, not " +
                    "where ${subject.label} is. Nothing has been changed. Correct whichever of the " +
                    "two is wrong."
            )
        } else if (coordinateState.isNotBlank() && place.state.isBlank()) {
            GroupNotice(
                warn = true,
                text = "This record has coordinates in $coordinateState and no stated location. " +
                    "Until this form existed the coordinates were the only location a record had, " +
                    "and they say where the device was — often a desk a long way from the workshop. " +
                    "Please say where ${subject.label} is above. Nothing has been changed or " +
                    "guessed."
            )
        }

        HorizontalDivider(color = MaterialTheme.field.hairline)

        // ----- Group two: provenance -----
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
                    contentDescription = "Captured at. $summary. Provenance only, not " +
                        "${subject.possessive} location."
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
                    fontSize = 15.sp
                )
                Text(summary, color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
            Text(
                if (expanded) "Hide" else "Show",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }

        // The requirement is announced even while collapsed: a researcher must not have to open a
        // panel to discover why Save refused. Not repeated once open — the card inside says it.
        if (required && value == null && showRequirementError && !expanded) {
            Text(LOCATION_REQUIRED_MESSAGE, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                "Where this device was when the record was written, and how sure it is. " +
                    "Provenance — it is not ${subject.possessive} location and nothing reads it " +
                    "as one. " +
                    "Filled in automatically; correct it only if the device got it wrong." +
                    when {
                        value == null -> ""
                        stamp.isNotEmpty() -> " Taken $stamp."
                        // Every coordinate stored before this change is undated, and saying so is
                        // the point: an undated reading cannot be told apart from one taken a month
                        // later in another state.
                        else -> " This reading carries no timestamp — it predates the field."
                    },
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }
        /*
         * OUTSIDE the AnimatedVisibility, and drawing nothing while the panel is shut.
         *
         * The automatic fix is an effect of this card being composed, and AnimatedVisibility does
         * not compose what it is not showing — so folding the card inside it produced a form that
         * captured nothing until somebody thought to open a panel labelled "provenance", on a
         * record that cannot be saved without a coordinate. `collapsed` is what lets the panel be
         * shut by default and the capture still happen.
         */
        LocationCaptureCard(
            value = value,
            onChange = { next ->
                val hadCoordinate = value != null
                emitCoordinate(next)
                /*
                 * THE PASSIVE PATH, all of it: the fix taken when the form opens, "Use current GPS",
                 * the pin dropped on the capture map, and typed coordinates. Every one of them says
                 * where the DEVICE is, which is why none of them may overwrite a statement about the
                 * artisan — they fill what is blank and offer the rest.
                 *
                 * Only a NEW point is worth a lookup; retyping a decimal place is not.
                 */
                if (next != null && (!hadCoordinate || !sameCoordinate(next, value))) {
                    lookupPlace(next.latitude, next.longitude, next.accuracy, PlaceIntent.Passive)
                }
            },
            required = required,
            isEdit = isEdit,
            showRequirementError = showRequirementError,
            title = if (required) "Captured coordinates *" else "Captured coordinates",
            description = "The device's own reading. A pin or two typed numbers satisfy this " +
                "exactly as a satellite fix does.",
            collapsed = !expanded,
            // A permission prompt, a dead location switch or a minute with no fix all need the
            // researcher, and none of them can be read through a shut panel.
            onNeedsAttention = { if (it) expanded = true },
            onMessage = onMessage
        )
    }

    if (showArtisanMap) {
        MapPickerDialog(
            initialLat = place.pinLat.toDoubleOrNull() ?: value?.latitude,
            initialLng = place.pinLng.toDoubleOrNull() ?: value?.longitude,
            onDismiss = { showArtisanMap = false },
            /*
             * THE EXPLICIT PATH, and the only one in this file that overwrites.
             *
             * The researcher has pointed at the artisan's place, on a map, deliberately. That is a
             * direct assertion about where the artisan is, so the state, district and pincode of that
             * point are written over whatever was there — asking them to then confirm the state and
             * district implied by their own pin is a second question about one answer, and a second
             * question about one answer gets tapped through rather than read.
             *
             * The write is announced and undoable (see `autofill`) because an automatic write nobody
             * can see is how a Bagru pincode ended up on a Dehradun record. Announced and reversible
             * is a different thing from silent.
             *
             * The pin carries no accuracy, so the coarse-fix guard does not apply and correctly does
             * not: the researcher pointed at the place, and a pointer has no error radius.
             */
            onPick = { lat, lng ->
                setPlace(place.copy(pinLat = trimCoordinate(lat), pinLng = trimCoordinate(lng)))
                onMessage("${subject.pinLabel} set: ${trimCoordinate(lat)}, ${trimCoordinate(lng)}")
                showArtisanMap = false
                lookupPlace(lat, lng, accuracy = null, intent = PlaceIntent.Explicit)
            }
        )
    }
}
