package com.designprototype.workshop.report

import kotlinx.serialization.Serializable

private fun isXmlLegal(cp: Int): Boolean =
    cp == 0x9 || cp == 0xA || cp == 0xD ||
        (cp in 0x20..0xD7FF) || (cp in 0xE000..0xFFFD) || (cp in 0x10000..0x10FFFF)

fun cleanText(value: Any?): String {
    if (value == null) return ""
    if (value is Boolean) return if (value) "Yes" else "No"
    val raw = value as? String ?: value.toString()
    if (raw.isEmpty()) return ""

    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
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
                if (isXmlLegal(Character.toCodePoint(c, raw[i + 1]))) {
                    out.append(c).append(raw[i + 1])
                }
                i += 2
                continue
            }
            i++
            continue
        }
        if (Character.isLowSurrogate(c)) {
            i++
            continue
        }
        if (isXmlLegal(c.code)) out.append(c)
        i++
    }
    return out.toString()
}

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

private val NEUTRAL_CATEGORIES: Set<Int> = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    Character.SPACE_SEPARATOR.toInt(),
    Character.LINE_SEPARATOR.toInt(),
    Character.PARAGRAPH_SEPARATOR.toInt(),
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.MATH_SYMBOL.toInt(),
    Character.MODIFIER_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(),
    Character.DECIMAL_DIGIT_NUMBER.toInt(),
    Character.OTHER_NUMBER.toInt(),
)

private fun scriptOrNeutral(cp: Int): Script? {
    for (range in SCRIPT_RANGES) {
        if (cp in range.lo..range.hi) return range.script
    }
    if (Character.getType(cp) in NEUTRAL_CATEGORIES) return null
    if (cp < 0x0370) return Script.LATIN
    return Script.OTHER
}

fun scriptOf(cp: Int): Script = scriptOrNeutral(cp) ?: Script.LATIN

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

    var carried: Script? = null
    for (k in resolved.indices) {
        val s = resolved[k]
        if (s != null) carried = s else resolved[k] = carried
    }

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

@Serializable
enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

@Serializable
enum class ParaStyle {
    BODY,
    LEAD,
    NOTE,
    QUOTE,
    CAPTION,
    COVER_LINE,
}

@Serializable
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val script: Script = Script.LATIN,
    val color: String? = null,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val highlight: Boolean = false,
)

const val HIGHLIGHT_FILL = "FFFF00"

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

fun runsText(runs: List<Run>): String = runs.joinToString("") { it.text }

@Serializable
data class ImageRef(
    val source: String,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val rotationDeg: Int = 0,
    val mimeType: String = "image/jpeg",
) {
    val displayWidthPx: Int get() = if (rotationDeg == 90 || rotationDeg == 270) heightPx else widthPx

    val displayHeightPx: Int get() = if (rotationDeg == 90 || rotationDeg == 270) widthPx else heightPx

    val aspect: Float
        get() {
            val w = displayWidthPx
            val h = displayHeightPx
            if (w <= 0 || h <= 0) return 4f / 3f
            return w.toFloat() / h.toFloat()
        }
}

typealias ImageLoader = (ImageRef) -> ByteArray?

@Serializable
sealed interface Block

@Serializable
data class CoverBlock(
    val title: String,
    val subtitle: String = "",
    val orgLines: List<String> = emptyList(),
    val logo: ImageRef? = null,
    val heroImage: ImageRef? = null,
    val infoRows: List<Pair<String, String>> = emptyList(),
    val footerLines: List<String> = emptyList(),
) : Block

@Serializable
data class TocBlock(
    val title: String = "Contents",
    val depth: Int = 3,
) : Block

@Serializable
data class HeadingBlock(
    val level: Int,
    val runs: List<Run>,
    val number: String = "",
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

@Serializable
data class KeyValueBlock(
    val pairs: List<Pair<String, List<Run>>>,
    val columns: Int = 1,
    val labelWidthPct: Float = 30.0f,
) : Block

@Serializable
data class TableColumn(
    val header: String,
    val widthPct: Float,
    val align: Align = Align.LEFT,
    val numeric: Boolean = false,
)

@Serializable
data class TableBlock(
    val columns: List<TableColumn>,
    val rows: List<List<List<Run>>>,
    val caption: String = "",
    val totalRow: List<List<Run>>? = null,
    val zebra: Boolean = true,
) : Block {
    init {
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
    val widthPct: Float = 70.0f,
    val align: Align = Align.CENTER,
    val caption: String = "",
) : Block

@Serializable
data class ImageGridBlock(
    val images: List<Pair<ImageRef, String>>,
    val columns: Int = 2,
    val caption: String = "",
) : Block

@Serializable
data class MetricRowBlock(
    val metrics: List<Triple<String, String, String>>,
) : Block

@Serializable
enum class MapPointKind { VENUE, ARTISAN, MARKET, OTHER }

@Serializable
data class MapPoint(
    val label: String,
    val lat: Double,
    val lon: Double,
    val kind: MapPointKind = MapPointKind.OTHER,
    val count: Int = 1,
)

@Serializable
data class MapBlock(
    val title: String = "",
    val caption: String = "",
    val points: List<MapPoint> = emptyList(),
    val highlight: Set<String> = emptySet(),
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

@Serializable
data class ChartBlock(
    val kind: ChartKind,
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
    val kind: String,
    val title: String,
    val runs: List<Run>,
) : Block

@Serializable
data class SignatureBlock(
    val signatories: List<Pair<String, String>>,
) : Block

@Serializable
data class SpacerBlock(
    val heightPct: Float = 5.0f,
) : Block

@Serializable
data object PageBreakBlock : Block

@Serializable
enum class PageSize {
    A4,
    LETTER;

    val sizeMm: Pair<Float, Float>
        get() = if (this == A4) 210.0f to 297.0f else 215.9f to 279.4f
}

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
    val complexFont: String = "Nirmala UI",
    val baseSizePt: Float = 10.5f,
)

const val DEFAULT_ACCENT: String = "1F3864"

data class AccentPreset(val key: String, val label: String, val hex: String)

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

fun normaliseHex(value: String?): String? {
    var text = (value ?: return null).trim()
    if (text.startsWith("#")) text = text.substring(1).trim()
    if (text.isEmpty() || text.any { it !in "0123456789abcdefABCDEF" }) return null
    if (text.length == 3) text = text.map { "$it$it" }.joinToString("")
    if (text.length != 6) return null
    return text.uppercase(java.util.Locale.ROOT)
}

private fun hexToRgb(hex: String): Triple<Double, Double, Double> = Triple(
    hex.substring(0, 2).toInt(16) / 255.0,
    hex.substring(2, 4).toInt(16) / 255.0,
    hex.substring(4, 6).toInt(16) / 255.0,
)

private fun rgbToHex(red: Double, green: Double, blue: Double): String =
    listOf(red, green, blue).joinToString("") { channel ->
        val clamped = channel.coerceIn(0.0, 1.0)
        String.format(java.util.Locale.ROOT, "%02X", (clamped * 255.0 + 0.5).toInt())
    }

private fun rgbToHsl(rgb: Triple<Double, Double, Double>): Triple<Double, Double, Double> {
    val (red, green, blue) = rgb
    val high = maxOf(red, green, blue)
    val low = minOf(red, green, blue)
    val lightness = (high + low) / 2.0
    val span = high - low
    if (span == 0.0) return Triple(0.0, 0.0, lightness)
    val saturation = if (lightness > 0.5) span / (2.0 - high - low) else span / (high + low)
    val hue = when (high) {
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

fun relativeLuminance(hex: String): Double {
    val (red, green, blue) = hexToRgb(hex)
    fun linear(channel: Double): Double =
        if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
    return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
}

fun contrastRatio(first: String, second: String): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

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
    val images: List<ImageRef> = emptyList(),
    val warnings: List<String> = emptyList(),
)

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

    val blockCount: Int get() = blocks.size

    fun warn(message: String): DocumentBuilder {
        warnings.add(cleanText(message))
        return this
    }

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

    fun para(
        text: Any?,
        style: ParaStyle = ParaStyle.BODY,
        align: Align = Align.LEFT,
    ): DocumentBuilder {
        val cleaned = cleanText(text)
        if (cleaned.isBlank()) return this
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

internal fun bookmarkId(number: String, text: String, ordinal: Int): String {
    val slug = NON_WORD.replace("${number}_$text", "_").trim('_').take(28)
    return if (slug.isNotEmpty()) "_S${ordinal}_$slug" else "_S$ordinal"
}
