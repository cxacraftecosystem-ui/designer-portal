package com.designprototype.workshop.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.FirstPasswordProof
import com.designprototype.workshop.data.GoogleAuthClient
import com.designprototype.workshop.data.OidcAuthClient
import com.designprototype.workshop.data.OidcLoginRequest
import com.designprototype.workshop.data.ProviderConfirmationRequired
import com.designprototype.workshop.data.confirmProviderFor
import com.designprototype.workshop.data.TwoFactorCodeRequired
import com.designprototype.workshop.data.FIRST_PASSWORD_CODE_MISSING
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.accountHasPassword
import com.designprototype.workshop.data.apiErrorMessage
import kotlinx.coroutines.launch

/*
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * GETTING BACK IN, ON THE HANDSET
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Three surfaces, the web's three, with the words in ui/AccountRecoveryCopy.kt:
 *
 *   1. [ForgotPasswordScreen] — drawn by `MainActivity` in place of the sign-in card. One box, one
 *      button, and the server's one answer printed as it is: the same for an address with an account
 *      and one without, so nothing on this screen can branch on which it was.
 *   2. [AccountPasswordScreen] — Settings → Password. Changes the password, or sets a first one for
 *      an account that signs in with Google, Microsoft or Yahoo.
 *   3. [HelpThemBackInActions] — on Manage users, beside the existing "Issue a set-password link" and
 *      "Turn off two-step verification": e-mail them a link, set a temporary password, sign them out
 *      of every device.
 *
 * NONE OF THESE MINTS A SESSION. A link or the forgotten-password e-mail sets a password; the person
 * then signs in and meets the two-step code exactly as any sign-in does.
 */

@Composable
fun ForgotPasswordScreen(
    repository: WorkshopRepository,
    onBack: () -> Unit,
    /** The address already typed on the sign-in card, if any — saves typing it twice. */
    initialEmail: String = "",
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail.trim()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            FORGOT_PASSWORD_HEADING,
            display = true,
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(FORGOT_PASSWORD_INTRO, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 19.sp)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    label = { Text("Email address") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                sent?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        val problem = forgotPasswordEmailProblem(email)
                        if (problem != null) {
                            error = problem
                            return@Button
                        }
                        scope.launch {
                            busy = true
                            error = null
                            sent = null
                            runCatching { repository.forgotPassword(email) }
                                .onSuccess { sent = forgotPasswordShown(it) }
                                .onFailure {
                                    error = it.apiErrorMessage("That request did not work. Check the address and try again.")
                                }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Sending…" else "Email me a link")
                }
                Text(FORGOT_PASSWORD_TWO_STEP_NOTE, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
                Text(FORGOT_PASSWORD_NO_EMAIL_NOTE, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
            Text("Back to sign in", fontSize = 13.sp)
        }
    }
}

/** Settings → Password. Its own TopAppBar, like the two-step screen beside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPasswordScreen(
    repository: WorkshopRepository,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password") },
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
            item { AccountPasswordCard(repository) }
        }
    }
}

@Composable
fun AccountPasswordCard(repository: WorkshopRepository) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(repository.cachedUser()) }
    // Re-read once, so an account that gained a password on the web is not offered "Set a password".
    LaunchedEffect(Unit) {
        runCatching { repository.refreshUser() }.onSuccess { user = it }
    }
    val hasPassword = accountHasPassword(user)
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val askCode = firstPasswordNeedsCode(hasPassword, user)
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    // ── THE PROVIDER PROOF A FIRST PASSWORD TRAVELS WITH (2026-10-17) ─────────────────────────
    // The sign-in code paths the sign-in screen uses, run again here: Credential Manager for
    // Google, AppAuth in a browser tab for Microsoft and Yahoo. Built lazily from the activity,
    // so an account that already has a password never binds a browser service.
    val activity = LocalContext.current.componentActivity()
    val oidcClient = remember(activity) { activity?.let { lazy { OidcAuthClient(it) } } }
    // Saveable: the browser tab can outlive this activity (rotation, or the process reclaimed while
    // it is open), and the nonce is the one thing the result does not carry back.
    var pendingNonce by rememberSaveable { mutableStateOf<String?>(null) }
    // The proof that went with a send refused only for its two-step code. Sent again with the
    // corrected code: the server remembers that it already verified, so no second provider trip.
    var heldProof by remember { mutableStateOf<FirstPasswordProof?>(null) }
    val confirmWith = confirmProviderFor(user)

    suspend fun send(proof: FirstPasswordProof?) {
        busy = true
        error = null
        val hadPassword = hasPassword
        runCatching {
            repository.saveOwnPassword(if (hadPassword) current else null, next, if (askCode) code else null, proof)
        }
            .onSuccess {
                heldProof = null
                saved = passwordSavedLine(hadPassword)
                current = ""
                next = ""
                confirm = ""
                code = ""
                user = repository.cachedUser()
            }
            .onFailure { failure ->
                if (failure is ProviderConfirmationRequired) {
                    // Stale, or for another mailbox: tapping Set password asks the provider again.
                    heldProof = null
                    error = failure.message
                } else if (failure is TwoFactorCodeRequired) {
                    // Only the code is emptied: the passwords and the provider proof were not refused.
                    heldProof = proof
                    error = failure.message
                    code = ""
                } else {
                    error = failure.apiErrorMessage(PASSWORD_CHANGE_NOT_SENT)
                }
            }
        busy = false
    }

    val oidcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val rawNonce = pendingNonce
        pendingNonce = null
        val client = oidcClient?.value
        if (rawNonce == null || client == null) {
            error = firstPasswordConfirmCancelled(confirmWith)
            busy = false
            return@rememberLauncherForActivityResult
        }
        when (val outcome = client.finish(result.data)) {
            is OidcAuthClient.Outcome.Failed -> {
                error = firstPasswordConfirmCancelled(confirmWith)
                busy = false
            }
            is OidcAuthClient.Outcome.Code -> scope.launch {
                send(
                    FirstPasswordProof.Oidc(
                        OidcLoginRequest(
                            oidcProvider = confirmWith,
                            oidcCode = outcome.code,
                            oidcCodeVerifier = outcome.codeVerifier,
                            oidcRedirectUri = outcome.redirectUri,
                            oidcNonce = rawNonce,
                        )
                    )
                )
            }
        }
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(passwordCardTitle(hasPassword), display = true, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(passwordCardIntro(hasPassword, user), color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 19.sp)
            if (hasPassword) {
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it; error = null; saved = null },
                    label = { Text("Current password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = passwordTransformation(reveal),
                    trailingIcon = { PasswordRevealIcon(reveal, { reveal = !reveal }) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = next,
                onValueChange = { next = it; error = null; saved = null },
                label = { Text("New password") },
                supportingText = { Text(passwordRuleLine()) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = passwordTransformation(reveal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = null; saved = null },
                label = { Text("Type it again") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = passwordTransformation(reveal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (askCode) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null; saved = null },
                    label = { Text(FIRST_PASSWORD_CODE_LABEL) },
                    supportingText = { Text(FIRST_PASSWORD_CODE_NOTE) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            saved?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
            if (!hasPassword) {
                Text(firstPasswordConfirmNote(confirmWith), color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Button(
                enabled = !busy && (!hasPassword || current.isNotEmpty()),
                onClick = {
                    val problem = newPasswordRefusal(next, confirm, if (hasPassword) current else "")
                    if (problem != null) {
                        error = problem
                        return@Button
                    }
                    if (askCode && code.isBlank()) {
                        error = FIRST_PASSWORD_CODE_MISSING
                        return@Button
                    }
                    if (hasPassword) {
                        scope.launch { send(null) }
                        return@Button
                    }
                    // A first password: confirm with the provider first, then send with its proof.
                    heldProof?.let { held ->
                        scope.launch { send(held) }
                        return@Button
                    }
                    if (activity == null) {
                        error = firstPasswordConfirmCancelled(confirmWith)
                        return@Button
                    }
                    if (confirmWith == "GOOGLE") {
                        scope.launch {
                            busy = true
                            error = null
                            runCatching { GoogleAuthClient(activity).getIdToken() }
                                .onSuccess { send(FirstPasswordProof.Google(it)) }
                                .onFailure {
                                    error = firstPasswordConfirmCancelled(confirmWith)
                                    busy = false
                                }
                        }
                        return@Button
                    }
                    val client = oidcClient?.value
                    val provider = client?.providers?.firstOrNull { it.id.name == confirmWith }
                    if (client == null || provider == null) {
                        error = firstPasswordConfirmCancelled(confirmWith)
                        return@Button
                    }
                    runCatching { client.start(provider) }
                        .onSuccess { started ->
                            busy = true
                            error = null
                            pendingNonce = started.rawNonce
                            oidcLauncher.launch(started.intent)
                        }
                        .onFailure { error = firstPasswordConfirmCancelled(confirmWith) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Saving…" else if (hasPassword) "Change password" else "Set password")
            }
        }
    }
}

/**
 * The three "help them back in" actions this handset lacked, for one row of Manage users. Drawn only
 * when the caller has already decided the administrator may manage [target] — the same test the
 * link and two-step buttons beside it use, which mirrors the server's `assert_may_reset_credentials`.
 *
 * [onChanged] hands back the row as the server now has it (null when nothing came back), so the list
 * can refresh; [onError] carries the server's own sentence.
 */
@Composable
fun HelpThemBackInActions(
    repository: WorkshopRepository,
    target: UserDto,
    onChanged: (UserDto?) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mailAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mailAvailable = repository.mailAvailable() }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var temporaryOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    HorizontalDivider()
    Text(HELP_THEM_BACK_IN_HEADING, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    if (emailLinkOffered(target, mailAvailable)) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    notice = null
                    runCatching { repository.emailPasswordLink(target.id) }
                        .onSuccess { notice = emailedLinkLine(target.name, target.email) }
                        .onFailure { onError(it.apiErrorMessage("Unable to email a password link")) }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Email them a password link") }
    }
    OutlinedButton(
        enabled = !busy,
        onClick = { temporaryOpen = true; notice = null },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Set a temporary password") }
    OutlinedButton(
        enabled = !busy,
        onClick = { confirmSignOut = true; notice = null },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Sign out of every device") }
    notice?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }

    if (temporaryOpen) {
        TemporaryPasswordDialog(
            name = target.name,
            busy = busy,
            onDismiss = { temporaryOpen = false },
            onSubmit = { password ->
                scope.launch {
                    busy = true
                    runCatching { repository.setTemporaryPassword(target.id, password) }
                        .onSuccess {
                            temporaryOpen = false
                            notice = temporaryPasswordSetLine(target.name)
                            onChanged(it)
                        }
                        .onFailure { onError(it.apiErrorMessage("Unable to set a temporary password")) }
                    busy = false
                }
            }
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmSignOut = false },
            title = { Text(signOutEverywhereTitle(target.name)) },
            text = { Text(signOutEverywhereBody(target.name)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmSignOut = false
                        scope.launch {
                            busy = true
                            runCatching { repository.signOutEverywhere(target.id) }
                                .onSuccess {
                                    notice = signedOutEverywhereLine(target.name)
                                    onChanged(it)
                                }
                                .onFailure { onError(it.apiErrorMessage("Unable to sign them out")) }
                            busy = false
                        }
                    }
                ) { Text("Sign out everywhere") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmSignOut = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TemporaryPasswordDialog(
    name: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    // Never trimmed, and never echoed anywhere once the dialog closes: a password typed for somebody
    // else is a shared secret, and this box is the one place on the handset it exists.
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Set a temporary password for $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(temporaryPasswordIntro(name), color = MaterialTheme.field.muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; problem = null },
                    label = { Text("Temporary password") },
                    supportingText = { Text(problem ?: passwordRuleLine()) },
                    isError = problem != null,
                    singleLine = true,
                    visualTransformation = passwordTransformation(reveal),
                    trailingIcon = { PasswordRevealIcon(reveal, { reveal = !reveal }) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val refusal = temporaryPasswordProblem(password)
                    if (refusal != null) problem = refusal else onSubmit(password)
                }
            ) { Text(if (busy) "Saving…" else "Set password") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The [ComponentActivity] this composition runs in, or null outside one (previews, tests). */
private tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is Activity -> null
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}
