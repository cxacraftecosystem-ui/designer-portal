package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "No connection — showing the copy saved on this phone on …" (see `data/ReadCache.kt`), drawn in the
 * same card shape as [LoadFailedCard] so a list served from the phone reads as a state of the page
 * rather than a stray line: an icon AND words (never colour alone), a polite live region so TalkBack
 * reads it, and the neutral container, because a copy that loaded is not a failure. No Try again: the
 * list is already on screen, and the next open asks the server first.
 */
@Composable
fun CachedCopyNote(note: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = "Offline copy", modifier = Modifier.size(20.dp))
            Text(note, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}
