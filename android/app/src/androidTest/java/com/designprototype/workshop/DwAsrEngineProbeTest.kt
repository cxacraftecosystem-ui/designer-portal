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

/**
 * DOES THE OFFLINE SPEECH ENGINE ACTUALLY TRANSCRIBE ON THIS HANDSET, AND WHAT DOES IT COST?
 *
 * The sibling of [DwDeviceTierProbeTest] and [DwLanguagePackProbeTest], written for the same reason
 * and read the same way: **a measurement, not a regression guard.** It prints a readout to logcat
 * under one tag and asserts almost nothing, because every question it answers is one only this phone
 * can answer, and an assertion written before the phone was asked is a claim rather than a check.
 *
 * ── WHAT WAS UNMEASURED WHEN THIS WAS WRITTEN, IN THE DOCUMENTS' OWN WORD ─────────────────────
 *
 * `docs/ASR-RUNTIME-MEASUREMENT.md` §6 and `docs/DEVICE-TIER-MEASUREMENT.md`'s *"What could not be
 * measured, and why"* between them list these, all spelled **unmeasured**, and every one of them is
 * a cell this file exists to fill:
 *
 *  - Does the runtime load on a Galaxy M32? *"no APK from this lane was installed on anything"*
 *  - Peak RSS with a model loaded, at any configured cap
 *  - Latency / real-time factor on the M32
 *  - Does the app survive being backgrounded with a model loaded?
 *  - Anything at all about a speech model's id, quantisation or on-disk size
 *
 * The second document is blunt about why none of them could be filled by attaching a handset:
 * *"A device does not unblock a measurement that needs a file nobody has published."* A file has now
 * been published — see [DW_ASR_MODELS] for exactly which one and where it came from — so the handset
 * is finally the thing that was missing.
 *
 * ── THE ONE THING THIS TEST CHECKS RATHER THAN PRINTS, AND WHY IT IS AN ASSERTION ─────────────
 *
 * **Nothing is loaded that was not verified in this same run.** That is rule 5 of `DwAsrRuntime.kt`
 * and it is the whole reason that file exists, so this test obeys it rather than working around it:
 * every model file on disk is hashed HERE, in THIS run, with the app's own [dwAsrSha256OfFile], and
 * the verdict comes from the app's own [dwAsrVerify]. If any file does not match the digest pinned in
 * [DW_ASR_MODELS] the test fails without constructing a recogniser — because a probe that loaded
 * unverified native code to see whether it worked would have proved the wrong thing, and would have
 * done it inside the process that holds Aadhaar numbers.
 *
 * ── HOW TO RUN IT. IT NEEDS FILES THIS REPOSITORY DOES NOT CONTAIN ────────────────────────────
 *
 * The model is 365 MB and the audio is somebody else's dataset; neither is in git, and the engine
 * files are read from `filesDir` exactly as the shipped design requires. Side-load them first:
 *
 *     adb push model.int8.onnx /data/local/tmp/dwasr/
 *     adb push tokens.txt      /data/local/tmp/dwasr/
 *     adb push <wavs>          /data/local/tmp/dwasr/wav/
 *     adb shell chmod -R 755 /data/local/tmp/dwasr
 *     ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.designprototype.workshop.DwAsrEngineProbeTest
 *     adb logcat -d -s DWASRPROBE:I
 *
 * **WHICH PART OF THE SHIPPED CHAIN THAT BYPASSES, SAID PLAINLY, BECAUSE IT IS NOT NOTHING.** The
 * copy below skips the HTTPS fetch, the `Content-Length` cap, and the container digest of
 * `DwAsrArtifact.sha256` — there is no server serving this file, which is the whole of
 * `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md`'s outstanding half. What it does NOT skip is the part that
 * decides whether code runs: the files are hashed **on disk, in this run, after the copy**, against
 * digests compiled into this APK, through the same two functions the download path uses. A file
 * substituted in `/data/local/tmp` between the push and the run fails here exactly as a substituted
 * download would.
 */
@RunWith(AndroidJUnit4::class)
class DwAsrEngineProbeTest {

    private val tag = "DWASRPROBE"

    private fun say(line: String) {
        // Chunked, because logcat truncates a long line and a truncated transcript is a transcript
        // nobody can compare against its reference — which is the only evidence this test produces.
        line.chunked(900).forEach { Log.i(tag, it) }
    }

    /** Where the side-loaded files are pushed to. Not read by the engine — copied out of first. */
    private val stagingDir = File("/data/local/tmp/dwasr")

    @Test
    fun transcribesOnThisHandset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        say("=== DW ASR ENGINE PROBE ===")
        say("device       : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) API ${Build.VERSION.SDK_INT}")
        say("abis         : ${Build.SUPPORTED_ABIS.joinToString()}")
        say("process      : ${context.packageName}  pid=${android.os.Process.myPid()}")

        // ── 1. WHERE `System.loadLibrary` ACTUALLY LOOKS ──────────────────────────────────────
        //
        // This is not decoration and it is not curiosity. `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md`
        // §8 step 2 instructs the next lane to load the engine's `.so` files out of `filesDir`
        // after downloading them. The upstream Kotlin binding makes that impossible, and the reason
        // is printed below rather than argued: every entry class in `com.k2fsa.sherpa.onnx` runs
        // `System.loadLibrary("sherpa-onnx-jni")` in its static initialiser, and `loadLibrary`
        // resolves through `ClassLoader.findLibrary`, which searches ONLY the directories listed
        // here. `filesDir` is not one of them and cannot be made one.
        say("--- native library search path ---")
        say("classloader  : ${javaClass.classLoader}")
        say("nativeLibDir : ${context.applicationInfo.nativeLibraryDir}")
        say("filesDir     : ${context.filesDir.absolutePath}")
        val jniInApk = File(context.applicationInfo.nativeLibraryDir, "libsherpa-onnx-jni.so")
        say("engine .so in the APK's own lib dir? ${jniInApk.exists()} (${jniInApk.length()} bytes)")

        // ── 2. THE PINNED MODEL, AND WHETHER THE ENGINE IS BUILT FOR THIS PROCESSOR ──────────
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

        // ── 3. COPY OUT OF STAGING INTO `filesDir`, THEN HASH WHAT LANDED ────────────────────
        //
        // Copied rather than read in place, because the rule is that the engine loads from internal
        // storage and nowhere else (`DwAsrRuntimeUi.kt`: never `cacheDir`, never external), and a
        // probe that read from a shell-writable directory would have measured a different thing
        // from the one that ships.
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

        // THE GATE. Every file hashed on disk, in this run, verdict from the app's own verifier.
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

        // ── 4. LOAD. This is the first line in this repository that has ever run native ASR ───
        say("--- loading ---")
        val rssBeforeLoad = vmHwmBytes() to rssBytes()
        say("VmHWM/VmRSS before load: ${rssBeforeLoad.first} / ${rssBeforeLoad.second}")

        /*
         * THE FILE NAMES COME OFF THE MODEL'S OWN HEAD AND ARE NO LONGER LITERALS. They used to be
         * `"model.int8.onnx"` and `"tokens.txt"`, which was correct while one model was pinned and is a
         * silent mismatch the moment a per-language artifact is: this probe would have hashed the files
         * the catalogue lists and then opened two paths that did not exist. `heads.first()` rather than
         * `headFor(tag)` because a probe has no language — it decodes whatever WAVs were pushed — and a
         * probe that guessed a tag would report a refusal about a language nobody asked for.
         */
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
                // Two, as the model's own card recommends for a handset. The M32's SoC reports
                // eight cores but six of them are the little cluster; more threads on a phone in a
                // courtyard is heat and battery rather than speed, and that trade is UNMEASURED
                // here — only this setting was run.
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

        // ── 5. TRANSCRIBE. THE ONLY EVIDENCE THAT COUNTS ─────────────────────────────────────
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

        // ── 6. DOES IT SURVIVE BEING BACKGROUNDED WITH THE MODEL LOADED? ─────────────────────
        //
        // `docs/DEVICE-TIER-MEASUREMENT.md` names this as a cell and says why it is not a nicety:
        // *"A designer takes a photograph mid-summary; if that kills the process, the summary and
        // possibly the draft go with it."*
        //
        // **AND WHAT IS MEASURED HERE IS WEAKER THAN THE QUESTION, WHICH IS SAID HERE RATHER THAN
        // DISCOVERED LATER.** A process under instrumentation is not an ordinary backgrounded app:
        // the runner holds it up, so `oom_score_adj` does not fall the way it would for a designer
        // who pressed Home. The reading below is therefore evidence that the model survives the
        // activity leaving the foreground, and is NOT evidence about the low-memory killer. Both
        // numbers are printed so nobody has to take that on trust.
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

    /**
     * Peak resident set of this process since it started, in bytes, or -1.
     *
     * `VmHWM` is the kernel's own high-water mark — it does not have to be sampled at the right
     * moment, which is the whole reason it is read instead of polling `VmRSS` in a loop and hoping
     * the poll landed on the peak. `docs/DEVICE-TIER-MEASUREMENT.md` is explicit that peak RSS and
     * not on-disk size is *"the number the low-memory killer reads"*.
     */
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

    /**
     * The platform's own accounting, beside the kernel's, because they answer different questions.
     *
     * `Debug.MemoryInfo` splits the resident set into Dalvik, native and other; `VmHWM` is the sum
     * the killer sees. Both are printed because an ONNX model allocates NATIVELY — which is the
     * argument `DwDeviceTier.kt` makes for refusing `getMemoryClass()`, and this is the first
     * reading in this repository that can put a number against it.
     */
    private fun memInfoLine(): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return "dalvikPss=${info.dalvikPss}kB nativePss=${info.nativePss}kB " +
            "otherPss=${info.otherPss}kB totalPss=${info.totalPss}kB " +
            "totalPrivateDirty=${info.totalPrivateDirty}kB"
    }
}
