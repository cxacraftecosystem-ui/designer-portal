package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one layout every row of buttons goes through, so the rule lives in one place.
 *
 * - One button: centred on its line.
 * - Two buttons: one line, never wrapping. Equal halves on a compact width, content width
 *   (each capped at half the line) and centred on a wider one.
 * - Three or more: stacked full width on a compact width; a centred row of pills on a wider
 *   one, wrapping only when the line genuinely runs out.
 *
 * "Compact" is the row's own width below [ACTION_ROW_COMPACT_WIDTH], the same 600dp line as
 * the compact window size class; measuring the row instead of the window keeps a row inside a
 * narrow card or dialog compact on a tablet too.
 *
 * Mark a destructive action with [Modifier.destructiveAction]: it is placed last and set apart
 * by a wider gap.
 *
 * [toolbar] is for a set of small controls that act on one thing (a preview's zoom), not a set of
 * actions: every child shares ONE line in equal cells at every width and count, and never stacks.
 * Give each child an icon and a short label (or a content description) and a 48dp height; four
 * fit a 360dp phone. A toolbar has no destructive action.
 */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    spacing: Dp = ACTION_ROW_SPACING,
    toolbar: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val apart = (spacing * 2).roundToPx()
        val ordered = measurables.sortedBy { if (it.isDestructive()) 1 else 0 }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            ordered.sumOf { it.maxIntrinsicWidth(constraints.maxHeight) } + gap * (ordered.size - 1).coerceAtLeast(0)
        }
        val compact = width < ACTION_ROW_COMPACT_WIDTH.roundToPx()
        val loose = Constraints(maxWidth = width)
        when {
            ordered.isEmpty() -> layout(width, 0) {}
            toolbar -> {
                val cell = toolbarCellWidth(width, ordered.size, gap)
                val height = ordered.maxOf { it.minIntrinsicHeight(cell) }
                val placeables = ordered.map { it.measure(Constraints(minWidth = cell, maxWidth = cell, minHeight = height)) }
                val rowHeight = placeables.maxOf { it.height }
                layout(width, rowHeight) {
                    placeables.forEachIndexed { i, p -> p.placeRelative(i * (cell + gap), (rowHeight - p.height) / 2) }
                }
            }
            ordered.size == 1 -> {
                val p = ordered[0].measure(loose)
                layout(width, p.height) { p.placeRelative((width - p.width) / 2, 0) }
            }
            ordered.size == 2 -> {
                val half = ((width - gap) / 2).coerceAtLeast(0)
                val height = ordered.maxOf { it.minIntrinsicHeight(half) }
                val placeables = if (compact) {
                    ordered.map { it.measure(Constraints(minWidth = half, maxWidth = half, minHeight = height)) }
                } else {
                    ordered.map { it.measure(Constraints(maxWidth = half, minHeight = height)) }
                }
                val rowHeight = placeables.maxOf { it.height }
                val used = placeables.sumOf { it.width } + gap
                var x = if (compact) 0 else ((width - used) / 2).coerceAtLeast(0)
                layout(width, rowHeight) {
                    placeables.forEach { p ->
                        p.placeRelative(x, (rowHeight - p.height) / 2)
                        x += p.width + gap
                    }
                }
            }
            compact -> {
                val placeables = ordered.map { it.measure(Constraints.fixedWidth(width)) }
                var total = 0
                placeables.forEachIndexed { i, p ->
                    if (i > 0) total += if (ordered[i].isDestructive() && !ordered[i - 1].isDestructive()) apart else gap
                    total += p.height
                }
                layout(width, total) {
                    var y = 0
                    placeables.forEachIndexed { i, p ->
                        if (i > 0) y += if (ordered[i].isDestructive() && !ordered[i - 1].isDestructive()) apart else gap
                        p.placeRelative(0, y)
                        y += p.height
                    }
                }
            }
            else -> {
                val height = ordered.maxOf { it.minIntrinsicHeight(width) }
                val placeables = ordered.map { it.measure(Constraints(maxWidth = width, minHeight = height)) }
                // Greedy lines, each centred.
                val lines = mutableListOf<MutableList<Int>>()
                var lineWidth = 0
                placeables.forEachIndexed { i, p ->
                    val lead = if (i == 0) 0 else gapBefore(ordered, i, gap, apart)
                    if (lines.isEmpty() || lineWidth + lead + p.width > width) {
                        lines += mutableListOf(i)
                        lineWidth = p.width
                    } else {
                        lines.last() += i
                        lineWidth += lead + p.width
                    }
                }
                val lineHeights = lines.map { line -> line.maxOf { placeables[it].height } }
                val total = lineHeights.sum() + gap * (lines.size - 1)
                layout(width, total) {
                    var y = 0
                    lines.forEachIndexed { li, line ->
                        val used = line.mapIndexed { k, i -> placeables[i].width + if (k == 0) 0 else gapBefore(ordered, i, gap, apart) }.sum()
                        var x = ((width - used) / 2).coerceAtLeast(0)
                        line.forEachIndexed { k, i ->
                            if (k > 0) x += gapBefore(ordered, i, gap, apart)
                            val p = placeables[i]
                            p.placeRelative(x, y + (lineHeights[li] - p.height) / 2)
                            x += p.width
                        }
                        y += lineHeights[li] + gap
                    }
                }
            }
        }
    }
}

/** Places this action last in its [ActionRow] and sets it apart from the others. */
fun Modifier.destructiveAction(): Modifier = this.then(DestructiveActionData)

private object DestructiveActionData : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = DestructiveActionData
}

private fun Measurable.isDestructive(): Boolean = parentData === DestructiveActionData

private fun gapBefore(ordered: List<Measurable>, i: Int, gap: Int, apart: Int): Int =
    if (ordered[i].isDestructive() && !ordered[i - 1].isDestructive()) apart else gap

/** The width of each of [count] equal cells in a toolbar [ActionRow] of [width] with [gap] between. */
fun toolbarCellWidth(width: Int, count: Int, gap: Int): Int =
    if (count <= 0) 0 else ((width - gap * (count - 1)) / count).coerceAtLeast(0)

/** Gap between actions in a row; destructive actions sit twice this far from the rest. */
val ACTION_ROW_SPACING: Dp = 8.dp

/** Below this width a row of actions lays out for a phone. */
val ACTION_ROW_COMPACT_WIDTH: Dp = 600.dp
