package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A total that overflows to infinity, which Python PRINTS and this port used to CRASH on.
 *
 * WHAT WAS WRONG. [DwPy.round] guards `!value.isFinite()` before it touches [java.math.BigDecimal];
 * [DwPy.format], four lines below it, did not — and `BigDecimal(double)` throws
 * `NumberFormatException` outright on an infinity or a NaN rather than returning anything. Every
 * money sentence in both ports goes through `format`, so the first sheet whose lines added up past
 * the top of a Double took the stage screen down with it. [DwDerived.formatted] already carries this
 * exact guard, in this exact codebase, with a comment saying why ("a finite factor times a finite
 * factor still overflows to infinity, and BigDecimal refuses a non-finite outright"), which is what
 * makes the omission here an oversight rather than a judgement.
 *
 * WHY IT IS REACHABLE AT ALL, since `as_number` refuses a non-finite INPUT. It refuses the input and
 * says nothing about the output: two FINITE line amounts near the top of the range sum to infinity,
 * and a division by a denormal cost does the same. `DwValues.coerce` stops a designer typing
 * "Infinity" (see [NonFiniteValueTest]) and stops nobody storing 1e308 — and the rows the stage-9
 * panel analyses do not all come from this handset's keyboard: they are also read back from the API
 * and from a draft written by an older build.
 *
 * THE EXPECTED STRINGS ARE PYTHON'S, CHARACTER FOR CHARACTER, produced by running these same rows
 * through `backend/app/services/{market_analysis,cost_integrity}.py`. That is the whole point: the
 * correct behaviour here is not "do not crash", it is "print what the server prints", because the
 * designer's copy of the finding and the ministry's copy have to be the same sentence. Python's
 * format spec renders these as `inf`, `-inf` and `nan` — lower case, no thousands separator, and no
 * sign on a NaN however its sign bit is set.
 *
 * These cases are pinned HERE rather than in `dw-analysis-cases.json` for a mechanical reason: their
 * payloads carry `Infinity` and `NaN` as JSON NUMBERS, and JSON has no such literals — `json.dumps`
 * emits them anyway, so freezing these cases into the golden would put a document outside the grammar
 * into the file every other case has to be read out of. The sentence is the part a designer actually
 * reads, and the sentence is what is pinned.
 */
class DwOverflowParityTest {

    private fun row(vararg pairs: Pair<String, String>): DwDataRow =
        pairs.associate { (key, value) -> key to (JsonPrimitive(value) as JsonElement) }

    /**
     * `format(inf, ',.2f')` in Python is `inf` — not a raise, and not `∞`.
     *
     * Checked at the primitive rather than only through the modules above it because every money
     * sentence in both ports funnels through this one function, so a regression here is a regression
     * in all of them at once.
     */
    @Test
    fun `a non-finite formats the way Python's format spec spells it`() {
        assertEquals("inf", DwPy.format(Double.POSITIVE_INFINITY, 2, grouped = true))
        assertEquals("-inf", DwPy.format(Double.NEGATIVE_INFINITY, 2, grouped = true))
        assertEquals("inf", DwPy.format(Double.POSITIVE_INFINITY, 0, grouped = false))
        assertEquals("-inf", DwPy.format(Double.NEGATIVE_INFINITY, 1, grouped = false))
        assertEquals("nan", DwPy.format(Double.NaN, 1, grouped = false))
        assertEquals("nan", DwPy.format(Double.NaN, 2, grouped = true))
        // Python prints `nan` for a negatively-signed NaN too, so the NaN test must come BEFORE the
        // sign test — `doubleToRawLongBits(-NaN) < 0` is true and would otherwise print `-nan`.
        assertEquals("nan", DwPy.format(-Double.NaN, 2, grouped = true))
    }

    /**
     * Two material lines whose amounts sum past the top of a Double, under a sheet declaring ₹1.00.
     *
     * The roll-up is infinite, the difference is negative infinity, and the finding is still a
     * finding: the sheet really does contradict its lines. Python says so in one sentence and this
     * must say the same one.
     */
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
            "Cost sheet 1: the 2 material line(s) add up to ₹inf, but the sheet declares ₹1.00 — " +
                "₹inf less than the lines account for.",
            check.message,
        )
    }

    /**
     * Cost heads that overflow, against a price that does not — the case that also produces a NaN.
     *
     * `amount / cost` is `-inf / inf`, which is NaN rather than an infinity, so this pins the second
     * spelling as well as the first. Note the margin is still reported BELOW_COST and not swallowed:
     * a product priced at ₹2.00 against an unreadable-magnitude cost is a loss, and refusing to say
     * so would be the module concealing the one finding it exists for.
     */
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

    /**
     * A price band judged against a sample whose own median overflows.
     *
     * The median is an INTERPOLATION — `v[lower] + (v[upper] - v[lower]) * fraction` — so a sample
     * holding one extreme negative and one extreme positive overflows in the subtraction, and the
     * band message prints that median. Eight observations, because seven would be withheld as
     * UNVERIFIABLE and the message would never reach the formatter.
     */
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
