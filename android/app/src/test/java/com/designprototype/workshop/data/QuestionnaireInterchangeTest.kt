package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The .xlsx interchange, asserted against the payloads the server actually sends.
 *
 * ── WHAT FAILURE THIS FILE EXISTS TO CATCH ────────────────────────────────────────────────────
 *
 * The same one [DwIdentityOcrWireTest] was written for, on a new endpoint pair: a DTO that is
 * self-consistent and names keys the server has never sent. `ApiClient` decodes with
 * `ignoreUnknownKeys = true`, so a misnamed field does not throw — it silently takes its default,
 * and the screen then reports a perfectly good upload as having done nothing. On THIS feature that
 * failure mode is worse than a blank field, because the field that would go quiet is
 * `report.provenance`: the paragraph telling a designer that the answers in their file were NOT
 * imported and why. Its absence is indistinguishable from "there were no answers", and the designer
 * concludes their fieldwork uploaded fine.
 *
 * ── THE FIXTURES ARE REAL, NOT TRANSCRIBED ────────────────────────────────────────────────────
 *
 * [IMPORTED], [SKIPPED] and [EDITED] below are the verbatim `report` objects printed by RUNNING the
 * backend against its local database on 2026-08-16 — `POST /api/questionnaires/upload` with a
 * hand-typed workbook, the same route with an answered workbook downloaded out of the platform, and
 * `POST /api/questionnaires/{id}/upload` — driven through the fixtures in
 * `backend/tests/test_questionnaire_interchange.py`. Only the questionnaire id has been left exactly
 * as it came back. Nothing here was read off the source and typed in; that is the whole point,
 * because reading the source is precisely how the identity-OCR DTO came to name five keys that do
 * not exist.
 *
 * ── THE ONE THING THESE THREE PAYLOADS PROVE TOGETHER ─────────────────────────────────────────
 *
 * THE TWO UPLOAD ENDPOINTS DO NOT RETURN THE SAME OBJECT. [EDITED] carries `details` and `updated`
 * and carries NO `entriesCreated`, NO `answersImported`, NO `answersSkipped` and NO `provenance` at
 * all — those keys are simply absent, not null. [IMPORTED] carries all four and has no `details`.
 * That is why every field on [QFormChangeReportDto] is defaulted, and this test is what stops a
 * later contributor "tightening" one of them into a required field and turning a successful
 * re-upload into a deserialisation crash on the screen that was about to explain it.
 */
class QuestionnaireInterchangeTest {

    /**
     * Configured exactly as `ApiClient` configures the converter Retrofit uses.
     *
     * That matters more than it looks: `ignoreUnknownKeys = true` is what turns a wrong key name
     * into silence rather than an exception, so a test that decoded strictly would not reproduce the
     * defect this file is about.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun decode(raw: String) = json.decodeFromString(QFormChangeReportDto.serializer(), raw)

    // ── The three live payloads ──────────────────────────────────────────────────────────────────

    /** `POST /questionnaires/upload` — a workbook typed by hand from paper interviews. */
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

    /** `POST /questionnaires/upload` — somebody else's answered workbook, downloaded and re-uploaded. */
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

    /**
     * `POST /questionnaires/{id}/reuse` — NOT AN UPLOAD, wearing the upload's clothes.
     *
     * The route answers in the upload response's shape on purpose, so that one panel renders both
     * (`questionnaire_forms.py`'s "THE RESPONSE IS THE UPLOAD RESPONSE'S SHAPE"). Every count is an
     * explicit zero, which is a statement and not an omission, and `problems` is present and empty.
     */
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

    /** `POST /questionnaires/{id}/upload` — the edit path. Note the four keys that are NOT here. */
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

    // ── The wire ─────────────────────────────────────────────────────────────────────────────────

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

    /**
     * The edit path's report is missing four keys the create path's has, and must still decode.
     *
     * This is the assertion that stops somebody making [QFormChangeReportDto.provenance] or
     * [QFormChangeReportDto.entriesCreated] required on the strength of having only ever looked at a
     * create response. A required field here does not degrade gracefully — kotlinx.serialization
     * throws, and the throw lands inside the coroutine that was about to show the designer which of
     * their questions had been superseded.
     */
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

    // ── Reading the report ───────────────────────────────────────────────────────────────────────

    /**
     * The tally prints only what happened, because a line of zeroes is a line nobody reads.
     *
     * The upload this asserts on retired four questions. If "0 superseded, 0 removed, 0 unchanged"
     * were printed beside it, the one term that matters would be the fourth of seven — and the
     * upload where "retired 4" appears is exactly the upload a designer must not skim.
     */
    @Test
    fun `the tally names only the non-zero outcomes`() {
        val summary = qFormUploadSummary(
            QFormChangeReportDto(created = 0, updated = 3, superseded = 0, retired = 4, unchanged = 0)
        )
        assertEquals("3 edited, 4 retired", summary)
        assertFalse("a zero term would bury the term that matters", summary.contains("0 "))
    }

    /** An upload that changed nothing says so, rather than leaving a blank line under a stopped spinner. */
    @Test
    fun `an upload that changed nothing produces a sentence and not an empty string`() {
        val summary = qFormUploadSummary(QFormChangeReportDto())
        assertTrue(summary.isNotBlank())
        assertTrue(summary.contains("nothing"))
    }

    /**
     * The provenance block warns on the skip branch and does NOT warn on the import branch.
     *
     * The asymmetry is the server's, and copying it is the point. `create_from_parsed` deliberately
     * pushes the skip sentence into `problems` and deliberately does not push the import sentence
     * there, with the reason written out in the source: a hand-filled workbook whose answers were
     * imported in full and attributed to the uploader is a CORRECT outcome, and dressing it in amber
     * teaches designers that an ordinary upload produces warnings — "the fastest way to make them
     * stop reading the list that does carry the rows they have lost".
     */
    @Test
    fun `only the skip branch is coloured as a warning`() {
        val skipped = qFormProvenanceNotice(decode(SKIPPED))!!
        assertTrue("answers left behind is the thing to notice", skipped.warn)
        assertTrue(skipped.heading.contains("NOT"))

        val imported = qFormProvenanceNotice(decode(IMPORTED))!!
        assertFalse("a correct hand-typed import must not read as a problem", imported.warn)
    }

    /**
     * A REUSE MUST NOT BE ANNOUNCED AS AN IMPORT.
     *
     * `action` has three values and the notice used to test for one of them, so `reused` fell into
     * the else arm and the panel said "The answers in this file were imported" — for an operation
     * that imported nothing, copied no sitting and read no file. On the one screen a designer opens
     * to find out what just happened to their questions.
     *
     * The properties, not the prose: it is not a warning (copying no answers is the whole point of a
     * reuse), it never says the word "imported", and it carries no tally, because every count on a
     * reuse is zero and a line reading "0 answers" invites the reader to look for a fault in an
     * operation that went perfectly.
     */
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

    /** The server's paragraph reaches the screen unaltered on this branch too. */
    @Test
    fun `the reuse reason is passed through verbatim`() {
        val report = decode(REUSED)
        assertEquals(report.provenance!!.reason, qFormProvenanceNotice(report)!!.reason)
    }

    /**
     * The three actions are three different sentences, and no two of them may be confused.
     *
     * This is the assertion that would have caught the defect: it fails the moment a fourth action
     * is added to the server and falls through to whichever branch happens to be last.
     */
    @Test
    fun `the three provenance actions read differently`() {
        val headings = listOf(REUSED, IMPORTED, SKIPPED)
            .map { qFormProvenanceNotice(decode(it))!!.heading }
        assertEquals("no two of the three may read alike", headings.size, headings.toSet().size)
    }

    /** No answers in the file means no provenance block — not a block reading "0 answers". */
    @Test
    fun `an upload carrying no answers shows no provenance block at all`() {
        assertNull(qFormProvenanceNotice(decode(EDITED)))
        assertNull(qFormProvenanceNotice(QFormChangeReportDto()))
    }

    /** The server's paragraph reaches the screen unaltered. Summarising it is the defect. */
    @Test
    fun `the provenance reason is passed through verbatim`() {
        val report = decode(SKIPPED)
        assertEquals(report.provenance!!.reason, qFormProvenanceNotice(report)!!.reason)
    }

    /**
     * The duplicated paragraph is dropped from the problem list, and the block keeps it.
     *
     * The server sends the same sentence twice ON PURPOSE — once as provenance and once as a problem
     * — so that a client rendering only the problem list still tells the designer. This client
     * renders both, and printing a two-hundred-word paragraph twice on one panel reads as a bug in
     * the app rather than as emphasis. The copy that goes is the one in `problems`, because the
     * block is the one with the tallies and the colour beside it.
     */
    @Test
    fun `the provenance paragraph is not printed twice`() {
        val report = decode(SKIPPED)
        assertEquals("the server really does duplicate it", 1, report.problems.size)
        assertEquals(report.provenance!!.reason, report.problems[0].reason)
        assertTrue("the copy in the problem list goes", qFormProblemsToShow(report).isEmpty())
    }

    /**
     * A REAL problem that merely happens to have no row is kept.
     *
     * The tempting filter — "drop whole-file warnings" or "drop the last problem" — would swallow
     * this one. Matching on the provenance text is the only rule that stays correct when either side
     * moves.
     */
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

    /** With no provenance at all, nothing is filtered — the list is passed straight through. */
    @Test
    fun `problems survive untouched when there is no provenance to deduplicate against`() {
        val report = QFormChangeReportDto(
            problems = listOf(QFormProblemDto(sheet = "Questionnaire", row = 12, reason = "Blank prompt."))
        )
        assertEquals(1, qFormProblemsToShow(report).size)
    }

    /** A problem reads as "where, then what", because the designer's next act is Ctrl+G. */
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

    // ── Which file is which ──────────────────────────────────────────────────────────────────────

    /**
     * Exactly one of the three artefacts carries the people who were interviewed.
     *
     * This is the assertion the whole feature turns on. Two of these downloads are named after the
     * same questionnaire and land in the same folder, and the server suffixes one of them
     * `-questions` because, in `question_set_filename`'s own words, "the name is the last thing
     * standing between a designer and that mistake". If a future change makes the question set
     * lossless — or makes the workbook questions-only — this test fails before anybody sends the
     * wrong file to a colleague.
     */
    @Test
    fun `only the full workbook carries respondents`() {
        assertFalse(artefactCarriesRespondents(DwQuestionnaireArtefact.PRO_FORMA))
        assertFalse(artefactCarriesRespondents(DwQuestionnaireArtefact.QUESTION_SET))
        assertTrue(artefactCarriesRespondents(DwQuestionnaireArtefact.FULL_WORKBOOK))
    }

    /** Every artefact says what it holds, in terms of people rather than of columns. */
    @Test
    fun `the question set says in words that it carries no answers`() {
        val text = artefactContents(DwQuestionnaireArtefact.QUESTION_SET)
        assertTrue(text.contains("no answers"))
        assertTrue(text.contains("no respondents"))
    }

    // ── The filename the server chose ────────────────────────────────────────────────────────────

    /**
     * The `-questions` suffix survives the trip, because it is the safety property.
     *
     * The server sends `Content-Disposition: attachment; filename="…-questions.xlsx"`. A client that
     * built its own name out of the questionnaire title would strip exactly the token that tells a
     * designer, later, looking at two files in Downloads with the same title, which of them is safe
     * to forward.
     */
    @Test
    fun `the servers filename is read off the header`() {
        assertEquals(
            "Bagru block-printing survey-questions.xlsx",
            filenameFromContentDisposition(
                """attachment; filename="Bagru block-printing survey-questions.xlsx""""
            )
        )
    }

    /** RFC 5987 wins when both spellings are present — it is the one that survives a non-ASCII title. */
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

    /**
     * A filename cannot walk out of the Downloads folder.
     *
     * This string goes into `MediaStore.Downloads.DISPLAY_NAME` and, below Q, into a path. It is a
     * value the SERVER chose, and a header carrying `../` would be the difference between a download
     * and a write somewhere nobody looked. The separators are stripped rather than the whole name
     * refused, because a questionnaire titled "Dyeing / printing" is ordinary and refusing its
     * download would be a strange way to find that out.
     */
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

    /** No header, or one without a filename, falls back rather than inventing an empty name. */
    @Test
    fun `a missing content-disposition returns null`() {
        assertNull(filenameFromContentDisposition(null))
        assertNull(filenameFromContentDisposition(""))
        assertNull(filenameFromContentDisposition("attachment"))
    }
}
