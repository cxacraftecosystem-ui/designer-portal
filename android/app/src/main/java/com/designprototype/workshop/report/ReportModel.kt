package com.designprototype.workshop.report

import kotlinx.serialization.Serializable

/**
 * The renderer-agnostic report document model — a faithful port of
 * `backend/app/services/report_model.py`.
 *
 * A generated workshop report is built in two steps that are deliberately kept apart:
 *
 *     workshop data + template  ->  ReportDocument  ->  .docx / .pdf
 *
 * [ReportDocument] is the waist of that hourglass. It is a plain tree of immutable data classes
 * describing *what the report says* — a cover, headings, prose, tables, photo grids, cost sheets —
 * and says nothing about *how any of it is drawn*. On this device two renderers consume it,
 * [DocxWriter] and [PdfWriter], and on the server two more do (`report_docx.py`, `report_pdf.py`).
 *
 * Because the Kotlin renderers are a PORT rather than a shared library, this model is the only
 * thing keeping the four outputs from drifting. Two rules protect that, and both are the reason
 * this file exists at all rather than the blocks being `JsonObject`s:
 *
 * **Sizes are relative, never absolute.** No block carries points, twips, EMUs or pixels. Column
 * widths are percentages of the text column; image widths are a fraction of it. A renderer
 * multiplies by whatever its own page geometry is. The first draft of the server model carried
 * centimetres, and the DOCX and PDF of the same workshop disagreed about every table width because
 * Word's usable column is the page minus its margins while the PDF frame had already subtracted
 * them — the same number meant two different widths. Percentages cannot express that bug.
 *
 * **Every string reaches a renderer through [cleanText] and nothing else.** `word/document.xml` is
 * XML 1.0, which admits only #x9, #xA, #xD and the printable ranges. A lone surrogate from a phone
 * that cut an emoji in half serialises to a numeric reference no XML parser will accept, and Word
 * responds by refusing to open the file at all rather than by dropping the character. The
 * constructor helpers below call [cleanText] for you, which is why they take `Any?` and not runs;
 * build a block by hand and bypass it and the failure reappears, in a file the researcher only
 * opens after leaving the field.
 *
 * Every type here is `@Serializable`: a report the device could not export (no storage, a kill
 * mid-write) is persisted as JSON and retried, using the same kotlinx-only, atomic-write mechanism
 * `OfflineOutbox` and `StagedJournal` use. That is also why [Block] is a sealed interface — a
 * polymorphic list needs a closed hierarchy, and a closed hierarchy is what makes the `when` in
 * each renderer exhaustive without an `else` that would silently swallow a new block type.
 */

// --------------------------------------------------------------------------------------
// The single door
// --------------------------------------------------------------------------------------

/**
 * Is [cp] a codepoint XML 1.0 §2.2 will carry?
 *
 * Everything outside these ranges — the C0 controls other than tab/LF/CR, the surrogate block
 * D800-DFFF, and the two noncharacters FFFE/FFFF — makes the part not well-formed. A Kotlin String
 * can hold all of them; a document part cannot carry any.
 *
 * Note what the last range does for free: a codepoint at or above 0x10000 can only have been
 * assembled from a VALID surrogate pair, so every astral character (an emoji, a rare CJK ideograph)
 * is legal and kept. The surrogate block itself is absent from every range, so a LONE surrogate —
 * the half-emoji a truncating text field produces — can never pass.
 */
private fun isXmlLegal(cp: Int): Boolean =
    cp == 0x9 || cp == 0xA || cp == 0xD ||
        (cp in 0x20..0xD7FF) || (cp in 0xE000..0xFFFD) || (cp in 0x10000..0x10FFFF)

/**
 * Coerce [value] to a string that can be written into a document part.
 *
 * Non-strings are stringified (`null` becomes an empty string, not the word "null" — a missing
 * optional field must leave a blank cell, and every renderer would otherwise print "null" into the
 * report). Line endings collapse to `\n`. Codepoints XML cannot carry are dropped rather than
 * replaced, because a replacement glyph in a government report reads as a data-entry error while a
 * dropped half-emoji reads as nothing at all.
 *
 * THE SURROGATE HANDLING BELOW IS THE SINGLE MOST IMPORTANT DETAIL IN THIS FILE. Python strings are
 * sequences of codepoints and its version of this function is a one-line regular expression; Kotlin
 * strings are UTF-16, so an astral character is TWO `Char`s and a naive per-`Char` filter deletes
 * both halves of every legal emoji (they are in the surrogate block) while a naive per-`Char` pass
 * that keeps the surrogate block lets a LONE surrogate through into the XML. Both were shipped, in
 * that order. The loop iterates by CODE POINT: a valid pair is judged as the one character it is
 * and kept whole, an unpaired half is dropped, and the two halves of a kept pair are re-emitted
 * together so the output is still well-formed UTF-16.
 *
 * `Boolean` is intercepted before the `toString()` branch because "true"/"false" is never what a
 * report wants — Yes/No is what the form showed the researcher. (In Python this ordering is
 * load-bearing for a second reason: `bool` is an `int` subclass there.)
 */
fun cleanText(value: Any?): String {
    if (value == null) return ""
    if (value is Boolean) return if (value) "Yes" else "No"
    val raw = value as? String ?: value.toString()
    if (raw.isEmpty()) return ""

    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        // Line endings first. Word renders a lone CR and a CRLF differently inside a run;
        // normalising here means no renderer ever has to agree on which one it was handed.
        // U+2028/U+2029 arrive in pasted web text and are separators no document format reflows
        // correctly, so they become ordinary newlines too.
        if (c == '\r') {
            out.append('\n')
            i++
            if (i < raw.length && raw[i] == '\n') i++
            continue
        }
        if (c == '\u2028' || c == '\u2029') {
            out.append('\n')
            i++
            continue
        }
        if (Character.isHighSurrogate(c)) {
            if (i + 1 < raw.length && Character.isLowSurrogate(raw[i + 1])) {
                // A legal astral character. Judge the assembled codepoint, keep both halves.
                if (isXmlLegal(Character.toCodePoint(c, raw[i + 1]))) {
                    out.append(c).append(raw[i + 1])
                }
                i += 2
                continue
            }
            // A high surrogate with nothing after it: not a character at all. Dropped.
            i++
            continue
        }
        if (Character.isLowSurrogate(c)) {
            // A low surrogate reached here means the high half was missing or already consumed.
            i++
            continue
        }
        if (isXmlLegal(c.code)) out.append(c)
        i++
    }
    return out.toString()
}

// --------------------------------------------------------------------------------------
// Script detection — the reason craft names are not boxes
// --------------------------------------------------------------------------------------

/**
 * Which writing system a run of text needs a font for.
 *
 * `localName` fields hold Devanagari, Odia, Bengali and Gujarati verbatim: the server's title-caser
 * returns non-Latin scripts untouched by rule, so whatever the artisan typed is what the report
 * must print. DOCX can only *name* a font and hope the reader has it, so [DocxWriter] names a
 * complex-script font on those runs via `w:cs` — the one hook Word offers. [PdfWriter] needs the
 * tag far less (Android's own fallback chain finds Noto for any script) but still uses it to keep
 * the two files' run structure identical.
 */
@Serializable
enum class Script {
    LATIN,
    DEVANAGARI,
    BENGALI,
    ODIA,
    GUJARATI,
    TAMIL,
    TELUGU,
    KANNADA,
    MALAYALAM,
    GURMUKHI,
    OTHER,
}

private class ScriptRange(val lo: Int, val hi: Int, val script: Script)

private val SCRIPT_RANGES = arrayOf(
    ScriptRange(0x0900, 0x097F, Script.DEVANAGARI),
    ScriptRange(0x0980, 0x09FF, Script.BENGALI),
    ScriptRange(0x0A00, 0x0A7F, Script.GURMUKHI),
    ScriptRange(0x0A80, 0x0AFF, Script.GUJARATI),
    ScriptRange(0x0B00, 0x0B7F, Script.ODIA),
    ScriptRange(0x0B80, 0x0BFF, Script.TAMIL),
    ScriptRange(0x0C00, 0x0C7F, Script.TELUGU),
    ScriptRange(0x0C80, 0x0CFF, Script.KANNADA),
    ScriptRange(0x0D00, 0x0D7F, Script.MALAYALAM),
)

/**
 * Unicode general categories with no script of their own: combining marks, spaces, punctuation and
 * format controls. They render identically in every font the report uses, so they must attach to
 * whichever run they abut instead of splitting it.
 *
 * Checked only AFTER the block ranges above, because Devanagari digits (U+0966-096F, category Nd)
 * and Indic vowel signs (Mn/Mc) live inside their script's block and belong to it — resolving those
 * as neutral would hand them to a neighbouring Latin run and print them as tofu.
 */
private val NEUTRAL_CATEGORIES: Set<Int> = setOf(
    Character.NON_SPACING_MARK.toInt(),          // Mn
    Character.COMBINING_SPACING_MARK.toInt(),    // Mc
    Character.ENCLOSING_MARK.toInt(),            // Me
    Character.SPACE_SEPARATOR.toInt(),           // Zs
    Character.LINE_SEPARATOR.toInt(),            // Zl
    Character.PARAGRAPH_SEPARATOR.toInt(),       // Zp
    Character.CONTROL.toInt(),                   // Cc
    Character.FORMAT.toInt(),                    // Cf
    Character.DASH_PUNCTUATION.toInt(),          // Pd
    Character.START_PUNCTUATION.toInt(),         // Ps
    Character.END_PUNCTUATION.toInt(),           // Pe
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), // Pi
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),   // Pf
    Character.OTHER_PUNCTUATION.toInt(),         // Po
    Character.CONNECTOR_PUNCTUATION.toInt(),     // Pc
    Character.MATH_SYMBOL.toInt(),               // Sm
    Character.MODIFIER_SYMBOL.toInt(),           // Sk
    Character.CURRENCY_SYMBOL.toInt(),           // Sc, including the rupee sign
    Character.DECIMAL_DIGIT_NUMBER.toInt(),      // Nd, outside a scripted block
    Character.OTHER_NUMBER.toInt(),              // No
)

/** The script of [cp], or `null` when it belongs to whatever run surrounds it. */
private fun scriptOrNeutral(cp: Int): Script? {
    for (range in SCRIPT_RANGES) {
        if (cp in range.lo..range.hi) return range.script
    }
    if (Character.getType(cp) in NEUTRAL_CATEGORIES) return null
    if (cp < 0x0370) return Script.LATIN
    return Script.OTHER
}

/** The script a single codepoint belongs to, for font selection. */
fun scriptOf(cp: Int): Script = scriptOrNeutral(cp) ?: Script.LATIN

/**
 * Split [text] into the longest possible same-script spans.
 *
 * A report line is routinely mixed — `Sambalpuri Ikat (ସମ୍ବଲପୁରୀ ଇକତ) weave` — and a PDF renderer
 * must switch typeface mid-line or the parenthesised half becomes tofu. Returning spans rather than
 * per-character pairs keeps the run count (and so the DOCX size) proportional to the number of
 * script changes, not to the number of characters.
 *
 * Neutral characters resolve to the script *before* them, falling back to the one after when the
 * string opens with punctuation. An earlier version let any Latin character extend an open run,
 * which merged the trailing `) weave` above into the Odia span and drew four English words in the
 * fallback face.
 *
 * The resolution array is indexed by UTF-16 unit but decided per CODE POINT, with both halves of a
 * surrogate pair stamped with the same answer. That is what stops a span boundary landing between
 * the two halves of an emoji and producing a run whose text is half a character — which is invalid
 * UTF-16 and, once escaped, invalid XML.
 */
fun splitByScript(text: String): List<Pair<String, Script>> {
    if (text.isEmpty()) return emptyList()

    val resolved = arrayOfNulls<Script>(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val units = Character.charCount(cp)
        val s = scriptOrNeutral(cp)
        for (k in 0 until units) resolved[i + k] = s
        i += units
    }

    // Forward-fill: a neutral takes the script of the last decided character.
    var carried: Script? = null
    for (k in resolved.indices) {
        val s = resolved[k]
        if (s != null) carried = s else resolved[k] = carried
    }

    // Back-fill whatever is still undecided, i.e. the neutrals before the first real character.
    var trailing = Script.LATIN
    for (k in resolved.indices.reversed()) {
        val s = resolved[k]
        if (s != null) trailing = s else resolved[k] = trailing
    }

    val spans = ArrayList<Pair<String, Script>>()
    var start = 0
    for (k in 1 until text.length) {
        if (resolved[k] != resolved[k - 1]) {
            spans.add(text.substring(start, k) to resolved[k - 1]!!)
            start = k
        }
    }
    spans.add(text.substring(start) to resolved[text.length - 1]!!)
    return spans
}

/** The script most of [text] is written in — used to pick a whole cell's font. */
fun dominantScript(text: String): Script {
    val counts = HashMap<Script, Int>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        val s = scriptOf(cp)
        if (s == Script.LATIN) continue
        counts[s] = (counts[s] ?: 0) + 1
    }
    return counts.maxByOrNull { it.value }?.key ?: Script.LATIN
}

// --------------------------------------------------------------------------------------
// Inline content
// --------------------------------------------------------------------------------------

@Serializable
enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

/** Named paragraph roles. A renderer maps each to its own concrete typography. */
@Serializable
enum class ParaStyle {
    BODY,
    LEAD,        // opening paragraph of a section, slightly larger
    NOTE,        // smaller, muted — provenance and caveats
    QUOTE,       // indented, italic — artisan and buyer verbatims
    CAPTION,     // under an image
    COVER_LINE,  // centred cover-page furniture
}

/** A span of text with uniform formatting. Always built through [runsOf]. */
@Serializable
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /**
     * Added for RICH_TEXT, and positioned HERE — after [italic], before [script] — on purpose.
     *
     * The server's `Run` grew the same two fields in the same slot, and both models are constructed
     * positionally in a dozen places (`Run(text, bold, italic)` in the report builder and in both
     * renderers). Appending them at the end instead would have been safe in Kotlin, where every one of
     * those call sites happens to name its arguments, and unsafe in Python, where they do not — and the
     * two files must stay field-for-field identical or a persisted [ReportDocument] written by one and
     * read by the other maps `strike` onto `script`. Keeping the ORDER the same on both sides is what
     * makes "the same document" a checkable claim rather than a hope.
     *
     * [DocxWriter] emits these as `<w:u w:val="single"/>` and `<w:strike/>`. [PdfWriter] cannot: a PDF
     * text object only draws glyphs, so a rule under a word is a line the renderer strokes itself, and
     * these two booleans have to survive word wrap all the way to the draw call.
     */
    val underline: Boolean = false,
    val strike: Boolean = false,
    val script: Script = Script.LATIN,
    /** "RRGGBB", no leading hash — both writers want it bare. */
    val color: String? = null,
    /**
     * Added for RICH_TEXT, and APPENDED rather than slotted in beside [underline] and [strike].
     *
     * Those two had to go where they are because the model was already being built positionally as
     * `Run(text, bold, italic)`; these come after [color], where nothing positional can reach them,
     * so every existing call site keeps meaning what it meant. `report_model.py` appends them in the
     * same place for the same reason — the two files must stay field-for-field identical, or a
     * `ReportDocument` written by one and read by the other maps one flag onto another.
     *
     * A character cannot be both, and SUPERSCRIPT WINS where a document somehow says both. That is
     * decided once, where marks become runs ([runsFor] in `RichText.kt` and its Python twin), so no
     * renderer has to choose and no two renderers can choose differently.
     *
     * [DocxWriter] emits these as a single `<w:vertAlign w:val="…"/>`. [PdfWriter] cannot: a PDF
     * content stream only draws glyphs, so a superscript is a smaller size drawn off the baseline
     * and both figures have to survive the word wrap to the draw call.
     */
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    /**
     * A BOOLEAN AND NOT A COLOUR, which is a constraint of the .docx rather than a simplification:
     * `w:highlight` takes a closed vocabulary of sixteen named colours, not an RGB value, so an
     * arbitrary colour stored here could not be expressed in Word and each renderer would
     * approximate it differently. One fixed yellow — [HIGHLIGHT_FILL] — is what all four draw
     * identically.
     */
    val highlight: Boolean = false,
)

/**
 * The highlight, as the four renderers must all draw it.
 *
 * This is Word's own `w:highlight w:val="yellow"`, to the byte, because [DocxWriter] cannot choose:
 * it names the colour and Word picks the pixels. Both PDF renderers fill a rectangle themselves and
 * would otherwise each choose their own yellow — and the whole point of a highlighted sentence is
 * that it looks the same in the file the office downloaded as in the one generated on the phone.
 * `report_model.py` holds the same constant; `tests/test_report_parity.py` fails if they diverge.
 */
const val HIGHLIGHT_FILL = "FFFF00"

/** Build the runs for a value, cleaned and split at every script boundary. */
fun runsOf(
    text: Any?,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    strike: Boolean = false,
    color: String? = null,
): List<Run> {
    val cleaned = cleanText(text)
    if (cleaned.isEmpty()) return emptyList()
    return splitByScript(cleaned).map { (span, script) ->
        Run(
            text = span,
            bold = bold,
            italic = italic,
            underline = underline,
            strike = strike,
            script = script,
            color = color,
        )
    }
}

/** The plain text of a run sequence — for measurement, TOC entries and alt text. */
fun runsText(runs: List<Run>): String = runs.joinToString("") { it.text }

// --------------------------------------------------------------------------------------
// Images
// --------------------------------------------------------------------------------------

/**
 * A picture the report will embed, resolved to bytes by the renderer that needs them.
 *
 * [widthPx]/[heightPx] are the *intrinsic* pixel size and [rotationDeg] the EXIF orientation already
 * resolved to a quarter turn. Both matter: this client stores camera output unrotated with the
 * orientation only in EXIF, so a renderer that ignores it lays every portrait photo on its side, and
 * one that ignores intrinsic size cannot preserve aspect ratio when fitting a photo into a grid
 * cell. Carrying them here means the DOCX and the PDF compute the same box for the same photo
 * instead of each guessing.
 *
 * [source] is opaque to this file: a media id on the server, an absolute file path on the device.
 * Only the renderer's [ImageLoader] interprets it.
 */
@Serializable
data class ImageRef(
    val source: String,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val rotationDeg: Int = 0,
    val mimeType: String = "image/jpeg",
) {
    /** Width after the EXIF quarter turn is applied. */
    val displayWidthPx: Int get() = if (rotationDeg == 90 || rotationDeg == 270) heightPx else widthPx

    val displayHeightPx: Int get() = if (rotationDeg == 90 || rotationDeg == 270) widthPx else heightPx

    /**
     * Displayed width divided by displayed height; 4:3 when the size is unknown.
     *
     * Never throws and never returns zero — a zero would propagate into a division in both
     * renderers' fit calculations, and an unknown-size photo must still occupy a sane box rather
     * than abort the export the researcher is standing in a field waiting for.
     */
    val aspect: Float
        get() {
            val w = displayWidthPx
            val h = displayHeightPx
            if (w <= 0 || h <= 0) return 4f / 3f
            return w.toFloat() / h.toFloat()
        }
}

/**
 * Resolves an [ImageRef] to bytes. Returning `null` drops the picture and records a warning rather
 * than aborting — a report missing one photo is still worth having in the field.
 */
typealias ImageLoader = (ImageRef) -> ByteArray?

// --------------------------------------------------------------------------------------
// Blocks
// --------------------------------------------------------------------------------------

/**
 * One thing the report says.
 *
 * SEALED ON PURPOSE. Both renderers dispatch on this with a `when` that has no `else`; adding a
 * block type therefore fails to compile in both of them rather than being silently skipped in one.
 * The server's Python union has no such guarantee and relies on a `raise TypeError` fallthrough that
 * only fires at render time — i.e. in the field.
 */
@Serializable
sealed interface Block

/** The report's first page. Rendered by both writers as a full page followed by a break. */
@Serializable
data class CoverBlock(
    val title: String,
    val subtitle: String = "",
    /** Ministry / department, above the title. */
    val orgLines: List<String> = emptyList(),
    val logo: ImageRef? = null,
    val heroImage: ImageRef? = null,
    /** Label / value pairs in the cover table. */
    val infoRows: List<Pair<String, String>> = emptyList(),
    val footerLines: List<String> = emptyList(),
) : Block

/**
 * A table of contents.
 *
 * DOCX emits a `TOC` field so Word paginates it natively; the PDF renderers do it the hard way, by
 * laying the body out once to learn the page of every heading and then drawing the list. Both honour
 * [depth].
 */
@Serializable
data class TocBlock(
    val title: String = "Contents",
    val depth: Int = 3,
) : Block

@Serializable
data class HeadingBlock(
    /** 1-4. */
    val level: Int,
    val runs: List<Run>,
    /** "3.2" — precomputed by [DocumentBuilder], never by a renderer. */
    val number: String = "",
    /** Stable anchor id, for the TOC and cross references. */
    val bookmark: String = "",
) : Block

@Serializable
data class ParagraphBlock(
    val runs: List<Run>,
    val style: ParaStyle = ParaStyle.BODY,
    val align: Align = Align.LEFT,
) : Block

@Serializable
data class BulletListBlock(
    val items: List<List<Run>>,
    val ordered: Boolean = false,
) : Block

/**
 * Label/value pairs — the shape most single-record stages render as.
 *
 * Drawn as a borderless two-column table so a long value wraps under its own label instead of
 * pushing the next pair off the line, which is what a tab-separated paragraph did.
 */
@Serializable
data class KeyValueBlock(
    val pairs: List<Pair<String, List<Run>>>,
    /** 1 or 2 pairs side by side. */
    val columns: Int = 1,
    val labelWidthPct: Float = 30.0f,
) : Block

@Serializable
data class TableColumn(
    val header: String,
    val widthPct: Float,
    val align: Align = Align.LEFT,
    /** Right-aligns the body and lets the renderer group digits. */
    val numeric: Boolean = false,
)

/** A data table. [rows] holds runs per cell so a cell can mix scripts and bold a total. */
@Serializable
data class TableBlock(
    val columns: List<TableColumn>,
    val rows: List<List<List<Run>>>,
    val caption: String = "",
    val totalRow: List<List<Run>>? = null,
    val zebra: Boolean = true,
) : Block {
    init {
        // A renderer that trusts these percentages and gets 118 draws a table wider than the page,
        // and Word silently rescales while the PDF layout silently clips — two different wrong
        // answers from one bad template. Fail where the template is edited instead. The check runs
        // on deserialization too, which is the point: a persisted report carrying a bad template
        // must not become a corrupt export three days later on a different device.
        val total = columns.sumOf { it.widthPct.toDouble() }
        if (columns.isNotEmpty() && kotlin.math.abs(total - 100.0) > 0.5) {
            throw IllegalArgumentException(
                "TableBlock column widths must sum to 100, got " +
                    "${String.format("%.1f", total)} (${columns.map { it.header }})"
            )
        }
    }
}

@Serializable
data class ImageBlock(
    val image: ImageRef,
    /** Of the text column. */
    val widthPct: Float = 70.0f,
    val align: Align = Align.CENTER,
    val caption: String = "",
) : Block

/**
 * A captioned photo grid — how every stage's gallery reaches the page.
 *
 * [columns] is a request, not a guarantee: a renderer drops to fewer columns when the cell would
 * fall below a legible width, so the same grid stays readable on A4 and on Letter.
 */
@Serializable
data class ImageGridBlock(
    /** (image, caption) */
    val images: List<Pair<ImageRef, String>>,
    val columns: Int = 2,
    val caption: String = "",
) : Block

/** A row of headline numbers — "12 designs · 7 prototypes · 4 adopted". */
@Serializable
data class MetricRowBlock(
    /** (label, value, unit) */
    val metrics: List<Triple<String, String, String>>,
) : Block

// --------------------------------------------------------------------------------------
// The map and the infographics
// --------------------------------------------------------------------------------------
//
// Both of these are DESCRIPTIONS, NOT PICTURES. A renderer turns one into a PNG through [ReportMap]
// / [ReportChart] and then hands that PNG to its own existing image path — which is the whole reason
// they rasterise rather than each drawing vectors into OOXML and into PDF operators. Two renderers
// times two figure kinds is four drawing implementations that would have to agree; one rasteriser
// plus the picture path they both already have is one.
//
// Nothing here carries pixels. [MapBlock.widthPct] is a fraction of the text column exactly as
// [ImageBlock.widthPct] is; how many pixels that becomes is the rasteriser's business and the
// rasteriser's alone. This is also why neither block appears in [imagesOf]: a figure has no source
// the [ImageLoader] could ever resolve, and listing one in [ReportDocument.images] would make the
// caller prefetch a media id that does not exist and report the miss to the designer as a
// photograph that could not be included.
//
// Must stay in step with `report_model.py`; `backend/tests/test_report_parity.py` reads this file as
// text and fails when a block type exists on one surface and not the other, because a block the
// phone cannot render is a section of the report that silently disappears in the field.

/**
 * What a pin on the report's map of India stands for.
 *
 * A renderer draws each kind differently — the venue larger and in the accent, an artisan's home
 * district smaller — so a reader can tell "where the workshop was" from "where the people came from"
 * without reading a single label.
 */
@Serializable
enum class MapPointKind { VENUE, ARTISAN, MARKET, OTHER }

/**
 * One pin: what it is called, where it is, and what it stands for.
 *
 * [count] exists because artisans cluster. Six weavers from Barpali resolve to one coordinate, and
 * six pins stacked on that coordinate look like one pin — so the builder folds them into a single
 * point that says six, which is both honest and legible.
 *
 * [lat] and [lon] are Double, not Float, and that is not incidental: they are fed straight into the
 * projection this figure shares with the web map, and a Float degree carries about 5 m of error at
 * these latitudes — enough to move a coastal pin off the coast on a printed map.
 */
@Serializable
data class MapPoint(
    val label: String,
    val lat: Double,
    val lon: Double,
    val kind: MapPointKind = MapPointKind.OTHER,
    val count: Int = 1,
)

/**
 * A map of India with the workshop's places pinned on it.
 *
 * [highlight] names STATES to tint. It is a set of names rather than geometry because this model must
 * stay renderer-agnostic and because the geometry already exists once, in the boundary assets both
 * this app's map screen and the web map draw; see [ReportMap].
 */
@Serializable
data class MapBlock(
    val title: String = "",
    val caption: String = "",
    val points: List<MapPoint> = emptyList(),
    val highlight: Set<String> = emptySet(),
    /** Of the text column, exactly like [ImageBlock]. */
    val widthPct: Float = 66.0f,
    val align: Align = Align.CENTER,
) : Block

@Serializable
enum class ChartKind {
    BAR,
    HORIZONTAL_BAR,
    PIE,
    DONUT,
    LINE;

    val isCircular: Boolean get() = this == PIE || this == DONUT
}

/**
 * One infographic: a single series of labelled values.
 *
 * ONE SERIES, deliberately. Every figure this report needs — designs and prototypes, prototypes by
 * status, cost by head, price bands, adoption at three, six and twelve months — is one measure over a
 * set of categories. A second series would need a second axis or a stack, and a dual-axis figure is
 * the single most misread thing in a government report: two scales, one picture, and no reader can
 * tell which line belongs to which.
 *
 * [unit] is printed once, beside the axis or in the title, never on every value.
 *
 * The values are Double because every division in [ReportChart] — the share of a pie, the height of a
 * bar against a rounded axis — is performed in Double on the server. Carrying Float here and widening
 * at the division would round a 33% slice to a visibly different angle from the one the .docx the
 * office downloaded shows for the same workshop.
 */
@Serializable
data class ChartBlock(
    val kind: ChartKind,
    /** (label, value) */
    val series: List<Pair<String, Double>> = emptyList(),
    val title: String = "",
    val caption: String = "",
    val unit: String = "",
    val widthPct: Float = 74.0f,
    val align: Align = Align.CENTER,
) : Block {
    val total: Double get() = series.sumOf { it.second }
}

@Serializable
data class CalloutBlock(
    /** INFO | WARNING | SUCCESS */
    val kind: String,
    val title: String,
    val runs: List<Run>,
) : Block

/** Sign-off lines for the designer, the implementing agency and the inspecting official. */
@Serializable
data class SignatureBlock(
    /** (name, designation) */
    val signatories: List<Pair<String, String>>,
) : Block

@Serializable
data class SpacerBlock(
    /** Of the text column height. */
    val heightPct: Float = 5.0f,
) : Block

@Serializable
data object PageBreakBlock : Block

// --------------------------------------------------------------------------------------
// Document
// --------------------------------------------------------------------------------------

@Serializable
enum class PageSize {
    A4,
    LETTER;

    /** (width, height) in millimetres. Every renderer converts from these and nothing else. */
    val sizeMm: Pair<Float, Float>
        get() = if (this == A4) 210.0f to 297.0f else 215.9f to 279.4f
}

/**
 * Colours and fonts, as the template declares them.
 *
 * Colours are bare `RRGGBB`. Fonts are *names*, resolved per renderer: DOCX writes the name and Word
 * finds it, and Android maps it onto a `Typeface` (falling back to the system default, which is
 * exactly the right answer on a phone that has no Calibri). A name no surface can resolve degrades
 * to that surface's default rather than failing.
 */
@Serializable
data class ReportTheme(
    val accent: String = "1F3864",
    val accentSoft: String = "2F5496",
    val ink: String = "1B1B1B",
    val muted: String = "5A6B87",
    val rule: String = "B8C4D9",
    val tableHeaderFill: String = "1F3864",
    val tableHeaderText: String = "FFFFFF",
    val zebraFill: String = "F2F5FA",
    val headingFont: String = "Calibri Light",
    val bodyFont: String = "Calibri",
    /** The `w:cs` face for Indic runs in DOCX. */
    val complexFont: String = "Nirmala UI",
    val baseSizePt: Float = 10.5f,
)

// --------------------------------------------------------------------------------------
// One accent in, a whole legible palette out — the port of report_theme.py
// --------------------------------------------------------------------------------------
//
// A ReportTheme carries eight colours and every renderer reads all of them. A designer choosing
// the colour of their report chooses exactly ONE, and [themeFromAccent] computes the other seven,
// for the reason argued at length in `backend/app/services/report_theme.py`: a form with eight
// colour wells is a form that can produce an illegible government report, and it will — white
// header text on pale yellow, a rule the same colour as the paper — and the person who finds out
// is the officer opening the file.
//
// THIS IS A PORT AND IT MUST AGREE TO THE BYTE. The server derives the same palette for the same
// workshop, and the two files land on the same desk: a designer exports the .docx here in the
// cluster with no signal, the office regenerates it from the record a week later, and if the two
// derivations differ by one hex digit the second copy is a different colour with nothing to
// explain why. The contract is the golden table in `backend/tests/test_report_theme.py`; every
// number and every rounding rule below was checked against it, and the two lines that would
// otherwise drift silently — half-up rounding and the negative-modulo in the hue — are commented
// where they occur.

/** `report_theme.DEFAULT_ACCENT` — the indigo the report was written in before the picker existed. */
const val DEFAULT_ACCENT: String = "1F3864"

/** One named accent: the token stored on stage 20, the words a designer reads, the colour. */
data class AccentPreset(val key: String, val label: String, val hex: String)

/**
 * `report_theme.ACCENT_PRESETS`, in the same order — which is a LIGHTNESS LADDER, not a rainbow.
 *
 * These reports are printed, and usually on a monochrome office laser where hue is discarded and
 * only lightness survives. Twelve colours that happen to be equally dark come out of that tray as
 * twelve identical reports, so each of these was moved along the lightness axis until it landed on
 * its own rung of a CIE L* ladder from 17 (navy) to 48 (burnt orange). Only the lightness moved: a
 * maroon is still a maroon, which is what the designer chose.
 */
val ACCENT_PRESETS: List<AccentPreset> = listOf(
    AccentPreset("NAVY", "Navy", "152A50"),
    AccentPreset("INDIGO", "Deep indigo", DEFAULT_ACCENT),
    AccentPreset("FOREST", "Forest green", "1D4835"),
    AccentPreset("PLUM", "Plum", "672F6A"),
    AccentPreset("MAROON", "Maroon", "802F42"),
    AccentPreset("CHARCOAL", "Charcoal", "4B5259"),
    AccentPreset("TEAL", "Teal", "10616A"),
    AccentPreset("BRICK", "Brick", "9C4030"),
    AccentPreset("SLATE", "Slate blue", "4E638E"),
    AccentPreset("OLIVE", "Olive", "6D6B24"),
    AccentPreset("BRONZE", "Bronze", "87682E"),
    AccentPreset("BURNT_ORANGE", "Burnt orange", "AD5D1C"),
)

/**
 * [value] as a bare upper-case `RRGGBB`, or null when it is not a colour at all.
 *
 * Accepts what actually reaches this function: `#1F3864` written by the web picker into the stage
 * entry, `1f3864` typed by hand into the field below, `#1F8` shorthand, and whitespace around any
 * of them. A colour name, an `rgb()` string, a blank and a null are all "not a colour", and the
 * caller falls back rather than guessing what was meant.
 */
fun normaliseHex(value: String?): String? {
    var text = (value ?: return null).trim()
    if (text.startsWith("#")) text = text.substring(1).trim()
    if (text.isEmpty() || text.any { it !in "0123456789abcdefABCDEF" }) return null
    // CSS shorthand: each digit doubles. #1F8 is #11FF88, not #001F08.
    if (text.length == 3) text = text.map { "$it$it" }.joinToString("")
    if (text.length != 6) return null
    return text.uppercase(java.util.Locale.ROOT)
}

private fun hexToRgb(hex: String): Triple<Double, Double, Double> = Triple(
    hex.substring(0, 2).toInt(16) / 255.0,
    hex.substring(2, 4).toInt(16) / 255.0,
    hex.substring(4, 6).toInt(16) / 255.0,
)

/**
 * Three 0..1 channels back to `RRGGBB`.
 *
 * `(x + 0.5).toInt()` and not `roundToInt()`, matching `int(x + 0.5)` in the Python and
 * `Math.floor(x + 0.5)` in the TypeScript. The three languages disagree about halves — Python
 * rounds to even — so the half-up rule is written out rather than delegated, and a channel landing
 * exactly on a half then cannot come back one digit different from the server for the same accent.
 * The clamp is what keeps a floating-point overshoot at lightness 1.0 from formatting as a
 * seven-digit colour that Word accepts and draws black.
 */
private fun rgbToHex(red: Double, green: Double, blue: Double): String =
    listOf(red, green, blue).joinToString("") { channel ->
        val clamped = channel.coerceIn(0.0, 1.0)
        String.format(java.util.Locale.ROOT, "%02X", (clamped * 255.0 + 0.5).toInt())
    }

/** (hue 0..360, saturation 0..1, lightness 0..1). Hue is 0 for any grey. */
private fun rgbToHsl(rgb: Triple<Double, Double, Double>): Triple<Double, Double, Double> {
    val (red, green, blue) = rgb
    val high = maxOf(red, green, blue)
    val low = minOf(red, green, blue)
    val lightness = (high + low) / 2.0
    val span = high - low
    if (span == 0.0) return Triple(0.0, 0.0, lightness)
    val saturation = if (lightness > 0.5) span / (2.0 - high - low) else span / (high + low)
    val hue = when (high) {
        // `%` keeps the sign of the dividend in Kotlin and in JavaScript but not in Python, where
        // it follows the divisor. The `+ 6` before the second `%` is what makes all three agree —
        // without it a red-dominant colour whose green is below its blue comes out with a negative
        // hue here and a positive one on the server, and the two halves of the ladder swap.
        red -> (((green - blue) / span) % 6.0 + 6.0) % 6.0
        green -> (blue - red) / span + 2.0
        else -> (red - green) / span + 4.0
    }
    return Triple(hue * 60.0, saturation, lightness)
}

private fun hslToRgb(hue: Double, saturation: Double, lightness: Double): Triple<Double, Double, Double> {
    if (saturation <= 0.0) return Triple(lightness, lightness, lightness)
    val chroma = (1.0 - kotlin.math.abs(2.0 * lightness - 1.0)) * saturation
    val sector = ((hue % 360.0) + 360.0) % 360.0 / 60.0
    val second = chroma * (1.0 - kotlin.math.abs(sector % 2.0 - 1.0))
    val triple = when {
        sector < 1.0 -> Triple(chroma, second, 0.0)
        sector < 2.0 -> Triple(second, chroma, 0.0)
        sector < 3.0 -> Triple(0.0, chroma, second)
        sector < 4.0 -> Triple(0.0, second, chroma)
        sector < 5.0 -> Triple(second, 0.0, chroma)
        else -> Triple(chroma, 0.0, second)
    }
    val base = lightness - chroma / 2.0
    return Triple(triple.first + base, triple.second + base, triple.third + base)
}

private fun hslHex(hue: Double, saturation: Double, lightness: Double): String {
    val (red, green, blue) = hslToRgb(hue, saturation.coerceIn(0.0, 1.0), lightness.coerceIn(0.0, 1.0))
    return rgbToHex(red, green, blue)
}

/** WCAG 2.1 relative luminance of a bare `RRGGBB`. */
fun relativeLuminance(hex: String): Double {
    val (red, green, blue) = hexToRgb(hex)
    fun linear(channel: Double): Double =
        if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
    return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
}

/** The WCAG contrast ratio between two bare `RRGGBB` colours: 1 (identical) to 21 (black on white). */
fun contrastRatio(first: String, second: String): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

// The page every legibility decision below is taken against, because that is what the report is
// printed on. 3:1 is the bar for anything used as LARGE text — the accent draws H1 at 17pt, H2 at
// 13.5, H3 at 11.5 and the metric numbers at 17 — and 4.5:1 the bar for the muted colour, which
// draws the running head and foot at 7.8pt and the NOTE paragraphs. The hues that carry the most
// luminance for a given lightness, teal and olive, are exactly the ones that fail the second bar
// at the fixed lightness below, which is why it is measured rather than assumed.
private const val PAPER_HEX = "FFFFFF"
private const val MIN_TEXT_CONTRAST = 3.0
private const val MIN_SMALL_TEXT_CONTRAST = 4.5
private const val SOFT_LIFT = 0.175
private const val SOFT_MAX_LIGHTNESS = 0.62
private const val INK_SATURATION = 0.14
private const val INK_SATURATION_CAP = 0.10
private const val INK_LIGHTNESS = 0.105
private const val MUTED_SATURATION = 0.38
private const val MUTED_SATURATION_CAP = 0.30
private const val MUTED_LIGHTNESS = 0.44
private const val RULE_SATURATION = 0.58
private const val RULE_SATURATION_CAP = 0.45
private const val RULE_LIGHTNESS = 0.786
private const val ZEBRA_SATURATION = 0.84
private const val ZEBRA_SATURATION_CAP = 0.60
private const val ZEBRA_LIGHTNESS = 0.965

/**
 * The colour at this hue and saturation, darkened until it can be read on the page.
 *
 * A hundred fixed steps rather than a solve. The loop is deterministic, always terminates — black
 * clears 3:1 against white seven times over — and, the reason it is a loop at all, is the same
 * arithmetic in Kotlin, Python and TypeScript. Three closed forms over floating point would be
 * three answers in the last hex digit, and on a table header fill that is a visible seam between
 * the file this phone writes and the one the server writes for the same workshop.
 */
private fun legibleOnPaper(hue: Double, saturation: Double, lightness: Double, minimum: Double): String {
    var candidate = hslHex(hue, saturation, lightness)
    for (step in 0..100) {
        candidate = hslHex(hue, saturation, lightness - step * 0.01)
        if (contrastRatio(candidate, PAPER_HEX) >= minimum) break
    }
    return candidate
}

private fun tinted(hue: Double, saturation: Double, scale: Double, cap: Double, lightness: Double): String =
    hslHex(hue, minOf(saturation * scale, cap), lightness)

/**
 * A complete, coherent, legible [ReportTheme] from one accent colour — `theme_from_accent`.
 *
 * [base] supplies everything that is not a colour: the heading, body and complex-script font names
 * and the base point size. Recolouring a report is not a request to re-typeset it.
 *
 * NEVER THROWS. A null, a blank, a colour name and a truncated hex all produce the DCH indigo
 * palette, because the caller is an export a designer is standing in a cluster waiting for and the
 * correct answer to a bad colour string is a report in the old colour, not no report.
 *
 * Two of the eight are decided by measurement rather than by taste, and both are the reason this
 * is a function rather than a table. The table header's TEXT is white or near-black according to
 * which has more contrast against the fill, never according to the hue — guessing gets mid-tone
 * teals and olives wrong in both directions, and white 9pt bold on pale yellow is invisible on an
 * office laser. And the accent itself is darkened until a heading drawn in it is legible on white,
 * so a designer who picks pale yellow gets a deeper yellow rather than forty pages of section
 * titles that are not there. The picker shows the derived colour for exactly that reason.
 */
fun themeFromAccent(accent: String?, base: ReportTheme = ReportTheme()): ReportTheme {
    val hex = normaliseHex(accent) ?: DEFAULT_ACCENT
    val (hue, saturation, lightness) = rgbToHsl(hexToRgb(hex))

    val accentHex = legibleOnPaper(hue, saturation, lightness, MIN_TEXT_CONTRAST)
    val accentLightness = rgbToHsl(hexToRgb(accentHex)).third
    val softLightness = minOf(
        accentLightness + (1.0 - accentLightness) * SOFT_LIFT,
        SOFT_MAX_LIGHTNESS,
    )
    val softHex = legibleOnPaper(hue, saturation, softLightness, MIN_TEXT_CONTRAST)
    val inkHex = tinted(hue, saturation, INK_SATURATION, INK_SATURATION_CAP, INK_LIGHTNESS)
    // The near-black option is the document's own ink and not pure black, so a header row that
    // reverses to dark text is the same dark as the prose under it rather than a second, harder
    // black nobody chose.
    val headerText =
        if (contrastRatio(accentHex, PAPER_HEX) >= contrastRatio(accentHex, inkHex)) PAPER_HEX else inkHex

    return base.copy(
        accent = accentHex,
        accentSoft = softHex,
        ink = inkHex,
        muted = legibleOnPaper(
            hue,
            minOf(saturation * MUTED_SATURATION, MUTED_SATURATION_CAP),
            MUTED_LIGHTNESS,
            MIN_SMALL_TEXT_CONTRAST,
        ),
        rule = tinted(hue, saturation, RULE_SATURATION, RULE_SATURATION_CAP, RULE_LIGHTNESS),
        tableHeaderFill = accentHex,
        tableHeaderText = headerText,
        zebraFill = tinted(hue, saturation, ZEBRA_SATURATION, ZEBRA_SATURATION_CAP, ZEBRA_LIGHTNESS),
    )
}

@Serializable
data class ReportMeta(
    val title: String,
    val subtitle: String = "",
    val author: String = "",
    val organisation: String = "",
    val templateId: String = "",
    val templateName: String = "",
    val workshopId: String = "",
    /** ISO-8601 UTC, supplied by the caller, never clock-read here — so a re-export is identical. */
    val generatedAt: String = "",
    val pageSize: PageSize = PageSize.A4,
    val marginMm: Float = 25.0f,
    val headerText: String = "",
    val footerText: String = "",
    val showPageNumbers: Boolean = true,
)

@Serializable
data class ReportDocument(
    val meta: ReportMeta,
    val theme: ReportTheme,
    val blocks: List<Block>,
    /**
     * Every image the document references, deduplicated by source, in first-use order. [DocxWriter]
     * needs this to emit one media part and one relationship per distinct file: a photo used on both
     * the prototype page and the annexure was embedded twice before this existed, which doubled a
     * 60-photo report's size for no visible difference.
     */
    val images: List<ImageRef> = emptyList(),
    /** Completeness-check output, surfaced in the UI not the file. */
    val warnings: List<String> = emptyList(),
)

/** Every distinct image in [blocks], in first-use order. */
fun collectImages(blocks: List<Block>): List<ImageRef> {
    val seen = LinkedHashMap<String, ImageRef>()
    for (block in blocks) {
        for (img in imagesOf(block)) {
            if (!seen.containsKey(img.source)) seen[img.source] = img
        }
    }
    return seen.values.toList()
}

private fun imagesOf(block: Block): List<ImageRef> = when (block) {
    is ImageBlock -> listOf(block.image)
    is ImageGridBlock -> block.images.map { it.first }
    is CoverBlock -> listOfNotNull(block.logo, block.heroImage)
    else -> emptyList()
}

// --------------------------------------------------------------------------------------
// Builder helpers — the shorthand the template interpreter is written in
// --------------------------------------------------------------------------------------

/**
 * Accumulates blocks and hands back an immutable [ReportDocument].
 *
 * Mutable on purpose and mutable *only here*: the interpreter appends as it walks the template, then
 * [build] freezes the result so no renderer can alter a document it is halfway through writing —
 * which matters more on this side than on the server, because [PdfWriter] walks the same block list
 * TWICE and a mutation between the passes would put the TOC's page numbers out by however much.
 */
class DocumentBuilder(
    val meta: ReportMeta,
    val theme: ReportTheme = ReportTheme(),
) {
    private val blocks = ArrayList<Block>()
    private val warnings = ArrayList<String>()
    private val headingCounters = intArrayOf(0, 0, 0, 0)

    fun add(block: Block): DocumentBuilder {
        blocks.add(block)
        return this
    }

    /**
     * How many blocks have been appended so far.
     *
     * Exposed for the template interpreter's "did this section write anything" test, which decides
     * whether a heading is followed by "Not recorded." or by nothing at all. Counting blocks is the
     * only way to answer that honestly: [para], [bullets] and [keyValues] all DROP empty content, so
     * a section can call four of them and add nothing, and any bookkeeping the caller keeps by hand
     * gets that case wrong in the direction that prints a bare heading over a blank half page.
     */
    val blockCount: Int get() = blocks.size

    fun warn(message: String): DocumentBuilder {
        warnings.add(cleanText(message))
        return this
    }

    /**
     * Append a heading, maintaining the "3.2.1" counters the TOC and cross-refs use.
     *
     * Numbering lives here rather than in either renderer because Word's own list numbering and the
     * PDF's would restart on different rules, and the two documents would then disagree about what
     * "section 4" is — in a report whose prose says "see section 4".
     */
    fun heading(text: Any?, level: Int = 1, numbered: Boolean = true): DocumentBuilder {
        val lvl = level.coerceIn(1, 4)
        var number = ""
        if (numbered) {
            headingCounters[lvl - 1] += 1
            for (i in lvl until 4) headingCounters[i] = 0
            number = (0 until lvl).joinToString(".") { headingCounters[it].toString() }
        }
        val cleaned = cleanText(text)
        val bookmark = bookmarkId(number, cleaned, blocks.size)
        return add(
            HeadingBlock(level = lvl, runs = runsOf(cleaned), number = number, bookmark = bookmark)
        )
    }

    /**
     * Append a paragraph. A blank value appends nothing at all.
     *
     * Skipping empties here is what keeps an optional Standard-tier field from leaving a run of
     * blank lines through the middle of a report whose workshop only captured the Basic tier.
     */
    fun para(
        text: Any?,
        style: ParaStyle = ParaStyle.BODY,
        align: Align = Align.LEFT,
    ): DocumentBuilder {
        val cleaned = cleanText(text)
        if (cleaned.isBlank()) return this
        // A textarea's blank line is a paragraph break, not a line break inside one.
        for (chunk in cleaned.split("\n\n")) {
            if (chunk.isBlank()) continue
            add(ParagraphBlock(runs = runsOf(chunk.trim()), style = style, align = align))
        }
        return this
    }

    fun bullets(items: List<Any?>, ordered: Boolean = false): DocumentBuilder {
        val kept = items.filter { cleanText(it).isNotBlank() }.map { runsOf(it) }
        if (kept.isEmpty()) return this
        return add(BulletListBlock(items = kept, ordered = ordered))
    }

    fun keyValues(
        pairs: List<Pair<String, Any?>>,
        columns: Int = 1,
        skipEmpty: Boolean = true,
    ): DocumentBuilder {
        val kept = pairs
            .filter { !skipEmpty || cleanText(it.second).isNotBlank() }
            .map { (label, value) -> cleanText(label) to runsOf(value) }
        if (kept.isEmpty()) return this
        return add(KeyValueBlock(pairs = kept, columns = columns))
    }

    fun build(): ReportDocument {
        val frozen = blocks.toList()
        return ReportDocument(
            meta = meta,
            theme = theme,
            blocks = frozen,
            images = collectImages(frozen),
            warnings = warnings.toList(),
        )
    }
}

private val NON_WORD = Regex("[^A-Za-z0-9]+")

/**
 * A Word-legal bookmark name: letters, digits and underscores, <= 40 chars, unique.
 *
 * Word silently discards a bookmark whose name starts with a digit or exceeds 40 characters, and a
 * discarded bookmark makes its TOC row unclickable — which looks like a broken report rather than a
 * naming-rule violation. The ordinal suffix keeps two identically-titled stage headings (every
 * prototype has a "Materials" heading) from colliding.
 */
internal fun bookmarkId(number: String, text: String, ordinal: Int): String {
    val slug = NON_WORD.replace("${number}_$text", "_").trim('_').take(28)
    return if (slug.isNotEmpty()) "_S${ordinal}_$slug" else "_S$ordinal"
}
