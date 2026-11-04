package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.QFeature
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

class ReportQuestionnaireAnnexureTest {

    private val answers = listOf(
        DwQuestionnaireAnswer(
            prompt = "How many looms?", answerText = "12",
            notes = "counted with the master weaver",
            sectionCode = "A", sectionTitle = "Household", isRequired = true,
        ),
        DwQuestionnaireAnswer(
            prompt = "Who weaves?", sectionCode = "A", sectionTitle = "Household", isRequired = true,
        ),
        DwQuestionnaireAnswer(
            prompt = "Optional and unanswered", sectionCode = "A", sectionTitle = "Household",
        ),
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
            fetchedAt = "",
        )

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
                "T|Questionnaire,Kind,Questions,Sittings,Answers recorded|" +
                    "Barpali loom survey || Kind not stated || 4 || 1 || 3",
                "H2|1.1|Barpali loom survey",
                "P|NOTE|The designer's own questionnaire, attached to this workshop · 1 sitting " +
                    "· 4 questions · version 3 · from barpali.xlsx · questionnaire cmq0001",
                "P|BODY|Household level.",
                "H3|1.1.1|Padma Meher",
                "P|NOTE|3 questions answered · recorded in the app · 2026-03-04 · recorded by " +
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
            listOf("Questionnaire" to 36.0f, "Kind" to 18.0f, "Questions" to 14.0f, "Sittings" to 14.0f,
                "Answers recorded" to 18.0f),
            table.columns.map { it.header to it.widthPct },
        )
        assertEquals(
            listOf(false, false, true, true, true),
            table.columns.map { it.numeric },
        )
        assertEquals(
            "Questionnaires attached to this workshop and the sittings recorded against them.",
            table.caption,
        )
    }

    @Test
    fun `an unfilled sitting is left out rather than printed as a blank heading`() {
        val headings = annexureBlocks(cacheOf(item)).filterIsInstance<HeadingBlock>()
            .map { runText(it.runs) }
        assertTrue(headings.contains("Padma Meher"))
        assertFalse("an empty sitting must not reach the document: $headings",
            headings.contains("Never filled in"))
    }

    @Test
    fun `a device that has never read the list prints nothing at all`() {
        assertEquals(emptyList<Block>(), annexureBlocks(null))
        assertEquals(DwQuestionnaireCopy.UNKNOWN, dwQuestionnaireCopy(null))
    }

    @Test
    fun `a workshop with no questionnaire attached prints nothing at all`() {
        assertEquals(emptyList<Block>(), annexureBlocks(cacheOf()))
        assertEquals(DwQuestionnaireCopy.NONE_ATTACHED, dwQuestionnaireCopy(cacheOf()))
    }

    @Test
    fun `an attached questionnaire nobody answered prints nothing and warns`() {
        assertEquals(emptyList<Block>(), annexureBlocks(cacheOf(unanswered)))
        assertEquals(
            listOf(
                "1 questionnaire attached to this workshop has no recorded answers and was " +
                    "left out of the questionnaire annexure (Dye sourcing)."
            ),
            dwQuestionnaireWarnings(cacheOf(unanswered)),
        )
    }

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
            text.contains("not printed below"),
        )
        assertTrue(
            "and must point at the record that does have them: $text",
            text.contains("Their answers are in the online workshop record"),
        )
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

    @Test
    fun `an incomplete list admits it is incomplete`() {
        val text = annexureBlocks(cacheOf(item, complete = false))
            .joinToString("\n", transform = ::describe)
        assertTrue("the annexure still prints: $text", text.contains("Padma Meher"))
        assertTrue(
            "and it must not imply it has the whole set: $text",
            text.contains("Other questionnaires may also be attached to this workshop"),
        )
        assertFalse(
            annexureBlocks(cacheOf(item)).joinToString("\n", transform = ::describe)
                .contains("may also be attached"),
        )
    }

    @Test
    fun `the file dates the copy of the answers it was built from`() {
        val dated = cacheOf(item).copy(fetchedAt = "2026-03-01T06:12:44Z")
        val text = annexureBlocks(dated).joinToString("\n", transform = ::describe)
        assertTrue("the date must be in the file: $text", text.contains("2026-03-01"))
        assertTrue(
            "and must say what a later sitting means for this copy: $text",
            text.contains("recorded after that date is in the online workshop record"),
        )
    }

    private fun question(
        id: String, prompt: String, required: Boolean = false, active: Boolean = true,
    ) = CustomQuestionDto(id = id, prompt = prompt, isRequired = required, isActive = active)

    private val wire = CustomQuestionnaireDto(
        id = "cmq0001", title = "Barpali loom survey", description = "Household level.",
        designWorkshopId = "w1", version = 3, sourceFilename = "barpali.xlsx",
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

    @Test
    fun `one questionnaire payload becomes the item the annexure prints`() {
        assertEquals(item, dwQuestionnaireItemOf(wire))
    }

    @Test
    fun `the printed question count excludes retired questions even though the payload counts them`() {
        assertEquals(5, wire.questionCount)
        assertEquals(4, dwQuestionnaireItemOf(wire).questionCount)
    }

    @Test
    fun `answers come out in section order and not interleaved`() {
        val labels = dwQuestionnaireItemOf(wire).sittings.first().printedAnswers.map { it.sectionLabel }
        assertEquals(listOf("A — Household", "A — Household", "B — Dyeing", "B — Dyeing"), labels)
    }

    @Test
    fun `a superseded question keeps the wording its answer was given under`() {
        val retired = dwQuestionnaireItemOf(wire).sittings.first().answers.single { it.isRetired }
        assertEquals("Old wording of the dye question", retired.prompt)
        assertEquals("Indigo", retired.answerText)
    }

    @Test
    fun `the unsupported-section warning stands only while the device has nothing`() {
        val template = reportTemplate("DCH_STANDARD")
        val never = reportWarnings("DCH_STANDARD", template, emptyMap(), "DOCX")
        assertTrue(
            "a handset that has never read the list still cannot print the annexure and must say " +
                "so: $never",
            never.any { it.contains("If a questionnaire is attached") },
        )
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
        val nothing = buildWorkshopDocument(
            schema = SchemaResponse(version = "test", stages = emptyList()),
            draft = null, workshopId = "w1", templateId = "DCH_STANDARD",
            warnings = emptyList(), accent = "", imageFor = { null }, plan = plan,
        )
        assertEquals(emptyList<Block>(), nothing.blocks)
    }

    @QFeature("A12")
    @Test
    fun `stated kinds group under their stage, in stage order, and unstated ones keep their shape`() {
        val stages = listOf(
            com.designprototype.workshop.data.StageDto(number = 6, key = "EXISTING_PRODUCTS_BASELINE", title = "Existing Products Baseline"),
            com.designprototype.workshop.data.StageDto(number = 8, key = "MARKET_SURVEY_CAPTURE", title = "Market Survey & Field Data"),
        )
        val survey = item.copy(questionnaireId = "s", title = "Survey", kind = "MARKET_SURVEY")
        val interview = item.copy(questionnaireId = "i", title = "Interview", kind = "WORKSHOP_INTERVIEW")
        val plain = item.copy(questionnaireId = "p", title = "Plain", kind = "")
        val groups = questionnairesByStage(listOf(plain, survey, interview), stages)
        assertEquals(
            listOf(
                "Workshop interview — stage 6, Existing Products Baseline",
                "Market survey — stage 8, Market Survey & Field Data",
                "",
            ),
            groups.map { it.first },
        )
        assertEquals(listOf("Plain"), groups.last().second.map { it.title })
        assertTrue(questionnaireProvenance(survey, stages).contains("filed as Market survey, under stage 8 (Market Survey & Field Data)"))
        val index = questionnaireIndexBlock(listOf(survey))
        assertEquals("Market survey", index.rows.single()[1].joinToString("") { it.text })
    }
}
