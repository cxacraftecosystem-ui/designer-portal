package com.designprototype.workshop.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.TwoFactorSetupDto
import com.designprototype.workshop.data.TwoFactorStatusDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import kotlinx.coroutines.launch
import retrofit2.HttpException

/*
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * TWO-STEP VERIFICATION (TOTP) ON THE HANDSET
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Three surfaces, the web's three, with the web's words:
 *
 *   1. [TwoStepChallengeForm] — the sign-in's second step, drawn by `MainActivity` in place of the
 *      sign-in card after ANY first factor (password, Google, Microsoft, Yahoo) answered a challenge.
 *   2. [TwoStepVerificationScreen] — the account's own switch, reached from Settings.
 *   3. The administrator's "Turn off two-step verification", on the users card in `MainActivity`.
 *
 * The server does the work (`backend/app/services/two_factor.py`): it checks and SPENDS the code, and
 * it draws the QR square. What lives here is the pure logic the tests pin — which box a code belongs
 * in ([TwoFactorCode]), whether a URI is one an authenticator should be handed, and reading the
 * server's SVG ([QrSvg]) — and the screens.
 *
 * THE PHONE IS USUALLY THE DEVICE WITH THE AUTHENTICATOR ON IT, which is why setup here offers "Open in
 * authenticator app" (ACTION_VIEW on the otpauth URI) and "Copy key" before the square: a person cannot
 * scan a QR code that is on the same screen as the camera they would scan it with.
 *
 * OFFLINE: asked only at an online sign-in. A session already cached on this handset keeps working,
 * because the second step is a property of opening a session, not of using one.
 */

/** Which kind of code a string is, by SHAPE — the server's `code_kind` and the web's `codeKind`. */
object TwoFactorCode {
    enum class Kind { AUTHENTICATOR, RECOVERY }

    fun kindOf(text: String): Kind? {
        val compact = text.filterNot { it.isWhitespace() || it == '-' }
        return when {
            compact.length == 6 && compact.all { it in '0'..'9' } -> Kind.AUTHENTICATOR
            compact.length == 8 && compact.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } -> Kind.RECOVERY
            else -> null
        }
    }

    /**
     * The URI to hand an authenticator app, or null when this is not one.
     *
     * Only `otpauth://totp/…` is ever launched: the string arrives from the network, and an
     * ACTION_VIEW on whatever a response happened to carry would let it open anything on the phone.
     */
    fun authenticatorUri(otpauthUri: String): String? {
        val trimmed = otpauthUri.trim()
        return if (trimmed.startsWith("otpauth://totp/", ignoreCase = false) && "secret=" in trimmed) trimmed else null
    }

    /** The key in groups of four, which is how an authenticator's manual-entry box is easiest to type. */
    fun grouped(secret: String): String = secret.chunked(4).joinToString(" ")
}

/**
 * The server's QR square, read. The server sends exactly one `viewBox="0 0 N N"` and one `<path d=…>`
 * made of `M`, `h`, `v` and `z` only (`two_factor.qr_svg`), which is what lets this app draw it with
 * Compose's own path parser instead of shipping an SVG renderer.
 */
object QrSvg {
    data class Drawing(val size: Int, val pathData: String)

    private val viewBox = Regex("""viewBox="0 0 (\d+) (\d+)"""")
    private val path = Regex("""<path[^>]* d="([Mhvz0-9 \-]+)"""")

    fun parse(svg: String): Drawing? {
        val box = viewBox.find(svg) ?: return null
        val width = box.groupValues[1].toIntOrNull() ?: return null
        if (width <= 0 || width != box.groupValues[2].toIntOrNull()) return null
        val d = path.find(svg)?.groupValues?.get(1) ?: return null
        return Drawing(width, d)
    }
}

/** The square, drawn black on its own white ground whatever the theme — a scanner needs the contrast. */
@Composable
fun QrSquare(svg: String, modifier: Modifier = Modifier) {
    val drawing = remember(svg) { QrSvg.parse(svg) }
    val path = remember(drawing) { drawing?.let { PathParser().parsePathString(it.pathData).toPath() } }
    if (drawing == null || path == null) return
    Canvas(
        modifier = modifier
            .size(200.dp)
            .background(Color.White)
            .semantics { contentDescription = "QR code to add this account to your authenticator app" }
    ) {
        val unit = size.minDimension / drawing.size
        scale(unit, unit, pivot = Offset.Zero) { drawPath(path, Color.Black) }
    }
}

private fun copyText(context: Context, label: String, text: String): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}.isSuccess

/**
 * The server's sentence for a finished challenge (`two_factor.CHALLENGE_DEAD_DETAIL`), matched exactly:
 * the one refusal after which the form has nothing left to offer but signing in again.
 */
const val CHALLENGE_ENDED_DETAIL = "This sign-in has timed out. Sign in again."

/* ── 1. The sign-in's second step ─────────────────────────────────────────────────────────────── */

@Composable
fun TwoStepChallengeForm(
    repository: WorkshopRepository,
    challengeToken: String,
    /** The account, signed in. The caller settles whatever else sign-in owes (consent, a password). */
    onSignedIn: suspend (UserDto) -> Unit,
    /** Back to the sign-in card; the challenge is dropped. Nobody was signed in. */
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var useRecovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dead by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Two-step verification", display = true, fontSize = 24.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            if (useRecovery) "Enter one of the recovery codes you saved. Each code works once."
            else "Enter the 6-digit code from your authenticator app.",
            color = MaterialTheme.field.muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!dead) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(20) },
                        label = { Text(if (useRecovery) "Recovery code" else "6-digit code") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (useRecovery) KeyboardType.Ascii else KeyboardType.NumberPassword
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                if (dead) {
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Sign in again") }
                } else {
                    Button(
                        enabled = !busy && code.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                runCatching { repository.verifyTwoStep(challengeToken, code) }
                                    .onSuccess { onSignedIn(it) }
                                    .onFailure { failure ->
                                        code = ""
                                        val message = failure.apiErrorMessage("That code didn't work. Try again.")
                                        if ((failure as? HttpException)?.code() == 401 && message == CHALLENGE_ENDED_DETAIL) dead = true
                                        error = message
                                    }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Checking…" else "Verify") }
                    TextButton(onClick = {
                        useRecovery = !useRecovery
                        code = ""
                        error = null
                    }) {
                        Text(if (useRecovery) "Use your authenticator app instead" else "Use a recovery code instead")
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Back to sign in") }
    }
}

/* ── 2. The account's own switch ──────────────────────────────────────────────────────────────── */

private sealed interface TwoStepStep {
    data object Status : TwoStepStep
    data class Setup(val setup: TwoFactorSetupDto) : TwoStepStep
    data class Codes(val codes: List<String>) : TwoStepStep
    data object ConfirmDisable : TwoStepStep
    data object ConfirmRegenerate : TwoStepStep
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoStepVerificationScreen(
    repository: WorkshopRepository,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Two-step verification") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { TwoStepVerificationCard(repository) }
        }
    }
}

/**
 * The card itself: off → "Turn on" → setup → recovery codes; on → status, codes left, "Make new
 * recovery codes", "Turn off". The recovery codes are shown ONCE — the server keeps only hashes — so
 * that step stays on screen until "I've saved them" is pressed.
 */
@Composable
fun TwoStepVerificationCard(repository: WorkshopRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TwoFactorStatusDto?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<TwoStepStep>(TwoStepStep.Status) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching { repository.twoFactorStatus() }
            .onSuccess { status = it; loadError = null }
            .onFailure { loadError = it.apiErrorMessage("Two-step verification could not be loaded.") }
    }

    LaunchedEffect(Unit) { load() }

    fun go(next: TwoStepStep) {
        step = next
        code = ""
        error = null
    }

    fun submit() {
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching {
                when (step) {
                    is TwoStepStep.Setup -> go(TwoStepStep.Codes(repository.enableTwoFactor(code)))
                    TwoStepStep.ConfirmRegenerate -> go(TwoStepStep.Codes(repository.makeNewRecoveryCodes(code)))
                    TwoStepStep.ConfirmDisable -> {
                        repository.disableTwoFactor(code)
                        go(TwoStepStep.Status)
                        notice = "Two-step verification is off."
                    }
                    else -> Unit
                }
                runCatching { repository.refreshUser() }
                load()
            }.onFailure {
                code = ""
                error = it.apiErrorMessage("That code didn't work. Try again.")
            }
            busy = false
        }
    }

    PreferenceCard {
        PreferenceCardHeading(Icons.Filled.Security, "Two-step verification")
        Text(
            "After you sign in, also enter a 6-digit code from an authenticator app on your phone, such as " +
                "Google Authenticator or Microsoft Authenticator.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.muted
        )
        (error ?: loadError)?.let {
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }
        notice?.let {
            Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp)
        }

        val current = status
        when {
            current == null -> if (loadError != null) {
                OutlinedButton(onClick = { scope.launch { load() } }) { Text("Try again") }
            } else {
                Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            step == TwoStepStep.Status && current.enabled -> {
                Text("On", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Recovery codes left: ${current.recoveryCodesRemaining} of 10",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                if (current.recoveryCodesRemaining <= 2) {
                    Text(
                        "You are running out of recovery codes. Make new ones so you can still get in without your phone.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
                OutlinedButton(onClick = { go(TwoStepStep.ConfirmRegenerate) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Make new recovery codes")
                }
                OutlinedButton(
                    onClick = { go(TwoStepStep.ConfirmDisable) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Turn off") }
            }

            step == TwoStepStep.Status -> Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        notice = null
                        runCatching { repository.beginTwoFactorSetup() }
                            .onSuccess { go(TwoStepStep.Setup(it)) }
                            .onFailure { error = it.apiErrorMessage("Setup could not be started.") }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Starting…" else "Turn on") }

            else -> when (val shown = step) {
                is TwoStepStep.Setup -> {
                    val uri = TwoFactorCode.authenticatorUri(shown.setup.otpauthUri)
                    Text("1. Add this account to your authenticator app.", fontSize = 14.sp)
                    if (uri != null) {
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                                } catch (_: ActivityNotFoundException) {
                                    notice = "No authenticator app was found on this phone. Install one, or copy the key."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open in authenticator app") }
                    }
                    Text("Or type this key into the app:", color = MaterialTheme.field.muted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            TwoFactorCode.grouped(shown.setup.secret),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            notice = if (copyText(context, "Authenticator key", shown.setup.secret)) "Key copied." else null
                        }) { Text("Copy key") }
                    }
                    Text("Or scan it from another device:", color = MaterialTheme.field.muted, fontSize = 13.sp)
                    QrSquare(shown.setup.qrSvg)
                    Text("2. Enter the 6-digit code the app shows.", fontSize = 14.sp)
                    CodeBox(code, { code = it }, "6-digit code", allowRecovery = false)
                    Button(enabled = !busy && code.isNotBlank(), onClick = { submit() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Checking…" else "Turn on")
                    }
                    TextButton(onClick = { go(TwoStepStep.Status) }) { Text("Cancel") }
                }

                is TwoStepStep.Codes -> {
                    Text("Save these codes", display = true, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "If you lose your phone, each code gets you in once instead of the 6-digit code. Keep them " +
                            "somewhere safe. They will not be shown again.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        shown.codes.chunked(2).forEach { pair ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { recovery ->
                                    Text(recovery, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                notice = if (copyText(context, "Recovery codes", shown.codes.joinToString("\n"))) {
                                    "Recovery codes copied."
                                } else null
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Copy") }
                        OutlinedButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Design Prototype Workshop recovery codes")
                                    putExtra(Intent.EXTRA_TEXT, shown.codes.joinToString("\n"))
                                }
                                runCatching { context.startActivity(Intent.createChooser(send, "Save recovery codes")) }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Share") }
                    }
                    Button(onClick = { go(TwoStepStep.Status); notice = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("I've saved them")
                    }
                }

                TwoStepStep.ConfirmDisable, TwoStepStep.ConfirmRegenerate -> {
                    val disabling = shown == TwoStepStep.ConfirmDisable
                    CodeBox(
                        code,
                        { code = it },
                        if (disabling) "Code" else "6-digit code",
                        allowRecovery = disabling
                    )
                    Text(
                        if (disabling) "Enter the 6-digit code from your authenticator app, or one of your recovery codes."
                        else "Enter the code from your authenticator app. Your old recovery codes will stop working.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    Button(
                        enabled = !busy && code.isNotBlank(),
                        onClick = { submit() },
                        colors = if (disabling) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        else ButtonDefaults.buttonColors(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Checking…" else if (disabling) "Turn off" else "Make new codes") }
                    TextButton(onClick = { go(TwoStepStep.Status) }) { Text("Cancel") }
                }

                TwoStepStep.Status -> Unit
            }
        }
    }
}

@Composable
private fun CodeBox(value: String, onChange: (String) -> Unit, label: String, allowRecovery: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(20)) },
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (allowRecovery) KeyboardType.Ascii else KeyboardType.NumberPassword
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
