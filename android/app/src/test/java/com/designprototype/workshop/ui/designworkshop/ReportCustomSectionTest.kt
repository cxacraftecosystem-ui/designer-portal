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
import com.designprototype.workshop.data.dwCustomSectionWarnings
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportRecord
import com.designprototype.workshop.report.ReportTheme
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TemplateSection
import com.designprototype.workshop.report.reportMetaFor
import com.designprototype.workshop.report.reportTemplate
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
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

    private fun draftWith(custom: Map<String, kotlinx.serialization.json.JsonElement>) = WorkshopDraft(
        workshopId = "w1",
        stages = mapOf(
            stage.key to StageDraft(
                stageId = stage.key, title = stage.title, order = 5,
                values = mapOf("currentProblems" to JsonPrimitive("Slow warping")),
                custom = custom,
            ),
        ),
    )

    private fun planFor(vararg sections: TemplateSection): ReportPlan = ReportPlan(
        template = reportTemplate("DCH_STANDARD").copy(
            sections = sections.toList(), numberHeadings = false, showEmptyNote = false,
        ),
        meta = reportMetaFor(ReportRecord(id = "w1", title = "T"), "DCH_STANDARD", null, ""),
        theme = ReportTheme(),
        settings = emptyMap(),
        warnings = emptyList(),
    )

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
    ) = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = "w1",
        templateId = "DCH_STANDARD",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        plan = planFor(TemplateSection(stageKey = stageKey)),
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
        // NOT the unit, and that is the app's existing behaviour rather than a gap in this lane:
        // `renderEntity` carries `field.unit` on a METRIC role only, and a KEY_VALUE row prints the
        // label and the value. A custom answer therefore prints exactly as a registry answer of the
        // same role does, which is the whole point of going through one renderer — teaching this one
        // path to print units would make a designer's own question look unlike every question beside
        // it.
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
     * A STAGE WHOSE ONLY CONTENT IS CUSTOM MUST NOT BE OMITTED.
     *
     * `omitIfEmpty` tested `singleton.isEmpty() && !hasCollections`, and eight of the twenty-two
     * stages declare no singleton at all — so the stage a designer extended precisely BECAUSE the
     * standard form asks nothing there would have been dropped from the file entirely, heading and
     * all, with nothing to say a section had been skipped.
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
        assertTrue("the stage survived omitIfEmpty: $text", text.contains("Sketch audit"))
        assertTrue(text.contains("Sheets used"))
        assertTrue(text.contains("9"))
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
     * THE ORDER: the stage's own fields, then the designer's, then the repeating rows — the scorer's
     * order, so the document and the Outstanding column beside it read the same way round.
     */
    @Test
    fun `custom questions print after the stage's own fields`() {
        val text = textOf(
            document(draftWith(mapOf("loomsWorking" to JsonPrimitive(12))), definition(loomAudit)).blocks
        )
        assertTrue(text.indexOf("Slow warping") < text.indexOf("Loom audit"))
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
