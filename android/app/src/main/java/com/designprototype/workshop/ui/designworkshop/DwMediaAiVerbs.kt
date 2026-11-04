package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ImageSearch
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_SUBTITLES_SECOND_UPLOAD_NOTE
import com.designprototype.workshop.data.DW_VERBS_MEDIA_NOT_UPLOADED
import com.designprototype.workshop.data.DW_VERBS_WORKSHOP_NOT_ON_SERVER
import com.designprototype.workshop.data.DwAiVerbResultDto
import com.designprototype.workshop.data.DwVerbGate
import com.designprototype.workshop.data.dwVerbMediaRefusal
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch

@Composable
internal fun DwMediaAiVerbsRow(
    item: DwMediaItem,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val verbs = remember(item.mediaType) { dwMediaVerbsFor(item.mediaType) }
    if (verbs.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var runs by remember(item.id) { mutableStateOf(0) }
    val surface = dwVerbSurface(runs)

    var open by remember(item.id) { mutableStateOf(false) }
    var running by remember(item.id) { mutableStateOf<String?>(null) }
    var problem by remember(item.id) { mutableStateOf<String?>(null) }
    var result by remember(item.id) { mutableStateOf<DwAiVerbResultDto?>(null) }

    var subtitled by remember(surface.serverWorkshopId) { mutableStateOf<Set<String>?>(null) }

    var gate by remember(item.id) { mutableStateOf<DwVerbGate>(DwVerbGate.StillReading) }
    LaunchedEffect(open, item.id, surface.serverWorkshopId, surface.consent) {
        if (!open) {
            gate = DwVerbGate.StillReading
            return@LaunchedEffect
        }
        gate = surface.gate(context)
        val workshop = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
        val repository = surface.repository
        if (MEDIA_VERB_SUBTITLES in verbs && repository != null && workshop != null) {
            subtitled = runCatching {
                repository.designWorkshopAiLayers(workshop, kind = "SUBTITLES")
                    .items
                    .filter { it.deletedAt == null }
                    .mapNotNull { layer -> layer.source?.id?.takeIf { it.isNotBlank() } }
                    .toSet()
            }.getOrNull()
        }
    }

    val serverMediaId = item.remoteMediaId?.takeIf { it.isNotBlank() }

    fun start(verb: String) {
        problem = null
        val workshop = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
        val repository = surface.repository
        if (repository == null || workshop == null) {
            problem = DW_VERBS_WORKSHOP_NOT_ON_SERVER
            return
        }
        dwVerbMediaRefusal(item.remoteMediaId)?.let {
            problem = it
            return
        }
        val mediaId = item.remoteMediaId?.takeIf { it.isNotBlank() } ?: run {
            problem = DW_VERBS_MEDIA_NOT_UPLOADED
            return
        }
        running = verb
        scope.launch {
            runCatching {
                if (verb == MEDIA_VERB_SUBTITLES) {
                    repository.designWorkshopSubtitleMedia(context, workshop, mediaId)
                } else {
                    repository.designWorkshopCaptionMedia(context, workshop, mediaId)
                }
            }.onSuccess { answer ->
                result = answer
                if (verb == MEDIA_VERB_SUBTITLES) {
                    subtitled = (subtitled ?: emptySet()) + mediaId
                }
            }.onFailure { error ->
                problem = dwAiVerbProblem(error)
            }
            running = null
            runs += 1
        }
    }

    val currentGate = gate

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (running != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    if (running == MEDIA_VERB_SUBTITLES) {
                        "Sending this recording up for subtitling…"
                    } else {
                        "Asking a model to describe this…"
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            ActionRow {
                TextButton(onClick = { open = !open }, enabled = enabled) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (open) "Close" else "Ask AI about this file", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        when {
            !open || running != null -> Unit

            currentGate is DwVerbGate.StillReading -> Unit

            currentGate is DwVerbGate.Refused -> {
                DwMediaVerbNote(currentGate.sentence)
                DwRecordConsentLink(surface.consent)
            }

            serverMediaId == null -> DwMediaVerbNote(DW_VERBS_MEDIA_NOT_UPLOADED)

            else -> {
                if (MEDIA_VERB_CAPTION in verbs) {
                    OutlinedButton(
                        onClick = { start(MEDIA_VERB_CAPTION) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.ImageSearch,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Describe this for the annexure", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (MEDIA_VERB_SUBTITLES in verbs) {
                    if (subtitled?.contains(serverMediaId) == true) {
                        DwMediaVerbNote(
                            "Subtitles already exist for this recording."
                        )
                    } else {
                        OutlinedButton(
                            onClick = { start(MEDIA_VERB_SUBTITLES) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Filled.ClosedCaption,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Make subtitles", style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            DW_SUBTITLES_SECOND_UPLOAD_NOTE,
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                        if (subtitled == null) {
                            Text(
                                "Couldn't check for existing subtitles.",
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
                dwAiVerbCountdownLine(surface.cap.remaining, surface.today)?.let {
                    Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
                }
                dwAiVerbAllowanceNote(surface.cap)?.let {
                    Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
                }
            }
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

    val answer = result
    val workshop = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
    val repository = surface.repository
    if (answer != null && repository != null && workshop != null) {
        DwAiVerbReviewSheet(
            result = answer,
            repository = repository,
            serverWorkshopId = workshop,
            sentPassage = null,
            onAccepted = { result = null },
            onDeclined = { result = null },
            onClose = { result = null },
        )
    }
}

@Composable
private fun DwMediaVerbNote(sentence: String) {
    Text(
        sentence,
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
        lineHeight = 16.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
