package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DwQrLiveFrameTest {

    private val white: Byte = -1

    private val dark: Byte = 0

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

    @Test
    fun `the reticle is a centred square on the shorter side`() {
        val tall = dwQrReticleFraction(1080, 1920)
        assertNotNull(tall)
        tall!!
        assertEquals(tall.left, 1f - tall.right, 1e-5f)
        assertEquals(tall.top, 1f - tall.bottom, 1e-5f)
        assertEquals(
            (tall.right - tall.left) * 1080f,
            (tall.bottom - tall.top) * 1920f,
            0.5f,
        )
        assertEquals(1080f * DW_QR_RETICLE_SIDE_FRACTION, (tall.right - tall.left) * 1080f, 0.5f)
    }

    @Test
    fun `an unmeasured box has no reticle at all`() {
        assertNull(dwQrReticleFraction(0, 0))
        assertNull(dwQrReticleFraction(1080, 0))
        assertNull(dwQrReticleFraction(-5, 100))
        assertNull(dwQrReticleFraction(100, 100, sideFraction = 0f))
        assertNull(dwQrReticleFraction(100, 100, sideFraction = 1.5f))
        assertNull(dwQrReticleFraction(100, 100, sideFraction = Float.NaN))
    }

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

        assertNear(DwQrCrop(100, 200, 200, 200), crop(0), "0 degrees")
        assertNear(DwQrCrop(200, 700, 200, 200), crop(90), "90 degrees")
        assertNear(DwQrCrop(700, 600, 200, 200), crop(180), "180 degrees")
        assertNear(DwQrCrop(600, 100, 200, 200), crop(270), "270 degrees")
    }

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

    @Test
    fun `an answer that cannot be computed honestly is null and never a guess`() {
        val reticle = dwQrReticleFraction(1080, 1920)!!
        val displayed = DwQrCrop(0, 0, 1280, 720)

        assertNull(dwQrCropInBuffer(reticle, displayed, 45, 1280, 720))
        assertNull(dwQrCropInBuffer(reticle, DwQrCrop(0, 0, 2000, 720), 0, 1280, 720))
        assertNull(dwQrCropInBuffer(reticle, DwQrCrop(-4, 0, 100, 100), 0, 1280, 720))
        assertNull(dwQrCropInBuffer(reticle, displayed, 0, 0, 0))
        assertNull(dwQrCropInBuffer(reticle, DwQrCrop(0, 0, 100, 100), 0, 1280, 720))
        assertNull(dwQrCropInBuffer(DwQrFraction(0f, 0f, 1f, 1f), displayed, 0, 1280, 720))
        assertNull(dwQrCropInBuffer(DwQrFraction(0.5f, 0.5f, 0.5f, 0.5f), displayed, 0, 1280, 720))
        assertNull(dwQrCropInBuffer(DwQrFraction(0.6f, 0.2f, 0.4f, 0.8f), displayed, 0, 1280, 720))
        assertNull(dwQrCropInBuffer(DwQrFraction(Float.NaN, 0f, 1f, 1f), displayed, 0, 1280, 720))
    }

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
        assertEquals(tight, dwQrCropInBuffer(reticle, displayed, 0, 1280, 720, margin = Float.NaN))
        assertEquals(tight, dwQrCropInBuffer(reticle, displayed, 0, 1280, 720, margin = -1f))
    }

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
        assertFalse("padding leaked into the image", out.any { it == 0x7B.toByte() })
    }

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

    @Test
    fun `a frame whose strides do not add up is refused rather than read`() {
        val plane = paddedPlane(12, 6, 15)
        val out = ByteArray(64)

        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(0, 0, 12, 40), ByteArray(12 * 40)))
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(-1, 0, 4, 4), out))
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(0, -2, 4, 4), out))
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(0, 0, 0, 4), out))
        assertFalse(dwQrCompactLuminance(plane, 0, 1, DwQrCrop(0, 0, 4, 4), out))
        assertFalse(dwQrCompactLuminance(plane, 15, 0, DwQrCrop(0, 0, 4, 4), out))
        assertFalse(dwQrCompactLuminance(plane, 15, 1, DwQrCrop(0, 0, 8, 5), ByteArray(8 * 5 - 1)))
    }

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

    @Test
    fun `a live frame of a card this app prints is read back as the record it names`() {
        val encoded = encodeWorkshopCode(DwWorkshopRecordType.PROTOTYPE, "cmsvfnb4y0001qq1bzd2g48lq")
        assertTrue("the fixture must be a real code, or this test asserts nothing", encoded is DwEncodeResult.Ok)
        val code = (encoded as DwEncodeResult.Ok).code
        val symbol = DwQrEncode.encode(code, DwQrEccLevel.Q)

        val width = 1280
        val height = 720
        val rowStride = width + 48
        val scale = 8
        val plane = planeWithSymbol(symbol, width, height, rowStride, scale)

        val reticle = dwQrReticleFraction(1080, 1920)!!
        val displayed = DwQrCrop(0, 0, width, height)
        val crop = dwQrCropInBuffer(reticle, displayed, 0, width, height)
        assertNotNull("the reticle must produce a crop at this resolution", crop)
        crop!!

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

    @Test
    fun `a CXA1 artisan card round-trips through the encoder and the reference decoder`() {
        val decoder = DwQrLiveDecoder()
        val width = 640
        val height = 480
        val rowStride = width + 12
        val token = "CXA1:D1:7K3QM9XA2F:QQPZR8FHGZPJXHBM"
        val symbol = DwQrEncode.encode(token, DwQrEccLevel.Q)
        assertEquals("35 alphanumeric characters at Q are a version 3 symbol", 3, symbol.version)
        val plane = planeWithSymbol(symbol, width, height, rowStride, scale = 6)
        val out = ByteArray(width * height)
        assertTrue(dwQrCompactLuminance(plane, rowStride, 1, DwQrCrop(0, 0, width, height), out))
        val read = decoder.decode(out, width, height)
        assertEquals(token, read)
        val parsed = ArtisanCardCode.parse(read)
        assertTrue(parsed is ArtisanCardCode.Result.Ok)
        assertEquals("7K3QM9XA2F", (parsed as ArtisanCardCode.Result.Ok).card.code)
    }

    @Test
    fun `the decoder refuses a frame smaller than its own dimensions`() {
        val decoder = DwQrLiveDecoder()
        assertNull(decoder.decode(ByteArray(10), 100, 100))
        assertNull(decoder.decode(ByteArray(0), 0, 0))
        assertNull(decoder.decode(ByteArray(100), -1, 10))
    }

    @Test
    fun `the upright frame swaps the buffer's sides on a quarter turn and not otherwise`() {
        assertEquals(1280, dwQrUprightWidth(1280, 720, 0))
        assertEquals(720, dwQrUprightHeight(1280, 720, 0))
        assertEquals(1280, dwQrUprightWidth(1280, 720, 180))
        assertEquals(720, dwQrUprightHeight(1280, 720, 180))
        assertEquals(720, dwQrUprightWidth(1280, 720, 90))
        assertEquals(1280, dwQrUprightHeight(1280, 720, 90))
        assertEquals(720, dwQrUprightWidth(1280, 720, 270))
        assertEquals(1280, dwQrUprightHeight(1280, 720, 270))
    }

    @Test
    fun `the buffer map and the upright map describe the same drawn box`() {
        val bufferWidth = 1280
        val bufferHeight = 720
        val displayed = DwQrCrop(0, 72, 1280, 576)
        val reticle = DwQrFraction(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.5f)

        for (rotation in listOf(0, 90, 180, 270)) {
            val inBuffer = dwQrCropInBuffer(
                reticle = reticle,
                displayed = displayed,
                rotationDegrees = rotation,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                margin = 0f,
            )
            assertNotNull("rotation $rotation must produce a crop", inBuffer)
            inBuffer!!

            val upright = dwQrReticleInUprightFrame(
                reticle = reticle,
                displayed = displayed,
                rotationDegrees = rotation,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                margin = 0f,
            )
            assertNotNull("rotation $rotation must produce an upright box", upright)
            upright!!

            val frameWidth = dwQrUprightWidth(bufferWidth, bufferHeight, rotation)
            val frameHeight = dwQrUprightHeight(bufferWidth, bufferHeight, rotation)

            fun uprightOf(u: Int, v: Int): Pair<Float, Float> = when (rotation) {
                90 -> (bufferHeight - v).toFloat() to u.toFloat()
                180 -> (bufferWidth - u).toFloat() to (bufferHeight - v).toFloat()
                270 -> v.toFloat() to (bufferWidth - u).toFloat()
                else -> u.toFloat() to v.toFloat()
            }

            val corners = listOf(
                uprightOf(inBuffer.left, inBuffer.top),
                uprightOf(inBuffer.left + inBuffer.width, inBuffer.top + inBuffer.height),
            )
            val expectedLeft = corners.minOf { it.first } / frameWidth
            val expectedRight = corners.maxOf { it.first } / frameWidth
            val expectedTop = corners.minOf { it.second } / frameHeight
            val expectedBottom = corners.maxOf { it.second } / frameHeight

            val slackX = 2f / frameWidth
            val slackY = 2f / frameHeight
            assertEquals("rotation $rotation left", expectedLeft, upright.left, slackX)
            assertEquals("rotation $rotation right", expectedRight, upright.right, slackX)
            assertEquals("rotation $rotation top", expectedTop, upright.top, slackY)
            assertEquals("rotation $rotation bottom", expectedBottom, upright.bottom, slackY)
        }
    }

    @Test
    fun `the drawn box lands centred in the upright frame on a portrait handset`() {
        val reticle = dwQrReticleFraction(1080, 2337)!!
        val displayed = DwQrCrop(0, 72, 1280, 576)
        val box = dwQrReticleInUprightFrame(
            reticle = reticle,
            displayed = displayed,
            rotationDegrees = 90,
            bufferWidth = 1280,
            bufferHeight = 720,
        )!!
        assertEquals(box.left, 1f - box.right, 2e-3f)
        assertEquals(box.top, 1f - box.bottom, 2e-3f)
        assertTrue("the box must be smaller than the frame", box.right - box.left < 0.99f)
        assertTrue("the box must be a usable size", box.right - box.left > 0.2f)
    }

    @Test
    fun `an upright box that cannot be computed is null rather than a guess`() {
        val reticle = DwQrFraction(0.2f, 0.2f, 0.8f, 0.8f)
        val displayed = DwQrCrop(0, 0, 1280, 720)
        assertNull(dwQrReticleInUprightFrame(reticle, displayed, 45, 1280, 720))
        assertNull(dwQrReticleInUprightFrame(reticle, displayed, 0, 0, 720))
        assertNull(dwQrReticleInUprightFrame(reticle, DwQrCrop(0, 0, 0, 0), 0, 1280, 720))
        assertNull(dwQrReticleInUprightFrame(reticle, DwQrCrop(0, 0, 1400, 720), 0, 1280, 720))
        assertNull(dwQrReticleInUprightFrame(DwQrFraction(0.8f, 0.2f, 0.2f, 0.8f), displayed, 0, 1280, 720))
        assertTrue(dwQrSightingInReticle(DwQrCrop(0, 0, 10, 10), 1280, 720, null))
    }

    @Test
    fun `a code larger than the box still reads and one beside it does not`() {
        val box = DwQrFraction(left = 0.2f, top = 0.3f, right = 0.8f, bottom = 0.7f)
        val width = 720
        val height = 1280

        assertTrue(dwQrSightingInReticle(DwQrCrop(300, 580, 120, 120), width, height, box))
        assertTrue(dwQrSightingInReticle(DwQrCrop(60, 200, 600, 880), width, height, box))
        assertFalse(dwQrSightingInReticle(DwQrCrop(620, 580, 90, 90), width, height, box))
        assertFalse(dwQrSightingInReticle(DwQrCrop(300, 40, 90, 90), width, height, box))
        assertTrue(dwQrSightingInReticle(null, width, height, box))
        assertTrue(dwQrSightingInReticle(DwQrCrop(0, 0, 0, 0), width, height, box))
    }

    @Test
    fun `a sighting inside the drawn box is accepted and one outside it is refused`() {
        val reticle = dwQrReticleFraction(1080, 2337)!!
        val displayed = DwQrCrop(0, 72, 1280, 576)
        val rotation = 90
        val bufferWidth = 1280
        val bufferHeight = 720
        val frameWidth = dwQrUprightWidth(bufferWidth, bufferHeight, rotation)
        val frameHeight = dwQrUprightHeight(bufferWidth, bufferHeight, rotation)
        val box = dwQrReticleInUprightFrame(reticle, displayed, rotation, bufferWidth, bufferHeight)!!

        val centreX = ((box.left + box.right) / 2f * frameWidth).toInt()
        val centreY = ((box.top + box.bottom) / 2f * frameHeight).toInt()
        assertTrue(
            "a code in the middle of the drawn box must read",
            dwQrSightingInReticle(DwQrCrop(centreX - 80, centreY - 80, 160, 160), frameWidth, frameHeight, box),
        )

        val aboveY = (box.top * frameHeight).toInt() / 2
        assertFalse(
            "a code well outside the drawn box must not be used",
            dwQrSightingInReticle(DwQrCrop(centreX - 40, aboveY - 40, 80, 80), frameWidth, frameHeight, box),
        )
    }
}
