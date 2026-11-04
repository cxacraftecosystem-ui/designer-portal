package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwMeasurementMarkersTest {

    private fun geometry() = dwGeometryMarker(DW_TECHNIQUE_SCALE)

    private fun methodOf(body: JsonObject?, column: String): String? =
        (body?.get(column) as? JsonObject)?.get("method")?.jsonPrimitive?.content

    @Test
    fun `an untouched acceptance is marked`() {
        val markers = DwMeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        val body = markers.body(mapOf("lengthInches" to "12.4"))

        assertEquals("PHOTO_GEOMETRY", methodOf(body, "lengthInches"))
        assertEquals(
            "the technique rides along so a later reader can repeat the geometry",
            "SCALE",
            (body?.get("lengthInches") as JsonObject)["technique"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `typing over an accepted reading drops its marker`() {
        val markers = DwMeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        val body = markers.body(mapOf("lengthInches" to "13"))

        assertNull(
            "a PHOTO_GEOMETRY marker on a hand-typed number is a false claim, and UNRECORDED is honest",
            body,
        )
    }

    @Test
    fun `clearing an accepted reading drops its marker`() {
        val markers = DwMeasurementMarkers()
        markers.accept("heightInches", "8", geometry())

        assertNull("an empty box carries no number and must carry no method", markers.body(mapOf("heightInches" to "")))
        assertNull("and the same for a box that is not in the request at all", markers.body(emptyMap()))
    }

    @Test
    fun `a changed digit drops the marker even when the number is nearly the same`() {
        val markers = DwMeasurementMarkers()
        markers.accept("breadthInches", "12.0", geometry())

        assertNull(markers.body(mapOf("breadthInches" to "12.00")))
    }

    @Test
    fun `whitespace alone does not drop a marker`() {
        val markers = DwMeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        assertNotNull(
            "re-entering the box and leaving a space did not change the number",
            markers.body(mapOf("lengthInches" to " 12.4 ")),
        )
    }

    @Test
    fun `editing one dimension leaves the others marked`() {
        val markers = DwMeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())
        markers.accept("breadthInches", "6.1", geometry())

        val body = markers.body(mapOf("lengthInches" to "99", "breadthInches" to "6.1"))

        assertNull("the one that was typed over", methodOf(body, "lengthInches"))
        assertEquals("the one that was not", "PHOTO_GEOMETRY", methodOf(body, "breadthInches"))
    }

    @Test
    fun `a vision reading echoes the server's own marker verbatim`() {
        val fromServer = buildJsonObject {
            put("method", JsonPrimitive("VISION_MODEL"))
            put("provider", JsonPrimitive("gemini"))
            put("modelId", JsonPrimitive("gemini-2.5-flash-lite"))
            put("selfReportedConfidence", JsonPrimitive(0.8))
        }
        val markers = DwMeasurementMarkers()
        markers.accept("lengthInches", "12", dwVisionMarker(fromServer))

        val marker = markers.body(mapOf("lengthInches" to "12"))?.get("lengthInches") as JsonObject

        assertEquals(
            "rebuilding it here would lose the confidence, which is the only number on the stamp",
            fromServer,
            marker,
        )
    }

    @Test
    fun `an older server that sent no marker still records that a model produced the number`() {
        val marker = dwVisionMarker(null)

        assertEquals("VISION_MODEL", marker["method"]?.jsonPrimitive?.content)
        assertNull("nothing is invented to fill the gap", marker["provider"])
    }

    @Test
    fun `an unknown technique is dropped rather than sent`() {
        val marker = dwGeometryMarker("TRIANGULATED")

        assertEquals("PHOTO_GEOMETRY", marker["method"]?.jsonPrimitive?.content)
        assertNull(marker["technique"])
    }

    @Test
    fun `a column outside the documented three can never be marked`() {
        val markers = DwMeasurementMarkers()
        listOf("height", "width", "thickness", "weight", "radius", "costOfMaking").forEach {
            markers.accept(it, "5", geometry())
        }

        assertNull(markers.body(mapOf("height" to "5", "width" to "5", "costOfMaking" to "5")))
    }

    @Test
    fun `every markable column is one the server allows`() {
        assertEquals(setOf("lengthInches", "breadthInches", "heightInches"), DW_MEASUREMENT_DIMENSIONS)
    }

    @Test
    fun `a form nobody accepted anything on sends no key at all`() {
        assertNull(DwMeasurementMarkers().body(mapOf("lengthInches" to "12", "breadthInches" to "6")))
    }

    @Test
    fun `the marker survives the outbox`() {
        val outbox = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val markers = DwMeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        val body = ProductCreateRequest(
            craftName = "Pattachitra",
            place = "Raghurajpur",
            artisanName = "A. Maharana",
            productName = "Palm leaf box",
            lengthInches = 12.4,
            measurementMethods = markers.body(mapOf("lengthInches" to "12.4")),
        )

        val wire = outbox.encodeToString(ProductCreateRequest.serializer(), body)
        val restored = outbox.decodeFromString(ProductCreateRequest.serializer(), wire)

        assertEquals(body.measurementMethods, restored.measurementMethods)
        assertEquals("PHOTO_GEOMETRY", methodOf(restored.measurementMethods, "lengthInches"))
    }

    @Test
    fun `an entry queued before this key existed still replays`() {
        val outbox = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = """{"craftName":"Pattachitra","place":"Raghurajpur","artisanName":"A. Maharana",
            |"productName":"Palm leaf box","lengthInches":12.4}""".trimMargin()

        val restored = outbox.decodeFromString(ProductCreateRequest.serializer(), legacy)

        assertNull(restored.measurementMethods)
        assertTrue(restored.lengthInches == 12.4)
    }
}
