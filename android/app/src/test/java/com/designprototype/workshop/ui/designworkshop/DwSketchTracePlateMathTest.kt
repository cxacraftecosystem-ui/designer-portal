package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor
import kotlin.random.Random

class DwSketchTracePlateMathTest {

    private fun portalReference(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        width: Int,
        height: Int,
    ): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val y0 = floor((y.toDouble() * sourceHeight) / height).toInt()
            val y1 = maxOf(y0 + 1, floor(((y + 1).toDouble() * sourceHeight) / height).toInt())
            for (x in 0 until width) {
                val x0 = floor((x.toDouble() * sourceWidth) / width).toInt()
                val x1 = maxOf(x0 + 1, floor(((x + 1).toDouble() * sourceWidth) / width).toInt())
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var n = 0
                for (sy in y0 until y1) {
                    var index = (sy * sourceWidth + x0) * 4
                    for (sx in x0 until x1) {
                        r += (source[index].toInt() and 0xFF).toDouble()
                        g += (source[index + 1].toInt() and 0xFF).toDouble()
                        b += (source[index + 2].toInt() and 0xFF).toDouble()
                        index += 4
                        n += 1
                    }
                }
                out[y * width + x] = (0xFF shl 24) or
                    (toUint8Clamp(r / n) shl 16) or
                    (toUint8Clamp(g / n) shl 8) or
                    toUint8Clamp(b / n)
            }
        }
        return out
    }

    private fun toUint8Clamp(value: Double): Int {
        if (value.isNaN()) return 0
        if (value <= 0.0) return 0
        if (value >= 255.0) return 255
        val f = floor(value)
        if (f + 0.5 < value) return (f + 1).toInt()
        if (value < f + 0.5) return f.toInt()
        return if (f.toInt() % 2 == 1) (f + 1).toInt() else f.toInt()
    }

    @Test
    fun `the photograph plate is the portal's box filter, pixel for pixel`() {
        val sourceWidth = 37
        val sourceHeight = 23
        val random = Random(20260827)
        val source = ByteArray(sourceWidth * sourceHeight * 4)
        for (i in source.indices) source[i] = random.nextInt(256).toByte()

        val plateWidth = 11
        val plateHeight = 7
        val expected = portalReference(source, sourceWidth, sourceHeight, plateWidth, plateHeight)

        val row = IntArray(plateWidth)
        for (py in 0 until plateHeight) {
            dwTraceResampleRow(source, sourceWidth, sourceHeight, plateWidth, plateHeight, py, row)
            for (px in 0 until plateWidth) {
                assertEquals(
                    "row $py column $px disagrees with comparisonPlates.fillBand",
                    expected[py * plateWidth + px],
                    row[px],
                )
            }
        }
    }

    @Test
    fun `an average landing exactly on a half rounds to the even neighbour`() {
        assertEquals(128, dwTraceClampedAverage(127L + 128L, 2))
        assertEquals(128, dwTraceClampedAverage(128L + 129L, 2))
        assertNotEquals(
            "truncation is the mistake this case exists to catch",
            127,
            dwTraceClampedAverage(127L + 128L, 2),
        )
        assertEquals(127, dwTraceClampedAverage(127L * 3, 3))
        assertEquals(0, dwTraceClampedAverage(0L, 4))
        assertEquals(255, dwTraceClampedAverage(255L * 4, 4))
    }

    @Test
    fun `an empty box is black`() {
        assertEquals(0, dwTraceClampedAverage(100L, 0))
    }

    @Test
    fun `the difference is the absolute per-channel difference and is opaque`() {
        val photograph = intArrayOf(0xFF204060.toInt(), 0x00FFFFFF)
        val trace = intArrayOf(0xFF10FF00.toInt(), 0xFFFFFFFF.toInt())
        val out = IntArray(2)
        dwTraceDifferenceRow(photograph, trace, out, 2)

        assertEquals(0xFF10BF60.toInt(), out[0])
        assertEquals(0xFF000000.toInt(), out[1])
    }

    @Test
    fun `two identical pictures differ by nothing`() {
        val same = intArrayOf(0xFF123456.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val out = IntArray(3)
        dwTraceDifferenceRow(same, same, out, 3)
        for (value in out) assertEquals(0xFF000000.toInt(), value)
    }

    @Test
    fun `the difference does not depend on which picture is subtracted from which`() {
        val a = intArrayOf(0xFF204060.toInt())
        val b = intArrayOf(0xFF10FF00.toInt())
        val forwards = IntArray(1)
        val backwards = IntArray(1)
        dwTraceDifferenceRow(a, b, forwards, 1)
        dwTraceDifferenceRow(b, a, backwards, 1)
        assertEquals(forwards[0], backwards[0])
    }

    @Test
    fun `the reduction sentence carries both sizes and is silent when there was none`() {
        assertEquals(
            "Both pictures here are 1024×768, reduced from 4096×3072 for the comparison only.",
            dwTraceComparisonReduction(1024, 768, 4096, 3072),
        )
        assertEquals("", dwTraceComparisonReduction(1024, 768, 1024, 768))
    }

    @Test
    fun `each way of having no comparison says something different`() {
        val current = dwTraceComparisonStatus(true, running = false, failed = false, plateRefusal = "", hasResult = true)
        val updating = dwTraceComparisonStatus(true, running = true, failed = false, plateRefusal = "", hasResult = true)
        val refused = dwTraceComparisonStatus(false, running = false, failed = false, plateRefusal = "no room", hasResult = true)
        val tracing = dwTraceComparisonStatus(false, running = true, failed = false, plateRefusal = "", hasResult = false)
        val failed = dwTraceComparisonStatus(false, running = false, failed = true, plateRefusal = "", hasResult = false)
        val nothing = dwTraceComparisonStatus(false, running = false, failed = false, plateRefusal = "", hasResult = false)

        assertEquals("a current comparison needs no caption at all", "", current)
        assertEquals("a plate refusal is carried through untouched", "no room", refused)

        val spoken = listOf(updating, refused, tracing, failed, nothing)
        assertEquals("two absences share a sentence", spoken.size, spoken.toSet().size)
        for (sentence in spoken) assertTrue("an absence must say something", sentence.isNotBlank())

        assertTrue("the failed branch must point at the reason: $failed", failed.contains("above"))
        assertTrue("the updating branch must explain the dead controls: $updating", updating.contains("controls"))
    }

    @Test
    fun `a plate refusal promises the drawing is still attachable`() {
        for (sentence in listOf(
            DW_TRACE_PLATE_MEMORY_REFUSAL,
            dwTraceSentence(DwTraceFailureKind.FRAME_MISMATCH),
            DW_TRACE_DIFFERENCE_REFUSAL,
        )) {
            assertTrue("must say the drawing survived: $sentence", sentence.contains("unaffected"))
            assertTrue("must be a sentence: $sentence", sentence.trim().endsWith("."))
        }
        assertTrue(dwTraceSentence(DwTraceFailureKind.FRAME_MISMATCH).contains("Rectify the page"))
        assertTrue(
            "the old sentence said nothing had been attached, which is no longer true",
            !dwTraceSentence(DwTraceFailureKind.FRAME_MISMATCH).contains("nothing has been attached"),
        )
    }

    private fun portalSentence(name: String): String {
        val file = File("../../frontend/components/sketches/upload/comparisonPlates.ts")
        assertTrue(
            "expected the portal's comparison strings at ${file.absolutePath}. This test is the only " +
                "mechanical check that the two clients describe the difference plate in the same " +
                "words; if the tree moved, fix the path rather than deleting the assertion.",
            file.exists(),
        )
        val source = file.readText(Charsets.UTF_8)
        val declaration = "export const $name"
        val at = source.indexOf(declaration)
        assertTrue("the portal no longer declares $name", at >= 0)
        val end = source.indexOf(';', at)
        assertTrue("the portal's $name has no terminator", end > at)
        val pieces = Regex("\"([^\"]*)\"").findAll(source.substring(at, end)).map { it.groupValues[1] }.toList()
        assertTrue("the portal's $name holds no text", pieces.isNotEmpty())
        return pieces.joinToString("")
    }

    @Test
    fun `the portal says exactly what the handset says about the difference plate`() {
        assertEquals(DW_TRACE_DIFFERENCE_NOTE, portalSentence("COMPARISON_DIFFERENCE_NOTE"))
        assertEquals(DW_TRACE_DIFFERENCE_PENDING, portalSentence("COMPARISON_DIFFERENCE_PENDING"))
        assertEquals(DW_TRACE_DIFFERENCE_DESCRIPTION, portalSentence("COMPARISON_DIFFERENCE_ALT"))
        assertEquals(DW_TRACE_DIFFERENCE_LABEL, portalSentence("COMPARISON_DIFFERENCE_BADGE"))

        val portalRefusal = portalSentence("COMPARISON_DIFFERENCE_REFUSAL")
        assertEquals(
            DW_TRACE_DIFFERENCE_REFUSAL.replace("This phone", "This browser"),
            portalRefusal,
        )
        assertNotEquals(
            "the refusal is supposed to name the device, so these two cannot be identical",
            DW_TRACE_DIFFERENCE_REFUSAL,
            portalRefusal,
        )
    }
}
