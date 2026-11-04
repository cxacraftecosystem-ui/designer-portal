package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.DwAiDecisionHistoryItemDto
import com.designprototype.workshop.data.DwAiLayerDto
import com.designprototype.workshop.data.DwTranscriptItemDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwDecisionLine
import com.designprototype.workshop.data.dwLayerKindWords
import com.designprototype.workshop.data.dwRegisterableTranscripts
import com.designprototype.workshop.data.dwRegisteredNotice
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch

const val DW_AI_LAYERS_TITLE = "AI suggestions"
const val DW_AI_LAYERS_SUBTITLE = "Every machine suggestion on this workshop, with who accepted or declined it."
const val DW_AI_LAYERS_LOCAL_ONLY = "Upload this workshop first. Suggestions live on the server."
const val DW_AI_LAYERS_EMPTY = "No suggestions yet."
const val DW_AI_LAYERS_REGISTER_TITLE = "Transcripts without a layer"
const val DW_AI_LAYERS_REGISTER_NOTE = "Provider and model show as not recorded for these."
const val DW_AI_LAYERS_HISTORY_TITLE = "Decision history"
const val DW_AI_LAYERS_HISTORY_EMPTY = "No decisions yet."

@Composable
fun DwAiLayersScreen(
    repository: WorkshopRepository,
    workshopId: String,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var serverId by remember(workshopId) { mutableStateOf<String?>(null) }
    var resolved by remember(workshopId) { mutableStateOf(false) }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var layers by remember(workshopId) { mutableStateOf<List<DwAiLayerDto>>(emptyList()) }
    var transcripts by remember(workshopId) { mutableStateOf<List<DwTranscriptItemDto>>(emptyList()) }
    var error by remember(workshopId) { mutableStateOf<String?>(null) }
    var notice by remember(workshopId) { mutableStateOf<String?>(null) }
    var busy by remember(workshopId) { mutableStateOf<String?>(null) }
    val texts = remember(workshopId) { mutableStateMapOf<String, String>() }
    val decisions = remember(workshopId) { mutableStateMapOf<String, List<DwAiDecisionHistoryItemDto>>() }
    var history by remember(workshopId) { mutableStateOf<List<DwAiDecisionHistoryItemDto>?>(null) }

    LaunchedEffect(workshopId, reload) {
        loading = true
        error = null
        val remote = runCatching { WorkshopDraftStore.load(appContext, workshopId)?.remoteId }.getOrNull()
            ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
        serverId = remote
        resolved = true
        if (remote == null) {
            loading = false
            return@LaunchedEffect
        }
        runCatching { repository.designWorkshopAiLayers(remote, includeDeleted = true) }
            .onSuccess { layers = it.items }
            .onFailure { error = it.apiErrorMessage("Couldn't load the suggestions. Try again.") }
        runCatching { repository.designWorkshopTranscripts(remote) }
            .onSuccess { transcripts = it.items }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            DW_AI_LAYERS_TITLE,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(DW_AI_LAYERS_SUBTITLE, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

        val remote = serverId
        if (resolved && remote == null) {
            Text(DW_AI_LAYERS_LOCAL_ONLY, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (loading) {
            FieldCircularProgressIndicator()
            return@Column
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
        if (remote == null) return@Column

        val live = layers.filter { it.deletedAt == null }
        if (live.isEmpty()) {
            Text(DW_AI_LAYERS_EMPTY, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium)
        }
        live.forEach { layer ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(dwLayerKindWords(layer.kind), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(if (layer.accepted) "Accepted" else "Awaiting a decision", layer.provider, layer.modelId)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val text = texts[layer.id] ?: layer.preview
                    text?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    decisions[layer.id]?.forEach { item ->
                        Text(dwDecisionLine(item), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!texts.containsKey(layer.id) && !layer.textWithheld) {
                        TextButton(
                            enabled = busy == null,
                            onClick = {
                                busy = layer.id
                                scope.launch {
                                    runCatching { repository.designWorkshopAiLayer(remote, layer.id) }
                                        .onSuccess { read ->
                                            read.layer.text?.let { texts[layer.id] = it }
                                            decisions[layer.id] = read.decisions
                                        }
                                        .onFailure { error = it.apiErrorMessage("Couldn't read that layer. Try again.") }
                                    busy = null
                                }
                            },
                        ) { Text(if (busy == layer.id) "Reading…" else "Read in full") }
                    }
                }
            }
        }

        val registerable = remember(layers, transcripts) { dwRegisterableTranscripts(layers, transcripts) }
        if (registerable.isNotEmpty()) {
            HorizontalDivider()
            Text(DW_AI_LAYERS_REGISTER_TITLE, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(DW_AI_LAYERS_REGISTER_NOTE, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            registerable.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        listOf(item.label.ifBlank { item.filename }, item.stageTitle, item.durationText)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ActionRow {
                        OutlinedButton(
                            enabled = busy == null,
                            onClick = {
                                busy = item.mediaId
                                notice = null
                                error = null
                                scope.launch {
                                    runCatching { repository.registerDesignWorkshopAiLayer(remote, item.mediaId) }
                                        .onSuccess { result ->
                                            notice = dwRegisteredNotice(result.total)
                                            reload++
                                        }
                                        .onFailure { error = it.apiErrorMessage("Couldn't register that recording.") }
                                    busy = null
                                }
                            },
                        ) { Text(if (busy == item.mediaId) "Registering…" else "Register") }
                    }
                }
            }
        }

        HorizontalDivider()
        val shown = history
        if (shown == null) {
            ActionRow {
                Button(
                    enabled = busy == null,
                    onClick = {
                        busy = "history"
                        scope.launch {
                            runCatching { repository.designWorkshopAiLayerDecisions(remote) }
                                .onSuccess { history = it.items }
                                .onFailure { error = it.apiErrorMessage("Couldn't load the history. Try again.") }
                            busy = null
                        }
                    },
                ) { Text(if (busy == "history") "Loading…" else "Show decision history") }
            }
        } else {
            Text(DW_AI_LAYERS_HISTORY_TITLE, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            if (shown.isEmpty()) {
                Text(DW_AI_LAYERS_HISTORY_EMPTY, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            shown.forEach { item ->
                Text(dwDecisionLine(item), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
