package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
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
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.ui.questionnaires.QUESTIONNAIRE_KIND_STAGE_KEYS
import com.designprototype.workshop.ui.questionnaires.labelForQuestionnaireKind
import java.util.Locale

const val QUESTIONNAIRE_ANNEXURE_HEADING = "Annexure — Questionnaire responses"

const val MAX_SITTINGS_PER_QUESTIONNAIRE = 40
const val MAX_ROWS_PER_SITTING = 400

private const val NOT_RECORDED = "Not recorded."

private const val RETIRED_NOTE = "no longer asked"

private const val ANNEXURE_LEAD =
    "The questionnaires this workshop's designer built and attached to it, and every sitting " +
        "recorded against them. Each question is printed in the wording the answer was given under: " +
        "a question reworded after it was answered is shown here as it was asked, marked " +
        "“$RETIRED_NOTE”. A required question left blank is printed as “$NOT_RECORDED” so that a " +
        "gap in the fieldwork is visible as a gap."

internal fun renderQuestionnaireAnnexure(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    cache: DwQuestionnaireCache?,
    stages: List<StageDto> = emptyList(),
): Int {
    if (cache == null) return 0
    val printed = cache.printedItems
    val unread = cache.unreadItems
    if (printed.isEmpty() && unread.isEmpty()) return 0

    val numbered = plan.template.numberHeadings
    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { QUESTIONNAIRE_ANNEXURE_HEADING },
        level = 1,
        numbered = numbered,
    )

    if (printed.isNotEmpty()) {
        builder.para(ANNEXURE_LEAD, style = ParaStyle.LEAD)
        builder.add(questionnaireIndexBlock(printed))
    }

    deviceNotes(cache).forEach { builder.para(it, style = ParaStyle.NOTE) }

    questionnairesByStage(printed, stages).forEach { (stageHeading, group) ->
      if (stageHeading.isNotEmpty()) builder.heading(stageHeading, level = 2, numbered = numbered)
      val depth = if (stageHeading.isNotEmpty()) 3 else 2
      group.forEach { item ->
        builder.heading(item.title.ifBlank { "Untitled questionnaire" }, level = depth, numbered = numbered)
        builder.para(questionnaireProvenance(item, stages), style = ParaStyle.NOTE)
        builder.para(item.description)
        val sittings = item.printedSittings
        val dropped = sittings.size - MAX_SITTINGS_PER_QUESTIONNAIRE
        sittings.take(MAX_SITTINGS_PER_QUESTIONNAIRE).forEach { sitting ->
            builder.heading(sitting.label, level = depth + 1, numbered = numbered)
            builder.para(sittingProvenance(sitting), style = ParaStyle.NOTE)
            builder.para(sitting.notes)
            sittingBlocks(sitting).forEach(builder::add)
        }
        if (dropped > 0) {
            builder.para(
                "[$dropped further ${if (dropped == 1) "sitting was" else "sittings were"} recorded against this questionnaire and " +
                    "${if (dropped == 1) "is" else "are"} not printed here. The full set is in the online workshop record.]",
                style = ParaStyle.NOTE,
            )
        }
      }
    }
    return printed.size
}

internal fun questionnaireStageGroup(item: DwQuestionnaireItem, stages: List<StageDto>): Triple<Int, String, String> {
    if (item.kind.isBlank()) return Triple(10_000, "", "")
    val label = labelForQuestionnaireKind(item.kind)
    val stageKey = QUESTIONNAIRE_KIND_STAGE_KEYS[item.kind] ?: return Triple(9_000, label, "filed as $label")
    val stage = stages.firstOrNull { it.key == stageKey }
    val number = stage?.number ?: 8_000
    val title = stage?.title ?: stageKey
    return Triple(
        number,
        "$label \u2014 stage $number, $title",
        "filed as $label, under stage $number ($title)",
    )
}

internal fun questionnairesByStage(
    items: List<DwQuestionnaireItem>,
    stages: List<StageDto>,
): List<Pair<String, List<DwQuestionnaireItem>>> {
    val groups = ArrayList<Pair<String, MutableList<DwQuestionnaireItem>>>()
    items.sortedBy { questionnaireStageGroup(it, stages).first }.forEach { item ->
        val heading = questionnaireStageGroup(item, stages).second
        if (groups.isNotEmpty() && groups.last().first == heading) groups.last().second += item
        else groups += heading to mutableListOf(item)
    }
    return groups
}

private fun deviceNotes(cache: DwQuestionnaireCache): List<String> {
    val notes = ArrayList<String>()
    if (cache.printedItems.isNotEmpty() && cache.fetchedAt.isNotBlank()) {
        notes += "These answers are those recorded up to ${cache.fetchedAt.take(10)}. Any sitting " +
            "recorded after that date is in the online workshop record."
    }
    val unread = cache.unreadItems
    if (unread.isNotEmpty()) {
        val names = unread.joinToString("; ") {
            it.title.ifBlank { "questionnaire ${it.questionnaireId}" }
        }
        notes += "${plural(unread.size, "questionnaire")} attached to this workshop ${if (unread.size == 1) "is" else "are"} not printed below: " +
            "$names. Their answers are in the online workshop record."
    }
    if (!cache.complete) {
        notes += "Other questionnaires may also be attached to this workshop; the online workshop " +
            "record lists them all."
    }
    return notes
}

internal fun questionnaireIndexBlock(items: List<DwQuestionnaireItem>): TableBlock = TableBlock(
    columns = listOf(
        TableColumn("Questionnaire", 36.0f),
        TableColumn("Kind", 18.0f),
        TableColumn("Questions", 14.0f, numeric = true),
        TableColumn("Sittings", 14.0f, numeric = true),
        TableColumn("Answers recorded", 18.0f, numeric = true),
    ),
    rows = items.map { item ->
        listOf(
            runsOf(item.title.ifBlank { "Untitled questionnaire" }),
            runsOf(labelForQuestionnaireKind(item.kind)),
            runsOf(if (item.questionCount > 0) grouped(item.questionCount) else "—"),
            runsOf(grouped(item.printedSittings.size)),
            runsOf(grouped(item.answeredTotal)),
        )
    },
    caption = "Questionnaires attached to this workshop and the sittings recorded against them.",
)

private fun grouped(value: Int): String = String.format(Locale.ROOT, "%,d", value)

private fun questionRuns(answer: DwQuestionnaireAnswer): List<Run> {
    val runs = ArrayList<Run>(runsOf(answer.prompt.ifBlank { "—" }))
    if (answer.isRetired) runs += runsOf(" ($RETIRED_NOTE)", italic = true)
    return runs
}

private fun answerRuns(answer: DwQuestionnaireAnswer): List<Run> {
    if (!answer.hasAnswer) return runsOf(NOT_RECORDED, italic = true)
    val runs = ArrayList<Run>(runsOf(answer.answerText.trim()))
    val note = answer.notes.trim()
    if (note.isNotEmpty()) runs += runsOf("  Note: $note", italic = true)
    return runs
}

internal fun sittingProvenance(sitting: DwQuestionnaireSitting): String {
    val source = when (sitting.source.uppercase(Locale.ROOT)) {
        "UPLOAD" -> "recorded on the uploaded spreadsheet"
        "APP" -> "recorded in the app"
        else -> ""
    }
    return listOf(
        "${sitting.answeredCount} ${if (sitting.answeredCount == 1) "question" else "questions"} answered",
        source,
        sitting.recordedAt.take(10),
        if (sitting.recordedBy.isNotEmpty()) "recorded by ${sitting.recordedBy}" else "",
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}

internal fun questionnaireProvenance(item: DwQuestionnaireItem, stages: List<StageDto> = emptyList()): String {
    val parts = listOf(
        questionnaireStageGroup(item, stages).third,
        "${item.printedSittings.size} ${if (item.printedSittings.size == 1) "sitting" else "sittings"}",
        if (item.questionCount > 0) "${item.questionCount} ${if (item.questionCount == 1) "question" else "questions"}" else "",
        "version ${item.version}",
        if (item.sourceFilename.isNotEmpty()) "from ${item.sourceFilename}" else "",
        "questionnaire ${item.questionnaireId}",
    ).filter { it.isNotEmpty() }
    return "The designer's own questionnaire, attached to this workshop · " + parts.joinToString(" · ")
}

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
                "[Answers truncated after $MAX_ROWS_PER_SITTING questions. The full set is in the " +
                    "online workshop record.]"
            ),
            style = ParaStyle.NOTE,
        )
    }
    return blocks
}
