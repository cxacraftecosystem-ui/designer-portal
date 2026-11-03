package com.designprototype.workshop.ui.designworkshop

import com.offlinetracer.pipeline.Knobs
import com.offlinetracer.pipeline.StylePreset
import com.offlinetracer.pipeline.Styles
import com.offlinetracer.pipeline.SubjectPreset
import com.offlinetracer.pipeline.Subjects
import com.offlinetracer.pipeline.TraceParams

internal const val DW_TRACE_SUBJECT_COMPOUNDS_NOTE: String =
    "Tapping again compounds; re-pick the style to reset."

internal val DW_TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE: List<String> =
    listOf("wood-carving", "stone-carving", "metalwork")

internal val DW_TRACE_SUBJECTS_ONLY_ON_THE_PORTAL: List<String> = listOf("carving")

internal val DW_TRACE_SUBJECT_DIVERGENCE_NOTES: Map<String, String> = linkedMapOf(
    "wood-carving" to DW_TRACE_SPLIT_CARVING_NOTE,
    "stone-carving" to DW_TRACE_SPLIT_CARVING_NOTE,
    "metalwork" to
        "Tuned for engraved and embossed metal.",
)

private const val DW_TRACE_SPLIT_CARVING_NOTE: String =
    "Wood and stone carving have separate settings."

internal fun dwTraceKotlinPresetTables(): DwTracePresetTables = DW_TRACE_KOTLIN_TABLES

private val DW_TRACE_KOTLIN_TABLES: DwTracePresetTables = DwTracePresetTables(
    styles = Styles.ALL.map(::dwTraceKotlinStyleRow),
    subjects = Subjects.ALL.map(::dwTraceKotlinSubjectRow),
)

private fun dwTraceKotlinStyleRow(style: StylePreset): DwTracePreset = DwTracePreset(
    id = style.id,
    name = style.name,
    description = style.description,
    group = style.group,
)

private fun dwTraceKotlinSubjectRow(subject: SubjectPreset): DwTracePreset = DwTracePreset(
    id = subject.id,
    name = subject.name,
    description = buildString {
        append(subject.hint)
        append(' ')
        append(DW_TRACE_SUBJECT_COMPOUNDS_NOTE)
        DW_TRACE_SUBJECT_DIVERGENCE_NOTES[subject.id]?.let {
            append(' ')
            append(it)
        }
    },
    group = "",
)

@Suppress("UNUSED_PARAMETER")
internal fun dwTraceKotlinApplyStyle(base: TraceParams, styleId: String): TraceParams {
    val preset = Styles.byId(styleId)
        ?: throw IllegalArgumentException(dwTraceKotlinNoSuchStyleSentence(styleId))
    return preset.params.sanitized()
}

internal fun dwTraceKotlinApplySubject(base: TraceParams, subjectId: String): TraceParams {
    val preset = Subjects.byId(subjectId)
        ?: throw IllegalArgumentException(dwTraceKotlinNoSuchSubjectSentence(subjectId))
    val adjusted = dwTraceKotlinPreserveIdentity(base, preset.adjust(base))
    return Knobs.restore(base, adjusted, base.auto.handTuned).sanitized()
}

private fun dwTraceKotlinPreserveIdentity(before: TraceParams, after: TraceParams): TraceParams = after.copy(
    edge = after.edge.copy(engine = before.edge.engine),
    output = after.output.copy(
        vectorMode = before.output.vectorMode,
        fillClosed = before.output.fillClosed,
    ),
    styleId = before.styleId,
    auto = before.auto,
)

internal fun dwTraceKotlinNoSuchStyleSentence(styleId: String): String =
    "There is no style called \"${styleId.trim()}\"."

internal fun dwTraceKotlinNoSuchSubjectSentence(subjectId: String): String {
    val bare = "There is no subject called \"${subjectId.trim()}\"."
    if (subjectId.trim() !in DW_TRACE_SUBJECTS_ONLY_ON_THE_PORTAL) return bare
    return bare + " Choose “Wood carving” or “Stone carving”."
}

internal fun dwTraceKotlinApplyStyle(base: DwTraceValues, styleId: String): DwTraceValues =
    dwTraceValuesOfParams(dwTraceKotlinApplyStyle(dwTraceParamsOf(base), styleId))

internal fun dwTraceKotlinApplySubject(base: DwTraceValues, subjectId: String): DwTraceValues =
    dwTraceValuesOfParams(dwTraceKotlinApplySubject(dwTraceParamsOf(base), subjectId))
