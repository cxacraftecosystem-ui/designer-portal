package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

/**
 * The streamed download manifest — the reader that replaced a 48 MB allocation.
 *
 * WHAT THE DEFECT WAS. `WorkshopRepositoryApi.datasetManifest()` and `.dataManifest()` were plain
 * typed Retrofit calls, so the whole manifest went through `Serializer.FromString` —
 * `decodeFromString(body.string())` — and `ResponseBody.string()` allocates ONE contiguous
 * `ByteArray` the size of the entire body and copies it into ONE contiguous `String`. The manifest
 * is unbounded in BYTES (the server's caps are on the entry COUNT, and every entry may inline a
 * details.txt body, an answers.txt or a full transcript), so on a large repository the handset died
 * with `java.lang.OutOfMemoryError: Failed to allocate a N byte allocation`.
 *
 * WHY THESE ASSERTIONS AND NOT A DOWNLOAD TEST. The fix's whole value is a property of memory, and a
 * unit test cannot watch the allocator. What it CAN pin down is the rules the fix depends on, each of
 * which silently reinstates the defect or breaks the download if it is ever "tidied":
 *
 *  1. [ManifestStream.isNdjson] must reject `application/json`. It is not politeness towards old
 *     servers — `BufferedReader.readLine()` has no length limit, so handed a single-line JSON object
 *     it allocates the whole body as one String and the OOM is back, now in a code path nobody is
 *     looking at.
 *  2. Entries must be decoded LAZILY, one line at a time. A `toList()` inside the decoder would make
 *     the whole change pointless while every test about content still passed.
 *  3. One undecodable line must not fail the download, and must not be silently forgotten either.
 *  4. The headers must survive, INCLUDING `X-Dataset-Skipped`, which is this repository's own: its
 *     `/export/dataset` answers `skippedMedia` beside `truncated`, and once the wrapper object is
 *     gone the header is the only place that number can travel.
 *
 * The wire shape asserted below is `export.dataset_manifest`'s and `data_browser.data_manifest`'s;
 * both go through `manifest_ndjson_response`, and `backend/tests/test_manifest_stream.py` asserts
 * the server end of the same contract.
 */
class ManifestStreamTest {

    /**
     * The app's decoder settings. Constructed from `ApiClient.json` itself rather than restated,
     * because a copy here would let the two drift and the drift that matters is `ignoreUnknownKeys`
     * — see the last test in this file for what it costs.
     */
    private val json: Json = ApiClient.json

    private fun lines(body: String) =
        ManifestLines(StringReader(body), json, DatasetFileDto.serializer())

    // -----------------------------------------------------------------------
    // Rule 1: what may reach the line reader at all
    // -----------------------------------------------------------------------

    @Test
    fun `the streamed media type is recognised with and without a charset`() {
        assertTrue(ManifestStream.isNdjson("application/x-ndjson"))
        assertTrue(ManifestStream.isNdjson("application/x-ndjson; charset=utf-8"))
        assertTrue(ManifestStream.isNdjson("Application/X-NDJSON"))
    }

    @Test
    fun `the reader refuses a body that is not newline-delimited`() {
        // A server that predates `?stream=1` ignores the parameter and answers the JSON object. If
        // this ever returns true, readLine() swallows the whole body into one String and the
        // OutOfMemoryError this class exists to prevent comes straight back.
        assertFalse(ManifestStream.isNdjson("application/json"))
        assertFalse(ManifestStream.isNdjson("application/json; charset=utf-8"))
        assertFalse(ManifestStream.isNdjson("text/html"))
        assertFalse(ManifestStream.isNdjson(null))
    }

    // -----------------------------------------------------------------------
    // Rule 2: laziness — the property the whole fix IS
    // -----------------------------------------------------------------------

    @Test
    fun `a manifest is decoded one entry at a time`() {
        // A Reader that records how much it was asked for. If the decoder ever materialises the
        // manifest — a `toList()`, a `readText()`, a `.lines()` — it is asked for everything up
        // front and this fails with the demand it saw.
        var maxLive = 0
        val body = (1..500).joinToString("\n") { """{"path":"f$it.jpg","url":"https://s3/$it"}""" }
        val gate = object : Reader() {
            private val inner = StringReader(body)
            var served = 0
                private set

            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                val n = inner.read(cbuf, off, len)
                if (n > 0) served += n
                return n
            }

            override fun close() = inner.close()
        }

        var consumed = 0
        val reader = ManifestLines(gate, json, DatasetFileDto.serializer())
        for (entry in reader.entries()) {
            consumed++
            if (consumed == 1) maxLive = gate.served
            assertEquals("f$consumed.jpg", entry.path)
        }

        assertEquals(500, consumed)
        // After the FIRST entry the decoder must not have pulled the whole body. BufferedReader
        // fills in 8 KB blocks, so the honest assertion is "one buffer, not 500 entries".
        assertTrue(
            "the decoder read $maxLive of ${body.length} characters before yielding entry 1",
            maxLive < body.length
        )
    }

    // -----------------------------------------------------------------------
    // Rule 3: tolerance, and honesty about what it cost
    // -----------------------------------------------------------------------

    @Test
    fun `one unreadable line loses that file and not the download`() {
        val reader = lines(
            """
            {"path":"a.txt","content":"first"}
            {"path":"b.jpg",  <-- this line is damage
            {"path":"c.txt","content":"third"}
            """.trimIndent()
        )

        val paths = reader.entries().map { it.path }.toList()

        assertEquals(listOf("a.txt", "c.txt"), paths)
        // Counted, not swallowed: `manifestTotal` adds this back so the archive is reported as short
        // instead of presenting itself as complete.
        assertEquals(1, reader.unreadable)
    }

    @Test
    fun `blank lines are not damage`() {
        // The server writes chunks of 200 entries each terminated with a newline, so a trailing empty
        // line is ordinary output. Counting it would make every download report a phantom missing
        // file.
        val reader = lines("{\"path\":\"a.txt\",\"content\":\"x\"}\n\n")

        assertEquals(1, reader.entries().count())
        assertEquals(0, reader.unreadable)
    }

    @Test
    fun `an entry keeps its inline content and its url`() {
        val reader = lines(
            """
            {"path":"Workshops/W/details.txt","content":"Title: W\nPlace: Bagru"}
            {"path":"Workshops/W/photo.jpg","url":"https://bucket/photo.jpg"}
            """.trimIndent()
        )

        val entries = reader.entries().toList()

        // The escaped \n inside the JSON string is what keeps a multi-line details.txt on ONE line of
        // the stream. If the server ever emitted a real newline there, this entry would arrive as two
        // unparseable fragments and the archive would come out short with no error anywhere.
        assertEquals("Title: W\nPlace: Bagru", entries[0].content)
        assertEquals(null, entries[0].url)
        assertEquals("https://bucket/photo.jpg", entries[1].url)
        assertEquals(null, entries[1].content)
    }

    @Test
    fun `a folder entry keeps the fields only this repository's manifest has`() {
        // `/data/manifest` here carries convertToMp4 + originalPath, which the download loop branches
        // on: audio the SERVER re-encodes is fetched from the API, falling back to the stored object.
        // Decoded off a line rather than out of a whole object now, so it is asserted off a line.
        val reader = ManifestLines(
            StringReader(
                """{"path":"a/rec.mp4","url":"https://s3/rec.wav","originalPath":"a/rec.wav",""" +
                    """"mediaId":"m1","mediaType":"AUDIO","convertToMp4":true}"""
            ),
            json,
            DataManifestFileDto.serializer()
        )

        val entry = reader.entries().single()

        assertTrue(entry.convertToMp4)
        assertEquals("m1", entry.mediaId)
        assertEquals("a/rec.wav", entry.originalPath)
    }

    @Test
    fun `an unknown field a newer server adds does not break the reader`() {
        // `ignoreUnknownKeys` is ApiClient's setting and this path has to inherit it: a manifest entry
        // gaining a field must not turn every installed handset's download into 20,000 unreadable
        // lines. This is the assertion that fails if somebody gives the reader its own `Json`.
        val reader = lines("""{"path":"a.jpg","url":"https://s3/a","checksum":"deadbeef"}""")

        assertEquals("a.jpg", reader.entries().single().path)
        assertEquals(0, reader.unreadable)
    }

    // -----------------------------------------------------------------------
    // Rule 4: the headers, which carry everything NDJSON has no wrapper object for
    // -----------------------------------------------------------------------

    @Test
    fun `the counts and the truncation flag survive the headers`() {
        assertEquals(4312, ManifestStream.count("4312"))
        assertEquals(4312, ManifestStream.count(" 4312 "))
        assertTrue(ManifestStream.flag("true"))
        assertFalse(ManifestStream.flag("false"))
    }

    @Test
    fun `the skipped-media count has a header of its own`() {
        // THIS ONE IS NOT IN THE SIBLING PORTAL. `/export/dataset` here answers five keys, not four:
        // `skippedMedia` counts media rows that could not be addressed at all, which is a different
        // follow-up from a capped table ("these files are broken in storage" vs "ask for a full
        // extract"). Delete the header and the streamed path silently loses the distinction.
        assertEquals("X-Dataset-Skipped", ManifestStream.SKIPPED_HEADER)
        assertEquals(3, ManifestStream.count("3"))
    }

    @Test
    fun `a missing count reads as unknown rather than as zero`() {
        // -1, never 0. A total of 0 would render as "142 of 0 files" and, worse, would let the caller
        // report a complete archive as empty.
        assertEquals(-1, ManifestStream.count(null))
        assertEquals(-1, ManifestStream.count("not a number"))
        // An old server saying nothing is not a server claiming the archive is whole.
        assertFalse(ManifestStream.flag(null))
    }
}
