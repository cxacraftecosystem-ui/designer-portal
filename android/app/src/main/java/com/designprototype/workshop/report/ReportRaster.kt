package com.designprototype.workshop.report

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * A pure-Kotlin RGB raster canvas and PNG encoder — a port of
 * `backend/app/services/report_raster.py`, line for line and constant for constant.
 *
 * WHY THIS EXISTS RATHER THAN android.graphics. The obvious thing on this surface is a Bitmap and a
 * Canvas: they are already linked in, [PdfWriter] already uses them, and they would draw a map in a
 * tenth of the code below. They are also the reason this file is NOT written that way. Skia's
 * anti-aliasing, its scan conversion rule, its rounding of a fractional span and its handling of a
 * self-intersecting polygon are all Skia's, and none of them are Python's. A map drawn through
 * Canvas would be *a* correct map, and it would differ from the server's in a few thousand pixels,
 * and the difference would be invisible until somebody laid the .docx the office downloaded beside
 * the .docx the designer generated in the field and found the two coastlines a hair apart. This
 * module reproduces the server's arithmetic instead, which is what makes those two files the same
 * picture rather than two pictures of the same thing.
 *
 * It is also what makes the port PROVABLE. Everything here is integer and double arithmetic over a
 * flat byte array with no platform dependency at all — no android.*, no Bitmap, nothing that needs a
 * device — so the identical source compiles and runs on a bare JVM against the Python module used as
 * an oracle. A Canvas-based renderer could only ever be eyeballed.
 *
 * The output is always 8-bit truecolour PNG, no interlacing, filter type 0 on every row. That is the
 * one PNG shape [probeImageSize] reads on the way back in, the one Word embeds without transcoding,
 * and the one BitmapFactory decodes with no optional codec. Emitting a palette or a 16-bit image
 * would save bytes and cost a picture that silently does not appear in the .docx.
 *
 * Three things in here are less obvious than they look, all three carried over from the Python:
 *
 * ANTI-ALIASING IS HORIZONTAL ONLY. A polygon span is measured to a fraction of a pixel across the
 * scanline and blended at both ends, but each output row is sampled once, at its centre. A
 * near-horizontal coastline therefore keeps one-pixel stair steps. At the resolution the map is
 * rendered (1000 px across a country, printed about 125 mm wide, so roughly 200 dpi) a one-pixel
 * step is 0.13 mm and invisible on paper — whereas vertical supersampling would triple the work on
 * an export a designer is standing in a field waiting for, on a phone.
 *
 * INTERIOR SPANS ARE WRITTEN WITH A FILL, NOT A PER-PIXEL LOOP. `java.util.Arrays.fill` over the
 * three-byte run is the equivalent of the Python's slice assignment, and the per-pixel path exists
 * only for the two fractional pixels at each end of a span. Filling India naively is seconds of work
 * even on the JVM; this is milliseconds.
 *
 * THE FONT IS A TABLE, NOT A RENDERER. Five columns by seven rows per glyph, ASCII only. Indic text
 * cannot be drawn here at all — there is no shaping engine and no Devanagari outline in a
 * five-by-seven cell — so [Raster.drawText] DROPS what it cannot draw rather than printing a row of
 * boxes. Every label the map and the charts place is a place name, an enum label or a number, all of
 * which the registry stores in Latin script; a craft's local name is never drawn onto an image, it is
 * printed as real text by the document renderers, which have Nirmala UI and a Noto fallback.
 */

/**
 * One colour, packed `0xRRGGBB` into an Int.
 *
 * A data class of three Ints would read closer to the Python's tuple and would also allocate on
 * every blend — and a blend happens once per anti-aliased pixel, which for the map's 300-odd
 * coastline rings is in the low millions. A packed Int is the same information with no allocation
 * and no equality method to get subtly wrong.
 */
internal typealias Rgb = Int

internal fun rgb(r: Int, g: Int, b: Int): Rgb = (r shl 16) or (g shl 8) or b

internal fun Rgb.red(): Int = (this ushr 16) and 0xFF

internal fun Rgb.green(): Int = (this ushr 8) and 0xFF

internal fun Rgb.blue(): Int = this and 0xFF

internal val PAPER: Rgb = rgb(255, 255, 255)

// --------------------------------------------------------------------------------------
// How big a figure is
// --------------------------------------------------------------------------------------

/**
 * Pixels per millimetre of printed width, for every figure this package rasterises.
 *
 * 200 dpi, chosen against both ends of the failure it sits between. Below about 150 the five-by-seven
 * labels break up on a laser printer and a reader cannot tell "3-6" from "36" on a chart axis; above
 * about 300 the phone is filling four times the pixels for a difference no printer in a district
 * office can resolve, on an export the designer is waiting on. Both renderers multiply the block's
 * `widthPct` by their own text column and then by this, so the .docx and the .pdf of one workshop
 * rasterise the same figure at the same size instead of each choosing — which is the same rule the
 * rest of the model follows and the reason no block carries pixels.
 *
 * Must equal `report_raster.RENDER_DPI`. A different value here is not a quality difference, it is
 * two documents for one workshop whose figures are different sizes.
 */
internal const val RENDER_DPI = 200.0
internal const val PIXELS_PER_MM = RENDER_DPI / 25.4

/**
 * The pixel width a figure printed [millimetres] wide should be rasterised at.
 *
 * Clamped at both ends. A figure narrower than 240 px cannot carry a legible label at all, and one
 * wider than 2400 px is a megabyte of PNG embedded in a document nobody will print larger than A4 —
 * a sixty-figure archival report would carry sixty of them, and on a mid-range phone that is the
 * difference between an export and an OutOfMemoryError.
 *
 * `Math.rint`, NOT `Math.round` and NOT `kotlin.math.round`. Python's `round()` on a float breaks a
 * tie to the EVEN integer; both of the other two break it AWAY FROM ZERO, so a figure whose width
 * lands exactly on x.5 px would come out one pixel wider here than on the server — and one pixel of
 * width is a different PNG, a different layout of every label inside it, and a parity failure nobody
 * can find. `kotlin.math.rint` does not exist; the JVM intrinsic is the only spelling that works, and
 * every `Math.rint` in this package is there for this reason and must not be "modernised".
 */
internal fun pixelsForMm(millimetres: Double): Int =
    max(240, min(2400, Math.rint(millimetres * PIXELS_PER_MM).toInt()))

// --------------------------------------------------------------------------------------
// The five-by-seven font
// --------------------------------------------------------------------------------------

/*
 * Columns per glyph, least significant bit at the TOP row. The classic 5x7 terminal face, which is in
 * the public domain and is the smallest thing that stays legible when a 1000-pixel image is printed
 * 125 mm wide. Stored as hex rather than as a nested list because 95 glyphs of five integers each is
 * four hundred lines of noise in a file whose subject is not typography.
 *
 * This string must stay byte-identical to `report_raster._FONT_ASCII`. One wrong nibble is one glyph
 * that differs between the server's figure and the phone's, which is exactly the kind of divergence
 * nobody notices until a reader compares two printouts of the same chart.
 */
private const val FONT_ASCII =
    "0000000000" + // (space)
        "00005f0000" + // !
        "0007000700" + // "
        "147f147f14" + // #
        "242a7f2a12" + // $
        "2313086462" + // %
        "3649552250" + // &
        "0005030000" + // '
        "001c224100" + // (
        "0041221c00" + // )
        "14083e0814" + // *
        "08083e0808" + // +
        "0050300000" + // ,
        "0808080808" + // -
        "0060600000" + // .
        "2010080402" + // /
        "3e5149453e" + // 0
        "00427f4000" + // 1
        "4261514946" + // 2
        "2141454b31" + // 3
        "1814127f10" + // 4
        "2745454539" + // 5
        "3c4a494930" + // 6
        "0171090503" + // 7
        "3649494936" + // 8
        "064949291e" + // 9
        "0036360000" + // :
        "0056360000" + // ;
        "0814224100" + // <
        "1414141414" + // =
        "0041221408" + // >
        "0201510906" + // ?
        "324979413e" + // @
        "7e1111117e" + // A
        "7f49494936" + // B
        "3e41414122" + // C
        "7f4141221c" + // D
        "7f49494941" + // E
        "7f09090901" + // F
        "3e4149497a" + // G
        "7f0808087f" + // H
        "00417f4100" + // I
        "2040413f01" + // J
        "7f08142241" + // K
        "7f40404040" + // L
        "7f020c027f" + // M
        "7f0408107f" + // N
        "3e4141413e" + // O
        "7f09090906" + // P
        "3e4151215e" + // Q
        "7f09192946" + // R
        "4649494931" + // S
        "01017f0101" + // T
        "3f4040403f" + // U
        "1f2040201f" + // V
        "3f4038403f" + // W
        "6314081463" + // X
        "0708700807" + // Y
        "6151494543" + // Z
        "007f414100" + // [
        "0204081020" + // backslash
        "0041417f00" + // ]
        "0402010204" + // ^
        "4040404040" + // _
        "0001020400" + // `
        "2054545478" + // a
        "7f48444438" + // b
        "3844444420" + // c
        "384444487f" + // d
        "3854545418" + // e
        "087e090102" + // f
        // g, REDRAWN one row lower than the face this table otherwise copies. The classic cell puts
        // the bowl on rows 1-4 while every other lowercase sits on rows 2-6, and at the two-pixel
        // scale a map label is drawn at, the result is read as a digit: the workshop venue printed
        // as "Khara9pur" and the cost head as "Packa9in9". Aligning the bowl with 'a' and 'o' and
        // taking the tail to the last row costs nothing and makes a place name a place name.
        "1864646438" + // g
        "7f08040478" + // h
        "00447d4000" + // i
        "2040443d00" + // j
        "7f10284400" + // k
        "00417f4000" + // l
        "7c04180478" + // m
        "7c08040478" + // n
        "3844444438" + // o
        "7c14141408" + // p
        "081414187c" + // q
        "7c08040408" + // r
        "4854545420" + // s
        "043f444020" + // t
        "3c4040207c" + // u
        "1c2040201c" + // v
        "3c4030403c" + // w
        "4428102844" + // x
        "0c5050503c" + // y
        "4464544c44" + // z
        "0008364100" + // {
        "00007f0000" + // |
        "0041360800" + // }
        "1008081008" //   ~

/** Width and height of one glyph cell, before scaling. One column of blank separates two glyphs. */
internal const val GLYPH_W = 5
internal const val GLYPH_H = 7
internal const val GLYPH_ADVANCE = 6

private val GLYPHS: Map<Char, IntArray> = HashMap<Char, IntArray>(128).apply {
    for (index in 0 until 95) {
        val hex = FONT_ASCII.substring(index * 10, (index + 1) * 10)
        put(
            (32 + index).toChar(),
            IntArray(5) { column -> hex.substring(column * 2, column * 2 + 2).toInt(16) },
        )
    }
    // The rupee sign, drawn by hand: two horizontal strokes and the descending leg. Every cost chart
    // in this report is money, and a currency symbol replaced by nothing turns "₹ 4,200" into
    // "4,200" on a figure whose axis then claims no unit at all.
    put('₹', intArrayOf(0x45, 0x25, 0x15, 0x0D, 0x07))
    put('•', intArrayOf(0x00, 0x1C, 0x1C, 0x1C, 0x00))
}

/*
 * Typographic characters a label routinely carries that have an exact ASCII stand-in in a
 * five-by-seven cell. Substituting is strictly better than dropping: "Artisan's" reads, "Artisans"
 * does not, and an en dash silently removed turns "3–6 months" into "36 months".
 */
private val SUBSTITUTES: Map<Char, String> = mapOf(
    '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
    '–' to "-", '—' to "-", '−' to "-", ' ' to " ",
    '×' to "x", '…' to "...",
)

/** [text] reduced to the characters this font can actually draw. */
private fun drawable(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) {
        val replacement = SUBSTITUTES[ch]
        if (replacement != null) {
            out.append(replacement)
        } else if (GLYPHS.containsKey(ch)) {
            out.append(ch)
        }
    }
    return out.toString()
}

/** Pixel width of [text] once undrawable characters are removed. */
internal fun textWidth(text: String, scale: Int = 1): Int {
    val drawn = drawable(text)
    if (drawn.isEmpty()) return 0
    return (drawn.length * GLYPH_ADVANCE - 1) * scale
}

internal fun textHeight(scale: Int = 1): Int = GLYPH_H * scale

/**
 * [text] shortened with a trailing ellipsis until it fits [maxWidth] pixels.
 *
 * A label that overruns its cell is not a cosmetic problem on a raster: there is no clipping region
 * here, so it would be drawn straight over the neighbouring bar's number and the reader would see two
 * figures overlapping with no way to tell which belonged to which.
 */
internal fun ellipsise(text: String, maxWidth: Int, scale: Int = 1): String {
    val drawn = drawable(text)
    if (textWidth(drawn, scale) <= maxWidth) return drawn
    for (cut in drawn.length - 1 downTo 1) {
        val candidate = drawn.substring(0, cut).trimEnd() + "..."
        if (textWidth(candidate, scale) <= maxWidth) return candidate
    }
    return ""
}

// --------------------------------------------------------------------------------------
// The canvas
// --------------------------------------------------------------------------------------

/**
 * An RGB image being drawn into, and the PNG it becomes.
 *
 * Three bytes per pixel in one flat array, row-major, no alpha channel and no stride padding — the
 * same layout `report_raster.Raster` keeps in its `bytearray`, so [toPng] can hand the buffer
 * straight to the encoder and the two implementations can be compared byte for byte.
 */
internal class Raster(width: Int, height: Int, background: Rgb = PAPER) {

    val width: Int = max(1, width)
    val height: Int = max(1, height)
    val pixels: ByteArray = ByteArray(this.width * this.height * 3)

    init {
        val r = background.red().toByte()
        val g = background.green().toByte()
        val b = background.blue().toByte()
        var offset = 0
        val end = pixels.size
        while (offset < end) {
            pixels[offset] = r
            pixels[offset + 1] = g
            pixels[offset + 2] = b
            offset += 3
        }
    }

    // -- primitives ---------------------------------------------------------------------

    /** Composite [colour] over one pixel at [alpha] coverage. Out-of-bounds is a no-op. */
    fun blend(x: Int, y: Int, colour: Rgb, alpha: Double) {
        if (alpha <= 0.0 || x < 0 || y < 0 || x >= width || y >= height) return
        val offset = (y * width + x) * 3
        if (alpha >= 1.0) {
            pixels[offset] = colour.red().toByte()
            pixels[offset + 1] = colour.green().toByte()
            pixels[offset + 2] = colour.blue().toByte()
            return
        }
        val inverse = 1.0 - alpha
        // `and 0xFF` on every read, because a JVM byte is SIGNED. Without it every channel above 127 —
        // which is most of a white page — arrives as a negative number, the blend produces a negative
        // result, and the paper behind a half-covered coastline comes out black. Python's bytearray is
        // unsigned and has no such trap, so this is one of the few lines of the port that has to be
        // written rather than copied.
        val br = pixels[offset].toInt() and 0xFF
        val bg = pixels[offset + 1].toInt() and 0xFF
        val bb = pixels[offset + 2].toInt() and 0xFF
        pixels[offset] = (br * inverse + colour.red() * alpha + 0.5).toInt().toByte()
        pixels[offset + 1] = (bg * inverse + colour.green() * alpha + 0.5).toInt().toByte()
        pixels[offset + 2] = (bb * inverse + colour.blue() * alpha + 0.5).toInt().toByte()
    }

    fun pixelAt(x: Int, y: Int): Rgb {
        val offset = (y * width + x) * 3
        return rgb(
            pixels[offset].toInt() and 0xFF,
            pixels[offset + 1].toInt() and 0xFF,
            pixels[offset + 2].toInt() and 0xFF,
        )
    }

    /**
     * Fill one scanline between two fractional x positions.
     *
     * The two end pixels are blended by how much of them the span actually covers; everything between
     * is a straight run of stores, which is where nearly all of the speed comes from and which matches
     * the Python's slice assignment exactly — both write the colour with no blending at all when alpha
     * is 1, so a fully opaque interior pixel is bit-identical on the two surfaces rather than merely
     * close.
     */
    fun span(y: Int, xFrom: Double, xTo: Double, colour: Rgb, alpha: Double = 1.0) {
        if (y < 0 || y >= height || xTo <= xFrom) return
        val from = max(0.0, xFrom)
        val to = min(width.toDouble(), xTo)
        if (to <= from) return
        val first = from.toInt()
        val last = to.toInt()
        if (first == last) {
            blend(first, y, colour, (to - from) * alpha)
            return
        }
        blend(first, y, colour, (first + 1 - from) * alpha)
        if (last < width) blend(last, y, colour, (to - last) * alpha)
        if (last > first + 1) {
            if (alpha >= 1.0) {
                val r = colour.red().toByte()
                val g = colour.green().toByte()
                val b = colour.blue().toByte()
                var offset = (y * width + first + 1) * 3
                val end = offset + (last - first - 1) * 3
                while (offset < end) {
                    pixels[offset] = r
                    pixels[offset + 1] = g
                    pixels[offset + 2] = b
                    offset += 3
                }
            } else {
                for (x in first + 1 until last) blend(x, y, colour, alpha)
            }
        }
    }

    fun rect(x: Double, y: Double, w: Double, h: Double, colour: Rgb, alpha: Double = 1.0) {
        val top = max(0, y.toInt())
        val bottom = min(height, (y + h + 0.999).toInt())
        for (row in top until bottom) {
            // Vertical coverage of this row by the rectangle, so a bar whose top lands mid-pixel does
            // not jump a whole pixel when its value changes by one unit.
            val cover = min(row + 1.0, y + h) - max(row.toDouble(), y)
            if (cover <= 0) continue
            span(row, x, x + w, colour, alpha * min(1.0, cover))
        }
    }

    // -- polygons -----------------------------------------------------------------------

    /**
     * Even-odd scanline fill of any number of closed rings, in ONE pass.
     *
     * All rings are filled together rather than one at a time, and that is what makes a hole a hole:
     * the outline of India carries 308 polygons and one interior ring, and filling each ring
     * separately would paint the hole in solid.
     *
     * Edges are bucketed by their first scanline and an active list is carried down the image, so the
     * cost is proportional to the number of crossings rather than to rings x scanlines. On a phone
     * that is not an optimisation, it is the difference between an export and an ANR: the naive form
     * tests all 11,649 coastline edges against every one of ~1,100 scanlines.
     */
    fun fillPolygons(rings: List<DoubleArray>, colour: Rgb, alpha: Double = 1.0) {
        // Each edge is four doubles — top y, bottom y, x at top, dx/dy — in a flat array rather than a
        // class, because the coastline produces tens of thousands of them for one figure and each
        // object header is 16 bytes of a phone's heap that buys nothing.
        val buckets = HashMap<Int, MutableList<DoubleArray>>()
        var yMin = height
        var yMax = 0
        for (ring in rings) {
            val count = ring.size / 2
            if (count < 3) continue
            for (index in 0 until count) {
                var x0 = ring[index * 2]
                var y0 = ring[index * 2 + 1]
                val next = (index + 1) % count
                var x1 = ring[next * 2]
                var y1 = ring[next * 2 + 1]
                if (y0 == y1) continue // a horizontal edge crosses no scanline centre
                if (y0 > y1) {
                    val tx = x0
                    val ty = y0
                    x0 = x1
                    y0 = y1
                    x1 = tx
                    y1 = ty
                }
                val first = max((y0 + 0.5).toInt(), 0)
                if (first >= height || y1 <= 0) continue
                buckets.getOrPut(first) { ArrayList() }
                    .add(doubleArrayOf(y0, y1, x0, (x1 - x0) / (y1 - y0)))
                if (first < yMin) yMin = first
                val lastRow = min(height - 1, (y1 + 0.5).toInt())
                if (lastRow > yMax) yMax = lastRow
            }
        }
        if (yMin > yMax) return

        var active = ArrayList<DoubleArray>()
        val crossings = ArrayList<Double>()
        for (row in yMin..yMax) {
            buckets.remove(row)?.let { active.addAll(it) }
            val centre = row + 0.5
            crossings.clear()
            val still = ArrayList<DoubleArray>(active.size)
            for (edge in active) {
                val top = edge[0]
                if (edge[1] <= centre) continue // finished above this scanline; drop it
                still.add(edge)
                if (top <= centre) crossings.add(edge[2] + (centre - top) * edge[3])
            }
            active = still
            if (crossings.size < 2) continue
            crossings.sort()
            var index = 0
            while (index < crossings.size - 1) {
                span(row, crossings[index], crossings[index + 1], colour, alpha)
                index += 2
            }
        }
    }

    // -- strokes ------------------------------------------------------------------------

    /**
     * Draw an OPEN polyline of the given thickness, as flat `x, y, x, y…` pairs.
     *
     * Each segment becomes a quadrilateral fed through the polygon filler, which is what gives the
     * line the same anti-aliasing the fills have. NEVER CLOSED: a state border is a run between two
     * junctions, not a ring, and closing one would draw a straight segment from its end back to its
     * start — a line straight across the interior of the country, laid over three other states and
     * indistinguishable at a glance from a real border. All 81 state polylines would grow one.
     */
    fun strokePolyline(
        points: DoubleArray,
        colour: Rgb,
        thickness: Double = 1.0,
        alpha: Double = 1.0,
    ) {
        val half = max(0.35, thickness / 2.0)
        val count = points.size / 2
        // One quad reused for every segment. A border file is 6,350 points; allocating a fresh array
        // and a fresh singleton list per segment is 12,700 short-lived objects per figure, which on a
        // phone is a GC pause in the middle of an export.
        val quad = DoubleArray(8)
        val single = listOf(quad)
        for (index in 0 until count - 1) {
            val x0 = points[index * 2]
            val y0 = points[index * 2 + 1]
            val x1 = points[index * 2 + 2]
            val y1 = points[index * 2 + 3]
            val dx = x1 - x0
            val dy = y1 - y0
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1e-9) continue
            val nx = -dy / length * half
            val ny = dx / length * half
            quad[0] = x0 + nx
            quad[1] = y0 + ny
            quad[2] = x1 + nx
            quad[3] = y1 + ny
            quad[4] = x1 - nx
            quad[5] = y1 - ny
            quad[6] = x0 - nx
            quad[7] = y0 - ny
            fillPolygons(single, colour, alpha)
        }
        // A butt-ended segment leaves a notch at every bend, and at a bend sharper than a right angle
        // the notch is a visible hole in the border. A square at each interior vertex is the cheapest
        // join that closes it.
        if (half > 0.7) {
            for (index in 1 until count - 1) {
                rect(
                    points[index * 2] - half, points[index * 2 + 1] - half,
                    half * 2, half * 2, colour, alpha,
                )
            }
        }
    }

    /** A filled circle, anti-aliased by exact horizontal extent per scanline. */
    fun disc(cx: Double, cy: Double, radius: Double, colour: Rgb, alpha: Double = 1.0) {
        if (radius <= 0) return
        val top = max(0, (cy - radius).toInt())
        val bottom = min(height, (cy + radius).toInt() + 1)
        for (row in top until bottom) {
            val dy = row + 0.5 - cy
            if (abs(dy) >= radius) continue
            val half = sqrt(radius * radius - dy * dy)
            span(row, cx - half, cx + half, colour, alpha)
        }
    }

    /**
     * An annular sector — the primitive both the pie and the donut are made of.
     *
     * [inner] of zero gives a pie slice. Drawn by testing each pixel's radius and angle rather than by
     * tessellating the arc, because a tessellated arc with too few segments shows flat spots on the
     * rim and with too many costs more than the test does.
     */
    fun ring(
        cx: Double,
        cy: Double,
        outer: Double,
        inner: Double,
        colour: Rgb,
        start: Double = 0.0,
        sweep: Double = TAU,
        alpha: Double = 1.0,
    ) {
        if (outer <= 0 || sweep <= 0) return
        val top = max(0, (cy - outer).toInt() - 1)
        val bottom = min(height, (cy + outer).toInt() + 2)
        val left = max(0, (cx - outer).toInt() - 1)
        val right = min(width, (cx + outer).toInt() + 2)
        val full = sweep >= 6.283185307179585
        for (row in top until bottom) {
            val dy = row + 0.5 - cy
            for (column in left until right) {
                val dx = column + 0.5 - cx
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > outer + 0.5 || distance < inner - 0.5) continue
                if (!full) {
                    // Kotlin's `%` takes the sign of the DIVIDEND where Python's takes the sign of the
                    // divisor, so any pixel whose angle came out negative would stay negative here,
                    // fail the sweep test below, and leave a hole through every slice that crosses
                    // three o'clock. The extra `+ TAU) % TAU` is Python's modulo written out, and it
                    // is the single most easily missed line in this whole port.
                    val angle = ((atan2(dy, dx) - start) % TAU + TAU) % TAU
                    // Outside the sweep, and further outside than the half pixel the two straight
                    // radial edges are feathered by — without the feather a thin slice comes out with
                    // a hard staircase along its own boundary.
                    if (angle > sweep && min(angle - sweep, TAU - angle) * distance > 0.5) continue
                }
                // Coverage from the two curved edges only; good enough at this radius and much cheaper
                // than supersampling a disc.
                var cover = min(1.0, outer + 0.5 - distance)
                if (inner > 0) cover = min(cover, distance - inner + 0.5)
                blend(column, row, colour, max(0.0, min(1.0, cover)) * alpha)
            }
        }
    }

    // -- text ---------------------------------------------------------------------------

    /** Draw [text] with its TOP-LEFT at ([x], [y]); returns the width drawn. */
    fun drawText(x: Int, y: Int, text: String, colour: Rgb, scale: Int = 1): Int {
        val drawn = drawable(text)
        if (drawn.isEmpty()) return 0
        var cursor = x
        for (character in drawn) {
            val columns = GLYPHS[character]
            if (columns != null) {
                for (columnIndex in columns.indices) {
                    val bits = columns[columnIndex]
                    if (bits == 0) continue
                    for (rowIndex in 0 until GLYPH_H) {
                        if (bits and (1 shl rowIndex) != 0) {
                            rect(
                                (cursor + columnIndex * scale).toDouble(),
                                (y + rowIndex * scale).toDouble(),
                                scale.toDouble(), scale.toDouble(), colour,
                            )
                        }
                    }
                }
            }
            cursor += GLYPH_ADVANCE * scale
        }
        return cursor - x - scale
    }

    fun drawTextCentred(cx: Int, y: Int, text: String, colour: Rgb, scale: Int = 1) {
        drawText(cx - textWidth(text, scale) / 2, y, text, colour, scale)
    }

    fun drawTextRight(right: Int, y: Int, text: String, colour: Rgb, scale: Int = 1) {
        drawText(right - textWidth(text, scale), y, text, colour, scale)
    }

    // -- flood fill ---------------------------------------------------------------------

    /**
     * Replace the connected run of [target] pixels reaching ([x], [y]), or NOTHING AT ALL.
     *
     * Nothing is written until the whole region is known, and the region is abandoned when it exceeds
     * [limit] pixels. That is the entire safety of highlighting a state on this map. The state borders
     * are derived from a district source whose outer extent differs from the national outline's by up
     * to about two kilometres, so a border run can stop just short of the coast; when it does, the
     * fill escapes through the gap and colours the whole subcontinent in the highlight colour, in a
     * report submitted to a ministry. Discovering first and committing second turns that from "the map
     * is wrong" into "one state is not tinted" — which [renderMapPng] then prints on the figure rather
     * than leaving the reader to attribute it to the data.
     */
    fun floodFill(x: Int, y: Int, target: Rgb, replacement: Rgb, limit: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        if (pixelAt(x, y) != target || target == replacement) return 0

        val seen = BooleanArray(width * height)
        val found = ArrayList<Int>()
        // An explicit stack, not recursion. A state is on the order of a million pixels at this
        // resolution and a recursive flood fill of that depth is a StackOverflowError on the 1 MB
        // stack an Android worker thread gets — an export that dies rather than a state that is not
        // tinted, which is the exact trade the budget above exists to avoid.
        val stack = ArrayList<Int>()
        val tr = target.red().toByte()
        val tg = target.green().toByte()
        val tb = target.blue().toByte()

        stack.add(y * width + x)
        seen[y * width + x] = true
        while (stack.isNotEmpty()) {
            val index = stack.removeAt(stack.size - 1)
            val offset = index * 3
            if (pixels[offset] != tr || pixels[offset + 1] != tg || pixels[offset + 2] != tb) continue
            found.add(index)
            if (found.size > limit) return 0
            val row = index / width
            val column = index % width
            if (column > 0 && !seen[index - 1]) {
                seen[index - 1] = true
                stack.add(index - 1)
            }
            if (column + 1 < width && !seen[index + 1]) {
                seen[index + 1] = true
                stack.add(index + 1)
            }
            if (row > 0 && !seen[index - width]) {
                seen[index - width] = true
                stack.add(index - width)
            }
            if (row + 1 < height && !seen[index + width]) {
                seen[index + width] = true
                stack.add(index + width)
            }
        }

        val rr = replacement.red().toByte()
        val rg = replacement.green().toByte()
        val rb = replacement.blue().toByte()
        for (index in found) {
            val offset = index * 3
            pixels[offset] = rr
            pixels[offset + 1] = rg
            pixels[offset + 2] = rb
        }
        return found.size
    }

    /** How many pixels are exactly this colour — used to size a flood-fill budget. */
    fun countColour(colour: Rgb): Int {
        val r = colour.red().toByte()
        val g = colour.green().toByte()
        val b = colour.blue().toByte()
        var total = 0
        var offset = 0
        val end = pixels.size
        while (offset < end) {
            if (pixels[offset] == r && pixels[offset + 1] == g && pixels[offset + 2] == b) total++
            offset += 3
        }
        return total
    }

    // -- output -------------------------------------------------------------------------

    /**
     * Encode as an 8-bit truecolour PNG.
     *
     * Filter type 0 on every row. A real encoder would try the five filters per row and keep the
     * smallest; the maps and charts here are large flats and thin strokes, which Deflate already
     * compresses well, and the filter search would cost more phone time than the bytes it saves are
     * worth on a file that is embedded once.
     */
    fun toPng(): ByteArray = encodePng(width, height, pixels)
}

/** One turn. Spelled out rather than `2 * PI` so it is the same literal the Python carries. */
internal const val TAU = 6.283185307179586

/**
 * A rasterised figure: the PNG and the pixel size it was drawn at.
 *
 * The size travels WITH the bytes rather than being probed back out of them. Both writers need it to
 * work out the aspect ratio of the box the picture goes in, and re-deriving it by parsing the IHDR of
 * a file this process wrote thirty microseconds ago is a decode that can fail — at which point the
 * figure is silently dropped from the report for no reason a log would ever explain. It is the same
 * `(png, width_px, height_px)` triple `report_map.render_map_png` and `report_chart.render_chart_png`
 * return on the server.
 */
internal class RasterFigure(val png: ByteArray, val widthPx: Int, val heightPx: Int)

/**
 * A PNG from raw RGB bytes.
 *
 * Deflate at level 6 with the default strategy and a zlib wrapper, matching `zlib.compress(data, 6)`
 * on the server so the two surfaces embed figures of comparable size in comparable time.
 *
 * THE IDAT BYTES ARE NOT THE PARITY SURFACE, AND CANNOT BE. Measured on the backend's own interpreter,
 * CPython here links zlib-ng (`zlib.ZLIB_RUNTIME_VERSION == "1.3.1.zlib-ng"`) while the JVM links
 * stock zlib; on one representative scanline stream they produced 11,531 and 12,188 bytes for
 * identical input. Both are valid Deflate and both decode to the same pixels, but they are different
 * bytes — and so are two CPython builds that differ in which zlib they were compiled against. Any
 * test that pinned the PNG bytes would therefore be pinning the backend's build of Python, and would
 * go red on a machine where nothing about this report changed.
 *
 * What the two surfaces MUST agree on to the last bit is the decoded picture: the IHDR fields and the
 * RGB grid underneath. That is the pair `backend/tools/report_figure_oracle.py` dumps and
 * `backend/tools/kotlin_figure_harness/` recomputes and compares, and it is the pair a reader can
 * actually see. Run those two after touching anything in this file, [ReportChart] or [ReportMap];
 * `tamper_oracle.py` beside the harness is the control that proves the comparison still bites.
 */
internal fun encodePng(width: Int, height: Int, rgbRows: ByteArray): ByteArray {
    val stride = width * 3
    val raw = ByteArray(height * (stride + 1))
    for (row in 0 until height) {
        // Filter type 0 (None) is already the zero this array was allocated with, so only the row's
        // pixels are copied in after it.
        System.arraycopy(rgbRows, row * stride, raw, row * (stride + 1) + 1, stride)
    }

    val deflater = Deflater(6)
    val compressed = ByteArrayOutputStream(raw.size / 4 + 64)
    try {
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) compressed.write(buffer, 0, n)
        }
    } finally {
        // A Deflater owns a native zlib stream that the garbage collector will not reclaim promptly.
        // A sixty-figure archival report that leaked one per figure exhausts native memory long before
        // the JVM heap notices anything is wrong, and the crash lands nowhere near this file.
        deflater.end()
    }

    val out = ByteArrayOutputStream(compressed.size() + 128)
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val ihdr = ByteArrayOutputStream(13)
    writeBigEndianInt(ihdr, width)
    writeBigEndianInt(ihdr, height)
    ihdr.write(8) // bit depth
    ihdr.write(2) // colour type 2: truecolour RGB, the one shape probeImageSize reads back
    ihdr.write(0) // compression method: deflate
    ihdr.write(0) // filter method 0
    ihdr.write(0) // no interlace
    writeChunk(out, "IHDR", ihdr.toByteArray())
    writeChunk(out, "IDAT", compressed.toByteArray())
    writeChunk(out, "IEND", ByteArray(0))
    return out.toByteArray()
}

private fun writeBigEndianInt(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 24) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
}

private fun writeChunk(out: ByteArrayOutputStream, tag: String, data: ByteArray) {
    writeBigEndianInt(out, data.size)
    val payload = ByteArray(4 + data.size)
    for (i in 0 until 4) payload[i] = tag[i].code.toByte()
    System.arraycopy(data, 0, payload, 4, data.size)
    out.write(payload, 0, payload.size)
    // The CRC covers the TYPE and the DATA but not the length. Getting that wrong produces a file
    // every decoder rejects, which on this path means a report whose every figure is a broken-image
    // box — so it is worth saying out loud rather than trusting the order of two arguments.
    val crc = CRC32()
    crc.update(payload)
    writeBigEndianInt(out, crc.value.toInt())
}

// --------------------------------------------------------------------------------------
// Colour helpers
// --------------------------------------------------------------------------------------

/** `"1F3864"` -> `0x1F3864`. A theme colour that is not six hex digits degrades to [fallback]. */
internal fun rgbOf(hexColour: String?, fallback: Rgb = 0): Rgb {
    var text = (hexColour ?: "").trim()
    while (text.startsWith("#")) text = text.substring(1)
    if (text.length != 6) return fallback
    return try {
        rgb(
            text.substring(0, 2).toInt(16),
            text.substring(2, 4).toInt(16),
            text.substring(4, 6).toInt(16),
        )
    } catch (_: NumberFormatException) {
        fallback
    }
}

/** Linear mix, [amount] of [b] into [a]. */
internal fun mix(a: Rgb, b: Rgb, amount: Double): Rgb {
    val t = max(0.0, min(1.0, amount))
    return rgb(
        (a.red() + (b.red() - a.red()) * t + 0.5).toInt(),
        (a.green() + (b.green() - a.green()) * t + 0.5).toInt(),
        (a.blue() + (b.blue() - a.blue()) * t + 0.5).toInt(),
    )
}
