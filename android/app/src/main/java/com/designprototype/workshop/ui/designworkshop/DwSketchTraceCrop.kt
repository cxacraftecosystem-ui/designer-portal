package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Immutable

@Immutable
data class DwTraceCropRect(val x: Int, val y: Int, val width: Int, val height: Int)

@Immutable
data class DwTraceFrameChoice(
    val rect: DwTraceCropRect,
    val frameWidth: Int,
    val frameHeight: Int,
)

const val DW_TRACE_CROP_MIN_EDGE_PX: Int = 16

fun dwTraceWholeFrame(width: Int, height: Int): DwTraceCropRect =
    DwTraceCropRect(0, 0, maxOf(1, width), maxOf(1, height))

fun dwTraceClampCrop(rect: DwTraceCropRect, width: Int, height: Int): DwTraceCropRect {
    val frameW = maxOf(1, width)
    val frameH = maxOf(1, height)
    val minW = minOf(DW_TRACE_CROP_MIN_EDGE_PX, frameW)
    val minH = minOf(DW_TRACE_CROP_MIN_EDGE_PX, frameH)

    val w = rect.width.coerceIn(minW, frameW)
    val h = rect.height.coerceIn(minH, frameH)
    val x = rect.x.coerceIn(0, frameW - w)
    val y = rect.y.coerceIn(0, frameH - h)
    return DwTraceCropRect(x, y, w, h)
}

fun dwTraceIsWholeFrame(rect: DwTraceCropRect, width: Int, height: Int): Boolean {
    val whole = dwTraceWholeFrame(width, height)
    return rect.x == 0 && rect.y == 0 && rect.width == whole.width && rect.height == whole.height
}

fun dwTraceCropIn(choice: DwTraceFrameChoice, width: Int, height: Int): DwTraceCropRect {
    if (choice.frameWidth < 1 || choice.frameHeight < 1 || width < 1 || height < 1) {
        return dwTraceWholeFrame(maxOf(1, width), maxOf(1, height))
    }
    if (choice.frameWidth == width && choice.frameHeight == height) {
        return dwTraceClampCrop(choice.rect, width, height)
    }
    val sx = width.toDouble() / choice.frameWidth.toDouble()
    val sy = height.toDouble() / choice.frameHeight.toDouble()
    val scaled = DwTraceCropRect(
        x = Math.round(choice.rect.x * sx).toInt(),
        y = Math.round(choice.rect.y * sy).toInt(),
        width = Math.round(choice.rect.width * sx).toInt(),
        height = Math.round(choice.rect.height * sy).toInt(),
    )
    return dwTraceClampCrop(scaled, width, height)
}

enum class DwTraceCropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

fun dwTraceMoveCorner(
    rect: DwTraceCropRect,
    corner: DwTraceCropCorner,
    dx: Int,
    dy: Int,
    width: Int,
    height: Int,
): DwTraceCropRect {
    val frameW = maxOf(1, width)
    val frameH = maxOf(1, height)
    val start = dwTraceClampCrop(rect, frameW, frameH)
    val minW = minOf(DW_TRACE_CROP_MIN_EDGE_PX, frameW)
    val minH = minOf(DW_TRACE_CROP_MIN_EDGE_PX, frameH)

    var left = start.x
    var top = start.y
    var right = start.x + start.width
    var bottom = start.y + start.height

    when (corner) {
        DwTraceCropCorner.TOP_LEFT -> {
            left = (left + dx).coerceIn(0, right - minW)
            top = (top + dy).coerceIn(0, bottom - minH)
        }

        DwTraceCropCorner.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + minW, frameW)
            top = (top + dy).coerceIn(0, bottom - minH)
        }

        DwTraceCropCorner.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(0, right - minW)
            bottom = (bottom + dy).coerceIn(top + minH, frameH)
        }

        DwTraceCropCorner.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + minW, frameW)
            bottom = (bottom + dy).coerceIn(top + minH, frameH)
        }
    }
    return DwTraceCropRect(left, top, right - left, bottom - top)
}

fun dwTraceMoveCrop(
    rect: DwTraceCropRect,
    dx: Int,
    dy: Int,
    width: Int,
    height: Int,
): DwTraceCropRect {
    val start = dwTraceClampCrop(rect, width, height)
    return dwTraceClampCrop(start.copy(x = start.x + dx, y = start.y + dy), width, height)
}

fun dwTraceCropClampNote(
    field: String,
    typed: Int,
    applied: DwTraceCropRect,
    width: Int,
    height: Int,
): String = when (field) {
    "Left" ->
        "Left can't be $typed (frame is ${applied.width} wide on ${width}px). It was set to ${applied.x}. Reduce Width to go further."

    "Top" ->
        "Top can't be $typed (frame is ${applied.height} tall on ${height}px). It was set to ${applied.y}. Reduce Height to go further."

    "Width" ->
        "Width must be between ${minOf(DW_TRACE_CROP_MIN_EDGE_PX, width)} and $width pixels. It was set to ${applied.width}."

    else ->
        "Height must be between ${minOf(DW_TRACE_CROP_MIN_EDGE_PX, height)} and $height pixels. It was set to ${applied.height}."
}

class DwTraceCroppedPixels(val rgba: ByteArray, val rect: DwTraceCropRect)

fun dwTraceCropRgba(
    src: ByteArray,
    width: Int,
    height: Int,
    rect: DwTraceCropRect,
): DwTraceCroppedPixels? {
    if (width < 1 || height < 1) return null
    if (src.size.toLong() < width.toLong() * height.toLong() * 4L) return null

    val box = dwTraceClampCrop(rect, width, height)
    if (dwTraceIsWholeFrame(box, width, height)) return DwTraceCroppedPixels(src, box)

    val rowBytes = box.width * 4
    val out = runCatching { ByteArray(rowBytes * box.height) }.getOrNull() ?: return null
    for (row in 0 until box.height) {
        val from = ((box.y + row) * width + box.x) * 4
        System.arraycopy(src, from, out, row * rowBytes, rowBytes)
    }
    return DwTraceCroppedPixels(out, box)
}

fun dwTraceCropNote(rect: DwTraceCropRect, sourceWidth: Int, sourceHeight: Int): String {
    if (sourceWidth < 1 || sourceHeight < 1) return ""
    val box = dwTraceClampCrop(rect, sourceWidth, sourceHeight)
    if (dwTraceIsWholeFrame(box, sourceWidth, sourceHeight)) return ""
    return "Cropped on the device to ${box.width}x${box.height} at (${box.x}, ${box.y}) of " +
        "${sourceWidth}x$sourceHeight."
}

fun dwTraceCropReadout(rect: DwTraceCropRect, width: Int, height: Int): String {
    if (width < 1 || height < 1) return ""
    val box = dwTraceClampCrop(rect, width, height)
    if (dwTraceIsWholeFrame(box, width, height)) return "The whole photograph, $width×$height."
    val part = box.width.toLong() * box.height.toLong()
    val whole = width.toLong() * height.toLong()
    val percent = Math.round(part * 100.0 / whole).toInt()
    return "${box.width}×${box.height} of $width×$height — $percent% of the frame. Everything outside it is absent from the drawing."
}

const val DW_TRACE_CROP_ENGINE_NOTE: String =
    "The engine may tighten the frame further."

fun dwTraceCropStaleNote(applied: DwTraceCropRect): String =
    "Frame changed — tap “Use this frame for the trace” to apply."
