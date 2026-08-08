package com.designprototype.workshop

import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WHAT THIS PHONE WILL ACTUALLY ADMIT TO BEING ABLE TO DOWNLOAD.
 *
 * Not a UI test and not a regression guard — a MEASUREMENT, run by hand against the fleet's own
 * handset when the answer matters. It asserts almost nothing on purpose: its output is a table in
 * logcat, and the table is the point.
 *
 * The question behind it: the language-pack list offers a download for Hindi and English and for
 * none of the other seventeen. Two explanations were readable from the source and neither could be
 * settled by reading it.
 *
 *  (a) [DwLanguagePackController.probeIntent] deliberately carries no `EXTRA_LANGUAGE`, on the
 *      reasoning that one call returns the device's whole inventory. If the platform instead answers
 *      RELATIVE TO THE INTENT, that one call is asking about the default locale and nothing else.
 *  (b) The controller prefers `createOnDeviceSpeechRecognizer` where it exists. The on-device engine
 *      and the general one are different services with different catalogues, and the smaller one may
 *      simply not carry the other seventeen.
 *
 * So this asks both engines, each both ways, and prints what came back. Whichever cell of that
 * two-by-two holds more languages than the app currently sees is the fix.
 *
 * Run:
 *   ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.designprototype.workshop.DwLanguagePackProbeTest
 *   adb -s <serial> logcat -d -s DWPACKPROBE:I
 */
@RunWith(AndroidJUnit4::class)
class DwLanguagePackProbeTest {

    private val tags = listOf(
        "hi-IN", "en-IN", "bn-IN", "mr-IN", "te-IN", "ta-IN", "gu-IN", "ur-IN", "kn-IN", "or-IN",
        "ml-IN", "pa-IN", "as-IN", "sa-IN", "kok-IN", "ne-IN", "mni-IN", "ks-IN", "sd-IN",
    )

    private data class Answer(
        val installed: List<String> = emptyList(),
        val pending: List<String> = emptyList(),
        val supported: List<String> = emptyList(),
        val online: List<String> = emptyList(),
        val errorCode: Int? = null,
        val timedOut: Boolean = false,
    ) {
        /** What the app would call this language: the state its `dwPackState` would resolve to. */
        fun stateFor(tag: String): String = when {
            errorCode != null -> "ERROR($errorCode)"
            timedOut -> "TIMEOUT"
            installed.isEmpty() && pending.isEmpty() && supported.isEmpty() && online.isEmpty() ->
                "UNKNOWN(told us nothing)"
            covers(installed, tag) -> "INSTALLED"
            covers(pending, tag) -> "DOWNLOADING"
            covers(supported, tag) -> "DOWNLOADABLE"
            covers(online, tag) -> "NETWORK_ONLY"
            else -> "UNSUPPORTED"
        }

        /** The app's own rule, copied deliberately rather than imported, so this measures behaviour. */
        private fun covers(list: List<String>, wanted: String): Boolean = list.any {
            val r = it.trim().replace('_', '-').lowercase()
            val w = wanted.trim().replace('_', '-').lowercase()
            r == w || (!r.contains('-') && r == w.substringBefore('-'))
        }
    }

    @Test
    fun whatEachEngineSaysAboutEachLanguage() {
        assumeTrue(
            "checkRecognitionSupport needs API 33",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        log("=".repeat(100))
        log("device sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} locale=${java.util.Locale.getDefault()}")
        log("onDeviceRecognitionAvailable=${SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}")
        log("recognitionAvailable=${SpeechRecognizer.isRecognitionAvailable(context)}")

        for (engineName in listOf("ON_DEVICE", "GENERAL")) {
            val engine = InstrumentationRegistry.getInstrumentation().runOnMainSync2 {
                runCatching {
                    when (engineName) {
                        "ON_DEVICE" ->
                            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
                                SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
                        else ->
                            if (SpeechRecognizer.isRecognitionAvailable(context))
                                SpeechRecognizer.createSpeechRecognizer(context) else null
                    }
                }.getOrNull()
            }
            if (engine == null) {
                log("--- $engineName: not available on this handset ---")
                continue
            }

            // 1. THE CALL THE APP MAKES TODAY: no EXTRA_LANGUAGE.
            val whole = ask(engine, null, context)
            log("--- $engineName / no EXTRA_LANGUAGE (what the app asks today) ---")
            log("    installed = ${whole.installed}")
            log("    pending   = ${whole.pending}")
            log("    supported = ${whole.supported}")
            log("    online    = ${whole.online}")
            if (whole.errorCode != null) log("    ERROR ${whole.errorCode}")
            if (whole.timedOut) log("    TIMED OUT")
            log("    => states the app would show:")
            tags.forEach { log("       ${it.padEnd(8)} ${whole.stateFor(it)}") }

            // 2. THE SAME QUESTION, ASKED PER LANGUAGE.
            log("--- $engineName / with EXTRA_LANGUAGE pinned, one call per language ---")
            tags.forEach { tag ->
                val a = ask(engine, tag, context)
                val note = when {
                    a.errorCode != null -> "ERROR(${a.errorCode})"
                    a.timedOut -> "TIMEOUT"
                    else -> "${a.stateFor(tag)}  inst=${a.installed} sup=${a.supported} on=${a.online}"
                }
                log("       ${tag.padEnd(8)} $note")
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.destroy() }
        }
        log("=".repeat(100))
    }

    private fun ask(engine: SpeechRecognizer, tag: String?, context: android.content.Context): Answer {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (tag != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
        }
        val latch = CountDownLatch(1)
        var result = Answer(timedOut = true)
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                result = Answer(
                    installed = support.installedOnDeviceLanguages.toList(),
                    pending = support.pendingOnDeviceLanguages.toList(),
                    supported = support.supportedOnDeviceLanguages.toList(),
                    online = support.onlineLanguages.toList(),
                )
                latch.countDown()
            }

            override fun onError(error: Int) {
                result = Answer(errorCode = error)
                latch.countDown()
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching {
                engine.checkRecognitionSupport(intent, Executors.newSingleThreadExecutor(), callback)
            }.onFailure {
                result = Answer(errorCode = -1)
                latch.countDown()
            }
        }
        latch.await(20, TimeUnit.SECONDS)
        return result
    }

    private fun log(line: String) = Log.i("DWPACKPROBE", line).let { }

    /** `runOnMainSync` with a return value — the recogniser must be built on the main looper. */
    private fun <T> android.app.Instrumentation.runOnMainSync2(block: () -> T): T {
        var out: T? = null
        runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }
}
