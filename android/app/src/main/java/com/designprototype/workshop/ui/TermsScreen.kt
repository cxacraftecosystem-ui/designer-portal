package com.designprototype.workshop.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.designprototype.workshop.data.UsageClient
import com.designprototype.workshop.data.UsageNoticeDto
import com.designprototype.workshop.data.UsageNoticeStore
import kotlinx.coroutines.launch

data class TermsClause(val number: Int, val title: String, val body: String)

const val TERMS_TITLE: String = "Terms and conditions"

const val TERMS_INTRO: String =
    "These terms govern your use of the Design Prototype Workshop platform. Ticking the box on the " +
        "sign-in screen accepts them."

val TERMS_CLAUSES: List<TermsClause> = listOf(
    TermsClause(
        1,
        "Who may use the platform",
        "Access is by invitation. An administrator approves an account before it can sign in, and " +
            "may set, change or withdraw its tier at any time."
    ),
    TermsClause(
        2,
        "Your account",
        "An account belongs to one person. Keep your credentials to yourself, and tell an " +
            "administrator at once if you believe someone else has them. Every record, edit and " +
            "review is stored against the account that made it."
    ),
    TermsClause(
        3,
        "What you record",
        "Record only what you observed, and only with the knowledge of the artisans, groups and " +
            "institutions concerned. Photographs, recordings and identity details of other people " +
            "are entered on their behalf, so their agreement is yours to obtain before you enter them."
    ),
    TermsClause(
        4,
        "Identity numbers",
        "Aadhaar and Pehchan card numbers are stored masked and are never shown in lists, exports " +
            "or reports. Do not enter a regulated identity number anywhere other than the field " +
            "provided for it."
    ),
    TermsClause(
        5,
        "The material you enter",
        "Records, media and reports created here belong to the programme that commissioned the " +
            "workshop. You keep the right to be identified as their author, and your name travels " +
            "with them."
    ),
    TermsClause(
        6,
        "Offline use",
        "The Android app holds work on the device when there is no signal and sends it when there " +
            "is. Work held on a device is your responsibility until it has sent — do not uninstall " +
            "the app or clear its data while anything is still waiting to upload."
    ),
    TermsClause(
        7,
        "Availability",
        "The platform is provided as it stands. Maintenance, releases and connectivity can " +
            "interrupt it, and no uptime is guaranteed."
    ),
    TermsClause(
        8,
        "Suspension",
        "An administrator may suspend or remove an account that is misused, shared, or used to " +
            "enter material the account holder had no right to enter."
    ),
    TermsClause(
        9,
        "Changes",
        "These terms and the recording notice below can change. The version you agreed to is " +
            "recorded against your account, so it is always possible to establish which words were " +
            "on screen when you agreed."
    ),
)

val TERMS_RECORDING_CLAUSE_NUMBER: Int get() = TERMS_CLAUSES.size + 1

const val TERMS_RECORDING_CLAUSE_TITLE: String = "How your use of the platform is recorded"

const val TERMS_NOTICE_UNAVAILABLE_LINE: String =
    "The recording notice could not be read, so it is not shown here. Everything above still " +
        "applies. It is also in Settings once you have signed in."

@Composable
fun TermsScreen(
    notice: UsageNoticeDto?,
    onRetryNotice: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            TERMS_INTRO,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 19.sp
        )

        TERMS_CLAUSES.forEach { clause ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${clause.number}. ${clause.title}",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    clause.body,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.field.hairline)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$TERMS_RECORDING_CLAUSE_NUMBER. $TERMS_RECORDING_CLAUSE_TITLE",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
            if (notice != null && notice.isUsable) {
                UsageNoticeBody(notice)
            } else {
                Text(
                    TERMS_NOTICE_UNAVAILABLE_LINE,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp
                )
                onRetryNotice?.let { retry ->
                    ActionRow {
                        TextButton(onClick = retry) { Text("Try again", style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
    }
}

@Composable
fun TermsDialog(
    notice: UsageNoticeDto?,
    onRetryNotice: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close the terms and conditions",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        TERMS_TITLE,
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
                TermsScreen(notice = notice, onRetryNotice = onRetryNotice)
            }
        }
    }
}

@Composable
fun TermsMenuDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { UsageNoticeStore(context) }
    var notice by remember(context) { mutableStateOf(store.read()) }

    suspend fun load(appContext: Context) {
        val fresh = runCatching { UsageClient.of(appContext).consentNotice() }.getOrNull()
        if (fresh != null && fresh.isUsable) {
            notice = fresh
            store.write(fresh)
        }
    }

    LaunchedEffect(context) { load(context) }

    TermsDialog(
        notice = notice,
        onRetryNotice = { scope.launch { load(context) } },
        onDismiss = onDismiss
    )
}
