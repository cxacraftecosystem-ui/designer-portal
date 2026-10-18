package com.designprototype.workshop.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.REGISTER_ARTISAN
import com.designprototype.workshop.data.ArtisanCardCode
import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.data.CARD_REMOTE
import com.designprototype.workshop.data.CARD_REPLACED
import com.designprototype.workshop.data.CardResolution
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.optionToArtisan
import kotlinx.coroutines.launch

/**
 * Scan or type an artisan QR card, and hand back who it names.
 *
 * One sheet for every place a card is read on the phone: the search header's QR button, the artisan
 * pickers on the record forms, and the stage's artisan reference field. The camera (ML Kit, on the
 * device), a picked picture and the typed short code all go through one [WorkshopRepository.resolveArtisanCard],
 * which matches the register this phone cached first, so a card resolves with no signal. A card that
 * matches nothing offline comes back [CardResolution.Pending]; a picker keeps it and the sync links it.
 *
 * The QR holds no personal data and no record id; a mistyped short code is refused before anything
 * is asked.
 */

/** The artisan register as this phone last cached it (`loadArtisanRegister`), card codes included. */
suspend fun cachedArtisanRegister(context: Context): List<ArtisanDto> =
    DwReferenceStore.load(context, DwReferenceStore.cacheKey(REGISTER_ARTISAN, "ALL", "", ""))
        ?.items?.mapNotNull(::optionToArtisan).orEmpty()

/** A scanned card's answer as a sentence for a refusal line, or null for the outcomes that act. */
fun cardResolutionMessage(resolution: CardResolution): String? = when (resolution) {
    is CardResolution.Found -> null
    is CardResolution.Replaced -> CARD_REPLACED
    CardResolution.Remote -> CARD_REMOTE
    is CardResolution.Pending -> ArtisanCardCode.pendingLabel(ArtisanCardCode.pendingValue(resolution.card))
    is CardResolution.Refused -> resolution.message
}

/** Resolve [input] against the cached register, then the server. */
suspend fun resolveScannedCard(context: Context, repository: WorkshopRepository, input: String): CardResolution =
    repository.resolveArtisanCard(input, cachedArtisanRegister(context))

/** The 48dp QR button that opens [ArtisanCardScanSheet]. */
@Composable
fun ArtisanCardScanButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan an artisan card")
    }
}

/**
 * @param acceptPending true where a picker can keep an offline scan as `card:<shortCode>`; the
 *   search header passes false, because there is nothing to open until the card resolves.
 * @param onResolved the artisan's id and name; the sheet closes.
 * @param onPending the offline card a picker keeps; the sheet closes.
 */
@Composable
fun ArtisanCardScanSheet(
    repository: WorkshopRepository,
    onDismiss: () -> Unit,
    onResolved: (artisanId: String, name: String) -> Unit,
    acceptPending: Boolean = false,
    onPending: (pendingValue: String) -> Unit = {},
    onOpenReplaced: ((artisanId: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var replacedId by remember { mutableStateOf<String?>(null) }

    fun lookUp(input: String) {
        if (input.isBlank() || busy) return
        busy = true
        message = null
        replacedId = null
        scope.launch {
            when (val answer = resolveScannedCard(context, repository, input)) {
                is CardResolution.Found -> onResolved(answer.artisanId, answer.name)
                is CardResolution.Pending ->
                    if (acceptPending) onPending(ArtisanCardCode.pendingValue(answer.card))
                    else message = "Offline. Try again once you're online."
                is CardResolution.Replaced -> {
                    message = cardResolutionMessage(answer)
                    replacedId = answer.artisanId.ifBlank { null }
                }
                else -> message = cardResolutionMessage(answer)
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
        title = { Text("Scan an artisan card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DwQrLiveScanControl(
                    enabled = !busy,
                    onText = { text ->
                        typed = text
                        lookUp(text)
                    },
                    onRefusal = { message = it },
                )
                DwQrScanControl(
                    enabled = !busy,
                    onText = { text ->
                        typed = text
                        lookUp(text)
                    },
                    onRefusal = { message = it },
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it
                        message = null
                    },
                    label = { Text("Card code") },
                    placeholder = { Text(ArtisanCardCode.PLACEHOLDER) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
                message?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                val replaced = replacedId
                if (replaced != null && onOpenReplaced != null) {
                    TextButton(onClick = { onOpenReplaced(replaced) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Open the artisan")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { lookUp(typed) }, enabled = typed.isNotBlank() && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (busy) "Looking…" else "Look up")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}
