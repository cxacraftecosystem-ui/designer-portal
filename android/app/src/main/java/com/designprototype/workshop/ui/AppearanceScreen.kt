package com.designprototype.workshop.ui

import com.designprototype.workshop.data.userMessage

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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CardDefaults
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
import com.designprototype.workshop.data.WeeklyDigestPreferenceDto
import com.designprototype.workshop.data.WeeklyDigestSwitch
import com.designprototype.workshop.data.PreferencesDto
import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import com.designprototype.workshop.ui.designworkshop.DwAsrModelRun
import com.designprototype.workshop.ui.designworkshop.rememberDwLanguagePacks
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val THEME_SYSTEM: String = "system"

const val THEME_LIGHT: String = "light"

const val THEME_DARK: String = "dark"

val THEME_CHOICES: List<String> = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

const val LARGER_TEXT_SCALE: Float = 1.125f

@Immutable
data class AppPreferences(
    val theme: String = THEME_SYSTEM,
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
) {
    val fontScale: Float get() = if (largerText) LARGER_TEXT_SCALE else 1f

    companion object {
        val Default: AppPreferences = AppPreferences()
    }
}

fun resolveDarkTheme(theme: String, systemDark: Boolean): Boolean = when (theme) {
    THEME_DARK -> true
    THEME_LIGHT -> false
    else -> systemDark
}

fun normalizeThemeChoice(value: String?): String =
    if (value != null && value in THEME_CHOICES) value else THEME_SYSTEM

fun PreferencesDto.toAppPreferences(): AppPreferences = AppPreferences(
    theme = normalizeThemeChoice(theme),
    reducedMotion = reducedMotion,
    largerText = largerText,
    highContrast = highContrast
)

class AppPreferencesStore(context: Context) {
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

    fun clear() {
        store.edit().clear().apply()
    }

    fun pendingSince(): Long? = store.getLong(KEY_PENDING_SINCE, 0L).takeIf { it > 0L }

    fun markPending(at: Long = System.currentTimeMillis()) {
        store.edit().putLong(KEY_PENDING_SINCE, at).apply()
    }

    fun clearPending(at: Long?) {
        if (at == null || pendingSince() == at) store.edit().remove(KEY_PENDING_SINCE).apply()
    }

    private companion object {
        const val KEY_PENDING_SINCE = "pendingSince"
        const val KEY_THEME = "theme"
        const val KEY_REDUCED_MOTION = "reducedMotion"
        const val KEY_LARGER_TEXT = "largerText"
        const val KEY_HIGH_CONTRAST = "highContrast"
    }
}

suspend fun syncAppPreferences(
    repository: WorkshopRepository,
    local: AppPreferences,
    store: AppPreferencesStore? = null
): AppPreferences {
    val remote = runCatching { repository.myPreferences() }.getOrNull()
    val pending = store?.pendingSince()
    if (remote == null || localPreferencesWin(pending, remote.updatedAt)) {
        runCatching { repository.savePreferences(local.toBody()) }
            .onSuccess { store?.clearPending(pending) }
        return local
    }
    store?.clearPending(pending)
    val resolved = remote.toAppPreferences()
    store?.write(resolved)
    return resolved
}

internal fun localPreferencesWin(pendingSince: Long?, remoteUpdatedAt: String?): Boolean {
    if (pendingSince == null) return false
    val remoteAt = remoteUpdatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: return true
    return pendingSince > remoteAt
}

private fun AppPreferences.toBody(): PreferencesDto = PreferencesDto(
    theme = normalizeThemeChoice(theme),
    reducedMotion = reducedMotion,
    largerText = largerText,
    highContrast = highContrast
)

val LocalAppPreferences = staticCompositionLocalOf { AppPreferences.Default }

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

private data class ThemeOption(val value: String, val label: String, val helper: String, val icon: ImageVector)

private val THEME_OPTIONS = listOf(
    ThemeOption(
        value = THEME_SYSTEM,
        label = "Match my device",
        helper = "Follows your device's light/dark setting.",
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

private val ACCESSIBILITY_OPTIONS = listOf(
    AccessibilityOption(
        label = "Reduce motion",
        helper = "Turns off animations. Your device setting is always respected.",
        read = { it.reducedMotion },
        write = { preferences, next -> preferences.copy(reducedMotion = next) }
    ),
    AccessibilityOption(
        label = "Larger text",
        helper = "About 10% larger.",
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

@Composable
fun AppearanceScreen(
    repository: WorkshopRepository,
    current: AppPreferences,
    onChanged: (AppPreferences) -> Unit,
    onBack: () -> Unit,
    onOpenSpeechAndAi: () -> Unit,
    onOpenMyAiKeys: () -> Unit,
    onOpenUsageRecording: () -> Unit,
    onOpenTwoStepVerification: () -> Unit,
    onOpenPassword: () -> Unit = {},
    onOpenWalkthrough: () -> Unit,
    onOpenAadhaar: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val systemDark = isSystemInDarkTheme()

    var digest by remember { mutableStateOf<WeeklyDigestPreferenceDto?>(null) }
    var digestSaving by remember { mutableStateOf(false) }
    var digestError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { digest = repository.weeklyDigestPreference() }

    val prefsContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val prefsStore = remember { AppPreferencesStore(prefsContext) }
    LaunchedEffect(Unit) {
        val resolved = syncAppPreferences(repository, current, prefsStore)
        if (resolved != current) onChanged(resolved)
    }

    val packs = rememberDwLanguagePacks(active = true)
    val modelStatus = DwAsrModelRun.status()
    val speechSummary = dwSpeechSummaryLine(
        packStates = dwPackStates(DW_DICTATION_LANGUAGES.map { it.tag }, packs.support),
        modelState = modelStatus.state,
        modelServedTags = dwAsrInstalledModelIds(modelStatus)
            .flatMap { id -> DW_TIER1_CATALOGUE.filter { it.modelId == id } }
            .flatMap { plan -> DW_DICTATION_LANGUAGES.map { it.tag }.filter { plan.servesLanguage(it) } }
            .toSet(),
        canAsk = packs.cannotAsk == null,
    )

    fun apply(next: AppPreferences) {
        if (next == current) return
        onChanged(next)
        error = null
        saveJob?.cancel()
        val changedAt = System.currentTimeMillis()
        prefsStore.markPending(changedAt)
        saveJob = scope.launch {
            saving = true
            runCatching { repository.savePreferences(next.toBody()) }
                .onSuccess { prefsStore.clearPending(changedAt) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    error = if (!repository.isOnline(prefsContext)) {
                        "Saved on this phone; syncs when online."
                    } else {
                        it.userMessage("Unable to save preferences")
                    }
                }
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

        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Palette, "Appearance")
            Text(
                buildString {
                    append("Applies wherever you sign in.")
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

        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Accessibility, "Accessibility")
            Text(
                "Changes apply instantly.",
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

        EmailNotificationsCard(repository)

        SettingsRow(
            icon = Icons.Filled.RecordVoiceOver,
            title = "Speech & AI",
            summary = speechSummary,
            onClick = onOpenSpeechAndAi,
        )

        SettingsRow(
            icon = Icons.Filled.VpnKey,
            title = "My AI keys",
            summary = "Use your own OpenAI, Gemini or Claude key",
            onClick = onOpenMyAiKeys,
        )

        val digestState = digest
        if (digestState != null && WeeklyDigestSwitch.visible(digestState)) {
            PreferenceCard {
                PreferenceCardHeading(icon = Icons.Filled.CalendarMonth, title = WeeklyDigestSwitch.LABEL)
                AppToggle(
                    label = WeeklyDigestSwitch.LABEL,
                    description = WeeklyDigestSwitch.description(digestState),
                    checked = digestState.enabled,
                    busy = digestSaving,
                    onCheckedChange = { next ->
                        val before = digestState
                        digest = before.copy(enabled = next)
                        digestError = null
                        digestSaving = true
                        scope.launch {
                            runCatching { repository.saveWeeklyDigestPreference(next) }
                                .onSuccess { digest = it }
                                .onFailure {
                                    digest = before
                                    digestError = it.userMessage("Your choice could not be saved. Try again.")
                                }
                            digestSaving = false
                        }
                    },
                )
                digestError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        SettingsRow(
            icon = Icons.Filled.Key,
            title = "Password",
            summary = "Change or set your password",
            onClick = onOpenPassword,
        )

        SettingsRow(
            icon = Icons.Filled.Security,
            title = "Two-step verification",
            summary = "Authenticator code at sign-in",
            onClick = onOpenTwoStepVerification,
        )

        SettingsRow(
            icon = Icons.Filled.Policy,
            title = "Usage recording",
            summary = "What's recorded and how to opt out",
            onClick = onOpenUsageRecording,
        )

        if (onOpenAadhaar != null) {
            SettingsRow(
                icon = Icons.Filled.VerifiedUser,
                title = "Aadhaar verification",
                summary = "Verify this account with Aadhaar",
                onClick = onOpenAadhaar,
            )
        }

        SettingsRow(
            icon = Icons.Filled.Explore,
            title = "Walkthrough",
            summary = "The order the work happens in, step by step",
            onClick = onOpenWalkthrough,
        )
    }
}

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

@Composable
private fun AccessibilitySwitchRow(
    label: String,
    helper: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    AppToggle(label = label, description = helper, checked = checked, onCheckedChange = onCheckedChange)
}

@Composable
private fun SaveStatusLine(saving: Boolean, error: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saving && error == null) {
            FieldCircularProgressIndicator(
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


