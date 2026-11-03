package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

val DW_MEASUREMENT_DIMENSIONS: Set<String> = setOf("lengthInches", "breadthInches", "heightInches")

const val DW_TECHNIQUE_SCALE = "SCALE"

const val DW_TECHNIQUE_RECTIFIED = "RECTIFIED"

private const val DW_METHOD_PHOTO_GEOMETRY = "PHOTO_GEOMETRY"
private const val DW_METHOD_VISION_MODEL = "VISION_MODEL"

fun dwGeometryMarker(technique: String?): JsonObject = buildJsonObject {
    put("method", JsonPrimitive(DW_METHOD_PHOTO_GEOMETRY))
    if (technique == DW_TECHNIQUE_SCALE || technique == DW_TECHNIQUE_RECTIFIED) {
        put("technique", JsonPrimitive(technique))
    }
}

fun dwVisionMarker(fromServer: JsonObject?): JsonObject =
    fromServer ?: buildJsonObject { put("method", JsonPrimitive(DW_METHOD_VISION_MODEL)) }

data class DwMeasurementReading(
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val valueInches: Double? = null,
    val marker: JsonObject? = null,
)

private data class DwAcceptedMeasurement(val acceptedText: String, val marker: JsonObject)

class DwMeasurementMarkers {
    private val accepted = mutableMapOf<String, DwAcceptedMeasurement>()

    fun accept(column: String, text: String, marker: JsonObject) {
        if (column !in DW_MEASUREMENT_DIMENSIONS) return
        if (text.isBlank()) return
        accepted[column] = DwAcceptedMeasurement(text, marker)
    }

    fun body(current: Map<String, String>): JsonObject? {
        val live = accepted.filter { (column, entry) ->
            val text = current[column]?.trim().orEmpty()
            text.isNotEmpty() && text == entry.acceptedText.trim()
        }
        if (live.isEmpty()) return null
        return buildJsonObject { live.forEach { (column, entry) -> put(column, entry.marker) } }
    }

    fun toDraft(): String {
        if (accepted.isEmpty()) return ""
        return buildJsonObject {
            accepted.forEach { (column, entry) ->
                put(column, buildJsonObject {
                    put("text", JsonPrimitive(entry.acceptedText))
                    put("marker", entry.marker)
                })
            }
        }.toString()
    }

    fun restoreDraft(text: String) {
        if (text.isBlank()) return
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }
            .getOrNull() ?: return
        root.forEach { (column, value) ->
            val entry = value as? JsonObject ?: return@forEach
            val accepted = (entry["text"] as? JsonPrimitive)?.content ?: return@forEach
            val marker = entry["marker"] as? JsonObject ?: return@forEach
            accept(column, accepted, marker)
        }
    }
}
