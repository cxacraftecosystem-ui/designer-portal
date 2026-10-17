package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.OutboxBanner

/*
 * THE TWO STATUS BANNERS THE APP ROOT STACKS ABOVE THE ISLAND BAR (see BannerClearance.kt).
 *
 * One chip treatment for both, so the connection line and the device queue read as one family:
 * a dark warm chip with light ink, which holds its contrast on the light canvas and, with the
 * hairline, separates from the dark one. The ink is 12.6:1 on the chip and the amber 8.4:1, so
 * neither depends on the theme.
 */

private val ChipInk = Color(0xFFF3E9DD)
private val ChipWarn = Color(0xFFF0B37E)
private val ChipFill = Color(0xFF2A2520)
private val ChipEdge = Color(0x33F3E9DD)
private val ChipShape = RoundedCornerShape(12.dp)

private fun Modifier.statusChip(): Modifier =
    this.background(ChipFill, ChipShape).border(1.dp, ChipEdge, ChipShape)

/** The one line that says the app is working from the phone: offline, or the server not answering. */
@Composable
fun OfflineNotice(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .statusChip()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = ChipInk, modifier = Modifier.size(18.dp))
        Text(text, color = ChipInk, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/**
 * The device queue: what is saved on this phone, and — the whole row is one button — the tray that
 * lists every entry with its status, Download and Retry. Tappable whenever the tray has a row for
 * this account ([OutboxBanner.actionable]): an entry that is only waiting still has files a
 * designer may want a copy of before it goes. [onOpen] is null when there is nothing behind it.
 */
@Composable
fun OutboxDeviceBannerCard(banner: OutboxBanner, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .statusChip()
            .then(
                if (onOpen != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "Show saved entries", onClick = onOpen)
                } else {
                    Modifier
                }
            )
            .heightIn(min = 48.dp)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            // CloudOff only for work a connection will move. A refusal under a cloud icon sends the
            // designer up the hill to find a signal, which is the one thing that cannot help — see
            // OutboxBanner.showCloudOff.
            if (banner.showCloudOff) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (banner.showCloudOff) ChipInk else ChipWarn,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            banner.lines.forEach { line ->
                Text(
                    line.text,
                    color = if (line.warn) ChipWarn else ChipInk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (line.warn) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
        if (onOpen != null) {
            Spacer(Modifier.size(2.dp))
            Text("View", color = ChipInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ChipInk,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
