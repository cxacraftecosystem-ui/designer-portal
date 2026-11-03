package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.AttachedImage
import com.designprototype.workshop.data.DwImageDecode
import com.designprototype.workshop.data.DwImageQuality
import com.designprototype.workshop.data.ImageMeasurement
import com.designprototype.workshop.data.QualityFinding
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DwPhotoFindings(
    val mediaId: String,
    val displayName: String,
    val findings: List<QualityFinding>,
)

@Composable
internal fun DwPhotoQualityAdvisories(
    ids: List<String>,
    media: DwMediaBridge,
    modifier: Modifier = Modifier,
) {
    val measured = remember { mutableStateMapOf<String, ImageMeasurement>() }

    val unmeasurable = remember { mutableStateMapOf<String, Boolean>() }

    var dismissed by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(ids) {
        for (id in ids) {
            if (measured.containsKey(id) || unmeasurable.containsKey(id)) continue
            val item = media.resolve(id) ?: continue
            if (!item.mediaType.equals("IMAGE", ignoreCase = true)) continue
            val banked = media.screening.measurementFor(id)
            if (banked != null) {
                measured[id] = banked
                continue
            }
            val measurement = withContext(Dispatchers.Default) { DwImageDecode.measure(item.absolutePath) }
            if (measurement == null) unmeasurable[id] = true else measured[id] = measurement
        }
    }

    val entries = buildList {
        val prior = mutableListOf<AttachedImage>()
        for (id in ids) {
            val item = media.resolve(id) ?: continue
            if (!item.mediaType.equals("IMAGE", ignoreCase = true)) continue
            val measurement = measured[id]
            if (measurement == null) {
                prior += AttachedImage(label = item.displayName, checksum = item.sha256)
                continue
            }
            val findings = DwImageQuality.findQualityIssues(
                measurement = measurement,
                checksum = item.sha256,
                attached = prior.toList(),
            )
            prior += AttachedImage(
                label = item.displayName,
                checksum = item.sha256,
                perceptualHash = measurement.perceptualHash,
            )
            if (findings.isNotEmpty()) add(DwPhotoFindings(id, item.displayName, findings))
        }
    }

    val visible = entries.filterNot { it.mediaId in dismissed }
    if (visible.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEach { entry ->
            DwPhotoQualityCard(
                entry = entry,
                onDismiss = { dismissed = dismissed + entry.mediaId },
            )
        }
    }
}

@Composable
private fun DwPhotoQualityCard(entry: DwPhotoFindings, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.warningContainer),
        border = BorderStroke(1.dp, MaterialTheme.field.warning),
    ) {
        Column(
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.field.onWarningContainer,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Check this photograph — ${entry.displayName}",
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.findings.forEach { finding ->
                        Text(
                            finding.message,
                            color = MaterialTheme.field.onWarningContainer,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 17.sp,
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Dismiss", color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "Already saved on this phone.",
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun DwMissingViewsNote(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.field.warning,
            modifier = Modifier.size(16.dp),
        )
        Text(message, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
    }
}
