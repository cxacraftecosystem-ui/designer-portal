package com.designprototype.workshop.ui.designworkshop

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.coroutines.coroutineContext

enum class DwTraceFailureKind {
    SANDBOX_UNSUPPORTED,

    SANDBOX_FEATURE_MISSING,

    SANDBOX_DIED,

    OUT_OF_MEMORY,

    RESULT_TOO_LARGE,

    ENGINE_ERROR,

    FRAME_MISMATCH,

    IMAGE_UNREADABLE,

    IMAGE_EMPTY,

    BUNDLE_MISSING,

    BUNDLE_CONTRACT_MISMATCH,

    PROTOCOL_UNREADABLE,
}

const val DW_TRACE_DETAIL_MAX: Int = 160

fun dwTraceSentence(kind: DwTraceFailureKind, detail: String = ""): String {
    val trimmed = detail.trim().let { if (it.isNotEmpty() && it.length <= DW_TRACE_DETAIL_MAX) it else "" }
    return when (kind) {
        DwTraceFailureKind.SANDBOX_UNSUPPORTED ->
            "Android System WebView is too old to trace. Update it from the Play Store; the photo is unaffected."

        DwTraceFailureKind.SANDBOX_FEATURE_MISSING ->
            "Android System WebView won't accept the image. Update it from the Play Store; the photo is unaffected."

        DwTraceFailureKind.SANDBOX_DIED ->
            "Android stopped the trace in the background. Retry and keep this screen open; the photo is unaffected."

        DwTraceFailureKind.OUT_OF_MEMORY ->
            "Out of memory. Use Fast resolution or another edge engine, and close other apps. The photo is unaffected."

        DwTraceFailureKind.RESULT_TOO_LARGE ->
            "Too many shapes. Raise “Minimum speck” or use Fast resolution, then retrace. The photo is unaffected."

        DwTraceFailureKind.ENGINE_ERROR ->
            if (trimmed.isEmpty()) {
                "Couldn't finish tracing. Try another edge engine or Fast resolution; the photo is unaffected."
            } else {
                "Couldn't finish tracing: $trimmed"
            }

        DwTraceFailureKind.FRAME_MISMATCH ->
            "The trace frame doesn't match the photo, so there's no comparison; the drawing is unaffected. Turn off “Rectify the page” to compare." + if (trimmed.isEmpty()) "" else " ($trimmed)"

        DwTraceFailureKind.IMAGE_UNREADABLE ->
            "Couldn't read this photo. Choose another; the original is unaffected."

        DwTraceFailureKind.IMAGE_EMPTY ->
            "This photo is empty, so there's nothing to trace; the original is unaffected. Choose another."

        DwTraceFailureKind.BUNDLE_MISSING ->
            "Tracing couldn't start. Update the app and retry; the photo is unaffected."

        DwTraceFailureKind.BUNDLE_CONTRACT_MISMATCH ->
            "Tracing needs an app update. Nothing has been attached; the photo is unaffected."

        DwTraceFailureKind.PROTOCOL_UNREADABLE ->
            "Trace didn't finish; nothing has been attached and the photo is unaffected. Retry or update the app."
    }
}

class DwTraceHostFailure(
    val kind: DwTraceFailureKind,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception(dwTraceSentence(kind, detail), cause)

interface DwTraceJsHost {
    suspend fun provideNamedData(name: String, bytes: ByteArray)

    suspend fun evaluate(script: String): String

    fun close()
}

const val DW_TRACE_CONTRACT: Int = 1

const val DW_TRACE_GLOBAL: String = "__DwTrace"

const val DW_TRACE_BUNDLE_ASSET: String = "dw-trace-engine.js"

const val DW_TRACE_IMAGE_DATA_NAME: String = "dwTraceImage"

const val DW_TRACE_PROTOCOL_NOTE: String =
    "__DwTrace.{hello,presets,defaults,withOverrides,applyStyle,applySubject,start,pump,cancel}, " +
        "each returning a JSON string envelope. See this constant's KDoc for the full contract."

class DwTraceHello(
    val contract: Int,
    val pumped: Boolean,
    val engineManifestSha256: String,
    val svgWriter: String,
    val notes: List<String>,
)

const val DW_TRACE_VERB_LINE: Byte = 0

const val DW_TRACE_VERB_QUAD: Byte = 1

const val DW_TRACE_VERB_CUBIC: Byte = 2

class DwTraceStyle(
    val stroke: Int?,
    val strokeWidth: Float,
    val fill: Int?,
    val fillRule: String,
    val cap: String,
    val join: String,
    val miterLimit: Float,
    val opacity: Float,
)

class DwTraceGeometry(
    val coords: FloatArray,
    val verbs: ByteArray,
    val verbStarts: IntArray,
    val coordStarts: IntArray,
    val closed: ByteArray,
    val styleTable: List<DwTraceStyle>,
    val styleIndex: IntArray,
) {
    val shapeCount: Int get() = closed.size

    fun isClosed(shape: Int): Boolean = closed[shape].toInt() != 0

    fun styleOf(shape: Int): DwTraceStyle = styleTable[styleIndex[shape]]

    fun validate() {
        val n = shapeCount
        if (verbStarts.size != n + 1) bad("verbStarts is ${verbStarts.size} for $n shapes")
        if (coordStarts.size != n + 1) bad("coordStarts is ${coordStarts.size} for $n shapes")
        if (styleIndex.size != n) bad("styleIndex is ${styleIndex.size} for $n shapes")
        if (n > 0 && styleTable.isEmpty()) bad("the style table is empty for $n shapes")
        if (verbStarts[n] != verbs.size) {
            bad("verbs is ${verbs.size} long, verbStarts ends at ${verbStarts[n]}")
        }
        if (coordStarts[n] != coords.size) {
            bad("coords is ${coords.size} long, coordStarts ends at ${coordStarts[n]}")
        }
        for (i in 0 until n) {
            if (verbStarts[i] < 0 || verbStarts[i] > verbStarts[i + 1]) {
                bad("verbStarts does not increase at shape $i")
            }
            if (coordStarts[i] < 0 || coordStarts[i] > coordStarts[i + 1]) {
                bad("coordStarts does not increase at shape $i")
            }
            if (styleIndex[i] !in styleTable.indices) bad("shape $i names style ${styleIndex[i]}")
            var want = 2
            for (v in verbStarts[i] until verbStarts[i + 1]) {
                want += when (verbs[v]) {
                    DW_TRACE_VERB_LINE -> 2
                    DW_TRACE_VERB_QUAD -> 4
                    DW_TRACE_VERB_CUBIC -> 6
                    else -> bad("shape $i has verb ${verbs[v]}, which is not a line, quad or cubic")
                }
            }
            val have = coordStarts[i + 1] - coordStarts[i]
            if (have != want) bad("shape $i has $have coordinates where its verbs need $want")
        }
    }

    private fun bad(why: String): Nothing =
        throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, why)
}

class DwTraceDecoded(
    val svg: String,
    val geometry: DwTraceGeometry,
    val background: Int?,
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
)

fun dwTraceArgbRowToRgba(pixels: IntArray, width: Int, out: ByteArray, rowStart: Int) {
    var o = rowStart
    for (x in 0 until width) {
        val p = pixels[x]
        out[o] = ((p ushr 16) and 0xFF).toByte()
        out[o + 1] = ((p ushr 8) and 0xFF).toByte()
        out[o + 2] = (p and 0xFF).toByte()
        out[o + 3] = ((p ushr 24) and 0xFF).toByte()
        o += 4
    }
}

const val DW_TRACE_DECODE_MAX_EDGE_PX: Int = 4096

fun dwTraceWorkingSize(width: Int, height: Int, maxEdge: Int = DW_TRACE_DECODE_MAX_EDGE_PX): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= 0 || maxEdge <= 0 || longest <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longest.toDouble()
    val w = maxOf(1, Math.round(width * scale).toInt())
    val h = maxOf(1, Math.round(height * scale).toInt())
    return w to h
}

const val DW_TRACE_DEFAULT_MAX_WORKING_EDGE: Int = 2048

const val DW_TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE: Int = 1024

fun dwTraceCeilings(totalRamBytes: Long?): Pair<Int, Int> {
    val small = totalRamBytes == null || totalRamBytes < 3L * 1024L * 1024L * 1024L
    return if (small) {
        (DW_TRACE_DEFAULT_MAX_WORKING_EDGE / 2) to (DW_TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE / 2)
    } else {
        DW_TRACE_DEFAULT_MAX_WORKING_EDGE to DW_TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE
    }
}

fun dwTraceHeapCapBytes(totalRamBytes: Long?): Long {
    val floor = 192L * 1024L * 1024L
    val ceiling = 512L * 1024L * 1024L
    val total = totalRamBytes ?: return floor
    return (total / 12L).coerceIn(floor, ceiling)
}

internal sealed class DwTraceEnvelope {
    class Hello(val hello: DwTraceHello) : DwTraceEnvelope()
    class Value(val params: DwTraceValues) : DwTraceEnvelope()
    class Presets(val tables: DwTracePresetTables) : DwTraceEnvelope()
    class Running(val events: List<DwTraceProgress>) : DwTraceEnvelope()
    class Done(val events: List<DwTraceProgress>, val decoded: DwTraceDecoded) : DwTraceEnvelope()
    class Failed(val message: String) : DwTraceEnvelope()
    object Cancelled : DwTraceEnvelope()
}

const val DW_TRACE_MAX_PUMPS: Int = 64

const val DW_TRACE_CANCEL_PUMPS: Int = 4

class DwTraceSession(private val host: DwTraceJsHost) {

    suspend fun hello(): DwTraceHello {
        val envelope = dwTraceReadEnvelope(host.evaluate("$DW_TRACE_GLOBAL.hello()"))
        val hello = (envelope as? DwTraceEnvelope.Hello)?.hello
            ?: throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                "hello() did not answer with a handshake",
            )
        if (hello.contract != DW_TRACE_CONTRACT) {
            throw DwTraceHostFailure(
                DwTraceFailureKind.BUNDLE_CONTRACT_MISMATCH,
                "the app speaks $DW_TRACE_CONTRACT, the packaged engine speaks ${hello.contract}",
            )
        }
        return hello
    }

    suspend fun presets(): DwTracePresetTables =
        expectPresets(host.evaluate("$DW_TRACE_GLOBAL.presets()"))

    suspend fun defaults(): DwTraceValues = expectValue(host.evaluate("$DW_TRACE_GLOBAL.defaults()"))

    suspend fun withOverrides(base: DwTraceValues, patch: Map<String, DwTraceValue>): DwTraceValues =
        expectValue(
            host.evaluate(
                "$DW_TRACE_GLOBAL.withOverrides(" +
                    "${dwTraceJsString(base.wire)}, ${dwTraceJsString(dwTracePatchJson(patch))})",
            ),
        )

    suspend fun applyStyle(base: DwTraceValues, styleId: String): DwTraceValues =
        expectValue(
            host.evaluate(
                "$DW_TRACE_GLOBAL.applyStyle(" +
                    "${dwTraceJsString(base.wire)}, ${dwTraceJsString(styleId)})",
            ),
        )

    suspend fun applySubject(base: DwTraceValues, subjectId: String): DwTraceValues =
        expectValue(
            host.evaluate(
                "$DW_TRACE_GLOBAL.applySubject(" +
                    "${dwTraceJsString(base.wire)}, ${dwTraceJsString(subjectId)})",
            ),
        )

    suspend fun trace(
        rgba: ByteArray,
        width: Int,
        height: Int,
        params: DwTraceValues,
        preview: Boolean,
        onProgress: suspend (DwTraceProgress) -> Unit,
    ): DwTraceDecoded {
        if (width < 1 || height < 1) {
            throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_EMPTY, "${width}x$height")
        }
        val needed = width.toLong() * height.toLong() * 4L
        if (rgba.size.toLong() < needed) {
            throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                "${rgba.size} bytes for a ${width}x$height frame, which needs $needed",
            )
        }

        host.provideNamedData(DW_TRACE_IMAGE_DATA_NAME, rgba)

        var envelope = dwTraceReadEnvelope(
            host.evaluate(
                "$DW_TRACE_GLOBAL.start(${dwTraceJsString(DW_TRACE_IMAGE_DATA_NAME)}, " +
                    "$width, $height, ${dwTraceJsString(params.wire)}, $preview)",
            ),
        )

        var cancelling = false
        var pumpsSinceCancel = 0
        var turns = 0
        while (true) {
            when (val answer = envelope) {
                is DwTraceEnvelope.Running -> emit(answer.events, onProgress)

                is DwTraceEnvelope.Done -> {
                    emit(answer.events, onProgress)
                    coroutineContext.ensureActive()
                    return answer.decoded
                }

                is DwTraceEnvelope.Failed -> throw DwTraceHostFailure(
                    DwTraceFailureKind.ENGINE_ERROR,
                    answer.message,
                )

                is DwTraceEnvelope.Cancelled -> {
                    coroutineContext.ensureActive()
                    throw DwTraceHostFailure(
                        DwTraceFailureKind.PROTOCOL_UNREADABLE,
                        "the engine reported a cancel nobody asked for",
                    )
                }

                else -> throw DwTraceHostFailure(
                    DwTraceFailureKind.PROTOCOL_UNREADABLE,
                    "the engine answered a trace with something else",
                )
            }

            turns += 1
            if (turns > DW_TRACE_MAX_PUMPS) {
                throw DwTraceHostFailure(
                    DwTraceFailureKind.PROTOCOL_UNREADABLE,
                    "the engine did not finish within $DW_TRACE_MAX_PUMPS stage turns",
                )
            }

            if (!cancelling && !coroutineContext.isActive) {
                cancelling = true
                envelope = dwTraceReadEnvelope(host.evaluate("$DW_TRACE_GLOBAL.cancel()"))
                continue
            }
            if (cancelling) {
                pumpsSinceCancel += 1
                if (pumpsSinceCancel > DW_TRACE_CANCEL_PUMPS) {
                    host.close()
                    coroutineContext.ensureActive()
                    throw DwTraceHostFailure(
                        DwTraceFailureKind.PROTOCOL_UNREADABLE,
                        "the engine ignored a cancel",
                    )
                }
            }
            envelope = dwTraceReadEnvelope(host.evaluate("$DW_TRACE_GLOBAL.pump()"))
        }
    }

    private suspend fun emit(
        events: List<DwTraceProgress>,
        onProgress: suspend (DwTraceProgress) -> Unit,
    ) {
        for (event in events) onProgress(event)
    }

    private fun expectValue(text: String): DwTraceValues {
        val envelope = dwTraceReadEnvelope(text)
        return (envelope as? DwTraceEnvelope.Value)?.params
            ?: throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                if (envelope is DwTraceEnvelope.Failed) envelope.message else "expected a parameter tree",
            )
    }

    private fun expectPresets(text: String): DwTracePresetTables {
        val envelope = dwTraceReadEnvelope(text)
        return (envelope as? DwTraceEnvelope.Presets)?.tables
            ?: throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                if (envelope is DwTraceEnvelope.Failed) envelope.message else "expected the preset tables",
            )
    }
}

private val dwTraceJson = Json

internal fun dwTraceReadEnvelope(text: String): DwTraceEnvelope {
    val root = runCatching { dwTraceJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "not JSON: ${text.take(80)}",
        )

    return when (val state = root.str("state")) {
        "hello" -> DwTraceEnvelope.Hello(
            DwTraceHello(
                contract = root.int("contract", -1),
                pumped = root.bool("pumped", false),
                engineManifestSha256 = root.str("engine").orEmpty(),
                svgWriter = root.str("svgWriter").orEmpty(),
                notes = root.strings("notes"),
            ),
        )

        "presets" -> DwTraceEnvelope.Presets(
            DwTracePresetTables(
                styles = root.presets("styles"),
                subjects = root.presets("subjects"),
            ),
        )

        "value" -> DwTraceEnvelope.Value(dwTraceValuesOf(root.str("params").orEmpty()))

        "running" -> DwTraceEnvelope.Running(root.events())

        "done" -> DwTraceEnvelope.Done(root.events(), dwTraceDecodeResult(root["result"]))

        "error" -> DwTraceEnvelope.Failed(root.str("message").orEmpty())

        "cancelled" -> DwTraceEnvelope.Cancelled

        else -> throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            if (state == null) "an envelope with no state" else "an envelope in state \"$state\"",
        )
    }
}

internal fun dwTraceValuesOf(wire: String): DwTraceValues {
    val tree = runCatching { dwTraceJson.parseToJsonElement(wire) }.getOrNull() as? JsonObject
        ?: throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "a parameter tree that is not a JSON object",
        )
    val leaves = LinkedHashMap<String, DwTraceValue>()
    dwTraceFlatten(tree, "", leaves)
    return DwTraceValues(leaves, wire)
}

private fun dwTraceFlatten(tree: JsonObject, prefix: String, out: MutableMap<String, DwTraceValue>) {
    for ((key, value) in tree) {
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        when {
            value is JsonNull -> out[path] = DwTraceValue.Absent
            value is JsonObject -> dwTraceFlatten(value, path, out)
            value is JsonArray -> Unit
            value is JsonPrimitive -> out[path] = dwTraceLeafOf(value, path)
            else -> Unit
        }
    }
}

private fun dwTraceLeafOf(primitive: JsonPrimitive, path: String): DwTraceValue {
    if (primitive.isString) return DwTraceValue.Choice(primitive.content)
    return when (val raw = primitive.content) {
        "true" -> DwTraceValue.Flag(true)
        "false" -> DwTraceValue.Flag(false)
        else -> raw.toDoubleOrNull()?.let { DwTraceValue.Num(it) }
            ?: throw DwTraceHostFailure(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                "$path is neither a number, a flag nor a name",
            )
    }
}

internal fun dwTracePatchJson(patch: Map<String, DwTraceValue>): String {
    val out = StringBuilder(patch.size * 24 + 2)
    out.append('{')
    var first = true
    for ((key, value) in patch) {
        if (!first) out.append(',')
        first = false
        out.append(dwTraceJsonString(key)).append(':')
        when (value) {
            is DwTraceValue.Num -> {
                if (!value.value.isFinite()) {
                    throw DwTraceHostFailure(
                        DwTraceFailureKind.PROTOCOL_UNREADABLE,
                        "$key was set to ${value.value}, which is not a number the engine can be sent",
                    )
                }
                out.append(value.value.toString())
            }
            is DwTraceValue.Flag -> out.append(if (value.value) "true" else "false")
            is DwTraceValue.Choice -> out.append(dwTraceJsonString(value.value))
            DwTraceValue.Absent -> out.append("null")
        }
    }
    out.append('}')
    return out.toString()
}

private fun dwTraceDecodeResult(element: JsonElement?): DwTraceDecoded {
    val result = element as? JsonObject
        ?: throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, "a done with no result")
    val geometry = dwTraceDecodeGeometry(result["geometry"])
    geometry.validate()
    return DwTraceDecoded(
        svg = result.str("svg").orEmpty(),
        geometry = geometry,
        background = result.argb("background"),
        width = result.int("width", 0),
        height = result.int("height", 0),
        workingWidth = result.int("workingWidth", 0),
        workingHeight = result.int("workingHeight", 0),
        shapeCount = result.int("shapeCount", 0),
        nodeCount = result.int("nodeCount", 0),
        stages = result.stages(),
        totalMillis = result.long("totalMillis", 0L),
        notes = result.strings("notes"),
        appliedParams = dwTraceValuesOf(result.str("appliedParams").orEmpty()),
        autoSubjectId = result.str("autoSubjectId").orEmpty(),
        suggestedStyleId = result.str("suggestedStyleId").orEmpty(),
    )
}

private fun dwTraceDecodeGeometry(element: JsonElement?): DwTraceGeometry {
    val g = element as? JsonObject
        ?: throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, "a result with no geometry")
    return DwTraceGeometry(
        coords = dwTraceFloats(g.str("coords").orEmpty(), "coords"),
        verbs = dwTraceBytes(g.str("verbs").orEmpty(), "verbs"),
        verbStarts = dwTraceInts(g.str("verbStarts").orEmpty(), "verbStarts"),
        coordStarts = dwTraceInts(g.str("coordStarts").orEmpty(), "coordStarts"),
        closed = dwTraceBytes(g.str("closed").orEmpty(), "closed"),
        styleTable = (g["styleTable"] as? JsonArray).orEmpty().map { dwTraceStyleOf(it) },
        styleIndex = dwTraceInts(g.str("styleIndex").orEmpty(), "styleIndex"),
    )
}

private fun dwTraceStyleOf(element: JsonElement): DwTraceStyle {
    val s = element as? JsonObject
        ?: throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, "a style that is not an object")
    return DwTraceStyle(
        stroke = s.argb("stroke"),
        strokeWidth = s.float("strokeWidth", 1.5f),
        fill = s.argb("fill"),
        fillRule = s.str("fillRule") ?: "EVENODD",
        cap = s.str("cap") ?: "ROUND",
        join = s.str("join") ?: "ROUND",
        miterLimit = s.float("miterLimit", 4f),
        opacity = s.float("opacity", 1f),
    )
}

private fun dwTraceRawBytes(base64: String, field: String): ByteArray =
    runCatching { Base64.getDecoder().decode(base64) }.getOrElse {
        throw DwTraceHostFailure(DwTraceFailureKind.PROTOCOL_UNREADABLE, "$field is not base64")
    }

private fun dwTraceBytes(base64: String, field: String): ByteArray = dwTraceRawBytes(base64, field)

private fun dwTraceFloats(base64: String, field: String): FloatArray {
    val bytes = dwTraceRawBytes(base64, field)
    if (bytes.size % 4 != 0) {
        throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "$field is ${bytes.size} bytes, which is not a whole number of float32s",
        )
    }
    val out = FloatArray(bytes.size / 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    return out
}

private fun dwTraceInts(base64: String, field: String): IntArray {
    val bytes = dwTraceRawBytes(base64, field)
    if (bytes.size % 4 != 0) {
        throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "$field is ${bytes.size} bytes, which is not a whole number of uint32s",
        )
    }
    val out = IntArray(bytes.size / 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
    return out
}

private fun JsonObject.prim(key: String): JsonPrimitive? {
    val value = this[key]
    if (value is JsonNull) return null
    return value as? JsonPrimitive
}

private fun JsonObject.str(key: String): String? = prim(key)?.content

private fun JsonObject.int(key: String, fallback: Int): Int =
    prim(key)?.content?.toDoubleOrNull()?.toInt() ?: fallback

private fun JsonObject.long(key: String, fallback: Long): Long =
    prim(key)?.content?.toDoubleOrNull()?.toLong() ?: fallback

private fun JsonObject.float(key: String, fallback: Float): Float =
    prim(key)?.content?.toFloatOrNull() ?: fallback

private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
    when (prim(key)?.content) {
        "true" -> true
        "false" -> false
        else -> fallback
    }

private fun JsonObject.argb(key: String): Int? =
    prim(key)?.content?.toDoubleOrNull()?.toLong()?.toInt()

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

private fun JsonObject.events(): List<DwTraceProgress> =
    (this["events"] as? JsonArray).orEmpty().mapNotNull { element ->
        val event = element as? JsonObject ?: return@mapNotNull null
        DwTraceProgress(
            stageId = event.str("stageId").orEmpty(),
            label = event.str("label").orEmpty(),
            fraction = event.float("fraction", 0f),
        )
    }

private fun JsonObject.stages(): List<DwTraceStageTiming> =
    (this["stages"] as? JsonArray).orEmpty().mapNotNull { element ->
        val stage = element as? JsonObject ?: return@mapNotNull null
        DwTraceStageTiming(
            id = stage.str("id").orEmpty(),
            label = stage.str("label").orEmpty(),
            millis = stage.long("millis", 0L),
        )
    }

private fun JsonObject.presets(key: String): List<DwTracePreset> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
        val preset = element as? JsonObject ?: return@mapNotNull null
        val id = preset.str("id").orEmpty()
        if (id.isEmpty()) return@mapNotNull null
        DwTracePreset(
            id = id,
            name = preset.str("name").orEmpty(),
            description = preset.str("description").orEmpty(),
            group = preset.str("group").orEmpty(),
        )
    }

internal fun dwTraceJsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch == '\b' -> out.append("\\b")
            ch == '\u000C' -> out.append("\\f")
            ch < ' ' || ch == '\u2028' || ch == '\u2029' ->
                out.append("\\u").append(String.format("%04x", ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}

internal fun dwTraceJsString(value: String): String = dwTraceJsonString(value)
