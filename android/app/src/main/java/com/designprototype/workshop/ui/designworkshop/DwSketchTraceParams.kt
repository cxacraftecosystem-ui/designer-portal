package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Immutable
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

const val DW_TRACE_GROUP_SOURCE = "Source"

const val DW_TRACE_GROUP_SHARPEN = "Sharpening"

const val DW_TRACE_GROUP_EDGES = "Edges"

const val DW_TRACE_GROUP_CLEANUP = "Cleanup"

const val DW_TRACE_GROUP_OUTPUT = "Output"

const val DW_TRACE_GROUP_EXPORT = "Export"

val DW_TRACE_GROUPS: List<String> = listOf(
    DW_TRACE_GROUP_SOURCE,
    DW_TRACE_GROUP_SHARPEN,
    DW_TRACE_GROUP_EDGES,
    DW_TRACE_GROUP_CLEANUP,
    DW_TRACE_GROUP_OUTPUT,
    DW_TRACE_GROUP_EXPORT,
)

enum class DwTraceTier {
    PRIMARY,

    ADVANCED,

    EXPORT,
}

enum class DwTraceScale {
    LINEAR,

    SQUARE,

    LOG,
}

const val DW_TRACE_SCALE_RECHECK: String =
    "grep -o \"minBlobArea: [0-9]*\"   styles.ts subjects.ts | sed 's/.*: //' | sort -n | uniq -c\n" +
        "grep -o \"minPathLength: [0-9]*\" styles.ts subjects.ts | sed 's/.*: //' | sort -n | uniq -c\n" +
        "grep -o \"xdogPhi: [0-9.]*\"      styles.ts             | sort -t' ' -k2 -n | uniq -c"

@Immutable
sealed class DwTraceControl {
    abstract val key: String
    abstract val label: String
    abstract val hint: String
    abstract val group: String
    abstract val tier: DwTraceTier

    open val handsetNote: String? = null
}

@Immutable
class DwTraceSlider(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: DwTraceTier,
    val min: Double,
    val max: Double,
    val step: Double,
    val scale: DwTraceScale = DwTraceScale.LINEAR,
    val integral: Boolean = false,
    override val handsetNote: String? = null,
) : DwTraceControl()

@Immutable
class DwTraceToggle(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: DwTraceTier,
    override val handsetNote: String? = null,
    val read: (DwTraceValues) -> Boolean? = { it.flag(key) },
    val patch: (Boolean) -> Map<String, DwTraceValue> = { mapOf(key to DwTraceValue.Flag(it)) },
) : DwTraceControl()

@Immutable
data class DwTraceChoiceOption(val value: String, val label: String)

@Immutable
class DwTraceChoice(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: DwTraceTier,
    val options: List<DwTraceChoiceOption>,
    override val handsetNote: String? = null,
) : DwTraceControl()

@Immutable
data class DwTraceNumberOption(val value: Double, val label: String, val note: String)

@Immutable
class DwTraceNumberChoice(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: DwTraceTier,
    val options: List<DwTraceNumberOption>,
    override val handsetNote: String? = null,
) : DwTraceControl()

fun DwTraceSlider.read(values: DwTraceValues): Double? = values.number(key)

fun DwTraceSlider.patch(value: Double): Map<String, DwTraceValue> {
    val snapped = snap(value)
    val out = if (integral) snapped.roundToLong().toDouble() else snapped
    return mapOf(key to DwTraceValue.Num(out))
}

fun DwTraceChoice.read(values: DwTraceValues): String? = values.choice(key)

fun DwTraceChoice.patch(value: String): Map<String, DwTraceValue> =
    mapOf(key to DwTraceValue.Choice(value))

fun DwTraceNumberChoice.read(values: DwTraceValues): Double? = values.number(key)

fun DwTraceNumberChoice.patch(value: Double): Map<String, DwTraceValue> =
    mapOf(key to DwTraceValue.Num(value.roundToLong().toDouble()))

fun DwTraceSlider.fractionOf(value: Double): Float {
    if (max <= min) return 0f
    val v = value.coerceIn(min, max)
    val t = when (scale) {
        DwTraceScale.LINEAR -> (v - min) / (max - min)
        DwTraceScale.SQUARE -> sqrt((v - min) / (max - min))
        DwTraceScale.LOG -> if (min <= 0.0) 0.0 else ln(v / min) / ln(max / min)
    }
    return t.coerceIn(0.0, 1.0).toFloat()
}

fun DwTraceSlider.valueAt(fraction: Float): Double {
    val t = fraction.toDouble().coerceIn(0.0, 1.0)
    val raw = when (scale) {
        DwTraceScale.LINEAR -> min + (max - min) * t
        DwTraceScale.SQUARE -> min + (max - min) * t * t
        DwTraceScale.LOG -> if (min <= 0.0) min else min * (max / min).pow(t)
    }
    return snap(raw)
}

fun DwTraceSlider.snap(value: Double): Double {
    if (step <= 0.0) return value.coerceIn(min, max)
    val steps = ((value - min) / step).roundToLong()
    val snapped = min + steps * step
    val factor = 10.0.pow(dwTraceDecimals(step))
    return (kotlin.math.round(snapped * factor) / factor).coerceIn(min, max)
}

fun dwTraceDecimals(step: Double): Int = when {
    step >= 1.0 -> 0
    step >= 0.1 -> 1
    else -> 2
}

fun dwTraceFormatValue(value: Double, step: Double): String {
    if (!value.isFinite()) return "0"
    if (step >= 1.0) return value.roundToInt().toString()
    val decimals = dwTraceDecimals(step)
    return String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
}

const val DW_TRACE_OPAQUE_WHITE: Double = 4294967295.0

val DW_TRACE_RESOLUTION: List<DwTraceNumberOption> = listOf(
    DwTraceNumberOption(
        value = 1024.0,
        label = "Fast",
        note = "1024 px. About a quarter of the work of Standard. Fine for a large, boldly drawn sheet.",
    ),
    DwTraceNumberOption(
        value = 2048.0,
        label = "Standard",
        note = "2048 px. The engine's own default, and what the portal uses unless it is told otherwise.",
    ),
    DwTraceNumberOption(
        value = 4096.0,
        label = "Detailed",
        note = "4096 px. Four times the work of Standard, for faint pencil and fine hatching. Slow on a phone.",
    ),
)

val DW_TRACE_SLIDERS: List<DwTraceSlider> = listOf(
    DwTraceSlider(
        key = "preprocess.denoiseStrength",
        label = "Noise reduction",
        hint = "How hard the chosen filter smooths before edges are looked for. Too much erases the thinnest lines.",
        group = DW_TRACE_GROUP_SOURCE,
        tier = DwTraceTier.ADVANCED,
        min = 0.0,
        max = 1.0,
        step = 0.01,
    ),
    DwTraceSlider(
        key = "preprocess.claheClip",
        label = "Local contrast",
        hint = "CLAHE clip limit. Higher pulls detail out of shadow and haze, and eventually amplifies grain with it.",
        group = DW_TRACE_GROUP_SOURCE,
        tier = DwTraceTier.ADVANCED,
        min = 1.0,
        max = 8.0,
        step = 0.1,
    ),
    DwTraceSlider(
        key = "preprocess.unsharpAmount",
        label = "Sharpen amount",
        hint = "How much of the difference between the photograph and a blurred copy of it is added back. 0 is off; past about 2 a pencil line grows a pale halo on both sides.",
        group = DW_TRACE_GROUP_SHARPEN,
        tier = DwTraceTier.PRIMARY,
        min = 0.0,
        max = 5.0,
        step = 0.05,
    ),
    DwTraceSlider(
        key = "preprocess.unsharpSigma",
        label = "Sharpen radius",
        hint = "The width of detail sharpening acts on, in pixels. Small values crisp the hairlines; large ones lift broad tonal edges and leave the hairlines alone.",
        group = DW_TRACE_GROUP_SHARPEN,
        tier = DwTraceTier.ADVANCED,
        min = 0.05,
        max = 8.0,
        step = 0.05,
    ),
    DwTraceSlider(
        key = "edge.sensitivity",
        label = "Edge sensitivity",
        hint = "More ink as it rises. The one control that means the same thing in every edge engine.",
        group = DW_TRACE_GROUP_EDGES,
        tier = DwTraceTier.PRIMARY,
        min = 0.0,
        max = 1.0,
        step = 0.01,
    ),
    DwTraceSlider(
        key = "edge.blurSigma",
        label = "Pre-blur",
        hint = "Gaussian applied before the detector. Raise it to ignore texture, lower it to keep hairlines.",
        group = DW_TRACE_GROUP_EDGES,
        tier = DwTraceTier.ADVANCED,
        min = 0.05,
        max = 8.0,
        step = 0.05,
    ),
    DwTraceSlider(
        key = "edge.flow.sigmaM",
        label = "Stroke length",
        hint = "How far the flow-based engine follows a stroke before letting it end. Only affects the flow engine.",
        group = DW_TRACE_GROUP_EDGES,
        tier = DwTraceTier.ADVANCED,
        min = 0.05,
        max = 12.0,
        step = 0.05,
    ),
    DwTraceSlider(
        key = "edge.xdogPhi",
        label = "Edge hardness",
        hint = "XDoG sharpness: 3 is soft graphite, 300 is a woodcut with no soft edge anywhere.",
        group = DW_TRACE_GROUP_EDGES,
        tier = DwTraceTier.ADVANCED,
        min = 0.1,
        max = 300.0,
        step = 0.1,
        scale = DwTraceScale.LOG,
    ),
    DwTraceSlider(
        key = "cleanup.minBlobArea",
        label = "Minimum speck",
        hint = "Ink blobs smaller than this many pixels are dust and are dropped.",
        group = DW_TRACE_GROUP_CLEANUP,
        tier = DwTraceTier.PRIMARY,
        min = 0.0,
        max = 1000.0,
        step = 1.0,
        scale = DwTraceScale.SQUARE,
        integral = true,
        handsetNote = "This is the courtyard-grit control: a sheet photographed on a table traces the " +
            "table's texture as dust unless something drops it.",
    ),
    DwTraceSlider(
        key = "cleanup.closeRadius",
        label = "Close gaps",
        hint = "Morphological closing radius: joins strokes that already nearly touch. Too large fuses neighbouring lines.",
        group = DW_TRACE_GROUP_CLEANUP,
        tier = DwTraceTier.ADVANCED,
        min = 0.0,
        max = 8.0,
        step = 1.0,
        integral = true,
    ),
    DwTraceSlider(
        key = "cleanup.maxGap",
        label = "Bridge reach",
        hint = "How far apart two stroke ends may be and still be joined. Ignored when gap bridging is off.",
        group = DW_TRACE_GROUP_CLEANUP,
        tier = DwTraceTier.ADVANCED,
        min = 0.0,
        max = 64.0,
        step = 1.0,
        integral = true,
    ),
    DwTraceSlider(
        key = "cleanup.pruneSpurs",
        label = "Prune spurs",
        hint = "Skeleton branches shorter than this are removed. 0 keeps every whisker thinning produced.",
        group = DW_TRACE_GROUP_CLEANUP,
        tier = DwTraceTier.ADVANCED,
        min = 0.0,
        max = 32.0,
        step = 1.0,
        integral = true,
    ),
    DwTraceSlider(
        key = "output.simplify",
        label = "Simplify",
        hint = "Douglas–Peucker tolerance in working pixels. Fewer nodes, straighter runs; 0 keeps every traced vertex.",
        group = DW_TRACE_GROUP_OUTPUT,
        tier = DwTraceTier.PRIMARY,
        min = 0.0,
        max = 8.0,
        step = 0.1,
    ),
    DwTraceSlider(
        key = "output.corner",
        label = "Keep corners",
        hint = "Higher keeps MORE corners: a vertex survives as a corner when its neighbours subtend a sharper angle than this.",
        group = DW_TRACE_GROUP_OUTPUT,
        tier = DwTraceTier.ADVANCED,
        min = 0.0,
        max = 180.0,
        step = 1.0,
    ),
    DwTraceSlider(
        key = "output.smoothIterations",
        label = "Smoothing passes",
        hint = "Chaikin passes over the polyline before curve fitting. Each one rounds the geometry a little more.",
        group = DW_TRACE_GROUP_OUTPUT,
        tier = DwTraceTier.ADVANCED,
        min = 0.0,
        max = 8.0,
        step = 1.0,
        integral = true,
    ),
    DwTraceSlider(
        key = "output.strokeWidth",
        label = "Stroke width",
        hint = "Painted width of the exported line, in document units.",
        group = DW_TRACE_GROUP_OUTPUT,
        tier = DwTraceTier.ADVANCED,
        min = 0.05,
        max = 8.0,
        step = 0.05,
    ),
    DwTraceSlider(
        key = "output.minPathLength",
        label = "Minimum path",
        hint = "Paths shorter than this are dropped.",
        group = DW_TRACE_GROUP_OUTPUT,
        tier = DwTraceTier.ADVANCED,
        min = 0.0,
        max = 200.0,
        step = 0.5,
        scale = DwTraceScale.SQUARE,
    ),
)

val DW_TRACE_TOGGLES: List<DwTraceToggle> = listOf(
    DwTraceToggle(
        key = "preprocess.invertInput",
        label = "Source is light-on-dark",
        hint = "Set for chalkboards, negatives and white ink on black, so “ink” stays the dark class everywhere downstream.",
        group = DW_TRACE_GROUP_SOURCE,
        tier = DwTraceTier.ADVANCED,
    ),
    DwTraceToggle(
        key = "preprocess.claheEnabled",
        label = "Equalise local contrast",
        hint = "Turn off for a source that is already evenly lit; CLAHE will otherwise amplify its grain.",
        group = DW_TRACE_GROUP_SOURCE,
        tier = DwTraceTier.ADVANCED,
    ),
    DwTraceToggle(
        key = "preprocess.perspectiveCorrect",
        label = "Rectify the page",
        hint = "Look for a document quadrilateral and flatten to it. For photographs of paper taken at an angle.",
        group = DW_TRACE_GROUP_SOURCE,
        tier = DwTraceTier.ADVANCED,
        handsetNote = "This is the engine's own automatic search for the sheet. If you have already " +
            "straightened this photograph with “$DW_RECTIFY_CARD_TITLE”, leave " +
            "this off — that plate is square to the page already.",
    ),
    DwTraceToggle(
        key = "cleanup.skeletonize",
        label = "Reduce ink to centrelines",
        hint = "Thins strokes to one pixel before tracing. Ignored in outline mode, where a skeleton would delete the boundary being traced.",
        group = DW_TRACE_GROUP_CLEANUP,
        tier = DwTraceTier.ADVANCED,
    ),
    DwTraceToggle(
        key = "cleanup.bridgeGaps",
        label = "Bridge stroke ends",
        hint = "Join nearby stroke ends that point at each other. Off keeps a line that stopped, stopped.",
        group = DW_TRACE_GROUP_CLEANUP,
        tier = DwTraceTier.ADVANCED,
    ),
    DwTraceToggle(
        key = "cleanup.removeBorderTouching",
        label = "Drop shapes touching the frame",
        hint = "Removes the drawing board, the scanner lid and the table edge, which a photograph almost always includes.",
        group = DW_TRACE_GROUP_CLEANUP,
        tier = DwTraceTier.ADVANCED,
    ),
    DwTraceToggle(
        key = "output.modulateWidth",
        label = "Vary width with the stroke",
        hint = "Samples the distance transform per node so thick-and-thin brushwork survives. Emitted as a filled outline, because SVG has no variable-width stroke.",
        group = DW_TRACE_GROUP_OUTPUT,
        tier = DwTraceTier.ADVANCED,
    ),
    DwTraceToggle(
        key = "output.fillClosed",
        label = "Fill closed shapes",
        hint = "Paints every closed region solid instead of outlining it. Outline mode with fill is the woodcut look.",
        group = DW_TRACE_GROUP_OUTPUT,
        tier = DwTraceTier.ADVANCED,
    ),
    DwTraceToggle(
        key = "output.background",
        label = "White background",
        hint = "Off exports a transparent background.",
        group = DW_TRACE_GROUP_EXPORT,
        tier = DwTraceTier.EXPORT,
        handsetNote = "The comparison above always shows the drawing on white so the wipe means " +
            "something. This decides the file only.",
        read = { it.present("output.background") },
        patch = { white ->
            mapOf(
                "output.background" to
                    if (white) DwTraceValue.Num(DW_TRACE_OPAQUE_WHITE) else DwTraceValue.Absent,
            )
        },
    ),
)

val DW_TRACE_CHOICES: List<DwTraceChoice> = listOf(
    DwTraceChoice(
        key = "edge.engine",
        label = "Edge engine",
        hint = "Which detector runs. They fail differently, which is why all of them exist.",
        group = DW_TRACE_GROUP_EDGES,
        tier = DwTraceTier.ADVANCED,
        options = listOf(
            DwTraceChoiceOption("FDOG", "Flow (long coherent strokes)"),
            DwTraceChoiceOption("XDOG", "XDoG (drawn look, tunable hardness)"),
            DwTraceChoiceOption("CANNY", "Canny (straight geometric edges)"),
            DwTraceChoiceOption("ADAPTIVE", "Adaptive threshold (already line art)"),
            DwTraceChoiceOption("LOG", "Laplacian (thinnest, most delicate)"),
        ),
        handsetNote = "Flow is the engine's default and by far the slowest on a phone — the spike " +
            "measured it at about six times the others at the same size. The panel says what a change " +
            "here will cost before you run it.",
    ),
    DwTraceChoice(
        key = "preprocess.denoise",
        label = "Noise filter",
        hint = "Bilateral keeps edges, median kills speckle, anisotropic flattens woven texture.",
        group = DW_TRACE_GROUP_SOURCE,
        tier = DwTraceTier.ADVANCED,
        options = listOf(
            DwTraceChoiceOption("NONE", "None"),
            DwTraceChoiceOption("BILATERAL", "Bilateral"),
            DwTraceChoiceOption("MEDIAN", "Median"),
            DwTraceChoiceOption("ANISOTROPIC", "Anisotropic"),
        ),
    ),
    DwTraceChoice(
        key = "matte.mode",
        label = "Background matte",
        hint = "Separates the subject from its background. Never applied unless you choose it, and the pipeline reports how much of the frame it removed.",
        group = DW_TRACE_GROUP_SOURCE,
        tier = DwTraceTier.ADVANCED,
        options = listOf(
            DwTraceChoiceOption("NONE", "None"),
            DwTraceChoiceOption("BORDER_FLOOD", "Flood from the border"),
            DwTraceChoiceOption("SALIENCY", "Keep the salient subject"),
        ),
        handsetNote = "The engine's default is None and stays there: a wrong matte deleting half of " +
            "somebody's artwork is the worst failure this can have.",
    ),
    DwTraceChoice(
        key = "output.vectorMode",
        label = "Vector mode",
        hint = "Centreline turns one stroke into one open path; outline turns one region into one closed path. The most consequential choice here.",
        group = DW_TRACE_GROUP_OUTPUT,
        tier = DwTraceTier.PRIMARY,
        options = listOf(
            DwTraceChoiceOption("CENTERLINE", "Centreline (one stroke, one path)"),
            DwTraceChoiceOption("OUTLINE", "Outline (one region, one closed path)"),
        ),
    ),
)

val DW_TRACE_NUMBER_CHOICES: List<DwTraceNumberChoice> = listOf(
    DwTraceNumberChoice(
        key = "preprocess.workingLongEdge",
        label = "Trace resolution",
        hint = "The long edge the trace runs at. Higher resolves finer detail and costs time; the vector is scaled back to the source size either way.",
        group = DW_TRACE_GROUP_SOURCE,
        tier = DwTraceTier.PRIMARY,
        options = DW_TRACE_RESOLUTION,
    ),
)

val DW_TRACE_CONTROLS: List<DwTraceControl> = DW_TRACE_GROUPS.flatMap { group ->
    val inGroup: (DwTraceControl) -> Boolean = { it.group == group }
    DW_TRACE_NUMBER_CHOICES.filter(inGroup) +
        DW_TRACE_CHOICES.filter(inGroup) +
        DW_TRACE_SLIDERS.filter(inGroup) +
        DW_TRACE_TOGGLES.filter(inGroup)
}

val DW_TRACE_PARAM_COUNT: Int = DW_TRACE_CONTROLS.size

val DW_TRACE_ADVANCED_COUNT: Int = DW_TRACE_CONTROLS.count { it.tier == DwTraceTier.ADVANCED }

val DW_TRACE_PRIMARY_KEYS: List<String> =
    DW_TRACE_CONTROLS.filter { it.tier == DwTraceTier.PRIMARY }.map { it.key }

const val DW_TRACE_DISCLOSURE_ACTION: String = "Show more options"

private fun dwTraceSettings(count: Int): String = if (count == 1) "1 setting" else "$count settings"

fun dwTraceAdvancedGroups(values: DwTraceValues): List<Pair<String, List<DwTraceControl>>> =
    DW_TRACE_GROUPS.mapNotNull { group ->
        val rows = DW_TRACE_CONTROLS.filter {
            it.group == group && it.tier == DwTraceTier.ADVANCED && it.key in values.keys
        }
        if (rows.isEmpty()) null else group to rows
    }

fun dwTraceAdvancedRevealed(values: DwTraceValues): Int =
    dwTraceAdvancedGroups(values).sumOf { it.second.size }

fun dwTraceDisclosureLabel(open: Boolean, revealed: Int, changedHidden: Int): String = if (open) {
    "Hide the other ${dwTraceSettings(revealed)}"
} else {
    DW_TRACE_DISCLOSURE_ACTION + " · ${dwTraceSettings(revealed)}" +
        (if (changedHidden > 0) " · $changedHidden changed" else "")
}

fun dwTraceDisclosureClickLabel(open: Boolean, revealed: Int): String = if (open) {
    "hide the other ${dwTraceSettings(revealed)}"
} else {
    "show the other ${dwTraceSettings(revealed)}"
}

fun dwTraceDisclosureState(open: Boolean): String = if (open) "Expanded" else "Collapsed"

fun dwTraceDisclosureBlurb(revealed: Int): String =
    "Inside: the ${dwTraceSettings(revealed)} that are not above, under the same headings as the " +
        "portal. Nothing in there is required — the trace runs on what is on screen now."

fun dwTraceHiddenChangedSentence(labels: List<String>): String? = when {
    labels.isEmpty() -> null
    labels.size == 1 -> "One setting that is not on screen has moved: ${labels.first()}."
    else ->
        "${labels.size} settings that are not on screen have moved: ${labels.joinToString(", ")}."
}

fun dwTraceChangedBehindDisclosure(before: DwTraceValues, after: DwTraceValues): List<String> =
    dwTraceChangedHiddenLabels(
        before = before,
        after = after,
        visible = setOf(DwTraceTier.PRIMARY, DwTraceTier.EXPORT),
    )

val DW_TRACE_CUT: Map<String, String> = mapOf(
    "cleanup.thinning" to
        "The engine's own documentation says Guo–Hall is the better kernel and the engine defaults to " +
        "Zhang–Suen. The difference cannot be seen at preview size and can be seen in the exported " +
        "file, and if the two clients defaulted differently one sheet of paper would produce two " +
        "different drawings. It stays at the engine's default on both.",
)

fun dwTraceInactiveReason(control: DwTraceControl, values: DwTraceValues): String? {
    val engine = values.choice("edge.engine")
    val outline = values.choice("output.vectorMode") == "OUTLINE"
    return when (control.key) {
        "edge.blurSigma" ->
            if (engine != null && engine != "CANNY") "Only the Canny engine reads this." else null
        "edge.flow.sigmaM" ->
            if (engine != null && engine != "FDOG") "Only the Flow engine reads this." else null
        "edge.xdogPhi" ->
            if (engine != null && engine != "XDOG" && engine != "FDOG") {
                "Only the XDoG and Flow engines read this."
            } else {
                null
            }
        "cleanup.skeletonize" ->
            if (outline) "Outline mode traces the edge of a region, so nothing is thinned." else null
        "cleanup.pruneSpurs" -> when {
            values.flag("cleanup.skeletonize") == false ->
                "“Reduce ink to centrelines” is off, so there is no skeleton to prune."
            outline -> "Outline mode traces the edge of a region, so nothing is thinned."
            else -> null
        }
        "cleanup.maxGap" ->
            if (values.flag("cleanup.bridgeGaps") == false) "“Bridge stroke ends” is off." else null
        "preprocess.claheClip" ->
            if (values.flag("preprocess.claheEnabled") == false) {
                "“Equalise local contrast” is off."
            } else {
                null
            }
        "preprocess.unsharpSigma" ->
            values.number("preprocess.unsharpAmount")
                ?.takeIf { it <= 0.0 }
                ?.let { "“Sharpen amount” is 0." }
        "preprocess.denoiseStrength" -> when (values.choice("preprocess.denoise")) {
            "NONE" -> "The noise filter is set to None."
            "MEDIAN" ->
                "The median filter works from a fixed radius the panel does not expose, not from this."
            else -> null
        }
        else -> null
    }
}

fun dwTraceMissingKeys(values: DwTraceValues): List<String> =
    DW_TRACE_CONTROLS.map { it.key }.filter { it !in values.keys }

private fun DwTraceControl.compareValue(values: DwTraceValues): Any? = when (this) {
    is DwTraceSlider -> read(values)
    is DwTraceToggle -> read(values)
    is DwTraceChoice -> read(values)
    is DwTraceNumberChoice -> read(values)
}

fun dwTraceChangedLabels(before: DwTraceValues, after: DwTraceValues): List<String> =
    DW_TRACE_CONTROLS.filter { it.compareValue(before) != it.compareValue(after) }.map { it.label }

fun dwTraceChangedHiddenLabels(
    before: DwTraceValues,
    after: DwTraceValues,
    visible: Set<DwTraceTier>,
): List<String> = DW_TRACE_CONTROLS
    .filter { it.tier !in visible && it.compareValue(before) != it.compareValue(after) }
    .map { it.label }

fun dwTraceOverwriteNotice(
    source: String,
    before: DwTraceValues,
    after: DwTraceValues,
): String? {
    val overwritten = dwTraceChangedLabels(before, after)
    if (overwritten.isEmpty()) return null
    val list = overwritten.joinToString(", ")
    return if (overwritten.size == 1) {
        "$source changed one setting: $list."
    } else {
        "$source changed ${overwritten.size} settings: $list."
    }
}

const val DW_TRACE_DEFAULT_SUBJECT_ID: String = "sketch"

val DW_TRACE_SUBJECT_FOR_CATEGORY: Map<String, String> = mapOf(
    "JEWELLERY" to "jewellery",
    "SAREE" to "textile",
    "DUPATTA_STOLE" to "textile",
    "YARDAGE" to "textile",
    "FLOOR_COVERING" to "textile",
    "TABLE_LINEN" to "textile",
    "DECORATIVE" to "carving",
)

fun dwTraceSubjectFor(category: String?): String =
    DW_TRACE_SUBJECT_FOR_CATEGORY[category?.trim().orEmpty()] ?: DW_TRACE_DEFAULT_SUBJECT_ID
