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

/**
 * **THE TRANSLATION BETWEEN THE PANEL'S FLAT MAP AND THE VENDORED ENGINE'S NESTED TREE.**
 *
 * ── WHAT THIS FILE IS ─────────────────────────────────────────────────────────────────────────
 *
 * `DwSketchTraceEngine.kt` declares [DwTraceValues]: a flat `Map<String, DwTraceValue>` keyed by dot
 * paths (`edge.flow.sigmaM`) plus the sanitised tree carried whole as [DwTraceValues.wire]. The
 * vendored Kotlin engine declares `com.offlinetracer.pipeline.TraceParams`: a tree of seven records,
 * one of them nested a second level (`edge.flow`). This file is the only place the two meet, and it
 * is what a Kotlin-backed [DwTraceRuntime] is built on top of. It imports no Android and no Compose,
 * for the reason `DwSketchTraceWire.kt`'s header gives: `app/build.gradle.kts` declares JUnit 4 and
 * no Robolectric, so anything touching `android.*` is by construction code no unit test here can
 * reach — and a conversion nobody can test is exactly the kind that is quietly wrong for a year.
 *
 * ── THE KEY SPELLINGS ARE THE WEB ENGINE'S, AND THAT IS CHECKED RATHER THAN INTENDED ──────────
 *
 * `frontend/lib/trace/engine/params.ts` is the authority: the two clients' saved parameter trees have
 * to open on each other, so a key spelled `preprocess.workingLongEdge` here and `preprocess.longEdge`
 * there would be a project the portal can save and the handset cannot read. `DwTraceKotlinParamsTest`
 * PARSES that TypeScript file, derives the dotted keys from its own `readonly` declarations, and
 * asserts [DW_TRACE_LEAF_KEYS] equals them — same names, same order — the way
 * `DwSketchTraceParamsTest` already pins this handset's control table against the portal's.
 *
 * ── HOW MANY LEAVES, AND THE TWO COMMANDS THAT SAY SO ─────────────────────────────────────────
 *
 * `TraceParams` has **74 leaves**. Counted, on 2026-08-27, by listing the constructor properties of
 * the seven `@Serializable` records and subtracting the eight that are not leaves — the six sub-trees
 * of `TraceParams`, `EdgeParams.flow`, and `Knobs.ALL`, which is a label table and not a parameter:
 *
 *     cd android/core-pipeline/src/main/java/com/offlinetracer/pipeline
 *     grep "^    val " Params.kt | grep -vE ": (PreprocessParams|MatteParams|EdgeParams|CleanupParams|OutputParams|AutoParams|FlowSettings|List<String>) " | wc -l
 *     -> 74
 *
 * The web agrees, from its own declarations — 81 `readonly` lines less the same seven structural ones
 * (it has no `Knobs.ALL`):
 *
 *     grep -c "^  readonly " frontend/lib/trace/engine/params.ts        -> 81
 *
 * **[DW_TRACE_LEAF_KEYS] holds 73 of those 74, and the missing one is named below.** That number is
 * not written down anywhere in this file: the list is DERIVED by flattening the engine's own default
 * tree, so a re-vendor that adds a leaf adds a key here with nobody editing anything.
 *
 * ── THE ONE LEAF WITH NO DOTTED KEY: `auto.handTuned` ─────────────────────────────────────────
 *
 * It is a `Set<String>` — the editor's visible labels for the knobs the user moved by hand, which
 * `Knobs.restore` puts back after auto-detection has overwritten them (`Params.kt:341-363`). Three
 * facts decide its treatment and none of them is a preference:
 *
 *  1. [DwTraceValue] has four cases — `Num`, `Flag`, `Choice`, `Absent` — and none of them is a list.
 *     Adding a fifth would be rewriting the seam this file is written against.
 *  2. `dwTraceFlatten` in `DwSketchTraceWire.kt` — the flattener the JavaScript route has shipped
 *     with — SKIPS arrays outright and says so: "ARRAYS ARE NOT LEAVES AND ARE SKIPPED. There is
 *     exactly one today". If this file invented a key for it, the two runtimes would hand the same
 *     panel two different leaf sets, and `dwTraceMissingKeys` would start reporting a phantom.
 *  3. Nothing is lost. It survives in [DwTraceValues.wire] with everything else, and
 *     [dwTraceApplyLeaves] carries it across from `base` untouched, so a patch can never drop it.
 *
 * So: 74 leaves, 73 dotted keys, one array carried whole. Stated here rather than discovered.
 *
 * ── COLOURS CROSS UNSIGNED, WHICH IS THE ONE PLACE THE TWO ENGINES SPELL A VALUE DIFFERENTLY ──
 *
 * `output.strokeColor` is a Kotlin `Int` and its default `0xFF000000.toInt()` is **-16777216**.
 * JavaScript has no signed 32-bit integer, so `params.ts:190-194` ends `colour()` with `>>> 0` and
 * the same black is **4278190080** in every tree the portal has ever written — which is also what
 * `DW_TRACE_OPAQUE_WHITE = 4294967295.0` in `DwSketchTraceParams.kt` already assumes, because the
 * "White background" toggle patches that number. A tree that carried -16777216 would therefore be a
 * tree the portal reads as a nearly-transparent dark blue.
 *
 * So the two colour leaves are converted at the boundary, in both directions, and NOWHERE ELSE:
 * `argb.toLong() and 0xFFFFFFFF` going out, `value.toLong().toInt()` coming back. Every other leaf is
 * whatever kotlinx serialisation makes of it.
 *
 * ── SANITISING IS THE ENGINE'S, ALWAYS, AND THERE IS NO CLAMP TABLE HERE ──────────────────────
 *
 * `DwSketchTraceEngine.kt` states the rule at its own head — "No Kotlin clamp table, ever" — because
 * several of the engine's bounds encode a measured incident rather than a taste. Every function below
 * that produces a `TraceParams` ends in `.sanitized()`, which `Params.kt:596-605` documents
 * idempotent precisely so a UI may run it on every slider tick without disagreeing with the pipeline.
 * The only arithmetic this file does to a number is the truncation an integer leaf needs on the way
 * in, and that mirrors the web's `Math.trunc` (`params.ts:167-172`) rather than inventing a rounding.
 *
 * ── UNKNOWN KEYS ARE IGNORED, DELIBERATELY ───────────────────────────────────────────────────
 *
 * A patch or a saved project from an older build may name a leaf that has been renamed or removed.
 * [dwTraceApplyLeaves] simply does not look for keys it does not know, so such an entry has no effect
 * and nothing throws; [dwTraceParamsOfWire] does the same for the tree form, via `ignoreUnknownKeys`.
 * That is what the web does too — its `withOverrides` spreads the stale property onto the merged
 * object and `sanitizeTraceParams` drops it, silently, on the next line. Use [dwTraceUnknownLeafKeys]
 * where the surface wants to SAY that keys were ignored; this layer will not decide that for it.
 *
 * What is NOT ignored is a value of the wrong shape for a key this file does know — a `Choice` where
 * a number belongs, or a `NaN`. Those are refused with the same exception and the same sentence
 * `dwTracePatchJson` already refuses a non-finite number with, and for its reason: "the slider moves,
 * the trace runs, and the parameter the designer changed is the one that did not change". The web's
 * sanitiser would instead reset such a leaf to its factory default. Refusing is the better of the two
 * — it can only be reached by a caller bug, since every patch in this app is built by
 * `DW_TRACE_CONTROLS` with the leaf's own kind — and the divergence is recorded here rather than
 * found later.
 */

/**
 * The reader and writer for the tree form.
 *
 * **`encodeDefaults = true` IS LOAD-BEARING AND NOT A STYLE CHOICE.** kotlinx omits any property
 * equal to its default, so without it `Json.encodeToString(TraceParams())` is `{}` — the panel would
 * come up with no leaves at all and every control would report itself missing.
 *
 * `ignoreUnknownKeys` is what lets a tree saved by a newer build load here; `coerceInputValues` is
 * what makes an unknown ENUM member fall back to the property's default instead of throwing, which is
 * exactly `enumOf`'s contract on the web (`params.ts:178-188`: "An unrecognised enum string falls
 * back rather than propagating into a `switch` with no arm"). Both are the difference between an old
 * project opening and an old project crashing.
 */
private val dwTraceParamsJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/* ────────────────────────────────────────────────────────────────────────────
 * Tree -> flat
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sanitised tree as JSON, in the spelling the portal writes.
 *
 * This string is what goes into [DwTraceValues.wire] and therefore into anything persisted, so it
 * carries the two colour leaves unsigned — see the file header. Key ORDER is the engine's own
 * declaration order, because kotlinx encodes constructor properties in order and every rebuild below
 * copies through a `LinkedHashMap`.
 */
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

/**
 * The engine's tree, as the panel reads it.
 *
 * **THE FLATTENING IS `dwTraceValuesOf`'s, NOT THIS FILE'S**, and that is the whole point of routing
 * through a JSON string rather than walking the records by hand. That function is what the JavaScript
 * route already uses, so both runtimes classify a leaf as `Num`/`Flag`/`Choice`/`Absent` by one walk,
 * skip the one array by one rule, and map `output.background: null` to [DwTraceValue.Absent] by one
 * line. A second flattener written here would agree with it on the day it was written and would be
 * the only thing able to disagree with it afterwards — and it would disagree silently, because a leaf
 * classified as the wrong kind does not fail: it draws a blank control.
 */
internal fun dwTraceValuesOfParams(params: TraceParams): DwTraceValues =
    dwTraceValuesOf(dwTraceWireOf(params))

/** The flat map on its own, for a caller that wants the leaves without the tree beside them. */
internal fun dwTraceFlattenParams(params: TraceParams): Map<String, DwTraceValue> {
    val values = dwTraceValuesOfParams(params)
    return values.keys.associateWith { key -> values[key]!! }
}

/**
 * Every dotted key, in the engine's own declaration order.
 *
 * DERIVED FROM THE ENGINE'S DEFAULT TREE, never transcribed — so this list cannot fall behind a
 * re-vendor, and its size is a measurement rather than a claim. See the file header for the
 * arithmetic against `TraceParams`' 74 leaves.
 */
internal val DW_TRACE_LEAF_KEYS: List<String> = dwTraceValuesOfParams(TraceParams()).keys.toList()

private val dwTraceLeafKeySet: Set<String> = DW_TRACE_LEAF_KEYS.toSet()

/** True for a key [dwTraceApplyLeaves] will act on. */
internal fun dwTraceIsLeafKey(key: String): Boolean = key in dwTraceLeafKeySet

/**
 * The keys in [keys] this build would ignore, in the order given.
 *
 * For a surface that wants to SAY that a stale saved project named leaves that no longer exist —
 * `dwTraceMissingKeys` is the mirror of this, and between them they cover both directions of a
 * version skew. `auto.handTuned` is reported here, correctly: it is a leaf of the tree and it is not
 * a key of the flat map.
 */
internal fun dwTraceUnknownLeafKeys(keys: Iterable<String>): List<String> =
    keys.filterNot { it in dwTraceLeafKeySet }

/* ────────────────────────────────────────────────────────────────────────────
 * Flat -> tree
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The engine's own `withOverrides(base, patch)`: [patch] laid over [base], then `sanitized()`.
 *
 * One leaf per line below, each naming its own dotted key beside the field it fills, because that
 * adjacency is the only thing a reader can check this table against. A key not in [patch] leaves
 * [base] alone; a key [patch] carries that this file does not know is ignored (file header).
 *
 * `auto.handTuned` is carried across from [base] untouched — it has no dotted key, so a patch cannot
 * name it and cannot lose it.
 */
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

/**
 * A whole flat map read as a tree, i.e. [dwTraceApplyLeaves] over the engine's factory defaults.
 *
 * This is the inverse of [dwTraceFlattenParams] for every one of the dotted keys:
 * `dwTraceFlattenParams(dwTraceNestLeaves(v)) == v` for any map `v` that a flatten produced.
 */
internal fun dwTraceNestLeaves(leaves: Map<String, DwTraceValue>): TraceParams =
    dwTraceApplyLeaves(TraceParams(), leaves)

/**
 * A tree written by either client, read back.
 *
 * Takes the portal's JSON as readily as this app's: unknown keys are ignored, an unknown enum member
 * falls back to that property's default, and both colour leaves are converted from the portal's
 * unsigned spelling. A tree that is not JSON at all, or is JSON this cannot read, is REFUSED with the
 * sentence `dwTraceValuesOf` refuses one with — not repaired into something plausible.
 */
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

/**
 * The tree behind a [DwTraceValues], via [DwTraceValues.wire] rather than via its leaves.
 *
 * THE WIRE AND NOT THE MAP, for the reason `DwSketchTraceEngine.kt` gives where it declares the
 * class: the wire is the authority and the map is a reading convenience. Going through the map would
 * drop `auto.handTuned`, which has no key, and would drop any leaf a newer engine has added that this
 * build has never heard of — both of which the wire carries through untouched.
 */
internal fun dwTraceParamsOf(values: DwTraceValues): TraceParams = dwTraceParamsOfWire(values.wire)

/* ────────────────────────────────────────────────────────────────────────────
 * The seven records, one leaf per line
 * ──────────────────────────────────────────────────────────────────────────── */

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

/**
 * The five `auto` leaves a flat map can name, plus the one it cannot.
 *
 * `handTuned` comes from [base] and only from [base]; see the file header for why it has no key.
 */
private fun dwTraceAutoOf(base: AutoParams, p: DwTraceLeaves) = AutoParams(
    mode = p.pick("auto.mode", base.mode, AutoMode.SUGGEST, AutoMode.entries),
    subjectId = p.text("auto.subjectId", base.subjectId),
    handTuned = base.handTuned,
    minConfidence = p.num("auto.minConfidence", base.minConfidence),
    allowMatte = p.flag("auto.allowMatte", base.allowMatte),
    allowCrop = p.flag("auto.allowCrop", base.allowCrop),
)

/* ────────────────────────────────────────────────────────────────────────────
 * Reading one leaf
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One patch, read leaf by leaf. A key that is absent answers the base value, so a one-key patch
 * changes one field and nothing else.
 */
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

    /**
     * TRUNCATION TOWARD ZERO, matching `Math.trunc` in the web's integer clamp
     * (`params.ts:167-172`) — which `DwSketchTraceParams.kt:217-225` already had to account for on
     * the slider side, because a leaf sent as 2047.9999 comes back 2047 and the readout settles one
     * below the thumb. Kotlin's `Double.toInt()` truncates toward zero and saturates at the `Int`
     * bounds; every integer leaf's legal range is far inside those, so the engine's own clamp
     * afterwards lands on the same value the web's does.
     */
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

    /**
     * An enum leaf. A name that is not a member of [all] falls back to [unknown], which is that
     * property's factory default — the web's `enumOf` contract, not this file's invention, and the
     * reason a tree from a newer build naming an engine this one has never heard of still opens.
     */
    fun <T : Enum<T>> pick(key: String, base: T, unknown: T, all: List<T>): T =
        when (val leaf = patch[key]) {
            null -> base
            is DwTraceValue.Choice -> all.firstOrNull { it.name == leaf.value } ?: unknown
            else -> wrong(key, leaf, "a name")
        }

    /** A packed ARGB colour, arriving unsigned. See the file header. */
    fun colour(key: String, base: Int): Int = when (val leaf = patch[key]) {
        null -> base
        is DwTraceValue.Num -> dwTraceSignedColour(finite(key, leaf.value))
        else -> wrong(key, leaf, "a packed colour")
    }

    /**
     * `output.background`, the one leaf whose `null` is a value rather than an absence — it is the
     * only spelling of a transparent export (`params.ts:359`), which is why the "White background"
     * toggle asks whether the leaf is PRESENT rather than reading a flag.
     */
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

/** What a leaf is, in the words the refusal above puts on screen. */
private fun dwTraceKindOf(leaf: DwTraceValue): String = when (leaf) {
    is DwTraceValue.Num -> "a number"
    is DwTraceValue.Flag -> "a true/false flag"
    is DwTraceValue.Choice -> "a name"
    DwTraceValue.Absent -> "nothing at all"
}

/* ────────────────────────────────────────────────────────────────────────────
 * Colours
 * ──────────────────────────────────────────────────────────────────────────── */

/** Kotlin's signed `Int` as the unsigned 32-bit number every tree the portal writes carries. */
private fun dwTraceUnsignedColour(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

/** The inverse: 4294967295 back to `0xFFFFFFFF.toInt()`, which is -1, not `Int.MAX_VALUE`. */
private fun dwTraceSignedColour(packed: Double): Int = packed.toLong().toInt()

/** The leaves of `output` that hold a packed colour. Their own keys, not their dotted paths. */
private val DW_TRACE_COLOUR_LEAVES: List<String> = listOf("strokeColor", "background")

/**
 * The two colour leaves of an incoming tree, rewritten so kotlinx can decode them into `Int`.
 *
 * WITHOUT THIS, 4294967295 IS NOT AN `Int` AND THE DECODE FAILS OUTRIGHT — every tree the portal has
 * written carries opaque black as 4278190080 and opaque white as 4294967295, both above `Int.MAX`.
 * `JsonNull` is tested before anything else for the reason `dwTraceFlatten` states: it IS a
 * `JsonPrimitive`, so a check that asks the general question first reads `null` as the string "null"
 * and the "White background" toggle sticks on forever.
 */
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
