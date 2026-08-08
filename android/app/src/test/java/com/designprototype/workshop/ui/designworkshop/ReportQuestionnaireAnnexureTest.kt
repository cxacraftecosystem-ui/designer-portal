package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.CustomAnswerDto
import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionDto
import com.designprototype.workshop.data.CustomQuestionnaireDto
import com.designprototype.workshop.data.CustomSectionDto
import com.designprototype.workshop.data.DwQuestionnaireAnswer
import com.designprototype.workshop.data.DwQuestionnaireCache
import com.designprototype.workshop.data.DwQuestionnaireCopy
import com.designprototype.workshop.data.DwQuestionnaireItem
import com.designprototype.workshop.data.DwQuestionnaireSitting
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.dwQuestionnaireCopy
import com.designprototype.workshop.data.dwQuestionnaireItemOf
import com.designprototype.workshop.data.dwQuestionnaireWarnings
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.DocumentBuilder
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.PageBreakBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.QUESTIONNAIRE_ANNEXURE
import com.designprototype.workshop.report.ReportRecord
import com.designprototype.workshop.report.ReportTheme
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.SpecialSection
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TemplateSection
import com.designprototype.workshop.report.reportMetaFor
import com.designprototype.workshop.report.reportTemplate
import com.designprototype.workshop.report.reportWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The questionnaire annexure the HANDSET writes, pinned against the one the office writes.
 *
 * ── WHAT THIS EXISTS FOR ──────────────────────────────────────────────────────────────────────────
 *
 * The report handed to a visiting ministry officer at the close of a workshop is the copy this phone
 * produced. Until this lane it carried no questionnaire answers at all and said so, and the reason it
 * gave was true: nothing under `data/` held one. Now it holds them, and the whole risk moves to a
 * different place — TWO renderings of one appendix of evidence, one in Python and one in Kotlin,
 * drifting apart where nobody is comparing them.
 *
 * ── THE EXPECTED STRINGS ARE THE SERVER'S OWN OUTPUT, NOT A TRANSCRIPTION ─────────────────────────
 *
 * Every literal below was produced by running the fixture in this file through
 * `backend/app/services/report_questionnaires.py` in the project's own venv and printing the result —
 * the discipline `DwOverflowParityTest` states: the correct behaviour is not "renders something", it
 * is "prints what the server prints", because the designer's copy and the ministry's copy have to be
 * the same document. The separators are `·` U+00B7 and `—` U+2014 and the quotes in the lead
 * paragraph are curly, exactly as Python emits them; a straight quote here would be a silent
 * divergence on every report.
 *
 * To regenerate after a deliberate change on the server, build the same three dataclasses and print
 * `questionnaire_annexure_blocks`, `_questionnaire_provenance` and `_sitting_provenance`.
 */
class ReportQuestionnaireAnnexureTest {

    // ── the fixture, identical on both sides ─────────────────────────────────────────────────────

    private val answers = listOf(
        DwQuestionnaireAnswer(
            prompt = "How many looms?", answerText = "12",
            notes = "counted with the master weaver",
            sectionCode = "A", sectionTitle = "Household", isRequired = true,
        ),
        // Required and blank: prints, because the gap is itself the finding.
        DwQuestionnaireAnswer(
            prompt = "Who weaves?", sectionCode = "A", sectionTitle = "Household", isRequired = true,
        ),
        // Optional and blank: prints nothing, so a forty-question form is not forty rows.
        DwQuestionnaireAnswer(
            prompt = "Optional and unanswered", sectionCode = "A", sectionTitle = "Household",
        ),
        // Answered under a wording that was later reworded. The answer stays with the wording it was
        // given under — losing that is the "twelve looms becomes twelve weavers" failure.
        DwQuestionnaireAnswer(
            prompt = "Old wording of the dye question", answerText = "Indigo",
            sectionCode = "B", sectionTitle = "Dyeing", isRetired = true,
        ),
        DwQuestionnaireAnswer(
            prompt = "Which dyes?", answerText = "Indigo and madder",
            sectionCode = "B", sectionTitle = "Dyeing",
        ),
    )

    private val sitting = DwQuestionnaireSitting(
        entryId = "cmentry000123456789", title = "First sitting", respondentName = "Padma Meher",
        source = "APP", notes = "Recorded in the courtyard.",
        recordedAt = "2026-03-04T09:30:00+00:00", recordedBy = "A. Mohanty", answers = answers,
    )

    private fun blank(
        prompt: String, code: String, title: String,
        required: Boolean = false, retired: Boolean = false,
    ) = DwQuestionnaireAnswer(
        prompt = prompt, sectionCode = code, sectionTitle = title,
        isRequired = required, isRetired = retired,
    )

    /**
     * Created and never filled in. Left out of the annexure and raised as a warning instead.
     *
     * IT STILL CARRIES A ROW PER QUESTION, because the mapping builds every sitting against the same
     * ordered question list — that is what makes two sittings of one form comparable side by side.
     * What keeps it out of the document is `answeredCount == 0`, not an absence of rows.
     */
    private val emptySitting = DwQuestionnaireSitting(
        entryId = "cmempty00000000000", title = "Never filled in",
        answers = listOf(
            blank("How many looms?", "A", "Household", required = true),
            blank("Who weaves?", "A", "Household", required = true),
            blank("Optional and unanswered", "A", "Household"),
            blank("Old wording of the dye question", "B", "Dyeing", retired = true),
            blank("Which dyes?", "B", "Dyeing"),
        ),
    )

    private val item = DwQuestionnaireItem(
        questionnaireId = "cmq0001", title = "Barpali loom survey", description = "Household level.",
        version = 3, sourceFilename = "barpali.xlsx", questionCount = 4, answersHeld = true,
        sittings = listOf(sitting, emptySitting),
    )

    private val unanswered = DwQuestionnaireItem(
        questionnaireId = "cmq0002", title = "Dye sourcing", version = 1, questionCount = 2,
        answersHeld = true, sittings = listOf(emptySitting),
    )

    private fun cacheOf(vararg items: DwQuestionnaireItem, complete: Boolean = true) =
        DwQuestionnaireCache(
            workshopId = "w1", items = items.toList(), complete = complete,
            // Blank, so the annexure's one device-only freshness line is out of the way of the
            // shape comparisons below. It has its own test.
            fetchedAt = "",
        )

    // ── reading the blocks ───────────────────────────────────────────────────────────────────────

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun describe(block: Block): String = when (block) {
        is PageBreakBlock -> "PageBreakBlock"
        is HeadingBlock -> "H${block.level}|${block.number}|${runText(block.runs)}"
        is ParagraphBlock -> "P|${block.style.name}|${runText(block.runs)}"
        is TableBlock -> "T|" + block.columns.joinToString(",") { it.header } + "|" +
            block.rows.joinToString(" ;; ") { row -> row.joinToString(" || ") { runText(it) } }
        else -> block::class.java.simpleName
    }

    private fun annexureBlocks(cache: DwQuestionnaireCache?): List<Block> {
        val template = reportTemplate("DCH_STANDARD")
        val builder = DocumentBuilder(
            meta = reportMetaFor(ReportRecord(id = "w1", title = "T"), "DCH_STANDARD", null, ""),
            theme = ReportTheme(),
        )
        renderQuestionnaireAnnexure(
            builder = builder,
            section = QUESTIONNAIRE_ANNEXURE,
            plan = ReportPlan(
                template = template,
                meta = reportMetaFor(ReportRecord(id = "w1", title = "T"), "DCH_STANDARD", null, ""),
                theme = ReportTheme(),
                settings = emptyMap(),
                warnings = emptyList(),
            ),
            cache = cache,
        )
        return builder.build().blocks
    }

    // ── the shape, block for block ───────────────────────────────────────────────────────────────

    @Test
    fun `the annexure is the server's, block for block`() {
        assertEquals(
            listOf(
                "PageBreakBlock",
                "H1|1|Annexure — Questionnaire responses",
                "P|LEAD|The questionnaires this workshop's designer built and attached to it, and " +
                    "every sitting recorded against them. Each question is printed in the wording " +
                    "the answer was given under: a question reworded after it was answered is shown " +
                    "here as it was asked, marked “no longer asked”. A required question left blank " +
                    "is printed as “Not recorded.” so that a gap in the fieldwork is visible as a gap.",
                "T|Questionnaire,Questions,Sittings,Answers recorded|" +
                    "Barpali loom survey || 4 || 1 || 3",
                "H2|1.1|Barpali loom survey",
                "P|NOTE|The designer's own questionnaire, attached to this workshop · 1 sitting(s) " +
                    "· 4 question(s) · version 3 · from barpali.xlsx · questionnaire cmq0001",
                "P|BODY|Household level.",
                "H3|1.1.1|Padma Meher",
                "P|NOTE|3 question(s) answered · recorded in the app · 2026-03-04 · recorded by " +
                    "A. Mohanty",
                "P|BODY|Recorded in the courtyard.",
                "P|BODY|A — Household",
                "T|Question,Answer|" +
                    "How many looms? || 12  Note: counted with the master weaver ;; " +
                    "Who weaves? || Not recorded.",
                "P|BODY|B — Dyeing",
                "T|Question,Answer|" +
                    "Old wording of the dye question (no longer asked) || Indigo ;; " +
                    "Which dyes? || Indigo and madder",
            ),
            annexureBlocks(cacheOf(item)).map(::describe),
        )
    }

    @Test
    fun `the index table's columns are the server's, and they still sum to 100`() {
        val table = annexureBlocks(cacheOf(item)).filterIsInstance<TableBlock>().first()
        assertEquals(
            listOf("Questionnaire" to 46.0f, "Questions" to 16.0f, "Sittings" to 16.0f,
                "Answers recorded" to 22.0f),
            table.columns.map { it.header to it.widthPct },
        )
        // `numeric` IS PART OF THE SHAPE, not a rendering preference. Both writers right-align a
        // numeric column and group its digits, so a flag set on one side and not the other gives the
        // office and the field the same figures in visibly different columns — and this annexure
        // exists to be read side by side with the office's copy. Pinned because the widths above
        // would have gone on passing while the three counts drifted left.
        assertEquals(
            listOf(false, true, true, true),
            table.columns.map { it.numeric },
        )
        assertEquals(
            "Questionnaires attached to this workshop and the sittings recorded against them.",
            table.caption,
        )
    }

    /**
     * A sitting created and never filled in is not an empty heading in an appendix of evidence.
     *
     * `printed_sittings` drops it on the server and [DwQuestionnaireItem.printedSittings] drops it
     * here, which is also why the provenance line above reads "1 sitting(s)" for an item that holds
     * two.
     */
    @Test
    fun `an unfilled sitting is left out rather than printed as a blank heading`() {
        val headings = annexureBlocks(cacheOf(item)).filterIsInstance<HeadingBlock>()
            .map { runText(it.runs) }
        assertTrue(headings.contains("Padma Meher"))
        assertFalse("an empty sitting must not reach the document: $headings",
            headings.contains("Never filled in"))
    }

    // ── the three states, which are the point of the lane ────────────────────────────────────────

    /**
     * NEVER FETCHED. The device cannot tell an unattached workshop from an attached one, so it draws
     * nothing and the conditional warning stands — byte for byte the report this build produced
     * before the annexure existed.
     */
    @Test
    fun `a device that has never read the list prints nothing at all`() {
        assertEquals(emptyList<Block>(), annexureBlocks(null))
        assertEquals(DwQuestionnaireCopy.UNKNOWN, dwQuestionnaireCopy(null))
    }

    /**
     * NOTHING ATTACHED. The single commonest case, and the one that must stay silent: no heading, no
     * page break, no apology. `append_questionnaire_annexure` with an empty list appends nothing
     * either, so the two copies are still byte-identical here.
     */
    @Test
    fun `a workshop with no questionnaire attached prints nothing at all`() {
        assertEquals(emptyList<Block>(), annexureBlocks(cacheOf()))
        assertEquals(DwQuestionnaireCopy.NONE_ATTACHED, dwQuestionnaireCopy(cacheOf()))
    }

    /**
     * ATTACHED AND ANSWERED BY NOBODY. Printed by neither copy — the server drops it too — and
     * reported to the designer as a warning instead, because they chose that form for this workshop
     * on purpose and would otherwise have to notice the shortfall in a sixty-page document.
     */
    @Test
    fun `an attached questionnaire nobody answered prints nothing and warns`() {
        assertEquals(emptyList<Block>(), annexureBlocks(cacheOf(unanswered)))
        assertEquals(
            listOf(
                "1 questionnaire(s) attached to this workshop have no recorded answers and were " +
                    "left out of the questionnaire annexure (Dye sourcing)."
            ),
            dwQuestionnaireWarnings(cacheOf(unanswered)),
        )
    }

    /**
     * ATTACHED, AND THIS DEVICE HAS NO COPY OF THE ANSWERS.
     *
     * THIS IS THE CASE THE LANE EXISTS FOR. An empty annexure and a missing one are different
     * documents: printing a heading with nothing under it reads as "nobody answered", which is a
     * false statement about somebody's fieldwork, and printing nothing at all is the silent
     * divergence between the phone's copy and the office's that this whole area exists to end. The
     * questionnaire is NAMED and the file says the answers are not in it.
     */
    @Test
    fun `an attached questionnaire whose answers are missing is named rather than implied absent`() {
        val unread = DwQuestionnaireItem(
            questionnaireId = "cmq0003", title = "Loom census", answersHeld = false,
        )
        val text = annexureBlocks(cacheOf(unread)).joinToString("\n", transform = ::describe)
        assertTrue("the section must open at all: $text", text.contains("Annexure — Questionnaire"))
        assertTrue("the questionnaire must be named: $text", text.contains("Loom census"))
        assertTrue(
            "the file must say the answers are absent from THIS copy rather than absent: $text",
            text.contains("this device holds no copy of the answers"),
        )
        assertTrue(
            "and must point at the copy that does have them: $text",
            text.contains("the office's copy of this report carries them"),
        )
        // No index table and no per-questionnaire heading: there is nothing answered to index, and a
        // numbered heading over nothing is what makes a report look corrupt.
        assertTrue("nothing may be indexed here: $text", !text.contains("Answers recorded"))
    }

    @Test
    fun `a questionnaire whose answers are missing is also raised at the export screen`() {
        val unread = DwQuestionnaireItem(questionnaireId = "cmq0003", title = "Loom census")
        val said = dwQuestionnaireWarnings(cacheOf(unread))
        assertEquals(1, said.size)
        assertTrue("it must name the form: ${said[0]}", said[0].contains("Loom census"))
        assertTrue(
            "and it must be actionable — the designer is usually the only person who will be near " +
                "this workshop with signal again: ${said[0]}",
            said[0].contains("once while you have a connection"),
        )
    }

    /**
     * A copy assembled from single-questionnaire reads cannot claim to be the whole list, and the
     * document says so rather than implying completeness it has not established.
     */
    @Test
    fun `an incomplete list admits it is incomplete`() {
        val text = annexureBlocks(cacheOf(item, complete = false))
            .joinToString("\n", transform = ::describe)
        assertTrue("the annexure still prints: $text", text.contains("Padma Meher"))
        assertTrue(
            "and it must not imply it has the whole set: $text",
            text.contains("has not read the full list of questionnaires"),
        )
        // A complete one says nothing of the kind — the note must not become noise on every report.
        assertFalse(
            annexureBlocks(cacheOf(item)).joinToString("\n", transform = ::describe)
                .contains("has not read the full list"),
        )
    }

    /**
     * The one line this copy carries that the office's does not, and the reason it is worth the
     * divergence: a report built from a fortnight-old copy of the answers is still worth having, and
     * what makes it safe to hand over is that it says how old it is.
     */
    @Test
    fun `the file dates the copy of the answers it was built from`() {
        val dated = cacheOf(item).copy(fetchedAt = "2026-03-01T06:12:44Z")
        val text = annexureBlocks(dated).joinToString("\n", transform = ::describe)
        assertTrue("the date must be in the file: $text", text.contains("2026-03-01"))
        assertTrue(
            "and must say what a later sitting means for this copy: $text",
            text.contains("is in the office's copy of this report and not in this one"),
        )
    }

    // ── the wire shape becomes the report shape ──────────────────────────────────────────────────

    private fun question(
        id: String, prompt: String, required: Boolean = false, active: Boolean = true,
    ) = CustomQuestionDto(id = id, prompt = prompt, isRequired = required, isActive = active)

    private val wire = CustomQuestionnaireDto(
        id = "cmq0001", title = "Barpali loom survey", description = "Household level.",
        designWorkshopId = "w1", version = 3, sourceFilename = "barpali.xlsx",
        // What `GET /questionnaires/{id}?includeRetired=true` reports: FOUR active plus the retired
        // one. The report must not print this number — see the assertion below.
        questionCount = 5,
        sections = listOf(
            CustomSectionDto(
                id = "s1", code = "A", title = "Household",
                questions = listOf(
                    question("q1", "How many looms?", required = true),
                    question("q2", "Who weaves?", required = true),
                    question("q3", "Optional and unanswered"),
                ),
            ),
            CustomSectionDto(
                id = "s2", code = "B", title = "Dyeing",
                questions = listOf(
                    question("q4", "Old wording of the dye question", active = false),
                    question("q5", "Which dyes?"),
                ),
            ),
        ),
        entries = listOf(
            CustomEntryDto(
                id = "cmentry000123456789", title = "First sitting", respondentName = "Padma Meher",
                source = "APP", notes = "Recorded in the courtyard.",
                createdAt = "2026-03-04T09:30:00+00:00", createdByName = "A. Mohanty",
                answers = listOf(
                    CustomAnswerDto(questionId = "q1", answerText = "12",
                        notes = "counted with the master weaver"),
                    CustomAnswerDto(questionId = "q4", answerText = "Indigo"),
                    CustomAnswerDto(questionId = "q5", answerText = "Indigo and madder"),
                ),
            ),
            CustomEntryDto(id = "cmempty00000000000", title = "Never filled in"),
        ),
    )

    /**
     * The mapping produces exactly the fixture the parity assertions above are written against, so
     * the port is pinned end to end: wire shape in, the server's document out.
     */
    @Test
    fun `one questionnaire payload becomes the item the annexure prints`() {
        assertEquals(item, dwQuestionnaireItemOf(wire))
    }

    /**
     * `questionCount` IS RECOMPUTED AND NOT COPIED, and the two numbers genuinely differ.
     *
     * `load_form` counts every question in the payload it built, and this client asks for the payload
     * WITH retired questions in it; `report_items` counts `q.isActive` only. A reader compares this
     * figure against the instrument in their hand and the instrument no longer asks the retired one,
     * so copying the wire value would print "5 question(s)" over a four-question form.
     */
    @Test
    fun `the printed question count excludes retired questions even though the payload counts them`() {
        assertEquals(5, wire.questionCount)
        assertEquals(4, dwQuestionnaireItemOf(wire).questionCount)
    }

    /**
     * WALKED SECTION BY SECTION. `QuestionnaireFormQuestion.sortOrder` is scoped to its section, and
     * the server's own comment records what a flat ordering did to the document: one single-row table
     * per question with the label repeated above each, A/B/A/B down the page, in the appendix of
     * evidence handed to a ministry officer.
     */
    @Test
    fun `answers come out in section order and not interleaved`() {
        val labels = dwQuestionnaireItemOf(wire).sittings.first().printedAnswers.map { it.sectionLabel }
        assertEquals(listOf("A — Household", "A — Household", "B — Dyeing", "B — Dyeing"), labels)
    }

    /** A question retired after it was answered keeps its own wording and is marked as such. */
    @Test
    fun `a superseded question keeps the wording its answer was given under`() {
        val retired = dwQuestionnaireItemOf(wire).sittings.first().answers.single { it.isRetired }
        assertEquals("Old wording of the dye question", retired.prompt)
        assertEquals("Indigo", retired.answerText)
    }

    // ── what the designer is told, and when ──────────────────────────────────────────────────────

    /**
     * The warning is GUARDED by what the device holds, exactly as the transcript warning is guarded
     * by the designer's own `includeTranscripts` answer.
     *
     * Both guards exist for one reason: a warning that is true in general and false in the case in
     * front of the designer is how a designer learns to stop reading warnings.
     */
    @Test
    fun `the unsupported-section warning stands only while the device has nothing`() {
        val template = reportTemplate("DCH_STANDARD")
        val never = reportWarnings("DCH_STANDARD", template, emptyMap(), "DOCX")
        assertTrue(
            "a handset that has never read the list still cannot print the annexure and must say " +
                "so: $never",
            never.any { it.contains("If a questionnaire is attached") },
        )
        // And the reason it gives must be the one that is still true. "This device keeps no copy of
        // them" was the root cause and is now false; leaving it would send a designer to wait for the
        // office over a gap they can close themselves with a bar of signal.
        assertTrue(
            "the warning must not still claim the device cannot hold them: $never",
            never.none { it.contains("This device keeps no copy") },
        )

        val none = reportWarnings(
            "DCH_STANDARD", template, emptyMap(), "DOCX", DwQuestionnaireCopy.NONE_ATTACHED,
        )
        assertTrue(
            "a workshop with nothing attached loses no section, so warning is a false alarm: $none",
            none.none { it.contains("questionnaire") },
        )
        val held = reportWarnings(
            "DCH_STANDARD", template, emptyMap(), "DOCX", DwQuestionnaireCopy.ATTACHED,
        )
        assertTrue(
            "and once the answers are here the annexure prints, so the disclaimer is a lie: $held",
            held.none { it.contains("If a questionnaire is attached") },
        )
    }

    /**
     * The other half of `ReportTemplateDocumentTest`'s "either rendered or named as unsupported"
     * invariant, from the side that test cannot reach: with a copy of the answers on the device the
     * section DOES draw, so the pairing is complete rather than permanently resolved by the warning.
     */
    @Test
    fun `the section is genuinely drawn once the device holds the answers`() {
        val template = reportTemplate("DCH_STANDARD").copy(
            sections = listOf(TemplateSection(special = SpecialSection.ANNEXURE_QUESTIONNAIRES)),
        )
        val plan = ReportPlan(
            template = template,
            meta = reportMetaFor(ReportRecord(id = "w1", title = "T"), "DCH_STANDARD", null, ""),
            theme = ReportTheme(),
            settings = emptyMap(),
            warnings = emptyList(),
        )
        val document = buildWorkshopDocument(
            schema = SchemaResponse(version = "test", stages = emptyList()),
            draft = null,
            workshopId = "w1",
            templateId = "DCH_STANDARD",
            warnings = emptyList(),
            accent = "",
            imageFor = { null },
            plan = plan,
            questionnaires = cacheOf(item),
        )
        assertTrue(
            "the whole point of the lane: the answers reach the report the handset writes",
            document.blocks.filterIsInstance<TableBlock>().any { table ->
                table.rows.any { row -> row.any { runText(it).contains("Indigo and madder") } }
            },
        )
        // And with no copy on the device the same call draws nothing, which is what keeps the
        // warning honest for the handset that has never had signal on this workshop.
        val nothing = buildWorkshopDocument(
            schema = SchemaResponse(version = "test", stages = emptyList()),
            draft = null, workshopId = "w1", templateId = "DCH_STANDARD",
            warnings = emptyList(), accent = "", imageFor = { null }, plan = plan,
        )
        assertEquals(emptyList<Block>(), nothing.blocks)
    }
}
