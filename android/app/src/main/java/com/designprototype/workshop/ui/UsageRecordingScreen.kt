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
import androidx.compose.material3.CircularProgressIndicator
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

/**
 * **USAGE RECORDING — this account's own answer, its own record, and the way out.**
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS SCREEN EXISTS ON THE HANDSET AND NOT ONLY ON THE WEB
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Agreeing is a condition of using the product, asked at a turnstile the person cannot get past
 * without answering. That is defensible for exactly one reason, and it is not that it is documented:
 * it is that the agreement can be TAKEN BACK, at no cost, by the person who gave it. If withdrawal
 * lived only on a laptop, then for the designers who work from a phone in a courtyard — which is
 * most of them, and the ones the recording is mostly about — the turnstile would be one-way, and
 * "you can withdraw at any time" would be a sentence about somebody else's device.
 *
 * So the withdrawal is here, on the client where the consent is most often given.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE FOUR THINGS ON IT, IN THE ORDER THEY MATTER
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * 1. **The answer on record**, with its DATE and its CIRCUMSTANCE. "You agreed" over a turnstile is
 *    a sentence that misleads by omission; the basis column exists so the record can say it was a
 *    condition of access, and a client that stored that and did not show it would be keeping an
 *    honest record and publishing a misleading one.
 * 2. **The controls** — withdraw, or agree again. Both, because a refusal that cannot be changed is
 *    a state rather than a choice, and a grant that cannot be withdrawn is not an agreement.
 * 3. **What is actually held**, which is what makes the notice's promise true rather than
 *    aspirational: "you can see exactly what we hold about you" is only honest once
 *    `GET /usage/me/trail` is on a screen. It is the aggregate AND the log, because they answer
 *    different questions and the log is the one a person actually means.
 * 4. **The decision log**, so this reads as a record of decisions and not as a toggle. A consent
 *    that shows only its current value invites a switch; one that shows "agreed on the 3rd at
 *    sign-in, withdrawn on the 9th in settings" is visibly a record.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * NOTHING ON THIS SCREEN IS CACHED, AND THAT IS DELIBERATE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Every other read-heavy screen in this app degrades to a copy on the device, because a design
 * workshop is a dated observation and yesterday's copy of it is still true. A person's usage trail is
 * the opposite kind of fact in the one way that counts: **withdrawing deletes it from the server**,
 * and a device that had kept a copy would still be holding, and showing, the very rows the person
 * asked to have destroyed. So this screen holds nothing between visits, says so when it cannot reach
 * the server, and never draws an empty list as an answer. (The notice itself IS kept — see
 * `UsageNoticeStore` — because it is published text about the policy and holds nobody's data.)
 */
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
    var recordRead by remember { mutableStateOf<UsageReadState>(UsageReadState.Loading) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var withdrawal by remember { mutableStateOf<UsageWithdrawalOutcomeDto?>(null) }
    var confirmWithdraw by remember { mutableStateOf(false) }
    var noticeOpen by remember { mutableStateOf(false) }

    // `isOnline` and not a fresh probe of our own: this app has ONE idea of whether it can reach the
    // server, and a second one here would let this screen call a dead tunnel a server fault while the
    // outbox behind it calls the same failure worth retrying.
    //
    // RE-READ AT EVERY LOAD rather than sampled once on entry. This screen is retried by somebody who
    // has just walked out of a building, and a value frozen at the moment they opened it would keep
    // telling them the phone has no connection long after it has one — sending them back up the hill
    // the sentence exists to stop them climbing.
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
        recordRead = when {
            // BOTH FAILING IS A FAILURE; one failing is not a reason to claim the other said nothing.
            aggregate == null && log == null -> UsageReadState.Failed
            else -> UsageReadState.Answered(count = log?.events?.size ?: aggregate?.requests ?: 0)
        }
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

            // ── 1. THE ANSWER ON RECORD ────────────────────────────────────────────────────────
            item {
                PreferenceCard {
                    val state = consent?.consent?.state
                    Text(
                        if (state != null) usageConsentHeading(state) else "Your answer",
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    usageReadNotice(
                        state = consentRead,
                        noun = "answer",
                        online = online,
                        // Unreachable in practice — the server always has an answer, even if it is
                        // NOT_RECORDED — and written anyway, because "unreachable" is what everybody
                        // said about the states the six shared sentences exist for.
                        emptyLine = "This server did not say what your answer is."
                    )?.let {
                        Text(it, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    consent?.consent?.let { record -> usageConsentDetail(record) }?.let {
                        Text(it, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    // The SERVER's sentence for this state, not a summary of it. Three states reach
                    // three different sentences because the next moves differ.
                    consent?.gate?.reason?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    if (consentRead is UsageReadState.Failed) {
                        Button(onClick = { scope.launch { loadConsent() } }) { Text("Try again") }
                    }
                }
            }

            // ── 2. THE CONTROLS ───────────────────────────────────────────────────────────────
            item {
                val record = consent
                if (record != null) {
                    PreferenceCard {
                        actionError?.let {
                            Text(
                                it,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                        withdrawal?.let { outcome ->
                            // The server's own explanation, both when the delete ran and when it did
                            // not. `storedDeleteRan = false` is never drawn as "0 rows deleted",
                            // which reads as "there was nothing to delete" — the opposite fact.
                            Text(
                                outcome.explanation,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = if (outcome.storedDeleteRan) {
                                    MaterialTheme.field.muted
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Text(
                                "${usageCount(outcome.storedDeleted)} stored, " +
                                    "${usageCount(outcome.bufferedDropped)} not yet written.",
                                color = MaterialTheme.field.muted,
                                fontSize = 12.sp
                            )
                        }

                        if (record.consent.state == USAGE_CONSENT_GRANTED) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { confirmWithdraw = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(USAGE_WITHDRAW_LABEL) }
                            Text(
                                // Said BESIDE the button and not only inside the dialog, because the
                                // fear that stops somebody pressing it is the fear of losing access,
                                // and a reassurance they only meet after pressing is a reassurance
                                // they never meet.
                                record.notice?.withdrawal?.costsNothing.orEmpty().ifBlank {
                                    "Withdrawing does not sign you out and takes nothing away."
                                },
                                color = MaterialTheme.field.muted,
                                fontSize = 12.sp,
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
                                                    // FREELY, and the server records it as such. This
                                                    // is the half of the vocabulary that makes the
                                                    // turnstile at the door defensible: the grants
                                                    // taken there are conditions of access, and the
                                                    // ones taken here are choices.
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
                                    "Nothing is being recorded while this stands, and nothing was " +
                                        "kept from before. Agreeing again starts a new record from " +
                                        "that moment — it does not bring anything back.",
                                    color = MaterialTheme.field.muted,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── THE NOTICE ────────────────────────────────────────────────────────────────────
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

            // ── 3. WHAT IS ACTUALLY HELD ──────────────────────────────────────────────────────
            item {
                PreferenceCard {
                    Text(
                        "What is held about you",
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        "The last $RECORD_WINDOW_DAYS days, newest first, up to $TRAIL_PAGE requests.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    usageReadNotice(
                        state = recordRead,
                        noun = "requests recorded about you",
                        online = online,
                        // NOT "you have never used the app", which is exactly what an empty list
                        // reads as and exactly the defect the route's own docstring names. The
                        // truthful reading of an empty trail depends on the ANSWER, and the answer is
                        // on screen a card above; this sentence names the two causes rather than
                        // implying the person did nothing.
                        emptyLine = "Nothing was recorded about you in this window. That can mean " +
                            "you made no requests in it, or that nothing of yours is being recorded " +
                            "at all — your answer above says which."
                    )?.let {
                        Text(it, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    mine?.let { aggregate ->
                        Text(
                            "${usageCount(aggregate.requests)} requests, across " +
                                "${usageCount(aggregate.routes.size)} screens.",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    // The server's own notes about what a trail is and is not — that an absence of
                    // rows is not an absence of work, and that a duration is server time only.
                    trail?.notes.orEmpty().forEach {
                        Text("• $it", color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    if (recordRead is UsageReadState.Failed) {
                        Button(onClick = { scope.launch { loadRecord() } }) { Text("Try again") }
                    }
                    if (recordRead is UsageReadState.Loading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                loadingListLine("recorded requests"),
                                color = MaterialTheme.field.muted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // NO `key` LAMBDA, deliberately. A LazyColumn CRASHES on duplicate keys, and the
            // obvious key here is `event.id` — which is `""` for every row if a deployment ever
            // serves the field empty, turning a screen that reports a privacy record into a screen
            // that reports a stack trace. There is nothing for a key to buy: the list is replaced
            // wholesale on every reload, never reordered and never partially updated, so positional
            // identity is exactly right.
            items(trail?.events.orEmpty()) { event -> TrailRow(event) }

            trail?.let { log ->
                if (log.events.size >= log.limit && log.limit > 0) {
                    item {
                        Text(
                            // The cap is STATED rather than left to be discovered. A list that
                            // quietly stops is indistinguishable from a short list, and here the
                            // short reading would be "this is everything the platform holds".
                            "This is the newest $TRAIL_PAGE of your recorded requests, not all of " +
                                "them. The server will not send more than ${log.maxRows} in one go.",
                            color = MaterialTheme.field.muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            // ── 4. THE DECISION LOG ───────────────────────────────────────────────────────────
            item {
                PreferenceCard {
                    Text(
                        "Your answers, dated",
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    val decisions = consent?.decisions.orEmpty()
                    if (consentRead is UsageReadState.Answered && decisions.isEmpty()) {
                        Text(
                            USAGE_NO_DECISIONS_LINE,
                            color = MaterialTheme.field.muted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    decisions.forEach { row ->
                        Text(
                            usageDecisionLine(row),
                            color = MaterialTheme.field.muted,
                            fontSize = 13.sp,
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
            text = { Text(USAGE_WITHDRAW_CONFIRM, fontSize = 13.sp, lineHeight = 18.sp) },
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
                                // Re-read rather than emptying the list here. The server is the one
                                // that knows whether the delete ran, and a client that cleared its
                                // own view on the strength of having asked would show an empty
                                // record over a `storedDeleteRan = false` that says otherwise.
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

/**
 * One recorded request, as the log replays it.
 *
 * THE ROUTE TEMPLATE IS PRINTED IN A MONOSPACE FACE AND NOT TRANSLATED into a screen name, because
 * there is no honest translation: "A route template is not a feature" — one template serves several
 * things a person would name differently, and one screen calls several templates. Inventing a
 * friendly name here would be this client asserting a mapping the server explicitly does not have.
 */
@Composable
private fun TrailRow(event: UsageEventDto) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "${event.method} ${event.routeTemplate}",
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Text(
            buildString {
                append(usageMoment(event.at) ?: event.at)
                append(" · ")
                append(event.statusCode)
                append(" · ")
                append(usageDurationText(event.durationMs))
                if (event.clientApp.isNotBlank()) {
                    append(" · ")
                    append(event.clientApp)
                }
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
    }
}

/**
 * What to say when a consent WRITE did not land.
 *
 * Two sentences and not one, on `outboxDeviceBanner`'s rule: telling somebody with four bars to go
 * and find a signal sends them up a hill that cannot help, and telling somebody in a tunnel that the
 * server refused them sends them to an administrator who cannot either. Both say plainly that
 * NOTHING WAS RECORDED, because the alternative — a screen that leaves it ambiguous after a
 * withdrawal — leaves a person believing their record was deleted when it was not.
 */
private fun Throwable.usageWriteFailureLine(online: Boolean): String = apiErrorMessage(
    if (online) {
        "Your answer did not reach the server, so nothing has been changed. Try again."
    } else {
        "This phone has no connection, so your answer was not sent and nothing has been changed. " +
            "Try again where there is a signal."
    }
)

/** The window this screen reads. A week, matching the web usage page's own default. */
private const val RECORD_WINDOW_DAYS: Long = 7

/**
 * How many trail rows to ask for.
 *
 * Well under the server's `maxTrailRows` of 200, and deliberately: this is a phone, the rows are
 * read by a person scrolling with a thumb, and asking for the cap would spend a rural connection on
 * 150 rows nobody scrolls to. The screen SAYS it is showing the newest [TRAIL_PAGE] rather than
 * implying it has everything — a list that quietly stops is indistinguishable from a short one.
 */
private const val TRAIL_PAGE: Int = 50
