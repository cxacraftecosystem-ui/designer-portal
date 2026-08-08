package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
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
import com.designprototype.workshop.data.DW_PACK_CANNOT_ASK_SENTENCE
import com.designprototype.workshop.data.DW_PACK_NO_CONNECTION_SENTENCE
import com.designprototype.workshop.data.DW_PACK_NO_PROGRESS_ON_13
import com.designprototype.workshop.data.DW_PACK_OFFER_BLURB
import com.designprototype.workshop.data.DW_PACK_REQUEST_IS_A_REQUEST
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwPackOffer
import com.designprototype.workshop.data.DwPackState
import com.designprototype.workshop.data.DwRecognitionSupport
import com.designprototype.workshop.data.dwDownloadCostSentence
import com.designprototype.workshop.data.dwPackOffer
import com.designprototype.workshop.data.dwPackState
import com.designprototype.workshop.data.dwPackStateLabel
import com.designprototype.workshop.data.dwPackStateSentence
import com.designprototype.workshop.data.dwPackStates
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Offering the nineteen dictation language packs, instead of failing on the one the designer picked.
 *
 * ── THE ANDROID HALF OF `data/DwLanguagePacks.kt` ─────────────────────────────────────────────
 *
 * The decision — installed, downloadable, or not supported by this device at all — is pure Kotlin
 * over in `data/`, where the desktop JVM can test it. Everything here is the part that cannot be:
 * two platform calls, a connectivity read, and three surfaces that draw the answer.
 *
 * ── WHAT THE PLATFORM ACTUALLY PROMISES, WHICH IS LESS THAN IT SOUNDS ─────────────────────────
 *
 * `SpeechRecognizer.checkRecognitionSupport(intent, executor, callback)` (API 33) answers with a
 * `RecognitionSupport` carrying four language lists — installed on device, pending, supported (i.e.
 * NOT on the phone yet), and online. That is how this app stops finding out about a missing pack by
 * provoking ERROR_LANGUAGE_UNAVAILABLE, which is exactly how it used to find out.
 *
 * `SpeechRecognizer.triggerModelDownload(intent)` (API 33) is documented as an ATTEMPT: "Attempts to
 * download the support for the given recognizerIntent. This might trigger user interaction to
 * approve the download. Callers can verify the status of the request via checkRecognitionSupport."
 * It is a REQUEST, not a promise. On API 33 there is no callback at all, so the only truthful thing
 * to show afterwards is that the request went out and the list is where you find out (see
 * [DW_PACK_NO_PROGRESS_ON_13]). API 34 added the
 * `triggerModelDownload(intent, executor, ModelDownloadListener)` overload, whose `onScheduled()`
 * means "queued, and there will be no further updates on this listener" — which is a third outcome,
 * not a failure, and is worded as one below.
 *
 * NEITHER CALL REPORTS A SIZE, anywhere. So none is printed. This runs on prepaid mobile data in a
 * district town and an invented "≈40 MB" beside somebody's data bundle is worse than saying nothing.
 *
 * ── NOTHING HERE EVER DOWNLOADS BY ITSELF ─────────────────────────────────────────────────────
 *
 * There is exactly one call to `triggerModelDownload` in the app, in [DwLanguagePackController.ask],
 * and every route to it starts at a button a designer pressed with a language named on it. The
 * first-run offer starts with NOTHING selected, the metered warning is printed above the button
 * rather than after the tap, and a download is never offered where there is no connection to carry
 * it — a control that cannot work is worse than an absent one, because the designer who taps it
 * concludes the app is broken rather than that they are offline.
 *
 * ── AND IT NEVER BLOCKS GETTING TO WORK ───────────────────────────────────────────────────────
 *
 * The first-run surface is a dismissible CARD on the dashboard, not a dialog. A modal on first
 * launch that must be dismissed before anything can be recorded would be worse than not shipping
 * this at all. The one dialog in this file ([DwLanguagePackOfferDialog]) opens only in direct
 * response to a designer choosing a language whose pack is missing, and it never opens when the
 * pack is present or when the device cannot be asked.
 */

// ---------------------------------------------------------------------------------------------
// Connectivity — one rung finer than the app's existing online/offline check
// ---------------------------------------------------------------------------------------------

/**
 * Whether this handset can fetch a pack, and whether doing so costs money.
 *
 * The online/offline half is [ConnectivityObserver.isOnline] and not a second copy of it: the app
 * must not be able to think it is offline for the outbox and online for a download.
 *
 * WHEN THE METER CANNOT BE READ THE ANSWER IS [DwConnection.METERED], deliberately. Getting it wrong
 * in that direction shows a cost warning on Wi-Fi, which is a wasted sentence; getting it wrong the
 * other way spends a prepaid bundle without saying so.
 */
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

// ---------------------------------------------------------------------------------------------
// The controller
// ---------------------------------------------------------------------------------------------

/**
 * How long the platform gets to answer `checkRecognitionSupport` before the answer is "unknown".
 *
 * Fifteen seconds: long enough for a cold bind to another app's recognition service on the slowest
 * handset in the fleet, short enough that a designer standing in a courtyard is not left holding a
 * spinner. This is the ONLY thing standing between a wedged OEM recogniser and a settings list that
 * never finishes loading.
 */
private const val PACK_CHECK_TIMEOUT_MS: Long = 15_000

/** Carries the platform's error code out of the callback and into the coroutine that is waiting. */
private class DwPackCheckFailure(val code: Int) : Exception()

/** What has happened to one download request in this sitting. Never persisted; it is about now. */
internal data class DwPackRequestNote(
    val text: String,
    /** 0-100 while `ModelDownloadListener.onProgress` is firing (API 34 only), else null. */
    val percent: Int? = null,
    val failed: Boolean = false,
)

/**
 * One recogniser, the answer it gave, and the requests made through it.
 *
 * Created by [rememberDwLanguagePacks] and alive only while a surface that shows packs is on screen.
 * That scoping is the point: binding a `SpeechRecognizer` is an IPC handshake with another app's
 * service, and doing it once per dictation button would mean one per FIELD on a stage screen. Here
 * it happens when a designer opens the language list, opens Settings, or expands the first-run
 * offer — three deliberate acts, never on a cold start.
 *
 * Every method must be called from the main thread, which is where composition runs.
 * `SpeechRecognizer`'s class documentation says its methods "must be invoked only from the main
 * application thread", and the callbacks below are posted back to the main executor for the same
 * reason.
 */
@Stable
internal class DwLanguagePackController(
    private val context: Context,
    /** The composition's own scope, so a check dies with the surface that asked for it. */
    private val scope: CoroutineScope,
) {

    /** The device's own answer, or null while it has not been obtained. Null means UNKNOWN. */
    var support by mutableStateOf<DwRecognitionSupport?>(null)
        private set

    /** True while `checkRecognitionSupport` is outstanding. */
    var checking by mutableStateOf(false)
        private set

    /**
     * Why this phone cannot be asked, in a sentence, or null when it can.
     *
     * Set rather than left implicit because "we do not know" is a state a designer has to be able to
     * READ. Silence would be indistinguishable from "nothing is installed".
     */
    var cannotAsk by mutableStateOf<String?>(null)
        private set

    var connection by mutableStateOf(DwConnection.NONE)
        private set

    /** Per-language outcome of a request made in this sitting, keyed by BCP-47 tag. */
    val requests = mutableStateMapOf<String, DwPackRequestNote>()

    private var recognizer: SpeechRecognizer? = null

    /** Can this build's platform be asked at all? API 33 added `checkRecognitionSupport`. */
    private val platformCanAnswer: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * The recogniser to ask, created once and kept until [release].
     *
     * The ON-DEVICE engine is preferred because its packs are the subject: `getOnlineLanguages()` is
     * documented to come back empty from an on-device recogniser, so an empty online list means "not
     * asked" rather than "nothing online" — which is exactly why `DwRecognitionSupport` can only ever
     * produce NETWORK_ONLY when some engine really did answer with online languages.
     */
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

    /**
     * Ask the phone what it has. Safe to call again; the answer is a snapshot and packs move.
     *
     * Re-read on every call rather than cached for the process, because the whole point of the
     * "Check again" control is that a pack requested five minutes ago may have landed since — and on
     * API 33 that control is the ONLY way to find out.
     */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    /**
     * The check itself, with a deadline.
     *
     * THE DEADLINE IS NOT DEFENSIVE PROGRAMMING, it is the difference between "unknown" and a
     * spinner that never stops. `checkRecognitionSupport` hands its answer to a callback supplied by
     * another app's service; if that service is wedged — and the recognisers on budget handsets are
     * the ones that wedge — nothing ever arrives, `checking` stays true, the "Check again" button
     * stays disabled and the designer is left watching a wheel with no way out. On the deadline the
     * state becomes the honest one this whole feature is built on: we asked, and we do not know.
     */
    private suspend fun refreshNow() {
        connection = dwConnection(context)
        if (!platformCanAnswer) {
            cannotAsk = DW_PACK_CANNOT_ASK_SENTENCE
            return
        }
        val engine = engine()
        if (engine == null) {
            cannotAsk = "This phone has no speech recogniser installed, so there are no language " +
                "packs to manage here and no dictation to use them."
            return
        }
        checking = true
        val answer = withTimeoutOrNull(PACK_CHECK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<DwRecognitionSupport>> { continuation ->
                checkSupport(
                    engine = engine,
                    // `isActive` guards a service that calls back twice, which would otherwise throw
                    // IllegalStateException out of somebody else's binder thread.
                    onAnswer = { answer -> if (continuation.isActive) continuation.resume(Result.success(answer)) },
                    onFailure = { code -> if (continuation.isActive) continuation.resume(Result.failure(DwPackCheckFailure(code))) },
                )
            }
        }
        checking = false
        val landed = answer?.getOrNull()
        when {
            landed != null -> {
                support = landed
                cannotAsk = null
            }
            // Failure and timeout both LEAVE `support` alone: a re-check that fails must not wipe a
            // good list and replace nineteen honest rows with nineteen "unknown"s.
            answer != null -> {
                val code = (answer.exceptionOrNull() as? DwPackCheckFailure)?.code
                cannotAsk = "This phone would not say which language packs it has (code $code). " +
                    "Dictation still works and still says when a language is missing."
            }
            else -> {
                cannotAsk = "This phone's speech service did not answer, so which packs are " +
                    "installed is unknown. Dictation still works and still says when a language " +
                    "is missing."
            }
        }
    }

    /**
     * Ask the platform for one pack. THE ONLY `triggerModelDownload` CALL IN THE APP.
     *
     * [label] is carried so the note a designer reads names the language rather than a tag: "Odia is
     * installed" is a sentence, "or-IN is installed" is a log line.
     */
    fun ask(tag: String, label: String) {
        if (!platformCanAnswer) return
        val engine = engine() ?: return
        val intent = downloadIntent(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requests[tag] = DwPackRequestNote("Asked the phone for $label…")
            askWithListener(engine, intent, tag, label)
        } else {
            // Android 13: the request goes out into silence. Say so rather than drawing a spinner
            // that can never resolve.
            requests[tag] = DwPackRequestNote(DW_PACK_NO_PROGRESS_ON_13)
            runCatching { engine.triggerModelDownload(intent) }.onFailure {
                requests[tag] = DwPackRequestNote(
                    "This phone's speech service refused the request for $label. Try adding it in " +
                        "the phone's own speech or keyboard settings.",
                    failed = true,
                )
            }
        }
    }

    /** Drop the binding to the recogniser service. Called when the surface showing packs leaves. */
    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        checking = false
    }

    /**
     * The intent the CHECK is made with — deliberately carrying NO `EXTRA_LANGUAGE`.
     *
     * `checkRecognitionSupport` answers for the intent it is given, and the answer is four language
     * lists. Asking with a language pinned would be asking nineteen times for the one inventory the
     * device holds; asking once without one returns the inventory itself, which is what every
     * surface here renders.
     */
    private fun probeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    /**
     * The intent a DOWNLOAD is requested with — which does carry the language, because that is the
     * only thing telling the service which of the nineteen packs to fetch.
     *
     * Both language extras are set for the reason `DwDictation.intentFor` sets both: some OEM
     * recognisers read only `EXTRA_LANGUAGE_PREFERENCE` and would otherwise quietly act on the
     * device locale — here that would mean downloading the wrong pack entirely.
     */
    private fun downloadIntent(tag: String): Intent = probeIntent().apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
    }

    /**
     * The API-33 support check, isolated in its own function.
     *
     * `RecognitionSupportCallback` does not exist below API 33, and an anonymous class implementing
     * it is verified when the method that CREATES it is first executed — so on an Android 9 handset
     * this method is never entered and the missing type is never looked for. Inlining it into
     * [refresh] would risk a NoClassDefFoundError on the very devices the honest-unknown path exists
     * for.
     */
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
            // ERROR_CANNOT_CHECK_SUPPORT (14). Written as the constant, which the compiler inlines,
            // so no API-33 class is touched on an older device.
            onFailure(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        }
    }

    /**
     * The API-34 download overload, isolated for the same reason [checkSupport] is.
     *
     * The four outcomes are genuinely different and are worded as four different things.
     * `onScheduled()` in particular is NOT an error and NOT a completion: the service has taken the
     * request and will do it later, and the listener will never fire again. A spinner left running
     * on that outcome would spin until the app was killed.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun askWithListener(engine: SpeechRecognizer, intent: Intent, tag: String, label: String) {
        val listener = object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) {
                requests[tag] = DwPackRequestNote("Downloading $label — $completedPercent%", percent = completedPercent)
            }

            override fun onSuccess() {
                requests[tag] = DwPackRequestNote(
                    "$label is on this phone now. Dictation in $label works with no signal."
                )
                // The list behind it is now wrong by one row, and this is the one moment the app
                // knows that for certain.
                refresh()
            }

            override fun onScheduled() {
                requests[tag] = DwPackRequestNote(
                    "Android has queued $label and will fetch it when it decides to — it gives no " +
                        "further word than that. Tap “Check again” later to see whether it arrived."
                )
            }

            override fun onError(error: Int) {
                requests[tag] = DwPackRequestNote(
                    "$label could not be fetched (code $error). Check the connection and try again, " +
                        "or add it in the phone's own speech or keyboard settings.",
                    failed = true,
                )
            }
        }
        runCatching { engine.triggerModelDownload(intent, ContextCompat.getMainExecutor(context), listener) }
            .onFailure {
                requests[tag] = DwPackRequestNote(
                    "This phone's speech service refused the request for $label.",
                    failed = true,
                )
            }
    }
}

/**
 * A controller that exists only while [active], and asks the phone once when it becomes so.
 *
 * [active] is what keeps a `SpeechRecognizer` off the dashboard's first frame and out of the
 * four-hundred-odd fields of a stage screen. It is true when the language chooser is open, when the
 * offer dialog is up, when Settings is showing the list, and when the first-run card has been
 * expanded — and false the rest of the time, when the binding is dropped.
 */
@Composable
internal fun rememberDwLanguagePacks(active: Boolean): DwLanguagePackController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { DwLanguagePackController(context, scope) }
    // Keyed on `active` so leaving and returning re-asks: a pack downloaded from Settings must be
    // reflected the next time the language chooser is opened, without restarting the app.
    DisposableEffect(active) {
        if (active) controller.refresh()
        onDispose { if (active) controller.release() }
    }
    return controller
}

// ---------------------------------------------------------------------------------------------
// The list — one composable, three hosts
// ---------------------------------------------------------------------------------------------

/**
 * The nineteen languages with their state, and one deliberate control that fetches the ticked ones.
 *
 * NOTHING IS TICKED TO BEGIN WITH, on any host. The first-run offer, the settings card and any
 * future caller all start from "you have asked for nothing", so the shortest path through this panel
 * spends no data at all.
 */
@Composable
internal fun DwLanguagePackList(controller: DwLanguagePackController, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val states = dwPackStates(DW_DICTATION_LANGUAGES.map { it.tag }, controller.support)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- What we know, and how we know it -------------------------------------------------
        controller.cannotAsk?.let { reason ->
            Text(reason, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        if (controller.checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Asking this phone which packs it has…", color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
        }

        /*
         * ---- The nineteen ----------------------------------------------------------------------
         *
         * A row is tickable only where a download could actually be started for it, and "already
         * asked for in this sitting" counts as cannot: `controller.requests` holds one note per
         * language and the row shows it. Leaving such a row tickable would put a second Download
         * button beside a pack that is already coming, which on a prepaid bundle in a district town
         * is how one file gets paid for twice. The state list itself only turns DOWNLOADING once the
         * platform admits the request, which on Android 13 it never does.
         */
        val askable = DW_DICTATION_LANGUAGES.filter { language ->
            dwPackOffer(states[language.tag] ?: DwPackState.UNKNOWN, controller.connection) == DwPackOffer.DOWNLOAD &&
                controller.requests[language.tag] == null
        }
        DW_DICTATION_LANGUAGES.forEach { language ->
            val state = states[language.tag] ?: DwPackState.UNKNOWN
            DwLanguagePackRow(
                label = language.label,
                state = state,
                selectable = language in askable,
                checked = language.tag in selected,
                note = controller.requests[language.tag],
                onCheckedChange = { ticked ->
                    selected = if (ticked) selected + language.tag else selected - language.tag
                },
            )
        }

        // ---- What it costs, said BEFORE the button ---------------------------------------------
        val downloadable = askable.size
        val missingButOffline = controller.connection == DwConnection.NONE &&
            DW_DICTATION_LANGUAGES.any { states[it.tag] == DwPackState.DOWNLOADABLE }
        // A list with no button under it must say WHY there is no button, or it reads as a list that
        // failed to finish loading. Each arm covers one of the three ways that happens.
        when {
            missingButOffline -> Text(
                DW_PACK_NO_CONNECTION_SENTENCE,
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
            downloadable > 0 -> {
                Text(dwDownloadCostSentence(controller.connection), color = MaterialTheme.field.muted, fontSize = 12.sp)
                Text(DW_PACK_REQUEST_IS_A_REQUEST, color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
            controller.support != null -> Text(
                "There is nothing to fetch. Every language marked “${dwPackStateLabel(DwPackState.INSTALLED)}” " +
                    "is already on the phone, and the ones marked " +
                    "“${dwPackStateLabel(DwPackState.UNSUPPORTED)}” have no offline pack this " +
                    "recogniser can add.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            /*
             * The download button exists only where a download could actually happen.
             *
             * `downloadable` is already zero when there is no connection (see `dwPackOffer`), when
             * every pack is present, and when the phone could not be asked — and in all three cases
             * an offer to download would be a control that cannot work. A designer who taps one of
             * those concludes the app is broken, not that they are offline. The sentence above says
             * which of the three it is.
             *
             * DISABLED, NOT HIDDEN, once it IS drawn: nothing is ticked to begin with, and a button
             * that appeared only after the first tick would hide the fact that ticking is what the
             * rows are for.
             */
            if (downloadable > 0) {
                Button(
                    onClick = {
                        // Over `askable`, not over the whole list: a tick left behind on a row that
                        // has since been requested (or has since arrived) must not send a second
                        // request for the same pack.
                        askable.filter { it.tag in selected }.forEach { controller.ask(it.tag, it.label) }
                        selected = emptySet()
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when (selected.size) {
                            0 -> "Tick a language to download"
                            1 -> "Download 1 pack"
                            else -> "Download ${selected.size} packs"
                        }
                    )
                }
            }
            // Always present, and on Android 13 it is the ONLY way to learn whether a requested pack
            // arrived — that platform gives no download callback at all.
            OutlinedButton(onClick = { controller.refresh() }, enabled = !controller.checking) {
                Text("Check again")
            }
        }
    }
}

/** One language: its name, what this phone can do with it, and a tick-box only where one can act. */
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
                // Only a row that can DO something is a tap target. A whole-row ripple on "Not on
                // this phone" invites a tap and then swallows it.
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
                // The row owns the gesture, so the box is decoration — null keeps the tap target from
                // being announced twice by a screen reader.
                Checkbox(checked = checked, onCheckedChange = null)
            }
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                dwPackStateLabel(state),
                color = if (state == DwPackState.INSTALLED) MaterialTheme.field.success else MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
        note?.let { request ->
            Text(
                request.text,
                color = if (request.failed) MaterialTheme.colorScheme.error else MaterialTheme.field.body,
                fontSize = 11.sp
            )
            request.percent?.let { percent ->
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Surface 1 — the first-run offer (a card, never a modal)
// ---------------------------------------------------------------------------------------------

private const val DW_PACK_PREFS = "dictation_language_packs"
private const val DW_PACK_OFFER_SEEN = "first_run_offer_seen"

/** True once the designer has dismissed or opened the first-run offer on this device. */
internal fun dwPackOfferSeen(context: Context): Boolean =
    context.getSharedPreferences(DW_PACK_PREFS, Context.MODE_PRIVATE).getBoolean(DW_PACK_OFFER_SEEN, false)

internal fun dwMarkPackOfferSeen(context: Context) {
    context.getSharedPreferences(DW_PACK_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(DW_PACK_OFFER_SEEN, true).apply()
}

/**
 * The offer a designer meets once, on the dashboard, after installing the app.
 *
 * A CARD AND NOT A DIALOG. A modal on first launch that has to be dismissed before anything can be
 * recorded is the one shape of this feature that would be worse than not shipping it: the first
 * thing this app does in a courtyard would be to stand between a designer and the stage they came to
 * fill in. This sits above the dashboard tiles, is dismissed by a button that says "Not now", and
 * costs a designer who taps past it exactly nothing — the same list lives permanently in Settings.
 *
 * COLLAPSED IT ASKS THE PHONE NOTHING. Binding the recogniser to check nineteen languages happens
 * only when "Choose languages" is tapped, so a cold start on a 6 GB handset does not pay for an
 * offer the designer may be about to dismiss.
 */
@Composable
internal fun DwLanguagePackOfferCard(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val controller = rememberDwLanguagePacks(active = expanded)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Dictate with no signal",
                display = true,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(DW_PACK_OFFER_BLURB, color = MaterialTheme.field.body, fontSize = 13.sp)
            if (expanded) {
                DwLanguagePackList(controller)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!expanded) {
                    Button(
                        onClick = {
                            expanded = true
                            // Opening it counts as having been offered: the card must not come back
                            // tomorrow to a designer who has already read it and chosen nothing.
                            dwMarkPackOfferSeen(context)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Choose languages") }
                }
                OutlinedButton(
                    onClick = { dwMarkPackOfferSeen(context); onDismiss() },
                    modifier = Modifier.weight(1f)
                ) { Text(if (expanded) "Done" else "Not now") }
            }
            Text(
                "You can change this at any time under Settings › Appearance & accessibility.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Surface 2 — Settings, where the list lives permanently
// ---------------------------------------------------------------------------------------------

/**
 * The settings body: the nineteen, their state, and the control that fetches the missing ones.
 *
 * Draws no card of its own — the caller drops it inside its screen's existing card shape, so it sits
 * with the appearance and accessibility cards rather than inventing a second card style beside them.
 *
 * `active = true` unconditionally, because reaching this composable at all means the designer has
 * navigated to Settings to look at exactly this list. The binding lasts as long as the screen.
 */
@Composable
internal fun DwLanguagePackSettings(modifier: Modifier = Modifier) {
    val controller = rememberDwLanguagePacks(active = true)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Dictation uses the phone's own recogniser when the language is on the phone, and a " +
                "server when it is not. A pack here is what makes it work in a courtyard with no " +
                "signal. This is a setting of THIS PHONE, not of your account — packs live on the " +
                "handset and do not follow you to another one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.muted
        )
        DwLanguagePackList(controller)
    }
}

// ---------------------------------------------------------------------------------------------
// Surface 3 — the offer at the point of choosing a language
// ---------------------------------------------------------------------------------------------

/**
 * What to say when a designer has just picked [label] and its pack is not on the phone.
 *
 * Opened by `DwDictationButton` and only for a language whose state is actionable — never for one
 * that is installed, and never on a handset that could not be asked, because a dialog that says "we
 * cannot tell" on every language choice is a dialog a designer learns to dismiss without reading.
 *
 * With a connection it offers the download, with the cost named first. WITHOUT ONE IT OFFERS
 * NOTHING and says why, because a download button that cannot download is the control that teaches a
 * designer the feature is broken.
 */
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
    // One tap per language per sitting: once the request has gone out, the confirm button becomes
    // "Close" rather than a second "Download" that would ask the service for the same file again.
    val canDownload = offer == DwPackOffer.DOWNLOAD && note == null
    // The answer has not landed yet. Saying "unknown" here would be a claim rather than a wait, and
    // the wait resolves into the real sentence in the same dialog a moment later.
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
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "Asking this phone whether the $label pack is installed…",
                            color = MaterialTheme.field.muted,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Text(dwPackStateSentence(label, state), color = MaterialTheme.field.body, fontSize = 13.sp)
                    when (offer) {
                        DwPackOffer.DOWNLOAD -> {
                            Text(dwDownloadCostSentence(controller.connection), color = MaterialTheme.field.muted, fontSize = 12.sp)
                            Text(DW_PACK_REQUEST_IS_A_REQUEST, color = MaterialTheme.field.muted, fontSize = 12.sp)
                        }
                        DwPackOffer.NO_CONNECTION ->
                            Text(DW_PACK_NO_CONNECTION_SENTENCE, color = MaterialTheme.field.muted, fontSize = 12.sp)
                        // INSTALLED / IN_PROGRESS / UNAVAILABLE / UNKNOWN all have their whole answer
                        // in the state sentence above; a second line would only restate it.
                        else -> Unit
                    }
                }
                note?.let {
                    Text(
                        it.text,
                        color = if (it.failed) MaterialTheme.colorScheme.error else MaterialTheme.field.body,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            if (canDownload) {
                TextButton(onClick = { controller.ask(tag, label) }) { Text("Download $label") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        // A second button only where the first one spends data — otherwise "Close" and "Not now"
        // would sit side by side doing the same thing.
        dismissButton = if (canDownload) {
            { TextButton(onClick = onDismiss) { Text("Not now") } }
        } else {
            null
        },
    )
}
