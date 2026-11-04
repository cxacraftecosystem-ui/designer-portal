package com.designprototype.workshop.ui.designworkshop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DwTraceCompareMode {
    DRAWING,

    WIPE,

    PHOTOGRAPH,

    DIFFERENCE,
}

const val DW_TRACE_COMPARE_START: Float = 0f

private const val DW_TRACE_PEEK_HOLD_MS: Long = 220L

private const val DW_TRACE_MAX_ZOOM: Float = 6f

private const val DW_TRACE_SEAM_SLIDE_MS: Int = 180

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DwSketchTraceCompare(
    photograph: ImageBitmap,
    trace: ImageBitmap,
    tracedWidth: Int,
    tracedHeight: Int,
    modifier: Modifier = Modifier,
    difference: ImageBitmap? = null,
    differenceRefusal: String = "",
    onDifferenceWanted: () -> Unit = {},
    enabled: Boolean = true,
) {
    val widthGap = abs(photograph.width - trace.width)
    val heightGap = abs(photograph.height - trace.height)
    if (widthGap > 1 || heightGap > 1) {
        DwPanelNote(
            warning = true,
            text = "Sizes differ (${photograph.width}×${photograph.height} vs ${trace.width}×${trace.height}), so they can't be overlaid. You can still attach the trace.",
        )
        return
    }

    val stillness = LocalAppPreferences.current.reducedMotion

    var mode by remember { mutableStateOf(DwTraceCompareMode.WIPE) }
    var seam by remember { mutableFloatStateOf(DW_TRACE_COMPARE_START) }
    var peeking by remember { mutableStateOf(false) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val target = when {
        peeking -> 1f
        mode == DwTraceCompareMode.DRAWING -> 0f
        mode == DwTraceCompareMode.PHOTOGRAPH -> 1f
        else -> seam
    }
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (stillness) 0 else DW_TRACE_SEAM_SLIDE_MS),
        label = "trace-compare-seam",
    )

    val percent = (shown * 100f).roundToInt()
    val speech = "$percent% Photograph, ${100 - percent}% Traced drawing"

    val aspect = if (photograph.height > 0) {
        photograph.width.toFloat() / photograph.height.toFloat()
    } else {
        1f
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DwPanelChip(
                label = "Drawing",
                selected = mode == DwTraceCompareMode.DRAWING,
                enabled = enabled,
            ) { mode = DwTraceCompareMode.DRAWING }
            DwPanelChip(
                label = "Wipe",
                selected = mode == DwTraceCompareMode.WIPE,
                enabled = enabled,
            ) { mode = DwTraceCompareMode.WIPE }
            DwPanelChip(
                label = "Photograph",
                selected = mode == DwTraceCompareMode.PHOTOGRAPH,
                enabled = enabled,
            ) { mode = DwTraceCompareMode.PHOTOGRAPH }
            DwPanelChip(
                label = DW_TRACE_DIFFERENCE_LABEL,
                selected = mode == DwTraceCompareMode.DIFFERENCE,
                enabled = enabled,
            ) {
                mode = DwTraceCompareMode.DIFFERENCE
                if (difference == null) onDifferenceWanted()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clipToBounds()
                .background(MaterialTheme.field.surface200, RoundedCornerShape(10.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, DW_TRACE_MAX_ZOOM)
                        pan += panChange
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            val releasedEarly = withTimeoutOrNull(DW_TRACE_PEEK_HOLD_MS) {
                                tryAwaitRelease()
                            }
                            if (releasedEarly == null) {
                                peeking = true
                                tryAwaitRelease()
                                peeking = false
                            }
                        },
                    )
                }
                .semantics {
                    contentDescription = if (mode == DwTraceCompareMode.DIFFERENCE) {
                        DW_TRACE_DIFFERENCE_DESCRIPTION
                    } else {
                        "Trace over its photo. $speech."
                    }
                },
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas

                val iw = photograph.width.toFloat()
                val ih = photograph.height.toFloat()
                val fit = min(size.width / iw, size.height / ih)
                val scale = fit * zoom
                val dw = iw * scale
                val dh = ih * scale
                val slackX = max(0f, (dw - size.width) / 2f)
                val slackY = max(0f, (dh - size.height) / 2f)
                val px = pan.x.coerceIn(-slackX, slackX)
                val py = pan.y.coerceIn(-slackY, slackY)
                val left = (size.width - dw) / 2f + px
                val top = (size.height - dh) / 2f + py
                val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
                val dstSize = IntSize(dw.roundToInt(), dh.roundToInt())

                if (mode == DwTraceCompareMode.DIFFERENCE && !peeking) {
                    if (difference != null) {
                        drawImage(image = difference, dstOffset = dstOffset, dstSize = dstSize)
                    }
                    return@Canvas
                }

                drawImage(image = trace, dstOffset = dstOffset, dstSize = dstSize)

                val divider = size.width * shown.coerceIn(0f, 1f)
                if (divider > 0f) {
                    clipRect(left = 0f, top = 0f, right = divider, bottom = size.height) {
                        drawImage(image = photograph, dstOffset = dstOffset, dstSize = dstSize)
                    }
                    if (divider < size.width) {
                        drawRect(
                            color = Color.Black.copy(alpha = 0.45f),
                            topLeft = Offset(divider - 1.5f, 0f),
                            size = Size(3f, size.height),
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.9f),
                            topLeft = Offset(divider - 0.5f, 0f),
                            size = Size(1f, size.height),
                        )
                    }
                }
            }

            if (mode == DwTraceCompareMode.DIFFERENCE && !peeking) {
                DwCompareBadge(DW_TRACE_DIFFERENCE_LABEL, Modifier.align(Alignment.TopStart))
            } else {
                if (shown > 0.18f) {
                    DwCompareBadge("Photograph", Modifier.align(Alignment.TopStart))
                }
                if (shown < 0.82f) {
                    DwCompareBadge("Traced drawing", Modifier.align(Alignment.TopEnd))
                }
            }
            if (zoom > 1.01f) {
                DwCompareBadge(
                    "${(zoom * 10f).roundToInt() / 10f}× — pinch to fit",
                    Modifier.align(Alignment.BottomStart),
                )
            }
        }

        if (mode == DwTraceCompareMode.WIPE) {
            DwTraceWipeStrip(
                position = shown,
                enabled = enabled && !peeking,
                speech = speech,
                onPosition = { seam = it.coerceIn(0f, 1f) },
            )
            Text(
                "Drag to compare. Press and hold to see the photo.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        if (mode == DwTraceCompareMode.DIFFERENCE) {
            if (difference == null) {
                DwPanelNote(
                    warning = differenceRefusal.isNotBlank(),
                    text = differenceRefusal.ifBlank { DW_TRACE_DIFFERENCE_PENDING },
                    polite = true,
                )
            }
            Text(
                DW_TRACE_DIFFERENCE_NOTE,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        val reduction = dwTraceComparisonReduction(
            plateWidth = photograph.width,
            plateHeight = photograph.height,
            tracedWidth = tracedWidth,
            tracedHeight = tracedHeight,
        )
        Text(
            if (reduction.isEmpty()) DW_TRACE_COMPARE_WHITE_NOTE else "$DW_TRACE_COMPARE_WHITE_NOTE $reduction",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun DwTraceWipeStrip(
    position: Float,
    enabled: Boolean,
    speech: String,
    onPosition: (Float) -> Unit,
) {
    var width by remember { mutableFloatStateOf(0f) }
    val handlePx = with(LocalDensity.current) { 48.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                state = rememberDraggableState { delta ->
                    if (width > 0f) onPosition(position + delta / width)
                },
            )
            .pointerInput(enabled, width) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    if (width > 0f) onPosition(offset.x / width)
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(position.coerceIn(0f, 1f), 0f..1f)
                stateDescription = speech
                contentDescription = "Compare photo and trace"
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.Center)
                .background(MaterialTheme.field.surface300, RoundedCornerShape(2.dp)),
        )
        val travel = max(0f, width - handlePx)
        val offsetPx = (position.coerceIn(0f, 1f) * travel).roundToInt()
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetPx, 0) }
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(90f),
            )
        }
    }
}

@Composable
private fun DwCompareBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
