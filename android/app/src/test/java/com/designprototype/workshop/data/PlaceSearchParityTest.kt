package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaceSearch] against `frontend/lib/placeSearch.ts`, PINNED BY VALUE.
 *
 * WHY BY VALUE AND NOT BY PROPERTY. This repository has already been bitten twice by a port that was
 * right about the rule and wrong about the answer — Python rounding half to EVEN where naive
 * formatting rounds half away (62% against 63%), and a tokeniser matching `\p{L}` where the original
 * matched `[\p{L}\p{M}]` so every Odia word shattered. A test that asserted "the context is the part
 * after the name" would have passed against all of those. So the two implementations are run over
 * ONE shared table and the outputs are diffed.
 *
 * HOW THE TABLE WAS MADE, so it can be remade. `scripts` were run out of the scratchpad, not checked
 * in, and the steps were: capture six real MapTiler bodies with this build's own key
 * (`api.maptiler.com/geocoding/{Barpali,Bargarh,Bagru,Kutch,Bhuj,Srinagar}.json?...&country=in`);
 * add the hand-written edge cases below; load `frontend/lib/placeSearch.ts` VERBATIM through jiti
 * with `@/lib/api` and `@/lib/offline` aliased to stubs of `ApiError`, `isTransient` and
 * `isUnreachable` copied from `frontend/lib/offline.ts:237-260`; stub `global.fetch` to return each
 * case and record the URL it was asked for; write inputs and answers into
 * `app/src/test/resources/placeSearchParity.json`. The TypeScript is the reference implementation
 * and this file is the diff. 22 parse cases and 10 failure cases.
 *
 * WHAT THE TABLE DELIBERATELY COVERS, because each one is a way a port of this module can be wrong
 * while looking right:
 *
 *   * Every branch of the name/context split, including a `place_name` that does NOT begin with
 *     `text,` (kept whole) and one that begins with `text` but without the comma (also kept whole).
 *   * Features with no usable centre — absent, null, a string, a one-element array — which must be
 *     DROPPED, and the fallback id of a later feature, which must be its index AFTER the drop.
 *   * MapTiler's degenerate bbox (`w == e`, `s == n`) for a village, which the live API returns for
 *     every `place.*` feature and which is what separates a frame from a point.
 *   * URL escaping of the path segment. `java.net.URLEncoder` would write "Kollam / Kerala" as
 *     "Kollam+%2F+Kerala" and search for a plus sign; Odia and Devanagari names must survive as
 *     percent-encoded UTF-8.
 *   * The three failure buckets across ten statuses. Getting 500 into the "no connection" bucket
 *     would send a designer out of the building to look for signal that was never the problem.
 */
class PlaceSearchParityTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val table: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("placeSearchParity.json")
            ?: error("placeSearchParity.json is missing from app/src/test/resources")
        json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun `the minimum query length matches the web`() {
        assertEquals(table["minQueryLength"]!!.jsonPrimitive.int, PLACE_SEARCH_MIN_QUERY_LENGTH)
    }

    /**
     * The URL, character for character.
     *
     * Whole-string equality rather than "contains country=in", because the failure this catches is
     * an encoder difference inside a value, which every looser assertion passes.
     */
    @Test
    fun `every request URL is byte-identical to the web's`() {
        val key = table.str("key")!!
        val mismatches = mutableListOf<String>()
        for (element in table["parse"]!!.jsonArray) {
            val case = element.jsonObject
            val proximity = (case["proximity"] as? JsonObject)?.let {
                it["lon"]!!.jsonPrimitive.double to it["lat"]!!.jsonPrimitive.double
            }
            val actual = placeSearchUrl(case.str("query")!!, key, proximity)
            val expected = case.str("expectedUrl")!!
            if (actual != expected) mismatches += "${case.str("name")}\n  web: $expected\n  kot: $actual"
        }
        assertEquals("URL parity diff:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    /**
     * The hits, field by field, including which features were dropped and in what order.
     *
     * Doubles are compared with `assertEquals(expected, actual, 0.0)` — an EXACT match. Both sides
     * parsed the same shortest-round-trip decimal text, so anything other than bit equality here is
     * a real difference and not a floating-point artefact; a tolerance would hide it.
     */
    @Test
    fun `every parsed result matches the web's, hit for hit`() {
        val mismatches = mutableListOf<String>()
        for (element in table["parse"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case.str("name")!!
            val status = case["status"]!!.jsonPrimitive.int
            val notJson = case["notJson"]!!.jsonPrimitive.boolean
            val body = case.str("body")!!

            if (status != 200) {
                // The status is decided before the body is looked at, so a 403 carrying MapTiler's
                // plain-text "Invalid key" is a refusal and never an unreadable-answer error.
                val expected = case["expectedError"]!!.jsonObject
                val failure = placeSearchStatusFailure(status)
                if (failure.status != expected["status"]!!.jsonPrimitive.int) {
                    mismatches += "$name: status ${failure.status} vs ${expected["status"]}"
                }
                if (failure.message != expected.str("message")) {
                    mismatches += "$name: message '${failure.message}' vs '${expected.str("message")}'"
                }
                continue
            }

            if (notJson) {
                val thrown = runCatching { parsePlaceSearchResponse(body) }.exceptionOrNull()
                val expected = case["expectedError"]!!.jsonObject
                val failure = thrown as? PlaceSearchException
                if (failure == null) {
                    mismatches += "$name: expected a PlaceSearchException, got $thrown"
                } else {
                    if (failure.status != expected["status"]!!.jsonPrimitive.int) {
                        mismatches += "$name: status ${failure.status} vs ${expected["status"]}"
                    }
                    if (failure.message != expected.str("message")) {
                        mismatches += "$name: message '${failure.message}' vs '${expected.str("message")}'"
                    }
                }
                continue
            }

            val actual = parsePlaceSearchResponse(body)
            val expected = case["expectedHits"]!!.jsonArray
            if (actual.size != expected.size) {
                mismatches += "$name: ${actual.size} hits vs ${expected.size}"
                continue
            }
            expected.forEachIndexed { index, expectedElement ->
                val want = expectedElement.jsonObject
                val got = actual[index]
                val where = "$name[$index]"
                if (got.id != want.str("id")) mismatches += "$where id '${got.id}' vs '${want.str("id")}'"
                if (got.name != want.str("name")) mismatches += "$where name '${got.name}' vs '${want.str("name")}'"
                if (got.context != want.str("context")) {
                    mismatches += "$where context '${got.context}' vs '${want.str("context")}'"
                }
                if (got.lon != want["lon"]!!.jsonPrimitive.double) {
                    mismatches += "$where lon ${got.lon} vs ${want["lon"]}"
                }
                if (got.lat != want["lat"]!!.jsonPrimitive.double) {
                    mismatches += "$where lat ${got.lat} vs ${want["lat"]}"
                }
                val wantBox = want["bbox"] as? JsonArray
                val gotBox = got.bounds
                when {
                    wantBox == null && gotBox != null -> mismatches += "$where bbox $gotBox vs none"
                    wantBox != null && gotBox == null -> mismatches += "$where bbox none vs $wantBox"
                    wantBox != null && gotBox != null -> {
                        val want4 = wantBox.map { it.jsonPrimitive.double }
                        val got4 = listOf(gotBox.west, gotBox.south, gotBox.east, gotBox.north)
                        if (want4 != got4) mismatches += "$where bbox $got4 vs $want4"
                    }
                }
            }
        }
        assertEquals("Hit parity diff:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    /** The three sentences, over every status the table carries. */
    @Test
    fun `every failure message matches the web's`() {
        val mismatches = mutableListOf<String>()
        for (element in table["failure"]!!.jsonArray) {
            val case = element.jsonObject
            val status = (case["status"] as? JsonPrimitive)?.takeIf { it.content != "null" }?.int
            val actual = describePlaceSearchFailure(PlaceSearchException(status, "x"))
            val expected = case.str("expectedMessage")!!
            if (actual != expected) mismatches += "${case.str("name")}\n  web: $expected\n  kot: $actual"
        }
        assertEquals("Failure parity diff:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    /**
     * A failure that is not a [PlaceSearchException] at all — a dead socket, DNS with no answer —
     * lands in the offline bucket, which is what the web's `isUnreachable` returns for anything that
     * is not an `ApiError`.
     */
    @Test
    fun `an unwrapped IO failure reads as no connection`() {
        val expected = table["failure"]!!.jsonArray
            .map { it.jsonObject }
            .first { it.str("name") == "no-response" }
            .str("expectedMessage")!!
        assertEquals(expected, describePlaceSearchFailure(java.io.IOException("socket closed")))
        assertEquals(expected, describePlaceSearchFailure(IllegalStateException("something else entirely")))
    }

    // -----------------------------------------------------------------------------------------
    // The half the web has no counterpart for: the camera, and the Leaflet call that performs it
    // -----------------------------------------------------------------------------------------

    /**
     * A real extent is framed; MapTiler's degenerate village bbox is NOT.
     *
     * The degenerate case is taken from the live response for Barpali, where `bbox` is the centre
     * repeated. Treating it as an extent asks Leaflet to fit a zero-size rectangle, which is a
     * request for its maximum zoom — a roof, with nothing on screen to say which village it is.
     */
    @Test
    fun `an area is framed and a village point is not`() {
        val area = PlaceHit(
            id = "subregion.977",
            name = "Bargarh",
            context = "Odisha, India",
            lon = 83.61904889345169,
            lat = 21.333500594158206,
            bounds = PlaceBounds(82.63642966747284, 20.722963153925253, 83.91022648662329, 21.74283862190238)
        )
        assertEquals(
            PlaceCamera.Frame(
                south = 20.722963153925253,
                west = 82.63642966747284,
                north = 21.74283862190238,
                east = 83.91022648662329
            ),
            cameraForPlace(area)
        )

        val village = PlaceHit(
            id = "place.2592481",
            name = "Barpali",
            context = "Paikmal, Odisha, India",
            lon = 82.74589028209448,
            lat = 20.89405824525621,
            bounds = PlaceBounds(82.74589028209448, 20.89405824525621, 82.74589028209448, 20.89405824525621)
        )
        assertEquals(
            PlaceCamera.Point(lat = 20.89405824525621, lon = 82.74589028209448, zoom = PLACE_POINT_ZOOM),
            cameraForPlace(village)
        )

        val noBox = village.copy(bounds = null)
        assertEquals(cameraForPlace(village), cameraForPlace(noBox))
    }

    /**
     * THE AXIS ORDER, which is the one thing in this feature that fails silently.
     *
     * MapTiler says `[west, south, east, north]`; Leaflet wants `[[south, west], [north, east]]`.
     * The same four numbers in the wrong order do not throw — they fly the map to a rectangle in the
     * Indian Ocean. This asserts the exact string, so a reordering is a failing test rather than a
     * confused designer.
     */
    @Test
    fun `the Leaflet script puts latitude first and never touches the marker`() {
        val frame = leafletCameraScript(
            PlaceCamera.Frame(south = 20.722963153925253, west = 82.63642966747284, north = 21.74283862190238, east = 83.91022648662329)
        )
        assertEquals(
            "if(window.__map){window.__map.fitBounds(" +
                "[[20.722963153925253,82.63642966747284],[21.74283862190238,83.91022648662329]]," +
                "{maxZoom:14,padding:[24,24]});}",
            frame
        )

        val point = leafletCameraScript(PlaceCamera.Point(lat = 20.89405824525621, lon = 82.74589028209448, zoom = 13))
        assertEquals(
            "if(window.__map){window.__map.setView([20.89405824525621,82.74589028209448],13);}",
            point
        )

        // THE LOAD-BEARING RULE, asserted rather than trusted: no camera script may move the marker
        // or report a coordinate back through the bridge. Either would put a geocoder's guess into a
        // research record with nobody in the loop.
        for (script in listOf(frame, point)) {
            assertTrue(script, !script.contains("setLatLng"))
            assertTrue(script, !script.contains("AndroidMapBridge"))
            assertTrue(script, !script.contains("onPick"))
        }
    }

    /**
     * A build with no MapTiler key must degrade, not crash.
     *
     * The picker's tiles come from OpenStreetMap and need no key, so a keyless build still has a
     * working map — which is why the UI hides only the search box. This is the belt to that braces:
     * a caller that forgot the check gets a refusal it can word, not a request to `key=`.
     */
    @Test
    fun `a blank key is unavailable and refuses loudly`() {
        assertTrue(placeSearchAvailable("OJJYFRqCD2HD2k3BbXGF"))
        assertTrue(!placeSearchAvailable(""))
        assertTrue(!placeSearchAvailable("   "))

        val thrown = runCatching {
            kotlinx.coroutines.runBlocking { searchPlaces("Barpali", "", null) }
        }.exceptionOrNull()
        assertTrue("expected a PlaceSearchException, got $thrown", thrown is PlaceSearchException)
        assertEquals(503, (thrown as PlaceSearchException).status)
    }
}
