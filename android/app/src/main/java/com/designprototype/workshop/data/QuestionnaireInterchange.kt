package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

/**
 * The .xlsx half of the custom-questionnaire API: the three downloads and the two uploads.
 *
 * ── WHY THIS FILE EXISTS AT ALL, WHICH IS A REVERSED DECISION ─────────────────────────────────
 *
 * Until now this client bound 16 of the 22 `questionnaires/…` endpoints and deliberately omitted
 * six of them. The omission was argued, not forgotten — the note in [WorkshopRepositoryApi] said a
 * form is built on a laptop in a spreadsheet and not on a handset, and that a designer picking an
 * .xlsx out of Android's document provider on a phone with no spreadsheet application is a worse
 * route to the same place. THE USER HAS SINCE ASKED FOR BOTH SURFACES ON THE HANDSET EXPLICITLY,
 * which is a requirement and outranks a client author's judgement about what a phone is for. The
 * old note is rewritten in place rather than deleted, so the next reader finds the reversal and its
 * reason instead of an absence.
 *
 * The original argument was also not wholly right, and the part that was wrong is the part this
 * feature turns on. It reasoned about BUILDING a form. Three of the six endpoints do not build
 * anything: `question-set.xlsx` is how a designer HANDS a colleague an instrument, and receiving one
 * is `POST /questionnaires/upload`. That pair is a courtyard act between two people standing next to
 * each other with phones, and it was the surface the laptop argument never covered.
 *
 * ── THE THREE ARTEFACTS, AND THE ONE SENTENCE THAT MUST NEVER BE COLLAPSED ────────────────────
 *
 * The single most important thing this client says is WHICH FILE CARRIES RESPONDENTS. Two of the
 * three downloads are named after the same questionnaire and land in the same Downloads folder, and
 * the difference between them is the difference between sending a colleague your question list and
 * sending them every person you have ever interviewed. [DwQuestionnaireArtefact] carries that
 * sentence per artefact and [artefactCarriesRespondents] is the flag a screen must render
 * unconditionally — never behind a disclosure, never summarised.
 *
 * ── THE UPLOAD THAT DOES NOT IMPORT ANSWERS, AND WHY THAT IS NOT AN ERROR ─────────────────────
 *
 * `create_from_parsed` refuses to write the answers of a workbook that came out of the platform —
 * detected by the Questionnaire ID on the Details sheet OR any filled Question ID cell. Those
 * answers already exist in this database under the names of the people who recorded them, so
 * writing them again is duplication rather than import, and that holds even for the owner. The
 * server reports which branch it took in `report.provenance`, with a sentence written to be shown
 * VERBATIM — see [qFormProvenanceNotice], which shows it and never paraphrases it.
 *
 * The server pushes that same sentence into `report.problems` on the SKIP branch only, so that a
 * client which renders only the problem list still tells the designer. This client renders both, so
 * it would print the sentence twice; [qFormProblemsToShow] removes the duplicate rather than the
 * provenance block, because the provenance block is the one with the tallies beside it. The IMPORT
 * branch is deliberately absent from `problems` on the server and must stay absent here: an
 * ordinary correct upload that produces a warning teaches designers that warnings are noise, which
 * is the fastest way to make them stop reading the list that does carry the rows they have lost.
 */

// --------------------------------------------------------------------------------------
// The wire shapes
// --------------------------------------------------------------------------------------

/**
 * One row the parser could not read cleanly, or read but could not apply as written.
 *
 * [row] is the 1-based worksheet row exactly as Excel's row gutter shows it, which is why it is
 * printed as "row 34" and not translated into anything friendlier: the designer's next act is to
 * press Ctrl+G in the spreadsheet and type it.
 *
 * [severity] is `error` when nothing was stored for that row and `warning` when it was stored but
 * something had to be assumed. Both are shown. A client that hid warnings would hide precisely the
 * rows whose damage is invisible — a merged cell, a formula Excel never calculated, "maybe" in the
 * Required column — which is the failure the list exists to prevent.
 */
@Serializable
data class QFormProblemDto(
    val sheet: String? = null,
    val row: Int? = null,
    val severity: String = "warning",
    val reason: String = "",
    val value: String? = null,
)

/**
 * One question the edit path superseded or retired, and the server's sentence saying why.
 *
 * [action] is `superseded` or `retired`. [before] is the wording that was there; [after] is the new
 * wording, on a supersede only. [reason] is meant to be shown verbatim — a designer whose six
 * corrections came back as six NEW questions has to be told that happened and that their answers
 * are safe, and a client that summarised it into "6 changes" would leave them to work that out
 * from a question count.
 */
@Serializable
data class QFormDetailDto(
    val action: String = "",
    val questionId: String? = null,
    val replacementId: String? = null,
    val before: String? = null,
    val after: String? = null,
    val reason: String = "",
)

/**
 * What the server did with the ANSWERS in an uploaded workbook, as opposed to its questions.
 *
 * [action] is `answersImported` or `answersNotImported`. The counts are branch-specific on the
 * server — a skip carries [answersSkipped], an import carries [answersImported] and
 * [entriesCreated] — so every one of them is defaulted here rather than required; a missing count
 * on the branch that does not have it is the shape, not a truncated reply.
 *
 * [reason] IS THE PAYLOAD. Both branches carry a paragraph written for a designer to read, and
 * neither is a code this client is supposed to translate. [sourceQuestionnaireId] is set only on
 * the skip branch and only when the Details sheet named one — the second signal, a filled Question
 * ID cell, identifies a platform file without naming which one.
 */
@Serializable
data class QFormProvenanceDto(
    val action: String = "",
    val sourceQuestionnaireId: String? = null,
    val answersImported: Int = 0,
    val answersSkipped: Int = 0,
    val entriesCreated: Int = 0,
    val reason: String = "",
)

/**
 * The change report both upload endpoints return beside the questionnaire.
 *
 * EVERY FIELD IS DEFAULTED, and that is not laziness about the contract — the two endpoints return
 * genuinely different subsets. The create path has `sections` and no `details`; the edit path has
 * `details`, `updated` and `removed` and never has `entriesCreated`. Requiring a field that one of
 * the two branches does not send would turn a successful upload into a deserialisation crash on
 * the screen that was about to explain it.
 */
@Serializable
data class QFormChangeReportDto(
    val created: Int = 0,
    val updated: Int = 0,
    val superseded: Int = 0,
    val retired: Int = 0,
    val removed: Int = 0,
    val unchanged: Int = 0,
    val sections: Int = 0,
    val entriesCreated: Int = 0,
    val answersImported: Int = 0,
    val answersSkipped: Int = 0,
    val provenance: QFormProvenanceDto? = null,
    val versionBefore: Int = 0,
    val versionAfter: Int = 0,
    val problems: List<QFormProblemDto> = emptyList(),
    val details: List<QFormDetailDto> = emptyList(),
)

/** `POST /questionnaires/upload` and `POST /questionnaires/{id}/upload`. */
@Serializable
data class QFormUploadResultDto(
    val questionnaire: CustomQuestionnaireDto,
    val report: QFormChangeReportDto = QFormChangeReportDto(),
)

// --------------------------------------------------------------------------------------
// The three artefacts, stated rather than remembered
// --------------------------------------------------------------------------------------

/**
 * Which of the three .xlsx files a control is about to fetch.
 *
 * An enum rather than three loose call sites, because the difference between two of them is the
 * difference between a question list and every respondent's name, and a boolean argument named
 * something like `questionsOnly` is exactly the shape the server refused for the same reason: a
 * parameter that changes what a file contains is one typo away from handing over the wrong one.
 * The backend made these two SEPARATE ROUTES on that argument; this client keeps them two separate
 * cases all the way to the button.
 */
enum class DwQuestionnaireArtefact {
    /** The blank workbook a questionnaire is typed into. Carries nothing about anybody. */
    PRO_FORMA,

    /** One questionnaire's questions alone — the artefact a designer sends to another designer. */
    QUESTION_SET,

    /** One questionnaire, losslessly: its questions, its sittings, every respondent and answer. */
    FULL_WORKBOOK,
}

/**
 * Does this artefact carry the people who were interviewed?
 *
 * THE ONE FLAG A SCREEN MUST RENDER UNCONDITIONALLY. It is the whole difference between the two
 * downloads that share a questionnaire's title, and the reason the server suffixes one filename
 * with `-questions`. A screen may collapse the elaboration behind a disclosure; it may not collapse
 * this.
 */
fun artefactCarriesRespondents(artefact: DwQuestionnaireArtefact): Boolean =
    artefact == DwQuestionnaireArtefact.FULL_WORKBOOK

/**
 * What this file contains, in one sentence, for the person about to send it to somebody.
 *
 * Written in terms of PEOPLE and not of columns. "Includes entries" is true and useless; "carries
 * every person you interviewed" is the fact a designer needs before they attach it to a message.
 */
fun artefactContents(artefact: DwQuestionnaireArtefact): String = when (artefact) {
    DwQuestionnaireArtefact.PRO_FORMA ->
        "A blank workbook. It carries no questions, no answers and nobody's name — it is the form " +
            "you type your own questions into."
    DwQuestionnaireArtefact.QUESTION_SET ->
        "The questions only — no answers, no respondents' names, no recorded sittings. This is the " +
            "one to send to another designer: uploading it creates a NEW questionnaire that belongs " +
            "to them, rather than editing yours."
    DwQuestionnaireArtefact.FULL_WORKBOOK ->
        "EVERYTHING: the questions, every recorded sitting, every respondent's name and every " +
            "answer. Do not send this to somebody who is only meant to run the questionnaire — " +
            "send the question set instead."
}

// --------------------------------------------------------------------------------------
// Reading the change report
// --------------------------------------------------------------------------------------

/** The server's word for "this workbook came out of the platform, so its answers were not written". */
const val QFORM_ANSWERS_NOT_IMPORTED = "answersNotImported"

/** The server's word for "these answers were hand-typed, so they were imported and attributed to you". */
const val QFORM_ANSWERS_IMPORTED = "answersImported"

/**
 * The tally line: what the upload actually did to the questionnaire, counted.
 *
 * ONLY NON-ZERO TERMS. A sentence reading "0 superseded, 0 retired, 0 removed" trains a designer to
 * skip the line, and the one upload where "retired 4" appears is the upload where they must not.
 * An upload that changed nothing says so in words rather than producing an empty string, because a
 * blank line under a spinner that has just stopped is indistinguishable from a failure.
 *
 * Pure, so the wording is pinned by a JVM test rather than by somebody re-uploading a spreadsheet.
 */
fun qFormUploadSummary(report: QFormChangeReportDto): String {
    val parts = listOfNotNull(
        report.created.takeIf { it > 0 }?.let { "$it question(s) added" },
        report.updated.takeIf { it > 0 }?.let { "$it edited" },
        report.superseded.takeIf { it > 0 }?.let { "$it superseded" },
        report.retired.takeIf { it > 0 }?.let { "$it retired" },
        report.removed.takeIf { it > 0 }?.let { "$it removed" },
        report.sections.takeIf { it > 0 }?.let { "$it section(s)" },
        report.entriesCreated.takeIf { it > 0 }?.let { "$it sitting(s) recorded" },
        report.unchanged.takeIf { it > 0 }?.let { "$it unchanged" },
    )
    return if (parts.isEmpty()) {
        "The workbook was read and nothing in the questionnaire needed changing."
    } else {
        parts.joinToString(", ")
    }
}

/**
 * The provenance block: what happened to the ANSWERS in the file, in the server's own words.
 *
 * Null when the upload carried no answers at all, which is the ordinary case for a question set
 * handed over between designers — there is nothing to say and a panel saying "0 answers were
 * imported" would invite a designer to wonder what went wrong.
 *
 * [warn] is what a screen colours on. It is TRUE only on the skip branch, and that asymmetry is
 * the same one the server applies to `problems`: a hand-filled workbook whose answers were imported
 * in full and attributed to the uploader is a correct outcome, and dressing it in amber would teach
 * designers that a perfectly ordinary upload is a problem.
 *
 * [reason] is passed through UNTOUCHED. It is a paragraph the server wrote for a designer to read;
 * this client's job is to put it on the screen, not to improve it.
 */
data class QFormProvenanceNotice(
    val warn: Boolean,
    val heading: String,
    val reason: String,
    val tally: String?,
)

fun qFormProvenanceNotice(report: QFormChangeReportDto): QFormProvenanceNotice? {
    val provenance = report.provenance ?: return null
    val skipped = provenance.action == QFORM_ANSWERS_NOT_IMPORTED
    val count = if (skipped) {
        provenance.answersSkipped.takeIf { it > 0 } ?: report.answersSkipped
    } else {
        provenance.answersImported.takeIf { it > 0 } ?: report.answersImported
    }
    val entries = provenance.entriesCreated.takeIf { it > 0 } ?: report.entriesCreated
    return QFormProvenanceNotice(
        warn = skipped,
        heading = if (skipped) "The answers in this file were NOT imported" else "The answers in this file were imported",
        reason = provenance.reason,
        tally = when {
            skipped && count > 0 -> "$count answer(s) left where they already are"
            !skipped && count > 0 && entries > 0 -> "$count answer(s) in $entries sitting(s), attributed to you"
            !skipped && count > 0 -> "$count answer(s), attributed to you"
            else -> null
        },
    )
}

/**
 * The problem rows worth putting on screen, with the provenance paragraph removed.
 *
 * The server deliberately pushes the skip-branch provenance sentence into `problems` as well, so
 * that a client which renders only the problem list still tells the designer what happened to their
 * answers. This client renders the provenance block too, and printing the same two-hundred-word
 * paragraph twice on one screen reads as a bug in the app rather than as emphasis — so the copy in
 * `problems` is dropped and the block is kept, because the block is the one with the tallies and
 * the colour.
 *
 * MATCHED ON THE TEXT, not on position or on severity. The server appends it, so "last row" would
 * work today and break the first time a problem is appended after it; and dropping every
 * `row == null` warning would silently swallow whole-file problems that have nothing to do with
 * provenance. Comparing the reason to the reason the provenance block is already showing is the
 * only test that stays true when either side moves.
 */
fun qFormProblemsToShow(report: QFormChangeReportDto): List<QFormProblemDto> {
    val duplicated = report.provenance?.reason?.trim().orEmpty()
    if (duplicated.isEmpty()) return report.problems
    return report.problems.filterNot { it.reason.trim() == duplicated }
}

/** How a problem row reads on a phone: where it is, then what is wrong with it. */
fun qFormProblemLine(problem: QFormProblemDto): String {
    val where = listOfNotNull(
        problem.sheet?.takeIf { it.isNotBlank() },
        problem.row?.let { "row $it" },
    ).joinToString(" · ")
    val value = problem.value?.takeIf { it.isNotBlank() }?.let { " (“$it”)" }.orEmpty()
    return if (where.isBlank()) problem.reason + value else "$where — ${problem.reason}$value"
}

// --------------------------------------------------------------------------------------
// The filename the server chose
// --------------------------------------------------------------------------------------

/**
 * The filename out of a `Content-Disposition` header, or null if there is not one to read.
 *
 * READ FROM THE SERVER RATHER THAN INVENTED HERE, and for this feature that is a correctness
 * requirement rather than a nicety. `question_set_filename` appends `-questions` to the stem, and
 * its own docstring says why: both downloads land in the same Downloads folder with the same
 * questionnaire title on them, and "the name is the last thing standing between a designer and
 * that mistake". A client that built its own name from the title would strip exactly the suffix
 * that distinguishes a question list from every respondent a designer has ever interviewed.
 *
 * Handles the two spellings a header can use — `filename="…"` and RFC 5987's `filename*=UTF-8''…`,
 * preferring the latter when both are present because it is the one that survives a non-ASCII
 * title. Anything that would escape the Downloads folder, or that is empty once cleaned, returns
 * null so the caller falls back to a name of its own.
 */
fun filenameFromContentDisposition(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val extended = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.get(1)?.trim()
    val fromExtended = extended?.substringAfter("''", missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
    val plain = Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.get(1)
        ?: Regex("""filename\s*=\s*([^;"]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim()
    return safeDownloadName(fromExtended ?: plain)
}

/**
 * A filename that cannot leave the folder it was meant for.
 *
 * A `Content-Disposition` is a string a server chose, and this one goes straight into
 * `MediaStore.Downloads.DISPLAY_NAME` or, below Q, into a path. `../` in it, or a bare directory
 * separator, is the difference between a download and a write somewhere nobody looked. The
 * separators are stripped rather than the whole name refused, because a title with a slash in it is
 * an ordinary questionnaire and refusing its download would be a strange way to find that out.
 */
internal fun safeDownloadName(raw: String?): String? {
    val cleaned = raw?.trim()
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.replace(Regex("""[\x00-\x1f]"""), "")
        ?.trim()
        ?.trim('.')
        ?: return null
    return cleaned.takeIf { it.isNotEmpty() }?.take(120)
}
