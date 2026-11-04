package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_TIER2_MAX_PASSAGE_CHARS
import com.designprototype.workshop.data.DW_TIER2_WORKING_SENTENCE
import com.designprototype.workshop.data.DW_VERB_MAX_LANGUAGE_CHARS
import com.designprototype.workshop.data.DW_VERB_MAX_TEXT_CHARS
import com.designprototype.workshop.data.DW_VERBS_NOTHING_SELECTED
import com.designprototype.workshop.data.DW_VERBS_WORKSHOP_NOT_ON_SERVER
import com.designprototype.workshop.data.DwAiVerbResultDto
import com.designprototype.workshop.data.DwModelPlan
import com.designprototype.workshop.data.DwTier2FailureStore
import com.designprototype.workshop.data.DwTier2LoadFailed
import com.designprototype.workshop.data.dwProbeDevice
import com.designprototype.workshop.data.dwTier2ModelToRun
import com.designprototype.workshop.data.dwTier2RunRefusal
import com.designprototype.workshop.data.dwTier2VerbNote
import com.designprototype.workshop.data.DwVerbGate
import com.designprototype.workshop.data.DwVerbSource
import com.designprototype.workshop.data.dwTranslationTargetRefusal
import com.designprototype.workshop.data.dwVerbPassageRefusal
import com.designprototype.workshop.data.dwVerbPassageTooLong
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DwAiVerbsPanel(
    enabled: Boolean,
    passageChars: Int,
    passagePreview: String,
    readPassage: () -> String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var runs by remember { mutableStateOf(0) }
    val surface = dwVerbSurface(runs)

    var open by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<DwAiVerbResultDto?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }

    var deviceModel by remember { mutableStateOf<DwModelPlan?>(null) }
    var runningOnDevice by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        deviceModel = withContext(Dispatchers.IO) {
            dwTier2ModelToRun(dwTier2InstalledIds(context), DwTier2FailureStore.read(context))
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (running) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    if (runningOnDevice) DW_TIER2_WORKING_SENTENCE else "Working on the passage…",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            OutlinedButton(
                onClick = {
                    open = !open
                    if (!open) target = null
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (open) "Close" else "Ask AI about this paragraph")
            }
        }

        if (open && !running) {
            val gate = surface.gate(context)

            fun start(verb: String, targetLanguage: String? = null) {
                problem = null
                val repository = surface.repository
                val workshopId = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
                if (repository == null || workshopId == null) {
                    problem = DW_VERBS_WORKSHOP_NOT_ON_SERVER
                    return
                }
                val passage = readPassage()
                val refusal = dwVerbPassageRefusal(passage)
                if (refusal != null) {
                    problem = if (refusal == DW_VERBS_NOTHING_SELECTED) {
                        DW_NO_PARAGRAPH_TO_WORK_ON
                    } else {
                        refusal
                    }
                    return
                }
                running = true
                scope.launch {
                    val model = deviceModel
                    val tier2Verb = dwTier2VerbFor(verb)
                    if (model != null && tier2Verb != null) {
                        val thermal = withContext(Dispatchers.IO) { dwProbeDevice(context).thermal }
                        if (dwTier2RunRefusal(tier2Verb, passage.length, model, false, thermal) == null) {
                            runningOnDevice = true
                            val outcome = runCatching {
                                dwRunVerbOnDevice(
                                    context, repository, workshopId, model, tier2Verb, passage, targetLanguage,
                                )
                            }
                            runningOnDevice = false
                            val failure = outcome.exceptionOrNull()
                            if (failure is DwTier2LoadFailed) {
                                DwTier2FailureStore.record(context, failure.model, failure.detail)
                                deviceModel = null
                            }
                            val answer = outcome.getOrNull()
                            if (answer != null) {
                                sent = passage
                                result = answer
                                target = null
                                open = false
                                running = false
                                runs += 1
                                return@launch
                            }
                        }
                    }
                    if (gate !is DwVerbGate.Ready) {
                        problem = (gate as? DwVerbGate.Refused)?.sentence
                        running = false
                        return@launch
                    }
                    runCatching {
                        when (verb) {
                            "PROOFREAD" -> repository.designWorkshopProofread(
                                context, workshopId, DwVerbSource.Passage(passage)
                            )
                            "EXPAND" -> repository.designWorkshopExpand(context, workshopId, passage)
                            else -> repository.designWorkshopTranslate(
                                context,
                                workshopId,
                                DwVerbSource.Passage(passage),
                                targetLanguage.orEmpty(),
                            )
                        }
                    }.onSuccess { answer ->
                        sent = passage
                        result = answer
                        target = null
                        open = false
                    }.onFailure { error ->
                        problem = dwAiVerbProblem(error)
                    }
                    running = false
                    runs += 1
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (gate) {
                        DwVerbGate.StillReading -> Unit

                        is DwVerbGate.Refused -> {
                            val model = deviceModel
                            if (model == null || !dwTier2CanRecord(context, surface.serverWorkshopId)) {
                                DwVerbRefusal(gate.sentence)
                                DwRecordConsentLink(surface.consent)
                            } else if (passageChars == 0 || passageChars > DW_TIER2_MAX_PASSAGE_CHARS) {
                                DwVerbRefusal(gate.sentence)
                                DwRecordConsentLink(surface.consent)
                            } else {
                                Text(
                                    "“$passagePreview”",
                                    color = MaterialTheme.field.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 17.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (target == null) {
                                    DwVerbChoice("Proofread this passage") { start("PROOFREAD") }
                                    DwVerbChoice("Translate this passage") { target = "" }
                                } else {
                                    DwVerbTranslateStep(
                                        value = target.orEmpty(),
                                        onValue = { target = it },
                                        onCancel = { target = null },
                                        onTranslate = { start("TRANSLATE", it) },
                                    )
                                }
                                Text(
                                    dwTier2VerbNote(model),
                                    color = MaterialTheme.field.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 16.sp,
                                )
                            }
                        }

                        DwVerbGate.Ready -> {
                            val passageRefusal = when {
                                passageChars == 0 -> DW_NO_PARAGRAPH_TO_WORK_ON
                                passageChars > DW_VERB_MAX_TEXT_CHARS ->
                                    dwVerbPassageTooLong(passageChars)
                                else -> null
                            }
                            if (passageRefusal != null) {
                                DwVerbRefusal(passageRefusal)
                            } else {
                                Text(
                                    "This text will be sent:",
                                    color = MaterialTheme.field.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 16.sp,
                                )
                                Text(
                                    "“$passagePreview”",
                                    color = MaterialTheme.field.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 17.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (target == null) {
                                    DwVerbChoice("Proofread this passage") { start("PROOFREAD") }
                                    DwVerbChoice("Write this note out") { start("EXPAND") }
                                    DwVerbChoice("Translate this passage") {
                                        target = ""
                                    }
                                } else {
                                    DwVerbTranslateStep(
                                        value = target.orEmpty(),
                                        onValue = { target = it },
                                        onCancel = { target = null },
                                        onTranslate = { start("TRANSLATE", it) },
                                    )
                                }
                                deviceModel?.let { model ->
                                    if (passageChars <= DW_TIER2_MAX_PASSAGE_CHARS) {
                                        Text(
                                            dwTier2VerbNote(model),
                                            color = MaterialTheme.field.muted,
                                            style = MaterialTheme.typography.bodySmall,
                                            lineHeight = 16.sp,
                                        )
                                    }
                                }
                                dwAiVerbCountdownLine(surface.cap.remaining, surface.today)?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.field.warning,
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = 16.sp,
                                    )
                                }
                                dwAiVerbAllowanceNote(surface.cap)?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.field.muted,
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = 16.sp,
                                    )
                                }
                            }
                        }
                    }
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
    val serverId = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
    val repository = surface.repository
    if (answer != null && repository != null && serverId != null) {
        DwAiVerbReviewSheet(
            result = answer,
            repository = repository,
            serverWorkshopId = serverId,
            sentPassage = sent,
            onAccepted = { result = null },
            onDeclined = { result = null },
            onClose = { result = null },
        )
    }
}

@Composable
private fun DwVerbChoice(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun DwVerbRefusal(sentence: String) {
    Text(
        sentence,
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
        lineHeight = 17.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun DwVerbTranslateStep(
    value: String,
    onValue: (String) -> Unit,
    onCancel: () -> Unit,
    onTranslate: (String) -> Unit,
) {
    val refusal = dwTranslationTargetRefusal(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= DW_VERB_MAX_LANGUAGE_CHARS) onValue(it) },
            label = { Text("Translate into") },
            placeholder = { Text("Odia, Hindi, English…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Language name or code, e.g. “Odia” or “or”. The original stays.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        if (refusal != null && value.isNotBlank()) {
            Text(refusal, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
        }
        ActionRow {
            Button(
                onClick = { onTranslate(value.trim()) },
                enabled = refusal == null,
            ) { Text("Translate this passage") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
