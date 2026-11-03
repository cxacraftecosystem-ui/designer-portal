package com.designprototype.workshop.ui.designworkshop

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
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
import com.designprototype.workshop.data.DwPhotoGate
import com.designprototype.workshop.data.ImageMeasurement
import com.designprototype.workshop.ui.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class DwScreenCandidate(
    val uri: Uri,
    val displayName: String,
    val staging: File? = null,
)

data class DwAdmittedPhoto(
    val candidate: DwScreenCandidate,
    val faults: List<DwPhotoGate.GateFault>,
    val measurement: ImageMeasurement?,
)

data class DwFieldScreening(
    val screening: Int = 0,
    val refused: List<DwPhotoGate.RefusedPhoto> = emptyList(),
) {
    companion object {
        val EMPTY = DwFieldScreening()
    }
}

@Stable
class DwScreeningStore(private val scope: CoroutineScope) {

    private val slots = mutableStateMapOf<String, DwFieldScreening>()

    private val measuredByMediaId = mutableStateMapOf<String, ImageMeasurement>()

    private val decodeLock = Mutex()

    fun stateFor(slot: String): DwFieldScreening = slots[slot] ?: DwFieldScreening.EMPTY

    fun measurementFor(mediaId: String): ImageMeasurement? = measuredByMediaId[mediaId]

    fun screen(
        slot: String,
        resolver: ContentResolver,
        candidates: List<DwScreenCandidate>,
        attached: List<AttachedImage>,
        onAdmitted: (List<DwAdmittedPhoto>) -> Unit,
    ) {
        if (candidates.isEmpty()) return
        val current = stateFor(slot)
        slots[slot] = DwFieldScreening(
            screening = current.screening + candidates.size,
            refused = if (current.screening == 0) emptyList() else current.refused,
        )

        scope.launch {
            val admitted = mutableListOf<DwAdmittedPhoto>()
            val seen = attached.toMutableList()

            for (candidate in candidates) {
                val screened = decodeLock.withLock {
                    withContext(Dispatchers.Default) { DwImageDecode.screen(resolver, candidate.uri) }
                }

                if (screened == null) {
                    admitted += DwAdmittedPhoto(candidate, emptyList(), null)
                    seen += AttachedImage(label = candidate.displayName, checksum = null)
                    withContext(Dispatchers.Main) { settle(slot, null) }
                    continue
                }

                val verdict = DwPhotoGate.judge(
                    measurement = screened.measurement,
                    checksum = screened.sha256,
                    attached = seen.toList(),
                )

                var refusal: DwPhotoGate.RefusedPhoto? = null
                if (verdict.admitted) {
                    admitted += DwAdmittedPhoto(
                        candidate = candidate,
                        faults = verdict.faults,
                        measurement = screened.measurement,
                    )
                    seen += AttachedImage(
                        label = candidate.displayName,
                        checksum = screened.sha256,
                        perceptualHash = screened.measurement.perceptualHash,
                    )
                } else {
                    refusal = DwPhotoGate.RefusedPhoto(candidate.displayName, verdict.faults)
                    candidate.staging?.let { file -> runCatching { file.delete() } }
                }

                withContext(Dispatchers.Main) { settle(slot, refusal) }
            }

            withContext(Dispatchers.Main) {
                if (admitted.isNotEmpty()) onAdmitted(admitted.toList())
            }
        }
    }

    private fun settle(slot: String, refusal: DwPhotoGate.RefusedPhoto?) {
        val current = stateFor(slot)
        slots[slot] = DwFieldScreening(
            screening = (current.screening - 1).coerceAtLeast(0),
            refused = if (refusal == null) current.refused else current.refused + refusal,
        )
    }

    fun bankMeasurements(admitted: List<DwAdmittedPhoto>, mediaIds: List<String>) {
        if (admitted.size != mediaIds.size) return
        admitted.forEachIndexed { index, photo ->
            photo.measurement?.let { measurement -> measuredByMediaId[mediaIds[index]] = measurement }
        }
    }
}

@Composable
internal fun DwPhotoRefusalNotice(refused: List<DwPhotoGate.RefusedPhoto>, modifier: Modifier = Modifier) {
    val lines = DwPhotoGate.refusalLines(refused)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
    ) {
        if (lines.isEmpty()) return@Column
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        DwPhotoGate.refusalHeading(lines.size),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                    )
                    lines.forEach { line ->
                        Text(
                            line,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 17.sp,
                        )
                    }
                    Text(
                        DwPhotoGate.scopeSentence(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
