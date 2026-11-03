package com.designprototype.workshop.ui

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.AppScope
import com.designprototype.workshop.data.DwPhotoMeasure
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.MEASUREMENT_GRID_PURPOSE
import com.designprototype.workshop.ui.designworkshop.DwMeasureTarget
import com.designprototype.workshop.ui.designworkshop.DwMediaItem
import com.designprototype.workshop.ui.designworkshop.DwPhotoMeasurePanel
import com.designprototype.workshop.ui.designworkshop.dwMeasurableLengthFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class DwRecordDimension(
    val column: String,
    val label: String,
    val unit: String = "in",
)

val PRODUCT_MEASURE_DIMENSIONS: List<DwRecordDimension> = listOf(
    DwRecordDimension("lengthInches", "Length (inches)"),
    DwRecordDimension("breadthInches", "Breadth (inches)"),
    DwRecordDimension("heightInches", "Height (inches)"),
)

val TOOL_MEASURE_DIMENSIONS: List<DwRecordDimension> = listOf(
    DwRecordDimension("lengthInches", "Length (inches)"),
    DwRecordDimension("breadthInches", "Breadth (inches)"),
    DwRecordDimension("heightInches", "Height (inches)"),
)

internal fun dwRecordMeasureTargets(dimensions: List<DwRecordDimension>): List<DwMeasureTarget> {
    val siblings: Map<String, FieldDto> = LinkedHashMap<String, FieldDto>().apply {
        dimensions.forEach { dimension ->
            put(
                dimension.column,
                FieldDto(
                    key = dimension.column,
                    label = dimension.label,
                    type = "DECIMAL",
                    unit = dimension.unit,
                    minValue = 0.0,
                ),
            )
        }
    }
    return dwMeasurableLengthFields(siblings)
}

internal fun dwRecordRowValues(current: Map<String, String>): Map<String, JsonElement> =
    current.mapNotNull { (column, text) ->
        text.trim().takeIf { it.isNotEmpty() }?.let { column to JsonPrimitive(it) }
    }.toMap()

internal const val DW_RECORD_DECIMALS: Int = 2

private fun dwClampedToColumn(offered: String): BigDecimal? =
    offered.toBigDecimalOrNull()
        ?.takeIf { it.scale() > DW_RECORD_DECIMALS }
        ?.setScale(DW_RECORD_DECIMALS, RoundingMode.HALF_UP)

internal fun dwRecordProposalText(proposed: JsonElement?): String {
    val offered = DwValues.text(proposed)
    val clamped = dwClampedToColumn(offered) ?: return offered
    return if (clamped.signum() == 0) "" else clamped.toPlainString()
}

internal fun dwRecordProposalNote(dimension: DwRecordDimension, proposed: JsonElement?): String? {
    val offered = DwValues.text(proposed)
    val clamped = dwClampedToColumn(offered) ?: return null
    if (clamped.signum() == 0) {
        return "≈ $offered ${dimension.unit} rounds to zero in “${dimension.label}” ($DW_RECORD_DECIMALS decimals), so nothing was put in it."
    }
    return "Rounded to ${clamped.toPlainString()} (“${dimension.label}” holds $DW_RECORD_DECIMALS decimals)."
}

internal fun dwRecordPhotoLabel(index: Int, total: Int, purpose: String?): String {
    val position = if (total > 1) "${index + 1} of $total" else "${index + 1}"
    return if (purpose == MEASUREMENT_GRID_PURPOSE) "Grid photo $position" else "Photo $position"
}

@Composable
internal fun RecordMeasureField(
    dimensions: List<DwRecordDimension>,
    current: Map<String, String>,
    photos: List<Uri>,
    purposes: Map<Uri, String>,
    enabled: Boolean = true,
    onPropose: (column: String, text: String, technique: String?) -> Unit,
) {
    val targets = remember(dimensions) { dwRecordMeasureTargets(dimensions) }
    if (targets.isEmpty()) return
    val items = rememberMeasurablePhotos(photos, purposes)
    if (items.isEmpty()) return
    var columnNote by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DwPhotoMeasurePanel(
            photos = items,
            targets = targets,
            rowValues = remember(current) { dwRecordRowValues(current) },
            enabled = enabled,
            onPropose = { column, proposed, technique ->
                val text = dwRecordProposalText(proposed)
                columnNote = dimensions.firstOrNull { it.column == column }
                    ?.let { dwRecordProposalNote(it, proposed) }
                if (text.isNotBlank()) onPropose(column, text, technique)
            },
        )
        columnNote?.let { note ->
            Text(
                note,
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
    }
}

private const val WORKING_DIR = "measure-working"

private data class DwWorkingFile(val path: String, val sizeBytes: Long, val copied: Boolean)

@Composable
private fun rememberMeasurablePhotos(
    uris: List<Uri>,
    purposes: Map<Uri, String>,
): List<DwMediaItem> {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<DwMediaItem>>(emptyList()) }
    val resolved = remember { ConcurrentHashMap<String, DwWorkingFile>() }

    LaunchedEffect(uris, purposes) {
        items = withContext(Dispatchers.IO) {
            val live = uris.mapTo(mutableSetOf()) { it.toString() }
            resolved.keys.filterNot { it in live }.forEach { key ->
                resolved.remove(key)?.let { if (it.copied) runCatching { File(it.path).delete() } }
            }
            uris.mapIndexedNotNull { index, uri ->
                val key = uri.toString()
                val file = resolved[key]
                    ?: dwWorkingCopy(context, uri)?.also { resolved[key] = it }
                    ?: return@mapIndexedNotNull null
                DwMediaItem(
                    id = key,
                    displayName = dwRecordPhotoLabel(index, uris.size, purposes[uri]),
                    absolutePath = file.path,
                    mediaType = "IMAGE",
                    sizeBytes = file.sizeBytes,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val paths = resolved.values.filter { it.copied }.map { it.path }
            resolved.clear()
            AppScope.io.launch { paths.forEach { runCatching { File(it).delete() } } }
        }
    }

    return items
}

private fun dwWorkingCopy(context: Context, uri: Uri): DwWorkingFile? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        )
        ?: return null
    if (!mimeType.startsWith("image/")) return null

    if (uri.scheme == null || uri.scheme == "file") {
        val existing = uri.path?.let { File(it) }?.takeIf { it.isFile } ?: return null
        return DwWorkingFile(path = existing.absolutePath, sizeBytes = existing.length(), copied = false)
    }

    val directory = File(context.cacheDir, WORKING_DIR)
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val target = File(directory, "${UUID.randomUUID()}.img")
    val copied = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
        }
    }.getOrNull()
    if (copied == null || copied <= 0L || target.length() != copied) {
        runCatching { target.delete() }
        return null
    }
    return DwWorkingFile(path = target.absolutePath, sizeBytes = copied, copied = true)
}

internal val DW_RECORD_UNITS: Set<String> get() = DwPhotoMeasure.LENGTH_UNITS.keys
