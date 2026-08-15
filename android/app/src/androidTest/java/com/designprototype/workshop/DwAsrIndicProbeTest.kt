package com.designprototype.workshop

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DwAsrVerification
import com.designprototype.workshop.data.dwAsrEngineAbiFor
import com.designprototype.workshop.data.dwAsrVerify
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **CAN AN INDICCONFORMER GRAPH LOAD AND DECODE ON THIS HANDSET, AND WHAT DOES IT COST?**
 *
 * The sibling of [DwAsrEngineProbeTest] pointed at the OTHER model family. Same shape, same reading:
 * **a measurement, not a regression guard.** It prints to logcat under one tag and asserts only the
 * digest gate, because every question it answers is one only this phone can answer.
 *
 * ── THE CELL IT EXISTS TO FILL, AND WHY ARITHMETIC WAS NOT ENOUGH ─────────────────────────────
 *
 * `docs/ASR-RUNTIME-MEASUREMENT.md` records that the official `ai4bharat/indic-conformer-600m-
 * multilingual` cannot load here, and it records it as **arithmetic**: 2,428,824,576 bytes of fp32
 * weights against a `MemAvailable` read off `/proc/meminfo` on this handset. That is a sound argument
 * and it is not a measurement of a load. Two things are worth having a real number for:
 *
 *  1. what an IndicConformer of a size that *might* fit actually costs in resident set here, and
 *  2. whether `OfflineNemoEncDecCtcModelConfig` — the branch [DwAsrSpeechModel] now takes for
 *     [com.designprototype.workshop.data.DwAsrModelFamily.NEMO_ENC_DEC_CTC] — opens one of these
 *     graphs on the sherpa-onnx **inside this APK**, rather than on a desktop wheel of the same
 *     version number.
 *
 * ── WHAT IT LOADS, AND THE ONE THING IT DOES DIFFERENTLY FROM THE SHIPPED PATH ────────────────
 *
 * It reads the graph **in place, out of the staging directory**, where [DwAsrEngineProbeTest] copies
 * into `filesDir` first. That is a deliberate departure and it is stated rather than hidden: this probe
 * is aimed at artifacts that are **not in the catalogue** and may be 2.4 GB, and duplicating those
 * bytes to measure whether they can be opened would run the volume out for a reason unrelated to the
 * question. Nothing here is installed and nothing survives the run. What it does NOT skip is the
 * digest gate: both files are hashed on the phone, in this run, and if an expected digest was supplied
 * the verdict comes from the app's own `dwAsrVerify`. See the comment at the hash for why the digest is
 * computed here rather than by the app's `dwAsrSha256OfFile`.
 *
 * ── HOW TO RUN IT. THE FILES ARE NOT IN GIT AND CANNOT BE ────────────────────────────────────
 *
 *     adb push model.onnx tokens.txt /data/local/tmp/dwic/<lang>/
 *     adb push clip.wav              /data/local/tmp/dwic/<lang>/wav/
 *     adb shell chmod -R 755 /data/local/tmp/dwic
 *     adb shell am instrument -w \
 *       -e class com.designprototype.workshop.DwAsrIndicProbeTest \
 *       -e dwicDir /data/local/tmp/dwic/<lang> \
 *       -e dwicGraphSha256 <64 hex> -e dwicTokensSha256 <64 hex> \
 *       com.designprototype.workshop.test/androidx.test.runner.AndroidJUnitRunner
 *     adb logcat -d -s DWICPROBE:I
 *
 * `am instrument` rather than `connectedDebugAndroidTest`, for [DwAsrModelTransferProbeTest]'s reason:
 * **AGP uninstalls the app afterwards**, which clears the signed-in session and any unsynced drafts on
 * a handset somebody is using.
 *
 * **AN ABSENT DIRECTORY IS A SKIP AND NOT A FAILURE.** Nothing in this repository ships these bytes,
 * so on any handset without them this test says so and returns. A failure would turn a measurement
 * nobody staged into a red suite.
 */
@RunWith(AndroidJUnit4::class)
class DwAsrIndicProbeTest {

    private val tag = "DWICPROBE"

    private fun say(line: String) {
        // Chunked, because logcat truncates a long line and a truncated transcript is the one thing
        // this test produces that nobody can reconstruct.
        line.chunked(900).forEach { Log.i(tag, it) }
    }

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.trim()?.ifEmpty { null }

    @Test
    fun decodesIndicConformerOnThisHandset() {
        val dir = File(arg("dwicDir") ?: "/data/local/tmp/dwic/ml")
        val graph = File(dir, arg("dwicGraph") ?: "model.onnx")
        val tokens = File(dir, arg("dwicTokens") ?: "tokens.txt")

        say("=== DW INDICCONFORMER PROBE ===")
        say("device       : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) API ${Build.VERSION.SDK_INT}")
        say("abis         : ${Build.SUPPORTED_ABIS.joinToString()}")
        say("engine ABI   : ${dwAsrEngineAbiFor(Build.SUPPORTED_ABIS.toList())}")
        say("staging dir  : ${dir.absolutePath}")
        say("meminfo      : ${meminfoLine()}")

        if (!graph.isFile || !tokens.isFile) {
            say("NOT STAGED: ${graph.absolutePath} / ${tokens.absolutePath}. Nothing was measured. " +
                "Push the artifact as this file's KDoc says and run again.")
            return
        }
        say("graph        : ${graph.name} ${graph.length()} bytes")
        say("tokens       : ${tokens.name} ${tokens.length()} bytes")

        // ── THE GATE. Hashed on the phone, in this run. ────────────────────────────────────────
        //
        // **`MessageDigest` HERE AND NOT THE APP'S OWN `dwAsrSha256OfFile`, AND THE REASON IS WORTH
        // WRITING DOWN BECAUSE IT COST AN HOUR.** The app's function is the right one to use and
        // [DwAsrEngineProbeTest] uses it. But an instrumentation APK is compiled against the CURRENT
        // source and then loads its target's classes out of the INSTALLED `base.apk`, and those two
        // drift the moment a signature changes: `dwAsrSha256OfFile` grew a default parameter, so this
        // probe died with `NoSuchMethodError: dwAsrSha256OfFile$default` against a handset carrying an
        // app built an hour earlier. Reinstalling 169 MB over an adb-tls link that drops every few
        // seconds is not a thing to require of a MEASUREMENT probe. The 32-byte digest of a file has
        // exactly one correct answer, so computing it here couples this probe to nothing — while the
        // VERDICT still comes from the app's own [dwAsrVerify], which is where the fail-closed rules
        // (a blank expectation never passes, a malformed digest never passes) actually live.
        val graphDigest = sha256OfFile(graph)
        val tokensDigest = sha256OfFile(tokens)
        say("graph sha256 : $graphDigest")
        say("tokens sha256: $tokensDigest")
        listOf(
            Triple("graph", arg("dwicGraphSha256"), graphDigest),
            Triple("tokens", arg("dwicTokensSha256"), tokensDigest),
        ).forEach { (what, expected, actual) ->
            if (expected == null) {
                say("$what: no expected digest supplied, so nothing was VERIFIED — the digest above " +
                    "is a reading and not a check. Pass -e dwic${what}Sha256 to make it one.")
                return@forEach
            }
            val verdict = if (actual == null) null else dwAsrVerify(expected, actual)
            say("$what verdict: $verdict")
            if (verdict != DwAsrVerification.VERIFIED) {
                throw AssertionError(
                    "Refusing to open the $what: it hashes to $actual and $expected was expected. " +
                        "The file on this phone is not the file that was measured."
                )
            }
        }

        // ── LOAD. `nemo`, which is the branch DwAsrSpeechModel takes for this family. ─────────
        say("VmHWM/VmRSS before load: ${vmHwmBytes()} / ${rssBytes()}")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = graph.absolutePath),
                tokens = tokens.absolutePath,
                // Two, matching DwAsrSpeechModel and the band in DW_TIER1_CATALOGUE. Any other value
                // makes the real-time factors below incomparable with everything already recorded.
                numThreads = 2,
                debug = false,
            ),
        )
        val loadStart = SystemClock.elapsedRealtime()
        val recognizer = runCatching { OfflineRecognizer(config = config) }
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        if (recognizer.isFailure) {
            val error = recognizer.exceptionOrNull()
            say("LOAD FAILED after $loadMs ms: ${error?.javaClass?.name}: ${error?.message}")
            say("VmHWM/VmRSS at failure : ${vmHwmBytes()} / ${rssBytes()}")
            say("meminfo at failure     : ${meminfoLine()}")
            say("=== END DW INDICCONFORMER PROBE (load failed) ===")
            // NOT an assertion failure. "This artifact does not load on this handset" is the answer
            // the probe was run to get, and it is a measurement rather than a defect in the app.
            return
        }
        val engine = recognizer.getOrThrow()
        say("LOADED in $loadMs ms")
        say("VmHWM/VmRSS after load : ${vmHwmBytes()} / ${rssBytes()}")
        say("pss after load         : ${memInfoLine()}")

        val wavs = File(dir, "wav").listFiles { f -> f.name.endsWith(".wav") }
            ?.sortedBy { it.name }.orEmpty()
        if (wavs.isEmpty()) {
            say("NO WAV FILES at ${File(dir, "wav").absolutePath}. The graph loaded and nothing was " +
                "transcribed, which proves the load and nothing else.")
        }
        wavs.forEach { wav ->
            val wave = WaveReader.readWave(wav.absolutePath)
            val durationMs = if (wave.sampleRate > 0) {
                wave.samples.size * 1000L / wave.sampleRate
            } else {
                0L
            }
            val decodeStart = SystemClock.elapsedRealtime()
            val stream = engine.createStream()
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            engine.decode(stream)
            val result = engine.getResult(stream)
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart
            stream.release()

            say("--- ${wav.name} ---")
            say("audio        : ${wave.samples.size} samples at ${wave.sampleRate} Hz = $durationMs ms")
            say("decode       : $decodeMs ms   rtf = " +
                if (durationMs > 0) "%.3f".format(decodeMs.toDouble() / durationMs) else "n/a")
            say("TRANSCRIPT   : ${result.text}")
            say("tokens       : ${result.tokens.size}")
            say("VmHWM/VmRSS  : ${vmHwmBytes()} / ${rssBytes()}")
        }

        say("--- final ---")
        say("PEAK RSS (VmHWM, whole process, this run): ${vmHwmBytes()} bytes")
        say("pss at end   : ${memInfoLine()}")
        say("meminfo      : ${meminfoLine()}")
        engine.release()
        say("=== END DW INDICCONFORMER PROBE ===")
    }

    /**
     * Lower-case hex SHA-256 of [file], or null if it could not be read.
     *
     * 1 MiB reads, which is the size the app's own hasher uses — the figure that matters is the digest
     * and not the throughput, but keeping the block size the same means the two cannot disagree about
     * how long hashing 493 MB takes on this handset.
     */
    private fun sha256OfFile(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun vmHwmBytes(): Long = procStatusKb("VmHWM")

    private fun rssBytes(): Long = procStatusKb("VmRSS")

    private fun procStatusKb(field: String): Long = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("$field:") }
            ?.filter { it.isDigit() }
            ?.toLong()
            ?.times(1024L)
            ?: -1L
    }.getOrDefault(-1L)

    /** What the kernel says is left, at this instant. The number the arithmetic was done against. */
    private fun meminfoLine(): String = runCatching {
        File("/proc/meminfo").readLines()
            .filter { it.startsWith("MemTotal:") || it.startsWith("MemAvailable:") }
            .joinToString("  ") { it.trim() }
    }.getOrDefault("unreadable")

    private fun memInfoLine(): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return "dalvikPss=${info.dalvikPss}kB nativePss=${info.nativePss}kB " +
            "otherPss=${info.otherPss}kB totalPss=${info.totalPss}kB"
    }
}
