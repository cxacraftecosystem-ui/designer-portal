package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwCustomCache
import com.designprototype.workshop.data.DwCustomCopy
import com.designprototype.workshop.data.DwCustomFieldDto
import com.designprototype.workshop.data.DwCustomOptionDto
import com.designprototype.workshop.data.DwCustomSectionDto
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.customSectionsForReport
import com.designprototype.workshop.data.dwCustomSectionWarnings
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.ParaStyle
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportRecord
import com.designprototype.workshop.report.ReportTheme
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.SpecialSection
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TemplateSection
import com.designprototype.workshop.report.applyReportSettings
import com.designprototype.workshop.report.reportMetaFor
import com.designprototype.workshop.report.reportTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCustomSectionTest {

    private val stage = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "traditionalProcess", cardinality = "SINGLETON", title = "Traditional process",
                fields = listOf(FieldDto(key = "currentProblems", label = "Current problems", type = "TEXT")),
            ),
        ),
    )

    private val sketches = StageDto(
        number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development",
        entities = listOf(
            EntityDto(
                key = "sketch", cardinality = "COLLECTION", title = "Sketch",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT")),
            ),
        ),
    )

    private val schema = SchemaResponse(version = "test", stages = listOf(stage, sketches))

    private fun definition(vararg sections: DwCustomSectionDto) = DwCustomCache(
        workshopId = "w1", customSchemaVersion = "d1", complete = true, sections = sections.toList(),
    )

    private val loomAudit = DwCustomSectionDto(
        id = "sec1", key = "loomAudit", stageKey = stage.key, title = "Loom audit",
        description = "Questions this cluster asked that the standard form does not.",
        fields = listOf(
            DwCustomFieldDto(
                id = "f1", key = "loomsWorking", label = "Looms in working order", type = "INT",
                tier = "BASIC", required = true, unit = "looms",
            ),
            DwCustomFieldDto(
                id = "f2", key = "warpMaterial", label = "Warp material", type = "ENUM",
                options = listOf(DwCustomOptionDto("COTTON", "Cotton")), sortOrder = 1,
            ),
        ),
    )

    private fun draftWith(custom: Map<String, JsonElement>) = WorkshopDraft(
        workshopId = "w1",
        stages = mapOf(
            stage.key to StageDraft(
                stageId = stage.key, title = stage.title, order = 5,
                values = mapOf("currentProblems" to JsonPrimitive("Slow warping")),
                custom = custom,
            ),
        ),
    )

    private fun planFor(
        custom: DwCustomCache?,
        draft: WorkshopDraft?,
        settings: Map<String, JsonElement>? = null,
        vararg sections: TemplateSection,
    ): ReportPlan {
        val base = reportTemplate("DCH_STANDARD").copy(
            sections = sections.toList(), numberHeadings = false, showEmptyNote = false,
        )
        val template = applyReportSettings(
            base,
            settings,
            customSections = customSectionsForReport(custom) { stageKey ->
                draft?.stages?.get(stageKey)?.custom.orEmpty()
            },
        )
        return ReportPlan(
            template = template,
            meta = reportMetaFor(ReportRecord(id = "w1", title = "T"), "DCH_STANDARD", null, ""),
            theme = ReportTheme(),
            settings = settings.orEmpty(),
            warnings = emptyList(),
        )
    }

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(blocks: List<Block>): String = blocks.joinToString("\n") { block ->
        when (block) {
            is HeadingBlock -> runText(block.runs)
            is ParagraphBlock -> runText(block.runs)
            is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
            is BulletListBlock -> block.items.joinToString("\n") { runText(it) }
            is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
            else -> ""
        }
    }

    private fun document(
        draft: WorkshopDraft?,
        custom: DwCustomCache?,
        stageKey: String = stage.key,
        settings: Map<String, JsonElement>? = null,
        sections: List<TemplateSection> = listOf(TemplateSection(stageKey = stageKey)),
    ) = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = "w1",
        templateId = "DCH_STANDARD",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        plan = planFor(custom, draft, settings, *sections.toTypedArray()),
        customSections = custom,
    )

    @Test
    fun `a designer's own question and its answer reach the document`() {
        val text = textOf(
            document(
                draftWith(mapOf("loomsWorking" to JsonPrimitive(12), "warpMaterial" to JsonPrimitive("COTTON"))),
                definition(loomAudit),
            ).blocks
        )
        assertTrue("the section heading: $text", text.contains("Loom audit"))
        assertTrue("the question", text.contains("Looms in working order"))
        assertTrue("the answer", text.contains("12"))
        assertTrue("the enum label rather than the token", text.contains("Cotton"))
        assertTrue("the unit, as the office's copy prints it: $text", text.contains("12 looms"))
    }

    @Test
    fun `the section description prints at the lead style the server gives it`() {
        val blocks = document(
            draftWith(mapOf("loomsWorking" to JsonPrimitive(12))),
            definition(loomAudit),
        ).blocks
        val description = blocks.filterIsInstance<ParagraphBlock>()
            .firstOrNull { runText(it.runs).contains("Questions this cluster asked") }
        assertNotNull("the description did not reach the document at all", description)
        assertEquals(
            "the designer's own description printed in the register this file reserves for notes " +
                "ABOUT the document, where the office prints it as the section's opening sentence",
            ParaStyle.LEAD,
            description!!.style,
        )
    }

    @Test
    fun `a device holding no definition prints exactly what it always did`() {
        val draft = draftWith(mapOf("loomsWorking" to JsonPrimitive(12)))
        val without = textOf(document(draft, null).blocks)
        assertFalse(without.contains("Loom audit"))
        assertTrue("the stage's own answers still print", without.contains("Slow warping"))
        val said = dwCustomSectionWarnings(DwCustomCopy.UNKNOWN, answersHeld = true).single()
        assertTrue(said.contains("connection"))
    }

    @Test
    fun `a collections-only stage whose only answers are custom is still printed`() {
        val sketchAudit = DwCustomSectionDto(
            id = "sec2", key = "sketchAudit", stageKey = sketches.key, title = "Sketch audit",
            fields = listOf(DwCustomFieldDto(id = "f", key = "sheets", label = "Sheets used", type = "INT")),
        )
        val draft = WorkshopDraft(
            workshopId = "w1",
            stages = mapOf(
                sketches.key to StageDraft(
                    stageId = sketches.key, title = sketches.title, order = 11,
                    custom = mapOf("sheets" to JsonPrimitive(9)),
                ),
            ),
        )
        val text = textOf(document(draft, definition(sketchAudit), stageKey = sketches.key).blocks)
        assertTrue(
            "the designer's block prints even though the stage section is legitimately dropped: $text",
            text.contains("Sketch audit"),
        )
        assertTrue(text.contains("Sheets used"))
        assertTrue(text.contains("9"))
        assertFalse("the stage's own section must not print: $text", text.contains("Sketch development"))
    }

    @Test
    fun `an untouched custom section draws no heading`() {
        val text = textOf(document(draftWith(emptyMap()), definition(loomAudit)).blocks)
        assertFalse("no empty heading: $text", text.contains("Loom audit"))
        assertTrue(text.contains("Slow warping"))
    }

    @Test
    fun `a retired question's answer is printed and marked as no longer asked`() {
        val superseded = loomAudit.copy(
            fields = loomAudit.fields + DwCustomFieldDto(
                id = "f0", key = "looms", label = "How many looms?", type = "INT",
                retired = true, supersededById = "f1", sortOrder = 2,
            ),
        )
        val text = textOf(
            document(
                draftWith(mapOf("loomsWorking" to JsonPrimitive(12), "looms" to JsonPrimitive(9))),
                definition(superseded),
            ).blocks
        )
        assertTrue("the old answer survives: $text", text.contains("How many looms?"))
        assertTrue(text.contains("no longer asks"))
    }

    @Test
    fun `an undrawable custom answer is not printed as though it were understood`() {
        val withFuture = loomAudit.copy(
            fields = loomAudit.fields + DwCustomFieldDto(
                id = "f9", key = "signedBy", label = "Signed by", type = "SIGNATURE", sortOrder = 3,
            ),
        )
        val text = textOf(
            document(
                draftWith(
                    mapOf(
                        "loomsWorking" to JsonPrimitive(12),
                        "signedBy" to JsonPrimitive("{\"strokes\":[]}"),
                    )
                ),
                definition(withFuture),
            ).blocks
        )
        assertTrue(text.contains("Looms in working order"))
        assertFalse("an unreadable shape must not be printed as an answer: $text", text.contains("strokes"))
        assertTrue("the question is named: $text", text.contains("Signed by"))
        assertTrue("and it points to where the answer is held", text.contains("online workshop record"))
    }

    @Test
    fun `custom questions print after the stage's own fields`() {
        val text = textOf(
            document(draftWith(mapOf("loomsWorking" to JsonPrimitive(12))), definition(loomAudit)).blocks
        )
        assertTrue(text.indexOf("Slow warping") < text.indexOf("Loom audit"))
    }

    @Test
    fun `a designer's section is a top-level heading, not a sub-heading inside the stage`() {
        val document = document(
            draftWith(mapOf("loomsWorking" to JsonPrimitive(12))),
            definition(loomAudit),
        )
        val heading = document.blocks.filterIsInstance<HeadingBlock>()
            .first { runText(it.runs).contains("Loom audit") }
        assertEquals("a designer's section is a section, not a sub-heading", 1, heading.level)
    }

    @Test
    fun `a section whose stage the template never prints becomes a back annexure`() {
        val blocks = document(
            draftWith(mapOf("loomsWorking" to JsonPrimitive(12))),
            definition(loomAudit),
            sections = listOf(
                TemplateSection(stageKey = sketches.key),
                TemplateSection(special = SpecialSection.COMPLETENESS),
            ),
        ).blocks
        val text = textOf(blocks)
        assertTrue(
            "the designer's section has its own heading: $text",
            blocks.filterIsInstance<HeadingBlock>().any { runText(it.runs).contains("Loom audit") },
        )
        assertTrue("and the answer under it: $text", text.contains("Looms in working order: 12 looms"))
    }

    @Test
    fun `the back annexure sits before the completeness section and takes a page break`() {
        val plan = planFor(
            definition(loomAudit),
            draftWith(mapOf("loomsWorking" to JsonPrimitive(12))),
            null,
            TemplateSection(stageKey = sketches.key),
            TemplateSection(special = SpecialSection.COMPLETENESS),
        )
        assertEquals(
            listOf(null, SpecialSection.CUSTOM_SECTION, SpecialSection.COMPLETENESS),
            plan.template.sections.map { it.special },
        )
        val block = plan.template.sections.single { it.special == SpecialSection.CUSTOM_SECTION }
        assertEquals("loomAudit", block.customKey)
        assertTrue("an annexure starts on its own page", block.pageBreakBefore)
    }

    @Test
    fun `a section whose stage was excluded in stage 20 is still printed`() {
        val excluded = mapOf(
            "excludedStages" to JsonArray(listOf(JsonPrimitive(stage.key)))
        )
        val text = textOf(
            document(
                draftWith(mapOf("loomsWorking" to JsonPrimitive(12))),
                definition(loomAudit),
                settings = excluded,
            ).blocks
        )
        assertFalse("the stage itself is gone, as asked: $text", text.contains("Slow warping"))
        assertTrue("the designer's own questions are not: $text", text.contains("Loom audit"))
    }

    @Test
    fun `two sections on one stage print in the designer's sort order`() {
        val dyeBath = DwCustomSectionDto(
            id = "sec3", key = "dyeBath", stageKey = stage.key, title = "Dye bath log", sortOrder = 1,
            fields = listOf(DwCustomFieldDto(id = "d1", key = "dyeSource", label = "Dye source", type = "TEXT")),
        )
        val text = textOf(
            document(
                draftWith(
                    mapOf("loomsWorking" to JsonPrimitive(12), "dyeSource" to JsonPrimitive("Indigo vat"))
                ),
                definition(dyeBath, loomAudit),
            ).blocks
        )
        assertTrue(text.indexOf("Loom audit") < text.indexOf("Dye bath log"))
    }

    @Test
    fun `a custom money, date and measurement print exactly as the registry's own do`() {
        val ledger = DwCustomSectionDto(
            id = "sec4", key = "ledger", stageKey = stage.key, title = "Loom ledger",
            fields = listOf(
                DwCustomFieldDto(id = "l1", key = "loomCost", label = "Loom cost", type = "MONEY"),
                DwCustomFieldDto(id = "l2", key = "boughtOn", label = "Bought on", type = "DATE", sortOrder = 1),
                DwCustomFieldDto(
                    id = "l3", key = "reedWidth", label = "Reed width", type = "DECIMAL",
                    unit = "cm", sortOrder = 2,
                ),
            ),
        )
        val text = textOf(
            document(
                draftWith(
                    mapOf(
                        "loomCost" to JsonPrimitive("120000.00"),
                        "boughtOn" to JsonPrimitive("2026-02-10"),
                        "reedWidth" to JsonPrimitive(12.5),
                    )
                ),
                definition(ledger),
            ).blocks
        )
        assertTrue("Indian grouping and two decimals: $text", text.contains("₹ 1,20,000.00"))
        assertTrue("the printed date, not the stored one: $text", text.contains("10 Feb 2026"))
        assertTrue("a measurement is unreadable without its unit: $text", text.contains("12.5 cm"))
    }

    @Test
    fun `a workshop the server says has no custom sections prints exactly as before`() {
        val draft = draftWith(emptyMap())
        val none = DwCustomCache(workshopId = "w1", complete = true)
        assertEquals(
            textOf(document(draft, null).blocks),
            textOf(document(draft, none).blocks),
        )
        assertTrue(dwCustomSectionWarnings(DwCustomCopy.NONE_DEFINED).isEmpty())
    }
}
