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

    /** Running head and foot. Suppressed on page 1, which is the cover. */
    private fun drawFurniture() {
        if (pageNo <= 1) return
        val meta = doc.meta
        val t = theme
        textPaint.typeface = regularFace
        textPaint.textSize = 7.8f
        textPaint.color = argb(t.muted)
        if (meta.headerText.isNotEmpty()) {
            val w = textPaint.measureText(meta.headerText)
            canvas.drawText(
                meta.headerText, pageW - margin - w, cy(pageH - margin + 4 * MM), textPaint
            )
            strokePaint.color = argb(t.rule)
            strokePaint.strokeWidth = 0.5f
            val ruleY = cy(pageH - margin + 2.6f * MM)
            canvas.drawLine(margin, ruleY, pageW - margin, ruleY, strokePaint)
        }
        val footY = margin - 4 * MM
        strokePaint.color = argb(t.rule)
        strokePaint.strokeWidth = 0.5f
        canvas.drawLine(margin, cy(footY + 4 * MM), pageW - margin, cy(footY + 4 * MM), strokePaint)
        if (meta.footerText.isNotEmpty()) {
            canvas.drawText(meta.footerText, margin, cy(footY), textPaint)
        }
        if (meta.showPageNumbers) {
            val label = "Page $pageNo"
            canvas.drawText(label, pageW - margin - textPaint.measureText(label), cy(footY), textPaint)
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
            if (drawing) {
                var cursor = when (align) {
                    Align.CENTER -> x + (width - line.width) / 2
                    Align.RIGHT -> x + width - line.width
                    else -> x
                }
                val lineBaseline = y - line.height * 0.78f
                for (piece in line.pieces) {
                    // `rise` is 0 for ordinary text, so this is the same baseline it always was.
                    // The RULES below use it too: an underline under a subscript belongs under the
                    // subscript, not under the line it was lowered from.
                    val baseline = lineBaseline + piece.rise
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
                            cy(baseline + piece.size * 0.90f),
                            cursor + advance,
                            cy(baseline - piece.size * 0.24f),
                            fillPaint,
                        )
                    }
                    textPaint.color = argb(piece.color ?: defaultColor)
                    canvas.drawText(piece.text, cursor, cy(baseline), textPaint)
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
                            val ruleY = cy(baseline - piece.size * 0.13f)
                            canvas.drawLine(cursor, ruleY, cursor + advance, ruleY, strokePaint)
                        }
                        if (piece.strike) {
                            val ruleY = cy(baseline + piece.size * 0.26f)
                            canvas.drawLine(cursor, ruleY, cursor + advance, ruleY, strokePaint)
                        }
                    }
                    cursor += advance
                }
            }
            y -= line.height
        }
    }

    /**
     * The intrinsic pixel size of a photo, cached by source and probed WITHOUT decoding it.
     *
     * `inJustDecodeBounds` reads the header only, so this costs a few kilobytes per photo instead of
     * the whole bitmap — which matters because both passes call it for every image and the layout
     * pass would otherwise decode the entire report's photography to learn a pair of integers.
     *
     * The BYTES are deliberately not cached alongside the size. Holding sixty full-resolution JPEGs
     * (4-6 MB each) is enough to be killed on a mid-range phone even with `largeHeap`, so the loader
     * is asked again at draw time, when the bytes are needed for a few milliseconds and then freed.
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

    private fun decodeFitted(ref: ImageRef, boxW: Float, boxH: Float): Bitmap? {
        val data = bytesFor(ref) ?: return null
        if (data.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // The box is expressed in the DISPLAY orientation, so undo the quarter turn before asking
        // for a sample size — otherwise a portrait photo shown landscape is downsampled against the
        // wrong axis and comes back either blurry or twice the size it needed to be.
        val quarter = ref.rotationDeg == 90 || ref.rotationDeg == 270
        val reqW = ((if (quarter) boxH else boxW) * IMAGE_OVERSAMPLE).roundToInt()
        val reqH = ((if (quarter) boxW else boxH) * IMAGE_OVERSAMPLE).roundToInt()
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, reqW, reqH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, opts) }.getOrNull()
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

        ensure(h)
        if (drawing) {
            val dx = when (align) {
                Align.CENTER -> x + (width - w) / 2
                Align.RIGHT -> x + width - w
                else -> x
            }
            val bitmap = decodeFitted(ref, w, h)
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
        for (entry in tocEntries) {
            if (entry.level > block.depth) continue
            val indent = (entry.level - 1) * 6 * MM
            val size = if (entry.level == 1) 10.0f else 9.2f
            val label = if (entry.number.isNotEmpty()) "${entry.number}. ${entry.text}" else entry.text
            val pageLabel = headingPages[entry.bookmark]?.toString() ?: ""
            ensure(size * 1.6f)
            if (drawing) {
                val face = if (entry.level == 1) boldFace else regularFace
                textPaint.typeface = face
                textPaint.textSize = size
                textPaint.color = argb(if (entry.level == 1) t.ink else t.muted)
                val baseline = y - size
                canvas.drawText(label, x + indent, cy(baseline), textPaint)
                if (pageLabel.isNotEmpty()) {
                    val pageW2 = textPaint.measureText(pageLabel)
                    canvas.drawText(pageLabel, x + textW - pageW2, cy(baseline), textPaint)
                    // Dot leader between the title and the page number.
                    val labelW = stringWidth(label, face, size)
                    val leadFrom = x + indent + labelW + 2 * MM
                    val leadTo = x + textW - pageW2 - 2 * MM
                    if (leadTo > leadFrom) {
                        textPaint.typeface = face
                        textPaint.textSize = size
                        textPaint.color = argb(t.rule)
                        val dotW = textPaint.measureText(".")
                        val count = max(0, ((leadTo - leadFrom) / max(dotW, 0.1f)).toInt())
                        canvas.drawText(".".repeat(count), leadFrom, cy(baseline), textPaint)
                    }
                }
            }
            y -= size * 1.6f
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
        val needed = lines.sumOf { it.height.toDouble() }.toFloat() + 6 * MM
        // keepNext: a heading alone at the foot of a page is worse than a slightly short page.
        ensure(needed)
        y -= (if (level > 1) 4.5f else 6.5f) * MM
        if (!drawing) {
            // Only a MEASURING pass builds the index. Appending during the drawing pass would print
            // every entry twice, because the drawing pass walks the same block list again.
            pendingPages[block.bookmark] = pageNo
            pendingEntries.add(TocEntry(level, block.number, runsText(block.runs), block.bookmark))
        }
        drawLines(lines, margin, textW, Align.LEFT, color)
        if (level == 1 && drawing) {
            y -= 1.2f * MM
            strokePaint.color = argb(t.rule)
            strokePaint.strokeWidth = 0.7f
            canvas.drawLine(margin, cy(y), margin + textW, cy(y), strokePaint)
        }
        y -= 2.4f * MM
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
            ensure(height)
            val rowTop = y
            if (drawing && i % 2 == 1) {
                fillPaint.color = argb(t.zebraFill)
                canvas.drawRect(margin, cy(rowTop), margin + textW, cy(rowTop - height), fillPaint)
            }
            locked {
                y = rowTop - 0.9f * MM
                drawLines(labLines, margin + 1.5f * MM, labelW - 3 * MM, Align.LEFT, t.muted)
                y = rowTop - 0.9f * MM
                drawLines(valLines, margin + labelW, valueW - 3 * MM, Align.LEFT, t.ink)
            }
            y = rowTop - height
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

        /** Draw one row at the cursor. The caller guarantees it fits; this never breaks a page. */
        fun drawRow(
            laid: List<List<Line>>,
            height: Float,
            fill: String?,
            textColor: String,
            topRule: Boolean = false,
        ) {
            val rowTop = y
            if (drawing) {
                if (fill != null) {
                    fillPaint.color = argb(fill)
                    canvas.drawRect(margin, cy(rowTop), margin + textW, cy(rowTop - height), fillPaint)
                }
                strokePaint.color = argb(t.rule)
                strokePaint.strokeWidth = 0.4f
                canvas.drawLine(
                    margin, cy(rowTop - height), margin + textW, cy(rowTop - height), strokePaint
                )
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

        fun placeHeader() {
            if (hasHeader) drawRow(headerLaid, headerH, t.tableHeaderFill, t.tableHeaderText)
        }

        // Reserve the header plus the first body row together: a header stranded at the foot of a
        // page reads as an empty table.
        val firstH = if (block.rows.isNotEmpty()) rowLines(block.rows[0], bodySize, false).second else 0f
        if (!fits(headerH + firstH)) newPage()
        placeHeader()

        block.rows.forEachIndexed { i, row ->
            val (laid, height) = rowLines(row, bodySize, false)
            // DECIDE THE BREAK BEFORE DRAWING, so a row is never split and never drawn twice. The
            // previous version drew the row, noticed the page had turned, and then redrew both the
            // header and the row — duplicating a line of a cost sheet, which is the one kind of
            // error in this file that changes what the report says rather than how it looks.
            if (!fits(height)) {
                newPage()
                placeHeader()
            }
            drawRow(laid, height, if (block.zebra && i % 2 == 1) t.zebraFill else null, t.ink)
        }

        val totalRow = block.totalRow
        if (totalRow != null) {
            val (laid, height) = rowLines(totalRow, bodySize, true)
            // The total must never be orphaned from the figures it totals.
            if (!fits(height)) {
                newPage()
                placeHeader()
            }
            drawRow(laid, height, t.zebraFill, t.ink, topRule = true)
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

        var start = 0
        while (start < block.images.size) {
            val chunk = block.images.subList(start, min(start + columns, block.images.size))
            val heights = ArrayList<Float>()
            for ((ref, cap) in chunk) {
                val size = intrinsicSize(ref)
                if (size == null) {
                    heights.add(0f)
                    continue
                }
                var iw = size.first
                var ih = size.second
                if (ref.rotationDeg == 90 || ref.rotationDeg == 270) {
                    val tmp = iw
                    iw = ih
                    ih = tmp
                }
                val aspect = if (iw > 0 && ih > 0) iw.toFloat() / ih.toFloat() else ref.aspect
                val h = min((cellW - 4 * MM) / aspect, (top - bottom) * 0.30f)
                val capH = if (cap.isNotEmpty()) {
                    wrap(runsOf(cap), cellW - 4 * MM, capSize)
                        .sumOf { it.height.toDouble() }.toFloat()
                } else 0f
                heights.add(h + capH + 3 * MM)
            }
            val rowH = heights.maxOrNull() ?: 0f
            if (rowH <= 0f) {
                start += columns
                continue
            }
            ensure(rowH)
            val rowTop = y
            // Locked: a grid row is ONE visual unit. Without this, a tall caption in the last cell
            // breaks the page and the remaining photos of the row land under the header of the next
            // one, detached from their captions.
            locked {
                chunk.forEachIndexed { i, (ref, cap) ->
                    val x = margin + i * cellW
                    y = rowTop
                    val drawn = drawImage(
                        ref, x + 2 * MM, cellW - 4 * MM, (top - bottom) * 0.30f, Align.CENTER
                    )
                    if (drawn > 0f && cap.isNotEmpty()) {
                        y -= 1.2f * MM
                        drawLines(
                            wrap(runsOf(cap, italic = true), cellW - 4 * MM, capSize),
                            x + 2 * MM, cellW - 4 * MM, Align.CENTER, theme.muted,
                        )
                    }
                }
            }
            y = rowTop - rowH
            start += columns
        }
        y -= 1.6f * MM
        caption(block.caption, margin, textW)
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
        if (title.isNotEmpty()) {
            // A figure title is a label, not a heading: it must not enter the table of contents, and it
            // must not be separated from its picture by a page break.
            ensure(6 * MM)
            drawLines(
                wrap(runsOf(title, bold = true), textW, baseSize),
                margin, textW, Align.LEFT, theme.accent,
            )
            y -= 1.2f * MM
        }
        val drawn = drawImage(ref, margin, figureWidth(widthPct), (top - bottom) * 0.58f, align)
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
        ensure(height)
        val boxTop = y
        if (drawing) {
            fillPaint.color = argb(fill)
            canvas.drawRect(margin, cy(boxTop), margin + textW, cy(boxTop - height), fillPaint)
            fillPaint.color = argb(edge)
            canvas.drawRect(margin, cy(boxTop), margin + 1.4f * MM, cy(boxTop - height), fillPaint)
        }
        locked {
            y = boxTop - 3 * MM
            if (titleLines.isNotEmpty()) {
                drawLines(titleLines, margin + 5 * MM, innerW, Align.LEFT, edge)
            }
            drawLines(bodyLines, margin + 5 * MM, innerW, Align.LEFT, t.ink)
        }
        y = boxTop - height - 3 * MM
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

            // Both are rebuilt by the drawing pass. droppedImages would otherwise report every
            // missing photo once per measuring iteration, and the size cache is cleared only so a
            // photo that failed to load gets one more chance — the loader may have been reading from
            // storage that was momentarily busy.
            droppedImages.clear()
            sizeCache.clear()

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
