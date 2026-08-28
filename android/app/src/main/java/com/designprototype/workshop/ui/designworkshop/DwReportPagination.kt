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

/**
 * Blocks flowing into fixed-height pages — the arithmetic, with no Compose and no Android in it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THIS IS A PORT. THE ORIGINAL IS `frontend/components/designworkshop/report/reportPagination.ts`
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Not "based on", not "the same idea as": a line-for-line port, deliberately keeping the web's own
 * names — `FlowItem`, `PageSlice`, `PackedPage`, `PackedDocument`, `usableOffsets`, `keepRunHeight`,
 * `packPages`, `EPSILON` — so that a divergence between the two clients is a `diff` rather than an
 * archaeology. `DwReportPaginationTest` runs the SAME cases as `frontend/e2e/report-pagination-unit.spec.ts`,
 * in the same order, with the same numbers, for exactly that reason: if one client is taught a rule
 * the other is not, a test fails on the side that was not taught it.
 *
 * WHY A PORT AND NOT A SECOND ALGORITHM. There are four renderers of this document —
 * `backend/app/services/report_docx.py`, `report_pdf.py`, and this device's own `DocxWriter.kt` and
 * `PdfWriter.kt` — and a ministry receives what they produce, so they must agree line for line. Two
 * PREVIEWS that disagreed about where a page ends would be a fifth and a sixth opinion: a designer
 * who approved the layout on a laptop and a colleague who checked it on a handset would be looking
 * at two different documents and neither would be the file. The packing rule therefore lives once,
 * in one shape, on both clients.
 *
 * ── WHAT "PX" MEANS ON THIS SIDE ────────────────────────────────────────────────────────────
 *
 * Every length here is in REFERENCE UNITS: the units of an A4 page drawn at [DW_PX_PER_MM], which is
 * `96 / 25.4` — one CSS pixel per 1/96 inch, the same constant `previewModel.ts` uses. So an A4 page
 * is 793.7 units wide and 1122.5 tall on both clients, and a block measured as 240 units tall on a
 * handset is the same fraction of the page as a block measured at 240 px in a browser. The screen
 * multiplies those units by one scale factor at draw time, which is what makes a sheet on a phone
 * the same document as a sheet on a laptop rather than a differently-proportioned one. The field
 * names keep the web's `Px` suffix rather than being renamed to `Units`, because the point of the
 * port is that the two files read as one file.
 *
 * ── WHAT DECIDES WHERE A BREAK MAY FALL: THE FILE WRITERS, NOT THIS FILE ────────────────────
 *
 * Every rule below is read off `report_pdf.py` (the writer whose output is a fixed page) and its
 * on-device twin `PdfWriter.kt`, and each is cited where it is applied:
 *
 *   · a PARAGRAPH breaks between LINES            — `_draw_lines` calls `_ensure(line.height)`
 *   · a BULLETLIST breaks between ITEMS           — `_block_bullets`
 *   · a TABLE breaks between ROWS **and repeats
 *     its header over the continuation**          — `place_row` calls `place_header()` after
 *                                                   `_new_page()`, "exactly as Word repeats a
 *                                                   `<w:tblHeader/>` row"
 *   · a KEYVALUE grid breaks between PAIRS        — `_simple_grid`
 *   · an IMAGEGRID breaks between GRID ROWS       — `_block_image_grid` loops `range(0, n, cols)`
 *   · a CALLOUT is moved WHOLE while it fits any
 *     page, and only cut when it fits none        — `_cut_row`, whose docstring calls the
 *                                                   fits-a-page case "an ordinary break"
 *   · an IMAGE, a CHART, a MAP, a METRICROW and a
 *     SIGNATURE block are never divided           — `_draw_image` reserves the whole box;
 *                                                   `_block_metrics`/`_block_signatures` `_ensure`
 *                                                   the whole height inside `_locked`
 *   · a HEADING is never last on a page           — `_block_heading` reserves its lead, its own
 *                                                   lines, its trail AND one body line
 *                                                   (`self.base_size * 1.32`) before drawing
 *   · a COVER is exactly one page and carries no
 *     running head or foot                        — `_block_cover` ends with `self._new_page()`
 *
 * ── THE ONE PLACE A PREVIEW CANNOT DO WHAT THE FILE DOES, STATED RATHER THAN HIDDEN ─────────
 *
 * A single unsplittable thing taller than a whole page. The .pdf never has one for a picture,
 * because it caps a photograph at 0.62 of the text column, a chart or map at 0.58 and a grid
 * photograph at 0.30 — it SCALES, so the picture always fits. For the rest — a table row of six
 * thousand characters, a callout longer than a page — the .pdf CUTS, mid-cell, and repeats the
 * header above the remainder; a preview cannot cut mid-cell without measuring every line box inside
 * every cell, so it does what the writers do to a picture instead: it scales the block down onto one
 * page and [PackedDocument.scaled] reports it so the screen can say so. Nothing is clipped and
 * nothing is dropped silently — a silently clipped figure is the bug class this repository hates
 * most, and `_cut_row`'s own docstring settles the principle for the file: "A REFUSAL, NOT A
 * TRUNCATION."
 */

/**
 * One CSS pixel per 1/96 inch — the constant `previewModel.ts` calls `PX_PER_MM`.
 *
 * It is here rather than in the drawing code because [dwPageGeometry] is the one place that turns
 * the payload's millimetres into the units [packPages] counts in, and both clients have to turn
 * them into the same ones.
 */
const val DW_PX_PER_MM: Float = 96f / 25.4f

/** `ReportMeta.marginMm`'s own default, repeated here so geometry can be asked for without a document. */
const val DW_DEFAULT_MARGIN_MM: Float = 25f

/**
 * The paper, in the units the packer counts in.
 *
 * [marginMm] comes from `ReportMeta.margin_mm`, which `report_pdf.py:509-512` reads to size its own
 * canvas — the preview must read the same field rather than assume a number, because the margin
 * decides the text column and the text column decides every line break.
 */
data class DwPageGeometry(
    val pageSize: PageSize,
    val widthMm: Float,
    val heightMm: Float,
    val marginMm: Float,
    /** Sheet width in reference units. */
    val pageWidthPx: Float,
    /** Sheet height in reference units. */
    val pageHeightPx: Float,
    /** The text column: the sheet less both margins. */
    val textWidthPx: Float,
    /** The sheet less both margins, before the running head and foot are taken off. */
    val textHeightPx: Float,
) {
    /** "A4 · 210 × 297 mm" — the strip prints it so the reader knows which paper they are judging. */
    val label: String
        get() = "${pageSize.name} · ${trimMm(widthMm)} × ${trimMm(heightMm)} mm"

    /**
     * "25 mm margins".
     *
     * ON THE STRIP RATHER THAN ONLY IN THE CODE, because the margin decides the text column and the
     * text column decides every line break — so it decides where the pages fall. A reader checking a
     * break has to be able to see which paper and which margin they are checking it against.
     */
    val marginLabel: String
        get() = "${trimMm(marginMm)} mm margins"
}

private fun trimMm(value: Float): String {
    val rounded = Math.round(value * 10f) / 10f
    return if (rounded == Math.round(rounded).toFloat()) Math.round(rounded).toString() else rounded.toString()
}

/**
 * The paper for a page size and a margin.
 *
 * The margin is CLAMPED against the paper rather than trusted: a template that asked for 120 mm
 * margins on A4 would otherwise produce a negative text column, and every length downstream — line
 * wrap, page capacity, the packer's own arithmetic — would be computed from it. `previewModel.ts`
 * clamps at the same two ends.
 */
fun dwPageGeometry(pageSize: PageSize, marginMm: Float = DW_DEFAULT_MARGIN_MM): DwPageGeometry {
    val (widthMm, heightMm) = pageSize.sizeMm
    val safe = if (marginMm.isFinite()) marginMm else DW_DEFAULT_MARGIN_MM
    // At most 40% of the shorter side each, which still leaves a fifth of the paper to print on.
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

/**
 * One block, measured, with the rules that govern where it may be cut.
 *
 * The five fields the packer genuinely needs are [blockIndex], [heightPx], [breakBefore],
 * [keepWithNext] and [splittable]. Everything else is optional and every one of them earns its place
 * by naming a behaviour of a FILE writer that would otherwise be lost:
 *
 *   [gapBeforePx]     the space between this block and the one above it, measured rather than
 *                     recomputed. Spent only mid-page: a page break eats the gap, the same way
 *                     `_new_page` resets the cursor to the top margin.
 *   [breakOffsetsPx]  the offsets, from this block's own top, at which a cut is LEGAL — row bottoms,
 *                     item bottoms, line-box bottoms. A [splittable] block with none of them behaves
 *                     as unsplittable, which is the honest reading of "the measurer could not find a
 *                     place to cut this".
 *   [repeatHeaderPx]  the height of the header a continuation page redraws (tables only).
 *   [preferWhole]     move to the next page whole rather than divide, while the block fits a page at
 *                     all. `_cut_row` refuses to cut in exactly that case.
 *   [breakAfter]      the block ends its page — a TOC, which `_block_toc` follows with `_new_page`.
 *   [isCover]         one page to itself, and no running furniture on it.
 */
data class FlowItem(
    /** Index into the caller's own block list. Slices carry it back so the caller can draw them. */
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

/**
 * One block, or one piece of one, on one page.
 *
 * [offsetPx] and [heightPx] are a WINDOW onto the block's own box: the caller draws the whole block
 * inside a clip of [heightPx], pushed up by [offsetPx]. That is what makes a continuation exact
 * rather than re-laid-out — the second half of a paragraph is the same line boxes the first half was
 * measured against, not a fresh wrap at a different width.
 */
data class PageSlice(
    val blockIndex: Int,
    /** 0-based piece of this block; 0 for a block that was not divided. */
    val part: Int,
    /** How many pieces the block ended up in. 1 means whole. */
    val parts: Int,
    /** Where this piece starts inside the block, in reference units from the block's top. */
    val offsetPx: Float,
    /** How tall this piece is, before [scale]. */
    val heightPx: Float,
    /** The measured gap above it. Always 0 for the first slice on a page. */
    val gapBeforePx: Float,
    /** A table header redrawn above a continuation, as `place_row` does. 0 on the first piece. */
    val repeatHeaderPx: Float,
    /** 1 normally. Below 1 for a block too tall for any page — reported in [PackedDocument.scaled]. */
    val scale: Float,
)

data class PackedPage(
    /** 1-based: the N in the "Page N of M" every renderer draws in its own foot. */
    val pageNumber: Int,
    /** The cover is one page and carries no running head or foot, in the file and here. */
    val isCover: Boolean,
    val slices: List<PageSlice>,
    /** What the slices came to, including gaps, repeated headers and scaling. */
    val usedPx: Float,
    val capacityPx: Float,
)

data class ScaledBlock(val blockIndex: Int, val pageNumber: Int, val scale: Float)

data class PackedDocument(
    val pages: List<PackedPage>,
    /**
     * Blocks that could not fit a page and were drawn scaled down onto one. NEVER empty silently:
     * the caller prints the count, because a figure quietly shrunk is a figure a designer approves at
     * a size the file will not use.
     */
    val scaled: List<ScaledBlock>,
    /** Blocks divided across pages, as the file divides them. */
    val divided: List<Int>,
    /**
     * Blocks the packer gave up on — always empty in practice, and reported rather than swallowed.
     * The loop below advances the cursor at least every second iteration, so the bound cannot be
     * reached by any measurement Compose can produce; if it ever is, a block is MISSING from the
     * pages and the reader has to be told rather than shown a document with a hole in it.
     */
    val abandoned: List<Int>,
)

/**
 * @param contentPx the content box of an ordinary page: the sheet less its margins, its running head
 *   and its running foot.
 * @param coverContentPx the cover's, which is taller because it carries no furniture. Defaults to
 *   [contentPx].
 */
data class PageBudget(val contentPx: Float, val coverContentPx: Float? = null)

/**
 * Half a unit.
 *
 * Heights here come from a Compose measure pass divided by the display density, which is sub-unit: a
 * block measured at 247.0000001 against a content box of 247 is the same block, and treating it as
 * an overflow would start a new page for a rounding error and do it on every page of the document.
 * The web's `EPSILON` is the same half-pixel for the same reason.
 */
private const val EPSILON = 0.5f

/** Ascending, inside the block, and de-duplicated — a repeated offset would cut a zero-height slice. */
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

/**
 * What a heading needs beneath it before it may be drawn.
 *
 * `_block_heading` reserves `lead + its own lines + trail + self.base_size * 1.32` — the last term
 * being ONE BODY LINE, and that term is the whole of keep-with-next: "a heading with nothing under it
 * is still an orphan even if it fits". So the run of consecutive keep-with-next blocks is measured
 * together with the first legal piece of whatever follows them.
 *
 * AN IMPOSSIBLE KEEP IMPOSES NO CONSTRAINT AT ALL, which is the half that is easy to get wrong. When
 * the run plus the first piece of what follows would not fit an EMPTY page, turning the page cannot
 * satisfy it — so this returns 0 rather than a demand nothing can meet, and the heading is placed
 * where it stands. Demanding a whole page instead would send every heading followed by a full-page
 * figure to a fresh sheet and leave the bottom of the previous one blank for nothing. The .pdf paid
 * for that same reading once in `_block_table`, where reserving a header plus an over-tall first row
 * "turned a page the row was going to start on anyway: the table's own first page came out
 * completely blank".
 */
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
        // The first piece of what follows. For a divisible block that is its first legal cut — one
        // line of a paragraph, one row of a table, which is exactly what `_block_heading` reserves.
        // For an indivisible one it is the whole block: reserving a fraction of a figure that would
        // then move to the next page on its own leaves the heading orphaned anyway, which is the
        // outcome keep-with-next exists to prevent.
        val firstPiece = if (next.splittable && offsets.isNotEmpty()) offsets[0] else next.heightPx
        need += next.gapBeforePx + firstPiece
    }
    return if (need > contentPx) 0f else need
}

/**
 * Flow the measured blocks into fixed-height pages.
 *
 * Deterministic and total: the same input always produces the same pages, and every block reaches one
 * of them — whole, divided, or scaled and reported.
 */
fun packPages(items: List<FlowItem>, budget: PageBudget): PackedDocument {
    val contentPx = maxOf(1f, budget.contentPx)
    val coverPx = maxOf(1f, budget.coverContentPx ?: contentPx)

    /*
      A SLICE IS BUILT MUTABLE AND FROZEN AT THE END, which is the one structural difference from the
      TypeScript and is worth a sentence because it is the kind of difference that becomes a bug.

      `parts` — how many pieces a block ended up in — is not knowable until the block has been fully
      laid, and by then its first piece is usually on a page that has already been CLOSED. The web
      gets that for free: its slices are JavaScript objects and it writes `made[k].parts` after the
      fact, reaching the same object the closed page is holding. Kotlin's `PageSlice` is an immutable
      data class, so this port carries a mutable twin through the loop and freezes every slice once,
      at the bottom. Doing it the other way — copying, then hunting for the copy inside a closed page
      — is how a `parts` of 1 ends up on a continuation nobody notices until a designer asks why a
      table that was never divided is drawn with its header repeated.
    */
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

        // A break the template asked for is a break in the FILE — every writer honours a `PAGEBREAK`
        // exactly — so it wins over everything the measurements say, including a page with room left.
        if (item.isCover || item.breakBefore) closePage()
        if (item.isCover) cover = true

        // Keep-with-next, decided BEFORE the block is placed: if the heading and what has to follow
        // it do not both fit here, the heading turns the page rather than being drawn and orphaned.
        if (item.keepWithNext && slices.isNotEmpty()) {
            val need = keepRunHeight(items, i, contentPx)
            if (item.gapBeforePx + need > capacity() - used + EPSILON) closePage()
        }

        val offsets = usableOffsets(item)
        val canSplit = item.splittable && offsets.isNotEmpty()
        val made = ArrayList<Building>()
        var cursor = 0f
        // Every iteration either advances `cursor` past one offset or turns a page, and a page can
        // only be turned when one is open — so two iterations per offset plus a handful is a hard
        // bound rather than a hopeful one. It exists so that a measurement this file has not imagined
        // cannot hang the composition; `abandoned` is what says out loud that it was reached.
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

            // The whole-block strategies: a block that may not be divided at all, and a block that
            // may be but would rather not be while it still fits a page of its own (`_cut_row`'s
            // "ordinary break"). Both answers are the same — turn the page, and try again on an
            // empty one.
            if (!canSplit || (item.preferWhole && first && item.heightPx <= cap + EPSILON)) {
                if (slices.isNotEmpty()) {
                    closePage()
                    continue
                }
                // An empty page and it still does not fit: taller than any page there is. Scaled onto
                // one page and reported, never clipped. See this file's header for why scaling is the
                // choice.
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
                // A single row taller than an empty page. The .pdf cuts it mid-cell and repeats the
                // header; the preview scales that one piece instead and says so, for the reason in
                // the header. The cursor still advances, so the rest of the block paginates normally.
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

    // A document whose every block was a page break still has to render as something. One empty
    // sheet reads as "this template produced nothing", which is the truth and is what the designer
    // needs to see; zero sheets would render as an empty panel indistinguishable from a load that
    // had not finished. (Carried forward verbatim from the web's `packPages`, and before it from
    // `splitIntoSheets`, which this replaced there.)
    if (frozen.isEmpty()) {
        frozen.add(PackedPage(1, false, emptyList(), 0f, contentPx))
    }

    return PackedDocument(frozen, scaled, divided, abandoned)
}

// --------------------------------------------------------------------------------------
// What each block type is allowed to do to a page — the static half of the plan
// --------------------------------------------------------------------------------------

/**
 * The rules a block carries into [packPages] before anything is measured.
 *
 * SEPARATED FROM THE MEASUREMENT ON PURPOSE. What a block MAY do to a page is a fact about the file
 * writers and is knowable without a screen; how tall it is is a fact about this device. Keeping the
 * first half here makes it testable in a JVM unit test, and makes the second half — the part that
 * needs Compose — small enough to read.
 */
data class DwBlockRule(
    val splittable: Boolean = false,
    val keepWithNext: Boolean = false,
    val preferWhole: Boolean = false,
    val breakAfter: Boolean = false,
    val isCover: Boolean = false,
    /** A continuation page redraws this block's header above the remainder. Tables only. */
    val repeatsHeader: Boolean = false,
)

/**
 * The file writers' rules, per block type.
 *
 * EXHAUSTIVE `when` ON A SEALED INTERFACE, with no `else` — the same discipline `DwReportBlock`
 * keeps, and for the same reason. A seventeenth block type must fail to compile here rather than
 * quietly inheriting "never divided, never kept with anything", which would put a heading at the
 * bottom of a page and its section at the top of the next one with nothing to say it had happened.
 */
fun dwBlockRule(block: Block): DwBlockRule = when (block) {
    // `_block_cover` ends with `self._new_page()`, and no writer draws a running head or foot on
    // page one — which is why the cover gets its own, taller, content box.
    is CoverBlock -> DwBlockRule(isCover = true)

    // `_block_toc` follows the contents with `_new_page`: chapter one begins on its own page.
    is TocBlock -> DwBlockRule(breakAfter = true)

    // `_block_heading` reserves its lead, its own lines, its trail AND one body line
    // (`self.base_size * 1.32`) before it will draw — "a heading with nothing under it is still an
    // orphan even if it fits".
    is HeadingBlock -> DwBlockRule(keepWithNext = true)

    // `_draw_lines` calls `_ensure(line.height)` once per line.
    is ParagraphBlock -> DwBlockRule(splittable = true)

    // `_block_bullets` places one item at a time.
    is BulletListBlock -> DwBlockRule(splittable = true)

    // `_simple_grid` places one pair at a time.
    is KeyValueBlock -> DwBlockRule(splittable = true)

    // `place_row` calls `place_header()` after `_new_page()`, "exactly as Word repeats a
    // `<w:tblHeader/>` row over a body row it has split".
    is TableBlock -> DwBlockRule(splittable = true, repeatsHeader = true)

    // `_block_image_grid` loops `range(0, n, cols)` and `_ensure`s one grid row at a time.
    is ImageGridBlock -> DwBlockRule(splittable = true)

    // One `_cut_row` over the whole flow, whose docstring calls the fits-a-page case "an ordinary
    // break": a callout that would sit happily on the next page is MOVED, not cut in half.
    is CalloutBlock -> DwBlockRule(splittable = true, preferWhole = true)

    // `_draw_image` reserves the whole box; `_block_figure` scales a chart or a map to fit rather
    // than dividing it; `_block_metrics` and `_block_signatures` `_ensure` their whole height inside
    // `_locked`. None of these may be cut, and a spacer has nothing to cut.
    is ImageBlock -> DwBlockRule()
    is ChartBlock -> DwBlockRule()
    is MapBlock -> DwBlockRule()
    is MetricRowBlock -> DwBlockRule()
    is SignatureBlock -> DwBlockRule()
    is SpacerBlock -> DwBlockRule()

    // A `PAGEBREAK` never becomes a FlowItem at all — [dwPlanFlow] folds it onto the block that
    // follows it as `breakBefore`. This arm exists so the `when` stays exhaustive.
    is PageBreakBlock -> DwBlockRule()
}

/**
 * The measured blocks, as [packPages] wants them.
 *
 * @param gapPx the space between two blocks in the flow, in reference units. It is the constant the
 *   drawing code lays the flow out with rather than a number guessed here, so measurement and
 *   drawing cannot drift.
 * @param heightOf the measured height of the block at that index, or null if it has not been
 *   measured yet. ONE missing height abandons the whole plan and returns an empty list: a document
 *   paginated from a partial measurement would show page counts that shuffle as the last photograph
 *   decodes, and a reader who saw "12 pages" and then "14" has been told a number twice.
 * @param offsetsOf the block's legal cut points, from its own top.
 * @param headerOf the height of the header a continuation redraws, for a block whose rule repeats one.
 */
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
                // A page break eats the gap, exactly as `_new_page` resets the cursor to the top
                // margin — so the gap is only ever charged mid-page, which [packPages] decides.
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

    // A TRAILING `PAGEBREAK` PRODUCES NO BLANK FINAL SHEET, and `pendingBreak` is deliberately
    // dropped here rather than turned into an empty page. `_new_page()` in the .pdf would emit one;
    // no template in `ReportTemplates.kt` ends with a break, and a blank sheet at the end of a
    // preview reads as a fault in the preview rather than as a fact about the document. The web's
    // `planFlow` carries the same decision, in the same words.
    return items
}
