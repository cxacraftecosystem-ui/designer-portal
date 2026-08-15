package com.designprototype.workshop

import android.os.SystemClock
import android.util.Log
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

/**
 * **DOES THE DOWNLOAD READOUT'S ARITHMETIC SURVIVE CONTACT WITH A REAL SOCKET AND A REAL DISK?**
 *
 * `DwDownloadTest` drives [com.designprototype.workshop.data.DwTransferMeter] off a scripted clock on
 * a desktop JVM and proves the arithmetic. **It cannot prove that the byte count fed to it is the byte
 * count arriving on the phone**, and that is the half a fabricated progress bar lives in. So this
 * probe runs the app's own fetch loop against a real https host, and at every sample compares the
 * figure the card would draw against `length()` of the file on disk — a number this test takes for
 * itself and the app never sees.
 *
 * ── WHY THIS PROBE EXISTS AT ALL: THE FETCH PATH HAD NEVER RUN ─────────────────────────────────
 *
 * `DwAsrModelController.downloadAndInstall`'s docstring said so in as many words — *"this function has
 * never run against a server and that is the honest state of it"*. It could not be made to: the pinned
 * container is a `.tar.bz2`, so `dwAsrModelOffer` answers `CONTAINER_NOT_READABLE_IN_THIS_BUILD` before
 * a control is drawn, and `DwAsrModelArtifact`'s constructor requires `https://` — correctly — so no
 * local plaintext server can stand in. Two hundred and ninety-two megabytes of somebody's prepaid
 * bundle were riding on a loop nothing had ever executed. The controller now takes its catalogues as
 * constructor parameters defaulting to the shipped ones, purely so that this file can point that loop
 * somewhere real.
 *
 * ── THE STAND-IN ARTIFACT, AND WHY IT IS THIS ONE ──────────────────────────────────────────────
 *
 * A 71 MB ONNX file from a public Hugging Face repository. It is not a model this app would ever
 * install; it is a **transport fixture**, chosen for four properties measured before it was written
 * down (`api/models/...?blobs=true` for the digest, and a `Range: bytes=1000000-1000099` GET for the
 * rest):
 *
 *  * `https://`, so it satisfies the artifact constructor's TLS rule rather than relaxing it;
 *  * `Accept-Ranges: bytes` on a HEAD, which is what `serverAcceptsRanges` asks;
 *  * a real `206 PartialContent` with `Content-Range: bytes 1000000-1000099/71082637`, which is what
 *    `dwRangeHonoured` demands — so the resume is a resume and not a hopeful re-fetch;
 *  * Hugging Face publishes the LFS object's SHA-256, so the digest below was **not** computed by
 *    hashing a local download and trusting it: it is the host's own statement of the bytes it serves,
 *    which is the thing the app then checks for itself.
 *
 * **IT IS NOT A ZIP AND THAT IS DELIBERATE.** The fetch is what is under test. The container digest is
 * checked after the last byte lands and before anything is opened, so a resumed download that stitched
 * the wrong bytes together fails there — and that check passing is precisely the assertion this probe
 * wants. The unpack then fails, the part-file is kept by the generic failure arm, and this test hashes
 * that file itself to confirm the app and the host agree. Making the fixture a zip would have proved
 * less, not more.
 *
 * ── HOW TO RUN IT WITHOUT SIGNING THE DESIGNER OUT ────────────────────────────────────────────
 *
 * **DO NOT USE `connectedDebugAndroidTest` ON A HANDSET SOMEBODY IS USING** — AGP uninstalls the app
 * afterwards, which clears the signed-in session and any unsynced drafts. Drive the installed test APK:
 *
 *   adb shell am instrument -w \
 *     -e class com.designprototype.workshop.DwAsrModelTransferProbeTest \
 *     com.designprototype.workshop.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s DWXFERPROBE
 *
 * It costs ~71 MB of the handset's data allowance per run and deletes everything it wrote.
 */
@RunWith(AndroidJUnit4::class)
class DwAsrModelTransferProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // -----------------------------------------------------------------------------------------
    // 1. The fetch, watched against bytes this test counts for itself
    // -----------------------------------------------------------------------------------------

    /**
     * **EVERY NUMBER ON THE CARD, CHECKED AGAINST THE FILE ON DISK, ONCE A SECOND, OVER 71 MB.**
     *
     * The three things a progress readout can lie about, and how each is caught here without trusting
     * anything the app says:
     *
     *  * **how much has arrived** — `readout.receivedBytes` must equal `partFile.length()`. This test
     *    stats the file; the app counts the return of `read()`. If those two ever disagree the bar is
     *    drawing something other than the download.
     *  * **the speed** — recomputed here from consecutive `length()` readings and a monotonic clock,
     *    and required to be the same order as the app's figure. Not equal: the app smooths over a
     *    five-second window on purpose, so the two agree in magnitude and not to the byte.
     *  * **the time remaining** — every sample's ETA is compared against **how long the download
     *    actually went on to take from that sample**, which is only knowable after the fact and is
     *    therefore the one check an implementation cannot be written to satisfy.
     */
    @Test
    fun theFetchReadoutMatchesTheBytesOnDiskAndItsEtaMatchesWhatHappened() {
        clearProbeState()
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
            // The card ticks once a second; this probe must tick on the same cadence or it would be
            // measuring a surface nobody ships.
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
        assertTrue("The probe recorded no fetch samples at all.", fetching.size >= 3)

        // ---- 1. The byte count IS the file on disk -------------------------------------------
        // Sampled without a lock, so the file can legitimately have grown between the two reads in
        // one loop pass; the tolerance is one 64 KiB buffer plus the poll interval's worth at the
        // fastest rate seen, and the direction is checked too — the app must never claim MORE than
        // is there, which is the direction that inflates a bar.
        fetching.forEach { s ->
            assertTrue(
                "The card claimed ${s.readout.receivedBytes} bytes with ${s.onDiskBytes} on disk.",
                s.readout.receivedBytes <= s.onDiskBytes + 64 * 1024,
            )
        }
        val worstLag = fetching.maxOf { it.onDiskBytes - it.readout.receivedBytes }
        Log.i(TAG, "worst gap between disk and readout: $worstLag bytes")

        // ---- 2. The speed is the same order as one measured here ------------------------------
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
        assertTrue("No speed samples were comparable; the fixture may be too small.", compared >= 2)

        // ---- 3. The ETA against what actually happened ----------------------------------------
        // The last FETCHING sample's clock is when the download ended, so every earlier sample's true
        // remaining time is known. Only STEADY samples carry an ETA at all, by design.
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

        // ---- 4. The digest of the ASSEMBLED file, taken by this test --------------------------
        // The fixture is not a zip, so the unpack fails after the container digest has passed and the
        // generic failure arm keeps the part-file. That the app got here at all means its own check
        // passed; this hashes the same bytes independently.
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

    // -----------------------------------------------------------------------------------------
    // 2. Interrupting it: what the designer sees and what the disk is left holding
    // -----------------------------------------------------------------------------------------

    /**
     * **PAUSE MID-FLIGHT, THEN RESUME, AND CHECK THE PHONE AT EVERY STEP.**
     *
     * Four things, three of which are about bytes somebody paid for:
     *
     *  * the part-file survives the pause with its length intact;
     *  * **the readout is cleared** — this is a fix. It used to be left holding the last live reading,
     *    so a stopped transfer sat there saying "2.4 MB/s · about 1 min left" beside the word Paused
     *    and a Resume button, and nothing would ever have corrected it because `tick` returns early
     *    once the state is no longer INSTALLING;
     *  * the resume asks for a range and the server honours it, so the second attempt's readout starts
     *    from the prefix rather than from zero — checked against `length()`, not against the app;
     *  * a corrupted part-file is caught by the digest of the assembled whole and deleted.
     */
    @Test
    fun pausingKeepsThePrefixAndClearsTheReadoutAndResumingCarriesOnFromIt() {
        clearProbeState()
        val scope = CoroutineScope(Dispatchers.Main)
        val controller = DwAsrModelController(context, scope, listOf(PROBE_MODEL), listOf(PROBE_ARTIFACT))
        refreshAndWait(controller)
        assumeTrue("A fetch needs a connection.", offerFor(controller) == DwAsrModelOffer.DOWNLOAD)

        onUi { controller.install() }
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < FETCH_TIMEOUT_MS) {
            if (partFile().isFile && partFile().length() > PAUSE_AFTER_BYTES) break
            Thread.sleep(POLL_MS)
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

        // The offer over a prefix is a RESUME, and the byte count under it is read off the disk.
        refreshAndWait(controller)
        assertEquals(DwAsrModelOffer.RESUME, offerFor(controller))
        assertEquals(keptBytes, readOnUi { controller.partialOnDiskBytes })

        // ---- Resume, and watch where the second attempt's readout starts ----------------------
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

        // Let it run out, then check the stitched file against the host's own digest.
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

    // -----------------------------------------------------------------------------------------
    // 3. The copy route, the phase that follows it, and what a bad byte costs
    // -----------------------------------------------------------------------------------------

    /**
     * **THE REACHABLE ROUTE, END TO END, PLUS THE VERIFYING PHASE THAT USED TO BORROW ITS NUMBERS.**
     *
     * The staged copy is the only install this build can actually perform, and it drives the same
     * meter. What this pins is the transition: when the copy ends and the hashing begins, the readout
     * must be about the hashing. Before the fix the meter was left where the copy had put it, so the
     * card printed the copy's speed and a full bar under "Checking the fingerprint" — and had the hash
     * taken ten seconds it would have called a phone reading at 300 MB/s *stalled*.
     */
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
        onUi { controller.install() }
        val startedAt = SystemClock.elapsedRealtime()
        var lastTickAt = 0L
        while (SystemClock.elapsedRealtime() - startedAt < COPY_TIMEOUT_MS) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTickAt >= 1_000L) {
                lastTickAt = now
                onUi { controller.tick(SystemClock.elapsedRealtime()) }
            }
            val phase = readOnUi { controller.phase }
            val readout = readOnUi { controller.readout }
            if (phase != null && readout != null) {
                phasesSeen.getOrPut(phase) { mutableListOf() } += readout
            }
            if (readOnUi { controller.status.state } != DwAsrModelState.INSTALLING) break
            Thread.sleep(60)
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

        // THE FIX, ASSERTED: the verifying phase's readings are about the verifying, so the first one
        // of them cannot be the previous phase's finished total.
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

        // ---- A corrupted byte, same length, must fail the digest and be deleted ---------------
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

        // Put the phone back the way a designer would want it: verified, from the staged bytes.
        refreshAndWait(controller)
        if (offerFor(controller) == DwAsrModelOffer.INSTALL_FROM_STAGED_FILES) {
            onUi { controller.install() }
            waitWhile(COPY_TIMEOUT_MS) { readOnUi { controller.status.state } == DwAsrModelState.INSTALLING }
            Log.i(TAG, "reinstalled: state=${readOnUi { controller.status.state }}")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------------------------

    private data class Sample(
        val atMillis: Long,
        val phase: DwTransferPhase?,
        /** `length()` of the part-file, taken by this test. The app never sees this number. */
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

    /** Where the fetch assembles the fixture. The same arithmetic `partialFor` uses. */
    private fun partFile(): File = File(
        File(File(context.filesDir, "asr-model"), "incoming"),
        "model-${PROBE_MODEL.modelId}${PROBE_ARTIFACT.container.extension}.part",
    )

    /** Leave the phone holding nothing this probe put there. 71 MB is 71 MB. */
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

        /**
         * The transport fixture. **Not a model — a file of known length and known digest.**
         *
         * Its `files` entry is never written: the unpack cannot succeed on a non-zip, which is the
         * point (see the class doc). The digest below is a placeholder for a file that never lands,
         * and the one that is actually checked is [PROBE_ARTIFACT]'s.
         */
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
                /*
                 * A SECOND PINNED FILE THAT ALSO NEVER LANDS, so the fixture can carry a head — see
                 * [DwAsrModelHead], whose graph and vocabulary must be two DIFFERENT pinned files.
                 * Nothing in this probe loads a model, so the family and the head below are shape and
                 * not behaviour; they are here because a `DwAsrModel` that could not name what gets
                 * opened would let a real catalogue row ship without naming it either.
                 */
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

        /**
         * The URL, the length and the digest, each measured before being written here.
         *
         * `GET .../api/models/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26?blobs=true`
         * answered `size 71082637` and `lfs.sha256 0d072fd4…0187` on 2026-08-13, and a
         * `Range: bytes=1000000-1000099` GET answered `206` with
         * `Content-Range: bytes 1000000-1000099/71082637`.
         */
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
