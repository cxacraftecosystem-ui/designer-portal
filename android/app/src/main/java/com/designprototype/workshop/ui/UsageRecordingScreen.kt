package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.USAGE_BASIS_OFFERED_IN_SETTINGS
import com.designprototype.workshop.data.USAGE_CONSENT_GRANTED
import com.designprototype.workshop.data.USAGE_CONSENT_REFUSED
import com.designprototype.workshop.data.UsageClient
import com.designprototype.workshop.data.UsageConsentBody
import com.designprototype.workshop.data.UsageConsentStateDto
import com.designprototype.workshop.data.UsageEventDto
import com.designprototype.workshop.data.UsageMineDto
import com.designprototype.workshop.data.UsageTrailDto
import com.designprototype.workshop.data.UsageWithdrawBody
import com.designprototype.workshop.data.UsageWithdrawalOutcomeDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.usageCount
import com.designprototype.workshop.data.usageDurationText
import com.designprototype.workshop.data.usageMoment
import com.designprototype.workshop.data.usageNowStamp
import com.designprototype.workshop.data.usageWindowStart
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageRecordingScreen(
    repository: WorkshopRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var consent by remember { mutableStateOf<UsageConsentStateDto?>(null) }
    var consentRead by remember { mutableStateOf<UsageReadState>(UsageReadState.Loading) }
    var mine by remember { mutableStateOf<UsageMineDto?>(null) }
    var trail by remember { mutableStateOf<UsageTrailDto?>(null) }
    var trailWindow by remember { mutableStateOf<Pair<String, String>?>(null) }
    var trailMore by remember { mutableStateOf(false) }
    var trailLoading by remember { mutableStateOf(false) }
    var trailFailed by remember { mutableStateOf(false) }
    var recordRead by remember { mutableStateOf<UsageReadState>(UsageReadState.Loading) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var withdrawal by remember { mutableStateOf<UsageWithdrawalOutcomeDto?>(null) }
    var confirmWithdraw by remember { mutableStateOf(false) }
    var noticeOpen by remember { mutableStateOf(false) }

    var online by remember { mutableStateOf(repository.isOnline(context)) }

    suspend fun loadConsent() {
        consentRead = UsageReadState.Loading
        online = repository.isOnline(context)
        val answer = runCatching { UsageClient.of(context).myConsent() }.getOrNull()
        consent = answer
        consentRead = if (answer == null) UsageReadState.Failed else UsageReadState.Answered(1)
    }

    suspend fun loadRecord() {
        recordRead = UsageReadState.Loading
        online = repository.isOnline(context)
        val from = usageWindowStart(RECORD_WINDOW_DAYS)
        val to = usageNowStamp()
        val api = UsageClient.of(context)
        val aggregate = runCatching { api.myUsage(from = from, to = to) }.getOrNull()
        val log = runCatching {
            api.myTrail(from = from, to = to, limit = TRAIL_PAGE, offset = 0)
        }.getOrNull()
        mine = aggregate
        trail = log
        trailWindow = from to to
        trailMore = usageTrailHasMore(log?.events?.size ?: 0, TRAIL_PAGE)
        trailFailed = false
        recordRead = when {
            aggregate == null && log == null -> UsageReadState.Failed
            else -> UsageReadState.Answered(count = log?.events?.size ?: aggregate?.requests ?: 0)
        }
    }

    suspend fun loadMoreTrail() {
        val held = trail ?: return
        val (from, to) = trailWindow ?: return
        trailLoading = true
        trailFailed = false
        val next = runCatching {
            UsageClient.of(context).myTrail(from = from, to = to, limit = TRAIL_PAGE, offset = held.events.size)
        }.getOrNull()
        if (next == null) {
            trailFailed = true
        } else {
            trail = held.copy(events = held.events + next.events)
            trailMore = usageTrailHasMore(next.events.size, TRAIL_PAGE)
        }
        trailLoading = false
    }

    LaunchedEffect(Unit) {
        loadConsent()
        loadRecord()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage recording") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            item {
                PreferenceCard {
                    val state = consent?.consent?.state
                    Text(
                        if (state != null) usageConsentHeading(state) else "Your answer",
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    usageReadNotice(
                        state = consentRead,
                        noun = "answer",
                        online = online,
                        emptyLine = "Your answer could not be shown. Try again."
                    )?.let {
                        Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                    }
                    consent?.consent?.let { record -> usageConsentDetail(record) }?.let {
                        Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                    }
                    consent?.gate?.reason?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                    }
                    if (consentRead is UsageReadState.Failed) {
                        ActionRow {
                            Button(onClick = { scope.launch { loadConsent() } }) { Text("Try again") }
                        }
                    }
                }
            }

            item {
                val record = consent
                if (record != null) {
                    PreferenceCard {
                        actionError?.let {
                            Text(
                                it,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 18.sp
                            )
                        }
                        withdrawal?.let { outcome ->
                            Text(
                                outcome.explanation,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = if (outcome.storedDeleteRan) {
                                    MaterialTheme.field.muted
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 18.sp
                            )
                            Text(
                                "${usageCount(outcome.storedDeleted)} stored, " +
                                    "${usageCount(outcome.bufferedDropped)} not yet written.",
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (record.consent.state == USAGE_CONSENT_GRANTED) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { confirmWithdraw = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(USAGE_WITHDRAW_LABEL) }
                            Text(
                                record.notice?.withdrawal?.costsNothing.orEmpty().ifBlank {
                                    "You stay signed in and lose nothing."
                                },
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 17.sp
                            )
                        } else {
                            val version = record.notice?.version ?: record.gate.noticeVersion
                            Button(
                                enabled = !busy && version.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        actionError = null
                                        runCatching {
                                            UsageClient.of(context).recordConsent(
                                                UsageConsentBody(
                                                    decision = USAGE_CONSENT_GRANTED,
                                                    basis = USAGE_BASIS_OFFERED_IN_SETTINGS,
                                                    noticeVersion = version,
                                                    recordedAt = usageNowStamp(),
                                                )
                                            )
                                        }.onSuccess {
                                            consent = it
                                            withdrawal = null
                                            loadRecord()
                                        }.onFailure {
                                            actionError = it.usageWriteFailureLine(online)
                                        }
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(USAGE_REGRANT_LABEL) }
                            if (record.consent.state == USAGE_CONSENT_REFUSED) {
                                Text(
                                    "Nothing is recorded while declined. Agreeing again starts fresh.",
                                    color = MaterialTheme.field.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                consent?.notice?.let { notice ->
                    PreferenceCard {
                        UsageNoticeDisclosure(
                            notice = notice,
                            expanded = noticeOpen,
                            onExpandedChange = { noticeOpen = it }
                        )
                    }
                }
            }

            item {
                PreferenceCard {
                    Text(
                        "What is held about you",
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Last $RECORD_WINDOW_DAYS days, newest first.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    usageReadNotice(
                        state = recordRead,
                        noun = "requests recorded about you",
                        online = online,
                        emptyLine = "Nothing recorded in this period."
                    )?.let {
                        Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                    }
                    mine?.let { aggregate ->
                        Text(
                            "${usageCount(aggregate.requests)} requests, across " +
                                "${usageCount(aggregate.routes.size)} screens.",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    trail?.notes.orEmpty().forEach {
                        Text("• $it", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                    }
                    if (recordRead is UsageReadState.Failed) {
                        ActionRow {
                            Button(onClick = { scope.launch { loadRecord() } }) { Text("Try again") }
                        }
                    }
                    if (recordRead is UsageReadState.Loading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                loadingListLine("recorded requests"),
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            items(trail?.events.orEmpty()) { event -> TrailRow(event) }

            if (trail != null && (trailMore || trailFailed)) {
                item {
                    ListReachButton(
                        shown = trail?.events?.size ?: 0,
                        total = 0,
                        label = ListReachText.showMore(TRAIL_PAGE),
                        loading = trailLoading,
                        failed = trailFailed,
                        tag = "usage.trail.more",
                        onClick = { scope.launch { loadMoreTrail() } },
                    )
                }
            }

            item {
                PreferenceCard {
                    Text(
                        "Your answers, dated",
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    val decisions = consent?.decisions.orEmpty()
                    if (consentRead is UsageReadState.Answered && decisions.isEmpty()) {
                        Text(
                            USAGE_NO_DECISIONS_LINE,
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp
                        )
                    }
                    decisions.forEach { row ->
                        Text(
                            usageDecisionLine(row),
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }

    if (confirmWithdraw) {
        AlertDialog(
            onDismissRequest = { confirmWithdraw = false },
            title = { Text("Withdraw your agreement?") },
            text = { Text(USAGE_WITHDRAW_CONFIRM, style = MaterialTheme.typography.bodyMedium, lineHeight = 18.sp) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmWithdraw = false
                        scope.launch {
                            busy = true
                            actionError = null
                            val version = consent?.notice?.version
                                ?: consent?.gate?.noticeVersion.orEmpty()
                            runCatching {
                                UsageClient.of(context).withdrawConsent(
                                    UsageWithdrawBody(
                                        noticeVersion = version,
                                        recordedAt = usageNowStamp(),
                                    )
                                )
                            }.onSuccess { answer ->
                                consent = answer
                                withdrawal = answer.withdrawal
                                loadRecord()
                            }.onFailure {
                                actionError = it.usageWriteFailureLine(online)
                            }
                            busy = false
                        }
                    }
                ) { Text(USAGE_WITHDRAW_LABEL, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWithdraw = false }) { Text("Keep it as it is") }
            }
        )
    }
}

@Composable
private fun TrailRow(event: UsageEventDto) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            event.routeTemplate,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp
        )
        Text(
            buildString {
                append(usageMoment(event.at) ?: event.at)
                append(" · ")
                append(if (event.statusCode in 1..399) "Worked" else "Failed")
                append(" · ")
                append(usageDurationText(event.durationMs))
                if (event.clientApp.isNotBlank()) {
                    append(" · ")
                    append(event.clientApp)
                }
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun Throwable.usageWriteFailureLine(online: Boolean): String = apiErrorMessage(
    if (online) {
        "Couldn't send your answer. Nothing changed."
    } else {
        "Offline — nothing changed. Try again when connected."
    }
)

private const val RECORD_WINDOW_DAYS: Long = 7

private const val TRAIL_PAGE: Int = 50

internal fun usageTrailHasMore(lastPageSize: Int, pageSize: Int): Boolean = pageSize > 0 && lastPageSize >= pageSize
