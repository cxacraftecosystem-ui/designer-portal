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

/**
 * THE THING THIS WHOLE LANE EXISTS FOR: a designer's own questions, in the .docx the HANDSET writes.
 *
 * Before this, `grep -r customSection android/app/src/main/java/` returned nothing, so a workshop
 * whose designer had added "how many looms are in working order" to stage 5 exported a field copy
 * with no such question and no such answer in it — and with nothing anywhere in the file or on the
 * screen saying a question had been left out. The office's copy carried it. Two documents, one
 * officer's desk.
 *
 * The choice made here was to RENDER rather than to add an `UNSUPPORTED_SECTIONS` apology, and the
 * argument is in these tests: the answers are already on the device (they are in `StageDraft.custom`,
 * which the sync writes and reads), the definition is already on the device
 * (`DwCustomSectionStore`), and one section maps onto a synthetic SINGLETON entity that
 * `renderEntity` prints unchanged — so the gap was never DATA, which is the condition the transcript
 * and AI-layer entries in that map are justified by. An apology would have been a choice to leave
 * evidence out of a document while holding it.
 */
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

    /** A collections-only stage — eight of the twenty-two are shaped like this. */
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

    /**
     * The plan the export screen resolves, with the SPLICE in it.
     *
     * `applyReportSettings` and not a hand-built section list, because where a designer's block
     * prints is a template decision on this surface exactly as it is on the server — see
     * [com.designprototype.workshop.report.SpecialSection.CUSTOM_SECTION]. A test that hand-placed a
     * `CUSTOM_SECTION` would be asserting the renderer while leaving the arbiter unexercised, which
     * is precisely the hole `report_templates_pin.json` still has.
     */
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

    /**
     * Every word the document actually carries, flattened.
     *
     * `KeyValueBlock` is the one that matters here and the one it is easy to leave out: an ordinary
     * scalar answer — a registry field's and a custom field's alike — is a label/value PAIR and not a
     * paragraph, so a reader that walked only the paragraphs would report an empty document over a
     * perfectly good one and every assertion below would be vacuously true.
     */
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

    // ── The lane's whole point ──────────────────────────────────────────────────────────────────

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
        // The ENUM's LABEL, resolved through the same lookup a registry enum goes through — the
        // stored token is `COTTON` and an officer must read "Cotton".
        assertTrue("the enum label rather than the token", text.contains("Cotton"))
        // AND THE UNIT, WHICH THIS TEST USED TO ASSERT THE ABSENCE OF. The comment that stood here
        // said `renderEntity` carries `field.unit` on a METRIC role only, so a KEY_VALUE row prints
        // the label and the value, and "teaching this one path to print units would make a
        // designer's own question look unlike every question beside it". The first half was an
        // accurate description of a defect and the second half is still the right rule — which is
        // why the fix was made in `displayValue`, where BOTH a registry answer and a custom one go
        // through it. `report_custom_sections.display_value` hands everything but its own ENUM lists
        // to `format_value`, and `format_value`'s INT arm ends
        // `f"{text} {spec.unit}".strip() if spec.unit else text`, so the office's copy has always
        // said "12 looms" here and the handset said "12".
        assertTrue("the unit, as the office's copy prints it: $text", text.contains("12 looms"))
    }

    /**
     * THE SECTION DESCRIPTION IS THE SECTION'S OPENING SENTENCE, NOT A NOTE ABOUT THE DOCUMENT.
     *
     * `append_custom_section` writes `doc.para(item.description, style=ParaStyle.LEAD)`, which is the
     * same style `renderStageSection` gives a stage's own `section.intro` — and it is LEAD on both
     * writers, so the difference is a visible one: NOTE is smaller and greyed. This surface carried
     * NOTE, inherited verbatim from the inline version that this block replaced, so a designer's own
     * description printed at a different weight on the handset than at the office while the KDoc
     * declared the drawing a port of `append_custom_section`.
     *
     * NOTE is this file's register for a statement ABOUT the document — "Not recorded.", the
     * retired-wording marker, the recording-place provenance line. A designer's sentence is not that.
     */
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

    /**
     * WITH NO DEFINITION HELD THE DOCUMENT IS UNCHANGED — which is what makes the warning honest.
     *
     * If this printed something for a device that has never read the definition, the export screen's
     * conditional sentence would be a lie in the one state it exists for.
     */
    @Test
    fun `a device holding no definition prints exactly what it always did`() {
        val draft = draftWith(mapOf("loomsWorking" to JsonPrimitive(12)))
        val without = textOf(document(draft, null).blocks)
        assertFalse(without.contains("Loom audit"))
        assertTrue("the stage's own answers still print", without.contains("Slow warping"))
        // …and the export screen says so, naming the one action that closes the gap.
        val said = dwCustomSectionWarnings(DwCustomCopy.UNKNOWN, answersHeld = true).single()
        assertTrue(said.contains("connection"))
    }

    /**
     * A STAGE WHOSE ONLY CONTENT IS CUSTOM STILL PRINTS THE DESIGNER'S QUESTIONS.
     *
     * Eight of the twenty-two stages declare no singleton at all, so this is the stage a designer
     * extends precisely BECAUSE the standard form asks nothing there. It used to be the case that
     * `omitIfEmpty` had to count custom answers, because the questions were drawn inside the stage
     * section and skipping the stage skipped them with it. They are their own spliced section now,
     * so the STAGE section is legitimately dropped — `_render_stage`'s `has_any` is singleton plus
     * collections on the server too — and the designer's block prints under its own heading anyway.
     * What must never come back is the file being short.
     */
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
        // AND THE STAGE SECTION REALLY IS DROPPED, which is the other half of the claim above and was
        // asserted in neither direction. `hasCustom` was deliberately removed from `omitIfEmpty` in
        // the same edit that gave custom sections their own spliced section: with no singleton and no
        // rows, `_render_stage`'s `has_any` is false at the office too, so a "Sketch development"
        // heading here would be the handset inventing a section the server does not print. Without
        // this line the removal is unpinned and a future reader reading the message above is sent
        // into `renderStageSection`, which is the one function no longer involved.
        assertFalse("the stage's own section must not print: $text", text.contains("Sketch development"))
    }

    /**
     * A SECTION WITH NOTHING ANSWERED UNDER IT PRINTS NO HEADING.
     *
     * A heading with nothing under it is the commonest way a generated report looks broken, and it is
     * the reason `renderStageSection` has a `showEmptyNote` at all.
     */
    @Test
    fun `an untouched custom section draws no heading`() {
        val text = textOf(document(draftWith(emptyMap()), definition(loomAudit)).blocks)
        assertFalse("no empty heading: $text", text.contains("Loom audit"))
        assertTrue(text.contains("Slow warping"))
    }

    /**
     * AN ANSWER GIVEN UNDER A WORDING NOBODY ASKS ANY MORE IS STILL PRINTED, AND IS NAMED AS SUCH.
     *
     * A section is retired precisely because it was answered — an answered question is superseded,
     * never deleted — so what is dropped here is evidence recorded under a form of words that appears
     * nowhere else. This is the "how many looms" → "how many weavers" failure, arrived at from the
     * report side: hiding it is how the phone's copy and the office's come to disagree about the
     * fieldwork with nothing in either saying so.
     */
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

    /**
     * A TYPE THIS BUILD CANNOT DRAW IS NOT INVENTED INTO THE DOCUMENT EITHER.
     *
     * `customSectionEntity` carries only the drawable fields, so a v1.1 answer this build cannot
     * interpret is left out of the table rather than being flattened by `DwValues.text` into
     * something that looks like a real answer. The FORM says so to the designer's face
     * ([dwCustomUnsupportedNote]); the report simply does not assert a value it cannot read.
     */
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
        // …but the question is NAMED, because a question silently absent from the field copy and
        // present in the office's is the exact divergence this lane exists to end.
        assertTrue("the question is named: $text", text.contains("Signed by"))
        assertTrue("and the type is named, so it can be reported", text.contains("SIGNATURE"))
    }

    /**
     * THE ORDER: the stage section, then the designer's own section after it — `apply_report_settings`
     * inserting the block at `anchor + 1`.
     *
     * This assertion is unchanged in words and changed in meaning. It used to hold because the
     * questions were drawn between the stage's singleton and its collections; it now holds because
     * the whole CUSTOM_SECTION follows the whole stage section, which is where the server puts it.
     */
    @Test
    fun `custom questions print after the stage's own fields`() {
        val text = textOf(
            document(draftWith(mapOf("loomsWorking" to JsonPrimitive(12))), definition(loomAudit)).blocks
        )
        assertTrue(text.indexOf("Slow warping") < text.indexOf("Loom audit"))
    }

    // ── WHERE the block prints, which is `applyReportSettings`' half ────────────────────────────

    /**
     * THE HEADING IS LEVEL 1, as `append_custom_section`'s `doc.heading(…, 1, …)` draws it.
     *
     * It was level 2, nested inside the stage section. That is not a cosmetic difference: a level-1
     * heading is numbered as its own top-level section and takes a contents-page entry, so the
     * office's copy and the handset's disagreed about every section number and every page number
     * from the first custom section onwards. A reader holding both would say the document had been
     * rearranged.
     */
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

    /**
     * ── THE ONE THIS LANE WAS REOPENED FOR ────────────────────────────────────────────────────────
     *
     * A TEMPLATE THAT DOES NOT PRINT THE ANCHOR STAGE MUST STILL PRINT THE DESIGNER'S QUESTIONS,
     * as a back annexure on its own page, before the completeness section.
     *
     * PHOTO_CATALOGUE carries three of the twenty-two stages. Export it, and every question the
     * designer added in the field — the whole reason the feature exists — was absent from the
     * handset's .docx, with nothing in the file and nothing on the export screen saying so, because
     * `dwCustomSectionWarnings` warns for UNKNOWN only and this copy was DEFINED. The office's copy
     * printed them. This is the case with no code path at all before the splice: the stage loop is
     * `stages[section.stageKey]?.let { renderStageSection(…) }`, and the custom blocks were resolved
     * inside that call.
     */
    @Test
    fun `a section whose stage the template never prints becomes a back annexure`() {
        val blocks = document(
            draftWith(mapOf("loomsWorking" to JsonPrimitive(12))),
            definition(loomAudit),
            // A template carrying only the SKETCH stage — the shape PHOTO_CATALOGUE has for
            // nineteen of the twenty-two.
            sections = listOf(
                TemplateSection(stageKey = sketches.key),
                TemplateSection(special = SpecialSection.COMPLETENESS),
            ),
        ).blocks
        val text = textOf(blocks)
        // THE HEADING AND THE ANSWER, NOT MERELY THE WORDS. The completeness annexure in the same
        // document names outstanding questions, so a bare `contains("Loom audit")` could pass on a
        // file that still omitted the block entirely — which is the failure under test.
        assertTrue(
            "the designer's section has its own heading: $text",
            blocks.filterIsInstance<HeadingBlock>().any { runText(it.runs).contains("Loom audit") },
        )
        assertTrue("and the answer under it: $text", text.contains("Looms in working order: 12 looms"))
    }

    /** …and it lands BEFORE the completeness annexure, which is a statement about the document. */
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

    /**
     * A STAGE THE DESIGNER EXCLUDED IN STAGE 20 TAKES THE SAME ROUTE, and the splice runs AFTER the
     * exclusion filter for that reason: inserted before it, the block would sit behind a stage
     * section that is then removed from under it, stranded in the middle of somebody else's
     * narrative with no heading above it to say what stage it belongs to.
     */
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

    /**
     * TWO SECTIONS ON ONE STAGE KEEP THE DESIGNER'S OWN ORDER.
     *
     * The server's comment records what the naive insert did: placing every block at `anchor + 1`
     * REVERSED them, so two sections on stage 13 came out 2, 1 — and it reads as the sort order not
     * working at all rather than as an insertion bug.
     *
     * IT IS NOT A REGRESSION TEST FOR THE SPLICE, and must not be counted as one. It passes with the
     * splice fully reverted: the inline renderer resolved blocks through `customStageBlocks`, which
     * already sorts by `(sortOrder, key)`, so "Loom audit" preceded "Dye bath log" before this
     * placement change as well. What it pins is the FUTURE — a naive `anchor + 1` rewrite of
     * `applyReportSettings`' walk-past — which is worth pinning because the failure looks like a
     * broken sort rather than a broken insert, and the first thing anyone would then "fix" is
     * `customStageBlocks`, which is correct.
     */
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
                // Handed to the cache in the WRONG order on purpose: the splice sorts, the payload
                // does not have to.
                definition(dyeBath, loomAudit),
            ).blocks
        )
        assertTrue(text.indexOf("Loom audit") < text.indexOf("Dye bath log"))
    }

    /**
     * A DESIGNER'S OWN MONEY, DATE AND MEASUREMENT PRINT AS THE OFFICE PRINTS THEM.
     *
     * `report_custom_sections.display_value` decides only ENUM and MULTI_ENUM for itself and hands
     * everything else to `report_builder.format_value`, so a custom question has always been
     * formatted by the same function a registry field is. On this side that function is
     * `displayValue`, and `customFieldToFieldDto` already carried `unit` into the [FieldDto] — so the
     * moment the registry side gained the DATE, MONEY and unit arms, the designer's own side gained
     * them too. This test is here to make sure a future edit cannot split them again.
     */
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
                        // MONEY is stored as a fixed-2 STRING — see `DwValues.coerce`.
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

    /** A workshop with no custom sections at all — which is most of them — is byte-identical. */
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
