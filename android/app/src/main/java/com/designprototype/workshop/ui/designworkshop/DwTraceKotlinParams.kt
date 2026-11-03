package com.designprototype.workshop.ui.designworkshop

import com.offlinetracer.pipeline.AutoMode
import com.offlinetracer.pipeline.AutoParams
import com.offlinetracer.pipeline.CleanupParams
import com.offlinetracer.pipeline.DenoiseMode
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.EdgeParams
import com.offlinetracer.pipeline.FlowSettings
import com.offlinetracer.pipeline.MatteMode
import com.offlinetracer.pipeline.MatteParams
import com.offlinetracer.pipeline.OutputParams
import com.offlinetracer.pipeline.PreprocessParams
import com.offlinetracer.pipeline.ThinningMode
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.pipeline.VectorModeParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val dwTraceParamsJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

internal fun dwTraceWireOf(params: TraceParams): String {
    val clean = params.sanitized()
    val root = dwTraceParamsJson.encodeToJsonElement(TraceParams.serializer(), clean).jsonObject
    val output = root.getValue("output").jsonObject.toMutableMap()
    output["strokeColor"] = JsonPrimitive(dwTraceUnsignedColour(clean.output.strokeColor))
    clean.output.background?.let { output["background"] = JsonPrimitive(dwTraceUnsignedColour(it)) }
    val out = root.toMutableMap()
    out["output"] = JsonObject(output)
    return dwTraceParamsJson.encodeToString(JsonObject.serializer(), JsonObject(out))
}

internal fun dwTraceValuesOfParams(params: TraceParams): DwTraceValues =
    dwTraceValuesOf(dwTraceWireOf(params))

internal fun dwTraceFlattenParams(params: TraceParams): Map<String, DwTraceValue> {
    val values = dwTraceValuesOfParams(params)
    return values.keys.associateWith { key -> values[key]!! }
}

internal val DW_TRACE_LEAF_KEYS: List<String> = dwTraceValuesOfParams(TraceParams()).keys.toList()

private val dwTraceLeafKeySet: Set<String> = DW_TRACE_LEAF_KEYS.toSet()

internal fun dwTraceIsLeafKey(key: String): Boolean = key in dwTraceLeafKeySet

internal fun dwTraceUnknownLeafKeys(keys: Iterable<String>): List<String> =
    keys.filterNot { it in dwTraceLeafKeySet }

internal fun dwTraceApplyLeaves(
    base: TraceParams,
    patch: Map<String, DwTraceValue>,
): TraceParams {
    if (patch.isEmpty()) return base.sanitized()
    val p = DwTraceLeaves(patch)
    return TraceParams(
        preprocess = dwTracePreprocessOf(base.preprocess, p),
        matte = dwTraceMatteOf(base.matte, p),
        edge = dwTraceEdgeOf(base.edge, p),
        cleanup = dwTraceCleanupOf(base.cleanup, p),
        output = dwTraceOutputOf(base.output, p),
        auto = dwTraceAutoOf(base.auto, p),
        styleId = p.text(DW_TRACE_STYLE_ID_KEY, base.styleId),
    ).sanitized()
}

internal fun dwTraceNestLeaves(leaves: Map<String, DwTraceValue>): TraceParams =
    dwTraceApplyLeaves(TraceParams(), leaves)

internal fun dwTraceParamsOfWire(wire: String): TraceParams {
    val root = runCatching { dwTraceParamsJson.parseToJsonElement(wire) }.getOrNull() as? JsonObject
        ?: throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "a parameter tree that is not a JSON object",
        )
    val decoded = runCatching {
        dwTraceParamsJson.decodeFromJsonElement(TraceParams.serializer(), dwTraceSignColours(root))
    }.getOrElse { cause ->
        throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "a parameter tree this app could not read",
            cause,
        )
    }
    return decoded.sanitized()
}

internal fun dwTraceParamsOf(values: DwTraceValues): TraceParams = dwTraceParamsOfWire(values.wire)

private fun dwTracePreprocessOf(base: PreprocessParams, p: DwTraceLeaves) = PreprocessParams(
    autoOrient = p.flag("preprocess.autoOrient", base.autoOrient),
    perspectiveCorrect = p.flag("preprocess.perspectiveCorrect", base.perspectiveCorrect),
    workingLongEdge = p.int("preprocess.workingLongEdge", base.workingLongEdge),
    denoise = p.pick("preprocess.denoise", base.denoise, DenoiseMode.BILATERAL, DenoiseMode.entries),
    denoiseStrength = p.num("preprocess.denoiseStrength", base.denoiseStrength),
    medianRadius = p.int("preprocess.medianRadius", base.medianRadius),
    claheEnabled = p.flag("preprocess.claheEnabled", base.claheEnabled),
    claheClip = p.num("preprocess.claheClip", base.claheClip),
    claheTiles = p.int("preprocess.claheTiles", base.claheTiles),
    brightness = p.num("preprocess.brightness", base.brightness),
    contrast = p.num("preprocess.contrast", base.contrast),
    gamma = p.num("preprocess.gamma", base.gamma),
    unsharpAmount = p.num("preprocess.unsharpAmount", base.unsharpAmount),
    unsharpSigma = p.num("preprocess.unsharpSigma", base.unsharpSigma),
    invertInput = p.flag("preprocess.invertInput", base.invertInput),
)

private fun dwTraceMatteOf(base: MatteParams, p: DwTraceLeaves) = MatteParams(
    mode = p.pick("matte.mode", base.mode, MatteMode.NONE, MatteMode.entries),
    tolerance = p.num("matte.tolerance", base.tolerance),
    feather = p.num("matte.feather", base.feather),
    threshold = p.num("matte.threshold", base.threshold),
)

private fun dwTraceFlowOf(base: FlowSettings, p: DwTraceLeaves) = FlowSettings(
    tensorSigma = p.num("edge.flow.tensorSigma", base.tensorSigma),
    etfIterations = p.int("edge.flow.etfIterations", base.etfIterations),
    etfRadius = p.int("edge.flow.etfRadius", base.etfRadius),
    sigmaC = p.num("edge.flow.sigmaC", base.sigmaC),
    sigmaM = p.num("edge.flow.sigmaM", base.sigmaM),
    tau = p.num("edge.flow.tau", base.tau),
    fdogIterations = p.int("edge.flow.fdogIterations", base.fdogIterations),
)

private fun dwTraceEdgeOf(base: EdgeParams, p: DwTraceLeaves) = EdgeParams(
    engine = p.pick("edge.engine", base.engine, EdgeEngine.FDOG, EdgeEngine.entries),
    sensitivity = p.num("edge.sensitivity", base.sensitivity),
    blurSigma = p.num("edge.blurSigma", base.blurSigma),
    cannyLow = p.num("edge.cannyLow", base.cannyLow),
    cannyHigh = p.num("edge.cannyHigh", base.cannyHigh),
    dogSigma = p.num("edge.dogSigma", base.dogSigma),
    dogK = p.num("edge.dogK", base.dogK),
    dogTau = p.num("edge.dogTau", base.dogTau),
    xdogEpsilon = p.num("edge.xdogEpsilon", base.xdogEpsilon),
    xdogPhi = p.num("edge.xdogPhi", base.xdogPhi),
    flow = dwTraceFlowOf(base.flow, p),
    adaptiveRadius = p.int("edge.adaptiveRadius", base.adaptiveRadius),
    adaptiveC = p.num("edge.adaptiveC", base.adaptiveC),
    useSauvola = p.flag("edge.useSauvola", base.useSauvola),
    logSigma = p.num("edge.logSigma", base.logSigma),
    logSlope = p.num("edge.logSlope", base.logSlope),
    modelId = p.text("edge.modelId", base.modelId),
)

private fun dwTraceCleanupOf(base: CleanupParams, p: DwTraceLeaves) = CleanupParams(
    minBlobArea = p.int("cleanup.minBlobArea", base.minBlobArea),
    removeIsolated = p.flag("cleanup.removeIsolated", base.removeIsolated),
    closeRadius = p.int("cleanup.closeRadius", base.closeRadius),
    openRadius = p.int("cleanup.openRadius", base.openRadius),
    bridgeGaps = p.flag("cleanup.bridgeGaps", base.bridgeGaps),
    maxGap = p.int("cleanup.maxGap", base.maxGap),
    maxBridgeAngle = p.num("cleanup.maxBridgeAngle", base.maxBridgeAngle),
    skeletonize = p.flag("cleanup.skeletonize", base.skeletonize),
    thinning = p.pick(
        "cleanup.thinning",
        base.thinning,
        ThinningMode.ZHANG_SUEN,
        ThinningMode.entries,
    ),
    pruneSpurs = p.int("cleanup.pruneSpurs", base.pruneSpurs),
    fillHolesUpTo = p.int("cleanup.fillHolesUpTo", base.fillHolesUpTo),
    keepLargest = p.int("cleanup.keepLargest", base.keepLargest),
    removeBorderTouching = p.flag("cleanup.removeBorderTouching", base.removeBorderTouching),
)

private fun dwTraceOutputOf(base: OutputParams, p: DwTraceLeaves) = OutputParams(
    vectorMode = p.pick(
        "output.vectorMode",
        base.vectorMode,
        VectorModeParam.CENTERLINE,
        VectorModeParam.entries,
    ),
    simplify = p.num("output.simplify", base.simplify),
    fitError = p.num("output.fitError", base.fitError),
    corner = p.num("output.corner", base.corner),
    smoothIterations = p.int("output.smoothIterations", base.smoothIterations),
    strokeWidth = p.num("output.strokeWidth", base.strokeWidth),
    modulateWidth = p.flag("output.modulateWidth", base.modulateWidth),
    widthScale = p.num("output.widthScale", base.widthScale),
    minPathLength = p.num("output.minPathLength", base.minPathLength),
    strokeColor = p.colour("output.strokeColor", base.strokeColor),
    background = p.colourOrNull("output.background", base.background),
    fillClosed = p.flag("output.fillClosed", base.fillClosed),
)

private fun dwTraceAutoOf(base: AutoParams, p: DwTraceLeaves) = AutoParams(
    mode = p.pick("auto.mode", base.mode, AutoMode.SUGGEST, AutoMode.entries),
    subjectId = p.text("auto.subjectId", base.subjectId),
    handTuned = base.handTuned,
    minConfidence = p.num("auto.minConfidence", base.minConfidence),
    allowMatte = p.flag("auto.allowMatte", base.allowMatte),
    allowCrop = p.flag("auto.allowCrop", base.allowCrop),
)

private class DwTraceLeaves(private val patch: Map<String, DwTraceValue>) {

    fun flag(key: String, base: Boolean): Boolean = when (val leaf = patch[key]) {
        null -> base
        is DwTraceValue.Flag -> leaf.value
        else -> wrong(key, leaf, "a true/false flag")
    }

    fun num(key: String, base: Float): Float = when (val leaf = patch[key]) {
        null -> base
        is DwTraceValue.Num -> finite(key, leaf.value).toFloat()
        else -> wrong(key, leaf, "a number")
    }

    fun int(key: String, base: Int): Int = when (val leaf = patch[key]) {
        null -> base
        is DwTraceValue.Num -> finite(key, leaf.value).toInt()
        else -> wrong(key, leaf, "a whole number")
    }

    fun text(key: String, base: String): String = when (val leaf = patch[key]) {
        null -> base
        is DwTraceValue.Choice -> leaf.value
        else -> wrong(key, leaf, "a name")
    }

    fun <T : Enum<T>> pick(key: String, base: T, unknown: T, all: List<T>): T =
        when (val leaf = patch[key]) {
            null -> base
            is DwTraceValue.Choice -> all.firstOrNull { it.name == leaf.value } ?: unknown
            else -> wrong(key, leaf, "a name")
        }

    fun colour(key: String, base: Int): Int = when (val leaf = patch[key]) {
        null -> base
        is DwTraceValue.Num -> dwTraceSignedColour(finite(key, leaf.value))
        else -> wrong(key, leaf, "a packed colour")
    }

    fun colourOrNull(key: String, base: Int?): Int? = when (val leaf = patch[key]) {
        null -> base
        DwTraceValue.Absent -> null
        is DwTraceValue.Num -> dwTraceSignedColour(finite(key, leaf.value))
        else -> wrong(key, leaf, "a packed colour or nothing at all")
    }

    private fun finite(key: String, value: Double): Double {
        if (!value.isFinite()) {
            throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                "$key was set to $value, which is not a number the engine can be sent",
            )
        }
        return value
    }

    private fun wrong(key: String, leaf: DwTraceValue, wanted: String): Nothing =
        throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "$key was sent as ${dwTraceKindOf(leaf)}, and it holds $wanted",
        )
}

private fun dwTraceKindOf(leaf: DwTraceValue): String = when (leaf) {
    is DwTraceValue.Num -> "a number"
    is DwTraceValue.Flag -> "a true/false flag"
    is DwTraceValue.Choice -> "a name"
    DwTraceValue.Absent -> "nothing at all"
}

private fun dwTraceUnsignedColour(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

private fun dwTraceSignedColour(packed: Double): Int = packed.toLong().toInt()

private val DW_TRACE_COLOUR_LEAVES: List<String> = listOf("strokeColor", "background")

private fun dwTraceSignColours(root: JsonObject): JsonObject {
    val output = root["output"] as? JsonObject ?: return root
    val fixed = output.toMutableMap()
    for (key in DW_TRACE_COLOUR_LEAVES) {
        val leaf = fixed[key] as? JsonPrimitive ?: continue
        if (leaf is JsonNull || leaf.isString) continue
        val packed = leaf.content.toDoubleOrNull() ?: continue
        fixed[key] = JsonPrimitive(dwTraceSignedColour(packed))
    }
    val out = root.toMutableMap()
    out["output"] = JsonObject(fixed)
    return JsonObject(out)
}
