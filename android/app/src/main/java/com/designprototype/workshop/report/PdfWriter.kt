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
import android.util.Log
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val PDF_MIME = "application/pdf"

private const val MM = 72.0f / 25.4f

private const val IMAGE_OVERSAMPLE = 2.0f

private const val VERTICAL_SCALE = 0.62f
private const val SUPERSCRIPT_RISE = 0.33f
private const val SUBSCRIPT_DROP = 0.14f

private fun argb(hex: String?): Int {
    val h = (hex ?: "000000").removePrefix("#")
    val value = if (h.length == 6) h.toLongOrNull(16) else null
    return (0xFF000000L or (value ?: 0L)).toInt()
}

class PdfWriter(
    private val doc: ReportDocument,
    private val loadImage: ImageLoader,
) {
    private val theme = doc.theme

    val droppedImages = ArrayList<String>()

    var clippedFurnitureLines = 0
        private set

    var pageCountDisagreement: Pair<Int, Int>? = null
        private set

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
        pageWpt = (wMm * MM).roundToInt()
        pageHpt = (hMm * MM).roundToInt()
        pageW = pageWpt.toFloat()
        pageH = pageHpt.toFloat()
        margin = doc.meta.marginMm * MM
        textW = pageW - 2 * margin
        top = pageH - margin
        bottom = margin + 10 * MM
        baseSize = theme.baseSizePt
    }

    private var drawing = false
    private var pageNo = 0
    private var pageStarted = false
    private var y = 0f

    private var totalPages = 0

    private var pdf: PdfDocument? = null
    private var page: PdfDocument.Page? = null
    private val canvas: Canvas get() = page!!.canvas

    private var lockedDepth = 0

    private class TocEntry(val level: Int, val number: String, val text: String, val bookmark: String)

    private val headingPages = HashMap<String, Int>()
    private val tocEntries = ArrayList<TocEntry>()

    private val pendingPages = HashMap<String, Int>()
    private val pendingEntries = ArrayList<TocEntry>()

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private fun cy(pdfY: Float): Float = pageH - pdfY

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

    private fun stringWidth(text: String, face: Typeface, size: Float): Float {
        textPaint.typeface = face
        textPaint.textSize = size
        return textPaint.measureText(text)
    }

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

    private class Line(val pieces: List<Piece>, val width: Float, val height: Float)

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
            val runSize = if (run.superscript || run.subscript) size * VERTICAL_SCALE else size
            val rise = when {
                run.superscript -> size * SUPERSCRIPT_RISE
                run.subscript -> -size * SUBSCRIPT_DROP
                else -> 0f
            }
            run.text.split("\n").forEachIndexed { paraIndex, segment ->
                if (paraIndex > 0) flush()
                if (segment.isEmpty()) return@forEachIndexed
                val tokens = segment.replace("\t", "    ").split(" ")
                tokens.forEachIndexed { tokenIndex, token ->
                    val piece = if (tokenIndex == tokens.size - 1) token else "$token "
                    if (piece.isEmpty()) return@forEachIndexed
                    val w = stringWidth(piece, face, runSize)
                    if (cur.isNotEmpty() && curW + w > width) flush()
                    if (w > width) {
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
        return lines.ifEmpty { listOf(Line(emptyList(), 0f, leading)) }
    }

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
        if (pageNo > 1) y = top - 6 * MM
    }

    private fun ensure(height: Float) {
        if (lockedDepth > 0) return
        if (y - height < bottom) newPage()
    }

    private inline fun <T> locked(body: () -> T): T {
        lockedDepth += 1
        try {
            return body()
        } finally {
            lockedDepth -= 1
        }
    }

    private fun fits(height: Float): Boolean = y - height >= bottom

    private class RowCut(
        val head: List<List<Line>>,
        val headHeight: Float,
        val tail: List<List<Line>>?,
        val tailHeight: Float,
    )

    private fun cutRow(
        columns: List<List<Line>>,
        height: Float,
        padding: Float,
        force: Boolean = false,
    ): RowCut? {
        if (lockedDepth > 0) {
            return RowCut(columns, height, null, 0f)
        }
        if (fits(height)) return RowCut(columns, height, null, 0f)
        if (height <= top - 6 * MM - bottom && !force) {
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

    private fun drawFurniture() {
        if (pageNo <= 1) return
        val meta = doc.meta
        val t = theme
        if (meta.headerText.isNotEmpty()) {
            val lines = wrap(runsOf(meta.headerText), textW, 7.8f)
            val kept = ArrayList<Line>()
            var baseline = pageH - margin + 4 * MM
            for (line in lines) {
                if (kept.isNotEmpty() && baseline + line.height > pageH - 2 * MM) break
                kept.add(line)
                baseline += line.height
            }
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
        val pageLabel = when {
            !meta.showPageNumbers -> ""
            totalPages > 0 -> "Page $pageNo of $totalPages"
            else -> "Page $pageNo"
        }
        val pageLabelW =
            if (pageLabel.isNotEmpty()) stringWidth(pageLabel, regularFace, 7.8f) else 0f
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
            val pieceBaseline = baseline + piece.rise
            textPaint.typeface = piece.face
            textPaint.textSize = piece.size
            val advance = textPaint.measureText(piece.text)
            if (piece.highlight) {
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

    private val sizeCache = HashMap<String, Pair<Int, Int>?>()

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
        if (size == null) droppedImages.add(ref.source)
        sizeCache[ref.source] = size
        return size
    }

    private fun sampleSizeFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) sample *= 2
        return sample
    }

    private fun decodeFitted(
        ref: ImageRef,
        intrinsic: Pair<Int, Int>,
        boxW: Float,
        boxH: Float,
    ): Bitmap? {
        if (intrinsic.first <= 0 || intrinsic.second <= 0) return null
        val data = bytesFor(ref) ?: return null
        if (data.isEmpty()) return null
        val quarter = ref.rotationDeg == 90 || ref.rotationDeg == 270
        val reqW = ((if (quarter) boxH else boxW) * IMAGE_OVERSAMPLE).roundToInt()
        val reqH = ((if (quarter) boxW else boxH) * IMAGE_OVERSAMPLE).roundToInt()
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(intrinsic.first, intrinsic.second, reqW, reqH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, opts) }.getOrNull()
    }

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
                val quarter = ref.rotationDeg == 90 || ref.rotationDeg == 270
                val targetW = if (quarter) h else w
                val targetH = if (quarter) w else h
                val m = Matrix()
                m.postScale(targetW / bitmap.width, targetH / bitmap.height)
                m.postRotate(ref.rotationDeg.toFloat())
                val boundsRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                m.mapRect(boundsRect)
                m.postTranslate(dx - boundsRect.left, cy(y) - boundsRect.top)
                canvas.drawBitmap(bitmap, m, bitmapPaint)
                bitmap.recycle()
            } else {
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
            val face = if (entry.level == 1) boldFace else regularFace
            var digitW = 0f
            for (digit in "0123456789") {
                digitW = max(digitW, stringWidth(digit.toString(), face, size))
            }
            val gutter = 4 * digitW
            val avail = max(textW - indent - (gutter + 4 * MM), 30 * MM)
            val color = if (entry.level == 1) t.ink else t.muted
            val lines =
                wrap(runsOf(label, bold = entry.level == 1), avail, size, leadingFactor = 1.6f)
            drawLines(lines, x + indent, avail, Align.LEFT, color)
            if (pageLabel.isNotEmpty() && drawing) {
                val last = lines.last()
                val baseline = y + last.height * 0.22f
                textPaint.typeface = face
                textPaint.textSize = size
                textPaint.color = argb(color)
                val pageLabelW = stringWidth(pageLabel, face, size)
                canvas.drawText(pageLabel, x + textW - pageLabelW, cy(baseline), textPaint)
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
        val lead = (if (level > 1) 4.5f else 6.5f) * MM
        val trail = (if (level == 1) 1.2f * MM else 0f) + 2.4f * MM
        val needed = lead + lines.sumOf { it.height.toDouble() }.toFloat() + trail + baseSize * 1.32f
        ensure(needed)
        y -= lead
        if (!drawing) {
            pendingPages[block.bookmark] = pageNo
            pendingEntries.add(TocEntry(level, block.number, runsText(block.runs), block.bookmark))
        }
        drawLines(lines, margin, textW, Align.LEFT, color)
        if (level == 1) {
            y -= 1.2f * MM
            if (drawing) {
                strokePaint.color = argb(t.rule)
                strokePaint.strokeWidth = 0.7f
                canvas.drawLine(margin, cy(y), margin + textW, cy(y), strokePaint)
            }
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
            if (hasHeader) {
                placeRow(headerLaid, headerH, t.tableHeaderFill, t.tableHeaderText)
            }
        }

        val rawFirstH =
            if (block.rows.isNotEmpty()) rowLines(block.rows[0], bodySize, false).second else 0f
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
        while (columns > 1 && (textW / columns) < 45 * MM) columns -= 1
        val cellW = textW / columns
        val capSize = 7.6f

        val maxImageH = (top - bottom) * 0.30f
        var start = 0
        while (start < block.images.size) {
            val chunk = block.images.subList(start, min(start + columns, block.images.size))
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

    private fun placeTallGridRow(
        cells: List<Triple<ImageRef, Float, List<Line>>>,
        cellW: Float,
        maxImageH: Float,
    ) {
        val gap = 1.2f * MM
        val remaining = cells.map { ArrayList(it.third) }
        val imageH = cells.maxOfOrNull { it.second } ?: 0f
        val firstLine = remaining.mapNotNull { it.firstOrNull()?.height }.maxOrNull() ?: 0f
        ensure(imageH + gap + firstLine + 3 * MM)
        var first = true
        while (true) {
            val rowTop = y
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

    private fun figureWidth(widthPct: Float): Float = textW * widthPct.coerceIn(20.0f, 100.0f) / 100

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

    private fun blockFigure(block: Block, fallbackSource: String, widthPct: Float, align: Align,
                            title: String, caption: String) {
        val ref = figureRef(block)
        if (ref == null) {
            if (!droppedImages.contains(fallbackSource)) droppedImages.add(fallbackSource)
            return
        }
        val maxH = (top - bottom) * 0.58f
        val figureW = figureWidth(widthPct)
        val box = imageBox(ref, figureW, maxH)
        val titleLines =
            if (title.isNotEmpty()) wrap(runsOf(title, bold = true), textW, baseSize) else emptyList()
        val reserve = titleLines.sumOf { it.height.toDouble() }.toFloat() +
            (if (titleLines.isNotEmpty()) 1.2f * MM else 0f)
        val boxH = box?.second ?: 0f
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

    fun writeTo(out: OutputStream) {
        val document = PdfDocument()
        pdf = document
        try {
            var previous: Map<String, Int> = emptyMap()
            val seen = HashSet<Map<String, Int>>()
            for (iteration in 0 until 8) {
                pendingPages.clear()
                pendingEntries.clear()
                runPass(drawing = false)
                tocEntries.clear()
                tocEntries.addAll(pendingEntries)
                if (pendingPages == previous) break
                if (!seen.add(HashMap(pendingPages))) break
                previous = HashMap(pendingPages)
            }

            pendingPages.clear()
            pendingEntries.clear()
            headingPages.clear()
            runPass(drawing = false)
            headingPages.putAll(pendingPages)
            totalPages = pageNo

            droppedImages.clear()
            sizeCache.entries.removeAll { it.value == null }

            pageCountDisagreement = null
            runPass(drawing = true)
            if (pageStarted) {
                drawFurniture()
                finishPage()
            }
            if (pageNo != totalPages) {
                pageCountDisagreement = pageNo to totalPages
                Log.e(
                    "PdfWriter",
                    "the drawing pass produced $pageNo pages and the measuring pass measured " +
                        "$totalPages. The contents page numbers and the 'of $totalPages' in the " +
                        "running foot describe a layout that was not drawn; something in the " +
                        "layout is applied on one pass and not the other.",
                )
            }
            document.writeTo(out)
        } finally {
            page?.let { runCatching { document.finishPage(it) } }
            page = null
            document.close()
            pdf = null
        }
    }
}

fun renderPdf(document: ReportDocument, loadImage: ImageLoader, out: OutputStream): List<String> {
    val writer = PdfWriter(document, loadImage)
    writer.writeTo(out)
    return writer.droppedImages.distinct().sorted()
}
