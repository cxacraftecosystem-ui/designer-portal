package com.designprototype.workshop.report

import com.designprototype.workshop.data.DwTranscriptItem
import java.util.Locale

/*
 * The transcript annexure — `backend/app/services/report_annexures.py`, transliterated.
 *
 * WHY A TRANSLITERATION AND NOT A DESIGN. The office's copy of a report is built by that module and
 * the phone's copy by this one, and the two are handed to the same officer for the same workshop. A
 * heading worded apart, a column rounded the other way or a caution printed below a transcript rather
 * than above it is a difference somebody will read as an altered document. So every constant, regex
 * and branch here is the server's, in the server's order, and `ReportTranscriptParityTest` holds the
 * output block for block to `shared/report-parity/expected.json` — the file the server's own code
 * writes from `shared/report-parity/workshop.json`.
 *
 * The items arrive already loaded (see `DwTranscriptStore`): every renderer here is synchronous,
 * exactly as the server's is, and a transcript is never fetched mid-render.
 */

const val TRANSCRIPT_DEFAULT_HEADING = "Annexure — Recordings and transcripts"

/** `MAX_PARAGRAPHS_PER_TRANSCRIPT`. */
const val MAX_PARAGRAPHS_PER_TRANSCRIPT = 1200

// The stored transcript form: a speaker turn is a BOLD span of at most 60 characters ending in a
// colon at the start of a line. Written without `(?U)`: ICU, which Android's regex is, rejects it.
private val LOOSE_LABEL_RE = Regex("\\*\\*\\s*([^*\\n]{1,60}?)\\s*\\*\\*\\s*:")
private val SPEAKER_LINE_RE = Regex("^\\*\\*([^*\\n]{1,60}?):\\*\\*\\s*(.*)$")
private val RULE_RE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
private val HEADING_RE = Regex("^\\s*#{1,6}\\s+(\\S.*?)\\s*#*\\s*$")
private val BULLET_RE = Regex("^\\s*[-*+]\\s+(?=\\S)")
private val CODE_RE = Regex("`([^`\\n]+)`")
// Bold before italic, so "**x**" is never read as an empty italic wrapping "*x*".
private val EMPHASIS_RE = Regex("\\*\\*\\s*([^*\\n]+?)\\s*\\*\\*|\\*\\s*([^*\\n]+?)\\s*\\*")
private val WHITESPACE_RE = Regex("\\s+")

/** Python's `str.strip()` — Unicode whitespace from both ends. */
private fun pyStrip(text: String): String = text.trim { it.isWhitespace() }

/** `_SPEAKER_LINE_RE.match(line)`; the lines it is asked about never hold a newline. */
private fun speakerLine(line: String): MatchResult? = SPEAKER_LINE_RE.find(line)?.takeIf { it.range.first == 0 }

/** `_normalised_lines`: the transcript as display lines, labels folded to one shape. */
private fun normalisedLines(text: String): List<String> {
    val lines = ArrayList<String>()
    for (raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
        if (RULE_RE.matches(raw)) {
            lines += ""
            continue
        }
        val heading = HEADING_RE.matchEntire(raw)
        if (heading != null) {
            lines += pyStrip(CODE_RE.replace(heading.groupValues[1], "$1"))
            continue
        }
        val line = CODE_RE.replace(BULLET_RE.replaceFirst(pyStrip(raw), "• "), "$1")
        lines += LOOSE_LABEL_RE.replace(line, "**$1:**")
    }
    return lines
}

/** `plain_text`: the transcript with its Markdown removed. */
fun transcriptPlainText(text: String): String {
    val stripped = EMPHASIS_RE.replace(text) { m ->
        m.groups[1]?.value ?: m.groups[2]?.value ?: ""
    }
    return stripped.split(WHITESPACE_RE).filter { it.isNotEmpty() }.joinToString(" ")
}

/** `speaker_count`: distinct voices the labels name; 1 for unlabelled speech, 0 for none. */
fun transcriptSpeakerCount(text: String): Int {
    val labels = normalisedLines(text)
        .mapNotNull { speakerLine(it) }
        .map { pyStrip(it.groupValues[1]).lowercase(Locale.ROOT) }
        .toSet()
    if (labels.isNotEmpty()) return labels.size
    return if (transcriptPlainText(text).isNotEmpty()) 1 else 0
}

/** `TranscriptItem.word_count`. */
fun transcriptWordCount(text: String): Int {
    val plain = transcriptPlainText(text)
    return if (plain.isEmpty()) 0 else plain.split(" ").size
}

/** `duration_text`: 754 -> "12 min 34 s"; blank when never recorded. */
fun transcriptDurationText(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return ""
    val minutesTotal = seconds / 60
    val remainder = seconds % 60
    val hours = minutesTotal / 60
    val minutes = minutesTotal % 60
    if (hours > 0) return String.format(Locale.ROOT, "%d h %02d min", hours, minutes)
    if (minutes > 0) return String.format(Locale.ROOT, "%d min %02d s", minutes, remainder)
    return "$remainder s"
}

/** `_runs_for_line`: one display line as runs, Markdown emphasis turned into real formatting. */
private fun runsForLine(line: String): List<Run> {
    val pieces = ArrayList<Run>()
    var position = 0
    for (match in EMPHASIS_RE.findAll(line)) {
        if (match.range.first > position) pieces += runsOf(line.substring(position, match.range.first))
        val bold = match.groups[1]?.value
        val italic = match.groups[2]?.value
        pieces += if (bold != null) runsOf(bold, bold = true) else runsOf(italic, italic = true)
        position = match.range.last + 1
    }
    if (position < line.length) pieces += runsOf(line.substring(position))
    return pieces
}

/** `transcript_body_blocks`: a paragraph per speaker turn, the label as a real bold run. */
fun transcriptBodyBlocks(text: String): List<Block> {
    val blocks = ArrayList<Block>()
    var truncated = false
    for (line in normalisedLines(text)) {
        if (blocks.size >= MAX_PARAGRAPHS_PER_TRANSCRIPT) {
            truncated = true
            break
        }
        if (pyStrip(line).isEmpty()) {
            if (blocks.isNotEmpty() && blocks.last() !is SpacerBlock) blocks += SpacerBlock(heightPct = 1.5f)
            continue
        }
        val match = speakerLine(line)
        val runs = if (match != null) {
            val label = pyStrip(match.groupValues[1])
            val body = pyStrip(match.groupValues[2])
            runsOf("$label: ", bold = true) + runsForLine(body)
        } else {
            runsForLine(line)
        }
        if (runs.isNotEmpty()) blocks += ParagraphBlock(runs = runs, style = ParaStyle.BODY, align = Align.LEFT)
    }
    if (truncated) {
        blocks += ParagraphBlock(
            runs = runsOf(
                "[Transcript shortened to the first $MAX_PARAGRAPHS_PER_TRANSCRIPT paragraphs. " +
                    "The full text is in the online workshop record.]"
            ),
            style = ParaStyle.NOTE,
        )
    }
    return blocks
}

/** `SPEAKER_NOTE`, word for word. */
const val TRANSCRIPT_SPEAKER_NOTE =
    "The speaker labels in this transcript — who is saying which line — were decided by the machine " +
        "that transcribed the recording, not by anybody who was present. Nothing recorded how many people " +
        "were speaking or who they were: the labels are that machine's separation of the voices it heard, " +
        "and it can merge two speakers into one or split one across two. The numbering is by order of first " +
        "speaking and is not a name. Check a line against the recording before attributing it to a " +
        "particular person."

/** `speaker_labels_are_guessed`. */
fun transcriptSpeakerLabelsAreGuessed(item: DwTranscriptItem): Boolean =
    item.text.isNotEmpty() && normalisedLines(item.text).any { speakerLine(it) != null }

/** `transcript_index_block`: the contents table at the head of the annexure. */
fun transcriptIndexBlock(items: List<DwTranscriptItem>): TableBlock = TableBlock(
    columns = listOf(
        TableColumn("Stage", 26.0f),
        TableColumn("Recording", 32.0f),
        TableColumn("Length", 14.0f, align = Align.RIGHT),
        TableColumn("Speakers", 12.0f, numeric = true),
        TableColumn("Words", 16.0f, numeric = true),
    ),
    rows = items.map { item ->
        listOf(
            runsOf(if (item.stageNumber != 0) "${item.stageNumber}. ${item.stageTitle}" else item.stageTitle),
            runsOf(item.label),
            runsOf(transcriptDurationText(item.durationSeconds).ifEmpty { "—" }),
            runsOf(transcriptSpeakerCount(item.text).toString()),
            runsOf(String.format(Locale.ROOT, "%,d", transcriptWordCount(item.text))),
        )
    },
    caption = "Recordings transcribed during this workshop. The speaker count is the number of voices " +
        "the transcribing machine separated out of the audio, not a count of the people present.",
)

/** `_provenance`: the line under each heading saying where the text came from. */
private fun provenance(item: DwTranscriptItem): String {
    val speakers = transcriptSpeakerCount(item.text)
    val parts = listOf(
        transcriptDurationText(item.durationSeconds),
        if (speakers != 0) "$speakers speaker${if (speakers != 1) "s" else ""}" else "",
        if (item.recordedAt.isNotEmpty()) item.recordedAt.take(10) else "",
        "recording ${item.mediaId}",
    ).filter { it.isNotEmpty() }
    return "Transcribed automatically from the workshop recording · " + parts.joinToString(" · ")
}

/**
 * `append_transcript_annexure`. Returns how many transcripts were printed.
 *
 * With nothing transcribed this appends nothing at all — not even the page break — exactly as the
 * server's does, so a template carrying the section is unchanged whenever there is nothing to print.
 */
fun appendTranscriptAnnexure(
    doc: DocumentBuilder,
    items: List<DwTranscriptItem>,
    heading: String = "",
    numbered: Boolean = true,
    pageBreakBefore: Boolean = true,
): Int {
    val printed = items.filter { it.hasText }
    if (printed.isEmpty()) return 0
    if (pageBreakBefore) doc.add(PageBreakBlock)
    doc.heading(heading.ifEmpty { TRANSCRIPT_DEFAULT_HEADING }, 1, numbered = numbered)
    doc.para(
        "The recordings made during this workshop, transcribed in full. The text is produced by " +
            "automatic speech recognition and lightly edited; it is the machine's reading of the audio " +
            "rather than a certified transcript, and the recordings themselves remain the record.",
        style = ParaStyle.LEAD,
    )
    doc.add(transcriptIndexBlock(printed))
    for (item in printed) {
        doc.heading(item.label, 2, numbered = numbered)
        doc.para(provenance(item), style = ParaStyle.NOTE)
        if (transcriptSpeakerLabelsAreGuessed(item)) doc.para(TRANSCRIPT_SPEAKER_NOTE, style = ParaStyle.NOTE)
        transcriptBodyBlocks(item.text).forEach { doc.add(it) }
    }
    return printed.size
}

private val IN_FLIGHT_STATUSES = setOf("QUEUED", "PROCESSING")
private val STALLED_STATUSES = setOf("FAILED", "UNAVAILABLE")

/** `annexure_warnings`, word for word: the recordings with no transcript, by what can be done. */
fun transcriptAnnexureWarnings(items: List<DwTranscriptItem>): List<String> {
    val missing = items.filter { !it.hasText }
    if (missing.isEmpty()) return emptyList()
    val statuses = missing.map { it.status.uppercase(Locale.ROOT) }
    val pending = statuses.count { it in IN_FLIGHT_STATUSES }
    val stalled = statuses.count { it in STALLED_STATUSES }
    val clauses = ArrayList<String>()
    if (pending > 0) clauses += "$pending still being transcribed"
    if (stalled > 0) {
        clauses += "$stalled will not arrive on their own — saving the stage they are attached to " +
            "re-attempts them"
    }
    val detail = if (clauses.isNotEmpty()) " (${clauses.joinToString("; ")})" else ""
    return listOf(
        "${missing.size} recording(s) have no transcript and were left out of the " +
            "transcript annexure$detail."
    )
}

/** `attach_report_transcripts`' refusal sentence, for recordings the server would not hand over. */
fun transcriptRefusedWarning(refused: Int): String? = if (refused <= 0) null else
    "$refused recording(s) attached to this workshop could not be included in the " +
        "transcript annexure: they were uploaded by another account, or the file is gone."
