package com.designprototype.workshop.data

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

enum class DwQrEccLevel(val formatBits: Int) {
    L(0b01),
    M(0b00),
    Q(0b11),
    H(0b10),
}

enum class DwQrRefusal {
    NOT_ALPHANUMERIC,

    TOO_LONG,

    EMPTY,
}

class DwQrEncodeException(
    val reason: DwQrRefusal,
    message: String,
) : IllegalArgumentException(message)

class DwQrSymbol(
    val matrix: Array<BooleanArray>,
    val size: Int,
    val version: Int,
    val level: DwQrEccLevel,
    val mask: Int,
)

class DwQrSvg(
    val path: String,
    val extent: Int,
    val symbol: DwQrSymbol,
)

object DwQrEncode {

    private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

    const val MIN_VERSION = 1
    const val MAX_VERSION = 6

    private val TOTAL_CODEWORDS = intArrayOf(26, 44, 70, 100, 134, 172)

    private val ALIGNMENT_CENTRES = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
    )

    private val BLOCKS: Map<DwQrEccLevel, List<IntArray>> = mapOf(
        DwQrEccLevel.L to listOf(
            intArrayOf(7, 1, 19, 0, 0),
            intArrayOf(10, 1, 34, 0, 0),
            intArrayOf(15, 1, 55, 0, 0),
            intArrayOf(20, 1, 80, 0, 0),
            intArrayOf(26, 1, 108, 0, 0),
            intArrayOf(18, 2, 68, 0, 0),
        ),
        DwQrEccLevel.M to listOf(
            intArrayOf(10, 1, 16, 0, 0),
            intArrayOf(16, 1, 28, 0, 0),
            intArrayOf(26, 1, 44, 0, 0),
            intArrayOf(18, 2, 32, 0, 0),
            intArrayOf(24, 2, 43, 0, 0),
            intArrayOf(16, 4, 27, 0, 0),
        ),
        DwQrEccLevel.Q to listOf(
            intArrayOf(13, 1, 13, 0, 0),
            intArrayOf(22, 1, 22, 0, 0),
            intArrayOf(18, 2, 17, 0, 0),
            intArrayOf(26, 2, 24, 0, 0),
            intArrayOf(18, 2, 15, 2, 16),
            intArrayOf(24, 4, 19, 0, 0),
        ),
        DwQrEccLevel.H to listOf(
            intArrayOf(17, 1, 9, 0, 0),
            intArrayOf(28, 1, 16, 0, 0),
            intArrayOf(22, 2, 13, 0, 0),
            intArrayOf(16, 4, 9, 0, 0),
            intArrayOf(22, 2, 11, 2, 12),
            intArrayOf(28, 4, 15, 0, 0),
        ),
    )

    private val REMAINDER_BITS = intArrayOf(0, 7, 7, 7, 7, 7)

    internal fun blocks(version: Int, level: DwQrEccLevel): IntArray = BLOCKS.getValue(level)[version - 1]

    internal fun totalCodewords(version: Int): Int = TOTAL_CODEWORDS[version - 1]

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) GF_EXP[i] = GF_EXP[i - 255]
    }

    private fun gfMultiply(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    private fun generatorPolynomial(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMultiply(poly[j], GF_EXP[i])
            }
            poly = next
        }
        return poly
    }

    private fun errorCorrectionFor(data: IntArray, ecCount: Int): IntArray {
        val generator = generatorPolynomial(ecCount)
        val remainder = IntArray(ecCount)
        for (byte in data) {
            val factor = byte xor remainder[0]
            System.arraycopy(remainder, 1, remainder, 0, ecCount - 1)
            remainder[ecCount - 1] = 0
            if (factor != 0) {
                for (i in 0 until ecCount) remainder[i] = remainder[i] xor gfMultiply(generator[i + 1], factor)
            }
        }
        return remainder
    }

    private class Bits {
        private val bits = ArrayList<Boolean>(256)

        val size: Int get() = bits.size

        fun put(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 == 1)
        }

        fun toBytes(): IntArray {
            val out = IntArray((bits.size + 7) / 8)
            for ((index, bit) in bits.withIndex()) {
                if (bit) out[index ushr 3] = out[index ushr 3] or (0x80 ushr (index and 7))
            }
            return out
        }
    }

    fun isAlphanumeric(text: String): Boolean {
        for (character in text) {
            if (!ALPHANUMERIC.contains(character)) return false
        }
        return true
    }

    fun capacity(version: Int, level: DwQrEccLevel): Int {
        val (_, g1Blocks, g1Data, g2Blocks, g2Data) = blocks(version, level)
        val dataBits = (g1Blocks * g1Data + g2Blocks * g2Data) * 8 - 4 - 9
        val pairs = dataBits / 11
        return pairs * 2 + if (dataBits - pairs * 11 >= 6) 1 else 0
    }

    private fun newMatrix(size: Int): Array<BooleanArray> = Array(size) { BooleanArray(size) }

    private fun drawFinder(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, top: Int, left: Int) {
        val size = matrix.size
        for (row in -1..7) {
            for (column in -1..7) {
                val r = top + row
                val c = left + column
                if (r < 0 || r >= size || c < 0 || c >= size) continue
                val ring = max(abs(row - 3), abs(column - 3))
                matrix[r][c] = ring != 2 && ring <= 3
                reserved[r][c] = true
            }
        }
    }

    private fun drawFunctionPatterns(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, version: Int) {
        val size = matrix.size

        drawFinder(matrix, reserved, 0, 0)
        drawFinder(matrix, reserved, 0, size - 7)
        drawFinder(matrix, reserved, size - 7, 0)

        for (i in 8 until size - 8) {
            val dark = i % 2 == 0
            matrix[6][i] = dark
            reserved[6][i] = true
            matrix[i][6] = dark
            reserved[i][6] = true
        }

        val centres = ALIGNMENT_CENTRES[version - 1]
        for (centreRow in centres) {
            for (centreColumn in centres) {
                val onFinder =
                    (centreRow == 6 && centreColumn == 6) ||
                        (centreRow == 6 && centreColumn == size - 7) ||
                        (centreRow == size - 7 && centreColumn == 6)
                if (onFinder) continue
                for (row in -2..2) {
                    for (column in -2..2) {
                        matrix[centreRow + row][centreColumn + column] = max(abs(row), abs(column)) != 1
                        reserved[centreRow + row][centreColumn + column] = true
                    }
                }
            }
        }

        for (i in 0..8) {
            if (i != 6) {
                reserved[8][i] = true
                reserved[i][8] = true
            }
        }
        for (i in 0 until 8) {
            reserved[8][size - 1 - i] = true
            reserved[size - 1 - i][8] = true
        }
        matrix[size - 8][8] = true
        reserved[size - 8][8] = true
    }

    private fun formatBits(level: DwQrEccLevel, mask: Int): Int {
        val data = (level.formatBits shl 3) or mask
        var remainder = data
        for (i in 0 until 10) remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537)
        return (((data shl 10) or remainder) xor 0x5412) and 0x7FFF
    }

    private fun drawFormat(matrix: Array<BooleanArray>, level: DwQrEccLevel, mask: Int) {
        val size = matrix.size
        val bits = formatBits(level, mask)
        fun bit(index: Int): Boolean = ((bits ushr index) and 1) == 1

        for (i in 0..5) matrix[i][8] = bit(i)
        matrix[7][8] = bit(6)
        matrix[8][8] = bit(7)
        matrix[8][7] = bit(8)
        for (i in 9 until 15) matrix[8][14 - i] = bit(i)

        for (i in 0 until 8) matrix[8][size - 1 - i] = bit(i)
        for (i in 8 until 15) matrix[size - 15 + i][8] = bit(i)
    }

    private fun maskInverts(mask: Int, row: Int, column: Int): Boolean = when (mask) {
        0 -> (row + column) % 2 == 0
        1 -> row % 2 == 0
        2 -> column % 3 == 0
        3 -> (row + column) % 3 == 0
        4 -> (row / 2 + column / 3) % 2 == 0
        5 -> ((row * column) % 2) + ((row * column) % 3) == 0
        6 -> (((row * column) % 2) + ((row * column) % 3)) % 2 == 0
        else -> (((row + column) % 2) + ((row * column) % 3)) % 2 == 0
    }

    internal fun penalty(matrix: Array<BooleanArray>): Int {
        val size = matrix.size
        var score = 0

        for (outer in 0 until size) {
            for (horizontal in listOf(true, false)) {
                var runValue = if (horizontal) matrix[outer][0] else matrix[0][outer]
                var runLength = 1
                for (inner in 1 until size) {
                    val value = if (horizontal) matrix[outer][inner] else matrix[inner][outer]
                    if (value == runValue) {
                        runLength++
                        continue
                    }
                    if (runLength >= 5) score += runLength - 2
                    runValue = value
                    runLength = 1
                }
                if (runLength >= 5) score += runLength - 2
            }
        }

        for (row in 0 until size - 1) {
            for (column in 0 until size - 1) {
                val value = matrix[row][column]
                if (value == matrix[row][column + 1] &&
                    value == matrix[row + 1][column] &&
                    value == matrix[row + 1][column + 1]
                ) {
                    score += 3
                }
            }
        }

        val forward = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val backward = forward.reversedArray()
        for (outer in 0 until size) {
            for (horizontal in listOf(true, false)) {
                var start = 0
                while (start + 11 <= size) {
                    var matchesForward = true
                    var matchesBackward = true
                    for (offset in 0 until 11) {
                        val value = if (horizontal) matrix[outer][start + offset] else matrix[start + offset][outer]
                        if (value != forward[offset]) matchesForward = false
                        if (value != backward[offset]) matchesBackward = false
                    }
                    if (matchesForward) score += 40
                    if (matchesBackward) score += 40
                    start++
                }
            }
        }

        var dark = 0
        for (row in matrix) for (value in row) if (value) dark++
        val percent = (dark * 100.0) / (size * size)
        score += floor(abs(percent - 50.0) / 5.0).toInt() * 10

        return score
    }

    private fun buildCodewords(text: String, version: Int, level: DwQrEccLevel): IntArray {
        val (ecPerBlock, g1Blocks, g1Data, g2Blocks, g2Data) = blocks(version, level)
        val totalData = g1Blocks * g1Data + g2Blocks * g2Data

        val buffer = Bits()
        buffer.put(0b0010, 4)
        buffer.put(text.length, 9)
        var i = 0
        while (i + 1 < text.length) {
            buffer.put(ALPHANUMERIC.indexOf(text[i]) * 45 + ALPHANUMERIC.indexOf(text[i + 1]), 11)
            i += 2
        }
        if (text.length % 2 == 1) buffer.put(ALPHANUMERIC.indexOf(text[text.length - 1]), 6)

        buffer.put(0, minOf(4, totalData * 8 - buffer.size))
        if (buffer.size % 8 != 0) buffer.put(0, 8 - (buffer.size % 8))
        val bytes = buffer.toBytes()
        val data = IntArray(totalData)
        bytes.copyInto(data, 0, 0, minOf(bytes.size, totalData))
        for (index in bytes.size until totalData) data[index] = if ((index - bytes.size) % 2 == 0) 0xEC else 0x11

        val dataBlocks = ArrayList<IntArray>(g1Blocks + g2Blocks)
        val ecBlocks = ArrayList<IntArray>(g1Blocks + g2Blocks)
        var offset = 0
        for (block in 0 until g1Blocks + g2Blocks) {
            val length = if (block < g1Blocks) g1Data else g2Data
            val slice = data.copyOfRange(offset, offset + length)
            offset += length
            dataBlocks.add(slice)
            ecBlocks.add(errorCorrectionFor(slice, ecPerBlock))
        }

        val out = IntArray(totalCodewords(version))
        var written = 0
        val longestData = max(g1Data, g2Data)
        for (index in 0 until longestData) {
            for (block in dataBlocks) if (index < block.size) out[written++] = block[index]
        }
        for (index in 0 until ecPerBlock) {
            for (block in ecBlocks) out[written++] = block[index]
        }
        return out
    }

    private fun drawCodewords(
        matrix: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        codewords: IntArray,
        version: Int,
    ) {
        val size = matrix.size
        val totalBits = codewords.size * 8 + REMAINDER_BITS[version - 1]
        var bitIndex = 0

        var right = size - 1
        while (right >= 1) {
            val rightColumn = if (right <= 6) right - 1 else right
            for (vertical in 0 until size) {
                for (offset in 0 until 2) {
                    val column = rightColumn - offset
                    val upward = ((rightColumn + 1) and 2) == 0
                    val row = if (upward) size - 1 - vertical else vertical
                    if (reserved[row][column]) continue
                    if (bitIndex >= totalBits) continue
                    val byte = bitIndex ushr 3
                    matrix[row][column] =
                        byte < codewords.size && ((codewords[byte] ushr (7 - (bitIndex and 7))) and 1) == 1
                    bitIndex++
                }
            }
            right -= 2
        }
    }

    fun buildMatrix(text: String, version: Int, level: DwQrEccLevel, mask: Int): Array<BooleanArray> {
        val size = 17 + 4 * version
        val matrix = newMatrix(size)
        val reserved = newMatrix(size)
        drawFunctionPatterns(matrix, reserved, version)
        drawCodewords(matrix, reserved, buildCodewords(text, version, level), version)
        for (row in 0 until size) {
            for (column in 0 until size) {
                if (!reserved[row][column] && maskInverts(mask, row, column)) matrix[row][column] = !matrix[row][column]
            }
        }
        drawFormat(matrix, level, mask)
        return matrix
    }

    fun encode(text: String, level: DwQrEccLevel = DwQrEccLevel.Q): DwQrSymbol {
        if (text.isEmpty()) throw DwQrEncodeException(DwQrRefusal.EMPTY, "There is nothing to encode.")
        if (!isAlphanumeric(text)) {
            throw DwQrEncodeException(
                DwQrRefusal.NOT_ALPHANUMERIC,
                "This code contains characters a compact QR symbol cannot carry. Codes are upper-case letters, " +
                    "digits and the punctuation the standard allows.",
            )
        }

        var version = 0
        for (candidate in MIN_VERSION..MAX_VERSION) {
            if (text.length <= capacity(candidate, level)) {
                version = candidate
                break
            }
        }
        if (version == 0) {
            throw DwQrEncodeException(
                DwQrRefusal.TOO_LONG,
                "This code is ${text.length} characters, and the largest symbol this app draws holds " +
                    "${capacity(MAX_VERSION, level)}.",
            )
        }

        var best: Array<BooleanArray>? = null
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val candidate = buildMatrix(text, version, level, mask)
            val score = penalty(candidate)
            if (score < bestScore) {
                bestScore = score
                best = candidate
                bestMask = mask
            }
        }

        return DwQrSymbol(
            matrix = checkNotNull(best) { "no mask was scored, which cannot happen" },
            size = 17 + 4 * version,
            version = version,
            level = level,
            mask = bestMask,
        )
    }

    fun svgPath(symbol: DwQrSymbol, quiet: Int = 4): DwQrSvg {
        val parts = StringBuilder()
        for (row in 0 until symbol.size) {
            var column = 0
            while (column < symbol.size) {
                if (!symbol.matrix[row][column]) {
                    column++
                    continue
                }
                var run = 1
                while (column + run < symbol.size && symbol.matrix[row][column + run]) run++
                parts.append("M").append(column + quiet).append(" ").append(row + quiet)
                    .append("h").append(run).append("v1h-").append(run).append("z")
                column += run
            }
        }
        return DwQrSvg(path = parts.toString(), extent = symbol.size + quiet * 2, symbol = symbol)
    }
}
