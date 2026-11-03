package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.AppToggle
import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwAiLayerDto
import com.designprototype.workshop.data.DwAiVerbResultDto
import com.designprototype.workshop.data.DwSubtitleFormat
import com.designprototype.workshop.data.DwSubtitleSummary
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dwSubtitleCueSummary
import com.designprototype.workshop.data.dwSubtitleTimecode
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch

@Composable
internal fun DwAiVerbReviewSheet(
    result: DwAiVerbResultDto,
    repository: WorkshopRepository,
    serverWorkshopId: String,
    sentPassage: String?,
    onAccepted: () -> Unit,
    onDeclined: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = result.layer

    var busy by remember(layer.id) { mutableStateOf<String?>(null) }
    var problem by remember(layer.id) { mutableStateOf<String?>(null) }
    var speakers by remember(layer.id) { mutableStateOf(false) }
    var savedTo by remember(layer.id) { mutableStateOf<String?>(null) }

    val evidence = layer.source?.text?.takeIf { it.isNotBlank() }
        ?: sentPassage?.takeIf { it.isNotBlank() }
    val cues: DwSubtitleSummary? = remember(layer.id, layer.kind) {
        if (layer.kind == "SUBTITLES") dwSubtitleCueSummary(layer.payload) else null
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        title = { Text(dwLayerKindLabel(layer.kind), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (result.acceptanceRequired && !result.accepted) {
                        "Nothing has been put in any document yet. Read it, then decide."
                    } else {
                        "Read it before you rely on it."
                    },
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 18.sp,
                )

                if (layer.kind == "EXPANDED") {
                    DwVerbCaution(
                        "Machine-written from your note (quoted below). Anything not in the note was supplied by the model — check it before quoting."
                    )
                }

                dwLayerKindNote(layer.kind)?.let {
                    Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                }

                if (evidence != null) {
                    DwVerbSection("What was sent") { DwVerbPassage(evidence) }
                } else if (layer.source?.kind == "MEDIA") {
                    Text(
                        "Generated from an attached file. Check it against the original.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                } else if (layer.source?.kind == "LAYER") {
                    Text(
                        "Made from another layer of this workshop.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                DwVerbSection("What came back") {
                    val body = layer.text?.trim().orEmpty()
                    when {
                        layer.textWithheld -> Text(
                            "You can't access the source recording, so you can't view or accept this. Ask its uploader.",
                            color = MaterialTheme.field.warning,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 17.sp,
                        )

                        body.isNotEmpty() -> DwVerbPassage(body)

                        else -> Text(
                            "No text — see the cues below.",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (cues != null) {
                    DwVerbCueList(
                        cues = cues,
                        speakers = speakers,
                        onSpeakers = { speakers = it },
                        busy = busy != null,
                        onSave = { format ->
                            busy = "SAVE"
                            problem = null
                            savedTo = null
                            scope.launch {
                                runCatching {
                                    repository.downloadDesignWorkshopSubtitles(
                                        context = context,
                                        workshopId = serverWorkshopId,
                                        layerId = layer.id,
                                        format = format,
                                        speakers = speakers,
                                    )
                                }.onSuccess { savedTo = it }
                                    .onFailure { problem = dwAiVerbProblem(it) }
                                busy = null
                            }
                        },
                    )
                }

                DwVerbProvenance(layer)

                dwAiVerbCountdownLine(result.aiVerbsRemaining, result.aiVerbDay)?.let {
                    Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
                }

                savedTo?.let {
                    Text(
                        "Saved to $it. Saving doesn't accept it.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                problem?.let {
                    Text(
                        it,
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = busy == null && !layer.textWithheld,
                onClick = {
                    busy = "ACCEPT"
                    problem = null
                    scope.launch {
                        runCatching {
                            repository.acceptDesignWorkshopAiLayer(serverWorkshopId, layer.id)
                        }.onSuccess { onAccepted() }
                            .onFailure { problem = dwAiVerbProblem(it) }
                        busy = null
                    }
                },
            ) {
                if (busy == "ACCEPT") {
                    FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (busy == "ACCEPT") "Accepting…" else "I have read it — accept it in my name")
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                ActionRow {
                    TextButton(enabled = busy == null, onClick = onClose) {
                        Text("Leave it for now")
                    }
                }
                ActionRow {
                    TextButton(
                        enabled = busy == null,
                        onClick = {
                            busy = "DECLINE"
                            problem = null
                            scope.launch {
                                runCatching {
                                    repository.declineDesignWorkshopAiLayer(serverWorkshopId, layer.id)
                                }.onSuccess { onDeclined() }
                                    .onFailure { problem = dwAiVerbProblem(it) }
                                busy = null
                            }
                        },
                    ) {
                        Text(
                            if (busy == "DECLINE") "Declining…" else "Decline it",
                            color = MaterialTheme.field.warning,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DwVerbCaution(sentence: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                sentence,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun DwVerbSection(heading: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            heading,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.field.body,
        )
        content()
    }
}

@Composable
private fun DwVerbPassage(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 19.sp,
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}

private const val DW_CUE_PREVIEW_LIMIT: Int = 25

@Composable
private fun DwVerbCueList(
    cues: DwSubtitleSummary,
    speakers: Boolean,
    onSpeakers: (Boolean) -> Unit,
    busy: Boolean,
    onSave: (DwSubtitleFormat) -> Unit,
) {
    DwVerbSection("The cues") {
        if (!cues.readable) {
            Text(
                "Update the app to see cues. You can still save the file below.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
            )
        }
        Text(
            buildString {
                append(if (cues.count == 1) "1 cue" else "${cues.count} cues")
                if (cues.estimatedCues > 0) append(", ${cues.estimatedCues} of them approximate")
                cues.durationSeconds?.let { append(" · ${dwSubtitleTimecode(it)}") }
                cues.language?.let { append(" · $it") }
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )

        if (cues.cues.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                cues.cues.take(DW_CUE_PREVIEW_LIMIT).forEach { cue ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            Text(
                                "${dwSubtitleTimecode(cue.start)} → ${dwSubtitleTimecode(cue.end)}",
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            cue.speaker?.let {
                                Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            if (cue.estimated) {
                                Text(
                                    "approximate timing",
                                    color = MaterialTheme.field.warning,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text(cue.text, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (cues.cues.size > DW_CUE_PREVIEW_LIMIT) {
                Text(
                    "First $DW_CUE_PREVIEW_LIMIT of ${cues.count} cues shown; the file has all.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        if (cues.hasSpeakers) {
            AppToggle(
                label = "Label speakers on each line",
                checked = speakers,
                onCheckedChange = onSpeakers,
                enabled = !busy,
                description = "Labels are a best guess.",
            )
        }

        DwSubtitleFormat.entries.forEach { format ->
            OutlinedButton(
                onClick = { onSave(format) },
                enabled = !busy && (cues.cues.isNotEmpty() || !cues.readable),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(format.label, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            "Save and check against the recording before deciding.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun DwVerbProvenance(layer: DwAiLayerDto) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                dwTierLabel(layer.tier),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                dwTierSentence(layer.tier),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            HorizontalDivider()
            Text(
                "Provider: ${dwProvenanceWord(layer.provider)} · " +
                    "Model: ${dwModelWords(layer.modelId, layer.modelVersion)} · " +
                    "Language: ${dwLanguageWords(layer.language)}",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            if (layer.kind == "TRANSLATION") {
                Text(
                    "From ${dwLanguageWords(layer.sourceLanguage)} into " +
                        dwLanguageWords(layer.targetLanguage) + ".",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
            Text(
                "Accepting records your name. Until then, no report prints this ${dwLayerKindNoun(layer.kind)}.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
    }
}
