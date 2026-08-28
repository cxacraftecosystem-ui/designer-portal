package com.designprototype.workshop.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DW_DICTATION_NOTHING_RECORDED
import com.designprototype.workshop.data.DW_TIER1_CATALOGUE
import com.designprototype.workshop.data.DwDictationRung
import com.designprototype.workshop.data.dwDictationLadder
import com.designprototype.workshop.data.dwMayAsk
import com.designprototype.workshop.data.dwModelWaitSentence
import com.designprototype.workshop.data.dwPackOffer
import com.designprototype.workshop.data.dwPackState
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import com.designprototype.workshop.ui.designworkshop.DwAsrModelRun
import com.designprototype.workshop.ui.designworkshop.DwAsrPcmRecorder
import com.designprototype.workshop.ui.designworkshop.DwAsrSpeechModel
import com.designprototype.workshop.ui.designworkshop.DwLanguagePackOfferDialog
import com.designprototype.workshop.ui.designworkshop.RichTextEditor
import com.designprototype.workshop.ui.designworkshop.hasPermission
import com.designprototype.workshop.ui.designworkshop.rememberDwLanguagePacks
import com.designprototype.workshop.report.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  THE TWO CONTROLS THE STAGE SCREENS HAVE, ON THE RECORD FORMS.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The requirement, in the words it was given in: *"In the existing pages apart from the designer
 * workshop as well, the dictate option along with the rich text formatting for the bigger fields
 * should be there."* Refined twice by the person who asked for it — **on-device dictation only**
 * ("the rest of the pages except for questionnaire is good with just the web speech or offline one
 * for both android and web"), **the larger boxes only** ("only the larger text boxes need to have
 * the rich text features"), and **the questionnaire screens are out of scope** because they already
 * have a section-audio workflow that was designed around how these interviews are actually run.
 *
 * ── WHY THIS IS ONE COMPOSABLE AND NOT AN EDIT IN TWENTY SCREENS ──────────────────────────────
 *
 * Every record form in this app builds its boxes from one private `TextInput` in `MainActivity`.
 * Putting the microphone and the editor HERE, behind two opt-in flags, and letting `TextInput`
 * forward to it means a form gains both controls by changing one argument at one call site — and it
 * means the twenty-odd screens cannot drift apart in what dictation does, which is precisely what
 * happened on the web between `IdentityCardReader` and `IdentityCardCapture`. It is also what makes
 * this portable: the sibling repository can copy these two files and change its call sites, rather
 * than re-deriving a ladder from twenty diffs.
 *
 * ── THE ONE THING THIS FILE MUST NEVER DO ─────────────────────────────────────────────────────
 *
 * **Send a clip anywhere.** There is no repository in this file, no `okhttp`, no upload, no
 * `MediaRecorder` writing a file that outlives a call, and no import that could acquire one. The
 * argument is in `RecordProseText.kt` and it is short: the only route that accepts an artisan's
 * recording checks a design workshop's recorded consent, a record form has no design workshop, and
 * the id-less route was retired to 410 GONE so that nobody could dictate without one. A record form
 * therefore gets rungs 1 and 1b — the platform's recogniser, and this app's own bundled model where
 * it is installed — and nothing else. Grep this file for `repository` and find nothing; that is the
 * guarantee, and it is checked by reading rather than by a test because absence is what it is made of.
 */

/**
 * The language last chosen on a RECORD form, for the life of the process.
 *
 * A separate memory from the stage screens' `lastDictationTag`, and not by choice: that one is
 * `private` to `DwDictation.kt`, which this lane may not edit. The cost is small and visible — a
 * designer who picks Odia on a stage and then opens an artisan record finds the record forms still
 * on Hindi — and the fix, if somebody wants one, is to hoist the single `var` out of `DwDictation.kt`
 * into `data/` and have both files read it. Worth doing; not worth editing three agents' file for.
 *
 * Process-scoped rather than persisted, for the stage file's reason: it is a preference of the
 * sitting, not of the account, and a phone handed to a colleague for an Odia session must not go on
 * silently answering in Hindi tomorrow.
 */
private var lastRecordDictationTag: String = DW_DICTATION_LANGUAGES.first().tag

/** What a record form's microphone is doing. Three states, because it has three rungs and no upload. */
private enum class RecordDictationState {
    IDLE,

    /** Rung 1 or 3: the platform's recogniser is listening and streaming partial results. */
    LISTENING,

    /** Rung 1 or 3: speech has ended and the engine is settling on the final text. */
    WORKING,

    /**
     * Rung 1b: the microphone is open and audio is going into memory for THIS PHONE to transcribe.
     *
     * Its own state rather than [LISTENING] because it makes a visibly different promise: nothing
     * appears in the box until Stop. A designer watching an empty field under a control that says
     * "Listening" concludes the microphone is dead.
     */
    RECORDING_FOR_THIS_PHONE,

    /**
     * Rung 1b: the microphone is shut and this phone's own model is decoding. **The long wait** —
     * measured at up to 2.967× the length of the audio on the fleet's handset, so ninety seconds of
     * speech can be four and a half minutes of nothing to look at.
     */
    TRANSCRIBING_ON_THIS_PHONE,
}

/**
 * Whether a record form should draw a microphone at all.
 *
 * ── DIFFERENT FROM THE STAGE SCREENS' TEST, AND THE DIFFERENCE IS THE WHOLE POINT ─────────────
 *
 * `rememberDictationAvailable` answers true when the platform has a recogniser **OR** this app has a
 * repository to post a clip through, because on a stage screen a phone with no speech service can
 * still dictate through the server. On a record form it cannot — there is no server rung — so the
 * repository half of that test would draw a microphone that has nothing behind it. The designer taps
 * it, gets a sentence, and learns that the app is broken.
 *
 * So this asks only about things that can actually answer here: a platform recogniser, or a verified
 * on-device model this app installed itself. An absent button asks no questions; a button that
 * always fails teaches somebody to stop trusting the ones that work.
 *
 * Not remembered across the composition's life beyond `remember(context)`: neither answer changes
 * while a form is on screen, and the model reading is a cached field on [DwAsrModelRun] rather than
 * a fresh 365 MB hash.
 */
@Composable
private fun rememberRecordDictationAvailable(): Boolean {
    val context = LocalContext.current
    // The PLATFORM half is cached for the process; the MODEL half is read live. The split is not an
    // optimisation for its own sake and the asymmetry is the whole reason it is written out.
    val platform = remember(context) { RecordSpeechServices.platformAvailable(context) }
    // A volatile field read, and it must not be cached: `DwAsrModelRun.warm` lands a second or two
    // after launch, so a cached "no model yet" would withhold the microphone for the rest of the run
    // on the one class of handset — no Google speech service, this app's model installed — where the
    // model is the only thing that can answer.
    return platform || DwAsrModelRun.status().model != null
}

/**
 * The two platform questions, asked once per process.
 *
 * ── WHY THIS IS CACHED AND THE STAGE SCREENS' EQUIVALENT IS NOT ───────────────────────────────
 *
 * `MainActivity`'s `TextInput` now forwards every record-form box through `RecordProseField`, and an
 * artisan form draws upwards of thirty of them in one pass. Both calls below reach the package
 * manager, and thirty package queries during the first composition of a form is a stutter on exactly
 * the budget handsets this app is built for — while the answers cannot change without an app being
 * installed or removed, which cannot happen while this process is in the foreground.
 *
 * Process-scoped rather than persisted, for the reason every other memory in this feature is: a
 * speech service installed this afternoon must be found by tomorrow's launch without anybody
 * clearing data.
 */
private object RecordSpeechServices {
    @Volatile private var cached: Boolean? = null

    fun platformAvailable(context: Context): Boolean = cached ?: run {
        val answer =
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                        .getOrDefault(false))
        cached = answer
        answer
    }
}

/**
 * What a box that asked for a microphone and cannot have one says instead of nothing.
 *
 * Said out loud rather than left as a blank space, because "this screen has no dictation" and "this
 * phone cannot dictate" look identical to somebody who was told the feature exists. It names the
 * phone, which is where the missing thing actually is, and it does not send anybody to a settings
 * screen — there is no setting in this app that installs a speech service, and pointing at one would
 * be advice incapable of a different outcome.
 */
private const val RECORD_DICTATION_UNAVAILABLE: String =
    "This phone has no speech recogniser installed, so there is no dictation here. Type the answer in."

/**
 * The microphone for one record-form field. **On-device rungs only.**
 *
 * [onPartial] receives the recogniser's running guess so the host can render it INSIDE the box the
 * designer is watching; [onCommit] receives the final text; [onError] receives a sentence. Drawing
 * the partial anywhere other than the box it will be saved from is what makes dictation feel
 * untrustworthy — the designer cannot tell whether the words they can see are the words that will
 * be kept.
 */
@Composable
private fun RecordDictationButton(
    enabled: Boolean,
    onPartial: (String) -> Unit,
    onCommit: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(RecordDictationState.IDLE) }
    var showLanguages by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf(lastRecordDictationTag) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    /**
     * The rungs THIS tap is walking, computed once from [dwDictationLadder] and then walked.
     *
     * Computed once rather than re-derived after each failure, for the reason the stage control
     * gives: recomputing would let a rung that has just failed be chosen again, and with three
     * engines and no natural cap that is a bounce with no exit. Already filtered — see
     * [recordDictationRungs] — so the server rung is not expressible here even if the shared ladder
     * grew one.
     */
    var plan by remember { mutableStateOf<List<DwDictationRung>>(emptyList()) }

    /** Rung 1b's microphone: 16 kHz mono PCM into memory. Nothing is written to a file. */
    val pcm = remember { DwAsrPcmRecorder() }

    /** Rung 1b's capture-and-decode, kept so leaving the field cancels it rather than orphaning it. */
    var localJob by remember { mutableStateOf<Job?>(null) }

    /** The measured wait rung 1b is about to cost, in words. Null for a model nobody has measured. */
    var localNote by remember { mutableStateOf<String?>(null) }

    /*
     * The 365 MB hash, started once per process and never on a tap. `DwAsrModelRun` guards itself, so
     * every call after the first is a boolean read; until it lands the model rung is simply not
     * offered, which is the fail-closed direction and costs a designer at most the first tap or two
     * after launch.
     */
    LaunchedEffect(Unit) { DwAsrModelRun.warm(context, scope) }

    /**
     * What this phone can dictate offline, asked instead of discovered by failing.
     *
     * `active` keeps it cheap: binding a `SpeechRecognizer` to ask is an IPC handshake, and a record
     * form draws several of these. It goes live only while the designer has the language list or the
     * pack offer open — which is exactly when the answer is wanted.
     */
    val packs = rememberDwLanguagePacks(active = showLanguages)

    /** The language just chosen whose pack is not on this phone, or null when there is nothing to offer. */
    var packOffer by remember { mutableStateOf<String?>(null) }

    // rememberUpdatedState so a listener created once per session always calls the CURRENT lambdas.
    // Capturing them directly would have a long dictation writing its words into the composable's
    // first-frame closure — which, on a repeated row, means writing into whichever row was open when
    // the recogniser started.
    val currentPartial by rememberUpdatedState(onPartial)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentError by rememberUpdatedState(onError)

    fun labelOf(chosen: String): String =
        DW_DICTATION_LANGUAGES.firstOrNull { it.tag == chosen }?.label ?: chosen

    /**
     * Put every engine down and go back to IDLE.
     *
     * BOTH rungs, not just the recogniser: rung 1b holds an `AudioRecord` and a coroutine that may be
     * blocked inside a native decode, and a release that only knew about `SpeechRecognizer` would
     * leave a microphone open under an idle-looking button. `DwAsrSpeechModel.release()` is NOT
     * called from here — the decode holds the object monitor for its whole duration, so releasing
     * from the main thread would block composition for minutes; the decode releases on every exit
     * path itself.
     */
    fun release() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        pcm.stop()
        localJob?.cancel()
        localJob = null
        localNote = null
        state = RecordDictationState.IDLE
    }

    fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * Everything the ladder is allowed to know, read FRESH at the moment it is asked.
     *
     * Not remembered, because every one of these can change between two taps on the same field: the
     * designer walks out of the courtyard and the connection appears, a pack finishes downloading,
     * the engine refuses a language and that refusal becomes a measured fact for the rest of the run.
     *
     * The workshop-shaped facts are pinned by [recordDictationConditions] rather than read, which is
     * where "a record form has no rung 2" is written down once.
     */
    fun conditionsNow() = recordDictationConditions(
        languageLabel = labelOf(tag),
        packState = dwPackState(tag, packs.support),
        onDeviceEngine = onDeviceAvailable(),
        networkRecogniser = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
            .getOrDefault(false),
        online = ConnectivityObserver.isOnline(context),
        deviceRefusedLanguage = com.designprototype.workshop.data.DwDictationRun.engineHasRefused(tag),
        appModelServesLanguage = DwAsrModelRun.servesLanguage(tag),
        appModelRefusedLanguage = DwAsrModelRun.hasRefused(tag),
    )

    var beginAt: (DwDictationRung) -> Unit = {}

    /**
     * This rung could not do it. Step to the next, or say why there is no next one.
     *
     * ONLY CALLED WHERE THE DESIGNER HAS NOT YET SPOKEN, which is a precondition rather than a
     * coincidence: the platform recogniser refuses a language within a moment of `startListening`,
     * so nothing is lost. Rung 1b fails only at the END of an utterance, and stepping silently from
     * there would re-open a microphone at somebody who has finished speaking — so it reports instead.
     */
    fun advance(from: DwDictationRung) {
        val index = plan.indexOf(from)
        val next = if (index < 0) null else plan.getOrNull(index + 1)
        if (next != null) {
            beginAt(next)
            return
        }
        // Composed from CURRENT conditions rather than from the plan's stale copy: by now the engine
        // may have refused the language, and that is the fact that decides which sentence is true.
        val sentence = recordDictationNothingLeftSentence(conditionsNow())
        release()
        currentError(sentence)
    }

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
            override fun onReadyForSpeech(params: Bundle?) { state = RecordDictationState.LISTENING }
            override fun onBeginningOfSpeech() { state = RecordDictationState.LISTENING }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { state = RecordDictationState.WORKING }

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
                // Cleared FIRST and unconditionally. A final result that arrives empty — which
                // happens on a clipped utterance — must not leave the last partial guess painted over
                // the box looking committed when nothing was saved.
                currentPartial("")
                if (text.isNotBlank()) currentCommit(text)
                release()
            }

            override fun onError(error: Int) {
                currentPartial("")
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Nothing was recognised. Check that the language beside the microphone " +
                            "matches what you are speaking, and try again closer to the phone."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech was heard. Tap the microphone and begin speaking straight away " +
                            "— it stops listening after a few seconds of silence."
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
                      12 is ERROR_LANGUAGE_NOT_SUPPORTED and 13 is ERROR_LANGUAGE_UNAVAILABLE, both
                      added in API 33 and both written as literals because this module builds against
                      minSdk 26. They are handled by MOVING ON rather than by explaining: the offline
                      engine reports 13 for a pack that is not downloaded, and the network engine may
                      well serve the same language. Only when the ladder is exhausted does anybody get
                      a sentence — and then it names a fix, because no number of further taps
                      downloads a language pack.
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
        // unless the preference is also present and silently answer in the device locale instead,
        // which turns a Tamil sentence into a page of phonetic English nobody can correct.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    fun listen(onDevice: Boolean) {
        val here =
            if (onDevice) DwDictationRung.ON_DEVICE_PACK else DwDictationRung.NETWORK_RECOGNISER
        release()
        val built = buildRecognizer(
            onDevice,
            onLanguageUnavailable = {
                // On the OFFLINE engine this is a measurement of this handset, worth more than any
                // catalogue, so it is remembered for the run and every remaining field on the form
                // skips the rung instead of paying for the same refusal. Not remembered for the
                // network engine: a "no" from Google's server says nothing about the pack on the
                // phone, and writing it down under the same key would retire a rung that works.
                if (onDevice) com.designprototype.workshop.data.DwDictationRun.engineRefused(tag)
                advance(here)
            },
            onNetworkFailure = { advance(here) },
        )
        if (built == null) {
            release()
            currentError("This phone would not start its speech recogniser.")
            return
        }
        recognizer = built
        state = RecordDictationState.WORKING
        runCatching { built.startListening(intentFor()) }.onFailure {
            release()
            currentError("This phone would not start its speech recogniser.")
        }
    }

    /**
     * **RUNG 1b: record into memory, then let this phone's own model write it down.**
     *
     * Nothing is sent anywhere: no repository, no upload, no file, no consent question and nothing
     * counted against any allowance. The samples are a `FloatArray` that goes out of scope when this
     * coroutine ends. That is why this rung is available on a record form at all, and it is the same
     * argument `dwDictationLadder`'s `appModelRung` conjunction makes by reading neither gate.
     *
     * It says the wait out loud FIRST, because it is the slowest thing in the app by a wide margin
     * and a designer who is not told will decide it has hung. There is no progress bar and there must
     * not be one: a CTC model reports no fraction of itself completed, so a bar filling at a guessed
     * rate would be a fabricated measurement on the one screen where somebody is deciding whether to
     * keep waiting.
     */
    fun startOnDeviceModel() {
        // Released first: arriving here from a code 13 means a `SpeechRecognizer` may still be bound,
        // and two objects holding the microphone is how a capture fails to start on exactly the
        // handsets least able to spare the audio path.
        release()
        val modelPlan = DW_TIER1_CATALOGUE
            .firstOrNull { it.modelId == DwAsrModelRun.status().model?.modelId }
        // Null for a model whose real-time factor nobody has measured. The panel still draws — it is
        // the only Stop and the only Cancel this rung has — it simply makes no timing claim.
        localNote = modelPlan?.let { dwModelWaitSentence(it, pcm.maxMillis) }
        val refusal = pcm.start()
        if (refusal != null) {
            release()
            currentError(refusal)
            return
        }
        state = RecordDictationState.RECORDING_FOR_THIS_PHONE
        localJob = scope.launch {
            val samples = withContext(Dispatchers.IO) { pcm.readUntilStopped() }
            pcm.stop()
            if (samples.isEmpty()) {
                release()
                currentError(DW_DICTATION_NOTHING_RECORDED)
                return@launch
            }
            state = RecordDictationState.TRANSCRIBING_ON_THIS_PHONE
            // `Dispatchers.Default` and not `IO`: this is minutes of CPU on two threads, not a wait
            // on a file, and the IO pool is sized for blocked threads.
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
                        // Worked and heard nothing. NOT a step to the next rung — see `advance`'s
                        // precondition: the designer has already spoken and pressed Stop, and
                        // silently opening another engine hands them "No speech was heard" a few
                        // seconds later with no account of where their words went.
                        release()
                        currentError(
                            "This phone's own speech model ran and found no words in what it " +
                                "heard. Nothing was sent anywhere. Try again a little closer to " +
                                "the microphone, or type the answer in."
                        )
                    }
                }

                is DwAsrSpeechModel.Outcome.Refused -> {
                    // A refusal is a measurement of this handset that contradicts the catalogue.
                    // Written down for the tag so the rest of the form skips the rung, and then the
                    // walk STOPS rather than advancing, because by now somebody has spoken.
                    DwAsrModelRun.recordRefusal(tag)
                    release()
                    currentError(
                        "This phone's own speech model would not run just now: ${outcome.detail}. " +
                            "Nothing was sent anywhere and nothing was recorded. Tap the " +
                            "microphone again and dictation will use whatever else this phone has."
                    )
                }
            }
        }
    }

    beginAt = { next ->
        when (next) {
            DwDictationRung.ON_DEVICE_PACK -> listen(onDevice = true)
            DwDictationRung.NETWORK_RECOGNISER -> listen(onDevice = false)
            DwDictationRung.APP_SPEECH_MODEL -> startOnDeviceModel()
            /*
             * THE RUNG THIS CONTROL DOES NOT HAVE, AND THE BRANCH IS NOT DEAD CODE.
             *
             * [recordDictationRungs] filters it out of every plan, so this is unreachable today. It
             * is written anyway, and it steps PAST rather than uploading, because `when` over an enum
             * is exhaustive: the day somebody adds a rung to `DwDictationRung` the compiler stops
             * here and makes them decide, and the day somebody weakens the filter this branch is what
             * stops an artisan's voice leaving a screen where nobody was asked for consent.
             *
             * Do not "clean this up" into an upload by copying `DwDictationButton.startRecording`.
             * The whole reason this file exists is that a record form has no consent-bearing workshop
             * to send a clip under.
             */
            DwDictationRung.SERVER_DICTATE -> advance(DwDictationRung.SERVER_DICTATE)
        }
    }

    fun beginWalk() {
        val conditions = conditionsNow()
        val rungs = recordDictationRungs(dwDictationLadder(conditions))
        plan = rungs
        val first = rungs.firstOrNull()
        if (first == null) {
            // Said HERE and not three engine timeouts later: every rung the ladder dropped was
            // dropped for a reason this phone already knows, and making somebody wait to be told so
            // is the same failure as telling them to type it in too early.
            currentError(recordDictationNothingLeftSentence(conditions))
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
            // Asked at the point of use and refused HERE means refused for dictation specifically,
            // which is a different thing from a launch-time batch declined months ago on a shared
            // handset. Say what was refused and what it was for.
            currentError("Dictation needs the microphone. Nothing was recorded.")
        }
    }

    /**
     * A live recogniser is a held microphone, and so is a live `AudioRecord`. Compose disposes this
     * button whenever the row collapses or the form scrolls it out of the tree, and an engine that
     * survives that keeps the hardware and goes on writing results into a lambda whose field is gone.
     */
    DisposableEffect(Unit) {
        onDispose {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            localJob?.cancel()
            pcm.stop()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            // Nothing a tap could mean while this phone is mid-decode: the microphone is already
            // shut, and a second tap starting a NEW dictation would leave the first one's words
            // arriving into a field that has moved on — and two 1.26 GB decodes at once besides.
            enabled = enabled && state != RecordDictationState.TRANSCRIBING_ON_THIS_PHONE,
            onClick = {
                when (state) {
                    // Stop means "I have finished the sentence": `stopListening` asks the engine to
                    // finalise what it already has, where `cancel` would throw it away. Somebody who
                    // has just spoken forty words and tapped the obvious button must not lose them.
                    RecordDictationState.LISTENING, RecordDictationState.WORKING ->
                        runCatching { recognizer?.stopListening() }
                    // The same promise on rung 1b: close the microphone and decode what was said. It
                    // does not abandon the audio — discarding is a separate, labelled button on the
                    // panel, because eighty seconds of speech is too much to lose to a guess.
                    RecordDictationState.RECORDING_FOR_THIS_PHONE -> pcm.requestStop()
                    RecordDictationState.TRANSCRIBING_ON_THIS_PHONE -> Unit
                    RecordDictationState.IDLE -> {
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
                if (state == RecordDictationState.IDLE) Icons.Filled.Mic else Icons.Filled.Stop,
                contentDescription = when (state) {
                    RecordDictationState.IDLE -> "Dictate this answer in ${labelOf(tag)}"
                    // Named apart from "Stop dictating" because a screen reader is the one surface
                    // that cannot see the panel explaining that this rung does not stream.
                    RecordDictationState.RECORDING_FOR_THIS_PHONE ->
                        "Stop recording and let this phone write it down"
                    RecordDictationState.TRANSCRIBING_ON_THIS_PHONE -> "Writing down what you said"
                    else -> "Stop dictating"
                },
                tint = if (state == RecordDictationState.IDLE) {
                    MaterialTheme.field.muted
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(20.dp)
            )
        }
        if (state == RecordDictationState.IDLE) {
            TextButton(
                onClick = { showLanguages = !showLanguages },
                enabled = enabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) {
                Text(labelOf(tag), color = MaterialTheme.field.muted, fontSize = 11.sp, maxLines = 1)
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
                        lastRecordDictationTag = chosen
                        // The offer opens ONLY where a tap could actually fetch something. Measured on
                        // the fleet's handset, the naive version opened for 17 of 19 languages with
                        // `Close` as its only control — a dialog somebody learns to dismiss unread.
                        // `dwMayAsk` is the same predicate the settings card and the dialog's own
                        // confirm button use, so three surfaces cannot disagree.
                        val offerNow = dwPackOffer(dwPackState(chosen, packs.support), packs.connection)
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
        }
    }

    packOffer?.let { chosen ->
        DwLanguagePackOfferDialog(
            controller = packs,
            tag = chosen,
            label = DW_DICTATION_LANGUAGES.firstOrNull { it.tag == chosen }?.label ?: chosen,
            onDismiss = { packOffer = null }
        )
    }

    // Rung 1b's surface. A dialog rather than a strip because this control is drawn into a text
    // field's trailing-icon slot, which is a few dp wide, and because Stop needs somewhere
    // unmissable to live — more so here than anywhere else in the app, since this is the one rung
    // that can run for minutes.
    if (state == RecordDictationState.RECORDING_FOR_THIS_PHONE ||
        state == RecordDictationState.TRANSCRIBING_ON_THIS_PHONE
    ) {
        RecordOnDeviceModelPanel(
            label = labelOf(tag),
            note = localNote,
            transcribing = state == RecordDictationState.TRANSCRIBING_ON_THIS_PHONE,
            onStop = { pcm.requestStop() },
            onCancel = {
                currentPartial("")
                release()
            },
        )
    }
}

/**
 * What rung 1b looks like while it runs on a record form.
 *
 * A near-twin of the stage screens' `DwOnDeviceModelPanel`, which is `private` to `DwDictation.kt`
 * and which this lane may not edit to widen. Duplicated rather than reached for, and the duplication
 * is bounded: it is a dialog with two buttons and one sentence, and the sentence that actually
 * matters — the measured wait band — comes from the shared `dwModelWaitSentence` so the two panels
 * cannot quote different numbers for the same model.
 *
 * **The elapsed clock is a measurement and the absence of a progress bar is deliberate.** A CTC model
 * reports no fraction of itself completed; a bar filling at a guessed rate would be a fabricated
 * number on the one screen where somebody is deciding whether to keep waiting. Do not divide the
 * clock by the estimate to fill anything in.
 */
@Composable
private fun RecordOnDeviceModelPanel(
    label: String,
    note: String?,
    transcribing: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        // Tapping outside cancels: this is the accidental case, and losing a wait is cheaper than
        // losing the words to a stray tap that silently abandoned a nearly-finished decode.
        onDismissRequest = onCancel,
        title = { Text("Dictating $label on this phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (transcribing) {
                    var elapsed by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) {
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        while (true) {
                            kotlinx.coroutines.delay(1_000)
                            elapsed =
                                ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
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
                // Said only where it was measured. An unmeasured model prints nothing here rather
                // than a sentence saying so; the elapsed clock is then the only timing claim on the
                // panel, and it is a reading rather than an estimate.
                note?.let { Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp) }
                if (!transcribing) {
                    Text(
                        "Nothing leaves this phone. Stops itself after 90 seconds.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            // No Stop once the microphone is already shut: there is nothing left to stop, and a
            // button that does nothing is how somebody decides the app has hung.
            if (!transcribing) TextButton(onClick = onStop) { Text("Stop") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(if (transcribing) "Cancel" else "Discard") }
        },
    )
}

/**
 * ONE PROSE BOX ON A RECORD FORM, with either control, both, or neither.
 *
 * ── THE TWO FLAGS ARE OPT-IN AND DEFAULT TO OFF, WHICH IS NOT LAZINESS ────────────────────────
 *
 * `MainActivity`'s `TextInput` forwards to this composable from ~200 call sites, almost all of them
 * single-line boxes for a name, a code, a phone number, a price or a date. A microphone beside a
 * money box is noise — nobody dictates "one thousand two hundred and fifty rupees fifty paise" into
 * a costing sheet, and the recogniser returns words where the coercion wants digits — and a
 * formatting toolbar over a two-word village name is a control that can only get in the way. So the
 * default for both is off and each larger box asks for what it wants, by name, at its own call site.
 * `dictatable()` on the stage screens narrows the same way for the same reason.
 *
 * ── WHY THE ERROR SENTENCE IS DRAWN HERE AND NOT HANDED UP ────────────────────────────────────
 *
 * Every record screen has a different way of showing a message — a snackbar, a banner, a local
 * `var`, nothing at all — and threading a channel through twenty of them would guarantee that some
 * of them dropped it. The stage screens already learned that lesson at a cost: `RichTextEditor`'s
 * dictation `onError` was `{ }` for a while and swallowed **every** sentence the control produces,
 * so a designer who spoke a passage watched it produce nothing with no account of why. Here the
 * sentence lands directly under the box that failed, always, with no call site able to discard it.
 *
 * @param rich Draw the rich-text editor instead of a plain box. **Larger narrative boxes only.**
 * @param dictate Draw the on-device microphone. Never uploads — see the file header.
 */
@Composable
fun RecordProseField(
    /**
     * The floating label, or **null** on a screen that draws its own heading above the box.
     *
     * Nullable because this app has two field conventions and both are load-bearing where they are
     * used: the record forms put the name inside the box as a Material label, and the newer admin
     * screens put a `FieldLabel` above it with a placeholder inside. A control that forced the first
     * would draw the name twice on the second, which is how a shared component gets forked.
     */
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    enabled: Boolean = true,
    rich: Boolean = false,
    dictate: Boolean = false,
    /** Shown inside an empty box. Ignored by the rich editor, which has nowhere to put one. */
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** Re-seed the rich editor when the form loads a different record into the same composition. */
    resetKey: Any? = null,
    /** Drawn under the box, above any dictation sentence. The form's own help for this field. */
    help: String? = null,
    /**
     * A VALIDATION REFUSAL for this field, or null. Non-null also paints the box in the error colour.
     *
     * ── WHY THIS EXISTS, ADDED 2026-08-28 ────────────────────────────────────────────────────────
     *
     * The owner asked that every record page offer dictation "wherever applicable, so as to reduce
     * the friction as much as possible". The boxes with the MOST typing friction on a record form
     * are the REQUIRED ones — an artisan's name, a product's name, a tool's toolkit name, an
     * interview's title — and those were the only boxes on the whole form that could not have a
     * microphone. `RequiredInput` in `MainActivity.kt` drew its own bare `OutlinedTextField`
     * because this component had nowhere to put an error message or a focus target, and
     * [RecordDictationButton] is private to this file.
     *
     * The two ways out were a second dictation control in `MainActivity.kt` — a second copy of the
     * partial/commit/refusal machinery, and the half that always drifts is the refusal wording — or
     * these three parameters. `TextInput` already forwards to this component precisely so the plain
     * and enriched paths cannot diverge in padding, label placement or keyboard type;
     * `RequiredInput` now does the same, and required and optional boxes stay one control.
     *
     * NULL IS NOT "VALID", IT IS "NOTHING TO SAY". This component performs no validation of its own
     * and must not start: the form owns the rule, this draws the answer.
     */
    errorText: String? = null,
    /**
     * Where a form's "you missed this one" focus call lands.
     *
     * Attached to the plain box only. The rich editor manages its own focus across a document and a
     * toolbar, and pointing a caller's `FocusRequester` into it would put the caret somewhere the
     * caller cannot reason about — so a rich box that is also required keeps the form's error
     * message and loses only the jump, which is the safe half to lose.
     */
    focusRequester: FocusRequester? = null,
    /** Drawn under the box — `TitleCaseHint` and friends, so a caller keeps its existing extras. */
    below: @Composable () -> Unit = {},
) {
    /**
     * The recogniser's running guess, drawn in the box but NOT yet in the store.
     *
     * Kept apart from [value] rather than appended to it as it grows, and the separation is the
     * point: a partial is REVISED as the sentence continues — "the weaver" becomes "the weavers of
     * Bagru" — so appending each one would leave the box holding every draft of the sentence
     * concatenated. Held apart, the last partial is simply replaced by the next, and by the final
     * text when it arrives.
     */
    var spoken by remember(resetKey) { mutableStateOf("") }

    /** The last dictation failure, shown under the box until the next attempt clears it. */
    var dictationError by remember(resetKey) { mutableStateOf<String?>(null) }

    val available = rememberRecordDictationAvailable()
    val showMic = dictate && available

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (rich) {
            /*
             * THE STAGE SCREENS' EDITOR, HOSTED AS IT STANDS.
             *
             * It takes no workshop id of any kind: its media bridge and its field descriptor are both
             * nullable and both absent here, so it degrades to "no Photograph button" — which is the
             * right answer on a record form, because only a stage knows which workshop's directory a
             * photograph's bytes belong in. Forking it would have meant duplicating ~2,900 lines of
             * caret, undo, span-merge and list arithmetic to change nothing; the identity-card reader
             * already made this exact journey on this platform (`FieldRenderer.kt:720-727` argues it)
             * and one control is what came out of it.
             *
             * ITS OWN TOOLBAR CARRIES A MICROPHONE, which is why [dictate] is not consulted in this
             * branch and why passing it would be a lie. That microphone is `DwDictationButton` — the
             * stage ladder — and on a record form it fails CLOSED to exactly the rungs this file
             * offers, because `DwDictationRun.published` is `(serverId = null, NOT_RECORDED)` off a
             * stage screen and the ladder refuses rung 2 twice over on that. It is not the same
             * sentences on exhaustion, and that mismatch is recorded in this lane's report rather than
             * papered over here: fixing it properly means widening `RichTextEditor` to take the
             * refusal copy as a parameter, which is a file three other agents are writing today.
             */
            /*
             * ── THE SEED IS HELD IN STATE, NOT DERIVED FROM [value] EVERY RECOMPOSITION ────────
             *
             * **THIS IS THE ONE THING IN THIS FILE THAT WOULD MAKE THE EDITOR UNUSABLE IF IT WERE
             * SIMPLIFIED.** `RichTextEditor` re-seeds itself from its `value` prop through a
             * `LaunchedEffect(value)` whenever the incoming document's signature differs from the
             * one it last emitted, and re-seeding moves the caret to the start of the document. Its
             * own file calls that "the single most common way a home-grown editor becomes unusable
             * for long-form writing", and on a stage screen it never happens because the value that
             * comes back is byte-identical to what the editor sent.
             *
             * Here it is NOT. What goes into the column is the FLATTENED text (see
             * `recordStoredFromDoc`), so a document that carries a bold run comes back out without
             * one and its signature therefore differs — every single time. Derived naively, the
             * chain would be: press Bold → emit plain text → parent re-renders → new seed → the
             * editor decides the document changed underneath it → the bold vanishes and the caret
             * jumps to character zero. On the second keystroke. For ever.
             *
             * So the seed changes only when the value arrived from somewhere OTHER than this
             * editor: a record finishing its load after the form composed, or a different record
             * being opened. [mine] is what tells the two apart. Within a sitting the marks stay
             * visible while the designer writes, which is exactly what `RECORD_RICH_TEXT_NOTE`
             * promises them and no more.
             */
            var seed by remember(resetKey) { mutableStateOf(richSeedOf(value)) }
            var mine by remember(resetKey) { mutableStateOf(value) }
            LaunchedEffect(value, resetKey) {
                if (value != mine) {
                    seed = richSeedOf(value)
                    mine = value
                }
            }
            RichTextEditor(
                value = seed,
                onChange = { next ->
                    // `recordStoredFromDoc` is the ONE place in this app that decides what lands in a
                    // record's `String?` column. Read its block comment before changing it: the
                    // alternative shape renders as visible JSON braces in a CSV, a report and a
                    // reviewer's edit box, and it does so silently.
                    val stored = recordStoredFromDoc(fromEditorDoc(next)).orEmpty()
                    // Recorded BEFORE the value goes up, so the round trip that comes back is
                    // recognised as this editor's own and does not re-seed it.
                    mine = stored
                    onValueChange(stored)
                },
                enabled = enabled,
                // The editor requires a label; a caller that draws its own heading passes null and
                // gets an empty one rather than a duplicate.
                label = label.orEmpty(),
                help = help,
                /*
                 * WIRED, BECAUSE THE DEFAULT IS `{ }` AND THE DEFAULT IS A KNOWN DEFECT.
                 *
                 * `RichTextEditor` hands its embedded microphone's `onError` straight through to
                 * this parameter, and its own file records at length what happened the last time a
                 * call site left it unwired: every sentence the dictation ladder produces — the
                 * permission refusal, the "no words were heard", the language that is not on this
                 * phone — arrived here and stopped, so a designer who had just spoken a passage
                 * watched it produce nothing with no account of why. Silence is the one answer this
                 * control may never give. Landing it in the same strip the plain box uses also means
                 * a record form reports a dictation failure the same way whichever box it happened
                 * in.
                 */
                onError = { message -> dictationError = message },
            )
            Text(RECORD_RICH_TEXT_NOTE, color = MaterialTheme.field.muted, fontSize = 11.sp)
        } else {
            OutlinedTextField(
                // While a partial is streaming the box shows what has been heard SO FAR, appended to
                // what was already there. Rendering it anywhere else — a strip below, a toast — is
                // what makes dictation feel untrustworthy: the designer cannot tell whether the words
                // they can see are the words that will be saved. Here they are literally in the box.
                value = if (spoken.isBlank()) value else appendSpokenToRecord(value, spoken),
                onValueChange = { raw -> if (spoken.isBlank()) onValueChange(raw) },
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let {
                    { Text(it, color = MaterialTheme.field.placeholder) }
                },
                enabled = enabled,
                // Read-only for the seconds the recogniser is running. A keystroke landing in the
                // middle of a stream is overwritten by the next partial, so the alternative is a box
                // that silently discards typing — which reads as a broken keyboard.
                readOnly = spoken.isNotBlank(),
                // The form's refusal, drawn where Material draws one and in the colour a reader
                // already associates with it. `supportingText` and not a `Text` underneath, so the
                // message is part of the field's own semantics node and TalkBack reads it WITH the
                // box rather than as a stray paragraph after it.
                isError = errorText != null,
                supportingText = errorText?.let { message -> { Text(message) } },
                minLines = minLines,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                trailingIcon = if (!showMic) null else {
                    {
                        RecordDictationButton(
                            enabled = enabled,
                            onPartial = { partial -> spoken = partial },
                            onCommit = { finalText ->
                                val merged = appendSpokenToRecord(value, finalText)
                                spoken = ""
                                dictationError = null
                                onValueChange(merged)
                            },
                            onError = { message ->
                                spoken = ""
                                dictationError = message
                            },
                        )
                    }
                },
                // The caller's focus target, where there is one. `.let` rather than a nullable
                // modifier expression so a box with no requester carries no extra node at all.
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base -> focusRequester?.let { base.focusRequester(it) } ?: base }
            )
            help?.let { Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp) }
        }

        // Three words, the web's own. It used to be fourteen; both halves of the extra clause
        // described what the designer was already watching happen.
        if (spoken.isNotBlank()) {
            Text("Listening — speak now.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        dictationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        /*
         * THE HONEST ABSENCE. A box that was asked for a microphone and has no recogniser to give it
         * says so, once, rather than showing nothing — because "this screen has no dictation" and
         * "this phone cannot dictate" look identical to somebody who was told the feature exists,
         * and the second one is a fact about their handset they can act on. Only on boxes that ASKED
         * for a mic: printing it under a phone-number field would be twenty copies of a sentence
         * about a control nobody wanted there.
         *
         * **AND NEVER UNDER THE RICH EDITOR, WHICH IS NOT TIDINESS BUT A CONTRADICTION AVOIDED.**
         * That editor owns its own microphone and its own availability rule, and the rule is wider
         * than this one: `rememberDictationAvailable` answers yes when this app merely HAS a
         * repository, because on a stage screen the server can dictate for a handset that cannot. So
         * on a phone with no speech service the editor still draws a mic, and printing "there is no
         * dictation here" underneath it would have the screen arguing with itself. Which of the two
         * is right on a record form is a real open question — see this lane's report — and the
         * answer is not "say both".
         */
        if (dictate && !rich && !available) {
            Text(RECORD_DICTATION_UNAVAILABLE, color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
        below()
    }
}

/**
 * The editor's emitted value, back as a document.
 *
 * A two-line forwarder so the conversion sits beside its inverse rather than inline in a lambda: the
 * editor emits `null` for an empty document and a `{"blocks": …}` object otherwise, and
 * `RichText.fromJson` already reads both of those plus every degenerate shape a stored value can be
 * in. Named rather than inlined because `recordStoredFromDoc(fromJson(next))` at a call site reads
 * as though a STORED value were being parsed, which is the one thing it is not.
 */
private fun fromEditorDoc(next: kotlinx.serialization.json.JsonElement?): RichDoc =
    com.designprototype.workshop.report.fromJson(next)

/**
 * A stored column value as the `JsonElement?` the editor opens.
 *
 * Through the document and back out as canonical JSON rather than handed over as a raw string,
 * because `fromJson` reads a bare string as unformatted prose one paragraph per line — so a saved
 * bullet list would reopen as paragraphs with the "• " glyphs baked into their text and would grow
 * another glyph on the next save. `recordDocFromStored` is where that round trip is decided; this
 * is only the adaptor to the editor's parameter type.
 *
 * Null for an empty document, which is the editor's own spelling of "nothing has been written here".
 */
private fun richSeedOf(stored: String): kotlinx.serialization.json.JsonElement? {
    val doc = recordDocFromStored(stored)
    return if (doc.blocks.isEmpty()) null else toJson(doc)
}
