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
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

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
    helpRow: (@Composable () -> Unit)? = null,
) {
    val current = remember(value) { dwLocationFromValue(value) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(requiredMarked(label), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        if (helpRow != null) {
            helpRow()
        } else if (help.isNotBlank()) {
            Text(help, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (repository == null) {
            Text(
                "Location opens once you can edit this workshop.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            LocationFieldsSection(
                repository = repository,
                value = current,
                required = required,
                isEdit = current != null,
                onChange = { next -> onChange(dwLocationToValue(next)) },
                onMessage = onMessage,
            )
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (!enabled) {
            Text("This stage is read-only.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    current?.let { describePlace(it) }
                        ?: "Not recorded yet",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
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
                    "Needs a connection once to load states and districts.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LocationFieldsSection(
                    repository = repository,
                    value = current,
                    required = false,
                    isEdit = current != null,
                    onChange = { next -> onChange(dwLocationToValue(next)) },
                    onMessage = onMessage,
                )
            }
        }
    }
}

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
