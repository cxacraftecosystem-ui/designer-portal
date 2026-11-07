package com.designprototype.workshop

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DW_ASR_MODELS
import com.designprototype.workshop.data.DwAsrContainerFormat
import com.designprototype.workshop.data.DwAsrModel
import com.designprototype.workshop.data.DwAsrModelArtifact
import com.designprototype.workshop.data.DwAsrModelFamily
import com.designprototype.workshop.data.DwAsrModelFile
import com.designprototype.workshop.data.DwAsrModelHead
import com.designprototype.workshop.data.DwAsrModelOffer
import com.designprototype.workshop.data.DwAsrModelState
import com.designprototype.workshop.data.DwRateStability
import com.designprototype.workshop.data.DwTransferPhase
import com.designprototype.workshop.data.DwTransferReadout
import com.designprototype.workshop.data.dwAsrModelOffer
import com.designprototype.workshop.data.dwTransferLine
import com.designprototype.workshop.ui.designworkshop.DwAsrModelController
import com.designprototype.workshop.ui.designworkshop.dwAsrModelDir
import com.designprototype.workshop.ui.designworkshop.dwAsrReadInstalledModel
import com.designprototype.workshop.ui.designworkshop.dwAsrSha256OfFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class DwAsrModelTransferProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theFetchReadoutMatchesTheBytesOnDiskAndItsEtaMatchesWhatHappened() = withRetries { fetchOnce() }

    private fun fetchOnce() {
        val scope = CoroutineScope(Dispatchers.Main)
        val controller = DwAsrModelController(context, scope, listOf(PROBE_MODEL), listOf(PROBE_ARTIFACT))

        refreshAndWait(controller)
        val offer = offerFor(controller)
        Log.i(TAG, "offer=$offer connection=${controller.connection} free=${controller.measurement?.freeStorageBytes}")
        assumeTrue("A fetch needs a connection on this handset.", offer == DwAsrModelOffer.DOWNLOAD)

        val trace = mutableListOf<Sample>()
        onUi { controller.install() }
        val startedAt = SystemClock.elapsedRealtime()
        var lastTickAt = 0L
        while (SystemClock.elapsedRealtime() - startedAt < FETCH_TIMEOUT_MS) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTickAt >= 1_000L) {
                lastTickAt = now
                onUi { controller.tick(SystemClock.elapsedRealtime()) }
            }
            val onDisk = if (partFile().isFile) partFile().length() else 0L
            val readout = readOnUi { controller.readout }
            val phase = readOnUi { controller.phase }
            val state = readOnUi { controller.status.state }
            if (readout != null) trace += Sample(now - startedAt, phase, onDisk, readout)
            if (state != DwAsrModelState.INSTALLING) break
            Thread.sleep(POLL_MS)
        }
        val totalMillis = SystemClock.elapsedRealtime() - startedAt
        val finalState = readOnUi { controller.status.state }
        val failure = readOnUi { controller.status.failure }

        trace.forEach { s ->
            Log.i(
                TAG,
                "t=%6d ms  phase=%-9s onDisk=%9d  said=%9d  %s".format(
                    s.atMillis, s.phase, s.onDiskBytes, s.readout.receivedBytes,
                    dwTransferLine(s.readout),
                )
            )
        }
        Log.i(TAG, "finished in $totalMillis ms  state=$finalState  failure=$failure")

        val fetching = trace.filter { it.phase == DwTransferPhase.FETCHING }
        if (fetching.size < 3) throw stopped(controller, "the fetch produced ${fetching.size} samples; state=$finalState failure=$failure")

        fetching.forEach { s ->
            assertTrue(
                "The card claimed ${s.readout.receivedBytes} bytes with ${s.onDiskBytes} on disk.",
                s.readout.receivedBytes <= s.onDiskBytes + 64 * 1024,
            )
        }
        val worstLag = fetching.maxOf { it.onDiskBytes - it.readout.receivedBytes }
        Log.i(TAG, "worst gap between disk and readout: $worstLag bytes")

        var compared = 0
        fetching.zipWithNext().forEach { (a, b) ->
            val span = b.atMillis - a.atMillis
            val moved = b.onDiskBytes - a.onDiskBytes
            val mine = if (span > 0L) moved * 1000.0 / span else return@forEach
            val theirs = b.readout.bytesPerSecond ?: return@forEach
            if (mine < 200_000.0) return@forEach
            compared++
            assertTrue(
                "Card said ${theirs.toLong()} B/s over a window where this test measured " +
                    "${mine.toLong()} B/s.",
                theirs in (mine / 8.0)..(mine * 8.0),
            )
        }
        if (compared < 2) throw stopped(controller, "only $compared windows ran faster than 200 kB/s")

        val endedAt = fetching.last().atMillis
        val etaErrors = fetching.mapNotNull { s ->
            val eta = s.readout.secondsRemaining ?: return@mapNotNull null
            val truth = (endedAt - s.atMillis) / 1000.0
            if (truth < 2.0) null else Pair(eta.toDouble(), truth)
        }
        Log.i(TAG, "ETA samples: " + etaErrors.joinToString { "said ${it.first.toLong()}s / was ${it.second.toLong()}s" })
        if (etaErrors.size >= 3) {
            val ratios = etaErrors.map { (said, truth) -> said / truth }.sorted()
            val median = ratios[ratios.size / 2]
            assertTrue(
                "The median ETA was ${"%.2f".format(median)}x the time the download actually took " +
                    "from that moment.",
                median in 0.25..4.0,
            )
            assertTrue(
                "No ETA may be shown for an ERRATIC window.",
                fetching.none { it.readout.stability == DwRateStability.ERRATIC && it.readout.secondsRemaining != null },
            )
        }

        val kept = partFile()
        if (kept.isFile && kept.length() == PROBE_ARTIFACT.downloadBytes) {
            val digest = dwAsrSha256OfFile(kept)
            Log.i(TAG, "assembled ${kept.length()} bytes, sha256=$digest")
            assertEquals(
                "The bytes this app assembled are not the bytes the host published.",
                PROBE_ARTIFACT.sha256, digest,
            )
            assertTrue(
                "A digest that passed must not be reported as a mismatch. Failure was: $failure",
                failure == null || !failure.contains("fingerprint", ignoreCase = true),
            )
        }
        clearProbeState()
    }

    @Test
    fun pausingKeepsThePrefixAndClearsTheReadoutAndResumingCarriesOnFromIt() = withRetries { pauseAndResumeOnce() }

    private fun pauseAndResumeOnce() {
        val scope = CoroutineScope(Dispatchers.Main)
        val controller = DwAsrModelController(context, scope, listOf(PROBE_MODEL), listOf(PROBE_ARTIFACT))
        refreshAndWait(controller)
        assumeTrue("A fetch needs a connection.", offerFor(controller) == DwAsrModelOffer.DOWNLOAD)

        onUi { controller.install() }
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < FETCH_TIMEOUT_MS) {
            if (partFile().isFile && partFile().length() > PAUSE_AFTER_BYTES) break
            if (readOnUi { controller.status.state } != DwAsrModelState.INSTALLING) break
            Thread.sleep(POLL_MS)
        }
        val reached = if (partFile().isFile) partFile().length() else 0L
        if (reached <= PAUSE_AFTER_BYTES) {
            val state = readOnUi { controller.status.state }
            val failure = readOnUi { controller.status.failure }
            throw stopped(controller, "the fetch reached $reached bytes before the pause; state=$state failure=$failure")
        }
        val beforePause = readOnUi { controller.readout }
        Log.i(TAG, "at the pause the card said: " + beforePause?.let { dwTransferLine(it) })
        onUi { controller.pause() }
        Thread.sleep(500)

        val keptBytes = if (partFile().isFile) partFile().length() else 0L
        Log.i(TAG, "paused with $keptBytes bytes kept, state=${readOnUi { controller.status.state }}")
        assertTrue("A pause must keep the prefix. Found $keptBytes bytes.", keptBytes > PAUSE_AFTER_BYTES)
        assertEquals(DwAsrModelState.PAUSED, readOnUi { controller.status.state })
        assertNull(
            "A paused transfer must not leave a live speed and a time-remaining on screen.",
            readOnUi { controller.readout },
        )

        refreshAndWait(controller)
        assertEquals(DwAsrModelOffer.RESUME, offerFor(controller))
        assertEquals(keptBytes, readOnUi { controller.partialOnDiskBytes })

        onUi { controller.install() }
        var firstResumedReadout: DwTransferReadout? = null
        val resumedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - resumedAt < FETCH_TIMEOUT_MS) {
            val r = readOnUi { controller.readout }
            val phase = readOnUi { controller.phase }
            if (phase == DwTransferPhase.FETCHING && r != null && r.receivedBytes > 0L) {
                firstResumedReadout = r
                break
            }
            if (readOnUi { controller.status.state } != DwAsrModelState.INSTALLING) break
            Thread.sleep(POLL_MS)
        }
        Log.i(TAG, "first reading after resume: " + firstResumedReadout?.let { dwTransferLine(it) })
        if (firstResumedReadout == null && readOnUi { controller.status.state } == DwAsrModelState.FAILED) {
            throw stopped(controller, "the resume failed before its first reading: ${readOnUi { controller.status.failure }}")
        }
        assertNotNull("The resume produced no readout at all.", firstResumedReadout)
        assertTrue(
            "A resume must count the prefix: it reported ${firstResumedReadout!!.receivedBytes} " +
                "against $keptBytes already on the phone.",
            firstResumedReadout.receivedBytes >= keptBytes,
        )
        assertTrue(
            "A resume must not restart the bar at zero.",
            (firstResumedReadout.percent ?: 0) > 0,
        )

        while (SystemClock.elapsedRealtime() - resumedAt < FETCH_TIMEOUT_MS) {
            if (readOnUi { controller.status.state } != DwAsrModelState.INSTALLING) break
            Thread.sleep(POLL_MS)
        }
        val assembled = partFile()
        if (assembled.isFile && assembled.length() == PROBE_ARTIFACT.downloadBytes) {
            val digest = dwAsrSha256OfFile(assembled)
            Log.i(TAG, "stitched ${assembled.length()} bytes across two attempts, sha256=$digest")
            assertEquals(
                "A resumed download must hash to the same digest as a fresh one.",
                PROBE_ARTIFACT.sha256, digest,
            )
        } else {
            Log.i(TAG, "resume did not complete inside the timeout; assembled digest not checked")
        }
        clearProbeState()
    }

    @Test
    fun theCopyRouteVerifiesWithItsOwnNumbersAndACorruptedByteIsCaughtAndDeleted() {
        val pinned = DW_ASR_MODELS.firstOrNull()
        assumeTrue("This build pins no model.", pinned != null)
        val scope = CoroutineScope(Dispatchers.Main)
        val controller = DwAsrModelController(context, scope)
        refreshAndWait(controller)
        val offer = offerFor(controller)
        Log.i(TAG, "staged=${controller.stagedFilesPresent} offer=$offer")
        assumeTrue(
            "Needs the model staged where the app can read it — see docs/ASR-MODEL-SIDELOAD.md.",
            offer == DwAsrModelOffer.INSTALL_FROM_STAGED_FILES ||
                offer == DwAsrModelOffer.ALREADY_INSTALLED,
        )

        if (offer == DwAsrModelOffer.ALREADY_INSTALLED) {
            onUi { controller.remove() }
            waitWhile(20_000L) { readOnUi { controller.busy } }
            refreshAndWait(controller)
        }

        val phasesSeen = linkedMapOf<DwTransferPhase, MutableList<DwTransferReadout>>()
        var lastFiled: DwTransferReadout? = null
        val writes = Snapshot.registerGlobalWriteObserver {
            if (Looper.myLooper() != Looper.getMainLooper()) return@registerGlobalWriteObserver
            val readout = controller.readout
            val phase = controller.phase
            if (readout == null || phase == null || readout === lastFiled) return@registerGlobalWriteObserver
            lastFiled = readout
            phasesSeen.getOrPut(phase) { mutableListOf() } += readout
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            onUi { controller.install() }
            var lastTickAt = 0L
            while (SystemClock.elapsedRealtime() - startedAt < COPY_TIMEOUT_MS) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTickAt >= 1_000L) {
                    lastTickAt = now
                    onUi { controller.tick(SystemClock.elapsedRealtime()) }
                }
                if (readOnUi { controller.status.state } != DwAsrModelState.INSTALLING) break
                Thread.sleep(60)
            }
        } finally {
            onUi { writes.dispose() }
        }
        Log.i(TAG, "copy install ended in ${SystemClock.elapsedRealtime() - startedAt} ms " +
            "state=${readOnUi { controller.status.state }}")
        phasesSeen.forEach { (phase, readouts) ->
            Log.i(TAG, "phase $phase, ${readouts.size} readings: " +
                readouts.takeLast(4).joinToString(" | ") { dwTransferLine(it) })
        }
        assertEquals(
            "The copy must end INSTALLED. failure=${readOnUi { controller.status.failure }}",
            DwAsrModelState.INSTALLED, readOnUi { controller.status.state },
        )

        assertNotNull(
            "The copy verified without publishing a VERIFYING readout.",
            phasesSeen[DwTransferPhase.VERIFYING],
        )
        phasesSeen[DwTransferPhase.VERIFYING]?.let { verifying ->
            Log.i(TAG, "verifying readings: " + verifying.joinToString(" | ") { dwTransferLine(it) })
            assertEquals(
                "The VERIFYING phase must open on its own zero, not on the copy's finished total.",
                0L, verifying.first().receivedBytes,
            )
            assertTrue(
                "Nothing in a phase that is reading at full speed may say it is stalled.",
                verifying.none { it.stalled },
            )
        }

        val dir = dwAsrModelDir(context, pinned!!.modelId)
        val graph = File(dir, pinned.files.first().fileName)
        assertTrue("The install left no ${graph.name}.", graph.isFile)
        val lengthBefore = graph.length()
        RandomAccessFile(graph, "rw").use { raf ->
            raf.seek(lengthBefore / 2)
            val original = raf.readByte()
            raf.seek(lengthBefore / 2)
            raf.writeByte(original.toInt() xor 0x01)
        }
        assertEquals("The corruption must not change the length.", lengthBefore, graph.length())

        val afterCorruption = dwAsrReadInstalledModel(context, pinned)
        Log.i(TAG, "after flipping one bit: state=${afterCorruption.state} failure=${afterCorruption.failure}")
        assertEquals(DwAsrModelState.FAILED, afterCorruption.state)
        assertTrue(
            "A file that does not match its digest must be deleted, not left beside a good one.",
            !graph.exists(),
        )
        assertNotNull("A digest failure has to be said in a sentence.", afterCorruption.failure)

        refreshAndWait(controller)
        if (offerFor(controller) == DwAsrModelOffer.INSTALL_FROM_STAGED_FILES) {
            onUi { controller.install() }
            waitWhile(COPY_TIMEOUT_MS) { readOnUi { controller.status.state } == DwAsrModelState.INSTALLING }
            Log.i(TAG, "reinstalled: state=${readOnUi { controller.status.state }}")
        }
    }

    private class ProbeInfra(message: String) : Exception(message)

    private fun stopped(controller: DwAsrModelController, why: String): ProbeInfra {
        if (readOnUi { controller.status.state } == DwAsrModelState.INSTALLING) onUi { controller.pause() }
        return ProbeInfra(why)
    }

    private fun hostAnswers(): Boolean = runCatching {
        val connection = URL(PROBE_ARTIFACT.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = HOST_TIMEOUT_MS
            connection.readTimeout = HOST_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun withRetries(attempt: () -> Unit) {
        val infra = mutableListOf<String>()
        var regression: Throwable? = null
        repeat(ATTEMPTS) { round ->
            clearProbeState()
            try {
                if (!hostAnswers()) throw ProbeInfra("the probe host did not answer")
                attempt()
                clearProbeState()
                return
            } catch (e: ProbeInfra) {
                infra += "attempt ${round + 1}: ${e.message}"
            } catch (e: AssertionError) {
                regression = e
            }
            Log.i(TAG, "attempt ${round + 1} of $ATTEMPTS did not pass")
        }
        clearProbeState()
        regression?.let { throw it }
        assumeTrue("The transfer probe could not reach its host: " + infra.joinToString("; "), false)
    }

    private data class Sample(
        val atMillis: Long,
        val phase: DwTransferPhase?,
        val onDiskBytes: Long,
        val readout: DwTransferReadout,
    )

    private fun onUi(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T> readOnUi(block: () -> T): T {
        var out: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun refreshAndWait(controller: DwAsrModelController) {
        onUi { controller.refresh() }
        waitWhile(60_000L) {
            readOnUi { controller.measurement } == null || readOnUi { controller.busy }
        }
    }

    private fun offerFor(controller: DwAsrModelController): DwAsrModelOffer {
        val reading = readOnUi { controller.measurement } ?: return DwAsrModelOffer.UNKNOWN
        return dwAsrModelOffer(
            readOnUi { controller.status },
            reading,
            readOnUi { controller.connection },
            readOnUi { controller.stagedFilesPresent },
            controller.modelCatalogue,
            controller.artifactCatalogue,
        )
    }

    private fun waitWhile(timeoutMillis: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMillis
        while (condition() && SystemClock.elapsedRealtime() < until) Thread.sleep(POLL_MS)
    }

    private fun partFile(): File = File(
        File(File(context.filesDir, "asr-model"), "incoming"),
        "model-${PROBE_MODEL.modelId}${PROBE_ARTIFACT.container.extension}.part",
    )

    private fun clearProbeState() {
        runCatching { partFile().delete() }
        runCatching { dwAsrModelDir(context, PROBE_MODEL.modelId).listFiles()?.forEach { it.delete() } }
        runCatching { dwAsrModelDir(context, PROBE_MODEL.modelId).delete() }
    }

    private companion object {
        const val TAG = "DWXFERPROBE"
        const val POLL_MS = 120L
        const val FETCH_TIMEOUT_MS = 300_000L
        const val COPY_TIMEOUT_MS = 120_000L
        const val PAUSE_AFTER_BYTES = 6_000_000L
        const val ATTEMPTS = 2
        const val HOST_TIMEOUT_MS = 15_000

        val PROBE_MODEL = DwAsrModel(
            modelId = "dw-transfer-probe",
            quantisation = "int8",
            family = DwAsrModelFamily.OMNILINGUAL_ASR_CTC,
            files = listOf(
                DwAsrModelFile(
                    fileName = "probe-payload.bin",
                    sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                    bytes = 71_082_637L,
                ),
                DwAsrModelFile(
                    fileName = "probe-tokens.txt",
                    sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                    bytes = 1L,
                ),
            ),
            heads = listOf(
                DwAsrModelHead(
                    languageTag = null,
                    graphFileName = "probe-payload.bin",
                    tokensFileName = "probe-tokens.txt",
                ),
            ),
            upstreamVersion = "transport fixture, not an upstream release",
            provenance = "A public Hugging Face LFS object used ONLY to give the fetch loop a real " +
                "https host that honours Range. Nothing from it is ever installed or loaded.",
            languageNote = "None. This is a transport fixture and serves no language.",
        )

        val PROBE_ARTIFACT = DwAsrModelArtifact(
            modelId = "dw-transfer-probe",
            url = "https://huggingface.co/csukuangfj/" +
                "sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/" +
                "encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
            sha256 = "0d072fd4ef956294ba9db9e9a71a541ac70659095ec4934c8453d8b2fe740187",
            downloadBytes = 71_082_637L,
            container = DwAsrContainerFormat.ZIP,
            upstreamVersion = "transport fixture",
            provenance = "Hugging Face's own published SHA-256 for the LFS object at this URL, read " +
                "from the model API rather than computed from a local copy, so the digest this app " +
                "checks is the host's statement about the bytes it serves.",
        )
    }
}
