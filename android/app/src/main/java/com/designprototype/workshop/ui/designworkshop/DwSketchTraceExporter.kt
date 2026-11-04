package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Immutable

@Immutable
class DwTraceExportRequest(
    val geometry: DwTraceGeometry,
    val width: Int,
    val height: Int,
    val format: DwTraceExportFormat,
    val background: Int?,
    val provenanceNote: String,
)

sealed class DwTraceExportOutcome {
    class Done(val bytes: ByteArray) : DwTraceExportOutcome()

    class Refused(val reason: String) : DwTraceExportOutcome()
}

interface DwTraceExporter {

    val refusal: String?

    suspend fun export(request: DwTraceExportRequest): DwTraceExportOutcome
}

class DwTraceExporterUnavailable(reason: String = DW_TRACE_NO_EXPORTER_SENTENCE) : DwTraceExporter {
    override val refusal: String = reason

    override suspend fun export(request: DwTraceExportRequest): DwTraceExportOutcome =
        DwTraceExportOutcome.Refused(refusal)
}

const val DW_TRACE_NO_EXPORTER_SENTENCE: String =
    "This drawing can be saved as an SVG, which is the full vector line work, or as a picture."

sealed class DwTraceExportPlan {
    data object FromTraceSvg : DwTraceExportPlan()

    data object FromPlatformRaster : DwTraceExportPlan()

    data object FromExporter : DwTraceExportPlan()

    data class Refused(val reason: String) : DwTraceExportPlan()
}

fun dwTraceExportPlan(
    format: DwTraceExportFormat,
    exporterRefusal: String?,
    hasGeometry: Boolean = true,
): DwTraceExportPlan {
    if (format.id == "svg") return DwTraceExportPlan.FromTraceSvg
    if (!hasGeometry) return DwTraceExportPlan.Refused(DW_TRACE_NO_GEOMETRY_SENTENCE)
    if (format.id == "png") return DwTraceExportPlan.FromPlatformRaster
    if (exporterRefusal != null) return DwTraceExportPlan.Refused(exporterRefusal)
    return DwTraceExportPlan.FromExporter
}

const val DW_TRACE_NO_GEOMETRY_SENTENCE: String =
    "The drawing's shapes did not come back with this trace, so the picture and the three take-away " +
        "formats cannot be made from it here. The SVG still saves, with the full vector line " +
        "work. Trace the sheet again if you need the rest."
