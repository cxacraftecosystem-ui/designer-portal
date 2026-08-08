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

/**
 * The fields that compute themselves, on the phone.
 *
 * WHAT THIS PROTECTS. `FieldDto` decoded every key of the field descriptor except the two that carry
 * a derivation — `derivedKind` and `derivedFrom` — and there was no port of `derive_value` here at
 * all. The registry's help text promises the behaviour outright ("Leave blank to compute it as
 * quantity × rate", "Leave blank to derive it from the start and end dates"), so a designer leaves
 * the box empty on the strength of the promise. On the web and on the server the number appeared. On
 * the phone the box stayed blank, and so did the cover page's duration and the cost sheet's total in
 * every report generated before the next sync — which in this app may be a fortnight away, in an
 * office, after the designer has left the cluster and can no longer check anything.
 *
 * The cases below are the ones where the three implementations could plausibly disagree, and each one
 * is a different wrong answer rather than a different shade of the same one: an off-by-one duration
 * that contradicts the attendance register, a zero where there is no answer at all, a total that
 * appears on the phone and is refused by the save.
 */
class DerivedFieldTest {

    // ── The three derivations, as the registry actually declares them ─────────────────────────────

    /** Stage 1's `durationDays`. */
    private val duration = FieldDto(
        key = "durationDays", label = "Duration", type = "INT", unit = "days",
        derivedKind = "DAYS_BETWEEN", derivedFrom = listOf("startDate", "endDate"),
    )

    /** A costing line's `amount`. */
    private val amount = FieldDto(
        key = "amount", label = "Amount", type = "MONEY", unit = "INR",
        derivedKind = "PRODUCT", derivedFrom = listOf("quantity", "rate"),
    )

    /** The cost sheet's `totalCost`, over six heads of which four are optional. */
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

    // ── The wire shape ───────────────────────────────────────────────────────────────────────────

    /**
     * The two keys have to survive the decode, or nothing below it can ever run.
     *
     * This is the whole of the original defect: the server has emitted both since `field_to_dict`
     * grew them, the bundled asset carries them, and `FieldDto` threw them away silently — kotlinx
     * ignores unknown keys, so there was no error anywhere to notice.
     */
    @Test
    fun `a derivation declared by the server survives the decode`() {
        val wire = """
            {"key":"durationDays","label":"Duration","type":"INT","unit":"days",
             "help":"Leave blank to derive it from the start and end dates.",
             "derivedKind":"DAYS_BETWEEN","derivedFrom":["startDate","endDate"]}
        """.trimIndent()
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(FieldDto.serializer(), wire)

        assertEquals("DAYS_BETWEEN", decoded.derivedKind)
        assertEquals(listOf("startDate", "endDate"), decoded.derivedFrom)
        assertTrue(DwDerived.isDerived(decoded))
    }

    /** A field with no derivation must not claim one, or every blank box would grow a hint. */
    @Test
    fun `an ordinary field is not derived`() {
        assertFalse(DwDerived.isDerived(FieldDto(key = "venue", label = "Venue", type = "TEXT")))
        assertNull(DwDerived.value(FieldDto(key = "venue", label = "Venue"), row("venue" to "Bagru")))
    }

    // ── DAYS_BETWEEN ─────────────────────────────────────────────────────────────────────────────

    /**
     * INCLUSIVE of both endpoints. The 12th to the 14th is three days, which is what the workshop's
     * attendance register and its utilisation certificate both say; the exclusive reading returns two
     * and disagrees with every other document in the file.
     */
    @Test
    fun `a duration counts both endpoints`() {
        assertEquals("3", derived(duration, row("startDate" to "2026-03-12", "endDate" to "2026-03-14")))
    }

    /** One day is one day, not zero. */
    @Test
    fun `a single-day workshop lasts one day`() {
        assertEquals("1", derived(duration, row("startDate" to "2026-03-12", "endDate" to "2026-03-12")))
    }

    /**
     * Across the March DST transition of the zones this handset may be set to.
     *
     * A local-time parse either side of a clock change loses or gains an hour, and an integer
     * division of the millisecond difference then reports 29 days where the sanction order says 30.
     * `LocalDate` carries no instant at all, so the arithmetic cannot be reached by a zone.
     */
    @Test
    fun `a duration spanning a daylight-saving change is not off by one`() {
        assertEquals("31", derived(duration, row("startDate" to "2026-03-01", "endDate" to "2026-03-31")))
        assertEquals("2", derived(duration, row("startDate" to "2026-03-08", "endDate" to "2026-03-09")))
    }

    /** A start with no end has no duration. NOT zero: "0 days" on a cover page is a wrong fact. */
    @Test
    fun `a missing endpoint yields nothing rather than zero`() {
        assertNull(DwDerived.value(duration, row("startDate" to "2026-03-12")))
        assertNull(DwDerived.value(duration, row("startDate" to "2026-03-12", "endDate" to "")))
        assertNull(DwDerived.value(duration, mapOf("startDate" to JsonPrimitive("2026-03-12"), "endDate" to JsonNull)))
    }

    /** An end before its start is not a negative duration, it is a typo with no answer. */
    @Test
    fun `an end before its start yields nothing`() {
        assertNull(DwDerived.value(duration, row("startDate" to "2026-03-14", "endDate" to "2026-03-12")))
    }

    @Test
    fun `an unparseable date yields nothing`() {
        assertNull(DwDerived.value(duration, row("startDate" to "12-03-2026", "endDate" to "2026-03-14")))
    }

    // ── PRODUCT ──────────────────────────────────────────────────────────────────────────────────

    /** MONEY is a fixed-2 STRING, so the figure survives the JSON round trip without an artefact. */
    @Test
    fun `a product of quantity and rate is a fixed two-decimal amount`() {
        assertEquals("3150.00", derived(amount, row("quantity" to "14", "rate" to "225")))
        assertEquals("1250.10", derived(amount, row("quantity" to "1", "rate" to "1250.10")))
    }

    /** Grouped input is what a spreadsheet paste looks like. */
    @Test
    fun `a product reads a grouped number`() {
        assertEquals("24000.00", derived(amount, row("quantity" to "2", "rate" to "12,000")))
    }

    /**
     * ANY blank factor abandons the whole product — the difference from SUM.
     *
     * A quantity with no rate is not a line worth nothing, it is a line not yet priced, and printing
     * ₹0.00 against it in a cost sheet a ministry reads is a claim rather than a blank.
     */
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

    /** A three-factor product — persons × days × rate, as the honorarium line declares it. */
    @Test
    fun `a product multiplies every factor it names`() {
        val honorarium = FieldDto(
            key = "amount", label = "Amount", type = "MONEY",
            derivedKind = "PRODUCT", derivedFrom = listOf("persons", "days", "rate"),
        )
        assertEquals("22500.00", derived(honorarium, row("persons" to "5", "days" to "9", "rate" to "500")))
        assertNull(DwDerived.value(honorarium, row("persons" to "5", "rate" to "500")))
    }

    // ── SUM ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * BLANK MEANS ZERO HERE, and is skipped. Four of the six heads are optional, so requiring all six
     * would leave `totalCost` empty for every workshop with no packaging or transport cost — most of
     * them.
     */
    @Test
    fun `a total sums the heads that were filled and skips the ones that were not`() {
        assertEquals(
            "1850.00",
            derived(totalCost, row("materialCost" to "1200", "labourCost" to "650")),
        )
    }

    /**
     * But a row with NONE of them filled has no total. It is an empty row, not a zero-rupee product,
     * and "₹ 0.00" printed into a cost sheet is a claim rather than a blank.
     */
    @Test
    fun `an empty row has no total rather than a total of zero`() {
        assertNull(DwDerived.value(totalCost, emptyMap()))
        assertNull(DwDerived.value(totalCost, row("materialCost" to "", "labourCost" to "")))
    }

    /** A head genuinely entered as zero IS an answer — "this workshop bought no packaging". */
    @Test
    fun `a head entered as zero still produces a total`() {
        assertEquals("0.00", derived(totalCost, row("packagingCost" to "0")))
    }

    /**
     * A BLANK AND AN UNPARSEABLE VALUE ARE NOT ALIKE, and conflating them is the subtle way this
     * would break: skipping "abc" the way a blank is skipped would show a total on the phone that the
     * server — which abandons the sum — then refuses, so the designer reads one figure and the report
     * prints another.
     */
    @Test
    fun `an unparseable head abandons the whole sum rather than being skipped`() {
        assertNull(DwDerived.value(totalCost, row("materialCost" to "1200", "labourCost" to "abc")))
    }

    // ── Sign ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * A NEGATIVE IS A NUMBER, not a failure to compute — and the sign has to reach the page.
     *
     * The trap is [daysBetween]'s `days > 0` guard, which is right there for anyone porting the next
     * derivation to copy: a positivity test on an amount would turn a returned line or a corrected
     * over-claim into a blank box, and a blank in a cost sheet is read as "not yet costed" rather than
     * as the deduction it actually is. The server applies that guard to DAYS_BETWEEN alone, so this
     * asserts the arithmetic carries the minus through both money kinds instead.
     */
    @Test
    fun `a negative amount keeps its sign rather than becoming nothing`() {
        assertEquals("-450.00", derived(amount, row("quantity" to "-2", "rate" to "225")))
        assertEquals(
            "1000.00",
            derived(totalCost, row("materialCost" to "1200", "labourCost" to "-200")),
        )
        // And a deduction that cancels the heads above it leaves a real zero total, which is a
        // different answer from the empty row that has no total at all.
        assertEquals(
            "0.00",
            derived(totalCost, row("materialCost" to "1200", "labourCost" to "-1200")),
        )
    }

    // ── Non-finite, at both ends ─────────────────────────────────────────────────────────────────

    /**
     * A stored "NaN" or "Infinity" must not become a printed figure.
     *
     * These are readable by `toDoubleOrNull` exactly as they are by `float()`, and a value written by
     * an older build — before `DwValues.coerce` refused them — is still sitting in drafts on
     * handsets. The derivation has to survive meeting one rather than propagate "₹ nan" into a total.
     */
    @Test
    fun `a non-finite input does not become a computed figure`() {
        assertNull(DwDerived.value(amount, row("quantity" to "NaN", "rate" to "225")))
        assertNull(DwDerived.value(amount, row("quantity" to "Infinity", "rate" to "225")))
        assertNull(DwDerived.value(totalCost, row("materialCost" to "1200", "labourCost" to "Infinity")))
    }

    /** Two finite factors can still overflow, and an infinite amount is not an amount. */
    @Test
    fun `a product that overflows to infinity yields nothing`() {
        assertNull(DwDerived.value(amount, row("quantity" to "1e300", "rate" to "1e300")))
    }

    // ── Non-money results ────────────────────────────────────────────────────────────────────────

    /** A non-MONEY derivation rounds to four places rather than stringifying to two. */
    @Test
    fun `a decimal derivation keeps four places`() {
        val metresSquared = FieldDto(
            key = "area", label = "Area", type = "DECIMAL",
            derivedKind = "PRODUCT", derivedFrom = listOf("width", "length"),
        )
        assertEquals("6.0501", derived(metresSquared, row("width" to "2.01", "length" to "3.01")))
    }

    /** An unknown kind is a phone behind the registry: show nothing, do not guess. */
    @Test
    fun `an unrecognised derivation kind yields nothing`() {
        val future = FieldDto(
            key = "x", label = "X", type = "DECIMAL",
            derivedKind = "GEOMETRIC_MEAN", derivedFrom = listOf("a", "b"),
        )
        assertNull(DwDerived.value(future, row("a" to "2", "b" to "8")))
    }
}
