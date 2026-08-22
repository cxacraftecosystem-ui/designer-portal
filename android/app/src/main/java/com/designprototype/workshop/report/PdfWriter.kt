package com.designprototype.workshop.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a [ReportDocument] to PDF on `android.graphics.pdf.PdfDocument` — the on-device twin of
 * `backend/app/services/report_pdf.py`, written as the same ALGORITHM rather than the same library:
 *
 *     measure a block against the text column  ->  does it fit in the space left on this page?
 *     ->  no: finish the page, start a new one  ->  draw it at the cursor  ->  advance the cursor
 *
 * with one case the loop cannot answer on its own: A BLOCK THAT FITS ON NO PAGE AT ALL. A table row
 * carrying a 6,000-character LONG_TEXT answer is taller than A4, so "start a new one" never makes it
 * fit, and the lock that keeps a row from breaking between two of its own lines then suppressed every
 * break there was — the remainder was drawn at a negative y, outside the paper, in a file that opens
 * perfectly. [cutRow] is the answer: the row is CUT at the foot of the page and continued overleaf,
 * which is what Word does for the .docx twin of the same document.
 *
 * Every block below is laid out by that loop. The server uses ReportLab's low-level `canvas` API for
 * exactly this reason — Platypus would have been shorter and its `Flowable` machinery has no
 * counterpart here, and a report whose server copy and phone copy paginate differently is a support
 * ticket that cannot be reproduced on either device. `canvas.drawString`/`drawImage`/`rect` map
 * one-to-one onto `Canvas.drawText`/`drawBitmap`/`drawRect`, so this file is a transliteration.
 *
 * Four things differ from the server, and each is deliberate:
 *
 * **The y axis is flipped, once, in [cy].** PDF user space (and therefore ReportLab, and therefore
 * the algorithm being ported) has its origin at the bottom-left with y growing upward; Android's
 * `Canvas` has it top-left with y growing downward. Rather than rewrite every comparison — `y -
 * height < bottom`, the cursor decrements, the whole "does it fit" test — the cursor stays in PDF
 * space and [cy] converts at the exact moment a coordinate is handed to the canvas. Flipping the
 * algorithm instead was tried first and every fit test came out inverted in a different place.
 *
 * **PdfDocument does not paginate.** It hands out one blank page of the size you ask for and draws
 * nothing else. Page size is in POINTS at 72 dpi — A4 is 595x842 — and every page break in this file
 * is one this file decided on.
 *
 * **Complex scripts actually shape.** Android's text stack IS HarfBuzz, so Devanagari, Odia, Bengali
 * and Gujarati get their reordering and conjunct formation. The server PDF cannot do this (ReportLab
 * positions glyphs by advance width) and its docstring calls that an honest limitation; here a
 * paragraph of Odia prose renders correctly, so the on-device PDF is the better artefact for
 * Indic-heavy content.
 *
 * **Bitmaps are downsampled and recycled.** A 60-photo report at full decode is several gigabytes of
 * `ARGB_8888`; `largeHeap` buys a few hundred megabytes and is nowhere near enough. Every photo is
 * decoded with an `inSampleSize` computed from the box it will actually occupy and recycled the
 * instant it has been drawn.
 */

const val PDF_MIME = "application/pdf"

/** PDF user space is points; every dimension in the model is relative or in millimetres. */
private const val MM = 72.0f / 25.4f

/**
 * How many device pixels to decode per PDF point.
 *
 * 1.0 would decode a photo at exactly the size it is drawn and print at 72 dpi, which is visibly
 * soft on paper; 4.0 is indistinguishable from 2.0 on any printer a district office owns and costs
 * four times the heap. 2.0 is 144 dpi, which is the point at which a photo of a loom stops looking
 * like a screenshot.
 */
private const val IMAGE_OVERSAMPLE = 2.0f

/**
 * How a raised or lowered run is drawn, as fractions of the paragraph's own point size.
 *
 * These three are duplicated verbatim in `report_pdf.py` and pinned by
 * `backend/tests/test_report_parity.py`. They have to be: the phone and the server produce the same
 * report for the same workshop, and a superscript raised by a different fraction on one of them is a
 * visibly different line — in a document somebody compares against the copy the office downloaded.
 * 0.62 / 0.33 / 0.14 is roughly what Word itself does, which is what makes the .docx and the .pdf of
 * one report look like the same document rather than two.
 */
private const val VERTICAL_SCALE = 0.62f
private const val SUPERSCRIPT_RISE = 0.33f
private const val SUBSCRIPT_DROP = 0.14f

/** Parse a bare "RRGGBB" into an opaque ARGB int. Malformed values render black, never crash. */
private fun argb(hex: String?): Int {
    val h = (hex ?: "000000").removePrefix("#")
    val value = if (h.length == 6) h.toLongOrNull(16) else null
    return (0xFF000000L or (value ?: 0L)).toInt()
}

/**
 * Lays a [ReportDocument] onto PDF pages with an explicit cursor.
 *
 * The two-pass structure exists for one reason: a table of contents needs the page number of every
 * heading, and a heading's page number is not known until the body has been laid out. The measuring
 * pass runs the whole layout with drawing suppressed and records `bookmark -> page`; the drawing
 * pass runs it again for real, and the TOC block now has numbers to print. Measuring is cheap — it
 * starts no pages, decodes no bitmaps and rasterises no glyphs — and it is the same trick the server
 * uses, which is why their page numbers agree. See [writeTo] for why the measuring pass repeats.
 */
class PdfWriter(
    private val doc: ReportDocument,
    private val loadImage: ImageLoader,
) {
    private val theme = doc.theme

    /** Sources that could not be drawn, for the caller to surface as export warnings. */
    val droppedImages = ArrayList<String>()

    /**
     * Wrapped lines of running head and foot the page margin could not hold, over the whole render.
     *
     * The server logs the same fact once per export (`report_pdf._note_clipped_furniture`) and this
     * had no counterpart at all, so a designer exporting from a phone lost the tail of their running
     * line in silence — a real server/handset divergence rather than a documented one. Counted here
     * because counting costs nothing and needs no other file; SURFACING it does, and would mean a
     * third warning list beside `ReportExport.Result.droppedImages` and the banner that reads it in
     * `ReportScreen`. Left for whoever owns those two.
     */
    var clippedFurnitureLines = 0
        private set

    /**
     * Rasterised map and chart bytes, so the picture path can load them like any other image, and the
     * [ImageRef] each figure block resolved to.
     *
     * See the note beside [DocxWriter]'s equivalent for why a figure cannot go through [loadImage];
     * see [figureRef] for why the second map is keyed by identity and why it exists at all on a
     * renderer that walks the block list twice. The bytes outlive both passes deliberately: a PNG of
     * India is a few hundred kilobytes and rebuilding it is seconds, which is the opposite trade from
     * the photographs, whose bytes are re-read per draw precisely because holding them is what kills
     * the export on a mid-range phone.
     */
    private val figures = HashMap<String, ByteArray>()
    private val figureRefs = java.util.IdentityHashMap<Block, ImageRef?>()

    private val pageW: Float
    private val pageH: Float
    private val pageWpt: Int
    private val pageHpt: Int
    private val margin: Float
    private val textW: Float
    private val top: Float
    private val bottom: Float
    private val baseSize: Float

    init {
        val (wMm, hMm) = doc.meta.pageSize.sizeMm
        // Rounded to whole points because PdfDocument.PageInfo takes ints: drawing against the
        // unrounded float while the page is the rounded int puts up to a point of content past the
        // trim edge, which on a bordered table is a visibly clipped rule.
        pageWpt = (wMm * MM).roundToInt()
        pageHpt = (hMm * MM).roundToInt()
        pageW = pageWpt.toFloat()
        pageH = pageHpt.toFloat()
        margin = doc.meta.marginMm * MM
        textW = pageW - 2 * margin
        top = pageH - margin
        bottom = margin + 10 * MM // room for the running foot
        baseSize = theme.baseSizePt
    }

    // Cursor and pass state.
    private var drawing = false
    private var pageNo = 0
    private var pageStarted = false
    private var y = 0f

    private var pdf: PdfDocument? = null
    private var page: PdfDocument.Page? = null
    private val canvas: Canvas get() = page!!.canvas

    private var lockedDepth = 0

    private class TocEntry(val level: Int, val number: String, val text: String, val bookmark: String)

    /**
     * The committed heading index — what [blockToc] draws from. Written between measuring passes,
     * never during one, so a pass always lays out against a stable answer.
     */
    private val headingPages = HashMap<String, Int>()
    private val tocEntries = ArrayList<TocEntry>()

    /** What the measuring pass currently running has found. Promoted into the two above at its end. */
    private val pendingPages = HashMap<String, Int>()
    private val pendingEntries = ArrayList<TocEntry>()

    // One Paint per role, reused. Allocating inside the draw loop of a sixty-page report is tens of
    // thousands of short-lived objects and the GC pauses show up as a visibly stalled export dialog.
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /** The one place PDF space (y up, origin bottom-left) becomes canvas space (y down, top-left). */
    private fun cy(pdfY: Float): Float = pageH - pdfY

    // -- fonts -------------------------------------------------------------------------

    /**
     * The typeface for one run.
     *
     * `Typeface.DEFAULT` is the whole Indic story on this side: Android resolves an unmapped
     * codepoint through the system fallback chain, which on every shipping device includes the Noto
     * family for Devanagari, Oriya, Bengali, Gujarati, Tamil, Telugu, Kannada, Malayalam and
     * Gurmukhi. The server has to hunt the filesystem for a TTF and warns at start-up when it finds
     * none; here the script tag on the run needs no font decision at all, and asking for a named
     * face the device does not have ("Calibri") would defeat the fallback and print tofu.
     *
     * Italic IS honoured here, unlike the server, which has no italic face bound and quietly draws
     * quotes upright. The synthetic oblique Android applies is measured by the same Paint that draws
     * it, so the wrap stays correct.
     */
    private fun typefaceFor(run: Run): Typeface {
        val style = when {
            run.bold && run.italic -> Typeface.BOLD_ITALIC
            run.bold -> Typeface.BOLD
            run.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(Typeface.DEFAULT, style)
    }

    private val regularFace: Typeface get() = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val boldFace: Typeface get() = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    // -- text measurement --------------------------------------------------------------

    private fun stringWidth(text: String, face: Typeface, size: Float): Float {
        textPaint.typeface = face
        textPaint.textSize = size
        return textPaint.measureText(text)
    }

    /**
     * One piece of a laid-out line: text plus everything needed to draw it identically later.
     *
     * PDF HAS NO UNDERLINE ATTRIBUTE. Unlike a .docx run, where `<w:u w:val="single"/>` is a property
     * Word honours, a PDF content stream can only draw glyphs — a rule under a word is a separate line
     * this renderer strokes itself. Bold and italic can therefore be resolved into the [Typeface] at
     * wrap time and forgotten; [underline] and [strike] cannot, and have to ride the whole way through
     * the word wrap to the draw call. Dropping them at the [Piece] boundary is exactly how the first
     * attempt lost every underline in the PDF while the .docx of the same report kept them.
     *
     * NOR HAS IT A SUPERSCRIPT ATTRIBUTE, and that one is settled differently again: a superscript
     * is a smaller size drawn off the baseline, so the SIZE is resolved into the piece at wrap time
     * — which it must be, because the wrap measures with it — and the offset rides along as [rise].
     * In PDF space y grows UPWARD, so a positive rise is up; [cy] does the flip once, at the canvas.
     *
     * NOR A HIGHLIGHT: that is a filled rectangle drawn BEFORE the glyphs, at the extent of the
     * piece — the same unit the underline rule uses, and the only one that stops a highlighted
     * phrase tinting the whole line it sits on.
     */
    private class Piece(
        val text: String,
        val face: Typeface,
        val size: Float,
        val color: String?,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val rise: Float = 0f,
        val highlight: Boolean = false,
    )

    /** One laid-out line: the pieces on it, its width and its height (leading). */
    private class Line(val pieces: List<Piece>, val width: Float, val height: Float)

    /**
     * Greedy word wrap across a run sequence, preserving each run's font and colour.
     *
     * Wrapping across runs rather than per run is what keeps `Ikat (ସମ୍ବଲପୁରୀ) weave` from breaking
     * after every script change. A single word longer than the column — a URL, a
     * German-length compound — is hard-split character by character rather than allowed to overflow
     * the margin, because an overflowing line is not merely ugly: it runs under the page number and
     * off the trim edge, and the reader never learns what it said.
     */
    private fun wrap(
        runs: List<Run>,
        width: Float,
        size: Float,
        leadingFactor: Float = 1.32f,
    ): List<Line> {
        val leading = size * leadingFactor
        val lines = ArrayList<Line>()
        var cur = ArrayList<Piece>()
        var curW = 0f

        fun flush() {
            if (cur.isNotEmpty()) {
                lines.add(Line(cur, curW, leading))
                cur = ArrayList()
                curW = 0f
            }
        }

        for (run in runs) {
            val face = typefaceFor(run)
            // A raised or lowered run is drawn SMALLER and OFF the baseline; the leading is left
            // alone, because a footnote marker must not open up the line it sits on. Both figures
            // are fractions of the paragraph's own size, and both are the same numbers
            // `report_pdf.py` uses — a server that raised "m²" by a different amount from the phone
            // would print a visibly different line for the same document, which is exactly what
            // `tests/test_report_parity.py` pins these three constants against.
            val runSize = if (run.superscript || run.subscript) size * VERTICAL_SCALE else size
            val rise = when {
                run.superscript -> size * SUPERSCRIPT_RISE
                run.subscript -> -size * SUBSCRIPT_DROP
                else -> 0f
            }
            run.text.split("\n").forEachIndexed { paraIndex, segment ->
                if (paraIndex > 0) flush()
                if (segment.isEmpty()) return@forEachIndexed
                // Keep the separating space attached to the preceding token so the width maths
                // matches what is actually drawn.
                val tokens = segment.replace("\t", "    ").split(" ")
                tokens.forEachIndexed { tokenIndex, token ->
                    val piece = if (tokenIndex == tokens.size - 1) token else "$token "
                    if (piece.isEmpty()) return@forEachIndexed
                    val w = stringWidth(piece, face, runSize)
                    if (cur.isNotEmpty() && curW + w > width) flush()
                    if (w > width) {
                        // Hard-split an unbreakable token, one character at a time.
                        val buf = StringBuilder()
                        var bufW = 0f
                        for (ch in piece) {
                            val cw = stringWidth(ch.toString(), face, runSize)
                            if (buf.isNotEmpty() && bufW + cw > width) {
                                cur.add(
                                    Piece(
                                        buf.toString(), face, runSize, run.color,
                                        run.underline, run.strike, rise, run.highlight,
                                    )
                                )
                                curW += bufW
                                flush()
                                buf.setLength(0)
                                bufW = 0f
                            }
                            buf.append(ch)
                            bufW += cw
                        }
                        if (buf.isNotEmpty()) {
                            cur.add(
                                Piece(
                                    buf.toString(), face, runSize, run.color,
                                    run.underline, run.strike, rise, run.highlight,
                                )
                            )
                            curW += bufW
                        }
                        return@forEachIndexed
                    }
                    cur.add(
                        Piece(
                            piece, face, runSize, run.color,
                            run.underline, run.strike, rise, run.highlight,
                        )
                    )
                    curW += w
                }
            }
        }
        flush()
        // Never empty: a caller that measures `lines[0].height` (bullets, table rows) would throw on
        // an empty list, and an empty paragraph must still occupy one line's worth of space.
        return lines.ifEmpty { listOf(Line(emptyList(), 0f, leading)) }
    }

    // -- page machinery ----------------------------------------------------------------

    private fun startPage() {
        val info = PdfDocument.PageInfo.Builder(pageWpt, pageHpt, pageNo).create()
        page = pdf!!.startPage(info)
    }

    private fun finishPage() {
        page?.let { pdf!!.finishPage(it) }
        page = null
    }

    private fun newPage() {
        if (drawing && pageStarted) {
            drawFurniture()
            finishPage()
        }
        pageNo += 1
        pageStarted = true
        if (drawing) startPage()
        y = top
        // Clearance under the running head. THE `drawing` FLAG MUST NOT APPEAR IN THIS CONDITION.
        // The server's version applies the 6 mm only while drawing, so its measuring pass believes
        // every page after the first is 6 mm taller than it really is — enough for one extra line of
        // a table to "fit" in pass one and not in pass two, which silently shifts every page number
        // the table of contents printed. Both passes must see identical geometry or the two-pass
        // trick is worthless.
        if (pageNo > 1) y = top - 6 * MM
    }

    /**
     * Break to a new page when [height] will not fit in what is left of this one.
     *
     * Suppressed inside a fixed-height box ([locked]). Without that, drawing the text of a table cell
     * would break the page *between* two lines of the cell, leaving the row's background rectangle
     * and its vertical rules on the previous page and the rest of the words on the next one.
     */
    private fun ensure(height: Float) {
        if (lockedDepth > 0) return
        if (y - height < bottom) newPage()
    }

    /** Draw without page breaks — the caller has already reserved the space. */
    private inline fun <T> locked(body: () -> T): T {
        lockedDepth += 1
        try {
            return body()
        } finally {
            lockedDepth -= 1
        }
    }

    private fun fits(height: Float): Boolean = y - height >= bottom

    /** What [cutRow] answers: the part that fits here, and the part that does not. */
    private class RowCut(
        val head: List<List<Line>>,
        val headHeight: Float,
        val tail: List<List<Line>>?,
        val tailHeight: Float,
    )

    /**
     * How much of a table row can be drawn here, and what is left over for the next page.
     *
     * ── A CELL TALLER THAN THE TEXT COLUMN USED TO BE DRAWN OFF THE BOTTOM OF THE SHEET ───────
     *
     * [simpleGrid] and [blockTable]'s `drawRow` draw a row inside [locked] after a single [ensure],
     * and that is right for every row that fits on a page: it is what stops a break landing between
     * two lines of one cell, with the row's background rectangle and its vertical rules on one page
     * and the words on the next. But a row TALLER than a page never fits, the lock suppresses every
     * break, and `y` simply kept going negative. On the server, where the same defect was measured,
     * one 6,000-character LONG_TEXT answer put 395 of 1,092 text pieces below y=0 — drawn outside
     * the paper, where no reader ever sees them. The .docx generated from the same document keeps
     * every one of them, because Word splits a `<w:tr>` carrying no `<w:cantSplit/>`.
     *
     * Clipping would have been shorter and is the wrong answer: the rule this repository states in
     * `stage_schema.coerce_value` is A REFUSAL, NOT A TRUNCATION. So the row is CUT instead — the
     * same thing the .docx does — and the caller draws the pieces on consecutive pages.
     *
     * `null` means "nothing useful fits here; turn the page and ask again", which is how the caller
     * learns to break WITHOUT this ever breaking a page itself. A [RowCut] whose `tail` is null is
     * the ordinary case: the whole row fits and the caller draws exactly what it always drew.
     *
     * [force] is set by a caller that has just turned the page, and defends only the pathological
     * document whose single line of type is taller than a sheet: without it that row turns pages for
     * ever.
     */
    private fun cutRow(
        columns: List<List<Line>>,
        height: Float,
        padding: Float,
        force: Boolean = false,
    ): RowCut? {
        if (lockedDepth > 0) {
            // Inside a region the caller has already reserved and sized — the cover — a row may not
            // break at all. That is the whole meaning of [locked], and [blockCover] depends on it.
            return RowCut(columns, height, null, 0f)
        }
        if (fits(height)) return RowCut(columns, height, null, 0f)
        if (height <= top - 6 * MM - bottom && !force) {
            // It fits on a page, just not on what is left of this one. An ordinary break.
            return null
        }
        val budget = y - bottom - padding
        val head = ArrayList<List<Line>>(columns.size)
        val tail = ArrayList<List<Line>>(columns.size)
        var used = 0f
        var left = 0f
        for (column in columns) {
            var taken = 0f
            var k = 0
            while (k < column.size && taken + column[k].height <= budget) {
                taken += column[k].height
                k += 1
            }
            if (force && k == 0 && column.isNotEmpty()) {
                taken = column[0].height
                k = 1
            }
            head.add(column.subList(0, k).toList())
            val rest = column.subList(k, column.size).toList()
            tail.add(rest)
            used = max(used, taken)
            left = max(left, rest.sumOf { it.height.toDouble() }.toFloat())
        }
        if (used <= 0f) return null
        if (tail.all { it.isEmpty() }) return RowCut(head, used + padding, null, 0f)
        return RowCut(head, used + padding, tail, left + padding)
    }

    /**
     * Running head and foot. Suppressed on page 1, which is the cover.
     *
     * BOTH ARE WRAPPED, because both are free text a designer types into stage 20 and neither was.
     * `headerText` defaults to "craft — cluster" and `footerText` to "template · workshop code", and
     * a real ministry line measured 509.5 pt on the server against a 453.5 pt text column: the single
     * `drawText` this used to be put the running head 265 pt off the LEFT edge of the sheet and the
     * running foot 265 pt past the right one, on every page. The .docx wraps the same line inside its
     * header part, so one download carried two different running heads.
     *
     * The lines stack INTO THE MARGIN — upward for the head, downward for the foot — and never into
     * the text column, so nothing here moves `y` and pagination is untouched. The margin is finite,
     * so the stack stops at the edge of the sheet, and both ends drop the TAIL of the line — see the
     * note inside on why the head had to be turned round to do that. What was dropped is counted in
     * [clippedFurnitureLines]; the server logs the same fact, and neither number reaches a designer
     * on the handset yet.
     */
    private fun drawFurniture() {
        if (pageNo <= 1) return
        val meta = doc.meta
        val t = theme
        if (meta.headerText.isNotEmpty()) {
            val lines = wrap(runsOf(meta.headerText), textW, 7.8f)
            // WHEN IT HAS TO DROP SOMETHING, BOTH ENDS DROP THE TAIL. The head is stacked upward, so
            // the natural loop — walk the wrapped lines backwards from the rule and stop at the sheet
            // edge — dropped its FIRST lines instead, which on a ministry running head is the
            // organisation that owns the document, while the foot below drops its tail. Two ends of
            // the same clipped line in one file is not a decision, it is an accident of which
            // direction each loop ran. A running line is read from its start, so how many fit is
            // counted from the FIRST line here and the kept lines are placed bottom-up after.
            val kept = ArrayList<Line>()
            var baseline = pageH - margin + 4 * MM
            for (line in lines) {
                if (kept.isNotEmpty() && baseline + line.height > pageH - 2 * MM) break
                kept.add(line)
                baseline += line.height
            }
            // Bottom line nearest the rule, the rest climbing towards the edge of the sheet.
            baseline = pageH - margin + 4 * MM
            for (line in kept.asReversed()) {
                drawLine(line, margin, textW, Align.RIGHT, t.muted, baseline)
                baseline += line.height
            }
            clippedFurnitureLines += lines.size - kept.size
            strokePaint.color = argb(t.rule)
            strokePaint.strokeWidth = 0.5f
            val ruleY = cy(pageH - margin + 2.6f * MM)
            canvas.drawLine(margin, ruleY, pageW - margin, ruleY, strokePaint)
        }
        val footY = margin - 4 * MM
        strokePaint.color = argb(t.rule)
        strokePaint.strokeWidth = 0.5f
        canvas.drawLine(margin, cy(footY + 4 * MM), pageW - margin, cy(footY + 4 * MM), strokePaint)
        val pageLabel = if (meta.showPageNumbers) "Page $pageNo" else ""
        val pageLabelW =
            if (pageLabel.isNotEmpty()) stringWidth(pageLabel, regularFace, 7.8f) else 0f
        // The foot shares its first line with the page number, so it gets the column MINUS that.
        val footW = max(textW - (if (pageLabel.isNotEmpty()) pageLabelW + 4 * MM else 0f), 20 * MM)
        if (meta.footerText.isNotEmpty()) {
            val lines = wrap(runsOf(meta.footerText), footW, 7.8f)
            var baseline = footY
            var placed = 0
            for (line in lines) {
                if (placed > 0 && baseline - line.height < 2 * MM) break
                drawLine(line, margin, footW, Align.LEFT, t.muted, baseline)
                baseline -= line.height
                placed += 1
            }
            clippedFurnitureLines += lines.size - placed
        }
        if (pageLabel.isNotEmpty()) {
            textPaint.typeface = regularFace
            textPaint.textSize = 7.8f
            textPaint.color = argb(t.muted)
            canvas.drawText(pageLabel, pageW - margin - pageLabelW, cy(footY), textPaint)
        }
    }

    // -- drawing helpers ---------------------------------------------------------------

    private fun drawLines(
        lines: List<Line>,
        x: Float,
        width: Float,
        align: Align,
        defaultColor: String,
    ) {
        for (line in lines) {
            ensure(line.height)
            if (drawing) drawLine(line, x, width, align, defaultColor, y - line.height * 0.78f)
            y -= line.height
        }
    }

    /**
     * Draw ONE laid-out line at an explicit baseline, leaving the cursor alone.
     *
     * Split out of [drawLines] so the running head and foot can be wrapped like any other text: they
     * are drawn in the MARGIN, outside the text column, so they must not move `y` — and everything a
     * line needs beyond its glyphs (the highlight fill, the underline and strike rules, the per-piece
     * face and colour) has to keep working there too. The server splits the same method for the same
     * reason; see `report_pdf._draw_line`.
     */
    private fun drawLine(
        line: Line,
        x: Float,
        width: Float,
        align: Align,
        defaultColor: String,
        baseline: Float,
    ) {
        var cursor = when (align) {
            Align.CENTER -> x + (width - line.width) / 2
            Align.RIGHT -> x + width - line.width
            else -> x
        }
        for (piece in line.pieces) {
            // `rise` is 0 for ordinary text, so this is the same baseline it always was.
            // The RULES below use it too: an underline under a subscript belongs under the
            // subscript, not under the line it was lowered from.
            val pieceBaseline = baseline + piece.rise
            textPaint.typeface = piece.face
            textPaint.textSize = piece.size
            val advance = textPaint.measureText(piece.text)
            if (piece.highlight) {
                // BEFORE the glyphs, or the fill paints over the words. The box is the
                // piece's own extent — the same unit the underline rule uses below, and the
                // only one that stops a highlighted phrase tinting the whole line. The
                // trailing space each token carries is deliberately inside it: Word's own
                // highlight runs through the spaces of a highlighted phrase, and a gapped
                // fill reads as several highlights rather than one. The same rectangle the
                // server draws — `report_pdf._draw_lines`, to the same fractions.
                fillPaint.color = argb(HIGHLIGHT_FILL)
                canvas.drawRect(
                    cursor,
                    cy(pieceBaseline + piece.size * 0.90f),
                    cursor + advance,
                    cy(pieceBaseline - piece.size * 0.24f),
                    fillPaint,
                )
            }
            textPaint.color = argb(piece.color ?: defaultColor)
            canvas.drawText(piece.text, cursor, cy(pieceBaseline), textPaint)
            if ((piece.underline || piece.strike) && piece.text.isNotBlank()) {
                // RULED PER PIECE, NOT PER LINE. A line where only one phrase is underlined
                // must not get a rule under the whole of it, and the wrap has already split
                // the line into pieces at exactly the run boundaries where the marks change —
                // so the piece is the only unit with the right extent. Ruling per line was the
                // first version and it underlined entire sentences around a single emphasised
                // word.
                //
                // The `text.isNotBlank()` guard stops the trailing space that the wrap keeps
                // attached to each token from extending the rule past the last word and into
                // the gap before the next one, which reads as a rule joining two words that
                // are not both underlined.
                //
                // The offsets are FRACTIONS OF THE POINT SIZE rather than a fixed gap, so they
                // track the type: the same 0.13 / 0.26 / max(0.4, 0.05) the server's ReportLab
                // renderer uses, which is what makes the underline sit in the same place in
                // both PDFs of one report. Note these are computed in PDF space — y grows
                // UPWARD — so `baseline - size * 0.13` is BELOW the baseline and
                // `baseline + size * 0.26` cuts through the x-height. cy() does the flip, once,
                // at the moment the coordinate is handed to the canvas; applying the signs the
                // canvas way here would draw the underline through the ascenders.
                strokePaint.color = argb(piece.color ?: defaultColor)
                strokePaint.strokeWidth = max(0.4f, piece.size * 0.05f)
                if (piece.underline) {
                    val ruleY = cy(pieceBaseline - piece.size * 0.13f)
                    canvas.drawLine(cursor, ruleY, cursor + advance, ruleY, strokePaint)
                }
                if (piece.strike) {
                    val ruleY = cy(pieceBaseline + piece.size * 0.26f)
                    canvas.drawLine(cursor, ruleY, cursor + advance, ruleY, strokePaint)
                }
            }
            cursor += advance
        }
    }

    /**
     * The intrinsic pixel size of a photo, cached by source.
     *
     * WHAT THE PROBE ACTUALLY COSTS, because the sentence that used to stand here said "a few
     * kilobytes per photo" and stopped the next reader noticing otherwise. `inJustDecodeBounds`
     * makes the DECODE read the header only — that part is true and is why no bitmap is allocated —
     * but the bytes it decodes from still have to be FETCHED, and [loadImage] on this surface is
     * `File(source).readBytes()`: the entire 4-6 MB file into a fresh array, every call. The probe is
     * a whole-file read that then looks at the first few hundred bytes of it.
     *
     * THE CACHE IS THEREFORE THE POINT, not an optimisation. It is what makes the measuring pass —
     * which runs up to three times to settle the contents page — cost ONE read per photograph rather
     * than three, and what makes the drawing pass's [drawImage] cost none.
     *
     * The BYTES are deliberately not cached alongside the size. Holding sixty full-resolution JPEGs
     * is enough to be killed on a mid-range phone even with `largeHeap`, so the loader is asked again
     * at draw time, when the bytes are needed for a few milliseconds and then freed. Two whole-file
     * reads per printed photograph is the floor this trade sets, and it is the right trade: the
     * alternative is the export dying with an officer waiting.
     */
    private val sizeCache = HashMap<String, Pair<Int, Int>?>()

    /**
     * The bytes behind [ref] — a figure this renderer made, or a photograph the loader must fetch.
     *
     * Figures first. [loadImage] resolves a media id against the device's local copy of a photograph,
     * and a synthetic `figure:N` source would resolve to nothing, be recorded as a dropped image and
     * remove the map from the report with a warning naming a source the designer has never seen.
     */
    private fun bytesFor(ref: ImageRef): ByteArray? = figures[ref.source] ?: loadImage(ref)

    private fun intrinsicSize(ref: ImageRef): Pair<Int, Int>? {
        if (sizeCache.containsKey(ref.source)) return sizeCache[ref.source]
        var size: Pair<Int, Int>? = null
        val data = bytesFor(ref)
        if (data != null && data.isNotEmpty()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) size = opts.outWidth to opts.outHeight
        }
        // An undecodable photo drops with a warning; it never aborts the export. A report missing
        // one picture is still worth having in the field.
        if (size == null) droppedImages.add(ref.source)
        sizeCache[ref.source] = size
        return size
    }

    /**
     * The power of two to decode at so a photo lands near [reqW] x [reqH] device pixels.
     *
     * Halving until the next halving would go UNDER the request (rather than at or under it) keeps
     * the decoded bitmap at least as large as the box it fills; the alternative rounds a 45 mm grid
     * photo down to a visibly blocky one.
     */
    private fun sampleSizeFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) sample *= 2
        return sample
    }

    /**
     * Decode [ref] downsampled to the box it will occupy.
     *
     * [intrinsic] is the photo's FILE-orientation size, which [drawImage] has already obtained from
     * [intrinsicSize] for this same source. Taking it as an argument rather than re-running
     * `inJustDecodeBounds` here is not a micro-optimisation: that probe costs a whole-file read (see
     * [sizeCache]), so re-running it made every printed photograph three whole-file reads instead of
     * two, on the export a designer runs at the close of the workshop with an officer waiting.
     */
    private fun decodeFitted(
        ref: ImageRef,
        intrinsic: Pair<Int, Int>,
        boxW: Float,
        boxH: Float,
    ): Bitmap? {
        if (intrinsic.first <= 0 || intrinsic.second <= 0) return null
        val data = bytesFor(ref) ?: return null
        if (data.isEmpty()) return null
        // The box is expressed in the DISPLAY orientation, so undo the quarter turn before asking
        // for a sample size — otherwise a portrait photo shown landscape is downsampled against the
        // wrong axis and comes back either blurry or twice the size it needed to be.
        val quarter = ref.rotationDeg == 90 || ref.rotationDeg == 270
        val reqW = ((if (quarter) boxH else boxW) * IMAGE_OVERSAMPLE).roundToInt()
        val reqH = ((if (quarter) boxW else boxH) * IMAGE_OVERSAMPLE).roundToInt()
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(intrinsic.first, intrinsic.second, reqW, reqH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, opts) }.getOrNull()
    }

    /**
     * The box [drawImage] will fit [ref] into, or null if it cannot draw it.
     *
     * Split out so a caller can RESERVE the picture's height before committing to anything —
     * [blockFigure] has to keep a chart's title on the same page as the chart, and it cannot do that
     * by guessing. Cheap to call twice: [intrinsicSize] is cached by source.
     */
    private fun imageBox(ref: ImageRef, width: Float, maxHeight: Float): Pair<Float, Float>? {
        val size = intrinsicSize(ref) ?: return null
        var iw = size.first
        var ih = size.second
        if (ref.rotationDeg == 90 || ref.rotationDeg == 270) {
            val t = iw
            iw = ih
            ih = t
        }
        val aspect = if (iw > 0 && ih > 0) iw.toFloat() / ih.toFloat() else ref.aspect

        var w = width
        var h = if (aspect != 0f) w / aspect else width * 0.75f
        if (h > maxHeight) {
            h = maxHeight
            w = h * aspect
        }
        return w to h
    }

    /** Draw [ref] fitted into the box and return the height it consumed. */
    private fun drawImage(
        ref: ImageRef,
        x: Float,
        width: Float,
        maxHeight: Float,
        align: Align,
    ): Float {
        val size = intrinsicSize(ref) ?: return 0f
        val box = imageBox(ref, width, maxHeight) ?: return 0f
        val w = box.first
        val h = box.second

        ensure(h)
        if (drawing) {
            val dx = when (align) {
                Align.CENTER -> x + (width - w) / 2
                Align.RIGHT -> x + width - w
                else -> x
            }
            val bitmap = decodeFitted(ref, size, w, h)
            if (bitmap != null) {
                // THE EXIF QUARTER TURN, as a Matrix. This client stores camera output unrotated
                // with the orientation only in EXIF, so skipping this lays every portrait photo of a
                // loom on its side. Android's Canvas rotates clockwise in its y-down space, which is
                // the same visual direction EXIF means by "rotate 90" — and the negation the server
                // needs (`rotate(-rotation_deg)` in ReportLab's y-up space) is exactly the flip
                // already accounted for by cy(). Do not copy the minus sign across.
                val quarter = ref.rotationDeg == 90 || ref.rotationDeg == 270
                val targetW = if (quarter) h else w
                val targetH = if (quarter) w else h
                val m = Matrix()
                m.postScale(targetW / bitmap.width, targetH / bitmap.height)
                m.postRotate(ref.rotationDeg.toFloat())
                // Rotating about the origin moves the content off the box; map the bitmap's own rect
                // through the matrix and translate by whatever the result's corner turned out to be,
                // which is correct for all four quarter turns without four special cases.
                val boundsRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                m.mapRect(boundsRect)
                m.postTranslate(dx - boundsRect.left, cy(y) - boundsRect.top)
                canvas.drawBitmap(bitmap, m, bitmapPaint)
                // Freed immediately, not left to the GC: a sixty-photo annexure holds one bitmap at a
                // time this way and the whole report's worth otherwise.
                bitmap.recycle()
            } else {
                // A PHOTOGRAPH THAT PROBED AND THEN WOULD NOT DECODE IS REPORTED HERE, and it has to
                // be reported here now that [sizeCache] survives the measure->draw boundary. It used
                // to be cleared wholesale, so the drawing pass re-probed every image and [intrinsicSize]
                // recorded the drop itself; keeping the successful probes is what took a printed photo
                // from three whole-file reads to two, and it also took away the only place this
                // failure was noticed. Without this the picture is a blank space and
                // "N photograph(s) could not be embedded" — the one sentence that tells the designer
                // a picture is gone — says nothing at all.
                droppedImages.add(ref.source)
            }
        }
        y -= h
        return h
    }

    private fun caption(text: String, x: Float, width: Float) {
        if (text.isEmpty()) return
        y -= 1.4f * MM
        drawLines(wrap(runsOf(text, italic = true), width, 7.8f), x, width, Align.CENTER, theme.muted)
        y -= 2.2f * MM
    }

    // -- blocks --------------------------------------------------------------------------

    /**
     * Draw the cover as EXACTLY one page.
     *
     * Everything here is measured before anything is drawn, and the hero photograph absorbs whatever
     * vertical space is left over. Laying the cover out top-down with page breaks enabled — which is
     * what the first version did — pushed the closing "Submitted to …" line onto a second, otherwise
     * blank page whenever the title wrapped to two lines or the info table gained a row. A cover that
     * silently becomes two pages also shifts every page number in the table of contents, so the
     * symptom shows up somewhere else entirely.
     */
    private fun blockCover(block: CoverBlock) {
        val t = theme
        val x = margin
        val logoH = if (block.logo != null) 26 * MM else 0f
        val orgLines = block.orgLines.map { wrap(runsOf(it), textW, 10.5f) }
        val titleLines = wrap(runsOf(block.title, bold = true), textW, 26f)
        val subtitleLines = if (block.subtitle.isNotEmpty()) {
            wrap(runsOf(block.subtitle, italic = true), textW, 13f)
        } else emptyList()
        val footerLines = block.footerLines.map { wrap(runsOf(it), textW, 8.6f) }

        fun total(groups: List<List<Line>>): Float =
            groups.sumOf { group -> group.sumOf { it.height.toDouble() } }.toFloat()

        val labelW = textW * 0.32f
        var infoH = 0f
        if (block.infoRows.isNotEmpty()) {
            for ((label, value) in block.infoRows) {
                val lab = wrap(runsOf(label, bold = true), labelW - 3 * MM, baseSize - 0.6f)
                val v = wrap(runsOf(value), textW - labelW - 3 * MM, baseSize)
                infoH += max(
                    lab.sumOf { it.height.toDouble() }.toFloat(),
                    v.sumOf { it.height.toDouble() }.toFloat(),
                ) + 1.8f * MM
            }
            infoH += 2.4f * MM
        }

        val gaps = (18 + 8 + 14 + 3 + 8 + 6) * MM
        val fixed = logoH + total(orgLines) +
            titleLines.sumOf { it.height.toDouble() }.toFloat() +
            subtitleLines.sumOf { it.height.toDouble() }.toFloat() +
            infoH + total(footerLines) + gaps
        val available = top - bottom
        val heroH = if (block.heroImage != null) {
            max(0f, min(62 * MM, available - fixed - 8 * MM))
        } else 0f

        locked {
            y = top - 18 * MM
            if (block.logo != null) {
                drawImage(block.logo, x, textW, logoH, Align.CENTER)
                y -= 8 * MM
            }
            for (group in orgLines) drawLines(group, x, textW, Align.CENTER, t.muted)
            y -= 14 * MM
            drawLines(titleLines, x, textW, Align.CENTER, t.accent)
            y -= 3 * MM
            if (subtitleLines.isNotEmpty()) {
                drawLines(subtitleLines, x, textW, Align.CENTER, t.accentSoft)
            }
            y -= 8 * MM
            if (block.heroImage != null && heroH > 18 * MM) {
                // Below about 18 mm the photograph is a smudge; dropping it is better than printing
                // one, and the cover still carries the title and the info table.
                drawImage(block.heroImage, x, textW * 0.8f, heroH, Align.CENTER)
                y -= 8 * MM
            }
            if (block.infoRows.isNotEmpty()) {
                simpleGrid(block.infoRows.map { (label, value) -> label to runsOf(value) }, 32.0f)
            }
            y -= 6 * MM
            for (group in footerLines) drawLines(group, x, textW, Align.CENTER, t.muted)
        }
        newPage()
    }

    private fun blockToc(block: TocBlock) {
        val t = theme
        val x = margin
        drawLines(
            wrap(runsOf(block.title, bold = true), textW, 16f), x, textW, Align.LEFT, t.accent
        )
        y -= 4 * MM
        // ── A CONTENTS ENTRY IS WRAPPED LIKE EVERY OTHER PIECE OF TEXT IN THIS FILE ─────────────
        //
        // It used to be a bare `drawText`, so a section title ran straight off the sheet: measured on
        // the server, the heading "Prototype development and iterative refinement of the traditional
        // Sambalpuri ikat weave for contemporary furnishing applications in the Bargarh cluster"
        // makes a 688.6 pt contents line against a 453.5 pt column — 164.2 pt past the edge of the
        // paper, taking its own page number with it. Section titles are the designer's stage names,
        // so this is ordinary content and not a pathological case. The page number and the dot leader
        // ride on the LAST line, where the eye expects to find them.
        for (entry in tocEntries) {
            if (entry.level > block.depth) continue
            val indent = (entry.level - 1) * 6 * MM
            val size = if (entry.level == 1) 10.0f else 9.2f
            val label = if (entry.number.isNotEmpty()) "${entry.number}. ${entry.text}" else entry.text
            val pageLabel = headingPages[entry.bookmark]?.toString() ?: ""
            val face = if (entry.level == 1) boldFace else regularFace
            // ── THE GUTTER IS RESERVED IN BOTH PASSES, NUMBER OR NO NUMBER ──────────────────────
            //
            // Sizing the wrap column from `pageLabel` makes the measuring pass and the drawing pass
            // see DIFFERENT geometry, which is the one thing neither renderer may do. Here the first
            // measuring pass has an empty [headingPages] and every later one does not; on the
            // `iterations >= 3` exit the map handed to the drawing pass is not the one the last
            // measuring pass wrapped against either. The server has the same shape and it was
            // measured there: entries wrapped into 453.5 pt while measuring and 431.4 pt while
            // drawing, which for a 60-section report put the contents on 5 pages when measured and 6
            // when drawn — all 60 printed numbers one page short, in a file whose bookmark outline is
            // perfectly correct.
            //
            // So the room for the number comes from a CONSTANT: four of the widest digit, the widest
            // label a 9,999-page report can print. `pageLabel` still governs whether a number and a
            // leader are DRAWN — it no longer governs how wide the column is.
            var digitW = 0f
            for (digit in "0123456789") {
                digitW = max(digitW, stringWidth(digit.toString(), face, size))
            }
            val gutter = 4 * digitW
            // The number and its leader need room on the last line, so the label gets the column
            // minus both. Floored, because a deep indent plus a wide number must still leave a
            // column to wrap into rather than a negative one.
            val avail = max(textW - indent - (gutter + 4 * MM), 30 * MM)
            val color = if (entry.level == 1) t.ink else t.muted
            // 1.6 is the entry leading this block has always used; keeping it as the wrap's leading
            // factor is what makes a one-line entry occupy exactly what it used to.
            val lines =
                wrap(runsOf(label, bold = entry.level == 1), avail, size, leadingFactor = 1.6f)
            drawLines(lines, x + indent, avail, Align.LEFT, color)
            if (pageLabel.isNotEmpty() && drawing) {
                val last = lines.last()
                // [drawLines] has left the cursor under the last line; its baseline is the same 0.78
                // of the leading above that, which is where the number must sit.
                val baseline = y + last.height * 0.22f
                textPaint.typeface = face
                textPaint.textSize = size
                textPaint.color = argb(color)
                val pageLabelW = stringWidth(pageLabel, face, size)
                canvas.drawText(pageLabel, x + textW - pageLabelW, cy(baseline), textPaint)
                // Dot leader between the title and the page number. It runs up to the NUMBER'S own
                // width, not to the reserved gutter: the gutter is sized for the widest label the
                // document could carry, and stopping the dots there would leave a visible gap in
                // front of every number shorter than that.
                val leadFrom = x + indent + last.width + 2 * MM
                val leadTo = x + textW - pageLabelW - 2 * MM
                if (leadTo > leadFrom) {
                    textPaint.color = argb(t.rule)
                    val dotW = textPaint.measureText(".")
                    val count = max(0, ((leadTo - leadFrom) / max(dotW, 0.1f)).toInt())
                    canvas.drawText(".".repeat(count), leadFrom, cy(baseline), textPaint)
                }
            }
        }
        newPage()
    }

    private fun blockHeading(block: HeadingBlock) {
        val t = theme
        val level = block.level.coerceIn(1, 4)
        val size = floatArrayOf(17.0f, 13.5f, 11.5f, 10.5f)[level - 1]
        val color = listOf(t.accent, t.accent, t.accentSoft, t.muted)[level - 1]
        val label = if (block.number.isNotEmpty()) "${block.number}. " else ""
        val runs = runsOf(label + runsText(block.runs), bold = true)
        val lines = wrap(runs, textW, size)
        // ── KEEPNEXT HAS TO RESERVE WHAT THE HEADING ACTUALLY COSTS, WHICH IS NOT ITS LINES ─────
        //
        // This reserved a flat 6 mm (17.0 pt) around the text. A level-1 heading spends 6.5 mm above
        // it, 1.2 mm on the rule beneath it and 2.4 mm after that — 10.1 mm, 28.6 pt — and every one
        // of those millimetres is taken AFTER the `ensure` that was supposed to account for them. So
        // a heading could pass the fit test and then walk past the bottom margin on its own: measured
        // on the server over 300 level-1 headings, 13 ended with less than one body line beneath them
        // and the worst finished 5.7 pt BELOW the margin, while the comment above the reservation
        // asserted that could not happen. Both siblings deliver keepNext properly — the .docx sets
        // `<w:keepNext/>` and the web report sheet uses `break-after: avoid`.
        //
        // Bound once, spent below, so the reservation is true by construction. The trailing body line
        // is the other half of keepNext: a heading with nothing under it is an orphan even if it fits.
        val lead = (if (level > 1) 4.5f else 6.5f) * MM
        val trail = (if (level == 1) 1.2f * MM else 0f) + 2.4f * MM
        val needed = lead + lines.sumOf { it.height.toDouble() }.toFloat() + trail + baseSize * 1.32f
        ensure(needed)
        y -= lead
        if (!drawing) {
            // Only a MEASURING pass builds the index. Appending during the drawing pass would print
            // every entry twice, because the drawing pass walks the same block list again.
            pendingPages[block.bookmark] = pageNo
            pendingEntries.add(TocEntry(level, block.number, runsText(block.runs), block.bookmark))
        }
        drawLines(lines, margin, textW, Align.LEFT, color)
        if (level == 1) {
            // ── THE `drawing` FLAG MAY GUARD A DRAW CALL AND MUST NEVER GUARD A CHANGE TO `y` ───
            //
            // The rule under a level-1 heading COSTS 1.2 mm of vertical space, and the whole block —
            // the space AND the stroke — used to sit inside `if (level == 1 && drawing)`. So the
            // measuring pass believed a level-1 heading was 1.2 mm shorter than the drawing pass would
            // make it, over a hundred and fifty of them in a real report: the drawn document ran long
            // enough to break pages the measuring pass had not, and every contents entry past the
            // first drift across a page boundary pointed at the wrong page.
            //
            // This is the THIRD time this exact class has shipped. [newPage] carries the first
            // ("THE `drawing` FLAG MUST NOT APPEAR IN THIS CONDITION"), `report_pdf._block_heading`
            // carries the second, on this same rule, on the server. Prose did not stop it, so
            // `test_report_parity.py` now asserts the SHAPE of these six lines as well.
            //
            // It hides behind the font: 1.2 mm x 150 headings is under a page of raw drift, and
            // whether that moves a section across a boundary depends on where the text wraps, which
            // depends on the face. The server's contents test passed under Nirmala and failed under
            // DejaVu on the identical commit.
            y -= 1.2f * MM
            if (drawing) {
                strokePaint.color = argb(t.rule)
                strokePaint.strokeWidth = 0.7f
                canvas.drawLine(margin, cy(y), margin + textW, cy(y), strokePaint)
            }
        }
        y -= 2.4f * MM
        // `lead` and `trail` above reserved exactly what has now been spent: 6.5 + 1.2 + 2.4 mm at
        // level 1, 4.5 + 2.4 mm below it. Change a gap here and change it there.
    }

    private fun blockParagraph(block: ParagraphBlock) {
        val t = theme
        val size: Float
        val color: String
        val indent: Float
        val italic: Boolean
        when (block.style) {
            ParaStyle.BODY -> { size = baseSize; color = t.ink; indent = 0f; italic = false }
            ParaStyle.LEAD -> { size = baseSize + 1.4f; color = t.ink; indent = 0f; italic = false }
            ParaStyle.NOTE -> { size = baseSize - 1.4f; color = t.muted; indent = 0f; italic = false }
            ParaStyle.QUOTE -> { size = baseSize; color = t.accentSoft; indent = 7 * MM; italic = true }
            ParaStyle.CAPTION -> { size = baseSize - 2.0f; color = t.muted; indent = 0f; italic = true }
            ParaStyle.COVER_LINE -> { size = baseSize + 0.5f; color = t.muted; indent = 0f; italic = false }
        }
        val runs = if (italic) block.runs.map { it.copy(italic = true) } else block.runs
        val width = textW - indent
        val lines = wrap(runs, width, size)
        val quoteTop = y
        drawLines(lines, margin + indent, width, block.align, color)
        if (block.style == ParaStyle.QUOTE && drawing) {
            strokePaint.color = argb(t.accentSoft)
            strokePaint.strokeWidth = 1.6f
            canvas.drawLine(margin + 2 * MM, cy(quoteTop), margin + 2 * MM, cy(y), strokePaint)
        }
        y -= 2.6f * MM
    }

    private fun blockBullets(block: BulletListBlock) {
        val t = theme
        val indent = 6 * MM
        val width = textW - indent
        block.items.forEachIndexed { i, item ->
            val marker = if (block.ordered) "${i + 1}." else "•"
            val lines = wrap(item, width, baseSize)
            ensure(lines[0].height)
            if (drawing) {
                textPaint.typeface = regularFace
                textPaint.textSize = baseSize
                textPaint.color = argb(t.accentSoft)
                canvas.drawText(marker, margin + 1.6f * MM, cy(y - lines[0].height * 0.78f), textPaint)
            }
            drawLines(lines, margin + indent, width, Align.LEFT, t.ink)
            y -= 1.1f * MM
        }
        y -= 1.6f * MM
    }

    /** The borderless label/value grid shared by the cover and [KeyValueBlock]. */
    private fun simpleGrid(pairs: List<Pair<String, List<Run>>>, labelPct: Float = 30.0f) {
        val t = theme
        val labelW = textW * labelPct / 100
        val valueW = textW - labelW
        pairs.forEachIndexed { i, (label, value) ->
            val labLines = wrap(runsOf(label, bold = true), labelW - 3 * MM, baseSize - 0.6f)
            val valLines = wrap(value, valueW - 3 * MM, baseSize)
            val height = max(
                labLines.sumOf { it.height.toDouble() }.toFloat(),
                valLines.sumOf { it.height.toDouble() }.toFloat(),
            ) + 1.8f * MM
            // A value taller than the page is cut across pages instead of being drawn off the
            // bottom of this one; see [cutRow], which is also what keeps the COVER's info grid
            // unbroken (it runs inside [locked], and a cut there is refused).
            var rest: List<List<Line>> = listOf(labLines, valLines)
            var restH = height
            var turned = false
            while (true) {
                val cut = cutRow(rest, restH, 1.8f * MM, force = turned)
                if (cut == null) {
                    newPage()
                    turned = true
                    continue
                }
                turned = false
                val rowTop = y
                if (drawing && i % 2 == 1) {
                    fillPaint.color = argb(t.zebraFill)
                    canvas.drawRect(
                        margin, cy(rowTop), margin + textW, cy(rowTop - cut.headHeight), fillPaint
                    )
                }
                locked {
                    y = rowTop - 0.9f * MM
                    drawLines(cut.head[0], margin + 1.5f * MM, labelW - 3 * MM, Align.LEFT, t.muted)
                    y = rowTop - 0.9f * MM
                    drawLines(cut.head[1], margin + labelW, valueW - 3 * MM, Align.LEFT, t.ink)
                }
                y = rowTop - cut.headHeight
                val tail = cut.tail ?: break
                rest = tail
                restH = cut.tailHeight
            }
        }
        y -= 2.4f * MM
    }

    private fun blockKeyValues(block: KeyValueBlock) {
        simpleGrid(block.pairs, block.labelWidthPct)
    }

    private fun blockTable(block: TableBlock) {
        if (block.columns.isEmpty()) return
        val t = theme
        val widths = block.columns.map { textW * it.widthPct / 100 }
        val pad = 1.6f * MM
        val headerSize = baseSize - 1.2f
        val bodySize = baseSize - 0.8f

        fun rowLines(cells: List<List<Run>>, size: Float, bold: Boolean): Pair<List<List<Line>>, Float> {
            val laid = widths.mapIndexed { j, w ->
                var value = if (j < cells.size) cells[j] else emptyList()
                if (bold) value = value.map { it.copy(bold = true) }
                wrap(value, w - 2 * pad, size)
            }
            val height = laid.maxOfOrNull { col -> col.sumOf { it.height.toDouble() }.toFloat() }
                ?: size
            return laid to (height + 1.8f * MM)
        }

        /**
         * Draw one row at the cursor. The caller guarantees it fits; this never breaks a page.
         *
         * [bottomRule] is false for every fragment of a row [cutRow] split across a page boundary
         * except the last: a rule under the half-row would read as the end of a record that is in
         * fact continued overleaf.
         */
        fun drawRow(
            laid: List<List<Line>>,
            height: Float,
            fill: String?,
            textColor: String,
            topRule: Boolean = false,
            bottomRule: Boolean = true,
        ) {
            val rowTop = y
            if (drawing) {
                if (fill != null) {
                    fillPaint.color = argb(fill)
                    canvas.drawRect(margin, cy(rowTop), margin + textW, cy(rowTop - height), fillPaint)
                }
                if (bottomRule) {
                    strokePaint.color = argb(t.rule)
                    strokePaint.strokeWidth = 0.4f
                    canvas.drawLine(
                        margin, cy(rowTop - height), margin + textW, cy(rowTop - height), strokePaint
                    )
                }
                if (topRule) {
                    strokePaint.color = argb(t.accent)
                    strokePaint.strokeWidth = 0.9f
                    canvas.drawLine(margin, cy(rowTop), margin + textW, cy(rowTop), strokePaint)
                }
                var cx = margin
                for (k in 0 until widths.size - 1) {
                    cx += widths[k]
                    strokePaint.color = argb(t.rule)
                    strokePaint.strokeWidth = 0.3f
                    canvas.drawLine(cx, cy(rowTop), cx, cy(rowTop - height), strokePaint)
                }
            }
            locked {
                var cursorX = margin
                block.columns.forEachIndexed { j, col ->
                    val align = if (col.numeric) Align.RIGHT else col.align
                    y = rowTop - 0.9f * MM
                    drawLines(laid[j], cursorX + pad, widths[j] - 2 * pad, align, textColor)
                    cursorX += widths[j]
                }
            }
            y = rowTop - height
        }

        val hasHeader = block.columns.any { it.header.isNotEmpty() }
        val (headerLaid, headerH) = rowLines(block.columns.map { runsOf(it.header) }, headerSize, true)

        /**
         * Put one row on the page, breaking it across pages when it is taller than one.
         *
         * THE BREAK IS DECIDED BEFORE ANYTHING IS DRAWN, so a row is never split by accident and
         * never drawn twice. An older version drew the row, noticed the page had turned, and then
         * redrew both the header and the row — duplicating a line of a cost sheet, which is the one
         * kind of error in this file that changes what the report says rather than how it looks.
         *
         * A row that does not fit on ANY page is cut by [cutRow] and continued on the next, with the
         * header repeated above it exactly as Word repeats a `<w:tblHeader/>` row over a body row it
         * has split. Before that, the lock inside `drawRow` suppressed every break and the rest of
         * the cell was drawn at a negative y — off the paper.
         *
         * [onBreak] is what puts the header back at the top of the new page, and it is passed IN
         * rather than called by name because the header is itself placed through this function: a
         * local function cannot call one declared after it, and a header taller than a page that
         * repeated itself above each of its own fragments would never terminate. The header therefore
         * passes null.
         */
        fun placeRow(
            laid: List<List<Line>>,
            height: Float,
            fill: String?,
            textColor: String,
            topRule: Boolean = false,
            onBreak: (() -> Unit)? = null,
        ) {
            var rest = laid
            var restH = height
            var first = true
            var turned = false
            while (true) {
                val cut = cutRow(rest, restH, 1.8f * MM, force = turned)
                if (cut == null) {
                    newPage()
                    onBreak?.invoke()
                    turned = true
                    // Re-cut against the page the header has just been placed on, not against the
                    // one that was measured before it.
                    continue
                }
                turned = false
                drawRow(
                    cut.head, cut.headHeight, fill, textColor,
                    topRule = topRule && first, bottomRule = cut.tail == null,
                )
                first = false
                val tail = cut.tail ?: return
                rest = tail
                restH = cut.tailHeight
            }
        }

        fun placeHeader() {
            // NEVER with an [onBreak]: a header taller than a page would otherwise place itself above
            // each of its own fragments, for ever.
            if (hasHeader) {
                placeRow(headerLaid, headerH, t.tableHeaderFill, t.tableHeaderText)
            }
        }

        // Reserve the header plus the first body row together: a header stranded at the foot of a
        // page reads as an empty table.
        val rawFirstH =
            if (block.rows.isNotEmpty()) rowLines(block.rows[0], bodySize, false).second else 0f
        // A FIRST ROW TALLER THAN A PAGE CANNOT BE RESERVED WHOLE, and asking for the impossible
        // turned a page the row was going to start on anyway: the table's own first page came out
        // completely blank. What this reservation is for is stopping a header being stranded alone
        // at the foot, so an over-tall row asks for the header plus one line of itself and
        // [placeRow] cuts the rest.
        val firstH =
            if (headerH + rawFirstH > top - 6 * MM - bottom) bodySize * 1.32f + 1.8f * MM
            else rawFirstH
        if (!fits(headerH + firstH)) newPage()
        placeHeader()

        block.rows.forEachIndexed { i, row ->
            val (laid, height) = rowLines(row, bodySize, false)
            placeRow(
                laid, height, if (block.zebra && i % 2 == 1) t.zebraFill else null, t.ink,
                onBreak = { placeHeader() },
            )
        }

        val totalRow = block.totalRow
        if (totalRow != null) {
            val (laid, height) = rowLines(totalRow, bodySize, true)
            // The total must never be orphaned from the figures it totals.
            placeRow(laid, height, t.zebraFill, t.ink, topRule = true, onBreak = { placeHeader() })
        }

        y -= 2.2f * MM
        caption(block.caption, margin, textW)
    }

    private fun blockImage(block: ImageBlock) {
        val width = textW * block.widthPct.coerceIn(5.0f, 100.0f) / 100
        val drawn = drawImage(block.image, margin, width, (top - bottom) * 0.62f, block.align)
        if (drawn > 0f) {
            caption(block.caption, margin, textW)
            y -= 1.6f * MM
        }
    }

    private fun blockImageGrid(block: ImageGridBlock) {
        if (block.images.isEmpty()) return
        var columns = block.columns.coerceIn(1, 4)
        // The same legibility floor DocxWriter applies, so both exports of one report drop to the
        // same column count. Below ~45 mm a photo stops being evidence and becomes a thumbnail.
        while (columns > 1 && (textW / columns) < 45 * MM) columns -= 1
        val cellW = textW / columns
        val capSize = 7.6f

        val maxImageH = (top - bottom) * 0.30f
        var start = 0
        while (start < block.images.size) {
            val chunk = block.images.subList(start, min(start + columns, block.images.size))
            // MEASURED ONCE PER CELL, and the caption is now wrapped in the same italic it is drawn
            // in. The split below needs the picture's height and the caption's LINES apart from
            // each other, which three separate re-measurements could not give it.
            val cells = ArrayList<Triple<ImageRef, Float, List<Line>>>(chunk.size)
            val heights = ArrayList<Float>(chunk.size)
            for ((ref, cap) in chunk) {
                val box = imageBox(ref, cellW - 4 * MM, maxImageH)
                if (box == null) {
                    cells.add(Triple(ref, 0f, emptyList()))
                    heights.add(0f)
                    continue
                }
                val lines =
                    if (cap.isNotEmpty()) wrap(runsOf(cap, italic = true), cellW - 4 * MM, capSize)
                    else emptyList()
                cells.add(Triple(ref, box.second, lines))
                heights.add(box.second + lines.sumOf { it.height.toDouble() }.toFloat() + 3 * MM)
            }
            val rowH = heights.maxOrNull() ?: 0f
            if (rowH <= 0f) {
                start += columns
                continue
            }
            if (rowH > top - 6 * MM - bottom) {
                placeTallGridRow(cells, cellW, maxImageH)
                start += columns
                continue
            }
            ensure(rowH)
            val rowTop = y
            // Locked: a grid row is ONE visual unit. Without this, a tall caption in the last cell
            // breaks the page and the remaining photos of the row land under the header of the next
            // one, detached from their captions.
            locked {
                cells.forEachIndexed { i, (ref, _, lines) ->
                    val x = margin + i * cellW
                    y = rowTop
                    val drawn = drawImage(ref, x + 2 * MM, cellW - 4 * MM, maxImageH, Align.CENTER)
                    if (drawn > 0f && lines.isNotEmpty()) {
                        y -= 1.2f * MM
                        drawLines(lines, x + 2 * MM, cellW - 4 * MM, Align.CENTER, theme.muted)
                    }
                }
            }
            y = rowTop - rowH
            start += columns
        }
        y -= 1.6f * MM
        caption(block.caption, margin, textW)
    }

    /**
     * Draw a grid row whose captions run past the page, without losing a word of them.
     *
     * A photograph is capped at 0.30 of the text column, so the PICTURES always fit and it is a
     * caption that can overrun — and every one of the registry's twenty-five caption fields is a
     * TEXT field with `max_length == 0`, so nothing upstream bounds one. The locked region that
     * keeps a row of photographs together then suppressed the break the row needed and the tail of
     * the caption was drawn below the foot of the sheet: 442 of 1,084 pieces, measured on the
     * server against the identical algorithm.
     *
     * Same answer as [cutRow] gives a table row, in the shape a grid needs: the pictures and as
     * much of every caption as this page holds go here, and each caption continues IN ITS OWN
     * COLUMN overleaf, so a reader still knows which photograph it belongs to.
     */
    private fun placeTallGridRow(
        cells: List<Triple<ImageRef, Float, List<Line>>>,
        cellW: Float,
        maxImageH: Float,
    ) {
        val gap = 1.2f * MM
        val remaining = cells.map { ArrayList(it.third) }
        val imageH = cells.maxOfOrNull { it.second } ?: 0f
        val firstLine = remaining.mapNotNull { it.firstOrNull()?.height }.maxOrNull() ?: 0f
        // The pictures and at least one line of caption belong on the same page as each other.
        ensure(imageH + gap + firstLine + 3 * MM)
        var first = true
        while (true) {
            val rowTop = y
            // Budgeted against the TALLEST picture in the row, so a cell whose own photograph is
            // shorter merely has room to spare rather than a caption that outruns the fragment.
            val room = rowTop - bottom - 3 * MM - (if (first) imageH + gap else 0f)
            val taken = ArrayList<List<Line>>(remaining.size)
            var used = 0f
            for (lines in remaining) {
                var consumed = 0f
                var k = 0
                while (k < lines.size && consumed + lines[k].height <= room) {
                    consumed += lines[k].height
                    k += 1
                }
                if (k == 0 && lines.isNotEmpty()) {
                    // A page too short for one line of caption. Take it anyway; an endless loop is
                    // not the better failure.
                    k = 1
                    consumed = lines[0].height
                }
                taken.add(lines.take(k))
                repeat(k) { lines.removeAt(0) }
                used = max(used, consumed)
            }
            val partH = (if (first) imageH + gap else 0f) + used + 3 * MM
            locked {
                cells.forEachIndexed { i, (ref, _, _) ->
                    val x = margin + i * cellW
                    y = rowTop
                    if (first &&
                        drawImage(ref, x + 2 * MM, cellW - 4 * MM, maxImageH, Align.CENTER) > 0f
                    ) {
                        y -= gap
                    }
                    if (taken[i].isNotEmpty()) {
                        drawLines(taken[i], x + 2 * MM, cellW - 4 * MM, Align.CENTER, theme.muted)
                    }
                }
            }
            y = rowTop - partH
            first = false
            if (remaining.all { it.isEmpty() }) return
            newPage()
        }
    }

    // -- figures: the map and the infographics -------------------------------------------

    /** The drawn width of a figure, in points. */
    private fun figureWidth(widthPct: Float): Float = textW * widthPct.coerceIn(20.0f, 100.0f) / 100

    /**
     * Rasterise [block] once per document and hand back an [ImageRef] for it.
     *
     * Keyed by IDENTITY, not by value. The blocks are data classes and hash perfectly well, but a
     * value key would fold two genuinely equal charts in different sections into one figure the reader
     * sees twice — and the cache exists for a different reason anyway: THIS RENDERER LAYS THE DOCUMENT
     * OUT TWICE. Pass one measures everything with drawing suppressed so the TOC learns each heading's
     * page; pass two draws it. Rasterising India takes seconds on a phone, so a map re-rendered per
     * pass would double that on every PDF export for a picture that cannot have changed between the
     * two passes.
     *
     * A null ENTRY (as opposed to a missing key) means the map's geometry is not on this device —
     * never a chart, which needs no asset. It is cached too, so a document with the same absent map in
     * three sections asks the asset source once.
     */
    private fun figureRef(block: Block): ImageRef? {
        if (figureRefs.containsKey(block)) return figureRefs[block]
        val widthPct = when (block) {
            is MapBlock -> block.widthPct
            is ChartBlock -> block.widthPct
            else -> 100.0f
        }
        val widthPx = pixelsForMm((figureWidth(widthPct) / MM).toDouble())
        val figure = when (block) {
            is MapBlock -> renderMapPng(block, theme, widthPx)
            is ChartBlock -> renderChartPng(block, theme, widthPx)
            else -> null
        }
        var ref: ImageRef? = null
        if (figure != null) {
            val source = "figure:${figures.size + 1}"
            figures[source] = figure.png
            ref = ImageRef(
                source = source, widthPx = figure.widthPx, heightPx = figure.heightPx,
                mimeType = "image/png",
            )
        }
        figureRefs[block] = ref
        return ref
    }

    /**
     * Draw one rasterised figure through the ORDINARY picture path.
     *
     * Short on purpose, and it must stay short. Both blocks are DESCRIPTIONS, not pictures; [ReportMap]
     * and [ReportChart] produce the PNG and everything below is the same [drawImage] a photograph goes
     * through. Drawing either of them with Canvas paths here instead would be a second renderer for a
     * figure the server already knows how to produce, anti-aliased by Skia rather than by the shared
     * rasteriser, and the .pdf generated in the field would not match the one the office downloaded.
     */
    private fun blockFigure(block: Block, fallbackSource: String, widthPct: Float, align: Align,
                            title: String, caption: String) {
        val ref = figureRef(block)
        if (ref == null) {
            if (!droppedImages.contains(fallbackSource)) droppedImages.add(fallbackSource)
            return
        }
        // ── THE PAGE BREAK THIS CLAIMED TO PREVENT WAS NEVER PREVENTED ─────────────────────
        //
        // The comment said the title "must not be separated from its picture by a page break", and
        // nothing stopped it: the 6 mm reserved for the title said nothing about the picture, which
        // reserves its own space inside [drawImage] and breaks the page there. So the title stayed at
        // the foot of one page and the chart it names moved to the next — measured on the server over
        // 80 chart figures, 32 of them were separated from their picture. A figure title is a LABEL,
        // it is not in the contents, and there is no other way to tell which picture it belongs to,
        // so a reader finds a bare "Figure 12: designs by status" over somebody else's chart.
        //
        // Reserved together and then drawn with breaks suppressed. The picture is capped at 0.58 of
        // the text column — but ONLY the picture is, and the title is not, so "title plus picture
        // always fits a page" is not something this method may simply assert. A title long enough to
        // eat the other 0.42 would be drawn inside a lock that suppresses every break, which is
        // precisely the shape [cutRow] exists to undo. Today's figure titles are developer-authored
        // constants in the report builder ("Prototypes by review decision"), so the case is
        // unreachable as the templates stand; it becomes reachable the day one of them puts a
        // designer's own text in a chart title.
        //
        // So the guarantee is CHECKED rather than claimed: when the pair genuinely fits no page, the
        // title falls back to paginating line by line outside the lock — the figure title is
        // separated from its picture, which is the defect this block was opened for, but a separated
        // title is recoverable and words drawn at a negative y are not.
        val maxH = (top - bottom) * 0.58f
        val figureW = figureWidth(widthPct)
        val box = imageBox(ref, figureW, maxH)
        val titleLines =
            if (title.isNotEmpty()) wrap(runsOf(title, bold = true), textW, baseSize) else emptyList()
        val reserve = titleLines.sumOf { it.height.toDouble() }.toFloat() +
            (if (titleLines.isNotEmpty()) 1.2f * MM else 0f)
        val boxH = box?.second ?: 0f
        // The text column of a continuation page: [newPage] drops the cursor 6 mm below the top for
        // the running head, so this is the most any locked region may ever ask for.
        val columnH = top - 6 * MM - bottom
        val drawn = if (reserve + boxH > columnH) {
            if (titleLines.isNotEmpty()) {
                drawLines(titleLines, margin, textW, Align.LEFT, theme.accent)
                y -= 1.2f * MM
            }
            drawImage(ref, margin, figureW, maxH, align)
        } else {
            ensure(reserve + boxH)
            locked {
                if (titleLines.isNotEmpty()) {
                    drawLines(titleLines, margin, textW, Align.LEFT, theme.accent)
                    y -= 1.2f * MM
                }
                drawImage(ref, margin, figureW, maxH, align)
            }
        }
        if (drawn > 0f) {
            caption(caption, margin, textW)
            y -= 1.6f * MM
        }
    }

    private fun blockMap(block: MapBlock) =
        blockFigure(block, "map:india", block.widthPct, block.align, block.title, block.caption)

    private fun blockChart(block: ChartBlock) = blockFigure(
        block, "chart:${block.kind.name}", block.widthPct, block.align, block.title, block.caption,
    )

    private fun blockMetrics(block: MetricRowBlock) {
        if (block.metrics.isEmpty()) return
        val t = theme
        val n = block.metrics.size
        val cellW = textW / n
        val height = 16 * MM
        ensure(height)
        val rowTop = y
        if (drawing) {
            fillPaint.color = argb(t.zebraFill)
            canvas.drawRect(margin, cy(rowTop), margin + textW, cy(rowTop - height), fillPaint)
            block.metrics.forEachIndexed { i, (label, value, unit) ->
                val cx = margin + i * cellW + cellW / 2
                textPaint.typeface = boldFace
                textPaint.textSize = 17f
                textPaint.color = argb(t.accent)
                val valueW = textPaint.measureText(value)
                canvas.drawText(value, cx - valueW / 2, cy(rowTop - 8 * MM), textPaint)
                if (unit.isNotEmpty()) {
                    val unitX = cx + valueW / 2 + 1.2f * MM
                    textPaint.typeface = regularFace
                    textPaint.textSize = 8f
                    textPaint.color = argb(t.muted)
                    canvas.drawText(unit, unitX, cy(rowTop - 8 * MM), textPaint)
                }
                textPaint.typeface = regularFace
                textPaint.textSize = 8f
                textPaint.color = argb(t.muted)
                canvas.drawText(
                    label, cx - textPaint.measureText(label) / 2, cy(rowTop - 13 * MM), textPaint
                )
            }
        }
        y = rowTop - height - 3 * MM
    }

    private fun blockCallout(block: CalloutBlock) {
        val t = theme
        val kind = block.kind.uppercase()
        val fill = when (kind) {
            "WARNING" -> "FDF3E2"
            "SUCCESS" -> "EAF6EE"
            else -> "EAF1FB"
        }
        val edge = when (kind) {
            "WARNING" -> "B7791F"
            "SUCCESS" -> "2F855A"
            else -> t.accentSoft
        }
        val innerW = textW - 8 * MM
        val titleLines = if (block.title.isNotEmpty()) {
            wrap(runsOf(block.title, bold = true), innerW, baseSize)
        } else emptyList()
        val bodyLines = wrap(block.runs, innerW, baseSize - 0.6f)
        val height = titleLines.sumOf { it.height.toDouble() }.toFloat() +
            bodyLines.sumOf { it.height.toDouble() }.toFloat() + 6 * MM
        // A callout longer than a page is CUT and continued overleaf rather than drawn past the
        // bottom of its own box: the same defect [cutRow] was written for, in the third of this
        // file's locked regions. Measured on the server before the fix, a page-and-a-bit callout
        // put 72 pieces below y=0. Title and body are ONE column here because they are stacked,
        // not side by side; `titlesLeft` is how the split remembers which of the lines it has
        // handed out were the title's and so must keep the edge colour.
        var rest: List<List<Line>> = listOf(titleLines + bodyLines)
        var restH = height
        var titlesLeft = titleLines.size
        var turned = false
        while (true) {
            val cut = cutRow(rest, restH, 6 * MM, force = turned)
            if (cut == null) {
                newPage()
                turned = true
                continue
            }
            turned = false
            val part = cut.head[0]
            val boxTop = y
            if (drawing) {
                fillPaint.color = argb(fill)
                canvas.drawRect(
                    margin, cy(boxTop), margin + textW, cy(boxTop - cut.headHeight), fillPaint
                )
                fillPaint.color = argb(edge)
                canvas.drawRect(
                    margin, cy(boxTop), margin + 1.4f * MM, cy(boxTop - cut.headHeight), fillPaint
                )
            }
            val headTitle = part.take(titlesLeft)
            locked {
                y = boxTop - 3 * MM
                if (headTitle.isNotEmpty()) {
                    drawLines(headTitle, margin + 5 * MM, innerW, Align.LEFT, edge)
                }
                drawLines(part.drop(headTitle.size), margin + 5 * MM, innerW, Align.LEFT, t.ink)
            }
            titlesLeft = max(0, titlesLeft - part.size)
            y = boxTop - cut.headHeight
            val tail = cut.tail ?: break
            rest = tail
            restH = cut.tailHeight
        }
        y -= 3 * MM
    }

    private fun blockSignatures(block: SignatureBlock) {
        if (block.signatories.isEmpty()) return
        val t = theme
        val n = block.signatories.size
        val cellW = textW / n
        ensure(30 * MM)
        y -= 18 * MM
        val rowTop = y
        locked {
            block.signatories.forEachIndexed { i, (name, designation) ->
                val x = margin + i * cellW
                if (drawing) {
                    strokePaint.color = argb(t.ink)
                    strokePaint.strokeWidth = 0.6f
                    canvas.drawLine(
                        x + 3 * MM, cy(rowTop), x + cellW - 3 * MM, cy(rowTop), strokePaint
                    )
                }
                y = rowTop - 1.5f * MM
                drawLines(
                    wrap(runsOf(name, bold = true), cellW - 6 * MM, 9.5f),
                    x + 3 * MM, cellW - 6 * MM, Align.CENTER, t.ink,
                )
                drawLines(
                    wrap(runsOf(designation), cellW - 6 * MM, 8f),
                    x + 3 * MM, cellW - 6 * MM, Align.CENTER, t.muted,
                )
            }
        }
        y = rowTop - 14 * MM
    }

    // -- the pass ------------------------------------------------------------------------

    /**
     * No `else` branch, on purpose — see the note on [DocxWriter.emitBlocks]. [Block] is sealed, so a
     * block type added to the model without a case here fails to compile.
     */
    private fun runPass(drawing: Boolean) {
        this.drawing = drawing
        pageNo = 0
        pageStarted = false
        newPage()
        for (block in doc.blocks) {
            when (block) {
                is CoverBlock -> blockCover(block)
                is TocBlock -> blockToc(block)
                is HeadingBlock -> blockHeading(block)
                is ParagraphBlock -> blockParagraph(block)
                is BulletListBlock -> blockBullets(block)
                is KeyValueBlock -> blockKeyValues(block)
                is TableBlock -> blockTable(block)
                is ImageBlock -> blockImage(block)
                is ImageGridBlock -> blockImageGrid(block)
                is MetricRowBlock -> blockMetrics(block)
                is MapBlock -> blockMap(block)
                is ChartBlock -> blockChart(block)
                is CalloutBlock -> blockCallout(block)
                is SignatureBlock -> blockSignatures(block)
                is SpacerBlock -> y -= (top - bottom) * block.heightPct / 100
                is PageBreakBlock -> newPage()
            }
        }
    }

    /**
     * Lay the document out twice and write the PDF into [out].
     *
     * The stream is written but NOT closed — [PdfDocument.writeTo] does not close it either, and the
     * caller needs it open to `fd.sync()` before publishing the file. `close()` on the PdfDocument is
     * in a `finally` because a half-built document holds native page buffers that nothing else will
     * release; an export that throws on the fiftieth photo must not leak them.
     */
    fun writeTo(out: OutputStream) {
        val document = PdfDocument()
        pdf = document
        try {
            // MEASURE, then DRAW. The measuring pass starts no pages, decodes no bitmaps and
            // rasterises no glyphs; all it does is run the cursor and record which page each heading
            // landed on.
            //
            // It runs more than once, and that is not the same thing as a third renderer pass — it is
            // the fixed point of a single question. A table of contents is laid out BEFORE the
            // headings it lists, so the first measuring pass reaches the TOC block with an empty
            // index and measures it as one title line. If the report then turns out to have forty
            // headings, the real TOC is two pages, every heading after it is one page later than the
            // pass recorded, and the drawing pass prints a contents list in which every single number
            // is wrong by one. The server has exactly this hole and it is invisible until a report
            // gets long enough — which is precisely the report nobody proof-reads.
            //
            // So the measuring pass repeats until the heading -> page map stops changing, capped at
            // three iterations. Two is the normal answer (empty index, then real index, then equal);
            // the cap exists because a report balanced exactly on a page boundary can oscillate
            // between two answers forever, and a slightly wrong contents page is a far better outcome
            // than an export that never finishes on a phone in a field.
            var iterations = 0
            while (true) {
                pendingPages.clear()
                pendingEntries.clear()
                runPass(drawing = false)
                iterations++
                val stable = pendingPages == headingPages
                headingPages.clear()
                headingPages.putAll(pendingPages)
                tocEntries.clear()
                tocEntries.addAll(pendingEntries)
                if (stable || iterations >= 3) break
            }

            // droppedImages would otherwise report every missing photo once per measuring
            // iteration, so it is rebuilt wholesale by the drawing pass.
            droppedImages.clear()
            // THE SIZE CACHE LOSES ONLY ITS FAILURES. The stated reason for clearing it is "a photo
            // that failed to load gets one more chance — the loader may have been reading from
            // storage that was momentarily busy", and that reason covers the null entries and
            // nothing else. Clearing it wholesale threw away every SUCCESSFUL probe as well, and
            // since each probe is a whole-file read (see [sizeCache]), it made the drawing pass
            // re-read every photograph in the report to relearn a pair of integers it already knew —
            // three whole-file reads per printed photo where two will do.
            sizeCache.entries.removeAll { it.value == null }

            runPass(drawing = true)
            if (pageStarted) {
                drawFurniture()
                finishPage()
            }
            document.writeTo(out)
        } finally {
            // Never leave a page open: finishPage() is what hands its buffer back to the document,
            // and close() on a document with a page still started throws over the original failure.
            page?.let { runCatching { document.finishPage(it) } }
            page = null
            document.close()
            pdf = null
        }
    }
}

/**
 * Render [document] into [out] as a PDF and return the image sources that could not be drawn.
 *
 * Synchronous and CPU-bound. `ReportExport` runs it on `Dispatchers.IO`.
 */
fun renderPdf(document: ReportDocument, loadImage: ImageLoader, out: OutputStream): List<String> {
    val writer = PdfWriter(document, loadImage)
    writer.writeTo(out)
    return writer.droppedImages.distinct().sorted()
}
