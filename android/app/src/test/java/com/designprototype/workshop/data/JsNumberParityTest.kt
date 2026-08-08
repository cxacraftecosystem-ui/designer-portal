package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [jsNumber] against JavaScript's `Number.prototype.toString`, PINNED BY VALUE.
 *
 * WHY THIS FILE EXISTS SEPARATELY FROM [PlaceSearchParityTest]. That test diffs whole URLs, and
 * every proximity in its table is a tidy Indian coordinate — `85,21`. Tidy coordinates are exactly
 * the inputs on which a wrong number formatter is right, so the 22-case table could not see this and
 * did not: it passed 8/8 against the implementation this file was written to correct.
 *
 * WHAT WAS WRONG. `placeSearch.ts` writes the proximity parameter as `${lon},${lat}`, which is
 * JavaScript's own number-to-string rule, and [jsNumber] is the port of that rule. The first version
 * stripped Java's `.0` and lower-cased its `E`, and its comment claimed the only value it could not
 * reproduce was a magnitude at or above 1e21. That claim was false at BOTH ends, because the two
 * languages switch to exponential notation at different thresholds:
 *
 *   * Java's `Double.toString` goes exponential below 1e-3 and at or above 1e7.
 *   * JavaScript goes exponential only below 1e-6 and at or above 1e21 (ECMA-262 Number::toString,
 *     which prints fixed notation while −6 < n ≤ 21, n being the position of the decimal point).
 *
 * So 0.0001 is `"0.0001"` in JavaScript and was `"1e-4"` here; 1e20 is twenty-one digits there and
 * was `"1e20"` here; `-0.0` is `"0"` there and was `"-0"` here; and even the acknowledged 1e21 case
 * was wrong in its spelling, because JavaScript writes the positive exponent with a sign — `"1e+21"`.
 * Measured on this machine's JDK 17.0.2 against node, fourteen of the values below disagreed.
 *
 * HOW MUCH IT MATTERED, stated honestly rather than inflated: almost not at all in the field. The
 * search is `country=in`, so every coordinate that comes BACK is Indian and far outside the
 * divergent band, and the only value that goes OUT through this function is the map-centre bias,
 * which would have to be panned to within about a hundred metres of the equator or the prime
 * meridian to reach it. This is fixed because the file's whole purpose is byte-parity with the web
 * and it carried a comment asserting a boundary that was not the real one — a confident wrong
 * number is the thing this repository keeps having to dig out of ports.
 *
 * HOW THE TABLE WAS MADE, so it can be remade: every expectation below is the literal output of
 * `String(v)` in node for the same literal, not a value reasoned out from the specification. The
 * generator was one line — `node -e "for (const v of [...]) console.log(String(v))"` — and the
 * India-shaped entries are real coordinates lifted from the live MapTiler responses for Barpali and
 * Bargarh so the ordinary case is pinned alongside the awkward one.
 */
class JsNumberParityTest {

    /**
     * `expected` is what node printed. Kotlin and JavaScript both parse a decimal literal to the
     * nearest double, so the two sides start from identical bits and any difference here is the
     * formatter's.
     */
    private val cases: List<Pair<Double, String>> = listOf(
        // Ordinary map centres and real MapTiler coordinates — the case that already worked, kept so
        // a fix to the awkward end cannot quietly break the end that carries every real search.
        0.0 to "0",
        1.0 to "1",
        -1.0 to "-1",
        85.0 to "85",
        21.0 to "21",
        79.0 to "79",
        22.0 to "22",
        180.0 to "180",
        -180.0 to "-180",
        90.0 to "90",
        -90.0 to "-90",
        0.5 to "0.5",
        0.1 to "0.1",
        -73.5 to "-73.5",
        82.74589028209448 to "82.74589028209448",
        20.89405824525621 to "20.89405824525621",
        83.91022648662329 to "83.91022648662329",
        21.74283862190238 to "21.74283862190238",
        82.63642966747284 to "82.63642966747284",
        20.722963153925253 to "20.722963153925253",
        83.58720168471336 to "83.58720168471336",
        21.190080997194745 to "21.190080997194745",

        // NEGATIVE ZERO. Leaflet hands back whatever the projection produced, and a map dragged onto
        // the prime meridian can produce -0.0. Java spells it "-0.0"; JavaScript spells it "0".
        -0.0 to "0",

        // THE LOWER BAND — Java exponential, JavaScript fixed. This is the whole divergence.
        0.001 to "0.001",
        -0.001 to "-0.001",
        0.0009999 to "0.0009999",
        0.0001 to "0.0001",
        -0.0001 to "-0.0001",
        0.00001 to "0.00001",
        0.000001 to "0.000001",
        -0.000001 to "-0.000001",
        0.000015 to "0.000015",
        0.00025 to "0.00025",

        // BELOW THE BAND both agree on exponential — and the sign of a negative exponent is written
        // bare in both, so these must NOT be "fixed" by an over-eager change.
        1e-7 to "1e-7",
        -1e-7 to "-1e-7",
        1e-8 to "1e-8",
        1.5e-8 to "1.5e-8",

        // THE UPPER BAND. Unreachable for a latitude or a longitude, and included anyway because the
        // rule is one rule: getting it right here is what makes the comment on [jsNumber] true.
        1e7 to "10000000",
        1.23e8 to "123000000",
        123456789.123 to "123456789.123",
        1e20 to "100000000000000000000",

        // AT AND ABOVE 1e21 JavaScript goes exponential AND writes the exponent's plus sign.
        1e21 to "1e+21",
        -1e21 to "-1e+21"
    )

    @Test
    fun `every number is spelt exactly as JavaScript spells it`() {
        val mismatches = mutableListOf<String>()
        for ((value, expected) in cases) {
            val actual = jsNumber(value)
            if (actual != expected) mismatches += "  js: $expected\n  kot: $actual"
        }
        assertEquals(
            "jsNumber parity diff (${mismatches.size} of ${cases.size}):\n" + mismatches.joinToString("\n"),
            0,
            mismatches.size
        )
    }

    /**
     * The formatter is only ever asked for coordinates, and a coordinate must never come back in a
     * notation that reopens the question this port exists to close.
     *
     * Everything inside the box a [PlaceHit] or a map centre can occupy is plain fixed notation, so
     * a `proximity` parameter and a Leaflet `setView` argument are always ordinary decimal numbers.
     * Asserted as a range rather than case by case because it is a claim about the whole domain.
     */
    @Test
    fun `no coordinate anywhere on Earth is written in exponential notation`() {
        val offenders = mutableListOf<String>()
        var degrees = -180.0
        while (degrees <= 180.0) {
            val written = jsNumber(degrees)
            if (written.contains('e') || written.contains('E')) offenders += "$degrees -> $written"
            // A quarter degree is about 28 km; stepping it walks both hemispheres, both signs and the
            // meridian itself without pretending to be exhaustive.
            degrees += 0.25
        }
        assertEquals("exponential coordinates: $offenders", 0, offenders.size)
    }
}
