package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import com.designprototype.workshop.data.dwProbeDevice
import com.offlinetracer.export.ExportFormat
import com.offlinetracer.export.ExportOptions
import com.offlinetracer.export.SvgExport
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.pipeline.CancellationToken
import com.offlinetracer.pipeline.CancelledException
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.Pipeline
import com.offlinetracer.pipeline.ProgressListener
import com.offlinetracer.pipeline.Stages
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.pipeline.TraceResult
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val DW_TRACE_KOTLIN_SVG_WRITER: String = "core-export/SvgExport#export"

val DW_TRACE_KOTLIN_STAGES: List<DwTraceStage> =
    Stages.ALL.map { DwTraceStage(id = it.id, label = it.label) }

val DW_TRACE_KOTLIN_BPP: Map<EdgeEngine, Int> = linkedMapOf(
    EdgeEngine.CANNY to 152,
    EdgeEngine.ADAPTIVE to 51,
    EdgeEngine.LOG to 42,
    EdgeEngine.FDOG to 40,
    EdgeEngine.XDOG to 152,
    EdgeEngine.MODEL to 152,
)

const val DW_TRACE_KOTLIN_INPUT_BPP: Int = 8

const val DW_TRACE_KOTLIN_RESULT_BYTES: Long = 24L * 1024L * 1024L

const val DW_TRACE_KOTLIN_HEAP_RESERVE_BYTES: Long = 32L * 1024L * 1024L

fun dwTraceKotlinHeapBytes(runtime: Runtime = Runtime.getRuntime()): Long =
    runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

fun dwTraceKotlinPeakBytes(sourcePixels: Long, workingPixels: Long, engine: EdgeEngine): Long {
    val perPixel = DW_TRACE_KOTLIN_BPP[engine] ?: DW_TRACE_KOTLIN_BPP.values.max()
    return sourcePixels * DW_TRACE_KOTLIN_INPUT_BPP + workingPixels * perPixel + DW_TRACE_KOTLIN_RESULT_BYTES
}

fun dwTraceKotlinWorkingPixels(width: Int, height: Int, params: TraceParams): Long {
    val (w, h) = dwTraceWorkingSize(width, height, params.preprocess.workingLongEdge)
    return w.toLong() * h.toLong()
}

fun dwTraceKotlinMemoryRefusal(
    sourcePixels: Long,
    workingPixels: Long,
    engine: EdgeEngine,
    heapBytes: Long,
): String? {
    val peak = dwTraceKotlinPeakBytes(sourcePixels, workingPixels, engine)
    if (peak + DW_TRACE_KOTLIN_HEAP_RESERVE_BYTES <= heapBytes) return null
    val need = dwTraceKotlinMegabytes(peak)
    val have = dwTraceKotlinMegabytes(heapBytes - DW_TRACE_KOTLIN_HEAP_RESERVE_BYTES)
    return "This trace needs about $need MB of memory and this phone can spare about $have MB right " +
        "now, so it has not been started rather than started and lost part-way. Set the trace " +
        "resolution lower, or choose a different edge engine — Canny needs about three times the " +
        "memory of the others — and close other apps if you can."
}

private fun dwTraceKotlinMegabytes(bytes: Long): Long =
    if (bytes <= 0L) 0L else Math.round(bytes / (1024.0 * 1024.0))

fun dwTraceKotlinImageOf(rgba: ByteArray, width: Int, height: Int): RgbaImage {
    if (width < 1 || height < 1) {
        throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_EMPTY, "${width}x$height")
    }
    val count = width.toLong() * height.toLong()
    if (rgba.size.toLong() < count * 4L) {
        throw DwTraceHostFailure(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            "${rgba.size} bytes for a ${width}x$height image, which needs ${count * 4L}",
        )
    }
    val pixels = IntArray(count.toInt())
    var b = 0
    for (i in pixels.indices) {
        val r = rgba[b].toInt() and 0xFF
        val g = rgba[b + 1].toInt() and 0xFF
        val bl = rgba[b + 2].toInt() and 0xFF
        val a = rgba[b + 3].toInt() and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        b += 4
    }
    return RgbaImage(width, height, pixels)
}

fun dwTraceKotlinGeometryOf(doc: VecDocument): DwTraceGeometry {
    val shapes = ArrayList<VecShape>(doc.shapeCount())
    for (layer in doc.layers) shapes.addAll(layer.shapes)

    var segTotal = 0
    var coordTotal = 0
    for (shape in shapes) {
        val segs = shape.path.segments
        segTotal += segs.size
        coordTotal += 2
        for (seg in segs) coordTotal += dwTraceKotlinCoordsOf(seg)
    }

    val coords = FloatArray(coordTotal)
    val verbs = ByteArray(segTotal)
    val verbStarts = IntArray(shapes.size + 1)
    val coordStarts = IntArray(shapes.size + 1)
    val closed = ByteArray(shapes.size)
    val styleIndex = IntArray(shapes.size)
    val styleTable = ArrayList<DwTraceStyle>()
    val styleKeys = LinkedHashMap<VecStyle, Int>()

    var v = 0
    var c = 0
    for (s in shapes.indices) {
        val shape = shapes[s]
        verbStarts[s] = v
        coordStarts[s] = c
        closed[s] = if (shape.path.closed) 1 else 0

        styleIndex[s] = styleKeys.getOrPut(shape.style) {
            styleTable.add(dwTraceKotlinStyleOf(shape.style))
            styleTable.size - 1
        }

        coords[c++] = shape.path.start.x
        coords[c++] = shape.path.start.y
        for (seg in shape.path.segments) {
            when (seg) {
                is VecSeg.Line -> {
                    verbs[v++] = DW_TRACE_VERB_LINE
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }

                is VecSeg.Quad -> {
                    verbs[v++] = DW_TRACE_VERB_QUAD
                    coords[c++] = seg.c.x
                    coords[c++] = seg.c.y
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }

                is VecSeg.Cubic -> {
                    verbs[v++] = DW_TRACE_VERB_CUBIC
                    coords[c++] = seg.c1.x
                    coords[c++] = seg.c1.y
                    coords[c++] = seg.c2.x
                    coords[c++] = seg.c2.y
                    coords[c++] = seg.to.x
                    coords[c++] = seg.to.y
                }
            }
        }
    }
    verbStarts[shapes.size] = v
    coordStarts[shapes.size] = c

    return DwTraceGeometry(
        coords = coords,
        verbs = verbs,
        verbStarts = verbStarts,
        coordStarts = coordStarts,
        closed = closed,
        styleTable = styleTable,
        styleIndex = styleIndex,
    )
}

private fun dwTraceKotlinCoordsOf(seg: VecSeg): Int = when (seg) {
    is VecSeg.Line -> 2
    is VecSeg.Quad -> 4
    is VecSeg.Cubic -> 6
}

private fun dwTraceKotlinStyleOf(style: VecStyle): DwTraceStyle = DwTraceStyle(
    stroke = style.stroke,
    strokeWidth = style.strokeWidth,
    fill = style.fill,
    fillRule = style.fillRule.name,
    cap = style.cap.name,
    join = style.join.name,
    miterLimit = style.miterLimit,
    opacity = style.opacity,
)

const val DW_TRACE_KOTLIN_MAX_SHAPES: Int = 200000

class DwTraceKotlinSvg(
    val svg: String,
    val shapesWritten: Int,
    val truncationNote: String?,
)

fun dwTraceKotlinSvgOf(doc: VecDocument): DwTraceKotlinSvg {
    val shapeCount = doc.shapeCount()
    val written = if (shapeCount > DW_TRACE_KOTLIN_MAX_SHAPES) DW_TRACE_KOTLIN_MAX_SHAPES else shapeCount
    val target = if (written == shapeCount) doc else dwTraceKotlinTrimmed(doc, written)
    val bytes = SvgExport.export(
        target,
        ExportOptions(
            format = ExportFormat.SVG,
            precision = 2,
            includeMetadata = false,
            flattenLayers = true,
        ),
    )
    return DwTraceKotlinSvg(
        svg = String(bytes, Charsets.UTF_8),
        shapesWritten = written,
        truncationNote = dwTraceKotlinTruncationNote(shapeCount, written),
    )
}

private fun dwTraceKotlinTrimmed(doc: VecDocument, limit: Int): VecDocument {
    var left = limit
    val layers = ArrayList<VecLayer>(doc.layers.size)
    for (layer in doc.layers) {
        if (left <= 0) break
        if (layer.shapes.size <= left) {
            layers.add(layer)
            left -= layer.shapes.size
        } else {
            layers.add(layer.copy(shapes = ArrayList(layer.shapes.subList(0, left))))
            left = 0
        }
    }
    return VecDocument(doc.width, doc.height, layers, doc.background)
}

fun dwTraceKotlinTruncationNote(shapeCount: Int, shapesWritten: Int): String? {
    if (shapeCount <= shapesWritten) return null
    val count = dwTraceKotlinCount(shapeCount)
    val kept = dwTraceKotlinCount(shapesWritten)
    return "This drawing has $count separate paths and the file holds the first $kept. Raise " +
        "\u201cMinimum speck\u201d or \u201cSimplify\u201d and trace again to get a drawing that fits."
}

private fun dwTraceKotlinCount(value: Int): String {
    if (value < 1000) return value.toString()
    val digits = value.toString()
    val head = digits.substring(0, digits.length - 3)
    val out = StringBuilder()
    var i = head.length
    while (i > 2) {
        out.insert(0, "," + head.substring(i - 2, i))
        i -= 2
    }
    out.insert(0, head.substring(0, i))
    return out.append(',').append(digits.substring(digits.length - 3)).toString()
}

const val DW_TRACE_KOTLIN_PREVIEW_LONG_EDGE: Int = 720

fun dwTraceKotlinPreviewParams(base: TraceParams): TraceParams = base
    .copy(preprocess = base.preprocess.copy(workingLongEdge = DW_TRACE_KOTLIN_PREVIEW_LONG_EDGE))
    .sanitized()

suspend fun dwTraceKotlinTrace(
    src: RgbaImage,
    params: TraceParams,
    preview: Boolean,
    onProgress: suspend (DwTraceProgress) -> Unit,
): DwTraceDecoded = coroutineScope {
    val events = Channel<DwTraceProgress>(Channel.UNLIMITED)
    val pump = launch { for (event in events) onProgress(event) }
    val result = try {
        dwTraceKotlinRunEngine(src, params, preview, events)
    } finally {
        events.close()
    }
    pump.join()
    dwTraceKotlinDecodedOf(result)
}

private suspend fun dwTraceKotlinRunEngine(
    src: RgbaImage,
    params: TraceParams,
    preview: Boolean,
    events: Channel<DwTraceProgress>,
): TraceResult {
    val token = CancellationToken()
    var lastStageId: String? = null
    val listener = if (preview) {
        null
    } else {
        ProgressListener { stageId, label, fraction ->
            if (stageId != lastStageId) {
                lastStageId = stageId
                events.trySend(DwTraceProgress(stageId = stageId, label = label, fraction = fraction))
            }
        }
    }
    val effective = if (preview) dwTraceKotlinPreviewParams(params) else params

    return try {
        withContext(Dispatchers.Default) {
            val guard = launch(Dispatchers.Unconfined) {
                try {
                    awaitCancellation()
                } finally {
                    token.cancel()
                }
            }
            try {
                Pipeline.run(src, effective, listener, token, classify = !preview)
            } finally {
                guard.cancel()
            }
        }
    } catch (cancelled: CancelledException) {
        currentCoroutineContext().ensureActive()
        throw DwTraceHostFailure(
            DwTraceFailureKind.ENGINE_ERROR,
            "the trace stopped before it finished, and nothing had asked it to stop",
            cancelled,
        )
    } catch (exhausted: OutOfMemoryError) {
        throw DwTraceHostFailure(DwTraceFailureKind.OUT_OF_MEMORY, "", exhausted)
    }
}

fun dwTraceKotlinDecodedOf(result: TraceResult): DwTraceDecoded {
    val doc = result.document
    val svg = dwTraceKotlinSvgOf(doc)
    return DwTraceDecoded(
        svg = svg.svg,
        geometry = dwTraceKotlinGeometryOf(doc),
        background = doc.background,
        width = result.sourceWidth,
        height = result.sourceHeight,
        workingWidth = result.workingWidth,
        workingHeight = result.workingHeight,
        shapeCount = doc.shapeCount(),
        nodeCount = doc.nodeCount(),
        stages = result.stages.map { DwTraceStageTiming(it.id, it.label, it.millis) },
        totalMillis = result.totalMillis,
        notes = if (svg.truncationNote == null) result.notes else result.notes + svg.truncationNote,
        appliedParams = dwTraceValuesOfParams(result.appliedParams),
        autoSubjectId = result.autoSubjectId,
        suggestedStyleId = "",
    )
}

const val DW_TRACE_KOTLIN_NO_SUGGESTION_NOTE: String =
    "The Kotlin engine's classifier answers with a sentence rather than a style id, so this runtime " +
        "suggests no style. See DwTraceKotlinRuntime.kt for the three alternatives and why each is " +
        "worse than an empty field."

private class DwTraceKotlinRefusal(message: String) : Exception(message)

class DwTraceKotlinRuntime internal constructor(
    override val availability: DwTraceAvailability,
) : DwTraceRuntime {

    val svgWriter: String = DW_TRACE_KOTLIN_SVG_WRITER

    val stages: List<DwTraceStage> = DW_TRACE_KOTLIN_STAGES

    override suspend fun presets(): DwTracePresetTables = withContext(Dispatchers.Default) {
        dwTraceKotlinPresetTables()
    }

    override suspend fun defaults(): DwTraceValues = withContext(Dispatchers.Default) {
        dwTraceValuesOfParams(TraceParams())
    }

    override suspend fun withOverrides(
        base: DwTraceValues,
        patch: Map<String, DwTraceValue>,
    ): DwTraceValues = withContext(Dispatchers.Default) {
        dwTraceValuesOfParams(dwTraceApplyLeaves(dwTraceParamsOf(base), patch))
    }

    override suspend fun applyStyle(base: DwTraceValues, styleId: String): DwTraceValues =
        withContext(Dispatchers.Default) { dwTraceKotlinApplyStyle(base, styleId) }

    override suspend fun applySubject(base: DwTraceValues, subjectId: String): DwTraceValues =
        withContext(Dispatchers.Default) { dwTraceKotlinApplySubject(base, subjectId) }

    override suspend fun trace(
        request: DwTraceRequest,
        onProgress: (DwTraceProgress) -> Unit,
    ): DwTraceOutcome = try {
        DwTraceOutcome.Done(runTrace(request, onProgress))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: DwTraceKotlinRefusal) {
        DwTraceOutcome.Refused(refused.message.orEmpty())
    } catch (failure: DwTraceHostFailure) {
        DwTraceOutcome.Refused(failure.message.orEmpty())
    } catch (unexpected: Throwable) {
        DwTraceOutcome.Refused(
            dwTraceSentence(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                unexpected::class.java.simpleName,
            ),
        )
    }

    private suspend fun runTrace(
        request: DwTraceRequest,
        onProgress: (DwTraceProgress) -> Unit,
    ): DwTraceResult {
        val preview = !request.kind.isFullResolution

        val source = dwTraceDecodeForTrace(request.photographPath)

        val prepared = withContext(Dispatchers.Default) {
            val rgba = DwSketchTracePlates.readRgba(source)
                ?: throw DwTraceHostFailure(DwTraceFailureKind.IMAGE_UNREADABLE, "reading the pixels back")
            val decodedWidth = source.width
            val decodedHeight = source.height
            source.recycle()

            val frame = request.frame
            val cropped = if (frame == null) {
                null
            } else {
                dwTraceCropRgba(
                    rgba,
                    decodedWidth,
                    decodedHeight,
                    dwTraceCropIn(frame, decodedWidth, decodedHeight),
                ) ?: throw DwTraceHostFailure(
                    DwTraceFailureKind.OUT_OF_MEMORY,
                    "taking the chosen frame out of the photograph",
                )
            }
            DwTraceKotlinInput(
                rgba = cropped?.rgba ?: rgba,
                width = cropped?.rect?.width ?: decodedWidth,
                height = cropped?.rect?.height ?: decodedHeight,
                frameNote = cropped?.let { dwTraceCropNote(it.rect, decodedWidth, decodedHeight) }.orEmpty(),
            )
        }

        val params = dwTraceParamsOf(request.params)
        val effective = if (preview) dwTraceKotlinPreviewParams(params) else params

        dwTraceKotlinMemoryRefusal(
            sourcePixels = prepared.width.toLong() * prepared.height.toLong(),
            workingPixels = dwTraceKotlinWorkingPixels(prepared.width, prepared.height, effective),
            engine = effective.edge.engine,
            heapBytes = dwTraceKotlinHeapBytes(),
        )?.let { throw DwTraceKotlinRefusal(it) }

        val image = withContext(Dispatchers.Default) {
            dwTraceKotlinImageOf(prepared.rgba, prepared.width, prepared.height)
        }

        val decoded = dwTraceKotlinTrace(image, params, preview) { progress ->
            withContext(Dispatchers.Main) { onProgress(progress) }
        }

        return withContext(Dispatchers.Default) {
            dwTracePlateResult(
                decoded = decoded,
                rgba = prepared.rgba,
                sourceWidth = prepared.width,
                sourceHeight = prepared.height,
                frameNote = prepared.frameNote,
                request = request,
            )
        }
    }
}

private class DwTraceKotlinInput(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val frameNote: String,
)

fun dwTraceKotlinRuntime(context: Context): DwTraceRuntime {
    val app = context.applicationContext
    val totalRam = runCatching { dwProbeDevice(app).totalRamBytes }.getOrNull()
    val (maxEdge, fdogMaxEdge) = dwTraceCeilings(totalRam)
    return DwTraceKotlinRuntime(
        availability = DwTraceAvailability(
            maxWorkingLongEdge = maxEdge,
            fdogMaxWorkingLongEdge = fdogMaxEdge,
            measuredOn = null,
        ),
    )
}
