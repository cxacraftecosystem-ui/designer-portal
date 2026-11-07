package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.ui.AppToggle
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.destructiveAction
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
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
import com.designprototype.workshop.data.DW_RETENTION_DISCARD
import com.designprototype.workshop.data.DW_RETENTION_STORE
import com.designprototype.workshop.data.DwIdentityCandidateDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.IdentityCardRecognizer
import com.designprototype.workshop.data.IdentityCardText
import com.designprototype.workshop.data.MlKitIdentityCardRecognizer
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.photographWasNotStored
import com.designprototype.workshop.data.retentionChoiceBlurb
import com.designprototype.workshop.data.retentionOutcomeSentence
import kotlinx.coroutines.CancellationException
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.DW_CAMERA_SETTINGS_BUTTON
import com.designprototype.workshop.ui.DwCameraUse
import com.designprototype.workshop.ui.dwCameraBlocked
import com.designprototype.workshop.ui.dwCameraRefusal
import com.designprototype.workshop.ui.dwOpenAppPermissionSettings
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch
import java.io.File

internal enum class DwIdentityKind {
    AADHAAR,
    PEHCHAN,

    ANY,
}

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

internal fun identityKindFor(field: FieldDto): DwIdentityKind {
    val haystack = (field.key + " " + field.label).lowercase()
    return when {
        haystack.contains("aadhaar") || haystack.contains("aadhar") -> DwIdentityKind.AADHAAR
        haystack.contains("pehchan") -> DwIdentityKind.PEHCHAN
        else -> DwIdentityKind.ANY
    }
}

internal enum class DwIdentitySource {
    ON_DEVICE,

    SERVER,
}

internal data class DwIdentityChoice(
    val value: String,
    val kind: DwIdentityKind,
    val confidence: Double?,
    val source: DwIdentitySource,
)

internal data class DwIdentityProposal(
    val value: String,
    val kind: DwIdentityKind,
    val confidence: Double?,
)

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
            DwIdentityKind.PEHCHAN -> ArtisanIdentity.normalizePehchan(proposal.value)
                .takeIf { ArtisanIdentity.isPehchan(it) }
            DwIdentityKind.ANY -> null
        } ?: return@forEach
        if (out.none { it.value == cleaned }) {
            out += DwIdentityChoice(cleaned, proposal.kind, proposal.confidence, source)
        }
    }
    return out
}

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

private fun identityScratchDir(context: Context): File =
    File(context.filesDir, "identity-scratch").apply { mkdirs() }

private fun sweepIdentityScratch(context: Context) {
    runCatching { identityScratchDir(context).listFiles()?.forEach { it.delete() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DwIdentityCardControl(
    targetLabel: String,
    kind: DwIdentityKind,
    repository: WorkshopRepository,
    enabled: Boolean,
    storeMasked: Boolean = false,
    onUse: (String) -> Unit,
    onError: (String) -> Unit,
    onKeepPhotograph: (suspend (Uri) -> String?)? = null,
    recognizer: IdentityCardRecognizer = MlKitIdentityCardRecognizer,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<DwIdentityChoice>>(emptyList()) }
    var unconfirmedByServer by remember { mutableStateOf(false) }
    var cameraBlocked by remember { mutableStateOf(false) }

    var keepPhotograph by remember { mutableStateOf(false) }
    val canKeep = onKeepPhotograph != null
    var keptNote by remember { mutableStateOf<String?>(null) }
    var serverConfirmedDiscard by remember { mutableStateOf(false) }

    val readableOnDevice = kind != DwIdentityKind.PEHCHAN

    DisposableEffect(Unit) {
        sweepIdentityScratch(context)
        onDispose { sweepIdentityScratch(context) }
    }

    fun discardPhoto() {
        pending?.let { file -> runCatching { file.delete() } }
        pending = null
    }

    fun send(source: Uri) {
        working = true
        choices = emptyList()
        unconfirmedByServer = false
        serverConfirmedDiscard = false
        keptNote = null
        scope.launch {
            val keep = keepPhotograph && onKeepPhotograph != null
            var keptMediaId: String? = null
            if (keep) {
                runCatching { onKeepPhotograph!!.invoke(source) }
                    .onSuccess { mediaId ->
                        keptMediaId = mediaId
                        keptNote = if (mediaId.isNullOrBlank()) {
                            "Photo NOT kept — nothing attached. The number can still be read below."
                        } else {
                            null
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        keptNote = error.apiErrorMessage(
                            "Photo NOT kept. The number can still be read below."
                        )
                    }
            }

            keptMediaId?.takeIf { it.isNotBlank() }?.let { mediaId ->
                runCatching { repository.decideIdentityPhotograph(mediaId, DW_RETENTION_STORE) }
                    .onSuccess { keptNote = retentionOutcomeSentence(it) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        keptNote = "Photo kept, but who kept it wasn't recorded. Tell an admin."
                    }
            }

            val localReading = runCatching { IdentityCardText.read(recognizer.scan(context, source)) }
            val local = localReading.getOrNull()?.let { identityChoices(it, kind) }.orEmpty()
            if (local.isNotEmpty()) {
                working = false
                choices = local
                return@launch
            }

            val localRejected = localReading.getOrNull()?.rejectedAadhaarCount ?: 0
            if (!ConnectivityObserver.isOnline(context)) {
                working = false
                discardPhoto()
                onError(nothingFoundOffline(kind, readableOnDevice, localRejected))
                return@launch
            }

            runCatching {
                repository.designWorkshopIdentityOcr(
                    context = context,
                    uri = source,
                    retention = if (keep) DW_RETENTION_STORE else DW_RETENTION_DISCARD,
                )
            }
                .onSuccess { result ->
                    working = false
                    serverConfirmedDiscard = !keep && photographWasNotStored(result)
                    val offered = identityChoices(result.aadhaarCandidates, result.pehchanCandidates, kind)
                    if (offered.isEmpty()) {
                        discardPhoto()
                        onError(
                            (localRejected + result.rejectedAadhaarCount).let { rejected ->
                                if (rejected > 0) {
                                    "${plural(rejected, "number")} read but none valid, and there's no connection to try online. Retake in better light, or type it."
                                } else {
                                    "No number found, and there's no connection to try online. Fill the frame, hold flat and retry — or type it."
                                }
                            }
                        )
                    } else {
                        choices = offered
                        unconfirmedByServer = !result.requiresConfirmation
                    }
                }
                .onFailure { error ->
                    working = false
                    discardPhoto()
                    onError(
                        if (!ConnectivityObserver.isOnline(context)) {
                            "Couldn't read the card and the connection dropped. Type it, or retry online."
                        } else {
                            error.apiErrorMessage(
                                "Couldn't read the card. Type the number instead."
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
                val blocked = dwCameraBlocked(context)
                cameraBlocked = blocked
                onError(dwCameraRefusal(DwCameraUse.IDENTITY_CARD, blocked))
            }
            return@rememberLauncherForActivityResult
        }
        cameraBlocked = false
        takePhoto.launch(dwCaptureUri(context, file))
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) send(uri) }

    fun refuseOffline(): Boolean {
        if (readableOnDevice || ConnectivityObserver.isOnline(context)) return false
        onError(
            "Pehchan cards need a connection to read. Type it now, or retry online."
        )
        return true
    }

    fun startCamera() {
        if (refuseOffline()) return
        val file = File(identityScratchDir(context), "card-${System.currentTimeMillis()}.jpg")
        pending = file
        if (hasPermission(context, Manifest.permission.CAMERA)) {
            cameraBlocked = false
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
    val usable = enabled && !working && (readableOnDevice || online)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canKeep) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppToggle(
                    label = "Keep the photograph on this record",
                    checked = keepPhotograph,
                    enabled = enabled && !working,
                    onCheckedChange = { keepPhotograph = it },
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        retentionChoiceBlurb(keepPhotograph),
                        color = if (keepPhotograph) {
                            MaterialTheme.field.warning
                        } else {
                            MaterialTheme.field.muted
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        ActionRow {
            OutlinedButton(onClick = ::startCamera, enabled = usable) {
                if (working) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Reading the card…" else "Photograph the card", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = ::startPick, enabled = usable) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Choose a photo", style = MaterialTheme.typography.labelLarge)
            }
            if (cameraBlocked) {
                OutlinedButton(onClick = { context.dwOpenAppPermissionSettings() }) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(DW_CAMERA_SETTINGS_BUTTON, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text(
            when {
                !readableOnDevice && !online ->
                    "Reading Pehchan needs a connection. Type the number instead."
                !readableOnDevice ->
                    "Photograph or pick the card. It's read online; you confirm it first." + notKeptSuffix(canKeep)
                online ->
                    "Photograph or pick the card. You confirm the number before it's filled in." + notKeptSuffix(canKeep)
                else ->
                    "Works offline. You confirm the number before it's filled in." + notKeptSuffix(canKeep)
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        keptNote?.let { note ->
            Text(note, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
        }
        if (serverConfirmedDiscard) {
            Text(
                "The photo was read and discarded.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
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
                else "Check these ${choices.size} numbers against the card",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Nothing is saved into $targetLabel until you tap a number. Check it against the card itself.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (storeMasked) {
                Text(
                    "$targetLabel stores only the last four digits (“XXXX XXXX ####”).",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (unconfirmedByServer) {
                Text(
                    "Check this against the card.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            choices.forEach { choice ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ActionRow {
                        Button(
                            onClick = {
                                onUse(choice.value)
                                choices = emptyList()
                                discardPhoto()
                            },
                            enabled = enabled
                        ) {
                            Text("Use ${displayOf(choice)}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text(
                        listOfNotNull(
                            if (choice.kind == DwIdentityKind.AADHAAR) "Aadhaar" else "Pehchan card",
                            when (choice.source) {
                                DwIdentitySource.ON_DEVICE -> "read on this phone"
                                DwIdentitySource.SERVER -> "read online"
                            },
                            choice.confidence?.let { if (it >= 0.85) "read clearly" else "read with difficulty" },
                        ).joinToString(" · "),
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            ActionRow {
                TextButton(modifier = Modifier.destructiveAction(), onClick = {
                    choices = emptyList()
                    discardPhoto()
                }) { Text("None of these — discard", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

internal fun nothingFoundOffline(
    kind: DwIdentityKind,
    readableOnDevice: Boolean,
    rejected: Int,
): String = when {
    !readableOnDevice ->
        "Pehchan needs a connection. Type it now."
    rejected > 0 ->
        "${plural(rejected, "number")} read but none valid, and there's no connection to try online. Retake in better light, or type it."
    kind == DwIdentityKind.PEHCHAN || kind == DwIdentityKind.ANY ->
        "No Aadhaar number found, and you're offline. Retry or type it (Pehchan needs a connection)."
    else ->
        "No number found, and there's no connection to try online. Fill the frame, hold flat and retry — or type it."
}

internal fun notKeptSuffix(canKeep: Boolean): String =
    if (canKeep) "" else " The photograph is not kept."

private fun displayOf(choice: DwIdentityChoice): String =
    if (choice.kind == DwIdentityKind.AADHAAR) ArtisanIdentity.grouped(choice.value) else choice.value
