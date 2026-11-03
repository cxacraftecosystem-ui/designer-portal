package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NonFiniteValueTest {

    private val money = FieldDto(key = "materialCost", label = "Material cost", type = "MONEY", minValue = 0.0)
    private val decimal = FieldDto(key = "widthCm", label = "Width", type = "DECIMAL")
    private val percent = FieldDto(key = "shrinkage", label = "Shrinkage", type = "PERCENT", minValue = 0.0, maxValue = 100.0)
    private val whole = FieldDto(key = "participants", label = "Participants", type = "INT")

    private fun assertRefused(field: FieldDto, typed: String) {
        val coerced = DwValues.coerce(field, typed)
        assertNull("“$typed” must not be stored in ${field.label}", coerced.value)
        assertNotNull("“$typed” in ${field.label} must produce a message", coerced.error)
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

    @Test
    fun `a number too large to represent is refused`() {
        assertRefused(money, "1e400")
        assertRefused(decimal, "9".repeat(400))
    }

    @Test
    fun `a non-finite does not pass a bound it could never satisfy`() {
        assertRefused(money, "NaN")
        assertRefused(percent, "Infinity")
    }

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

    @Test
    fun `a blank box is still not an error`() {
        assertNull(DwValues.coerce(money, "").value)
        assertNull(DwValues.coerce(money, "").error)
    }

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
