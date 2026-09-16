package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CENTIMETRES ↔ INCHES, TO THE LAST HUNDREDTH, ON THE SAME STRINGS THE BROWSER IS DRIVEN WITH.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS IS A TABLE AND NOT A PROPERTY
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Four clients implement this conversion — two browsers and two handsets — and the number they write
 * is the number a ministry reads. A property test ("converting back gives roughly the same value")
 * would pass on every one of the divergences that actually matter, because each of them moves ONE
 * HUNDREDTH on ONE input:
 *
 *  · `Math.round` versus `floor(x + 0.5)` — identical on the JVM, different in Kotlin's stdlib,
 *    and they part company exactly on a halfway value. `1.25 in` is `317.5` hundredths of a
 *    centimetre EXACTLY in binary64, so it is decided entirely by the halfway rule.
 *  · `v * 2.54 * 100.0` rewritten as `v * 254.0`, or `v / 2.54 * 100.0` as `v * 0.3937007874015748`.
 *    Each is one refactor away and each moves the last ulp.
 *  · `Double.toString()` instead of assembling the digits: Kotlin prints "3.0" where JavaScript
 *    prints "3", so one client saves `3` and the other `3.0` for the same measurement, and every
 *    edit form then reads the record as changed.
 *
 * So the guard is a TABLE OF EXACT STRINGS, computed once against the browser's implementation and
 * copied here verbatim. `frontend/e2e/dimension-units-unit.spec.ts` drives the identical rows on the
 * other side. Two tests, one table, copied — that is the point of it. A row that disagrees means the
 * two clients have diverged, and this file says nothing about which one is right.
 */
class DimensionUnitsTest {

    /** `input` → `inches→cm`, `cm→inches`. Every row verified against the browser's arithmetic. */
    private val table = listOf(
        Triple("1", "2.54", "0.39"),
        // The same number spelled with a trailing zero. Both clients drop it, because the text is
        // assembled from the scaled integer and never formatted from a double.
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
        // ── THE HALFWAY ROWS, WHICH ARE THE WHOLE REASON THE ROUNDING IS SPELLED OUT ───────────
        // Each of these lands on an EXACT `.5` in hundredths, so the answer is decided purely by the
        // halfway rule and a change of rule shows up here and nowhere else.
        Triple("1.25", "3.18", "0.49"),     // (1.25 * 2.54) * 100 == 317.5 exactly → 318
        Triple("0.125", "0.32", "0.05"),    // == 31.75 exactly → 32
        Triple("1.875", "4.76", "0.74"),    // == 476.25 exactly → 476
        Triple("6.25", "15.88", "2.46"),    // == 1587.5 exactly → 1588
        // NOT a halfway value, and it looks like one: 0.05 * 2.54 * 100 is 12.699999999999999 in
        // binary64, so it rounds UP to 13 through the ordinary path. Kept because a "simplification"
        // to decimal arithmetic would answer 12 here and pass every other row.
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
        // WRITES NOTHING rather than writing garbage. The form's rule is that only an EMPTY box
        // clears its partner; everything here leaves the partner exactly as it stands, which is why
        // null has to come back rather than "" or 0.
        listOf(
            "",         // handled by the form's own clearing branch, never by a conversion
            " ",
            "-",
            "-1",       // no sign is in the grammar at all, so negatives are refused by parsing
            "1.2.3",
            "abc",
            "1e3",      // `Number("1e3")` is 1000 and `"1e3".toDouble()` is 1000 — refused on BOTH
            "0x1A",     // `Number` says 26, Kotlin throws. The grammar is what keeps them agreeing.
            "1.5f",     // Kotlin says 1.5, `Number` says NaN. Likewise.
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
        // THE DECISION THIS PINS: "1." is a number, not a mistake. Typing 1 → 1. → 1.5 gives the
        // partner 2.54 → 2.54 → 3.81, and it never blanks mid-keystroke. A grammar that refused the
        // trailing dot would make the partner flicker empty on every decimal anybody types, and an
        // empty partner means "no value" — a claim the designer has not made yet.
        assertEquals("2.54", cmTextFromInches("1"))
        assertEquals("2.54", cmTextFromInches("1."))
        assertEquals("3.81", cmTextFromInches("1.5"))
        // And the leading-dot form, which soft keyboards produce readily.
        assertEquals("1.27", cmTextFromInches(".5"))
        // One keystroke further and it is no longer a number: the partner is LEFT ALONE, holding the
        // value from the previous keystroke, rather than cleared.
        assertNull(cmTextFromInches("1.2."))
    }

    @Test
    fun `space and tab padding are accepted and nothing else is`() {
        assertEquals("2.54", cmTextFromInches(" 1 "))
        assertEquals("2.54", cmTextFromInches("\t1\t"))
        // A newline is not in the grammar. It cannot come from a `KeyboardType.Decimal` box, and
        // admitting it would be one more character the two languages have to agree about.
        assertNull(cmTextFromInches("\n1"))
    }

    @Test
    fun `a value too large for the column converts to nothing`() {
        // Both columns are `Decimal(10,2)`: 99,999,999.99 is the ceiling. A number that converts past
        // it would be refused by the server with a 422 — which on the handset means the WHOLE save
        // is lost, not merely the partner — so the partner is left empty and the designer keeps the
        // box they typed into.
        assertEquals("99999999.97", cmTextFromInches("39370078.73"))
        assertNull(cmTextFromInches("99999999"))
        assertNull(inchesTextFromCm("999999999"))
    }

    @Test
    fun `an unrepresentable magnitude is refused rather than parsed as infinity`() {
        // REACHABLE, despite the grammar admitting no exponent: four hundred digits is a perfectly
        // good match for it, and both `Double.parseDouble` and JavaScript's `Number` answer
        // Infinity. Without the finiteness check that infinity would be multiplied and compared.
        val huge = "1" + "0".repeat(400)
        assertNull(parseDimension(huge))
        assertNull(cmTextFromInches(huge))
        assertNull(inchesTextFromCm(huge))
    }

    @Test
    fun `the conversion is a real conversion and not a value copy`() {
        // The defining property, stated as a test so nobody "simplifies" it back into the copy the
        // two boxes used to be: 1 inch is 2.54 cm, and the inch is DEFINED as 25.4 mm, so this is
        // arithmetic on a definition rather than a measured ratio.
        assertEquals(2.54, CM_PER_INCH, 0.0)
        assertEquals("2.54", cmTextFromInches("1"))
        assertEquals("1", inchesTextFromCm("2.54"))
    }

    @Test
    fun `propagate clears on an empty box, writes on a number, and does nothing on anything else`() {
        var partner = "seed"
        // RULE 3 — clearing propagates. An empty box means "no value", and a stale converted number
        // left standing beside it is a lie. This is the ONE case where a non-number writes.
        propagateDimension("", ::cmTextFromInches) { partner = it }
        assertEquals("", partner)

        partner = "seed"
        propagateDimension("   ", ::cmTextFromInches) { partner = it }
        assertEquals("whitespace is an empty box", "", partner)

        partner = "seed"
        propagateDimension("1", ::cmTextFromInches) { partner = it }
        assertEquals("2.54", partner)

        // RULE 4 — anything that cannot be a number writes NOTHING. The partner holds what it held.
        partner = "2.54"
        propagateDimension("1.2.", ::cmTextFromInches) { partner = it }
        assertEquals("2.54", partner)

        partner = "2.54"
        propagateDimension("-", ::cmTextFromInches) { partner = it }
        assertEquals("2.54", partner)
    }

    @Test
    fun `a round trip through both directions does not walk the value`() {
        // WHY THE FORM IS ONE-DIRECTIONAL PER KEYSTROKE, demonstrated: 1 cm is 0.39 in is 0.99 cm.
        // The arithmetic is correct at every step and the value has still moved, which is why a
        // watcher over both boxes is forbidden and the partner is written from the source's own
        // handler and nowhere else. This test does not assert a bug is absent — it records the
        // number that makes the rule necessary.
        assertEquals("0.39", inchesTextFromCm("1"))
        assertEquals("0.99", cmTextFromInches("0.39"))
    }
}
