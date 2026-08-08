package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "NaN" and "Infinity" typed into a money box.
 *
 * `String.toDoubleOrNull` reads both of them, and every numeric field in this app is an ordinary text
 * box on a decimal keypad — so a designer can type one, and a spreadsheet cell that divided by an
 * empty column pastes one. `rangeChecked` cannot catch either: every comparison against NaN is false,
 * so `nan < 0` passes a min-0 floor, and `inf` passes any floor there is.
 *
 * What it cost, before the server grew the same guard. MONEY stringifies, so `"%.2f"` of NaN stored
 * the literal "nan" behind a successful save — the designer is told "Stage saved" — and the report
 * printed "₹ nan" on the cover preview and in the .docx submitted to the ministry, while the cost
 * charts dropped the row silently (`cleanSeries` rejects non-finite) so the figure disagreed with the
 * table beside it with nothing to say why. DECIMAL stored the raw float, Postgres refused it, and the
 * whole stage save 500'd — which the stage editor reads as "no connection", so the designer is told
 * to wait for signal while a permanently un-writable value retries for ever.
 *
 * The server rejects these now, so the phone's failure mode without this guard is a save that is
 * refused after the fact and blamed on the network. Caught here, the message names the box.
 */
class NonFiniteValueTest {

    private val money = FieldDto(key = "materialCost", label = "Material cost", type = "MONEY", minValue = 0.0)
    private val decimal = FieldDto(key = "widthCm", label = "Width", type = "DECIMAL")
    private val percent = FieldDto(key = "shrinkage", label = "Shrinkage", type = "PERCENT", minValue = 0.0, maxValue = 100.0)
    private val whole = FieldDto(key = "participants", label = "Participants", type = "INT")

    private fun assertRefused(field: FieldDto, typed: String) {
        val coerced = DwValues.coerce(field, typed)
        assertNull("“$typed” must not be stored in ${field.label}", coerced.value)
        assertNotNull("“$typed” in ${field.label} must produce a message", coerced.error)
        // The message has to name the box. A stage of forty numeric fields that reports only "invalid
        // number" leaves the designer hunting for which one.
        assertTrue(
            "the message must name the field: ${coerced.error}",
            coerced.error!!.contains(field.label),
        )
    }

    @Test
    fun `NaN is refused by every numeric type`() {
        assertRefused(money, "NaN")
        assertRefused(decimal, "NaN")
        assertRefused(percent, "NaN")
        assertRefused(whole, "NaN")
    }

    @Test
    fun `infinity is refused however it is spelled`() {
        for (typed in listOf("Infinity", "-Infinity", "+Infinity")) {
            assertRefused(money, typed)
            assertRefused(decimal, typed)
            assertRefused(percent, typed)
        }
    }

    /**
     * A run of more than 308 digits, or an exponent past the double range, IS infinity once parsed.
     *
     * This is the arm a range check could never have covered, and the one a designer reaches by
     * holding a key down or pasting a spreadsheet's error cell.
     */
    @Test
    fun `a number too large to represent is refused`() {
        assertRefused(money, "1e400")
        assertRefused(decimal, "9".repeat(400))
    }

    /** The min-0 floor is exactly what NaN and infinity walk straight through. */
    @Test
    fun `a non-finite does not pass a bound it could never satisfy`() {
        assertRefused(money, "NaN")        // nan < 0 is false, so the floor waved it through
        assertRefused(percent, "Infinity") // inf > 100 was the only thing that could have caught it
    }

    // ── What must still be accepted ──────────────────────────────────────────────────────────────

    /**
     * The guard must not become a refusal of ordinary numbers. A costing sheet is entered in a
     * courtyard and a field that rejects a real amount is worse than one that accepts a bad one.
     */
    @Test
    fun `ordinary amounts are unaffected`() {
        assertEquals(JsonPrimitive("1250.10"), DwValues.coerce(money, "1250.10").value)
        assertEquals(JsonPrimitive("1250.10"), DwValues.coerce(money, "₹1,250.10").value)
        assertEquals(JsonPrimitive("0.00"), DwValues.coerce(money, "0").value)
        assertEquals(JsonPrimitive(12.5), DwValues.coerce(decimal, "12.5").value)
        assertEquals(JsonPrimitive(-3.25), DwValues.coerce(decimal, "-3.25").value)
        assertEquals(JsonPrimitive(100.0), DwValues.coerce(percent, "100").value)
        assertEquals(JsonPrimitive(1e300), DwValues.coerce(decimal, "1e300").value)
        assertNull(DwValues.coerce(money, "1250.10").error)
    }

    /** A blank is still a blank rather than an error — whether blank is allowed is `validate`'s question. */
    @Test
    fun `a blank box is still not an error`() {
        assertNull(DwValues.coerce(money, "").value)
        assertNull(DwValues.coerce(money, "").error)
    }

    /**
     * And the whole entry validates the same way, which is the path a save actually takes.
     *
     * `validate` re-coerces every filled text-shaped value, so a "nan" that reached the draft from an
     * older build is caught before the payload is built rather than by the server afterwards.
     */
    @Test
    fun `a stored non-finite is reported against its own field at validation`() {
        val entity = EntityDto(
            key = "costLine", cardinality = "COLLECTION", title = "Cost line",
            fields = listOf(money, decimal),
        )
        val errors = DwValues.validate(
            entity,
            mapOf("materialCost" to JsonPrimitive("NaN"), "widthCm" to JsonPrimitive(12.5)),
            enforceRequired = false,
        )
        assertEquals(setOf("materialCost"), errors.keys)
        assertTrue(errors.getValue("materialCost").contains("Material cost"))
    }
}
