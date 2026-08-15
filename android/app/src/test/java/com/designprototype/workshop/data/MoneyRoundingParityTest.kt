package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A typed amount must store the same two decimals on the phone as it does on the server.
 *
 * `DwValues.coerce`'s MONEY arm used to format with `String.format(Locale.ROOT, "%.2f", parsed)`.
 * Java's `Formatter` rounds the SHORTEST decimal representation of the double half AWAY from zero;
 * `coerce_value` on the server does `f"{checked:.2f}"`, which rounds the EXACT binary value half to
 * EVEN. The web's MONEY input sends the raw typed string, so the browser gets the server's answer —
 * and the phone got a different one from the same keystrokes. Nothing corrected it afterwards: the
 * save accepts an already-formatted string verbatim.
 *
 * Every expectation below is the literal output of this repository's own interpreter — measured, not
 * reasoned about:
 *
 *     $ backend/.venv/Scripts/python.exe -c "print(f'{float(\"2.675\"):.2f}')"   → 2.67
 *
 * for each of the values named in `expected`. If a future edit reaches for `String.format` again
 * because it is shorter, this file fails on the first row.
 *
 * The same rule is pinned for the DERIVED path by `DerivedFieldTest`; `DwDerived.formatted` has used
 * `BigDecimal(...).setScale(2, HALF_EVEN)` since it was written, and the two paths — a figure the
 * designer typed and a figure the phone computed — now agree with each other as well as with Python.
 */
class MoneyRoundingParityTest {

    private val money = FieldDto(key = "unitRate", label = "Rate per unit", type = "MONEY")

    private fun stored(typed: String): String? =
        (DwValues.coerce(money, typed).value as? JsonPrimitive)?.content

    /**
     * The five amounts where `%.2f` and Python disagreed, and they are not exotic: a per-metre rate
     * of ₹2.675 is an ordinary thing to type into a cost sheet.
     */
    @Test
    fun `three-decimal amounts round the way the server rounds them`() {
        val expected = mapOf(
            // typed      Python f"{x:.2f}"     what "%.2f" used to store
            "2.675" to "2.67",              // 2.68
            "5.005" to "5.00",              // 5.01
            "1.005" to "1.00",              // 1.01
            "0.045" to "0.04",              // 0.05
            "12.125" to "12.12",            // 12.13
        )
        for ((typed, want) in expected) {
            assertEquals("₹$typed must store as the server stores it", want, stored(typed))
        }
    }

    /**
     * HALF_EVEN is not "always round down at a half": it rounds to the nearest EVEN last digit, and
     * for most decimals the binary double is not exactly a half at all, so the answer is simply the
     * nearest. These rows exist so a "fix" that rounds everything down fails too.
     */
    @Test
    fun `it is nearest-even and not a blanket round-down`() {
        assertEquals("1500.51", stored("1500.505"))
        assertEquals("0.01", stored("0.005"))
        assertEquals("2.35", stored("2.345"))
        assertEquals("3.35", stored("3.345"))
    }

    /** Negatives round toward the same digit, not away from zero. */
    @Test
    fun `negative amounts follow the same rule`() {
        assertEquals("-2.67", stored("-2.675"))
        assertEquals("-0.04", stored("-0.045"))
    }

    /**
     * The plain amounts must be untouched by the change. `toPlainString` and not `toString`: a
     * BigDecimal is entitled to print itself in scientific notation, and "1.25010E+3" in a costing
     * table is worse than a paisa.
     */
    @Test
    fun `ordinary amounts are stored exactly as before`() {
        assertEquals("1250.10", stored("1250.10"))
        assertEquals("1250.10", stored("₹1,250.10"))
        assertEquals("0.00", stored("0"))
        assertEquals("12.00", stored("12"))
        assertEquals("1000000.00", stored("1000000"))
    }

    /**
     * A typed amount and a computed one must land on the same string.
     *
     * They are printed side by side — a line rate the designer entered and the line total the phone
     * derived from it — and a costing table whose two columns round differently is a table an officer
     * is asked to sign and cannot reconcile.
     */
    @Test
    fun `the typed path agrees with the derived path`() {
        for (typed in listOf("2.675", "5.005", "1.005", "0.045", "12.125", "1500.505")) {
            val computed = java.math.BigDecimal(typed.toDouble())
                .setScale(2, java.math.RoundingMode.HALF_EVEN)
                .toPlainString()
            assertEquals("typed and derived must agree on $typed", computed, stored(typed))
        }
    }
}
