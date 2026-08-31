package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.report.Align
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.EMPTY_RICH_DOC
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.report.fromPlain
import com.designprototype.workshop.report.toJson
import com.designprototype.workshop.report.toPlain
import com.designprototype.workshop.ui.appendSpokenToRecord
import com.designprototype.workshop.ui.looksLikeRichDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  WHAT `QuestionnaireResponse.answerText` HOLDS, ON A COLUMN THAT IS AND STAYS `String?`.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The interview form's answer box became a RICH TEXT box on the web on 2026-08-31, at the owner's
 * request that a transcript *"should appear in the rich text box"*. The column did not change type
 * and there was no migration: it holds NULL, prose, or the JSON encoding of a document, told apart
 * only by looking at the value. This file is the handset's half of that arrangement — the encoder,
 * the decoder, the read boundary, and the one append that has to know the difference.
 *
 * ── THIS COLUMN TAKES OPTION (a). THE RECORD COLUMNS TAKE OPTION (b). BOTH ARE RIGHT ──────────
 *
 * `RecordProseText.kt` sets out the same two candidates at length for the twenty-odd `String?`
 * columns a RECORD form writes — (a) store the document as JSON and flatten in every reader, or
 * (b) store `toPlain` and lose the inline marks — and chose (b), because option (a)'s readers were
 * not all taught: eleven raw Prisma `contains` clauses, `record_fields.cell`'s four export surfaces,
 * and two review-diff renderers. Its argument is sound and this file does not overturn it.
 *
 * It simply does not apply here, because THIS column's readers were taught in the same wave that
 * made the box rich, and can be listed:
 *
 *   - `record_fields.cell` — the chokepoint behind the data browser's info card, the `/data/report`
 *     workbook, `details.txt`, `answers.txt` and the two record CSVs. `answers.txt` was the fifth
 *     call site it acquired, and it was acquired FOR this column, on the day the box changed.
 *   - `questionnaire_consolidation` flattens through `plain_from_stored` before an answer reaches
 *     the consolidated page, which is the surface a ministry report is assembled from.
 *   - `export.py`'s interview emitter, at the one line in that file that needed it.
 *   - The web's own two read-only renderings of a response, through `plainFromStoredRichText`.
 *
 * And the decisive half: **the web ALREADY WRITES documents into this column.** A handset that
 * cannot read one is not neutral — it shows a researcher `{"blocks":[{"kind":"PARAGRAPH"…` where a
 * colleague's answer should be, and overwrites it if they type. Reading is therefore not optional,
 * and once the phone reads the shape, refusing to write it is the parity gap running the wrong way:
 * the handset is the device actually carried into the workshop.
 *
 * ── THE RULE, WHICH IS THE WEB'S RULE AND NOT A SECOND ONE ────────────────────────────────────
 *
 * **A document is only ever stringified when it is not expressible as plain text.**
 * [questionnaireAnswerStored] flattens an unformatted document with `toPlain` and writes the prose;
 * it writes JSON only once somebody has actually applied a mark, a heading, a list, a quote, an
 * alignment, a table or an inline photograph. `encodeStoredRichText` in
 * `frontend/components/richtext/storedRichText.ts` is the same three lines and states the same
 * consequence: the overwhelming majority of answers are typed and dictated plainly, and those keep
 * exactly the bytes they keep today, so nothing downstream sees a change for them at all.
 *
 * The residual cost is bounded and is worth naming rather than discovering: an answer somebody
 * formatted stores a document, and a reader that has NOT been taught to flatten shows braces for
 * that one answer. Two such readers are still in the tree at the time of writing and neither is in
 * this lane's files — `data_browser._interview_answers`, which interpolates `r.answerText or ''`
 * into the interview `details.txt` beside the `_cell` calls that do flatten, and both clients'
 * artisan questionnaire panel, which prints the column straight. They are reported rather than
 * reached into; making the phone write plain prose instead would not fix either, because the web
 * writes the documents.
 *
 * ── WHY THE SHAPE TEST IS IMPORTED AND NOT REWRITTEN ──────────────────────────────────────────
 *
 * [looksLikeRichDocument] is `internal` in `RecordProseText.kt` and is used here as it stands. A
 * second spelling of "is this a document" is how the two come to disagree about one, and the
 * disagreement is silent in both directions: a document read as prose prints braces, and prose read
 * as a document blanks somebody's answer.
 */

/**
 * Whether every block of [doc] survives a trip through plain text unchanged.
 *
 * DELIBERATELY A WHITELIST OF "NOTHING INTERESTING IS SET" rather than a blacklist of known-lossy
 * features, and the polarity is the whole point: a block kind or a mark added to `RichText.kt` later
 * must default to "store as JSON", never to "quietly drop it". The failure of the other polarity is
 * invisible — a researcher's table would flatten to pipe-separated lines on save and nobody would
 * find out until the report was printed. `isPlainProse` on the web is this function, field for field.
 */
internal fun questionnaireAnswerIsProse(doc: RichDoc): Boolean =
    doc.blocks.all { block ->
        block.kind == BlockKind.PARAGRAPH &&
            block.level == 0 &&
            block.align == Align.LEFT &&
            block.media.isEmpty() &&
            block.rows.isEmpty() &&
            block.spans.all { it.marks.isEmpty() }
    }

/**
 * What lands in the column. **The single decision point named in this file's header.**
 *
 * Returns `""` and never null for an empty document, because the one caller is a text box whose
 * value is a `String`; the form's own `takeIf { it.isNotBlank() }` at submit time turns that into
 * the null the API expects, exactly as it did when this was a plain box.
 */
fun questionnaireAnswerStored(doc: RichDoc): String {
    if (doc.isEmpty) return ""
    if (!questionnaireAnswerIsProse(doc)) return toJson(doc).toString()
    return toPlain(doc)
}

/**
 * Whatever is in the column, as a document the editor can open.
 *
 * ── A DOCUMENT IS PARSED; ANYTHING ELSE IS PROSE, AND PROSE IS PARAGRAPHS ─────────────────────
 *
 * `fromPlain` and NOT `recordDocFromStored`, which is the other reader in this app and reads a
 * leading "• " or "1. " back into the list block that wrote it. That is exactly right for a record
 * column, where the marker IS the stored form of a list — and exactly wrong here, where a list is
 * stored as JSON and never as a marker. Re-reading markers on this column would take an answer
 * somebody TYPED as "1. She learnt it from her mother" and reopen it as an ordered list, which is
 * then no longer plain prose, so the next save would silently promote a typed sentence into a
 * stored JSON document. The web reads this column with `fromPlainText`, one paragraph per line, and
 * this is that.
 *
 * A `{"blocks": …}` that will not parse is a truncated or corrupt value, and the honest reading is
 * the prose branch below: show the researcher the characters that are actually in the record rather
 * than an empty box that invites them to overwrite it with nothing.
 */
fun questionnaireAnswerDoc(stored: String?): RichDoc {
    val text = stored?.takeIf { it.isNotBlank() } ?: return EMPTY_RICH_DOC
    if (looksLikeRichDocument(text)) {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (parsed is JsonObject) return fromJson(parsed)
    }
    return fromPlain(text)
}

/**
 * A stored answer as the words a person wrote — prose returned untouched, a document flattened.
 *
 * **THIS IS THE READ BOUNDARY, and every surface on this handset that RENDERS a stored answer
 * without opening an editor over it has to go through here.** Without it the interview detail card
 * prints `{"blocks":[{"kind":"PARAGRAPH"…` where an artisan's answer belongs — not a crash, not a
 * 500, just the braces, in the place a researcher reads the answer back. `plainFromStoredRichText`
 * on the web is the same function and exists for the same surfaces.
 *
 * Returns `""` for null and for an empty column, so callers keep their own blank wording — the
 * detail card already draws nothing for a blank answer and must go on drawing nothing.
 *
 * The flattening is `toPlain`, the same flattener the report writers and the search index use, list
 * markers included, so what this prints is what the .docx would print rather than an approximation.
 */
fun questionnaireAnswerPlain(stored: String?): String {
    val text = stored?.takeIf { it.isNotBlank() } ?: return ""
    if (!looksLikeRichDocument(text)) return text
    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    if (parsed !is JsonObject) return text
    return toPlain(fromJson(parsed))
}

/**
 * A stored answer as the `JsonElement?` [com.designprototype.workshop.ui.designworkshop.RichTextEditor]
 * opens.
 *
 * Through the document and back out as canonical JSON rather than handed over as the raw string,
 * because `fromJson` reads a bare string as unformatted prose — which is right for this column and
 * still has to be spelled, since the editor's parameter is a `JsonElement?` and not a `String`.
 *
 * Null for an empty document, which is the editor's own spelling of "nothing has been written here".
 */
internal fun questionnaireAnswerSeed(stored: String): JsonElement? {
    val doc = questionnaireAnswerDoc(stored)
    return if (doc.blocks.isEmpty()) null else toJson(doc)
}

/**
 * The machine's words added to a stored answer, whatever shape that answer is in.
 *
 * ── THIS IS NOT DECORATION; IT IS THE BUG THAT WOULD OTHERWISE SHIP ───────────────────────────
 *
 * Both paths that put a transcript into an answer box append. Concatenating `" and she dyes it in
 * indigo"` onto the END OF A JSON STRING produces a value that is neither valid JSON nor readable
 * prose: the editor cannot parse it, so it falls through to the prose branch and shows the
 * researcher raw braces followed by the machine's sentence, and every downstream reader shows the
 * same. The web's `appendStoredParagraph` exists for exactly this and states it in the same words —
 * though its own interview form does not yet call it, which is reported rather than copied.
 *
 * ── THE PROSE BRANCH IS BYTE-FOR-BYTE WHAT IT ALWAYS WAS ──────────────────────────────────────
 *
 * [appendSpokenToRecord] — one space, only where one is missing. An unformatted answer, which is
 * almost every answer, takes exactly the path it took before this function existed.
 *
 * ── AND THE DOCUMENT BRANCH ADDS A PARAGRAPH RATHER THAN CONTINUING THE LAST BLOCK ────────────
 *
 * A space-join would be the closer analogue of the prose rule, and it is the wrong answer once
 * there is structure to land in: the last block of a formatted answer is quite often a bullet, a
 * table row or a photograph's caption, and running a fresh take onto the end of one of those files
 * the artisan's words inside a list item — or inside a table cell, where `toPlain` will later join
 * them with pipes. A new paragraph is the one placement that cannot land inside something the
 * researcher built. Nothing is lost by it: the words are all there, in order, in the box.
 *
 * One paragraph per LINE of the addition, matching [fromPlain], because a span holding a "\n" is
 * the one thing the serialiser has to repair and it repairs it by replacing the newline with a
 * space — which would run two takes together into a single sentence.
 */
fun questionnaireAnswerAppend(stored: String, addition: String): String {
    val extra = addition.trim()
    if (extra.isEmpty()) return stored
    if (!looksLikeRichDocument(stored)) return appendSpokenToRecord(stored, extra)
    val doc = questionnaireAnswerDoc(stored)
    val added = extra.split("\n")
        .filter { it.isNotBlank() }
        .map { RichBlock(spans = listOf(RichSpan(it.trim()))) }
    return questionnaireAnswerStored(RichDoc(blocks = doc.blocks + added))
}
