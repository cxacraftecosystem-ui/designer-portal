package com.designprototype.workshop.ui.aadhaar

import com.designprototype.workshop.ui.ActionRow
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.AadhaarClient
import com.designprototype.workshop.data.AadhaarFeatures
import com.designprototype.workshop.data.AadhaarOtpIssuedDto
import com.designprototype.workshop.data.AadhaarSubject
import com.designprototype.workshop.data.AadhaarSummaryDto
import com.designprototype.workshop.data.DwQrReadResult
import com.designprototype.workshop.data.DwQrSource
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.apiRefusal
import com.designprototype.workshop.data.dwReadQrPicture
import com.designprototype.workshop.data.looksLikeSecureQr
import com.designprototype.workshop.ui.DwQrLiveScanControl
import com.designprototype.workshop.ui.PreferenceCard
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Verify with Aadhaar" on the handset — the web's `components/aadhaar/AadhaarVerifyPanel.tsx`, step
 * for step and word for word: status → consent → method → the method's form → status again.
 *
 * DRAWS NOTHING while the account the handset holds says both Aadhaar features are off — and an
 * account cached before the server sent `features`, or from a server that never does, reads as off
 * ([AadhaarFeatures.of]). Only the methods that are on are offered.
 *
 * The QR is read by the camera scanner this app already ships for workshop codes
 * ([DwQrLiveScanControl]: CameraX with ML Kit, ZXing as the fallback reader) or from a picture with
 * the same ZXing reader; the ZIP is read from the file the person picks. All of it is held in memory
 * for the one request and is never written to disk, the outbox or preferences.
 */
object AadhaarCopy {
    const val TITLE = "Aadhaar verification"
    const val VERIFY = "Verify with Aadhaar"
    const val VERIFY_AGAIN = "Verify again"
    const val REMOVE = "Remove verification"
    const val CONSENT_HEADING = "Before you continue"
    const val CONTINUE = "Continue"
    const val CANCEL = "Cancel"
    const val METHOD_HEADING = "How would you like to verify?"
    const val VERIFIED = "Verified with Aadhaar."
    const val MISMATCH =
        "Verified by UIDAI, but the name differs from this record. An admin will review it."
    const val NO_QR_IN_PICTURE =
        "No Aadhaar QR found. Use a sharp, straight-on image of the whole QR code."
    const val NOT_A_SECURE_QR =
        "Not an Aadhaar Secure QR. Scan the large QR on the letter, card or e-Aadhaar."
    const val VID_HINT =
        "A 16-digit Virtual ID (from UIDAI) keeps the Aadhaar number private. Neither is stored."

    /** The consent notice. The SAME sentences as `frontend/lib/aadhaar.ts`'s `consentPoints`. */
    fun consentPoints(subject: AadhaarSubject): List<String> {
        val whose = if (subject is AadhaarSubject.Self) "your" else "the artisan's"
        return listOf(
            "This checks $whose identity against UIDAI, the authority that issues Aadhaar. It is voluntary — nothing else in this app depends on it.",
            "What is read: the name, date of birth and gender UIDAI signed, and the last four digits of the Aadhaar number.",
            "What is kept: only that the check passed, how and when, the name as UIDAI holds it, the year of birth (the full date for an artisan record), the gender and those last four digits.",
            "What is never kept: the full Aadhaar number, the photograph, the address, the QR code or file itself, the share code or any one-time code.",
            "You can remove the result at any time from this screen.",
        )
    }

    fun consentStatement(subject: AadhaarSubject): String =
        if (subject is AadhaarSubject.Self) "I agree to verify my identity with Aadhaar as described above."
        else "The artisan has heard this read out and agrees to verify their identity with Aadhaar."

    fun statusLabel(status: String?): String? = when (status) {
        "VERIFIED" -> "Aadhaar verified"
        "NAME_MISMATCH" -> "Aadhaar name differs"
        "REJECTED" -> "Aadhaar not accepted"
        else -> null
    }

    fun methodLabel(method: String?): String? = when (method) {
        "SECURE_QR" -> "Aadhaar QR code"
        "OFFLINE_XML" -> "Offline e-KYC file"
        "ONLINE_OTP" -> "Aadhaar OTP"
        "ONLINE_EKYC" -> "Aadhaar e-KYC"
        else -> null
    }
}

private enum class Step { STATUS, CONSENT, METHOD, QR, ZIP, OTP }

private val VerifiedInk = Color(0xFF15803D)
private val VerifiedGround = Color(0xFFDCFCE7)
private val WarningInk = Color(0xFF92400E)
private val WarningGround = Color(0xFFFEF3C7)
private val RefusedInk = Color(0xFFDC2626)
private val RefusedGround = Color(0xFFFEE2E2)

/** The status chip, with its word — colour is never the only channel. */
@Composable
fun AadhaarStatusChip(status: String?) {
    val label = AadhaarCopy.statusLabel(status) ?: return
    val (ink, ground) = when (status) {
        "VERIFIED" -> VerifiedInk to VerifiedGround
        "NAME_MISMATCH" -> WarningInk to WarningGround
        else -> RefusedInk to RefusedGround
    }
    Text(
        label,
        color = ink,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(ground, RoundedCornerShape(50))
            .border(1.dp, ink.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun readBytes(context: Context, uri: Uri, limit: Int = 4 * 1024 * 1024): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // Bounded by hand: InputStream.readNBytes is API 33 and this app runs from 26.
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) return@use null
            }
            out.toByteArray()
        }
    }.getOrNull()

@Composable
fun AadhaarVerifyCard(
    subject: AadhaarSubject,
    viewer: UserDto?,
    recordName: String? = null,
) {
    val features = AadhaarFeatures.of(viewer)
    if (!features.enabled) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { AadhaarClient.create(context) }
    var summary by remember(subject) { mutableStateOf<AadhaarSummaryDto?>(null) }
    var step by remember(subject) { mutableStateOf(Step.STATUS) }
    var agreed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var zipBytes by remember { mutableStateOf<ByteArray?>(null) }
    var zipName by remember { mutableStateOf<String?>(null) }
    var shareCode by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var uid by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var issued by remember { mutableStateOf<AadhaarOtpIssuedDto?>(null) }

    LaunchedEffect(subject) {
        summary = runCatching { client.read(subject) }.getOrNull()
    }

    fun reset(next: Step = Step.STATUS) {
        step = next
        agreed = false
        zipBytes = null
        zipName = null
        shareCode = ""
        mobile = ""
        email = ""
        uid = ""
        otp = ""
        issued = null
    }

    fun run(fallback: String, action: suspend () -> AadhaarSummaryDto) {
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching { action() }
                .onSuccess { result ->
                    summary = result
                    reset()
                    notice = if (result.status == "VERIFIED") AadhaarCopy.VERIFIED else AadhaarCopy.MISMATCH
                }
                .onFailure { error = it.apiRefusal(fallback).message }
            busy = false
        }
    }

    fun sendQr(text: String) {
        if (!looksLikeSecureQr(text)) {
            error = AadhaarCopy.NOT_A_SECURE_QR
            return
        }
        run("Couldn't verify the QR. Try again.") { client.secureQr(subject, text) }
    }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            when (val read = dwReadQrPicture(context, uri, DwQrSource.PICTURE)) {
                is DwQrReadResult.Found -> {
                    busy = false
                    sendQr(read.text)
                }
                is DwQrReadResult.NothingFound, is DwQrReadResult.Unreadable -> {
                    busy = false
                    error = AadhaarCopy.NO_QR_IN_PICTURE
                }
            }
        }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readBytes(context, uri) }
            if (bytes == null) {
                error = "Couldn't read that file. Pick the ZIP from UIDAI."
            } else {
                zipBytes = bytes
                zipName = uri.lastPathSegment?.substringAfterLast('/') ?: "offline e-KYC file"
                error = null
            }
        }
    }

    PreferenceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                if (subject is AadhaarSubject.Artisan && recordName != null) "${AadhaarCopy.TITLE} — $recordName" else AadhaarCopy.TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        when (step) {
            Step.STATUS -> {
                val current = summary
                if (current?.status != null) {
                    AadhaarStatusChip(current.status)
                    val detail = listOfNotNull(
                        AadhaarCopy.methodLabel(current.method),
                        current.verifiedAt?.take(10),
                        current.last4?.let { "Aadhaar ending $it" },
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, color = MaterialTheme.field.muted, fontSize = 13.sp)
                    current.name?.let { Text("Name as UIDAI holds it: $it", fontSize = 13.sp) }
                    if (current.status == "NAME_MISMATCH") {
                        current.recordName?.let { Text("Name on this record: $it", color = WarningInk, fontSize = 13.sp) }
                    }
                } else {
                    Text(
                        if (subject is AadhaarSubject.Self) "Verify this account with your Aadhaar."
                        else "Verify this artisan with their Aadhaar.",
                        fontSize = 14.sp,
                    )
                }
                Button(onClick = { step = Step.CONSENT }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (current?.status != null) AadhaarCopy.VERIFY_AGAIN else AadhaarCopy.VERIFY)
                }
                if (current?.status != null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { client.withdraw(subject) }
                                    .onSuccess { summary = it; notice = "The Aadhaar verification was removed." }
                                    .onFailure { error = it.apiRefusal("It could not be removed. Try again.").message }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(AadhaarCopy.REMOVE) }
                }
                if (current?.status == "NAME_MISMATCH" && subject is AadhaarSubject.Artisan && isAdminRole(viewer?.role)) {
                    OutlinedButton(
                        onClick = { run("The decision could not be saved.") { client.decideArtisan(subject.id, true) } },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Accept as the same person") }
                    OutlinedButton(
                        onClick = { run("The decision could not be saved.") { client.decideArtisan(subject.id, false) } },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Do not accept") }
                }
            }

            Step.CONSENT -> {
                Text(AadhaarCopy.CONSENT_HEADING, fontWeight = FontWeight.SemiBold)
                AadhaarCopy.consentPoints(subject).forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", fontSize = 14.sp)
                        Text(point, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { agreed = !agreed },
                ) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text(AadhaarCopy.consentStatement(subject), fontSize = 14.sp)
                }
                Button(onClick = { step = Step.METHOD }, enabled = agreed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(AadhaarCopy.CONTINUE)
                }
                ActionRow {
                    TextButton(onClick = { reset() }) { Text(AadhaarCopy.CANCEL) }
                }
            }

            Step.METHOD -> {
                Text(AadhaarCopy.METHOD_HEADING, fontWeight = FontWeight.SemiBold)
                if (features.offline) {
                    MethodRow(
                        icon = { Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        title = "The Aadhaar QR code",
                        note = "Scan the large QR on the letter, PVC card or e-Aadhaar, or use a picture.",
                    ) { step = Step.QR }
                    MethodRow(
                        icon = { Icon(Icons.Filled.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        title = "The offline e-KYC file",
                        note = "The Offline e-KYC ZIP from UIDAI, plus its share code.",
                    ) { step = Step.ZIP }
                }
                if (features.online) {
                    MethodRow(
                        icon = { Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        title = "A one-time code from UIDAI",
                        note = "Sent to the Aadhaar-registered mobile.",
                    ) { step = Step.OTP }
                }
                ActionRow {
                    TextButton(onClick = { reset() }) { Text(AadhaarCopy.CANCEL) }
                }
            }

            Step.QR -> {
                DwQrLiveScanControl(
                    enabled = !busy,
                    onText = { text -> sendQr(text) },
                    onRefusal = { message -> error = message },
                )
                OutlinedButton(
                    onClick = { pickPicture.launch("image/*") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Reading…" else "Read it from a picture")
                }
                ActionRow {
                    TextButton(onClick = { reset() }, enabled = !busy) { Text(AadhaarCopy.CANCEL) }
                }
            }

            Step.ZIP -> {
                OutlinedButton(
                    onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(zipName ?: "Choose the ZIP file")
                }
                OutlinedTextField(
                    value = shareCode,
                    onValueChange = { shareCode = it },
                    label = { Text("Share code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Optional: registered mobile or email to cross-check. Not stored.",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = mobile,
                    onValueChange = { mobile = it },
                    label = { Text("Mobile number (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val bytes = zipBytes ?: return@Button
                        run("The file could not be verified. Try again.") {
                            client.offlineXml(subject, bytes, shareCode, mobile, email)
                        }
                    },
                    enabled = !busy && zipBytes != null && shareCode.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (busy) "Checking…" else "Verify") }
                ActionRow {
                    TextButton(onClick = { reset() }, enabled = !busy) { Text(AadhaarCopy.CANCEL) }
                }
            }

            Step.OTP -> {
                OutlinedTextField(
                    value = uid,
                    onValueChange = { uid = it },
                    label = { Text("Virtual ID (VID) or Aadhaar number") },
                    singleLine = true,
                    enabled = issued == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(AadhaarCopy.VID_HINT, color = MaterialTheme.field.muted, fontSize = 12.sp)
                val current = issued
                if (current != null) {
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { otp = it },
                        label = { Text("The six-digit code UIDAI sent") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = {
                        if (current == null) {
                            scope.launch {
                                busy = true
                                error = null
                                runCatching { client.requestOtp(subject, uid) }
                                    .onSuccess { issued = it }
                                    .onFailure { error = it.apiRefusal("The code could not be sent. Try again.").message }
                                busy = false
                            }
                        } else {
                            run("The code could not be checked. Try again.") { client.verifyOtp(subject, uid, otp, current) }
                        }
                    },
                    enabled = !busy && uid.isNotBlank() && (current == null || otp.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (busy) "Please wait…" else if (current == null) "Send the code" else "Verify") }
                ActionRow {
                    TextButton(onClick = { reset() }, enabled = !busy) { Text(AadhaarCopy.CANCEL) }
                }
            }
        }

        val line = error ?: notice
        if (line != null) {
            Text(
                line,
                color = if (error != null) RefusedInk else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun MethodRow(icon: @Composable () -> Unit, title: String, note: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(note, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
    }
}

private fun isAdminRole(role: String?): Boolean = role == "ADMIN" || role == "MASTER_ADMIN"

/** The account's own verification, one level below Settings — the web's card on /settings. */
@Composable
fun AadhaarSelfScreen(viewer: UserDto?, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
            }
            Text(AadhaarCopy.TITLE, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        }
        AadhaarVerifyCard(subject = AadhaarSubject.Self, viewer = viewer)
    }
}

/**
 * The chip on a user-management row, and an administrator's decision on a name that did not match.
 * Nothing while both features are off or the account has never verified.
 */
@Composable
fun AadhaarUserBadge(account: UserDto, viewer: UserDto?) {
    if (!AadhaarFeatures.of(viewer).enabled) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(account.id, account.aadhaarVerifyStatus) { mutableStateOf(account.aadhaarVerifyStatus) }
    var reviewing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (status == null) return
    val mayDecide = status == "NAME_MISMATCH" && isAdminRole(viewer?.role) && viewer?.id != account.id
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AadhaarStatusChip(status)
        if (mayDecide) TextButton(onClick = { reviewing = true }) { Text("Review") }
    }
    if (reviewing) {
        val client = remember { AadhaarClient.create(context) }
        fun decide(accept: Boolean) {
            scope.launch {
                runCatching { client.decideUser(account.id, accept) }
                    .onSuccess { status = it.status; reviewing = false }
                    .onFailure { error = it.apiRefusal("The decision could not be saved. Try again.").message }
            }
        }
        AlertDialog(
            onDismissRequest = { reviewing = false },
            title = { Text("Aadhaar name differs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Verified by UIDAI, but the names don't match closely enough.")
                    Text("Name on the account: ${account.name}")
                    Text("Name as UIDAI holds it: ${account.aadhaarVerifyName ?: "UIDAI returned no name for this method"}")
                    error?.let { Text(it, color = RefusedInk) }
                }
            },
            confirmButton = { TextButton(onClick = { decide(true) }) { Text("Accept as the same person") } },
            dismissButton = { TextButton(onClick = { decide(false) }) { Text("Do not accept") } },
        )
    }
}
