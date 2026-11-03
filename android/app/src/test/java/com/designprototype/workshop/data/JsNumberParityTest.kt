package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Test

class JsNumberParityTest {

    private val cases: List<Pair<Double, String>> = listOf(
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

        -0.0 to "0",

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

        1e-7 to "1e-7",
        -1e-7 to "-1e-7",
        1e-8 to "1e-8",
        1.5e-8 to "1.5e-8",

        1e7 to "10000000",
        1.23e8 to "123000000",
        123456789.123 to "123456789.123",
        1e20 to "100000000000000000000",

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

    @Test
    fun `no coordinate anywhere on Earth is written in exponential notation`() {
        val offenders = mutableListOf<String>()
        var degrees = -180.0
        while (degrees <= 180.0) {
            val written = jsNumber(degrees)
            if (written.contains('e') || written.contains('E')) offenders += "$degrees -> $written"
            degrees += 0.25
        }
        assertEquals("exponential coordinates: $offenders", 0, offenders.size)
    }
}
