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

    @Test
    fun `a date prints as the office prints it and never as the stored ISO string`() {
        val text = printed(mapOf("documentedOn" to JsonPrimitive("2026-02-10")))
        assertTrue("the printed date: $text", text.contains("Documented on: 10 Feb 2026"))
        assertFalse("the stored form must not reach the document: $text", text.contains("2026-02-10"))
    }

    @Test
    fun `an unparseable date is printed as it was stored`() {
        val text = printed(mapOf("documentedOn" to JsonPrimitive("last monsoon")))
        assertTrue(text.contains("Documented on: last monsoon"))
    }

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

    @Test
    fun `a money value that is not a number prints without the rupee sign`() {
        val text = printed(mapOf("cost" to JsonPrimitive("nan")))
        assertTrue("the unreadable cell, said plainly: $text", text.contains("Cost: nan"))
        assertFalse("never as an amount: $text", text.contains("₹"))
    }

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

    @Test
    fun `a large count is grouped and a small one is not`() {
        assertTrue(printed(mapOf("looms" to JsonPrimitive(250000))).contains("Looms: 2,50,000 looms"))
        assertTrue(printed(mapOf("looms" to JsonPrimitive(9999))).contains("Looms: 9999 looms"))
    }

    @Test
    fun `a decimal prints without trailing zeros, grouped once it is long enough`() {
        assertTrue(printed(mapOf("lengthCm" to JsonPrimitive(12.50))).contains("Length: 12.5 cm"))
        assertTrue(printed(mapOf("lengthCm" to JsonPrimitive(12345.60))).contains("Length: 12,345.6 cm"))
    }

    @Test
    fun `a percentage drops its trailing zeros`() {
        assertTrue(printed(mapOf("yieldPct" to JsonPrimitive(12.0))).contains("Yield: 12%"))
        assertTrue(printed(mapOf("yieldPct" to JsonPrimitive(12.5))).contains("Yield: 12.5%"))
    }

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
