package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.destructiveAction
import com.designprototype.workshop.data.userMessage

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwQrEccLevel
import com.designprototype.workshop.data.DwQrEncode
import com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE
import com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE
import com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_EXTENSION
import com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_MIME
import com.designprototype.workshop.data.QuestionnaireHandoffRead
import com.designprototype.workshop.data.ReceivedQuestionnaire
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.questionnaireHandoffVerdict
import com.designprototype.workshop.data.readQuestionnaireHandoffCode
import com.designprototype.workshop.data.readableStamp
import com.designprototype.workshop.data.receivedQuestionnaireStatus
import com.designprototype.workshop.ui.DwQrLiveScanControl
import com.designprototype.workshop.ui.DwQrSymbolImage
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private fun Modifier.heightIn48(): Modifier = this.heightIn(min = 48.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionnaireHandoffCard(
    repository: WorkshopRepository,
    questionnaireId: String,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    var working by remember(questionnaireId) { mutableStateOf(false) }
    var built by remember(questionnaireId) {
        mutableStateOf<WorkshopRepository.QuestionnaireHandoffFile?>(null)
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Hand it to another phone",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Makes a small file to share offline (Nearby Share, Bluetooth, cable). They open it under “A questionnaire from another phone”.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            Text(
                QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )

            ActionRow {
                OutlinedButton(
                    onClick = {
                        if (busy) return@OutlinedButton
                        onBusyChange(true)
                        working = true
                        built = null
                        scope.launch {
                            runCatching {
                                repository.buildQuestionnaireHandoffFile(appContext, questionnaireId)
                            }
                                .onSuccess { built = it }
                                .onFailure { error ->
                                    if (error !is CancellationException) {
                                        onError(
                                            error.apiErrorMessage(
                                                "Couldn't make the file."
                                            )
                                        )
                                    }
                                }
                            working = false
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn48(),
                ) {
                    if (working) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (working) "Making the file…" else "Make the file", style = MaterialTheme.typography.labelLarge)
                }
            }

            built?.let { file ->
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Ready — ${plural(file.bundle.questionCount, "question")} in " +
                            plural(file.bundle.sections.count { it.questions.isNotEmpty() }, "section"),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(file.savedTo, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)

                    if (file.shareUri != null) {
                        OutlinedButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = QUESTIONNAIRE_BUNDLE_MIME
                                    putExtra(Intent.EXTRA_STREAM, file.shareUri)
                                    putExtra(
                                        Intent.EXTRA_SUBJECT,
                                        file.bundle.title.ifBlank { "Questionnaire" },
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(send, "Send the questionnaire")
                                )
                            },
                            modifier = Modifier.fillMaxWidth().heightIn48(),
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Send it")
                        }
                        Text(
                            "Offline? Use Nearby Share, Bluetooth or a cable — chat apps wait until you're online.",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                    } else {
                        Text(
                            "Find it in Downloads to send it.",
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.field.hairline)
                    HandoffCodePanel(code = file.handoffCode)
                }
            }
        }
    }
}

@Composable
private fun HandoffCodePanel(code: String) {
    val symbol = remember(code) { runCatching { DwQrEncode.encode(code, DwQrEccLevel.Q) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "The check code",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "A fingerprint, not the questionnaire. Have the receiver compare it to confirm the file arrived whole.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (symbol != null) {
                DwQrSymbolImage(
                    symbol = symbol,
                    label = "Check code for the questionnaire file",
                    modifier = Modifier.widthIn(max = 132.dp).aspectRatio(1f),
                )
            }
            Text(
                code,
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceivedQuestionnairesCard(
    repository: WorkshopRepository,
    incoming: Uri?,
    onIncomingConsumed: () -> Unit,
    onAdopted: () -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<ReceivedQuestionnaire>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var adopting by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(reload) {
        rows = runCatching { repository.receivedQuestionnaires(appContext) }.getOrDefault(emptyList())
    }

    var queuedDeliveries by remember { mutableStateOf<List<Uri>>(emptyList()) }

    fun receive(uri: Uri) {
        if (busy) {
            queuedDeliveries = queuedDeliveries + uri
            return
        }
        busy = true
        scope.launch {
            repository.receiveQuestionnaireHandoff(appContext, uri)
                .onSuccess { row ->
                    reload++
                    onMessage(
                        "“${row.title.ifBlank { row.filename }}” received — ${plural(row.questionCount, "question")}. Not added yet."
                    )
                }
                .onFailure { error -> onError(error.userMessage("That file could not be read.")) }
            busy = false
        }
    }

    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        receive(uri)
        onIncomingConsumed()
    }

    LaunchedEffect(queuedDeliveries, busy) {
        if (busy) return@LaunchedEffect
        val next = queuedDeliveries.firstOrNull() ?: return@LaunchedEffect
        queuedDeliveries = queuedDeliveries.drop(1)
        receive(next)
    }

    val pickBundle = rememberQuestionnaireBundlePicker { uri -> receive(uri) }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "A questionnaire from another phone",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Open a .$QUESTIONNAIRE_BUNDLE_EXTENSION file a colleague sent you (usually in Downloads).",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )

            ActionRow {
                OutlinedButton(
                    onClick = pickBundle,
                    enabled = !busy,
                    modifier = Modifier.heightIn48(),
                ) {
                    if (busy) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Reading the file…" else "Open a questionnaire file", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (rows.isEmpty()) {
                Text(
                    "Nothing has arrived on this phone yet.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE,
                    color = MaterialTheme.field.warning,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }

            rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.field.hairline)
                ReceivedQuestionnaireRow(
                    row = row,
                    busy = busy,
                    adopting = adopting == row.id,
                    progress = if (adopting == row.id) progress else null,
                    verdict = checked?.takeIf { it.first == row.id }?.second,
                    onAdopt = {
                        if (busy) return@ReceivedQuestionnaireRow
                        busy = true
                        adopting = row.id
                        progress = null
                        scope.launch {
                            repository.adoptReceivedQuestionnaire(
                                context = appContext,
                                row = row,
                                onProgress = { done, total ->
                                    progress = "$done of ${plural(total, "section")} added."
                                },
                            )
                                .onSuccess {
                                    onMessage(
                                        "“${row.title.ifBlank { row.filename }}” added to your questionnaires."
                                    )
                                    onAdopted()
                                }
                                .onFailure { error ->
                                    if (error !is CancellationException) {
                                        onError(error.userMessage("That questionnaire could not be added."))
                                    }
                                }
                            reload++
                            adopting = null
                            progress = null
                            busy = false
                        }
                    },
                    onDiscard = {
                        if (busy) return@ReceivedQuestionnaireRow
                        busy = true
                        scope.launch {
                            runCatching { repository.discardReceivedQuestionnaire(appContext, row.id) }
                            reload++
                            busy = false
                        }
                    },
                    onCheck = { scanned ->
                        checked = row.id to when (val read = readQuestionnaireHandoffCode(scanned)) {
                            is QuestionnaireHandoffRead.Refused -> read.message
                            is QuestionnaireHandoffRead.Ok ->
                                questionnaireHandoffVerdict(
                                    expected = read.digest,
                                    fileDigest = row.handoffDigest,
                                )
                        }
                    },
                    onScanRefused = onError,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceivedQuestionnaireRow(
    row: ReceivedQuestionnaire,
    busy: Boolean,
    adopting: Boolean,
    progress: String?,
    verdict: String?,
    onAdopt: () -> Unit,
    onDiscard: () -> Unit,
    onCheck: (String) -> Unit,
    onScanRefused: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            row.title.ifBlank { row.filename },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${row.filename} · arrived ${readableStamp(row.receivedAt) ?: row.receivedAt}",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            receivedQuestionnaireStatus(row),
            color = if (row.failure != null) MaterialTheme.field.warning else MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        progress?.let {
            Text(it, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        }

        if (!row.adopted) {
            ActionRow {
                OutlinedButton(onClick = onAdopt, enabled = !busy, modifier = Modifier.heightIn48()) {
                    if (adopting) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        when {
                            adopting -> "Adding…"
                            row.started -> "Carry on adding"
                            else -> "Add to my questionnaires"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                TextButton(onClick = onDiscard, enabled = !busy) {
                    Text("Throw it away", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                "Adding needs a connection. The file is kept until then.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            ActionRow {
                TextButton(modifier = Modifier.destructiveAction(), onClick = onDiscard, enabled = !busy) {
                    Text("Remove this file", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        DwQrLiveScanControl(
            enabled = !busy,
            onText = onCheck,
            onRefusal = onScanRefused,
            pictureLabel = null,
            captureLabel = null,
        )
        verdict?.let {
            Text(
                it,
                color = if (it.startsWith("This is the same")) {
                    MaterialTheme.field.body
                } else {
                    MaterialTheme.field.warning
                },
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
internal fun rememberQuestionnaireBundlePicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return {
        launcher.launch(
            arrayOf(
                QUESTIONNAIRE_BUNDLE_MIME,
                com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_MIME_FR,
                "application/gzip",
                "application/x-gzip",
                "application/octet-stream",
                "*/*",
            )
        )
    }
}
