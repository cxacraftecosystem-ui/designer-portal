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
import com.designprototype.workshop.data.IdentityCardRecognizer
import com.designprototype.workshop.data.IdentityCardText
import com.designprototype.workshop.data.MlKitIdentityCardRecognizer
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
 * ── WHERE THE RECOGNITION RUNS: THIS PHONE FIRST, THE SERVER ONLY IF IT HAS TO ────────────────
 *
 * On the device, using the BUNDLED ML Kit Latin recogniser — no model download, no Play Services
 * dependency at read time, no connection. That is the whole point: this application is carried into
 * villages that have had no signal for days, and a reader that only works online is a reader that
 * does not work where the work happens. It costs real bytes and they are measured, not estimated, in
 * `docs/DECISION-identity-card-ocr-on-android.md`, which also records that an earlier lane measured
 * the same artifacts and recommended AGAINST them, and that the recommendation was overruled.
 *
 * THE ORDER IS ON-DEVICE, THEN SERVER, AND THE DESIGNER IS TOLD WHICH ANSWERED. The phone is asked
 * first because it always works. If the phone finds nothing AND there is a connection, the server's
 * vision model is asked as well — it reads a creased, glared, badly-lit card better than a bundled
 * 10 MB model does, and giving that up would be trading one failure for another. The panel then says
 * "read on this phone" or "read by the server" beside every candidate, because those two readers do
 * NOT have the same accuracy and a person being asked to proofread twelve digits is entitled to know
 * which one produced them.
 *
 * The device also refuses things the server would offer. Only the phone applies the maximal-run rule
 * in [IdentityCardText] that stops a sixteen-digit VID being clipped into a twelve-digit "Aadhaar
 * number" — so on-device-first is not merely faster, it is the stricter of the two readers.
 *
 * ── WHAT THE PHONE CANNOT DO ──────────────────────────────────────────────────────────────────
 *
 * Read a Pehchan number. An Aadhaar number proves itself — twelve digits, never starting 0 or 1, a
 * Verhoeff check digit — and nothing else on a card passes that by accident. A Pehchan card number
 * has no checksum and no fixed length, so picking it out of raw recognised text would mean guessing,
 * with nothing downstream able to catch the guess being wrong. The server does not guess either: it
 * asks a vision model to LABEL the Pehchan number and only normalises the answer. So a Pehchan-only
 * field still needs a connection, and says so in the disabled state rather than after a timeout.
 * [IdentityCardText]'s header has the full argument and the condition for revisiting it.
 *
 * ── TWO WAYS IN, AND THE PICKER IS STILL WORTH ITS BUTTON ─────────────────────────────────────
 *
 * "Photograph the card" and "Choose a photo". The picker used to be the ONLY thing that made the
 * offline case survivable — photograph the card with the camera app now, come back and read it in
 * signal later — and that is no longer its job, because the camera path itself now works with no
 * connection. It stays for the case that has not gone away: a card already photographed, by this
 * designer or handed over by someone else, that would otherwise mean asking the artisan to produce
 * a national identity document a second time. For this class of paper that is a request which is
 * not always granted twice.
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
 * Which reader produced a candidate.
 *
 * Carried all the way to the button, and printed there. The two readers are not equally accurate and
 * they do not fail in the same way — the bundled model on the phone is small and literal, the vision
 * model on the server is large and willing to interpret — so "which one said this" is information the
 * person proofreading the digits needs, not provenance trivia for a log.
 */
internal enum class DwIdentitySource {
    /** The bundled ML Kit recogniser, on this handset, with or without a connection. */
    ON_DEVICE,

    /** `POST /design-workshops/ocr/identity`, asked only when the phone found nothing. */
    SERVER,
}

/**
 * A candidate the handset is willing to show a designer, after its OWN check.
 *
 * [confidence] is null for an on-device read and that is deliberate. ML Kit does not return a
 * calibrated per-number confidence for this, and inventing one — "0.9 because it passed the checksum"
 * — would put a reassuring number next to digits whose entire purpose on that screen is to be
 * doubted. A missing confidence prints nothing; it does not print a guess.
 */
internal data class DwIdentityChoice(
    val value: String,
    val kind: DwIdentityKind,
    val confidence: Double?,
    val source: DwIdentitySource,
)

/** One unchecked number a reader proposed, before this file decides whether it may be offered. */
internal data class DwIdentityProposal(
    val value: String,
    val kind: DwIdentityKind,
    val confidence: Double?,
)

/**
 * THE ONE FILTER BOTH READERS PASS THROUGH, and the only route to the confirm panel.
 *
 * Two readers now propose numbers and there is exactly one place that decides what a designer may be
 * shown, on purpose. A second confirm path for the on-device reader is how the two drift: one of them
 * keeps the Verhoeff refusal and the masking rule, the other quietly stops, and nobody notices
 * because both screens look right. Everything below — normalisation, the checksum, the deduplication,
 * the kind gate — applies identically to a number read on the phone and one read on the server.
 *
 * A candidate failing the check is REFUSED, never offered with a warning. For a server candidate that
 * means the two ports of the rule have drifted, which is a transport problem and not a card a
 * designer can do anything about. For a device candidate it should be unreachable — [IdentityCardText]
 * has already applied the same rule — and if it ever fires, the same conclusion holds.
 *
 * Pure and internal so a JVM test can hold it to the contract without a device: see
 * `DwIdentityOcrWireTest` and `IdentityCardTextTest`.
 */
internal fun identityChoices(
    proposals: List<DwIdentityProposal>,
    kind: DwIdentityKind,
    source: DwIdentitySource,
): List<DwIdentityChoice> {
    val out = mutableListOf<DwIdentityChoice>()
    proposals.forEach { proposal ->
        val wanted = kind == DwIdentityKind.ANY || kind == proposal.kind
        if (!wanted) return@forEach
        val cleaned = when (proposal.kind) {
            DwIdentityKind.AADHAAR -> ArtisanIdentity.normalizeAadhaar(proposal.value)
                .takeIf { ArtisanIdentity.isAadhaar(it) }
            // Normalised to the ONE spelling the server stores, so a read and a typed entry of the
            // same card cannot become two different strings — there is no checksum here to catch it.
            DwIdentityKind.PEHCHAN -> ArtisanIdentity.normalizePehchan(proposal.value)
                .takeIf { ArtisanIdentity.isPehchan(it) }
            // A proposal cannot itself be "either kind"; ANY is a question a FIELD asks, not an answer
            // a reader gives. Dropping it is the safe direction — an unlabelled number has no
            // validator to apply, and applying the wrong one would offer a name as a card number.
            DwIdentityKind.ANY -> null
        } ?: return@forEach
        if (out.none { it.value == cleaned }) {
            out += DwIdentityChoice(cleaned, proposal.kind, proposal.confidence, source)
        }
    }
    return out
}

/**
 * The server's answer, converted to candidates.
 *
 * Aadhaar before Pehchan, which is the order a card-number field offers them in: an artisan who has
 * both cards is deduplicated on the Aadhaar number, so it is the one the registry actually wants.
 */
internal fun identityChoices(
    aadhaar: List<DwIdentityCandidateDto>,
    pehchan: List<DwIdentityCandidateDto>,
    kind: DwIdentityKind,
): List<DwIdentityChoice> = identityChoices(
    proposals = aadhaar.map { DwIdentityProposal(it.value, DwIdentityKind.AADHAAR, it.confidence) } +
        pehchan.map { DwIdentityProposal(it.value, DwIdentityKind.PEHCHAN, it.confidence) },
    kind = kind,
    source = DwIdentitySource.SERVER,
)

/**
 * This phone's own answer, converted to candidates.
 *
 * Aadhaar only — see [IdentityCardText] for why the device does not attempt a Pehchan number. For a
 * PEHCHAN field this therefore returns nothing and the flow falls through to the server, which is the
 * honest outcome rather than a silent one: the control says so before a photograph is taken.
 */
internal fun identityChoices(
    reading: IdentityCardText.IdentityCardReading,
    kind: DwIdentityKind,
): List<DwIdentityChoice> = identityChoices(
    proposals = reading.aadhaar.map {
        DwIdentityProposal(it, DwIdentityKind.AADHAAR, confidence = null)
    },
    kind = kind,
    source = DwIdentitySource.ON_DEVICE,
)

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
    // A DEFAULT rather than an injected dependency, because there is exactly one recogniser and the
    // parameter exists so that a future instrumented test can substitute a canned scan. The default
    // is a direct object reference and not a factory lookup: R8 shrinks the release APK, and the
    // seventeen megabytes of ML Kit that this application now carries have to be provably REACHED
    // from a control a designer can press, not merely present in a dependency block.
    recognizer: IdentityCardRecognizer = MlKitIdentityCardRecognizer,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<DwIdentityChoice>>(emptyList()) }
    var unconfirmedByServer by remember { mutableStateOf(false) }

    /**
     * Whether this phone can read this field's card at all without a connection.
     *
     * Aadhaar only. A PEHCHAN field has no on-device path, so for it the control is exactly as
     * connection-bound as it was before this lane, and must go on saying so.
     */
    val readableOnDevice = kind != DwIdentityKind.PEHCHAN

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

    /**
     * Read the photograph: THIS PHONE FIRST, then the server only if the phone found nothing.
     *
     * The precedence is not an optimisation, it is the feature — the phone answers in a courtyard
     * with no signal, which is where the cards are. The server is asked afterwards because the two
     * readers fail differently: a bundled 10 MB model gives up on a creased, glared or badly-lit card
     * that a large vision model still reads, and dropping that fallback would trade one class of
     * failure for another rather than removing it.
     *
     * The result carries WHICH reader answered all the way to the button. Nothing here auto-commits
     * on either path; both land in the same [choices] list, drawn by the same panel, filtered by the
     * same [identityChoices].
     */
    fun send(source: Uri) {
        working = true
        choices = emptyList()
        unconfirmedByServer = false
        scope.launch {
            // ── 1. The phone ─────────────────────────────────────────────────────────────────────
            // A THROW here is not "no number on the card"; it is "the local reader is unavailable"
            // (an unreadable file, a model that would not load). Those need different next steps, so
            // failure falls through to the server while an empty-but-successful read is a real answer
            // that still falls through — the server may read a card this model could not.
            val localReading = runCatching { IdentityCardText.read(recognizer.scan(context, source)) }
            val local = localReading.getOrNull()?.let { identityChoices(it, kind) }.orEmpty()
            if (local.isNotEmpty()) {
                working = false
                // The photograph is kept only until the candidate is resolved, so the designer can
                // look from one to the other while checking the digits.
                choices = local
                return@launch
            }

            // ── 2. The server, only with a connection ────────────────────────────────────────────
            val localRejected = localReading.getOrNull()?.rejectedAadhaarCount ?: 0
            if (!ConnectivityObserver.isOnline(context)) {
                working = false
                discardPhoto()
                onError(nothingFoundOffline(kind, readableOnDevice, localRejected))
                return@launch
            }

            runCatching { repository.designWorkshopIdentityOcr(context, source) }
                .onSuccess { result ->
                    working = false
                    val offered = identityChoices(result.aadhaarCandidates, result.pehchanCandidates, kind)
                    if (offered.isEmpty()) {
                        discardPhoto()
                        // The rejected COUNT, never the rejected values — a misread is still
                        // somebody's identity number. The two sentences are different next actions:
                        // "found and misread" means better light, "not found" means fill the frame.
                        // Both readers' counts are added: a designer does not care which of the two
                        // rejected a misreading, only that the card WAS found and misread.
                        onError(
                            (localRejected + result.rejectedAadhaarCount).let { rejected ->
                                if (rejected > 0) {
                                    "$rejected number(s) were read off that card and every one failed " +
                                        "its checksum, so at least one digit was wrong in each. Take " +
                                        "another photograph in better light with no glare across the " +
                                        "digits, or type the number in."
                                } else {
                                    "No number could be read from that photograph, by this phone or by " +
                                        "the server. Fill the frame with the card, hold it flat and try " +
                                        "again — or type the number in."
                                }
                            }
                        )
                    } else {
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
                            // The phone had already tried and found nothing, so this is not "we lost
                            // the only reader" — say what actually happened rather than implying the
                            // card is unreadable.
                            "This phone could not read that card, and the connection dropped before " +
                                "the server could be asked. Type the number in, or try again where " +
                                "there is signal."
                        } else {
                            error.apiErrorMessage(
                                "This phone could not read that card and the server could not either. " +
                                    "Type the number in instead."
                            )
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
     * Refuse only what this phone genuinely cannot do offline, which is now just a Pehchan card.
     *
     * Checked HERE, at the tap, rather than only at composition: a stage screen stays on a phone for
     * hours and the connection it had when it opened is not the connection it has now. The Aadhaar
     * path no longer consults this at all — that is the point of the lane.
     */
    fun refuseOffline(): Boolean {
        if (readableOnDevice || ConnectivityObserver.isOnline(context)) return false
        onError(
            "A Pehchan card number can only be read with a connection: this phone reads Aadhaar " +
                "numbers, which prove themselves with a checksum, and a Pehchan number has none — so " +
                "guessing at one would put a number nothing downstream could catch in front of you. " +
                "Type it in now, or photograph the card and come back here in signal."
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
    // Usable with no signal for every field except a Pehchan-only one. This single boolean is what
    // the lane changed: the buttons used to be dead offline, which is the state a designer in a
    // courtyard is always in.
    val usable = enabled && !working && (readableOnDevice || online)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::startCamera, enabled = usable) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Reading the card…" else "Photograph the card", fontSize = 13.sp)
            }
            OutlinedButton(onClick = ::startPick, enabled = usable) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Choose a photo", fontSize = 13.sp)
            }
        }
        Text(
            when {
                // The disabled reason, in the disabled state, where the disabled control is. A greyed
                // button with the explanation elsewhere is a button people tap repeatedly.
                !readableOnDevice && !online ->
                    "Reading a Pehchan card needs a connection — this phone reads Aadhaar numbers " +
                        "only. Type the number in; photograph the card with the camera app and you " +
                        "can check it here later."
                !readableOnDevice ->
                    "Photograph the card, or pick a photograph of it already on this phone, and the " +
                        "number is read off it on the server. You confirm it before anything is " +
                        "filled in. The photograph is not kept."
                online ->
                    "Photograph the card, or pick a photograph of it already on this phone. The " +
                        "number is read on this phone; if it cannot be read here, the server is asked " +
                        "as well. You confirm it before anything is filled in, and you are told which " +
                        "one read it. The photograph is not kept."
                else ->
                    "This works with no connection — the number is read on this phone. You confirm it " +
                        "before anything is filled in. The photograph is not kept."
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
                            // WHICH READER, next to the number, every time. The two do not have the
                            // same accuracy and they do not fail the same way, and a designer being
                            // asked to proofread twelve digits is entitled to know whether a bundled
                            // model on a mid-range handset or a large vision model produced them.
                            when (choice.source) {
                                DwIdentitySource.ON_DEVICE -> "read on this phone"
                                DwIdentitySource.SERVER -> "read by the server"
                            },
                            // Words, not a percentage. "82%" invites a designer to treat 82 as good
                            // enough and skip the check, which is the one behaviour this panel exists
                            // to prevent. Absent entirely for an on-device read: see DwIdentityChoice.
                            choice.confidence?.let { if (it >= 0.85) "read clearly" else "read with difficulty" },
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
 * What to tell a designer when the phone read the photograph, found nothing usable, and there is no
 * connection to ask the server with.
 *
 * Three different sentences because they are three different next actions, and a single "could not
 * read the card" would send a designer photographing a card again for a problem that is not about the
 * photograph. Pure and internal so the wording is pinned by a JVM test rather than by a screenshot.
 *
 * NO DIGITS IN ANY OF THEM. [rejected] is a count; the numbers behind it were misreadings of
 * somebody's identity number and they do not leave this device, not into a message and not into a log.
 */
internal fun nothingFoundOffline(
    kind: DwIdentityKind,
    readableOnDevice: Boolean,
    rejected: Int,
): String = when {
    // Not something a photograph can fix: this phone has no Pehchan reader at all.
    !readableOnDevice ->
        "A Pehchan card number can only be read with a connection, and there is none. Type it in now."
    // The card WAS found and misread — better light, no glare. Distinct from "not found".
    rejected > 0 ->
        "$rejected number(s) were read off that card and every one failed its checksum, so at least " +
            "one digit was wrong in each. There is no connection to check them against the server, " +
            "so: another photograph in better light with no glare across the digits, or type the " +
            "number in."
    // Nothing found at all — fill the frame. Says the server was NOT asked, so a designer who moves
    // into signal knows there is something left to try rather than concluding the card is unreadable.
    kind == DwIdentityKind.PEHCHAN || kind == DwIdentityKind.ANY ->
        "No Aadhaar number could be read from that photograph on this phone, and there is no " +
            "connection to ask the server. Fill the frame with the card and hold it flat, or type " +
            "the number in — a Pehchan card can only be read with a connection."
    else ->
        "No number could be read from that photograph on this phone, and there is no connection to " +
            "ask the server. Fill the frame with the card, hold it flat and try again — or type the " +
            "number in."
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
