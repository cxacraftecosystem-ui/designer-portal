package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.CalloutBlock
import com.designprototype.workshop.report.ChartBlock
import com.designprototype.workshop.report.CoverBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ImageBlock
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.MapBlock
import com.designprototype.workshop.report.MetricRowBlock
import com.designprototype.workshop.report.PageBreakBlock
import com.designprototype.workshop.report.PageSize
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.SignatureBlock
import com.designprototype.workshop.report.SpacerBlock
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TocBlock

const val DW_PX_PER_MM: Float = 96f / 25.4f

const val DW_DEFAULT_MARGIN_MM: Float = 25f

data class DwPageGeometry(
    val pageSize: PageSize,
    val widthMm: Float,
    val heightMm: Float,
    val marginMm: Float,
    val pageWidthPx: Float,
    val pageHeightPx: Float,
    val textWidthPx: Float,
    val textHeightPx: Float,
) {
    val label: String
        get() = "${pageSize.name} · ${trimMm(widthMm)} × ${trimMm(heightMm)} mm"

    val marginLabel: String
        get() = "${trimMm(marginMm)} mm margins"
}

private fun trimMm(value: Float): String {
    val rounded = Math.round(value * 10f) / 10f
    return if (rounded == Math.round(rounded).toFloat()) Math.round(rounded).toString() else rounded.toString()
}

fun dwPageGeometry(pageSize: PageSize, marginMm: Float = DW_DEFAULT_MARGIN_MM): DwPageGeometry {
    val (widthMm, heightMm) = pageSize.sizeMm
    val safe = if (marginMm.isFinite()) marginMm else DW_DEFAULT_MARGIN_MM
    val cap = minOf(widthMm, heightMm) * 0.4f
    val margin = safe.coerceIn(0f, cap)
    return DwPageGeometry(
        pageSize = pageSize,
        widthMm = widthMm,
        heightMm = heightMm,
        marginMm = margin,
        pageWidthPx = widthMm * DW_PX_PER_MM,
        pageHeightPx = heightMm * DW_PX_PER_MM,
        textWidthPx = (widthMm - 2f * margin) * DW_PX_PER_MM,
        textHeightPx = (heightMm - 2f * margin) * DW_PX_PER_MM,
    )
}

data class FlowItem(
    val blockIndex: Int,
    val heightPx: Float,
    val gapBeforePx: Float = 0f,
    val breakBefore: Boolean = false,
    val breakAfter: Boolean = false,
    val keepWithNext: Boolean = false,
    val splittable: Boolean = false,
    val breakOffsetsPx: List<Float> = emptyList(),
    val repeatHeaderPx: Float = 0f,
    val preferWhole: Boolean = false,
    val isCover: Boolean = false,
)

data class PageSlice(
    val blockIndex: Int,
    val part: Int,
    val parts: Int,
    val offsetPx: Float,
    val heightPx: Float,
    val gapBeforePx: Float,
    val repeatHeaderPx: Float,
    val scale: Float,
)

data class PackedPage(
    val pageNumber: Int,
    val isCover: Boolean,
    val slices: List<PageSlice>,
    val usedPx: Float,
    val capacityPx: Float,
)

data class ScaledBlock(val blockIndex: Int, val pageNumber: Int, val scale: Float)

data class PackedDocument(
    val pages: List<PackedPage>,
    val scaled: List<ScaledBlock>,
    val divided: List<Int>,
    val abandoned: List<Int>,
)

data class PageBudget(val contentPx: Float, val coverContentPx: Float? = null)

private const val EPSILON = 0.5f

internal fun usableOffsets(item: FlowItem): List<Float> {
    val kept = ArrayList<Float>()
    for (offset in item.breakOffsetsPx) {
        if (!offset.isFinite()) continue
        if (offset <= EPSILON || offset >= item.heightPx - EPSILON) continue
        if (kept.any { Math.abs(it - offset) <= EPSILON }) continue
        kept.add(offset)
    }
    kept.sort()
    return kept
}

internal fun keepRunHeight(items: List<FlowItem>, index: Int, contentPx: Float): Float {
    var need = 0f
    var k = index
    while (k < items.size && items[k].keepWithNext) {
        need += (if (k > index) items[k].gapBeforePx else 0f) + items[k].heightPx
        k += 1
        if (need > contentPx) return 0f
    }
    val next = if (k < items.size) items[k] else null
    if (next != null && !next.breakBefore && !next.isCover) {
        val offsets = usableOffsets(next)
        val firstPiece = if (next.splittable && offsets.isNotEmpty()) offsets[0] else next.heightPx
        need += next.gapBeforePx + firstPiece
    }
    return if (need > contentPx) 0f else need
}

fun packPages(items: List<FlowItem>, budget: PageBudget): PackedDocument {
    val contentPx = maxOf(1f, budget.contentPx)
    val coverPx = maxOf(1f, budget.coverContentPx ?: contentPx)

    class Building(
        val blockIndex: Int,
        var part: Int,
        var parts: Int,
        val offsetPx: Float,
        val heightPx: Float,
        val gapBeforePx: Float,
        val repeatHeaderPx: Float,
        val scale: Float,
    ) {
        fun freeze() =
            PageSlice(blockIndex, part, parts, offsetPx, heightPx, gapBeforePx, repeatHeaderPx, scale)
    }

    class BuildingPage(
        val pageNumber: Int,
        val isCover: Boolean,
        val slices: List<Building>,
        val usedPx: Float,
        val capacityPx: Float,
    )

    val pages = ArrayList<BuildingPage>()
    val scaled = ArrayList<ScaledBlock>()
    val divided = ArrayList<Int>()
    val abandoned = ArrayList<Int>()

    var slices = ArrayList<Building>()
    var used = 0f
    var cover = false

    fun capacity(): Float = if (cover) coverPx else contentPx

    fun closePage() {
        if (slices.isEmpty()) {
            cover = false
            return
        }
        pages.add(BuildingPage(pages.size + 1, cover, slices, used, capacity()))
        slices = ArrayList()
        used = 0f
        cover = false
    }

    for (i in items.indices) {
        val item = items[i]

        if (item.isCover || item.breakBefore) closePage()
        if (item.isCover) cover = true

        if (item.keepWithNext && slices.isNotEmpty()) {
            val need = keepRunHeight(items, i, contentPx)
            if (item.gapBeforePx + need > capacity() - used + EPSILON) closePage()
        }

        val offsets = usableOffsets(item)
        val canSplit = item.splittable && offsets.isNotEmpty()
        val made = ArrayList<Building>()
        var cursor = 0f
        val limit = 2 * (offsets.size + 2) + 4
        var steps = 0

        while (cursor < item.heightPx - EPSILON || made.isEmpty()) {
            if (steps >= limit) {
                abandoned.add(item.blockIndex)
                break
            }
            steps += 1

            val first = made.isEmpty()
            val gap = if (first && slices.isNotEmpty()) item.gapBeforePx else 0f
            val header = if (first) 0f else item.repeatHeaderPx
            val cap = capacity()
            val free = cap - used - gap - header
            val left = item.heightPx - cursor

            fun emit(height: Float, scale: Float) {
                val slice = Building(item.blockIndex, made.size, 1, cursor, height, gap, header, scale)
                slices.add(slice)
                made.add(slice)
                used += gap + header + height * scale
            }

            if (left <= free + EPSILON) {
                emit(left, 1f)
                cursor = item.heightPx
                break
            }

            if (!canSplit || (item.preferWhole && first && item.heightPx <= cap + EPSILON)) {
                if (slices.isNotEmpty()) {
                    closePage()
                    continue
                }
                val scale = cap / left
                emit(left, scale)
                scaled.add(ScaledBlock(item.blockIndex, pages.size + 1, scale))
                cursor = item.heightPx
                break
            }

            val reach = cursor + free
            var cut = -1f
            for (offset in offsets) {
                if (offset > cursor + EPSILON && offset <= reach + EPSILON) cut = offset
            }

            if (cut < 0f) {
                if (slices.isNotEmpty()) {
                    closePage()
                    continue
                }
                val next = offsets.firstOrNull { it > cursor + EPSILON } ?: item.heightPx
                val piece = next - cursor
                val scale = free / piece
                emit(piece, scale)
                scaled.add(ScaledBlock(item.blockIndex, pages.size + 1, scale))
                cursor = next
                if (cursor >= item.heightPx - EPSILON) break
                closePage()
                continue
            }

            emit(cut - cursor, 1f)
            cursor = cut
            closePage()
        }

        for (k in made.indices) {
            made[k].part = k
            made[k].parts = made.size
        }
        if (made.size > 1) divided.add(item.blockIndex)

        if (item.isCover || item.breakAfter) closePage()
    }

    closePage()

    val frozen = pages.map { page ->
        PackedPage(
            pageNumber = page.pageNumber,
            isCover = page.isCover,
            slices = page.slices.map { it.freeze() },
            usedPx = page.usedPx,
            capacityPx = page.capacityPx,
        )
    }.toMutableList()

    if (frozen.isEmpty()) {
        frozen.add(PackedPage(1, false, emptyList(), 0f, contentPx))
    }

    return PackedDocument(frozen, scaled, divided, abandoned)
}

data class DwBlockRule(
    val splittable: Boolean = false,
    val keepWithNext: Boolean = false,
    val preferWhole: Boolean = false,
    val breakAfter: Boolean = false,
    val isCover: Boolean = false,
    val repeatsHeader: Boolean = false,
)

fun dwBlockRule(block: Block): DwBlockRule = when (block) {
    is CoverBlock -> DwBlockRule(isCover = true)

    is TocBlock -> DwBlockRule(breakAfter = true)

    is HeadingBlock -> DwBlockRule(keepWithNext = true)

    is ParagraphBlock -> DwBlockRule(splittable = true)

    is BulletListBlock -> DwBlockRule(splittable = true)

    is KeyValueBlock -> DwBlockRule(splittable = true)

    is TableBlock -> DwBlockRule(splittable = true, repeatsHeader = true)

    is ImageGridBlock -> DwBlockRule(splittable = true)

    is CalloutBlock -> DwBlockRule(splittable = true, preferWhole = true)

    is ImageBlock -> DwBlockRule()
    is ChartBlock -> DwBlockRule()
    is MapBlock -> DwBlockRule()
    is MetricRowBlock -> DwBlockRule()
    is SignatureBlock -> DwBlockRule()
    is SpacerBlock -> DwBlockRule()

    is PageBreakBlock -> DwBlockRule()
}

fun dwPlanFlow(
    blocks: List<Block>,
    gapPx: Float,
    heightOf: (Int) -> Float?,
    offsetsOf: (Int) -> List<Float>,
    headerOf: (Int) -> Float,
): List<FlowItem> {
    val items = ArrayList<FlowItem>()
    var pendingBreak = false
    var first = true

    for ((index, block) in blocks.withIndex()) {
        if (block is PageBreakBlock) {
            pendingBreak = true
            continue
        }
        val rule = dwBlockRule(block)
        val height = heightOf(index) ?: return emptyList()
        items.add(
            FlowItem(
                blockIndex = index,
                heightPx = height,
                gapBeforePx = if (first) 0f else gapPx,
                breakBefore = pendingBreak,
                breakAfter = rule.breakAfter,
                keepWithNext = rule.keepWithNext,
                splittable = rule.splittable,
                breakOffsetsPx = if (rule.splittable) offsetsOf(index) else emptyList(),
                repeatHeaderPx = if (rule.repeatsHeader) headerOf(index) else 0f,
                preferWhole = rule.preferWhole,
                isCover = rule.isCover,
            )
        )
        pendingBreak = false
        first = false
    }

    return items
}
