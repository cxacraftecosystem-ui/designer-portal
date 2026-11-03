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
import com.designprototype.workshop.data.DwTier2FailureStore
import com.designprototype.workshop.data.dwAsrInstalledModelIds
import com.designprototype.workshop.data.dwDeviceClassLabel
import com.designprototype.workshop.data.dwDeviceReadoutSentence
import com.designprototype.workshop.data.dwProbeDevice
import com.designprototype.workshop.data.dwProbeIsStale
import com.designprototype.workshop.data.dwRecommendTiers
import com.designprototype.workshop.data.dwTier2TierLine
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
import com.designprototype.workshop.ui.designworkshop.rememberDwTier2Models
import kotlinx.coroutines.delay

@Composable
fun SpeechAndAiScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val languagePacks = rememberDwLanguagePacks(active = true)
    val asrRuntime = rememberDwAsrRuntime(active = true)
    val asrModel = rememberDwAsrModel(active = true)
    val tier2Models = rememberDwTier2Models()
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

        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.RecordVoiceOver, "Offline dictation")
            DwLanguagePackSettings(languagePacks)
            DwAsrModelBody(asrModel, languageLabels, languagePacks.support)
        }

        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Memory, "AI on this phone")
            DwDeviceTierBody(asrRuntime.status, asrModel, tier2Models)
        }
    }
}

@Composable
private fun DwDeviceTierBody(
    runtime: com.designprototype.workshop.data.DwAsrRuntimeStatus,
    models: com.designprototype.workshop.ui.designworkshop.DwAsrModelController,
    tier2Models: com.designprototype.workshop.ui.designworkshop.DwTier2ModelController,
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
        tier2Models.refresh()
    }

    val measurement = reading
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

        val failures = remember(probeCount) { DwTier2FailureStore.read(context) }
        val recommendation = dwRecommendTiers(measurement, connection, failures, runtime = runtime)

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

        dwTier2TierLine(recommendation.tier2, tier2Models.installed)?.let { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
        }
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
            connection = connection,
            models = tier2Models,
        )

        ActionRow {
            OutlinedButton(onClick = { probeCount += 1 }) { Text("Check again") }
        }
    }
}

internal fun dwSpeechInstallInFlight(state: DwAsrModelState): Boolean =
    state == DwAsrModelState.INSTALLING

internal fun dwSpeechInstalledModelIds(
    status: com.designprototype.workshop.data.DwAsrModelStatus,
): Set<String> = dwAsrInstalledModelIds(status)
