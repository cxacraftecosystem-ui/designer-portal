package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.AiCatalogueDto
import com.designprototype.workshop.data.AiProviderDto
import com.designprototype.workshop.data.UserAiKeyDto
import com.designprototype.workshop.data.WorkshopRepository
import kotlinx.coroutines.launch

/**
 * **A DESIGNER'S OWN PROVIDER KEYS**, on the handset. The twin of the web's `MyAiKeysPanel`.
 *
 * ── WHAT THIS IS, AND HOW IT DIFFERS FROM [ApiKeysScreen] ─────────────────────────────────────
 *
 * [ApiKeysScreen] manages the DEPLOYMENT's keys and is reachable only from the admin hub, by a
 * master admin: those are the organisation's credentials, on the organisation's bill, and that
 * screen has a reveal control because a master admin sometimes has to compare a stored key with a
 * provider dashboard.
 *
 * This screen is the opposite of that in every respect. It is a PERSONAL setting, open to every
 * signed-in account, and it acts only on the caller's own rows — the server takes the owner from
 * the token, so there is no request this screen could make that touches somebody else's key. And
 * **there is no reveal control here and there must never be one**: nobody, administrator included,
 * has any business reading another person's personal credential. The last four characters are the
 * most this app will ever show, which is enough to tell two of your own keys apart and no more.
 *
 * ── THE CAPABILITY LINE IS LOAD-BEARING ───────────────────────────────────────────────────────
 *
 * Each model prints what it can actually be used for, and a provider that cannot do something says
 * so in words — Claude cannot transcribe audio, because no Claude model accepts a sound file. The
 * server enforces that rule regardless; this screen is where a person can see it BEFORE choosing.
 * Without it, somebody pastes a Claude key believing their recordings are now on their own account
 * and discovers otherwise from a bill that never arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAiKeysScreen(
    repository: WorkshopRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var catalogue by remember { mutableStateOf<AiCatalogueDto?>(null) }
    var keys by remember { mutableStateOf<List<UserAiKeyDto>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        loading = true
        loadError = null
        runCatching {
            catalogue = repository.aiProviders()
            keys = repository.myAiKeys()
        }.onFailure { loadError = it.message ?: "Could not load your AI keys." }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My AI keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            loadError != null -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text(loadError!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { scope.launch { reload() } }) { Text("Try again") }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column {
                        Text(
                            // "summarising" was in this list and is not any more: nothing in this
                            // app can ask for a summary — see TASKS_NOTHING_CAN_ASK_FOR below.
                            // Naming a job here that a designer then goes hunting for and cannot
                            // find is the same lie as printing it on a model's row, told earlier
                            // and to somebody who has not yet decided whether to hand over a key.
                            "Bring your own key and the AI work you ask for — proofreading, " +
                                "expanding, translating, transcribing and photo descriptions — " +
                                "runs on your account with your provider, at your choice of " +
                                "model, and is billed to you.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Leave this empty and everything works exactly as it does now, on the " +
                                "key this server is set up with. Your key is stored encrypted, is " +
                                "used only for work you personally ask for, and is never shown to " +
                                "anyone — including administrators.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(catalogue?.providers.orEmpty(), key = { it.provider }) { provider ->
                    val state = keys.firstOrNull { it.provider == provider.provider }
                    if (state != null) {
                        ProviderKeyCard(
                            provider = provider,
                            state = state,
                            pricesCheckedOn = catalogue?.pricesCheckedOn.orEmpty(),
                            onSave = { key, model ->
                                scope.launch {
                                    runCatching { repository.setMyAiKey(provider.provider, key, model) }
                                        .onSuccess { updated ->
                                            keys = keys.map { if (it.provider == updated.provider) updated else it }
                                        }
                                }
                            },
                            onTest = {
                                scope.launch {
                                    runCatching { repository.testMyAiKey(provider.provider) }
                                        .onSuccess { updated ->
                                            keys = keys.map { if (it.provider == updated.provider) updated else it }
                                        }
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    runCatching { repository.deleteMyAiKey(provider.provider) }
                                        .onSuccess { updated ->
                                            keys = keys.map { if (it.provider == updated.provider) updated else it }
                                        }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** The designer-facing name of each job. The server sends the enum; this is the only place on the
 *  handset that turns it into words, so the names read the same on every row. Deliberately a
 *  COMPLETE mirror of `ai_providers.AiTask` — what a row is allowed to SAY is decided by
 *  [TASKS_NOTHING_CAN_ASK_FOR] below and not by leaving a name out of this map, which would print a
 *  raw "SUMMARISE" at a designer instead of hiding it. */
private val TASK_LABELS = mapOf(
    "PROOFREAD" to "Proofread",
    "EXPAND" to "Expand",
    "SUMMARISE" to "Summarise",
    "TRANSLATE" to "Translate",
    "TRANSCRIBE" to "Transcribe audio",
    "CAPTION" to "Describe photos"
)

/**
 * JOBS THE SERVER'S CATALOGUE LISTS THAT NOTHING IN THIS PRODUCT CAN ACTUALLY ASK FOR.
 *
 * **`SUMMARISE` IS ADVERTISED AND UNREACHABLE, AND THIS SCREEN WAS ONE OF THE TWO PLACES THE CLAIM
 * WAS MADE.** `AiTask.SUMMARISE` is in the enum and in `TEXT_TASKS`, so every chat model in every
 * family carries it and every "Used for:" line printed it. There is no way to run one: `ai_verbs.Verb`
 * has five members — PROOFREAD, EXPAND, TRANSLATE, CAPTION, SUBTITLES — matched one-for-one by five
 * routes (`POST /{workshop_id}/ai-layers/{proofread,expand,translate,caption,subtitles}`), and
 * `DwAiVerb` in `data/DwAiVerbs.kt` names exactly the same five. SUMMARISE is in none of the three.
 * `ai.summarise_text` does exist, with its own system prompt and a `LayerKind.SUMMARY` that has a
 * placement law — and NOTHING CALLS IT: its only reference anywhere in the repository is the
 * `summarize_text` alias on the line beneath its own definition.
 *
 * So "Used for: … Summarise …" told a designer that their own key and their own money would be spent
 * on a job they cannot ask this app to do — a small lie in exactly the place a person is deciding
 * whether to hand over a credential, which is the worst place in the app to keep one.
 *
 * **WHY HIDDEN RATHER THAN WIRED, WHICH IS A DECISION AND NOT AN OMISSION.** Wiring it is not one
 * missing route: a verb here is a `Verb` member, a `LayerKind`, a rung in `ALLOWED_PARENTS`, an
 * acceptance step, an annexure section in the report, cap accounting, and this whole surface again on
 * the handset — where a release ships to a fleet that may be offline for a fortnight. Whether a
 * designer should summarise a transcript, and what a SUMMARY layer means underneath a report somebody
 * signs, is a product decision with an owner and not something to settle as a side effect of
 * correcting a caption. Hiding is reversible in one line and can mislead nobody; shipping a
 * half-wired fifth verb is neither.
 *
 * **FILTERED HERE RATHER THAN CUT FROM THE CATALOGUE** because `AiModel.tasks` is an honest statement
 * about the MODEL — GPT-4o really can summarise — while this line is a statement about what THIS
 * DEPLOYMENT WILL SPEND YOUR KEY ON. Different claims; only the second was wrong.
 *
 * WHEN A SUMMARISE VERB IS WIRED, DELETE THE ENTRY AND NOTHING ELSE — the label above is already
 * there, and the identical one-line deletion is waiting in the web's `MyAiKeysPanel.tsx`, which
 * carries the same set for the same reason.
 *
 * True as of «2026-08-27»; re-check with
 * «grep -rn "summarise_text\|summarize_text" backend/app» (only the definition and its alias, both
 * in `services/ai.py`).
 */
private val TASKS_NOTHING_CAN_ASK_FOR = setOf("SUMMARISE")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderKeyCard(
    provider: AiProviderDto,
    state: UserAiKeyDto,
    pricesCheckedOn: String,
    onSave: (String?, String) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    var typedKey by remember(state.provider) { mutableStateOf("") }
    // Per-composition only, and re-masked whenever the provider changes — a reveal that
    // survived switching provider would put one key on screen under another one's label.
    var revealKey by remember(state.provider) { mutableStateOf(false) }
    var chosenModel by remember(state.provider, state.model) { mutableStateOf(state.model) }
    var howToOpen by remember { mutableStateOf(false) }

    val chosen = provider.models.firstOrNull { it.id == chosenModel }
    val transcribes = provider.models.any { "TRANSCRIBE" in it.tasks }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        state.unreadable -> "Paste it again"
                        !state.configured -> "Not set"
                        state.lastStatus == "OK" -> "Working"
                        state.lastStatus == "FAILED" -> "Not working"
                        else -> "Untested"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        state.unreadable || state.lastStatus == "FAILED" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (state.hint != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Ends …${state.hint}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.unreadable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This key can no longer be decrypted — the server's encryption key changed " +
                        "after it was saved. Paste it again to fix it. Nothing is using it meanwhile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (!state.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (!state.modelKnown) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The model saved here is not one this app offers any more. Pick another below " +
                        "— until you do, your key runs whichever current model fits each job.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = typedKey,
                onValueChange = { typedKey = it },
                label = { Text(if (state.configured) "Replace the key" else "Paste your key") },
                placeholder = { Text(provider.keyPrefix?.let { "$it…" } ?: "Your API key") },
                singleLine = true,
                // ── THE COMMENT ABOVE THIS LINE SAID "MUST NEVER ECHO", AND THAT RULE WAS
                //    CHANGED ON 2026-08-30 ──────────────────────────────────────────────────
                //
                // The old argument — a key typed in a courtyard is typed in front of whoever is
                // standing there — is real and is why the box is still MASKED BY DEFAULT and why
                // the reveal is never persisted (ui/PasswordReveal.kt). What it got wrong is
                // treating "never" as the safe direction. The person cannot see what they typed
                // at all, so a key mistyped on a soft keyboard is saved wrong and reported as
                // "the provider rejected this" much later, and the fix is to paste it again
                // blind. The reveal is a deliberate press, by the person holding the phone, who
                // is the one who knows who is standing behind them.
                visualTransformation = passwordTransformation(revealKey),
                trailingIcon = {
                    PasswordRevealIcon(
                        revealed = revealKey,
                        onToggle = { revealKey = !revealKey },
                        noun = "key"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            /*
              THIS WAS THE APP'S ONLY `ExposedDropdownMenuBox` — DROPDOWN_DESIGN §3.4, closed.

              ── WHAT WAS WRONG WITH IT, WHICH IS NOT THAT IT LOOKED DIFFERENT ────────────────

              It sat outside the shared picker entirely: no [SEARCH_THRESHOLD], no [SelectOption], and
              — the part §3.4 is actually about — no way to say which of the four empty states it was
              in. `provider.models` is a constant compiled into this APK, so today it cannot be
              empty and none of those sentences would ever fire; that is a fact about the CURRENT
              vocabulary and not about the control, and §3.4's rule is that a control which cannot
              say which state it is in reads as "there are none" the day it can be empty. Routing it
              through the shared field means the day these models come off the server — which is
              exactly what §3.1's `workshopLevelOptions` pattern anticipates — the sentences are
              already there.

              It also gains the things every other picker on this handset has and this one did not:
              the sheet above eight options (there are more than eight models on some providers), the
              "N options / M of N match" live region, IME commit, and a trigger whose accessible name
              is the LABEL plus the value rather than the value alone.

              CLASS (a), so [BUNDLED_LIST_HAS_NO_SENTENCE] and nothing further: a vocabulary compiled
              into the APK is always answerable and §3.1 gives it no sentence to say.
            */
            SearchableSelectField(
                label = "Model",
                options = provider.models.map { model ->
                    SelectOption(
                        value = model.id,
                        // The "(recommended)" marker stays in the LABEL rather than moving to `hint`.
                        // It is not a second fact that tells two models apart; it is part of how this
                        // screen names one of them, and `hint` is drawn on its own line.
                        label = model.label + if (model.id == provider.defaultModel) " (recommended)" else ""
                    )
                },
                selectedValue = chosenModel,
                // A model must always be chosen; the "not one this app offers any more" case is
                // reported by the sentence above this box, not by an empty row inside it.
                includeNone = false,
                emptyMessage = BUNDLED_LIST_HAS_NO_SENTENCE,
                onSelect = { chosenModel = it }
            )

            if (chosen != null) {
                Spacer(Modifier.height(6.dp))
                Text(chosen.note, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Used for: " + chosen.tasks
                        .filterNot { it in TASKS_NOTHING_CAN_ASK_FOR }
                        .joinToString(" · ") { TASK_LABELS[it] ?: it }
                        .plus("."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val input = chosen.inputPricePerMTok
                val output = chosen.outputPricePerMTok
                if (input != null && output != null) {
                    // The date travels with the figure, every time. See AiCatalogueDto.
                    Text(
                        "About \$$input in / \$$output out per million words-ish, checked $pricesCheckedOn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!transcribes) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${provider.label} cannot transcribe audio — none of its models accepts a " +
                        "sound file — so recordings keep using whatever this server is set up " +
                        "with, whatever you save here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        onSave(typedKey.takeIf { it.isNotBlank() }, chosenModel)
                        typedKey = ""
                    },
                    enabled = typedKey.isNotBlank() || chosenModel != state.model
                ) { Text("Save") }
                OutlinedButton(onClick = onTest, enabled = state.configured) { Text("Test") }
                if (state.configured || state.unreadable) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            TextButton(onClick = { howToOpen = !howToOpen }, modifier = Modifier.fillMaxWidth()) {
                Text("How to get a ${provider.label} key")
                Spacer(Modifier.weight(1f))
                Icon(
                    if (howToOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
            if (howToOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    provider.howTo.forEachIndexed { index, step ->
                        Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    // The URLs are printed rather than opened, on purpose: getting a key means
                    // signing in to a provider console and copying a secret, which is a laptop job.
                    // A tap that dropped somebody into a mobile browser to do it would be inviting
                    // them to paste a credential into whatever else that browser has open.
                    Text(
                        "Keys page: ${provider.consoleUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Current prices: ${provider.pricingUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
