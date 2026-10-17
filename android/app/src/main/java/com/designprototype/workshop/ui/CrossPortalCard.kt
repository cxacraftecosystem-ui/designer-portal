package com.designprototype.workshop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.CrossPortalClient
import com.designprototype.workshop.data.CrossPortalCopy
import com.designprototype.workshop.data.CrossPortalLinkDto
import com.designprototype.workshop.data.CrossPortalMatchDto
import com.designprototype.workshop.data.CrossPortalPrefill
import kotlinx.coroutines.launch

/**
 * C27 on the handset — the twin of the web's `components/forms/CrossPortalLookup.tsx`, same words
 * ([CrossPortalCopy]). Draws NOTHING until the server says the feature is on. The consent tick is an
 * agreement, not a setting, so it is a checkbox row (§11.11), as Aadhaar's consent is.
 */
@Composable
fun rememberCrossPortalEnabled(): Pair<Boolean, CrossPortalClient> {
    val context = LocalContext.current
    val client = remember { CrossPortalClient.create(context) }
    var enabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enabled = client.enabled() }
    return enabled to client
}

@Composable
private fun ConsentRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(CrossPortalCopy.CONSENT, fontSize = 14.sp)
    }
}

@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) { content() }
}

/** A new artisan: find by the numbers typed above, then fill the form in one press. */
@Composable
fun CrossPortalFindCard(
    aadhaar: String,
    pehchanNumber: String,
    filled: CrossPortalMatchDto?,
    onFill: (CrossPortalMatchDto) -> Unit,
) {
    val (enabled, client) = rememberCrossPortalEnabled()
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<CrossPortalMatchDto?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    if (!enabled) return
    if (filled != null) {
        Text(CrossPortalCopy.filled(CrossPortalPrefill.summary(filled)), fontSize = 14.sp)
        return
    }
    CardFrame {
        Text(CrossPortalCopy.HEADING, fontWeight = FontWeight.SemiBold)
        ConsentRow(consent) { consent = it }
        val found = match
        if (found != null) {
            Text(CrossPortalCopy.found(CrossPortalPrefill.summary(found)), fontSize = 14.sp)
            ActionRow {
                Button(onClick = { onFill(found) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(CrossPortalCopy.FILL) }
                OutlinedButton(onClick = { match = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(CrossPortalCopy.NOT_THIS)
                }
            }
        } else {
            ActionRow {
                OutlinedButton(
                    enabled = consent && !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        if (aadhaar.isBlank() && pehchanNumber.isBlank()) {
                            note = CrossPortalCopy.NEED_NUMBER
                            return@OutlinedButton
                        }
                        busy = true
                        note = null
                        scope.launch {
                            runCatching { client.lookup(aadhaar, pehchanNumber) }
                                .onSuccess { answer ->
                                    if (answer != null) match = answer else note = CrossPortalCopy.NOT_FOUND
                                }
                                .onFailure { note = CrossPortalCopy.UNREACHABLE }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Looking…" else CrossPortalCopy.FIND) }
            }
        }
        note?.let {
            Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** A saved artisan: the link, Refresh and Unlink — or Link, for one saved before. */
@Composable
fun CrossPortalLinkCard(artisanId: String, onRefreshed: () -> Unit = {}) {
    val (enabled, client) = rememberCrossPortalEnabled()
    val scope = rememberCoroutineScope()
    var link by remember(artisanId) { mutableStateOf<CrossPortalLinkDto?>(null) }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled, artisanId) {
        if (enabled) link = runCatching { client.link(artisanId) }.getOrDefault(CrossPortalLinkDto())
    }
    val current = link
    if (!enabled || current == null) return

    fun run(action: suspend () -> Unit) {
        busy = true
        note = null
        scope.launch {
            runCatching { action() }.onFailure { note = CrossPortalCopy.UNREACHABLE }
            busy = false
        }
    }

    CardFrame {
        if (!current.linked) {
            Text("Field Repository", fontWeight = FontWeight.SemiBold)
            ConsentRow(consent) { consent = it }
            ActionRow {
                OutlinedButton(
                    enabled = consent && !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { run { link = client.createLink(artisanId, null) } },
                ) { Text(CrossPortalCopy.LINK) }
            }
        } else {
            Text(CrossPortalCopy.LINKED, fontSize = 14.sp)
            ActionRow {
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        run {
                            val answer = client.refresh(artisanId)
                            link = answer.link
                            note = if (answer.changed.isNotEmpty()) CrossPortalCopy.UPDATED else CrossPortalCopy.UP_TO_DATE
                            if (answer.changed.isNotEmpty()) onRefreshed()
                        }
                    },
                ) { Text(CrossPortalCopy.REFRESH) }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).destructiveAction(),
                    onClick = { run { link = client.unlink(artisanId) } },
                ) { Text(CrossPortalCopy.UNLINK, color = MaterialTheme.colorScheme.error) }
            }
        }
        note?.let {
            Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
