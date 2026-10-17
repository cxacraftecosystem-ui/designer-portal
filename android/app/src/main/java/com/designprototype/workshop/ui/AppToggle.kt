package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * `AppToggle` — the ONE on/off control for a SETTING on the handset. The web's twin is `Toggle`
 * (frontend/components/ui/Toggle.tsx), same words, same states.
 *
 * A setting is something that stays switched — two-step verification, reduced motion, a capability.
 * It is never the word "On"/"Off" beside a button and never a checkbox (a checkbox chooses ROWS).
 *
 * A Material 3 [Switch] at the end of a full-width row whose label, description and status line are
 * all part of the target: the ROW is [toggleable] with [Role.Switch], at least 48dp tall, and the
 * switch itself is decoration (`onCheckedChange = null`) so TalkBack announces one control, once.
 *
 * [busy] is the honest in-between state: the two-step verification switch reads "Setting up…" while
 * the server has not confirmed a code, and is NOT checked. A busy row refuses taps and says so in
 * its state description; [checked] stays the truth throughout.
 */
@Composable
fun AppToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    /** A plain-words state line under the description — "On since 17 Oct 2026 · 8 recovery codes left". */
    status: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interactive = AppToggleRules.interactive(enabled, busy)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = interactive, role = Role.Switch) { next ->
                AppToggleRules.outcome(checked, enabled, busy)?.let { if (it == next) onCheckedChange(next) }
            }
            .semantics { stateDescription = AppToggleRules.stateWords(checked, busy) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.field.muted
            )
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
            }
            status?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            // Off is an ink-500 edge and knob on the surface-200 track: the theme's outline is line-200,
            // about 1.2:1 on the card, which left an Off switch barely visible (WCAG 1.4.11 asks 3:1).
            // On is the theme's primary/onPrimary — purple-700/white in light, purple-400/950 in dark.
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedTrackColor = MaterialTheme.field.surface200,
                uncheckedThumbColor = MaterialTheme.field.muted,
                uncheckedBorderColor = MaterialTheme.field.muted,
            )
        )
    }
}

/** The pure rules behind [AppToggle], pinned by `AppToggleTest` — the web's `toggleKeys.ts`. */
object AppToggleRules {
    fun interactive(enabled: Boolean, busy: Boolean): Boolean = enabled && !busy

    /** What a tap does: the flipped value, or null when the row is disabled or mid-change. */
    fun outcome(checked: Boolean, enabled: Boolean, busy: Boolean): Boolean? =
        if (interactive(enabled, busy)) !checked else null

    fun stateWords(checked: Boolean, busy: Boolean): String = when {
        busy -> if (checked) "On, changing" else "Off, changing"
        checked -> "On"
        else -> "Off"
    }
}
