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

/**
 * THE PRINTER AND THE READER, CHECKED AGAINST EACH OTHER — on the JVM, on every build.
 *
 * ── WHY THIS TEST CAN EXIST AT ALL, AND WHY THAT DECIDED THE DEPENDENCY ───────────────────────
 *
 * `IdentityCardRecognizer`'s header states the limit of the other on-device reader plainly: ML Kit
 * "cannot run in a JVM unit test … every claim about recognition ACCURACY is therefore a hardware
 * claim that has not been made yet", on a machine with no device and no emulator. That is a real
 * gap and it is one of the reasons `com.google.zxing:core` was chosen here over
 * `com.google.mlkit:barcode-scanning` — ZXing is pure Java, so it runs on this classpath, and the
 * claim below is not a hardware claim.
 *
 * WHAT IT ACTUALLY PROVES: a symbol produced by THIS APPLICATION'S OWN encoder — [DwQrEncode], a
 * hand-written ISO 18004 implementation with its own Reed-Solomon, its own masking and its own
 * version tables — is read back by the library that will read it on the handset, and the text
 * survives byte for byte. Neither half is trusted; they are compared.
 *
 * That is worth more than it looks. `DwQrEncodeTest` already checks the encoder against the
 * standard's own arithmetic, but "my tables add up" and "an independent decoder can read what I
 * drew" are different claims, and only the second one is what a designer holding a printed card
 * needs. A masking or interleaving slip that satisfied every internal invariant would produce a
 * card that no scanner in the world reads, and nothing before this test could have caught it.
 *
 * ── WHAT IT DOES NOT PROVE ────────────────────────────────────────────────────────────────────
 *
 * Nothing about a CAMERA. There is no lens here, no perspective, no glare and no motion blur — the
 * matrix is rendered to clean pixels at a known scale. Detection off a real photograph in courtyard
 * light is exactly the hardware claim this repository is careful not to make, and the decode ladder
 * in [DwQrDecode] exists because it cannot be made here. What is verified is the whole chain from
 * the app's own bits to a decoded string, which is the half that can be.
 */
class DwQrDecodeTest {

    /**
     * The app's own symbol, rendered to pixels the way a printed card presents one to a scanner.
     *
     * [scale] is pixels per module and [quiet] is the quiet zone in modules. FOUR MODULES OF QUIET
     * ZONE IS NOT DECORATION — it is what ISO 18004 requires and what [DwQrEncode.svgPath] already
     * accounts for, and a symbol rendered hard against its border does not scan. Rendering with less
     * here would make this test agree with a card that fails in the field.
     *
     * White is 0xFFFFFFFF and dark is 0xFF000000 in ARGB, which is what [RGBLuminanceSource] reads.
     */
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

    /**
     * ZXing configured EXACTLY as `ZxingQrImageDecoder` configures it.
     *
     * Same hints, same binarizer, same source type. A test that decoded with different settings from
     * the shipping reader would be checking a decoder nobody runs — and the settings are load-bearing:
     * QR_CODE-only is what makes a shop barcode report as "no QR found" rather than as a workshop
     * code that fails its check digit.
     */
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

    // ── The round trip ───────────────────────────────────────────────────────────────────────────

    /**
     * A real workshop code, printed by this app's encoder, read by the app's decoder, parsed by the
     * app's parser — and it comes out as the same record.
     *
     * THIS IS THE WHOLE FEATURE IN ONE ASSERTION. Every link is the shipping one: the code comes from
     * [encodeWorkshopCode], the symbol from [DwQrEncode], the read from ZXing with the shipping
     * hints, and the parse from [decodeWorkshopCode] — which is the same parser the typed box uses,
     * deliberately, so a scanned code and a typed one can never be judged differently.
     */
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

    /**
     * Every record type this app prints a tag for survives the round trip.
     *
     * Not one sample. The type letter is a single character in the payload, and a masking or
     * interleaving slip that only bit one letter would pass a single-case test while making one
     * class of tag — prototypes, say, which are the ones tied to physical objects — unreadable.
     */
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

    /**
     * THE RESOLUTION FLOOR, MEASURED RATHER THAN ASSUMED — and it is TWO pixels per module, not one.
     *
     * ── WHY THIS TEST SAYS SOMETHING DIFFERENT FROM WHAT IT FIRST SAID ────────────────────────
     *
     * It was written asserting that the symbol reads at ONE pixel per module, on the reasoning that
     * "at one pixel per module there is nothing left to lose". THAT IS FALSE AND THE TEST CAUGHT IT:
     * `scale = 1` decodes to null. ZXing's grid sampler needs to find a module's centre and cannot do
     * it when a module is a single pixel — there is no sub-pixel to sample. The assertion has been
     * rewritten to pin the real floor rather than deleted, because the floor is a NUMBER THE LADDER
     * DEPENDS ON and nothing else in this repository records it.
     *
     * ── WHAT THE NUMBER MEANS FOR [DW_QR_SAMPLE_LADDER], WHICH IS WHY IT IS WORTH PINNING ───────
     *
     * Rung one halves the picture. Halving is safe only while the symbol is at least FOUR pixels per
     * module in the original, because half of four is the floor proved here. A code filling a
     * reasonable part of a photograph clears that easily; a code that is small in frame does not, and
     * rung one destroys it. That is exactly what rung two — the untouched original — is for, and this
     * test is the evidence that the second rung is a necessity rather than belt and braces.
     *
     * If a future change ever drops the original from the ladder, this docstring is the argument
     * against it and this number is the reason.
     */
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

    /**
     * THE QUIET ZONE, TESTED AGAINST WHAT ACTUALLY THREATENS IT: ink, not the edge of the picture.
     *
     * ── WHY THIS TEST ALSO SAYS SOMETHING DIFFERENT FROM WHAT IT FIRST SAID ───────────────────
     *
     * It was written as "a symbol rendered with `quiet = 0` does not scan". THE TEST CAUGHT THAT
     * BEING FALSE: ZXing reads it perfectly. A symbol that fills the whole image has its finder
     * patterns against the image border, and the detector handles that — the image edge is itself a
     * boundary, so there is nothing for the symbol to be confused with.
     *
     * The old assertion was therefore testing a property of the RENDERER, not the property
     * [DwQrEncode.svgPath] actually cares about. Its comment says a symbol printed "hard against a
     * card's border" does not scan, and a printed card has the rest of the card around it: a rule,
     * a border, a record name in black. THAT is the case where a missing quiet zone kills the read,
     * because the finder pattern's outer light ring fuses with the ink beside it and the detector
     * measures the wrong module size.
     *
     * So the symbol is now surrounded by a dark frame, which is what a card border is, and the two
     * renderings differ by the quiet zone alone. That makes the claim in `svgPath`'s comment
     * checkable instead of merely stated — and if a card renderer ever drops the margin, the failure
     * is a red test rather than a sheet of three hundred printed tags nobody can read.
     */
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

    /**
     * Put a dark border around a rendering — a card's printed rule, in other words.
     *
     * Eight pixels of solid black on all four sides. It is not a quiet zone substitute and is not
     * meant to be: it is the INK that a quiet zone exists to hold the symbol away from.
     */
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

    /** A picture with nothing in it decodes to nothing, rather than to a spurious string. */
    @Test
    fun `a blank picture yields no payload`() {
        val side = 200
        assertNull(decode(IntArray(side * side) { 0xFFFFFFFF.toInt() }, side, side))
    }

    // ── The ladder and the refusals ──────────────────────────────────────────────────────────────

    /**
     * The ladder starts by HALVING, then goes to full size, then halves again.
     *
     * Not smallest-first and not largest-first, and the order is the whole design: rung one is the
     * fast pass that decodes an ordinary screenshot in milliseconds; rung two rescues the symbol that
     * was too small in frame to survive halving; rung three rescues the opposite failure, a
     * photograph so close and so high-resolution that module edges read as texture. Reordering it
     * would trade one of those away.
     */
    @Test
    fun `the sample ladder tries a halving, then the original, then a quarter`() {
        assertEquals(listOf(2, 1, 4), DW_QR_SAMPLE_LADDER)
        assertTrue("the original must be attempted, or a small-in-frame code can never read", DW_QR_SAMPLE_LADDER.contains(1))
    }

    /**
     * The two "nothing found" sentences are different, because they are two different next actions.
     *
     * A designer at the camera can move closer and press again. A designer who was SENT a screenshot
     * cannot retake it and has no card to point at — telling them to "fill the frame" is telling them
     * to do something impossible, and it is the single most likely place this feature would waste
     * somebody's afternoon.
     */
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

    /**
     * "This phone cannot open that file" is never reported as "your card is unreadable".
     *
     * The web lane closed exactly this on its own decoder — HEIC got its own refusal — and it is the
     * same trap here: a chat app hands over a format `BitmapFactory` will not decode, and a message
     * blaming the card sends a designer to re-photograph something that was always fine.
     */
    @Test
    fun `an unopenable file is blamed on the file and not on the card`() {
        assertTrue(DW_QR_UNREADABLE_PICTURE.contains("could not be opened"))
        assertTrue("naming a real culprit is what stops the wrong diagnosis", DW_QR_UNREADABLE_PICTURE.contains("HEIC"))
        assertTrue(
            "it must not describe the code as unreadable",
            !DW_QR_UNREADABLE_PICTURE.contains("No QR code"),
        )
    }

    /**
     * A payload that is not ours is refused by the SHARED parser, not by the scanner.
     *
     * The scanner hands over whatever text it read and forms no opinion. That is what keeps a payment
     * QR photographed by mistake and a mistyped code answering the same sentence — and the sentence
     * is the parser's, which is the one that knows what a workshop code is.
     */
    @Test
    fun `a foreign qr payload is refused by the same parser a typed code meets`() {
        // UPPER-CASE AND NO `?` OR `@`, because [DwQrEncode] is an alphanumeric-mode-only encoder and
        // refuses anything outside "0-9 A-Z $%*+-./: and space" — which is a property of THIS app's
        // printer, not of QR. The fixture is therefore something the printer can actually draw; a
        // real payment or web QR carries bytes this encoder would never be asked to produce, and the
        // point being made is about the PARSER, which sees only a decoded string either way.
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
