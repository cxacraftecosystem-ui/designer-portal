package com.designprototype.workshop.ui.designworkshop

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.designprototype.workshop.data.DwAsrModelFamily
import com.designprototype.workshop.data.DwAsrModelHead
import com.designprototype.workshop.data.DwAsrModelStatus
import com.designprototype.workshop.data.dwAsrModelMayLoad
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.launch
import java.io.File

/**
 * **THE LOADER. THE LINE BETWEEN A MEASURED MODEL AND A DESIGNER PRESSING DICTATE.**
 *
 * ── WHAT WAS MISSING, AND WHY NOTHING ELSE IN THE LANE MATTERED WITHOUT IT ────────────────────
 *
 * [DwDictationRung.APP_SPEECH_MODEL] has existed in `DwDictationLadder.kt` since the ladder was
 * written, and `beginAt` in `DwDictation.kt` **stepped silently past it** — a deliberate stopping
 * point, argued at the time, because there was no loader and a `TODO()` would have been a crash
 * waiting for whoever wired one. The only code in this repository that had ever run this engine was
 * an instrumented probe (`DwAsrEngineProbeTest`). This file is the loader that comment was waiting
 * for, and it is written to the shape that comment specified: load through the verified model, and on
 * a refusal record it for the tag and call `advance`.
 *
 * ── WHERE THE ENGINE COMES FROM, WHICH IS NOT NEGOTIABLE ──────────────────────────────────────
 *
 * `com.k2fsa.sherpa.onnx.OfflineRecognizer` static-inits `System.loadLibrary("sherpa-onnx-jni")`, so
 * the `.so` has to be somewhere `ClassLoader.findLibrary` looks, and `filesDir` is not and cannot be
 * made to be (docs/DEVICE-TIER-MEASUREMENT.md, *THE FINDING THAT INVALIDATES A DESIGN*). It is
 * therefore **in the APK** — 23,646,824 bytes at `lib/arm64-v8a/libsherpa-onnx-jni.so`, mapped
 * straight out of `base.apk` because `minSdk = 26` gives `extractNativeLibs="false"`. That is what
 * `DW_TIER1_RUNTIME_PRESENT` now says. **The MODEL, by contrast, is data given to the graph executor
 * by absolute path, so it lives in `filesDir` and is verified there** — which is the arrangement the
 * probe measured and the arrangement that ships.
 *
 * ── THE THREE RULES THIS FILE KEEPS, AND WHAT EACH ONE COSTS ──────────────────────────────────
 *
 *  1. **NOTHING IS DECODED THAT WAS NOT VERIFIED IN THIS RUN.** [transcribe] takes a
 *     [DwAsrModelStatus] and asks [dwAsrModelMayLoad] before it opens anything. A status can only be
 *     INSTALLED if every file was hashed off disk in this process, so there is no path here that
 *     reaches a graph nobody looked at.
 *  2. **THE RECOGNISER IS RELEASED AFTER EVERY DICTATION**, and that is a measured decision rather
 *     than tidiness. Peak RSS with this model loaded is **1.26 GB** on a handset reporting ~1.5 GB
 *     free. Keeping it resident between dictations would leave the app one camera preview away from
 *     the low-memory killer for the whole workshop — and what the killer takes is the process, with
 *     the draft in it. The cost of releasing is that every dictation pays the load again: measured at
 *     **3,263 / 3,290 / 3,664 / 8,510 ms** across four runs, which is why [DW_ASR_MODEL_LOAD_HINT_MS]
 *     exists and why the panel says so before the designer commits.
 *  3. **NOTHING IS REPORTED THAT IS NOT MEASURED.** There is no progress callback in this file and
 *     there is no partial transcript, because a CTC model has neither: audio goes in whole and one
 *     result comes out. A bar that filled at a guessed rate would be a fabricated measurement on the
 *     one screen where a designer is deciding whether to keep waiting.
 *
 * ── AND WHAT IT DELIBERATELY DOES NOT TOUCH ───────────────────────────────────────────────────
 *
 * **CONSENT AND THE DAILY CAP.** Neither is read here and neither may be. The cap is scoped, in the
 * repository owner's own words, to `/dictate` reaching ElevenLabs, Deepgram or Whisper; the consent
 * question is whether an artisan's recording may LEAVE THE DEVICE. **Nothing here leaves the device**
 * — no `okhttp`, no repository, no upload, no file that outlives the call. Gating this rung on either
 * would withdraw an offline capability over a question that does not apply to it, and
 * `DwDictationLadder`'s own conjunction for `appModelRung` reads neither field for the same reason.
 */
internal object DwAsrSpeechModel {

    private const val TAG = "DwAsrSpeechModel"

    /**
     * The recogniser, while one dictation is using it. **Null between dictations, on purpose.**
     *
     * Guarded by the object monitor rather than left to chance: [transcribe] runs on a background
     * dispatcher and [release] can be called from the model controller's remove path at the same
     * moment, and a `release()` racing a `decode()` on the same native handle is a SIGSEGV rather than
     * an exception.
     */
    private var recognizer: OfflineRecognizer? = null

    /**
     * How long opening the model took, measured. **Four readings, and the spread is the point.**
     *
     * 3,263 / 3,290 / 3,664 / 8,510 ms on the fleet's SM-M325F, off files already in `filesDir`
     * (docs/DEVICE-TIER-MEASUREMENT.md). The 8,510 came immediately after another run on an
     * already-warm handset with `cpu0` observed at 774 MHz against a 1,800 MHz maximum — so the
     * spread is thermal or governor state and **which of the two is unmeasured, in that word.**
     *
     * The LARGEST is the one quoted to a designer, for [DwModelRtfBand]'s reason: somebody told three
     * seconds and made to wait nine decides the app has frozen.
     */
    const val DW_ASR_MODEL_LOAD_HINT_MS: Long = 8_510L

    /**
     * How many threads the decode runs on. **Two, because two is what was measured.**
     *
     * The M32's SoC reports eight cores and six of them are the little cluster; more threads on a
     * phone in a courtyard is heat and battery rather than speed. **What any other value would do to
     * the real-time factor, the heat and the battery is UNMEASURED** — one setting was run, and the
     * band in `DW_TIER1_CATALOGUE` is a band for this setting only. Changing this number invalidates
     * that band and the sentences built from it.
     */
    private const val DW_ASR_NUM_THREADS = 2

    /** The sample rate the model's feature extractor expects. Not a preference — the model's own. */
    const val DW_ASR_SAMPLE_RATE = 16_000

    /** What one decode produced, or why there was none. */
    sealed interface Outcome {
        /** The transcript, exactly as the engine produced it. Possibly empty — see [transcribe]. */
        data class Text(val text: String, val decodeMillis: Long) : Outcome

        /**
         * The model could not be opened or the decode threw. **[detail] is kept verbatim.**
         *
         * A refusal here is the counterpart of rung 1's `ERROR_LANGUAGE_UNAVAILABLE`: it is a
         * MEASUREMENT of this handset that contradicts a catalogue, which is exactly what
         * docs/DEVICE-TIER-MEASUREMENT.md exists to collect. The caller records it for the tag so the
         * next four hundred fields on a stage do not each pay for the same refusal.
         */
        data class Refused(val detail: String) : Outcome
    }

    /**
     * Decode [samples] with the verified model. **Blocking, and slower than real time — call it off
     * the main thread.**
     *
     * [samples] is mono PCM in [-1, 1] at [DW_ASR_SAMPLE_RATE], which is what [DwAsrPcmRecorder]
     * produces and what `WaveReader` produces in the probe — the same shape, so what ships and what
     * was measured are the same call.
     *
     * **AN EMPTY TRANSCRIPT IS A SUCCESS, NOT A REFUSAL.** A designer who tapped Stop before speaking
     * gets [Outcome.Text] with an empty string, and the caller says "nothing was heard" rather than
     * stepping to the next rung — because the model worked, and silently opening a network engine at
     * somebody who has already finished speaking is the failure `advance`'s own precondition is about.
     */
    fun transcribe(
        context: Context,
        status: DwAsrModelStatus,
        samples: FloatArray,
        /**
         * **THE LANGUAGE BEING DICTATED, AND FOR A PER-LANGUAGE MODEL IT DECIDES WHICH GRAPH OPENS.**
         *
         * Not a hint and not a decoding preference. IndicConformer's 22 languages share one encoder and
         * differ in a CTC head that is sliced **when the artifact is built**, because sherpa-onnx has no
         * runtime mask — see [DwAsrModelHead]. Decoded through the wrong head, or through none, the
         * model is acoustically right and spells the answer in the wrong script, which is a transcript a
         * field would store as data. So this argument is required, and a tag no head answers for is a
         * refusal rather than a fallback to whichever head was written first.
         *
         * For a single-head model — the Omnilingual export pinned today — every tag resolves to the one
         * head and this argument changes nothing. That is the correct behaviour rather than a special
         * case: [DwAsrModel.headFor] is what knows the difference.
         */
        languageTag: String,
    ): Outcome = synchronized(this) {
        // THE GATE. Belt to DwAsrModelStatus's braces — see [dwAsrModelMayLoad], which is asked here
        // rather than trusted from whoever built the status.
        if (!dwAsrModelMayLoad(status)) {
            return Outcome.Refused(
                "the speech model on this phone was not verified in this run, so it was not opened"
            )
        }
        val model = status.model
            ?: return Outcome.Refused("no speech model is pinned in this build")
        val head = model.headFor(languageTag)
            ?: return Outcome.Refused(
                "the speech model on this phone has no head for $languageTag, so there is no graph " +
                    "that would write this language down"
            )
        val dir = dwAsrModelDir(context, model.modelId)
        val graph = File(dir, head.graphFileName)
        val tokens = File(dir, head.tokensFileName)
        if (!graph.isFile || !tokens.isFile) {
            return Outcome.Refused(
                "the speech model's files are not where this app installed them (${dir.absolutePath})"
            )
        }
        if (samples.isEmpty()) return Outcome.Text("", 0L)

        return runCatching {
            val started = SystemClock.elapsedRealtime()
            val engine = recognizer ?: OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = DW_ASR_SAMPLE_RATE, featureDim = 80),
                    /*
                     * THE FAMILY DECIDES WHICH SUB-CONFIG IS POPULATED, AND THE CATALOGUE DECIDES THE
                     * FAMILY. `OfflineModelConfig` holds seventeen mutually exclusive model configs and
                     * the engine picks its decoder from whichever is set; naming one as a literal was
                     * right while one model was pinned and becomes a silent mismatch the moment a second
                     * family is. The `when` is exhaustive on purpose — adding a family to the enum
                     * without teaching this branch how to open it will not compile.
                     */
                    modelConfig = when (model.family) {
                        DwAsrModelFamily.OMNILINGUAL_ASR_CTC -> OfflineModelConfig(
                            omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                                model = graph.absolutePath,
                            ),
                            tokens = tokens.absolutePath,
                            numThreads = DW_ASR_NUM_THREADS,
                            debug = false,
                        )

                        DwAsrModelFamily.NEMO_ENC_DEC_CTC -> OfflineModelConfig(
                            nemo = OfflineNemoEncDecCtcModelConfig(
                                model = graph.absolutePath,
                            ),
                            tokens = tokens.absolutePath,
                            numThreads = DW_ASR_NUM_THREADS,
                            debug = false,
                        )
                    },
                ),
            ).also { recognizer = it }
            val loaded = SystemClock.elapsedRealtime()
            Log.i(TAG, "recogniser ready in ${loaded - started} ms; decoding ${samples.size} samples")

            val stream = engine.createStream()
            val text = try {
                stream.acceptWaveform(samples, DW_ASR_SAMPLE_RATE)
                engine.decode(stream)
                engine.getResult(stream).text
            } finally {
                runCatching { stream.release() }
            }
            val done = SystemClock.elapsedRealtime()
            Log.i(TAG, "decoded in ${done - loaded} ms")
            Outcome.Text(text.trim(), done - loaded)
        }.getOrElse { error ->
            /*
             * THE HANDLE IS DROPPED ON ANY FAILURE. A recogniser that threw part-way through
             * construction or decoding is not one to hand the next dictation, and a native object in
             * an unknown state is worse than none: the next `decode` on it is a crash in the process
             * holding the draft rather than a second exception.
             */
            releaseLocked()
            Outcome.Refused(error.message ?: error.javaClass.simpleName)
        }.also {
            /*
             * RELEASED AFTER EVERY DICTATION — rule 2 in this file's header. 1.26 GB is not a thing to
             * hold between two taps on a phone that reports 1.5 GB free, and the alternative to paying
             * the load again is the low-memory killer taking the process with the draft in it.
             */
            releaseLocked()
        }
    }

    /** Put the model down and give the memory back. Safe to call when nothing is loaded. */
    fun release() = synchronized(this) { releaseLocked() }

    private fun releaseLocked() {
        val engine = recognizer ?: return
        recognizer = null
        runCatching { engine.release() }
    }
}

/**
 * **WHETHER THIS PROCESS HAS A VERIFIED SPEECH MODEL, ANSWERED ONCE PER RUN RATHER THAN PER FIELD.**
 *
 * ── THE PROBLEM THIS SOLVES, WHICH IS A REAL ONE AND NOT AN OPTIMISATION ──────────────────────
 *
 * `DwAsrModelStatus` may only say INSTALLED when every file was hashed **in this run**, and this
 * model is **365 MB** — roughly a second and a half of reading on the fleet's handset. A stage screen
 * composes one [DwDictationButton] per prose field, hundreds across a workshop, and `conditionsNow()`
 * runs on every tap. Asking each of them to hash 365 MB would be minutes of flash reading per screen,
 * on the main thread, to answer a question whose answer cannot change while the process lives.
 *
 * **"IN THIS RUN" MEANS THIS PROCESS, WHICH IS EXACTLY WHAT THIS CACHES.** The rule exists because a
 * digest written down at install time and trusted afterwards is a claim about a file as it was before
 * the phone rebooted, before the app updated, and before anybody with a cable rewrote it — none of
 * which can happen without this process dying. Nothing is persisted here: the field is in memory, it
 * goes with the process, and the next launch hashes again.
 *
 * ── IT ANSWERS "NOT YET" RATHER THAN BLOCKING, AND THAT COSTS THE FIRST TAP ───────────────────
 *
 * [warm] does the reading on a background thread; [status] returns whatever has landed, which before
 * it lands is [DwAsrModelState.UNKNOWN]. `DwDictationConditions.appModelServesLanguage` is then false
 * and the ladder does not offer the rung — **the honest-unknown rule pointing the way that file's own
 * docstring says it points for this field**: an unconfirmed model install resolves to "do not offer
 * it", because a rung that opens a model this app cannot confirm would fail *after* the designer has
 * spoken, which is the expensive kind of failure.
 *
 * So a designer who taps dictate within the first second or two of the app starting may get the
 * server or a Google pack instead of the local model. That is a real cost and it is the right side to
 * err on.
 */
internal object DwAsrModelRun {

    @Volatile private var cached: DwAsrModelStatus = DwAsrModelStatus()

    @Volatile private var warming: Boolean = false

    /**
     * Languages this phone's own model was asked for and could not take, in this run.
     *
     * The exact counterpart of `DwDictationRun.engineRefused` for rung 1, kept for its reason: a model
     * listed as serving a language that then refuses it on this handset is a model that cannot serve
     * this dictation whatever the catalogue says — **and it is data**, in the sense
     * docs/DEVICE-TIER-MEASUREMENT.md means. Remembering it stops the next four hundred fields on a
     * stage from each opening a model that has already said no.
     */
    private val refused = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** What was verified in this run. [DwAsrModelState.UNKNOWN] until [warm] has landed. */
    fun status(): DwAsrModelStatus = cached

    /** Publish a reading somebody else has already taken — the settings card's, so it is not retaken. */
    fun publish(status: DwAsrModelStatus) {
        cached = status
    }

    /**
     * Hash the installed model on a background thread, once per process, unless it is already known.
     *
     * Cheap to call from anywhere and from every field: the guard makes every call after the first a
     * boolean read. It re-runs after a FAILED or NOT_INSTALLED reading, because an install may have
     * happened since — but never while one is in flight, and never once a verified answer is held.
     */
    fun warm(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        if (warming || dwAsrModelMayLoad(cached)) return
        warming = true
        val app = context.applicationContext
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                cached = dwAsrReadInstalledModel(app)
            } finally {
                warming = false
            }
        }
    }

    /** Whether a verified model on this phone was MEASURED to serve [tag]. Both halves, ANDed. */
    fun servesLanguage(tag: String): Boolean {
        if (!dwAsrModelMayLoad(cached)) return false
        val id = cached.model?.modelId ?: return false
        return com.designprototype.workshop.data.DW_TIER1_CATALOGUE
            .any { it.modelId == id && it.servesLanguage(tag) }
    }

    /** Whether this run has already had the model refuse [tag]. */
    fun hasRefused(tag: String): Boolean = tag in refused

    /** Write down that the model would not take [tag] on this handset. See [refused]. */
    fun recordRefusal(tag: String) {
        refused += tag
    }
}

/**
 * **THE MICROPHONE FOR THE ON-DEVICE MODEL: 16 kHz mono PCM, straight into memory.**
 *
 * ── WHY NOT [DwDictationRecorder], WHICH ALREADY EXISTS ───────────────────────────────────────
 *
 * That one writes a compressed file for the SERVER, which is the right shape for something that has
 * to travel: it is small, and a codec the server can open. This rung sends nothing anywhere, and what
 * the recogniser wants is raw float samples at exactly 16 kHz. Going through a file would mean
 * encoding, writing, reading and decoding audio that never leaves the device, and it would introduce
 * a resampling step whose effect on the word error rate is unmeasured — against a model whose measured
 * WER is already the thing deciding which languages it may be offered for.
 *
 * **AND IT IS THE SHAPE THAT WAS MEASURED.** `DwAsrEngineProbeTest` fed `WaveReader.readWave`'s
 * `FloatArray` at 16 kHz. This produces the same array by the same arithmetic, so the transcripts in
 * docs/DEVICE-TIER-MEASUREMENT.md are evidence about what ships rather than about a probe.
 *
 * ── WHAT IT COSTS IN MEMORY, WHICH IS SMALL AND IS STILL CAPPED ───────────────────────────────
 *
 * Four bytes a sample at 16,000 a second is 64 kB per second — **3.8 MB for a minute**, which is
 * nothing beside the model's 1.26 GB. The cap below is therefore not about memory; it is about the
 * WAIT, which is the thing that actually costs a designer something.
 */
internal class DwAsrPcmRecorder {

    private var record: AudioRecord? = null
    @Volatile private var stopping = false

    /**
     * The longest recording this rung will take. **Ninety seconds, and it is a CHOSEN number.**
     *
     * Rung 2's cap is four minutes because the wait after Stop is an upload and a server. Here the
     * wait is this phone decoding at **up to 2.967× the length of the audio** — so four minutes of
     * speech is close to twelve minutes of standing still, and a cap that let a designer reach it
     * would be a trap the app set for them. Ninety seconds costs at most about four and a half
     * minutes, which is long enough to be worth waiting for and short enough to abandon.
     *
     * **NOTHING MEASURED CHOSE 90.** What a designer will actually tolerate in a courtyard is
     * unmeasured; this is a number picked to keep the worst case under five minutes, and if somebody
     * measures the real tolerance it belongs in docs/DEVICE-TIER-MEASUREMENT.md and this constant
     * should be revisited against it rather than defended.
     */
    val maxMillis: Long = 90_000L

    /**
     * Open the microphone. Returns a refusal sentence, or null when it started.
     *
     * `@SuppressLint`: RECORD_AUDIO is requested and granted by the ladder walk before any rung is
     * started ([DwDictationButton]'s `micPermission`), which is the same guarantee rung 2's recorder
     * relies on. A permission check here would be a second copy of a decision made one function up.
     */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        stop()
        stopping = false
        val minBuffer = AudioRecord.getMinBufferSize(
            DwAsrSpeechModel.DW_ASR_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            return "This phone would not open its microphone at the quality this app's own speech " +
                "model needs (16 kHz). Nothing was recorded."
        }
        val built = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                DwAsrSpeechModel.DW_ASR_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // Four times the minimum, so a scheduling hiccup on a loaded handset drops no audio.
                // A dropped window is not an error anywhere — it is a word missing from a transcript
                // nobody would think to check against the recording, because there is no recording.
                minBuffer * 4,
            )
        }.getOrNull()
        if (built == null || built.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { built?.release() }
            return "This phone would not start its microphone. Nothing was recorded."
        }
        return runCatching {
            built.startRecording()
            record = built
            null
        }.getOrElse {
            runCatching { built.release() }
            "This phone would not start its microphone. Nothing was recorded."
        }
    }

    /**
     * Read until [stop] is called or the cap is reached. **Blocking — call it off the main thread.**
     *
     * Returns the samples captured, which may be empty if the designer stopped immediately. It never
     * throws for an ordinary stop: [stop] closes the device and the read loop ends.
     */
    fun readUntilStopped(): FloatArray {
        val device = record ?: return FloatArray(0)
        val chunk = ShortArray(4096)
        val out = ArrayList<FloatArray>()
        var total = 0
        val maxSamples = (maxMillis * DwAsrSpeechModel.DW_ASR_SAMPLE_RATE / 1000L).toInt()
        while (!stopping && total < maxSamples) {
            val read = runCatching { device.read(chunk, 0, chunk.size) }.getOrDefault(-1)
            if (read <= 0) break
            val take = minOf(read, maxSamples - total)
            val floats = FloatArray(take)
            for (i in 0 until take) {
                // 32768 rather than 32767: the negative range is one larger, and dividing by 32767
                // lets the most negative sample exceed -1.0, which is a clipped input to a feature
                // extractor that expects [-1, 1].
                floats[i] = chunk[i] / 32768.0f
            }
            out += floats
            total += take
        }
        val samples = FloatArray(total)
        var offset = 0
        out.forEach { part ->
            part.copyInto(samples, offset)
            offset += part.size
        }
        return samples
    }

    /** Ask the read loop to finish. Idempotent; safe from any thread. */
    fun requestStop() {
        stopping = true
    }

    /** Close the microphone. Safe to call twice, and safe to call when nothing is open. */
    fun stop() {
        stopping = true
        val device = record ?: return
        record = null
        runCatching { device.stop() }
        runCatching { device.release() }
    }
}
