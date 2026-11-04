package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ImageQualityParityTest {

    private fun seededRandom(seed: Int): () -> Double {
        var state = seed
        return {
            state += 0x6d2b79f5
            var t = state
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    private fun plane(width: Int, height: Int, fill: (Int, Int) -> Double): GreyPlane {
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * width + x] = DwImageQuality.clampToByte(fill(x, y)).toByte()
            }
        }
        return GreyPlane(data, width, height)
    }

    private fun checkerboard(width: Int, height: Int, cell: Int, amplitude: Double = 255.0): GreyPlane {
        val mid = 128 - amplitude / 2
        return plane(width, height) { x, y ->
            if ((x / cell + y / cell) % 2 == 0) mid + amplitude else mid
        }
    }

    private fun pinkNoise(width: Int, height: Int, seed: Int, amplitude: Double = 1.0): GreyPlane {
        val random = seededRandom(seed)
        val field = DoubleArray(width * height)
        for (octave in 0 until 7) {
            val step = 1 shl (6 - octave)
            val weight = 1.0 / (octave + 1)
            val cols = ceil(width.toDouble() / step).toInt() + 2
            val rows = ceil(height.toDouble() / step).toInt() + 2
            val grid = DoubleArray(cols * rows)
            for (index in grid.indices) grid[index] = random() * 2 - 1
            for (y in 0 until height) {
                val gy = y.toDouble() / step
                val y0 = floor(gy).toInt()
                val fy = gy - y0
                for (x in 0 until width) {
                    val gx = x.toDouble() / step
                    val x0 = floor(gx).toInt()
                    val fx = gx - x0
                    val a = grid[y0 * cols + x0]
                    val b = grid[y0 * cols + x0 + 1]
                    val c = grid[(y0 + 1) * cols + x0]
                    val d = grid[(y0 + 1) * cols + x0 + 1]
                    val top = a + (b - a) * fx
                    val bottom = c + (d - c) * fx
                    field[y * width + x] += (top + (bottom - top) * fy) * weight
                }
            }
        }
        var lowest = Double.POSITIVE_INFINITY
        var highest = Double.NEGATIVE_INFINITY
        for (value in field) {
            if (value < lowest) lowest = value
            if (value > highest) highest = value
        }
        val span = if (highest - lowest == 0.0) 1.0 else highest - lowest
        return plane(width, height) { x, y ->
            val normalised = (field[y * width + x] - lowest) / span
            128 + (normalised - 0.5) * 255 * amplitude
        }
    }

    private fun boxBlur(source: GreyPlane, radius: Int, passes: Int = 3): GreyPlane {
        var current = source
        repeat(passes) {
            val previous = current
            val horizontal = plane(previous.width, previous.height) { x, y ->
                var total = 0.0
                var count = 0
                for (offset in -radius..radius) {
                    val sx = min(previous.width - 1, max(0, x + offset))
                    total += previous.at(y * previous.width + sx)
                    count += 1
                }
                total / count
            }
            current = plane(horizontal.width, horizontal.height) { x, y ->
                var total = 0.0
                var count = 0
                for (offset in -radius..radius) {
                    val sy = min(horizontal.height - 1, max(0, y + offset))
                    total += horizontal.at(sy * horizontal.width + x)
                    count += 1
                }
                total / count
            }
        }
        return current
    }

    private val w = DwImageQuality.WORK_EDGE_PX
    private val h = (DwImageQuality.WORK_EDGE_PX * 3) / 4

    private val photo1337 by lazy { pinkNoise(w, h, 1337) }
    private val photo90210 by lazy { pinkNoise(w, h, 90210) }
    private val photo4242 by lazy { pinkNoise(w, h, 4242) }

    @Test
    fun `the calibrated constants match the web module exactly`() {
        assertEquals(640, DwImageQuality.WORK_EDGE_PX)
        assertEquals(12.0, DwImageQuality.MIN_CONTRAST_STDDEV, 0.0)
        assertEquals(60.0, DwImageQuality.BLUR_VARIANCE_FLOOR, 0.0)
        assertEquals(1280, DwImageQuality.MIN_LONG_EDGE_PX)
        assertEquals(6, DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE)
    }

    @Test
    fun `the working plane size matches the web for every shape a handset produces`() {
        val cases = listOf(
            Triple(4000, 3000, 640 to 480),
            Triple(3000, 4000, 480 to 640),
            Triple(4032, 3024, 640 to 480),
            Triple(4160, 3120, 640 to 480),
            Triple(1600, 1200, 640 to 480),
            Triple(1280, 960, 640 to 480),
            Triple(1279, 959, 640 to 480),
            Triple(640, 480, 640 to 480),
            Triple(500, 400, 500 to 400),
            Triple(1, 1, 1 to 1),
            Triple(1080, 1920, 360 to 640),
            Triple(1023, 768, 640 to 480),
            Triple(6000, 4000, 640 to 427),
            Triple(9000, 1, 640 to 1),
        )
        for ((width, height, expected) in cases) {
            assertEquals("${width}x$height", expected, DwImageQuality.workingSizeFor(width, height))
        }
    }

    @Test
    fun `an exact half rounds to the even neighbour, as a Uint8ClampedArray does`() {
        val ties = listOf(
            28.5 to 28, 21.5 to 22, 14.5 to 14, 7.5 to 8, 32.5 to 32, 25.5 to 26,
            0.5 to 0, 1.5 to 2, 254.5 to 254,
        )
        for ((value, expected) in ties) {
            assertEquals("ToUint8Clamp($value)", expected, DwImageQuality.clampToByte(value))
        }
        assertEquals(0, DwImageQuality.clampToByte(-0.4))
        assertEquals(0, DwImageQuality.clampToByte(-1000.0))
        assertEquals(255, DwImageQuality.clampToByte(255.5))
        assertEquals(255, DwImageQuality.clampToByte(1e9))
        assertEquals(0, DwImageQuality.clampToByte(Double.NaN))
    }

    @Test
    fun `Rec 601 luma matches the web conversion over the whole sweep`() {
        val rgba = intArrayOf(
            0, 0, 0, 255, 37, 91, 53, 255, 74, 182, 106, 255, 111, 17, 159, 255,
            148, 108, 212, 255, 185, 199, 9, 255, 222, 34, 62, 255, 3, 125, 115, 255,
            40, 216, 168, 255, 77, 51, 221, 255, 114, 142, 18, 255, 151, 233, 71, 255,
            188, 68, 124, 255, 225, 159, 177, 255, 6, 250, 230, 255, 43, 85, 27, 255,
            80, 176, 80, 255, 117, 11, 133, 255, 154, 102, 186, 255, 191, 193, 239, 255,
            228, 28, 36, 255, 9, 119, 89, 255, 46, 210, 142, 255, 83, 45, 195, 255,
            120, 136, 248, 255, 157, 227, 45, 255, 194, 62, 98, 255, 231, 153, 151, 255,
            12, 244, 204, 255, 49, 79, 1, 255, 86, 170, 54, 255, 123, 5, 107, 255,
            160, 96, 160, 255, 197, 187, 213, 255, 234, 22, 10, 255, 15, 113, 63, 255,
            52, 204, 116, 255, 89, 39, 169, 255, 126, 130, 222, 255, 163, 221, 19, 255,
            200, 56, 72, 255, 237, 147, 125, 255, 18, 238, 178, 255, 55, 73, 231, 255,
            92, 164, 28, 255, 129, 255, 81, 255, 166, 90, 134, 255, 203, 181, 187, 255,
            240, 16, 240, 255, 21, 107, 37, 255, 58, 198, 90, 255, 95, 33, 143, 255,
            132, 124, 196, 255, 169, 215, 249, 255, 206, 50, 46, 255, 243, 141, 99, 255,
            24, 232, 152, 255, 61, 67, 205, 255, 98, 158, 2, 255, 135, 249, 55, 255,
            172, 84, 108, 255, 209, 175, 161, 255, 246, 10, 214, 255, 27, 101, 11, 255,
            0, 0, 250, 255, 0, 4, 168, 255, 0, 8, 86, 255, 0, 12, 4, 255,
            0, 14, 213, 255, 0, 18, 131, 255,
        )
        val expected = intArrayOf(
            0, 71, 141, 61, 132, 173, 93, 87, 158, 78, 119, 190, 110, 181, 175, 66,
            136, 57, 127, 198, 89, 83, 153, 73, 144, 185, 106, 176, 170, 61, 132, 52,
            122, 193, 84, 78, 149, 69, 139, 181, 101, 171, 165, 86, 127, 197, 118, 188,
            109, 73, 144, 64, 135, 205, 96, 167, 161, 81, 122, 193, 113, 184, 104, 69,
            28, 22, 14, 8, 32, 26,
        )
        val packed = IntArray(rgba.size / 4) { index ->
            val offset = index * 4
            (0xFF shl 24) or (rgba[offset] shl 16) or (rgba[offset + 1] shl 8) or rgba[offset + 2]
        }
        val grey = DwImageQuality.greyPlaneFromArgb(packed, packed.size, 1)
        assertEquals(expected.size, grey.data.size)
        for (index in expected.indices) {
            assertEquals("luma[$index]", expected[index], grey.at(index))
        }
    }

    private data class Expected(
        val name: String,
        val blur: Double,
        val contrast: Double,
        val hash: String,
    )

    private fun corpus(): List<Pair<GreyPlane, Expected>> = listOf(
        photo1337 to Expected("photoLike1337", 732.5529829106159, 32.229153849095134, "2d6e6ac2791892f3"),
        photo90210 to Expected("photoLike90210", 806.5892936902629, 33.34959800281591, "5958694b6cc9321a"),
        boxBlur(photo1337, 1) to Expected("photoLike1337~blur1", 5.12099460738263, 31.024224978594177, "2c6e6ac2791892f3"),
        boxBlur(photo1337, 2) to Expected("photoLike1337~blur2", 2.3568486632858896, 30.495622857836608, "2c6e6ac2791892f3"),
        boxBlur(photo1337, 4) to Expected("photoLike1337~blur4", 1.7608962234124659, 29.579512471313027, "2c6e6ac2795892f3"),
        boxBlur(photo90210, 4) to Expected("photoLike90210~blur4", 1.7676937445493062, 30.414872434269903, "5958694b6cc9321a"),
        boxBlur(photo1337, 1, 1) to Expected("photoLike1337~blur1pass1", 27.031373933318086, 31.391714588100665, "2c6e6ac2791892f3"),
        pinkNoise(w, h, 1337, 0.28) to Expected("lowContrastSharp", 58.98379410390244, 9.028137798849546, "2c6e6ac2595812b3"),
        checkerboard(w, h, 8) to Expected("checkerboard8", 40106.18433651185, 127.5, "a5a54a4aa5a54a4a"),
        checkerboard(w, h, 4) to Expected("checkerboard4", 97062.01518867801, 127.5, "1000100010001000"),
        photo4242 to Expected("photoLike4242", 787.4453147556668, 35.765915797114154, "565656512565e9b2"),
        DwImageQuality.resampleGrey(photo4242, w / 2, h / 2) to
            Expected("photoLike4242~half", 455.9233646215381, 35.21001280181453, "565656512565e9b2"),
        pinkNoise(12, 9, 7) to Expected("tiny12x9seed7", 11801.199183673469, 55.5976962088461, "2c90d44e6e0c4f4d"),
    )

    @Test
    fun `every measurement in the shared case table matches the web`() {
        val differences = mutableListOf<String>()
        for ((image, expected) in corpus()) {
            val blur = DwImageQuality.laplacianVariance(image)
            val contrast = DwImageQuality.contrastStdDev(image)
            val hash = DwImageQuality.differenceHash(image)
            if (kotlin.math.abs(blur - expected.blur) > 1e-9) {
                differences += "${expected.name}: blur ${expected.blur} (web) vs $blur (kotlin)"
            }
            if (kotlin.math.abs(contrast - expected.contrast) > 1e-9) {
                differences += "${expected.name}: contrast ${expected.contrast} (web) vs $contrast (kotlin)"
            }
            if (hash != expected.hash) {
                differences += "${expected.name}: hash ${expected.hash} (web) vs $hash (kotlin)"
            }
        }
        assertEquals("${differences.size} of ${corpus().size * 3} measurements differ:\n" +
            differences.joinToString("\n"), 0, differences.size)
    }

    @Test
    fun `the plane and the hash grid are byte-identical to the web`() {
        val tiny = pinkNoise(12, 9, 7)
        val expectedPlane = intArrayOf(
            117, 139, 149, 137, 64, 82, 63, 49, 0, 46, 102, 127,
            130, 86, 63, 126, 133, 38, 31, 45, 48, 92, 85, 108,
            201, 125, 94, 74, 99, 55, 54, 84, 33, 56, 112, 139,
            160, 185, 105, 108, 111, 128, 78, 125, 88, 83, 80, 110,
            174, 217, 212, 157, 127, 143, 158, 114, 112, 72, 120, 94,
            126, 126, 116, 180, 198, 203, 134, 109, 73, 117, 123, 128,
            79, 151, 100, 175, 216, 219, 200, 160, 140, 106, 77, 85,
            101, 172, 172, 199, 217, 241, 243, 142, 105, 110, 120, 121,
            164, 231, 212, 215, 237, 230, 255, 179, 191, 202, 196, 114,
        )
        assertEquals(expectedPlane.size, tiny.data.size)
        for (index in expectedPlane.indices) {
            assertEquals("plane[$index]", expectedPlane[index], tiny.at(index))
        }

        val expectedGrid = intArrayOf(
            117, 139, 143, 64, 82, 56, 0, 46, 114,
            130, 86, 94, 133, 38, 38, 48, 92, 96,
            201, 125, 84, 99, 55, 69, 33, 56, 126,
            160, 185, 106, 111, 128, 102, 88, 83, 95,
            174, 217, 184, 127, 143, 136, 112, 72, 107,
            126, 126, 148, 198, 203, 122, 73, 117, 126,
            79, 151, 138, 216, 219, 180, 140, 106, 81,
            132, 202, 200, 227, 236, 205, 148, 156, 138,
        )
        val grid = DwImageQuality.resampleGrey(tiny, 9, 8)
        assertEquals(expectedGrid.size, grid.data.size)
        for (index in expectedGrid.indices) {
            assertEquals("grid[$index]", expectedGrid[index], grid.at(index))
        }
    }

    @Test
    fun `hamming distance matches the web, including the mismatched-length refusal`() {
        val cases = listOf(
            Triple("0000000000000000", "0000000000000000", 0),
            Triple("0000000000000000", "000000000000000f", 4),
            Triple("ffffffffffffffff", "0000000000000000", 64),
            Triple("0f1e2d3c4b5a6978", "0f1e2d3c4b5a6979", 1),
            Triple("abc", "abcd", 64),
        )
        for ((left, right, expected) in cases) {
            assertEquals("$left vs $right", expected, DwImageQuality.hammingDistance(left, right))
        }
    }

    @Test
    fun `sharp and blurred populations are separated by more than an order of magnitude`() {
        val sharp = listOf(photo1337, photo90210).map { DwImageQuality.laplacianVariance(it) }
        val blurred = listOf(photo1337, photo90210)
            .flatMap { image -> listOf(1, 2, 4).map { DwImageQuality.laplacianVariance(boxBlur(image, it)) } }

        val worstSharp = sharp.min()
        val bestBlurred = blurred.max()
        assertTrue(
            "worst sharp $worstSharp must clear ${DwImageQuality.BLUR_VARIANCE_FLOOR * 10}",
            worstSharp > DwImageQuality.BLUR_VARIANCE_FLOOR * 10
        )
        assertTrue(
            "best blurred $bestBlurred must sit under ${DwImageQuality.BLUR_VARIANCE_FLOOR}",
            bestBlurred < DwImageQuality.BLUR_VARIANCE_FLOOR
        )
        assertTrue(worstSharp / max(bestBlurred, 1.0) > 100)

        for (image in listOf(checkerboard(w, h, 8), checkerboard(w, h, 4))) {
            assertTrue(DwImageQuality.laplacianVariance(image) > DwImageQuality.BLUR_VARIANCE_FLOOR * 100)
        }
    }

    @Test
    fun `a sharp but low-contrast photograph is never called blurred`() {
        val flat = pinkNoise(w, h, 1337, 0.28)
        val blur = DwImageQuality.laplacianVariance(flat)
        val contrast = DwImageQuality.contrastStdDev(flat)

        assertTrue("blur $blur must be below the floor for this to be a real trap", blur < DwImageQuality.BLUR_VARIANCE_FLOOR)
        assertTrue("contrast $contrast must be below the guard", contrast < DwImageQuality.MIN_CONTRAST_STDDEV)
        assertFalse(DwImageQuality.isBlurred(blur, contrast))
    }

    @Test
    fun `the contrast guard does not swallow a genuinely blurred photograph`() {
        val soft = boxBlur(photo1337, 4)
        val blur = DwImageQuality.laplacianVariance(soft)
        val contrast = DwImageQuality.contrastStdDev(soft)
        assertTrue(contrast > DwImageQuality.MIN_CONTRAST_STDDEV)
        assertTrue(DwImageQuality.isBlurred(blur, contrast))
    }

    @Test
    fun `identical, near-duplicate and unrelated images land in separated distance bands`() {
        val hashA = DwImageQuality.differenceHash(photo1337)
        val hashSelf = DwImageQuality.differenceHash(photo1337)
        val hashNear = DwImageQuality.differenceHash(boxBlur(photo1337, 1, 1))
        val hashB = DwImageQuality.differenceHash(photo90210)

        assertEquals(0, DwImageQuality.hammingDistance(hashA, hashSelf))
        assertTrue(DwImageQuality.hammingDistance(hashA, hashNear) <= DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE)
        assertTrue(
            DwImageQuality.hammingDistance(hashA, hashB) > DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE * 3
        )
    }

    @Test
    fun `a downscale of one photograph still hashes as the same shot`() {
        val half = DwImageQuality.resampleGrey(photo4242, w / 2, h / 2)
        val distance = DwImageQuality.hammingDistance(
            DwImageQuality.differenceHash(photo4242),
            DwImageQuality.differenceHash(half)
        )
        assertTrue("distance $distance", distance <= DwImageQuality.NEAR_DUPLICATE_MAX_DISTANCE)
    }

    private fun measurement(
        width: Int = 4000,
        height: Int = 3000,
        blur: Double = 900.0,
        contrast: Double = 60.0,
        hash: String = "0".repeat(16),
    ) = ImageMeasurement(width, height, blur, contrast, hash, 0L)

    @Test
    fun `findings match the web word for word`() {
        assertEquals(emptyList<QualityFinding>(), DwImageQuality.findQualityIssues(measurement()))

        val small = DwImageQuality.findQualityIssues(measurement(width = 1024, height = 768))
        assertEquals(1, small.size)
        assertEquals(QualityFlag.LOW_RESOLUTION, small[0].flag)
        assertEquals(QualitySeverity.MEDIUM, small[0].severity)
        assertEquals(
            "1024x768 — a catalogue plate needs about 1280px on the long edge to print sharply at A4. " +
                "This will still be saved; it will simply print soft.",
            small[0].message
        )
        assertEquals(emptyList<QualityFinding>(), DwImageQuality.findQualityIssues(measurement(width = 1300, height = 4000)))

        val blurred = DwImageQuality.findQualityIssues(measurement(blur = 41.6))
        assertEquals(1, blurred.size)
        assertEquals(QualityFlag.BLUR, blurred[0].flag)
        assertEquals(
            "Sharpness score 42 — a sharp photograph normally scores well above 60 here. This one may " +
                "be out of focus. If it is meant to be soft, or it is the only shot you have, keep it.",
            blurred[0].message
        )
        assertEquals(
            emptyList<QualityFinding>(),
            DwImageQuality.findQualityIssues(measurement(blur = 41.6, contrast = 9.0))
        )

        val exact = DwImageQuality.findQualityIssues(
            measurement(),
            checksum = "sha256:abc",
            attached = listOf(
                AttachedImage("loom-front.jpg", checksum = "sha256:abc"),
                AttachedImage("b.jpg", checksum = "sha256:abc"),
            )
        )
        assertEquals(1, exact.size)
        assertEquals(QualityFlag.DUPLICATE, exact[0].flag)
        assertEquals(QualitySeverity.LOW, exact[0].severity)
        assertEquals(
            "The same file is already attached here as \"loom-front.jpg\", \"b.jpg\" — identical " +
                "content, so this looks like the same import twice.",
            exact[0].message
        )

        val near = DwImageQuality.findQualityIssues(
            measurement(),
            attached = listOf(AttachedImage("same-shot.jpg", perceptualHash = "0".repeat(15) + "3"))
        )
        assertEquals(
            "This looks like the same shot as \"same-shot.jpg\". Two views of one object are worth " +
                "keeping; two copies of one view are not.",
            near[0].message
        )
        assertEquals(
            emptyList<QualityFinding>(),
            DwImageQuality.findQualityIssues(
                measurement(),
                attached = listOf(AttachedImage("other-product.jpg", perceptualHash = "f".repeat(16)))
            )
        )

        val both = DwImageQuality.findQualityIssues(
            measurement(),
            checksum = "sha256:abc",
            attached = listOf(AttachedImage("twin.jpg", checksum = "sha256:abc", perceptualHash = "0".repeat(16)))
        )
        assertEquals(1, both.count { it.flag == QualityFlag.DUPLICATE })

        assertEquals(
            emptyList<QualityFinding>(),
            DwImageQuality.findQualityIssues(
                measurement(),
                checksum = null,
                attached = listOf(AttachedImage("x.jpg", checksum = "sha256:abc"))
            )
        )
        assertEquals(
            emptyList<QualityFinding>(),
            DwImageQuality.findQualityIssues(
                measurement(),
                checksum = "sha256:abc",
                attached = listOf(AttachedImage("old.jpg", checksum = null))
            )
        )

        val everything = DwImageQuality.findQualityIssues(
            measurement(width = 800, height = 600, blur = 12.25, contrast = 44.0),
            checksum = "sha256:zz",
            attached = listOf(AttachedImage("one.jpg", checksum = "sha256:zz"))
        )
        assertEquals(
            listOf(QualityFlag.BLUR, QualityFlag.LOW_RESOLUTION, QualityFlag.DUPLICATE),
            everything.map { it.flag }
        )
        assertTrue(everything[0].message.startsWith("Sharpness score 12 —"))
    }

    private fun row(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = pairs.toMap()

    @Test
    fun `missing views match the web, and exist only where the registry has real slots`() {
        val two = DwImageQuality.findMissingViews(
            "existingProduct",
            row("viewFront" to JsonPrimitive("media-1"), "viewDetail" to JsonPrimitive("media-2"))
        )
        assertEquals(1, two.size)
        assertEquals(QualityFlag.MISSING_VIEW, two[0].flag)
        assertEquals(QualitySeverity.LOW, two[0].severity)
        assertEquals(
            "2 of 3 views captured — no back view. That view is easiest to add now, while the product " +
                "is still in front of you.",
            two[0].message
        )

        val one = DwImageQuality.findMissingViews("existingProduct", row("viewFront" to JsonPrimitive("media-1")))
        assertEquals(
            "1 of 3 views captured — no back view, no detail view. Those views are easiest to add now, " +
                "while the product is still in front of you.",
            one[0].message
        )

        assertEquals(emptyList<QualityFinding>(), DwImageQuality.findMissingViews("existingProduct", emptyMap()))
        assertEquals(
            emptyList<QualityFinding>(),
            DwImageQuality.findMissingViews(
                "existingProduct",
                row(
                    "viewFront" to JsonPrimitive("a"),
                    "viewBack" to JsonPrimitive("b"),
                    "viewDetail" to JsonPrimitive("c"),
                )
            )
        )

        val emptied = DwImageQuality.findMissingViews(
            "existingProduct",
            row("viewFront" to JsonArray(emptyList()), "viewBack" to JsonArray(listOf(JsonPrimitive("x"))))
        )
        assertEquals(
            "1 of 3 views captured — no front view, no detail view. Those views are easiest to add now, " +
                "while the product is still in front of you.",
            emptied[0].message
        )

        assertEquals(emptyList<QualityFinding>(), DwImageQuality.findMissingViews("sketch", row("image" to JsonPrimitive("a"))))
        assertEquals(
            emptyList<QualityFinding>(),
            DwImageQuality.findMissingViews("finalProduct", row("finalPhotos" to JsonArray(listOf(JsonPrimitive("a")))))
        )
    }
}
