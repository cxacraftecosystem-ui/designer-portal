package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DwDownloadLiveProbeTest {

    @Test
    fun theLiveReadoutAgreesWithTheFileOnDiskAndItsEtaWithWhatHappened() {
        assumeTrue("Set DW_LIVE_DOWNLOAD_PROBE=1 to spend 71 MB on this.", enabled())
        val target = File.createTempFile("dw-live-probe", ".bin").also { it.delete() }
        try {
            val trace = fetch(target, from = 0L, stopAfterBytes = null)
            trace.rows.forEach { println(it.render()) }
            println("finished ${target.length()} bytes in ${trace.millis} ms")

            assertTrue("The probe collected too few samples to say anything.", trace.rows.size >= 4)
            assertEquals(
                "The transfer did not deliver the length the host publishes.",
                PROBE_BYTES, target.length(),
            )

            trace.rows.forEach { row ->
                assertTrue(
                    "The readout claimed ${row.readout.receivedBytes} with ${row.onDisk} on disk.",
                    row.readout.receivedBytes <= row.onDisk,
                )
                assertTrue(
                    "The readout fell more than one buffer behind the file: " +
                        "${row.readout.receivedBytes} against ${row.onDisk}.",
                    row.onDisk - row.readout.receivedBytes <= 64L * 1024L,
                )
            }

            var compared = 0
            trace.rows.zipWithNext().forEach { (a, b) ->
                val span = b.atMillis - a.atMillis
                val moved = b.onDisk - a.onDisk
                if (span <= 0L || moved <= 0L) return@forEach
                val mine = moved * 1000.0 / span
                val theirs = b.readout.bytesPerSecond ?: return@forEach
                compared++
                assertTrue(
                    "Card ${theirs.toLong()} B/s where this test measured ${mine.toLong()} B/s.",
                    theirs in (mine / 8.0)..(mine * 8.0),
                )
            }
            assertTrue("No comparable speed samples.", compared >= 3)

            val endedAt = trace.rows.last().atMillis
            val etas = trace.rows.mapNotNull { row ->
                val said = row.readout.secondsRemaining ?: return@mapNotNull null
                val truth = (endedAt - row.atMillis) / 1000.0
                if (truth < 2.0) null else said.toDouble() to truth
            }
            println("ETA samples: " + etas.joinToString { "said ${it.first.toLong()}s, was ${it.second.toLong()}s" })
            assumeTrue("The transfer was too short to project across.", etas.size >= 3)
            val ratios = etas.map { (said, truth) -> said / truth }.sorted()
            val median = ratios[ratios.size / 2]
            println("median ETA / truth = %.2f".format(median))
            assertTrue(
                "The median projection was ${"%.2f".format(median)}x what the transfer actually had " +
                    "left to run.",
                median in 0.25..4.0,
            )

            trace.rows.forEach { row ->
                if (row.readout.stability == DwRateStability.ERRATIC) {
                    assertNull(
                        "An ERRATIC window must print no time remaining: ${dwTransferLine(row.readout)}",
                        row.readout.secondsRemaining,
                    )
                }
            }

            assertEquals(
                "The bytes on disk are not the bytes the host publishes.",
                PROBE_SHA256, sha256(target),
            )
        } finally {
            target.delete()
        }
    }

    @Test
    fun anInterruptedTransferResumesFromItsPrefixAndTheAssembledFileHashesCorrectly() {
        assumeTrue("Set DW_LIVE_DOWNLOAD_PROBE=1 to spend 71 MB on this.", enabled())
        val target = File.createTempFile("dw-live-resume", ".bin").also { it.delete() }
        try {
            val first = fetch(target, from = 0L, stopAfterBytes = 9_000_000L)
            val prefix = target.length()
            println("stopped with $prefix bytes on disk after ${first.millis} ms")
            assertTrue("The abort left nothing to resume from.", prefix in 9_000_000L..12_000_000L)

            val acceptsRanges = headAcceptsRanges()
            println("HEAD says Accept-Ranges: bytes = $acceptsRanges")
            assertTrue("This fixture was chosen because the host honours ranges.", acceptsRanges)
            assertEquals(
                DwResumeDecision.RESUME_FROM_PARTIAL,
                dwResumePlan(prefix, PROBE_BYTES, acceptsRanges),
            )

            val second = fetch(target, from = prefix, stopAfterBytes = null)
            second.rows.take(3).forEach { println("resumed: " + it.render()) }
            println("assembled ${target.length()} bytes; second attempt took ${second.millis} ms")

            assertTrue(
                "The server did not honour the range, so this was not a resume at all.",
                second.rangeHonoured,
            )
            assertEquals(
                "A resume must land on exactly the published length.",
                PROBE_BYTES, target.length(),
            )
            assertEquals(
                "The stitched file does not hash to the digest the host publishes for it.",
                PROBE_SHA256, sha256(target),
            )
            assertEquals(
                DwAsrVerification.VERIFIED,
                dwAsrVerify(PROBE_SHA256, sha256(target)),
            )
            second.rows.firstOrNull()?.let { row ->
                println("first reading of the resumed attempt: " + dwTransferLine(row.readout))
                assertTrue(
                    "A resume must not restart the bar at zero: ${dwTransferLine(row.readout)}",
                    (row.readout.percent ?: 0) >= 12,
                )
            }

            val good = sha256(target)
            java.io.RandomAccessFile(target, "rw").use { raf ->
                val at = target.length() / 2
                raf.seek(at)
                val original = raf.readByte()
                raf.seek(at)
                raf.writeByte(original.toInt() xor 0x01)
            }
            assertEquals("The corruption must not change the length.", PROBE_BYTES, target.length())
            val corrupted = sha256(target)
            assertTrue("Flipping a bit must change the digest.", corrupted != good)
            assertEquals(
                "A file of the right length and the wrong content must be refused.",
                DwAsrVerification.MISMATCH,
                dwAsrVerify(PROBE_SHA256, corrupted),
            )
        } finally {
            target.delete()
        }
    }

    private data class Row(
        val atMillis: Long,
        val onDisk: Long,
        val readout: DwTransferReadout,
    ) {
        fun render(): String =
            "t=%6d ms  onDisk=%9d  said=%9d  %s".format(
                atMillis, onDisk, readout.receivedBytes, dwTransferLine(readout),
            )
    }

    private class Trace(val rows: List<Row>, val millis: Long, val rangeHonoured: Boolean)

    private fun fetch(target: File, from: Long, stopAfterBytes: Long?): Trace {
        val connection = (URL(PROBE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            if (from > 0L) setRequestProperty("Range", "bytes=$from-")
        }
        val code = connection.responseCode
        val honoured = from == 0L || dwRangeHonoured(
            code,
            dwParseContentRangeStart(connection.getHeaderField("Content-Range")),
            from,
        )
        if (from > 0L) {
            println("resume asked for bytes=$from-, got $code " +
                "Content-Range=${connection.getHeaderField("Content-Range")} honoured=$honoured")
        }
        val meter = DwTransferMeter(totalBytes = PROBE_BYTES, resumedFromBytes = if (honoured) from else 0L)
        val rows = mutableListOf<Row>()
        val startedAt = System.nanoTime()
        fun nowMillis() = (System.nanoTime() - startedAt) / 1_000_000L

        var moved = 0L
        var lastSampleAt = -1_000L
        connection.inputStream.use { input ->
            FileOutputStream(target, honoured && from > 0L).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    moved += read
                    val at = nowMillis()
                    if (at - lastSampleAt >= 250L) {
                        lastSampleAt = at
                        val readout = meter.observe(moved, at)
                        output.flush()
                        rows += Row(at, target.length(), readout)
                    }
                    if (stopAfterBytes != null && moved >= stopAfterBytes) break
                }
                output.flush()
            }
        }
        connection.disconnect()
        return Trace(rows, nowMillis(), honoured)
    }

    private fun headAcceptsRanges(): Boolean = runCatching {
        val connection = (URL(PROBE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 30_000
        }
        val ok = connection.responseCode in 200..299 &&
            connection.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
        connection.disconnect()
        ok
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun enabled(): Boolean = System.getenv("DW_LIVE_DOWNLOAD_PROBE") == "1"

    private companion object {
        const val PROBE_URL = "https://huggingface.co/csukuangfj/" +
            "sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/" +
            "encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx"
        const val PROBE_BYTES = 71_082_637L
        const val PROBE_SHA256 = "0d072fd4ef956294ba9db9e9a71a541ac70659095ec4934c8453d8b2fe740187"
    }
}
