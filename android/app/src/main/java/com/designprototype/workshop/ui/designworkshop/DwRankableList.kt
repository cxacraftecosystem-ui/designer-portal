package com.designprototype.workshop.ui.designworkshop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.designprototype.workshop.data.dwMoveBy
import com.designprototype.workshop.data.dwMoveTo
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DwRankableList(
    order: List<String>,
    labelFor: (String) -> String,
    onReorder: (List<String>) -> Unit,
    disabledReason: String?,
    row: @Composable (id: String, position: Int, total: Int, dragging: Boolean) -> Unit,
) {
    val total = order.size
    val locked = disabledReason != null
    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val density = LocalDensity.current
    val gapPx = with(density) { ROW_GAP.toPx() }
    val gripPx = with(density) { GRIP_TOUCH_TARGET.toPx() }
    val lookaheadPx = with(density) { DW_AUTOSCROLL_LOOKAHEAD.toPx() }
    val armPx = with(density) { DW_AUTOSCROLL_ARM.toPx() }

    val heights = remember { mutableStateMapOf<String, Float>() }
    var drag by remember { mutableStateOf<DwDragState?>(null) }
    var announcement by remember { mutableStateOf("") }

    val autoScroll = remember { DwAutoScroll() }

    val currentOrder by rememberUpdatedState(order)
    val currentReorder by rememberUpdatedState(onReorder)
    val currentLabel by rememberUpdatedState(labelFor)

    fun announceMove(id: String, index: Int) {
        announcement = "${currentLabel(id)} moved to position ${index + 1} of ${currentOrder.size}."
    }

    fun step(id: String, delta: Int) {
        if (locked) return
        val next = dwMoveBy(currentOrder, id, delta)
        if (next == currentOrder) return
        currentReorder(next)
        val landed = next.indexOf(id)
        if (landed >= 0) announceMove(id, landed)
    }

    DisposableEffect(Unit) { onDispose { drag = null } }

    val gestureInFlight = drag != null
    LaunchedEffect(gestureInFlight) {
        if (!gestureInFlight) return@LaunchedEffect
        while (isActive) {
            val heading = drag?.heading ?: 0
            val grip = autoScroll.grip
            if (heading != 0 && grip != null) {
                try {
                    grip.bringIntoView(dwAutoScrollBand(heading, gripPx, lookaheadPx))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
            if (reduceMotion) delay(DW_AUTOSCROLL_DWELL_MILLIS) else withFrameNanos { }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val top = coordinates.positionInWindow().y
                val previous = autoScroll.lastTop
                autoScroll.lastTop = top
                val current = drag
                if (current != null && !previous.isNaN() && previous != top) {
                    drag = current.advancedBy(previous - top, gapPx)
                }
            },
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            if (announcement.isNotEmpty()) {
                Text(
                    announcement,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
            }
        }

        order.forEachIndexed { index, id ->
            val dragging = drag?.key == id
            val target = drag.shiftFor(index, gapPx)
            val gripRequester = remember(id) { BringIntoViewRequester() }
            val shift by animateFloatAsState(
                targetValue = target,
                animationSpec = if (dragging || reduceMotion) tween(0) else tween(180),
                label = "dwRankShift",
            )
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = shift }
                    .onSizeChanged { size ->
                        heights[id] = size.height.toFloat()
                    },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.field.surface200, CircleShape),
                    ) {
                        Text(
                            "${index + 1}",
                            color = MaterialTheme.field.body,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    IconButton(
                        onClick = { step(id, -1) },
                        enabled = !locked && index > 0,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = "Move ${labelFor(id)} up",
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { step(id, 1) },
                        enabled = !locked && index < total - 1,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = "Move ${labelFor(id)} down",
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(GRIP_TOUCH_TARGET)
                            .bringIntoViewRequester(gripRequester)
                            .semantics {
                                contentDescription = if (locked) {
                                    "Reordering unavailable. ${disabledReason.orEmpty()}"
                                } else {
                                    "Reorder ${labelFor(id)}. Drag, or use the move buttons."
                                }
                            }
                            .pointerInput(id, locked) {
                                if (locked) return@pointerInput
                                detectDragGestures(
                                    onDragStart = {
                                        val snapshot = currentOrder
                                        val from = snapshot.indexOf(id)
                                        drag = if (from < 0) {
                                            null
                                        } else {
                                            DwDragState(
                                                key = id,
                                                from = from,
                                                to = from,
                                                offset = 0f,
                                                travel = 0f,
                                                heading = 0,
                                                snapshot = snapshot,
                                                heights = snapshot.map { heights[it] ?: 0f },
                                            )
                                        }
                                        autoScroll.grip = if (from < 0) null else gripRequester
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag = drag?.draggedBy(amount.y, gapPx, armPx)
                                    },
                                    onDragEnd = {
                                        val current = drag
                                        drag = null
                                        autoScroll.grip = null
                                        if (current != null && current.to != current.from) {
                                            if (current.snapshot != currentOrder) {
                                                announcement =
                                                    "${currentLabel(current.key)} was not moved — the list changed. Try again."
                                            } else {
                                                val next =
                                                    dwMoveTo(currentOrder, current.from, current.to)
                                                if (next != currentOrder) {
                                                    currentReorder(next)
                                                    announceMove(current.key, current.to)
                                                }
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        drag = null
                                        autoScroll.grip = null
                                    },
                                )
                            },
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = if (locked) {
                                MaterialTheme.field.placeholder
                            } else {
                                MaterialTheme.field.muted
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (dragging) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp),
                                )
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    key(id) {
                        row(id, index + 1, total, dragging)
                    }
                }
            }
        }

        if (locked) {
            Text(
                disabledReason.orEmpty(),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
    }
}

private val ROW_GAP = 12.dp

private val GRIP_TOUCH_TARGET = 48.dp

private val DW_AUTOSCROLL_LOOKAHEAD = 96.dp

private val DW_AUTOSCROLL_ARM = 8.dp

private const val DW_AUTOSCROLL_DWELL_MILLIS = 140L

@OptIn(ExperimentalFoundationApi::class)
private class DwAutoScroll {
    var lastTop: Float = Float.NaN
    var grip: BringIntoViewRequester? = null
}

internal data class DwDragState(
    val key: String,
    val from: Int,
    val to: Int,
    val offset: Float,
    val travel: Float,
    val heading: Int,
    val snapshot: List<String>,
    val heights: List<Float>,
)

internal fun DwDragState.advancedBy(delta: Float, gap: Float): DwDragState {
    val moved = offset + delta
    val tops = ArrayList<Float>(heights.size)
    var running = 0f
    for (height in heights) {
        tops.add(running)
        running += height + gap
    }
    val ownHeight = heights.getOrNull(from) ?: return copy(offset = moved)
    val ownTop = tops.getOrNull(from) ?: return copy(offset = moved)
    val centre = ownTop + ownHeight / 2f + moved
    var next = from
    heights.indices.forEach { index ->
        if (index == from) return@forEach
        val otherCentre = (tops[index]) + (heights[index] / 2f)
        if (index < from && centre < otherCentre) next = minOf(next, index)
        if (index > from && centre > otherCentre) next = maxOf(next, index)
    }
    return copy(offset = moved, to = next)
}

internal fun DwDragState.draggedBy(delta: Float, gap: Float, arm: Float): DwDragState {
    val moved = advancedBy(delta, gap)
    val travelled = travel + delta
    val nextHeading = when {
        abs(travelled) < arm -> 0
        delta > 0f -> 1
        delta < 0f -> -1
        else -> heading
    }
    return moved.copy(travel = travelled, heading = nextHeading)
}

internal fun dwAutoScrollBand(heading: Int, gripHeight: Float, lookahead: Float): Rect = when {
    heading > 0 -> Rect(left = 0f, top = gripHeight, right = 1f, bottom = gripHeight + lookahead)
    heading < 0 -> Rect(left = 0f, top = -lookahead, right = 1f, bottom = 0f)
    else -> Rect.Zero
}

internal fun DwDragState?.shiftFor(index: Int, gap: Float): Float {
    val drag = this ?: return 0f
    val ownHeight = drag.heights.getOrNull(drag.from) ?: return 0f
    val step = ownHeight + gap
    return when {
        index == drag.from -> drag.offset
        index > drag.from && index <= drag.to -> -step
        index < drag.from && index >= drag.to -> step
        else -> 0f
    }
}
