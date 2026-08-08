package com.designprototype.workshop.ui.designworkshop

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.designprototype.workshop.data.ArtisanIdentity
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DwIdentityCandidateDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch
import java.io.File

/**
 * Attach a photograph of an identity card to an identity field, read the number off it, and make the
 * designer confirm it.
 *
 * ── THE ONE RULE THIS CONTROL EXISTS TO ENFORCE ───────────────────────────────────────────────
 *
 * IT NEVER WRITES THE NUMBER BY ITSELF. The recognised digits are shown, grouped as the card prints
 * them, and nothing reaches the field until a person taps a button that spells the number out.
 *
 * That is not caution for its own sake. An artisan card number is a DEDUPLICATION KEY — this
 * codebase has `GET /artisans/lookup/aadhaar` and a Verhoeff validator built around the assumption
 * that the number identifies a person. A read that turns 5 into 6 in one digit produces a number
 * that is well-formed, passes every format check, belongs to nobody, and quietly creates a second
 * record for an artisan who already exists. The duplicate is then counted twice in a ministry
 * return, and no error is raised anywhere; worse, the number will be masked to "XXXX XXXX 9012" on
 * every surface afterwards, so nobody ever reads it back and notices. A misread a human glances at
 * and corrects costs three seconds. A misread that auto-commits costs a corrupted data set.
 *
 * So: candidate, checked twice, confirmed by a person.
 *
 * ── WHERE THE RECOGNITION RUNS, AND WHY IT IS NOT ON THIS DEVICE ──────────────────────────────
 *
 * On the server. There is no on-device model and this file does not pretend otherwise — no bundled
 * recogniser, no queued "we will read it later" that a designer would take for done. That was a
 * measured decision, not an omission: `docs/DECISION-identity-card-ocr-on-android.md` has the
 * artifact sizes. The short version is that ML Kit's bundled Latin recogniser is 10.55 MB of native
 * code per ARM ABI on a 6.09 MB APK that the in-app updater re-downloads whole for every release,
 * and it buys the TYPING, not the CHECKING — the number still has to be read against the card
 * either way, and the same Verhoeff checksum catches a typo and a misread with equal power.
 *
 * With no connection the control disables itself and says why BEFORE a photograph is taken, because
 * the alternative is a designer photographing a card and then watching a spinner for a two-minute
 * HTTP timeout, which is indistinguishable from a hung app. The connectivity check is a best-effort
 * look at the active network, so it can be wrong in the optimistic direction — a captive portal, a
 * bar of signal that carries nothing — and the failure path below therefore says the same thing in
 * the past tense.
 *
 * ── TWO WAYS IN, AND THE SECOND ONE IS THE ONE THAT MATTERS OFFLINE ───────────────────────────
 *
 * "Photograph the card" and "Choose a photo". The picker is not a convenience: it is what makes the
 * offline case work at all. A designer in a courtyard with no signal photographs the card with the
 * phone's own camera, types the number by hand for now, and when they are back in signal they can
 * point this control at that photograph and check what they typed — without asking the artisan for
 * the card a second time, which for this class of document is a request that is not always granted
 * twice.
 *
 * The picker is `PickVisualMedia`, the system photo picker, and NOT `GetContent` with a media
 * permission. It hands back exactly the one image the designer chose and grants this app no standing
 * access to the gallery at all — the right posture for a control whose entire subject matter is
 * regulated personal data.
 *
 * ── WHERE THE PHOTOGRAPH GOES: NOWHERE ────────────────────────────────────────────────────────
 *
 * A photograph of somebody's Aadhaar card is the most sensitive object this application will ever
 * hold, and nobody asked to keep it — the field stores a NUMBER. So:
 *
 * - a photograph TAKEN here is written to a scratch directory of its own, sent, and deleted the
 *   moment the candidate is accepted or dismissed. It is never imported into the workshop's media,
 *   never uploaded as an attachment, and never lands in the directory `removeMedia` treats as
 *   documents;
 * - a photograph CHOSEN here is never copied at all. Its bytes are read straight from the content
 *   Uri into the request body. The app ends the flow with no copy of it;
 * - the scratch directory is SWEPT on every mount, because the delete above does not run if the
 *   process is killed between the shutter and the response — which on a 6 GB handset with a camera
 *   intent in the foreground is a real event, and would otherwise leave an Aadhaar card photograph
 *   on a shared field handset indefinitely.
 *
 * The server keeps nothing either: `scan_identity_card` has no storage path in it at all, and says
 * so. A card photograph is retained ONLY when a designer deliberately uploads one through the
 * ordinary media flow, which is a visible act with a record.
 *
 * ── MASKING ───────────────────────────────────────────────────────────────────────────────────
 *
 * The full number appears in exactly one place: the confirmation panel, where a person is being
 * asked to compare it with the card in their hand. Every other string this file can produce — an
 * error, a rejection, a count — carries [ArtisanIdentity.mask] or no digits at all. Nothing here
 * writes a number to a log at any level.
 */

/** Which kind of number the field being filled in holds, so the panel offers the right candidates. */
internal enum class DwIdentityKind {
    AADHAAR,
    PEHCHAN,

    /**
     * The field takes whichever card the artisan produced.
     *
     * The registry's `artisanCardNo` is this: the label says "card number" and a designer fills it
     * from an Aadhaar card, a Pehchan card or a state artisan card depending on what is in the
     * artisan's hand. Both candidate lists are offered, Aadhaar first.
     */
    ANY,
}

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
 * Which kind of number a registry field holds, read off its own name.
 *
 * Falls back to [DwIdentityKind.ANY] rather than guessing AADHAAR, and the direction of that
 * fallback is the safe one: ANY offers both candidate lists and lets the designer pick, whereas a
 * wrong guess of AADHAAR would hide the Pehchan number the card actually carried and leave the
 * designer believing nothing was read.
 */
internal fun identityKindFor(field: FieldDto): DwIdentityKind {
    val haystack = (field.key + " " + field.label).lowercase()
    return when {
        haystack.contains("aadhaar") || haystack.contains("aadhar") -> DwIdentityKind.AADHAAR
        haystack.contains("pehchan") -> DwIdentityKind.PEHCHAN
        else -> DwIdentityKind.ANY
    }
}

/**
 * A candidate the handset is willing to show a designer, after its OWN check.
 *
 * The server has already applied the Verhoeff filter (`identity_ocr.aadhaar_candidates`), so in a
 * healthy system this second pass rejects nothing. It exists for the unhealthy one: if it ever
 * rejects something, the two ports of the same rule have drifted, and the moment to find that out is
 * before the number is offered rather than after it is stored. Refusing rather than warning is
 * deliberate — the server's contract is that only checksum-valid numbers are sent, so a candidate
 * that fails here is a transport or shape problem, not a card a designer can do anything about.
 */
internal data class DwIdentityChoice(val value: String, val kind: DwIdentityKind, val confidence: Double)

/**
 * The candidates worth offering for [kind], best first, each re-checked on this device.
 *
 * Pure and internal so a JVM test can hold it to the contract without a device — see
 * `DwIdentityOcrWireTest`.
 */
internal fun identityChoices(
    aadhaar: List<DwIdentityCandidateDto>,
    pehchan: List<DwIdentityCandidateDto>,
    kind: DwIdentityKind,
): List<DwIdentityChoice> {
    val out = mutableListOf<DwIdentityChoice>()
    if (kind == DwIdentityKind.AADHAAR || kind == DwIdentityKind.ANY) {
        aadhaar.forEach { candidate ->
            val digits = ArtisanIdentity.normalizeAadhaar(candidate.value)
            if (ArtisanIdentity.isAadhaar(digits) && out.none { it.value == digits }) {
                out += DwIdentityChoice(digits, DwIdentityKind.AADHAAR, candidate.confidence)
            }
        }
    }
    if (kind == DwIdentityKind.PEHCHAN || kind == DwIdentityKind.ANY) {
        pehchan.forEach { candidate ->
            // Normalised to the ONE spelling the server stores, so a read and a typed entry of the
            // same card cannot become two different strings — there is no checksum here to catch it.
            val cleaned = ArtisanIdentity.normalizePehchan(candidate.value)
            if (ArtisanIdentity.isPehchan(cleaned) && out.none { it.value == cleaned }) {
                out += DwIdentityChoice(cleaned, DwIdentityKind.PEHCHAN, candidate.confidence)
            }
        }
    }
    return out
}

/**
 * The scratch directory a photograph TAKEN by this control lives in for the seconds it exists.
 *
 * Its own directory under `filesDir`, deliberately not the workshop's `captures/` folder: that one
 * holds documents on their way to becoming attachments, and this holds one file that is promised to
 * be deleted. Mixing the two would put a sweep that deletes everything in the same directory as
 * photographs a designer is waiting to have uploaded.
 *
 * Not `cacheDir` either — Android reclaims that under storage pressure without warning, so a camera
 * intent writing there can have its output deleted between the shutter and the read.
 */
private fun identityScratchDir(context: Context): File =
    File(context.filesDir, "identity-scratch").apply { mkdirs() }

/**
 * Delete everything left in the scratch directory.
 *
 * Called on mount and on dispose. The per-flow delete below covers the ordinary paths; this covers
 * the one it cannot — the process being killed between the shutter and the response, which leaves a
 * photograph of a national identity document on a shared handset with nothing scheduled to remove
 * it.
 */
private fun sweepIdentityScratch(context: Context) {
    runCatching { identityScratchDir(context).listFiles()?.forEach { it.delete() } }
}

/**
 * The control, drawn under the number box it fills.
 *
 * [onUse] is called with the confirmed value and only ever from a tap. There is intentionally no code
 * path from a successful response to [onUse] that does not pass through a human.
 *
 * Both dependencies are NON-NULLABLE and the caller does the gating. An earlier shape took them as
 * nullable and returned early, which put the launchers below a conditional return — a composable
 * whose remembered slots appear and disappear between frames, and therefore a crash waiting for the
 * first field where the condition ever changes. The gate belongs at the call site.
 *
 * ── THE CALLER MUST ALSO GATE ON `FieldPermissions.canRunDesignWorkshops` ─────────────────────
 *
 * `POST /design-workshops/ocr/identity` starts with `_require_designer` — the SET
 * {DESIGNER, ADMIN, MASTER_ADMIN}, not a rank threshold. The stage form gets that for free (the
 * whole design-workshop destination is behind the same predicate in `AppNavigation`), but the
 * ARTISAN FORM does not: it is reached through `canCreateRecords`, RESEARCHER and above, so a
 * researcher — and a PROFESSOR, who outranks a designer and is still outside the set — can stand in
 * front of this control. `MainActivity.ArtisanForm` therefore computes `canReadIdentityCards` and
 * wraps both call sites in it.
 *
 * It is not a cosmetic guard. The refusal arrives as a 403 AFTER the request, so an ungated button
 * means a photograph of somebody's Aadhaar card is taken and uploaded to a third-party vision model
 * before anything says no. Hiding the control is the only point at which that is preventable here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DwIdentityCardControl(
    targetLabel: String,
    kind: DwIdentityKind,
    repository: WorkshopRepository,
    enabled: Boolean,
    onUse: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<DwIdentityChoice>>(emptyList()) }
    var unconfirmedByServer by remember { mutableStateOf(false) }

    // Mount and unmount both sweep. See sweepIdentityScratch for the failure this closes.
    DisposableEffect(Unit) {
        sweepIdentityScratch(context)
        onDispose { sweepIdentityScratch(context) }
    }

    /** Delete the photograph this control took. Called on every exit from the flow. */
    fun discardPhoto() {
        pending?.let { file -> runCatching { file.delete() } }
        pending = null
    }

    fun send(source: Uri) {
        working = true
        choices = emptyList()
        scope.launch {
            runCatching { repository.designWorkshopIdentityOcr(context, source) }
                .onSuccess { result ->
                    working = false
                    val offered = identityChoices(result.aadhaarCandidates, result.pehchanCandidates, kind)
                    if (offered.isEmpty()) {
                        discardPhoto()
                        // The rejected COUNT, never the rejected values — a misread is still
                        // somebody's identity number. The two sentences are different next actions:
                        // "found and misread" means better light, "not found" means fill the frame.
                        onError(
                            if (result.rejectedAadhaarCount > 0) {
                                "${result.rejectedAadhaarCount} number(s) were read off that card and " +
                                    "every one failed its checksum, so at least one digit was wrong in " +
                                    "each. Take another photograph in better light with no glare across " +
                                    "the digits, or type the number in."
                            } else {
                                "No number could be read from that photograph. Fill the frame with the " +
                                    "card, hold it flat and try again — or type the number in."
                            }
                        )
                    } else {
                        // The photograph is kept only until the candidate is resolved, so the
                        // designer can look from one to the other while checking the digits.
                        choices = offered
                        // `requiresConfirmation` is the server stating the contract in the payload.
                        // This client confirms regardless of what it says; a server that said
                        // otherwise would be a contract change worth SEEING rather than obeying
                        // silently, so it is surfaced in the panel instead of being ignored.
                        unconfirmedByServer = !result.requiresConfirmation
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
        val file = pending ?: return@rememberLauncherForActivityResult
        if (ok) send(dwCaptureUri(context, file)) else discardPhoto()
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pending
        if (!granted || file == null) {
            discardPhoto()
            if (!granted) {
                onError(
                    "The camera permission was refused, so the card cannot be photographed. You can " +
                        "still choose a photograph already on the phone, or type the number in."
                )
            }
            return@rememberLauncherForActivityResult
        }
        takePhoto.launch(dwCaptureUri(context, file))
    }

    // The system photo picker: one image, chosen by the designer, and NO standing gallery permission
    // for an app whose subject here is regulated personal data. Its bytes are read straight into the
    // request; nothing is copied into this app's storage.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) send(uri) }

    /**
     * Checked HERE, at the tap, rather than only at composition: a stage screen stays on a phone for
     * hours and the connection it had when it opened is not the connection it has now.
     */
    fun refuseOffline(): Boolean {
        if (ConnectivityObserver.isOnline(context)) return false
        onError(
            "Reading a card needs a connection and there is none. Type the number in now — and if you " +
                "photograph the card with the phone's camera, you can come back here in signal and " +
                "check what you typed against it."
        )
        return true
    }

    fun startCamera() {
        if (refuseOffline()) return
        val file = File(identityScratchDir(context), "card-${System.currentTimeMillis()}.jpg")
        pending = file
        if (hasPermission(context, Manifest.permission.CAMERA)) {
            takePhoto.launch(dwCaptureUri(context, file))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun startPick() {
        if (refuseOffline()) return
        discardPhoto()
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val online = ConnectivityObserver.isOnline(context)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::startCamera, enabled = enabled && !working && online) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Reading the card…" else "Photograph the card", fontSize = 13.sp)
            }
            OutlinedButton(onClick = ::startPick, enabled = enabled && !working && online) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Choose a photo", fontSize = 13.sp)
            }
        }
        Text(
            if (online) {
                "Photograph the card, or pick a photograph of it already on this phone, and the " +
                    "number is read off it. You confirm it before anything is filled in. The " +
                    "photograph is not kept."
            } else {
                // The disabled reason, in the disabled state, where the disabled control is. A greyed
                // button with the explanation elsewhere is a button people tap repeatedly.
                "Reading a card needs a connection. With none, type the number in — photograph the " +
                    "card with the camera app and you can check it here later."
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
    }

    if (choices.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                if (choices.size == 1) "Check this against the card before using it"
                else "Check these against the card — ${choices.size} numbers were read",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Nothing is saved into $targetLabel until you tap the number below. A single wrong " +
                    "digit creates a second record for an artisan who is already in the system, and " +
                    "nothing downstream can detect it — so read it off the card itself, not off this " +
                    "screen.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
            if (unconfirmedByServer) {
                Text(
                    "The server marked this reading as not needing confirmation. This app confirms " +
                        "it anyway — an identity number is never written without a person checking it.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }
            choices.forEach { choice ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                onUse(choice.value)
                                choices = emptyList()
                                discardPhoto()
                            },
                            enabled = enabled
                        ) {
                            // The button SAYS the number it will write, grouped 4-4-4 as the card
                            // prints it. "Accept" would be a button whose effect the designer has to
                            // remember; this one is the sentence they are agreeing to.
                            Text("Use ${displayOf(choice)}", fontSize = 13.sp)
                        }
                    }
                    Text(
                        listOfNotNull(
                            if (choice.kind == DwIdentityKind.AADHAAR) "Aadhaar" else "Pehchan card",
                            // Words, not a percentage. "82%" invites a designer to treat 82 as good
                            // enough and skip the check, which is the one behaviour this panel exists
                            // to prevent.
                            if (choice.confidence >= 0.85) "read clearly" else "read with difficulty",
                        ).joinToString(" · "),
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
            }
            TextButton(onClick = {
                choices = emptyList()
                discardPhoto()
            }) { Text("None of these — discard", fontSize = 13.sp) }
        }
    }
}

/**
 * How a candidate is written on the button that would commit it.
 *
 * Aadhaar is grouped 4-4-4 because that is how it is printed and how a person proofreads it group by
 * group. A Pehchan number has no such grouping and is shown as stored — upper-cased and stripped —
 * which is also exactly what will be saved, so what the button says and what the field gets cannot
 * differ.
 */
private fun displayOf(choice: DwIdentityChoice): String =
    if (choice.kind == DwIdentityKind.AADHAAR) ArtisanIdentity.grouped(choice.value) else choice.value
