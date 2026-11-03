package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DwOverflowParityTest {

    private fun row(vararg pairs: Pair<String, String>): DwDataRow =
        pairs.associate { (key, value) -> key to (JsonPrimitive(value) as JsonElement) }

    @Test
    fun `a non-finite formats the way Python's format spec spells it`() {
        assertEquals("inf", DwPy.format(Double.POSITIVE_INFINITY, 2, grouped = true))
        assertEquals("-inf", DwPy.format(Double.NEGATIVE_INFINITY, 2, grouped = true))
        assertEquals("inf", DwPy.format(Double.POSITIVE_INFINITY, 0, grouped = false))
        assertEquals("-inf", DwPy.format(Double.NEGATIVE_INFINITY, 1, grouped = false))
        assertEquals("nan", DwPy.format(Double.NaN, 1, grouped = false))
        assertEquals("nan", DwPy.format(Double.NaN, 2, grouped = true))
        assertEquals("nan", DwPy.format(-Double.NaN, 2, grouped = true))
    }

    @Test
    fun `a material roll-up that overflows reports the sentence Python reports`() {
        val sheets = listOf(row("_entryId" to "s1", "materialCost" to "1.00"))
        val material = listOf(
            row("costSheetRef" to "s1", "item" to "Yarn A", "amount" to "1e308"),
            row("costSheetRef" to "s1", "item" to "Yarn B", "amount" to "1e308"),
        )
        val findings = DwCostIntegrity.analyse(sheets, material, emptyList())
        val check = findings.sheets.single().checks.single { it.key == "materialCost" }
        assertEquals("MISMATCH", check.verdict)
        assertEquals(
            "Cost sheet 1: the 2 material lines add up to ₹inf, but the sheet declares ₹1.00 — " +
                "₹inf less than the lines account for.",
            check.message,
        )
    }

    @Test
    fun `overflowing cost heads report inf and a NaN margin exactly as Python does`() {
        val sheets = listOf(
            row(
                "_entryId" to "s2",
                "packagingCost" to "1e308",
                "finishingCost" to "1e308",
                "totalCost" to "1.00",
                "expectedPrice" to "2.00",
                "marginPercent" to "55",
            )
        )
        val findings = DwCostIntegrity.analyse(sheets, emptyList(), emptyList())
        val sheet = findings.sheets.single()

        val total = sheet.checks.single { it.key == "totalCost" }
        assertEquals("MISMATCH", total.verdict)
        assertEquals(
            "Cost sheet 1: the six cost heads add up to ₹inf, but the stored total cost is ₹1.00. " +
                "Total cost is a derived field, so the stored value is out of date — reopening the " +
                "sheet and saving it will recompute it.",
            total.message,
        )

        val margin = sheet.checks.single { it.key == "marginPercent" }
        assertEquals("MISMATCH", margin.verdict)
        assertEquals(
            "Cost sheet 1: a margin of 55.0% is declared, but the expected price and total cost on " +
                "this sheet imply nan% on cost.",
            margin.message,
        )

        assertEquals("BELOW_COST", sheet.margin.verdict)
        assertEquals(
            "Cost sheet 1: the expected price ₹2.00 is below the total cost ₹inf — a loss of ₹inf " +
                "on every piece sold.",
            sheet.margin.message,
        )
    }

    @Test
    fun `a band message whose median overflows prints the sentence Python prints`() {
        val responses = (0 until 8).map { index ->
            row(
                "priceExpectation" to (if (index < 4) "-1e308" else "1e308"),
                "respondentName" to "R$index",
            )
        }
        val bands = listOf(row("category" to "Stoles", "lowPrice" to "0", "highPrice" to "100"))
        val findings = DwMarketAnalysis.analyse(responses, emptyList(), bands, emptyList())
        val band = findings.bands.single()
        assertEquals("NARROW", band.verdict)
        assertEquals(
            "The band ₹0–100 covers only 0 of 8 observations for Stoles (4 below, 4 above; median " +
                "₹inf). Widening it, or splitting the category, would match what was recorded.",
            band.message,
        )
    }
}
