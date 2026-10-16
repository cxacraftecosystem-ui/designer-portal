package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.HeadingBlock

/**
 * Where one stage sits inside a whole report document, and how a live copy of it is put back —
 * the handset's port of the web's `components/designworkshop/report/stageSlice.ts`, under the SAME
 * FUNCTION NAMES.
 *
 * Both are held to one fixture, `shared/report-parity/stage-slice-vectors.json`, by
 * `DwStageSliceTest` here and `e2e/stage-slice-unit.spec.ts` on the web, so a designer checking a
 * stage on a laptop and a colleague checking it on a phone are shown the same pages.
 *
 * NOTHING HERE DERIVES A BLOCK. The blocks are always the report builder's own
 * ([buildWorkshopDocument]); these functions only decide where to start and stop reading.
 */

/** Case, surrounding space, runs of whitespace and a leading section NUMBER come off. */
internal fun foldTitle(value: String): String =
    value.replace(Regex("^[\\s\\d.]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

private fun headingText(block: HeadingBlock): String = block.runs.joinToString("") { it.text }

/**
 * `[start, end)` of one stage's blocks, or null when its heading is not in the document: from the
 * heading whose text is the stage's title to the next heading at the same level or SHALLOWER.
 * Null and never an empty range — "prints nothing" and "could not be found" are different answers.
 */
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

/** The blocks belonging to one stage, or null when its heading is not in the document. */
internal fun sliceStageBlocks(blocks: List<Block>, stageTitle: String): List<Block>? =
    stageBlockRange(blocks, stageTitle)?.let { blocks.subList(it.first, it.last + 1).toList() }

/**
 * [base] with this stage's blocks replaced by the stage's blocks from [live], or null when the
 * stage's heading is missing from either — the caller then shows [live] whole rather than guessing
 * where the stage would go.
 */
internal fun spliceStageBlocks(base: List<Block>, live: List<Block>, stageTitle: String): List<Block>? {
    val into = stageBlockRange(base, stageTitle) ?: return null
    val from = stageBlockRange(live, stageTitle) ?: return null
    return base.subList(0, into.first) + live.subList(from.first, from.last + 1) + base.subList(into.last + 1, base.size)
}
