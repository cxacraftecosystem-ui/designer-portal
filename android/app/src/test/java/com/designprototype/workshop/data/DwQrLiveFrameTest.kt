package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * THE LIVE SCANNER'S TWO INVISIBLE FAILURES, BOTH ASSERTED ON A MACHINE WITH NO HANDSET.
 *
 * ── WHY THESE TWO AND NOT SOMETHING ABOUT A LENS ──────────────────────────────────────────────
 *
 * A live QR scanner has exactly two defects that produce NO error, NO log line and NO difference a
 * designer can describe. Both are in this file.
 *
 *  1. THE ROW STRIDE. `Image.Plane.getRowStride()` is at least the width and on many handsets is
 *     strictly greater, because the capture buffer is padded to an alignment. An implementation that
 *     treats the plane as tightly packed produces an image sheared a few pixels further left on every
 *     row: it decodes NOTHING on those handsets and works perfectly on the ones where the stride
 *     happens to equal the width. Correct on the machine of whoever wrote it, silent in a courtyard.
 *     Every plane built below therefore has a stride WIDER than its width, and
 *     [a padded plane is not read as if it were tightly packed] fails against a naive reader.
 *
 *  2. THE RETICLE AND THE CROP. The box drawn on screen and the region handed to the decoder must be
 *     the same region. If the crop is even slightly inside the drawn box, a designer lines a code up
 *     perfectly and the code does not read — indistinguishable from a bad card. So the crop is
 *     asserted to CONTAIN the exact mapped rectangle under every rotation, rather than merely to be
 *     near it.
 *
 * ── AND WHAT IS STILL NOT PROVED ──────────────────────────────────────────────────────────────
 *
 * Nothing about a lens. There is no perspective here, no glare, no motion blur and no autofocus, and
 * whether CameraX fills `ImageProxy.cropRect` on a real handset the way its documentation says is a
 * hardware claim this repository cannot make. `DwQrDecodeTest`'s header sets out the same boundary for
 * the still path; the boundary has not moved. What IS verified is the whole chain from this app's own
 * bits, through the stride handling and the crop arithmetic, to a decoded record — which is the half
 * that can be.
 */
class DwQrLiveFrameTest {

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    /** White in a luminance plane. `0xFF` as a signed byte. */
    private val white: Byte = -1

    /** Dark in a luminance plane. */
    private val dark: Byte = 0

    /**
     * A luminance plane with DELIBERATE ROW PADDING and a value that encodes its own coordinates.
     *
     * Each byte is `(row * 7 + column) mod 251`, which is unique enough over any small window that a
     * reader picking up the wrong row or the wrong column produces a different number rather than a
     * coincidence. The padding bytes between rows are set to a value that appears nowhere in the
     * image, so a tightly-packed reader picks up PADDING and the assertion says so.
     */
    private fun paddedPlane(width: Int, height: Int, rowStride: Int, pixelStride: Int = 1): ByteBuffer {
        require(rowStride >= width * pixelStride)
        val bytes = ByteArray(rowStride * height) { 0x7B }
        for (row in 0 until height) {
            for (column in 0 until width) {
                bytes[row * rowStride + column * pixelStride] = ((row * 7 + column) % 251).toByte()
            }
        }
        return ByteBuffer.wrap(bytes)
    }

    private fun expectedAt(row: Int, column: Int): Byte = ((row * 7 + column) % 251).toByte()

    /**
     * One of this app's own symbols, painted into a padded luminance plane at [scale] pixels per
     * module with a four-module quiet zone, centred in the plane.
     *
     * FOUR MODULES OF QUIET ZONE IS NOT DECORATION — it is what ISO 18004 requires and what
     * `DwQrSymbolImage` and `renderCardSheetPdf` both leave, and a symbol drawn hard against its
     * border does not scan. Rendering with less here would make this test agree with a card that
     * fails in the field.
     */
    private fun planeWithSymbol(
        symbol: DwQrSymbol,
        width: Int,
        height: Int,
        rowStride: Int,
        scale: Int,
        quiet: Int = 4,
    ): ByteBuffer {
        val bytes = ByteArray(rowStride * height) { white }
        val extent = (symbol.size + quiet * 2) * scale
        val originX = (width - extent) / 2
        val originY = (height - extent) / 2
        require(originX >= 0 && originY >= 0) { "the symbol does not fit in this plane" }
        for (moduleRow in 0 until symbol.size) {
            for (moduleColumn in 0 until symbol.size) {
                if (!symbol.matrix[moduleRow][moduleColumn]) continue
                val top = originY + (moduleRow + quiet) * scale
                val left = originX + (moduleColumn + quiet) * scale
                for (y in top until top + scale) {
                    for (x in left until left + scale) {
                        bytes[y * rowStride + x] = dark
                    }
                }
            }
        }
        return ByteBuffer.wrap(bytes)
    }

    // ── The crop arithmetic ─────────────────────────────────────────────────────────────────────

    /**
     * The reticle is a CENTRED SQUARE on the shorter side, whatever shape the box is.
     *
     * Centred is what makes it findable and square is what makes a QR fit it; both are asserted
     * because the scanner's whole overlay is drawn from this one value.
     */
    @Test
    fun `the reticle is a centred square on the shorter side`() {
        val tall = dwQrReticleFraction(1080, 1920)
        assertNotNull(tall)
        tall!!
        // Centred: the two margins are equal on each axis.
        assertEquals(tall.left, 1f - tall.right, 1e-5f)
        assertEquals(tall.top, 1f - tall.bottom, 1e-5f)
        // Square IN PIXELS, which on a non-square box means UNEQUAL fractions. Asserting equal
        // fractions would be asserting a rectangle.
        assertEquals(
            (tall.right - tall.left) * 1080f,
            (tall.bottom - tall.top) * 1920f,
            0.5f,
        )
        // And it is the SHORTER side that is filled, so the square fits.
        assertEquals(1080f * DW_QR_RETICLE_SIDE_FRACTION, (tall.right - tall.left) * 1080f, 0.5f)
    }

    /** An unmeasured box has no reticle, and no reticle must NEVER mean a guessed rectangle. */
    @Test
    fun `an unmeasured box has no reticle at all`() {
        assertNull(dwQrReticleFraction(0, 0))
        assertNull(dwQrReticleFraction(1080, 0))
        assertNull(dwQrReticleFraction(-5, 100))
        assertNull(dwQrReticleFraction(100, 100, sideFraction = 0f))
        assertNull(dwQrReticleFraction(100, 100, sideFraction = 1.5f))
        assertNull(dwQrReticleFraction(100, 100, sideFraction = Float.NaN))
    }

    /**
     * THE ROTATION, PINNED WITH AN OFF-CENTRE RECTANGLE — which is the only kind that can catch it.
     *
     * A centred square maps to itself under all four rotations, so a test using the real reticle
     * would pass with the rotation ignored entirely. This rectangle is off-centre on both axes, so
     * each quarter turn sends it somewhere different and a dropped or reversed rotation shows up.
     *
     * The tolerance is two pixels and is not slack: `0.3f` is 0.30000001192 as a float, so
     * `ceil(0.3f * 1000)` is 301 rather than 300, and every edge here is rounded OUTWARD on purpose
     * (see the containment test below). Asserting exact integers would be asserting float noise.
     */
    @Test
    fun `a rectangle is mapped back through each of the four rotations`() {
        val displayed = DwQrCrop(0, 0, 1000, 1000)
        val reticle = DwQrFraction(left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f)

        fun crop(rotation: Int) = dwQrCropInBuffer(
            reticle = reticle,
            displayed = displayed,
            rotationDegrees = rotation,
            bufferWidth = 1000,
            bufferHeight = 1000,
            margin = 0f,
        )

        fun assertNear(expected: DwQrCrop, actual: DwQrCrop?, what: String) {
            assertNotNull(what, actual)
            actual!!
            assertTrue(
                "$what: expected about $expected, got $actual",
                kotlin.math.abs(actual.left - expected.left) <= 2 &&
                    kotlin.math.abs(actual.top - expected.top) <= 2 &&
                    kotlin.math.abs(actual.width - expected.width) <= 3 &&
                    kotlin.math.abs(actual.height - expected.height) <= 3,
            )
        }

        // 0 degrees: straight through.
        assertNear(DwQrCrop(100, 200, 200, 200), crop(0), "0 degrees")
        // 90 clockwise: the buffer's top-left ends up top-right on screen, so screen (x, y) came from
        // buffer (u, v) = (y, 1 - x).
        assertNear(DwQrCrop(200, 700, 200, 200), crop(90), "90 degrees")
        // 180: its own inverse.
        assertNear(DwQrCrop(700, 600, 200, 200), crop(180), "180 degrees")
        // 270 clockwise: buffer (u, v) = (1 - y, x).
        assertNear(DwQrCrop(600, 100, 200, 200), crop(270), "270 degrees")
    }

    /**
     * THE INVARIANT THAT MATTERS MORE THAN ANY EXACT NUMBER: the crop always CONTAINS the box drawn
     * on screen.
     *
     * If it does not — by half a pixel, on one edge — a designer lines a code up inside a box the app
     * is not looking at and nothing at all reports it. Every rounding in `dwQrCropInBuffer` is
     * outward and the rectangle is inflated by `DW_QR_RETICLE_MARGIN` first, and this asserts the
     * consequence rather than the implementation, at margin 0 (the strictest case) and under every
     * rotation.
     */
    @Test
    fun `the crop always contains the rectangle drawn on screen`() {
        val displayed = DwQrCrop(40, 24, 1200, 700)
        val buffer = 1280 to 760
        val reticle = dwQrReticleFraction(1080, 1920)!!

        listOf(0, 90, 180, 270).forEach { rotation ->
            val crop = dwQrCropInBuffer(
                reticle = reticle,
                displayed = displayed,
                rotationDegrees = rotation,
                bufferWidth = buffer.first,
                bufferHeight = buffer.second,
                margin = 0f,
            )
            assertNotNull("rotation $rotation should crop", crop)
            crop!!
            // The exact mapped rectangle, computed independently here in doubles so that a float
            // rounding bug in the implementation cannot hide behind the same float rounding.
            val (l, t, r, b) = mappedExactly(reticle, rotation)
            val exactLeft = displayed.left + l * displayed.width
            val exactTop = displayed.top + t * displayed.height
            val exactRight = displayed.left + r * displayed.width
            val exactBottom = displayed.top + b * displayed.height
            assertTrue(
                "rotation $rotation: crop $crop must contain [$exactLeft,$exactTop,$exactRight,$exactBottom]",
                crop.left <= exactLeft + 1e-3 &&
                    crop.top <= exactTop + 1e-3 &&
                    crop.left + crop.width >= exactRight - 1e-3 &&
                    crop.top + crop.height >= exactBottom - 1e-3,
            )
            // And it never leaves the region the viewfinder is actually showing.
            assertTrue("rotation $rotation stays inside the displayed rect", crop.left >= displayed.left)
            assertTrue("rotation $rotation stays inside the displayed rect", crop.top >= displayed.top)
            assertTrue(
                "rotation $rotation stays inside the displayed rect",
                crop.left + crop.width <= displayed.left + displayed.width,
            )
            assertTrue(
                "rotation $rotation stays inside the displayed rect",
                crop.top + crop.height <= displayed.top + displayed.height,
            )
        }
    }

    /** The rotation map, written a second time in doubles, so the test is not the implementation. */
    private fun mappedExactly(box: DwQrFraction, rotation: Int): List<Double> {
        val l = box.left.toDouble()
        val t = box.top.toDouble()
        val r = box.right.toDouble()
        val b = box.bottom.toDouble()
        val corners = when (rotation) {
            90 -> listOf(t, 1 - r, b, 1 - l)
            180 -> listOf(1 - r, 1 - b, 1 - l, 1 - t)
            270 -> listOf(1 - b, l, 1 - t, r)
            else -> listOf(l, t, r, b)
        }
        return listOf(
            minOf(corners[0], corners[2]),
            minOf(corners[1], corners[3]),
            maxOf(corners[0], corners[2]),
            maxOf(corners[1], corners[3]),
        )
    }

    /**
     * EVERY WAY THE ANSWER CAN BE "DECODE THE WHOLE FRAME INSTEAD", and it is never "guess".
     *
     * Each of these is a real state a camera implementation can present. A function that answered
     * with a plausible-looking rectangle for any of them would be inventing the correspondence the
     * previous test exists to guarantee.
     */
    @Test
    fun `an answer that cannot be computed honestly is null and never a guess`() {
        val reticle = dwQrReticleFraction(1080, 1920)!!
        val displayed = DwQrCrop(0, 0, 1280, 720)

        // A rotation the map does not cover. Rounding it to the nearest right angle would silently
        // crop a quarter turn away from the box on screen.
        assertNull(dwQrCropInBuffer(reticle, displayed, 45, 1280, 720))
        // A displayed rectangle that is not inside the buffer at all.
        assertNull(dwQrCropInBuffer(reticle, DwQrCrop(0, 0, 2000, 720), 0, 1280, 720))
        assertNull(dwQrCropInBuffer(reticle, DwQrCrop(-4, 0, 100, 100), 0, 1280, 720))
        // No buffer.
        assertNull(dwQrCropInBuffer(reticle, displayed, 0, 0, 0))
        // A crop under the two-pixels-per-module floor `DwQrDecodeTest` measured. 72% of 100 is 72
        // pixels, which cannot resolve a module of the largest symbol this app prints.
        assertNull(dwQrCropInBuffer(reticle, DwQrCrop(0, 0, 100, 100), 0, 1280, 720))
        // A reticle covering the whole frame: there is nothing to crop, so the caller should read the
        // displayed rectangle it already has.
        assertNull(dwQrCropInBuffer(DwQrFraction(0f, 0f, 1f, 1f), displayed, 0, 1280, 720))
        // A degenerate rectangle.
        assertNull(dwQrCropInBuffer(DwQrFraction(0.5f, 0.5f, 0.5f, 0.5f), displayed, 0, 1280, 720))
        assertNull(dwQrCropInBuffer(DwQrFraction(0.6f, 0.2f, 0.4f, 0.8f), displayed, 0, 1280, 720))
        assertNull(dwQrCropInBuffer(DwQrFraction(Float.NaN, 0f, 1f, 1f), displayed, 0, 1280, 720))
    }

    /** The margin only ever grows the crop, and never past the region being displayed. */
    @Test
    fun `the margin grows the crop and never past the displayed rectangle`() {
        val reticle = dwQrReticleFraction(1080, 1920)!!
        val displayed = DwQrCrop(0, 0, 1280, 720)
        val tight = dwQrCropInBuffer(reticle, displayed, 0, 1280, 720, margin = 0f)!!
        val loose = dwQrCropInBuffer(reticle, displayed, 0, 1280, 720, margin = DW_QR_RETICLE_MARGIN)!!
        assertTrue("the margin must widen the crop", loose.width > tight.width)
        assertTrue("the margin must heighten the crop", loose.height > tight.height)
        assertTrue(loose.left <= tight.left)
        assertTrue(loose.top <= tight.top)
        assertTrue(loose.left + loose.width <= displayed.width)
        assertTrue(loose.top + loose.height <= displayed.height)
        // A nonsense margin is ignored rather than propagated into a NaN rectangle.
        assertEquals(tight, dwQrCropInBuffer(reticle, displayed, 0, 1280, 720, margin = Float.NaN))
        assertEquals(tight, dwQrCropInBuffer(reticle, displayed, 0, 1280, 720, margin = -1f))
    }

    // ── The stride ──────────────────────────────────────────────────────────────────────────────

    /**
     * A PADDED PLANE IS NOT READ AS IF IT WERE TIGHTLY PACKED.
     *
     * This is the test that fails against the implementation everybody writes first. The plane below
     * has three bytes of padding after each row, filled with a value the image never contains, so a
     * reader that walks the buffer linearly picks up padding on row 1 and every row after it.
     */
    @Test
    fun `a padded plane is not read as if it were tightly packed`() {
        val width = 12
        val height = 6
        val rowStride = width + 3
        val plane = paddedPlane(width, height, rowStride)
        val crop = DwQrCrop(2, 1, 8, 4)
        val out = ByteArray(crop.width * crop.height)

        assertTrue(dwQrCompactLuminance(plane, rowStride, 1, crop, out))
        for (row in 0 until crop.height) {
            for (column in 0 until crop.width) {
                assertEquals(
                    "row $row column $column came from the wrong place",
                    expectedAt(crop.top + row, crop.left + column),
                    out[row * crop.width + column],
                )
            }
        }
        // And the padding value never appears — which is what a linear read would have produced.
        assertFalse("padding leaked into the image", out.any { it == 0x7B.toByte() })
    }

    /** `pixelStride` above 1 is honoured, because "every device does 1" is not a checkable claim. */
    @Test
    fun `a plane with a pixel stride above one is honoured`() {
        val width = 10
        val height = 5
        val pixelStride = 2
        val rowStride = width * pixelStride + 4
        val plane = paddedPlane(width, height, rowStride, pixelStride)
        val crop = DwQrCrop(1, 1, 6, 3)
        val out = ByteArray(crop.width * crop.height)

        assertTrue(dwQrCompactLuminance(plane, rowStride, pixelStride, crop, out))
        for (row in 0 until crop.height) {
            for (column in 0 until crop.width) {
                assertEquals(
                    expectedAt(crop.top + row, crop.left + column),
                    out[row * crop.width + column],
                )
            }
        }
    }

    /**
     * A FRAME THAT DOES NOT ADD UP IS DROPPED, NOT CRASHED ON.
     *
     * Every refusal here is reachable from a real camera implementation, and each one would otherwise
     * be an ArrayIndexOutOfBounds inside a frame callback on somebody's handset. False is the
     * analyser's cue to skip the frame; the next one arrives in 33 ms.
     */
    @Test
    fun `a frame whose strides do not add up is refused rather than read`() {
        val plane = paddedPlane(12, 6, 15)
        val out = ByteArray(64)

        // A buffer shorter than the crop's own last index.
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(0, 0, 12, 40), ByteArray(12 * 40)))
        // A crop starting outside the plane.
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(-1, 0, 4, 4), out))
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(0, -2, 4, 4), out))
        // An empty crop.
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(0, 0, 0, 4), out))
        // Impossible strides.
        assertFalse(dwQrCompactLuminance(plane, 0, 1, DwQrCrop(0, 0, 4, 4), out))
        assertFalse(dwQrCompactLuminance(plane, 15, 0, DwQrCrop(0, 0, 4, 4), out))
        // A destination too small — which is what a reused buffer looks like the frame a bigger crop
        // first arrives.
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(0, 0, 8, 5), ByteArray(8 * 5 - 1)))
    }

    /** The reused buffer only ever grows, and it is the same array when it does not need to. */
    @Test
    fun `the luminance buffer is reused and only ever grows`() {
        val decoder = DwQrLiveDecoder()
        val first = decoder.luminanceBuffer(1024)
        assertTrue(first.size >= 1024)
        assertSameArray(first, decoder.luminanceBuffer(512))
        assertSameArray(first, decoder.luminanceBuffer(1024))
        val bigger = decoder.luminanceBuffer(4096)
        assertTrue(bigger.size >= 4096)
        assertEquals(0, decoder.luminanceBuffer(0).size)
    }

    private fun assertSameArray(expected: ByteArray, actual: ByteArray) {
        assertTrue("the buffer must not be reallocated", expected === actual)
    }

    // ── The whole chain ─────────────────────────────────────────────────────────────────────────

    /**
     * THIS APP'S OWN PRINTER, THIS APP'S OWN LIVE READER, THIS APP'S OWN PARSER — over a padded plane
     * with a reticle crop, coming out as the record the card names.
     *
     * EVERY LINK IS THE SHIPPING ONE. The code comes from [encodeWorkshopCode], the symbol from
     * [DwQrEncode] at the level cards are printed at, the reticle from [dwQrReticleFraction], the crop
     * from [dwQrCropInBuffer], the compaction from [dwQrCompactLuminance], the decode from
     * [DwQrLiveDecoder] with the hints the analyser uses, and the parse from [decodeWorkshopCode] —
     * which is the same parser the typed box uses, deliberately, so a scanned code and a typed one
     * can never be judged differently.
     *
     * The one thing that is NOT the shipping arrangement is the plane, and it is deliberately made
     * HARDER than a real one in the way that catches bugs (padded rows) and easier in the ways only a
     * lens can supply (no blur, no perspective, no glare).
     */
    @Test
    fun `a live frame of a card this app prints is read back as the record it names`() {
        val encoded = encodeWorkshopCode(DwWorkshopRecordType.PROTOTYPE, "cmsvfnb4y0001qq1bzd2g48lq")
        assertTrue("the fixture must be a real code, or this test asserts nothing", encoded is DwEncodeResult.Ok)
        val code = (encoded as DwEncodeResult.Ok).code
        // Level Q, which is what every card this app draws uses — see `RecordCodeCard.CODE_ECC`.
        val symbol = DwQrEncode.encode(code, DwQrEccLevel.Q)

        val width = 1280
        val height = 720
        val rowStride = width + 48
        val scale = 8
        val plane = planeWithSymbol(symbol, width, height, rowStride, scale)

        // The box the designer is looking at, in the shape a handset in portrait presents.
        val reticle = dwQrReticleFraction(1080, 1920)!!
        val displayed = DwQrCrop(0, 0, width, height)
        val crop = dwQrCropInBuffer(reticle, displayed, 0, width, height)
        assertNotNull("the reticle must produce a crop at this resolution", crop)
        crop!!

        // The symbol must actually be inside the crop, or this test would be asserting that a decoder
        // reads a blank frame — which it would not, and the test would pass for the wrong reason.
        val extent = (symbol.size + 8) * scale
        assertTrue(
            "the fixture symbol ($extent px) must fit inside the crop (${crop.width} px)",
            extent <= crop.width && extent <= crop.height,
        )

        val decoder = DwQrLiveDecoder()
        val buffer = decoder.luminanceBuffer(crop.width * crop.height)
        assertTrue(dwQrCompactLuminance(plane, rowStride, 1, crop, buffer))

        val text = decoder.decode(buffer, crop.width, crop.height)
        assertEquals("the live path must read back exactly what was printed", code, text)

        val parsed = decodeWorkshopCode(text)
        assertTrue("the shipping parser must accept it", parsed is DwDecodeResult.Ok)
        assertEquals(
            DwWorkshopCodeRef(DwWorkshopRecordType.PROTOTYPE, "cmsvfnb4y0001qq1bzd2g48lq"),
            (parsed as DwDecodeResult.Ok).ref,
        )
    }

    /**
     * THE DESIGN-WORKSHOP CARD SPECIFICALLY, because it is the one this feature was built for.
     *
     * It is the longest code in ordinary use (`DPW1:G:` plus a 25-character cuid plus a four-character
     * check) and it is the one a person is HANDED, so it is the one that has to read off a live frame
     * in a courtyard. Asserted separately from the prototype tag above rather than parameterised, so
     * that a failure names which card stopped working.
     */
    @Test
    fun `a live frame of a design-workshop card reads back as that workshop`() {
        val workshopId = "cmsik2jg8000eh8xc1lcy661a"
        val encoded = encodeWorkshopCode(DwWorkshopRecordType.DESIGN_WORKSHOP, workshopId)
        assertTrue(encoded is DwEncodeResult.Ok)
        val code = (encoded as DwEncodeResult.Ok).code
        val symbol = DwQrEncode.encode(code, DwQrEccLevel.Q)

        val width = 1280
        val height = 720
        val rowStride = width + 16
        val plane = planeWithSymbol(symbol, width, height, rowStride, scale = 8)
        val crop = dwQrCropInBuffer(dwQrReticleFraction(1080, 1920)!!, DwQrCrop(0, 0, width, height), 0, width, height)!!

        val decoder = DwQrLiveDecoder()
        val buffer = decoder.luminanceBuffer(crop.width * crop.height)
        assertTrue(dwQrCompactLuminance(plane, rowStride, 1, crop, buffer))

        val parsed = decodeWorkshopCode(decoder.decode(buffer, crop.width, crop.height))
        assertTrue(parsed is DwDecodeResult.Ok)
        assertEquals(
            DwWorkshopCodeRef(DwWorkshopRecordType.DESIGN_WORKSHOP, workshopId),
            (parsed as DwDecodeResult.Ok).ref,
        )
    }

    /**
     * ONE ANALYSER, MANY FRAMES, AND THE SHARED READER SURVIVES IT.
     *
     * [DwQrLiveDecoder] keeps ONE `MultiFormatReader` and one buffer for the life of a scan, which is
     * a deliberate departure from what `ZxingQrImageDecoder` does and says. The risk that buys is a
     * reader carrying state from one frame into the next, and the symptom would be a code decoding to
     * the wrong text after a while — which is worse than not decoding at all. So: an empty frame, a
     * real one, an empty one, a DIFFERENT real one, and every answer must be the right one.
     */
    @Test
    fun `one decoder reads frame after frame without carrying state`() {
        val decoder = DwQrLiveDecoder()
        val width = 640
        val height = 480
        val rowStride = width + 12

        fun frameFor(recordType: DwWorkshopRecordType, id: String): Pair<String, ByteArray> {
            val code = (encodeWorkshopCode(recordType, id) as DwEncodeResult.Ok).code
            val symbol = DwQrEncode.encode(code, DwQrEccLevel.Q)
            val plane = planeWithSymbol(symbol, width, height, rowStride, scale = 6)
            val crop = DwQrCrop(0, 0, width, height)
            val out = ByteArray(width * height)
            assertTrue(dwQrCompactLuminance(plane, rowStride, 1, crop, out))
            return code to out
        }

        val blank = ByteArray(width * height) { white }
        val (artisanCode, artisanFrame) = frameFor(DwWorkshopRecordType.ARTISAN, "cmsik2jg8000eh8xc1lcy661a")
        val (toolCode, toolFrame) = frameFor(DwWorkshopRecordType.TOOL, "cmsvfnb4y0001qq1bzd2g48lq")
        assertFalse("the two fixtures must differ, or this proves nothing", artisanCode == toolCode)

        assertNull("a blank frame is an ordinary null", decoder.decode(blank, width, height))
        assertEquals(artisanCode, decoder.decode(artisanFrame, width, height))
        assertNull(decoder.decode(blank, width, height))
        assertEquals(toolCode, decoder.decode(toolFrame, width, height))
        assertEquals(artisanCode, decoder.decode(artisanFrame, width, height))
    }

    /** A frame smaller than it claims to be is a null, not an exception. */
    @Test
    fun `the decoder refuses a frame smaller than its own dimensions`() {
        val decoder = DwQrLiveDecoder()
        assertNull(decoder.decode(ByteArray(10), 100, 100))
        assertNull(decoder.decode(ByteArray(0), 0, 0))
        assertNull(decoder.decode(ByteArray(100), -1, 10))
    }
}
