package com.designprototype.workshop

import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DW_ASR_MODELS
import com.designprototype.workshop.data.DwAsrVerification
import com.designprototype.workshop.data.dwAsrEngineAbiFor
import com.designprototype.workshop.data.dwAsrModel
import com.designprototype.workshop.data.dwAsrVerify
import com.designprototype.workshop.ui.designworkshop.dwAsrSha256OfFile
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DwAsrEngineProbeTest {

    private val tag = "DWASRPROBE"

    private fun say(line: String) {
        line.chunked(900).forEach { Log.i(tag, it) }
    }

    private val stagingDir = File("/data/local/tmp/dwasr")

    @Test
    fun transcribesOnThisHandset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        say("=== DW ASR ENGINE PROBE ===")
        say("device       : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) API ${Build.VERSION.SDK_INT}")
        say("abis         : ${Build.SUPPORTED_ABIS.joinToString()}")
        say("process      : ${context.packageName}  pid=${android.os.Process.myPid()}")

        say("--- native library search path ---")
        say("classloader  : ${javaClass.classLoader}")
        say("nativeLibDir : ${context.applicationInfo.nativeLibraryDir}")
        say("filesDir     : ${context.filesDir.absolutePath}")
        val jniInApk = File(context.applicationInfo.nativeLibraryDir, "libsherpa-onnx-jni.so")
        say("engine .so in the APK's own lib dir? ${jniInApk.exists()} (${jniInApk.length()} bytes)")

        say("engine ABI for this handset: ${dwAsrEngineAbiFor(Build.SUPPORTED_ABIS.toList())}")
        val model = dwAsrModel()
        if (model == null) {
            say("NO PINNED MODEL. DW_ASR_MODELS holds ${DW_ASR_MODELS.size} row(s). Nothing to " +
                "measure; this is a build fact, not a handset one.")
            return
        }
        say("--- pinned model ---")
        say("modelId      : ${model.modelId}")
        say("quantisation : ${model.quantisation}")
        say("upstream     : ${model.upstreamVersion}")
        say("provenance   : ${model.provenance}")
        say("languages    : ${model.languageNote}")
        model.files.forEach { say("file         : ${it.fileName}  ${it.bytes} bytes  sha256=${it.sha256}") }

        val modelDir = File(File(context.filesDir, "asr-model"), model.modelId)
        modelDir.mkdirs()
        var copiedBytes = 0L
        val copyStart = SystemClock.elapsedRealtime()
        model.files.forEach { pinned ->
            val target = File(modelDir, pinned.fileName)
            val source = File(stagingDir, pinned.fileName)
            if (target.length() != pinned.bytes) {
                if (!source.exists()) {
                    say("MISSING: ${source.absolutePath} is not there and ${target.absolutePath} " +
                        "does not already hold ${pinned.bytes} bytes. Push the model as the KDoc " +
                        "at the top of this file says, then run this again.")
                    throw AssertionError(
                        "The model file ${pinned.fileName} is neither staged at " +
                            "${source.absolutePath} nor already installed at ${target.absolutePath}."
                    )
                }
                source.inputStream().use { input ->
                    target.outputStream().use { output -> copiedBytes += input.copyTo(output) }
                }
            }
        }
        say("copied ${copiedBytes} bytes into filesDir in " +
            "${SystemClock.elapsedRealtime() - copyStart} ms")

        say("--- verification, in this run, off the files on disk ---")
        val verdicts = model.files.map { pinned ->
            val onDisk = File(modelDir, pinned.fileName)
            val actual = dwAsrSha256OfFile(onDisk)
            val verdict = if (actual == null) null else dwAsrVerify(pinned.sha256, actual)
            say("${pinned.fileName}: ${onDisk.length()} bytes on disk, sha256=$actual -> $verdict")
            verdict
        }
        if (verdicts.any { it != DwAsrVerification.VERIFIED }) {
            throw AssertionError(
                "Refusing to load the engine: at least one model file on this phone does not hash " +
                    "to the digest pinned in this APK. The verdicts were $verdicts. Delete " +
                    "${modelDir.absolutePath} and push the model again; if it still fails, the " +
                    "file being staged is not the file DW_ASR_MODELS describes."
            )
        }
        val onDiskTotal = model.files.sumOf { File(modelDir, it.fileName).length() }
        say("ON-DISK SIZE (all pinned files, measured on the phone): $onDiskTotal bytes")

        say("--- loading ---")
        val rssBeforeLoad = vmHwmBytes() to rssBytes()
        say("VmHWM/VmRSS before load: ${rssBeforeLoad.first} / ${rssBeforeLoad.second}")

        val head = model.heads.first()
        say("head         : ${head.languageTag ?: "every language this model serves"} -> " +
            "${head.graphFileName} + ${head.tokensFileName}  (family ${model.family})")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = File(modelDir, head.graphFileName).absolutePath,
                ),
                tokens = File(modelDir, head.tokensFileName).absolutePath,
                numThreads = 2,
                debug = false,
            ),
        )
        val loadStart = SystemClock.elapsedRealtime()
        val recognizer = OfflineRecognizer(config = config)
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        say("ENGINE LOADED. recogniser constructed in $loadMs ms")
        say("VmHWM/VmRSS after load : ${vmHwmBytes()} / ${rssBytes()}")
        say("dalvik/native/total after load: ${memInfoLine()}")

        val wavDir = File(stagingDir, "wav")
        val wavs = wavDir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
        if (wavs.isEmpty()) {
            say("NO WAV FILES at ${wavDir.absolutePath}. The engine loaded and nothing was " +
                "transcribed, which proves the load and nothing else.")
        }
        wavs.forEach { wav ->
            val wave = WaveReader.readWave(wav.absolutePath)
            val samples = wave.samples
            val rate = wave.sampleRate
            val durationMs = if (rate > 0) samples.size * 1000L / rate else 0L

            val decodeStart = SystemClock.elapsedRealtime()
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, rate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart
            stream.release()

            say("--- ${wav.name} ---")
            say("audio        : ${samples.size} samples at $rate Hz = ${durationMs} ms")
            say("decode       : $decodeMs ms   real-time factor = " +
                "${if (durationMs > 0) "%.3f".format(decodeMs.toDouble() / durationMs) else "n/a"}")
            say("TRANSCRIPT   : ${result.text}")
            say("tokens       : ${result.tokens.size}")
            say("VmHWM/VmRSS  : ${vmHwmBytes()} / ${rssBytes()}")
        }

        say("--- backgrounding ---")
        say("oom_score_adj before HOME: ${readProc("/proc/self/oom_score_adj")}")
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(5_000)
        say("oom_score_adj after HOME : ${readProc("/proc/self/oom_score_adj")}")
        say("VmHWM/VmRSS after HOME   : ${vmHwmBytes()} / ${rssBytes()}")

        if (wavs.isNotEmpty()) {
            val wav = wavs.first()
            val wave = WaveReader.readWave(wav.absolutePath)
            val stream = recognizer.createStream()
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            recognizer.decode(stream)
            val again = recognizer.getResult(stream)
            stream.release()
            say("DECODED AGAIN AFTER BACKGROUNDING (${wav.name}): ${again.text}")
        }

        say("--- final ---")
        say("PEAK RSS (VmHWM, whole process, this run): ${vmHwmBytes()} bytes")
        say("dalvik/native/total at end: ${memInfoLine()}")
        recognizer.release()
        say("=== END DW ASR ENGINE PROBE ===")
    }

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

    private fun readProc(path: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault("unreadable")

    private fun memInfoLine(): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return "dalvikPss=${info.dalvikPss}kB nativePss=${info.nativePss}kB " +
            "otherPss=${info.otherPss}kB totalPss=${info.totalPss}kB " +
            "totalPrivateDirty=${info.totalPrivateDirty}kB"
    }
}
