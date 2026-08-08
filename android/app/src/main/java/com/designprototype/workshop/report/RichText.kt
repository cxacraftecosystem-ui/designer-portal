package com.designprototype.workshop.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The portable rich-text document: one representation, five renderers, no HTML anywhere — a faithful
 * port of `backend/app/services/rich_text.py`.
 *
 * A designer writing the introduction to a workshop report needs bold, italic, underline, alignment,
 * and numbered and bulleted lists — the ordinary furniture of a document. That text has to survive
 * being typed on a phone, saved into a JSON column, printed into a .docx, drawn onto a PDF page by
 * two different renderers, and shown back in a browser preview.
 *
 * **It is stored as a structured document, never as HTML.** HTML would have been the quick answer and
 * is the wrong one, for three reasons that all end in the same place:
 *
 * 1. *The phone cannot render it.* [PdfWriter] draws onto a `Canvas` with a `TextPaint`. Handing it a
 *    fragment of HTML means shipping an HTML parser and a CSS cascade to a field phone, or it means
 *    the offline report silently loses every mark the designer applied. This file is the reason it
 *    needs neither.
 * 2. *It is an injection surface.* Stored HTML is attacker-controlled markup that four surfaces later
 *    interpret. A structured document has no `<script>` to smuggle, because there is no tag
 *    vocabulary at all — a mark is an enum member or it does not exist.
 * 3. *It cannot be validated.* `<b><i>x</b></i>` is a thing a contenteditable will produce, and no
 *    amount of care downstream makes it well-defined. The model below cannot express it.
 *
 * The shape is deliberately the smallest one that covers the requirement:
 *
 *     RichDoc
 *       └── blocks: RichBlock[]        PARAGRAPH | HEADING | BULLET_ITEM | ORDERED_ITEM | QUOTE
 *             ├── align                LEFT | CENTER | RIGHT | JUSTIFY
 *             ├── level                heading level (1-4), or list nesting depth (0-3)
 *             └── spans: RichSpan[]    text + marks
 *                   └── marks          BOLD | ITALIC | UNDERLINE | STRIKETHROUGH | CODE
 *                                      | SUPERSCRIPT | SUBSCRIPT | HIGHLIGHT
 *
 * That is a *flat* block list with a nesting depth, not a tree. A tree is what every rich-text library
 * produces and it is the wrong shape here: the DOCX and PDF writers both lay out one paragraph at a
 * time, so a tree would be flattened at the start of all four renderers and the four flattenings would
 * disagree about the edge cases. Flattening once, at the point the editor saves, means the renderers
 * cannot drift.
 *
 * [toJson] and [fromJson] are inverse. [toPlain] throws the marks away, for search, for CSV export and
 * for the completeness gate — which counts a field as filled on its text, never on its formatting.
 *
 * Every string entering this file passes [cleanText], for exactly the reason [ReportModel][cleanText]
 * documents: a lone surrogate from a phone that cut an emoji in half makes `word/document.xml` not
 * well-formed, and Word refuses the entire file rather than dropping the character. There is exactly
 * one `cleanText` in this app and it lives in `ReportModel.kt`; a second copy here would be a second
 * answer to "what is a legal character", and the two would drift the first time either was patched.
 *
 * THE POINT OF THIS FILE BEING A PORT RATHER THAN AN INDEPENDENT DESIGN: a document typed on the phone
 * and one typed in the browser must be the SAME JSON, byte for byte, because the same field is edited
 * from both and whichever wrote last wins. `backend/tests/test_rich_text.py` pins the round trip, the
 * sanitisation and the block mapping; the harness that proves this port matches runs those same
 * invariants against the Python module's own output.
 */

// --------------------------------------------------------------------------------------
// Budgets
// --------------------------------------------------------------------------------------

/**
 * A stored document larger than this is not prose a human typed, and letting it through means a report
 * generation that never finishes — on a phone, that is an export dialog that spins until the process
 * is killed. The limit is per FIELD and generous: the longest narrative field in the registry is a
 * cluster history, and 200 000 characters is around forty pages of it.
 */
const val MAX_DOCUMENT_CHARS = 200_000

const val MAX_BLOCKS = 2_000
const val MAX_HEADING_LEVEL = 4
const val MAX_LIST_DEPTH = 3

/**
 * Spans per block. A block with more than this many spans is not a paragraph, it is an editor that
 * emitted one span per keystroke; the cap bounds the work before the character budget can.
 */
private const val MAX_SPANS_PER_BLOCK = 512

/**
 * Table bounds. A table bigger than this is a spreadsheet somebody pasted, not prose with a grid in
 * it, and both are enforced on the way IN so no renderer ever meets a 400-column table. They must
 * match `rich_text.py`: a phone that accepted 20 columns where the server keeps 12 would write a
 * document the server then silently truncated.
 */
private const val MAX_TABLE_ROWS = 200
private const val MAX_TABLE_COLUMNS = 12
private const val MAX_SPANS_PER_CELL = 64

// --------------------------------------------------------------------------------------
// The model
// --------------------------------------------------------------------------------------

/** An inline mark. The whole vocabulary — there is no mechanism for adding one at runtime. */
@Serializable
enum class Mark {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    CODE,

    /**
     * The two a workshop report actually needs and that every renderer can carry: a footnote
     * marker, "m²", "H₂O", the ordinal in "3ʳᵈ warp".
     *
     * A CHARACTER CANNOT BE BOTH. Both marks on one span is a document no editor here can produce
     * and a hand-written value could; [RichSpan.subscript] resolves it by letting SUPERSCRIPT win,
     * in one place, so [DocxWriter] and [PdfWriter] cannot disagree about which one it was — the
     * same rule `rich_text.py` applies on the server.
     */
    SUPERSCRIPT,
    SUBSCRIPT,

    /**
     * One highlight, not a palette, and the colour is not the designer's to choose.
     *
     * `w:highlight` takes a CLOSED vocabulary of sixteen named colours — it is not an RGB value —
     * so a picker offering an arbitrary colour would store something Word cannot express and each
     * renderer would then approximate it differently. One fixed yellow ([HIGHLIGHT_FILL]) is what
     * all four renderers draw identically, and it is the only thing a highlight in a submitted
     * report ever means: "look at this".
     */
    HIGHLIGHT,
}

@Serializable
enum class BlockKind {
    PARAGRAPH,
    HEADING,
    BULLET_ITEM,
    ORDERED_ITEM,
    QUOTE,

    /**
     * A grid the designer built inside the prose. Its content lives in [RichBlock.rows], not in
     * [RichBlock.spans] — the one kind whose text is not a run of spans.
     *
     * IT IS HERE PRIMARILY SO THE PHONE CANNOT DESTROY ONE. `coerceKind` degrades an unrecognised
     * kind to PARAGRAPH, and the parser reads only `spans`, which a table leaves empty — so a
     * build that did not know this constant would open a field containing a table, find nothing in
     * it, and write the field back with the whole grid gone. That is silent data loss on a
     * document a designer may have spent an afternoon on, and it is triggered by the ordinary act
     * of opening the stage. Round-tripping it is not optional; editing it on the handset is.
     */
    TABLE,

    /**
     * A photograph placed WHERE THE DESIGNER PUT IT, rather than in the stage's gallery. The id is
     * in [RichBlock.media] and the caption is the block's [RichBlock.spans].
     *
     * Here for the same reason [TABLE] is: `coerceKind` degrades an unknown kind to PARAGRAPH and
     * the parser reads only `spans`, so a build that did not know this constant would open a field
     * containing an inline photograph, keep the caption, throw the picture away, and write the
     * field back. The designer would see a caption under nothing.
     */
    IMAGE;

    val isListItem: Boolean get() = this == BULLET_ITEM || this == ORDERED_ITEM
}

/** A run of text carrying zero or more marks. */
@Serializable
data class RichSpan(
    val text: String,
    val marks: Set<Mark> = emptySet(),
) {
    val bold: Boolean get() = Mark.BOLD in marks
    val italic: Boolean get() = Mark.ITALIC in marks
    val underline: Boolean get() = Mark.UNDERLINE in marks
    val strike: Boolean get() = Mark.STRIKETHROUGH in marks
    val code: Boolean get() = Mark.CODE in marks
    val superscript: Boolean get() = Mark.SUPERSCRIPT in marks

    // SUPERSCRIPT wins a span carrying both — see the note on the enum. Resolved on the READER
    // rather than on the way in, so the designer's marks are stored exactly as they applied them
    // and only the rendering is made single-valued.
    val subscript: Boolean get() = Mark.SUBSCRIPT in marks && Mark.SUPERSCRIPT !in marks
    val highlight: Boolean get() = Mark.HIGHLIGHT in marks
}

@Serializable
data class RichBlock(
    val kind: BlockKind = BlockKind.PARAGRAPH,
    val spans: List<RichSpan> = emptyList(),
    val align: Align = Align.LEFT,
    /**
     * Heading level 1-4 for [BlockKind.HEADING]; list nesting depth 0-3 for the two item kinds;
     * ignored otherwise and forced to 0 on the way in, so a paragraph can never carry one.
     */
    val level: Int = 0,
    /**
     * [BlockKind.TABLE] only, empty for every other kind. Rows of cells of spans, the FIRST row
     * being the header — the same reading the server gives it, which is what lets both sides map
     * the block onto one [TableBlock].
     */
    val rows: List<List<List<RichSpan>>> = emptyList(),
    /** [BlockKind.IMAGE] only: the media id, resolved through the same resolver as any picture. */
    val media: String = "",
    /** [BlockKind.IMAGE] only: printed width as a percentage of the text column, clamped 10-100. */
    val widthPct: Float = 70f,
) {
    val text: String
        get() =
            // A table's text is its cells, read left to right and top to bottom. It must be
            // defined: `isEmpty` reads it and the completeness gate reads `isEmpty`, so a stage
            // whose only content was a filled-in table would otherwise report as unfilled.
            if (kind == BlockKind.TABLE) {
                rows.joinToString("\n") { row ->
                    row.joinToString("\t") { cell -> cell.joinToString("") { it.text } }
                }
            } else {
                spans.joinToString("") { it.text }
            }

    // An IMAGE is never empty while it holds a media id, even with no caption: judging it on text
    // would make a field whose only content is a photograph read as unfilled to the gate.
    val isEmpty: Boolean get() = if (kind == BlockKind.IMAGE) media.isEmpty() else text.isBlank()

    /** The widest row. Rows are padded to this when the table is drawn, never before. */
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

@Serializable
data class RichDoc(
    val blocks: List<RichBlock> = emptyList(),
) {
    val isEmpty: Boolean get() = blocks.all { it.isEmpty }

    val text: String get() = blocks.joinToString("\n") { it.text }
}

/** The canonical empty document. Shared, because every miss in [fromJson] returns it. */
val EMPTY_RICH_DOC = RichDoc()

// --------------------------------------------------------------------------------------
// Codepoint arithmetic — why the budget is not `String.take`
// --------------------------------------------------------------------------------------

/**
 * The number of Unicode codepoints in [text], which is what the server counts.
 *
 * Python strings are sequences of codepoints, so `len(s)` there is a codepoint count. A Kotlin String
 * is UTF-16, so `s.length` counts an emoji as TWO. Counting the Kotlin way would make the same
 * document truncate at a different place on the phone than on the server — and two clients disagreeing
 * about where a 200 000-character narrative ends is a field that changes every time it is saved from
 * the other surface.
 */
private fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

/**
 * The first [limit] CODEPOINTS of [text].
 *
 * `text.take(n)` would cut at a UTF-16 index, and a cut that lands between the two halves of a
 * surrogate pair leaves a LONE SURROGATE at the end of the string — precisely the character
 * [cleanText] exists to remove, reintroduced downstream of it, in a value that then goes straight into
 * `word/document.xml`. Word's response to that is to refuse the whole file. Cutting on a codepoint
 * boundary cannot produce one.
 */
private fun takeCodePoints(text: String, limit: Int): String {
    if (limit <= 0) return ""
    // A string whose UTF-16 length already fits cannot have more codepoints than that.
    if (text.length <= limit) return text
    val count = text.codePointCount(0, text.length)
    if (count <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit))
}

// --------------------------------------------------------------------------------------
// Parsing — every path in is forgiving, because three clients write these
// --------------------------------------------------------------------------------------

/**
 * A JSON scalar as the plain Kotlin value [cleanText] expects.
 *
 * A composite (an object or an array) where a string was expected is a client bug with no salvageable
 * text in it, so it becomes `null` and therefore an empty string. The server stringifies it instead
 * and would print a Python dict repr into a government report; that is the one place this port is
 * deliberately less faithful, and it is less faithful in the direction of printing nothing.
 */
private fun scalarOf(element: JsonElement?): Any? = when {
    element == null -> null
    element is JsonNull -> null
    element is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "true" -> true
        element.content == "false" -> false
        else -> element.content
    }
    else -> null
}

/**
 * The marks in [raw] that this build recognises; anything else is dropped in silence.
 *
 * Dropping rather than raising is the same rule the stage registry follows for unknown field keys: a
 * phone one release ahead may apply a mark this build has never heard of, and losing the mark is a
 * cosmetic regression while losing the paragraph is data loss.
 */
private fun coerceMarks(raw: JsonElement?): Set<Mark> {
    if (raw !is JsonArray) return emptySet()
    val found = LinkedHashSet<Mark>()
    for (item in raw) {
        val token = (scalarOf(item) as? String)?.uppercase() ?: continue
        val mark = MARK_BY_NAME[token] ?: continue
        found.add(mark)
    }
    return found
}

private val MARK_BY_NAME: Map<String, Mark> = Mark.entries.associateBy { it.name }
private val KIND_BY_NAME: Map<String, BlockKind> = BlockKind.entries.associateBy { it.name }
private val ALIGN_BY_NAME: Map<String, Align> = Align.entries.associateBy { it.name }

private fun coerceAlign(raw: JsonElement?): Align {
    val token = (scalarOf(raw) as? String)?.uppercase() ?: return Align.LEFT
    return ALIGN_BY_NAME[token] ?: Align.LEFT
}

private fun coerceKind(raw: JsonElement?): BlockKind {
    val token = (scalarOf(raw) as? String)?.uppercase() ?: return BlockKind.PARAGRAPH
    return KIND_BY_NAME[token] ?: BlockKind.PARAGRAPH
}

/**
 * Rows of cells of spans, bounded, forgiving, and sharing the document's character budget.
 *
 * The port of the server's `_coerce_rows`, and forgiving in the same way as everything else here: a
 * row that is not an array is skipped, a cell that is a bare string becomes one unmarked span, and
 * a ragged table stays ragged rather than being rejected — `columnCount` squares it off at drawing
 * time. Returns the rows and what is left of the budget, because the budget is the DOCUMENT's and a
 * table must not be able to spend more of it than the paragraphs around it.
 */
private fun coerceRows(
    raw: JsonElement?,
    startBudget: Int,
): Pair<List<List<List<RichSpan>>>, Int> {
    val array = raw as? JsonArray ?: return emptyList<List<List<RichSpan>>>() to startBudget
    var budget = startBudget
    val rows = ArrayList<List<List<RichSpan>>>()
    outer@ for (rowRaw in array.take(MAX_TABLE_ROWS)) {
        val rowArray = rowRaw as? JsonArray ?: continue
        val cells = ArrayList<List<RichSpan>>()
        for (cellRaw in rowArray.take(MAX_TABLE_COLUMNS)) {
            val spans = ArrayList<RichSpan>()
            val items: List<JsonElement> = when {
                cellRaw is JsonPrimitive && cellRaw.isString -> listOf(cellRaw)
                cellRaw is JsonArray -> cellRaw
                else -> emptyList()
            }
            for (spanRaw in items.take(MAX_SPANS_PER_CELL)) {
                var text: String
                val marks: Set<Mark>
                when {
                    spanRaw is JsonPrimitive && spanRaw.isString -> {
                        text = cleanText(spanRaw.content)
                        marks = emptySet()
                    }
                    spanRaw is JsonObject -> {
                        text = cleanText(scalarOf(spanRaw["text"]))
                        marks = coerceMarks(spanRaw["marks"])
                    }
                    else -> continue
                }
                text = text.replace("\n", " ")
                if (budget <= 0) break
                text = takeCodePoints(text, budget)
                budget -= codePointLength(text)
                if (text.isNotEmpty()) spans.add(RichSpan(text = text, marks = marks))
            }
            cells.add(spans)
        }
        if (cells.isNotEmpty()) rows.add(cells)
        if (budget <= 0) break@outer
    }
    // Drop trailing rows that are entirely blank — what an editor leaves behind when a designer
    // adds a row and then thinks better of it.
    while (rows.isNotEmpty() && rows.last().none { cell -> cell.any { it.text.isNotBlank() } }) {
        rows.removeAt(rows.size - 1)
    }
    return rows to budget
}

/**
 * A stored `level`, or 0 when the value is not a number.
 *
 * The `isString` guard is not pedantry: `{"level": "3"}` is what a form control that never coerced its
 * input produces, and the server's `isinstance(level, (int, float))` rejects it. Accepting it here
 * would give the phone a level-3 heading where the server has a level-1 one, in the same field.
 * `true` is rejected on both sides too — in Python because `bool` is an `int` subclass and the check
 * excludes it explicitly, here because a boolean has no numeric value.
 */
private fun coerceLevel(raw: JsonElement?): Int {
    val primitive = raw as? JsonPrimitive ?: return 0
    if (primitive is JsonNull || primitive.isString) return 0
    val value = primitive.content.toDoubleOrNull() ?: return 0
    // Python's int() truncates toward zero, and so does Kotlin's toInt().
    return value.toInt()
}

/**
 * Build a document from whatever a client stored, never throwing.
 *
 * Accepts three shapes, because all three genuinely occur:
 *
 * * the canonical `{"blocks": [...]}` this file emits;
 * * a bare list of blocks, which is what a client that stored `doc.blocks` produces;
 * * a plain string, which is EVERY value written before a field was promoted from LONG_TEXT to
 *   RICH_TEXT. That last one is the important case: promoting a field must not blank the prose already
 *   stored under it, so a string is read as an unformatted document, with its blank lines becoming
 *   paragraph breaks exactly as the plain-text renderer treated them. On this device that migration is
 *   not a server-side one-off — a draft sitting in `WorkshopDraftStore` from before the promotion is
 *   read back by whatever build the phone has now.
 */
fun fromJson(raw: JsonElement?): RichDoc {
    if (raw == null || raw is JsonNull) return EMPTY_RICH_DOC
    // A scalar is preserved as text rather than discarded. A number where a document was expected is a
    // client bug, but the value is still something a designer typed, and silently dropping it loses
    // data to fix a formatting mistake.
    if (raw is JsonPrimitive) return fromPlain(raw.content)

    val blocksRaw: JsonArray = when (raw) {
        is JsonObject -> raw["blocks"] as? JsonArray ?: return EMPTY_RICH_DOC
        is JsonArray -> raw
        else -> return EMPTY_RICH_DOC
    }

    val blocks = ArrayList<RichBlock>()
    var budget = MAX_DOCUMENT_CHARS
    for (entry in blocksRaw.take(MAX_BLOCKS)) {
        if (entry !is JsonObject) {
            // A bare string in the block list is a paragraph; anything else is skipped.
            val loose = entry as? JsonPrimitive
            if (loose != null && loose.isString) {
                val text = takeCodePoints(cleanText(loose.content), budget)
                budget -= codePointLength(text)
                if (text.isNotEmpty()) blocks.add(RichBlock(spans = listOf(RichSpan(text))))
            }
            continue
        }

        val kind = coerceKind(entry["kind"])
        val spans = ArrayList<RichSpan>()
        val spansRaw = (entry["spans"] as? JsonArray) ?: JsonArray(emptyList())
        for (spanRaw in spansRaw.take(MAX_SPANS_PER_BLOCK)) {
            var text: String
            var marks: Set<Mark>
            when {
                spanRaw is JsonPrimitive && spanRaw !is JsonNull && spanRaw.isString -> {
                    text = cleanText(spanRaw.content)
                    marks = emptySet()
                }
                spanRaw is JsonObject -> {
                    text = cleanText(scalarOf(spanRaw["text"]))
                    marks = coerceMarks(spanRaw["marks"])
                }
                else -> continue
            }
            // A newline inside a span would make one block render as two, which no renderer expects;
            // the editor emits a new block for a new line, so this only fires on hand-written or
            // migrated data. It has to happen BEFORE the budget is charged, or the same document costs
            // a different number of characters depending on how its line breaks were spelled.
            text = text.replace("\n", " ")
            if (budget <= 0) break
            text = takeCodePoints(text, budget)
            budget -= codePointLength(text)
            if (text.isNotEmpty()) spans.add(RichSpan(text = text, marks = marks))
        }

        var level = coerceLevel(entry["level"])
        level = when {
            // A heading with no level is a level 1, not a level 0 that no renderer has a style for —
            // both writers index a four-element array of sizes by `level - 1`.
            kind == BlockKind.HEADING -> maxOf(1, minOf(MAX_HEADING_LEVEL, if (level == 0) 1 else level))
            kind.isListItem -> maxOf(0, minOf(MAX_LIST_DEPTH, level))
            else -> 0
        }

        var media = ""
        var widthPct = 70f
        if (kind == BlockKind.IMAGE) {
            media = cleanText(scalarOf(entry["media"])).trim().take(64)
            // An IMAGE with no id is not a picture — dropped, exactly as an empty TABLE is.
            if (media.isEmpty()) continue
            val rawWidth = (entry["widthPct"] as? JsonPrimitive)?.content?.toFloatOrNull()
            if (rawWidth != null) widthPct = maxOf(10f, minOf(100f, rawWidth))
        }

        var rows: List<List<List<RichSpan>>> = emptyList()
        if (kind == BlockKind.TABLE) {
            val parsed = coerceRows(entry["rows"], budget)
            rows = parsed.first
            budget = parsed.second
            // A TABLE with nothing in it is not a table. Dropped rather than kept as an empty grid
            // because `isEmpty` judges on text: a document whose only block was a 3x3 of blank
            // cells would otherwise count as filled and pass the completeness gate.
            if (rows.isEmpty()) continue
        }

        blocks.add(
            RichBlock(
                kind = kind,
                spans = spans,
                align = coerceAlign(entry["align"]),
                level = level,
                rows = rows,
                media = media,
                widthPct = widthPct,
            )
        )
        if (budget <= 0) break
    }
    return RichDoc(blocks = blocks)
}

/**
 * A plain string as an unformatted document, one paragraph per line.
 *
 * Blank lines separate paragraphs and are not themselves kept, matching how [DocumentBuilder.para] has
 * always split a textarea's contents.
 */
fun fromPlain(text: Any?): RichDoc {
    val cleaned = takeCodePoints(cleanText(text), MAX_DOCUMENT_CHARS)
    if (cleaned.isBlank()) return EMPTY_RICH_DOC
    val blocks = cleaned.split("\n")
        .filter { it.isNotBlank() }
        .map { RichBlock(spans = listOf(RichSpan(it.trim()))) }
    return RichDoc(blocks = blocks.take(MAX_BLOCKS))
}

// --------------------------------------------------------------------------------------
// Serialising
// --------------------------------------------------------------------------------------

/**
 * The canonical storage form. Defaults are omitted to keep the JSON column small.
 *
 * The key ORDER matters more than it looks: this JSON is compared against the server's byte for byte
 * by the port's own harness, and it is what a sync conflict resolver diffs. `kind`, `spans`, then the
 * optional `align` and `level` — the same order `to_json` writes them in.
 *
 * Marks are sorted by NAME, not by declaration order, because Python emits `sorted(...)` of the mark
 * values and an unsorted set would produce `["ITALIC","BOLD"]` on the phone against `["BOLD","ITALIC"]`
 * on the server for the same document — two different strings in the column for the same formatting.
 */
fun toJson(doc: RichDoc): JsonObject = buildJsonObject {
    putJsonArray("blocks") {
        for (block in doc.blocks) {
            addJsonObject {
                put("kind", block.kind.name)
                putJsonArray("spans") {
                    for (span in block.spans) {
                        addJsonObject {
                            put("text", span.text)
                            if (span.marks.isNotEmpty()) {
                                putJsonArray("marks") {
                                    for (name in span.marks.map { it.name }.sorted()) add(name)
                                }
                            }
                        }
                    }
                }
                if (block.align != Align.LEFT) put("align", block.align.name)
                if (block.level != 0) put("level", block.level)
                if (block.kind == BlockKind.IMAGE) {
                    put("media", block.media)
                    // Written only when it differs from the default, matching `to_json`.
                    if (block.widthPct != 70f) put("widthPct", block.widthPct)
                }
                if (block.kind == BlockKind.TABLE) {
                    // Emitted in the same position and the same shape as the server's `to_json`,
                    // and `spans` above stays present-and-empty rather than being omitted: the
                    // oracle compares these objects key for key, and an absent key is a different
                    // document from an empty one.
                    putJsonArray("rows") {
                        for (row in block.rows) {
                            addJsonArray {
                                for (cell in row) {
                                    addJsonArray {
                                        for (span in cell) {
                                            addJsonObject {
                                                put("text", span.text)
                                                if (span.marks.isNotEmpty()) {
                                                    putJsonArray("marks") {
                                                        for (name in span.marks.map { it.name }.sorted()) add(name)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The text with every mark discarded.
 *
 * List items keep a visible marker, because this string is what a CSV export and a search index
 * receive, and a bulleted list flattened to bare lines reads as one run-on sentence.
 *
 * The ordered counter RESETS on any block that is not an ordered item. Two separate numbered lists with
 * a paragraph between them must both start at 1; a counter that only ever climbed printed the second
 * list as "4. 5. 6." and made it read as a continuation of the first.
 */
fun toPlain(doc: RichDoc): String {
    val lines = ArrayList<String>()
    var ordinal = 0
    for (block in doc.blocks) {
        if (block.kind == BlockKind.IMAGE) {
            // The CAPTION is the text of a photograph; a caption-less picture contributes nothing.
            ordinal = 0
            val caption = block.text.trim()
            if (caption.isNotEmpty()) lines.add(caption)
            continue
        }
        if (block.kind == BlockKind.TABLE) {
            // One line per row, cells joined by " | ". A tab would be invisible in a search index
            // and would make two adjacent cells read as one word in a CSV export.
            ordinal = 0
            for (row in block.rows) {
                lines.add(row.joinToString(" | ") { cell ->
                    cell.joinToString("") { it.text }.trim()
                })
            }
            continue
        }
        val text = block.text.trim()
        if (block.kind == BlockKind.ORDERED_ITEM) {
            ordinal += 1
            lines.add("  ".repeat(block.level) + "$ordinal. " + text)
            continue
        }
        ordinal = 0
        if (block.kind == BlockKind.BULLET_ITEM) {
            lines.add("  ".repeat(block.level) + "• " + text)
        } else {
            lines.add(text)
        }
    }
    return lines.joinToString("\n").trim()
}

fun toPlain(raw: JsonElement?): String = toPlain(fromJson(raw))

/**
 * Whether a stored value counts as unfilled, for the completeness gate.
 *
 * A document of empty paragraphs is empty. This is why the gate reads the TEXT and not the presence of
 * the JSON: an editor that has been focused and left alone still saves
 * `{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}`, and counting that as a filled required field would
 * let a designer submit a report with a blank introduction at 100% complete.
 */
fun isEmptyDocument(raw: JsonElement?): Boolean {
    if (raw == null || raw is JsonNull) return true
    if (raw is JsonPrimitive && raw.isString) return raw.content.isBlank()
    return fromJson(raw).isEmpty
}

/** A one-line plain-text preview, for a list row or a collection's label field. */
fun summary(raw: JsonElement?, limit: Int = 160): String = summaryOf(toPlain(raw), limit)

fun summary(doc: RichDoc, limit: Int = 160): String = summaryOf(toPlain(doc), limit)

private fun summaryOf(plain: String, limit: Int): String {
    val text = plain.split(WHITESPACE_RUN).filter { it.isNotEmpty() }.joinToString(" ")
    if (codePointLength(text) <= limit) return text
    // Truncated on a CODEPOINT boundary: a label cut through an emoji is a lone surrogate, and this
    // string is used as a collection row's title, which is written straight into the report.
    return takeCodePoints(text, limit - 1).trimEnd() + "…"
}

private val WHITESPACE_RUN = Regex("\\s+")

// --------------------------------------------------------------------------------------
// Rendering into the report model
// --------------------------------------------------------------------------------------

private val STYLE_FOR_KIND = mapOf(
    BlockKind.PARAGRAPH to ParaStyle.BODY,
    BlockKind.QUOTE to ParaStyle.QUOTE,
)

/**
 * One span as report-model runs, split at every script boundary.
 *
 * The split has to happen HERE rather than in the renderers: a span is a unit of *formatting* and a
 * [Run] is a unit of *formatting and font*, and a designer who bolds a phrase containing both English
 * and Odia has produced one span that must become two runs or half of it renders as boxes. This calls
 * [splitByScript] — the one in `ReportModel.kt` — for the same reason there is only one [cleanText]:
 * a second script splitter would put the boundary in a different place than [DocxWriter] and
 * [PdfWriter] expect, and the same sentence would be one run in the .docx and two in the PDF.
 */
private fun runsFor(span: RichSpan): List<Run> =
    splitByScript(span.text).map { (text, script) ->
        Run(
            text = text,
            bold = span.bold,
            italic = span.italic,
            underline = span.underline,
            strike = span.strike,
            script = script,
            superscript = span.superscript,
            subscript = span.subscript,
            highlight = span.highlight,
        )
    }

/**
 * Render a stored rich-text value into report-model blocks.
 *
 * Consecutive list items of the same kind are merged into ONE [BulletListBlock], because that is what
 * both writers want: a .docx list is a run of paragraphs sharing a numbering id, and emitting one block
 * per item would restart the numbering at every line — a five-point recommendation list printed as
 * "1. 1. 1. 1. 1.". [PdfWriter.blockBullets] has the same property for the same reason: it numbers from
 * the item's index WITHIN the block, so one block per item gives five items all numbered 1.
 *
 * [baseStyle] is carried for parity with the server's signature. It is currently unreachable — every
 * one of the five kinds is either handled before the fallback or present in [STYLE_FOR_KIND] — and it
 * stays only so that adding a sixth kind lands on the caller's choice rather than on BODY.
 */
fun toReportBlocks(
    doc: RichDoc,
    baseStyle: ParaStyle = ParaStyle.BODY,
    resolveMedia: ((String) -> ImageRef?)? = null,
): List<Block> {
    val out = ArrayList<Block>()
    var pending = ArrayList<List<Run>>()
    var pendingOrdered = false

    fun flush() {
        if (pending.isNotEmpty()) {
            out.add(BulletListBlock(items = pending, ordered = pendingOrdered))
            pending = ArrayList()
        }
    }

    for (block in doc.blocks) {
        if (block.isEmpty) {
            // An empty paragraph between two list items must not split the list, but an empty paragraph
            // anywhere else is the designer's own spacing and is simply dropped — printing it would put
            // a blank line into a government report for every stray return.
            continue
        }
        if (block.kind == BlockKind.TABLE) {
            // A table ends any list it interrupts, exactly as a paragraph does.
            flush()
            tableBlockOf(block)?.let { out.add(it) }
            continue
        }

        if (block.kind == BlockKind.IMAGE) {
            flush()
            // No resolver, or a media row that has gone, means NO picture and no caption. A caption
            // with nothing above it reads as a lost paragraph, and a placeholder box in a document
            // submitted to a ministry is worse than an honest gap.
            val ref = resolveMedia?.invoke(block.media) ?: continue
            out.add(
                ImageBlock(
                    image = ref,
                    widthPct = block.widthPct,
                    align = if (block.align == Align.LEFT) Align.CENTER else block.align,
                    caption = block.text.trim(),
                )
            )
            continue
        }

        val runs = block.spans.flatMap { runsFor(it) }
        if (runs.isEmpty()) continue

        if (block.kind.isListItem) {
            val ordered = block.kind == BlockKind.ORDERED_ITEM
            if (pending.isNotEmpty() && ordered != pendingOrdered) flush()
            pendingOrdered = ordered
            pending.add(runs)
            continue
        }

        flush()
        if (block.kind == BlockKind.HEADING) {
            val text = block.text.trim()
            out.add(
                HeadingBlock(
                    level = maxOf(1, minOf(MAX_HEADING_LEVEL, if (block.level == 0) 1 else block.level)),
                    runs = runs,
                    number = "",
                    // No section number: a heading a designer typed inside a narrative field is not
                    // part of the report's own "3.2" outline, and numbering it would give the document
                    // two competing numbering schemes. The ordinal keeps the bookmark unique.
                    bookmark = bookmarkId("", text, out.size),
                )
            )
            continue
        }

        out.add(
            ParagraphBlock(
                runs = runs,
                style = STYLE_FOR_KIND[block.kind] ?: baseStyle,
                align = block.align,
            )
        )
    }

    flush()
    return out
}

fun toReportBlocks(
    raw: JsonElement?,
    baseStyle: ParaStyle = ParaStyle.BODY,
    resolveMedia: ((String) -> ImageRef?)? = null,
): List<Block> = toReportBlocks(fromJson(raw), baseStyle, resolveMedia)

/**
 * One rich TABLE as the report model's own [TableBlock], or null when there is nothing to draw.
 *
 * THE FIRST ROW IS THE HEADER — the same mapping the server's `_table_block` makes, and the reason
 * both sides reuse [TableBlock] rather than inventing a second table shape: a new block type would
 * have meant new drawing paths in the DOCX writer, the PDF writer and the browser preview, and
 * three more chances for them to disagree about a border.
 *
 * The widths are equal and computed to sum to EXACTLY 100. [TableBlock]'s own `init` refuses a set
 * that does not, and it is right to — 100/3 is 33.33 three times and sums to 99.99 — so the last
 * column takes the remainder rather than the quotient. Rounded to two decimals with the same
 * arithmetic as the Python, because a phone that produced 33.34/33.33/33.33 where the server
 * produced 33.33/33.33/33.34 would draw a visibly different table from the same document.
 */
private fun tableBlockOf(block: RichBlock): TableBlock? {
    val width = block.columnCount
    if (block.rows.isEmpty() || width == 0) return null

    fun cellRuns(row: List<List<RichSpan>>, index: Int): List<Run> =
        // Ragged rows are padded HERE rather than at parse time, so what the designer typed is what
        // is stored and only the drawing is squared off.
        (row.getOrNull(index) ?: emptyList()).flatMap { runsFor(it) }

    val header = block.rows.first()
    val body = block.rows.drop(1)

    val each = Math.round(100.0 / width * 100.0) / 100.0
    val widths = MutableList(width) { each }
    widths[width - 1] = Math.round((100.0 - each * (width - 1)) * 100.0) / 100.0

    val columns = (0 until width).map { i ->
        TableColumn(
            header = (header.getOrNull(i) ?: emptyList()).joinToString("") { it.text }.trim(),
            widthPct = widths[i].toFloat(),
        )
    }
    return TableBlock(
        columns = columns,
        rows = body.map { row -> (0 until width).map { i -> cellRuns(row, i) } },
    )
}

/**
 * The whole document as a single run sequence, for somewhere a block cannot go.
 *
 * A table cell, for instance: a `w:tc` holds block-level elements but [TableBlock] models a cell as
 * runs, not blocks, so a rich-text field rendered inside one keeps its marks and loses its paragraph
 * structure to single spaces. Losing the structure is the right trade — the alternative is a nested
 * table, which Word lays out unpredictably and [PdfWriter] cannot lay out at all — but it IS a loss, so
 * this is deliberately a separate function rather than the default path.
 *
 * The ordered marker is numbered from the block's index in the WHOLE document, not from a running count
 * of ordered items. That is the server's behaviour and it is quirky — a cell whose third block is the
 * first ordered item prints "3." — but a cell is one line of prose and the two surfaces printing
 * different numbers there is worse than both printing the same odd one.
 */
fun plainRuns(doc: RichDoc): List<Run> {
    val runs = ArrayList<Run>()
    doc.blocks.forEachIndexed { index, block ->
        if (block.isEmpty) return@forEachIndexed
        if (runs.isNotEmpty()) runs.add(Run(text = " "))
        if (block.kind == BlockKind.BULLET_ITEM) {
            runs.add(Run(text = "• "))
        } else if (block.kind == BlockKind.ORDERED_ITEM) {
            runs.add(Run(text = "${index + 1}. "))
        }
        for (span in block.spans) runs.addAll(runsFor(span))
    }
    // `runsOf("")` is itself empty, so an empty document still yields an empty list here — exactly as
    // on the server. The call is kept rather than inlined because the day `runsOf` learns to return a
    // placeholder run, both surfaces must learn it together.
    return runs.ifEmpty { runsOf("") }
}

fun plainRuns(raw: JsonElement?): List<Run> = plainRuns(fromJson(raw))
