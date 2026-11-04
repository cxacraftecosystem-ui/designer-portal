package com.designprototype.workshop.ui.designworkshop

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRecordingPlaceTest {

    private fun schemaOf(vararg stages: StageDto) =
        SchemaResponse(version = "test", stages = stages.toList())

    private val stageFourteen = StageDto(
        number = 14, key = "PROTOTYPE_ITERATION", title = "Prototype iteration",
        entities = listOf(
            EntityDto(
                key = "iteration", cardinality = "SINGLETON", title = "Iteration",
                fields = listOf(
                    FieldDto(key = "summary", label = "What changed", type = "TEXT", reportRole = "KEY_VALUE"),
                ),
            )
        ),
    )

    private fun place(
        village: String? = "Barpali",
        district: String? = "Bargarh",
        state: String? = "Odisha",
    ): JsonElement = buildJsonObject {
        put("lat", 21.18540)
        put("lon", 83.58750)
        village?.let { put("village", it) }
        district?.let { put("district", it) }
        state?.let { put("state", it) }
    }

    private fun draftOf(values: Map<String, JsonElement>) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            "PROTOTYPE_ITERATION" to StageDraft(stageId = "PROTOTYPE_ITERATION", values = values),
        ),
    )

    private fun build(draft: WorkshopDraft): ReportDocument = buildWorkshopDocument(
        schema = schemaOf(stageFourteen),
        draft = draft,
        workshopId = draft.workshopId,
        templateId = "DCH_STANDARD",
        templateName = "DCH standard",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        generatedAt = "2026-08-15T09:30:00Z",
    )

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        else -> ""
    }

    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    private fun headings(document: ReportDocument): List<String> =
        document.blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs) }

    @Test
    fun `the place a stage was recorded in prints under that stage's heading`() {
        val document = build(
            draftOf(
                mapOf(
                    "summary" to JsonPrimitive("Narrowed the border to two inches."),
                    DW_RECORDING_PLACE_KEY to place(),
                )
            )
        )

        val printed = printedText(document)
        assertTrue(
            "the recording place reached no part of the document:\n$printed",
            printed.contains("Recorded at Barpali, Bargarh, Odisha"),
        )
        assertTrue(
            "the coordinate is missing, so the line cannot be audited:\n$printed",
            printed.contains("(21.18540, 83.58750)"),
        )
        assertTrue(
            "the provenance line must end at the coordinate:\n$printed",
            printed.contains("Recorded at Barpali, Bargarh, Odisha (21.18540, 83.58750)."),
        )
    }

    @Test
    fun `a place with no named address still prints its coordinate`() {
        val document = build(
            draftOf(
                mapOf(
                    "summary" to JsonPrimitive("Narrowed the border to two inches."),
                    DW_RECORDING_PLACE_KEY to place(village = null, district = null, state = null),
                )
            )
        )

        val printed = printedText(document)
        assertTrue("the unnamed reading printed nothing:\n$printed", printed.contains("Recorded at 21.18540, 83.58750"))
        assertFalse(
            "an empty name list must not print as stray punctuation:\n$printed",
            printed.contains("Recorded at  ") || printed.contains("Recorded at (") || printed.contains(", ("),
        )
    }

    @Test
    fun `a stage nobody answered the card on prints no provenance line`() {
        val document = build(draftOf(mapOf("summary" to JsonPrimitive("Narrowed the border."))))

        assertFalse(
            "a line was invented for a card that was never answered:\n${printedText(document)}",
            printedText(document).contains("Recorded at"),
        )
    }

    @Test
    fun `a stage holding only a recording place is omitted from the document entirely`() {
        val document = build(draftOf(mapOf(DW_RECORDING_PLACE_KEY to place())))

        assertFalse(
            "a stage with no answers printed a heading anyway:\n${headings(document)}",
            headings(document).any { it.contains("Prototype iteration") },
        )
        assertFalse(
            "and with it the provenance line, standing over nothing:\n${printedText(document)}",
            printedText(document).contains("Recorded at"),
        )
    }

    @Test
    fun `the provenance line does not suppress the empty-section note`() {
        val document = build(
            draftOf(
                mapOf(
                    "retiredOnTheServer" to JsonPrimitive("an answer to a question no longer asked"),
                    DW_RECORDING_PLACE_KEY to place(),
                )
            )
        )

        val printed = printedText(document)
        assertTrue(
            "the section was not drawn at all, so this test no longer covers what it says:\n$printed",
            printed.contains("Recorded at Barpali"),
        )
        assertTrue(
            "the provenance line was counted as content and suppressed the note:\n$printed",
            printed.contains("Not recorded."),
        )
    }
}
