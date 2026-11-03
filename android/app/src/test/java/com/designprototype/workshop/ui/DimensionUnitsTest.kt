package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DimensionUnitsTest {

    private val table = listOf(
        Triple("1", "2.54", "0.39"),
        Triple("1.0", "2.54", "0.39"),
        Triple(".5", "1.27", "0.2"),
        Triple("0.5", "1.27", "0.2"),
        Triple("2", "5.08", "0.79"),
        Triple("3", "7.62", "1.18"),
        Triple("10", "25.4", "3.94"),
        Triple("100", "254", "39.37"),
        Triple("2.54", "6.45", "1"),
        Triple("12.34", "31.34", "4.86"),
        Triple("0.1", "0.25", "0.04"),
        Triple("1.25", "3.18", "0.49"),
        Triple("0.125", "0.32", "0.05"),
        Triple("1.875", "4.76", "0.74"),
        Triple("6.25", "15.88", "2.46"),
        Triple("0.05", "0.13", "0.02"),
        Triple("1.005", "2.55", "0.4"),
    )

    @Test
    fun `every row of the cross-surface table converts to the exact string the browser produces`() {
        table.forEach { (input, cm, inches) ->
            assertEquals("inches→cm for “$input”", cm, cmTextFromInches(input))
            assertEquals("cm→inches for “$input”", inches, inchesTextFromCm(input))
        }
    }

    @Test
    fun `a string that cannot be a number converts to nothing at all`() {
        listOf(
            "",
            " ",
            "-",
            "-1",
            "1.2.3",
            "abc",
            "1e3",
            "0x1A",
            "1.5f",
            "Infinity",
            "NaN",
            "1,5",
            "1 000",
        ).forEach { bad ->
            assertNull("“$bad” must convert to nothing", cmTextFromInches(bad))
            assertNull("“$bad” must convert to nothing", inchesTextFromCm(bad))
            assertNull("“$bad” is not a dimension", parseDimension(bad))
        }
    }

    @Test
    fun `a partially typed decimal keeps converting instead of blanking`() {
        assertEquals("2.54", cmTextFromInches("1"))
        assertEquals("2.54", cmTextFromInches("1."))
        assertEquals("3.81", cmTextFromInches("1.5"))
        assertEquals("1.27", cmTextFromInches(".5"))
        assertNull(cmTextFromInches("1.2."))
    }

    @Test
    fun `space and tab padding are accepted and nothing else is`() {
        assertEquals("2.54", cmTextFromInches(" 1 "))
        assertEquals("2.54", cmTextFromInches("\t1\t"))
        assertNull(cmTextFromInches("\n1"))
    }

    @Test
    fun `a value too large for the column converts to nothing`() {
        assertEquals("99999999.97", cmTextFromInches("39370078.73"))
        assertNull(cmTextFromInches("99999999"))
        assertNull(inchesTextFromCm("999999999"))
    }

    @Test
    fun `an unrepresentable magnitude is refused rather than parsed as infinity`() {
        val huge = "1" + "0".repeat(400)
        assertNull(parseDimension(huge))
        assertNull(cmTextFromInches(huge))
        assertNull(inchesTextFromCm(huge))
    }

    @Test
    fun `the conversion is a real conversion and not a value copy`() {
        assertEquals(2.54, CM_PER_INCH, 0.0)
        assertEquals("2.54", cmTextFromInches("1"))
        assertEquals("1", inchesTextFromCm("2.54"))
    }

    @Test
    fun `propagate clears on an empty box, writes on a number, and does nothing on anything else`() {
        var partner = "seed"
        propagateDimension("", ::cmTextFromInches) { partner = it }
        assertEquals("", partner)

        partner = "seed"
        propagateDimension("   ", ::cmTextFromInches) { partner = it }
        assertEquals("whitespace is an empty box", "", partner)

        partner = "seed"
        propagateDimension("1", ::cmTextFromInches) { partner = it }
        assertEquals("2.54", partner)

        partner = "2.54"
        propagateDimension("1.2.", ::cmTextFromInches) { partner = it }
        assertEquals("2.54", partner)

        partner = "2.54"
        propagateDimension("-", ::cmTextFromInches) { partner = it }
        assertEquals("2.54", partner)
    }

    @Test
    fun `a round trip through both directions does not walk the value`() {
        assertEquals("0.39", inchesTextFromCm("1"))
        assertEquals("0.99", cmTextFromInches("0.39"))
    }
}
