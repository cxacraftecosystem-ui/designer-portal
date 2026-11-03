package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyRoundingParityTest {

    private val money = FieldDto(key = "unitRate", label = "Rate per unit", type = "MONEY")

    private fun stored(typed: String): String? =
        (DwValues.coerce(money, typed).value as? JsonPrimitive)?.content

    @Test
    fun `three-decimal amounts round the way the server rounds them`() {
        val expected = mapOf(
            "2.675" to "2.67",
            "5.005" to "5.00",
            "1.005" to "1.00",
            "0.045" to "0.04",
            "12.125" to "12.12",
        )
        for ((typed, want) in expected) {
            assertEquals("₹$typed must store as the server stores it", want, stored(typed))
        }
    }

    @Test
    fun `it is nearest-even and not a blanket round-down`() {
        assertEquals("1500.51", stored("1500.505"))
        assertEquals("0.01", stored("0.005"))
        assertEquals("2.35", stored("2.345"))
        assertEquals("3.35", stored("3.345"))
    }

    @Test
    fun `negative amounts follow the same rule`() {
        assertEquals("-2.67", stored("-2.675"))
        assertEquals("-0.04", stored("-0.045"))
    }

    @Test
    fun `ordinary amounts are stored exactly as before`() {
        assertEquals("1250.10", stored("1250.10"))
        assertEquals("1250.10", stored("₹1,250.10"))
        assertEquals("0.00", stored("0"))
        assertEquals("12.00", stored("12"))
        assertEquals("1000000.00", stored("1000000"))
    }

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
