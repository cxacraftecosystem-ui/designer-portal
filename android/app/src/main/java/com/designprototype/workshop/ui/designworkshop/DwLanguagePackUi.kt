package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.designprototype.workshop.ui.FieldCheckbox
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DW_PACK_NO_PROGRESS_ON_13
import com.designprototype.workshop.data.DW_PACK_REQUEST_IS_A_REQUEST
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwPackOffer
import com.designprototype.workshop.data.DwPackState
import com.designprototype.workshop.data.DwRecognitionSupport
import com.designprototype.workshop.data.dwDownloadCostSentence
import com.designprototype.workshop.data.dwMayAsk
import com.designprototype.workshop.data.dwPackEmptyListSentence
import com.designprototype.workshop.data.dwPackOffer
import com.designprototype.workshop.data.dwPackRows
import com.designprototype.workshop.data.dwPackState
import com.designprototype.workshop.data.dwPackStateLabel
import com.designprototype.workshop.data.dwPackStateSentence
import com.designprototype.workshop.data.dwPackStates
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun dwConnection(context: Context): DwConnection {
    if (!ConnectivityObserver.isOnline(context)) return DwConnection.NONE
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return DwConnection.METERED
    val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        ?: return DwConnection.METERED
    return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
        DwConnection.UNMETERED
    } else {
        DwConnection.METERED
    }
}

private const val PACK_CHECK_TIMEOUT_MS: Long = 15_000

private class DwPackCheckFailure(val code: Int) : Exception()

internal data class DwPackRequestNote(
    val text: String,
    val percent: Int? = null,
    val failed: Boolean = false,
)

@Stable
internal class DwLanguagePackController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    var support by mutableStateOf<DwRecognitionSupport?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    var cannotAsk by mutableStateOf<String?>(null)
        private set

    var connection by mutableStateOf(DwConnection.NONE)
        private set

    val requests = mutableStateMapOf<String, DwPackRequestNote>()

    private var recognizer: SpeechRecognizer? = null

    private var checkJob: Job? = null

    private val platformCanAnswer: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun engine(): SpeechRecognizer? {
        recognizer?.let { return it }
        if (!platformCanAnswer) return null
        val created = runCatching {
            when {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                SpeechRecognizer.isRecognitionAvailable(context) ->
                    SpeechRecognizer.createSpeechRecognizer(context)
                else -> null
            }
        }.getOrNull()
        recognizer = created
        return created
    }

    fun refresh() {
        if (!platformCanAnswer) {
            cannotAsk = dwPackEmptyListSentence(canAsk = false)
            return
        }
        checkJob?.cancel()
        checking = true
        checkJob = scope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        connection = dwConnection(context)
        val engine = engine()
        if (engine == null) {
            checking = false
            cannotAsk = "No speech recogniser on this phone."
            return
        }
        val answer = withTimeoutOrNull(PACK_CHECK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<DwRecognitionSupport>> { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkSupport(
                        engine = engine,
                        onAnswer = { answer -> if (continuation.isActive) continuation.resume(Result.success(answer)) },
                        onFailure = { code -> if (continuation.isActive) continuation.resume(Result.failure(DwPackCheckFailure(code))) },
                    )
                } else {
                    continuation.resume(Result.failure(DwPackCheckFailure(SpeechRecognizer.ERROR_CLIENT)))
                }
            }
        }
        checking = false
        val landed = answer?.getOrNull()
        when {
            landed != null -> {
                support = landed
                cannotAsk = null
                DW_DICTATION_LANGUAGES.forEach { language ->
                    if (dwPackState(language.tag, landed) == DwPackState.INSTALLED) {
                        DwDictationRun.forgetRefusal(language.tag)
                    }
                }
            }
            answer != null -> {
                val code = (answer.exceptionOrNull() as? DwPackCheckFailure)?.code
                cannotAsk = "Couldn't list language packs. Dictation still works."
            }
            else -> {
                cannotAsk = "Speech service didn't respond. Dictation still works."
            }
        }
    }

    fun ask(tag: String, label: String) {
        if (!platformCanAnswer) return
        val engine = engine() ?: return
        val intent = downloadIntent(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requests[tag] = DwPackRequestNote("Asked the phone for $label…")
            askWithListener(engine, intent, tag, label)
        } else {
            requests[tag] = DwPackRequestNote(DW_PACK_NO_PROGRESS_ON_13)
            runCatching { engine.triggerModelDownload(intent) }.onFailure {
                requests[tag] = DwPackRequestNote(
                    "Request for $label refused. Add it in the phone's speech settings.",
                    failed = true,
                )
            }
        }
    }

    fun release() {
        checkJob?.cancel()
        checkJob = null
        runCatching { recognizer?.destroy() }
        recognizer = null
        checking = false
    }

    private fun probeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun downloadIntent(tag: String): Intent = probeIntent().apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkSupport(
        engine: SpeechRecognizer,
        onAnswer: (DwRecognitionSupport) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                onAnswer(
                    DwRecognitionSupport(
                        installedOnDevice = recognitionSupport.installedOnDeviceLanguages.toList(),
                        pendingOnDevice = recognitionSupport.pendingOnDeviceLanguages.toList(),
                        supportedOnDevice = recognitionSupport.supportedOnDeviceLanguages.toList(),
                        online = recognitionSupport.onlineLanguages.toList(),
                    )
                )
            }

            override fun onError(error: Int) = onFailure(error)
        }
        runCatching {
            engine.checkRecognitionSupport(probeIntent(), ContextCompat.getMainExecutor(context), callback)
        }.onFailure {
            onFailure(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun askWithListener(engine: SpeechRecognizer, intent: Intent, tag: String, label: String) {
        val listener = object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) {
                requests[tag] = DwPackRequestNote("Downloading $label — $completedPercent%", percent = completedPercent)
            }

            override fun onSuccess() {
                requests[tag] = DwPackRequestNote(
                    "$label installed — works offline."
                )
                refresh()
            }

            override fun onScheduled() {
                requests[tag] = DwPackRequestNote(
                    "Android queued $label. Tap “Check again” later."
                )
            }

            override fun onError(error: Int) {
                requests[tag] = DwPackRequestNote(
                    "Couldn't fetch $label. Check the connection, or add it in speech settings.",
                    failed = true,
                )
            }
        }
        runCatching { engine.triggerModelDownload(intent, ContextCompat.getMainExecutor(context), listener) }
            .onFailure {
                requests[tag] = DwPackRequestNote(
                    "Request for $label refused.",
                    failed = true,
                )
            }
    }
}

@Composable
internal fun rememberDwLanguagePacks(active: Boolean): DwLanguagePackController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { DwLanguagePackController(context, scope) }
    DisposableEffect(active) {
        if (active) controller.refresh()
        onDispose { if (active) controller.release() }
    }
    return controller
}

@Composable
internal fun DwLanguagePackList(controller: DwLanguagePackController, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val states = dwPackStates(DW_DICTATION_LANGUAGES.map { it.tag }, controller.support)
    val rows = remember(states) { dwPackRows(states) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controller.checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Checking…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        val askable = DW_DICTATION_LANGUAGES.filter { language ->
            val note = controller.requests[language.tag]
            dwMayAsk(
                offer = dwPackOffer(states[language.tag] ?: DwPackState.UNKNOWN, controller.connection),
                requested = note != null,
                refused = note?.failed == true,
            )
        }
        val reason = controller.cannotAsk
        if (!controller.checking) {
            when {
                reason != null -> Text(reason, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                rows.isEmpty() -> Text(
                    dwPackEmptyListSentence(canAsk = true),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        rows.forEach { tag ->
            val language = DW_DICTATION_LANGUAGES.first { it.tag == tag }
            DwLanguagePackRow(
                label = language.label,
                state = states[tag] ?: DwPackState.UNKNOWN,
                selectable = language in askable,
                checked = tag in selected,
                note = controller.requests[tag],
                onCheckedChange = { ticked ->
                    selected = if (ticked) selected + tag else selected - tag
                },
            )
        }

        val ticked = askable.filter { it.tag in selected }
        if (askable.isNotEmpty()) {
            Text(dwDownloadCostSentence(controller.connection), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            Text(DW_PACK_REQUEST_IS_A_REQUEST, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        val downloadable = askable.size

        ActionRow {
            if (downloadable > 0) {
                Button(
                    onClick = {
                        ticked.forEach { controller.ask(it.tag, it.label) }
                        selected = emptySet()
                    },
                    enabled = ticked.isNotEmpty(),
                ) {
                    Text(
                        when (ticked.size) {
                            0 -> "Tick a language to download"
                            1 -> "Download 1 pack"
                            else -> "Download ${ticked.size} packs"
                        }
                    )
                }
            }
            OutlinedButton(onClick = { controller.refresh() }, enabled = !controller.checking) {
                Text("Check again")
            }
        }
    }
}

@Composable
private fun DwLanguagePackRow(
    label: String,
    state: DwPackState,
    selectable: Boolean,
    checked: Boolean,
    note: DwPackRequestNote?,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.field.hairline, shape)
            .background(MaterialTheme.field.surface50, shape)
            .then(
                if (selectable) {
                    Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectable) {
                FieldCheckbox(checked = checked, onCheckedChange = null)
            }
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                dwPackStateLabel(state),
                color = if (state == DwPackState.INSTALLED) MaterialTheme.field.success else MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        note?.let { request ->
            Text(
                request.text,
                color = if (request.failed) MaterialTheme.colorScheme.error else MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall
            )
            request.percent?.let { percent ->
                FieldLinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun DwLanguagePackSettings(
    controller: DwLanguagePackController = rememberDwLanguagePacks(active = true),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DwLanguagePackList(controller)
    }
}

@Composable
internal fun DwLanguagePackOfferDialog(
    controller: DwLanguagePackController,
    tag: String,
    label: String,
    onDismiss: () -> Unit,
) {
    val state = dwPackState(tag, controller.support)
    val offer = dwPackOffer(state, controller.connection)
    val note = controller.requests[tag]
    val canDownload = dwMayAsk(offer, requested = note != null, refused = note?.failed == true)
    val stillAsking = controller.checking && controller.support == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label dictation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stillAsking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "Checking $label…",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Text(dwPackStateSentence(label, state), color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
                    if (offer == DwPackOffer.DOWNLOAD) {
                        Text(dwDownloadCostSentence(controller.connection), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                        Text(DW_PACK_REQUEST_IS_A_REQUEST, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                note?.let {
                    Text(
                        it.text,
                        color = if (it.failed) MaterialTheme.colorScheme.error else MaterialTheme.field.body,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (canDownload) {
                TextButton(onClick = { controller.ask(tag, label) }) {
                    Text(if (note?.failed == true) "Try $label again" else "Download $label")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (canDownload) {
            { TextButton(onClick = onDismiss) { Text("Not now") } }
        } else {
            null
        },
    )
}
