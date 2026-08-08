package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [placeSearchBias] — which map centres are worth biasing a search towards, and which are dropped.
 *
 * WHY THIS IS ANDROID-ONLY AND HAS NO PARITY CASE. On the web the proximity comes from
 * `map.getCenter()` on a MapLibre canvas that React owns, and `placeSearch.ts` sends whatever it is
 * given. Here it comes from `MapBridge.onCentre` — a `double` marshalled out of JavaScript across
 * `addJavascriptInterface`, from a Leaflet map inside a WebView that frequently finishes layout
 * AFTER its script runs. That is the documented blank-map symptom in `mapHtml`, and a map with a
 * zero-size container reports a centre computed from a degenerate viewport. `NaN` is what arrives.
 *
 * WHAT IT COSTS TO GET WRONG. MapTiler validates `proximity` and refuses the WHOLE request over it,
 * so an unusable bias does not degrade the ranking — it returns a 400, which
 * [describePlaceSearchFailure] then correctly but uselessly reports to the designer as "the place
 * search refused this request: this build's map key may be missing, expired or out of quota." A
 * designer would be sent to find an administrator to check a key that was never the problem, over a
 * parameter whose entire job is to reorder results that would have come back anyway.
 *
 * THE ASYMMETRY IS THE POINT. The pair is (longitude, latitude) — MapTiler's order, and the reverse
 * of every Android location API, which makes swapping the two the easiest mistake in this feature
 * and one that never throws. `a swapped pair is not silently accepted` below is the assertion that
 * catches it: 100 is a perfectly ordinary longitude and is not a latitude at all.
 */
class PlaceSearchBiasTest {

    /** Where a designer documenting the Bargarh cluster would actually have the map. */
    private val odisha = 83.6 to 21.3

    @Test
    fun `a real Indian map centre is sent unchanged`() {
        assertEquals(odisha, placeSearchBias(odisha))
        // The corners of the country, so the guard cannot be tightened into one that clips India.
        assertEquals(68.1 to 23.7, placeSearchBias(68.1 to 23.7)) // Kutch, the western edge
        assertEquals(97.4 to 28.2, placeSearchBias(97.4 to 28.2)) // Arunachal Pradesh, the eastern
        assertEquals(77.2 to 8.1, placeSearchBias(77.2 to 8.1))   // Kanyakumari, the southern
        assertEquals(74.9 to 37.0, placeSearchBias(74.9 to 37.0)) // the northern frontier
    }

    /** No map, no bias — and no reason to refuse the search over it. */
    @Test
    fun `no centre at all is not an error`() {
        assertNull(placeSearchBias(null))
    }

    /**
     * The WebView case this guard was written for.
     *
     * A Leaflet map that has not been laid out reports a centre off a degenerate viewport. Both
     * elements are checked independently because the two coordinates are computed separately and one
     * can arrive usable while the other does not.
     */
    @Test
    fun `a non-finite centre is dropped`() {
        assertNull(placeSearchBias(Double.NaN to 21.3))
        assertNull(placeSearchBias(83.6 to Double.NaN))
        assertNull(placeSearchBias(Double.NaN to Double.NaN))
        assertNull(placeSearchBias(Double.POSITIVE_INFINITY to 21.3))
        assertNull(placeSearchBias(83.6 to Double.NEGATIVE_INFINITY))
    }

    /**
     * THE AXIS ORDER, asserted by a pair that is valid in one order and impossible in the other.
     *
     * (100, 0) is a point in the Gulf of Thailand: a legitimate longitude and a legitimate latitude,
     * so it is kept — the guard's job is to reject the unusable, not to enforce India, which the
     * `country=in` parameter already does. (0, 100) is not a point anywhere, because there is no
     * hundredth degree of latitude, so it must be dropped. Widening the latitude range to ±180, or
     * swapping the two checks, makes the second line below fail and nothing else in this repository
     * complain.
     */
    @Test
    fun `a swapped pair is not silently accepted`() {
        assertEquals(100.0 to 0.0, placeSearchBias(100.0 to 0.0))
        assertNull(placeSearchBias(0.0 to 100.0))
    }

    /** Beyond the ends of the coordinate system in either element. */
    @Test
    fun `an out of range centre is dropped`() {
        assertNull(placeSearchBias(180.1 to 21.3))
        assertNull(placeSearchBias(-180.1 to 21.3))
        assertNull(placeSearchBias(83.6 to 90.1))
        assertNull(placeSearchBias(83.6 to -90.1))
        // The ends themselves are real places and stay: the poles and the antimeridian are points a
        // map can legitimately be centred on, and refusing them would be a guard inventing a rule.
        assertEquals(180.0 to 90.0, placeSearchBias(180.0 to 90.0))
        assertEquals(-180.0 to -90.0, placeSearchBias(-180.0 to -90.0))
    }

    /**
     * The whole reason the guard exists: what it prevents reaching the URL.
     *
     * Asserted through [placeSearchUrl] rather than stopping at the pair, because "the bias was
     * dropped" and "the request went out without a proximity parameter" are different claims and the
     * second is the one that matters. A `proximity=NaN%2C21.3` is what MapTiler 400s over.
     */
    @Test
    fun `a dropped bias leaves no proximity parameter in the URL`() {
        val url = placeSearchUrl("Barpali", "TESTKEY", placeSearchBias(Double.NaN to 21.3))
        assertEquals(
            "https://api.maptiler.com/geocoding/Barpali.json" +
                "?key=TESTKEY&country=in&language=en&limit=8&autocomplete=true",
            url
        )

        // And the same request WITH a usable centre still carries it, so the assertion above is
        // about the guard rather than about proximity having quietly stopped working altogether.
        val biased = placeSearchUrl("Barpali", "TESTKEY", placeSearchBias(odisha))
        assertEquals(
            "https://api.maptiler.com/geocoding/Barpali.json" +
                "?key=TESTKEY&country=in&language=en&limit=8&autocomplete=true&proximity=83.6%2C21.3",
            biased
        )
    }
}
