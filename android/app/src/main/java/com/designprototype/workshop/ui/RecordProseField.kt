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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
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

private var lastRecordDictationTag: String = DW_DICTATION_LANGUAGES.first().tag

private enum class RecordDictationState {
    IDLE,

    LISTENING,

    WORKING,

    RECORDING_FOR_THIS_PHONE,

    TRANSCRIBING_ON_THIS_PHONE,
}

@Composable
private fun rememberRecordDictationAvailable(): Boolean {
    val context = LocalContext.current
    val platform = remember(context) { RecordSpeechServices.platformAvailable(context) }
    return platform || DwAsrModelRun.status().model != null
}

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

private const val RECORD_DICTATION_UNAVAILABLE: String =
    "There is no speech recogniser on this phone. Type the answer in."

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

    var plan by remember { mutableStateOf<List<DwDictationRung>>(emptyList()) }

    val pcm = remember { DwAsrPcmRecorder() }

    var localJob by remember { mutableStateOf<Job?>(null) }

    var localNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { DwAsrModelRun.warm(context, scope) }

    val packs = rememberDwLanguagePacks(active = showLanguages)

    var packOffer by remember { mutableStateOf<String?>(null) }

    val currentPartial by rememberUpdatedState(onPartial)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentError by rememberUpdatedState(onError)

    fun labelOf(chosen: String): String =
        DW_DICTATION_LANGUAGES.firstOrNull { it.tag == chosen }?.label ?: chosen

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

    fun advance(from: DwDictationRung) {
        val index = plan.indexOf(from)
        val next = if (index < 0) null else plan.getOrNull(index + 1)
        if (next != null) {
            beginAt(next)
            return
        }
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
                        "Dictation stopped (code $error). Try again or type."
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

    fun listen(onDevice: Boolean) {
        val here =
            if (onDevice) DwDictationRung.ON_DEVICE_PACK else DwDictationRung.NETWORK_RECOGNISER
        release()
        val built = buildRecognizer(
            onDevice,
            onLanguageUnavailable = {
                if (onDevice) com.designprototype.workshop.data.DwDictationRun.engineRefused(tag)
                advance(here)
            },
            onNetworkFailure = { advance(here) },
        )
        if (built == null) {
            release()
            currentError("Couldn't start speech recognition.")
            return
        }
        recognizer = built
        state = RecordDictationState.WORKING
        runCatching { built.startListening(intentFor()) }.onFailure {
            release()
            currentError("Couldn't start speech recognition.")
        }
    }

    fun startOnDeviceModel() {
        release()
        val modelPlan = DW_TIER1_CATALOGUE
            .firstOrNull { it.modelId == DwAsrModelRun.status().model?.modelId }
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
                        "On-phone model couldn't run: ${outcome.detail}. Tap the mic to try another way."
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
            DwDictationRung.SERVER_DICTATE -> advance(DwDictationRung.SERVER_DICTATE)
        }
    }

    fun beginWalk() {
        val conditions = conditionsNow()
        val rungs = recordDictationRungs(dwDictationLadder(conditions))
        plan = rungs
        val first = rungs.firstOrNull()
        if (first == null) {
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
            currentError("Dictation needs the microphone.")
        }
    }

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
            enabled = enabled && state != RecordDictationState.TRANSCRIBING_ON_THIS_PHONE,
            onClick = {
                when (state) {
                    RecordDictationState.LISTENING, RecordDictationState.WORKING ->
                        runCatching { recognizer?.stopListening() }
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
                    RecordDictationState.RECORDING_FOR_THIS_PHONE ->
                        "Stop and transcribe on this phone"
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
                Text(labelOf(tag), color = MaterialTheme.field.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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

@Composable
private fun RecordOnDeviceModelPanel(
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
                            elapsed =
                                ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
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
                        "Stays on this phone. Stops after 90 seconds.",
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
fun RecordProseField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    enabled: Boolean = true,
    rich: Boolean = false,
    dictate: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization? = null,
    resetKey: Any? = null,
    help: String? = null,
    errorText: String? = null,
    focusRequester: FocusRequester? = null,
    below: @Composable () -> Unit = {},
    keepFormatting: Boolean = false,
) {
    var spoken by remember(resetKey) { mutableStateOf("") }

    var dictationError by remember(resetKey) { mutableStateOf<String?>(null) }

    val available = rememberRecordDictationAvailable()
    val showMic = dictate && available

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (rich) {
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
                    val doc = fromEditorDoc(next)
                    val stored = (
                        if (keepFormatting) recordStoredKeepingFormat(doc) else recordStoredFromDoc(doc)
                        ).orEmpty()
                    mine = stored
                    onValueChange(stored)
                },
                enabled = enabled,
                label = label.orEmpty(),
                help = help,
                onError = { message -> dictationError = message },
            )
            if (!keepFormatting) {
                Text(RECORD_RICH_TEXT_NOTE, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val focusManager = LocalFocusManager.current
            OutlinedTextField(
                value = if (spoken.isBlank()) value else appendSpokenToRecord(value, spoken),
                onValueChange = { raw -> if (spoken.isBlank()) onValueChange(raw) },
                label = label?.let { { Text(requiredMarked(it)) } },
                placeholder = placeholder?.let {
                    { Text(it, color = MaterialTheme.field.placeholder) }
                },
                enabled = enabled,
                readOnly = spoken.isNotBlank(),
                isError = errorText != null,
                supportingText = errorText?.let { message -> { Text(message) } },
                minLines = minLines,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    capitalization = capitalization
                        ?: if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                    imeAction = if (minLines == 1) ImeAction.Next else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base -> focusRequester?.let { base.focusRequester(it) } ?: base }
                    .let { base ->
                        when (keyboardType) {
                            KeyboardType.Email -> base.semantics { contentType = ContentType.EmailAddress }
                            KeyboardType.Phone -> base.semantics { contentType = ContentType.PhoneNumber }
                            else -> base
                        }
                    }
            )
            help?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
        }

        if (spoken.isNotBlank()) {
            Text("Listening — speak now.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        dictationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (dictate && !rich && !available) {
            Text(RECORD_DICTATION_UNAVAILABLE, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        below()
    }
}

private fun fromEditorDoc(next: kotlinx.serialization.json.JsonElement?): RichDoc =
    com.designprototype.workshop.report.fromJson(next)

private fun richSeedOf(stored: String): kotlinx.serialization.json.JsonElement? {
    val doc = recordDocFromStored(stored)
    return if (doc.blocks.isEmpty()) null else toJson(doc)
}
