package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwDisplayImage
import com.designprototype.workshop.data.DwImageDecode
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

internal const val DW_SHARED_PREVIEW_EDGE_PX: Int = 256

@Immutable
internal sealed interface DwSketchPhotographSupply {

    @Immutable
    data object OwnChoice : DwSketchPhotographSupply

    @Immutable
    data class Hosted(val source: DwSketchSource?) : DwSketchPhotographSupply
}

@Stable
internal class DwSketchDerivationPhoto(chosenId: String) {

    private var chosenId by mutableStateOf(chosenId)

    fun chosen(sources: List<DwSketchSource>): DwSketchSource? =
        sources.firstOrNull { it.item.id == chosenId } ?: sources.firstOrNull()

    fun choose(id: String) {
        chosenId = id
    }

    companion object {
        val Saver = listSaver<DwSketchDerivationPhoto, String>(
            save = { listOf(it.chosenId) },
            restore = { DwSketchDerivationPhoto(it.getOrElse(0) { "" }) },
        )
    }
}

@Composable
internal fun rememberDwSketchDerivationPhoto(): DwSketchDerivationPhoto =
    rememberSaveable(saver = DwSketchDerivationPhoto.Saver) { DwSketchDerivationPhoto("") }

internal const val DW_SHARED_PHOTOGRAPH_LABEL: String = "Photograph of the sketch"

internal const val DW_SHARED_PHOTOGRAPH_IDLE: String =
    "Choose it once. Both panels below work from it; neither files anything until you press a " +
        "button there."

internal fun dwNoPhotographSentence(
    offersPlate: Boolean,
    offersMeasure: Boolean,
    photoFieldLabels: List<String>,
): String {
    val acts = when {
        offersPlate && offersMeasure -> "trace, straighten or measure against"
        offersPlate -> "trace or straighten"
        offersMeasure -> "measure against"
        else -> "work from"
    }
    val where = if (photoFieldLabels.isEmpty()) {
        "one of this record's image fields"
    } else {
        photoFieldLabels.joinToString(" or ") { "“${it.trim()}”" }
    }
    return "There is no photograph on this record yet, so there is nothing to $acts. Attach one in " +
        "$where — with the capture cards above, where this tab offers that field, or on this " +
        "record's own stage form, which offers all of them. Every panel here reads the photographs " +
        "that are already on the record, so nothing needs uploading twice."
}

internal const val DW_MEASURE_DIFFERENT_PHOTOGRAPH: String = "Measure a different photograph"

internal const val DW_MEASURE_BACK_TO_SHARED: String = "Go back to the photograph chosen above"

internal const val DW_MEASURE_ELSEWHERE_TITLE: String = "Measuring a different photograph"

internal const val DW_MEASURE_DIFFERENT_WHY: String =
    "The sheet worth tracing and the photograph with a ruler beside the object are often two " +
        "different pictures. This measures one that is not the one being traced. Neither photograph " +
        "is changed, moved or detached by anything on this card."

internal fun dwSharedPhotographSentence(imageLabel: String, rowName: String?): String {
    val where = if (rowName.isNullOrBlank()) {
        "“${imageLabel.trim()}” on this record"
    } else {
        "“${imageLabel.trim()}” on “${rowName.trim()}”"
    }
    return "Every panel below works from this photograph. It is already attached as $where — nothing " +
        "further is written until a button in one of the panels below is pressed, and none of " +
        "them ever changes the photograph itself."
}

internal fun dwPhotographSizeSentence(sourceWidth: Int, sourceHeight: Int, sizeBytes: Long): String {
    val file = if (sizeBytes > 0L) humanSize(sizeBytes) else null
    if (sourceWidth < 1 || sourceHeight < 1) {
        return file?.let { "$it. Opening the photograph…" } ?: "Opening the photograph…"
    }
    val frame = "$sourceWidth×$sourceHeight"
    return if (file == null) "$frame." else "$frame · $file."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DwSketchSharedPhotograph(
    sources: List<DwSketchSource>,
    choice: DwSketchDerivationPhoto,
    rowName: String?,
    noPhotographSentence: String,
    enabled: Boolean,
) {
    val chosen = choice.chosen(sources)

    var preview by remember { mutableStateOf<DwDisplayImage?>(null) }
    LaunchedEffect(chosen?.item?.id) {
        preview = null
        val path = chosen?.item?.absolutePath ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            DwImageDecode.decodeForDisplay(path, DW_SHARED_PREVIEW_EDGE_PX)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(16.dp),
            )
            Text(
                DW_SHARED_PHOTOGRAPH_LABEL,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (chosen == null) {
            Text(
                noPhotographSentence,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(8.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DwSharedPhotographPreview(preview)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    chosen.item.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    dwPhotographSizeSentence(
                        sourceWidth = preview?.sourceWidth ?: 0,
                        sourceHeight = preview?.sourceHeight ?: 0,
                        sizeBytes = chosen.item.sizeBytes,
                    ),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        Text(
            dwSharedPhotographSentence(imageLabel = chosen.fieldLabel, rowName = rowName),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        if (sources.size > 1) {
            DwPanelLabel(
                "Choose a different photograph — every panel below follows this"
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEach { candidate ->
                    DwPanelChip(
                        label = "${candidate.fieldLabel} · ${candidate.item.displayName}",
                        selected = candidate.item.id == chosen.item.id,
                        enabled = enabled,
                        onClick = { choice.choose(candidate.item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DwSharedPhotographPreview(preview: DwDisplayImage?) {
    val bitmap = remember(preview) { preview?.bitmap?.asImageBitmap() }
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.field.surface100)
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

internal fun dwDerivationCardCount(offersPlate: Boolean, offersMeasure: Boolean): Int =
    (if (offersPlate) 2 else 0) + (if (offersMeasure) 1 else 0)

internal fun dwSharesOnePhotograph(offersPlate: Boolean, offersMeasure: Boolean): Boolean =
    dwDerivationCardCount(offersPlate, offersMeasure) > 1

@Composable
internal fun DwSketchDerivationSection(
    sources: List<DwSketchSource>,
    photoFieldLabels: List<String>,
    rowName: String?,
    plateField: FieldDto?,
    plateFileName: String?,
    targets: List<DwMeasureTarget>,
    rowValues: Map<String, JsonElement>,
    recordCategory: String?,
    media: DwMediaBridge,
    runtime: DwTraceRuntime,
    enabled: Boolean,
    onAttachedToPlate: (String) -> Unit,
    onPropose: (String, JsonElement?, String?) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    exportCard: (@Composable (DwTraceExportSlot) -> Unit)? = null,
) {
    if (plateField == null && targets.isEmpty()) return

    val choice = rememberDwSketchDerivationPhoto()

    val shares = dwSharesOnePhotograph(
        offersPlate = plateField != null,
        offersMeasure = targets.isNotEmpty(),
    )

    val nothingToWorkFrom = dwNoPhotographSentence(
        offersPlate = plateField != null,
        offersMeasure = targets.isNotEmpty(),
        photoFieldLabels = photoFieldLabels,
    )

    val supply = if (shares) {
        DwSketchPhotographSupply.Hosted(choice.chosen(sources))
    } else {
        DwSketchPhotographSupply.OwnChoice
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sources.isEmpty()) {
            Text(
                nothingToWorkFrom,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            return@Column
        }

        if (shares) {
            DwSketchSharedPhotograph(
                sources = sources,
                choice = choice,
                rowName = rowName,
                noPhotographSentence = nothingToWorkFrom,
                enabled = enabled,
            )
        }

        if (plateField != null) {
            DwSketchRectifyPanel(
                field = plateField,
                sources = sources,
                media = media,
                currentFileName = plateFileName,
                enabled = enabled,
                supply = supply,
                onAttached = onAttachedToPlate,
                onMessage = onMessage,
                onError = onError,
            )
            DwSketchTracePanel(
                field = plateField,
                sources = sources,
                runtime = runtime,
                media = media,
                recordCategory = recordCategory,
                currentFileName = plateFileName,
                enabled = enabled,
                supply = supply,
                onAttached = onAttachedToPlate,
                onMessage = onMessage,
                onError = onError,
                exportCard = exportCard,
            )
        }

        if (targets.isNotEmpty()) {
            DwPhotoMeasurePanel(
                photos = sources.map { it.item },
                photoFieldLabels = photoFieldLabels,
                targets = targets,
                rowValues = rowValues,
                enabled = enabled,
                supply = supply,
                onPropose = onPropose,
            )
        }
    }
}
