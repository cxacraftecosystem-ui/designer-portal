package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwQuestionnaireAnswer
import com.designprototype.workshop.data.DwQuestionnaireCache
import com.designprototype.workshop.data.DwQuestionnaireItem
import com.designprototype.workshop.data.DwQuestionnaireSitting
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.renderDocx
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The answers must reach the BYTES of the file, not merely the block list.
 *
 * ── WHY THIS EXISTS BESIDE [ReportQuestionnaireAnnexureTest] ──────────────────────────────────────
 *
 * That test asserts on `ReportDocument.blocks`, which is the renderer's INPUT. A block list is not a
 * document: this project has already shipped a perfectly valid .docx with a correct cover page and
 * ten paragraphs of nothing behind it, and only unzipping the package revealed it. `word/document.xml`
 * is the artefact a ministry officer actually opens, so that is what is asserted here.
 *
 * TWO THINGS IT PINS THAT THE BLOCK-LEVEL TEST STRUCTURALLY CANNOT:
 *
 *  1. THE REAL TEMPLATE, UNMODIFIED. [ReportQuestionnaireAnnexureTest] proves the section draws by
 *     replacing `DCH_STANDARD.sections` with a one-element list holding only the annexure. That
 *     answers "does the branch run", not "does the annexure survive a document that also contains a
 *     cover, a table of contents, a metric row, twenty-two stages and three other annexures". The
 *     section-walk in `buildWorkshopDocument` is the thing that could drop it, and stripping the
 *     section list removes exactly the code under suspicion.
 *  2. [DocxWriter] EMITTING IT. Every block the annexure produces has to be one the writer knows how
 *     to draw. A block type the writer's `when` does not handle is dropped in silence — no exception,
 *     no warning, a valid package with the section missing — which is the failure this file is named
 *     after.
 *
 * IT WRITES A REAL FILE as well as asserting on the bytes in memory: `app/build/verify-annexure/`
 * holds the .docx after a run, so a human can open it in Word and a reviewer can unzip it without
 * re-deriving the fixture. The assertions do not depend on that file — a read-only build tree costs
 * the artefact and not the test.
 */
class ReportQuestionnaireDocxTest {

    // ── one workshop, one questionnaire, one answered sitting ────────────────────────────────────

    /** The one string a reader would look for. Distinctive enough to grep the package for. */
    private val ANSWER = "Indigo and madder"

    private val cache = DwQuestionnaireCache(
        workshopId = "w1",
        complete = true,
        fetchedAt = "2026-03-01T06:12:44Z",
        items = listOf(
            DwQuestionnaireItem(
                questionnaireId = "cmq0001",
                title = "Barpali loom survey",
                description = "Household level.",
                version = 3,
                sourceFilename = "barpali.xlsx",
                questionCount = 4,
                answersHeld = true,
                sittings = listOf(
                    DwQuestionnaireSitting(
                        entryId = "cmentry000123456789",
                        title = "First sitting",
                        respondentName = "Padma Meher",
                        source = "APP",
                        notes = "Recorded in the courtyard.",
                        recordedAt = "2026-03-04T09:30:00+00:00",
                        recordedBy = "A. Mohanty",
                        answers = listOf(
                            DwQuestionnaireAnswer(
                                prompt = "How many looms?", answerText = "12",
                                notes = "counted with the master weaver",
                                sectionCode = "A", sectionTitle = "Household", isRequired = true,
                            ),
                            DwQuestionnaireAnswer(
                                prompt = "Which dyes?", answerText = ANSWER,
                                sectionCode = "B", sectionTitle = "Dyeing",
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun text(key: String, label: String) =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = "KEY_VALUE", tier = "BASIC")

    /**
     * A registry of real stage keys, so the resolved template is the real one.
     *
     * Every key here is in `NARRATIVE_ORDER`; an unknown key would make `reportPlanFor` append a
     * section and raise a warning, which is a different code path from the one under test.
     */
    private val schema = SchemaResponse(
        version = "verify",
        stages = listOf(
            StageDto(
                number = 1, key = "WORKSHOP_SETUP", title = "Workshop setup",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Workshop setup",
                        fields = listOf(
                            text("craftName", "Craft"),
                            text("clusterName", "Cluster"),
                            text("state", "State"),
                        ),
                    )
                ),
            ),
        ),
    )

    private fun values(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { (k, v) -> k to JsonPrimitive(v) }

    private val draft = WorkshopDraft(
        workshopId = "w1",
        title = "Barpali cluster",
        stages = mapOf(
            "WORKSHOP_SETUP" to StageDraft(
                stageId = "WORKSHOP_SETUP",
                values = values(
                    "craftName" to "Sambalpuri ikat",
                    "clusterName" to "Barpali",
                    "state" to "Odisha",
                ),
            ),
        ),
    )

    /** The whole report, through the SHIPPED template — nothing stripped, nothing substituted. */
    private fun document(questionnaires: DwQuestionnaireCache?): ReportDocument =
        buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = "w1",
            templateId = "DCH_STANDARD",
            warnings = emptyList(),
            accent = "",
            imageFor = { null },
            format = "DOCX",
            generatedAt = "2026-03-04T09:30:00Z",
            questionnaires = questionnaires,
        )

    /** The .docx as the designer would get it, and as a file on disk for anyone who wants to open it. */
    private fun docxBytes(questionnaires: DwQuestionnaireCache?, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        renderDocx(document(questionnaires), { null }, out)
        val bytes = out.toByteArray()
        runCatching {
            val dir = File("build/verify-annexure").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
        }
        return bytes
    }

    private fun entriesOf(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    private fun documentXml(bytes: ByteArray): String {
        val entries = entriesOf(bytes)
        assertTrue(
            "a .docx without word/document.xml is not a document Word will open: ${entries.keys}",
            entries.containsKey("word/document.xml"),
        )
        return String(entries.getValue("word/document.xml"), Charsets.UTF_8)
    }

    /**
     * `<w:t>…</w:t>` is where every visible character of a .docx lives.
     *
     * CONCATENATED WITH NO SEPARATOR, and that is not laziness. `runsOf` puts every span through
     * `splitByScript`, so one printed sentence becomes one run PER SCRIPT and one `<w:t>` per run —
     * an Odia respondent name inside an English sentence is three nodes. Joining on a newline would
     * make a search for the sentence fail on exactly the handsets this app is built for, and the test
     * would pass on the developer's Latin-only fixture and lie about the field.
     */
    private fun visibleText(xml: String): String =
        Regex("<w:t(?: [^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).joinToString("") { it.groupValues[1] }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    // ── the assertion the lane turns on ──────────────────────────────────────────────────────────

    /**
     * The whole lane, measured at the only place that counts: the bytes handed to the officer.
     *
     * Asserted against the TEXT NODES rather than the raw XML, so a string that happens to appear in
     * a relationship id, a style name or a comment cannot pass for a printed answer.
     */
    @Test
    fun `the recorded answer is in the bytes of the exported docx`() {
        val printed = visibleText(documentXml(docxBytes(cache, "with-answers.docx")))

        assertTrue(
            "the answer a designer typed in a courtyard must be readable in the file: $printed",
            printed.contains(ANSWER),
        )
        assertTrue(
            "and under its own annexure heading, or it is loose text nobody can find",
            printed.contains("Annexure — Questionnaire responses"),
        )
        assertTrue("the questionnaire must be named", printed.contains("Barpali loom survey"))
        assertTrue("and the sitting attributed", printed.contains("Padma Meher"))
        assertTrue(
            "the question the answer belongs to must print beside it",
            printed.contains("Which dyes?"),
        )
        assertTrue(
            "the interviewer's note is part of the evidence",
            printed.contains("counted with the master weaver"),
        )
        assertTrue(
            "the provenance line lets the file be matched back to the record",
            printed.contains("questionnaire cmq0001"),
        )
        assertTrue(
            "and the copy must date itself — see DwQuestionnaireStore.fetchedAt",
            printed.contains("2026-03-01"),
        )
    }

    /**
     * The cover page cannot pass for a report.
     *
     * The ten-paragraph .docx that shipped here was not empty — it had a correct cover — so a floor
     * on the paragraph count is what tells "the document rendered" from "the document opened".
     */
    @Test
    fun `the annexure is not the only thing in the package, and the package is a real docx`() {
        val bytes = docxBytes(cache, "with-answers.docx")
        val entries = entriesOf(bytes)
        assertTrue("[Content_Types].xml is what makes it openable", entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("word/_rels/document.xml.rels"))

        val xml = documentXml(bytes)
        val paragraphs = Regex("<w:p[ >]").findAll(xml).count()
        assertTrue(
            "a cover sheet alone is about ten paragraphs; the annexure's own rows are more than " +
                "that on their own, so anything at this level means the body did not render: " +
                "$paragraphs",
            paragraphs > 25,
        )
        assertTrue(
            "the annexure is drawn as tables, which is how the two copies are compared",
            xml.contains("<w:tbl>"),
        )
    }

    /**
     * THE OTHER HALF, and it is not symmetry for its own sake.
     *
     * A handset that has never read this workshop's questionnaire list must produce byte-for-byte the
     * report this build produced before the annexure existed — no heading, no page break, no
     * apology — because that is the majority of exports and the argument for keeping the conditional
     * warning rests on it.
     */
    @Test
    fun `a device with no copy of the answers writes the document it always wrote`() {
        val without = docxBytes(null, "without-answers.docx")
        val printed = visibleText(documentXml(without))

        assertFalse("no answer may appear from nowhere: $printed", printed.contains(ANSWER))
        assertFalse(
            "and no heading may be opened over nothing — an empty annexure reads as 'nobody " +
                "answered', which is a false statement about somebody's fieldwork",
            printed.contains("Annexure — Questionnaire responses"),
        )
        // The rest of the report is untouched: the cover still names the workshop.
        assertTrue("the report itself must still be there", printed.contains("Barpali cluster"))
    }

    /**
     * A questionnaire the device knows is attached and whose answers it does not hold is NAMED.
     *
     * Silence here is the defect the lane exists to end: it asserts by omission that the fieldwork
     * does not exist. Measured in the file, because that is where the assertion is made.
     */
    @Test
    fun `an attached questionnaire without its answers is named in the file itself`() {
        val unread = DwQuestionnaireCache(
            workshopId = "w1", complete = true, fetchedAt = "2026-03-01T06:12:44Z",
            items = listOf(
                DwQuestionnaireItem(questionnaireId = "cmq0003", title = "Loom census", answersHeld = false)
            ),
        )
        val printed = visibleText(documentXml(docxBytes(unread, "named-without-answers.docx")))

        assertTrue("the form must be named: $printed", printed.contains("Loom census"))
        assertTrue(
            "and the file must say the gap is in THIS copy: $printed",
            printed.contains("this device holds no copy of the answers"),
        )
        assertEquals(
            "nothing may be indexed — there is no answer to index, and a numbered heading over an " +
                "empty table is what makes a report look corrupt",
            0,
            Regex("Answers recorded").findAll(printed).count(),
        )
    }
}
