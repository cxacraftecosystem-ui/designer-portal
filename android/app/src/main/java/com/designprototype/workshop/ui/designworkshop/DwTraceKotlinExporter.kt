package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.offlinetracer.export.ExportFormat
import com.offlinetracer.export.ExportOptions
import com.offlinetracer.export.Exporter
import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

const val DW_TRACE_KOTLIN_LAYER_NAME: String = "Line art"

private const val DW_TRACE_KOTLIN_LAYER_ID: String = "trace"

class DwTraceKotlinDocument(
    val document: VecDocument,
    val shapesWritten: Int,
    val truncationNote: String?,
)

fun dwTraceKotlinDocumentOf(
    geometry: DwTraceGeometry,
    width: Int,
    height: Int,
    background: Int?,
): DwTraceKotlinDocument {
    geometry.validate()

    val shapeCount = geometry.shapeCount
    val written = if (shapeCount > DW_TRACE_KOTLIN_MAX_SHAPES) DW_TRACE_KOTLIN_MAX_SHAPES else shapeCount
    val shapes = ArrayList<VecShape>(written)

    for (i in 0 until written) {
        val coordStart = geometry.coordStarts[i]
        val verbFrom = geometry.verbStarts[i]
        val verbTo = geometry.verbStarts[i + 1]

        var c = coordStart
        val start = VecPoint(geometry.coords[c], geometry.coords[c + 1])
        c += 2

        val segments = ArrayList<VecSeg>(verbTo - verbFrom)
        for (v in verbFrom until verbTo) {
            when (geometry.verbs[v]) {
                DW_TRACE_VERB_LINE -> {
                    segments.add(VecSeg.Line(VecPoint(geometry.coords[c], geometry.coords[c + 1])))
                    c += 2
                }
                DW_TRACE_VERB_QUAD -> {
                    segments.add(
                        VecSeg.Quad(
                            VecPoint(geometry.coords[c], geometry.coords[c + 1]),
                            VecPoint(geometry.coords[c + 2], geometry.coords[c + 3]),
                        )
                    )
                    c += 4
                }
                else -> {
                    segments.add(
                        VecSeg.Cubic(
                            VecPoint(geometry.coords[c], geometry.coords[c + 1]),
                            VecPoint(geometry.coords[c + 2], geometry.coords[c + 3]),
                            VecPoint(geometry.coords[c + 4], geometry.coords[c + 5]),
                        )
                    )
                    c += 6
                }
            }
        }

        shapes.add(
            VecShape(
                path = VecPath(start = start, segments = segments, closed = geometry.isClosed(i)),
                style = dwTraceKotlinVecStyleOf(geometry.styleTable.getOrNull(geometry.styleIndex[i])),
            )
        )
    }

    return DwTraceKotlinDocument(
        document = VecDocument(
            width = dwTraceKotlinDimension(width),
            height = dwTraceKotlinDimension(height),
            layers = listOf(
                VecLayer(id = DW_TRACE_KOTLIN_LAYER_ID, name = DW_TRACE_KOTLIN_LAYER_NAME, shapes = shapes)
            ),
            background = background,
        ),
        shapesWritten = written,
        truncationNote = dwTraceKotlinTruncationNote(shapeCount, written),
    )
}

private fun dwTraceKotlinVecStyleOf(style: DwTraceStyle?): VecStyle {
    if (style == null) return VecStyle(cap = LineCap.BUTT, join = LineJoin.MITER)
    return VecStyle(
        stroke = style.stroke,
        strokeWidth = if (style.strokeWidth.isFinite() && style.strokeWidth > 0f) style.strokeWidth else 1f,
        fill = style.fill,
        fillRule = if (style.fillRule == "NONZERO") FillRule.NONZERO else FillRule.EVENODD,
        cap = when (style.cap) {
            "ROUND" -> LineCap.ROUND
            "SQUARE" -> LineCap.SQUARE
            else -> LineCap.BUTT
        },
        join = when (style.join) {
            "ROUND" -> LineJoin.ROUND
            "BEVEL" -> LineJoin.BEVEL
            else -> LineJoin.MITER
        },
        miterLimit = if (style.miterLimit.isFinite() && style.miterLimit > 0f) style.miterLimit else 4f,
        opacity = if (style.opacity.isFinite()) style.opacity.coerceIn(0f, 1f) else 1f,
    )
}

private fun dwTraceKotlinDimension(value: Int): Float = if (value < 1) 1f else value.toFloat()

class DwTraceKotlinExporter : DwTraceExporter {

    override val refusal: String? = null

    override suspend fun export(request: DwTraceExportRequest): DwTraceExportOutcome =
        withContext(Dispatchers.Default) {
            val format = dwTraceKotlinExportFormat(request.format)
                ?: return@withContext DwTraceExportOutcome.Refused(
                    dwTraceKotlinUnwritableSentence(request.format)
                )

            currentCoroutineContextEnsureActive()

            val built = try {
                dwTraceKotlinDocumentOf(
                    geometry = request.geometry,
                    width = request.width,
                    height = request.height,
                    background = request.background,
                )
            } catch (failure: DwTraceHostFailure) {
                return@withContext DwTraceExportOutcome.Refused(failure.message.orEmpty())
            }

            currentCoroutineContextEnsureActive()

            val bytes = try {
                Exporter.export(
                    doc = built.document,
                    raster = null,
                    o = ExportOptions(
                        format = format,
                        width = 0,
                        height = 0,
                        scale = 1f,
                        background = request.background,
                        dpi = DW_TRACE_PNG_DPI,
                        precision = 2,
                        includeMetadata = false,
                        flattenLayers = false,
                    ),
                )
            } catch (unsupported: UnsupportedOperationException) {
                return@withContext DwTraceExportOutcome.Refused(
                    dwTraceKotlinUnwritableSentence(request.format)
                )
            } catch (memory: OutOfMemoryError) {
                return@withContext DwTraceExportOutcome.Refused(DW_TRACE_EXPORT_MEMORY_SENTENCE)
            }

            DwTraceExportOutcome.Done(bytes)
        }
}

private fun dwTraceKotlinExportFormat(format: DwTraceExportFormat): ExportFormat? {
    val named = ExportFormat.entries.firstOrNull { it.name == format.engineFormat } ?: return null
    return if (Exporter.supports(named)) named else null
}

private fun dwTraceKotlinUnwritableSentence(format: DwTraceExportFormat): String =
    "The ${format.label} file could not be saved. The SVG and the picture still save."

const val DW_TRACE_EXPORT_MEMORY_SENTENCE: String =
    "This drawing has too many separate paths for this phone to write out as a file. The SVG still " +
        "saves. Raise “Minimum speck” or “Simplify” and trace again for a drawing that fits."

@Composable
fun rememberDwTraceExporter(): DwTraceExporter = remember { DwTraceKotlinExporter() }

private suspend fun currentCoroutineContextEnsureActive() {
    kotlin.coroutines.coroutineContext.ensureActive()
}
