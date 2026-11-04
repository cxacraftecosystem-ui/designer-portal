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

private const val MM = 72.0f / 25.4f

private const val QR_BOX_MM = 26.0f

private const val PAGE_W_MM = 210.0f
private const val PAGE_H_MM = 297.0f
private const val MARGIN_MM = 10.0f

private class CardGeometry(val widthMm: Float, val heightMm: Float, val columns: Int)

private fun geometryFor(recordType: DwWorkshopRecordType): CardGeometry = when (recordType) {
    DwWorkshopRecordType.ARTISAN -> CardGeometry(85f, 54f, 2)
    DwWorkshopRecordType.PROTOTYPE -> CardGeometry(63f, 45f, 3)
    else -> CardGeometry(63f, 45f, 3)
}

private const val INK = 0xFF111111.toInt()
private const val INK_SOFT = 0xFF444450.toInt()
private const val CODE_INK = 0xFF333340.toInt()
private const val CUT_GUIDE = 0xFFB9B9C4.toInt()
private const val REFUSAL_INK = 0xFF92400E.toInt()
private const val REFUSAL_FILL = 0xFFFEF3C7.toInt()
private const val REFUSAL_EDGE = 0xFFF59E0B.toInt()
private const val BLANK_EDGE = 0xFFD8D8E0.toInt()

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

    val rows = maxOf(1, ((PAGE_H_MM - 2 * MARGIN_MM) / geometry.heightMm).toInt())
    val perPage = maxOf(1, rows * geometry.columns)

    val document = PdfDocument()
    var pages = 0
    try {
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
            text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            drawWrapped(canvas, card.printed, bodyLeft, y, bodyWidth, text, maxLines = 4, leading = 7.5f)
        }

        is DwCardRender.Refused -> {
            y += 3f
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
            val lines = maxOf(1, ((boxBottom - boxTop - 3f) / 9f).toInt())
            drawWrapped(canvas, card.message, bodyLeft + 3f, y, bodyWidth - 6f, text, maxLines = lines, leading = 9f)
        }
    }
}

private fun drawSymbol(canvas: Canvas, card: DwCardRender.Ok, left: Float, top: Float, box: Float) {
    val symbol = card.symbol
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
        val taken = maxOf(1, paint.breakText(remaining, true, width, null))
        val last = line == maxLines - 1
        if (last && taken < remaining.length) {
            canvas.drawText(TextUtils.ellipsize(remaining, paint, width, TextUtils.TruncateAt.END).toString(), left, y, paint)
            return y + leading
        }
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
