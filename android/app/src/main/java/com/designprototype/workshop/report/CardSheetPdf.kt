package com.designprototype.workshop.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.designprototype.workshop.data.DwCardRender
import com.designprototype.workshop.data.DwWorkshopRecordType
import java.io.OutputStream

/**
 * Artisan cards and prototype tags, laid out on A4 at the size they will actually be cut to — the
 * handset's twin of `frontend/components/designworkshop/WorkshopCodeSheet.tsx`.
 *
 * ── WHY A FILE AND NOT A SCREEN ───────────────────────────────────────────────────────────────
 *
 * A phone has no printer, and "print" on this client means "produce something a designer can carry to
 * the one printer in the block office, or mail to the studio". A PDF is the only artefact that
 * survives that journey at a known physical size; a screenshot of a Compose preview does not, because
 * nothing in it says how big a millimetre was. The preview on the screen exists to let a designer
 * check a card BEFORE spending the walk; this file is what they take with them. Both draw from the
 * same [DwCardRender.Ok.symbol], so what is checked is what is printed.
 *
 * ── WHY MILLIMETRES, RESTATED, BECAUSE HERE IT DECIDES WHETHER THE THING WORKS ────────────────
 *
 * The web's header makes the argument and it is if anything stronger on this side: a QR module below
 * roughly half a millimetre is at the resolution limit of a handset camera held over a card in a
 * courtyard, so a sheet that came out 15% small would produce forty tags that scan on the designer's
 * own phone at 8cm and fail on everybody else's. Every dimension here is physical and every number is
 * the web's own — [QR_BOX_MM] is chosen so that the largest symbol this app draws (version 4, 33
 * modules plus a 4-module quiet zone a side = 41 units) still clears 0.63mm per module, and a
 * prototype created offline NEEDS version 4 because until its row syncs it is identified by a
 * 36-character UUID client key rather than a 25-character cuid. Shrinking the box to fit more tags on
 * a page is the one change here that silently breaks the feature.
 *
 * PdfDocument pages are integers of POINTS, so A4 is 595 x 842 rather than exactly 210 x 297mm; the
 * rounding is a fifth of a millimetre across the page and is absorbed by the outer margin, never by a
 * card. Nothing is scaled to fit: the sheet prints at 100% or it is not this sheet.
 *
 * ── WHAT IS ON A CARD, AND WHAT MAY NEVER BE ──────────────────────────────────────────────────
 *
 * The face carries a name because a card nobody can identify by eye gets handed to the wrong person,
 * which is the very error this feature exists to remove. The QR carries an opaque reference and
 * nothing else. What is on NEITHER is any identity number — no Aadhaar, no Pehchan, no artisan card
 * number — and this renderer cannot leak one because [DwCardRender] has nowhere to put one.
 *
 * A REFUSAL IS PRINTED RATHER THAN THE CARD BEING DROPPED, for the reason the web gives: a sheet that
 * silently held 29 tags when the workshop has 30 is cut up, tied on, and found out on the day the
 * report is due. A card that says "this row has not been saved yet" is a card somebody can act on.
 *
 * NO COMPOSE AND NO CONTEXT — it takes rendered cards and an [OutputStream], in the shape of
 * [PdfWriter], so it can be driven from a test or from a background dispatcher without a device.
 */

/** Points per millimetre. [PdfWriter] holds the same constant privately; both are 72/25.4. */
private const val MM = 72.0f / 25.4f

/** The QR box, in millimetres. See the header — this is the number that must not shrink. */
private const val QR_BOX_MM = 26.0f

/** A4, and the margin the cards are laid inside. The web's `@page size: A4` and its 10mm padding. */
private const val PAGE_W_MM = 210.0f
private const val PAGE_H_MM = 297.0f
private const val MARGIN_MM = 10.0f

/**
 * Card geometry per kind, in millimetres.
 *
 * The artisan card is ID-1 (85.6 x 54mm) rounded down to 85 — the size of every identity card anybody
 * in the room already owns, so it fits the lanyard pouches a workshop already buys. The prototype tag
 * is smaller because it is tied or taped to an object rather than worn.
 */
private class CardGeometry(val widthMm: Float, val heightMm: Float, val columns: Int)

private fun geometryFor(recordType: DwWorkshopRecordType): CardGeometry = when (recordType) {
    DwWorkshopRecordType.ARTISAN -> CardGeometry(85f, 54f, 2)
    DwWorkshopRecordType.PROTOTYPE -> CardGeometry(63f, 45f, 3)
    // Only the two kinds a workshop cuts up are offered as a SHEET. Every other record type carries
    // its code on its own screen, one at a time, where it is expanded and shared rather than printed
    // forty to a page. A kind arriving here would be a caller bug, and the honest failure for a
    // designer mid-export is a smaller card — right symbol, right code — rather than an exception
    // that loses the whole export.
    else -> CardGeometry(63f, 45f, 3)
}

/** Ink. Fixed values, not theme tokens: this is a depiction of paper and does not invert. */
private const val INK = 0xFF111111.toInt()
private const val INK_SOFT = 0xFF444450.toInt()
private const val CODE_INK = 0xFF333340.toInt()
private const val CUT_GUIDE = 0xFFB9B9C4.toInt()
private const val REFUSAL_INK = 0xFF92400E.toInt()
private const val REFUSAL_FILL = 0xFFFEF3C7.toInt()
private const val REFUSAL_EDGE = 0xFFF59E0B.toInt()
private const val BLANK_EDGE = 0xFFD8D8E0.toInt()

/**
 * Write [cards] as an A4 sheet of cut-out cards.
 *
 * @return the number of PAGES written, which the caller states beside the file. A designer who is
 *   told "3 pages" before walking to the print shop can decide whether to go.
 */
fun renderCardSheetPdf(
    recordType: DwWorkshopRecordType,
    cards: List<DwCardRender>,
    out: OutputStream,
): Int {
    val geometry = geometryFor(recordType)
    val pageWpt = Math.round(PAGE_W_MM * MM)
    val pageHpt = Math.round(PAGE_H_MM * MM)
    val margin = MARGIN_MM * MM
    val cardW = geometry.widthMm * MM
    val cardH = geometry.heightMm * MM

    // Derived from the geometry rather than passed in, so the number of cards on a page and the size
    // they are cut to cannot disagree. 277 is the printable height in mm — the web's own figure.
    val rows = maxOf(1, ((PAGE_H_MM - 2 * MARGIN_MM) / geometry.heightMm).toInt())
    val perPage = maxOf(1, rows * geometry.columns)

    val document = PdfDocument()
    var pages = 0
    try {
        // An empty list still writes ONE page, carrying the sentence that says so. A zero-page PDF is
        // a file that will not open, which a designer reads as a broken export rather than as an empty
        // workshop — and those two lead to completely different next actions.
        val chunks = if (cards.isEmpty()) listOf(emptyList()) else cards.chunked(perPage)
        for (chunk in chunks) {
            val info = PdfDocument.PageInfo.Builder(pageWpt, pageHpt, pages + 1).create()
            val page = document.startPage(info)
            pages++
            drawPage(page.canvas, chunk, geometry, margin, cardW, cardH, recordType)
            document.finishPage(page)
        }
        document.writeTo(out)
    } finally {
        // Always, on every path. A PdfDocument holds native page buffers, and an export that threw
        // half way through a sheet of forty tags would otherwise leak them on the device least able to
        // spare the memory.
        document.close()
    }
    return pages
}

private fun drawPage(
    canvas: Canvas,
    cards: List<DwCardRender>,
    geometry: CardGeometry,
    margin: Float,
    cardW: Float,
    cardH: Float,
    recordType: DwWorkshopRecordType,
) {
    // The page is white PAPER, drawn rather than assumed. A transparent PDF page renders grey in some
    // viewers and, more to the point, leaves the quiet zone of every symbol without the light border a
    // scanner uses to find it at all.
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE
    canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)

    if (cards.isEmpty()) {
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        text.color = INK
        text.textSize = 10f
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(
            if (recordType == DwWorkshopRecordType.PROTOTYPE) {
                "No prototypes have been recorded in this workshop yet."
            } else {
                "No artisans are on this workshop's roster yet."
            },
            margin,
            margin + 14f,
            text,
        )
        return
    }

    cards.forEachIndexed { index, card ->
        val column = index % geometry.columns
        val row = index / geometry.columns
        drawCard(canvas, card, margin + column * cardW, margin + row * cardH, cardW, cardH)
    }
}

private fun drawCard(canvas: Canvas, card: DwCardRender, left: Float, top: Float, width: Float, height: Float) {
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.2f * MM
        color = CUT_GUIDE
        // A HAIRLINE RULE IS THE CUT GUIDE. Scissors need a line; a card printed with none is cut by
        // eye and comes out crooked, and a crooked cut through a quiet zone stops the symbol scanning.
        pathEffect = DashPathEffect(floatArrayOf(2f * MM, 1.4f * MM), 0f)
    }
    canvas.drawRect(left, top, left + width, top + height, stroke)

    val pad = 3.5f * MM
    val gap = 3f * MM
    val qrBox = QR_BOX_MM * MM
    val qrLeft = left + pad
    val qrTop = top + (height - qrBox) / 2f
    val bodyLeft = qrLeft + qrBox + gap
    val bodyWidth = left + width - pad - bodyLeft

    when (card) {
        is DwCardRender.Ok -> drawSymbol(canvas, card, qrLeft, qrTop, qrBox)
        is DwCardRender.Refused -> {
            val blank = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.2f * MM
                color = BLANK_EDGE
                pathEffect = DashPathEffect(floatArrayOf(1.4f * MM, 1.4f * MM), 0f)
            }
            canvas.drawRect(qrLeft, qrTop, qrLeft + qrBox, qrTop + qrBox, blank)
        }
    }

    val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    var y = top + pad + 9f

    // The title, clipped to two lines. A name that overflows pushes the printed code off the bottom
    // edge, and the code is the half a human falls back on when the camera will not focus — so the
    // NAME is what gets clipped, never the code.
    text.color = INK
    text.textSize = 11f
    text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    y = drawClipped(canvas, card.spec.title, bodyLeft, y, bodyWidth, text, maxLines = 2, leading = 12.6f)

    text.color = INK_SOFT
    text.textSize = 8f
    text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    for (line in card.spec.lines.take(2)) {
        y += 1.6f
        y = drawClipped(canvas, line, bodyLeft, y, bodyWidth, text, maxLines = 1, leading = 10.4f)
    }

    when (card) {
        is DwCardRender.Ok -> {
            y += 4.5f
            text.color = CODE_INK
            text.textSize = 6f
            // Monospace so the four check characters line up under the eye of somebody reading them
            // aloud, and so 0/O and 1/l are told apart on paper.
            text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            // Wrapped rather than clipped: the printed code is the manual-entry path, and half a code
            // is not a code. Three lines is enough for the longest payload this app emits (a UUID
            // client key, 51 characters, in groups of four).
            drawWrapped(canvas, card.printed, bodyLeft, y, bodyWidth, text, maxLines = 4, leading = 7.5f)
        }

        is DwCardRender.Refused -> {
            y += 3f
            // The amber wash is the only thing that distinguishes "this card could not be made" from a
            // blank card somebody will assume printed badly, so it is a filled box and not just words.
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = REFUSAL_FILL }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.2f * MM
                color = REFUSAL_EDGE
            }
            val boxTop = y - 6f
            val boxBottom = minOf(top + height - pad, boxTop + 34f)
            canvas.drawRect(bodyLeft, boxTop, bodyLeft + bodyWidth, boxBottom, fill)
            canvas.drawRect(bodyLeft, boxTop, bodyLeft + bodyWidth, boxBottom, edge)
            text.color = REFUSAL_INK
            text.textSize = 7.5f
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            // The line count is derived from the box the wash was just painted, not fixed at three: a
            // long name pushes the box down, and text drawn past its bottom edge would sit on the cut
            // guide in amber-less black and read as a printing fault rather than as a truncated reason.
            val lines = maxOf(1, ((boxBottom - boxTop - 3f) / 9f).toInt())
            drawWrapped(canvas, card.message, bodyLeft + 3f, y, bodyWidth - 6f, text, maxLines = lines, leading = 9f)
        }
    }
}

/**
 * The dark modules of a symbol, drawn as merged horizontal runs.
 *
 * ONE RECTANGLE PER RUN, not one per module: a version 4 symbol is 1089 modules, and a sheet of thirty
 * tags is thirty thousand draw calls on a device that is already the cheapest thing in the room.
 * Merging also renders more reliably — adjacent rectangles leave hairline seams at some zoom levels in
 * some viewers, and a seam across a QR module is exactly the artefact that makes a scanner hesitate.
 *
 * The run rectangles are drawn WITHOUT antialiasing and on whole-module boundaries computed from the
 * box rather than accumulated per module, so rounding cannot open a sub-pixel gap between two runs of
 * the same row.
 */
private fun drawSymbol(canvas: Canvas, card: DwCardRender.Ok, left: Float, top: Float, box: Float) {
    val symbol = card.symbol
    // The quiet zone is included in the extent rather than left to the caller: a symbol printed hard
    // against a card's border does not scan and there is no way to see that on screen. Four modules is
    // the standard's minimum and is what `DwQrEncode.svgPath` uses on the other path.
    val quiet = 4
    val extent = symbol.size + quiet * 2
    val unit = box / extent

    val paper = Paint().apply { color = Color.WHITE }
    canvas.drawRect(left, top, left + box, top + box, paper)

    val dark = Paint().apply {
        color = INK
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    for (row in 0 until symbol.size) {
        var column = 0
        while (column < symbol.size) {
            if (!symbol.matrix[row][column]) {
                column++
                continue
            }
            var run = 1
            while (column + run < symbol.size && symbol.matrix[row][column + run]) run++
            val x0 = left + (column + quiet) * unit
            val x1 = left + (column + run + quiet) * unit
            val y0 = top + (row + quiet) * unit
            val y1 = top + (row + 1 + quiet) * unit
            canvas.drawRect(x0, y0, x1, y1, dark)
            column += run
        }
    }
}

/** One or two lines, ellipsised at the end. Returns the baseline to continue from. */
private fun drawClipped(
    canvas: Canvas,
    text: String,
    left: Float,
    baseline: Float,
    width: Float,
    paint: TextPaint,
    maxLines: Int,
    leading: Float,
): Float {
    var y = baseline
    var remaining = text
    var line = 0
    while (remaining.isNotEmpty() && line < maxLines) {
        // `maxOf(1, …)`: breakText answers 0 when not even one character fits, and a cut of 0 would
        // leave `remaining` unchanged. The line counter bounds the loop either way, but a zero cut
        // draws blank lines and swallows the text — so take one character and let it overhang.
        val taken = maxOf(1, paint.breakText(remaining, true, width, null))
        val last = line == maxLines - 1
        if (last && taken < remaining.length) {
            canvas.drawText(TextUtils.ellipsize(remaining, paint, width, TextUtils.TruncateAt.END).toString(), left, y, paint)
            return y + leading
        }
        // Break on a space where there is one, so a name does not split mid-syllable. `taken` is the
        // widest prefix that fits; the search below only ever shortens it.
        var cut = taken
        if (taken < remaining.length) {
            val space = remaining.lastIndexOf(' ', taken)
            if (space > 0) cut = space
        }
        canvas.drawText(remaining.substring(0, cut).trimEnd(), left, y, paint)
        remaining = remaining.substring(cut).trimStart()
        y += leading
        line++
    }
    return y
}

/** Wrapped text with no ellipsis — for a code, where half a value is worse than none. */
private fun drawWrapped(
    canvas: Canvas,
    text: String,
    left: Float,
    baseline: Float,
    width: Float,
    paint: TextPaint,
    maxLines: Int,
    leading: Float,
) {
    var y = baseline
    var remaining = text
    var line = 0
    while (remaining.isNotEmpty() && line < maxLines) {
        val taken = maxOf(1, paint.breakText(remaining, true, width, null))
        var cut = taken
        if (taken < remaining.length) {
            val space = remaining.lastIndexOf(' ', taken)
            if (space > 0) cut = space
        }
        canvas.drawText(remaining.substring(0, cut).trimEnd(), left, y, paint)
        remaining = remaining.substring(cut).trimStart()
        y += leading
        line++
    }
}
