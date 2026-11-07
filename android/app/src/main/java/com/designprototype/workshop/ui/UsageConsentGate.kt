package com.designprototype.workshop.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.USAGE_BASIS_REQUIRED_AT_SIGN_IN
import com.designprototype.workshop.data.USAGE_CONSENT_GRANTED
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.UsageClient
import com.designprototype.workshop.data.UsageConsentBody
import com.designprototype.workshop.data.UsageConsentStateDto
import com.designprototype.workshop.data.UsageNoticeDto
import com.designprototype.workshop.data.UsageNoticeStore
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.usageNowStamp
import kotlinx.coroutines.launch

@Stable
class UsageDoorState internal constructor(private val store: UsageNoticeStore) {

    var notice by mutableStateOf<UsageNoticeDto?>(null)
        private set

    var fetching by mutableStateOf(false)
        private set

    var agreed by mutableStateOf(false)
        private set

    var agreedAt by mutableStateOf<String?>(null)
        private set

    val noticeReady: Boolean
        get() = notice?.isUsable == true

    val mayProceed: Boolean
        get() = agreed

    fun agree(next: Boolean) {
        agreed = next
        agreedAt = if (next) usageNowStamp() else null
    }

    fun reset() {
        agreed = false
        agreedAt = null
    }

    internal fun seedFromDevice() {
        if (notice == null) notice = store.read()
    }

    suspend fun refresh(context: Context) {
        fetching = notice == null
        val fresh = runCatching { UsageClient.of(context).consentNotice() }.getOrNull()
        if (fresh != null && fresh.isUsable) {
            if (fresh.version != notice?.version) agree(false)
            notice = fresh
            store.write(fresh)
        }
        fetching = false
    }
}

@Composable
fun rememberUsageDoorState(): UsageDoorState {
    val context = LocalContext.current
    val state = remember(context) { UsageDoorState(UsageNoticeStore(context)).also { it.seedFromDevice() } }
    LaunchedEffect(state) { state.refresh(context) }
    return state
}

fun usageConsentBlocks(user: UserDto?): Boolean = user?.usageConsentGate?.required == true

suspend fun usageAnswerAtTheDoor(
    context: Context,
    repository: WorkshopRepository,
    user: UserDto,
    door: UsageDoorState,
): UserDto {
    val gate = user.usageConsentGate ?: return user
    if (!gate.required) return user
    if (!door.agreed) return user
    val version = door.notice?.version?.takeIf { it.isNotBlank() } ?: return user

    val recorded = runCatching {
        UsageClient.of(context).recordConsent(
            UsageConsentBody(
                decision = USAGE_CONSENT_GRANTED,
                basis = USAGE_BASIS_REQUIRED_AT_SIGN_IN,
                noticeVersion = version,
                recordedAt = door.agreedAt,
            )
        )
    }.getOrNull() ?: return user

    return runCatching { repository.refreshUser() }
        .getOrElse { user.copy(usageConsentGate = recorded.gate, usageConsent = recorded.consent.state) }
}

@Composable
fun UsageNoticeBody(notice: UsageNoticeDto, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoticeSection("What is recorded", notice.collects)
        NoticeSection("What is never recorded", notice.doesNotCollect)

        if (notice.requiredSentence.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, MaterialTheme.shapes.medium)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "This is required",
                    display = true,
                    color = MaterialTheme.field.onWarningContainer,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(notice.requiredSentence, color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (notice.durationCaveat.isNotBlank()) {
            NoticeParagraph("What \"how long it took\" is not", notice.durationCaveat)
        }

        if (notice.readableBy.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Who can read it",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                notice.readableBy.forEach { (_, who) ->
                    Text(who, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Taking it back",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            if (notice.withdrawal.where.isNotBlank()) {
                Text(notice.withdrawal.where, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
            }
            if (notice.withdrawal.costsNothing.isNotBlank()) {
                Text(
                    notice.withdrawal.costsNothing,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
            notice.withdrawal.does.forEach { Bullet(it) }
            notice.withdrawal.doesNot.forEach { Bullet(it) }
        }

        if (notice.retention.isNotBlank()) {
            NoticeParagraph("How long it is kept", notice.retention)
        }

        Text(
            "Notice version ${notice.version}" +
                (notice.document.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun NoticeSection(heading: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            heading,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
        lines.forEach { Bullet(it) }
    }
}

@Composable
private fun NoticeParagraph(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            heading,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(body, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
    }
}

@Composable
private fun Bullet(text: String) {
    Text("• $text", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
}

@Composable
fun UsageNoticeDisclosure(
    notice: UsageNoticeDto,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                USAGE_NOTICE_EXPANDER,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) UsageNoticeBody(notice)
    }
}

@Composable
private fun UsageAgreeRow(
    agreed: Boolean,
    onAgree: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.toggleable(
                value = agreed,
                role = Role.Checkbox,
                onValueChange = onAgree
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FieldCheckbox(checked = agreed, onCheckedChange = null)
            Text(
                USAGE_AGREE_LABEL,
                color = MaterialTheme.colorScheme.onSurface,
                style = style
            )
        }
        Text(
            USAGE_TERMS_LINK,
            modifier = Modifier
                .weight(1f)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Read the terms and conditions"
                ) { onOpenTerms() }
                .padding(vertical = 14.dp),
            color = MaterialTheme.colorScheme.primary,
            style = style,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline
        )
    }
}

@Composable
fun UsageConsentDoor(
    door: UsageDoorState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showTerms by remember { mutableStateOf(false) }
    val hint = usageDoorHint(
        agreed = door.agreed,
        noticeReady = door.noticeReady,
        stillFetching = door.fetching
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UsageAgreeRow(
            agreed = door.agreed,
            onAgree = { door.agree(it) },
            onOpenTerms = { showTerms = true }
        )
        if (hint != null) {
            Text(
                hint,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp
            )
        }
    }

    if (showTerms) {
        TermsDialog(
            notice = door.notice,
            onRetryNotice = { scope.launch { door.refresh(context) } },
            onDismiss = { showTerms = false }
        )
    }
}

@Composable
fun UsageConsentGateScreen(
    repository: WorkshopRepository,
    user: UserDto,
    onSatisfied: (UserDto) -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val online = ConnectivityObserver.isOnline(context)
    var state by remember { mutableStateOf<UsageConsentStateDto?>(null) }
    var read by remember { mutableStateOf<UsageReadState>(UsageReadState.Loading) }
    var agreed by remember { mutableStateOf(false) }
    var agreedAt by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var showTerms by remember { mutableStateOf(false) }

    suspend fun load() {
        read = UsageReadState.Loading
        val answer = runCatching { UsageClient.of(context).myConsent() }.getOrNull()
        if (answer == null) {
            read = UsageReadState.Failed
            return
        }
        state = answer
        answer.notice?.let { UsageNoticeStore(context).write(it) }
        read = UsageReadState.Answered(count = 1)
        if (!answer.gate.required) onSatisfied(user.copy(usageConsentGate = answer.gate))
    }

    LaunchedEffect(Unit) { load() }

    val notice = state?.notice

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            notice?.title?.takeIf { it.isNotBlank() } ?: "Recording how you use this platform",
            display = true,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        state?.gate?.reason?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp)
        }

        when {
            read is UsageReadState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(loadingListLine("recording notice"), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }

            read is UsageReadState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (online) usageCouldNotReadLine("recording notice") else usageOfflineLine("recording notice"),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
                Button(onClick = { scope.launch { load() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            }

            notice != null -> ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    UsageAgreeRow(
                        agreed = agreed,
                        onAgree = {
                            agreed = it
                            agreedAt = if (it) usageNowStamp() else null
                        },
                        onOpenTerms = { showTerms = true },
                        style = MaterialTheme.typography.bodyLarge
                    )

                    if (!agreed) {
                        Text(
                            USAGE_TICK_TO_CONTINUE,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    sendError?.let {
                        Text(
                            it,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 18.sp
                        )
                    }

                    Button(
                        enabled = agreed && !sending,
                        onClick = {
                            scope.launch {
                                sending = true
                                sendError = null
                                runCatching {
                                    UsageClient.of(context).recordConsent(
                                        UsageConsentBody(
                                            decision = USAGE_CONSENT_GRANTED,
                                            basis = USAGE_BASIS_REQUIRED_AT_SIGN_IN,
                                            noticeVersion = notice.version,
                                            recordedAt = agreedAt,
                                        )
                                    )
                                }.onSuccess { recorded ->
                                    val refreshed = runCatching { repository.refreshUser() }
                                        .getOrElse {
                                            user.copy(
                                                usageConsentGate = recorded.gate,
                                                usageConsent = recorded.consent.state
                                            )
                                        }
                                    onSatisfied(refreshed)
                                }.onFailure { failure ->
                                    sendError = failure.apiErrorMessage(
                                        if (online) {
                                            "Couldn't send your answer. Try again."
                                        } else {
                                            "Offline — nothing recorded. Try again when connected."
                                        }
                                    )
                                }
                                sending = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (sending) "Recording your answer…" else "Agree and continue")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out instead")
        }
        TextButton(
            onClick = { scope.launch { load() } },
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape
        ) {
            Text("Reload this question", style = MaterialTheme.typography.labelMedium)
        }
    }

    if (showTerms) {
        TermsDialog(
            notice = notice,
            onRetryNotice = { scope.launch { load() } },
            onDismiss = { showTerms = false }
        )
    }
}
