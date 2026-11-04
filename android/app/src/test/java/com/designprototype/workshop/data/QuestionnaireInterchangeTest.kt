package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireInterchangeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun decode(raw: String) = json.decodeFromString(QFormChangeReportDto.serializer(), raw)

    private val IMPORTED = """
    {
     "answersImported": 1,
     "answersSkipped": 0,
     "created": 2,
     "entriesCreated": 1,
     "problems": [],
     "provenance": {
      "action": "answersImported",
      "answersImported": 1,
      "entriesCreated": 1,
      "reason": "1 answers were already typed into this workbook, so they were recorded as 1 sitting and attributed to you. This file carries no Question IDs and no Questionnaire ID, which is what says it was filled in by hand rather than downloaded out of the platform — if somebody else recorded these interviews, say so in each sitting's notes, because the app can only attribute them to whoever uploaded the file.",
      "sourceQuestionnaireId": null
     },
     "removed": 0,
     "retired": 0,
     "sections": 1,
     "superseded": 0,
     "unchanged": 0,
     "versionAfter": 1,
     "versionBefore": 1
    }
    """.trimIndent()

    private val SKIPPED = """
    {
     "answersImported": 0,
     "answersSkipped": 1,
     "created": 2,
     "entriesCreated": 0,
     "problems": [
      {
       "reason": "This workbook came out of the platform (its Details sheet names questionnaire cmsvfnb4y0001qq1bzd2g48lq), and the 1 answers in it are fieldwork that is already recorded there, under the names of the people who recorded it. Its questions were imported and its answers were NOT: copying them into a second questionnaire would duplicate that fieldwork and record it under your name. Open the questionnaire they belong to in order to read them, or type your own interviews into a blank pro-forma.",
       "row": null,
       "severity": "warning",
       "sheet": "Questionnaire",
       "value": null
      }
     ],
     "provenance": {
      "action": "answersNotImported",
      "answersSkipped": 1,
      "reason": "This workbook came out of the platform (its Details sheet names questionnaire cmsvfnb4y0001qq1bzd2g48lq), and the 1 answers in it are fieldwork that is already recorded there, under the names of the people who recorded it. Its questions were imported and its answers were NOT: copying them into a second questionnaire would duplicate that fieldwork and record it under your name. Open the questionnaire they belong to in order to read them, or type your own interviews into a blank pro-forma.",
      "sourceQuestionnaireId": "cmsvfnb4y0001qq1bzd2g48lq"
     },
     "removed": 0,
     "retired": 0,
     "sections": 1,
     "superseded": 0,
     "unchanged": 0,
     "versionAfter": 1,
     "versionBefore": 1
    }
    """.trimIndent()

    private val REUSED = """
    {
     "answersImported": 0,
     "answersSkipped": 0,
     "created": 2,
     "entriesCreated": 0,
     "problems": [],
     "provenance": {
      "action": "reused",
      "answersSkipped": 0,
      "reason": "This is a new questionnaire carrying the 2 questions of \u201cWeaving intake 2026\u201d. The two are separate from here on: editing one does not change the other. No sitting and no answer was copied \u2014 the fieldwork recorded against the original stays on the original, under the names of the people who recorded it \u2014 so this copy starts empty and ready for its own.",
      "sourceQuestionnaireId": "cmsvfnb4y0001qq1bzd2g48lq"
     },
     "removed": 0,
     "retired": 0,
     "sections": 1,
     "superseded": 0,
     "unchanged": 0,
     "versionAfter": 1,
     "versionBefore": 1
    }
    """.trimIndent()

    private val EDITED = """
    {
     "created": 0,
     "details": [],
     "problems": [],
     "removed": 0,
     "retired": 0,
     "sections": 0,
     "superseded": 0,
     "unchanged": 2,
     "updated": 0,
     "versionAfter": 1,
     "versionBefore": 1
    }
    """.trimIndent()

    @Test
    fun `the hand-typed import is read off the keys the server actually sends`() {
        val report = decode(IMPORTED)
        assertEquals(2, report.created)
        assertEquals(1, report.sections)
        assertEquals(1, report.entriesCreated)
        assertEquals(1, report.answersImported)
        assertEquals(0, report.answersSkipped)
        assertNotNull("provenance is the whole point of this reply", report.provenance)
        val provenance = report.provenance!!
        assertEquals(QFORM_ANSWERS_IMPORTED, provenance.action)
        assertNull("a hand-filled file names no source questionnaire", provenance.sourceQuestionnaireId)
        assertTrue(provenance.reason.contains("attributed to you"))
    }

    @Test
    fun `the platform-workbook skip is read, including which questionnaire it came out of`() {
        val report = decode(SKIPPED)
        assertEquals("the QUESTIONS still arrive — the uploader is not punished", 2, report.created)
        assertEquals(0, report.entriesCreated)
        assertEquals(0, report.answersImported)
        assertEquals(1, report.answersSkipped)
        val provenance = report.provenance!!
        assertEquals(QFORM_ANSWERS_NOT_IMPORTED, provenance.action)
        assertEquals("cmsvfnb4y0001qq1bzd2g48lq", provenance.sourceQuestionnaireId)
        assertTrue(provenance.reason.contains("already recorded"))
        assertEquals(1, report.problems.size)
        assertEquals("Questionnaire", report.problems[0].sheet)
        assertNull("a whole-file problem has no row", report.problems[0].row)
    }

    @Test
    fun `the edit path's report decodes even though it omits every answer-related key`() {
        val report = decode(EDITED)
        assertEquals(2, report.unchanged)
        assertEquals(0, report.updated)
        assertTrue("`details` is present and empty on an edit that changed nothing", report.details.isEmpty())
        assertNull("the edit path sends no provenance at all", report.provenance)
        assertEquals("absent must read as zero, not crash", 0, report.entriesCreated)
        assertEquals(0, report.answersImported)
        assertEquals(0, report.answersSkipped)
    }

    @Test
    fun `the tally names only the non-zero outcomes`() {
        val summary = qFormUploadSummary(
            QFormChangeReportDto(created = 0, updated = 3, superseded = 0, retired = 4, unchanged = 0)
        )
        assertEquals("3 edited, 4 retired", summary)
        assertFalse("a zero term would bury the term that matters", summary.contains("0 "))
    }

    @Test
    fun `an upload that changed nothing produces a sentence and not an empty string`() {
        val summary = qFormUploadSummary(QFormChangeReportDto())
        assertTrue(summary.isNotBlank())
        assertTrue(summary.contains("nothing"))
    }

    @Test
    fun `only the skip branch is coloured as a warning`() {
        val skipped = qFormProvenanceNotice(decode(SKIPPED))!!
        assertTrue("answers left behind is the thing to notice", skipped.warn)
        assertTrue(skipped.heading.contains("NOT"))

        val imported = qFormProvenanceNotice(decode(IMPORTED))!!
        assertFalse("a correct hand-typed import must not read as a problem", imported.warn)
    }

    @Test
    fun `a reuse is not described as an import of a file`() {
        val notice = qFormProvenanceNotice(decode(REUSED))!!

        assertFalse("copying no answers is what a reuse IS", notice.warn)
        assertFalse(
            "the else arm used to claim this",
            notice.heading.contains("imported", ignoreCase = true),
        )
        assertFalse(
            "and there was no file",
            notice.heading.contains("file", ignoreCase = true),
        )
        assertNull("every count is zero; a tally here would be a line about nothing", notice.tally)
    }

    @Test
    fun `the reuse reason is passed through verbatim`() {
        val report = decode(REUSED)
        assertEquals(report.provenance!!.reason, qFormProvenanceNotice(report)!!.reason)
    }

    @Test
    fun `the three provenance actions read differently`() {
        val headings = listOf(REUSED, IMPORTED, SKIPPED)
            .map { qFormProvenanceNotice(decode(it))!!.heading }
        assertEquals("no two of the three may read alike", headings.size, headings.toSet().size)
    }

    @Test
    fun `an upload carrying no answers shows no provenance block at all`() {
        assertNull(qFormProvenanceNotice(decode(EDITED)))
        assertNull(qFormProvenanceNotice(QFormChangeReportDto()))
    }

    @Test
    fun `the provenance reason is passed through verbatim`() {
        val report = decode(SKIPPED)
        assertEquals(report.provenance!!.reason, qFormProvenanceNotice(report)!!.reason)
    }

    @Test
    fun `the provenance paragraph is not printed twice`() {
        val report = decode(SKIPPED)
        assertEquals("the server really does duplicate it", 1, report.problems.size)
        assertEquals(report.provenance!!.reason, report.problems[0].reason)
        assertTrue("the copy in the problem list goes", qFormProblemsToShow(report).isEmpty())
    }

    @Test
    fun `a whole-file problem that is not the provenance sentence survives the filter`() {
        val report = decode(SKIPPED).let { skipped ->
            skipped.copy(
                problems = skipped.problems + QFormProblemDto(
                    sheet = "Questionnaire",
                    row = null,
                    severity = "error",
                    reason = "The Questions sheet could not be found, so the first sheet was read instead.",
                )
            )
        }
        val shown = qFormProblemsToShow(report)
        assertEquals(1, shown.size)
        assertTrue(shown[0].reason.contains("could not be found"))
    }

    @Test
    fun `problems survive untouched when there is no provenance to deduplicate against`() {
        val report = QFormChangeReportDto(
            problems = listOf(QFormProblemDto(sheet = "Questionnaire", row = 12, reason = "Blank prompt."))
        )
        assertEquals(1, qFormProblemsToShow(report).size)
    }

    @Test
    fun `a problem line names the sheet and the row exactly as Excel shows them`() {
        assertEquals(
            "Questionnaire · row 34 — Required could not be read (“maybe”)",
            qFormProblemLine(
                QFormProblemDto(
                    sheet = "Questionnaire",
                    row = 34,
                    severity = "warning",
                    reason = "Required could not be read",
                    value = "maybe",
                )
            )
        )
    }

    @Test
    fun `only the full workbook carries respondents`() {
        assertFalse(artefactCarriesRespondents(DwQuestionnaireArtefact.PRO_FORMA))
        assertFalse(artefactCarriesRespondents(DwQuestionnaireArtefact.QUESTION_SET))
        assertTrue(artefactCarriesRespondents(DwQuestionnaireArtefact.FULL_WORKBOOK))
    }

    @Test
    fun `the question set says in words that it carries no answers`() {
        val text = artefactContents(DwQuestionnaireArtefact.QUESTION_SET)
        assertTrue(text.contains("no answers"))
        assertTrue(text.contains("no respondents"))
    }

    @Test
    fun `the servers filename is read off the header`() {
        assertEquals(
            "Bagru block-printing survey-questions.xlsx",
            filenameFromContentDisposition(
                """attachment; filename="Bagru block-printing survey-questions.xlsx""""
            )
        )
    }

    @Test
    fun `the extended filename is preferred over the ascii fallback`() {
        assertEquals(
            "सर्वेक्षण-questions.xlsx",
            filenameFromContentDisposition(
                "attachment; filename=\"survey-questions.xlsx\"; " +
                    "filename*=UTF-8''%E0%A4%B8%E0%A4%B0%E0%A5%8D%E0%A4%B5%E0%A5%87%E0%A4%95%E0%A5%8D" +
                    "%E0%A4%B7%E0%A4%A3-questions.xlsx"
            )
        )
    }

    @Test
    fun `a traversal in the header cannot escape the downloads folder`() {
        assertEquals(
            "passwd",
            filenameFromContentDisposition("""attachment; filename="../../etc/passwd"""")
        )
        assertEquals("survey.xlsx", safeDownloadName("""C:\Windows\survey.xlsx"""))
        assertNull("a name that cleans away to nothing must fall back", safeDownloadName("../"))
        assertNull(safeDownloadName("   "))
    }

    @Test
    fun `a missing content-disposition returns null`() {
        assertNull(filenameFromContentDisposition(null))
        assertNull(filenameFromContentDisposition(""))
        assertNull(filenameFromContentDisposition("attachment"))
    }
}
