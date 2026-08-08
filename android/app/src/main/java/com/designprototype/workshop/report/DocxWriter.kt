package com.designprototype.workshop.report

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a [ReportDocument] to a .docx, with no dependency beyond `java.util.zip` — a line-for-line
 * port of `backend/app/services/report_docx.py`.
 *
 * A .docx is a zip of XML parts, so this class needs nothing but [ZipOutputStream], which is already
 * a proven path in this app (`WorkshopRepository.downloadDataFolder` builds the dataset zip with
 * it). That is not a stunt — it is why the *identical* algorithm runs on the phone and on the
 * server. Keeping the server side dependency-free is what makes the two provably the same document
 * rather than two libraries' interpretations of one intent; python-docx would have made the server
 * file shorter and this one impossible.
 *
 * Parts written, in the order Word likes to find them:
 *
 *     [Content_Types].xml          every extension and every part override
 *     _rels/.rels                  package -> document, core properties, app properties
 *     word/document.xml            the body, ending in the one sectPr
 *     word/_rels/document.xml.rels styles, numbering, settings, header, footer, images, charts
 *     word/styles.xml              the theme rendered as real Word styles
 *     word/numbering.xml           bullet and ordered-list definitions
 *     word/settings.xml            updateFields, so the TOC fills itself in on open
 *     word/header1.xml             running head, suppressed on the cover by w:titlePg
 *     word/footer1.xml             running foot with PAGE / NUMPAGES fields
 *     word/media/image*.ext        one part per DISTINCT image
 *     word/charts/chart*.xml       one c:chartSpace per NATIVE chart
 *     word/charts/_rels/…rels      each chart -> its embedded workbook
 *     word/embeddings/chart*.xlsx  the numbers behind each native chart, as a real workbook
 *     docProps/core.xml            title, author, timestamps
 *     docProps/app.xml             producer
 *
 * Three things in here are not obvious and each one cost a corrupt file to learn:
 *
 * **Every string goes through [esc].** [cleanText] has already removed the codepoints XML cannot
 * carry; [esc] then escapes the five entities. Skipping the first makes an unopenable file, skipping
 * the second makes a mis-parsed one, and Word reports both as "The file is corrupt and cannot be
 * opened" with no indication which.
 *
 * **A table must be followed by a paragraph.** Two `w:tbl` siblings with nothing between them are
 * merged by Word into one table with the second's rows appended, silently. Every table this class
 * writes is followed by an empty `w:p`, and [validateBody] refuses to hand back a package where that
 * is not true.
 *
 * **Relationship ids are allocated, never guessed.** `rId1`-`rId5` are the five fixed parts; images
 * take `rId6` upward in first-use order. An earlier version numbered images from their index in the
 * block tree, so a document whose cover carried a logo produced two relationships with the same id
 * and Word dropped both pictures without complaint.
 *
 * `backend/tests/test_report_docx.py` opens the produced package, parses every part, and asserts
 * that every `r:embed` resolves — the same checks that caught all three of the above, and the
 * invariants this port has to keep.
 */

const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

// Word's unit zoo. A twip is 1/20 pt = 1/1440 in; an EMU is 1/914400 in. Both are integers in the
// file, so every conversion below rounds once, at the end, rather than accumulating.
private const val TWIP_PER_MM = 1440.0f / 25.4f
private const val EMU_PER_MM = 36000.0f

private val ALIGN_TO_JC = mapOf(
    Align.LEFT to "left",
    Align.CENTER to "center",
    Align.RIGHT to "right",
    Align.JUSTIFY to "both",
)

// The five relationship ids used by the fixed parts. Images start after them.
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

/**
 * Escape the five XML entities. [cleanText] has already run; this is the second half of the door.
 *
 * The `&` replacement MUST come first. Reversed, the `&` this function itself introduces for `<`
 * would be escaped a second time and every angle bracket in the report would print as `&amp;lt;`.
 */
private fun esc(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

// --------------------------------------------------------------------------------------
// Image probing — intrinsic size when the model was not told one
// --------------------------------------------------------------------------------------

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

/**
 * The start-of-frame markers whose payload carries the image dimensions: SOF0-SOF15 with C4 (DHT),
 * C8 (JPG) and CC (DAC) removed. Those three sit inside the same numeric run but are not frame
 * headers, and reading a Huffman table as if it were one yields a plausible-looking but wrong size —
 * which then silently sets the aspect ratio every photo in the report is drawn at.
 */
private val SOF_MARKERS = setOf(
    0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
)

/**
 * Walk the JPEG marker chain to the first SOF and read its frame size.
 *
 * Handing the bytes to `BitmapFactory` with `inJustDecodeBounds` would be two lines and is what
 * [PdfWriter] does — but this writer must produce byte-identical geometry to the server's, whose
 * Python has no Pillow in the core install and therefore does exactly this loop. Two different
 * probes are two chances to disagree about a photo's aspect ratio in the same report.
 */
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
        // Standalone markers carry no length word.
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
            i += 2
            continue
        }
        if (marker == 0xD9) return null // EOI
        if (i + 4 > n) return null
        val segLen = be16(data, i + 2)
        // SOF0-SOF15, excluding DHT (C4), DAC (CC) and the restart markers.
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

/** Intrinsic pixel size of a PNG or JPEG, or `null` when it is neither. */
fun probeImageSize(data: ByteArray): Pair<Int, Int>? = pngSize(data) ?: jpegSize(data)

private val MIME_TO_EXT = mapOf("image/png" to "png", "image/jpeg" to "jpeg")

// --------------------------------------------------------------------------------------
// Runs and paragraphs
// --------------------------------------------------------------------------------------

/**
 * The `w:rPr` for one run, including the complex-script font for Indic text.
 *
 * `w:cs` is the only lever a .docx has over shaping: Word picks the complex-script face for a run
 * tagged `w:cs`, and Nirmala UI ships with Windows and covers every Indic block this app captures. A
 * Latin run must NOT carry it, or Word uses Nirmala's Latin glyphs and the report changes typeface
 * mid-sentence.
 */
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
        // w:val IS MANDATORY on w:u. The schema types it as a required ST_Underline, and Word's
        // response to a bare <w:u/> is not to underline with a default style and not to report an
        // error: it discards the ENTIRE run's formatting — the bold and the colour go with it — so the
        // symptom is a paragraph that lost its emphasis rather than one that lost its underline, and
        // nobody looks at the underline code. "single" is the value the server writes.
        parts.append("<w:u w:val=\"single\"/>")
    }
    if (run.strike) parts.append("<w:strike/>")
    if (run.superscript || run.subscript) {
        // ONE element with two values, which is why the two marks cannot both apply:
        // CT_VerticalAlignRun takes baseline|superscript|subscript and emitting the element twice is
        // schema-invalid. [Run] is already single-valued here — `runsFor` resolves a span carrying
        // both in favour of superscript — so this reads the flags rather than choosing between them.
        val value = if (run.superscript) "superscript" else "subscript"
        parts.append("<w:vertAlign w:val=\"$value\"/>")
    }
    if (run.highlight) {
        // A NAME, not a colour: ST_HighlightColor is a closed enumeration and Word refuses an RGB
        // value here — which is exactly why [Run.highlight] is a boolean and why [PdfWriter] fills
        // [HIGHLIGHT_FILL], the hex Word draws for this name.
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

/** One `w:r`. A run's internal newlines become `w:br` so soft wraps survive. */
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

/**
 * Assemble one `w:p`. Every paragraph in this file is built here.
 *
 * `w:pPr` children are order-sensitive in the schema — pStyle, keepNext, numPr, pBdr, shd, spacing,
 * ind, jc, outlineLvl — and Word rejects the part outright if they are shuffled. Centralising the
 * assembly is what keeps that order in one place instead of in fourteen call sites.
 */
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

/** Open/close pair for a heading bookmark, so a TOC row can hyperlink to it. */
private fun bookmark(name: String, ordinal: Int): Pair<String, String> {
    if (name.isEmpty()) return "" to ""
    return "<w:bookmarkStart w:id=\"$ordinal\" w:name=\"${esc(name)}\"/>" to
        "<w:bookmarkEnd w:id=\"$ordinal\"/>"
}

private fun pageBreak(): String = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"

/** A simple Word field (PAGE, NUMPAGES). The literal between separate/end is the fallback. */
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
    // A cell whose content is empty is invalid: w:tc must contain at least one block-level element.
    // Word "repairs" it by dropping the whole row.
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
    // CT_TblPr IS A SEQUENCE, not a set: tblW, jc, tblCellSpacing, tblInd, tblBorders, shd,
    // tblLayout, tblCellMar, tblLook. Borders must precede layout. Emit them the other way round
    // and the part is schema-invalid; Word's only report of that is "the file is corrupt".
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

// --------------------------------------------------------------------------------------
// The writer
// --------------------------------------------------------------------------------------

/** Turns one [ReportDocument] into one .docx. */
class DocxWriter(
    private val doc: ReportDocument,
    private val loadImage: ImageLoader,
) {
    private val theme = doc.theme
    private val body = ArrayList<String>()

    /** One embedded media part: its file name inside `word/media/`, its bytes, mime and rId. */
    private class MediaPart(
        val name: String,
        val data: ByteArray,
        val mime: String,
        val rid: Int,
        val probedWidth: Int,
        val probedHeight: Int,
    )

    private val media = ArrayList<MediaPart>()
    private val ridBySource = HashMap<String, Int>()
    private var drawingSeq = 0
    private var bookmarkSeq = 0

    /**
     * Pictures this writer MADE rather than loaded: the rasterised map and every chart.
     *
     * They go through the ordinary image path from here on — one media part, one relationship, one
     * `w:drawing` — which is the whole reason a [MapBlock] rasterises instead of being drawn in
     * DrawingML. What they cannot go through is [loadImage]: that resolves a media id against the
     * device's local copy of a photograph, and a figure has no media id and never will. So
     * [registerImage] consults this table first.
     */
    private val figures = HashMap<String, ByteArray>()
    private var figureSeq = 0

    /**
     * Native charts, in emission order: the `c:chartSpace` XML and its embedded workbook.
     *
     * The list index is the part number, so charts[0] is `word/charts/chart1.xml` and its workbook
     * is `word/embeddings/chart1.xlsx` — one counter for three part names and one relationship, so
     * they cannot come apart.
     */
    private val charts = ArrayList<Pair<String, ByteArray>>()

    /** Charts that could not be expressed natively and fell back to the PNG. Test seam. */
    internal val rasterisedCharts = ArrayList<String>()

    /** Sources that could not be embedded, for the caller to surface as export warnings. */
    val droppedImages = ArrayList<String>()

    private val pageWMm: Float
    private val pageHMm: Float
    private val marginMm: Float

    /** The text column: EVERY relative width in the model is a fraction of THIS. */
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

    // -- images ----------------------------------------------------------------------

    /** rId plus the DISPLAY pixel size of an embedded image, or null when it cannot be used. */
    private class Registered(val rid: Int, val widthPx: Int, val heightPx: Int)

    /**
     * Embed [image] once and return its relationship id and display size.
     *
     * The size returned is the *display* size, i.e. after the EXIF quarter turn, preferring what the
     * model was told and falling back to probing the bytes. A phone photo whose model-side
     * dimensions were never populated still lays out at the right aspect ratio.
     */
    private fun registerImage(image: ImageRef): Registered? {
        val cached = ridBySource[image.source]
        if (cached != null) {
            val part = media.first { it.rid == cached }
            val (w, h) = displaySize(image, part.probedWidth to part.probedHeight)
            return Registered(cached, w, h)
        }

        // Figures first, photographs second. A rasterised map is already bytes in this process; asking
        // the loader for "figure:1" would resolve nothing and drop the map from the report.
        val data = figures[image.source] ?: loadImage(image)
        if (data == null || data.isEmpty()) {
            droppedImages.add(image.source)
            return null
        }

        val probed = probeImageSize(data)
        if (probed == null) {
            // Not a PNG or JPEG at all. Embedding it would produce a part Word cannot decode and a
            // red X in the document, which reads as a bug in the app rather than in the file that
            // was uploaded.
            droppedImages.add(image.source)
            return null
        }

        // TRUST THE MAGIC BYTES OVER THE DECLARED MIME. The media table has held "image/jpeg" for
        // PNG bytes ever since the client started guessing the type from the file extension, and a
        // .jpeg part holding PNG bytes is one Word cannot decode. Only these two types are ever
        // emitted: Word renders neither WEBP nor HEIC inside a document part on any version still
        // deployed in a government office, and a picture that silently does not appear is worse
        // than one that was never claimed. probeImageSize() above has already refused anything else.
        val mime = if (looksLikePng(data)) "image/png" else "image/jpeg"
        val rid = RID_FIRST_IMAGE + media.size
        val ext = MIME_TO_EXT.getValue(mime)
        media.add(
            MediaPart("image${media.size + 1}.$ext", data, mime, rid, probed.first, probed.second)
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

    /** One inline picture, fitted into `widthMm` x `maxHeightMm` keeping aspect. */
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

        // THE PIXELS MUST BE ROTATED, NOT ONLY THE FRAME. (Ported from report_docx.py, which
        // carries the full note; keep the two in step.)
        //
        // The box above is already sized for the DISPLAYED photo — displaySize swapped the
        // intrinsic width and height for a quarter turn — but the bytes in the media part are
        // still the camera's unrotated frame, and a .docx draws them into the box as they are.
        // A portrait photograph taken on a phone therefore arrived in a landscape frame with
        // portrait pixels stretched across it, which on this surface is EVERY photo the
        // designer takes holding the phone upright.
        //
        // DrawingML rotates about the shape's centre; its a:ext is the UNROTATED extent while
        // wp:extent is the bounding box the rotated result occupies in the flow. For a quarter
        // turn the two are the same numbers swapped, and getting the pair the wrong way round
        // renders the picture rotated but cropped to the wrong box.
        val rotation = if (image.rotationDeg != 0) ((image.rotationDeg % 360) + 360) % 360 else 0
        val extCx = if (rotation == 90 || rotation == 270) cy else cx
        val extCy = if (rotation == 90 || rotation == 270) cx else cy
        // DrawingML angles are in sixtieth-thousandths of a degree, clockwise.
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

    // -- blocks ----------------------------------------------------------------------

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
        // A TOC field, not a rendered list. Word paginates it on open because settings.xml carries
        // w:updateFields; the literal run between separate/end is the placeholder a reader sees if
        // they decline the refresh, so it has to be an instruction, not "1".
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
        // (pStyle, size, colour, left indent in twips) per role.
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

    /**
     * A borderless two- (or four-) column grid.
     *
     * Rendered as a table rather than tab-separated runs so a long value wraps within its own column
     * instead of pushing the following pair onto the next line.
     */
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
            // Pad a short final chunk: a ragged row makes Word render the table with a missing
            // column, which looks like the value was lost rather than absent.
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
            // tblHeader repeats the row on every page a long table spills onto; cantSplit stops Word
            // breaking the header itself across the page boundary.
            // cantSplit BEFORE tblHeader: CT_TrPr is a SEQUENCE, not a set. Reversed, the part is
            // schema-invalid even though Word has historically tolerated it.
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
        // MANDATORY: two adjacent w:tbl merge silently. validateBody() enforces it.
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

    /** A photo grid laid out as a borderless table, one picture + caption per cell. */
    private fun emitImageGrid(block: ImageGridBlock) {
        if (block.images.isEmpty()) return
        val t = theme
        // Below ~45 mm a photo stops being evidence and becomes a thumbnail, so drop columns rather
        // than shrink past that. PdfWriter applies the identical rule, which is what makes the two
        // exports of one report show the same grid.
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

    // -- figures: the map and the infographics ----------------------------------------

    /**
     * The printed width of a figure, in millimetres.
     *
     * Computed in Double even though [MapBlock.widthPct] is a Float, because the result is fed to
     * [pixelsForMm], whose rounding decides the PNG's pixel width — and a figure one pixel wider than
     * the server's is a different picture with every label inside it in a different place. Widening a
     * Float at the multiplication rather than after it is what keeps the arithmetic the server's.
     */
    private fun figureWidthMm(widthPct: Float): Double =
        textWMm.toDouble() * widthPct.toDouble().coerceIn(20.0, 100.0) / 100.0

    /**
     * Place a rasterised figure through the ORDINARY picture path.
     *
     * This function is short and must stay short. A [MapBlock] and a [ChartBlock] are DESCRIPTIONS,
     * not pictures; [ReportMap] and [ReportChart] turn one into a PNG and everything from here on is
     * the same media part, the same relationship and the same `w:drawing` a photograph gets. Drawing
     * either of them in DrawingML instead would be a second renderer for a picture the server already
     * knows how to produce, and the two would disagree.
     *
     * Title and caption are emitted as REAL TEXT rather than drawn into the bitmap, and that is not a
     * stylistic choice: [Raster]'s five-by-seven face is ASCII only, so a caption naming a craft in
     * Odia would be silently dropped inside the picture, while a Word run carrying `w:cs="Nirmala UI"`
     * prints it. It also means a caption is searchable.
     */
    private fun emitFigure(
        figure: RasterFigure,
        widthPct: Float,
        align: Align,
        title: String,
        caption: String,
    ) {
        figureSeq += 1
        val source = "figure:$figureSeq"
        // Registered where registerImage will find it, NOT handed to loadImage. That resolves a media
        // id against local storage, and a figure has no media id and never will. A synthetic source
        // must not appear in ReportDocument.images either, or the caller would try to prefetch it and
        // report the miss to the designer as a photograph that could not be included; collectImages
        // is where that is guaranteed, because neither block carries an ImageRef at all.
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

    /**
     * The figure's heading line, as real text above whatever draws the figure.
     *
     * Shared by the rasterised path and the native-chart path so the two produce the SAME paragraph.
     * A native chart that titled itself inside the frame would sit differently on the page from the
     * map above it, and the two figures would stop looking like one report.
     */
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
            // The boundary assets are not on this device. Recorded exactly as a photograph that failed
            // to load is, so the caller can tell the designer, and NOT thrown: a workshop report
            // without its locator map is still the report they asked for.
            droppedImages.add("map:india")
            return
        }
        emitFigure(figure, block.widthPct, block.align, block.title, block.caption)
    }

    /**
     * A statistical figure, natively if Word has a form for it and as a picture if not.
     *
     * THE ORDER OF THESE TWO BRANCHES IS THE FEATURE. A native chart is better in every way that
     * matters to the reader — it enlarges without breaking up, it can be restyled, it prints an Odia
     * category the rasteriser would have dropped, and double-clicking it shows the numbers. But
     * "better" is not "always possible", and the only unacceptable outcome is a figure the template
     * asked for that is not in the document at all. So the native path is tried, and anything it
     * declines falls through to the picture path that has always worked.
     */
    private fun emitChart(block: ChartBlock) {
        val (series, notes) = cleanSeries(block)
        if (emitNativeChart(block, series)) {
            // The rasteriser prints these inside the PNG. A native chart has no such corner, and a
            // dropped category that goes unmentioned is a figure quietly missing a cost head.
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

    /**
     * Emit [block] as a real `c:chart`. Returns false when it must stay a raster.
     *
     * The one refusal is an EMPTY series, and it is a refusal on purpose. [renderChartPng] draws
     * that case as a framed panel reading "No values recorded.", which is a statement about the
     * record — an omitted figure looks like a rendering fault. A `c:chart` with no points draws an
     * empty plot area and says nothing, so for that one input the picture is the better document and
     * the fallback is not a degradation but the correct answer.
     */
    private fun emitNativeChart(block: ChartBlock, series: List<Pair<String, Double>>): Boolean {
        if (series.isEmpty()) {
            rasterisedCharts.add(block.title.ifEmpty { block.kind.name })
            return false
        }

        var wMm = figureWidthMm(block.widthPct)
        // The native chart has no bitmap and therefore no intrinsic size; its frame is whatever the
        // drawing says. Taking the aspect from the rasteriser's own box is what keeps a cost chart
        // the same SHAPE in the .docx and the .pdf of one workshop.
        val (pxW, pxH) = chartPixelBox(block, pixelsForMm(wMm), series.size)
        var hMm = if (pxW != 0) wMm * pxH / pxW else wMm * 0.62
        if (block.kind == ChartKind.HORIZONTAL_BAR) {
            hMm = max(hMm, NATIVE_CHROME_MM + NATIVE_ROW_MM * series.size)
        }
        val maxHMm = textHMm * 0.58
        if (hMm > maxHMm) {
            // Shrink the width with it. Clamping height alone squashes a six-head cost chart into a
            // strip, which is the same figure telling a different story.
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

    /**
     * The `w:drawing` that places a chart part.
     *
     * Nearly [drawingXml], and different in the three ways that matter: the graphicData uri is the
     * CHART namespace rather than the picture one, the payload is a bare `c:chart` reference instead
     * of a `pic:pic` tree, and there is no `a:graphicFrameLocks noChangeAspect` — a chart is meant to
     * be resized freely, and locking its aspect is what stops a reader fitting it to their column.
     */
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

    // -- assembly --------------------------------------------------------------------

    /**
     * No `else` branch, on purpose. [Block] is sealed, so a block type added to the model without a
     * renderer here fails to COMPILE — which is the only place that failure can be cheap. The
     * server's Python equivalent raises at render time, i.e. in the field.
     */
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

    /**
     * Refuse to emit a body Word would silently mangle.
     *
     * Only one rule so far, and it is the one that actually happened: two `w:tbl` siblings merge into
     * a single table, with the second's rows appended to the first, and Word reports nothing at all.
     * Throwing here turns a subtly wrong report into a failed export naming the body index that did
     * it — a bug report instead of a cost sheet whose totals row has ten extra lines under it.
     */
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

    /** Test seam: lets a check defeat the trailing paragraph and prove [validateBody] is live. */
    internal fun bodyForTest(): MutableList<String> = body

    /**
     * Write the whole package into [out].
     *
     * Streams rather than returning a `ByteArray` because a 60-photo report is tens of megabytes and
     * this runs on a phone: the media parts are already in memory once (they must be, to be
     * deflated), and buffering the finished zip as well would double that for no reason. The stream
     * is finished but NOT closed — the caller owns it, and closing a caller's `FileOutputStream`
     * before it has had a chance to `fd.sync()` is how a temp file gets published half-written.
     */
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
            zip.putNextEntry(ZipEntry("word/media/${part.name}"))
            zip.write(part.data)
            zip.closeEntry()
        }
        // Three parts per native chart, and all three or none. A chart part whose workbook
        // relationship dangles opens as a chart with a broken "Edit Data"; a workbook with no chart
        // part is dead weight the reader never sees.
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

    /** The whole package as bytes. Only for tests and small documents; prefer [writeTo]. */
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
            // The embedded workbooks. A Default rather than an Override each, because every one of
            // them is the same type and Word resolves the extension the same way.
            extensions["xlsx"] = XLSX_MIME
        }
        val defaults = extensions.entries.joinToString("") { (ext, ct) ->
            "<Default Extension=\"$ext\" ContentType=\"$ct\"/>"
        }
        val wml = "application/vnd.openxmlformats-officedocument.wordprocessingml"
        val overrides = listOf(
            // NOT the .docx file mime. That is the content type of the .docx *file*; the main
            // document *part* inside it is ".document.main+xml". Declaring the package type here
            // produces a package that passes every structural check — all parts parse, all
            // relationships resolve, the zip verifies — and that Word refuses with a bare "the file
            // appears to be corrupted" and no clue which part is at fault. Every entry below is
            // likewise the PART type, not a file type.
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
            // A chart part ends in .xml, so the Default above already gives it "application/xml" —
            // and a chart declared as generic XML is one Word ignores completely, leaving a
            // correctly sized blank rectangle where the figure should be. The Override names it.
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
            // The cover carries no running head or foot. Without titlePg the header prints over the
            // ministry line and the footer numbers the cover as page 1 of the body.
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
            // CT_CoreProperties IS A SEQUENCE: category, contentStatus, created, creator,
            // description, identifier, keywords, language, lastModifiedBy, lastPrinted, modified,
            // revision, subject, title, version — alphabetical by LOCAL name, which is not the order
            // anyone writes these in by hand and not the order the namespace prefixes suggest.
            // Written out of order the part is schema-invalid and Word refuses the whole file.
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

// --------------------------------------------------------------------------------------
// Fixed parts
// --------------------------------------------------------------------------------------

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
        // Without this Word shows the TOC placeholder text until the reader knows to press F9.
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

/**
 * The theme rendered as real Word styles, so a reader can restyle the whole report.
 *
 * Emitting direct formatting on every run instead would look identical and be unusable: an officer
 * who wants the department's own heading colour would have to touch every heading.
 */
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

// --------------------------------------------------------------------------------------
// Native Word charts
// --------------------------------------------------------------------------------------
//
// A port of the section of the same name in `backend/app/services/report_docx.py`, and it has to
// stay one: a phone that wrote a picture where the server writes a chart would hand a designer in a
// field a different document from the one the office downloads for the same workshop.
//
// A figure in this report used to be a PNG in every case, and for the map it still is. For the
// statistical figures it is now a real `c:chart` part: vector, restylable, and carrying its own
// numbers in an embedded workbook that "Edit Data" opens. The reason is not polish. A ministry
// receiving this .docx pulls one chart into a presentation and enlarges it; a 200 dpi raster of a
// five-by-seven bitmap font at A3 is unusable, and the officer's alternative is to retype the
// numbers off the picture — which is how a figure and its source table start to disagree.
//
// FOUR PARTS AND TWO RELATIONSHIP HOPS, all of which have to be right together:
//
//     word/charts/chart1.xml            the c:chartSpace
//     word/charts/_rels/chart1.xml.rels chart -> workbook, relationship type ".../package"
//     word/embeddings/chart1.xlsx       the workbook, itself a zip of six XML parts
//     word/_rels/document.xml.rels      document -> chart, relationship type ".../chart"
//     [Content_Types].xml               an Override for the chart part, a Default for "xlsx"
//
// THE CACHE IS NOT OPTIONAL. c:cat and c:val each carry BOTH a reference into the workbook and a
// literal cache of the values. Word renders from the cache and only touches the workbook when the
// reader asks to edit. A chart written without the cache draws an empty plot area on every machine
// that has not opened the workbook — which is every machine, because Word does not open it on load.
//
// WHAT STAYS A RASTER. The map, always: it is a pinned projection, not a Word chart type. And any
// chart whose series is empty after [cleanSeries] — [renderChartPng] draws an explaining frame
// ("No values recorded.") for that case and a native chart cannot say anything at all, so the PNG
// is the better document. Degrading is correct; dropping the figure never is.

private const val NS_C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
private const val NS_SS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

private const val CHART_PART_TYPE =
    "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
private const val XLSX_MIME =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * Chart relationships are `cId1`, `cId2` … and NOT `rId`-anything.
 *
 * An Id in an OPC relationship part is an xsd:ID — any NCName will do, and Word has never cared that
 * its own writer happens to emit "rId". Using a disjoint prefix is what makes it *impossible* for a
 * chart to collide with an image, rather than merely unlikely: [DocxWriter.registerImage] derives an
 * image's id arithmetically from the media count, so any scheme that also counted charts into that
 * space would hand the same id to a picture and a chart the moment a chart was emitted between two
 * photographs. That is exactly the duplicate-id failure the class docstring describes, and its
 * symptom is silence — Word drops both parts and reports nothing.
 */
private const val CHART_RID_PREFIX = "cId"

/** Axis ids only have to be unique inside one chart part, and every chart here has one pair. */
private const val CAT_AX_ID = 111111111
private const val VAL_AX_ID = 222222222

/** The relationship id INSIDE a chart part, pointing at that chart's own workbook. */
private const val WORKBOOK_RID = "rId1"

/**
 * 12,34,567 rather than 1,234,567, as an Excel format code.
 *
 * The same grouping the report's cost tables apply to every money value and [formatNumber] prints on
 * the raster's axis. Without it Word's General format prints an eight-digit sanctioned amount as one
 * unbroken run of digits, directly beside a cost table that groups it — and an officer reconciling
 * the two has to count characters. Three conditional sections because Excel has no Indian grouping
 * primitive: crore, lakh, and everything else (which is also where negatives land, keeping sign).
 */
private const val INDIAN_NUMBER_FORMAT =
    "[>=10000000]##\\,##\\,##\\,##0.##;[>=100000]##\\,##\\,##0.##;##\\,##0.##"

/**
 * A fixed timestamp for every entry of an embedded workbook: 1980-01-02T12:00:00Z.
 *
 * [ZipOutputStream] stamps "now" by default, which makes two exports of one unchanged workshop
 * produce two different files. NOT the zip epoch itself, which is the obvious choice and the wrong
 * one: a zip stores DOS dates in LOCAL time and cannot represent anything before 1980-01-01, so on
 * a device west of Greenwich `java.util.zip` would clamp the epoch and stamp a different date from
 * the server's. Noon on the second day leaves twelve hours of slack either way.
 */
private const val ZIP_EPOCH_MILLIS = 315662400000L

/**
 * Vertical room a native horizontal-bar chart needs PER CATEGORY, and for its axis, in mm.
 *
 * The one place the native chart deliberately does NOT copy the rasteriser's box, and the reason is
 * type size. [Raster]'s five-by-seven face is about 1.4 mm tall printed at 200 dpi; Word's 8 pt is
 * 2.8 mm. So the height that fits six cost heads in the PNG fits three in a real chart, and Word's
 * response to labels that do not fit is to DROP them — silently, and starting from the ones in the
 * middle. A cost chart missing "Transport" between "Material" and "Labour" is not a smaller figure,
 * it is a wrong one, and nothing in the document says so.
 */
private const val NATIVE_ROW_MM = 6.5
private const val NATIVE_CHROME_MM = 14.0

private const val HEX_DIGITS = "0123456789ABCDEF"

/** `0x1F3864` -> `"1F3864"`. Built by hand rather than through `String.format`, which is
 *  locale-sensitive and would otherwise be one more thing that can differ from the server. */
private fun srgb(colour: Rgb): String {
    val value = colour and 0xFFFFFF
    val out = StringBuilder(6)
    for (shift in intArrayOf(20, 16, 12, 8, 4, 0)) out.append(HEX_DIGITS[(value shr shift) and 0xF])
    return out.toString()
}

/**
 * A number as a spreadsheet cell and a chart cache carry it: plain, never in exponent form.
 *
 * `12345678.0.toString()` is `"1.2345678E7"` in Kotlin, and a `c:v` holding `1.2345678E7` is read by
 * Word as TEXT — the point vanishes from the plot with no error anywhere. This is the single most
 * likely way this port could produce a chart that looks right on the server and is empty on the
 * phone, because Python's `str()` does not switch to exponent notation until far later.
 */
private fun cellNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) {
        // cleanSeries has already dropped these; reaching here means a caller went round it.
        return "0"
    }
    val whole = value.toLong()
    if (value == whole.toDouble() && value > -1e15 && value < 1e15) return whole.toString()
    val text = BigDecimal(value).setScale(6, RoundingMode.HALF_UP).toPlainString()
    return text.trimEnd('0').trimEnd('.')
}

/**
 * Default run properties for every string a chart part draws.
 *
 * `a:cs` is here for exactly the reason `w:cs` is on a body run: it is the only lever DrawingML has
 * over shaping, and a cost head or a craft name in Odia renders as boxes without it. This is the one
 * place the native chart is strictly BETTER than the PNG it replaces — [Raster]'s five-by-seven face
 * is ASCII only and drops an Indic label in silence, so a category called "ସମ୍ବଲପୁରୀ" was previously
 * an EMPTY slot under a bar.
 */
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

/** A `c:strRef` — the workbook range AND the literal cache Word actually renders from. */
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

/**
 * One `c:dLbls`.
 *
 * CT_DLbls is a SEQUENCE and Word rejects the part outright if it is shuffled: numFmt, spPr, txPr,
 * dLblPos, then the six show-flags in their fixed order. [position] is null for the circular kinds
 * on purpose — a doughnut has no legal value for `dLblPos`, and Word refuses to open a chart part
 * that gives it one.
 */
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

/**
 * The unit, as a real axis title.
 *
 * [ChartBlock.unit] is printed once beside the axis and never on every value — the model says so.
 * The rasteriser draws it in a corner because it has nowhere better; a native chart has the place
 * the schema made for it.
 */
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

/**
 * The `c:catAx` / `c:valAx` pair shared by the bar and line kinds.
 *
 * CT_CatAx and CT_ValAx are sequences too, and the two differ in their tails: a category axis ends
 * with auto/lblAlgn/lblOffset/noMultiLvlLbl, a value axis with crossBetween. Writing one with the
 * other's tail produces a part Word silently declines to draw, leaving a correctly sized EMPTY frame
 * in the document — the failure that looks most like "the chart is missing".
 */
private fun cartesianAxes(block: ChartBlock, theme: ReportTheme): String {
    val horizontal = block.kind == ChartKind.HORIZONTAL_BAR
    val catPos = if (horizontal) "l" else "b"
    val valPos = if (horizontal) "b" else "l"
    // A horizontal bar chart must run its first category along the TOP, because that is where the
    // rasterised version puts it and because a cost sheet reads downward from its largest head.
    // Word's default for a bar chart is bottom-up, which silently reverses every figure.
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
        // midCat puts a line's first point ON the axis rather than half a slot in, which is where
        // the rasteriser draws it and where a follow-up at three months belongs.
        "<c:crossBetween val=\"$crossBetween\"/>" +
        "</c:valAx>"
}

/**
 * `c:barChart` — vertical when the categories are a sequence, horizontal when they are words.
 *
 * CT_BarSer sequence: idx, order, tx, spPr, invertIfNegative, dPt*, dLbls, cat, val.
 */
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
    // A negative bar is darkened rather than recoloured, exactly as the rasteriser darkens it. A
    // credited cost line is still that cost head, not a new category, and a second hue would say
    // otherwise. invertIfNegative is Word's own lever for this and it forces a PATTERN fill, which
    // photocopies to noise — hence per-point overrides instead.
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
        // 61%: the rasteriser draws a bar at 0.62 of its slot, so the gap is the other 0.38 —
        // 0.38/0.62 as a percentage of bar width, which is what gapWidth means.
        "<c:gapWidth val=\"61\"/>" +
        "<c:axId val=\"$CAT_AX_ID\"/><c:axId val=\"$VAL_AX_ID\"/>" +
        "</c:barChart>"
}

/** `c:lineChart`. CT_LineSer: idx, order, tx, spPr, marker, dPt*, dLbls, cat, val, smooth. */
private fun lineGroup(theme: ReportTheme, tx: String, cat: String, value: String): String {
    val accent = srgb(rgbOf(theme.accent, rgb(31, 56, 100)))
    return "<c:lineChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>" +
        "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>$tx" +
        // 12700 EMU = 1 pt, which is what the rasteriser's 2.4 px at 200 dpi comes to. A hairline
        // default would disappear on the photocopy every one of these reports takes.
        "<c:spPr><a:ln w=\"12700\" cap=\"rnd\">${solidFill(accent)}<a:round/></a:ln></c:spPr>" +
        "<c:marker><c:symbol val=\"circle\"/><c:size val=\"6\"/>" +
        "<c:spPr>${solidFill(accent)}" +
        "<a:ln w=\"9525\">${solidFill("FFFFFF")}</a:ln></c:spPr></c:marker>" +
        dataLabels(theme, "t", showValue = true, showPercent = false,
            numberFormat = INDIAN_NUMBER_FORMAT) +
        cat + value + "<c:smooth val=\"0\"/></c:ser>" +
        // A line of three follow-up points with no markers is a line nobody can read a value off.
        "<c:marker val=\"1\"/>" +
        "<c:axId val=\"$CAT_AX_ID\"/><c:axId val=\"$VAL_AX_ID\"/>" +
        "</c:lineChart>"
}

/**
 * `c:pieChart` or `c:doughnutChart`, one `c:dPt` per slice.
 *
 * Every slice is coloured explicitly from [sliceColours] rather than left to Word's theme palette,
 * and that is the photocopier argument again: Word's default categorical palette collapses to four
 * indistinguishable greys the first time this report is copied, and the legend then names four
 * slices a reader cannot tell apart. The monochrome lightness ramp survives the copy — and it is the
 * SAME ramp the .pdf of this workshop uses, which is the point.
 */
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
            // A white keyline between slices: without it two adjacent steps of a lightness ramp
            // read as one slice on a laser print.
            "<a:ln w=\"19050\">${solidFill("FFFFFF")}</a:ln></c:spPr></c:dPt>"
    }
    val donut = block.kind == ChartKind.DONUT
    val tag = if (donut) "doughnutChart" else "pieChart"
    // 55 matches the rasteriser's inner radius of 0.55 of the outer.
    val tail = "<c:firstSliceAng val=\"0\"/>" + if (donut) "<c:holeSize val=\"55\"/>" else ""
    return "<c:$tag><c:varyColors val=\"1\"/>" +
        "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>$tx$points" +
        // The share, not the count: the count is in the legend and in the table this figure sits
        // beside, and a slice labelled with both is unreadable at a quarter of a page.
        dataLabels(theme, null, showValue = false, showPercent = true, numberFormat = "0%") +
        cat + value + "</c:ser>$tail</c:$tag>"
}

/**
 * The whole `word/charts/chartN.xml` for one block.
 *
 * CT_ChartSpace is a sequence: roundedCorners, chart, spPr, txPr, externalData. CT_Chart is a
 * sequence too: autoTitleDeleted, plotArea, legend, plotVisOnly, dispBlanksAs. Both are checked by
 * `backend/tests/test_report_docx_chart.py`, which asserts the emission order rather than trusting
 * it — a shuffled sequence is schema-invalid and Word's only report of it is "we found a problem
 * with some content", offering to repair the document by deleting the chart.
 */
internal fun chartSpaceXml(
    block: ChartBlock,
    series: List<Pair<String, Double>>,
    theme: ReportTheme,
): String {
    val labels = series.map { it.first }
    val values = series.map { it.second }
    val last = series.size + 1        // row 1 is the header, so n categories end on row n+1
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
        // A pie without a legend is a ring of coloured wedges nobody can name. The rasteriser draws
        // its own legend for exactly this reason; here the schema has one.
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
        // One series has nothing to distinguish, so a legend would print the figure's own title a
        // second time under it.
        legend = ""
    }

    return XML_DECL +
        "<c:chartSpace xmlns:c=\"$NS_C\" xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\">" +
        "<c:roundedCorners val=\"0\"/>" +
        "<c:chart>" +
        // The block's title is emitted ABOVE the drawing as real Word text, by the same code that
        // titles the map — so it can carry Odia, so it is searchable, and so restyling the report
        // restyles it. Without autoTitleDeleted Word invents a second title from the series name
        // and prints it inside the frame, directly under the real one.
        "<c:autoTitleDeleted val=\"1\"/>" +
        "<c:plotArea><c:layout/>$group$axes" +
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>" +
        "</c:plotArea>" +
        legend +
        "<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/>" +
        "</c:chart>" +
        // No fill and no outline: the figure sits on the page like the picture it replaces, rather
        // than in the bordered box Word's default chart style draws.
        "<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>" +
        chartTxPr(theme, 8.5f, theme.muted) +
        "<c:externalData r:id=\"$WORKBOOK_RID\"><c:autoUpdate val=\"0\"/></c:externalData>" +
        "</c:chartSpace>"
}

/**
 * The embedded workbook for one chart: a real .xlsx, written by hand.
 *
 * Six parts is the whole of a valid spreadsheet package. Categories go down column A from row 2,
 * values down column B, and B1 carries the series name — which is the layout the `c:f` formulas in
 * [chartSpaceXml] reference, so the two must be changed together or "Edit Data" opens a sheet whose
 * ranges point at empty cells.
 *
 * Strings are written inline (`t="inlineStr"`) rather than through a shared-strings part. Sharing
 * pays for itself across thousands of repeated cells; a chart has at most a few dozen distinct
 * labels, and the part it saves is a seventh part to keep consistent on two platforms.
 */
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
    // The minimum styles part Excel will accept. It is optional in the schema and NOT optional in
    // practice: Excel repairs a workbook that references cell format 0 without defining it, and a
    // repair prompt on "Edit Data" reads as a corrupt report.
    val styles = XML_DECL + "<styleSheet xmlns=\"$NS_SS\">" +
        "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"1\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
        // The "Normal" named style. Without it every reader that checks decides the workbook has no
        // default style and substitutes its own, which is the kind of difference that turns into a
        // repair prompt on somebody else's build.
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

/**
 * `word/charts/chartN.xml.rels` — the one hop from a chart to its workbook.
 *
 * The relationship type is `.../package`, not `.../oleObject` and not `.../image`. A wrong type here
 * does not stop the chart drawing (it renders from its cache) but breaks "Edit Data" with a dialog
 * about a missing linked file, which is worse than no button at all.
 */
internal fun chartPartRels(workbookName: String): String =
    XML_DECL + "<Relationships xmlns=\"$NS_PKG_REL\">" +
        "<Relationship Id=\"$WORKBOOK_RID\" Type=\"$NS_R/package\" " +
        "Target=\"../embeddings/${esc(workbookName)}\"/>" +
        "</Relationships>"

// --------------------------------------------------------------------------------------
// Public entry point
// --------------------------------------------------------------------------------------

/**
 * Render [document] into [out] as a .docx and return the image sources that could not be embedded.
 *
 * Synchronous and CPU-bound. `ReportExport` runs it on `Dispatchers.IO`, which is also where the
 * file it writes into lives.
 */
fun renderDocx(document: ReportDocument, loadImage: ImageLoader, out: OutputStream): List<String> {
    val writer = DocxWriter(document, loadImage)
    writer.writeTo(out)
    return writer.droppedImages.toList()
}
