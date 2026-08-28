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

/**
 * The claim a saved dimension makes about HOW it was measured, and the rule that withdraws it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THESE ARE TESTS AND NOT A CODE REVIEW
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Both directions of failure are invisible on the screen where they happen.
 *
 * A marker that is WRONGLY KEPT — left on a number somebody typed over after accepting a proposal —
 * renders identically to one that is right. The form shows the designer's own number, the save
 * succeeds, and the record quietly asserts that a photograph's geometry produced a figure a person
 * typed. Nobody finds out until somebody tries to re-derive it from marks that never existed.
 *
 * A marker that is WRONGLY SENT — naming a dimension this request carries no value for, or a column
 * outside the documented three — is worse than invisible: the server refuses the WHOLE save with a
 * 422, and Android's outbox will not queue a 4xx because the server saw it and said no. So the
 * record is not merely unmarked, it is GONE, and it was filled in somewhere with no signal.
 *
 * That is the whole reason this logic lives in a pure class with no Compose and no network in it.
 */
class DwMeasurementMarkersTest {

    private fun geometry() = dwGeometryMarker(DW_TECHNIQUE_SCALE)

    private fun methodOf(body: JsonObject?, column: String): String? =
        (body?.get(column) as? JsonObject)?.get("method")?.jsonPrimitive?.content

    /* ── The claim stands while the number is still the one that was proposed ─────────────────── */

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

    /* ── …and is withdrawn the moment it stops being true ─────────────────────────────────────── */

    @Test
    fun `typing over an accepted reading drops its marker`() {
        val markers = DwMeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())

        // The designer looked at the object again and typed their own figure.
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

        // Not a rounding detail: the panel rounds to the precision its error bar reaches, so the
        // number of digits is itself part of what was measured.
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

    /* ── One field's edit must not take another field's marker with it ────────────────────────── */

    @Test
    fun `editing one dimension leaves the others marked`() {
        val markers = DwMeasurementMarkers()
        markers.accept("lengthInches", "12.4", geometry())
        markers.accept("breadthInches", "6.1", geometry())

        val body = markers.body(mapOf("lengthInches" to "99", "breadthInches" to "6.1"))

        assertNull("the one that was typed over", methodOf(body, "lengthInches"))
        assertEquals("the one that was not", "PHOTO_GEOMETRY", methodOf(body, "breadthInches"))
    }

    /* ── The two shapes the server accepts, and the one it refuses by name ────────────────────── */

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
        // The server refuses a technique outside GEOMETRY_TECHNIQUES, and a refusal here costs the
        // whole record. The method is worth recording without it.
        val marker = dwGeometryMarker("TRIANGULATED")

        assertEquals("PHOTO_GEOMETRY", marker["method"]?.jsonPrimitive?.content)
        assertNull(marker["technique"])
    }

    @Test
    fun `a column outside the documented three can never be marked`() {
        val markers = DwMeasurementMarkers()
        // The tool form's unit-less `height`, and its four other typed boxes. The server answers a
        // marker naming one of these with a 422 that names it, so this must not reach the wire.
        listOf("height", "width", "thickness", "weight", "radius", "costOfMaking").forEach {
            markers.accept(it, "5", geometry())
        }

        assertNull(markers.body(mapOf("height" to "5", "width" to "5", "costOfMaking" to "5")))
    }

    @Test
    fun `every markable column is one the server allows`() {
        // Pins this file's set against the three names `DIMENSION_FIELDS` holds. A fourth added here
        // without the server agreeing is a 422 on every save that uses it.
        assertEquals(setOf("lengthInches", "breadthInches", "heightInches"), DW_MEASUREMENT_DIMENSIONS)
    }

    @Test
    fun `a form nobody accepted anything on sends no key at all`() {
        // The ordinary case, and the one that must stay byte-for-byte identical to what this app sent
        // before the key existed: null, so `explicitNulls = false` drops it from the body entirely.
        assertNull(DwMeasurementMarkers().body(mapOf("lengthInches" to "12", "breadthInches" to "6")))
    }

    /* ── The round trip that actually reaches the server ──────────────────────────────────────── */

    @Test
    fun `the marker survives the outbox`() {
        // THE POINT OF THIS TEST. A record saved in a courtyard is serialised into the outbox and
        // decoded back out a fortnight later, and if the marker did not survive that trip an offline
        // record would lose its provenance while an online one kept it — the one asymmetry nobody
        // would notice, because both saves succeed.
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
        // A handset may be a fortnight behind, and its outbox older still. An entry written without
        // the key must decode to "no marker" rather than failing the replay.
        val outbox = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = """{"craftName":"Pattachitra","place":"Raghurajpur","artisanName":"A. Maharana",
            |"productName":"Palm leaf box","lengthInches":12.4}""".trimMargin()

        val restored = outbox.decodeFromString(ProductCreateRequest.serializer(), legacy)

        assertNull(restored.measurementMethods)
        assertTrue(restored.lengthInches == 12.4)
    }
}
