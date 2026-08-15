package com.designprototype.workshop.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.DW_TIER_STALE_SENTENCE
import com.designprototype.workshop.data.DwAiTier
import com.designprototype.workshop.data.DwAsrModelState
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.dwAsrInstalledModelIds
import com.designprototype.workshop.data.dwDeviceClassLabel
import com.designprototype.workshop.data.dwDeviceReadoutSentence
import com.designprototype.workshop.data.dwProbeDevice
import com.designprototype.workshop.data.dwProbeIsStale
import com.designprototype.workshop.data.dwRecommendTiers
import com.designprototype.workshop.data.dwTier3Sentence
import com.designprototype.workshop.data.dwTierOfferSentence
import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import com.designprototype.workshop.ui.designworkshop.DwAsrModelBody
import com.designprototype.workshop.ui.designworkshop.DwLanguagePackSettings
import com.designprototype.workshop.ui.designworkshop.DwModelChoiceList
import com.designprototype.workshop.ui.designworkshop.DwTier2ModelList
import com.designprototype.workshop.ui.designworkshop.dwConnection
import com.designprototype.workshop.ui.designworkshop.rememberDwAsrModel
import com.designprototype.workshop.ui.designworkshop.rememberDwAsrRuntime
import com.designprototype.workshop.ui.designworkshop.rememberDwLanguagePacks
import kotlinx.coroutines.delay

/**
 * **EVERYTHING ABOUT THIS PHONE'S SPEECH AND AI, ON ONE SCREEN, IN TWO CARDS.**
 *
 * ── WHAT THIS REPLACED ────────────────────────────────────────────────────────────────────────
 *
 * Four cards at the bottom of Appearance & accessibility, measured on the handset's own view
 * hierarchy at roughly **2,300 words**: "Offline dictation languages" (170), "Offline speech engine"
 * (176), "Offline speech model" (188+), and "AI on this phone" (156 plus a nineteen-row language
 * list of 1,207). Beneath two cards about a colour scheme.
 *
 * ── AND WHY IT IS TWO CARDS AND NOT FOUR ──────────────────────────────────────────────────────
 *
 *  * **The engine card is gone entirely.** It described 23.6 MB that is inside the APK and cannot be
 *    anywhere else; it had no state a designer can change and one control, "Check again", which
 *    re-read a fact that cannot vary. The reading is still taken — `dwRecommendTiers` needs it — and
 *    is simply no longer read aloud. See the note at the foot of `DwAsrRuntimeUi.kt`.
 *  * **The model is merged into the dictation card**, because it answers the same question the packs
 *    answer: what can this phone hear with no signal. Two cards made a designer compose the answer
 *    themselves, and `dwAsrModelWhatItBuysSentence` now states the model's contribution **net of what
 *    the packs already give** — which on the fleet's own handset is nothing at all.
 *  * **The 1,207-word coverage list is gone.** Nineteen rows, seventeen of them a paragraph saying
 *    nothing can be done. What a designer needs to know about a language with no offline pack they
 *    meet in the dictation flow, at the moment it costs something — which is where the web puts it.
 *
 * ── WHAT IS DELIBERATELY KEPT ─────────────────────────────────────────────────────────────────
 *
 * The numbers. Device class, free memory, free storage, one line per tier, the model's size and the
 * verdict on this handset. Principle 5: measured things show their number. And the model list, which
 * is where a designer may install a model this device would not have suggested — the suggestion is
 * not binding, and `DwModelChoiceList` still gates that on a named confirmation.
 *
 * ── ONE READING OF THE PHONE, THREE READERS ───────────────────────────────────────────────────
 *
 * The pack controller and the model controller are created HERE and handed down, for the reason this
 * repository has already shipped a defect over: two controllers would bind two `SpeechRecognizer`s
 * and take two readings of one handset moments apart, and the two cards would print two accounts of
 * one fact. `DwDictationRun` remembering a refusal for a pack that had since arrived is that failure,
 * written up in `DwLanguagePackUi.kt`.
 */
@Composable
fun SpeechAndAiScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val languagePacks = rememberDwLanguagePacks(active = true)
    val asrRuntime = rememberDwAsrRuntime(active = true)
    val asrModel = rememberDwAsrModel(active = true)
    val languageLabels = remember { DW_DICTATION_LANGUAGES.associate { it.tag to it.label } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Speech & AI",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        /*
         * ---- ONE CARD FOR OFFLINE DICTATION: the packs and the model, which are one question -----
         *
         * The packs come first because they are Android's and cost a designer nothing to discover;
         * the model second because it is 365 MB and its whole value is measured against what the
         * packs already cover.
         */
        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.RecordVoiceOver, "Offline dictation")
            DwLanguagePackSettings(languagePacks)
            DwAsrModelBody(asrModel, languageLabels, languagePacks.support)
        }

        /*
         * ---- AND ONE FOR WHAT THIS PHONE IS, WHICH IS NUMBERS -----------------------------------
         *
         * It is second because it offers less: nothing on it can be installed. The Tier 2 catalogue is
         * no longer empty — two weighed rows since 2026-08-13 — but no runtime in this build can open
         * one, so this card's honest content is a readout, a verdict per model, and no control. The
         * brief's words for it:
         * *"Keep 'AI on this phone' as the numbers only — class, free memory, free storage, one line
         * per tier."*
         */
        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Memory, "AI on this phone")
            DwDeviceTierBody(asrRuntime.status, asrModel)
        }
    }
}

/**
 * What this handset is, as numbers, and what each tier makes of them.
 *
 * ── WHAT CAME OFF THIS CARD ───────────────────────────────────────────────────────────────────
 *
 *  * **`DW_TIER_CARD_BLURB`**, a standing paragraph explaining what tiers are. A designer does not
 *    choose a tier; they read a verdict.
 *  * **The nineteen-row coverage list and its summary**, 1,207 words — see the screen's header.
 *  * **`DW_TIER_REPROBE_SENTENCE`**, which explained that the reading is taken again when "Check
 *    again" is tapped. The button says that.
 *  * **The duplicated `runBound` paragraph.** `dwTierOfferSentence` appended `"One run is: "` plus
 *    ~75 words, and `dwModelChoiceSentence` — printed by the model list a few dp below it —
 *    appended the identical string. One card, one screen, the same paragraph twice. It is kept in
 *    the model row, where a designer choosing between models needs it, and dropped from the tier
 *    line, which is a verdict rather than a specification.
 *
 * ── WHAT STAYED, AND WHY THE STALE WARNING IS NOT NARRATION ───────────────────────────────────
 *
 * Free memory and free storage move through a working day, and a figure from twenty minutes ago
 * passed off as the state of the phone is a fabricated measurement — the one thing this lane may not
 * do. [dwProbeIsStale] says so instead.
 */
@Composable
private fun DwDeviceTierBody(
    runtime: com.designprototype.workshop.data.DwAsrRuntimeStatus,
    models: com.designprototype.workshop.ui.designworkshop.DwAsrModelController,
) {
    val context = LocalContext.current.applicationContext
    var probeCount by remember { mutableStateOf(0) }
    var reading by remember { mutableStateOf<DwDeviceMeasurement?>(null) }
    var connection by remember { mutableStateOf(dwConnection(context)) }
    var stale by remember { mutableStateOf(false) }

    LaunchedEffect(probeCount) {
        stale = false
        reading = dwProbeDevice(context)
        connection = dwConnection(context)
    }

    val measurement = reading
    /*
     * WHY THIS POLLS INSTEAD OF SLEEPING ONCE FOR THE WHOLE FRESHNESS WINDOW. `delay` runs on a clock
     * that does not advance while the device is in deep sleep, so a phone put in a pocket for an hour
     * with this screen open would come back with the timer still part-way through and the card still
     * claiming a reading from before the walk. Asking `SystemClock.elapsedRealtime()` every fifteen
     * seconds is what actually notices. The loop ends the moment it turns stale.
     */
    LaunchedEffect(measurement) {
        val taken = measurement?.takenAtElapsedMs ?: return@LaunchedEffect
        while (!stale) {
            delay(15_000)
            stale = dwProbeIsStale(taken, SystemClock.elapsedRealtime())
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (measurement == null) {
            Text(
                "Reading this phone…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
            return@Column
        }

        // THE ENGINE'S STATUS IS PASSED IN, NOT DEFAULTED. Left out, `dwRecommendTiers` would answer
        // Tier 1 out of an UNKNOWN engine state — "this app could not look at its own files" — which
        // is a true sentence about a question this screen HAS asked and would print it beside a card
        // that knows the answer.
        val recommendation = dwRecommendTiers(measurement, connection, runtime = runtime)

        // ---- The numbers, first, because they are what a designer reads down a phone line --------
        Text(
            "This phone: ${dwDeviceClassLabel(recommendation.deviceClass)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            dwDeviceReadoutSentence(measurement),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.muted
        )
        if (stale) {
            Text(DW_TIER_STALE_SENTENCE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.warning)
        }

        // ---- One line per tier --------------------------------------------------------------
        Text(
            dwTierOfferSentence(DwAiTier.TIER_2, recommendation.tier2),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.body
        )
        Text(
            dwTierOfferSentence(DwAiTier.TIER_1, recommendation.tier1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.body
        )
        Text(
            dwTier3Sentence(connection),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.body
        )

        /*
         * ---- The models a designer may choose from ---------------------------------------------
         *
         * THE SUGGESTION IS NOT BINDING, which is why this list exists at all: every measured model
         * is drawn on every device with what it would cost here, and a designer may install one the
         * arithmetic would not have suggested after a single confirmation naming the cost.
         * `DwModelChoiceList` owns that gate, and on the fleet's own handset it is the TIGHT path
         * that fires — the pinned model's 1.26 GB peak RSS against this phone's free memory fits
         * physically with the 512 MiB margin gone.
         *
         * Tier 2 draws through `DwTier2ModelList` and not through this composable, and the reason is
         * two false sentences rather than a preference: `dwModelChoiceSentence` appends "How
         * accurately it transcribes ANY language is UNMEASURED" and "How long it takes to transcribe a
         * recording on this phone is UNMEASURED" to a plan with no scores and no timing band — true of
         * the speech model they were written for, nonsense under a proofreader. That list also carries
         * the two Gemma 3n artifacts, which have no published memory figure and therefore cannot be
         * `DwModelPlan`s at all, and it needs no `onInstall`: this build has no runtime that could
         * open a language model, so a control spending 2.6 GB would be worse than none.
         */
        val labels = remember { DW_DICTATION_LANGUAGES.associate { it.tag to it.label } }
        DwModelChoiceList(
            choices = recommendation.tier1Choices,
            measurement = measurement,
            connection = connection,
            languageLabels = labels,
            heading = "Speech models",
            onInstall = { models.install() },
        )
        DwTier2ModelList(
            choices = recommendation.tier2Choices,
            measurement = measurement,
        )

        OutlinedButton(onClick = { probeCount += 1 }) { Text("Check again") }
    }
}

/**
 * Whether anything on this phone is mid-install, so the caller can keep a reading warm.
 *
 * Exposed rather than inlined because `MainActivity` uses it to decide whether leaving this screen
 * should be treated as a pause — see `DwAsrModelController.release`, which keeps the part-file.
 */
internal fun dwSpeechInstallInFlight(state: DwAsrModelState): Boolean =
    state == DwAsrModelState.INSTALLING

/** Ids of models verified on this phone, for the row summary. Thin wrapper, one import. */
internal fun dwSpeechInstalledModelIds(
    status: com.designprototype.workshop.data.DwAsrModelStatus,
): Set<String> = dwAsrInstalledModelIds(status)
