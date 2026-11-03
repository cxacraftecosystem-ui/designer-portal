package com.designprototype.workshop.ui.designworkshop

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun Modifier.heightIn48(): Modifier = this.heightIn(min = 48.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DwSketchTraceExportCard(
    repository: WorkshopRepository,
    traceSvg: String,
    geometry: DwTraceGeometry?,
    documentWidth: Int,
    documentHeight: Int,
    documentBackground: Int?,
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    frameNote: String = "",
    isPreview: Boolean,
    exporter: DwTraceExporter,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBackgroundChange: ((white: Boolean) -> Unit)? = null,
    onNeedFullResolution: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var formatId by remember { mutableStateOf(DW_TRACE_EXPORT_FORMATS.first().id) }
    var working by remember { mutableStateOf(false) }

    var saved by remember { mutableStateOf<DwTraceExportSaved?>(null) }

    var savedNote by remember { mutableStateOf("") }

    val format = remember(formatId) {
        dwTraceExportFormat(formatId) ?: DW_TRACE_EXPORT_FORMATS.first()
    }

    val plan = remember(format, exporter.refusal, geometry) {
        dwTraceExportPlan(format, exporter.refusal, hasGeometry = geometry != null)
    }
    val losses = remember(format, documentBackground, documentWidth, documentHeight, frameNote) {
        dwTraceExportLosses(
            format = format,
            documentBackground = documentBackground,
            documentLongEdgePx = maxOf(documentWidth, documentHeight),
            frameNote = frameNote,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Save this drawing",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "$DW_TRACE_EXPORT_FORMAT_COUNT formats. The drawing is the same in all of them; what " +
                "changes is which machine can open it.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DW_TRACE_EXPORT_FORMATS.forEach { row ->
                FilterChip(
                    selected = row.id == formatId,
                    onClick = {
                        if (busy) return@FilterChip
                        formatId = row.id
                        saved = null
                        savedNote = ""
                    },
                    label = { Text(row.label, style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.heightIn48(),
                )
            }
        }

        Text(
            format.hint,
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        HorizontalDivider(color = MaterialTheme.field.hairline)

        Text(
            "Background",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (onBackgroundChange != null) {
            val white = dwTraceBackgroundIsWhite(documentBackground)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true, false).forEach { option ->
                    FilterChip(
                        selected = option == white,
                        onClick = {
                            if (busy || option == white) return@FilterChip
                            saved = null
                            savedNote = ""
                            onBackgroundChange(option)
                        },
                        label = { Text(if (option) "White" else "Transparent", style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier.heightIn48(),
                    )
                }
            }
            Text(
                DW_TRACE_BACKGROUND_RETRACE_SENTENCE,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        } else {
            Text(
                dwTraceBackgroundLabel(documentBackground),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        losses.forEach { line ->
            Text(
                "· $line",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        when {
            isPreview -> {
                Text(
                    DW_TRACE_EXPORT_PREVIEW_SENTENCE,
                    color = MaterialTheme.field.warning,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
                if (onNeedFullResolution != null) {
                    OutlinedButton(
                        onClick = { if (!busy) onNeedFullResolution() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn48(),
                    ) {
                        Text("Trace it at full size", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            plan is DwTraceExportPlan.Refused -> {
                Text(
                    plan.reason,
                    color = MaterialTheme.field.warning,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
            }

            else -> {
                OutlinedButton(
                    onClick = {
                        if (busy) return@OutlinedButton
                        onBusyChange(true)
                        working = true
                        saved = null
                        savedNote = ""
                        scope.launch {
                            runCatching {
                                dwWriteTraceExport(
                                    context = context,
                                    repository = repository,
                                    exporter = exporter,
                                    plan = plan,
                                    format = format,
                                    geometry = geometry,
                                    documentWidth = documentWidth,
                                    documentHeight = documentHeight,
                                    documentBackground = documentBackground,
                                    traceSvg = traceSvg,
                                    sourceName = sourceName,
                                    shapeCount = shapeCount,
                                    nodeCount = nodeCount,
                                    frameNote = frameNote,
                                )
                            }
                                .onSuccess { outcome ->
                                    when (outcome) {
                                        is DwTraceWriteOutcome.Saved -> {
                                            saved = outcome.saved
                                            savedNote = outcome.note
                                        }
                                        is DwTraceWriteOutcome.Refused -> onError(outcome.reason)
                                    }
                                }
                                .onFailure { error ->
                                    if (error !is CancellationException) {
                                        onError(
                                            error.message?.takeIf { it.isNotBlank() }
                                                ?: "That file could not be saved."
                                        )
                                    }
                                }
                            working = false
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn48(),
                ) {
                    if (working) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (working) "Writing the file…" else format.save, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            saved?.let { file ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Saved",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(file.savedTo, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)

                    if (savedNote.isNotBlank()) {
                        Text(
                            savedNote,
                            color = MaterialTheme.field.warning,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                    }

                    if (file.shareUri != null) {
                        OutlinedButton(
                            onClick = {
                                val send = dwTraceExportShareIntent(file) ?: return@OutlinedButton
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(send, "Send the drawing")
                                    )
                                }.onFailure {
                                    onError(
                                        "Nothing installed on this device offered to send it. It is " +
                                            "still saved — ${file.savedTo}"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn48(),
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Send it")
                        }
                        Text(
                            DW_TRACE_EXPORT_SHARE_CAVEAT,
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                    } else {
                        Text(
                            DW_TRACE_EXPORT_NO_SHARE_SENTENCE,
                            color = MaterialTheme.field.muted,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

sealed class DwTraceWriteOutcome {
    class Saved(val saved: DwTraceExportSaved, val note: String = "") : DwTraceWriteOutcome()

    class Refused(val reason: String) : DwTraceWriteOutcome()
}

private suspend fun dwWriteTraceExport(
    context: android.content.Context,
    repository: WorkshopRepository,
    exporter: DwTraceExporter,
    plan: DwTraceExportPlan,
    format: DwTraceExportFormat,
    geometry: DwTraceGeometry?,
    documentWidth: Int,
    documentHeight: Int,
    documentBackground: Int?,
    traceSvg: String,
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    frameNote: String,
): DwTraceWriteOutcome {
    var note = ""
    val bytes: ByteArray = when (plan) {
        is DwTraceExportPlan.Refused -> return DwTraceWriteOutcome.Refused(plan.reason)

        is DwTraceExportPlan.FromTraceSvg -> traceSvg.toByteArray(Charsets.UTF_8)

        is DwTraceExportPlan.FromPlatformRaster -> {
            val handle = geometry
                ?: return DwTraceWriteOutcome.Refused(DW_TRACE_NO_GEOMETRY_SENTENCE)
            note = dwTracePngReductionNote(
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                size = dwTracePngSize(documentWidth, documentHeight),
            )
            dwTraceRenderPngBytes(
                geometry = handle,
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                background = dwTraceExportBackground(documentBackground),
            ) ?: return DwTraceWriteOutcome.Refused(DW_TRACE_PNG_MEMORY_REFUSAL)
        }

        is DwTraceExportPlan.FromExporter -> {
            val handle = geometry
                ?: return DwTraceWriteOutcome.Refused(DW_TRACE_NO_GEOMETRY_SENTENCE)
            val outcome = exporter.export(
                DwTraceExportRequest(
                    geometry = handle,
                    width = documentWidth,
                    height = documentHeight,
                    format = format,
                    background = dwTraceExportBackground(documentBackground),
                    provenanceNote = dwTraceProvenanceNote(
                        sourceName = sourceName,
                        shapeCount = shapeCount,
                        nodeCount = nodeCount,
                        frameNote = frameNote,
                    ),
                )
            )
            when (outcome) {
                is DwTraceExportOutcome.Refused -> return DwTraceWriteOutcome.Refused(outcome.reason)
                is DwTraceExportOutcome.Done -> outcome.bytes
            }
        }
    }

    val name = dwTraceExportFileName(
        sourceName = sourceName,
        extension = format.extension,
        suffix = dwTraceSaveSuffix(format),
    )
    return DwTraceWriteOutcome.Saved(
        dwSaveTraceExport(
            context = context,
            repository = repository,
            bytes = bytes,
            fileName = name,
            mime = format.mime,
        ),
        note = note,
    )
}
