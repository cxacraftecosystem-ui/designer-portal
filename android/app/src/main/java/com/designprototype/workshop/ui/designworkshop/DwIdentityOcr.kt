package com.designprototype.workshop.ui.designworkshop

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DwIdentityOcrDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch
import java.io.File

/**
 * Photograph an identity card, read the number off it, and make the designer confirm it.
 *
 * ── THE ONE RULE THIS CONTROL EXISTS TO ENFORCE ───────────────────────────────────────────────
 *
 * IT NEVER WRITES THE NUMBER BY ITSELF. The recognised digits are shown, side by side with the
 * photograph they came from, and nothing reaches the field until a person taps "Use this number".
 *
 * That is not caution for its own sake. An artisan card number is a DEDUPLICATION KEY — this
 * codebase already has `GET /artisans/lookup/aadhaar` and a checksum validator built around the
 * assumption that the number identifies a person. An OCR that reads 5 as 6 in one digit produces a
 * number that is well-formed, passes every format check, belongs to nobody, and quietly creates a
 * second record for an artisan who already exists. The duplicate is then paid twice, or counted
 * twice in a ministry return, and no error was ever raised anywhere. A misread that a human glances
 * at and corrects costs three seconds; a misread that auto-commits costs a corrected dataset.
 *
 * So: candidate, photograph, confirm. And the confirm button says what will be written, in full,
 * rather than "Accept".
 *
 * ── OFFLINE ───────────────────────────────────────────────────────────────────────────────────
 *
 * The recognition runs on the server. There is no on-device fallback and this file does not pretend
 * otherwise — no bundled model, no queued "we will read it later" that a designer would take for
 * done. With no connection the control disables itself and says why, BEFORE the photograph is taken,
 * because the alternative is a designer photographing a card and then watching a spinner sit there
 * for a two-minute HTTP timeout, which is indistinguishable from a hung app.
 *
 * The connectivity check is a best-effort look at the active network, so it can be wrong in the
 * optimistic direction — a captive portal, or a bar of signal that carries nothing. The failure path
 * below therefore has to be just as clear as the disabled state, and says the same thing in the past
 * tense.
 *
 * ── WHERE THE PHOTOGRAPH GOES ─────────────────────────────────────────────────────────────────
 *
 * Into the workshop's own directory under `filesDir`, like every other capture in this feature —
 * never cacheDir. It is deleted as soon as the designer accepts or dismisses the candidate: a
 * photograph of somebody's Aadhaar card is the most sensitive object this app will ever hold, it is
 * not an attachment anybody asked to keep, and leaving it on a shared field handset for the sake of
 * a retry that will not happen is indefensible. A retry re-photographs.
 */

/**
 * Whether this field is an identity number worth offering the camera on.
 *
 * Deliberately a narrow, WRITTEN-DOWN list rather than a clever heuristic. A loose match would put
 * an "Aadhaar" button under "Number of looms", and a control that offers to photograph an identity
 * card next to an unrelated field invites a designer to photograph one that nobody needed — which,
 * for this class of data, is a privacy incident caused by a regular expression.
 *
 * `artisanCardNo` is the registry's one identity-number field today (stage 3, the participant
 * roster). The other tokens are matched so that a field added on the server starts working with no
 * client change, which is this feature's whole premise.
 */
internal fun isIdentityNumberField(field: FieldDto): Boolean {
    val haystack = (field.key + " " + field.label).lowercase()
    return haystack.contains("aadhaar") ||
        haystack.contains("aadhar") ||
        haystack.contains("pehchan") ||
        haystack.contains("cardno") ||
        haystack.contains("card number") ||
        haystack.contains("identity number") ||
        haystack.contains("id number")
}

/**
 * The control, drawn under the number box it fills.
 *
 * [onUse] is called with the confirmed digits and only ever from a tap. There is intentionally no
 * code path from a successful response to [onUse] that does not pass through a human.
 */
// Both dependencies are NON-NULLABLE, and the caller does the gating. An earlier shape took them as
// nullable and returned early, which put the four `rememberLauncherForActivityResult` calls below a
// conditional return — a composable whose remembered slots appear and disappear between frames, and
// therefore a crash waiting for the first field where the condition ever changes. The gate belongs
// at the call site (`canReadCard` in FieldRenderer), where it is one boolean rather than a shape.
@Composable
internal fun DwIdentityOcrControl(
    field: FieldDto,
    repository: WorkshopRepository,
    newCaptureFile: (String) -> File,
    enabled: Boolean,
    onUse: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<DwIdentityOcrDto?>(null) }

    /** Delete the photograph. Called on every exit from the flow, successful or not. */
    fun discardPhoto() {
        pending?.let { file -> runCatching { file.delete() } }
        pending = null
    }

    fun send(file: File) {
        working = true
        scope.launch {
            runCatching { repository.designWorkshopIdentityOcr(context, dwCaptureUri(context, file)) }
                .onSuccess { result ->
                    working = false
                    if (result.number.isBlank()) {
                        discardPhoto()
                        onError(
                            result.message.takeIf { it.isNotBlank() }
                                ?: "No number could be read from that photograph. Fill the frame with " +
                                "the card, hold it flat and try again — or type the number in."
                        )
                    } else {
                        // The photograph is kept only until the candidate is resolved, so the
                        // designer can look from one to the other while checking the digits.
                        candidate = result
                    }
                }
                .onFailure { error ->
                    working = false
                    discardPhoto()
                    onError(
                        if (!ConnectivityObserver.isOnline(context)) {
                            "The connection dropped before the card could be read. Nothing was sent. " +
                                "Type the number in, or try again where there is signal."
                        } else {
                            error.apiErrorMessage("That card could not be read. Type the number in instead.")
                        }
                    )
                }
        }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending
        if (file == null) return@rememberLauncherForActivityResult
        if (ok) send(file) else discardPhoto()
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pending
        if (!granted || file == null) {
            discardPhoto()
            if (!granted) onError("The camera permission was refused, so the card cannot be photographed.")
            return@rememberLauncherForActivityResult
        }
        takePhoto.launch(dwCaptureUri(context, file))
    }

    fun start() {
        // Checked HERE, at the tap, rather than only at composition: a stage screen stays on a phone
        // for hours and the connection it had when it opened is not the connection it has now.
        if (!ConnectivityObserver.isOnline(context)) {
            onError(
                "Reading a card needs a connection and there is none. The number can be typed in now " +
                    "and the card photographed later; nothing is queued, because a queued " +
                    "identity photograph is one nobody remembers is on the phone."
            )
            return
        }
        val file = newCaptureFile(".jpg")
        pending = file
        if (hasPermission(context, Manifest.permission.CAMERA)) {
            takePhoto.launch(dwCaptureUri(context, file))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val online = ConnectivityObserver.isOnline(context)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = ::start, enabled = enabled && !working && online) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Reading the card…" else "Read from card", fontSize = 13.sp)
            }
        }
        Text(
            if (online) {
                "Photograph the ${field.label.lowercase()} card and the number is read off it. " +
                    "You confirm it before anything is filled in."
            } else {
                // The disabled reason, in the disabled state, where the disabled control is. A greyed
                // button with the explanation elsewhere is a button people tap repeatedly.
                "Reading a card needs a connection. With none, type the number in."
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
    }

    candidate?.let { result ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Check this against the card before using it",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                // Spaced in groups of four. An unbroken twelve-digit run is the single hardest thing
                // to proofread, and proofreading it is the entire purpose of this panel.
                grouped(result.number),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            val provenance = listOfNotNull(
                result.documentType.takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.uppercase() },
                result.name.takeIf { it.isNotBlank() }?.let { "printed name: $it" },
                // Words, not a percentage. "0.82" invites a designer to treat 0.82 as good enough and
                // skip the check, which is the one behaviour this panel exists to prevent.
                result.confidence?.let { if (it >= 0.85) "read clearly" else "read with difficulty" },
            )
            if (provenance.isNotEmpty()) {
                Text(provenance.joinToString(" · "), color = MaterialTheme.field.muted, fontSize = 11.sp)
            }
            Text(
                "A single wrong digit creates a second record for an artisan who is already in the " +
                    "system. Read it off the card itself, not off this screen.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onUse(result.number.filter { !it.isWhitespace() })
                        candidate = null
                        discardPhoto()
                    },
                    enabled = enabled
                ) { Text("Use ${grouped(result.number)}", fontSize = 13.sp) }
                TextButton(onClick = {
                    candidate = null
                    discardPhoto()
                }) { Text("Discard", fontSize = 13.sp) }
            }
        }
    }
}

/** Digits in groups of four, the way an Aadhaar number is printed on the card being compared. */
private fun grouped(raw: String): String {
    val digits = raw.filter { !it.isWhitespace() }
    if (digits.length !in 8..20) return digits
    return digits.chunked(4).joinToString(" ")
}
