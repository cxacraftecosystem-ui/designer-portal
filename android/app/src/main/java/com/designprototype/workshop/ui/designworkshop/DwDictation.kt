package com.designprototype.workshop.ui.designworkshop

import android.Manifest
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import com.designprototype.workshop.transcript.ui.TranscriptEntry
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import com.designprototype.workshop.ui.MediaDownloadButton
import com.designprototype.workshop.ui.MediaDownloadNotice
import com.designprototype.workshop.ui.MediaDownloader
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.rememberMediaDownloader
import com.designprototype.workshop.ui.field

@Immutable
data class DwDictationLanguage(val tag: String, val label: String)

internal val DW_DICTATION_LANGUAGES: List<DwDictationLanguage> = listOf(
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

private var lastDictationTag: String = DW_DICTATION_LANGUAGES.first().tag

@Composable
internal fun rememberDictationAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) ||
            DwDictationRun.repository() != null
    }
}

internal object DwDictationAdornment {
    const val TARGET_DP = 48

    fun label(state: DwDictationState): String = when (state) {
        DwDictationState.IDLE -> "Dictate"
        DwDictationState.RECORDING -> "Stop and transcribe"
        DwDictationState.UPLOADING -> "Writing down what you dictated"
        else -> "Stop dictating"
    }

    fun languageState(languageLabel: String): String = "In $languageLabel"

    fun languageButton(languageLabel: String): String = "Dictation language: $languageLabel"

    const val LONG_PRESS_LABEL = "Choose dictation language"

    fun filled(state: DwDictationState): Boolean = state != DwDictationState.IDLE
}

internal enum class DwDictationState {
    IDLE,

    LISTENING,

    WORKING,

    RECORDING,

    UPLOADING,

    RECORDING_FOR_THIS_PHONE,

    TRANSCRIBING_ON_THIS_PHONE,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DwDictationButton(
    enabled: Boolean,
    onPartial: (String) -> Unit,
    onCommit: (String) -> Unit,
    onError: (String) -> Unit,
    onKept: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(DwDictationState.IDLE) }
    var showLanguages by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf(lastDictationTag) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    var plan by remember { mutableStateOf<DwDictationPlan?>(null) }

    val recorder = remember { DwDictationRecorder() }

    val pcm = remember { DwAsrPcmRecorder() }

    var localJob by remember { mutableStateOf<Job?>(null) }

    var localNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { DwAsrModelRun.warm(context, scope) }

    var uploadJob by remember { mutableStateOf<Job?>(null) }

    var sendingClip by remember { mutableStateOf<File?>(null) }
    val clipDownloader = rememberMediaDownloader()

    var sendingUnder by remember { mutableStateOf<String?>(null) }

    var packOffer by remember { mutableStateOf<String?>(null) }

    val packs = rememberDwLanguagePacks(active = showLanguages || packOffer != null)

    val currentPartial by rememberUpdatedState(onPartial)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentError by rememberUpdatedState(onError)
    val currentKept by rememberUpdatedState(onKept)

    fun release() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        uploadJob?.cancel()
        uploadJob = null
        recorder.discard()
        pcm.stop()
        localJob?.cancel()
        localJob = null
        localNote = null
        sendingUnder = null
        state = DwDictationState.IDLE
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
                currentPartial("")
                if (text.isNotBlank()) currentCommit(text)
                release()
            }

            override fun onError(error: Int) {
                currentPartial("")
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Nothing recognised. Check the language and speak closer."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech heard. Start speaking right after tapping the mic."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission needed. Allow it in Settings › Apps."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        onNetworkFailure()
                        return
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Speech service busy. Try again shortly."
                    SpeechRecognizer.ERROR_AUDIO ->
                        "Couldn't read the microphone — another app may be using it."
                    13, 12 -> {
                        onLanguageUnavailable()
                        return
                    }
                    else ->
                        "Dictation stopped. Try again or type."
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

    fun conditionsNow(): DwDictationConditions {
        val cap = DwDictationRun.capView(context)
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
            appModelServesLanguage = DwAsrModelRun.servesLanguage(tag),
            appModelRefusedLanguage = DwAsrModelRun.hasRefused(tag),
            tier3Consent = workshop.consent,
            dailyCapSpent = cap.spent,
            dailyCapLimit = cap.limit,
            workshopOnServer = workshop.serverId != null,
        )
    }

    var beginAt: (DwDictationRung) -> Unit = {}

    fun advance(from: DwDictationRung) {
        val next = plan?.after(from)
        if (next != null) {
            beginAt(next)
            return
        }
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
                if (onDevice) DwDictationRun.engineRefused(tag)
                advance(here)
            },
            onNetworkFailure = {
                advance(here)
            },
        )
        if (built == null) {
            release()
            currentError("Couldn't start speech recognition.")
            return
        }
        recognizer = built
        state = DwDictationState.WORKING
        runCatching { built.startListening(intentFor()) }.onFailure {
            release()
            currentError("Couldn't start speech recognition.")
        }
    }

    fun finishRecording() {
        if (state != DwDictationState.RECORDING) return
        val clip = recorder.stop()
        if (clip == null) {
            release()
            currentError(DW_DICTATION_NOTHING_RECORDED)
            return
        }
        dwDictationOversize(clip.length())?.let { refusal ->
            runCatching { clip.delete() }
            release()
            currentError(refusal)
            return
        }
        val repository = DwDictationRun.repository()
        val sendUnder = sendingUnder
        if (repository == null || sendUnder == null) {
            runCatching { clip.delete() }
            advance(DwDictationRung.SERVER_DICTATE)
            return
        }
        state = DwDictationState.UPLOADING
        sendingClip = clip
        uploadJob = scope.launch {
            var answer: DwDictateDto? = null
            var failure: Throwable? = null
            try {
                answer = repository.designWorkshopDictate(sendUnder, clip, tag)
            } catch (e: CancellationException) {
                runCatching { clip.delete() }
                sendingClip = null
                throw e
            } catch (e: Throwable) {
                failure = e
            }
            runCatching { clip.delete() }
            sendingClip = null
            uploadJob = null

            val dto = answer
            if (dto != null) {
                DwDictationRun.learnAllowance(context, dto)
                val text = dto.text.trim()
                if (text.isNotEmpty()) {
                    currentPartial("")
                    release()
                    currentCommit(text)
                    dwDictationKeptMediaId(dto)?.let { currentKept(it) }
                } else {
                    release()
                    currentError(dwDictationServerAnswerSentence(dto.status, dto.message))
                }
                return@launch
            }

            if (failure is DwDictationNotConfigured) {
                DwDictationRun.serverSaidNotConfigured()
                val next = plan?.after(DwDictationRung.SERVER_DICTATE)
                release()
                currentError(
                    if (next != null) DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN else DW_DICTATION_NOT_CONFIGURED
                )
                return@launch
            }
            if (failure is DwDictationConsentRefused) {
                release()
                currentError(failure.detail ?: DW_DICTATION_CONSENT_REFUSED)
                return@launch
            }
            if (failure is DwDictationCapRefused) {
                if (failure.transientThrottle) {
                    release()
                    currentError(failure.detail ?: DW_DICTATION_BUSY)
                    return@launch
                }
                DwDictationRun.learnCapRefusal(context, failure)
                val next = plan?.after(DwDictationRung.SERVER_DICTATE)
                val nothingLeft = failure.detail ?: dwDictationNothingLeftSentence(conditionsNow())
                release()
                currentError(if (next != null) DW_DICTATION_CAP_SPENT_SAY_AGAIN else nothingLeft)
                return@launch
            }
            val http = failure as? HttpException
            release()
            currentError(
                when {
                    http == null -> DW_DICTATION_UPLOAD_FAILED
                    http.code() == 503 -> DW_DICTATION_SERVER_UNREACHABLE
                    else -> http.apiErrorMessage(DW_DICTATION_UPLOAD_FAILED)
                }
            )
        }
    }

    fun startRecording() {
        release()
        val workshop = DwDictationRun.publishedWorkshop().serverId
        if (workshop == null) {
            advance(DwDictationRung.SERVER_DICTATE)
            return
        }
        sendingUnder = workshop
        val refusal = recorder.start(context) { finishRecording() }
        if (refusal != null) {
            release()
            currentError(refusal)
            return
        }
        state = DwDictationState.RECORDING
    }

    fun startOnDeviceModel() {
        release()
        val plan = DW_TIER1_CATALOGUE.firstOrNull { it.modelId == DwAsrModelRun.status().model?.modelId }
        localNote = plan?.let { dwModelWaitSentence(it, pcm.maxMillis) }
        val refusal = pcm.start()
        if (refusal != null) {
            release()
            currentError(refusal)
            return
        }
        state = DwDictationState.RECORDING_FOR_THIS_PHONE
        localJob = scope.launch {
            val samples = withContext(Dispatchers.IO) { pcm.readUntilStopped() }
            pcm.stop()
            if (samples.isEmpty()) {
                release()
                currentError(DW_DICTATION_NOTHING_RECORDED)
                return@launch
            }
            state = DwDictationState.TRANSCRIBING_ON_THIS_PHONE
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
                        release()
                        currentError(
                            "No words found. Speak closer or type."
                        )
                    }
                }

                is DwAsrSpeechModel.Outcome.Refused -> {
                    DwAsrModelRun.recordRefusal(tag)
                    release()
                    currentError(
                        "The on-phone model couldn't run. Tap the mic to try another way."
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
            DwDictationRung.APP_SPEECH_MODEL -> startOnDeviceModel()
        }
    }

    fun beginWalk() {
        val decided = dwDictationLadder(conditionsNow())
        plan = decided
        val first = decided.first
        if (first == null) {
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
            currentError("Dictation needs the microphone.")
        }
    }

    DisposableEffect(Unit) {
        DwDictationRun.sweepScratchOnce(context)
        onDispose {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            uploadJob?.cancel()
            recorder.discard()
        }
    }

    val languageLabel = DW_DICTATION_LANGUAGES.firstOrNull { it.tag == tag }?.label ?: tag
    val micEnabled = enabled &&
                state != DwDictationState.UPLOADING &&
                state != DwDictationState.TRANSCRIBING_ON_THIS_PHONE

    fun chooseLanguage(chosen: String) {
        if (chosen.isNotBlank()) {
            tag = chosen
            lastDictationTag = chosen
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

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(DwDictationAdornment.TARGET_DP.dp)
                .clip(CircleShape)
                .background(
                    if (DwDictationAdornment.filled(state)) MaterialTheme.colorScheme.primary else Color.Transparent
                )
                .dwClaimsHold()
                .combinedClickable(
                    enabled = micEnabled,
                    role = Role.Button,
                    onLongClickLabel = DwDictationAdornment.LONG_PRESS_LABEL,
                    onLongClick = { if (state == DwDictationState.IDLE && enabled) showLanguages = true },
                    onClick = {
                        when (state) {
                            DwDictationState.LISTENING, DwDictationState.WORKING ->
                                runCatching { recognizer?.stopListening() }
                            DwDictationState.RECORDING -> finishRecording()
                            DwDictationState.RECORDING_FOR_THIS_PHONE -> pcm.requestStop()
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
                    },
                )
                .semantics {
                    contentDescription = DwDictationAdornment.label(state)
                    stateDescription = DwDictationAdornment.languageState(labelOf(tag))
                },
        ) {
            Icon(
                if (state == DwDictationState.IDLE) Icons.Filled.Mic else Icons.Filled.Stop,
                contentDescription = null,
                tint = if (DwDictationAdornment.filled(state)) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.field.muted
                },
                modifier = Modifier.size(20.dp)
            )
        }
        if (state == DwDictationState.IDLE) {
            Box {
                IconButton(
                    onClick = { showLanguages = !showLanguages },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription = DwDictationAdornment.languageButton(languageLabel)
                    },
                ) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.field.muted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(expanded = showLanguages, onDismissRequest = { showLanguages = false }) {
                    DW_DICTATION_LANGUAGES.forEach { option ->
                        val chosen = option.tag == tag
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                )
                            },
                            onClick = { chooseLanguage(option.tag) },
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                selected = chosen
                            },
                        )
                    }
                }
            }
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

    if (state == DwDictationState.RECORDING || state == DwDictationState.UPLOADING) {
        DwServerDictationPanel(
            label = labelOf(tag),
            uploading = state == DwDictationState.UPLOADING,
            sendingClip = sendingClip?.takeIf { state == DwDictationState.UPLOADING },
            downloader = clipDownloader,
            onStop = { finishRecording() },
            onCancel = {
                currentPartial("")
                release()
            },
        )
    }

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

@Composable
private fun DwOnDeviceModelPanel(
    label: String,
    note: String?,
    transcribing: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
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
                            elapsed = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            if (elapsed < 60) {
                                "Writing it down — $elapsed sec so far."
                            } else {
                                "Transcribing — ${elapsed / 60} min ${elapsed % 60} sec."
                            },
                            color = MaterialTheme.field.body,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Text(
                        "Recording — speak now, then press Stop.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                note?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
                if (!transcribing) {
                    Text(
                        "Stops itself after 90 seconds.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (!transcribing) TextButton(onClick = onStop) { Text("Stop") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(if (transcribing) "Cancel" else "Discard") }
        },
    )
}

@Composable
private fun DwServerDictationPanel(
    label: String,
    uploading: Boolean,
    sendingClip: File?,
    downloader: MediaDownloader,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Dictating $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uploading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "Writing down what you said…",
                            color = MaterialTheme.field.body,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    sendingClip?.let { clip ->
                        MediaDownloadButton(
                            fileName = "voice note",
                            onDownload = {
                                downloader.save(Uri.fromFile(clip), null, "audio/mp4", clip.lastModified())
                            },
                        )
                    }
                    MediaDownloadNotice(downloader)
                } else {
                    Text(
                        "Recording — speak now, then press Stop.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (!uploading) {
                    Text(
                        "Text appears after you tap Stop.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Stops itself after four minutes.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (!uploading) TextButton(onClick = onStop) { Text("Stop") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(if (uploading) "Cancel" else "Discard") }
        },
    )
}

@Composable
internal fun DwDictationKept(mediaId: String?) {
    if (mediaId != null) TranscriptEntry(mediaId = mediaId, audio = null)
}

internal fun dwDictationKeptMediaId(dto: DwDictateDto): String? =
    dto.mediaId?.takeIf { it.isNotBlank() && dto.text.isNotBlank() }

@Composable
internal fun DwDictationHint(listening: Boolean) {
    if (!listening) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Listening — speak now.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun dictatable(type: com.designprototype.workshop.data.DwFieldType): Boolean =
    type == com.designprototype.workshop.data.DwFieldType.TEXT ||
        type == com.designprototype.workshop.data.DwFieldType.LONG_TEXT

internal fun dictationUnavailableNote(context: Context): String? = when {
    runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) -> null
    DwDictationRun.repository() != null -> null
    else -> "Dictation isn't available right now. Type instead."
}
