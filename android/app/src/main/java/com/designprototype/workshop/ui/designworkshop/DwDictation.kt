package com.designprototype.workshop.ui.designworkshop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * Dictation into any text field, with the words appearing as they are spoken.
 *
 * ── WHY THIS IS WORTH THE FILE ────────────────────────────────────────────────────────────────
 *
 * The registry's narrative fields are the ones the report is actually built from — the process
 * description, the officer's remarks, the design brief — and they are long. They are also written by
 * someone standing in a workshop with a phone in one hand, which is the single worst environment for
 * a soft keyboard: no surface, poor light, and a conversation happening that they are trying to
 * record rather than miss. A designer who has to type four hundred words on a phone writes forty,
 * and the report reads like it.
 *
 * ── LIVE PARTIALS, AND WHY THEY ARE NOT A FLOURISH ────────────────────────────────────────────
 *
 * `EXTRA_PARTIAL_RESULTS` streams the recogniser's running guess. Rendering it is what makes the
 * control trustworthy: without it the designer speaks a paragraph into a silent box and finds out
 * only at the end whether anything was heard. With it, a microphone that is not picking them up is
 * obvious within two words, while they still remember what they were going to say.
 *
 * ── THE BUTTON DOES NOT APPEAR ON A PHONE THAT CANNOT DICTATE ─────────────────────────────────
 *
 * [SpeechRecognizer.isRecognitionAvailable] is checked before the icon is composed at all. This
 * matters because a large share of the handsets this app runs on are budget Indian devices without
 * Google's speech service, and on those a mic button is not merely useless — it is a control that
 * looks like every other working control, and the designer who taps it and gets nothing concludes
 * the app is broken rather than that the phone lacks a recogniser. An absent button asks no
 * questions.
 *
 * ── OFFLINE ───────────────────────────────────────────────────────────────────────────────────
 *
 * Say it plainly, because this app's whole premise is a courtyard with no signal: on most devices
 * the default recogniser is Google's and it is a NETWORK service. With no connection it answers
 * ERROR_NETWORK almost immediately.
 *
 * Two things are done about that rather than none. First, on API 33 and above, where the platform
 * exposes [SpeechRecognizer.isOnDeviceRecognitionAvailable], the on-device recogniser is used from
 * the start whenever the device reports one — so a phone whose owner has downloaded the Hindi
 * offline pack dictates in a village with no signal at all. Second, when the network recogniser
 * fails with a network error and an on-device one exists, the attempt is retried against it once,
 * silently, so the designer never has to know which engine answered.
 *
 * ── THE FALLBACK RUNS BOTH WAYS, AND THE SECOND DIRECTION WAS MISSING ─────────────────────────
 *
 * Preferring the offline engine has a cost that was not handled: it reports
 * ERROR_LANGUAGE_UNAVAILABLE (13) for any language whose pack the owner has not downloaded, and
 * Hindi — the default here, and the language most of these workshops are run in — is not installed
 * by default on a great many handsets. Observed on a Galaxy M32 running Android 13: every tap of the
 * microphone produced "Dictation stopped unexpectedly (code 13). Type the answer in, or try again."
 *
 * That sentence was the generic arm, and its advice could not work: no number of further taps
 * downloads a language pack. So the offline engine's language failure now falls back to the NETWORK
 * engine exactly as the network engine's failure falls back to the offline one — same one-shot
 * `retried` guard, so the two cannot bounce a request between them — and only when both are
 * exhausted does the designer get a sentence, which then names the fix instead of suggesting a
 * retry.
 *
 * When neither is available the failure is reported as a SENTENCE naming the cause and the way
 * round it ("…dictation on this phone needs a connection; your keyboard's own microphone may have an
 * offline language pack"), because a designer who thinks the feature is broken stops using it
 * permanently, whereas one who knows it needs signal uses it in the evening at the guest house.
 */

/**
 * The languages offered, matching the list the web questionnaire form offers.
 *
 * SAME LANGUAGES, DIFFERENT SPELLING, and the difference is unavoidable rather than sloppy: the web
 * form stores a human-readable name ("Hindi") because that is what a transcription provider is told
 * and what a report prints, while [SpeechRecognizer] wants a BCP-47 tag. Pairing them here keeps one
 * list rather than two — a language that appears in the questionnaire dropdown and not in this one
 * is a language a designer can record an interview in but not dictate a note in, and nobody would
 * ever notice which.
 *
 * The entries with no standard tag are deliberately absent rather than guessed. "Other" cannot be a
 * locale, and Bodo, Dogri, Santali and Maithili have no recogniser on any shipping Android: offering
 * them would produce a dropdown row that always fails, which is worse than a shorter dropdown.
 * Manipuri is offered under `mni-IN` where the platform has begun to accept it and simply falls back
 * to the device default when it does not.
 */
@Immutable
data class DwDictationLanguage(val tag: String, val label: String)

internal val DW_DICTATION_LANGUAGES: List<DwDictationLanguage> = listOf(
    // Hindi first, matching the web form's default, because it is the language most of these
    // workshops are actually run in.
    DwDictationLanguage("hi-IN", "Hindi"),
    DwDictationLanguage("en-IN", "English (India)"),
    DwDictationLanguage("bn-IN", "Bengali"),
    DwDictationLanguage("mr-IN", "Marathi"),
    DwDictationLanguage("te-IN", "Telugu"),
    DwDictationLanguage("ta-IN", "Tamil"),
    DwDictationLanguage("gu-IN", "Gujarati"),
    DwDictationLanguage("ur-IN", "Urdu"),
    DwDictationLanguage("kn-IN", "Kannada"),
    DwDictationLanguage("or-IN", "Odia"),
    DwDictationLanguage("ml-IN", "Malayalam"),
    DwDictationLanguage("pa-IN", "Punjabi"),
    DwDictationLanguage("as-IN", "Assamese"),
    DwDictationLanguage("sa-IN", "Sanskrit"),
    DwDictationLanguage("kok-IN", "Konkani"),
    DwDictationLanguage("ne-IN", "Nepali"),
    DwDictationLanguage("mni-IN", "Manipuri (Meitei)"),
    DwDictationLanguage("ks-IN", "Kashmiri"),
    DwDictationLanguage("sd-IN", "Sindhi"),
)

/**
 * The language last chosen, for the life of the process.
 *
 * A designer runs a workshop in ONE language. Making them re-pick Hindi on every one of four hundred
 * and ninety-six fields would make the control slower than typing, which is the only way a dictation
 * feature can be worse than no dictation feature. Process-scoped rather than persisted because it is
 * a preference of the sitting, not of the account, and a phone handed to a colleague for the Odia
 * session should not silently keep answering in Hindi tomorrow.
 */
private var lastDictationTag: String = DW_DICTATION_LANGUAGES.first().tag

/** Whether this handset can dictate at all. Checked once; the platform answer does not change. */
@Composable
internal fun rememberDictationAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) }
}

/** What the recogniser is doing, for the caller that has to draw it. */
internal enum class DwDictationState { IDLE, LISTENING, WORKING }

/**
 * The microphone control for one field.
 *
 * [onCommit] receives the FINAL text and nothing else. [onPartial] receives the running guess so the
 * caller can render it inside the input the designer is watching — passing it up rather than drawing
 * it here is what lets the words appear in the box itself rather than in a separate strip below it,
 * and a transcript that appears somewhere other than where it will be saved is a transcript nobody
 * trusts.
 */
@Composable
internal fun DwDictationButton(
    enabled: Boolean,
    onPartial: (String) -> Unit,
    onCommit: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(DwDictationState.IDLE) }
    var showLanguages by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf(lastDictationTag) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    /** Set while a network failure is being retried on-device, so the retry cannot itself retry. */
    var retried by remember { mutableStateOf(false) }

    // rememberUpdatedState so the listener — which is created once per session and outlives several
    // recompositions — always calls the CURRENT lambdas. Capturing them directly would have a long
    // dictation writing its words into the composable's first-frame closure, which for a collection
    // row means writing into whichever row was open when the recogniser started.
    val currentPartial by rememberUpdatedState(onPartial)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentError by rememberUpdatedState(onError)

    fun release() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        state = DwDictationState.IDLE
    }

    /**
     * A recogniser bound to a fresh listener, or null when the platform refuses to make one.
     *
     * [onDevice] picks the offline engine. It is only ever true on API 33+, where
     * `isOnDeviceRecognitionAvailable` can be asked first — `createOnDeviceSpeechRecognizer` exists
     * from API 31, but with no way to ask whether a language pack is installed the call succeeds and
     * then fails at `startListening` with an error the designer cannot act on.
     */
    fun buildRecognizer(
        onDevice: Boolean,
        onNetworkFailure: () -> Unit,
        onLanguageUnavailable: () -> Unit,
    ): SpeechRecognizer? {
        val created = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull() ?: return null

        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { state = DwDictationState.LISTENING }
            override fun onBeginningOfSpeech() { state = DwDictationState.LISTENING }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { state = DwDictationState.WORKING }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) currentPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                // The partial is cleared FIRST and unconditionally. A final result that arrives empty
                // (which happens, on a clipped utterance) must not leave the last partial guess
                // painted over the box looking committed when nothing was saved.
                currentPartial("")
                if (text.isNotBlank()) currentCommit(text)
                release()
            }

            override fun onError(error: Int) {
                currentPartial("")
                // The three errors that mean something specific to the person holding the phone, each
                // answered in its own words. A single "dictation failed" for all of them is how a
                // designer with a muted microphone spends ten minutes speaking louder.
                val message = when (error) {
                    // Heard sound, matched nothing. Almost always background noise or a language
                    // mismatch — the designer set Hindi and spoke Marathi — so the language is named.
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Nothing was recognised. Check that the language above matches what you are " +
                            "speaking, and try again closer to the phone."
                    // Heard nothing at all for the platform's silence window.
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech was heard. Tap the microphone and begin speaking straight away — " +
                            "it stops listening after a few seconds of silence."
                    // The permission was revoked between the check and the call, or a device policy
                    // blocks the microphone. Never retried: retrying produces the same error forever.
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "This app does not have permission to use the microphone. Grant it under " +
                            "Settings › Apps › permissions, then tap the microphone again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        onNetworkFailure()
                        return
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "The phone's speech service is busy. Wait a moment and tap the microphone again."
                    SpeechRecognizer.ERROR_AUDIO ->
                        "The microphone could not be read. Something else on the phone may be using it."
                    /*
                      THE LANGUAGE THE DESIGNER CHOSE IS NOT ON THIS PHONE, and until this branch
                      existed that fell to the `else` below and told them to "try again" — advice that
                      cannot ever work, because nothing about tapping the microphone a second time
                      downloads a language pack. Observed on a Galaxy M32 running Android 13 with the
                      field set to Hindi: `code 13`, on every attempt, for ever.

                      12 is ERROR_LANGUAGE_NOT_SUPPORTED and 13 is ERROR_LANGUAGE_UNAVAILABLE, both
                      added in API 33. They are written as literals because this module is built
                      against minSdk 26 and the named constants would put an API-level guard around a
                      value that is only ever compared, never called.

                      UNAVAILABLE (13) IS THE RECOVERABLE ONE and it is handled by retrying rather
                      than by explaining: the OFFLINE engine reports it for a pack that is not
                      downloaded, while the NETWORK engine can usually serve the same language. So
                      this hands over to `onLanguageUnavailable`, which mirrors the existing
                      network -> offline fallback in the opposite direction. Only when that is
                      exhausted does the designer get a sentence, and then it names the fix.
                    */
                    13 -> {
                        onLanguageUnavailable()
                        return
                    }
                    12 ->
                        "This phone's speech recogniser does not support the language chosen above. " +
                            "Pick another language, or type the answer in."
                    else ->
                        "Dictation stopped unexpectedly (code $error). Type the answer in, or try again."
                }
                release()
                currentError(message)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        return created
    }

    fun intentFor(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        // Asked for explicitly as well as by EXTRA_LANGUAGE: some OEM recognisers ignore the former
        // unless the preference is also present, and silently answer in the device locale instead —
        // which turns a Tamil sentence into a page of phonetic English nobody can correct.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    fun listen(onDevice: Boolean) {
        release()
        val built = buildRecognizer(
            onDevice,
            onLanguageUnavailable = {
                /*
                  THE MIRROR OF THE NETWORK FALLBACK BELOW, and it is the half that was missing.

                  The offline engine says a language is unavailable when its pack is not downloaded;
                  the network engine usually serves that same language without one. So a designer who
                  has a connection gets their dictation instead of an error, and the retry is capped
                  by the SAME `retried` flag, so offline and language failures cannot bounce a request
                  between the two engines.

                  Where there is no network engine to fall back to — already on it, or the one retry
                  spent — the sentence names the actual fix rather than suggesting another tap. It is
                  the message that already existed for this case and, until the `13` branch above was
                  written, could not be reached by it.
                */
                if (!retried && onDevice) {
                    retried = true
                    listen(onDevice = false)
                } else {
                    release()
                    currentError(
                        "The language chosen above is not installed on this phone. Download it in the " +
                            "phone's speech or keyboard settings, choose another language, or type the " +
                            "answer in."
                    )
                }
            },
            onNetworkFailure = {
                // The network engine could not reach its server. If the phone has an offline engine,
                // go straight to it — once — rather than telling a designer in a courtyard that
                // dictation does not work when in fact it does.
                if (!retried && !onDevice && onDeviceAvailable()) {
                    retried = true
                    listen(onDevice = true)
                } else {
                    release()
                    currentError(
                        if (onDevice) {
                            "The offline recogniser could not handle this language. Download the " +
                                "language pack in the phone's speech settings, or type the answer in."
                        } else {
                            "Dictation on this phone needs a connection and there is none. Your " +
                                "keyboard's own microphone may have an offline language pack; " +
                                "otherwise type the answer in and dictate the rest later."
                        }
                    )
                }
            },
        )
        if (built == null) {
            currentError("This phone would not start its speech recogniser.")
            return
        }
        recognizer = built
        state = DwDictationState.WORKING
        runCatching { built.startListening(intentFor()) }.onFailure {
            release()
            currentError("This phone would not start its speech recogniser.")
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            retried = false
            listen(onDevice = onDeviceAvailable())
        } else {
            // Asked AT THE POINT OF USE and refused here means refused for dictation specifically,
            // which is a different thing from the launch-time batch being declined months ago on a
            // shared handset. Say what was refused and what it was for.
            currentError("Dictation needs the microphone. Nothing was recorded.")
        }
    }

    /**
     * A live recogniser is a held microphone. Compose disposes this button whenever the collection
     * row collapses or the stage scrolls it out of the tree, and a recogniser that survives that
     * keeps the hardware and goes on writing partial results into a lambda whose field is gone.
     */
    DisposableEffect(Unit) {
        onDispose {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            enabled = enabled,
            onClick = {
                when (state) {
                    // Stop means "I have finished the sentence": stopListening asks the engine to
                    // finalise what it already has, where cancel would throw it away. A designer who
                    // has just spoken forty words and taps the obvious button must not lose them.
                    DwDictationState.LISTENING, DwDictationState.WORKING ->
                        runCatching { recognizer?.stopListening() }
                    DwDictationState.IDLE -> {
                        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                            retried = false
                            listen(onDevice = onDeviceAvailable())
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
        ) {
            Icon(
                if (state == DwDictationState.IDLE) Icons.Filled.Mic else Icons.Filled.Stop,
                contentDescription = if (state == DwDictationState.IDLE) {
                    "Dictate this answer in ${DW_DICTATION_LANGUAGES.firstOrNull { it.tag == tag }?.label ?: tag}"
                } else {
                    "Stop dictating"
                },
                tint = if (state == DwDictationState.IDLE) {
                    MaterialTheme.field.muted
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(20.dp)
            )
        }
        if (state == DwDictationState.IDLE) {
            TextButton(
                onClick = { showLanguages = !showLanguages },
                enabled = enabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    DW_DICTATION_LANGUAGES.firstOrNull { it.tag == tag }?.label ?: tag,
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }

    if (showLanguages) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SearchableSelectField(
                label = "Dictation language",
                options = remember { DW_DICTATION_LANGUAGES.map { SelectOption(it.tag, it.label) } },
                selectedValue = tag,
                includeNone = false,
                onSelect = { chosen ->
                    if (chosen.isNotBlank()) {
                        tag = chosen
                        lastDictationTag = chosen
                    }
                    showLanguages = false
                }
            )
            Text(
                "The recogniser has to be told which language to expect. Choosing the wrong one " +
                    "does not fail — it transcribes the sounds into the wrong script, which is " +
                    "harder to spot afterwards than an empty box.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
    }
}

/** The strip under a field that is currently being dictated into. */
@Composable
internal fun DwDictationHint(listening: Boolean) {
    if (!listening) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Listening — speak now.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            "The words appear as you speak; tap the square to finish.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
    }
}

/**
 * Whether a field is one a person would ever dictate into.
 *
 * Narrow on purpose. A microphone next to a money box or a date box is noise: nobody dictates
 * "one thousand two hundred and fifty rupees fifty paise" into a costing sheet, and the recogniser
 * would return words where the coercion wants digits, so the button would appear to do nothing. It
 * belongs on prose, on names and on places — the fields that are long, or that are proper nouns a
 * soft keyboard fights.
 */
internal fun dictatable(type: com.designprototype.workshop.data.DwFieldType): Boolean =
    type == com.designprototype.workshop.data.DwFieldType.TEXT ||
        type == com.designprototype.workshop.data.DwFieldType.LONG_TEXT

internal fun dictationUnavailableNote(context: Context): String? =
    if (runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)) null
    else "This phone has no speech recogniser installed, so there is no dictation here."
