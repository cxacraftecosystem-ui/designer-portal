package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.LocationRequest
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.LocationFieldsSection
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Location, using the components the rest of this app already spent its accuracy budget on.
 *
 * ── WHY THE TWO TYPED BOXES WERE NOT ENOUGH ───────────────────────────────────────────────────
 *
 * The first version of the GEO field drew a latitude box, a longitude box and the map picker, and
 * argued that adapting [LocationFieldsSection] would mean "inventing a place name for every reading".
 * The argument was backwards. What that component adds is not a place NAME — it is the state and the
 * district, chosen from the canonical lists the server serves at `GET /reference/address` and
 * validated against them, plus the pincode and the village. Those are not decoration. They are the
 * columns every downstream query groups by, and this app already has fifteen live records that put
 * Rajasthani artisans in West Bengal precisely because a form captured a coordinate and let a human
 * type the administrative half from memory.
 *
 * So a GEO field here is the real card: the live fix, the map pin, the canonical state/district
 * dropdowns, the pincode check, and the "this was filled in for you, here is Undo" notice that stops
 * a Bagru pincode being saved onto a Dehradun record.
 *
 * ── WHAT SURVIVES THE SERVER, AND WHAT DOES NOT ───────────────────────────────────────────────
 *
 * SAY THIS PLAINLY, because a reader of the stored data has to know. The registry's GEO type is
 * `{lat, lon, accuracy}` and the server's coercion (`stage_schema._coerce`, the GEO branch) builds a
 * NEW dict from exactly those three keys — anything else in the object is dropped on sync, silently,
 * as unknown keys are everywhere in this protocol.
 *
 * The extra keys are still stored, and they are still worth storing, because on this feature the
 * local draft is the document: the report is generated on the device from `draft.json`, so a state
 * and a district captured here print in the .docx even though the server will never hold them. What
 * they will not survive is a stage re-seeded FROM the server onto a fresh handset. That is a real
 * limitation and it is recorded here rather than discovered later; closing it needs a registry
 * change (a GEO that carries an address, or a sibling ENUM per administrative level), which is a
 * server decision and not one a client may make on its own.
 */

/** The stored shape of a GEO answer, read back into the card's own type. */
internal fun dwLocationFromValue(value: JsonElement?): LocationRequest? {
    val obj = value as? JsonObject ?: return null
    val lat = (obj["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val lon = (obj["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
    fun text(key: String): String? =
        (obj[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    return LocationRequest(
        latitude = lat,
        longitude = lon,
        accuracy = (obj["accuracy"] as? JsonPrimitive)?.doubleOrNull,
        state = text("state"),
        district = text("district"),
        village = text("village"),
        pincode = text("pincode"),
        placeName = text("placeName"),
    )
}

/**
 * The card's answer as the registry stores it.
 *
 * `lat`, `lon` and `accuracy` are written with exactly the spellings the server's coercion looks for
 * — get one of them wrong and the whole reading is rejected as "not a valid coordinate", which the
 * designer would see as a location that refuses to save with no explanation of which half is wrong.
 * The administrative keys follow, and are dropped server-side; see the note at the top of this file.
 */
internal fun dwLocationToValue(location: LocationRequest?): JsonElement? {
    if (location == null) return null
    return JsonObject(
        buildMap {
            put("lat", JsonPrimitive(location.latitude))
            put("lon", JsonPrimitive(location.longitude))
            location.accuracy?.let { put("accuracy", JsonPrimitive(it)) }
            location.state?.takeIf { it.isNotBlank() }?.let { put("state", JsonPrimitive(it)) }
            location.district?.takeIf { it.isNotBlank() }?.let { put("district", JsonPrimitive(it)) }
            location.village?.takeIf { it.isNotBlank() }?.let { put("village", JsonPrimitive(it)) }
            location.pincode?.takeIf { it.isNotBlank() }?.let { put("pincode", JsonPrimitive(it)) }
            location.placeName?.takeIf { it.isNotBlank() }?.let { put("placeName", JsonPrimitive(it)) }
        }
    )
}

/**
 * A registry GEO field.
 *
 * [required] follows the registry rather than the card's own default, so a GEO the source matrix
 * marked optional does not start demanding a state and a district — `artisanLocationRequirementError`
 * is for the artisan record form and would block a save the registry never asked to block.
 */
@Composable
internal fun DwGeoField(
    label: String,
    help: String,
    required: Boolean,
    value: JsonElement?,
    repository: WorkshopRepository?,
    enabled: Boolean,
    error: String?,
    onChange: (JsonElement?) -> Unit,
    onMessage: (String) -> Unit,
) {
    val current = remember(value) { dwLocationFromValue(value) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(requiredMarked(label), color = MaterialTheme.field.muted, fontSize = 12.sp)
        if (help.isNotBlank()) {
            Text(help, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        if (repository == null) {
            // The state and district dropdowns are served, so the card cannot exist without something
            // to serve them. Saying so beats drawing a card whose two most important controls are
            // permanently empty and look broken.
            Text(
                "The location controls need this workshop to be open for editing.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        } else {
            LocationFieldsSection(
                repository = repository,
                value = current,
                required = required,
                // Every GEO field in a design workshop is being EDITED as often as created — a stage
                // is reopened a dozen times over a fortnight — and `isEdit` is what stops the card
                // taking an automatic fix on every one of those openings. Without it, reopening
                // stage 4 in the guest house would quietly overwrite the coordinate captured in the
                // village that morning with the guest house's own.
                isEdit = current != null,
                onChange = { next -> onChange(dwLocationToValue(next)) },
                onMessage = onMessage,
            )
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (!enabled) {
            Text("This stage is read-only.", color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
    }
}

/**
 * WHERE THIS STAGE WAS RECORDED — one control, on every stage, above the fields.
 *
 * ── WHY EVERY STAGE AND NOT JUST THE ONES WITH A GEO FIELD ────────────────────────────────────
 *
 * The registry gives a handful of entities a GEO field because the SUBJECT has a location: the
 * cluster, the artisan's workshop, the site of a demonstration. That is a different question from
 * where the designer was standing when they wrote the stage down, and the second question has no
 * field anywhere in the registry — yet it is the one that makes the record auditable. A 22-stage
 * report is assembled over a fortnight across several villages, and a ministry reviewer asking "was
 * stage 14 written at the cluster or typed up afterwards in Jaipur?" currently has nothing to read.
 *
 * ── WHERE IT IS STORED, AND WHY UNDER AN UNDERSCORE ───────────────────────────────────────────
 *
 * In the stage's singleton values, under a key beginning `_`. That prefix is the sync protocol's own
 * marker: [com.designprototype.workshop.ui.designworkshop] strips every underscore key out of the
 * body before a stage is PUT, so this answer stays on the device by construction rather than by
 * anyone remembering to exclude it. That is the correct outcome today, because the server has no
 * column for it and a key it does not recognise is dropped and reported as "the server did not
 * recognise 1 field" — a red line on every single stage, on every sync, for a value that was never
 * going to be stored.
 *
 * It survives in the local draft, which is what the report is generated from, so the provenance
 * prints even though it never leaves the phone. When the registry grows a real field for it, the
 * migration is to copy this key into that field, and this comment is the note that says so.
 *
 * Collapsed by default and expanded once answered: it is a question asked once per stage, and a
 * location card sitting open above every form pushes the first actual field below the fold.
 */
internal const val DW_RECORDING_PLACE_KEY = "_recordingPlace"

@Composable
internal fun DwRecordingPlaceCard(
    value: JsonElement?,
    repository: WorkshopRepository?,
    onChange: (JsonElement?) -> Unit,
    onMessage: (String) -> Unit,
) {
    val current = remember(value) { dwLocationFromValue(value) }
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Place of recording",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    current?.let { describePlace(it) }
                        ?: "Not recorded — where were you when this stage was filled in?",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.field.muted
            )
        }
        if (expanded) {
            if (repository == null) {
                Text(
                    "The location controls need a connection at least once, to load the state and " +
                        "district lists.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            } else {
                LocationFieldsSection(
                    repository = repository,
                    value = current,
                    // NEVER required. This is provenance, not data the report depends on, and a stage
                    // that refuses to save because nobody answered an optional question about where
                    // they were standing is a stage that gets abandoned.
                    required = false,
                    isEdit = current != null,
                    onChange = { next -> onChange(dwLocationToValue(next)) },
                    onMessage = onMessage,
                )
            }
        }
    }
}

/** "Bagru, Jaipur, Rajasthan" — or the coordinate, when nobody named the place. */
private fun describePlace(location: LocationRequest): String {
    val named = listOfNotNull(
        location.village?.takeIf { it.isNotBlank() },
        location.district?.takeIf { it.isNotBlank() },
        location.state?.takeIf { it.isNotBlank() },
    )
    if (named.isNotEmpty()) return named.joinToString(", ")
    return String.format(
        java.util.Locale.ROOT,
        "%.5f, %.5f",
        location.latitude,
        location.longitude
    )
}
