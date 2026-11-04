package com.designprototype.workshop.report

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal typealias Rgb = Int

internal fun rgb(r: Int, g: Int, b: Int): Rgb = (r shl 16) or (g shl 8) or b

internal fun Rgb.red(): Int = (this ushr 16) and 0xFF

internal fun Rgb.green(): Int = (this ushr 8) and 0xFF

internal fun Rgb.blue(): Int = this and 0xFF

internal val PAPER: Rgb = rgb(255, 255, 255)

internal const val RENDER_DPI = 200.0
internal const val PIXELS_PER_MM = RENDER_DPI / 25.4

internal fun pixelsForMm(millimetres: Double): Int =
    max(240, min(2400, Math.rint(millimetres * PIXELS_PER_MM).toInt()))

private const val FONT_ASCII =
    "0000000000" +
        "00005f0000" +
        "0007000700" +
        "147f147f14" +
        "242a7f2a12" +
        "2313086462" +
        "3649552250" +
        "0005030000" +
        "001c224100" +
        "0041221c00" +
        "14083e0814" +
        "08083e0808" +
        "0050300000" +
        "0808080808" +
        "0060600000" +
        "2010080402" +
        "3e5149453e" +
        "00427f4000" +
        "4261514946" +
        "2141454b31" +
        "1814127f10" +
        "2745454539" +
        "3c4a494930" +
        "0171090503" +
        "3649494936" +
        "064949291e" +
        "0036360000" +
        "0056360000" +
        "0814224100" +
        "1414141414" +
        "0041221408" +
        "0201510906" +
        "324979413e" +
        "7e1111117e" +
        "7f49494936" +
        "3e41414122" +
        "7f4141221c" +
        "7f49494941" +
        "7f09090901" +
        "3e4149497a" +
        "7f0808087f" +
        "00417f4100" +
        "2040413f01" +
        "7f08142241" +
        "7f40404040" +
        "7f020c027f" +
        "7f0408107f" +
        "3e4141413e" +
        "7f09090906" +
        "3e4151215e" +
        "7f09192946" +
        "4649494931" +
        "01017f0101" +
        "3f4040403f" +
        "1f2040201f" +
        "3f4038403f" +
        "6314081463" +
        "0708700807" +
        "6151494543" +
        "007f414100" +
        "0204081020" +
        "0041417f00" +
        "0402010204" +
        "4040404040" +
        "0001020400" +
        "2054545478" +
        "7f48444438" +
        "3844444420" +
        "384444487f" +
        "3854545418" +
        "087e090102" +
        "1864646438" +
        "7f08040478" +
        "00447d4000" +
        "2040443d00" +
        "7f10284400" +
        "00417f4000" +
        "7c04180478" +
        "7c08040478" +
        "3844444438" +
        "7c14141408" +
        "081414187c" +
        "7c08040408" +
        "4854545420" +
        "043f444020" +
        "3c4040207c" +
        "1c2040201c" +
        "3c4030403c" +
        "4428102844" +
        "0c5050503c" +
        "4464544c44" +
        "0008364100" +
        "00007f0000" +
        "0041360800" +
        "1008081008"

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
    put('₹', intArrayOf(0x45, 0x25, 0x15, 0x0D, 0x07))
    put('•', intArrayOf(0x00, 0x1C, 0x1C, 0x1C, 0x00))
}

private val SUBSTITUTES: Map<Char, String> = mapOf(
    '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
    '–' to "-", '—' to "-", '−' to "-", ' ' to " ",
    '×' to "x", '…' to "...",
)

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

internal fun textWidth(text: String, scale: Int = 1): Int {
    val drawn = drawable(text)
    if (drawn.isEmpty()) return 0
    return (drawn.length * GLYPH_ADVANCE - 1) * scale
}

internal fun textHeight(scale: Int = 1): Int = GLYPH_H * scale

internal fun ellipsise(text: String, maxWidth: Int, scale: Int = 1): String {
    val drawn = drawable(text)
    if (textWidth(drawn, scale) <= maxWidth) return drawn
    for (cut in drawn.length - 1 downTo 1) {
        val candidate = drawn.substring(0, cut).trimEnd() + "..."
        if (textWidth(candidate, scale) <= maxWidth) return candidate
    }
    return ""
}

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
            val cover = min(row + 1.0, y + h) - max(row.toDouble(), y)
            if (cover <= 0) continue
            span(row, x, x + w, colour, alpha * min(1.0, cover))
        }
    }

    fun fillPolygons(rings: List<DoubleArray>, colour: Rgb, alpha: Double = 1.0) {
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
                if (y0 == y1) continue
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
                if (edge[1] <= centre) continue
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

    fun strokePolyline(
        points: DoubleArray,
        colour: Rgb,
        thickness: Double = 1.0,
        alpha: Double = 1.0,
    ) {
        val half = max(0.35, thickness / 2.0)
        val count = points.size / 2
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
        if (half > 0.7) {
            for (index in 1 until count - 1) {
                rect(
                    points[index * 2] - half, points[index * 2 + 1] - half,
                    half * 2, half * 2, colour, alpha,
                )
            }
        }
    }

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
                    val angle = ((atan2(dy, dx) - start) % TAU + TAU) % TAU
                    if (angle > sweep && min(angle - sweep, TAU - angle) * distance > 0.5) continue
                }
                var cover = min(1.0, outer + 0.5 - distance)
                if (inner > 0) cover = min(cover, distance - inner + 0.5)
                blend(column, row, colour, max(0.0, min(1.0, cover)) * alpha)
            }
        }
    }

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

    fun floodFill(x: Int, y: Int, target: Rgb, replacement: Rgb, limit: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        if (pixelAt(x, y) != target || target == replacement) return 0

        val seen = BooleanArray(width * height)
        val found = ArrayList<Int>()
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

    fun toPng(): ByteArray = encodePng(width, height, pixels)
}

internal const val TAU = 6.283185307179586

internal class RasterFigure(val png: ByteArray, val widthPx: Int, val heightPx: Int)

internal fun encodePng(width: Int, height: Int, rgbRows: ByteArray): ByteArray {
    val stride = width * 3
    val raw = ByteArray(height * (stride + 1))
    for (row in 0 until height) {
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
        deflater.end()
    }

    val out = ByteArrayOutputStream(compressed.size() + 128)
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val ihdr = ByteArrayOutputStream(13)
    writeBigEndianInt(ihdr, width)
    writeBigEndianInt(ihdr, height)
    ihdr.write(8)
    ihdr.write(2)
    ihdr.write(0)
    ihdr.write(0)
    ihdr.write(0)
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
    val crc = CRC32()
    crc.update(payload)
    writeBigEndianInt(out, crc.value.toInt())
}

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

internal fun mix(a: Rgb, b: Rgb, amount: Double): Rgb {
    val t = max(0.0, min(1.0, amount))
    return rgb(
        (a.red() + (b.red() - a.red()) * t + 0.5).toInt(),
        (a.green() + (b.green() - a.green()) * t + 0.5).toInt(),
        (a.blue() + (b.blue() - a.blue()) * t + 0.5).toInt(),
    )
}
