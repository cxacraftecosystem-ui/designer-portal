package com.designprototype.workshop.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.DarkMode
// The walkthrough's own glyph, and it must stay this one: `FIELD_NAV_ITEMS` gives the Walkthrough
// row `Icons.Filled.Explore`, and a settings row that led to the same place behind a different
// picture would be a second sign on one door.
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.designprototype.workshop.data.DW_TIER1_CATALOGUE
import com.designprototype.workshop.data.dwAsrInstalledModelIds
import com.designprototype.workshop.data.dwPackStates
import com.designprototype.workshop.data.dwSpeechSummaryLine
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.PreferencesDto
import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import com.designprototype.workshop.ui.designworkshop.DwAsrModelRun
import com.designprototype.workshop.ui.designworkshop.rememberDwLanguagePacks
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Appearance + accessibility — this ACCOUNT's own two settings, mirroring the pair of cards the web
 * shows every signed-in user on /settings (frontend/components/settings/PersonalSettingsCards.tsx).
 *
 * The contract is four values (theme, reduceMotion, largerText, highContrast) and it lives in three
 * places that must agree, exactly as on the web (frontend/lib/preferences.ts):
 *
 *   - [AppPreferencesStore] (SharedPreferences), so the choice applies on the very first frame,
 *     before the network — the Android twin of localStorage + the pre-hydration boot script;
 *   - the running composition, via [LocalAppPreferences] / the `darkTheme` argument of
 *     [DesignWorkshopTheme];
 *   - GET/PUT /preferences/me, so the choice follows the account onto another device.
 *
 * Order of authority is the same as the web's: the stored value paints first, the server row lands
 * last and only corrects it. An empty `{}` from the GET (decoded as `null` by
 * WorkshopRepository.myPreferences) means "this account has no opinion yet" — seed the server from this
 * device rather than snapping the user back to the defaults.
 */

// ---------------------------------------------------------------------------------------------
// The contract
// ---------------------------------------------------------------------------------------------

/** `theme` — follow the device. */
const val THEME_SYSTEM: String = "system"

/** `theme` — always the paper-white canvas. */
const val THEME_LIGHT: String = "light"

/** `theme` — always the purple-tinted dark surfaces. */
const val THEME_DARK: String = "dark"

/** The only three values PUT /preferences/me accepts; anything else is a 422. */
val THEME_CHOICES: List<String> = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

/**
 * How much "Larger text" scales type by: 112.5%, the same `font-size` bump
 * `:root[data-larger-text="true"]` applies in frontend/app/globals.css. Both clients therefore grow
 * by the same amount rather than merely both "getting bigger".
 */
const val LARGER_TEXT_SCALE: Float = 1.125f

/**
 * One account's appearance + accessibility choices — the whole `/preferences/me` contract, plus the
 * two derived values the rest of the app needs.
 *
 * [theme] is the CHOICE (`system` included), never the resolved light/dark; call [resolveDarkTheme]
 * for that. The default is what a device with no stored row and no server row uses.
 */
@Immutable
data class AppPreferences(
    val theme: String = THEME_SYSTEM,
    /** Force reduced motion. ORs with the OS setting; it can never switch the OS preference off. */
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
) {
    /**
     * Multiplier for `Density.fontScale`. 1f normally, [LARGER_TEXT_SCALE] when "Larger text" is on.
     * [ProvideAppPreferences] applies it; nothing else should have to.
     */
    val fontScale: Float get() = if (largerText) LARGER_TEXT_SCALE else 1f

    companion object {
        /** `system` + everything off — the state before the device or the account has an opinion. */
        val Default: AppPreferences = AppPreferences()
    }
}

/**
 * Resolve a theme CHOICE against the device to the boolean [DesignWorkshopTheme] wants.
 *
 * Deliberately not `@Composable` so it can be called from an Activity, a store read, or a test —
 * pass `isSystemInDarkTheme()` (or `Configuration.uiMode`) as [systemDark] at the call site.
 */
fun resolveDarkTheme(theme: String, systemDark: Boolean): Boolean = when (theme) {
    THEME_DARK -> true
    THEME_LIGHT -> false
    else -> systemDark
}

/** Coerce anything (a stale store value, an older server row) into a theme the API will accept. */
fun normalizeThemeChoice(value: String?): String =
    if (value != null && value in THEME_CHOICES) value else THEME_SYSTEM

/** The server row as the app's own holder. */
fun PreferencesDto.toAppPreferences(): AppPreferences = AppPreferences(
    theme = normalizeThemeChoice(theme),
    reducedMotion = reducedMotion,
    largerText = largerText,
    highContrast = highContrast
)

// ---------------------------------------------------------------------------------------------
// Device-local copy — so the choice applies before the network (or even login) wakes up
// ---------------------------------------------------------------------------------------------

/**
 * The last-applied preferences, on this device.
 *
 * Read it SYNCHRONOUSLY in `MainActivity.onCreate` before `setContent` — that is what stops the app
 * flashing light before the account's dark choice arrives, and it is the only reason this exists
 * separately from the server row. Its own file, so signing out (which clears
 * `field_repository_auth`) does not throw the theme away.
 */
class AppPreferencesStore(context: Context) {
    // Pre-rebrand name, kept deliberately. A SharedPreferences name is the file name of an XML
    // document that already exists on every installed device, and asking for a different one hands
    // back an empty file instead of failing — so renaming it would not migrate a single stored
    // preference, it would silently reset every user's theme and reduced-motion choice on update.
    // The full argument is written out in TokenStore.kt, where the cost is far higher.
    private val store = context.applicationContext
        .getSharedPreferences("field_repository_preferences", Context.MODE_PRIVATE)

    fun read(): AppPreferences = AppPreferences(
        theme = normalizeThemeChoice(store.getString(KEY_THEME, null)),
        reducedMotion = store.getBoolean(KEY_REDUCED_MOTION, false),
        largerText = store.getBoolean(KEY_LARGER_TEXT, false),
        highContrast = store.getBoolean(KEY_HIGH_CONTRAST, false)
    )

    fun write(preferences: AppPreferences) {
        store.edit()
            .putString(KEY_THEME, normalizeThemeChoice(preferences.theme))
            .putBoolean(KEY_REDUCED_MOTION, preferences.reducedMotion)
            .putBoolean(KEY_LARGER_TEXT, preferences.largerText)
            .putBoolean(KEY_HIGH_CONTRAST, preferences.highContrast)
            .apply()
    }

    /** Forget this device's copy. Sign-out does NOT need this — the look is a device courtesy. */
    fun clear() {
        store.edit().clear().apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_REDUCED_MOTION = "reducedMotion"
        const val KEY_LARGER_TEXT = "largerText"
        const val KEY_HIGH_CONTRAST = "highContrast"
    }
}

/**
 * Reconcile this device's [local] copy with the account, once, after sign-in.
 *
 * Returns what the app should now apply: the server row when there is one, otherwise [local] —
 * having first seeded the account with it, so the look this device already had is what follows the
 * user to their next device. Writes the result to [store] when one is given. Never throws: a
 * failure leaves [local] applied and is silent, exactly like the web provider's `.catch`.
 */
suspend fun syncAppPreferences(
    repository: WorkshopRepository,
    local: AppPreferences,
    store: AppPreferencesStore? = null
): AppPreferences {
    val remote = runCatching { repository.myPreferences() }.getOrNull()
    if (remote == null) {
        // Either "no saved row yet" or an unreachable server. Seeding is safe in both cases: it is
        // the same whole-object PUT every toggle makes, and a failure changes nothing.
        runCatching { repository.savePreferences(local.toBody()) }
        return local
    }
    val resolved = remote.toAppPreferences()
    store?.write(resolved)
    return resolved
}

/** The whole-object shape PUT /preferences/me wants. Kept private: screens go through the helpers. */
private fun AppPreferences.toBody(): PreferencesDto = PreferencesDto(
    theme = normalizeThemeChoice(theme),
    reducedMotion = reducedMotion,
    largerText = largerText,
    highContrast = highContrast
)

// ---------------------------------------------------------------------------------------------
// Reading the choices from anywhere in the tree
// ---------------------------------------------------------------------------------------------

/**
 * The preferences in force. Provided by [ProvideAppPreferences]; read it wherever a screen needs to
 * skip an animation ([AppPreferences.reducedMotion]) or thicken a border
 * ([AppPreferences.highContrast]).
 */
val LocalAppPreferences = staticCompositionLocalOf { AppPreferences.Default }

/**
 * Publish [preferences] to the tree and apply "Larger text" to it.
 *
 * Scaling `Density.fontScale` is the Compose equivalent of the web bumping the root `font-size`:
 * every `sp` in the app grows by [LARGER_TEXT_SCALE] and every `dp` stays put, so type gets bigger
 * without the layout breaking. Multiplying the INHERITED scale (rather than assigning) keeps the
 * user's own Android font-size setting intact — this only ever adds to it.
 *
 * Wrap the app content with it, inside [DesignWorkshopTheme].
 */
@Composable
fun ProvideAppPreferences(preferences: AppPreferences, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val density = remember(base, preferences.fontScale) {
        if (preferences.fontScale == 1f) base else Density(base.density, base.fontScale * preferences.fontScale)
    }
    CompositionLocalProvider(
        LocalAppPreferences provides preferences,
        LocalDensity provides density,
        content = content
    )
}

// ---------------------------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------------------------

private data class ThemeOption(val value: String, val label: String, val helper: String, val icon: ImageVector)

/** Wording verbatim from THEME_OPTIONS in frontend/components/settings/PersonalSettingsCards.tsx. */
private val THEME_OPTIONS = listOf(
    ThemeOption(
        value = THEME_SYSTEM,
        label = "Match my device",
        helper = "Follows your system's light/dark setting, and switches with it.",
        icon = Icons.Filled.PhoneAndroid
    ),
    ThemeOption(
        value = THEME_LIGHT,
        label = "Light",
        helper = "The default paper-white canvas.",
        icon = Icons.Filled.LightMode
    ),
    ThemeOption(
        value = THEME_DARK,
        label = "Dark",
        helper = "Purple-tinted dark surfaces for low light.",
        icon = Icons.Filled.DarkMode
    )
)

private data class AccessibilityOption(
    val label: String,
    val helper: String,
    val read: (AppPreferences) -> Boolean,
    val write: (AppPreferences, Boolean) -> AppPreferences
)

/**
 * Wording verbatim from ACCESSIBILITY_OPTIONS on the web, with ONE edit: the high-contrast helper
 * drops the web's "and a thicker keyboard focus ring" clause, since a phone has no keyboard focus
 * ring to thicken. Every label is unchanged.
 */
private val ACCESSIBILITY_OPTIONS = listOf(
    AccessibilityOption(
        label = "Reduce motion",
        helper = "Stops animations and transitions. Your device's own reduce-motion setting is always " +
            "honoured too — this only ever adds to it.",
        read = { it.reducedMotion },
        write = { preferences, next -> preferences.copy(reducedMotion = next) }
    ),
    AccessibilityOption(
        label = "Larger text",
        helper = "Scales the whole interface up by about a tenth.",
        read = { it.largerText },
        write = { preferences, next -> preferences.copy(largerText = next) }
    ),
    AccessibilityOption(
        label = "High contrast",
        helper = "Stronger borders and darker muted text.",
        read = { it.highContrast },
        write = { preferences, next -> preferences.copy(highContrast = next) }
    )
)

/**
 * Appearance + accessibility, the two cards every account owns.
 *
 * [current] is the state in force (the caller's single source of truth); every change is reported
 * through [onChanged] FIRST so it applies instantly, and only then written to the account — the
 * web's "applies instantly, then catches the server up" order. The caller owns persisting to
 * [AppPreferencesStore]; this screen owns the network.
 *
 * On entry it reconciles with the account once: a saved row replaces [current] via [onChanged], and
 * `{}` (never saved) seeds the account from this device instead.
 */
@Composable
fun AppearanceScreen(
    repository: WorkshopRepository,
    current: AppPreferences,
    onChanged: (AppPreferences) -> Unit,
    onBack: () -> Unit,
    /**
     * Open the phone's own speech and AI settings. See the row at the bottom of this screen for why
     * four cards became one navigation.
     */
    onOpenSpeechAndAi: () -> Unit,
    onOpenMyAiKeys: () -> Unit,
    /**
     * Re-open the first-run walkthrough. See the row at the bottom of this screen for why a settings
     * page carries it at all.
     */
    onOpenWalkthrough: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val systemDark = isSystemInDarkTheme()

    // One-shot reconcile with the account. Keyed on Unit: `current` changes on every toggle and
    // re-running this would fight the user.
    LaunchedEffect(Unit) {
        val remote = runCatching { repository.myPreferences() }.getOrNull()
        if (remote != null) {
            val resolved = remote.toAppPreferences()
            if (resolved != current) onChanged(resolved)
        } else {
            // `{}` — no saved row. Seed the account with what this device is already showing.
            runCatching { repository.savePreferences(current.toBody()) }
        }
    }

    /*
     * THE ROW'S SUMMARY, AND THE TWO THINGS IT IS CAREFUL NOT TO COST.
     *
     * `active = true` binds a `SpeechRecognizer` — one IPC handshake, on a screen a designer
     * navigated to deliberately — which is what lets the row answer "can this phone dictate in a
     * courtyard" before it is tapped. It does NOT take a device probe and it does NOT hash 365 MB:
     * the model's state comes from [DwAsrModelRun], the process-wide reading the dictation ladder
     * already keeps warm, so this screen pays for none of it.
     */
    val packs = rememberDwLanguagePacks(active = true)
    val modelStatus = DwAsrModelRun.status()
    val speechSummary = dwSpeechSummaryLine(
        packStates = dwPackStates(DW_DICTATION_LANGUAGES.map { it.tag }, packs.support),
        modelState = modelStatus.state,
        modelServedTags = dwAsrInstalledModelIds(modelStatus)
            .flatMap { id -> DW_TIER1_CATALOGUE.filter { it.modelId == id } }
            .flatMap { plan -> DW_DICTATION_LANGUAGES.map { it.tag }.filter { plan.servesLanguage(it) } }
            .toSet(),
        // `cannotAsk` is non-null exactly on the handsets the platform will not answer for, which is
        // the distinction between "no packs" and "we were not able to ask".
        canAsk = packs.cannotAsk == null,
    )

    /** Apply locally, then PUT the whole object. Last write wins: an in-flight save is cancelled. */
    fun apply(next: AppPreferences) {
        if (next == current) return
        onChanged(next)
        error = null
        saveJob?.cancel()
        saveJob = scope.launch {
            saving = true
            runCatching { repository.savePreferences(next.toBody()) }
                .onFailure { error = it.message ?: "Unable to save preferences" }
            saving = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // The same arrow the shared header draws on every other screen. This screen is hosted
            // outside that header (it owns its viewport), so it repeats the control rather than
            // inventing a second shape for it.
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Appearance & accessibility",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "How this account looks and reads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.field.muted
            )
        }

        // ---- Appearance -----------------------------------------------------------------------
        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Palette, "Appearance")
            Text(
                buildString {
                    append("Applies to this account everywhere you sign in.")
                    if (current.theme == THEME_SYSTEM) {
                        append(" Your device is currently ")
                        append(if (systemDark) THEME_DARK else THEME_LIGHT)
                        append(".")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
            THEME_OPTIONS.forEach { option ->
                ThemeChoiceRow(
                    option = option,
                    selected = current.theme == option.value,
                    onSelect = { apply(current.copy(theme = option.value)) }
                )
            }
        }

        // ---- Accessibility --------------------------------------------------------------------
        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Accessibility, "Accessibility")
            Text(
                "Reading comfort for this account. Each switch applies the moment it is flipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
            ACCESSIBILITY_OPTIONS.forEach { option ->
                AccessibilitySwitchRow(
                    label = option.label,
                    helper = option.helper,
                    checked = option.read(current),
                    onCheckedChange = { next -> apply(option.write(current, next)) }
                )
            }
            SaveStatusLine(saving = saving, error = error)
        }

        /*
         * ---- ONE ROW INTO SPEECH & AI -----------------------------------------------------------
         *
         * FOUR CARDS STOOD HERE. Measured on the handset's own view hierarchy: "Offline dictation
         * languages" 170 words, "Offline speech engine" 176, "Offline speech model" 188 or more,
         * and "AI on this phone" 156 plus a nineteen-row coverage list of 1,207 — roughly 2,300
         * words below two cards about a colour scheme.
         *
         * They are one row now, and the row carries a SHORT TRUE STATE SUMMARY rather than a chevron
         * and a hope: a designer wants to know whether this phone can dictate in a courtyard, and
         * `dwSpeechSummaryLine` answers it in a clause before they tap. The cards themselves live on
         * [SpeechAndAiScreen], which is where the numbers and the controls are.
         *
         * WHY A SUB-SCREEN RATHER THAN JUST SHORTER CARDS. Because these two cards belong to the
         * ACCOUNT and follow a designer to any handset they sign in on, and everything below the row
         * belongs to THIS PHONE and follows nobody. That distinction used to be made by a paragraph
         * inside the pack card; a screen boundary makes it without a word.
         */
        SettingsRow(
            icon = Icons.Filled.RecordVoiceOver,
            title = "Speech & AI",
            summary = speechSummary,
            onClick = onOpenSpeechAndAi,
        )

        /*
         * ON THIS SIDE OF THE BOUNDARY BECAUSE A KEY FOLLOWS THE ACCOUNT, NOT THE HANDSET.
         * The row above leads to what THIS PHONE can do offline; this one leads to a
         * credential stored on the server against this account, which applies on every
         * device the designer signs in on and on the web. It is deliberately NOT in the
         * admin hub beside the deployment's keys: those are the organisation's, this is
         * the person's own, and confusing the two is how somebody ends up paying for work
         * they did not do.
         */
        SettingsRow(
            icon = Icons.Filled.VpnKey,
            title = "My AI keys",
            summary = "Use your own OpenAI, Gemini or Claude key for AI work, billed to you",
            onClick = onOpenMyAiKeys,
        )

        /*
         * ---- READ THE WALKTHROUGH AGAIN ---------------------------------------------------------
         *
         * The walkthrough opens itself once, on the first launch after signing in, and then never
         * again on this handset — a device-local flag, on purpose, so it is not a wall a returning
         * designer has to dismiss every morning. The cost of that decision is that the ONE surface
         * which teaches the order the work happens in is, by design, the one surface a person cannot
         * get back to by waiting. So it has to be reachable on demand, permanently, and this is the
         * second of the two places it is.
         *
         * NOT A SECOND WALKTHROUGH AND NOT A SECOND DESTINATION. This row hands back the same
         * `NavDestination.WALKTHROUGH` the menu's own ungated root chip does; it is one more door
         * onto one room. The web reaches its `/guide` from five places for the same reason, and
         * `NavEntry.label` already fixes what both clients call it. A row here that opened anything
         * else — a second "Getting started", a "Tour" — would be the two-answers-to-one-question
         * failure this codebase deletes in writing.
         *
         * WHY SETTINGS AND NOT A DASHBOARD TILE. The dashboard's only route into the walkthrough is a
         * text button inside the `if (!canCreateRecords)` block, so a Researcher and everybody above
         * them has no dashboard route at all — which is the gap this row closes. A tile would have
         * been the more visible fix and it is not available: `DashboardTileParityTest` reads the web
         * dashboard off disk and asserts the two grids tile for tile, so a tile added to one client
         * alone fails the build. Settings is where this app already keeps the rows that lead
         * somewhere rather than toggle something, and it is where somebody looking for help looks.
         *
         * The summary says what the walkthrough IS rather than that it exists, because a row reading
         * "Walkthrough >" makes a designer open it to find out whether they wanted it — the same
         * argument `SettingsRow` makes for itself, and the reason the Speech & AI row above carries a
         * measured state summary instead of a chevron and a hope.
         */
        SettingsRow(
            icon = Icons.Filled.Explore,
            title = "Walkthrough",
            summary = "The order the work happens in, step by step — the same journey as on the web",
            onClick = onOpenWalkthrough,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces. Local copies on purpose: the app's card/back-pill helpers are private to MainActivity.kt.
// ---------------------------------------------------------------------------------------------

/**
 * A settings row that leads somewhere, with **a true state summary under its title**.
 *
 * The summary is the whole point of the shape. A row reading "Speech & AI ›" makes a designer open a
 * screen to discover whether they needed to; a row reading "2 of 19 languages work offline · speech
 * model installed" has already answered the question most of them had.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    PreferenceCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.field.brandTile),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.field.onBrandTile,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * The app's standard record card, restated here (MainActivity's `RecordCard` is file-private).
 *
 * `internal` rather than private since 2026-08-12: `SpeechAndAiScreen` draws the same two cards and a
 * second copy of a card shape is how two screens in one settings menu come to look like two apps.
 */
@Composable
internal fun PreferenceCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** The web's card heading: a brand-tile icon chip beside a display-face title. */
@Composable
internal fun PreferenceCardHeading(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.field.brandTile),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.field.onBrandTile,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/*
 * `DwDeviceTierSettings` LIVED HERE AND HAS MOVED to `SpeechAndAiScreen.kt`, shortened, as
 * `DwDeviceTierBody`. This file is about the two settings that belong to the ACCOUNT and follow a
 * designer to any handset; everything it used to carry below those two belongs to THIS PHONE. The
 * screen boundary now makes that distinction, which a paragraph inside a card used to have to.
 */

/** One theme choice: a bordered row that tints and outlines in the action colour when selected. */
@Composable
private fun ThemeChoiceRow(option: ThemeOption, selected: Boolean, onSelect: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.hairline,
                shape = shape
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    option.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(option.helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        }
    }
}

/** One accessibility switch: label + helper on the left, the switch on the right, whole row tappable. */
@Composable
private fun AccessibilitySwitchRow(
    label: String,
    helper: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        }
        // The row owns the gesture, so the switch itself is decoration (null keeps it non-clickable
        // and stops the tap target from being announced twice).
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The web's status line: an error if there is one, else saving/saved. */
@Composable
private fun SaveStatusLine(saving: Boolean, error: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saving && error == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            error ?: if (saving) "Saving to your account…" else "Saved to your account automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.field.muted
        )
    }
}


