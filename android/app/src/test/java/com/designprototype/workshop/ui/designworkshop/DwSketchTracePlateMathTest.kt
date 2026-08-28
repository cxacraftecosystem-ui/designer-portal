package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor
import kotlin.random.Random

/**
 * **THE COMPARISON HAD NO TESTS ON THIS CLIENT AT ALL, AND IT IS THE SURFACE THAT JUDGES THE OTHERS.**
 *
 * Everything else in the trace feature announces its failures: a dead sandbox throws, a bad bundle
 * refuses, a malformed envelope is caught by `DwTraceGeometry.validate`. The comparator's arithmetic
 * does not. An aliased photograph plate looks like a photograph, and the designer looking at it
 * concludes that the TRACE dropped the strokes the reduction dropped — so the one control that exists
 * to catch a bad trace starts producing bad verdicts, quietly, in a direction nobody would think to
 * check.
 *
 * The Compose surface stays out of reach — there is no Robolectric in this module, so
 * `android.graphics` cannot be touched from here, and that is an accepted constraint rather than a
 * defect. What is reachable is everything that was moved into `DwSketchTracePlateMath.kt` for exactly
 * this purpose, and it is checked the way `DwSketchTraceWireTest` checks the marshalling: **against
 * the other client's own arithmetic, transcribed here, rather than against whatever this port printed
 * the day it was written.**
 */
class DwSketchTracePlateMathTest {

    /* ── the box filter, against the portal's own ───────────────────────────────────────────── */

    /**
     * `comparisonPlates.fillBand`, transcribed line for line, including how JavaScript stores a byte.
     *
     * The subtle half is the last four lines of that function: `out[at] = r / n` assigns a
     * non-integer into a `Uint8ClampedArray`, and that is not a truncation — ECMA-262's `ToUint8Clamp`
     * ROUNDS, and rounds a tie to the even neighbour. A port that used integer division would be one
     * count low on every box whose average lands above a half, on every pixel of every plate.
     *
     * @return packed opaque ARGB, so it can be compared against [dwTraceResampleRow] directly.
     */
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

    /** ECMA-262 `ToUint8Clamp`, which is what writing into a `Uint8ClampedArray` does. */
    private fun toUint8Clamp(value: Double): Int {
        if (value.isNaN()) return 0
        if (value <= 0.0) return 0
        if (value >= 255.0) return 255
        val f = floor(value)
        if (f + 0.5 < value) return (f + 1).toInt()
        if (value < f + 0.5) return f.toInt()
        return if (f.toInt() % 2 == 1) (f + 1).toInt() else f.toInt()
    }

    /**
     * The whole plate, against the portal's, on a reduction with no whole-number ratio in either axis.
     *
     * 37x23 down to 11x7 is deliberate: every box is a different size, the bands do not tile evenly,
     * and the last row and column are the ones that run off the end if the far edge is stepped rather
     * than recomputed. A seeded generator so a failure is reproducible from the message alone.
     */
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

    /**
     * The tie rule, on its own, because it is the half a reader would not think to check.
     *
     * Two source pixels averaging 127.5 must come back 128 and two averaging 128.5 must come back 128
     * — that is round-half-to-even, and it is what a browser does. Integer division would give 127 and
     * 128, so the first of these is the assertion that fails if somebody "simplifies" this later.
     */
    @Test
    fun `an average landing exactly on a half rounds to the even neighbour`() {
        assertEquals(128, dwTraceClampedAverage(127L + 128L, 2))
        assertEquals(128, dwTraceClampedAverage(128L + 129L, 2))
        assertNotEquals(
            "truncation is the mistake this case exists to catch",
            127,
            dwTraceClampedAverage(127L + 128L, 2),
        )
        // The ordinary cases, so the tie rule cannot be "fixed" by rounding everything up.
        assertEquals(127, dwTraceClampedAverage(127L * 3, 3))
        assertEquals(0, dwTraceClampedAverage(0L, 4))
        assertEquals(255, dwTraceClampedAverage(255L * 4, 4))
    }

    /** No source pixels is black rather than a guess, and never a division by zero. */
    @Test
    fun `an empty box is black`() {
        assertEquals(0, dwTraceClampedAverage(100L, 0))
    }

    /* ── the difference plate ───────────────────────────────────────────────────────────────── */

    /**
     * ABSOLUTE DIFFERENCE PER CHANNEL — the definition both clients implement, asserted rather than
     * described.
     *
     * The alternative was a luminance difference, and the reason it was not chosen is in
     * [dwTraceDifferenceRow]: a luminance difference needs a set of weights, more than one standard
     * set is in common use, and the day the two clients picked different ones the difference plate
     * would disagree between a laptop and a handset with nothing on either screen to say which was
     * right. This has exactly one definition, which is why it can be pinned in four numbers.
     */
    @Test
    fun `the difference is the absolute per-channel difference and is opaque`() {
        val photograph = intArrayOf(0xFF204060.toInt(), 0x00FFFFFF)
        val trace = intArrayOf(0xFF10FF00.toInt(), 0xFFFFFFFF.toInt())
        val out = IntArray(2)
        dwTraceDifferenceRow(photograph, trace, out, 2)

        // |0x20-0x10| = 0x10, |0x40-0xFF| = 0xBF, |0x60-0x00| = 0x60.
        assertEquals(0xFF10BF60.toInt(), out[0])
        // The two alphas differ and the answer is still opaque: a translucent layer in a comparator
        // shows the layer beneath it, which is the failure the whole plate contract is written against.
        assertEquals(0xFF000000.toInt(), out[1])
    }

    /** Agreement is black. It is the property the whole mode rests on, so it is asserted directly. */
    @Test
    fun `two identical pictures differ by nothing`() {
        val same = intArrayOf(0xFF123456.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val out = IntArray(3)
        dwTraceDifferenceRow(same, same, out, 3)
        for (value in out) assertEquals(0xFF000000.toInt(), value)
    }

    /** Subtraction the other way round is the same picture — the sign is dropped, not assumed. */
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

    /* ── the sentences ──────────────────────────────────────────────────────────────────────── */

    /**
     * The reduction is stated with BOTH numbers, and stated only when it happened.
     *
     * `comparisonPlates.ts:68-70` carries a flag whose entire documentation is "Say so on screen", and
     * this client said nothing anywhere. Without it, a designer judging lost line weight at 1024
     * against a 4096 trace cannot tell whether the loss is the trace's or the plate's.
     */
    @Test
    fun `the reduction sentence carries both sizes and is silent when there was none`() {
        assertEquals(
            "Both pictures here are 1024×768, reduced from 4096×3072 for the comparison only.",
            dwTraceComparisonReduction(1024, 768, 4096, 3072),
        )
        assertEquals("", dwTraceComparisonReduction(1024, 768, 1024, 768))
    }

    /**
     * Every absence has its own sentence, and the one state that needs no caption has none.
     *
     * The bug this closes is the panel composing nothing at all when there was no result — an empty
     * area indistinguishable from a place with no records, which is the failure mode this repository
     * takes most seriously.
     */
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

        // The failed branch POINTS AT the red message rather than restating it: two copies of one
        // fault in one panel is how a designer ends up believing there are two.
        assertTrue("the failed branch must point at the reason: $failed", failed.contains("above"))
        // A comparator whose gestures have gone dead must say why, or for those few seconds it is
        // indistinguishable from a frozen control.
        assertTrue("the updating branch must explain the dead controls: $updating", updating.contains("controls"))
    }

    /**
     * A plate that could not be built costs the comparison and says the drawing survived.
     *
     * Both of these sentences replaced a whole-run refusal that discarded a finished SVG, so the thing
     * they most have to carry is that the drawing is still there — the same promise the portal's two
     * refusals make in the words "The drawing above is unaffected."
     */
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
        // The frame mismatch keeps the remedy that names a control the designer can see — which the
        // portal's equivalent sentence does not have.
        assertTrue(dwTraceSentence(DwTraceFailureKind.FRAME_MISMATCH).contains("Rectify the page"))
        // And it no longer claims the trace was thrown away, because it is not.
        assertTrue(
            "the old sentence said nothing had been attached, which is no longer true",
            !dwTraceSentence(DwTraceFailureKind.FRAME_MISMATCH).contains("nothing has been attached"),
        )
    }

    /* ── the same words on both clients, read off the portal itself ────────────────────────── */

    /**
     * The portal's copy of one of these sentences, reassembled from its source.
     *
     * WHY IT READS THE FILE RATHER THAN TRUSTING A TRANSCRIPTION. Every other sentence in this class
     * is pinned against a literal typed here, which answers "did the handset drift" and cannot answer
     * "did the two clients drift apart" — the question that matters for a view built on two clients at
     * once, from one instruction, by two people who never spoke. A transcription of the portal's
     * string is just a third copy that can go stale in its own right. This one goes to the source.
     *
     * The portal writes these as a run of double-quoted pieces joined by plus signs across several
     * lines, so the value is everything between the declaration and its semicolon with the quoted runs
     * concatenated in order. Apostrophes inside the sentences are the typewriter kind and never a
     * double quote, which is what lets the extraction stay this simple.
     */
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

    /**
     * Everything the difference view says, said the same way on both clients.
     *
     * ── WHY THIS TEST EXISTS AND THE OTHERS IN THIS FILE DO NOT COVER IT ──────────────────────
     *
     * The difference plate was built on the two clients at the same time, separately, against one
     * written instruction. The arithmetic agreed, because it was stated as arithmetic. The WORDS did
     * not. Two of this view's five strings had been written TWICE, once on each client, in different
     * words, for the same state: what it says while it is thinking, and how it describes itself to a
     * screen reader. The fifth was on one client only — the handset writes "Difference" on the
     * picture and the portal wrote nothing there at all. Nothing failed in either case. Two apps
     * simply told one designer two different things about one sheet, and then told them nothing.
     *
     * That is the failure this repository's wording rule exists to prevent, and by-eye agreement is
     * how it got past two careful passes. So the agreement is mechanical from here: the handset owns
     * the wording, the portal copies it, and this reads the portal's own file to check that it did.
     *
     * ── THE ONE DIFFERENCE THAT IS ALLOWED, PINNED AS EXACTLY THAT ────────────────────────────
     *
     * The refusal names the device it could not find room on, so the portal says "This browser" where
     * the handset says "This phone". That is argued and deliberate, the same class as Save against
     * Download in the export row. It is asserted as a substitution rather than skipped, so the day
     * anything else in that sentence moves this fails.
     */
    @Test
    fun `the portal says exactly what the handset says about the difference plate`() {
        assertEquals(DW_TRACE_DIFFERENCE_NOTE, portalSentence("COMPARISON_DIFFERENCE_NOTE"))
        assertEquals(DW_TRACE_DIFFERENCE_PENDING, portalSentence("COMPARISON_DIFFERENCE_PENDING"))
        assertEquals(DW_TRACE_DIFFERENCE_DESCRIPTION, portalSentence("COMPARISON_DIFFERENCE_ALT"))
        assertEquals(DW_TRACE_DIFFERENCE_LABEL, portalSentence("COMPARISON_DIFFERENCE_BADGE"))

        // The refusal, and the single word it is allowed to differ by.
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
