package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.HeadingBlock

internal fun foldTitle(value: String): String =
    value.replace(Regex("^[\\s\\d.]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

private fun headingText(block: HeadingBlock): String = block.runs.joinToString("") { it.text }

internal fun stageBlockRange(blocks: List<Block>, stageTitle: String): IntRange? {
    val wanted = foldTitle(stageTitle)
    if (wanted.isEmpty()) return null
    val startAt = blocks.indexOfFirst { it is HeadingBlock && foldTitle(headingText(it)) == wanted }
    if (startAt < 0) return null
    val level = (blocks[startAt] as HeadingBlock).level
    var endAt = blocks.size
    for (at in startAt + 1 until blocks.size) {
        val block = blocks[at]
        if (block is HeadingBlock && block.level <= level) {
            endAt = at
            break
        }
    }
    return startAt until endAt
}

internal fun sliceStageBlocks(blocks: List<Block>, stageTitle: String): List<Block>? =
    stageBlockRange(blocks, stageTitle)?.let { blocks.subList(it.first, it.last + 1).toList() }

internal fun spliceStageBlocks(base: List<Block>, live: List<Block>, stageTitle: String): List<Block>? {
    val into = stageBlockRange(base, stageTitle) ?: return null
    val from = stageBlockRange(live, stageTitle) ?: return null
    return base.subList(0, into.first) + live.subList(from.first, from.last + 1) + base.subList(into.last + 1, base.size)
}
