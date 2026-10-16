package com.designprototype.workshop.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import com.designprototype.workshop.data.AppScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.BackHandler
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

/* ── 0. The code entry every two-step form shares ─────────────────────────────────────────────── */

/**
 * The six boxes ([OtpCodeField]) for an authenticator code and — where one is also accepted — a
 * SEPARATE text box for a recovery code, reached by "Use a recovery code instead". The web's
 * `TwoStepCodeEntry`. Switching kind empties the box: half a code of one kind is not half the other.
 */
@Composable
fun TwoStepCodeEntry(
    useRecovery: Boolean,
    onUseRecoveryChange: (Boolean) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    allowRecovery: Boolean,
    enabled: Boolean,
    isError: Boolean,
    errorKey: Int,
    onComplete: ((String) -> Unit)? = null,
    totpLabel: String = "6-digit code from your authenticator app",
    totpHint: String? = null,
    autoFocus: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allowRecovery && useRecovery) {
            OutlinedTextField(
                value = code,
                onValueChange = { onCodeChange(it.take(20)) },
                label = { Text("Recovery code") },
                placeholder = { Text("xxxx-xxxx") },
                supportingText = { Text("One of the recovery codes you saved when you turned this on. Each one works once.") },
                singleLine = true,
                enabled = enabled,
                isError = isError,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FieldDisplayFontFamily),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OtpCodeField(
                value = code,
                onValueChange = onCodeChange,
                label = totpLabel,
                supportingText = totpHint,
                onComplete = onComplete,
                isError = isError,
                errorKey = errorKey,
                enabled = enabled,
                autoFocus = autoFocus,
            )
        }
        if (allowRecovery) {
            TextButton(
                enabled = enabled,
                onClick = {
                    onCodeChange("")
                    onUseRecoveryChange(!useRecovery)
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (useRecovery) "Use your authenticator app instead" else "Use a recovery code instead")
            }
        }
    }
}

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
    var errorKey by remember { mutableIntStateOf(0) }
    var dead by remember { mutableStateOf(false) }

    fun submit(entered: String) {
        if (busy || dead || entered.isBlank()) return
        if (!useRecovery && !OtpCode.isComplete(entered)) return
        scope.launch {
            busy = true
            error = null
            runCatching { repository.verifyTwoStep(challengeToken, entered) }
                .onSuccess { onSignedIn(it) }
                .onFailure { failure ->
                    code = ""
                    errorKey++
                    val message = failure.apiErrorMessage("That code didn't work. Try again.")
                    if ((failure as? HttpException)?.code() == 401 && message == CHALLENGE_ENDED_DETAIL) dead = true
                    error = message
                }
            busy = false
        }
    }

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
                    TwoStepCodeEntry(
                        useRecovery = useRecovery,
                        onUseRecoveryChange = { useRecovery = it; error = null },
                        code = code,
                        onCodeChange = { code = it },
                        allowRecovery = true,
                        enabled = !busy,
                        isError = error != null,
                        errorKey = errorKey,
                        onComplete = { submit(it) },
                        totpLabel = "6-digit code",
                        autoFocus = true,
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
                        enabled = !busy && (if (useRecovery) code.isNotBlank() else OtpCode.isComplete(code)),
                        onClick = { submit(code) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Checking…" else "Verify") }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Back to sign in") }
    }
}

/* ── 2. The account's own switch ──────────────────────────────────────────────────────────────── */

/**
 * What the "Two-step verification" switch shows — the web's `twoStepSwitchView` (lib/twoStepSwitch.ts),
 * row for row, pinned by `TwoStepSwitchTest`.
 *
 * THE RULE THE OWNER REPORTED BROKEN (2026-10-17): it reads On ONLY when the server says it is on —
 * `/enable` accepted a code. A setup in progress reads "Setting up…" and is not checked; a setup the
 * server still holds from an earlier visit reads Off with "start again"; never On, and never a request
 * for a code nobody saved.
 */
object TwoStepSwitch {
    enum class Phase { IDLE, SETUP, CODES, DISABLE, REGENERATE }

    data class View(val checked: Boolean, val statusLine: String)

    private val dayFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK)

    /** "17 Oct 2026" from the server's ISO instant, or null when it is not one. */
    fun onSince(iso: String?): String? = iso?.let { text ->
        runCatching { java.time.OffsetDateTime.parse(text).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate() }
            .recoverCatching { java.time.LocalDateTime.parse(text.removeSuffix("Z")).toLocalDate() }
            .getOrNull()
            ?.format(dayFormat)
    }

    fun recoveryCodesLeft(count: Int): String = when {
        count <= 0 -> "No recovery codes left"
        count == 1 -> "1 recovery code left"
        else -> "$count recovery codes left"
    }

    fun view(status: TwoFactorStatusDto?, phase: Phase): View {
        val enabled = status?.enabled == true
        return when {
            phase == Phase.SETUP -> View(false, "Setting up… Nothing is switched on until the code is checked.")
            phase == Phase.DISABLE && enabled -> View(true, "Enter a code to turn it off.")
            status == null -> View(false, "Loading…")
            enabled -> View(true, onLine(status))
            status.pending -> View(false, "Off · You started setting this up and did not finish. Switch it on to start again.")
            else -> View(false, "Off")
        }
    }

    private fun onLine(status: TwoFactorStatusDto): String =
        listOf(onSince(status.enabledAt)?.let { "On since $it" } ?: "On", recoveryCodesLeft(status.recoveryCodesRemaining))
            .joinToString(" · ")

    /**
     * Leaving setup DISCARDS the unconfirmed key: Cancel, switching it back off, Back, or leaving the
     * screen. NOT the app going to the background — the person is very likely in their authenticator
     * app reading the code. When they come back, the status is re-read; a setup the server has since
     * expired (15 minutes) drops back to Off.
     */
    enum class Leave { CANCEL, SWITCH_OFF, SCREEN_CLOSED, BACKGROUNDED }

    fun leavingDiscards(leave: Leave): Boolean = leave != Leave.BACKGROUNDED

    /** Back from the background in setup, the server no longer holds the key: setup is over. */
    fun setupExpired(phase: Phase, status: TwoFactorStatusDto?): Boolean =
        phase == Phase.SETUP && status != null && !status.enabled && !status.pending
}

private sealed interface TwoStepStep {
    val phase: TwoStepSwitch.Phase
    data object Idle : TwoStepStep { override val phase = TwoStepSwitch.Phase.IDLE }
    data class Setup(val setup: TwoFactorSetupDto) : TwoStepStep { override val phase = TwoStepSwitch.Phase.SETUP }
    data class Codes(val codes: List<String>) : TwoStepStep { override val phase = TwoStepSwitch.Phase.CODES }
    data object ConfirmDisable : TwoStepStep { override val phase = TwoStepSwitch.Phase.DISABLE }
    data object ConfirmRegenerate : TwoStepStep { override val phase = TwoStepSwitch.Phase.REGENERATE }
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
 * The card: an [AppToggle] labelled "Two-step verification" whose state is the SERVER's, plus whichever
 * step is open under it — setup (key, QR, six boxes), the recovery codes (shown once, until "I've saved
 * them"), or a code to turn it off / make new codes.
 */
@Composable
fun TwoStepVerificationCard(repository: WorkshopRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TwoFactorStatusDto?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<TwoStepStep>(TwoStepStep.Idle) }
    var code by remember { mutableStateOf("") }
    var useRecovery by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorKey by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val inSetup by rememberUpdatedState(step is TwoStepStep.Setup)

    suspend fun load() {
        runCatching { repository.twoFactorStatus() }
            .onSuccess { status = it; loadError = null }
            .onFailure { loadError = it.apiErrorMessage("Two-step verification could not be loaded.") }
    }

    LaunchedEffect(Unit) { load() }

    fun go(next: TwoStepStep) {
        step = next
        code = ""
        useRecovery = false
        error = null
    }

    // Back from the background (very likely from the authenticator app): re-read the status. A setup
    // the server has expired meanwhile is over — the switch drops to Off and says so.
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    load()
                    if (TwoStepSwitch.setupExpired(step.phase, status)) {
                        go(TwoStepStep.Idle)
                        notice = "Setup timed out. Nothing was switched on. Switch it on to start again."
                    }
                }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // Leaving the screen mid-setup discards the unconfirmed key — on a scope that outlives this card.
    DisposableEffect(Unit) {
        onDispose {
            if (inSetup && TwoStepSwitch.leavingDiscards(TwoStepSwitch.Leave.SCREEN_CLOSED)) {
                AppScope.io.launch { runCatching { repository.cancelTwoFactorSetup() } }
            }
        }
    }

    fun leaveSetup() {
        go(TwoStepStep.Idle)
        notice = "Setup cancelled. Two-step verification is still off."
        scope.launch {
            runCatching { repository.cancelTwoFactorSetup() }
            load()
        }
    }

    fun submit(entered: String) {
        if (busy || entered.isBlank()) return
        val needsSix = step !is TwoStepStep.ConfirmDisable || !useRecovery
        if (needsSix && !OtpCode.isComplete(entered)) return
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching {
                when (step) {
                    is TwoStepStep.Setup -> go(TwoStepStep.Codes(repository.enableTwoFactor(entered)))
                    TwoStepStep.ConfirmRegenerate -> go(TwoStepStep.Codes(repository.makeNewRecoveryCodes(entered)))
                    TwoStepStep.ConfirmDisable -> {
                        repository.disableTwoFactor(entered)
                        go(TwoStepStep.Idle)
                        notice = "Two-step verification is off."
                    }
                    else -> Unit
                }
                runCatching { repository.refreshUser() }
                load()
            }.onFailure {
                code = ""
                errorKey++
                error = it.apiErrorMessage("That code didn't work. Try again.")
            }
            busy = false
        }
    }

    fun startSetup() {
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching { repository.beginTwoFactorSetup() }
                .onSuccess { go(TwoStepStep.Setup(it)) }
                .onFailure { error = it.apiErrorMessage("Setup could not be started.") }
            busy = false
        }
    }

    val current = status
    val view = TwoStepSwitch.view(current, step.phase)

    PreferenceCard {
        PreferenceCardHeading(Icons.Filled.Security, "Two-step verification")
        AppToggle(
            label = "Two-step verification",
            description = "After you sign in, also enter a 6-digit code from an authenticator app on your phone, such as " +
                "Google Authenticator or Microsoft Authenticator.",
            status = view.statusLine,
            checked = view.checked,
            enabled = current != null,
            busy = busy,
            onCheckedChange = { on ->
                when {
                    on -> startSetup()
                    step is TwoStepStep.Setup -> leaveSetup()
                    current?.enabled == true -> go(TwoStepStep.ConfirmDisable)
                }
            }
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
        if (current == null && loadError != null) {
            OutlinedButton(onClick = { scope.launch { load() } }) { Text("Try again") }
        }

        when (val shown = step) {
            TwoStepStep.Idle -> if (current?.enabled == true) {
                if ((current?.recoveryCodesRemaining ?: 0) <= 2) {
                    Text(
                        "You are running out of recovery codes. Make new ones so you can still get in without your phone.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
                OutlinedButton(onClick = { go(TwoStepStep.ConfirmRegenerate) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Make new recovery codes")
                }
            }

            is TwoStepStep.Setup -> {
                BackHandler { leaveSetup() }
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
                Text(
                    "2. Enter the 6-digit code the app shows. Two-step verification switches on only when this code is checked.",
                    fontSize = 14.sp
                )
                OtpCodeField(
                    value = code,
                    onValueChange = { code = it },
                    label = "6-digit code",
                    supportingText = "Codes change every 30 seconds. Type the one on screen now.",
                    onComplete = { submit(it) },
                    isError = error != null,
                    errorKey = errorKey,
                    enabled = !busy,
                )
                Button(enabled = !busy && OtpCode.isComplete(code), onClick = { submit(code) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Checking…" else "Turn on")
                }
                TextButton(onClick = { leaveSetup() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
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
                Button(onClick = { go(TwoStepStep.Idle); notice = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("I've saved them")
                }
            }

            TwoStepStep.ConfirmDisable, TwoStepStep.ConfirmRegenerate -> {
                val disabling = shown == TwoStepStep.ConfirmDisable
                if (disabling) {
                    TwoStepCodeEntry(
                        useRecovery = useRecovery,
                        onUseRecoveryChange = { useRecovery = it; error = null },
                        code = code,
                        onCodeChange = { code = it },
                        allowRecovery = true,
                        enabled = !busy,
                        isError = error != null,
                        errorKey = errorKey,
                        onComplete = { submit(it) },
                        totpHint = "Enter the code your authenticator app shows now to turn two-step verification off.",
                        autoFocus = true,
                    )
                } else {
                    OtpCodeField(
                        value = code,
                        onValueChange = { code = it },
                        label = "6-digit code from your authenticator app",
                        supportingText = "Your old recovery codes will stop working.",
                        onComplete = { submit(it) },
                        isError = error != null,
                        errorKey = errorKey,
                        enabled = !busy,
                        autoFocus = true,
                    )
                }
                val ready = if (disabling && useRecovery) code.isNotBlank() else OtpCode.isComplete(code)
                Button(
                    enabled = !busy && ready,
                    onClick = { submit(code) },
                    colors = if (disabling) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Checking…" else if (disabling) "Turn off" else "Make new codes") }
                TextButton(onClick = { go(TwoStepStep.Idle) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}
