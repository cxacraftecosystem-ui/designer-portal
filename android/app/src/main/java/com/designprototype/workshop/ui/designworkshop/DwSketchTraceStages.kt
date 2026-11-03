package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Immutable

@Immutable
data class DwTraceStage(val id: String, val label: String)

val DW_TRACE_STAGES: List<DwTraceStage> = listOf(
    DwTraceStage("prepare", "Preparing image"),
    DwTraceStage("matte", "Separating background"),
    DwTraceStage("crop", "Cropping to the subject"),
    DwTraceStage("gray", "Converting to grey"),
    DwTraceStage("denoise", "Reducing noise"),
    DwTraceStage("contrast", "Enhancing contrast"),
    DwTraceStage("edge", "Detecting edges"),
    DwTraceStage("cleanup", "Cleaning up"),
    DwTraceStage("skeleton", "Thinning strokes"),
    DwTraceStage("distance", "Measuring stroke width"),
    DwTraceStage("vectorize", "Tracing vectors"),
    DwTraceStage("document", "Assembling document"),
)

val DW_TRACE_STAGE_COUNT: Int = DW_TRACE_STAGES.size

fun dwTraceStageIndex(stageId: String): Int = DW_TRACE_STAGES.indexOfFirst { it.id == stageId }

val DW_TRACE_SLOW_STAGE_IDS: List<String> = listOf("edge", "vectorize")

@Immutable
class DwTraceProgressWeights private constructor(
    private val startFractions: Map<String, Float>,
    val measured: Boolean,
) {

    fun fractionAt(stageId: String, fallback: Float): Float =
        startFractions[stageId] ?: fallback

    companion object {
        val Unweighted: DwTraceProgressWeights = DwTraceProgressWeights(
            startFractions = DW_TRACE_STAGES.withIndex().associate { (index, stage) ->
                stage.id to index.toFloat() / DW_TRACE_STAGE_COUNT
            },
            measured = false,
        )

        fun from(timings: List<DwTraceStageTiming>): DwTraceProgressWeights {
            val total = timings.sumOf { it.millis }
            if (timings.isEmpty() || total <= 0L) return Unweighted
            var elapsed = 0L
            val out = LinkedHashMap<String, Float>(timings.size)
            for (timing in timings) {
                out[timing.id] = elapsed.toFloat() / total.toFloat()
                elapsed += timing.millis
            }
            return DwTraceProgressWeights(startFractions = out, measured = true)
        }
    }
}

fun dwTraceProgressSentence(
    progress: DwTraceProgress,
    stages: List<DwTraceStage> = DW_TRACE_STAGES,
): String {
    val index = stages.indexOfFirst { it.id == progress.stageId }
    return if (index < 0) {
        progress.label
    } else {
        "${progress.label}. Stage ${index + 1} of ${stages.size}."
    }
}

val DW_TRACE_KNOBS: Map<String, String> = linkedMapOf(
    "EDGE_SENSITIVITY" to "edge sensitivity",
    "STROKE_WIDTH" to "stroke width",
    "SIMPLIFY" to "simplify",
    "CORNER" to "corner threshold",
    "MIN_PATH_LENGTH" to "minimum path length",
    "MIN_BLOB_AREA" to "minimum blob area",
)

val DW_TRACE_KNOB_NAMES: List<String> = DW_TRACE_KNOBS.values.toList()

val DW_TRACE_KNOB_KEY_FOR_NAME: Map<String, String> = mapOf(
    "edge sensitivity" to "edge.sensitivity",
    "stroke width" to "output.strokeWidth",
    "simplify" to "output.simplify",
    "corner threshold" to "output.corner",
    "minimum path length" to "output.minPathLength",
    "minimum blob area" to "cleanup.minBlobArea",
)
