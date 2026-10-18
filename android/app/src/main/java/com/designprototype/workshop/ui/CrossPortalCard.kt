package com.designprototype.workshop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.CrossPortalClient
import com.designprototype.workshop.data.CrossPortalCopy
import com.designprototype.workshop.data.CrossPortalFieldsDto
import com.designprototype.workshop.data.CrossPortalLinkDto
import com.designprototype.workshop.data.CrossPortalMatchDto
import com.designprototype.workshop.data.CrossPortalPrefill
import com.designprototype.workshop.data.apiRefusal
import kotlinx.coroutines.launch

/**
 * C27 on the handset — the twin of the web's `components/forms/CrossPortalLookup.tsx`, same words
 * ([CrossPortalCopy]). Draws NOTHING until the server says the feature is on. The consent tick is an
 * agreement, not a setting, so it is a checkbox row (§11.11), as Aadhaar's consent is, and it names
 * every item the lookup shares, from the server's list. A refusal prints the server's own sentence;
 * only a failure to reach it reads "can't be reached". Unlink asks first.
 */
data class CrossPortalUiState(val enabled: Boolean = false, val sharedFields: String? = null)

@Composable
fun rememberCrossPortal(): Pair<CrossPortalUiState, CrossPortalClient> {
    val context = LocalContext.current
    val client = remember { CrossPortalClient.create(context) }
    var state by remember { mutableStateOf(CrossPortalUiState()) }
    LaunchedEffect(Unit) {
        val status = client.status()
        state = CrossPortalUiState(status.enabled, status.sharedFields)
    }
    return state to client
}

/** One target for the whole row: the box and its words toggle together, announced as a checkbox. */
@Composable
private fun ConsentRow(checked: Boolean, words: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(words, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
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

@Composable
private fun Note(text: String?) {
    text?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** A new artisan: find by the numbers typed above, then fill the form in one press. */
@Composable
fun CrossPortalFindCard(
    aadhaar: String,
    pehchanNumber: String,
    filled: CrossPortalMatchDto?,
    onFill: (CrossPortalMatchDto) -> Unit,
) {
    val (status, client) = rememberCrossPortal()
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<CrossPortalMatchDto?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    if (!status.enabled) return
    if (filled != null) {
        Text(CrossPortalCopy.filled(CrossPortalPrefill.summary(filled)), style = MaterialTheme.typography.bodyMedium)
        return
    }
    CardFrame {
        Text(CrossPortalCopy.HEADING, fontWeight = FontWeight.SemiBold)
        ConsentRow(consent, CrossPortalCopy.consent(status.sharedFields)) { consent = it }
        val found = match
        if (found != null) {
            Text(CrossPortalCopy.found(CrossPortalPrefill.summary(found)), style = MaterialTheme.typography.bodyMedium)
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
                                .onFailure { note = it.apiRefusal(CrossPortalCopy.UNREACHABLE).message }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Looking…" else CrossPortalCopy.FIND) }
            }
        }
        Note(note)
    }
}

/**
 * A saved artisan: the link, Refresh and Unlink — or Link, for one saved before. [onRefreshed] is
 * handed the artisan's identity values as the server now holds them and which fields moved; the
 * form MUST take them, or its next Save writes the old ones back over the Field Repository's.
 */
@Composable
fun CrossPortalLinkCard(artisanId: String, onRefreshed: (CrossPortalFieldsDto, List<String>) -> Unit) {
    val (status, client) = rememberCrossPortal()
    val scope = rememberCoroutineScope()
    var link by remember(artisanId) { mutableStateOf<CrossPortalLinkDto?>(null) }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmUnlink by remember { mutableStateOf(false) }
    LaunchedEffect(status.enabled, artisanId) {
        if (status.enabled) link = runCatching { client.link(artisanId) }.getOrDefault(CrossPortalLinkDto())
    }
    val current = link
    if (!status.enabled || current == null) return

    fun run(action: suspend () -> Unit) {
        busy = true
        note = null
        scope.launch {
            runCatching { action() }.onFailure { note = it.apiRefusal(CrossPortalCopy.UNREACHABLE).message }
            busy = false
        }
    }

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text(CrossPortalCopy.UNLINK_TITLE) },
            text = { Text(CrossPortalCopy.UNLINK_BODY) },
            // Cancel first and the safe default: a reflex tap loses nothing.
            dismissButton = {
                TextButton(onClick = { confirmUnlink = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnlink = false
                        run { link = client.unlink(artisanId) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).destructiveAction(),
                ) { Text(CrossPortalCopy.UNLINK, color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    CardFrame {
        if (!current.linked) {
            Text("Field Repository", fontWeight = FontWeight.SemiBold)
            ConsentRow(consent, CrossPortalCopy.consent(status.sharedFields)) { consent = it }
            ActionRow {
                OutlinedButton(
                    enabled = consent && !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { run { link = client.createLink(artisanId, null) } },
                ) { Text(CrossPortalCopy.LINK) }
            }
        } else {
            Text(CrossPortalCopy.LINKED, style = MaterialTheme.typography.bodyMedium)
            ActionRow {
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        run {
                            val answer = client.refresh(artisanId)
                            link = answer.link
                            note = if (answer.changed.isNotEmpty()) CrossPortalCopy.UPDATED else CrossPortalCopy.UP_TO_DATE
                            val artisan = answer.artisan
                            if (answer.changed.isNotEmpty() && artisan != null) onRefreshed(artisan, answer.changed)
                        }
                    },
                ) { Text(CrossPortalCopy.REFRESH) }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).destructiveAction(),
                    onClick = { confirmUnlink = true },
                ) { Text(CrossPortalCopy.UNLINK, color = MaterialTheme.colorScheme.error) }
            }
        }
        Note(note)
    }
}
