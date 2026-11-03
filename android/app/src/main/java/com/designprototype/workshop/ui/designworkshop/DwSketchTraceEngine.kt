package com.designprototype.workshop.ui.designworkshop

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable

@Immutable
sealed class DwTraceValue {
    data class Num(val value: Double) : DwTraceValue()

    data class Flag(val value: Boolean) : DwTraceValue()

    data class Choice(val value: String) : DwTraceValue()

    data object Absent : DwTraceValue()
}

@Immutable
class DwTraceValues(
    private val leaves: Map<String, DwTraceValue>,
    val wire: String,
) {
    operator fun get(key: String): DwTraceValue? = leaves[key]

    fun number(key: String): Double? = (leaves[key] as? DwTraceValue.Num)?.value

    fun flag(key: String): Boolean? = (leaves[key] as? DwTraceValue.Flag)?.value

    fun choice(key: String): String? = (leaves[key] as? DwTraceValue.Choice)?.value

    fun present(key: String): Boolean {
        val leaf = leaves[key] ?: return false
        return leaf != DwTraceValue.Absent
    }

    val keys: Set<String> get() = leaves.keys

    val styleId: String get() = choice(DW_TRACE_STYLE_ID_KEY).orEmpty()
}

const val DW_TRACE_STYLE_ID_KEY: String = "styleId"

@Immutable
data class DwTracePreset(
    val id: String,
    val name: String,
    val description: String,
    val group: String,
)

@Immutable
data class DwTracePresetTables(
    val styles: List<DwTracePreset>,
    val subjects: List<DwTracePreset>,
)

enum class DwTraceRunKind {
    PREVIEW,

    ATTACH,

    EXPORT,
}

val DwTraceRunKind.isFullResolution: Boolean get() = this != DwTraceRunKind.PREVIEW

@Immutable
data class DwTraceRequest(
    val photographPath: String,
    val params: DwTraceValues,
    val kind: DwTraceRunKind,
    val frame: DwTraceFrameChoice? = null,
    val plateLongEdgePx: Int = DW_TRACE_PLATE_LONG_EDGE_PX,
)

const val DW_TRACE_PLATE_LONG_EDGE_PX: Int = 1024

@Immutable
data class DwTraceProgress(
    val stageId: String,
    val label: String,
    val fraction: Float,
)

@Immutable
data class DwTraceStageTiming(val id: String, val label: String, val millis: Long)

@Immutable
class DwTraceResult(
    val svg: String,
    val geometry: DwTraceGeometry?,
    val tracePlate: Bitmap?,
    val photographPlate: Bitmap?,
    val plateRefusal: String,
    val width: Int,
    val height: Int,
    val workingWidth: Int,
    val workingHeight: Int,
    val shapeCount: Int,
    val nodeCount: Int,
    val stages: List<DwTraceStageTiming>,
    val totalMillis: Long,
    val notes: List<String>,
    val appliedParams: DwTraceValues,
    val autoSubjectId: String,
    val suggestedStyleId: String,
    val frameNote: String = "",
) {
    val isPreview: Boolean get() = workingWidth < width || workingHeight < height

    val hasPlates: Boolean get() = tracePlate != null && photographPlate != null
}

sealed class DwTraceOutcome {
    data class Done(val result: DwTraceResult) : DwTraceOutcome()

    data class Refused(val reason: String) : DwTraceOutcome()
}

@Immutable
data class DwTraceAvailability(
    val maxWorkingLongEdge: Int,
    val fdogMaxWorkingLongEdge: Int,
    val measuredOn: String?,
)

interface DwTraceRuntime {

    val availability: DwTraceAvailability

    suspend fun presets(): DwTracePresetTables

    suspend fun defaults(): DwTraceValues

    suspend fun withOverrides(base: DwTraceValues, patch: Map<String, DwTraceValue>): DwTraceValues

    suspend fun applyStyle(base: DwTraceValues, styleId: String): DwTraceValues

    suspend fun applySubject(base: DwTraceValues, subjectId: String): DwTraceValues

    suspend fun trace(
        request: DwTraceRequest,
        onProgress: (DwTraceProgress) -> Unit,
    ): DwTraceOutcome
}

const val DW_TRACE_ENGINE_SILENT_SENTENCE: String =
    "Tracing controls didn't load. Close and reopen."
