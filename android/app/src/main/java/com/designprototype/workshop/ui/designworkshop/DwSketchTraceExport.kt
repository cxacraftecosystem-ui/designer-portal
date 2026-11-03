package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Immutable

const val DW_TRACE_PNG_MAX_EDGE_PX: Int = 2048

fun dwTracePngScale(width: Int, height: Int): Double {
    val safeWidth = if (width > 0) width else 1
    val safeHeight = if (height > 0) height else 1
    val longEdge = if (safeWidth > safeHeight) safeWidth else safeHeight
    return (DW_TRACE_PNG_MAX_EDGE_PX.toDouble() / longEdge.toDouble()).coerceAtMost(1.0)
}

@Immutable
data class DwTracePngSize(
    val width: Int,
    val height: Int,
    val reduced: Boolean,
)

fun dwTracePngSize(width: Int, height: Int): DwTracePngSize {
    val safeWidth = if (width > 0) width else 1
    val safeHeight = if (height > 0) height else 1
    val scale = dwTracePngScale(safeWidth, safeHeight)
    val outWidth = Math.round(safeWidth * scale).toInt().coerceAtLeast(1)
    val outHeight = Math.round(safeHeight * scale).toInt().coerceAtLeast(1)
    return DwTracePngSize(outWidth, outHeight, reduced = scale < 1.0)
}

fun dwTracePngReductionNote(
    documentWidth: Int,
    documentHeight: Int,
    size: DwTracePngSize,
): String {
    if (!size.reduced) return ""
    return "The picture is ${size.width}x${size.height}, reduced from " +
        "${documentWidth}x$documentHeight so it stays inside what a phone can hold. The SVG " +
        "beside it has no such limit."
}

const val DW_TRACE_PNG_MEMORY_REFUSAL: String =
    "This phone could not make room for a picture that size. The SVG is the same drawing and needs " +
        "almost no room — or choose a smaller region under “The part of the photograph to trace” " +
        "and trace again, because the picture is as big as the part of the sheet you traced."

const val DW_TRACE_PNG_DPI: Int = 72

@Immutable
data class DwTraceExportFormat(
    val id: String,
    val label: String,
    val engineFormat: String,
    val extension: String,
    val mime: String,
    val attachable: Boolean,
    val isVector: Boolean,
    val save: String,
    val hint: String,
)

val DW_TRACE_EXPORT_FORMATS: List<DwTraceExportFormat> = listOf(
    DwTraceExportFormat(
        id = "svg",
        label = "SVG",
        engineFormat = "SVG",
        extension = "svg",
        mime = "image/svg+xml",
        attachable = true,
        isVector = true,
        save = "Save the trace (SVG)",
        hint = "The traced paths themselves. Scales to any size without ever going blocky, opens in " +
            "Illustrator, Inkscape and CorelDRAW, and is what the “Line art” field is " +
            "declared for.",
    ),
    DwTraceExportFormat(
        id = "png",
        label = "PNG",
        engineFormat = "PNG",
        extension = "png",
        mime = "image/png",
        attachable = true,
        isVector = false,
        save = "Save the rendered image (PNG)",
        hint = "The drawing rendered as a picture, transparent wherever the drawing is not, up to " +
            "${DW_TRACE_PNG_MAX_EDGE_PX}px on its long edge. Opens anywhere and drops straight into " +
            "a letter or a slide, but it is pixels — enlarge it and it goes soft.",
    ),
    DwTraceExportFormat(
        id = "pdf",
        label = "PDF",
        engineFormat = "PDF",
        extension = "pdf",
        mime = "application/pdf",
        attachable = false,
        isVector = true,
        save = "Save a PDF to send on",
        hint = "Vector, and it opens on every machine you could mail it to without anybody installing " +
            "anything. The one to attach to an email, or to hand to somebody who only needs to look " +
            "at it.",
    ),
    DwTraceExportFormat(
        id = "dxf",
        label = "DXF",
        engineFormat = "DXF",
        extension = "dxf",
        mime = "image/vnd.dxf",
        attachable = false,
        isVector = true,
        save = "Save for a CAD or cutting machine (DXF)",
        hint = "The outlines as CAD geometry, for a laser cutter, a CNC router or a drafting package. " +
            "It is DXF R12, which every controller reads: curves arrive as many short straight lines, " +
            "and colour, fill and line thickness are not carried at all.",
    ),
    DwTraceExportFormat(
        id = "eps",
        label = "EPS",
        engineFormat = "EPS",
        extension = "eps",
        mime = "application/postscript",
        attachable = false,
        isVector = true,
        save = "Save for a print shop (EPS)",
        hint = "Vector PostScript, for a print shop or sign-cutting software that will not take an " +
            "SVG. PostScript has no transparency, so anything part-see-through is flattened onto the " +
            "background as the file is written.",
    ),
)

val DW_TRACE_EXPORT_FORMAT_COUNT: Int = DW_TRACE_EXPORT_FORMATS.size

val DW_TRACE_ATTACHABLE_FORMATS: List<DwTraceExportFormat> =
    DW_TRACE_EXPORT_FORMATS.filter { it.attachable }

fun dwTraceExportFormat(id: String): DwTraceExportFormat? =
    DW_TRACE_EXPORT_FORMATS.firstOrNull { it.id == id }

@Immutable
data class DwTraceExportAbsence(val engineFormat: String, val reason: String)

val DW_TRACE_NOT_OFFERED: List<DwTraceExportAbsence> = listOf(
    DwTraceExportAbsence(
        engineFormat = "JPEG",
        reason = "Lossy, and lossy is at its worst on exactly this: hard black edges on white come " +
            "back with grey mush around them. It carries no transparency either, so a traced drawing " +
            "would arrive on a white rectangle. `exportDocument` throws for it by design; PNG is the " +
            "raster answer.",
    ),
    DwTraceExportAbsence(
        engineFormat = "WEBP",
        reason = "The same lossy objection as JPEG, and it is a web delivery format rather than one a " +
            "print shop, a CAD package or a ministry office would take. `exportDocument` throws for " +
            "it too.",
    ),
    DwTraceExportAbsence(
        engineFormat = "TIFF",
        reason = "The engine writes it uncompressed — a ${DW_TRACE_PNG_MAX_EDGE_PX}px plate is " +
            "16 MB of RGBA — for a use nobody has asked for. PNG is the same pixels an order of " +
            "magnitude smaller, and the vector formats are the answer to “I need it bigger”.",
    ),
    DwTraceExportAbsence(
        engineFormat = "BMP",
        reason = "Uncompressed 32bpp, the same 16 MB, in a format whose only advantage is opening on " +
            "a computer from 1998. PNG covers every reader that would take a BMP.",
    ),
    DwTraceExportAbsence(
        engineFormat = "PROJECT",
        reason = "`.otproj` is the tracer's own session file — geometry plus every parameter, so " +
            "a trace can be reopened and re-tuned. It is not a drawing, it is useful only inside an " +
            "application the designer does not have, and `exportDocument` refuses it as well " +
            "(`ProjectCodec` writes it). Worth revisiting if this panel ever grows a “reopen " +
            "this trace” door; there is nothing to reopen it with today, because nothing on " +
            "this panel is stored.",
    ),
)

fun dwTraceExportBackground(documentBackground: Int?): Int? = documentBackground

fun dwTraceDocumentBackground(appliedParams: DwTraceValues): Int? =
    appliedParams.number("output.background")?.toLong()?.toInt()

fun dwTraceBackgroundIsWhite(documentBackground: Int?): Boolean = documentBackground != null

fun dwTraceBackgroundLabel(documentBackground: Int?): String =
    if (dwTraceBackgroundIsWhite(documentBackground)) "White" else "Transparent"

const val DW_TRACE_BACKGROUND_RETRACE_SENTENCE: String =
    "Changing this traces the sheet again, which takes a few seconds — the background is part of the " +
        "drawing the engine writes, so every format below changes together and none of them can " +
        "disagree with the others."

const val DW_TRACE_ATTACH_SUFFIX: String = "line-art"

const val DW_TRACE_SAVE_SUFFIX: String = "line-art"

const val DW_TRACE_RENDER_SUFFIX: String = "traced"

fun dwTraceSaveSuffix(format: DwTraceExportFormat): String =
    if (format.isVector) DW_TRACE_SAVE_SUFFIX else DW_TRACE_RENDER_SUFFIX

fun dwTraceExportFileName(
    sourceName: String,
    extension: String,
    suffix: String = DW_TRACE_ATTACH_SUFFIX,
): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
    val trimmed = leaf.replace(Regex("\\.[^./\\\\]+$"), "").trim()
    val base = if (trimmed.isNotEmpty()) trimmed else "sketch"
    val safe = base.replace(Regex("[^\\w\\-. ]+"), "_").take(80)
    val tag = suffix.replace(Regex("[^\\w\\-. ]+"), "_").trim()
    return if (tag.isNotEmpty()) "$safe-$tag.$extension" else "$safe.$extension"
}

fun dwTraceProvenanceNote(
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    frameNote: String = "",
): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "a photograph" }
    val head = "Traced on the device from $leaf by the Design & Prototype Workshop portal. " +
        "$shapeCount paths, $nodeCount nodes."
    return if (frameNote.isBlank()) head else "$head ${frameNote.trim()}"
}

const val DW_TRACE_EXPORT_REPORT_SENTENCE: String =
    "This file is yours to keep and to send on. It does not go into the workshop report: the report " +
        "prints photographs, and it names an attached file without carrying it — so an officer " +
        "reading the document will see that line art was produced and will not see this drawing " +
        "unless you send it to them."

const val DW_TRACE_ATTACH_REPORT_SENTENCE: String =
    "The drawing is attached as an SVG — vector line work that prints at any size without ever " +
        "going blocky. Attaching it does not change what the ministry report shows: the report " +
        "prints the sketch photograph, names the attached file and does not carry it, so the " +
        "drawing stays in the workshop record for whoever opens it there. The picture above is " +
        "only for comparing; it is never what gets attached."

const val DW_TRACE_EXPORT_PREVIEW_SENTENCE: String =
    "This drawing was traced at a smaller size so it could be tuned quickly. Trace it once at full " +
        "size before saving it — a preview saved now would be a coarser drawing than the one on " +
        "screen, and nothing in the file would say so."

const val DW_TRACE_EXPORT_ENGINE_NAME_SENTENCE: String =
    "The file records that it was made by the Offline Tracer engine, which is the tracing library " +
        "this app uses on the device. That is a note about the software — not about the drawing, " +
        "and not about you."

const val DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE: String =
    "This file records nothing about which photograph it was traced from. None of the five forms " +
        "this app writes has anywhere to put that note, so keep the photograph beside it if somebody " +
        "will need to know which sheet it came from."

const val DW_TRACE_EXPORT_NO_FRAME_SENTENCE: String =
    "You traced part of the photograph rather than the whole sheet. This file has nowhere to record " +
        "that either, so somebody holding it beside the photograph cannot tell why the two do not " +
        "match. Say which part you traced when you send it on."

fun dwTraceExportLosses(
    format: DwTraceExportFormat,
    documentBackground: Int?,
    documentLongEdgePx: Int = 0,
    frameNote: String = "",
): List<String> {
    val transparent = !dwTraceBackgroundIsWhite(documentBackground)
    val out = mutableListOf<String>()
    out += DW_TRACE_EXPORT_REPORT_SENTENCE
    when (format.id) {
        "png" -> {
            val capBites = documentLongEdgePx <= 0 || documentLongEdgePx > DW_TRACE_PNG_MAX_EDGE_PX
            if (capBites) {
                out += "Pixels, not curves, and no bigger than $DW_TRACE_PNG_MAX_EDGE_PX px on the " +
                    "long edge — a larger one is more memory than these phones have. Save the " +
                    "SVG instead if it has to be enlarged."
            }
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "dxf" -> {
            out += "DXF R12 carries geometry and nothing else: no colour, no fill, no line " +
                "thickness, and curves arrive as many short straight lines."
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
            out += "It carries no background either, so the choice above changes nothing in this file."
        }
        "eps" -> {
            out += "PostScript has no transparency. Anything part-see-through is flattened onto the " +
                "background as the file is written, and the engine records in the file that it did so."
            out += DW_TRACE_EXPORT_ENGINE_NAME_SENTENCE
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "pdf" -> {
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "svg" -> {
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
    }
    if (frameNote.isNotBlank() && DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE in out) {
        out += DW_TRACE_EXPORT_NO_FRAME_SENTENCE
    }
    if (transparent && format.id != "dxf") {
        out += "Transparent means the page shows through wherever the drawing is not. In an email or " +
            "a report that is whatever colour the reader's page happens to be — turn the white " +
            "background on above if somebody is going to print it."
    }
    return out
}
