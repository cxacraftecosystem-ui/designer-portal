package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwQuestionnaireAnswer
import com.designprototype.workshop.data.DwQuestionnaireCache
import com.designprototype.workshop.data.DwQuestionnaireItem
import com.designprototype.workshop.data.DwQuestionnaireSitting
import com.designprototype.workshop.report.Align
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.DocumentBuilder
import com.designprototype.workshop.report.PageBreakBlock
import com.designprototype.workshop.report.ParaStyle
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TableColumn
import com.designprototype.workshop.report.TemplateSection
import com.designprototype.workshop.report.runsOf
import java.util.Locale

/**
 * The questionnaire annexure, drawn on the handset — the port of
 * `backend/app/services/report_questionnaires.py`.
 *
 * WHAT IT PRINTS. Every sitting recorded against a questionnaire the designer built in the .xlsx
 * pro-forma and attached to THIS workshop. Each question in the wording its answer was given under; a
 * question reworded afterwards marked as no longer asked; a required question left blank printed as a
 * blank so a gap in the fieldwork is visible as a gap.
 *
 * WHY IT MIRRORS THE SERVER LINE FOR LINE rather than being drawn the way a phone would draw it. The
 * office renders the same annexure from the same records, and the two files are compared: a section
 * present in one and absent from the other, or the same section in a different order, reads as a
 * designer having altered the document. Every string, every column width, every cap and every
 * ordering below is the server's, and where this file adds something it is one clearly-marked NOTE
 * about THIS COPY — never a change to the shared shape.
 *
 * ── THE THREE STATES, WHICH ARE THE WHOLE POINT ───────────────────────────────────────────────────
 *
 * An empty annexure and a missing one are different documents:
 *
 *   * NOTHING ATTACHED (or this device has never asked) — nothing is printed at all. Not a heading,
 *     not a page break, not a note. Byte for byte the report this build produced before the annexure
 *     existed, which is also exactly what `append_questionnaire_annexure` does with an empty list.
 *   * ATTACHED AND ANSWERED — the annexure, matching the office's copy.
 *   * ATTACHED AND THIS DEVICE HAS NO COPY OF THE ANSWERS — the questionnaire is NAMED, and the
 *     document says the answers are not in this file. Printing an empty heading instead would read as
 *     "nobody answered", which is a false statement about somebody's fieldwork; printing nothing at
 *     all would be the silent divergence this whole area exists to end.
 */

/** Mirrors `report_questionnaires.DEFAULT_HEADING`. */
const val QUESTIONNAIRE_ANNEXURE_HEADING = "Annexure — Questionnaire responses"

/**
 * Mirrors `MAX_SITTINGS_PER_QUESTIONNAIRE` / `MAX_ROWS_PER_SITTING`.
 *
 * A runaway form must not turn a report into a document nobody can open, and on this side the ceiling
 * is lower than a server's: [PdfWriter] lays out every row on the phone that is being handed over.
 * Forty sittings of four hundred answers is far above any real instrument and far below the size at
 * which the render stops finishing. Truncation SAYS SO in the document rather than dropping the tail
 * silently, because half a respondent's answers that nobody is told about is worse than a visible
 * note.
 */
const val MAX_SITTINGS_PER_QUESTIONNAIRE = 40
const val MAX_ROWS_PER_SITTING = 400

/** What an unanswered REQUIRED question prints. `report_questionnaires.NOT_RECORDED`. */
private const val NOT_RECORDED = "Not recorded."

/** Marks a question that was reworded after this answer was given. `RETIRED_NOTE`. */
private const val RETIRED_NOTE = "no longer asked"

/**
 * The lead paragraph, verbatim from `append_questionnaire_annexure`.
 *
 * The curly quotes are the server's and are kept: `cleanText` normalises line endings and strips
 * characters the .docx cannot carry, and touches neither, so the two documents hold the same bytes
 * here.
 */
private const val ANNEXURE_LEAD =
    "The questionnaires this workshop's designer built and attached to it, and every sitting " +
        "recorded against them. Each question is printed in the wording the answer was given under: " +
        "a question reworded after it was answered is shown here as it was asked, marked " +
        "“$RETIRED_NOTE”. A required question left blank is printed as “$NOT_RECORDED” so that a " +
        "gap in the fieldwork is visible as a gap."

/**
 * Draw the annexure for one workshop into [builder]. Returns how many questionnaires were printed.
 *
 * With nothing to say this appends NOTHING — not even the page break — so every workshop with no
 * questionnaire attached, which is most of them, produces exactly the report it produced before this
 * existed. That is the same contract `append_questionnaire_annexure` states and it is what makes the
 * change safe to ship into six templates that all carry the section.
 */
internal fun renderQuestionnaireAnnexure(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    cache: DwQuestionnaireCache?,
): Int {
    if (cache == null) return 0
    val printed = cache.printedItems
    val unread = cache.unreadItems
    // Attached-but-unanswered questionnaires are NOT a reason to open the annexure: the server drops
    // them too and raises a warning beside the download instead. Only answers to print, or an
    // attached questionnaire whose answers are missing from THIS copy, put a heading on the page.
    if (printed.isEmpty() && unread.isEmpty()) return 0

    val numbered = plan.template.numberHeadings
    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { QUESTIONNAIRE_ANNEXURE_HEADING },
        level = 1,
        numbered = numbered,
    )

    // ── the server's shape, contiguous and unbroken ──────────────────────────────────────────────
    if (printed.isNotEmpty()) {
        builder.para(ANNEXURE_LEAD, style = ParaStyle.LEAD)
        builder.add(questionnaireIndexBlock(printed))
    }

    // ── what is true of THIS copy and not of the office's ────────────────────────────────────────
    //
    // Placed after the index and before the questionnaires, as one run of NOTE paragraphs, so the
    // shared shape above stays exactly as the server emits it and every departure is in one place a
    // reader can find. `builder.para` drops a blank, so the common case adds nothing.
    deviceNotes(cache).forEach { builder.para(it, style = ParaStyle.NOTE) }

    printed.forEach { item ->
        builder.heading(item.title.ifBlank { "Untitled questionnaire" }, level = 2, numbered = numbered)
        builder.para(questionnaireProvenance(item), style = ParaStyle.NOTE)
        builder.para(item.description)
        val sittings = item.printedSittings
        val dropped = sittings.size - MAX_SITTINGS_PER_QUESTIONNAIRE
        sittings.take(MAX_SITTINGS_PER_QUESTIONNAIRE).forEach { sitting ->
            builder.heading(sitting.label, level = 3, numbered = numbered)
            builder.para(sittingProvenance(sitting), style = ParaStyle.NOTE)
            builder.para(sitting.notes)
            sittingBlocks(sitting).forEach(builder::add)
        }
        if (dropped > 0) {
            builder.para(
                "[$dropped further sitting(s) were recorded against this questionnaire and are " +
                    "not printed here. The full set is held in the repository.]",
                style = ParaStyle.NOTE,
            )
        }
    }
    return printed.size
}

/**
 * The lines that say what this copy is, and are absent from the office's.
 *
 * WHY THEY ARE IN THE DOCUMENT AND NOT ONLY IN THE EXPORT WARNINGS. `ReportPlan.warnings` are
 * advisory and belong to the ACT of generating — they are gone by the time an officer opens the file.
 * These are statements of fact about the file itself, addressed to its reader, and the second of them
 * is the one this lane exists for: a questionnaire that is attached and whose answers are not in this
 * copy must be named, or the annexure asserts by omission that the fieldwork does not exist. That is
 * the same argument `fieldCopyNote` makes for the cover line; this annexure can make it more
 * precisely, because by the time it is drawn the device knows exactly which questionnaires those are.
 */
private fun deviceNotes(cache: DwQuestionnaireCache): List<String> {
    val notes = ArrayList<String>()
    if (cache.printedItems.isNotEmpty() && cache.fetchedAt.isNotBlank()) {
        notes += "These answers are the copy this handset last read from the record on " +
            "${cache.fetchedAt.take(10)}. Any sitting recorded after that date is in the office's " +
            "copy of this report and not in this one."
    }
    val unread = cache.unreadItems
    if (unread.isNotEmpty()) {
        val names = unread.joinToString("; ") {
            it.title.ifBlank { "questionnaire ${it.questionnaireId}" }
        }
        notes += "${unread.size} questionnaire(s) attached to this workshop are not printed below, " +
            "because this device holds no copy of the answers recorded against them: $names. This " +
            "is a gap in THIS file and not in the fieldwork — the office's copy of this report " +
            "carries them."
    }
    if (!cache.complete) {
        notes += "This handset has not read the full list of questionnaires attached to this " +
            "workshop, so there may be others beyond the one(s) named here. The office's copy is " +
            "built from the list itself."
    }
    return notes
}

/** The contents table at the head of the annexure — `questionnaire_index_block`. */
internal fun questionnaireIndexBlock(items: List<DwQuestionnaireItem>): TableBlock = TableBlock(
    columns = listOf(
        TableColumn("Questionnaire", 46.0f),
        TableColumn("Questions", 16.0f, numeric = true),
        TableColumn("Sittings", 16.0f, numeric = true),
        TableColumn("Answers recorded", 22.0f, numeric = true),
    ),
    rows = items.map { item ->
        listOf(
            runsOf(item.title.ifBlank { "Untitled questionnaire" }),
            runsOf(if (item.questionCount > 0) grouped(item.questionCount) else "—"),
            runsOf(grouped(item.printedSittings.size)),
            runsOf(grouped(item.answeredTotal)),
        )
    },
    caption = "Questionnaires attached to this workshop and the sittings recorded against them.",
)

/**
 * Python's `f"{n:,}"`, and PINNED TO [Locale.ROOT] rather than to the device's.
 *
 * This handset ships with Odia and Hindi locales in use — that is the whole reason the PDF is drawn
 * in the system face. `String.format` with the default locale would group and render the digits the
 * way that locale does, so the same count would print as one number at the office and another on the
 * phone, in a table an officer reads as a discrepancy. The server has no device locale to be
 * influenced by; this is what makes the two agree.
 */
private fun grouped(value: Int): String = String.format(Locale.ROOT, "%,d", value)

/** The question cell: the prompt, and the retirement marker when there is one. `_question_runs`. */
private fun questionRuns(answer: DwQuestionnaireAnswer): List<Run> {
    val runs = ArrayList<Run>(runsOf(answer.prompt.ifBlank { "—" }))
    if (answer.isRetired) runs += runsOf(" ($RETIRED_NOTE)", italic = true)
    return runs
}

/**
 * The answer cell: what was said, and the interviewer's note under it. `_answer_runs`.
 *
 * The note is italic and prefixed rather than run together with the answer, because a note is the
 * interviewer's own words ABOUT the answer and an officer quoting the cell must be able to tell the
 * two apart. It is one cell rather than a third column so the table still fits a portrait page when
 * the answers are sentences, which they are.
 */
private fun answerRuns(answer: DwQuestionnaireAnswer): List<Run> {
    if (!answer.hasAnswer) return runsOf(NOT_RECORDED, italic = true)
    val runs = ArrayList<Run>(runsOf(answer.answerText.trim()))
    val note = answer.notes.trim()
    if (note.isNotEmpty()) runs += runsOf("  Note: $note", italic = true)
    return runs
}

/**
 * The one NOTE line under a sitting's heading saying where its answers came from.
 * `_sitting_provenance`.
 *
 * THE COUNT IS BARE AND NOT "X OF Y", deliberately. A sitting can carry answers to questions the form
 * no longer asks — a reworded question keeps its old answers under its old wording, which is the
 * supersede rule doing its job — so an answered count can legitimately exceed the number of questions
 * the questionnaire currently contains. "9 of 8 questions answered" in a document submitted to a
 * ministry reads as a defect in the app rather than as the truth about the record.
 */
internal fun sittingProvenance(sitting: DwQuestionnaireSitting): String {
    val source = when (sitting.source.uppercase(Locale.ROOT)) {
        "UPLOAD" -> "recorded on the uploaded spreadsheet"
        "APP" -> "recorded in the app"
        else -> ""
    }
    return listOf(
        "${sitting.answeredCount} question(s) answered",
        source,
        sitting.recordedAt.take(10),
        if (sitting.recordedBy.isNotEmpty()) "recorded by ${sitting.recordedBy}" else "",
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}

/**
 * The line under a questionnaire's heading that lets a file be matched back to its source.
 * `_questionnaire_provenance`.
 *
 * The id and the VERSION are both here on purpose. `Questionnaire.version` counts the
 * destructive-ish edits applied after the first answer existed — a supersede or a retire — so two
 * reports of the same workshop that disagree can be told apart by it without opening the database.
 */
internal fun questionnaireProvenance(item: DwQuestionnaireItem): String {
    val parts = listOf(
        "${item.printedSittings.size} sitting(s)",
        if (item.questionCount > 0) "${item.questionCount} question(s)" else "",
        "version ${item.version}",
        if (item.sourceFilename.isNotEmpty()) "from ${item.sourceFilename}" else "",
        "questionnaire ${item.questionnaireId}",
    ).filter { it.isNotEmpty() }
    return "The designer's own questionnaire, attached to this workshop · " + parts.joinToString(" · ")
}

/**
 * One sitting's answers as report blocks: a labelled table per section of the form. `sitting_blocks`.
 *
 * ONE TABLE PER SECTION, EACH PRECEDED BY ITS LABEL, and the label is not only editorial. Both
 * writers refuse two adjacent tables — an empty paragraph between them is what stops Word merging the
 * two into one — so a per-section table with nothing in between would either merge the sections or
 * need a blank paragraph that says nothing. The section label is the paragraph that has to be there
 * anyway, doing the job.
 */
internal fun sittingBlocks(sitting: DwQuestionnaireSitting): List<Block> {
    val blocks = ArrayList<Block>()
    val rows = ArrayList<List<List<Run>>>()
    var current = ""
    var printed = 0
    var truncated = false

    fun flush() {
        if (rows.isEmpty()) return
        if (current.isNotEmpty()) {
            blocks += ParagraphBlock(
                runs = runsOf(current, bold = true),
                style = ParaStyle.BODY,
                align = Align.LEFT,
            )
        }
        blocks += TableBlock(
            columns = listOf(TableColumn("Question", 46.0f), TableColumn("Answer", 54.0f)),
            rows = rows.toList(),
        )
        rows.clear()
    }

    for (answer in sitting.printedAnswers) {
        if (printed >= MAX_ROWS_PER_SITTING) {
            truncated = true
            break
        }
        val label = answer.sectionLabel
        if (label != current) {
            flush()
            current = label
        }
        rows += listOf(questionRuns(answer), answerRuns(answer))
        printed++
    }
    flush()

    if (truncated) {
        blocks += ParagraphBlock(
            runs = runsOf(
                "[Answers truncated after $MAX_ROWS_PER_SITTING questions. The full set is held " +
                    "against the questionnaire in the repository.]"
            ),
            style = ParaStyle.NOTE,
        )
    }
    return blocks
}
