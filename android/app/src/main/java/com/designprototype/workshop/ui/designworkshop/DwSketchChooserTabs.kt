package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

internal enum class DwSketchChooserTab(
    val label: String,
    val hint: String,
    val icon: ImageVector,
) {
    UPLOAD(
        label = "Upload",
        hint = "Add drawings, photos and 3D models to each piece.",
        icon = Icons.Filled.Upload,
    ),
    REVIEW(
        label = "Review",
        hint = "Rate others' work, suggest changes and set the order.",
        icon = Icons.Filled.Star,
    ),
}

@Composable
internal fun DwSketchChooserTabStrip(
    selected: DwSketchChooserTab,
    onSelect: (DwSketchChooserTab) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tabs = remember { DwSketchChooserTab.entries.toList() }
    val requesters = remember { DwSketchChooserTab.entries.associateWith { FocusRequester() } }

    var keyboardMove by remember { mutableStateOf(false) }

    LaunchedEffect(selected, keyboardMove) {
        if (!keyboardMove) return@LaunchedEffect
        keyboardMove = false
        runCatching { requesters[selected]?.requestFocus() }
    }

    fun move(to: DwSketchChooserTab) {
        if (!enabled || to == selected) return
        keyboardMove = true
        onSelect(to)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TabRow(
            selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
            containerColor = MaterialTheme.field.surface50,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = tab == selected
                Tab(
                    selected = isSelected,
                    enabled = enabled,
                    onClick = { if (enabled) onSelect(tab) },
                    modifier = Modifier
                        .focusRequester(requesters.getValue(tab))
                        .focusProperties { canFocus = isSelected && enabled }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val last = tabs.lastIndex
                            val target = when (event.key) {
                                Key.DirectionRight -> tabs[if (index == last) 0 else index + 1]
                                Key.DirectionLeft -> tabs[if (index == 0) last else index - 1]
                                Key.MoveHome -> tabs.first()
                                Key.MoveEnd -> tabs.last()
                                else -> null
                            } ?: return@onPreviewKeyEvent false
                            move(target)
                            true
                        }
                        .semantics {
                            stateDescription = if (isSelected) "showing" else "not showing"
                        },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    },
                )
            }
        }
        Text(
            selected.hint,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun DwSketchChooserTabPanel(
    tab: DwSketchChooserTab,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { paneTitle = tab.label },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
