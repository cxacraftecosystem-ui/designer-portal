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

internal object DwAsrSpeechModel {

    private const val TAG = "DwAsrSpeechModel"

    private var recognizer: OfflineRecognizer? = null

    const val DW_ASR_MODEL_LOAD_HINT_MS: Long = 8_510L

    private const val DW_ASR_NUM_THREADS = 2

    const val DW_ASR_SAMPLE_RATE = 16_000

    sealed interface Outcome {
        data class Text(val text: String, val decodeMillis: Long) : Outcome

        data class Refused(val detail: String) : Outcome
    }

    fun transcribe(
        context: Context,
        status: DwAsrModelStatus,
        samples: FloatArray,
        languageTag: String,
    ): Outcome = synchronized(this) {
        if (!dwAsrModelMayLoad(status)) {
            return Outcome.Refused(
                "the speech model on this phone has not been checked yet"
            )
        }
        val model = status.model
            ?: return Outcome.Refused("there is no speech model on this phone")
        val head = model.headFor(languageTag)
            ?: return Outcome.Refused(
                "the speech model on this phone does not write this language"
            )
        val dir = dwAsrModelDir(context, model.modelId)
        val graph = File(dir, head.graphFileName)
        val tokens = File(dir, head.tokensFileName)
        if (!graph.isFile || !tokens.isFile) {
            return Outcome.Refused(
                "the speech model's files are missing from this phone"
            )
        }
        if (samples.isEmpty()) return Outcome.Text("", 0L)

        return runCatching {
            val started = SystemClock.elapsedRealtime()
            val engine = recognizer ?: OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = DW_ASR_SAMPLE_RATE, featureDim = 80),
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
            releaseLocked()
            Outcome.Refused(error.message ?: error.javaClass.simpleName)
        }.also {
            releaseLocked()
        }
    }

    fun release() = synchronized(this) { releaseLocked() }

    private fun releaseLocked() {
        val engine = recognizer ?: return
        recognizer = null
        runCatching { engine.release() }
    }
}

internal object DwAsrModelRun {

    @Volatile private var cached: DwAsrModelStatus = DwAsrModelStatus()

    @Volatile private var warming: Boolean = false

    private val refused = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun status(): DwAsrModelStatus = cached

    fun publish(status: DwAsrModelStatus) {
        cached = status
    }

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

    fun servesLanguage(tag: String): Boolean {
        if (!dwAsrModelMayLoad(cached)) return false
        val id = cached.model?.modelId ?: return false
        return com.designprototype.workshop.data.DW_TIER1_CATALOGUE
            .any { it.modelId == id && it.servesLanguage(tag) }
    }

    fun hasRefused(tag: String): Boolean = tag in refused

    fun recordRefusal(tag: String) {
        refused += tag
    }
}

internal class DwAsrPcmRecorder {

    private var record: AudioRecord? = null
    @Volatile private var stopping = false

    val maxMillis: Long = 90_000L

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
            return "Couldn't open the microphone at 16 kHz."
        }
        val built = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                DwAsrSpeechModel.DW_ASR_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
        }.getOrNull()
        if (built == null || built.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { built?.release() }
            return "Couldn't start the microphone."
        }
        return runCatching {
            built.startRecording()
            record = built
            null
        }.getOrElse {
            runCatching { built.release() }
            "Couldn't start the microphone."
        }
    }

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

    fun requestStop() {
        stopping = true
    }

    fun stop() {
        stopping = true
        val device = record ?: return
        record = null
        runCatching { device.stop() }
        runCatching { device.release() }
    }
}
