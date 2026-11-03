package com.designprototype.workshop.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwQrDecodeTest {

    private fun render(symbol: DwQrSymbol, scale: Int = 4, quiet: Int = 4): Triple<IntArray, Int, Int> {
        val side = (symbol.size + quiet * 2) * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (row in 0 until symbol.size) {
            for (col in 0 until symbol.size) {
                if (!symbol.matrix[row][col]) continue
                val top = (row + quiet) * scale
                val left = (col + quiet) * scale
                for (y in top until top + scale) {
                    for (x in left until left + scale) {
                        pixels[y * side + x] = 0xFF000000.toInt()
                    }
                }
            }
        }
        return Triple(pixels, side, side)
    }

    private fun decode(pixels: IntArray, width: Int, height: Int): String? {
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return runCatching {
            MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                    )
                )
            }.decode(binary).text
        }.getOrNull()
    }

    private fun roundTrip(text: String, scale: Int = 4): String? {
        val symbol = DwQrEncode.encode(text)
        val (pixels, width, height) = render(symbol, scale = scale)
        return decode(pixels, width, height)
    }

    @Test
    fun `a card this app prints is read back as the record it names`() {
        val encoded = encodeWorkshopCode(DwWorkshopRecordType.ARTISAN, "cmsvfnb4y0001qq1bzd2g48lq")
        assertTrue("the fixture must be a real code, or this test asserts nothing", encoded is DwEncodeResult.Ok)
        val code = (encoded as DwEncodeResult.Ok).code

        val scanned = roundTrip(code)
        assertEquals("the symbol did not survive its own printer and an independent reader", code, scanned)

        val parsed = decodeWorkshopCode(scanned)
        assertTrue("a scanned code must reach the same parser a typed one does", parsed is DwDecodeResult.Ok)
        assertEquals(DwWorkshopRecordType.ARTISAN, (parsed as DwDecodeResult.Ok).ref.recordType)
        assertEquals("cmsvfnb4y0001qq1bzd2g48lq", parsed.ref.id)
        assertEquals(encoded.ref, parsed.ref)
    }

    @Test
    fun `every record type this app can print survives the round trip`() {
        DwWorkshopRecordType.entries.forEach { type ->
            val encoded = encodeWorkshopCode(type, "cmsvfnb4y0001qq1bzd2g48lq")
            if (encoded !is DwEncodeResult.Ok) return@forEach
            assertEquals(
                "a ${type.name} tag did not read back",
                encoded.code,
                roundTrip(encoded.code),
            )
        }
    }

    @Test
    fun `two pixels per module is the floor, and one is not enough`() {
        val encoded = encodeWorkshopCode(DwWorkshopRecordType.PROTOTYPE, "cmsvfnb4y0001qq1bzd2g48lq")
        val code = (encoded as DwEncodeResult.Ok).code
        assertEquals("two pixels per module reads", code, roundTrip(code, scale = 2))
        assertNull(
            "one pixel per module does NOT read — this is why the ladder keeps an un-halved rung",
            roundTrip(code, scale = 1),
        )
    }

    @Test
    fun `ink hard against the symbol is what a missing quiet zone cannot survive`() {
        val encoded = encodeWorkshopCode(DwWorkshopRecordType.ARTISAN, "cmsvfnb4y0001qq1bzd2g48lq")
        val code = (encoded as DwEncodeResult.Ok).code
        val symbol = DwQrEncode.encode(code)

        val (withZone, w1, h1) = framed(render(symbol, scale = 4, quiet = 4))
        assertEquals(
            "four modules of quiet zone survive a card border — the control case",
            code,
            decode(withZone, w1, h1),
        )

        val (withoutZone, w2, h2) = framed(render(symbol, scale = 4, quiet = 0))
        assertNotEquals(
            "with the border touching the symbol the read is lost — this is what svgPath prevents",
            code,
            decode(withoutZone, w2, h2),
        )
    }

    private fun framed(source: Triple<IntArray, Int, Int>): Triple<IntArray, Int, Int> {
        val (pixels, width, height) = source
        val border = 8
        val outWidth = width + border * 2
        val outHeight = height + border * 2
        val out = IntArray(outWidth * outHeight) { 0xFF000000.toInt() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[(y + border) * outWidth + (x + border)] = pixels[y * width + x]
            }
        }
        return Triple(out, outWidth, outHeight)
    }

    @Test
    fun `a blank picture yields no payload`() {
        val side = 200
        assertNull(decode(IntArray(side * side) { 0xFFFFFFFF.toInt() }, side, side))
    }

    @Test
    fun `the sample ladder tries a halving, then the original, then a quarter`() {
        assertEquals(listOf(2, 1, 4), DW_QR_SAMPLE_LADDER)
        assertTrue("the original must be attempted, or a small-in-frame code can never read", DW_QR_SAMPLE_LADDER.contains(1))
    }

    @Test
    fun `the camera and the picture are told different things when nothing is found`() {
        val camera = dwQrNothingFound(DwQrSource.CAMERA)
        val picture = dwQrNothingFound(DwQrSource.PICTURE)
        assertNotEquals(camera, picture)
        assertTrue("only the camera can be re-aimed", camera.contains("Fill the frame"))
        assertTrue("a sent picture cannot be retaken; a crop can be", picture.contains("crop"))
        assertTrue("both must offer the typed route, which always works", camera.contains("type the code"))
        assertTrue(picture.contains("type the code"))
    }

    @Test
    fun `an unopenable file is blamed on the file and not on the card`() {
        assertTrue(DW_QR_UNREADABLE_PICTURE.contains("could not be opened"))
        assertTrue("naming a real culprit is what stops the wrong diagnosis", DW_QR_UNREADABLE_PICTURE.contains("HEIC"))
        assertTrue(
            "it must not describe the code as unreadable",
            !DW_QR_UNREADABLE_PICTURE.contains("No QR code"),
        )
    }

    @Test
    fun `a foreign qr payload is refused by the same parser a typed code meets`() {
        val foreign = "HTTPS://EXAMPLE.ORG/SHOP/4471"
        val symbol = DwQrEncode.encode(foreign)
        val (pixels, width, height) = render(symbol)
        val scanned = decode(pixels, width, height)
        assertEquals(foreign, scanned)

        val parsed = decodeWorkshopCode(scanned)
        assertTrue(parsed is DwDecodeResult.Refused)
        assertEquals(DwDecodeRefusal.NOT_A_WORKSHOP_CODE, (parsed as DwDecodeResult.Refused).reason)
        assertTrue("the refusal names what a workshop code looks like", parsed.message.contains("DPW"))
    }
}
