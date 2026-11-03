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

class ReportQuestionnaireDocxTest {

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

    private fun visibleText(xml: String): String =
        Regex("<w:t(?: [^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).joinToString("") { it.groupValues[1] }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

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
        assertTrue("the report itself must still be there", printed.contains("Barpali cluster"))
    }

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
            printed.contains("Their answers are in the online workshop record"),
        )
        assertEquals(
            "nothing may be indexed — there is no answer to index, and a numbered heading over an " +
                "empty table is what makes a report look corrupt",
            0,
            Regex("Answers recorded").findAll(printed).count(),
        )
    }
}
