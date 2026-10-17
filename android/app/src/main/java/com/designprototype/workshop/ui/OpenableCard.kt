package com.designprototype.workshop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * THE WHOLE CARD IS THE WAY IN — one modifier for every card, row and tile that opens something.
 *
 * Clip, THEN click, then the 48dp floor. Clipping first keeps the ripple inside the card's rounded
 * corner; a `clickable` on the outer `ElevatedCard` modifier (outside the card's own clip) flashes a
 * square. Role.Button and "Open <name>" are what TalkBack reads. Buttons inside the card are children
 * with their own pointer handling, so a tap on Update, Delete or Download never also opens the card.
 *
 * [clickLabel] overrides the "Open <title>" announcement for a card whose natural act is another
 * verb (a dashboard tile says "New Artisan"). [enabled] false draws nothing tappable at all, so a row
 * with nowhere to go does not ripple and then do nothing.
 */
fun Modifier.openable(
    title: String,
    shape: Shape,
    enabled: Boolean = true,
    clickLabel: String = RecordOpen.openClickLabel(title),
    onOpen: () -> Unit
): Modifier =
    if (!enabled) this
    else this
        .clip(shape)
        .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onOpen)
        .heightIn(min = OPENABLE_MIN_TARGET)

/** The touch floor every openable card keeps. */
val OPENABLE_MIN_TARGET: Dp = 48.dp

/**
 * A card whose whole surface opens [title]. The click sits on the card's CONTENT, inside the card's
 * clip, exactly as `DashboardActionCard` and the record picker's cards do it on both portals.
 */
@Composable
fun OpenableCard(
    title: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    spacing: Dp = 6.dp,
    enabled: Boolean = true,
    clickLabel: String = RecordOpen.openClickLabel(title),
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        shape = shape,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .openable(title = title, shape = shape, enabled = enabled, clickLabel = clickLabel, onOpen = onOpen)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}
