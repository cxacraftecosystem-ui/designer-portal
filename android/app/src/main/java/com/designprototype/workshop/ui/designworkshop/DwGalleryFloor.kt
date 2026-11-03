package com.designprototype.workshop.ui.designworkshop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwPhotoGate
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

@Composable
internal fun DwGalleryFloor(
    floor: Int,
    label: String,
    counts: DwPhotoGate.GalleryCounts,
    modifier: Modifier = Modifier,
) {
    val progress = DwPhotoGate.galleryProgress(counts, floor)
    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val fraction by animateFloatAsState(
        targetValue = progress.percent / 100f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 300),
        label = "galleryFloor",
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(label, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            Text(
                progress.readout,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        FieldLinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .semantics { stateDescription = progress.words },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.field.surface100,
        )
        Text(
            progress.words,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        Text(
            DwPhotoGate.galleryFloorSentence(floor = floor, label = label),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
    }
}
