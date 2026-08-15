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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DW_TIER1_CATALOGUE
import com.designprototype.workshop.data.DW_DICTATION_BUSY
import com.designprototype.workshop.data.DW_DICTATION_CAP_SPENT_SAY_AGAIN
import com.designprototype.workshop.data.DW_DICTATION_CONSENT_REFUSED
import com.designprototype.workshop.data.DW_DICTATION_NOTHING_RECORDED
import com.designprototype.workshop.data.DW_DICTATION_NOT_CONFIGURED
import com.designprototype.workshop.data.DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN
import com.designprototype.workshop.data.DW_DICTATION_SERVER_UNREACHABLE
import com.designprototype.workshop.data.DW_DICTATION_UPLOAD_FAILED
import com.designprototype.workshop.data.DwDictateDto
import com.designprototype.workshop.data.DwDictationCapRefused
import com.designprototype.workshop.data.DwDictationConditions
import com.designprototype.workshop.data.DwDictationConsentRefused
import com.designprototype.workshop.data.DwDictationNotConfigured
import com.designprototype.workshop.data.DwDictationPlan
import com.designprototype.workshop.data.DwDictationRecorder
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwDictationRung
import com.designprototype.workshop.data.DwPackState
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwDictationLadder
import com.designprototype.workshop.data.dwDictationNothingLeftSentence
import com.designprototype.workshop.data.dwDictationOversize
import com.designprototype.workshop.data.dwDictationServerAnswerSentence
import com.designprototype.workshop.data.dwModelWaitSentence
import com.designprototype.workshop.data.dwMayAsk
import com.designprototype.workshop.data.dwPackOffer
import com.designprototype.workshop.data.dwPackState
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
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
 * THAT TEST IS NOW ONE OF TWO, and the reason is at [rememberDictationAvailable]: since rung 2 below
 * exists, a phone with no speech service can still dictate — through the server — so a missing
 * recogniser is no longer a missing feature. The principle is unchanged; what changed is what makes
 * the control capable of working.
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
 * downloads a language pack. So the offline engine's language failure falls through to another engine
 * exactly as the network engine's failure falls through to the offline one, and only when everything
 * is exhausted does the designer get a sentence, which then names the fix instead of suggesting a
 * retry. (That fall-through was a pair of engines and a one-shot `retried` flag when it was written;
 * it is now an ordered plan of three rungs — see the last section of this header — in which each
 * engine appears exactly once, so a bounce between two of them is no longer expressible.)
 *
 * When neither is available the failure is reported as a SENTENCE naming the cause and the way
 * round it ("…dictation on this phone needs a connection; your keyboard's own microphone may have an
 * offline language pack"), because a designer who thinks the feature is broken stops using it
 * permanently, whereas one who knows it needs signal uses it in the evening at the guest house.
 *
 * ── AND NOW THE APP OFFERS TO FIX IT RATHER THAN ONLY EXPLAINING IT ───────────────────────────
 *
 * All of the above is still the floor, and it is still reached on Android 12 and below. But the
 * fallback needs a connection, and Odia — the language of the state these workshops are run in — is
 * no likelier to be installed than Hindi was. From API 33 the platform will say which of the
 * nineteen packs are present WITHOUT being made to fail first
 * (`SpeechRecognizer.checkRecognitionSupport`) and will accept a request to fetch one
 * (`triggerModelDownload`). `DwLanguagePackUi.kt` does both; picking a language whose pack is
 * missing now opens an offer instead of waiting to produce a code 13 in a courtyard.
 *
 * ── AND THE FALLBACK IS NO LONGER GOOGLE'S GENERIC MODEL ──────────────────────────────────────
 *
 * Everything above describes two engines, both of them Google's. That was the whole ladder, and it
 * had a hole in it that only shows up when you compare surfaces: the web, on a browser with no
 * `SpeechRecognition`, records a clip and posts it to `POST /design-workshops/{id}/dictate`, which runs
 * it through the provider chain that boosts a CRAFT KEYTERM LIST — the reason it writes "dabu", a
 * mud-resist printing technique, rather than "double". Google's generic network recogniser has none
 * of that vocabulary. Measured (docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md): its on-device
 * catalogue on the fleet's M32 holds two of our nineteen languages, so for the other seventeen —
 * Odia among them — the same audio produced a craft-aware transcript on the web and a generic one on
 * the phone.
 *
 * So the ladder this control now walks has four rungs, and the decision about their order is in
 * `data/DwDictationLadder.kt` where a desktop JVM can test it:
 *
 * ```
 * 1. Installed on-device pack   free, instant, streaming, works with no signal.   STILL FIRST.
 * 2. Server dictation (online)  the web's own path: craft keyterms.               NEW.
 * 3. Google network recogniser  last resort.                                      DEMOTED.
 * 4. Honest failure             the sentence, said only once nothing is left.
 * ```
 *
 * Rung 1 stays first because it is free and works in a courtyard; spending provider credit per
 * sentence on a phone that can already do the job would be a bill for nothing. What changed is what
 * happens when it CANNOT: the craft-aware server now comes before the generic engine.
 *
 * ── RUNG 2 LOOKS DIFFERENT, AND SAYS SO ───────────────────────────────────────────────────────
 *
 * There are no partial results on rung 2 — the clip is transcribed after it is sent, so the words
 * arrive on Stop rather than as they are spoken. A designer who has used this control on a phone with
 * the pack installed will otherwise watch an empty box and conclude the microphone is dead. The web
 * says the same thing in the same situation ("This browser has no built-in dictation, so the
 * recording is transcribed after you press Stop"); this control says it too, and adds WHY it is on
 * rung 2, because "the pack for this language is not on the phone" is a fact the designer can act on.
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

/**
 * Whether this handset can dictate at all — the question that decides whether a microphone is drawn.
 *
 * IT IS NO LONGER ONLY ABOUT `SpeechRecognizer`, and the widening is deliberate. The header above
 * argues at length that a mic button on a phone with no recogniser is worse than no button, because a
 * control that looks like every other working control and does nothing teaches its owner that the app
 * is broken. That argument holds only while a missing recogniser means missing dictation — and since
 * rung 2, it does not. An Android build with no Google speech services is one of the exact cases the
 * server route was written for (see the route's own docstring), and hiding the microphone on those
 * handsets would withhold the one path that works on them.
 *
 * So the button appears when EITHER the platform has a recogniser OR this app has a repository to
 * post a clip through. The second half is not a promise of a connection: with no signal the tap
 * produces the honest sentence from the ladder rather than silence, which is rung 4 doing its job.
 *
 * Checked once per composition: neither answer changes while a field is on screen.
 */
@Composable
internal fun rememberDictationAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) ||
            DwDictationRun.repository() != null
    }
}

/** What the control is doing, for the caller that has to draw it. */
internal enum class DwDictationState {
    IDLE,

    /** Rung 1 or 3: the platform's recogniser is listening and streaming partial results. */
    LISTENING,

    /** Rung 1 or 3: speech has ended and the engine is deciding on the final text. */
    WORKING,

    /**
     * Rung 2: the microphone is open and audio is going to a file.
     *
     * A SEPARATE STATE FROM [LISTENING] because it is a visibly different promise — nothing appears
     * in the box while it runs. Folding the two together would leave a designer watching an empty
     * field under a control that says "Listening" and waiting for words that cannot arrive until Stop.
     */
    RECORDING,

    /** Rung 2: the clip is on its way to the server, or the server is transcribing it. */
    UPLOADING,

    /**
     * Rung 1b: the microphone is open and audio is going into memory for THIS PHONE to transcribe.
     *
     * A separate state from [RECORDING] although both promise "nothing appears until Stop", because
     * what the designer must be told next differs: rung 2's panel says the clip needs a connection
     * and stops itself after four minutes, and this one says the opposite — nothing leaves the phone,
     * and it stops itself after ninety seconds because THIS PHONE has to do the decoding and is
     * slower than real time at it.
     */
    RECORDING_FOR_THIS_PHONE,

    /**
     * Rung 1b: the microphone is shut and this phone's own model is decoding. **The long wait.**
     *
     * Not [UPLOADING], which means "the clip is on its way to the server" and is the one claim this
     * rung must never make — nothing leaves the device. It is its own state because it is the only
     * state in this app that can last minutes with nothing to show: a CTC model produces no partial
     * transcript, so there is nothing to render and nothing honest to put in a progress bar.
     */
    TRANSCRIBING_ON_THIS_PHONE,
}

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
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(DwDictationState.IDLE) }
    var showLanguages by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf(lastDictationTag) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    /**
     * The plan this tap is walking.
     *
     * WHICH rung it is on is NOT kept beside it, deliberately: each rung's failure handler already
     * knows which rung it is (`here`), and a second copy in state would be one more thing that has to
     * be right for the walk to be right. Nothing else about the walk is kept beside it: the panel a
     * designer looks at is gated on [DwDictationState], not on a second copy of the plan.
     *
     * Computed ONCE per tap, from [dwDictationLadder], and then walked. Recomputing it after every
     * failure would let a rung that has just failed be chosen again — the same bouncing the `retried`
     * flag used to prevent between two engines, except with three of them and no natural cap.
     */
    var plan by remember { mutableStateOf<DwDictationPlan?>(null) }

    /** Rung 2's microphone. One per control, released with the composition. */
    val recorder = remember { DwDictationRecorder() }

    /**
     * Rung 1b's microphone — 16 kHz PCM into memory, for this phone's own model. See
     * [DwAsrPcmRecorder] for why it is not the one above.
     */
    val pcm = remember { DwAsrPcmRecorder() }

    /** Rung 1b's capture-and-decode, kept so leaving the field cancels it rather than orphaning it. */
    var localJob by remember { mutableStateOf<Job?>(null) }

    /**
     * The measured WAIT this phone's own model is about to cost, in words, shown before it starts.
     *
     * Built from the real-time-factor band's slowest reading, because a designer who taps dictate on a
     * ninety-second passage and is given no idea that it will be four minutes before anything appears
     * will decide the app has hung, and they will be right to.
     *
     * **NULL IN TWO CASES, AND IT NO LONGER GATES THE PANEL.** Off rung 1b entirely, and on rung 1b for
     * a model whose real-time factor nobody has measured — there is no honest sentence for that and so
     * there is no sentence. `startOnDeviceModel` carries the argument.
     */
    var localNote by remember { mutableStateOf<String?>(null) }

    /*
     * THE 365 MB HASH, STARTED ONCE PER PROCESS AND NEVER ON A TAP.
     *
     * `conditionsNow()` needs to know whether a VERIFIED model is on this phone, and "verified" means
     * hashed in this run — 365 MB of it. A stage screen composes hundreds of these buttons, so the
     * reading is taken once, in the background, by an object that guards itself, and every caller
     * after the first reads a field. Until it lands the answer is "not yet", the rung is not offered,
     * and the ladder is exactly the one this phone has today — which is the fail-closed direction
     * `DwDictationConditions.appModelServesLanguage` asks for in its own docstring.
     */
    LaunchedEffect(Unit) { DwAsrModelRun.warm(context, scope) }

    /** The upload in flight, kept so leaving the field can cancel it rather than orphan it. */
    var uploadJob by remember { mutableStateOf<Job?>(null) }

    /*
     * `serverNote` USED TO LIVE HERE and is gone with the paragraph it carried. It held
     * `dwDictationServerNote(...)` — why this dictation had landed on rung 2 — and the panel printed
     * it. Which of four engines answered is not a designer's decision and not their problem; the one
     * thing that IS different about this rung (the words do not stream) is now stated by the panel
     * itself, in the web's own words, and the panel is gated on the STATE, which is the fact it was
     * really keyed to all along.
     */

    /**
     * THE WORKSHOP THIS RECORDING WILL BE SENT UNDER, captured when rung 2's recorder opens. Null off
     * rung 2.
     *
     * ── WHY IT IS CAPTURED AND NOT READ AGAIN AT STOP ──────────────────────────────────────────
     *
     * Because the two moments can be four minutes apart ([DW_DICTATION_MAX_MILLIS]), and what is being
     * sent is a named artisan's voice under a named workshop's recorded consent. The ambient describes
     * the workshop on screen NOW; if that changed while the microphone was open, reading it at Stop
     * would post THIS clip under THAT workshop's id — a recording sent under a consent nobody gave for
     * it, and checked by the server against the wrong column. Capturing at the start means the clip is
     * sent under the workshop whose GRANTED answer authorised the rung in the first place, or not at
     * all.
     *
     * A cross-workshop republication is not reachable in the shapes this app has today — `StageScreen`
     * records the same thing about its own token guard — so this is a guard against the shape that
     * arrives the day something navigates straight from one workshop's stage to another's, which is
     * precisely the day nobody would be looking at this file.
     */
    var sendingUnder by remember { mutableStateOf<String?>(null) }

    /**
     * The language just chosen whose pack is NOT on this phone, or null when there is nothing to
     * offer. Drives [DwLanguagePackOfferDialog].
     */
    var packOffer by remember { mutableStateOf<String?>(null) }

    /**
     * WHAT THIS PHONE CAN ACTUALLY DICTATE WITHOUT SIGNAL, asked instead of discovered by failing.
     *
     * `active` is what keeps this cheap. A stage screen composes one of these buttons per prose
     * field — hundreds across a workshop — and binding a `SpeechRecognizer` in each would be an IPC
     * handshake per field. It goes live only while the designer has the language list open or the
     * offer in front of them, which is also exactly when the answer is wanted: by the time they tap
     * a language, the check started when they opened the list has usually landed.
     */
    val packs = rememberDwLanguagePacks(active = showLanguages || packOffer != null)

    // rememberUpdatedState so the listener — which is created once per session and outlives several
    // recompositions — always calls the CURRENT lambdas. Capturing them directly would have a long
    // dictation writing its words into the composable's first-frame closure, which for a collection
    // row means writing into whichever row was open when the recogniser started.
    val currentPartial by rememberUpdatedState(onPartial)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentError by rememberUpdatedState(onError)

    /**
     * Put every engine down and go back to IDLE.
     *
     * ALL THREE RUNGS, not just the recogniser: rung 2 holds the microphone through a
     * `MediaRecorder` and a socket through a coroutine, and a `release()` that only knew about
     * `SpeechRecognizer` would leave a recording running under an idle-looking button. The recorder's
     * own `discard` deletes the file it was writing, which is the exit path this control must never
     * skip — see [DwDictationRecorder.discard].
     */
    fun release() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        uploadJob?.cancel()
        uploadJob = null
        recorder.discard()
        /*
         * RUNG 1b GOES DOWN HERE TOO, AND THE ORDER MATTERS. `pcm.stop()` closes the microphone;
         * cancelling the job unwinds the coroutine that is either reading from it or blocked inside a
         * native decode. `DwAsrSpeechModel.release()` is NOT called from here: the decode holds the
         * object monitor for its whole duration, so releasing from the main thread would block
         * composition for up to several minutes. The decode releases the recogniser itself on every
         * exit path — success, refusal and cancellation alike — which is where that belongs.
         */
        pcm.stop()
        localJob?.cancel()
        localJob = null
        localNote = null
        // Rung 2's workshop has no meaning off rung 2, and a stale id left lying here is the wrong-
        // workshop send [sendingUnder] exists to prevent, arriving from the other direction.
        sendingUnder = null
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

                      UNAVAILABLE (13) IS THE RECOVERABLE ONE and it is handled by moving on rather
                      than by explaining: the OFFLINE engine reports it for a pack that is not
                      downloaded, while the SERVER — and, failing that, the network engine — can serve
                      the same language. So this hands over to `onLanguageUnavailable`, which steps to
                      the next rung of the ladder. Only when the ladder is exhausted does the designer
                      get a sentence, and then it names the fix.

                      12 NOW DOES THE SAME, and that is a change. It used to end the attempt with
                      "pick another language, or type the answer in" — which was true of the ENGINE
                      that answered and false of the app, because rung 2 transcribes languages no
                      Android recogniser offers at all. Seventeen of our nineteen have no on-device
                      pack on the fleet's handset (docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md: the
                      catalogue holds thirty languages and exactly two of ours, `hi-IN` and `en-IN`),
                      so treating one engine's "no" as the app's "no" retired a control that works.
                      How many of the seventeen the ONLINE engine refuses is unmeasured — that list
                      came back empty from an on-device recogniser, which returns one by
                      construction — and unmeasured is exactly why a 12 must step to the next rung
                      rather than end the attempt.
                    */
                    13, 12 -> {
                        onLanguageUnavailable()
                        return
                    }
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

    fun labelOf(chosen: String): String =
        DW_DICTATION_LANGUAGES.firstOrNull { it.tag == chosen }?.label ?: chosen

    /**
     * Everything [dwDictationLadder] is allowed to know, read FRESH at the moment it is asked.
     *
     * Not remembered, because every one of these can change between two taps on the same field: a
     * designer walks out of the courtyard and the connection appears, a pack finishes downloading, the
     * offline engine refuses a language and that refusal is now a measured fact. A cached copy would
     * decide this tap from the state of the last one.
     *
     * `packs.support` is usually null here, and that is expected rather than a gap: the pack check
     * binds a `SpeechRecognizer`, so it runs only while the designer has the language list or the
     * offer open (see [rememberDwLanguagePacks]) — asking per field would be an IPC handshake per
     * field on a screen with hundreds. Null resolves to [DwPackState.UNKNOWN], the ladder starts at
     * the free engine exactly as it does today, and a code 13 turns the unknown into a measurement
     * that [DwDictationRun] then remembers for the rest of the run.
     *
     * A missing repository counts as "the route is unavailable" rather than as a crash: it is the
     * same fact from this control's point of view — there is no way to reach `/dictate` — and the
     * ladder's own sentences already say what that means.
     *
     * ── THE TWO FACTS PLAN §6 ADDED, AND WHY THEY ARE READ HERE RATHER THAN AT `beginWalk` ────────
     *
     * The workshop's consent and this designer's daily allowance are read in THIS function, on every
     * call, for the reason the paragraph above gives about the others — and one more.
     * [dwDictationNothingLeftSentence] is public because it is asked at TWO moments, and the second is
     * at the end of a walk, from fresh conditions (`advance`, below). A walk that started with an
     * allowance and ended after the server refused it would otherwise print the sentence that was true
     * when the microphone opened, which is the old false "the server has no transcription service
     * configured".
     *
     * NEITHER COSTS IO WORTH MEASURING, which is the constraint this function is under (a stage screen
     * composes one of these per prose field). The consent is a field on [DwDictationRun], published
     * once by the stage screen that had already read the draft for its own reasons — loading a draft
     * here would be a whole-22-stage JSON parse per field under the store-wide mutex every auto-save
     * also takes. The allowance is a SharedPreferences read, which is a map lookup once the file is
     * open, and the same store [TokenStore] answers from in place.
     */
    fun conditionsNow(): DwDictationConditions {
        val cap = DwDictationRun.capView(context)
        // ONE READ FOR BOTH HALVES. The consent and the workshop id are published together and read
        // together; two reads could pair one workshop's answer with another workshop's id, and the
        // ladder would then authorise a send under a consent that was never given for it. See
        // [DwDictationRun.Published].
        val workshop = DwDictationRun.publishedWorkshop()
        return DwDictationConditions(
            languageLabel = labelOf(tag),
            packState = dwPackState(tag, packs.support),
            onDeviceEngine = onDeviceAvailable(),
            networkRecogniser = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
                .getOrDefault(false),
            online = ConnectivityObserver.isOnline(context),
            serverRouteUnavailable = DwDictationRun.serverRouteUnavailable ||
                DwDictationRun.repository() == null,
            deviceRefusedLanguage = DwDictationRun.engineHasRefused(tag),
            /*
             * THIS APP'S OWN MODEL, AND BOTH HALVES ARE ANDed INSIDE [DwAsrModelRun.servesLanguage]:
             * a model INSTALLED AND VERIFIED IN THIS RUN, whose own measured language list covers this
             * tag. Either half alone would offer a rung that cannot answer.
             *
             * IT IS A CACHED READING AND NOT A FRESH ONE, which is the single departure from this
             * function's "nothing is remembered" rule, and it is forced rather than chosen: the
             * reading is a SHA-256 over 365 MB and this function runs on every tap on every one of
             * hundreds of fields. What makes it safe is that the thing it caches cannot change while
             * the process lives — see [DwAsrModelRun], where that argument is made in full.
             */
            appModelServesLanguage = DwAsrModelRun.servesLanguage(tag),
            // The counterpart of `deviceRefusedLanguage` for our own engine: a model the catalogue
            // says serves this tag and which then refused it HERE is a measurement that outranks the
            // catalogue, and it is remembered so the rest of the stage does not pay for it again.
            appModelRefusedLanguage = DwAsrModelRun.hasRefused(tag),
            // NOT_RECORDED unless a stage screen has published otherwise, which is the fail-closed
            // answer and also the true one: a microphone drawn on a surface with no workshop behind it
            // has no artisan's consent behind it either.
            tier3Consent = workshop.consent,
            dailyCapSpent = cap.spent,
            dailyCapLimit = cap.limit,
            // AND WITHOUT A SERVER ID THERE IS NOWHERE TO SEND A CLIP THAT WOULD CHECK ANY OF THIS.
            // Rung 2 posts to `POST /design-workshops/{id}/dictate`, the only dictation route that
            // reads a workshop's consent column; the one that takes no id reads nothing, which is why
            // this app stopped using it. Null here — no workshop on screen, or one that has never been
            // sent up — therefore closes the rung rather than falling back to the ungated door.
            workshopOnServer = workshop.serverId != null,
        )
    }

    /*
      THE TWO HALVES OF THE WALK MEET HERE.

      Starting a rung installs callbacks that, on failure, ask for the NEXT rung — which starts it.
      Kotlin has no forward declaration for local functions, so `advance` reaches `beginAt` through
      this variable, assigned once every rung's starter exists below. Every piece of state either half
      touches is a remembered `MutableState`, so a listener that captured an earlier composition's
      copy of this closure still reads and writes the values on screen now — the same reasoning as the
      `rememberUpdatedState` above.

      THE `retried` FLAG IS GONE and the plan replaces it. It existed to stop two engines bouncing one
      request between them; a plan is an ordered list in which each rung appears exactly once, so the
      bounce is not expressible.
    */
    var beginAt: (DwDictationRung) -> Unit = {}

    /**
     * This rung could not do it. Step to the next one, or say why there is no next one.
     *
     * The handover is SILENT on purpose: a designer does not care which of three engines answered,
     * only that the words arrived. What is not silent is rung 2 looking different when it starts —
     * see [DwServerDictationPanel] — because that changes what they should expect to see.
     *
     * ONLY CALLED WHERE THE DESIGNER HAS NOT YET SPOKEN, and that is a precondition rather than a
     * coincidence. Rungs 1 and 3 refuse a language within a moment of `startListening`, so nothing
     * has been said and nothing is lost. Rung 2 fails only at the end of an utterance, and a silent
     * step from there re-opens the microphone at somebody who has finished speaking — so its failures
     * report instead, at the bottom of `finishRecording`. See [DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN].
     */
    fun advance(from: DwDictationRung) {
        val next = plan?.after(from)
        if (next != null) {
            beginAt(next)
            return
        }
        // Composed from the CURRENT conditions rather than from the plan's own stale copy: by now the
        // server may have said it is not configured and the engine may have refused the language, and
        // those are the two facts that decide which sentence is true.
        val sentence = dwDictationNothingLeftSentence(conditionsNow())
        release()
        currentError(sentence)
    }

    fun listen(onDevice: Boolean) {
        val here = if (onDevice) DwDictationRung.ON_DEVICE_PACK else DwDictationRung.NETWORK_RECOGNISER
        release()
        val built = buildRecognizer(
            onDevice,
            onLanguageUnavailable = {
                /*
                  THE LANGUAGE IS NOT ON THIS ENGINE. On the offline engine that is a MEASUREMENT of
                  this handset — worth more than any list, because the engine that owns the packs has
                  just said it cannot serve this one — so it is remembered for the run and every
                  remaining field on the stage skips rung 1 instead of paying for the same refusal
                  again.

                  Not remembered for the network engine: a "no" from Google's server says nothing
                  about the pack on the phone, and writing it down under the same key would retire
                  rung 1 for a language it may well still have.
                */
                if (onDevice) DwDictationRun.engineRefused(tag)
                advance(here)
            },
            onNetworkFailure = {
                // Whichever engine this was, it could not reach what it needed. The next rung may be
                // the server (which needs the same connection and may still be reachable when the
                // speech service's own endpoint is blocked by a proxy — a real case on cluster
                // wifi), or nothing at all, in which case `advance` says so honestly.
                advance(here)
            },
        )
        if (built == null) {
            release()
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

    /**
     * Stop rung 2's recorder and send what was captured. Also the limit callback's landing point.
     *
     * Guarded on [DwDictationState.RECORDING] because it has two callers that can both fire: the
     * designer's tap on Stop, and the recorder reaching its own duration or size cap
     * ([DW_DICTATION_MAX_MILLIS]). The second is not a failure — what was captured is good and gets
     * sent — but arriving twice would post the same clip twice and spend the credit twice.
     */
    fun finishRecording() {
        if (state != DwDictationState.RECORDING) return
        val clip = recorder.stop()
        if (clip == null) {
            release()
            currentError(DW_DICTATION_NOTHING_RECORDED)
            return
        }
        // THE CAP IS CHECKED BEFORE THE UPLOAD, NOT BY BEING REFUSED AFTER IT. The connection is the
        // scarce resource in a district town; spending six megabytes of it to be told six megabytes
        // is too many is the one failure this check exists to prevent. See [DW_DICTATION_MAX_BYTES].
        dwDictationOversize(clip.length())?.let { refusal ->
            runCatching { clip.delete() }
            release()
            currentError(refusal)
            return
        }
        val repository = DwDictationRun.repository()
        // THE WORKSHOP CAPTURED WHEN THE RECORDER OPENED, and there is no upload without it: the id is a
        // required argument of `designWorkshopDictate` precisely so that there is no way to send a clip
        // to a URL that consults no consent. `startRecording` refuses the rung when it is missing, so
        // neither of these can be null here — they are answered together, and answered the way the
        // repository-less case was already answered, because the one thing that may not happen is the
        // send.
        val sendUnder = sendingUnder
        if (repository == null || sendUnder == null) {
            runCatching { clip.delete() }
            advance(DwDictationRung.SERVER_DICTATE)
            return
        }
        state = DwDictationState.UPLOADING
        uploadJob = scope.launch {
            var answer: DwDictateDto? = null
            var failure: Throwable? = null
            try {
                answer = repository.designWorkshopDictate(sendUnder, clip, tag)
            } catch (e: CancellationException) {
                // The field left the tree mid-upload. Delete the recording and take the cancellation
                // with us — a composable that has gone must not write into a lambda that has gone.
                runCatching { clip.delete() }
                throw e
            } catch (e: Throwable) {
                failure = e
            }
            // THE CLIP IS DELETED ON EVERY PATH, including this one. Audio of a named artisan has no
            // business outliving the sentence it became; the endpoint stores nothing either.
            runCatching { clip.delete() }
            // Cleared before anything below can call `release`, so a `release` triggered from inside
            // this coroutine cannot cancel the coroutine it is running in.
            uploadJob = null

            val dto = answer
            if (dto != null) {
                // THE ALLOWANCE IS WRITTEN DOWN FROM A DICTATION THAT WORKED, which is the whole
                // reason the server puts it on the 200. Learned here, the FIRST field of tomorrow that
                // would exceed it is refused before the microphone opens, with the number named —
                // instead of every field on the stage buying the same refusal with six megabytes. A
                // response from a deployment that predates the cap carries no day and changes nothing.
                DwDictationRun.learnAllowance(context, dto)
                val text = dto.text.trim()
                if (text.isNotEmpty()) {
                    currentPartial("")
                    release()
                    currentCommit(text)
                } else {
                    // A round trip that worked and found no words is a different next move from a
                    // round trip that did not happen — the microphone and the room, not the signal.
                    // WHICH sentence is [dwDictationServerAnswerSentence]'s decision and not a `when`
                    // written out here, because the rule it holds is one this file had already got
                    // wrong: the chain's "will retry automatically" arrives on a FAILED status as
                    // well as on a throttled one, and passing it through promises a designer a
                    // transcript that nothing on either side is going to produce. A pure function is
                    // what lets a desktop JVM refuse that.
                    release()
                    currentError(dwDictationServerAnswerSentence(dto.status, dto.message))
                }
                return@launch
            }

            // THE ROUTE'S OWN 503 — told apart from the gateway's by its body rather than by its
            // code; see [DwDictationNotConfigured]. Only this one is a property of the deployment,
            // and so only this one is remembered, once, so that no other field on this stage spends
            // an upload discovering it again.
            if (failure is DwDictationNotConfigured) {
                DwDictationRun.serverSaidNotConfigured()
                // AND THE WALK STOPS HERE EVEN WITH A RUNG LEFT. This one failed AFTER the designer
                // spoke: silently starting Google's engine now would re-open the microphone at
                // somebody who has already said their sentence and pressed Stop, and hand them "No
                // speech was heard" a few seconds later with no account of where their words went.
                // The 503 is remembered, so their next tap starts on that rung deliberately — see
                // [DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN].
                val next = plan?.after(DwDictationRung.SERVER_DICTATE)
                release()
                currentError(
                    if (next != null) DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN else DW_DICTATION_NOT_CONFIGURED
                )
                return@launch
            }
            /*
              THE CONSENT GATE REFUSED IT — THE 409, AND THE SENTENCE IS THE SERVER'S.

              THIS IS THE GATE WORKING, NOT A FAILURE OF THE LADDER. The ladder read this phone's mirror
              of the answer and the server read its own column; the two disagree, which happens in two
              ordinary field states (a consent recorded in a courtyard and not yet pushed up, and one
              withdrawn in a browser since this handset synced). The server's column is the one that
              decides, and closing the ungated door is what put it in a position to.

              THE DETAIL IS SHOWN VERBATIM because the server wrote two sentences for this — one for
              "nobody has asked the artisan", one for "the artisan said no" — and this client cannot tell
              which state it found. Composing our own would mean guessing, and sending a designer to ask a
              question that is already on record is how they learn to stop reading these messages.

              AND NO "SAY IT AGAIN", WHICH IS THE DIFFERENCE FROM THE TWO BRANCHES AROUND IT. Those two
              are remembered, so their promise that the next tap goes to this phone's own recogniser is
              true. This one is not remembered ([DwDictationConsentRefused] argues why: the only honest
              thing to remember is "the two answers differ", which is not a state this ladder has a
              sentence for), so the next tap may well reach rung 2 again — and a promise about which
              engine answers next would be a false one. The walk stops either way: rung 2 failed at the
              END of an utterance, and silently opening Google's engine would re-open the microphone at
              somebody who has finished speaking. What the server's sentence names instead is a person
              deciding, which is the only thing that changes a consent.
            */
            if (failure is DwDictationConsentRefused) {
                release()
                currentError(failure.detail ?: DW_DICTATION_CONSENT_REFUSED)
                return@launch
            }
            /*
              THE DAILY ALLOWANCE, REFUSED AFTER THE DESIGNER HAD ALREADY SPOKEN.

              REMEMBERED, unlike every other failure here except the route's own 503, and for the same
              reason: it is a fact about the rest of the day rather than about this clip, so forgetting
              it means the next several hundred prose fields each spend a six-megabyte upload to be told
              it again — the failure `DwDictationUpload.kt` names in as many words. The memory is
              persisted and day-stamped rather than process-scoped, because this one EXPIRES: see
              [DwDictationRun.learnCapRefusal].

              AND NOT REMEMBERED WHEN IT WAS THE COURTESY RATE LIMITER, which answers the same code and
              means "this instant" rather than "today". Writing that down would retire the craft-aware
              rung for the rest of the day over a burst of taps, which is the CloudFront-blip failure the
              503 branch above exists to prevent. [DwDictationCapRefused] tells the two apart by the
              body's own keys, and the limiter's sentence — "wait a moment and try again" — is exactly
              the right next move, so it is shown verbatim where there is one.

              THE WALK STOPS EITHER WAY. Rung 2 failed at the END of an utterance, and silently opening
              Google's engine now would re-open the microphone at somebody who has finished speaking.
              See [DW_DICTATION_CAP_SPENT_SAY_AGAIN].
            */
            if (failure is DwDictationCapRefused) {
                if (failure.transientThrottle) {
                    release()
                    currentError(failure.detail ?: DW_DICTATION_BUSY)
                    return@launch
                }
                DwDictationRun.learnCapRefusal(context, failure)
                val next = plan?.after(DwDictationRung.SERVER_DICTATE)
                // With nothing left, the server's own sentence is the better one: it names the number,
                // which this phone may not invent. With no sentence in the body, the ladder's own
                // exhausted sentence is composed from conditions that now include the refusal we have
                // just written down — the same words the designer's next tap would produce.
                val nothingLeft = failure.detail ?: dwDictationNothingLeftSentence(conditionsNow())
                release()
                currentError(if (next != null) DW_DICTATION_CAP_SPENT_SAY_AGAIN else nothingLeft)
                return@launch
            }
            val http = failure as? HttpException
            release()
            currentError(
                when {
                    // Not an HTTP answer at all: no route to the host, a timeout, a dropped socket.
                    http == null -> DW_DICTATION_UPLOAD_FAILED
                    // A 503 that got this far is one the branch above found no FastAPI `detail` in,
                    // which makes it the gateway's rather than the route's: "not now", not "not
                    // configured", and nothing to ask an administrator for. Its body has already been
                    // read once by that check, so `apiErrorMessage` could only answer with the
                    // status line itself — a code, which is what this repository refuses to show.
                    http.code() == 503 -> DW_DICTATION_SERVER_UNREACHABLE
                    // Every other HTTP answer has the server's own sentence in it — the 413 names the
                    // cap and what to do instead — and those are written for the person holding the
                    // phone.
                    else -> http.apiErrorMessage(DW_DICTATION_UPLOAD_FAILED)
                }
            )
        }
    }

    /**
     * Rung 2: open the microphone into a file, and say why the words will not stream.
     *
     * The note goes up BEFORE the recording starts. A designer who taps and sees nothing happen for
     * three seconds has already decided the control is broken.
     */
    fun startRecording() {
        // RELEASED FIRST, exactly as `listen` does, and for a reason that only bites on this rung:
        // arriving here from a code 13 means a `SpeechRecognizer` is still bound from the rung that
        // just failed, and two objects holding the microphone at once is how a MediaRecorder fails to
        // start on the handsets least able to spare the audio path.
        release()
        /*
          THE WORKSHOP IS SETTLED BEFORE THE MICROPHONE OPENS, AND A MISSING ONE ENDS THE RUNG HERE.

          The ladder has already refused rung 2 for a workshop the server does not know
          ([DwDictationConditions.workshopOnServer]), so reaching this line with no id means the screen
          changed between the tap and this instant. Stepping to the next rung is the right answer AND is
          silent, which it could not be a moment later: nobody has spoken yet, so nothing is lost — the
          precondition `advance` documents. The same treatment, and the same reasoning, as a control that
          finds itself with no repository.

          WHAT MUST NEVER HAPPEN IS THE OTHER READING: recording anyway and deciding at Stop, which is
          how a clip would end up with nowhere gated to go and the ungated door one line away.
        */
        val workshop = DwDictationRun.publishedWorkshop().serverId
        if (workshop == null) {
            advance(DwDictationRung.SERVER_DICTATE)
            return
        }
        sendingUnder = workshop
        // The limit callback arrives on the thread that built the recorder, which is composition's
        // main thread, so it can touch the same state a tap does.
        val refusal = recorder.start(context) { finishRecording() }
        if (refusal != null) {
            release()
            currentError(refusal)
            return
        }
        state = DwDictationState.RECORDING
    }

    /**
     * **RUNG 1b: RECORD INTO MEMORY, THEN LET THIS PHONE'S OWN MODEL WRITE IT DOWN.**
     *
     * ── WHAT IT SENDS ANYWHERE: NOTHING ───────────────────────────────────────────────────────
     *
     * No repository, no upload, no file, no consent check, and nothing counted against the daily
     * allowance. Those two gates are about a recording LEAVING THE DEVICE for a third-party provider,
     * and `dwDictationLadder`'s `appModelRung` conjunction reads neither for the same reason. The
     * samples are a `FloatArray` that goes out of scope when this coroutine ends.
     *
     * ── WHY IT SAYS THE WAIT OUT LOUD FIRST ───────────────────────────────────────────────────
     *
     * This is the slowest thing in the app by a wide margin: **up to 2.967× the length of the audio**,
     * plus up to **8.5 seconds** to open the model, both measured on this fleet's handset. Ninety
     * seconds of speech can therefore be four and a half minutes of waiting with an empty box. A
     * designer who is not told that will decide the app has hung — so the panel says the range before
     * anything starts, and it says there is nothing to watch, and it gives them a way out.
     *
     * ── AND WHY THERE IS NO PROGRESS BAR ──────────────────────────────────────────────────────
     *
     * Because there is nothing to measure. A CTC model takes the audio whole and returns one result;
     * it emits no partial transcript and reports no fraction of itself completed. A bar filling at a
     * guessed rate would be a fabricated measurement on the one screen where the designer is deciding
     * whether to keep waiting, and this app does not report progress it is not measuring.
     */
    fun startOnDeviceModel() {
        // Released first, exactly as `startRecording` does and for the same reason: arriving here
        // from a rung-1 refusal means a `SpeechRecognizer` may still be bound, and two objects
        // holding the microphone is how a capture fails to start on the handsets least able to spare
        // the audio path.
        release()
        val plan = DW_TIER1_CATALOGUE.firstOrNull { it.modelId == DwAsrModelRun.status().model?.modelId }
        /*
         * NULL WHEN THERE IS NO MEASURED BAND, AND THE PANEL DRAWS ANYWAY.
         *
         * This used to fall back to *"Nothing leaves this phone. How long it takes here is
         * unmeasured."* The refusal to invent a number was right and is kept — the sentence was not.
         * Both its clauses are narration: which engine served the request is an implementation detail
         * a designer never chose (principle 1), and "how long it takes is unmeasured" is the app
         * describing the state of its own documentation to somebody waiting to speak. A model with no
         * measured real-time factor gets NO wait sentence, which is the honest answer, and the elapsed
         * clock on the panel is then the only timing claim on screen — a measured one.
         *
         * THE PANEL IS NO LONGER GATED ON THIS BEING NON-NULL, and that is the part that had to change
         * with it. It was `localNote?.let { … }`, so a null note meant no panel at all: no Stop, no
         * Cancel, and nothing on screen for a rung that can run for four minutes with an empty box.
         * The wait sentence is optional; the way out never is.
         */
        localNote = plan?.let { dwModelWaitSentence(it, pcm.maxMillis) }
        val refusal = pcm.start()
        if (refusal != null) {
            release()
            currentError(refusal)
            return
        }
        state = DwDictationState.RECORDING_FOR_THIS_PHONE
        localJob = scope.launch {
            // The read loop blocks until `requestStop` or the ninety-second cap, so it cannot be on
            // the dispatcher composition runs on.
            val samples = withContext(Dispatchers.IO) { pcm.readUntilStopped() }
            pcm.stop()
            if (samples.isEmpty()) {
                release()
                currentError(DW_DICTATION_NOTHING_RECORDED)
                return@launch
            }
            state = DwDictationState.TRANSCRIBING_ON_THIS_PHONE
            /*
             * `Dispatchers.Default` AND NOT `IO`: this is minutes of CPU on two threads, not a wait
             * on a file. Putting it on the IO pool, which is sized for blocked threads, would let a
             * decode and a photograph's JPEG write contend for the same handset's little cores.
             */
            /*
             * `tag` IS PASSED, AND FOR A PER-LANGUAGE MODEL IT DECIDES WHICH GRAPH OPENS. The ladder
             * only put this rung in the plan because `appModelServesLanguage` was true for this tag, so
             * the head resolution below cannot fail for a correctly-built artifact — and if it does, it
             * is [Outcome.Refused] with the tag in the sentence rather than a decode through some other
             * language's head, which is the failure mode `DwAsrModelHead` exists to name.
             */
            val outcome = withContext(Dispatchers.Default) {
                DwAsrSpeechModel.transcribe(context, DwAsrModelRun.status(), samples, tag)
            }
            when (outcome) {
                is DwAsrSpeechModel.Outcome.Text -> {
                    val text = outcome.text.trim()
                    if (text.isNotEmpty()) {
                        currentPartial("")
                        release()
                        currentCommit(text)
                    } else {
                        /*
                         * THE MODEL WORKED AND HEARD NOTHING. **NOT a step to the next rung**, for
                         * `advance`'s documented precondition: the designer has already spoken and
                         * pressed Stop, and silently opening a network engine at somebody who has
                         * finished speaking hands them "No speech was heard" a few seconds later with
                         * no account of where their words went. The same call `finishRecording` makes
                         * for rung 2.
                         */
                        release()
                        currentError(
                            "This phone's own speech model ran and found no words in what it heard. " +
                                "Nothing was sent anywhere. Try again a little closer to the " +
                                "microphone, or type the answer in."
                        )
                    }
                }

                is DwAsrSpeechModel.Outcome.Refused -> {
                    /*
                     * THE MODEL REFUSED, AND THAT IS A MEASUREMENT OF THIS HANDSET. Written down for
                     * the tag so the rest of the stage skips this rung, exactly as a code 13 is
                     * written down for rung 1 — and then the walk STOPS rather than advancing,
                     * because by now the designer has spoken. The sentence says it is worth
                     * reporting, because a model measured as serving this language and refusing it on
                     * a real phone is precisely the contradiction docs/DEVICE-TIER-MEASUREMENT.md
                     * exists to collect.
                     */
                    DwAsrModelRun.recordRefusal(tag)
                    release()
                    currentError(
                        "This phone's own speech model would not run just now: ${outcome.detail}. " +
                            "Nothing was sent anywhere and nothing was recorded. Tap the microphone " +
                            "again and dictation will use whatever else this phone has. This is " +
                            "worth reporting — the model was measured as working on this handset, so " +
                            "it refusing here is a fault in this app rather than anything you did."
                    )
                }
            }
        }
    }

    beginAt = { next ->
        when (next) {
            DwDictationRung.ON_DEVICE_PACK -> listen(onDevice = true)
            DwDictationRung.NETWORK_RECOGNISER -> listen(onDevice = false)
            DwDictationRung.SERVER_DICTATE -> startRecording()
            /*
             * THIS APP'S OWN MODEL. **WIRED 2026-08-12, AND REACHABLE ON THE ATTACHED HANDSET.**
             *
             * ── THE COMMENT THAT USED TO SIT HERE IS DELETED, NOT AMENDED ─────────────────────
             *
             * Two blocks stood here and they contradicted each other, which is worse than either
             * being wrong on its own: a reader believed whichever one they reached first. The first
             * said this rung was *"UNREACHABLE TODAY, AND NOT BY LUCK"*, that `conditionsNow()`
             * passes `appModelServesLanguage = false` on every handset, that *"the model catalogue is
             * empty and no model can be installed at all"*, and that this branch therefore STEPS PAST
             * the rung rather than serving it. **Every clause of that is now false.**
             * `DW_TIER1_CATALOGUE` holds one plan, it is installed and digest-verified on the
             * SM-M325F, `dwDictationLadder` does put this rung in a plan for `hi-IN`, and the branch
             * calls [startOnDeviceModel] rather than stepping anywhere. It was deleted rather than
             * kept as history because a comment describing a code path that no longer exists, sitting
             * directly above the path that replaced it, is the load-bearing kind of stale: the next
             * reader to change this `when` reads "unreachable" and reasons from it.
             *
             * ── THE ONE DEPARTURE FROM WHAT THAT COMMENT ASKED FOR, WHICH IS DELIBERATE ───────
             *
             * It asked for: load through the verified engine (`dwAsrMayLoad`), and on a refusal call
             * `advance(APP_SPEECH_MODEL)` after recording the refusal for the tag, as the code-13 path
             * does for rung 1. [startOnDeviceModel] does the first half and **reports instead of
             * advancing.** The reasoning it was written from — that this rung, like rungs 1 and 3,
             * refuses "within a moment of being started" — is true of OPENING the model and false of
             * the failure that actually matters: a decode that throws after ninety seconds of somebody
             * speaking. `advance`'s documented precondition is that nothing has been said yet; by then
             * something has, so the walk stops and says where the words went.
             */
            DwDictationRung.APP_SPEECH_MODEL -> startOnDeviceModel()
        }
    }

    /** Work out the ladder for this tap and start on its first rung, or say why there is none. */
    fun beginWalk() {
        val decided = dwDictationLadder(conditionsNow())
        plan = decided
        val first = decided.first
        if (first == null) {
            // Rung 4. Said HERE and not three engine failures later, because every rung the ladder
            // dropped was dropped for a reason this phone already knows — no signal, no pack, no
            // provider — and making a designer wait through timeouts to be told so is the "type it
            // in, said far too early" failure in its other direction.
            currentError(decided.exhausted ?: DW_DICTATION_UPLOAD_FAILED)
            return
        }
        beginAt(first)
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginWalk()
        } else {
            // Asked AT THE POINT OF USE and refused here means refused for dictation specifically,
            // which is a different thing from the launch-time batch being declined months ago on a
            // shared handset. Say what was refused and what it was for.
            currentError("Dictation needs the microphone. Nothing was recorded.")
        }
    }

    /**
     * A live recogniser is a held microphone, and so is a live recorder. Compose disposes this button
     * whenever the collection row collapses or the stage scrolls it out of the tree, and an engine
     * that survives that keeps the hardware and goes on writing results into a lambda whose field is
     * gone.
     *
     * RUNG 2 ADDS TWO MORE THINGS TO PUT DOWN: a `MediaRecorder` (which also owns a file that must be
     * deleted, not merely closed) and an upload in flight. The upload's own scope dies with this
     * composable, which is what cancels it; the explicit `discard` is what stops a recording that was
     * still running from holding the microphone for the rest of the sitting.
     *
     * The mount sweep clears clips orphaned by an earlier run — a process killed between Stop and the
     * response would otherwise leave a recording of an artisan's voice on a shared phone. It runs
     * once per app run, not once per field: see [DwDictationRun.sweepScratchOnce].
     */
    DisposableEffect(Unit) {
        DwDictationRun.sweepScratchOnce(context)
        onDispose {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            uploadJob?.cancel()
            recorder.discard()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            // Disabled while the clip is with the server. There is nothing a tap could mean: the
            // recording has already gone, and a second tap that started a NEW dictation would leave
            // the first one's words arriving into a field that has moved on.
            enabled = enabled &&
                state != DwDictationState.UPLOADING &&
                // The same reasoning as UPLOADING, one rung up: this phone is mid-decode, the
                // microphone is shut, and a second tap that started a NEW dictation would leave the
                // first one's words arriving into a field that has moved on — except that here the
                // first one's decode is also holding 1.26 GB, so a second would be the pair of them.
                state != DwDictationState.TRANSCRIBING_ON_THIS_PHONE,
            onClick = {
                when (state) {
                    // Stop means "I have finished the sentence": stopListening asks the engine to
                    // finalise what it already has, where cancel would throw it away. A designer who
                    // has just spoken forty words and taps the obvious button must not lose them.
                    DwDictationState.LISTENING, DwDictationState.WORKING ->
                        runCatching { recognizer?.stopListening() }
                    // The same promise on rung 2, kept a different way: the recording is closed and
                    // sent rather than discarded. Reached from the panel's Stop button too — this
                    // one is here for the case where the panel is not what the designer taps.
                    DwDictationState.RECORDING -> finishRecording()
                    // RUNG 1b, AND STOP MEANS THE SAME THING IT MEANS EVERYWHERE ELSE HERE: close
                    // the microphone and write down what was said. It does NOT abandon the audio —
                    // the decode starts the moment the read loop ends. Discarding is a separate,
                    // labelled button on the panel, because eighty seconds of speech is too much to
                    // lose to a button whose meaning has to be guessed.
                    DwDictationState.RECORDING_FOR_THIS_PHONE -> pcm.requestStop()
                    // Nothing to stop that would not also throw the words away: the microphone is
                    // already shut and this phone is mid-decode. The panel's Cancel is the way out,
                    // and it is labelled as one. A tap here doing nothing is correct and is why the
                    // button is disabled below for this state as well as for UPLOADING.
                    DwDictationState.TRANSCRIBING_ON_THIS_PHONE -> Unit
                    DwDictationState.UPLOADING -> Unit
                    DwDictationState.IDLE -> {
                        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                            beginWalk()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
        ) {
            Icon(
                if (state == DwDictationState.IDLE) Icons.Filled.Mic else Icons.Filled.Stop,
                contentDescription = when (state) {
                    DwDictationState.IDLE -> "Dictate this answer in ${labelOf(tag)}"
                    // Named apart from "Stop dictating" because a screen reader is the one surface
                    // that cannot see the panel explaining that this rung does not stream.
                    DwDictationState.RECORDING ->
                        "Stop recording and send this dictation to be written down"
                    DwDictationState.UPLOADING -> "Writing down what you dictated"
                    else -> "Stop dictating"
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
                        /*
                          THE OFFER OPENS ONLY WHERE A TAP COULD ACTUALLY FETCH SOMETHING.

                          ── THE MEASUREMENT THAT FORCED THIS ──────────────────────────────────

                          On the fleet's SM-M325F this dialog opened for **17 of 19 languages** and
                          the only control on it was `Close`. Fifty-seven words, a dismissal, and
                          nothing done — on every language choice, for ever. This file's own
                          `DwLanguagePackOfferDialog` docstring already argued the principle: *"a
                          dialog that says 'we cannot tell' on every language choice is a dialog a
                          designer learns to dismiss without reading"*. A dialog that says "nothing
                          can be done" is the same dialog with a different sentence in it.

                          ── `dwMayAsk` WAS ALREADY THE PREDICATE; THIS CALL SITE WAS IGNORING IT ──

                          The gate is now the same function the settings list and the dialog's own
                          confirm button use, so three surfaces cannot disagree about whether one
                          pack may be asked for. NO_OFFLINE_PACK, UNSUPPORTED, NETWORK_ONLY,
                          INSTALLED, UNKNOWN and DOWNLOADING all fall through it — five of them
                          because nothing could be fetched, and DOWNLOADING because it already is.

                          THE ONE CASE THAT STILL OPENS ON A MAYBE is a check that has not landed:
                          a designer who picks a language a half-second after opening the list must
                          not silently miss a real offer. The dialog opens saying it is asking and
                          resolves in place — and if the answer turns out to be "nothing to do", it
                          shows the state sentence with a Close, which is the one moment that panel
                          is honest rather than pointless.
                        */
                        val offerNow = dwPackOffer(
                            dwPackState(chosen, packs.support),
                            packs.connection,
                        )
                        packOffer = when {
                            packs.support == null && packs.checking -> chosen
                            dwMayAsk(
                                offer = offerNow,
                                requested = packs.requests[chosen] != null,
                                refused = packs.requests[chosen]?.failed == true,
                            ) -> chosen
                            else -> null
                        }
                    }
                    showLanguages = false
                }
            )
            /*
             * ── AND THAT IS THE WHOLE PANEL. ─────────────────────────────────────────────────
             *
             * Three things stood here and all three are gone. Measured before: **61 words**. After:
             * the select and its label.
             *
             *  1. **A per-language state line** ("Hindi: works offline"). The row a designer just
             *     picked from is not improved by being told what they picked.
             *  2. **The suppressed-rung warning** — that a craft-aware path was withheld for want of
             *     consent or allowance. It was in the right place for the wrong reason: it is a
             *     failure, and principle 2 says a failure is reported AT THE MOMENT IT FAILS, not as
             *     a standing note above a control that is about to work anyway. `dwDictationLadder`
             *     still computes it and `dwDictationNothingLeftSentence` still says it when the walk
             *     runs out — which is when it costs something.
             *  3. **A 35-word lecture** about choosing the wrong language transcribing into the wrong
             *     script. The web trusts its dropdown; so does this one. If the language is wrong the
             *     recogniser answers ERROR_NO_MATCH and `onError` above says so, naming the language,
             *     at the moment it happens.
             *
             * The web's whole dictation surface is three words. This is now a labelled select.
             */
        }
    }

    // A dialog rather than a strip under the field: this button is drawn into a text field's
    // trailing-icon slot, which is a few dp wide, and an offer that has to fit there would be an
    // offer nobody could read. It opens only in response to the designer's own tap on a language.
    packOffer?.let { chosen ->
        DwLanguagePackOfferDialog(
            controller = packs,
            tag = chosen,
            label = DW_DICTATION_LANGUAGES.firstOrNull { it.tag == chosen }?.label ?: chosen,
            onDismiss = { packOffer = null }
        )
    }

    // Rung 2's own surface, for the same reason the offer above is a dialog: there is nowhere in a
    // trailing-icon slot to put three sentences. It exists at all because this rung does not stream —
    // see [DwDictationState.RECORDING].
    if (state == DwDictationState.RECORDING || state == DwDictationState.UPLOADING) {
        DwServerDictationPanel(
            label = labelOf(tag),
            uploading = state == DwDictationState.UPLOADING,
            onStop = { finishRecording() },
            onCancel = {
                // Cancel throws the recording away unsent. Distinct from Stop on purpose: a
                // designer who started dictating into the wrong field needs a way out that does
                // not spend an upload and does not write words into the box.
                currentPartial("")
                release()
            },
        )
    }

    // Rung 1b's surface, for rung 2's reason: there is nowhere in a trailing-icon slot to put a
    // sentence about a four-minute wait, and Stop needs somewhere unmissable to live — more so here
    // than anywhere else in the app, because this is the one rung that can run for minutes.
    // GATED ON THE STATE, NOT ON THE SENTENCE. See the note in `startOnDeviceModel`: a model with no
    // measured wait band leaves `localNote` null, and the panel still has to draw — it is the only
    // Stop and the only Cancel this rung has.
    if (state == DwDictationState.RECORDING_FOR_THIS_PHONE ||
        state == DwDictationState.TRANSCRIBING_ON_THIS_PHONE
    ) {
        DwOnDeviceModelPanel(
            label = labelOf(tag),
            note = localNote,
            transcribing = state == DwDictationState.TRANSCRIBING_ON_THIS_PHONE,
            onStop = { pcm.requestStop() },
            onCancel = {
                currentPartial("")
                release()
            },
        )
    }
}

/**
 * What rung 1b looks like while it runs. **The panel that has to be honest about the wait.**
 *
 * ── WHY IT IS NOT [DwServerDictationPanel] WITH A DIFFERENT STRING ────────────────────────────
 *
 * Because every load-bearing sentence in that one is false here, and in the direction that matters.
 * It says the dictation "needs a connection" — this one needs none, which is the whole point of it.
 * It says the recorder "stops itself after four minutes" — this one stops after ninety seconds, and
 * for a different reason: not the size of an upload but the fact that THIS PHONE has to decode it and
 * is slower than real time at doing so. And its Cancel is about not spending an upload, where here
 * there is nothing to spend.
 *
 * ── THE TWO PHASES ARE DRAWN DIFFERENTLY BECAUSE THEY PROMISE DIFFERENT THINGS ────────────────
 *
 * While RECORDING the designer can still shorten the wait by pressing Stop sooner, and the panel says
 * so. While TRANSCRIBING there is nothing they can do but wait or abandon — and **there is no
 * progress bar**, because a CTC model reports no fraction of itself completed and a bar filling at a
 * guessed rate is a fabricated measurement. What is shown instead is a spinner and the honest range,
 * which is what [dwModelWaitSentence] carries.
 *
 * ── AND WHY "STOP" IS NOT "CANCEL" ────────────────────────────────────────────────────────────
 *
 * Stop ends the RECORDING and starts the decode: what was said still becomes text. Cancel throws
 * everything away. Folding them together would give a designer who has spoken for eighty seconds one
 * button that might mean either, and the cost of guessing wrong is eighty seconds of speech.
 */
@Composable
private fun DwOnDeviceModelPanel(
    label: String,
    /** The measured wait band, or **null** for a model whose real-time factor nobody has measured. */
    note: String?,
    transcribing: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        // Tapping outside CANCELS. A stray tap must not silently abandon a decode that is nearly
        // done, so the designer who meant to wait has an unmissable panel and the one who meant to
        // leave has a labelled button; this is the accidental case, and losing a wait is cheaper than
        // losing the words.
        onDismissRequest = onCancel,
        title = { Text("Dictating $label on this phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (transcribing) {
                    /*
                     * ── THE ELAPSED CLOCK. THE BRIEF'S "show elapsed time and a cancel, never a
                     *    fake bar", AND THE SPINNER ALONE WAS NOT IT. ──────────────────────────
                     *
                     * A spinner says "something is happening" and nothing else, which on a wait that
                     * can genuinely run to four and a half minutes is indistinguishable from a hung
                     * app. A counting clock is a MEASURED number — the only one this rung has — and
                     * it is what lets a designer hold the range in `note` against reality and decide
                     * whether to keep waiting.
                     *
                     * IT IS STILL NOT A PROGRESS BAR AND MUST NOT BECOME ONE. Elapsed time is
                     * something that happened; a bar would be a claim about how much is left, and a
                     * CTC model reports no fraction of itself completed. Do not divide this by the
                     * estimate to fill anything in.
                     */
                    var elapsed by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) {
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        while (true) {
                            kotlinx.coroutines.delay(1_000)
                            elapsed = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            if (elapsed < 60) {
                                "Writing it down — $elapsed sec so far."
                            } else {
                                "Writing it down — ${elapsed / 60} min ${elapsed % 60} sec so far."
                            },
                            color = MaterialTheme.field.body,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Text(
                        "Recording — speak now, then press Stop.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
                // The measured wait band, which is the one thing on this panel worth a sentence and
                // the reason this rung gets one where rung 2 no longer does. See dwModelWaitSentence.
                //
                // DRAWN ONLY WHEN IT WAS MEASURED. An unmeasured model prints nothing here rather than
                // a sentence saying so — the elapsed clock above is then the only timing claim on the
                // panel, and it is a reading rather than an estimate.
                note?.let { Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp) }
                if (!transcribing) {
                    // Said while it is still true rather than as a surprise at ninety seconds. The
                    // "why" clause went with the rest of the narration — the wait band above already
                    // tells a designer that a shorter recording is a shorter wait.
                    Text(
                        "Stops itself after 90 seconds.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            // No Stop once the microphone is already shut: there is nothing left to stop, and a
            // button that does nothing is how a designer decides the app has hung.
            if (!transcribing) TextButton(onClick = onStop) { Text("Stop") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(if (transcribing) "Cancel" else "Discard") }
        },
    )
}

/**
 * What rung 2 looks like while it runs.
 *
 * ── WHY THERE IS A PANEL AT ALL ───────────────────────────────────────────────────────────────
 *
 * On rungs 1 and 3 the words appear in the box as they are spoken, and the strip under the field
 * ([DwDictationHint]) is enough. On rung 2 NOTHING appears until Stop, because the clip is
 * transcribed after it is sent. A designer who has used dictation on a phone with the pack installed
 * will otherwise watch an empty box, decide the microphone is dead, and stop using the feature — so
 * the difference is stated in words before it can be misread, exactly as the web states it
 * ("This browser has no built-in dictation, so the recording is transcribed after you press Stop").
 *
 * [note] carries the second half, which the web has no equivalent of: WHY this dictation is on rung
 * 2. "The Odia pack is not on this phone" is a fact a designer can act on; behaving differently
 * without saying so teaches them nothing.
 *
 * ── WHY A DIALOG AND NOT A STRIP ──────────────────────────────────────────────────────────────
 *
 * The same constraint as [DwLanguagePackOfferDialog]: this control is composed into a text field's
 * trailing-icon slot, which is a few dp wide. It also gives Stop somewhere unmissable to live, which
 * matters more here than on the other rungs — the platform recogniser stops itself after a silence,
 * and a `MediaRecorder` does not. It runs until it is told to stop or until it reaches its own cap.
 */
@Composable
private fun DwServerDictationPanel(
    label: String,
    uploading: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        // Tapping outside CANCELS rather than sends. A stray tap must not spend an upload, and the
        // designer who meant to stop has an unmissable Stop button two centimetres away.
        onDismissRequest = onCancel,
        title = { Text("Dictating $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uploading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "Writing down what you said…",
                            color = MaterialTheme.field.body,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Text(
                        "Recording — speak now, then press Stop.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
                /*
                 * ── 92 WORDS BECAME 22, AND [note] IS WHAT WENT. ────────────────────────────────
                 *
                 * `dwDictationServerNote` composed two things: **why** this dictation is on rung 2
                 * (a pack list a designer cannot act on from here) and a 60-word paragraph about the
                 * craft keyterm list being handed to the model. The second is provenance — how the
                 * app works — not state; nothing a designer reads there changes what they do next.
                 * The first is an implementation detail: they picked a language and pressed a button,
                 * and which of four engines answered is not their decision to make.
                 *
                 * WHAT SURVIVES IS THE ONLY THING THAT IS ACTUALLY DIFFERENT ABOUT THIS RUNG — the
                 * words do not stream — which is exactly the one line the web prints in the same
                 * situation, and the self-stop, which is a fact about what is about to happen to a
                 * designer mid-sentence. `dwDictationServerNote` is no longer called from anywhere
                 * in this panel and its parameter is gone from the signature — an argument nothing
                 * draws is the next reader's invitation to draw it again.
                 */
                if (!uploading) {
                    Text(
                        "The words arrive when you press Stop, not as you speak.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    // Said while it is still true rather than as an error at the end. The recorder
                    // stops itself at its cap and sends what it has (see DW_DICTATION_MAX_MILLIS).
                    Text(
                        "Stops itself after four minutes.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            // No Stop while the clip is already with the server: there is nothing left to stop, and a
            // button that does nothing is how a designer decides the app has hung.
            if (!uploading) TextButton(onClick = onStop) { Text("Stop") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(if (uploading) "Cancel" else "Discard") }
        },
    )
}

/**
 * The strip under a field that is currently being dictated into. **Three words, the web's own.**
 *
 * It was fourteen: the three below plus *"The words appear as you speak; tap the square to finish."*
 * Both halves of that clause describe what the designer is already watching happen — the words ARE
 * appearing in the box in front of them, and the button they pressed has visibly become a square.
 * `Dictation.tsx` prints `Listening… speak now.` and stops, and it is right to.
 */
@Composable
internal fun DwDictationHint(listening: Boolean) {
    if (!listening) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Listening — speak now.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
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

/**
 * Why a surface is showing no microphone, or null when it is showing one.
 *
 * Both halves have to be false before this says anything, for the reason [rememberDictationAvailable]
 * gives: a phone with no speech service can still dictate through the server, so "no recogniser" on
 * its own is no longer a reason to say dictation is absent. When it IS absent BOTH halves are
 * missing at once — no speech service on this handset AND no way to reach the route — so the
 * sentence names both. It named only the recogniser while that was the whole story, and leaving it
 * there would have told a designer on a signed-out or half-built app that their phone was the
 * problem.
 */
internal fun dictationUnavailableNote(context: Context): String? = when {
    runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) -> null
    DwDictationRun.repository() != null -> null
    else -> "This phone has no speech recogniser installed and this app has no server to send a " +
        "recording to, so there is no dictation here. Type the answer in."
}
