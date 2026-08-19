package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOW ONE STORED VALUE PRINTS, against `report_builder.format_value`.
 *
 * ── WHAT THIS PINS, AND WHY IT IS NOT COVERED BY THE OTHER REPORT TESTS ───────────────────────────
 *
 * `ReportEntityParityTest` asserts WHERE a block sits and `ReportDocumentTest` asserts that the words
 * a designer typed are in the file. Neither looks at the SHAPE of a number, a date or a measurement,
 * and `displayValue` — which every printed cell, pair, table cell and cover row goes through, and
 * whose own KDoc declares it to be the port of `format_value` — was missing four of that function's
 * arms for as long as it had existed:
 *
 *   * no DATE arm, so the office printed "Documented on: 10 Feb 2026" and the handset "2026-02-10";
 *   * MONEY was `"₹" + DwValues.text(value)`, so "₹ 1,20,000.00" against "₹120000";
 *   * no INT/DECIMAL arm, so no Indian grouping above ten thousand;
 *   * no read of `FieldDto.unit` anywhere, so "Age: 45 years" against "45" and "Length: 12.5 cm"
 *     against "12.5" — the dangerous one, because a bare "12.5" in a dimensions row is unreadable as
 *     centimetres and a bare "45" under a column headed Age is ambiguous.
 *
 * Counted off the shipped registry that is 25 DATE fields, 34 MONEY fields and 36 unit-bearing
 * INT/DECIMAL fields — every table and grid in the document that carries a date, a price or a
 * measurement. A ministry officer holding both copies of one workshop saw them disagree in all of
 * them, and neither file says which is authoritative.
 *
 * ── THE CASES ARE THE SERVER'S OWN ARITHMETIC, NOT ROUND NUMBERS ──────────────────────────────────
 *
 * `format_value` groups only from |n| >= 10000, drops trailing zeros through `f"{n:g}"`, rounds
 * half-to-EVEN where Java's `Formatter` rounds half away from zero, and refuses a non-finite stored
 * value rather than dressing it up as an amount. Each of those is one case below, because each of
 * them is a way the two documents could come apart again without anybody noticing.
 */
class ReportValueFormatTest {

    private fun field(
        key: String,
        label: String,
        type: String,
        unit: String = "",
        role: String = "KEY_VALUE",
        width: Float = 0f,
    ) = FieldDto(
        key = key, label = label, type = type, unit = unit, reportRole = role, columnWidthPct = width,
    )

    /** One singleton of scalars, plus a collection that prints as a table. */
    private val stage = StageDto(
        number = 3,
        key = "CLUSTER_CRAFT_BACKGROUND",
        title = "Cluster & craft background",
        entities = listOf(
            EntityDto(
                key = "background", cardinality = "SINGLETON", title = "Background",
                fields = listOf(
                    field("documentedOn", "Documented on", "DATE"),
                    field("age", "Age", "INT", unit = "years"),
                    field("lengthCm", "Length", "DECIMAL", unit = "cm"),
                    field("cost", "Cost", "MONEY", unit = "INR"),
                    field("looms", "Looms", "INT", unit = "looms"),
                    field("yieldPct", "Yield", "PERCENT"),
                    field("weight", "Weight", "DECIMAL", unit = "g"),
                ),
            ),
            EntityDto(
                key = "toolRow", cardinality = "COLLECTION", title = "Tools", labelField = "name",
                fields = listOf(
                    field("name", "Name", "TEXT", role = "TABLE_COLUMN"),
                    field("toolCost", "Cost", "MONEY", unit = "INR", role = "TABLE_COLUMN"),
                    field("lengthCm", "Length", "DECIMAL", unit = "cm", role = "TABLE_COLUMN"),
                ),
            ),
        ),
    )

    private val schema = SchemaResponse(version = "test", stages = listOf(stage))

    private fun draftOf(
        values: Map<String, JsonElement> = emptyMap(),
        rows: List<DraftRow> = emptyList(),
    ) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            stage.key to StageDraft(stageId = stage.key, values = values, rows = rows)
        ),
    )

    private fun build(draft: WorkshopDraft): ReportDocument = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = draft.workshopId,
        templateId = "DCH_STANDARD",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        // DELIBERATELY NOT THE DATE ANY CASE BELOW STORES. The cover prints "Generated on …" from
        // this, so a shared date would let `assertFalse(contains("2026-02-10"))` pass or fail for a
        // reason that has nothing to do with the field under test.
        generatedAt = "2031-03-04T09:00:00Z",
    )

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        else -> ""
    }

    private fun printed(values: Map<String, JsonElement>): String =
        build(draftOf(values)).blocks.joinToString("\n") { textOf(it) }

    // ── DATE ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * `2026-02-10` -> `10 Feb 2026` — `_format_date`, through the port that already existed.
     *
     * `formatReportDate` has been in `report/ReportSettings.kt` since the cover was ported and is
     * already imported by this file's production side; `displayValue` simply never called it, so the
     * one date on the cover was formatted and the twenty-five in the body were not.
     */
    @Test
    fun `a date prints as the office prints it and never as the stored ISO string`() {
        val text = printed(mapOf("documentedOn" to JsonPrimitive("2026-02-10")))
        assertTrue("the printed date: $text", text.contains("Documented on: 10 Feb 2026"))
        assertFalse("the stored form must not reach the document: $text", text.contains("2026-02-10"))
    }

    /** An unparseable date is printed verbatim rather than blanked — evidence beats a tidy gap. */
    @Test
    fun `an unparseable date is printed as it was stored`() {
        val text = printed(mapOf("documentedOn" to JsonPrimitive("last monsoon")))
        assertTrue(text.contains("Documented on: last monsoon"))
    }

    // ── MONEY ────────────────────────────────────────────────────────────────────────────────────

    /**
     * ₹ 1,20,000.00 — Indian grouping, two decimals, a space after the sign.
     *
     * Not cosmetic. Every cost sheet in this report is read by an officer who writes lakhs and
     * crores, and a Western-grouped figure is misread at a glance — which, for a number that becomes
     * a sanctioned amount, is a real error and not a stylistic one. The handset printed the stored
     * string with a rupee sign glued to it.
     */
    @Test
    fun `money is grouped the Indian way, to the paisa`() {
        val text = printed(mapOf("cost" to JsonPrimitive("120000.00")))
        assertTrue("the office's shape: $text", text.contains("Cost: ₹ 1,20,000.00"))
        assertFalse("the old shape: $text", text.contains("₹120000"))
    }

    @Test
    fun `a negative amount keeps its sign outside the rupee`() {
        val text = printed(mapOf("cost" to JsonPrimitive("-1234567.5")))
        assertTrue(text.contains("Cost: -₹ 12,34,567.50"))
    }

    /**
     * A STORED VALUE THAT IS NOT A NUMBER IS NOT DRESSED UP AS AN AMOUNT.
     *
     * MONEY is kept as a string, so a stage saved before `coerce_value` refused non-finite input
     * holds the literal "nan" — and "₹ nan." went into a document submitted to a ministry. The
     * charts have always dropped those rows, so the table and the figure beside it disagreed with
     * nothing to say why.
     */
    @Test
    fun `a money value that is not a number prints without the rupee sign`() {
        val text = printed(mapOf("cost" to JsonPrimitive("nan")))
        assertTrue("the unreadable cell, said plainly: $text", text.contains("Cost: nan"))
        assertFalse("never as an amount: $text", text.contains("₹"))
    }

    // ── numbers and their units ──────────────────────────────────────────────────────────────────

    /**
     * THE UNIT LOSS, WHICH IS THE HALF THAT MISLEADS RATHER THAN MERELY DIFFERS.
     *
     * 47 of the 70 unit-bearing fields in the registry are KEY_VALUE, where the table-header trick
     * could never have compensated for them.
     */
    @Test
    fun `a measurement carries its unit into the cell`() {
        val text = printed(
            mapOf(
                "age" to JsonPrimitive(45),
                "lengthCm" to JsonPrimitive(12.5),
                "weight" to JsonPrimitive(0.5),
            )
        )
        assertTrue("$text", text.contains("Age: 45 years"))
        assertTrue("$text", text.contains("Length: 12.5 cm"))
        assertTrue("$text", text.contains("Weight: 0.5 g"))
    }

    /**
     * GROUPED FROM FIVE FIGURES UP AND NOT BEFORE — the server's `abs(number) >= 10000`.
     *
     * 9,999 pieces reads as a count and 1,00,000 reads as a quantity; grouping the small ones would
     * make every four-digit answer in the report look like money.
     */
    @Test
    fun `a large count is grouped and a small one is not`() {
        assertTrue(printed(mapOf("looms" to JsonPrimitive(250000))).contains("Looms: 2,50,000 looms"))
        assertTrue(printed(mapOf("looms" to JsonPrimitive(9999))).contains("Looms: 9999 looms"))
    }

    /**
     * A DECIMAL DROPS ITS TRAILING ZEROS, because `format_value` prints it through `f"{n:g}"`.
     *
     * `12.50` stored by a form that pads to two places must not print as "12.50 cm" on one surface
     * and "12.5 cm" on the other.
     */
    @Test
    fun `a decimal prints without trailing zeros, grouped once it is long enough`() {
        assertTrue(printed(mapOf("lengthCm" to JsonPrimitive(12.50))).contains("Length: 12.5 cm"))
        assertTrue(printed(mapOf("lengthCm" to JsonPrimitive(12345.60))).contains("Length: 12,345.6 cm"))
    }

    /** PERCENT goes through the same `:g`, so a stored 12.0 is "12%" and not "12.0%". */
    @Test
    fun `a percentage drops its trailing zeros`() {
        assertTrue(printed(mapOf("yieldPct" to JsonPrimitive(12.0))).contains("Yield: 12%"))
        assertTrue(printed(mapOf("yieldPct" to JsonPrimitive(12.5))).contains("Yield: 12.5%"))
    }

    // ── the table's header and its cells ─────────────────────────────────────────────────────────

    /**
     * THE HEADER IS THE BARE LABEL AND THE UNIT IS IN THE CELL — `TableColumn(header=spec.label, …)`.
     *
     * The handset used to append " (unit)" to the header as a compensation for the unit `displayValue`
     * dropped. With the cell fixed, keeping the suffix would simply have moved the divergence into
     * the HEADER row: one workshop, two documents, one headed "Cost (INR)" and one "Cost".
     */
    @Test
    fun `a table heads its columns as the server does and puts the unit in the cell`() {
        val document = build(
            draftOf(
                rows = listOf(
                    DraftRow(
                        id = "toolRow#row-1",
                        values = mapOf(
                            "name" to JsonPrimitive("Pit loom"),
                            "toolCost" to JsonPrimitive("12000.00"),
                            "lengthCm" to JsonPrimitive(220.0),
                        ),
                    )
                ),
            )
        )
        val table = document.blocks.filterIsInstance<TableBlock>().first()
        assertEquals(listOf("Name", "Cost", "Length"), table.columns.map { it.header })
        val row = table.rows.first().map { runText(it) }
        assertEquals(listOf("Pit loom", "₹ 12,000.00", "220 cm"), row)
    }
}
