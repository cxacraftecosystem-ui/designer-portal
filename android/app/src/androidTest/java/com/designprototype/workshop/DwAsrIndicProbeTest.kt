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

@RunWith(AndroidJUnit4::class)
class DwAsrIndicProbeTest {

    private val tag = "DWICPROBE"

    private fun say(line: String) {
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

        say("VmHWM/VmRSS before load: ${vmHwmBytes()} / ${rssBytes()}")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = graph.absolutePath),
                tokens = tokens.absolutePath,
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
