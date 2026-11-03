package com.designprototype.workshop.report

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private const val TWIP_PER_MM = 1440.0f / 25.4f
private const val EMU_PER_MM = 36000.0f

private val ALIGN_TO_JC = mapOf(
    Align.LEFT to "left",
    Align.CENTER to "center",
    Align.RIGHT to "right",
    Align.JUSTIFY to "both",
)

private const val RID_STYLES = 1
private const val RID_NUMBERING = 2
private const val RID_SETTINGS = 3
private const val RID_HEADER = 4
private const val RID_FOOTER = 5
private const val RID_FIRST_IMAGE = 6

private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"

private fun esc(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private fun be16(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

private fun be32(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
        ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

private val PNG_MAGIC = byteArrayOf(
    0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
    0x0D, 0x0A, 0x1A, 0x0A,
)

internal fun looksLikePng(data: ByteArray): Boolean {
    if (data.size < PNG_MAGIC.size) return false
    for (i in PNG_MAGIC.indices) if (data[i] != PNG_MAGIC[i]) return false
    return true
}

private fun pngSize(data: ByteArray): Pair<Int, Int>? {
    if (data.size < 24 || !looksLikePng(data)) return null
    return be32(data, 16) to be32(data, 20)
}

private val SOF_MARKERS = setOf(
    0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
)

private fun jpegSize(data: ByteArray): Pair<Int, Int>? {
    if (data.size < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null
    var i = 2
    val n = data.size
    while (i < n - 9) {
        if (data[i] != 0xFF.toByte()) {
            i += 1
            continue
        }
        val marker = data[i + 1].toInt() and 0xFF
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
            i += 2
            continue
        }
        if (marker == 0xD9) return null
        if (i + 4 > n) return null
        val segLen = be16(data, i + 2)
        if (marker in SOF_MARKERS) {
            if (i + 9 > n) return null
            val height = be16(data, i + 5)
            val width = be16(data, i + 7)
            return width to height
        }
        if (segLen < 2) return null
        i += 2 + segLen
    }
    return null
}

fun probeImageSize(data: ByteArray): Pair<Int, Int>? = pngSize(data) ?: jpegSize(data)

private val MIME_TO_EXT = mapOf("image/png" to "png", "image/jpeg" to "jpeg")

private fun rpr(
    run: Run,
    theme: ReportTheme,
    sizePt: Float? = null,
    color: String? = null,
): String {
    val parts = StringBuilder("<w:rPr>")
    if (run.script != Script.LATIN) {
        parts.append(
            "<w:rFonts w:ascii=\"${esc(theme.bodyFont)}\" w:hAnsi=\"${esc(theme.bodyFont)}\"" +
                " w:cs=\"${esc(theme.complexFont)}\"/>"
        )
    }
    if (run.bold) parts.append("<w:b/><w:bCs/>")
    if (run.italic) parts.append("<w:i/><w:iCs/>")
    if (run.underline) {
        parts.append("<w:u w:val=\"single\"/>")
    }
    if (run.strike) parts.append("<w:strike/>")
    if (run.superscript || run.subscript) {
        val value = if (run.superscript) "superscript" else "subscript"
        parts.append("<w:vertAlign w:val=\"$value\"/>")
    }
    if (run.highlight) {
        parts.append("<w:highlight w:val=\"yellow\"/>")
    }
    val effectiveColor = run.color ?: color
    if (effectiveColor != null) parts.append("<w:color w:val=\"${esc(effectiveColor)}\"/>")
    if (sizePt != null && sizePt != 0f) {
        val half = (sizePt * 2).roundToInt()
        parts.append("<w:sz w:val=\"$half\"/><w:szCs w:val=\"$half\"/>")
    }
    parts.append("</w:rPr>")
    return parts.toString()
}

private fun runXml(
    run: Run,
    theme: ReportTheme,
    sizePt: Float? = null,
    color: String? = null,
): String {
    if (run.text.isEmpty()) return ""
    val out = StringBuilder("<w:r>").append(rpr(run, theme, sizePt, color))
    run.text.split("\n").forEachIndexed { i, line ->
        if (i > 0) out.append("<w:br/>")
        if (line.isNotEmpty()) out.append("<w:t xml:space=\"preserve\">${esc(line)}</w:t>")
    }
    return out.append("</w:r>").toString()
}

private fun runsXml(
    runs: List<Run>,
    theme: ReportTheme,
    sizePt: Float? = null,
    color: String? = null,
): String = runs.joinToString("") { runXml(it, theme, sizePt, color) }

private fun para(
    content: String,
    style: String? = null,
    align: Align? = null,
    after: Int = 120,
    before: Int = 0,
    keepNext: Boolean = false,
    numbered: Int? = null,
    indent: Int = 0,
    outline: Int? = null,
    shading: String? = null,
    borderBottom: String? = null,
    line: Int? = null,
): String {
    val ppr = StringBuilder("<w:pPr>")
    if (style != null) ppr.append("<w:pStyle w:val=\"${esc(style)}\"/>")
    if (keepNext) ppr.append("<w:keepNext/><w:keepLines/>")
    if (numbered != null) {
        ppr.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$numbered\"/></w:numPr>")
    }
    if (borderBottom != null) {
        ppr.append(
            "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"4\" " +
                "w:color=\"${esc(borderBottom)}\"/></w:pBdr>"
        )
    }
    if (shading != null) {
        ppr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${esc(shading)}\"/>")
    }
    ppr.append("<w:spacing w:before=\"$before\" w:after=\"$after\"")
    if (line != null) ppr.append(" w:line=\"$line\" w:lineRule=\"auto\"")
    ppr.append("/>")
    if (indent != 0) ppr.append("<w:ind w:left=\"$indent\"/>")
    if (align != null) ppr.append("<w:jc w:val=\"${ALIGN_TO_JC[align]}\"/>")
    if (outline != null) ppr.append("<w:outlineLvl w:val=\"$outline\"/>")
    ppr.append("</w:pPr>")
    return "<w:p>$ppr$content</w:p>"
}

private fun bookmark(name: String, ordinal: Int): Pair<String, String> {
    if (name.isEmpty()) return "" to ""
    return "<w:bookmarkStart w:id=\"$ordinal\" w:name=\"${esc(name)}\"/>" to
        "<w:bookmarkEnd w:id=\"$ordinal\"/>"
}

private fun pageBreak(): String = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"

private fun field(instruction: String): String =
    "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
        "<w:r><w:instrText xml:space=\"preserve\"> $instruction </w:instrText></w:r>" +
        "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
        "<w:r><w:rPr><w:color w:val=\"5A6B87\"/><w:sz w:val=\"17\"/></w:rPr><w:t>1</w:t></w:r>" +
        "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"

private fun cell(
    content: String,
    widthTwip: Int,
    fill: String? = null,
    leftBorder: String? = null,
    topBorder: String? = null,
): String {
    val tcPr = StringBuilder("<w:tcPr><w:tcW w:w=\"$widthTwip\" w:type=\"dxa\"/>")
    if (leftBorder != null || topBorder != null) {
        tcPr.append("<w:tcBorders>")
        if (topBorder != null) {
            tcPr.append(
                "<w:top w:val=\"single\" w:sz=\"12\" w:space=\"0\" w:color=\"${esc(topBorder)}\"/>"
            )
        }
        if (leftBorder != null) {
            tcPr.append(
                "<w:left w:val=\"single\" w:sz=\"18\" w:space=\"0\" w:color=\"${esc(leftBorder)}\"/>"
            )
        }
        tcPr.append("</w:tcBorders>")
    }
    if (fill != null) {
        tcPr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${esc(fill)}\"/>")
    }
    tcPr.append("<w:vAlign w:val=\"top\"/></w:tcPr>")
    return "<w:tc>$tcPr${content.ifEmpty { para("") }}</w:tc>"
}

private fun tbl(
    rows: List<String>,
    widths: List<Int>,
    totalTwip: Int,
    borders: Boolean,
    ruleColor: String = "B8C4D9",
): String {
    val grid = widths.joinToString("") { "<w:gridCol w:w=\"$it\"/>" }
    val borderXml = if (!borders) "" else {
        "<w:tblBorders>" + listOf("top", "left", "bottom", "right", "insideH", "insideV")
            .joinToString("") {
                "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"${esc(ruleColor)}\"/>"
            } + "</w:tblBorders>"
    }
    return "<w:tbl><w:tblPr>" +
        "<w:tblW w:w=\"$totalTwip\" w:type=\"dxa\"/>" +
        borderXml +
        "<w:tblLayout w:type=\"fixed\"/>" +
        "<w:tblCellMar>" +
        "<w:top w:w=\"70\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/>" +
        "<w:bottom w:w=\"70\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/>" +
        "</w:tblCellMar>" +
        "</w:tblPr>" +
        "<w:tblGrid>$grid</w:tblGrid>" +
        rows.joinToString("") +
        "</w:tbl>"
}

class DocxWriter(
    private val doc: ReportDocument,
    private val loadImage: ImageLoader,
) {
    private val theme = doc.theme
    private val body = ArrayList<String>()

    private class MediaPart(
        val name: String,
        val image: ImageRef,
        val mime: String,
        val rid: Int,
        val probedWidth: Int,
        val probedHeight: Int,
    )

    private val media = ArrayList<MediaPart>()
    private val ridBySource = HashMap<String, Int>()
    private var drawingSeq = 0
    private var bookmarkSeq = 0

    private val figures = HashMap<String, ByteArray>()
    private var figureSeq = 0

    private val charts = ArrayList<Pair<String, ByteArray>>()

    internal val rasterisedCharts = ArrayList<String>()

    val droppedImages = ArrayList<String>()

    private val pageWMm: Float
    private val pageHMm: Float
    private val marginMm: Float

    private val textWMm: Float
    private val textHMm: Float
    private val textWTwip: Int

    init {
        val (w, h) = doc.meta.pageSize.sizeMm
        pageWMm = w
        pageHMm = h
        marginMm = doc.meta.marginMm
        textWMm = w - 2 * marginMm
        textHMm = h - 2 * marginMm
        textWTwip = (textWMm * TWIP_PER_MM).toInt()
    }

    private class Registered(val rid: Int, val widthPx: Int, val heightPx: Int)

    private fun bytesFor(image: ImageRef): ByteArray? = figures[image.source] ?: loadImage(image)

    private fun registerImage(image: ImageRef): Registered? {
        val cached = ridBySource[image.source]
        if (cached != null) {
            val part = media.first { it.rid == cached }
            val (w, h) = displaySize(image, part.probedWidth to part.probedHeight)
            return Registered(cached, w, h)
        }

        val data = bytesFor(image)
        if (data == null || data.isEmpty()) {
            droppedImages.add(image.source)
            return null
        }

        val probed = probeImageSize(data)
        if (probed == null) {
            droppedImages.add(image.source)
            return null
        }

        val mime = if (looksLikePng(data)) "image/png" else "image/jpeg"
        val rid = RID_FIRST_IMAGE + media.size
        val ext = MIME_TO_EXT.getValue(mime)
        media.add(
            MediaPart("image${media.size + 1}.$ext", image, mime, rid, probed.first, probed.second)
        )
        ridBySource[image.source] = rid
        val (w, h) = displaySize(image, probed)
        return Registered(rid, w, h)
    }

    private fun displaySize(image: ImageRef, probed: Pair<Int, Int>?): Pair<Int, Int> {
        var w = image.displayWidthPx
        var h = image.displayHeightPx
        if ((w <= 0 || h <= 0) && probed != null) {
            var pw = probed.first
            var ph = probed.second
            if (image.rotationDeg == 90 || image.rotationDeg == 270) {
                val t = pw
                pw = ph
                ph = t
            }
            w = pw
            h = ph
        }
        if (w <= 0 || h <= 0) {
            w = 800
            h = 600
        }
        return w to h
    }

    private fun drawingXml(image: ImageRef, widthMm: Float, maxHeightMm: Float): String {
        val registered = registerImage(image) ?: return ""
        val aspect = if (registered.heightPx != 0) {
            registered.widthPx.toFloat() / registered.heightPx.toFloat()
        } else {
            image.aspect
        }

        var wMm = widthMm
        var hMm = if (aspect != 0f) wMm / aspect else widthMm * 0.75f
        if (hMm > maxHeightMm) {
            hMm = maxHeightMm
            wMm = hMm * aspect
        }

        val cx = (wMm * EMU_PER_MM).toInt()
        val cy = (hMm * EMU_PER_MM).toInt()

        val rotation = if (image.rotationDeg != 0) ((image.rotationDeg % 360) + 360) % 360 else 0
        val extCx = if (rotation == 90 || rotation == 270) cy else cx
        val extCy = if (rotation == 90 || rotation == 270) cx else cy
        val rotAttr = if (rotation != 0) " rot=\"${rotation * 60000}\"" else ""

        drawingSeq += 1
        val n = drawingSeq
        return "<w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
            "<wp:docPr id=\"$n\" name=\"Picture $n\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"$NS_A\" noChangeAspect=\"1\"/>" +
            "</wp:cNvGraphicFramePr>" +
            "<a:graphic xmlns:a=\"$NS_A\">" +
            "<a:graphicData uri=\"$NS_PIC\">" +
            "<pic:pic xmlns:pic=\"$NS_PIC\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"Picture $n\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rId${registered.rid}\"/>" +
            "<a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm$rotAttr><a:off x=\"0\" y=\"0\"/>" +
            "<a:ext cx=\"$extCx\" cy=\"$extCy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
    }

    private fun emitCover(block: CoverBlock) {
        val t = theme
        if (block.logo != null) {
            val drawing = drawingXml(block.logo, textWMm * 0.22f, 28f)
            if (drawing.isNotEmpty()) {
                body.add(para(drawing, align = Align.CENTER, after = 240))
            }
        } else {
            body.add(para("", after = 1200))
        }

        for (line in block.orgLines) {
            body.add(
                para(
                    runsXml(runsOf(line), t, sizePt = 11f, color = t.muted),
                    align = Align.CENTER, after = 60,
                )
            )
        }

        body.add(para("", after = 380))
        body.add(
            para(
                runsXml(runsOf(block.title, bold = true), t, sizePt = 27f, color = t.accent),
                align = Align.CENTER, after = 140,
            )
        )
        if (block.subtitle.isNotEmpty()) {
            body.add(
                para(
                    runsXml(
                        runsOf(block.subtitle, italic = true), t, sizePt = 13f, color = t.accentSoft
                    ),
                    align = Align.CENTER, after = 260,
                )
            )
        }

        if (block.heroImage != null) {
            val drawing = drawingXml(block.heroImage, textWMm * 0.78f, textHMm * 0.34f)
            if (drawing.isNotEmpty()) {
                body.add(para(drawing, align = Align.CENTER, after = 260))
            }
        }

        if (block.infoRows.isNotEmpty()) {
            emitTable(
                TableBlock(
                    columns = listOf(
                        TableColumn("", 32.0f),
                        TableColumn("", 68.0f),
                    ),
                    rows = block.infoRows.map { (label, value) ->
                        listOf(runsOf(label, bold = true), runsOf(value))
                    },
                    zebra = true,
                ),
                headerless = true,
            )
        }

        for (line in block.footerLines) {
            body.add(
                para(
                    runsXml(runsOf(line), t, sizePt = 9.5f, color = t.muted),
                    align = Align.CENTER, after = 60,
                )
            )
        }
        body.add(pageBreak())
    }

    private fun emitToc(block: TocBlock) {
        val t = theme
        body.add(
            para(
                runsXml(runsOf(block.title, bold = true), t, sizePt = 16f, color = t.accent),
                style = "TOCHeading", after = 180,
            )
        )
        val depth = block.depth.coerceIn(1, 4)
        body.add(
            "<w:p><w:pPr><w:tabs>" +
                "<w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"$textWTwip\"/>" +
                "</w:tabs></w:pPr>" +
                "<w:r><w:fldChar w:fldCharType=\"begin\" w:dirty=\"true\"/></w:r>" +
                "<w:r><w:instrText xml:space=\"preserve\"> TOC \\o \"1-$depth\" \\h \\z \\u " +
                "</w:instrText></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
                "<w:r><w:rPr><w:i/><w:color w:val=\"5A6B87\"/></w:rPr>" +
                "<w:t>Right-click here and choose &quot;Update Field&quot; to build the " +
                "contents.</w:t></w:r>" +
                "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
        )
        body.add(pageBreak())
    }

    private fun emitHeading(block: HeadingBlock) {
        bookmarkSeq += 1
        val (start, end) = bookmark(block.bookmark, bookmarkSeq)
        val label = if (block.number.isNotEmpty()) "${block.number}. " else ""
        val content = StringBuilder(start)
        if (label.isNotEmpty()) content.append(runXml(Run(text = label, bold = true), theme))
        content.append(runsXml(block.runs, theme)).append(end)
        body.add(
            para(
                content.toString(),
                style = "Heading${block.level}",
                keepNext = true,
                outline = block.level - 1,
                before = intArrayOf(320, 260, 220, 180)[block.level - 1],
                after = 120,
            )
        )
    }

    private fun emitParagraph(block: ParagraphBlock) {
        val t = theme
        val style: String?
        val size: Float?
        val color: String?
        val indent: Int
        when (block.style) {
            ParaStyle.BODY -> { style = null; size = null; color = null; indent = 0 }
            ParaStyle.LEAD -> { style = null; size = 12.0f; color = t.ink; indent = 0 }
            ParaStyle.NOTE -> { style = "ReportNote"; size = 9.0f; color = t.muted; indent = 0 }
            ParaStyle.QUOTE -> { style = "ReportQuote"; size = null; color = t.accentSoft; indent = 420 }
            ParaStyle.CAPTION -> { style = "Caption"; size = null; color = null; indent = 0 }
            ParaStyle.COVER_LINE -> { style = null; size = 11.0f; color = t.muted; indent = 0 }
        }
        var runs = block.runs
        if (block.style == ParaStyle.QUOTE) runs = runs.map { it.copy(italic = true) }
        body.add(
            para(
                runsXml(runs, t, sizePt = size, color = color),
                style = style, align = block.align, indent = indent, after = 140, line = 276,
            )
        )
    }

    private fun emitBullets(block: BulletListBlock) {
        val numId = if (block.ordered) 2 else 1
        for (item in block.items) {
            body.add(para(runsXml(item, theme), numbered = numId, after = 60, line = 276))
        }
    }

    private fun emitKeyValues(block: KeyValueBlock) {
        val t = theme
        val perRow = block.columns.coerceIn(1, 2)
        val labelPct = block.labelWidthPct / perRow
        val valuePct = (100.0f / perRow) - labelPct

        val labelTwip = (textWTwip * labelPct / 100).toInt()
        val valueTwip = (textWTwip * valuePct / 100).toInt()

        val rows = ArrayList<String>()
        val pairs = block.pairs
        var i = 0
        while (i < pairs.size) {
            val chunk = pairs.subList(i, minOf(i + perRow, pairs.size))
            val cells = ArrayList<String>()
            for ((label, value) in chunk) {
                cells.add(
                    cell(
                        para(
                            runXml(Run(text = label, bold = true), t, sizePt = 9.5f, color = t.muted),
                            after = 40,
                        ),
                        widthTwip = labelTwip,
                    )
                )
                cells.add(cell(para(runsXml(value, t), after = 40), widthTwip = valueTwip))
            }
            while (cells.size < perRow * 2) {
                cells.add(cell(para(""), widthTwip = labelTwip))
            }
            rows.add("<w:tr>${cells.joinToString("")}</w:tr>")
            i += perRow
        }

        val gridWidths = ArrayList<Int>()
        repeat(perRow) {
            gridWidths.add(labelTwip)
            gridWidths.add(valueTwip)
        }
        body.add(tbl(rows, gridWidths, textWTwip, borders = false))
        body.add(para("", after = 100))
    }

    private fun emitTable(block: TableBlock, headerless: Boolean = false) {
        val t = theme
        val widths = block.columns.map { (textWTwip * it.widthPct / 100).toInt() }
        val rows = ArrayList<String>()

        if (!headerless) {
            val headerCells = block.columns.mapIndexed { j, col ->
                cell(
                    para(
                        runXml(
                            Run(text = col.header, bold = true), t,
                            sizePt = 9.0f, color = t.tableHeaderText,
                        ),
                        align = col.align, after = 0,
                    ),
                    widthTwip = widths[j], fill = t.tableHeaderFill,
                )
            }
            rows.add(
                "<w:tr><w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>" +
                    headerCells.joinToString("") + "</w:tr>"
            )
        }

        block.rows.forEachIndexed { i, row ->
            val fill = if (block.zebra && i % 2 == 1) t.zebraFill else null
            val cells = block.columns.mapIndexed { j, col ->
                val value = if (j < row.size) row[j] else emptyList()
                val align = if (col.numeric) Align.RIGHT else col.align
                cell(
                    para(runsXml(value, t, sizePt = 9.5f), align = align, after = 0),
                    widthTwip = widths[j], fill = fill,
                )
            }
            rows.add("<w:tr>${cells.joinToString("")}</w:tr>")
        }

        val totalRow = block.totalRow
        if (totalRow != null) {
            val cells = block.columns.mapIndexed { j, col ->
                val value = if (j < totalRow.size) totalRow[j] else emptyList()
                val bolded = value.map { it.copy(bold = true) }
                val align = if (col.numeric) Align.RIGHT else col.align
                cell(
                    para(runsXml(bolded, t, sizePt = 9.5f), align = align, after = 0),
                    widthTwip = widths[j], fill = t.zebraFill, topBorder = t.accent,
                )
            }
            rows.add("<w:tr>${cells.joinToString("")}</w:tr>")
        }

        body.add(tbl(rows, widths, textWTwip, borders = true, ruleColor = t.rule))
        body.add(para("", after = 100))
        if (block.caption.isNotEmpty()) {
            body.add(
                para(
                    runsXml(runsOf(block.caption, italic = true), t, sizePt = 9.0f, color = t.muted),
                    style = "Caption", align = Align.CENTER, after = 180,
                )
            )
        }
    }

    private fun emitImage(block: ImageBlock) {
        val drawing = drawingXml(
            block.image,
            widthMm = textWMm * block.widthPct.coerceIn(5.0f, 100.0f) / 100,
            maxHeightMm = textHMm * 0.62f,
        )
        if (drawing.isEmpty()) return
        body.add(
            para(drawing, align = block.align, after = 60, keepNext = block.caption.isNotEmpty())
        )
        if (block.caption.isNotEmpty()) {
            body.add(
                para(
                    runsXml(
                        runsOf(block.caption, italic = true), theme,
                        sizePt = 9.0f, color = theme.muted,
                    ),
                    style = "Caption", align = Align.CENTER, after = 200,
                )
            )
        }
    }

    private fun emitImageGrid(block: ImageGridBlock) {
        if (block.images.isEmpty()) return
        val t = theme
        var columns = block.columns.coerceIn(1, 4)
        while (columns > 1 && (textWMm / columns) < 45.0f) columns -= 1

        val cellWMm = textWMm / columns
        val cellWTwip = textWTwip / columns
        val widths = List(columns) { cellWTwip }
        val rows = ArrayList<String>()

        var start = 0
        while (start < block.images.size) {
            val chunk = block.images.subList(start, minOf(start + columns, block.images.size))
            val cells = ArrayList<String>()
            for ((image, caption) in chunk) {
                val inner = StringBuilder()
                val drawing = drawingXml(image, cellWMm - 6, textHMm * 0.30f)
                if (drawing.isNotEmpty()) {
                    inner.append(
                        para(
                            drawing, align = Align.CENTER, after = 40,
                            keepNext = caption.isNotEmpty(),
                        )
                    )
                }
                if (caption.isNotEmpty()) {
                    inner.append(
                        para(
                            runsXml(runsOf(caption, italic = true), t, sizePt = 8.5f, color = t.muted),
                            align = Align.CENTER, after = 60,
                        )
                    )
                }
                if (inner.isEmpty()) inner.append(para("", after = 0))
                cells.add(cell(inner.toString(), widthTwip = cellWTwip))
            }
            while (cells.size < columns) cells.add(cell(para("", after = 0), widthTwip = cellWTwip))
            rows.add(
                "<w:tr><w:trPr><w:cantSplit/></w:trPr>${cells.joinToString("")}</w:tr>"
            )
            start += columns
        }

        body.add(tbl(rows, widths, textWTwip, borders = false))
        body.add(para("", after = 100))
        if (block.caption.isNotEmpty()) {
            body.add(
                para(
                    runsXml(runsOf(block.caption, italic = true), t, sizePt = 9.0f, color = t.muted),
                    style = "Caption", align = Align.CENTER, after = 180,
                )
            )
        }
    }

    private fun figureWidthMm(widthPct: Float): Double =
        textWMm.toDouble() * widthPct.toDouble().coerceIn(20.0, 100.0) / 100.0

    private fun emitFigure(
        figure: RasterFigure,
        widthPct: Float,
        align: Align,
        title: String,
        caption: String,
    ) {
        figureSeq += 1
        val source = "figure:$figureSeq"
        figures[source] = figure.png
        val ref = ImageRef(
            source = source, widthPx = figure.widthPx, heightPx = figure.heightPx,
            mimeType = "image/png",
        )

        emitFigureTitle(title)
        val drawing = drawingXml(ref, figureWidthMm(widthPct).toFloat(), textHMm * 0.58f)
        if (drawing.isEmpty()) return
        body.add(para(drawing, align = align, after = 60, keepNext = caption.isNotEmpty()))
        emitFigureCaption(caption)
    }

    private fun emitFigureTitle(title: String) {
        if (title.isEmpty()) return
        body.add(
            para(
                runXml(Run(text = title, bold = true), theme, sizePt = 10.5f, color = theme.accent),
                after = 60, keepNext = true,
            )
        )
    }

    private fun emitFigureCaption(caption: String) {
        if (caption.isEmpty()) return
        body.add(
            para(
                runsXml(runsOf(caption, italic = true), theme, sizePt = 9.0f, color = theme.muted),
                style = "Caption", align = Align.CENTER, after = 200,
            )
        )
    }

    private fun emitMap(block: MapBlock) {
        val figure = renderMapPng(block, theme, pixelsForMm(figureWidthMm(block.widthPct)))
        if (figure == null) {
            droppedImages.add("map:india")
            return
        }
        emitFigure(figure, block.widthPct, block.align, block.title, block.caption)
    }

    private fun emitChart(block: ChartBlock) {
        val (series, notes) = cleanSeries(block)
        if (emitNativeChart(block, series)) {
            for (note in notes) {
                body.add(
                    para(
                        runsXml(runsOf(note), theme, sizePt = 8.0f, color = theme.muted),
                        align = Align.CENTER, after = 40,
                    )
                )
            }
            emitFigureCaption(block.caption)
            return
        }
        val figure = renderChartPng(block, theme, pixelsForMm(figureWidthMm(block.widthPct)))
        emitFigure(figure, block.widthPct, block.align, block.title, block.caption)
    }

    private fun emitNativeChart(block: ChartBlock, series: List<Pair<String, Double>>): Boolean {
        if (series.isEmpty()) {
            rasterisedCharts.add(block.title.ifEmpty { block.kind.name })
            return false
        }

        var wMm = figureWidthMm(block.widthPct)
        val (pxW, pxH) = chartPixelBox(block, pixelsForMm(wMm), series.size)
        var hMm = if (pxW != 0) wMm * pxH / pxW else wMm * 0.62
        if (block.kind == ChartKind.HORIZONTAL_BAR) {
            hMm = max(hMm, NATIVE_CHROME_MM + NATIVE_ROW_MM * series.size)
        }
        val maxHMm = textHMm * 0.58
        if (hMm > maxHMm) {
            wMm *= maxHMm / hMm
            hMm = maxHMm
        }

        charts.add(chartSpaceXml(block, series, theme) to chartWorkbookXlsx(block, series))
        val rid = "$CHART_RID_PREFIX${charts.size}"

        emitFigureTitle(block.title)
        body.add(
            para(
                chartDrawing(rid, wMm, hMm), align = block.align, after = 60,
                keepNext = block.caption.isNotEmpty(),
            )
        )
        return true
    }

    private fun chartDrawing(rid: String, widthMm: Double, heightMm: Double): String {
        drawingSeq += 1
        val n = drawingSeq
        val cx = (widthMm * EMU_PER_MM).toInt()
        val cy = (heightMm * EMU_PER_MM).toInt()
        return "<w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
            "<wp:docPr id=\"$n\" name=\"Chart $n\"/>" +
            "<wp:cNvGraphicFramePr/>" +
            "<a:graphic xmlns:a=\"$NS_A\">" +
            "<a:graphicData uri=\"$NS_C\">" +
            "<c:chart xmlns:c=\"$NS_C\" xmlns:r=\"$NS_R\" r:id=\"${esc(rid)}\"/>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
    }

    private fun emitMetrics(block: MetricRowBlock) {
        if (block.metrics.isEmpty()) return
        val t = theme
        val n = block.metrics.size
        val width = textWTwip / n
        val cells = block.metrics.map { (label, value, unit) ->
            val content = para(
                runXml(Run(text = value, bold = true), t, sizePt = 18f, color = t.accent) +
                    if (unit.isNotEmpty()) {
                        runXml(Run(text = " $unit"), t, sizePt = 9.5f, color = t.muted)
                    } else "",
                align = Align.CENTER, after = 20,
            ) + para(
                runXml(Run(text = label), t, sizePt = 8.5f, color = t.muted),
                align = Align.CENTER, after = 0,
            )
            cell(content, widthTwip = width, fill = t.zebraFill)
        }
        body.add(
            tbl(
                listOf("<w:tr>${cells.joinToString("")}</w:tr>"),
                List(n) { width }, textWTwip, borders = false,
            )
        )
        body.add(para("", after = 140))
    }

    private fun emitCallout(block: CalloutBlock) {
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
        val inner = StringBuilder()
        if (block.title.isNotEmpty()) {
            inner.append(
                para(
                    runXml(Run(text = block.title, bold = true), t, sizePt = 10f, color = edge),
                    after = 40,
                )
            )
        }
        inner.append(para(runsXml(block.runs, t, sizePt = 9.5f), after = 0))
        val c = cell(inner.toString(), widthTwip = textWTwip, fill = fill, leftBorder = edge)
        body.add(tbl(listOf("<w:tr>$c</w:tr>"), listOf(textWTwip), textWTwip, borders = false))
        body.add(para("", after = 140))
    }

    private fun emitSignatures(block: SignatureBlock) {
        if (block.signatories.isEmpty()) return
        val t = theme
        val n = block.signatories.size
        val width = textWTwip / n
        body.add(para("", after = 700))
        val cells = block.signatories.map { (name, designation) ->
            val content = para("", after = 0, borderBottom = t.ink) +
                para(
                    runXml(Run(text = name, bold = true), t, sizePt = 10f),
                    align = Align.CENTER, after = 20,
                ) +
                para(
                    runXml(Run(text = designation), t, sizePt = 8.5f, color = t.muted),
                    align = Align.CENTER, after = 0,
                )
            cell(content, widthTwip = width)
        }
        body.add(
            tbl(
                listOf("<w:tr>${cells.joinToString("")}</w:tr>"),
                List(n) { width }, textWTwip, borders = false,
            )
        )
        body.add(para("", after = 100))
    }

    internal fun emitBlocks() {
        for (block in doc.blocks) {
            when (block) {
                is CoverBlock -> emitCover(block)
                is TocBlock -> emitToc(block)
                is HeadingBlock -> emitHeading(block)
                is ParagraphBlock -> emitParagraph(block)
                is BulletListBlock -> emitBullets(block)
                is KeyValueBlock -> emitKeyValues(block)
                is TableBlock -> emitTable(block)
                is ImageBlock -> emitImage(block)
                is ImageGridBlock -> emitImageGrid(block)
                is MetricRowBlock -> emitMetrics(block)
                is MapBlock -> emitMap(block)
                is ChartBlock -> emitChart(block)
                is CalloutBlock -> emitCallout(block)
                is SignatureBlock -> emitSignatures(block)
                is SpacerBlock -> body.add(para("", after = (block.heightPct * 24).toInt()))
                is PageBreakBlock -> body.add(pageBreak())
            }
        }
    }

    internal fun validateBody() {
        for (i in 0 until body.size - 1) {
            if (body[i].startsWith("<w:tbl>") && body[i + 1].startsWith("<w:tbl>")) {
                throw IllegalStateException(
                    "two adjacent tables at body index $i: Word merges these silently. " +
                        "Every emit* that appends a table must append a paragraph after it."
                )
            }
        }
    }

    internal fun bodyForTest(): MutableList<String> = body

    fun writeTo(out: OutputStream) {
        emitBlocks()
        validateBody()

        val zip = ZipOutputStream(out)
        putText(zip, "[Content_Types].xml", contentTypes())
        putText(zip, "_rels/.rels", ROOT_RELS)
        putText(zip, "word/document.xml", documentXml())
        putText(zip, "word/_rels/document.xml.rels", documentRels())
        putText(zip, "word/styles.xml", stylesXml(theme))
        putText(zip, "word/numbering.xml", NUMBERING_XML)
        putText(zip, "word/settings.xml", SETTINGS_XML)
        putText(zip, "word/header1.xml", headerXml())
        putText(zip, "word/footer1.xml", footerXml())
        putText(zip, "docProps/core.xml", coreXml())
        putText(zip, "docProps/app.xml", APP_XML)
        for (part in media) {
            val data = bytesFor(part.image)
            zip.putNextEntry(ZipEntry("word/media/${part.name}"))
            if (data == null || data.isEmpty()) {
                droppedImages.add(part.image.source)
            } else {
                zip.write(data)
            }
            zip.closeEntry()
        }
        for ((index, chart) in charts.withIndex()) {
            val n = index + 1
            putText(zip, "word/charts/chart$n.xml", chart.first)
            putText(zip, "word/charts/_rels/chart$n.xml.rels", chartPartRels("chart$n.xlsx"))
            zip.putNextEntry(ZipEntry("word/embeddings/chart$n.xlsx"))
            zip.write(chart.second)
            zip.closeEntry()
        }
        zip.finish()
    }

    fun build(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes(): String {
        val extensions = sortedMapOf(
            "rels" to "application/vnd.openxmlformats-package.relationships+xml",
            "xml" to "application/xml",
        )
        for (part in media) extensions[MIME_TO_EXT.getValue(part.mime)] = part.mime
        if (charts.isNotEmpty()) {
            extensions["xlsx"] = XLSX_MIME
        }
        val defaults = extensions.entries.joinToString("") { (ext, ct) ->
            "<Default Extension=\"$ext\" ContentType=\"$ct\"/>"
        }
        val wml = "application/vnd.openxmlformats-officedocument.wordprocessingml"
        val overrides = listOf(
            "<Override PartName=\"/word/document.xml\" ContentType=\"$wml.document.main+xml\"/>",
            "<Override PartName=\"/word/styles.xml\" ContentType=\"$wml.styles+xml\"/>",
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"$wml.numbering+xml\"/>",
            "<Override PartName=\"/word/settings.xml\" ContentType=\"$wml.settings+xml\"/>",
            "<Override PartName=\"/word/header1.xml\" ContentType=\"$wml.header+xml\"/>",
            "<Override PartName=\"/word/footer1.xml\" ContentType=\"$wml.footer+xml\"/>",
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd." +
                "openxmlformats-package.core-properties+xml\"/>",
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd." +
                "openxmlformats-officedocument.extended-properties+xml\"/>",
        ).joinToString("") +
            (1..charts.size).joinToString("") {
                "<Override PartName=\"/word/charts/chart$it.xml\" " +
                    "ContentType=\"$CHART_PART_TYPE\"/>"
            }
        return XML_DECL +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            defaults + overrides + "</Types>"
    }

    private fun documentRels(): String {
        val base = "$NS_R/"
        val fixed = listOf(
            "<Relationship Id=\"rId$RID_STYLES\" Type=\"${base}styles\" Target=\"styles.xml\"/>",
            "<Relationship Id=\"rId$RID_NUMBERING\" Type=\"${base}numbering\" " +
                "Target=\"numbering.xml\"/>",
            "<Relationship Id=\"rId$RID_SETTINGS\" Type=\"${base}settings\" " +
                "Target=\"settings.xml\"/>",
            "<Relationship Id=\"rId$RID_HEADER\" Type=\"${base}header\" Target=\"header1.xml\"/>",
            "<Relationship Id=\"rId$RID_FOOTER\" Type=\"${base}footer\" Target=\"footer1.xml\"/>",
        ).joinToString("")
        val mediaRels = media.joinToString("") {
            "<Relationship Id=\"rId${it.rid}\" Type=\"${base}image\" Target=\"media/${it.name}\"/>"
        }
        val chartRels = (1..charts.size).joinToString("") {
            "<Relationship Id=\"$CHART_RID_PREFIX$it\" Type=\"${base}chart\" " +
                "Target=\"charts/chart$it.xml\"/>"
        }
        return XML_DECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/" +
            "relationships\">" + fixed + mediaRels + chartRels + "</Relationships>"
    }

    private fun documentXml(): String {
        val w = (pageWMm * TWIP_PER_MM).toInt()
        val h = (pageHMm * TWIP_PER_MM).toInt()
        val m = (marginMm * TWIP_PER_MM).toInt()
        val sect = "<w:sectPr>" +
            "<w:headerReference w:type=\"default\" r:id=\"rId$RID_HEADER\"/>" +
            "<w:footerReference w:type=\"default\" r:id=\"rId$RID_FOOTER\"/>" +
            "<w:pgSz w:w=\"$w\" w:h=\"$h\"/>" +
            "<w:pgMar w:top=\"$m\" w:right=\"$m\" w:bottom=\"$m\" w:left=\"$m\" " +
            "w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/>" +
            "<w:titlePg/>" +
            "</w:sectPr>"
        return XML_DECL +
            "<w:document xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\" xmlns:wp=\"$NS_WP\" " +
            "xmlns:a=\"$NS_A\" xmlns:pic=\"$NS_PIC\">" +
            "<w:body>${body.joinToString("")}$sect</w:body></w:document>"
    }

    private fun headerXml(): String {
        val t = theme
        val text = doc.meta.headerText
        val content = if (text.isNotEmpty()) {
            runXml(Run(text = text), t, sizePt = 8.5f, color = t.muted)
        } else ""
        return XML_DECL + "<w:hdr xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\">" +
            para(content, align = Align.RIGHT, after = 0, borderBottom = t.rule) +
            "</w:hdr>"
    }

    private fun footerXml(): String {
        val t = theme
        val meta = doc.meta
        val left = if (meta.footerText.isNotEmpty()) {
            runXml(Run(text = meta.footerText), t, sizePt = 8.5f, color = t.muted)
        } else ""
        val pages = if (!meta.showPageNumbers) "" else {
            "<w:r><w:tab/></w:r>" +
                runXml(Run(text = "Page "), t, sizePt = 8.5f, color = t.muted) +
                field("PAGE") +
                runXml(Run(text = " of "), t, sizePt = 8.5f, color = t.muted) +
                field("NUMPAGES")
        }
        val ppr = "<w:pPr>" +
            "<w:pBdr><w:top w:val=\"single\" w:sz=\"6\" w:space=\"4\" " +
            "w:color=\"${esc(t.rule)}\"/></w:pBdr>" +
            "<w:tabs><w:tab w:val=\"right\" w:pos=\"$textWTwip\"/></w:tabs>" +
            "<w:spacing w:before=\"0\" w:after=\"0\"/>" +
            "</w:pPr>"
        return XML_DECL + "<w:ftr xmlns:w=\"$NS_W\" xmlns:r=\"$NS_R\">" +
            "<w:p>$ppr$left$pages</w:p></w:ftr>"
    }

    private fun coreXml(): String {
        val meta = doc.meta
        val stamp = meta.generatedAt.ifEmpty { "2026-01-01T00:00:00Z" }
        val who = meta.author.ifEmpty { meta.organisation }
        return XML_DECL +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/" +
            "metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<cp:category>${esc(meta.templateName)}</cp:category>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">${esc(stamp)}</dcterms:created>" +
            "<dc:creator>${esc(who)}</dc:creator>" +
            "<cp:lastModifiedBy>${esc(who)}</cp:lastModifiedBy>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">${esc(stamp)}</dcterms:modified>" +
            "<dc:subject>${esc(meta.subtitle)}</dc:subject>" +
            "<dc:title>${esc(meta.title)}</dc:title>" +
            "</cp:coreProperties>"
    }
}

private val ROOT_RELS =
    XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/" +
        "relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"$NS_R/extended-properties\" " +
        "Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

private val APP_XML =
    XML_DECL +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/" +
        "extended-properties\">" +
        "<Application>Design Prototype Workshop</Application>" +
        "</Properties>"

private val SETTINGS_XML =
    XML_DECL + "<w:settings xmlns:w=\"$NS_W\">" +
        "<w:zoom w:percent=\"100\"/>" +
        "<w:defaultTabStop w:val=\"720\"/>" +
        "<w:updateFields w:val=\"true\"/>" +
        "<w:compat><w:compatSetting w:name=\"compatibilityMode\" " +
        "w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat>" +
        "</w:settings>"

private val NUMBERING_XML =
    XML_DECL + "<w:numbering xmlns:w=\"$NS_W\">" +
        "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>" +
        "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/>" +
        "<w:lvlText w:val=\"•\"/><w:lvlJc w:val=\"left\"/>" +
        "<w:pPr><w:ind w:left=\"567\" w:hanging=\"284\"/></w:pPr>" +
        "<w:rPr><w:rFonts w:ascii=\"Symbol\" w:hAnsi=\"Symbol\" w:hint=\"default\"/></w:rPr>" +
        "</w:lvl></w:abstractNum>" +
        "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>" +
        "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/>" +
        "<w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/>" +
        "<w:pPr><w:ind w:left=\"567\" w:hanging=\"284\"/></w:pPr></w:lvl>" +
        "</w:abstractNum>" +
        "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
        "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>" +
        "</w:numbering>"

private fun stylesXml(theme: ReportTheme): String {
    val bodyFont = esc(theme.bodyFont)
    val headFont = esc(theme.headingFont)
    val cs = esc(theme.complexFont)
    val half = (theme.baseSizePt * 2).roundToInt()

    val levels = listOf(
        Triple(18.0f, theme.accent, 320),
        Triple(14.0f, theme.accent, 280),
        Triple(12.0f, theme.accentSoft, 240),
        Triple(11.0f, theme.muted, 200),
    )
    val headings = levels.mapIndexed { index, (size, color, before) ->
        val level = index + 1
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\">" +
            "<w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:keepLines/>" +
            "<w:spacing w:before=\"$before\" w:after=\"120\"/>" +
            "<w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
            "<w:rPr><w:rFonts w:ascii=\"$headFont\" w:hAnsi=\"$headFont\" w:cs=\"$cs\"/>" +
            "<w:b/><w:bCs/><w:color w:val=\"${esc(color)}\"/>" +
            "<w:sz w:val=\"${(size * 2).toInt()}\"/><w:szCs w:val=\"${(size * 2).toInt()}\"/>" +
            "</w:rPr></w:style>"
    }.joinToString("")

    fun simple(styleId: String, name: String, ppr: String, rpr: String): String =
        "<w:style w:type=\"paragraph\" w:styleId=\"$styleId\">" +
            "<w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
            "<w:qFormat/><w:pPr>$ppr</w:pPr><w:rPr>$rpr</w:rPr></w:style>"

    return XML_DECL + "<w:styles xmlns:w=\"$NS_W\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr>" +
        "<w:rFonts w:ascii=\"$bodyFont\" w:hAnsi=\"$bodyFont\" w:cs=\"$cs\"/>" +
        "<w:color w:val=\"${esc(theme.ink)}\"/>" +
        "<w:sz w:val=\"$half\"/><w:szCs w:val=\"$half\"/>" +
        "<w:lang w:val=\"en-IN\" w:bidi=\"hi-IN\"/>" +
        "</w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr>" +
        "<w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/>" +
        "</w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">" +
        "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        headings +
        simple(
            "Caption", "caption",
            "<w:spacing w:before=\"0\" w:after=\"200\"/><w:jc w:val=\"center\"/>",
            "<w:i/><w:iCs/><w:color w:val=\"${esc(theme.muted)}\"/><w:sz w:val=\"17\"/>",
        ) +
        simple(
            "ReportNote", "Report Note",
            "<w:spacing w:after=\"120\"/>",
            "<w:color w:val=\"${esc(theme.muted)}\"/><w:sz w:val=\"18\"/>",
        ) +
        simple(
            "ReportQuote", "Report Quote",
            "<w:ind w:left=\"420\"/><w:spacing w:after=\"140\"/>",
            "<w:i/><w:iCs/><w:color w:val=\"${esc(theme.accentSoft)}\"/>",
        ) +
        simple(
            "TOCHeading", "TOC Heading",
            "<w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"9\"/>",
            "<w:rFonts w:ascii=\"$headFont\" w:hAnsi=\"$headFont\"/><w:b/>" +
                "<w:color w:val=\"${esc(theme.accent)}\"/><w:sz w:val=\"32\"/>",
        ) +
        "</w:styles>"
}

private const val NS_C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
private const val NS_SS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

private const val CHART_PART_TYPE =
    "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
private const val XLSX_MIME =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

private const val CHART_RID_PREFIX = "cId"

private const val CAT_AX_ID = 111111111
private const val VAL_AX_ID = 222222222

private const val WORKBOOK_RID = "rId1"

private const val INDIAN_NUMBER_FORMAT =
    "[>=10000000]##\\,##\\,##\\,##0.##;[>=100000]##\\,##\\,##0.##;##\\,##0.##"

private const val ZIP_EPOCH_MILLIS = 315662400000L

private const val NATIVE_ROW_MM = 6.5
private const val NATIVE_CHROME_MM = 14.0

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun srgb(colour: Rgb): String {
    val value = colour and 0xFFFFFF
    val out = StringBuilder(6)
    for (shift in intArrayOf(20, 16, 12, 8, 4, 0)) out.append(HEX_DIGITS[(value shr shift) and 0xF])
    return out.toString()
}

private fun cellNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) {
        return "0"
    }
    val whole = value.toLong()
    if (value == whole.toDouble() && value > -1e15 && value < 1e15) return whole.toString()
    val text = BigDecimal(value).setScale(6, RoundingMode.HALF_UP).toPlainString()
    return text.trimEnd('0').trimEnd('.')
}

private fun chartTxPr(theme: ReportTheme, sizePt: Float, colour: String): String {
    val hundredths = (sizePt * 100).roundToInt()
    return "<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr>" +
        "<a:defRPr sz=\"$hundredths\" b=\"0\" i=\"0\">" +
        "<a:solidFill><a:srgbClr val=\"${esc(colour)}\"/></a:solidFill>" +
        "<a:latin typeface=\"${esc(theme.bodyFont)}\"/>" +
        "<a:cs typeface=\"${esc(theme.complexFont)}\"/>" +
        "</a:defRPr></a:pPr><a:endParaRPr lang=\"en-IN\"/></a:p></c:txPr>"
}

private fun solidFill(colourHex: String): String =
    "<a:solidFill><a:srgbClr val=\"${esc(colourHex)}\"/></a:solidFill>"

private fun strRef(formula: String, values: List<String>): String {
    val points = values.withIndex().joinToString("") { (i, v) ->
        "<c:pt idx=\"$i\"><c:v>${esc(cleanText(v))}</c:v></c:pt>"
    }
    return "<c:strRef><c:f>${esc(formula)}</c:f><c:strCache>" +
        "<c:ptCount val=\"${values.size}\"/>$points</c:strCache></c:strRef>"
}

private fun numRef(formula: String, values: List<Double>): String {
    val points = values.withIndex().joinToString("") { (i, v) ->
        "<c:pt idx=\"$i\"><c:v>${cellNumber(v)}</c:v></c:pt>"
    }
    return "<c:numRef><c:f>${esc(formula)}</c:f><c:numCache>" +
        "<c:formatCode>General</c:formatCode>" +
        "<c:ptCount val=\"${values.size}\"/>$points</c:numCache></c:numRef>"
}

private fun dataLabels(
    theme: ReportTheme,
    position: String?,
    showValue: Boolean,
    showPercent: Boolean,
    numberFormat: String,
): String {
    val out = StringBuilder("<c:dLbls>")
    out.append("<c:numFmt formatCode=\"${esc(numberFormat)}\" sourceLinked=\"0\"/>")
    out.append("<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>")
    out.append(chartTxPr(theme, 8.0f, theme.ink))
    if (position != null) out.append("<c:dLblPos val=\"$position\"/>")
    out.append("<c:showLegendKey val=\"0\"/>")
    out.append("<c:showVal val=\"${if (showValue) 1 else 0}\"/>")
    out.append("<c:showCatName val=\"0\"/><c:showSerName val=\"0\"/>")
    out.append("<c:showPercent val=\"${if (showPercent) 1 else 0}\"/>")
    out.append("<c:showBubbleSize val=\"0\"/>")
    out.append("</c:dLbls>")
    return out.toString()
}

private fun axisTitle(theme: ReportTheme, text: String, vertical: Boolean): String {
    if (text.isEmpty()) return ""
    val body = if (vertical) "<a:bodyPr rot=\"-5400000\" vert=\"horz\"/>" else "<a:bodyPr/>"
    return "<c:title><c:tx><c:rich>" +
        body + "<a:lstStyle/><a:p><a:pPr>" +
        "<a:defRPr sz=\"900\" b=\"0\" i=\"0\">${solidFill(theme.muted)}" +
        "<a:latin typeface=\"${esc(theme.bodyFont)}\"/>" +
        "<a:cs typeface=\"${esc(theme.complexFont)}\"/></a:defRPr></a:pPr>" +
        "<a:r><a:t>${esc(cleanText(text))}</a:t></a:r></a:p>" +
        "</c:rich></c:tx><c:overlay val=\"0\"/></c:title>"
}

private fun cartesianAxes(block: ChartBlock, theme: ReportTheme): String {
    val horizontal = block.kind == ChartKind.HORIZONTAL_BAR
    val catPos = if (horizontal) "l" else "b"
    val valPos = if (horizontal) "b" else "l"
    val catOrientation = if (horizontal) "maxMin" else "minMax"
    val gridLine = "<a:ln w=\"9525\">${solidFill(theme.rule)}</a:ln>"
    val crossBetween = if (block.kind == ChartKind.LINE) "midCat" else "between"
    return "<c:catAx><c:axId val=\"$CAT_AX_ID\"/>" +
        "<c:scaling><c:orientation val=\"$catOrientation\"/></c:scaling>" +
        "<c:delete val=\"0\"/>" +
        "<c:axPos val=\"$catPos\"/>" +
        "<c:numFmt formatCode=\"General\" sourceLinked=\"1\"/>" +
        "<c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/>" +
        "<c:tickLblPos val=\"nextTo\"/>" +
        "<c:spPr><a:noFill/>$gridLine</c:spPr>" +
        chartTxPr(theme, 8.0f, theme.muted) +
        "<c:crossAx val=\"$VAL_AX_ID\"/>" +
        "<c:crosses val=\"autoZero\"/>" +
        "<c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/>" +
        "<c:noMultiLvlLbl val=\"0\"/>" +
        "</c:catAx>" +
        "<c:valAx><c:axId val=\"$VAL_AX_ID\"/>" +
        "<c:scaling><c:orientation val=\"minMax\"/></c:scaling>" +
        "<c:delete val=\"0\"/>" +
        "<c:axPos val=\"$valPos\"/>" +
        "<c:majorGridlines><c:spPr>$gridLine</c:spPr></c:majorGridlines>" +
        axisTitle(theme, block.unit, vertical = !horizontal) +
        "<c:numFmt formatCode=\"${esc(INDIAN_NUMBER_FORMAT)}\" sourceLinked=\"0\"/>" +
        "<c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/>" +
        "<c:tickLblPos val=\"nextTo\"/>" +
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>" +
        chartTxPr(theme, 8.0f, theme.muted) +
        "<c:crossAx val=\"$CAT_AX_ID\"/>" +
        "<c:crosses val=\"autoZero\"/>" +
        "<c:crossBetween val=\"$crossBetween\"/>" +
        "</c:valAx>"
}

private fun barGroup(
    block: ChartBlock,
    series: List<Pair<String, Double>>,
    theme: ReportTheme,
    tx: String,
    cat: String,
    value: String,
): String {
    val soft = rgbOf(theme.accentSoft, rgb(47, 84, 150))
    val ink = rgbOf(theme.ink, rgb(27, 27, 27))
    val negative = srgb(mix(soft, ink, 0.35))
    val points = series.withIndex().filter { it.value.second < 0 }.joinToString("") { (i, _) ->
        "<c:dPt><c:idx val=\"$i\"/><c:invertIfNegative val=\"0\"/><c:bubble3D val=\"0\"/>" +
            "<c:spPr>${solidFill(negative)}<a:ln><a:noFill/></a:ln></c:spPr></c:dPt>"
    }
    val direction = if (block.kind == ChartKind.HORIZONTAL_BAR) "bar" else "col"
    return "<c:barChart>" +
        "<c:barDir val=\"$direction\"/>" +
        "<c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>" +
        "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>$tx" +
        "<c:spPr>${solidFill(srgb(soft))}<a:ln><a:noFill/></a:ln></c:spPr>" +
        "<c:invertIfNegative val=\"0\"/>" +
        points +
        dataLabels(theme, "outEnd", showValue = true, showPercent = false,
            numberFormat = INDIAN_NUMBER_FORMAT) +
        cat + value + "</c:ser>" +
        "<c:gapWidth val=\"61\"/>" +
        "<c:axId val=\"$CAT_AX_ID\"/><c:axId val=\"$VAL_AX_ID\"/>" +
        "</c:barChart>"
}

private fun lineGroup(theme: ReportTheme, tx: String, cat: String, value: String): String {
    val accent = srgb(rgbOf(theme.accent, rgb(31, 56, 100)))
    return "<c:lineChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>" +
        "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>$tx" +
        "<c:spPr><a:ln w=\"12700\" cap=\"rnd\">${solidFill(accent)}<a:round/></a:ln></c:spPr>" +
        "<c:marker><c:symbol val=\"circle\"/><c:size val=\"6\"/>" +
        "<c:spPr>${solidFill(accent)}" +
        "<a:ln w=\"9525\">${solidFill("FFFFFF")}</a:ln></c:spPr></c:marker>" +
        dataLabels(theme, "t", showValue = true, showPercent = false,
            numberFormat = INDIAN_NUMBER_FORMAT) +
        cat + value + "<c:smooth val=\"0\"/></c:ser>" +
        "<c:marker val=\"1\"/>" +
        "<c:axId val=\"$CAT_AX_ID\"/><c:axId val=\"$VAL_AX_ID\"/>" +
        "</c:lineChart>"
}

private fun circularGroup(
    block: ChartBlock,
    series: List<Pair<String, Double>>,
    theme: ReportTheme,
    tx: String,
    cat: String,
    value: String,
): String {
    val colours = sliceColours(series.size, theme)
    val points = colours.withIndex().joinToString("") { (i, colour) ->
        "<c:dPt><c:idx val=\"$i\"/><c:bubble3D val=\"0\"/>" +
            "<c:spPr>${solidFill(srgb(colour))}" +
            "<a:ln w=\"19050\">${solidFill("FFFFFF")}</a:ln></c:spPr></c:dPt>"
    }
    val donut = block.kind == ChartKind.DONUT
    val tag = if (donut) "doughnutChart" else "pieChart"
    val tail = "<c:firstSliceAng val=\"0\"/>" + if (donut) "<c:holeSize val=\"55\"/>" else ""
    return "<c:$tag><c:varyColors val=\"1\"/>" +
        "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>$tx$points" +
        dataLabels(theme, null, showValue = false, showPercent = true, numberFormat = "0%") +
        cat + value + "</c:ser>$tail</c:$tag>"
}

internal fun chartSpaceXml(
    block: ChartBlock,
    series: List<Pair<String, Double>>,
    theme: ReportTheme,
): String {
    val labels = series.map { it.first }
    val values = series.map { it.second }
    val last = series.size + 1
    val name = cleanText(block.title).ifEmpty { "Value" }

    val tx = "<c:tx>" + strRef("Sheet1!\$B\$1", listOf(name)) + "</c:tx>"
    val cat = "<c:cat>" + strRef("Sheet1!\$A\$2:\$A\$$last", labels) + "</c:cat>"
    val value = "<c:val>" + numRef("Sheet1!\$B\$2:\$B\$$last", values) + "</c:val>"

    val group: String
    val axes: String
    val legend: String
    if (block.kind.isCircular) {
        group = circularGroup(block, series, theme, tx, cat, value)
        axes = ""
        legend = "<c:legend><c:legendPos val=\"r\"/><c:overlay val=\"0\"/>" +
            "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>" +
            chartTxPr(theme, 8.5f, theme.muted) +
            "</c:legend>"
    } else {
        group = if (block.kind == ChartKind.LINE) {
            lineGroup(theme, tx, cat, value)
        } else {
            barGroup(block, series, theme, tx, cat, value)
        }
        axes = cartesianAxes(block, theme)
        legend = ""
    }

    return XML_DECL +
        "<c:chartSpace xmlns:c=\"$NS_C\" xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\">" +
        "<c:roundedCorners val=\"0\"/>" +
        "<c:chart>" +
        "<c:autoTitleDeleted val=\"1\"/>" +
        "<c:plotArea><c:layout/>$group$axes" +
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>" +
        "</c:plotArea>" +
        legend +
        "<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/>" +
        "</c:chart>" +
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>" +
        chartTxPr(theme, 8.5f, theme.muted) +
        "<c:externalData r:id=\"$WORKBOOK_RID\"><c:autoUpdate val=\"0\"/></c:externalData>" +
        "</c:chartSpace>"
}

internal fun chartWorkbookXlsx(block: ChartBlock, series: List<Pair<String, Double>>): ByteArray {
    val name = cleanText(block.title).ifEmpty { "Value" }

    fun inline(ref: String, text: String): String =
        "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"

    val rows = StringBuilder("<row r=\"1\">${inline("B1", name)}</row>")
    for ((index, entry) in series.withIndex()) {
        val r = index + 2
        rows.append("<row r=\"$r\">${inline("A$r", cleanText(entry.first))}")
        rows.append("<c r=\"B$r\"><v>${cellNumber(entry.second)}</v></c></row>")
    }

    val sheet = XML_DECL + "<worksheet xmlns=\"$NS_SS\">" +
        "<dimension ref=\"A1:B${series.size + 1}\"/>" +
        "<sheetData>$rows</sheetData></worksheet>"
    val workbook = XML_DECL + "<workbook xmlns=\"$NS_SS\" xmlns:r=\"$NS_R\">" +
        "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
    val workbookRels = XML_DECL + "<Relationships xmlns=\"$NS_PKG_REL\">" +
        "<Relationship Id=\"rId1\" Type=\"$NS_R/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$NS_R/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"
    val rootRels = XML_DECL + "<Relationships xmlns=\"$NS_PKG_REL\">" +
        "<Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"
    val styles = XML_DECL + "<styleSheet xmlns=\"$NS_SS\">" +
        "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"1\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
        "<cellStyles count=\"1\">" +
        "<cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"
    val contentTypes = XML_DECL + "<Types xmlns=\"$NS_CT\">" +
        "<Default Extension=\"rels\" " +
        "ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-" +
        "officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd." +
        "openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-" +
        "officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    val out = ByteArrayOutputStream()
    val zip = ZipOutputStream(out)
    for (
        (part, text) in listOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rootRels,
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to workbookRels,
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet,
        )
    ) {
        val entry = ZipEntry(part)
        entry.time = ZIP_EPOCH_MILLIS
        zip.putNextEntry(entry)
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    zip.finish()
    zip.close()
    return out.toByteArray()
}

internal fun chartPartRels(workbookName: String): String =
    XML_DECL + "<Relationships xmlns=\"$NS_PKG_REL\">" +
        "<Relationship Id=\"$WORKBOOK_RID\" Type=\"$NS_R/package\" " +
        "Target=\"../embeddings/${esc(workbookName)}\"/>" +
        "</Relationships>"

fun renderDocx(document: ReportDocument, loadImage: ImageLoader, out: OutputStream): List<String> {
    val writer = DocxWriter(document, loadImage)
    writer.writeTo(out)
    return writer.droppedImages.toList()
}
