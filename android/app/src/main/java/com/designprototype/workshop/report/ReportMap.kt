package com.designprototype.workshop.report

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/*
 * The report's map of India: the app's own geometry, projected the app's own way, as a PNG — a port
 * of `backend/app/services/report_map.py`.
 *
 * WHY THIS IS NOT A NEW MAP. Two maps of this country already exist in this repository and they agree
 * with each other: the web app's (`frontend/components/map/IndiaMap.tsx` over `indiaGeometry` and
 * `borderGeometry`, through the single `project` in `frontend/components/map/projection.ts`) and this
 * app's own map screen (`ui/IndiaOutline.kt` over the same `res/raw` assets). A report that drew a
 * third would be a third depiction of a national boundary maintained in a third place, and the three
 * would diverge the first time any one of them was touched. Everything below is therefore a PORT of
 * that projection and a READER of those assets, never a redraw:
 *
 *   * [MIN_LONGITUDE] and friends, [VIEW_WIDTH] and [PADDING] are the constants from
 *     `projection.ts`, and [project] is that file's `project` line for line. The report and both apps
 *     place a pin at the same fraction of the same outline, so a designer who saw a cluster in the
 *     top-left of the map screen finds it in the top-left of the printed one.
 *   * The coastline and the international frontier come from `india_outline.bin` — the same
 *     Government of India depiction the map screen draws, whose provenance is documented at the head
 *     of `ui/IndiaOutline.kt` and verified point-in-polygon there. Nothing here simplifies, clips or
 *     re-derives it.
 *   * The state borders come from `state-borders.txt`, the derived interior edges described in
 *     `frontend/components/map/borderGeometry.ts`.
 *
 * WHY THE .txt AND NOT THE .bin THE MAP SCREEN READS. Both encode the same 81 polylines and the map
 * screen's `state_borders.bin` is already in the APK, so reading it would have shipped nothing new.
 * It is a DIFFERENT QUANTISATION of those polylines, though — a uint16 grid over bounds rounded to
 * three decimals, against the text format's thousandth-of-a-degree deltas — and the server reads the
 * text file. Recovered coordinates therefore differ by up to ~2e-4 degrees, which is under a
 * hundredth of a pixel on this figure and would still be a handful of anti-aliased pixels that differ
 * between the .docx the office downloaded and the .docx generated in the field. Reading the byte-
 * identical asset makes the two figures the same picture by construction instead of by an argument
 * about how small a difference is small enough. The 21 KB is the price of not having that argument.
 *
 * WHY IT IS A RASTER AND NOT VECTORS. The alternative is drawing the outline into OOXML DrawingML
 * *and* into PDF path operators *and* twice more on the server — four implementations of one picture
 * that would have to agree about 11,649 points. Rasterising once and handing the PNG to the image
 * path every renderer already has is one implementation, and it is the reason [DocxWriter] and
 * [PdfWriter] needed nothing new to print a map beyond a call into here.
 *
 * WHY THERE IS NO android.graphics IN THE IMPORT LIST. [Raster] does the drawing with `java.util.zip`
 * and integer arithmetic; see its header for why a Canvas-drawn map would be a different map. Keeping
 * this file free of platform types is also what lets it compile and run on a bare JVM against the
 * Python module used as an oracle, which is the only way a port of this size can be checked at all.
 *
 * HOW A STATE IS TINTED, which is the one genuinely surprising thing in this file. The assets are
 * BORDERS, not polygons — `borderGeometry.ts` explains at length why storing them as polygons would
 * ship the interior of India twice — so there is no "Rajasthan" ring to fill. What there is, after the
 * country is filled and every state border is stroked over it, is a raster in which each state is a
 * connected region of land-coloured pixels bounded by stroke. Seeding a flood fill at the state's own
 * capital therefore tints exactly that state, using the geometry that is already there rather than a
 * second dataset that would have to agree with it. The fill is budgeted and discovers before it
 * commits — see [Raster.floodFill] for what happens when a border run stops a pixel short of the
 * coast, and why that must never colour the subcontinent.
 */

// --------------------------------------------------------------------------------------
// The projection — frontend/components/map/projection.ts, ported constant for constant
// --------------------------------------------------------------------------------------

/*
 * `indiaGeometry.INDIA_BOUNDS`. The bounds of the SIMPLIFIED geometry, not of the source, and not the
 * bounds in the binary asset's own header either — those are the unrounded extent the quantiser used.
 * `projection.ts` divides by THESE, so a report that divided by the header's would place every pin a
 * fraction of a pixel off the web app's, which is the kind of difference nobody sees and everybody
 * argues about later.
 */
internal const val MIN_LONGITUDE = 68.2060
internal const val MAX_LONGITUDE = 97.3940
internal const val MIN_LATITUDE = 6.7560
internal const val MAX_LATITUDE = 37.0820

/** Width of the shared user space. Height follows from the country's true aspect ratio. */
internal const val VIEW_WIDTH = 1000.0

/** Breathing room around the coastline, so a coastal pin is not half cut off. */
internal const val PADDING = 24.0

private const val MID_LATITUDE = (MIN_LATITUDE + MAX_LATITUDE) / 2

/**
 * Plate carrée with the horizontal axis scaled by cos(centre latitude).
 *
 * NOT Web Mercator: India spans 6.8N to 37.1N and Mercator stretches the top of that range about 25%
 * more than the bottom, so Kashmir would print visibly too large beside Kerala on a figure whose whole
 * job is comparing how much work came from where. Note that this is NOT the projection
 * `ui/IndiaOutline.kt` uses either — the interactive map screen IS Web Mercator, because it zooms and
 * a conformal projection is what keeps a pin on its own coastline at every scale. A printed figure has
 * exactly one scale and needs the honest proportions instead, which is why the report shares the WEB
 * map's projection rather than the phone map's.
 */
internal val LONGITUDE_SCALE = cos(MID_LATITUDE * Math.PI / 180.0)

private val SPAN_X = (MAX_LONGITUDE - MIN_LONGITUDE) * LONGITUDE_SCALE
private val SPAN_Y = MAX_LATITUDE - MIN_LATITUDE

internal val UNITS_PER_DEGREE = (VIEW_WIDTH - PADDING * 2) / SPAN_X
internal val VIEW_HEIGHT = SPAN_Y * UNITS_PER_DEGREE + PADDING * 2

/** Where a coordinate lands in the shared user space, as x. Latitude grows north; y grows down. */
internal fun projectX(longitude: Double): Double =
    PADDING + (longitude - MIN_LONGITUDE) * LONGITUDE_SCALE * UNITS_PER_DEGREE

internal fun projectY(latitude: Double): Double = PADDING + (MAX_LATITUDE - latitude) * UNITS_PER_DEGREE

/**
 * How many user-space units a kilometre covers. Used to size the scale bar.
 *
 * The same function the web map's bar uses, so the two figures cannot disagree about how long 500 km
 * is — which a reader comparing a printed report against the screen would notice immediately.
 */
internal fun unitsPerKilometre(): Double = UNITS_PER_DEGREE / 111.32

// --------------------------------------------------------------------------------------
// Reading the assets
// --------------------------------------------------------------------------------------

/** `borderGeometry.ts`'s alphabet and quantisation. Must match `scripts/build_boundaries.py`. */
private const val ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

private val ALPHABET_VALUES: IntArray = IntArray(128) { -1 }.also { table ->
    for (index in ALPHABET.indices) table[ALPHABET[index].code] = index
}

private const val COORD_SCALE = 1000.0

/** `IND1`. The header magic of every `.bin` in `res/raw`. */
private const val OUTLINE_MAGIC = 0x494E4431

/** Magic, four float64 bounds, an int32 record count. Big-endian, unpadded. */
private const val BIN_HEADER_BYTES = 4 + 8 * 4 + 4

/**
 * One decoded record: interleaved `lon, lat, lon, lat…`.
 *
 * A flat [DoubleArray] rather than a list of pairs, because the outline is 11,649 points and the
 * border file another 6,350; as boxed pairs that is 36,000 objects and about a megabyte of a phone's
 * heap, allocated inside an export the designer is waiting on, for data that is never mutated and
 * never indexed by anything but position.
 */
internal typealias Polyline = DoubleArray

/**
 * Decode `state-borders.txt` — `borderGeometry.decodeBorders`, in Kotlin.
 *
 * Per record a zig-zag varint point count, then that many delta-encoded coordinate pairs, with x and y
 * carried ACROSS records so a neighbouring border costs a few characters rather than a full
 * coordinate. Open polylines: never close one, or the Rajasthan/Gujarat border grows a straight line
 * across the country back to where it started.
 */
internal fun decodeTextPolylines(payload: String): List<Polyline> {
    val lines = ArrayList<Polyline>()
    val length = payload.length
    var index = 0
    var x = 0L
    var y = 0L

    // The accumulator is a Long where the server's is a Python int of unbounded width. Five-bit groups
    // mean a well-formed coordinate delta uses at most four of them, so the two agree on every byte
    // this asset actually contains; the shift guard exists only so a TRUNCATED or corrupt file cannot
    // run the shift past the word size, where Kotlin silently wraps it modulo 64 and would produce a
    // plausible-looking coordinate in the middle of the Bay of Bengal instead of a short read.
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
        // A zero or negative count means a truncated payload; stop rather than spin on it.
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

/**
 * Decode an `IND1` binary — the format `ui/IndiaOutline.loadIndiaGeometry` reads.
 *
 * The header carries the bounds the quantiser used, and the points are un-quantised with THOSE rather
 * than with [MIN_LONGITUDE] and friends. Mixing the two is the classic version of this bug: the
 * numbers are within a thousandth of a degree of each other, so the map still looks like India and
 * every coastline sits a pixel out.
 */
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

    // readUnsignedShort, not readShort: the grid is unsigned and the top half of the country would
    // otherwise arrive as negative longitudes.
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

/**
 * Where the boundary geometry comes from on this surface.
 *
 * Injected rather than read directly, for two reasons. The obvious one is that the assets live behind
 * an `AssetManager` and a `Resources`, both of which need a `Context`, and a `Context` in this file
 * would put `android.*` in a module that has to compile on a bare JVM to be checked against the
 * server at all. The one that matters more in the field: a build that has not installed a source, or
 * an APK a future split-install shipped without the boundary assets, must DEGRADE — [renderMapPng]
 * returns null, the writers drop the figure and record a warning, and the report is generated without
 * its locator map. A report that refused to generate because a decorative map was unavailable would
 * be a far worse outcome for the designer waiting on it in a field.
 *
 * Returning null for a name is the normal way to say "this build does not carry that asset". Throwing
 * is not: it would surface inside an export as a crash three screens away from anything about maps.
 */
fun interface BoundaryAssetSource {
    /** The raw bytes of one named asset, or null when this build does not carry it. */
    fun open(name: String): ByteArray?
}

/**
 * The decoded boundary geometry, read at most once per process.
 *
 * Decoded once and kept, exactly as `indiaGeometry.indiaRings` memoises on the web and as the map
 * screen keeps its `IndiaGeometry`: a single pass over 11,649 points is cheap but it is not free, and
 * a report with three maps in it would otherwise pay for it three times inside an export the designer
 * is watching a progress bar for.
 *
 * The cache is keyed by kind and holds the EMPTY LIST for an asset that is not there, so a build
 * without the geometry asks its source once and then stops asking. [install] clears it, which is what
 * makes the JVM parity harness able to point at a checkout's files without a stale decode from a
 * previous case leaking into the next.
 */
object BoundaryAssets {

    @Volatile
    private var source: BoundaryAssetSource? = null

    private val cache = HashMap<String, List<Polyline>>()

    private val lock = Any()

    /** Point this process's map rendering at [assets]. Safe to call more than once. */
    fun install(assets: BoundaryAssetSource) {
        synchronized(lock) {
            source = assets
            cache.clear()
        }
    }

    private fun bytes(name: String): ByteArray? = try {
        source?.open(name)
    } catch (_: Exception) {
        // An unreadable asset is a MISSING asset, not a crashed export — the same rule the server
        // applies to an unreadable mount point. Broad on purpose: an AssetManager throws IOException,
        // a Resources lookup throws NotFoundException, and a future source could throw something else
        // again; none of them is a reason to lose the whole report.
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

    /** The coastline and the international frontier, as rings of (longitude, latitude). */
    internal fun indiaRings(): List<Polyline> = load("outline")

    /** The interior edges between two states, as OPEN polylines. */
    internal fun stateBorders(): List<Polyline> = load("state")

    /** The interior edges between two districts of the same state, as OPEN polylines. */
    internal fun districtBorders(): List<Polyline> = load("district")

    /**
     * Whether there is enough geometry to draw a map at all.
     *
     * The outline alone is enough: a country with no interior borders is a poorer figure than one with
     * them, but it is still an honest map. A missing outline is not — the pins would float on a white
     * rectangle with nothing to locate them against.
     */
    fun available(): Boolean = indiaRings().isNotEmpty()
}

// --------------------------------------------------------------------------------------
// Where a state is, for the purpose of seeding a fill
// --------------------------------------------------------------------------------------

/**
 * The seat of every one of the 36 states and union territories — `geography.STATE_SEATS`, ported.
 *
 * A PUBLISHED CAPITAL, NOT A COMPUTED CENTROID, and that is the whole reason this table exists rather
 * than a bounding-box midpoint taken off the geometry already loaded above. A centroid is not
 * guaranteed to lie inside its own state: Maharashtra's wraps around Mumbai's inlet, and the centroid
 * of the Andaman and Nicobar Islands is open sea. A seed that lands outside the region it names does
 * not fail loudly — it floods whichever neighbour it landed in, and the report tints the wrong state.
 *
 * Two seats are shared on purpose and are not a mistake: Chandigarh is the capital of Haryana, of
 * Punjab and of itself. A fill seeded there tints the union territory, which is what the pixel under
 * the seed actually belongs to; the two states go untinted and are NAMED on the figure by
 * [renderMapPng] rather than silently dropped, because a state a template asked to tint and that did
 * not tint is a difference a reader would otherwise attribute to the data.
 */
private val STATE_SEATS: Map<String, DoubleArray> = linkedMapOf(
    // States, in the order `address.INDIAN_STATES` lists them.
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
    // Union territories, in the order `address.INDIAN_UNION_TERRITORIES` lists them.
    "Andaman and Nicobar Islands" to doubleArrayOf(11.6234, 92.7265),
    "Chandigarh" to doubleArrayOf(30.7333, 76.7794),
    "Dadra and Nagar Haveli and Daman and Diu" to doubleArrayOf(20.3974, 72.8328),
    "Delhi" to doubleArrayOf(28.6139, 77.2090),
    "Jammu and Kashmir" to doubleArrayOf(34.0837, 74.7973),
    "Ladakh" to doubleArrayOf(34.1526, 77.5771),
    "Lakshadweep" to doubleArrayOf(10.5669, 72.6420),
    "Puducherry" to doubleArrayOf(11.9416, 79.8083),
)

/**
 * Spellings that are not canonical but name a real state — `address._ALIASES`, ported.
 *
 * Keyed by the FOLDED form, exactly as the server keys them, so "Orissa", "orissa" and "ORISSA" all
 * arrive here as `orissa`. Every one of these is a spelling that is actually in the data: the state
 * column predates its validator and is nullable, so rows exist holding "Orissa" and "Pondicherry",
 * and a highlight list built from those rows would tint nothing and print a "Not tinted" note naming
 * states the reader can see perfectly well on the map.
 */
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

/**
 * A state name reduced to its comparison key: lower-cased, "&" spelled out, punctuation dropped.
 *
 * "Tamil Nadu", "TAMIL NADU", "tamil-nadu" and "Tamilnadu" all fold to `tamilnadu`, so a value that is
 * only mis-cased or mis-spaced is corrected rather than rejected. Folding "&" to "and" first is what
 * makes "Jammu & Kashmir" and "Andaman & Nicobar Islands" resolve without needing an alias entry for
 * every ampersand variant.
 *
 * `lowercase()` WITHOUT A LOCALE, matching Python's `str.lower()`. The locale-sensitive overload is
 * the default in Kotlin and on a Turkish handset it maps 'I' to a dotless 'ı', so "TAMIL NADU" typed
 * on that phone would fold to `tamılnadu` and match nothing — a state that tints on every device but
 * one, which is the worst shape a bug of this kind can take.
 */
private fun foldStateName(value: String): String {
    val out = StringBuilder(value.length)
    for (ch in value.lowercase(java.util.Locale.ROOT)) {
        // Exactly the server's `[^a-z0-9]+` character class, NOT `Char.isLetterOrDigit()`. The Kotlin
        // predicate is Unicode-aware and would keep the Devanagari and the accented letters the
        // server's regex drops, so a name typed with a combining mark would fold to something the
        // server's key set has never contained and would resolve on one surface only.
        if (ch == '&') out.append("and") else if (ch in 'a'..'z' || ch in '0'..'9') out.append(ch)
    }
    return out.toString()
}

/**
 * The canonical state name, or null for anything that is not on the closed list.
 *
 * The STRICT resolver, matching `address.canonical_state` and not `normalize_state`: an unrecognised
 * name returns null rather than being handed back trimmed. This is a read path, and a caller that
 * treated a trimmed "Atlantis" as valid would look up a seat that does not exist, get nothing, and
 * report "Not tinted: Atlantis" — which is right by accident here but wrong everywhere else the same
 * mistake is made.
 */
internal fun canonicalState(value: String?): String? {
    val text = (value ?: "").trim()
    if (text.isEmpty()) return null
    val folded = foldStateName(text)
    STATE_ALIASES[folded]?.let { return it }
    for (name in STATE_SEATS.keys) if (foldStateName(name) == folded) return name
    return null
}

// --------------------------------------------------------------------------------------
// Drawing
// --------------------------------------------------------------------------------------

/**
 * Nominal user-space radius of each pin, before the raster's own scale factor.
 *
 * The venue is larger than an artisan's origin so the two are told apart without reading a single
 * label — which is the whole reason [MapPointKind] exists.
 */
private fun pinRadius(kind: MapPointKind): Double = when (kind) {
    MapPointKind.VENUE -> 11.0
    MapPointKind.ARTISAN -> 7.5
    MapPointKind.MARKET -> 7.5
    MapPointKind.OTHER -> 6.5
}

/**
 * Draw order.
 *
 * A venue drawn first would be hidden under whichever artisan pin landed on the same town — and at a
 * workshop the venue and several artisans routinely share one.
 */
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

/**
 * Whether [point] is on this map at all.
 *
 * A coordinate outside the drawn extent is DROPPED rather than clamped to the edge. Clamping would put
 * a pin on the coastline for a latitude that is not in India at all — a zero/zero default from a form
 * that never captured a fix, most often — and a pin on the Konkan coast reads as a finding rather than
 * as missing data.
 */
private fun onThisMap(point: MapPoint): Boolean =
    point.lat >= MIN_LATITUDE - 0.5 && point.lat <= MAX_LATITUDE + 0.5 &&
        point.lon >= MIN_LONGITUDE - 0.5 && point.lon <= MAX_LONGITUDE + 0.5

/**
 * Which parts of the image already carry a label.
 *
 * A raster has no clipping region and no text layout engine, so two labels drawn at the same place are
 * simply drawn on top of one another and the reader sees a smear of overlapping strokes with no way to
 * tell which name belonged to which pin. Refusing the second is the honest outcome: the pin is still
 * there, and the accompanying prose names every place.
 */
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

/**
 * Flood-fill each named state, and report the ones that could not be tinted.
 *
 * THE BUDGET IS THE SAFETY. The largest state is about a tenth of India's land area, so a fill that
 * reaches a quarter of it has escaped through a gap between a border run and the coast — the two
 * datasets differ at the frontier by up to about two kilometres, which `borderGeometry.ts` documents
 * and is exactly the size of gap a stroke can fail to close. Discovering the whole region before
 * writing any of it turns that from "the map is one solid block of colour, submitted to a ministry"
 * into "one state is not tinted".
 */
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

/**
 * A 500 km bar in the bottom-left, so a reader can judge how far apart two clusters are.
 *
 * Sized through [unitsPerKilometre], which is the same function the web map's bar uses, so the two
 * figures cannot disagree about how long 500 km is.
 */
private fun drawScaleBar(canvas: Raster, scale: Double, ink: Rgb, muted: Rgb) {
    val kilometres = 500.0
    val length = unitsPerKilometre() * kilometres * scale
    // A bar under 8 px is a dash with two ticks on it and says nothing; one over 60% of the width is
    // furniture competing with the country. Neither is worth drawing.
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

/**
 * Rasterise [block] and return the PNG with the pixel size it was drawn at, or null.
 *
 * NULL MEANS THE GEOMETRY IS NOT ON THIS DEVICE — see [BoundaryAssetSource]. It is not an error
 * condition and must not be turned into one by a caller: the writers drop the figure and record a
 * warning, and the report is generated without it.
 *
 * A block with NO POINTS still renders. An empty map of the workshop's state is a perfectly ordinary
 * thing for a report whose participants all came from villages the place table cannot resolve, and
 * returning nothing in that case would silently remove a figure the template asked for — which reads
 * as a rendering fault rather than as an absence of data.
 */
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
    // The land is a very light wash of the theme's own accent rather than a fixed grey, so the map
    // belongs to the same document as the tables above it under all four template themes.
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
    // ONE call with every ring, not one call per ring. Even-odd filling is what makes the single
    // interior ring a hole; filling each ring on its own would paint the hole in solid, and the hole
    // is real geometry rather than a defect in the source.
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

    // The frontier is stroked LAST of the line work and from the outline rather than from the border
    // files, because the border generator drops every edge that appears once — the coast and the
    // international frontier — on purpose. Drawing it from the outline's rings is what makes the
    // national boundary identical at every level of detail by construction rather than by hoping two
    // published datasets agree. They do not: they differ by up to about 2 km.
    val coastWidth = max(0.8, 1.5 * scale)
    val coastColour = mix(accent, PAPER, 0.25)
    for (ring in projectedRings) {
        if (ring.size < 4) continue
        // A ring is closed geometry, so the stroke gets its first point appended. [strokePolyline]
        // never closes a run itself — a state border must not — so the closure has to be made here or
        // every one of the 308 coastline rings would be left with one segment of its outline missing,
        // at whichever arbitrary point the source happened to start it.
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
        // A white ring under every pin. Two pins in neighbouring districts otherwise merge into one
        // blob at this scale, and a reader counts three origins where there were five.
        canvas.disc(x, y, radius + max(1.0, 1.2 * scale), PAPER)
        canvas.disc(x, y, radius, colour)
        if (point.kind == MapPointKind.VENUE) canvas.disc(x, y, radius * 0.42, PAPER)

        var text = point.label
        if (point.count > 1) text = "$text (${point.count})"
        text = ellipsise(text, (width * 0.32).toInt(), glyph)
        if (text.isEmpty()) continue
        val w = textWidth(text, glyph)
        val gap = radius + 3 * scale
        // Right of the pin, then left, then below, then above. The first that is inside the image and
        // does not overlap a label already placed wins; if none does, the pin goes unlabelled.
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
                // A halo of paper behind the text, for the same reason the pin has one: five-by-seven
                // strokes over a stroked border are unreadable, and the label is the only thing that
                // says which cluster this is.
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
        // Said on the figure rather than swallowed. A state the template asked to tint and that this
        // map did not tint is a difference a reader would otherwise attribute to the data.
        val small = max(1, glyph - 1)
        val note = ellipsise("Not tinted: " + missed.joinToString(", "), (width * 0.9).toInt(), small)
        canvas.drawText((6 * scale).toInt(), (6 * scale).toInt(), note, muted, small)
    }

    return RasterFigure(canvas.toPng(), width, height)
}
