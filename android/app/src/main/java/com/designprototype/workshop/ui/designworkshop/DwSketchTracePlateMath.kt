package com.designprototype.workshop.ui.designworkshop

fun dwTraceClampedAverage(sum: Long, count: Int): Int {
    if (count <= 0) return 0
    if (sum <= 0L) return 0
    val floor = sum / count
    val twiceRemainder = 2L * (sum - floor * count)
    val rounded = when {
        twiceRemainder > count -> floor + 1L
        twiceRemainder < count -> floor
        (floor and 1L) == 1L -> floor + 1L
        else -> floor
    }
    return rounded.coerceIn(0L, 255L).toInt()
}

fun dwTraceResampleRow(
    src: ByteArray,
    srcWidth: Int,
    srcHeight: Int,
    plateWidth: Int,
    plateHeight: Int,
    py: Int,
    out: IntArray,
) {
    if (srcWidth < 1 || srcHeight < 1 || plateWidth < 1 || plateHeight < 1) return

    val sy0 = (py.toLong() * srcHeight / plateHeight).toInt()
    val sy1 = maxOf(sy0 + 1, ((py + 1).toLong() * srcHeight / plateHeight).toInt())
    for (px in 0 until plateWidth) {
        val sx0 = (px.toLong() * srcWidth / plateWidth).toInt()
        val sx1 = maxOf(sx0 + 1, ((px + 1).toLong() * srcWidth / plateWidth).toInt())
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (sy in sy0 until minOf(sy1, srcHeight)) {
            var index = (sy * srcWidth + sx0) * 4
            for (sx in sx0 until minOf(sx1, srcWidth)) {
                r += (src[index].toInt() and 0xFF).toLong()
                g += (src[index + 1].toInt() and 0xFF).toLong()
                b += (src[index + 2].toInt() and 0xFF).toLong()
                index += 4
                n += 1
            }
        }
        out[px] = if (n == 0) {
            0xFF000000.toInt()
        } else {
            (0xFF shl 24) or
                (dwTraceClampedAverage(r, n) shl 16) or
                (dwTraceClampedAverage(g, n) shl 8) or
                dwTraceClampedAverage(b, n)
        }
    }
}

fun dwTraceDifferenceRow(photograph: IntArray, trace: IntArray, out: IntArray, width: Int) {
    for (x in 0 until width) {
        val p = photograph[x]
        val t = trace[x]
        val r = Math.abs(((p ushr 16) and 0xFF) - ((t ushr 16) and 0xFF))
        val g = Math.abs(((p ushr 8) and 0xFF) - ((t ushr 8) and 0xFF))
        val b = Math.abs((p and 0xFF) - (t and 0xFF))
        out[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

const val DW_TRACE_DIFFERENCE_NOTE: String =
    "Difference subtracts the two pictures from each other: black where they agree, bright where " +
        "they do not. A line the trace missed and a line it invented both come out bright, and the " +
        "paper's own tone shows as an even dim grey."

const val DW_TRACE_DIFFERENCE_REFUSAL: String =
    "This phone could not make room for the difference picture. The wipe and the two whole " +
        "pictures still work, and the drawing is unaffected."

const val DW_TRACE_DIFFERENCE_PENDING: String = "Working out the difference picture…"

const val DW_TRACE_DIFFERENCE_DESCRIPTION: String =
    "The traced drawing and the photograph subtracted from each other. Dark where they agree, " +
        "bright where they differ."

const val DW_TRACE_DIFFERENCE_LABEL: String = "Difference"

const val DW_TRACE_COMPARE_WHITE_NOTE: String =
    "The comparison paints the drawing on white so it is visible over the photograph; the file " +
        "that is attached or downloaded keeps whatever background you chose."

fun dwTraceComparisonReduction(
    plateWidth: Int,
    plateHeight: Int,
    tracedWidth: Int,
    tracedHeight: Int,
): String {
    if (plateWidth < 1 || plateHeight < 1 || tracedWidth < 1 || tracedHeight < 1) return ""
    if (plateWidth >= tracedWidth && plateHeight >= tracedHeight) return ""
    return "Both pictures here are $plateWidth×$plateHeight, reduced from $tracedWidth×" +
        "$tracedHeight for the comparison only."
}

fun dwTraceComparisonStatus(
    hasPlates: Boolean,
    running: Boolean,
    failed: Boolean,
    plateRefusal: String,
    hasResult: Boolean,
): String = when {
    hasPlates && running ->
        "A newer trace is running. This is the last finished one, and its controls come back when " +
            "the new drawing appears."

    hasPlates -> ""

    plateRefusal.isNotBlank() -> plateRefusal

    running -> "Tracing… the comparison appears here when it finishes."

    failed -> "The trace did not finish, so there is nothing to compare. The reason is above."

    !hasResult -> "The comparison appears as soon as the first trace finishes."

    else -> "There is no comparison for this drawing."
}

const val DW_TRACE_PLATE_MEMORY_REFUSAL: String =
    "This phone could not make room for the two comparison pictures, so there is nothing to " +
        "compare the drawing against. The drawing itself is unaffected and can still be attached — " +
        "set the trace resolution to Fast if you want the comparison as well."
