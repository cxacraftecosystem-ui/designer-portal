package com.designprototype.workshop.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The .docx writer must not hold every photograph's bytes on the heap at once.
 *
 * WHAT THIS PINS. `MediaPart` carried the loaded `ByteArray`, every part was registered while the
 * body was being emitted, and nothing was written until the very end — so a photo-heavy workshop had
 * every original JPEG (4-6 MB each, unsubsampled, uncapped) simultaneously resident before a byte
 * reached the zip. On the Android 8/9 handsets this fleet targets that is enough to be killed: the
 * designer, with an officer waiting, was told "The report could not be generated." while the .pdf of
 * the same workshop succeeded, because `PdfWriter` deliberately refuses to retain bytes and this
 * writer did not.
 *
 * IT IS ASSERTED THROUGH THE LOADER, not through a heap measurement, because "how many bytes are
 * live" is not something a JVM unit test can ask honestly. What the loader can say is how many times
 * it was asked, and asking twice per photograph — once to probe, once to deflate — is exactly the
 * shape of "nothing was kept". One call per photograph would mean the bytes were kept.
 *
 * The writer needs nothing but `java.util.zip`, which is what lets this run as a plain JVM test.
 */
class DocxMediaStreamingTest {

    /**
     * A PNG header the writer's own `probeImageSize` reads, with padding standing in for pixels.
     *
     * Hand-built rather than loaded from a fixture so the size the probe reports is stated here in
     * the test that depends on it. `pngSize` reads the magic and then the IHDR width and height at
     * byte 16 and 20, and looks no further, so this is a complete input for it.
     */
    private fun fakePng(width: Int, height: Int, padding: Int = 64): ByteArray {
        val out = ByteArray(24 + padding)
        val magic = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        magic.copyInto(out)
        // Length + "IHDR", which pngSize skips over but a reader of this file should not have to
        // guess at.
        out[11] = 13
        "IHDR".forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        fun be32(at: Int, value: Int) {
            out[at] = (value ushr 24).toByte()
            out[at + 1] = (value ushr 16).toByte()
            out[at + 2] = (value ushr 8).toByte()
            out[at + 3] = value.toByte()
        }
        be32(16, width)
        be32(20, height)
        // Something recognisable in the payload so the zip entry can be checked byte for byte.
        for (i in 24 until out.size) out[i] = (width + i).toByte()
        return out
    }

    private fun documentOf(sources: List<String>): ReportDocument {
        val blocks = sources.map { source ->
            ImageBlock(image = ImageRef(source = source, widthPx = 1600, heightPx = 1200))
        }
        return ReportDocument(
            meta = ReportMeta(title = "Barpali cluster"),
            theme = ReportTheme(),
            blocks = blocks,
            images = collectImages(blocks),
        )
    }

    private fun entriesOf(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    @Test
    fun `each photograph is fetched again at write time rather than held from emit to zip`() {
        val sources = (1..5).map { "/data/photo$it.png" }
        val calls = LinkedHashMap<String, Int>()
        val png = fakePng(1600, 1200)
        val writer = DocxWriter(documentOf(sources)) { ref ->
            calls[ref.source] = (calls[ref.source] ?: 0) + 1
            png
        }
        val bytes = writer.build()

        assertEquals(
            "every photograph must be asked for exactly twice — once to probe its size and mime " +
                "while the body is emitted, once to deflate it into its entry. One call each would " +
                "mean the writer kept the bytes, which is the defect: $calls",
            sources.associateWith { 2 },
            calls,
        )

        val entries = entriesOf(bytes)
        assertEquals(
            "one media part per distinct photograph",
            5,
            entries.keys.count { it.startsWith("word/media/") },
        )
        assertTrue(
            "the re-fetched bytes are what reached the package",
            png.contentEquals(entries.getValue("word/media/image1.png")),
        )
    }

    @Test
    fun `a photograph that disappears between the two reads costs one picture, not the report`() {
        // The window is milliseconds inside one export against app-private storage, so this is
        // remote — but a dangling relationship is not a missing picture, it is a package Word
        // refuses with "the file appears to be corrupted", and one unreadable photo must not cost
        // the designer the whole document.
        val png = fakePng(1600, 1200)
        var seen = 0
        val writer = DocxWriter(documentOf(listOf("/data/gone.png"))) {
            seen += 1
            if (seen == 1) png else null
        }
        val entries = entriesOf(writer.build())

        assertNotNull(
            "the part the relationship points at must exist even when its bytes do not",
            entries["word/media/image1.png"],
        )
        assertEquals(0, entries.getValue("word/media/image1.png").size)
        assertEquals(
            "and the designer is told which photograph is missing",
            listOf("/data/gone.png"),
            writer.droppedImages.toList(),
        )
    }
}
