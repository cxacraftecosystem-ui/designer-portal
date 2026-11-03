package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedFieldTest {

    private val duration = FieldDto(
        key = "durationDays", label = "Duration", type = "INT", unit = "days",
        derivedKind = "DAYS_BETWEEN", derivedFrom = listOf("startDate", "endDate"),
    )

    private val amount = FieldDto(
        key = "amount", label = "Amount", type = "MONEY", unit = "INR",
        derivedKind = "PRODUCT", derivedFrom = listOf("quantity", "rate"),
    )

    private val totalCost = FieldDto(
        key = "totalCost", label = "Total cost", type = "MONEY", unit = "INR",
        derivedKind = "SUM",
        derivedFrom = listOf(
            "materialCost", "labourCost", "packagingCost", "finishingCost", "transportCost",
            "overheadCost",
        ),
    )

    private fun row(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { (key, value) -> key to JsonPrimitive(value) }

    private fun derived(field: FieldDto, row: Map<String, JsonElement>): String? =
        DwDerived.value(field, row)?.let(DwValues::text)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a derivation declared by the server survives the decode`() {
        val wire = """
            {"key":"durationDays","label":"Duration","type":"INT","unit":"days",
             "help":"Leave blank to derive it from the start and end dates.",
             "derivedKind":"DAYS_BETWEEN","derivedFrom":["startDate","endDate"]}
        """.trimIndent()
        val decoded = json.decodeFromString(FieldDto.serializer(), wire)

        assertEquals("DAYS_BETWEEN", decoded.derivedKind)
        assertEquals(listOf("startDate", "endDate"), decoded.derivedFrom)
        assertTrue(DwDerived.isDerived(decoded))
    }

    @Test
    fun `an ordinary field is not derived`() {
        assertFalse(DwDerived.isDerived(FieldDto(key = "venue", label = "Venue", type = "TEXT")))
        assertNull(DwDerived.value(FieldDto(key = "venue", label = "Venue"), row("venue" to "Bagru")))
    }

    @Test
    fun `a duration counts both endpoints`() {
        assertEquals("3", derived(duration, row("startDate" to "2026-03-12", "endDate" to "2026-03-14")))
    }

    @Test
    fun `a single-day workshop lasts one day`() {
        assertEquals("1", derived(duration, row("startDate" to "2026-03-12", "endDate" to "2026-03-12")))
    }

    @Test
    fun `a duration spanning a daylight-saving change is not off by one`() {
        assertEquals("31", derived(duration, row("startDate" to "2026-03-01", "endDate" to "2026-03-31")))
        assertEquals("2", derived(duration, row("startDate" to "2026-03-08", "endDate" to "2026-03-09")))
    }

    @Test
    fun `a missing endpoint yields nothing rather than zero`() {
        assertNull(DwDerived.value(duration, row("startDate" to "2026-03-12")))
        assertNull(DwDerived.value(duration, row("startDate" to "2026-03-12", "endDate" to "")))
        assertNull(DwDerived.value(duration, mapOf("startDate" to JsonPrimitive("2026-03-12"), "endDate" to JsonNull)))
    }

    @Test
    fun `an end before its start yields nothing`() {
        assertNull(DwDerived.value(duration, row("startDate" to "2026-03-14", "endDate" to "2026-03-12")))
    }

    @Test
    fun `an unparseable date yields nothing`() {
        assertNull(DwDerived.value(duration, row("startDate" to "12-03-2026", "endDate" to "2026-03-14")))
    }

    @Test
    fun `a product of quantity and rate is a fixed two-decimal amount`() {
        assertEquals("3150.00", derived(amount, row("quantity" to "14", "rate" to "225")))
        assertEquals("1250.10", derived(amount, row("quantity" to "1", "rate" to "1250.10")))
    }

    @Test
    fun `a product reads a grouped number`() {
        assertEquals("24000.00", derived(amount, row("quantity" to "2", "rate" to "12,000")))
    }

    @Test
    fun `a product with any factor blank yields nothing`() {
        assertNull(DwDerived.value(amount, row("quantity" to "14")))
        assertNull(DwDerived.value(amount, row("quantity" to "14", "rate" to "")))
        assertNull(DwDerived.value(amount, row("quantity" to "", "rate" to "225")))
    }

    @Test
    fun `a product with an unparseable factor yields nothing`() {
        assertNull(DwDerived.value(amount, row("quantity" to "fourteen", "rate" to "225")))
    }

    @Test
    fun `a product multiplies every factor it names`() {
        val honorarium = FieldDto(
            key = "amount", label = "Amount", type = "MONEY",
            derivedKind = "PRODUCT", derivedFrom = listOf("persons", "days", "rate"),
        )
        assertEquals("22500.00", derived(honorarium, row("persons" to "5", "days" to "9", "rate" to "500")))
        assertNull(DwDerived.value(honorarium, row("persons" to "5", "rate" to "500")))
    }

    @Test
    fun `a total sums the heads that were filled and skips the ones that were not`() {
        assertEquals(
            "1850.00",
            derived(totalCost, row("materialCost" to "1200", "labourCost" to "650")),
        )
    }

    @Test
    fun `an empty row has no total rather than a total of zero`() {
        assertNull(DwDerived.value(totalCost, emptyMap()))
        assertNull(DwDerived.value(totalCost, row("materialCost" to "", "labourCost" to "")))
    }

    @Test
    fun `a head entered as zero still produces a total`() {
        assertEquals("0.00", derived(totalCost, row("packagingCost" to "0")))
    }

    @Test
    fun `an unparseable head abandons the whole sum rather than being skipped`() {
        assertNull(DwDerived.value(totalCost, row("materialCost" to "1200", "labourCost" to "abc")))
    }

    @Test
    fun `a negative amount keeps its sign rather than becoming nothing`() {
        assertEquals("-450.00", derived(amount, row("quantity" to "-2", "rate" to "225")))
        assertEquals(
            "1000.00",
            derived(totalCost, row("materialCost" to "1200", "labourCost" to "-200")),
        )
        assertEquals(
            "0.00",
            derived(totalCost, row("materialCost" to "1200", "labourCost" to "-1200")),
        )
    }

    @Test
    fun `a non-finite input does not become a computed figure`() {
        assertNull(DwDerived.value(amount, row("quantity" to "NaN", "rate" to "225")))
        assertNull(DwDerived.value(amount, row("quantity" to "Infinity", "rate" to "225")))
        assertNull(DwDerived.value(totalCost, row("materialCost" to "1200", "labourCost" to "Infinity")))
    }

    @Test
    fun `a product that overflows to infinity yields nothing`() {
        assertNull(DwDerived.value(amount, row("quantity" to "1e300", "rate" to "1e300")))
    }

    @Test
    fun `a decimal derivation keeps four places`() {
        val metresSquared = FieldDto(
            key = "area", label = "Area", type = "DECIMAL",
            derivedKind = "PRODUCT", derivedFrom = listOf("width", "length"),
        )
        assertEquals("6.0501", derived(metresSquared, row("width" to "2.01", "length" to "3.01")))
    }

    @Test
    fun `an unrecognised derivation kind yields nothing`() {
        val future = FieldDto(
            key = "x", label = "X", type = "DECIMAL",
            derivedKind = "GEOMETRIC_MEAN", derivedFrom = listOf("a", "b"),
        )
        assertNull(DwDerived.value(future, row("a" to "2", "b" to "8")))
    }
}
