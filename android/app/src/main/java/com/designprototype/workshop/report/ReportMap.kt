package com.designprototype.workshop.report

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

internal const val MIN_LONGITUDE = 68.2060
internal const val MAX_LONGITUDE = 97.3940
internal const val MIN_LATITUDE = 6.7560
internal const val MAX_LATITUDE = 37.0820

internal const val VIEW_WIDTH = 1000.0

internal const val PADDING = 24.0

private const val MID_LATITUDE = (MIN_LATITUDE + MAX_LATITUDE) / 2

internal val LONGITUDE_SCALE = cos(MID_LATITUDE * Math.PI / 180.0)

private val SPAN_X = (MAX_LONGITUDE - MIN_LONGITUDE) * LONGITUDE_SCALE
private val SPAN_Y = MAX_LATITUDE - MIN_LATITUDE

internal val UNITS_PER_DEGREE = (VIEW_WIDTH - PADDING * 2) / SPAN_X
internal val VIEW_HEIGHT = SPAN_Y * UNITS_PER_DEGREE + PADDING * 2

internal fun projectX(longitude: Double): Double =
    PADDING + (longitude - MIN_LONGITUDE) * LONGITUDE_SCALE * UNITS_PER_DEGREE

internal fun projectY(latitude: Double): Double = PADDING + (MAX_LATITUDE - latitude) * UNITS_PER_DEGREE

internal fun unitsPerKilometre(): Double = UNITS_PER_DEGREE / 111.32

private const val ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

private val ALPHABET_VALUES: IntArray = IntArray(128) { -1 }.also { table ->
    for (index in ALPHABET.indices) table[ALPHABET[index].code] = index
}

private const val COORD_SCALE = 1000.0

private const val OUTLINE_MAGIC = 0x494E4431

private const val BIN_HEADER_BYTES = 4 + 8 * 4 + 4

internal typealias Polyline = DoubleArray

internal fun decodeTextPolylines(payload: String): List<Polyline> {
    val lines = ArrayList<Polyline>()
    val length = payload.length
    var index = 0
    var x = 0L
    var y = 0L

    fun readInt(): Long {
        var shift = 0
        var result = 0L
        while (index < length) {
            val ch = payload[index].code
            val value = if (ch < 128) ALPHABET_VALUES[ch] else -1
            index += 1
            if (value >= 0 && shift < 63) result = result or ((value.toLong() and 0x1FL) shl shift)
            shift += 5
            if (value < 0x20) break
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }

    while (index < length) {
        val count = readInt()
        if (count <= 0) break
        val line = DoubleArray((count * 2).toInt())
        for (point in 0 until count.toInt()) {
            x += readInt()
            y += readInt()
            line[point * 2] = x / COORD_SCALE
            line[point * 2 + 1] = y / COORD_SCALE
        }
        lines.add(line)
    }
    return lines
}

internal fun decodeBinaryPolylines(blob: ByteArray): List<Polyline> {
    if (blob.size < BIN_HEADER_BYTES) return emptyList()
    fun int32(at: Int): Int =
        ((blob[at].toInt() and 0xFF) shl 24) or ((blob[at + 1].toInt() and 0xFF) shl 16) or
            ((blob[at + 2].toInt() and 0xFF) shl 8) or (blob[at + 3].toInt() and 0xFF)

    fun float64(at: Int): Double {
        var bits = 0L
        for (i in 0 until 8) bits = (bits shl 8) or (blob[at + i].toLong() and 0xFFL)
        return Double.fromBits(bits)
    }

    fun uint16(at: Int): Int = ((blob[at].toInt() and 0xFF) shl 8) or (blob[at + 1].toInt() and 0xFF)

    if (int32(0) != OUTLINE_MAGIC) return emptyList()
    val minLon = float64(4)
    val maxLon = float64(12)
    val minLat = float64(20)
    val maxLat = float64(28)
    val count = int32(36)
    if (count <= 0) return emptyList()

    var offset = BIN_HEADER_BYTES
    val lonStep = (maxLon - minLon) / 65535
    val latStep = (maxLat - minLat) / 65535
    val lines = ArrayList<Polyline>(count)
    for (record in 0 until count) {
        if (offset + 4 > blob.size) break
        val points = int32(offset)
        offset += 4
        if (points <= 0 || offset + points * 4 > blob.size) break
        val line = DoubleArray(points * 2)
        for (point in 0 until points) {
            line[point * 2] = minLon + uint16(offset) * lonStep
            line[point * 2 + 1] = minLat + uint16(offset + 2) * latStep
            offset += 4
        }
        lines.add(line)
    }
    return lines
}

fun interface BoundaryAssetSource {
    fun open(name: String): ByteArray?
}

object BoundaryAssets {

    @Volatile
    private var source: BoundaryAssetSource? = null

    private val cache = HashMap<String, List<Polyline>>()

    private val lock = Any()

    fun install(assets: BoundaryAssetSource) {
        synchronized(lock) {
            source = assets
            cache.clear()
        }
    }

    private fun bytes(name: String): ByteArray? = try {
        source?.open(name)
    } catch (_: Exception) {
        null
    }

    private fun load(kind: String): List<Polyline> {
        synchronized(lock) {
            cache[kind]?.let { return it }
        }
        var lines: List<Polyline> = emptyList()
        if (kind == "outline") {
            bytes("india_outline.bin")?.let { lines = decodeBinaryPolylines(it) }
        } else {
            val text = bytes("$kind-borders.txt")
            if (text != null) {
                lines = decodeTextPolylines(text.toString(Charsets.UTF_8))
            } else {
                bytes("${kind}_borders.bin")?.let { lines = decodeBinaryPolylines(it) }
            }
        }
        synchronized(lock) { cache[kind] = lines }
        return lines
    }

    internal fun indiaRings(): List<Polyline> = load("outline")

    internal fun stateBorders(): List<Polyline> = load("state")

    internal fun districtBorders(): List<Polyline> = load("district")

    fun available(): Boolean = indiaRings().isNotEmpty()
}

internal val STATE_SEATS: Map<String, DoubleArray> = linkedMapOf(
    "Andhra Pradesh" to doubleArrayOf(16.5062, 80.6480),
    "Arunachal Pradesh" to doubleArrayOf(27.0844, 93.6053),
    "Assam" to doubleArrayOf(26.1433, 91.7898),
    "Bihar" to doubleArrayOf(25.5941, 85.1376),
    "Chhattisgarh" to doubleArrayOf(21.2514, 81.6296),
    "Goa" to doubleArrayOf(15.4909, 73.8278),
    "Gujarat" to doubleArrayOf(23.2156, 72.6369),
    "Haryana" to doubleArrayOf(30.7333, 76.7794),
    "Himachal Pradesh" to doubleArrayOf(31.1048, 77.1734),
    "Jharkhand" to doubleArrayOf(23.3441, 85.3096),
    "Karnataka" to doubleArrayOf(12.9716, 77.5946),
    "Kerala" to doubleArrayOf(8.5241, 76.9366),
    "Madhya Pradesh" to doubleArrayOf(23.2599, 77.4126),
    "Maharashtra" to doubleArrayOf(19.0760, 72.8777),
    "Manipur" to doubleArrayOf(24.8170, 93.9368),
    "Meghalaya" to doubleArrayOf(25.5788, 91.8933),
    "Mizoram" to doubleArrayOf(23.7271, 92.7176),
    "Nagaland" to doubleArrayOf(25.6751, 94.1086),
    "Odisha" to doubleArrayOf(20.2961, 85.8245),
    "Punjab" to doubleArrayOf(30.7333, 76.7794),
    "Rajasthan" to doubleArrayOf(26.9124, 75.7873),
    "Sikkim" to doubleArrayOf(27.3314, 88.6138),
    "Tamil Nadu" to doubleArrayOf(13.0827, 80.2707),
    "Telangana" to doubleArrayOf(17.3850, 78.4867),
    "Tripura" to doubleArrayOf(23.8315, 91.2868),
    "Uttar Pradesh" to doubleArrayOf(26.8467, 80.9462),
    "Uttarakhand" to doubleArrayOf(30.3165, 78.0322),
    "West Bengal" to doubleArrayOf(22.5726, 88.3639),
    "Andaman and Nicobar Islands" to doubleArrayOf(11.6234, 92.7265),
    "Chandigarh" to doubleArrayOf(30.7333, 76.7794),
    "Dadra and Nagar Haveli and Daman and Diu" to doubleArrayOf(20.3974, 72.8328),
    "Delhi" to doubleArrayOf(28.6139, 77.2090),
    "Jammu and Kashmir" to doubleArrayOf(34.0837, 74.7973),
    "Ladakh" to doubleArrayOf(34.1526, 77.5771),
    "Lakshadweep" to doubleArrayOf(10.5669, 72.6420),
    "Puducherry" to doubleArrayOf(11.9416, 79.8083),
)

internal val INDIAN_STATES_AND_UNION_TERRITORIES: List<String> = STATE_SEATS.keys.toList()

private val STATE_ALIASES: Map<String, String> = mapOf(
    "orissa" to "Odisha",
    "pondicherry" to "Puducherry",
    "pondichery" to "Puducherry",
    "uttaranchal" to "Uttarakhand",
    "chattisgarh" to "Chhattisgarh",
    "dadraandnagarhaveli" to "Dadra and Nagar Haveli and Daman and Diu",
    "damananddiu" to "Dadra and Nagar Haveli and Daman and Diu",
    "newdelhi" to "Delhi",
    "delhinct" to "Delhi",
    "nctofdelhi" to "Delhi",
    "nationalcapitalterritoryofdelhi" to "Delhi",
)

private fun foldStateName(value: String): String {
    val out = StringBuilder(value.length)
    for (ch in value.lowercase(java.util.Locale.ROOT)) {
        if (ch == '&') out.append("and") else if (ch in 'a'..'z' || ch in '0'..'9') out.append(ch)
    }
    return out.toString()
}

internal fun canonicalState(value: String?): String? {
    val text = (value ?: "").trim()
    if (text.isEmpty()) return null
    val folded = foldStateName(text)
    STATE_ALIASES[folded]?.let { return it }
    for (name in STATE_SEATS.keys) if (foldStateName(name) == folded) return name
    return null
}

private fun pinRadius(kind: MapPointKind): Double = when (kind) {
    MapPointKind.VENUE -> 11.0
    MapPointKind.ARTISAN -> 7.5
    MapPointKind.MARKET -> 7.5
    MapPointKind.OTHER -> 6.5
}

private val DRAW_ORDER =
    listOf(MapPointKind.OTHER, MapPointKind.MARKET, MapPointKind.ARTISAN, MapPointKind.VENUE)

private fun pinColour(kind: MapPointKind, theme: ReportTheme): Rgb {
    val accent = rgbOf(theme.accent, rgb(31, 56, 100))
    val soft = rgbOf(theme.accentSoft, rgb(47, 84, 150))
    return when (kind) {
        MapPointKind.VENUE -> accent
        MapPointKind.MARKET -> mix(soft, PAPER, 0.35)
        else -> soft
    }
}

private fun onThisMap(point: MapPoint): Boolean =
    point.lat >= MIN_LATITUDE - 0.5 && point.lat <= MAX_LATITUDE + 0.5 &&
        point.lon >= MIN_LONGITUDE - 0.5 && point.lon <= MAX_LONGITUDE + 0.5

private class LabelSpace {
    private val boxes = ArrayList<DoubleArray>()

    fun place(x: Double, y: Double, width: Double, height: Double): Boolean {
        val box = doubleArrayOf(x - 1, y - 1, x + width + 1, y + height + 1)
        for (other in boxes) {
            if (box[0] < other[2] && other[0] < box[2] && box[1] < other[3] && other[1] < box[3]) {
                return false
            }
        }
        boxes.add(box)
        return true
    }
}

private fun tintStates(
    canvas: Raster,
    highlight: Set<String>,
    land: Rgb,
    tint: Rgb,
): List<String> {
    val wanted = highlight.sorted()
    val landPixels = canvas.countColour(land)
    if (landPixels <= 0) return wanted
    val budget = (landPixels * 0.25).toInt()
    val missed = ArrayList<String>()
    val scale = canvas.width / VIEW_WIDTH
    for (name in wanted) {
        val canonical = canonicalState(name) ?: name
        val seat = STATE_SEATS[canonical]
        if (seat == null) {
            missed.add(name)
            continue
        }
        val filled = canvas.floodFill(
            (projectX(seat[1]) * scale).toInt(), (projectY(seat[0]) * scale).toInt(),
            land, tint, budget,
        )
        if (filled == 0) missed.add(name)
    }
    return missed
}

private fun drawScaleBar(canvas: Raster, scale: Double, ink: Rgb, muted: Rgb) {
    val kilometres = 500.0
    val length = unitsPerKilometre() * kilometres * scale
    if (length <= 8 || length > canvas.width * 0.6) return
    val x = 22.0 * scale
    val y = canvas.height - 26.0 * scale
    val thickness = max(1.0, 2.0 * scale)
    canvas.rect(x, y, length, thickness, ink)
    canvas.rect(x, y - 3 * scale, thickness, 3 * scale + thickness, ink)
    canvas.rect(x + length - thickness, y - 3 * scale, thickness, 3 * scale + thickness, ink)
    val glyph = max(1, Math.rint(scale * 1.6).toInt())
    canvas.drawText(x.toInt(), (y + 5 * scale).toInt(), "0", muted, glyph)
    canvas.drawTextRight((x + length).toInt(), (y + 5 * scale).toInt(), "500 km", muted, glyph)
}

internal fun renderMapPng(block: MapBlock, theme: ReportTheme, widthPx: Int): RasterFigure? {
    val rings = BoundaryAssets.indiaRings()
    if (rings.isEmpty()) return null

    val width = max(240, min(2400, widthPx))
    val scale = width / VIEW_WIDTH
    val height = max(1, Math.rint(VIEW_HEIGHT * scale).toInt())

    val ink = rgbOf(theme.ink, rgb(27, 27, 27))
    val muted = rgbOf(theme.muted, rgb(90, 107, 135))
    val rule = rgbOf(theme.rule, rgb(184, 196, 217))
    val accent = rgbOf(theme.accent, rgb(31, 56, 100))
    val land = mix(PAPER, accent, 0.10)
    val tint = mix(PAPER, accent, 0.30)

    val canvas = Raster(width, height, PAPER)

    val projectedRings = ArrayList<DoubleArray>(rings.size)
    for (ring in rings) {
        val out = DoubleArray(ring.size)
        var index = 0
        while (index < ring.size) {
            out[index] = projectX(ring[index]) * scale
            out[index + 1] = projectY(ring[index + 1]) * scale
            index += 2
        }
        projectedRings.add(out)
    }
    canvas.fillPolygons(projectedRings, land)

    val borderWidth = max(0.7, 1.15 * scale)
    for (line in BoundaryAssets.stateBorders()) {
        val out = DoubleArray(line.size)
        var index = 0
        while (index < line.size) {
            out[index] = projectX(line[index]) * scale
            out[index + 1] = projectY(line[index + 1]) * scale
            index += 2
        }
        canvas.strokePolyline(out, rule, borderWidth)
    }

    val missed =
        if (block.highlight.isNotEmpty()) tintStates(canvas, block.highlight, land, tint)
        else emptyList()

    val coastWidth = max(0.8, 1.5 * scale)
    val coastColour = mix(accent, PAPER, 0.25)
    for (ring in projectedRings) {
        if (ring.size < 4) continue
        val closed = DoubleArray(ring.size + 2)
        System.arraycopy(ring, 0, closed, 0, ring.size)
        closed[ring.size] = ring[0]
        closed[ring.size + 1] = ring[1]
        canvas.strokePolyline(closed, coastColour, coastWidth)
    }

    drawScaleBar(canvas, scale, ink, muted)

    val labels = LabelSpace()
    val glyph = max(1, Math.rint(scale * 1.9).toInt())
    val labelH = textHeight(glyph)

    val points = block.points
        .filter { onThisMap(it) }
        .sortedBy { DRAW_ORDER.indexOf(it.kind).let { at -> if (at < 0) 0 else at } }

    for (point in points) {
        val x = projectX(point.lon) * scale
        val y = projectY(point.lat) * scale
        val radius = pinRadius(point.kind) * scale * 0.5
        val colour = pinColour(point.kind, theme)
        canvas.disc(x, y, radius + max(1.0, 1.2 * scale), PAPER)
        canvas.disc(x, y, radius, colour)
        if (point.kind == MapPointKind.VENUE) canvas.disc(x, y, radius * 0.42, PAPER)

        var text = point.label
        if (point.count > 1) text = "$text (${point.count})"
        text = ellipsise(text, (width * 0.32).toInt(), glyph)
        if (text.isEmpty()) continue
        val w = textWidth(text, glyph)
        val gap = radius + 3 * scale
        val candidates = arrayOf(
            doubleArrayOf(x + gap, y - labelH / 2.0),
            doubleArrayOf(x - gap - w, y - labelH / 2.0),
            doubleArrayOf(x - w / 2.0, y + gap),
            doubleArrayOf(x - w / 2.0, y - gap - labelH),
        )
        for (candidate in candidates) {
            val left = candidate[0]
            val top = candidate[1]
            if (left < 2 || left + w > width - 2 || top < 2 || top + labelH > height - 2) continue
            if (labels.place(left, top, w.toDouble(), labelH.toDouble())) {
                canvas.rect(
                    left - scale, top - scale, w + 2 * scale, labelH + 2 * scale, PAPER, 0.82,
                )
                canvas.drawText(
                    left.toInt(), top.toInt(), text,
                    if (point.kind == MapPointKind.VENUE) ink else muted, glyph,
                )
                break
            }
        }
    }

    if (missed.isNotEmpty()) {
        val small = max(1, glyph - 1)
        val note = ellipsise("Not tinted: " + missed.joinToString(", "), (width * 0.9).toInt(), small)
        canvas.drawText((6 * scale).toInt(), (6 * scale).toInt(), note, muted, small)
    }

    return RasterFigure(canvas.toPng(), width, height)
}
