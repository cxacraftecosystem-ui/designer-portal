package com.designprototype.workshop.report

import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater

/*
 * Recompute, on a bare JVM, everything `backend/tools/report_figure_oracle.py` wrote down, and compare.
 *
 * The three files under test — ReportRaster.kt, ReportChart.kt, ReportMap.kt — are compiled here from
 * their REAL paths in the Android source tree, unmodified. That is the whole point: what is checked is
 * the code that ships, not a copy of it.
 *
 * Pixels, not PNG bytes. The backend's CPython links zlib-ng and this JVM links stock zlib, so
 * identical pixels compress to different IDAT. See the oracle's docstring.
 */

private const val EXPECTED_DIGITS = 16

private fun dbits(value: Double): String =
    java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(value))
        .padStart(EXPECTED_DIGITS, '0')

private fun fnv1a(data: ByteArray): String {
    var digest = -0x340d631b7bdddcdbL // 0xCBF29CE484222325 as a signed Long
    for (byte in data) {
        digest = digest xor (byte.toLong() and 0xFFL)
        digest *= 0x100000001B3L
    }
    return java.lang.Long.toHexString(digest).padStart(EXPECTED_DIGITS, '0')
}

private fun sha256(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

private fun rgbTuple(colour: Rgb): String = "(${colour.red()}, ${colour.green()}, ${colour.blue()})"

// --------------------------------------------------------------------------------------
// Failure accounting
// --------------------------------------------------------------------------------------

private val failures = ArrayList<String>()
private var checks = 0

private fun expect(key: String, expected: String, actual: String) {
    checks += 1
    if (expected != actual) {
        // Truncated: a mismatched pixel digest is 64 characters and a mismatched palette could be
        // thousands of lines, and a wall of hex is what stops somebody reading the first real failure.
        failures.add("$key\n    oracle: ${expected.take(200)}\n    kotlin: ${actual.take(200)}")
    }
}

// --------------------------------------------------------------------------------------
// PNG, decoded the way the oracle decodes it
// --------------------------------------------------------------------------------------

private class Decoded(
    val width: Int,
    val height: Int,
    val depth: Int,
    val colour: Int,
    val rows: ByteArray,
)

private fun beInt(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
        ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

private fun decodePng(data: ByteArray): Decoded {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    require(data.copyOfRange(0, 8).contentEquals(signature)) { "not a PNG" }
    var offset = 8
    var width = 0
    var height = 0
    var depth = 0
    var colour = 0
    var sawHeader = false
    val payload = java.io.ByteArrayOutputStream()
    while (offset < data.size) {
        val length = beInt(data, offset)
        val tag = String(data, offset + 4, 4, Charsets.US_ASCII)
        val body = data.copyOfRange(offset + 8, offset + 8 + length)
        val stored = beInt(data, offset + 8 + length)
        val crc = java.util.zip.CRC32()
        crc.update(tag.toByteArray(Charsets.US_ASCII))
        crc.update(body)
        require(crc.value.toInt() == stored) { "bad CRC on $tag" }
        when (tag) {
            "IHDR" -> {
                width = beInt(body, 0)
                height = beInt(body, 4)
                depth = body[8].toInt()
                colour = body[9].toInt()
                require(body[10].toInt() == 0 && body[11].toInt() == 0 && body[12].toInt() == 0) {
                    "unexpected compression/filter/interlace in IHDR"
                }
                sawHeader = true
            }
            "IDAT" -> payload.write(body)
        }
        offset += 12 + length
    }
    require(sawHeader) { "no IHDR" }

    val inflater = Inflater()
    inflater.setInput(payload.toByteArray())
    val stride = width * 3
    val raw = ByteArray(height * (stride + 1))
    var written = 0
    while (written < raw.size && !inflater.finished()) {
        val n = inflater.inflate(raw, written, raw.size - written)
        if (n == 0) break
        written += n
    }
    inflater.end()
    require(written == raw.size) { "inflated $written bytes, expected ${raw.size}" }

    val rows = ByteArray(height * stride)
    for (row in 0 until height) {
        val start = row * (stride + 1)
        require(raw[start].toInt() == 0) { "row $row uses filter ${raw[start]}, not None" }
        System.arraycopy(raw, start + 1, rows, row * stride, stride)
    }
    return Decoded(width, height, depth, colour, rows)
}

private fun histogram(rows: ByteArray): String {
    val counts = HashMap<Int, Int>()
    var index = 0
    while (index < rows.size) {
        val key = ((rows[index].toInt() and 0xFF) shl 16) or
            ((rows[index + 1].toInt() and 0xFF) shl 8) or (rows[index + 2].toInt() and 0xFF)
        counts[key] = (counts[key] ?: 0) + 1
        index += 3
    }
    val out = StringBuilder()
    counts.entries
        .map { "%06x".format(it.key) to it.value }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        .forEach { out.append(it.first).append('\t').append(it.second).append('\n') }
    return out.toString()
}

// --------------------------------------------------------------------------------------
// The scene — mirrors report_figure_oracle.draw_primitives line for line
// --------------------------------------------------------------------------------------

private fun primitivesScene(canvas: Raster) {
    val ink = rgb(17, 34, 51)
    val warm = rgb(200, 90, 40)
    val pale = mix(PAPER, ink, 0.12)

    for (step in 0 until 12) {
        canvas.span(2 + step, 3.0 + step * 0.37, 60.0 - step * 0.41, ink, 0.25 + step * 0.06)
    }

    canvas.rect(4.6, 18.3, 40.7, 12.4, warm)
    canvas.rect(50.2, 18.9, 30.1, 11.05, warm, 0.55)

    val outer = doubleArrayOf(10.0, 40.0, 95.0, 36.5, 120.0, 78.25, 60.5, 96.0, 8.0, 70.75)
    val hole = doubleArrayOf(35.0, 55.0, 70.0, 53.0, 68.0, 74.0, 33.0, 72.0)
    canvas.fillPolygons(listOf(outer, hole), pale)

    canvas.fillPolygons(
        listOf(doubleArrayOf(130.0, 40.0, 185.0, 90.0, 130.0, 90.0, 185.0, 40.0)),
        rgb(60, 120, 90), 0.8,
    )

    canvas.strokePolyline(
        doubleArrayOf(12.5, 108.0, 60.0, 102.25, 61.0, 140.5, 150.75, 118.0, 196.0, 150.0),
        ink, 3.6,
    )
    canvas.strokePolyline(doubleArrayOf(12.0, 160.0, 90.0, 156.5, 140.0, 168.0), warm, 0.5)

    canvas.disc(30.5, 185.0, 14.0, rgb(40, 80, 160))
    canvas.disc(30.5, 185.0, 5.75, PAPER)
    canvas.disc(66.25, 186.5, 0.8, ink)

    canvas.ring(120.0, 190.0, 26.0, 15.5, rgb(150, 40, 60))
    canvas.ring(120.0, 190.0, 26.0, 15.5, rgb(30, 90, 140), start = -0.9, sweep = 1.7)
    canvas.ring(120.0, 190.0, 26.0, 15.5, rgb(240, 190, 60), start = 2.9, sweep = 0.9)
    canvas.ring(178.0, 190.0, 22.0, 0.0, rgb(90, 60, 150), start = -1.5707963267948966, sweep = 2.4)

    canvas.drawText(4, 218, "Wg 0123 ,.;:", ink, 1)
    canvas.drawTextCentred(100, 228, "‘Kharaɡpur’ – ₹4,200", ink, 2)
    canvas.drawTextRight(196, 246, "ଓଡିଆ x2 …", warm, 1)

    canvas.rect(150.0, 210.0, 44.0, 34.0, PAPER)
    canvas.strokePolyline(
        doubleArrayOf(150.0, 210.0, 194.0, 210.0, 194.0, 244.0, 150.0, 244.0, 150.0, 210.0), ink, 2.0
    )
    canvas.floodFill(172, 227, PAPER, rgb(250, 220, 120), 20_000)
    canvas.floodFill(2, 250, PAPER, rgb(255, 0, 0), 40)
}

// --------------------------------------------------------------------------------------
// The cases — the same list the oracle carries
// --------------------------------------------------------------------------------------

private val THEMES: Map<String, ReportTheme> = mapOf(
    "default" to ReportTheme(),
    "alt" to ReportTheme(
        accent = "7A3B12", accentSoft = "B26B2E", ink = "221A10", muted = "6B5A47", rule = "D9C4A8",
    ),
    "broken" to ReportTheme(
        accent = "zzz", accentSoft = "", ink = "#12345", muted = "  1a2b3c  ", rule = "x",
    ),
)

private class ChartCase(
    val name: String,
    val block: ChartBlock,
    val theme: String,
    val widthPx: Int,
)

private val CHART_CASES = listOf(
    ChartCase("chart_bar_plain", ChartBlock(
        kind = ChartKind.BAR,
        series = listOf("Sarees" to 12.0, "Stoles" to 7.0, "Yardage" to 19.0, "Dupatta" to 3.0),
        unit = "pieces",
    ), "default", 640),
    ChartCase("chart_bar_negative", ChartBlock(
        kind = ChartKind.BAR,
        series = listOf("Q1" to 4.0, "Q2" to -6.5, "Q3" to 0.0, "Q4" to 11.25),
    ), "alt", 520),
    ChartCase("chart_bar_all_zero", ChartBlock(
        kind = ChartKind.BAR, series = listOf("A" to 0.0, "B" to 0.0, "C" to 0.0),
    ), "default", 400),
    ChartCase("chart_bar_huge", ChartBlock(
        kind = ChartKind.BAR,
        series = listOf("Material" to 1234567.0, "Wages" to 987654.5, "Dye" to 1500.5),
        unit = "₹",
    ), "default", 900),
    ChartCase("chart_bar_broken_theme", ChartBlock(
        kind = ChartKind.BAR, series = listOf("One" to 1.0, "Two" to 2.0),
    ), "broken", 300),
    ChartCase("chart_line_plain", ChartBlock(
        kind = ChartKind.LINE,
        series = listOf("3 months" to 40.0, "6 months" to 62.5, "12 months" to 58.0),
        unit = "units sold",
    ), "default", 700),
    ChartCase("chart_line_single", ChartBlock(
        kind = ChartKind.LINE, series = listOf("Only" to 5.0),
    ), "alt", 480),
    ChartCase("chart_hbar_costs", ChartBlock(
        kind = ChartKind.HORIZONTAL_BAR,
        series = listOf(
            "Material" to 4200.0, "Wages" to 3100.0, "Dyeing and finishing" to 900.0,
            "Packaging" to 250.0, "Transport" to 175.5, "Overheads" to 0.0,
        ),
        unit = "₹ per piece",
    ), "default", 760),
    ChartCase("chart_hbar_negative", ChartBlock(
        kind = ChartKind.HORIZONTAL_BAR,
        series = listOf("Margin" to -420.0, "Rebate" to 130.0),
    ), "alt", 340),
    ChartCase("chart_pie_plain", ChartBlock(
        kind = ChartKind.PIE,
        series = listOf("Accepted" to 9.0, "Revised" to 5.0, "Rejected" to 2.0, "Pending" to 1.0),
    ), "default", 800),
    ChartCase("chart_pie_dropped", ChartBlock(
        kind = ChartKind.PIE,
        series = listOf("Good" to 6.0, "Bad" to -2.0, "Worse" to -1.0, "Fine" to 3.0),
    ), "default", 560),
    ChartCase("chart_pie_zero_total", ChartBlock(
        kind = ChartKind.PIE, series = listOf("A" to 0.0, "B" to 0.0),
    ), "alt", 460),
    ChartCase("chart_donut_plain", ChartBlock(
        kind = ChartKind.DONUT,
        series = listOf(
            "Under 500" to 14.0, "500-1500" to 22.0, "1500-5000" to 8.0, "Over 5000" to 3.0,
        ),
        unit = "pieces",
    ), "default", 820),
    ChartCase("chart_donut_many", ChartBlock(
        kind = ChartKind.DONUT,
        series = (1..14).map { "Head $it" to ((it * it) % 17 + 1).toDouble() },
    ), "alt", 600),
    ChartCase("chart_empty", ChartBlock(kind = ChartKind.BAR, series = emptyList()), "default", 380),
    ChartCase("chart_all_dropped", ChartBlock(
        kind = ChartKind.PIE, series = listOf("A" to -1.0, "B" to -2.0),
    ), "default", 380),
)

private class MapCase(val name: String, val block: MapBlock, val theme: String, val widthPx: Int)

private val MAP_CASES = listOf(
    MapCase("map_bare", MapBlock(), "default", 320),
    MapCase("map_points", MapBlock(
        points = listOf(
            MapPoint("Sambalpur", 21.4669, 83.9812, MapPointKind.VENUE, 1),
            MapPoint("Barpali", 21.1833, 83.5833, MapPointKind.ARTISAN, 6),
            MapPoint("Bhubaneswar", 20.2961, 85.8245, MapPointKind.MARKET, 2),
            MapPoint("Somewhere", 28.6139, 77.2090, MapPointKind.OTHER, 1),
            MapPoint("Nowhere", 0.0, 0.0, MapPointKind.ARTISAN, 3),
        ),
    ), "default", 420),
    MapCase("map_highlight", MapBlock(
        points = listOf(MapPoint("Jaipur", 26.9124, 75.7873, MapPointKind.VENUE, 1)),
        highlight = setOf("Rajasthan", "Orissa", "Atlantis"),
    ), "alt", 420),
)

// --------------------------------------------------------------------------------------
// Scalars — the same list, in the same order, as report_figure_oracle.scalar_lines
// --------------------------------------------------------------------------------------

private val MM_SAMPLES =
    doubleArrayOf(0.0, 1.0, 12.7, 30.48, 30.4800001, 105.6, 160.0, 199.9, 304.8, 400.0, 1000.0)

private val NUMBER_SAMPLES = doubleArrayOf(
    0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.5, 3.5, 0.125, 0.005, 0.995, 12.34, 999.0, 999.5,
    1000.0, 1000.5, 1500.5, 2500.5, 12345.0, 1234567.0, 987654.5, -1234567.89, 1e12, 1e-3,
    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
)

private val STEP_SAMPLES = listOf(
    0.0 to 4, 1.0 to 4, 3.0 to 4, 7.0 to 4, 10.0 to 4, 23.0 to 4, 99.0 to 4, 100.0 to 4,
    1234.0 to 4, 0.004 to 4, 1e7 to 4, 7.0 to 0, -3.0 to 4,
)

private val TEXT_SAMPLES = listOf(
    "", "A", "Material", "3–6 months", "Artisan’s ‘work’", "₹ 4,200", "ଓଡିଆ",
    "Dyeing and finishing", "a very long category label indeed", "x×y…",
)

private val STATE_SAMPLES = listOf(
    "Rajasthan", "rajasthan", "RAJASTHAN", "Orissa", "Odisha", "Jammu & Kashmir",
    "Jammu and Kashmir", "tamil-nadu", "Tamilnadu", "Pondicherry", "New Delhi", "NCT of Delhi",
    "Atlantis", "", "   ", "Dadra & Nagar Haveli",
)

private val COORD_SAMPLES = listOf(
    68.2060 to 6.7560, 97.3940 to 37.0820, 77.2090 to 28.6139, 83.9812 to 21.4669,
    72.8777 to 19.0760, 0.0 to 0.0, -12.5 to 45.25,
)

private fun coordinateDigest(lines: List<Polyline>): String {
    val buffer = java.io.ByteArrayOutputStream()
    val out = java.io.DataOutputStream(buffer)
    for (line in lines) {
        out.writeInt(line.size / 2)
        for (value in line) out.writeDouble(value)
    }
    return fnv1a(buffer.toByteArray())
}

private fun scalarLines(): List<String> {
    val out = ArrayList<String>()

    out.add("render_dpi\t${dbits(RENDER_DPI)}")
    out.add("pixels_per_mm\t${dbits(PIXELS_PER_MM)}")
    for (mm in MM_SAMPLES) out.add("pixels_for_mm:${dbits(mm)}\t${pixelsForMm(mm)}")

    for (scale in 1..3) {
        out.add("text_height:$scale\t${textHeight(scale)}")
        for (text in TEXT_SAMPLES) {
            out.add("text_width:$scale:$text\t${textWidth(text, scale)}")
            for (width in intArrayOf(0, 20, 60, 140)) {
                out.add("ellipsise:$scale:$width:$text\t${ellipsise(text, width, scale)}")
            }
        }
    }

    for (hexed in listOf("1F3864", "#2F5496", "  b8c4d9  ", "zzzzzz", "12345", "", "FFFFFF", "000000")) {
        out.add("rgb_of:$hexed\t${rgbTuple(rgbOf(hexed, rgb(7, 8, 9)))}")
    }
    for (amount in doubleArrayOf(0.0, 0.1, 0.25, 0.5, 0.82, 1.0, -1.0, 2.0)) {
        out.add("mix:${dbits(amount)}\t${rgbTuple(mix(rgb(31, 56, 100), PAPER, amount))}")
    }

    for (value in NUMBER_SAMPLES) out.add("format_number:${dbits(value)}\t${formatNumber(value)}")
    for ((span, ticks) in STEP_SAMPLES) {
        out.add("nice_step:${dbits(span)}:$ticks\t${dbits(niceStep(span, ticks))}")
    }
    for (values in listOf(
        listOf(0.0), listOf(5.0), listOf(-3.0, 9.0), listOf(0.4), listOf(1e6, 2e6),
        listOf(-1.0, -2.0), listOf(0.0, 0.0),
    )) {
        val (low, high, step) = axisBounds(values)
        val key = values.joinToString(",") { dbits(it) }
        out.add("axis_bounds:$key\t${dbits(low)} ${dbits(high)} ${dbits(step)}")
    }

    out.add("view_width\t${dbits(VIEW_WIDTH)}")
    out.add("view_height\t${dbits(VIEW_HEIGHT)}")
    out.add("padding\t${dbits(PADDING)}")
    out.add("longitude_scale\t${dbits(LONGITUDE_SCALE)}")
    out.add("units_per_degree\t${dbits(UNITS_PER_DEGREE)}")
    out.add("units_per_kilometre\t${dbits(unitsPerKilometre())}")
    for ((lon, lat) in COORD_SAMPLES) {
        out.add("project:${dbits(lon)}:${dbits(lat)}\t${dbits(projectX(lon))} ${dbits(projectY(lat))}")
    }

    for (name in STATE_SAMPLES) out.add("canonical_state:$name\t${canonicalState(name) ?: ""}")

    for ((kind, lines) in listOf(
        "outline" to BoundaryAssets.indiaRings(),
        "state" to BoundaryAssets.stateBorders(),
        "district" to BoundaryAssets.districtBorders(),
    )) {
        out.add("geometry_records:$kind\t${lines.size}")
        out.add("geometry_points:$kind\t${lines.sumOf { it.size / 2 }}")
        out.add("geometry_digest:$kind\t${coordinateDigest(lines)}")
    }

    return out
}

// --------------------------------------------------------------------------------------
// Running it
// --------------------------------------------------------------------------------------

private fun compareFigure(oracle: File, name: String, png: ByteArray, expected: List<String>) {
    val decoded = decodePng(png)
    expect("$name/ihdr", expected.subList(1, 5).joinToString(" "),
        "${decoded.width} ${decoded.height} ${decoded.depth} ${decoded.colour}")
    expect("$name/pixels", expected[5], sha256(decoded.rows))
    // The pixel digest already decides pass or fail; the palette is compared so a failure says whether
    // the two images differ in COLOUR or only in where the colours landed.
    expect("$name/palette", File(oracle, "$name.pal").readText(Charsets.UTF_8), histogram(decoded.rows))
    // And the raw grid, so a mismatch can be located rather than merely detected.
    val expectedRows = File(oracle, "$name.rgb").readBytes()
    if (!expectedRows.contentEquals(decoded.rows)) {
        var at = -1
        for (i in decoded.rows.indices) {
            if (i >= expectedRows.size || expectedRows[i] != decoded.rows[i]) { at = i; break }
        }
        if (at >= 0) {
            val pixel = at / 3
            failures.add(
                "$name/first-differing-pixel at (${pixel % decoded.width}, ${pixel / decoded.width})" +
                    " channel ${at % 3}: oracle ${expectedRows[at].toInt() and 0xFF}," +
                    " kotlin ${decoded.rows[at].toInt() and 0xFF}"
            )
        }
    }
}

fun main(args: Array<String>) {
    val oracle = File(args[0])
    val repo = File(args[1])

    val androidAssets = File(repo, "android/app/src/main/assets/boundaries")
    val webAssets = File(repo, "frontend/public/boundaries")
    val rawRes = File(repo, "android/app/src/main/res/raw")

    // THE SHIPPED COPY MUST BE THE FILE THE SERVER READ. The Python oracle read the boundary text out
    // of frontend/public; the APK carries its own copy. If a regenerated boundary set updates one and
    // not the other, every geometry digest below would still agree with whichever copy this harness
    // happened to read, and the divergence would only ever appear in a report. Check the bytes.
    for (name in listOf("state-borders.txt", "district-borders.txt")) {
        checks += 1
        val shipped = File(androidAssets, name)
        val web = File(webAssets, name)
        if (!shipped.isFile || !web.isFile || !shipped.readBytes().contentEquals(web.readBytes())) {
            failures.add("asset/$name: the APK copy and frontend/public/boundaries differ")
        }
    }

    BoundaryAssets.install { name ->
        val candidate = when (name) {
            "india_outline.bin", "state_borders.bin", "district_borders.bin" -> File(rawRes, name)
            else -> File(androidAssets, name)
        }
        if (candidate.isFile) candidate.readBytes() else null
    }
    check(BoundaryAssets.available()) { "the boundary assets are not readable from $repo" }

    val index = File(oracle, "index.tsv").readLines()
        .filter { it.isNotBlank() }
        .associate { line -> line.split("\t").let { it[0] to it } }

    val canvas = Raster(200, 260)
    primitivesScene(canvas)
    compareFigure(oracle, "primitives", canvas.toPng(), index.getValue("primitives"))

    for (case in CHART_CASES) {
        val figure = renderChartPng(case.block, THEMES.getValue(case.theme), case.widthPx)
        expect("${case.name}/reported-size", index.getValue(case.name).subList(1, 3).joinToString(" "),
            "${figure.widthPx} ${figure.heightPx}")
        compareFigure(oracle, case.name, figure.png, index.getValue(case.name))
    }

    for (case in MAP_CASES) {
        val figure = renderMapPng(case.block, THEMES.getValue(case.theme), case.widthPx)
            ?: error("${case.name} rendered null with assets present")
        expect("${case.name}/reported-size", index.getValue(case.name).subList(1, 3).joinToString(" "),
            "${figure.widthPx} ${figure.heightPx}")
        compareFigure(oracle, case.name, figure.png, index.getValue(case.name))
    }

    val expectedScalars = File(oracle, "scalars.tsv").readLines().filter { it.isNotEmpty() }
    val actualScalars = scalarLines()
    expect("scalars/count", expectedScalars.size.toString(), actualScalars.size.toString())
    for (i in 0 until minOf(expectedScalars.size, actualScalars.size)) {
        val a = expectedScalars[i]
        val b = actualScalars[i]
        checks += 1
        if (a != b) failures.add("scalar line ${i + 1}\n    oracle: $a\n    kotlin: $b")
    }

    println("checks: $checks")
    if (failures.isEmpty()) {
        println("PARITY OK — every figure, palette and scalar matches the Python oracle")
    } else {
        println("PARITY FAILED — ${failures.size} mismatch(es)")
        // Grouped first. Twenty-five raw mismatches scroll the interesting one off the screen, and
        // what a reader needs before any of them is "which of the three modules drifted".
        println("  by kind:")
        failures.map { it.substringBefore('\n').substringAfterLast('/') }
            .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
            .forEach { (kind, count) -> println("    $kind: $count") }
        for (failure in failures.take(25)) println("  * $failure")
        if (failures.size > 25) println("  ... and ${failures.size - 25} more")
        kotlin.system.exitProcess(1)
    }
}
